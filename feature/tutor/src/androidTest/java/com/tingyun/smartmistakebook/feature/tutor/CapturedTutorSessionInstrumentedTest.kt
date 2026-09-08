package com.tingyun.smartmistakebook.feature.tutor

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.AppendTutorAssistantMessageCommand
import com.tingyun.smartmistakebook.core.domain.AppendTutorStudentMessageCommand
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ClearTutorConversationDraftCommand
import com.tingyun.smartmistakebook.core.domain.DeleteTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.SaveTutorConversationDraftCommand
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.PauseTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetail
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorSolutionExposureCommand
import com.tingyun.smartmistakebook.core.domain.RevealTutorSolutionCommand
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyQuestionMemory
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureSurfaceKind
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorConversationReference
import com.tingyun.smartmistakebook.core.domain.TutorConversation
import com.tingyun.smartmistakebook.core.domain.TutorConversationAnchorKind
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorConversationSnapshot
import com.tingyun.smartmistakebook.core.domain.TutorConversationStatus
import com.tingyun.smartmistakebook.core.domain.TutorMessage
import com.tingyun.smartmistakebook.core.domain.TutorMessageRole
import com.tingyun.smartmistakebook.core.domain.TutorMessageStatus
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.domain.UpdateTutorMessageStatusCommand
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoice
import com.tingyun.smartmistakebook.core.model.TutorConceptMapScene
import com.tingyun.smartmistakebook.core.model.TutorConceptRelation
import com.tingyun.smartmistakebook.core.model.TutorFormulaDerivationScene
import com.tingyun.smartmistakebook.core.model.TutorFormulaDerivationStep
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorMemoryPreference
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorProcessStage
import com.tingyun.smartmistakebook.core.model.TutorProcessTimelineScene
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorSceneEmphasis
import com.tingyun.smartmistakebook.core.model.TutorSceneStep
import com.tingyun.smartmistakebook.core.model.TutorStepFlowScene
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorRequestedLocalCapability
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.TutorVisualSceneRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@RunWith(AndroidJUnit4::class)
class CapturedTutorSessionInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tutorEmptyStateOffersRealConversationCaptureAndExistingQuestionEntry() {
        var captureRequests = 0
        var existingQuestionRequests = 0
        var executedRequest: ModelTaskRequest? = null
        val modelTasks = object : ModelTaskRepository {
            override suspend fun capabilities() = ProviderCapabilitySnapshot(
                providerId = "local-test",
                providerDisplayName = "本地测试模型",
                modelId = "local-test-model",
                supportedTasks = setOf(ModelTaskKind.TUTOR_LOBBY),
                supportsImageInput = false,
                supportsStructuredOutput = true,
                supportsStreaming = false,
                executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
                providerConfigurationVersion = "local-test-v1",
            )

            override fun observe(requestId: String) = flowOf<ModelTaskSnapshot?>(null)

            override fun observeBySubject(
                subjectId: String,
                kind: ModelTaskKind,
            ) = flowOf(emptyList<ModelTaskSnapshot>())

            override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow {
                executedRequest = request
            }
        }
        composeRule.setContent {
            MaterialTheme {
                TutorLobbyRoute(
                    onCapture = { captureRequests += 1 },
                    onChooseExisting = { existingQuestionRequests += 1 },
                    onOpenCapabilitySettings = {},
                    onOpenMistakeNotebook = {},
                    onOpenProfile = {},
                    onOpenHistory = {},
                    conversations = emptyConversations(),
                    modelTasks = modelTasks,
                    catalogEntries = emptyList(),
                    profile = StudyProfileOverview(),
                )
            }
        }

