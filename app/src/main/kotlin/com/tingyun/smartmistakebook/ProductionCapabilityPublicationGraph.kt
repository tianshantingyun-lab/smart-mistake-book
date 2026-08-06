package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.data.production.CompleteProductionCapabilityAssembly
import com.tingyun.smartmistakebook.core.data.production.ProductionCapabilitySnapshot

/**
 * Process-local publication state.
 *
 * The app can hold or close an owner-issued snapshot, but it cannot construct a lease, replace a
 * slot, or publish incrementally.
 */
internal sealed interface ProductionCapabilityPublicationState {
    data object Unpublished : ProductionCapabilityPublicationState

    data object Publishing : ProductionCapabilityPublicationState

    data class Published(
        val capabilities: ProductionCapabilitySnapshot,
    ) : ProductionCapabilityPublicationState

    data class Failed(
        val failure: ProductionCapabilityPublicationFailure,
    ) : ProductionCapabilityPublicationState

    data object Closed : ProductionCapabilityPublicationState
}

internal enum class ProductionCapabilityPublicationFailureCode {
    ASSEMBLY_CLAIM_FAILED,
}

internal data class ProductionCapabilityPublicationFailure(
    val code: ProductionCapabilityPublicationFailureCode,
)

/**
 * Atomically publishes one complete core:data assembly.
 *
 * A failed or duplicate publication closes the rejected assembly, whose owner releases every
 * inventoried lease in reverse order. Consumers observe one complete [Published] snapshot or none.
 */
internal class ProductionCapabilityPublicationGraph : AutoCloseable {
    private val monitor = Any()

    @Volatile
    private var currentState: ProductionCapabilityPublicationState =
        ProductionCapabilityPublicationState.Unpublished

    val state: ProductionCapabilityPublicationState
        get() = currentState

    val publishedCapabilities: ProductionCapabilitySnapshot?
        get() =
            (currentState as? ProductionCapabilityPublicationState.Published)
                ?.capabilities

    fun publish(
        assembly: CompleteProductionCapabilityAssembly,
    ): ProductionCapabilityPublicationState =
        synchronized(monitor) {
            if (currentState != ProductionCapabilityPublicationState.Unpublished) {
                assembly.closeQuietly()
                return@synchronized currentState
            }

            currentState = ProductionCapabilityPublicationState.Publishing
            val snapshot =
                try {
                    assembly.claimForPublication()
                } catch (failure: Throwable) {
                    assembly.closeAfterClaimFailure(failure)
                    if (failure !is Exception) throw failure
                    return@synchronized fail()
                }

            val published =
                ProductionCapabilityPublicationState.Published(snapshot)
            currentState = published
            published
        }

    override fun close() {
        val published =
            synchronized(monitor) {
                if (currentState == ProductionCapabilityPublicationState.Closed) {
                    return
                }
                val snapshot =
                    (currentState as? ProductionCapabilityPublicationState.Published)
                        ?.capabilities
                currentState = ProductionCapabilityPublicationState.Closed
                snapshot
            }
        if (published != null) {
            try {
                published.close()
            } catch (failure: Throwable) {
                throw ProductionCapabilityCloseException(failure)
            }
        }
    }

    private fun fail(): ProductionCapabilityPublicationState.Failed =
        ProductionCapabilityPublicationState
            .Failed(
                ProductionCapabilityPublicationFailure(
                    ProductionCapabilityPublicationFailureCode.ASSEMBLY_CLAIM_FAILED,
                ),
            ).also { currentState = it }
}

internal class ProductionCapabilityCloseException(
    failure: Throwable,
) : IllegalStateException("Failed to release the production capability snapshot", failure)

private fun CompleteProductionCapabilityAssembly.closeQuietly() {
    try {
        close()
    } catch (_: Throwable) {
        // Publication already failed. No partial snapshot is exposed.
    }
}

private fun CompleteProductionCapabilityAssembly.closeAfterClaimFailure(
    ownerFailure: Throwable,
) {
    try {
        close()
    } catch (closeFailure: Throwable) {
        ownerFailure.addSuppressed(closeFailure)
    }
}
