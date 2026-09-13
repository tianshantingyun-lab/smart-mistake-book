package com.tingyun.smartmistakebook

import android.app.Activity
import android.app.Application
import android.os.StrictMode
import androidx.core.content.ContextCompat
import com.tingyun.smartmistakebook.core.data.capture.CaptureWorkflowRepositoryFactory
import com.tingyun.smartmistakebook.core.data.capture.ConfiguredCleanImageGeneratorFactory
import com.tingyun.smartmistakebook.core.data.capture.BatchImportRepositoryFactory
import com.tingyun.smartmistakebook.core.data.backup.BackupRepositoryFactory
import com.tingyun.smartmistakebook.core.data.backup.BackupRestoreStartupRecovery
import com.tingyun.smartmistakebook.core.data.knowledge.BundledKnowledgeBaseInstaller
import com.tingyun.smartmistakebook.core.data.knowledge.TutorTeachingReferenceRepositoryFactory
import com.tingyun.smartmistakebook.core.data.library.LibraryCatalogRepositoryFactory
import com.tingyun.smartmistakebook.core.data.library.LibraryPagingSourceProvider
import com.tingyun.smartmistakebook.core.data.mistake.MistakeDetailRepositoryFactory
import com.tingyun.smartmistakebook.core.data.mistake.MistakeOrganizationRepositoryFactory
import com.tingyun.smartmistakebook.core.data.model.ConfiguredModelGatewayFactory
import com.tingyun.smartmistakebook.core.data.model.ConfiguredModelCapabilityTesterFactory
import com.tingyun.smartmistakebook.core.data.model.ModelTaskRepositoryFactory
import com.tingyun.smartmistakebook.core.data.model.RestrictedModelAssetSourceFactory
import com.tingyun.smartmistakebook.core.data.model.UnavailableModelGateway
import com.tingyun.smartmistakebook.core.data.study.StudyExperienceRepositoryFactory
import com.tingyun.smartmistakebook.core.data.study.VisualInteractionEventSinkFactory
import com.tingyun.smartmistakebook.core.data.tutor.TutorInteractionRepositoryFactory
import com.tingyun.smartmistakebook.core.data.tutor.TutorConversationRepositoryFactory
import com.tingyun.smartmistakebook.core.data.settings.DataStoreModelAgentConsentStore
import com.tingyun.smartmistakebook.core.data.settings.DataStoreModelConfigurationStore
import com.tingyun.smartmistakebook.core.data.settings.DataStoreReviewReminderRepository
import com.tingyun.smartmistakebook.core.data.settings.DataStoreSleepJournalStore
import com.tingyun.smartmistakebook.core.data.settings.DataStoreSchedulingSettingsStore
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.BackupRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelAgentConsentStore
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTester
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.LibraryCatalogRepository
import com.tingyun.smartmistakebook.core.domain.ReviewReminderRepository
import com.tingyun.smartmistakebook.core.domain.SchedulingSettingsStore
import com.tingyun.smartmistakebook.core.domain.SleepJournalStore
import com.tingyun.smartmistakebook.core.domain.StudyExperienceRepository
import com.tingyun.smartmistakebook.core.domain.SplitImportRepository
import com.tingyun.smartmistakebook.core.data.splitimport.SplitImportRepositoryFactory
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import com.tingyun.smartmistakebook.core.domain.visual.VisualInteractionEventSink
import com.tingyun.smartmistakebook.feature.capture.CaptureCacheMaintenance
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

