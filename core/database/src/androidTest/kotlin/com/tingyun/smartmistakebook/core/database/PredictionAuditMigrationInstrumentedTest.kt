package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.tingyun.smartmistakebook.core.database.port.StudentModelPredictionRecord

@RunWith(AndroidJUnit4::class)
class PredictionAuditMigrationInstrumentedTest {

    @Test
    fun versionThirtyTwoMigratesToPredictionAuditAndRoundTripsRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "prediction-audit-v32-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 32)
            val store = StudyDatabaseFactory.open(context, databaseName)

            assertEquals(STUDY_DATABASE_VERSION, 34)

            store.recordStudentModelPredictions(
                listOf(
                    StudentModelPredictionRecord(
                        predictionId = "pred-audit-1",
                        modelId = "hlr-shadow-v1",
                        modelVersion = "0.1.0-experimental",
                        algorithmHash = "hlr-recall-v1",
                        practiceUnitId = "practice-audit",
                        knowledgeNodeId = "node-audit",
                        featureFingerprint = "fp-audit",
                        predictedScore = 0.8,
                        conservativeScore = 0.72,
                        predictionWindowStartEpochMillis = 1_000,
                        predictionWindowEndEpochMillis = 2_000,
                        predictedAtEpochMillis = 1_000,
                    ),
                ),
            )

            // Outcome backfill resolves every pending prediction in the window.
            assertEquals(
                1,
                store.resolveStudentModelPredictions(
                    practiceUnitId = "practice-audit",
                    wasIndependentCorrect = true,
                    observedAtEpochMillis = 1_500,
                    responseLatencyMs = null,
                    hintCount = 0,
                ),
            )
            // A second resolve finds nothing pending.
            assertEquals(
                0,
                store.resolveStudentModelPredictions(
                    practiceUnitId = "practice-audit",
                    wasIndependentCorrect = false,
                    observedAtEpochMillis = 1_600,
                    responseLatencyMs = null,
                    hintCount = 0,
                ),
            )

            val resolved = store.readResolvedStudentModelPredictions(
                modelId = "hlr-shadow-v1",
                modelVersion = "0.1.0-experimental",
            )
            assertEquals(1, resolved.size)
            val row = resolved.single()
            assertEquals("pred-audit-1", row.predictionId)
            assertEquals("hlr-shadow-v1", row.modelId)
            assertEquals("0.1.0-experimental", row.modelVersion)
            assertEquals("hlr-recall-v1", row.algorithmHash)
            assertEquals(0.8, row.predictedScore, 0.0)
            assertEquals(0.72, row.conservativeScore, 0.0)
            assertTrue(row.wasIndependentCorrect)
            assertEquals(1_500, row.observedAtEpochMillis)

            store.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migratedDatabaseMatchesExportedSchemaThirtyThreeSqliteMaster() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val referenceName = "prediction-audit-ref-${System.nanoTime()}.db"
        val migratedName = "prediction-audit-mig-${System.nanoTime()}.db"
        context.deleteDatabase(referenceName)
        context.deleteDatabase(migratedName)
        try {
            // Reference: built purely from the exported 33.json createSql.
            createDatabaseFromExportedSchema(context, referenceName, version = 33)
            // Candidate: 32.json schema pushed through the real migration chain.
            createDatabaseFromExportedSchema(context, migratedName, version = 32)
            runBlocking {
                val migrated = StudyDatabaseFactory.open(context, migratedName)
                migrated.close()
            }
            assertEquals(
                readSqliteMaster(context.getDatabasePath(referenceName)),
                readSqliteMaster(context.getDatabasePath(migratedName)),
            )
        } finally {
            context.deleteDatabase(referenceName)
            context.deleteDatabase(migratedName)
        }
    }

    private fun readSqliteMaster(path: File): List<List<String?>> {
        val database = SQLiteDatabase.openDatabase(
            path.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            val cursor = database.rawQuery(
                "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name",
                null,
            )
            val rows = mutableListOf<List<String?>>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    rows += listOf(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                    )
                }
            }
            return rows
        } finally {
            database.close()
        }
    }
}
