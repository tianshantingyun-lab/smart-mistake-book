package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Fts4
import androidx.room3.FtsOptions
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "student_problem_search_document",
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["revision_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["revision_id"], unique = true),
        Index(value = ["source_canonical_fingerprint"]),
    ],
)
internal data class StudentProblemSearchDocumentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0,
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "source_canonical_fingerprint")
    val sourceCanonicalFingerprint: String,
    @ColumnInfo(name = "normalized_text")
    val normalizedText: String,
    @ColumnInfo(name = "tokenized_text")
    val tokenizedText: String,
    @ColumnInfo(name = "indexed_at_epoch_millis")
    val indexedAtEpochMillis: Long,
)

@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    contentEntity = StudentProblemSearchDocumentEntity::class,
)
@Entity(tableName = "student_problem_search_fts")
internal data class StudentProblemSearchFtsEntity(
    @ColumnInfo(name = "tokenized_text")
    val tokenizedText: String,
)

@Entity(tableName = "student_problem_search_index_state")
internal data class StudentProblemSearchIndexStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "index_key")
    val indexKey: String,
    val state: String,
    @ColumnInfo(name = "after_revision_id")
    val afterRevisionId: String?,
    @ColumnInfo(name = "indexed_document_count")
    val indexedDocumentCount: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

internal enum class StudentProblemSearchIndexState {
    PREPARING,
    READY,
}
