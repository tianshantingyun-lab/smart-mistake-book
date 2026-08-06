package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Immutable receipt for every model submission that reaches the host-owned mastery boundary.
 *
 * Candidate and admission links are deliberately informational rather than foreign keys: malformed
 * and conflicting submissions must remain auditable even when no candidate row can exist. The
 * nullable primary-attempt key is the stable logical request fingerprint, so changing mode or
 * permission epochs cannot mint another candidate budget.
 */
@Entity(
    tableName = LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE,
    indices = [
        Index(
            value = ["request_generation_fingerprint", "proposal_fingerprint"],
            unique = true,
        ),
        Index(value = ["primary_attempt_key"], unique = true),
        Index(value = ["learner_id", "subject", "received_at_epoch_millis"]),
        Index(value = ["source_fact_id", "received_at_epoch_millis"]),
        Index(value = ["candidate_id"]),
        Index(value = ["admission_receipt_fingerprint"]),
    ],
)
internal data class MasteryModelSubmissionAttemptReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "receipt_fingerprint")
    val receiptFingerprint: String,
    @ColumnInfo(name = "request_generation_fingerprint")
    val requestGenerationFingerprint: String,
    @ColumnInfo(name = "primary_attempt_key")
    val primaryAttemptKey: String?,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "model_version")
    val modelVersion: String,
    @ColumnInfo(name = "request_version")
    val requestVersion: String,
    @ColumnInfo(name = "mode_version")
    val modeVersion: String,
    @ColumnInfo(name = "proposal_fingerprint")
    val proposalFingerprint: String,
    @ColumnInfo(name = "terminal_reason")
    val terminalReason: String,
    @ColumnInfo(name = "candidate_id")
    val candidateId: String?,
    @ColumnInfo(name = "admission_receipt_fingerprint")
    val admissionReceiptFingerprint: String?,
    @ColumnInfo(name = "received_at_epoch_millis")
    val receivedAtEpochMillis: Long,
)

/**
 * Immutable owner receipt connecting one independently evaluated open response to the existing
 * direction-free pending candidate.
 *
 * It is audit context, not learning evidence: no knowledge node, weight, direction, event, or
 * projection field is present. Conversation identities are copied rather than foreign-keyed so
 * deleting a chat cannot erase the learner's durable evidence provenance.
 */
@Entity(
    tableName = LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
    foreignKeys = [
        ForeignKey(
            entity = MasteryObservationCandidateEntity::class,
            parentColumns = ["candidate_id", "canonical_fingerprint"],
            childColumns = ["candidate_id", "candidate_canonical_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MasteryEvidenceReviewCaseEntity::class,
            parentColumns = ["review_case_id"],
            childColumns = ["review_case_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = ["candidate_id", "candidate_canonical_fingerprint"],
        ),
        Index(
            value = ["review_case_id"],
        ),
        Index(value = ["candidate_idempotency_key"], unique = true),
        Index(value = ["lineage_parent_fingerprint"], unique = true),
        Index(value = ["logical_attempt_fingerprint"]),
        Index(value = ["logical_attempt_fingerprint", "revision_ordinal"], unique = true),
        Index(value = ["source_fact_id", "received_at_epoch_millis"]),
        Index(value = ["learner_id", "subject", "received_at_epoch_millis"]),
        Index(value = ["conversation_id", "conversation_generation"]),
        Index(value = ["question_document_id", "question_revision_number"]),
    ],
)
internal data class MasteryOpenResponseWeakCandidateReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "receipt_fingerprint")
    val receiptFingerprint: String,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "logical_attempt_fingerprint")
    val logicalAttemptFingerprint: String,
    @ColumnInfo(name = "lineage_parent_fingerprint")
    val lineageParentFingerprint: String,
    @ColumnInfo(name = "revision_ordinal")
    val revisionOrdinal: Long?,
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    @ColumnInfo(name = "candidate_canonical_fingerprint")
    val candidateCanonicalFingerprint: String,
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "review_case_id")
    val reviewCaseId: String,
    @ColumnInfo(name = "review_case_fingerprint")
    val reviewCaseFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "scope_fingerprint")
    val scopeFingerprint: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "conversation_generation")
    val conversationGeneration: Long,
    @ColumnInfo(name = "conversation_state_version")
    val conversationStateVersion: Long,
    @ColumnInfo(name = "question_document_id")
    val questionDocumentId: String,
    @ColumnInfo(name = "question_revision_number")
    val questionRevisionNumber: Int,
    @ColumnInfo(name = "question_fingerprint")
    val questionFingerprint: String,
    @ColumnInfo(name = "answer_fingerprint")
    val responseBinding: String,
    @ColumnInfo(name = "evidence_request_id")
    val evidenceRequestId: String,
    @ColumnInfo(name = "presentation_fingerprint")
    val presentationFingerprint: String,
    @ColumnInfo(name = "problem_fingerprint")
    val problemFingerprint: String,
    @ColumnInfo(name = "problem_family_fingerprint")
    val problemFamilyFingerprint: String,
    @ColumnInfo(name = "turn_reference_id")
    val turnReferenceId: String,
    @ColumnInfo(name = "turn_ordinal")
    val turnOrdinal: Int,
    @ColumnInfo(name = "turn_generation")
    val turnGeneration: Long,
    @ColumnInfo(name = "mode_version")
    val modeVersion: Long,
    @ColumnInfo(name = "request_version")
    val requestVersion: Long,
    @ColumnInfo(name = "attempt_ordinal")
    val attemptOrdinal: Int,
    @ColumnInfo(name = "hint_count")
    val hintCount: Int,
    @ColumnInfo(name = "answer_was_revealed")
    val answerWasRevealed: Boolean,
    @ColumnInfo(name = "model_task_request_id")
    val modelTaskRequestId: String,
    @ColumnInfo(name = "model_response_schema_version")
    val modelResponseSchemaVersion: Int,
    @ColumnInfo(name = "evaluator_request_version")
    val evaluatorRequestVersion: Long,
    @ColumnInfo(name = "candidate_idempotency_key")
    val candidateIdempotencyKey: String,
    @ColumnInfo(name = "revision_of_candidate_idempotency_key")
    val revisionOfCandidateIdempotencyKey: String?,
    @ColumnInfo(name = "evidence_fingerprint")
    val evidenceFingerprint: String,
    @ColumnInfo(name = "model_version")
    val modelVersion: String,
    val outcome: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "received_at_epoch_millis")
    val receivedAtEpochMillis: Long,
    @ColumnInfo(name = "proof_chain_version", defaultValue = "0")
    val proofChainVersion: Int = 0,
)

