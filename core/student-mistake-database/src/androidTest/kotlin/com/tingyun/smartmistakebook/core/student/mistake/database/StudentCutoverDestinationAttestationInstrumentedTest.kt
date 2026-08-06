package com.tingyun.smartmistakebook.core.student.mistake.database

import android.database.sqlite.SQLiteDatabase
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentCutoverDestinationAttestationInstrumentedTest {
    @Test
    fun everyKnownRetiredLegacyReviewVersionIsAttestedAsImmutableTombstone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "student-retired-v1-${System.nanoTime()}.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        try {
            val helper =
                MigrationTestHelper(
                    instrumentation = InstrumentationRegistry.getInstrumentation(),
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = StudentMistakeRoomDatabase::class,
                )
            helper.createDatabase(17).use { connection ->
                connection.execSQL(
                    """
                    INSERT INTO student_store_metadata(
                        metadata_key, metadata_value,
                        created_at_epoch_millis, updated_at_epoch_millis
                    ) VALUES ('$STORE_GENERATION_METADATA_KEY', 'legacy-student-generation', 100, 100)
                    """.trimIndent(),
                )
                connection.insertLegacyReviewOutboxV17(
                    eventId = "retired-v1-event",
                    payloadVersion = 1,
                )
                connection.insertLegacyReviewOutboxV17(
                    eventId = "retired-v2-event",
                    payloadVersion = 2,
                )
                createStudentReviewReceiptImmutabilityTriggers(connection)
                createStudentCutoverAndMigrationLedgerImmutabilityTriggers(connection)
                createStudentImportSnapshotImmutabilityTriggers(connection)
                createStudentDestinationReattestationImmutabilityTriggers(connection)
                createStudentProblemOrganizationImmutabilityTriggers(connection)
                createStudentProblemIdentityReceiptImmutabilityTriggers(connection)
            }
            helper.runMigrationsAndValidate(
                version = 18,
                migrations = listOf(STUDENT_MISTAKE_MIGRATION_17_18),
            ).close()
            context.openOrCreateDatabase(databaseName, 0, null).use { database ->
                assertTrue(
                    runCatching {
                        database.insertLegacyReviewOutbox(
                            eventId = "forged-retired-v1-event",
                            payloadVersion = 1,
                            deliveryState = "RETIRED_UNSAFE_LEGACY",
                            deliveredAtEpochMillis = 200L,
                        )
                    }.isFailure,
                )
            }
            StudentMistakeStoreFactory.openForTest(context, databaseName).use { store ->
                val relay = store.relayForLearner("learner-retired-v1")
                assertTrue(relay.readPending(99L).isEmpty())
                assertTrue(
                    relay.readPending(100L).isEmpty(),
                )
            }

            StudentCutoverDestinationAttestationPortFactory.openForTest(context, databaseName)
                .use { ports ->
                    val attestation = drainOutbox(ports, binding("retired-v1"))
                    assertEquals(2L, attestation.verifiedRecordCount)
                    assertTrue(attestation.hasValidFingerprint())
                }

            context.openOrCreateDatabase(databaseName, 0, null).use { database ->
                assertTrue(
                    runCatching {
                        database.execSQL(
                            "UPDATE student_store_outbox SET payload_wire = ? WHERE event_id = ?",
                            arrayOf("tampered", "retired-v1-event"),
                        )
                    }.isFailure,
                )
                assertTrue(
                    runCatching {
                        database.execSQL(
                            "DELETE FROM student_store_outbox WHERE event_id = ?",
                            arrayOf("retired-v1-event"),
                        )
                    }.isFailure,
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun unknownOutboxTerminalStateCannotPassJournalAttestation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "student-invalid-terminal-${System.nanoTime()}.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        try {
            StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName).let { roomDatabase ->
                try {
                    roomDatabase.mistakeDao().readProblem("schema-open-probe")
                } finally {
                    roomDatabase.close()
                }
            }
            context.openOrCreateDatabase(databaseName, 0, null).use { database ->
                database.insertLegacyReviewOutbox(
                    eventId = "invalid-terminal-event",
                    payloadVersion = 1,
                    deliveryState = "UNKNOWN_TERMINAL",
                    deliveredAtEpochMillis = 200L,
                )
            }
            StudentCutoverDestinationAttestationPortFactory.openForTest(context, databaseName)
                .use { ports ->
                    assertTrue(
                        runCatching {
                            ports.outboxReconciled.verifyOutboxNext(
                                binding = binding("invalid-terminal"),
                                cursor = null,
                                limit = 1,
                            )
                        }.isFailure,
                    )
                }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun realOwnerPortVerifiesBoundedEmptyDestinationWithoutWritingCutoverState() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName =
                "student-attestation-${System.nanoTime()}.student-mistake-test.db"
            context.deleteDatabase(databaseName)
            val knowledgeAuthority = KnowledgeReferenceProofAuthority.create()

            RoomStudentMistakeStore(
                database =
                    StudentMistakeStoreFactory.openDatabaseForTest(
                        context,
                        databaseName,
                    ),
                knowledgeReferenceVerifier = knowledgeAuthority.verifier,
            ).use { store ->
                assertEquals(
                    StudentMistakeSearchIndexStatus.Ready,
                    store.libraryForLearner("learner-attestation")
                        .prepareSearchIndex(maxDocuments = 8),
                )
            }

            val binding =
                StudentCutoverDestinationBinding(
                    cutoverGeneration = 3,
                    legacyPrefixFingerprint = fingerprint("legacy-prefix"),
                    studentDestinationFingerprint = fingerprint("student-destination"),
                    studentSchemaVersion = STUDENT_MISTAKE_SCHEMA_VERSION,
                    attestationPolicyVersion =
                        STUDENT_CUTOVER_ATTESTATION_POLICY_VERSION,
                )
            StudentCutoverDestinationAttestationPortFactory
                .openForTest(context, databaseName)
                .use { ports ->
                    val outbox = drainOutbox(ports, binding)
                    val indexes = drainIndexes(ports, binding)
                    assertEquals(0L, outbox.verifiedRecordCount)
                    assertEquals(0L, indexes.verifiedRecordCount)

                    val authority =
                        ports.authorityVerified.attest(
                            binding = binding,
                            documentImportReceipt =
                                StudentDocumentImportReceiptReference.issue(
                                    ownerKey = StudentMistakeOwnerKey.INSTANCE,
                                    cutoverGeneration = binding.cutoverGeneration,
                                    legacyPrefixFingerprint =
                                        binding.legacyPrefixFingerprint,
                                    migratedRecordCount = 0,
                                    sourceCheckpoint = "empty-stage-five-checkpoint",
                                    destinationFingerprint =
                                        binding.studentDestinationFingerprint,
                                    receiptFingerprint =
                                        fingerprint("stage-five-receipt"),
                                ),
                            outboxReconciled = outbox,
                            indexesRebuilt = indexes,
                        )
                    assertTrue(authority.hasValidFingerprint())
                    assertTrue(authority.issuedAtEpochMillis > 0L)
                }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(0L, database.count("student_store_outbox"))
                assertEquals(0L, database.count("student_store_inbox"))
                assertEquals(0L, database.count("student_cutover_fence"))
                assertEquals(
                    0L,
                    database.count("student_cutover_completion_receipt"),
                )
            }
            context.deleteDatabase(databaseName)
        }
    }

    private suspend fun drainOutbox(
        ports: StudentCutoverDestinationAttestationPorts,
        binding: StudentCutoverDestinationBinding,
    ): StudentOutboxReconciledAttestation {
        var cursor: StudentCutoverVerificationCursor? = null
        repeat(8) {
            when (
                val result =
                    ports.outboxReconciled.verifyOutboxNext(
                        binding = binding,
                        cursor = cursor,
                        limit = 1,
                    )
            ) {
                is StudentCutoverAttestationProgress.Continue ->
                    cursor = result.cursor

                is StudentCutoverAttestationProgress.Verified ->
                    return result.attestation
            }
        }
        error("Real student journal verification did not terminate")
    }

    private suspend fun drainIndexes(
        ports: StudentCutoverDestinationAttestationPorts,
        binding: StudentCutoverDestinationBinding,
    ): StudentIndexesRebuiltAttestation {
        var cursor: StudentCutoverVerificationCursor? = null
        repeat(8) {
            when (
                val result =
                    ports.indexesRebuilt.verifyIndexesNext(
                        binding = binding,
                        cursor = cursor,
                        limit = 1,
                    )
            ) {
                is StudentCutoverAttestationProgress.Continue ->
                    cursor = result.cursor

                is StudentCutoverAttestationProgress.Verified ->
                    return result.attestation
            }
        }
        error("Real student index verification did not terminate")
    }

    private fun SQLiteDatabase.count(table: String): Long =
        rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SQLiteDatabase.insertLegacyReviewOutbox(
        eventId: String,
        payloadVersion: Int,
        deliveryState: String,
        deliveredAtEpochMillis: Long?,
    ) {
        execSQL(
            """
            INSERT INTO student_store_outbox(
                event_id, source_store, destination_store, learner_id, aggregate_id,
                aggregate_version, payload_type, payload_version,
                payload_canonical_fingerprint, payload_wire,
                envelope_canonical_fingerprint, occurred_at_epoch_millis, idempotency_key,
                source_store_generation, delivery_state, delivery_attempt_count,
                available_at_epoch_millis, delivered_at_epoch_millis
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                eventId,
                "STUDENT_MISTAKES",
                "LEARNER_MASTERY",
                "learner-retired-v1",
                "legacy-review-observation",
                1L,
                "review_observation_captured",
                payloadVersion,
                fingerprint("legacy-payload"),
                "archive-only-wire-that-must-never-decode",
                fingerprint("legacy-envelope"),
                100L,
                "legacy-review-idempotency-$eventId",
                "legacy-student-generation",
                deliveryState,
                0,
                100L,
                deliveredAtEpochMillis,
            ),
        )
    }

    private fun SQLiteConnection.insertLegacyReviewOutboxV17(
        eventId: String,
        payloadVersion: Int,
    ) {
        val ordinal = payloadVersion.toString().padStart(2, '0')
        execSQL(
            """
            INSERT INTO student_store_outbox(
                event_id, source_store, destination_store, learner_id, aggregate_id,
                aggregate_version, payload_type, payload_version,
                payload_canonical_fingerprint, payload_wire,
                envelope_canonical_fingerprint, occurred_at_epoch_millis, idempotency_key,
                source_store_generation, delivery_state, delivery_attempt_count,
                available_at_epoch_millis, delivered_at_epoch_millis
            ) VALUES (
                '$eventId', 'STUDENT_MISTAKES', 'LEARNER_MASTERY', 'learner-retired-v1',
                'legacy-review-observation-$ordinal', $payloadVersion,
                'review_observation_captured', $payloadVersion,
                '${fingerprint("legacy-payload-$ordinal")}',
                'archive-only-wire-that-must-never-decode',
                '${fingerprint("legacy-envelope-$ordinal")}', 100,
                'legacy-review-idempotency-$eventId', 'legacy-student-generation',
                'PENDING', 0, 100, NULL
            )
            """.trimIndent(),
        )
    }

    private fun binding(seed: String) =
        StudentCutoverDestinationBinding(
            cutoverGeneration = 3,
            legacyPrefixFingerprint = fingerprint("legacy-prefix-$seed"),
            studentDestinationFingerprint = fingerprint("student-destination-$seed"),
            studentSchemaVersion = STUDENT_MISTAKE_SCHEMA_VERSION,
            attestationPolicyVersion = STUDENT_CUTOVER_ATTESTATION_POLICY_VERSION,
        )

    private fun fingerprint(value: String): String =
        CanonicalSha256("student-cutover-attestation-instrumented-test")
            .field("value", value)
            .finish()
}
