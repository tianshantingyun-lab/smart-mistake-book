package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Transaction
import androidx.room3.Update
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef

/**
 * Problem document, revision, practice unit, import, image, solution and error primitives.
 */
@Dao
internal abstract class StudentProblemDocumentDao : StudentMistakeCrossStoreDao() {
    @Query(
        """
        SELECT *
        FROM student_outbox_authenticity_key_state
        WHERE singleton_id = 1
        LIMIT 1
        """,
    )
    abstract suspend fun readActiveOutboxAuthenticityKeyState():
        StudentOutboxAuthenticityKeyStateEntity?

    @Query(
        """
        SELECT problem_id, learner_id, subject, primary_practice_unit_id,
               current_revision_id, error_book_entry_id, lifecycle_state, archived_at_epoch_millis,
               tombstoned_at_epoch_millis, created_at_epoch_millis,
               updated_at_epoch_millis
        FROM student_problem_document
        WHERE problem_id = :problemId
        LIMIT 1
        """,
    )
    abstract suspend fun readProblem(problemId: String): StudentProblemDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertProblem(problem: StudentProblemDocumentEntity)

    @Update
    protected abstract suspend fun updateProblem(problem: StudentProblemDocumentEntity): Int

    @Query(
        """
        SELECT revision_id, problem_id, revision_number, title, stem_markdown,
               captured_question_document_wire, document_canonical_fingerprint, created_at_epoch_millis,
               updated_at_epoch_millis
        FROM student_problem_revision
        WHERE revision_id = :revisionId
        LIMIT 1
        """,
    )
    abstract suspend fun readRevision(revisionId: String): StudentProblemRevisionEntity?

