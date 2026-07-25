package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.database.TutorAnswerExposureRecord
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureSurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
