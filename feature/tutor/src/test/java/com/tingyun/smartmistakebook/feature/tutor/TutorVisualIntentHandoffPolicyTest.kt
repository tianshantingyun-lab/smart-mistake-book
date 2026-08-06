package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import com.tingyun.smartmistakebook.core.model.TutorLobbyVisualKind
import com.tingyun.smartmistakebook.core.model.TutorLobbyVisualRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class TutorVisualIntentHandoffPolicyTest {
    @Test
    fun explicitVisualDraftCarriesUserIntentIntoEitherImagePicker() {
        listOf(
            "请画一个受力图说明我接下来拍的题",
            "请用动画展示我接下来上传的题",
            "请给接下来的题做三维展示",
        ).forEach { draft ->
            assertEquals(
                TutorCurrentSessionVisualIntent.USER_EXPLICIT,
                tutorCaptureVisualIntent(studentDraft = draft),
            )
        }
    }

    @Test
    fun ordinaryCaptureStartsWithoutVisualIntent() {
        assertEquals(
            TutorCurrentSessionVisualIntent.NONE,
            tutorCaptureVisualIntent(studentDraft = "这道题怎么做"),
        )
        assertEquals(
            TutorCurrentSessionVisualIntent.NONE,
            tutorCaptureVisualIntent(),
        )
    }

    @Test
    fun persistedExplicitRequestSurvivesAfterTheLobbyDraftIsCleared() {
        val request = TutorLobbyVisualRequest(
            kind = TutorLobbyVisualKind.VISUALIZATION,
            focusMarkdown = "展示当前题的关键关系",
        )

        assertEquals(
            TutorCurrentSessionVisualIntent.USER_EXPLICIT,
            tutorCaptureVisualIntent(explicitVisualRequest = request),
        )
    }

    @Test
    fun consumedOrLateVisualSourceCannotLeakIntoAnotherCapture() {
        val request = TutorLobbyVisualRequest(
            kind = TutorLobbyVisualKind.DIAGRAM,
            focusMarkdown = "展示当前题的关键关系",
        )

        assertEquals(
            TutorCurrentSessionVisualIntent.NONE,
            tutorCaptureVisualIntent(
                explicitVisualRequest = request,
                visualSourceRequestId = "request-a",
                consumedVisualSourceRequestId = "request-a",
            ),
        )
        assertEquals(
            TutorCurrentSessionVisualIntent.USER_EXPLICIT,
            tutorCaptureVisualIntent(
                explicitVisualRequest = request,
                visualSourceRequestId = "request-b",
                consumedVisualSourceRequestId = "request-a",
            ),
        )
    }
}
