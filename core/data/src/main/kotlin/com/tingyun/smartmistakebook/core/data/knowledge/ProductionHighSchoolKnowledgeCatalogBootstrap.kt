package com.tingyun.smartmistakebook.core.data.knowledge

import android.content.Context
import com.tingyun.smartmistakebook.core.data.authority.ActivatedHighSchoolKnowledgeCatalog
import com.tingyun.smartmistakebook.core.data.authority.HighSchoolKnowledgeCatalogBootstrap
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeProductionRuntime
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofIssuer

/**
 * Production startup is read-only and fail-closed.
 *
 * Installation remains a separate reviewed release process. Missing, historical-sample,
 * unregistered, non-production, tampered, or stale packs cannot be substituted here.
 */
internal object ProductionHighSchoolKnowledgeCatalogBootstrap :
    HighSchoolKnowledgeCatalogBootstrap {
    override suspend fun openActivatedCatalog(
        context: Context,
        proofIssuer: KnowledgeReferenceProofIssuer,
    ): ActivatedHighSchoolKnowledgeCatalog {
        val runtime =
            HighSchoolKnowledgeProductionRuntime.open(
                context = context.applicationContext,
                proofIssuer = proofIssuer,
            )
        return ActivatedHighSchoolKnowledgeCatalog(
            catalog = runtime.catalog,
            manifest = runtime.manifest,
            activation = runtime.activation,
            productionCutoverEligible = true,
            productionActivationWitness = runtime.witness,
        )
    }
}
