package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.database.CancelTutorEvidenceRequestCommand as DatabaseCancelEvidenceCommand
import com.tingyun.smartmistakebook.core.database.TutorConversationConflictException
import com.tingyun.smartmistakebook.core.database.TutorConversationArchiveWriteResult
import com.tingyun.smartmistakebook.core.database.TutorConversationSessionDatabasePort
import com.tingyun.smartmistakebook.core.database.TutorConversationWriteResult
import com.tingyun.smartmistakebook.core.database.TutorEvidenceCancellationResult
import com.tingyun.smartmistakebook.core.database.TutorEvidencePreparationResult
import com.tingyun.smartmistakebook.core.database.TutorTurnAllocationResult
import com.tingyun.smartmistakebook.core.database.TutorTurnReadResult
import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnResult
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.OpenTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.OpenTutorTurnResult
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceAnchorFingerprints
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceAssistance
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceCancellationReason
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceCurrentSessionReference
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceOutcome
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceSubmission
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceTerminal
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryConflictException
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryConflictReason
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryOperation
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorTurnReceipt
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTutorLearningMemoryRepositoryTest {
    @Test
    fun mapsDurableConversationTurnAndEvidenceOutcomes() = runBlocking {
        var createCalls = 0
        var openCalls = 0
        var archiveCalls = 0
        var finalizeCalls = 0
        var openTurnCalls = 0
        var openEvidenceCalls = 0
        var finalizeCommand: DatabaseCancelEvidenceCommand? = null
        val repository = RoomTutorLearningMemoryRepository(database { method, arguments ->
            when (method) {
                "createTutorConversation" -> TutorConversationWriteResult(
                    created = createCalls++ == 0,
                    conversation = activeConversation,
                )

                "openTutorConversation" -> if (openCalls++ == 0) activeConversation else null
                "latestActiveTutorConversation" -> activeConversation
                "latestActiveTutorConversationInNamespace" -> activeConversation
                "openTutorTurn" -> if (openTurnCalls++ == 0) {
                    TutorTurnReadResult.Found(receipt)
                } else {
                    TutorTurnReadResult.NotFound
                }
                "openTutorEvidenceRequest" ->
                    if (openEvidenceCalls++ == 0) pendingRequest else null
                "archiveTutorConversation" -> TutorConversationArchiveWriteResult(
                    archived = archiveCalls++ == 0,
                    conversation = archivedConversation,
                )
                "allocateTutorTurn" -> TutorTurnAllocationResult(
                    created = false,
                    conversation = activeConversation,
                    receipt = receipt,
                )

                "prepareTutorEvidenceRequest" -> TutorEvidencePreparationResult(
                    created = false,
                    request = pendingRequest,
                )

                "cancelTutorEvidenceRequest" -> {
                    finalizeCommand = arguments?.first() as DatabaseCancelEvidenceCommand
                    finalizeCalls += 1
                    TutorEvidenceCancellationResult(
                        replayed = false,
                        request = cancelledRequest,
                    )
                }

                "close" -> Unit
                else -> error("Unexpected database call: $method")
            }
        })

        assertTrue(repository.createConversation(createCommand) is CreateTutorConversationResult.Created)
        assertTrue(repository.createConversation(createCommand) is CreateTutorConversationResult.Replayed)
        assertTrue(repository.openConversation(openCommand) is OpenTutorConversationResult.Opened)
        assertEquals(OpenTutorConversationResult.NotFound, repository.openConversation(openCommand))
        assertEquals(activeConversation, repository.latestActiveConversation(LEARNER_ID))
        assertEquals(
            activeConversation,
            repository.latestActiveConversationInNamespace(LEARNER_ID, "conversation-"),
        )
        assertTrue(
            runCatching {
                repository.latestActiveConversationInNamespace(LEARNER_ID, "")
            }.isFailure,
        )
        assertTrue(runCatching { repository.latestActiveConversation(" learner-1") }.isFailure)
        assertEquals(OpenTutorTurnResult.Found(receipt), repository.openTurn(LEARNER_ID, receipt.turnReceiptId))
        assertEquals(OpenTutorTurnResult.NotFound, repository.openTurn(LEARNER_ID, receipt.turnReceiptId))
        assertTrue(runCatching { repository.openTurn(" learner-1", receipt.turnReceiptId) }.isFailure)
        assertTrue(runCatching { repository.openTurn(LEARNER_ID, " turn-1") }.isFailure)
        assertEquals(
            OpenTutorEvidenceResult.Found(pendingRequest),
            repository.openEvidenceRequest(LEARNER_ID, pendingRequest.evidenceRequestId),
        )
        assertEquals(
            OpenTutorEvidenceResult.NotFound,
            repository.openEvidenceRequest(LEARNER_ID, pendingRequest.evidenceRequestId),
        )
        assertTrue(
            runCatching {
                repository.openEvidenceRequest(" learner-1", pendingRequest.evidenceRequestId)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                repository.openEvidenceRequest(LEARNER_ID, " request-1")
            }.isFailure,
        )
        assertTrue(repository.archiveConversation(archiveCommand) is ArchiveTutorConversationResult.Archived)
        assertTrue(repository.archiveConversation(archiveCommand) is ArchiveTutorConversationResult.Replayed)
        assertTrue(repository.allocateTurn(allocateCommand) is AllocateTutorTurnResult.Replayed)
        assertTrue(repository.prepareEvidenceRequest(prepareCommand) is PrepareTutorEvidenceResult.Replayed)
        assertTrue(runCatching { repository.finalizeEvidence(submitCommand) }.isFailure)
        assertEquals(0, finalizeCalls)
        assertTrue(repository.finalizeEvidence(cancelCommand) is FinalizeTutorEvidenceResult.Cancelled)
        assertEquals(pendingRequest.evidenceRequestId, finalizeCommand?.evidenceRequestId)
    }

    @Test
    fun mapsDatabaseConflictsWithoutPersistenceDetails() = runBlocking {
        val repository = RoomTutorLearningMemoryRepository(database { method, _ ->
            if (method == "createTutorConversation") {
                throw TutorConversationConflictException("conversation-row-42 SQL")
            }
            error("Unexpected database call: $method")
        })

        val failure = runCatching { repository.createConversation(createCommand) }.exceptionOrNull()

        assertTrue(failure is TutorLearningMemoryConflictException)
        failure as TutorLearningMemoryConflictException
        assertEquals(TutorLearningMemoryOperation.CREATE_CONVERSATION, failure.operation)
        assertEquals(TutorLearningMemoryConflictReason.IDEMPOTENCY_PAYLOAD_MISMATCH, failure.reason)
        assertFalse(failure.message.orEmpty().contains("conversation-row-42"))
        assertFalse(failure.message.orEmpty().contains("sql", ignoreCase = true))
    }

    private fun database(
        call: (String, Array<out Any?>?) -> Any?,
    ): TutorConversationSessionDatabasePort = Proxy.newProxyInstance(
        TutorConversationSessionDatabasePort::class.java.classLoader,
        arrayOf(TutorConversationSessionDatabasePort::class.java),
    ) { _, method, arguments -> call(method.name, arguments) } as TutorConversationSessionDatabasePort

    private companion object {
        const val LEARNER_ID = "learner-1"
        val activeConversation = TutorConversation(
            conversationId = "conversation-1",
            learnerScopeId = LEARNER_ID,
            generation = 1,
            status = TutorConversationStatus.ACTIVE,
            createdAtEpochMillis = 100,
            archivedAtEpochMillis = null,
            stateVersion = 3,
        )
        val archivedConversation = activeConversation.copy(
            status = TutorConversationStatus.ARCHIVED,
            archivedAtEpochMillis = 200,
            stateVersion = 4,
        )
        val receipt = TutorTurnReceipt(
            turnReceiptId = "turn-1",
            conversationId = activeConversation.conversationId,
            conversationGeneration = 1,
            conversationStateVersion = 3,
            turnOrdinal = 1,
            subject = SubjectKind.MATH,
            problemAnchorId = "anchor-1",
            requestVersion = 1,
            modeVersion = 1,
            explanationMode = TutorExplanationMode.GUIDED,
            directiveFingerprint = hash('a'),
            studentMessageFingerprint = hash('b'),
            studentMessageSummary = "我卡在这一步。",
            occurredAtEpochMillis = 150,
        )
        val pendingRequest = TutorEvidenceRequest(
            evidenceRequestId = "evidence-1",
            conversationId = activeConversation.conversationId,
            conversationGeneration = 1,
            conversationStateVersion = 3,
            turnReceiptId = receipt.turnReceiptId,
            turnOrdinal = receipt.turnOrdinal,
            subject = SubjectKind.MATH,
            problemAnchorId = "anchor-1",
            kind = TutorEvidenceRequestKind.CHOICE,
            requestVersion = 1,
            modeVersion = 1,
            explanationMode = TutorExplanationMode.GUIDED,
            directiveFingerprint = hash('a'),
            status = TutorEvidenceRequestStatus.PENDING,
            stateVersion = 0,
            createdAtEpochMillis = 160,
            resolvedAtEpochMillis = null,
            terminalReceiptId = null,
        )
        val cancelledRequest = pendingRequest.copy(
            status = TutorEvidenceRequestStatus.CANCELLED,
            stateVersion = 1,
            resolvedAtEpochMillis = 200,
        )
        val createCommand = CreateTutorConversationCommand(
            learnerScopeId = LEARNER_ID,
            conversationId = activeConversation.conversationId,
            conversationGeneration = 1,
            clientIdempotencyKey = "create-key",
            payloadFingerprint = hash('d'),
            occurredAtEpochMillis = 100,
        )
        val openCommand = OpenTutorConversationCommand(LEARNER_ID, activeConversation.conversationId, 1)
        val archiveCommand = ArchiveTutorConversationCommand(
            learnerScopeId = LEARNER_ID,
            conversationId = activeConversation.conversationId,
            conversationGeneration = 1,
            expectedConversationStateVersion = 3,
            clientIdempotencyKey = "archive-key",
            payloadFingerprint = hash('e'),
            occurredAtEpochMillis = 200,
        )
        val allocateCommand = AllocateTutorTurnCommand(
            learnerScopeId = LEARNER_ID,
            conversationId = activeConversation.conversationId,
            conversationGeneration = 1,
            expectedConversationStateVersion = 2,
            expectedTurnOrdinal = 1,
            turnReceiptId = receipt.turnReceiptId,
            subject = receipt.subject,
            problemAnchorId = receipt.problemAnchorId,
            requestVersion = receipt.requestVersion,
            modeVersion = receipt.modeVersion,
            mode = receipt.explanationMode,
            directiveFingerprint = receipt.directiveFingerprint,
            studentMessageFingerprint = receipt.studentMessageFingerprint,
            studentMessageSummary = receipt.studentMessageSummary,
            clientIdempotencyKey = "turn-key",
            payloadFingerprint = hash('f'),
            occurredAtEpochMillis = receipt.occurredAtEpochMillis,
        )
        val prepareCommand = PrepareTutorEvidenceCommand(
            learnerScopeId = LEARNER_ID,
            conversationId = activeConversation.conversationId,
            conversationGeneration = 1,
            expectedConversationStateVersion = 3,
            turnReceiptId = receipt.turnReceiptId,
            turnOrdinal = receipt.turnOrdinal,
            subject = receipt.subject,
            problemAnchorId = receipt.problemAnchorId!!,
            evidenceRequestId = pendingRequest.evidenceRequestId,
            kind = pendingRequest.kind,
            requestVersion = pendingRequest.requestVersion,
            modeVersion = pendingRequest.modeVersion,
            mode = pendingRequest.explanationMode,
            directiveFingerprint = pendingRequest.directiveFingerprint,
            clientIdempotencyKey = "prepare-key",
            payloadFingerprint = hash('1'),
            occurredAtEpochMillis = pendingRequest.createdAtEpochMillis,
        )
        val submitCommand = finalizeCommand(
            TutorLearningEvidenceTerminal.Submitted(
                TutorLearningEvidenceSubmission(
                    anchors =
                        TutorLearningEvidenceAnchorFingerprints(
                            questionFingerprint = hash('2'),
                            problemRevisionFingerprint = hash('3'),
                            fingerprintVersion = "problem-fingerprint-v1",
                            turnFingerprint = hash('4'),
                            directiveFingerprint = pendingRequest.directiveFingerprint,
                        ),
                    responseFingerprint = hash('c'),
                    outcome = TutorLearningEvidenceOutcome.INCORRECT,
                    occurredAtEpochMillis = 170,
                    producerVersion = "tutor-evidence-v2",
                    currentSessionReference =
                        TutorLearningEvidenceCurrentSessionReference(
                            authorizationFingerprint = hash('7'),
                            learningWritePermissionVersion = 3,
                        ),
                    attemptOrdinal = 1,
                    retryCount = 0,
                    hintCount = 0,
                    answerWasRevealed = false,
                    independentlyAnswered = true,
                    assistance = TutorLearningEvidenceAssistance.INDEPENDENT,
                ),
            ),
        )
        val cancelCommand = finalizeCommand(
            TutorLearningEvidenceTerminal.Cancelled(
                TutorLearningEvidenceCancellationReason.USER_CANCELLED,
            ),
        )

        fun finalizeCommand(terminal: TutorLearningEvidenceTerminal) = FinalizeTutorEvidenceCommand(
            learnerScopeId = LEARNER_ID,
            conversationId = activeConversation.conversationId,
            conversationGeneration = 1,
            conversationStateVersion = 3,
            turnReceiptId = receipt.turnReceiptId,
            turnOrdinal = receipt.turnOrdinal,
            subject = receipt.subject,
            problemAnchorId = receipt.problemAnchorId!!,
            evidenceRequestId = pendingRequest.evidenceRequestId,
            kind = pendingRequest.kind,
            requestVersion = pendingRequest.requestVersion,
            modeVersion = pendingRequest.modeVersion,
            mode = pendingRequest.explanationMode,
            directiveFingerprint = pendingRequest.directiveFingerprint,
            expectedEvidenceStateVersion = 0,
            terminal = terminal,
            clientIdempotencyKey = "finalize-key",
            payloadFingerprint = hash('5'),
            occurredAtEpochMillis = 200,
        )

        fun hash(character: Char): String = character.toString().repeat(64)
    }
}
