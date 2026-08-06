package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.storage.LearningEvidenceRef

internal const val STUDENT_PROBLEM_EXACT_ASSET_IDENTITY_NAMESPACE =
    "exact-asset-selection"
internal const val STUDENT_PROBLEM_OPAQUE_IDENTITY_NAMESPACE =
    "opaque-capture"
internal const val STUDENT_PROBLEM_OWNER_IDENTITY_VERSION = "v1"
internal const val ATOMIC_CAPTURE_IMAGE_REFERENCE_ID_DOMAIN =
    "student-atomic-capture-image-reference-id-v1"
internal const val ATOMIC_CAPTURE_SEARCH_DOCUMENT_DOMAIN =
    "student-atomic-capture-search-document-v1"

internal const val LIBRARY_ROWS_QUERY =
    """
    SELECT
      problem.subject AS subject,
      collection.problem_id AS problemId,
      problem.primary_practice_unit_id AS practiceUnitId,
      revision.revision_id AS revisionId,
      revision.revision_number AS revisionNumber,
      revision.document_canonical_fingerprint AS documentCanonicalFingerprint,
      problem.error_book_entry_id AS errorBookEntryId,
      revision.title AS title,
      substr(revision.stem_markdown, 1, 512) AS stemPreview,
      unit.title AS practiceUnitTitle,
      unit.estimated_duration_seconds AS estimatedDurationSeconds,
      collection.mistake_state AS mistakeState,
      collection.favorite AS favorite,
      collection.added_at_epoch_millis AS addedAtEpochMillis,
      collection.changed_at_epoch_millis AS changedAtEpochMillis
    FROM student_problem_collection AS collection
    INNER JOIN student_problem_document AS problem
      ON problem.problem_id = collection.problem_id
     AND problem.learner_id = collection.learner_id
    INNER JOIN student_problem_revision AS revision
      ON revision.revision_id = problem.current_revision_id
    INNER JOIN student_practice_unit AS unit
      ON unit.practice_unit_id = collection.practice_unit_id
     AND unit.problem_id = collection.problem_id
    WHERE collection.learner_id = :learnerId
      AND collection.mistake_state = 'ACTIVE'
      AND problem.lifecycle_state = 'ACTIVE'
      AND problem.error_book_entry_id IS NOT NULL
      AND (:subject IS NULL OR problem.subject = :subject)
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
          collection.problem_id > :cursorProblemId
        )
      )
    """

internal const val LIBRARY_SEARCH_ROWS_QUERY =
    """
    SELECT
      problem.subject AS subject,
      collection.problem_id AS problemId,
      problem.primary_practice_unit_id AS practiceUnitId,
      revision.revision_id AS revisionId,
      revision.revision_number AS revisionNumber,
      revision.document_canonical_fingerprint AS documentCanonicalFingerprint,
      problem.error_book_entry_id AS errorBookEntryId,
      revision.title AS title,
      substr(revision.stem_markdown, 1, 512) AS stemPreview,
      unit.title AS practiceUnitTitle,
      unit.estimated_duration_seconds AS estimatedDurationSeconds,
      collection.mistake_state AS mistakeState,
      collection.favorite AS favorite,
      collection.added_at_epoch_millis AS addedAtEpochMillis,
      collection.changed_at_epoch_millis AS changedAtEpochMillis
    FROM student_problem_search_fts AS search_fts
    INNER JOIN student_problem_search_document AS search_document
      ON search_document.rowid = search_fts.docid
    INNER JOIN student_problem_revision AS revision
      ON revision.revision_id = search_document.revision_id
    INNER JOIN student_problem_document AS problem
      ON problem.current_revision_id = revision.revision_id
     AND problem.problem_id = revision.problem_id
    INNER JOIN student_problem_collection AS collection
      ON collection.problem_id = problem.problem_id
     AND collection.learner_id = problem.learner_id
     AND collection.practice_unit_id = problem.primary_practice_unit_id
    INNER JOIN student_practice_unit AS unit
      ON unit.practice_unit_id = collection.practice_unit_id
     AND unit.problem_id = collection.problem_id
    WHERE search_fts.tokenized_text MATCH :ftsMatchExpression
      AND instr(search_document.normalized_text, :normalizedSearchText) > 0
      AND collection.learner_id = :learnerId
      AND collection.mistake_state = 'ACTIVE'
      AND problem.lifecycle_state = 'ACTIVE'
      AND problem.error_book_entry_id IS NOT NULL
      AND (:subject IS NULL OR problem.subject = :subject)
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
          collection.problem_id > :cursorProblemId
        )
      )
    """

