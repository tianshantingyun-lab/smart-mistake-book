package com.tingyun.smartmistakebook.core.mastery.database

import android.database.sqlite.SQLiteDatabase
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerMasteryMigration16To17InstrumentedTest {
    @Test
    fun migrationEncryptsExactLegacyTextPreservesAttestationsAndScrubsDatabaseFiles() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "mastery-v16-v17-encryption-${System.nanoTime()}.mastery-test.db"
            val databaseFile = context.getDatabasePath(databaseName)
            context.deleteDatabase(databaseName)
            deleteProductionKey()
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = databaseFile,
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )
            val fixture = v17LegacyFixture(HIGH_ENTROPY_CANARY)
            try {
                helper.createDatabase(16).use { connection ->
                    connection.insertV16LegacyFixture(fixture)
                }
                val before = readAttestationSnapshot(databaseFile)

                helper.runMigrationsAndValidate(
                    version = 17,
                    migrations = listOf(LEARNER_MASTERY_MIGRATION_16_17),
                ).close()

                SQLiteDatabase.openDatabase(
                    databaseFile.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    assertEquals(
                        LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_PENDING,
                        sqlite.textForQuery(
                            "SELECT metadata_value FROM mastery_store_metadata " +
                                "WHERE metadata_key = " +
                                sqlLiteral(
                                    LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_METADATA_KEY,
                                ),
                        ),
                    )
                    assertFalse(sqlite.tableColumns().contains("response_summary"))
                    assertTrue(sqlite.tableColumns().containsAll(listOf("key_version", "nonce", "ciphertext")))
                    assertEquals(
                        LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_KEY_VERSION.toLong(),
                        sqlite.longForQuery(
                            "SELECT key_version FROM mastery_legacy_observation_snapshot",
                        ),
                    )
                    assertEquals(12L, sqlite.longForQuery("SELECT length(nonce) FROM mastery_legacy_observation_snapshot"))
                    assertTrue(
                        sqlite.longForQuery(
                            "SELECT length(ciphertext) FROM mastery_legacy_observation_snapshot",
                        ) > 16L,
                    )
                    assertNotEquals(
                        HIGH_ENTROPY_CANARY,
                        sqlite.textForQuery(
                            "SELECT CAST(ciphertext AS TEXT) FROM mastery_legacy_observation_snapshot",
                        ),
                    )
                }

                runLearnerMasteryResponseSummaryHygieneExclusive(context, databaseName)

                val after = readAttestationSnapshot(databaseFile)
                assertEquals(before, after)
                SQLiteDatabase.openDatabase(
                    databaseFile.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    assertEquals(
                        LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_COMPLETE,
                        sqlite.textForQuery(
                            "SELECT metadata_value FROM mastery_store_metadata " +
                                "WHERE metadata_key = " +
                                sqlLiteral(
                                    LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_METADATA_KEY,
                                ),
                        ),
                    )
                }
                assertCanaryAbsentFromDatabaseAndSidecars(databaseFile, HIGH_ENTROPY_CANARY)

                val control =
                    LearnerMasteryCutoverControlPortFactory.openForTest(
                        context = context,
                        databaseName = databaseName,
                        learnerId = LOCAL_LEARNER_ID,
                    )
                try {
                    val ledger =
                        checkNotNull(
                            control.recomputeCompletedMigrationLedger(fixture.sourceGeneration),
                        )
                    assertEquals(1L, ledger.rawSnapshotCount)
                    assertEquals(fixture.sourcePageCanonicalFingerprint, ledger.terminalSourcePageCanonicalFingerprint)
                } finally {
                    control.close()
                }
            } finally {
                context.deleteDatabase(databaseName)
                deleteProductionKey()
            }
        }

    @Test
    fun cipherFailureRollsBackSchemaRowsMetadataAndImmutableGuardsAtomically() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "mastery-v16-v17-rollback-${System.nanoTime()}.mastery-test.db"
            val databaseFile = context.getDatabasePath(databaseName)
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = databaseFile,
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )
            try {
                helper.createDatabase(16).use { connection ->
                    connection.insertV16LegacyFixture(v17LegacyFixture(HIGH_ENTROPY_CANARY))
                    connection.insertSecondV16RollbackSnapshot()
                }
                var encryptCallCount = 0
                val failingCipher =
                    object : LearnerMasteryLegacyResponseSummaryCipher {
                        override fun encrypt(
                            binding: LearnerMasteryLegacyResponseSummaryBinding,
                            plaintext: String,
                        ): LearnerMasteryEncryptedLegacyResponseSummary {
                            encryptCallCount += 1
                            if (encryptCallCount == 2) error("injected cipher failure")
                            return LearnerMasteryEncryptedLegacyResponseSummary(
                                keyVersion = binding.keyVersion,
                                nonce = ByteArray(12) { 0x2a },
                                ciphertext = ByteArray(17) { 0x5c },
                            )
                        }

                        override fun decrypt(
                            binding: LearnerMasteryLegacyResponseSummaryBinding,
                            encrypted: LearnerMasteryEncryptedLegacyResponseSummary,
                        ): String? = null
                    }
                assertTrue(
                    runCatching {
                        helper.runMigrationsAndValidate(
                            version = 17,
                            migrations = listOf(learnerMasteryMigration16To17(failingCipher)),
                        ).close()
                    }.isFailure,
                )
                assertEquals(2, encryptCallCount)

                SQLiteDatabase.openDatabase(
                    databaseFile.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    assertEquals(16, sqlite.version)
                    assertTrue(sqlite.tableColumns().contains("response_summary"))
                    assertFalse(sqlite.tableColumns().contains("ciphertext"))
                    assertEquals(
                        HIGH_ENTROPY_CANARY,
                        sqlite.textForQuery(
                            "SELECT response_summary FROM mastery_legacy_observation_snapshot",
                        ),
                    )
                    assertEquals(
                        2L,
                        sqlite.longForQuery(
                            "SELECT COUNT(*) FROM mastery_legacy_observation_snapshot",
                        ),
                    )
                    assertEquals(
                        0L,
                        sqlite.longForQuery(
                            "SELECT COUNT(*) FROM mastery_store_metadata WHERE metadata_key = " +
                                sqlLiteral(
                                    LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_METADATA_KEY,
                                ),
                        ),
                    )
                    assertEquals(
                        1L,
                        sqlite.longForQuery(
                            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' " +
                                "AND name = 'immutable_mastery_legacy_observation_snapshot_update'",
                        ),
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }
}

private data class V17LegacyFixture(
    val responseSummary: String,
    val sourceGeneration: String,
    val sourceFactId: String,
    val responseFingerprint: String,
    val sourcePayloadCanonicalFingerprint: String,
    val sourceRecordCanonicalFingerprint: String,
    val snapshotCanonicalFingerprint: String,
    val sourcePageCanonicalFingerprint: String,
    val pageReceiptCanonicalFingerprint: String,
)

private data class V17AttestationSnapshot(
    val sourceRecordCanonicalFingerprint: String,
    val snapshotCanonicalFingerprint: String,
    val sourcePageCanonicalFingerprint: String,
    val pageReceiptCanonicalFingerprint: String,
    val destinationCount: Long,
    val completionCount: Long,
)

private fun v17LegacyFixture(responseSummary: String): V17LegacyFixture {
    val learnerId = LOCAL_LEARNER_ID
    val sourceGeneration = v17Fingerprint("source-generation")
    val sourceFactId = "legacy-fact:encrypted-canary"
    val anchorId = "legacy-anchor:encrypted-canary"
    val responseFingerprint = v17Fingerprint("response")
    val payloadFingerprint = v17Fingerprint("payload")
    val sourceRecordFingerprint =
        CanonicalSha256("exact-legacy-mastery-fact-record-v1")
            .field("sourceFactId", sourceFactId)
            .field("learnerScopeId", learnerId)
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
            .field("occurredAtEpochMillis", 10L)
            .field("sourceVersion", "legacy-observation-v1")
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
    val snapshotFingerprint =
        CanonicalSha256("learner-mastery-legacy-observation-snapshot-v1")
            .field("learnerId", learnerId)
            .field("sourceGeneration", sourceGeneration)
            .field("batchSequence", 1L)
            .field("snapshotOrdinal", 0)
            .field("sourceFactId", sourceFactId)
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
            .field("occurredAtEpochMillis", 10L)
            .field("sourceVersion", "legacy-observation-v1")
            .field("sourcePayloadCanonicalFingerprint", payloadFingerprint)
            .field("proofPresent", false)
            .nullableField("sourceProofCanonicalFingerprint", null)
            .nullableField("sourceReferenceId", null)
            .nullableField("targetKind", null)
            .nullableField("targetDatabase", null)
            .nullableField("targetId", null)
            .nullableField("targetVersion", null)
            .nullableField("targetCanonicalFingerprint", null)
            .nullableField("attestedAtEpochMillis", null)
            .field("sourceRecordCanonicalFingerprint", sourceRecordFingerprint)
            .finish()
    val sourcePageFingerprint =
        CanonicalSha256("exact-legacy-mastery-fact-page-v1")
            .field("manifestVersion", "exact-legacy-authority-manifest-v1")
            .field("learnerId", learnerId)
            .nullableField("afterOccurredAtEpochMillis", null)
            .nullableField("afterSourceFactId", null)
            .field("recordCount", 1)
            .field("record[0]", sourceRecordFingerprint)
            .finish()
    val pageReceiptFingerprint =
        CanonicalSha256("learner-mastery-legacy-snapshot-page-v1")
            .field("learnerId", learnerId)
            .field("sourceGeneration", sourceGeneration)
            .field("batchSequence", 1L)
            .field("sourcePageCanonicalFingerprint", sourcePageFingerprint)
            .nullableField("afterOccurredAtEpochMillis", null)
            .nullableField("afterSourceFactId", null)
            .field("recordCount", 1)
            .field("finalBatch", true)
            .field("sourceRecord[0]", sourceRecordFingerprint)
            .finish()
    return V17LegacyFixture(
        responseSummary = responseSummary,
        sourceGeneration = sourceGeneration,
        sourceFactId = sourceFactId,
        responseFingerprint = responseFingerprint,
        sourcePayloadCanonicalFingerprint = payloadFingerprint,
        sourceRecordCanonicalFingerprint = sourceRecordFingerprint,
        snapshotCanonicalFingerprint = snapshotFingerprint,
        sourcePageCanonicalFingerprint = sourcePageFingerprint,
        pageReceiptCanonicalFingerprint = pageReceiptFingerprint,
    )
}

private fun SQLiteConnection.insertV16LegacyFixture(fixture: V17LegacyFixture) {
    execSQL("DROP TRIGGER IF EXISTS sealed_mastery_legacy_observation_snapshot_insert")
    execSQL(
        """
        INSERT INTO mastery_legacy_observation_snapshot_page (
            learner_id, source_generation, batch_sequence,
            source_page_canonical_fingerprint, after_occurred_at_epoch_millis,
            after_source_fact_id, terminal_occurred_at_epoch_millis,
            terminal_source_fact_id, snapshot_count, final_batch,
            page_receipt_canonical_fingerprint
        ) VALUES (
            ${sqlLiteral(LOCAL_LEARNER_ID)}, ${sqlLiteral(fixture.sourceGeneration)}, 1,
            ${sqlLiteral(fixture.sourcePageCanonicalFingerprint)}, NULL, NULL, 10,
            ${sqlLiteral(fixture.sourceFactId)}, 1, 1,
            ${sqlLiteral(fixture.pageReceiptCanonicalFingerprint)}
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO mastery_legacy_observation_snapshot (
            learner_id, source_generation, batch_sequence, snapshot_ordinal,
            source_fact_id, source, fact_kind, anchor_id, subject,
            conversation_generation, conversation_id, turn_receipt_id,
            evidence_request_id, response_fingerprint, response_summary,
            occurred_at_epoch_millis, source_version,
            source_payload_canonical_fingerprint, proof_present,
            source_proof_canonical_fingerprint, source_reference_id, target_kind,
            target_database, target_id, target_version, target_canonical_fingerprint,
            attested_at_epoch_millis, source_record_canonical_fingerprint,
            snapshot_canonical_fingerprint
        ) VALUES (
            ${sqlLiteral(LOCAL_LEARNER_ID)}, ${sqlLiteral(fixture.sourceGeneration)}, 1, 0,
            ${sqlLiteral(fixture.sourceFactId)}, 'TUTOR_SPECIFIC_STUCK', 'SPECIFIC_STUCK',
            'legacy-anchor:encrypted-canary', 'PHYSICS', NULL, NULL, NULL, NULL,
            ${sqlLiteral(fixture.responseFingerprint)}, ${sqlLiteral(fixture.responseSummary)},
            10, 'legacy-observation-v1',
            ${sqlLiteral(fixture.sourcePayloadCanonicalFingerprint)}, 0,
            NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
            ${sqlLiteral(fixture.sourceRecordCanonicalFingerprint)},
            ${sqlLiteral(fixture.snapshotCanonicalFingerprint)}
        )
        """.trimIndent(),
    )
    installLearnerMasteryImmutableLedgerGuards(this)
}

private fun SQLiteConnection.insertSecondV16RollbackSnapshot() {
    val sourceFactId = "legacy-fact:rollback-second"
    execSQL("DROP TRIGGER IF EXISTS sealed_mastery_legacy_observation_snapshot_insert")
    execSQL("DROP TRIGGER IF EXISTS immutable_mastery_legacy_observation_snapshot_page_update")
    execSQL(
        """
        UPDATE mastery_legacy_observation_snapshot_page
        SET snapshot_count = 2,
            terminal_occurred_at_epoch_millis = 20,
            terminal_source_fact_id = ${sqlLiteral(sourceFactId)}
        WHERE learner_id = ${sqlLiteral(LOCAL_LEARNER_ID)} AND batch_sequence = 1
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO mastery_legacy_observation_snapshot (
            learner_id, source_generation, batch_sequence, snapshot_ordinal,
            source_fact_id, source, fact_kind, anchor_id, subject,
            conversation_generation, conversation_id, turn_receipt_id,
            evidence_request_id, response_fingerprint, response_summary,
            occurred_at_epoch_millis, source_version,
            source_payload_canonical_fingerprint, proof_present,
            source_proof_canonical_fingerprint, source_reference_id, target_kind,
            target_database, target_id, target_version, target_canonical_fingerprint,
            attested_at_epoch_millis, source_record_canonical_fingerprint,
            snapshot_canonical_fingerprint
        )
        SELECT learner_id, source_generation, batch_sequence, 1,
               ${sqlLiteral(sourceFactId)}, source, fact_kind,
               'legacy-anchor:rollback-second', subject,
               conversation_generation, conversation_id, turn_receipt_id,
               evidence_request_id, ${sqlLiteral(v17Fingerprint("rollback-response"))},
               '第二条记录用于验证部分写入也会回滚', 20, source_version,
               ${sqlLiteral(v17Fingerprint("rollback-payload"))}, proof_present,
               source_proof_canonical_fingerprint, source_reference_id, target_kind,
               target_database, target_id, target_version, target_canonical_fingerprint,
               attested_at_epoch_millis, ${sqlLiteral(v17Fingerprint("rollback-source-record"))},
               ${sqlLiteral(v17Fingerprint("rollback-snapshot"))}
        FROM mastery_legacy_observation_snapshot
        WHERE snapshot_ordinal = 0
        """.trimIndent(),
    )
    installLearnerMasteryImmutableLedgerGuards(this)
}

private fun readAttestationSnapshot(databaseFile: java.io.File): V17AttestationSnapshot =
    SQLiteDatabase.openDatabase(
        databaseFile.path,
        null,
        SQLiteDatabase.OPEN_READONLY,
    ).use { sqlite ->
        V17AttestationSnapshot(
            sourceRecordCanonicalFingerprint =
                sqlite.textForQuery(
                    "SELECT source_record_canonical_fingerprint " +
                        "FROM mastery_legacy_observation_snapshot",
                ),
            snapshotCanonicalFingerprint =
                sqlite.textForQuery(
                    "SELECT snapshot_canonical_fingerprint " +
                        "FROM mastery_legacy_observation_snapshot",
                ),
            sourcePageCanonicalFingerprint =
                sqlite.textForQuery(
                    "SELECT source_page_canonical_fingerprint " +
                        "FROM mastery_legacy_observation_snapshot_page",
                ),
            pageReceiptCanonicalFingerprint =
                sqlite.textForQuery(
                    "SELECT page_receipt_canonical_fingerprint " +
                        "FROM mastery_legacy_observation_snapshot_page",
                ),
            destinationCount =
                sqlite.longForQuery(
                    "SELECT COUNT(*) FROM mastery_legacy_fact_migration_destination_record",
                ),
            completionCount =
                sqlite.longForQuery(
                    "SELECT COUNT(*) FROM mastery_cutover_completion_receipt",
                ),
        )
    }

private fun SQLiteDatabase.tableColumns(): Set<String> =
    rawQuery("PRAGMA table_info(mastery_legacy_observation_snapshot)", null).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(1))
        }
    }

