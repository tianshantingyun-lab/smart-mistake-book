package com.tingyun.smartmistakebook.core.data.tutor

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
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.mastery.database.EphemeralTutorProblemLearningContext
import com.tingyun.smartmistakebook.core.mastery.database.LearningObservationInertReason
import com.tingyun.smartmistakebook.core.mastery.database.RecordTrustedLearningObservationCommand
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningObservationDisposition
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningObservationResult
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningObservationTerminalReceipt
import com.tingyun.smartmistakebook.core.mastery.database.VerifiedEphemeralKnowledgeEvidence
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerMasteryTutorLearningMemoryRepositoryTest {
    @Test
    fun submittedEvidenceWritesMasteryExactlyOnceAndNeverCallsLegacyFinalizer() = runBlocking {
        val legacy = RecordingLegacyRepository()
        val session = DurableSessionPort()
        val observations = mutableListOf<RecordTrustedLearningObservationCommand>()
        val repository = repository(legacy, session, observations)

        val first = repository.finalizeEvidence(submitCommand())
        legacy.openedRequest = first.request
        val replay = repository.finalizeEvidence(submitCommand())

        assertTrue(first is FinalizeTutorEvidenceResult.Submitted)
        assertTrue(replay is FinalizeTutorEvidenceResult.Replayed)
        assertEquals(0, legacy.finalizeCalls)
        assertEquals(1, observations.size)
        assertEquals(
            MASTERY_CANDIDATE_ID,
            checkNotNull(first.receipt).receiptId,
        )
        val observation = observations.single()
        assertEquals(2, observation.retryCount)
        assertEquals(2, observation.hintCount)
        assertTrue(observation.answerRevealed)
        assertTrue(!observation.independentlyAnswered)
        val context = observation.context as EphemeralTutorProblemLearningContext
        assertEquals(1, context.verifiedKnowledgeEvidence.size)
        assertEquals(KNOWLEDGE_NODE, context.verifiedKnowledgeEvidence.single().knowledgeNode)
    }

    @Test
    fun cancellationRemainsSessionOnlyAndDoesNotReachMastery() = runBlocking {
        val legacy = RecordingLegacyRepository()
        val observations = mutableListOf<RecordTrustedLearningObservationCommand>()
        val repository = repository(legacy, DurableSessionPort(), observations)

        val result = repository.finalizeEvidence(cancelCommand())

        assertTrue(result is FinalizeTutorEvidenceResult.Cancelled)
        assertEquals(1, legacy.finalizeCalls)
        assertTrue(observations.isEmpty())
    }

    @Test
    fun emptyKnowledgeProofsFailBeforeMasteryOrSessionAcknowledgement() = runBlocking {
        val observations = mutableListOf<RecordTrustedLearningObservationCommand>()
        val session = DurableSessionPort()
        val repository =
            repository(
                legacy = RecordingLegacyRepository(),
                session = session,
                observations = observations,
                proofs = emptyList(),
            )

        assertTrue(runCatching { repository.finalizeEvidence(submitCommand()) }.isFailure)
        assertTrue(observations.isEmpty())
        assertEquals(0, session.acknowledgements)
    }

    @Test
    fun ordinaryInertAndDuplicateOfOrdinaryInertNeverAcknowledgeSession() = runBlocking {
        listOf(
            TrustedLearningObservationDisposition.INERT,
            TrustedLearningObservationDisposition.DUPLICATE,
        ).forEach { returnedDisposition ->
            val observations = mutableListOf<RecordTrustedLearningObservationCommand>()
            val session = DurableSessionPort()
            val repository =
                repository(
                    legacy = RecordingLegacyRepository(),
                    session = session,
                    observations = observations,
                    result = { observation ->
                        trustedResult(
                            observation = observation,
                            returnedDisposition = returnedDisposition,
                            originalDisposition = TrustedLearningObservationDisposition.INERT,
                            originalInertReason = LearningObservationInertReason.NO_LEARNING_OUTCOME,
                        )
                    },
                )

            assertTrue(runCatching { repository.finalizeEvidence(submitCommand()) }.isFailure)
            assertEquals(1, observations.size)
            assertEquals(0, session.acknowledgements)
        }
    }

    @Test
    fun duplicateOfAdmittedAndWeakReviewPendingAreAcknowledged() = runBlocking {
        val acceptedDecisions =
            listOf(
                Triple(
                    TrustedLearningObservationDisposition.DUPLICATE,
                    TrustedLearningObservationDisposition.ADMITTED,
                    null,
                ),
                Triple(
                    TrustedLearningObservationDisposition.INERT,
                    TrustedLearningObservationDisposition.INERT,
                    LearningObservationInertReason.WEAK_CONFLICT_REQUIRES_REVIEW,
                ),
            )
        acceptedDecisions.forEach { (returned, original, reason) ->
            val session = DurableSessionPort()
            val result =
                repository(
                    legacy = RecordingLegacyRepository(),
                    session = session,
                    observations = mutableListOf(),
                    result = { observation ->
                        trustedResult(observation, returned, original, reason)
                    },
                ).finalizeEvidence(submitCommand())

            assertTrue(result is FinalizeTutorEvidenceResult.Submitted)
            assertEquals(1, session.acknowledgements)
            assertEquals(MASTERY_RECEIPT_FINGERPRINT, checkNotNull(result.receipt).receiptFingerprint)
        }
    }

    @Test
    fun closedProductionOwnerRejectsLateSubmissionBeforeSessionOrMastery() = runBlocking {
        val legacy = RecordingLegacyRepository()
        val session = DurableSessionPort()
        val observations = mutableListOf<RecordTrustedLearningObservationCommand>()
        val repository =
            repository(
                legacy = legacy,
                session = session,
                observations = observations,
                ownerIsCurrent = { false },
            )

        assertTrue(runCatching { repository.finalizeEvidence(submitCommand()) }.isFailure)
        assertEquals(0, legacy.finalizeCalls)
        assertEquals(0, session.acknowledgements)
        assertTrue(observations.isEmpty())
    }

    private fun repository(
        legacy: RecordingLegacyRepository,
        session: TutorLearningEvidenceSessionPort,
        observations: MutableList<RecordTrustedLearningObservationCommand>,
        proofs: List<VerifiedKnowledgeReferenceProof>? = listOf(knowledgeProof()),
        ownerIsCurrent: () -> Boolean = { true },
        result: (RecordTrustedLearningObservationCommand) -> TrustedLearningObservationResult =
            { observation ->
                trustedResult(
                    observation = observation,
                    returnedDisposition = TrustedLearningObservationDisposition.ADMITTED,
                    originalDisposition = TrustedLearningObservationDisposition.ADMITTED,
                    originalInertReason = null,
                )
            },
    ) = LearnerMasteryTutorLearningMemoryRepository(
        delegate = legacy,
        boundLearnerId = LEARNER_ID,
        evidenceSession = session,
        observationSink =
            TutorMasteryObservationSink { observation ->
                observations += observation
                result(observation)
            },
        currentSessionProofSource =
            TutorLearningEvidenceCurrentSessionProofSource { candidate ->
                check(
                    candidate.currentSessionReference.authorizationFingerprint ==
                        CURRENT_SESSION_AUTHORIZATION_FINGERPRINT,
                )
                proofs
            },
        knowledgeEvidenceAuthorizer =
            TutorKnowledgeEvidenceAuthorizer { proof ->
                check(PROOF_AUTHORITY.verifier.verifies(proof))
                verifiedEvidence(proof)
            },
        productionOwnerIsCurrent = ownerIsCurrent,
    )

    private class DurableSessionPort : TutorLearningEvidenceSessionPort {
        private var intent: TutorLearningEvidenceSessionIntent? = null
        private var receipt: TutorLearningEvidenceSessionReceipt? = null
        var acknowledgements: Int = 0
            private set

        override suspend fun begin(
            requested: TutorLearningEvidenceSessionIntent,
        ): TutorLearningEvidenceSessionBeginResult {
            val existing = intent
            if (existing == null) intent = requested else check(existing == requested)
            return receipt?.let(TutorLearningEvidenceSessionBeginResult::Finalized)
                ?: TutorLearningEvidenceSessionBeginResult.Pending(checkNotNull(intent))
        }

        override suspend fun acknowledge(
            acknowledgement: TutorLearningEvidenceSessionAcknowledgement,
        ): TutorLearningEvidenceSessionAcknowledgeResult {
            val existing = receipt
            val next =
                TutorLearningEvidenceSessionReceipt(
                    scope = acknowledgement.scope,
                    evidenceRequestId = acknowledgement.evidenceRequestId,
                    candidateFingerprint = acknowledgement.candidateFingerprint,
                    masteryReceiptId = acknowledgement.masteryReceiptId,
                    masteryReceiptFingerprint = acknowledgement.masteryReceiptFingerprint,
                    evidenceStateVersion = 1,
                    createdAtEpochMillis = 90,
                    resolvedAtEpochMillis = 110,
                )
            if (existing != null) {
                check(existing == next)
                return TutorLearningEvidenceSessionAcknowledgeResult.Replayed(existing)
            }
            receipt = next
            acknowledgements += 1
            return TutorLearningEvidenceSessionAcknowledgeResult.Acknowledged(next)
        }
    }

    private class RecordingLegacyRepository : TutorLearningMemoryRepository {
        var finalizeCalls: Int = 0
            private set
        var openedRequest: TutorEvidenceRequest = pendingRequest()

        override suspend fun openEvidenceRequest(
            learnerScopeId: String,
            evidenceRequestId: String,
        ): OpenTutorEvidenceResult =
            if (learnerScopeId == LEARNER_ID && evidenceRequestId == EVIDENCE_REQUEST_ID) {
                OpenTutorEvidenceResult.Found(openedRequest)
            } else {
                OpenTutorEvidenceResult.NotFound
            }

        override suspend fun finalizeEvidence(
            command: FinalizeTutorEvidenceCommand,
        ): FinalizeTutorEvidenceResult {
            finalizeCalls += 1
            check(command.terminal is TutorLearningEvidenceTerminal.Cancelled)
            return FinalizeTutorEvidenceResult.Cancelled(
                pendingRequest().copy(
                    status = TutorEvidenceRequestStatus.CANCELLED,
                    stateVersion = 1,
                    resolvedAtEpochMillis = 110,
                ),
            )
        }

        override suspend fun createConversation(
            command: CreateTutorConversationCommand,
        ): CreateTutorConversationResult = error("unused")

        override suspend fun openConversation(
            command: OpenTutorConversationCommand,
        ): OpenTutorConversationResult = error("unused")

        override suspend fun latestActiveConversation(
            learnerScopeId: String,
        ): TutorConversation? = error("unused")

        override suspend fun latestActiveConversationInNamespace(
            learnerScopeId: String,
            conversationIdPrefix: String,
        ): TutorConversation? = error("unused")

        override suspend fun openTurn(
            learnerScopeId: String,
            turnReceiptId: String,
        ): OpenTutorTurnResult = error("unused")

        override suspend fun archiveConversation(
            command: ArchiveTutorConversationCommand,
        ): ArchiveTutorConversationResult = error("unused")

        override suspend fun allocateTurn(
            command: AllocateTutorTurnCommand,
        ): AllocateTutorTurnResult = error("unused")

        override suspend fun prepareEvidenceRequest(
            command: PrepareTutorEvidenceCommand,
        ): PrepareTutorEvidenceResult = error("unused")
    }

    private fun submitCommand() =
        finalizationCommand(
            TutorLearningEvidenceTerminal.Submitted(
                TutorLearningEvidenceSubmission(
                    anchors =
                        TutorLearningEvidenceAnchorFingerprints(
                            questionFingerprint = hash('1'),
                            problemRevisionFingerprint = hash('2'),
                            fingerprintVersion = "problem-v1",
                            turnFingerprint = hash('3'),
                            directiveFingerprint = DIRECTIVE_FINGERPRINT,
                        ),
                    responseFingerprint = hash('4'),
                    outcome = TutorLearningEvidenceOutcome.ASSISTED_CORRECT,
                    occurredAtEpochMillis = 100,
                    producerVersion = "captured-choice-v2",
                    currentSessionReference =
                        TutorLearningEvidenceCurrentSessionReference(
                            authorizationFingerprint =
                                CURRENT_SESSION_AUTHORIZATION_FINGERPRINT,
                            learningWritePermissionVersion = 3,
                        ),
                    attemptOrdinal = 3,
                    retryCount = 2,
                    hintCount = 2,
                    answerWasRevealed = true,
                    independentlyAnswered = false,
                    assistance = TutorLearningEvidenceAssistance.ANSWER_REVEALED,
                ),
            ),
        )

    private fun cancelCommand() =
        finalizationCommand(
            TutorLearningEvidenceTerminal.Cancelled(
                TutorLearningEvidenceCancellationReason.USER_CANCELLED,
            ),
        )

    private fun finalizationCommand(terminal: TutorLearningEvidenceTerminal) =
        FinalizeTutorEvidenceCommand(
            learnerScopeId = LEARNER_ID,
            conversationId = CONVERSATION_ID,
            conversationGeneration = 1,
            conversationStateVersion = 2,
            turnReceiptId = TURN_RECEIPT_ID,
            turnOrdinal = 1,
            subject = SubjectKind.MATH,
            problemAnchorId = PROBLEM_ANCHOR_ID,
            evidenceRequestId = EVIDENCE_REQUEST_ID,
            kind = TutorEvidenceRequestKind.CHOICE,
            requestVersion = 1,
            modeVersion = 1,
            mode = TutorExplanationMode.GUIDED,
            directiveFingerprint = DIRECTIVE_FINGERPRINT,
            expectedEvidenceStateVersion = 0,
            terminal = terminal,
            clientIdempotencyKey = "finalize-key",
            payloadFingerprint = hash('5'),
            occurredAtEpochMillis = 110,
        )

    private companion object {
        const val LEARNER_ID = "learner-a"
        const val CONVERSATION_ID = "conversation-a"
        const val TURN_RECEIPT_ID = "turn-a"
        const val PROBLEM_ANCHOR_ID = "anchor-a"
        const val EVIDENCE_REQUEST_ID = "evidence-a"
        const val MASTERY_CANDIDATE_ID = "mastery-candidate-a"
        val DIRECTIVE_FINGERPRINT = hash('6')
        val CURRENT_SESSION_AUTHORIZATION_FINGERPRINT = hash('7')
        val MASTERY_RECEIPT_FINGERPRINT = hash('8')
        val PROOF_AUTHORITY: KnowledgeReferenceProofAuthority =
            KnowledgeReferenceProofAuthority.create()
        val KNOWLEDGE_NODE =
            KnowledgeNodeRef(
                subject = SubjectKind.MATH,
                knowledgeNodeId = "math.function.quadratic",
                taxonomyVersion = "taxonomy-v1",
                knowledgePackVersion = "pack-v1",
            )

        fun knowledgeProof(): VerifiedKnowledgeReferenceProof =
            PROOF_AUTHORITY.issuer.issue(
                KNOWLEDGE_NODE,
                hash('a'),
                3,
            )

        fun verifiedEvidence(
            proof: VerifiedKnowledgeReferenceProof,
        ): VerifiedEphemeralKnowledgeEvidence {
            val constructor =
                VerifiedEphemeralKnowledgeEvidence::class.java.declaredConstructors
                    .single { it.parameterCount == 4 }
            constructor.isAccessible = true
            return constructor.newInstance(
                proof.ref,
                proof.manifestFingerprint,
                proof.activationGeneration,
                "test-runtime-binding",
            ) as VerifiedEphemeralKnowledgeEvidence
        }

        fun trustedResult(
            observation: RecordTrustedLearningObservationCommand,
            returnedDisposition: TrustedLearningObservationDisposition,
            originalDisposition: TrustedLearningObservationDisposition,
            originalInertReason: LearningObservationInertReason?,
        ): TrustedLearningObservationResult =
            TrustedLearningObservationResult(
                observationId = observation.observationId,
                disposition = returnedDisposition,
                inertReason =
                    originalInertReason.takeIf {
                        returnedDisposition == TrustedLearningObservationDisposition.INERT
                    },
                terminalReceipt =
                    terminalReceipt(
                        disposition = originalDisposition,
                        inertReason = originalInertReason,
                    ),
            )

        fun terminalReceipt(
            disposition: TrustedLearningObservationDisposition,
            inertReason: LearningObservationInertReason?,
        ): TrustedLearningObservationTerminalReceipt {
            val constructor =
                TrustedLearningObservationTerminalReceipt::class.java.declaredConstructors
                    .single { it.parameterCount == 5 }
            constructor.isAccessible = true
            return constructor.newInstance(
                MASTERY_CANDIDATE_ID,
                hash('b'),
                disposition,
                inertReason,
                MASTERY_RECEIPT_FINGERPRINT,
            ) as TrustedLearningObservationTerminalReceipt
        }

        fun pendingRequest() =
            TutorEvidenceRequest(
                evidenceRequestId = EVIDENCE_REQUEST_ID,
                conversationId = CONVERSATION_ID,
                conversationGeneration = 1,
                conversationStateVersion = 2,
                turnReceiptId = TURN_RECEIPT_ID,
                turnOrdinal = 1,
                subject = SubjectKind.MATH,
                problemAnchorId = PROBLEM_ANCHOR_ID,
                kind = TutorEvidenceRequestKind.CHOICE,
                requestVersion = 1,
                modeVersion = 1,
                explanationMode = TutorExplanationMode.GUIDED,
                directiveFingerprint = DIRECTIVE_FINGERPRINT,
                status = TutorEvidenceRequestStatus.PENDING,
                stateVersion = 0,
                createdAtEpochMillis = 90,
                resolvedAtEpochMillis = null,
                terminalReceiptId = null,
            )

        fun hash(character: Char): String = character.toString().repeat(64)
    }
}
