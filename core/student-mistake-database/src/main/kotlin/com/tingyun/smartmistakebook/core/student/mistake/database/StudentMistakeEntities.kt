package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

internal const val STORE_GENERATION_METADATA_KEY = "store_generation"

internal val STUDENT_MISTAKE_DOMAIN_TABLES: Set<String> =
    setOf(
        "student_store_metadata",
        "student_problem_document",
        "student_problem_revision",
        "student_problem_canonical_identity",
        "student_problem_canonical_source_binding",
        "student_problem_error_occurrence",
        "student_problem_error_occurrence_evidence",
        "student_capture_occurrence_transaction",
        "student_problem_identity_receipt",
        "student_problem_organization_receipt",
        "student_problem_organization_occurrence_binding",
        "student_problem_step_knowledge_binding",
        "student_problem_organization_facet",
        "student_practice_unit",
        "student_problem_import_semantic_snapshot",
        "student_problem_image_reference",
        "student_problem_search_document",
        "student_problem_search_fts",
        "student_problem_search_index_state",
        "student_problem_collection",
        "student_problem_classification_result",
        "student_problem_solution_analysis",
        "student_problem_solution_step",
        "student_problem_error_attribution",
        "student_problem_error_evidence",
        "student_review_candidate",
        "student_review_plan",
        "student_review_queue_item",
        "student_review_self_report_receipt",
        "student_review_session",
        "student_review_transition_receipt",
        "student_review_reveal_receipt",
        "student_trusted_review_answer_rule",
        "student_trusted_review_lease_receipt",
        "student_trusted_review_presentation_fence",
        "student_trusted_review_attempt_receipt",
        "student_trusted_review_assistance_receipt",
        "student_tutor_interaction_answer_certificate",
        "student_tutor_interaction_answer_certificate_status_event",
        "student_tutor_interaction_answer_certificate_lease_receipt",
        "student_tutor_interaction_answer_evaluation_receipt",
        "student_learner_change",
        "student_mistake_save_receipt",
        "student_mistake_migration_checkpoint",
        "student_mistake_migration_receipt",
        "student_mistake_migration_destination_record",
        "student_cutover_fence",
        "student_cutover_completion_receipt",
        "student_mistake_destination_attestation_invalidation",
        "student_mistake_destination_reattestation_receipt",
        "student_capture_save_handoff",
        "student_outbox_authenticity_key_state",
        "student_mastery_relay_source_binding",
        "student_mastery_relay_reauthorization_case",
        "student_mastery_relay_reauthorization_resolution",
        "student_pre_auth_inbox_quarantine",
        "student_authenticated_mastery_inbox_receipt",
        "student_store_outbox",
        "student_store_inbox",
    )

