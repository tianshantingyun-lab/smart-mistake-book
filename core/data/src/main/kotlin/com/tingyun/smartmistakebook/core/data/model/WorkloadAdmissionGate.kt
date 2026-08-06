package com.tingyun.smartmistakebook.core.data.model

import kotlinx.coroutines.sync.Semaphore
import com.tingyun.smartmistakebook.core.model.ProjectionWorkloadGate

internal enum class WorkloadCategory {
    MODEL,
    BATCH_IMPORT,
    PDF,
    PROJECTION,
}

/**
 * Shared two-level admission for provider-bound and host-bound work.
 *
 * A caller needs one category permit and one total permit. This keeps a single heavy category from
 * starving unrelated background work while still bounding overall device load.
 */
internal class WorkloadAdmissionGate(
    categoryPermits: Map<WorkloadCategory, Int>,
    totalPermits: Int = DEFAULT_TOTAL_PERMITS,
) {
    init {
        require(totalPermits > 0) { "Total workload permits must be positive" }
        categoryPermits.forEach { (category, permits) ->
            require(permits > 0) { "$category permits must be positive" }
            require(permits <= totalPermits) {
                "$category permits cannot exceed the total workload budget"
            }
        }
    }

    private val totalSemaphore = Semaphore(totalPermits)
    private val categorySemaphores =
        categoryPermits.mapValues { (_, permits) -> Semaphore(permits) }

    suspend fun <T> withPermit(
        category: WorkloadCategory,
        block: suspend () -> T,
    ): T {
        val categorySemaphore =
            categorySemaphores[category]
                ?: error("Unregistered workload category: $category")
        categorySemaphore.acquire()
        totalSemaphore.acquire()
        try {
            return block()
        } finally {
            totalSemaphore.release()
            categorySemaphore.release()
        }
    }

    companion object {
        const val DEFAULT_TOTAL_PERMITS = 8
    }
}

internal object WorkloadAdmissionGateFactory {
    fun createDefault(): WorkloadAdmissionGate =
        WorkloadAdmissionGate(
            categoryPermits =
                mapOf(
                    WorkloadCategory.MODEL to 4,
                    WorkloadCategory.BATCH_IMPORT to 2,
                    WorkloadCategory.PDF to 1,
                    WorkloadCategory.PROJECTION to 1,
                ),
            totalPermits = WorkloadAdmissionGate.DEFAULT_TOTAL_PERMITS,
        )
}

/**
 * One process-wide admission gate shared by model, batch import, PDF and projection owners.
 *
 * Keep the instance lazy so early unit construction never starts a hidden workload.
 */
internal object SharedWorkloadAdmissionGate {
    val gate: WorkloadAdmissionGate by lazy { WorkloadAdmissionGateFactory.createDefault() }
}

internal object SharedProjectionWorkloadGate : ProjectionWorkloadGate {
    override suspend fun <T> withPermit(block: suspend () -> T): T =
        SharedWorkloadAdmissionGate.gate.withPermit(WorkloadCategory.PROJECTION, block)
}
