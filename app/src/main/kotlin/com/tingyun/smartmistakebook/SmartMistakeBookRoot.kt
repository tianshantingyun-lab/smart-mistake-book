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
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.currentCapabilityVerification
import com.tingyun.smartmistakebook.core.domain.StudyDataStatus
import com.tingyun.smartmistakebook.core.domain.StudyReviewAdvanceResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact
import com.tingyun.smartmistakebook.core.model.TutorAutoStartAuthorization
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.Paper
import com.tingyun.smartmistakebook.core.ui.RootBottomBarFrame
import com.tingyun.smartmistakebook.core.ui.SmartDimens
import com.tingyun.smartmistakebook.feature.capture.CaptureScreen
import com.tingyun.smartmistakebook.feature.capture.CaptureInitialAction
import com.tingyun.smartmistakebook.feature.library.BatchImportRoute
import com.tingyun.smartmistakebook.feature.library.LibraryRoute
import com.tingyun.smartmistakebook.feature.library.InvalidMistakeExportRoute
import com.tingyun.smartmistakebook.feature.library.MistakeBatchExportRoute
import com.tingyun.smartmistakebook.feature.library.MAX_LIBRARY_BATCH_EXPORT_QUESTIONS
import com.tingyun.smartmistakebook.feature.library.MistakeDetailRoute
import com.tingyun.smartmistakebook.feature.library.MistakeExportRoute
import com.tingyun.smartmistakebook.feature.library.PendingCaptureInboxRoute
import com.tingyun.smartmistakebook.feature.profile.ProfileRoute
import com.tingyun.smartmistakebook.feature.review.CapturedReviewSessionScreen
import com.tingyun.smartmistakebook.feature.review.ReviewRoute
import com.tingyun.smartmistakebook.feature.review.ReviewSessionScreen
import com.tingyun.smartmistakebook.feature.tutor.CapturedTutorSessionRoute
import com.tingyun.smartmistakebook.feature.tutor.SavedMistakeTutorRoute
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
    const val CaptureTutor = "capture/tutor"
    const val CaptureLibrary = "capture/library"
    const val CaptureInbox = "capture/inbox"
    const val BatchImport = "capture/batch"
    const val LibraryBatchExport = "library/export"
    const val CaptureResume = "capture/resume/{draftId}"
    const val CapturedTutorSession = "tutor/captured/{sessionId}"
    const val MistakeDetail = "mistake/{itemId}"
    const val MistakeTutor = "mistake/tutor/{entryId}/{problemId}/{problemRevisionId}"
    const val MistakeExport =
        "mistake/export/{entryId}/{problemId}/{problemRevisionId}"
    const val Capability = "settings/capability"
    const val LearningMastery = "profile/learning-mastery"
    const val Privacy = "settings/privacy"
    const val Reminder = "settings/reminder"
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

    fun captureResume(draftId: String): String = "capture/resume/${Uri.encode(draftId)}"
}

private data class RootDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val testTag: String,
)

private data class TeachingArtifactLoad(
    val practiceUnitId: String? = null,
    val artifact: VerifiedTeachingArtifact? = null,
    val isLoaded: Boolean = false,
)

private val rootDestinations = listOf(
    RootDestination(Routes.Tutor, "讲题", Icons.AutoMirrored.Outlined.Chat, "nav_tutor"),
    RootDestination(Routes.Review, "复习", Icons.Outlined.EventAvailable, "nav_review"),
    RootDestination(Routes.Library, "错题本", Icons.AutoMirrored.Outlined.MenuBook, "nav_library"),
    RootDestination(Routes.Profile, "我的", Icons.Outlined.ManageAccounts, "nav_profile"),
)

internal val rootDestinationRoutes = rootDestinations.map(RootDestination::route)
internal const val ROOT_START_DESTINATION = Routes.Tutor

