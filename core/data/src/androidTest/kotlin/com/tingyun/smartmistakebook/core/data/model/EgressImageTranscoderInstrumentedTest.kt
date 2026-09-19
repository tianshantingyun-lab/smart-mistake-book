package com.tingyun.smartmistakebook.core.data.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 出网图片压制的真实编解码面（策略的算术在 JVM 单测里，这里证明它在真机上确实生效）。
 *
 * 这条链路此前把 12MP 原图直接 base64 出网：一次多图请求会顶到传输上限而被上游整条拒收，
 * 学生看到的是"本次题图总量超过单次发送上限"——连文字一起废掉。
 */
@RunWith(AndroidJUnit4::class)
class EgressImageTranscoderInstrumentedTest {

    private fun photo(
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // 确定性噪声图案：均匀色块会压到几十 KB，测不出"超预算"这件事；这里要的是
        // "压不动"，不是随机性——固定图案也让测试可复现。
        val pixels = IntArray(width * height) { index ->
            val mixed = (index * 2_654_435_761L).toInt() xor (index shl 7)
            mixed or 0xFF000000.toInt()
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val output = ByteArrayOutputStream()
        check(bitmap.compress(format, 95, output))
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun boundsOf(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }

    @Test
    fun aLargePhotoIsBoundedBeforeItLeavesTheDevice() {
        val source = photo(3_072, 4_096, Bitmap.CompressFormat.JPEG)

        val egress = runBlocking {
            EgressImageTranscoder.transcodeForEgress("image/jpeg", source)
        }

        assertEquals("image/jpeg", egress.mimeType)
        val (width, height) = boundsOf(egress.bytes)
        assertTrue("长边必须压到预算内，实际 ${width}x$height", maxOf(width, height) <= EgressImageBudget.MAX_EDGE_PX)
        assertTrue(
            "体积必须压到单图预算内，实际 ${egress.bytes.size}",
            egress.bytes.size <= EgressImageBudget.MAX_IMAGE_BYTES,
        )
        assertTrue("必须真的变小，否则这次压制没意义", egress.bytes.size < source.size)
    }

    @Test
    fun aPhotoThatAlreadyFitsIsSentByteForByte() {
        val source = photo(1_200, 900, Bitmap.CompressFormat.JPEG)

        val egress = runBlocking {
            EgressImageTranscoder.transcodeForEgress("image/jpeg", source)
        }

        assertSame("够小的图不该被重编码", source, egress.bytes)
        assertEquals("image/jpeg", egress.mimeType)
    }

    @Test
    fun anOversizedPngLeavesAsAJpeg() {
        // 非 JPEG 源按 PNG 存下时体积是同图 JPEG 的数倍，一旦超预算就统一转成 JPEG。
        val source = photo(2_048, 2_048, Bitmap.CompressFormat.PNG)
        assertTrue(
            "前置条件：这份 PNG 必须真的超预算，实际 ${source.size}",
            source.size > EgressImageBudget.MAX_IMAGE_BYTES,
        )

        val egress = runBlocking {
            EgressImageTranscoder.transcodeForEgress("image/png", source)
        }

        assertEquals("image/jpeg", egress.mimeType)
        val (width, height) = boundsOf(egress.bytes)
        assertEquals(2_048, width)
        assertEquals(2_048, height)
    }

    @Test
    fun aPngThatAlreadyFitsIsSentByteForByte() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val source = ByteArrayOutputStream().also { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }.toByteArray()
        bitmap.recycle()

        val egress = runBlocking {
            EgressImageTranscoder.transcodeForEgress("image/png", source)
        }

        assertSame("够小的 PNG 不该被转码", source, egress.bytes)
        assertEquals("image/png", egress.mimeType)
    }

    @Test
    fun transparencyIsFlattenedOntoWhiteInsteadOfBlack() {
        // 左半张完全透明、右半张不可压缩：全透明图会被 PNG 编码器压成几 KB（RGB 被清零），
        // 那样就跨不过体积预算，测不到重编码路径。
        val size = 2_048
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val mixed = (index * 2_654_435_761L).toInt() xor (index shl 7)
            if (x < size / 2) 0 else mixed or 0xFF000000.toInt()
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        val source = ByteArrayOutputStream().also { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 95, output))
        }.toByteArray()
        bitmap.recycle()
        assertTrue(
            "前置条件：这份带透明通道的 PNG 必须真的超预算，实际 ${source.size}",
            source.size > EgressImageBudget.MAX_IMAGE_BYTES,
        )

        val egress = runBlocking {
            EgressImageTranscoder.transcodeForEgress("image/png", source)
        }

        assertEquals("image/jpeg", egress.mimeType)
        val decoded = BitmapFactory.decodeByteArray(egress.bytes, 0, egress.bytes.size)
        // 透明区域必须变成白底：JPEG 没有 alpha，不压白底就会整片发黑。JPEG 是有损的，
        // 所以断言"接近纯白"而不是逐位相等。
        val pixel = decoded.getPixel(0, 0)
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        assertTrue(
            "透明区域应压成白底，实际 RGB=$red,$green,$blue",
            red >= 240 && green >= 240 && blue >= 240,
        )
        decoded.recycle()
    }
}
