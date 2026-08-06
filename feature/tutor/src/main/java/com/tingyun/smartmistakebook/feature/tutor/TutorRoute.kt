package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LibraryAddCheck
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingyun.smartmistakebook.core.domain.AdaptiveDecision
import com.tingyun.smartmistakebook.core.domain.AdaptiveDecisionKind
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealRequest
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealResult
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmission
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.domain.TutorCapabilityDecision
import com.tingyun.smartmistakebook.core.domain.TutorCapabilityBlockReason
import com.tingyun.smartmistakebook.core.domain.TutorCapabilityGate
import com.tingyun.smartmistakebook.core.domain.TutorConversationLobbyPort
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoice
import com.tingyun.smartmistakebook.core.model.TutorChoiceEvaluation
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingFollowUp
import com.tingyun.smartmistakebook.core.ui.Divider
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkMuted
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.Jade
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.LocalModeLine
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.Paper
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.SmartDimens

@Composable
fun TutorRoute(
    isSaved: Boolean,
    onSave: suspend () -> Unit,
    showSaveAction: Boolean = true,
    practiceUnitId: String,
    teachingArtifact: VerifiedTeachingArtifact?,
    adaptiveDecision: AdaptiveDecision?,
    profile: StudyProfileOverview,
    onSubmitChoice: suspend (StudyChoiceSubmission) -> StudyChoiceSubmissionResult,
    onCancelChoiceSubmission: (String) -> Unit = {},
    onRevealAnswer: suspend (StudyAnswerRevealRequest) -> StudyAnswerRevealResult,
    onCapture: () -> Unit,
    onGallery: () -> Unit = onCapture,
    onChooseExisting: () -> Unit,
    onOpenCapabilitySettings: () -> Unit,
    onOpenMistakeNotebook: () -> Unit,
    onOpenProfile: () -> Unit,
    modelTasks: ScopedModelTaskPort,
    conversationLobby: TutorConversationLobbyPort,
    catalogEntries: List<StudyCatalogEntry>,
    capabilities: AppCapabilitySnapshot,
    explanationMode: TutorExplanationMode = TutorExplanationMode.DIRECT,
    onExplanationModeChange: (TutorExplanationMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (practiceUnitId.isBlank() && teachingArtifact == null) {
        TutorLobbyRoute(
            onCapture = onCapture,
            onGallery = onGallery,
            onChooseExisting = onChooseExisting,
            onOpenCapabilitySettings = onOpenCapabilitySettings,
            onOpenMistakeNotebook = onOpenMistakeNotebook,
            onOpenProfile = onOpenProfile,
            modelTasks = modelTasks,
            conversationLobby = conversationLobby,
            catalogEntries = catalogEntries,
            explanationMode = explanationMode,
            onExplanationModeChange = onExplanationModeChange,
            modifier = modifier,
        )
        return
    }
    val viewModel: TutorViewModel = viewModel()
    val requestExplanationModeChange: (TutorExplanationMode) -> Unit = { mode ->
        requestTutorExplanationModeChange(
            mode = mode,
            cancelPendingEvidence = {
                viewModel.useExplanationMode(
                    TutorExplanationMode.DIRECT,
                    onCancelChoiceSubmission,
                )
            },
            persistMode = onExplanationModeChange,
        )
    }
    LaunchedEffect(explanationMode) {
        viewModel.useExplanationMode(explanationMode, onCancelChoiceSubmission)
    }

    when (val decision = TutorCapabilityGate().evaluate(capabilities, teachingArtifact)) {
        is TutorCapabilityDecision.Available -> {
            val assessmentItem = adaptiveDecision
                ?.selectedAssessmentItemId
                ?.let { selectedId ->
                    decision.artifact.assessmentItems.firstOrNull { it.id == selectedId }
                }
            when {
                adaptiveDecision == null -> TutorAdaptivePauseScreen(
                    message = "先确认要讲的当前题，再开始讲解。",
                    detail = "讲解只围绕你提供或选择的题目，不会自动添加题目来测试你。",
                    onCapture = onCapture,
                    onOpenCapabilitySettings = onOpenCapabilitySettings,
                    modifier = modifier,
                )

                assessmentItem != null -> {
                    val presentationKey = tutorPresentationKey(
                        practiceUnitId = practiceUnitId,
                        artifactId = decision.artifact.id,
                        assessmentItemId = assessmentItem.id,
                    )
                    LaunchedEffect(presentationKey, isSaved, explanationMode) {
                        viewModel.synchronizePresentation(presentationKey, isSaved)
                        if (explanationMode == TutorExplanationMode.DIRECT) {
                            viewModel.recordDirectExposure()
                            viewModel.requestReveal(
                                assessmentItem = assessmentItem,
                                practiceUnitId = practiceUnitId,
                                reveal = onRevealAnswer,
                            )
                        }
                    }
                    if (!viewModel.isActivePresentation(presentationKey)) {
                        TutorAdaptivePauseScreen(
                            message = "正在切换到这道题，暂不沿用上一题状态。",
                            detail = "题目、作答和讲解准备完成后即可继续。",
                            onCapture = onCapture,
                            onOpenCapabilitySettings = onOpenCapabilitySettings,
                            modifier = modifier,
                        )
                    } else {
                        TutorScreen(
                            artifact = decision.artifact,
                            assessmentItem = assessmentItem,
                            isSaved = isSaved || viewModel.saveStatus == TutorSaveStatus.SAVED,
                            showSaveAction = showSaveAction,
                            saveStatus = viewModel.saveStatus,
                            submissionStatus = viewModel.submissionStatus,
                            revealStatus = viewModel.revealStatus,
                            selectedChoiceId = viewModel.selectedChoiceFor(assessmentItem),
                            submittedChoiceId = viewModel.submittedChoiceFor(assessmentItem),
                            selectedFollowUpId = viewModel.selectedFollowUpId,
                            revealedExplanation = viewModel.revealedExplanation,
                            adaptationMessage = adaptationMessage(adaptiveDecision, profile),
                            draft = viewModel.draft,
                            unverifiedQuestionNoticeVisible = viewModel.unverifiedQuestionNoticeVisible,
                            onSave = { viewModel.requestSave(onSave) },
                            onCapture = onCapture,
                            onGallery = onGallery,
                            onChooseExisting = onChooseExisting,
                            onOpenCapabilitySettings = onOpenCapabilitySettings,
                            onChoice = { choiceId ->
                                viewModel.selectChoice(assessmentItem, choiceId)
                            },
                            onSubmit = {
                                viewModel.requestSubmit(
                                    assessmentItem = assessmentItem,
                                    practiceUnitId = practiceUnitId,
                                    submit = onSubmitChoice,
                                )
                            },
                            onReveal = {
                                viewModel.requestReveal(
                                    assessmentItem = assessmentItem,
                                    practiceUnitId = practiceUnitId,
                                    reveal = onRevealAnswer,
                                )
                            },
                            onFollowUp = { followUpId ->
                                viewModel.requestReveal(
                                    assessmentItem = assessmentItem,
                                    practiceUnitId = practiceUnitId,
                                    reveal = onRevealAnswer,
                                    followUpId = followUpId,
                                )
                            },
                            onDraftChange = viewModel::updateDraft,
                            onSendDraft = viewModel::submitDraft,
                            explanationMode = explanationMode,
                            onExplanationModeChange = requestExplanationModeChange,
                            modifier = modifier,
                        )
                    }
                }

                else -> TutorAdaptivePauseScreen(
                    message = adaptivePauseMessage(adaptiveDecision.kind),
                    detail = adaptivePauseDetail(adaptiveDecision.kind),
                    onCapture = onCapture,
                    onOpenCapabilitySettings = onOpenCapabilitySettings,
                    modifier = modifier,
                )
            }
        }

        is TutorCapabilityDecision.Blocked -> TutorUnavailableScreen(
            reason = decision.reason,
            onOpenCapabilitySettings = onOpenCapabilitySettings,
            modifier = modifier,
        )
    }
}

@Composable
internal fun TutorStartEmptyState(
    onCapture: () -> Unit,
    onChooseExisting: () -> Unit,
    onOpenCapabilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RootPageColumn(modifier = modifier.testTag("tutor_screen")) {
        TutorTopBar(onOpenCapabilitySettings = onOpenCapabilitySettings)
        LocalModeLine(text = "从你要讲的题开始")
        PaperDivider(Modifier.padding(top = 2.dp, bottom = 16.dp))
        TutorPrompt(
            text = "拍下、上传或选择一道已有题目，我会围绕这道题讲解。",
            modifier = Modifier.testTag("tutor_empty_state"),
        )
        PrimaryActionButton(
            text = "拍题讲解",
            onClick = onCapture,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .testTag("tutor_capture_button"),
            contentDescription = "拍照或选择题目图片开始讲解",
        )
        OutlineActionChip(
            text = "从错题本选择",
            onClick = onChooseExisting,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .testTag("tutor_choose_existing_button"),
            contentDescription = "从错题本选择已有题目",
        )
    }
}

@Composable
private fun TutorAdaptivePauseScreen(
    message: String,
    detail: String,
    onCapture: () -> Unit,
    onOpenCapabilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RootPageColumn(modifier = modifier.testTag("tutor_screen")) {
        TutorTopBar(onOpenCapabilitySettings = onOpenCapabilitySettings)
        PaperDivider(Modifier.padding(top = 2.dp, bottom = 16.dp))
        TutorPrompt(
            text = message,
            modifier = Modifier.testTag("tutor_adaptive_pause"),
        )
        Text(
            text = detail,
            modifier = Modifier.padding(top = 10.dp),
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        PrimaryActionButton(
            text = "拍一道需要讲解的题",
            onClick = onCapture,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .testTag("tutor_capture_button"),
            contentDescription = "拍照上传一道需要讲解的题",
        )
    }
}

@Composable
private fun TutorUnavailableScreen(
    reason: TutorCapabilityBlockReason,
    onOpenCapabilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RootPageColumn(modifier = modifier.testTag("tutor_screen")) {
        TutorTopBar(
            onOpenCapabilitySettings = onOpenCapabilitySettings,
        )
        PaperDivider(Modifier.padding(top = 2.dp, bottom = 16.dp))
        Text(
            text = when (reason) {
                TutorCapabilityBlockReason.TUTOR_DISABLED_BY_BUILD -> "当前版本未启用讲题能力。"
                TutorCapabilityBlockReason.NO_VERIFIED_TEACHING_ARTIFACT -> "当前题目暂时无法讲解。"
                TutorCapabilityBlockReason.NO_ASSESSMENT_ITEM -> "当前讲题内容没有可作答的问题。"
            },
            modifier = Modifier.testTag("tutor_unavailable_notice"),
            color = InkSecondary,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun TutorScreen(
    artifact: VerifiedTeachingArtifact,
    assessmentItem: TutorAssessmentItem,
    isSaved: Boolean,
    showSaveAction: Boolean,
    saveStatus: TutorSaveStatus,
    submissionStatus: TutorSubmissionStatus,
    revealStatus: TutorRevealStatus,
    selectedChoiceId: String?,
    submittedChoiceId: String?,
    selectedFollowUpId: String?,
    revealedExplanation: String?,
    adaptationMessage: String,
    draft: String,
    unverifiedQuestionNoticeVisible: Boolean,
    onSave: () -> Unit,
    onCapture: () -> Unit,
    onGallery: () -> Unit,
    onChooseExisting: () -> Unit,
    onOpenCapabilitySettings: () -> Unit,
    onChoice: (String) -> Unit,
    onSubmit: () -> Unit,
    onReveal: () -> Unit,
    onFollowUp: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onSendDraft: () -> Unit,
    explanationMode: TutorExplanationMode,
    onExplanationModeChange: (TutorExplanationMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper)
            .imePadding(),
    ) {
        RootPageColumn(
            modifier = Modifier
                .weight(1f)
                .testTag("tutor_screen"),
        ) {
        TutorTopBar(
            onOpenCapabilitySettings = onOpenCapabilitySettings,
        )
        PaperDivider(Modifier.padding(top = 2.dp, bottom = 8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.AutoStories,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = JadeActive,
            )
            Text(
                text = adaptationMessage,
                modifier = Modifier.padding(start = 8.dp),
                color = JadeActive,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionHeader(
            title = artifact.title,
            modifier = Modifier.padding(top = 8.dp),
            action =
                if (shouldShowTutorSaveAction(showSaveAction)) {
                    {
                        OutlineActionChip(
                            text = when {
                                isSaved -> "已存入"
                                saveStatus == TutorSaveStatus.SAVING -> "保存中"
                                saveStatus == TutorSaveStatus.FAILED -> "重试保存"
                                else -> "存入错题本"
                            },
                            onClick = onSave,
                            enabled = !isSaved && saveStatus != TutorSaveStatus.SAVING,
                            icon = Icons.Outlined.LibraryAddCheck,
                            modifier = Modifier.testTag("tutor_save_button"),
                            contentDescription = when {
                                isSaved -> "本题已存入错题本"
                                saveStatus == TutorSaveStatus.SAVING -> "本题正在保存"
                                saveStatus == TutorSaveStatus.FAILED ->
                                    "保存失败，重试存入错题本"
                                else -> "将本题存入错题本"
                            },
                        )
                    }
                } else {
                    null
                },
        )

        if (showSaveAction && saveStatus == TutorSaveStatus.FAILED) {
            Text(
                text = "保存失败，请检查本机存储后重试。",
                modifier = Modifier
                    .padding(top = 6.dp)
                    .testTag("tutor_save_error"),
                color = ErrorWarm,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (explanationMode == TutorExplanationMode.DIRECT) {
            TutorPrompt(
                text = artifact.explanationMarkdown,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .testTag("tutor_full_explanation"),
            )
        } else {
            SafeMarkdownText(
                markdown = assessmentItem.stemMarkdown,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                color = Ink,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
            )
            PaperDivider()

            TutorPrompt(
                text = assessmentItem.promptMarkdown ?: artifact.explanationMarkdown,
                modifier = Modifier.padding(top = 10.dp),
            )

            Column(
                modifier = Modifier.padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                assessmentItem.choices.forEach { option ->
                    TutorChoiceRow(
                        option = option,
                        isCorrect = assessmentItem.evaluateChoice(option.id).isCorrect,
                        selectedChoiceId = selectedChoiceId,
                        submittedChoiceId = submittedChoiceId,
                        enabled = submissionStatus == TutorSubmissionStatus.IDLE &&
                            revealStatus != TutorRevealStatus.RECORDING,
                        onClick = { onChoice(option.id) },
                    )
                }
            }

            if (submittedChoiceId == null) {
            PrimaryActionButton(
                text = when {
                    submissionStatus == TutorSubmissionStatus.RECORDING -> "正在提交答案"
                    submissionStatus == TutorSubmissionStatus.FAILED -> "重新提交答案"
                    revealStatus == TutorRevealStatus.REVEALED -> "提交已选答案（已看讲解）"
                    else -> "提交答案"
                },
                onClick = onSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .testTag("tutor_submit_answer"),
                enabled = selectedChoiceId != null &&
                    submissionStatus != TutorSubmissionStatus.RECORDING &&
                    revealStatus != TutorRevealStatus.RECORDING,
                contentDescription = if (selectedChoiceId == null) {
                    "请先选择一个答案"
                } else {
                    "提交当前选择"
                },
            )
            }

        if (submissionStatus == TutorSubmissionStatus.RECORDING) {
            Text(
                text = "正在把本次选择写入本机学习记录…",
                modifier = Modifier.padding(top = 8.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (submissionStatus == TutorSubmissionStatus.FAILED) {
            Text(
                text = "本次答案尚未确认写入。为保证安全重试，当前选择已锁定；请点击“重新提交答案”。",
                modifier = Modifier.padding(top = 8.dp),
                color = ErrorWarm,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (submittedChoiceId != null) {
            ChoiceExplanation(
                evaluation = assessmentItem.evaluateChoice(submittedChoiceId),
                fallbackExplanation = "本次选择已记录。你可以继续追问，或在确认后查看完整讲解。",
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        OutlineActionChip(
            text = when (revealStatus) {
                TutorRevealStatus.HIDDEN -> "查看完整讲解"
                TutorRevealStatus.RECORDING -> "正在记录并打开"
                TutorRevealStatus.REVEALED -> "完整讲解已打开"
                TutorRevealStatus.FAILED -> "重试打开讲解"
            },
            onClick = onReveal,
            enabled = revealStatus != TutorRevealStatus.RECORDING &&
                revealStatus != TutorRevealStatus.REVEALED &&
                submissionStatus != TutorSubmissionStatus.RECORDING,
            icon = Icons.Outlined.AutoStories,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("tutor_reveal_answer"),
            contentDescription = "记录查看答案并打开完整讲解",
        )

        if (revealStatus == TutorRevealStatus.FAILED) {
            Text(
                text = "讲解尚未安全记录，因此暂未显示。请重试。",
                modifier = Modifier.padding(top = 6.dp),
                color = ErrorWarm,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (revealedExplanation != null) {
            TutorPrompt(
                text = revealedExplanation,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("tutor_full_explanation"),
            )
        }

        if (submittedChoiceId != null || revealedExplanation != null) {
            DynamicFollowUps(
                artifact = artifact,
                assessmentItem = assessmentItem,
                evaluation = submittedChoiceId?.let(assessmentItem::evaluateChoice),
                selectedFollowUpId = selectedFollowUpId,
                onFollowUp = onFollowUp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

            if (selectedFollowUpId != null) {
            FollowUpExplanation(
                artifact = artifact,
                actionId = selectedFollowUpId,
                modifier = Modifier.padding(top = 8.dp),
            )
            }
        }

            if (unverifiedQuestionNoticeVisible) {
                UnverifiedQuestionNotice(Modifier.padding(top = 10.dp))
            }
        }

        PaperDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Paper),
            contentAlignment = Alignment.TopCenter,
        ) {
            TutorComposer(
                value = draft,
                onValueChange = onDraftChange,
                onCapture = onCapture,
                onGallery = onGallery,
                onChooseExisting = onChooseExisting,
                onSend = onSendDraft,
                explanationMode = explanationMode,
                onExplanationModeChange = onExplanationModeChange,
                modifier = Modifier
                    .widthIn(max = SmartDimens.MaximumContentWidth)
                    .padding(
                        horizontal = SmartDimens.ContentHorizontalPadding,
                        vertical = 8.dp,
                    ),
            )
        }
    }
}

@Composable
internal fun TutorTopBar(
    onOpenCapabilitySettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = SmartDimens.MinimumTouchTarget)
            .semantics {
                heading()
                contentDescription = "讲题"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onOpenCapabilitySettings,
            modifier = Modifier.testTag("tutor_capability_settings_button"),
        ) {
            Icon(Icons.Outlined.Tune, contentDescription = "讲题能力设置", tint = Ink)
        }
    }
}

@Composable
internal fun TutorPrompt(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(JadeSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoStories,
                contentDescription = "智能讲题助手",
                modifier = Modifier.size(21.dp),
                tint = JadeActive,
            )
        }
        SafeMarkdownText(
            markdown = text,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
                .border(1.dp, Divider, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = Ink,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TutorChoiceRow(
    option: TutorChoice,
    isCorrect: Boolean,
    selectedChoiceId: String?,
    submittedChoiceId: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val isSubmitted = submittedChoiceId != null
    val isSelected = selectedChoiceId == option.id
    val isSubmittedSelection = submittedChoiceId == option.id
    val isCorrectSelection = isSubmittedSelection && isCorrect
    val borderColor = when {
        isCorrectSelection -> JadeActive
        isSubmittedSelection -> ErrorWarm
        isSelected -> JadeActive
        else -> Outline
    }
    val background = if (isSelected) JadeSoft.copy(alpha = 0.55f) else Color.Transparent
    val stateLabel = when {
        isCorrectSelection -> "已提交，回答正确"
        isSubmittedSelection -> "已提交，需要修正"
        isSubmitted -> "未选择"
        !enabled && isSelected -> "已选中，当前选择已锁定"
        !enabled -> "当前不可选择"
        isSelected -> "已选中，尚未提交"
        else -> "可选择"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled && !isSubmitted, role = Role.RadioButton, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                selected = isSelected
                stateDescription = stateLabel
                if (isSubmitted || !enabled) disabled()
            }
            .testTag("tutor_choice_${option.id.lowercase()}")
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .border(1.dp, if (isSelected) borderColor else JadeActive, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = option.id,
                color = if (isSubmittedSelection && !isCorrect) ErrorWarm else JadeActive,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        SafeMarkdownText(
            markdown = option.markdown,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
            color = Ink,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ChoiceExplanation(
    evaluation: TutorChoiceEvaluation,
    fallbackExplanation: String,
    modifier: Modifier = Modifier,
) {
    val correct = evaluation.isCorrect
    val text = evaluation.choice.feedbackMarkdown ?: fallbackExplanation
    val tint = if (correct) JadeActive else ErrorWarm

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(JadeSoft.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (correct) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = tint,
        )
        SafeMarkdownText(
            markdown = text,
            modifier = Modifier.padding(start = 8.dp),
            color = Ink,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DynamicFollowUps(
    artifact: VerifiedTeachingArtifact,
    assessmentItem: TutorAssessmentItem,
    evaluation: TutorChoiceEvaluation?,
    selectedFollowUpId: String?,
    onFollowUp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = followUpsFor(artifact, assessmentItem, evaluation)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.forEach { action ->
            OutlineActionChip(
                text = followUpLabel(action, evaluation),
                onClick = { onFollowUp(action.id) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("tutor_follow_up_${action.id}"),
                icon = followUpIcon(action.id),
                selected = selectedFollowUpId == action.id,
                contentDescription = "继续追问：${action.label}",
            )
        }
    }
}

@Composable
private fun FollowUpExplanation(
    artifact: VerifiedTeachingArtifact,
    actionId: String,
    modifier: Modifier = Modifier,
) {
    val text = artifact.followUps.firstOrNull { it.id == actionId }?.contentMarkdown ?: return
    TutorPrompt(text = text, modifier = modifier.testTag("tutor_follow_up_explanation"))
}

@Composable
internal fun TutorComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onCapture: () -> Unit,
    onGallery: () -> Unit = onCapture,
    onChooseExisting: () -> Unit = {},
    onSend: () -> Unit,
    explanationMode: TutorExplanationMode = TutorExplanationMode.DIRECT,
    onExplanationModeChange: (TutorExplanationMode) -> Unit = {},
    modifier: Modifier = Modifier,
    placeholder: String = "输入你的推导、困惑或新问题",
    enabled: Boolean = true,
) {
    val composerHeight = if (LocalDensity.current.fontScale <= 1f) {
        Modifier.height(SmartDimens.ComposerHeight)
    } else {
        Modifier.heightIn(min = SmartDimens.ComposerHeight)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(composerHeight)
                .testTag("tutor_draft_input"),
            placeholder = { Text(placeholder) },
            enabled = enabled,
            singleLine = true,
            trailingIcon = {
                IconButton(
                    onClick = onSend,
                    enabled = enabled && value.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("tutor_send_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "提交输入",
                        tint = if (value.isBlank()) InkMuted else Jade,
                    )
                }
            },
            shape = RoundedCornerShape(8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onCapture,
                enabled = enabled,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("tutor_capture_button"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = "拍题讲解",
                    tint = Jade,
                )
            }
            IconButton(
                onClick = onGallery,
                enabled = enabled,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("tutor_gallery_button"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoLibrary,
                    contentDescription = "从相册选择题目",
                    tint = Jade,
                )
            }
            IconButton(
                onClick = onChooseExisting,
                enabled = enabled,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("tutor_library_button"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoStories,
                    contentDescription = "从错题本选择",
                    tint = Jade,
                )
            }
            Spacer(Modifier.weight(1f))
            TutorGuidanceModeControl(
                mode = explanationMode,
                onModeChange = onExplanationModeChange,
            )
        }
    }
}

internal fun shouldShowTutorSaveAction(showSaveAction: Boolean): Boolean = showSaveAction

@Composable
private fun UnverifiedQuestionNotice(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, ErrorWarm, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag("tutor_unverified_notice"),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = ErrorWarm,
        )
        Text(
            text = "先拍题或从错题本选择，我才能准确讲解。你的草稿已保留。",
            modifier = Modifier.padding(start = 8.dp),
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun followUpsFor(
    artifact: VerifiedTeachingArtifact,
    assessmentItem: TutorAssessmentItem,
    evaluation: TutorChoiceEvaluation?,
): List<VerifiedTeachingFollowUp> {
    val followUpIds = evaluation?.choice?.followUpIds ?: assessmentItem.initialFollowUpIds
    return followUpIds.map(artifact::requireFollowUp)
}

private fun followUpLabel(
    followUp: VerifiedTeachingFollowUp,
    evaluation: TutorChoiceEvaluation?,
): String = when {
    evaluation?.isCorrect == true && followUp.id == "alternate" -> "换方法"
    evaluation != null && followUp.id == "sign_table" -> "符号表"
    evaluation != null && followUp.id == "explain_reason" -> "说理由"
    else -> followUp.label
}

private fun followUpIcon(followUpId: String): ImageVector = when (followUpId) {
    "alternate", "factor_focus", "function_vs_derivative" -> Icons.Outlined.Shuffle
    "sign_table", "nearby_points" -> Icons.Outlined.TableChart
    else -> Icons.Outlined.Edit
}

private fun adaptationMessage(
    decision: AdaptiveDecision,
    profile: StudyProfileOverview,
): String = when {
    decision.kind == AdaptiveDecisionKind.ASK_CALIBRATION ->
        "从当前题的关键步骤开始"
    !profile.projectionIsCurrent ->
        "围绕当前题继续讲解"
    else ->
        "先看当前题里最关键的一步"
}

internal fun tutorPresentationKey(
    practiceUnitId: String,
    artifactId: String,
    assessmentItemId: String,
): String = buildString {
    listOf(practiceUnitId, artifactId, assessmentItemId).forEach { part ->
        append(part.length)
        append(':')
        append(part)
    }
}

private fun adaptivePauseMessage(kind: AdaptiveDecisionKind): String = when (kind) {
    AdaptiveDecisionKind.SKIP_MASTERED_FOUNDATION ->
        "当前题里的基础步骤可以略过，直接看关键部分。"
    AdaptiveDecisionKind.CLARIFY_OR_CONFIRM ->
        "先确认当前题的题面，再继续讲解。"
    AdaptiveDecisionKind.NO_SAFE_CANDIDATE ->
        "本轮不另外添加问题，继续围绕当前题。"
    AdaptiveDecisionKind.ASK,
    AdaptiveDecisionKind.ASK_CALIBRATION,
    -> "选题结果与可信题目不一致，本轮先暂停提问。"
}

private fun adaptivePauseDetail(kind: AdaptiveDecisionKind): String = when (kind) {
    AdaptiveDecisionKind.SKIP_MASTERED_FOUNDATION ->
        "你可以直接指定这道题里想看的步骤或方法。"
    AdaptiveDecisionKind.CLARIFY_OR_CONFIRM ->
        "题面不完整，请重新拍摄这道题。"
    AdaptiveDecisionKind.NO_SAFE_CANDIDATE ->
        "你可以查看完整讲解、换一种方法，或指出当前题里卡住的步骤。"
    AdaptiveDecisionKind.ASK,
    AdaptiveDecisionKind.ASK_CALIBRATION,
    -> "请稍后重试，或拍照上传需要讲解的题。"
}
