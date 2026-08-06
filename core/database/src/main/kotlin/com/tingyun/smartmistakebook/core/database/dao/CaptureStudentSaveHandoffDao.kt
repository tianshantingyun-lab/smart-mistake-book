package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.CaptureStudentSaveHandoffConflictException
import com.tingyun.smartmistakebook.core.database.CaptureStudentSaveHandoffIntegrityException
import com.tingyun.smartmistakebook.core.database.CaptureStudentSaveHandoffNotFoundException
import com.tingyun.smartmistakebook.core.database.CaptureStudentSaveHandoffRecord
import com.tingyun.smartmistakebook.core.database.CaptureStudentSaveHandoffState
import com.tingyun.smartmistakebook.core.database.CaptureStudentSaveHandoffWriteOutcome
import com.tingyun.smartmistakebook.core.database.CaptureStudentSaveHandoffWriteResult
import com.tingyun.smartmistakebook.core.database.FinalizeCaptureStudentSaveHandoffCommand
import com.tingyun.smartmistakebook.core.database.PrepareCaptureStudentSaveHandoffCommand
import com.tingyun.smartmistakebook.core.database.ReadPendingCaptureStudentSaveHandoffsQuery
import com.tingyun.smartmistakebook.core.database.entity.CaptureStudentSaveHandoffEntity
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef

@Dao
internal abstract class CaptureStudentSaveHandoffDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(
        entity: CaptureStudentSaveHandoffEntity,
    ): Long

    @Query(
        """
        SELECT intent_id, intent_canonical_fingerprint, learner_id, draft_id,
            draft_revision_number, session_id, target_subject, target_problem_id,
            target_practice_unit_id, target_problem_ref_schema_version, target_revision_id,
            target_revision_number, target_document_canonical_fingerprint,
            target_revision_ref_schema_version, state, prepared_at_epoch_millis,
            finalized_at_epoch_millis, target_save_receipt_fingerprint, state_version,
            schema_version
        FROM capture_student_save_handoff
        WHERE intent_id = :intentId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readEntity(
        intentId: String,
    ): CaptureStudentSaveHandoffEntity?

    @Query(
        """
        SELECT intent_id, intent_canonical_fingerprint, learner_id, draft_id,
            draft_revision_number, session_id, target_subject, target_problem_id,
            target_practice_unit_id, target_problem_ref_schema_version, target_revision_id,
            target_revision_number, target_document_canonical_fingerprint,
            target_revision_ref_schema_version, state, prepared_at_epoch_millis,
            finalized_at_epoch_millis, target_save_receipt_fingerprint, state_version,
            schema_version
        FROM capture_student_save_handoff
        WHERE learner_id = :learnerId AND state = 'PREPARED'
        ORDER BY prepared_at_epoch_millis ASC, intent_id ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readPendingEntities(
        learnerId: String,
        limit: Int,
    ): List<CaptureStudentSaveHandoffEntity>

    @Query(
        """
        UPDATE capture_student_save_handoff
        SET state = 'FINALIZED',
            finalized_at_epoch_millis = :finalizedAtEpochMillis,
            target_save_receipt_fingerprint = :targetSaveReceiptFingerprint,
            state_version = 2
        WHERE intent_id = :intentId
            AND learner_id = :learnerId
            AND intent_canonical_fingerprint = :intentCanonicalFingerprint
            AND state = 'PREPARED'
            AND state_version = :expectedStateVersion
        """,
    )
    protected abstract suspend fun finalizePrepared(
        intentId: String,
        learnerId: String,
        intentCanonicalFingerprint: String,
        targetSaveReceiptFingerprint: String,
        finalizedAtEpochMillis: Long,
        expectedStateVersion: Long,
    ): Int

    @Transaction
    open suspend fun prepare(
        command: PrepareCaptureStudentSaveHandoffCommand,
    ): CaptureStudentSaveHandoffWriteResult {
        val candidate = command.toPreparedEntity()
        val inserted = insertIfAbsent(candidate) != -1L
        val durable = readEntity(command.intentId)
            ?: throw CaptureStudentSaveHandoffIntegrityException(
                "Prepared capture save handoff was not readable",
            )
        if (!durable.matchesPrepared(candidate)) {
            throw CaptureStudentSaveHandoffConflictException(command.intentId)
        }
        return CaptureStudentSaveHandoffWriteResult(
            outcome = if (inserted) {
                CaptureStudentSaveHandoffWriteOutcome.INSERTED
            } else {
                CaptureStudentSaveHandoffWriteOutcome.REPLAYED
            },
            record = durable.toRecord(),
        )
    }

    @Transaction
    open suspend fun finalize(
        command: FinalizeCaptureStudentSaveHandoffCommand,
    ): CaptureStudentSaveHandoffWriteResult {
        val before = readEntity(command.intentId)
            ?: throw CaptureStudentSaveHandoffNotFoundException(command.intentId)
        if (
            before.learnerId != command.learnerId ||
            before.intentCanonicalFingerprint != command.intentCanonicalFingerprint
        ) {
            throw CaptureStudentSaveHandoffConflictException(command.intentId)
        }
        if (command.finalizedAtEpochMillis < before.preparedAtEpochMillis) {
            throw CaptureStudentSaveHandoffConflictException(command.intentId)
        }
        if (before.state == CaptureStudentSaveHandoffState.FINALIZED.name) {
            if (
                before.targetSaveReceiptFingerprint == command.targetSaveReceiptFingerprint &&
                before.stateVersion == CaptureStudentSaveHandoffRecord.FINALIZED_STATE_VERSION
            ) {
                return CaptureStudentSaveHandoffWriteResult(
                    outcome = CaptureStudentSaveHandoffWriteOutcome.REPLAYED,
                    record = before.toRecord(),
                )
            }
            throw CaptureStudentSaveHandoffConflictException(command.intentId)
        }
        if (
            before.state != CaptureStudentSaveHandoffState.PREPARED.name ||
            before.stateVersion != command.expectedStateVersion
        ) {
            throw CaptureStudentSaveHandoffIntegrityException(
                "Capture save handoff has an invalid durable state",
            )
        }

        val updated = finalizePrepared(
            intentId = command.intentId,
            learnerId = command.learnerId,
            intentCanonicalFingerprint = command.intentCanonicalFingerprint,
            targetSaveReceiptFingerprint = command.targetSaveReceiptFingerprint,
            finalizedAtEpochMillis = command.finalizedAtEpochMillis,
            expectedStateVersion = command.expectedStateVersion,
        )
        val durable = readEntity(command.intentId)
            ?: throw CaptureStudentSaveHandoffIntegrityException(
                "Finalized capture save handoff was not readable",
            )
        if (updated != 1) {
            if (
                durable.state == CaptureStudentSaveHandoffState.FINALIZED.name &&
                durable.targetSaveReceiptFingerprint == command.targetSaveReceiptFingerprint
            ) {
                return CaptureStudentSaveHandoffWriteResult(
                    outcome = CaptureStudentSaveHandoffWriteOutcome.REPLAYED,
                    record = durable.toRecord(),
                )
            }
            throw CaptureStudentSaveHandoffConflictException(command.intentId)
        }
        return CaptureStudentSaveHandoffWriteResult(
            outcome = CaptureStudentSaveHandoffWriteOutcome.TRANSITIONED,
            record = durable.toRecord(),
        )
    }

    @Transaction
    open suspend fun readPending(
        query: ReadPendingCaptureStudentSaveHandoffsQuery,
    ): List<CaptureStudentSaveHandoffRecord> =
        readPendingEntities(query.learnerId, query.limit).map { entity -> entity.toRecord() }
}