internal const val LIBRARY_ROWS_ORDER_AND_LIMIT =
    """
    ORDER BY collection.changed_at_epoch_millis DESC, collection.problem_id ASC
    LIMIT :limit
    """

internal data class CommitStudentProblemBundle(
    val problem: StudentProblemDocumentEntity,
    val revision: StudentProblemRevisionEntity,
    val practiceUnit: StudentPracticeUnitEntity,
    val images: List<StudentProblemImageReferenceEntity>,
    val solutionAnalysis: StudentProblemSolutionAnalysisEntity?,
    val solutionSteps: List<StudentProblemSolutionStepEntity>,
    val errorAttributions: List<StudentProblemErrorAttributionEntity>,
    val errorEvidence: List<StudentProblemErrorEvidenceEntity>,
    val searchDocument: StudentProblemSearchDocumentEntity,
    val outbox: StudentStoreOutboxEntity,
    val supersededRevisionOutbox: StudentStoreOutboxEntity? = null,
)

internal data class RecordStudentClassificationsBundle(
    val problemId: String,
    val revisionId: String,
    val results: List<StudentProblemClassificationResultEntity>,
    val expectedCurrentAcceptedIds: Set<String>,
    val supersededAcceptedIds: Set<String>,
    val expectedFinalAcceptedIds: Set<String>,
    val expectedPreviousBindingSetVersion: Long?,
    val outbox: StudentStoreOutboxEntity?,
)

internal data class StoreStudentReviewQueueBundle(
    val plan: StudentReviewPlanEntity,
    val items: List<StudentReviewQueueItemEntity>,
)

internal data class StartStudentReviewSessionBundle(
    val session: StudentReviewSessionEntity,
    val expectedPlanCanonicalFingerprint: String,
)

internal enum class StartStudentReviewSessionDaoDisposition {
    READY,
    ACTIVE_SESSION_CONFLICT,
    LEGACY_ACTIVITY_CONFLICT,
    RELOAD_REQUIRED,
}

internal data class StartStudentReviewSessionDaoResult(
    val disposition: StartStudentReviewSessionDaoDisposition,
    val session: StudentReviewSessionEntity?,
)

internal data class StudentReviewSessionSnapshotBundle(
    val session: StudentReviewSessionEntity,
    val currentQueueItem: StudentReviewQueueReadRow?,
)

internal data class StudentReviewHomeSnapshotBundle(
    val changeVersion: Long,
    val plan: StudentReviewPlanEntity?,
    val planItems: List<StudentReviewQueueReadRow>,
    val activeSession: StudentReviewSessionSnapshotBundle?,
)

internal data class ExistingStudentReviewTransitionContext(
    val planId: String,
    val queueItem: StudentReviewQueueReadRow,
)

internal data class ApplyStudentReviewTransitionBundle(
    val receipt: StudentReviewTransitionReceiptEntity,
    val revealReceipt: StudentReviewRevealReceiptEntity?,
    val outbox: StudentStoreOutboxEntity?,
)

internal enum class ApplyStudentReviewTransitionDisposition {
    APPLIED,
    DUPLICATE,
    RELOAD_REQUIRED,
}

internal class StudentReviewTransitionConcurrencyException :
    IllegalStateException("Review transition changed concurrently")

internal data class SaveConfirmedStudentMistakeBundle(
    val problem: CommitStudentProblemBundle,
    val collection: StudentProblemCollectionEntity,
    val initialReviewCandidate: StudentReviewCandidateEntity,
    val receipt: StudentMistakeSaveReceiptEntity,
)

internal enum class ResolvedAtomicCaptureTargetMode {
    CREATE_IDENTITY,
    REUSE_REVISION,
    CREATE_REVIEWED_ALIAS_REVISION,
}

