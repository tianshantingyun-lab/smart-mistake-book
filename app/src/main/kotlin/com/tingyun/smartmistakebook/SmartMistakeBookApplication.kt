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
import com.tingyun.smartmistakebook.core.data.backup.RestoreRecoveryAttention
import com.tingyun.smartmistakebook.core.data.backup.RestoreStartupOutcome
import com.tingyun.smartmistakebook.core.data.backup.attentionRequired
import com.tingyun.smartmistakebook.core.data.knowledge.BundledKnowledgeBaseInstaller
import com.tingyun.smartmistakebook.core.data.knowledge.TutorTeachingReferenceRepositoryFactory
import com.tingyun.smartmistakebook.core.data.library.LibraryCatalogRepositoryFactory
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
            // a half-swapped generation can never become visible to Room. The
            // outcome is kept: a rolled-back or quarantined restore means the
            // book is not what the student left, and staying silent about that
            // is indistinguishable from lying about their data.
            val restoreRecovery = BackupRestoreStartupRecovery.recoverOnStartup(this)
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
            libraryCatalogRepository = LibraryCatalogRepositoryFactory.create(database)
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
                cleanRedraw = modelConfigurationStore?.let { store ->
                    ConfiguredCleanImageGeneratorFactory.create(
                        configurationStore = store,
                        networkRequestsAllowed = capabilities.networkRequestsAllowed,
                    )
                },
                cleanRedrawScope = applicationScope,
                // Save path runs a model classify round to decide redraw. Egress needs only a
                // configured model: the global "model agent" consent toggle was removed
                // (2026-09-13), so a configured provider is the single condition. No model →
                // no round, original photo kept.
                modelTasks = modelTaskRepository,
                captureEgressAllowed = { modelConfigurationStore != null },
            )
            batchImportRepository = BatchImportRepositoryFactory.create(
                context = this,
                database = database,
                capture = captureRepository,
                processingScope = applicationScope,
                modelTasks = modelTaskRepository,
                splitImports = SplitImportRepositoryFactory.createConcrete(database),
                // Batch page organization egresses page images; same single condition as the
                // capture save path above (configured model).
                modelEgressAllowed = { modelConfigurationStore != null },
            )
            backupRepository = BackupRepositoryFactory.create(this, database)
            OrphanAssetGc.enqueue(this)
            registerDebugHarness()
            startupState.value = StartupState.Ready
            // Applied after Ready so a successful open is still reported as
            // usable: the book works, the student just has to know that a
            // restore did not land.
            restoreRecoveryFailure(restoreRecovery)?.let { failure ->
                startupState.value = failure
            }
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
            try {
                BundledKnowledgeBaseInstaller.install(database)
                studyRepository.initialize()
                if (startupState.value is StartupState.Ready) {
                    startupState.value = StartupState.Ready
                }
                // Silent FSRS parameter refit (spec §2.11): self-gated by the
                // fsrs-rs data thresholds (>=64 samples for a full fit) and
                // failure-proof; fitted parameters apply on the next launch.
                applicationScope.launch {
                    runCatching { studyRepository.optimizeSchedulingParameters() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                android.util.Log.e(
                    "SmartMistakeBook",
                    "Bundled knowledge install failed",
                    failure,
                )
                startupState.value = StartupState.RecoverableFailure(
                    title = "本地知识包尚未准备好",
                    message = "错题和复习可以继续使用，自动分类会暂缓。",
                    diagnosticId = "startup:knowledge:${failure.hashCode().toUInt()}",
                    errorCategory = StartupErrorCategory.KNOWLEDGE_BASE,
                )
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

/**
 * The startup state a recovery outcome warrants, or null when the student does
 * not need to hear about it.
 *
 * The book is usable in both reported cases — the record just is not what the
 * student last saw, so each message leads with what happened to their data and
 * avoids implying loss that did not occur. A clean start and a pre-swap cleanup
 * leave the live generation untouched and stay silent.
 */
private fun restoreRecoveryFailure(
    outcome: RestoreStartupOutcome,
): StartupState.RecoverableFailure? = when (outcome.attentionRequired()) {
    RestoreRecoveryAttention.NONE -> null

    RestoreRecoveryAttention.RESTORE_REVERTED -> StartupState.RecoverableFailure(
        title = "备份恢复没有完成",
        message = "已回到恢复之前的数据，错题记录仍然完整。需要的话可以重新恢复一次。",
        diagnosticId = "startup:restore-reverted:${outcome.diagnosticTag()}",
        errorCategory = StartupErrorCategory.DATABASE,
    )

    RestoreRecoveryAttention.DATA_QUARANTINED -> StartupState.RecoverableFailure(
        title = "恢复未完成，部分数据已隔离",
        message = "为避免读到损坏的数据，相关文件已单独隔离，当前记录可能不完整。",
        diagnosticId = "startup:restore-quarantined:${outcome.diagnosticTag()}",
        errorCategory = StartupErrorCategory.DATABASE,
    )
}

/** Stable per-incident identifier for support, never student-visible text. */
private fun RestoreStartupOutcome.diagnosticTag(): String = when (this) {
    RestoreStartupOutcome.NothingToRecover -> "none"
    is RestoreStartupOutcome.Cleaned -> restoreId
    is RestoreStartupOutcome.RolledBack -> restoreId
    is RestoreStartupOutcome.Quarantined -> restoreId
    is RestoreStartupOutcome.Unreadable -> reason.hashCode().toUInt().toString()
}
