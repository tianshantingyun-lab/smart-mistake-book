package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.PersistedLearningLedgerEvent
import com.tingyun.smartmistakebook.core.database.ProjectionCasConflictException
import com.tingyun.smartmistakebook.core.database.ProjectionCommitMode
import com.tingyun.smartmistakebook.core.database.ProjectionDrainBudgetExhaustedException
import com.tingyun.smartmistakebook.core.database.ProjectionReplayLimitExceededException
import com.tingyun.smartmistakebook.core.domain.LearningProjector
import com.tingyun.smartmistakebook.core.model.ChatEvidenceSubmitted
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **全量重放与逐条增量投影必须落到同一个状态。**
 *
 * 消灭的失败：投影版本升级（`LearningProjector.VERSION` bump）强制所有既有安装走
 * `commitFullReplay` 的 `replay`，而此后每一天都走 `project` 的增量路径。若两条路
 * 对同一份账本算出不同状态，老用户升级后要么拿到修复前的旧值、要么拿到一个只存在于
 * 重放里的状态，而**下一次增量提交会把差异抹平成一个谁也没算过的第三种状态**——
 * 差异本身不可见，只有在用户看到错误掌握度时才显形。这是「升级路径」的基本假设，
 * 也是它必须在批 0 被钉住的原因。
 *
 * **定义域**（决策 D-5）：不含修正的账本。修正结构上进不了增量路径
 * （批次加载器一读到修正行就要求全量重放），因此它不属于「不等价」，而是
 * 「增量路径不存在」——那一半由 [aCorrectionInThePageEscalatesToFullReplay]
 * 和 ADR-0002 承担。
 *
 * 等价不是靠"两边都调同一个函数"得来的：`replay` 在内存里对整份账本跑两遍
 * （第一遍收集修正、第二遍按原位置追溯生效），`project` 只对一页事件跑一遍、
 * 并把累积态交回持久层。断言因此必须是逐字段的，且必须让两条路真的走过那些会
 * 分叉的分支——见每条用例对"这条形状真的被走到了"的旁证。
 */
class StudyProjectionDrainerEquivalenceTest {

    private val learnerId = "learner:local"

    // ------------------------------------------------------------------ 等价

    @Test
    fun fullReplayEqualsStepByStepIncrementalAccumulation() = runBlocking {
        val ledger = mixedLedger()

        val replayed = drain(
            LedgerProjectionPort(
                learnerId = learnerId,
                ledgerEvents = ledger,
                // 版本不匹配的空快照 = 真实升级场景：既有安装被要求重算。
                current = persistedSnapshot(LearnerSnapshot.empty(learnerId, "unprojected-v6")),
                pageSize = ledger.size,
            ),
        )!!.snapshot

        val byOne = drain(
            LedgerProjectionPort(learnerId = learnerId, ledgerEvents = ledger, pageSize = 1),
        )!!.snapshot
        val bySeven = drain(
            LedgerProjectionPort(learnerId = learnerId, ledgerEvents = ledger, pageSize = 7),
        )!!.snapshot
        val byProductionPage = drain(
            LedgerProjectionPort(learnerId = learnerId, ledgerEvents = ledger, pageSize = 100),
        )!!.snapshot

        // 这条账本真的走满了四种事件与两条相反的因果顺序，不是一条"什么也没发生"的账本。
        assertTrue("夹具必须真的产生状态，否则等价性断言是空的", replayed.checkpoint.lastSequence == ledger.size.toLong())
        assertEquals(
            setOf("kc-monotonicity", "kc-induction"),
            replayed.knowledgeMasteryStates.keys,
        )
        assertEquals(setOf("unit-1", "unit-2"), replayed.problemMemoryStates.keys)
        assertEquals(2, replayed.appliedAnswerRevealRecords.size)
        assertEquals(2, replayed.appliedTutorAnswerExposureRecords.size)
        // 追踪呈现因果的两条分支都被走到：seq3 的曝光在作答之后（被抑制），
        // seq8 的曝光在 seq9 的作答之前（真的改了记忆，见 answerRevealCount 的 +1）。
        assertEquals(setOf("attempt-1", "attempt-2", "attempt-3", "attempt-4", "attempt-5"), replayed.appliedAttemptRecords.keys)
        assertEquals(1, replayed.problemMemoryStates.getValue("unit-1").answerRevealCount)

        assertEquals("逐条增量累积必须与一次性全量重放逐字段相等", replayed, byOne)
        assertEquals("切分位置不得影响结果", replayed, bySeven)
        assertEquals("生产页大小（100）不得影响结果", replayed, byProductionPage)
    }

