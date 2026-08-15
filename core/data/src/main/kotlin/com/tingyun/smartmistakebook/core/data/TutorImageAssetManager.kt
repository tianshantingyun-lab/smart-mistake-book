package com.tingyun.smartmistakebook.core.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import java.util.UUID

/**
 * 管理讲题对话中的图片资产
 * - 复制用户选择的图片到应用私有目录
 * - 生成持久化的资产引用ID
 * - 清理过期资产
 */
class TutorImageAssetManager(private val context: Context) {

    private val assetDir = File(context.filesDir, "tutor_images").apply {
        if (!exists()) mkdirs()
    }

    /**
     * 保存用户选择的图片到私有目录
     * @param sourceUri 用户选择的图片URI（可能是临时的）
     * @return 持久化的资产引用ID（可以在数据库中存储）
     */
    suspend fun saveImage(sourceUri: Uri): String {
        val assetId = "tutor_img_${UUID.randomUUID()}"
        val extension = getImageExtension(sourceUri)
        val targetFile = File(assetDir, "$assetId.$extension")

        // 复制图片内容
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Failed to open image: $sourceUri")

        return assetId
    }

    /**
     * 根据资产ID获取图片URI
     */
    fun getImageUri(assetId: String): Uri? {
        val files = assetDir.listFiles() ?: return null
        val file = files.find { it.nameWithoutExtension == assetId }
        return file?.toUri()
    }

    /**
     * 删除指定资产
     */
    fun deleteAsset(assetId: String) {
        val files = assetDir.listFiles() ?: return
        files.find { it.nameWithoutExtension == assetId }?.delete()
    }

    /**
     * 清理孤立的资产（未被任何对话引用）
     * 注意：这个方法需要数据库查询，暂时简化实现
     */
    suspend fun cleanupOrphanedAssets() {
        // TODO: 查询数据库，找出未被引用的资产并删除
        // 目前简化：保留所有资产
    }

    private fun getImageExtension(uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri)
        return when (mimeType) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg" // 默认
        }
    }
}
