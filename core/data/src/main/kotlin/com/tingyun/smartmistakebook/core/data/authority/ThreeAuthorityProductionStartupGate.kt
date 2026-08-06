package com.tingyun.smartmistakebook.core.data.authority

import java.security.MessageDigest
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

/**
 * Production publication state for the three independent learning authorities.
 *
 * The state deliberately carries no database handle, cutover record, activation witness, or proof
 * bytes. Callers may publish business capabilities only after receiving [Ready].
 */
internal sealed interface ThreeAuthorityProductionStartupState {
    class Ready private constructor() : ThreeAuthorityProductionStartupState {
        companion object {
            internal fun fromVerifiedProof(
                proof: VerifiedThreeAuthorityFence,
            ): Ready {
                val proofClaim = VerifiedThreeAuthorityFence.Owner.claim(proof)
                return Ready().also { ready ->
                    ThreeAuthorityReadyOwner.register(ready, proofClaim)
                }
            }
        }
    }

    data class Blocked(
        val reason: ThreeAuthorityStartupBlockReason,
    ) : ThreeAuthorityProductionStartupState
}

/**
 * Opaque, single-use statement that a zero-field [ThreeAuthorityProductionStartupState.Ready]
 * token and one freshly recovered terminal proof identify the same current authority generation.
 *
 * The constructor is private and the process-local owner records issued instances by identity.
 * Reflective construction therefore cannot be consumed by the runtime binding owner.
 */
internal class CurrentGenerationFenceBinding private constructor() : AutoCloseable {
    @JvmSynthetic
    internal fun claimForRuntimeBinding() {
        check(ThreeAuthorityReadyOwner.claimBinding(this)) {
            "Current-generation fence binding is revoked, forged, or already claimed"
        }
    }

    override fun close() {
        ThreeAuthorityReadyOwner.revokeBinding(this)
    }

    internal companion object {
        @JvmSynthetic
        internal fun bind(
            ready: ThreeAuthorityProductionStartupState.Ready,
            reverifiedProof: VerifiedThreeAuthorityFence,
        ): CurrentGenerationFenceBinding {
            val binding = CurrentGenerationFenceBinding()
            ThreeAuthorityReadyOwner.bind(
                ready = ready,
                reverifiedProof = reverifiedProof,
                binding = binding,
            )
            return binding
        }
    }
}

@JvmSynthetic
internal fun ThreeAuthorityProductionStartupState.Ready.revokeUnclaimedProof() {
    ThreeAuthorityReadyOwner.revokeReady(this)
}

/**
 * File-private identity registry. No proof bytes or generation values leave this file through a
 * public type, and every successful transition consumes its predecessor exactly once.
 */
private object ThreeAuthorityReadyOwner {
    private val monitor = Any()
    private val readyProofs =
        IdentityHashMap<
            ThreeAuthorityProductionStartupState.Ready,
            VerifiedThreeAuthorityFence.Claim,
        >()
    private val bindings =
        java.util.Collections.newSetFromMap(
            IdentityHashMap<CurrentGenerationFenceBinding, Boolean>(),
        )

    fun register(
        ready: ThreeAuthorityProductionStartupState.Ready,
        proof: VerifiedThreeAuthorityFence.Claim,
    ) {
        synchronized(monitor) {
            check(readyProofs.put(ready, proof) == null) {
                "Ready token was already registered"
            }
        }
    }

    fun bind(
        ready: ThreeAuthorityProductionStartupState.Ready,
        reverifiedProof: VerifiedThreeAuthorityFence,
        binding: CurrentGenerationFenceBinding,
    ) {
        val reverifiedClaim = VerifiedThreeAuthorityFence.Owner.claim(reverifiedProof)
        synchronized(monitor) {
            val issuedProof =
                checkNotNull(readyProofs.remove(ready)) {
                    "Ready token is forged, revoked, or already claimed"
                }
            check(
                issuedProof.cutoverGeneration == reverifiedClaim.cutoverGeneration &&
                    constantTimeEquals(
                        issuedProof.proofFingerprint,
                        reverifiedClaim.proofFingerprint,
                    ),
            ) {
                "Ready token does not identify the freshly verified authority generation"
            }
            check(bindings.add(binding)) {
                "Current-generation fence binding was already registered"
            }
        }
    }