    @Query(
        """
        SELECT
          problem.learner_id AS learnerId,
          problem.subject AS subject,
          problem.problem_id AS problemId,
          problem.primary_practice_unit_id AS practiceUnitId,
          problem.lifecycle_state AS lifecycleState,
          collection.mistake_state AS mistakeState,
          revision.revision_id AS revisionId,
          revision.revision_number AS revisionNumber,
          revision.document_canonical_fingerprint AS documentCanonicalFingerprint
        FROM student_problem_revision AS revision
        INNER JOIN student_problem_document AS problem
          ON problem.problem_id = revision.problem_id
        LEFT JOIN student_problem_collection AS collection
          ON collection.practice_unit_id = problem.primary_practice_unit_id
        WHERE revision.revision_id IN (:revisionIds)
        ORDER BY revision.revision_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readRevisionReferenceRows(
        revisionIds: List<String>,
        limit: Int,
    ): List<PersistedStudentProblemRevisionRefRow>

    @Query(
        """
        SELECT revision_id, problem_id, revision_number, title, stem_markdown,
               captured_question_document_wire, document_canonical_fingerprint, created_at_epoch_millis,
               updated_at_epoch_millis
        FROM student_problem_revision
        WHERE problem_id = :problemId
        ORDER BY revision_number DESC
        LIMIT 1
        """,
    )
    protected abstract suspend fun readLatestRevision(
        problemId: String,
    ): StudentProblemRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRevision(revision: StudentProblemRevisionEntity)

    @Query(
        """
        SELECT practice_unit_id, problem_id, basis_revision_id, unit_kind, title,
               item_family_id, estimated_duration_seconds, source_bundle_id,
               part_ids_wire, created_at_epoch_millis, updated_at_epoch_millis
        FROM student_practice_unit
        WHERE practice_unit_id = :practiceUnitId
        LIMIT 1
        """,
    )
    abstract suspend fun readPracticeUnit(practiceUnitId: String): StudentPracticeUnitEntity?

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
        WHERE revision_id = :revisionId
        LIMIT 1
        """,
    )
    abstract suspend fun readImportSnapshot(
        revisionId: String,
    ): StudentProblemImportSemanticSnapshotEntity?

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
    protected abstract suspend fun readImportSnapshots(
        revisionIds: List<String>,
    ): List<StudentProblemImportSemanticSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertImportSnapshot(
        snapshot: StudentProblemImportSemanticSnapshotEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPracticeUnit(unit: StudentPracticeUnitEntity)

    @Update
    protected abstract suspend fun updatePracticeUnit(unit: StudentPracticeUnitEntity): Int

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
    abstract suspend fun readImages(
        revisionId: String,
        limit: Int,
    ): List<StudentProblemImageReferenceEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertImages(images: List<StudentProblemImageReferenceEntity>)

    @Query(
        """
        SELECT problem_id, learner_id, subject, primary_practice_unit_id,
               current_revision_id, error_book_entry_id, lifecycle_state,
               archived_at_epoch_millis, tombstoned_at_epoch_millis,
               created_at_epoch_millis, updated_at_epoch_millis
        FROM student_problem_document
        WHERE error_book_entry_id = :errorBookEntryId
        LIMIT 1
        """,
    )
    abstract suspend fun readProblemByErrorBookEntryId(
        errorBookEntryId: String,
    ): StudentProblemDocumentEntity?

    @Query(
        """
        SELECT problem.problem_id, problem.learner_id, problem.subject,
               problem.primary_practice_unit_id, problem.current_revision_id,
               problem.error_book_entry_id,
               problem.lifecycle_state, problem.archived_at_epoch_millis,
               problem.tombstoned_at_epoch_millis,
               problem.created_at_epoch_millis, problem.updated_at_epoch_millis
        FROM student_problem_document AS problem
        INNER JOIN student_practice_unit AS unit
          ON unit.problem_id = problem.problem_id
        WHERE unit.practice_unit_id = :practiceUnitId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readProblemByPracticeUnit(
        practiceUnitId: String,
    ): StudentProblemDocumentEntity?

    @Query(
        """
        SELECT solution_analysis_id, basis_revision_id, organization_receipt_id,
               summary_markdown,
               final_answer_markdown, model_provider_id, model_id,
               analyzer_version, result_canonical_fingerprint,
               recorded_at_epoch_millis
        FROM student_problem_solution_analysis AS analysis
        WHERE analysis.basis_revision_id = :revisionId
          AND (
            analysis.organization_receipt_id = (
              SELECT receipt.receipt_id
              FROM student_problem_organization_receipt AS receipt
              WHERE receipt.basis_revision_id = :revisionId
                AND receipt.status = 'COMPLETED'
              ORDER BY receipt.organization_revision DESC
              LIMIT 1
            )
            OR (
              analysis.organization_receipt_id IS NULL
              AND NOT EXISTS (
                SELECT 1
                FROM student_problem_organization_receipt AS receipt
                WHERE receipt.basis_revision_id = :revisionId
                  AND receipt.status = 'COMPLETED'
              )
            )
          )
        ORDER BY analysis.recorded_at_epoch_millis DESC,
                 analysis.solution_analysis_id DESC
        LIMIT 1
        """,
    )
    abstract suspend fun readSolutionAnalysis(
        revisionId: String,
    ): StudentProblemSolutionAnalysisEntity?

    @Query(
        """
        SELECT solution_analysis_id, basis_revision_id, step_id, ordinal,
               summary_markdown, reasoning_markdown, result_markdown,
               step_canonical_fingerprint
        FROM student_problem_solution_step
        WHERE solution_analysis_id = :solutionAnalysisId
        ORDER BY ordinal ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readSolutionSteps(
        solutionAnalysisId: String,
        limit: Int,
    ): List<StudentProblemSolutionStepEntity>

    @Query(
        """
        SELECT attribution_id, basis_revision_id, organization_receipt_id,
               solution_analysis_id,
               resolution_status, rationale_markdown, step_ordinal,
               atomic_reference_id, model_provider_id, model_id,
               analyzer_version, result_canonical_fingerprint,
               recorded_at_epoch_millis
        FROM student_problem_error_attribution AS attribution
        WHERE attribution.basis_revision_id = :revisionId
          AND (
            attribution.organization_receipt_id = (
              SELECT receipt.receipt_id
              FROM student_problem_organization_receipt AS receipt
              WHERE receipt.basis_revision_id = :revisionId
                AND receipt.status = 'COMPLETED'
              ORDER BY receipt.organization_revision DESC
              LIMIT 1
            )
            OR (
              attribution.organization_receipt_id IS NULL
              AND NOT EXISTS (
                SELECT 1
                FROM student_problem_organization_receipt AS receipt
                WHERE receipt.basis_revision_id = :revisionId
                  AND receipt.status = 'COMPLETED'
              )
            )
          )
        ORDER BY attribution_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readErrorAttributions(
        revisionId: String,
        limit: Int,
    ): List<StudentProblemErrorAttributionEntity>

    @Query(
        """
        SELECT evidence.attribution_id, evidence.basis_revision_id,
               evidence.ordinal, evidence.block_id,
               evidence.source_asset_id, evidence.evidence_kind
        FROM student_problem_error_evidence AS evidence
        INNER JOIN student_problem_error_attribution AS attribution
          ON attribution.attribution_id = evidence.attribution_id
         AND attribution.basis_revision_id = evidence.basis_revision_id
        WHERE evidence.basis_revision_id = :revisionId
          AND (
            attribution.organization_receipt_id = (
              SELECT receipt.receipt_id
              FROM student_problem_organization_receipt AS receipt
              WHERE receipt.basis_revision_id = :revisionId
                AND receipt.status = 'COMPLETED'
              ORDER BY receipt.organization_revision DESC
              LIMIT 1
            )
            OR (
              attribution.organization_receipt_id IS NULL
              AND NOT EXISTS (
                SELECT 1
                FROM student_problem_organization_receipt AS receipt
                WHERE receipt.basis_revision_id = :revisionId
                  AND receipt.status = 'COMPLETED'
              )
            )
          )
        ORDER BY evidence.attribution_id ASC, evidence.ordinal ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readErrorEvidence(
        revisionId: String,
        limit: Int,
    ): List<StudentProblemErrorEvidenceEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSolutionAnalysis(
        analysis: StudentProblemSolutionAnalysisEntity,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSolutionSteps(
        steps: List<StudentProblemSolutionStepEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertErrorAttributions(
        attributions: List<StudentProblemErrorAttributionEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertErrorEvidence(
        evidence: List<StudentProblemErrorEvidenceEntity>,
    )

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
          revision.created_at_epoch_millis AS committedAtEpochMillis,
          CASE WHEN revision.captured_question_document_wire IS NULL
            THEN 0 ELSE 1 END AS hasCapturedQuestionDocument,
          CASE WHEN solution.solution_analysis_id IS NULL
            THEN 0 ELSE 1 END AS hasSolutionAnalysis,
          (
            SELECT COUNT(*)
            FROM student_problem_error_attribution AS attribution
            WHERE attribution.basis_revision_id = revision.revision_id
          ) AS errorAttributionCount
        FROM student_problem_document AS problem
        INNER JOIN student_problem_revision AS revision
          ON revision.problem_id = problem.problem_id
        LEFT JOIN student_problem_solution_analysis AS solution
          ON solution.basis_revision_id = revision.revision_id
        WHERE problem.error_book_entry_id = :errorBookEntryId
          AND (
            :cursorRevisionNumber IS NULL OR
            revision.revision_number < :cursorRevisionNumber OR
            (
              revision.revision_number = :cursorRevisionNumber AND
              revision.revision_id > :cursorRevisionId
            )
          )
        ORDER BY revision.revision_number DESC, revision.revision_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readRevisionHistoryRows(
        errorBookEntryId: String,
        cursorRevisionNumber: Int?,
        cursorRevisionId: String?,
        limit: Int,
    ): List<StudentProblemRevisionHistoryRow>

    @Transaction
    open suspend fun commitProblem(
        bundle: CommitStudentProblemBundle,
        recordChange: Boolean = true,
        publishRevisionToMastery: Boolean = true,
    ) {
        val existingProblem = readProblem(bundle.problem.problemId)
        if (existingProblem == null) {
            insertProblem(bundle.problem)
            insertRevision(bundle.revision)
            insertRevisionAuthority(bundle)
            insertPracticeUnit(bundle.practiceUnit)
            bundle.images.insertWhenNotEmpty(::insertImages)
            synchronizeSearchDocuments(listOf(bundle.searchDocument))
            if (publishRevisionToMastery) {
                insertOutboxExactlyOnce(bundle.outbox)
            }
            if (recordChange) bumpChangeVersion(bundle.problem.learnerId)
            return
        }

        check(existingProblem.sameProblemIdentity(bundle.problem)) {
            "Problem id is already owned by a different learner, subject, or practice unit"
        }
        check(
            existingProblem.errorBookEntryId == null ||
                bundle.problem.errorBookEntryId == null ||
                existingProblem.errorBookEntryId == bundle.problem.errorBookEntryId,
        ) {
            "Problem id was replayed with a different stable error-book entry id"
        }
        check(existingProblem.lifecycleState != StudentProblemLifecycleState.TOMBSTONED.name) {
            "A tombstoned problem cannot receive new revisions"
        }
        val existingRevision = readRevision(bundle.revision.revisionId)
        if (existingRevision != null) {
            check(existingRevision == bundle.revision) {
                "Problem revision id was replayed with different immutable content"
            }
            check(
                readImages(bundle.revision.revisionId, MAX_STORED_IMAGES + 1) == bundle.images,
            ) {
                "Problem revision was replayed with different image references"
            }
            checkRevisionAuthority(bundle)
            if (existingProblem.currentRevisionId == bundle.revision.revisionId) {
                check(readPracticeUnit(bundle.practiceUnit.practiceUnitId) == bundle.practiceUnit) {
                    "Current problem revision was replayed with different practice-unit content"
                }
            }
            if (
                existingProblem.errorBookEntryId == null &&
                bundle.problem.errorBookEntryId != null
            ) {
                check(
                    updateProblem(
                        existingProblem.copy(
                            errorBookEntryId = bundle.problem.errorBookEntryId,
                        ),
                    ) == 1,
                ) {
                    "Stable error-book entry id backfill did not affect exactly one row"
                }
                if (recordChange) bumpChangeVersion(existingProblem.learnerId)
            }
            synchronizeSearchDocuments(listOf(bundle.searchDocument))
            if (publishRevisionToMastery) {
                insertOutboxExactlyOnce(bundle.outbox)
            }
            return
        }

        val latestRevision = checkNotNull(readLatestRevision(bundle.problem.problemId)) {
            "Existing problem has no revision"
        }
        check(bundle.revision.revisionNumber == latestRevision.revisionNumber + 1) {
            "Problem revisions must be committed in contiguous order"
        }
        val existingPracticeUnit =
            checkNotNull(readPracticeUnit(bundle.practiceUnit.practiceUnitId)) {
                "Existing problem has no primary practice unit"
            }
        check(existingPracticeUnit.problemId == bundle.problem.problemId) {
            "Practice unit belongs to another problem"
        }

        insertRevision(bundle.revision)
        insertRevisionAuthority(bundle)
        check(updatePracticeUnit(bundle.practiceUnit) == 1) {
            "Practice unit update did not affect exactly one row"
        }
        bundle.images.insertWhenNotEmpty(::insertImages)
        synchronizeSearchDocuments(listOf(bundle.searchDocument))
        check(
            updateProblem(
                existingProblem.copy(
                    currentRevisionId = bundle.revision.revisionId,
                    errorBookEntryId =
                        existingProblem.errorBookEntryId ?: bundle.problem.errorBookEntryId,
                    updatedAtEpochMillis = bundle.problem.updatedAtEpochMillis,
                ),
            ) == 1,
        ) {
            "Problem update did not affect exactly one row"
        }
        if (publishRevisionToMastery) {
            insertOutboxExactlyOnce(bundle.outbox)
            bundle.supersededRevisionOutbox?.let { insertOutboxExactlyOnce(it) }
        }
        if (recordChange) bumpChangeVersion(bundle.problem.learnerId)
    }

    @Transaction
    open suspend fun setCollectionState(
        collection: StudentProblemCollectionEntity,
        recordChange: Boolean = true,
    ) {
        val problem = checkNotNull(readProblem(collection.problemId)) {
            "Cannot collect a missing problem"
        }
        check(
            problem.learnerId == collection.learnerId &&
                problem.primaryPracticeUnitId == collection.practiceUnitId,
        ) {
            "Collection state does not match the problem owner or practice unit"
        }
        val existing = readCollection(collection.practiceUnitId)
        if (existing == null) {
            insertCollection(collection)
            if (recordChange) bumpChangeVersion(collection.learnerId)
        } else {
            check(collection.changedAtEpochMillis >= existing.changedAtEpochMillis) {
                "Collection state update is older than the persisted state"
            }
            val next =
                collection.copy(
                    addedAtEpochMillis = existing.addedAtEpochMillis ?: collection.addedAtEpochMillis,
                )
            if (next == existing) return
            check(updateCollection(next) == 1) {
                "Collection state update did not affect exactly one row"
            }
            if (recordChange) bumpChangeVersion(collection.learnerId)
        }
    }

    @Transaction
    open suspend fun setProblemLifecycle(
        ref: StudentProblemRef,
        nextState: StudentProblemLifecycleState,
        changedAtEpochMillis: Long,
        outbox: StudentStoreOutboxEntity? = null,
        lifecycleOutbox: StudentStoreOutboxEntity? = null,
    ) {
        val problem = checkNotNull(readProblem(ref.problemId)) {
            "Cannot change lifecycle for a missing problem"
        }
        check(
            problem.learnerId == ref.learnerId &&
                problem.subject == ref.subject.name &&
                problem.primaryPracticeUnitId == ref.practiceUnitId,
        ) {
            "Problem lifecycle reference does not match persisted identity"
        }
        check(changedAtEpochMillis >= problem.updatedAtEpochMillis) {
            "Problem lifecycle update is older than the persisted state"
        }
        check(
            problem.lifecycleState != StudentProblemLifecycleState.TOMBSTONED.name ||
                nextState == StudentProblemLifecycleState.TOMBSTONED,
        ) {
            "A tombstoned problem cannot be restored"
        }
        val updated =
            problem.copy(
                lifecycleState = nextState.name,
                archivedAtEpochMillis =
                    if (nextState == StudentProblemLifecycleState.ARCHIVED) {
                        changedAtEpochMillis
                    } else {
                        problem.archivedAtEpochMillis
                    },
                tombstonedAtEpochMillis =
                    if (nextState == StudentProblemLifecycleState.TOMBSTONED) {
                        changedAtEpochMillis
                    } else {
                        problem.tombstonedAtEpochMillis
                    },
                updatedAtEpochMillis = changedAtEpochMillis,
            )
        if (updated == problem) return
        check(updateProblem(updated) == 1) {
            "Problem lifecycle update did not affect exactly one row"
        }
        outbox?.let {
            check(it.aggregateId == problem.currentRevisionId) {
                "Lifecycle binding snapshot targets another revision"
            }
            insertOutboxExactlyOnce(it)
        }
        lifecycleOutbox?.let {
            check(it.aggregateId == problem.currentRevisionId) {
                "Lifecycle change event targets another revision"
            }
            insertOutboxExactlyOnce(it)
        }
        bumpChangeVersion(problem.learnerId)
    }

    protected suspend fun insertRevisionAuthority(bundle: CommitStudentProblemBundle) {
        bundle.solutionAnalysis?.let { insertSolutionAnalysis(it) }
        bundle.solutionSteps.insertWhenNotEmpty(::insertSolutionSteps)
        bundle.errorAttributions.insertWhenNotEmpty(::insertErrorAttributions)
        bundle.errorEvidence.insertWhenNotEmpty(::insertErrorEvidence)
    }

    protected suspend fun checkRevisionAuthority(bundle: CommitStudentProblemBundle) {
        check(readSolutionAnalysis(bundle.revision.revisionId) == bundle.solutionAnalysis) {
            "Problem revision was replayed with different solution analysis"
        }
        val persistedSteps =
            bundle.solutionAnalysis?.let { analysis ->
                readSolutionSteps(analysis.solutionAnalysisId, MAX_STORED_SOLUTION_STEPS + 1)
            }.orEmpty()
        check(persistedSteps == bundle.solutionSteps) {
            "Problem revision was replayed with different solution steps"
        }
        check(
            readErrorAttributions(
                bundle.revision.revisionId,
                MAX_STORED_ERROR_ATTRIBUTIONS + 1,
            ) == bundle.errorAttributions,
        ) {
            "Problem revision was replayed with different error attributions"
        }
        check(
            readErrorEvidence(
                bundle.revision.revisionId,
                MAX_STORED_ERROR_EVIDENCE + 1,
            ) == bundle.errorEvidence,
        ) {
            "Problem revision was replayed with different error evidence"
        }
    }

    @Transaction
    open suspend fun recordClassifications(bundle: RecordStudentClassificationsBundle) {
        val revision = checkNotNull(readRevision(bundle.revisionId)) {
            "Cannot classify a missing problem revision"
        }
        check(revision.problemId == bundle.problemId) {
            "Classification revision belongs to another problem"
        }
        val problem = checkNotNull(readProblem(bundle.problemId)) {
            "Classification problem is missing"
        }
        bundle.expectedPreviousBindingSetVersion?.let { expectedVersion ->
            check(readLatestBindingSetVersion(bundle.revisionId) == expectedVersion) {
                "Knowledge binding snapshot version changed while preparing an atomic update"
            }
        }
        val currentAccepted =
            readCurrentClassifications(
                revisionId = bundle.revisionId,
                limit = MAX_STORED_CLASSIFICATIONS + 1,
            )
        check(currentAccepted.size <= MAX_STORED_CLASSIFICATIONS) {
            "Current classification set exceeds the supported budget"
        }
        check(
            currentAccepted.mapTo(sortedSetOf()) { it.classificationId } ==
                bundle.expectedCurrentAcceptedIds.toSortedSet(),
        ) {
            "Classification state changed while preparing an atomic update"
        }
        val referencedIds =
            buildSet {
                bundle.results.forEach { result ->
                    add(result.classificationId)
                    result.supersedesClassificationId?.let(::add)
                }
            }.toList()
        val existingById =
            referencedIds
                .chunked(MAX_SQLITE_BIND_BATCH)
                .flatMap { ids ->
                    readClassificationsByIds(
                        classificationIds = ids,
                        limit = ids.size,
                    )
                }
                .associateBy(StudentProblemClassificationResultEntity::classificationId)
        val newResults =
            bundle.results.filter { result ->
                val existing = existingById[result.classificationId]
                if (existing != null) {
                    check(existing.sameSubmittedClassification(result)) {
                        "Classification id was replayed with different immutable content"
                    }
                }
                existing == null
            }
        newResults.forEach { result ->
            val requiresKnowledgeVerification =
                result.dimension == StudentProblemClassificationDimension.KNOWLEDGE.name &&
                    (
                        result.status == StudentProblemClassificationStatus.ACCEPTED.name ||
                            result.status == StudentProblemClassificationStatus.REVOKED.name
                    )
            check(
                if (requiresKnowledgeVerification) {
                    result.knowledgeManifestFingerprint != null &&
                        result.knowledgeActivationGeneration != null &&
                        result.knowledgeActivationGeneration > 0
                } else {
                    result.knowledgeManifestFingerprint == null &&
                        result.knowledgeActivationGeneration == null
                },
            ) {
                "Knowledge classification verification provenance is invalid"
            }
            result.supersedesClassificationId?.let { supersededId ->
                val superseded = existingById[supersededId]
                checkNotNull(superseded) {
                    "Reclassification references a missing prior classification"
                }
                check(
                    superseded.problemId == result.problemId &&
                        superseded.basisRevisionId == result.basisRevisionId &&
                        superseded.dimension == result.dimension &&
                        superseded.classificationId in bundle.expectedCurrentAcceptedIds &&
                        superseded.recordedAtEpochMillis <= result.recordedAtEpochMillis,
                ) {
                    "Reclassification must explicitly replace or revoke a current result in the same dimension"
                }
                if (result.status == StudentProblemClassificationStatus.REVOKED.name) {
                    check(superseded.sameClassificationReference(result)) {
                        "Revocation must retain the verified reference being revoked"
                    }
                }
            }
        }
        bundle.supersededAcceptedIds.forEach { classificationId ->
            check(classificationId in bundle.expectedCurrentAcceptedIds) {
                "Only a current accepted classification may be superseded"
            }
            check(supersedeAcceptedClassification(classificationId) == 1) {
                "Accepted classification supersession did not affect exactly one row"
            }
        }
        newResults.insertWhenNotEmpty(::insertClassifications)
        check(
            readCurrentClassifications(
                revisionId = bundle.revisionId,
                limit = MAX_STORED_CLASSIFICATIONS + 1,
            ).mapTo(sortedSetOf()) { it.classificationId } ==
                bundle.expectedFinalAcceptedIds.toSortedSet(),
        ) {
            "Atomic classification update produced an unexpected effective set"
        }
        bundle.outbox?.let { insertOutboxExactlyOnce(it) }
        if (newResults.isNotEmpty() || bundle.supersededAcceptedIds.isNotEmpty()) {
            bumpChangeVersion(problem.learnerId)
        }
    }
}
