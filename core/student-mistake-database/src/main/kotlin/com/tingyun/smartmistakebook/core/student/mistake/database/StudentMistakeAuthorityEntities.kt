package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "student_problem_solution_analysis",
    foreignKeys = [
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
        Index(value = ["basis_revision_id", "recorded_at_epoch_millis", "solution_analysis_id"]),
        Index(
            value = ["solution_analysis_id", "basis_revision_id"],
            unique = true,
        ),
        Index(value = ["organization_receipt_id"], unique = true),
        Index(value = ["result_canonical_fingerprint"]),
    ],
    primaryKeys = ["solution_analysis_id"],
)
internal data class StudentProblemSolutionAnalysisEntity(
    @ColumnInfo(name = "solution_analysis_id")
    val solutionAnalysisId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "organization_receipt_id")
    val organizationReceiptId: String? = null,
    @ColumnInfo(name = "summary_markdown")
    val summaryMarkdown: String,
    @ColumnInfo(name = "final_answer_markdown")
    val finalAnswerMarkdown: String?,
    @ColumnInfo(name = "model_provider_id")
    val modelProviderId: String,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "analyzer_version")
    val analyzerVersion: String,
    @ColumnInfo(name = "result_canonical_fingerprint")
    val resultCanonicalFingerprint: String,
    @ColumnInfo(name = "recorded_at_epoch_millis")
    val recordedAtEpochMillis: Long,
)

@Entity(
    tableName = "student_problem_solution_step",
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemSolutionAnalysisEntity::class,
            parentColumns = ["solution_analysis_id", "basis_revision_id"],
            childColumns = ["solution_analysis_id", "basis_revision_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["solution_analysis_id", "basis_revision_id"]),
        Index(value = ["solution_analysis_id", "step_id"], unique = true),
        Index(value = ["step_canonical_fingerprint"]),
    ],
    primaryKeys = ["solution_analysis_id", "ordinal"],
)
internal data class StudentProblemSolutionStepEntity(
    @ColumnInfo(name = "solution_analysis_id")
    val solutionAnalysisId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "step_id")
    val stepId: String,
    val ordinal: Int,
    @ColumnInfo(name = "summary_markdown")
    val summaryMarkdown: String,
    @ColumnInfo(name = "reasoning_markdown")
    val reasoningMarkdown: String,
    @ColumnInfo(name = "result_markdown")
    val resultMarkdown: String?,
    @ColumnInfo(name = "step_canonical_fingerprint")
    val stepCanonicalFingerprint: String,
)

@Entity(
    tableName = "student_problem_error_attribution",
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["basis_revision_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = StudentProblemSolutionAnalysisEntity::class,
            parentColumns = ["solution_analysis_id", "basis_revision_id"],
            childColumns = ["solution_analysis_id", "basis_revision_id"],
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
        Index(value = ["basis_revision_id", "recorded_at_epoch_millis", "attribution_id"]),
        Index(value = ["solution_analysis_id", "basis_revision_id"]),
        Index(value = ["organization_receipt_id"]),
        Index(value = ["result_canonical_fingerprint"]),
        Index(value = ["attribution_id", "basis_revision_id"], unique = true),
    ],
    primaryKeys = ["attribution_id"],
)
internal data class StudentProblemErrorAttributionEntity(
    @ColumnInfo(name = "attribution_id")
    val attributionId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "organization_receipt_id")
    val organizationReceiptId: String? = null,
    @ColumnInfo(name = "solution_analysis_id")
    val solutionAnalysisId: String?,
    @ColumnInfo(name = "resolution_status")
    val resolutionStatus: String,
    @ColumnInfo(name = "rationale_markdown")
    val rationaleMarkdown: String,
    @ColumnInfo(name = "step_ordinal")
    val stepOrdinal: Int?,
    @ColumnInfo(name = "atomic_reference_id")
    val atomicReferenceId: String?,
    @ColumnInfo(name = "model_provider_id")
    val modelProviderId: String,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "analyzer_version")
    val analyzerVersion: String,
    @ColumnInfo(name = "result_canonical_fingerprint")
    val resultCanonicalFingerprint: String,
    @ColumnInfo(name = "recorded_at_epoch_millis")
    val recordedAtEpochMillis: Long,
)

