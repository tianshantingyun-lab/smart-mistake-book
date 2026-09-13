package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
     * Audit §1.4 (D-2/S-3): the pseudo fallback is only legitimate for a
     * question that carries no accepted binding.
     *
     * Three practice units pin the predicate from three sides, and each uses a
     * subject no other state touches, so the pseudo node each one ends up with
     * can only have come from its own call:
     *
     *  - unclassified (PHYSICS)  → fallback written, node materialized;
     *  - classified (MATH)       → fallback refused, node STILL materialized;
     *  - classified + legacy pseudo (CHEMISTRY, the realistic migration order:
     *    the planner wrote a pseudo binding first, a real classification
     *    arrived later) → refused, and no second pseudo row appears.
     */
    @Test
    fun pseudoBindingIsWrittenOnlyForQuestionsWithNoAcceptedBinding() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val port = StudyDatabaseFactory.openInMemory(context)
        try {
            val bound = seedChain("bound", "MATH")
            val mixed = seedChain("mixed", "CHEMISTRY")
            val unbound = seedChain("unbound", "PHYSICS")
            port.seedFixture(
                StudySeedBundle(
                    problems = bound.problems + mixed.problems + unbound.problems,
                    revisions = bound.revisions + mixed.revisions + unbound.revisions,
                    practiceUnits = bound.practiceUnits + mixed.practiceUnits + unbound.practiceUnits,
                    errorBookEntries = bound.errorBookEntries +
                        mixed.errorBookEntries +
                        unbound.errorBookEntries,
                    knowledgeNodes = listOf(
                        KnowledgeNodeSeedRecord(
                            knowledgeNodeId = "kc-real-math",
                            stableCode = "kc-real-math",
                            subject = "MATH",
                            displayName = "已归类知识点",
                            parentKnowledgeNodeId = null,
                            taxonomyVersion = "math-graph-v3",
                            createdAtEpochMillis = 500L,
                            verificationStatus = "USER_CONFIRMED",
                        ),
                        KnowledgeNodeSeedRecord(
                            knowledgeNodeId = "kc-real-chem",
                            stableCode = "kc-real-chem",
                            subject = "CHEMISTRY",
                            displayName = "已归类知识点",
                            parentKnowledgeNodeId = null,
                            taxonomyVersion = "chem-graph-v1",
                            createdAtEpochMillis = 500L,
                            verificationStatus = "USER_CONFIRMED",
                        ),
                        // Pre-existing because the legacy pseudo binding below
                        // foreign-keys onto it.
                        KnowledgeNodeSeedRecord(
                            knowledgeNodeId = "pseudo:CHEMISTRY",
                            stableCode = "pseudo:CHEMISTRY",
                            subject = "CHEMISTRY",
                            displayName = PSEUDO_NODE_DISPLAY_NAME,
                            parentKnowledgeNodeId = null,
                            taxonomyVersion = PSEUDO_TAXONOMY_VERSION,
                            createdAtEpochMillis = 100L,
                        ),
                    ),
                    knowledgeBindings = listOf(
                        KnowledgeBindingSeedRecord(
                            bindingId = "binding-real-math",
                            practiceUnitId = "unit-bound",
                            knowledgeNodeId = "kc-real-math",
                            basisRevisionId = "revision-bound",
                            strength = 1.0,
                            sourceType = "USER_CORRECTED",
                            taxonomyVersion = "math-graph-v3",
                            acceptedAtEpochMillis = 500L,
                        ),
                        // Older than the real binding: the planner created it
                        // while the question was still unclassified.
                        KnowledgeBindingSeedRecord(
                            bindingId = "binding-pseudo-chem",
                            practiceUnitId = "unit-mixed",
                            knowledgeNodeId = "pseudo:CHEMISTRY",
                            basisRevisionId = "revision-mixed",
                            strength = 1.0,
                            sourceType = PSEUDO_BINDING_SOURCE_TYPE,
                            taxonomyVersion = "pseudo-plan-v1",
                            acceptedAtEpochMillis = 100L,
                        ),
                        KnowledgeBindingSeedRecord(
                            bindingId = "binding-real-chem",
                            practiceUnitId = "unit-mixed",
                            knowledgeNodeId = "kc-real-chem",
                            basisRevisionId = "revision-mixed",
                            strength = 1.0,
                            sourceType = "USER_CORRECTED",
                            taxonomyVersion = "chem-graph-v1",
                            acceptedAtEpochMillis = 900L,
                        ),
                    ),
                ),
            )

            // Classified question: the fallback is refused, so no pseudo row is
            // written and the self-report snapshot stays attribution-free. An
            // attribution-free snapshot is a state the contract already
            // recognises (LocalReviewSelfReportContract).
            assertNull(
                port.ensurePseudoKnowledgeBinding(
                    practiceUnitId = "unit-bound",
                    problemRevisionId = "revision-bound",
                    taxonomyVersion = "local-review-self-report-v1",
                    subject = "MATH",
                    acceptedAtEpochMillis = 1_000L,
                ),
            )
            assertEquals(
                listOf("binding-real-math"),
                port.readPracticeUnitKnowledgeBindings("unit-bound").map { it.bindingId },
            )
            // Refusing the binding must not refuse the node: the planner names
            // `pseudo:MATH` whenever this question's catalog projection is empty
            // (the projection drops a binding once it stops matching the latest
            // organization receipt and its KNOWLEDGE classification), which can
            // happen while binding rows exist, and review_queue_knowledge_node
            // foreign-keys onto it. Nothing else in this fixture touches MATH.
            assertTrue(
                "the refused call must still materialize pseudo:MATH",
                port.readKnowledgeNodesByIds(setOf("pseudo:MATH"))
                    .any { it.knowledgeNodeId == "pseudo:MATH" },
            )

            // Classified, but carrying a legacy pseudo row: still refused, and
            // exactly the two pre-existing rows survive. A predicate that asked
            // whether *all* bindings are pseudo, rather than whether any is a
            // real one, would append a third row here.
            assertNull(
                port.ensurePseudoKnowledgeBinding(
                    practiceUnitId = "unit-mixed",
                    problemRevisionId = "revision-mixed",
                    taxonomyVersion = "local-review-self-report-v1",
                    subject = "CHEMISTRY",
                    acceptedAtEpochMillis = 1_100L,
                ),
            )
            assertEquals(
                listOf("binding-pseudo-chem", "binding-real-chem"),
                port.readPracticeUnitKnowledgeBindings("unit-mixed").map { it.bindingId },
            )

            // Unclassified question: unchanged, the fallback is still written.
            val fallback = port.ensurePseudoKnowledgeBinding(
                practiceUnitId = "unit-unbound",
                problemRevisionId = "revision-unbound",
                taxonomyVersion = "local-review-self-report-v1",
                subject = "PHYSICS",
                acceptedAtEpochMillis = 1_000L,
            )
            assertNotNull(fallback)
            assertEquals("pseudo:PHYSICS", fallback!!.knowledgeNodeId)
            assertEquals(
                listOf(
                    "pseudo-binding:unit-unbound:revision-unbound:" +
                        "local-review-self-report-v1:pseudo:PHYSICS",
                ),
                port.readPracticeUnitKnowledgeBindings("unit-unbound").map { it.bindingId },
            )
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

    /**
     * A minimal problem -> revision -> practice_unit -> entry chain, so a
     * binding has rows to foreign-key onto. `suffix` is what keeps several
     * chains in one fixture set distinct; `subject` is what keeps their pseudo
     * knowledge nodes distinct.
     */
    private fun seedChain(suffix: String, subject: String): StudySeedBundle = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(
                problemId = "problem-$suffix",
                canonicalFingerprint = "fp-$suffix",
                subject = subject,
                createdAtEpochMillis = 0L,
            ),
        ),
        revisions = listOf(
            ProblemRevisionSeedRecord(
                revisionId = "revision-$suffix",
                problemId = "problem-$suffix",
                revisionNumber = 1,
                title = "伪KC边界题",
                problemMarkdown = "求证。",
                answerSpecId = null,
                answerSpecSnapshot = null,
                answerVerificationStatus = "USER_ASSERTED",
                sourceType = "CAPTURE",
                sourceReference = null,
                contentFingerprint = "fp-$suffix-r1",
                createdAtEpochMillis = 0L,
            ),
        ),
        practiceUnits = listOf(
            PracticeUnitSeedRecord(
                practiceUnitId = "unit-$suffix",
                problemId = "problem-$suffix",
                problemRevisionId = "revision-$suffix",
                unitKey = "unit-$suffix",
                unitKind = "SINGLE",
                title = "伪KC边界单元",
                promptMarkdown = "求证。",
                estimatedSeconds = 60,
                createdAtEpochMillis = 0L,
            ),
        ),
        errorBookEntries = listOf(
            ErrorBookEntrySeedRecord(
                entryId = "entry-$suffix",
                practiceUnitId = "unit-$suffix",
                problemId = "problem-$suffix",
                currentRevisionId = "revision-$suffix",
                sourceKey = "capture:$suffix",
                status = "ACTIVE",
                acceptedAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
            ),
        ),
    )

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
