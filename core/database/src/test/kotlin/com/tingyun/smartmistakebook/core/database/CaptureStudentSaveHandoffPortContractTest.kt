package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class CaptureStudentSaveHandoffPortContractTest {
    @Test
    fun publicAbiContainsOnlyPrepareFinalizeAndPendingRead() {
        val expected = setOf("prepare", "finalize", "readPending")
        val interfaceMethods =
            CaptureStudentSaveHandoffJournalPort::class.java.methods.domainMethodNames()
        val adapterMethods =
            StudyDatabaseCaptureStudentSaveHandoffJournalAdapter::class.java.methods
                .domainMethodNames()

        assertEquals(expected, interfaceMethods)
        assertEquals(expected, adapterMethods)
        assertFalse(
            (interfaceMethods + adapterMethods).any { methodName ->
                FORBIDDEN_METHOD_FRAGMENTS.any(methodName.lowercase()::contains)
            },
        )
        assertFalse(
            StudyDatabaseCaptureStudentSaveHandoffJournalAdapter::class.java.constructors
                .flatMap { constructor -> constructor.parameterTypes.asList() }
                .contains(StudyDatabasePort::class.java),
        )
    }

    @Test
    fun adapterForwardsOnlyInjectedHandoffCapabilities() {
        val prepare = prepareCommand()
        val preparedRecord = prepare.toRecord()
        val preparedResult = CaptureStudentSaveHandoffWriteResult(
            CaptureStudentSaveHandoffWriteOutcome.INSERTED,
            preparedRecord,
        )
        val finalize = FinalizeCaptureStudentSaveHandoffCommand(
            intentId = prepare.intentId,
            intentCanonicalFingerprint = prepare.intentCanonicalFingerprint,
            learnerId = prepare.learnerId,
            targetSaveReceiptFingerprint = "d".repeat(64),
            finalizedAtEpochMillis = 200,
        )
        val finalizedResult = CaptureStudentSaveHandoffWriteResult(
            CaptureStudentSaveHandoffWriteOutcome.TRANSITIONED,
            preparedRecord.copy(
                state = CaptureStudentSaveHandoffState.FINALIZED,
                finalizedAtEpochMillis = finalize.finalizedAtEpochMillis,
                targetSaveReceiptFingerprint = finalize.targetSaveReceiptFingerprint,
                stateVersion = CaptureStudentSaveHandoffRecord.FINALIZED_STATE_VERSION,
            ),
        )
        val query = ReadPendingCaptureStudentSaveHandoffsQuery(prepare.learnerId)
        var forwardedPrepare: PrepareCaptureStudentSaveHandoffCommand? = null
        var forwardedFinalize: FinalizeCaptureStudentSaveHandoffCommand? = null
        var forwardedQuery: ReadPendingCaptureStudentSaveHandoffsQuery? = null
        val adapter = StudyDatabaseCaptureStudentSaveHandoffJournalAdapter(
            prepareHandoff = {
                forwardedPrepare = it
                preparedResult
            },
            finalizeHandoff = {
                forwardedFinalize = it
                finalizedResult
            },
            readPendingHandoffs = {
                forwardedQuery = it
                listOf(preparedRecord)
            },
        )

        assertSame(preparedResult, runImmediately { adapter.prepare(prepare) })
        assertSame(finalizedResult, runImmediately { adapter.finalize(finalize) })
        assertEquals(listOf(preparedRecord), runImmediately { adapter.readPending(query) })
        assertSame(prepare, forwardedPrepare)
        assertSame(finalize, forwardedFinalize)
        assertSame(query, forwardedQuery)
    }

    private fun prepareCommand(): PrepareCaptureStudentSaveHandoffCommand {
        val problemRef = StudentProblemRef(
            learnerId = "learner:local",
            subject = SubjectKind.MATH,
            problemId = "problem:target",
            practiceUnitId = "practice:target",
        )
        return PrepareCaptureStudentSaveHandoffCommand(
            intentId = "intent:save:1",
            intentCanonicalFingerprint = "a".repeat(64),
            learnerId = problemRef.learnerId,
            draftId = "draft:capture:1",
            draftRevisionNumber = 2,
            sessionId = "session:tutor:1",
            targetProblemRef = problemRef,
            targetProblemRevisionRef = StudentProblemRevisionRef(
                problem = problemRef,
                revisionId = "revision:target:2",
                revisionNumber = 2,
                documentCanonicalFingerprint = "b".repeat(64),
            ),
            preparedAtEpochMillis = 100,
        )
    }

    private fun PrepareCaptureStudentSaveHandoffCommand.toRecord() =
        CaptureStudentSaveHandoffRecord(
            intentId = intentId,
            intentCanonicalFingerprint = intentCanonicalFingerprint,
            learnerId = learnerId,
            draftId = draftId,
            draftRevisionNumber = draftRevisionNumber,
            sessionId = sessionId,
            targetProblemRef = targetProblemRef,
            targetProblemRevisionRef = targetProblemRevisionRef,
            state = CaptureStudentSaveHandoffState.PREPARED,
            preparedAtEpochMillis = preparedAtEpochMillis,
            finalizedAtEpochMillis = null,
            targetSaveReceiptFingerprint = null,
            stateVersion = CaptureStudentSaveHandoffRecord.PREPARED_STATE_VERSION,
            schemaVersion = schemaVersion,
        )

    private fun Array<Method>.domainMethodNames(): Set<String> =
        filterNot { method -> method.declaringClass == Any::class.java }
            .mapTo(linkedSetOf()) { method -> method.name }

    private fun <T> runImmediately(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return outcome?.getOrThrow()
            ?: error("The handoff capability unexpectedly suspended")
    }

    private companion object {
        val FORBIDDEN_METHOD_FRAGMENTS = setOf(
            "delete",
            "problemwrite",
            "mistakewrite",
            "errorbook",
            "mastery",
            "knowledge",
            "sql",
            "dao",
        )
    }
}