private fun SQLiteDatabase.textForQuery(sql: String): String =
    rawQuery(sql, emptyArray<String>()).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

private fun SQLiteDatabase.longForQuery(sql: String): Long =
    rawQuery(sql, emptyArray<String>()).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

private fun assertCanaryAbsentFromDatabaseAndSidecars(
    databaseFile: java.io.File,
    canary: String,
) {
    val escaped = JSONObject.quote(canary)
    val needles =
        listOf(
            canary,
            escaped,
            escaped.removePrefix("\"").removeSuffix("\""),
        ).map { it.toByteArray(StandardCharsets.UTF_8) }
    listOf(
        databaseFile,
        java.io.File(databaseFile.path + "-wal"),
        java.io.File(databaseFile.path + "-shm"),
    ).filter(java.io.File::isFile).forEach { file ->
        val bytes = file.readBytes()
        needles.forEach { needle ->
            assertFalse(
                "Plaintext legacy response survived in ${file.name}",
                bytes.containsSubsequence(needle),
            )
        }
    }
}

private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    for (start in 0..size - needle.size) {
        var matches = true
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) {
                matches = false
                break
            }
        }
        if (matches) return true
    }
    return false
}

private fun deleteProductionKey() {
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        .deleteEntry(LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_KEY_ALIAS)
}

private fun sqlLiteral(value: String): String = "'${value.replace("'", "''")}'"

private fun v17Fingerprint(seed: String): String =
    CanonicalSha256("learner-mastery-v17-migration-test")
        .field("seed", seed)
        .finish()

private const val HIGH_ENTROPY_CANARY =
    "高熵‘单引号’\"双引号\"\\反斜杠::d7K9!pQ2#xV8@rT4%uN6^mL1&zC5"
