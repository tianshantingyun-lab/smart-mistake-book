package com.tingyun.smartmistakebook.core.data.knowledge.provisioning

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSourceSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialNodeBindingRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialRecord
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeCoverageContractTest {
    @Test
    fun fullTeachingSupportRejectsAFineGrainedPointWithoutTeachingMaterial() {
        val fixture = fullCoverageFixture()

        val failure = runCatching {
            KnowledgeCoverageContract.validate(
                coverage = fixture.coverage,
                nodes = fixture.nodes,
                sources = fixture.sources,
                teachingMaterials = fixture.materials,
                teachingMaterialBindings = fixture.bindings.dropLast(1),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("every fine-grained point"))
    }

    @Test
    fun fullTeachingSupportRejectsASubjectWithoutWorkedExample() {
        val fixture = fullCoverageFixture()
        val materials = fixture.materials.filterNot { material ->
            material.subject == SubjectKind.CHINESE.name &&
                material.materialType == KnowledgeTeachingMaterialType.WORKED_EXAMPLE.name
        }

        val failure = runCatching {
            KnowledgeCoverageContract.validate(
                coverage = fixture.coverage,
                nodes = fixture.nodes,
                sources = fixture.sources,
                teachingMaterials = materials,
                teachingMaterialBindings = fixture.bindings,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("worked example"))
    }

    @Test
    fun fullTeachingSupportAcceptsNineSubjectMethodExampleAndPointCoverage() {
        val fixture = fullCoverageFixture()

        KnowledgeCoverageContract.validate(
            coverage = fixture.coverage,
            nodes = fixture.nodes,
            sources = fixture.sources,
            teachingMaterials = fixture.materials,
            teachingMaterialBindings = fixture.bindings,
        )
    }

    private fun fullCoverageFixture(): FullCoverageFixture {
        val subjects = SubjectKind.entries.filterNot { it == SubjectKind.GENERAL }
        val sources = subjects.map { subject ->
            KnowledgeSourceSeedRecord(
                sourceId = "source:${subject.name.lowercase()}",
                subject = subject.name,
                sourceType = "OFFICIAL_CURRICULUM_STANDARD",
                title = "${subject.name} source",
                publisher = "publisher",
                edition = "2025",
                sourceUri = "https://example.org/${subject.name.lowercase()}",
                licenseStatus = "PUBLIC_OFFICIAL",
                contentFingerprint = subjectFingerprint(subject),
                importedAtEpochMillis = 1,
            )
        }
        val nodes = subjects.flatMap { subject ->
            val subjectKey = subject.name.lowercase()
            listOf(
                KnowledgeNodeSeedRecord(
                    knowledgeNodeId = "kb:$subjectKey:topic",
                    stableCode = "stable:$subjectKey:topic",
                    subject = subject.name,
                    displayName = "$subjectKey topic",
                    parentKnowledgeNodeId = null,
                    taxonomyVersion = "test-v1",
                    createdAtEpochMillis = 1,
                    canonicalName = "$subjectKey topic",
                    nodeKind = "TOPIC",
                    granularity = KnowledgeNodeGranularity.TOPIC.name,
                    verificationStatus = "CURATED",
                ),
                KnowledgeNodeSeedRecord(
                    knowledgeNodeId = "kb:$subjectKey:point",
                    stableCode = "stable:$subjectKey:point",
                    subject = subject.name,
                    displayName = "$subjectKey point",
                    parentKnowledgeNodeId = "kb:$subjectKey:topic",
                    taxonomyVersion = "test-v1",
                    createdAtEpochMillis = 1,
                    canonicalName = "$subjectKey point",
                    nodeKind = "CONCEPT",
                    granularity = KnowledgeNodeGranularity.ATOMIC.name,
                    verificationStatus = "SOURCE_GROUNDED",
                ),
            )
        }
        val materials = subjects.flatMap { subject ->
            listOf(
                material(subject, KnowledgeTeachingMaterialType.METHOD_MODEL),
                material(subject, KnowledgeTeachingMaterialType.WORKED_EXAMPLE),
            )
        }
        val bindings = subjects.map { subject ->
            val subjectKey = subject.name.lowercase()
            KnowledgeTeachingMaterialNodeBindingRecord(
                materialId = "material:$subjectKey:method_model",
                knowledgeNodeId = "kb:$subjectKey:point",
                role = "PRIMARY",
            )
        }
        return FullCoverageFixture(
            coverage = KnowledgePackCoverage(
                baselineId = KnowledgeCoverageContract.CURRENT_BASELINE_ID,
                catalogLevel = KnowledgeCoverageLevel.FULL,
                teachingSupportLevel = KnowledgeCoverageLevel.FULL,
            ),
            sources = sources,
            nodes = nodes,
            materials = materials,
            bindings = bindings,
        )
    }

    private fun material(
        subject: SubjectKind,
        type: KnowledgeTeachingMaterialType,
    ): KnowledgeTeachingMaterialRecord {
        val subjectKey = subject.name.lowercase()
        val typeKey = type.name.lowercase()
        return KnowledgeTeachingMaterialRecord(
            materialId = "material:$subjectKey:$typeKey",
            stableCode = "stable:$subjectKey:$typeKey",
            subject = subject.name,
            materialType = type.name,
            title = "$subjectKey $typeKey",
            summaryMarkdown = "summary",
            applicabilityMarkdown = "applicability",
            contentMarkdown = "content",
            boundaryMarkdown = "boundary",
            derivationKind = "REVIEWED_SYNTHESIS",
            sourceId = "source:$subjectKey",
            sourceLocator = "locator",
            contentFingerprint = "A".repeat(64),
            reviewedAtEpochMillis = 1,
        )
    }

    private fun subjectFingerprint(subject: SubjectKind): String =
        subject.ordinal.toString(16).uppercase().padStart(64, '0')

    private data class FullCoverageFixture(
        val coverage: KnowledgePackCoverage,
        val sources: List<KnowledgeSourceSeedRecord>,
        val nodes: List<KnowledgeNodeSeedRecord>,
        val materials: List<KnowledgeTeachingMaterialRecord>,
        val bindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
    )
}
