package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
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
import org.junit.Assert.assertEquals
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

        assertTrue(resolved is TutorVisualResolution.NeedsReview)
        assertTrue((resolved as TutorVisualResolution.NeedsReview).reasonCodes.contains("lattice"))
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

        assertTrue(resolved is TutorVisualResolution.NeedsReview)
        assertTrue(
            (resolved as TutorVisualResolution.NeedsReview)
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

        assertEquals(TutorVisualResolution.Unavailable, resolved)
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
