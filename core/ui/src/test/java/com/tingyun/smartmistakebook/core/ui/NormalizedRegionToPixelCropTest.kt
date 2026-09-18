package com.tingyun.smartmistakebook.core.ui

import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NormalizedRegionToPixelCropTest {
    @Test
    fun fullRegionKeepsTheWholeImage() {
        val crop = normalizedRegionToPixelCrop(region(0.0, 0.0, 1.0, 1.0), 200, 300)

        assertEquals(PixelCrop(left = 0, top = 0, width = 200, height = 300), crop)
    }

    @Test
    fun halfPageRegionCropsTheLeftHalf() {
        val crop = normalizedRegionToPixelCrop(region(0.0, 0.0, 0.5, 1.0), 200, 300)

        assertEquals(PixelCrop(left = 0, top = 0, width = 100, height = 300), crop)
    }

    @Test
    fun fractionsOutsideTheImageAreClampedToItsBounds() {
        val crop = normalizedRegionToPixelCrop(region(-0.4, -2.0, 1.3, 5.0), 200, 300)

        assertEquals(PixelCrop(left = 0, top = 0, width = 200, height = 300), crop)
    }

    @Test
    fun partialRegionRoundsOutwardsToCoverTheWholeArea() {
        // 0.13 * 300 = 39 and 0.47 * 300 = 141 exactly; ceil on the right edge keeps the
        // shared pixel column inside the crop.
        val crop = normalizedRegionToPixelCrop(region(0.13, 0.0, 0.47, 1.0), 300, 300)

        assertEquals(PixelCrop(left = 39, top = 0, width = 102, height = 300), crop)
    }

    @Test
    fun aSubPixelRegionStillProducesAtLeastOnePixel() {
        // Positive area, but both sides round inside one pixel: keep a 1x1 crop rather
        // than rendering an empty rectangle.
        val crop = normalizedRegionToPixelCrop(region(0.5, 0.5, 0.5005, 0.5005), 200, 300)

        assertEquals(PixelCrop(left = 100, top = 150, width = 1, height = 1), crop)
    }

    @Test
    fun anEmptyRegionFallsBackToTheWholeImage() {
        assertNull(normalizedRegionToPixelCrop(region(0.5, 0.5, 0.5, 0.5), 200, 300))
    }

    @Test
    fun invertedOrEmptyRegionsFallBackToTheWholeImage() {
        assertNull(normalizedRegionToPixelCrop(region(0.6, 0.0, 0.4, 1.0), 200, 300))
        assertNull(normalizedRegionToPixelCrop(region(0.0, 0.8, 1.0, 0.2), 200, 300))
    }

    @Test
    fun nonFiniteRegionsFallBackToTheWholeImage() {
        assertNull(normalizedRegionToPixelCrop(region(Double.NaN, 0.0, 1.0, 1.0), 200, 300))
        assertNull(normalizedRegionToPixelCrop(region(0.0, 0.0, Double.POSITIVE_INFINITY, 1.0), 200, 300))
    }

    @Test
    fun degenerateImageSizesFallBackToTheWholeImage() {
        assertNull(normalizedRegionToPixelCrop(region(0.0, 0.0, 1.0, 1.0), 0, 300))
        assertNull(normalizedRegionToPixelCrop(region(0.0, 0.0, 1.0, 1.0), 200, -1))
    }

    private fun region(left: Double, top: Double, right: Double, bottom: Double) =
        NormalizedSourceRegion(left = left, top = top, right = right, bottom = bottom)
}
