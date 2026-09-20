package com.tingyun.smartmistakebook

import android.net.Uri
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
import com.tingyun.smartmistakebook.core.data.model.AttachedImageGeneratorFactory
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.MistakeSourceAsset
import com.tingyun.smartmistakebook.core.domain.MistakeSourceLocation
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.domain.StudyDataStatus
import com.tingyun.smartmistakebook.core.domain.StudyExperienceRepository
import com.tingyun.smartmistakebook.core.domain.StudyExperienceSnapshot
import com.tingyun.smartmistakebook.core.domain.StudyReviewAdvanceResult
import com.tingyun.smartmistakebook.core.domain.TutorJudgedReviewSettlement
import com.tingyun.smartmistakebook.core.domain.TutorJudgedReviewSettlementStatus
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.NetworkMode
import com.tingyun.smartmistakebook.core.model.TeachingAdvisoryRecord
import com.tingyun.smartmistakebook.feature.review.CapturedReviewSessionScreen
import com.tingyun.smartmistakebook.feature.review.ReviewSessionScreen
import com.tingyun.smartmistakebook.feature.tutor.SavedMistakeTutorRoute
import com.tingyun.smartmistakebook.feature.tutor.buildTutorDebriefRequestForApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

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

    // 讲题判定的自动结算（第2条）：这道无工件题在讲题页被检查过之后，把判定落成 attempt
    // 并推进队列。幂等（同一队列项只结算一次），没有判定时是 no-op，所以每次进入/账本
    // 变化都可以放心地试一次。
    //
    // 推进**用结算自己的结果**（result 就是 StudyReviewAdvanceResult），不走"从 experience
    // 快照对账"：快照要等账本/投影 Flow 才更新，照抄它会把刚推进的显示倒回上一题，或者
    // 在它短暂重建时把整页弹走（RootExperience 的
    // submittedReviewItemIsAlreadyAdvancedWhenUserLeavesBeforeNext 就是被这个坑红的）。
    // 有机判工件的题不走这条通道（研究 §4(iii)8：可机器判分的题必须走客观通道）。
    LaunchedEffect(
        experience.status,
        displayedSessionId,
        displayedSessionVersion,
        displayedPracticeUnitId,
        displayedOrdinal,
        artifactLoad.isLoaded,
        artifactLoad.artifact,
    ) {
        if (experience.status != StudyDataStatus.READY) return@LaunchedEffect
        val settleSessionId = displayedSessionId ?: return@LaunchedEffect
        val settleVersion = displayedSessionVersion ?: return@LaunchedEffect
        val settlePracticeUnitId = displayedPracticeUnitId ?: return@LaunchedEffect
        val settleOrdinal = displayedOrdinal ?: return@LaunchedEffect
        // 只有在"这道题没有机判选项"时才走讲题判定通道：机判项的答案必须走客观通道
        // （研究 §4(iii)8）。无工件题（captured 分支）与有工件但无 assessment item 的
        // 题（ReviewSessionScreen 的「去讲题判定」分支）都满足这个条件。
        val hasMachineCheckableItem =
            artifactLoad.artifact?.assessmentItems?.singleOrNull() != null
        if (!artifactLoad.isLoaded ||
            artifactLoad.practiceUnitId != settlePracticeUnitId ||
            hasMachineCheckableItem
        ) {
            return@LaunchedEffect
        }
        val result = runCatching {
            repository.settleTutorJudgedReview(
                TutorJudgedReviewSettlement(
                    requestId = "tutor-judged-settle:$settleSessionId:$settleOrdinal",
                    sessionId = settleSessionId,
                    expectedStateVersion = settleVersion,
                    practiceUnitId = settlePracticeUnitId,
                    presentationId = "presentation:tutor-judged:$settleSessionId:$settleOrdinal",
                    occurredAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }.getOrNull()
        if (result != null && result.status == TutorJudgedReviewSettlementStatus.RECORDED) {
            stageReviewAdvance(result)
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
            queuePosition = ordinal + 1,
            queueSize = queueSize,
            // 无工件错题的唯一作答面是讲题判定；strictOffline 构建里没有模型可言，
            // 如实说明而不是给一个走不通的按钮（该复习项保持到期）。
            tutorJudgedAvailable = capabilities.networkMode != NetworkMode.STRICT_OFFLINE,
            onOpenTutorJudge = {
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

/**
 * 错题详情「讲解这道题」、复习预判（`onRequestTutorPretest`）与判题复核（`onOpenTutorJudge`）
 * 三条入口都落在这里：它们带的是同一份题身份（entryId/problemId/problemRevisionId），
 * 进入的是同一个讲题页面，题作为**本轮附件**显示在页面里；底部导航仍停在「智能体」。
 */
@Composable
internal fun SavedMistakeTutorDestination(
    entry: NavBackStackEntry,
    experience: StudyExperienceSnapshot,
    application: SmartMistakeBookApplication,
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
            // 学生文字要落进 tutor_message，写侧门控才能逐字核对模型引文（纯文字作答同理）。
            conversations = application.tutorConversationRepository,
            catalogEntries = experience.catalog,
            // 会话里也能像大厅一样给学生消息附图（例如自己的手写过程）。
            imageIntake = application.lobbyMessageImageIntake,
            // 模型要的配图（重绘题面 / 过程图）要在这一页真的画出来：错题讲题页此前拿不到
            // 解析器，整段被跳过（只有拍照会话传了它）。题面字节取自这道题的规范资产，
            // 与会话页同一条"逐位核对"纪律，见 savedMistakeSheetBytes。
            attachedImageResolver = AttachedImageGeneratorFactory.create(
                context = application,
                configurationStore = application.modelConfigurationStore,
                networkRequestsAllowed = application.capabilities.networkRequestsAllowed,
                resolveCurrentSheetBytes = {
                    savedMistakeSheetBytes(application.mistakeDetailRepository, key)
                },
            ),
            // 意图确认按钮的落地动作（此前是默认的 {}，点了没反应）：
            // 「确认加入错题本」——这道题本来就在错题本里，把学生带到它所在的地方；
            // 「确认结束且不保存」——离开这次讲题，题与对话都已保存，不会丢弃任何东西。
            onRequestSave = {
                navController.navigate(Routes.Library) { launchSingleTop = true }
            },
            onRequestEnd = navController::popBackStack,
            onOpenMistakeNotebook = {
                navController.navigate(Routes.Library) { launchSingleTop = true }
            },
            onOpenProfile = {
                navController.navigate(Routes.Profile) { launchSingleTop = true }
            },
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
            onOpenHistory = { navController.navigate(Routes.TutorHistory) },
            onBack = navController::popBackStack,
        )
    }
}

/** 错题题面里"干净重绘"的角色名（与 core:database / core:export / feature:library 同值）。 */
internal const val CLEAN_PROBLEM_SHEET_ROLE = "CLEAN_IMAGE"

/**
 * 错题讲题页的「当前题面」字节：模型申请重绘题面（`REDRAW_PROBLEM`）时用它，**绝不能由模型提供**。
 *
 * 与拍照会话同一条纪律（`RoomCaptureWorkflowRepository.readTutorSessionSheetBytes`）：字节
 * 必须与规范记录逐位一致（sha256 + 字节数），核对不过就按"没有这张图"处理 —— 磁盘上的文件
 * 被替换或损坏时，那些字节不该被 POST 给图像模型。取干净重绘优先、否则原图，与错题详情、
 * 导出同优先级；两者都没有（旧记录 / 本机文件缺失）时返回 null。
 */
internal suspend fun savedMistakeSheetBytes(
    repository: MistakeDetailRepository,
    key: MistakeRevisionKey,
): ByteArray? {
    val ready = runCatching { repository.readExact(key) }.getOrNull() as? MistakeDetailState.Ready
        ?: return null
    val asset = sheetSourceAsset(ready.detail.source) ?: return null
    val localUri = (asset.location as? MistakeSourceLocation.Available)?.localUri ?: return null
    val bytes = runCatching {
        withContext(Dispatchers.IO) {
            File(Uri.parse(localUri).path.orEmpty()).readBytes()
        }
    }.getOrNull() ?: return null
    return bytes.takeIf(asset::matchesRecordedBytes)
}

/** 重绘题面用哪一张：干净重绘优先，否则原图；没有本机位置的资产直接跳过。 */
internal fun sheetSourceAsset(source: MistakeSourceSet): MistakeSourceAsset? {
    val assets = (source as? MistakeSourceSet.Present)?.assets.orEmpty()
        .filter { it.location is MistakeSourceLocation.Available }
    return assets.firstOrNull { it.role == CLEAN_PROBLEM_SHEET_ROLE } ?: assets.firstOrNull()
}

/** 字节与规范记录是否逐位一致（sha256 + 字节数）；不一致就不该出网。 */
internal fun MistakeSourceAsset.matchesRecordedBytes(bytes: ByteArray): Boolean {
    if (bytes.size.toLong() != byteSize) return false
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) } == contentSha256
}
