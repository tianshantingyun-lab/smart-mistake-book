package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Materialized search projection for the mistake library (audit section 8.2).
 *
 * Text columns store CJK-segmented text produced by
 * [com.tingyun.smartmistakebook.core.database.CjkTextTokenizer] so the FTS4
 * default simple tokenizer can match Chinese queries deterministically.
 */
@Entity(
    tableName = "library_search_content",
    indices = [
        Index(value = ["problem_revision_id"], unique = true),
    ],
)
data class LibrarySearchContentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "content_row_id")
    val contentRowId: Long = 0L,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "stem_text")
    val stemText: String,
    @ColumnInfo(name = "options_text")
    val optionsText: String,
    @ColumnInfo(name = "solution_text")
    val solutionText: String,
    @ColumnInfo(name = "subject")
    val subject: String,
    @ColumnInfo(name = "chapter")
    val chapter: String,
    @ColumnInfo(name = "knowledge_points")
    val knowledgePoints: String,
    @ColumnInfo(name = "tags")
    val tags: String,
    @ColumnInfo(name = "error_reason")
    val errorReason: String,
    @ColumnInfo(name = "formula_tokens")
    val formulaTokens: String,
)

/**
 * Outbox queue feeding incremental projection refreshes. Triggers on
 * problem_revision changes append rows here; the drain path re-segments and
 * rebuilds the FTS index so it never goes stale (audit section 8.2).
 */
@Entity(tableName = "library_search_outbox")
data class LibrarySearchOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "outbox_id")
    val outboxId: Long = 0L,
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "queued_at_epoch_millis")
    val queuedAtEpochMillis: Long,
)