internal data class ResolvedAtomicCaptureOccurrenceBundle(
    val identity: StudentProblemCanonicalIdentityEntity,
    val sourceBinding: StudentProblemCanonicalSourceBindingEntity,
    val capture: SaveStudentOwnedCaptureBundle,
    val occurrence: StudentProblemErrorOccurrenceBundle,
    val transaction: StudentCaptureOccurrenceTransactionDraft,
    val targetMode: ResolvedAtomicCaptureTargetMode,
    val insertIdentity: Boolean,
    val insertSourceBinding: Boolean,
)

internal data class StudentReviewQueueReadRow(
    val queueItemId: String,
    val planId: String,
    val learnerId: String,
    val subject: String,
    val problemId: String,
    val practiceUnitId: String,
    val basisRevisionId: String,
    val revisionNumber: Int,
    val documentCanonicalFingerprint: String,
    val scheduledOrder: Int,
    val estimatedDurationSeconds: Int,
    val reasonCodesWire: String,
    val sourceEvidenceEventKind: String?,
    val sourceEvidenceEventId: String?,
    val sourceEvidenceSequence: Long?,
    val sourceEvidenceCanonicalFingerprint: String?,
    val state: String,
    val createdAtEpochMillis: Long,
    val stateChangedAtEpochMillis: Long,
)

internal data class StudentMistakeSearchRow(
    val learnerId: String,
    val subject: String,
    val problemId: String,
    val practiceUnitId: String,
    val revisionId: String,
    val revisionNumber: Int,
    val documentCanonicalFingerprint: String,
    val title: String?,
    val stemPreview: String,
    val practiceUnitTitle: String,
    val estimatedDurationSeconds: Int,
    val favorite: Boolean,
    val changedAtEpochMillis: Long,
)

internal data class StudentReviewCandidateReadRow(
    val candidateId: String,
    val learnerId: String,
    val subject: String,
    val problemId: String,
    val practiceUnitId: String,
    val basisRevisionId: String,
    val revisionNumber: Int,
    val documentCanonicalFingerprint: String,
    val reasonCodesWire: String,
    val itemFamilyId: String,
    val estimatedDurationSeconds: Int,
    val availableAtEpochMillis: Long,
    val dueAtEpochMillis: Long?,
    val sourceEvidenceEventKind: String?,
    val sourceEvidenceEventId: String?,
    val sourceEvidenceSequence: Long?,
    val sourceEvidenceCanonicalFingerprint: String?,
    val candidateVersion: Long,
    val updatedAtEpochMillis: Long,
)

internal data class StudentReviewAcceptedKnowledgeRow(
    val basisRevisionId: String,
    val knowledgeSubject: String,
    val knowledgeNodeId: String,
    val knowledgeTaxonomyVersion: String,
    val knowledgePackVersion: String,
    val knowledgeManifestFingerprint: String,
    val knowledgeActivationGeneration: Long,
)

internal data class StudentReviewOrganizationFamilyRow(
    val basisRevisionId: String,
    val familyId: String,
    val basisDocumentCanonicalFingerprint: String,
)

internal data class StudentReviewQueueLocalReadinessRow(
    val queueItemId: String,
    val planId: String,
    val learnerId: String,
    val basisRevisionId: String,
    val savedRevisionEligible: Int,
    val acceptedKnowledgeCount: Int,
)

internal data class FutureUnreadyReviewQueueItem(
    val item: StudentReviewQueueItemEntity,
    val reason: StudentReviewQueueRemovalReason,
)

internal data class PersistedStudentProblemRevisionRefRow(
    val learnerId: String,
    val subject: String,
    val problemId: String,
    val practiceUnitId: String,
    val lifecycleState: String,
    val mistakeState: String?,
    val revisionId: String,
    val revisionNumber: Int,
    val documentCanonicalFingerprint: String,
)

internal data class StudentProblemRevisionHistoryRow(
    val learnerId: String,
    val subject: String,
    val problemId: String,
    val practiceUnitId: String,
    val revisionId: String,
    val revisionNumber: Int,
    val documentCanonicalFingerprint: String,
    val title: String?,
    val stemPreview: String,
    val committedAtEpochMillis: Long,
    val hasCapturedQuestionDocument: Boolean,
    val hasSolutionAnalysis: Boolean,
    val errorAttributionCount: Int,
)

