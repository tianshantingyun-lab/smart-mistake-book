package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Relation
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.ReviewSessionAdvanceCommand
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.entity.ActiveReviewPlanSlotEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewPlanEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueItemEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueKnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueReasonEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionAdvanceReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionRevisionEntity
import kotlinx.coroutines.flow.Flow

internal data class ReviewQueueAggregate(
    @Embedded
    val item: ReviewQueueItemEntity,
    @Relation(
        parentColumns = ["review_queue_item_id"],
        entityColumns = ["review_queue_item_id"],
    )
    val knowledgeNodes: List<ReviewQueueKnowledgeNodeEntity>,
    @Relation(
        parentColumns = ["review_queue_item_id"],
        entityColumns = ["review_queue_item_id"],
    )
    val reasons: List<ReviewQueueReasonEntity>,
)

internal data class ReviewPlanAggregate(
    @Embedded
    val plan: ReviewPlanEntity,
    @Relation(
        entity = ReviewQueueItemEntity::class,
        parentColumns = ["review_plan_id"],
        entityColumns = ["review_plan_id"],
    )
    val queue: List<ReviewQueueAggregate>,
    @Relation(
        parentColumns = ["review_plan_id"],
        entityColumns = ["review_plan_id"],
    )
    val sessions: List<ReviewSessionEntity>,
    @Relation(
        parentColumns = ["review_plan_id"],
        entityColumns = ["current_review_plan_id"],
    )
    val currentSlots: List<ActiveReviewPlanSlotEntity>,
)

internal data class ReviewSessionAdvanceTransactionResult(
    val created: Boolean,
    val session: ReviewSessionEntity,
    val receipt: ReviewSessionAdvanceReceiptEntity,
)

private val latestReviewSessionComparator =
    compareBy<ReviewSessionEntity>(ReviewSessionEntity::lastActiveAtEpochMillis)
        .thenBy(ReviewSessionEntity::startedAtEpochMillis)
        .thenBy(ReviewSessionEntity::stateVersion)
        .thenBy(ReviewSessionEntity::reviewSessionId)

internal fun ReviewPlanAggregate.activeSessionHead(): ReviewSessionEntity? =
    sessions.singleOrNull { it.status == StudyDbValue.ReviewStatus.IN_PROGRESS }

internal fun ReviewPlanAggregate.latestSessionHead(): ReviewSessionEntity? =
    activeSessionHead() ?: sessions.maxWithOrNull(latestReviewSessionComparator)

@Dao
internal interface ReviewDao {
    @Transaction
    @Query("SELECT * FROM review_plan WHERE review_plan_id = :reviewPlanId LIMIT 1")
    fun observePlan(reviewPlanId: String): Flow<ReviewPlanAggregate?>

    @Transaction
    @Query(
        """
        SELECT rp.*
        FROM review_plan AS rp
        JOIN review_session AS rs
          ON rs.review_plan_id = rp.review_plan_id
        WHERE rs.review_session_id = :sessionId
        LIMIT 1
        """,
    )
    fun observePlanForSession(sessionId: String): Flow<ReviewPlanAggregate?>

    @Transaction
    @Query(
        """
        SELECT rp.*
        FROM review_plan AS rp
        JOIN review_session AS rs
          ON rs.review_plan_id = rp.review_plan_id
        WHERE rp.learner_id = :learnerId
          AND rs.status = 'IN_PROGRESS'
        ORDER BY rs.last_active_at_epoch_millis DESC,
                 rs.started_at_epoch_millis DESC,
                 rs.state_version DESC,
                 rs.review_session_id DESC
        """,
    )
    fun observeActivePlans(learnerId: String): Flow<List<ReviewPlanAggregate>>

    @Transaction
    @Query(
        """
        SELECT * FROM review_plan
        WHERE review_plan_id = (
            SELECT current_review_plan_id
            FROM active_review_plan_slot
            WHERE learner_id = :learnerId
              AND local_day_epoch_day = :localDayEpochDay
              AND time_zone_id = :timeZoneId
        )
        LIMIT 1
        """,
    )
    fun observeCurrentPlan(
        learnerId: String,
        localDayEpochDay: Long,
        timeZoneId: String,
    ): Flow<ReviewPlanAggregate?>

