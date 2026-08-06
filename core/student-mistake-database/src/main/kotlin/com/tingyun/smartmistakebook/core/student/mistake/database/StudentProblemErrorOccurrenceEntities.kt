package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "student_problem_error_occurrence",
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["basis_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["learner_id", "idempotency_key"], unique = true),
        Index(
            value = [
                "learner_id",
                "batch_canonical_fingerprint",
                "basis_revision_id",
            ],
            unique = true,
        ),
        Index(
            value = [
                "learner_id",
                "batch_canonical_fingerprint",
                "import_source_canonical_fingerprint",
            ],
        ),
        Index(
            value = [
                "basis_revision_id",
                "occurred_at_epoch_millis",
                "imported_at_epoch_millis",
                "occurrence_id",
            ],
        ),
        Index(value = ["learner_id", "problem_id", "occurred_at_epoch_millis"]),
        Index(value = ["import_source_canonical_fingerprint"]),
        Index(value = ["occurrence_canonical_fingerprint"]),
    ],
)
internal data class StudentProblemErrorOccurrenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "occurrence_id")
    val occurrenceId: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "basis_revision_number")
    val basisRevisionNumber: Int,
    @ColumnInfo(name = "basis_document_canonical_fingerprint")
    val basisDocumentCanonicalFingerprint: String,
    @ColumnInfo(name = "batch_canonical_fingerprint")
    val batchCanonicalFingerprint: String,
    @ColumnInfo(name = "import_source_canonical_fingerprint")
    val importSourceCanonicalFingerprint: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "imported_at_epoch_millis")
    val importedAtEpochMillis: Long,
    @ColumnInfo(name = "attribution_status")
    val attributionStatus: String?,
    @ColumnInfo(name = "evidence_count")
    val evidenceCount: Int,
    @ColumnInfo(name = "occurrence_canonical_fingerprint")
    val occurrenceCanonicalFingerprint: String,
)

@Entity(
    tableName = "student_problem_error_occurrence_evidence",
    primaryKeys = ["occurrence_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemErrorOccurrenceEntity::class,
            parentColumns = ["occurrence_id"],
            childColumns = ["occurrence_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = [
                "occurrence_id",
                "block_id",
                "source_asset_id",
                "evidence_kind",
            ],
            unique = true,
        ),
        Index(value = ["block_id", "source_asset_id"]),
    ],
)
internal data class StudentProblemErrorOccurrenceEvidenceEntity(
    @ColumnInfo(name = "occurrence_id")
    val occurrenceId: String,
    val ordinal: Int,
    @ColumnInfo(name = "block_id")
    val blockId: String,
    @ColumnInfo(name = "source_asset_id")
    val sourceAssetId: String,
    @ColumnInfo(name = "evidence_kind")
    val evidenceKind: String,
)
