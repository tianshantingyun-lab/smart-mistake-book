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
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.ConfirmedMistakeOrganization
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyQuestionMemory
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorSessionProblemAnchor
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorDebriefOutput
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.LocalModeLine
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.StructuredContentRenderer
import com.tingyun.smartmistakebook.core.ui.studentSubjectLabel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

@Composable
fun SavedMistakeTutorRoute(
    key: MistakeRevisionKey,
    repository: MistakeDetailRepository,
    organizationRepository: MistakeOrganizationRepository,
    teachingReferenceRepository: TutorTeachingReferenceRepository,
    modelTasks: ModelTaskRepository,
    interactions: TutorInteractionRepository,
    profile: StudyProfileOverview,
    learningMemory: StudyQuestionMemory? = null,
    onOpenModelSettings: () -> Unit,
    onBack: () -> Unit,
    /** Silent teaching-focus persistence (three-store loop); no UI surface. */
    onRecordTeachingFocus: (sessionId: String, practiceUnitId: String, labels: List<String>) -> Unit = { _, _, _ -> },
    /** Silent misconception debrief request on session exit; no UI surface. */
    onRequestDebrief: (sessionId: String, practiceUnitId: String, stemMarkdown: String, transcriptMarkdown: String, labels: List<String>) -> Unit = { _, _, _, _, _ -> },
    /** Silent misconception advisory write when a debrief completes. */
    onRecordMisconception: (sessionId: String, practiceUnitId: String, payloadMarkdown: String) -> Unit = { _, _, _ -> },
    /** Stored advisories injected into the tutor prompt (read side of the loop). */
    priorTeachingAdvisories: List<String> = emptyList(),
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
                    limit = TutorTeachingReferenceRepository.DEFAULT_LIMIT,
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
                    TutorPageHeader(onBack)
                    PaperDivider()
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
                profile = profile,
                learningMemory = learningMemory,
                relatedKnowledgeNodeIds = requireNotNull(organization).knowledgeNodeIds,
                reviewedTeachingReferences = teachingReferences,
                onOpenModelSettings = onOpenModelSettings,
                onBack = onBack,
                onRecordTeachingFocus = onRecordTeachingFocus,
                onRequestDebrief = onRequestDebrief,
                onRecordMisconception = onRecordMisconception,
                priorTeachingAdvisories = priorTeachingAdvisories,
                modifier = modifier.testTag("saved_mistake_tutor_screen"),
            )
        }

        else -> TutorConversationFrame(
            header = {
                TutorPageHeader(onBack)
                PaperDivider()
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
    profile: StudyProfileOverview,
    learningMemory: StudyQuestionMemory?,
    relatedKnowledgeNodeIds: Set<String> = emptySet(),
    reviewedTeachingReferences: List<TutorTeachingReference> = emptyList(),
    onOpenModelSettings: () -> Unit,
    onBack: () -> Unit = {},
    /** Silent teaching-focus persistence (three-store loop); no UI surface. */
    onRecordTeachingFocus: (sessionId: String, practiceUnitId: String, labels: List<String>) -> Unit = { _, _, _ -> },
    /** Silent misconception debrief request on session exit; no UI surface. */
    onRequestDebrief: (sessionId: String, practiceUnitId: String, stemMarkdown: String, transcriptMarkdown: String, labels: List<String>) -> Unit = { _, _, _, _, _ -> },
    /** Silent misconception advisory write when a debrief completes. */
    onRecordMisconception: (sessionId: String, practiceUnitId: String, payloadMarkdown: String) -> Unit = { _, _, _ -> },
    /** Stored advisories injected into the tutor prompt (read side of the loop). */
    priorTeachingAdvisories: List<String> = emptyList(),
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
    // Three-store closed loop: whenever the model's plan names teaching
    // focus labels, persist them as advisories in the mastery database.
    // Silent by design (user decision) - no prompt, no badge.
    var debriefLabels by remember(question.sessionId) { mutableStateOf(emptyList<String>()) }
    var debriefTranscript by remember(question.sessionId) { mutableStateOf("") }
    DisposableEffect(question.sessionId, modelTasks) {
        onDispose {
            // Silent debrief on session exit (user-approved, no UI surface).
            val stem = question.questionDocument.document.blocks
                .mapNotNull { block ->
                    when (block) {
                        is com.tingyun.smartmistakebook.core.model.ContentBlock.Paragraph -> block.markdown
                        is com.tingyun.smartmistakebook.core.model.ContentBlock.Formula -> block.alternativeText
                        else -> null
                    }
                }
                .joinToString(separator = "\n")
            if (debriefLabels.isNotEmpty() && stem.isNotBlank()) {
                onRequestDebrief(
                    question.sessionId,
                    identity.practiceUnitId,
                    stem.take(com.tingyun.smartmistakebook.core.model.TutorDebriefInput.MAX_DEBRIEF_STEM_CHARS),
                    debriefTranscript.take(com.tingyun.smartmistakebook.core.model.TutorDebriefInput.MAX_DEBRIEF_TRANSCRIPT_CHARS),
                    debriefLabels,
                )
            }
        }
    }
    LaunchedEffect(question.sessionId, modelTasks) {
        modelTasks
            .observeRecentBySubject(question.sessionId, ModelTaskKind.TUTOR_PLAN, limit = 8)
            .collect { tasks ->
                tasks.forEach { task ->
                    val output = task.output as? TutorPlanOutput ?: return@forEach
                    val labels = output.plan.targetedEvidenceLabels +
                        output.plan.inferredKnowledgeLabels
                    if (labels.isEmpty()) return@forEach
                    onRecordTeachingFocus(
                        output.sessionId,
                        identity.practiceUnitId,
                        labels,
                    )
                }
            }
    }
    LaunchedEffect(question.sessionId, modelTasks, onRecordMisconception) {
        modelTasks
            .observeRecentBySubject(question.sessionId, ModelTaskKind.LEARNING_SUMMARIZE, limit = 4)
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
        onOpenModelSettings = onOpenModelSettings,
        clock = clock,
        headerContent = {
            TutorPageHeader(onBack)
            PaperDivider()
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
