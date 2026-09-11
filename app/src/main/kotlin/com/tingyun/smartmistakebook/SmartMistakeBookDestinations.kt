package com.tingyun.smartmistakebook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.StudyDataStatus
import com.tingyun.smartmistakebook.core.domain.StudyExperienceRepository
import com.tingyun.smartmistakebook.core.domain.StudyExperienceSnapshot
import com.tingyun.smartmistakebook.core.domain.StudyReviewAdvanceResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TeachingAdvisoryRecord
import com.tingyun.smartmistakebook.feature.review.CapturedReviewSessionScreen
import com.tingyun.smartmistakebook.feature.review.ReviewSessionScreen
import com.tingyun.smartmistakebook.feature.tutor.SavedMistakeTutorRoute
import com.tingyun.smartmistakebook.feature.tutor.buildTutorDebriefRequestForApp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * NavHost destinations of [SmartMistakeBookRoot] that carry their own
 * session/artifact state. Extracted so the root composable stays a
 * navigation shell instead of a 950-line screen body.
 */
@Composable
internal fun ReviewSessionDestination(
    entry: NavBackStackEntry,
    experience: StudyExperienceSnapshot,
    capabilities: AppCapabilitySnapshot,
    repository: StudyExperienceRepository,
    navController: NavHostController,
) {
    val destinationLifecycle by entry.lifecycle.currentStateFlow
        .collectAsStateWithLifecycle()
    val onReviewBack = {
        if (entry.lifecycle.currentState == Lifecycle.State.RESUMED) {
            navController.popBackStack()
        }
        Unit
    }
    BackHandler(onBack = onReviewBack)
    var displayedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var displayedSessionVersion by rememberSaveable { mutableStateOf<Long?>(null) }
    var displayedPracticeUnitId by rememberSaveable { mutableStateOf<String?>(null) }
    var displayedOrdinal by rememberSaveable { mutableStateOf<Int?>(null) }
    var displayedQueueSize by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(
        experience.status,
        experience.review.activeSessionId,
        experience.review.sessionStateVersion,
        experience.review.currentOrdinal,
        experience.review.scheduledPracticeUnitIds,
        displayedSessionId,
    ) {
        if (experience.status != StudyDataStatus.READY || displayedSessionId != null) {
            return@LaunchedEffect
        }
        val activeSessionId = experience.review.activeSessionId
        val activeSessionVersion = experience.review.sessionStateVersion
        val activeOrdinal = experience.review.currentOrdinal
        val activePracticeUnitId = experience.review.scheduledPracticeUnitIds
            .getOrNull(activeOrdinal)
        if (
            activeSessionId != null &&
            activeSessionVersion != null &&
            activePracticeUnitId != null
        ) {
            displayedSessionId = activeSessionId
            displayedSessionVersion = activeSessionVersion
            displayedPracticeUnitId = activePracticeUnitId
            displayedOrdinal = activeOrdinal
            displayedQueueSize = experience.review.scheduledPracticeUnitIds.size
        } else {
            val restoredReviewRoot = navController.popBackStack(Routes.Review, false)
            if (!restoredReviewRoot) {
                navController.navigate(Routes.Review) {
                    popUpTo(Routes.ReviewSession) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    val artifactLoad by produceState(
        initialValue = TeachingArtifactLoad(),
        key1 = experience.status,
        key2 = displayedPracticeUnitId,
    ) {
        val requestedId = displayedPracticeUnitId
        value = TeachingArtifactLoad(practiceUnitId = requestedId)
        val loadedArtifact = requestedId
            ?.takeIf { experience.status == StudyDataStatus.READY }
            ?.let { repository.teachingArtifact(it) }
        currentCoroutineContext().ensureActive()
        // Spec §2.16 re-teach opening, loaded in the same round trip as the
        // artifact: only a leeched card yields one, so this stays a null lookup
        // for ordinary review. It is deliberately *not* fetched through
        // revealAnswer — that path records a "saw the answer" event, which would
        // turn the student's next attempt into a post-reveal attempt.
        val reTeachOpening = if (loadedArtifact != null) {
            repository.reTeachOpening(requireNotNull(requestedId))
        } else {
            null
        }
        // Spec §2.9 prerequisite remediation, same round trip. Independent of the
        // leech opening: a card can be both, and neither implies the other.
        val prerequisiteRemediation = if (loadedArtifact != null) {
            repository.prerequisiteRemediation(requireNotNull(requestedId))
        } else {
            null
        }
        value = TeachingArtifactLoad(
            practiceUnitId = requestedId,
            artifact = loadedArtifact,
            reTeachOpening = reTeachOpening,
            prerequisiteRemediation = prerequisiteRemediation,
            isLoaded = true,
        )
    }

    val sessionId = displayedSessionId
    val sessionVersion = displayedSessionVersion
    val practiceUnitId = displayedPracticeUnitId
    val ordinal = displayedOrdinal
    val queueSize = displayedQueueSize
    val capturedEntry = experience.catalog.firstOrNull { catalogEntry ->
        catalogEntry.practiceUnitId == practiceUnitId
    }
    val stageReviewAdvance: (StudyReviewAdvanceResult) -> Unit = { result ->
        if (result.progress.status == StudyReviewSessionStatus.COMPLETED) {
            displayedSessionId = null
        } else {
            val nextPracticeUnitId = result.nextPracticeUnitId
            if (nextPracticeUnitId != null) {
                displayedSessionId = result.progress.sessionId
                displayedSessionVersion = result.progress.stateVersion
                displayedPracticeUnitId = nextPracticeUnitId
                displayedOrdinal = result.progress.currentOrdinal
                displayedQueueSize = result.progress.queueSize
            }
        }
    }
    val continueReview: (StudyReviewAdvanceResult) -> Unit = { result ->
        stageReviewAdvance(result)
        if (result.progress.status == StudyReviewSessionStatus.COMPLETED) {
            navController.popBackStack(Routes.Review, false)
        }
    }
    when {
        destinationLifecycle != Lifecycle.State.RESUMED -> ReviewSessionGateMessage(
            "正在打开复习题…",
        )
        experience.status == StudyDataStatus.LOADING -> ReviewSessionGateMessage(
            "正在恢复本机复习进度…",
        )
        experience.status == StudyDataStatus.ERROR -> ReviewSessionGateMessage(
            "学习记录暂时不可用，复习已暂停。",
        )
        sessionId == null || sessionVersion == null || practiceUnitId == null ||
            ordinal == null || queueSize == null -> ReviewSessionGateMessage(
                "正在确认本机复习会话…",
            )
        !artifactLoad.isLoaded || artifactLoad.practiceUnitId != practiceUnitId ->
            ReviewSessionGateMessage("正在读取题目…")
        artifactLoad.artifact != null -> ReviewSessionScreen(
            onBack = onReviewBack,
            capabilities = capabilities,
            practiceUnitId = practiceUnitId,
            presentationId = "presentation:review:$sessionId:$sessionVersion:$practiceUnitId",
            teachingArtifact = artifactLoad.artifact,
            profile = experience.profile,
            reTeachOpening = artifactLoad.reTeachOpening,
            prerequisiteRemediation = artifactLoad.prerequisiteRemediation,
            queuePosition = ordinal + 1,
            queueSize = queueSize,
            onSubmitChoice = { submission ->
                repository.submitReviewChoice(
                    sessionId = sessionId,
                    expectedStateVersion = sessionVersion,
                    submission = submission,
                )
            },
            onRevealAnswer = repository::revealAnswer,
            onContinue = continueReview,
            onRequestTutorPretest = capturedEntry?.let { entry ->
                {
                    // PretestRouting.TUTOR_JUDGED_FLOW (spec
                    // batch-intake §3): free-response items with no
                    // machine-checkable options hand the first
                    // attempt to the tutor-judged session.
                    navController.navigate(
                        Routes.mistakeTutor(
                            MistakeRevisionKey(
                                entryId = entry.entryId,
                                problemId = entry.problemId,
                                problemRevisionId = entry.problemRevisionId,
                            ),
                        ),
                    )
                }
            },
        )
        capturedEntry != null -> CapturedReviewSessionScreen(
            onBack = onReviewBack,
            entry = capturedEntry,
            presentationId = "presentation:review:$sessionId:$sessionVersion:$practiceUnitId",
            queuePosition = ordinal + 1,
            queueSize = queueSize,
            onSubmit = { submission ->
                repository.submitReviewSelfReport(
                    sessionId = sessionId,
                    expectedStateVersion = sessionVersion,
                    submission = submission,
                )
            },
            onSubmitRating = { submission ->
                repository.submitReviewRating(
                    sessionId = sessionId,
                    expectedStateVersion = sessionVersion,
                    submission = submission,
                )
            },
            onContinue = continueReview,
            onNeedsTutor = { result ->
                stageReviewAdvance(result)
                navController.navigate(
                    Routes.mistakeTutor(
                        MistakeRevisionKey(
                            entryId = capturedEntry.entryId,
                            problemId = capturedEntry.problemId,
                            problemRevisionId = capturedEntry.problemRevisionId,
                        ),
                    ),
                ) {
                    launchSingleTop = true
                }
            },
        )
        else -> ReviewSessionGateMessage(
            "当前题目暂时不可用，未记录本次作答。",
        )
    }
}

@Composable
internal fun SavedMistakeTutorDestination(
    entry: NavBackStackEntry,
    experience: StudyExperienceSnapshot,
    application: SmartMistakeBookApplication,
    agentConsentEnabled: Boolean,
    navController: NavHostController,
) {
    val key = Routes.decodeMistakeExportKey(
        entryId = entry.arguments?.getString("entryId"),
        problemId = entry.arguments?.getString("problemId"),
        problemRevisionId = entry.arguments?.getString("problemRevisionId"),
    )
    if (key == null) {
        ReviewSessionGateMessage("没有找到这道错题")
    } else {
        var priorAdvisories by remember(key.entryId) {
            mutableStateOf<List<TeachingAdvisoryRecord>>(emptyList())
        }
        LaunchedEffect(key.entryId) {
            val practiceUnitId = experience.catalog
                .firstOrNull { it.entryId == key.entryId }
                ?.practiceUnitId
            application.studyRepository
                .observeTeachingAdvisories(practiceUnitId)
                .collect { priorAdvisories = it }
        }
        SavedMistakeTutorRoute(
            key = key,
            repository = application.mistakeDetailRepository,
            organizationRepository = application.mistakeOrganizationRepository,
            teachingReferenceRepository =
                application.tutorTeachingReferenceRepository,
            modelTasks = application.modelTaskRepository,
            interactions = application.tutorInteractionRepository,
            onRecordTeachingFocus = { sessionId, practiceUnitId, labels ->
                application.applicationScope.launch {
                    runCatching {
                        application.studyRepository.recordTeachingFocus(
                            sessionId = sessionId,
                            practiceUnitId = practiceUnitId,
                            labels = labels,
                        )
                    }
                }
            },
            onRequestDebrief = { sessionId, practiceUnitId, stemMarkdown, transcriptMarkdown, labels ->
                // Silent debrief (user-approved, no UI): local-only
                // providers run it; external-provider configs skip
                // rather than ship the transcript unapproved.
                application.applicationScope.launch {
                    runCatching {
                        val capabilities = application.modelTaskRepository.capabilities()
                        val request = buildTutorDebriefRequestForApp(
                            capabilities = capabilities,
                            sessionId = sessionId,
                            practiceUnitId = practiceUnitId,
                            subject = experience.catalog
                                .firstOrNull { it.practiceUnitId == practiceUnitId }
                                ?.subject
                                ?: "GENERAL",
                            questionStemMarkdown = stemMarkdown,
                            transcriptMarkdown = transcriptMarkdown,
                            knowledgeLabels = labels,
                            requestId = "debrief:$sessionId:${System.nanoTime()}",
                            occurredAtEpochMillis = System.currentTimeMillis(),
                        ) ?: return@launch
                        application.modelTaskRepository.execute(request).collect { /* fire-and-forget; MISCONCEPTION observer persists the result */ }
                    }
                }
            },
            onRecordMisconception = { sessionId, practiceUnitId, payloadMarkdown ->
                application.applicationScope.launch {
                    runCatching {
                        application.studyRepository.recordMisconceptionAdvisory(
                            sessionId = sessionId,
                            practiceUnitId = practiceUnitId,
                            payloadMarkdown = payloadMarkdown,
                        )
                    }
                }
            },
            priorTeachingAdvisories = priorAdvisories.map { it.payloadMarkdown },
            profile = experience.profile,
            learningMemory = experience.catalog.firstOrNull { catalogEntry ->
                catalogEntry.entryId == key.entryId &&
                    catalogEntry.problemId == key.problemId &&
                    catalogEntry.problemRevisionId == key.problemRevisionId
            }?.questionMemory,
            onOpenModelSettings = { navController.navigate(Routes.Capability) },
            onBack = navController::popBackStack,
            agentConsentEnabled = agentConsentEnabled,
        )
    }
}
