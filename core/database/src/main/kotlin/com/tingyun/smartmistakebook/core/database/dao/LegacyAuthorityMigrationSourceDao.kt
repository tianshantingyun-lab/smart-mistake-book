package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query

internal data class LegacyStudentDocumentMigrationRow(
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "problem_canonical_fingerprint")
    val problemCanonicalFingerprint: String,
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    val subject: String,
    @ColumnInfo(name = "problem_created_at_epoch_millis")
    val problemCreatedAtEpochMillis: Long,
    @ColumnInfo(name = "problem_archived_at_epoch_millis")
    val problemArchivedAtEpochMillis: Long?,
    val title: String,
    @ColumnInfo(name = "problem_markdown")
    val problemMarkdown: String,
    @ColumnInfo(name = "question_document_snapshot")
    val questionDocumentSnapshot: String?,
    @ColumnInfo(name = "answer_spec_id")
    val answerSpecId: String?,
    @ColumnInfo(name = "answer_spec_snapshot")
    val answerSpecSnapshot: String?,
    @ColumnInfo(name = "answer_verification_status")
    val answerVerificationStatus: String,
    @ColumnInfo(name = "revision_source_type")
    val revisionSourceType: String,
    @ColumnInfo(name = "revision_source_reference")
    val revisionSourceReference: String?,
    @ColumnInfo(name = "content_fingerprint")
    val contentFingerprint: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "practice_unit_key")
    val practiceUnitKey: String,
    @ColumnInfo(name = "practice_unit_kind")
    val practiceUnitKind: String,
    @ColumnInfo(name = "practice_unit_title")
    val practiceUnitTitle: String,
    @ColumnInfo(name = "practice_unit_prompt_markdown")
    val practiceUnitPromptMarkdown: String,
    @ColumnInfo(name = "estimated_seconds")
    val estimatedSeconds: Int,
    @ColumnInfo(name = "practice_unit_revision_id")
    val practiceUnitRevisionId: String,
    @ColumnInfo(name = "practice_unit_created_at_epoch_millis")
    val practiceUnitCreatedAtEpochMillis: Long,
    @ColumnInfo(name = "entry_current_revision_id")
    val entryCurrentRevisionId: String,
    @ColumnInfo(name = "source_key")
    val sourceKey: String?,
    val status: String,
    @ColumnInfo(name = "accepted_at_epoch_millis")
    val acceptedAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "revision_created_at_epoch_millis")
    val revisionCreatedAtEpochMillis: Long,
    @ColumnInfo(name = "committed_at_epoch_millis")
    val committedAtEpochMillis: Long,
)

/**
 * Minimal durable identity needed to claim one legacy capture commit for cross-store replay.
 *
 * Every field comes from receipt-bound legacy rows. The learner is intentionally absent because
 * the legacy schema never stored one; callers must supply the explicit local-learner claim.
 */
internal data class LegacyCaptureStudentSaveClaimRow(
    val subject: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    @ColumnInfo(name = "content_fingerprint")
    val contentFingerprint: String,
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "committed_at_epoch_millis")
    val committedAtEpochMillis: Long,
)

internal data class LegacyStudentDocumentAssetRow(
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "page_index")
    val pageIndex: Int?,
    val role: String,
    @ColumnInfo(name = "linked_source_asset_id")
    val linkedSourceAssetId: String,
    @ColumnInfo(name = "canonical_source_asset_id")
    val canonicalSourceAssetId: String?,
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String?,
    @ColumnInfo(name = "relative_path")
    val relativePath: String?,
    @ColumnInfo(name = "mime_type")
    val mimeType: String?,
    @ColumnInfo(name = "byte_size")
    val byteSize: Long?,
    val width: Int?,
    val height: Int?,
    @ColumnInfo(name = "source_type")
    val sourceType: String?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long?,
)

internal data class LegacyStudentDocumentAssetBudgetRow(
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "source_asset_link_count")
    val sourceAssetLinkCount: Long,
    @ColumnInfo(name = "broken_source_asset_link_count")
    val brokenSourceAssetLinkCount: Long,
    @ColumnInfo(name = "source_asset_byte_count")
    val sourceAssetByteCount: Long,
)

/**
 * Asset row used only by the exact capture replay.
 *
 * Canonical columns stay nullable so a damaged legacy foreign-key edge fails closed in Kotlin
 * instead of being silently dropped by an inner join. Page index is present only when the asset
 * belongs to the replayed draft; an earlier repeated capture keeps a null page while remaining
 * part of the canonical revision.
 */
internal data class ExactLegacyStudentDocumentAssetRow(
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "page_index")
    val pageIndex: Int?,
    val role: String,
    @ColumnInfo(name = "linked_source_asset_id")
    val linkedSourceAssetId: String,
    @ColumnInfo(name = "canonical_source_asset_id")
    val canonicalSourceAssetId: String?,
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String?,
    @ColumnInfo(name = "relative_path")
    val relativePath: String?,
    @ColumnInfo(name = "mime_type")
    val mimeType: String?,
    @ColumnInfo(name = "byte_size")
    val byteSize: Long?,
    val width: Int?,
    val height: Int?,
    @ColumnInfo(name = "source_type")
    val sourceType: String?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long?,
)

