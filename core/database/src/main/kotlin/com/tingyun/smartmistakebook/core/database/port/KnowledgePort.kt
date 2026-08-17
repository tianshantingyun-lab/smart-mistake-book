package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.KnowledgeGroundingResolutionRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeRelationRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSourceBindingSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeResearchReviewBundleRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSourceSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialNodeBindingRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialRecord
import com.tingyun.smartmistakebook.core.database.ReviewedKnowledgeCoverageRecord
import kotlinx.coroutines.flow.Flow

/**
 * Read-only port for knowledge base operations.
 */
interface KnowledgeReadPort {
    suspend fun readSubjectKnowledgeNodes(
        subject: String,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord>

    suspend fun readSubjectKnowledgeRecallCandidates(
        subject: String,
        searchFeatures: Set<String>,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord>

    suspend fun readKnowledgeNodesByIds(ids: Set<String>): List<KnowledgeNodeSeedRecord>
    suspend fun readKnowledgeSourcesByIds(ids: Set<String>): List<KnowledgeSourceSeedRecord>

    suspend fun readKnowledgeNodeSourceBindings(
        knowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeSourceBindingSeedRecord>

    suspend fun readSubjectKnowledgeNodeRelations(
        subject: String,
        limit: Int,
    ): List<KnowledgeNodeRelationRecord>

    suspend fun readKnowledgeNodeRelationsForDependents(
        subject: String,
        dependentKnowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeRelationRecord>

    suspend fun readKnowledgeTeachingMaterialsForNodes(
        subject: String,
        knowledgeNodeIds: Set<String>,
        limit: Int,
    ): List<KnowledgeTeachingMaterialRecord>

    suspend fun readKnowledgeTeachingMaterialsByIds(
        materialIds: Set<String>,
    ): List<KnowledgeTeachingMaterialRecord>

    suspend fun readKnowledgeTeachingMaterialNodeBindings(
        materialIds: Set<String>,
    ): List<KnowledgeTeachingMaterialNodeBindingRecord>

    fun observePendingKnowledgeGroundingRequests(): Flow<List<Any>>
    fun observePendingKnowledgeGroundingSummaries(): Flow<List<Any>>
    fun observeReviewedKnowledgeCoverage(): Flow<List<ReviewedKnowledgeCoverageRecord>>

    suspend fun readPendingKnowledgeResearchReviewBundles(): List<KnowledgeResearchReviewBundleRecord>
    suspend fun readKnowledgeResearchReviewBundle(
        bundleId: String,
    ): KnowledgeResearchReviewBundleRecord?

    suspend fun readKnowledgeGroundingResolution(
        requestId: String,
    ): KnowledgeGroundingResolutionRecord?
}

/**
 * Write port for knowledge base operations.
 */
interface KnowledgeWritePort {
    suspend fun importKnowledgeNodeRelations(
        subject: String,
        relations: List<KnowledgeNodeRelationRecord>,
    )

    suspend fun importKnowledgeTeachingMaterials(
        subject: String,
        materials: List<KnowledgeTeachingMaterialRecord>,
    )

    suspend fun importKnowledgeBase(
        subject: String,
        nodes: List<KnowledgeNodeSeedRecord>,
        sources: List<KnowledgeSourceSeedRecord>,
    )

    suspend fun applyReviewedKnowledgePack(
        subject: String,
        nodes: List<KnowledgeNodeSeedRecord>,
        relations: List<KnowledgeNodeRelationRecord>,
    )

    suspend fun enqueueKnowledgeResearchReviewBundle(
        bundle: KnowledgeResearchReviewBundleRecord,
    )

    suspend fun decideKnowledgeResearchReviewBundle(
        bundleId: String,
        decision: String,
        decisionNote: String?,
        decidedAtEpochMillis: Long,
    )

    suspend fun applyApprovedKnowledgeResearchPack(
        subject: String,
        nodes: List<KnowledgeNodeSeedRecord>,
    )

    suspend fun recordKnowledgeGroundingRequests(
        requests: List<Any>,
    )

    suspend fun resolveKnowledgeGrounding(
        requestId: String,
        resolution: String,
        resolvedAtEpochMillis: Long,
    )
}
