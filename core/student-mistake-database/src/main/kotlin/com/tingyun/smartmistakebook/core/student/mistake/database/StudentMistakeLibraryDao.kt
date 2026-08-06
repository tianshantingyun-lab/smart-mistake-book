package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction

/**
 * Read-only library surface for the student mistake notebook.
 *
 * Keeping library paging/detail/facets separate from the write-heavy DAO gives this presentation
 * surface a stable, smaller ownership boundary.
 */
@Dao
internal abstract class StudentMistakeLibraryDao {
    @Query(
        """
        SELECT COALESCE(MAX(change_version), 0)
        FROM student_learner_change
        WHERE learner_id = :learnerId
        """,
    )
    protected abstract suspend fun readChangeVersion(learnerId: String): Long

    @Query(
        """
        SELECT image_reference_id, revision_id, local_content_uri,
               content_canonical_fingerprint, media_type, ordinal,
               width_pixels, height_pixels, byte_size, selected_regions_wire,
               created_at_epoch_millis
        FROM student_problem_image_reference
        WHERE revision_id = :revisionId
        ORDER BY ordinal ASC
        LIMIT :limit
        """,
    )
    internal abstract suspend fun readImages(
        revisionId: String,
        limit: Int,
    ): List<StudentProblemImageReferenceEntity>

    @Query(LIBRARY_ROWS_QUERY + LIBRARY_ROWS_ORDER_AND_LIMIT)
    protected abstract suspend fun readLibraryRows(
        learnerId: String,
        subject: String?,
        curriculumSectionLabelId: String?,
        knowledgeSubject: String?,
        knowledgeNodeId: String?,
        knowledgeTaxonomyVersion: String?,
        knowledgePackVersion: String?,
        cursorChangedAtEpochMillis: Long?,
        cursorProblemId: String?,
        limit: Int,
    ): List<StudentMistakeLibraryListRow>

    @Query(
        LIBRARY_ROWS_QUERY +
            " AND collection.favorite = 1 " +
            LIBRARY_ROWS_ORDER_AND_LIMIT,
    )
    protected abstract suspend fun readFavoriteLibraryRows(
        learnerId: String,
        subject: String?,
        curriculumSectionLabelId: String?,
        knowledgeSubject: String?,
        knowledgeNodeId: String?,
        knowledgeTaxonomyVersion: String?,
        knowledgePackVersion: String?,
        cursorChangedAtEpochMillis: Long?,
        cursorProblemId: String?,
        limit: Int,
    ): List<StudentMistakeLibraryListRow>

    @Query(LIBRARY_SEARCH_ROWS_QUERY + LIBRARY_ROWS_ORDER_AND_LIMIT)
    protected abstract suspend fun readSearchedLibraryRows(
        learnerId: String,
        subject: String?,
        ftsMatchExpression: String,
        normalizedSearchText: String,
        curriculumSectionLabelId: String?,
        knowledgeSubject: String?,
        knowledgeNodeId: String?,
        knowledgeTaxonomyVersion: String?,
        knowledgePackVersion: String?,
        cursorChangedAtEpochMillis: Long?,
        cursorProblemId: String?,
        limit: Int,
    ): List<StudentMistakeLibraryListRow>

    @Query(
        LIBRARY_SEARCH_ROWS_QUERY +
            " AND collection.favorite = 1 " +
            LIBRARY_ROWS_ORDER_AND_LIMIT,
    )
    protected abstract suspend fun readFavoriteSearchedLibraryRows(
        learnerId: String,
        subject: String?,
        ftsMatchExpression: String,
        normalizedSearchText: String,
        curriculumSectionLabelId: String?,
        knowledgeSubject: String?,
        knowledgeNodeId: String?,
        knowledgeTaxonomyVersion: String?,
        knowledgePackVersion: String?,
        cursorChangedAtEpochMillis: Long?,
        cursorProblemId: String?,
        limit: Int,
    ): List<StudentMistakeLibraryListRow>

    @Query(
        """
        SELECT basis_revision_id AS basisRevisionId,
               dimension AS dimension,
               label_id AS labelId,
               knowledge_subject AS knowledgeSubject,
               knowledge_node_id AS knowledgeNodeId,
               knowledge_taxonomy_version AS knowledgeTaxonomyVersion,
               knowledge_pack_version AS knowledgePackVersion
        FROM student_problem_classification_result AS classification
        WHERE classification.basis_revision_id IN (:basisRevisionIds)
          AND classification.status = 'ACCEPTED'
          AND classification.dimension IN ('CURRICULUM_SECTION', 'KNOWLEDGE')
          AND (
            classification.organization_receipt_id = (
              SELECT receipt.receipt_id
              FROM student_problem_organization_receipt AS receipt
              WHERE receipt.basis_revision_id = classification.basis_revision_id
                AND receipt.status = 'COMPLETED'
              ORDER BY receipt.organization_revision DESC
              LIMIT 1
            )
            OR (
              classification.organization_receipt_id IS NULL
              AND NOT EXISTS (
                SELECT 1
                FROM student_problem_organization_receipt AS receipt
                WHERE receipt.basis_revision_id = classification.basis_revision_id
                  AND receipt.status = 'COMPLETED'
              )
            )
          )
        ORDER BY classification.basis_revision_id ASC,
                 classification.dimension ASC,
                 classification.label_id ASC
        """,
    )
    protected abstract suspend fun readAcceptedLibraryClassifications(
        basisRevisionIds: List<String>,
    ): List<StudentMistakeLibraryClassificationRow>

    @Query(
        """
        SELECT
          problem.subject AS subject,
          problem.problem_id AS problemId,
          problem.primary_practice_unit_id AS practiceUnitId,
          revision.revision_id AS revisionId,
          revision.revision_number AS revisionNumber,
          revision.document_canonical_fingerprint AS documentCanonicalFingerprint,
          problem.error_book_entry_id AS errorBookEntryId,
          revision.title AS title,
          revision.stem_markdown AS stemMarkdown,
          unit.unit_kind AS practiceUnitKind,
          unit.title AS practiceUnitTitle,
          unit.estimated_duration_seconds AS estimatedDurationSeconds,
          collection.mistake_state AS mistakeState,
          collection.favorite AS favorite,
          collection.added_at_epoch_millis AS addedAtEpochMillis,
          collection.changed_at_epoch_millis AS changedAtEpochMillis
        FROM student_problem_document AS problem
        INNER JOIN student_problem_collection AS collection
          ON collection.problem_id = problem.problem_id
         AND collection.learner_id = problem.learner_id
         AND collection.practice_unit_id = problem.primary_practice_unit_id
        INNER JOIN student_problem_revision AS revision
          ON revision.revision_id = problem.current_revision_id
        INNER JOIN student_practice_unit AS unit
          ON unit.practice_unit_id = problem.primary_practice_unit_id
         AND unit.problem_id = problem.problem_id
        WHERE problem.learner_id = :learnerId
          AND problem.error_book_entry_id = :errorBookEntryId
          AND problem.lifecycle_state = 'ACTIVE'
          AND collection.mistake_state = 'ACTIVE'
        LIMIT 1
        """,
    )
    protected abstract suspend fun readLibraryDetailRow(
        learnerId: String,
        errorBookEntryId: String,
    ): StudentMistakeLibraryDetailRow?

    @Query(
        """
        SELECT problem.subject AS subject,
               COUNT(DISTINCT problem.problem_id) AS problemCount
        FROM student_problem_collection AS collection
        INNER JOIN student_problem_document AS problem
          ON problem.problem_id = collection.problem_id
         AND problem.learner_id = collection.learner_id
        WHERE collection.learner_id = :learnerId
          AND collection.mistake_state = 'ACTIVE'
          AND problem.lifecycle_state = 'ACTIVE'
          AND problem.error_book_entry_id IS NOT NULL
        GROUP BY problem.subject
        ORDER BY problem.subject ASC
        """,
    )
    protected abstract suspend fun readLibrarySubjectFacets(
        learnerId: String,
    ): List<StudentMistakeSubjectFacetRow>

    @Query(
        """
        SELECT problem.subject AS subject,
               classification.label_id AS labelId,
               COUNT(DISTINCT problem.problem_id) AS problemCount
        FROM student_problem_collection AS collection
        INNER JOIN student_problem_document AS problem
          ON problem.problem_id = collection.problem_id
         AND problem.learner_id = collection.learner_id
        INNER JOIN student_problem_classification_result AS classification
          ON classification.basis_revision_id = problem.current_revision_id
        WHERE collection.learner_id = :learnerId
          AND collection.mistake_state = 'ACTIVE'
          AND problem.lifecycle_state = 'ACTIVE'
          AND problem.error_book_entry_id IS NOT NULL
          AND classification.dimension = 'CURRICULUM_SECTION'
          AND classification.status = 'ACCEPTED'
          AND (
            classification.organization_receipt_id = (
              SELECT receipt.receipt_id
              FROM student_problem_organization_receipt AS receipt
              WHERE receipt.basis_revision_id = classification.basis_revision_id
                AND receipt.status = 'COMPLETED'
              ORDER BY receipt.organization_revision DESC
              LIMIT 1
            )
            OR (
              classification.organization_receipt_id IS NULL
              AND NOT EXISTS (
                SELECT 1
                FROM student_problem_organization_receipt AS receipt
                WHERE receipt.basis_revision_id = classification.basis_revision_id
                  AND receipt.status = 'COMPLETED'
              )
            )
          )
        GROUP BY problem.subject, classification.label_id
        ORDER BY problem.subject ASC, classification.label_id ASC
        """,
    )
    protected abstract suspend fun readLibrarySectionFacets(
        learnerId: String,
    ): List<StudentMistakeSectionFacetRow>

    @Query(
        """
        SELECT classification.knowledge_subject AS knowledgeSubject,
               classification.knowledge_node_id AS knowledgeNodeId,
               classification.knowledge_taxonomy_version AS knowledgeTaxonomyVersion,
               classification.knowledge_pack_version AS knowledgePackVersion,
               COUNT(DISTINCT problem.problem_id) AS problemCount
        FROM student_problem_collection AS collection
        INNER JOIN student_problem_document AS problem
          ON problem.problem_id = collection.problem_id
         AND problem.learner_id = collection.learner_id
        INNER JOIN student_problem_classification_result AS classification
          ON classification.basis_revision_id = problem.current_revision_id
        WHERE collection.learner_id = :learnerId
          AND collection.mistake_state = 'ACTIVE'
          AND problem.lifecycle_state = 'ACTIVE'
          AND problem.error_book_entry_id IS NOT NULL
          AND classification.dimension = 'KNOWLEDGE'
          AND classification.status = 'ACCEPTED'
          AND (
            classification.organization_receipt_id = (
              SELECT receipt.receipt_id
              FROM student_problem_organization_receipt AS receipt
              WHERE receipt.basis_revision_id = classification.basis_revision_id
                AND receipt.status = 'COMPLETED'
              ORDER BY receipt.organization_revision DESC
              LIMIT 1
            )
            OR (
              classification.organization_receipt_id IS NULL
              AND NOT EXISTS (
                SELECT 1
                FROM student_problem_organization_receipt AS receipt
                WHERE receipt.basis_revision_id = classification.basis_revision_id
                  AND receipt.status = 'COMPLETED'
              )
            )
          )
          AND classification.knowledge_subject IS NOT NULL
          AND classification.knowledge_node_id IS NOT NULL
          AND classification.knowledge_taxonomy_version IS NOT NULL
          AND classification.knowledge_pack_version IS NOT NULL
        GROUP BY classification.knowledge_subject,
                 classification.knowledge_node_id,
                 classification.knowledge_taxonomy_version,
                 classification.knowledge_pack_version
        ORDER BY classification.knowledge_subject ASC,
                 classification.knowledge_node_id ASC,
                 classification.knowledge_taxonomy_version ASC,
                 classification.knowledge_pack_version ASC
        """,
    )
    protected abstract suspend fun readLibraryKnowledgeFacets(
        learnerId: String,
    ): List<StudentMistakeKnowledgeFacetRow>

    @Transaction
    internal open suspend fun readLibraryPageSnapshot(
        learnerId: String,
        expectedChangeVersion: Long?,
        subject: String?,
        favoriteOnly: Boolean,
        ftsMatchExpression: String?,
        normalizedSearchText: String?,
        curriculumSectionLabelId: String?,
        knowledgeSubject: String?,
        knowledgeNodeId: String?,
        knowledgeTaxonomyVersion: String?,
        knowledgePackVersion: String?,
        cursorChangedAtEpochMillis: Long?,
        cursorProblemId: String?,
        pageSize: Int,
    ): StudentMistakeLibraryPageSnapshot {
        val currentChangeVersion = readChangeVersion(learnerId)
        if (
            expectedChangeVersion != null &&
            expectedChangeVersion != currentChangeVersion
        ) {
            return StudentMistakeLibraryPageSnapshot.ReloadRequired
        }
        check((ftsMatchExpression == null) == (normalizedSearchText == null)) {
            "Search match expression and normalized text must be provided together"
        }
        val rows =
            if (ftsMatchExpression != null && normalizedSearchText != null && favoriteOnly) {
                readFavoriteSearchedLibraryRows(
                    learnerId = learnerId,
                    subject = subject,
                    ftsMatchExpression = ftsMatchExpression,
                    normalizedSearchText = normalizedSearchText,
                    curriculumSectionLabelId = curriculumSectionLabelId,
                    knowledgeSubject = knowledgeSubject,
                    knowledgeNodeId = knowledgeNodeId,
                    knowledgeTaxonomyVersion = knowledgeTaxonomyVersion,
                    knowledgePackVersion = knowledgePackVersion,
                    cursorChangedAtEpochMillis = cursorChangedAtEpochMillis,
                    cursorProblemId = cursorProblemId,
                    limit = pageSize + 1,
                )
            } else if (ftsMatchExpression != null && normalizedSearchText != null) {
                readSearchedLibraryRows(
                    learnerId = learnerId,
                    subject = subject,
                    ftsMatchExpression = ftsMatchExpression,
                    normalizedSearchText = normalizedSearchText,
                    curriculumSectionLabelId = curriculumSectionLabelId,
                    knowledgeSubject = knowledgeSubject,
                    knowledgeNodeId = knowledgeNodeId,
                    knowledgeTaxonomyVersion = knowledgeTaxonomyVersion,
                    knowledgePackVersion = knowledgePackVersion,
                    cursorChangedAtEpochMillis = cursorChangedAtEpochMillis,
                    cursorProblemId = cursorProblemId,
                    limit = pageSize + 1,
                )
            } else if (favoriteOnly) {
                readFavoriteLibraryRows(
                    learnerId = learnerId,
                    subject = subject,
                    curriculumSectionLabelId = curriculumSectionLabelId,
                    knowledgeSubject = knowledgeSubject,
                    knowledgeNodeId = knowledgeNodeId,
                    knowledgeTaxonomyVersion = knowledgeTaxonomyVersion,
                    knowledgePackVersion = knowledgePackVersion,
                    cursorChangedAtEpochMillis = cursorChangedAtEpochMillis,
                    cursorProblemId = cursorProblemId,
                    limit = pageSize + 1,
                )
            } else {
                readLibraryRows(
                    learnerId = learnerId,
                    subject = subject,
                    curriculumSectionLabelId = curriculumSectionLabelId,
                    knowledgeSubject = knowledgeSubject,
                    knowledgeNodeId = knowledgeNodeId,
                    knowledgeTaxonomyVersion = knowledgeTaxonomyVersion,
                    knowledgePackVersion = knowledgePackVersion,
                    cursorChangedAtEpochMillis = cursorChangedAtEpochMillis,
                    cursorProblemId = cursorProblemId,
                    limit = pageSize + 1,
                )
            }
        val pageRevisionIds =
            rows
                .take(pageSize)
                .map(StudentMistakeLibraryListRow::revisionId)
                .distinct()
        val classifications =
            if (pageRevisionIds.isEmpty()) {
                emptyList()
            } else {
                readAcceptedLibraryClassifications(pageRevisionIds)
            }
        return StudentMistakeLibraryPageSnapshot.Ready(
            changeVersion = currentChangeVersion,
            rows = rows,
            classifications = classifications,
        )
    }

    @Transaction
    internal open suspend fun readLibraryDetailSnapshot(
        learnerId: String,
        errorBookEntryId: String,
    ): StudentMistakeLibraryDetailSnapshot? {
        val row = readLibraryDetailRow(learnerId, errorBookEntryId) ?: return null
        val images = readImages(row.revisionId, MAX_STORED_IMAGES + 1)
        check(images.size <= MAX_STORED_IMAGES) {
            "Stored problem image count exceeds the supported budget"
        }
        val classifications =
            readAcceptedLibraryClassifications(listOf(row.revisionId))
        check(classifications.size <= MAX_STORED_CLASSIFICATIONS) {
            "Stored classification count exceeds the supported budget"
        }
        return StudentMistakeLibraryDetailSnapshot(
            row = row,
            images = images,
            classifications = classifications,
        )
    }

    @Transaction
    internal open suspend fun readLibraryFacetSnapshot(
        learnerId: String,
    ): StudentMistakeLibraryFacetSnapshot =
        StudentMistakeLibraryFacetSnapshot(
            changeVersion = readChangeVersion(learnerId),
            subjects = readLibrarySubjectFacets(learnerId),
            sections = readLibrarySectionFacets(learnerId),
            knowledge = readLibraryKnowledgeFacets(learnerId),
        )
}
