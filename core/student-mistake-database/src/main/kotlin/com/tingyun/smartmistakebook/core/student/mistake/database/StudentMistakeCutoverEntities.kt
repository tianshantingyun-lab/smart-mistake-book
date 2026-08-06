package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = STUDENT_CUTOVER_FENCE_TABLE,
    primaryKeys = [STUDENT_CUTOVER_SINGLETON_COLUMN],
)
internal data class StudentMistakeCutoverFenceEntity(
    @ColumnInfo(name = STUDENT_CUTOVER_SINGLETON_COLUMN)
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
    tableName = STUDENT_CUTOVER_COMPLETION_RECEIPT_TABLE,
    primaryKeys = [STUDENT_CUTOVER_SINGLETON_COLUMN],
    foreignKeys = [
        ForeignKey(
            entity = StudentMistakeCutoverFenceEntity::class,
            parentColumns = [STUDENT_CUTOVER_SINGLETON_COLUMN],
            childColumns = [STUDENT_CUTOVER_SINGLETON_COLUMN],
        ),
    ],
)
internal data class StudentMistakeCutoverCompletionReceiptEntity(
    @ColumnInfo(name = STUDENT_CUTOVER_SINGLETON_COLUMN)
    val singletonKey: String,
    @ColumnInfo(name = "cutover_generation")
    val cutoverGeneration: Long,
    @ColumnInfo(name = "cutover_intent_fingerprint")
    val cutoverIntentFingerprint: String,
    @ColumnInfo(name = "authority_fence_fingerprint")
    val authorityFenceFingerprint: String,
    @ColumnInfo(name = "receipt_fingerprint")
    val receiptFingerprint: String,
)

@Entity(
    tableName = STUDENT_MIGRATION_DESTINATION_RECORD_TABLE,
    primaryKeys = [
        "migration_id",
        "source_page_canonical_fingerprint",
        "page_record_ordinal",
    ],
    foreignKeys = [
        ForeignKey(
            entity = StudentMistakeMigrationCheckpointEntity::class,
            parentColumns = ["migration_id"],
            childColumns = ["migration_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["migration_id", "revision_id"], unique = true),
        Index(value = ["revision_id"]),
    ],
)
internal data class StudentMistakeMigrationDestinationRecordEntity(
    @ColumnInfo(name = "migration_id")
    val migrationId: String,
    @ColumnInfo(name = "source_page_canonical_fingerprint")
    val sourcePageCanonicalFingerprint: String,
    @ColumnInfo(name = "page_record_ordinal")
    val pageRecordOrdinal: Int,
    @ColumnInfo(name = "committed_at_epoch_millis")
    val committedAtEpochMillis: Long,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "import_snapshot_canonical_fingerprint")
    val importSnapshotCanonicalFingerprint: String? = null,
    @ColumnInfo(name = "destination_record_canonical_fingerprint")
    val destinationRecordCanonicalFingerprint: String,
)

/**
 * Append-only record explaining why one legacy destination proof can no longer be replayed.
 *
 * The old fingerprint remains intact in its original ledger. This row only binds that exact proof
 * to the replacement canonical policy; it never invents a replacement for removed source data.
 */
@Entity(
    tableName = STUDENT_MIGRATION_DESTINATION_ATTESTATION_INVALIDATION_TABLE,
    primaryKeys = ["migration_id", "revision_id"],
    foreignKeys = [
        ForeignKey(
            entity = StudentMistakeMigrationDestinationRecordEntity::class,
            parentColumns = ["migration_id", "revision_id"],
            childColumns = ["migration_id", "revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["revision_id"]),
        Index(value = ["legacy_destination_record_canonical_fingerprint"]),
    ],
)
internal data class StudentMistakeDestinationAttestationInvalidationEntity(
    @ColumnInfo(name = "migration_id")
    val migrationId: String,
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "legacy_destination_record_canonical_fingerprint")
    val legacyDestinationRecordCanonicalFingerprint: String,
    @ColumnInfo(name = "invalidation_reason")
    val invalidationReason: String,
    @ColumnInfo(name = "replacement_policy_version")
    val replacementPolicyVersion: Int,
    @ColumnInfo(name = "invalidated_at_schema_version")
    val invalidatedAtSchemaVersion: Int,
)

/** Owner-issued, append-only proof that a legacy destination was checked under the new policy. */
@Entity(
    tableName = STUDENT_MIGRATION_DESTINATION_REATTESTATION_RECEIPT_TABLE,
    primaryKeys = ["migration_id", "revision_id"],
    foreignKeys = [
        ForeignKey(
            entity = StudentMistakeDestinationAttestationInvalidationEntity::class,
            parentColumns = ["migration_id", "revision_id"],
            childColumns = ["migration_id", "revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["replacement_destination_record_canonical_fingerprint"]),
        Index(value = ["receipt_canonical_fingerprint"], unique = true),
    ],
)
internal data class StudentMistakeDestinationReattestationReceiptEntity(
    @ColumnInfo(name = "migration_id")
    val migrationId: String,
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "legacy_destination_record_canonical_fingerprint")
    val legacyDestinationRecordCanonicalFingerprint: String,
    @ColumnInfo(name = "replacement_destination_record_canonical_fingerprint")
    val replacementDestinationRecordCanonicalFingerprint: String,
    @ColumnInfo(name = "canonical_policy_version")
    val canonicalPolicyVersion: Int,
    @ColumnInfo(name = "issuer_key_id")
    val issuerKeyId: String,
    @ColumnInfo(name = "issuer_version")
    val issuerVersion: String,
    @ColumnInfo(name = "issued_at_epoch_millis")
    val issuedAtEpochMillis: Long,
    @ColumnInfo(name = "receipt_canonical_fingerprint")
    val receiptCanonicalFingerprint: String,
)

internal const val STUDENT_CUTOVER_FENCE_TABLE = "student_cutover_fence"
internal const val STUDENT_CUTOVER_COMPLETION_RECEIPT_TABLE =
    "student_cutover_completion_receipt"
internal const val STUDENT_MIGRATION_DESTINATION_RECORD_TABLE =
    "student_mistake_migration_destination_record"
internal const val STUDENT_MIGRATION_DESTINATION_ATTESTATION_INVALIDATION_TABLE =
    "student_mistake_destination_attestation_invalidation"
internal const val STUDENT_MIGRATION_DESTINATION_REATTESTATION_RECEIPT_TABLE =
    "student_mistake_destination_reattestation_receipt"
internal const val STUDENT_CUTOVER_SINGLETON_COLUMN = "singleton_key"
internal const val STUDENT_CUTOVER_SINGLETON_KEY = "student-mistakes"
