package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorLearningMemoryRepositoryTest {
    @Test
    fun submittedResultCarriesOpaqueMasteryReceiptWithoutLearningFact() {
        val receipt = TutorLearningEvidenceReceipt("mastery-receipt-a", hash('9'))
        val request =
            pendingRequest().copy(
                status = TutorEvidenceRequestStatus.SUBMITTED,
                stateVersion = 1,
                resolvedAtEpochMillis = 110,
                terminalReceiptId = receipt.receiptId,
            )

        val result = FinalizeTutorEvidenceResult.Submitted(request, receipt)

        assertEquals(receipt, result.receipt)
        val signatures =
            FinalizeTutorEvidenceResult::class.java.declaredClasses
                .flatMap { type ->
                    type.declaredFields.map { it.toGenericString() } +
                        type.declaredMethods.map { it.toGenericString() }
                }.joinToString("\n")
        assertTrue("LearningObservationSourceFact" !in signatures)
    }

    @Test
    fun cancellationHasNoMasteryReceipt() {
        val request =
            pendingRequest().copy(
                status = TutorEvidenceRequestStatus.CANCELLED,
                stateVersion = 1,
                resolvedAtEpochMillis = 110,
            )

        assertNull(FinalizeTutorEvidenceResult.Cancelled(request).receipt)
        assertNull(FinalizeTutorEvidenceResult.Replayed(request, null).receipt)
    }

    @Test
    fun freeResponseAndMismatchedStuckOutcomeCannotCreateSemanticCandidate() {
        assertTrue(
            runCatching {
                command(
                    kind = TutorEvidenceRequestKind.FREE_RESPONSE,
                    outcome = TutorLearningEvidenceOutcome.CORRECT,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                command(
                    kind = TutorEvidenceRequestKind.SPECIFIC_STUCK,
                    outcome = TutorLearningEvidenceOutcome.INCORRECT,
                )
            }.isFailure,
        )
    }

    @Test
    fun semanticCandidateContainsOnlyFingerprintsOutcomeAndProducerMetadata() {
        val submitted =
            (command().terminal as TutorLearningEvidenceTerminal.Submitted).evidence

        assertEquals(TutorLearningEvidenceOutcome.INCORRECT, submitted.outcome)
        val fields = TutorLearningEvidenceSubmission::class.java.declaredFields
            .map { it.name }
        assertTrue("responseSummary" !in fields)
        assertTrue("answerMarkdown" !in fields)
        assertTrue("sourceFact" !in fields)
    }

    @Test
    fun behaviorFactsRejectRevealedIndependenceAndRetryMismatch() {
        assertTrue(
            runCatching {
                TutorLearningEvidenceCurrentSessionReference(
                    authorizationFingerprint = "",
                    learningWritePermissionVersion = 0,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                submission(
                    answerWasRevealed = true,
                    independentlyAnswered = true,
                    assistance = TutorLearningEvidenceAssistance.ANSWER_REVEALED,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                submission(
                    attemptOrdinal = 3,
                    retryCount = 1,
                    independentlyAnswered = false,
                    assistance = TutorLearningEvidenceAssistance.UNKNOWN,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                submission(
                    outcome = TutorLearningEvidenceOutcome.CORRECT,
                    answerWasRevealed = true,
                    independentlyAnswered = false,
                    assistance = TutorLearningEvidenceAssistance.ANSWER_REVEALED,
                )
            }.isFailure,
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacySubmissionWithoutCurrentPermissionFailsClosed() {
        assertTrue(
            runCatching {
                TutorLearningEvidenceSubmission(
                    anchors = anchors(),
                    responseFingerprint = hash('5'),
                    outcome = TutorLearningEvidenceOutcome.INCORRECT,
                    occurredAtEpochMillis = 100,
                    producerVersion = "tutor-evidence-v2",
                )
            }.isFailure,
        )
    }

    @Test
    fun domainSubmissionAbiContainsNoProofSourceMasteryOrDataTypes() {
        val signatures =
            TutorLearningEvidenceSubmission::class.java.declaredFields
                .map { it.toGenericString() }
                .plus(
                    TutorLearningEvidenceSubmission::class.java.declaredMethods
                        .map { it.toGenericString() },
                ).joinToString("\n")

        listOf(
            "VerifiedKnowledgeReferenceProof",
            "TrustedLearningObservationSource",
            "LearningObservationSource",
            "MasteryEvidence",
            "core.data",
        ).forEach { forbidden -> assertTrue(forbidden !in signatures) }
    }

    private fun command(
        kind: TutorEvidenceRequestKind = TutorEvidenceRequestKind.CHOICE,
        outcome: TutorLearningEvidenceOutcome = TutorLearningEvidenceOutcome.INCORRECT,
    ) = FinalizeTutorEvidenceCommand(
        learnerScopeId = "learner-a",
        conversationId = "conversation-a",
        conversationGeneration = 1,
        conversationStateVersion = 2,
        turnReceiptId = "turn-a",
        turnOrdinal = 1,
        subject = SubjectKind.MATH,
        problemAnchorId = "anchor-a",
        evidenceRequestId = "evidence-a",
        kind = kind,
        requestVersion = 1,
        modeVersion = 1,
        mode = TutorExplanationMode.GUIDED,
        directiveFingerprint = hash('1'),
        expectedEvidenceStateVersion = 0,
        terminal =
            TutorLearningEvidenceTerminal.Submitted(
                submission(outcome = outcome),
            ),
        clientIdempotencyKey = "finalize-a",
        payloadFingerprint = hash('6'),
        occurredAtEpochMillis = 110,
    )

    private fun submission(
        outcome: TutorLearningEvidenceOutcome = TutorLearningEvidenceOutcome.INCORRECT,
        attemptOrdinal: Int = 1,
        retryCount: Int = 0,
        hintCount: Int = 0,
        answerWasRevealed: Boolean = false,
        independentlyAnswered: Boolean = true,
        assistance: TutorLearningEvidenceAssistance =
            TutorLearningEvidenceAssistance.INDEPENDENT,
    ) = TutorLearningEvidenceSubmission(
        anchors = anchors(),
        responseFingerprint = hash('5'),
        outcome = outcome,
        occurredAtEpochMillis = 100,
        producerVersion = "tutor-evidence-v2",
        currentSessionReference =
            TutorLearningEvidenceCurrentSessionReference(
                authorizationFingerprint = hash('7'),
                learningWritePermissionVersion = 3,
            ),
        attemptOrdinal = attemptOrdinal,
        retryCount = retryCount,
        hintCount = hintCount,
        answerWasRevealed = answerWasRevealed,
        independentlyAnswered = independentlyAnswered,
        assistance = assistance,
    )

    private fun anchors() =
        TutorLearningEvidenceAnchorFingerprints(
            questionFingerprint = hash('2'),
            problemRevisionFingerprint = hash('3'),
            fingerprintVersion = "problem-v1",
            turnFingerprint = hash('4'),
            directiveFingerprint = hash('1'),
        )

    private companion object {
        fun pendingRequest() =
            TutorEvidenceRequest(
                evidenceRequestId = "evidence-a",
                conversationId = "conversation-a",
                conversationGeneration = 1,
                conversationStateVersion = 2,
                turnReceiptId = "turn-a",
                turnOrdinal = 1,
                subject = SubjectKind.MATH,
                problemAnchorId = "anchor-a",
                kind = TutorEvidenceRequestKind.CHOICE,
                requestVersion = 1,
                modeVersion = 1,
                explanationMode = TutorExplanationMode.GUIDED,
                directiveFingerprint = hash('1'),
                status = TutorEvidenceRequestStatus.PENDING,
                stateVersion = 0,
                createdAtEpochMillis = 90,
                resolvedAtEpochMillis = null,
                terminalReceiptId = null,
            )

        fun hash(character: Char): String = character.toString().repeat(64)
    }
}