/** Immutable, content-free attestation of the complete host-validated model evaluation. */
@Entity(
    tableName = LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE,
    foreignKeys = [
        ForeignKey(
            entity = MasteryOpenResponseWeakCandidateReceiptEntity::class,
            parentColumns = ["receipt_fingerprint"],
            childColumns = ["receipt_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MasteryEvidenceReviewCaseEntity::class,
            parentColumns = ["review_case_id"],
            childColumns = ["review_case_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["receipt_fingerprint"], unique = true),
        Index(value = ["review_case_id"]),
        Index(value = ["candidate_id"]),
        Index(value = ["learner_id", "subject", "received_at_epoch_millis"]),
        Index(value = ["model_output_fingerprint"], unique = true),
    ],
)
internal data class MasteryOpenResponseModelEvaluationAttestationEntity(
    @PrimaryKey
    @ColumnInfo(name = "attestation_fingerprint")
    val attestationFingerprint: String,
    @ColumnInfo(name = "receipt_fingerprint")
    val receiptFingerprint: String,
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    @ColumnInfo(name = "candidate_canonical_fingerprint")
    val candidateCanonicalFingerprint: String,
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "review_case_id")
    val reviewCaseId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "scope_fingerprint")
    val scopeFingerprint: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "conversation_generation")
    val conversationGeneration: Long,
    @ColumnInfo(name = "conversation_state_version")
    val conversationStateVersion: Long,
    @ColumnInfo(name = "question_document_id")
    val questionDocumentId: String,
    @ColumnInfo(name = "question_revision_number")
    val questionRevisionNumber: Int,
    @ColumnInfo(name = "question_fingerprint")
    val questionFingerprint: String,
    @ColumnInfo(name = "evidence_request_id")
    val evidenceRequestId: String,
    @ColumnInfo(name = "turn_reference_id")
    val turnReferenceId: String,
    @ColumnInfo(name = "turn_ordinal")
    val turnOrdinal: Int,
    @ColumnInfo(name = "turn_generation")
    val turnGeneration: Long,
    @ColumnInfo(name = "mode_version")
    val modeVersion: Long,
    @ColumnInfo(name = "request_version")
    val requestVersion: Long,
    @ColumnInfo(name = "model_task_request_id")
    val modelTaskRequestId: String,
    @ColumnInfo(name = "model_response_schema_version")
    val modelResponseSchemaVersion: Int,
    @ColumnInfo(name = "evaluator_request_version")
    val evaluatorRequestVersion: Long,
    @ColumnInfo(name = "candidate_idempotency_key")
    val candidateIdempotencyKey: String,
    @ColumnInfo(name = "evidence_fingerprint")
    val evidenceFingerprint: String,
    @ColumnInfo(name = "model_output_fingerprint")
    val modelOutputFingerprint: String,
    @ColumnInfo(name = "model_version")
    val modelVersion: String,
    val outcome: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "received_at_epoch_millis")
    val receivedAtEpochMillis: Long,
)

