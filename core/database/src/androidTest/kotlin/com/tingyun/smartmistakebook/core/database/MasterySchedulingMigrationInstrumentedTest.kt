package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level verification of the mastery-scheduling v35 -> v36 migration
 * (spec mastery-scheduling §3) against real seeded rows: the difficulty
 * domain conversion, the new attempt/review-log columns, and the recreated
 * library_catalog view; plus the pseudo-KC fallback binding path (§3.4)
 * through the real Room port with foreign keys enforced.
 */
@RunWith(AndroidJUnit4::class)
class MasterySchedulingMigrationInstrumentedTest {

    @Test
    fun v35ToV36ConvertsDifficultyAndKeepsRowsReadable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "mastery-migration-v35-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 35)
            seedV35MemoryRow(context, databaseName)

            val port = StudyDatabaseFactory.open(context, databaseName)
            try {
                // The recreated view still answers the catalog count query.
                assertEquals(0, port.libraryCatalogCount("", null, null, null, null))
            } finally {
                port.close()
            }

            // Difficulty was stored on the 0..1 domain at v35; the migration
            // maps it one-to-one onto 1..10 (D = 1 + 9·d) in place.
            val migrated = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
                context.getDatabasePath(databaseName),
                null,
            )
            try {
                migrated.rawQuery(
                    "SELECT difficulty, consecutive_cross_day_success, " +
                        "last_evidence_reason FROM learner_problem_memory_state " +
                        "WHERE practice_unit_id = ?",
                    arrayOf("unit-seed"),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(5.5, cursor.getDouble(0), 1e-9)
                    assertEquals(0, cursor.getInt(1))
                    assertTrue(cursor.isNull(2))
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun pseudoKnowledgeBindingSatisfiesAttributionForeignKeysOnDevice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val port = StudyDatabaseFactory.openInMemory(context)
        try {
            seedContentChain(port)
            val binding = port.ensurePseudoKnowledgeBinding(
                practiceUnitId = "unit-pseudo-test",
                problemRevisionId = "revision-pseudo-test",
                taxonomyVersion = "local-review-self-report-v1",
                subject = "MATH",
                acceptedAtEpochMillis = 1_000L,
            )
            assertNotNull(binding)
            binding!!
            assertEquals("pseudo:MATH", binding.knowledgeNodeId)
            assertEquals("revision-pseudo-test", binding.basisRevisionId)

            // The second call is idempotent and returns the same row.
            val replay = port.ensurePseudoKnowledgeBinding(
                practiceUnitId = "unit-pseudo-test",
                problemRevisionId = "revision-pseudo-test",
                taxonomyVersion = "local-review-self-report-v1",
                subject = "MATH",
                acceptedAtEpochMillis = 2_000L,
            )
            assertNotNull(replay)
            assertEquals(binding.bindingId, replay!!.bindingId)

            // Review-log collection round-trips through the real table.
            port.recordReviewLogEntries(
                listOf(
                    ReviewLogEntry(
                        learnerId = "learner:local",
                        practiceUnitId = "unit-pseudo-test",
                        rating = 3,
                        deltaTDays = 2.0,
                        durationMs = 45_000,
                        reviewedAtEpochMillis = 10_000,
                        sourceKind = "SELF_REPORT",
                        sourceId = "attempt-device-1",
                        evidenceWeight = 0.8,
                        schedulingEligible = true,
                        timeBucket = "MORNING",
                        recordedAtEpochMillis = 11_000,
                    ),
                ),
            )
            val samples = port.readReviewLogSamples("learner:local", limit = 10)
            assertEquals(1, samples.size)
            assertEquals(3, samples.single().rating)
            assertEquals("MORNING", samples.single().timeBucket)
            assertTrue(port.readReviewLogSamples("learner:other", limit = 10).isEmpty())
        } finally {
            port.close()
        }
    }

    /**
     * The pseudo binding foreign-keys onto practice_unit, which chains up to
     * problem_revision and problem; seed the minimal content chain first.
     */
    @Test
    fun v33ChainStructurallyMatchesExportedCurrentSchema() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val referenceName = "diagnose-ref-${System.nanoTime()}.db"
        val migratedName = "diagnose-mig-${System.nanoTime()}.db"
        context.deleteDatabase(referenceName)
        context.deleteDatabase(migratedName)
        try {
            createDatabaseFromExportedSchema(context, referenceName, version = STUDY_DATABASE_VERSION)
            createDatabaseFromExportedSchema(context, migratedName, version = 33)
            // Room migrates lazily: touch the database so the chain runs.
            StudyDatabaseFactory.open(context, migratedName).use { port ->
                port.readDatabaseVersion()
            }
            // ALTER TABLE rewrites the stored CREATE text and Room adds
            // runtime FTS triggers on open, so compare structure (objects +
            // column layout), which is the migration contract Room itself
            // validates.
            assertStructurallyEqual(
                context.getDatabasePath(referenceName),
                context.getDatabasePath(migratedName),
            )
        } finally {
            context.deleteDatabase(referenceName)
            context.deleteDatabase(migratedName)
        }
    }

    private suspend fun seedContentChain(port: StudyDatabasePort) {
        port.seedFixture(
            StudySeedBundle(
                problems = listOf(
                    ProblemSeedRecord(
                        problemId = "unit-pseudo-test-problem",
                        canonicalFingerprint = "fp-pseudo",
                        subject = "MATH",
                        createdAtEpochMillis = 0L,
                    ),
                ),
                revisions = listOf(
                    ProblemRevisionSeedRecord(
                        revisionId = "revision-pseudo-test",
                        problemId = "unit-pseudo-test-problem",
                        revisionNumber = 1,
                        title = "伪KC验证题",
                        problemMarkdown = "求证。",
                        answerSpecId = null,
                        answerSpecSnapshot = null,
                        answerVerificationStatus = "USER_ASSERTED",
                        sourceType = "CAPTURE",
                        sourceReference = null,
                        contentFingerprint = "fp-pseudo-r1",
                        createdAtEpochMillis = 0L,
                    ),
                ),
                practiceUnits = listOf(
                    PracticeUnitSeedRecord(
                        practiceUnitId = "unit-pseudo-test",
                        problemId = "unit-pseudo-test-problem",
                        problemRevisionId = "revision-pseudo-test",
                        unitKey = "unit-pseudo-test",
                        unitKind = "SINGLE",
                        title = "伪KC验证单元",
                        promptMarkdown = "求证。",
                        estimatedSeconds = 60,
                        createdAtEpochMillis = 0L,
                    ),
                ),
                errorBookEntries = listOf(
                    ErrorBookEntrySeedRecord(
                        entryId = "entry-pseudo-test",
                        practiceUnitId = "unit-pseudo-test",
                        problemId = "unit-pseudo-test-problem",
                        currentRevisionId = "revision-pseudo-test",
                        sourceKey = "capture:pseudo-test",
                        status = "ACTIVE",
                        acceptedAtEpochMillis = 0L,
                        updatedAtEpochMillis = 0L,
                    ),
                ),
            ),
        )
    }

    private fun seedV35MemoryRow(context: Context, databaseName: String) {
        val database = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(databaseName),
            null,
        )
        try {
            database.execSQL(
                """
                INSERT INTO learner_projection_snapshot (
                    projection_name, learner_id, state_version, checkpoint_sequence,
                    known_ledger_head_sequence, projector_version, projected_at_epoch_millis,
                    generated_at_epoch_millis, correction_watermark_epoch_millis,
                    freshness, projection_status
                ) VALUES (
                    'study-experience-v1', 'learner:local', 1, 1,
                    1, 'learning-core-v4', 100,
                    100, NULL,
                    'CURRENT', 'CURRENT'
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO learner_problem_memory_state (
                    projection_name, learner_id, practice_unit_id, stability_days,
                    difficulty, last_reviewed_at_epoch_millis, next_review_at_epoch_millis,
                    independent_correct_count, assisted_correct_count, lapse_count,
                    answer_reveal_count, last_lapse_at_epoch_millis, clock_anomaly_count,
                    last_clock_anomaly_at_epoch_millis, last_attempt_id,
                    projector_version, checkpoint_sequence
                ) VALUES (
                    'study-experience-v1', 'learner:local', 'unit-seed', 3.0,
                    0.5, 100, 200,
                    1, 0, 0,
                    0, NULL, 0,
                    NULL, 'attempt-seed',
                    'learning-core-v4', 1
                )
                """.trimIndent(),
            )
        } finally {
            database.close()
        }
    }
}