    /**
     * 两条路由**不同代码**算出这三个字段，因此单独点出来，让读者看见它们为什么相同：
     * 重放恒取账本末条序列为头、时间线从 0 起，增量路径的头来自批次、时间线承接上一版快照。
     * 这里没有任何墙钟——两条路的时间线都是事件时间戳的最大值。
     */
    @Test
    fun replayedAndIncrementalAgreeOnTheDerivedCheckpointFields() = runBlocking {
        val ledger = mixedLedger()
        val replayed = drain(
            LedgerProjectionPort(
                learnerId = learnerId,
                ledgerEvents = ledger,
                current = persistedSnapshot(LearnerSnapshot.empty(learnerId, "unprojected-v6")),
                pageSize = ledger.size,
            ),
        )!!.snapshot
        val incremental = drain(
            LedgerProjectionPort(learnerId = learnerId, ledgerEvents = ledger, pageSize = 1),
        )!!.snapshot

        assertEquals(replayed.knownLedgerHeadSequence, incremental.knownLedgerHeadSequence)
        assertEquals(replayed.knownLedgerHeadSequence, ledger.size.toLong())
        assertEquals(replayed.checkpoint, incremental.checkpoint)
        assertEquals(replayed.checkpoint.projectedAtEpochMillis, incremental.generatedAtEpochMillis)
        // 无修正账本上，只有重放能写这两个字段，而它写的正是"没有"——所以两条路仍相等。
        assertNull(replayed.correctionWatermarkEpochMillis)
        assertTrue(replayed.appliedCorrectionRecords.isEmpty())
        assertTrue(incremental.appliedCorrectionRecords.isEmpty())
    }

    // -------------------------------------------------------- 修正走升级路径

    /**
     * 决策 D-9：批次里出现修正行 ⇒ 走全量重放。
     *
     * 这一半断的是 `StudyProjectionDrainer` 对 `FULL_REPLAY_REQUIRED` 的反应。
     * 另一半——"DAO 读到修正行确实返回该停止原因"——住在
     * `ProjectionTransactionDao.loadProjectionBatch`（`ProjectionTransactionDao.kt:475-486`）
     * 的 SQL 循环里，只能在仪器化测试里验证，本机不跑：
     * **UNVERIFIED（待补 `:core:database:connectedDebugAndroidTest`）**。
     * 替身按同一语义返回该停止原因，所以本用例证明的是"给定 DAO 的承诺，drainer 兑现它"。
     */
    @Test
    fun aCorrectionInThePageEscalatesToFullReplay() = runBlocking {
        val ledger = listOf(
            ledgerEvent(sampleAttempt("attempt-1", 1, setOf("kc-monotonicity"))),
            ledgerEvent(sampleCorrection("attempt-1", sequence = 2, replacementEvidence = negativeEvidence())),
        )
        val port = LedgerProjectionPort(learnerId = learnerId, ledgerEvents = ledger)

        val drained = drain(port)!!.snapshot

        assertEquals(
            "修正行必须逼出全量重放，绝不能做增量提交",
            listOf(ProjectionCommitMode.FULL_REPLAY),
            port.commits.map { it.mode },
        )
        assertEquals(
            "重放必须让修正追溯生效：被修正的作答改成负向证据",
            LearningEvidenceDirection.NEGATIVE.name,
            drained.knowledgeMasteryStates.getValue("kc-monotonicity").lastEvidenceDirection,
        )
        assertEquals(setOf("attempt-1"), drained.appliedAttemptRecords.keys)
        assertEquals(setOf("correction-attempt-1"), drained.appliedCorrectionRecords.keys)
        assertEquals(2L, drained.checkpoint.lastSequence)
    }