@Entity(tableName = "student_store_metadata")
internal data class StudentStoreMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "metadata_key")
    val metadataKey: String,
    @ColumnInfo(name = "metadata_value")
    val metadataValue: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "student_problem_document",
    indices = [
        Index(
            value = [
                "learner_id",
                "subject",
                "lifecycle_state",
                "updated_at_epoch_millis",
            ],
        ),
        Index(value = ["learner_id", "primary_practice_unit_id"], unique = true),
        Index(value = ["current_revision_id"], unique = true),
        Index(value = ["error_book_entry_id"], unique = true),
        Index(value = ["learner_id", "error_book_entry_id"]),
    ],
)
internal data class StudentProblemDocumentEntity(
    @PrimaryKey
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "primary_practice_unit_id")
    val primaryPracticeUnitId: String,
    @ColumnInfo(name = "current_revision_id")
    val currentRevisionId: String,
    @ColumnInfo(name = "error_book_entry_id")
    val errorBookEntryId: String?,
    @ColumnInfo(name = "lifecycle_state")
    val lifecycleState: String,
    @ColumnInfo(name = "archived_at_epoch_millis")
    val archivedAtEpochMillis: Long?,
    @ColumnInfo(name = "tombstoned_at_epoch_millis")
    val tombstonedAtEpochMillis: Long?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "student_problem_revision",
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemDocumentEntity::class,
            parentColumns = ["problem_id"],
            childColumns = ["problem_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["problem_id", "revision_number"], unique = true),
        Index(value = ["problem_id", "created_at_epoch_millis"]),
        Index(value = ["document_canonical_fingerprint"]),
    ],
)
internal data class StudentProblemRevisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    val title: String?,
    @ColumnInfo(name = "stem_markdown")
    val stemMarkdown: String,
    @ColumnInfo(name = "captured_question_document_wire")
    val capturedQuestionDocumentWire: String?,
    @ColumnInfo(name = "document_canonical_fingerprint")
    val documentCanonicalFingerprint: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "student_practice_unit",
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemDocumentEntity::class,
            parentColumns = ["problem_id"],
            childColumns = ["problem_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["basis_revision_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["problem_id"]),
        Index(value = ["basis_revision_id"]),
        Index(value = ["item_family_id"]),
    ],
)
internal data class StudentPracticeUnitEntity(
    @PrimaryKey
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "unit_kind")
    val unitKind: String,
    val title: String,
    @ColumnInfo(name = "item_family_id")
    val itemFamilyId: String,
    @ColumnInfo(name = "estimated_duration_seconds")
    val estimatedDurationSeconds: Int,
    @ColumnInfo(name = "source_bundle_id")
    val sourceBundleId: String?,
    @ColumnInfo(name = "part_ids_wire")
    val partIdsWire: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "student_problem_image_reference",
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["revision_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["revision_id", "ordinal"], unique = true),
        Index(value = ["content_canonical_fingerprint"]),
    ],
)
internal data class StudentProblemImageReferenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "image_reference_id")
    val imageReferenceId: String,
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "local_content_uri")
    val localContentUri: String,
    @ColumnInfo(name = "content_canonical_fingerprint")
    val contentCanonicalFingerprint: String,
    @ColumnInfo(name = "media_type")
    val mediaType: String,
    val ordinal: Int,
    @ColumnInfo(name = "width_pixels")
    val widthPixels: Int?,
    @ColumnInfo(name = "height_pixels")
    val heightPixels: Int?,
    @ColumnInfo(name = "byte_size")
    val byteSize: Long?,
    @ColumnInfo(name = "selected_regions_wire", defaultValue = "'0:'")
    val selectedRegionsWire: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "student_problem_collection",
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemDocumentEntity::class,
            parentColumns = ["problem_id"],
            childColumns = ["problem_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = StudentPracticeUnitEntity::class,
            parentColumns = ["practice_unit_id"],
            childColumns = ["practice_unit_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["problem_id"]),
        Index(
            value = [
                "learner_id",
                "mistake_state",
                "changed_at_epoch_millis",
                "problem_id",
            ],
            orders = [
                Index.Order.ASC,
                Index.Order.ASC,
                Index.Order.DESC,
                Index.Order.ASC,
            ],
        ),
        Index(
            value = [
                "learner_id",
                "mistake_state",
                "favorite",
                "changed_at_epoch_millis",
                "problem_id",
            ],
            orders = [
                Index.Order.ASC,
                Index.Order.ASC,
                Index.Order.ASC,
                Index.Order.DESC,
                Index.Order.ASC,
            ],
        ),
    ],
)
internal data class StudentProblemCollectionEntity(
    @PrimaryKey
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "mistake_state")
    val mistakeState: String,
    val favorite: Boolean,
    @ColumnInfo(name = "added_at_epoch_millis")
    val addedAtEpochMillis: Long?,
    @ColumnInfo(name = "archived_at_epoch_millis")
    val archivedAtEpochMillis: Long?,
    @ColumnInfo(name = "trashed_at_epoch_millis")
    val trashedAtEpochMillis: Long?,
    @ColumnInfo(name = "changed_at_epoch_millis")
    val changedAtEpochMillis: Long,
)

