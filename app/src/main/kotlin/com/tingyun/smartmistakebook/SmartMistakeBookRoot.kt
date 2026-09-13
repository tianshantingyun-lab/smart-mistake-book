package com.tingyun.smartmistakebook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.data.model.AttachedImageGeneratorFactory
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.currentCapabilityVerification
import com.tingyun.smartmistakebook.core.domain.StudyDataStatus
import com.tingyun.smartmistakebook.core.domain.StudyReviewAdvanceResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus
import com.tingyun.smartmistakebook.core.domain.PrerequisiteRemediation
import com.tingyun.smartmistakebook.core.domain.ReTeachOpening
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact
import com.tingyun.smartmistakebook.core.model.TeachingAdvisoryRecord
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.Paper
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.SmartDimens
import com.tingyun.smartmistakebook.feature.capture.CaptureScreen
import com.tingyun.smartmistakebook.feature.library.BatchImportRoute
import com.tingyun.smartmistakebook.feature.library.LibraryRoute
import com.tingyun.smartmistakebook.feature.library.InvalidMistakeExportRoute
import com.tingyun.smartmistakebook.feature.library.MistakeBatchExportRoute
import com.tingyun.smartmistakebook.feature.library.MAX_LIBRARY_BATCH_EXPORT_QUESTIONS
import com.tingyun.smartmistakebook.feature.library.MistakeDetailRoute
import com.tingyun.smartmistakebook.feature.library.MistakeExportRoute
import com.tingyun.smartmistakebook.core.domain.SchedulingOptions
import com.tingyun.smartmistakebook.core.domain.SplitImportRepository
import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewSessionPlan
import com.tingyun.smartmistakebook.feature.library.SplitImportReviewRoute
import com.tingyun.smartmistakebook.feature.profile.ProfileRoute
import com.tingyun.smartmistakebook.feature.review.CapturedReviewSessionScreen
import com.tingyun.smartmistakebook.feature.review.KnowledgeReviewQuizLoader
import com.tingyun.smartmistakebook.feature.review.KnowledgeReviewSessionScreen
import com.tingyun.smartmistakebook.feature.review.ReviewRoute
import com.tingyun.smartmistakebook.feature.review.ReviewSessionScreen
import com.tingyun.smartmistakebook.feature.tutor.CapturedTutorSessionRoute
import com.tingyun.smartmistakebook.feature.tutor.SavedMistakeTutorRoute
import com.tingyun.smartmistakebook.feature.tutor.TutorHistoryRoute
import com.tingyun.smartmistakebook.feature.tutor.buildTutorDebriefRequestForApp
import com.tingyun.smartmistakebook.feature.tutor.TutorRoute
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal object Routes {
    const val Review = "review"
    const val Tutor = "tutor"
    const val Library = "library"
    const val Profile = "profile"
    const val ReviewSession = "review/session"
    const val KnowledgeReviewSession = "review/knowledge-session"
    const val CaptureTutor = "capture/tutor"
    const val CaptureLibrary = "capture/library"
    const val BatchImport = "capture/batch"
    const val LibraryBatchExport = "library/export"
    const val CaptureResume = "capture/resume/{draftId}"
    const val SplitReview = "capture/split-review"
    const val CapturedTutorSession = "tutor/captured/{sessionId}"
    const val TutorHistory = "tutor/history"
    const val TutorTextConversation = "tutor/lobby/{conversationId}"
    const val MistakeDetail = "mistake/{itemId}"
    const val MistakeTutor = "mistake/tutor/{entryId}/{problemId}/{problemRevisionId}"
    const val MistakeExport =
        "mistake/export/{entryId}/{problemId}/{problemRevisionId}"
    const val Capability = "settings/capability"
    const val LearningMastery = "profile/learning-mastery"
    const val Privacy = "settings/privacy"
    const val Reminder = "settings/reminder"
    const val Scheduling = "settings/scheduling"
    const val Storage = "settings/storage"

    fun mistakeDetail(itemId: String): String = "mistake/${Uri.encode(itemId)}"

    fun mistakeExport(key: MistakeRevisionKey): String = listOf(
        "mistake",
        "export",
        encodeRevisionArgument(key.entryId),
        encodeRevisionArgument(key.problemId),
        encodeRevisionArgument(key.problemRevisionId),
    ).joinToString("/")

    fun mistakeTutor(key: MistakeRevisionKey): String = listOf(
        "mistake",
        "tutor",
        encodeRevisionArgument(key.entryId),
        encodeRevisionArgument(key.problemId),
        encodeRevisionArgument(key.problemRevisionId),
    ).joinToString("/")

    fun decodeMistakeExportKey(
        entryId: String?,
        problemId: String?,
        problemRevisionId: String?,
    ): MistakeRevisionKey? = runCatching {
        MistakeRevisionKey(
            entryId = Uri.decode(entryId.orEmpty()),
            problemId = Uri.decode(problemId.orEmpty()),
            problemRevisionId = Uri.decode(problemRevisionId.orEmpty()),
        )
    }.getOrNull()

    // Navigation decodes a path argument once; keep one encoded layer for the explicit boundary decode.
    private fun encodeRevisionArgument(value: String): String = Uri.encode(Uri.encode(value))

    fun capturedTutorSession(sessionId: String): String = "tutor/captured/${Uri.encode(sessionId)}"

    fun tutorTextConversation(conversationId: String): String =
        "tutor/lobby/${Uri.encode(conversationId)}"

    fun captureResume(draftId: String): String = "capture/resume/${Uri.encode(draftId)}"
}