internal data class ExactLegacyDraftSourceAssetRow(
    @ColumnInfo(name = "page_index")
    val pageIndex: Int,
    @ColumnInfo(name = "source_asset_id")
    val sourceAssetId: String,
)

internal data class LegacyMasteryFactMigrationRow(
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val source: String,
    @ColumnInfo(name = "fact_kind")
    val factKind: String,
    @ColumnInfo(name = "anchor_id")
    val anchorId: String,
    val subject: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String?,
    @ColumnInfo(name = "conversation_generation")
    val conversationGeneration: Long?,
    @ColumnInfo(name = "turn_receipt_id")
    val turnReceiptId: String?,
    @ColumnInfo(name = "evidence_request_id")
    val evidenceRequestId: String?,
    @ColumnInfo(name = "response_fingerprint")
    val responseFingerprint: String,
    @ColumnInfo(name = "response_summary")
    val responseSummary: String,
    @ColumnInfo(name = "source_payload_fingerprint")
    val sourcePayloadFingerprint: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "source_version")
    val sourceVersion: String,
    @ColumnInfo(name = "source_proof_fingerprint")
    val sourceProofFingerprint: String?,
    @ColumnInfo(name = "source_reference_id")
    val sourceReferenceId: String?,
    @ColumnInfo(name = "target_kind")
    val targetKind: String?,
    @ColumnInfo(name = "target_database")
    val targetDatabase: String?,
    @ColumnInfo(name = "target_id")
    val targetId: String?,
    @ColumnInfo(name = "target_version")
    val targetVersion: String?,
    @ColumnInfo(name = "target_fingerprint")
    val targetFingerprint: String?,
    @ColumnInfo(name = "attested_at_epoch_millis")
    val attestedAtEpochMillis: Long?,
)

internal data class LegacyAuthorityMigrationStatsRow(
    @ColumnInfo(name = "student_document_revision_count")
    val studentDocumentRevisionCount: Long,
    @ColumnInfo(name = "student_source_asset_link_count")
    val studentSourceAssetLinkCount: Long,
    @ColumnInfo(name = "student_broken_source_asset_link_count")
    val studentBrokenSourceAssetLinkCount: Long,
    @ColumnInfo(name = "student_source_asset_byte_count")
    val studentSourceAssetByteCount: Long,
    @ColumnInfo(name = "student_problem_with_multiple_entries_count")
    val studentProblemWithMultipleEntriesCount: Long,
    @ColumnInfo(name = "student_problem_current_revision_not_latest_count")
    val studentProblemCurrentRevisionNotLatestCount: Long,
    @ColumnInfo(name = "mastery_source_fact_count")
    val masterySourceFactCount: Long,
    @ColumnInfo(name = "mastery_proven_source_fact_count")
    val masteryProvenSourceFactCount: Long,
    @ColumnInfo(name = "latest_student_mutation_at_epoch_millis")
    val latestStudentMutationAtEpochMillis: Long,
    @ColumnInfo(name = "latest_mastery_fact_at_epoch_millis")
    val latestMasteryFactAtEpochMillis: Long,
)

@Dao
internal interface LegacyAuthorityMigrationSourceDao {
    @Query(
        """
        SELECT
            problem.subject AS subject,
            receipt.problem_id AS problem_id,
            receipt.practice_unit_id AS practice_unit_id,
            receipt.problem_revision_id AS revision_id,
            revision.revision_number AS revision_number,
            revision.content_fingerprint AS content_fingerprint,
            receipt.error_book_entry_id AS entry_id,
            receipt.committed_at_epoch_millis AS committed_at_epoch_millis
        FROM problem_draft_commit_receipt AS receipt
        JOIN problem_draft AS draft
          ON draft.draft_id = receipt.draft_id
         AND draft.current_revision_number = receipt.draft_revision_number
         AND draft.status = 'COMMITTED'
        JOIN problem_draft_revision AS draft_revision
          ON draft_revision.draft_id = receipt.draft_id
         AND draft_revision.revision_number = receipt.draft_revision_number
        JOIN problem AS problem
          ON problem.problem_id = receipt.problem_id
         AND problem.subject = draft_revision.subject
        JOIN problem_revision AS revision
          ON revision.revision_id = receipt.problem_revision_id
         AND revision.problem_id = receipt.problem_id
        JOIN practice_unit AS unit
          ON unit.practice_unit_id = receipt.practice_unit_id
         AND unit.problem_id = receipt.problem_id
         AND unit.problem_revision_id = receipt.problem_revision_id
        JOIN error_book_entry AS entry
          ON entry.entry_id = receipt.error_book_entry_id
         AND entry.practice_unit_id = receipt.practice_unit_id
         AND entry.problem_id = receipt.problem_id
         AND entry.current_revision_id = receipt.problem_revision_id
        LEFT JOIN tutor_session AS tutor
          ON tutor.draft_id = receipt.draft_id
        WHERE
            :learnerId = 'learner:local'
            AND receipt.command_id = :intentId
            AND receipt.payload_fingerprint = :intentCanonicalFingerprint
            AND receipt.draft_id = :draftId
            AND receipt.draft_revision_number = :draftRevisionNumber
            AND receipt.problem_id = :problemId
            AND receipt.problem_revision_id = :problemRevisionId
            AND receipt.practice_unit_id = :practiceUnitId
            AND receipt.error_book_entry_id = :errorBookEntryId
            AND (
                (
                    :tutorSessionId IS NULL
                    AND draft.origin = 'LIBRARY'
                    AND tutor.session_id IS NULL
                )
                OR (
                    draft.origin = 'TUTOR'
                    AND tutor.session_id = :tutorSessionId
                    AND tutor.draft_revision_number = :draftRevisionNumber
                )
            )
        LIMIT 1
        """,
    )
    suspend fun readClaimableCaptureStudentSave(
        learnerId: String,
        intentId: String,
        intentCanonicalFingerprint: String,
        draftId: String,
        draftRevisionNumber: Int,
        tutorSessionId: String?,
        problemId: String,
        problemRevisionId: String,
        practiceUnitId: String,
        errorBookEntryId: String,
    ): LegacyCaptureStudentSaveClaimRow?