@Entity(
    tableName = "student_problem_classification_result",
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemDocumentEntity::class,
            parentColumns = ["problem_id"],
            childColumns = ["problem_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["basis_revision_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = StudentProblemOrganizationReceiptEntity::class,
            parentColumns = ["receipt_id"],
            childColumns = ["organization_receipt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["problem_id", "basis_revision_id"]),
        Index(value = ["organization_receipt_id"]),
        Index(
            value = [
                "basis_revision_id",
                "dimension",
                "status",
                "label_id",
            ],
        ),
        Index(
            value = [
                "basis_revision_id",
                "dimension",
                "status",
                "knowledge_subject",
                "knowledge_node_id",
                "knowledge_taxonomy_version",
                "knowledge_pack_version",
            ],
        ),
        Index(value = ["result_canonical_fingerprint"]),
        Index(value = ["supersedes_classification_id"]),
    ],
)
internal data class StudentProblemClassificationResultEntity(
    @PrimaryKey
    @ColumnInfo(name = "classification_id")
    val classificationId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "organization_receipt_id")
    val organizationReceiptId: String? = null,
    val dimension: String,
    @ColumnInfo(name = "label_id")
    val labelId: String,
    @ColumnInfo(name = "knowledge_subject")
    val knowledgeSubject: String?,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String?,
    @ColumnInfo(name = "knowledge_taxonomy_version")
    val knowledgeTaxonomyVersion: String?,
    @ColumnInfo(name = "knowledge_pack_version")
    val knowledgePackVersion: String?,
    @ColumnInfo(name = "knowledge_manifest_fingerprint")
    val knowledgeManifestFingerprint: String?,
    @ColumnInfo(name = "knowledge_activation_generation")
    val knowledgeActivationGeneration: Long?,
    @ColumnInfo(name = "model_provider_id")
    val modelProviderId: String,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "classifier_version")
    val classifierVersion: String,
    @ColumnInfo(name = "result_canonical_fingerprint")
    val resultCanonicalFingerprint: String,
    val status: String,
    @ColumnInfo(name = "supersedes_classification_id")
    val supersedesClassificationId: String?,
    @ColumnInfo(name = "recorded_at_epoch_millis")
    val recordedAtEpochMillis: Long,
)

@Entity(
    tableName = "student_review_candidate",
    foreignKeys = [
        ForeignKey(
            entity = StudentPracticeUnitEntity::class,
            parentColumns = ["practice_unit_id"],
            childColumns = ["practice_unit_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["basis_revision_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["practice_unit_id"]),
        Index(value = ["basis_revision_id"]),
        Index(
            value = [
                "learner_id",
                "available_at_epoch_millis",
                "candidate_id",
            ],
        ),
        Index(value = ["learner_id", "item_family_id"]),
        Index(value = ["source_evidence_event_id", "source_evidence_sequence"]),
    ],
)
internal data class StudentReviewCandidateEntity(
    @PrimaryKey
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "reason_codes_wire")
    val reasonCodesWire: String,
    @ColumnInfo(name = "item_family_id")
    val itemFamilyId: String,
    @ColumnInfo(name = "estimated_duration_seconds")
    val estimatedDurationSeconds: Int,
    @ColumnInfo(name = "available_at_epoch_millis")
    val availableAtEpochMillis: Long,
    @ColumnInfo(name = "due_at_epoch_millis")
    val dueAtEpochMillis: Long?,
    @ColumnInfo(name = "source_evidence_event_kind")
    val sourceEvidenceEventKind: String?,
    @ColumnInfo(name = "source_evidence_event_id")
    val sourceEvidenceEventId: String?,
    @ColumnInfo(name = "source_evidence_sequence")
    val sourceEvidenceSequence: Long?,
    @ColumnInfo(name = "source_evidence_canonical_fingerprint")
    val sourceEvidenceCanonicalFingerprint: String?,
    @ColumnInfo(name = "candidate_version")
    val candidateVersion: Long,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "student_review_plan",
    indices = [
        Index(value = ["learner_id", "local_day_epoch_day"], unique = true),
        Index(value = ["plan_canonical_fingerprint"], unique = true),
        Index(value = ["plan_id", "learner_id"], unique = true),
    ],
)
internal data class StudentReviewPlanEntity(
    @PrimaryKey
    @ColumnInfo(name = "plan_id")
    val planId: String,
    @ColumnInfo(name = "plan_canonical_fingerprint")
    val planCanonicalFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "local_day_epoch_day")
    val localDayEpochDay: Long,
    @ColumnInfo(name = "time_zone_id")
    val timeZoneId: String,
    @ColumnInfo(name = "time_budget_seconds")
    val timeBudgetSeconds: Int,
    @ColumnInfo(name = "generated_at_epoch_millis")
    val generatedAtEpochMillis: Long,
    @ColumnInfo(name = "planner_version")
    val plannerVersion: String,
)

