package com.tingyun.smartmistakebook.feature.library

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.domain.BatchImportJob
import com.tingyun.smartmistakebook.core.domain.BatchImportPage
import com.tingyun.smartmistakebook.core.domain.BatchImportPageStatus
import com.tingyun.smartmistakebook.core.domain.BatchImportStatus
import com.tingyun.smartmistakebook.core.domain.SplitImportJobSummary
import com.tingyun.smartmistakebook.core.domain.SplitImportQuestionSummary
import com.tingyun.smartmistakebook.core.domain.SplitImportRepository
import com.tingyun.smartmistakebook.core.domain.SplitImportStatus
import com.tingyun.smartmistakebook.core.domain.SplitRegion
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Entry-surface regressions for the split-review flow (P0-1 in the 2026-09-13
 * QA audit): the review page must render an empty state instead of a bare
 * blank screen, and the batch import page must surface its ready auto-split
 * job as a tappable card.
 */
class SplitReviewEntryInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reviewPageShowsAnEmptyStateInsteadOfABlankScreenWhenNoJobIsActive() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                SplitImportReviewRoute(
                    repository = FakeSplitImportRepository(emptyList()),
                    onOpenDraft = {},
                    onFinished = {},
                )
            }
        }

        composeRule.onNodeWithTag("split_review_empty").assertExists()
        composeRule.onNodeWithText("当前没有待确认的拆分结果").assertExists()
    }

    @Test
    fun reviewPageSelectsTheRoutedJobAndListsItsQuestions() {
        val jobs = listOf(
            jobSummary(jobId = "split:batch-1:0", ordinalBase = 0),
            jobSummary(jobId = "split:batch-1:1", ordinalBase = 0),
        )
        composeRule.setContent {
            SmartMistakeBookTheme {
                SplitImportReviewRoute(
                    repository = FakeSplitImportRepository(jobs),
                    initialJobId = "split:batch-1:1",
                    onOpenDraft = {},
                    onFinished = {},
                )
            }
        }

        composeRule.onNodeWithTag("split_review_count").assertExists()
        composeRule.onNodeWithText("1/1").assertExists()
    }

    @Test
    fun eachQuestionThumbnailShowsItsOwnCroppedAreaRatherThanTheWholePage() {
        // Two-tone page: the left half is red, the right half is blue. The question's
        // region covers only the left half, so its thumbnail must contain no blue.
        val pageFile = writeTwoTonePageFile()
        val job = SplitImportJobSummary(
            jobId = "split:batch-1:0",
            sourceKind = "BATCH",
            pageCount = 1,
            questionCount = 1,
            status = SplitImportStatus.READY,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
            sourceUri = pageFile.toURI().toString(),
            questions = listOf(
                SplitImportQuestionSummary(
                    jobId = "split:batch-1:0",
                    questionOrdinal = 0,
                    pageIndex = 0,
                    region = SplitRegion(left = 0.0, top = 0.0, right = 0.5, bottom = 1.0),
                    selected = true,
                    confirmState = "PENDING",
                    splitDraftId = null,
                ),
            ),
        )
        composeRule.setContent {
            SmartMistakeBookTheme {
                SplitImportReviewRoute(
                    repository = FakeSplitImportRepository(listOf(job)),
                    initialJobId = "split:batch-1:0",
                    onOpenDraft = {},
                    onFinished = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("题目区域")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val thumb = composeRule.onAllNodesWithContentDescription("题目区域")[0]
            .captureToImage()
            .asAndroidBitmap()
        val (red, blue) = countRedAndBluePixels(thumb)

        assertTrue("The cropped thumbnail should show the red left half", red > 0)
        assertTrue(
            "The thumbnail must not show the blue right half (red=$red, blue=$blue)",
            blue * 20 < red,
        )
    }

    @Test
    fun batchImportSurfacesTheReadyAutoSplitJobAsATappableCard() {
        var openedJobId: String? = null
        val job = BatchImportJob(
            jobId = "batch-1",
            status = BatchImportStatus.COMPLETED,
            pages = listOf(
                page(0, BatchImportPageStatus.READY, "draft-1"),
                page(1, BatchImportPageStatus.READY, "draft-2"),
            ),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
            splitReadyJobId = "split:batch-1:1",
        )
        composeRule.setContent {
            SmartMistakeBookTheme {
                BatchImportContent(
                    job = job,
                    message = null,
                    onChoosePhotos = {},
                    onChoosePdf = {},
                    onPause = {},
                    onResume = {},
                    onRetry = { _, _ -> },
                    onSkip = { _, _ -> },
                    onSplitReady = { openedJobId = it },
                    onOpenDraft = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("batch_import_split_ready").assertExists()
        composeRule.onNodeWithTag("batch_import_split_ready_open").performClick()
        assertEquals("split:batch-1:1", openedJobId)
    }
}

private fun page(index: Int, status: BatchImportPageStatus, draftId: String? = null) =
    BatchImportPage(
        pageIndex = index,
        status = status,
        draftId = draftId,
        failureCode = null,
        attemptCount = 1,
        updatedAtEpochMillis = 2,
    )

/** Writes a 320x320 PNG whose left half is red and right half is blue. */
private fun writeTwoTonePageFile(): File {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File(context.cacheDir, "split-review-two-tone-page.png")
    val side = 320
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    for (y in 0 until side) {
        for (x in 0 until side) {
            bitmap.setPixel(x, y, if (x < side / 2) Color.RED else Color.BLUE)
        }
    }
    file.outputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    }
    bitmap.recycle()
    return file
}

/** Counts sampled red and blue pixels; letterbox or transparent pixels are ignored. */
private fun countRedAndBluePixels(bitmap: Bitmap): Pair<Int, Int> {
    var red = 0
    var blue = 0
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) > 200) {
                when {
                    Color.red(pixel) > 150 && Color.green(pixel) < 100 && Color.blue(pixel) < 100 -> red++
                    Color.blue(pixel) > 150 && Color.red(pixel) < 100 && Color.green(pixel) < 100 -> blue++
                }
            }
            x += 4
        }
        y += 4
    }
    return red to blue
}

