package com.tingyun.smartmistakebook.core.domain

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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorLearningMemoryRepositoryTest {
    @Test
    fun `exact evidence read default validates scope and reveals nothing`() = runBlocking {
        val repository = repositoryWithLatest(null)

        assertEquals(
            OpenTutorEvidenceResult.NotFound,
            repository.openEvidenceRequest("learner-1", "evidence-1"),
        )
        assertTrue(
            runCatching { repository.openEvidenceRequest(" learner-1", "evidence-1") }.isFailure,
        )
        assertTrue(
            runCatching { repository.openEvidenceRequest("learner-1", " evidence-1") }.isFailure,
        )
    }

    @Test
    fun `namespaced latest default rejects unsafe prefixes and never crosses namespaces`() =
        runBlocking {
            val capturedLatest = activeConversation().copy(
                conversationId = "captured:tutor-session",
            )
            val capturedRepository = repositoryWithLatest(capturedLatest)

            assertTrue(
                runCatching {
                    capturedRepository.latestActiveConversationInNamespace("learner-1", "")
                }.isFailure,
            )
            assertNull(
                capturedRepository.latestActiveConversationInNamespace(
                    "learner-1",
                    "tutor-lobby:",
                ),
            )
            assertNull(
                capturedRepository.latestActiveConversationInNamespace(
                    "learner-1",
                    "captured:tutor-sessions",
                ),
            )
            assertEquals(
                capturedLatest,
                capturedRepository.latestActiveConversationInNamespace(
                    "learner-1",
                    "captured:tutor-",
                ),
            )
        }

    @Test
    fun `conversation mutations bind learner opaque id generation version and payload`() {
        val create = createConversationCommand()
        val archive = ArchiveTutorConversationCommand(
            learnerScopeId = create.learnerScopeId,
            conversationId = create.conversationId,
            conversationGeneration = create.conversationGeneration,
            expectedConversationStateVersion = 4,
            clientIdempotencyKey = "archive-client-key",
            payloadFingerprint = hash('b'),
            occurredAtEpochMillis = 200,
        )

        assertNotEquals(create, create.copy(learnerScopeId = "other-learner"))
        assertNotEquals(create, create.copy(conversationGeneration = 2))
        assertNotEquals(create, create.copy(payloadFingerprint = hash('c')))
        assertNotEquals(archive, archive.copy(expectedConversationStateVersion = 5))
        assertEquals(0, archive.copy(expectedConversationStateVersion = 0).expectedConversationStateVersion)
        assertTrue(
            runCatching {
                archive.copy(expectedConversationStateVersion = -1)
            }.isFailure,
        )
    }

    @Test
    fun `turn allocation binds exact ordinal mode directive and student payload`() {
        val command = allocateTurnCommand()

        assertNotEquals(command, command.copy(expectedTurnOrdinal = 3))
        assertNotEquals(command, command.copy(modeVersion = 8))
        assertNotEquals(command, command.copy(mode = TutorExplanationMode.DIRECT))
        assertNotEquals(command, command.copy(directiveFingerprint = hash('d')))
        assertNotEquals(command, command.copy(studentMessageFingerprint = hash('e')))
        assertTrue(runCatching { command.copy(expectedTurnOrdinal = 0) }.isFailure)
        assertTrue(
            runCatching {
                command.copy(studentMessageSummary = "x".repeat(513))
            }.isFailure,
        )
    }

    @Test
    fun `prepared evidence requests always belong to guided mode`() {
        TutorEvidenceRequestKind.entries.forEach { kind ->
            assertTrue(
                runCatching {
                    prepareEvidenceCommand(
                        kind = kind,
                        mode = TutorExplanationMode.DIRECT,
                    )
                }.isFailure,
            )
        }
    }

    @Test
    fun `evidence preparation cannot target an unanchored or general-subject turn`() {
        val command = prepareEvidenceCommand()

        assertTrue(runCatching { command.copy(problemAnchorId = "") }.isFailure)
        assertTrue(runCatching { command.copy(subject = SubjectKind.GENERAL) }.isFailure)
        assertNotEquals(command, command.copy(turnOrdinal = 3))
        assertNotEquals(command, command.copy(turnReceiptId = "turn-other"))
    }

    @Test
    fun `submitted evidence carries a local fact and exact fingerprint anchors`() {
        val command = finalizeSubmittedCommand()
        val submitted = command.terminal as TutorLearningEvidenceTerminal.Submitted

        assertEquals(LearningObservationSource.TUTOR_CHOICE, submitted.sourceFact.source)
        assertEquals(
            LearningObservationFactKind.MODEL_EVALUATED_INCORRECT_RESPONSE,
            submitted.sourceFact.factKind,
        )
        assertEquals(command.directiveFingerprint, submitted.anchors.directiveFingerprint)
        assertEquals(command.problemAnchorId, submitted.sourceFact.anchorId)
        assertEquals(command.evidenceRequestId, submitted.sourceFact.evidenceRequestId)
        assertTrue(
            runCatching {
                command.copy(
                    terminal = submitted.copy(
                        anchors = submitted.anchors.copy(fingerprintVersion = " "),
                    ),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                command.copy(
                    terminal = submitted.copy(
                        sourceFact = submitted.sourceFact.copy(
                            conversationId = "other-conversation",
                        ),
                    ),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                command.copy(
                    terminal = submitted.copy(
                        anchors = submitted.anchors.copy(directiveFingerprint = hash('f')),
                    ),
                )
            }.isFailure,
        )
    }

    @Test
    fun `cancelled evidence is structurally fact free and finalize expects pending state`() {
        val submitted = finalizeSubmittedCommand()
        val cancelled = submitted.copy(
            terminal = TutorLearningEvidenceTerminal.Cancelled(
                reason = TutorLearningEvidenceCancellationReason.GUIDANCE_DISABLED,
            ),
            payloadFingerprint = hash('f'),
        )

        assertTrue(cancelled.terminal is TutorLearningEvidenceTerminal.Cancelled)
        assertEquals(TutorEvidenceRequestStatus.PENDING, cancelled.expectedEvidenceStatus)
        assertTrue(
            runCatching {
                cancelled.copy(expectedEvidenceStatus = TutorEvidenceRequestStatus.SUBMITTED)
            }.isFailure,
        )
    }

    @Test
    fun `finalization rejects a fact type that does not match its prepared request kind`() {
        val command = finalizeSubmittedCommand()
        val submitted = command.terminal as TutorLearningEvidenceTerminal.Submitted

        assertTrue(
            runCatching {
                command.copy(
                    terminal = submitted.copy(
                        sourceFact = submitted.sourceFact.copy(
                            source = LearningObservationSource.TUTOR_FREE_RESPONSE,
                            factKind = LearningObservationFactKind.OPEN_RESPONSE_SUBMITTED,
                        ),
                    ),
                )
            }.isFailure,
        )
    }

    @Test
    fun `results distinguish created replayed submitted and cancelled outcomes`() {
        val active = activeConversation()
        val receipt = turnReceipt()
        val pending = pendingEvidenceRequest()
        val fact = sourceFact()

        assertTrue(CreateTutorConversationResult.Created(active).created)
        assertTrue(!CreateTutorConversationResult.Replayed(active).created)
        assertTrue(AllocateTutorTurnResult.Created(active, receipt).created)
        assertTrue(!AllocateTutorTurnResult.Replayed(active, receipt).created)
        assertTrue(PrepareTutorEvidenceResult.Created(pending).created)
        assertTrue(!PrepareTutorEvidenceResult.Replayed(pending).created)

        val submitted = pending.copy(
            status = TutorEvidenceRequestStatus.SUBMITTED,
            stateVersion = 1,
            resolvedAtEpochMillis = 300,
            terminalSourceFactId = fact.sourceFactId,
        )
        val cancelled = pending.copy(
            status = TutorEvidenceRequestStatus.CANCELLED,
            stateVersion = 1,
            resolvedAtEpochMillis = 300,
        )
        assertEquals(
            TutorEvidenceRequestStatus.SUBMITTED,
            FinalizeTutorEvidenceResult.Submitted(submitted, fact).request.status,
        )
        assertEquals(
            TutorEvidenceRequestStatus.CANCELLED,
            FinalizeTutorEvidenceResult.Cancelled(cancelled).request.status,
        )
        assertNull(FinalizeTutorEvidenceResult.Replayed(cancelled, null).sourceFact)
    }

    @Test
    fun `conflict reports operation and safe reason without storage identity`() {
        val conflict = TutorLearningMemoryConflictException(
            operation = TutorLearningMemoryOperation.FINALIZE_EVIDENCE,
            reason = TutorLearningMemoryConflictReason.EVIDENCE_NOT_PENDING,
        )

        assertEquals(TutorLearningMemoryOperation.FINALIZE_EVIDENCE, conflict.operation)
        assertEquals(TutorLearningMemoryConflictReason.EVIDENCE_NOT_PENDING, conflict.reason)
        assertTrue(!conflict.message.orEmpty().contains("row", ignoreCase = true))
        assertTrue(!conflict.message.orEmpty().contains("sql", ignoreCase = true))
    }

    private fun createConversationCommand() = CreateTutorConversationCommand(
        learnerScopeId = "learner-1",
        conversationId = "conversation-1",
        conversationGeneration = 1,
        clientIdempotencyKey = "create-client-key",
        payloadFingerprint = hash('a'),
        occurredAtEpochMillis = 100,
    )

    private fun allocateTurnCommand() = AllocateTutorTurnCommand(
        learnerScopeId = "learner-1",
        conversationId = "conversation-1",
        conversationGeneration = 1,
        expectedConversationStateVersion = 2,
        expectedTurnOrdinal = 2,
        turnReceiptId = "turn-2",
        subject = SubjectKind.MATH,
        problemAnchorId = "anchor-1",
        requestVersion = 3,
        modeVersion = 7,
        mode = TutorExplanationMode.GUIDED,
        directiveFingerprint = hash('a'),
        studentMessageFingerprint = hash('b'),
        studentMessageSummary = "我选择 B，因为函数在这里递增。",
        clientIdempotencyKey = "turn-client-key",
        payloadFingerprint = hash('c'),
        occurredAtEpochMillis = 150,
    )

    private fun prepareEvidenceCommand(
        kind: TutorEvidenceRequestKind = TutorEvidenceRequestKind.CHOICE,
        mode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    ) = PrepareTutorEvidenceCommand(
        learnerScopeId = "learner-1",
        conversationId = "conversation-1",
        conversationGeneration = 1,
        expectedConversationStateVersion = 3,
        turnReceiptId = "turn-2",
        turnOrdinal = 2,
        subject = SubjectKind.MATH,
        problemAnchorId = "anchor-1",
        evidenceRequestId = "evidence-1",
        kind = kind,
        requestVersion = 3,
        modeVersion = 7,
        mode = mode,
        directiveFingerprint = hash('a'),
        clientIdempotencyKey = "prepare-client-key",
        payloadFingerprint = hash('b'),
        occurredAtEpochMillis = 160,
    )

    private fun finalizeSubmittedCommand(): FinalizeTutorEvidenceCommand {
        val prepared = prepareEvidenceCommand()
        return FinalizeTutorEvidenceCommand(
            learnerScopeId = prepared.learnerScopeId,
            conversationId = prepared.conversationId,
            conversationGeneration = prepared.conversationGeneration,
            conversationStateVersion = prepared.expectedConversationStateVersion,
            turnReceiptId = prepared.turnReceiptId,
            turnOrdinal = prepared.turnOrdinal,
            subject = prepared.subject,
            problemAnchorId = prepared.problemAnchorId,
            evidenceRequestId = prepared.evidenceRequestId,
            kind = prepared.kind,
            requestVersion = prepared.requestVersion,
            modeVersion = prepared.modeVersion,
            mode = prepared.mode,
            directiveFingerprint = prepared.directiveFingerprint,
            expectedEvidenceStateVersion = 0,
            expectedEvidenceStatus = TutorEvidenceRequestStatus.PENDING,
            terminal = TutorLearningEvidenceTerminal.Submitted(
                sourceFact = sourceFact(),
                anchors = TutorLearningEvidenceAnchorFingerprints(
                    questionFingerprint = hash('1'),
                    problemRevisionFingerprint = hash('2'),
                    fingerprintVersion = "problem-fingerprint-v1",
                    turnFingerprint = hash('3'),
                    directiveFingerprint = prepared.directiveFingerprint,
                ),
            ),
            clientIdempotencyKey = "finalize-client-key",
            payloadFingerprint = hash('c'),
            occurredAtEpochMillis = 200,
        )
    }

    private fun activeConversation() = TutorConversation(
        conversationId = "conversation-1",
        learnerScopeId = "learner-1",
        generation = 1,
        status = TutorConversationStatus.ACTIVE,
        createdAtEpochMillis = 100,
        archivedAtEpochMillis = null,
        stateVersion = 3,
    )

    private fun turnReceipt() = TutorTurnReceipt(
        turnReceiptId = "turn-2",
        conversationId = "conversation-1",
        conversationGeneration = 1,
        conversationStateVersion = 3,
        turnOrdinal = 2,
        subject = SubjectKind.MATH,
        problemAnchorId = "anchor-1",
        requestVersion = 3,
        modeVersion = 7,
        explanationMode = TutorExplanationMode.GUIDED,
        directiveFingerprint = hash('a'),
        studentMessageFingerprint = hash('b'),
        studentMessageSummary = "我选择 B，因为函数在这里递增。",
        occurredAtEpochMillis = 150,
    )

    private fun pendingEvidenceRequest() = TutorEvidenceRequest(
        evidenceRequestId = "evidence-1",
        conversationId = "conversation-1",
        conversationGeneration = 1,
        conversationStateVersion = 3,
        turnReceiptId = "turn-2",
        turnOrdinal = 2,
        subject = SubjectKind.MATH,
        problemAnchorId = "anchor-1",
        kind = TutorEvidenceRequestKind.CHOICE,
        requestVersion = 3,
        modeVersion = 7,
        explanationMode = TutorExplanationMode.GUIDED,
        directiveFingerprint = hash('a'),
        status = TutorEvidenceRequestStatus.PENDING,
        stateVersion = 0,
        createdAtEpochMillis = 160,
        resolvedAtEpochMillis = null,
        terminalSourceFactId = null,
    )

    private fun sourceFact() = LearningObservationSourceFact(
        sourceFactId = "fact-1",
        learnerScopeId = "learner-1",
        source = LearningObservationSource.TUTOR_CHOICE,
        factKind = LearningObservationFactKind.MODEL_EVALUATED_INCORRECT_RESPONSE,
        anchorId = "anchor-1",
        subject = SubjectKind.MATH,
        conversationGeneration = 1,
        conversationId = "conversation-1",
        turnReceiptId = "turn-2",
        evidenceRequestId = "evidence-1",
        responseFingerprint = hash('4'),
        responseSummary = "选择 B；本地判定不正确。",
        occurredAtEpochMillis = 180,
        sourceVersion = "tutor-source-v1",
    )

    private fun repositoryWithLatest(
        latest: TutorConversation?,
    ) = object : TutorLearningMemoryRepository {
        override suspend fun createConversation(
            command: CreateTutorConversationCommand,
        ): CreateTutorConversationResult = error("Not used")

        override suspend fun openConversation(
            command: OpenTutorConversationCommand,
        ): OpenTutorConversationResult = error("Not used")

        override suspend fun latestActiveConversation(
            learnerScopeId: String,
        ): TutorConversation? = latest

        override suspend fun archiveConversation(
            command: ArchiveTutorConversationCommand,
        ): ArchiveTutorConversationResult = error("Not used")

        override suspend fun allocateTurn(
            command: AllocateTutorTurnCommand,
        ): AllocateTutorTurnResult = error("Not used")

        override suspend fun prepareEvidenceRequest(
            command: PrepareTutorEvidenceCommand,
        ): PrepareTutorEvidenceResult = error("Not used")

        override suspend fun finalizeEvidence(
            command: FinalizeTutorEvidenceCommand,
        ): FinalizeTutorEvidenceResult = error("Not used")
    }

    private fun hash(char: Char): String = char.toString().repeat(64)
}
