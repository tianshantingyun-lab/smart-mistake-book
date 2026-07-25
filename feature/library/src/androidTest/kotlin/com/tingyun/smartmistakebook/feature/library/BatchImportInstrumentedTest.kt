package com.tingyun.smartmistakebook.feature.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.BatchImportJob
import com.tingyun.smartmistakebook.core.domain.BatchImportBoundaryStatus
import com.tingyun.smartmistakebook.core.domain.BatchImportOrganizationOffer
import com.tingyun.smartmistakebook.core.domain.BatchImportPage
import com.tingyun.smartmistakebook.core.domain.BatchImportPageStatus
import com.tingyun.smartmistakebook.core.domain.BatchImportStatus
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchImportInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun partialBatchShowsCalmProgressAndKeepsActionsPageSpecific() {
        var openedDraft: String? = null
        var retriedPage: Int? = null
        var skippedPage: Int? = null
        val job = BatchImportJob(
            jobId = "batch-1",
            status = BatchImportStatus.PROCESSING,
            pages = listOf(
                page(0, BatchImportPageStatus.READY, "draft-1"),
                page(1, BatchImportPageStatus.IMPORTING),
                page(2, BatchImportPageStatus.FAILED),
                page(3, BatchImportPageStatus.QUEUED),
            ),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
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
                    onRetry = { _, page -> retriedPage = page },
                    onSkip = { _, page -> skippedPage = page },
                    onOpenDraft = { openedDraft = it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("正在保存 4 页").assertExists()
        composeRule.onNodeWithText("已保存 1 页 · 需重试 1 页 · 剩余 2 页").assertExists()
        composeRule.onNodeWithTag("batch_import_choose_pdf").assertExists()
        composeRule.onNodeWithText(
            "可以离开本页；已保存的题会继续留在待处理题目中。",
        ).assertExists()
        composeRule.captureLibraryQaScreenshot("batch-import-current.png")
        composeRule.onNodeWithTag("batch_import_page_0").performClick()
        composeRule.onNodeWithText("重试").performScrollTo().performClick()
        composeRule.onNodeWithText("跳过").performScrollTo().performClick()

        assertEquals("draft-1", openedDraft)
        assertEquals(2, retriedPage)
        assertEquals(2, skippedPage)
    }

    @Test
    fun organizationConsentUsesPlainLanguage() {
        var approved = false
        val pending = completedJob(
            firstDraftId = "draft-1",
            secondDraftId = "draft-2",
            firstBoundary = BatchImportBoundaryStatus.PENDING,
        )
        val offer = BatchImportOrganizationOffer(
            jobId = pending.jobId,
            pageCount = 2,
            provider = provider(),
        )
        composeRule.setContent {
            SmartMistakeBookTheme {
                BatchImportContent(
                    job = pending,
                    message = null,
                    onChoosePhotos = {},
                    onChoosePdf = {},
                    onPause = {},
                    onResume = {},
                    onRetry = { _, _ -> },
                    onSkip = { _, _ -> },
                    organizationOffer = offer,
                    onApproveOrganization = { approved = true },
                    onOpenDraft = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText(
            "会把这 2 页交给测试模型，只判断前后页面是不是同一道题；不会发送其他题目或学习记录。",
        ).assertExists()
        composeRule.onNodeWithText("同意并整理").performClick()
        assertTrue(approved)
    }

    @Test
    fun confirmedContinuationLooksLikeOneQuestion() {
        val organized = completedJob(
            firstDraftId = "draft-1",
            secondDraftId = "draft-1",
            firstBoundary = BatchImportBoundaryStatus.SAME_QUESTION,
        )
        composeRule.setContent {
            SmartMistakeBookTheme {
                BatchImportContent(
                    job = organized,
                    message = null,
                    onChoosePhotos = {},
                    onChoosePdf = {},
                    onPause = {},
                    onResume = {},
                    onRetry = { _, _ -> },
                    onSkip = { _, _ -> },
                    onOpenDraft = {},
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("1 道题已分好").assertExists()
        composeRule.onNodeWithText("第 2 页 · 接上页").assertExists()
        composeRule.onNodeWithText("已和上一页放在同一道题里").assertExists()
    }

    private fun page(
        index: Int,
        status: BatchImportPageStatus,
        draftId: String? = null,
    ) = BatchImportPage(
        pageIndex = index,
        status = status,
        draftId = draftId,
        failureCode = if (status == BatchImportPageStatus.FAILED) "IMPORT_FAILED" else null,
        attemptCount = if (status == BatchImportPageStatus.QUEUED) 0 else 1,
        updatedAtEpochMillis = 2,
    )

    private fun completedJob(
        firstDraftId: String,
        secondDraftId: String,
        firstBoundary: BatchImportBoundaryStatus,
    ) = BatchImportJob(
        jobId = "batch-organize",
        status = BatchImportStatus.COMPLETED,
        pages = listOf(
            page(0, BatchImportPageStatus.READY, firstDraftId).copy(
                boundaryAfterStatus = firstBoundary,
            ),
            page(1, BatchImportPageStatus.READY, secondDraftId),
        ),
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun provider() = ProviderCapabilitySnapshot(
        providerId = "fixture",
        providerDisplayName = "测试模型",
        modelId = "fixture-v1",
        supportedTasks = setOf(ModelTaskKind.CAPTURE_ASSESS),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
    )
}
