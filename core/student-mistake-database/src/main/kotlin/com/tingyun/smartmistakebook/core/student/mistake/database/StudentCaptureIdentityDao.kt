package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy

/**
 * Save-receipt, capture handoff, canonical problem identity and occurrence primitives.
 */
@Dao
internal abstract class StudentCaptureIdentityDao : StudentProblemDocumentDao() {
    @Query(
        """
        SELECT intent_confirmation_id, intent_canonical_fingerprint, learner_id,
               problem_id, basis_revision_id, error_book_entry_id,
               confirmed_at_epoch_millis, saved_at_epoch_millis
        FROM student_mistake_save_receipt
        WHERE intent_confirmation_id = :intentConfirmationId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readSaveReceipt(
        intentConfirmationId: String,
    ): StudentMistakeSaveReceiptEntity?

    @Query(
        """
        SELECT intent_confirmation_id, intent_canonical_fingerprint, learner_id,
               problem_id, basis_revision_id, error_book_entry_id,
               confirmed_at_epoch_millis, saved_at_epoch_millis
        FROM student_mistake_save_receipt
        WHERE basis_revision_id = :basisRevisionId
           OR error_book_entry_id = :errorBookEntryId
           OR intent_confirmation_id = :targetReceiptId
           OR intent_canonical_fingerprint = :targetCanonicalFingerprint
        ORDER BY intent_confirmation_id ASC
        LIMIT 4
        """,
    )
    protected abstract suspend fun readSaveReceiptTargetCollisions(
        basisRevisionId: String,
        errorBookEntryId: String,
        targetReceiptId: String,
        targetCanonicalFingerprint: String,
    ): List<StudentMistakeSaveReceiptEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSaveReceipt(
        receipt: StudentMistakeSaveReceiptEntity,
    )

    @Query(
        """
        SELECT *
        FROM student_capture_save_handoff
        WHERE intent_id = :intentId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readCaptureSaveHandoffByIntent(
        intentId: String,
    ): StudentCaptureSaveHandoffEntity?

    @Query(
        """
        SELECT *
        FROM student_capture_save_handoff
        WHERE intent_id = :intentId
           OR draft_id = :draftId
           OR (:sessionId IS NOT NULL AND session_id = :sessionId)
        ORDER BY intent_id ASC
        LIMIT 3
        """,
    )
    protected abstract suspend fun readCaptureSaveHandoffCollisions(
        intentId: String,
        draftId: String,
        sessionId: String?,
    ): List<StudentCaptureSaveHandoffEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCaptureSaveHandoff(
        handoff: StudentCaptureSaveHandoffEntity,
    )

    @Query(
        """
        SELECT transaction_id, transaction_canonical_fingerprint,
               request_canonical_fingerprint, schema_version,
               learner_id, capture_intent_id, source_kind,
               source_canonical_fingerprint, identity_namespace,
               identity_version, identity_stable_key,
               identity_canonical_fingerprint, identity_resolution_kind,
               identity_evidence_kind,
               identity_evidence_canonical_fingerprint,
               trusted_source_proof_fingerprint,
               reviewed_alias_proof_fingerprint, target_save_receipt_id,
               target_problem_id, target_revision_id, target_revision_number,
               target_document_canonical_fingerprint, target_canonical_fingerprint,
               save_outcome, occurrence_id, occurrence_canonical_fingerprint,
               batch_canonical_fingerprint, import_source_canonical_fingerprint,
               asset_manifest_canonical_fingerprint,
               selected_region_canonical_fingerprint,
               committed_at_epoch_millis
        FROM student_capture_occurrence_transaction
        WHERE transaction_id = :transactionId
           OR request_canonical_fingerprint = :requestCanonicalFingerprint
           OR capture_intent_id = :captureIntentId
           OR occurrence_id = :occurrenceId
        ORDER BY transaction_id ASC
        LIMIT 4
        """,
    )
    protected abstract suspend fun readCaptureOccurrenceTransactionCollisions(
        transactionId: String,
        requestCanonicalFingerprint: String,
        captureIntentId: String,
        occurrenceId: String,
    ): List<StudentCaptureOccurrenceTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCaptureOccurrenceTransaction(
        transaction: StudentCaptureOccurrenceTransactionEntity,
    )

    @Query(
        """
        SELECT *
        FROM student_problem_canonical_identity
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND identity_namespace = :identityNamespace
          AND identity_version = :identityVersion
          AND stable_key = :stableKey
        LIMIT 1
        """,
    )
    protected abstract suspend fun readCanonicalProblemIdentity(
        learnerId: String,
        subject: String,
        identityNamespace: String,
        identityVersion: String,
        stableKey: String,
    ): StudentProblemCanonicalIdentityEntity?

    @Query(
        """
        SELECT *
        FROM student_problem_canonical_source_binding
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND evidence_kind = :evidenceKind
          AND evidence_canonical_fingerprint = :evidenceCanonicalFingerprint
        LIMIT 1
        """,
    )
    protected abstract suspend fun readCanonicalProblemSourceBinding(
        learnerId: String,
        subject: String,
        evidenceKind: String,
        evidenceCanonicalFingerprint: String,
    ): StudentProblemCanonicalSourceBindingEntity?

    @Query(
        """
        SELECT *
        FROM student_problem_canonical_source_binding
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND evidence_kind IN (:exactAssetEvidenceKind, :trustedSourceEvidenceKind)
          AND asset_manifest_canonical_fingerprint =
              :assetManifestCanonicalFingerprint
          AND selected_region_canonical_fingerprint =
              :selectedRegionCanonicalFingerprint
        ORDER BY created_at_epoch_millis ASC,
                 evidence_canonical_fingerprint ASC
        """,
    )
    protected abstract suspend fun readCanonicalProblemAssetBindings(
        learnerId: String,
        subject: String,
        exactAssetEvidenceKind: String,
        trustedSourceEvidenceKind: String,
        assetManifestCanonicalFingerprint: String,
        selectedRegionCanonicalFingerprint: String,
    ): List<StudentProblemCanonicalSourceBindingEntity>

    @Query(
        """
        SELECT *
        FROM student_problem_canonical_source_binding
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND evidence_kind = :reviewedAliasEvidenceKind
          AND asset_manifest_canonical_fingerprint =
              :assetManifestCanonicalFingerprint
          AND selected_region_canonical_fingerprint =
              :selectedRegionCanonicalFingerprint
          AND document_canonical_fingerprint =
              :documentCanonicalFingerprint
          AND review_case_id IS NOT NULL
          AND review_revision IS NOT NULL
          AND review_decision_canonical_fingerprint IS NOT NULL
        ORDER BY created_at_epoch_millis DESC,
                 evidence_canonical_fingerprint DESC
        """,
    )
    protected abstract suspend fun readReviewedAliasCorrectionBindings(
        learnerId: String,
        subject: String,
        reviewedAliasEvidenceKind: String,
        assetManifestCanonicalFingerprint: String,
        selectedRegionCanonicalFingerprint: String,
        documentCanonicalFingerprint: String,
    ): List<StudentProblemCanonicalSourceBindingEntity>

    @Query(
        """
        SELECT *
        FROM student_problem_revision
        WHERE problem_id = :problemId
          AND document_canonical_fingerprint = :documentCanonicalFingerprint
        ORDER BY revision_number DESC
        LIMIT 1
        """,
    )
    protected abstract suspend fun readCanonicalProblemRevisionByDocument(
        problemId: String,
        documentCanonicalFingerprint: String,
    ): StudentProblemRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCanonicalProblemIdentity(
        identity: StudentProblemCanonicalIdentityEntity,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCanonicalProblemSourceBinding(
        binding: StudentProblemCanonicalSourceBindingEntity,
    )

    @Query(
        """
        SELECT occurrence_id, idempotency_key, schema_version, learner_id, subject,
               problem_id, practice_unit_id, basis_revision_id, basis_revision_number,
               basis_document_canonical_fingerprint, batch_canonical_fingerprint,
               import_source_canonical_fingerprint, occurred_at_epoch_millis,
               imported_at_epoch_millis, attribution_status, evidence_count,
               occurrence_canonical_fingerprint
        FROM student_problem_error_occurrence
        WHERE occurrence_id = :occurrenceId
           OR (
             learner_id = :learnerId
             AND idempotency_key = :idempotencyKey
           )
           OR (
             learner_id = :learnerId
             AND batch_canonical_fingerprint = :batchCanonicalFingerprint
             AND basis_revision_id = :basisRevisionId
           )
        ORDER BY occurrence_id ASC
        LIMIT 3
        """,
    )
    protected abstract suspend fun readAtomicErrorOccurrenceCollisions(
        occurrenceId: String,
        learnerId: String,
        idempotencyKey: String,
        batchCanonicalFingerprint: String,
        basisRevisionId: String,
    ): List<StudentProblemErrorOccurrenceEntity>

    @Query(
        """
        SELECT occurrence_id, ordinal, block_id, source_asset_id, evidence_kind
        FROM student_problem_error_occurrence_evidence
        WHERE occurrence_id = :occurrenceId
        ORDER BY ordinal ASC
        """,
    )
    protected abstract suspend fun readAtomicErrorOccurrenceEvidence(
        occurrenceId: String,
    ): List<StudentProblemErrorOccurrenceEvidenceEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAtomicErrorOccurrence(
        occurrence: StudentProblemErrorOccurrenceEntity,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAtomicErrorOccurrenceEvidence(
        evidence: List<StudentProblemErrorOccurrenceEvidenceEntity>,
    )

    @Query(
        """
        SELECT *
        FROM student_capture_save_handoff
        WHERE learner_id = :learnerId
          AND acknowledged_at_epoch_millis IS NULL
          AND (
            :afterOccurredAtEpochMillis IS NULL
            OR occurred_at_epoch_millis > :afterOccurredAtEpochMillis
            OR (
              occurred_at_epoch_millis = :afterOccurredAtEpochMillis
              AND intent_id > :afterIntentId
            )
          )
        ORDER BY occurred_at_epoch_millis ASC, intent_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readPendingCaptureSaveHandoffs(
        learnerId: String,
        afterOccurredAtEpochMillis: Long?,
        afterIntentId: String?,
        limit: Int,
    ): List<StudentCaptureSaveHandoffEntity>

    @Query(
        """
        SELECT *
        FROM student_capture_save_handoff
        WHERE learner_id = :learnerId
          AND draft_id IN (:draftIds)
        ORDER BY draft_id ASC
        """,
    )
    abstract suspend fun readCaptureSaveHandoffsByDraftIds(
        learnerId: String,
        draftIds: List<String>,
    ): List<StudentCaptureSaveHandoffEntity>

    @Query(
        """
        SELECT *
        FROM student_capture_save_handoff
        WHERE learner_id = :learnerId
          AND session_id = :sessionId
        LIMIT 1
        """,
    )
    abstract suspend fun readCaptureSaveHandoffBySessionId(
        learnerId: String,
        sessionId: String,
    ): StudentCaptureSaveHandoffEntity?

    @Query(
        """
        UPDATE student_capture_save_handoff
        SET acknowledged_at_epoch_millis = :acknowledgedAtEpochMillis
        WHERE intent_id = :intentId
          AND learner_id = :learnerId
          AND source_canonical_fingerprint = :sourceCanonicalFingerprint
          AND target_canonical_fingerprint = :targetCanonicalFingerprint
          AND acknowledged_at_epoch_millis IS NULL
        """,
    )
    protected abstract suspend fun markCaptureSaveHandoffAcknowledged(
        intentId: String,
        learnerId: String,
        sourceCanonicalFingerprint: String,
        targetCanonicalFingerprint: String,
        acknowledgedAtEpochMillis: Long,
    ): Int
}
