package com.tingyun.smartmistakebook.core.data.model

/**
 * Bounded host-wide model execution admission. This is the first shared admission point for
 * provider-bound work; batch import, PDF conversion and projection rebuilds will join the same
 * policy in later B07 work packages.
 */
internal class ModelExecutionAdmissionGate(
    permits: Int = DEFAULT_MODEL_EXECUTION_PERMITS,
    delegate: WorkloadAdmissionGate? = null,
) {
    init {
        require(permits > 0) { "Model execution permits must be positive" }
    }

    private val delegate =
        delegate
            ?: WorkloadAdmissionGate(
                categoryPermits = mapOf(WorkloadCategory.MODEL to permits),
                totalPermits = permits,
            )

    suspend fun <T> withPermit(block: suspend () -> T): T =
        delegate.withPermit(WorkloadCategory.MODEL, block)

    companion object {
        const val DEFAULT_MODEL_EXECUTION_PERMITS = 4
    }
}
