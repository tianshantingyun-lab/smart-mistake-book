package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.LobbyMessageImageIntake
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.ConfirmedMistakeOrganization
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyQuestionMemory
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorSessionProblemAnchor
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import com.tingyun.smartmistakebook.core.model.AttachedImage
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.TutorDebriefOutput
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.LocalModeLine
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.StructuredContentRenderer
import com.tingyun.smartmistakebook.core.ui.studentSubjectLabel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun SavedMistakeTutorRoute(
    key: MistakeRevisionKey,
    repository: MistakeDetailRepository,
    organizationRepository: MistakeOrganizationRepository,
    teachingReferenceRepository: TutorTeachingReferenceRepository,
    modelTasks: ModelTaskRepository,
    interactions: TutorInteractionRepository,
    /** 学生文字落库用；缺省 null 时该界面不落库（门控按空语料 fail-closed）。 */
    conversations: TutorConversationRepository? = null,
    catalogEntries: List<StudyCatalogEntry> = emptyList(),
    profile: StudyProfileOverview,
    learningMemory: StudyQuestionMemory? = null,
    /** 学生消息附图的资产读取器；null 时会话页不提供附图入口。 */
    imageIntake: LobbyMessageImageIntake? = null,
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenModelSettings: () -> Unit,
    /** 讲题历史入口；与大厅、拍照会话共用同一个页面标题栏，所以三个入口都有它。 */
    onOpenHistory: (() -> Unit)? = null,
    onBack: () -> Unit,
    /** Silent teaching-focus persistence (three-store loop); no UI surface. */
    onRecordTeachingFocus: (sessionId: String, practiceUnitId: String, labels: List<String>) -> Unit = { _, _, _ -> },
    /** Silent misconception debrief request on session exit; no UI surface. */
    onRequestDebrief: (sessionId: String, practiceUnitId: String, stemMarkdown: String, transcriptMarkdown: String, labels: List<String>) -> Unit = { _, _, _, _, _ -> },
    /** Silent misconception advisory write when a debrief completes. */
    onRecordMisconception: (sessionId: String, practiceUnitId: String, payloadMarkdown: String) -> Unit = { _, _, _ -> },
    /** Stored advisories injected into the tutor prompt (read side of the loop). */
    priorTeachingAdvisories: List<String> = emptyList(),
    /**
     * 模型要求的配图（重绘题面 / 生成过程图）的解析器。
     * null 时会话页里这类图**整段不渲染**——错题讲题页此前拿不到它，只有拍照会话传了。
     */
    attachedImageResolver: (suspend (AttachedImage) -> String?)? = null,
    /**
     * 意图确认按钮的动作。默认值都指向本页真实存在的动作，不留空实现：
     * 这道题已经在错题本里，「确认加入错题本」打开错题本（它就在里面）；
     * 「确认结束且不保存」离开这次讲题（题与对话都已经保存，页面不会谎称丢弃了什么）。
     */
    onRequestSave: () -> Unit = onOpenMistakeNotebook,
    onRequestEnd: () -> Unit = onBack,
    modifier: Modifier = Modifier,
) {
    val stateFlow: Flow<MistakeDetailState> = remember(key, repository) {
        repository.observeExact(key)
    }
    val state by stateFlow.collectAsStateWithLifecycle(MistakeDetailState.Loading)
    val organizationFlow: Flow<ConfirmedMistakeOrganization?> = remember(key, organizationRepository) {
        organizationRepository.observeConfirmed(key)
    }
    val organization by organizationFlow.collectAsStateWithLifecycle(initialValue = null)
    val teachingReferences by produceState<List<TutorTeachingReference>>(
        initialValue = emptyList(),
        key1 = organization,
        key2 = state,
        key3 = teachingReferenceRepository,
    ) {
        val ready = state as? MistakeDetailState.Ready
        val confirmed = organization
        value = if (ready == null || confirmed == null) {
            emptyList()
        } else {
            try {
                teachingReferenceRepository.referencesFor(
                    subject = ready.detail.identity.subject,
                    knowledgeNodeIds = confirmed.knowledgeNodeIds,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    when (val current = state) {
        is MistakeDetailState.Ready -> if (organization == null) {
            TutorConversationFrame(
                header = {
                    TutorPageHeader(
                        onOpenCapabilitySettings = onOpenModelSettings,
                        onOpenHistory = onOpenHistory,
                        onBack = onBack,
                    )
                },
                autoScrollVersion = current,
                modifier = modifier.testTag("saved_mistake_tutor_screen"),
            ) {
                item("saved_mistake_organization_loading") { LoadingTutorQuestion() }
            }
        } else {
            SavedMistakeTutorContent(
                state = current,
                modelTasks = modelTasks,
                interactions = interactions,
                conversations = conversations,
                catalogEntries = catalogEntries,
                profile = profile,
                learningMemory = learningMemory,
                imageIntake = imageIntake,
                relatedKnowledgeNodeIds = requireNotNull(organization).knowledgeNodeIds,
                reviewedTeachingReferences = teachingReferences,
                onOpenMistakeNotebook = onOpenMistakeNotebook,
                onOpenProfile = onOpenProfile,
                onOpenModelSettings = onOpenModelSettings,
                onOpenHistory = onOpenHistory,
                onBack = onBack,
                onRecordTeachingFocus = onRecordTeachingFocus,
                onRequestDebrief = onRequestDebrief,
                onRecordMisconception = onRecordMisconception,
                priorTeachingAdvisories = priorTeachingAdvisories,
                attachedImageResolver = attachedImageResolver,
                onRequestSave = onRequestSave,
                onRequestEnd = onRequestEnd,
                modifier = modifier.testTag("saved_mistake_tutor_screen"),
            )
        }

        else -> TutorConversationFrame(
            header = {
                TutorPageHeader(
                    onOpenCapabilitySettings = onOpenModelSettings,
                    onOpenHistory = onOpenHistory,
                    onBack = onBack,
                )
            },
            autoScrollVersion = current,
            modifier = modifier.testTag("saved_mistake_tutor_screen"),
        ) {
            item("saved_mistake_non_ready") {
                when (current) {
                    MistakeDetailState.Loading -> LoadingTutorQuestion()
                    is MistakeDetailState.Legacy -> TutorQuestionUnavailable(
                        title = "这道题需要重新拍摄",
                        detail = "旧题面不够完整，重新拍摄后即可讲解。",
                        onRetry = onBack,
                    )
                    is MistakeDetailState.CorruptSnapshot -> TutorQuestionUnavailable(
                        title = "题面需要重新上传",
                        detail = "这道题保存得不完整，重新拍摄后即可讲解。",
                        onRetry = onBack,
                    )
                    MistakeDetailState.NotFound -> TutorQuestionUnavailable(
                        title = "没有找到这道错题",
                        detail = "它可能已归档、删除或切换到了新的修订。",
                        onRetry = onBack,
                    )
                    is MistakeDetailState.Ready -> Unit
                }
            }
        }
    }
}

@Composable
internal fun SavedMistakeTutorContent(
    state: MistakeDetailState.Ready,
    modelTasks: ModelTaskRepository,
    interactions: TutorInteractionRepository,
    /** 学生文字落库用；缺省 null 时该界面不落库（门控按空语料 fail-closed）。 */
    conversations: TutorConversationRepository? = null,
    catalogEntries: List<StudyCatalogEntry> = emptyList(),
    profile: StudyProfileOverview,
    learningMemory: StudyQuestionMemory?,
    imageIntake: LobbyMessageImageIntake? = null,
    relatedKnowledgeNodeIds: Set<String> = emptySet(),
    reviewedTeachingReferences: List<TutorTeachingReference> = emptyList(),
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenModelSettings: () -> Unit,
    onOpenHistory: (() -> Unit)? = null,
    onBack: () -> Unit = {},
    /** Silent teaching-focus persistence (three-store loop); no UI surface. */
    onRecordTeachingFocus: (sessionId: String, practiceUnitId: String, labels: List<String>) -> Unit = { _, _, _ -> },
    /** Silent misconception debrief request on session exit; no UI surface. */
    onRequestDebrief: (sessionId: String, practiceUnitId: String, stemMarkdown: String, transcriptMarkdown: String, labels: List<String>) -> Unit = { _, _, _, _, _ -> },
    /** Silent misconception advisory write when a debrief completes. */
    onRecordMisconception: (sessionId: String, practiceUnitId: String, payloadMarkdown: String) -> Unit = { _, _, _ -> },
    /** Stored advisories injected into the tutor prompt (read side of the loop). */
    priorTeachingAdvisories: List<String> = emptyList(),
    /** 模型要求的配图解析器；null 时会话页不渲染这类图（见 [SavedMistakeTutorRoute]）。 */
    attachedImageResolver: (suspend (AttachedImage) -> String?)? = null,
    /** 意图确认按钮的动作（见 [SavedMistakeTutorRoute]）。 */
    onRequestSave: () -> Unit = onOpenMistakeNotebook,
    onRequestEnd: () -> Unit = onBack,
    clock: () -> Long = System::currentTimeMillis,
    modifier: Modifier = Modifier,
) {
    val question = remember(
        state.detail.identity,
        state.detail.tutorConversation,
        state.questionDocument,
        learningMemory,
        relatedKnowledgeNodeIds,
        reviewedTeachingReferences,
    ) {
        savedMistakeTutorQuestion(
            state = state,
            learningMemory = learningMemory,
            relatedKnowledgeNodeIds = relatedKnowledgeNodeIds,
            reviewedTeachingReferences = reviewedTeachingReferences,
            priorTeachingAdvisories = priorTeachingAdvisories,
        )
    }
    val identity = state.detail.identity
    // Three-store loop write side: silent by product decision.
    var debriefDraft by remember(question.sessionId) { mutableStateOf(DebriefDraft.EMPTY) }
    DisposableEffect(question.sessionId, modelTasks) {
        onDispose {
            // Silent debrief on session exit (user-approved, no UI surface).
            val stem = com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
                .project(question.questionDocument.document)
            val draft = debriefDraft
            if (draft.labels.isNotEmpty() && stem.isNotBlank()) {
                onRequestDebrief(
                    question.sessionId,
                    identity.practiceUnitId,
                    stem.take(com.tingyun.smartmistakebook.core.model.TutorDebriefInput.MAX_DEBRIEF_STEM_CHARS),
                    draft.transcript.take(com.tingyun.smartmistakebook.core.model.TutorDebriefInput.MAX_DEBRIEF_TRANSCRIPT_CHARS),
                    draft.labels,
                )
            }
        }
    }
    LaunchedEffect(question.sessionId, modelTasks) {
        modelTasks
            .observeRecentBySubject(question.sessionId, ModelTaskKind.TUTOR_PLAN, limit = 8)
            .distinctUntilChanged()
            .collect { tasks ->
                tasks.forEach { task ->
                    val output = task.output as? TutorPlanOutput ?: return@forEach
                    val labels = output.plan.targetedEvidenceLabels +
                        output.plan.inferredKnowledgeLabels
                    if (labels.isEmpty()) return@forEach
                    debriefDraft = debriefDraft.copy(labels = labels.distinct())
                    onRecordTeachingFocus(
                        output.sessionId,
                        identity.practiceUnitId,
                        labels,
                    )
                }
            }
    }
    LaunchedEffect(question.sessionId, modelTasks) {
        modelTasks
            .observeRecentBySubject(question.sessionId, ModelTaskKind.TUTOR_RESPOND, limit = 20)
            .distinctUntilChanged()
            .collect { tasks ->
                val transcript = tasks
                    .sortedBy { it.createdAtEpochMillis }
                    .joinToString(separator = "\n") { task ->
                        (task.output as? com.tingyun.smartmistakebook.core.model.TutorRespondOutput)
                            ?.messageMarkdown
                            .orEmpty()
                    }
                debriefDraft = debriefDraft.copy(transcript = transcript)
            }
    }
    LaunchedEffect(question.sessionId, modelTasks, onRecordMisconception) {
        modelTasks
            .observeRecentBySubject(question.sessionId, ModelTaskKind.LEARNING_SUMMARIZE, limit = 4)
            .distinctUntilChanged()
            .collect { tasks ->
                tasks.forEach { task ->
                    val output = task.output as? TutorDebriefOutput ?: return@forEach
                    val misconception = output.misconceptionMarkdown ?: return@forEach
                    onRecordMisconception(
                        output.sessionId,
                        output.practiceUnitId,
                        misconception,
                    )
                }
            }
    }
    LaunchedEffect(question.sessionId, identity.problemRevisionId, identity.practiceUnitId) {
        interactions.anchorSession(
            savedMistakeTutorAnchor(
                sessionId = question.sessionId,
                problemRevisionId = identity.problemRevisionId,
                practiceUnitId = identity.practiceUnitId,
                anchoredAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }
    TutorModelPanel(
        question = question,
        profile = profile,
        modelTasks = modelTasks,
        interactions = interactions,
        conversations = conversations,
        catalogEntries = catalogEntries,
        imageIntake = imageIntake,
        onOpenMistakeNotebook = onOpenMistakeNotebook,
        onOpenProfile = onOpenProfile,
        onOpenModelSettings = onOpenModelSettings,
        // 模型要的配图（重绘图 / 过程图）要在这一页渲染出来：此前这里没传，整段被跳过。
        attachedImageResolver = attachedImageResolver,
        // 意图确认按钮：此前这两个动作默认 {}，学生点了没反应。
        onRequestSave = onRequestSave,
        onRequestEnd = onRequestEnd,
        clock = clock,
        // 同一个页面标题栏：错题讲题与大堂、拍照会话长得一模一样。
        headerContent = {
            TutorPageHeader(
                onOpenCapabilitySettings = onOpenModelSettings,
                onOpenHistory = onOpenHistory,
                onBack = onBack,
            )
        },
        leadingContent = {
            LocalModeLine("已存入错题本 · 再次打开会接着上次讲题")
            SectionHeader(question.title, modifier = Modifier.padding(top = 10.dp))
            Text(
                text = question.subject.studentSubjectLabel(),
                modifier = Modifier.padding(top = 4.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(14.dp))
            StructuredContentRenderer(
                document = question.questionDocument.document,
                choicesEnabled = false,
            )
            learningMemory?.let { memory ->
                TutorQuestionMemoryCard(
                    memory = memory,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        },
        trailingContent = { Spacer(Modifier.height(12.dp)) },
        modifier = modifier,
    )
}

internal fun savedMistakeTutorAnchor(
    sessionId: String,
    problemRevisionId: String,
    practiceUnitId: String,
    anchoredAtEpochMillis: Long,
) = TutorSessionProblemAnchor(
    sessionId = sessionId,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    anchoredAtEpochMillis = anchoredAtEpochMillis,
)

internal fun savedMistakeTutorQuestion(
    state: MistakeDetailState.Ready,
    learningMemory: StudyQuestionMemory? = null,
    relatedKnowledgeNodeIds: Set<String> = emptySet(),
    reviewedTeachingReferences: List<TutorTeachingReference> = emptyList(),
    priorTeachingAdvisories: List<String> = emptyList(),
): TutorQuestionContext {
    val identity = state.detail.identity
    state.detail.tutorConversation?.let { conversation ->
        return TutorQuestionContext(
            sessionId = conversation.sessionId,
            revisionNumber = conversation.questionRevisionNumber,
            subject = identity.subject,
            title = identity.title,
            questionDocument = state.questionDocument,
            learningMemory = learningMemory,
            relatedKnowledgeNodeIds = relatedKnowledgeNodeIds,
            reviewedTeachingReferences = reviewedTeachingReferences,
        )
    }
    val stableSessionId = MessageDigest.getInstance("SHA-256")
        .digest(
            "${identity.problemId}\n${identity.problemRevisionId}"
                .toByteArray(StandardCharsets.UTF_8),
        )
        .joinToString("") { "%02x".format(it) }
        .take(32)
    return TutorQuestionContext(
        sessionId = "mistake-tutor-$stableSessionId",
        revisionNumber = identity.revisionNumber,
        subject = identity.subject,
        title = identity.title,
        questionDocument = state.questionDocument,
        learningMemory = learningMemory,
        relatedKnowledgeNodeIds = relatedKnowledgeNodeIds,
        reviewedTeachingReferences = reviewedTeachingReferences,
        priorTeachingAdvisories = priorTeachingAdvisories,
    )
}

@Composable
internal fun TutorQuestionMemoryCard(
    memory: StudyQuestionMemory,
    modifier: Modifier = Modifier,
) {
    val now = remember(memory) { System.currentTimeMillis() }
    // Qualitative retention bands only: precise probabilities require a
    // calibrated model (audit section 6.4).
    val retentionLabel =
        com.tingyun.smartmistakebook.core.ui.retentionBandLabel(
            memory.retrievabilityAtSnapshot,
        )
    val status = when {
        !memory.projectionIsCurrent -> "学习记录正在重新计算，暂不判断当前掌握度"
        memory.nextReviewAtEpochMillis <= now -> "$retentionLabel · 已到复习时间"
        else -> "$retentionLabel · 下次复习已安排"
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("saved_mistake_learning_memory"),
        color = JadeSoft.copy(alpha = 0.34f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "这道题的学习记忆",
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = buildString {
                    append("独立答对 ${memory.independentRecallCount} 次")
                    append(" · 提示后答对 ${memory.assistedRecallCount} 次")
                    append(" · 遗忘 ${memory.retrievalFailureCount} 次")
                    if (memory.answerRevealCount > 0) {
                        append(" · 看过答案 ${memory.answerRevealCount} 次")
                    }
                },
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(status, color = InkSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Accumulated silent-debrief inputs for one tutoring visit. */
internal data class DebriefDraft(
    val labels: List<String> = emptyList(),
    val transcript: String = "",
) {
    companion object {
        val EMPTY = DebriefDraft()
    }
}