    @Query(
        """
        SELECT
            entry.entry_id AS entry_id,
            receipt.problem_id AS problem_id,
            problem.canonical_fingerprint AS problem_canonical_fingerprint,
            receipt.problem_revision_id AS revision_id,
            revision.revision_number AS revision_number,
            problem.subject AS subject,
            problem.created_at_epoch_millis AS problem_created_at_epoch_millis,
            problem.archived_at_epoch_millis AS problem_archived_at_epoch_millis,
            revision.title AS title,
            revision.problem_markdown AS problem_markdown,
            revision.question_document_snapshot AS question_document_snapshot,
            revision.answer_spec_id AS answer_spec_id,
            revision.answer_spec_snapshot AS answer_spec_snapshot,
            revision.answer_verification_status AS answer_verification_status,
            revision.source_type AS revision_source_type,
            revision.source_reference AS revision_source_reference,
            revision.content_fingerprint AS content_fingerprint,
            receipt.practice_unit_id AS practice_unit_id,
            unit.unit_key AS practice_unit_key,
            unit.unit_kind AS practice_unit_kind,
            unit.title AS practice_unit_title,
            unit.prompt_markdown AS practice_unit_prompt_markdown,
            unit.estimated_seconds AS estimated_seconds,
            unit.problem_revision_id AS practice_unit_revision_id,
            unit.created_at_epoch_millis AS practice_unit_created_at_epoch_millis,
            entry.current_revision_id AS entry_current_revision_id,
            entry.source_key AS source_key,
            entry.status AS status,
            entry.accepted_at_epoch_millis AS accepted_at_epoch_millis,
            entry.updated_at_epoch_millis AS updated_at_epoch_millis,
            revision.created_at_epoch_millis AS revision_created_at_epoch_millis,
            CASE
                WHEN revision.created_at_epoch_millis > entry.accepted_at_epoch_millis
                THEN revision.created_at_epoch_millis
                ELSE entry.accepted_at_epoch_millis
            END AS committed_at_epoch_millis
        FROM problem_draft_commit_receipt AS receipt
        JOIN problem_draft AS draft
          ON draft.draft_id = receipt.draft_id
         AND draft.current_revision_number = receipt.draft_revision_number
         AND draft.status = 'COMMITTED'
        JOIN problem_draft_revision AS draft_revision
          ON draft_revision.draft_id = receipt.draft_id
         AND draft_revision.revision_number = receipt.draft_revision_number
        JOIN problem_draft_source_asset AS primary_draft_asset
          ON primary_draft_asset.draft_id = receipt.draft_id
         AND primary_draft_asset.page_index = 0
         AND primary_draft_asset.source_asset_id = draft.source_asset_id
        JOIN problem AS problem
          ON problem.problem_id = receipt.problem_id
         AND problem.subject = draft_revision.subject
        JOIN problem_revision AS revision
          ON revision.revision_id = receipt.problem_revision_id
         AND revision.problem_id = receipt.problem_id
        JOIN practice_unit AS unit
          ON unit.practice_unit_id = receipt.practice_unit_id
         AND unit.problem_id = receipt.problem_id
         AND unit.problem_revision_id = receipt.problem_revision_id
        JOIN error_book_entry AS entry
          ON entry.entry_id = receipt.error_book_entry_id
         AND entry.practice_unit_id = receipt.practice_unit_id
         AND entry.problem_id = receipt.problem_id
         AND entry.current_revision_id = receipt.problem_revision_id
        JOIN capture_student_save_handoff AS handoff
          ON handoff.intent_id = receipt.command_id
         AND handoff.intent_canonical_fingerprint = receipt.payload_fingerprint
         AND handoff.learner_id = :learnerId
         AND handoff.draft_id = receipt.draft_id
         AND handoff.draft_revision_number = receipt.draft_revision_number
         AND handoff.target_subject = problem.subject
         AND handoff.target_problem_id = receipt.problem_id
         AND handoff.target_practice_unit_id = receipt.practice_unit_id
         AND handoff.target_problem_ref_schema_version = 1
         AND handoff.target_revision_id = receipt.problem_revision_id
         AND handoff.target_revision_number = revision.revision_number
         AND handoff.target_document_canonical_fingerprint = revision.content_fingerprint
         AND handoff.target_revision_ref_schema_version = 1
         AND handoff.prepared_at_epoch_millis = receipt.committed_at_epoch_millis
         AND handoff.schema_version = 1
         AND (
             (
                 handoff.state = 'PREPARED'
                 AND handoff.state_version = 1
                 AND handoff.finalized_at_epoch_millis IS NULL
                 AND handoff.target_save_receipt_fingerprint IS NULL
             )
             OR (
                 handoff.state = 'FINALIZED'
                 AND handoff.state_version = 2
                 AND handoff.finalized_at_epoch_millis >= handoff.prepared_at_epoch_millis
                 AND length(handoff.target_save_receipt_fingerprint) = 64
                 AND handoff.target_save_receipt_fingerprint
                     NOT GLOB '*[^0-9a-f]*'
             )
         )
        LEFT JOIN tutor_session AS tutor
          ON tutor.draft_id = receipt.draft_id
        WHERE
            :learnerId = 'learner:local'
            AND receipt.command_id = :intentId
            AND receipt.payload_fingerprint = :intentCanonicalFingerprint
            AND receipt.draft_id = :draftId
            AND receipt.draft_revision_number = :draftRevisionNumber
            AND (
                (
                    :tutorSessionId IS NULL
                    AND draft.origin = 'LIBRARY'
                    AND tutor.session_id IS NULL
                    AND handoff.session_id IS NULL
                )
                OR (
                    draft.origin = 'TUTOR'
                    AND tutor.session_id = :tutorSessionId
                    AND tutor.draft_revision_number = :draftRevisionNumber
                    AND handoff.session_id = :tutorSessionId
                )
            )
            AND receipt.problem_id = :problemId
            AND receipt.problem_revision_id = :problemRevisionId
            AND receipt.practice_unit_id = :practiceUnitId
            AND receipt.error_book_entry_id = :errorBookEntryId
        LIMIT 2
        """,
    )
    suspend fun readExactReceiptCaptureStudentDocument(
        learnerId: String,
        intentId: String,
        intentCanonicalFingerprint: String,
        draftId: String,
        draftRevisionNumber: Int,
        tutorSessionId: String?,
        problemId: String,
        problemRevisionId: String,
        practiceUnitId: String,
        errorBookEntryId: String,
    ): List<LegacyStudentDocumentMigrationRow>

