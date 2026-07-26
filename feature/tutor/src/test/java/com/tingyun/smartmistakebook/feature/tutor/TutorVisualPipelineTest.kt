package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskLogicalOperationFingerprint
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.TutorVisual2DConnectorElement
import com.tingyun.smartmistakebook.core.model.TutorVisualConnectionAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualConnectorKind
import com.tingyun.smartmistakebook.core.model.TutorVisualCamera
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualLatticeBasisSite
import com.tingyun.smartmistakebook.core.model.TutorVisualLatticeElement
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualStep
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import com.tingyun.smartmistakebook.core.model.TutorVisualVector3
import com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorVisualPipelineTest {
    @Test
    fun lowRiskLocallyValidSceneCanRenderWithoutASecondModelCall() {
        val scene = lowRiskScene()
        val generation = generationTask(scene = scene, confidence = 0.97)

        val resolved = resolveTutorVisual(
            anchor = anchor,
            question = question,
            generationTasks = listOf(generation),
            reviewTasks = emptyList(),
        )

        assertTrue(resolved is TutorVisualResolution.Ready)
        assertEquals(scene, (resolved as TutorVisualResolution.Ready).scene)
        assertEquals(64, resolved.cacheKey.length)
    }

    @Test
    fun latticeSceneRequiresExactlyOneIndependentReview() {
        val generation = generationTask(scene = latticeScene(), confidence = 0.99)

        val resolved = resolveTutorVisual(
            anchor = anchor,
            question = question,
            generationTasks = listOf(generation),
            reviewTasks = emptyList(),
        )

        assertTrue(resolved is TutorVisualResolution.Reviewing)
        assertTrue((resolved as TutorVisualResolution.Reviewing).reasonCodes.contains("lattice"))
    }

    @Test
    fun reviewRequiredSceneFallsBackWhenProviderCannotReview() {
        val generation = generationTask(scene = latticeScene(), confidence = 0.99)
        val generateOnlyProvider = ProviderCapabilitySnapshot(
            providerId = "generate-only",
            providerDisplayName = "Generate only",
            modelId = "model-v1",
            supportedTasks = setOf(ModelTaskKind.TUTOR_VISUAL_GENERATE),
            supportsImageInput = true,
            supportsStructuredOutput = true,
            supportsStreaming = false,
        )

        val resolved = resolveTutorVisual(
            anchor = anchor,
            question = question,
            generationTasks = listOf(generation),
            reviewTasks = emptyList(),
            reviewProvider = generateOnlyProvider,
        )

        assertTrue(resolved is TutorVisualResolution.Fallback)
        assertEquals(
            TutorVisualFallbackReason.PROVIDER_UNAVAILABLE,
            (resolved as TutorVisualResolution.Fallback).reason,
        )
    }

    @Test
    fun localIntegrityErrorCannotBypassTheReviewGate() {
        val generation = generationTask(scene = invalidDirectionScene(), confidence = 0.99)

        val resolved = resolveTutorVisual(
            anchor = anchor,
            question = question,
            generationTasks = listOf(generation),
            reviewTasks = emptyList(),
        )

        assertTrue(resolved is TutorVisualResolution.Reviewing)
        assertTrue(
            (resolved as TutorVisualResolution.Reviewing)
                .reasonCodes
                .contains("local_integrity_error"),
        )
    }

    @Test
    fun approvedHighRiskCandidateUsesTheOriginalScene() {
        val scene = latticeScene()
        val generation = generationTask(scene = scene, confidence = 0.99)
        val review = reviewTask(
            candidate = scene,
            decision = TutorVisualReviewDecision.APPROVED,
            repairedScene = null,
            confidence = 0.96,
        )

        val resolved = resolveTutorVisual(
            anchor = anchor,
            question = question,
            generationTasks = listOf(generation),
            reviewTasks = listOf(review),
        )

        assertTrue(resolved is TutorVisualResolution.Ready)
        assertEquals(scene, (resolved as TutorVisualResolution.Ready).scene)
    }

    @Test
    fun sameSceneIdWithDifferentContentRequiresANewExactReview() {
        val original = latticeScene()
        val changed = original.copy(title = "另一份晶胞关系")
        val originalGeneration = generationTask(scene = original, confidence = 0.99)
        val changedGeneration = generationTask(scene = changed, confidence = 0.99)
        val provider = ProviderCapabilitySnapshot(
            providerId = "review-provider",
            providerDisplayName = "Review provider",
            modelId = "review-model",
            supportedTasks = setOf(ModelTaskKind.TUTOR_VISUAL_REVIEW),
            supportsImageInput = true,
            supportsStructuredOutput = true,
            supportsStreaming = false,
            providerConfigurationVersion = "review-config-v1",
        )
        val originalOutput = originalGeneration.output as TutorVisualGenerateOutput
        val changedOutput = changedGeneration.output as TutorVisualGenerateOutput
        val reasons = setOf("lattice")
        val originalRequestId = tutorVisualReviewRequestId(
            generationRequestId = originalGeneration.request.requestId,
            provider = provider,
            generated = originalOutput,
            reviewReasonCodes = reasons,
        )
        val changedRequestId = tutorVisualReviewRequestId(
            generationRequestId = changedGeneration.request.requestId,
            provider = provider,
            generated = changedOutput,
            reviewReasonCodes = reasons,
        )
        val oldReview = reviewTask(
            candidate = original,
            decision = TutorVisualReviewDecision.APPROVED,
            repairedScene = null,
            confidence = 0.96,
        )
        val forgedRequest = oldReview.request.copy(requestId = changedRequestId)
        val oldReviewWithCurrentId = oldReview.copy(
            taskId = changedRequestId,
            request = forgedRequest,
            requestFingerprint = ModelTaskFingerprint.of(forgedRequest),
        )

        val resolved = resolveTutorVisual(
            anchor = anchor,
            question = question,
            generationTasks = listOf(changedGeneration),
            reviewTasks = listOf(oldReviewWithCurrentId),
            expectedReviewRequestId = changedRequestId,
            reviewProvider = provider,
        )

        assertNotEquals(originalRequestId, changedRequestId)
        assertTrue(resolved is TutorVisualResolution.Reviewing)
    }

    @Test
    fun rejectedHighRiskCandidateNeverRenders() {
        val scene = latticeScene()
        val generation = generationTask(scene = scene, confidence = 0.99)
        val review = reviewTask(
            candidate = scene,
            decision = TutorVisualReviewDecision.REJECTED,
            repairedScene = null,
            confidence = 0.99,
        )

        val resolved = resolveTutorVisual(
            anchor = anchor,
            question = question,
            generationTasks = listOf(generation),
            reviewTasks = listOf(review),
        )

        assertTrue(resolved is TutorVisualResolution.Fallback)
        assertEquals(
            TutorVisualFallbackReason.REJECTED,
            (resolved as TutorVisualResolution.Fallback).reason,
        )
        assertEquals(false, resolved.canRetry)
    }

    @Test
    fun missingGenerationTaskStaysVisibleAsPreparing() {
        val resolved = resolveTutorVisual(
            anchor = anchor,
            question = question,
            generationTasks = emptyList(),
            reviewTasks = emptyList(),
        )

        assertEquals(TutorVisualResolution.Preparing, resolved)
    }

    @Test
    fun providerLoadFailureCannotRemainPreparing() {
        assertEquals(
            TutorVisualResolution.Preparing,
            tutorVisualProviderLoadingResolution(provider = null, providerLoadFailed = false),
        )
        val failed = tutorVisualProviderLoadingResolution(
            provider = null,
            providerLoadFailed = true,
        ) as TutorVisualResolution.Fallback
        assertEquals(TutorVisualFallbackReason.PROVIDER_UNAVAILABLE, failed.reason)
    }

    @Test
    fun persistedGenerationFailureBecomesRetryableFallback() {
        val failed = failedGenerationTask()

        val resolved = resolveTutorVisual(
            anchor = anchor,
            question = question,
            generationTasks = listOf(failed),
            reviewTasks = emptyList(),
        )

        assertTrue(resolved is TutorVisualResolution.Fallback)
        assertEquals(
            TutorVisualFallbackReason.TASK_FAILURE,
            (resolved as TutorVisualResolution.Fallback).reason,
        )
        assertTrue(resolved.canRetry)
        assertEquals(failed, resolved.failedTask)
    }

    @Test
    fun retryBuildsAFreshEnvelopeForTheSameLogicalOperation() {
        val provider = ProviderCapabilitySnapshot(
            providerId = "external-provider",
            providerDisplayName = "External provider",
            modelId = "model-v1",
            supportedTasks = setOf(ModelTaskKind.TUTOR_VISUAL_GENERATE),
            supportsImageInput = true,
            supportsStructuredOutput = true,
            supportsStreaming = false,
            providerConfigurationVersion = "config-v1",
        )
        val fresh = buildTutorVisualGenerateRequest(
            question = question,
            provider = provider,
            sourceAssets = listOf(
                TutorVisualSourceAssetScope(
                    pageIndex = 0,
                    assetId = "asset-1",
                    sha256 = "a".repeat(64),
                    byteSize = 100,
                    width = 100,
                    height = 100,
                ),
            ),
            anchor = anchor,
            focusMarkdown = "聚焦装置中的方向关系",
            explanationMarkdown = "先核对装置连接，再判断方向。",
            occurredAtEpochMillis = 100,
            approvedAtEpochMillis = 100,
        )
        val failed = failedGenerationTask().let { task ->
            val failedRequest = task.request.copy(
                requestId = "${fresh.requestId}:retry:4",
            )
            task.copy(
                request = failedRequest,
                requestFingerprint = ModelTaskFingerprint.of(failedRequest),
            )
        }

        val retry = requireNotNull(freshTutorVisualRetryRequest(fresh, failed))

        assertEquals("${fresh.requestId}:retry:5", retry.requestId)
        assertEquals(100L, retry.occurredAtEpochMillis)
        assertEquals("authorization:${retry.requestId}", retry.egressManifest?.authorizationId)
        assertEquals(100L, retry.egressManifest?.approvedAtEpochMillis)
        assertNotEquals(failed.request, retry)
        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(failed.request),
            ModelTaskLogicalOperationFingerprint.of(retry),
        )
    }

    @Test
    fun retryRejectsExhaustedOrDifferentLogicalOperation() {
        val failed = failedGenerationTask()
        val fresh = failed.request.copy(
            requestId = "visual-generate-current",
            occurredAtEpochMillis = 100,
        )

        assertNull(
            freshTutorVisualRetryRequest(
                freshRequest = fresh,
                failedTask = failed.copy(attemptCount = 3),
            ),
        )
        assertNull(
            freshTutorVisualRetryRequest(
                freshRequest = fresh.copy(
                    input = generateInput().copy(focusMarkdown = "不同的图解目标"),
                ),
                failedTask = failed,
            ),
        )
    }

    @Test
    fun staleSemanticGenerationRequestDoesNotBecomeReady() {
        val stale = generationTask(scene = lowRiskScene(), confidence = 0.97)

        val resolved = resolveTutorVisual(
            anchor = anchor,
            question = question,
            generationTasks = listOf(stale),
            reviewTasks = emptyList(),
            expectedGenerationRequestId = "visual-generate-current-provider-and-source",
        )

        assertEquals(TutorVisualResolution.Preparing, resolved)
    }

    private fun generationTask(
        scene: TutorVisualDocumentScene,
        confidence: Double,
    ): ModelTaskSnapshot {
        val input = generateInput()
        val request = ModelTaskRequest(
            requestId = "visual-generate",
            input = input,
            occurredAtEpochMillis = 1,
        )
        return succeededTask(
            request = request,
            output = TutorVisualGenerateOutput(
                sessionId = input.sessionId,
                draftRevisionNumber = input.draftRevisionNumber,
                questionDocumentId = input.questionDocument.id,
                anchor = input.anchor,
                decision = TutorVisualGenerationDecision.GENERATED,
                confidence = confidence,
                scene = scene,
                modelVersion = "model-v1",
            ),
        )
    }

    private fun reviewTask(
        candidate: TutorVisualDocumentScene,
        decision: TutorVisualReviewDecision,
        repairedScene: TutorVisualDocumentScene?,
        confidence: Double,
    ): ModelTaskSnapshot {
        val generatedInput = generateInput()
        val input = TutorVisualReviewInput(
            sessionId = generatedInput.sessionId,
            draftRevisionNumber = generatedInput.draftRevisionNumber,
            subject = generatedInput.subject,
            questionDocument = generatedInput.questionDocument,
            sourceAssets = generatedInput.sourceAssets,
            anchor = generatedInput.anchor,
            focusMarkdown = generatedInput.focusMarkdown,
            explanationMarkdown = generatedInput.explanationMarkdown,
            candidateScene = candidate,
            reviewReasonCodes = setOf("lattice"),
        )
        val request = ModelTaskRequest(
            requestId = "visual-review-${decision.name.lowercase()}",
            input = input,
            occurredAtEpochMillis = 2,
        )
        return succeededTask(
            request = request,
            output = TutorVisualReviewOutput(
                sessionId = input.sessionId,
                draftRevisionNumber = input.draftRevisionNumber,
                questionDocumentId = input.questionDocument.id,
                anchor = input.anchor,
                decision = decision,
                confidence = confidence,
                scene = repairedScene,
                modelVersion = "review-v1",
            ),
        )
    }

    private fun failedGenerationTask(): ModelTaskSnapshot {
        val input = generateInput()
        val request = ModelTaskRequest(
            requestId = "visual-generate",
            input = input,
            occurredAtEpochMillis = 1,
        )
        return ModelTaskSnapshot(
            taskId = request.requestId,
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = ModelTaskStatus.RETRYABLE_FAILURE,
            stateVersion = 1,
            stage = ModelTaskStage.PREPARING,
            userMessage = "稍后重试",
            attemptCount = 1,
            failure = ModelTaskFailure(
                code = ModelFailureCode.NETWORK_UNAVAILABLE,
                message = "暂时无法完成",
                retryable = true,
            ),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )
    }

    private fun generateInput() = TutorVisualGenerateInput(
        sessionId = question.sessionId,
        draftRevisionNumber = question.revisionNumber,
        subject = question.subject,
        questionDocument = question.questionDocument.document,
        sourceAssets = listOf(
            CaptureSourceAssetRef(
                assetId = "asset-1",
                sha256 = "a".repeat(64),
                width = 100,
                height = 100,
                pageIndex = 0,
            ),
        ),
        anchor = anchor,
        focusMarkdown = "聚焦装置中的方向关系",
        explanationMarkdown = "先核对装置连接，再判断方向。",
    )

    private fun succeededTask(
        request: ModelTaskRequest,
        output: com.tingyun.smartmistakebook.core.model.ModelTaskOutput,
    ) = ModelTaskSnapshot(
        taskId = request.requestId,
        request = request,
        requestFingerprint = ModelTaskFingerprint.of(request),
        status = ModelTaskStatus.SUCCEEDED,
        stateVersion = 1,
        stage = ModelTaskStage.COMPLETE,
        userMessage = "完成",
        attemptCount = 1,
        output = output,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun lowRiskScene() = TutorVisualDocumentScene(
        sceneId = "low-risk",
        title = "方向关系",
        panels = listOf(
            TutorVisualPanel("panel", TutorVisualPanelKind.DIAGRAM_2D),
        ),
        elements = listOf(
            TutorVisual2DNodeElement(
                elementId = "object",
                panelId = "panel",
                kind = TutorVisual2DNodeKind.RECTANGLE,
                label = "物体",
            ),
        ),
        steps = listOf(
            TutorVisualStep(
                stepId = "focus",
                label = "先看物体",
                focusElementIds = listOf("object"),
                primaryRelationElementId = "object",
            ),
        ),
        fallbackMarkdown = "先看物体的方向关系。",
        accessibilitySummary = "一个标有物体的矩形。",
    )

    private fun latticeScene() = TutorVisualDocumentScene(
        sceneId = "lattice",
        title = "晶胞关系",
        panels = listOf(
            TutorVisualPanel(
                panelId = "panel",
                kind = TutorVisualPanelKind.SCENE_3D,
                camera = TutorVisualCamera(),
            ),
        ),
        elements = listOf(
            TutorVisualLatticeElement(
                elementId = "lattice-node",
                panelId = "panel",
                latticeVectors = listOf(
                    TutorVisualVector3(1.0, 0.0, 0.0),
                    TutorVisualVector3(0.0, 1.0, 0.0),
                    TutorVisualVector3(0.0, 0.0, 1.0),
                ),
                basis = listOf(
                    TutorVisualLatticeBasisSite(
                        fractionalCoordinate = TutorVisualVector3.ZERO,
                        label = "原子",
                    ),
                ),
            ),
        ),
        steps = listOf(
            TutorVisualStep(
                stepId = "focus",
                label = "先看晶胞",
                focusElementIds = listOf("lattice-node"),
                primaryRelationElementId = "lattice-node",
            ),
        ),
        fallbackMarkdown = "按分数坐标核对晶胞位置。",
        accessibilitySummary = "一个可旋转的晶胞结构。",
    )

    private fun invalidDirectionScene() = TutorVisualDocumentScene(
        sceneId = "invalid-direction",
        title = "方向关系",
        panels = listOf(
            TutorVisualPanel("panel", TutorVisualPanelKind.DIAGRAM_2D),
        ),
        elements = listOf(
            TutorVisual2DNodeElement(
                elementId = "source",
                panelId = "panel",
                kind = TutorVisual2DNodeKind.RECTANGLE,
                label = "起点",
            ),
            TutorVisual2DNodeElement(
                elementId = "target",
                panelId = "panel",
                kind = TutorVisual2DNodeKind.RECTANGLE,
                label = "终点",
            ),
            TutorVisual2DConnectorElement(
                elementId = "flow",
                panelId = "panel",
                kind = TutorVisualConnectorKind.FLOW,
                from = TutorVisualConnectionAnchor("source"),
                to = TutorVisualConnectionAnchor("target"),
                directed = false,
            ),
        ),
        steps = listOf(
            TutorVisualStep(
                stepId = "focus",
                label = "核对方向",
                focusElementIds = listOf("flow"),
                primaryRelationElementId = "flow",
            ),
        ),
        fallbackMarkdown = "根据题面核对方向。",
        accessibilitySummary = "起点和终点之间的方向关系。",
    )

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
            title = "题目",
            questionDocument = CapturedQuestionDocument(
                document = QuestionDocument(
                    id = "question",
                    blocks = listOf(ContentBlock.Paragraph("stem", "判断方向")),
                ),
                blockEvidence = listOf(
                    QuestionBlockEvidence(
                        blockId = "stem",
                        sourceAssetId = "asset-1",
                        sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                        writingLayer = WritingLayer.PRINTED,
                        provenance = QuestionBlockProvenance.USER_TRANSCRIPTION,
                        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    ),
                ),
            ),
        )
    }
}
