package com.tingyun.smartmistakebook.core.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「9 张 12MP 照片 → 一次请求顶到 36MiB 传输上限 → 上游整条拒收」这条失败的策略面：
 * 哪些图原样出网、哪些必须压成有界副本，以及压缩时的尺寸算术。
 *
 * 关键性质是"只有超预算的才重编码"——学生手机里本来就不大的图不该被我们再压一遍。
 */
class EgressImageBudgetTest {

    @Test
    fun aSmallJpegGoesOutExactlyAsStored() {
        val plan = EgressImageBudget.plan(
            mimeType = "image/jpeg",
            width = 1_600,
            height = 1_200,
            byteSize = 900_000,
        )

        assertEquals(EgressImagePlan.KeepSourceBytes, plan)
    }

    @Test
    fun aTwelveMegapixelPhotoIsDownscaled() {
        val plan = EgressImageBudget.plan(
            mimeType = "image/jpeg",
            width = 3_072,
            height = 4_096,
            byteSize = 3_599_286,
        )

        assertEquals(EgressImagePlan.Downscale, plan)
    }

    @Test
    fun anOversizedPngIsScheduledForReEncodingAsAJpeg() {
        // 非 JPEG 源按 PNG 存下时体积是同图 JPEG 的数倍，越容易撞预算；
        // 一旦需要重编码，出网格式统一成 JPEG。
        val plan = EgressImageBudget.plan(
            mimeType = "image/png",
            width = 2_048,
            height = 1_536,
            byteSize = 4_200_000,
        )

        assertEquals(EgressImagePlan.Downscale, plan)
        assertEquals("image/jpeg", EgressImageBudget.egressMimeType("image/png"))
    }

    @Test
    fun anImageJustOverTheByteBudgetIsDownscaledEvenWhenItsEdgesAreSmall() {
        val plan = EgressImageBudget.plan(
            mimeType = "image/jpeg",
            width = 1_200,
            height = 900,
            byteSize = EgressImageBudget.MAX_IMAGE_BYTES + 1,
        )

        assertEquals(EgressImagePlan.Downscale, plan)
    }

    @Test
    fun downscalingKeepsTheAspectRatioAndTheLongEdgeBudget() {
        val scaled = EgressImageBudget.scaledSize(width = 3_072, height = 4_096)

        assertEquals(EgressImageBudget.MAX_EDGE_PX, scaled.height)
        assertEquals(1_536, scaled.width)
    }

    @Test
    fun anImageInsideTheEdgeBudgetIsNotResized() {
        val scaled = EgressImageBudget.scaledSize(width = 1_600, height = 900)

        assertEquals(ImageSize(1_600, 900), scaled)
    }

    @Test
    fun sampleSizeNeverDecodesSmallerThanTheTarget() {
        // 采样只负责"别把 12MP 全量解进内存"，精确尺寸交给缩放：采样后必须仍不小于目标。
        val sample = EgressImageBudget.sampleSizeFor(
            width = 3_072,
            height = 4_096,
            targetWidth = 1_536,
            targetHeight = 2_048,
        )

        assertEquals(2, sample)
        assertTrue(3_072 / sample >= 1_536)
        assertTrue(4_096 / sample >= 2_048)
    }

    @Test
    fun sampleSizeStaysOneWhenNoDownsamplingIsNeeded() {
        val sample = EgressImageBudget.sampleSizeFor(
            width = 1_000,
            height = 800,
            targetWidth = 1_000,
            targetHeight = 800,
        )

        assertEquals(1, sample)
    }
}
