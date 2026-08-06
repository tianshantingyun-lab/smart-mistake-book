package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Transaction
import androidx.room3.Update

/**
 * Review plan/session/queue/receipt write primitives.
 */
@Dao
internal abstract class StudentReviewWriteDao {
    @Query(
        """
        SELECT metadata_key, metadata_value,
               created_at_epoch_millis, updated_at_epoch_millis
        FROM student_store_metadata
        WHERE metadata_key = :metadataKey
        LIMIT 1
        """,
    )
    protected abstract suspend fun readMetadata(
        metadataKey: String,
    ): StudentStoreMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMetadata(
        metadata: StudentStoreMetadataEntity,
    ): Long

    @Query(
        """
        SELECT *
        FROM student_store_outbox
        WHERE event_id = :eventId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readOutboxByEventId(
        eventId: String,
    ): StudentStoreOutboxEntity?

    @Query(
        """
        SELECT *
        FROM student_store_outbox
        WHERE destination_store = :destinationStore
          AND source_store_generation = :sourceStoreGeneration
          AND idempotency_key = :idempotencyKey
        LIMIT 1
        """,
    )
    protected abstract suspend fun readOutboxByIdempotency(
        destinationStore: String,
        sourceStoreGeneration: String,
        idempotencyKey: String,
    ): StudentStoreOutboxEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertOutbox(message: StudentStoreOutboxEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertLearnerChange(
        change: StudentLearnerChangeEntity,
    ): Long

    @Query(
        """
        UPDATE student_learner_change
        SET change_version = change_version + 1
        WHERE learner_id = :learnerId
        """,
    )
    protected abstract suspend fun incrementChangeVersion(learnerId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertReviewPlan(plan: StudentReviewPlanEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertReviewSession(
        session: StudentReviewSessionEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertTrustedReviewPresentationFence(
        fence: StudentTrustedReviewPresentationFenceEntity,
    )

    @Query(
        """
        UPDATE student_review_session
        SET active_learner_id = :activeLearnerId,
            state = :nextState,
            session_version = session_version + 1,
            current_queue_item_id = :nextQueueItemId,
            current_presentation_id = :nextPresentationId,
            updated_at_epoch_millis = :updatedAtEpochMillis,
            completed_at_epoch_millis = :completedAtEpochMillis
        WHERE session_id = :sessionId
          AND learner_id = :learnerId
          AND plan_id = :planId
          AND active_learner_id = :learnerId
          AND state = 'ACTIVE'
          AND session_version = :expectedSessionVersion
          AND current_queue_item_id = :expectedQueueItemId
          AND current_presentation_id = :expectedPresentationId
        """,
    )
    protected abstract suspend fun compareAndSetReviewSession(
        sessionId: String,
        learnerId: String,
        planId: String,
        expectedSessionVersion: Long,
        expectedQueueItemId: String,
        expectedPresentationId: String,
        activeLearnerId: String?,
        nextState: String,
        nextQueueItemId: String?,
        nextPresentationId: String?,
        updatedAtEpochMillis: Long,
        completedAtEpochMillis: Long?,
    ): Int

    @Query(
        """
        UPDATE student_review_queue_item
        SET state = :nextState,
            state_changed_at_epoch_millis = :changedAtEpochMillis
        WHERE queue_item_id = :queueItemId
          AND plan_id = :planId
          AND learner_id = :learnerId
          AND state = :expectedState
        """,
    )
    protected abstract suspend fun compareAndSetReviewQueueItem(
        queueItemId: String,
        planId: String,
        learnerId: String,
        expectedState: String,
        nextState: String,
        changedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE student_review_queue_item
        SET state = 'REMOVED',
            reason_codes_wire = :auditReasonCodesWire,
            state_changed_at_epoch_millis = :changedAtEpochMillis
        WHERE queue_item_id = :queueItemId
          AND plan_id = :planId
          AND learner_id = :learnerId
          AND scheduled_order = :expectedScheduledOrder
          AND practice_unit_id = :expectedPracticeUnitId
          AND basis_revision_id = :expectedBasisRevisionId
          AND state_changed_at_epoch_millis = :expectedStateChangedAtEpochMillis
          AND state = 'READY'
          AND :changedAtEpochMillis >= state_changed_at_epoch_millis
        """,
    )
    protected abstract suspend fun compareAndRemoveUnreadyReviewQueueItem(
        queueItemId: String,
        planId: String,
        learnerId: String,
        expectedScheduledOrder: Int,
        expectedPracticeUnitId: String,
        expectedBasisRevisionId: String,
        expectedStateChangedAtEpochMillis: Long,
        auditReasonCodesWire: String,
        changedAtEpochMillis: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertReviewTransitionReceipt(
        receipt: StudentReviewTransitionReceiptEntity,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertReviewRevealReceipt(
        receipt: StudentReviewRevealReceiptEntity,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertReviewQueueItems(
        items: List<StudentReviewQueueItemEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertReviewSelfReportReceipt(
        receipt: StudentReviewSelfReportReceiptEntity,
    )

    @Update
    protected abstract suspend fun updateReviewQueueItem(item: StudentReviewQueueItemEntity): Int

    protected open suspend fun bumpChangeVersion(learnerId: String) {
        insertLearnerChange(
            StudentLearnerChangeEntity(
                learnerId = learnerId,
                changeVersion = 0,
            ),
        )
        check(incrementChangeVersion(learnerId) == 1) {
            "Learner change version did not advance exactly once"
        }
    }

    @Transaction
    open suspend fun ensureStoreGeneration(
        proposed: StudentStoreMetadataEntity,
    ): String {
        readMetadata(STORE_GENERATION_METADATA_KEY)?.let { return it.metadataValue }
        insertMetadata(proposed)
        return checkNotNull(readMetadata(STORE_GENERATION_METADATA_KEY)) {
            "Student mistake store generation was not persisted"
        }.metadataValue
    }

    protected open suspend fun insertOutboxExactlyOnce(
        message: StudentStoreOutboxEntity,
    ) {
        val existingById = readOutboxByEventId(message.eventId)
        val existingByIdempotency =
            readOutboxByIdempotency(
                destinationStore = message.destinationStore,
                sourceStoreGeneration = message.sourceStoreGeneration,
                idempotencyKey = message.idempotencyKey,
            )
        val existing = existingById ?: existingByIdempotency
        if (existing != null) {
            check(existing.sameImmutableOutboxContent(message)) {
                "Outbox event or idempotency key was replayed with different content"
            }
            return
        }
        check(insertOutbox(message) != -1L) {
            "Outbox insertion lost an idempotency race"
        }
    }
}