internal fun bottomBarRouteFor(route: String?): String? = when (route) {
    Routes.Review,
    Routes.Tutor,
    Routes.Library,
    Routes.Profile,
    -> route
    Routes.CapturedTutorSession,
    Routes.MistakeTutor,
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
    val tutorExplanationMode by application.tutorSettingsRepository.mode
        .collectAsStateWithLifecycle(initialValue = TutorExplanationMode.DIRECT)
    val setTutorExplanationMode: (TutorExplanationMode) -> Unit = { mode ->
        applicationUiScope.launch {
            application.tutorSettingsRepository.setMode(mode)
        }
    }
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
    var freshTutorAutoStartAuthorization by remember {
        mutableStateOf<TutorAutoStartAuthorization?>(null)
    }
    var tutorCaptureInitialAction by rememberSaveable {
        mutableStateOf(CaptureInitialAction.CAMERA)
    }
    var pendingLibraryExportEntryIds by rememberSaveable {
        mutableStateOf<List<String>>(emptyList())
    }
    var pendingLibraryExportCount by rememberSaveable { mutableIntStateOf(0) }
    val reviewOpenRequest by reviewOpenRequests.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROOT_START_DESTINATION
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
                        if (destination.route != selectedBottomRoute) {
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
            StudyDataStatusLine(experience.status)
            NavHost(
                navController = navController,
                startDestination = ROOT_START_DESTINATION,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = innerPadding.calculateBottomPadding()),
            ) {
            composable(Routes.Review) { entry ->
                val destinationLifecycle by entry.lifecycle.currentStateFlow
                    .collectAsStateWithLifecycle()
                if (destinationLifecycle == Lifecycle.State.RESUMED) {
                    ReviewRoute(
                        overview = experience.review,
                        profile = experience.profile,
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
                      onSave = { repository.saveTutorExampleMistake() },
                      onSubmitChoice = repository::submitChoice,
                      onCancelChoiceSubmission = repository::cancelChoiceSubmission,
                      onRevealAnswer = repository::revealAnswer,
                    onCapture = {
                        tutorCaptureInitialAction = CaptureInitialAction.CAMERA
                        navController.navigate(Routes.CaptureTutor)
                    },
                    onGallery = {
                        tutorCaptureInitialAction = CaptureInitialAction.GALLERY
                        navController.navigate(Routes.CaptureTutor)
                    },
                    onChooseExisting = { navController.navigate(Routes.Library) },
                    onOpenCapabilitySettings = { navController.navigate(Routes.Capability) },
                    onOpenMistakeNotebook = { navController.navigate(Routes.Library) },
                    onOpenProfile = { navController.navigate(Routes.Profile) },
                    modelTasks = application.modelTaskRepository,
                    catalogEntries = experience.catalog,
                    explanationMode = tutorExplanationMode,
                    onExplanationModeChange = setTutorExplanationMode,
                    modifier = Modifier.testTag("root_tutor"),
                )
            }
            composable(Routes.Library) {
                LibraryRoute(
                    entries = experience.catalog,
                    pendingCaptureCount = experience.pendingCorrectionCount,
                    onCapture = { navController.navigate(Routes.CaptureLibrary) },
                    onBatchImport = { navController.navigate(Routes.BatchImport) },
                    onOpenPendingCaptures = { navController.navigate(Routes.CaptureInbox) },
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
                    onOpenStorage = { navController.navigate(Routes.Storage) },
                    modifier = Modifier.testTag("root_profile"),
                )
            }
            composable(Routes.ReviewSession) { entry ->
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
                    value = TeachingArtifactLoad(
                        practiceUnitId = requestedId,
                        artifact = loadedArtifact,
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
            composable(Routes.CaptureTutor) {
                CaptureScreen(
                    entryOrigin = CaptureEntryOrigin.TUTOR,
                    initialAction = tutorCaptureInitialAction,
                    repository = application.captureRepository,
                    modelTasks = application.modelTaskRepository,
                    onOpenModelSettings = { navController.navigate(Routes.Capability) },
                    onTutorSessionReady = { sessionId, autoStartAuthorization ->
                        freshTutorAutoStartAuthorization = autoStartAuthorization
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
                        navController.navigate(Routes.CaptureInbox) {
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
                    onTutorSessionReady = { sessionId, autoStartAuthorization ->
                        freshTutorAutoStartAuthorization = autoStartAuthorization
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
                        navController.navigate(Routes.CaptureInbox) {
                            popUpTo(Routes.CaptureLibrary) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.CaptureInbox) {
                PendingCaptureInboxRoute(
                    repository = application.captureRepository,
                    onResumeDraft = { draftId ->
                        navController.navigate(Routes.captureResume(draftId))
                    },
                    onOpenTutorSession = { sessionId ->
                        freshTutorAutoStartAuthorization = null
                        navController.navigate(Routes.capturedTutorSession(sessionId))
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
                    onBack = navController::popBackStack,
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
                    onTutorSessionReady = { sessionId, autoStartAuthorization ->
                        freshTutorAutoStartAuthorization = autoStartAuthorization
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
                        navController.navigate(Routes.CaptureInbox) {
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
                    autoStartAuthorization = freshTutorAutoStartAuthorization?.takeIf {
                        it.sessionId == sessionId
                    },
                    onAutoStartAuthorizationConsumed = { consumedAuthorizationId ->
                        if (
                            freshTutorAutoStartAuthorization?.authorizationId ==
                            consumedAuthorizationId
                        ) {
                            freshTutorAutoStartAuthorization = null
                        }
                    },
                    repository = application.captureRepository,
                    modelTasks = application.modelTaskRepository,
                    interactions = application.tutorInteractionRepository,
                    profile = experience.profile,
                    catalogEntries = experience.catalog,
                    onOpenModelSettings = { navController.navigate(Routes.Capability) },
                    onOpenMistakeNotebook = {
                        navController.navigate(Routes.Library) { launchSingleTop = true }
                    },
                    onOpenProfile = {
                        navController.navigate(Routes.Profile) { launchSingleTop = true }
                    },
                    onCameraAttachment = {
                        tutorCaptureInitialAction = CaptureInitialAction.CAMERA
                        navController.navigate(Routes.CaptureTutor)
                    },
                    onGalleryAttachment = {
                        tutorCaptureInitialAction = CaptureInitialAction.GALLERY
                        navController.navigate(Routes.CaptureTutor)
                    },
                    onLibraryAttachment = {
                        navController.navigate(Routes.Library) { launchSingleTop = true }
                    },
                    onBack = navController::popBackStack,
                    onEndedWithoutSave = { navController.popBackStack() },
                    explanationMode = tutorExplanationMode,
                    onExplanationModeChange = setTutorExplanationMode,
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
                val key = Routes.decodeMistakeExportKey(
                    entryId = entry.arguments?.getString("entryId"),
                    problemId = entry.arguments?.getString("problemId"),
                    problemRevisionId = entry.arguments?.getString("problemRevisionId"),
                )
                if (key == null) {
                    ReviewSessionGateMessage("没有找到这道错题")
                } else {
                    SavedMistakeTutorRoute(
                        key = key,
                        repository = application.mistakeDetailRepository,
                        organizationRepository = application.mistakeOrganizationRepository,
                        teachingReferenceRepository =
                            application.tutorTeachingReferenceRepository,
                        modelTasks = application.modelTaskRepository,
                        interactions = application.tutorInteractionRepository,
                        profile = experience.profile,
                        learningMemory = experience.catalog.firstOrNull { catalogEntry ->
                            catalogEntry.entryId == key.entryId &&
                                catalogEntry.problemId == key.problemId &&
                                catalogEntry.problemRevisionId == key.problemRevisionId
                        }?.questionMemory,
                        onOpenModelSettings = { navController.navigate(Routes.Capability) },
                        onCameraAttachment = {
                            tutorCaptureInitialAction = CaptureInitialAction.CAMERA
                            navController.navigate(Routes.CaptureTutor)
                        },
                        onGalleryAttachment = {
                            tutorCaptureInitialAction = CaptureInitialAction.GALLERY
                            navController.navigate(Routes.CaptureTutor)
                        },
                        onLibraryAttachment = {
                            navController.navigate(Routes.Library) { launchSingleTop = true }
                        },
                        onBack = navController::popBackStack,
                        explanationMode = tutorExplanationMode,
                        onExplanationModeChange = setTutorExplanationMode,
                    )
                }
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
                ReminderScreen(
                    repository = application.reviewReminderRepository,
                    onRefreshSchedule = application::refreshReviewReminderSchedule,
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.Storage) {
                StorageScreen(onBack = navController::popBackStack)
            }
            }
        }
    }
}

@Composable
private fun ReviewSessionGateMessage(message: String) {
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
    RootBottomBarFrame {
        NavigationBar(
            modifier = Modifier.fillMaxSize(),
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
                            modifier = Modifier.size(SmartDimens.IconSize),
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
