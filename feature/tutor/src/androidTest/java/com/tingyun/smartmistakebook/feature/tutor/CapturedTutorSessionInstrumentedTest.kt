package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.AppendTutorStudentMessageCommand
import com.tingyun.smartmistakebook.core.domain.LobbyMessageImage
import com.tingyun.smartmistakebook.core.domain.LobbyMessageImageIntake
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyQuestionMemory
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureSurfaceKind
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.TutorConversationIds
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorConceptMapScene
import com.tingyun.smartmistakebook.core.model.TutorConceptRelation
import com.tingyun.smartmistakebook.core.model.TutorFormulaDerivationScene
import com.tingyun.smartmistakebook.core.model.TutorFormulaDerivationStep
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorProcessStage
import com.tingyun.smartmistakebook.core.model.TutorProcessTimelineScene
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorSceneEmphasis
import com.tingyun.smartmistakebook.core.model.TutorSceneStep
import com.tingyun.smartmistakebook.core.model.TutorStepFlowScene
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.TutorVisualSceneRenderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapturedTutorSessionInstrumentedTest : CapturedTutorSessionTestBase() {
    @Test
    fun tutorEmptyStateOffersRealConversationCaptureAndInPageQuestionPicker() {
        var captureRequests = 0
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
        // 「从错题本选择」不再跳去错题本页：它在**这个页面里**打开选择器，挑中的题作为
        // 这一轮要讲的那道题进同一个页面（详见 TutorMistakePickerDialog）。这里断言
        // 选择器确实打开了、并且没有离开讲题页。
        composeRule.onNodeWithTag("tutor_choose_existing_button").performClick()
        composeRule.onNodeWithTag("lobby_picker_empty").assertExists()
        composeRule.onNodeWithTag("lobby_picker_cancel").performClick()
        composeRule.onNodeWithTag("tutor_screen").assertExists()
        // 大厅与会话共用一个输入区（`TutorChatComposer`）：同一屏里输入框与发送键只有一套标签。
        composeRule.onNodeWithTag("tutor_chat_composer").performTextInput("我想问一下这一步")
        composeRule.onNodeWithTag("tutor_chat_send").performClick()
        composeRule.runOnIdle {
            assertEquals(1, captureRequests)
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
    fun aStudentTextTurnIsPersistedBeforeDispatchSoTheLocalAnchorCheckCanSeeIt() {
        // 写侧门控核对"模型有没有逐字引用学生的话"时读的是 tutor_message 的 STUDENT 行。
        // 学生文字必须真的落库——否则纯文字（开放式）作答的语料恒为空，MASTERED 机械不可达，
        // 提示词里"引文会被本地逐条比对"也形同虚设。
        val session = session()
        val modelTasks = ChatModelTaskRepository(session)
        val interactions = RecordingTutorInteractions()
        val recordedStudentMessages = mutableListOf<AppendTutorStudentMessageCommand>()
        val exactMessage = "我觉得先把两边同时开方"

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
                    conversations = emptyConversations(recordedStudentMessages),
                    profile = StudyProfileOverview(),
                    onOpenModelSettings = {},
                )
            }
        }

        composeRule.onNodeWithTag("tutor_chat_composer").performTextInput(exactMessage)
        composeRule.onNodeWithTag("tutor_chat_send").assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            modelTasks.respondTasks.value.singleOrNull()?.status == ModelTaskStatus.SUCCEEDED
        }

        val command = recordedStudentMessages.single()
        assertEquals(TutorConversationIds.captured(session.sessionId), command.conversationId)
        assertEquals(exactMessage, command.bodyMarkdown)
        // 学生第 n 轮固定落在第 2n-1 条：序号由请求自身决定，恢复重放不会因"当前最大 +1"漂移。
        assertEquals(1, command.ordinal)
        assertTrue(command.messageId.startsWith("tutor-message:"))
    }

    /**
     * 会话页的附图入口只在资产读取器接线时出现。
     *
     * 这条钉的是接线本身：`imageIntake` 从 App 一路穿过
     * `CapturedTutorSessionRoute` → `CapturedTutorSessionContent` → `ReadyCapturedSession`
     * → `TutorModelPanel` 才到输入框。任何一层漏传，入口都会消失——而一个消失的入口
     * 在真机上表现成"学生说好的附图功能不见了"，不会报错。
     */
    @Test
    fun theSessionOffersTheImageEntryOnlyWhenAnIntakeIsWired() {
        val session = session()
        val intakeState = mutableStateOf<LobbyMessageImageIntake?>(null)

        composeRule.setContent {
            MaterialTheme {
                ReadyCapturedSession(
                    session = session,
                    clock = { 10_000L },
                    saveInProgress = false,
                    saveError = null,
                    onSave = {},
                    modelTasks = ChatModelTaskRepository(session),
                    interactions = RecordingTutorInteractions(),
                    conversations = emptyConversations(),
                    profile = StudyProfileOverview(),
                    imageIntake = intakeState.value,
                    onOpenModelSettings = {},
                )
            }
        }

        composeRule.onNodeWithTag("tutor_chat_attach").assertDoesNotExist()

        composeRule.runOnIdle { intakeState.value = RecordingImageIntake() }

        composeRule.onNodeWithTag("tutor_chat_attach").assertExists()
    }

    /**
     * 会话页只把读取器用于「发送时登记」与「气泡回显」；这条测试只关心入口是否接线，
     * 所以登记返回一个固定的资产引用，不触碰真实资产库。
     */
    private class RecordingImageIntake : LobbyMessageImageIntake {
        override suspend fun registerImage(
            localUri: String,
            occurredAtEpochMillis: Long,
        ): LobbyMessageImage = LobbyMessageImage(
            assetId = "asset-session-test",
            sha256 = "a".repeat(64),
            byteSize = 1,
            width = 1,
            height = 1,
        )

        override suspend fun resolveImageUri(assetId: String): String? = null

        /** 这条测试不碰真实资产库，因此没有"已登记资产"的元数据可读。 */
        override suspend fun describeImage(assetId: String): LobbyMessageImage? = null
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
}
