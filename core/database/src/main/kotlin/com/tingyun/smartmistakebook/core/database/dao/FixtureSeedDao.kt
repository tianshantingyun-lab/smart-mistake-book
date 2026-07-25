package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentItemSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.ErrorBookEntryEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeMasteryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemMemoryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRevisionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewPlanEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueItemEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueKnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueReasonEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionRevisionEntity

internal data class FixtureSeedResult(
    val insertedProblemCount: Int,
    val insertedErrorBookEntryCount: Int,
)

/** Fixture facts are immutable: exact retries replay, any alternate payload is rejected. */
@Dao
internal abstract class FixtureSeedDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertProblem(item: ProblemEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertRevision(item: ProblemRevisionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPracticeUnit(item: PracticeUnitEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertErrorBookEntry(item: ErrorBookEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertKnowledgeNode(item: KnowledgeNodeEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertKnowledgeBinding(
        item: PracticeUnitKnowledgeBindingEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertRelation(item: ProblemRelationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertAssessmentItem(item: AssessmentItemSnapshotEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertAssessmentEvent(item: AssessmentEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMemoryState(item: ProblemMemoryStateEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMasteryState(item: KnowledgeMasteryStateEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertReviewPlan(item: ReviewPlanEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertReviewQueueItem(item: ReviewQueueItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertReviewQueueKnowledgeNode(
        item: ReviewQueueKnowledgeNodeEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertReviewQueueReason(item: ReviewQueueReasonEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertReviewSession(item: ReviewSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertReviewSessionRevision(
        item: ReviewSessionRevisionEntity,
    ): Long

    @Query("SELECT * FROM problem WHERE problem_id = :id LIMIT 1")
    protected abstract suspend fun findProblem(id: String): ProblemEntity?

    @Query("SELECT * FROM problem_revision WHERE revision_id = :id LIMIT 1")
    protected abstract suspend fun findRevision(id: String): ProblemRevisionEntity?

    @Query("SELECT * FROM practice_unit WHERE practice_unit_id = :id LIMIT 1")
    protected abstract suspend fun findPracticeUnit(id: String): PracticeUnitEntity?

    @Query("SELECT * FROM error_book_entry WHERE entry_id = :id LIMIT 1")
    protected abstract suspend fun findErrorBookEntry(id: String): ErrorBookEntryEntity?

    @Query("SELECT * FROM knowledge_node WHERE knowledge_node_id = :id LIMIT 1")
    protected abstract suspend fun findKnowledgeNode(id: String): KnowledgeNodeEntity?

    @Query("SELECT * FROM practice_unit_knowledge_binding WHERE binding_id = :id LIMIT 1")
    protected abstract suspend fun findKnowledgeBinding(
        id: String,
    ): PracticeUnitKnowledgeBindingEntity?

    @Query("SELECT * FROM problem_relation WHERE relation_id = :id LIMIT 1")
    protected abstract suspend fun findRelation(id: String): ProblemRelationEntity?

    @Query("SELECT * FROM assessment_item_snapshot WHERE assessment_item_snapshot_id = :id LIMIT 1")
    protected abstract suspend fun findAssessmentItem(id: String): AssessmentItemSnapshotEntity?

    @Query("SELECT * FROM assessment_event WHERE assessment_event_id = :id LIMIT 1")
    protected abstract suspend fun findAssessmentEvent(id: String): AssessmentEventEntity?

    @Query("SELECT * FROM problem_memory_state WHERE practice_unit_id = :id LIMIT 1")
    protected abstract suspend fun findMemoryState(id: String): ProblemMemoryStateEntity?

    @Query("SELECT * FROM knowledge_mastery_state WHERE knowledge_node_id = :id LIMIT 1")
    protected abstract suspend fun findMasteryState(id: String): KnowledgeMasteryStateEntity?

    @Query("SELECT * FROM review_plan WHERE review_plan_id = :id LIMIT 1")
    protected abstract suspend fun findReviewPlan(id: String): ReviewPlanEntity?

    @Query("SELECT * FROM review_queue_item WHERE review_queue_item_id = :id LIMIT 1")
    protected abstract suspend fun findReviewQueueItem(id: String): ReviewQueueItemEntity?

    @Query(
        """
        SELECT * FROM review_queue_knowledge_node
        WHERE review_queue_item_id = :queueItemId AND knowledge_node_id = :knowledgeNodeId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findReviewQueueKnowledgeNode(
        queueItemId: String,
        knowledgeNodeId: String,
    ): ReviewQueueKnowledgeNodeEntity?

    @Query(
        """
        SELECT * FROM review_queue_reason
        WHERE review_queue_item_id = :queueItemId AND reason = :reason
        LIMIT 1
        """,
    )
    protected abstract suspend fun findReviewQueueReason(
        queueItemId: String,
        reason: String,
    ): ReviewQueueReasonEntity?

    @Query("SELECT * FROM review_session WHERE review_session_id = :id LIMIT 1")
    protected abstract suspend fun findReviewSession(id: String): ReviewSessionEntity?

    @Query("SELECT COUNT(*) FROM review_queue_item WHERE review_plan_id = :reviewPlanId")
    protected abstract suspend fun countReviewQueueItems(reviewPlanId: String): Int

    @Query(
        """
        SELECT * FROM review_session_revision
        WHERE review_session_id = :sessionId AND state_version = :stateVersion
        LIMIT 1
        """,
    )
    protected abstract suspend fun findReviewSessionRevision(
        sessionId: String,
        stateVersion: Long,
    ): ReviewSessionRevisionEntity?

    @Transaction
    open suspend fun seed(
        problems: List<ProblemEntity>,
        revisions: List<ProblemRevisionEntity>,
        practiceUnits: List<PracticeUnitEntity>,
        errorBookEntries: List<ErrorBookEntryEntity>,
        knowledgeNodes: List<KnowledgeNodeEntity>,
        knowledgeBindings: List<PracticeUnitKnowledgeBindingEntity>,
        relations: List<ProblemRelationEntity>,
        assessmentItems: List<AssessmentItemSnapshotEntity>,
        assessmentEvents: List<AssessmentEventEntity>,
        memoryStates: List<ProblemMemoryStateEntity>,
        masteryStates: List<KnowledgeMasteryStateEntity>,
        reviewPlans: List<ReviewPlanEntity>,
        reviewQueueItems: List<ReviewQueueItemEntity>,
        reviewQueueKnowledgeNodes: List<ReviewQueueKnowledgeNodeEntity>,
        reviewQueueReasons: List<ReviewQueueReasonEntity>,
        reviewSessions: List<ReviewSessionEntity>,
        reviewSessionRevisions: List<ReviewSessionRevisionEntity>,
    ): FixtureSeedResult {
        var problemCount = 0
        var entryCount = 0
        problems.forEach { item ->
            if (saveImmutable("problem", item.problemId, item, ::insertProblem, ::findProblem)) {
                problemCount++
            }
        }
        revisions.forEach { item ->
            saveImmutable("problem_revision", item.revisionId, item, ::insertRevision, ::findRevision)
        }
        practiceUnits.forEach { item ->
            saveImmutable("practice_unit", item.practiceUnitId, item, ::insertPracticeUnit, ::findPracticeUnit)
        }
        errorBookEntries.forEach { item ->
            if (saveImmutable(
                    "error_book_entry",
                    item.entryId,
                    item,
                    ::insertErrorBookEntry,
                    ::findErrorBookEntry,
                )
            ) {
                entryCount++
            }
        }
        knowledgeNodes.forEach { item ->
            saveImmutable("knowledge_node", item.knowledgeNodeId, item, ::insertKnowledgeNode, ::findKnowledgeNode)
        }
        knowledgeBindings.forEach { item ->
            saveImmutable(
                "knowledge_binding",
                item.bindingId,
                item,
                ::insertKnowledgeBinding,
                ::findKnowledgeBinding,
            )
        }
        relations.forEach { item ->
            saveImmutable("problem_relation", item.relationId, item, ::insertRelation, ::findRelation)
        }
        assessmentItems.forEach { item ->
            saveImmutable(
                "assessment_item_snapshot",
                item.assessmentItemSnapshotId,
                item,
                ::insertAssessmentItem,
                ::findAssessmentItem,
            )
        }
        assessmentEvents.forEach { item ->
            saveImmutable(
                "assessment_event",
                item.assessmentEventId,
                item,
                ::insertAssessmentEvent,
                ::findAssessmentEvent,
            )
        }
        memoryStates.forEach { item ->
            saveImmutable(
                "problem_memory_state_fixture",
                item.practiceUnitId,
                item,
                ::insertMemoryState,
                ::findMemoryState,
            )
        }
        masteryStates.forEach { item ->
            saveImmutable(
                "knowledge_mastery_state_fixture",
                item.knowledgeNodeId,
                item,
                ::insertMasteryState,
                ::findMasteryState,
            )
        }
        reviewPlans.forEach { item ->
            saveImmutable("review_plan", item.reviewPlanId, item, ::insertReviewPlan, ::findReviewPlan)
        }
        reviewQueueItems.forEach { item ->
            saveImmutable(
                "review_queue_item",
                item.reviewQueueItemId,
                item,
                ::insertReviewQueueItem,
                ::findReviewQueueItem,
            )
        }
        reviewQueueKnowledgeNodes.forEach { item ->
            val id = "${item.reviewQueueItemId}:${item.knowledgeNodeId}"
            saveImmutableComposite(
                "review_queue_knowledge_node",
                id,
                item,
                ::insertReviewQueueKnowledgeNode,
            ) { findReviewQueueKnowledgeNode(item.reviewQueueItemId, item.knowledgeNodeId) }
        }
        reviewQueueReasons.forEach { item ->
            val id = "${item.reviewQueueItemId}:${item.reason}"
            saveImmutableComposite(
                "review_queue_reason",
                id,
                item,
                ::insertReviewQueueReason,
            ) { findReviewQueueReason(item.reviewQueueItemId, item.reason) }
        }
        val sessionKeys = reviewSessions.map { it.reviewSessionId to it.stateVersion }
        val revisionsByKey = reviewSessionRevisions.associateBy {
            it.reviewSessionId to it.stateVersion
        }
        if (sessionKeys.toSet().size != sessionKeys.size ||
            revisionsByKey.size != reviewSessionRevisions.size ||
            sessionKeys.toSet() != revisionsByKey.keys
        ) {
            throw ImmutablePayloadConflictException("review_session_fixture", "revision-set")
        }
        reviewSessions.forEach { item ->
            val plan = findReviewPlan(item.reviewPlanId)
            val validInitialState = plan != null &&
                countReviewQueueItems(item.reviewPlanId) > 0 &&
                item.stateVersion == 0L &&
                item.currentOrdinal == 0 &&
                item.status == StudyDbValue.ReviewStatus.IN_PROGRESS &&
                item.activeSessionKey == item.reviewPlanId &&
                item.completedAtEpochMillis == null &&
                item.lastActiveAtEpochMillis == item.startedAtEpochMillis &&
                item.timeBudgetSeconds == plan.timeBudgetSeconds &&
                item.projectionCheckpoint == plan.projectionCheckpoint
            if (!validInitialState) {
                throw ImmutablePayloadConflictException("review_session_fixture", item.reviewSessionId)
            }
            val key = item.reviewSessionId to item.stateVersion
            if (item.toFixtureRevision() != revisionsByKey.getValue(key)) {
                throw ImmutablePayloadConflictException("review_session_fixture", item.reviewSessionId)
            }
            val current = findReviewSession(item.reviewSessionId)
            if (current == null) {
                if (insertReviewSession(item) == -1L) {
                    throw ImmutablePayloadConflictException("review_session", item.reviewSessionId)
                }
            } else if (current.stateVersion < item.stateVersion ||
                (current.stateVersion == item.stateVersion && current != item) ||
                (current.stateVersion > item.stateVersion && !current.hasSameImmutableIdentity(item))
            ) {
                throw ImmutablePayloadConflictException("review_session", item.reviewSessionId)
            }
            // A progressed current row is valid only when its immutable historical revision below
            // proves this is an exact retry; the fixture must never roll a session backward.
        }
        reviewSessionRevisions.forEach { item ->
            val id = "${item.reviewSessionId}:${item.stateVersion}"
            saveImmutableComposite(
                "review_session_revision",
                id,
                item,
                ::insertReviewSessionRevision,
            ) { findReviewSessionRevision(item.reviewSessionId, item.stateVersion) }
        }
        return FixtureSeedResult(problemCount, entryCount)
    }

    private suspend fun <T> saveImmutable(
        entityType: String,
        entityId: String,
        requested: T,
        insert: suspend (T) -> Long,
        read: suspend (String) -> T?,
    ): Boolean {
        if (insert(requested) != -1L) return true
        if (read(entityId) == requested) return false
        throw ImmutablePayloadConflictException(entityType, entityId)
    }

    private suspend fun <T> saveImmutableComposite(
        entityType: String,
        entityId: String,
        requested: T,
        insert: suspend (T) -> Long,
        read: suspend () -> T?,
    ): Boolean {
        if (insert(requested) != -1L) return true
        if (read() == requested) return false
        throw ImmutablePayloadConflictException(entityType, entityId)
    }
}

private fun ReviewSessionEntity.toFixtureRevision() = ReviewSessionRevisionEntity(
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

private fun ReviewSessionEntity.hasSameImmutableIdentity(other: ReviewSessionEntity): Boolean =
    reviewSessionId == other.reviewSessionId &&
        reviewPlanId == other.reviewPlanId &&
        startedAtEpochMillis == other.startedAtEpochMillis &&
        timeBudgetSeconds == other.timeBudgetSeconds &&
        projectionCheckpoint == other.projectionCheckpoint
