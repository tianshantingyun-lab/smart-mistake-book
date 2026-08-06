package com.tingyun.smartmistakebook.core.knowledge.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionHighSchoolKnowledgeRuntimeInstrumentedTest {
    @Test
    fun debugBoundaryFixtureCanNeverOpenTheProductionRuntime() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearKnowledgePack(context)
        try {
            val provision =
                HighSchoolKnowledgePackProvisioner.provisionApkBundledReviewedPack(
                    context = context,
                    pack = DebugBoundaryKnowledgePackFixture.reviewedPack(),
                )

            assertFalse(provision.productionCutoverEligible)
            assertNull(
                HighSchoolKnowledgeProductionCutover
                    .witnessReader(context)
                    .readFreshProductionActivation(),
            )
            assertNotNull(
                runCatching {
                    HighSchoolKnowledgeProductionRuntime.open(context).close()
                }.exceptionOrNull(),
            )
        } finally {
            clearKnowledgePack(context)
        }
    }

    private fun clearKnowledgePack(context: android.content.Context) {
        HighSchoolKnowledgePackBuilder.discardNext(context)
        context.deleteDatabase(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
        val databaseDirectory =
            requireNotNull(context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).parentFile)
        listOf(
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation",
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.pending",
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.rollback",
        ).forEach { name ->
            val file = java.io.File(databaseDirectory, name)
            if (file.exists()) {
                assertTrue(file.delete())
            }
        }
    }
}