/** Full verified catalog scope. It deliberately contains no prompt/answer/display text or weight. */
@Entity(
    tableName = LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE,
    primaryKeys = ["attestation_fingerprint", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = MasteryOpenResponseModelEvaluationAttestationEntity::class,
            parentColumns = ["attestation_fingerprint"],
            childColumns = ["attestation_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["attestation_fingerprint", "ref_fingerprint"], unique = true),
        Index(
            value = [
                "attestation_fingerprint",
                "knowledge_node_ref_fingerprint",
            ],
            unique = true,
        ),
        Index(value = ["knowledge_node_ref_fingerprint"]),
        Index(value = ["scope_entry_fingerprint"], unique = true),
    ],
)
internal data class MasteryOpenResponseEvaluationKnowledgeScopeEntity(
    @ColumnInfo(name = "attestation_fingerprint")
    val attestationFingerprint: String,
    val ordinal: Int,
    @ColumnInfo(name = "ref_fingerprint")
    val refFingerprint: String,
    val subject: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "knowledge_pack_version")
    val knowledgePackVersion: String,
    @ColumnInfo(name = "knowledge_node_ref_fingerprint")
    val knowledgeNodeRefFingerprint: String,
    @ColumnInfo(name = "manifest_fingerprint")
    val manifestFingerprint: String,
    @ColumnInfo(name = "activation_generation")
    val activationGeneration: Long,
    @ColumnInfo(name = "evaluation_role")
    val evaluationRole: String?,
    @ColumnInfo(name = "scope_entry_fingerprint")
    val scopeEntryFingerprint: String,
)

