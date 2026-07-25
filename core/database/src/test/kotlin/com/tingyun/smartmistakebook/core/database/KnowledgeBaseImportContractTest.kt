package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceContentUsePolicy
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeBaseImportContractTest {
    @Test
    fun acceptsReviewedSourceGroundedSameSubjectOntology() {
        val pack = validPack()

        KnowledgeBaseImportContract.validate(
            pack.sources,
            pack.nodes,
            pack.bindings,
        )
    }

    @Test
    fun acceptsIncrementalAtomicNodeUsingReviewedExistingParentAndSource() {
        val pack = validPack()
        val atomic = pack.nodes.last().copy(
            knowledgeNodeId = "kb:curriculum-v1:math:atomic:range",
            stableCode = "curriculum-v1:math:atomic:range",
            displayName = "求函数值域",
            canonicalName = "求函数值域",
            aliases = emptySet(),
            boundaryMarkdown = "只求给定函数在指定定义域上的值域。",
        )

        KnowledgeBaseImportContract.validate(
            sources = emptyList(),
            nodes = listOf(atomic),
            bindings = listOf(
                pack.bindings.last().copy(knowledgeNodeId = atomic.knowledgeNodeId),
            ),
            existingSources = pack.sources,
            existingParentNodes = listOf(pack.nodes.first()),
        )
    }

    @Test
    fun rejectsModelCandidateEvenWhenItHasAProvenanceBinding() {
        val pack = validPack()
        expectViolation("Model-candidate") {
            KnowledgeBaseImportContract.validate(
                pack.sources,
                pack.nodes.map { node ->
                    if (node.granularity == KnowledgeNodeGranularity.ATOMIC.name) {
                        node.copy(
                            verificationStatus = KnowledgeNodeVerificationStatus.MODEL_CANDIDATE.name,
                        )
                    } else {
                        node
                    }
                },
                pack.bindings,
            )
        }
    }

    @Test
    fun rejectsUnreviewedProvenance() {
        val pack = validPack()
        expectViolation("review timestamp") {
            KnowledgeBaseImportContract.validate(
                pack.sources,
                pack.nodes,
                pack.bindings.map { it.copy(reviewedAtEpochMillis = null) },
            )
        }
    }

    @Test
    fun rejectsCrossSubjectProvenance() {
        val pack = validPack()
        expectViolation("inside one subject") {
            KnowledgeBaseImportContract.validate(
                pack.sources.map { it.copy(subject = "PHYSICS") },
                pack.nodes,
                pack.bindings,
            )
        }
    }

    @Test
    fun rejectsNodeWithoutReviewedProvenance() {
        val pack = validPack()
        expectViolation("Every imported knowledge node") {
            KnowledgeBaseImportContract.validate(
                pack.sources,
                pack.nodes,
                pack.bindings.filterNot { it.knowledgeNodeId == ATOMIC_ID },
            )
        }
    }

    @Test
    fun rejectsUntraceablePublicSourceUri() {
        val pack = validPack()
        expectViolation("absolute HTTPS URI") {
            KnowledgeBaseImportContract.validate(
                pack.sources.map { it.copy(sourceUri = "http://example.edu/curriculum.pdf") },
                pack.nodes,
                pack.bindings,
            )
        }
    }

    @Test
    fun rejectsAtomicKnowledgeOutsideItsParentsTaxonomy() {
        val pack = validPack()
        expectViolation("same-subject topic parent") {
            KnowledgeBaseImportContract.validate(
                pack.sources,
                pack.nodes.map { node ->
                    if (node.knowledgeNodeId == ATOMIC_ID) {
                        node.copy(taxonomyVersion = "curriculum-v2")
                    } else {
                        node
                    }
                },
                pack.bindings,
            )
        }
    }

    @Test
    fun rejectsDirectReuseWithoutSpecificLicenseEvidenceAndAttribution() {
        val pack = validPack()
        expectViolation("licenseExpression") {
            KnowledgeBaseImportContract.validate(
                pack.sources.map {
                    it.copy(
                        contentUsePolicy =
                            KnowledgeSourceContentUsePolicy.EXCERPT_ALLOWED.name,
                    )
                },
                pack.nodes,
                pack.bindings,
            )
        }
    }

    @Test
    fun rejectsDirectReuseFromReferenceOnlyMaterial() {
        val pack = validPack()
        expectViolation("reviewed synthesis only") {
            KnowledgeBaseImportContract.validate(
                pack.sources.map {
                    it.copy(
                        licenseStatus = KnowledgeSourceLicenseStatus.REFERENCE_ONLY.name,
                        contentUsePolicy =
                            KnowledgeSourceContentUsePolicy.ADAPTATION_ALLOWED.name,
                        licenseExpression = "LicenseRef-Example",
                        licenseUri = "https://example.edu/license",
                        attributionText = "示例资料，示例出版社。",
                    )
                },
                pack.nodes,
                pack.bindings,
            )
        }
    }

    @Test
    fun acceptsLicensedAdaptationWithAuditableReuseTerms() {
        val pack = validPack()
        KnowledgeBaseImportContract.validate(
            pack.sources.map {
                it.copy(
                    licenseStatus = KnowledgeSourceLicenseStatus.LICENSED.name,
                    contentUsePolicy =
                        KnowledgeSourceContentUsePolicy.ADAPTATION_ALLOWED.name,
                    licenseExpression = "CC-BY-NC-SA-4.0",
                    licenseUri = "https://creativecommons.org/licenses/by-nc-sa/4.0/",
                    attributionText = "示例课程资料，依 CC BY-NC-SA 4.0 改编。",
                )
            },
            pack.nodes,
            pack.bindings,
        )
    }

    private fun expectViolation(messagePart: String, block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue(thrown is DatabaseContractViolationException)
        assertTrue(thrown?.message.orEmpty().contains(messagePart))
    }

    private fun validPack(): KnowledgeBaseTestPack {
        val source = KnowledgeSourceSeedRecord(
            sourceId = SOURCE_ID,
            subject = "MATH",
            sourceType = KnowledgeSourceType.OFFICIAL_CURRICULUM_STANDARD.name,
            title = "普通高中数学课程标准",
            publisher = "教育部",
            edition = "2020年修订",
            sourceUri = "https://example.edu/curriculum.pdf",
            licenseStatus = KnowledgeSourceLicenseStatus.PUBLIC_OFFICIAL.name,
            contentFingerprint = "A".repeat(64),
            importedAtEpochMillis = 1_000,
        )
        val topic = KnowledgeNodeSeedRecord(
            knowledgeNodeId = TOPIC_ID,
            stableCode = "curriculum-v1:math:topic:function",
            subject = "MATH",
            displayName = "函数",
            parentKnowledgeNodeId = null,
            taxonomyVersion = "curriculum-v1",
            createdAtEpochMillis = 1_000,
            canonicalName = "函数",
            nodeKind = KnowledgeNodeKind.TOPIC.name,
            granularity = KnowledgeNodeGranularity.TOPIC.name,
            verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
        )
        val atomic = KnowledgeNodeSeedRecord(
            knowledgeNodeId = ATOMIC_ID,
            stableCode = "curriculum-v1:math:atomic:monotonicity",
            subject = "MATH",
            displayName = "判断函数单调性",
            parentKnowledgeNodeId = TOPIC_ID,
            taxonomyVersion = "curriculum-v1",
            createdAtEpochMillis = 1_000,
            canonicalName = "判断函数单调性",
            nodeKind = KnowledgeNodeKind.REASONING.name,
            granularity = KnowledgeNodeGranularity.ATOMIC.name,
            aliases = setOf("判断单调性"),
            boundaryMarkdown = "只判断给定函数在指定区间上的单调性。",
            verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
        )
        val bindings = listOf(topic, atomic).map { node ->
            KnowledgeNodeSourceBindingSeedRecord(
                knowledgeNodeId = node.knowledgeNodeId,
                sourceId = SOURCE_ID,
                sourceLocator = "课程内容·函数",
                derivationNote = "经人工审校后拆分为可复用知识节点。",
                reviewedAtEpochMillis = 1_000,
            )
        }
        return KnowledgeBaseTestPack(listOf(source), listOf(topic, atomic), bindings)
    }

    private data class KnowledgeBaseTestPack(
        val sources: List<KnowledgeSourceSeedRecord>,
        val nodes: List<KnowledgeNodeSeedRecord>,
        val bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
    )

    private companion object {
        const val SOURCE_ID = "source:curriculum:math"
        const val TOPIC_ID = "kb:curriculum-v1:math:topic:function"
        const val ATOMIC_ID = "kb:curriculum-v1:math:atomic:monotonicity"
    }
}
