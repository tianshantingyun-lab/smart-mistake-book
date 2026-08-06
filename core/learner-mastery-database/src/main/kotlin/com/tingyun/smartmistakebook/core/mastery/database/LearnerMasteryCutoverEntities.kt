package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = LEARNER_MASTERY_CUTOVER_FENCE_TABLE,
    primaryKeys = [LEARNER_MASTERY_CUTOVER_SINGLETON_COLUMN],
)
internal data class LearnerMasteryCutoverFenceEntity(
    @ColumnInfo(name = LEARNER_MASTERY_CUTOVER_SINGLETON_COLUMN)
    val singletonKey: String,
    @ColumnInfo(name = "cutover_generation")
    val cutoverGeneration: Long,
    @ColumnInfo(name = "student_import_evidence_fingerprint")
    val studentImportEvidenceFingerprint: String,
    @ColumnInfo(name = "mastery_import_evidence_fingerprint")
    val masteryImportEvidenceFingerprint: String,
    @ColumnInfo(name = "cutover_intent_fingerprint")
    val cutoverIntentFingerprint: String,
    @ColumnInfo(name = "fence_fingerprint")
    val fenceFingerprint: String,
)

@Entity(
    tableName = LEARNER_MASTERY_CUTOVER_COMPLETION_RECEIPT_TABLE,
    primaryKeys = [LEARNER_MASTERY_CUTOVER_SINGLETON_COLUMN],
    foreignKeys = [
        ForeignKey(
            entity = LearnerMasteryCutoverFenceEntity::class,
            parentColumns = [LEARNER_MASTERY_CUTOVER_SINGLETON_COLUMN],
            childColumns = [LEARNER_MASTERY_CUTOVER_SINGLETON_COLUMN],
        ),
    ],
)
internal data class LearnerMasteryCutoverCompletionReceiptEntity(
    @ColumnInfo(name = LEARNER_MASTERY_CUTOVER_SINGLETON_COLUMN)
    val singletonKey: String,
    @ColumnInfo(name = "cutover_generation")
    val cutoverGeneration: Long,
    @ColumnInfo(name = "cutover_intent_fingerprint")
    val cutoverIntentFingerprint: String,
    @ColumnInfo(name = "authority_fence_fingerprint")
    val authorityFenceFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "source_generation")
    val sourceGeneration: String,
    @ColumnInfo(name = "migration_ledger_canonical_digest")
    val migrationLedgerCanonicalDigest: String,
    @ColumnInfo(name = "ledger_binding_fingerprint")
    val ledgerBindingFingerprint: String,
    @ColumnInfo(name = "receipt_fingerprint")
    val receiptFingerprint: String,
)

@Entity(
    tableName = LEARNER_MASTERY_MIGRATION_DESTINATION_RECORD_TABLE,
    primaryKeys = [
        "learner_id",
        "source_generation",
        "batch_sequence",
        "observation_ordinal",
    ],
    foreignKeys = [
        ForeignKey(
            entity = MasteryLegacyFactMigrationCheckpointEntity::class,
            parentColumns = ["learner_id", "source_generation", "batch_sequence"],
            childColumns = ["learner_id", "source_generation", "batch_sequence"],
            onDelete = ForeignKey.RESTRICT,
            deferred = true,
        ),
        ForeignKey(
            entity = MasterySourceFactEntity::class,
            parentColumns = ["source_fact_id"],
            childColumns = ["source_fact_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MasteryObservationCandidateEntity::class,
            parentColumns = ["candidate_id"],
            childColumns = ["candidate_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = ["learner_id", "source_generation", "source_fact_id"],
            unique = true,
        ),
        Index(
            value = ["learner_id", "source_generation", "candidate_id"],
            unique = true,
        ),
        Index(value = ["source_fact_id"]),
        Index(value = ["candidate_id"]),
    ],
)
internal data class LearnerMasteryMigrationDestinationRecordEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "source_generation")
    val sourceGeneration: String,
    @ColumnInfo(name = "batch_sequence")
    val batchSequence: Long,
    @ColumnInfo(name = "observation_ordinal")
    val observationOrdinal: Int,
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "source_fact_canonical_fingerprint")
    val sourceFactCanonicalFingerprint: String,
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    @ColumnInfo(name = "candidate_canonical_fingerprint")
    val candidateCanonicalFingerprint: String,
    @ColumnInfo(name = "destination_record_canonical_fingerprint")
    val destinationRecordCanonicalFingerprint: String,
)

