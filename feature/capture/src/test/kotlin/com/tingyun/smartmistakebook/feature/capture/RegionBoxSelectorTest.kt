package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.ui.geometry.Offset
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动框选的几何契约：归一化换算、最小尺寸阈值与两两重叠约束
 * 必须与 SplitCaptureDraftRequest 的校验保持一致，否则框完提交会被拒绝。
 */
class RegionBoxSelectorTest {

    @Test
    fun dragRectConvertsToNormalizedRegion() {
        val region = normalizedRegionFromRect(
            start = Offset(100f, 200f),
            end = Offset(300f, 500f),
            canvasWidth = 1000f,
            canvasHeight = 1000f,
        )

        // 触摸坐标是 Float，断言用容差而不是精确相等。
        assertEquals(0.1, region?.left ?: 0.0, 1e-6)
        assertEquals(0.2, region?.top ?: 0.0, 1e-6)
        assertEquals(0.3, region?.right ?: 0.0, 1e-6)
        assertEquals(0.5, region?.bottom ?: 0.0, 1e-6)
    }

    @Test
    fun dragRectNormalizesReverseDirectionAndClampsToPage() {
        val region = normalizedRegionFromRect(
            start = Offset(1100f, -50f),
            end = Offset(600f, 300f),
            canvasWidth = 1000f,
            canvasHeight = 1000f,
        )

        // 反向拖拽与越界都要归一化到 0..1 的合法区域。
        assertEquals(0.6, region?.left ?: 0.0, 1e-6)
        assertEquals(0.0, region?.top ?: 0.0, 1e-6)
        assertEquals(1.0, region?.right ?: 0.0, 1e-6)
        assertEquals(0.3, region?.bottom ?: 0.0, 1e-6)
    }

    @Test
    fun tinyDragsAreRejectedInsteadOfBlockingConfirm() {
        assertNull(
            normalizedRegionFromRect(
                start = Offset(100f, 100f),
                end = Offset(120f, 110f),
                canvasWidth = 1000f,
                canvasHeight = 1000f,
            ),
        )
    }

    @Test
    fun zeroCanvasRejectsEverything() {
        assertNull(
            normalizedRegionFromRect(
                start = Offset(0f, 0f),
                end = Offset(100f, 100f),
                canvasWidth = 0f,
                canvasHeight = 0f,
            ),
        )
    }

    @Test
    fun mostlyOverlappingRegionsAreRejected() {
        // 小框被大框覆盖约 81%（>80%）：用户重复框了同一道题。
        val mostlyOverlapping = listOf(
            NormalizedSourceRegion(0.0, 0.0, 0.5, 0.5),
            NormalizedSourceRegion(0.05, 0.05, 0.55, 0.55),
        )
        assertTrue(regionsOverlapBeyondLimit(mostlyOverlapping))

        val separate = listOf(
            NormalizedSourceRegion(0.0, 0.0, 0.5, 0.5),
            NormalizedSourceRegion(0.5, 0.5, 1.0, 1.0),
        )
        assertFalse(regionsOverlapBeyondLimit(separate))
    }

    @Test
    fun overlapLimitMatchesSplitRequestSemantics() {
        // 小框被大框覆盖约 64%（<80%）是允许的相邻题框。
        val tolerated = listOf(
            NormalizedSourceRegion(0.0, 0.0, 0.5, 0.5),
            NormalizedSourceRegion(0.4, 0.4, 0.7, 0.7),
        )
        assertFalse(regionsOverlapBeyondLimit(tolerated))
    }
}
