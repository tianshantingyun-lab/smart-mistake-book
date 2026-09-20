package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.PersistTutorAnswerExposureCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorSessionAnchorCommand
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.TutorAnswerExposureRecord
import com.tingyun.smartmistakebook.core.database.TutorSessionProblemAnchorRecord
import com.tingyun.smartmistakebook.core.database.entity.LearningSequenceEntity
import com.tingyun.smartmistakebook.core.database.entity.ModelTaskEntity
import com.tingyun.smartmistakebook.core.database.entity.ProjectionOutboxEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorAnswerExposureEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorAnswerExposureOutcomeEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorSessionProblemAnchorEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorTurnResponseEntity
import com.tingyun.smartmistakebook.core.model.LearningLedgerFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.canExposeSolutionFor
import com.tingyun.smartmistakebook.core.model.requiresRoundQuestionBinding
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val EVENT_KIND_TUTOR_ANSWER_EXPOSURE = "TUTOR_ANSWER_EXPOSURE_OUTCOME"

@Dao
internal abstract class TutorExposureDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertAnchor(anchor: TutorSessionProblemAnchorEntity): Long

    @Query("SELECT * FROM tutor_session_problem_anchor WHERE session_id = :sessionId LIMIT 1")
    protected abstract suspend fun findAnchor(sessionId: String): TutorSessionProblemAnchorEntity?

    /**
     * 该学习者最近一次锚定到这道题的讲题会话。讲题判定结算靠它把"刚讲完的会话"
     * 与"复习队列当前这一项"对上；anchor 表一直有数据，此前只有内部写路径能读到。
     */
    @Query(
        """
        SELECT * FROM tutor_session_problem_anchor
        WHERE practice_unit_id = :practiceUnitId AND learner_id = :learnerId
        ORDER BY anchored_at_epoch_millis DESC
        LIMIT 1
        """,
    )
    protected abstract suspend fun findLatestAnchorForPracticeUnit(
        practiceUnitId: String,
        learnerId: String,
    ): TutorSessionProblemAnchorEntity?

    /** 结算用的公开读取：把锚定记录映射成端口记录，找不到返回 null。 */
    open suspend fun readLatestAnchorForPracticeUnit(
        practiceUnitId: String,
        learnerId: String,
    ): TutorSessionProblemAnchorRecord? =
        findLatestAnchorForPracticeUnit(practiceUnitId, learnerId)?.toRecord()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertExposure(exposure: TutorAnswerExposureEntity): Long

    @Query(
        """
        SELECT * FROM tutor_answer_exposure
        WHERE model_task_request_id = :modelTaskRequestId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findExposure(
        modelTaskRequestId: String,
    ): TutorAnswerExposureEntity?

    @Query(
        """
        SELECT * FROM tutor_answer_exposure
        WHERE model_task_request_id IN (:modelTaskRequestIds)
        """,
    )
    protected abstract suspend fun findExposures(
        modelTaskRequestIds: Set<String>,
    ): List<TutorAnswerExposureEntity>

    @Query("SELECT * FROM model_task WHERE request_id = :requestId LIMIT 1")
    protected abstract suspend fun findModelTask(requestId: String): ModelTaskEntity?

    @Query(
        """
        SELECT * FROM tutor_turn_response
        WHERE session_id = :sessionId AND cycle_ordinal = :cycleOrdinal
          AND turn_ordinal = :turnOrdinal
        LIMIT 1
        """,
    )
    protected abstract suspend fun findTurnResponse(
        sessionId: String,
        cycleOrdinal: Int,
        turnOrdinal: Int,
    ): TutorTurnResponseEntity?

    @Query("SELECT * FROM tutor_answer_exposure_outcome WHERE exposure_id = :exposureId LIMIT 1")
    internal abstract suspend fun findOutcomeByExposure(
        exposureId: String,
    ): TutorAnswerExposureOutcomeEntity?

    @Query("SELECT * FROM tutor_answer_exposure_outcome WHERE outcome_id = :outcomeId LIMIT 1")
    internal abstract suspend fun findOutcome(
        outcomeId: String,
    ): TutorAnswerExposureOutcomeEntity?

    @Query(
        """
        SELECT exposure.*
        FROM tutor_answer_exposure AS exposure
        JOIN tutor_session_problem_anchor AS anchor ON anchor.session_id = exposure.session_id
        LEFT JOIN tutor_answer_exposure_outcome AS outcome ON outcome.exposure_id = exposure.exposure_id
        WHERE exposure.session_id = :sessionId AND outcome.exposure_id IS NULL
        ORDER BY exposure.exposed_at_epoch_millis ASC, exposure.exposure_id ASC
        """,
    )
    protected abstract suspend fun findPendingForSession(
        sessionId: String,
    ): List<TutorAnswerExposureEntity>

    @Query(
        """
        SELECT exposure.*
        FROM tutor_answer_exposure AS exposure
        JOIN tutor_session_problem_anchor AS anchor ON anchor.session_id = exposure.session_id
        LEFT JOIN tutor_answer_exposure_outcome AS outcome ON outcome.exposure_id = exposure.exposure_id
        WHERE anchor.learner_id = :learnerId AND outcome.exposure_id IS NULL
        ORDER BY exposure.exposed_at_epoch_millis ASC, exposure.exposure_id ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun findPendingForLearner(
        learnerId: String,
        limit: Int,
    ): List<TutorAnswerExposureEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOutcome(outcome: TutorAnswerExposureOutcomeEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOutbox(outbox: ProjectionOutboxEntity)

    @Query(
        """
        SELECT * FROM projection_outbox
        WHERE event_kind = :eventKind AND event_id = :eventId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findOutbox(
        eventKind: String,
        eventId: String,
    ): ProjectionOutboxEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun initializeSequence(sequence: LearningSequenceEntity): Long

    @Query("SELECT last_allocated_sequence FROM learning_sequence WHERE learner_id = :learnerId")
    protected abstract suspend fun lastAllocatedSequence(learnerId: String): Long?

    @Query(
        """
        UPDATE learning_sequence SET last_allocated_sequence = :next
        WHERE learner_id = :learnerId AND last_allocated_sequence = :expected
        """,
    )
    protected abstract suspend fun compareAndSetSequence(
        learnerId: String,
        expected: Long,
        next: Long,
    ): Int

    @Transaction
    open suspend fun bindAnchor(
        command: PersistTutorSessionAnchorCommand,
    ): TutorSessionProblemAnchorRecord {
        val candidate = TutorSessionProblemAnchorEntity(
            sessionId = command.sessionId,
            learnerId = command.learnerId,
            problemRevisionId = command.problemRevisionId,
            practiceUnitId = command.practiceUnitId,
            anchorSource = command.source,
            anchoredAtEpochMillis = command.anchoredAtEpochMillis,
        )
        insertAnchor(candidate)
        val stored = checkNotNull(findAnchor(command.sessionId))
        if (stored.learnerId != candidate.learnerId ||
            stored.problemRevisionId != candidate.problemRevisionId ||
            stored.practiceUnitId != candidate.practiceUnitId
        ) {
            throw ImmutablePayloadConflictException("tutor_session_problem_anchor", command.sessionId)
        }
        findPendingForSession(command.sessionId).forEach { exposure ->
            materialize(exposure, stored)
        }
        return stored.toRecord()
    }

    @Transaction
    open suspend fun recordVisibleExposure(
        command: PersistTutorAnswerExposureCommand,
    ): TutorAnswerExposureRecord {
        validateVisibleSurface(command)
        val candidate = command.toExposureEntity()
        insertExposure(candidate)
        val stored = checkNotNull(findExposure(command.modelTaskRequestId))
        if (!stored.sameIdentity(candidate)) {
            throw ImmutablePayloadConflictException(
                "tutor_answer_exposure",
                command.modelTaskRequestId,
            )
        }
        findAnchor(command.sessionId)?.let { anchor -> materialize(stored, anchor) }
        return stored.toRecord(findOutcomeByExposure(stored.exposureId)?.outcomeId)
    }

    @Transaction
    open suspend fun reconcilePending(learnerId: String, limit: Int): Int {
        require(learnerId.isNotBlank())
        require(limit in 1..1_000)
        var reconciled = 0
        while (true) {
            val pending = findPendingForLearner(learnerId, limit)
            if (pending.isEmpty()) return reconciled
            pending.forEach { exposure ->
                val anchor = checkNotNull(findAnchor(exposure.sessionId)) {
                    "Anchored pending tutor exposure lost its session anchor"
                }
                if (materialize(exposure, anchor)) reconciled++
            }
        }
    }

    @Transaction
    open suspend fun readExposure(
        modelTaskRequestId: String,
    ): TutorAnswerExposureRecord? {
        val exposure = findExposure(modelTaskRequestId) ?: return null
        return exposure.toRecord(findOutcomeByExposure(exposure.exposureId)?.outcomeId)
    }

    open suspend fun readExposures(
        modelTaskRequestIds: Set<String>,
    ): List<TutorAnswerExposureRecord> {
        if (modelTaskRequestIds.isEmpty()) return emptyList()
        require(modelTaskRequestIds.none(String::isBlank))
        return findExposures(modelTaskRequestIds).map { exposure ->
            exposure.toRecord(outcomeId = null)
        }
    }

    private suspend fun validateVisibleSurface(command: PersistTutorAnswerExposureCommand) {
        val task = findModelTask(command.modelTaskRequestId)
            ?: throw ImmutablePayloadConflictException(
                "tutor_answer_exposure_model_task",
                command.modelTaskRequestId,
            )
        val request = ModelTaskCodec.decodeRequest(task.requestSnapshot)
        val output = task.outputSnapshot?.let(ModelTaskCodec::decodeOutput)
        val commonIdentityMatches = task.status == ModelTaskStatus.SUCCEEDED.name &&
            request.requestId == command.modelTaskRequestId &&
            command.occurredAtEpochMillis >= task.updatedAtEpochMillis
        val surfaceMatches = when (command.surfaceKind) {
            "PLAN_SOLUTION" -> {
                val input = request.input as? TutorPlanInput
                val turn = findTurnResponse(
                    command.sessionId,
                    command.cycleOrdinal,
                    command.turnOrdinal,
                )
                input != null && output is TutorPlanOutput && command.responseOrdinal == null &&
                    input.sessionId == command.sessionId &&
                    input.questionDocument.id == command.questionDocumentId &&
                    input.draftRevisionNumber == command.revisionNumber &&
                    input.cycleOrdinal == command.cycleOrdinal &&
                    input.turnOrdinal == command.turnOrdinal &&
                    turn != null &&
                    turn.questionDocumentId == command.questionDocumentId &&
                    turn.revisionNumber == command.revisionNumber &&
                    turn.solutionRevealed &&
                    command.occurredAtEpochMillis >= turn.updatedAtEpochMillis
            }
            "RESPOND_REPLY" -> {
                val input = request.input as? TutorRespondInput
                val respondOutput = output as? TutorRespondOutput
                input != null &&
                    respondOutput?.canExposeSolutionFor(
                        input,
                        requiresRoundQuestionBinding = request.requiresRoundQuestionBinding,
                    ) == true &&
                    input.sessionId == command.sessionId &&
                    input.questionDocument.id == command.questionDocumentId &&
                    input.draftRevisionNumber == command.revisionNumber &&
                    input.cycleOrdinal == command.cycleOrdinal &&
                    input.turnOrdinal == command.turnOrdinal &&
                    input.responseOrdinal == command.responseOrdinal
            }
            else -> false
        }
        if (!commonIdentityMatches || !surfaceMatches) {
            throw ImmutablePayloadConflictException(
                "tutor_answer_exposure_surface",
                command.modelTaskRequestId,
            )
        }
    }

    private suspend fun materialize(
        exposure: TutorAnswerExposureEntity,
        anchor: TutorSessionProblemAnchorEntity,
    ): Boolean {
        if (exposure.learnerId != anchor.learnerId) {
            throw ImmutablePayloadConflictException("tutor_answer_exposure_learner", exposure.exposureId)
        }
        findOutcomeByExposure(exposure.exposureId)?.let { existing ->
            verifyMaterialized(existing)
            return false
        }
        val sequence = allocateSequence(anchor.learnerId)
        val outcome = TutorAnswerExposureOutcome(
            outcomeId = outcomeId(exposure.exposureId),
            exposureId = exposure.exposureId,
            sessionId = exposure.sessionId,
            questionDocumentId = exposure.questionDocumentId,
            questionRevisionNumber = exposure.questionRevisionNumber,
            cycleOrdinal = exposure.cycleOrdinal,
            turnOrdinal = exposure.turnOrdinal,
            problemRevisionId = anchor.problemRevisionId,
            practiceUnitId = anchor.practiceUnitId,
            occurredAtEpochMillis = exposure.exposedAtEpochMillis,
            eventSequence = sequence,
        )
        val fingerprint = LearningLedgerFingerprint.tutorAnswerExposure(outcome)
        val entity = outcome.toEntity(anchor.learnerId, fingerprint)
        insertOutcome(entity)
        insertOutbox(entity.toOutbox())
        return true
    }

    private suspend fun verifyMaterialized(entity: TutorAnswerExposureOutcomeEntity) {
        val outcome = entity.toModel()
        if (LearningLedgerFingerprint.tutorAnswerExposure(outcome) != entity.canonicalFingerprint ||
            findOutbox(EVENT_KIND_TUTOR_ANSWER_EXPOSURE, entity.outcomeId) != entity.toOutbox()
        ) {
            throw ImmutablePayloadConflictException("tutor_answer_exposure_outcome", entity.outcomeId)
        }
    }

    private suspend fun allocateSequence(learnerId: String): Long {
        initializeSequence(LearningSequenceEntity(learnerId, 0))
        val current = checkNotNull(lastAllocatedSequence(learnerId))
        check(current < Long.MAX_VALUE) { "Learning sequence exhausted for $learnerId" }
        val next = current + 1
        check(compareAndSetSequence(learnerId, current, next) == 1) {
            "Learning sequence CAS failed inside a serialized Room transaction"
        }
        return next
    }
}

private fun PersistTutorAnswerExposureCommand.toExposureEntity(): TutorAnswerExposureEntity {
    val identity = "$sessionId\n$cycleOrdinal\n$turnOrdinal\n$surfaceKind\n" +
        "$modelTaskRequestId\n${responseOrdinal ?: 0}"
    return TutorAnswerExposureEntity(
        exposureId = "tutor-answer-exposure:${sha256(identity)}",
        learnerId = learnerId,
        sessionId = sessionId,
        questionDocumentId = questionDocumentId,
        questionRevisionNumber = revisionNumber,
        cycleOrdinal = cycleOrdinal,
        turnOrdinal = turnOrdinal,
        surfaceKind = surfaceKind,
        modelTaskRequestId = modelTaskRequestId,
        responseOrdinal = responseOrdinal,
        exposedAtEpochMillis = occurredAtEpochMillis,
    )
}

private fun TutorAnswerExposureEntity.sameIdentity(other: TutorAnswerExposureEntity): Boolean =
    exposureId == other.exposureId && learnerId == other.learnerId && sessionId == other.sessionId &&
        questionDocumentId == other.questionDocumentId &&
        questionRevisionNumber == other.questionRevisionNumber &&
        cycleOrdinal == other.cycleOrdinal && turnOrdinal == other.turnOrdinal &&
        surfaceKind == other.surfaceKind && modelTaskRequestId == other.modelTaskRequestId &&
        responseOrdinal == other.responseOrdinal

private fun TutorSessionProblemAnchorEntity.toRecord() = TutorSessionProblemAnchorRecord(
    learnerId = learnerId,
    sessionId = sessionId,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    source = anchorSource,
    anchoredAtEpochMillis = anchoredAtEpochMillis,
)

private fun TutorAnswerExposureEntity.toRecord(outcomeId: String?) = TutorAnswerExposureRecord(
    exposureId = exposureId,
    learnerId = learnerId,
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    questionRevisionNumber = questionRevisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
    surfaceKind = surfaceKind,
    modelTaskRequestId = modelTaskRequestId,
    responseOrdinal = responseOrdinal,
    exposedAtEpochMillis = exposedAtEpochMillis,
    outcomeId = outcomeId,
)

private fun TutorAnswerExposureOutcome.toEntity(
    learnerId: String,
    canonicalFingerprint: String,
) = TutorAnswerExposureOutcomeEntity(
    outcomeId = outcomeId,
    exposureId = exposureId,
    learnerId = learnerId,
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    questionRevisionNumber = questionRevisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    eventSequence = eventSequence,
    canonicalFingerprint = canonicalFingerprint,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

internal fun TutorAnswerExposureOutcomeEntity.toModel() = TutorAnswerExposureOutcome(
    outcomeId = outcomeId,
    exposureId = exposureId,
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    questionRevisionNumber = questionRevisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    occurredAtEpochMillis = occurredAtEpochMillis,
    eventSequence = eventSequence,
)

internal fun TutorAnswerExposureOutcomeEntity.toOutbox() = ProjectionOutboxEntity(
    outboxId = "learning-outbox:$learnerId:$EVENT_KIND_TUTOR_ANSWER_EXPOSURE:$outcomeId",
    learnerId = learnerId,
    outboxSequence = eventSequence,
    eventKind = EVENT_KIND_TUTOR_ANSWER_EXPOSURE,
    eventId = outcomeId,
    canonicalFingerprint = canonicalFingerprint,
    status = StudyDbValue.OutboxStatus.PENDING,
    createdAtEpochMillis = occurredAtEpochMillis,
)

private fun outcomeId(exposureId: String): String =
    "tutor-answer-exposure-outcome:${sha256(exposureId)}"

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
