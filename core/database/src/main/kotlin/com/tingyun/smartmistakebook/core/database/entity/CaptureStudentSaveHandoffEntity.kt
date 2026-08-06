package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "capture_student_save_handoff",
    indices = [
        Index(
            value = [
                "learner_id",
                "state",
                "prepared_at_epoch_millis",
                "intent_id",
            ],
        ),
    ],
)
internal data class CaptureStudentSaveHandoffEntity(
    @PrimaryKey
    @ColumnInfo(name = "intent_id")
    val intentId: String,
    @ColumnInfo(name = "intent_canonical_fingerprint")
    val intentCanonicalFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "draft_id")
    val draftId: String,
    @ColumnInfo(name = "draft_revision_number")
    val draftRevisionNumber: Int,
    @ColumnInfo(name = "session_id")
    val sessionId: String?,
    @ColumnInfo(name = "target_subject")
    val targetSubject: String,
    @ColumnInfo(name = "target_problem_id")
    val targetProblemId: String,
    @ColumnInfo(name = "target_practice_unit_id")
    val targetPracticeUnitId: String,
    @ColumnInfo(name = "target_problem_ref_schema_version")
    val targetProblemRefSchemaVersion: Int,
    @ColumnInfo(name = "target_revision_id")
    val targetRevisionId: String,
    @ColumnInfo(name = "target_revision_number")
    val targetRevisionNumber: Int,
    @ColumnInfo(name = "target_document_canonical_fingerprint")
    val targetDocumentCanonicalFingerprint: String,
    @ColumnInfo(name = "target_revision_ref_schema_version")
    val targetRevisionRefSchemaVersion: Int,
    val state: String,
    @ColumnInfo(name = "prepared_at_epoch_millis")
    val preparedAtEpochMillis: Long,
    @ColumnInfo(name = "finalized_at_epoch_millis")
    val finalizedAtEpochMillis: Long?,
    @ColumnInfo(name = "target_save_receipt_fingerprint")
    val targetSaveReceiptFingerprint: String?,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
)