    fun claimBinding(binding: CurrentGenerationFenceBinding): Boolean =
        synchronized(monitor) {
            bindings.remove(binding)
        }

    fun revokeBinding(binding: CurrentGenerationFenceBinding) {
        synchronized(monitor) {
            bindings.remove(binding)
        }
    }

    fun revokeReady(ready: ThreeAuthorityProductionStartupState.Ready) {
        synchronized(monitor) {
            readyProofs.remove(ready)
        }
    }

    private fun constantTimeEquals(
        left: String,
        right: String,
    ): Boolean =
        MessageDigest.isEqual(
            left.toByteArray(Charsets.US_ASCII),
            right.toByteArray(Charsets.US_ASCII),
        )
}

internal enum class ThreeAuthorityStartupBlockReason {
    MISSING_PRODUCTION_KNOWLEDGE,
    INVALID_PRODUCTION_KNOWLEDGE,
    KNOWLEDGE_VERIFICATION_FAILED,
    LEGACY_FENCE_CONFLICT,
    TERMINAL_PROOF_INCOMPLETE_OR_INVALID,
    LEGACY_BUSINESS_WRITE_BARRIER_CONFLICT,
    POST_VERIFICATION_FENCE_CONFLICT,
}

/**
 * A narrow, short-lived legacy migration session.
 *
 * Implementations may privately own a legacy database handle, but that handle is never returned
 * through this interface. [migrate] and [close] both run while the process-wide writer exclusion
 * is held.
 */
internal interface LegacyMigrationWriterSession : AutoCloseable {
    suspend fun migrate()
}

internal fun interface LegacyMigrationWriterSessionFactory {
    suspend fun open(): LegacyMigrationWriterSession
}

internal enum class LegacyMigrationWriterRunResult {
    COMPLETED,
    DENIED_NO_PRODUCTION_KNOWLEDGE,
    DENIED_INVALID_PRODUCTION_KNOWLEDGE,
    DENIED_KNOWLEDGE_VERIFICATION_FAILED,
    DENIED_FENCED,
    DENIED_CONFLICT,
}

/**
 * Fail-closed production startup coordinator.
 *
 * Readiness requires a fresh formal knowledge activation plus the unforgeable terminal proof that
 * re-verifies both student and mastery imports. Legacy migration can only run through the same
 * process-wide exclusion used by terminal cutover, and the session is always closed before that
 * exclusion is released.
 */
