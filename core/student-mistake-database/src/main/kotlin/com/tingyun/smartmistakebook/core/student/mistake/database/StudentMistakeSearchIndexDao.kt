package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Update
import androidx.room3.Transaction

/**
 * Student-mistake full-text search index state, backfill and document sync primitives.
 */
@Dao
internal abstract class StudentMistakeSearchIndexDao : StudentMistakeClassificationDao() {
    @Query(
        """
        SELECT index_key, state, after_revision_id,
               indexed_document_count, updated_at_epoch_millis
        FROM student_problem_search_index_state
        WHERE index_key = :indexKey
        LIMIT 1
        """,
    )
    abstract suspend fun readSearchIndexState(
        indexKey: String = STUDENT_PROBLEM_SEARCH_INDEX_KEY,
    ): StudentProblemSearchIndexStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSearchIndexState(
        state: StudentProblemSearchIndexStateEntity,
    ): Long

    @Query(
        """
        SELECT revision.revision_id AS revisionId,
               revision.title AS title,
               revision.stem_markdown AS stemMarkdown,
               unit.title AS practiceUnitTitle
        FROM student_problem_document AS problem
        INNER JOIN student_problem_revision AS revision
          ON revision.revision_id = problem.current_revision_id
        INNER JOIN student_practice_unit AS unit
          ON unit.practice_unit_id = problem.primary_practice_unit_id
         AND unit.problem_id = problem.problem_id
        WHERE (:afterRevisionId IS NULL OR revision.revision_id > :afterRevisionId)
        ORDER BY revision.revision_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readSearchIndexBackfillPage(
        afterRevisionId: String?,
        limit: Int,
    ): List<StudentProblemSearchSourceRow>

    @Query(
        """
        SELECT rowid, revision_id, source_canonical_fingerprint,
               normalized_text, tokenized_text, indexed_at_epoch_millis
        FROM student_problem_search_document
        WHERE revision_id IN (:revisionIds)
        """,
    )
    protected abstract suspend fun readSearchDocuments(
        revisionIds: List<String>,
    ): List<StudentProblemSearchDocumentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSearchDocuments(
        documents: List<StudentProblemSearchDocumentEntity>,
    )

    @Update
    protected abstract suspend fun updateSearchDocuments(
        documents: List<StudentProblemSearchDocumentEntity>,
    )

    @Query(
        """
        UPDATE student_problem_search_index_state
        SET state = :nextState,
            after_revision_id = :nextAfterRevisionId,
            indexed_document_count = :nextIndexedDocumentCount,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE index_key = :indexKey
          AND state = 'PREPARING'
          AND (
            (after_revision_id IS NULL AND :expectedAfterRevisionId IS NULL) OR
            after_revision_id = :expectedAfterRevisionId
          )
        """,
    )
    protected abstract suspend fun updateSearchIndexState(
        indexKey: String,
        expectedAfterRevisionId: String?,
        nextState: String,
        nextAfterRevisionId: String?,
        nextIndexedDocumentCount: Long,
        updatedAtEpochMillis: Long,
    ): Int

    @Transaction
    open suspend fun ensureSearchIndexState(
        proposed: StudentProblemSearchIndexStateEntity,
    ): StudentProblemSearchIndexStateEntity {
        readSearchIndexState(proposed.indexKey)?.let { return it }
        insertSearchIndexState(proposed)
        return checkNotNull(readSearchIndexState(proposed.indexKey)) {
            "Search-index state was not persisted"
        }
    }

    @Transaction
    open suspend fun applySearchIndexBackfillBatch(
        expectedAfterRevisionId: String?,
        documents: List<StudentProblemSearchDocumentEntity>,
        nextAfterRevisionId: String?,
        completed: Boolean,
        indexedDocumentCount: Long,
        updatedAtEpochMillis: Long,
    ): Boolean {
        val current =
            readSearchIndexState(STUDENT_PROBLEM_SEARCH_INDEX_KEY)
                ?: return false
        if (
            current.state != StudentProblemSearchIndexState.PREPARING.name ||
            current.afterRevisionId != expectedAfterRevisionId
        ) {
            return false
        }
        synchronizeSearchDocuments(documents)
        return updateSearchIndexState(
            indexKey = STUDENT_PROBLEM_SEARCH_INDEX_KEY,
            expectedAfterRevisionId = expectedAfterRevisionId,
            nextState =
                if (completed) {
                    StudentProblemSearchIndexState.READY.name
                } else {
                    StudentProblemSearchIndexState.PREPARING.name
                },
            nextAfterRevisionId = nextAfterRevisionId,
            nextIndexedDocumentCount = indexedDocumentCount,
            updatedAtEpochMillis = updatedAtEpochMillis,
        ) == 1
    }

    protected open suspend fun synchronizeSearchDocuments(
        documents: List<StudentProblemSearchDocumentEntity>,
    ) {
        if (documents.isEmpty()) return
        val existingByRevision =
            readSearchDocuments(documents.map(StudentProblemSearchDocumentEntity::revisionId))
                .associateBy(StudentProblemSearchDocumentEntity::revisionId)
        val newDocuments = ArrayList<StudentProblemSearchDocumentEntity>(documents.size)
        val changedDocuments = ArrayList<StudentProblemSearchDocumentEntity>(documents.size)
        documents.forEach { document ->
            val existing = existingByRevision[document.revisionId]
            if (existing == null) {
                newDocuments += document
            } else {
                changedDocuments += document.copy(rowId = existing.rowId)
            }
        }
        newDocuments.insertWhenNotEmpty(::insertSearchDocuments)
        changedDocuments.insertWhenNotEmpty(::updateSearchDocuments)
    }

}
