package com.tingyun.smartmistakebook.core.knowledge.database

import android.content.Context
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionKnowledgeActivationWitnessContractTest {
    @Test
    fun witnessIsOpaqueAndExposesOnlyVerifiedIdentityFields() {
        assertTrue(
            ProductionKnowledgeActivationWitness::class.java.constructors.none { constructor ->
                !constructor.isSynthetic
            },
        )
        assertTrue(
            ProductionKnowledgeActivationWitness::class.java.declaredMethods.none { method ->
                method.name == "copy"
            },
        )
        assertEquals(
            setOf(
                "getActivatedAtEpochMillis",
                "getActivationGeneration",
                "getKnowledgePackVersion",
                "getManifestFingerprint",
                "getPackId",
                "getTaxonomyVersion",
            ),
            ProductionKnowledgeActivationWitness::class.java.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) }
                .filterNot { method -> method.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
    }

    @Test
    fun publicCapabilityHasNoDatabaseDaoSqlOrMutationSurface() {
        val factory =
            HighSchoolKnowledgeProductionCutover::class.java.declaredMethods.single { method ->
                method.name == "witnessReader"
            }
        assertEquals(listOf(Context::class.java), factory.parameterTypes.toList())
        assertEquals(
            ProductionKnowledgeActivationWitnessReader::class.java,
            factory.returnType,
        )

        val publicMethods =
            ProductionKnowledgeActivationWitnessReader::class.java.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) }
        assertEquals(1, publicMethods.size)
        assertTrue(publicMethods.single().name.startsWith("readFreshProductionActivation"))
        val forbiddenTerms = listOf("database", "dao", "sql", "write", "install", "activate")
        assertTrue(
            (publicMethods + factory).none { method ->
                forbiddenTerms.any { term -> method.name.contains(term, ignoreCase = true) }
            },
        )
        assertTrue(
            (publicMethods + factory).flatMap { method ->
                method.parameterTypes.toList() + method.returnType
            }.none { type ->
                type.name.contains("room", ignoreCase = true) ||
                    type.name.contains("sqlite", ignoreCase = true) ||
                    type.simpleName.contains("Dao")
            },
        )
    }

    @Test
    fun historicalSampleIsNeverAProductionCutoverActivation() {
        assertFalse(
            isRegisteredProductionCutoverActivation(
                packId = HistoricalSampleKnowledgePackAuthorization.PACK_ID,
                knowledgePackVersion =
                    HistoricalSampleKnowledgePackAuthorization.KNOWLEDGE_PACK_VERSION,
                taxonomyVersion = HistoricalSampleKnowledgePackAuthorization.TAXONOMY_VERSION,
                searchIndexVersion =
                    HistoricalSampleKnowledgePackAuthorization.SEARCH_INDEX_VERSION,
                generation = HistoricalSampleKnowledgePackAuthorization.GENERATION,
                contentFingerprint =
                    HistoricalSampleKnowledgePackAuthorization.EXPECTED_CONTENT_FINGERPRINT,
            ),
        )
    }
}
