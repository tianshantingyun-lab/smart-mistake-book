package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tingyun.smartmistakebook.core.model.CaptureAssessment
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentAction
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentIssue
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentIssueCode
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentSeverity
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CaptureModelTaskCardInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun demoProviderRoutesToModelSettingsWithoutManualEntry() {
        var settingsOpened = false
        composeRule.setContent {
            MaterialTheme {
                CaptureModelTaskCard(
                    snapshot = succeededDemoSnapshot(),
                    onRetry = {},
                    onOpenModelSettings = { settingsOpened = true },
                )
            }
        }

        composeRule.onNodeWithText("需要连接模型").assertExists()
        composeRule.onNodeWithTag("capture_manual_entry_button").assertDoesNotExist()
        composeRule.onNodeWithTag("capture_model_settings_button").performClick()
        composeRule.runOnIdle { assertTrue(settingsOpened) }
    }

    @Test
    fun missingModelShowsOnlySettingsRecovery() {
        var settingsOpened = false
        composeRule.setContent {
            MaterialTheme {
                CaptureModelTaskCard(
                    snapshot = failureSnapshot(ModelFailureCode.MODEL_NOT_CONFIGURED),
                    onRetry = {},
                    onOpenModelSettings = { settingsOpened = true },
                )
            }
        }

        composeRule.onNodeWithTag("capture_manual_entry_button").assertDoesNotExist()
        composeRule.onNodeWithTag("capture_model_retry_button").assertDoesNotExist()
        composeRule.onNodeWithTag("capture_model_egress_consent_card").assertDoesNotExist()
        composeRule.onNodeWithTag("capture_model_egress_approve_button").assertDoesNotExist()
        composeRule.onNodeWithText("继续整理这道题").assertDoesNotExist()
        composeRule.onNodeWithText("本次只发送当前题图").assertDoesNotExist()
        composeRule.onNodeWithTag("capture_model_settings_button").performClick()
        composeRule.runOnIdle { assertTrue(settingsOpened) }
    }

    @Test
    fun temporaryParseFailureInvokesOnlyTheParseRetry() {
        var assessmentRetried = false
        var parseRetried = false
        composeRule.setContent {
            MaterialTheme {
                CaptureModelTaskCard(
                    snapshot = failureSnapshot(ModelFailureCode.NETWORK_UNAVAILABLE),
                    parseSnapshot = failureSnapshot(ModelFailureCode.TIMEOUT).copy(taskId = "parse"),
                    onRetry = { assessmentRetried = true },
                    onRetryParse = { parseRetried = true },
                    onOpenModelSettings = {},
                )
            }
        }

        composeRule.onNodeWithTag("capture_manual_entry_button").assertDoesNotExist()
        composeRule.onNodeWithTag("capture_model_retry_button").performClick()
        composeRule.runOnIdle {
            assertTrue(parseRetried)
            assertTrue(!assessmentRetried)
        }
    }

    @Test
    fun cancelledTaskOffersRestartWithoutPushingManualEntry() {
        var retried = false
        composeRule.setContent {
            MaterialTheme {
                CaptureModelTaskCard(
                    snapshot = cancelledSnapshot(),
                    onRetry = { retried = true },
                    onOpenModelSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("这道题暂时没准备好").assertExists()
        composeRule.onNodeWithText("原图已经保存", substring = true).assertExists()
        composeRule.onNodeWithTag("capture_manual_entry_button").assertDoesNotExist()
        composeRule.onNodeWithTag("capture_model_retry_button").performClick()
        composeRule.runOnIdle { assertTrue(retried) }
    }

    @Test
    fun incompleteQuestionOnlyOffersAppendWithoutDiscardingSavedPages() {
        var addPageRequested = false
        val snapshot = succeededDemoSnapshot().copy(
            output = CaptureAssessmentOutput(
                CaptureAssessment(
                    decision = CaptureAssessmentDecision.NEED_MORE_IMAGE,
                    issues = listOf(
                        CaptureAssessmentIssue(
                            code = CaptureAssessmentIssueCode.KEY_TEXT_UNREADABLE,
                            severity = CaptureAssessmentSeverity.BLOCKING,
                            message = "题干延续到了下一页",
                        ),
                    ),
                    suggestedActions = listOf(CaptureAssessmentAction.ADD_IMAGE),
                    modelVersion = "demo/capture-v1",
                ),
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                CaptureModelTaskCard(
                    snapshot = snapshot,
                    onRetry = {},
                    onOpenModelSettings = {},
                    onAddPage = { addPageRequested = true },
                )
            }
        }

        composeRule.onNodeWithText("已保存的页面不会丢失", substring = true).assertExists()
        composeRule.onNodeWithTag("capture_manual_entry_button").assertDoesNotExist()
        composeRule.onNodeWithTag("capture_model_add_page_button").performClick()
        composeRule.runOnIdle {
            assertTrue(addPageRequested)
        }
    }

    @Test
    fun multipleQuestionsAreOrganizedWithoutAskingForManualCropping() {
        val snapshot = succeededDemoSnapshot().copy(
            provider = succeededDemoSnapshot().provider?.copy(isDemo = false),
            output = CaptureAssessmentOutput(
                CaptureAssessment(
                    decision = CaptureAssessmentDecision.SPLIT,
                    issues = listOf(
                        CaptureAssessmentIssue(
                            code = CaptureAssessmentIssueCode.MULTIPLE_QUESTIONS,
                            severity = CaptureAssessmentSeverity.BLOCKING,
                            message = "画面中有多道独立题目",
                        ),
                    ),
                    suggestedActions = emptyList(),
                    questionRegions = listOf(
                        NormalizedSourceRegion(0.05, 0.05, 0.95, 0.45),
                        NormalizedSourceRegion(0.05, 0.55, 0.95, 0.95),
                    ),
                    modelVersion = "demo/capture-v1",
                ),
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                CaptureModelTaskCard(
                    snapshot = snapshot,
                    onRetry = {},
                    onOpenModelSettings = {},
                    splitInProgress = true,
                )
            }
        }

        composeRule.onNodeWithText("分别整理", substring = true).assertExists()
        composeRule.onNodeWithTag("capture_model_retake_button").assertDoesNotExist()
        composeRule.onNodeWithTag("capture_manual_entry_button").assertDoesNotExist()
    }

    private fun succeededDemoSnapshot(): ModelTaskSnapshot {
        val request = request()
        return ModelTaskSnapshot(
            taskId = "task-1",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = ModelTaskStatus.SUCCEEDED,
            stateVersion = 4,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "图片理解已完成",
            attemptCount = 1,
            provider = ProviderCapabilitySnapshot(
                providerId = "demo",
                providerDisplayName = "演示模型",
                modelId = "capture-demo-v1",
                supportedTasks = setOf(ModelTaskKind.CAPTURE_ASSESS),
                supportsImageInput = true,
                supportsStructuredOutput = true,
                supportsStreaming = true,
                isDemo = true,
            ),
            output = CaptureAssessmentOutput(
                CaptureAssessment(
                    decision = CaptureAssessmentDecision.PASS,
                    issues = emptyList(),
                    suggestedActions = emptyList(),
                    modelVersion = "demo/capture-v1",
                ),
            ),
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 200,
        )
    }

    private fun failureSnapshot(code: ModelFailureCode): ModelTaskSnapshot {
        val request = request()
        return ModelTaskSnapshot(
            taskId = "task-1",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = ModelTaskStatus.RETRYABLE_FAILURE,
            stateVersion = 2,
            stage = ModelTaskStage.PREPARING,
            userMessage = "配置可用的模型后会从这里继续",
            attemptCount = 0,
            failure = ModelTaskFailure(
                code = code,
                message = "这次没有完成",
                retryable = true,
            ),
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 200,
        )
    }

    private fun cancelledSnapshot(): ModelTaskSnapshot {
        val request = request()
        return ModelTaskSnapshot(
            taskId = "task-cancelled",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = ModelTaskStatus.CANCELLED,
            stateVersion = 3,
            stage = ModelTaskStage.WAITING,
            userMessage = "任务已结束",
            attemptCount = 0,
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 200,
        )
    }

    private fun request() = ModelTaskRequest(
        requestId = "capture-assess:ui-test",
        input = CaptureAssessmentInput(
            draftId = "draft-ui-test",
            sourceAssetId = "asset-ui-test",
            origin = CaptureAssessmentOrigin.LIBRARY,
            imageWidth = 1080,
            imageHeight = 1440,
        ),
        occurredAtEpochMillis = 100,
    )
}