private fun PrepareCaptureStudentSaveHandoffCommand.toPreparedEntity() =
    CaptureStudentSaveHandoffEntity(
        intentId = intentId,
        intentCanonicalFingerprint = intentCanonicalFingerprint,
        learnerId = learnerId,
        draftId = draftId,
        draftRevisionNumber = draftRevisionNumber,
        sessionId = sessionId,
        targetSubject = targetProblemRef.subject.name,
        targetProblemId = targetProblemRef.problemId,
        targetPracticeUnitId = targetProblemRef.practiceUnitId,
        targetProblemRefSchemaVersion = targetProblemRef.schemaVersion,
        targetRevisionId = targetProblemRevisionRef.revisionId,
        targetRevisionNumber = targetProblemRevisionRef.revisionNumber,
        targetDocumentCanonicalFingerprint =
            targetProblemRevisionRef.documentCanonicalFingerprint,
        targetRevisionRefSchemaVersion = targetProblemRevisionRef.schemaVersion,
        state = CaptureStudentSaveHandoffState.PREPARED.name,
        preparedAtEpochMillis = preparedAtEpochMillis,
        finalizedAtEpochMillis = null,
        targetSaveReceiptFingerprint = null,
        stateVersion = CaptureStudentSaveHandoffRecord.PREPARED_STATE_VERSION,
        schemaVersion = schemaVersion,
    )

private fun CaptureStudentSaveHandoffEntity.matchesPrepared(
    candidate: CaptureStudentSaveHandoffEntity,
): Boolean =
    intentId == candidate.intentId &&
        intentCanonicalFingerprint == candidate.intentCanonicalFingerprint &&
        learnerId == candidate.learnerId &&
        draftId == candidate.draftId &&
        draftRevisionNumber == candidate.draftRevisionNumber &&
        sessionId == candidate.sessionId &&
        targetSubject == candidate.targetSubject &&
        targetProblemId == candidate.targetProblemId &&
        targetPracticeUnitId == candidate.targetPracticeUnitId &&
        targetProblemRefSchemaVersion == candidate.targetProblemRefSchemaVersion &&
        targetRevisionId == candidate.targetRevisionId &&
        targetRevisionNumber == candidate.targetRevisionNumber &&
        targetDocumentCanonicalFingerprint == candidate.targetDocumentCanonicalFingerprint &&
        targetRevisionRefSchemaVersion == candidate.targetRevisionRefSchemaVersion &&
        schemaVersion == candidate.schemaVersion

private fun CaptureStudentSaveHandoffEntity.toRecord(): CaptureStudentSaveHandoffRecord {
    try {
        val problemRef = StudentProblemRef(
            learnerId = learnerId,
            subject = SubjectKind.valueOf(targetSubject),
            problemId = targetProblemId,
            practiceUnitId = targetPracticeUnitId,
            schemaVersion = targetProblemRefSchemaVersion,
        )
        val revisionRef = StudentProblemRevisionRef(
            problem = problemRef,
            revisionId = targetRevisionId,
            revisionNumber = targetRevisionNumber,
            documentCanonicalFingerprint = targetDocumentCanonicalFingerprint,
            schemaVersion = targetRevisionRefSchemaVersion,
        )
        return CaptureStudentSaveHandoffRecord(
            intentId = intentId,
            intentCanonicalFingerprint = intentCanonicalFingerprint,
            learnerId = learnerId,
            draftId = draftId,
            draftRevisionNumber = draftRevisionNumber,
            sessionId = sessionId,
            targetProblemRef = problemRef,
            targetProblemRevisionRef = revisionRef,
            state = CaptureStudentSaveHandoffState.valueOf(state),
            preparedAtEpochMillis = preparedAtEpochMillis,
            finalizedAtEpochMillis = finalizedAtEpochMillis,
            targetSaveReceiptFingerprint = targetSaveReceiptFingerprint,
            stateVersion = stateVersion,
            schemaVersion = schemaVersion,
        )
    } catch (failure: IllegalArgumentException) {
        throw CaptureStudentSaveHandoffIntegrityException(
            "Capture save handoff contains invalid persisted data",
            failure,
        )
    }
}
