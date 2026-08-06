package com.tingyun.smartmistakebook.core.knowledge.database

import android.content.Context
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofIssuer
import kotlin.jvm.JvmSynthetic

/**
 * One verified, immutable production knowledge generation.
 *
 * The catalog lease prevents activation from replacing the database until [catalog] is closed.
 * The manifest, receipt, and witness are all re-read while that lease is held, so callers never
 * compose identities from different generations.
 */
class ProductionHighSchoolKnowledgeRuntime private constructor(
    val catalog: HighSchoolKnowledgeCatalog,
    val manifest: KnowledgePackManifest,
    val activation: KnowledgeCatalogActivationReceipt,
    val witness: ProductionKnowledgeActivationWitness,
) : AutoCloseable {
    override fun close() {
        catalog.close()
    }

    internal companion object {
        fun create(
            catalog: HighSchoolKnowledgeCatalog,
            manifest: KnowledgePackManifest,
            activation: KnowledgeCatalogActivationReceipt,
            witness: ProductionKnowledgeActivationWitness,
        ): ProductionHighSchoolKnowledgeRuntime =
            ProductionHighSchoolKnowledgeRuntime(
                catalog = catalog,
                manifest = manifest,
                activation = activation,
                witness = witness,
            )
    }
}

/**
 * Opens only a fixed-registry, production-cutover-eligible knowledge pack.
 *
 * This surface exposes no installer, builder, DAO, database, SQL connection, or write authority.
 */
object HighSchoolKnowledgeProductionRuntime {
    suspend fun open(context: Context): ProductionHighSchoolKnowledgeRuntime {
        val proofAuthority = KnowledgeReferenceProofAuthority.create()
        return open(context, proofAuthority.issuer)
    }

    @JvmSynthetic
    suspend fun open(
        context: Context,
        proofIssuer: KnowledgeReferenceProofIssuer,
    ): ProductionHighSchoolKnowledgeRuntime {
        val applicationContext = context.applicationContext
        val catalog =
            HighSchoolKnowledgeCatalogFactory.open(
                context = applicationContext,
                proofIssuer = proofIssuer,
            )
        try {
            /*
             * The catalog lease is already active here. Pack activation therefore cannot advance
             * the generation between this full physical verification and the returned runtime.
             */
            val witness =
                requireNotNull(
                    HighSchoolKnowledgeProductionCutover
                        .witnessReader(applicationContext)
                        .readFreshProductionActivation(),
                ) {
                    "No registered production high-school knowledge pack is active"
                }
            val activation =
                requireNotNull(
                    KnowledgePackActivationManager.currentActivationReceipt(applicationContext),
                ) {
                    "The active high-school knowledge pack has no activation receipt"
                }
            val manifest = catalog.readManifest()
            requireSameProductionGeneration(
                manifest = manifest,
                activation = activation,
                witness = witness,
            )
            return ProductionHighSchoolKnowledgeRuntime.create(
                catalog = catalog,
                manifest = manifest,
                activation = activation,
                witness = witness,
            )
        } catch (failure: Throwable) {
            try {
                catalog.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }
}

internal fun requireSameProductionGeneration(
    manifest: KnowledgePackManifest,
    activation: KnowledgeCatalogActivationReceipt,
    witness: ProductionKnowledgeActivationWitness,
) {
    require(
        manifest.packId == activation.packId &&
            manifest.knowledgePackVersion == activation.knowledgePackVersion &&
            manifest.taxonomyVersion == activation.taxonomyVersion &&
            manifest.contentFingerprint == activation.manifestFingerprint,
    ) {
        "Knowledge catalog manifest and activation receipt identify different content"
    }
    require(
        witness.activationGeneration == activation.generation &&
            witness.activatedAtEpochMillis == activation.activatedAtEpochMillis &&
            witness.packId == activation.packId &&
            witness.knowledgePackVersion == activation.knowledgePackVersion &&
            witness.taxonomyVersion == activation.taxonomyVersion &&
            witness.manifestFingerprint == activation.manifestFingerprint,
    ) {
        "Knowledge activation witness is stale or identifies different content"
    }
}