    @Query(
        """
        SELECT
            entry.entry_id AS entry_id,
            receipt.problem_id AS problem_id,
            problem.canonical_fingerprint AS problem_canonical_fingerprint,
            receipt.problem_revision_id AS revision_id,
            revision.revision_number AS revision_number,
            problem.subject AS subject,
            problem.created_at_epoch_millis AS problem_created_at_epoch_millis,
            problem.archived_at_epoch_millis AS problem_archived_at_epoch_millis,
            revision.title AS title,
            revision.problem_markdown AS problem_markdown,
            revision.question_document_snapshot AS question_document_snapshot,
            revision.answer_spec_id AS answer_spec_id,
            revision.answer_spec_snapshot AS answer_spec_snapshot,
            revision.answer_verification_status AS answer_verification_status,
            revision.source_type AS revision_source_type,
            revision.source_reference AS revision_source_reference,
            revision.content_fingerprint AS content_fingerprint,
            receipt.practice_unit_id AS practice_unit_id,
            unit.unit_key AS practice_unit_key,
            unit.unit_kind AS practice_unit_kind,
            unit.title AS practice_unit_title,
            unit.prompt_markdown AS practice_unit_prompt_markdown,
            unit.estimated_seconds AS estimated_seconds,
            unit.problem_revision_id AS practice_unit_revision_id,
            unit.created_at_epoch_millis AS practice_unit_created_at_epoch_millis,
            entry.current_revision_id AS entry_current_revision_id,
            entry.source_key AS source_key,
            entry.status AS status,
            entry.accepted_at_epoch_millis AS accepted_at_epoch_millis,
            entry.updated_at_epoch_millis AS updated_at_epoch_millis,
            revision.created_at_epoch_millis AS revision_created_at_epoch_millis,
            CASE
                WHEN revision.created_at_epoch_millis > entry.accepted_at_epoch_millis
                THEN revision.created_at_epoch_millis
                ELSE entry.accepted_at_epoch_millis
            END AS committed_at_epoch_millis
        FROM capture_student_save_handoff AS handoff
        JOIN problem_draft_commit_receipt AS receipt
          ON receipt.draft_id = handoff.draft_id
         AND receipt.command_id = handoff.intent_id
         AND receipt.payload_fingerprint = handoff.intent_canonical_fingerprint
         AND receipt.draft_revision_number = handoff.draft_revision_number
         AND receipt.problem_id = handoff.target_problem_id
         AND receipt.problem_revision_id = handoff.target_revision_id
         AND receipt.practice_unit_id = handoff.target_practice_unit_id
        JOIN problem_draft AS draft
          ON draft.draft_id = receipt.draft_id
         AND draft.current_revision_number = receipt.draft_revision_number
         AND draft.status = 'COMMITTED'
        JOIN problem_draft_revision AS draft_revision
          ON draft_revision.draft_id = receipt.draft_id
         AND draft_revision.revision_number = receipt.draft_revision_number
        JOIN problem_draft_source_asset AS primary_draft_asset
          ON primary_draft_asset.draft_id = receipt.draft_id
         AND primary_draft_asset.page_index = 0
         AND primary_draft_asset.source_asset_id = draft.source_asset_id
        JOIN problem AS problem
          ON problem.problem_id = receipt.problem_id
         AND problem.subject = draft_revision.subject
         AND problem.subject = handoff.target_subject
        JOIN problem_revision AS revision
          ON revision.revision_id = receipt.problem_revision_id
         AND revision.problem_id = receipt.problem_id
         AND revision.revision_number = handoff.target_revision_number
         AND revision.content_fingerprint =
             handoff.target_document_canonical_fingerprint
        JOIN practice_unit AS unit
          ON unit.practice_unit_id = receipt.practice_unit_id
         AND unit.problem_id = receipt.problem_id
         AND unit.problem_revision_id = receipt.problem_revision_id
        JOIN error_book_entry AS entry
          ON entry.entry_id = receipt.error_book_entry_id
         AND entry.practice_unit_id = receipt.practice_unit_id
         AND entry.problem_id = receipt.problem_id
         AND entry.current_revision_id = receipt.problem_revision_id
        LEFT JOIN tutor_session AS tutor
          ON tutor.draft_id = receipt.draft_id
        WHERE
            :learnerId = 'learner:local'
            AND handoff.intent_id = :intentId
            AND handoff.intent_canonical_fingerprint = :intentCanonicalFingerprint
            AND handoff.learner_id = :learnerId
            AND handoff.draft_id = :draftId
            AND handoff.draft_revision_number = :draftRevisionNumber
            AND handoff.target_subject = :subject
            AND handoff.target_problem_id = :problemId
            AND handoff.target_practice_unit_id = :practiceUnitId
            AND handoff.target_problem_ref_schema_version = 1
            AND handoff.target_revision_id = :problemRevisionId
            AND handoff.target_revision_number = :problemRevisionNumber
            AND handoff.target_document_canonical_fingerprint =
                :documentCanonicalFingerprint
            AND handoff.target_revision_ref_schema_version = 1
            AND handoff.prepared_at_epoch_millis = receipt.committed_at_epoch_millis
            AND handoff.schema_version = 1
            AND (
                (
                    handoff.state = 'PREPARED'
                    AND handoff.state_version = 1
                    AND handoff.finalized_at_epoch_millis IS NULL
                    AND handoff.target_save_receipt_fingerprint IS NULL
                )
                OR (
                    handoff.state = 'FINALIZED'
                    AND handoff.state_version = 2
                    AND handoff.finalized_at_epoch_millis >=
                        handoff.prepared_at_epoch_millis
                    AND length(handoff.target_save_receipt_fingerprint) = 64
                    AND handoff.target_save_receipt_fingerprint
                        NOT GLOB '*[^0-9a-f]*'
                )
            )
            AND (
                (
                    :tutorSessionId IS NULL
                    AND draft.origin = 'LIBRARY'
                    AND tutor.session_id IS NULL
                    AND handoff.session_id IS NULL
                )
                OR (
                    draft.origin = 'TUTOR'
                    AND tutor.session_id = :tutorSessionId
                    AND tutor.draft_revision_number = :draftRevisionNumber
                    AND handoff.session_id = :tutorSessionId
                )
            )
        LIMIT 2
        """,
    )
    suspend fun readExactPreparedHandoffCaptureStudentDocument(
        learnerId: String,
        intentId: String,
        intentCanonicalFingerprint: String,
        draftId: String,
        draftRevisionNumber: Int,
        tutorSessionId: String?,
        subject: String,
        problemId: String,
        problemRevisionId: String,
        problemRevisionNumber: Int,
        practiceUnitId: String,
        documentCanonicalFingerprint: String,
    ): List<LegacyStudentDocumentMigrationRow>