    @Query(
        """
        SELECT DISTINCT rp.local_day_epoch_day
        FROM review_plan AS rp
        JOIN review_session AS rs
          ON rs.review_plan_id = rp.review_plan_id
        WHERE rp.learner_id = :learnerId
          AND rs.status = 'COMPLETED'
        ORDER BY rp.local_day_epoch_day DESC
        LIMIT :limit
        """,
    )
    fun observeCompletedLocalDays(
        learnerId: String,
        limit: Int,
    ): Flow<List<Long>>
}

@Dao
internal abstract class ReviewPlanTransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPlan(plan: ReviewPlanEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertQueueItems(items: List<ReviewQueueItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertKnowledgeNodes(items: List<ReviewQueueKnowledgeNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertReasons(items: List<ReviewQueueReasonEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSession(session: ReviewSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSessionRevision(revision: ReviewSessionRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAdvanceReceipt(
        receipt: ReviewSessionAdvanceReceiptEntity,
    )

    @Upsert
    protected abstract suspend fun upsertCurrentSlot(slot: ActiveReviewPlanSlotEntity)

    @Query("SELECT * FROM review_plan WHERE review_plan_id = :reviewPlanId LIMIT 1")
    protected abstract suspend fun findPlan(reviewPlanId: String): ReviewPlanEntity?

    @Query(
        """
        SELECT * FROM review_queue_item
        WHERE review_plan_id = :reviewPlanId
        ORDER BY ordinal ASC
        """,
    )
    protected abstract suspend fun findQueue(reviewPlanId: String): List<ReviewQueueItemEntity>

    @Query(
        """
        SELECT node.*
        FROM review_queue_knowledge_node AS node
        JOIN review_queue_item AS item
          ON item.review_queue_item_id = node.review_queue_item_id
        WHERE item.review_plan_id = :reviewPlanId
        ORDER BY node.review_queue_item_id ASC, node.knowledge_node_id ASC
        """,
    )
    protected abstract suspend fun findKnowledgeNodes(
        reviewPlanId: String,
    ): List<ReviewQueueKnowledgeNodeEntity>

    @Query(
        """
        SELECT reason.*
        FROM review_queue_reason AS reason
        JOIN review_queue_item AS item
          ON item.review_queue_item_id = reason.review_queue_item_id
        WHERE item.review_plan_id = :reviewPlanId
        ORDER BY reason.review_queue_item_id ASC, reason.reason ASC
        """,
    )
    protected abstract suspend fun findReasons(reviewPlanId: String): List<ReviewQueueReasonEntity>

    @Query("SELECT * FROM review_session WHERE review_session_id = :reviewSessionId LIMIT 1")
    protected abstract suspend fun findSession(reviewSessionId: String): ReviewSessionEntity?

    @Query(
        """
        SELECT rs.*
        FROM review_session AS rs
        JOIN review_plan AS rp
          ON rp.review_plan_id = rs.review_plan_id
        WHERE rp.learner_id = :learnerId
          AND rs.status = 'IN_PROGRESS'
        ORDER BY rs.last_active_at_epoch_millis DESC,
                 rs.started_at_epoch_millis DESC,
                 rs.state_version DESC,
                 rs.review_session_id DESC
        """,
    )
    protected abstract suspend fun findActiveSessionsForLearner(
        learnerId: String,
    ): List<ReviewSessionEntity>

    @Query(
        """
        SELECT * FROM review_session_revision
        WHERE review_session_id = :reviewSessionId AND state_version = :stateVersion
        LIMIT 1
        """,
    )
    protected abstract suspend fun findSessionRevision(
        reviewSessionId: String,
        stateVersion: Long,
    ): ReviewSessionRevisionEntity?

    @Query(
        """
        SELECT * FROM review_session_advance_receipt
        WHERE attempt_id = :attemptId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findAdvanceReceipt(
        attemptId: String,
    ): ReviewSessionAdvanceReceiptEntity?

    @Query("SELECT * FROM review_queue_item WHERE review_queue_item_id = :queueItemId LIMIT 1")
    protected abstract suspend fun findQueueItem(queueItemId: String): ReviewQueueItemEntity?

    @Query(
        """
        SELECT * FROM review_queue_item
        WHERE review_plan_id = :reviewPlanId AND ordinal = :ordinal
        LIMIT 1
        """,
    )
    protected abstract suspend fun findQueueItemAtOrdinal(
        reviewPlanId: String,
        ordinal: Int,
    ): ReviewQueueItemEntity?

    @Query(
        """
        SELECT
            attempt.learner_id,
            attempt.attempt_id,
            attempt.submission_id,
            attempt.presentation_id,
            snapshot.practice_unit_id,
            attempt.occurred_at_epoch_millis,
            attempt.event_sequence,
            attempt.canonical_fingerprint,
            outbox.outbox_id
        FROM attempt_event AS attempt
        JOIN assessment_evidence_snapshot AS snapshot
          ON snapshot.snapshot_id = attempt.assessment_snapshot_id
        JOIN projection_outbox AS outbox
          ON outbox.event_kind = 'ATTEMPT'
         AND outbox.event_id = attempt.attempt_id
         AND outbox.learner_id = attempt.learner_id
         AND outbox.outbox_sequence = attempt.event_sequence
         AND outbox.canonical_fingerprint = attempt.canonical_fingerprint
        WHERE attempt.attempt_id = :attemptId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findAttemptAdvanceProof(
        attemptId: String,
    ): AttemptAdvanceProofRow?

    @Query("SELECT COUNT(*) FROM review_queue_item WHERE review_plan_id = :reviewPlanId")
    protected abstract suspend fun countQueueItems(reviewPlanId: String): Int

    @Query(
        """
        UPDATE review_session
        SET status = :status,
            active_session_key = :activeSessionKey,
            last_active_at_epoch_millis = :lastActiveAtEpochMillis,
            completed_at_epoch_millis = :completedAtEpochMillis,
            current_ordinal = current_ordinal + 1,
            state_version = state_version + 1
        WHERE review_session_id = :reviewSessionId
          AND state_version = :expectedStateVersion
          AND current_ordinal = :expectedOrdinal
          AND status = :inProgressStatus
          AND active_session_key = review_plan_id
        """,
    )
    protected abstract suspend fun compareAndSetSessionState(
        reviewSessionId: String,
        expectedStateVersion: Long,
        expectedOrdinal: Int,
        inProgressStatus: String,
        status: String,
        activeSessionKey: String?,
        lastActiveAtEpochMillis: Long,
        completedAtEpochMillis: Long?,
    ): Int

    @Transaction
    open suspend fun savePlan(
        plan: ReviewPlanEntity,
        queue: List<ReviewQueueItemEntity>,
        knowledgeNodes: List<ReviewQueueKnowledgeNodeEntity>,
        reasons: List<ReviewQueueReasonEntity>,
        activeSession: ReviewSessionEntity?,
        isCurrent: Boolean,
    ) {
        val canonicalQueue = queue.sortedBy(ReviewQueueItemEntity::ordinal)
        val canonicalNodes = knowledgeNodes.sortedWith(
            compareBy(ReviewQueueKnowledgeNodeEntity::reviewQueueItemId)
                .thenBy(ReviewQueueKnowledgeNodeEntity::knowledgeNodeId),
        )
        val canonicalReasons = reasons.sortedWith(
            compareBy(ReviewQueueReasonEntity::reviewQueueItemId)
                .thenBy(ReviewQueueReasonEntity::reason),
        )
        val created = insertPlan(plan) != -1L
        if (created) {
            if (canonicalQueue.isNotEmpty()) insertQueueItems(canonicalQueue)
            if (canonicalNodes.isNotEmpty()) insertKnowledgeNodes(canonicalNodes)
            if (canonicalReasons.isNotEmpty()) insertReasons(canonicalReasons)
        } else {
            if (findPlan(plan.reviewPlanId) != plan ||
                findQueue(plan.reviewPlanId) != canonicalQueue ||
                findKnowledgeNodes(plan.reviewPlanId) != canonicalNodes ||
                findReasons(plan.reviewPlanId) != canonicalReasons
            ) {
                throw ImmutablePayloadConflictException("review_plan_bundle", plan.reviewPlanId)
            }
        }
        activeSession?.let { saveSession(it) }
        if (isCurrent) {
            upsertCurrentSlot(
                ActiveReviewPlanSlotEntity(
                    learnerId = plan.learnerId,
                    localDayEpochDay = plan.localDayEpochDay,
                    timeZoneId = plan.timeZoneId,
                    currentReviewPlanId = plan.reviewPlanId,
                    updatedAtEpochMillis = plan.planningAtEpochMillis,
                ),
            )
        }
    }

    @Transaction
    open suspend fun saveSession(session: ReviewSessionEntity): Boolean {
        val revision = session.toRevision()
        val current = findSession(session.reviewSessionId)
        if (current == null) {
            requireInitialSession(session)
            if (insertSession(session) == -1L) {
                throw ImmutablePayloadConflictException("review_session", session.reviewSessionId)
            }
            insertSessionRevision(revision)
            return true
        }

        findSessionRevision(session.reviewSessionId, session.stateVersion)?.let { existingRevision ->
            if (existingRevision == revision && current.hasSameImmutableIdentity(session)) return false
            throw ImmutablePayloadConflictException("review_session_revision", session.reviewSessionId)
        }
        throw ImmutablePayloadConflictException("review_session", session.reviewSessionId)
    }

    @Transaction
    open suspend fun advanceSession(
        command: ReviewSessionAdvanceCommand,
        attemptCreatedInCurrentTransaction: Boolean,
    ): ReviewSessionAdvanceTransactionResult {
        if (command.expectedStateVersion == Long.MAX_VALUE) {
            throw ImmutablePayloadConflictException("review_session", command.sessionId)
        }
        val requestedReceipt = command.toReceipt()
        findAdvanceReceipt(command.attemptId)?.let { existing ->
            return readAdvanceReplay(existing, requestedReceipt)
        }
        if (!attemptCreatedInCurrentTransaction) {
            throw ImmutablePayloadConflictException("review_attempt", command.attemptId)
        }

        val current = findSession(command.sessionId)
            ?: throw ImmutablePayloadConflictException("review_session", command.sessionId)
        val currentRevision = findSessionRevision(command.sessionId, command.expectedStateVersion)
        val plan = findPlan(current.reviewPlanId)
            ?: throw ImmutablePayloadConflictException("review_plan", current.reviewPlanId)
        val activeLearnerSessions = findActiveSessionsForLearner(plan.learnerId)
        val queueSize = countQueueItems(current.reviewPlanId)
        val queueItem = findQueueItemAtOrdinal(current.reviewPlanId, current.currentOrdinal)
        val proof = findAttemptAdvanceProof(command.attemptId)
            ?: throw ImmutablePayloadConflictException("review_attempt", command.attemptId)

        val validCurrentState = current.status == StudyDbValue.ReviewStatus.IN_PROGRESS &&
            current.activeSessionKey == current.reviewPlanId &&
            current.stateVersion == command.expectedStateVersion &&
            current.stateVersion == current.currentOrdinal.toLong() &&
            current.completedAtEpochMillis == null &&
            currentRevision == current.toRevision() &&
            current.currentOrdinal in 0 until queueSize &&
            activeLearnerSessions.size == 1 &&
            activeLearnerSessions.single().reviewSessionId == current.reviewSessionId
        val validQueueItem = queueItem != null &&
            queueItem.reviewQueueItemId == command.reviewQueueItemId &&
            queueItem.practiceUnitId == command.practiceUnitId &&
            queueItem.status == StudyDbValue.ReviewStatus.PLANNED
        val validAttempt = proof.learnerId == plan.learnerId &&
            proof.attemptId == command.attemptId &&
            proof.submissionId == command.submissionId &&
            proof.presentationId == command.presentationId &&
            proof.practiceUnitId == command.practiceUnitId &&
            proof.occurredAtEpochMillis >= current.startedAtEpochMillis &&
            proof.occurredAtEpochMillis <= command.occurredAtEpochMillis
        if (!validCurrentState || !validQueueItem || !validAttempt ||
            command.occurredAtEpochMillis < current.lastActiveAtEpochMillis
        ) {
            throw ImmutablePayloadConflictException("review_session_advance", command.attemptId)
        }

        val nextOrdinal = current.currentOrdinal + 1
        val completed = nextOrdinal == queueSize
        val next = current.copy(
            status = if (completed) {
                StudyDbValue.ReviewStatus.COMPLETED
            } else {
                StudyDbValue.ReviewStatus.IN_PROGRESS
            },
            activeSessionKey = current.reviewPlanId.takeUnless { completed },
            lastActiveAtEpochMillis = command.occurredAtEpochMillis,
            completedAtEpochMillis = command.occurredAtEpochMillis.takeIf { completed },
            currentOrdinal = nextOrdinal,
            stateVersion = current.stateVersion + 1,
        )
        if (compareAndSetSessionState(
                reviewSessionId = current.reviewSessionId,
                expectedStateVersion = current.stateVersion,
                expectedOrdinal = current.currentOrdinal,
                inProgressStatus = StudyDbValue.ReviewStatus.IN_PROGRESS,
                status = next.status,
                activeSessionKey = next.activeSessionKey,
                lastActiveAtEpochMillis = next.lastActiveAtEpochMillis,
                completedAtEpochMillis = next.completedAtEpochMillis,
            ) != 1
        ) {
            throw ImmutablePayloadConflictException("review_session", command.sessionId)
        }
        insertSessionRevision(next.toRevision())
        insertAdvanceReceipt(requestedReceipt)
        return ReviewSessionAdvanceTransactionResult(
            created = true,
            session = next,
            receipt = requestedReceipt,
        )
    }

    private suspend fun requireInitialSession(session: ReviewSessionEntity) {
        val plan = findPlan(session.reviewPlanId)
        val queueSize = countQueueItems(session.reviewPlanId)
        val activeLearnerSessions = plan?.let { findActiveSessionsForLearner(it.learnerId) }.orEmpty()
        val valid = plan != null &&
            queueSize > 0 &&
            activeLearnerSessions.isEmpty() &&
            session.stateVersion == 0L &&
            session.currentOrdinal == 0 &&
            session.status == StudyDbValue.ReviewStatus.IN_PROGRESS &&
            session.activeSessionKey == session.reviewPlanId &&
            session.completedAtEpochMillis == null &&
            session.lastActiveAtEpochMillis == session.startedAtEpochMillis &&
            session.timeBudgetSeconds == plan.timeBudgetSeconds &&
            session.projectionCheckpoint == plan.projectionCheckpoint
        if (!valid) {
            throw ImmutablePayloadConflictException("review_session", session.reviewSessionId)
        }
    }

    private suspend fun readAdvanceReplay(
        existing: ReviewSessionAdvanceReceiptEntity,
        requested: ReviewSessionAdvanceReceiptEntity,
    ): ReviewSessionAdvanceTransactionResult {
        if (existing != requested) {
            throw ImmutablePayloadConflictException("review_session_advance_receipt", existing.attemptId)
        }
        val fromRevision = findSessionRevision(existing.reviewSessionId, existing.fromVersion)
        val toRevision = findSessionRevision(existing.reviewSessionId, existing.toVersion)
        val queueItem = findQueueItem(existing.reviewQueueItemId)
        val plan = toRevision?.let { findPlan(it.reviewPlanId) }
        val queueSize = toRevision?.let { countQueueItems(it.reviewPlanId) } ?: 0
        val proof = findAttemptAdvanceProof(existing.attemptId)
        val completed = toRevision?.currentOrdinal == queueSize
        val valid = existing.fromVersion < Long.MAX_VALUE &&
            existing.toVersion == existing.fromVersion + 1 &&
            fromRevision != null &&
            toRevision != null &&
            plan != null &&
            queueItem != null &&
            proof != null &&
            queueSize > 0 &&
            fromRevision.status == StudyDbValue.ReviewStatus.IN_PROGRESS &&
            fromRevision.currentOrdinal.toLong() == existing.fromVersion &&
            fromRevision.hasSameImmutableIdentity(toRevision) &&
            toRevision.stateVersion == existing.toVersion &&
            toRevision.currentOrdinal.toLong() == existing.toVersion &&
            toRevision.lastActiveAtEpochMillis == existing.occurredAtEpochMillis &&
            toRevision.status == if (completed) {
                StudyDbValue.ReviewStatus.COMPLETED
            } else {
                StudyDbValue.ReviewStatus.IN_PROGRESS
            } &&
            toRevision.completedAtEpochMillis == existing.occurredAtEpochMillis.takeIf { completed } &&
            queueItem.reviewPlanId == toRevision.reviewPlanId &&
            queueItem.ordinal.toLong() == existing.fromVersion &&
            queueItem.practiceUnitId == existing.practiceUnitId &&
            proof.learnerId == plan.learnerId &&
            proof.attemptId == existing.attemptId &&
            proof.submissionId == existing.submissionId &&
            proof.presentationId == existing.presentationId &&
            proof.practiceUnitId == existing.practiceUnitId &&
            proof.occurredAtEpochMillis >= fromRevision.startedAtEpochMillis &&
            proof.occurredAtEpochMillis <= existing.occurredAtEpochMillis
        if (!valid) {
            throw ImmutablePayloadConflictException("review_session_advance_receipt", existing.attemptId)
        }
        return ReviewSessionAdvanceTransactionResult(
            created = false,
            session = toRevision.toSession(),
            receipt = existing,
        )
    }
}

private fun ReviewSessionEntity.toRevision() = ReviewSessionRevisionEntity(
    reviewSessionId = reviewSessionId,
    stateVersion = stateVersion,
    reviewPlanId = reviewPlanId,
    status = status,
    startedAtEpochMillis = startedAtEpochMillis,
    lastActiveAtEpochMillis = lastActiveAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    currentOrdinal = currentOrdinal,
    timeBudgetSeconds = timeBudgetSeconds,
    projectionCheckpoint = projectionCheckpoint,
)

private fun ReviewSessionAdvanceCommand.toReceipt() = ReviewSessionAdvanceReceiptEntity(
    reviewSessionId = sessionId,
    fromVersion = expectedStateVersion,
    toVersion = expectedStateVersion + 1,
    reviewQueueItemId = reviewQueueItemId,
    practiceUnitId = practiceUnitId,
    attemptId = attemptId,
    submissionId = submissionId,
    presentationId = presentationId,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

private fun ReviewSessionRevisionEntity.toSession() = ReviewSessionEntity(
    reviewSessionId = reviewSessionId,
    reviewPlanId = reviewPlanId,
    status = status,
    activeSessionKey = reviewPlanId.takeIf { status == StudyDbValue.ReviewStatus.IN_PROGRESS },
    startedAtEpochMillis = startedAtEpochMillis,
    lastActiveAtEpochMillis = lastActiveAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    currentOrdinal = currentOrdinal,
    timeBudgetSeconds = timeBudgetSeconds,
    projectionCheckpoint = projectionCheckpoint,
    stateVersion = stateVersion,
)

private fun ReviewSessionEntity.hasSameImmutableIdentity(other: ReviewSessionEntity): Boolean =
    reviewSessionId == other.reviewSessionId &&
        reviewPlanId == other.reviewPlanId &&
        startedAtEpochMillis == other.startedAtEpochMillis &&
        timeBudgetSeconds == other.timeBudgetSeconds &&
        projectionCheckpoint == other.projectionCheckpoint

private fun ReviewSessionRevisionEntity.hasSameImmutableIdentity(
    other: ReviewSessionRevisionEntity,
): Boolean = reviewSessionId == other.reviewSessionId &&
    reviewPlanId == other.reviewPlanId &&
    startedAtEpochMillis == other.startedAtEpochMillis &&
    timeBudgetSeconds == other.timeBudgetSeconds &&
    projectionCheckpoint == other.projectionCheckpoint
