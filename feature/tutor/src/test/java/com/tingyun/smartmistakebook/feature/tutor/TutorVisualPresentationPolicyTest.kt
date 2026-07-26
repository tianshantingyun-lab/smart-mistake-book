package com.tingyun.smartmistakebook.feature.tutor

import org.junit.Assert.assertEquals
import org.junit.Test

class TutorVisualPresentationPolicyTest {
    @Test
    fun currentVisualIsExpandedAndHistoryVisualIsCollapsed() {
        assertEquals(
            TutorVisualPresentationMode.CURRENT_EXPANDED,
            tutorVisualPresentationMode(isCurrent = true),
        )
        assertEquals(
            TutorVisualPresentationMode.HISTORY_COLLAPSED,
            tutorVisualPresentationMode(isCurrent = false),
        )
        assertEquals(
            TutorVisualPresentationMode.CURRENT_EXPANDED,
            tutorPlanVisualPresentationMode(isCurrentTurn = true),
        )
    }

    @Test
    fun fallbackExposesOnlyOneSafeAction() {
        val retryable = TutorVisualResolution.Fallback(
            reason = TutorVisualFallbackReason.TASK_FAILURE,
            canRetry = true,
        )
        val rejected = TutorVisualResolution.Fallback(
            reason = TutorVisualFallbackReason.REJECTED,
        )

        assertEquals(
            TutorVisualFallbackAction.RETRY,
            retryable.primaryAction(originalAvailable = true),
        )
        assertEquals(
            TutorVisualFallbackAction.ORIGINAL,
            rejected.primaryAction(originalAvailable = true),
        )
        assertEquals(null, rejected.primaryAction(originalAvailable = false))
    }
}
