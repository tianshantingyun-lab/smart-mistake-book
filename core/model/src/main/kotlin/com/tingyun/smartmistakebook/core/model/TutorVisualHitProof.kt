package com.tingyun.smartmistakebook.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class TutorVisualSceneSourceKind {
    INLINE,
    GENERATED,
}

data class TutorVisualPresentationIdentity(
    val ownerModelTaskRequestId: String,
    val sourceKind: TutorVisualSceneSourceKind,
    val sceneTaskRequestId: String,
    val sceneId: String,
    val sceneFingerprint: String,
) {
    init {
        require(ownerModelTaskRequestId.isNotBlank())
        require(sceneTaskRequestId.isNotBlank() && sceneId.isNotBlank())
        require(sceneFingerprint.isSha256())
        if (sourceKind == TutorVisualSceneSourceKind.INLINE) {
            require(sceneTaskRequestId == ownerModelTaskRequestId) {
                "Inline tutor visuals must be owned by the presenting tutor task"
            }
        }
    }
}

@ConsistentCopyVisibility
data class TutorVisualHitProof internal constructor(
    val proofId: String,
    val presentation: TutorVisualPresentationIdentity,
    val panelId: String,
    val frameFingerprint: String,
    val stepIndex: Int,
    val selectedTargetId: String,
) {
    init {
        require(proofId.isNotBlank() && panelId.isNotBlank() && selectedTargetId.isNotBlank())
        require(frameFingerprint.isSha256())
        require(stepIndex >= 0)
    }
}

/**
 * Process-local capability minted only by the bounded renderer after a real, currently hittable
 * element wins hit testing. Persistence consumes it exactly once and process recreation drops it.
 */
object TutorVisualHitProofRegistry {
    private val proofs = ConcurrentHashMap<String, StoredProof>()

    /**
     * This is public only because the bounded renderer and persistence adapter are separate Gradle
     * modules. Production issuance is restricted to the real 2D pointer hit path; model payloads,
     * routes, and database adapters may only carry or consume the resulting capability.
     */
    fun issue(
        presentation: TutorVisualPresentationIdentity,
        panelId: String,
        frameFingerprint: String,
        stepIndex: Int,
        selectedTargetId: String,
        eligibleTargetIds: Set<String>,
        issuedAtElapsedMillis: Long = elapsedMillis(),
    ): TutorVisualHitProof {
        require(panelId.isNotBlank() && selectedTargetId in eligibleTargetIds)
        require(frameFingerprint.isSha256() && stepIndex >= 0)
        require(issuedAtElapsedMillis >= 0)
        pruneExpired(issuedAtElapsedMillis)
        val proof = TutorVisualHitProof(
            proofId = UUID.randomUUID().toString(),
            presentation = presentation,
            panelId = panelId,
            frameFingerprint = frameFingerprint,
            stepIndex = stepIndex,
            selectedTargetId = selectedTargetId,
        )
        proofs[proof.proofId] = StoredProof(
            proof = proof,
            expiresAtElapsedMillis = issuedAtElapsedMillis + PROOF_TTL_MILLIS,
        )
        return proof
    }

    fun consume(
        proof: TutorVisualHitProof,
        nowElapsedMillis: Long = elapsedMillis(),
    ): Boolean =
        claim(proof, nowElapsedMillis) && finalize(proof)

    fun claim(
        proof: TutorVisualHitProof,
        nowElapsedMillis: Long = elapsedMillis(),
    ): Boolean {
        require(nowElapsedMillis >= 0)
        var accepted = false
        proofs.compute(proof.proofId) { _, stored ->
            when {
                stored == null -> null
                stored.expiresAtElapsedMillis < nowElapsedMillis -> null
                stored.proof != proof -> stored
                stored.claimed -> stored
                else -> {
                    accepted = true
                    stored.copy(claimed = true)
                }
            }
        }
        return accepted
    }

    fun finalize(proof: TutorVisualHitProof): Boolean {
        var finalized = false
        proofs.compute(proof.proofId) { _, stored ->
            if (stored?.proof == proof && stored.claimed) {
                finalized = true
                null
            } else {
                stored
            }
        }
        return finalized
    }

    fun release(
        proof: TutorVisualHitProof,
        nowElapsedMillis: Long = elapsedMillis(),
    ): Boolean {
        require(nowElapsedMillis >= 0)
        var released = false
        proofs.compute(proof.proofId) { _, stored ->
            when {
                stored == null -> null
                stored.expiresAtElapsedMillis < nowElapsedMillis -> null
                stored.proof != proof || !stored.claimed -> stored
                else -> {
                    released = true
                    stored.copy(claimed = false)
                }
            }
        }
        return released
    }

    internal fun clearForTest() {
        proofs.clear()
    }

    private fun pruneExpired(nowElapsedMillis: Long) {
        proofs.entries.removeIf { (_, stored) ->
            stored.expiresAtElapsedMillis < nowElapsedMillis
        }
    }

    private fun elapsedMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

    private data class StoredProof(
        val proof: TutorVisualHitProof,
        val expiresAtElapsedMillis: Long,
        val claimed: Boolean = false,
    )

    private const val PROOF_TTL_MILLIS = 2 * 60 * 1_000L
    private const val NANOS_PER_MILLI = 1_000_000L
}

object TutorVisualSceneFingerprint {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = true
    }

    fun of(scene: TutorVisualScene): String =
        sha256(json.encodeToString(TutorVisualScene.serializer(), scene))
}

/**
 * Local renderer state is keyed by verified content and, when available, its exact presentation
 * owner. Model-controlled scene ids are deliberately not state authority.
 */
object TutorVisualPresentationStateKey {
    fun of(
        scene: TutorVisualScene,
        presentation: TutorVisualPresentationIdentity? = null,
    ): String {
        val sceneFingerprint = TutorVisualSceneFingerprint.of(scene)
        val exactPresentation = presentation?.takeIf { candidate ->
            candidate.sceneId == scene.sceneId &&
                candidate.sceneFingerprint == sceneFingerprint
        }
        return sha256(
            buildString {
                append(exactPresentation?.ownerModelTaskRequestId.orEmpty()).append('|')
                append(exactPresentation?.sourceKind?.name.orEmpty()).append('|')
                append(exactPresentation?.sceneTaskRequestId.orEmpty()).append('|')
                append(sceneFingerprint)
            },
        )
    }
}

object TutorVisualFrameFingerprint {
    fun of(
        panelId: String,
        stepId: String,
        stepIndex: Int,
        timeSeconds: Double,
        eligibleTargetIds: Set<String>,
    ): String {
        require(panelId.isNotBlank() && stepId.isNotBlank())
        require(stepIndex >= 0 && timeSeconds.isFinite() && timeSeconds >= 0.0)
        return sha256(
            buildString {
                append(panelId).append('|')
                append(stepId).append('|')
                append(stepIndex).append('|')
                append(timeSeconds.toBits()).append('|')
                append(eligibleTargetIds.sorted().joinToString(separator = "\u001f"))
            },
        )
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun String.isSha256(): Boolean =
    length == SHA_256_HEX_CHARS && all { character -> character in '0'..'9' || character in 'a'..'f' }
