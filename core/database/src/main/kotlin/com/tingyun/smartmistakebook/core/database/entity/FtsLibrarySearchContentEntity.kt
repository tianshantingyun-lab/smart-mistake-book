package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts4

/**
 * External-content FTS4 index over [LibrarySearchContentEntity].
 *
 * trigram tokenizer is intentionally NOT used: Android's bundled SQLite
 * version varies across API levels and trigram support cannot be guaranteed;
 * the CJK space-segmentation strategy in CjkTextTokenizer works on every
 * SQLite build.
 */
@Fts4(contentEntity = LibrarySearchContentEntity::class)
@Entity(tableName = "library_search_fts")
data class FtsLibrarySearchContentEntity(
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
