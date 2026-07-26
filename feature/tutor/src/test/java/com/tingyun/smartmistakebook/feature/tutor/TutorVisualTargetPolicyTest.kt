package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualStep
import com.tingyun.smartmistakebook.core.model.TutorSceneStep
import com.tingyun.smartmistakebook.core.model.TutorStepFlowScene
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorVisualTargetPolicyTest {
    @Test
    fun exactReadyGuidedHitCanSubmitCurrentEvidence() {
        assertTrue(
            canSubmitTutorVisualTarget(
                mode = TutorExplanationMode.GUIDED,
                pendingEvidenceRequestId = "request-1",
                requestId = "request-1",
                visualReady = true,
                expectedTargetId = "node-1",
                hitTargetId = "node-1",
                sceneReported = false,
            ),
        )
        assertTrue(
            isTutorVisualTargetReady(
                state = TutorVisualResolution.Hidden,
                inlineScene = documentScene(),
            ),
        )
        assertFalse(
            isTutorVisualTargetReady(
                state = TutorVisualResolution.Hidden,
                inlineScene = null,
            ),
        )
    }

    @Test
    fun planWrongVisibleHitSubmitsNegativeEvidenceWhileInvalidHitsFailClosed() {
        val valid = VisualTargetAttempt(
            mode = TutorExplanationMode.GUIDED,
            pendingEvidenceRequestId = "request-1",
            requestId = "request-1",
            visualReady = true,
            expectedTargetId = "node-1",
            hitTargetId = "node-1",
            sceneReported = false,
        )

        assertFalse(canSubmitTutorVisualTarget(valid.copy(requestId = "stale")))
        assertFalse(canSubmitTutorVisualTarget(valid.copy(visualReady = false)))
        assertFalse(canSubmitTutorVisualTarget(valid.copy(mode = TutorExplanationMode.DIRECT)))
        assertFalse(canSubmitTutorVisualTarget(valid.copy(sceneReported = true)))
        assertTrue(canSubmitTutorVisualTarget(valid.copy(hitTargetId = "node-2")))
        assertFalse(canSubmitTutorVisualTarget(valid.copy(hitTargetId = "")))
        assertFalse(canSubmitTutorVisualTarget(valid.copy(pendingEvidenceRequestId = null)))
    }

    @Test
    fun legacyV1SceneCanRenderButNeverBecomesVisualTargetEvidenceAuthority() {
        val legacyScene = TutorStepFlowScene(
            sceneId = "legacy-scene",
            title = "旧版步骤图",
            steps = listOf(
                TutorSceneStep("first", "第一步", "先读题。"),
                TutorSceneStep("second", "第二步", "再列式。"),
            ),
        )

        assertFalse(
            isTutorVisualTargetReady(
                state = TutorVisualResolution.Hidden,
                inlineScene = legacyScene,
            ),
        )
        assertFalse(
            isTutorVisualTargetReady(
                state = TutorVisualResolution.Ready(
                    scene = legacyScene,
                    cacheKey = "legacy",
                ),
                inlineScene = null,
            ),
        )
        assertNotEquals(
            inlineTutorVisualResolution(
                scene = legacyScene,
                ownerModelTaskRequestId = "plan-request-a",
            ).presentationStateKey("plan-request-a"),
            inlineTutorVisualResolution(
                scene = legacyScene,
                ownerModelTaskRequestId = "plan-request-b",
            ).presentationStateKey("plan-request-b"),
        )
    }

    private fun documentScene() = TutorVisualDocumentScene(
        sceneId = "scene",
        title = "方向关系",
        panels = listOf(TutorVisualPanel("panel", TutorVisualPanelKind.DIAGRAM_2D)),
        elements = listOf(
            TutorVisual2DNodeElement(
                elementId = "node-1",
                panelId = "panel",
                kind = TutorVisual2DNodeKind.RECTANGLE,
                label = "物体",
            ),
        ),
        steps = listOf(
            TutorVisualStep(
                stepId = "focus",
                label = "先看物体",
                focusElementIds = listOf("node-1"),
                primaryRelationElementId = "node-1",
            ),
        ),
        fallbackMarkdown = "先看物体。",
        accessibilitySummary = "一个物体。",
    )
}
