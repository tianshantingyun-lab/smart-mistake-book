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
class StudentMistakeV17MigrationInstrumentedTest {
    @Test
    fun migration16To17RetiresEveryPendingUnsignedReviewAndInstallsImmutableRoot() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "student-mistake-v16-v17-authenticity.db"
            context.deleteDatabase(databaseName)
            try {
                val helper =
                    MigrationTestHelper(
                        instrumentation = instrumentation,
                        file = context.getDatabasePath(databaseName),
                        driver = AndroidSQLiteDriver(),
                        databaseClass = StudentMistakeRoomDatabase::class,
                    )
                helper.createDatabase(16).use { connection ->
                    connection.seedV16UnsignedOutbox()
                }

                helper.runMigrationsAndValidate(
                    version = 17,
                    migrations = listOf(STUDENT_MISTAKE_MIGRATION_16_17),
                ).use { connection ->
                    listOf("review-v1", "review-v2").forEach { eventId ->
                        assertEquals(
                            "RETIRED_UNSAFE_LEGACY",
                            connection.v17ReadText(
                                "SELECT delivery_state FROM student_store_outbox " +
                                    "WHERE event_id = '$eventId'",
                            ),
                        )
                        assertEquals(
                            100L,
                            connection.v17ReadLong(
                                "SELECT delivered_at_epoch_millis FROM student_store_outbox " +
                                    "WHERE event_id = '$eventId'",
                            ),
                        )
                    }
                    assertEquals(
                        "PENDING",
                        connection.v17ReadText(
                            "SELECT delivery_state FROM student_store_outbox " +
                                "WHERE event_id = 'problem-revision'",
                        ),
                    )
                    assertEquals(
                        150L,
                        connection.v17ReadLong(
                            "SELECT delivered_at_epoch_millis FROM student_store_outbox " +
                                "WHERE event_id = 'already-retired-v1'",
                        ),
                    )
                    assertEquals(
                        0L,
                        connection.v17ReadLong(
                            "SELECT COUNT(*) FROM student_outbox_authenticity_key_state",
                        ),
                    )
                    verifyStudentOutboxAuthenticityImmutabilityTriggers(connection)

                    listOf("review-v1", "review-v2", "already-retired-v1").forEach { eventId ->
                        assertTrue(
                            runCatching {
                                connection.execSQL(
                                    "UPDATE student_store_outbox SET payload_wire = 'tampered' " +
                                        "WHERE event_id = '$eventId'",
                                )
                            }.isFailure,
                        )
                        assertTrue(
                            runCatching {
                                connection.execSQL(
                                    "DELETE FROM student_store_outbox WHERE event_id = '$eventId'",
                                )
                            }.isFailure,
                        )
                    }
                    assertTrue(
                        runCatching {
                            connection.insertV16Outbox(
                                eventId = "forged-retired-v2",
                                payloadType = "review_observation_captured",
                                payloadVersion = 2,
                                deliveryState = "RETIRED_UNSAFE_LEGACY",
                                deliveredAtEpochMillis = 200L,
                            )
                        }.isFailure,
                    )

                }
            } finally {
                context.deleteDatabase(databaseName)
            }
            Unit
        }
}

private fun SQLiteConnection.seedV16UnsignedOutbox() {
    insertV16Outbox(
        eventId = "review-v1",
        payloadType = "review_observation_captured",
        payloadVersion = 1,
    )
    insertV16Outbox(
        eventId = "review-v2",
        payloadType = "review_observation_captured",
        payloadVersion = 2,
    )
    insertV16Outbox(
        eventId = "problem-revision",
        payloadType = "problem_revision_committed",
        payloadVersion = 1,
    )
    insertV16Outbox(
        eventId = "already-retired-v1",
        payloadType = "review_observation_captured",
        payloadVersion = 1,
        deliveryState = "RETIRED_UNSAFE_LEGACY",
        deliveredAtEpochMillis = 150L,
    )
}

private fun SQLiteConnection.insertV16Outbox(
    eventId: String,
    payloadType: String,
    payloadVersion: Int,
    deliveryState: String = "PENDING",
    deliveredAtEpochMillis: Long? = null,
) {
    val delivered = deliveredAtEpochMillis?.toString() ?: "NULL"
    execSQL(
        """
        INSERT INTO student_store_outbox (
            event_id, source_store, destination_store, learner_id, aggregate_id,
            aggregate_version, payload_type, payload_version,
            payload_canonical_fingerprint, payload_wire,
            envelope_canonical_fingerprint, occurred_at_epoch_millis,
            idempotency_key, source_store_generation, delivery_state,
            delivery_attempt_count, available_at_epoch_millis, delivered_at_epoch_millis
        ) VALUES (
            '$eventId', 'STUDENT_MISTAKES', 'LEARNER_MASTERY', 'learner-v17',
            'aggregate-$eventId', 1, '$payloadType', $payloadVersion,
            '${"a".repeat(64)}', '{"event":"$eventId"}', '${"b".repeat(64)}', 100,
            'idempotency-$eventId', 'student-generation-v16', '$deliveryState',
            0, 100, $delivered
        )
        """.trimIndent(),
    )
}

private fun SQLiteConnection.v17ReadLong(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step()) { "Expected one long value" }
        statement.getLong(0)
    }

private fun SQLiteConnection.v17ReadText(sql: String): String =
    prepare(sql).use { statement ->
        check(statement.step()) { "Expected one text value" }
        statement.getText(0)
    }
