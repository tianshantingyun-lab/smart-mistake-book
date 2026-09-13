package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.LearningLedgerRead
import com.tingyun.smartmistakebook.core.database.LearningLedgerReadStatus
import com.tingyun.smartmistakebook.core.database.PersistedIncrementalLearningEvent
import com.tingyun.smartmistakebook.core.database.PersistedLearnerSnapshot
import com.tingyun.smartmistakebook.core.database.PersistedLearningLedgerEvent
import com.tingyun.smartmistakebook.core.database.ProjectionBatch
import com.tingyun.smartmistakebook.core.database.ProjectionBatchStopReason
import com.tingyun.smartmistakebook.core.database.ProjectionCommit
import com.tingyun.smartmistakebook.core.database.ProjectionOutboxRecord
import com.tingyun.smartmistakebook.core.database.port.LearningProjectionPort
import com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.ChatEvidenceSubmitted
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.IncrementalLearningEvent
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.LearningLedgerEvent
import com.tingyun.smartmistakebook.core.model.LearningLedgerFingerprint
import com.tingyun.smartmistakebook.core.model.PresentationProjectionState
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome

/**
 * `StudyProjectionDrainer` 各测试共用的端口替身与事件夹具。
 *
 * 收窄到 [LearningProjectionPort] 之后才可能这样写：drain 只调用四个方法，
 * 不必为其余 28 个端口方法编造替身（见 `StudyProjectionDrainer` 的类注释）。
 *
 * 它逐条镜像真实 DAO 的语义，**不是随手写的假实现**——替身一旦偏离生产语义，
 * 这些测试就会在断言一个不存在的世界：
 * - 页按序列连续性检查，不连续给 [ProjectionBatchStopReason.GAP]；
 * - 页里出现修正行立刻给 `FULL_REPLAY_REQUIRED`，不再往后读
 *   （`ProjectionTransactionDao.kt:475-486`：`"Correction ${row.eventId} requires a full ledger replay"`）；
 * - 页满而账本未尽给 `LIMIT_REACHED`，反之 `END_OF_LEDGER`；
 * - `canonicalFingerprint` 用 [LearningLedgerFingerprint.event]，与 DAO 写入时同一个函数
 *   （`AttemptTransactionDao.kt:350`、`ChatEvidenceDao.kt:77` 等）。替身若自造指纹，
 *   投影器会把它当冲突——等价性测试就测不出真东西；
 * - 呈现状态在**读取时**把 `asOfLedgerSequence` 归到当前检查点，缺失则给
 *   `memoryProjectionApplied = false` 的默认态，与
 *   `PresentationProjectionStateEntity.toModel(checkpoint)` 及 `batchStop` 的兜底一致。
 */
