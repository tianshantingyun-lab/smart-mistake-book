package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureStudentSaveHandoffInstrumentedTest {
    @Test
    fun versionThirtyEightMigratesToEmptyHandoffJournal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "capture-student-handoff-v39-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 38)

            StudyDatabaseFactory.open(context, databaseName).use { store ->
                assertEquals(
                    emptyList<CaptureStudentSaveHandoffRecord>(),
                    store.readPendingCaptureStudentSaveHandoffs(pendingQuery()),
                )
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                assertEquals(0, database.rowCount(HANDOFF_TABLE))
                assertEquals(
                    setOf(PENDING_INDEX),
                    database.indexNames(HANDOFF_TABLE),
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun preparedIntentSurvivesCrashAndCanBeReplayedThenFinalized() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "capture-student-handoff-replay-${System.nanoTime()}.db"
        val prepare = prepareCommand()
        val finalize = finalizeCommand(prepare)
        context.deleteDatabase(databaseName)
        try {
            val first = StudyDatabaseFactory.open(context, databaseName).use { store ->
                store.prepareCaptureStudentSaveHandoff(prepare)
            }
            assertEquals(CaptureStudentSaveHandoffWriteOutcome.INSERTED, first.outcome)

            StudyDatabaseFactory.open(context, databaseName).use { reopened ->
                assertEquals(listOf(first.record), reopened.readPendingCaptureStudentSaveHandoffs(
                    pendingQuery(),
                ))
                assertEquals(
                    CaptureStudentSaveHandoffWriteOutcome.REPLAYED,
                    reopened.prepareCaptureStudentSaveHandoff(
                        prepare.copy(preparedAtEpochMillis = 9_000),
                    ).outcome,
                )
                val finalized = reopened.finalizeCaptureStudentSaveHandoff(finalize)
                assertEquals(
                    CaptureStudentSaveHandoffWriteOutcome.TRANSITIONED,
                    finalized.outcome,
                )
                assertEquals(CaptureStudentSaveHandoffState.FINALIZED, finalized.record.state)
                assertEquals(2L, finalized.record.stateVersion)
                assertEquals(
                    emptyList<CaptureStudentSaveHandoffRecord>(),
                    reopened.readPendingCaptureStudentSaveHandoffs(pendingQuery()),
                )
                assertEquals(
                    CaptureStudentSaveHandoffWriteOutcome.REPLAYED,
                    reopened.finalizeCaptureStudentSaveHandoff(
                        finalize.copy(finalizedAtEpochMillis = 9_500),
                    ).outcome,
                )
            }

            StudyDatabaseFactory.open(context, databaseName).use { reopenedAgain ->
                assertEquals(
                    emptyList<CaptureStudentSaveHandoffRecord>(),
                    reopenedAgain.readPendingCaptureStudentSaveHandoffs(pendingQuery()),
                )
                assertEquals(
                    CaptureStudentSaveHandoffWriteOutcome.REPLAYED,
                    reopenedAgain.prepareCaptureStudentSaveHandoff(prepare).outcome,
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun concurrentPrepareIsIdempotentAndDifferentFingerprintConflicts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        StudyDatabaseFactory.openInMemory(context).use { store ->
            val command = prepareCommand()
            val results = List(16) {
                async(Dispatchers.IO) {
                    store.prepareCaptureStudentSaveHandoff(command)
                }
            }.awaitAll()

            assertEquals(
                1,
                results.count { it.outcome == CaptureStudentSaveHandoffWriteOutcome.INSERTED },
            )
            assertEquals(
                15,
                results.count { it.outcome == CaptureStudentSaveHandoffWriteOutcome.REPLAYED },
            )
            assertEquals(1, results.map { it.record }.distinct().size)

            val failure = runCatching {
                store.prepareCaptureStudentSaveHandoff(
                    command.copy(intentCanonicalFingerprint = "e".repeat(64)),
                )
            }.exceptionOrNull()
            assertTrue(failure is CaptureStudentSaveHandoffConflictException)

            val otherProblemRef = command.targetProblemRef.copy(
                problemId = "student-problem:different",
            )
            val changedTargetFailure = runCatching {
                store.prepareCaptureStudentSaveHandoff(
                    command.copy(
                        targetProblemRef = otherProblemRef,
                        targetProblemRevisionRef = command.targetProblemRevisionRef.copy(
                            problem = otherProblemRef,
                        ),
                    ),
                )
            }.exceptionOrNull()
            assertTrue(changedTargetFailure is CaptureStudentSaveHandoffConflictException)

            val finalizeFailure = runCatching {
                store.finalizeCaptureStudentSaveHandoff(
                    finalizeCommand(command).copy(
                        intentCanonicalFingerprint = "f".repeat(64),
                    ),
                )
            }.exceptionOrNull()
            assertTrue(finalizeFailure is CaptureStudentSaveHandoffConflictException)
            assertEquals(
                listOf(results.first().record),
                store.readPendingCaptureStudentSaveHandoffs(pendingQuery()),
            )
        }
    }

    @Test
    fun journalOperationsNeverWriteLegacyProblemOrErrorBookTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "capture-student-handoff-boundary-${System.nanoTime()}.db"
        val prepare = prepareCommand()
        context.deleteDatabase(databaseName)
        try {
            StudyDatabaseFactory.open(context, databaseName).use { store ->
                store.prepareCaptureStudentSaveHandoff(prepare)
                store.finalizeCaptureStudentSaveHandoff(finalizeCommand(prepare))
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(1, database.rowCount(HANDOFF_TABLE))
                assertEquals(0, database.rowCount("problem"))
                assertEquals(0, database.rowCount("error_book_entry"))
                assertEquals(0, database.rowCount("problem_draft_commit_receipt"))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun studentOwnedTutorAckEndsSessionLifecycleWithoutLegacyBusinessRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "student-owned-session-ack-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            StudyDatabaseFactory.openPreCutoverForTest(context, databaseName).use { store ->
                store.readPendingCaptureStudentSaveHandoffs(
                    ReadPendingCaptureStudentSaveHandoffsQuery(LEARNER_ID),
                )
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    """
                    INSERT INTO canonical_source_asset (
                      source_asset_id, content_sha256, relative_path, mime_type, byte_size,
                      width, height, source_type, created_at_epoch_millis
                    ) VALUES (
                      'asset:student-ack', '${"1".repeat(64)}', 'captures/student-ack.jpg',
                      'image/jpeg', 100, 10, 10, 'CAMERA', 100
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO problem_draft (
                      draft_id, source_asset_id, origin, status, current_revision_number,
                      created_at_epoch_millis, updated_at_epoch_millis, request_fingerprint
                    ) VALUES (
                      'draft:student-ack', 'asset:student-ack', 'TUTOR', 'EDITING', 1,
                      100, 100, NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO problem_draft_revision (
                      draft_id, revision_number, basis_revision_number, subject, title,
                      question_document_snapshot, document_fingerprint, author,
                      created_at_epoch_millis
                    ) VALUES (
                      'draft:student-ack', 1, NULL, 'PHYSICS', '电磁学',
                      '{}', '${"4".repeat(64)}', 'USER', 100
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO tutor_session (
                      session_id, draft_id, draft_revision_number, created_at_epoch_millis
                    ) VALUES (
                      'session:student-ack', 'draft:student-ack', 1, 100
                    )
                    """.trimIndent(),
                )
            }

            val problem =
                StudentProblemRef(
                    learnerId = LEARNER_ID,
                    subject = SubjectKind.PHYSICS,
                    problemId = "student-problem:ack",
                    practiceUnitId = "student-practice:ack",
                )
            val command =
                AcknowledgeStudentOwnedCaptureSessionCommand(
                    learnerId = LEARNER_ID,
                    source =
                        StudentOwnedTutorCaptureSessionAckSource(
                            intentId = "student-capture-save:ack",
                            sourceCanonicalFingerprint = "3".repeat(64),
                            saveRequestId = "save:ack",
                            sessionId = "session:student-ack",
                            draftId = "draft:student-ack",
                            draftRevisionNumber = 1,
                            occurredAtEpochMillis = 200,
                        ),
                    targetProblem = problem,
                    targetRevision =
                        StudentProblemRevisionRef(
                            problem = problem,
                            revisionId = "student-revision:ack",
                            revisionNumber = 1,
                            documentCanonicalFingerprint = "4".repeat(64),
                        ),
                    targetSaveReceiptFingerprint = "5".repeat(64),
                    acknowledgedAtEpochMillis = 200,
                )

            StudyDatabaseFactory.openPreCutoverForTest(context, databaseName).use { store ->
                assertTrue(store.acknowledgeStudentOwnedCaptureSession(command).created)
                assertTrue(!store.acknowledgeStudentOwnedCaptureSession(command).created)
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(
                    "COMMITTED",
                    database.textValue(
                        "SELECT status FROM problem_draft WHERE draft_id = 'draft:student-ack'",
                    ),
                )
                assertEquals(1, database.rowCount(HANDOFF_TABLE))
                assertEquals(1, database.rowCount("problem_draft"))
                assertEquals(1, database.rowCount("problem_draft_revision"))
                assertEquals(1, database.rowCount("tutor_session"))
                assertEquals(0, database.rowCount("problem"))
                assertEquals(0, database.rowCount("problem_revision"))
                assertEquals(0, database.rowCount("practice_unit"))
                assertEquals(0, database.rowCount("error_book_entry"))
                assertEquals(0, database.rowCount("problem_draft_commit_receipt"))
                assertEquals(0, database.rowCount("knowledge_node"))
                assertEquals(0, database.rowCount("learner_knowledge_mastery_state"))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun exactReceiptHandoffLookupUsesPointIndexesWithoutScanningLegacyTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "capture-student-handoff-plan-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            StudyDatabaseFactory.open(context, databaseName).use { store ->
                store.readPendingCaptureStudentSaveHandoffs(
                    ReadPendingCaptureStudentSaveHandoffsQuery(LEARNER_ID),
                )
            }

            val plans =
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { database ->
                    listOf(
                        database.exactCaptureHandoffQueryPlan(),
                        database.exactCaptureAssetQueryPlan(),
                        database.exactDraftAssetQueryPlan(),
                    )
                }
            val plan = plans.flatten()

            listOf(
                "receipt",
                "draft",
                "draft_revision",
                "primary_draft_asset",
                "problem",
                "revision",
                "unit",
                "entry",
                "handoff",
                "tutor",
                "link",
                "asset",
                "draft_asset",
            )
                .forEach { alias ->
                    assertTrue(
                        "Expected an indexed point lookup for $alias, plan=$plan",
                        plan.any { detail -> detail.contains("SEARCH $alias") },
                    )
                    assertTrue(
                        "Unexpected scan for $alias, plan=$plan",
                        plan.none { detail -> detail.contains("SCAN $alias") },
                    )
                }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun prepareCommand(): PrepareCaptureStudentSaveHandoffCommand {
        val problemRef = StudentProblemRef(
            learnerId = LEARNER_ID,
            subject = SubjectKind.PHYSICS,
            problemId = "student-problem:electromagnetism:42",
            practiceUnitId = "practice-unit:electromagnetism:42",
        )
        return PrepareCaptureStudentSaveHandoffCommand(
            intentId = "capture-save-intent:42",
            intentCanonicalFingerprint = "a".repeat(64),
            learnerId = LEARNER_ID,
            draftId = "capture-draft:42",
            draftRevisionNumber = 3,
            sessionId = "tutor-session:42",
            targetProblemRef = problemRef,
            targetProblemRevisionRef = StudentProblemRevisionRef(
                problem = problemRef,
                revisionId = "student-revision:42:3",
                revisionNumber = 3,
                documentCanonicalFingerprint = "b".repeat(64),
            ),
            preparedAtEpochMillis = 1_000,
        )
    }

    private fun finalizeCommand(
        prepare: PrepareCaptureStudentSaveHandoffCommand,
    ) = FinalizeCaptureStudentSaveHandoffCommand(
        intentId = prepare.intentId,
        intentCanonicalFingerprint = prepare.intentCanonicalFingerprint,
        learnerId = prepare.learnerId,
        targetSaveReceiptFingerprint = "c".repeat(64),
        finalizedAtEpochMillis = 2_000,
    )

    private fun pendingQuery() = ReadPendingCaptureStudentSaveHandoffsQuery(
        learnerId = LEARNER_ID,
    )

    private fun SQLiteDatabase.rowCount(tableName: String): Int =
        rawQuery("SELECT COUNT(*) FROM `$tableName`", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SQLiteDatabase.textValue(sql: String): String =
        rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SQLiteDatabase.indexNames(tableName: String): Set<String> =
        rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ? " +
                "AND name NOT LIKE 'sqlite_autoindex_%'",
            arrayOf(tableName),
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }

    private fun SQLiteDatabase.exactCaptureHandoffQueryPlan(): List<String> =
        rawQuery(
            """
            EXPLAIN QUERY PLAN
            SELECT entry.entry_id
            FROM problem_draft_commit_receipt AS receipt
            JOIN problem_draft AS draft
              ON draft.draft_id = receipt.draft_id
             AND draft.current_revision_number = receipt.draft_revision_number
             AND draft.status = 'COMMITTED'
            JOIN problem_draft_revision AS draft_revision
              ON draft_revision.draft_id = receipt.draft_id
             AND draft_revision.revision_number = receipt.draft_revision_number
            JOIN problem_draft_source_asset AS primary_draft_asset
              ON primary_draft_asset.draft_id = receipt.draft_id
             AND primary_draft_asset.page_index = 0
             AND primary_draft_asset.source_asset_id = draft.source_asset_id
            JOIN problem AS problem
              ON problem.problem_id = receipt.problem_id
             AND problem.subject = draft_revision.subject
            JOIN problem_revision AS revision
              ON revision.revision_id = receipt.problem_revision_id
             AND revision.problem_id = receipt.problem_id
            JOIN practice_unit AS unit
              ON unit.practice_unit_id = receipt.practice_unit_id
             AND unit.problem_id = receipt.problem_id
             AND unit.problem_revision_id = receipt.problem_revision_id
            JOIN error_book_entry AS entry
              ON entry.entry_id = receipt.error_book_entry_id
             AND entry.practice_unit_id = receipt.practice_unit_id
             AND entry.problem_id = receipt.problem_id
             AND entry.current_revision_id = receipt.problem_revision_id
            JOIN capture_student_save_handoff AS handoff
              ON handoff.intent_id = receipt.command_id
             AND handoff.intent_canonical_fingerprint = receipt.payload_fingerprint
             AND handoff.learner_id = ?
             AND handoff.draft_id = receipt.draft_id
             AND handoff.draft_revision_number = receipt.draft_revision_number
             AND handoff.target_subject = problem.subject
             AND handoff.target_problem_id = receipt.problem_id
             AND handoff.target_practice_unit_id = receipt.practice_unit_id
             AND handoff.target_revision_id = receipt.problem_revision_id
             AND handoff.target_revision_number = revision.revision_number
             AND handoff.target_document_canonical_fingerprint = revision.content_fingerprint
             AND handoff.prepared_at_epoch_millis = receipt.committed_at_epoch_millis
            LEFT JOIN tutor_session AS tutor
              ON tutor.draft_id = receipt.draft_id
            WHERE receipt.command_id = ?
              AND receipt.payload_fingerprint = ?
              AND receipt.draft_id = ?
              AND receipt.draft_revision_number = ?
              AND tutor.session_id = ?
              AND handoff.session_id = ?
              AND receipt.problem_id = ?
              AND receipt.problem_revision_id = ?
              AND receipt.practice_unit_id = ?
              AND receipt.error_book_entry_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(
                LEARNER_ID,
                "intent:plan",
                "a".repeat(64),
                "draft:plan",
                "1",
                "session:plan",
                "session:plan",
                "problem:plan",
                "revision:plan",
                "practice:plan",
                "entry:plan",
            ),
        ).use { cursor ->
            val detailColumn = cursor.getColumnIndexOrThrow("detail")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(detailColumn))
            }
        }

    private fun SQLiteDatabase.exactCaptureAssetQueryPlan(): List<String> =
        rawQuery(
            """
            EXPLAIN QUERY PLAN
            SELECT link.source_asset_id, asset.content_sha256
            FROM problem_draft_source_asset AS draft_asset
            JOIN problem_revision_source_asset AS link
              ON link.source_asset_id = draft_asset.source_asset_id
             AND link.problem_revision_id = ?
             AND link.role = 'QUESTION_SOURCE'
            LEFT JOIN canonical_source_asset AS asset
              ON asset.source_asset_id = link.source_asset_id
            WHERE draft_asset.draft_id = ?
            ORDER BY draft_asset.page_index ASC
            LIMIT 257
            """.trimIndent(),
            arrayOf("revision:plan", "draft:plan"),
        ).queryPlanDetails()

    private fun SQLiteDatabase.exactDraftAssetQueryPlan(): List<String> =
        rawQuery(
            """
            EXPLAIN QUERY PLAN
            SELECT page_index, source_asset_id
            FROM problem_draft_source_asset AS draft_asset
            WHERE draft_asset.draft_id = ?
            ORDER BY draft_asset.page_index ASC
            LIMIT 257
            """.trimIndent(),
            arrayOf("draft:plan"),
        ).queryPlanDetails()

    private fun android.database.Cursor.queryPlanDetails(): List<String> = use { cursor ->
        val detailColumn = cursor.getColumnIndexOrThrow("detail")
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(detailColumn))
        }
    }

    private companion object {
        const val LEARNER_ID = "learner:local"
        const val HANDOFF_TABLE = "capture_student_save_handoff"
        const val PENDING_INDEX =
            "index_capture_student_save_handoff_learner_id_state_" +
                "prepared_at_epoch_millis_intent_id"
    }
}
