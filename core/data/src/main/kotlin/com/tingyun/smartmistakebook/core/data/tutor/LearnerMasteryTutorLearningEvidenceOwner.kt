package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceOutcome
import com.tingyun.smartmistakebook.core.mastery.database.EphemeralTutorProblemLearningContext
import com.tingyun.smartmistakebook.core.mastery.database.LearningObservationInertReason
import com.tingyun.smartmistakebook.core.mastery.database.RecordTrustedLearningObservationCommand
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningObservationDisposition
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningObservationSource
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningObservationTerminalReceipt
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningResponseForm
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningVerification
import com.tingyun.smartmistakebook.core.mastery.database.VerifiedEphemeralKnowledgeEvidence
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof

/** One-purpose adapter issued by the authority owner; the mastery capability itself never escapes. */
internal fun interface TutorKnowledgeEvidenceAuthorizer {
    fun authorize(
        proof: VerifiedKnowledgeReferenceProof,
    ): VerifiedEphemeralKnowledgeEvidence
}

/**
 * Learner-mastery-owned adapter for a qualified tutor candidate.
 *
 * The session store never receives the generated observation. It receives only the opaque receipt
 * returned after the owner sink has durably committed the source fact/event.
 */
internal class LearnerMasteryTutorLearningEvidenceOwner(
    private val sink: TutorMasteryObservationSink,
    private val knowledgeEvidenceAuthorizer: TutorKnowledgeEvidenceAuthorizer,
) : TutorLearningEvidenceMasteryOwner {
    override suspend fun commit(
        candidate: AuthorizedTutorLearningEvidenceCandidate,
    ): TutorLearningEvidenceMasteryReceipt {
        val observation = candidate.toMasteryObservation(knowledgeEvidenceAuthorizer)
        val result = sink.record(observation)
        check(result.observationId == observation.observationId) {
            "Learner-mastery returned a mismatched tutor observation identity"
        }
        val terminalReceipt =
            checkNotNull(result.terminalReceipt) {
                "Learner-mastery did not return an immutable terminal receipt"
            }
        when (result.disposition) {
            TrustedLearningObservationDisposition.ADMITTED ->
                check(
                    terminalReceipt.disposition ==
                        TrustedLearningObservationDisposition.ADMITTED,
                ) {
                    "Admitted tutor evidence has no admitted terminal receipt"
                }

            TrustedLearningObservationDisposition.DUPLICATE ->
                check(
                    terminalReceipt.disposition ==
                        TrustedLearningObservationDisposition.ADMITTED ||
                        terminalReceipt.isReviewPending(),
                ) {
                    "Duplicate tutor evidence was not originally admitted or review-pending"
                }

            TrustedLearningObservationDisposition.INERT ->
                check(terminalReceipt.isReviewPending()) {
                    "Inert tutor evidence is not eligible for session acknowledgement"
                }

            TrustedLearningObservationDisposition.FACT_STORED ->
                error("Tutor evidence did not reach a terminal mastery decision")
            TrustedLearningObservationDisposition.CONFLICT ->
                error("Tutor evidence conflicts with existing learner-mastery history")
        }
        val candidateFingerprint = candidate.candidate.canonicalFingerprint()
        return TutorLearningEvidenceMasteryReceipt(
            receiptId = terminalReceipt.candidateId,
            receiptFingerprint = terminalReceipt.receiptFingerprint,
            candidateFingerprint = candidateFingerprint,
        )
    }
}

internal fun AuthorizedTutorLearningEvidenceCandidate.toMasteryObservation(
    knowledgeEvidenceAuthorizer: TutorKnowledgeEvidenceAuthorizer,
): RecordTrustedLearningObservationCommand {
    val semantic = candidate
    val policy = semantic.fixedMasteryPolicy()
    val candidateFingerprint = authorizedFingerprint()
    val verifiedKnowledgeEvidence =
        knowledgeReferenceProofs.map(knowledgeEvidenceAuthorizer::authorize)
    val presentationFingerprint =
        CanonicalSha256(PRESENTATION_FINGERPRINT_DOMAIN)
            .field("subject", semantic.subject.name)
            .field("sessionAnchorId", semantic.sessionAnchorId)
            .field("questionFingerprint", semantic.questionFingerprint)
            .finish()
    val problemFingerprint =
        CanonicalSha256(UNSAVED_PROBLEM_FINGERPRINT_DOMAIN)
            .field("subject", semantic.subject.name)
            .field("sessionAnchorId", semantic.sessionAnchorId)
            .field("questionFingerprint", semantic.questionFingerprint)
            .field("problemRevisionFingerprint", semantic.problemRevisionFingerprint)
            .field("problemFingerprintVersion", semantic.problemFingerprintVersion)
            .finish()
    val problemFamilyFingerprint =
        CanonicalSha256(PROBLEM_FAMILY_FINGERPRINT_DOMAIN)
            .field("subject", semantic.subject.name)
            .field("sessionAnchorId", semantic.sessionAnchorId)
            .finish()
    val submissionEvidenceFingerprint =
        CanonicalSha256(SUBMISSION_EVIDENCE_FINGERPRINT_DOMAIN)
            .field("responseFingerprint", semantic.responseFingerprint)
            .finish()
    val observationId =
        "tutor-observation:${
            CanonicalSha256(OBSERVATION_ID_DOMAIN)
                .field("learnerId", semantic.learnerId)
                .field("evidenceRequestId", semantic.evidenceRequestId)
                .field("candidateFingerprint", candidateFingerprint)
                .finish()
        }"

    return RecordTrustedLearningObservationCommand(
        observationId = observationId,
        subject = semantic.subject,
        source = policy.source,
        sourceReferenceId =
            "tutor-evidence:${
                CanonicalSha256(EVIDENCE_REQUEST_REFERENCE_DOMAIN)
                    .field("learnerId", semantic.learnerId)
                    .field("evidenceRequestId", semantic.evidenceRequestId)
                    .finish()
            }",
        presentationFingerprint = presentationFingerprint,
        context =
            EphemeralTutorProblemLearningContext(
                problemFingerprint = problemFingerprint,
                problemFamilyFingerprint = problemFamilyFingerprint,
                tutorTurnReferenceId =
                    "tutor-turn:${
                        CanonicalSha256(TURN_REFERENCE_DOMAIN)
                            .field("learnerId", semantic.learnerId)
                            .field("turnReceiptId", semantic.turnReceiptId)
                            .finish()
                    }",
                submissionEvidenceFingerprint = submissionEvidenceFingerprint,
                attributionModelVersion = semantic.producerVersion,
                verifiedKnowledgeEvidence = verifiedKnowledgeEvidence,
            ),
        responseForm = policy.responseForm,
        answerWasCorrect = policy.answerWasCorrect,
        learnerReportedStuck = policy.learnerReportedStuck,
        answerWasViewed = false,
        independentlyAnswered = semantic.independentlyAnswered,
        hintCount = semantic.hintCount,
        answerRevealed = semantic.answerWasRevealed,
        retryCount = semantic.retryCount,
        elapsedDurationMillis = null,
        verification = policy.verification,
        evidenceCanonicalFingerprint = candidateFingerprint,
        occurredAtEpochMillis = semantic.occurredAtEpochMillis,
        attestedAtEpochMillis = semantic.attestedAtEpochMillis,
    )
}

