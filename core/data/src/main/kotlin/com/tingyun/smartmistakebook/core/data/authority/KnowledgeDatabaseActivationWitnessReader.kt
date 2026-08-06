package com.tingyun.smartmistakebook.core.data.authority

import android.content.Context
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeProductionCutover
import com.tingyun.smartmistakebook.core.knowledge.database.ProductionKnowledgeActivationWitness
import com.tingyun.smartmistakebook.core.knowledge.database.ProductionKnowledgeActivationWitnessReader

/**
 * Adapts the knowledge authority's opaque, freshly verified proof into the terminal cutover
 * protocol. This class owns no database, DAO, SQL, or mutation capability.
 */
internal class KnowledgeDatabaseActivationWitnessReader(
    context: Context,
) : KnowledgeActivationWitnessReader {
    private val verifiedActivationReader: ProductionKnowledgeActivationWitnessReader =
        HighSchoolKnowledgeProductionCutover.witnessReader(context.applicationContext)

    override suspend fun readCurrentActivationWitness(): KnowledgeActivationWitness? =
        verifiedActivationReader
            .readFreshProductionActivation()
            ?.toCutoverWitness()
}

private fun ProductionKnowledgeActivationWitness.toCutoverWitness():
    KnowledgeActivationWitness =
    KnowledgeActivationWitness.create(
        activationGeneration = activationGeneration,
        activatedAtEpochMillis = activatedAtEpochMillis,
        packId = packId,
        knowledgePackVersion = knowledgePackVersion,
        taxonomyVersion = taxonomyVersion,
        manifestFingerprint = manifestFingerprint,
    )
