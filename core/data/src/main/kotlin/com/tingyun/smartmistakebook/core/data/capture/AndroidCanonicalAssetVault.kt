package com.tingyun.smartmistakebook.core.data.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor

internal const val MAX_CANONICAL_SOURCE_INPUT_BYTES = 20L * 1_024L * 1_024L

/** Bounded decoder and EXIF-stripping vault for app-private capture URIs. */
internal class AndroidCanonicalAssetVault(
    private val context: Context,
) {
    fun import(
        localUri: String,
        sourceType: String,
        createdAtEpochMillis: Long,
    ): CanonicalSourceAssetRecord {
        val uri = Uri.parse(localUri)
        require(uri.scheme == "content") { "Capture input must be a content URI" }
        require(
            uri.authority == "${context.packageName}.capture.fileprovider" ||
                uri.authority == batchImportProviderAuthority(context),
        ) {
            "Capture input must come from the app-private provider"
        }

        val assetRoot = File(context.filesDir, ASSET_DIRECTORY).also { directory ->
            check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) {
                "Cannot create canonical asset vault"
            }
            check(directory.canonicalFile.parentFile == context.filesDir.canonicalFile) {
                "Canonical asset vault escaped app-private storage"
            }
        }
        val raw = File.createTempFile(".raw-", ".tmp", assetRoot)
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                copyWithinLimit(input, raw, MAX_CANONICAL_SOURCE_INPUT_BYTES)
            } ?: false
            check(copied) { "Capture input is empty or exceeds the byte budget" }

            val bounds = decodeBounds(raw)
            check(bounds.width in 1..MAX_DIMENSION && bounds.height in 1..MAX_DIMENSION) {
                "Capture dimensions are outside the supported range"
            }
            check(bounds.width.toLong() * bounds.height <= MAX_PIXELS) {
                "Capture exceeds the decoded pixel budget"
            }

            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decoded = BitmapFactory.decodeFile(raw.path, options)
                ?: error("Capture pixels cannot be decoded")
            oriented = applyExifOrientation(decoded, readExifOrientation(raw))
            val output = checkNotNull(oriented)
            return persistBitmap(
                bitmap = output,
                preferJpeg = bounds.mimeType == "image/jpeg",
                sourceType = sourceType,
                createdAtEpochMillis = createdAtEpochMillis,
            )
        } catch (outOfMemory: OutOfMemoryError) {
            throw IllegalArgumentException("Capture cannot be decoded within the device memory budget", outOfMemory)
        } finally {
            if (oriented !== decoded) oriented?.recycle()
            decoded?.recycle()
            raw.delete()
        }
    }

    fun crop(
        source: CanonicalSourceAssetRecord,
        region: NormalizedSourceRegion,
        createdAtEpochMillis: Long,
    ): CanonicalSourceAssetRecord {
        val sourceFile = resolve(source)
        var decoded: Bitmap? = null
        try {
            decoded = BitmapFactory.decodeFile(
                sourceFile.path,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            ) ?: error("Canonical source pixels cannot be decoded")
            val bitmap = checkNotNull(decoded)
            val bounds = region.toPaddedPixelBounds(bitmap.width, bitmap.height)
            var cropped: Bitmap? = null
            return try {
                cropped = Bitmap.createBitmap(
                    bitmap,
                    bounds.left,
                    bounds.top,
                    bounds.width,
                    bounds.height,
                )
                persistBitmap(
                    bitmap = cropped,
                    preferJpeg = source.mimeType == "image/jpeg",
                    sourceType = source.sourceType,
                    createdAtEpochMillis = createdAtEpochMillis,
                )
            } finally {
                if (cropped !== bitmap) cropped?.recycle()
            }
        } catch (outOfMemory: OutOfMemoryError) {
            throw IllegalArgumentException(
                "Capture regions cannot be decoded within the device memory budget",
                outOfMemory,
            )
        } finally {
            decoded?.recycle()
        }
    }

    /**
     * Persists already-decoded image bytes (e.g. an MCP redraw result) as a
     * canonical source asset. Enforces the same decoded bounds and encoded
     * byte budget as [import]; the caller supplies the mime type, which drives
     * the JPEG-vs-PNG canonical encoding.
     */
    fun persistCleanImageBytes(
        bytes: ByteArray,
        mimeType: String,
        sourceType: String,
        createdAtEpochMillis: Long,
    ): CanonicalSourceAssetRecord {
        require(bytes.isNotEmpty() && bytes.size <= MAX_CLEAN_INPUT_BYTES) {
            "Clean image input is empty or exceeds the byte budget"
        }
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        check(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
            "Clean image dimensions are outside the supported range"
        }
        check(width.toLong() * height <= MAX_PIXELS) {
            "Clean image exceeds the decoded pixel budget"
        }
        var decoded: Bitmap? = null
        return try {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: error("Clean image pixels cannot be decoded")
            persistBitmap(
                bitmap = checkNotNull(decoded),
                preferJpeg = mimeType == "image/jpeg",
                sourceType = sourceType,
                createdAtEpochMillis = createdAtEpochMillis,
            )
        } catch (outOfMemory: OutOfMemoryError) {
            throw IllegalArgumentException(
                "Clean image cannot be decoded within the device memory budget",
                outOfMemory,
            )
        } finally {
            decoded?.recycle()
        }
    }

    fun delete(record: CanonicalSourceAssetRecord) {
        val file = resolve(record)
        check(file.delete() || !file.exists()) { "Cannot delete unreferenced canonical asset" }
    }

    /**
     * Reads the bytes of a local content URI (e.g. a session's source image) within
     * the canonical input byte budget. Returns null when the stream is empty, over
     * budget, or cannot be read. Local-only — the URI is never fetched remotely.
     */
    fun readUriBytes(uri: String): ByteArray? {
        val parsed = Uri.parse(uri)
        val scheme = parsed.scheme
        if (scheme != "content" && scheme != "file") return null
        val stream = if (scheme == "content") {
            context.contentResolver.openInputStream(parsed)
        } else {
            parsed.path?.let(::File)?.takeIf(File::isFile)?.inputStream()
        } ?: return null
        return stream.use { input ->
            val bytes = input.readBytes()
            bytes.takeIf { it.isNotEmpty() && it.size <= MAX_CANONICAL_SOURCE_INPUT_BYTES }
        }
    }

    fun resolve(record: CanonicalSourceAssetRecord): File {
        val assetRoot = File(context.filesDir, ASSET_DIRECTORY).canonicalFile
        val file = File(context.filesDir, record.relativePath).canonicalFile
        check(file.parentFile == assetRoot) { "Canonical source asset escaped its vault" }
        check(
            file.isFile &&
                file.length() == record.byteSize &&
                sha256(file) == record.contentSha256,
        ) {
            "Canonical source asset is missing or changed"
        }
        return file
    }

    private fun decodeBounds(file: File): ImageBounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        check(options.outWidth > 0 && options.outHeight > 0 && options.outMimeType != null) {
            "Capture image header is invalid"
        }
        return ImageBounds(
            width = options.outWidth,
            height = options.outHeight,
            mimeType = checkNotNull(options.outMimeType),
        )
    }

    private fun readExifOrientation(file: File): Int = runCatching {
        ExifInterface(file).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun persistBitmap(
        bitmap: Bitmap,
        preferJpeg: Boolean,
        sourceType: String,
        createdAtEpochMillis: Long,
    ): CanonicalSourceAssetRecord {
        val assetRoot = File(context.filesDir, ASSET_DIRECTORY).canonicalFile
        check(assetRoot.isDirectory) { "Canonical asset vault is unavailable" }
        val canonical = File.createTempFile(".canonical-", ".tmp", assetRoot)
        try {
            val format = if (preferJpeg && !bitmap.hasAlpha()) {
                Bitmap.CompressFormat.JPEG
            } else {
                Bitmap.CompressFormat.PNG
            }
            val extension = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
            val mimeType = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
            FileOutputStream(canonical).use { stream ->
                check(bitmap.compress(format, JPEG_QUALITY, stream)) {
                    "Canonical image encoding failed"
                }
                stream.flush()
                stream.fd.sync()
            }
            check(canonical.length() in 1L..MAX_CANONICAL_BYTES) {
                "Canonical image exceeds the byte budget"
            }
            val sha256 = sha256(canonical)
            val destination = File(assetRoot, "$sha256.$extension")
            check(destination.canonicalFile.parentFile == assetRoot) {
                "Canonical asset destination escaped its vault"
            }
            if (destination.exists()) {
                check(destination.isFile && sha256(destination) == sha256) {
                    "Canonical asset hash collision"
                }
            } else {
                check(
                    canonical.renameTo(destination) ||
                        (destination.isFile && sha256(destination) == sha256),
                ) { "Cannot finalize canonical source asset" }
            }
            return CanonicalSourceAssetRecord(
                sourceAssetId = "asset-${sha256.take(32)}",
                contentSha256 = sha256,
                relativePath = "$ASSET_DIRECTORY/$sha256.$extension",
                mimeType = mimeType,
                byteSize = destination.length(),
                width = bitmap.width,
                height = bitmap.height,
                sourceType = sourceType,
                createdAtEpochMillis = createdAtEpochMillis,
            )
        } finally {
            canonical.delete()
        }
    }

    private fun NormalizedSourceRegion.toPaddedPixelBounds(
        imageWidth: Int,
        imageHeight: Int,
    ): PixelBounds {
        val leftPx = floor((left - CROP_PADDING_FRACTION).coerceAtLeast(0.0) * imageWidth).toInt()
        val topPx = floor((top - CROP_PADDING_FRACTION).coerceAtLeast(0.0) * imageHeight).toInt()
        val rightPx = ceil((right + CROP_PADDING_FRACTION).coerceAtMost(1.0) * imageWidth).toInt()
        val bottomPx = ceil((bottom + CROP_PADDING_FRACTION).coerceAtMost(1.0) * imageHeight).toInt()
        check(leftPx in 0 until rightPx && rightPx <= imageWidth)
        check(topPx in 0 until bottomPx && bottomPx <= imageHeight)
        return PixelBounds(
            left = leftPx,
            top = topPx,
            width = rightPx - leftPx,
            height = bottomPx - topPx,
        )
    }

    private fun copyWithinLimit(input: InputStream, destination: File, maxBytes: Long): Boolean {
        var total = 0L
        var emptyReads = 0
        return runCatching {
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) {
                        emptyReads += 1
                        check(emptyReads <= MAX_EMPTY_READS) { "Capture stream made no progress" }
                        continue
                    }
                    emptyReads = 0
                    total += read
                    check(total <= maxBytes) { "Capture input exceeds byte budget" }
                    output.write(buffer, 0, read)
                }
            }
            total > 0
        }.getOrDefault(false).also { copied ->
            if (!copied) destination.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
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
        val mimeType: String,
    )

    private data class PixelBounds(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val ASSET_DIRECTORY = "source-assets"
        const val MAX_CANONICAL_BYTES = 24L * 1_024L * 1_024L
        const val MAX_CLEAN_INPUT_BYTES = 24L * 1_024L * 1_024L
        const val MAX_DIMENSION = 8_192
        const val MAX_PIXELS = 16_000_000L
        const val MAX_EMPTY_READS = 16
        const val JPEG_QUALITY = 95
        const val CROP_PADDING_FRACTION = 0.015
    }
}