internal data class StudentMistakeLibraryListRow(
    val subject: String,
    val problemId: String,
    val practiceUnitId: String,
    val revisionId: String,
    val revisionNumber: Int,
    val documentCanonicalFingerprint: String,
    val errorBookEntryId: String?,
    val title: String?,
    val stemPreview: String,
    val practiceUnitTitle: String,
    val estimatedDurationSeconds: Int,
    val mistakeState: String,
    val favorite: Boolean,
    val addedAtEpochMillis: Long?,
    val changedAtEpochMillis: Long,
)

internal data class StudentProblemSearchSourceRow(
    val revisionId: String,
    val title: String?,
    val stemMarkdown: String,
    val practiceUnitTitle: String,
)

internal data class StudentMistakeLibraryDetailRow(
    val subject: String,
    val problemId: String,
    val practiceUnitId: String,
    val revisionId: String,
    val revisionNumber: Int,
    val documentCanonicalFingerprint: String,
    val errorBookEntryId: String?,
    val title: String?,
    val stemMarkdown: String,
    val practiceUnitKind: String,
    val practiceUnitTitle: String,
    val estimatedDurationSeconds: Int,
    val mistakeState: String,
    val favorite: Boolean,
    val addedAtEpochMillis: Long?,
    val changedAtEpochMillis: Long,
)

internal data class StudentMistakeLibraryClassificationRow(
    val basisRevisionId: String,
    val dimension: String,
    val labelId: String,
    val knowledgeSubject: String?,
    val knowledgeNodeId: String?,
    val knowledgeTaxonomyVersion: String?,
    val knowledgePackVersion: String?,
)

internal data class StudentMistakeSubjectFacetRow(
    val subject: String,
    val problemCount: Long,
)

internal data class StudentMistakeSectionFacetRow(
    val subject: String,
    val labelId: String,
    val problemCount: Long,
)

internal data class StudentMistakeKnowledgeFacetRow(
    val knowledgeSubject: String?,
    val knowledgeNodeId: String?,
    val knowledgeTaxonomyVersion: String?,
    val knowledgePackVersion: String?,
    val problemCount: Long,
)

internal sealed interface StudentMistakeLibraryPageSnapshot {
    data object ReloadRequired : StudentMistakeLibraryPageSnapshot

    data class Ready(
        val changeVersion: Long,
        val rows: List<StudentMistakeLibraryListRow>,
        val classifications: List<StudentMistakeLibraryClassificationRow>,
    ) : StudentMistakeLibraryPageSnapshot
}

internal data class StudentMistakeLibraryDetailSnapshot(
    val row: StudentMistakeLibraryDetailRow,
    val images: List<StudentProblemImageReferenceEntity>,
    val classifications: List<StudentMistakeLibraryClassificationRow>,
)

internal data class StudentMistakeLibraryFacetSnapshot(
    val changeVersion: Long,
    val subjects: List<StudentMistakeSubjectFacetRow>,
    val sections: List<StudentMistakeSectionFacetRow>,
    val knowledge: List<StudentMistakeKnowledgeFacetRow>,
)

internal data class ApplyStudentMistakeMigrationBundle(
    val command: ApplyStudentMistakeMigrationPageCommand,
    val problemBundles: List<CommitStudentProblemBundle>,
    val collections: List<StudentProblemCollectionEntity>,
    val initialReviewCandidates: List<StudentReviewCandidateEntity?>,
    val importSnapshots: List<StudentProblemImportSemanticSnapshotEntity>,
    val destinationRecords: List<StudentMistakeMigrationDestinationRecordEntity>,
    val checkpoint: StudentMistakeMigrationCheckpointEntity,
    val receipt: StudentMistakeMigrationReceiptEntity,
)

internal data class ApplyStudentInboxBundle(
    val message: StudentStoreInboxEntity,
    val authenticatedReceipt: StudentAuthenticatedMasteryInboxReceiptEntity,
    val completedQueueItemId: String?,
    val expectedLearnerId: String?,
    val expectedSubject: String?,
    val expectedProblemId: String?,
    val expectedRevisionId: String?,
    val learningEvidence: LearningEvidenceRef?,
    val attemptRecordedAtEpochMillis: Long?,
)
