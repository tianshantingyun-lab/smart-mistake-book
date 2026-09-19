package com.tingyun.smartmistakebook.core.data.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把已授权的规范资产字节转成有界副本再出网（策略见 [EgressImageBudget]）。
 *
 * 每个环节的失败都退回"原样出网"而不是让这次发送死掉：这些字节来自本应用校验过哈希与尺寸的
 * 资产库，读不懂说明是异常情况；此时让上游与请求预算去判，比在本地新增一种发送失败更诚实。
 */
internal object EgressImageTranscoder {

    suspend fun transcodeForEgress(mimeType: String, bytes: ByteArray): EgressImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            Log.w(LOG_TAG, "Egress image header is unreadable; sending the authorized bytes as-is")
            return EgressImage(mimeType, bytes)
        }
        val plan = EgressImageBudget.plan(mimeType, width, height, bytes.size.toLong())
        if (plan == EgressImagePlan.KeepSourceBytes) return EgressImage(mimeType, bytes)
        // 解码与重编码是 CPU 密集的（12MP 一进一出约百毫秒级），绝不能在主线程上做：
        // 调用方是 UI 协程作用域，9 张图连着解码足够卡到 ANR。
        return withContext(Dispatchers.Default) {
            downscale(mimeType, bytes, width, height)
        }
    }

    private fun downscale(
        mimeType: String,
        bytes: ByteArray,
        width: Int,
        height: Int,
    ): EgressImage {
        val target = EgressImageBudget.scaledSize(width, height)
        val sample = EgressImageBudget.sampleSizeFor(
            width = width,
            height = height,
            targetWidth = target.width,
            targetHeight = target.height,
        )
        var decoded: Bitmap? = null
        var scaled: Bitmap? = null
        var flattened: Bitmap? = null
        return try {
            decoded = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: run {
                Log.w(LOG_TAG, "Egress image pixels are undecodable; sending authorized bytes as-is")
                return EgressImage(mimeType, bytes)
            }
            val sampled = if (decoded.width == target.width && decoded.height == target.height) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, target.width, target.height, true)
                    .also { scaled = it }
            }
            // JPEG 没有透明通道：带 alpha 的图（截图、图元）先压到白底。
            val canvasSource = if (sampled.hasAlpha()) {
                Bitmap.createBitmap(sampled.width, sampled.height, Bitmap.Config.ARGB_8888)
                    .also { flattened = it }
                    .also { flat ->
                        Canvas(flat).apply {
                            drawColor(Color.WHITE)
                            drawBitmap(sampled, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                        }
                    }
            } else {
                sampled
            }
            val encoded = compress(canvasSource, EgressImageBudget.JPEG_QUALITY)
            val bounded = if (encoded.size > EgressImageBudget.MAX_IMAGE_BYTES) {
                compress(canvasSource, EgressImageBudget.JPEG_FALLBACK_QUALITY)
            } else {
                encoded
            }
            EgressImage(EgressImageBudget.egressMimeType(mimeType), bounded)
        } catch (outOfMemory: OutOfMemoryError) {
            Log.w(LOG_TAG, "Egress image downscale exceeded memory; sending authorized bytes as-is", outOfMemory)
            EgressImage(mimeType, bytes)
        } finally {
            // 只回收本次新建的位图；`decoded` 若是原图则一并回收，它已不再被使用。
            listOf(flattened, scaled, decoded).forEach { bitmap ->
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            "Egress image encoding failed"
        }
        return output.toByteArray()
    }

    private const val LOG_TAG = "SmartMistakeBook"
}

/** 一次出网图片：可能是有界副本，也可能是原字节（见 [EgressImageBudget.plan]）。 */
internal class EgressImage(val mimeType: String, val bytes: ByteArray)
