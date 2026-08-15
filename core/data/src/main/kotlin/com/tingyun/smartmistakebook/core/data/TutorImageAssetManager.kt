package com.tingyun.smartmistakebook.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 管理讲题对话中的图片资产
 * - 复制用户选择的图片到应用私有目录
 * - 生成持久化的资产引用ID
 * - 清理过期资产
 * - 安全防护：字节上限、EXIF 清理、原子写入
 */
class TutorImageAssetManager(private val context: Context) {

    private val assetDir = File(context.filesDir, "tutor_images").apply {
        if (!exists()) mkdirs()
    }

    companion object {
        /** 单张图片最大字节数（10 MB） */
        private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024L

        /** EXIF 敏感标签（GPS、设备信息、时间戳等） */
        private val EXIF_SENSITIVE_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_USER_COMMENT,
            "ImageUniqueID",
            "CameraSerialNumber",
            "LensSerialNumber",
        )
    }

    /**
     * 保存用户选择的图片到私有目录
     * @param sourceUri 用户选择的图片URI（可能是临时的）
     * @return 持久化的资产引用ID（可以在数据库中存储）
     * @throws IllegalStateException 如果图片无法读取、超过大小限制或无效
     */
    suspend fun saveImage(sourceUri: Uri): String {
        val assetId = "tutor_img_${UUID.randomUUID()}"
        val extension = getImageExtension(sourceUri)
        val targetFile = File(assetDir, "$assetId.$extension")
        val tempFile = File(assetDir, "$assetId.tmp")

        try {
            // 原子写入：先写临时文件，成功后再 rename
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                // 检查字节大小
                val totalBytes = input.available().toLong()
                if (totalBytes > MAX_IMAGE_BYTES) {
                    throw IllegalStateException("Image exceeds $MAX_IMAGE_BYTES bytes: $totalBytes")
                }

                // 复制到临时文件
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Failed to open image: $sourceUri")

            // 清理 EXIF 敏感元数据（GPS、设备信息等）
            stripExifMetadata(tempFile)

            // 验证图片可解码（防止损坏文件）
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tempFile.path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                throw IllegalStateException("Invalid image file: cannot decode bounds")
            }

            // 原子提交：临时文件 rename 到最终文件
            if (!tempFile.renameTo(targetFile)) {
                throw IllegalStateException("Failed to commit image file")
            }

            return assetId
        } catch (e: Exception) {
            // 清理临时文件
            tempFile.delete()
            throw e
        }
    }

    /**
     * 清理 EXIF 敏感元数据，保留方向信息
     */
    private fun stripExifMetadata(file: File) {
        try {
            val exif = ExifInterface(file.path)

            // 保存原始方向
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            // 清除所有标签（包括 GPS、设备制造商、时间戳等）
            EXIF_SENSITIVE_TAGS.forEach { tag ->
                exif.setAttribute(tag, null)
            }

            // 恢复方向标签（显示需要）
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())

            // 写回文件
            exif.saveAttributes()
        } catch (e: Exception) {
            // EXIF 处理失败不阻断保存，但记录日志
            android.util.Log.w("TutorImageAssetManager", "Failed to strip EXIF: ${e.message}")
        }
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
