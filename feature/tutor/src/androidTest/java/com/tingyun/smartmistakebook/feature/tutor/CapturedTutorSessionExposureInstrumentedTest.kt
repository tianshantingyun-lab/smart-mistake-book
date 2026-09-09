package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.MistakeDetail
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.domain.RecordTutorSolutionExposureCommand
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorConversationReference
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorMemoryPreference
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorRequestedLocalCapability
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapturedTutorSessionExposureInstrumentedTest : CapturedTutorSessionTestBase() {
    @Test
    fun cancelledExposureWriteIsRetriedByTheRestartedCollector() {
        val session = session()
        val modelTasks = ChatModelTaskRepository(
            session = session,
            restoredSucceededMessage = "请直接告诉我这道题的答案",
            restoredSucceededRevealsSolution = true,
        )
        val suspendingInteractions = SuspendingExposureTutorInteractions()
        val retryInteractions = RecordingTutorInteractions()
        val activeInteractions = mutableStateOf<TutorInteractionRepository>(suspendingInteractions)
        composeRule.setContent {
            MaterialTheme {
                ReadyCapturedSession(
                    session = session,
                    clock = { 10_000L },
                    saveInProgress = false,
                    saveError = null,
                    onSave = {},
                    modelTasks = modelTasks,
                    interactions = activeInteractions.value,
                    profile = StudyProfileOverview(),
                    onOpenModelSettings = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            suspendingInteractions.exposureCommands.size == 1
        }
        composeRule.runOnIdle { activeInteractions.value = retryInteractions }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            suspendingInteractions.cancellationCount == 1 &&
                retryInteractions.recordedExposureKeys.size == 1
        }
        composeRule.runOnIdle {
            assertEquals(1, suspendingInteractions.exposureCommands.size)
            assertEquals(1, retryInteractions.exposureCommands.size)
            assertEquals(
                suspendingInteractions.exposureCommands.single().copy(occurredAtEpochMillis = 0),
                retryInteractions.exposureCommands.single().copy(occurredAtEpochMillis = 0),
            )
        }
    }

    @Test
    fun restoredFreeTextAnswerExposureRecordsItsExactTurnOnceWithoutChoiceEvidence() {
        val session = session()
        val modelTasks = ChatModelTaskRepository(
            session = session,
            restoredSucceededMessage = "请直接告诉我这道题的答案",
            restoredSucceededRevealsSolution = true,
            restoredCycleOrdinal = 2,
            restoredTurnOrdinal = 3,
        )
        val interactions = RecordingTutorInteractions()
        val mounted = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                if (mounted.value) {
                    ReadyCapturedSession(
                            session = session,
                            clock = { 10_000L },
                            saveInProgress = false,
                            saveError = null,
                            onSave = {},
                            modelTasks = modelTasks,
                            interactions = interactions,
                            profile = StudyProfileOverview(),
                            onOpenModelSettings = {},
                        )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            interactions.recordedExposureKeys.size == 1 &&
                interactions.responses.value.singleOrNull()?.solutionRevealed == true
        }
        composeRule.runOnIdle {
            val command = interactions.exposureCommands.first()
            assertEquals(session.sessionId, command.sessionId)
            assertEquals(session.questionDocument.document.id, command.questionDocumentId)
            assertEquals(session.draftRevisionNumber, command.revisionNumber)
            assertEquals(2, command.cycleOrdinal)
            assertEquals(3, command.turnOrdinal)
            assertEquals(10_000L, command.occurredAtEpochMillis)
            assertEquals(0, modelTasks.executeRespondCalls)
            assertEquals(null, interactions.responses.value.single().selectedChoiceId)
            assertEquals(null, interactions.responses.value.single().requestedMove)
            mounted.value = false
        }
        composeRule.runOnIdle { mounted.value = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, interactions.recordedExposureKeys.size) }
    }

    @Test
    fun blockedLongTermWritesKeepTheRequestedAnswerVisibleWithoutExposureWrites() {
        val session = session()
        val modelTasks = ChatModelTaskRepository(
            session = session,
            restoredSucceededMessage = "这次不要记录，请直接告诉我答案。",
            restoredSucceededRevealsSolution = true,
            restoredSucceededIntentDecision = TutorIntentDecision(
                intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
                confidence = 1.0,
                explicitActionRequest = true,
                memoryPreference = TutorMemoryPreference.BLOCK_LONG_TERM_WRITES_FOR_SESSION,
                requestedLocalCapability = TutorRequestedLocalCapability.NONE,
            ),
        )
        val interactions = RecordingTutorInteractions()
        var blockNotifications = 0
        composeRule.setContent {
            MaterialTheme {
                ReadyCapturedSession(
                    session = session,
                    clock = { 10_000L },
                    saveInProgress = false,
                    saveError = null,
                    onSave = {},
                    modelTasks = modelTasks,
                    interactions = interactions,
                    profile = StudyProfileOverview(),
                    onLongTermWritesBlocked = { blockNotifications += 1 },
                    onOpenModelSettings = {},
                )
            }
        }

        composeRule.onNodeWithTag("tutor_chat_assistant_1")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, blockNotifications)
            assertTrue(interactions.revealCommands.isEmpty())
            assertTrue(interactions.exposureCommands.isEmpty())
            assertTrue(interactions.recordedExposureKeys.isEmpty())
        }
    }

    @Test
    fun answerExposureWaitsUntilItsExactReplyIsVisibleAndUsesTheVisibleTimeOnce() {
        val session = longSession()
        val modelTasks = ChatModelTaskRepository(
            session = session,
            restoredPendingMessage = "请直接告诉我这道题的答案",
            holdRespondExecution = true,
        )
        val interactions = RecordingTutorInteractions()
        composeRule.setContent {
            MaterialTheme {
                ReadyCapturedSession(
                    session = session,
                    clock = { 10_000L },
                    saveInProgress = false,
                    saveError = null,
                    onSave = {},
                    modelTasks = modelTasks,
                    interactions = interactions,
                    profile = StudyProfileOverview(),
                    onOpenModelSettings = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            modelTasks.executeRespondCalls == 1 &&
                modelTasks.respondTasks.value.singleOrNull()?.status == ModelTaskStatus.RUNNING
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tutor_chat_assistant_1").assertIsDisplayed()
        repeat(3) {
            composeRule.onNodeWithTag("tutor_conversation_list")
                .performTouchInput { swipeDown(durationMillis = 350) }
        }
        composeRule.onNodeWithTag("captured_tutor_save").performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle { modelTasks.publishRestoredSolutionReply() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(
                emptyList<RecordTutorSolutionExposureCommand>(),
                interactions.exposureCommands,
            )
        }

        composeRule.onNodeWithTag("tutor_conversation_list").performScrollToIndex(2)
        composeRule.onNodeWithTag("tutor_chat_assistant_1").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            interactions.recordedExposureKeys.size == 1
        }
        composeRule.runOnIdle {
            val command = interactions.exposureCommands.first()
            assertEquals(10_000L, command.occurredAtEpochMillis)
            assertTrue(
                command.occurredAtEpochMillis >
                    modelTasks.respondTasks.value.single().updatedAtEpochMillis,
            )
        }

        composeRule.onNodeWithTag("captured_tutor_save").performScrollTo()
        composeRule.onNodeWithTag("tutor_conversation_list").performScrollToIndex(2)
        composeRule.onNodeWithTag("tutor_chat_assistant_1").assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, interactions.recordedExposureKeys.size) }
    }

    @Test
    fun longAnswerExposureWaitsForTheReplyBottomAndRecordsOnlyOnce() {
        val session = longSession()
        val modelTasks = ChatModelTaskRepository(
            session = session,
            restoredPendingMessage = "请直接告诉我这道题的答案",
            holdRespondExecution = true,
        )
        val interactions = RecordingTutorInteractions()
        composeRule.setContent {
            MaterialTheme {
                ReadyCapturedSession(
                    session = session,
                    clock = { 10_000L },
                    saveInProgress = false,
                    saveError = null,
                    onSave = {},
                    modelTasks = modelTasks,
                    interactions = interactions,
                    profile = StudyProfileOverview(),
                    onOpenModelSettings = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            modelTasks.executeRespondCalls == 1 &&
                modelTasks.respondTasks.value.singleOrNull()?.status == ModelTaskStatus.RUNNING
        }
        composeRule.onNodeWithTag("tutor_chat_assistant_1").assertIsDisplayed()
        repeat(3) {
            composeRule.onNodeWithTag("tutor_conversation_list")
                .performTouchInput { swipeDown(durationMillis = 350) }
        }
        composeRule.onNodeWithTag("captured_tutor_save").performScrollTo().assertIsDisplayed()

        val ending = "\n\n完整答案到这里结束。"
        val repeatedStep = "逐步推导当前题，检查每一步的条件与结论。\n"
        val longReply = repeatedStep
            .repeat(TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS / repeatedStep.length)
            .take(TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS - ending.length) + ending
        assertTrue(
            longReply.length >=
                TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS - repeatedStep.length,
        )
        composeRule.runOnIdle { modelTasks.publishRestoredSolutionReply(longReply) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(
                emptyList<RecordTutorSolutionExposureCommand>(),
                interactions.exposureCommands,
            )
        }

        composeRule.onNodeWithTag("tutor_conversation_list").performScrollToIndex(2)
        composeRule.onNodeWithTag("tutor_chat_assistant_1").assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(
                emptyList<RecordTutorSolutionExposureCommand>(),
                interactions.exposureCommands,
            )
        }

        composeRule.onNodeWithTag("tutor_chat_assistant_bottom_1")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            interactions.recordedExposureKeys.size == 1
        }

        composeRule.onNodeWithTag("tutor_conversation_list").performScrollToIndex(0)
        composeRule.onNodeWithTag("tutor_conversation_list").performScrollToIndex(2)
        composeRule.onNodeWithTag("tutor_chat_assistant_bottom_1")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, interactions.exposureCommands.size)
            assertEquals(1, interactions.recordedExposureKeys.size)
        }
    }

    @Test
    fun localNoEgressFirstStartNeverShowsAnExternalProviderDisclosure() {
        val session = session()
        val modelTasks = ChatModelTaskRepository(
            session = session,
            includeInitialPlan = false,
        )
        composeRule.setContent {
            MaterialTheme {
                ReadyCapturedSession(
                    session = session,
                    clock = { 10_000L },
                    saveInProgress = false,
                    saveError = null,
                    onSave = {},
                    modelTasks = modelTasks,
                    interactions = RecordingTutorInteractions(),
                    profile = StudyProfileOverview(),
                    onOpenModelSettings = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            modelTasks.planTasks.value.singleOrNull()?.status == ModelTaskStatus.SUCCEEDED
        }
        composeRule.onNodeWithTag("captured_tutor_disclosure").assertDoesNotExist()
        composeRule.onNodeWithTag("tutor_respond_disclosure").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, modelTasks.executePlanCalls)
            assertEquals(null, modelTasks.planRequests.single().egressManifest)
        }
    }

    @Test
    fun restoredPendingReplyAutoResumesAndExecutesItsExactRequestOnce() {
        val session = session()
        val exactMessage = "我不明白为什么要分区间"
        val modelTasks = ChatModelTaskRepository(
            session = session,
            restoredPendingMessage = exactMessage,
        )
        composeRule.setContent {
            MaterialTheme {
                ReadyCapturedSession(
                        session = session,
                        clock = { 10_000L },
                        saveInProgress = false,
                        saveError = null,
                        onSave = {},
                        modelTasks = modelTasks,
                        interactions = RecordingTutorInteractions(),
                        profile = StudyProfileOverview(),
                        onOpenModelSettings = {},
                    )
            }
        }

        composeRule.onNodeWithTag("tutor_chat_reply_progress").assertDoesNotExist()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            modelTasks.respondTasks.value.singleOrNull()?.status == ModelTaskStatus.SUCCEEDED
        }
        composeRule.onNodeWithText("先看导数在临界点两侧的符号。")
            .performScrollTo()
            .assertExists()
        composeRule.runOnIdle {
            assertEquals(1, modelTasks.executeRespondCalls)
            val request = modelTasks.respondRequests.single()
            val input = request.input as TutorRespondInput
            assertEquals("tutor-respond-restored", request.requestId)
            assertEquals(exactMessage, input.studentMessage)
            assertEquals(1, input.cycleOrdinal)
            assertEquals(1, input.turnOrdinal)
        }
        composeRule.onNodeWithTag("tutor_chat_composer").assertExists()
    }

    @Test
    fun recoveredPendingLocalNoEgressPlanResumesWithoutARecoveryDisclosure() {
        val session = session()
        val modelTasks = ChatModelTaskRepository(
            session = session,
            restoredPlanStatus = ModelTaskStatus.WAITING_FOR_MODEL,
        )
        val originalRequest = modelTasks.planTasks.value.single().request
        composeRule.setContent {
            MaterialTheme {
                ReadyCapturedSession(
                    session = session,
                    clock = { 10_000L },
                    saveInProgress = false,
                    saveError = null,
                    onSave = {},
                    modelTasks = modelTasks,
                    interactions = RecordingTutorInteractions(),
                    profile = StudyProfileOverview(),
                    onOpenModelSettings = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            modelTasks.planTasks.value.singleOrNull()?.status == ModelTaskStatus.SUCCEEDED
        }
        composeRule.onNodeWithTag("captured_tutor_disclosure").assertDoesNotExist()
        composeRule.onNodeWithTag("tutor_respond_disclosure").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, modelTasks.executePlanCalls)
            assertEquals(0, modelTasks.executeRespondCalls)
            assertEquals(originalRequest, modelTasks.planRequests.single())
        }
    }

    @Test
    fun retryableReplyRetriesTheExactPersistedRequest() {
        val session = session()
        val modelTasks = ChatModelTaskRepository(
            session = session,
            restoredFailureStatus = ModelTaskStatus.RETRYABLE_FAILURE,
        )
        composeRule.setContent {
            MaterialTheme {
                ReadyCapturedSession(
                    session = session,
                    clock = { 10_000L },
                    saveInProgress = false,
                    saveError = null,
                    onSave = {},
                    modelTasks = modelTasks,
                    interactions = RecordingTutorInteractions(),
                    profile = StudyProfileOverview(),
                    onOpenModelSettings = {},
                )
            }
        }

        composeRule.onNodeWithTag("tutor_chat_retry").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            modelTasks.respondTasks.value.singleOrNull()?.status == ModelTaskStatus.SUCCEEDED
        }
        composeRule.runOnIdle {
            assertEquals(1, modelTasks.executeRespondCalls)
            assertEquals(
                "tutor-respond-restored",
                modelTasks.respondRequests.single().requestId,
            )
        }
    }

    @Test
    fun authenticationFailureOpensModelSettingsAndNeverOffersAConflictingRetry() {
        val session = session()
        var settingsRequests = 0
        val modelTasks = ChatModelTaskRepository(
            session = session,
            restoredFailureStatus = ModelTaskStatus.PERMANENT_FAILURE,
            restoredFailureCode = ModelFailureCode.AUTHENTICATION_FAILED,
            externalProvider = true,
        )
        composeRule.setContent {
            MaterialTheme {
                ReadyCapturedSession(
                    session = session,
                    clock = { 10_000L },
                    saveInProgress = false,
                    saveError = null,
                    onSave = {},
                    modelTasks = modelTasks,
                    interactions = RecordingTutorInteractions(),
                    profile = StudyProfileOverview(),
                    onOpenModelSettings = { settingsRequests += 1 },
                )
            }
        }

        composeRule.onNodeWithText("模型设置需要更新，题目已经保存。")
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithTag("tutor_chat_retry").assertDoesNotExist()
        composeRule.onNodeWithTag("tutor_chat_model_settings").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, settingsRequests)
            assertEquals(0, modelTasks.executeRespondCalls)
        }
    }

    @Test
    fun expiredSendingAuthorizationReturnsToTheExistingScopeDisclosure() {
        val session = session()
        var settingsRequests = 0
        val modelTasks = ChatModelTaskRepository(
            session = session,
            restoredFailureStatus = ModelTaskStatus.PERMANENT_FAILURE,
            restoredFailureCode = ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
            externalProvider = true,
        )
        composeRule.setContent {
            MaterialTheme {
                ReadyCapturedSession(
                    session = session,
                    clock = { 10_000L },
                    saveInProgress = false,
                    saveError = null,
                    onSave = {},
                    modelTasks = modelTasks,
                    interactions = RecordingTutorInteractions(),
                    profile = StudyProfileOverview(),
                    onOpenModelSettings = { settingsRequests += 1 },
                )
            }
        }

        composeRule.onNodeWithText("这次回复没有完成。")
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithTag("tutor_chat_retry").assertDoesNotExist()
        composeRule.onNodeWithTag("tutor_chat_model_settings").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(0, settingsRequests)
            assertEquals(0, modelTasks.executeRespondCalls)
        }
    }

    @Test
    fun restoredByokFailureWithChangedConfigurationRequestsConsentThenContinuesExactAction() {
        val session = session()
        val exactMessage = "  我卡在配方法第二步\n"
        val changedProvider = ProviderCapabilitySnapshot(
            providerId = "configured-provider",
            providerDisplayName = "已更新模型",
            modelId = "tutor-model-v1",
            supportedTasks = setOf(ModelTaskKind.TUTOR_PLAN, ModelTaskKind.TUTOR_RESPOND),
            supportsImageInput = false,
            supportsStructuredOutput = true,
            supportsStreaming = true,
            providerConfigurationVersion = "configuration-v2",
        )
        val modelTasks = ChatModelTaskRepository(
            session = session,
            restoredFailureStatus = ModelTaskStatus.PERMANENT_FAILURE,
            restoredFailureCode = ModelFailureCode.AUTHENTICATION_FAILED,
            restoredFailureMessage = exactMessage,
            restoredRequestedMove = TutorMoveType.CHANGE_REPRESENTATION,
            restoredCycleOrdinal = 2,
            restoredTurnOrdinal = 4,
            externalProvider = true,
            currentCapabilities = changedProvider,
        )
        composeRule.setContent {
            MaterialTheme {
                ReadyCapturedSession(
                    session = session,
                    clock = { 10_000L },
                    saveInProgress = false,
                    saveError = null,
                    onSave = {},
                    modelTasks = modelTasks,
                    interactions = RecordingTutorInteractions(),
                    profile = StudyProfileOverview(),
                    onOpenModelSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("模型设置需要更新，题目已经保存。")
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithTag("tutor_chat_retry").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(0, modelTasks.executeRespondCalls)
        }
    }

    @Test
    fun cancelledReplyNeverOffersAConflictingRetry() {
        val session = session()
        val modelTasks = ChatModelTaskRepository(
            session = session,
            restoredFailureStatus = ModelTaskStatus.CANCELLED,
        )
        composeRule.setContent {
            MaterialTheme {
                ReadyCapturedSession(
                    session = session,
                    clock = { 10_000L },
                    saveInProgress = false,
                    saveError = null,
                    onSave = {},
                    modelTasks = modelTasks,
                    interactions = RecordingTutorInteractions(),
                    profile = StudyProfileOverview(),
                    onOpenModelSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("这次回复没有完成。").performScrollTo().assertExists()
        composeRule.onNodeWithTag("tutor_chat_retry").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, modelTasks.executeRespondCalls) }
    }

    @Test
    fun cachedTeachingRemainsVisibleWhenTheCurrentProviderIsUnavailable() {
        val session = session()
        val modelTasks = ChatModelTaskRepository(
            session = session,
            restoredSucceededMessage = "之前保存的问题",
            currentCapabilities = ProviderCapabilitySnapshot(
                providerId = "unconfigured",
                providerDisplayName = "尚未配置模型",
                modelId = "unconfigured",
                supportedTasks = emptySet(),
                supportsImageInput = false,
                supportsStructuredOutput = false,
                supportsStreaming = false,
                executionLocation = ModelExecutionLocation.UNAVAILABLE,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                ReadyCapturedSession(
                        session = session,
                        clock = { 10_000L },
                        saveInProgress = false,
                        saveError = null,
                        onSave = {},
                        modelTasks = modelTasks,
                        interactions = RecordingTutorInteractions(),
                        profile = StudyProfileOverview(),
                        onOpenModelSettings = {},
                    )
            }
        }

        composeRule.onNodeWithText("先判断导数的正负变化。")
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithText("先看导数在临界点两侧的符号。")
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithText("需要先连接大模型").assertDoesNotExist()
        composeRule.onNodeWithTag("tutor_chat_composer").assertDoesNotExist()
    }

    @Test
    fun capturedEntryKeepsTheComposerVisibleOutsideTheLongConversationList() {
        val longSession = longSession()
        val capturedTasks = ChatModelTaskRepository(longSession)
        composeRule.setContent {
            MaterialTheme {
                ReadyCapturedSession(
                    session = longSession,
                    clock = { 10_000L },
                    saveInProgress = false,
                    saveError = null,
                    onSave = {},
                    modelTasks = capturedTasks,
                    interactions = RecordingTutorInteractions(),
                    profile = StudyProfileOverview(),
                    onOpenModelSettings = {},
                )
            }
        }

        composeRule.onNodeWithTag("tutor_conversation_list").assertExists()
        composeRule.onNodeWithTag("tutor_chat_composer").assertIsDisplayed()
        composeRule.onNodeWithTag("captured_tutor_save").assertExists()
    }

    @Test
    fun savedEntryKeepsTheComposerVisibleOutsideTheLongConversationList() {
        val longSession = longSession()
        val savedTasks = ChatModelTaskRepository(longSession)
        val savedState = MistakeDetailState.Ready(
            detail = MistakeDetail(
                identity = MistakeDetailIdentity(
                    errorBookEntryId = "entry-1",
                    problemId = "problem-1",
                    problemRevisionId = "problem-revision-1",
                    revisionNumber = longSession.draftRevisionNumber,
                    title = longSession.title,
                    subject = longSession.subject,
                ),
                fallbackMarkdown = "已知题面",
                source = MistakeSourceSet.Missing,
                tutorConversation = TutorConversationReference(
                    sessionId = longSession.sessionId,
                    questionRevisionNumber = longSession.draftRevisionNumber,
                ),
            ),
            questionDocument = longSession.questionDocument,
        )
        composeRule.setContent {
            MaterialTheme {
                SavedMistakeTutorContent(
                    state = savedState,
                    modelTasks = savedTasks,
                    interactions = RecordingTutorInteractions(),
                    profile = StudyProfileOverview(),
                    learningMemory = null,
                    onOpenModelSettings = {},
                    clock = { 10_000L },
                )
            }
        }

        composeRule.onNodeWithTag("tutor_conversation_list").assertExists()
        composeRule.onNodeWithTag("tutor_chat_composer").assertIsDisplayed()
        composeRule.onNodeWithText("已存入错题本 · 再次打开会接着上次讲题").assertExists()
    }

    @Test
    fun restartingAfterRecreationCarriesExactStudentWordsWithStableRequestIdentity() {
        val session = session()
        val exactMessage = "  我卡在配方法第二步\n"
        val modelTasks = RestartCycleModelTaskRepository(session, exactMessage)
        val interactions = FixedTutorInteractions(
            (1..TutorPlanInput.MAX_TURNS).map { turnOrdinal ->
                tutorResponse("choice-2").copy(
                    turnOrdinal = turnOrdinal,
                    requestedMove = TutorMoveType.CHANGE_REPRESENTATION,
                    submittedAtEpochMillis = 200L + turnOrdinal,
                    updatedAtEpochMillis = 200L + turnOrdinal,
                    choiceSubmittedAtEpochMillis = 200L + turnOrdinal,
                )
            },
        )
        val mounted = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                if (mounted.value) {
                    ReadyCapturedSession(
                        session = session,
                        clock = { 10_000L },
                        saveInProgress = false,
                        saveError = null,
                        onSave = {},
                        modelTasks = modelTasks,
                        interactions = interactions,
                        profile = StudyProfileOverview(),
                        onOpenModelSettings = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("captured_tutor_restart_cycle")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { modelTasks.planRequests.size == 1 }
        composeRule.runOnIdle { mounted.value = false }
        composeRule.runOnIdle { mounted.value = true }
        composeRule.onNodeWithTag("captured_tutor_restart_cycle")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { modelTasks.planRequests.size == 2 }

        composeRule.runOnIdle {
            val first = modelTasks.planRequests[0]
            val restored = modelTasks.planRequests[1]
            val firstInput = first.input as TutorPlanInput
            assertEquals(listOf(exactMessage), firstInput.priorCycleStudentMessages)
            assertEquals(first.requestId, restored.requestId)
            assertEquals(firstInput, restored.input)
            assertEquals(2, firstInput.cycleOrdinal)
        }
    }

    @Test
    fun conversationFrameFollowsTheTailUntilTheStudentScrollsUpAndSendForcesItBack() {
        val itemCount = mutableIntStateOf(30)
        val forceFollowToken = mutableStateOf<String?>(null)
        lateinit var listState: LazyListState
        composeRule.setContent {
            MaterialTheme {
                val state = rememberLazyListState()
                SideEffect { listState = state }
                TutorConversationFrame(
                    header = {},
                    autoScrollVersion = itemCount.intValue,
                    forceFollowToken = forceFollowToken.value,
                    listState = state,
                    composer = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("frame_test_composer"),
                        ) {
                            Text("固定输入框")
                        }
                    },
                ) {
                    items(itemCount.intValue, key = { it }) { index ->
                        Text(
                            text = "会话项 $index",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .testTag("frame_test_item_$index"),
                        )
                    }
                }
            }
        }

        fun isAtTail(): Boolean {
            val layout = listState.layoutInfo
            return layout.totalItemsCount > 0 &&
                layout.visibleItemsInfo.lastOrNull()?.index == layout.totalItemsCount - 1
        }

        composeRule.waitUntil(timeoutMillis = 5_000, condition = ::isAtTail)
        composeRule.onNodeWithTag("frame_test_composer").assertIsDisplayed()
        composeRule.runOnIdle { itemCount.intValue = 31 }
        composeRule.waitUntil(timeoutMillis = 5_000, condition = ::isAtTail)

        repeat(2) {
            composeRule.onNodeWithTag("tutor_conversation_list")
                .performTouchInput { swipeDown(durationMillis = 350) }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { !isAtTail() }
        composeRule.waitForIdle()
        val firstVisibleBeforeUpdate = listState.firstVisibleItemIndex
        val composerBoundsBefore = composeRule.onNodeWithTag("frame_test_composer")
            .fetchSemanticsNode().boundsInRoot

        composeRule.runOnIdle { itemCount.intValue = 32 }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(firstVisibleBeforeUpdate, listState.firstVisibleItemIndex)
        }
        composeRule.onNodeWithTag("frame_test_composer").assertIsDisplayed()
        val composerBoundsAfter = composeRule.onNodeWithTag("frame_test_composer")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(composerBoundsBefore, composerBoundsAfter)

        composeRule.runOnIdle {
            itemCount.intValue = 33
            forceFollowToken.value = "student-send-1"
        }
        composeRule.waitUntil(timeoutMillis = 5_000, condition = ::isAtTail)
    }
}
