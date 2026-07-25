package com.tingyun.smartmistakebook.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureCompletionIntentTest {
    @Test
    fun titleSuggestionRemovesTheMandatoryBlankTitleStep() {
        assertEquals("求函数 f(x) 的单调区间", suggestCaptureTitle("  求函数 f(x) 的单调区间  \n第二行"))
        assertEquals("新拍题目", suggestCaptureTitle(" \n\t "))
        assertEquals(24, suggestCaptureTitle("这是一道用于验证标题不会无限延伸而遮挡页面层级的很长题目").length)
    }

    @Test
    fun tutorIntentOnlyPromisesATemporaryQuestionWaitingForRealTeaching() {
        assertEquals(
            "开始讲题",
            captureCommitButtonText(
                completionIntent = CaptureCompletionIntent.START_TUTORING,
                isSaving = false,
                isRetryLocked = false,
                structuredProjectionEdited = false,
            ),
        )
        assertEquals(
            "保存修改并开始讲题",
            captureCommitButtonText(
                completionIntent = CaptureCompletionIntent.START_TUTORING,
                isSaving = false,
                isRetryLocked = false,
                structuredProjectionEdited = true,
            ),
        )
        assertEquals(
            "重试保存待讲题目",
            captureCommitButtonText(
                completionIntent = CaptureCompletionIntent.START_TUTORING,
                isSaving = false,
                isRetryLocked = true,
                structuredProjectionEdited = false,
            ),
        )
    }

    @Test
    fun libraryIntentMakesTheDirectSaveDestinationExplicit() {
        assertEquals(
            "存入错题本",
            captureCommitButtonText(
                completionIntent = CaptureCompletionIntent.SAVE_TO_LIBRARY,
                isSaving = false,
                isRetryLocked = false,
                structuredProjectionEdited = false,
            ),
        )
        assertEquals(
            "保存修改并存入错题本",
            captureCommitButtonText(
                completionIntent = CaptureCompletionIntent.SAVE_TO_LIBRARY,
                isSaving = false,
                isRetryLocked = false,
                structuredProjectionEdited = true,
            ),
        )
        assertEquals(
            "重试保存",
            captureCommitButtonText(
                completionIntent = CaptureCompletionIntent.SAVE_TO_LIBRARY,
                isSaving = false,
                isRetryLocked = true,
                structuredProjectionEdited = false,
            ),
        )
    }
}
