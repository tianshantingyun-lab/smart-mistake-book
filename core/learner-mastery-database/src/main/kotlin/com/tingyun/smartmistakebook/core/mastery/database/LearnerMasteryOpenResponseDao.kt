package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Transaction

/**
 * Source-fact, candidate admission, open-response and evidence-review DAO surface.
 *
 * These primitives feed the projection pipeline without making the write-heavy
 * [LearnerMasteryDao] responsible for their schema details.
 */
@Dao
internal abstract class LearnerMasteryOpenResponseDao : LearnerMasteryCalibrationDao() {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSourceFact(entity: MasterySourceFactEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSourceProof(entity: MasterySourceProofEntity)

    @Query("SELECT * FROM mastery_source_fact WHERE source_fact_id = :sourceFactId LIMIT 1")
    protected abstract suspend fun findSourceFact(sourceFactId: String): MasterySourceFactEntity?

    @Query(
        "SELECT * FROM mastery_source_fact " +
            "WHERE canonical_fingerprint = :canonicalFingerprint LIMIT 1",
    )
    protected abstract suspend fun findSourceFactByCanonicalFingerprint(
        canonicalFingerprint: String,
    ): MasterySourceFactEntity?

    @Query(
        "SELECT * FROM mastery_source_fact " +
            "WHERE problem_revision_ref_fingerprint = :problemRevisionRefFingerprint",
    )
    protected abstract suspend fun findSourceFactsByProblemRevision(
        problemRevisionRefFingerprint: String,
    ): List<MasterySourceFactEntity>

    @Query("SELECT * FROM mastery_source_fact WHERE idempotency_key = :idempotencyKey LIMIT 1")
    protected abstract suspend fun findSourceFactByIdempotency(
        idempotencyKey: String,
    ): MasterySourceFactEntity?

    @Query(
        """
        SELECT * FROM mastery_source_fact
        WHERE learner_id = :learnerId
          AND source_kind = :sourceKind
          AND source_reference_id = :sourceReferenceId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findSourceFactBySourceReference(
        learnerId: String,
        sourceKind: String,
        sourceReferenceId: String,
    ): MasterySourceFactEntity?

    @Query(
        """
        SELECT * FROM mastery_source_fact
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND authority_attempt_fingerprint = :authorityAttemptFingerprint
        LIMIT 1
        """,
    )
    protected abstract suspend fun findSourceFactByAuthorityAttempt(
        learnerId: String,
        subject: String,
        authorityAttemptFingerprint: String,
    ): MasterySourceFactEntity?

    @Query(
        """
        SELECT * FROM mastery_source_fact
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND authority_submission_fingerprint = :authoritySubmissionFingerprint
        LIMIT 1
        """,
    )
    protected abstract suspend fun findSourceFactByAuthoritySubmission(
        learnerId: String,
        subject: String,
        authoritySubmissionFingerprint: String,
    ): MasterySourceFactEntity?

    @Query("SELECT * FROM mastery_source_proof WHERE source_fact_id = :sourceFactId LIMIT 1")
    protected abstract suspend fun findSourceProof(
        sourceFactId: String,
    ): MasterySourceProofEntity?

    @Query(
        """
        SELECT * FROM mastery_problem_binding_authority
        WHERE problem_revision_ref_fingerprint = :problemRevisionRefFingerprint
        ORDER BY binding_ref_fingerprint ASC
        """,
    )
    protected abstract suspend fun findProblemBindingAuthorities(
        problemRevisionRefFingerprint: String,
    ): List<MasteryProblemBindingAuthorityEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM mastery_problem_binding_authority_state
            WHERE problem_revision_ref_fingerprint = :problemRevisionRefFingerprint
        )
        """,
    )
    internal abstract suspend fun hasProblemBindingAuthorityState(
        problemRevisionRefFingerprint: String,
    ): Boolean

    @Query(
        """
        SELECT * FROM mastery_cross_store_inbox
        WHERE payload_type = :payloadType
          AND payload_version = :payloadVersion
          AND aggregate_id = :revisionId
        ORDER BY received_at_epoch_millis DESC, event_id DESC
        LIMIT :limit
        """,
    )
    internal abstract suspend fun readProblemReferenceReceipts(
        payloadType: String,
        payloadVersion: Int,
        revisionId: String,
        limit: Int,
    ): List<MasteryCrossStoreInboxEntity>

    @Transaction
    internal open suspend fun areProblemKnowledgeBindingsAuthorized(
        problemRevisionRefFingerprint: String,
        bindingRefFingerprints: Set<String>,
    ): Boolean {
        requireMasteryFingerprint(
            problemRevisionRefFingerprint,
            "Problem revision fingerprint",
        )
        bindingRefFingerprints.forEach { fingerprint ->
            requireMasteryFingerprint(fingerprint, "Problem binding fingerprint")
        }
        if (!hasProblemBindingAuthorityState(problemRevisionRefFingerprint)) {
            return false
        }
        if (bindingRefFingerprints.isEmpty()) {
            return true
        }
        val authorized =
            findProblemBindingAuthorities(problemRevisionRefFingerprint)
                .mapTo(mutableSetOf(), MasteryProblemBindingAuthorityEntity::bindingRefFingerprint)
        return authorized.containsAll(bindingRefFingerprints)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertCandidate(
        entity: MasteryObservationCandidateEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCandidateAttributions(
        entities: List<MasteryCandidateAttributionEntity>,
    )

    @Query(
        "SELECT * FROM mastery_observation_candidate WHERE candidate_id = :candidateId LIMIT 1",
    )
    protected abstract suspend fun findCandidate(
        candidateId: String,
    ): MasteryObservationCandidateEntity?

    @Query(
        """
        SELECT * FROM mastery_observation_candidate
        WHERE idempotency_key = :idempotencyKey
        LIMIT 1
        """,
    )
    protected abstract suspend fun findCandidateByIdempotency(
        idempotencyKey: String,
    ): MasteryObservationCandidateEntity?

    @Query(
        """
        SELECT * FROM mastery_observation_candidate
        WHERE canonical_fingerprint = :canonicalFingerprint
        LIMIT 1
        """,
    )
    protected abstract suspend fun findCandidateByCanonicalFingerprint(
        canonicalFingerprint: String,
    ): MasteryObservationCandidateEntity?

    @Query(
        """
        SELECT * FROM mastery_observation_candidate
        WHERE source_fact_id = :sourceFactId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findCandidateBySourceFact(
        sourceFactId: String,
    ): MasteryObservationCandidateEntity?

    @Query(
        """
        SELECT * FROM mastery_candidate_attribution
        WHERE candidate_id = :candidateId
        ORDER BY ordinal ASC
        """,
    )
    protected abstract suspend fun findCandidateAttributions(
        candidateId: String,
    ): List<MasteryCandidateAttributionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAdmissionReceipt(
        entity: MasteryAdmissionReceiptEntity,
    )

    @Query(
        "SELECT * FROM mastery_admission_receipt WHERE candidate_id = :candidateId LIMIT 1",
    )
    protected abstract suspend fun findAdmissionReceipt(
        candidateId: String,
    ): MasteryAdmissionReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertModelSubmissionAttemptReceipt(
        entity: MasteryModelSubmissionAttemptReceiptEntity,
    )

    @Query(
        """
        SELECT * FROM mastery_model_submission_attempt_receipt
        WHERE request_generation_fingerprint = :requestGenerationFingerprint
          AND proposal_fingerprint = :proposalFingerprint
        LIMIT 1
        """,
    )
    protected abstract suspend fun findModelSubmissionAttemptReceipt(
        requestGenerationFingerprint: String,
        proposalFingerprint: String,
    ): MasteryModelSubmissionAttemptReceiptEntity?

    @Query(
        """
        SELECT * FROM mastery_model_submission_attempt_receipt
        WHERE primary_attempt_key = :logicalRequestFingerprint
        LIMIT 1
        """,
    )
    protected abstract suspend fun findPrimaryModelSubmissionAttemptReceipt(
        logicalRequestFingerprint: String,
    ): MasteryModelSubmissionAttemptReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertOpenResponseWeakCandidateReceipt(
        entity: MasteryOpenResponseWeakCandidateReceiptEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOpenResponseModelEvaluationAttestation(
        entity: MasteryOpenResponseModelEvaluationAttestationEntity,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOpenResponseEvaluationKnowledgeScope(
        entities: List<MasteryOpenResponseEvaluationKnowledgeScopeEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOpenResponseDedicatedDecision(
        entity: MasteryOpenResponseDedicatedDecisionEntity,
    )

    @Query(
        "SELECT * FROM mastery_open_response_model_evaluation_attestation " +
            "WHERE receipt_fingerprint = :receiptFingerprint LIMIT 1",
    )
    protected abstract suspend fun findOpenResponseModelEvaluationAttestation(
        receiptFingerprint: String,
    ): MasteryOpenResponseModelEvaluationAttestationEntity?

    @Query(
        "SELECT * FROM mastery_open_response_evaluation_knowledge_scope " +
            "WHERE attestation_fingerprint = :attestationFingerprint ORDER BY ordinal ASC",
    )
    protected abstract suspend fun findOpenResponseEvaluationKnowledgeScope(
        attestationFingerprint: String,
    ): List<MasteryOpenResponseEvaluationKnowledgeScopeEntity>

    @Query(
        "SELECT * FROM mastery_open_response_dedicated_decision " +
            "WHERE receipt_fingerprint = :receiptFingerprint LIMIT 1",
    )
    protected abstract suspend fun findOpenResponseDedicatedDecision(
        receiptFingerprint: String,
    ): MasteryOpenResponseDedicatedDecisionEntity?

    @Query(
        """
        SELECT * FROM mastery_open_response_weak_candidate_receipt
        WHERE candidate_idempotency_key = :candidateIdempotencyKey
        LIMIT 1
        """,
    )
    internal abstract suspend fun findOpenResponseWeakCandidateReceiptByIdempotency(
        candidateIdempotencyKey: String,
    ): MasteryOpenResponseWeakCandidateReceiptEntity?

    @Query(
        """
        SELECT * FROM mastery_open_response_weak_candidate_receipt
        WHERE logical_attempt_fingerprint = :logicalAttemptFingerprint
          AND revision_ordinal IS NOT NULL
        ORDER BY revision_ordinal DESC
        LIMIT 1
        """,
    )
    protected abstract suspend fun findLatestOpenResponseWeakCandidateReceipt(
        logicalAttemptFingerprint: String,
    ): MasteryOpenResponseWeakCandidateReceiptEntity?

    @Query(
        """
        SELECT COUNT(*) FROM mastery_open_response_weak_candidate_receipt
        WHERE logical_attempt_fingerprint = :logicalAttemptFingerprint
          AND revision_ordinal IS NULL
        """,
    )
    protected abstract suspend fun countUnresolvedOpenResponseWeakCandidateRevisions(
        logicalAttemptFingerprint: String,
    ): Long

    @Query(
        """
        SELECT COUNT(*) FROM mastery_open_response_weak_candidate_receipt
        WHERE source_fact_id = :sourceFactId
        """,
    )
    internal abstract suspend fun countOpenResponseWeakCandidateReceipts(
        sourceFactId: String,
    ): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEvidenceReviewCase(
        entity: MasteryEvidenceReviewCaseEntity,
    )

    @Query(
        """
        SELECT c.* FROM mastery_evidence_review_case c
        LEFT JOIN mastery_evidence_review_resolution r
          ON r.review_case_id = c.review_case_id
        LEFT JOIN mastery_open_response_dedicated_decision open_response_decision
          ON open_response_decision.review_case_id = c.review_case_id
         AND open_response_decision.disposition = 'ACCEPTED'
        WHERE c.learner_id = :learnerId
          AND c.subject = :subject
          AND c.calibration_binding_status = 'BOUND'
          AND r.review_case_id IS NULL
          AND open_response_decision.review_case_id IS NULL
        ORDER BY c.created_at_epoch_millis ASC, c.review_case_id ASC
        LIMIT :limit
        """,
    )
    internal abstract suspend fun readPendingEvidenceReviewCases(
        learnerId: String,
        subject: String,
        limit: Int,
    ): List<MasteryEvidenceReviewCaseEntity>

    @Query(
        "SELECT * FROM mastery_evidence_review_case " +
            "WHERE review_case_id = :reviewCaseId LIMIT 1",
    )
    protected abstract suspend fun findEvidenceReviewCase(
        reviewCaseId: String,
    ): MasteryEvidenceReviewCaseEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertEvidenceReviewResolution(
        entity: MasteryEvidenceReviewResolutionEntity,
    ): Long

    @Query(
        "SELECT * FROM mastery_evidence_review_resolution " +
            "WHERE review_case_id = :reviewCaseId LIMIT 1",
    )
    protected abstract suspend fun findEvidenceReviewResolutionByCase(
        reviewCaseId: String,
    ): MasteryEvidenceReviewResolutionEntity?

    @Query(
        "SELECT * FROM mastery_evidence_review_resolution " +
            "WHERE idempotency_key = :idempotencyKey LIMIT 1",
    )
    protected abstract suspend fun findEvidenceReviewResolutionByIdempotency(
        idempotencyKey: String,
    ): MasteryEvidenceReviewResolutionEntity?
}
