package com.tingyun.smartmistakebook.core.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class MistakePdfPreview private constructor(
    val sourceFile: File,
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {
    val pageCount: Int get() = renderer.pageCount

    fun renderPage(pageIndex: Int, maxWidthPx: Int, maxHeightPx: Int): Bitmap {
        require(pageIndex in 0 until pageCount) { "Page index is out of bounds" }
        require(maxWidthPx > 0 && maxHeightPx > 0) { "Preview bounds must be positive" }
        require(
            maxWidthPx <= MistakePdfExportLimits.MAX_PREVIEW_EDGE_PX &&
                maxHeightPx <= MistakePdfExportLimits.MAX_PREVIEW_EDGE_PX,
        ) { "Preview bounds exceed the bitmap budget" }
        renderer.openPage(pageIndex).use { page ->
            val scale = min(
                maxWidthPx.toFloat() / page.width,
                maxHeightPx.toFloat() / page.height,
            )
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val matrix = Matrix().apply { setScale(scale, scale) }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    override fun close() {
        renderer.close()
        descriptor.close()
    }

    companion object {
        fun open(prepared: PreparedMistakePdf): MistakePdfPreview {
            require(prepared.verifyIntegrity()) { "Prepared PDF integrity check failed" }
            val descriptor = ParcelFileDescriptor.open(
                prepared.file,
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            return try {
                MistakePdfPreview(prepared.file, descriptor, PdfRenderer(descriptor))
            } catch (failure: Exception) {
                descriptor.close()
                throw failure
            }
        }
    }
}

class PreparedPdfPrintDocumentAdapter(
    private val prepared: PreparedMistakePdf,
    private val displayName: String,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) : PrintDocumentAdapter() {
    init {
        require(displayName.isNotBlank()) { "Print display name must not be blank" }
    }

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: android.os.Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        if (!prepared.verifyIntegrity()) {
            callback.onLayoutFailed("PDF 暂时无法生成")
            return
        }
        callback.onLayoutFinished(
            PrintDocumentInfo.Builder(displayName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(prepared.pageCount)
                .build(),
            oldAttributes != newAttributes,
        )
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        executor.execute {
            if (cancellationSignal.isCanceled) {
                callback.onWriteCancelled()
                return@execute
            }
            try {
                val requested = requestedPageIndexes(pages, prepared.pageCount)
                if (requested == null) {
                    callback.onWriteFailed("PDF 暂时无法生成")
                } else if (requested.size == prepared.pageCount) {
                    // Whole document requested: stream the original file so the
                    // printed output keeps its vector content.
                    val copiedDigest = copyAndDigest(prepared.file, destination, cancellationSignal)
                    if (copiedDigest != prepared.sha256) {
                        callback.onWriteFailed("PDF 暂时无法生成")
                    } else if (cancellationSignal.isCanceled) {
                        callback.onWriteCancelled()
                    } else {
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    }
                } else {
                    // Partial range: the print framework honours the student's
                    // selection only if the adapter writes exactly those pages.
                    // Copying the whole file and reporting ALL_PAGES (the old
                    // behaviour) printed every page regardless of the choice.
                    writePageSubset(requested, destination, cancellationSignal)
                    if (cancellationSignal.isCanceled) {
                        callback.onWriteCancelled()
                    } else {
                        callback.onWriteFinished(pages)
                    }
                }
            } catch (_: Exception) {
                if (cancellationSignal.isCanceled) {
                    callback.onWriteCancelled()
                } else {
                    callback.onWriteFailed("无法复制 PDF")
                }
            }
        }
    }

    override fun onFinish() {
        executor.shutdownNow()
        super.onFinish()
    }

    /**
     * Expands the print framework's requested ranges into a contiguous page
     * index list, or null when the request is unusable. `PageRange.ALL_PAGES`
     * is open-ended (0..Int.MAX_VALUE), so every range is clamped to the real
     * page count.
     */
    private fun requestedPageIndexes(
        pages: Array<out PageRange>,
        pageCount: Int,
    ): List<Int>? {
        if (pageCount <= 0 || pages.isEmpty()) return null
        val indexes = sortedSetOf<Int>()
        pages.forEach { range ->
            if (range.start < 0) return null
            val end = min(range.end, pageCount - 1)
            for (index in range.start..end) indexes += index
        }
        return indexes.takeIf { it.isNotEmpty() }?.toList()
    }

    /** Re-renders only [pageIndexes] into [destination] via [PdfRenderer]. */
    private fun writePageSubset(
        pageIndexes: List<Int>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
    ) {
        val source = ParcelFileDescriptor.open(
            prepared.file,
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
        try {
            PdfRenderer(source).use { renderer ->
                val document = PdfDocument()
                try {
                    pageIndexes.forEachIndexed { ordinal, pageIndex ->
                        if (cancellationSignal.isCanceled) return@forEachIndexed
                        renderer.openPage(pageIndex).use { page ->
                            val bitmap = Bitmap.createBitmap(
                                page.width,
                                page.height,
                                Bitmap.Config.ARGB_8888,
                            )
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(
                                    bitmap,
                                    null,
                                    null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                                )
                                val info = PdfDocument.PageInfo
                                    .Builder(page.width, page.height, ordinal + 1)
                                    .create()
                                document.startPage(info).let { documentPage ->
                                    documentPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                                    document.finishPage(documentPage)
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    if (!cancellationSignal.isCanceled) {
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            document.writeTo(output)
                            output.flush()
                        }
                    }
                } finally {
                    document.close()
                }
            }
        } finally {
            source.close()
        }
    }

    private fun copyAndDigest(
        source: File,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        BufferedInputStream(FileInputStream(source)).use { input ->
            FileOutputStream(destination.fileDescriptor).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (cancellationSignal.isCanceled) break
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    require(copied <= MistakePdfExportLimits.MAX_PDF_BYTES) {
                        "PDF exceeds copy budget"
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
        return digest.digest().toHex()
    }
}

typealias PreparedPdfPrintAdapter = PreparedPdfPrintDocumentAdapter

enum class MistakePdfSaveResult {
    SAVED,
    INVALID_PREPARED_FILE,
    DESTINATION_UNAVAILABLE,
    DESTINATION_INTEGRITY_MISMATCH,
}

/** Copies the prepared file selected by the user, then re-reads the destination before success. */
object MistakePdfDocumentSaver {
    fun save(
        context: Context,
        prepared: PreparedMistakePdf,
        destination: Uri,
    ): MistakePdfSaveResult {
        if (!prepared.verifyIntegrity()) return MistakePdfSaveResult.INVALID_PREPARED_FILE
        if (destination.scheme != "content") {
            return MistakePdfSaveResult.DESTINATION_UNAVAILABLE
        }
        if (destination.authority == "${context.packageName}.mistake-pdf-exports") {
            return MistakePdfSaveResult.DESTINATION_UNAVAILABLE
        }
        return try {
            val resolver = context.applicationContext.contentResolver
            val destinationDescriptor = resolver.openFileDescriptor(destination, "rwt")
                ?: return MistakePdfSaveResult.DESTINATION_UNAVAILABLE
            BufferedInputStream(FileInputStream(prepared.file)).use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(destinationDescriptor).use { output ->
                    copyWithinBudget(input, output)
                    output.flush()
                    output.fd.sync()
                }
            }
            val copiedDigest = resolver.openInputStream(destination)?.use(::digestWithinBudget)
                ?: return MistakePdfSaveResult.DESTINATION_UNAVAILABLE
            if (copiedDigest == prepared.sha256) {
                MistakePdfSaveResult.SAVED
            } else {
                MistakePdfSaveResult.DESTINATION_INTEGRITY_MISMATCH
            }
        } catch (_: Exception) {
            MistakePdfSaveResult.DESTINATION_UNAVAILABLE
        }
    }

    private fun copyWithinBudget(input: InputStream, output: FileOutputStream) {
        var copied = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            require(copied <= MistakePdfExportLimits.MAX_PDF_BYTES) {
                "PDF exceeds copy budget"
            }
            output.write(buffer, 0, read)
        }
        require(copied > 0L) { "PDF copy is empty" }
    }

    private fun digestWithinBudget(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var readBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            readBytes += read
            require(readBytes <= MistakePdfExportLimits.MAX_PDF_BYTES) {
                "Saved PDF exceeds verification budget"
            }
            digest.update(buffer, 0, read)
        }
        require(readBytes > 0L) { "Saved PDF is empty" }
        return digest.digest().toHex()
    }
}

object MistakePdfDeliveryIntents {
    const val MIME_TYPE = "application/pdf"

    fun createDocument(displayName: String): Intent {
        require(displayName.isNotBlank()) { "PDF display name must not be blank" }
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = MIME_TYPE
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, displayName)
        }
    }

    fun share(
        context: Context,
        prepared: PreparedMistakePdf,
        displayName: String,
    ): Intent {
        require(displayName.isNotBlank()) { "PDF display name must not be blank" }
        val uri = MistakePdfShareUris.uriFor(context, prepared)
        return Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, displayName)
            clipData = ClipData.newUri(context.contentResolver, displayName, uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
    }
}

object MistakePdfShareUris {
    fun uriFor(context: Context, prepared: PreparedMistakePdf): Uri {
        require(prepared.verifyIntegrity()) { "Prepared PDF integrity check failed" }
        val allowedDirectory = File(
            context.applicationContext.cacheDir,
            MistakePdfExporter.EXPORT_DIRECTORY,
        ).canonicalFile
        val preparedFile = prepared.file.canonicalFile
        val contentDirectory = preparedFile.parentFile
        require(
            contentDirectory?.parentFile == allowedDirectory &&
                contentDirectory.name == prepared.inputSha256 &&
                preparedFile.name == PREPARED_PDF_FILE_NAME,
        ) {
            "Prepared PDF is outside the share directory"
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.mistake-pdf-exports",
            preparedFile,
        )
    }
}