    // ------------------------------------------------------------ 记录表封顶

    /**
     * 决策 D-11：跨批累积的记录表封顶。
     *
     * `MAX_APPLIED_RECORDS = 4_096` 在两条路上被**不同的时机**施加：重放是"攒满 4_097 条再一次裁"，
     * 增量是"每次提交各裁一次"。若裁剪口径不是"保留序列号最大的那批"，两者会分叉——
     * 而分叉的后果是同一台设备在不同升级时机拿到不同的记录表，进而让重投的作答
     * 在一条路上被判重复、在另一条路上被判冲突。
     *
     * 用 4_097 条而不是更多，是为了同时钉住三件事：上界恰好是 4_096、被丢掉的是最旧的那条、
     * 两条路的保留窗口一致。任一处写偏（`take(4_095)`、`take(4_097)`、先裁剪再排序）都会让
     * 下面第一条断言变红。
     */
    @Test
    fun theAppliedRecordCapRetainsTheSameWindowUnderAnyBatchSize() = runBlocking {
        val count = 4_097
        val ledger = (1..count).map { sequence ->
            ledgerEvent(sampleTutorExposure(id = "$sequence", sequence = sequence.toLong()))
        }
        val expectedSequences = (2L..count.toLong()).toList()

        val oneShot = drain(
            LedgerProjectionPort(learnerId = learnerId, ledgerEvents = ledger, pageSize = count),
        )!!.snapshot
        // 生产页大小：这次账本被切成 41 批，封顶在每一批后各施加一次。
        val batched = drain(
            LedgerProjectionPort(learnerId = learnerId, ledgerEvents = ledger, pageSize = 100),
        )!!.snapshot

        assertEquals(
            "上界必须是 4_096 条、且丢掉的是序列号最小的那条",
            expectedSequences,
            batched.appliedTutorAnswerExposureRecords.values.map { it.eventSequence }.sorted(),
        )
        assertEquals("分批累积与一次性重放必须保留同一个窗口", oneShot, batched)
    }

    // -------------------------------------------------------------- 重放上界