class SmartMistakeBookApplication : Application() {
    // SupervisorJob stops sibling cancellation but does NOT swallow a child's
    // uncaught exception — without a handler it reaches the process default
    // handler and kills the app. This backstop logs instead of crashing; the
    // full stack stays visible in logcat.
    private val coroutineCrashBackstop = CoroutineExceptionHandler { _, failure ->
        android.util.Log.e("SmartMistakeBook", "Uncaught applicationScope coroutine failure", failure)
    }

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineCrashBackstop)

    val startupState = MutableStateFlow<StartupState>(StartupState.Initializing)

    lateinit var studyRepository: StudyExperienceRepository
    lateinit var schedulingSettingsStore: SchedulingSettingsStore
        private set
    lateinit var sleepJournalStore: SleepJournalStore
        private set

    lateinit var captureRepository: CaptureWorkflowRepository
        private set

    lateinit var batchImportRepository: BatchImportRepository
        private set

    lateinit var backupRepository: BackupRepository
        private set

    val studyDatabase: StudyDatabasePort
        get() = database

    /** Best-effort sink for visual-interaction attempts; null until the database opens. */
    val visualInteractionSink: VisualInteractionEventSink?
        get() = if (::database.isInitialized) visualInteractionSinkLazy else null

    private val visualInteractionSinkLazy: VisualInteractionEventSink by lazy {
        VisualInteractionEventSinkFactory.create(database)
    }

    lateinit var mistakeDetailRepository: MistakeDetailRepository
        private set

    lateinit var mistakeOrganizationRepository: MistakeOrganizationRepository
        private set

    lateinit var modelTaskRepository: ModelTaskRepository
        private set

    lateinit var tutorInteractionRepository: TutorInteractionRepository
        private set

    lateinit var tutorConversationRepository: TutorConversationRepository
        private set

    lateinit var tutorTeachingReferenceRepository: TutorTeachingReferenceRepository
        private set

    lateinit var libraryCatalogRepository: LibraryCatalogRepository
    /** 分页源端口（审计 R-02）：feature 的 `Pager` 要它，而域接口不再提 Paging。 */
    lateinit var libraryPagingSources: LibraryPagingSourceProvider
        private set

    lateinit var reviewReminderRepository: ReviewReminderRepository
        private set

    lateinit var splitImportRepository: SplitImportRepository
        private set

    private lateinit var database: StudyDatabasePort
    private lateinit var reviewReminderCoordinator: ReviewReminderCoordinator

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
        }
        CaptureCacheMaintenance.pruneExpiredFiles(this)
        try {
            // Repair any interrupted restore BEFORE the database is opened so
            // a half-swapped generation can never become visible to Room.
            BackupRestoreStartupRecovery.recoverOnStartup(this)
            database = StudyDatabaseFactory.open(this)
            schedulingSettingsStore = DataStoreSchedulingSettingsStore(this, applicationScope)
            // Settings are read synchronously to mirror the synchronous database
            // open above; a scheduling-flag flip applies on the next launch so a
            // single session's projection model stays stable (spec 2.20).
            val schedulingOptions = runBlocking { schedulingSettingsStore.options.first() }
            val optimizedParameters = runBlocking { schedulingSettingsStore.optimizedParameters.first() }
            studyRepository = StudyExperienceRepositoryFactory.create(
                database = database,
                applicationScope = applicationScope,
                schedulingOptions = schedulingOptions,
                schedulingSettingsStore = schedulingSettingsStore,
                optimizedFsrsParameters = optimizedParameters,
            )
            mistakeDetailRepository = MistakeDetailRepositoryFactory.create(this, database)
            mistakeOrganizationRepository = MistakeOrganizationRepositoryFactory.create(database)
            tutorInteractionRepository = TutorInteractionRepositoryFactory.create(database)
            tutorConversationRepository = TutorConversationRepositoryFactory.create(database)
            tutorTeachingReferenceRepository =
                TutorTeachingReferenceRepositoryFactory.create(database)
            LibraryCatalogRepositoryFactory.create(database).also { libraryCatalog ->
                libraryCatalogRepository = libraryCatalog
                libraryPagingSources = libraryCatalog
            }
            splitImportRepository = SplitImportRepositoryFactory.create(database)
            reviewReminderRepository = DataStoreReviewReminderRepository(this, applicationScope)
            sleepJournalStore = DataStoreSleepJournalStore(this, applicationScope)
            registerActivityLifecycleCallbacks(
                object : ActivityLifecycleCallbacks {
                    // Silent sleep-window collection (spec 2.14): every time
                    // the app becomes visible one activity stamp is journaled;
                    // the gaps between stamps become the inferred nights.
                    override fun onActivityStarted(activity: Activity) {
                        applicationScope.launch {
                            sleepJournalStore.recordActivity(System.currentTimeMillis())
                        }
                    }

                    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit

                    override fun onActivityResumed(activity: Activity) = Unit

                    override fun onActivityPaused(activity: Activity) = Unit

                    override fun onActivityStopped(activity: Activity) = Unit

                    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

                    override fun onActivityDestroyed(activity: Activity) = Unit
                },
            )
            reviewReminderCoordinator = ReviewReminderCoordinator(
                repository = reviewReminderRepository,
                platform = ReviewReminderPlatform(this),
                pendingReviewCount = {
                    studyRepository.refresh()
                    val review = studyRepository.snapshot.value.review
                    if (review.completedToday) {
                        0
                    } else {
                        (review.scheduledCount - review.currentOrdinal).coerceAtLeast(0)
                    }
                },
                scope = applicationScope,
            ).also(ReviewReminderCoordinator::start)
            val gateway = modelConfigurationStore?.let { configurationStore ->
                ConfiguredModelGatewayFactory.create(
                    configurationStore = configurationStore,
                    assetSource = RestrictedModelAssetSourceFactory.create(this, database),
                )
            } ?: UnavailableModelGateway()
            modelTaskRepository = ModelTaskRepositoryFactory.create(
                database = database,
                gateway = gateway,
            )
            captureRepository = CaptureWorkflowRepositoryFactory.create(
                context = this,
                database = database,
                // Clean redraw engine: reused by the save decision round once the
                // model classifies the committed photo as figure-bearing.
                // Consent = the user's global "model agent" toggle, the same switch
                // the capture path reads — NOT the build-flavor capability bit
                // (audit S-2: the flavor bit let a revoked consent still upload the
                // original photo bytes to the provider).
                cleanRedraw = modelConfigurationStore?.let { store ->
                    ConfiguredCleanImageGeneratorFactory.create(
                        configurationStore = store,
                        modelAgentConsentStore = modelAgentConsentStore,
                    )
                },
                cleanRedrawScope = applicationScope,
                // Save path runs a model classify round to decide redraw. Consent =
                // the global "model agent" toggle AND a configured model; no consent or
                // no model → no round, original photo kept.
                modelTasks = modelTaskRepository,
                captureConsentGranted = {
                    modelConfigurationStore != null &&
                        runBlocking { modelAgentConsentStore?.current() ?: false }
                },
            )
            batchImportRepository = BatchImportRepositoryFactory.create(
                context = this,
                database = database,
                capture = captureRepository,
                processingScope = applicationScope,
                modelTasks = modelTaskRepository,
                splitImports = SplitImportRepositoryFactory.createConcrete(database),
                // Batch page organization egresses page images; it runs only under the global
                // "model agent" consent AND a configured model, mirroring the capture save path.
                consentEnabled = {
                    modelConfigurationStore != null &&
                        runBlocking { modelAgentConsentStore?.current() ?: false }
                },
            )
            backupRepository = BackupRepositoryFactory.create(this, database)
            OrphanAssetGc.enqueue(this)
            registerDebugHarness()
            startupState.value = StartupState.Ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            startupState.value = StartupState.FatalFailure(
                title = "应用数据无法打开",
                message = "数据库初始化失败，暂时不能安全读写学习记录。",
                diagnosticId = "startup:database:${System.currentTimeMillis().hashCode().toUInt()}",
                errorCategory = StartupErrorCategory.DATABASE,
            )
            return
        }
        applicationScope.launch {
            // 知识包安装与投影初始化**分开归类**：合用一个 catch 会让任何投影/账本失败
            // 都被说成「本地知识包尚未准备好」，并附上一句"可以继续使用"（审计 N-02）。
            // 归属本身住在 `runStartupInitialization`，因此它有断言；文案与编号前缀见
            // `StartupFailureMessages.kt`。
            val failure = runStartupInitialization(
                installKnowledgeBase = { BundledKnowledgeBaseInstaller.install(database) },
                initializeProjection = { studyRepository.initialize() },
                logFailure = { description, error ->
                    android.util.Log.e("SmartMistakeBook", description, error)
                },
            )
            if (failure != null) {
                startupState.value = failure
                return@launch
            }
            // 全部成功时不写状态：此时启动态已是 Ready，重写一次是空操作，
            // 而投影成功也绝不能抹掉先前那条知识包失败。
            // Silent FSRS parameter refit (spec §2.11): self-gated by the
            // fsrs-rs data thresholds (>=64 samples for a full fit) and
            // failure-proof; fitted parameters apply on the next launch.
            applicationScope.launch {
                runCatching { studyRepository.optimizeSchedulingParameters() }
            }
        }
    }

    fun handleReviewReminderBroadcast(action: String?, onFinished: () -> Unit) {
        applicationScope.launch {
            try {
                reviewReminderCoordinator.handleBroadcast(action)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A reminder must never crash app startup; the next preference emission repairs it.
            } finally {
                onFinished()
            }
        }
    }

    fun refreshReviewReminderSchedule() {
        applicationScope.launch {
            reviewReminderCoordinator.refresh()
        }
    }

    fun refreshStudyExperience() {
        applicationScope.launch {
            try {
                studyRepository.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The repository publishes the recoverable state consumed by the root UI.
            }
        }
    }

    /**
     * 启动横幅上「重试」按下的那条路（审计 N-21）：重跑**与启动同一条**初始化，
     * 并把这一次的结论写回 [startupState]——成功就收回横幅，失败就说这一次的失败。
     *
     * 与 [refreshStudyExperience] 分开，是因为两者是两件事：后者只是让仓储重发一次快照
     * （`MainActivity` 的 `onStart` 用它），它既不重装知识包，也不该动启动态。
     */
    fun retryStartupInitialization() {
        applicationScope.launch {
            runStartupRetry(
                installKnowledgeBase = { BundledKnowledgeBaseInstaller.install(database) },
                initializeProjection = { studyRepository.initialize() },
                publish = { state -> startupState.value = state },
                logFailure = { description, error ->
                    android.util.Log.e("SmartMistakeBook", description, error)
                },
            )
        }
    }

    val capabilities by lazy {
        FlavorCapabilityFactory.create()
    }

    val modelConfigurationStore: ModelConfigurationStore? by lazy {
        if (capabilities.networkRequestsAllowed) {
            DataStoreModelConfigurationStore(this, applicationScope)
        } else {
            null
        }
    }

    /** Debug-only model-config seed harness (never present in release). */
    private val testSeedReceiver = TestSeedModelConfigReceiver()
    val modelAgentConsentStore: ModelAgentConsentStore? by lazy {
        if (capabilities.networkRequestsAllowed) {
            DataStoreModelAgentConsentStore(this, applicationScope)
        } else {
            null
        }
    }

    val modelCapabilityTester: ModelCapabilityTester? by lazy {
        modelConfigurationStore?.let(ConfiguredModelCapabilityTesterFactory::create)
    }

    override fun onTerminate() {
        studyRepository.close()
        database.close()
        applicationScope.cancel()
        super.onTerminate()
    }

    /** Registers the debug-only seed receiver so `adb am broadcast` can configure the model. */
    private fun registerDebugHarness() {
        if (!BuildConfig.DEBUG) return
        // API 33+ requires an explicit export flag for dynamically registered
        // receivers; exported only because QA drives it via adb from outside the
        // app. ContextCompat maps the flag to the right platform overload on every
        // API level (masked to 0 before 33, two-argument before 26), so minSdk 23
        // stays safe without a lint suppression.
        ContextCompat.registerReceiver(
            this,
            testSeedReceiver,
            android.content.IntentFilter(TestSeedModelConfigReceiver.ACTION_TEST_SEED_MODEL_CONFIG),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
}
