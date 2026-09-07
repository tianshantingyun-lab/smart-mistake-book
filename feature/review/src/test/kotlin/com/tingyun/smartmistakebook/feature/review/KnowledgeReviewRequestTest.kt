package com.tingyun.smartmistakebook.feature.review

import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewQueueEntry
import com.tingyun.smartmistakebook.core.model.KnowledgeQuizInput
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeReviewRequestTest {

    private val entry = KnowledgeReviewQueueEntry(
        knowledgeNodeId = "kc-monotonicity",
        subject = "MATH",
        displayName = "函数单调性",
        masteryScore = 0.4,
        lastEvidenceAtEpochMillis = 5_000L,
        score = 4.0,
        reasonNames = setOf("STALE_KNOWLEDGE"),
    )

    private val reference = TutorTeachingReference(
        materialId = "material:monotonicity",
        subject = "MATH",
        materialType = com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION,
        title = "函数单调性讲解",
        summaryMarkdown = "概要",
        applicabilityMarkdown = "适用",
        contentMarkdown = "单调性判定正文",
        boundaryMarkdown = "只覆盖单调性判定，不涉及极值",
        knowledgeNodeIds = listOf("kc-monotonicity"),
    )

    private fun provider(
        location: ModelExecutionLocation,
        supportsQuiz: Boolean = true,
    ) = ProviderCapabilitySnapshot(
        providerId = "provider:test",
        providerDisplayName = "测试模型",
        modelId = "model:test",
        supportedTasks = if (supportsQuiz) {
            setOf(ModelTaskKind.KNOWLEDGE_QUIZ)
        } else {
            emptySet()
        },
        supportsImageInput = false,
        supportsStructuredOutput = true,
        supportsStreaming = true,
        executionLocation = location,
        providerConfigurationVersion = "cfg-v1",
    )

    @Test
    fun externalProviderCarriesAnExactLeastDisclosureManifest() {
        val request = buildKnowledgeQuizRequest(
            provider = provider(ModelExecutionLocation.EXTERNAL_PROVIDER),
            requestId = "knowledge-quiz:kc-monotonicity:1",
            entry = entry,
            reference = reference,
            occurredAtEpochMillis = 9_000,
        )

        val manifest = requireNotNull(request?.egressManifest)
        assertEquals(ModelEgressPurpose.TUTORING, manifest.purpose)
        assertEquals(setOf(ModelTaskKind.KNOWLEDGE_QUIZ), manifest.authorizedTaskKinds)
        assertEquals("MATH", manifest.subjectId)
        assertEquals(ModelEgressManifest.KNOWLEDGE_QUIZ_DISCLOSURE, manifest.disclosedData)
        assertEquals(
            ModelEgressManifest.KNOWLEDGE_QUIZ_PROHIBITED_DATA,
            manifest.prohibitedData,
        )
        assertEquals(ModelPromptPolicyVersions.KNOWLEDGE_QUIZ, manifest.promptPolicyVersion)
        assertEquals(9_000, manifest.approvedAtEpochMillis)
    }

    @Test
    fun inputCarriesTheNodeMaterialAndLastMastery() {
        val request = buildKnowledgeQuizRequest(
            provider = provider(ModelExecutionLocation.EXTERNAL_PROVIDER),
            requestId = "knowledge-quiz:kc-monotonicity:1",
            entry = entry,
            reference = reference,
            occurredAtEpochMillis = 9_000,
        )
        val input = request?.input as KnowledgeQuizInput
        assertEquals("kc-monotonicity", input.knowledgeNodeId)
        assertEquals("MATH", input.subjectId)
        assertEquals("函数单调性讲解", input.materialTitle)
        assertEquals("单调性判定正文", input.materialContentMarkdown)
        assertEquals("只覆盖单调性判定，不涉及极值", input.materialBoundaryMarkdown)
        assertEquals(0.4, input.lastMasteryScore ?: -1.0, 0.0)
        assertEquals(5_000L, input.lastEvidenceAtEpochMillis)
    }

    @Test
    fun localNoEgressProviderCarriesNoManifest() {
        val request = buildKnowledgeQuizRequest(
            provider = provider(ModelExecutionLocation.LOCAL_NO_EGRESS),
            requestId = "knowledge-quiz:kc-monotonicity:1",
            entry = entry,
            reference = reference,
            occurredAtEpochMillis = 9_000,
        )
        assertNull(request?.egressManifest)
    }

    @Test
    fun providerWithoutQuizSupportReturnsNull() {
        assertNull(
            buildKnowledgeQuizRequest(
                provider = provider(ModelExecutionLocation.EXTERNAL_PROVIDER, supportsQuiz = false),
                requestId = "knowledge-quiz:kc-monotonicity:1",
                entry = entry,
                reference = reference,
                occurredAtEpochMillis = 9_000,
            ),
        )
    }

    @Test
    fun mismatchedSubjectIsRejected() {
        val wrongSubject = reference.copy(subject = "PHYSICS")
        try {
            buildKnowledgeQuizRequest(
                provider = provider(ModelExecutionLocation.EXTERNAL_PROVIDER),
                requestId = "knowledge-quiz:kc-monotonicity:1",
                entry = entry,
                reference = wrongSubject,
                occurredAtEpochMillis = 9_000,
            )
            throw AssertionError("Expected an IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
