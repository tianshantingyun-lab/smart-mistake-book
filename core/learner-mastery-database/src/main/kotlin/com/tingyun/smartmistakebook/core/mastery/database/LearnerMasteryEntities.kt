package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "mastery_source_fact",
    indices = [
        Index(value = ["learner_id", "subject", "occurred_at_epoch_millis"]),
        Index(value = ["learner_id", "source_kind", "source_reference_id"], unique = true),
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["canonical_fingerprint"], unique = true),
        Index(
            value = ["learner_id", "subject", "authority_attempt_fingerprint"],
            unique = true,
        ),
        Index(
            value = ["learner_id", "subject", "authority_submission_fingerprint"],
            unique = true,
        ),
    ],
)
internal data class MasterySourceFactEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "source_kind")
    val sourceKind: String,
    @ColumnInfo(name = "source_reference_id")
    val sourceReferenceId: String,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "problem_revision_ref_fingerprint")
    val problemRevisionRefFingerprint: String?,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String?,
    @ColumnInfo(name = "problem_id")
    val problemId: String?,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String?,
    @ColumnInfo(name = "problem_revision_number")
    val problemRevisionNumber: Int?,
    @ColumnInfo(name = "problem_document_fingerprint")
    val problemDocumentFingerprint: String?,
    @ColumnInfo(name = "review_session_id")
    val reviewSessionId: String?,
    @ColumnInfo(name = "review_queue_item_id")
    val reviewQueueItemId: String?,
    @ColumnInfo(name = "review_submission_id")
    val reviewSubmissionId: String?,
    val outcome: String,
    val assistance: String,
    @ColumnInfo(name = "retry_state")
    val retryState: String,
    val authority: String,
    @ColumnInfo(name = "source_payload_fingerprint")
    val sourcePayloadFingerprint: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "attested_at_epoch_millis")
    val attestedAtEpochMillis: Long,
    @ColumnInfo(name = "received_at_epoch_millis")
    val receivedAtEpochMillis: Long,
    @ColumnInfo(name = "source_policy_version")
    val sourcePolicyVersion: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "problem_family_fingerprint")
    val problemFamilyFingerprint: String? = null,
    @ColumnInfo(name = "presentation_fingerprint", defaultValue = "''")
    val presentationFingerprint: String = "",
    @ColumnInfo(name = "response_form", defaultValue = "'IMPORTED_RECORD'")
    val responseForm: String = "IMPORTED_RECORD",
    @ColumnInfo(name = "independently_answered", defaultValue = "0")
    val independentlyAnswered: Boolean = false,
    @ColumnInfo(name = "hint_count", defaultValue = "0")
    val hintCount: Int = 0,
    @ColumnInfo(name = "answer_revealed", defaultValue = "0")
    val answerRevealed: Boolean = false,
    @ColumnInfo(name = "elapsed_duration_millis")
    val elapsedDurationMillis: Long? = null,
    @ColumnInfo(name = "verification_kind", defaultValue = "'SELF_REPORTED'")
    val verificationKind: String = "SELF_REPORTED",
    @ColumnInfo(name = "evidence_context_kind", defaultValue = "'SAVED_MISTAKE'")
    val evidenceContextKind: String = "SAVED_MISTAKE",
    @ColumnInfo(name = "ephemeral_problem_fingerprint")
    val ephemeralProblemFingerprint: String? = null,
    @ColumnInfo(name = "tutor_turn_reference_id")
    val tutorTurnReferenceId: String? = null,
    @ColumnInfo(name = "submission_evidence_fingerprint")
    val submissionEvidenceFingerprint: String? = null,
    @ColumnInfo(name = "attribution_model_version")
    val attributionModelVersion: String? = null,
    @ColumnInfo(name = "authorized_problem_bindings_fingerprint")
    val authorizedProblemBindingsFingerprint: String? = null,
    @ColumnInfo(name = "authorized_knowledge_refs_fingerprint")
    val authorizedKnowledgeRefsFingerprint: String? = null,
    @ColumnInfo(name = "knowledge_manifest_fingerprint")
    val knowledgeManifestFingerprint: String? = null,
    @ColumnInfo(name = "knowledge_activation_generation")
    val knowledgeActivationGeneration: Long? = null,
    @ColumnInfo(name = "authority_attempt_fingerprint")
    val authorityAttemptFingerprint: String? = null,
    @ColumnInfo(name = "authority_submission_fingerprint")
    val authoritySubmissionFingerprint: String? = null,
    @ColumnInfo(name = "authority_presentation_fingerprint")
    val authorityPresentationFingerprint: String? = null,
    @ColumnInfo(name = "authority_problem_family_fingerprint")
    val authorityProblemFamilyFingerprint: String? = null,
    @ColumnInfo(name = "authority_identity_version")
    val authorityIdentityVersion: String? = null,
)

