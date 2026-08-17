package com.tingyun.smartmistakebook

import android.app.Application
import android.os.StrictMode
import com.tingyun.smartmistakebook.core.data.capture.CaptureWorkflowRepositoryFactory
import com.tingyun.smartmistakebook.core.data.capture.BatchImportRepositoryFactory
import com.tingyun.smartmistakebook.core.data.backup.BackupRepositoryFactory
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
import com.tingyun.smartmistakebook.core.data.tutor.TutorInteractionRepositoryFactory
import com.tingyun.smartmistakebook.core.data.tutor.TutorConversationRepositoryFactory
import com.tingyun.smartmistakebook.core.data.settings.DataStoreModelConfigurationStore
import com.tingyun.smartmistakebook.core.data.settings.DataStoreReviewReminderRepository
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
import com.tingyun.smartmistakebook.core.domain.StudyExperienceRepository
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import com.tingyun.smartmistakebook.feature.capture.CaptureCacheMaintenance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

class SmartMistakeBookApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val startupState = MutableStateFlow<StartupState>(StartupState.Initializing)

    lateinit var studyRepository: StudyExperienceRepository
        private set

    lateinit var captureRepository: CaptureWorkflowRepository
        private set

    lateinit var batchImportRepository: BatchImportRepository
        private set

    lateinit var backupRepository: BackupRepository
        private set

    val studyDatabase: StudyDatabasePort
        get() = database

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
            database = StudyDatabaseFactory.open(this)
            studyRepository = StudyExperienceRepositoryFactory.create(
                database = database,
                applicationScope = applicationScope,
            )
            captureRepository = CaptureWorkflowRepositoryFactory.create(this, database)
            mistakeDetailRepository = MistakeDetailRepositoryFactory.create(this, database)
            mistakeOrganizationRepository = MistakeOrganizationRepositoryFactory.create(database)
            tutorInteractionRepository = TutorInteractionRepositoryFactory.create(database)
            tutorConversationRepository = TutorConversationRepositoryFactory.create(database)
            tutorTeachingReferenceRepository =
                TutorTeachingReferenceRepositoryFactory.create(database)
            libraryCatalogRepository = LibraryCatalogRepositoryFactory.create(database)
            reviewReminderRepository = DataStoreReviewReminderRepository(this, applicationScope)
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
            batchImportRepository = BatchImportRepositoryFactory.create(
                context = this,
                database = database,
                capture = captureRepository,
                processingScope = applicationScope,
                modelTasks = modelTaskRepository,
            )
            backupRepository = BackupRepositoryFactory.create(this, database)
            OrphanAssetGc.enqueue(this)
            startupState.value = StartupState.Ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            startupState.value = StartupState.FatalFailure(
                title = "应用数据无法打开",
                message = "数据库初始化失败，暂时不能安全读写学习记录。",
                diagnosticId = "startup:database:${System.currentTimeMillis().hashCode().toUInt()}",
            )
            throw failure
        }
        applicationScope.launch {
            try {
                BundledKnowledgeBaseInstaller.install(database)
                studyRepository.initialize()
                if (startupState.value is StartupState.Ready) {
                    startupState.value = StartupState.Ready
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                startupState.value = StartupState.RecoverableFailure(
                    title = "本地知识包尚未准备好",
                    message = "错题和复习可以继续使用，自动分类会暂缓。",
                    diagnosticId = "startup:knowledge:${failure.hashCode().toUInt()}",
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

    val modelCapabilityTester: ModelCapabilityTester? by lazy {
        modelConfigurationStore?.let(ConfiguredModelCapabilityTesterFactory::create)
    }

    override fun onTerminate() {
        studyRepository.close()
        database.close()
        applicationScope.cancel()
        super.onTerminate()
    }
}
