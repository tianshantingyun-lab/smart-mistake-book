package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

/**
 * Behavior tests for the FSRS-6 projection (spec mastery-scheduling §2.4,
 * §2.15, §2.16, §2.10) and the legacy kill-switch equivalence.
 */
class FsrsProjectionBehaviorTest {

    private val projector = LearningProjector()
    private val legacyProjector = LearningProjector(
        forgettingCurve = ForgettingCurve(),
        memoryUpdateModel = LegacyExponentialMemoryUpdateModel(),
    )

    @Test
    fun `first review seeds initial stability and difficulty for its rating`() {
        val result = projector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(attempt("a-1", 1, easyEvidence())),
            1,
        )
        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(FsrsScheduleMath.initialStability(FsrsRating.GOOD), memory.stabilityDays, 1e-9)
        assertEquals(
            FsrsScheduleMath.clampDifficulty(FsrsScheduleMath.initialDifficulty(FsrsRating.GOOD)),
            memory.difficulty,
            1e-9,
        )
        assertEquals(1, memory.consecutiveCrossDaySuccess)
        assertEquals(LearningEvidenceReason.INDEPENDENT_CORRECT.name, memory.lastEvidenceReason)
        assertEquals(LearningEvidenceDirection.POSITIVE.name, memory.lastEvidenceDirection)
    }

    @Test
    fun `same day review takes the short term branch and never grows stability for good`() {
        val first = projector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(attempt("a-1", 1, easyEvidence(), occurredAt = DAY_MILLIS)),
            1,
        ).snapshot
        val firstStability = first.problemMemoryStates.getValue("unit-1").stabilityDays

        val second = projector.project(
            first,
            listOf(attempt("a-2", 2, easyEvidence(), occurredAt = DAY_MILLIS + 60_000)),
            2,
        )
        val memory = second.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(
            FsrsScheduleMath.shortTermStability(firstStability, FsrsRating.GOOD),
            memory.stabilityDays,
            1e-9,
        )
        // Same-day repeats never reach the cross-day long-run branch.
        assertEquals(1, memory.consecutiveCrossDaySuccess)
    }

    @Test
    fun `cross midnight review under 24 wall clock hours still counts as a new day`() {
        val first = projector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(attempt("a-1", 1, easyEvidence(), occurredAt = DAY_MILLIS)),
            1,
        ).snapshot
        val firstStability = first.problemMemoryStates.getValue("unit-1").stabilityDays

        // Second review is only 30 wall-clock minutes later (DAY_MILLIS + 60_000), but its
        // learner-local calendar day is the next day (epoch day 2) — a cross-midnight review that
        // must take the long-run branch, not the same-day short-term branch.
        val crossingMidnight = attempt("a-2", 2, easyEvidence(), occurredAt = DAY_MILLIS + 60_000)
            .copy(studyDay = StudyDayContext(epochDay = 2, timeZoneId = "Asia/Shanghai", utcOffsetMinutes = 480))
        val second = projector.project(first, listOf(crossingMidnight), 2)
        val memory = second.snapshot.problemMemoryStates.getValue("unit-1")

        // Cross-day success must advance the streak (same-day would keep it at 1).
        assertEquals(2, memory.consecutiveCrossDaySuccess)
        // The long-run recall branch grows stability past the short-term floor; the same-day
        // branch for Good would leave it at the short-term value (which equals the seed here).
        assertTrue(
            "cross-midnight Good must grow stability via the long-run branch, got ${memory.stabilityDays}",
            memory.stabilityDays > firstStability,
        )
    }

    @Test
    fun `cross day again counts toward the leech streak and lowers stability`() {
        val seeded = seededCrossDay()
        val lastReviewedAt = seeded.memory.lastReviewedAtEpochMillis
        val lapseAt = lastReviewedAt + 3 * DAY_MILLIS
        val result = projector.project(
            seeded.snapshot,
            listOf(
                attempt(
                    "a-lapse",
                    4,
                    wrongEvidence(),
                    occurredAt = lapseAt,
                ),
            ),
            4,
        )
        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(1, memory.consecutiveCrossDayAgain)
        assertTrue(
            memory.stabilityDays <=
                seeded.memory.stabilityDays / exp(
                    FsrsScheduleMath.DEFAULT_PARAMETERS[17] * FsrsScheduleMath.DEFAULT_PARAMETERS[18],
                ) + 1e-9,
        )
    }

    @Test
    fun `cross day again resets the graduation success streak`() {
        // Seed one cross-day success, then lapse on the next cross-day review:
        // the success streak must reset to zero — two further successes must
        // NOT read as "three in a row" (spec §2.10 counts consecutive
        // cross-day successes; a lapse breaks the run).
        val seeded = seededCrossDay()
        val lapseAt = seeded.memory.lastReviewedAtEpochMillis + 3 * DAY_MILLIS
        val lapsed = projector.project(
            seeded.snapshot,
            listOf(attempt("a-lapse", 4, wrongEvidence(), occurredAt = lapseAt)),
            4,
        )
        val afterLapse = lapsed.snapshot.problemMemoryStates.getValue("unit-1")
        assertEquals("a cross-day Again must clear the success streak", 0, afterLapse.consecutiveCrossDaySuccess)
        assertEquals(1, afterLapse.consecutiveCrossDayAgain)

        // Drive the card through two further cross-day successes (each review
        // explicitly on a fresh calendar day after the lapse). If the streak
        // had not been reset by the lapse, the second success would already
        // read as three in a row and trigger graduation.
        val lapseEpochDay = lapseAt / DAY_MILLIS
        var snapshot = lapsed.snapshot
        for (index in 1..2) {
            val occurredAt = lapseAt + index * DAY_MILLIS
            val crossDayAttempt = attempt(
                "a-recover-$index",
                4L + index,
                easyEvidence(),
                occurredAt = occurredAt,
            ).copy(
                studyDay = StudyDayContext(
                    epochDay = lapseEpochDay + index,
                    timeZoneId = "UTC",
                    utcOffsetMinutes = 0,
                ),
            )
            val result = projector.project(snapshot, listOf(crossDayAttempt), 4L + index)
            snapshot = result.snapshot
        }
        val recovered = snapshot.problemMemoryStates.getValue("unit-1")
        // Exactly two successes after the lapse; graduation needs three.
        assertEquals(2, recovered.consecutiveCrossDaySuccess)
        assertEquals(0, recovered.consecutiveCrossDayAgain)
    }

    @Test
    fun `leech freezes difficulty at its ceiling`() {
        val leeched = seededCrossDay().memory.copy(
            lapseCount = ProblemMemoryState.LEECH_LAPSE_THRESHOLD,
            consecutiveCrossDayAgain = ProblemMemoryState.LEECH_AGAIN_STREAK,
            difficulty = 8.0,
        )
        val snapshot = LearnerSnapshot(
            learnerId = "learner-1",
            problemMemoryStates = mapOf("unit-1" to leeched),
            checkpoint = ProjectionCheckpoint(10, LearningProjector.VERSION, 10 * DAY_MILLIS),
            generatedAtEpochMillis = 10 * DAY_MILLIS,
        )

        val result = projector.project(
            snapshot,
            listOf(
                attempt(
                    "a-lapse",
                    11,
                    wrongEvidence(),
                    occurredAt = leeched.lastReviewedAtEpochMillis + 3 * DAY_MILLIS,
                ),
            ),
            11,
        )
        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")

        assertTrue(memory.isLeeched)
        // Spec 2.16: a leeched card's difficulty may not climb further, even
        // though the Again rating would normally push it up.
        assertEquals(8.0, memory.difficulty, 1e-9)
    }

    @Test
    fun `three cross day successes with a ninety day interval schedule maintenance`() {
        var snapshot = LearnerSnapshot.empty("learner-1")
        var sequence = 0L
        // Drive the card through enough successful cross-day reviews for a
        // ninety-day interval; the graduation override must then kick in.
        var nextAt = 0L
        for (index in 1..14) {
            sequence += 1
            val occurredAt = if (index == 1) 0 else nextAt
            val result = projector.project(
                snapshot,
                listOf(attempt("a-$index", sequence, easyEvidence(), occurredAt = occurredAt)),
                sequence,
            )
            snapshot = result.snapshot
            val memory = snapshot.problemMemoryStates.getValue("unit-1")
            nextAt = memory.nextReviewAtEpochMillis
            if (memory.consecutiveCrossDaySuccess >= 3) {
                val regularInterval = FsrsScheduleMath.intervalDays(
                    memory.stabilityDays,
                    FsrsMemoryUpdateModel.DEFAULT_DESIRED_RETENTION,
                )
                if (regularInterval >= 90) {
                    val maintenanceInterval = FsrsScheduleMath.intervalDays(
                        memory.stabilityDays,
                        LearningProjector.GRADUATION_TARGET_RETENTION,
                    )
                    val scheduledInterval =
                        (memory.nextReviewAtEpochMillis - occurredAt) / DAY_MILLIS
                    assertEquals(maintenanceInterval.toLong(), scheduledInterval)
                    assertTrue(maintenanceInterval >= regularInterval)
                    return
                }
            }
        }
        throw AssertionError("graduation never scheduled a maintenance interval")
    }

    /**
     * F-01 的第 ⑤ 处（**接线**那一半）：毕业分支写给 `nextReviewAt` 的那一天必须按
     * **投影器手里那个模型的衰减**算，而不是按 `FsrsScheduleMath` 的出厂默认。
     *
     * 上面那条毕业用例证明的是"毕业分支算得对"（两边都用出厂衰减，所以它两条曲线都能过）；
     * 这一条把夹具换成**只有 `w20` 不同**的模型与曲线，于是它只在"接线真的发生了"时才成立。
     * 夹具自带一条反向断言：若两种衰减算出的维护间隔相同，说明这一格分辨不出差异，用例作废。
     */
    @Test
    fun `graduation maintenance follows the fitted decay of its model`() {
        val fittedParameters = FsrsScheduleMath.DEFAULT_PARAMETERS.copyOf().also { it[20] = 0.4 }
        val fittedProjector = LearningProjector(
            forgettingCurve = ForgettingCurve(
                algorithm = ForgettingCurveAlgorithm.FSRS6_POWER_LAW,
                decay = -fittedParameters[20],
            ),
            memoryUpdateModel = FsrsMemoryUpdateModel(parameters = fittedParameters),
        )

        var snapshot = LearnerSnapshot.empty("learner-1")
        var sequence = 0L
        var nextAt = 0L
        for (index in 1..30) {
            sequence += 1
            val occurredAt = if (index == 1) 0 else nextAt
            val result = fittedProjector.project(
                snapshot,
                listOf(attempt("a-$index", sequence, easyEvidence(), occurredAt = occurredAt)),
                sequence,
            )
            snapshot = result.snapshot
            val memory = snapshot.problemMemoryStates.getValue("unit-1")
            nextAt = memory.nextReviewAtEpochMillis
            if (memory.consecutiveCrossDaySuccess >= 3) {
                val regularInterval = FsrsScheduleMath.intervalDays(
                    memory.stabilityDays,
                    FsrsMemoryUpdateModel.DEFAULT_DESIRED_RETENTION,
                    -fittedParameters[20],
                )
                if (regularInterval < 90) continue

                val scheduledInterval = (memory.nextReviewAtEpochMillis - occurredAt) / DAY_MILLIS
                val atFittedDecay = FsrsScheduleMath.intervalDays(
                    memory.stabilityDays,
                    LearningProjector.GRADUATION_TARGET_RETENTION,
                    -fittedParameters[20],
                )
                val atFactoryDecay = FsrsScheduleMath.intervalDays(
                    memory.stabilityDays,
                    LearningProjector.GRADUATION_TARGET_RETENTION,
                )
                assertNotEquals(
                    "夹具必须能分辨两种衰减，否则这条用例证明不了接线：S=${memory.stabilityDays}",
                    atFactoryDecay,
                    atFittedDecay,
                )
                assertEquals(
                    "维护间隔必须按模型的拟合衰减算，而不是按出厂默认",
                    atFittedDecay.toLong(),
                    scheduledInterval,
                )
                return
            }
        }
        throw AssertionError("graduation never scheduled a maintenance interval")
    }

    /**
     * F-01 的第 ⑤ 处（**闸门**那一半，与上面那条接线用例互补）：
     * 判「常规间隔够不够 90 天」这一步也必须按**模型自己那组参数**的衰减算。
     *
     * 为什么它要单独一条，而且**必须把保持率目标设在 0.9 以外**：
     * `intervalDays(S, 0.9, 任意衰减) == S`——FACTOR 就是按 `R(S,S)=0.9` 定义的，所以
     * 目标保持率正好取 0.9 时间隔与衰减**恒等无关**（`FsrsScheduleMath.intervalDays` 的
     * KDoc 里记着这条性质）。第一版的变异 M7（把闸门的衰减换回出厂默认）之所以一条都没打红，
     * 不是因为哪条用例写得松，而是因为**闸门在生产默认值 0.9 下本来就分辨不出衰减**：
     * 实测两种衰减都给出 89 天 / 93 天，逐位相同。
     *
     * 而 0.9 只是**默认值**，不是唯一取值：设置页把 `desiredRetention` 做成 0.7..0.97 的滑杆，
     * 生产装配点（`RoomBackedStudyExperienceRepository`）把它原样交给这个模型。用户一旦拖动过
     * 那根滑杆，闸门的衰减就开始决定"这次复习算不算毕业"。所以这条用例取 0.75
     * （滑杆区间内的用户可达取值），并挑**两种衰减对闸门判断相反**的那一格：
     * 拟合衰减说"还不够 90 天"、出厂衰减说"够了"。此时正确行为是**不毕业**，
     * 写在 `nextReviewAt` 上的必须是常规间隔。
     *
     * 为什么不是 0.8——那是 `GRADUATION_TARGET_RETENTION`：闸门过与不过会写出**同一天**，
     * 于是"闸门读了哪条曲线"又一次不可观测（这条用例的第二版就栽在这里）。所以目标保持率
     * 必须同时避开 0.9 与 0.8 这两个"看不出差别"的取值，夹具里两条反向断言守的正是这件事。
     */
    @Test
    fun `graduation gate follows the fitted decay of its model`() {
        val fittedParameters = FsrsScheduleMath.DEFAULT_PARAMETERS.copyOf().also { it[20] = 0.4 }
        val fittedProjector = LearningProjector(
            forgettingCurve = ForgettingCurve(
                algorithm = ForgettingCurveAlgorithm.FSRS6_POWER_LAW,
                decay = -fittedParameters[20],
            ),
            memoryUpdateModel = FsrsMemoryUpdateModel(
                parameters = fittedParameters,
                desiredRetention = GATE_DESIRED_RETENTION,
            ),
        )
        val desired = GATE_DESIRED_RETENTION
        val probes = generateSequence(1.0) { it * 1.02 }
            .takeWhile { it <= 1_000_000.0 }
            .map { seedStabilityDays ->
                val closing = closingReview(fittedProjector, seedStabilityDays)
                val atFitted = FsrsScheduleMath.intervalDays(
                    closing.state.stabilityDays,
                    desired,
                    -fittedParameters[20],
                )
                val atFactory = FsrsScheduleMath.intervalDays(closing.state.stabilityDays, desired)
                StraddleProbe(seedStabilityDays, closing, atFitted, atFactory)
            }
            .toList()
        val straddle = probes.firstOrNull {
            it.atFitted < GRADUATION_MIN_INTERVAL_DAYS && it.atFactory >= GRADUATION_MIN_INTERVAL_DAYS
        } ?: throw AssertionError(
            "夹具没找到「两种衰减对 $GRADUATION_MIN_INTERVAL_DAYS 天闸门判断相反」的记忆强度，" +
                "这条用例在这种情况下证明不了闸门读的是哪条曲线。" +
                "最后一次仍判「不够」的探针：${probes.lastOrNull { it.atFactory < GRADUATION_MIN_INTERVAL_DAYS }}；" +
                "第一次判「够」的探针：${probes.firstOrNull { it.atFactory >= GRADUATION_MIN_INTERVAL_DAYS }}",
        )
        val closing = straddle.closing
        val atFitted = straddle.atFitted
        val atFactory = straddle.atFactory

        val maintenance = FsrsScheduleMath.intervalDays(
            closing.state.stabilityDays,
            LearningProjector.GRADUATION_TARGET_RETENTION,
            -fittedParameters[20],
        )
        assertTrue(
            "夹具必须能分辨「毕业 / 不毕业」两种结果，否则断言等于没断：常规 $atFitted 天 vs 维护 $maintenance 天",
            abs(atFitted - maintenance) > 2,
        )
        assertEquals(
            "闸门没过时必须留在常规排期上（闸门按拟合衰减判：$atFitted 天，" +
                "按出厂衰减会误判成 $atFactory 天而毕业）",
            atFitted.toLong(),
            (closing.state.nextReviewAtEpochMillis - closing.occurredAt) / DAY_MILLIS,
        )
    }

    /**
     * 从"差一次跨日成功就满毕业连胜"的状态出发，投影**那一次**成功的复习。
     * 返回投影后的记忆状态与这次复习发生的时刻。
     */
    private fun closingReview(
        projector: LearningProjector,
        seedStabilityDays: Double,
    ): ClosingReview {
        val seedReviewedAt = 100L * DAY_MILLIS
        val occurredAt = seedReviewedAt + 2 * DAY_MILLIS
        val seeded = LearnerSnapshot.empty("learner-1").copy(
            // 检查点必须与下面那条记忆状态的 checkpointSequence 对得上：
            // `LearnerSnapshot` 自己会校验"每条记忆状态都来自同一个检查点"，
            // 也会校验"账本头不早于检查点"。
            knownLedgerHeadSequence = 1,
            generatedAtEpochMillis = seedReviewedAt,
            checkpoint = ProjectionCheckpoint(
                lastSequence = 1,
                projectorVersion = LearningProjector.VERSION,
                projectedAtEpochMillis = seedReviewedAt,
            ),
            problemMemoryStates = mapOf(
                "unit-1" to ProblemMemoryState(
                    practiceUnitId = "unit-1",
                    stabilityDays = seedStabilityDays,
                    difficulty = 5.0,
                    lastReviewedAtEpochMillis = seedReviewedAt,
                    nextReviewAtEpochMillis = occurredAt,
                    // 差一次跨日成功就满毕业连胜。
                    consecutiveCrossDaySuccess = ProblemMemoryState.GRADUATION_SUCCESS_STREAK - 1,
                    lastAttemptId = "attempt:seed",
                    projectorVersion = LearningProjector.VERSION,
                    checkpointSequence = 1,
                ),
            ),
        )
        val result = projector.project(
            seeded,
            // 序号必须是检查点之后的那一个：投影器按"已应用到哪一条"跳过事件，
            // 用 1 会让这次复习被当成已投影过而整个跳过（下一次投影读到的还是播种状态）。
            listOf(attempt("a-closing", 2, easyEvidence(), occurredAt = occurredAt)),
            2,
        )
        return ClosingReview(result.snapshot.problemMemoryStates.getValue("unit-1"), occurredAt)
    }

    private data class ClosingReview(val state: ProblemMemoryState, val occurredAt: Long)

    /** 一次"播种 → 投影一次收尾复习"的探针结果，用来找闸门判断相反的那一格。 */
    private data class StraddleProbe(
        val seedStabilityDays: Double,
        val closing: ClosingReview,
        val atFitted: Int,
        val atFactory: Int,
    ) {
        override fun toString(): String =
            "种子强度 ${"%.2f".format(seedStabilityDays)} → 记忆强度 " +
                "${"%.2f".format(closing.state.stabilityDays)}，" +
                "拟合衰减 $atFitted 天 / 出厂衰减 $atFactory 天"
    }

    @Test
    fun `legacy kill switch keeps the audited exponential behavior`() {
        val result = legacyProjector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(attempt("a-1", 1, easyEvidence(weight = 1.0))),
            1,
        )
        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")

        // projection-v4 formula: S = 0.5 * (1 + 1.6 * 1.0) + 0.25
        assertEquals(0.5 * 2.6 + 0.25, memory.stabilityDays, 1e-9)
        assertEquals(5.5 - 0.72, memory.difficulty, 1e-9)
    }

    @Test
    fun `legacy kill switch keeps the ten minute reveal review`() {
        val revealed = attempt("a-1", 1).copy(
            evidence = LearningEvidence(
                LearningEvidenceDirection.NONE,
                0.0,
                LearningEvidenceReason.ANSWER_REVEALED,
            ),
            problemMemoryOutcome = ProblemMemoryOutcome.ANSWER_REVEALED,
        )

        val result = legacyProjector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(revealed),
            1,
        )
        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(revealed.occurredAtEpochMillis + 10 * 60_000, memory.nextReviewAtEpochMillis)
    }

    private fun seededCrossDay(): LearningProjectionResult {
        var snapshot = LearnerSnapshot.empty("learner-1")
        var nextAt = 0L
        for (index in 1..3) {
            val result = projector.project(
                snapshot,
                listOf(attempt("a-$index", index.toLong(), easyEvidence(), occurredAt = nextAt)),
                index.toLong(),
            )
            snapshot = result.snapshot
            nextAt = snapshot.problemMemoryStates.getValue("unit-1").nextReviewAtEpochMillis
        }
        return LearningProjectionResult(snapshot, emptySet(), emptySet(), emptySet())
    }

    private val LearningProjectionResult.memory
        get() = snapshot.problemMemoryStates.getValue("unit-1")

    private fun attempt(
        id: String,
        sequence: Long,
        evidence: LearningEvidence = easyEvidence(),
        occurredAt: Long = sequence * DAY_MILLIS,
    ): Attempt {
        val outcome = when {
            evidence.direction == LearningEvidenceDirection.NONE -> ProblemMemoryOutcome.ANSWER_REVEALED
            evidence.signedWeight > 0 -> ProblemMemoryOutcome.INDEPENDENT_RECALL
            else -> ProblemMemoryOutcome.RETRIEVAL_FAILURE
        }
        return Attempt(
            attemptId = id,
            presentationId = "presentation-$id",
            responseOrdinal = 1,
            assessmentSnapshot = AssessmentEvidenceSnapshot(
                snapshotId = "snapshot-$id",
                assessmentItemId = "assessment-$id",
                practiceUnitId = "unit-1",
                problemRevisionId = "revision-1",
                answerSpecId = "answer-1",
                itemFamilyId = "family-$id",
                sourceBundleId = "source-$id",
                taxonomyVersion = "taxonomy-v1",
                verification = AssessmentSnapshotVerification.VERIFIED,
                calibration = CalibrationSnapshot(
                    CalibrationSupport.SUPPORTED,
                    "calibration-source",
                    "calibration-v1",
                    0,
                    400 * DAY_MILLIS,
                ),
                attributions = listOf(
                    KnowledgeEvidenceAttribution(
                        bindingId = "binding-$id",
                        knowledgeNodeId = "kc-a",
                        weight = 1.0,
                        basisRevisionId = "revision-1",
                        taxonomyVersion = "taxonomy-v1",
                        role = EvidenceAttributionRole.PRIMARY,
                        certainty = EvidenceAttributionCertainty.DIRECT,
                    ),
                ),
                capturedAtEpochMillis = 0,
            ),
            evidence = evidence,
            problemMemoryOutcome = outcome,
            occurredAtEpochMillis = occurredAt,
            durationSeconds = 60,
            studyDay = StudyDayContext(occurredAt / DAY_MILLIS, "UTC", 0),
            eventSequence = sequence,
        )
    }

    private fun easyEvidence(weight: Double = 1.0) = LearningEvidence(
        LearningEvidenceDirection.POSITIVE,
        weight,
        LearningEvidenceReason.INDEPENDENT_CORRECT,
    )

    private fun wrongEvidence() = LearningEvidence(
        LearningEvidenceDirection.NEGATIVE,
        1.0,
        LearningEvidenceReason.INDEPENDENT_INCORRECT,
    )

    private companion object {
        const val DAY_MILLIS = 86_400_000L

        /** 与 `LearningProjector` 的私有常量同值（那边不对外，这里按既有用例的做法写死）。 */
        const val GRADUATION_MIN_INTERVAL_DAYS = 90

        /**
         * 闸门那条用例用的目标保持率：**故意同时避开 0.9 与 0.8**。
         * 0.9 时间隔与衰减恒等无关（见该用例的注释），闸门读哪条曲线都看不出来；
         * 0.8 是 `GRADUATION_TARGET_RETENTION`，"毕业"与"不毕业"会写出同一天，也看不出来。
         * 0.75 落在设置页滑杆的 0.7..0.97 区间内，是用户真能设出来的值。
         */
        const val GATE_DESIRED_RETENTION = 0.75
    }
}
