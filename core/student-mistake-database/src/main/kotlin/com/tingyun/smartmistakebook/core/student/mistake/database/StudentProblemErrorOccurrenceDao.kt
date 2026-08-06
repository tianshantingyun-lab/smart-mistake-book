package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint

internal data class StudentProblemErrorOccurrenceBundle(
    val occurrence: StudentProblemErrorOccurrenceEntity,
    val evidence: List<StudentProblemErrorOccurrenceEvidenceEntity>,
)

internal data class StudentProblemErrorOccurrenceDaoResult(
    val created: Boolean,
    val bundle: StudentProblemErrorOccurrenceBundle,
)

@Dao
internal abstract class StudentProblemErrorOccurrenceDao {
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
        LIMIT 1
        """,
    )
    protected abstract suspend fun readOccurrenceById(
        occurrenceId: String,
    ): StudentProblemErrorOccurrenceEntity?

    @Query(
        """
        SELECT occurrence_id, idempotency_key, schema_version, learner_id, subject,
               problem_id, practice_unit_id, basis_revision_id, basis_revision_number,
               basis_document_canonical_fingerprint, batch_canonical_fingerprint,
               import_source_canonical_fingerprint, occurred_at_epoch_millis,
               imported_at_epoch_millis, attribution_status, evidence_count,
               occurrence_canonical_fingerprint
        FROM student_problem_error_occurrence
        WHERE learner_id = :learnerId
          AND idempotency_key = :idempotencyKey
        LIMIT 1
        """,
    )
    protected abstract suspend fun readOccurrenceByIdempotencyKey(
        learnerId: String,
        idempotencyKey: String,
    ): StudentProblemErrorOccurrenceEntity?

    @Query(
        """
        SELECT occurrence_id, idempotency_key, schema_version, learner_id, subject,
               problem_id, practice_unit_id, basis_revision_id, basis_revision_number,
               basis_document_canonical_fingerprint, batch_canonical_fingerprint,
               import_source_canonical_fingerprint, occurred_at_epoch_millis,
               imported_at_epoch_millis, attribution_status, evidence_count,
               occurrence_canonical_fingerprint
        FROM student_problem_error_occurrence
        WHERE learner_id = :learnerId
          AND batch_canonical_fingerprint = :batchCanonicalFingerprint
          AND basis_revision_id = :basisRevisionId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readOccurrenceByBatchRevision(
        learnerId: String,
        batchCanonicalFingerprint: String,
        basisRevisionId: String,
    ): StudentProblemErrorOccurrenceEntity?

    @Query(
        """
        SELECT occurrence_id, idempotency_key, schema_version, learner_id, subject,
               problem_id, practice_unit_id, basis_revision_id, basis_revision_number,
               basis_document_canonical_fingerprint, batch_canonical_fingerprint,
               import_source_canonical_fingerprint, occurred_at_epoch_millis,
               imported_at_epoch_millis, attribution_status, evidence_count,
               occurrence_canonical_fingerprint
        FROM student_problem_error_occurrence
        WHERE basis_revision_id = :basisRevisionId
        ORDER BY occurred_at_epoch_millis DESC,
                 imported_at_epoch_millis DESC,
                 occurrence_id ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readHistoryRows(
        basisRevisionId: String,
        limit: Int,
    ): List<StudentProblemErrorOccurrenceEntity>

    @Query(
        """
        SELECT occurrence_id, ordinal, block_id, source_asset_id, evidence_kind
        FROM student_problem_error_occurrence_evidence
        WHERE occurrence_id = :occurrenceId
        ORDER BY ordinal ASC
        """,
    )
    protected abstract suspend fun readEvidence(
        occurrenceId: String,
    ): List<StudentProblemErrorOccurrenceEvidenceEntity>

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
        WHERE revision.revision_id = :revisionId
        LIMIT 1
        """,
    )
    abstract suspend fun readExactRevision(
        revisionId: String,
    ): PersistedStudentProblemRevisionRefRow?