    /**
     * 决策 D-13／D-14：全量重放有上界，超限**显式失败并保留旧检查点**。
     *
     * 消灭的失败：账本无界、永不裁剪（§12.6），而 `replay` 必须把整份账本一次持在内存里
     * （ADR-0002 说明它为什么不能分块）。没有这条上界，撞上内存墙的唯一表现是一次
     * 随时可能发生在任何时刻的 OOM——用户看到的是进程被杀，而**旧检查点是否还完好
     * 无从判断**。有了它，超限是一次可命名的失败，且失败前不落任何东西。
     *
     * 用小上界（3）而不是产线的 10⁵ 来测：边界行为与上界的**大小**是两件事，
     * 后者由 [theReplayLimitIsAboveTheDrainBoundAndBelowTheOomHorizon] 单独钉住。
     * 构造器因此留了一个带产线默认值的上界参数——它存在的唯一理由是让这条边界可测。
     */
    @Test
    fun aLedgerOverTheReplayLimitFailsExplicitlyWithoutCommitting() = runBlocking {
        val ledger = (1..4).map { sequence ->
            ledgerEvent(sampleTutorExposure(id = "$sequence", sequence = sequence.toLong()))
        }
        val seed = persistedSnapshot(LearnerSnapshot.empty(learnerId, "unprojected-v6"))
        val limit = 3

        // 上界之内：照常全量重放并提交。
        val withinLimit = LedgerProjectionPort(
            learnerId = learnerId,
            ledgerEvents = ledger.take(limit),
            current = seed,
            pageSize = limit,
        )
        assertEquals(limit.toLong(), drainWithLimit(withinLimit, limit)!!.snapshot.checkpoint.lastSequence)

        // 上界之外：一次提交都不能有，旧检查点原样留在 0。
        val overLimit = LedgerProjectionPort(
            learnerId = learnerId,
            ledgerEvents = ledger,
            current = seed,
            pageSize = ledger.size,
        )
        val thrown = assertThrows(ProjectionReplayLimitExceededException::class.java) {
            runBlocking { drainWithLimit(overLimit, limit) }
        }

        assertTrue(
            "异常必须说清需要多少条、上界是多少：${thrown.message}",
            thrown.message!!.contains("4") && thrown.message!!.contains("3"),
        )
        // 决策 D-13 的「不继承 CAS」目前**由语言本身保证**：`ProjectionCasConflictException`
        // 是 final 类，变异成继承它根本编译不过（`This type is final, so it cannot be extended`），
        // 所以这条断言不是因为类型现在是安全的才多余——它守的是**将来**：谁若把这个异常
        // 做成 open（N-06 要改的正是同一处类型层级），这行会立刻红。
        assertFalse(
            "不得继承 ProjectionCasConflictException（决策 D-13）：那会让 drain 把它当成" +
                "可重试的检查点竞争，重试同一份账本只会得到同一个结果。" +
                "实际类型：${thrown::class.qualifiedName}",
            ProjectionCasConflictException::class.java.isAssignableFrom(thrown::class.java),
        )
        assertTrue(
            "超限时不得提交任何东西，否则一个不完整的结果会落库",
            overLimit.commits.isEmpty(),
        )
        assertEquals(
            "旧检查点必须原样保留，恢复路径才可能成立",
            0L,
            overLimit.current?.snapshot?.checkpoint?.lastSequence,
        )
    }

    // -------------------------------------------------------------- 排空预算