internal class LedgerProjectionPort(
    private val learnerId: String,
    ledgerEvents: List<PersistedLearningLedgerEvent> = emptyList(),
    var current: PersistedLearnerSnapshot? = null,
    private val ledgerStatus: LearningLedgerReadStatus = LearningLedgerReadStatus.COMPLETE,
    private val ledgerDetail: String? = null,
    /**
     * 本替身每次最多返回多少条，**压过调用方传的 `limit`**。
     *
     * 生产里页大小由调用方决定（`StudyProjectionDrainer` 传 `PROJECTION_BATCH_SIZE = 100`），
     * 替身把它做成可调旋钮，因为「同一份账本被切在哪里」正是「重放与逐条增量是否等价」
     * 的唯一变量。设成非默认值只表示"模拟另一种切分"，不代表生产会这么切。
     */
    private val pageSize: Int = Int.MAX_VALUE,
    /** 模拟"序列已分配但账本行缺失"：账本头比实际行更靠前，DAO 此时给 `GAP`。 */
    private val allocatedLedgerHeadSequence: Long? = null,
) : LearningProjectionPort {

    private val orderedLedger = ledgerEvents.sortedBy { it.event.eventSequence }
    private val presentationStates = mutableMapOf<String, PresentationProjectionState>()

    val commits = mutableListOf<ProjectionCommit>()

    override suspend fun loadProjectionBatch(
        projectionName: String,
        learnerId: String,
        // 刻意忽略：切分由 [pageSize] 决定，见它的说明。
        limit: Int,
    ): ProjectionBatch {
        val checkpoint = current?.snapshot?.checkpoint?.lastSequence ?: 0L
        val ledgerHead = allocatedLedgerHeadSequence
            ?: orderedLedger.lastOrNull()?.event?.eventSequence
            ?: 0L
        val page = mutableListOf<PersistedIncrementalLearningEvent>()
        var expected = checkpoint + 1
        var stopReason: ProjectionBatchStopReason? = null
        var blockedAt: Long? = null
        var detail: String? = null
        for (persisted in orderedLedger) {
            val sequence = persisted.event.eventSequence
            if (sequence <= checkpoint) continue
            if (sequence != expected) {
                stopReason = ProjectionBatchStopReason.GAP
                blockedAt = expected
                detail = "Expected sequence $expected but found $sequence"
                break
            }
            if (persisted.event is AttemptCorrection) {
                stopReason = ProjectionBatchStopReason.FULL_REPLAY_REQUIRED
                blockedAt = expected
                detail = "Correction ${persisted.event.ledgerEventId} requires a full ledger replay"
                break
            }
            if (page.size == pageSize) {
                stopReason = ProjectionBatchStopReason.LIMIT_REACHED
                blockedAt = expected
                break
            }
            page += persisted.toIncremental()
            expected++
        }
        val consumedThrough = expected - 1
        return ProjectionBatch(
            projectionName = projectionName,
            learnerId = learnerId,
            previousCheckpoint = checkpoint,
            ledgerHeadSequence = ledgerHead,
            events = page,
            authoritativePresentationStates = authoritativeStatesFor(page, checkpoint),
            stopReason = stopReason ?: if (ledgerHead > consumedThrough) {
                ProjectionBatchStopReason.GAP
            } else {
                ProjectionBatchStopReason.END_OF_LEDGER
            },
            blockedAtSequence = if (stopReason == null && ledgerHead > consumedThrough) {
                expected
            } else {
                blockedAt
            },
            detail = if (stopReason == null && ledgerHead > consumedThrough) {
                "Sequence $expected was allocated but has no immutable ledger event"
            } else {
                detail
            },
        )
    }

    override suspend fun loadLearningLedger(learnerId: String) = LearningLedgerRead(
        learnerId = learnerId,
        validPrefix = orderedLedger,
        status = ledgerStatus,
        detail = ledgerDetail,
    )

    override suspend fun readCurrentLearnerSnapshot(
        projectionName: String,
        learnerId: String,
    ): PersistedLearnerSnapshot? = current

    override suspend fun commitProjection(commit: ProjectionCommit): PersistedLearnerSnapshot {
        commits += commit
        presentationStates.putAll(commit.presentationProjectionStates)
        return PersistedLearnerSnapshot(
            projectionName = commit.projectionName,
            stateVersion = (current?.stateVersion ?: 0L) + 1L,
            knownLedgerHeadSequence = commit.knownLedgerHeadSequence,
            snapshot = commit.snapshot,
        ).also { current = it }
    }

    /** 只保留本页真正需要的呈现状态 id；`asOfLedgerSequence` 归到本页检查点。 */
    private fun authoritativeStatesFor(
        page: List<PersistedIncrementalLearningEvent>,
        checkpoint: Long,
    ): Map<String, PresentationProjectionState> = page
        .mapNotNullTo(linkedSetOf()) { persisted ->
            when (val event = persisted.event) {
                is Attempt -> event.presentationId
                is AnswerRevealOutcome -> event.presentationId
                is TutorAnswerExposureOutcome, is ChatEvidenceSubmitted -> null
            }
        }
        .associateWith { presentationId ->
            presentationStates[presentationId]?.copy(asOfLedgerSequence = checkpoint)
                ?: PresentationProjectionState(
                    presentationId = presentationId,
                    asOfLedgerSequence = checkpoint,
                    memoryProjectionApplied = false,
                )
        }

    private fun PersistedLearningLedgerEvent.toIncremental(): PersistedIncrementalLearningEvent {
        val event = requireNotNull(this.event as? IncrementalLearningEvent) {
            "A correction cannot enter the incremental projection path"
        }
        return PersistedIncrementalLearningEvent(
            event = event,
            canonicalFingerprint = canonicalFingerprint,
            outbox = ProjectionOutboxRecord(
                outboxId = "outbox:${event.ledgerEventId}",
                learnerId = learnerId,
                outboxSequence = event.eventSequence,
                eventKind = eventKindOf(event),
                eventId = event.ledgerEventId,
                canonicalFingerprint = canonicalFingerprint,
                status = "PENDING",
                createdAtEpochMillis = event.occurredAtEpochMillis,
            ),
        )
    }

    private fun eventKindOf(event: IncrementalLearningEvent): String = when (event) {
        is Attempt -> "ATTEMPT"
        is AnswerRevealOutcome -> "ANSWER_REVEAL_OUTCOME"
        is TutorAnswerExposureOutcome -> "TUTOR_ANSWER_EXPOSURE_OUTCOME"
        is ChatEvidenceSubmitted -> "CHAT_EVIDENCE_SUBMITTED"
    }
}

