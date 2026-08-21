package com.tingyun.smartmistakebook.core.visual.ui

import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorVisual2DRendererRegistryTest {

    @Test
    fun `registry covers all TutorVisual2DNodeKind entries`() {
        val registry = TutorVisual2DRendererRegistry(defaultNodeRenderers)
        assertEquals(
            TutorVisual2DNodeKind.entries.toSet(),
            registry.supportedKinds,
        )
    }

    @Test
    fun `each renderer declares exactly one supported kind`() {
        val kinds = defaultNodeRenderers.map(TutorVisual2DNodeRenderer::supportedKind)
        assertEquals(kinds.size, kinds.toSet().size, "Each renderer must cover a unique kind")
    }

    @Test
    fun `default renderers count matches enum entry count`() {
        assertEquals(
            TutorVisual2DNodeKind.entries.size,
            defaultNodeRenderers.size,
            "Number of default renderers must equal number of enum entries",
        )
    }

    @Test
    fun `registry rejects duplicate renderers for the same kind`() {
        val duplicate = listOf(
            PointNodeRenderer(),
            PointNodeRenderer(),
        )
        try {
            TutorVisual2DRendererRegistry(duplicate)
            assertTrue("Expected IllegalArgumentException for duplicate renderers", false)
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun `registry rejects incomplete renderer set`() {
        val incomplete = defaultNodeRenderers.drop(1)
        try {
            TutorVisual2DRendererRegistry(incomplete)
            assertTrue("Expected IllegalArgumentException for missing renderer", false)
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }
}
