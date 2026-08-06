package com.tingyun.smartmistakebook.core.student.mistake.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentCaptureSaveInstrumentedTest {
    @Test
    fun captureSaveAtomicallyCommitsBusinessReceiptAndReplayableSessionAck() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE_NAME)
        val store = StudentMistakeStoreFactory.openForTest(context, TEST_DATABASE_NAME)
        val captures = store.captureSavesForLearner(LEARNER_ID)
        try {
            val command = captureCommand()

            val created = captures.save(command)
            val duplicate = captures.save(command)

            assertEquals(TargetConfirmedStudentMistakeSaveOutcome.CREATED, created.outcome)
            assertEquals(TargetConfirmedStudentMistakeSaveOutcome.DUPLICATE, duplicate.outcome)
            assertEquals(command.target.problem.revision, created.handoff.targetRevision)
            assertEquals(
                command.target.problem.revision,
                store.findProblem(command.target.problem.revision.problem)?.revision,
            )
            assertEquals(
                listOf(command.source.intentId),
                captures.readPending().map { it.source.intentId },
            )

            val acknowledged =
                captures.acknowledge(
                    AcknowledgeStudentCaptureSaveHandoffCommand(
                        intentId = command.source.intentId,
                        sourceCanonicalFingerprint =
                            command.source.sourceCanonicalFingerprint,
                        targetCanonicalFingerprint =
                            command.target.targetCanonicalFingerprint,
                        acknowledgedAtEpochMillis = command.source.occurredAtEpochMillis,
                    ),
                )

            assertEquals(command.source.occurredAtEpochMillis, acknowledged.acknowledgedAtEpochMillis)
            assertTrue(captures.readPending().isEmpty())
            assertEquals(
                acknowledged,
                captures.readByDraftIds(setOf(command.source.draftId)).single(),
            )
            store.setCollectionState(
                SetStudentProblemCollectionCommand(
                    problem = command.target.problem.revision.problem,
                    mistakeState = StudentMistakeEntryState.ACTIVE,
                    favorite = true,
                    changedAtEpochMillis = 2_000L,
                ),
            )
            val replayedAfterMutableChange = captures.save(command)
            assertEquals(
                TargetConfirmedStudentMistakeSaveOutcome.DUPLICATE,
                replayedAfterMutableChange.outcome,
            )
            assertEquals(acknowledged, replayedAfterMutableChange.handoff)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(TEST_DATABASE_NAME).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                listOf(
                    "student_problem_document",
                    "student_problem_revision",
                    "student_practice_unit",
                    "student_problem_collection",
                    "student_review_candidate",
                    "student_mistake_save_receipt",
                    "student_capture_save_handoff",
                    "student_store_outbox",
                ).forEach { table ->
                    assertEquals("$table must be written exactly once", 1, database.rowCount(table))
                }
            }
        } finally {
            store.close()
            context.deleteDatabase(TEST_DATABASE_NAME)
        }
    }

    @Test
    fun captureSourceCollisionRollsBackDifferentStudentTarget() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE_NAME)
        val store = StudentMistakeStoreFactory.openForTest(context, TEST_DATABASE_NAME)
        val captures = store.captureSavesForLearner(LEARNER_ID)
        try {
            val first = captureCommand()
            val conflicting =
                captureCommand(
                    intentId = "commit-2",
                    sourceFingerprint = "d".repeat(64),
                    problemId = "problem-2",
                    revisionId = "revision-2",
                    practiceUnitId = "practice-2",
                    errorBookEntryId = "entry-2",
                )
            captures.save(first)

            val result = runCatching { captures.save(conflicting) }

            assertTrue(result.isFailure)
            assertNull(store.findProblem(conflicting.target.problem.revision.problem))
            assertEquals(
                listOf(first.source.intentId),
                captures.readByDraftIds(setOf(first.source.draftId)).map { it.source.intentId },
            )
        } finally {
            store.close()
            context.deleteDatabase(TEST_DATABASE_NAME)
        }
    }

    private fun captureCommand(
        intentId: String = "commit-1",
        sourceFingerprint: String = "a".repeat(64),
        problemId: String = "problem-1",
        revisionId: String = "revision-1",
        practiceUnitId: String = "practice-1",
        errorBookEntryId: String = "entry-1",
    ): SaveStudentOwnedCaptureCommand {
        val source =
            LibraryStudentCaptureSaveSource(
                intentId = intentId,
                sourceCanonicalFingerprint = sourceFingerprint,
                draftId = "draft-1",
                basisRevisionNumber = 2,
                workspaceVersion = 3,
                workspaceCanonicalFingerprint = "b".repeat(64),
                confirmationRequestId = "confirm-1",
                occurredAtEpochMillis = 1_000L,
            )
        val problem =
            StudentProblemRef(
                learnerId = LEARNER_ID,
                subject = SubjectKind.MATH,
                problemId = problemId,
                practiceUnitId = practiceUnitId,
            )
        val revision =
            StudentProblemRevisionRef(
                problem = problem,
                revisionId = revisionId,
                revisionNumber = 1,
                documentCanonicalFingerprint = "c".repeat(64),
            )
        return SaveStudentOwnedCaptureCommand(
            source = source,
            target =
                SaveTargetConfirmedStudentMistakeCommand(
                    problem =
                        CommitStudentProblemCommand(
                            revision = revision,
                            title = "函数",
                            stemMarkdown = "求函数值。",
                            practiceUnitKind = StudentPracticeUnitKind.WHOLE_PROBLEM,
                            practiceUnitTitle = "函数",
                            itemFamilyId = problemId,
                            estimatedDurationSeconds = 180,
                            sourceBundleId = null,
                            partIds = emptyList(),
                            originalImages = emptyList(),
                            committedAtEpochMillis = source.occurredAtEpochMillis,
                            errorBookEntryId = errorBookEntryId,
                        ),
                    confirmedAtEpochMillis = source.occurredAtEpochMillis,
                ),
        )
    }

    private companion object {
        const val LEARNER_ID = "learner-local"
        const val TEST_DATABASE_NAME = "student-capture-save.student-mistake-test.db"
    }
}

private fun SQLiteDatabase.rowCount(tableName: String): Int {
    require(tableName.matches(Regex("[a-z_]+")))
    return rawQuery("SELECT COUNT(*) FROM `$tableName`", null).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }
}
