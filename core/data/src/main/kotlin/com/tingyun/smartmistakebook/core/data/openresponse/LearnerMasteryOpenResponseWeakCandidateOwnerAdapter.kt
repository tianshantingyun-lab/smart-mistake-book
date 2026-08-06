package com.tingyun.smartmistakebook.core.data.openresponse

import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOpenResponseWeakCandidateCommand
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOpenResponseWeakCandidateReceiptQuery
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOpenResponseWeakCandidateDisposition
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOpenResponseKnowledgeScopeEntry
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOpenResponseAdmissionMode
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOpenResponseWeakCandidateOwner
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOpenResponseScopeLease
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess.bindLearnerMasteryOpenResponseWeakCandidateAuthorization
import com.tingyun.smartmistakebook.core.model.TutorOpenResponseEvaluationCandidateOutput
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Maps the host-validated proposal into learner-mastery's dedicated open-response proof chain.
 *
 * The full verified catalog scope is persisted without display text or numeric influence. Model
 * roles remain qualitative input; the learner owner alone decides direction, mass and admission.
 */
internal class LearnerMasteryOpenResponseWeakCandidateOwnerAdapter(
    private val owner: LearnerMasteryOpenResponseWeakCandidateOwner,
) : LearnerBoundOpenResponseWeakCandidateOwner,
    AutoCloseable {
    override val learnerId: String = owner.learnerId
    private val closed = AtomicBoolean(false)

    override suspend fun findCommitted(
        query: OpenResponseWeakCandidateReceiptQuery,
    ): OpenResponseWeakCandidateCommitReceipt? {
        if (closed.get() || query.learnerId != learnerId) return null
        val ownerReceipt =
            try {
                owner.findCommitted(
                    LearnerMasteryOpenResponseWeakCandidateReceiptQuery(
                        learnerId = query.learnerId,
                        sourceFactId = query.sourceFactId,
                        reviewCaseId = query.reviewCaseId,
                        scopeFingerprint = query.scopeFingerprint,
                        candidateIdempotencyKey = query.candidateIdempotencyKey,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return null
        return OpenResponseWeakCandidateCommitReceipt(
            query = query,
            receiptFingerprint = ownerReceipt.receiptFingerprint,
        )
    }

    override suspend fun submit(
        proposal: OpenResponseWeakCandidateProposal,
        authorization: OpenResponseWeakCandidateSubmissionAuthorization,
    ): OpenResponseWeakCandidateCommitResult {
        if (closed.get()) {
            return failed(OpenResponseWeakCandidateDisposition.STORAGE_UNAVAILABLE)
        }
        if (
            proposal.learnerId != learnerId ||
            proposal.scope.learnerId != learnerId ||
            authorization.scopeFingerprint != proposal.scope.canonicalFingerprint
        ) {
            return failed(OpenResponseWeakCandidateDisposition.REJECTED)
        }
        val command =
            try {
                proposal.toLearnerMasteryCommand()
            } catch (_: IllegalArgumentException) {
                return failed(OpenResponseWeakCandidateDisposition.REJECTED)
            }
        val learnerAuthorization =
            try {
                bindLearnerMasteryOpenResponseWeakCandidateAuthorization(
                    owner,
                    learnerId,
                    proposal.scope.canonicalFingerprint,
                    LearnerMasteryOpenResponseScopeLease {
                        authorization.requireCurrentEpoch()
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return failed(OpenResponseWeakCandidateDisposition.STORAGE_UNAVAILABLE)
            }
        val result =
            try {
                owner.submit(command, learnerAuthorization)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return failed(OpenResponseWeakCandidateDisposition.STORAGE_UNAVAILABLE)
            }
        val disposition = when (result.disposition) {
            LearnerMasteryOpenResponseWeakCandidateDisposition.PENDING_CONFIRMATION ->
                OpenResponseWeakCandidateDisposition.PENDING_CONFIRMATION
            LearnerMasteryOpenResponseWeakCandidateDisposition.DUPLICATE ->
                OpenResponseWeakCandidateDisposition.DUPLICATE
            LearnerMasteryOpenResponseWeakCandidateDisposition.CONFLICT ->
                OpenResponseWeakCandidateDisposition.CONFLICT
            LearnerMasteryOpenResponseWeakCandidateDisposition.REJECTED ->
                OpenResponseWeakCandidateDisposition.REJECTED
        }
        val receipt = result.receiptFingerprint?.let { fingerprint ->
            OpenResponseWeakCandidateCommitReceipt(
                query = proposal.receiptQuery(),
                receiptFingerprint = fingerprint,
            )
        }
        return OpenResponseWeakCandidateCommitResult(disposition, receipt)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            owner.close()
        }
    }
}

internal fun OpenResponseWeakCandidateProposal.toLearnerMasteryCommand():
    LearnerMasteryOpenResponseWeakCandidateCommand =
    LearnerMasteryOpenResponseWeakCandidateCommand(
        learnerId = learnerId,
        subject = scope.subject,
        sourceFactId = sourceFactId,
        reviewCaseId = reviewCaseId,
        scopeFingerprint = scope.canonicalFingerprint,
        conversationId = scope.conversationId,
        conversationGeneration = scope.conversationGeneration,
        conversationStateVersion = scope.conversationStateVersion,
        questionDocumentId = scope.questionDocumentId,
        questionRevisionNumber = scope.questionRevisionNumber,
        questionFingerprint = scope.questionFingerprint,
        responseBinding = scope.responseBinding,
        evidenceRequestId = scope.evidenceRequestId,
        turnReferenceId = scope.turnReferenceId,
        turnOrdinal = scope.turnOrdinal,
        turnGeneration = scope.turnGeneration,
        modeVersion = scope.modeVersion,
        requestVersion = scope.requestVersion,
        attemptOrdinal = attemptOrdinal,
        hintCount = hintCount,
        answerWasRevealed = answerWasRevealed,
        modelTaskRequestId = modelTaskRequestId,
        modelResponseSchemaVersion =
            TutorOpenResponseEvaluationCandidateOutput.CURRENT_EVALUATION_SCHEMA_VERSION,
        evaluatorRequestVersion = evaluatorRequestVersion,
        candidateIdempotencyKey = candidateIdempotencyKey,
        revisionOfCandidateIdempotencyKey = revisionOfCandidateIdempotencyKey,
        evidenceFingerprint = evidenceFingerprint,
        modelOutputFingerprint = modelOutputFingerprint,
        modelVersion = modelVersion,
        outcome = outcome,
        admissionMode = LearnerMasteryOpenResponseAdmissionMode.REVIEW_ONLY,
        authorizedKnowledgeScope =
            authorizedKnowledgeScope.map { authorized ->
                LearnerMasteryOpenResponseKnowledgeScopeEntry(
                    refFingerprint = authorized.refFingerprint,
                    knowledgeNode = authorized.knowledgeNode,
                    manifestFingerprint = authorized.manifestFingerprint,
                    activationGeneration = authorized.activationGeneration,
                    evaluationRole =
                        knowledgeEffects
                            .firstOrNull { effect ->
                                effect.refFingerprint == authorized.refFingerprint
                            }?.role,
                )
            },
        occurredAtEpochMillis = occurredAtEpochMillis,
    )

private fun OpenResponseWeakCandidateProposal.receiptQuery() =
    OpenResponseWeakCandidateReceiptQuery(
        learnerId = learnerId,
        sourceFactId = sourceFactId,
        reviewCaseId = reviewCaseId,
        scopeFingerprint = scope.canonicalFingerprint,
        candidateIdempotencyKey = candidateIdempotencyKey,
    )

private fun failed(
    disposition: OpenResponseWeakCandidateDisposition,
) = OpenResponseWeakCandidateCommitResult(disposition = disposition, receipt = null)
