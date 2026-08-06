package com.tingyun.smartmistakebook.core.data.authority

import android.content.Context
import com.tingyun.smartmistakebook.core.database.TrustedLegacyCutoverMigrationDatabaseCapability
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import java.util.IdentityHashMap
import kotlin.jvm.JvmSynthetic

/**
 * One process-local ownership transfer from a freshly reverified terminal generation to the
 * runtime opened for that exact canonical Android application context.
 *
 * The object exposes no learner id, generation, proof fingerprint, database identity, knowledge
 * witness, or raw authority handle. It can be claimed exactly once by the capability assembly
 * owner; closing it before that claim closes the runtime.
 */
internal class GenerationBoundLearningAuthorityRuntime private constructor() :
    AutoCloseable {
    @JvmSynthetic
    internal fun claimForCapabilityAssembly(): LocalLearningAuthorityRuntime =
        GenerationBoundRuntimeOwner.claim(this)

    override fun close() {
        GenerationBoundRuntimeOwner.revoke(this)?.close()
    }

    internal companion object {
        @JvmSynthetic
        internal suspend fun bind(
            context: Context,
            ready: ThreeAuthorityProductionStartupState.Ready,
            runtimeClaim: CurrentLocalLearnerRuntimeClaim,
            legacyProofSource: TrustedLegacyCutoverMigrationDatabaseCapability,
        ): GenerationBoundLearningAuthorityRuntime {
            val canonicalContext = context.canonicalApplicationContext()
            val runtimeState = CurrentLocalLearnerRuntimeClaim.Owner.claim(runtimeClaim)
            val runtime = runtimeState.runtime
            var currentState: CurrentGenerationReverification.State? = null
            var fenceBinding: CurrentGenerationFenceBinding? = null
            try {
                check(runtimeState.canonicalContext === canonicalContext) {
                    "Authority runtime claim belongs to another application context"
                }
                check(runtimeState.learnerId == LOCAL_LEARNER_ID) {
                    "Authority runtime claim belongs to another learner"
                }
                check(runtime.belongsToCanonicalContext(canonicalContext)) {
                    "Authority runtime belongs to another application context"
                }
                check(runtime.belongsToLearner(LOCAL_LEARNER_ID)) {
                    "Authority runtime does not belong to the canonical local learner"
                }

                val current =
                    reverifyCurrentGenerationFromAuditedOwners(
                        canonicalContext = canonicalContext,
                        legacyProofSource = legacyProofSource,
                    )
                val verifiedState =
                    try {
                        CurrentGenerationReverification.Owner.claim(
                            current,
                            canonicalContext,
                        )
                    } finally {
                        current.close()
                    }
                currentState = verifiedState
                check(verifiedState.learnerId == runtimeState.learnerId) {
                    "Current generation belongs to another learner"
                }
                check(runtime.hasCurrentKnowledgeWitness(verifiedState.knowledge)) {
                    "Authority runtime knowledge activation is not current"
                }
                check(runtime.claimForProductionGeneration()) {
                    "Authority runtime was already bound to a production generation"
                }
                val verifiedFenceBinding =
                    CurrentGenerationFenceBinding.bind(
                        ready = ready,
                        reverifiedProof = verifiedState.proof,
                    )
                fenceBinding = verifiedFenceBinding
                currentState = null
                verifiedFenceBinding.claimForRuntimeBinding()
                return GenerationBoundLearningAuthorityRuntime().also { binding ->
                    GenerationBoundRuntimeOwner.register(binding, runtime)
                }
            } catch (failure: Throwable) {
                currentState?.let { state ->
                    VerifiedThreeAuthorityFence.Owner.revoke(state.proof)
                }
                fenceBinding?.close()
                closeAfterFailure(runtime, failure)
                throw failure
            }
        }
    }
}

private object GenerationBoundRuntimeOwner {
    private val monitor = Any()
    private val runtimes =
        IdentityHashMap<
            GenerationBoundLearningAuthorityRuntime,
            LocalLearningAuthorityRuntime,
        >()

    fun register(
        binding: GenerationBoundLearningAuthorityRuntime,
        runtime: LocalLearningAuthorityRuntime,
    ) {
        synchronized(monitor) {
            check(runtimes.put(binding, runtime) == null) {
                "Generation-bound runtime was already registered"
            }
        }
    }

    fun claim(
        binding: GenerationBoundLearningAuthorityRuntime,
    ): LocalLearningAuthorityRuntime =
        synchronized(monitor) {
            checkNotNull(runtimes.remove(binding)) {
                "Generation-bound runtime is revoked, forged, or already claimed"
            }
        }

    fun revoke(
        binding: GenerationBoundLearningAuthorityRuntime,
    ): LocalLearningAuthorityRuntime? =
        synchronized(monitor) {
            runtimes.remove(binding)
        }
}

private fun Context.canonicalApplicationContext(): Context =
    applicationContext ?: this

private fun closeAfterFailure(
    resource: AutoCloseable,
    ownerFailure: Throwable,
) {
    try {
        resource.close()
    } catch (closeFailure: Throwable) {
        ownerFailure.addSuppressed(closeFailure)
    }
}
