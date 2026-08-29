package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.ApplyApprovedKnowledgeResearchPackCommand
import com.tingyun.smartmistakebook.core.database.ApplyReviewedKnowledgePackCommand
import com.tingyun.smartmistakebook.core.database.DecideKnowledgeResearchReviewBundleCommand
import com.tingyun.smartmistakebook.core.database.KnowledgeGroundingRequestRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeGroundingResolutionRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeGroundingSummaryRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeRelationRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSourceBindingSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeResearchReviewBundleRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSourceSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialNodeBindingRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialRecord
import com.tingyun.smartmistakebook.core.database.ResolveKnowledgeGroundingCommand
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

    fun observePendingKnowledgeGroundingRequests(
        limit: Int,
    ): Flow<List<KnowledgeGroundingRequestRecord>>

    fun observePendingKnowledgeGroundingSummaries(
        limit: Int,
    ): Flow<List<KnowledgeGroundingSummaryRecord>>

    fun observeReviewedKnowledgeCoverage(): Flow<List<ReviewedKnowledgeCoverageRecord>>

    suspend fun readPendingKnowledgeResearchReviewBundles(
        limit: Int,
    ): List<KnowledgeResearchReviewBundleRecord>

    suspend fun readKnowledgeResearchReviewBundle(
        bundleId: String,
    ): KnowledgeResearchReviewBundleRecord?

    suspend fun readKnowledgeGroundingResolution(
        groundingKey: String,
    ): KnowledgeGroundingResolutionRecord?
}

/**
 * Write port for knowledge base operations.
 */
interface KnowledgeWritePort {
    suspend fun importKnowledgeNodeRelations(
        relations: List<KnowledgeNodeRelationRecord>,
    )

    suspend fun importKnowledgeTeachingMaterials(
        materials: List<KnowledgeTeachingMaterialRecord>,
        bindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
        sources: List<KnowledgeSourceSeedRecord>,
    )

    suspend fun importKnowledgeBase(
        sources: List<KnowledgeSourceSeedRecord>,
        nodes: List<KnowledgeNodeSeedRecord>,
        bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
    )

    suspend fun applyReviewedKnowledgePack(
        command: ApplyReviewedKnowledgePackCommand,
    ): List<KnowledgeGroundingResolutionRecord>

    suspend fun enqueueKnowledgeResearchReviewBundle(
        bundle: KnowledgeResearchReviewBundleRecord,
    )

    suspend fun decideKnowledgeResearchReviewBundle(
        command: DecideKnowledgeResearchReviewBundleCommand,
    ): KnowledgeResearchReviewBundleRecord

    suspend fun applyApprovedKnowledgeResearchPack(
        command: ApplyApprovedKnowledgeResearchPackCommand,
    ): List<KnowledgeGroundingResolutionRecord>

    suspend fun recordKnowledgeGroundingRequests(
        requests: List<KnowledgeGroundingRequestRecord>,
    )

    suspend fun resolveKnowledgeGrounding(
        command: ResolveKnowledgeGroundingCommand,
    ): KnowledgeGroundingResolutionRecord
}

/**
 * Read surface for the knowledge-question lattice (v39 view): every
 * (knowledge node, practice unit) binding joined with that node's mastery
 * and that question's memory - the structured view the KC-to-question
 * weight propagation and tutor context assembly read.
 */
interface KnowledgeQuestionLatticePort {
    fun observeKnowledgeQuestionLattice(
        learnerId: String,
    ): Flow<List<KnowledgeQuestionLatticeRecord>> = kotlinx.coroutines.flow.flowOf(emptyList())
}

/** One lattice row (v39 view). Mastery/memory halves are null when absent. */
data class KnowledgeQuestionLatticeRecord(
    val practiceUnitId: String,
    val knowledgeNodeId: String,
    val bindingStrength: Double,
    val basisRevisionId: String,
    val bindingTaxonomyVersion: String,
    val entryId: String?,
    val entryStatus: String?,
    val kcLearnerId: String?,
    val memoryLearnerId: String?,
    val kcConservativeMastery: Double?,
    val kcStatus: String?,
    val kcLastEvidenceDirection: String?,
    val kcLastEvidenceAt: Long?,
    val questionStabilityDays: Double?,
    val questionDifficulty: Double?,
    val questionNextReviewAt: Long?,
    val questionLapseCount: Int?,
    val questionCrossDayAgain: Int?,
)