/** Local-only terminal decision; qualitative model output never controls direction or mass. */
@Entity(
    tableName = LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE,
    foreignKeys = [
        ForeignKey(
            entity = MasteryOpenResponseModelEvaluationAttestationEntity::class,
            parentColumns = ["attestation_fingerprint"],
            childColumns = ["attestation_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MasteryOpenResponseWeakCandidateReceiptEntity::class,
            parentColumns = ["receipt_fingerprint"],
            childColumns = ["receipt_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["attestation_fingerprint"], unique = true),
        Index(value = ["receipt_fingerprint"], unique = true),
        Index(value = ["review_case_id"]),
        Index(value = ["candidate_id"]),
        Index(value = ["accepted_event_id"], unique = true),
        Index(value = ["learner_id", "subject", "decided_at_epoch_millis"]),
    ],
)
internal data class MasteryOpenResponseDedicatedDecisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "decision_fingerprint")
    val decisionFingerprint: String,
    @ColumnInfo(name = "attestation_fingerprint")
    val attestationFingerprint: String,
    @ColumnInfo(name = "receipt_fingerprint")
    val receiptFingerprint: String,
    @ColumnInfo(name = "review_case_id")
    val reviewCaseId: String,
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    val disposition: String,
    val direction: String?,
    @ColumnInfo(name = "local_reason")
    val localReason: String?,
    @ColumnInfo(name = "selected_scope_fingerprint")
    val selectedScopeFingerprint: String?,
    @ColumnInfo(name = "selected_knowledge_count")
    val selectedKnowledgeCount: Int,
    @ColumnInfo(name = "local_policy_version")
    val localPolicyVersion: String,
    @ColumnInfo(name = "calibration_snapshot_fingerprint")
    val calibrationSnapshotFingerprint: String,
    @ColumnInfo(name = "accepted_event_id")
    val acceptedEventId: String?,
    @ColumnInfo(name = "independently_completed")
    val independentlyCompleted: Boolean,
    @ColumnInfo(name = "decided_at_epoch_millis")
    val decidedAtEpochMillis: Long,
)

@Entity(
    tableName = "mastery_evidence_review_case",
    foreignKeys = [
        ForeignKey(
            entity = MasteryObservationCandidateEntity::class,
            parentColumns = ["candidate_id", "canonical_fingerprint"],
            childColumns = ["candidate_id", "candidate_canonical_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MasterySourceProofEntity::class,
            parentColumns = ["source_fact_id", "proof_fingerprint"],
            childColumns = ["source_fact_id", "source_proof_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MasteryCalibrationSnapshotEntity::class,
            parentColumns = [
                "subject",
                "calibration_version",
                "profile_id",
                "snapshot_fingerprint",
            ],
            childColumns = [
                "subject",
                "calibration_version",
                "calibration_profile_id",
                "calibration_snapshot_fingerprint",
            ],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = ["candidate_id", "candidate_canonical_fingerprint"],
            unique = true,
        ),
        Index(value = ["source_fact_id", "source_proof_fingerprint"]),
        Index(value = ["learner_id", "subject", "created_at_epoch_millis"]),
        Index(value = ["review_case_fingerprint"], unique = true),
        Index(
            value = ["review_case_id", "calibration_snapshot_fingerprint"],
            unique = true,
        ),
        Index(
            value = [
                "subject",
                "calibration_version",
                "calibration_profile_id",
                "calibration_snapshot_fingerprint",
            ],
        ),
    ],
)
internal data class MasteryEvidenceReviewCaseEntity(
    @PrimaryKey
    @ColumnInfo(name = "review_case_id")
    val reviewCaseId: String,
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    @ColumnInfo(name = "candidate_canonical_fingerprint")
    val candidateCanonicalFingerprint: String,
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "source_proof_fingerprint")
    val sourceProofFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    val reason: String,
    @ColumnInfo(name = "admission_policy_version")
    val admissionPolicyVersion: String,
    @ColumnInfo(name = "calibration_binding_status")
    val calibrationBindingStatus: String,
    @ColumnInfo(name = "calibration_version")
    val calibrationVersion: String?,
    @ColumnInfo(name = "calibration_profile_id")
    val calibrationProfileId: String?,
    @ColumnInfo(name = "calibration_snapshot_fingerprint")
    val calibrationSnapshotFingerprint: String?,
    @ColumnInfo(name = "review_case_fingerprint")
    val reviewCaseFingerprint: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
) {
    init {
        val status =
            runCatching {
                enumValueOf<MasteryCalibrationBindingStatus>(calibrationBindingStatus)
            }.getOrNull()
        require(status != null) {
            "Evidence review case has an unsupported calibration binding status"
        }
        require(
            when (status) {
                MasteryCalibrationBindingStatus.BOUND ->
                    calibrationVersion != null &&
                        calibrationProfileId != null &&
                        calibrationSnapshotFingerprint != null
                MasteryCalibrationBindingStatus.LEGACY_UNCALIBRATED ->
                    calibrationVersion == null &&
                        calibrationProfileId == null &&
                        calibrationSnapshotFingerprint == null
            },
        ) {
            "Evidence review calibration binding must be either complete or explicitly legacy"
        }
    }
}

@Entity(
    tableName = "mastery_evidence_review_resolution",
    foreignKeys = [
        ForeignKey(
            entity = MasteryEvidenceReviewCaseEntity::class,
            parentColumns = ["review_case_id", "calibration_snapshot_fingerprint"],
            childColumns = ["review_case_id", "calibration_snapshot_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["review_case_id"], unique = true),
        Index(value = ["review_case_id", "calibration_snapshot_fingerprint"]),
        Index(value = ["learner_id", "subject", "decided_at_epoch_millis"]),
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["resolution_fingerprint"], unique = true),
    ],
)
internal data class MasteryEvidenceReviewResolutionEntity(
    @PrimaryKey
    @ColumnInfo(name = "resolution_id")
    val resolutionId: String,
    @ColumnInfo(name = "review_case_id")
    val reviewCaseId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    val decision: String,
    val authority: String,
    @ColumnInfo(name = "reviewer_version")
    val reviewerVersion: String,
    @ColumnInfo(name = "review_evidence_fingerprint")
    val reviewEvidenceFingerprint: String,
    @ColumnInfo(name = "calibration_snapshot_fingerprint")
    val calibrationSnapshotFingerprint: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "resolution_fingerprint")
    val resolutionFingerprint: String,
    @ColumnInfo(name = "decided_at_epoch_millis")
    val decidedAtEpochMillis: Long,
)

/**
 * Exact v9 resolution audit rows whose review cases predate immutable calibration leases.
 *
 * These rows remain query-inert: they preserve the historical reviewer decision without
 * fabricating a v10 calibration binding or authorizing a learning event.
 */
@Entity(
    tableName = LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE,
    indices = [
        Index(value = ["review_case_id"], unique = true),
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["resolution_fingerprint"], unique = true),
    ],
)
internal data class MasteryLegacyEvidenceReviewResolutionAuditEntity(
    @PrimaryKey
    @ColumnInfo(name = "resolution_id")
    val resolutionId: String,
    @ColumnInfo(name = "review_case_id")
    val reviewCaseId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    val decision: String,
    val authority: String,
    @ColumnInfo(name = "reviewer_version")
    val reviewerVersion: String,
    @ColumnInfo(name = "review_evidence_fingerprint")
    val reviewEvidenceFingerprint: String,
    @ColumnInfo(name = "unverified_calibration_snapshot_fingerprint")
    val unverifiedCalibrationSnapshotFingerprint: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "resolution_fingerprint")
    val resolutionFingerprint: String,
    @ColumnInfo(name = "decided_at_epoch_millis")
    val decidedAtEpochMillis: Long,
)