@Entity(
    tableName = "student_problem_error_evidence",
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemErrorAttributionEntity::class,
            parentColumns = ["attribution_id", "basis_revision_id"],
            childColumns = ["attribution_id", "basis_revision_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["attribution_id", "basis_revision_id"]),
        Index(value = ["basis_revision_id", "block_id", "source_asset_id"]),
    ],
    primaryKeys = ["attribution_id", "ordinal"],
)
internal data class StudentProblemErrorEvidenceEntity(
    @ColumnInfo(name = "attribution_id")
    val attributionId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    val ordinal: Int,
    @ColumnInfo(name = "block_id")
    val blockId: String,
    @ColumnInfo(name = "source_asset_id")
    val sourceAssetId: String,
    @ColumnInfo(name = "evidence_kind")
    val evidenceKind: String,
)

@Entity(
    tableName = "student_learner_change",
    primaryKeys = ["learner_id"],
)
internal data class StudentLearnerChangeEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "change_version")
    val changeVersion: Long,
)

@Entity(
    tableName = "student_mistake_save_receipt",
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["basis_revision_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["basis_revision_id"], unique = true),
        Index(value = ["error_book_entry_id"], unique = true),
        Index(value = ["intent_canonical_fingerprint"], unique = true),
    ],
    primaryKeys = ["intent_confirmation_id"],
)
internal data class StudentMistakeSaveReceiptEntity(
    @ColumnInfo(name = "intent_confirmation_id")
    val intentConfirmationId: String,
    @ColumnInfo(name = "intent_canonical_fingerprint")
    val intentCanonicalFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "error_book_entry_id")
    val errorBookEntryId: String,
    @ColumnInfo(name = "confirmed_at_epoch_millis")
    val confirmedAtEpochMillis: Long,
    @ColumnInfo(name = "saved_at_epoch_millis")
    val savedAtEpochMillis: Long,
)

@Entity(
    tableName = "student_mistake_migration_checkpoint",
    primaryKeys = ["migration_id"],
    indices = [
        Index(value = ["source_database_canonical_fingerprint"]),
        Index(value = ["completed"]),
    ],
)
internal data class StudentMistakeMigrationCheckpointEntity(
    @ColumnInfo(name = "migration_id")
    val migrationId: String,
    @ColumnInfo(name = "source_database_canonical_fingerprint")
    val sourceDatabaseCanonicalFingerprint: String,
    @ColumnInfo(name = "last_committed_at_epoch_millis")
    val lastCommittedAtEpochMillis: Long?,
    @ColumnInfo(name = "last_problem_id")
    val lastProblemId: String?,
    @ColumnInfo(name = "last_revision_number")
    val lastRevisionNumber: Int?,
    @ColumnInfo(name = "last_revision_id")
    val lastRevisionId: String?,
    @ColumnInfo(name = "imported_record_count")
    val importedRecordCount: Long,
    val completed: Boolean,
    @ColumnInfo(name = "checkpoint_canonical_fingerprint")
    val checkpointCanonicalFingerprint: String,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "destination_ledger_version", defaultValue = "0")
    val destinationLedgerVersion: Int = 0,
)

@Entity(
    tableName = "student_mistake_migration_receipt",
    foreignKeys = [
        ForeignKey(
            entity = StudentMistakeMigrationCheckpointEntity::class,
            parentColumns = ["migration_id"],
            childColumns = ["migration_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["migration_id"]),
        Index(value = ["receipt_canonical_fingerprint"], unique = true),
    ],
    primaryKeys = ["migration_id", "source_page_canonical_fingerprint"],
)
internal data class StudentMistakeMigrationReceiptEntity(
    @ColumnInfo(name = "migration_id")
    val migrationId: String,
    @ColumnInfo(name = "source_page_canonical_fingerprint")
    val sourcePageCanonicalFingerprint: String,
    @ColumnInfo(name = "imported_record_count")
    val importedRecordCount: Int,
    @ColumnInfo(name = "result_last_committed_at_epoch_millis")
    val resultLastCommittedAtEpochMillis: Long?,
    @ColumnInfo(name = "result_last_problem_id")
    val resultLastProblemId: String?,
    @ColumnInfo(name = "result_last_revision_number")
    val resultLastRevisionNumber: Int?,
    @ColumnInfo(name = "result_last_revision_id")
    val resultLastRevisionId: String?,
    @ColumnInfo(name = "result_total_record_count")
    val resultTotalRecordCount: Long,
    @ColumnInfo(name = "result_completed")
    val resultCompleted: Boolean,
    @ColumnInfo(name = "checkpoint_canonical_fingerprint")
    val checkpointCanonicalFingerprint: String,
    @ColumnInfo(name = "receipt_canonical_fingerprint")
    val receiptCanonicalFingerprint: String,
    @ColumnInfo(name = "applied_at_epoch_millis")
    val appliedAtEpochMillis: Long,
)
