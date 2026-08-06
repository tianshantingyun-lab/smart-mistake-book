package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Query

@Dao
internal abstract class StudentCutoverDestinationAttestationDao {
    @Query(
        """
        SELECT *
        FROM student_store_outbox
        WHERE (:afterExclusive IS NULL OR event_id > :afterExclusive)
        ORDER BY event_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readOutboxPage(
        afterExclusive: String?,
        limit: Int,
    ): List<StudentStoreOutboxEntity>

    @Query(
        """
        SELECT event_id, source_store, destination_store, aggregate_id,
               aggregate_version, payload_type, payload_version,
               payload_canonical_fingerprint, payload_wire,
               envelope_canonical_fingerprint, occurred_at_epoch_millis,
               idempotency_key, source_store_generation, apply_state,
               received_at_epoch_millis, applied_at_epoch_millis
        FROM student_store_inbox
        WHERE (:afterExclusive IS NULL OR event_id > :afterExclusive)
        ORDER BY event_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readInboxPage(
        afterExclusive: String?,
        limit: Int,
    ): List<StudentStoreInboxEntity>

    @Query(
        """
        SELECT COUNT(*)
        FROM student_problem_revision
        WHERE revision_id = :revisionId
        """,
    )
    abstract suspend fun countRevision(revisionId: String): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM student_review_transition_receipt
        WHERE outbox_event_id = :eventId
        """,
    )
    abstract suspend fun countReviewTransitionForOutbox(eventId: String): Int

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM student_store_outbox) AS outboxCount,
          (SELECT COUNT(*) FROM student_store_inbox) AS inboxCount,
          (SELECT COUNT(*) FROM student_store_outbox
             WHERE delivery_state = 'RETIRED_UNSAFE_LEGACY')
             AS retiredUnsafeLegacyOutboxCount,
          (SELECT COUNT(*) FROM student_store_outbox
             WHERE delivery_state NOT IN ('DELIVERED', 'RETIRED_UNSAFE_LEGACY')
                OR delivered_at_epoch_millis IS NULL) AS pendingOutboxCount,
          (SELECT COUNT(*) FROM student_store_outbox
             WHERE delivery_state = 'RETIRED_UNSAFE_LEGACY'
               AND (source_store != 'STUDENT_MISTAKES'
                    OR destination_store != 'LEARNER_MASTERY'
                    OR delivered_at_epoch_millis IS NULL
                    OR delivered_at_epoch_millis < occurred_at_epoch_millis
                    OR delivery_attempt_count < 0))
             AS invalidRetiredUnsafeLegacyOutboxCount,
          (SELECT COUNT(*) FROM student_store_inbox
             WHERE apply_state != 'APPLIED'
                OR applied_at_epoch_millis IS NULL) AS pendingInboxCount,
          (SELECT COUNT(*) FROM (
             SELECT 1 FROM student_store_outbox
             GROUP BY destination_store, source_store_generation, idempotency_key
             HAVING COUNT(*) != 1
           ) AS duplicate_outbox_keys) AS duplicateOutboxIdempotencyCount,
          (SELECT COUNT(*) FROM (
             SELECT 1 FROM student_store_inbox
             GROUP BY source_store, source_store_generation, idempotency_key
             HAVING COUNT(*) != 1
           ) AS duplicate_inbox_keys) AS duplicateInboxIdempotencyCount,
          (SELECT COUNT(*)
             FROM student_review_transition_receipt AS receipt
             LEFT JOIN student_store_outbox AS outbox
               ON outbox.event_id = receipt.outbox_event_id
            WHERE receipt.outbox_event_id IS NOT NULL
              AND outbox.event_id IS NULL) AS orphanReviewOutboxReceiptCount,
          (SELECT MAX(event_id) FROM student_store_outbox) AS maxOutboxEventId,
          (SELECT MAX(event_id) FROM student_store_inbox) AS maxInboxEventId,
          (SELECT MAX(delivered_at_epoch_millis)
             FROM student_store_outbox) AS maxDeliveredAtEpochMillis,
          (SELECT MAX(applied_at_epoch_millis)
             FROM student_store_inbox) AS maxAppliedAtEpochMillis
        """,
    )
    abstract suspend fun readJournalSnapshot(): StudentCutoverJournalSnapshotRow

