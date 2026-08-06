package com.tingyun.smartmistakebook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tingyun.smartmistakebook.core.data.authority.ProductionAuthorityStartupState
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeRequest
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeState
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.ReviewReminderPreferences
import com.tingyun.smartmistakebook.core.domain.ReviewExamTarget
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.domain.currentCapabilityVerification
import com.tingyun.smartmistakebook.core.domain.TutorExplanationModeSnapshot
import com.tingyun.smartmistakebook.core.model.TutorAutoStartAuthorization
import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
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
import com.tingyun.smartmistakebook.feature.profile.ProfileProductionCapability
import com.tingyun.smartmistakebook.feature.profile.ProfileProductionCapabilityProvider
import com.tingyun.smartmistakebook.feature.profile.ProfileProductionCapabilitySource
import com.tingyun.smartmistakebook.feature.profile.ProductionProfileHomeRoute
import com.tingyun.smartmistakebook.feature.review.ProductionDailyReviewRoute
import com.tingyun.smartmistakebook.feature.review.ProductionDailyReviewSessionRoute
import com.tingyun.smartmistakebook.feature.review.ReviewHomeRequestProvider
import com.tingyun.smartmistakebook.feature.review.ReviewProductionCapability
import com.tingyun.smartmistakebook.feature.review.ReviewProductionCapabilityProvider
import com.tingyun.smartmistakebook.feature.review.ReviewProductionCapabilitySource
import com.tingyun.smartmistakebook.feature.tutor.CapturedTutorSessionRoute
import com.tingyun.smartmistakebook.feature.tutor.SavedMistakeTutorRoute
import com.tingyun.smartmistakebook.feature.tutor.TutorLobbyRoute
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal object Routes {
    const val TutorVisualIntentArgument = "visualIntent"
    const val Review = "review"
    const val Tutor = "tutor"
    const val Library = "library"
    const val Profile = "profile"
    const val ReviewSession = "review/session"
    const val CaptureTutor = "capture/tutor?visualIntent={visualIntent}"
    const val CaptureLibrary = "capture/library"
    const val CaptureInbox = "capture/inbox"
    const val BatchImport = "capture/batch"
    const val LibraryBatchExport = "library/export"
    const val CaptureResume = "capture/resume/{draftId}"
    const val CapturedTutorSession = "tutor/captured/{sessionId}?visualIntent={visualIntent}"
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

    fun captureTutor(
        visualIntent: TutorCurrentSessionVisualIntent = TutorCurrentSessionVisualIntent.NONE,
    ): String = "capture/tutor?visualIntent=${visualIntent.name}"

    fun capturedTutorSession(
        sessionId: String,
        visualIntent: TutorCurrentSessionVisualIntent = TutorCurrentSessionVisualIntent.NONE,
    ): String =
        "tutor/captured/${Uri.encode(sessionId)}?visualIntent=${visualIntent.name}"

    fun tutorVisualIntent(argument: String?): TutorCurrentSessionVisualIntent =
        runCatching {
            TutorCurrentSessionVisualIntent.valueOf(argument.orEmpty())
        }.getOrDefault(TutorCurrentSessionVisualIntent.NONE)

    fun captureResume(draftId: String): String = "capture/resume/${Uri.encode(draftId)}"
}

