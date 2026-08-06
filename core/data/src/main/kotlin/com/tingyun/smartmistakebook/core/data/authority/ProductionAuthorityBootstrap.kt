package com.tingyun.smartmistakebook.core.data.authority

import android.content.Context
import com.tingyun.smartmistakebook.core.data.production.CompleteProductionCapabilityAssembly
import com.tingyun.smartmistakebook.core.domain.ConfiguredModelExecutionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Application capabilities that must stop reading or writing the legacy business database before
 * the three independent authorities may be published.
 */
enum class ProductionAdapter {
    CAPTURE_WORKFLOW,
    BATCH_IMPORT,
    MISTAKE_DETAIL,
    MISTAKE_ORGANIZATION,
    STUDENT_MISTAKE_CATALOG,
    TUTOR_SESSION,
    TUTOR_LEARNING_MEMORY,
    TUTOR_MASTERY_CONTEXT,
    TUTOR_TEACHING_REFERENCE,
    MODEL_TASK_QUEUE,
    MODEL_ASSET_DOCUMENTS,
    REVIEW_PLANNING,
    WORK_MANAGER_COORDINATION,
    TERMINAL_CUTOVER_GATE,
}

/**
 * An explicit inventory rather than a release flag.
 *
 * Adding a new adapter to [ProductionAdapter] makes it unavailable until the production manifest
 * names it. This keeps application readiness fail-closed as the graph evolves.
 */
class ProductionAdapterManifest private constructor(
    availableAdapters: Set<ProductionAdapter>,
) {
    val availableAdapters: Set<ProductionAdapter> = availableAdapters.toSet()

    val missingAdapters: Set<ProductionAdapter> =
        ProductionAdapter.entries.toSet() - this.availableAdapters

    val isComplete: Boolean
        get() = missingAdapters.isEmpty()

    companion object {
        fun fromAvailable(
            availableAdapters: Set<ProductionAdapter>,
        ): ProductionAdapterManifest =
            ProductionAdapterManifest(availableAdapters)
    }
}

fun interface ProductionAdapterAvailabilityPort {
    fun readManifest(): ProductionAdapterManifest
}

enum class ProductionAuthorityStartupBlockCode {
    MISSING_PRODUCTION_ADAPTERS,
    PRODUCTION_GATE_UNAVAILABLE,
    MISSING_PRODUCTION_KNOWLEDGE,
    INVALID_PRODUCTION_KNOWLEDGE,
    PRODUCTION_KNOWLEDGE_UNAVAILABLE,
    LEGACY_AUTHORITY_CONFLICT,
    TERMINAL_PROOF_INCOMPLETE,
    CAPABILITY_PUBLICATION_FAILED,
}

sealed interface ProductionAuthorityStartupState {
    data object Loading : ProductionAuthorityStartupState

    /**
     * This state carries no database, DAO, proof, witness, or raw authority handle.
     *
     * The bootstrap publishes the application capability graph before exposing this singleton.
     */
    data object Ready : ProductionAuthorityStartupState

    data class Blocked(
        val code: ProductionAuthorityStartupBlockCode,
        val missingAdapters: Set<ProductionAdapter> = emptySet(),
    ) : ProductionAuthorityStartupState
}

/**
 * The application publication boundary.
 *
 * Adapter completeness is checked before terminal proof recovery. Business capabilities are
 * published only after the terminal gate returns Ready, and [state] changes to Ready only after
 * publication completes.
 */
