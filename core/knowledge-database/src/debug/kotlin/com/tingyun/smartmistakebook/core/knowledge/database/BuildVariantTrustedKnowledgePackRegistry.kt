package com.tingyun.smartmistakebook.core.knowledge.database

import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceContentUsePolicy
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.SubjectKind

/**
 * Fixed debug-only registry entry for install and integrity boundary tests.
 *
 * It has one structural topic per subject solely to satisfy catalog integrity rules. It is not a
 * complete knowledge pack, is never production-cutover eligible, and is absent from release
 * artifacts.
 */
object DebugBoundaryKnowledgePackFixture {
    const val PACK_ID = "debug.boundary-fixture"
    const val KNOWLEDGE_PACK_VERSION = "debug-boundary-v1"
    const val TAXONOMY_VERSION = "debug-taxonomy-v1"
    const val SEARCH_INDEX_VERSION = "local-search-index-v1"
    const val GENERATION = 9_001_001L

    fun reviewedPack(): ReviewedKnowledgePack {
        val subjects = enumValues<SubjectKind>().filter { it != SubjectKind.GENERAL }
        return ReviewedKnowledgePack(
            metadata =
                ReviewedKnowledgePackMetadata(
                    packId = PACK_ID,
                    knowledgePackVersion = KNOWLEDGE_PACK_VERSION,
                    taxonomyVersion = TAXONOMY_VERSION,
                    searchIndexVersion = SEARCH_INDEX_VERSION,
                    builtAtEpochMillis = 1_800_000_000_000L,
                ),
            nodes =
                subjects.flatMap { subject ->
                    val topic =
                        ReviewedKnowledgeNode(
                            knowledgeNodeId = "debug.${subject.name.lowercase()}.topic",
                            stableCode = "debug.${subject.name.lowercase()}.root",
                            subject = subject,
                            displayName = subject.debugDisplayName(),
                            canonicalName = subject.debugDisplayName(),
                            kind = KnowledgeNodeKind.TOPIC,
                            granularity = KnowledgeNodeGranularity.TOPIC,
                            boundaryMarkdown = "仅用于调试生产读取边界。",
                            verificationStatus = KnowledgeNodeVerificationStatus.CURATED,
                            parentKnowledgeNodeId = null,
                            reviewedAtEpochMillis = 1_800_000_000_000L,
                        )
                    val related =
                        if (subject == SubjectKind.MATH) {
                            listOf(
                                ReviewedKnowledgeNode(
                                    knowledgeNodeId = "debug.math.related",
                                    stableCode = "debug.math.related-root",
                                    subject = SubjectKind.MATH,
                                    displayName = "一元二次方程",
                                    canonicalName = "一元二次方程",
                                    kind = KnowledgeNodeKind.CONCEPT,
                                    granularity = KnowledgeNodeGranularity.ATOMIC,
                                    aliases = listOf("一元二次方程根"),
                                    boundaryMarkdown = "仅用于调试相关知识邻域。",
                                    verificationStatus = KnowledgeNodeVerificationStatus.CURATED,
                                    parentKnowledgeNodeId = "debug.math.topic",
                                    reviewedAtEpochMillis = 1_800_000_000_000L,
                                ),
                            )
                        } else {
                            emptyList()
                        }
                    listOf(topic) + related
                },
            sources =
                subjects.map { subject ->
                    ReviewedKnowledgeSource(
                        sourceId = "debug.${subject.name.lowercase()}.source",
                        subject = subject,
                        sourceType = KnowledgeSourceType.MANUAL_RESEARCH,
                        title = "${subject.debugDisplayName()}调试来源",
                        publisher = null,
                        edition = null,
                        sourceUri = null,
                        licenseStatus = KnowledgeSourceLicenseStatus.REFERENCE_ONLY,
                        contentUsePolicy =
                            KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY,
                        contentFingerprint =
                            (subject.ordinal + 1).toString(16).padStart(64, '0'),
                        licenseExpression = null,
                        licenseUri = null,
                        attributionText = null,
                        reviewedAtEpochMillis = 1_800_000_000_000L,
                    )
                },
            nodeSourceBindings =
                subjects.flatMap { subject ->
                    val topic =
                        ReviewedKnowledgeNodeSourceBinding(
                            knowledgeNodeId = "debug.${subject.name.lowercase()}.topic",
                            sourceId = "debug.${subject.name.lowercase()}.source",
                            sourceLocator = "debug/${subject.name.lowercase()}",
                            derivationNote = "调试边界固定夹具。",
                            reviewedAtEpochMillis = 1_800_000_000_000L,
                        )
                    val related =
                        if (subject == SubjectKind.MATH) {
                            listOf(
                                ReviewedKnowledgeNodeSourceBinding(
                                    knowledgeNodeId = "debug.math.related",
                                    sourceId = "debug.math.source",
                                    sourceLocator = "debug/math/related",
                                    derivationNote = "调试相关知识邻域固定夹具。",
                                    reviewedAtEpochMillis = 1_800_000_000_000L,
                                ),
                            )
                        } else {
                            emptyList()
                        }
                    listOf(topic) + related
                },
            relations =
                listOf(
                    ReviewedKnowledgeRelation(
                        relationId = "debug.math.topic-to-related",
                        subject = SubjectKind.MATH,
                        fromKnowledgeNodeId = "debug.math.topic",
                        toKnowledgeNodeId = "debug.math.related",
                        relationType = "PREREQUISITE",
                        sourceId = "debug.math.source",
                        sourceLocator = "debug/math/related",
                        reviewedAtEpochMillis = 1_800_000_000_000L,
                    ),
                ),
            teachingMaterials = emptyList(),
            teachingMaterialBindings = emptyList(),
        )
    }
}

internal object BuildVariantTrustedKnowledgePackRegistry {
    val formalSigningKeys: List<FormalKnowledgePackSigningKey> = emptyList()

    val entries: List<BuildVariantTrustedKnowledgePackDefinition> =
        listOf(
            BuildVariantTrustedKnowledgePackDefinition(
                pack = DebugBoundaryKnowledgePackFixture.reviewedPack(),
                generation = DebugBoundaryKnowledgePackFixture.GENERATION,
                productionCutoverEligible = false,
                purpose = BuiltInKnowledgePackPurpose.DEBUG_BOUNDARY_FIXTURE,
                formalActivationProof = null,
            ),
        )
}

private fun SubjectKind.debugDisplayName(): String =
    when (this) {
        SubjectKind.CHINESE -> "语文"
        SubjectKind.MATH -> "数学"
        SubjectKind.ENGLISH -> "英语"
        SubjectKind.PHYSICS -> "物理"
        SubjectKind.CHEMISTRY -> "化学"
        SubjectKind.BIOLOGY -> "生物"
        SubjectKind.POLITICS -> "思想政治"
        SubjectKind.HISTORY -> "历史"
        SubjectKind.GEOGRAPHY -> "地理"
        SubjectKind.GENERAL -> error("Debug knowledge fixture requires a specific subject")
    }
