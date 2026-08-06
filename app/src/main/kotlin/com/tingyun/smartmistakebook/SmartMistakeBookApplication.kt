package com.tingyun.smartmistakebook

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.tingyun.smartmistakebook.core.data.authority.ProductionAuthorityBootstrap
import com.tingyun.smartmistakebook.core.data.authority.ProductionAuthorityBootstrapFactory
import com.tingyun.smartmistakebook.core.data.authority.ProductionAuthorityStartupState
import com.tingyun.smartmistakebook.core.data.mistake.StudentMistakeLibraryCatalogRepository
import com.tingyun.smartmistakebook.core.data.production.ProductionCapabilitySnapshot
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionResolver
import com.tingyun.smartmistakebook.core.data.production.AuditedProductionAdapterAvailability
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeRequest
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeState
import com.tingyun.smartmistakebook.core.data.settings.DataStoreModelConfigurationStore
import com.tingyun.smartmistakebook.core.data.settings.DataStoreReviewReminderRepository
import com.tingyun.smartmistakebook.core.data.settings.DataStoreTutorSettingsRepository
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTester
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ReviewReminderRepository
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.TutorSettingsRepository
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import com.tingyun.smartmistakebook.core.model.provider.ConfiguredModelCapabilityTesterFactory
import com.tingyun.smartmistakebook.core.model.provider.ConfiguredModelExecutionLeaseFactory
import com.tingyun.smartmistakebook.feature.capture.CaptureCacheMaintenance
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SmartMistakeBookApplication : Application(), Configuration.Provider {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /*
     * Every getter resolves one field from the same atomic snapshot. There are no independently
     * initialized repositories for Root or WorkManager to observe during publication.
     */
    val captureRepository: CaptureWorkflowRepository
        get() = requirePublishedCapabilities().captureWorkflow

    val batchImportRepository: BatchImportRepository
        get() = requirePublishedCapabilities().batchImport

    val mistakeDetailRepository: MistakeDetailRepository
        get() = requirePublishedCapabilities().mistakeDetail

    val studentMistakeLibraryCatalogRepository: StudentMistakeLibraryCatalogRepository
        get() = requirePublishedCapabilities().studentMistakeCatalog

    val mistakeOrganizationRepository: MistakeOrganizationRepository
        get() = requirePublishedCapabilities().mistakeOrganization

    val tutorMasteryContextRepository: TutorMasteryContextRepository
        get() = requirePublishedCapabilities()
            .tutorMasteryAndProfile
            .tutorMasteryContext

    val learningMasteryDisplayRepository: LearningMasteryDisplayRepository
        get() = requirePublishedCapabilities()
            .tutorMasteryAndProfile
            .learningMasteryDisplay

    val tutorTeachingReferenceRepository: TutorTeachingReferenceRepository
        get() = requirePublishedCapabilities().tutorTeachingReference

    lateinit var reviewReminderRepository: ReviewReminderRepository
        private set

    lateinit var tutorSettingsRepository: TutorSettingsRepository
        private set

    private lateinit var authorityBootstrap: ProductionAuthorityBootstrap
    private lateinit var reviewReminderCoordinator: ReviewReminderCoordinator
    private lateinit var reviewReminderBroadcastHandoff: ReviewReminderBroadcastHandoff
    private lateinit var problemOrganizationWorkSchedulingCoordinator:
        ProblemOrganizationWorkSchedulingCoordinator
    private lateinit var longTaskRecoveryScheduler: LongTaskRecoveryScheduler
    private val productionCapabilityPublicationGraph =
        ProductionCapabilityPublicationGraph()
    private val problemOrganizationExecutionResolver =
        AtomicReference<ProductionProblemOrganizationExecutionResolver?>(null)

    val productionAuthorityStartupState: StateFlow<ProductionAuthorityStartupState>
        get() = authorityBootstrap.state

    internal val publishedProductionCapabilities: ProductionCapabilitySnapshot?
        get() =
            if (
                ::authorityBootstrap.isInitialized &&
                authorityBootstrap.state.value == ProductionAuthorityStartupState.Ready
            ) {
                productionCapabilityPublicationGraph.publishedCapabilities
            } else {
                null
            }

    internal val productionAdapterAvailability
        get() = AuditedProductionAdapterAvailability

    private val problemOrganizationWorkerFactory =
        ProblemOrganizationWorkerFactory {
            problemOrganizationExecutionResolver.get()
        }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setWorkerFactory(problemOrganizationWorkerFactory)
                .build()

    override fun onCreate() {
        super.onCreate()
        CaptureCacheMaintenance.pruneExpiredFiles(this)

        // Preference stores are application settings, not any of the three business authorities.
        reviewReminderRepository = DataStoreReviewReminderRepository(this, applicationScope)
        tutorSettingsRepository = DataStoreTutorSettingsRepository(this, applicationScope)
        reviewReminderBroadcastHandoff =
            ReviewReminderBroadcastHandoff(
                store = SharedPreferencesReviewReminderPendingBroadcastStore(this),
                scope = applicationScope,
                handlerProvider = {
                    if (::reviewReminderCoordinator.isInitialized) {
                        reviewReminderCoordinator
                    } else {
                        null
                    }
                },
            )
        longTaskRecoveryScheduler = LongTaskRecoveryScheduler(applicationScope)

        authorityBootstrap =
            ProductionAuthorityBootstrapFactory.createProduction(
                context = this,
                adapterAvailability = AuditedProductionAdapterAvailability,
                modelExecutionLeaseProvider = {
                    ConfiguredModelExecutionLeaseFactory.create(
                        checkNotNull(modelConfigurationStore) {
                            "Production model configuration is unavailable"
                        },
                    )
                },
                publishCapabilities = { assembly ->
                    val publication = productionCapabilityPublicationGraph.publish(assembly)
                    check(publication is ProductionCapabilityPublicationState.Published) {
                        "Production capability publication did not complete atomically"
                    }
                    val capabilities =
                        checkNotNull(productionCapabilityPublicationGraph.publishedCapabilities)
                    longTaskRecoveryScheduler.register(
                        "problem-organization",
                    ) {
                        startProblemOrganizationWorkScheduling(capabilities)
                    }
                    longTaskRecoveryScheduler.register(
                        "batch-import",
                    ) {
                        capabilities.batchImport.recoverInterruptedBatchImportWork()
                    }
                    longTaskRecoveryScheduler.register(
                        "review-reminder",
                    ) {
                        startReviewReminderCoordinator(capabilities)
                    }
                    longTaskRecoveryScheduler.start()
                },
            )
        applicationScope.launch {
            authorityBootstrap.start()
        }
    }

    fun handleReviewReminderBroadcast(action: String?, onFinished: () -> Unit) {
        if (!ReviewReminderContract.acceptsBroadcastAction(action)) {
            onFinished()
            return
        }
        reviewReminderBroadcastHandoff.accept(checkNotNull(action), onFinished)
    }

    fun refreshReviewReminderSchedule() {
        if (!::reviewReminderCoordinator.isInitialized) return
        applicationScope.launch {
            reviewReminderCoordinator.refresh()
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
        if (::problemOrganizationWorkSchedulingCoordinator.isInitialized) {
            problemOrganizationWorkSchedulingCoordinator.close()
        }
        problemOrganizationExecutionResolver.set(null)
        applicationScope.cancel()
        runCatching(productionCapabilityPublicationGraph::close)
        super.onTerminate()
    }

    private fun requirePublishedCapabilities(): ProductionCapabilitySnapshot =
        checkNotNull(publishedProductionCapabilities) {
            "Production capabilities are not published"
        }

    private fun startProblemOrganizationWorkScheduling(
        capabilities: ProductionCapabilitySnapshot,
    ) {
        check(!::problemOrganizationWorkSchedulingCoordinator.isInitialized) {
            "Problem organization scheduling coordinator was already initialized"
        }
        val scheduling =
            capabilities.workManagerCoordination.currentProblemOrganizationScheduling()
        val resolver =
            capabilities.workManagerCoordination.currentProblemOrganizationExecutionResolver()
        check(problemOrganizationExecutionResolver.compareAndSet(null, resolver)) {
            "Problem organization execution resolver was already published"
        }
        val scheduler = ProblemOrganizationWorkScheduler(WorkManager.getInstance(this))
        problemOrganizationWorkSchedulingCoordinator =
            ProblemOrganizationWorkSchedulingCoordinator(
                parentScope = applicationScope,
                observeSchedulable = scheduling::observeSchedulable,
                readRunningRecoveryPage = scheduling::readRunningRecoveryPage,
                enqueueSchedulable = { schedule -> scheduler.enqueue(schedule) },
                enqueueRunningRecovery = { schedule ->
                    scheduler.enqueueRunningRecovery(schedule)
                },
            )
        problemOrganizationWorkSchedulingCoordinator.start()
    }

    private fun startReviewReminderCoordinator(capabilities: ProductionCapabilitySnapshot) {
        check(!::reviewReminderCoordinator.isInitialized) {
            "Review reminder coordinator was already initialized"
        }
        val reviewRepository = capabilities.reviewPlanning.planning.repository
        reviewReminderCoordinator =
            ReviewReminderCoordinator(
                repository = reviewReminderRepository,
                platform = ReviewReminderPlatform(this),
                pendingReviewCount = {
                    val zone = ZoneId.systemDefault()
                    when (
                        val home =
                            reviewRepository.readHome(
                                ReviewHomeRequest(
                                    localDayEpochDay = LocalDate.now(zone).toEpochDay(),
                                    timeZoneId = zone.id,
                                    requestedAtEpochMillis = System.currentTimeMillis(),
                                ),
                            )
                    ) {
                        is ReviewHomeState.Ready -> home.plan.remainingItemCount
                        is ReviewHomeState.Unavailable -> 0
                    }
                },
                scope = applicationScope,
            )
        reviewReminderCoordinator.start()
        reviewReminderBroadcastHandoff.onHandlerReady()
    }
}

/**
 * Capability availability is derived from the audited owner-source inventory. A complete
 * inventory still cannot create Ready without the owner-issued current-generation assembly.
 */
