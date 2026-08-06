package com.tingyun.smartmistakebook.core.data.authority

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.knowledge.database.DebugBoundaryKnowledgePackFixture
import com.tingyun.smartmistakebook.core.knowledge.database.HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgePackProvisioner
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class KnowledgeDatabaseActivationWitnessReaderInstrumentedTest {
    @Test
    fun debugBoundaryFixtureNeverProducesAProductionWitness() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearKnowledgeAuthority(context)
        try {
            val reader = KnowledgeDatabaseActivationWitnessReader(context)
            assertNull(reader.readCurrentActivationWitness())

            val provision =
                HighSchoolKnowledgePackProvisioner.provisionApkBundledReviewedPack(
                    context = context,
                    pack = DebugBoundaryKnowledgePackFixture.reviewedPack(),
                )

            assertFalse(provision.productionCutoverEligible)
            assertNull(reader.readCurrentActivationWitness())
        } finally {
            clearKnowledgeAuthority(context)
        }
    }

    private fun clearKnowledgeAuthority(context: Context) {
        listOf(
            HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME,
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.next",
        ).forEach(context::deleteDatabase)
        val directory =
            requireNotNull(context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).parentFile)
        listOf(
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation",
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.pending",
        ).forEach { name -> File(directory, name).delete() }
    }
}
