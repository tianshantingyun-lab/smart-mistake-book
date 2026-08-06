package com.tingyun.smartmistakebook.core.data.production

import com.tingyun.smartmistakebook.core.data.mistake.StudentMistakeLibraryCatalogRepository
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAssetSource
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.domain.TutorConversationLobbyPort
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import java.util.concurrent.atomic.AtomicBoolean

internal data class IssuedProductionCapabilityLeases(
    val captureWorkflow: SharedProductionCapabilityLease<CaptureWorkflowRepository>,
    val batchImport: SharedProductionCapabilityLease<BatchImportRepository>,
    val mistakeDetail: SharedProductionCapabilityLease<MistakeDetailRepository>,
    val mistakeOrganization: SharedProductionCapabilityLease<MistakeOrganizationRepository>,
    val studentMistakeCatalog:
        SharedProductionCapabilityLease<StudentMistakeLibraryCatalogRepository>,
    val tutorSession: SharedProductionCapabilityLease<TutorSessionProductionCapability>,
    val tutorConversationLobby: SharedProductionCapabilityLease<TutorConversationLobbyPort>,
    val tutorMasteryAndProfile:
        SharedProductionCapabilityLease<TutorMasteryAndProfileProductionCapability>,
    val tutorTeachingReference:
        SharedProductionCapabilityLease<TutorTeachingReferenceRepository>,
    val modelTaskQueue: SharedProductionCapabilityLease<ModelTaskRepository>,
    val modelAssetDocuments: SharedProductionCapabilityLease<RestrictedModelAssetSource>,
    val reviewPlanning: SharedProductionCapabilityLease<ReviewPlanningProductionCapability>,
    val workManagerCoordination:
        SharedProductionCapabilityLease<ProductionWorkManagerCoordination>,
    val terminalCutoverGate:
        SharedProductionCapabilityLease<ProductionTerminalCutoverCapability>,
) {
    fun closeReverse() {
        var firstFailure: Throwable? = null
        ordered()
            .asReversed()
            .forEach { lease ->
                try {
                    lease.close()
                } catch (failure: Throwable) {
                    val first = firstFailure
                    if (first == null) {
                        firstFailure = failure
                    } else {
                        first.addSuppressed(failure)
                    }
                }
            }
        firstFailure?.let { throw it }
    }

    private fun ordered(): List<SharedProductionCapabilityLease<*>> =
        listOf(
            captureWorkflow,
            batchImport,
            mistakeDetail,
            mistakeOrganization,
            studentMistakeCatalog,
            tutorSession,
            tutorConversationLobby,
            tutorMasteryAndProfile,
            tutorTeachingReference,
            modelTaskQueue,
            modelAssetDocuments,
            reviewPlanning,
            workManagerCoordination,
            terminalCutoverGate,
        )
}

internal class SharedProductionCapabilityLease<out Capability : Any>(
    val capability: Capability,
    private val owner: SharedProductionCapabilityOwner,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            owner.releaseLease()
        }
    }
}

internal class SharedProductionCapabilityOwner(
    leaseCount: Int,
    release: () -> Unit,
) {
    private val monitor = Any()
    private var remainingLeases = leaseCount
    private var released = false
    private val release = release

    init {
        require(leaseCount == PRODUCTION_CAPABILITY_SLOT_COUNT) {
            "Production publication must own every capability inventory slot"
        }
    }

    fun <Capability : Any> lease(
        capability: Capability,
    ): SharedProductionCapabilityLease<Capability> =
        SharedProductionCapabilityLease(capability, this)

    fun releaseLease() {
        val releaseNow =
            synchronized(monitor) {
                check(remainingLeases > 0) {
                    "Production capability lease was released more than once"
                }
                remainingLeases -= 1
                if (remainingLeases == 0) {
                    check(!released)
                    released = true
                    true
                } else {
                    false
                }
            }
        if (releaseNow) release()
    }

    fun closeUnissuedAfterFailure(ownerFailure: Throwable) {
        val releaseNow =
            synchronized(monitor) {
                if (released) {
                    false
                } else {
                    released = true
                    remainingLeases = 0
                    true
                }
            }
        if (!releaseNow) return
        try {
            release()
        } catch (closeFailure: Throwable) {
            ownerFailure.addSuppressed(closeFailure)
        }
    }
}

internal val PRODUCTION_CAPABILITY_SLOT_COUNT: Int
    get() = ProductionAdapter.entries.size

internal fun productionWorkManagerCoordination(
    executionResolverProvider: () -> ProductionProblemOrganizationExecutionResolver,
    schedulingProvider: () -> ProductionProblemOrganizationScheduling,
): ProductionWorkManagerCoordination =
    ProductionWorkManagerCoordination.issue(executionResolverProvider, schedulingProvider)