@Entity(
    tableName = LEARNER_MASTERY_LEGACY_SNAPSHOT_PAGE_TABLE,
    primaryKeys = ["learner_id", "source_generation", "batch_sequence"],
    indices = [
        Index(
            value = [
                "learner_id",
                "source_generation",
                "source_page_canonical_fingerprint",
            ],
            unique = true,
        ),
    ],
)
internal data class LearnerMasteryLegacySnapshotPageEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "source_generation")
    val sourceGeneration: String,
    @ColumnInfo(name = "batch_sequence")
    val batchSequence: Long,
    @ColumnInfo(name = "source_page_canonical_fingerprint")
    val sourcePageCanonicalFingerprint: String,
    @ColumnInfo(name = "after_occurred_at_epoch_millis")
    val afterOccurredAtEpochMillis: Long?,
    @ColumnInfo(name = "after_source_fact_id")
    val afterSourceFactId: String?,
    @ColumnInfo(name = "terminal_occurred_at_epoch_millis")
    val terminalOccurredAtEpochMillis: Long?,
    @ColumnInfo(name = "terminal_source_fact_id")
    val terminalSourceFactId: String?,
    @ColumnInfo(name = "snapshot_count")
    val snapshotCount: Int,
    @ColumnInfo(name = "final_batch")
    val finalBatch: Boolean,
    @ColumnInfo(name = "page_receipt_canonical_fingerprint")
    val pageReceiptCanonicalFingerprint: String,
)

@Entity(
    tableName = LEARNER_MASTERY_LEGACY_OBSERVATION_SNAPSHOT_TABLE,
    primaryKeys = [
        "learner_id",
        "source_generation",
        "batch_sequence",
        "snapshot_ordinal",
    ],
    foreignKeys = [
        ForeignKey(
            entity = LearnerMasteryLegacySnapshotPageEntity::class,
            parentColumns = ["learner_id", "source_generation", "batch_sequence"],
            childColumns = ["learner_id", "source_generation", "batch_sequence"],
            onDelete = ForeignKey.RESTRICT,
            deferred = true,
        ),
    ],
    indices = [
        Index(
            value = ["learner_id", "source_generation", "source_fact_id"],
            unique = true,
        ),
    ],
)
internal data class LearnerMasteryLegacyObservationSnapshotEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "source_generation")
    val sourceGeneration: String,
    @ColumnInfo(name = "batch_sequence")
    val batchSequence: Long,
    @ColumnInfo(name = "snapshot_ordinal")
    val snapshotOrdinal: Int,
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "fact_kind")
    val factKind: String,
    @ColumnInfo(name = "anchor_id")
    val anchorId: String,
    @ColumnInfo(name = "subject")
    val subject: String,
    @ColumnInfo(name = "conversation_generation")
    val conversationGeneration: Long?,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String?,
    @ColumnInfo(name = "turn_receipt_id")
    val turnReceiptId: String?,
    @ColumnInfo(name = "evidence_request_id")
    val evidenceRequestId: String?,
    @ColumnInfo(name = "response_fingerprint")
    val responseFingerprint: String,
    @ColumnInfo(name = "key_version")
    val keyVersion: Int,
    @ColumnInfo(name = "nonce")
    val nonce: ByteArray,
    @ColumnInfo(name = "ciphertext")
    val ciphertext: ByteArray,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "source_version")
    val sourceVersion: String,
    @ColumnInfo(name = "source_payload_canonical_fingerprint")
    val sourcePayloadCanonicalFingerprint: String,
    @ColumnInfo(name = "proof_present")
    val proofPresent: Boolean,
    @ColumnInfo(name = "source_proof_canonical_fingerprint")
    val sourceProofCanonicalFingerprint: String?,
    @ColumnInfo(name = "source_reference_id")
    val sourceReferenceId: String?,
    @ColumnInfo(name = "target_kind")
    val targetKind: String?,
    @ColumnInfo(name = "target_database")
    val targetDatabase: String?,
    @ColumnInfo(name = "target_id")
    val targetId: String?,
    @ColumnInfo(name = "target_version")
    val targetVersion: String?,
    @ColumnInfo(name = "target_canonical_fingerprint")
    val targetCanonicalFingerprint: String?,
    @ColumnInfo(name = "attested_at_epoch_millis")
    val attestedAtEpochMillis: Long?,
    @ColumnInfo(name = "source_record_canonical_fingerprint")
    val sourceRecordCanonicalFingerprint: String,
    @ColumnInfo(name = "snapshot_canonical_fingerprint")
    val snapshotCanonicalFingerprint: String,
)

internal const val LEARNER_MASTERY_CUTOVER_FENCE_TABLE = "mastery_cutover_fence"
internal const val LEARNER_MASTERY_CUTOVER_COMPLETION_RECEIPT_TABLE =
    "mastery_cutover_completion_receipt"
internal const val LEARNER_MASTERY_MIGRATION_DESTINATION_RECORD_TABLE =
    "mastery_legacy_fact_migration_destination_record"
internal const val LEARNER_MASTERY_LEGACY_SNAPSHOT_PAGE_TABLE =
    "mastery_legacy_observation_snapshot_page"
internal const val LEARNER_MASTERY_LEGACY_OBSERVATION_SNAPSHOT_TABLE =
    "mastery_legacy_observation_snapshot"
internal const val LEARNER_MASTERY_CUTOVER_SINGLETON_COLUMN = "singleton_key"
internal const val LEARNER_MASTERY_CUTOVER_SINGLETON_KEY = "learner-mastery"
