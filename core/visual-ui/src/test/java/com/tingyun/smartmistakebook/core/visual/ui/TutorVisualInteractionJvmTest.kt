package com.tingyun.smartmistakebook.core.visual.ui

import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.TutorVisualChartPoint
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualStep
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualDocumentCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the pure logic behind the PR-11 interaction loop:
 * frame-bound chart data visibility, fallback 3D picking, and default
 * constraint derivation.
 */
class TutorVisualInteractionJvmTest {

    private val points = listOf(
        TutorVisualChartPoint(0.0, 1.0),
        TutorVisualChartPoint(1.0, 2.0),
        TutorVisualChartPoint(2.0, 3.0),
        TutorVisualChartPoint(3.0, 4.0),
    )

    @Test
    fun completedFrameShowsEveryPoint() {
        assertEquals(points, visiblePointsForFrame(points, 1.0))
        assertEquals(points, visiblePointsForFrame(points, 2.5))
        assertEquals(emptyList<TutorVisualChartPoint>(), visiblePointsForFrame(emptyList(), 0.4))
    }

    @Test
    fun earlyFrameShowsOnlyTheLeadingPoint() {
        assertEquals(points.take(1), visiblePointsForFrame(points, 0.0))
        assertEquals(points.take(1), visiblePointsForFrame(points, -0.5))
    }

    @Test
    fun midFrameTruncatesPointsUpToTheCurrentProgress() {
        // cutoff = 0 + 3 * 0.5 = 1.5 -> x in {0,1}
        assertEquals(points.take(2), visiblePointsForFrame(points, 0.5))
        // cutoff = 0 + 3 * 0.75 = 2.25 -> x in {0,1,2}
        assertEquals(points.take(3), visiblePointsForFrame(points, 0.75))
    }

    @Test
    fun degenerateXRangeKeepsAllPointsAndSparseStartKeepsOne() {
        val flat = listOf(TutorVisualChartPoint(1.0, 1.0), TutorVisualChartPoint(1.0, 2.0))
        assertEquals(flat, visiblePointsForFrame(flat, 0.25))
        val sparse = listOf(TutorVisualChartPoint(0.0, 1.0), TutorVisualChartPoint(10.0, 2.0))
        assertEquals(sparse.take(1), visiblePointsForFrame(sparse, 0.05))
    }

    @Test
    fun pickingSelectsTheNearestElementInsideTheThreshold() {
        val distances = mapOf("a" to 400.0, "b" to 90.0, "c" to 40_000.0)
        assertEquals("b", resolvePickHit(distances, thresholdSquared = 100.0))
    }

    @Test
    fun pickingRejectsEveryElementOutsideTheThreshold() {
        val distances = mapOf("a" to 400.0, "b" to 900.0)
        assertNull(resolvePickHit(distances, thresholdSquared = 100.0))
        assertNull(resolvePickHit(emptyMap(), thresholdSquared = 100.0))
    }

    @Test
    fun defaultConstraintsExposeEvery2DNodeAsDraggable() {
        val scene = TutorVisualDocumentScene(
            sceneId = "scene-1",
            title = "关系图",
            panels = listOf(TutorVisualPanel("panel", TutorVisualPanelKind.DIAGRAM_2D)),
            elements = listOf(
                TutorVisual2DNodeElement(
                    elementId = "object-a",
                    panelId = "panel",
                    kind = TutorVisual2DNodeKind.RECTANGLE,
                    label = "A",
                ),
                TutorVisual2DNodeElement(
                    elementId = "object-b",
                    panelId = "panel",
                    kind = TutorVisual2DNodeKind.RECTANGLE,
                    label = "B",
                ),
            ),
            steps = listOf(
                TutorVisualStep(
                    stepId = "focus",
                    label = "先看对象",
                    focusElementIds = listOf("object-a"),
                ),
            ),
            fallbackMarkdown = "先观察对象。",
            accessibilitySummary = "两个矩形。",
        )
        val compiled = TutorVisualDocumentCompiler.compile(scene)
        val constraints = deriveDefaultVisualConstraints(compiled)
        assertEquals("scene-1", constraints.problemRevisionId)
        assertEquals(setOf("object-a", "object-b"), constraints.draggableElementIds)
        assertEquals(emptyMap<String, ClosedFloatingPointRange<Double>>(), constraints.dragTargetX)
    }
}
