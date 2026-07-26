package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureSurfaceKind
import com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot
import com.tingyun.smartmistakebook.core.model.TutorStreamIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapturedTutorSessionUiPolicyTest {
    @Test
    fun temporarySessionNeverClaimsThatTeachingIsReady() {
        assertEquals(
            "临时题目 · 讲完后再决定是否存入",
            tutorSessionStatusLine(isSaved = false),
        )
        assertEquals(
            "已存入错题本",
            tutorSessionStatusLine(isSaved = true),
        )
        assertEquals(
            "本次讲题已结束 · 未存入错题本",
            tutorSessionStatusLine(TutorSessionDisposition.ENDED_WITHOUT_SAVE),
        )
    }

    @Test
    fun saveActionStaysOptionalAndReportsDurableStates() {
        assertEquals(
            "存入错题本",
            tutorSessionSaveLabel(isSaved = false, saveInProgress = false, saveFailed = false),
        )
        assertEquals(
            "保存中",
            tutorSessionSaveLabel(isSaved = false, saveInProgress = true, saveFailed = false),
        )
        assertEquals(
            "重试保存",
            tutorSessionSaveLabel(isSaved = false, saveInProgress = false, saveFailed = true),
        )
        assertEquals(
            "已存入",
            tutorSessionSaveLabel(isSaved = true, saveInProgress = false, saveFailed = false),
        )
    }

    @Test
    fun directPreviewCreatesExposureOnlyAfterSafeMarkdownIsVisible() {
        val key = exposureKey()
        val preparing = activeMessage(snapshot = null)
        val visible = activeMessage(
            snapshot = TutorMarkdownSnapshot(
                stableMarkdown = "完整讲解",
                provisionalMarkdown = "",
            ),
        )

        assertNull(
            transientDirectPreviewExposureKey(
                exposureKey = key,
                explanationMode = TutorExplanationMode.DIRECT,
                activeMessage = preparing,
            ),
        )
        assertEquals(
            key,
            transientDirectPreviewExposureKey(
                exposureKey = key,
                explanationMode = TutorExplanationMode.DIRECT,
                activeMessage = visible,
            ),
        )
        assertEquals("完整讲解", visible.snapshot?.visibleMarkdown)
    }

    @Test
    fun guidedPreviewNeverCreatesUnauthorizedTransientExposure() {
        assertNull(
            transientDirectPreviewExposureKey(
                exposureKey = exposureKey(),
                explanationMode = TutorExplanationMode.GUIDED,
                activeMessage = activeMessage(
                    snapshot = TutorMarkdownSnapshot(
                        stableMarkdown = "引导提示",
                        provisionalMarkdown = "",
                    ),
                ),
            ),
        )
    }

    @Test
    fun invalidTerminalKeepsShownDirectContentAndExactlyOneRetry() {
        val failed = activeMessage(
            snapshot = TutorMarkdownSnapshot(
                stableMarkdown = "已显示的完整讲解",
                provisionalMarkdown = "",
            ),
            phase = TutorActiveStreamPhase.FAILED,
            retryable = true,
        )

        assertEquals("已显示的完整讲解", failed.snapshot?.visibleMarkdown)
        assertEquals(listOf(TutorActiveStreamRecovery.RETRY), failed.recoveryActions)
        assertEquals(
            exposureKey(),
            transientDirectPreviewExposureKey(
                exposureKey = exposureKey(),
                explanationMode = TutorExplanationMode.DIRECT,
                activeMessage = failed,
            ),
        )
    }

    private fun activeMessage(
        snapshot: TutorMarkdownSnapshot?,
        phase: TutorActiveStreamPhase = TutorActiveStreamPhase.STREAMING,
        retryable: Boolean = false,
    ) = TutorActiveStreamMessage(
        studentMessage = "请直接讲解",
        ownerVersion = 7,
        turnVersion = 1,
        modeVersion = 0,
        identity = TutorStreamIdentity(
            requestId = "request-direct",
            ownerVersion = 7,
            turnVersion = 1,
            modeVersion = 0,
        ),
        snapshot = snapshot,
        phase = phase,
        retryable = retryable,
    )

    private fun exposureKey() = TutorAnswerExposureKey(
        sessionId = "session-1",
        questionDocumentId = "document-1",
        revisionNumber = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        surfaceKind = TutorAnswerExposureSurfaceKind.RESPOND_REPLY,
        modelTaskRequestId = "request-direct",
        responseOrdinal = 1,
    )
}