private data class RootDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val testTag: String,
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
private fun AuthorityStartupLoading() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Paper)
                .padding(24.dp)
                .testTag("authority_startup_loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = JadeActive,
            strokeWidth = 2.dp,
        )
        Text(
            text = "正在准备",
            modifier = Modifier.padding(top = 16.dp),
            color = InkSecondary,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun AuthorityStartupBlocked() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Paper)
                .padding(24.dp)
                .testTag("authority_startup_blocked"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "暂时无法使用",
            color = InkSecondary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun SmartMistakeBookRoot(reviewOpenRequests: StateFlow<Long>) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val application = context.applicationContext as SmartMistakeBookApplication
    val startupState by
        application.productionAuthorityStartupState.collectAsStateWithLifecycle()
    when (startupState) {
        ProductionAuthorityStartupState.Loading -> {
            AuthorityStartupLoading()
            return
        }

        is ProductionAuthorityStartupState.Blocked -> {
            AuthorityStartupBlocked()
            return
        }

        ProductionAuthorityStartupState.Ready -> Unit
    }
    val productionCapabilities =
        checkNotNull(application.publishedProductionCapabilities) {
            "Ready startup state requires one published production capability snapshot"
        }
    val modelTaskScopeOwner = remember(productionCapabilities) {
        ProductionScopedModelTaskPortOwner(productionCapabilities.modelTaskQueue)
    }
    DisposableEffect(modelTaskScopeOwner) {
        onDispose(modelTaskScopeOwner::close)
    }
    val tutorLobbyModelTasks = rememberScopedModelTaskPort(
        owner = modelTaskScopeOwner,
        key = "tutor-lobby",
    ) {
        rotating(
            feature = ProductionModelTaskFeature.TUTOR,
            allowedKinds = TUTOR_LOBBY_UI_MODEL_TASK_KINDS,
        )
    }
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
    val tutorExplanationModeSnapshot by application.tutorSettingsRepository.modeSnapshot
        .collectAsStateWithLifecycle(
            initialValue = TutorExplanationModeSnapshot(
                mode = TutorExplanationMode.DIRECT,
                modeVersion = 0L,
            ),
        )
    val tutorExplanationMode = tutorExplanationModeSnapshot.mode
    val setTutorExplanationMode: (TutorExplanationMode) -> Unit = { mode ->
        applicationUiScope.launch {
            application.tutorSettingsRepository.setMode(mode)
        }
    }
    val studentMistakeLibraryCatalogState by
        productionCapabilities.studentMistakeCatalog.state.collectAsStateWithLifecycle()
    val libraryCatalog =
        studentMistakeLibraryCatalogState.verifiedEntriesOrNull()
            ?: emptyList()
    val pendingCaptures by
        productionCapabilities.captureWorkflow.observePendingCaptures()
            .collectAsStateWithLifecycle(initialValue = emptyList())
    val publishedReview = productionCapabilities.reviewPlanning
    val reviewReminderPreferences by application.reviewReminderRepository.preferences
        .collectAsStateWithLifecycle(initialValue = ReviewReminderPreferences())
    val reviewRequestProvider = remember(reviewReminderPreferences.pacingLevel) {
        ReviewHomeRequestProvider {
            val zone = ZoneId.systemDefault()
            ReviewHomeRequest(
                localDayEpochDay = LocalDate.now(zone).toEpochDay(),
                timeZoneId = zone.id,
                requestedAtEpochMillis = System.currentTimeMillis(),
                pacingLevel = reviewReminderPreferences.pacingLevel,
                examTarget =
                    reviewReminderPreferences.examSubject?.let { subject ->
                        reviewReminderPreferences.examEpochDay?.let { day ->
                            ReviewExamTarget(
                                subject = subject,
                                examAtEpochMillis = day * 86_400_000L,
                            )
                        }
                    },
            )
        }
    }
    val reviewCapabilityProvider =
        remember(publishedReview, reviewRequestProvider) {
            ReviewProductionCapabilityProvider(
                adapterAvailability = application.productionAdapterAvailability,
                capabilitySource =
                    ReviewProductionCapabilitySource {
                        ReviewProductionCapability(
                            planning = publishedReview.planning,
                            sessionActions = publishedReview.sessionActions,
                            pacingActions = publishedReview.pacingActions,
                            answerSubmissionPorts = publishedReview.answerSubmissionPorts,
                            assistanceActions = publishedReview.assistanceActions,
                            pacingCommandFactory = publishedReview.pacingCommandFactory,
                            requestProvider = reviewRequestProvider,
                        )
                    },
            )
        }
    val profileCapabilityProvider =
        remember(productionCapabilities) {
            ProfileProductionCapabilityProvider(
                adapterAvailability = application.productionAdapterAvailability,
                capabilitySource =
                    ProfileProductionCapabilitySource {
                        ProfileProductionCapability(
                            productionCapabilities
                                .tutorMasteryAndProfile
                                .learningMasteryDisplay,
                        )
                    },
            )
        }
    var activeReviewHome by remember {
        mutableStateOf<ReviewHomeState.Ready?>(null)
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
                    ProductionDailyReviewRoute(
                        capabilityProvider = reviewCapabilityProvider,
                        onOpenSession = { home ->
                            activeReviewHome = home
                            navController.navigate(Routes.ReviewSession) {
                                launchSingleTop = true
                            }
                        },
                        modifier = Modifier.testTag("root_review"),
                    )
                } else {
                    ReviewSessionGateMessage("正在打开今日复习…")
                }
            }
            composable(Routes.Tutor) {
                TutorLobbyRoute(
                    onCapture = {
                        tutorCaptureInitialAction = CaptureInitialAction.CAMERA
                        navController.navigate(Routes.captureTutor())
                    },
                    onGallery = {
                        tutorCaptureInitialAction = CaptureInitialAction.GALLERY
                        navController.navigate(Routes.captureTutor())
                    },
                    onCaptureWithVisualIntent = { visualIntent ->
                        tutorCaptureInitialAction = CaptureInitialAction.CAMERA
                        navController.navigate(Routes.captureTutor(visualIntent))
                    },
                    onGalleryWithVisualIntent = { visualIntent ->
                        tutorCaptureInitialAction = CaptureInitialAction.GALLERY
                        navController.navigate(Routes.captureTutor(visualIntent))
                    },
                    onChooseExisting = { navController.navigate(Routes.Library) },
                    onOpenCapabilitySettings = { navController.navigate(Routes.Capability) },
                    onOpenMistakeNotebook = { navController.navigate(Routes.Library) },
                    onOpenProfile = { navController.navigate(Routes.Profile) },
                    modelTasks = tutorLobbyModelTasks,
                    conversationLobby = productionCapabilities.tutorConversationLobby,
                    catalogEntries = libraryCatalog,
                    explanationMode = tutorExplanationMode,
                    onExplanationModeChange = setTutorExplanationMode,
                    modifier = Modifier.testTag("root_tutor"),
                )
            }
            composable(Routes.Library) {
                LibraryRoute(
                    entries = libraryCatalog,
                    pendingCaptureCount = pendingCaptures.size,
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
                ProductionProfileHomeRoute(
                    capabilityProvider = profileCapabilityProvider,
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
                val recoveredHome by produceState<ReviewHomeState.Ready?>(
                    initialValue = activeReviewHome,
                    key1 = activeReviewHome?.session?.currentPresentationId,
                ) {
                    if (value == null) {
                        value =
                            runCatching {
                                publishedReview.planning.repository.readHome(
                                    reviewRequestProvider.currentRequest(),
                                )
                            }.getOrNull() as? ReviewHomeState.Ready
                    }
                }
                LaunchedEffect(recoveredHome) {
                    if (activeReviewHome == null) activeReviewHome = recoveredHome
                }
                val home = activeReviewHome ?: recoveredHome
                if (destinationLifecycle != Lifecycle.State.RESUMED || home == null) {
                    ReviewSessionGateMessage("正在打开复习题…")
                } else {
                    val refreshHome: () -> Unit = {
                        applicationUiScope.launch {
                            val refreshed =
                                runCatching {
                                    publishedReview.planning.repository.readHome(
                                        reviewRequestProvider.currentRequest(),
                                    )
                                }.getOrNull() as? ReviewHomeState.Ready
                            if (refreshed?.nextProblem == null) {
                                activeReviewHome = null
                                navController.popBackStack(Routes.Review, false)
                            } else {
                                activeReviewHome = refreshed
                            }
                        }
                    }
                    ProductionDailyReviewSessionRoute(
                        capabilityProvider = reviewCapabilityProvider,
                        home = home,
                        onBack = onReviewBack,
                        onOpenExplanation = { problem ->
                            val entryForProblem =
                                libraryCatalog.firstOrNull { catalogEntry ->
                                    catalogEntry.problemId == problem.problemRevision.problem.problemId &&
                                        catalogEntry.problemRevisionId ==
                                        problem.problemRevision.revisionId
                                }
                            if (entryForProblem != null) {
                                navController.navigate(
                                    Routes.mistakeTutor(
                                        MistakeRevisionKey(
                                            entryId = entryForProblem.entryId,
                                            problemId = entryForProblem.problemId,
                                            problemRevisionId = entryForProblem.problemRevisionId,
                                        ),
                                    ),
                                ) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        onPacingRecorded = { refreshHome() },
                        onVerifiedAnswerRecorded = refreshHome,
                        modifier = Modifier.testTag("root_review_session"),
                    )
                }
            }
            composable(
                route = Routes.CaptureTutor,
                arguments = listOf(
                    navArgument(Routes.TutorVisualIntentArgument) {
                        type = NavType.StringType
                        defaultValue = TutorCurrentSessionVisualIntent.NONE.name
                    },
                ),
            ) { entry ->
                val captureVisualIntent = Routes.tutorVisualIntent(
                    entry.arguments?.getString(Routes.TutorVisualIntentArgument),
                )
                val captureModelTasks = rememberScopedModelTaskPort(
                    owner = modelTaskScopeOwner,
                    key = Routes.CaptureTutor,
                ) {
                    bindOnce(
                        feature = ProductionModelTaskFeature.CAPTURE,
                        allowedKinds = CAPTURE_UI_MODEL_TASK_KINDS,
                    )
                }
                CaptureScreen(
                    entryOrigin = CaptureEntryOrigin.TUTOR,
                    initialAction = tutorCaptureInitialAction,
                    repository = productionCapabilities.captureWorkflow,
                    modelTasks = captureModelTasks,
                    onOpenModelSettings = { navController.navigate(Routes.Capability) },
                    onTutorSessionReady = { sessionId, autoStartAuthorization ->
                        freshTutorAutoStartAuthorization = autoStartAuthorization
                        navController.navigate(
                            Routes.capturedTutorSession(sessionId, captureVisualIntent),
                        ) {
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
                val captureModelTasks = rememberScopedModelTaskPort(
                    owner = modelTaskScopeOwner,
                    key = Routes.CaptureLibrary,
                ) {
                    bindOnce(
                        feature = ProductionModelTaskFeature.CAPTURE,
                        allowedKinds = CAPTURE_UI_MODEL_TASK_KINDS,
                    )
                }
                CaptureScreen(
                    entryOrigin = CaptureEntryOrigin.LIBRARY,
                    repository = productionCapabilities.captureWorkflow,
                    modelTasks = captureModelTasks,
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
                    repository = productionCapabilities.captureWorkflow,
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
                    repository = productionCapabilities.batchImport,
                    onOpenDraft = { draftId ->
                        navController.navigate(Routes.captureResume(draftId))
                    },
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.LibraryBatchExport) {
                val entriesById = libraryCatalog.associateBy { catalogEntry ->
                    catalogEntry.entryId
                }
                MistakeBatchExportRoute(
                    entries = pendingLibraryExportEntryIds.mapNotNull(entriesById::get),
                    requestedCount = pendingLibraryExportCount,
                    repository = productionCapabilities.mistakeDetail,
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.CaptureResume) { entry ->
                val draftId = entry.arguments?.getString("draftId").orEmpty()
                val captureModelTasks = rememberScopedModelTaskPort(
                    owner = modelTaskScopeOwner,
                    key = "capture-resume:$draftId",
                ) {
                    if (draftId.isBlank()) {
                        bindOnce(
                            feature = ProductionModelTaskFeature.CAPTURE,
                            allowedKinds = CAPTURE_UI_MODEL_TASK_KINDS,
                        )
                    } else {
                        exact(
                            feature = ProductionModelTaskFeature.CAPTURE,
                            subjectId = draftId,
                            allowedKinds = CAPTURE_UI_MODEL_TASK_KINDS,
                        )
                    }
                }
                CaptureScreen(
                    // Recovery replaces this placeholder with the draft's persisted origin.
                    entryOrigin = CaptureEntryOrigin.LIBRARY,
                    resumeDraftId = draftId,
                    repository = productionCapabilities.captureWorkflow,
                    modelTasks = captureModelTasks,
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
            composable(
                route = Routes.CapturedTutorSession,
                arguments = listOf(
                    navArgument(Routes.TutorVisualIntentArgument) {
                        type = NavType.StringType
                        defaultValue = TutorCurrentSessionVisualIntent.NONE.name
                    },
                ),
            ) { entry ->
                val sessionId = entry.arguments?.getString("sessionId").orEmpty()
                val capturedVisualIntent = Routes.tutorVisualIntent(
                    entry.arguments?.getString(Routes.TutorVisualIntentArgument),
                )
                val tutorModelTasks = rememberScopedModelTaskPort(
                    owner = modelTaskScopeOwner,
                    key = "captured-tutor:$sessionId",
                ) {
                    if (sessionId.isBlank()) {
                        bindOnce(
                            feature = ProductionModelTaskFeature.TUTOR,
                            allowedKinds = TUTOR_SESSION_UI_MODEL_TASK_KINDS,
                        )
                    } else {
                        exact(
                            feature = ProductionModelTaskFeature.TUTOR,
                            subjectId = sessionId,
                            allowedKinds = TUTOR_SESSION_UI_MODEL_TASK_KINDS,
                        )
                    }
                }
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
                    repository = productionCapabilities.captureWorkflow,
                    modelTasks = tutorModelTasks,
                    sessionHost = productionCapabilities.tutorSession.currentSessionHost,
                    masteryContextRepository =
                        productionCapabilities
                            .tutorMasteryAndProfile
                            .tutorMasteryContext,
                    catalogEntries = libraryCatalog,
                    onOpenModelSettings = { navController.navigate(Routes.Capability) },
                    onOpenMistakeNotebook = {
                        navController.navigate(Routes.Library) { launchSingleTop = true }
                    },
                    onOpenProfile = {
                        navController.navigate(Routes.Profile) { launchSingleTop = true }
                    },
                    onCameraAttachment = {
                        tutorCaptureInitialAction = CaptureInitialAction.CAMERA
                        navController.navigate(Routes.captureTutor())
                    },
                    onGalleryAttachment = {
                        tutorCaptureInitialAction = CaptureInitialAction.GALLERY
                        navController.navigate(Routes.captureTutor())
                    },
                    onLibraryAttachment = {
                        navController.navigate(Routes.Library) { launchSingleTop = true }
                    },
                    onBack = navController::popBackStack,
                    onEndedWithoutSave = { navController.popBackStack() },
                    explanationMode = tutorExplanationMode,
                    explanationModeVersion = tutorExplanationModeSnapshot.modeVersion,
                    onExplanationModeChange = setTutorExplanationMode,
                    visualIntent = capturedVisualIntent,
                )
            }
            composable(Routes.MistakeDetail) { entry ->
                val errorBookEntryId = entry.arguments?.getString("itemId").orEmpty()
                val libraryModelTasks = rememberScopedModelTaskPort(
                    owner = modelTaskScopeOwner,
                    key = "mistake-detail:$errorBookEntryId",
                ) {
                    bindOnce(
                        feature = ProductionModelTaskFeature.LIBRARY,
                        allowedKinds = LIBRARY_UI_MODEL_TASK_KINDS,
                    )
                }
                MistakeDetailRoute(
                    errorBookEntryId = errorBookEntryId,
                    repository = productionCapabilities.mistakeDetail,
                    organizationRepository = productionCapabilities.mistakeOrganization,
                    modelTasks = libraryModelTasks,
                    catalogEntries = libraryCatalog,
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
                    val tutorModelTasks = rememberScopedModelTaskPort(
                        owner = modelTaskScopeOwner,
                        key = "saved-tutor:${key.problemRevisionId}",
                    ) {
                        bindOnce(
                            feature = ProductionModelTaskFeature.TUTOR,
                            allowedKinds = TUTOR_SESSION_UI_MODEL_TASK_KINDS,
                        )
                    }
                    SavedMistakeTutorRoute(
                        key = key,
                        repository = productionCapabilities.mistakeDetail,
                        organizationRepository = productionCapabilities.mistakeOrganization,
                        teachingReferenceRepository =
                            productionCapabilities.tutorTeachingReference,
                        modelTasks = tutorModelTasks,
                        sessionHost = productionCapabilities.tutorSession.currentSessionHost,
                        masteryContextRepository =
                            productionCapabilities
                                .tutorMasteryAndProfile
                                .tutorMasteryContext,
                        onOpenModelSettings = { navController.navigate(Routes.Capability) },
                        onCameraAttachment = {
                            tutorCaptureInitialAction = CaptureInitialAction.CAMERA
                            navController.navigate(Routes.captureTutor())
                        },
                        onGalleryAttachment = {
                            tutorCaptureInitialAction = CaptureInitialAction.GALLERY
                            navController.navigate(Routes.captureTutor())
                        },
                        onLibraryAttachment = {
                            navController.navigate(Routes.Library) { launchSingleTop = true }
                        },
                        onBack = navController::popBackStack,
                        explanationMode = tutorExplanationMode,
                        explanationModeVersion = tutorExplanationModeSnapshot.modeVersion,
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
                        repository = productionCapabilities.mistakeDetail,
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
                LearningMasteryRoute(
                    repository =
                        productionCapabilities
                            .tutorMasteryAndProfile
                            .learningMasteryDisplay,
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.Privacy) {
                DataPrivacyScreen(
                    capabilities = capabilities,
                    learningMasteryPrivacy =
                        productionCapabilities
                            .tutorMasteryAndProfile
                            .learningMasteryPrivacy,
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
                StorageScreen(
                    learningMasteryDisplay =
                        productionCapabilities
                            .tutorMasteryAndProfile
                            .learningMasteryDisplay,
                    onBack = navController::popBackStack,
                )
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

@Composable
private fun rememberScopedModelTaskPort(
    owner: ProductionScopedModelTaskPortOwner,
    key: String,
    issue: ProductionScopedModelTaskPortOwner.() -> ScopedModelTaskPort,
): ScopedModelTaskPort {
    val port = remember(owner, key) { owner.issue() }
    DisposableEffect(port) {
        onDispose { (port as? AutoCloseable)?.close() }
    }
    return port
}

internal fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
