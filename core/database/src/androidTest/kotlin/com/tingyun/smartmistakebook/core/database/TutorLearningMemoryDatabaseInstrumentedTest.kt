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
            assertNull(
                store.latestActiveTutorConversationInNamespace(
                    LEARNER_ID,
                    "tutor-lobby:active:",
                ),
            )
            assertTrue(
                runCatching {
                    store.latestActiveTutorConversationInNamespace(LEARNER_ID, "")
                }.isFailure,
            )
            assertTrue(
                runCatching {
                    store.latestActiveTutorConversationInNamespace(" $LEARNER_ID", "tutor-lobby:")
                }.isFailure,
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
                assertNull(request.terminalReceiptId)
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
            val submittedFixture = exactTutorChoiceEvidenceFixture(store, "archive-submitted")
            val cancelledFixture = exactTutorChoiceEvidenceFixture(store, "archive-cancelled")
            val submitted = store.finalizeTutorEvidenceRequest(
                submittedFixture.submitCommand,
            ).request
            val cancelled = store.finalizeTutorEvidenceRequest(
                cancelEvidence(cancelledFixture.prepare, terminalSeed = "before-archive"),
            ).request

            now += 1_000
            val archives = listOf(submittedFixture, cancelledFixture).mapIndexed { index, fixture ->
                ArchiveTutorConversationCommand(
                    learnerId = LEARNER_ID,
                    conversationId = fixture.prepare.conversationId,
                    conversationGeneration = 1,
                    expectedStateVersion = fixture.prepare.conversationStateVersion,
                    idempotencyKey = "archive-terminal-evidence-$index",
                    payloadFingerprint = sha256("archive-terminal-evidence-$index"),
                )
            }
            archives.forEach { archive -> store.archiveTutorConversation(archive) }
            now += 1_000
            archives.forEach { archive -> store.archiveTutorConversation(archive) }

            assertEquals(
                submitted,
                store.prepareTutorEvidenceRequest(submittedFixture.prepare).request,
            )
            assertEquals(
                cancelled,
                store.prepareTutorEvidenceRequest(cancelledFixture.prepare).request,
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
                assertNull(request.terminalReceiptId)
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
            val fixture = exactTutorChoiceEvidenceFixture(
                store = store,
                suffix = "scope-idempotency",
                persistEvidenceRequest = false,
            )
            val prepare = fixture.prepare
            val prepared = store.prepareTutorEvidenceRequest(prepare)
            val replay = store.prepareTutorEvidenceRequest(prepare)
            assertTrue(prepared.created)
            assertFalse(replay.created)
            assertEquals(
                prepared.request,
                store.openTutorEvidenceRequest(LEARNER_ID, prepare.evidenceRequestId),
            )
            assertNull(
                store.openTutorEvidenceRequest(OTHER_LEARNER_ID, prepare.evidenceRequestId),
            )
            assertNull(store.openTutorEvidenceRequest(LEARNER_ID, "missing-evidence-request"))

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
                fixture.submitCommand,
            )
            val submittedReplay = store.finalizeTutorEvidenceRequest(
                fixture.submitCommand,
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
                    fixture.submitCommand.copy(
                        directiveFingerprint = sha256("another-directive"),
                    ),
                )
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun identicalCallerPayloadAndChoiceTextCannotCollapseDistinctTrustedRequests() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(context()) { TRUSTED_NOW }
        try {
            val fixtures = listOf(
                exactTutorChoiceEvidenceFixture(store, "shared-answer-first"),
                exactTutorChoiceEvidenceFixture(store, "shared-answer-second"),
                exactTutorChoiceEvidenceFixture(
                    store = store,
                    suffix = "shared-answer-other",
                    learnerId = OTHER_LEARNER_ID,
                ),
            )
            fixtures.forEach { fixture ->
                store.finalizeTutorEvidenceRequest(
                    fixture.submitCommand.copy(
                        payloadFingerprint = SHARED_ANSWER_PAYLOAD,
                    ),
                )
            }

            val dao = store.database.tutorLearningMemoryDao()
            assertEquals(3, dao.countSourceFacts())
            assertEquals(3, dao.countAnchors())
        } finally {
            store.close()
        }
    }

    @Test
    fun callerFingerprintsCannotCollapseDifferentCapturedProblemsIntoOneAnchor() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(context()) { TRUSTED_NOW }
        try {
            val first = exactTutorChoiceEvidenceFixture(store, "anchor-first")
            val second = exactTutorChoiceEvidenceFixture(store, "anchor-second")
            val callerQuestionFingerprint = sha256("caller-collapsed-question")
            val callerRevisionFingerprint = sha256("caller-collapsed-revision")
            listOf(first, second).forEach { fixture ->
                store.finalizeTutorEvidenceRequest(
                    fixture.submitCommand.copy(
                        submission = checkNotNull(fixture.submitCommand.submission).copy(
                            questionFingerprint = callerQuestionFingerprint,
                            revisionFingerprint = callerRevisionFingerprint,
                        ),
                    ),
                )
            }

            val dao = store.database.tutorLearningMemoryDao()
            assertEquals(2, dao.countAnchors())
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
            val beforeRequest = exactTutorChoiceEvidenceFixture(
                store = store,
                suffix = "before-request",
                choiceSubmittedAtEpochMillis = TRUSTED_NOW - 1,
            )
            val afterTrustedClock = exactTutorChoiceEvidenceFixture(
                store = store,
                suffix = "after-trusted-clock",
                choiceSubmittedAtEpochMillis = TRUSTED_NOW + 1,
            )
            val bounded = exactTutorChoiceEvidenceFixture(
                store = store,
                suffix = "bounded-time",
                choiceSubmittedAtEpochMillis = TRUSTED_NOW,
            )

            assertConflict<TutorEvidenceConflictException> {
                store.finalizeTutorEvidenceRequest(beforeRequest.submitCommand)
            }
            assertConflict<TutorEvidenceConflictException> {
                store.finalizeTutorEvidenceRequest(afterTrustedClock.submitCommand)
            }
            val submitted = store.finalizeTutorEvidenceRequest(
                bounded.submitCommand,
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
            val sequential = exactTutorChoiceEvidenceFixture(store, "sequential-race")
            val sequentialCommands = (0 until SEQUENTIAL_RACE_CASES).map { index ->
                if (index % 2 == 0) {
                    sequential.submitCommand.copy(
                        idempotencyKey = "race-submit-$index",
                        payloadFingerprint = sha256("race-submit-$index"),
                    )
                } else {
                    cancelEvidence(sequential.prepare, "race-cancel-$index")
                }
            }
            store.finalizeTutorEvidenceRequest(sequentialCommands.first())
            sequentialCommands.drop(1).forEach { conflictingCommand ->
                assertConflict<TutorEvidenceConflictException> {
                    store.finalizeTutorEvidenceRequest(conflictingCommand)
                }
            }

            val concurrent = exactTutorChoiceEvidenceFixture(store, "concurrent-race")
            val concurrentCommands = (0 until CONCURRENT_RACE_CASES).map { index ->
                if (index % 2 == 0) {
                    concurrent.submitCommand.copy(
                        idempotencyKey = "concurrent-submit-$index",
                        payloadFingerprint = sha256("concurrent-submit-$index"),
                    )
                } else {
                    cancelEvidence(concurrent.prepare, "concurrent-cancel-$index")
                }
            }
            val outcomes = coroutineScope {
                concurrentCommands.map { command ->
                    async(Dispatchers.IO) {
                        runCatching {
                            store.finalizeTutorEvidenceRequest(command)
                        }
                    }
                }.awaitAll()
            }
            assertEquals(1, outcomes.count(Result<*>::isSuccess))
            assertEquals(
                CONCURRENT_RACE_CASES - 1,
                outcomes.count(Result<*>::isFailure),
            )

            val dao = store.database.tutorLearningMemoryDao()
            val submitted = dao.countEvidenceRequests(TutorEvidenceRequestStatus.SUBMITTED.name)
            val cancelled = dao.countEvidenceRequests(TutorEvidenceRequestStatus.CANCELLED.name)
            val pending = dao.countEvidenceRequests(TutorEvidenceRequestStatus.PENDING.name)
            assertEquals(2, submitted + cancelled)
            assertEquals(0, pending)
            assertEquals(submitted, dao.countSourceFacts())
            assertEquals(submitted, dao.countAnchors())
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
            factKind = LearningObservationFactKind.MODEL_EVALUATED_INCORRECT_RESPONSE,
            questionFingerprint = QUESTION_FINGERPRINT,
            revisionFingerprint = REVISION_FINGERPRINT,
            fingerprintVersion = "question-fingerprint-v1",
            responseFingerprint = sha256("response:$terminalSeed"),
            responseSummary = "选择了不正确的方向。",
            occurredAtEpochMillis = TRUSTED_NOW,
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

    private companion object {
        const val LEARNER_ID = "learner-local"
        const val CONVERSATION_ID = "conversation-v34"
        const val OTHER_LEARNER_ID = "learner-other"
        const val TURN_RECEIPT_ID = "turn-receipt-1"
        const val CLIENT_TURN_ID = "client-turn-1"
        const val EVIDENCE_REQUEST_ID = "evidence-request-1"
        const val ANCHOR_ID = "learning-anchor-1"
        const val TRUSTED_NOW = 10_000L
        const val SEQUENTIAL_RACE_CASES = 1_000
        const val CONCURRENT_RACE_CASES = 16
        val DIRECTIVE_FINGERPRINT = sha256("guided-choice-directive")
        val QUESTION_FINGERPRINT = sha256("question")
        val REVISION_FINGERPRINT = sha256("question-revision")
        val SHARED_ANSWER_PAYLOAD = sha256("shared-answer-terminal-payload")

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
