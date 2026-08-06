package com.tingyun.smartmistakebook.core.mastery.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerMasteryCutoverControlInstrumentedTest {
    @Test
    fun ownerCapabilitiesRejectAnyNonLocalLearnerBeforeOpeningTheDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertTrue(
            runCatching {
                LearnerMasteryCutoverControlPortFactory.openForTest(
                    context = context,
                    databaseName = "must-not-open.mastery-test.db",
                    learnerId = "learner:other-profile",
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                LearnerMasteryLegacyMigrationPortFactory.openForTest(
                    context = context,
                    databaseName = "must-not-open.mastery-test.db",
                    learnerId = "learner:other-profile",
                )
            }.isFailure,
        )
    }

    @Test
    fun rawLegacyLedgerIsReplaySafeUnprojectedAndBoundToTheTerminalReceipt() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName = "mastery-cutover-${System.nanoTime()}.mastery-test.db"
            val databasePath = context.getDatabasePath(databaseName)
            context.deleteDatabase(databaseName)

            val database =
                LearnerMasteryStoreFactory.openDatabaseForTest(
                    context = context,
                    databaseName = databaseName,
                )
            val migrationPort =
                RoomLearnerMasteryLegacyMigrationPort(
                    database = database,
                    learnerId = LEARNER_ID,
                )
            val controlPort =
                RoomLearnerMasteryCutoverControlPort(
                    database = database,
                    learnerId = LEARNER_ID,
                )
            val firstSnapshots =
                listOf(
                    legacySnapshot("one", 10),
                    legacySnapshot("two", 20),
                )
            val firstPage =
                legacyPage(
                    batchSequence = 1,
                    afterExclusive = null,
                    snapshots = firstSnapshots,
                    finalBatch = false,
                )
            val terminalSnapshot = legacySnapshot("three", 30)
            val terminalPage =
                legacyPage(
                    batchSequence = 2,
                    afterExclusive = firstPage.terminalCursor,
                    snapshots = listOf(terminalSnapshot),
                    finalBatch = true,
                )
            val fence =
                LearnerMasteryAuthorityCutoverFence.create(
                    cutoverGeneration = 11,
                    studentImportEvidenceFingerprint = "a".repeat(64),
                    masteryImportEvidenceFingerprint = SOURCE_GENERATION,
                )

            try {
                assertNull(controlPort.recomputeCompletedMigrationLedger(SOURCE_GENERATION))
                assertEquals(
                    LearnerMasteryLegacySnapshotPageDisposition.IMPORTED,
                    migrationPort.applyPage(firstPage).disposition,
                )
                assertEquals(
                    LearnerMasteryLegacySnapshotPageDisposition.DUPLICATE,
                    migrationPort.applyPage(firstPage).disposition,
                )
                assertNull(controlPort.recomputeCompletedMigrationLedger(SOURCE_GENERATION))
                assertEquals(
                    LearnerMasteryLegacySnapshotPageDisposition.IMPORTED,
                    migrationPort.applyPage(terminalPage).disposition,
                )
                assertEquals(
                    LearnerMasteryLegacySnapshotPageDisposition.DUPLICATE,
                    migrationPort.applyPage(terminalPage).disposition,
                )
                assertEquals(
                    LearnerMasteryLegacySnapshotPageDisposition.OUT_OF_ORDER,
                    migrationPort.applyPage(
                        legacyPage(
                            batchSequence = 3,
                            afterExclusive = terminalPage.terminalCursor,
                            snapshots = listOf(legacySnapshot("late", 40)),
                            finalBatch = true,
                        ),
                    ).disposition,
                )

                val ledger =
                    checkNotNull(
                        controlPort.recomputeCompletedMigrationLedger(SOURCE_GENERATION),
                    )
                assertEquals(RAW_SNAPSHOT_DESTINATION_LEDGER_VERSION, ledger.destinationLedgerVersion)
                assertEquals(3L, ledger.migratedObservationCount)
                assertEquals(3L, ledger.rawSnapshotCount)
                assertEquals(2L, ledger.terminalBatchSequence)
                assertEquals(2, ledger.batchReceiptCount)
                assertEquals(terminalPage.canonicalFingerprint, ledger.terminalBatchFingerprint)
                assertEquals(
                    terminalPage.sourcePageCanonicalFingerprint,
                    ledger.terminalSourcePageCanonicalFingerprint,
                )
                assertEquals(terminalSnapshot.cursor, ledger.terminalCursor)
                assertEquals(
                    ledger,
                    controlPort.recomputeCompletedMigrationLedger(SOURCE_GENERATION),
                )

                assertEquals(fence, controlPort.appendCutoverFenceIfAbsent(fence))
                val wrongLedger =
                    ledger.copy(destinationCanonicalFingerprint = "f".repeat(64))
                assertTrue(
                    runCatching {
                        controlPort.appendCompletionReceiptIfAbsent(
                            LearnerMasteryAuthorityCutoverCompletionReceipt.create(
                                fence,
                                wrongLedger,
                            ),
                        )
                    }.isFailure,
                )
                assertNull(controlPort.readCompletionReceipt())

                val receipt =
                    LearnerMasteryAuthorityCutoverCompletionReceipt.create(fence, ledger)
                assertEquals(
                    receipt,
                    controlPort.appendCompletionReceiptIfAbsent(receipt),
                )
                assertEquals(
                    receipt,
                    controlPort.appendCompletionReceiptIfAbsent(receipt),
                )
                assertEquals(receipt, controlPort.readCompletionReceipt())
            } finally {
                controlPort.close()
            }

            val originalSnapshotEnvelope =
                SQLiteDatabase.openDatabase(
                    databasePath.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    sqlite.rawQuery(
                        """
                        SELECT snapshot_canonical_fingerprint, nonce, ciphertext
                        FROM mastery_legacy_observation_snapshot
                        WHERE source_fact_id = 'legacy-fact:one'
                        """.trimIndent(),
                        emptyArray<String>(),
                    ).use { cursor ->
                        check(cursor.moveToFirst())
                        Triple(cursor.getString(0), cursor.getBlob(1), cursor.getBlob(2))
                    }
                }
            val originalSnapshotFingerprint = originalSnapshotEnvelope.first

            SQLiteDatabase.openDatabase(
                databasePath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                assertEquals(
                    2L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM mastery_legacy_observation_snapshot_page",
                    ),
                )
                assertEquals(
                    3L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM mastery_legacy_observation_snapshot",
                    ),
                )
                listOf(
                    "mastery_source_fact",
                    "mastery_observation_candidate",
                    "mastery_learning_event",
                    "mastery_knowledge_projection",
                    "mastery_legacy_fact_migration_destination_record",
                ).forEach { table ->
                    assertEquals(
                        "Raw audit snapshots must not be promoted into $table",
                        0L,
                        sqlite.longForQuery("SELECT COUNT(*) FROM $table"),
                    )
                }
                assertSqlFails(
                    sqlite,
                    """
                    UPDATE mastery_legacy_observation_snapshot
                    SET ciphertext = zeroblob(length(ciphertext))
                    WHERE source_fact_id = 'legacy-fact:one'
                    """.trimIndent(),
                )
                assertSqlFails(
                    sqlite,
                    """
                    DELETE FROM mastery_legacy_observation_snapshot
                    WHERE source_fact_id = 'legacy-fact:one'
                    """.trimIndent(),
                )
                assertSqlFails(
                    sqlite,
                    """
                    UPDATE mastery_legacy_observation_snapshot_page
                    SET snapshot_count = 99
                    WHERE batch_sequence = 1
                    """.trimIndent(),
                )
                assertSqlFails(
                    sqlite,
                    """
                    DELETE FROM mastery_legacy_observation_snapshot_page
                    WHERE batch_sequence = 1
                    """.trimIndent(),
                )
            }

            val sealedLedgerCanonicalDigest =
                LearnerMasteryCutoverControlPortFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    learnerId = LEARNER_ID,
                ).use { reopened ->
                    val ledger =
                        checkNotNull(
                            reopened.recomputeCompletedMigrationLedger(SOURCE_GENERATION),
                        )
                    assertEquals(3L, ledger.rawSnapshotCount)
                    assertEquals(
                        ledger.destinationCanonicalFingerprint,
                        checkNotNull(reopened.readCompletionReceipt())
                            .migrationLedgerCanonicalDigest,
                    )
                    ledger.destinationCanonicalFingerprint
                }

            SQLiteDatabase.openDatabase(
                databasePath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                sqlite.execSQL(
                    "DROP TRIGGER immutable_mastery_legacy_observation_snapshot_update",
                )
                sqlite.execSQL(
                    """
                    UPDATE mastery_legacy_observation_snapshot
                    SET ciphertext = zeroblob(length(ciphertext))
                    WHERE source_fact_id = 'legacy-fact:one'
                    """.trimIndent(),
                )
            }
            assertSealedLedgerRejectsTampering(
                context,
                databaseName,
                sealedLedgerCanonicalDigest,
            )

            SQLiteDatabase.openDatabase(
                databasePath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                sqlite.execSQL(
                    "DROP TRIGGER immutable_mastery_legacy_observation_snapshot_update",
                )
                sqlite.execSQL(
                    """
                    UPDATE mastery_legacy_observation_snapshot
                    SET nonce = ?, ciphertext = ?,
                        snapshot_canonical_fingerprint = '${"0".repeat(64)}'
                    WHERE source_fact_id = 'legacy-fact:one'
                    """.trimIndent(),
                    arrayOf<Any>(originalSnapshotEnvelope.second, originalSnapshotEnvelope.third),
                )
            }
            assertSealedLedgerRejectsTampering(
                context,
                databaseName,
                sealedLedgerCanonicalDigest,
            )

            SQLiteDatabase.openDatabase(
                databasePath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                sqlite.execSQL(
                    "DROP TRIGGER immutable_mastery_legacy_observation_snapshot_update",
                )
                sqlite.execSQL(
                    """
                    UPDATE mastery_legacy_observation_snapshot
                    SET snapshot_canonical_fingerprint = '$originalSnapshotFingerprint'
                    WHERE source_fact_id = 'legacy-fact:one'
                    """.trimIndent(),
                )
                sqlite.execSQL(
                    "DROP TRIGGER immutable_mastery_legacy_observation_snapshot_page_update",
                )
                sqlite.execSQL(
                    """
                    UPDATE mastery_legacy_observation_snapshot_page
                    SET snapshot_count = 99
                    WHERE batch_sequence = 1
                    """.trimIndent(),
                )
            }
            assertSealedLedgerRejectsTampering(
                context,
                databaseName,
                sealedLedgerCanonicalDigest,
            )

            SQLiteDatabase.openDatabase(
                databasePath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                sqlite.execSQL(
                    "DROP TRIGGER immutable_mastery_legacy_observation_snapshot_update",
                )
                sqlite.execSQL(
                    "DROP TRIGGER immutable_mastery_legacy_observation_snapshot_page_update",
                )
                sqlite.execSQL(
                    """
                    UPDATE mastery_legacy_observation_snapshot_page
                    SET snapshot_count = 2
                    WHERE batch_sequence = 1
                    """.trimIndent(),
                )
                sqlite.execSQL("PRAGMA foreign_keys = OFF")
                sqlite.execSQL(
                    """
                    UPDATE mastery_legacy_observation_snapshot
                    SET batch_sequence = 99
                    WHERE batch_sequence = 1
                    """.trimIndent(),
                )
                sqlite.execSQL(
                    """
                    UPDATE mastery_legacy_observation_snapshot_page
                    SET batch_sequence = 99
                    WHERE batch_sequence = 1
                    """.trimIndent(),
                )
            }
            assertSealedLedgerRejectsTampering(
                context,
                databaseName,
                sealedLedgerCanonicalDigest,
            )

            context.deleteDatabase(databaseName)
            Unit
        }
}

