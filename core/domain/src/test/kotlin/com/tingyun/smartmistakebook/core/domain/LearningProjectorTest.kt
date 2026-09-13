package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.ChatEvidenceSubmitted
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningProjectorTest {
    private val projector = LearningProjector()
    private val DAY_MILLIS = 86_400_000L

    @Test
    fun `attempt ids are idempotent and weighted bindings update every knowledge node`() {
        val attempt = attempt("attempt-1", 1, setOf("kc-a", "kc-b"), positiveEvidence())

        val first = projector.project(LearnerSnapshot.empty("learner-1"), listOf(attempt), 1)
        val replay = projector.project(first.snapshot, listOf(attempt), 1)

        assertEquals(setOf("attempt-1"), first.appliedAttemptIds)
        assertEquals(setOf("attempt-1"), replay.ignoredAttemptIds)
        assertEquals(first.snapshot, replay.snapshot)
        assertEquals(setOf("kc-a", "kc-b"), first.snapshot.knowledgeMasteryStates.keys)
        // Spec 2.13: every bound KC receives the FULL evidence record; the
        // binding split (0.6/0.4) only orders attributions, it no longer
        // divides the evidence mass.
        assertEquals(
            listOf(1.0, 1.0),
            first.snapshot.knowledgeMasteryStates.values.map { it.evidenceMass }.sorted(),
        )
    }

    @Test
    fun `independent error after high mastery enters conflicted state`() {
        val mastered = KnowledgeMasteryState(
            knowledgeNodeId = "kc-a",
            masteryScore = 0.94,
            conservativeMasteryScore = 0.88,
            evidenceMass = 4.0,
            status = MasteryStatus.MASTERED,
            calibrationSupport = CalibrationSupport.SUPPORTED,
            projectorVersion = LearningProjector.VERSION,
            checkpointSequence = 4,
            lastEvidenceAtEpochMillis = 400,
        )
        val snapshot = LearnerSnapshot(
            learnerId = "learner-1",
            knowledgeMasteryStates = mapOf("kc-a" to mastered),
            checkpoint = ProjectionCheckpoint(4, LearningProjector.VERSION, 400),
            generatedAtEpochMillis = 400,
        )

        val result = projector.project(
            snapshot,
            listOf(attempt("attempt-5", 5, setOf("kc-a"), negativeEvidence())),
            5,
        )

        assertEquals(MasteryStatus.CONFLICTED, result.snapshot.knowledgeMasteryStates.getValue("kc-a").status)
        assertEquals(1, result.snapshot.problemMemoryStates.getValue("unit-1").lapseCount)
    }

    @Test
    fun `same attempt id with conflicting payload is rejected deterministically`() {
        val first = attempt("attempt-1", 1, setOf("kc-a"), positiveEvidence())
        val conflicting = first.copy(
            evidence = negativeEvidence(),
            problemMemoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
        )

        val result = projector.project(
            LearnerSnapshot.empty("learner-1"), listOf(conflicting, first), 1,
        )

        assertTrue(result.appliedAttemptIds.isEmpty())
        assertEquals(setOf("attempt-1"), result.conflictedAttemptIds)
        assertEquals(LearnerSnapshotFreshness.STALE, result.snapshot.freshness)
    }

    @Test
    fun `identical retries in one projection batch apply exactly once`() {
        val attempt = attempt("attempt-1", 1, setOf("kc-a"), positiveEvidence())

        val result = projector.project(
            LearnerSnapshot.empty("learner-1"), listOf(attempt, attempt), 1,
        )

        assertEquals(setOf("attempt-1"), result.appliedAttemptIds)
        assertTrue(result.conflictedAttemptIds.isEmpty())
        assertEquals(1, result.snapshot.problemMemoryStates.size)
    }

    @Test
    fun `device clock rollback is recorded and cannot move memory review time backwards`() {
        val expiredCalibration = CalibrationSnapshot(
            CalibrationSupport.SUPPORTED,
            "calibration-source",
            "short-calibration-v1",
            0,
            3_000,
        )
        val firstSeed = attempt("attempt-1", 1, setOf("kc-a"), positiveEvidence())
        val first = firstSeed.copy(
            occurredAtEpochMillis = 5_000,
            assessmentSnapshot = firstSeed.assessmentSnapshot.copy(calibration = expiredCalibration),
        )
        val secondSeed = attempt("attempt-2", 2, setOf("kc-a"), positiveEvidence())
        val second = secondSeed.copy(
            occurredAtEpochMillis = 1_000,
            assessmentSnapshot = secondSeed.assessmentSnapshot.copy(calibration = expiredCalibration),
        )

        val result = projector.project(
            LearnerSnapshot.empty("learner-1"), listOf(first, second), 2,
        )

        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")
        assertEquals(5_000, memory.lastReviewedAtEpochMillis)
        assertEquals(1, memory.clockAnomalyCount)
        assertTrue(memory.nextReviewAtEpochMillis >= memory.lastReviewedAtEpochMillis)
        assertEquals(
            5_000L,
            result.snapshot.knowledgeMasteryStates.getValue("kc-a").lastEvidenceAtEpochMillis,
        )
        assertEquals(
            CalibrationSupport.UNKNOWN,
            result.snapshot.knowledgeMasteryStates.getValue("kc-a").calibrationSupport,
        )
    }

    @Test
    fun `valid calibration snapshot carries current support into mastery state`() {
        val result = projector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(attempt("attempt-1", 1, setOf("kc-a"), positiveEvidence())),
            1,
        )

        assertEquals(
            CalibrationSupport.SUPPORTED,
            result.snapshot.knowledgeMasteryStates.getValue("kc-a").calibrationSupport,
        )
    }

    @Test
    fun `visible tutor answer refreshes the clock without double penalty`() {
        val learned = projector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(attempt("attempt-1", 1, setOf("kc-a"), positiveEvidence())),
            1,
        ).snapshot
        val masteryBefore = learned.knowledgeMasteryStates
        val exposure = TutorAnswerExposureOutcome(
            outcomeId = "tutor-exposure-outcome-1",
            exposureId = "tutor-exposure-1",
            sessionId = "tutor-session-1",
            questionDocumentId = "question-document-1",
            questionRevisionNumber = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            problemRevisionId = "revision-1",
            practiceUnitId = "unit-1",
            occurredAtEpochMillis = 2_000,
            eventSequence = 2,
        )

        val projected = projector.project(learned, listOf(exposure), 2)
        val retried = projector.project(projected.snapshot, listOf(exposure), 2)
        val memory = projected.snapshot.problemMemoryStates.getValue("unit-1")

        val stabilityBefore = learned.problemMemoryStates.getValue("unit-1").stabilityDays
        assertEquals(setOf(exposure.outcomeId), projected.appliedTutorAnswerExposureOutcomeIds)
        // Spec 2.5/2.6 merge: the exposure is a clock refresh only - it must
        // not decay stability, count a lapse, or double-penalize the attempt
        // that already happened.
        assertEquals(stabilityBefore, memory.stabilityDays, 1e-9)
        assertEquals(0, memory.answerRevealCount)
        assertEquals(0, memory.lapseCount)
        assertEquals(masteryBefore, projected.snapshot.knowledgeMasteryStates)
        assertEquals(setOf(exposure.outcomeId), retried.ignoredTutorAnswerExposureOutcomeIds)
        assertEquals(projected.snapshot, retried.snapshot)
    }

    @Test
    fun `chat evidence refreshes the mastery clock like any other evidence`() {
        // 消灭的失败（审计 AUDIT-ALGORITHM-2026-09-09 §3.5）：projectChatEvidence
        // 从不写 lastEvidenceAtEpochMillis，而该字段默认只从
        // independentCorrectObservations 推导（attempt 通道）——只经模型判断或
        // 知识点复习获得证据的 KC，lastEvidenceAt 恒为 null，被 ReviewPlanner
        // 判为 stale，于是**永远留在复习队列**，无论答对多少次。
        val projected = projector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(chatEvidence("chat-ev-1", 1, LearningEvidenceDirection.POSITIVE, 0.15)),
            1,
        )

        val state = projected.snapshot.knowledgeMasteryStates.getValue("kc-a")
        assertEquals(1_000L, state.lastEvidenceAtEpochMillis)
        assertEquals(LearningEvidenceDirection.POSITIVE.name, state.lastEvidenceDirection)
    }

    @Test
    fun `a negative chat judgment carries its direction into the mastery state`() {
        // 后果 B（同审计条目）：kcMasteryDropPressure 要求
        // lastEvidenceDirection == NEGATIVE 才传导；该字段此前对 chat 通道恒为
        // null，模型写入的负向证据因此不产生任何 KC 传导压力——spec §5 的
        // "KC→错题权重联动律"在模型通道上是死的。
        val learned = projector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(attempt("attempt-1", 1, setOf("kc-a"), positiveEvidence())),
            1,
        )

        val projected = projector.project(
            learned.snapshot,
            listOf(chatEvidence("chat-ev-1", 2, LearningEvidenceDirection.NEGATIVE, 0.35)),
            2,
        )

        val state = projected.snapshot.knowledgeMasteryStates.getValue("kc-a")
        assertEquals(LearningEvidenceDirection.NEGATIVE.name, state.lastEvidenceDirection)
        assertEquals(2_000L, state.lastEvidenceAtEpochMillis)
    }

    @Test
    fun `a later chat judgment supersedes an earlier attempt clock`() {
        // 时钟取"最近一次证据"，不是"最近一次作答"：模型在作答之后又判了一次，
        // 该 KC 的陈旧判定应以较晚的那条为准。
        val learned = projector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(attempt("attempt-1", 1, setOf("kc-a"), positiveEvidence())),
            1,
        )

        val projected = projector.project(
            learned.snapshot,
            listOf(chatEvidence("chat-ev-1", 2, LearningEvidenceDirection.POSITIVE, 0.15)),
            2,
        )

        assertEquals(
            2_000L,
            projected.snapshot.knowledgeMasteryStates.getValue("kc-a").lastEvidenceAtEpochMillis,
        )
    }

    @Test
    fun `full replay and incremental projection agree on the whole snapshot`() {
        // 升级路径依赖这条等价性：projector 版本 bump 会让已有库走
        // StudyProjectionDrainer 的全量重放（`replay`）而不是增量投影。若两条路
        // 对同一份账本算出不同的状态，老用户升级后拿到的就仍是修复前的旧值。
        //
        // 断言是**整份快照 + 呈现状态表**，不是单个字段。这条测试此前只比
        // `knowledgeMasteryStates`，于是 `problemMemoryStates`、`checkpoint`、
        // `knownLedgerHeadSequence`、`generatedAtEpochMillis`、四张记录表以及
        // `presentationProjectionStates` 上的任何分叉都逃得过去——而最后一张表
        // 正是提交时 CAS 校验的对象（`ProjectionTransactionDao.kt:964`），
        // 两条路一旦不一致，升级后的第一次增量提交就会被判 CAS 冲突。
        //
        // 两条路都**没有墙钟**：`replay` 从 0 起、`project` 承接上一版快照的
        // `generatedAtEpochMillis`（空快照为 0），随后都取事件时间戳的最大值。
        val events = listOf(
            attempt("attempt-1", 1, setOf("kc-a", "kc-b"), positiveEvidence()),
            chatEvidence("chat-ev-1", 2, LearningEvidenceDirection.NEGATIVE, 0.35),
            exposure("exposure-outcome-1", 3),
            attempt("attempt-2", 4, setOf("kc-a"), negativeEvidence()),
        )

        val incremental = projector.project(LearnerSnapshot.empty("learner-1"), events, 4)
        val replayed = projector.replay("learner-1", events)

        assertEquals(replayed.snapshot, incremental.snapshot)
        assertEquals(replayed.presentationProjectionStates, incremental.presentationProjectionStates)
        assertEquals(
            "两条路都必须把账本末条序列当作已知头",
            4L,
            replayed.snapshot.knownLedgerHeadSequence,
        )
        assertEquals(4L, replayed.snapshot.checkpoint.lastSequence)
        assertEquals(
            "时间线是事件时间戳的最大值，不是调用时刻",
            4_000L,
            replayed.snapshot.generatedAtEpochMillis,
        )
        assertEquals(
            LearningEvidenceDirection.NEGATIVE.name,
            replayed.snapshot.knowledgeMasteryStates.getValue("kc-a").lastEvidenceDirection,
        )
        // 无修正账本上，只有 `replay` 能写这两个字段，而它写的正是"没有"。
        assertNull(replayed.snapshot.correctionWatermarkEpochMillis)
        assertTrue(replayed.snapshot.appliedCorrectionRecords.isEmpty())
    }

    @Test
    fun `a review after an answer exposure stays on its own learner-local day`() {
        // 消灭的失败（审计 AUDIT-ALGORITHM-2026-09-09 §3.7）：`TutorAnswerExposureOutcome`
        // 不带 studyDay，`projectTutorAnswerExposure` 也从未显式写 `lastReviewedEpochDay`，
        // 于是该字段的默认值 `lastReviewedAtEpochMillis / 86_400_000`（**UTC** 日序）被写进状态。
        // UTC+8 学员本地 D+1 07:00 看到答案时（UTC 仍在 D），当天 20:00 复习得到的
        // `eventEpochDay` 是 D+1，与状态里的 D 相减得 1 → 被当成跨日：走 long_term 分支
        // 拿到本不该有的稳定性增益，并让 §2.10 的毕业连胜多计一次。
        val localDay = 20_000L
        val utcOffset = 480
        // 本地 D 日 20:00（= UTC 同日 12:00）。
        val firstAttemptAt = localDay * DAY_MILLIS + 12 * 3_600_000L
        // 本地 D+1 日 07:00 —— UTC 日序仍是 D，与本地日序差 1。
        val exposureAt = (localDay + 1) * DAY_MILLIS - 3_600_000L
        // 本地 D+1 日 20:00，与曝光同一个本地日。
        val secondAttemptAt = (localDay + 1) * DAY_MILLIS + 12 * 3_600_000L

        val first = attempt("attempt-1", 1, setOf("kc-a"), positiveEvidence()).copy(
            occurredAtEpochMillis = firstAttemptAt,
            studyDay = StudyDayContext(localDay, "Asia/Shanghai", utcOffset),
        )
        val exposure = TutorAnswerExposureOutcome(
            outcomeId = "tutor-exposure-outcome-1",
            exposureId = "tutor-exposure-1",
            sessionId = "tutor-session-1",
            questionDocumentId = "question-document-1",
            questionRevisionNumber = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            problemRevisionId = "revision-1",
            practiceUnitId = "unit-1",
            occurredAtEpochMillis = exposureAt,
            eventSequence = 2,
        )
        val second = attempt("attempt-2", 3, setOf("kc-a"), positiveEvidence()).copy(
            occurredAtEpochMillis = secondAttemptAt,
            studyDay = StudyDayContext(localDay + 1, "Asia/Shanghai", utcOffset),
        )

        // 先走曝光，确认它确实把时钟推到曝光时刻，再走同一本地日的复习。
        val exposed = projector.project(
            projector.project(LearnerSnapshot.empty("learner-1"), listOf(first), 1).snapshot,
            listOf(exposure),
            2,
        ).snapshot
        assertEquals(exposureAt, exposed.problemMemoryStates.getValue("unit-1").lastReviewedAtEpochMillis)

        val projected = projector.project(exposed, listOf(second), 3).snapshot
        val memory = projected.problemMemoryStates.getValue("unit-1")

        // 曝光与本次复习落在同一个本地日 → 同日分支，不是跨日。
        assertEquals(1, memory.consecutiveCrossDaySuccess)
        assertEquals(0, memory.consecutiveCrossDayAgain)
    }

    @Test
    fun `a review on the local day after an exposure is still a cross day`() {
        // 上一条的边界：跨日本身必须照常识别。曝光在本地 D+1，复习在本地 D+2
        // → 仍是一次跨日复习，不能因为"曝光不带 studyDay"就把所有曝光后的复习
        // 都吞成同日。
        val localDay = 20_000L
        val utcOffset = 480
        val first = attempt("attempt-1", 1, setOf("kc-a"), positiveEvidence()).copy(
            occurredAtEpochMillis = localDay * DAY_MILLIS + 12 * 3_600_000L,
            studyDay = StudyDayContext(localDay, "Asia/Shanghai", utcOffset),
        )
        val exposure = TutorAnswerExposureOutcome(
            outcomeId = "tutor-exposure-outcome-1",
            exposureId = "tutor-exposure-1",
            sessionId = "tutor-session-1",
            questionDocumentId = "question-document-1",
            questionRevisionNumber = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            problemRevisionId = "revision-1",
            practiceUnitId = "unit-1",
            occurredAtEpochMillis = (localDay + 1) * DAY_MILLIS - 3_600_000L,
            eventSequence = 2,
        )
        val second = attempt("attempt-2", 3, setOf("kc-a"), positiveEvidence()).copy(
            occurredAtEpochMillis = (localDay + 2) * DAY_MILLIS + 12 * 3_600_000L,
            studyDay = StudyDayContext(localDay + 2, "Asia/Shanghai", utcOffset),
        )

        val exposed = projector.project(
            projector.project(LearnerSnapshot.empty("learner-1"), listOf(first), 1).snapshot,
            listOf(exposure),
            2,
        ).snapshot
        val memory = projector.project(exposed, listOf(second), 3)
            .snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(2, memory.consecutiveCrossDaySuccess)
    }

    private fun chatEvidence(
        evidenceId: String,
        sequence: Int,
        direction: LearningEvidenceDirection,
        weight: Double,
    ) = ChatEvidenceSubmitted(
        evidenceId = evidenceId,
        conversationId = "tutor-conv-1",
        knowledgeNodeId = "kc-a",
        direction = direction,
        weight = weight,
        reasonMarkdown = "模型判断。",
        confidence = 0.9,
        occurredAtEpochMillis = sequence * 1_000L,
        eventSequence = sequence.toLong(),
    )

    /** 讲题栏曝光答案：只刷新记忆时钟、不二次衰减（spec §2.5/§2.6）。 */
    private fun exposure(
        outcomeId: String,
        sequence: Int,
        practiceUnitId: String = "unit-1",
    ) = TutorAnswerExposureOutcome(
        outcomeId = outcomeId,
        exposureId = "exposure-$outcomeId",
        sessionId = "tutor-session-1",
        questionDocumentId = "question-document-1",
        questionRevisionNumber = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        problemRevisionId = "revision-1",
        practiceUnitId = practiceUnitId,
        occurredAtEpochMillis = sequence * 1_000L,
        eventSequence = sequence.toLong(),
    )

    private fun attempt(
        id: String,
        sequence: Long,
        knowledgeNodeIds: Set<String>,
        evidence: LearningEvidence,
    ): Attempt {
        val weight = 1.0 / knowledgeNodeIds.size
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
                    100_000,
                ),
                attributions = knowledgeNodeIds.sorted().mapIndexed { index, knowledgeNodeId ->
                    KnowledgeEvidenceAttribution(
                        bindingId = "binding-$id-$knowledgeNodeId",
                        knowledgeNodeId = knowledgeNodeId,
                        weight = weight,
                        basisRevisionId = "revision-1",
                        taxonomyVersion = "taxonomy-v1",
                        role = if (index == 0) EvidenceAttributionRole.PRIMARY else EvidenceAttributionRole.SECONDARY,
                        certainty = EvidenceAttributionCertainty.DIRECT,
                    )
                },
                capturedAtEpochMillis = 0,
            ),
            evidence = evidence,
            problemMemoryOutcome = if (evidence.signedWeight > 0) {
                ProblemMemoryOutcome.INDEPENDENT_RECALL
            } else {
                ProblemMemoryOutcome.RETRIEVAL_FAILURE
            },
            occurredAtEpochMillis = sequence * 1_000,
            durationSeconds = 60,
            studyDay = StudyDayContext(sequence, "Asia/Shanghai", 480),
            eventSequence = sequence,
        )
    }

    private fun positiveEvidence() = LearningEvidence(
        LearningEvidenceDirection.POSITIVE,
        1.0,
        LearningEvidenceReason.INDEPENDENT_CORRECT,
    )
    private fun negativeEvidence() = LearningEvidence(
        LearningEvidenceDirection.NEGATIVE,
        1.0,
        LearningEvidenceReason.INDEPENDENT_INCORRECT,
    )
}