    /**
     * 审计 N-06：步数预算用尽时，说的是「这一次没走完」，**不是**「检查点被别的写者改过」。
     *
     * 原错法（一手探针实测：账本 6_401 条、生产页大小 100）：`drain()` 的
     * `repeat(MAX_PROJECTION_DRAIN_STEPS = 64)` 走完后抛 `ProjectionCasConflictException`，
     * 于是 64 次**已经落库**的提交被报成一次检查点竞争——而那个检查点在本调用里根本没被
     * 别的写者动过。6_400 条积压对正常使用的重度用户是可达的（每天 20 题 ≈ 一年 7_300 条），
     * 用户会看到一次「投影更新失败」，排查者会去找一个不存在的并发。
     *
     * 三条断言合起来才等于「这条失败出口说对了话」：
     * 1. 类型是预算用尽，且**不是** CAS 冲突那一族；
     * 2. 已提交的推进**留在库里**——与上面 `aLedgerOverTheReplayLimitFailsExplicitly…`
     *    的「旧检查点原样保留」正相反，因为"下次接着走"正是这条失败的出路；
     * 3. 同一份账本、同一个替身，只把预算放大就能排完——证明挡住它的是预算，不是数据。
     */
    @Test
    fun runningOutOfDrainStepsReportsItselfAndKeepsTheCommittedProgress() = runBlocking {
        val ledger = (1..5).map { sequence ->
            ledgerEvent(sampleTutorExposure(id = "$sequence", sequence = sequence.toLong()))
        }
        // 一步一页：这份账本要 5 步才追平，预算只给 2 步。
        val port = LedgerProjectionPort(learnerId = learnerId, ledgerEvents = ledger, pageSize = 1)

        val thrown = assertThrows(ProjectionDrainBudgetExhaustedException::class.java) {
            runBlocking { drainWithSteps(port, steps = 2) }
        }

        assertFalse(
            "预算用尽不得复用 CAS 冲突的语义（N-06）：检查点在本次调用里没有被别的写者动过。" +
                "实际类型：${thrown::class.qualifiedName}",
            ProjectionCasConflictException::class.java.isAssignableFrom(thrown::class.java),
        )
        assertTrue(
            "异常必须点明是「步数预算」，否则日志里与并发竞争分辨不开：${thrown.message}",
            thrown.message!!.contains("2") && thrown.message!!.contains("budget"),
        )
        assertEquals(
            "两次提交都已独立落库——这正是「下次接着走」成立的前提",
            2,
            port.commits.size,
        )
        assertEquals(
            "检查点必须停在已推进到的位置，不得回退",
            2L,
            port.current?.snapshot?.checkpoint?.lastSequence,
        )

        // 只换预算，不换数据：够用的预算必须能排完。
        assertEquals(
            "预算够时同一份账本必须排完（否则挡住它的就不是预算）",
            5L,
            drainWithSteps(port, steps = 5)!!.snapshot.checkpoint.lastSequence,
        )

        // **边界：预算恰好等于页数时也会抛**，而这时工作其实已经全部提交——循环靠"读到一批
        // 空批次"确认追平，最后一页的停止原因仍是 `LIMIT_REACHED`，所以 5 步只能确认 4 页。
        // 这一格是异常文案必须说「没看到空批次」而不能说「没追平」的原因：
        // 后者在这一格上是假话（检查点已经是账本头），而它又不是崩溃——只是白报一次失败。
        val exactBudget = LedgerProjectionPort(
            learnerId = learnerId,
            ledgerEvents = ledger,
            pageSize = 1,
        )
        assertThrows(ProjectionDrainBudgetExhaustedException::class.java) {
            runBlocking { drainWithSteps(exactBudget, steps = ledger.size) }
        }
        assertEquals(
            "预算恰好等于页数时，这一份账本其实已经全部提交",
            ledger.size.toLong(),
            exactBudget.current?.snapshot?.checkpoint?.lastSequence,
        )
        assertEquals(
            "重试必须立刻返回、不再做任何工作——否则「可重试」只是说说",
            ledger.size.toLong(),
            drainWithSteps(exactBudget, steps = ledger.size)!!.snapshot.checkpoint.lastSequence,
        )
    }

    /**
     * 决策 D-14 的取值口径在代码里留一条断言，并且**两个方向都钉**：
     *
     * - 下界：必须高于增量路径一次排空能**确认**处理的量。这个量是 `(MAX_PROJECTION_DRAIN_STEPS − 1) ×
     *   PROJECTION_BATCH_SIZE` = 63 × 100 = **6_300** 条：循环靠"读到一批空批次"确认追平，
     *   最后一页的停止原因仍是 `LIMIT_REACHED`，所以 k 步只能确认 k−1 页。
     *   低于它，一个今天增量投影得好好的安装，投影版本一升就变成永久硬故障。
     *   （断言仍写在 6_400 上——那是**更松**的一侧，取更松的一侧只会更安全，
     *   而这条口径由 N-06 的边界用例单独钉住。）
     * - 上界：必须落在"防 OOM"的量级。一个在 OOM 之后才触发的上界不是上界——
     *   那只是让代码看起来有保护（决策 D-14 明确拒绝这个做法）。
     *
     * 这条断言**故意**是数字的：它守的是一个由用户拍板的取值决定，
     * 而"看起来更安全"的小数字（例如同族的 4_096）正是它要拦下的改动。
     */
    @Test
    fun theReplayLimitIsAboveTheDrainBoundAndBelowTheOomHorizon() {
        assertTrue(
            "上界必须高于增量路径一次排空的量（6_400 条），否则正常安装会被升级路径打死",
            MAX_FULL_REPLAY_EVENTS > 6_400,
        )
        assertTrue(
            "上界不能小到「看起来有保护」的程度（同族的 4_096 量级）",
            MAX_FULL_REPLAY_EVENTS >= 100_000,
        )
        assertTrue(
            "上界也不能大到在 OOM 之后才触发，那样它就不是上界",
            MAX_FULL_REPLAY_EVENTS <= 1_000_000,
        )
    }

