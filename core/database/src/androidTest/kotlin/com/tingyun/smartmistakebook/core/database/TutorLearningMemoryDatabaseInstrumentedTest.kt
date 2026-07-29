package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.room3.executeSQL
import androidx.room3.withWriteTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorLearningMemoryDatabaseInstrumentedTest {
    @Test
    fun latestActiveConversationIsLearnerScopedAndOrderedByCreationThenId() = runBlocking {
        var now = 1_000L
        val store = StudyDatabaseFactory.openInMemory(context()) { now }
        try {
            store.createTutorConversation(
                createConversation(
                    conversationId = "conversation-a",
                    idempotencyKey = "create-a",
                ),
            )
            store.createTutorConversation(
                createConversation(
                    conversationId = "conversation-z",
                    idempotencyKey = "create-z",
                ),
            )
            now = 2_000L
            store.createTutorConversation(
                createConversation(
                    conversationId = "other-conversation",
                    learnerId = OTHER_LEARNER_ID,
                    idempotencyKey = "create-other",
                ),
            )

            assertEquals(
                "conversation-z",
                store.latestActiveTutorConversation(LEARNER_ID)?.conversationId,
            )
            assertEquals(
                "other-conversation",
                store.latestActiveTutorConversation(OTHER_LEARNER_ID)?.conversationId,
            )

            now = 3_000L
            store.archiveTutorConversation(
                ArchiveTutorConversationCommand(
                    learnerId = LEARNER_ID,
                    conversationId = "conversation-z",
                    conversationGeneration = 1,
                    expectedStateVersion = 0,
                    idempotencyKey = "archive-z",
                    payloadFingerprint = sha256("archive-z"),
                ),
            )

            assertEquals(
                "conversation-a",
                store.latestActiveTutorConversation(LEARNER_ID)?.conversationId,
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun latestActiveConversationInNamespaceIgnoresNewerConversationsElsewhere() = runBlocking {
        var now = 1_000L
        val store = StudyDatabaseFactory.openInMemory(context()) { now }
        try {
            store.createTutorConversation(
                createConversation(
                    conversationId = "tutor-lobby:active",
                    idempotencyKey = "create-lobby",
                ),
            )
            now = 2_000L
            store.createTutorConversation(
                createConversation(
                    conversationId = "captured:tutor-session",
                    idempotencyKey = "create-captured",
                ),
            )

            assertEquals(
                "captured:tutor-session",
                store.latestActiveTutorConversation(LEARNER_ID)?.conversationId,
            )
            assertEquals(
                "tutor-lobby:active",
                store.latestActiveTutorConversationInNamespace(
                    LEARNER_ID,
                    "tutor-lobby:",
                )?.conversationId,
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun conversationAndTurnAllocationAreScopedIdempotentAndContinuous() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(context()) { TRUSTED_NOW }
        try {
            val create = createConversation()
            val first = store.createTutorConversation(create)
            val replay = store.createTutorConversation(create)
            assertTrue(first.created)
            assertFalse(replay.created)
            assertEquals(0, first.conversation.stateVersion)
            assertNull(store.openTutorConversation("another-learner", CONVERSATION_ID, 1))
            assertNull(store.openTutorConversation(LEARNER_ID, CONVERSATION_ID, 2))

            val turnOne = allocateTurn(expectedStateVersion = 0, expectedOrdinal = 1)
            val allocatedOne = store.allocateTutorTurn(turnOne)
            val replayedOne = store.allocateTutorTurn(turnOne)
            assertTrue(allocatedOne.created)
            assertFalse(replayedOne.created)
            assertEquals(1, allocatedOne.receipt.turnOrdinal)
            assertEquals(1, allocatedOne.conversation.stateVersion)
            assertConflict<TutorTurnConflictException> {
                store.allocateTutorTurn(
                    turnOne.copy(expectedConversationStateVersion = 1),
                )
            }

            assertConflict<TutorTurnConflictException> {
                store.allocateTutorTurn(
                    allocateTurn(
                        expectedStateVersion = 1,
                        expectedOrdinal = 3,
                        turnReceiptId = "turn-receipt-gap",
                        clientTurnId = "client-turn-gap",
                    ),
                )
            }
            assertConflict<TutorTurnConflictException> {
                store.allocateTutorTurn(
                    turnOne.copy(
                        turnReceiptId = "different-receipt",
                        payloadFingerprint = sha256("different-turn-payload"),
                    ),
                )
            }

            val allocatedTwo = store.allocateTutorTurn(
                allocateTurn(
                    expectedStateVersion = 1,
                    expectedOrdinal = 2,
                    turnReceiptId = "turn-receipt-2",
                    clientTurnId = "client-turn-2",
                    explanationMode = TutorExplanationMode.DIRECT,
                ),
            )
            assertEquals(2, allocatedTwo.receipt.turnOrdinal)
            assertEquals(2, allocatedTwo.conversation.stateVersion)
            assertEquals(TutorExplanationMode.DIRECT, allocatedTwo.receipt.explanationMode)
            assertTrue(
                runCatching {
                    prepareEvidence(
                        conversationStateVersion = allocatedTwo.receipt.conversationStateVersion,
                        requestId = "direct-evidence-request",
                        idempotencyKey = "direct-evidence-key",
                        payloadSeed = "direct-evidence-payload",
                        turnReceiptId = allocatedTwo.receipt.turnReceiptId,
                        turnOrdinal = allocatedTwo.receipt.turnOrdinal,
                    ).copy(explanationMode = TutorExplanationMode.DIRECT)
                }.exceptionOrNull() is IllegalArgumentException,
            )

            val archived = store.archiveTutorConversation(
                ArchiveTutorConversationCommand(
                    learnerId = LEARNER_ID,
                    conversationId = CONVERSATION_ID,
                    conversationGeneration = 1,
                    expectedStateVersion = 2,
                    idempotencyKey = "archive-key",
                    payloadFingerprint = sha256("archive"),
                ),
            )
            val archivedReplay = store.archiveTutorConversation(
                ArchiveTutorConversationCommand(
                    learnerId = LEARNER_ID,
                    conversationId = CONVERSATION_ID,
                    conversationGeneration = 1,
                    expectedStateVersion = 2,
                    idempotencyKey = "archive-key",
                    payloadFingerprint = sha256("archive"),
                ),
            )
            assertTrue(archived.archived)
            assertFalse(archivedReplay.archived)
            assertEquals(TutorConversationStatus.ARCHIVED, archived.conversation.status)
            assertEquals(archived.conversation, archivedReplay.conversation)
            assertConflict<TutorConversationConflictException> {
                store.archiveTutorConversation(
                    ArchiveTutorConversationCommand(
                        learnerId = LEARNER_ID,
                        conversationId = CONVERSATION_ID,
                        conversationGeneration = 1,
                        expectedStateVersion = 2,
                        idempotencyKey = "archive-key",
                        payloadFingerprint = sha256("different-archive"),
                    ),
                )
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun openTurnReturnsExactScopedReceiptAndHidesOtherLearners() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(context()) { TRUSTED_NOW }
        try {
            store.createTutorConversation(createConversation())
            val allocated = store.allocateTutorTurn(
                allocateTurn(expectedStateVersion = 0, expectedOrdinal = 1),
            ).receipt

            assertEquals(
                TutorTurnReadResult.Found(allocated),
                store.openTutorTurn(LEARNER_ID, allocated.turnReceiptId),
            )
            assertEquals(
                TutorTurnReadResult.NotFound,
                store.openTutorTurn(OTHER_LEARNER_ID, allocated.turnReceiptId),
            )
            assertEquals(
                TutorTurnReadResult.NotFound,
                store.openTutorTurn(LEARNER_ID, "missing-turn-receipt"),
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun archiveAtomicallyCancelsEveryPendingRequestWithoutCreatingLearningFacts() = runBlocking {
        var now = TRUSTED_NOW
        val store = StudyDatabaseFactory.openInMemory(context()) { now }
        try {
            store.createTutorConversation(createConversation())
            val turn = store.allocateTutorTurn(
                allocateTurn(expectedStateVersion = 0, expectedOrdinal = 1),
            ).receipt
            val first = prepareEvidence(
                conversationStateVersion = turn.conversationStateVersion,
                requestId = "archive-pending-1",
                idempotencyKey = "archive-prepare-1",
                payloadSeed = "archive-prepare-payload-1",
            )
            val second = prepareEvidence(
                conversationStateVersion = turn.conversationStateVersion,
                requestId = "archive-pending-2",
                idempotencyKey = "archive-prepare-2",
                payloadSeed = "archive-prepare-payload-2",
            )
            store.prepareTutorEvidenceRequest(first)
            store.prepareTutorEvidenceRequest(second)

            now = TRUSTED_NOW + 1_000
            val archive = ArchiveTutorConversationCommand(
                learnerId = LEARNER_ID,
                conversationId = CONVERSATION_ID,
                conversationGeneration = 1,
                expectedStateVersion = turn.conversationStateVersion,
                idempotencyKey = "archive-with-pending",
                payloadFingerprint = sha256("archive-with-pending"),
            )
            val archived = store.archiveTutorConversation(archive)
            val firstAfterArchive = store.prepareTutorEvidenceRequest(first).request
            val secondAfterArchive = store.prepareTutorEvidenceRequest(second).request

            assertTrue(archived.archived)
            listOf(firstAfterArchive, secondAfterArchive).forEach { request ->
                assertEquals(TutorEvidenceRequestStatus.CANCELLED, request.status)
                assertEquals(1, request.stateVersion)
                assertEquals(now, request.resolvedAtEpochMillis)
                assertNull(request.terminalSourceFactId)
            }
            val dao = store.database.tutorLearningMemoryDao()
            assertEquals(0, dao.countEvidenceRequests(TutorEvidenceRequestStatus.PENDING.name))
            assertEquals(2, dao.countEvidenceRequests(TutorEvidenceRequestStatus.CANCELLED.name))
            assertEquals(0, dao.countAnchors())
            assertEquals(0, dao.countSourceFacts())

            val archiveCancellationReplay = store.finalizeTutorEvidenceRequest(
                cancelEvidence(first, "ignored").copy(
                    idempotencyKey = archive.idempotencyKey,
                    payloadFingerprint = archive.payloadFingerprint,
                ),
            )
            assertTrue(archiveCancellationReplay.replayed)
            assertEquals(TutorEvidenceRequestStatus.CANCELLED, archiveCancellationReplay.request.status)
            assertNull(archiveCancellationReplay.sourceFact)

            assertConflict<TutorEvidenceConflictException> {
                store.finalizeTutorEvidenceRequest(
                    submitEvidence(first, terminalSeed = "late-submit-after-archive"),
                )
            }
            assertConflict<TutorEvidenceConflictException> {
                store.prepareTutorEvidenceRequest(
                    first.copy(
                        evidenceRequestId = "new-request-after-archive",
                        idempotencyKey = "new-prepare-after-archive",
                        payloadFingerprint = sha256("new-prepare-after-archive"),
                    ),
                )
            }
            assertEquals(0, dao.countAnchors())
            assertEquals(0, dao.countSourceFacts())

            now += 1_000
            val replay = store.archiveTutorConversation(archive)
            val firstAfterReplay = store.prepareTutorEvidenceRequest(first).request
            assertFalse(replay.archived)
            assertEquals(firstAfterArchive, firstAfterReplay)
            assertEquals(2, dao.countEvidenceRequests(TutorEvidenceRequestStatus.CANCELLED.name))
        } finally {
            store.close()
        }
    }

    @Test
    fun archiveReplayLeavesExistingTerminalEvidenceUnchanged() = runBlocking {
        var now = TRUSTED_NOW
        val store = StudyDatabaseFactory.openInMemory(context()) { now }
        try {
            store.createTutorConversation(createConversation())
            val turn = store.allocateTutorTurn(
                allocateTurn(expectedStateVersion = 0, expectedOrdinal = 1),
            ).receipt
            val submittedPrepare = prepareEvidence(
                conversationStateVersion = turn.conversationStateVersion,
                requestId = "already-submitted",
                idempotencyKey = "prepare-already-submitted",
                payloadSeed = "prepare-already-submitted",
            )
            val cancelledPrepare = prepareEvidence(
                conversationStateVersion = turn.conversationStateVersion,
                requestId = "already-cancelled",
                idempotencyKey = "prepare-already-cancelled",
                payloadSeed = "prepare-already-cancelled",
            )
            store.prepareTutorEvidenceRequest(submittedPrepare)
            store.prepareTutorEvidenceRequest(cancelledPrepare)
            val submitted = store.finalizeTutorEvidenceRequest(
                submitEvidence(submittedPrepare, terminalSeed = "before-archive"),
            ).request
            val cancelled = store.finalizeTutorEvidenceRequest(
                cancelEvidence(cancelledPrepare, terminalSeed = "before-archive"),
            ).request

            now += 1_000
            val archive = ArchiveTutorConversationCommand(
                learnerId = LEARNER_ID,
                conversationId = CONVERSATION_ID,
                conversationGeneration = 1,
                expectedStateVersion = turn.conversationStateVersion,
                idempotencyKey = "archive-terminal-evidence",
                payloadFingerprint = sha256("archive-terminal-evidence"),
            )
            store.archiveTutorConversation(archive)
            now += 1_000
            store.archiveTutorConversation(archive)

            assertEquals(
                submitted,
                store.prepareTutorEvidenceRequest(submittedPrepare).request,
            )
            assertEquals(
                cancelled,
                store.prepareTutorEvidenceRequest(cancelledPrepare).request,
            )
            val dao = store.database.tutorLearningMemoryDao()
            assertEquals(1, dao.countEvidenceRequests(TutorEvidenceRequestStatus.SUBMITTED.name))
            assertEquals(1, dao.countEvidenceRequests(TutorEvidenceRequestStatus.CANCELLED.name))
            assertEquals(1, dao.countAnchors())
            assertEquals(1, dao.countSourceFacts())
        } finally {
            store.close()
        }
    }

    @Test
    fun archivedConversationReplayRepairsLegacyPendingEvidenceExactlyOnce() = runBlocking {
        var now = TRUSTED_NOW
        val store = StudyDatabaseFactory.openInMemory(context()) { now }
        try {
            store.createTutorConversation(createConversation())
            val turn = store.allocateTutorTurn(
                allocateTurn(expectedStateVersion = 0, expectedOrdinal = 1),
            ).receipt
            val pending = prepareEvidence(turn.conversationStateVersion)
            val initial = store.prepareTutorEvidenceRequest(pending).request
            val archive = ArchiveTutorConversationCommand(
                learnerId = LEARNER_ID,
                conversationId = CONVERSATION_ID,
                conversationGeneration = 1,
                expectedStateVersion = turn.conversationStateVersion,
                idempotencyKey = "legacy-archive",
                payloadFingerprint = sha256("legacy-archive"),
            )
            store.database.withWriteTransaction {
                executeSQL(
                    """
                    UPDATE tutor_conversation
                    SET status = 'ARCHIVED',
                        archive_idempotency_key = '${archive.idempotencyKey}',
                        archive_payload_fingerprint = '${archive.payloadFingerprint}',
                        archived_at_epoch_millis = $now,
                        updated_at_epoch_millis = $now,
                        state_version = state_version + 1
                    WHERE conversation_id = '$CONVERSATION_ID'
                    """.trimIndent(),
                )
            }

            now += 1_000
            val firstReplay = store.archiveTutorConversation(archive)
            val repaired = store.prepareTutorEvidenceRequest(pending).request
            now += 1_000
            val secondReplay = store.archiveTutorConversation(archive)
            val stable = store.prepareTutorEvidenceRequest(pending).request

            assertFalse(firstReplay.archived)
            assertFalse(secondReplay.archived)
            assertEquals(TutorEvidenceRequestStatus.PENDING, initial.status)
            assertEquals(TutorEvidenceRequestStatus.CANCELLED, repaired.status)
            assertEquals(initial.stateVersion + 1, repaired.stateVersion)
            assertEquals(TRUSTED_NOW + 1_000, repaired.resolvedAtEpochMillis)
            assertEquals(repaired, stable)
            val dao = store.database.tutorLearningMemoryDao()
            assertEquals(1, dao.countEvidenceRequests(TutorEvidenceRequestStatus.CANCELLED.name))
            assertEquals(0, dao.countAnchors())
            assertEquals(0, dao.countSourceFacts())
        } finally {
            store.close()
        }
    }

    @Test
    fun archiveResolutionNeverPrecedesEvidenceWhenTrustedClockMovesBackward() = runBlocking {
        var now = TRUSTED_NOW - 2_000
        val store = StudyDatabaseFactory.openInMemory(context()) { now }
        try {
            store.createTutorConversation(createConversation())
            now = TRUSTED_NOW
            val turn = store.allocateTutorTurn(
                allocateTurn(expectedStateVersion = 0, expectedOrdinal = 1),
            ).receipt
            val pending = prepareEvidence(turn.conversationStateVersion)
            val created = store.prepareTutorEvidenceRequest(pending).request

            now = TRUSTED_NOW - 1_000
            store.archiveTutorConversation(
                ArchiveTutorConversationCommand(
                    learnerId = LEARNER_ID,
                    conversationId = CONVERSATION_ID,
                    conversationGeneration = 1,
                    expectedStateVersion = turn.conversationStateVersion,
                    idempotencyKey = "clock-rollback-archive",
                    payloadFingerprint = sha256("clock-rollback-archive"),
                ),
            )
            val cancelled = store.prepareTutorEvidenceRequest(pending).request

            assertEquals(TutorEvidenceRequestStatus.CANCELLED, cancelled.status)
            assertEquals(created.createdAtEpochMillis, cancelled.resolvedAtEpochMillis)
            assertEquals(TRUSTED_NOW, cancelled.resolvedAtEpochMillis)
        } finally {
            store.close()
        }
    }

    @Test
    fun archiveFailureAfterEvidenceCancellationRollsBackEntireTransaction() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(context()) { TRUSTED_NOW }
        try {
            store.createTutorConversation(createConversation())
            val turn = store.allocateTutorTurn(
                allocateTurn(expectedStateVersion = 0, expectedOrdinal = 1),
            ).receipt
            val first = prepareEvidence(
                conversationStateVersion = turn.conversationStateVersion,
                requestId = "rollback-pending-1",
                idempotencyKey = "rollback-prepare-1",
                payloadSeed = "rollback-prepare-1",
            )
            val second = prepareEvidence(
                conversationStateVersion = turn.conversationStateVersion,
                requestId = "rollback-pending-2",
                idempotencyKey = "rollback-prepare-2",
                payloadSeed = "rollback-prepare-2",
            )
            val firstBefore = store.prepareTutorEvidenceRequest(first).request
            val secondBefore = store.prepareTutorEvidenceRequest(second).request
            store.database.withWriteTransaction {
                executeSQL(
                    """
                    CREATE TRIGGER fail_tutor_conversation_archive
                    BEFORE UPDATE OF status ON tutor_conversation
                    WHEN NEW.status = 'ARCHIVED'
                    BEGIN
                        SELECT RAISE(ABORT, 'forced archive failure');
                    END
                    """.trimIndent(),
                )
            }

            val failure = runCatching {
                store.archiveTutorConversation(
                    ArchiveTutorConversationCommand(
                        learnerId = LEARNER_ID,
                        conversationId = CONVERSATION_ID,
                        conversationGeneration = 1,
                        expectedStateVersion = turn.conversationStateVersion,
                        idempotencyKey = "forced-failure-archive",
                        payloadFingerprint = sha256("forced-failure-archive"),
                    ),
                )
            }.exceptionOrNull()
            assertTrue("Expected the archive trigger to abort the transaction", failure != null)

            assertEquals(firstBefore, store.prepareTutorEvidenceRequest(first).request)
            assertEquals(secondBefore, store.prepareTutorEvidenceRequest(second).request)
            listOf(firstBefore, secondBefore).forEach { request ->
                assertEquals(TutorEvidenceRequestStatus.PENDING, request.status)
                assertEquals(0, request.stateVersion)
                assertNull(request.resolvedAtEpochMillis)
                assertNull(request.terminalSourceFactId)
            }
            assertEquals(
                TutorConversationStatus.ACTIVE,
                store.openTutorConversation(LEARNER_ID, CONVERSATION_ID, 1)?.status,
            )
            val dao = store.database.tutorLearningMemoryDao()
            assertEquals(2, dao.countEvidenceRequests(TutorEvidenceRequestStatus.PENDING.name))
            assertEquals(0, dao.countEvidenceRequests(TutorEvidenceRequestStatus.CANCELLED.name))
            assertEquals(0, dao.countAnchors())
            assertEquals(0, dao.countSourceFacts())
        } finally {
            store.database.withWriteTransaction {
                executeSQL("DROP TRIGGER IF EXISTS fail_tutor_conversation_archive")
            }
            store.close()
        }
    }

    @Test
    fun evidenceScopeIdempotencyAndPrivacyFailClosed() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(context()) { TRUSTED_NOW }
        try {
            store.createTutorConversation(createConversation())
            val turn = store.allocateTutorTurn(
                allocateTurn(expectedStateVersion = 0, expectedOrdinal = 1),
            ).receipt
            val prepare = prepareEvidence(turn.conversationStateVersion)
            val prepared = store.prepareTutorEvidenceRequest(prepare)
            val replay = store.prepareTutorEvidenceRequest(prepare)
            assertTrue(prepared.created)
            assertFalse(replay.created)

            assertConflict<TutorEvidenceConflictException> {
                store.prepareTutorEvidenceRequest(
                    prepare.copy(
                        evidenceRequestId = "another-request-id",
                        payloadFingerprint = sha256("different-prepare"),
                    ),
                )
            }
            assertConflict<TutorMemoryScopeConflictException> {
                store.prepareTutorEvidenceRequest(
                    prepare.copy(
                        evidenceRequestId = "wrong-learner-request",
                        learnerId = "another-learner",
                        idempotencyKey = "wrong-learner-key",
                        payloadFingerprint = sha256("wrong-learner"),
                    ),
                )
            }
            assertConflict<TutorMemoryScopeConflictException> {
                store.prepareTutorEvidenceRequest(
                    prepare.copy(
                        evidenceRequestId = "wrong-generation-request",
                        conversationGeneration = 2,
                        idempotencyKey = "wrong-generation-key",
                        payloadFingerprint = sha256("wrong-generation"),
                    ),
                )
            }
            assertConflict<TutorMemoryScopeConflictException> {
                store.prepareTutorEvidenceRequest(
                    prepare.copy(
                        evidenceRequestId = "wrong-turn-request",
                        turnOrdinal = 2,
                        idempotencyKey = "wrong-turn-key",
                        payloadFingerprint = sha256("wrong-turn"),
                    ),
                )
            }
            assertConflict<TutorMemoryScopeConflictException> {
                store.prepareTutorEvidenceRequest(
                    prepare.copy(
                        evidenceRequestId = "wrong-directive-request",
                        directiveFingerprint = sha256("wrong-directive"),
                        idempotencyKey = "wrong-directive-key",
                        payloadFingerprint = sha256("wrong-directive-prepare"),
                    ),
                )
            }

            val submitted = store.finalizeTutorEvidenceRequest(
                submitEvidence(prepare, terminalSeed = "first-submit"),
            )
            val submittedReplay = store.finalizeTutorEvidenceRequest(
                submitEvidence(prepare, terminalSeed = "first-submit"),
            )
            assertFalse(submitted.replayed)
            assertTrue(submittedReplay.replayed)
            assertEquals(TutorEvidenceRequestStatus.SUBMITTED, submitted.request.status)
            assertEquals(1, store.database.tutorLearningMemoryDao().countAnchors())
            assertEquals(1, store.database.tutorLearningMemoryDao().countSourceFacts())
            assertEquals(0, store.database.tutorLearningMemoryDao().countErrorBookEntries())
            assertEquals(0, store.database.tutorLearningMemoryDao().countReviewQueueItems())

            assertConflict<TutorEvidenceConflictException> {
                store.finalizeTutorEvidenceRequest(cancelEvidence(prepare, "late-cancel"))
            }
            assertConflict<TutorMemoryScopeConflictException> {
                store.finalizeTutorEvidenceRequest(
                    submitEvidence(prepare, terminalSeed = "cross-mode").copy(
                        directiveFingerprint = sha256("another-directive"),
                    ),
                )
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun identicalAnswerPayloadCanProduceFactsForDifferentRequestsAndLearners() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(context()) { TRUSTED_NOW }
        try {
            store.createTutorConversation(createConversation())
            val firstTurn = store.allocateTutorTurn(
                allocateTurn(expectedStateVersion = 0, expectedOrdinal = 1),
            ).receipt
            val firstRequest = prepareEvidence(firstTurn.conversationStateVersion)
            store.prepareTutorEvidenceRequest(firstRequest)
            store.finalizeTutorEvidenceRequest(
                submitEvidence(firstRequest, "shared-answer-first").withSharedAnswerPayload(),
            )

            val secondTurn = store.allocateTutorTurn(
                allocateTurn(
                    expectedStateVersion = 1,
                    expectedOrdinal = 2,
                    turnReceiptId = "shared-answer-turn-2",
                    clientTurnId = "shared-answer-client-2",
                ),
            ).receipt
            val secondRequest = prepareEvidence(
                conversationStateVersion = secondTurn.conversationStateVersion,
                requestId = "shared-answer-request-2",
                idempotencyKey = "shared-answer-prepare-2",
                payloadSeed = "shared-answer-prepare-2",
                turnReceiptId = secondTurn.turnReceiptId,
                turnOrdinal = secondTurn.turnOrdinal,
            )
            store.prepareTutorEvidenceRequest(secondRequest)
            store.finalizeTutorEvidenceRequest(
                submitEvidence(secondRequest, "shared-answer-second").withSharedAnswerPayload(),
            )

            store.createTutorConversation(
                createConversation().copy(
                    conversationId = OTHER_CONVERSATION_ID,
                    learnerId = OTHER_LEARNER_ID,
                    idempotencyKey = "other-create-key",
                    payloadFingerprint = sha256("other-create-payload"),
                ),
            )
            val otherTurn = store.allocateTutorTurn(
                allocateTurn(
                    expectedStateVersion = 0,
                    expectedOrdinal = 1,
                    turnReceiptId = "other-turn-receipt",
                    clientTurnId = "other-client-turn",
                ).copy(
                    learnerId = OTHER_LEARNER_ID,
                    conversationId = OTHER_CONVERSATION_ID,
                    problemAnchorId = OTHER_ANCHOR_ID,
                ),
            ).receipt
            val otherRequest = prepareEvidence(
                conversationStateVersion = otherTurn.conversationStateVersion,
                requestId = "other-evidence-request",
                idempotencyKey = "other-prepare-key",
                payloadSeed = "other-prepare-payload",
                turnReceiptId = otherTurn.turnReceiptId,
                turnOrdinal = otherTurn.turnOrdinal,
            ).copy(
                learnerId = OTHER_LEARNER_ID,
                conversationId = OTHER_CONVERSATION_ID,
                problemAnchorId = OTHER_ANCHOR_ID,
            )
            store.prepareTutorEvidenceRequest(otherRequest)
            store.finalizeTutorEvidenceRequest(
                submitEvidence(otherRequest, "shared-answer-other").withSharedAnswerPayload(),
            )

            val dao = store.database.tutorLearningMemoryDao()
            assertEquals(3, dao.countSourceFacts())
            assertEquals(2, dao.countAnchors())
        } finally {
            store.close()
        }
    }

    @Test
    fun collectionLifecycleCannotChangeOrDuplicateLearningAnchor() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(context()) { TRUSTED_NOW }
        try {
            store.createTutorConversation(createConversation())
            val firstTurn = store.allocateTutorTurn(
                allocateTurn(expectedStateVersion = 0, expectedOrdinal = 1),
            ).receipt
            val beforeCollection = prepareEvidence(firstTurn.conversationStateVersion)
            store.prepareTutorEvidenceRequest(beforeCollection)
            store.finalizeTutorEvidenceRequest(
                submitEvidence(beforeCollection, terminalSeed = "before-collection"),
            )

            val secondTurn = store.allocateTutorTurn(
                allocateTurn(
                    expectedStateVersion = 1,
                    expectedOrdinal = 2,
                    turnReceiptId = "turn-receipt-after-collection",
                    clientTurnId = "client-turn-after-collection",
                ),
            ).receipt
            val afterCollection = prepareEvidence(
                conversationStateVersion = secondTurn.conversationStateVersion,
                requestId = "request-after-collection",
                idempotencyKey = "prepare-after-collection",
                payloadSeed = "prepare-after-collection",
                turnReceiptId = secondTurn.turnReceiptId,
                turnOrdinal = secondTurn.turnOrdinal,
            )
            store.prepareTutorEvidenceRequest(afterCollection)
            store.finalizeTutorEvidenceRequest(
                submitEvidence(afterCollection, terminalSeed = "after-collection"),
            )

            val dao = store.database.tutorLearningMemoryDao()
            assertEquals(1, dao.countAnchors())
            assertEquals(2, dao.countSourceFacts())
            assertEquals(0, dao.countErrorBookEntries())
            assertEquals(0, dao.countReviewQueueItems())
        } finally {
            store.close()
        }
    }

    @Test
    fun staleConversationVersionCannotPrepareOrFinalizeEvidence() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(context()) { TRUSTED_NOW }
        try {
            store.createTutorConversation(createConversation())
            val firstTurn = store.allocateTutorTurn(
                allocateTurn(expectedStateVersion = 0, expectedOrdinal = 1),
            ).receipt
            val prepared = prepareEvidence(firstTurn.conversationStateVersion)
            store.prepareTutorEvidenceRequest(prepared)

            store.allocateTutorTurn(
                allocateTurn(
                    expectedStateVersion = 1,
                    expectedOrdinal = 2,
                    turnReceiptId = "stale-version-turn-2",
                    clientTurnId = "stale-version-client-2",
                ),
            )

            assertConflict<TutorEvidenceConflictException> {
                store.finalizeTutorEvidenceRequest(
                    submitEvidence(prepared, terminalSeed = "stale-finalize"),
                )
            }
            assertConflict<TutorEvidenceConflictException> {
                store.prepareTutorEvidenceRequest(
                    prepared.copy(
                        evidenceRequestId = "stale-prepare-request",
                        idempotencyKey = "stale-prepare-key",
                        payloadFingerprint = sha256("stale-prepare-payload"),
                    ),
                )
            }

            val dao = store.database.tutorLearningMemoryDao()
            assertEquals(1, dao.countEvidenceRequests(TutorEvidenceRequestStatus.PENDING.name))
            assertEquals(0, dao.countSourceFacts())
            assertEquals(0, dao.countAnchors())
        } finally {
            store.close()
        }
    }

    @Test
    fun evidenceOccurrenceMustBeBoundedByRequestAndTrustedClock() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(context()) { TRUSTED_NOW }
        try {
            store.createTutorConversation(createConversation())
            val turn = store.allocateTutorTurn(
                allocateTurn(expectedStateVersion = 0, expectedOrdinal = 1),
            ).receipt
            val prepared = prepareEvidence(turn.conversationStateVersion)
            store.prepareTutorEvidenceRequest(prepared)

            assertConflict<TutorEvidenceConflictException> {
                store.finalizeTutorEvidenceRequest(
                    submitEvidence(
                        prepared,
                        terminalSeed = "before-request",
                        occurredAtEpochMillis = TRUSTED_NOW - 1,
                    ),
                )
            }
            assertConflict<TutorEvidenceConflictException> {
                store.finalizeTutorEvidenceRequest(
                    submitEvidence(
                        prepared,
                        terminalSeed = "after-trusted-clock",
                        occurredAtEpochMillis = TRUSTED_NOW + 1,
                    ),
                )
            }
            val submitted = store.finalizeTutorEvidenceRequest(
                submitEvidence(
                    prepared,
                    terminalSeed = "bounded-time",
                    occurredAtEpochMillis = TRUSTED_NOW,
                ),
            )

            assertEquals(TRUSTED_NOW, submitted.sourceFact?.occurredAtEpochMillis)
            assertEquals(1, store.database.tutorLearningMemoryDao().countSourceFacts())
        } finally {
            store.close()
        }
    }

    @Test
    fun thousandSubmitCancelAttemptsCommitExactlyOneTerminalState() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(context()) { TRUSTED_NOW }
        try {
            store.createTutorConversation(createConversation())
            val turn = store.allocateTutorTurn(
                allocateTurn(expectedStateVersion = 0, expectedOrdinal = 1),
            ).receipt
            val prepares = (0 until SEQUENTIAL_RACE_CASES).map { index ->
                prepareEvidence(
                    conversationStateVersion = turn.conversationStateVersion,
                    requestId = "race-request-$index",
                    idempotencyKey = "race-prepare-$index",
                    payloadSeed = "race-prepare-$index",
                ).also { store.prepareTutorEvidenceRequest(it) }
            }
            prepares.forEachIndexed { index, prepare ->
                val winner = if (index % 2 == 0) {
                    submitEvidence(prepare, "race-submit-$index")
                } else {
                    cancelEvidence(prepare, "race-cancel-$index")
                }
                val loser = if (index % 2 == 0) {
                    cancelEvidence(prepare, "race-cancel-$index")
                } else {
                    submitEvidence(prepare, "race-submit-$index")
                }
                store.finalizeTutorEvidenceRequest(winner)
                assertConflict<TutorEvidenceConflictException> {
                    store.finalizeTutorEvidenceRequest(loser)
                }
            }

            val concurrentPrepares = (0 until CONCURRENT_RACE_CASES).map { index ->
                prepareEvidence(
                    conversationStateVersion = turn.conversationStateVersion,
                    requestId = "concurrent-request-$index",
                    idempotencyKey = "concurrent-prepare-$index",
                    payloadSeed = "concurrent-prepare-$index",
                ).also { store.prepareTutorEvidenceRequest(it) }
            }
            concurrentPrepares.forEachIndexed { index, prepare ->
                val outcomes = coroutineScope {
                    listOf(
                        async(Dispatchers.IO) {
                            runCatching {
                                store.finalizeTutorEvidenceRequest(
                                    submitEvidence(prepare, "concurrent-submit-$index"),
                                )
                            }
                        },
                        async(Dispatchers.IO) {
                            runCatching {
                                store.finalizeTutorEvidenceRequest(
                                    cancelEvidence(prepare, "concurrent-cancel-$index"),
                                )
                            }
                        },
                    ).awaitAll()
                }
                assertEquals(1, outcomes.count(Result<*>::isSuccess))
                assertEquals(1, outcomes.count(Result<*>::isFailure))
            }

            val dao = store.database.tutorLearningMemoryDao()
            val submitted = dao.countEvidenceRequests(TutorEvidenceRequestStatus.SUBMITTED.name)
            val cancelled = dao.countEvidenceRequests(TutorEvidenceRequestStatus.CANCELLED.name)
            val pending = dao.countEvidenceRequests(TutorEvidenceRequestStatus.PENDING.name)
            assertEquals(SEQUENTIAL_RACE_CASES + CONCURRENT_RACE_CASES, submitted + cancelled)
            assertEquals(0, pending)
            assertEquals(submitted, dao.countSourceFacts())
            assertEquals(1, dao.countAnchors())
        } finally {
            store.close()
        }
    }

    private fun createConversation(
        conversationId: String = CONVERSATION_ID,
        learnerId: String = LEARNER_ID,
        idempotencyKey: String = "create-conversation-key",
    ) = CreateTutorConversationCommand(
        conversationId = conversationId,
        learnerId = learnerId,
        generation = 1,
        idempotencyKey = idempotencyKey,
        payloadFingerprint = sha256("create-conversation:$idempotencyKey"),
    )

    private fun allocateTurn(
        expectedStateVersion: Long,
        expectedOrdinal: Int,
        turnReceiptId: String = TURN_RECEIPT_ID,
        clientTurnId: String = CLIENT_TURN_ID,
        explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    ) = AllocateTutorTurnCommand(
        turnReceiptId = turnReceiptId,
        learnerId = LEARNER_ID,
        conversationId = CONVERSATION_ID,
        conversationGeneration = 1,
        expectedConversationStateVersion = expectedStateVersion,
        expectedTurnOrdinal = expectedOrdinal,
        clientTurnId = clientTurnId,
        payloadFingerprint = sha256("turn:$clientTurnId"),
        subject = SubjectKind.PHYSICS,
        problemAnchorId = ANCHOR_ID,
        requestVersion = 1,
        explanationMode = explanationMode,
        modeVersion = 1,
        directiveFingerprint = DIRECTIVE_FINGERPRINT,
        studentMessageFingerprint = sha256("student-message:$clientTurnId"),
        studentMessageSummary = "我在这一步卡住了。",
        occurredAtEpochMillis = TRUSTED_NOW,
    )

    private fun prepareEvidence(
        conversationStateVersion: Long,
        requestId: String = EVIDENCE_REQUEST_ID,
        idempotencyKey: String = "prepare-evidence-key",
        payloadSeed: String = "prepare-evidence",
        turnReceiptId: String = TURN_RECEIPT_ID,
        turnOrdinal: Int = 1,
    ) = PrepareTutorEvidenceRequestCommand(
        evidenceRequestId = requestId,
        learnerId = LEARNER_ID,
        conversationId = CONVERSATION_ID,
        conversationGeneration = 1,
        conversationStateVersion = conversationStateVersion,
        turnReceiptId = turnReceiptId,
        turnOrdinal = turnOrdinal,
        subject = SubjectKind.PHYSICS,
        problemAnchorId = ANCHOR_ID,
        kind = TutorEvidenceRequestKind.CHOICE,
        requestVersion = 1,
        explanationMode = TutorExplanationMode.GUIDED,
        modeVersion = 1,
        directiveFingerprint = DIRECTIVE_FINGERPRINT,
        idempotencyKey = idempotencyKey,
        payloadFingerprint = sha256(payloadSeed),
    )

    private fun submitEvidence(
        prepared: PrepareTutorEvidenceRequestCommand,
        terminalSeed: String,
        occurredAtEpochMillis: Long = TRUSTED_NOW,
    ) = FinalizeTutorEvidenceRequestCommand(
        learnerId = prepared.learnerId,
        conversationId = prepared.conversationId,
        conversationGeneration = prepared.conversationGeneration,
        conversationStateVersion = prepared.conversationStateVersion,
        turnReceiptId = prepared.turnReceiptId,
        turnOrdinal = prepared.turnOrdinal,
        subject = prepared.subject,
        problemAnchorId = prepared.problemAnchorId,
        evidenceRequestId = prepared.evidenceRequestId,
        expectedEvidenceStateVersion = 0,
        kind = prepared.kind,
        requestVersion = prepared.requestVersion,
        explanationMode = prepared.explanationMode,
        modeVersion = prepared.modeVersion,
        directiveFingerprint = prepared.directiveFingerprint,
        terminalStatus = TutorEvidenceRequestStatus.SUBMITTED,
        idempotencyKey = "terminal-$terminalSeed",
        payloadFingerprint = sha256("terminal-payload:$terminalSeed"),
        submission = TutorEvidenceSubmission(
            sourceFactId = "source-fact-$terminalSeed",
            source = LearningObservationSource.TUTOR_CHOICE,
            factKind = LearningObservationFactKind.VERIFIED_INCORRECT_RESPONSE,
            questionFingerprint = QUESTION_FINGERPRINT,
            revisionFingerprint = REVISION_FINGERPRINT,
            fingerprintVersion = "question-fingerprint-v1",
            responseFingerprint = sha256("response:$terminalSeed"),
            responseSummary = "选择了不正确的方向。",
            occurredAtEpochMillis = occurredAtEpochMillis,
            sourceVersion = "tutor-source-v1",
        ),
    )

    private fun cancelEvidence(
        prepared: PrepareTutorEvidenceRequestCommand,
        terminalSeed: String,
    ) = FinalizeTutorEvidenceRequestCommand(
        learnerId = prepared.learnerId,
        conversationId = prepared.conversationId,
        conversationGeneration = prepared.conversationGeneration,
        conversationStateVersion = prepared.conversationStateVersion,
        turnReceiptId = prepared.turnReceiptId,
        turnOrdinal = prepared.turnOrdinal,
        subject = prepared.subject,
        problemAnchorId = prepared.problemAnchorId,
        evidenceRequestId = prepared.evidenceRequestId,
        expectedEvidenceStateVersion = 0,
        kind = prepared.kind,
        requestVersion = prepared.requestVersion,
        explanationMode = prepared.explanationMode,
        modeVersion = prepared.modeVersion,
        directiveFingerprint = prepared.directiveFingerprint,
        terminalStatus = TutorEvidenceRequestStatus.CANCELLED,
        idempotencyKey = "terminal-$terminalSeed",
        payloadFingerprint = sha256("terminal-payload:$terminalSeed"),
        submission = null,
    )

    private suspend inline fun <reified T : Throwable> assertConflict(
        crossinline block: suspend () -> Unit,
    ) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected ${T::class.java.simpleName}, got $failure", failure is T)
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun FinalizeTutorEvidenceRequestCommand.withSharedAnswerPayload() = copy(
        payloadFingerprint = SHARED_ANSWER_PAYLOAD,
        submission = checkNotNull(submission).copy(
            responseFingerprint = SHARED_ANSWER_RESPONSE,
        ),
    )

    private companion object {
        const val LEARNER_ID = "learner-local"
        const val CONVERSATION_ID = "conversation-v34"
        const val OTHER_LEARNER_ID = "learner-other"
        const val OTHER_CONVERSATION_ID = "conversation-other"
        const val TURN_RECEIPT_ID = "turn-receipt-1"
        const val CLIENT_TURN_ID = "client-turn-1"
        const val EVIDENCE_REQUEST_ID = "evidence-request-1"
        const val ANCHOR_ID = "learning-anchor-1"
        const val OTHER_ANCHOR_ID = "learning-anchor-other"
        const val TRUSTED_NOW = 10_000L
        const val SEQUENTIAL_RACE_CASES = 1_000
        const val CONCURRENT_RACE_CASES = 16
        val DIRECTIVE_FINGERPRINT = sha256("guided-choice-directive")
        val QUESTION_FINGERPRINT = sha256("question")
        val REVISION_FINGERPRINT = sha256("question-revision")
        val SHARED_ANSWER_PAYLOAD = sha256("shared-answer-terminal-payload")
        val SHARED_ANSWER_RESPONSE = sha256("shared-answer-response")

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