@Entity(
    tableName = "mastery_source_proof",
    foreignKeys = [
        ForeignKey(
            entity = MasterySourceFactEntity::class,
            parentColumns = ["source_fact_id"],
            childColumns = ["source_fact_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["source_fact_id", "proof_fingerprint"], unique = true),
        Index(value = ["proof_fingerprint"], unique = true),
    ],
)
internal data class MasterySourceProofEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "source_fact_canonical_fingerprint")
    val sourceFactCanonicalFingerprint: String,
    @ColumnInfo(name = "source_policy_version")
    val sourcePolicyVersion: String,
    @ColumnInfo(name = "policy_supported")
    val policySupported: Boolean,
    @ColumnInfo(name = "proof_fingerprint")
    val proofFingerprint: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "mastery_problem_binding_authority",
    foreignKeys = [
        ForeignKey(
            entity = MasteryCrossStoreInboxEntity::class,
            parentColumns = ["event_id"],
            childColumns = ["inbox_event_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["inbox_event_id"]),
        Index(value = ["problem_revision_ref_fingerprint", "binding_ref_fingerprint"], unique = true),
        Index(
            value = [
                "problem_revision_ref_fingerprint",
                "learner_id",
                "subject",
                "knowledge_node_id",
                "taxonomy_version",
                "knowledge_pack_version",
            ],
        ),
    ],
)
internal data class MasteryProblemBindingAuthorityEntity(
    @PrimaryKey
    @ColumnInfo(name = "binding_ref_fingerprint")
    val bindingRefFingerprint: String,
    @ColumnInfo(name = "inbox_event_id")
    val inboxEventId: String,
    @ColumnInfo(name = "source_store_generation")
    val sourceStoreGeneration: String,
    @ColumnInfo(name = "envelope_canonical_fingerprint")
    val envelopeCanonicalFingerprint: String,
    @ColumnInfo(name = "payload_canonical_fingerprint")
    val payloadCanonicalFingerprint: String,
    @ColumnInfo(name = "binding_protocol_version")
    val bindingProtocolVersion: Int,
    @ColumnInfo(name = "binding_set_version")
    val bindingSetVersion: Long,
    @ColumnInfo(name = "problem_revision_ref_fingerprint")
    val problemRevisionRefFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "knowledge_pack_version")
    val knowledgePackVersion: String,
    @ColumnInfo(name = "knowledge_node_ref_fingerprint")
    val knowledgeNodeRefFingerprint: String,
    @ColumnInfo(name = "accepted_at_epoch_millis")
    val acceptedAtEpochMillis: Long,
)

/**
 * Mutable pointer to the one current binding-authority snapshot for a problem revision.
 *
 * The referenced inbox rows remain immutable audit records. Replacing this pointer and the
 * materialized authority rows in one transaction gives revocation (including an empty snapshot)
 * real semantics without rewriting history.
 */
@Entity(
    tableName = "mastery_problem_binding_authority_state",
    foreignKeys = [
        ForeignKey(
            entity = MasteryCrossStoreInboxEntity::class,
            parentColumns = ["event_id"],
            childColumns = ["inbox_event_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["inbox_event_id"]),
        Index(value = ["learner_id", "subject"]),
    ],
)
internal data class MasteryProblemBindingAuthorityStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "problem_revision_ref_fingerprint")
    val problemRevisionRefFingerprint: String,
    @ColumnInfo(name = "inbox_event_id")
    val inboxEventId: String,
    @ColumnInfo(name = "source_store_generation")
    val sourceStoreGeneration: String,
    @ColumnInfo(name = "envelope_canonical_fingerprint")
    val envelopeCanonicalFingerprint: String,
    @ColumnInfo(name = "payload_canonical_fingerprint")
    val payloadCanonicalFingerprint: String,
    @ColumnInfo(name = "binding_protocol_version")
    val bindingProtocolVersion: Int,
    @ColumnInfo(name = "binding_set_version")
    val bindingSetVersion: Long,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "changed_at_epoch_millis")
    val changedAtEpochMillis: Long,
)

/**
 * Candidate rows never change state. Their one terminal admission/quarantine decision is stored in
 * [MasteryAdmissionReceiptEntity], so a late proof cannot silently activate an old candidate.
 */
