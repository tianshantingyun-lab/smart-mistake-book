package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.database.TutorAnswerExposureRecord
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureSurfaceKind
import com.tingyun.smartmistakebook.core.domain.TutorEvidenceRejectedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.runBlocking

class RoomTutorInteractionRepositoryTest {
    @Test
    fun `answer exposure requires the exact learner question revision and turn`() {
        val key = TutorAnswerExposureKey(
            sessionId = "session-1",
            questionDocumentId = "question-1",
            revisionNumber = 2,
            cycleOrdinal = 3,
            turnOrdinal = 4,
            surfaceKind = TutorAnswerExposureSurfaceKind.RESPOND_REPLY,
            modelTaskRequestId = "respond-request-4",
            responseOrdinal = 4,
        )
        val record = TutorAnswerExposureRecord(
            exposureId = "exposure-1",
            learnerId = "learner:local",
            sessionId = key.sessionId,
            questionDocumentId = key.questionDocumentId,
            questionRevisionNumber = key.revisionNumber,
            cycleOrdinal = key.cycleOrdinal,
            turnOrdinal = key.turnOrdinal,
            surfaceKind = key.surfaceKind.name,
            modelTaskRequestId = key.modelTaskRequestId,
            responseOrdinal = key.responseOrdinal,
            exposedAtEpochMillis = 1_000,
            outcomeId = null,
        )

        assertTrue(record.matchesAnswerExposure("learner:local", key))
        assertFalse(record.matchesAnswerExposure("learner:other", key))
        assertFalse(
            record.copy(sessionId = "session-2")
                .matchesAnswerExposure("learner:local", key),
        )
        assertFalse(
            record.copy(questionDocumentId = "question-2")
                .matchesAnswerExposure("learner:local", key),
        )
        assertFalse(
            record.copy(questionRevisionNumber = 3)
                .matchesAnswerExposure("learner:local", key),
        )
        assertFalse(
            record.copy(cycleOrdinal = 4)
                .matchesAnswerExposure("learner:local", key),
        )
        assertFalse(
            record.copy(turnOrdinal = 5)
                .matchesAnswerExposure("learner:local", key),
        )
        assertFalse(
            record.copy(modelTaskRequestId = "respond-request-other")
                .matchesAnswerExposure("learner:local", key),
        )
        assertFalse(
            record.copy(responseOrdinal = 5)
                .matchesAnswerExposure("learner:local", key),
        )
    }

    @Test
    fun `batch exposure matching returns only exact requested identities`() {
        val exact = TutorAnswerExposureKey(
            sessionId = "session-1",
            questionDocumentId = "question-1",
            revisionNumber = 2,
            cycleOrdinal = 3,
            turnOrdinal = 4,
            surfaceKind = TutorAnswerExposureSurfaceKind.RESPOND_REPLY,
            modelTaskRequestId = "respond-request-4",
            responseOrdinal = 4,
        )
        val record = TutorAnswerExposureRecord(
            exposureId = "exposure-1",
            learnerId = "learner:local",
            sessionId = exact.sessionId,
            questionDocumentId = exact.questionDocumentId,
            questionRevisionNumber = exact.revisionNumber,
            cycleOrdinal = exact.cycleOrdinal,
            turnOrdinal = exact.turnOrdinal,
            surfaceKind = exact.surfaceKind.name,
            modelTaskRequestId = exact.modelTaskRequestId,
            responseOrdinal = exact.responseOrdinal,
            exposedAtEpochMillis = 1_000,
            outcomeId = null,
        )
        val sameRequestWrongTurn = exact.copy(turnOrdinal = 9)
        val absent = exact.copy(
            modelTaskRequestId = "respond-request-5",
            responseOrdinal = 5,
        )

        val matches = listOf(record).matchingAnswerExposureKeys(
            expectedLearnerId = "learner:local",
            candidates = setOf(exact, sameRequestWrongTurn, absent),
        )

        assertEquals(setOf(exact), matches)
    }

    @Test
    fun `revoked request compensates a non cooperative late persistence completion`() = runBlocking {
        val gate = TutorEvidenceWriteGate()
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var stored = false

        supervisorScope {
            val lateWrite = async {
                gate.persist(
                    requestId = "evidence-3",
                    write = {
                        writeStarted.complete(Unit)
                        releaseWrite.await()
                        stored = true
                        "stored"
                    },
                    discard = { stored = false },
                )
            }

            writeStarted.await()
            assertTrue(gate.cancel("evidence-3"))
            releaseWrite.complete(Unit)

            val failure = runCatching { lateWrite.await() }.exceptionOrNull()
            assertTrue(failure is TutorEvidenceRejectedException)
            assertFalse(stored)
        }
    }

    @Test
    fun `cancel reports whether it won the atomic evidence finalization race`() = runBlocking {
        val gate = TutorEvidenceWriteGate()

        assertEquals(
            "stored",
            gate.persist(
                requestId = "evidence-finalized",
                write = { "stored" },
                discard = { error("Finalized evidence must not be discarded") },
            ),
        )

        assertFalse(gate.cancel("evidence-finalized"))
        assertTrue(gate.cancel("evidence-never-started"))
        val failure = runCatching {
            gate.persist(
                requestId = "evidence-never-started",
                write = { error("Revoked evidence must not start writing") },
                discard = {},
            )
        }.exceptionOrNull()
        assertTrue(failure is TutorEvidenceRejectedException)
    }
}