private fun jobSummary(
    jobId: String,
    ordinalBase: Int,
) = SplitImportJobSummary(
    jobId = jobId,
    sourceKind = "BATCH",
    pageCount = 1,
    questionCount = 1,
    status = SplitImportStatus.READY,
    createdAtEpochMillis = 1,
    updatedAtEpochMillis = 2,
    sourceUri = "content://fixture/source",
    questions = listOf(
        SplitImportQuestionSummary(
            jobId = jobId,
            questionOrdinal = ordinalBase,
            pageIndex = 0,
            region = SplitRegion(left = 0.05, top = 0.05, right = 0.95, bottom = 0.45),
            selected = true,
            confirmState = "PENDING",
            splitDraftId = null,
        ),
    ),
)

private class FakeSplitImportRepository(
    jobs: List<SplitImportJobSummary>,
) : SplitImportRepository {
    private val jobs = MutableStateFlow(jobs)

    override fun observeActiveImports(): Flow<List<SplitImportJobSummary>> = this.jobs

    override suspend fun readImport(jobId: String): SplitImportJobSummary? =
        this.jobs.value.firstOrNull { it.jobId == jobId }

    override suspend fun updateSelection(
        jobId: String,
        questionOrdinal: Int,
        selected: Boolean,
        occurredAtEpochMillis: Long,
    ): Boolean = true

    override suspend fun markConfirmed(
        jobId: String,
        questionOrdinal: Int,
        confirmState: String,
        splitDraftId: String?,
        occurredAtEpochMillis: Long,
    ): Boolean = true

    override suspend fun complete(jobId: String, occurredAtEpochMillis: Long): Boolean = true

    override suspend fun abandon(jobId: String, occurredAtEpochMillis: Long): Boolean = true
}
