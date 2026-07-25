package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CaptureModelEgressConsentCardInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstCaptureActionsAreAlreadyInformedAndDoNotAddASecondConfirmation() {
        var cameraClicks = 0
        var photoClicks = 0
        composeRule.setContent {
            MaterialTheme {
                CaptureActions(
                    provider = provider(),
                    entryOrigin = CaptureEntryOrigin.TUTOR,
                    onTakePicture = { cameraClicks += 1 },
                    onPickPhoto = { photoClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText("拍照并整理").performClick()
        composeRule.onNodeWithText("选图并整理").performClick()
        composeRule.onNodeWithText(
            "本次题图、整理后的题目和与本题相关的学习记录会交给我的视觉模型，用于开始讲解；不会发送其他题目。",
        ).assertExists()
        composeRule.onNodeWithText("vision-model-1", substring = true).assertDoesNotExist()
        composeRule.onAllNodesWithTag("capture_model_egress_approve_button").assertCountEquals(0)
        composeRule.runOnIdle {
            assertEquals(1, cameraClicks)
            assertEquals(1, photoClicks)
        }
    }

    @Test
    fun approvalExplainsTheExactRecipientScopePurposeAndExclusions() {
        var approved = false
        composeRule.setContent {
            MaterialTheme {
                CaptureModelEgressConsentCard(
                    provider = provider(),
                    approved = false,
                    onApprove = { approved = true },
                )
            }
        }

        composeRule.onNodeWithText("把这张题图交给模型整理？").assertExists()
        composeRule.onNodeWithText(
            "本次只把当前题图发给我的视觉模型，用于读题和整理；不会发送其他题目或学习记录。",
        ).assertExists()
        composeRule.onNodeWithText("vision-model-1", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("capture_model_egress_approve_button").performClick()
        composeRule.onAllNodesWithTag("capture_model_egress_manual_button").assertCountEquals(0)
        composeRule.runOnIdle { assertTrue(approved) }
    }

    @Test
    fun approvedStateDoesNotAskForDuplicateConfirmation() {
        composeRule.setContent {
            MaterialTheme {
                CaptureModelEgressConsentCard(
                    provider = provider(),
                    approved = true,
                    onApprove = {},
                )
            }
        }

        composeRule.onNodeWithText("本次只发送当前题图").assertExists()
        composeRule.onAllNodesWithTag("capture_model_egress_approve_button").assertCountEquals(0)
        composeRule.onAllNodesWithTag("capture_model_egress_manual_button").assertCountEquals(0)
        composeRule.onNodeWithText("把这张题图交给模型整理？").assertDoesNotExist()
        composeRule.onNodeWithText("允许这一次").assertDoesNotExist()
        composeRule.onNodeWithText("继续整理这道题").assertDoesNotExist()
    }

    @Test
    fun restoredWorkUsesOnePlainContinuationAction() {
        var continued = false
        composeRule.setContent {
            MaterialTheme {
                CaptureModelEgressConsentCard(
                    provider = provider(),
                    approved = false,
                    onApprove = { continued = true },
                    approveActionText = "继续整理这道题",
                )
            }
        }

        composeRule.onNodeWithText("把这张题图交给模型整理？").assertExists()
        composeRule.onAllNodesWithTag("capture_model_egress_approve_button").assertCountEquals(1)
        composeRule.onNodeWithText("本次只发送当前题图").assertDoesNotExist()
        composeRule.onNodeWithText("继续整理这道题").performClick()
        composeRule.onNodeWithText("允许这一次").assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(continued) }
    }

    @Test
    fun changedConfigurationConsentCreatesOneExactSourceRecoveryRequest() {
        val oldProvider = provider()
        val changedProvider = provider().copy(providerConfigurationVersion = "config-v2")
        val page = CaptureSourcePage(
            pageIndex = 0,
            imageUri = "content://capture/exact-source",
            sourceAssetId = "asset-exact",
            sourceAssetSha256 = "a".repeat(64),
            width = 1080,
            height = 1440,
            byteSize = 2048,
        )
        val oldManifest = buildCaptureEgressManifest(
            authorizationId = "old-approval",
            draftId = "draft-exact",
            provider = oldProvider,
            sourcePages = listOf(page),
            approvedAtEpochMillis = 100,
        )
        val original = ModelTaskRequest(
            requestId = "capture-assess:failed-config",
            input = CaptureAssessmentInput(
                draftId = "draft-exact",
                sourceAssetId = page.sourceAssetId,
                origin = CaptureAssessmentOrigin.TUTOR,
                imageWidth = page.width,
                imageHeight = page.height,
            ),
            occurredAtEpochMillis = 100,
            egressManifest = oldManifest,
        )
        val failed = ModelTaskSnapshot(
            taskId = "task-capture-assess-failed-config",
            request = original,
            requestFingerprint = ModelTaskFingerprint.of(original),
            status = ModelTaskStatus.PERMANENT_FAILURE,
            stateVersion = 2,
            stage = ModelTaskStage.PREPARING,
            userMessage = "模型设置需要更新",
            attemptCount = 1,
            provider = oldProvider,
            failure = ModelTaskFailure(
                ModelFailureCode.AUTHENTICATION_FAILED,
                "认证失败",
                retryable = false,
            ),
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 101,
        )
        val approved = mutableStateOf(false)
        var recovered: ModelTaskRequest? = null
        assertTrue(failed.requiresFreshCaptureApproval(changedProvider))
        composeRule.setContent {
            MaterialTheme {
                CaptureModelEgressConsentCard(
                    provider = changedProvider,
                    approved = approved.value,
                    onApprove = {
                        val freshManifest = buildCaptureEgressManifest(
                            authorizationId = "fresh-config-v2-approval",
                            draftId = "draft-exact",
                            provider = changedProvider,
                            sourcePages = listOf(page),
                            approvedAtEpochMillis = 200,
                        )
                        recovered = rebuildCaptureRequestAfterApproval(
                            failed,
                            changedProvider,
                            freshManifest,
                        )
                        approved.value = true
                    },
                )
            }
        }

        composeRule.onNodeWithTag("capture_model_egress_approve_button").performClick()
        composeRule.onAllNodesWithTag("capture_model_egress_approve_button").assertCountEquals(0)
        composeRule.runOnIdle {
            val request = requireNotNull(recovered)
            assertEquals(original.input, request.input)
            assertEquals(original.occurredAtEpochMillis, request.occurredAtEpochMillis)
            assertNotEquals(original.requestId, request.requestId)
            assertEquals("config-v2", request.egressManifest?.providerConfigurationVersion)
        }
    }

    private fun provider() = ProviderCapabilitySnapshot(
        providerId = "provider-1",
        providerDisplayName = "我的视觉模型",
        modelId = "vision-model-1",
        supportedTasks = setOf(
            ModelTaskKind.CAPTURE_ASSESS,
            ModelTaskKind.CAPTURE_PARSE,
            ModelTaskKind.TUTOR_PLAN,
        ),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = true,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = "config-v1",
    )
}
