package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialDerivationKind
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialNodeRole
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceContentUsePolicy
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeTeachingMaterialContractTest {
    @Test
    fun `worked example is valid teaching content and not treated as a question bank`() {
        KnowledgeTeachingMaterialContract.validate(
            materials = listOf(material()),
            bindings = listOf(binding()),
            nodes = listOf(node()),
            sources = listOf(source()),
        )
    }

    @Test
    fun `reference-only source permits synthesis but rejects copied excerpt`() {
        val failure = runCatching {
            KnowledgeTeachingMaterialContract.validate(
                materials = listOf(
                    material(
                        derivationKind = KnowledgeMaterialDerivationKind.LICENSED_EXCERPT.name,
                    ),
                ),
                bindings = listOf(binding()),
                nodes = listOf(node()),
                sources = listOf(source()),
            )
        }.exceptionOrNull()

        assertTrue(failure is DatabaseContractViolationException)
        assertTrue(failure?.message.orEmpty().contains("content-use policy"))
    }

    @Test
    fun `licensed adaptation can retain a complete worked solution`() {
        val licensedSource = source().copy(
            licenseStatus = KnowledgeSourceLicenseStatus.LICENSED.name,
            contentUsePolicy = KnowledgeSourceContentUsePolicy.ADAPTATION_ALLOWED.name,
            licenseExpression = "CC-BY-NC-SA-4.0",
            licenseUri = "https://creativecommons.org/licenses/by-nc-sa/4.0/",
            attributionText = "示例教材，依 CC BY-NC-SA 4.0 改编。",
        )
        KnowledgeBaseImportContract.validateSourcesOnly(listOf(licensedSource))
        KnowledgeTeachingMaterialContract.validate(
            materials = listOf(
                material(
                    derivationKind =
                        KnowledgeMaterialDerivationKind.LICENSED_ADAPTATION.name,
                    contentMarkdown = """
                        例：已知函数 f(x)=x²-2x，求其在 [0,3] 上的单调区间。
                        解：f'(x)=2x-2。当 0≤x<1 时 f'(x)<0；当 1<x≤3 时 f'(x)>0。
                        所以函数在 [0,1] 上递减，在 [1,3] 上递增，最小值为 f(1)=-1。
                    """.trimIndent(),
                ),
            ),
            bindings = listOf(binding()),
            nodes = listOf(node()),
            sources = listOf(licensedSource),
        )
    }

    @Test
    fun `complete solution and derivation remain valid teaching material types`() {
        KnowledgeTeachingMaterialType.entries
            .filter {
                it == KnowledgeTeachingMaterialType.COMPLETE_SOLUTION ||
                    it == KnowledgeTeachingMaterialType.DERIVATION
            }
            .forEach { type ->
                KnowledgeTeachingMaterialContract.validate(
                    materials = listOf(material(materialType = type)),
                    bindings = listOf(binding()),
                    nodes = listOf(node()),
                    sources = listOf(source()),
                )
            }
    }

    @Test
    fun `teaching material requires exactly one primary fine grained point`() {
        val failure = runCatching {
            KnowledgeTeachingMaterialContract.validate(
                materials = listOf(material()),
                bindings = listOf(
                    binding(role = KnowledgeMaterialNodeRole.SUPPORTING.name),
                ),
                nodes = listOf(node()),
                sources = listOf(source()),
            )
        }.exceptionOrNull()

        assertTrue(failure is DatabaseContractViolationException)
        assertTrue(failure?.message.orEmpty().contains("exactly one primary"))
    }

    @Test
    fun `model candidate cannot receive reviewed teaching material`() {
        val failure = runCatching {
            KnowledgeTeachingMaterialContract.validate(
                materials = listOf(material()),
                bindings = listOf(binding()),
                nodes = listOf(
                    node(
                        verificationStatus = KnowledgeNodeVerificationStatus.MODEL_CANDIDATE.name,
                    ),
                ),
                sources = listOf(source()),
            )
        }.exceptionOrNull()

        assertTrue(failure is DatabaseContractViolationException)
        assertTrue(failure?.message.orEmpty().contains("reviewed fine-grained"))
    }

    private fun source() = KnowledgeSourceSeedRecord(
        sourceId = "source:math:reference",
        subject = "MATH",
        sourceType = KnowledgeSourceType.AUTHORIZED_EDUCATION_MATERIAL.name,
        title = "教学参考资料",
        publisher = "示例出版社",
        edition = "2026",
        sourceUri = "https://example.org/reference",
        licenseStatus = KnowledgeSourceLicenseStatus.REFERENCE_ONLY.name,
        contentFingerprint = "A".repeat(64),
        importedAtEpochMillis = 1,
    )

    private fun node(
        verificationStatus: String = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
    ) = KnowledgeNodeSeedRecord(
        knowledgeNodeId = "knowledge:math:monotonicity",
        stableCode = "math:function:monotonicity",
        subject = "MATH",
        displayName = "判断函数单调性",
        parentKnowledgeNodeId = "knowledge:math:function",
        taxonomyVersion = "test-v1",
        createdAtEpochMillis = 1,
        canonicalName = "判断函数单调性",
        nodeKind = KnowledgeNodeKind.REASONING.name,
        granularity = KnowledgeNodeGranularity.ATOMIC.name,
        boundaryMarkdown = "根据定义、图象或导数判断给定区间内的单调性。",
        verificationStatus = verificationStatus,
    )

    private fun material(
        derivationKind: String = KnowledgeMaterialDerivationKind.REVIEWED_SYNTHESIS.name,
        materialType: KnowledgeTeachingMaterialType =
            KnowledgeTeachingMaterialType.WORKED_EXAMPLE,
        contentMarkdown: String =
            "例：给定函数后，求导、解不等式，并按区间写出结论与理由。",
    ): KnowledgeTeachingMaterialRecord {
        val draft = KnowledgeTeachingMaterialRecord(
            materialId = "material:math:monotonicity-method",
            stableCode = "math:function:monotonicity:method-model",
            subject = "MATH",
            materialType = materialType.name,
            title = "用导数判断单调性的完整示例",
            summaryMarkdown = "先确定定义域，再判断导数符号。",
            applicabilityMarkdown = "适用于可求导函数在给定区间上的单调性判断。",
            contentMarkdown = contentMarkdown,
            boundaryMarkdown = "不能省略定义域，也不能把导数为零的孤立点直接当作单调区间。",
            derivationKind = derivationKind,
            sourceId = "source:math:reference",
            sourceLocator = "函数与导数·单调性方法",
            contentFingerprint = "PENDING",
            reviewedAtEpochMillis = 2,
        )
        return draft.copy(
            contentFingerprint = KnowledgeTeachingMaterialFingerprint.expected(draft),
        )
    }

    private fun binding(
        role: String = KnowledgeMaterialNodeRole.PRIMARY.name,
    ) = KnowledgeTeachingMaterialNodeBindingRecord(
        materialId = "material:math:monotonicity-method",
        knowledgeNodeId = "knowledge:math:monotonicity",
        role = role,
    )
}
