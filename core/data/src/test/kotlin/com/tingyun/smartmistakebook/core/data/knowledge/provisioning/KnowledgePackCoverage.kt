package com.tingyun.smartmistakebook.core.data.knowledge.provisioning

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSourceSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialNodeBindingRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialRecord
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.SubjectKind

internal enum class KnowledgeCoverageLevel {
    HISTORICAL_SAMPLE,
    PARTIAL,
    FULL,
}

/**
 * A reviewed declaration about what a content pack covers.
 *
 * Coverage is deliberately separate from pack size. A large historical or partial pack must not
 * become a "full high-school" pack merely because it contains many nodes.
 */
internal data class KnowledgePackCoverage(
    val baselineId: String,
    val catalogLevel: KnowledgeCoverageLevel,
    val teachingSupportLevel: KnowledgeCoverageLevel,
) {
    companion object {
        fun historical2020Sample() = KnowledgePackCoverage(
            baselineId = KnowledgeCoverageContract.HISTORICAL_2020_BASELINE_ID,
            catalogLevel = KnowledgeCoverageLevel.HISTORICAL_SAMPLE,
            teachingSupportLevel = KnowledgeCoverageLevel.HISTORICAL_SAMPLE,
        )
    }
}

internal data class KnowledgeCoverageAudit(
    val currentBaselinePackIds: Set<String>,
    val currentSubjects: Set<SubjectKind>,
    val currentFineGrainedPointCount: Int,
    val currentTeachingMaterialCount: Int,
    val catalogDeclaredFull: Boolean,
    val teachingSupportDeclaredFull: Boolean,
    val fullCoverageReady: Boolean,
)

internal object KnowledgeCoverageContract {
    const val CURRENT_BASELINE_ID = "moe-high-school-2017-2025"
    const val HISTORICAL_2020_BASELINE_ID = "moe-high-school-2017-2020"

    private val requiredSubjects = SubjectKind.entries
        .filterNotTo(linkedSetOf()) { it == SubjectKind.GENERAL }

    fun validate(
        coverage: KnowledgePackCoverage,
        nodes: List<KnowledgeNodeSeedRecord>,
        sources: List<KnowledgeSourceSeedRecord>,
        teachingMaterials: List<KnowledgeTeachingMaterialRecord>,
        teachingMaterialBindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
    ) {
        require(coverage.baselineId.isNotBlank() && coverage.baselineId == coverage.baselineId.trim()) {
            "Knowledge coverage baseline must be a trimmed non-blank id"
        }
        require(
            (coverage.catalogLevel == KnowledgeCoverageLevel.HISTORICAL_SAMPLE) ==
                (coverage.teachingSupportLevel == KnowledgeCoverageLevel.HISTORICAL_SAMPLE),
        ) {
            "Historical sample status must apply to both knowledge-catalog layers"
        }
        if (coverage.catalogLevel == KnowledgeCoverageLevel.HISTORICAL_SAMPLE) {
            require(coverage.baselineId == HISTORICAL_2020_BASELINE_ID) {
                "Historical sample packs must identify the 2020 compatibility baseline"
            }
            return
        }

        require(coverage.baselineId == CURRENT_BASELINE_ID) {
            "Current knowledge packs must target the 2025 daily-revision baseline"
        }
        if (coverage.catalogLevel == KnowledgeCoverageLevel.FULL) {
            val sourceSubjects = sources.mapNotNullTo(linkedSetOf()) { source ->
                enumValues<SubjectKind>().singleOrNull { it.name == source.subject }
            }
            val pointSubjects = nodes
                .asSequence()
                .filter { it.granularity == KnowledgeNodeGranularity.ATOMIC.name }
                .mapNotNullTo(linkedSetOf()) { node ->
                    enumValues<SubjectKind>().singleOrNull { it.name == node.subject }
                }
            require(sourceSubjects == requiredSubjects && pointSubjects == requiredSubjects) {
                "A full current knowledge catalog must contain reviewed content for all nine subjects"
            }
        }
        if (coverage.teachingSupportLevel == KnowledgeCoverageLevel.FULL) {
            require(coverage.catalogLevel == KnowledgeCoverageLevel.FULL) {
                "Full teaching support requires a full current knowledge catalog"
            }
            val atomicNodes = nodes.filter {
                it.granularity == KnowledgeNodeGranularity.ATOMIC.name
            }
            val teachingSubjects = teachingMaterials.mapTo(linkedSetOf()) { material ->
                enumValues<SubjectKind>().singleOrNull { it.name == material.subject }
            }
            require(teachingSubjects == requiredSubjects) {
                "Full teaching support needs reviewed teaching materials for all nine subjects"
            }
            val materialTypesBySubject = teachingMaterials.groupBy(
                KnowledgeTeachingMaterialRecord::subject,
            ).mapValues { (_, materials) ->
                materials.mapTo(hashSetOf(), KnowledgeTeachingMaterialRecord::materialType)
            }
            require(requiredSubjects.all { subject ->
                val types = materialTypesBySubject[subject.name].orEmpty()
                KnowledgeTeachingMaterialType.METHOD_MODEL.name in types &&
                    KnowledgeTeachingMaterialType.WORKED_EXAMPLE.name in types
            }) {
                "Full teaching support needs a method model and worked example in every subject"
            }
            val supportedNodeIds = teachingMaterialBindings.mapTo(hashSetOf()) {
                it.knowledgeNodeId
            }
            require(atomicNodes.all { it.knowledgeNodeId in supportedNodeIds }) {
                "Full teaching support must bind reviewed teaching material to every fine-grained point"
            }
        }
    }

    fun audit(packs: List<KnowledgeBasePack>): KnowledgeCoverageAudit {
        val current = packs.filter { it.coverage.baselineId == CURRENT_BASELINE_ID }
        val currentSubjects = current
            .flatMap(KnowledgeBasePack::sources)
            .mapNotNullTo(linkedSetOf()) { source ->
                enumValues<SubjectKind>().singleOrNull { it.name == source.subject }
            }
        val fineGrainedCount = current.sumOf { pack ->
            pack.nodes.count { it.granularity == KnowledgeNodeGranularity.ATOMIC.name }
        }
        val teachingCount = current.sumOf { it.teachingMaterials.size }
        val catalogFull = current.any {
            it.coverage.catalogLevel == KnowledgeCoverageLevel.FULL
        }
        val teachingFull = current.any {
            it.coverage.teachingSupportLevel == KnowledgeCoverageLevel.FULL
        }
        return KnowledgeCoverageAudit(
            currentBaselinePackIds = current.mapTo(linkedSetOf(), KnowledgeBasePack::packId),
            currentSubjects = currentSubjects,
            currentFineGrainedPointCount = fineGrainedCount,
            currentTeachingMaterialCount = teachingCount,
            catalogDeclaredFull = catalogFull,
            teachingSupportDeclaredFull = teachingFull,
            fullCoverageReady = current.isNotEmpty() &&
                currentSubjects == requiredSubjects &&
                catalogFull &&
                teachingFull,
        )
    }
}