@Entity(
    tableName = "student_review_queue_item",
    foreignKeys = [
        ForeignKey(
            entity = StudentReviewPlanEntity::class,
            parentColumns = ["plan_id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = StudentPracticeUnitEntity::class,
            parentColumns = ["practice_unit_id"],
            childColumns = ["practice_unit_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["basis_revision_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["plan_id", "scheduled_order"], unique = true),
        Index(value = ["queue_item_id", "plan_id", "learner_id"], unique = true),
        Index(value = ["practice_unit_id"]),
        Index(value = ["basis_revision_id"]),
        Index(value = ["plan_id", "state"]),
        Index(value = ["source_evidence_event_id", "source_evidence_sequence"]),
    ],
)
internal data class StudentReviewQueueItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "queue_item_id")
    val queueItemId: String,
    @ColumnInfo(name = "plan_id")
    val planId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "scheduled_order")
    val scheduledOrder: Int,
    @ColumnInfo(name = "estimated_duration_seconds")
    val estimatedDurationSeconds: Int,
    @ColumnInfo(name = "reason_codes_wire")
    val reasonCodesWire: String,
    @ColumnInfo(name = "source_evidence_event_kind")
    val sourceEvidenceEventKind: String?,
    @ColumnInfo(name = "source_evidence_event_id")
    val sourceEvidenceEventId: String?,
    @ColumnInfo(name = "source_evidence_sequence")
    val sourceEvidenceSequence: Long?,
    @ColumnInfo(name = "source_evidence_canonical_fingerprint")
    val sourceEvidenceCanonicalFingerprint: String?,
    val state: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "state_changed_at_epoch_millis")
    val stateChangedAtEpochMillis: Long,
)

@Entity(
    tableName = "student_review_self_report_receipt",
    foreignKeys = [
        ForeignKey(
            entity = StudentReviewQueueItemEntity::class,
            parentColumns = ["queue_item_id"],
            childColumns = ["queue_item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["queue_item_id"], unique = true),
        Index(value = ["report_canonical_fingerprint"], unique = true),
        Index(value = ["learner_id", "reported_at_epoch_millis"]),
    ],
)
internal data class StudentReviewSelfReportReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "self_report_id")
    val selfReportId: String,
    @ColumnInfo(name = "report_canonical_fingerprint")
    val reportCanonicalFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "queue_item_id")
    val queueItemId: String,
    @ColumnInfo(name = "report_kind")
    val reportKind: String,
    @ColumnInfo(name = "reported_at_epoch_millis")
    val reportedAtEpochMillis: Long,
    @ColumnInfo(name = "next_available_at_epoch_millis")
    val nextAvailableAtEpochMillis: Long,
    @ColumnInfo(name = "next_due_at_epoch_millis")
    val nextDueAtEpochMillis: Long?,
    @ColumnInfo(name = "scheduling_policy_version")
    val schedulingPolicyVersion: String,
)