// ---------------------------------------------------------------------- 夹具

/** 账本行：指纹取自与 DAO 相同的规范化函数，见 [LedgerProjectionPort] 的类注释。 */
internal fun ledgerEvent(event: LearningLedgerEvent) = PersistedLearningLedgerEvent(
    event = event,
    canonicalFingerprint = LearningLedgerFingerprint.event(event),
)

internal fun persistedSnapshot(
    snapshot: LearnerSnapshot,
    projectionName: String = "study-experience-v1",
    stateVersion: Long = 1L,
) = PersistedLearnerSnapshot(
    projectionName = projectionName,
    stateVersion = stateVersion,
    knownLedgerHeadSequence = snapshot.knownLedgerHeadSequence,
    snapshot = snapshot,
)

internal fun positiveEvidence() = LearningEvidence(
    LearningEvidenceDirection.POSITIVE,
    1.0,
    LearningEvidenceReason.INDEPENDENT_CORRECT,
)

internal fun negativeEvidence() = LearningEvidence(
    LearningEvidenceDirection.NEGATIVE,
    1.0,
    LearningEvidenceReason.INDEPENDENT_INCORRECT,
)

/**
 * 提示后的错答：用于**同一呈现的第二次及以后作答**。
 *
 * `Attempt` 的构造器要求「只有第一次作答可以带独立证据」
 * （`LearningState.kt:278`），所以重作答必须换成非独立理由，
 * 否则夹具根本构造不出来——这条约束本身就是投影器判"独立错误"的依据。
 */
internal fun hintedIncorrectEvidence() = LearningEvidence(
    LearningEvidenceDirection.NEGATIVE,
    1.0,
    LearningEvidenceReason.INCORRECT_AFTER_HINT,
)

/**
 * 一条作答。[knowledgeNodeIds] 上的证据按条数均分（与 `LearningProjectorTest` 的夹具同口径），
 * 每个知识点都拿到 `DIRECT` 归因——`AMBIGUOUS` 会走 `ambiguousAttemptIds` 旁路。
 */
