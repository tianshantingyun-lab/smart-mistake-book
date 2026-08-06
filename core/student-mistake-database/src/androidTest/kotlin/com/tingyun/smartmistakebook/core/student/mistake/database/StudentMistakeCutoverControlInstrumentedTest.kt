package com.tingyun.smartmistakebook.core.student.mistake.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentMistakeCutoverControlInstrumentedTest {
    @Test
    fun appendIfAbsentReturnsThePersistedWinnerAndTablesStayAppendOnly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName =
            "student-cutover-${System.nanoTime()}.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        val fence =
            StudentMistakeAuthorityCutoverFence.create(
                cutoverGeneration = 11,
                studentImportEvidenceFingerprint = "a".repeat(64),
                masteryImportEvidenceFingerprint = "b".repeat(64),
            )
        val conflictingFence =
            StudentMistakeAuthorityCutoverFence.create(
                cutoverGeneration = 12,
                studentImportEvidenceFingerprint = "c".repeat(64),
                masteryImportEvidenceFingerprint = "d".repeat(64),
            )
        val receipt = StudentMistakeAuthorityCutoverCompletionReceipt.create(fence)

        StudentMistakeCutoverControlPortFactory.openForTest(context, databaseName).use { port ->
            assertNull(port.readCutoverFence())
            assertEquals(fence, port.appendCutoverFenceIfAbsent(fence))
            assertEquals(fence, port.appendCutoverFenceIfAbsent(fence))
            assertEquals(fence, port.appendCutoverFenceIfAbsent(conflictingFence))

            assertTrue(
                runCatching {
                    port.appendCompletionReceiptIfAbsent(
                        StudentMistakeAuthorityCutoverCompletionReceipt.create(
                            conflictingFence,
                        ),
                    )
                }.isFailure,
            )
            assertEquals(receipt, port.appendCompletionReceiptIfAbsent(receipt))
            assertEquals(receipt, port.appendCompletionReceiptIfAbsent(receipt))
            assertEquals(
                receipt,
                port.appendCompletionReceiptIfAbsent(
                    StudentMistakeAuthorityCutoverCompletionReceipt.create(
                        conflictingFence,
                    ),
                ),
            )
        }

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            assertEquals(1L, database.longForQuery("SELECT COUNT(*) FROM student_cutover_fence"))
            assertEquals(
                1L,
                database.longForQuery(
                    "SELECT COUNT(*) FROM student_cutover_completion_receipt",
                ),
            )
            assertTrue(
                database.textSetForQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'trigger'",
                ).containsAll(STUDENT_CUTOVER_IMMUTABILITY_TRIGGER_NAMES),
            )

            assertSqlFails(
                database,
                "UPDATE student_cutover_fence SET cutover_generation = 99",
            )
            assertSqlFails(database, "DELETE FROM student_cutover_fence")
            assertSqlFails(
                database,
                """
                UPDATE student_cutover_completion_receipt
                SET cutover_generation = 99
                """.trimIndent(),
            )
            assertSqlFails(database, "DELETE FROM student_cutover_completion_receipt")
            assertSqlFails(
                database,
                """
                INSERT INTO student_cutover_fence (
                    singleton_key, cutover_generation,
                    student_import_evidence_fingerprint,
                    mastery_import_evidence_fingerprint,
                    cutover_intent_fingerprint, fence_fingerprint
                ) VALUES (
                    'not-the-student-authority', 1,
                    '${"a".repeat(64)}', '${"b".repeat(64)}',
                    '${"c".repeat(64)}', '${"d".repeat(64)}'
                )
                """.trimIndent(),
            )
            assertSqlFailsWithMessage(
                database,
                """
                INSERT INTO student_mistake_migration_checkpoint (
                    migration_id, source_database_canonical_fingerprint,
                    last_committed_at_epoch_millis, last_problem_id,
                    last_revision_number, last_revision_id, imported_record_count,
                    completed, checkpoint_canonical_fingerprint,
                    updated_at_epoch_millis
                ) VALUES (
                    'post-fence', '${"a".repeat(64)}',
                    NULL, NULL, NULL, NULL, 0, 1, '${"b".repeat(64)}', 1
                )
                """.trimIndent(),
                "student migration ledger is terminal",
            )
        }

        StudentMistakeCutoverControlPortFactory.openForTest(context, databaseName).use { port ->
            assertEquals(fence, port.readCutoverFence())
            assertEquals(receipt, port.readCompletionReceipt())
        }
        assertEquals(databaseName, context.getDatabasePath(databaseName).name)
        context.deleteDatabase(databaseName)
        Unit
    }

    @Test
    fun terminalCheckpointSealsOnlyItsMigrationIdBeforeCutoverFence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName =
            "student-terminal-checkpoint-${System.nanoTime()}.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        try {
            StudentMistakeCutoverControlPortFactory
                .openForTest(context, databaseName)
                .use { port -> assertNull(port.readCutoverFence()) }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    """
                    INSERT INTO student_mistake_migration_checkpoint (
                        migration_id, source_database_canonical_fingerprint,
                        last_committed_at_epoch_millis, last_problem_id,
                        last_revision_number, last_revision_id, imported_record_count,
                        completed, checkpoint_canonical_fingerprint,
                        updated_at_epoch_millis
                    ) VALUES (
                        'terminal-only', '${"a".repeat(64)}',
                        NULL, NULL, NULL, NULL, 0, 1, '${"b".repeat(64)}', 1
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO student_mistake_migration_checkpoint (
                        migration_id, source_database_canonical_fingerprint,
                        last_committed_at_epoch_millis, last_problem_id,
                        last_revision_number, last_revision_id, imported_record_count,
                        completed, checkpoint_canonical_fingerprint,
                        updated_at_epoch_millis
                    ) VALUES (
                        'after-terminal-only', '${"c".repeat(64)}',
                        NULL, NULL, NULL, NULL, 0, 0, '${"d".repeat(64)}', 2
                    )
                    """.trimIndent(),
                )
                assertEquals(
                    2L,
                    database.longForQuery(
                        "SELECT COUNT(*) FROM student_mistake_migration_checkpoint",
                    ),
                )
                assertSqlFailsWithMessage(
                    database,
                    """
                    INSERT INTO student_mistake_migration_receipt (
                        migration_id, source_page_canonical_fingerprint,
                        imported_record_count, result_last_committed_at_epoch_millis,
                        result_last_problem_id, result_last_revision_number,
                        result_last_revision_id, result_total_record_count,
                        result_completed, checkpoint_canonical_fingerprint,
                        receipt_canonical_fingerprint, applied_at_epoch_millis
                    ) VALUES (
                        'terminal-only', '${"c".repeat(64)}',
                        0, NULL, NULL, NULL, NULL, 0, 1,
                        '${"b".repeat(64)}', '${"d".repeat(64)}', 2
                    )
                    """.trimIndent(),
                    "student migration ledger is terminal",
                )
                assertSqlFailsWithMessage(
                    database,
                    """
                    INSERT INTO student_mistake_migration_destination_record (
                        migration_id, source_page_canonical_fingerprint,
                        page_record_ordinal, committed_at_epoch_millis,
                        problem_id, revision_number, revision_id,
                        destination_record_canonical_fingerprint
                    ) VALUES (
                        'terminal-only', '${"c".repeat(64)}', 0, 1,
                        'missing-problem', 1, 'missing-revision', '${"d".repeat(64)}'
                    )
                    """.trimIndent(),
                    "student migration ledger is terminal",
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
        Unit
    }

    @Test
    fun databaseOpenFailsClosedWhenAMigrationSealTriggerIsMissing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName =
            "student-trigger-verification-${System.nanoTime()}.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        try {
            StudentMistakeCutoverControlPortFactory
                .openForTest(context, databaseName)
                .use { port -> assertNull(port.readCutoverFence()) }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    "DROP TRIGGER sealed_student_mistake_migration_receipt_insert",
                )
            }

            assertTrue(
                runCatching {
                    StudentMistakeCutoverControlPortFactory
                        .openForTest(context, databaseName)
                        .use { port -> port.readCutoverFence() }
                }.exceptionOrNull().hasMessage(
                    "Student cutover/migration immutability triggers are missing",
                ),
            )
        } finally {
            context.deleteDatabase(databaseName)
        }
        Unit
    }

    @Test
    fun completedMigrationDigestIsFreshAndRejectsLedgerTampering() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName =
            "student-cutover-ledger-${System.nanoTime()}.student-mistake-test.db"
        val migrationId = "legacy-student-terminal-v1"
        context.deleteDatabase(databaseName)

        StudentMistakeMigrationPortFactory.openForTest(context, databaseName).use { migration ->
            migration.applyPage(
                ApplyStudentMistakeMigrationPageCommand(
                    migrationId = migrationId,
                    sourceDatabaseCanonicalFingerprint = "a".repeat(64),
                    sourcePageCanonicalFingerprint = "b".repeat(64),
                    expectedCheckpointCanonicalFingerprint = null,
                    afterExclusive = null,
                    records = emptyList(),
                    isLastPage = true,
                    appliedAtEpochMillis = 100,
                ),
            )
        }

        val firstDigest =
            StudentMistakeCutoverControlPortFactory
                .openForTest(context, databaseName)
                .use { port ->
                    checkNotNull(port.recomputeCompletedMigrationLedger(migrationId))
                }
        assertEquals(0L, firstDigest.migratedRecordCount)
        assertEquals(1, firstDigest.pageReceiptCount)
        assertEquals("a".repeat(64), firstDigest.sourceDatabaseCanonicalFingerprint)
        assertEquals("b".repeat(64), firstDigest.terminalSourcePageCanonicalFingerprint)
        assertEquals(
            firstDigest,
            StudentMistakeCutoverControlPortFactory
                .openForTest(context, databaseName)
                .use { port ->
                    port.recomputeCompletedMigrationLedger(migrationId)
                },
        )

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            assertSqlFails(
                database,
                """
                UPDATE student_mistake_migration_receipt
                SET receipt_canonical_fingerprint = '${"c".repeat(64)}'
                WHERE migration_id = '$migrationId'
                """.trimIndent(),
            )
            assertSqlFails(
                database,
                """
                UPDATE student_mistake_migration_checkpoint
                SET imported_record_count = 1
                WHERE migration_id = '$migrationId'
                """.trimIndent(),
            )
            assertSqlFails(
                database,
                """
                DELETE FROM student_mistake_migration_receipt
                WHERE migration_id = '$migrationId'
                """.trimIndent(),
            )

            database.execSQL("DROP TRIGGER immutable_student_mistake_migration_receipt_update")
            database.execSQL(
                """
                UPDATE student_mistake_migration_receipt
                SET receipt_canonical_fingerprint = '${"c".repeat(64)}'
                WHERE migration_id = '$migrationId'
                """.trimIndent(),
            )
        }

        val tamperResult =
            runCatching {
                StudentMistakeCutoverControlPortFactory
                    .openForTest(context, databaseName)
                    .use { port ->
                        port.recomputeCompletedMigrationLedger(migrationId)
                    }
            }
        assertTrue(tamperResult.isFailure)
        assertNotNull(tamperResult.exceptionOrNull())
        context.deleteDatabase(databaseName)
        Unit
    }

    @Test
    fun completedMigrationRehashesDestinationRowsAndPhysicallySealsItsLedger() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName =
            "student-cutover-destination-${System.nanoTime()}.student-mistake-test.db"
        val migrationId = "legacy-student-destination-v1"
        val migratedRecord = migrationRecord("destination", committedAtEpochMillis = 100)
        context.deleteDatabase(databaseName)
        try {
            val command =
                ApplyStudentMistakeMigrationPageCommand(
                    migrationId = migrationId,
                    sourceDatabaseCanonicalFingerprint = "a".repeat(64),
                    sourcePageCanonicalFingerprint = "b".repeat(64),
                    expectedCheckpointCanonicalFingerprint = null,
                    afterExclusive = null,
                    records = listOf(migratedRecord),
                    isLastPage = true,
                    appliedAtEpochMillis = 200,
                )
            StudentMistakeMigrationPortFactory.openForTest(context, databaseName).use { migration ->
                val first = migration.applyPage(command)
                assertEquals(first, migration.applyPage(command))
            }

            val initialDigest = recomputeMigrationDigest(context, databaseName, migrationId)
            assertEquals(1L, initialDigest.migratedRecordCount)
            assertEquals(3, initialDigest.destinationLedgerVersion)
            assertEquals(1L, initialDigest.immutableImportSnapshotCount)
            assertEquals(1L, initialDigest.legacySemanticSnapshotCount)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    """
                    UPDATE student_practice_unit
                    SET title = 'Later legal title',
                        estimated_duration_seconds = 75,
                        updated_at_epoch_millis = 250
                    WHERE practice_unit_id = 'unit-destination'
                    """.trimIndent(),
                )
            }
            assertEquals(
                initialDigest,
                recomputeMigrationDigest(context, databaseName, migrationId),
            )
            StudentMistakeMigrationPortFactory
                .openForTest(context, databaseName)
                .use { migration ->
                    migration.applyPage(
                        ApplyStudentMistakeMigrationPageCommand(
                            migrationId = "post-terminal-migration",
                            sourceDatabaseCanonicalFingerprint = "c".repeat(64),
                            sourcePageCanonicalFingerprint = "d".repeat(64),
                            expectedCheckpointCanonicalFingerprint = null,
                            afterExclusive = null,
                            records = emptyList(),
                            isLastPage = true,
                            appliedAtEpochMillis = 300,
                        ),
                    )
                }
            assertEquals(
                0L,
                recomputeMigrationDigest(
                    context,
                    databaseName,
                    "post-terminal-migration",
                ).migratedRecordCount,
            )

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.setForeignKeyConstraintsEnabled(true)
                assertEquals(
                    1L,
                    database.longForQuery(
                        "SELECT COUNT(*) FROM student_mistake_migration_destination_record",
                    ),
                )
                assertEquals(
                    "capture:destination",
                    database.textForQuery(
                        """
                        SELECT legacy_revision_source_reference
                        FROM student_problem_import_semantic_snapshot
                        WHERE revision_id = 'revision-destination'
                        """.trimIndent(),
                    ),
                )
                assertEquals(
                    "error-book:destination",
                    database.textForQuery(
                        """
                        SELECT legacy_error_book_source_key
                        FROM student_problem_import_semantic_snapshot
                        WHERE revision_id = 'revision-destination'
                        """.trimIndent(),
                    ),
                )
                assertEquals(
                    "请选择正确结论。",
                    database.textForQuery(
                        """
                        SELECT legacy_practice_unit_prompt_markdown
                        FROM student_problem_import_semantic_snapshot
                        WHERE revision_id = 'revision-destination'
                        """.trimIndent(),
                    ),
                )
                assertSqlFails(
                    database,
                    """
                    UPDATE student_problem_import_semantic_snapshot
                    SET legacy_revision_source_type = 'tampered'
                    WHERE revision_id = 'revision-destination'
                    """.trimIndent(),
                )
                assertSqlFails(
                    database,
                    """
                    DELETE FROM student_problem_import_semantic_snapshot
                    WHERE revision_id = 'revision-destination'
                    """.trimIndent(),
                )
                assertSqlFailsWithMessage(
                    database,
                    """
                    INSERT INTO student_mistake_migration_checkpoint (
                        migration_id, source_database_canonical_fingerprint,
                        last_committed_at_epoch_millis, last_problem_id,
                        last_revision_number, last_revision_id, imported_record_count,
                        completed, checkpoint_canonical_fingerprint,
                        updated_at_epoch_millis
                    ) VALUES (
                        '$migrationId', '${"c".repeat(64)}',
                        NULL, NULL, NULL, NULL, 0, 1, '${"d".repeat(64)}', 300
                    )
                    """.trimIndent(),
                    "student migration ledger is terminal",
                )
                assertSqlFailsWithMessage(
                    database,
                    """
                    INSERT INTO student_mistake_migration_receipt (
                        migration_id, source_page_canonical_fingerprint,
                        imported_record_count, result_last_committed_at_epoch_millis,
                        result_last_problem_id, result_last_revision_number,
                        result_last_revision_id, result_total_record_count,
                        result_completed, checkpoint_canonical_fingerprint,
                        receipt_canonical_fingerprint, applied_at_epoch_millis
                    ) VALUES (
                        '$migrationId', '${"d".repeat(64)}',
                        0, 100, 'problem-destination', 1,
                        'revision-destination', 1, 1,
                        '${"e".repeat(64)}', '${"f".repeat(64)}', 300
                    )
                    """.trimIndent(),
                    "student migration ledger is terminal",
                )
                assertSqlFailsWithMessage(
                    database,
                    """
                    INSERT INTO student_mistake_migration_destination_record (
                        migration_id, source_page_canonical_fingerprint,
                        page_record_ordinal, committed_at_epoch_millis,
                        problem_id, revision_number, revision_id,
                        destination_record_canonical_fingerprint
                    ) VALUES (
                        '$migrationId', '${"d".repeat(64)}', 0, 100,
                        'problem-destination', 1, 'revision-destination',
                        '${"e".repeat(64)}'
                    )
                    """.trimIndent(),
                    "student migration ledger is terminal",
                )
                assertSqlFails(
                    database,
                    """
                    UPDATE student_mistake_migration_destination_record
                    SET destination_record_canonical_fingerprint = '${"e".repeat(64)}'
                    WHERE migration_id = '$migrationId'
                    """.trimIndent(),
                )
                assertSqlFails(
                    database,
                    """
                    DELETE FROM student_mistake_migration_destination_record
                    WHERE migration_id = '$migrationId'
                    """.trimIndent(),
                )
                assertSqlFails(
                    database,
                    "DELETE FROM student_problem_revision " +
                        "WHERE revision_id = 'revision-destination'",
                )
                database.execSQL(
                    """
                    UPDATE student_problem_revision
                    SET title = 'tampered'
                    WHERE revision_id = 'revision-destination'
                    """.trimIndent(),
                )
            }
            assertTrue(
                runCatching {
                    recomputeMigrationDigest(context, databaseName, migrationId)
                }.exceptionOrNull().hasMessage(
                    "Imported student revision content changed after migration",
                ),
            )

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    """
                    UPDATE student_problem_revision
                    SET title = 'Migrated destination'
                    WHERE revision_id = 'revision-destination'
                    """.trimIndent(),
                )
            }
            assertEquals(
                initialDigest,
                recomputeMigrationDigest(context, databaseName, migrationId),
            )

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.setForeignKeyConstraintsEnabled(false)
                database.execSQL(
                    "DELETE FROM student_problem_revision " +
                        "WHERE revision_id = 'revision-destination'",
                )
                assertEquals(
                    0L,
                    database.longForQuery(
                        """
                        SELECT COUNT(*)
                        FROM student_problem_revision
                        WHERE revision_id = 'revision-destination'
                        """.trimIndent(),
                    ),
                )
            }
            assertTrue(
                runCatching {
                    recomputeMigrationDigest(context, databaseName, migrationId)
                }.exceptionOrNull().hasMessage("Imported student revision revision-destination is missing"),
            )
        } finally {
            context.deleteDatabase(databaseName)
        }
        Unit
    }
}