private fun legacyPage(
    batchSequence: Long,
    afterExclusive: LearnerMasteryLegacyMigrationCursor?,
    snapshots: List<LearnerMasteryLegacyObservationSnapshot>,
    finalBatch: Boolean,
): LearnerMasteryLegacySnapshotPage =
    LearnerMasteryLegacySnapshotPage(
        learnerId = LEARNER_ID,
        sourceGeneration = SOURCE_GENERATION,
        batchSequence = batchSequence,
        sourcePageCanonicalFingerprint =
            recomputeSourcePageFingerprint(
                learnerId = LEARNER_ID,
                afterExclusive = afterExclusive,
                snapshots = snapshots,
            ),
        afterExclusive = afterExclusive,
        snapshots = snapshots,
        finalBatch = finalBatch,
    )

private fun legacySnapshot(
    id: String,
    occurredAtEpochMillis: Long,
): LearnerMasteryLegacyObservationSnapshot {
    val sourceFactId = "legacy-fact:$id"
    val anchorId = "legacy-anchor:$id"
    val responseFingerprint = testFingerprint("response:$id")
    val payloadFingerprint = testFingerprint("payload:$id")
    val responseSummary = "legacy response $id"
    val sourceVersion = "legacy-observation-v1"
    val recordFingerprint =
        CanonicalSha256("exact-legacy-mastery-fact-record-v1")
            .field("sourceFactId", sourceFactId)
            .field("learnerScopeId", LEARNER_ID)
            .field("source", "TUTOR_SPECIFIC_STUCK")
            .field("factKind", "SPECIFIC_STUCK")
            .field("anchorId", anchorId)
            .field("subject", "PHYSICS")
            .nullableField("conversationGeneration", null)
            .nullableField("conversationId", null)
            .nullableField("turnReceiptId", null)
            .nullableField("evidenceRequestId", null)
            .field("responseFingerprint", responseFingerprint)
            .field("responseSummary", responseSummary)
            .field("occurredAtEpochMillis", occurredAtEpochMillis)
            .field("sourceVersion", sourceVersion)
            .field("sourcePayloadCanonicalFingerprint", payloadFingerprint)
            .nullableField("sourceProofCanonicalFingerprint", null)
            .nullableField("sourceReferenceId", null)
            .nullableField("targetKind", null)
            .nullableField("targetDatabase", null)
            .nullableField("targetId", null)
            .nullableField("targetVersion", null)
            .nullableField("targetCanonicalFingerprint", null)
            .nullableField("attestedAtEpochMillis", null)
            .finish()
    return LearnerMasteryLegacyObservationSnapshot(
        sourceFactId = sourceFactId,
        learnerId = LEARNER_ID,
        source = "TUTOR_SPECIFIC_STUCK",
        factKind = "SPECIFIC_STUCK",
        anchorId = anchorId,
        subject = "PHYSICS",
        conversationGeneration = null,
        conversationId = null,
        turnReceiptId = null,
        evidenceRequestId = null,
        responseFingerprint = responseFingerprint,
        responseSummary = responseSummary,
        occurredAtEpochMillis = occurredAtEpochMillis,
        sourceVersion = sourceVersion,
        sourcePayloadCanonicalFingerprint = payloadFingerprint,
        proofPresent = false,
        sourceProofCanonicalFingerprint = null,
        sourceReferenceId = null,
        targetKind = null,
        targetDatabase = null,
        targetId = null,
        targetVersion = null,
        targetCanonicalFingerprint = null,
        attestedAtEpochMillis = null,
        sourceRecordCanonicalFingerprint = recordFingerprint,
    )
}