    @Query(
        """
        SELECT
            entry.entry_id AS entry_id,
            entry.problem_id AS problem_id,
            problem.canonical_fingerprint AS problem_canonical_fingerprint,
            revision.revision_id AS revision_id,
            revision.revision_number AS revision_number,
            problem.subject AS subject,
            problem.created_at_epoch_millis AS problem_created_at_epoch_millis,
            problem.archived_at_epoch_millis AS problem_archived_at_epoch_millis,
            revision.title AS title,
            revision.problem_markdown AS problem_markdown,
            revision.question_document_snapshot AS question_document_snapshot,
            revision.answer_spec_id AS answer_spec_id,
            revision.answer_spec_snapshot AS answer_spec_snapshot,
            revision.answer_verification_status AS answer_verification_status,
            revision.source_type AS revision_source_type,
            revision.source_reference AS revision_source_reference,
            revision.content_fingerprint AS content_fingerprint,
            unit.practice_unit_id AS practice_unit_id,
            unit.unit_key AS practice_unit_key,
            unit.unit_kind AS practice_unit_kind,
            unit.title AS practice_unit_title,
            unit.prompt_markdown AS practice_unit_prompt_markdown,
            unit.estimated_seconds AS estimated_seconds,
            unit.problem_revision_id AS practice_unit_revision_id,
            unit.created_at_epoch_millis AS practice_unit_created_at_epoch_millis,
            entry.current_revision_id AS entry_current_revision_id,
            entry.source_key AS source_key,
            entry.status AS status,
            entry.accepted_at_epoch_millis AS accepted_at_epoch_millis,
            entry.updated_at_epoch_millis AS updated_at_epoch_millis,
            revision.created_at_epoch_millis AS revision_created_at_epoch_millis,
            CASE
                WHEN revision.created_at_epoch_millis > entry.accepted_at_epoch_millis
                THEN revision.created_at_epoch_millis
                ELSE entry.accepted_at_epoch_millis
            END AS committed_at_epoch_millis
        FROM error_book_entry AS entry
        JOIN problem AS problem
          ON problem.problem_id = entry.problem_id
        JOIN problem_revision AS revision
          ON revision.revision_id = entry.current_revision_id
         AND revision.problem_id = entry.problem_id
        JOIN practice_unit AS unit
          ON unit.practice_unit_id = entry.practice_unit_id
         AND unit.problem_id = entry.problem_id
        WHERE
            :afterCommittedAtEpochMillis IS NULL
            OR (
                CASE
                    WHEN revision.created_at_epoch_millis > entry.accepted_at_epoch_millis
                    THEN revision.created_at_epoch_millis
                    ELSE entry.accepted_at_epoch_millis
                END
            ) > :afterCommittedAtEpochMillis
            OR (
                (
                    CASE
                        WHEN revision.created_at_epoch_millis > entry.accepted_at_epoch_millis
                        THEN revision.created_at_epoch_millis
                        ELSE entry.accepted_at_epoch_millis
                    END
                ) = :afterCommittedAtEpochMillis
                AND entry.problem_id > :afterProblemId
            )
            OR (
                (
                    CASE
                        WHEN revision.created_at_epoch_millis > entry.accepted_at_epoch_millis
                        THEN revision.created_at_epoch_millis
                        ELSE entry.accepted_at_epoch_millis
                    END
                ) = :afterCommittedAtEpochMillis
                AND entry.problem_id = :afterProblemId
                AND revision.revision_number > :afterRevisionNumber
            )
            OR (
                (
                    CASE
                        WHEN revision.created_at_epoch_millis > entry.accepted_at_epoch_millis
                        THEN revision.created_at_epoch_millis
                        ELSE entry.accepted_at_epoch_millis
                    END
                ) = :afterCommittedAtEpochMillis
                AND entry.problem_id = :afterProblemId
                AND revision.revision_number = :afterRevisionNumber
                AND revision.revision_id > :afterRevisionId
            )
        ORDER BY
            committed_at_epoch_millis ASC,
            entry.problem_id ASC,
            revision.revision_number ASC,
            revision.revision_id ASC
        LIMIT :limit
        """,
    )
    suspend fun readStudentDocumentPage(
        afterCommittedAtEpochMillis: Long?,
        afterProblemId: String?,
        afterRevisionNumber: Int?,
        afterRevisionId: String?,
        limit: Int,
    ): List<LegacyStudentDocumentMigrationRow>

