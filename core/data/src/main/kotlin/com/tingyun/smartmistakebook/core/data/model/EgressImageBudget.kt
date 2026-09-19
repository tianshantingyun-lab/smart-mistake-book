package com.tingyun.smartmistakebook.core.data.model

/**
 * 出网图片的有界副本策略（纯计算，不碰 Android 解码器，便于单测）。
 *
 * 消灭的失败：学生一次选 9 张 12MP 手机照片时，一次请求的 base64 体量会顶到 36MiB 传输
 * 上限，上游直接拒收（`REQUEST_TOO_LARGE`：「本次题图总量超过单次发送上限，请减少图片后
 * 重试」），整条消息连图带文全废；单张 12MP 也会让上传慢到容易超时。非 JPEG 源更糟：导入
 * 时按 PNG 编码，体积是同图 JPEG 的数倍。
 *
 * 做法是"只在需要时才加工"：原字节已经够小（体积与长边都在预算内）就**原样出网**——学生
 * 自己的字节不做无谓重编码；只有超预算时才等比缩到长边上限、重编码为 JPEG。出网的是已授权
 * 资产的一个**有界副本**：授权校验仍针对原始资产字节（sha256/尺寸），这里只减少外发体积。
 */
internal object EgressImageBudget {

    /** 长边上限：够读题面与手写批注，同时把解码与上传成本压在可接受范围。 */
    const val MAX_EDGE_PX = 2_048

    /** 单图出网体积上限；按最大 9 张计约 13.5MB，加文本仍在传输上限内。 */
    const val MAX_IMAGE_BYTES = 1_500_000L

    const val JPEG_QUALITY = 88

    /** 缩小后仍超单图预算时的兜底质量：宁可略降清晰度，也不把整条消息顶成"发不出去"。 */
    const val JPEG_FALLBACK_QUALITY = 70

    fun plan(mimeType: String, width: Int, height: Int, byteSize: Long): EgressImagePlan {
        val withinBudget = byteSize in 1..MAX_IMAGE_BYTES && maxOf(width, height) <= MAX_EDGE_PX
        return if (withinBudget) EgressImagePlan.KeepSourceBytes else EgressImagePlan.Downscale
    }

    /** 等比缩到长边不超过 [maxEdgePx]；已经够小就返回原尺寸。 */
    fun scaledSize(width: Int, height: Int, maxEdgePx: Int = MAX_EDGE_PX): ImageSize {
        require(width > 0 && height > 0) { "Egress image dimensions must be positive" }
        require(maxEdgePx > 0) { "Egress image edge budget must be positive" }
        val longestEdge = maxOf(width, height)
        if (longestEdge <= maxEdgePx) return ImageSize(width, height)
        val scale = maxEdgePx.toDouble() / longestEdge
        return ImageSize(
            width = maxOf(1, (width * scale).toInt()),
            height = maxOf(1, (height * scale).toInt()),
        )
    }

    /**
     * 解码采样率：先按 2 的幂次降采样再精确缩放，避免为了缩小而先把 12MP 全量解码进内存
     * （那正是 OOM 的来源）。返回值只保证"降采样后仍不小于目标"，精确尺寸交给缩放。
     */
    fun sampleSizeFor(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        require(width > 0 && height > 0) { "Egress image dimensions must be positive" }
        require(targetWidth > 0 && targetHeight > 0) { "Egress image target must be positive" }
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }

    /** 出网一律用能承载的照片格式：JPEG 体积最省、上游支持最广。 */
    fun egressMimeType(mimeType: String): String = "image/jpeg"
}

internal sealed interface EgressImagePlan {
    /** 原字节已经够小：直接出网，不重新编码。 */
    data object KeepSourceBytes : EgressImagePlan

    /** 超预算：等比缩小后重编码为 JPEG。 */
    data object Downscale : EgressImagePlan
}

internal data class ImageSize(val width: Int, val height: Int)