private fun TrustedLearningObservationTerminalReceipt.isReviewPending(): Boolean =
    disposition == TrustedLearningObservationDisposition.INERT &&
        inertReason == LearningObservationInertReason.WEAK_CONFLICT_REQUIRES_REVIEW

private data class FixedMasteryPolicy(
    val source: TrustedLearningObservationSource,
    val responseForm: TrustedLearningResponseForm,
    val answerWasCorrect: Boolean?,
    val learnerReportedStuck: Boolean,
    val verification: TrustedLearningVerification,
)

private fun TutorLearningEvidenceCandidate.fixedMasteryPolicy(): FixedMasteryPolicy =
    when (kind) {
        TutorEvidenceRequestKind.CHOICE ->
            gradedPolicy(
                source = TrustedLearningObservationSource.TUTOR_CHOICE,
                responseForm = TrustedLearningResponseForm.MULTIPLE_CHOICE,
            )

        TutorEvidenceRequestKind.VISUAL_TARGET ->
            gradedPolicy(
                source = TrustedLearningObservationSource.TUTOR_VISUAL_TARGET,
                responseForm = TrustedLearningResponseForm.VISUAL_TARGET,
            )

        TutorEvidenceRequestKind.SPECIFIC_STUCK -> {
            require(outcome == TutorLearningEvidenceOutcome.SPECIFIC_STUCK) {
                "Specific-stuck evidence requires a specific-stuck outcome"
            }
            FixedMasteryPolicy(
                source = TrustedLearningObservationSource.TUTOR_SPECIFIC_STUCK,
                responseForm = TrustedLearningResponseForm.STUCK_REPORT,
                answerWasCorrect = null,
                learnerReportedStuck = true,
                verification = TrustedLearningVerification.SELF_REPORTED,
            )
        }

        TutorEvidenceRequestKind.FREE_RESPONSE ->
            error("Ungraded free responses cannot create learner-mastery evidence")
    }

private fun TutorLearningEvidenceCandidate.gradedPolicy(
    source: TrustedLearningObservationSource,
    responseForm: TrustedLearningResponseForm,
): FixedMasteryPolicy =
    when (outcome) {
        TutorLearningEvidenceOutcome.CORRECT ->
            FixedMasteryPolicy(
                source = source,
                responseForm = responseForm,
                answerWasCorrect = true,
                learnerReportedStuck = false,
                verification = TrustedLearningVerification.MODEL_REVIEWED,
            )

        TutorLearningEvidenceOutcome.INCORRECT ->
            FixedMasteryPolicy(
                source = source,
                responseForm = responseForm,
                answerWasCorrect = false,
                learnerReportedStuck = false,
                verification = TrustedLearningVerification.MODEL_REVIEWED,
            )

        TutorLearningEvidenceOutcome.ASSISTED_CORRECT ->
            FixedMasteryPolicy(
                source = source,
                responseForm = responseForm,
                answerWasCorrect = true,
                learnerReportedStuck = false,
                verification = TrustedLearningVerification.MODEL_REVIEWED,
            )

        TutorLearningEvidenceOutcome.SPECIFIC_STUCK ->
            error("A graded tutor request cannot use a specific-stuck outcome")
    }

private const val PRESENTATION_FINGERPRINT_DOMAIN =
    "core-data-tutor-presentation-fingerprint-v2"
private const val UNSAVED_PROBLEM_FINGERPRINT_DOMAIN =
    "core-data-tutor-unsaved-problem-fingerprint-v2"
private const val PROBLEM_FAMILY_FINGERPRINT_DOMAIN =
    "core-data-tutor-problem-family-fingerprint-v2"
private const val SUBMISSION_EVIDENCE_FINGERPRINT_DOMAIN =
    "core-data-tutor-submission-evidence-fingerprint-v2"
private const val OBSERVATION_ID_DOMAIN = "core-data-tutor-observation-id-v2"
private const val EVIDENCE_REQUEST_REFERENCE_DOMAIN =
    "core-data-tutor-evidence-request-reference-v2"
private const val TURN_REFERENCE_DOMAIN = "core-data-tutor-turn-reference-v2"
