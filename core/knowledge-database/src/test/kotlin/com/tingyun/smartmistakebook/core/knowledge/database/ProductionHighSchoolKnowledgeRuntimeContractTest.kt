package com.tingyun.smartmistakebook.core.knowledge.database

import java.lang.reflect.Modifier
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionHighSchoolKnowledgeRuntimeContractTest {
    @Test
    fun productionRuntimeCannotBeManufacturedOrExposeStorageAuthority() {
        assertTrue(
            ProductionHighSchoolKnowledgeRuntime::class.java.declaredConstructors.none { constructor ->
                Modifier.isPublic(constructor.modifiers) && !constructor.isSynthetic
            },
        )
        val forbiddenTokens = listOf("database", "dao", "builder", "install", "write", "sql")
        val publicSurface =
            ProductionHighSchoolKnowledgeRuntime::class.java.methods.map { it.name.lowercase() }
        forbiddenTokens.forEach { token ->
            assertTrue(publicSurface.none { name -> token in name })
        }
        assertNotNull(
            ProductionHighSchoolKnowledgeRuntime::class.java.methods.singleOrNull {
                it.name == "getCatalog"
            },
        )
        assertNotNull(
            ProductionHighSchoolKnowledgeRuntime::class.java.methods.singleOrNull {
                it.name == "getWitness"
            },
        )
    }

    @Test
    fun staleWitnessIsRejectedByExactGenerationCheck() {
        val fingerprint = "a".repeat(64)
        val manifest = manifest(fingerprint)
        val activation =
            KnowledgeCatalogActivationReceipt.create(
                generation = 41L,
                activatedAtEpochMillis = 900L,
                packId = manifest.packId,
                knowledgePackVersion = manifest.knowledgePackVersion,
                taxonomyVersion = manifest.taxonomyVersion,
                manifestFingerprint = fingerprint,
            )
        val staleWitness =
            ProductionKnowledgeActivationWitness.issue(
                activationGeneration = 40L,
                activatedAtEpochMillis = activation.activatedAtEpochMillis,
                packId = activation.packId,
                knowledgePackVersion = activation.knowledgePackVersion,
                taxonomyVersion = activation.taxonomyVersion,
                manifestFingerprint = activation.manifestFingerprint,
            )

        val failure =
            runCatching {
                requireSameProductionGeneration(manifest, activation, staleWitness)
            }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun manifest(fingerprint: String): KnowledgePackManifest =
        KnowledgePackManifest(
            packId = "production-runtime-contract",
            schemaVersion = 1,
            knowledgePackVersion = "content-v1",
            taxonomyVersion = "taxonomy-v1",
            searchIndexVersion = "search-v1",
            contentFingerprint = fingerprint,
            builtAtEpochMillis = 800L,
            nodeCount = 9,
            sourceCount = 9,
            relationCount = 0,
            materialCount = 0,
            searchFeatureCount = 9,
        )
}
