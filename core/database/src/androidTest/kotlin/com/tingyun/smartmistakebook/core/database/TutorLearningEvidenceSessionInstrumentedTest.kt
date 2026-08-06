package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorLearningEvidenceSessionInstrumentedTest {
    @Test
    fun intentAndMasteryAcknowledgementReplayWithoutLegacyLearningFact() = runBlocking {
        var now = 100L
        val store = StudyDatabaseFactory.openInMemory(context()) { now }
        try {
            preparePendingEvidence(store)
            val capability = StudyDatabaseFactory.authorizeTutorSession(store)
            val intent = beginCommand()
            val oldFactCount = store.database.tutorLearningMemoryDao().countSourceFacts()

            val first = capability.beginTutorLearningEvidenceSessionIntent(intent)
            val replay = capability.beginTutorLearningEvidenceSessionIntent(intent)
            now = 200L
            val acknowledgement = acknowledgeCommand()
            val acknowledged =
                capability.acknowledgeTutorLearningEvidenceSession(acknowledgement)
            val acknowledgedReplay =
                capability.acknowledgeTutorLearningEvidenceSession(acknowledgement)

            assertEquals(TutorLearningEvidenceSessionRecordState.PENDING_MASTERY, first.state)
            assertEquals(first, replay)
            assertFalse(acknowledged.replayed)
            assertTrue(acknowledgedReplay.replayed)
            assertEquals(
                TutorLearningEvidenceSessionRecordState.MASTERY_ACKNOWLEDGED,
                acknowledged.record.state,
            )
            assertEquals("mastery-receipt-1", acknowledged.record.masteryReceiptId)
            val reopened =
                capability.openTutorEvidenceRequest(
                    learnerId = LEARNER_ID,
                    evidenceRequestId = EVIDENCE_REQUEST_ID,
                )
            assertEquals(TutorEvidenceRequestStatus.SUBMITTED, reopened?.status)
            assertEquals(1L, reopened?.stateVersion)
            assertEquals(200L, reopened?.resolvedAtEpochMillis)
            assertEquals("mastery-receipt-1", reopened?.terminalReceiptId)
            val legacyRequest =
                store.database.tutorLearningMemoryDao()
                    .openEvidenceRequest(LEARNER_ID, EVIDENCE_REQUEST_ID)
            assertEquals(TutorEvidenceRequestStatus.PENDING.name, legacyRequest?.status)
            assertNull(legacyRequest?.terminalSourceFactId)
            assertEvidenceConflict {
                capability.cancelTutorEvidenceRequest(cancelCommand())
            }
            assertEquals(
                oldFactCount,
                store.database.tutorLearningMemoryDao().countSourceFacts(),
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun cancellationBeforeIntentWinsWithoutCreatingMasterySession() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(context()) { 100L }
        try {
            preparePendingEvidence(store)
            val capability = StudyDatabaseFactory.authorizeTutorSession(store)
            val oldFactCount = store.database.tutorLearningMemoryDao().countSourceFacts()

            val cancelled = capability.cancelTutorEvidenceRequest(cancelCommand())

            assertEquals(TutorEvidenceRequestStatus.CANCELLED, cancelled.request.status)
            assertSessionConflict {
                capability.beginTutorLearningEvidenceSessionIntent(beginCommand())
            }
            assertEquals(
                oldFactCount,
                store.database.tutorLearningMemoryDao().countSourceFacts(),
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun intentBeforeCancellationFencesLegacyTerminalCasAndCanBeAcknowledged() = runBlocking {
        var now = 100L
        val store = StudyDatabaseFactory.openInMemory(context()) { now }
        try {
            preparePendingEvidence(store)
            val capability = StudyDatabaseFactory.authorizeTutorSession(store)
            val oldFactCount = store.database.tutorLearningMemoryDao().countSourceFacts()

            capability.beginTutorLearningEvidenceSessionIntent(beginCommand())
            assertEvidenceConflict {
                capability.cancelTutorEvidenceRequest(cancelCommand())
            }
            assertEquals(
                TutorEvidenceRequestStatus.PENDING,
                capability.openTutorEvidenceRequest(LEARNER_ID, EVIDENCE_REQUEST_ID)?.status,
            )
            assertNull(
                capability.openTutorEvidenceRequest(
                    LEARNER_ID,
                    EVIDENCE_REQUEST_ID,
                )?.terminalReceiptId,
            )

            now = 200L
            capability.acknowledgeTutorLearningEvidenceSession(acknowledgeCommand())
            val reopened =
                capability.openTutorEvidenceRequest(LEARNER_ID, EVIDENCE_REQUEST_ID)

            assertEquals(TutorEvidenceRequestStatus.SUBMITTED, reopened?.status)
            assertEquals("mastery-receipt-1", reopened?.terminalReceiptId)
            assertEquals(
                oldFactCount,
                store.database.tutorLearningMemoryDao().countSourceFacts(),
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun archiveAfterIntentCannotCancelItAndCrashReplayCanFinishAcknowledgement() = runBlocking {
        var now = 100L
        val store = StudyDatabaseFactory.openInMemory(context()) { now }
        try {
            preparePendingEvidence(store)
            val capability = StudyDatabaseFactory.authorizeTutorSession(store)
            val intent = capability.beginTutorLearningEvidenceSessionIntent(beginCommand())

            now = 150L
            val archived = capability.archiveTutorConversation(archiveCommand())

            assertEquals(TutorConversationStatus.ARCHIVED, archived.conversation.status)
            assertEquals(
                TutorEvidenceRequestStatus.PENDING,
                capability.openTutorEvidenceRequest(LEARNER_ID, EVIDENCE_REQUEST_ID)?.status,
            )
            assertEquals(intent, capability.beginTutorLearningEvidenceSessionIntent(beginCommand()))

            now = 200L
            capability.acknowledgeTutorLearningEvidenceSession(acknowledgeCommand())
            assertEquals(
                TutorEvidenceRequestStatus.SUBMITTED,
                capability.openTutorEvidenceRequest(LEARNER_ID, EVIDENCE_REQUEST_ID)?.status,
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun changedOrCrossScopePayloadCannotReplayOrAcknowledgeIntent() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(context()) { 100L }
        try {
            preparePendingEvidence(store)
            val capability = StudyDatabaseFactory.authorizeTutorSession(store)
            capability.beginTutorLearningEvidenceSessionIntent(beginCommand())

            assertSessionConflict {
                capability.beginTutorLearningEvidenceSessionIntent(
                    beginCommand().copy(candidateFingerprint = hash("changed-candidate")),
                )
            }
            assertSessionConflict {
                capability.beginTutorLearningEvidenceSessionIntent(
                    beginCommand().copy(learnerId = "another-learner"),
                )
            }
            assertSessionConflict {
                capability.acknowledgeTutorLearningEvidenceSession(
                    acknowledgeCommand().copy(conversationId = "another-conversation"),
                )
            }
            assertSessionConflict {
                capability.acknowledgeTutorLearningEvidenceSession(
                    acknowledgeCommand().copy(learnerId = "another-learner"),
                )
            }
            assertNull(
                capability.openTutorEvidenceRequest(
                    learnerId = "another-learner",
                    evidenceRequestId = EVIDENCE_REQUEST_ID,
                ),
            )
        } finally {
            store.close()
        }
    }

    private suspend fun preparePendingEvidence(store: RoomStudyDatabase) {
        store.createTutorConversation(
            CreateTutorConversationCommand(
                conversationId = CONVERSATION_ID,
                learnerId = LEARNER_ID,
                generation = 1,
                idempotencyKey = "create-1",
                payloadFingerprint = hash("create"),
            ),
        )
        val turn =
            store.allocateTutorTurn(
                AllocateTutorTurnCommand(
                    turnReceiptId = TURN_RECEIPT_ID,
                    learnerId = LEARNER_ID,
                    conversationId = CONVERSATION_ID,
                    conversationGeneration = 1,
                    expectedConversationStateVersion = 0,
                    expectedTurnOrdinal = 1,
                    clientTurnId = "client-turn-1",
                    payloadFingerprint = hash("turn"),
                    subject = SubjectKind.MATH,
                    problemAnchorId = SESSION_ANCHOR_ID,
                    requestVersion = 1,
                    explanationMode = TutorExplanationMode.GUIDED,
                    modeVersion = 2,
                    directiveFingerprint = hash("directive"),
                    studentMessageFingerprint = hash("student-message"),
                    studentMessageSummary = "我选择了第三项。",
                    occurredAtEpochMillis = 100,
                ),
            ).receipt
        store.prepareTutorEvidenceRequest(
            PrepareTutorEvidenceRequestCommand(
                evidenceRequestId = EVIDENCE_REQUEST_ID,
                learnerId = LEARNER_ID,
                conversationId = CONVERSATION_ID,
                conversationGeneration = 1,
                conversationStateVersion = turn.conversationStateVersion,
                turnReceiptId = TURN_RECEIPT_ID,
                turnOrdinal = 1,
                subject = SubjectKind.MATH,
                problemAnchorId = SESSION_ANCHOR_ID,
                kind = TutorEvidenceRequestKind.CHOICE,
                requestVersion = 1,
                explanationMode = TutorExplanationMode.GUIDED,
                modeVersion = 2,
                directiveFingerprint = hash("directive"),
                idempotencyKey = "prepare-1",
                payloadFingerprint = hash("prepare"),
            ),
        )
    }

    private fun beginCommand() =
        BeginTutorLearningEvidenceSessionIntentCommand(
            learnerId = LEARNER_ID,
            evidenceRequestId = EVIDENCE_REQUEST_ID,
            conversationId = CONVERSATION_ID,
            conversationGeneration = 1,
            conversationStateVersion = 1,
            turnReceiptId = TURN_RECEIPT_ID,
            turnOrdinal = 1,
            subject = SubjectKind.MATH,
            sessionAnchorId = SESSION_ANCHOR_ID,
            evidenceKind = TutorEvidenceRequestKind.CHOICE,
            requestVersion = 1,
            modeVersion = 2,
            idempotencyKey = "submit-1",
            candidateFingerprint = hash("candidate"),
        )

    private fun cancelCommand() =
        CancelTutorEvidenceRequestCommand(
            learnerId = LEARNER_ID,
            conversationId = CONVERSATION_ID,
            conversationGeneration = 1,
            conversationStateVersion = 1,
            turnReceiptId = TURN_RECEIPT_ID,
            turnOrdinal = 1,
            subject = SubjectKind.MATH,
            problemAnchorId = SESSION_ANCHOR_ID,
            evidenceRequestId = EVIDENCE_REQUEST_ID,
            expectedEvidenceStateVersion = 0,
            kind = TutorEvidenceRequestKind.CHOICE,
            requestVersion = 1,
            explanationMode = TutorExplanationMode.GUIDED,
            modeVersion = 2,
            directiveFingerprint = hash("directive"),
            idempotencyKey = "cancel-1",
            payloadFingerprint = hash("cancel"),
        )

    private fun archiveCommand() =
        ArchiveTutorConversationCommand(
            learnerId = LEARNER_ID,
            conversationId = CONVERSATION_ID,
            conversationGeneration = 1,
            expectedStateVersion = 1,
            idempotencyKey = "archive-1",
            payloadFingerprint = hash("archive"),
        )

    private fun acknowledgeCommand() =
        AcknowledgeTutorLearningEvidenceSessionCommand(
            learnerId = LEARNER_ID,
            evidenceRequestId = EVIDENCE_REQUEST_ID,
            conversationId = CONVERSATION_ID,
            conversationGeneration = 1,
            turnReceiptId = TURN_RECEIPT_ID,
            candidateFingerprint = hash("candidate"),
            expectedStateVersion = 0,
            masteryReceiptId = "mastery-receipt-1",
            masteryReceiptFingerprint = hash("mastery-receipt"),
        )

    private suspend fun assertSessionConflict(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue(failure is TutorLearningEvidenceSessionConflictException)
    }

    private suspend fun assertEvidenceConflict(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue(failure is TutorEvidenceConflictException)
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private companion object {
        const val LEARNER_ID = "learner-local"
        const val CONVERSATION_ID = "conversation-v44"
        const val TURN_RECEIPT_ID = "turn-v44"
        const val EVIDENCE_REQUEST_ID = "evidence-v44"
        const val SESSION_ANCHOR_ID = "session-anchor-v44"

        fun hash(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