private data class RootDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val testTag: String,
)

internal data class TeachingArtifactLoad(
    val practiceUnitId: String? = null,
    val artifact: VerifiedTeachingArtifact? = null,
    /** Spec §2.16: non-null only when this card is a leech with reviewed material. */
    val reTeachOpening: ReTeachOpening? = null,
    /** Spec §2.9: non-null when a prerequisite of this card is below ready. */
    val prerequisiteRemediation: PrerequisiteRemediation? = null,
    val isLoaded: Boolean = false,
    /**
     * 非 null 表示**这一次读取失败了**，而不是"这道题没有可读的题干"。
     *
     * 两者的区别就是这条维度存在的理由（审计 N-12）：`artifact == null` 是**正常**的
     * ——实拍题本来就没有策展件；而读取过程抛异常是**故障**。此前两者共用 `isLoaded`，
     * 于是故障的表现是 `isLoaded` 永远停在 false、界面永远停在「正在读取题目…」，
     * 用户既看不到原因也没法继续。文案见 [teachingArtifactFailureMessage]。
     */
    val loadFailureDiagnosticId: String? = null,
)

private val rootDestinations = listOf(
    RootDestination(Routes.Review, "复习", Icons.Outlined.EventAvailable, "nav_review"),
    RootDestination(Routes.Tutor, "智能体", Icons.AutoMirrored.Outlined.Chat, "nav_tutor"),
    RootDestination(Routes.Library, "错题本", Icons.AutoMirrored.Outlined.MenuBook, "nav_library"),
    RootDestination(Routes.Profile, "我的", Icons.Outlined.ManageAccounts, "nav_profile"),
)

internal fun bottomBarRouteFor(route: String?): String? = when (route) {
    Routes.Review,
    Routes.Tutor,
    Routes.Library,
    Routes.Profile,
    -> route
    Routes.CapturedTutorSession,
    Routes.MistakeTutor,
    Routes.TutorHistory,
    Routes.TutorTextConversation,
    -> Routes.Tutor
    else -> null
}

