package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualStep
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorVisualModelTaskPolicyTest {
    @Test
    fun generationRequestCarriesExactSourceScopeUnderAgentConsent() {
        val request = buildTutorVisualGenerateRequest(
            question = question,
            provider = provider,
            sourceAssets = assets,
            anchor = anchor,
            focusMarkdown = "聚焦液面高度关系",
            explanationMarkdown = "先比较两侧液面。",
            occurredAtEpochMillis = 1_000,
        )
        val input = request.input as TutorVisualGenerateInput

        assertEquals(ModelTaskKind.TUTOR_VISUAL_GENERATE, input.kind)
        assertEquals(assets.map { it.toSourceRef() }, input.sourceAssets)
        assertTrue(request.agentConsentGranted)
        assertTrue(request.egressManifest == null)
    }

    @Test
    fun generationRequestIdentityIgnoresRetryTimingButBindsSourceAndModelVersion() {
        val first = buildTutorVisualGenerateRequest(
            question = question,
            provider = provider,
            sourceAssets = assets,
            anchor = anchor,
            focusMarkdown = "聚焦液面高度关系",
            explanationMarkdown = "先比较两侧液面。",
            occurredAtEpochMillis = 1_000,
        )
        val later = buildTutorVisualGenerateRequest(
            question = question,
            provider = provider,
            sourceAssets = assets,
            anchor = anchor,
            focusMarkdown = "聚焦液面高度关系",
            explanationMarkdown = "先比较两侧液面。",
            occurredAtEpochMillis = 2_000,
        )
        val changedSource = buildTutorVisualGenerateRequest(
            question = question,
            provider = provider,
            sourceAssets = assets.map { it.copy(sha256 = "b".repeat(64)) },
            anchor = anchor,
            focusMarkdown = "聚焦液面高度关系",
            explanationMarkdown = "先比较两侧液面。",
            occurredAtEpochMillis = 2_000,
        )

        assertEquals(first.requestId, later.requestId)
        assertTrue(first.requestId != changedSource.requestId)
    }

    @Test
    fun reviewAddsOnlyTheCandidateAndKeepsTheExactGenerationImageScope() {
        val generationRequest = buildTutorVisualGenerateRequest(
            question = question,
            provider = provider,
            sourceAssets = assets,
            anchor = anchor,
            focusMarkdown = "聚焦液面高度关系",
            explanationMarkdown = "先比较两侧液面。",
            occurredAtEpochMillis = 1_000,
        )
        val generated = TutorVisualGenerateOutput(
            sessionId = question.sessionId,
            draftRevisionNumber = question.revisionNumber,
            questionDocumentId = question.questionDocument.document.id,
            anchor = anchor,
            decision = TutorVisualGenerationDecision.GENERATED,
            confidence = 0.82,
            scene = scene,
            modelVersion = "model-v1",
        )
        val reviewRequest = buildTutorVisualReviewRequest(
            question = question,
            provider = provider,
            sourceAssets = assets,
            generationRequest = generationRequest,
            generated = generated,
            reviewReasonCodes = setOf("low_generation_confidence"),
            occurredAtEpochMillis = 1_100,
        )
        val input = reviewRequest.input as TutorVisualReviewInput

        assertEquals(scene, input.candidateScene)
        assertEquals(assets.map { it.toSourceRef() }, input.sourceAssets)
        val generationInput = generationRequest.input as TutorVisualGenerateInput
        assertEquals(generationInput.sourceAssets, input.sourceAssets)
        assertTrue(reviewRequest.agentConsentGranted)
        assertTrue(reviewRequest.egressManifest == null)
    }

    @Test
    fun visualAgentGateRequiresImageCapabilityForExternalProviders() {
        val imageCapable = provider
        val structuredOnly = provider.copy(
            supportsImageInput = false,
        )
        val local = provider.copy(
            executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
        )

        assertTrue(
            tutorAgentChatEnabled(
                provider = imageCapable,
                kind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = imageCapable,
                kind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
            ),
        )
        assertFalse(
            tutorAgentChatEnabled(
                provider = structuredOnly,
                kind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
            ),
        )
        assertFalse(
            tutorAgentChatEnabled(
                provider = structuredOnly,
                kind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = structuredOnly,
                kind = ModelTaskKind.TUTOR_PLAN,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = structuredOnly,
                kind = ModelTaskKind.TUTOR_RESPOND,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = local,
                kind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
            ),
        )
    }

    private companion object {
        val anchor = TutorVisualTurnAnchor(
            surface = TutorVisualTurnSurface.PLAN,
            cycleOrdinal = 1,
            turnOrdinal = 1,
        )
        val question = TutorQuestionContext(
            sessionId = "session",
            revisionNumber = 1,
            subject = "PHYSICS",
            title = "液柱题",
            questionDocument = CapturedQuestionDocument(
                document = QuestionDocument(
                    id = "question",
                    blocks = listOf(ContentBlock.Paragraph("stem", "比较两侧液面")),
                ),
                blockEvidence = emptyList(),
            ),
        )
        val provider = ProviderCapabilitySnapshot(
            providerId = "provider",
            providerDisplayName = "模型服务",
            modelId = "model",
            providerConfigurationVersion = "config-v1",
            executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
            supportedTasks = setOf(
                ModelTaskKind.TUTOR_PLAN,
                ModelTaskKind.TUTOR_RESPOND,
                ModelTaskKind.TUTOR_VISUAL_GENERATE,
                ModelTaskKind.TUTOR_VISUAL_REVIEW,
            ),
            supportsImageInput = true,
            supportsStructuredOutput = true,
            supportsStreaming = false,
        )
        val assets = listOf(
            TutorVisualSourceAssetScope(
                pageIndex = 0,
                assetId = "asset",
                sha256 = "a".repeat(64),
                byteSize = 128,
                width = 100,
                height = 100,
            ),
        )
        val scene = TutorVisualDocumentScene(
            sceneId = "scene",
            title = "液面关系",
            panels = listOf(TutorVisualPanel("panel", TutorVisualPanelKind.DIAGRAM_2D)),
            elements = listOf(
                TutorVisual2DNodeElement(
                    elementId = "level",
                    panelId = "panel",
                    kind = TutorVisual2DNodeKind.LIQUID_LEVEL,
                    label = "液面",
                ),
            ),
            steps = listOf(
                TutorVisualStep(
                    stepId = "focus",
                    label = "比较液面",
                    focusElementIds = listOf("level"),
                    primaryRelationElementId = "level",
                ),
            ),
            fallbackMarkdown = "比较两侧液面高度。",
            accessibilitySummary = "一条表示液面的水平线。",
        )
    }
}