private fun testFingerprint(value: String): String =
    CanonicalSha256("learner-mastery-cutover-test-v2")
        .field("value", value)
        .finish()

private fun SQLiteDatabase.longForQuery(sql: String): Long =
    rawQuery(sql, emptyArray<String>()).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

private fun SQLiteDatabase.stringForQuery(sql: String): String =
    rawQuery(sql, emptyArray<String>()).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

private suspend fun assertSealedLedgerRejectsTampering(
    context: android.content.Context,
    databaseName: String,
    sealedLedgerCanonicalDigest: String,
) {
    LearnerMasteryCutoverControlPortFactory.openForTest(
        context = context,
        databaseName = databaseName,
        learnerId = LEARNER_ID,
    ).use { reopened ->
        val recomputed =
            runCatching {
                checkNotNull(
                    reopened.recomputeCompletedMigrationLedger(SOURCE_GENERATION),
                )
            }
        recomputed.getOrNull()?.let { changed ->
            assertTrue(
                changed.destinationCanonicalFingerprint != sealedLedgerCanonicalDigest,
            )
        }
        assertTrue(runCatching { reopened.readCompletionReceipt() }.isFailure)
    }
}

private fun assertSqlFails(
    database: SQLiteDatabase,
    sql: String,
) {
    assertTrue(runCatching { database.execSQL(sql) }.isFailure)
}

private const val LEARNER_ID = LOCAL_LEARNER_ID
private val SOURCE_GENERATION = testFingerprint("legacy-source-generation")
