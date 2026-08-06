package com.tingyun.smartmistakebook.core.knowledge.database

import android.content.Context

/**
 * Opaque proof that the current app-private knowledge database was freshly validated against the
 * fixed production trust registry.
 *
 * The constructor stays private so callers can forward this proof but cannot manufacture one from
 * an activation receipt or caller-selected metadata.
 */
class ProductionKnowledgeActivationWitness private constructor(
    val activationGeneration: Long,
    val activatedAtEpochMillis: Long,
    val packId: String,
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val manifestFingerprint: String,
) {
    internal companion object {
        fun issue(
            activationGeneration: Long,
            activatedAtEpochMillis: Long,
            packId: String,
            knowledgePackVersion: String,
            taxonomyVersion: String,
            manifestFingerprint: String,
        ): ProductionKnowledgeActivationWitness =
            ProductionKnowledgeActivationWitness(
                activationGeneration = activationGeneration,
                activatedAtEpochMillis = activatedAtEpochMillis,
                packId = packId,
                knowledgePackVersion = knowledgePackVersion,
                taxonomyVersion = taxonomyVersion,
                manifestFingerprint = manifestFingerprint,
            )
    }
}

/**
 * Narrow read capability for terminal authority cutover. It exposes neither Room nor installation
 * authority, and every read performs a new physical verification.
 */
fun interface ProductionKnowledgeActivationWitnessReader {
    suspend fun readFreshProductionActivation(): ProductionKnowledgeActivationWitness?
}

object HighSchoolKnowledgeProductionCutover {
    /**
     * Creates a read-only capability bound to the app-private `high-school-knowledge.db`.
     */
    fun witnessReader(context: Context): ProductionKnowledgeActivationWitnessReader {
        val applicationContext = context.applicationContext
        return ProductionKnowledgeActivationWitnessReader {
            KnowledgePackActivationManager.readFreshProductionCutoverWitness(applicationContext)
        }
    }
}