    @Query(
        """
        SELECT revision_id, problem_id, revision_number, title, stem_markdown,
               captured_question_document_wire, document_canonical_fingerprint,
               created_at_epoch_millis, updated_at_epoch_millis
        FROM student_problem_revision
        WHERE revision_id = :revisionId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readRevision(
        revisionId: String,
    ): StudentProblemRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertOccurrence(
        occurrence: StudentProblemErrorOccurrenceEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEvidence(
        evidence: List<StudentProblemErrorOccurrenceEvidenceEntity>,
    )

    @Query(
        """
        INSERT INTO student_learner_change(
            learner_id,
            change_version
        )
        VALUES(:learnerId, 1)
        ON CONFLICT(learner_id) DO UPDATE SET
            change_version = student_learner_change.change_version + 1
        """,
    )
    protected abstract suspend fun bumpLearnerChangeVersion(learnerId: String)

    @Transaction
    open suspend fun append(
        bundle: StudentProblemErrorOccurrenceBundle,
    ): StudentProblemErrorOccurrenceDaoResult {
        requireBundleIntegrity(bundle)
        val expected = bundle.occurrence
        val target =
            readExactRevision(expected.basisRevisionId)
                ?: errorOccurrenceConflict("Error occurrence target revision does not exist")
        requireExactTarget(target, expected)
        requireCapturedEvidence(bundle)
        readReplayCandidate(expected)?.let { existing ->
            requireExactReplay(existing, bundle)
            return StudentProblemErrorOccurrenceDaoResult(
                created = false,
                bundle = StudentProblemErrorOccurrenceBundle(
                    occurrence = existing,
                    evidence = readEvidence(existing.occurrenceId),
                ),
            )
        }
        if (target.lifecycleState == StudentProblemLifecycleState.TOMBSTONED.name) {
            errorOccurrenceConflict("A tombstoned problem cannot receive a new error occurrence")
        }
        if (insertOccurrence(expected) == -1L) {
            val winner =
                readReplayCandidate(expected)
                    ?: errorOccurrenceConflict(
                        "Error occurrence lost an idempotency race without a winner",
                    )
            requireExactReplay(winner, bundle)
            return StudentProblemErrorOccurrenceDaoResult(
                created = false,
                bundle = StudentProblemErrorOccurrenceBundle(
                    occurrence = winner,
                    evidence = readEvidence(winner.occurrenceId),
                ),
            )
        }
        if (bundle.evidence.isNotEmpty()) {
            insertEvidence(bundle.evidence)
        }
        val storedEvidence = readEvidence(expected.occurrenceId)
        if (storedEvidence != bundle.evidence) {
            errorOccurrenceConflict("Atomic error-occurrence write produced incomplete evidence")
        }
        bumpLearnerChangeVersion(expected.learnerId)
        return StudentProblemErrorOccurrenceDaoResult(
            created = true,
            bundle = StudentProblemErrorOccurrenceBundle(
                occurrence = expected,
                evidence = storedEvidence,
            ),
        )
    }

    @Transaction
    open suspend fun readHistory(
        basisRevisionId: String,
        limit: Int,
    ): List<StudentProblemErrorOccurrenceBundle> =
        readHistoryRows(basisRevisionId, limit).map { occurrence ->
            val evidence = readEvidence(occurrence.occurrenceId)
            if (evidence.size != occurrence.evidenceCount) {
                errorOccurrenceConflict(
                    "Persisted error occurrence has incomplete evidence history",
                )
            }
            StudentProblemErrorOccurrenceBundle(
                occurrence = occurrence,
                evidence = evidence,
            )
        }

    private fun requireBundleIntegrity(
        bundle: StudentProblemErrorOccurrenceBundle,
    ) {
        val occurrence = bundle.occurrence
        if (
            occurrence.schemaVersion != STUDENT_PROBLEM_ERROR_OCCURRENCE_SCHEMA_VERSION ||
            occurrence.evidenceCount != bundle.evidence.size ||
            bundle.evidence.indices.any { index ->
                val evidence = bundle.evidence[index]
                evidence.occurrenceId != occurrence.occurrenceId ||
                    evidence.ordinal != index
            } ||
            bundle.evidence
                .map { Triple(it.blockId, it.sourceAssetId, it.evidenceKind) }
                .distinct()
                .size != bundle.evidence.size
        ) {
            errorOccurrenceConflict(
                "Error occurrence bundle crosses an immutable occurrence boundary",
            )
        }
    }

    private fun requireExactTarget(
        target: PersistedStudentProblemRevisionRefRow,
        expected: StudentProblemErrorOccurrenceEntity,
    ) {
        if (
            target.learnerId != expected.learnerId ||
            target.subject != expected.subject ||
            target.problemId != expected.problemId ||
            target.practiceUnitId != expected.practiceUnitId ||
            target.revisionId != expected.basisRevisionId ||
            target.revisionNumber != expected.basisRevisionNumber ||
            target.documentCanonicalFingerprint !=
            expected.basisDocumentCanonicalFingerprint
        ) {
            errorOccurrenceConflict(
                "Error occurrence target does not match the exact persisted learner revision",
            )
        }
    }

    private suspend fun requireCapturedEvidence(
        bundle: StudentProblemErrorOccurrenceBundle,
    ) {
        if (bundle.evidence.isEmpty()) return
        val occurrence = bundle.occurrence
        val revision =
            readRevision(occurrence.basisRevisionId)
                ?: errorOccurrenceConflict("Error occurrence target revision disappeared")
        val capturedWire =
            revision.capturedQuestionDocumentWire
                ?: errorOccurrenceConflict(
                    "Error occurrence evidence requires a captured-question snapshot",
                )
        val captured =
            runCatching {
                CapturedQuestionDocumentCodec.decode(capturedWire)
            }.getOrElse {
                errorOccurrenceConflict(
                    "Persisted captured-question snapshot is unreadable",
                )
            }
        if (
            CapturedQuestionDocumentFingerprint.of(captured) !=
            occurrence.basisDocumentCanonicalFingerprint
        ) {
            errorOccurrenceConflict(
                "Captured-question snapshot does not match the exact occurrence revision",
            )
        }
        val capturedEvidence =
            captured.blockEvidence.mapTo(hashSetOf()) {
                it.blockId to it.sourceAssetId
            }
        if (
            bundle.evidence.any {
                it.blockId to it.sourceAssetId !in capturedEvidence
            }
        ) {
            errorOccurrenceConflict(
                "Error occurrence references evidence outside its exact captured revision",
            )
        }
    }

    private suspend fun readReplayCandidate(
        expected: StudentProblemErrorOccurrenceEntity,
    ): StudentProblemErrorOccurrenceEntity? {
        val candidates =
            listOfNotNull(
                readOccurrenceById(expected.occurrenceId),
                readOccurrenceByIdempotencyKey(
                    learnerId = expected.learnerId,
                    idempotencyKey = expected.idempotencyKey,
                ),
                readOccurrenceByBatchRevision(
                    learnerId = expected.learnerId,
                    batchCanonicalFingerprint = expected.batchCanonicalFingerprint,
                    basisRevisionId = expected.basisRevisionId,
                ),
            ).distinctBy(StudentProblemErrorOccurrenceEntity::occurrenceId)
        if (candidates.size > 1) {
            errorOccurrenceConflict(
                "Error occurrence immutable keys resolve to different history rows",
            )
        }
        return candidates.singleOrNull()
    }

    private suspend fun requireExactReplay(
        existing: StudentProblemErrorOccurrenceEntity,
        expected: StudentProblemErrorOccurrenceBundle,
    ) {
        if (
            existing != expected.occurrence ||
            readEvidence(existing.occurrenceId) != expected.evidence
        ) {
            errorOccurrenceConflict(
                "Error occurrence was replayed with different immutable content",
            )
        }
    }
}

private fun errorOccurrenceConflict(message: String): Nothing =
    throw StudentProblemErrorOccurrenceConflictException(message)