    @Query(
        """
        SELECT
            link.problem_revision_id AS problem_revision_id,
            draft_asset.page_index AS page_index,
            link.role AS role,
            link.source_asset_id AS linked_source_asset_id,
            asset.source_asset_id AS canonical_source_asset_id,
            asset.content_sha256 AS content_sha256,
            asset.relative_path AS relative_path,
            asset.mime_type AS mime_type,
            asset.byte_size AS byte_size,
            asset.width AS width,
            asset.height AS height,
            asset.source_type AS source_type,
            asset.created_at_epoch_millis AS created_at_epoch_millis
        FROM problem_revision_source_asset AS link
        LEFT JOIN problem_draft_source_asset AS draft_asset
          ON draft_asset.draft_id = :draftId
         AND draft_asset.source_asset_id = link.source_asset_id
        LEFT JOIN canonical_source_asset AS asset
          ON asset.source_asset_id = link.source_asset_id
        WHERE link.problem_revision_id = :revisionId
        ORDER BY
            CASE WHEN draft_asset.page_index IS NULL THEN 1 ELSE 0 END ASC,
            draft_asset.page_index ASC,
            asset.created_at_epoch_millis ASC,
            link.source_asset_id ASC,
            link.role ASC
        LIMIT :limit
        """,
    )
    suspend fun readExactStudentDocumentAssets(
        draftId: String,
        revisionId: String,
        limit: Int,
    ): List<ExactLegacyStudentDocumentAssetRow>