@Entity(
    tableName = "student_store_outbox",
    indices = [
        Index(
            value = ["destination_store", "source_store_generation", "idempotency_key"],
            unique = true,
        ),
        Index(
            value = [
                "learner_id",
                "delivery_state",
                "available_at_epoch_millis",
                "occurred_at_epoch_millis",
            ],
        ),
        Index(value = ["aggregate_id", "aggregate_version"]),
    ],
)
internal data class StudentStoreOutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "source_store")
    val sourceStore: String,
    @ColumnInfo(name = "destination_store")
    val destinationStore: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String?,
    @ColumnInfo(name = "aggregate_id")
    val aggregateId: String,
    @ColumnInfo(name = "aggregate_version")
    val aggregateVersion: Long,
    @ColumnInfo(name = "payload_type")
    val payloadType: String,
    @ColumnInfo(name = "payload_version")
    val payloadVersion: Int,
    @ColumnInfo(name = "payload_canonical_fingerprint")
    val payloadCanonicalFingerprint: String,
    @ColumnInfo(name = "payload_wire")
    val payloadWire: String,
    @ColumnInfo(name = "envelope_canonical_fingerprint")
    val envelopeCanonicalFingerprint: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "source_store_generation")
    val sourceStoreGeneration: String,
    @ColumnInfo(name = "authenticity_proof_protocol_version")
    val authenticityProofProtocolVersion: Int?,
    @ColumnInfo(name = "authenticity_algorithm_version")
    val authenticityAlgorithmVersion: String?,
    @ColumnInfo(name = "authenticity_issuer_key_id")
    val authenticityIssuerKeyId: String?,
    @ColumnInfo(name = "authenticity_learner_id")
    val authenticityLearnerId: String?,
    @ColumnInfo(name = "authenticity_envelope_fingerprint")
    val authenticityEnvelopeFingerprint: String?,
    @ColumnInfo(name = "authenticity_tag_hex")
    val authenticityTagHex: String?,
    @ColumnInfo(name = "authenticity_relay_epoch")
    val authenticityRelayEpoch: String?,
    @ColumnInfo(name = "delivery_state")
    val deliveryState: String,
    @ColumnInfo(name = "delivery_attempt_count")
    val deliveryAttemptCount: Int,
    @ColumnInfo(name = "available_at_epoch_millis")
    val availableAtEpochMillis: Long,
    @ColumnInfo(name = "delivered_at_epoch_millis")
    val deliveredAtEpochMillis: Long?,
)

@Entity(
    tableName = "student_store_inbox",
    indices = [
        Index(
            value = ["source_store", "source_store_generation", "idempotency_key"],
            unique = true,
        ),
        Index(value = ["aggregate_id", "aggregate_version"]),
        Index(value = ["apply_state", "received_at_epoch_millis"]),
    ],
)
internal data class StudentStoreInboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "source_store")
    val sourceStore: String,
    @ColumnInfo(name = "destination_store")
    val destinationStore: String,
    @ColumnInfo(name = "aggregate_id")
    val aggregateId: String,
    @ColumnInfo(name = "aggregate_version")
    val aggregateVersion: Long,
    @ColumnInfo(name = "payload_type")
    val payloadType: String,
    @ColumnInfo(name = "payload_version")
    val payloadVersion: Int,
    @ColumnInfo(name = "payload_canonical_fingerprint")
    val payloadCanonicalFingerprint: String,
    @ColumnInfo(name = "payload_wire")
    val payloadWire: String,
    @ColumnInfo(name = "envelope_canonical_fingerprint")
    val envelopeCanonicalFingerprint: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "source_store_generation")
    val sourceStoreGeneration: String,
    @ColumnInfo(name = "apply_state")
    val applyState: String,
    @ColumnInfo(name = "received_at_epoch_millis")
    val receivedAtEpochMillis: Long,
    @ColumnInfo(name = "applied_at_epoch_millis")
    val appliedAtEpochMillis: Long?,
)
