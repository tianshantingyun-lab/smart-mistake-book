package com.tingyun.smartmistakebook.core.export

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.domain.MistakeDetail
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.FigureAxis
import com.tingyun.smartmistakebook.core.model.FigureCoordinate
import com.tingyun.smartmistakebook.core.model.FigurePoint
import com.tingyun.smartmistakebook.core.model.FigurePolyline
import com.tingyun.smartmistakebook.core.model.FigureSchema
import com.tingyun.smartmistakebook.core.model.FigureSeriesStyle
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.StructuredChoice
import com.tingyun.smartmistakebook.core.model.WritingLayer
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MistakePdfExporterInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun preparedPdfOpensPreviewsAndReusesSamePathAndDigest() {
        val input = eligibleInput(
            listOf(
                ContentBlock.Paragraph("stem", "已知函数满足 \$f(x)=x^2-2x\$。"),
                ContentBlock.Formula("formula", "f(x)=x^2-2x", "f x 等于 x 平方减二 x"),
                ContentBlock.ChoiceGroup(
                    id = "choice",
                    promptMarkdown = "函数在哪个区间递增？",
                    choices = listOf(
                        StructuredChoice("a", "负无穷到 1"),
                        StructuredChoice("b", "1 到正无穷"),
                    ),
                ),
            ),
        )
        val exporter = MistakePdfExporter(context)

        val first = exporter.prepare(input)
        val second = exporter.prepare(input)

        assertEquals(first.file.canonicalPath, second.file.canonicalPath)
        assertEquals(first.sha256, second.sha256)
        assertEquals(input.inputSha256, first.file.parentFile?.name)
        assertEquals(
            setOf(PREPARED_PDF_FILE_NAME, PREPARED_DIGEST_FILE_NAME),
            first.file.parentFile?.listFiles()?.map(File::getName)?.toSet(),
        )
        assertTrue(first.verifyIntegrity())
        assertTrue(first.file.inputStream().use { inputStream ->
            val header = ByteArray(4)
            inputStream.read(header) == header.size && String(header, Charsets.US_ASCII) == "%PDF"
        })
        MistakePdfPreview.open(first).use { preview ->
            assertEquals(first.file.canonicalPath, preview.sourceFile.canonicalPath)
            assertEquals(first.pageCount, preview.pageCount)
            val bitmap = preview.renderPage(0, 595, 842)
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            bitmap.recycle()
        }
    }

    @Test
    fun cartesianFigureRendersIntoVerifiedPdfPreview() {
        val figure = ContentBlock.Figure(
            id = "figure",
            title = "函数图像",
            alternativeText = "直线 y=x 经过原点",
            schema = FigureSchema.Cartesian(
                xAxis = FigureAxis(-2.0, 2.0, "x"),
                yAxis = FigureAxis(-2.0, 2.0, "y"),
                polylines = listOf(
                    FigurePolyline(
                        id = "line",
                        points = listOf(
                            FigureCoordinate(-2.0, -2.0),
                            FigureCoordinate(2.0, 2.0),
                        ),
                    ),
                ),
                points = listOf(
                    FigurePoint(
                        coordinate = FigureCoordinate(0.0, 0.0),
                        label = "O",
                        style = FigureSeriesStyle.EMPHASIS,
                    ),
                ),
            ),
        )

        val prepared = MistakePdfExporter(context).prepare(eligibleInput(listOf(figure)))

        assertTrue(prepared.verifyIntegrity())
        MistakePdfPreview.open(prepared).use { preview ->
            val bitmap = preview.renderPage(0, 595, 842)
            var nonWhitePixels = 0
            for (y in 100 until 450 step 2) {
                for (x in 40 until 555 step 2) {
                    if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) nonWhitePixels++
                }
            }
            assertTrue("Expected rendered figure pixels", nonWhitePixels > 1_000)
            bitmap.recycle()
        }
    }

    @Test
    fun preparingAnotherExportRemovesPublishedArtifactsOlderThanOneDay() {
        val exporter = MistakePdfExporter(context)
        val stale = exporter.prepare(
            eligibleInput(listOf(ContentBlock.Paragraph("stem", "超过一天的缓存题目"))),
        )
        val staleDirectory = checkNotNull(stale.file.parentFile)
        val staleTimestamp = System.currentTimeMillis() - MistakePdfExportLimits.CACHE_TTL_MILLIS - 1
        assertTrue(staleDirectory.setLastModified(staleTimestamp))

        val current = exporter.prepare(
            eligibleInput(listOf(ContentBlock.Paragraph("stem", "当前需要导出的题目"))),
        )

        assertFalse(staleDirectory.exists())
        assertTrue(current.verifyIntegrity())
    }

    @Test
    fun recoveryTokenReopensOnlyTheExactVerifiedPreparedPdf() {
        val exporter = MistakePdfExporter(context)
        val prepared = exporter.prepare(
            eligibleInput(listOf(ContentBlock.Paragraph("stem", "恢复保存时应使用同一份 PDF"))),
        )
        val restoredToken = checkNotNull(
            PreparedMistakePdfToken.fromPersistedValue(prepared.recoveryToken.toPersistedValue()),
        )

        val recreatedExporter = MistakePdfExporter(context)
        val reopened = checkNotNull(recreatedExporter.reopenVerified(restoredToken))

        assertEquals(prepared.file.canonicalPath, reopened.file.canonicalPath)
        assertEquals(prepared.sha256, reopened.sha256)
        assertEquals(prepared.pageCount, reopened.pageCount)
        assertTrue(reopened.verifyIntegrity())
        assertEquals(
            null,
            recreatedExporter.reopenVerified(restoredToken.copy(pdfSha256 = "0".repeat(64))),
        )
        assertEquals(
            null,
            recreatedExporter.reopenVerified(restoredToken.copy(pageCount = restoredToken.pageCount + 1)),
        )
    }

    @Test
    fun orphanedHalfPublishIsCleanedBeforeAtomicDirectoryPublish() {
        val exportRoot = File(context.cacheDir, MistakePdfExporter.EXPORT_DIRECTORY).apply {
            mkdirs()
        }
        val orphan = File(exportRoot, "$PENDING_DIRECTORY_PREFIX${UUID.randomUUID()}").apply {
            assertTrue(mkdir())
        }
        File(orphan, PREPARED_PDF_FILE_NAME).writeText("partial PDF without digest")
        val input = eligibleInput(listOf(ContentBlock.Paragraph("stem", "恢复后仍可导出")))

        val prepared = MistakePdfExporter(context).prepare(input)

        assertFalse(orphan.exists())
        assertEquals(input.inputSha256, prepared.file.parentFile?.name)
        assertTrue(prepared.verifyIntegrity())
    }

    @Test
    fun incompleteExistingTargetFailsClosedInsteadOfBeingOverwritten() {
        val input = eligibleInput(listOf(ContentBlock.Paragraph("stem", "不得覆盖半成品")))
        val exportRoot = File(context.cacheDir, MistakePdfExporter.EXPORT_DIRECTORY).apply {
            mkdirs()
        }
        val incompleteTarget = File(exportRoot, input.inputSha256).apply { assertTrue(mkdir()) }
        val incompletePdf = File(incompleteTarget, PREPARED_PDF_FILE_NAME).apply {
            writeText("incomplete")
        }

        try {
            MistakePdfExporter(context).prepare(input)
            fail("Expected incomplete target to fail closed")
        } catch (failure: MistakePdfExportException) {
            assertSame(MistakePdfExportFailure.EXISTING_FILE_INTEGRITY_CONFLICT, failure.failure)
            assertEquals("incomplete", incompletePdf.readText())
        } finally {
            incompletePdf.delete()
            incompleteTarget.delete()
        }
    }

    @Test
    fun longDocumentPaginatesAndHardLimitFailsClosed() {
        val exporter = MistakePdfExporter(context)
        val multipage = exporter.prepare(
            eligibleInput(
                listOf(ContentBlock.Paragraph("long", "函数图像分析。".repeat(700))),
            ),
        )
        assertTrue(multipage.pageCount > 1)

        val overLimit = eligibleInput(
            listOf(ContentBlock.Paragraph("too-long", "字\n".repeat(4_000))),
        )
        try {
            exporter.prepare(overLimit)
            fail("Expected page limit failure")
        } catch (failure: MistakePdfExportException) {
            assertSame(MistakePdfExportFailure.PAGE_LIMIT_EXCEEDED, failure.failure)
        }
    }

    @Test
    fun fileProviderSharesOnlyTheDedicatedCacheSubtree() {
        val prepared = MistakePdfExporter(context).prepare(
            eligibleInput(listOf(ContentBlock.Paragraph("stem", "题目"))),
        )

        val allowed = MistakePdfShareUris.uriFor(context, prepared)

        assertEquals("content", allowed.scheme)
        val outside = File(context.cacheDir, "outside.pdf").apply { writeText("not a pdf") }
        try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.mistake-pdf-exports",
                outside,
            )
            fail("Outside cache file must not be exposed")
        } catch (_: IllegalArgumentException) {
            // Expected: XML exposes only cache/mistake_pdf_exports.
        } finally {
            outside.delete()
        }
    }

    @Test
    fun deliveryIntentsUsePrivateProviderAndReadOnlyShareGrant() {
        val prepared = MistakePdfExporter(context).prepare(
            eligibleInput(listOf(ContentBlock.Paragraph("stem", "题目"))),
        )

        val createDocument = MistakePdfDeliveryIntents.createDocument("错题.pdf")

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, createDocument.action)
        assertEquals(MistakePdfDeliveryIntents.MIME_TYPE, createDocument.type)
        assertTrue(createDocument.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
        assertEquals("错题.pdf", createDocument.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(0, createDocument.flags)

        val share = MistakePdfDeliveryIntents.share(context, prepared, "错题.pdf")
        val sharedUri = share.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)

        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals(MistakePdfDeliveryIntents.MIME_TYPE, share.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, share.flags)
        assertEquals(0, share.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, share.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        assertEquals(0, share.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertNotNull(sharedUri)
        assertEquals(sharedUri, share.clipData?.getItemAt(0)?.uri)

        val authority = "${context.packageName}.mistake-pdf-exports"
        val provider = context.packageManager.resolveContentProvider(authority, 0)
        assertNotNull(provider)
        assertFalse(provider!!.exported)
        assertTrue(provider.grantUriPermissions)
    }

    @Test
    fun saveCopiesPreparedPdfThenRechecksDestinationDigest() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val prepared = MistakePdfExporter(context).prepare(
            eligibleInput(listOf(ContentBlock.Paragraph("stem", "保存后仍是同一份题目"))),
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "mistake-export-${UUID.randomUUID()}.pdf")
            put(MediaStore.MediaColumns.MIME_TYPE, MistakePdfDeliveryIntents.MIME_TYPE)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/SmartMistakeBookTests",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = checkNotNull(
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
        )
        try {
            assertSame(
                MistakePdfSaveResult.SAVED,
                MistakePdfDocumentSaver.save(context, prepared, destination),
            )
        } finally {
            context.contentResolver.delete(destination, null, null)
        }
    }

    @Test
    fun saveRefusesToOverwriteItsOwnPreparedShareFile() {
        val prepared = MistakePdfExporter(context).prepare(
            eligibleInput(listOf(ContentBlock.Paragraph("stem", "原文件不能被保存动作覆盖"))),
        )
        val ownShareUri = MistakePdfShareUris.uriFor(context, prepared)

        assertSame(
            MistakePdfSaveResult.DESTINATION_UNAVAILABLE,
            MistakePdfDocumentSaver.save(context, prepared, ownShareUri),
        )
        assertTrue(prepared.verifyIntegrity())
    }

    @Test
    fun batchExportRendersBeyondSingleQuestionPageLimitWithinItsOwnBound() {
        val states = List(60) { index ->
            readyState(
                blocks = listOf(
                    ContentBlock.Paragraph(
                        "stem-$index",
                        "第 ${index + 1} 题 " + "函数单调性与导数符号关系。".repeat(120),
                    ),
                ),
                title = "练习 ${index + 1}",
            )
        }
        val input = (
            MistakePdfBatchEligibility.check(states) as
                MistakePdfBatchEligibilityResult.Eligible
            ).input

        val started = SystemClock.elapsedRealtime()
        val prepared = MistakePdfExporter(context).prepare(input)
        val elapsedMillis = SystemClock.elapsedRealtime() - started
        Log.i(
            "MistakeBatchPerf",
            "pdfQuestions=60 pages=${prepared.pageCount} elapsedMs=$elapsedMillis",
        )

        assertTrue(prepared.verifyIntegrity())
        assertTrue(prepared.pageCount > MistakePdfExportLimits.MAX_SINGLE_PAGES)
        assertTrue(prepared.pageCount <= MistakePdfExportLimits.MAX_BATCH_PAGES)
        assertTrue("Batch PDF took ${elapsedMillis}ms", elapsedMillis < 5_000)
    }

    private fun eligibleInput(blocks: List<ContentBlock>): MistakePdfExportInput {
        val state = readyState(blocks)
        return (MistakePdfEligibility.check(state) as MistakePdfEligibilityResult.Eligible).input
    }

    private fun readyState(
        blocks: List<ContentBlock>,
        title: String = "单道错题导出",
    ): MistakeDetailState.Ready {
        val unique = UUID.randomUUID().toString()
        return MistakeDetailState.Ready(
            detail = MistakeDetail(
                identity = MistakeDetailIdentity(
                    errorBookEntryId = "entry-$unique",
                    problemId = "problem-$unique",
                    problemRevisionId = "revision-$unique",
                    revisionNumber = 1,
                    title = title,
                    subject = "数学",
                ),
                fallbackMarkdown = "fallback must never be used",
                source = MistakeSourceSet.Missing,
            ),
            questionDocument = CapturedQuestionDocument(
                document = QuestionDocument(
                    id = "document-$unique",
                    title = title,
                    blocks = blocks,
                ),
                blockEvidence = blocks.map { block ->
                    QuestionBlockEvidence(
                        blockId = block.id,
                        sourceAssetId = "source-$unique",
                        sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                        writingLayer = WritingLayer.PRINTED,
                        provenance = QuestionBlockProvenance.USER_CORRECTION,
                        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    )
                },
            ),
        )
    }
}