        composeRule.onNodeWithTag("tutor_empty_state").assertExists()
        composeRule.onNodeWithTag("tutor_history_button").assertExists()
        composeRule.onNodeWithTag("tutor_capture_shortcut").performClick()
        composeRule.onNodeWithTag("tutor_upload_button").assertDoesNotExist()
        composeRule.onNodeWithTag("tutor_choose_existing_button").performClick()
        composeRule.onNodeWithTag("tutor_draft_input").performTextInput("我想问一下这一步")
        composeRule.onNodeWithTag("tutor_send_button").performClick()
        composeRule.runOnIdle {
            assertEquals(1, captureRequests)
            assertEquals(1, existingQuestionRequests)
            assertEquals(
                "我想问一下这一步",
                (executedRequest?.input as? TutorLobbyInput)?.studentMessage,
            )
            assertTrue(executedRequest?.egressManifest == null)
        }
    }

    @Test
    fun temporarySessionWaitsForRealTeachingAndOffersAnExplicitOptionalSave() {
        var saveRequests = 0
        var endRequests = 0
        composeRule.setContent {
            MaterialTheme {
                Column {
                    ReadyCapturedSession(
                        session = session(),
                        clock = { 10_000L },
                        saveInProgress = false,
                        saveError = null,
                        onSave = { saveRequests += 1 },
                        onRequestEnd = { endRequests += 1 },
                        modelTasks = unavailableModelTasks(),
                        interactions = emptyInteractions(),
                        profile = StudyProfileOverview(),
                        onOpenModelSettings = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription(
            "本地数据状态：临时题目 · 讲完后再决定是否存入",
        ).assertExists()
        composeRule.onNodeWithText("需要先连接大模型").assertExists()
        composeRule.onNodeWithText("题目已经保存", substring = true).assertExists()
        composeRule.onNodeWithTag("captured_tutor_save").performClick()
        composeRule.onNodeWithTag("captured_tutor_end_without_save").performClick()

        composeRule.runOnIdle {
            assertEquals(1, saveRequests)
            assertEquals(1, endRequests)
        }
    }

    @Test
    fun endedSessionShowsTerminalStateAndNoLongerOffersMutations() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    ReadyCapturedSession(
                        session = session().copy(isEndedWithoutSave = true),
                        clock = { 10_000L },
                        saveInProgress = false,
                        saveError = null,
                        onSave = {},
                        modelTasks = unavailableModelTasks(),
                        interactions = emptyInteractions(),
                        profile = StudyProfileOverview(),
                        onOpenModelSettings = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription(
            "本地数据状态：本次讲题已结束 · 未存入错题本",
        ).assertExists()
        composeRule.onNodeWithText("不会生成错题", substring = true).assertExists()
        composeRule.onNodeWithTag("captured_tutor_save").assertDoesNotExist()
        composeRule.onNodeWithTag("captured_tutor_end_without_save").assertDoesNotExist()
    }

    @Test
    fun generatedTurnKeepsAnswerHiddenAndUsesContextualNextMoves() {
        val response = mutableStateOf<TutorTurnResponse?>(null)
        composeRule.setContent {
            MaterialTheme {
                RootPageColumn {
                    TutorTurnContent(
                        output = tutorOutput(),
                        response = response.value,
                        onSubmitChoice = { choiceId ->
                            response.value = tutorResponse(choiceId)
                        },
                        onRevealSolution = {
                            response.value = response.value?.copy(solutionRevealed = true)
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithText("完整主解法内容").assertDoesNotExist()
        composeRule.onNodeWithTag("captured_tutor_submit_choice").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("选择并提交：先减后增").assertExists()
        composeRule.onNodeWithTag("captured_tutor_choice_choice-2").performScrollTo().performClick()
        composeRule.onNodeWithText("你把正负关系反过来了。").performScrollTo().assertExists()
        composeRule.onNodeWithText("完整主解法内容").assertDoesNotExist()
        composeRule.onNodeWithText("查看完整讲解").performScrollTo().performClick()
        composeRule.onNodeWithText("完整主解法内容").performScrollTo().assertExists()
        composeRule.onNodeWithText("符号表替代解法内容").performScrollTo().assertExists()
        composeRule.runOnIdle {
            assertEquals("先减后增", response.value?.selectedChoiceMarkdown)
        }
    }

    @Test
    fun generatedTurnDoubleTapSubmitsTheChoiceOnlyOnceWhileFeedbackIsPending() {
        val submittedChoiceIds = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                RootPageColumn {
                    TutorTurnContent(
                        output = tutorOutput(),
                        onSubmitChoice = submittedChoiceIds::add,
                    )
                }
            }
        }

        composeRule.onNodeWithTag("captured_tutor_choice_choice-2")
            .performScrollTo()
            .performTouchInput { doubleClick() }

        composeRule.runOnIdle {
            assertEquals(listOf("choice-2"), submittedChoiceIds)
        }
        composeRule.onNodeWithTag("captured_tutor_choice_choice-2").assertIsNotEnabled()
        composeRule.onNodeWithTag("captured_tutor_submit_choice").assertDoesNotExist()
    }

    @Test
    fun pendingChoiceDoesNotRemainLockedAfterSavedStateRestoration() {
        val submittedChoiceIds = mutableListOf<String>()
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            MaterialTheme {
                RootPageColumn {
                    TutorTurnContent(
                        output = tutorOutput(),
                        onSubmitChoice = submittedChoiceIds::add,
                    )
                }
            }
        }

        composeRule.onNodeWithTag("captured_tutor_choice_choice-2")
            .performScrollTo()
            .performClick()
            .assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(listOf("choice-2"), submittedChoiceIds) }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("captured_tutor_choice_choice-2")
            .performScrollTo()
            .assertIsEnabled()
    }

    @Test
    fun pendingChoiceStaysLockedAfterTheWriteReturnsUntilTheResponseFlowEmits() {
        val persistedResponse = MutableStateFlow<TutorTurnResponse?>(null)
        val interactionBusy = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                RootPageColumn {
                    TutorTurnContent(
                        output = tutorOutput(),
                        response = persistedResponse.collectAsState().value,
                        interactionBusy = interactionBusy.value,
                        onSubmitChoice = { interactionBusy.value = true },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("captured_tutor_choice_choice-2")
            .performScrollTo()
            .performClick()
            .assertIsNotEnabled()

        // The repository call has returned, but Room's observed row has not arrived yet.
        composeRule.runOnIdle { interactionBusy.value = false }
        composeRule.onNodeWithTag("captured_tutor_choice_choice-2")
            .performScrollTo()
            .assertIsNotEnabled()

        composeRule.runOnIdle { persistedResponse.value = tutorResponse("choice-2") }
        composeRule.onNodeWithText("你把正负关系反过来了。")
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithTag("captured_tutor_choice_choice-2").assertIsNotEnabled()
    }

    @Test
    fun generatedTurnKeepsTheUncertaintyEscapeSeparateFromGradedChoices() {
        var submittedChoiceId: String? = null
        var hintRequests = 0
        composeRule.setContent {
            MaterialTheme {
                RootPageColumn {
                    TutorTurnContent(
                        output = tutorOutput(),
                        onSubmitChoice = { submittedChoiceId = it },
                        onRequestHint = { hintRequests += 1 },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("captured_tutor_request_hint")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, hintRequests)
            assertEquals(null, submittedChoiceId)
        }
    }

    @Test
    fun generatedTurnOffersAContextualMoveAfterTheChoiceIsPersisted() {
        var continued: TutorMoveType? = null
        composeRule.setContent {
            MaterialTheme {
                RootPageColumn {
                    TutorTurnContent(
                        output = tutorOutput(),
                        response = tutorResponse("choice-2"),
                        onContinue = { continued = it },
                    )
                }
            }
        }

        composeRule.onNodeWithText("换一种思路").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(TutorMoveType.CHANGE_REPRESENTATION, continued)
        }
    }

    @Test
    fun explanationOnlyTurnDoesNotInventAChoiceAndKeepsDirectTeachingActions() {
        val response = mutableStateOf<TutorTurnResponse?>(null)
        var requestedMove: TutorMoveType? = null
        composeRule.setContent {
            MaterialTheme {
                RootPageColumn {
                    TutorTurnContent(
                        output = tutorOutput().copy(
                            plan = tutorOutput().plan.copy(diagnosticItem = null),
                        ),
                        response = response.value,
                        onContinue = { move ->
                            requestedMove = move
                            response.value = actionResponse(requestedMove = move)
                        },
                        onRevealSolution = {
                            response.value = response.value
                                ?.copy(solutionRevealed = true, updatedAtEpochMillis = 1_100)
                                ?: actionResponse(solutionRevealed = true)
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("captured_tutor_submit_choice").assertDoesNotExist()
        composeRule.onNodeWithTag("captured_tutor_choice_choice-1").assertDoesNotExist()
        composeRule.onNodeWithText("符号表替代解法内容").assertDoesNotExist()
        composeRule.onNodeWithTag("captured_tutor_show_alternate").performScrollTo().performClick()
        composeRule.onNodeWithText("符号表替代解法内容").performScrollTo().assertExists()
        composeRule.onNodeWithText("完整主解法内容").assertDoesNotExist()
        composeRule.onNodeWithTag("captured_tutor_reveal_without_choice").performScrollTo().performClick()
        composeRule.onNodeWithText("完整主解法内容").performScrollTo().assertExists()
        composeRule.runOnIdle {
            assertEquals(TutorMoveType.CHANGE_REPRESENTATION, requestedMove)
            assertEquals(true, response.value?.solutionRevealed)
            assertEquals(null, response.value?.selectedChoiceId)
        }
    }

    @Test
    fun visualSceneAppearsAfterARealCurrentQuestionChoiceAndNeverBeforeIt() {
        val response = mutableStateOf<TutorTurnResponse?>(null)
        val output = tutorOutput().copy(
            plan = tutorOutput().plan.copy(
                visualScene = TutorStepFlowScene(
                    sceneId = "scene-1",
                    title = "从条件走到结论",
                    steps = listOf(
                        TutorSceneStep(
                            stepId = "scene-1-step-1",
                            label = "确定范围",
                            bodyMarkdown = "先确定函数的定义域。",
                        ),
                        TutorSceneStep(
                            stepId = "scene-1-step-2",
                            label = "判断符号",
                            bodyMarkdown = "再判断导数正负。",
                            emphasis = TutorSceneEmphasis.KEY,
                        ),
                    ),
                ),
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                RootPageColumn {
                    TutorTurnContent(output = output, response = response.value)
                }
            }
        }

        composeRule.onNodeWithText("从条件走到结论").assertDoesNotExist()
        composeRule.runOnIdle { response.value = tutorResponse("choice-1") }
        if (TutorVisualIsolation.STRUCTURED_SCENE_ISOLATED) {
            // D-001 2026-09-06 增补：结构化场景渲染已隔离，TutorTurnContent 不再渲染
            // visualScene。本测试在隔离期验证"确实不渲染"，翻转 STRUCTURED_SCENE_ISOLATED
            // 后自动恢复下面的原断言（场景在真实选择后出现）。
            composeRule.onNodeWithText("从条件走到结论").assertDoesNotExist()
            composeRule.onNodeWithText("再判断导数正负。").assertDoesNotExist()
            return
        }
        composeRule.onNodeWithText("从条件走到结论").performScrollTo().assertExists()
        composeRule.onNodeWithText("再判断导数正负。").performScrollTo().assertExists()
    }

    @Test
    fun processConceptAndFormulaScenesRenderAsLocalBoundedGui() {
        val process = TutorProcessTimelineScene(
            sceneId = "process",
            title = "反应如何达到平衡",
            stages = listOf(
                TutorProcessStage(
                    stageId = "process-start",
                    label = "开始反应",
                    bodyMarkdown = "正反应速率较快。",
                    transitionMarkdown = "反应物减少，生成物增加",
                ),
                TutorProcessStage(
                    stageId = "process-balanced",
                    label = "动态平衡",
                    bodyMarkdown = "正、逆反应速率相等，浓度保持稳定。",
                ),
            ),
        )
        val concept = TutorConceptMapScene(
            sceneId = "concept",
            title = "导数符号关联什么",
            centerMarkdown = "导数符号",
            relations = listOf(
                TutorConceptRelation(
                    relationId = "concept-monotonicity",
                    relationLabel = "决定",
                    targetMarkdown = "原函数增减性",
                ),
                TutorConceptRelation(
                    relationId = "concept-extreme",
                    relationLabel = "变号时",
                    targetMarkdown = "可能出现极值",
                    detailMarkdown = "仍需核对定义域与变号方向。",
                ),
            ),
        )
        val formula = TutorFormulaDerivationScene(
            sceneId = "formula",
            title = "配方时每一步为什么成立",
            startFormula = "x^2+4x+1",
            steps = listOf(
                TutorFormulaDerivationStep(
                    stepId = "formula-complete-square",
                    reasonMarkdown = "补上 4，再减去 4，式子的值不变。",
                    resultFormula = "x^2+4x+4-3",
                ),
                TutorFormulaDerivationStep(
                    stepId = "formula-factor",
                    reasonMarkdown = "前三项正好组成完全平方。",
                    resultFormula = "(x+2)^2-3",
                ),
            ),
        )
        val scene = mutableStateOf<TutorVisualScene>(process)
        composeRule.setContent {
            MaterialTheme {
                RootPageColumn {
                    TutorVisualSceneRenderer(scene.value)
                }
            }
        }

        composeRule.onNodeWithText("反应如何达到平衡").assertExists()
        composeRule.onNodeWithText("反应物减少，生成物增加").assertExists()
        composeRule.onNodeWithContentDescription("第 1 个阶段，共 2 个阶段").assertExists()
        captureCurrentTutorScreen("tutor-process-timeline-current.png")
        composeRule.runOnIdle { scene.value = concept }
        composeRule.onNodeWithTag("tutor-concept-center-concept").assertExists()
        composeRule.onNodeWithTag("tutor-concept-relation-concept-extreme").assertExists()
        composeRule.onNodeWithText("仍需核对定义域与变号方向。").assertExists()
        captureCurrentTutorScreen("tutor-concept-map-current.png")
        composeRule.runOnIdle { scene.value = formula }
        composeRule.onNodeWithTag("tutor-formula-derivation-start-formula").assertExists()
        composeRule.onNodeWithTag(
            "tutor-formula-derivation-result-formula-factor",
        ).assertExists()
        composeRule.onNodeWithContentDescription("第 2 次变形，共 2 次").assertExists()
        captureCurrentTutorScreen("tutor-formula-derivation-current.png")
    }

    @Test
    fun explanationOnlyTurnWithNoUsefulMovesDoesNotRenderAnEmptyActionBar() {
        val output = tutorOutput().copy(
            plan = tutorOutput().plan.copy(
                diagnosticItem = null,
                suggestedMoves = emptyList(),
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                RootPageColumn { TutorTurnContent(output = output) }
            }
        }

        composeRule.onNodeWithText("接下来想怎么看？").assertDoesNotExist()
        composeRule.onNodeWithTag("captured_tutor_show_alternate").assertDoesNotExist()
        composeRule.onNodeWithTag("captured_tutor_reveal_without_choice").assertExists()
    }

    @Test
    fun explanationOnlyActionsRestoreFromPersistedResponse() {
        composeRule.setContent {
            MaterialTheme {
                RootPageColumn {
                    TutorTurnContent(
                        output = tutorOutput().copy(
                            plan = tutorOutput().plan.copy(diagnosticItem = null),
                        ),
                        response = actionResponse(
                            requestedMove = TutorMoveType.CHANGE_REPRESENTATION,
                            solutionRevealed = true,
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("captured_tutor_submit_choice").assertDoesNotExist()
        composeRule.onNodeWithText("符号表替代解法内容").performScrollTo().assertExists()
        composeRule.onNodeWithText("完整主解法内容").performScrollTo().assertExists()
    }

    @Test
    fun explanationOnlyRoutePersistsActionsWithTheCurrentQuestionIdentity() {
        val session = session()
        val interactions = RecordingTutorInteractions()
        val longSolution = "逐步推导并核对每一个条件。\n".repeat(100)
        val modelTasks = succeededExplanationOnlyModelTasks(session, longSolution)
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

        composeRule.onNodeWithTag("captured_tutor_show_alternate").performScrollTo().performClick()
        composeRule.onNodeWithText("符号表替代解法内容").performScrollTo().assertExists()
        composeRule.onNodeWithTag("captured_tutor_reveal_without_choice").performScrollTo().performClick()
        composeRule.onNodeWithTag("captured_tutor_solution").assertExists()
        composeRule.runOnIdle {
            assertTrue(interactions.revealCommands.isEmpty())
            assertTrue(interactions.exposureCommands.isEmpty())
        }
        composeRule.onNodeWithTag(
            "tutor_solution_bottom_plan:1:1:tutor-plan-action-only",
        ).performScrollTo().assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            interactions.recordedExposureKeys.size == 1
        }
        composeRule.runOnIdle {
            assertEquals(session.questionDocument.document.id, interactions.moveCommand?.questionDocumentId)
            assertEquals(session.draftRevisionNumber, interactions.moveCommand?.revisionNumber)
            assertEquals(session.questionDocument.document.id, interactions.revealCommand?.questionDocumentId)
            assertEquals(session.draftRevisionNumber, interactions.revealCommand?.revisionNumber)
            assertEquals(
                session.questionDocument.document.id,
                interactions.exposureCommands.single().questionDocumentId,
            )
            assertEquals(listOf("reveal", "exposure"), interactions.writeOrder)
            assertEquals(
                interactions.revealCommands.single().occurredAtEpochMillis,
                interactions.exposureCommands.single().occurredAtEpochMillis,
            )
            assertEquals(0, modelTasks.executeCalls)
            assertEquals(null, interactions.responses.value.single().selectedChoiceId)
        }
    }

    @Test
    fun leavingImmediatelyAfterRevealRequestDoesNotRecordAnUnseenExposure() {
        val session = session()
        val mounted = mutableStateOf(true)
        val interactions = RecordingTutorInteractions()
        val modelTasks = succeededExplanationOnlyModelTasks(
            session,
            "逐步推导并核对每一个条件。\n".repeat(100),
        )
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

        composeRule.onNodeWithTag("captured_tutor_reveal_without_choice")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("captured_tutor_solution").assertExists()
        composeRule.runOnIdle {
            assertTrue(interactions.revealCommands.isEmpty())
            assertTrue(interactions.exposureCommands.isEmpty())
            mounted.value = false
        }

        composeRule.runOnIdle {
            assertTrue(interactions.revealCommands.isEmpty())
            assertTrue(interactions.responses.value.isEmpty())
            assertTrue(interactions.exposureCommands.isEmpty())
            assertTrue(interactions.recordedExposureKeys.isEmpty())
            mounted.value = true
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("captured_tutor_solution").assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue(interactions.revealCommands.isEmpty())
            assertTrue(interactions.exposureCommands.isEmpty())
        }
    }

    @Test
    fun explanationOnlyRouteRestoresPersistedActionsWithoutExecutingAnotherTurn() {
        val session = session()
        val restoredResponse = actionResponse(
            requestedMove = TutorMoveType.CHANGE_REPRESENTATION,
            solutionRevealed = true,
        )
        val interactions = RecordingTutorInteractions().apply {
            responses.value = listOf(restoredResponse)
        }
        val modelTasks = succeededExplanationOnlyModelTasks(session)
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

        composeRule.onNodeWithText("符号表替代解法内容").performScrollTo().assertExists()
        composeRule.onNodeWithText("完整主解法内容").performScrollTo().assertExists()
        composeRule.runOnIdle {
            assertEquals(0, modelTasks.executeCalls)
            assertEquals(null, interactions.moveCommand)
            assertEquals(null, interactions.revealCommand)
        }
    }

    @Test
    fun advancingToAnotherTurnResetsTheLocalChoiceState() {
        val output = mutableStateOf(tutorOutput())
        val response = mutableStateOf<TutorTurnResponse?>(null)
        composeRule.setContent {
            MaterialTheme {
                RootPageColumn {
                    TutorTurnContent(
                        output = output.value,
                        response = response.value,
                        onSubmitChoice = { choiceId -> response.value = tutorResponse(choiceId) },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("captured_tutor_choice_choice-2").performScrollTo().performClick()
        composeRule.onNodeWithText("你把正负关系反过来了。").assertExists()

        composeRule.runOnIdle {
            output.value = tutorOutput().copy(turnOrdinal = 2)
            response.value = null
        }

        composeRule.onNodeWithTag("captured_tutor_choice_choice-2")
            .performScrollTo()
            .assertIsEnabled()
        composeRule.onNodeWithTag("captured_tutor_submit_choice").assertDoesNotExist()
        composeRule.onNodeWithText("你把正负关系反过来了。").assertDoesNotExist()
    }

    @Test
    fun savedMistakeShowsExactQuestionLearningMemoryWithoutOpeningAnotherPage() {
        composeRule.setContent {
            MaterialTheme {
                RootPageColumn {
                    TutorQuestionMemoryCard(
                        StudyQuestionMemory(
                            independentRecallCount = 2,
                            assistedRecallCount = 1,
                            retrievalFailureCount = 3,
                            answerRevealCount = 1,
                            lastReviewedAtEpochMillis = 100,
                            nextReviewAtEpochMillis = 200,
                            retrievabilityAtSnapshot = 0.42,
                            projectionIsCurrent = true,
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("saved_mistake_learning_memory").assertExists()
        composeRule.onNodeWithText("这道题的学习记忆").assertExists()
        composeRule.onNodeWithText(
            "独立答对 2 次 · 提示后答对 1 次 · 遗忘 3 次 · 看过答案 1 次",
        ).assertExists()
        composeRule.onNodeWithText("已到复习时间", substring = true).assertExists()
    }

    @Test
    fun freeTextReplyPreservesExactMessageRestoresLocallyAndDoesNotWriteLearningEvidence() {
        val session = session()
        val modelTasks = ChatModelTaskRepository(session)
        val interactions = RecordingTutorInteractions()
        val mounted = mutableStateOf(true)
        val exactMessage = "  x < 3 时为什么？\n参考 https://example.com 和 `f'(x)`  "
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

        composeRule.onNodeWithTag("tutor_chat_composer").assertIsDisplayed()
        captureCurrentTutorScreen()
        composeRule.onNodeWithTag("tutor_chat_composer").performTextInput(exactMessage)
        composeRule.onNodeWithTag("tutor_chat_send").assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            modelTasks.respondTasks.value.singleOrNull()?.status == ModelTaskStatus.SUCCEEDED
        }
        composeRule.onNodeWithTag("tutor_conversation_list").performScrollToIndex(2)
        composeRule.onNodeWithTag("tutor_chat_user_1").assertExists()
        composeRule.onNodeWithText("先看导数在临界点两侧的符号。").assertExists()
        composeRule.runOnIdle {
            val input = modelTasks.respondRequests.single().input as TutorRespondInput
            assertEquals(exactMessage, input.studentMessage)
            assertEquals(1, modelTasks.executeRespondCalls)
            assertEquals(emptyList<TutorTurnResponse>(), interactions.responses.value)
            assertEquals(null, interactions.moveCommand)
            assertEquals(null, interactions.revealCommand)
            mounted.value = false
        }
        composeRule.runOnIdle { mounted.value = true }
        composeRule.onNodeWithTag("tutor_conversation_list").performScrollToIndex(2)
        composeRule.onNodeWithTag("tutor_chat_user_1").assertExists()
        composeRule.onNodeWithText("先看导数在临界点两侧的符号。").assertExists()
        composeRule.runOnIdle { assertEquals(1, modelTasks.executeRespondCalls) }
    }

    @Test
    fun uncertaintyEscapeSendsAnExactHintRequestWithoutWritingChoiceEvidence() {
        val session = session()
        val modelTasks = ChatModelTaskRepository(session)
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

        composeRule.onNodeWithTag("captured_tutor_request_hint")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            modelTasks.respondTasks.value.singleOrNull()?.status == ModelTaskStatus.SUCCEEDED
        }

        composeRule.runOnIdle {
            val input = modelTasks.respondRequests.single().input as TutorRespondInput
            assertEquals("我不确定，请给我一点提示", input.studentMessage)
            assertEquals(null, input.requestedMove)
            assertEquals(emptyList<TutorTurnResponse>(), interactions.responses.value)
            assertEquals(null, interactions.moveCommand)
            assertEquals(null, interactions.revealCommand)
        }
    }

    private fun captureCurrentTutorScreen(displayName: String = "tutor-active-current.png") {
        val resolver = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .contentResolver
        val collection = MediaStore.Images.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
        val relativePath = "Pictures/SmartMistakeBookQA/"
        resolver.delete(
            collection,
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(displayName),
        )
        val uri = checkNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        val screenshot = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val saved = checkNotNull(resolver.openOutputStream(uri)).use { stream ->
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
        check(saved) { "Tutor screenshot could not be encoded" }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
    }

    @Test
    fun diagnosticManualRevealWaitsForTheSuccessfulReplyBottomBeforeRecordingExposure() {
        val session = session()
        val revealMove = TutorSuggestedMove(
            id = "reveal-diagnostic-answer",
            label = "直接展示完整答案",
            type = TutorMoveType.REVEAL_SOLUTION,
        )
        val modelTasks = ChatModelTaskRepository(
            session = session,
            restoredSucceededMessage = "我还是不明白，请给我一个可选动作",
            restoredSucceededSuggestedMoves = listOf(revealMove),
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

        composeRule.onNodeWithTag("tutor_chat_move_${revealMove.id}")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            modelTasks.executeRespondCalls == 1 &&
                modelTasks.respondTasks.value.lastOrNull()?.status == ModelTaskStatus.RUNNING
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(interactions.revealCommands.isEmpty())
            assertTrue(interactions.exposureCommands.isEmpty())
        }

        composeRule.runOnIdle { modelTasks.publishLatestResponseFailure() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(ModelTaskStatus.RETRYABLE_FAILURE, modelTasks.respondTasks.value.last().status)
            assertTrue(interactions.revealCommands.isEmpty())
            assertTrue(interactions.exposureCommands.isEmpty())
        }

        val ending = "\n\n完整答案到这里结束。"
        val repeatedStep = "逐步推导当前题，检查每一步的条件与结论。\n"
        val longReply = repeatedStep
            .repeat(TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS / repeatedStep.length)
            .take(TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS - ending.length) + ending
        composeRule.runOnIdle { modelTasks.publishRestoredSolutionReply(longReply) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(interactions.revealCommands.isEmpty())
            assertTrue(interactions.exposureCommands.isEmpty())
        }

        composeRule.onNodeWithTag("tutor_chat_assistant_bottom_2")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            interactions.recordedExposureKeys.size == 1
        }
        composeRule.runOnIdle {
            assertEquals(1, interactions.exposureCommands.size)
            assertEquals(
                TutorAnswerExposureSurfaceKind.RESPOND_REPLY,
                interactions.exposureCommands.single().surfaceKind,
            )
            assertEquals(1, interactions.exposureCommands.single().cycleOrdinal)
            assertEquals(1, interactions.exposureCommands.single().turnOrdinal)
            assertTrue(
                interactions.exposureCommands.single().occurredAtEpochMillis >= 10_000L,
            )
        }
    }

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

    private fun longSession(): ConfirmedTutorSession {
        val baseSession = session()
        return baseSession.copy(
            questionDocument = baseSession.questionDocument.copy(
                document = baseSession.questionDocument.document.copy(
                    blocks = listOf(
                        ContentBlock.Paragraph(
                            id = "stem",
                            markdown = (1..80).joinToString("\n") { line ->
                                "第 $line 行：继续分析这道题的已知条件。"
                            },
                        ),
                    ),
                ),
            ),
        )
    }

    private fun session(): ConfirmedTutorSession {
        val sourceAssetId = "asset-1"
        return ConfirmedTutorSession(
            sessionId = "session-1",
            draftId = "draft-1",
            draftRevisionNumber = 2,
            subject = "MATH",
            title = "求函数的单调区间",
            questionDocument = CapturedQuestionDocument(
                document = QuestionDocument(
                    id = "document-1",
                    title = "求函数的单调区间",
                    blocks = listOf(
                        ContentBlock.Paragraph(
                            id = "stem",
                            markdown = "已知 f(x)=x³-3x，求它的单调区间。",
                        ),
                    ),
                ),
                blockEvidence = listOf(
                    QuestionBlockEvidence(
                        blockId = "stem",
                        sourceAssetId = sourceAssetId,
                        sourceRegion = NormalizedSourceRegion(
                            left = 0.0,
                            top = 0.0,
                            right = 1.0,
                            bottom = 1.0,
                        ),
                        writingLayer = WritingLayer.PRINTED,
                        provenance = QuestionBlockProvenance.USER_CORRECTION,
                        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    ),
                ),
            ),
            sourceImageUri = "file:///data/user/0/example/files/source.jpg",
            createdAtEpochMillis = 1_000,
            isSaved = false,
            errorBookEntryId = null,
        )
    }

    private fun unavailableModelTasks(): ModelTaskRepository = object : ModelTaskRepository {
        override suspend fun capabilities() = ProviderCapabilitySnapshot(
            providerId = "unconfigured",
            providerDisplayName = "尚未配置模型",
            modelId = "unconfigured",
            supportedTasks = emptySet(),
            supportsImageInput = false,
            supportsStructuredOutput = false,
            supportsStreaming = false,
            executionLocation = ModelExecutionLocation.UNAVAILABLE,
        )

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> = flowOf(emptyList())

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> =
            error("Unavailable provider must not execute")
    }

    private fun succeededExplanationOnlyModelTasks(
        session: ConfirmedTutorSession,
        solutionMarkdown: String = tutorOutput().plan.solutionMarkdown,
    ): RecordingModelTaskRepository {
        val provider = ProviderCapabilitySnapshot(
            providerId = "configured-provider",
            providerDisplayName = "已配置模型",
            modelId = "tutor-model-v1",
            supportedTasks = setOf(ModelTaskKind.TUTOR_PLAN),
            supportsImageInput = false,
            supportsStructuredOutput = true,
            supportsStreaming = true,
        )
        val request = buildTutorPlanRequest(
            session = session,
            profile = StudyProfileOverview(),
            provider = provider,
            requestId = "tutor-plan-action-only",
            occurredAtEpochMillis = 100,
        )
        val output = tutorOutput().copy(
            plan = tutorOutput().plan.copy(
                diagnosticItem = null,
                solutionMarkdown = solutionMarkdown,
            ),
        )
        return RecordingModelTaskRepository(
            provider = provider,
            snapshot = ModelTaskSnapshot(
                taskId = "task-action-only",
                request = request,
                requestFingerprint = ModelTaskFingerprint.of(request),
                status = com.tingyun.smartmistakebook.core.model.ModelTaskStatus.SUCCEEDED,
                stateVersion = 1,
                stage = ModelTaskStage.COMPLETE,
                userMessage = "当前题讲解已准备",
                attemptCount = 1,
                provider = provider,
                output = output,
                createdAtEpochMillis = 100,
                updatedAtEpochMillis = 200,
            ),
        )
    }

    private fun emptyInteractions(): TutorInteractionRepository = object : TutorInteractionRepository {
        override fun observe(sessionId: String): Flow<List<TutorTurnResponse>> = flowOf(emptyList())

        override suspend fun recordChoice(command: RecordTutorChoiceCommand): TutorTurnResponse =
            error("No interaction write expected")

        override suspend fun recordMove(command: RecordTutorMoveCommand): TutorTurnResponse =
            error("No interaction write expected")

        override suspend fun revealSolution(command: RevealTutorSolutionCommand): TutorTurnResponse =
            error("No interaction write expected")

        override suspend fun recordSolutionExposure(
            command: RecordTutorSolutionExposureCommand,
        ) = error("No exposure write expected")
    }

    private fun emptyConversations(): TutorConversationRepository =
        object : TutorConversationRepository {
            private val snapshot = MutableStateFlow<TutorConversationSnapshot?>(null)

            override fun observeRecent(limit: Int): Flow<List<TutorConversation>> =
                snapshot.map { it?.conversation?.let(::listOf).orEmpty() }

            override fun observeConversation(
                conversationId: String,
            ): Flow<TutorConversationSnapshot?> = snapshot

            override suspend fun createConversation(
                command: CreateTutorConversationCommand,
            ): TutorConversation {
                val conversation = TutorConversation(
                    conversationId = command.conversationId,
                    anchorKind = command.anchorKind,
                    anchorId = command.anchorId,
                    anchorRevisionId = command.anchorRevisionId,
                    status = TutorConversationStatus.ACTIVE,
                    title = command.title,
                    createdAtEpochMillis = command.createdAtEpochMillis,
                    updatedAtEpochMillis = command.createdAtEpochMillis,
                    lastTurnOrdinal = 0,
                )
                snapshot.value = TutorConversationSnapshot(conversation, emptyList())
                return conversation
            }

            override suspend fun appendStudentMessage(
                command: AppendTutorStudentMessageCommand,
            ): TutorMessage {
                val message = TutorMessage(
                    messageId = command.messageId,
                    conversationId = command.conversationId,
                    ordinal = command.ordinal,
                    role = TutorMessageRole.STUDENT,
                    bodyMarkdown = command.bodyMarkdown,
                    status = TutorMessageStatus.PERSISTED,
                    logicalOperationId = command.logicalOperationId,
                    replyToMessageId = null,
                    createdAtEpochMillis = command.createdAtEpochMillis,
                    completedAtEpochMillis = command.createdAtEpochMillis,
                    errorCode = null,
                )
                snapshot.value = snapshot.value?.let { current ->
                    current.copy(
                        conversation = current.conversation.copy(
                            updatedAtEpochMillis = command.createdAtEpochMillis,
                            lastTurnOrdinal = command.ordinal,
                        ),
                        messages = current.messages + message,
                    )
                }
                return message
            }

            override suspend fun appendAssistantMessage(
                command: AppendTutorAssistantMessageCommand,
            ): TutorMessage {
                val message = TutorMessage(
                    messageId = command.messageId,
                    conversationId = command.conversationId,
                    ordinal = command.ordinal,
                    role = TutorMessageRole.ASSISTANT,
                    bodyMarkdown = command.bodyMarkdown,
                    status = command.status,
                    logicalOperationId = command.logicalOperationId,
                    replyToMessageId = command.replyToMessageId,
                    createdAtEpochMillis = command.createdAtEpochMillis,
                    completedAtEpochMillis = command.completedAtEpochMillis,
                    errorCode = command.errorCode,
                )
                snapshot.value = snapshot.value?.let { current ->
                    current.copy(
                        conversation = current.conversation.copy(
                            updatedAtEpochMillis = command.completedAtEpochMillis
                                ?: command.createdAtEpochMillis,
                            lastTurnOrdinal = command.ordinal,
                        ),
                        messages = current.messages + message,
                    )
                }
                return message
            }

            override suspend fun updateMessageStatus(
                command: UpdateTutorMessageStatusCommand,
            ): TutorMessage {
                val current = snapshot.value ?: error("No tutor conversation")
                val existing = current.messages.first { it.messageId == command.messageId }
                val updated = existing.copy(
                    status = command.nextStatus,
                    bodyMarkdown = command.bodyMarkdown ?: existing.bodyMarkdown,
                    completedAtEpochMillis = command.completedAtEpochMillis,
                    errorCode = command.errorCode,
                )
                snapshot.value = current.copy(
                    messages = current.messages.map { message ->
                        if (message.messageId == command.messageId) updated else message
                    },
                )
                return updated
            }

            override suspend fun pauseConversation(
                command: PauseTutorConversationCommand,
            ): TutorConversation {
                val current = snapshot.value ?: error("No tutor conversation")
                val conversation = current.conversation.copy(
                    status = TutorConversationStatus.PAUSED,
                    updatedAtEpochMillis = command.occurredAtEpochMillis,
                )
                snapshot.value = current.copy(conversation = conversation)
                return conversation
            }

            override suspend fun archiveConversation(
                command: ArchiveTutorConversationCommand,
            ): TutorConversation {
                val current = snapshot.value ?: error("No tutor conversation")
                val conversation = current.conversation.copy(
                    status = TutorConversationStatus.ARCHIVED,
                    updatedAtEpochMillis = command.occurredAtEpochMillis,
                )
                snapshot.value = current.copy(conversation = conversation)
                return conversation
            }

            override suspend fun deleteConversation(
                command: DeleteTutorConversationCommand,
            ) {
                snapshot.value = null
            }

            override suspend fun saveDraft(
                command: SaveTutorConversationDraftCommand,
            ) {
                snapshot.value = snapshot.value?.let { current ->
                    current.copy(
                        conversation = current.conversation.copy(
                            studentDraft = command.draft,
                            updatedAtEpochMillis = command.occurredAtEpochMillis,
                        ),
                    )
                }
            }

            override suspend fun clearDraft(
                command: ClearTutorConversationDraftCommand,
            ) {
                snapshot.value = snapshot.value?.let { current ->
                    current.copy(
                        conversation = current.conversation.copy(
                            studentDraft = null,
                            updatedAtEpochMillis = command.occurredAtEpochMillis,
                        ),
                    )
                }
            }
        }

    private fun tutorResponse(choiceId: String): TutorTurnResponse {
        val output = tutorOutput()
        val item = requireNotNull(output.plan.diagnosticItem)
        val evaluation = item.evaluateChoice(choiceId)
        return TutorTurnResponse(
            sessionId = output.sessionId,
            questionDocumentId = output.questionDocumentId,
            revisionNumber = output.draftRevisionNumber,
            cycleOrdinal = output.cycleOrdinal,
            turnOrdinal = output.turnOrdinal,
            diagnosticStemMarkdown = item.stemMarkdown,
            selectedChoiceId = evaluation.choice.id,
            selectedChoiceMarkdown = evaluation.choice.markdown,
            selectionWasCorrect = evaluation.isCorrect,
            feedbackMarkdown = requireNotNull(evaluation.choice.feedbackMarkdown),
            submittedAtEpochMillis = 1_000,
            updatedAtEpochMillis = 1_000,
        )
    }

    private fun actionResponse(
        requestedMove: TutorMoveType? = null,
        solutionRevealed: Boolean = false,
    ): TutorTurnResponse {
        val output = tutorOutput()
        return TutorTurnResponse(
            sessionId = output.sessionId,
            questionDocumentId = output.questionDocumentId,
            revisionNumber = output.draftRevisionNumber,
            cycleOrdinal = output.cycleOrdinal,
            turnOrdinal = output.turnOrdinal,
            diagnosticStemMarkdown = null,
            selectedChoiceId = null,
            selectedChoiceMarkdown = null,
            selectionWasCorrect = null,
            feedbackMarkdown = null,
            requestedMove = requestedMove,
            solutionRevealed = solutionRevealed,
            submittedAtEpochMillis = 1_000,
            updatedAtEpochMillis = 1_000,
        )
    }

    private fun tutorOutput() = TutorPlanOutput(
        sessionId = "session-1",
        draftRevisionNumber = 2,
        questionDocumentId = "document-1",
        plan = TutorTurnPlan(
            openingMarkdown = "先判断导数的正负变化。",
            diagnosticItem = TutorAssessmentItem(
                id = "diagnostic-1",
                stemMarkdown = "导数先正后负时，原函数怎样变化？",
                choices = listOf(
                    TutorChoice("choice-1", "先增后减", "这个对应关系是正确的。"),
                    TutorChoice("choice-2", "先减后增", "你把正负关系反过来了。"),
                    TutorChoice("choice-3", "始终递增", "这里忽略了导数变号。"),
                ),
                correctChoiceId = "choice-1",
            ),
            solutionMarkdown = "完整主解法内容",
            alternateMethodMarkdown = "符号表替代解法内容",
            difficultyReasonMarkdown = "用于区分符号对应和变号遗漏。",
            targetedEvidenceLabels = emptyList(),
            inferredKnowledgeLabels = listOf("导数", "函数单调性"),
            suggestedMoves = listOf(
                TutorSuggestedMove(
                    id = "change",
                    label = "换一种思路",
                    type = TutorMoveType.CHANGE_REPRESENTATION,
                ),
                TutorSuggestedMove(
                    id = "solution",
                    label = "查看完整讲解",
                    type = TutorMoveType.REVEAL_SOLUTION,
                ),
            ),
        ),
        modelVersion = "model-v1",
    )

    private inner class RestartCycleModelTaskRepository(
        session: ConfirmedTutorSession,
        exactStudentMessage: String,
    ) : ModelTaskRepository {
        private val provider = ProviderCapabilitySnapshot(
            providerId = "configured-provider",
            providerDisplayName = "已配置模型",
            modelId = "tutor-model-v1",
            supportedTasks = setOf(ModelTaskKind.TUTOR_PLAN, ModelTaskKind.TUTOR_RESPOND),
            supportsImageInput = false,
            supportsStructuredOutput = true,
            supportsStreaming = true,
        )
        private val priorTurns = (1 until TutorPlanInput.MAX_TURNS).map { turnOrdinal ->
            TutorTurnHistoryEntry(
                turnOrdinal = turnOrdinal,
                diagnosticStemMarkdown = "第 $turnOrdinal 步应怎样继续？",
                selectedChoiceMarkdown = "先减后增",
                selectionWasCorrect = false,
                feedbackMarkdown = "继续围绕当前题修正这一步。",
                requestedMove = TutorMoveType.CHANGE_REPRESENTATION,
            )
        }
        private val planRequest = buildTutorPlanRequest(
            session = session,
            profile = StudyProfileOverview(),
            provider = provider,
            requestId = "tutor-plan-restart-source",
            occurredAtEpochMillis = 100,
            priorTurns = priorTurns,
        )
        private val planSnapshot = ModelTaskSnapshot(
            taskId = "task-tutor-plan-restart-source",
            request = planRequest,
            requestFingerprint = ModelTaskFingerprint.of(planRequest),
            status = ModelTaskStatus.SUCCEEDED,
            stateVersion = 1,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "讲解已准备好",
            attemptCount = 1,
            provider = provider,
            output = tutorOutput().copy(turnOrdinal = TutorPlanInput.MAX_TURNS),
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 101,
        )
        private val respondRequest = buildTutorRespondRequest(
            question = session.toTutorQuestionContext(),
            profile = StudyProfileOverview(),
            provider = provider,
            requestId = "tutor-respond-stuck-step",
            occurredAtEpochMillis = 50,
            responseOrdinal = 1,
            cycleOrdinal = 1,
            turnOrdinal = TutorPlanInput.MAX_TURNS,
            studentMessage = exactStudentMessage,
            visibleTutorContextMarkdown = "正在讲配方法。",
            priorMessages = emptyList(),
        )
        private val respondSnapshot = ModelTaskSnapshot(
            taskId = "task-tutor-respond-stuck-step",
            request = respondRequest,
            requestFingerprint = ModelTaskFingerprint.of(respondRequest),
            status = ModelTaskStatus.CANCELLED,
            stateVersion = 1,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "回复未完成",
            attemptCount = 1,
            provider = provider,
            output = null,
            createdAtEpochMillis = 50,
            updatedAtEpochMillis = 51,
        )
        val planRequests = mutableListOf<ModelTaskRequest>()

        override suspend fun capabilities(): ProviderCapabilitySnapshot = provider

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(
            listOf(planSnapshot, respondSnapshot).firstOrNull {
                it.request.requestId == requestId
            },
        )

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> = when (kind) {
            ModelTaskKind.TUTOR_PLAN -> flowOf(listOf(planSnapshot))
            ModelTaskKind.TUTOR_RESPOND -> flowOf(listOf(respondSnapshot))
            else -> flowOf(emptyList())
        }

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow {
            planRequests += request
            emit(
                ModelTaskSnapshot(
                    taskId = "task:${request.requestId}",
                    request = request,
                    requestFingerprint = ModelTaskFingerprint.of(request),
                    status = ModelTaskStatus.RUNNING,
                    stateVersion = 1,
                    stage = ModelTaskStage.PREPARING,
                    userMessage = "正在继续讲解",
                    attemptCount = 1,
                    provider = provider,
                    createdAtEpochMillis = request.occurredAtEpochMillis,
                    updatedAtEpochMillis = request.occurredAtEpochMillis,
                ),
            )
        }
    }

    private class FixedTutorInteractions(
        private val responses: List<TutorTurnResponse>,
    ) : TutorInteractionRepository {
        override fun observe(sessionId: String): Flow<List<TutorTurnResponse>> = flowOf(responses)

        override suspend fun recordChoice(command: RecordTutorChoiceCommand): TutorTurnResponse =
            error("No choice write expected")

        override suspend fun recordMove(command: RecordTutorMoveCommand): TutorTurnResponse =
            error("No move write expected")

        override suspend fun revealSolution(command: RevealTutorSolutionCommand): TutorTurnResponse =
            error("No reveal write expected")

        override suspend fun recordSolutionExposure(
            command: RecordTutorSolutionExposureCommand,
        ) = error("No exposure write expected")
    }

    private class SuspendingExposureTutorInteractions : TutorInteractionRepository {
        val exposureCommands = mutableListOf<RecordTutorSolutionExposureCommand>()
        var cancellationCount: Int = 0

        override fun observe(sessionId: String): Flow<List<TutorTurnResponse>> = flowOf(emptyList())

        override suspend fun recordChoice(command: RecordTutorChoiceCommand): TutorTurnResponse =
            error("No choice write expected")

        override suspend fun recordMove(command: RecordTutorMoveCommand): TutorTurnResponse =
            error("No move write expected")

        override suspend fun revealSolution(command: RevealTutorSolutionCommand): TutorTurnResponse =
            TutorTurnResponse(
                sessionId = command.sessionId,
                questionDocumentId = command.questionDocumentId,
                revisionNumber = command.revisionNumber,
                cycleOrdinal = command.cycleOrdinal,
                turnOrdinal = command.turnOrdinal,
                diagnosticStemMarkdown = null,
                selectedChoiceId = null,
                selectedChoiceMarkdown = null,
                selectionWasCorrect = null,
                feedbackMarkdown = null,
                requestedMove = null,
                solutionRevealed = true,
                submittedAtEpochMillis = command.occurredAtEpochMillis,
                updatedAtEpochMillis = command.occurredAtEpochMillis,
            )

        override suspend fun recordSolutionExposure(command: RecordTutorSolutionExposureCommand) {
            exposureCommands += command
            try {
                awaitCancellation()
            } finally {
                cancellationCount += 1
            }
        }
    }

    private class RecordingModelTaskRepository(
        private val provider: ProviderCapabilitySnapshot,
        private val snapshot: ModelTaskSnapshot,
    ) : ModelTaskRepository {
        var executeCalls: Int = 0

        override suspend fun capabilities(): ProviderCapabilitySnapshot = provider

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(
            snapshot.takeIf { it.request.requestId == requestId },
        )

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> = flowOf(
            listOf(snapshot).filter {
                it.request.input.subjectId == subjectId && it.request.input.kind == kind
            },
        )

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow {
            executeCalls += 1
            error("An explanation-only action must not manufacture another tutor turn")
        }
    }

    private inner class ChatModelTaskRepository(
        private val session: ConfirmedTutorSession,
        restoredPendingMessage: String? = null,
        restoredSucceededMessage: String? = null,
        private val restoredSucceededRevealsSolution: Boolean = false,
        private val restoredSucceededIntentDecision: TutorIntentDecision =
            TutorIntentDecision.currentQuestionDefault(),
        private val restoredSucceededSuggestedMoves: List<TutorSuggestedMove> = emptyList(),
        private val restoredCycleOrdinal: Int = 1,
        private val restoredTurnOrdinal: Int = 1,
        private val restoredFailureStatus: ModelTaskStatus? = null,
        private val restoredFailureCode: ModelFailureCode? = null,
        private val restoredFailureMessage: String = "请解释为什么要分区间",
        private val restoredRequestedMove: TutorMoveType? = null,
        private val restoredPlanStatus: ModelTaskStatus = ModelTaskStatus.SUCCEEDED,
        private val includeInitialPlan: Boolean = true,
        externalProvider: Boolean = false,
        currentCapabilities: ProviderCapabilitySnapshot? = null,
        private val holdRespondExecution: Boolean = false,
    ) : ModelTaskRepository {
        private val provider = ProviderCapabilitySnapshot(
            providerId = "configured-provider",
            providerDisplayName = "已配置模型",
            modelId = "tutor-model-v1",
            supportedTasks = setOf(ModelTaskKind.TUTOR_PLAN, ModelTaskKind.TUTOR_RESPOND),
            supportsImageInput = false,
            supportsStructuredOutput = true,
            supportsStreaming = true,
            executionLocation = if (externalProvider) {
                ModelExecutionLocation.EXTERNAL_PROVIDER
            } else {
                ModelExecutionLocation.LOCAL_NO_EGRESS
            },
        )
        private var currentCapabilitiesOverride = currentCapabilities
        private val planRequest = buildTutorPlanRequest(
            session = session,
            profile = StudyProfileOverview(),
            provider = provider,
            requestId = "tutor-plan-chat",
            occurredAtEpochMillis = 100,
        )
        private val planSnapshot = ModelTaskSnapshot(
            taskId = "task-plan-chat",
            request = planRequest,
            requestFingerprint = ModelTaskFingerprint.of(planRequest),
            status = restoredPlanStatus,
            stateVersion = 1,
            stage = if (restoredPlanStatus == ModelTaskStatus.SUCCEEDED) {
                ModelTaskStage.COMPLETE
            } else {
                ModelTaskStage.PREPARING
            },
            userMessage = if (restoredPlanStatus == ModelTaskStatus.SUCCEEDED) {
                "讲解已准备好"
            } else {
                "正在准备讲解"
            },
            attemptCount = 1,
            provider = provider,
            output = tutorOutput().takeIf {
                restoredPlanStatus == ModelTaskStatus.SUCCEEDED
            },
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 200,
        )
        val planTasks = MutableStateFlow(
            if (includeInitialPlan) listOf(planSnapshot) else emptyList(),
        )
        val respondTasks = MutableStateFlow<List<ModelTaskSnapshot>>(emptyList())
        val planRequests = mutableListOf<ModelTaskRequest>()
        val respondRequests = mutableListOf<ModelTaskRequest>()
        var executePlanCalls: Int = 0
        var executeRespondCalls: Int = 0
        val capabilitySnapshot: ProviderCapabilitySnapshot
            get() = currentCapabilitiesOverride ?: provider

        fun useCapabilities(capabilities: ProviderCapabilitySnapshot) {
            currentCapabilitiesOverride = capabilities
        }

        init {
            require(
                listOf(
                    restoredPendingMessage != null,
                    restoredSucceededMessage != null,
                    restoredFailureStatus != null,
                ).count { it } <= 1,
            )
            require(
                restoredFailureStatus == null || restoredFailureStatus in setOf(
                    ModelTaskStatus.RETRYABLE_FAILURE,
                    ModelTaskStatus.PERMANENT_FAILURE,
                    ModelTaskStatus.CANCELLED,
                ),
            )
            require(restoredFailureCode == null || restoredFailureStatus != null)
            val restoredMessage = restoredPendingMessage ?: restoredSucceededMessage
                ?: restoredFailureStatus?.let { restoredFailureMessage }
            if (restoredMessage != null) {
                val restoredRequest = buildTutorRespondRequest(
                    question = session.toTutorQuestionContext(),
                    profile = StudyProfileOverview(),
                    provider = provider,
                    requestId = "tutor-respond-restored",
                    occurredAtEpochMillis = 300,
                    responseOrdinal = 1,
                    cycleOrdinal = restoredCycleOrdinal,
                    turnOrdinal = restoredTurnOrdinal,
                    studentMessage = restoredMessage,
                    visibleTutorContextMarkdown = "先判断导数的正负变化。",
                    priorMessages = emptyList(),
                    requestedMove = restoredRequestedMove,
                )
                val restoredInput = restoredRequest.input as TutorRespondInput
                val restoredSucceeded = restoredSucceededMessage != null
                val restoredStatus = when {
                    restoredSucceeded -> ModelTaskStatus.SUCCEEDED
                    restoredFailureStatus != null -> restoredFailureStatus
                    else -> ModelTaskStatus.WAITING_FOR_MODEL
                }
                respondTasks.value = listOf(
                    responseSnapshot(
                        request = restoredRequest,
                        status = restoredStatus,
                        output = if (restoredSucceeded) {
                            TutorRespondOutput(
                                sessionId = restoredInput.sessionId,
                                draftRevisionNumber = restoredInput.draftRevisionNumber,
                                questionDocumentId = restoredInput.questionDocument.id,
                                responseOrdinal = restoredInput.responseOrdinal,
                                cycleOrdinal = restoredInput.cycleOrdinal,
                                turnOrdinal = restoredInput.turnOrdinal,
                                messageMarkdown = "先看导数在临界点两侧的符号。",
                                solutionRevealed = restoredSucceededRevealsSolution,
                                suggestedMoves = restoredSucceededSuggestedMoves,
                                intentDecision = restoredSucceededIntentDecision,
                                modelVersion = "model-v1",
                            )
                        } else {
                            null
                        },
                        stateVersion = if (restoredSucceeded) 3 else 1,
                    ),
                )
            }
        }

        override suspend fun capabilities(): ProviderCapabilitySnapshot =
            currentCapabilitiesOverride ?: provider

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(
            (planTasks.value + respondTasks.value)
                .firstOrNull { it.request.requestId == requestId },
        )

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> = when (kind) {
            ModelTaskKind.TUTOR_PLAN -> planTasks
            ModelTaskKind.TUTOR_RESPOND -> respondTasks
            else -> flowOf(emptyList())
        }

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow {
            when (val input = request.input) {
                is TutorPlanInput -> {
                    executePlanCalls += 1
                    planRequests += request
                    val running = planSnapshot.copy(
                        request = request,
                        requestFingerprint = ModelTaskFingerprint.of(request),
                        status = ModelTaskStatus.RUNNING,
                        stateVersion = 2,
                        stage = ModelTaskStage.PREPARING,
                        userMessage = "正在准备讲解",
                        output = null,
                    )
                    planTasks.value = listOf(running)
                    emit(running)
                    val succeeded = running.copy(
                        status = ModelTaskStatus.SUCCEEDED,
                        stateVersion = 3,
                        stage = ModelTaskStage.COMPLETE,
                        userMessage = "讲解已准备好",
                        output = tutorOutput().copy(
                            sessionId = input.sessionId,
                            draftRevisionNumber = input.draftRevisionNumber,
                            questionDocumentId = input.questionDocument.id,
                            cycleOrdinal = input.cycleOrdinal,
                            turnOrdinal = input.turnOrdinal,
                        ),
                    )
                    planTasks.value = listOf(succeeded)
                    emit(succeeded)
                }
                is TutorRespondInput -> {
                    executeRespondCalls += 1
                    respondRequests += request
                    val running = responseSnapshot(
                        request = request,
                        status = ModelTaskStatus.RUNNING,
                        output = null,
                        stateVersion = 2,
                    )
                    upsert(running)
                    emit(running)
                    if (holdRespondExecution) awaitCancellation()
                    val succeeded = responseSnapshot(
                        request = request,
                        status = ModelTaskStatus.SUCCEEDED,
                        output = TutorRespondOutput(
                            sessionId = input.sessionId,
                            draftRevisionNumber = input.draftRevisionNumber,
                            questionDocumentId = input.questionDocument.id,
                            responseOrdinal = input.responseOrdinal,
                            cycleOrdinal = input.cycleOrdinal,
                            turnOrdinal = input.turnOrdinal,
                            messageMarkdown = "先看导数在临界点两侧的符号。",
                            intentDecision = TutorIntentDecision.currentQuestionDefault(),
                            modelVersion = "model-v1",
                        ),
                        stateVersion = 3,
                    )
                    upsert(succeeded)
                    emit(succeeded)
                }
                else -> error("Chat repository only executes tutor tasks")
            }
        }

        fun publishRestoredSolutionReply(messageMarkdown: String = "先看完整推导。") {
            val current = respondTasks.value.last()
            val input = current.request.input as TutorRespondInput
            upsert(
                responseSnapshot(
                    request = current.request,
                    status = ModelTaskStatus.SUCCEEDED,
                    output = TutorRespondOutput(
                        sessionId = input.sessionId,
                        draftRevisionNumber = input.draftRevisionNumber,
                        questionDocumentId = input.questionDocument.id,
                        responseOrdinal = input.responseOrdinal,
                        cycleOrdinal = input.cycleOrdinal,
                        turnOrdinal = input.turnOrdinal,
                        messageMarkdown = messageMarkdown,
                        solutionRevealed = true,
                        intentDecision = TutorIntentDecision.currentQuestionDefault(),
                        modelVersion = "model-v1",
                    ),
                    stateVersion = current.stateVersion + 1,
                ),
            )
        }

        fun publishLatestResponseFailure() {
            val current = respondTasks.value.last()
            upsert(
                responseSnapshot(
                    request = current.request,
                    status = ModelTaskStatus.RETRYABLE_FAILURE,
                    output = null,
                    stateVersion = current.stateVersion + 1,
                ),
            )
        }

        private fun upsert(snapshot: ModelTaskSnapshot) {
            respondTasks.value = respondTasks.value
                .filterNot { it.request.requestId == snapshot.request.requestId }
                .plus(snapshot)
        }

        private fun responseSnapshot(
            request: ModelTaskRequest,
            status: ModelTaskStatus,
            output: TutorRespondOutput?,
            stateVersion: Long,
        ) = ModelTaskSnapshot(
            taskId = "task:${request.requestId}",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = status,
            stateVersion = stateVersion,
            stage = if (status == ModelTaskStatus.SUCCEEDED) {
                ModelTaskStage.COMPLETE
            } else {
                ModelTaskStage.PREPARING
            },
            userMessage = if (status == ModelTaskStatus.SUCCEEDED) {
                "回复已准备好"
            } else {
                "正在回复"
            },
            attemptCount = 1,
            provider = request.egressManifest?.let { manifest ->
                currentCapabilitiesOverride?.takeIf { candidate ->
                    manifest.providerId == candidate.providerId &&
                        manifest.modelId == candidate.modelId &&
                        manifest.providerConfigurationVersion ==
                        candidate.providerConfigurationVersion
                }
            } ?: currentCapabilitiesOverride ?: provider,
            output = output,
            failure = when (status) {
                ModelTaskStatus.RETRYABLE_FAILURE -> ModelTaskFailure(
                    code = restoredFailureCode ?: ModelFailureCode.TIMEOUT,
                    message = "暂时没有完成",
                    retryable = true,
                )
                ModelTaskStatus.PERMANENT_FAILURE -> ModelTaskFailure(
                    code = restoredFailureCode ?: ModelFailureCode.PROVIDER_REJECTED_INPUT,
                    message = "这次无法完成",
                    retryable = false,
                )
                else -> null
            },
            createdAtEpochMillis = request.occurredAtEpochMillis,
            updatedAtEpochMillis = request.occurredAtEpochMillis + stateVersion,
        )
    }

    private class RecordingTutorInteractions : TutorInteractionRepository {
        val responses = MutableStateFlow<List<TutorTurnResponse>>(emptyList())
        var moveCommand: RecordTutorMoveCommand? = null
        var revealCommand: RevealTutorSolutionCommand? = null
        val revealCommands = mutableListOf<RevealTutorSolutionCommand>()
        val exposureCommands = mutableListOf<RecordTutorSolutionExposureCommand>()
        val recordedExposureKeys = linkedSetOf<String>()
        val writeOrder = mutableListOf<String>()

        override fun observe(sessionId: String): Flow<List<TutorTurnResponse>> = responses

        override suspend fun recordChoice(command: RecordTutorChoiceCommand): TutorTurnResponse =
            TutorTurnResponse(
                sessionId = command.sessionId,
                questionDocumentId = command.questionDocumentId,
                revisionNumber = command.revisionNumber,
                cycleOrdinal = command.cycleOrdinal,
                turnOrdinal = command.turnOrdinal,
                diagnosticStemMarkdown = command.diagnosticStemMarkdown,
                selectedChoiceId = command.selectedChoiceId,
                selectedChoiceMarkdown = command.selectedChoiceMarkdown,
                selectionWasCorrect = command.selectionWasCorrect,
                feedbackMarkdown = command.feedbackMarkdown,
                requestedMove = null,
                solutionRevealed = false,
                submittedAtEpochMillis = command.occurredAtEpochMillis,
                updatedAtEpochMillis = command.occurredAtEpochMillis,
            ).also { response ->
                responses.value = listOf(response)
            }

        override suspend fun recordMove(command: RecordTutorMoveCommand): TutorTurnResponse {
            moveCommand = command
            val current = responses.value.singleOrNull { response ->
                response.sessionId == command.sessionId &&
                    response.questionDocumentId == command.questionDocumentId &&
                    response.revisionNumber == command.revisionNumber &&
                    response.cycleOrdinal == command.cycleOrdinal &&
                    response.turnOrdinal == command.turnOrdinal
            }
            return (
                current?.copy(
                    requestedMove = command.requestedMove,
                    updatedAtEpochMillis = maxOf(
                        current.updatedAtEpochMillis,
                        command.occurredAtEpochMillis,
                    ),
                ) ?: action(command)
                ).also { response -> responses.value = listOf(response) }
        }

        override suspend fun revealSolution(command: RevealTutorSolutionCommand): TutorTurnResponse {
            revealCommand = command
            revealCommands += command
            writeOrder += "reveal"
            val revealed = revealResponse(
                sessionId = command.sessionId,
                questionDocumentId = command.questionDocumentId,
                revisionNumber = command.revisionNumber,
                cycleOrdinal = command.cycleOrdinal,
                turnOrdinal = command.turnOrdinal,
                occurredAtEpochMillis = command.occurredAtEpochMillis,
            )
            responses.value = listOf(revealed)
            return revealed
        }

        override suspend fun recordSolutionExposure(command: RecordTutorSolutionExposureCommand) {
            if (command.surfaceKind == TutorAnswerExposureSurfaceKind.PLAN_SOLUTION) {
                require(
                    responses.value.any { response ->
                        response.sessionId == command.sessionId &&
                            response.questionDocumentId == command.questionDocumentId &&
                            response.revisionNumber == command.revisionNumber &&
                            response.cycleOrdinal == command.cycleOrdinal &&
                            response.turnOrdinal == command.turnOrdinal &&
                            response.solutionRevealed
                    },
                ) { "A plan exposure requires the exact solution reveal first" }
            }
            exposureCommands += command
            writeOrder += "exposure"
            recordedExposureKeys += "${command.sessionId}:${command.cycleOrdinal}:${command.turnOrdinal}"
        }

        private fun revealResponse(
            sessionId: String,
            questionDocumentId: String,
            revisionNumber: Int,
            cycleOrdinal: Int,
            turnOrdinal: Int,
            occurredAtEpochMillis: Long,
        ): TutorTurnResponse {
            val current = responses.value.singleOrNull()
            return current?.copy(
                solutionRevealed = true,
                updatedAtEpochMillis = maxOf(current.updatedAtEpochMillis, occurredAtEpochMillis),
            ) ?: TutorTurnResponse(
                sessionId = sessionId,
                questionDocumentId = questionDocumentId,
                revisionNumber = revisionNumber,
                cycleOrdinal = cycleOrdinal,
                turnOrdinal = turnOrdinal,
                diagnosticStemMarkdown = null,
                selectedChoiceId = null,
                selectedChoiceMarkdown = null,
                selectionWasCorrect = null,
                feedbackMarkdown = null,
                solutionRevealed = true,
                submittedAtEpochMillis = occurredAtEpochMillis,
                updatedAtEpochMillis = occurredAtEpochMillis,
            )
        }

        private fun action(command: RecordTutorMoveCommand) = TutorTurnResponse(
            sessionId = command.sessionId,
            questionDocumentId = command.questionDocumentId,
            revisionNumber = command.revisionNumber,
            cycleOrdinal = command.cycleOrdinal,
            turnOrdinal = command.turnOrdinal,
            diagnosticStemMarkdown = null,
            selectedChoiceId = null,
            selectedChoiceMarkdown = null,
            selectionWasCorrect = null,
            feedbackMarkdown = null,
            requestedMove = command.requestedMove,
            submittedAtEpochMillis = command.occurredAtEpochMillis,
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        )
    }
}
