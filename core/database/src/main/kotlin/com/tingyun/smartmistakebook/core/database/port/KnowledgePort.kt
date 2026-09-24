package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.ApplyApprovedKnowledgeResearchPackCommand
import com.tingyun.smartmistakebook.core.database.ApplyReviewedKnowledgePackCommand
import com.tingyun.smartmistakebook.core.database.ContentInstallStateRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeContentUpdateCommand
import com.tingyun.smartmistakebook.core.database.KnowledgeContentUpdateResult
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

    /**
     * 召回候选：`matched` 优先、父 topic 随后。
     *
     * [queryText] = 原始查询文本，仅供可选稠密腿（Stage-3）使用；`null` = 该调用方不要稠密腿
     * （纯词面 = Stage-1/2 行为）。传了文本也不保证被用到：稠密腿未装配/不可用时静默退回
     * 词面次序；**返回集合的成员与长度语义不变**（契约见 `DenseRecallReranker`）。
     */
    suspend fun readSubjectKnowledgeRecallCandidates(
        subject: String,
        searchFeatures: Set<String>,
        limit: Int,
        queryText: String? = null,
    ): List<KnowledgeNodeSeedRecord>

    suspend fun readKnowledgeNodesByIds(ids: Set<String>): List<KnowledgeNodeSeedRecord>

    /**
     * 这些 id 里仍然有效的那些（未退役）。
     *
     * "当前工作"路径用它把集合收一道；[readKnowledgeNodesByIds] 是历史解释路径、不过滤——
     * 分界见 `ProblemOrganizationDao.readActiveKnowledgeNodeIds` 的注释。
     */
    suspend fun readActiveKnowledgeNodeIds(ids: Set<String>): Set<String>
    suspend fun readKnowledgeSourcesByIds(ids: Set<String>): List<KnowledgeSourceSeedRecord>

    suspend fun readKnowledgeNodeSourceBindings(
        knowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeSourceBindingSeedRecord>

    /**
     * `已退役的知识点 id -> 取代它的节点 id`。投影用它把历史证据算到存活节点上。
     *
     * 只给原始边、不做传递闭包：链式合并（`X→Y` 之后 `Y→Z`）由领域侧的
     * `KnowledgeNodeSuccessors` 解析，那里有防环。
     */
    suspend fun readKnowledgeNodeSuccessors(): Map<String, String>

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

    companion object {
        /**
         * 一次取回的教学材料行数上界。**不是展示上限**——展示由调用方的字符预算决定
         * （讲题侧是 `TutorPlanInput.MAX_TEACHING_REFERENCE_MARKDOWN_CHARS`）。
         *
         * 取值要保证"预算先于取数用尽"，否则这条上界会变成一条没人明说的条数门：
         * 预算 20000 ÷ 内置包实测最短材料 64 字符 = 313，故 1024 留约 3 倍余量，
         * 使约束始终落在预算上。
         */
        const val MAX_TEACHING_MATERIAL_CANDIDATES = 1_024
    }
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

    /**
     * 内容调和：让库里的内容等于这份包（插入 / 原地更新 / 退役；退役**永不物删**）。
     *
     * 与 [importKnowledgeBase] 的区别是语义——那个是"追加"，只在库里一行都没有时可用；
     * 这个是"对齐"，因此是发布后更新知识库的唯一通道。逐对象判定，坏对象只跳过自己。
     */
    suspend fun applyKnowledgeContentUpdate(
        command: KnowledgeContentUpdateCommand,
    ): KnowledgeContentUpdateResult

    /** 读调和进度。返回 null 表示这个包从未被调和过（走全量）。 */
    suspend fun readContentInstallState(packId: String): ContentInstallStateRecord?

    /** 写调和进度。**必须在调和全部做完之后调用**——它是"跑完了"的唯一凭据。 */
    suspend fun recordContentInstallState(record: ContentInstallStateRecord)

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

/**
 * Knowledge-node-grained mastery reads for the tutor's `MASTERY_READ` tool.
 *
 * Why a second surface next to [KnowledgeQuestionLatticePort]: the lattice is
 * **question-grained**, so one node appears once per bound question and its row
 * count is a binding count. The tool needs one row per node, weakest first.
 *
 * [subject] is a disclosure boundary rather than a filter of convenience: the
 * tool may only ever return the subject the session is already working in, so
 * the query is scoped by it rather than filtered after the fact.
 */
interface TutorMasteryOverviewPort {
    suspend fun readSubjectMastery(
        learnerId: String,
        subject: String,
    ): List<SubjectMasteryRecord> = emptyList()

    suspend fun readMasteryAggregates(
        learnerId: String,
        knowledgeNodeIds: Set<String>,
    ): List<MasteryAggregateRecord> = emptyList()

    suspend fun countReviewableKnowledgeNodes(subject: String): Int = 0
}

/**
 * One knowledge node that has projected mastery. Nodes with no evidence have no
 * record at all — the caller reports them as a remainder count instead.
 */
data class SubjectMasteryRecord(
    val knowledgeNodeId: String,
    val displayName: String,
    val granularity: String,
    val nodeKind: String,
    val probabilityIndependentCorrect: Double,
    val lowerBoundIndependentCorrect: Double,
    val evidenceMass: Double,
    val status: String,
    val lastEvidenceAtEpochMillis: Long?,
    val lastEvidenceDirection: String?,
    val lastIndependentErrorAtEpochMillis: Long?,
    val boundQuestionCount: Int,
)

/**
 * Structured history aggregates behind one knowledge node: counts and
 * timestamps only, never event rows and never free text. That is what keeps the
 * read inside the disclosed "bounded learning evidence" class.
 */
data class MasteryAggregateRecord(
    val knowledgeNodeId: String,
    val independentCorrectCount: Int,
    val independentCorrectItemFamilyCount: Int,
    val independentCorrectStudyDayCount: Int,
    val lastIndependentCorrectAtEpochMillis: Long?,
    val independentErrorCount: Int,
    val lastIndependentErrorAtEpochMillis: Long?,
    val acceptedModelEvidenceCount: Int,
    val rejectedModelEvidenceCount: Int,
    val lastAcceptedModelEvidenceAtEpochMillis: Long?,
)
