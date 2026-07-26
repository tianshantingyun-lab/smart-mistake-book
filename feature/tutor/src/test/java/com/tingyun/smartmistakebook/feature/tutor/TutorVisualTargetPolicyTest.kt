package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import org.junit.Assert.assertFalse
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
                hasInlineScene = true,
            ),
        )
        assertFalse(
            isTutorVisualTargetReady(
                state = TutorVisualResolution.Hidden,
                hasInlineScene = false,
            ),
        )
    }

    @Test
    fun staleMissingDirectReportedAndBlankHitsFailClosedWhileOtherVisibleHitsSubmit() {
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
}
