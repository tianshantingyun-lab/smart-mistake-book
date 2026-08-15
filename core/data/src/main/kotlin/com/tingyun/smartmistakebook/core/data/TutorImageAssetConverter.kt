package com.tingyun.smartmistakebook.core.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import java.io.File
import java.security.MessageDigest

/**
 * 将讲题图片资产转换为模型可用的格式
 */
class TutorImageAssetConverter(private val context: Context) {

    private val imageAssetManager = TutorImageAssetManager(context)

    /**
     * 从资产ID创建 ModelEgressAssetGrant
     * @param assetId 资产ID（例如 "tutor_img_xxx"）
     * @return ModelEgressAssetGrant 或 null（如果文件不存在或无效）
     */
    fun createAssetGrant(assetId: String): ModelEgressAssetGrant? {
        val uri = imageAssetManager.getImageUri(assetId) ?: return null

        // 获取文件路径
        val file = when (uri.scheme) {
            "file" -> uri.path?.let { File(it) }
            "content" -> null // Content URI 需要特殊处理
            else -> null
        } ?: return null

        if (!file.exists() || !file.isFile) return null

        return try {
            // 读取图片尺寸
            val bounds = decodeBounds(file) ?: return null

            // 计算文件 SHA-256
            val sha256 = sha256(file)

            // 获取文件大小
            val byteSize = file.length()

            ModelEgressAssetGrant(
                assetId = assetId,
                sha256 = sha256,
                byteSize = byteSize,
                width = bounds.width,
                height = bounds.height,
                selectedRegion = null, // 讲题不需要选区
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 批量转换资产ID到 ModelEgressAssetGrant
     */
    fun createAssetGrants(assetIds: List<String>): List<ModelEgressAssetGrant> {
        return assetIds.mapNotNull { createAssetGrant(it) }
    }

    /**
     * 读取图片尺寸
     */
    private fun decodeBounds(file: File): ImageBounds? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)

        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        return ImageBounds(
            width = options.outWidth,
            height = options.outHeight,
        )
    }

    /**
     * 计算文件的 SHA-256 哈希
     */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private data class ImageBounds(
        val width: Int,
        val height: Int,
    )
}