    @Query(
        """
        SELECT page_index, source_asset_id
        FROM problem_draft_source_asset
        WHERE draft_id = :draftId
        ORDER BY page_index ASC
        LIMIT :limit
        """,
    )
    suspend fun readExactDraftSourceAssets(
        draftId: String,
        limit: Int,
    ): List<ExactLegacyDraftSourceAssetRow>

    @Query(
        """
        SELECT
            link.problem_revision_id AS problem_revision_id,
            COUNT(*) AS source_asset_link_count,
            SUM(
                CASE
                    WHEN asset.source_asset_id IS NULL THEN 1
                    ELSE 0
                END
            ) AS broken_source_asset_link_count,
            COALESCE(
                SUM(
                    CASE
                        WHEN asset.source_asset_id IS NULL THEN 0
                        ELSE asset.byte_size
                    END
                ),
                0
            ) AS source_asset_byte_count
        FROM problem_revision_source_asset AS link
        LEFT JOIN canonical_source_asset AS asset
          ON asset.source_asset_id = link.source_asset_id
        WHERE link.problem_revision_id IN (:revisionIds)
        GROUP BY link.problem_revision_id
        ORDER BY link.problem_revision_id ASC
        """,
    )
    suspend fun readStudentDocumentAssetBudgets(
        revisionIds: List<String>,
    ): List<LegacyStudentDocumentAssetBudgetRow>

    /**
     * Uses one receipt draft as the page-order witness for each revision. The widest draft wins,
     * then the latest receipt breaks ties; links outside that single witness keep a null page so
     * the destination cannot mistake unrelated capture-local orders for one document order.
     */
    @Query(
        """
        SELECT
            link.problem_revision_id AS problem_revision_id,
            draft_asset.page_index AS page_index,
            link.role AS role,
            link.source_asset_id AS linked_source_asset_id,
            asset.source_asset_id AS canonical_source_asset_id,
            asset.content_sha256 AS content_sha256,
            asset.relative_path AS relative_path,
            asset.mime_type AS mime_type,
            asset.byte_size AS byte_size,
            asset.width AS width,
            asset.height AS height,
            asset.source_type AS source_type,
            asset.created_at_epoch_millis AS created_at_epoch_millis
        FROM problem_revision_source_asset AS link
        LEFT JOIN canonical_source_asset AS asset
          ON asset.source_asset_id = link.source_asset_id
        LEFT JOIN problem_draft_commit_receipt AS receipt
          ON receipt.command_id = (
              SELECT candidate.command_id
              FROM problem_draft_commit_receipt AS candidate
              WHERE candidate.problem_revision_id = link.problem_revision_id
              ORDER BY
                  (
                      SELECT COUNT(*)
                      FROM problem_draft_source_asset AS candidate_asset
                      WHERE candidate_asset.draft_id = candidate.draft_id
                  ) DESC,
                  candidate.committed_at_epoch_millis DESC,
                  candidate.command_id DESC
              LIMIT 1
          )
        LEFT JOIN problem_draft_source_asset AS draft_asset
          ON draft_asset.draft_id = receipt.draft_id
         AND draft_asset.source_asset_id = link.source_asset_id
        WHERE link.problem_revision_id IN (:revisionIds)
        ORDER BY
            link.problem_revision_id ASC,
            CASE WHEN draft_asset.page_index IS NULL THEN 1 ELSE 0 END ASC,
            draft_asset.page_index ASC,
            asset.created_at_epoch_millis ASC,
            asset.source_asset_id ASC,
            link.role ASC
        LIMIT :limit
        """,
    )
    suspend fun readStudentDocumentAssets(
        revisionIds: List<String>,
        limit: Int,
    ): List<LegacyStudentDocumentAssetRow>

