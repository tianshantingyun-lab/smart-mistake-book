package com.tingyun.smartmistakebook.core.data.capture

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tingyun.smartmistakebook.core.domain.MAX_BATCH_IMPORT_PAGES
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt

internal interface PdfBatchImportPageRenderer {
    fun render(source: Uri, session: File): List<File>
}

internal class AndroidPdfBatchImportPageRenderer(
    private val resolver: ContentResolver,
) : PdfBatchImportPageRenderer {
    override fun render(source: Uri, session: File): List<File> {
        val temporaryPdf = File(session, "document.pdf.partial").canonicalFile
        val privatePdf = File(session, "document.pdf").canonicalFile
        check(temporaryPdf.parentFile == session && privatePdf.parentFile == session)
        val input = resolver.openInputStream(source)
            ?: throw IOException("PDF import source could not be opened")
        val copied = input.use { sourceStream ->
            FileOutputStream(temporaryPdf).use { destinationStream ->
                copyBoundedSource(
                    input = sourceStream,
                    output = destinationStream,
                    sourceLimit = MAX_PDF_SOURCE_BYTES,
                    batchLimit = MAX_PDF_SOURCE_BYTES,
                )
            }
        }
        if (copied == 0L) throw IOException("PDF import source was empty")
        check(temporaryPdf.renameTo(privatePdf)) {
            "PDF import source could not be committed to private staging"
        }
        val pages = ArrayList<File>()
        var renderedBytes = 0L
        ParcelFileDescriptor.open(privatePdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount in 2..MAX_BATCH_IMPORT_PAGES) {
                    "PDF import must contain between 2 and $MAX_BATCH_IMPORT_PAGES pages"
                }
                repeat(renderer.pageCount) { pageIndex ->
                    renderer.openPage(pageIndex).use { page ->
                        val (width, height) = renderDimensions(page.width, page.height)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                            val destination = File(
                                session,
                                "page-${pageIndex.toString().padStart(2, '0')}.source",
                            ).canonicalFile
                            check(destination.parentFile == session)
                            renderedBytes += commitRenderedPage(bitmap, session, destination)
                            if (renderedBytes > MAX_BATCH_IMPORT_STAGED_BYTES) {
                                throw IOException("Rendered PDF exceeds its private staging limit")
                            }
                            pages += destination
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
        check(privatePdf.delete()) { "Private PDF staging copy could not be removed" }
        return pages
    }

    private fun commitRenderedPage(bitmap: Bitmap, session: File, destination: File): Long {
        val temporary = File.createTempFile("render-", ".partial", session).canonicalFile
        check(temporary.parentFile == session)
        try {
            FileOutputStream(temporary).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PDF_JPEG_QUALITY, output)) {
                    throw IOException("PDF page could not be rendered")
                }
            }
            val pageBytes = temporary.length()
            if (pageBytes <= 0L) throw IOException("Rendered PDF page was empty")
            check(temporary.renameTo(destination)) {
                "Rendered PDF page could not be committed to private staging"
            }
            return pageBytes
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
    }

    private fun renderDimensions(pageWidth: Int, pageHeight: Int): Pair<Int, Int> {
        require(pageWidth > 0 && pageHeight > 0) { "PDF page dimensions must be positive" }
        val scale = PDF_RENDER_LONG_EDGE_PIXELS.toDouble() / maxOf(pageWidth, pageHeight)
        return maxOf(1, (pageWidth * scale).roundToInt()) to
            maxOf(1, (pageHeight * scale).roundToInt())
    }

    private companion object {
        const val MAX_PDF_SOURCE_BYTES = 128L * 1024L * 1024L
        const val PDF_RENDER_LONG_EDGE_PIXELS = 2_200
        const val PDF_JPEG_QUALITY = 94
    }
}
