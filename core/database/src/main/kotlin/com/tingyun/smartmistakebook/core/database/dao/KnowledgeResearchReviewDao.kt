package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeResearchReviewBundleEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeResearchReviewSourceEntity

@Dao
internal interface KnowledgeResearchReviewDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBundle(bundle: KnowledgeResearchReviewBundleEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSources(sources: List<KnowledgeResearchReviewSourceEntity>): List<Long>

    @Query(
        "SELECT * FROM knowledge_research_review_bundle WHERE bundle_id = :bundleId",
    )
    suspend fun readBundle(bundleId: String): KnowledgeResearchReviewBundleEntity?

    @Query(
        """
        SELECT * FROM knowledge_research_review_source
        WHERE bundle_id = :bundleId
        ORDER BY source_ordinal ASC
        """,
    )
    suspend fun readSources(bundleId: String): List<KnowledgeResearchReviewSourceEntity>

    @Query(
        """
        SELECT * FROM knowledge_research_review_source
        WHERE bundle_id IN (:bundleIds)
        ORDER BY bundle_id ASC, source_ordinal ASC
        """,
    )
    suspend fun readSourcesForBundles(
        bundleIds: Set<String>,
    ): List<KnowledgeResearchReviewSourceEntity>

    @Query(
        """
        SELECT * FROM knowledge_research_review_bundle
        WHERE status = 'PENDING_REVIEW'
        ORDER BY created_at_epoch_millis ASC, bundle_id ASC
        LIMIT :limit
        """,
    )
    suspend fun readPendingBundles(limit: Int): List<KnowledgeResearchReviewBundleEntity>

    @Query(
        """
        UPDATE knowledge_research_review_bundle
        SET status = :decisionStatus,
            reviewer_reference = :reviewerReference,
            decision_note = :decisionNote,
            reviewed_at_epoch_millis = :decidedAtEpochMillis,
            updated_at_epoch_millis = :decidedAtEpochMillis
        WHERE bundle_id = :bundleId
          AND status = 'PENDING_REVIEW'
          AND created_at_epoch_millis <= :decidedAtEpochMillis
        """,
    )
    suspend fun decidePending(
        bundleId: String,
        decisionStatus: String,
        reviewerReference: String,
        decisionNote: String,
        decidedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE knowledge_research_review_bundle
        SET status = 'APPLIED',
            applied_pack_fingerprint = :packFingerprint,
            applied_at_epoch_millis = :appliedAtEpochMillis,
            updated_at_epoch_millis = :appliedAtEpochMillis
        WHERE bundle_id = :bundleId
          AND status = 'APPROVED'
          AND reviewed_at_epoch_millis <= :appliedAtEpochMillis
        """,
    )
    suspend fun markApprovedAsApplied(
        bundleId: String,
        packFingerprint: String,
        appliedAtEpochMillis: Long,
    ): Int

    @Transaction
    suspend fun enqueue(
        bundle: KnowledgeResearchReviewBundleEntity,
        sources: List<KnowledgeResearchReviewSourceEntity>,
    ) {
        val existing = readBundle(bundle.bundleId)
        if (existing != null) {
            if (
                !existing.sameResearchPayloadAs(bundle) ||
                readSources(bundle.bundleId) != sources
            ) {
                throw ImmutablePayloadConflictException(
                    entityType = "knowledge research review bundle",
                    entityId = bundle.bundleId,
                )
            }
            return
        }

        if (insertBundle(bundle) == INSERT_IGNORED) {
            throw ImmutablePayloadConflictException(
                entityType = "knowledge research review bundle",
                entityId = bundle.bundleId,
            )
        }
        if (insertSources(sources).any { it == INSERT_IGNORED }) {
            throw ImmutablePayloadConflictException(
                entityType = "knowledge research review source",
                entityId = bundle.bundleId,
            )
        }
    }

    private companion object {
        const val INSERT_IGNORED = -1L
    }
}

private fun KnowledgeResearchReviewBundleEntity.sameResearchPayloadAs(
    other: KnowledgeResearchReviewBundleEntity,
): Boolean =
    bundleId == other.bundleId &&
        groundingKey == other.groundingKey &&
        subject == other.subject &&
        query == other.query &&
        expectedParentKnowledgeDisplayName == other.expectedParentKnowledgeDisplayName &&
        relatedQuestionCount == other.relatedQuestionCount &&
        workflowVersion == other.workflowVersion &&
        createdAtEpochMillis == other.createdAtEpochMillis
