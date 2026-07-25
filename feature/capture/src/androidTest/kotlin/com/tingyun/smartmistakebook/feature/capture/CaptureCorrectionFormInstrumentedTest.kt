package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CaptureCorrectionFormInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun optionalEditorStillAllowsStudentCorrectionsBeforeAtomicCommit() {
        var subject by mutableStateOf("MATH")
        var title by mutableStateOf("函数单调性")
        var transcription by mutableStateOf("求函数的单调区间。")
        var writingLayer by mutableStateOf(CaptureWritingLayer.PRINTED)
        var committed = false

        composeRule.setContent {
            MaterialTheme {
                CaptureCorrectionForm(
                    subject = subject,
                    title = title,
                    transcription = transcription,
                    writingLayer = writingLayer,
                    recognitionState = CaptureRecognitionState.CANDIDATE_AVAILABLE,
                    recognitionConfidence = 0.9,
                    recognitionBlockCount = 2,
                    candidateKind = CaptureCandidateKind.MODEL_STRUCTURED,
                    isSaving = false,
                    isRetryLocked = false,
                    onSubjectChange = { subject = it },
                    onTitleChange = { title = it },
                    onTranscriptionChange = { transcription = it },
                    onWritingLayerChange = { writingLayer = it },
                    onCommit = { committed = true },
                )
            }
        }

        composeRule.onNodeWithTag("capture_title_input").assertDoesNotExist()
        composeRule.onNodeWithText("题面有误，修改").performClick()
        composeRule.onNodeWithText("混合").performClick()
        composeRule.onNodeWithTag("capture_commit_button").assertIsEnabled().performClick()

        composeRule.runOnIdle { assertTrue(committed) }
    }

    @Test
    fun usableCandidateCommitsWithoutTypingOrASeparateConfirmationToggle() {
        composeRule.setContent {
            MaterialTheme {
                CaptureCorrectionForm(
                    subject = "MATH",
                    title = "函数单调性",
                    transcription = "求函数的单调区间。",
                    writingLayer = CaptureWritingLayer.PRINTED,
                    recognitionState = CaptureRecognitionState.CANDIDATE_AVAILABLE,
                    recognitionConfidence = 0.71,
                    recognitionBlockCount = 2,
                    candidateKind = CaptureCandidateKind.MODEL_STRUCTURED,
                    isSaving = false,
                    isRetryLocked = false,
                    onSubjectChange = {},
                    onTitleChange = {},
                    onTranscriptionChange = {},
                    onWritingLayerChange = {},
                    onCommit = {},
                )
            }
        }

        composeRule.onNodeWithTag("capture_commit_button").assertIsEnabled()
        composeRule.onNodeWithTag("capture_candidate_confirm").assertDoesNotExist()
        composeRule.onNodeWithTag("capture_title_input").assertDoesNotExist()
        composeRule.onNodeWithTag("capture_transcription_input").assertDoesNotExist()
    }

    @Test
    fun usableCandidateKeepsCorrectionsBehindAnOptionalEditAction() {
        composeRule.setContent {
            MaterialTheme {
                CaptureCorrectionForm(
                    subject = "MATH",
                    title = "函数单调性",
                    transcription = "求函数的单调区间。",
                    writingLayer = CaptureWritingLayer.PRINTED,
                    recognitionState = CaptureRecognitionState.CANDIDATE_AVAILABLE,
                    recognitionConfidence = 0.71,
                    recognitionBlockCount = 2,
                    candidateKind = CaptureCandidateKind.MODEL_STRUCTURED,
                    transcriptionEditable = false,
                    isSaving = false,
                    isRetryLocked = false,
                    onSubjectChange = {},
                    onTitleChange = {},
                    onTranscriptionChange = {},
                    onWritingLayerChange = {},
                    onCommit = {},
                )
            }
        }

        composeRule.onNodeWithTag("capture_title_input").assertDoesNotExist()
        composeRule.onNodeWithText("题面有误，修改").performClick()
        composeRule.onNodeWithTag("capture_title_input").assertExists()
    }

    @Test
    fun unusableCandidateNeverAsksTheStudentToSupplyTranscription() {
        composeRule.setContent {
            MaterialTheme {
                CaptureCorrectionForm(
                    subject = "",
                    title = "",
                    transcription = "",
                    writingLayer = CaptureWritingLayer.UNKNOWN,
                    recognitionState = CaptureRecognitionState.NO_TEXT,
                    recognitionConfidence = null,
                    recognitionBlockCount = 0,
                    candidateKind = CaptureCandidateKind.NONE,
                    candidateUsable = false,
                    isSaving = false,
                    isRetryLocked = false,
                    onSubjectChange = {},
                    onTitleChange = {},
                    onTranscriptionChange = {},
                    onWritingLayerChange = {},
                    onCommit = {},
                )
            }
        }

        composeRule.onNodeWithTag("capture_correction_form").assertDoesNotExist()
        composeRule.onNodeWithTag("capture_transcription_input").assertDoesNotExist()
        composeRule.onNodeWithText("补充题面").assertDoesNotExist()
        composeRule.onNodeWithTag("capture_commit_button").assertDoesNotExist()
    }

    @Test
    fun expandedEditorCannotBypassAnUnusableCandidate() {
        composeRule.setContent {
            MaterialTheme {
                CaptureCorrectionForm(
                    subject = "",
                    title = "",
                    transcription = "",
                    writingLayer = CaptureWritingLayer.UNKNOWN,
                    recognitionState = CaptureRecognitionState.NO_TEXT,
                    recognitionConfidence = null,
                    recognitionBlockCount = 0,
                    candidateUsable = false,
                    initialEditorExpanded = true,
                    isSaving = false,
                    isRetryLocked = false,
                    onSubjectChange = {},
                    onTitleChange = {},
                    onTranscriptionChange = {},
                    onWritingLayerChange = {},
                    onCommit = {},
                )
            }
        }

        composeRule.onNodeWithTag("capture_correction_form").assertDoesNotExist()
        composeRule.onNodeWithTag("capture_transcription_input").assertDoesNotExist()
        composeRule.onNodeWithText("补充题面").assertDoesNotExist()
        composeRule.onNodeWithTag("capture_commit_button").assertDoesNotExist()
    }

    @Test
    fun unavailableSourcePreviewKeepsCommitGateClosed() {
        composeRule.setContent {
            MaterialTheme {
                CaptureCorrectionForm(
                    subject = "MATH",
                    title = "函数单调性",
                    transcription = "求函数的单调区间。",
                    writingLayer = CaptureWritingLayer.PRINTED,
                    recognitionState = CaptureRecognitionState.CANDIDATE_AVAILABLE,
                    recognitionConfidence = 0.91,
                    recognitionBlockCount = 2,
                    entryGateOpen = false,
                    entryGateMessage = "必须先成功打开原图，才能核对并保存；请重新选择或拍摄。",
                    isSaving = false,
                    isRetryLocked = false,
                    onSubjectChange = {},
                    onTitleChange = {},
                    onTranscriptionChange = {},
                    onWritingLayerChange = {},
                    onCommit = {},
                )
            }
        }

        composeRule.onNodeWithText("必须先成功打开原图", substring = true).assertExists()
        composeRule.onNodeWithTag("capture_commit_button").assertIsNotEnabled()
    }

    @Test
    fun editedStructuredProjectionUsesStudentFacingCorrectionCopy() {
        composeRule.setContent {
            MaterialTheme {
                CaptureCorrectionForm(
                    subject = "MATH",
                    title = "函数单调性",
                    transcription = "人工修正后的题面",
                    writingLayer = CaptureWritingLayer.PRINTED,
                    recognitionState = CaptureRecognitionState.CANDIDATE_AVAILABLE,
                    recognitionConfidence = 0.91,
                    recognitionBlockCount = 2,
                    candidateKind = CaptureCandidateKind.MODEL_STRUCTURED,
                    structuredProjectionEdited = true,
                    isSaving = false,
                    isRetryLocked = false,
                    onSubjectChange = {},
                    onTitleChange = {},
                    onTranscriptionChange = {},
                    onWritingLayerChange = {},
                    onCommit = {},
                )
            }
        }

        composeRule.onNodeWithText("题面有误，修改").performClick()
        composeRule.onNodeWithText("只改有误的地方", substring = true).assertExists()
        composeRule.onNodeWithText("保存修改并存入错题本").assertExists()
    }

    @Test
    fun tutorIntentSavesAWaitingQuestionWithoutPromisingTeachingOrLibrarySave() {
        composeRule.setContent {
            MaterialTheme {
                CaptureCorrectionForm(
                    subject = "MATH",
                    title = "函数单调性",
                    transcription = "已核对题面",
                    writingLayer = CaptureWritingLayer.PRINTED,
                    recognitionState = CaptureRecognitionState.NO_TEXT,
                    recognitionConfidence = null,
                    recognitionBlockCount = 0,
                    completionIntent = CaptureCompletionIntent.START_TUTORING,
                    isSaving = false,
                    isRetryLocked = false,
                    onSubjectChange = {},
                    onTitleChange = {},
                    onTranscriptionChange = {},
                    onWritingLayerChange = {},
                    onCommit = {},
                )
            }
        }

        composeRule.onNodeWithText("开始讲题").assertExists()
        composeRule.onNodeWithText("不会自动加入错题本", substring = true).assertExists()
    }

    @Test
    fun retryOnlyStateFreezesEditsButKeepsTheCommitReplayAvailable() {
        var commitCalls = 0

        composeRule.setContent {
            MaterialTheme {
                CaptureCorrectionForm(
                    subject = "MATH",
                    title = "函数单调性",
                    transcription = "求函数的单调区间。",
                    writingLayer = CaptureWritingLayer.PRINTED,
                    recognitionState = CaptureRecognitionState.CANDIDATE_AVAILABLE,
                    recognitionConfidence = 0.91,
                    recognitionBlockCount = 2,
                    candidateKind = CaptureCandidateKind.MODEL_STRUCTURED,
                    initialEditorExpanded = true,
                    isSaving = false,
                    isRetryLocked = true,
                    onSubjectChange = {},
                    onTitleChange = {},
                    onTranscriptionChange = {},
                    onWritingLayerChange = {},
                    onCommit = { commitCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("capture_title_input").assertIsNotEnabled()
        composeRule.onNodeWithTag("capture_transcription_input").assertIsNotEnabled()
        composeRule.onNodeWithTag("capture_commit_button").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, commitCalls) }
    }

}
