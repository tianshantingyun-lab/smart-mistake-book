package com.tingyun.smartmistakebook.feature.capture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val CAPTURE_CACHE_TTL_MILLIS = 24L * 60L * 60L * 1_000L
private const val MAX_CAPTURE_BYTES = 20L * 1_024L * 1_024L
internal val captureCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

object CaptureCacheMaintenance {
    /** Removes abandoned camera originals without racing a recent saved-state recovery. */
    fun pruneExpiredFiles(context: Context) {
        val applicationContext = context.applicationContext
        captureCleanupScope.launch {
            pruneOwnedCaptureCache(
                context = applicationContext,
                retainedUris = emptyList(),
                deleteBeforeMillis = System.currentTimeMillis() - CAPTURE_CACHE_TTL_MILLIS,
            )
        }
    }
}

internal fun createCaptureUri(context: Context): Result<Uri> = runCatching {
    val directory = File(context.cacheDir, "captured_images").also { folder ->
        check(folder.exists() || folder.mkdirs()) { "Cannot create capture cache directory" }
    }
    val image = File.createTempFile("question_", ".jpg", directory)
    FileProvider.getUriForFile(
        context,
        "${context.packageName}.capture.fileprovider",
        image,
    )
}

internal fun importPickedPhoto(context: Context, sourceUri: Uri): Result<Uri> = runCatching {
    val directory = File(context.cacheDir, "captured_images").also { folder ->
        check(folder.exists() || folder.mkdirs()) { "Cannot create capture cache directory" }
    }
    val image = File.createTempFile("question_", ".img", directory)
    try {
        val copied = context.contentResolver.openInputStream(sourceUri)?.use { input ->
            copyCaptureStreamWithinLimit(input, image)
        } ?: false
        check(copied) { "Selected image failed bounded local import" }
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.capture.fileprovider",
            image,
        )
    } catch (error: Exception) {
        image.delete()
        throw error
    } finally {
        revokePickedPhotoGrant(context, sourceUri)
    }
}

internal fun pruneOwnedCaptureCache(
    context: Context,
    retainedUris: List<String>,
    deleteBeforeMillis: Long? = null,
) {
    val directory = File(context.cacheDir, "captured_images")
    if (!directory.isDirectory) return
    val retainedNames = retainedUris.mapNotNull(::ownedCaptureFileName).toSet()
    pruneCaptureDirectory(directory, retainedNames, deleteBeforeMillis)
}

internal fun pruneCaptureDirectory(
    directory: File,
    retainedNames: Set<String>,
    deleteBeforeMillis: Long? = null,
): Int = directory.listFiles()
    .orEmpty()
    .asSequence()
    .filter { it.isFile && isOwnedCaptureFileName(it.name) }
    .filterNot { it.name in retainedNames }
    .filter { deleteBeforeMillis == null || it.lastModified() <= deleteBeforeMillis }
    .count { it.delete() }

internal fun deleteOwnedCaptureAsync(context: Context, uri: String?) {
    if (uri == null) return
    captureCleanupScope.launch { deleteOwnedCapture(context, uri) }
}

internal fun deleteOwnedCapture(context: Context, uri: String) {
    val fileName = ownedCaptureFileName(uri) ?: return
    val directory = File(context.cacheDir, "captured_images")
    val candidate = File(directory, fileName)
    runCatching {
        if (candidate.canonicalFile.parentFile == directory.canonicalFile) {
            candidate.delete()
        }
    }
}

internal fun isOwnedCaptureUri(uri: String?): Boolean =
    uri != null && ownedCaptureFileName(uri) != null

internal fun ownedCaptureExists(context: Context, uri: String?): Boolean {
    val fileName = ownedCaptureFileName(uri ?: return false) ?: return false
    val directory = File(context.cacheDir, "captured_images")
    return runCatching {
        val candidate = File(directory, fileName)
        candidate.canonicalFile.parentFile == directory.canonicalFile && candidate.isFile
    }.getOrDefault(false)
}

internal fun revokeCaptureGrant(context: Context, uri: String) {
    runCatching {
        context.revokeUriPermission(
            Uri.parse(uri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}

private fun revokePickedPhotoGrant(context: Context, uri: Uri) {
    runCatching {
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal fun ownedCaptureWithinLimit(context: Context, uri: String): Boolean {
    val fileName = ownedCaptureFileName(uri) ?: return false
    val directory = File(context.cacheDir, "captured_images")
    return captureFileWithinLimit(directory, fileName)
}

internal fun captureFileWithinLimit(
    directory: File,
    fileName: String,
    maxBytes: Long = MAX_CAPTURE_BYTES,
): Boolean {
    if (maxBytes <= 0) return false
    val candidate = File(directory, fileName)
    return runCatching {
        candidate.canonicalFile.parentFile == directory.canonicalFile &&
            candidate.isFile &&
            candidate.length() in 1L..maxBytes &&
            hasSupportedImageSignature(candidate)
    }.getOrDefault(false)
}

internal fun copyCaptureStreamWithinLimit(
    input: InputStream,
    destination: File,
    maxBytes: Long = MAX_CAPTURE_BYTES,
): Boolean {
    if (maxBytes <= 0L) return false
    val copied = runCatching {
        var totalBytes = 0L
        var consecutiveEmptyReads = 0
        destination.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) {
                    consecutiveEmptyReads += 1
                    check(consecutiveEmptyReads <= 16) { "Capture stream made no progress" }
                    continue
                }
                consecutiveEmptyReads = 0
                totalBytes += read
                check(totalBytes <= maxBytes) { "Capture exceeds byte budget" }
                output.write(buffer, 0, read)
            }
        }
        totalBytes > 0L && hasSupportedImageSignature(destination)
    }.getOrDefault(false)
    if (!copied) destination.delete()
    return copied
}

private fun hasSupportedImageSignature(file: File): Boolean = runCatching {
    val header = ByteArray(32)
    val read = file.inputStream().buffered().use { it.read(header) }
    when {
        read >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() &&
            header[2] == 0xFF.toByte() -> true
        read >= 8 && header.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        ) -> true
        read >= 6 && String(header, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> true
        read >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> true
        read >= 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp" &&
            String(header, 8, 4, Charsets.US_ASCII) in SUPPORTED_ISO_IMAGE_BRANDS -> true
        else -> false
    }
}.getOrDefault(false)

private fun ownedCaptureFileName(uri: String): String? =
    Uri.parse(uri).lastPathSegment?.takeIf(::isOwnedCaptureFileName)

private fun isOwnedCaptureFileName(fileName: String): Boolean =
    fileName.startsWith("question_") &&
        (fileName.endsWith(".jpg") || fileName.endsWith(".img"))

private val SUPPORTED_ISO_IMAGE_BRANDS = setOf(
    "heic",
    "heix",
    "hevc",
    "hevx",
    "mif1",
    "msf1",
    "avif",
    "avis",
)

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
