package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentMistakeV18MigrationInstrumentedTest {
    @Test
    fun migration17To18RetiresEveryPreviouslyUnsignedStudentRelayKind() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "student-mistake-v17-v18-persisted-proof.db"
            context.deleteDatabase(databaseName)
            try {
                val helper =
                    MigrationTestHelper(
                        instrumentation = instrumentation,
                        file = context.getDatabasePath(databaseName),
                        driver = AndroidSQLiteDriver(),
                        databaseClass = StudentMistakeRoomDatabase::class,
                    )
                helper.createDatabase(17).use { connection ->
                    connection.execSQL(
                        """
                        INSERT INTO student_store_metadata (
                            metadata_key, metadata_value,
                            created_at_epoch_millis, updated_at_epoch_millis
                        ) VALUES ('$STORE_GENERATION_METADATA_KEY', 'generation-v17', 100, 100)
                        """.trimIndent(),
                    )
                    listOf(
                        Triple("revision-v1", "problem_revision_committed", 1),
                        Triple("bindings-v1", "problem_knowledge_bindings_accepted", 1),
                        Triple("bindings-v2", "problem_knowledge_bindings_snapshot", 2),
                        Triple("review-v1", "review_observation_captured", 1),
                        Triple("review-v2", "review_observation_captured", 2),
                    ).forEachIndexed { index, (eventId, payloadType, payloadVersion) ->
                        connection.insertUnsignedV17Relay(
                            eventId = eventId,
                            payloadType = payloadType,
                            payloadVersion = payloadVersion,
                            ordinal = index + 1,
                        )
                    }
                }

                helper.runMigrationsAndValidate(
                    version = 18,
                    migrations = listOf(STUDENT_MISTAKE_MIGRATION_17_18),
                ).use { connection ->
                    assertEquals(
                        5L,
                        connection.v18Long(
                            "SELECT COUNT(*) FROM student_store_outbox " +
                                "WHERE delivery_state = 'RETIRED_UNSAFE_LEGACY'",
                        ),
                    )
                    assertEquals(
                        5L,
                        connection.v18Long(
                            "SELECT COUNT(*) FROM student_store_outbox " +
                                "WHERE authenticity_tag_hex IS NULL",
                        ),
                    )
                    assertEquals(
                        0L,
                        connection.v18Long(
                            "SELECT COUNT(*) FROM student_outbox_authenticity_key_state",
                        ),
                    )
                    verifyStudentOutboxAuthenticityV18ImmutabilityTriggers(connection)
                    assertTrue(
                        runCatching {
                            connection.insertUnsignedV17Relay(
                                eventId = "forged-pending",
                                payloadType = "problem_revision_committed",
                                payloadVersion = 1,
                                ordinal = 99,
                            )
                        }.isFailure,
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }
}

private fun SQLiteConnection.insertUnsignedV17Relay(
    eventId: String,
    payloadType: String,
    payloadVersion: Int,
    ordinal: Int,
) {
    val fingerprint = ordinal.toString(16).padStart(64, '0')
    execSQL(
        """
        INSERT INTO student_store_outbox (
            event_id, source_store, destination_store, learner_id,
            aggregate_id, aggregate_version, payload_type, payload_version,
            payload_canonical_fingerprint, payload_wire,
            envelope_canonical_fingerprint, occurred_at_epoch_millis,
            idempotency_key, source_store_generation, delivery_state,
            delivery_attempt_count, available_at_epoch_millis,
            delivered_at_epoch_millis
        ) VALUES (
            '$eventId', 'STUDENT_MISTAKES', 'LEARNER_MASTERY', 'learner-v18',
            'aggregate-$ordinal', $ordinal, '$payloadType', $payloadVersion,
            '$fingerprint', '{}', '$fingerprint', 100,
            'idempotency-$ordinal', 'generation-v17', 'PENDING', 0, 100, NULL
        )
        """.trimIndent(),
    )
}

private fun SQLiteConnection.v18Long(query: String): Long =
    prepare(query).use { statement ->
        check(statement.step())
        statement.getLong(0)
    }
