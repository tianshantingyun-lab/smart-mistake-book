package com.tingyun.smartmistakebook.core.mastery.database

import android.database.sqlite.SQLiteDatabase
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerMasteryMigration17To18InstrumentedTest {
    @Test
    fun migrationQuarantinesEveryPreAuthInboxAndRevokesItsBindingAuthority() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName =
                "mastery-v17-v18-auth-quarantine-${System.nanoTime()}.mastery-test.db"
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
                helper.createDatabase(17).use { connection ->
                    connection.execSQL(
                        """
                        INSERT INTO mastery_cross_store_inbox (
                            event_id, source_store, destination_store, aggregate_id,
                            aggregate_version, payload_type, payload_version,
                            payload_canonical_fingerprint, payload_wire,
                            envelope_canonical_fingerprint, idempotency_key,
                            source_store_generation, occurred_at_epoch_millis,
                            received_at_epoch_millis, processed_at_epoch_millis
                        ) VALUES (
                            'legacy-event', 'STUDENT_MISTAKES', 'LEARNER_MASTERY',
                            'legacy-aggregate', 1, 'problem_knowledge_bindings_snapshot', 2,
                            '${"1".repeat(64)}', 'legacy-wire', '${"2".repeat(64)}',
                            'legacy-idempotency', 'legacy-generation', 10, 20, 30
                        )
                        """.trimIndent(),
                    )
                    connection.execSQL(
                        """
                        INSERT INTO mastery_problem_binding_authority_state (
                            problem_revision_ref_fingerprint, inbox_event_id,
                            source_store_generation, envelope_canonical_fingerprint,
                            payload_canonical_fingerprint, binding_protocol_version,
                            binding_set_version, learner_id, subject, changed_at_epoch_millis
                        ) VALUES (
                            '${"3".repeat(64)}', 'legacy-event', 'legacy-generation',
                            '${"2".repeat(64)}', '${"1".repeat(64)}', 2, 1,
                            'local-learner', 'MATH', 10
                        )
                        """.trimIndent(),
                    )
                }

                helper.runMigrationsAndValidate(
                    version = 18,
                    migrations = listOf(LEARNER_MASTERY_MIGRATION_17_18),
                ).close()

                SQLiteDatabase.openDatabase(
                    databaseFile.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    assertEquals(
                        1L,
                        sqlite.longForAuthMigrationQuery(
                            "SELECT COUNT(*) FROM mastery_pre_auth_student_inbox_quarantine",
                        ),
                    )
                    assertEquals(
                        MASTERY_PRE_AUTH_STUDENT_INBOX_QUARANTINE_REASON,
                        sqlite.textForAuthMigrationQuery(
                            "SELECT quarantine_reason FROM " +
                                "mastery_pre_auth_student_inbox_quarantine",
                        ),
                    )
                    assertEquals(
                        0L,
                        sqlite.longForAuthMigrationQuery(
                            "SELECT COUNT(*) FROM mastery_cross_store_inbox",
                        ),
                    )
                    assertEquals(
                        0L,
                        sqlite.longForAuthMigrationQuery(
                            "SELECT COUNT(*) FROM mastery_problem_binding_authority_state",
                        ),
                    )
                    assertEquals(
                        0L,
                        sqlite.longForAuthMigrationQuery(
                            "SELECT COUNT(*) FROM " +
                                "mastery_authenticated_student_inbox_receipt",
                        ),
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }
}

private fun SQLiteDatabase.longForAuthMigrationQuery(sql: String): Long =
    rawQuery(sql, emptyArray()).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

private fun SQLiteDatabase.textForAuthMigrationQuery(sql: String): String =
    rawQuery(sql, emptyArray()).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }
