package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Update

/**
 * Student collection, classification and review-candidate primitive DAO.
 */
@Dao
internal abstract class StudentMistakeClassificationDao : StudentReviewWriteDao() {
    @Query(
        """
        SELECT practice_unit_id, problem_id, learner_id, mistake_state, favorite,
               added_at_epoch_millis, archived_at_epoch_millis,
               trashed_at_epoch_millis, changed_at_epoch_millis
        FROM student_problem_collection
        WHERE practice_unit_id = :practiceUnitId
        LIMIT 1
        """,
    )
    abstract suspend fun readCollection(
        practiceUnitId: String,
    ): StudentProblemCollectionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCollection(collection: StudentProblemCollectionEntity)

    @Update
    protected abstract suspend fun updateCollection(collection: StudentProblemCollectionEntity): Int

    @Query(
        """
        SELECT
          problem.learner_id AS learnerId,
          problem.subject AS subject,
          problem.problem_id AS problemId,
          problem.primary_practice_unit_id AS practiceUnitId,
          revision.revision_id AS revisionId,
          revision.revision_number AS revisionNumber,
          revision.document_canonical_fingerprint AS documentCanonicalFingerprint,
          revision.title AS title,
          substr(revision.stem_markdown, 1, 512) AS stemPreview,
          unit.title AS practiceUnitTitle,
          unit.estimated_duration_seconds AS estimatedDurationSeconds,
          collection.favorite AS favorite,
          collection.changed_at_epoch_millis AS changedAtEpochMillis
        FROM student_problem_collection AS collection
        INNER JOIN student_problem_document AS problem
          ON problem.problem_id = collection.problem_id
        INNER JOIN student_problem_revision AS revision
          ON revision.revision_id = problem.current_revision_id
        INNER JOIN student_practice_unit AS unit
          ON unit.practice_unit_id = collection.practice_unit_id
        WHERE collection.learner_id = :learnerId
          AND collection.mistake_state = 'ACTIVE'
          AND problem.lifecycle_state = 'ACTIVE'
          AND (:subject IS NULL OR problem.subject = :subject)
          AND (:favoriteOnly = 0 OR collection.favorite = 1)
          AND (
            :searchPattern IS NULL OR
            revision.title LIKE :searchPattern ESCAPE '\' OR
            revision.stem_markdown LIKE :searchPattern ESCAPE '\' OR
            unit.title LIKE :searchPattern ESCAPE '\'
          )
          AND (
            :curriculumSectionLabelId IS NULL OR EXISTS (
              SELECT 1
              FROM student_problem_classification_result AS section_result
              WHERE section_result.basis_revision_id = revision.revision_id
                AND section_result.dimension = 'CURRICULUM_SECTION'
                AND section_result.status = 'ACCEPTED'
                AND (
                  section_result.organization_receipt_id = (
                    SELECT receipt.receipt_id
                    FROM student_problem_organization_receipt AS receipt
                    WHERE receipt.basis_revision_id = revision.revision_id
                      AND receipt.status = 'COMPLETED'
                    ORDER BY receipt.organization_revision DESC
                    LIMIT 1
                  )
                  OR (
                    section_result.organization_receipt_id IS NULL
                    AND NOT EXISTS (
                      SELECT 1
                      FROM student_problem_organization_receipt AS receipt
                      WHERE receipt.basis_revision_id = revision.revision_id
                        AND receipt.status = 'COMPLETED'
                    )
                  )
                )
                AND section_result.label_id = :curriculumSectionLabelId
            )
          )
          AND (
            :knowledgeNodeId IS NULL OR EXISTS (
              SELECT 1
              FROM student_problem_classification_result AS knowledge_result
              WHERE knowledge_result.basis_revision_id = revision.revision_id
                AND knowledge_result.dimension = 'KNOWLEDGE'
                AND knowledge_result.status = 'ACCEPTED'
                AND (
                  knowledge_result.organization_receipt_id = (
                    SELECT receipt.receipt_id
                    FROM student_problem_organization_receipt AS receipt
                    WHERE receipt.basis_revision_id = revision.revision_id
                      AND receipt.status = 'COMPLETED'
                    ORDER BY receipt.organization_revision DESC
                    LIMIT 1
                  )
                  OR (
                    knowledge_result.organization_receipt_id IS NULL
                    AND NOT EXISTS (
                      SELECT 1
                      FROM student_problem_organization_receipt AS receipt
                      WHERE receipt.basis_revision_id = revision.revision_id
                        AND receipt.status = 'COMPLETED'
                    )
                  )
                )
                AND knowledge_result.knowledge_subject = :knowledgeSubject
                AND knowledge_result.knowledge_node_id = :knowledgeNodeId
                AND knowledge_result.knowledge_taxonomy_version = :knowledgeTaxonomyVersion
                AND knowledge_result.knowledge_pack_version = :knowledgePackVersion
            )
          )
          AND (
            :cursorChangedAtEpochMillis IS NULL OR
            collection.changed_at_epoch_millis < :cursorChangedAtEpochMillis OR
            (
              collection.changed_at_epoch_millis = :cursorChangedAtEpochMillis AND
              problem.problem_id > :cursorProblemId
            )
          )
        ORDER BY collection.changed_at_epoch_millis DESC, problem.problem_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun searchMistakes(
        learnerId: String,
        subject: String?,
        favoriteOnly: Boolean,
        searchPattern: String?,
        curriculumSectionLabelId: String?,
        knowledgeSubject: String?,
        knowledgeNodeId: String?,
        knowledgeTaxonomyVersion: String?,
        knowledgePackVersion: String?,
        cursorChangedAtEpochMillis: Long?,
        cursorProblemId: String?,
        limit: Int,
    ): List<StudentMistakeSearchRow>

    @Query(
        """
        SELECT classification_id, problem_id, basis_revision_id,
               organization_receipt_id, dimension,
               label_id, knowledge_subject, knowledge_node_id,
               knowledge_taxonomy_version, knowledge_pack_version,
               knowledge_manifest_fingerprint, knowledge_activation_generation,
               model_provider_id, model_id, classifier_version,
               result_canonical_fingerprint, status,
               supersedes_classification_id, recorded_at_epoch_millis
        FROM student_problem_classification_result
        WHERE basis_revision_id = :revisionId
        ORDER BY dimension ASC, classification_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readClassifications(
        revisionId: String,
        limit: Int,
    ): List<StudentProblemClassificationResultEntity>

    @Query(
        """
        SELECT classification_id, problem_id, basis_revision_id,
               organization_receipt_id, dimension,
               label_id, knowledge_subject, knowledge_node_id,
               knowledge_taxonomy_version, knowledge_pack_version,
               knowledge_manifest_fingerprint, knowledge_activation_generation,
               model_provider_id, model_id, classifier_version,
               result_canonical_fingerprint, status,
               supersedes_classification_id, recorded_at_epoch_millis
        FROM student_problem_classification_result AS classification
        WHERE classification.basis_revision_id = :revisionId
          AND classification.status = 'ACCEPTED'
          AND (
            classification.organization_receipt_id = (
              SELECT receipt.receipt_id
              FROM student_problem_organization_receipt AS receipt
              WHERE receipt.basis_revision_id = :revisionId
                AND receipt.status = 'COMPLETED'
              ORDER BY receipt.organization_revision DESC
              LIMIT 1
            )
            OR (
              classification.organization_receipt_id IS NULL
              AND NOT EXISTS (
                SELECT 1
                FROM student_problem_organization_receipt AS receipt
                WHERE receipt.basis_revision_id = :revisionId
                  AND receipt.status = 'COMPLETED'
              )
            )
          )
        ORDER BY dimension ASC, classification_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readCurrentClassifications(
        revisionId: String,
        limit: Int,
    ): List<StudentProblemClassificationResultEntity>

    @Query(
        """
        SELECT classification_id, problem_id, basis_revision_id,
               organization_receipt_id, dimension,
               label_id, knowledge_subject, knowledge_node_id,
               knowledge_taxonomy_version, knowledge_pack_version,
               knowledge_manifest_fingerprint, knowledge_activation_generation,
               model_provider_id, model_id, classifier_version,
               result_canonical_fingerprint, status,
               supersedes_classification_id, recorded_at_epoch_millis
        FROM student_problem_classification_result
        WHERE classification_id IN (:classificationIds)
        ORDER BY classification_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readClassificationsByIds(
        classificationIds: List<String>,
        limit: Int,
    ): List<StudentProblemClassificationResultEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertClassifications(
        results: List<StudentProblemClassificationResultEntity>,
    )

    @Query(
        """
        UPDATE student_problem_classification_result
        SET status = 'SUPERSEDED'
        WHERE classification_id = :classificationId
          AND status = 'ACCEPTED'
        """,
    )
    protected abstract suspend fun supersedeAcceptedClassification(
        classificationId: String,
    ): Int

    @Query(
        """
        SELECT candidate_id, learner_id, practice_unit_id, basis_revision_id,
               reason_codes_wire, item_family_id, estimated_duration_seconds,
               available_at_epoch_millis, due_at_epoch_millis,
               source_evidence_event_kind, source_evidence_event_id,
               source_evidence_sequence, source_evidence_canonical_fingerprint,
               candidate_version, created_at_epoch_millis, updated_at_epoch_millis
        FROM student_review_candidate
        WHERE candidate_id = :candidateId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readReviewCandidate(
        candidateId: String,
    ): StudentReviewCandidateEntity?

    @Query(
        """
        SELECT candidate_id, learner_id, practice_unit_id, basis_revision_id,
               reason_codes_wire, item_family_id, estimated_duration_seconds,
               available_at_epoch_millis, due_at_epoch_millis,
               source_evidence_event_kind, source_evidence_event_id,
               source_evidence_sequence, source_evidence_canonical_fingerprint,
               candidate_version, created_at_epoch_millis, updated_at_epoch_millis
        FROM student_review_candidate
        WHERE learner_id = :learnerId
          AND practice_unit_id = :practiceUnitId
        ORDER BY candidate_version DESC, updated_at_epoch_millis DESC, candidate_id ASC
        LIMIT 1
        """,
    )
    protected abstract suspend fun readLatestReviewCandidate(
        learnerId: String,
        practiceUnitId: String,
    ): StudentReviewCandidateEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertReviewCandidate(
        candidate: StudentReviewCandidateEntity,
    )

    @Update
    protected abstract suspend fun updateReviewCandidate(
        candidate: StudentReviewCandidateEntity,
    ): Int
}
