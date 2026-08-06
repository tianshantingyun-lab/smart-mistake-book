package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
internal abstract class StudentMistakeCutoverDao {
    @Query(
        """
        SELECT singleton_key, cutover_generation,
               student_import_evidence_fingerprint,
               mastery_import_evidence_fingerprint,
               cutover_intent_fingerprint, fence_fingerprint
        FROM student_cutover_fence
        ORDER BY singleton_key
        """,
    )
    protected abstract suspend fun readCutoverFenceRows():
        List<StudentMistakeCutoverFenceEntity>

    @Query(
        """
        SELECT singleton_key, cutover_generation, cutover_intent_fingerprint,
               authority_fence_fingerprint, receipt_fingerprint
        FROM student_cutover_completion_receipt
        ORDER BY singleton_key
        """,
    )
    protected abstract suspend fun readCompletionReceiptRows():
        List<StudentMistakeCutoverCompletionReceiptEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertCutoverFence(
        fence: StudentMistakeCutoverFenceEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertCompletionReceipt(
        receipt: StudentMistakeCutoverCompletionReceiptEntity,
    ): Long

    @Query(
        """
        SELECT migration_id, source_database_canonical_fingerprint,
               last_committed_at_epoch_millis, last_problem_id,
               last_revision_number, last_revision_id, imported_record_count,
               completed, destination_ledger_version,
               checkpoint_canonical_fingerprint,
               updated_at_epoch_millis
        FROM student_mistake_migration_checkpoint
        WHERE migration_id = :migrationId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readMigrationCheckpoint(
        migrationId: String,
    ): StudentMistakeMigrationCheckpointEntity?

    @Query(
        """
        SELECT migration_id, source_page_canonical_fingerprint,
               imported_record_count, result_last_committed_at_epoch_millis,
               result_last_problem_id, result_last_revision_number,
               result_last_revision_id, result_total_record_count,
               result_completed, checkpoint_canonical_fingerprint,
               receipt_canonical_fingerprint, applied_at_epoch_millis
        FROM student_mistake_migration_receipt
        WHERE migration_id = :migrationId
        ORDER BY result_total_record_count ASC,
                 result_completed ASC,
                 applied_at_epoch_millis ASC,
                 source_page_canonical_fingerprint ASC
        """,
    )
    protected abstract suspend fun readMigrationReceipts(
        migrationId: String,
    ): List<StudentMistakeMigrationReceiptEntity>

    @Query(
        """
        SELECT migration_id, source_page_canonical_fingerprint,
               page_record_ordinal, committed_at_epoch_millis,
               problem_id, revision_number, revision_id,
               import_snapshot_canonical_fingerprint,
               destination_record_canonical_fingerprint
        FROM student_mistake_migration_destination_record
        WHERE migration_id = :migrationId
        ORDER BY source_page_canonical_fingerprint ASC,
                 page_record_ordinal ASC
        """,
    )
    protected abstract suspend fun readMigrationDestinationRecordsUnordered(
        migrationId: String,
    ): List<StudentMistakeMigrationDestinationRecordEntity>

    @Query(
        """
        SELECT migration_id, revision_id,
               legacy_destination_record_canonical_fingerprint,
               invalidation_reason, replacement_policy_version,
               invalidated_at_schema_version
        FROM student_mistake_destination_attestation_invalidation
        WHERE migration_id = :migrationId
        ORDER BY revision_id ASC
        """,
    )
    abstract suspend fun readDestinationAttestationInvalidations(
        migrationId: String,
    ): List<StudentMistakeDestinationAttestationInvalidationEntity>

    @Query(
        """
        SELECT migration_id, revision_id,
               legacy_destination_record_canonical_fingerprint,
               replacement_destination_record_canonical_fingerprint,
               canonical_policy_version, issuer_key_id, issuer_version,
               issued_at_epoch_millis, receipt_canonical_fingerprint
        FROM student_mistake_destination_reattestation_receipt
        WHERE migration_id = :migrationId
        ORDER BY revision_id ASC
        """,
    )
    abstract suspend fun readDestinationReattestationReceipts(
        migrationId: String,
    ): List<StudentMistakeDestinationReattestationReceiptEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertDestinationReattestationReceipt(
        receipt: StudentMistakeDestinationReattestationReceiptEntity,
    ): Long

    @Query(
        """
        SELECT problem_id, learner_id, subject, primary_practice_unit_id,
               current_revision_id, error_book_entry_id, lifecycle_state,
               archived_at_epoch_millis, tombstoned_at_epoch_millis,
               created_at_epoch_millis, updated_at_epoch_millis
        FROM student_problem_document
        WHERE problem_id IN (:problemIds)
        """,
    )
    protected abstract suspend fun readDestinationProblems(
        problemIds: List<String>,
    ): List<StudentProblemDocumentEntity>

    @Query(
        """
        SELECT revision_id, problem_id, revision_number, title, stem_markdown,
               captured_question_document_wire, document_canonical_fingerprint,
               created_at_epoch_millis, updated_at_epoch_millis
        FROM student_problem_revision
        WHERE revision_id IN (:revisionIds)
        """,
    )
    protected abstract suspend fun readDestinationRevisions(
        revisionIds: List<String>,
    ): List<StudentProblemRevisionEntity>

    @Query(
        """
        SELECT revision_id, problem_id, practice_unit_id, target_unit_kind,
               target_title, target_item_family_id,
               target_estimated_duration_seconds, target_source_bundle_id,
               target_part_ids_wire, target_error_book_entry_id,
               legacy_semantics_present, legacy_problem_canonical_fingerprint,
               legacy_revision_source_type, legacy_revision_source_reference,
               legacy_answer_spec_id, legacy_answer_spec_snapshot,
               legacy_answer_verification_status, legacy_error_book_source_key,
               legacy_practice_unit_key, legacy_practice_unit_prompt_markdown,
               snapshot_canonical_fingerprint
        FROM student_problem_import_semantic_snapshot
        WHERE revision_id IN (:revisionIds)
        """,
    )
    protected abstract suspend fun readDestinationImportSnapshots(
        revisionIds: List<String>,
    ): List<StudentProblemImportSemanticSnapshotEntity>

    @Query(
        """
        SELECT image_reference_id, revision_id, local_content_uri,
               content_canonical_fingerprint, media_type, ordinal,
               width_pixels, height_pixels, byte_size,
               selected_regions_wire, created_at_epoch_millis
        FROM student_problem_image_reference
        WHERE revision_id IN (:revisionIds)
        """,
    )
    protected abstract suspend fun readDestinationImages(
        revisionIds: List<String>,
    ): List<StudentProblemImageReferenceEntity>

    @Query(
        """
        SELECT solution_analysis_id, basis_revision_id, organization_receipt_id,
               summary_markdown,
               final_answer_markdown, model_provider_id, model_id,
               analyzer_version, result_canonical_fingerprint,
               recorded_at_epoch_millis
        FROM student_problem_solution_analysis
        WHERE basis_revision_id IN (:revisionIds)
        """,
    )
    protected abstract suspend fun readDestinationSolutionAnalyses(
        revisionIds: List<String>,
    ): List<StudentProblemSolutionAnalysisEntity>

    @Query(
        """
        SELECT solution_analysis_id, basis_revision_id, step_id, ordinal,
               summary_markdown, reasoning_markdown, result_markdown,
               step_canonical_fingerprint
        FROM student_problem_solution_step
        WHERE basis_revision_id IN (:revisionIds)
        """,
    )
    protected abstract suspend fun readDestinationSolutionSteps(
        revisionIds: List<String>,
    ): List<StudentProblemSolutionStepEntity>

    @Query(
        """
        SELECT attribution_id, basis_revision_id, organization_receipt_id,
               solution_analysis_id,
               resolution_status, rationale_markdown,
               step_ordinal, atomic_reference_id, model_provider_id,
               model_id, analyzer_version, result_canonical_fingerprint,
               recorded_at_epoch_millis
        FROM student_problem_error_attribution
        WHERE basis_revision_id IN (:revisionIds)
        """,
    )
    protected abstract suspend fun readDestinationErrorAttributions(
        revisionIds: List<String>,
    ): List<StudentProblemErrorAttributionEntity>

    @Query(
        """
        SELECT attribution_id, basis_revision_id, ordinal, block_id,
               source_asset_id, evidence_kind
        FROM student_problem_error_evidence
        WHERE basis_revision_id IN (:revisionIds)
        """,
    )
    protected abstract suspend fun readDestinationErrorEvidence(
        revisionIds: List<String>,
    ): List<StudentProblemErrorEvidenceEntity>

    open suspend fun readCutoverFence(): StudentMistakeCutoverFenceEntity? =
        requireCutoverFenceSingleton(readCutoverFenceRows())

    @Transaction
    open suspend fun appendCutoverFenceIfAbsent(
        candidate: StudentMistakeCutoverFenceEntity,
    ): StudentMistakeCutoverFenceEntity {
        readCutoverFence()?.let { return it }
        check(readCompletionReceiptRows().isEmpty()) {
            "Student cutover completion receipt exists without its fence"
        }
        insertCutoverFence(candidate)
        return checkNotNull(readCutoverFence()) {
            "Student cutover fence was not durable after append"
        }
    }

    @Transaction
    open suspend fun readBoundCompletionReceipt():
        StudentMistakeCutoverCompletionReceiptEntity? {
        val receipt =
            requireCompletionReceiptSingleton(readCompletionReceiptRows())
                ?: return null
        val fence =
            checkNotNull(readCutoverFence()) {
                "Student cutover completion receipt exists without its fence"
            }
        check(receipt.isBoundTo(fence)) {
            "Student cutover completion receipt is not bound to its durable fence"
        }
        return receipt
    }

    @Transaction
    open suspend fun appendCompletionReceiptIfAbsent(
        candidate: StudentMistakeCutoverCompletionReceiptEntity,
    ): StudentMistakeCutoverCompletionReceiptEntity {
        readBoundCompletionReceipt()?.let { return it }
        val fence =
            checkNotNull(readCutoverFence()) {
                "Student cutover completion receipt requires its durable fence"
            }
        check(candidate.isBoundTo(fence)) {
            "Student cutover completion receipt changed its durable fence"
        }
        insertCompletionReceipt(candidate)
        return checkNotNull(readBoundCompletionReceipt()) {
            "Student cutover completion receipt was not durable after append"
        }
    }

    @Transaction
    open suspend fun appendDestinationReattestationReceiptIfAbsent(
        candidate: StudentMistakeDestinationReattestationReceiptEntity,
    ): StudentMistakeDestinationReattestationReceiptEntity {
        readDestinationReattestationReceipts(candidate.migrationId)
            .singleOrNull { it.revisionId == candidate.revisionId }
            ?.let { existing ->
                check(existing == candidate) {
                    "Destination reattestation receipt conflicts with its append-only winner"
                }
                return existing
            }
        insertDestinationReattestationReceipt(candidate)
        return checkNotNull(
            readDestinationReattestationReceipts(candidate.migrationId)
                .singleOrNull { it.revisionId == candidate.revisionId },
        ) {
            "Destination reattestation receipt was not durable after append"
        }.also { persisted ->
            check(persisted == candidate) {
                "Destination reattestation receipt changed during append"
            }
        }
    }

    @Transaction
    open suspend fun readMigrationLedgerRows(
        migrationId: String,
    ): StudentMistakeMigrationLedgerRows? {
        val checkpoint = readMigrationCheckpoint(migrationId)
        val receipts = readMigrationReceipts(migrationId)
        if (checkpoint == null) {
            check(receipts.isEmpty()) {
                "Student migration receipts exist without their checkpoint"
            }
            check(readMigrationDestinationRecordsUnordered(migrationId).isEmpty()) {
                "Student migration destination records exist without their checkpoint"
            }
            check(readDestinationAttestationInvalidations(migrationId).isEmpty()) {
                "Destination attestation invalidation exists without its checkpoint"
            }
            check(readDestinationReattestationReceipts(migrationId).isEmpty()) {
                "Destination reattestation receipt exists without its checkpoint"
            }
            return null
        }
        val destinationRecords =
            readMigrationDestinationRecordsUnordered(migrationId)
                .sortedWith(
                    compareBy(
                        StudentMistakeMigrationDestinationRecordEntity::
                            sourcePageCanonicalFingerprint,
                        StudentMistakeMigrationDestinationRecordEntity::pageRecordOrdinal,
                    ),
                )
        val revisionIds =
            destinationRecords
                .map(StudentMistakeMigrationDestinationRecordEntity::revisionId)
                .distinct()
        val problemIds =
            destinationRecords
                .map(StudentMistakeMigrationDestinationRecordEntity::problemId)
                .distinct()
        val problems = mutableListOf<StudentProblemDocumentEntity>()
        val revisions = mutableListOf<StudentProblemRevisionEntity>()
        val importSnapshots =
            mutableListOf<StudentProblemImportSemanticSnapshotEntity>()
        val images = mutableListOf<StudentProblemImageReferenceEntity>()
        val analyses = mutableListOf<StudentProblemSolutionAnalysisEntity>()
        val steps = mutableListOf<StudentProblemSolutionStepEntity>()
        val attributions = mutableListOf<StudentProblemErrorAttributionEntity>()
        val evidence = mutableListOf<StudentProblemErrorEvidenceEntity>()
        problemIds.chunked(CUTOVER_READ_BIND_BATCH).forEach { ids ->
            problems += readDestinationProblems(ids)
        }
        revisionIds.chunked(CUTOVER_READ_BIND_BATCH).forEach { ids ->
            revisions += readDestinationRevisions(ids)
            importSnapshots += readDestinationImportSnapshots(ids)
            images += readDestinationImages(ids)
            analyses += readDestinationSolutionAnalyses(ids)
            steps += readDestinationSolutionSteps(ids)
            attributions += readDestinationErrorAttributions(ids)
            evidence += readDestinationErrorEvidence(ids)
        }
        return StudentMistakeMigrationLedgerRows(
            checkpoint = checkpoint,
            receipts = receipts,
            destinationRecords = destinationRecords,
            problems = problems,
            revisions = revisions,
            importSnapshots = importSnapshots,
            images = images,
            solutionAnalyses = analyses,
            solutionSteps = steps,
            errorAttributions = attributions,
            errorEvidence = evidence,
            destinationAttestationInvalidations =
                readDestinationAttestationInvalidations(migrationId),
            destinationReattestationReceipts =
                readDestinationReattestationReceipts(migrationId),
        )
    }
}

internal data class StudentMistakeMigrationLedgerRows(
    val checkpoint: StudentMistakeMigrationCheckpointEntity,
    val receipts: List<StudentMistakeMigrationReceiptEntity>,
    val destinationRecords: List<StudentMistakeMigrationDestinationRecordEntity>,
    val problems: List<StudentProblemDocumentEntity>,
    val revisions: List<StudentProblemRevisionEntity>,
    val importSnapshots: List<StudentProblemImportSemanticSnapshotEntity>,
    val images: List<StudentProblemImageReferenceEntity>,
    val solutionAnalyses: List<StudentProblemSolutionAnalysisEntity>,
    val solutionSteps: List<StudentProblemSolutionStepEntity>,
    val errorAttributions: List<StudentProblemErrorAttributionEntity>,
    val errorEvidence: List<StudentProblemErrorEvidenceEntity>,
    val destinationAttestationInvalidations:
        List<StudentMistakeDestinationAttestationInvalidationEntity>,
    val destinationReattestationReceipts:
        List<StudentMistakeDestinationReattestationReceiptEntity>,
)

private fun requireCutoverFenceSingleton(
    rows: List<StudentMistakeCutoverFenceEntity>,
): StudentMistakeCutoverFenceEntity? {
    check(rows.size <= 1) { "Student cutover fence table is not a singleton" }
    return rows.singleOrNull()?.also { row ->
        check(row.singletonKey == STUDENT_CUTOVER_SINGLETON_KEY) {
            "Student cutover fence has an invalid singleton key"
        }
    }
}

private fun requireCompletionReceiptSingleton(
    rows: List<StudentMistakeCutoverCompletionReceiptEntity>,
): StudentMistakeCutoverCompletionReceiptEntity? {
    check(rows.size <= 1) {
        "Student cutover completion receipt table is not a singleton"
    }
    return rows.singleOrNull()?.also { row ->
        check(row.singletonKey == STUDENT_CUTOVER_SINGLETON_KEY) {
            "Student cutover completion receipt has an invalid singleton key"
        }
    }
}

private fun StudentMistakeCutoverCompletionReceiptEntity.isBoundTo(
    fence: StudentMistakeCutoverFenceEntity,
): Boolean =
    singletonKey == fence.singletonKey &&
        cutoverGeneration == fence.cutoverGeneration &&
        cutoverIntentFingerprint == fence.cutoverIntentFingerprint &&
        authorityFenceFingerprint == fence.fenceFingerprint

private const val CUTOVER_READ_BIND_BATCH = 400