internal class ThreeAuthorityProductionStartupGate(
    private val legacyWriteFenceControl: ThreeAuthorityLegacyWriteFenceControl,
    private val knowledge: KnowledgeActivationWitnessReader,
    private val terminalProofCoordinator: ThreeAuthorityCutoverProofCoordinator,
    private val legacyBusinessWriteBarrierOwner:
        ThreeAuthorityLegacyBusinessWriteBarrierOwner?,
) {
    suspend fun recoverOrBlock(): ThreeAuthorityProductionStartupState {
        when (readKnowledgePreflight()) {
            KnowledgePreflight.Missing ->
                return blocked(ThreeAuthorityStartupBlockReason.MISSING_PRODUCTION_KNOWLEDGE)
            KnowledgePreflight.Invalid ->
                return blocked(ThreeAuthorityStartupBlockReason.INVALID_PRODUCTION_KNOWLEDGE)
            KnowledgePreflight.Unavailable ->
                return blocked(ThreeAuthorityStartupBlockReason.KNOWLEDGE_VERIFICATION_FAILED)
            is KnowledgePreflight.Verified -> Unit
        }

        when (readLegacyFenceStateOrConflict()) {
            LegacyWriteFenceState.CONFLICT ->
                return blocked(ThreeAuthorityStartupBlockReason.LEGACY_FENCE_CONFLICT)
            LegacyWriteFenceState.UNFENCED,
            LegacyWriteFenceState.FENCED -> Unit
        }

        val proof =
            try {
                terminalProofCoordinator.recoverOrVerify()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return blocked(
                    ThreeAuthorityStartupBlockReason.TERMINAL_PROOF_INCOMPLETE_OR_INVALID,
                )
            }

        when (readLegacyFenceStateOrConflict()) {
            LegacyWriteFenceState.FENCED -> Unit
            LegacyWriteFenceState.UNFENCED ->
                return blockedAfterRevokingProof(
                    proof,
                    ThreeAuthorityStartupBlockReason.TERMINAL_PROOF_INCOMPLETE_OR_INVALID,
                )
            LegacyWriteFenceState.CONFLICT ->
                return blockedAfterRevokingProof(
                    proof,
                    ThreeAuthorityStartupBlockReason.POST_VERIFICATION_FENCE_CONFLICT,
                )
        }

        val barrierOwner =
            legacyBusinessWriteBarrierOwner
                ?: return blockedAfterRevokingProof(
                    proof,
                    ThreeAuthorityStartupBlockReason
                        .LEGACY_BUSINESS_WRITE_BARRIER_CONFLICT,
                )
        try {
            barrierOwner.finalizeAfterVerifiedFence(proof)
        } catch (cancelled: CancellationException) {
            VerifiedThreeAuthorityFence.Owner.revoke(proof)
            throw cancelled
        } catch (_: Exception) {
            return blockedAfterRevokingProof(
                proof,
                ThreeAuthorityStartupBlockReason.LEGACY_BUSINESS_WRITE_BARRIER_CONFLICT,
            )
        }

        return when (readLegacyFenceStateOrConflict()) {
            LegacyWriteFenceState.FENCED ->
                ThreeAuthorityProductionStartupState.Ready.fromVerifiedProof(proof)
            LegacyWriteFenceState.UNFENCED,
            LegacyWriteFenceState.CONFLICT,
            ->
                blockedAfterRevokingProof(
                    proof,
                    ThreeAuthorityStartupBlockReason.POST_VERIFICATION_FENCE_CONFLICT,
                )
        }
    }

    /**
     * Runs a legacy business writer only before the first student or mastery fence.
     *
     * A formal knowledge activation is checked before entering the exclusion and again immediately
     * before opening the session. The session is closed on success, failure, and cancellation.
     * This method never creates or returns a Ready state.
     */
    suspend fun runLegacyMigrationWriterIfPermitted(
        sessionFactory: LegacyMigrationWriterSessionFactory,
    ): LegacyMigrationWriterRunResult {
        val initialKnowledge =
            when (val preflight = readKnowledgePreflight()) {
                KnowledgePreflight.Missing ->
                    return LegacyMigrationWriterRunResult.DENIED_NO_PRODUCTION_KNOWLEDGE
                KnowledgePreflight.Invalid ->
                    return LegacyMigrationWriterRunResult.DENIED_INVALID_PRODUCTION_KNOWLEDGE
                KnowledgePreflight.Unavailable ->
                    return LegacyMigrationWriterRunResult.DENIED_KNOWLEDGE_VERIFICATION_FAILED
                is KnowledgePreflight.Verified -> preflight.witness
            }

        var completed = false
        val fenceState =
            legacyWriteFenceControl.runLegacyMigrationWriterIfUnfenced {
                val currentKnowledge = knowledge.readCurrentActivationWitness()
                if (
                    currentKnowledge == null ||
                    !currentKnowledge.hasValidFingerprint() ||
                    currentKnowledge != initialKnowledge
                ) {
                    throw ThreeAuthorityCutoverIntegrityException(
                        "Production knowledge activation changed before legacy writer open",
                    )
                }

                val session = sessionFactory.open()
                useLegacyMigrationSession(session) {
                    it.migrate()
                    val finalKnowledge = knowledge.readCurrentActivationWitness()
                    if (
                        finalKnowledge == null ||
                        !finalKnowledge.hasValidFingerprint() ||
                        finalKnowledge != initialKnowledge
                    ) {
                        throw ThreeAuthorityCutoverIntegrityException(
                            "Production knowledge activation changed during legacy migration",
                        )
                    }
                    completed = true
                }
            }

        return when (fenceState) {
            LegacyWriteFenceState.UNFENCED -> {
                check(completed) {
                    "Legacy migration writer returned without completing its session"
                }
                LegacyMigrationWriterRunResult.COMPLETED
            }
            LegacyWriteFenceState.FENCED -> LegacyMigrationWriterRunResult.DENIED_FENCED
            LegacyWriteFenceState.CONFLICT -> LegacyMigrationWriterRunResult.DENIED_CONFLICT
        }
    }

    private suspend fun readKnowledgePreflight(): KnowledgePreflight {
        return try {
            val witness = knowledge.readCurrentActivationWitness()
            when {
                witness == null -> KnowledgePreflight.Missing
                witness.hasValidFingerprint() -> KnowledgePreflight.Verified(witness)
                else -> KnowledgePreflight.Invalid
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            KnowledgePreflight.Unavailable
        }
    }

    private suspend fun readLegacyFenceStateOrConflict(): LegacyWriteFenceState =
        try {
            legacyWriteFenceControl.readLegacyWriteFenceState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LegacyWriteFenceState.CONFLICT
        }

    private fun blocked(
        reason: ThreeAuthorityStartupBlockReason,
    ): ThreeAuthorityProductionStartupState.Blocked =
        ThreeAuthorityProductionStartupState.Blocked(reason)

    private fun blockedAfterRevokingProof(
        proof: VerifiedThreeAuthorityFence,
        reason: ThreeAuthorityStartupBlockReason,
    ): ThreeAuthorityProductionStartupState.Blocked {
        VerifiedThreeAuthorityFence.Owner.revoke(proof)
        return blocked(reason)
    }

    companion object {
        /**
         * Migration-only gate. It shares the exact production knowledge and legacy-fence
         * exclusion, but can never return terminal Ready because it owns no barrier handoff.
         */
        fun migrationOnly(
            legacyWriteFenceControl: ThreeAuthorityLegacyWriteFenceControl,
            knowledge: KnowledgeActivationWitnessReader,
        ): ThreeAuthorityProductionStartupGate =
            ThreeAuthorityProductionStartupGate(
                legacyWriteFenceControl = legacyWriteFenceControl,
                knowledge = knowledge,
                terminalProofCoordinator =
                    ThreeAuthorityCutoverProofCoordinator(
                        legacyWriteFenceControl = legacyWriteFenceControl,
                        knowledge = knowledge,
                    ),
                legacyBusinessWriteBarrierOwner = null,
            )
    }
}

private sealed interface KnowledgePreflight {
    data object Missing : KnowledgePreflight

    data object Invalid : KnowledgePreflight

    data object Unavailable : KnowledgePreflight

    data class Verified(
        val witness: KnowledgeActivationWitness,
    ) : KnowledgePreflight
}

private suspend inline fun useLegacyMigrationSession(
    session: LegacyMigrationWriterSession,
    block: suspend (LegacyMigrationWriterSession) -> Unit,
) {
    var ownerFailure: Throwable? = null
    try {
        block(session)
    } catch (failure: Throwable) {
        ownerFailure = failure
        throw failure
    } finally {
        try {
            session.close()
        } catch (closeFailure: Throwable) {
            val failure = ownerFailure
            if (failure == null) {
                throw closeFailure
            }
            failure.addSuppressed(closeFailure)
        }
    }
}