private fun assertSqlFails(
    database: SQLiteDatabase,
    sql: String,
) {
    assertTrue("Expected SQL to fail: $sql", runCatching { database.execSQL(sql) }.isFailure)
}

private fun assertSqlFailsWithMessage(
    database: SQLiteDatabase,
    sql: String,
    expectedMessage: String,
) {
    val failure =
        checkNotNull(runCatching { database.execSQL(sql) }.exceptionOrNull()) {
            "Expected SQL to fail: $sql"
        }
    assertTrue(
        "Expected SQL failure to contain '$expectedMessage', but was: $failure",
        failure.hasMessage(expectedMessage),
    )
}

private fun Throwable?.hasMessage(expectedMessage: String): Boolean {
    if (this == null) return false
    return generateSequence(this) { it.cause }
        .any { it.message?.contains(expectedMessage) == true }
}

private suspend fun recomputeMigrationDigest(
    context: android.content.Context,
    databaseName: String,
    migrationId: String,
): StudentMistakeImmutableMigrationLedgerDigest =
    StudentMistakeCutoverControlPortFactory
        .openForTest(context, databaseName)
        .use { port ->
            checkNotNull(port.recomputeCompletedMigrationLedger(migrationId))
        }

private fun migrationRecord(
    suffix: String,
    committedAtEpochMillis: Long,
): StudentMistakeMigrationRecord {
    val problem =
        StudentProblemRef(
            learnerId = "learner-$suffix",
            subject = SubjectKind.MATH,
            problemId = "problem-$suffix",
            practiceUnitId = "unit-$suffix",
        )
    val command =
        CommitStudentProblemCommand(
            revision =
                StudentProblemRevisionRef(
                    problem = problem,
                    revisionId = "revision-$suffix",
                    revisionNumber = 1,
                    documentCanonicalFingerprint = "9".repeat(64),
                ),
            title = "Migrated $suffix",
            stemMarkdown = "Imported immutable destination.",
            practiceUnitKind = StudentPracticeUnitKind.WHOLE_PROBLEM,
            practiceUnitTitle = "Migrated $suffix",
            itemFamilyId = "family-$suffix",
            estimatedDurationSeconds = 60,
            sourceBundleId = null,
            partIds = emptyList(),
            originalImages = emptyList(),
            committedAtEpochMillis = committedAtEpochMillis,
            errorBookEntryId = "entry-$suffix",
        )
    return StudentMistakeMigrationRecord(
        problem = command,
        collection =
            SetStudentProblemCollectionCommand(
                problem = problem,
                mistakeState = StudentMistakeEntryState.ACTIVE,
                favorite = false,
                changedAtEpochMillis = committedAtEpochMillis,
            ),
        importSemanticSnapshot =
            StudentMistakeImportSemanticSnapshot(
                problemCanonicalFingerprint = "8".repeat(64),
                revisionSourceType = "CAPTURE_CONFIRMED",
                revisionSourceReference = "capture:$suffix",
                answerSpecId = "answer-$suffix",
                answerSpecSnapshot = """{"answer":"B"}""",
                answerVerificationStatus = "VERIFIED",
                errorBookSourceKey = "error-book:$suffix",
                practiceUnitKey = "whole-problem",
                practiceUnitPromptMarkdown = "请选择正确结论。",
            ),
    )
}

private fun SQLiteDatabase.longForQuery(sql: String): Long =
    rawQuery(sql, emptyArray()).use { cursor ->
        check(cursor.moveToFirst()) { "Expected one scalar row" }
        cursor.getLong(0)
    }

private fun SQLiteDatabase.textSetForQuery(sql: String): Set<String> =
    rawQuery(sql, emptyArray()).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

private fun SQLiteDatabase.textForQuery(sql: String): String =
    rawQuery(sql, emptyArray()).use { cursor ->
        check(cursor.moveToFirst()) { "Expected one scalar row" }
        cursor.getString(0)
    }