internal fun sampleAttempt(
    id: String,
    sequence: Long,
    knowledgeNodeIds: Set<String>,
    evidence: LearningEvidence = positiveEvidence(),
    practiceUnitId: String = "unit-1",
    presentationId: String = "presentation-$id",
    responseOrdinal: Int = 1,
    studyDayEpochDay: Long = sequence,
): Attempt {
    val weight = 1.0 / knowledgeNodeIds.size
    return Attempt(
        attemptId = id,
        presentationId = presentationId,
        responseOrdinal = responseOrdinal,
        assessmentSnapshot = sampleAssessmentSnapshot(
            id = id,
            practiceUnitId = practiceUnitId,
            knowledgeNodeIds = knowledgeNodeIds,
            weight = weight,
        ),
        evidence = evidence,
        problemMemoryOutcome = if (evidence.signedWeight > 0) {
            ProblemMemoryOutcome.INDEPENDENT_RECALL
        } else {
            ProblemMemoryOutcome.RETRIEVAL_FAILURE
        },
        occurredAtEpochMillis = sequence * 1_000,
        durationSeconds = 60,
        studyDay = StudyDayContext(studyDayEpochDay, "Asia/Shanghai", 480),
        eventSequence = sequence,
    )
}

internal fun sampleAssessmentSnapshot(
    id: String,
    practiceUnitId: String = "unit-1",
    knowledgeNodeIds: Set<String> = setOf("kc-monotonicity"),
    weight: Double = 1.0,
) = AssessmentEvidenceSnapshot(
    snapshotId = "snapshot-$id",
    assessmentItemId = "assessment-$id",
    practiceUnitId = practiceUnitId,
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
)

internal fun sampleAnswerReveal(
    presentationId: String,
    sequence: Long,
    practiceUnitId: String = "unit-1",
    studyDayEpochDay: Long = sequence,
) = AnswerRevealOutcome(
    outcomeId = "reveal-$presentationId",
    presentationId = presentationId,
    assessmentSnapshot = sampleAssessmentSnapshot(
        id = "reveal-$presentationId",
        practiceUnitId = practiceUnitId,
    ),
    occurredAtEpochMillis = sequence * 1_000,
    studyDay = StudyDayContext(studyDayEpochDay, "Asia/Shanghai", 480),
    eventSequence = sequence,
)

internal fun sampleTutorExposure(
    id: String,
    sequence: Long,
    practiceUnitId: String = "unit-1",
) = TutorAnswerExposureOutcome(
    outcomeId = "exposure-outcome-$id",
    exposureId = "exposure-$id",
    sessionId = "tutor-session-1",
    questionDocumentId = "question-document-1",
    questionRevisionNumber = 1,
    cycleOrdinal = 1,
    turnOrdinal = 1,
    problemRevisionId = "revision-1",
    practiceUnitId = practiceUnitId,
    occurredAtEpochMillis = sequence * 1_000,
    eventSequence = sequence,
)

internal fun sampleChatEvidence(
    sequence: Long,
    knowledgeNodeId: String = "kc-monotonicity",
    direction: LearningEvidenceDirection = LearningEvidenceDirection.POSITIVE,
    weight: Double = ChatEvidenceSubmitted.POSITIVE_WEIGHT,
) = ChatEvidenceSubmitted(
    evidenceId = "chat-evidence:$sequence",
    conversationId = "conversation:1",
    knowledgeNodeId = knowledgeNodeId,
    direction = direction,
    weight = weight,
    reasonMarkdown = "学生自己说出了单调性的判据",
    confidence = 0.9,
    occurredAtEpochMillis = 1_000L * sequence,
    eventSequence = sequence,
)

internal fun sampleCorrection(
    attemptId: String,
    sequence: Long,
    replacementEvidence: LearningEvidence = positiveEvidence(),
) = AttemptCorrection(
    correctionId = "correction-$attemptId",
    attemptId = attemptId,
    replacementEvidence = replacementEvidence,
    replacementMemoryOutcome = if (replacementEvidence.signedWeight > 0) {
        ProblemMemoryOutcome.INDEPENDENT_RECALL
    } else {
        ProblemMemoryOutcome.RETRIEVAL_FAILURE
    },
    reasonMarkdown = "原先的判定把提示后的作答记成了独立作答",
    occurredAtEpochMillis = sequence * 1_000,
    eventSequence = sequence,
)
