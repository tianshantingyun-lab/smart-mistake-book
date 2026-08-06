package com.tingyun.smartmistakebook.core.model

/**
 * Low-level projection rebuild gate.
 *
 * The database module owns the rebuild loop but cannot depend on core:data. Production wiring
 * supplies a shared workload gate through the owner bridge; tests and non-production runtimes keep
 * the no-op default.
 */
interface ProjectionWorkloadGate {
    suspend fun <T> withPermit(block: suspend () -> T): T
}

object NoopProjectionWorkloadGate : ProjectionWorkloadGate {
    override suspend fun <T> withPermit(block: suspend () -> T): T = block()
}
