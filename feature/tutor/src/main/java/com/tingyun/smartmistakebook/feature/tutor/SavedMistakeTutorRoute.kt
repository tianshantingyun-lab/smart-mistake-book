package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingyun.smartmistakebook.core.domain.ConfirmedKnowledgeNodeBinding
import com.tingyun.smartmistakebook.core.domain.ConfirmedMistakeOrganization
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeSourceLocation
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyQuestionMemory
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostPort
import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionQuestionPreparationBlocker
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionQuestionPreparationResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionSavedMistakeReference
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRequest
import com.tingyun.smartmistakebook.core.domain.TutorSessionProblemAnchor
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.BoundedLocalImage
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.StructuredContentRenderer
import com.tingyun.smartmistakebook.core.ui.studentSubjectLabel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun SavedMistakeTutorRoute(
    key: MistakeRevisionKey,
    repository: MistakeDetailRepository,
    organizationRepository: MistakeOrganizationRepository,
    teachingReferenceRepository: TutorTeachingReferenceRepository,
    modelTasks: ScopedModelTaskPort,
    sessionHost: TutorCurrentSessionHostPort,
    profile: StudyProfileOverview? = null,
    learningMemory: StudyQuestionMemory? = null,
    onOpenModelSettings: () -> Unit,
    onCameraAttachment: () -> Unit = {},
    onGalleryAttachment: () -> Unit = {},
    onLibraryAttachment: () -> Unit = {},
    onBack: () -> Unit,
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    explanationModeVersion: Long = 0L,
    onExplanationModeChange: (TutorExplanationMode) -> Unit = {},
    learningWritesAllowed: Boolean = true,
    visualIntent: TutorCurrentSessionVisualIntent = TutorCurrentSessionVisualIntent.NONE,
    masteryContextRepository: TutorMasteryContextRepository? = null,
    questionKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    fallbackKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val stateFlow: Flow<MistakeDetailState> = remember(key, repository) {
        repository.observeExact(key)
    }
    val state by stateFlow.collectAsStateWithLifecycle(MistakeDetailState.Loading)
    val currentSubject = (state as? MistakeDetailState.Ready)?.detail?.identity?.subject
    var teachingContextWaitExpired by remember(key, currentSubject) { mutableStateOf(false) }
    LaunchedEffect(key, currentSubject) {
        teachingContextWaitExpired = false
        delay(TEACHING_PLAN_CONTEXT_WAIT_MILLIS)
        teachingContextWaitExpired = true
    }
    val directTeachingContextFlow = remember(
        key,
        currentSubject,
        organizationRepository,
        teachingReferenceRepository,
    ) {
        observeDirectTeachingContext(
            key = key,
            subject = currentSubject,
            organizationRepository = organizationRepository,
            teachingReferenceRepository = teachingReferenceRepository,
        )
    }
    val observedDirectTeachingContext by directTeachingContextFlow.collectAsStateWithLifecycle(
        DirectTeachingContext.preparing(key, currentSubject),
    )
    val directTeachingContext = observedDirectTeachingContext.takeIf {
        it.isCurrentFor(key = key, subject = currentSubject)
    } ?: DirectTeachingContext.preparing(key, currentSubject)
    val effectiveQuestionKnowledgeNodes = remember(
        questionKnowledgeNodes,
        directTeachingContext.directKnowledgeNodeRefs,
    ) {
        effectiveTutorMasteryKnowledgeNodes(
            explicit = questionKnowledgeNodes,
            direct = directTeachingContext.directKnowledgeNodeRefs,
        )
    }
    val tutorPlanContextReady = directTeachingContext.isReadyForTutorPlan(
        waitExpired = teachingContextWaitExpired,
    )

    when (val current = state) {
        is MistakeDetailState.Ready -> HostSavedMistakeTutorContent(
            state = current,
            modelTasks = modelTasks,
            sessionHost = sessionHost,
            learningMemory = learningMemory,
            directKnowledgeNodeIds = directTeachingContext.directKnowledgeNodeIds,
            relatedKnowledgeNodeIds = emptySet(),
            reviewedTeachingReferences = directTeachingContext.references,
            tutorPlanContextReady = tutorPlanContextReady,
            onOpenModelSettings = onOpenModelSettings,
            onBack = onBack,
            explanationMode = explanationMode,
            onExplanationModeChange = onExplanationModeChange,
            learningWritesAllowed = learningWritesAllowed,
            visualIntent = visualIntent,
            masteryContextRepository = masteryContextRepository,
            questionKnowledgeNodes = effectiveQuestionKnowledgeNodes,
            fallbackKnowledgeNodes = fallbackKnowledgeNodes,
            modifier = modifier.testTag("saved_mistake_tutor_screen"),
        )

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
private fun HostSavedMistakeTutorContent(
    state: MistakeDetailState.Ready,
    modelTasks: ScopedModelTaskPort,
    sessionHost: TutorCurrentSessionHostPort,
    learningMemory: StudyQuestionMemory?,
    directKnowledgeNodeIds: Set<String>,
    relatedKnowledgeNodeIds: Set<String>,
    reviewedTeachingReferences: List<TutorTeachingReference>,
    tutorPlanContextReady: Boolean,
    onOpenModelSettings: () -> Unit,
    onBack: () -> Unit,
    explanationMode: TutorExplanationMode,
    onExplanationModeChange: (TutorExplanationMode) -> Unit,
    learningWritesAllowed: Boolean,
    visualIntent: TutorCurrentSessionVisualIntent,
    masteryContextRepository: TutorMasteryContextRepository?,
    questionKnowledgeNodes: List<KnowledgeNodeRef>,
    fallbackKnowledgeNodes: List<KnowledgeNodeRef>,
    modifier: Modifier = Modifier,
) {
    val identity = state.detail.identity
    val savedReference = remember(identity) {
        TutorCurrentSessionSavedMistakeReference(
            errorBookEntryId = identity.errorBookEntryId,
            problemId = identity.problemId,
            problemRevisionId = identity.problemRevisionId,
            expectedRevisionNumber = identity.revisionNumber,
        )
    }
    val preparation by produceState<TutorCurrentSessionQuestionPreparationResult?>(
        initialValue = null,
        key1 = savedReference,
        key2 = sessionHost,
    ) {
        value = try {
            sessionHost.prepareSavedMistake(savedReference)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TutorCurrentSessionQuestionPreparationResult.Unavailable(
                TutorCurrentSessionQuestionPreparationBlocker.NOT_FOUND,
            )
        }
    }
    val preparedQuestion = when (val result = preparation) {
        is TutorCurrentSessionQuestionPreparationResult.Ready -> result.question
        null -> {
            TutorConversationFrame(
                header = {
                    TutorPageHeader(onBack)
                    PaperDivider()
                },
                autoScrollVersion = savedReference,
                modifier = modifier,
            ) {
                item("saved_mistake_host_preparing") { LoadingTutorQuestion() }
            }
            return
        }
        is TutorCurrentSessionQuestionPreparationResult.Unavailable -> {
            TutorConversationFrame(
                header = {
                    TutorPageHeader(onBack)
                    PaperDivider()
                },
                autoScrollVersion = result.blocker,
                modifier = modifier,
            ) {
                item("saved_mistake_host_unavailable") {
                    TutorQuestionUnavailable(
                        title = "暂时无法讲解",
                        detail = "请稍后再试。",
                        onRetry = onBack,
                    )
                }
            }
            return
        }
    }
    val question = remember(
        state.detail.identity,
        state.detail.tutorConversation,
        state.questionDocument,
        preparedQuestion,
        learningMemory,
        directKnowledgeNodeIds,
        relatedKnowledgeNodeIds,
        reviewedTeachingReferences,
        questionKnowledgeNodes,
        fallbackKnowledgeNodes,
    ) {
        savedMistakeTutorQuestion(
            state = state,
            learningMemory = learningMemory,
            directKnowledgeNodeIds = directKnowledgeNodeIds,
            relatedKnowledgeNodeIds = relatedKnowledgeNodeIds,
            reviewedTeachingReferences = reviewedTeachingReferences,
            questionKnowledgeNodes = questionKnowledgeNodes,
            fallbackKnowledgeNodes = fallbackKnowledgeNodes,
            sessionIdOverride = preparedQuestion.sessionId,
        )
    }
    val originalUri = remember(state.detail.source) {
        state.detail.source.firstAvailableOriginalUri()
    }
    var sourceExpanded by rememberSaveable(question.sessionId) { mutableStateOf(false) }
    TutorCurrentSessionHostPanel(
        question = question,
        modelTasks = modelTasks,
        sessionHost = sessionHost,
        masteryContextRepository = masteryContextRepository,
        learningWritesAllowed = learningWritesAllowed,
        explanationMode = explanationMode,
        visualIntent = visualIntent,
        planContextReady = tutorPlanContextReady,
        onExplanationModeChange = onExplanationModeChange,
        onOpenModelSettings = onOpenModelSettings,
        visualSourceAssetsReader = { state.detail.source.toTutorVisualSourceAssets() },
        visualOriginalAvailable = originalUri != null,
        onOpenVisualOriginal = {
            if (originalUri != null) sourceExpanded = true
        },
        headerContent = {
            TutorPageHeader(onBack)
            PaperDivider()
        },
        leadingContent = {
            SectionHeader(question.title, modifier = Modifier.padding(top = 10.dp))
            Text(
                text = question.subject.studentSubjectLabel(),
                modifier = Modifier.padding(top = 4.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            SavedMistakeStatus()
            Spacer(Modifier.height(14.dp))
            StructuredContentRenderer(
                document = question.questionDocument.document,
                choicesEnabled = false,
            )
            originalUri?.let { uri ->
                OutlineActionChip(
                    text = if (sourceExpanded) "收起原图" else "查看原图",
                    onClick = { sourceExpanded = !sourceExpanded },
                    icon = Icons.Outlined.Image,
                    contentDescription = if (sourceExpanded) "收起题目原图" else "查看题目原图",
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .testTag("saved_mistake_tutor_source_toggle"),
                )
                if (sourceExpanded) {
                    BoundedLocalImage(
                        imageUri = uri,
                        contentDescription = "题目原图",
                        expanded = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .testTag("saved_mistake_tutor_source_image"),
                    )
                }
            }
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

@Composable
internal fun SavedMistakeTutorContent(
    state: MistakeDetailState.Ready,
    modelTasks: ScopedModelTaskPort,
    interactions: TutorInteractionRepository,
    profile: StudyProfileOverview? = null,
    learningMemory: StudyQuestionMemory?,
    directKnowledgeNodeIds: Set<String> = emptySet(),
    relatedKnowledgeNodeIds: Set<String> = emptySet(),
    reviewedTeachingReferences: List<TutorTeachingReference> = emptyList(),
    onOpenModelSettings: () -> Unit,
    onBack: () -> Unit = {},
    onCameraAttachment: () -> Unit = {},
    onGalleryAttachment: () -> Unit = {},
    onLibraryAttachment: () -> Unit = {},
    clock: () -> Long = System::currentTimeMillis,
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    explanationModeVersion: Long = 0L,
    onExplanationModeChange: (TutorExplanationMode) -> Unit = {},
    masteryContextRepository: TutorMasteryContextRepository? = null,
    questionKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    fallbackKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val question = remember(
        state.detail.identity,
        state.detail.tutorConversation,
        state.questionDocument,
        learningMemory,
        directKnowledgeNodeIds,
        relatedKnowledgeNodeIds,
        reviewedTeachingReferences,
        questionKnowledgeNodes,
        fallbackKnowledgeNodes,
    ) {
        savedMistakeTutorQuestion(
            state = state,
            learningMemory = learningMemory,
            directKnowledgeNodeIds = directKnowledgeNodeIds,
            relatedKnowledgeNodeIds = relatedKnowledgeNodeIds,
            reviewedTeachingReferences = reviewedTeachingReferences,
            questionKnowledgeNodes = questionKnowledgeNodes,
            fallbackKnowledgeNodes = fallbackKnowledgeNodes,
        )
    }
    val identity = state.detail.identity
    val visualSourceAssets = remember(state.detail.source) {
        state.detail.source.toTutorVisualSourceAssets()
    }
    val originalUri = remember(state.detail.source) {
        state.detail.source.firstAvailableOriginalUri()
    }
    var sourceExpanded by rememberSaveable(question.sessionId) { mutableStateOf(false) }
    var longTermWritesBlocked by rememberSaveable(question.sessionId) { mutableStateOf(false) }
    var learningWritePermissionVersion by rememberSaveable(question.sessionId) {
        mutableLongStateOf(0L)
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
        masteryContextRepository = masteryContextRepository,
        visualSourceAssetsReader = { visualSourceAssets },
        visualOriginalAvailable = originalUri != null,
        interactions = interactions,
        allowLongTermLearningWrites = !longTermWritesBlocked,
        learningWritePermissionVersion = learningWritePermissionVersion,
        onLongTermWritesBlocked = {
            if (!longTermWritesBlocked) {
                longTermWritesBlocked = true
                learningWritePermissionVersion += 1
            }
        },
        onOpenVisualOriginal = {
            if (originalUri != null) sourceExpanded = true
        },
        onOpenModelSettings = onOpenModelSettings,
        onCameraAttachment = onCameraAttachment,
        onGalleryAttachment = onGalleryAttachment,
        onLibraryAttachment = onLibraryAttachment,
        clock = clock,
        explanationMode = explanationMode,
        explanationModeVersion = explanationModeVersion,
        onExplanationModeChange = onExplanationModeChange,
        headerContent = {
            TutorPageHeader(onBack)
            PaperDivider()
        },
        leadingContent = {
            SectionHeader(question.title, modifier = Modifier.padding(top = 10.dp))
            Text(
                text = question.subject.studentSubjectLabel(),
                modifier = Modifier.padding(top = 4.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            SavedMistakeStatus()
            Spacer(Modifier.height(14.dp))
            StructuredContentRenderer(
                document = question.questionDocument.document,
                choicesEnabled = false,
            )
            originalUri?.let { uri ->
                OutlineActionChip(
                    text = if (sourceExpanded) "收起原图" else "查看原图",
                    onClick = { sourceExpanded = !sourceExpanded },
                    icon = Icons.Outlined.Image,
                    contentDescription = if (sourceExpanded) "收起题目原图" else "查看题目原图",
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .testTag("saved_mistake_tutor_source_toggle"),
                )
                if (sourceExpanded) {
                    BoundedLocalImage(
                        imageUri = uri,
                        contentDescription = "题目原图",
                        expanded = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .testTag("saved_mistake_tutor_source_image"),
                    )
                }
            }
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

internal fun MistakeSourceSet.toTutorVisualSourceAssets(): List<TutorVisualSourceAssetScope> =
    when (this) {
        MistakeSourceSet.Missing -> emptyList()
        is MistakeSourceSet.Present -> assets.mapIndexed { pageIndex, asset ->
            TutorVisualSourceAssetScope(
                pageIndex = pageIndex,
                assetId = asset.sourceAssetId,
                sha256 = asset.contentSha256,
                byteSize = asset.byteSize,
                width = asset.width,
                height = asset.height,
            )
        }
    }

internal fun MistakeSourceSet.firstAvailableOriginalUri(): String? =
    (this as? MistakeSourceSet.Present)
        ?.assets
        ?.firstNotNullOfOrNull { asset ->
            (asset.location as? MistakeSourceLocation.Available)?.localUri
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
    directKnowledgeNodeIds: Set<String> = emptySet(),
    relatedKnowledgeNodeIds: Set<String> = emptySet(),
    reviewedTeachingReferences: List<TutorTeachingReference> = emptyList(),
    questionKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    fallbackKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    sessionIdOverride: String? = null,
): TutorQuestionContext {
    val identity = state.detail.identity
    val overriddenSessionId = sessionIdOverride?.takeIf(String::isNotBlank)
    if (overriddenSessionId != null) {
        return TutorQuestionContext(
            sessionId = overriddenSessionId,
            revisionNumber = identity.revisionNumber,
            subject = identity.subject,
            title = identity.title,
            questionDocument = state.questionDocument,
            learningMemory = learningMemory,
            directKnowledgeNodeIds = directKnowledgeNodeIds,
            relatedKnowledgeNodeIds = relatedKnowledgeNodeIds,
            reviewedTeachingReferences = reviewedTeachingReferences,
            questionKnowledgeNodes = questionKnowledgeNodes,
            fallbackKnowledgeNodes = fallbackKnowledgeNodes,
        )
    }
    state.detail.tutorConversation?.let { conversation ->
        return TutorQuestionContext(
            sessionId = conversation.sessionId,
            revisionNumber = conversation.questionRevisionNumber,
            subject = identity.subject,
            title = identity.title,
            questionDocument = state.questionDocument,
            learningMemory = learningMemory,
            directKnowledgeNodeIds = directKnowledgeNodeIds,
            relatedKnowledgeNodeIds = relatedKnowledgeNodeIds,
            reviewedTeachingReferences = reviewedTeachingReferences,
            questionKnowledgeNodes = questionKnowledgeNodes,
            fallbackKnowledgeNodes = fallbackKnowledgeNodes,
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
        directKnowledgeNodeIds = directKnowledgeNodeIds,
        relatedKnowledgeNodeIds = relatedKnowledgeNodeIds,
        reviewedTeachingReferences = reviewedTeachingReferences,
        questionKnowledgeNodes = questionKnowledgeNodes,
        fallbackKnowledgeNodes = fallbackKnowledgeNodes,
    )
}

internal data class DirectTeachingContext(
    val key: MistakeRevisionKey?,
    val subject: String?,
    val lookupState: DirectTeachingLookupState,
    val authority: DirectTeachingAuthority?,
    val directKnowledgeNodeRefs: List<KnowledgeNodeRef> = emptyList(),
    val references: List<TutorTeachingReference>,
) {
    val directKnowledgeNodeIds: Set<String>
        get() = directKnowledgeNodeRefs.mapTo(linkedSetOf()) { it.knowledgeNodeId }

    companion object {
        val EMPTY = DirectTeachingContext(
            key = null,
            subject = null,
            lookupState = DirectTeachingLookupState.READY,
            authority = null,
            directKnowledgeNodeRefs = emptyList(),
            references = emptyList(),
        )

        fun preparing(
            key: MistakeRevisionKey,
            subject: String?,
            authority: DirectTeachingAuthority? = null,
            directKnowledgeNodeRefs: List<KnowledgeNodeRef> = emptyList(),
        ) = DirectTeachingContext(
            key = key,
            subject = subject,
            lookupState = DirectTeachingLookupState.PREPARING,
            authority = authority,
            directKnowledgeNodeRefs = directKnowledgeNodeRefs,
            references = emptyList(),
        )

        fun ready(
            key: MistakeRevisionKey,
            subject: String?,
            authority: DirectTeachingAuthority? = null,
            directKnowledgeNodeRefs: List<KnowledgeNodeRef> = emptyList(),
            references: List<TutorTeachingReference> = emptyList(),
        ) = DirectTeachingContext(
            key = key,
            subject = subject,
            lookupState = DirectTeachingLookupState.READY,
            authority = authority,
            directKnowledgeNodeRefs = directKnowledgeNodeRefs,
            references = references,
        )
    }

    fun isCurrentFor(key: MistakeRevisionKey, subject: String?): Boolean =
        this.key == key && this.subject == subject

    fun isReadyForTutorPlan(waitExpired: Boolean): Boolean =
        lookupState == DirectTeachingLookupState.READY || waitExpired
}

internal enum class DirectTeachingLookupState {
    PREPARING,
    READY,
}

internal data class DirectTeachingAuthority(
    val key: MistakeRevisionKey,
    val subject: String,
    val bindingFingerprint: String,
    val manifestFingerprint: String,
    val activationGeneration: Long,
)

internal fun observeDirectTeachingContext(
    key: MistakeRevisionKey,
    subject: String?,
    organizationRepository: MistakeOrganizationRepository,
    teachingReferenceRepository: TutorTeachingReferenceRepository,
    timeoutMillis: Long = TEACHING_REFERENCE_TIMEOUT_MILLIS,
): Flow<DirectTeachingContext> = observeDirectTeachingContext(
    key = key,
    subject = subject,
    confirmedOrganizations = organizationRepository.observeConfirmed(key),
    teachingReferenceRepository = teachingReferenceRepository,
    timeoutMillis = timeoutMillis,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeDirectTeachingContext(
    key: MistakeRevisionKey,
    subject: String?,
    confirmedOrganizations: Flow<ConfirmedMistakeOrganization>,
    teachingReferenceRepository: TutorTeachingReferenceRepository,
    timeoutMillis: Long = TEACHING_REFERENCE_TIMEOUT_MILLIS,
): Flow<DirectTeachingContext> = confirmedOrganizations
    .transformLatest { confirmed ->
        emit(DirectTeachingContext.preparing(key, subject))
        val directKnowledgeNodes = confirmed.directKnowledgeNodes.validatedForSubject(subject)
        val authority = directKnowledgeNodes.toDirectTeachingAuthority(key, subject)
        if (authority == null) {
            emit(DirectTeachingContext.ready(key, subject))
            return@transformLatest
        }
        val directKnowledgeNodeRefs = directKnowledgeNodes.map { it.ref }
        emit(
            DirectTeachingContext.preparing(
                key = key,
                subject = subject,
                authority = authority,
                directKnowledgeNodeRefs = directKnowledgeNodeRefs,
            ),
        )
        val references = loadDirectTeachingReferences(
            subject = subject,
            directKnowledgeNodes = directKnowledgeNodes,
            repository = teachingReferenceRepository,
            timeoutMillis = timeoutMillis,
        )
        currentCoroutineContext().ensureActive()
        emit(
            DirectTeachingContext.ready(
                key = key,
                subject = subject,
                authority = authority,
                directKnowledgeNodeRefs = directKnowledgeNodeRefs,
                references = references,
            ),
        )
    }
    .catch { error ->
        if (error is CancellationException) throw error
        emit(DirectTeachingContext.ready(key, subject))
    }

private fun List<ConfirmedKnowledgeNodeBinding>.toDirectTeachingAuthority(
    key: MistakeRevisionKey,
    subject: String?,
): DirectTeachingAuthority? {
    if (isEmpty() || subject.isNullOrBlank()) return null
    val firstBinding = first()
    val bindingFingerprint = CanonicalSha256(DIRECT_TEACHING_BINDING_DOMAIN)
        .field("entryId", key.entryId)
        .field("problemId", key.problemId)
        .field("problemRevisionId", key.problemRevisionId)
        .field("subject", subject)
        .field("manifestFingerprint", firstBinding.manifestFingerprint)
        .field("activationGeneration", firstBinding.activationGeneration)
        .also { digest ->
            map { binding -> binding.ref.canonicalFingerprint }
                .sorted()
                .forEachIndexed { index, fingerprint ->
                    digest.field("nodeRef[$index]", fingerprint)
                }
        }
        .finish()
    return DirectTeachingAuthority(
        key = key,
        subject = subject,
        bindingFingerprint = bindingFingerprint,
        manifestFingerprint = firstBinding.manifestFingerprint,
        activationGeneration = firstBinding.activationGeneration,
    )
}

internal suspend fun loadDirectTeachingReferences(
    subject: String?,
    directKnowledgeNodes: List<ConfirmedKnowledgeNodeBinding>,
    repository: TutorTeachingReferenceRepository,
    timeoutMillis: Long = TEACHING_REFERENCE_TIMEOUT_MILLIS,
): List<TutorTeachingReference> {
    val validatedDirectNodes = directKnowledgeNodes.validatedForSubject(subject)
    if (validatedDirectNodes.isEmpty() || timeoutMillis <= 0L) {
        return emptyList()
    }
    val directByNodeId = validatedDirectNodes.associateBy { it.ref.knowledgeNodeId }
    val firstBinding = validatedDirectNodes.first()
    return try {
        withTimeoutOrNull(timeoutMillis) {
            repository.referencesForConfirmedNodes(
                subject = checkNotNull(subject),
                directKnowledgeNodes = validatedDirectNodes.toSet(),
                limit = TutorTeachingReferenceRepository.DEFAULT_LIMIT,
            ).asSequence()
                .filter { reference ->
                    reference.subject == subject &&
                        reference.boundKnowledgeNodes.isNotEmpty() &&
                        reference.boundKnowledgeNodes.map { it.knowledgeNodeId } ==
                            reference.knowledgeNodeIds &&
                        reference.boundKnowledgeNodes.all { node ->
                            directByNodeId[node.knowledgeNodeId]?.ref == node
                        } &&
                        reference.manifestFingerprint == firstBinding.manifestFingerprint &&
                        reference.activationGeneration == firstBinding.activationGeneration
                }
                .distinctBy(TutorTeachingReference::materialId)
                .take(TutorTeachingReferenceRepository.DEFAULT_LIMIT)
                .toList()
        }.orEmpty()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
}

private fun List<ConfirmedKnowledgeNodeBinding>.validatedForSubject(
    subject: String?,
): List<ConfirmedKnowledgeNodeBinding> {
    if (subject.isNullOrBlank() || isEmpty()) return emptyList()
    val firstBinding = first()
    return takeIf { bindings ->
        bindings.distinctBy { it.ref.knowledgeNodeId }.size == bindings.size &&
            bindings.all {
                it.ref.subject.name == subject &&
                    it.manifestFingerprint == firstBinding.manifestFingerprint &&
                    it.activationGeneration == firstBinding.activationGeneration
            }
    }.orEmpty()
}

internal fun effectiveTutorMasteryKnowledgeNodes(
    explicit: List<KnowledgeNodeRef>,
    direct: List<KnowledgeNodeRef>,
): List<KnowledgeNodeRef> =
    explicit.ifEmpty {
        direct
            .distinctBy(KnowledgeNodeRef::canonicalFingerprint)
            .take(TutorMasteryContextRequest.MAX_QUESTION_KNOWLEDGE_NODES)
    }

private const val TEACHING_REFERENCE_TIMEOUT_MILLIS = 1_500L
private const val TEACHING_PLAN_CONTEXT_WAIT_MILLIS = 1_750L
private const val DIRECT_TEACHING_BINDING_DOMAIN = "saved-mistake-direct-teaching-binding-v1"

@Composable
internal fun TutorQuestionMemoryCard(
    memory: StudyQuestionMemory,
    modifier: Modifier = Modifier,
) {
    val now = remember(memory) { System.currentTimeMillis() }
    val status = when {
        !memory.projectionIsCurrent -> "最近有波动"
        memory.nextReviewAtEpochMillis <= now ||
            memory.retrievalFailureCount > memory.independentRecallCount -> "需要再巩固"
        memory.independentRecallCount >= 2 && memory.retrievabilityAtSnapshot >= 0.75 -> "比较稳"
        else -> "正在熟悉"
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
                text = "掌握情况",
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(status, color = InkSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SavedMistakeStatus() {
    Text(
        text = "本题已存入错题本",
        modifier = Modifier
            .padding(top = 4.dp)
            .testTag("saved_mistake_status"),
        color = InkSecondary,
        style = MaterialTheme.typography.labelMedium,
    )
}