class ProductionAuthorityBootstrap internal constructor(
    private val adapterAvailability: ProductionAdapterAvailabilityPort,
    private val publicationPreparer: ProductionAuthorityPublicationPreparer?,
    private val publishCapabilities:
        suspend (CompleteProductionCapabilityAssembly) -> Unit,
) {
    private val startMutex = Mutex()
    private val mutableState =
        MutableStateFlow<ProductionAuthorityStartupState>(
            ProductionAuthorityStartupState.Loading,
        )

    val state: StateFlow<ProductionAuthorityStartupState> = mutableState.asStateFlow()

    suspend fun start() {
        startMutex.withLock {
            if (mutableState.value != ProductionAuthorityStartupState.Loading) return

            val manifest =
                try {
                    adapterAvailability.readManifest()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.value =
                        ProductionAuthorityStartupState.Blocked(
                            ProductionAuthorityStartupBlockCode
                                .CAPABILITY_PUBLICATION_FAILED,
                        )
                    return
                }
            if (!manifest.isComplete) {
                mutableState.value =
                    ProductionAuthorityStartupState.Blocked(
                        code =
                            ProductionAuthorityStartupBlockCode
                                .MISSING_PRODUCTION_ADAPTERS,
                        missingAdapters = manifest.missingAdapters,
                    )
                return
            }

            val activePreparer = publicationPreparer
            if (activePreparer == null) {
                mutableState.value =
                    ProductionAuthorityStartupState.Blocked(
                        ProductionAuthorityStartupBlockCode.PRODUCTION_GATE_UNAVAILABLE,
                    )
                return
            }
            val preparation =
                try {
                    activePreparer.prepare()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.value =
                        ProductionAuthorityStartupState.Blocked(
                            ProductionAuthorityStartupBlockCode
                                .TERMINAL_PROOF_INCOMPLETE,
                        )
                    return
                }
            when (preparation) {
                is ProductionAuthorityPublicationPreparation.Blocked -> {
                    mutableState.value =
                        ProductionAuthorityStartupState.Blocked(preparation.code)
                    return
                }

                is ProductionAuthorityPublicationPreparation.Complete -> {
                    val assembly = preparation.assembly
                    try {
                        publishCapabilities(assembly)
                    } catch (failure: Throwable) {
                        assembly.closeAfterPublicationFailure(failure)
                        if (failure is CancellationException) throw failure
                        if (failure !is Exception) throw failure
                        mutableState.value =
                            ProductionAuthorityStartupState.Blocked(
                                ProductionAuthorityStartupBlockCode
                                    .CAPABILITY_PUBLICATION_FAILED,
                            )
                        return
                    }
                }
            }
            mutableState.value = ProductionAuthorityStartupState.Ready
        }
    }
}

/**
 * Public construction path for an application whose adapter inventory is not complete yet.
 *
 * A complete manifest still cannot become Ready through this factory because it has no terminal
 * proof gate. The production factory will replace this path when all adapters are implemented.
 */
object ProductionAuthorityBootstrapFactory {
    fun failClosed(
        adapterAvailability: ProductionAdapterAvailabilityPort,
        publishCapabilities: suspend () -> Unit,
    ): ProductionAuthorityBootstrap =
        ProductionAuthorityBootstrap(
            adapterAvailability = adapterAvailability,
            publicationPreparer = null,
            publishCapabilities = { publishCapabilities() },
        )

    /**
     * Canonical production entry point.
     *
     * It never accepts a terminal Ready token, proof, runtime, database owner, generation, or
     * witness from app code. The canonical preparer obtains each authority from its audited local
     * owner and remains fail-closed unless the durable terminal chain verifies in full.
     */
    fun createProduction(
        context: Context,
        adapterAvailability: ProductionAdapterAvailabilityPort,
        modelExecutionLeaseProvider: ProductionModelExecutionLeaseProvider,
        publishCapabilities:
            suspend (CompleteProductionCapabilityAssembly) -> Unit,
    ): ProductionAuthorityBootstrap =
        ProductionAuthorityBootstrap(
            adapterAvailability = adapterAvailability,
            publicationPreparer =
                CanonicalProductionAuthorityPublicationPreparer(
                    context.applicationContext ?: context,
                    modelExecutionLeaseProvider,
                ),
            publishCapabilities = publishCapabilities,
        )

    @JvmSynthetic
    internal fun createWithProductionPreparer(
        adapterAvailability: ProductionAdapterAvailabilityPort,
        publicationPreparer: ProductionAuthorityPublicationPreparer,
        publishCapabilities:
            suspend (CompleteProductionCapabilityAssembly) -> Unit,
    ): ProductionAuthorityBootstrap =
        ProductionAuthorityBootstrap(
            adapterAvailability = adapterAvailability,
            publicationPreparer = publicationPreparer,
            publishCapabilities = publishCapabilities,
        )
}

/** App wiring can issue a configured provider lease, but cannot observe any terminal authority. */
fun interface ProductionModelExecutionLeaseProvider {
    fun acquire(): ConfiguredModelExecutionLease
}

internal sealed interface ProductionAuthorityPublicationPreparation {
    data class Complete(
        val assembly: CompleteProductionCapabilityAssembly,
    ) : ProductionAuthorityPublicationPreparation

    data class Blocked(
        val code: ProductionAuthorityStartupBlockCode,
    ) : ProductionAuthorityPublicationPreparation
}

internal fun interface ProductionAuthorityPublicationPreparer {
    suspend fun prepare(): ProductionAuthorityPublicationPreparation
}

private fun CompleteProductionCapabilityAssembly.closeAfterPublicationFailure(
    owner: Throwable,
) {
    try {
        close()
    } catch (closeFailure: Throwable) {
        owner.addSuppressed(closeFailure)
    }
}