    @Query(
        """
        SELECT problem.problem_id AS problemId,
               revision.problem_id AS revisionProblemId,
               revision.revision_id AS revisionId,
               revision.title AS title,
               revision.stem_markdown AS stemMarkdown,
               problem.primary_practice_unit_id AS primaryPracticeUnitId,
               unit.practice_unit_id AS resolvedPracticeUnitId,
               unit.title AS practiceUnitTitle,
               search.rowid AS searchRowId,
               search.source_canonical_fingerprint AS searchSourceCanonicalFingerprint,
               search.normalized_text AS searchNormalizedText,
               search.tokenized_text AS searchTokenizedText,
               fts.tokenized_text AS ftsTokenizedText
        FROM student_problem_document AS problem
        INNER JOIN student_problem_revision AS revision
          ON revision.revision_id = problem.current_revision_id
        LEFT JOIN student_practice_unit AS unit
          ON unit.practice_unit_id = problem.primary_practice_unit_id
         AND unit.problem_id = problem.problem_id
        LEFT JOIN student_problem_search_document AS search
          ON search.revision_id = revision.revision_id
        LEFT JOIN student_problem_search_fts AS fts
          ON fts.rowid = search.rowid
        WHERE (:afterExclusive IS NULL OR revision.revision_id > :afterExclusive)
        ORDER BY revision.revision_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readSearchPage(
        afterExclusive: String?,
        limit: Int,
    ): List<StudentCutoverSearchRow>

    @Query(
        """
        SELECT classification.classification_id AS stableKey,
               classification.result_canonical_fingerprint AS canonicalFingerprint,
               CASE
                 WHEN problem.problem_id IS NULL OR revision.revision_id IS NULL THEN 0
                 WHEN classification.organization_receipt_id IS NOT NULL
                  AND organization.receipt_id IS NULL THEN 0
                 WHEN classification.dimension NOT IN ('CURRICULUM_SECTION', 'KNOWLEDGE') THEN 0
                 WHEN classification.status NOT IN (
                    'CANDIDATE', 'ACCEPTED', 'REJECTED', 'SUPERSEDED', 'REVOKED'
                 ) THEN 0
                 WHEN classification.dimension = 'KNOWLEDGE'
                  AND (
                    classification.knowledge_subject IS NULL OR
                    classification.knowledge_node_id IS NULL OR
                    classification.knowledge_subject != problem.subject OR
                    classification.knowledge_node_id != classification.label_id OR
                    classification.knowledge_taxonomy_version IS NULL OR
                    classification.knowledge_pack_version IS NULL OR
                    classification.knowledge_manifest_fingerprint IS NULL OR
                    classification.knowledge_activation_generation IS NULL
                  ) THEN 0
                 WHEN classification.dimension = 'CURRICULUM_SECTION'
                  AND NOT (
                    (
                      classification.knowledge_subject IS NULL AND
                      classification.knowledge_node_id IS NULL AND
                      classification.knowledge_taxonomy_version IS NULL AND
                      classification.knowledge_pack_version IS NULL AND
                      classification.knowledge_manifest_fingerprint IS NULL AND
                      classification.knowledge_activation_generation IS NULL
                    ) OR (
                      classification.knowledge_subject IS NOT NULL AND
                      classification.knowledge_node_id IS NOT NULL AND
                      classification.knowledge_subject = problem.subject AND
                      classification.knowledge_node_id = classification.label_id AND
                      classification.knowledge_taxonomy_version IS NOT NULL AND
                      classification.knowledge_pack_version IS NOT NULL AND
                      classification.knowledge_manifest_fingerprint IS NOT NULL AND
                      classification.knowledge_activation_generation IS NOT NULL
                    )
                  ) THEN 0
                 WHEN classification.recorded_at_epoch_millis < 0
                  OR classification.supersedes_classification_id =
                     classification.classification_id
                  OR (
                    classification.status = 'REVOKED' AND
                    classification.supersedes_classification_id IS NULL
                  ) THEN 0
                 ELSE 1
               END AS consistent
        FROM student_problem_classification_result AS classification
        LEFT JOIN student_problem_document AS problem
          ON problem.problem_id = classification.problem_id
        LEFT JOIN student_problem_revision AS revision
          ON revision.revision_id = classification.basis_revision_id
         AND revision.problem_id = classification.problem_id
        LEFT JOIN student_problem_organization_receipt AS organization
          ON organization.receipt_id = classification.organization_receipt_id
         AND organization.problem_id = classification.problem_id
         AND organization.basis_revision_id = classification.basis_revision_id
         AND organization.basis_document_canonical_fingerprint =
             revision.document_canonical_fingerprint
        WHERE (:afterExclusive IS NULL
          OR classification.classification_id > :afterExclusive)
        ORDER BY classification.classification_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readClassificationPage(
        afterExclusive: String?,
        limit: Int,
    ): List<StudentCutoverIndexedReferenceRow>

    @Query(
        """
        SELECT stableKey, canonicalFingerprint, consistent
        FROM (
          SELECT '0:' || identity.identity_canonical_fingerprint AS stableKey,
                 identity.identity_canonical_fingerprint AS canonicalFingerprint,
                 CASE
                   WHEN problem.problem_id IS NULL OR revision.revision_id IS NULL
                    OR save_receipt.intent_confirmation_id IS NULL THEN 0
                   WHEN identity.issuance_kind NOT IN (
                     'FRESH_OPAQUE', 'EXACT_ASSET_SELECTION',
                     'TRUSTED_SOURCE', 'REVIEWED_ALIAS'
                   ) THEN 0
                   WHEN revision.problem_id != identity.problem_id
                    OR revision.revision_number != identity.revision_number
                    OR revision.document_canonical_fingerprint !=
                       identity.document_canonical_fingerprint
                    OR problem.learner_id != identity.learner_id
                    OR problem.subject != identity.subject
                    OR problem.primary_practice_unit_id != identity.practice_unit_id
                    OR problem.error_book_entry_id IS NOT
                       identity.error_book_entry_id
                    OR save_receipt.learner_id != identity.learner_id
                    OR save_receipt.problem_id != identity.problem_id
                    OR save_receipt.basis_revision_id != identity.revision_id
                    OR save_receipt.error_book_entry_id !=
                       identity.error_book_entry_id THEN 0
                   ELSE 1
                 END AS consistent
          FROM student_problem_canonical_identity AS identity
          LEFT JOIN student_problem_document AS problem
            ON problem.problem_id = identity.problem_id
          LEFT JOIN student_problem_revision AS revision
            ON revision.revision_id = identity.revision_id
          LEFT JOIN student_mistake_save_receipt AS save_receipt
            ON save_receipt.intent_confirmation_id =
               identity.target_save_receipt_id

          UNION ALL

          SELECT '1:' || binding.evidence_canonical_fingerprint AS stableKey,
                 binding.evidence_canonical_fingerprint AS canonicalFingerprint,
                 CASE
                   WHEN identity.identity_canonical_fingerprint IS NULL
                    OR revision.revision_id IS NULL THEN 0
                   WHEN binding.evidence_kind NOT IN (
                     'OPAQUE_CAPTURE_INTENT', 'EXACT_ASSET_SELECTION',
                     'TRUSTED_SOURCE', 'REVIEWED_ALIAS'
                   ) THEN 0
                   WHEN binding.evidence_kind = 'TRUSTED_SOURCE'
                    AND (
                      binding.trusted_source_proof_fingerprint IS NULL OR
                      binding.locator_namespace IS NULL OR
                      binding.locator_version IS NULL OR
                      binding.item_locator_canonical_fingerprint IS NULL
                    ) THEN 0
                   WHEN binding.evidence_kind = 'REVIEWED_ALIAS'
                    AND (
                      binding.reviewed_alias_proof_fingerprint IS NULL OR
                      binding.review_case_id IS NULL OR
                      binding.review_revision IS NULL OR
                      binding.review_decision_canonical_fingerprint IS NULL
                    ) THEN 0
                   WHEN binding.document_canonical_fingerprint !=
                       revision.document_canonical_fingerprint
                    OR revision.revision_id != identity.revision_id
                    OR revision.problem_id != identity.problem_id THEN 0
                   ELSE 1
                 END AS consistent
          FROM student_problem_canonical_source_binding AS binding
          LEFT JOIN student_problem_canonical_identity AS identity
            ON identity.learner_id = binding.learner_id
           AND identity.subject = binding.subject
           AND identity.identity_namespace = binding.identity_namespace
           AND identity.identity_version = binding.identity_version
           AND identity.stable_key = binding.identity_stable_key
          LEFT JOIN student_problem_revision AS revision
            ON revision.revision_id = binding.bound_revision_id

          UNION ALL

          SELECT '2:' || receipt.receipt_id AS stableKey,
                 receipt.receipt_canonical_fingerprint AS canonicalFingerprint,
                 CASE
                   WHEN problem.problem_id IS NULL OR revision.revision_id IS NULL THEN 0
                   WHEN receipt.receipt_kind NOT IN (
                     'EXACT_ASSET_SELECTION', 'TRUSTED_SOURCE', 'REVIEWED_ALIAS'
                   ) THEN 0
                   WHEN receipt.fingerprint_version NOT IN (1, 2)
                    OR receipt.renewal_generation < 0
                    OR receipt.issued_at_epoch_millis < 0
                    OR receipt.expires_at_epoch_millis <
                       receipt.issued_at_epoch_millis THEN 0
                   WHEN problem.learner_id != receipt.learner_id
                    OR problem.subject != receipt.subject
                    OR problem.primary_practice_unit_id != receipt.practice_unit_id
                    OR revision.problem_id != receipt.problem_id
                    OR revision.revision_number != receipt.revision_number
                    OR revision.document_canonical_fingerprint !=
                       receipt.document_canonical_fingerprint THEN 0
                   WHEN receipt.existing_identity_stable_key IS NULL
                    AND (
                      receipt.existing_identity_namespace IS NOT NULL OR
                      receipt.existing_identity_version IS NOT NULL OR
                      receipt.existing_identity_canonical_fingerprint IS NOT NULL
                    ) THEN 0
                   WHEN receipt.existing_identity_stable_key IS NOT NULL
                    AND (
                      receipt.existing_identity_namespace IS NULL OR
                      receipt.existing_identity_version IS NULL OR
                      receipt.existing_identity_canonical_fingerprint IS NULL OR
                      identity.identity_canonical_fingerprint IS NULL OR
                      identity.identity_canonical_fingerprint !=
                        receipt.existing_identity_canonical_fingerprint
                    ) THEN 0
                   ELSE 1
                 END AS consistent
          FROM student_problem_identity_receipt AS receipt
          LEFT JOIN student_problem_document AS problem
            ON problem.problem_id = receipt.problem_id
          LEFT JOIN student_problem_revision AS revision
            ON revision.revision_id = receipt.revision_id
          LEFT JOIN student_problem_canonical_identity AS identity
            ON identity.learner_id = receipt.learner_id
           AND identity.subject = receipt.subject
           AND identity.identity_namespace =
               receipt.existing_identity_namespace
           AND identity.identity_version = receipt.existing_identity_version
           AND identity.stable_key = receipt.existing_identity_stable_key
        ) AS identity_rows
        WHERE (:afterExclusive IS NULL OR stableKey > :afterExclusive)
        ORDER BY stableKey ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readIdentityPage(
        afterExclusive: String?,
        limit: Int,
    ): List<StudentCutoverIndexedReferenceRow>

    @Query(
        """
        SELECT
          (SELECT state FROM student_problem_search_index_state
             WHERE index_key = 'library-search-v1'
             LIMIT 1) AS searchState,
          (SELECT indexed_document_count
             FROM student_problem_search_index_state
             WHERE index_key = 'library-search-v1'
             LIMIT 1) AS searchIndexedDocumentCount,
          (SELECT COUNT(*)
             FROM student_problem_document AS problem
             INNER JOIN student_problem_revision AS revision
               ON revision.revision_id = problem.current_revision_id) AS currentRevisionCount,
          (SELECT COUNT(*) FROM student_problem_search_document) AS searchDocumentCount,
          (SELECT COUNT(*) FROM student_problem_search_fts) AS searchFtsCount,
          (SELECT COUNT(*) FROM student_problem_classification_result)
             AS classificationCount,
          (SELECT COUNT(*) FROM student_problem_canonical_identity)
             AS canonicalIdentityCount,
          (SELECT COUNT(*) FROM student_problem_canonical_source_binding)
             AS sourceBindingCount,
          (SELECT COUNT(*) FROM student_problem_identity_receipt)
             AS identityReceiptCount,
          (SELECT COALESCE(MAX(change_version), 0)
             FROM student_learner_change) AS maxLearnerChangeVersion,
          (SELECT COALESCE(SUM(change_version), 0)
             FROM student_learner_change) AS sumLearnerChangeVersion,
          (SELECT MAX(updated_at_epoch_millis)
             FROM student_problem_search_index_state
             WHERE index_key = 'library-search-v1')
             AS searchUpdatedAtEpochMillis,
          (SELECT COUNT(*) FROM student_cutover_fence) AS cutoverFenceCount,
          (SELECT COUNT(*) FROM student_cutover_completion_receipt)
             AS completionReceiptCount
        """,
    )
    abstract suspend fun readIndexSnapshot(): StudentCutoverIndexSnapshotRow
}

internal data class StudentCutoverJournalSnapshotRow(
    val outboxCount: Long,
    val inboxCount: Long,
    val retiredUnsafeLegacyOutboxCount: Long,
    val pendingOutboxCount: Long,
    val invalidRetiredUnsafeLegacyOutboxCount: Long,
    val pendingInboxCount: Long,
    val duplicateOutboxIdempotencyCount: Long,
    val duplicateInboxIdempotencyCount: Long,
    val orphanReviewOutboxReceiptCount: Long,
    val maxOutboxEventId: String?,
    val maxInboxEventId: String?,
    val maxDeliveredAtEpochMillis: Long?,
    val maxAppliedAtEpochMillis: Long?,
)

internal data class StudentCutoverSearchRow(
    val problemId: String,
    val revisionProblemId: String,
    val revisionId: String,
    val title: String?,
    val stemMarkdown: String,
    val primaryPracticeUnitId: String,
    val resolvedPracticeUnitId: String?,
    val practiceUnitTitle: String?,
    val searchRowId: Long?,
    val searchSourceCanonicalFingerprint: String?,
    val searchNormalizedText: String?,
    val searchTokenizedText: String?,
    val ftsTokenizedText: String?,
)

internal data class StudentCutoverIndexedReferenceRow(
    val stableKey: String,
    val canonicalFingerprint: String,
    val consistent: Boolean,
)

internal data class StudentCutoverIndexSnapshotRow(
    val searchState: String?,
    val searchIndexedDocumentCount: Long?,
    val currentRevisionCount: Long,
    val searchDocumentCount: Long,
    val searchFtsCount: Long,
    val classificationCount: Long,
    val canonicalIdentityCount: Long,
    val sourceBindingCount: Long,
    val identityReceiptCount: Long,
    val maxLearnerChangeVersion: Long,
    val sumLearnerChangeVersion: Long,
    val searchUpdatedAtEpochMillis: Long?,
    val cutoverFenceCount: Long,
    val completionReceiptCount: Long,
)