    @Query(
        """
        SELECT
            fact.source_fact_id AS source_fact_id,
            fact.learner_id AS learner_id,
            fact.source AS source,
            fact.fact_kind AS fact_kind,
            fact.anchor_id AS anchor_id,
            fact.subject AS subject,
            fact.conversation_id AS conversation_id,
            fact.conversation_generation AS conversation_generation,
            fact.turn_receipt_id AS turn_receipt_id,
            fact.evidence_request_id AS evidence_request_id,
            fact.response_fingerprint AS response_fingerprint,
            fact.response_summary AS response_summary,
            fact.payload_fingerprint AS source_payload_fingerprint,
            fact.occurred_at_epoch_millis AS occurred_at_epoch_millis,
            fact.source_version AS source_version,
            proof.proof_fingerprint AS source_proof_fingerprint,
            proof.source_reference_id AS source_reference_id,
            proof.target_kind AS target_kind,
            proof.target_database AS target_database,
            proof.target_id AS target_id,
            proof.target_version AS target_version,
            proof.target_fingerprint AS target_fingerprint,
            proof.attested_at_epoch_millis AS attested_at_epoch_millis
        FROM learning_observation_source_fact AS fact
        LEFT JOIN learning_observation_source_fact_proof AS proof
          ON proof.source_fact_id = fact.source_fact_id
        WHERE
            fact.learner_id = :learnerId
            AND (
                :afterOccurredAtEpochMillis IS NULL
                OR fact.occurred_at_epoch_millis > :afterOccurredAtEpochMillis
                OR (
                    fact.occurred_at_epoch_millis = :afterOccurredAtEpochMillis
                    AND fact.source_fact_id > :afterSourceFactId
                )
            )
        ORDER BY
            fact.occurred_at_epoch_millis ASC,
            fact.source_fact_id ASC
        LIMIT :limit
        """,
    )
    suspend fun readMasteryFactPage(
        learnerId: String,
        afterOccurredAtEpochMillis: Long?,
        afterSourceFactId: String?,
        limit: Int,
    ): List<LegacyMasteryFactMigrationRow>

    @Query(
        """
        SELECT
            (
                SELECT COUNT(*)
                FROM error_book_entry AS entry
                JOIN problem_revision AS revision
                  ON revision.revision_id = entry.current_revision_id
                 AND revision.problem_id = entry.problem_id
            ) AS student_document_revision_count,
            (
                SELECT COUNT(*)
                FROM error_book_entry AS entry
                JOIN problem_revision AS revision
                  ON revision.revision_id = entry.current_revision_id
                 AND revision.problem_id = entry.problem_id
                JOIN problem_revision_source_asset AS link
                  ON link.problem_revision_id = revision.revision_id
            ) AS student_source_asset_link_count,
            (
                SELECT COUNT(*)
                FROM error_book_entry AS entry
                JOIN problem_revision AS revision
                  ON revision.revision_id = entry.current_revision_id
                 AND revision.problem_id = entry.problem_id
                JOIN problem_revision_source_asset AS link
                  ON link.problem_revision_id = revision.revision_id
                LEFT JOIN canonical_source_asset AS asset
                  ON asset.source_asset_id = link.source_asset_id
                WHERE asset.source_asset_id IS NULL
            ) AS student_broken_source_asset_link_count,
            COALESCE(
                (
                    SELECT SUM(asset.byte_size)
                    FROM error_book_entry AS entry
                    JOIN problem_revision AS revision
                      ON revision.revision_id = entry.current_revision_id
                     AND revision.problem_id = entry.problem_id
                    JOIN problem_revision_source_asset AS link
                      ON link.problem_revision_id = revision.revision_id
                    JOIN canonical_source_asset AS asset
                      ON asset.source_asset_id = link.source_asset_id
                ),
                0
            ) AS student_source_asset_byte_count,
            (
                SELECT COUNT(*)
                FROM (
                    SELECT entry.problem_id
                    FROM error_book_entry AS entry
                    GROUP BY entry.problem_id
                    HAVING COUNT(*) > 1
                )
            ) AS student_problem_with_multiple_entries_count,
            (
                SELECT COUNT(*)
                FROM error_book_entry AS entry
                JOIN problem_revision AS current_revision
                  ON current_revision.revision_id = entry.current_revision_id
                 AND current_revision.problem_id = entry.problem_id
                WHERE current_revision.revision_number != (
                    SELECT MAX(candidate.revision_number)
                    FROM problem_revision AS candidate
                    WHERE candidate.problem_id = entry.problem_id
                )
            ) AS student_problem_current_revision_not_latest_count,
            (
                SELECT COUNT(*)
                FROM learning_observation_source_fact AS fact
                WHERE fact.learner_id = :learnerId
            ) AS mastery_source_fact_count,
            (
                SELECT COUNT(*)
                FROM learning_observation_source_fact AS fact
                JOIN learning_observation_source_fact_proof AS proof
                  ON proof.source_fact_id = fact.source_fact_id
                WHERE fact.learner_id = :learnerId
            ) AS mastery_proven_source_fact_count,
            COALESCE(
                (
                    SELECT MAX(
                        CASE
                            WHEN revision.created_at_epoch_millis >
                                 entry.updated_at_epoch_millis
                            THEN revision.created_at_epoch_millis
                            ELSE entry.updated_at_epoch_millis
                        END
                    )
                    FROM error_book_entry AS entry
                    JOIN problem_revision AS revision
                      ON revision.revision_id = entry.current_revision_id
                     AND revision.problem_id = entry.problem_id
                ),
                0
            ) AS latest_student_mutation_at_epoch_millis,
            COALESCE(
                (
                    SELECT MAX(fact.occurred_at_epoch_millis)
                    FROM learning_observation_source_fact AS fact
                    WHERE fact.learner_id = :learnerId
                ),
                0
            ) AS latest_mastery_fact_at_epoch_millis
        """,
    )
    suspend fun readStats(learnerId: String): LegacyAuthorityMigrationStatsRow
}
