package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.database.AcknowledgeTutorLearningEvidenceSessionCommand
import com.tingyun.smartmistakebook.core.database.BeginTutorLearningEvidenceSessionIntentCommand
import com.tingyun.smartmistakebook.core.database.TutorLearningEvidenceSessionDatabasePort
import com.tingyun.smartmistakebook.core.database.TutorLearningEvidenceSessionAcknowledgeDatabaseResult
import com.tingyun.smartmistakebook.core.database.TutorLearningEvidenceSessionRecord
import com.tingyun.smartmistakebook.core.database.TutorLearningEvidenceSessionRecordState
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTutorLearningEvidenceSessionAdapterTest {
    @Test
    fun mapsPendingIntentAndOpaqueMasteryAcknowledgement() = runBlocking {
        val database = FakeSessionDatabase()
        val adapter = RoomTutorLearningEvidenceSessionAdapter(database)

        val begun = adapter.begin(intent())
        val acknowledged =
            adapter.acknowledge(
                TutorLearningEvidenceSessionAcknowledgement(
                    scope = intent().scope,
                    evidenceRequestId = EVIDENCE_REQUEST_ID,
                    candidateFingerprint = hash('a'),
                    masteryReceiptId = "mastery-receipt-1",
                    masteryReceiptFingerprint = hash('b'),
                ),
            )

        assertTrue(begun is TutorLearningEvidenceSessionBeginResult.Pending)
        assertTrue(
            acknowledged is TutorLearningEvidenceSessionAcknowledgeResult.Acknowledged,
        )
        assertEquals(EVIDENCE_REQUEST_ID, database.beginCommand?.evidenceRequestId)
        assertEquals("mastery-receipt-1", database.acknowledgeCommand?.masteryReceiptId)
    }

    @Test
    fun acknowledgedIntentReplaysWithoutOpeningMastery() = runBlocking {
        val database =
            FakeSessionDatabase(
                initial =
                    record().copy(
                        state = TutorLearningEvidenceSessionRecordState.MASTERY_ACKNOWLEDGED,
                        masteryReceiptId = "mastery-receipt-1",
                        masteryReceiptFingerprint = hash('b'),
                        stateVersion = 1,
                        acknowledgedAtEpochMillis = 20,
                    ),
            )

        val result = RoomTutorLearningEvidenceSessionAdapter(database).begin(intent())

        assertTrue(result is TutorLearningEvidenceSessionBeginResult.Finalized)
        result as TutorLearningEvidenceSessionBeginResult.Finalized
        assertEquals("mastery-receipt-1", result.receipt.masteryReceiptId)
    }

    private class FakeSessionDatabase(
        initial: TutorLearningEvidenceSessionRecord = record(),
    ) : TutorLearningEvidenceSessionDatabasePort {
        private var record = initial
        var beginCommand: BeginTutorLearningEvidenceSessionIntentCommand? = null
        var acknowledgeCommand: AcknowledgeTutorLearningEvidenceSessionCommand? = null

        override suspend fun beginTutorLearningEvidenceSessionIntent(
            command: BeginTutorLearningEvidenceSessionIntentCommand,
        ): TutorLearningEvidenceSessionRecord {
            beginCommand = command
            return record
        }

        override suspend fun acknowledgeTutorLearningEvidenceSession(
            command: AcknowledgeTutorLearningEvidenceSessionCommand,
        ): TutorLearningEvidenceSessionAcknowledgeDatabaseResult {
            acknowledgeCommand = command
            record =
                record.copy(
                    state = TutorLearningEvidenceSessionRecordState.MASTERY_ACKNOWLEDGED,
                    masteryReceiptId = command.masteryReceiptId,
                    masteryReceiptFingerprint = command.masteryReceiptFingerprint,
                    stateVersion = 1,
                    acknowledgedAtEpochMillis = 20,
                )
            return TutorLearningEvidenceSessionAcknowledgeDatabaseResult(
                replayed = false,
                record = record,
            )
        }
    }

    private companion object {
        const val LEARNER_ID = "learner-1"
        const val EVIDENCE_REQUEST_ID = "evidence-1"

        fun intent() =
            TutorLearningEvidenceSessionIntent(
                scope =
                    TutorLearningEvidenceSessionScope(
                        learnerId = LEARNER_ID,
                        conversationId = "conversation-1",
                        conversationGeneration = 1,
                        turnReceiptId = "turn-1",
                    ),
                evidenceRequestId = EVIDENCE_REQUEST_ID,
                conversationStateVersion = 3,
                turnOrdinal = 1,
                subject = SubjectKind.MATH,
                sessionAnchorId = "anchor-1",
                kind = TutorEvidenceRequestKind.CHOICE,
                requestVersion = 1,
                modeVersion = 2,
                idempotencyKey = "submit-1",
                candidateFingerprint = hash('a'),
                state = TutorLearningEvidenceSessionState.PENDING_MASTERY,
            )

        fun record() =
            TutorLearningEvidenceSessionRecord(
                learnerId = LEARNER_ID,
                evidenceRequestId = EVIDENCE_REQUEST_ID,
                conversationId = "conversation-1",
                conversationGeneration = 1,
                conversationStateVersion = 3,
                turnReceiptId = "turn-1",
                turnOrdinal = 1,
                subject = SubjectKind.MATH,
                sessionAnchorId = "anchor-1",
                evidenceKind = TutorEvidenceRequestKind.CHOICE,
                requestVersion = 1,
                modeVersion = 2,
                idempotencyKey = "submit-1",
                candidateFingerprint = hash('a'),
                state = TutorLearningEvidenceSessionRecordState.PENDING_MASTERY,
                masteryReceiptId = null,
                masteryReceiptFingerprint = null,
                stateVersion = 0,
                intentCreatedAtEpochMillis = 10,
                acknowledgedAtEpochMillis = null,
            )

        fun hash(character: Char): String = character.toString().repeat(64)
    }
}
