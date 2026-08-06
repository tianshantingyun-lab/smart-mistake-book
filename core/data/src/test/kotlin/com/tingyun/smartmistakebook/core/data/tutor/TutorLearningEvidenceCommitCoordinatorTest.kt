package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceAssistance
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceCurrentSessionReference
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceOutcome
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorLearningEvidenceCommitCoordinatorTest {
    @Test
    fun crashAfterSessionIntentReplaysToOneMasteryFactAndOneSessionAcknowledgement() =
        runBlocking {
            val session = RecordingSessionPort(failAfterIntentOnce = true)
            val mastery = RecordingMasteryOwner()
            val authorizer = RecordingCandidateAuthorizer()
            val coordinator = coordinator(session, mastery, authorizer)

            assertTrue(runCatching { coordinator.commit(candidate()) }.isFailure)
            val replay = coordinator.commit(candidate())

            assertTrue(replay is TutorLearningEvidenceCommitResult.Committed)
            assertEquals(1, mastery.durableWrites)
            assertEquals(1, session.acknowledgements)
            assertEquals(1, authorizer.calls)
        }

    @Test
    fun crashAfterMasteryCommitReplaysIdempotentlyBeforeSessionAcknowledgement() =
        runBlocking {
            val session = RecordingSessionPort()
            val mastery = RecordingMasteryOwner(failAfterCommitOnce = true)
            val coordinator = coordinator(session, mastery)

            assertTrue(runCatching { coordinator.commit(candidate()) }.isFailure)
            val replay = coordinator.commit(candidate())

            assertTrue(replay is TutorLearningEvidenceCommitResult.Committed)
            assertEquals(1, mastery.durableWrites)
            assertEquals(2, mastery.commitCalls)
            assertEquals(1, session.acknowledgements)
        }

    @Test
    fun crashAfterSessionAcknowledgementReturnsFinalReceiptWithoutAnotherMasteryWrite() =
        runBlocking {
            val session = RecordingSessionPort(failAfterAcknowledgementOnce = true)
            val mastery = RecordingMasteryOwner()
            val authorizer = RecordingCandidateAuthorizer()
            val coordinator = coordinator(session, mastery, authorizer)

            assertTrue(runCatching { coordinator.commit(candidate()) }.isFailure)
            val replay = coordinator.commit(candidate())

            assertTrue(replay is TutorLearningEvidenceCommitResult.Replayed)
            assertEquals(1, mastery.commitCalls)
            assertEquals(1, mastery.durableWrites)
            assertEquals(1, session.acknowledgements)
            assertEquals(1, authorizer.calls)
        }

    @Test
    fun exactReplayIsIdempotentButLateChangedPayloadConflicts() = runBlocking {
        val session = RecordingSessionPort()
        val mastery = RecordingMasteryOwner()
        val coordinator = coordinator(session, mastery)

        assertTrue(coordinator.commit(candidate()) is TutorLearningEvidenceCommitResult.Committed)
        assertTrue(coordinator.commit(candidate()) is TutorLearningEvidenceCommitResult.Replayed)
        val changed = candidate(responseFingerprint = hash('9'))

        assertTrue(runCatching { coordinator.commit(changed) }.isFailure)
        assertEquals(1, mastery.durableWrites)
        assertEquals(1, session.acknowledgements)
    }

    @Test
    fun sameEvidenceRequestInAnotherConversationCannotObserveOrAcknowledgeFirstReceipt() =
        runBlocking {
            val session = RecordingSessionPort()
            val mastery = RecordingMasteryOwner()
            val coordinator = coordinator(session, mastery)

            coordinator.commit(candidate(conversationId = "conversation-a"))
            val crossConversation =
                runCatching {
                    coordinator.commit(candidate(conversationId = "conversation-b"))
                }

            assertTrue(crossConversation.isFailure)
            assertEquals(1, mastery.durableWrites)
            assertEquals(1, session.acknowledgements)
        }

    @Test
    fun sessionProtocolContainsNoLegacyLearningFactOrRawAnswerField() {
        val protocolTypes =
            listOf(
                TutorLearningEvidenceSessionPort::class.java,
                TutorLearningEvidenceSessionIntent::class.java,
                TutorLearningEvidenceSessionAcknowledgement::class.java,
                TutorLearningEvidenceSessionReceipt::class.java,
            )
        val signatureText =
            protocolTypes.flatMap { type ->
                type.declaredMethods.map { it.toGenericString() } +
                    type.declaredFields.map { it.toGenericString() }
            }.joinToString("\n")

        assertFalse(signatureText.contains("LearningObservationSourceFact"))
        assertFalse(signatureText.contains("LearningProblemAnchor"))
        assertFalse(signatureText.contains("responseSummary"))
        assertFalse(signatureText.contains("answerMarkdown"))
    }

    private class RecordingSessionPort(
        private var failAfterIntentOnce: Boolean = false,
        private var failAfterAcknowledgementOnce: Boolean = false,
    ) : TutorLearningEvidenceSessionPort {
        private var intent: TutorLearningEvidenceSessionIntent? = null
        private var receipt: TutorLearningEvidenceSessionReceipt? = null
        var acknowledgements: Int = 0
            private set

        override suspend fun begin(
            requested: TutorLearningEvidenceSessionIntent,
        ): TutorLearningEvidenceSessionBeginResult {
            val existing = intent
            if (existing == null) {
                intent = requested
            } else {
                check(existing == requested) {
                    "Late tutor evidence belongs to another scoped payload"
                }
            }
            receipt?.let { return TutorLearningEvidenceSessionBeginResult.Finalized(it) }
            if (failAfterIntentOnce) {
                failAfterIntentOnce = false
                error("simulated process death after durable session intent")
            }
            return TutorLearningEvidenceSessionBeginResult.Pending(checkNotNull(intent))
        }

        override suspend fun acknowledge(
            acknowledgement: TutorLearningEvidenceSessionAcknowledgement,
        ): TutorLearningEvidenceSessionAcknowledgeResult {
            val expected = checkNotNull(intent)
            check(expected.scope == acknowledgement.scope)
            check(expected.evidenceRequestId == acknowledgement.evidenceRequestId)
            check(expected.candidateFingerprint == acknowledgement.candidateFingerprint)
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
            val existing = receipt
            if (existing == null) {
                receipt = next
                acknowledgements += 1
            } else {
                check(existing == next)
            }
            if (failAfterAcknowledgementOnce) {
                failAfterAcknowledgementOnce = false
                error("simulated process death after durable session acknowledgement")
            }
            return if (existing == null) {
                TutorLearningEvidenceSessionAcknowledgeResult.Acknowledged(next)
            } else {
                TutorLearningEvidenceSessionAcknowledgeResult.Replayed(existing)
            }
        }
    }

    private class RecordingMasteryOwner(
        private var failAfterCommitOnce: Boolean = false,
    ) : TutorLearningEvidenceMasteryOwner {
        private val receipts = mutableMapOf<String, TutorLearningEvidenceMasteryReceipt>()
        var commitCalls: Int = 0
            private set
        var durableWrites: Int = 0
            private set

        override suspend fun commit(
            candidate: AuthorizedTutorLearningEvidenceCandidate,
        ): TutorLearningEvidenceMasteryReceipt {
            commitCalls += 1
            val fingerprint = candidate.candidate.canonicalFingerprint()
            val receipt =
                receipts.getOrPut(fingerprint) {
                    durableWrites += 1
                    TutorLearningEvidenceMasteryReceipt(
                        receiptId = "mastery-receipt:$fingerprint",
                        receiptFingerprint = hash('8'),
                        candidateFingerprint = fingerprint,
                    )
                }
            if (failAfterCommitOnce) {
                failAfterCommitOnce = false
                error("simulated process death after durable mastery commit")
            }
            return receipt
        }
    }

    private class RecordingCandidateAuthorizer : TutorLearningEvidenceCandidateAuthorizer {
        var calls: Int = 0
            private set

        override suspend fun authorize(
            candidate: TutorLearningEvidenceCandidate,
        ): AuthorizedTutorLearningEvidenceCandidate {
            calls += 1
            return AuthorizedTutorLearningEvidenceCandidate(candidate, listOf(knowledgeProof()))
        }
    }

    private fun coordinator(
        session: TutorLearningEvidenceSessionPort,
        mastery: TutorLearningEvidenceMasteryOwner,
        authorizer: TutorLearningEvidenceCandidateAuthorizer = RecordingCandidateAuthorizer(),
    ) = TutorLearningEvidenceCommitCoordinator(
        session = session,
        candidateAuthorizer = authorizer,
        mastery = mastery,
    )

    private fun candidate(
        conversationId: String = "conversation-a",
        responseFingerprint: String = hash('4'),
    ) = TutorLearningEvidenceCandidate(
        learnerId = "learner-a",
        evidenceRequestId = "evidence-a",
        conversationId = conversationId,
        conversationGeneration = 1,
        conversationStateVersion = 2,
        turnReceiptId = "turn-a",
        turnOrdinal = 1,
        subject = SubjectKind.MATH,
        sessionAnchorId = "anchor-a",
        kind = TutorEvidenceRequestKind.CHOICE,
        requestVersion = 1,
        modeVersion = 1,
        directiveFingerprint = hash('1'),
        idempotencyKey = "finalize-a",
        payloadFingerprint = hash('2'),
        questionFingerprint = hash('3'),
        problemRevisionFingerprint = hash('5'),
        problemFingerprintVersion = "problem-v1",
        turnFingerprint = hash('6'),
        responseFingerprint = responseFingerprint,
        outcome = TutorLearningEvidenceOutcome.INCORRECT,
        occurredAtEpochMillis = 100,
        attestedAtEpochMillis = 110,
        producerVersion = "tutor-candidate-v1",
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
    )

    private companion object {
        val PROOF_AUTHORITY: KnowledgeReferenceProofAuthority =
            KnowledgeReferenceProofAuthority.create()

        fun knowledgeProof() =
            PROOF_AUTHORITY.issuer.issue(
                KnowledgeNodeRef(
                    subject = SubjectKind.MATH,
                    knowledgeNodeId = "math.function.quadratic",
                    taxonomyVersion = "taxonomy-v1",
                    knowledgePackVersion = "pack-v1",
                ),
                hash('a'),
                3,
            )

        fun hash(character: Char): String = character.toString().repeat(64)
    }
}
