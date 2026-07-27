package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureSurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorSolutionExposureStabilityTest {
    private val viewport = Rect(0f, 0f, 100f, 100f)
    private val bottom = Rect(0f, 90f, 1f, 91f)

    @Test
    fun continuousVisibilityReachesTheStabilityWindow() {
        val stability = TutorSolutionExposureStability(stabilityWindowNanos = 100)

        assertEquals(WAITING, stability.observe(0, viewport, bottom))
        assertEquals(WAITING, stability.observe(40, viewport, bottom))
        assertEquals(WAITING, stability.observe(80, viewport, bottom))
        assertEquals(STABLE, stability.observe(100, viewport, bottom))
    }

    @Test
    fun visibleBoundsMovementDoesNotRestartTheStabilityWindow() {
        val stability = TutorSolutionExposureStability(stabilityWindowNanos = 100)
        val movedBottom = Rect(0f, 89f, 1f, 90f)

        assertEquals(WAITING, stability.observe(0, viewport, bottom))
        assertEquals(WAITING, stability.observe(50, viewport, bottom))
        assertEquals(WAITING, stability.observe(75, viewport, movedBottom))
        assertEquals(STABLE, stability.observe(100, viewport, movedBottom))
    }

    @Test
    fun replacingTheAnchorRestartsTheStabilityWindowEvenWhenBoundsMatch() {
        val stability = TutorSolutionExposureStability(stabilityWindowNanos = 100)
        val oldAnchor = Any()
        val newAnchor = Any()

        assertEquals(WAITING, stability.observe(0, viewport, bottom, oldAnchor))
        assertEquals(WAITING, stability.observe(60, viewport, bottom, oldAnchor))
        assertEquals(WAITING, stability.observe(80, viewport, bottom, newAnchor))
        assertEquals(WAITING, stability.observe(179, viewport, bottom, newAnchor))
        assertEquals(STABLE, stability.observe(180, viewport, bottom, newAnchor))
    }

    @Test
    fun invisibleBottomStopsTheObservation() {
        val stability = TutorSolutionExposureStability(stabilityWindowNanos = 100)
        val belowViewport = Rect(0f, 110f, 1f, 111f)

        assertEquals(WAITING, stability.observe(0, viewport, bottom))
        assertEquals(NOT_VISIBLE, stability.observe(50, viewport, belowViewport))
        assertEquals(WAITING, stability.observe(75, viewport, bottom))
        assertEquals(WAITING, stability.observe(174, viewport, bottom))
        assertEquals(STABLE, stability.observe(175, viewport, bottom))
    }

    @Test
    fun longFrameGapRestartsTheStabilityWindow() {
        val stability = TutorSolutionExposureStability(stabilityWindowNanos = 100)

        assertEquals(WAITING, stability.observe(0, viewport, bottom))
        assertEquals(WAITING, stability.observe(50, viewport, bottom))
        assertEquals(WAITING, stability.observe(150, viewport, bottom))
        assertEquals(WAITING, stability.observe(249, viewport, bottom))
        assertEquals(STABLE, stability.observe(250, viewport, bottom))
    }

    private companion object {
        val NOT_VISIBLE = TutorSolutionExposureStabilityStatus.NOT_VISIBLE
        val WAITING = TutorSolutionExposureStabilityStatus.WAITING
        val STABLE = TutorSolutionExposureStabilityStatus.STABLE
    }
}

class TutorSolutionExposureStartTest {
    private val exposureKey = TutorAnswerExposureKey(
        sessionId = "session-1",
        questionDocumentId = "document-1",
        revisionNumber = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        surfaceKind = TutorAnswerExposureSurfaceKind.PLAN_SOLUTION,
        modelTaskRequestId = "request-1",
    )

    @Test
    fun completedAndInFlightKeysCannotStartAnotherWrite() {
        val inFlightKeys = mutableSetOf<TutorAnswerExposureKey>()

        assertTrue(
            tryStartTutorSolutionExposure(
                exposureKey = exposureKey,
                completedKeys = emptySet(),
                inFlightKeys = inFlightKeys,
            ),
        )
        assertFalse(
            tryStartTutorSolutionExposure(
                exposureKey = exposureKey.copy(),
                completedKeys = emptySet(),
                inFlightKeys = inFlightKeys,
            ),
        )
        assertFalse(
            tryStartTutorSolutionExposure(
                exposureKey = exposureKey,
                completedKeys = setOf(exposureKey),
                inFlightKeys = mutableSetOf(),
            ),
        )
    }

    @Test
    fun transientPreviewDoesNotBlockTheSameKeyFromBeingDurablyRecorded() {
        val recordedKeysState = mutableStateOf(emptySet<TutorAnswerExposureKey>())
        val tracker = TutorSolutionExposureTracker(
            viewportBounds = mutableStateOf<Rect?>(null),
            solutionBottomAnchors = mutableStateMapOf(),
            recordedAnswerExposureKeysState = recordedKeysState,
            transientAnswerExposureKeysState = mutableStateOf(emptySet()),
            targets = emptyList(),
        )
        val inFlightKeys = mutableSetOf<TutorAnswerExposureKey>()

        assertTrue(tracker.markTransientAnswerExposure(exposureKey))
        assertTrue(tracker.answerExposureKeys.isEmpty())
        assertEquals(setOf(exposureKey), tracker.presentationAnswerExposureKeys)
        assertTrue(
            tryStartTutorSolutionExposure(
                exposureKey = exposureKey,
                completedKeys = recordedKeysState.value,
                inFlightKeys = inFlightKeys,
            ),
        )

        recordedKeysState.value = setOf(exposureKey)
        assertFalse(
            tryStartTutorSolutionExposure(
                exposureKey = exposureKey,
                completedKeys = recordedKeysState.value,
                inFlightKeys = mutableSetOf(),
            ),
        )
    }

    @Test
    fun disposedAnchorCannotRemoveANewerAnchorWithTheSameStableId() {
        val anchors = mutableStateMapOf<String, TutorSolutionBottomAnchor>()
        val tracker = TutorSolutionExposureTracker(
            viewportBounds = mutableStateOf<Rect?>(null),
            solutionBottomAnchors = anchors,
            recordedAnswerExposureKeysState = mutableStateOf(emptySet()),
            transientAnswerExposureKeysState = mutableStateOf(emptySet()),
            targets = emptyList(),
        )
        val stableId = "reply-1"
        val oldToken = Any()
        val newToken = Any()
        val bottomBounds = Rect(0f, 90f, 1f, 91f)

        tracker.updateSolutionBottomBounds(stableId, oldToken, bottomBounds)
        tracker.updateSolutionBottomBounds(stableId, newToken, bottomBounds)
        tracker.removeSolutionBottomBounds(stableId, oldToken)

        assertEquals(newToken, anchors[stableId]?.token)
        tracker.removeSolutionBottomBounds(stableId, newToken)
        assertEquals(null, anchors[stableId])
    }
}