    // ------------------------------------------------------------------ 夹具

    private suspend fun drain(port: LedgerProjectionPort) =
        StudyProjectionDrainer(
            database = port,
            learnerId = learnerId,
            learningProjector = LearningProjector(),
        ).drain()

    /** 只有在验证上界本身时才收紧它；其余用例一律走产线默认值。 */
    private suspend fun drainWithLimit(port: LedgerProjectionPort, limit: Int) =
        StudyProjectionDrainer(
            database = port,
            learnerId = learnerId,
            learningProjector = LearningProjector(),
            maxFullReplayEvents = limit,
        ).drain()

    /** 只有在验证排空预算时才收紧它；其余用例一律走产线默认值（64 步）。 */
    private suspend fun drainWithSteps(port: LedgerProjectionPort, steps: Int) =
        StudyProjectionDrainer(
            database = port,
            learnerId = learnerId,
            learningProjector = LearningProjector(),
            maxDrainSteps = steps,
        ).drain()

    /**
     * 一条走满四种事件、两条相反因果顺序的账本。
     *
     * seq3 的曝光排在它的作答（seq1）之后 → `applyRevealCausality` 不该改动作答，
     * 且不能二次衰减记忆；seq8 的曝光排在它的作答（seq9）之前 → 作答的证据方向被
     * 改写成 `ANSWER_REVEALED` 并抑制记忆写入。这两条分支的分叉正是"重放两遍 vs
     * 增量一遍"最容易不一致的地方，因此夹具必须同时包含它们。
     */
    private fun mixedLedger(): List<PersistedLearningLedgerEvent> = listOf(
        ledgerEvent(sampleAttempt("attempt-1", 1, setOf("kc-monotonicity", "kc-induction"))),
        ledgerEvent(sampleChatEvidence(sequence = 2)),
        ledgerEvent(sampleAnswerReveal(presentationId = "presentation-attempt-1", sequence = 3)),
        ledgerEvent(
            sampleAttempt(
                id = "attempt-2",
                sequence = 4,
                knowledgeNodeIds = setOf("kc-monotonicity"),
                presentationId = "presentation-b",
            ),
        ),
        ledgerEvent(sampleTutorExposure(id = "1", sequence = 5)),
        ledgerEvent(
            sampleAttempt(
                id = "attempt-3",
                sequence = 6,
                knowledgeNodeIds = setOf("kc-monotonicity"),
                // 同一呈现的第二次作答：构造器禁止它带独立证据（`LearningState.kt:278`）。
                evidence = hintedIncorrectEvidence(),
                presentationId = "presentation-b",
                responseOrdinal = 2,
            ),
        ),
        ledgerEvent(
            sampleChatEvidence(
                sequence = 7,
                direction = LearningEvidenceDirection.NEGATIVE,
                weight = ChatEvidenceSubmitted.NEGATIVE_WEIGHT,
            ),
        ),
        ledgerEvent(sampleAnswerReveal(presentationId = "presentation-c", sequence = 8)),
        ledgerEvent(
            sampleAttempt(
                id = "attempt-4",
                sequence = 9,
                knowledgeNodeIds = setOf("kc-induction"),
                presentationId = "presentation-c",
            ),
        ),
        ledgerEvent(sampleTutorExposure(id = "2", sequence = 10, practiceUnitId = "unit-2")),
        ledgerEvent(
            sampleAttempt(
                id = "attempt-5",
                sequence = 11,
                knowledgeNodeIds = setOf("kc-induction"),
                evidence = hintedIncorrectEvidence(),
                presentationId = "presentation-c",
                responseOrdinal = 2,
            ),
        ),
        ledgerEvent(sampleChatEvidence(sequence = 12, knowledgeNodeId = "kc-induction")),
    )
}