@Composable
internal fun SmartMistakeBookRoot(reviewOpenRequests: StateFlow<Long>) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val application = context.applicationContext as SmartMistakeBookApplication
    val repository = application.studyRepository
    val baseCapabilities = application.capabilities
    val startupState by application.startupState.collectAsStateWithLifecycle()
    val consentStore = application.modelAgentConsentStore
    val agentConsentEnabled = consentStore?.consentEnabled
        ?.collectAsStateWithLifecycle(initialValue = true)
        ?.value ?: true
    val configurationStore = application.modelConfigurationStore
    val modelConfiguration = if (configurationStore != null) {
        configurationStore.configuration
            .collectAsStateWithLifecycle(initialValue = ModelConfigurationSnapshot())
            .value
    } else {
        ModelConfigurationSnapshot()
    }
    val verifiedModelCapabilities = modelConfiguration.currentCapabilityVerification()
    val capabilities = baseCapabilities.copy(
        remoteModelConfigured = modelConfiguration.isConfigured,
        remoteModelCapabilitiesTested = verifiedModelCapabilities != null,
        remoteModelImageInputVerified =
            verifiedModelCapabilities?.supportsImageInput == true,
        remoteModelStructuredOutputVerified =
            verifiedModelCapabilities?.supportsStructuredOutput == true,
    )
    val applicationUiScope = rememberCoroutineScope()
    val experience by repository.snapshot.collectAsStateWithLifecycle()
    val tutorArtifactLoad by produceState(
        initialValue = TeachingArtifactLoad(),
        key1 = experience.status,
        key2 = experience.tutorPracticeUnitId,
    ) {
        val requestedId = experience.tutorPracticeUnitId
        value = TeachingArtifactLoad(practiceUnitId = requestedId)
        val loadedArtifact = requestedId
            ?.takeIf { experience.status == StudyDataStatus.READY }
            ?.let { repository.teachingArtifact(it) }
        currentCoroutineContext().ensureActive()
        value = TeachingArtifactLoad(
            practiceUnitId = requestedId,
            artifact = loadedArtifact,
            isLoaded = true,
        )
    }
    val tutorArtifact = tutorArtifactLoad.artifact.takeIf {
        experience.status == StudyDataStatus.READY &&
            tutorArtifactLoad.isLoaded &&
            tutorArtifactLoad.practiceUnitId == experience.tutorPracticeUnitId
    }
    val navController = rememberNavController()
    var pendingLibraryExportEntryIds by rememberSaveable {
        mutableStateOf<List<String>>(emptyList())
    }
    var pendingLibraryExportCount by rememberSaveable { mutableIntStateOf(0) }
    val reviewOpenRequest by reviewOpenRequests.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.Review
    val isRootDestination = rootDestinations.any { it.route == currentRoute }
    val selectedBottomRoute = bottomBarRouteFor(currentRoute)

    LaunchedEffect(reviewOpenRequest) {
        if (reviewOpenRequest > 0L) {
            navController.navigate(Routes.Review) {
                popUpTo(navController.graph.findStartDestination().id)
                launchSingleTop = true
            }
        }
    }

    BackHandler(enabled = isRootDestination && activity != null) {
        activity?.moveTaskToBack(true)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .semantics { testTagsAsResourceId = true },
        containerColor = Paper,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (selectedBottomRoute != null) {
                SmartBottomBar(
                    selectedRoute = selectedBottomRoute,
                    onSelect = { destination ->
                        if (destination.route != bottomBarRouteFor(currentRoute)) {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            StartupStateBanner(
                state = startupState,
                onRetry = if (startupState.isRetryable) {
                    { application.refreshStudyExperience() }
                } else {
                    null
                },
            )
            StudyDataStatusLine(experience.status)
            NavHost(
                navController = navController,
                startDestination = Routes.Review,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = innerPadding.calculateBottomPadding()),
            ) {
            composable(Routes.Review) { entry ->
                val destinationLifecycle by entry.lifecycle.currentStateFlow
                    .collectAsStateWithLifecycle()
                if (destinationLifecycle == Lifecycle.State.RESUMED) {
                    // 今日可复习知识点数（spec dual-review-entry §3.1）：仅在复习首页可见且模型
                    // 可用时实算一次（含材料可出题过滤），避免把材料读取放进 snapshot 发布热路径。
                    // 键含 planId/profile：计划或掌握态变化时重算；失败/无计划 → null（不显示入口）。
                    val knowledgeReviewCount by produceState<Int?>(
                        initialValue = null,
                        key1 = experience.status,
                        key2 = Triple(
                            experience.review.planId,
                            experience.profile,
                            capabilities.remoteModelAvailable,
                        ),
                    ) {
                        value = if (
                            experience.status == StudyDataStatus.READY &&
                            capabilities.remoteModelAvailable
                        ) {
                            try {
                                repository.currentKnowledgeReviewPlan(
                                    requestId = "knowledge-review-count:${UUID.randomUUID()}",
                                    occurredAtEpochMillis = System.currentTimeMillis(),
                                )?.queue?.size
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                    }
                    ReviewRoute(
                        overview = experience.review,
                        profile = experience.profile,
                        knowledgeReviewCount = knowledgeReviewCount,
                        onStartReview = {
                            applicationUiScope.launch {
                                try {
                                    val progress = repository.startOrResumeReviewSession(
                                        requestId = "review-start:${UUID.randomUUID()}",
                                        occurredAtEpochMillis = System.currentTimeMillis(),
                                    )
                                    if (
                                        progress?.status == StudyReviewSessionStatus.ACTIVE &&
                                        entry.lifecycle.currentState == Lifecycle.State.RESUMED
                                    ) {
                                        navController.navigate(Routes.ReviewSession) {
                                            launchSingleTop = true
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // The shared repository snapshot exposes the fail-closed error state.
                                }
                            }
                        },
                        // 知识点复习的取题依赖模型现场生成（KNOWLEDGE_QUIZ），错题复习用本地
                        // verified artifact——只有模型可用时才给出知识点入口，避免进会话后取题
                        // 必然失败、重试无用的死路（strictOffline / 未配置模型时隐藏该入口）。
                        onStartKnowledgeReview = if (capabilities.remoteModelAvailable) {
                            {
                                // 知识点复习不要求错题会话已启动：目标路由进入时自行经
                                // currentKnowledgeReviewPlan 组装今日知识点计划。
                                navController.navigate(Routes.KnowledgeReviewSession) {
                                    launchSingleTop = true
                                }
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag("root_review"),
                    )
                } else {
                    ReviewSessionGateMessage("正在打开今日复习…")
                }
            }
            composable(Routes.Tutor) {
                TutorRoute(
                    isSaved = experience.tutorExampleSaved,
                    capabilities = capabilities,
                    practiceUnitId = experience.tutorPracticeUnitId.orEmpty(),
                    teachingArtifact = tutorArtifact,
                    adaptiveDecision = experience.tutorDecision.takeIf {
                        experience.status == StudyDataStatus.READY
                    },
                    profile = experience.profile,
                    onSave = {
                        val practiceUnitId = experience.tutorPracticeUnitId.orEmpty()
                        applicationUiScope.launch {
                            repository.saveTutorProblem(
                                com.tingyun.smartmistakebook.core.domain.SaveTutorProblemCommand(
                                    conversationId = practiceUnitId.ifBlank { "lobby-tutor" },
                                    ephemeralProblemId = practiceUnitId.ifBlank { "lobby-tutor" },
                                    sourceAssetIds = emptyList(),
                                    logicalOperationId = "lobby-save",
                                ),
                            )
                        }
                    },
                    onSubmitChoice = repository::submitChoice,
                    onRevealAnswer = repository::revealAnswer,
                    onCapture = { navController.navigate(Routes.CaptureTutor) },
                    onChooseExisting = { navController.navigate(Routes.Library) },
                    onOpenCapabilitySettings = { navController.navigate(Routes.Capability) },
                    onOpenMistakeNotebook = { navController.navigate(Routes.Library) },
                    onOpenProfile = { navController.navigate(Routes.Profile) },
                    onOpenHistory = { navController.navigate(Routes.TutorHistory) },
                    conversations = application.tutorConversationRepository,
                    modelTasks = application.modelTaskRepository,
                    catalogEntries = experience.catalog,
                    modifier = Modifier.testTag("root_tutor"),
                )
            }
            composable(Routes.TutorHistory) {
                TutorHistoryRoute(
                    conversations = application.tutorConversationRepository,
                    onArchive = { conversationId ->
                        applicationUiScope.launch {
                            application.tutorConversationRepository.archiveConversation(
                                com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationCommand(
                                    conversationId = conversationId,
                                    occurredAtEpochMillis = System.currentTimeMillis(),
                                ),
                            )
                        }
                    },
                    onDelete = { conversationId ->
                        applicationUiScope.launch {
                            application.tutorConversationRepository.deleteConversation(
                                com.tingyun.smartmistakebook.core.domain.DeleteTutorConversationCommand(
                                    conversationId = conversationId,
                                    occurredAtEpochMillis = System.currentTimeMillis(),
                                ),
                            )
                        }
                    },
                    onOpenTextConversation = { conversationId ->
                        navController.navigate(Routes.tutorTextConversation(conversationId)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenCapturedSession = { sessionId ->
                        navController.navigate(Routes.capturedTutorSession(sessionId)) {
                            launchSingleTop = true
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.TutorTextConversation) { entry ->
                val conversationId = entry.arguments?.getString("conversationId")
                    .orEmpty()
                if (conversationId.isBlank()) {
                    return@composable
                }
                TutorRoute(
                    isSaved = false,
                    capabilities = capabilities,
                    practiceUnitId = "",
                    teachingArtifact = null,
                    adaptiveDecision = null,
                    profile = experience.profile,
                    onSave = {
                        val id = conversationId.ifBlank { "captured-tutor" }
                        applicationUiScope.launch {
                            repository.saveTutorProblem(
                                com.tingyun.smartmistakebook.core.domain.SaveTutorProblemCommand(
                                    conversationId = id,
                                    ephemeralProblemId = id,
                                    sourceAssetIds = emptyList(),
                                    logicalOperationId = "captured-save",
                                ),
                            )
                        }
                    },
                    onSubmitChoice = repository::submitChoice,
                    onRevealAnswer = repository::revealAnswer,
                    onCapture = { navController.navigate(Routes.CaptureTutor) },
                    onChooseExisting = { navController.navigate(Routes.Library) },
                    onOpenCapabilitySettings = { navController.navigate(Routes.Capability) },
                    onOpenMistakeNotebook = { navController.navigate(Routes.Library) },
                    onOpenProfile = { navController.navigate(Routes.Profile) },
                    onOpenHistory = { navController.navigate(Routes.TutorHistory) },
                    conversations = application.tutorConversationRepository,
                    modelTasks = application.modelTaskRepository,
                    catalogEntries = experience.catalog,
                    initialConversationId = conversationId,
                    modifier = Modifier.testTag("root_tutor"),
                )
            }
            composable(Routes.Library) {
                LibraryRoute(
                    entries = experience.catalog,
                    catalogRepository = application.libraryCatalogRepository,
                    onCapture = { navController.navigate(Routes.CaptureLibrary) },
                    onBatchImport = { navController.navigate(Routes.BatchImport) },
                    onExportVisible = { entryIds ->
                        pendingLibraryExportCount = entryIds.size
                        pendingLibraryExportEntryIds =
                            entryIds.takeIf {
                                it.size <= MAX_LIBRARY_BATCH_EXPORT_QUESTIONS
                            }.orEmpty()
                        navController.navigate(Routes.LibraryBatchExport) {
                            launchSingleTop = true
                        }
                    },
                    onOpenItem = { itemId -> navController.navigate(Routes.mistakeDetail(itemId)) },
                    modifier = Modifier.testTag("root_library"),
                )
            }
            composable(Routes.Profile) {
                ProfileRoute(
                    overview = experience.profile,
                    review = experience.review,
                    capabilities = capabilities,
                    onOpenCapability = { navController.navigate(Routes.Capability) },
                    onOpenLearningMastery = {
                        navController.navigate(Routes.LearningMastery)
                    },
                    onOpenDataPrivacy = { navController.navigate(Routes.Privacy) },
                    onOpenReminder = { navController.navigate(Routes.Reminder) },
                    onOpenScheduling = { navController.navigate(Routes.Scheduling) },
                    onOpenStorage = { navController.navigate(Routes.Storage) },
                    modifier = Modifier.testTag("root_profile"),
                )
            }
            composable(Routes.ReviewSession) { entry ->
                ReviewSessionDestination(
                    entry = entry,
                    experience = experience,
                    capabilities = capabilities,
                    repository = repository,
                    navController = navController,
                )
            }
            composable(Routes.KnowledgeReviewSession) { entry ->
                val destinationLifecycle by entry.lifecycle.currentStateFlow
                    .collectAsStateWithLifecycle()
                val onKnowledgeBack = {
                    if (entry.lifecycle.currentState == Lifecycle.State.RESUMED) {
                        navController.popBackStack()
                    }
                    Unit
                }
                BackHandler(onBack = onKnowledgeBack)
                val planState by produceState<KnowledgeReviewSessionPlan?>(
                    initialValue = null,
                    key1 = experience.status,
                ) {
                    value = if (experience.status == StudyDataStatus.READY) {
                        try {
                            repository.currentKnowledgeReviewPlan(
                                requestId = "knowledge-review-plan:${UUID.randomUUID()}",
                                occurredAtEpochMillis = System.currentTimeMillis(),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                }
                val quizLoader = remember {
                    KnowledgeReviewQuizLoader(
                        modelTasks = application.modelTaskRepository,
                        references = application.tutorTeachingReferenceRepository,
                    )
                }
                when {
                    destinationLifecycle != Lifecycle.State.RESUMED ->
                        ReviewSessionGateMessage("正在打开知识点复习…")
                    experience.status != StudyDataStatus.READY ->
                        ReviewSessionGateMessage("学习记录暂时不可用，知识点复习已暂停。")
                    planState == null || planState!!.isEmpty ->
                        ReviewSessionGateMessage("今天没有需要复习的知识点——都已掌握或尚未到期。")
                    else -> KnowledgeReviewSessionScreen(
                        plan = planState!!,
                        onBack = onKnowledgeBack,
                        loadQuiz = quizLoader::loadQuiz,
                        submitAnswer = repository::submitKnowledgeQuizFeedback,
                        onFinished = onKnowledgeBack,
                        modifier = Modifier.testTag("root_knowledge_review_session"),
                    )
                }
            }
            composable(Routes.CaptureTutor) {
                CaptureScreen(
                    entryOrigin = CaptureEntryOrigin.TUTOR,
                    repository = application.captureRepository,
                    modelTasks = application.modelTaskRepository,
                    onOpenModelSettings = { navController.navigate(Routes.Capability) },
                    onTutorSessionReady = { sessionId ->
                        navController.navigate(Routes.capturedTutorSession(sessionId)) {
                            popUpTo(Routes.CaptureTutor) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onLibraryEntryReady = { entryId ->
                        navController.navigate(Routes.mistakeDetail(entryId)) {
                            popUpTo(Routes.CaptureTutor) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onSplitReady = {
                        navController.navigate(Routes.SplitReview) {
                            popUpTo(Routes.CaptureTutor) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.CaptureLibrary) {
                CaptureScreen(
                    entryOrigin = CaptureEntryOrigin.LIBRARY,
                    repository = application.captureRepository,
                    modelTasks = application.modelTaskRepository,
                    onOpenModelSettings = { navController.navigate(Routes.Capability) },
                    onTutorSessionReady = { sessionId ->
                        navController.navigate(Routes.capturedTutorSession(sessionId)) {
                            popUpTo(Routes.CaptureLibrary) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onLibraryEntryReady = { entryId ->
                        navController.navigate(Routes.mistakeDetail(entryId)) {
                            popUpTo(Routes.CaptureLibrary) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onSplitReady = {
                        navController.navigate(Routes.SplitReview) {
                            popUpTo(Routes.CaptureLibrary) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.BatchImport) {
                BatchImportRoute(
                    repository = application.batchImportRepository,
                    onOpenDraft = { draftId ->
                        navController.navigate(Routes.captureResume(draftId))
                    },
                    onSplitReady = {
                        navController.navigate(Routes.SplitReview) {
                            launchSingleTop = true
                        }
                    },
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.SplitReview) {
                SplitImportReviewRoute(
                    repository = application.splitImportRepository,
                    onOpenDraft = { draftId ->
                        navController.navigate(Routes.captureResume(draftId)) {
                            launchSingleTop = true
                        }
                    },
                    onFinished = { navController.popBackStack() },
                    modifier = Modifier.testTag("root_split_review"),
                )
            }
            composable(Routes.LibraryBatchExport) {
                val entriesById = experience.catalog.associateBy { catalogEntry ->
                    catalogEntry.entryId
                }
                MistakeBatchExportRoute(
                    entries = pendingLibraryExportEntryIds.mapNotNull(entriesById::get),
                    requestedCount = pendingLibraryExportCount,
                    repository = application.mistakeDetailRepository,
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.CaptureResume) { entry ->
                CaptureScreen(
                    // Recovery replaces this placeholder with the draft's persisted origin.
                    entryOrigin = CaptureEntryOrigin.LIBRARY,
                    resumeDraftId = entry.arguments?.getString("draftId").orEmpty(),
                    repository = application.captureRepository,
                    modelTasks = application.modelTaskRepository,
                    onOpenModelSettings = { navController.navigate(Routes.Capability) },
                    onTutorSessionReady = { sessionId ->
                        navController.navigate(Routes.capturedTutorSession(sessionId)) {
                            popUpTo(Routes.CaptureResume) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onLibraryEntryReady = { entryId ->
                        navController.navigate(Routes.mistakeDetail(entryId)) {
                            popUpTo(Routes.CaptureResume) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onSplitReady = {
                        navController.navigate(Routes.SplitReview) {
                            popUpTo(Routes.CaptureResume) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.CapturedTutorSession) { entry ->
                val sessionId = entry.arguments?.getString("sessionId").orEmpty()
                CapturedTutorSessionRoute(
                    sessionId = sessionId,
                    repository = application.captureRepository,
                    modelTasks = application.modelTaskRepository,
                    interactions = application.tutorInteractionRepository,
                    conversations = application.tutorConversationRepository,
                    profile = experience.profile,
                    catalogEntries = experience.catalog,
                    attachedImageResolver = AttachedImageGeneratorFactory.create(
                        context = application,
                        configurationStore = configurationStore,
                        // 用户同意，不是变体位：见审计 S-2。原来这里传
                        // `application.capabilities.networkRequestsAllowed`，
                        // 于是关掉「模型智能体同意」也照样把原图发出去。
                        modelAgentConsentStore = application.modelAgentConsentStore,
                        resolveCurrentSheetBytes = {
                            application.captureRepository.readTutorSessionSheetBytes(sessionId)
                        },
                    ),
                    onOpenModelSettings = { navController.navigate(Routes.Capability) },
                    onOpenMistakeNotebook = {
                        navController.navigate(Routes.Library) { launchSingleTop = true }
                    },
                    onOpenProfile = {
                        navController.navigate(Routes.Profile) { launchSingleTop = true }
                    },
                    onBack = navController::popBackStack,
                    onEndedWithoutSave = { navController.popBackStack() },
                    agentConsentEnabled = agentConsentEnabled,
                )
            }
            composable(Routes.MistakeDetail) { entry ->
                MistakeDetailRoute(
                    errorBookEntryId = entry.arguments?.getString("itemId").orEmpty(),
                    repository = application.mistakeDetailRepository,
                    organizationRepository = application.mistakeOrganizationRepository,
                    modelTasks = application.modelTaskRepository,
                    profile = experience.profile,
                    catalogEntries = experience.catalog,
                    onBack = navController::popBackStack,
                    onExport = { key ->
                        navController.navigate(Routes.mistakeExport(key)) {
                            launchSingleTop = true
                        }
                    },
                    onTutor = { key ->
                        navController.navigate(Routes.mistakeTutor(key)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenRelatedMistake = { relatedEntryId ->
                        navController.navigate(Routes.mistakeDetail(relatedEntryId))
                    },
                    onOpenModelSettings = { navController.navigate(Routes.Capability) },
                )
            }
            composable(Routes.MistakeTutor) { entry ->
                SavedMistakeTutorDestination(
                    entry = entry,
                    experience = experience,
                    application = application,
                    agentConsentEnabled = agentConsentEnabled,
                    navController = navController,
                )
            }
            composable(Routes.MistakeExport) { entry ->
                val key = Routes.decodeMistakeExportKey(
                    entryId = entry.arguments?.getString("entryId"),
                    problemId = entry.arguments?.getString("problemId"),
                    problemRevisionId = entry.arguments?.getString("problemRevisionId"),
                )
                if (key == null) {
                    InvalidMistakeExportRoute(onBack = navController::popBackStack)
                } else {
                    MistakeExportRoute(
                        key = key,
                        repository = application.mistakeDetailRepository,
                        onBack = navController::popBackStack,
                    )
                }
            }
            composable(Routes.Capability) {
                CapabilityScreen(
                    capabilities = capabilities,
                    configurationStore = application.modelConfigurationStore,
                    capabilityTester = application.modelCapabilityTester,
                    modelAgentConsentStore = application.modelAgentConsentStore,
                    calibrationReportProvider = { application.studyRepository.calibrationReport() },
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.LearningMastery) {
                LearningMasteryScreen(
                    overview = experience.profile,
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.Privacy) {
                DataPrivacyScreen(
                    capabilities = capabilities,
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.Reminder) {
                var suggestedReminderMinute by remember { mutableStateOf<Int?>(null) }
                LaunchedEffect(Unit) {
                    suggestedReminderMinute = application.studyRepository.suggestedReminderMinute()
                }
                ReminderScreen(
                    repository = application.reviewReminderRepository,
                    onRefreshSchedule = application::refreshReviewReminderSchedule,
                    onBack = navController::popBackStack,
                    suggestedMinute = suggestedReminderMinute,
                )
            }
            composable(Routes.Scheduling) {
                val schedulingOptions by application.schedulingSettingsStore.options
                    .collectAsStateWithLifecycle(initialValue = SchedulingOptions())
                val exams by application.schedulingSettingsStore.exams
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                val schedulingScope = rememberCoroutineScope()
                var retentionHint by remember {
                    mutableStateOf<com.tingyun.smartmistakebook.core.domain.OptimalRetention.Recommendation?>(null)
                }
                LaunchedEffect(schedulingOptions) {
                    retentionHint = application.studyRepository.recommendedDesiredRetention()
                }
                SchedulingSettingsScreen(
                    options = schedulingOptions,
                    exams = exams,
                    retentionHint = retentionHint,
                    onSetOptions = { updated ->
                        schedulingScope.launch {
                            application.schedulingSettingsStore.setOptions(updated)
                        }
                    },
                    onAddExam = { entry ->
                        schedulingScope.launch {
                            application.schedulingSettingsStore.addExam(entry)
                        }
                    },
                    onRemoveExam = { entryId ->
                        schedulingScope.launch {
                            application.schedulingSettingsStore.removeExam(entryId)
                        }
                    },
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.Storage) {
                StorageScreen(
                    onBack = navController::popBackStack,
                    backupRepository = application.backupRepository,
                )
            }
            }
        }
    }
}

@Composable
internal fun ReviewSessionGateMessage(message: String) {
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 24.dp)
            .testTag("review_session_gate"),
        color = InkSecondary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun StudyDataStatusLine(status: StudyDataStatus) {
    val message = when (status) {
        StudyDataStatus.LOADING -> "正在读取本机学习记录…"
        StudyDataStatus.ERROR -> "本机学习数据暂时无法更新；不会用空白结果替代已有记录。"
        StudyDataStatus.READY -> return
    }
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (status == StudyDataStatus.ERROR) JadeSoft else Paper)
            .padding(horizontal = 26.dp, vertical = 8.dp),
        color = if (status == StudyDataStatus.ERROR) InkSecondary else JadeActive,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun SmartBottomBar(
    selectedRoute: String,
    onSelect: (RootDestination) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(SmartDimens.BottomBarHeight)
            .background(Paper),
    ) {
        PaperDivider()
        NavigationBar(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding(),
            containerColor = Paper,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0),
        ) {
            rootDestinations.forEach { destination ->
                val selected = selectedRoute == destination.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(destination) },
                    icon = {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = null,
                            modifier = Modifier.size(27.dp),
                        )
                    },
                    label = {
                        Text(
                            text = destination.label,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    },
                    modifier = Modifier.testTag(destination.testTag),
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = JadeActive,
                        selectedTextColor = JadeActive,
                        indicatorColor = JadeSoft,
                        unselectedIconColor = InkSecondary,
                        unselectedTextColor = InkSecondary,
                    ),
                    alwaysShowLabel = true,
                )
            }
        }
    }
}

internal fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
