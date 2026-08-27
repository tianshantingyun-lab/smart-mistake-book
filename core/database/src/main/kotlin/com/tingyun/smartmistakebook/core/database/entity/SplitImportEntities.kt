package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

/**
 * A split-import ledger: one import action (a multi-page PDF, a multi-photo
 * batch, or a single page) whose pages are being cut into individual
 * questions by the model. Exists only while the student confirms which
 * pieces to keep; confirmed pieces enter the normal draft workflows.
 */
@Entity(
    tableName = "split_import_job",
    indices = [Index(value = ["source_fingerprint"], unique = true)],
    primaryKeys = ["job_id"],
)
internal data class SplitImportJobEntity(
    @ColumnInfo(name = "job_id")
    val jobId: String,
    @ColumnInfo(name = "source_kind")
    val sourceKind: String,
    @ColumnInfo(name = "source_fingerprint")
    val sourceFingerprint: String,
    @ColumnInfo(name = "source_uri")
    val sourceUri: String,
    @ColumnInfo(name = "page_count")
    val pageCount: Int,
    @ColumnInfo(name = "question_count")
    val questionCount: Int,
    val status: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

/**
 * One cut-out question from a [SplitImportJobEntity]. The draft keeps the
 * exact cropped page-local region plus an ordered ordinal (reading order).
 */
@Entity(
    tableName = "split_import_question",
    primaryKeys = ["job_id", "question_ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = SplitImportJobEntity::class,
            parentColumns = ["job_id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProblemDraftEntity::class,
            parentColumns = ["draft_id"],
            childColumns = ["split_draft_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["job_id", "split_draft_id"]),
        Index(value = ["split_draft_id"]),
        Index(value = ["job_id", "confirm_state"]),
    ],
)
internal data class SplitImportQuestionEntity(
    @ColumnInfo(name = "job_id")
    val jobId: String,
    @ColumnInfo(name = "question_ordinal")
    val questionOrdinal: Int,
    @ColumnInfo(name = "page_index")
    val pageIndex: Int,
    @ColumnInfo(name = "region_left")
    val regionLeft: Double,
    @ColumnInfo(name = "region_top")
    val regionTop: Double,
    @ColumnInfo(name = "region_right")
    val regionRight: Double,
    @ColumnInfo(name = "region_bottom")
    val regionBottom: Double,
    @ColumnInfo(name = "selected")
    val selected: Boolean,
    @ColumnInfo(name = "confirm_state")
    val confirmState: String,
    @ColumnInfo(name = "split_draft_id")
    val splitDraftId: String?,
)