@Entity(
    tableName = "mastery_observation_candidate",
    indices = [
        Index(value = ["learner_id", "subject", "proposed_at_epoch_millis"]),
        Index(value = ["source_fact_id"], unique = true),
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["canonical_fingerprint"], unique = true),
        Index(value = ["candidate_id", "canonical_fingerprint"], unique = true),
    ],
)
internal data class MasteryObservationCandidateEntity(
    @PrimaryKey
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    val confidence: String,
    @ColumnInfo(name = "model_version")
    val modelVersion: String,
    @ColumnInfo(name = "requested_policy_version")
    val requestedPolicyVersion: String,
    @ColumnInfo(name = "proposed_at_epoch_millis")
    val proposedAtEpochMillis: Long,
    @ColumnInfo(name = "received_at_epoch_millis")
    val receivedAtEpochMillis: Long,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "candidate_origin", defaultValue = "'TRUSTED_LOCAL'")
    val candidateOrigin: String = MasteryCandidateOrigin.TRUSTED_LOCAL.name,
)

@Entity(
    tableName = "mastery_candidate_attribution",
    primaryKeys = ["candidate_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = MasteryObservationCandidateEntity::class,
            parentColumns = ["candidate_id"],
            childColumns = ["candidate_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = [
                "candidate_id",
                "knowledge_node_id",
                "taxonomy_version",
                "knowledge_pack_version",
            ],
            unique = true,
        ),
        Index(value = ["knowledge_node_ref_fingerprint"]),
        Index(value = ["problem_binding_ref_fingerprint"]),
    ],
)
internal data class MasteryCandidateAttributionEntity(
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    val ordinal: Int,
    val subject: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "knowledge_pack_version")
    val knowledgePackVersion: String,
    @ColumnInfo(name = "knowledge_node_ref_fingerprint")
    val knowledgeNodeRefFingerprint: String,
    @ColumnInfo(name = "problem_binding_ref_fingerprint")
    val problemBindingRefFingerprint: String?,
    @ColumnInfo(name = "binding_problem_revision_ref_fingerprint")
    val bindingProblemRevisionRefFingerprint: String?,
    val role: String,
    val certainty: String,
    @ColumnInfo(name = "proposal_fingerprint")
    val proposalFingerprint: String,
)

@Entity(
    tableName = "mastery_admission_receipt",
    foreignKeys = [
        ForeignKey(
            entity = MasteryObservationCandidateEntity::class,
            parentColumns = ["candidate_id", "canonical_fingerprint"],
            childColumns = ["candidate_id", "candidate_canonical_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = ["candidate_id", "candidate_canonical_fingerprint"],
            unique = true,
        ),
        Index(value = ["receipt_fingerprint"], unique = true),
        Index(value = ["learner_id", "disposition", "decided_at_epoch_millis"]),
    ],
)
internal data class MasteryAdmissionReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    @ColumnInfo(name = "candidate_canonical_fingerprint")
    val candidateCanonicalFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val disposition: String,
    @ColumnInfo(name = "inert_reason")
    val inertReason: String?,
    @ColumnInfo(name = "source_proof_fingerprint")
    val sourceProofFingerprint: String?,
    @ColumnInfo(name = "policy_version")
    val policyVersion: String,
    @ColumnInfo(
        name = "admission_policy_version",
        defaultValue = "'learner-mastery-admission-legacy'",
    )
    val admissionPolicyVersion: String = LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
    @ColumnInfo(
        name = "calibration_version",
        defaultValue = "'learner-mastery-calibration-legacy'",
    )
    val calibrationVersion: String = LEARNER_MASTERY_CALIBRATION_VERSION,
    @ColumnInfo(name = "event_id")
    val eventId: String?,
    @ColumnInfo(name = "receipt_fingerprint")
    val receiptFingerprint: String,
    @ColumnInfo(name = "decided_at_epoch_millis")
    val decidedAtEpochMillis: Long,
)


@Entity(tableName = "mastery_store_metadata")
internal data class MasteryStoreMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "metadata_key")
    val metadataKey: String,
    @ColumnInfo(name = "metadata_value")
    val metadataValue: String,
)

@Entity(tableName = "mastery_ledger_sequence")
internal data class MasteryLedgerSequenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "last_allocated_sequence")
    val lastAllocatedSequence: Long,
)

@Entity(
    tableName = "mastery_legacy_fact_migration_checkpoint",
    primaryKeys = ["learner_id", "source_generation", "batch_sequence"],
    indices = [
        Index(
            value = ["learner_id", "source_generation", "batch_fingerprint"],
            unique = true,
        ),
    ],
)
internal data class MasteryLegacyFactMigrationCheckpointEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "source_generation")
    val sourceGeneration: String,
    @ColumnInfo(name = "batch_sequence")
    val batchSequence: Long,
    @ColumnInfo(name = "batch_fingerprint")
    val batchFingerprint: String,
    @ColumnInfo(name = "observation_count")
    val observationCount: Int,
    @ColumnInfo(name = "final_batch")
    val finalBatch: Boolean,
    @ColumnInfo(name = "source_policy_version")
    val sourcePolicyVersion: String,
    @ColumnInfo(name = "projection_policy_version")
    val projectionPolicyVersion: String,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long,
)
