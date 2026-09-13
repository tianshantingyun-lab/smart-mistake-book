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
        // 一个可空局部量替掉"布尔门 ＋ 三处 requireNotNull(requestedId)"：可空性只表达一次，
        // 下面每处用 `?.let`/`if (x != null)` 都拿得到非空值，不需要再断言一遍。
        val readableUnitId = requestedId
            ?.takeIf { experience.status == StudyDataStatus.READY }
        // 题干读取走的是**抛不出异常**的通道（2026-09-13 一手复核，见审计 §12.5 N-12 的处置）：
        // 生产里 `StudyExperienceRepository.teachingArtifact` 只有一种实现，
        // `RoomBackedStudyExperienceRepository` 直接委托给 `fixtureSource`；而 `StudyFixtureSource`
        // 的两种实现都是纯查表——release 侧 `EmptyStudyFixtureSource` 的函数体就是 `= null`，
        // debug 侧是 `curatedQuestions().firstOrNull { … }?.teachingArtifact()`，不做 IO、不抛异常。
        // 工厂也没有任何装饰层。
        //
        // 所以这里**不包"读失败"兜底**：那层兜底捕不到任何真实事件，却会把 N-12 记成"已修"
        // ——机制建成、却没有能触发它的失败，正是这份审计反复点名的病。将来工件若改从库里读
        // （那时才真的会失败），再按**届时的真实失败**重新设计失败态，而不是先摆一个空壳。
        val loadedArtifact = readableUnitId?.let { repository.teachingArtifact(it) }
        currentCoroutineContext().ensureActive()
        // Spec §2.16 re-teach opening, loaded in the same round trip as the
        // artifact: only a leeched card yields one, so this stays a null lookup
        // for ordinary review. It is deliberately *not* fetched through
        // revealAnswer — that path records a "saw the answer" event, which would
        // turn the student's next attempt into a post-reveal attempt.
        //
        // 它仍然以 `artifact != null` 为门：这条通道的 KC 范围目前**仍取自策展件**
        // （`RoomBackedStudyExperienceRepository.reTeachOpening`），实拍题拿不到范围，
        // 放开这道门只会多一次必然返回 null 的查询。同一类问题登记在案，未在本批改动内。
        val reTeachOpening = if (loadedArtifact != null) {
            loadOptionalSessionCard { repository.reTeachOpening(requireNotNull(requestedId)) }
        } else {
            null
        }
        // Spec §2.9 prerequisite remediation, same round trip. Independent of the
        // leech opening: a card can be both, and neither implies the other.
        //
        // 它的门是"这道题读得出来"而**不是** `artifact != null`（审计批 2 第 3 项／S-5）：
        // 实拍题没有策展件是**正常**的，而补救要的是题库里的 KC 范围与讲解材料——以策展件
        // 为门等于把这条通道整个锁在 debug 的演示内容里，生产里一张卡都拿不到补救。
        // 两张卡都走 loadOptionalSessionCard：这里抛出去会顺着组合的协程把会话带走（N-03）。
        val prerequisiteRemediation = readableUnitId?.let { practiceUnitId ->
            loadOptionalSessionCard { repository.prerequisiteRemediation(practiceUnitId) }
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
        // 「读不出来」与「这道题本来就没有可读的题干」在这里**不是**两件事：实拍题没有策展件是
        // 正常的，而读取本身抛不出异常（见上面那段复核）。所以只有"还没读完"这一条。
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
            // Spec §2.9：实拍题也要拿得到前置补救。这条参数是本批（批 2 第 3 项）新加的——
            // 在它之前，补救只在策展屏上渲染，而实拍题走的是本屏，于是生产里从不出现。
            prerequisiteRemediation = artifactLoad.prerequisiteRemediation,
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
