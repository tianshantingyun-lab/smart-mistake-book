package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.database.TutorConversationConflictException
import com.tingyun.smartmistakebook.core.database.TutorConversationArchiveWriteResult
import com.tingyun.smartmistakebook.core.database.TutorConversationWriteResult
import com.tingyun.smartmistakebook.core.database.TutorEvidenceFinalizationResult
import com.tingyun.smartmistakebook.core.database.TutorEvidencePreparationResult
import com.tingyun.smartmistakebook.core.database.TutorTurnAllocationResult
import com.tingyun.smartmistakebook.core.database.FinalizeTutorEvidenceRequestCommand as DatabaseFinalizeEvidenceCommand
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
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceAnchorFingerprints
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceCancellationReason
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceTerminal
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryConflictException
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryConflictReason
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryOperation
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.LearningObservationSourceFact
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTutorLearningMemoryRepositoryTest {
    @Test
    fun mapsDurableConversationTurnAndEvidenceOutcomes() = runBlocking {
        var createCalls = 0
        var openCalls = 0
        var archiveCalls = 0
        var finalizeCalls = 0
        var finalizeCommand: DatabaseFinalizeEvidenceCommand? = null
        val repository = RoomTutorLearningMemoryRepository(database { method, arguments ->
            when (method) {
                "createTutorConversation" -> TutorConversationWriteResult(
                    created = createCalls++ == 0,
                    conversation = activeConversation,
                )

                "openTutorConversation" -> if (openCalls++ == 0) activeConversation else null
                "latestActiveTutorConversation" -> activeConversation
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

                "finalizeTutorEvidenceRequest" -> {
                    finalizeCommand = arguments?.first() as DatabaseFinalizeEvidenceCommand
                    if (finalizeCalls++ == 0) {
                        TutorEvidenceFinalizationResult(
                            replayed = false,
                            request = submittedRequest,
                            anchor = null,
                            sourceFact = sourceFact,
                        )
                    } else {
                        TutorEvidenceFinalizationResult(
                            replayed = false,
                            request = cancelledRequest,
                            anchor = null,
                            sourceFact = null,
                        )
                    }
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
        assertTrue(runCatching { repository.latestActiveConversation(" learner-1") }.isFailure)
        assertTrue(repository.archiveConversation(archiveCommand) is ArchiveTutorConversationResult.Archived)
        assertTrue(repository.archiveConversation(archiveCommand) is ArchiveTutorConversationResult.Replayed)
        assertTrue(repository.allocateTurn(allocateCommand) is AllocateTutorTurnResult.Replayed)
        assertTrue(repository.prepareEvidenceRequest(prepareCommand) is PrepareTutorEvidenceResult.Replayed)
        assertTrue(repository.finalizeEvidence(submitCommand) is FinalizeTutorEvidenceResult.Submitted)
        assertEquals(
            "problem-fingerprint-v1",
            finalizeCommand?.submission?.fingerprintVersion,
        )
        assertTrue(repository.finalizeEvidence(cancelCommand) is FinalizeTutorEvidenceResult.Cancelled)
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
    ): StudyDatabasePort = Proxy.newProxyInstance(
        StudyDatabasePort::class.java.classLoader,
        arrayOf(StudyDatabasePort::class.java),
    ) { _, method, arguments -> call(method.name, arguments) } as StudyDatabasePort

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
            terminalSourceFactId = null,
        )
        val sourceFact = LearningObservationSourceFact(
            sourceFactId = "source-fact-1",
            learnerScopeId = LEARNER_ID,
            source = LearningObservationSource.TUTOR_CHOICE,
            factKind = LearningObservationFactKind.VERIFIED_INCORRECT_RESPONSE,
            anchorId = pendingRequest.problemAnchorId,
            subject = pendingRequest.subject,
            conversationId = pendingRequest.conversationId,
            conversationGeneration = pendingRequest.conversationGeneration,
            turnReceiptId = pendingRequest.turnReceiptId,
            evidenceRequestId = pendingRequest.evidenceRequestId,
            responseFingerprint = hash('c'),
            responseSummary = "选择不正确。",
            occurredAtEpochMillis = 170,
            sourceVersion = "tutor-source-v1",
        )
        val submittedRequest = pendingRequest.copy(
            status = TutorEvidenceRequestStatus.SUBMITTED,
            stateVersion = 1,
            resolvedAtEpochMillis = 200,
            terminalSourceFactId = sourceFact.sourceFactId,
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
                sourceFact = sourceFact,
                anchors = TutorLearningEvidenceAnchorFingerprints(
                    questionFingerprint = hash('2'),
                    problemRevisionFingerprint = hash('3'),
                    fingerprintVersion = "problem-fingerprint-v1",
                    turnFingerprint = hash('4'),
                    directiveFingerprint = pendingRequest.directiveFingerprint,
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
