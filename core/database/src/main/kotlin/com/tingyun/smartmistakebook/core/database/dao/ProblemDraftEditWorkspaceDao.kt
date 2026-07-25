package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.ConsumeProblemDraftEditWorkspaceCommand
import com.tingyun.smartmistakebook.core.database.DatabaseContractValidator
import com.tingyun.smartmistakebook.core.database.ExpectedProblemDraftEditWorkspace
import com.tingyun.smartmistakebook.core.database.ProblemDraftEditWorkspaceConflictException
import com.tingyun.smartmistakebook.core.database.ProblemDraftEditWorkspaceIntegrityException
import com.tingyun.smartmistakebook.core.database.ProblemDraftEditWorkspaceRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftEditWorkspaceWriteResult
import com.tingyun.smartmistakebook.core.database.SaveProblemDraftEditWorkspaceCommand
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftEditWorkspaceEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftRevisionEntity

@Dao
internal abstract class ProblemDraftEditWorkspaceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(entity: ProblemDraftEditWorkspaceEntity): Long

    @Query("SELECT * FROM problem_draft_edit_snapshot WHERE draft_id = :draftId LIMIT 1")
    protected abstract suspend fun find(draftId: String): ProblemDraftEditWorkspaceEntity?

    @Query("SELECT * FROM problem_draft WHERE draft_id = :draftId LIMIT 1")
    protected abstract suspend fun findDraft(draftId: String): ProblemDraftEntity?

    @Query(
        """
        SELECT * FROM problem_draft_revision
        WHERE draft_id = :draftId AND revision_number = :revisionNumber
        LIMIT 1
        """,
    )
    protected abstract suspend fun findRevision(
        draftId: String,
        revisionNumber: Int,
    ): ProblemDraftRevisionEntity?

    @Query(
        """
        UPDATE problem_draft_edit_snapshot
        SET workspace_version = :nextWorkspaceVersion,
            snapshot_schema_version = :snapshotSchemaVersion,
            workspace_snapshot = :workspaceSnapshot,
            workspace_fingerprint = :nextWorkspaceFingerprint,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE draft_id = :draftId
          AND basis_revision_number = :basisRevisionNumber
          AND workspace_version = :expectedWorkspaceVersion
          AND workspace_fingerprint = :expectedWorkspaceFingerprint
        """,
    )
    protected abstract suspend fun updateCas(
        draftId: String,
        basisRevisionNumber: Int,
        expectedWorkspaceVersion: Long,
        expectedWorkspaceFingerprint: String,
        nextWorkspaceVersion: Long,
        snapshotSchemaVersion: Int,
        workspaceSnapshot: String,
        nextWorkspaceFingerprint: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        DELETE FROM problem_draft_edit_snapshot
        WHERE draft_id = :draftId
          AND basis_revision_number = :basisRevisionNumber
          AND workspace_version = :expectedWorkspaceVersion
          AND workspace_fingerprint = :expectedWorkspaceFingerprint
        """,
    )
    protected abstract suspend fun deleteCas(
        draftId: String,
        basisRevisionNumber: Int,
        expectedWorkspaceVersion: Long,
        expectedWorkspaceFingerprint: String,
    ): Int

    @Query("SELECT EXISTS(SELECT 1 FROM tutor_session WHERE draft_id = :draftId)")
    protected abstract suspend fun hasTutorSession(draftId: String): Boolean

    @Transaction
    open suspend fun read(draftId: String): ProblemDraftEditWorkspaceRecord? {
        require(draftId.isNotBlank()) { "draftId must not be blank" }
        val entity = find(draftId) ?: return null
        validatePersisted(entity)
        return entity.toRecord()
    }

    @Transaction
    open suspend fun save(
        command: SaveProblemDraftEditWorkspaceCommand,
    ): ProblemDraftEditWorkspaceWriteResult {
        val incomingWorkspace = DatabaseContractValidator
            .validateSaveProblemDraftEditWorkspace(command)
        val draft = findDraft(command.draftId)
            ?: throw ProblemDraftEditWorkspaceConflictException(
                "Problem draft ${command.draftId} no longer exists",
            )
        if (
            draft.status != StudyDbValue.ProblemDraftStatus.EDITING ||
            draft.currentRevisionNumber != command.basisRevisionNumber ||
            hasTutorSession(command.draftId)
        ) {
            throw ProblemDraftEditWorkspaceConflictException(
                "Problem draft ${command.draftId} is not editing the expected revision",
            )
        }
        val basisRevision = findRevision(command.draftId, command.basisRevisionNumber)
            ?: throw ProblemDraftEditWorkspaceIntegrityException(
                "Problem draft ${command.draftId} has no workspace basis revision",
            )
        validateIncomingBinding(
            command = command,
            draft = draft,
            basisRevision = basisRevision,
            baseCandidateFingerprint = incomingWorkspace.baseCandidateFingerprint,
            sourceAssetIds = incomingWorkspace.workingDocument.blockEvidence
                .map { it.sourceAssetId },
        )

        val existing = find(command.draftId)
        if (existing == null) {
            if (
                command.expectedWorkspaceVersion != 0L ||
                command.expectedWorkspaceFingerprint != null
            ) {
                throw conflict(command.draftId)
            }
            val entity = ProblemDraftEditWorkspaceEntity(
                draftId = command.draftId,
                basisRevisionNumber = command.basisRevisionNumber,
                workspaceVersion = 1,
                snapshotSchemaVersion = command.snapshotSchemaVersion,
                workspaceSnapshot = command.workspaceSnapshot,
                workspaceFingerprint = command.workspaceFingerprint,
                createdAtEpochMillis = command.updatedAtEpochMillis,
                updatedAtEpochMillis = command.updatedAtEpochMillis,
            )
            if (insert(entity) == -1L) {
                val winner = find(command.draftId) ?: throw conflict(command.draftId)
                validatePersisted(winner)
                if (!winner.hasSamePayload(command)) throw conflict(command.draftId)
                return ProblemDraftEditWorkspaceWriteResult(
                    created = false,
                    workspace = winner.toRecord(),
                )
            }
            return ProblemDraftEditWorkspaceWriteResult(
                created = true,
                workspace = entity.toRecord(),
            )
        }

        validatePersisted(existing)
        if (existing.hasSamePayload(command)) {
            return ProblemDraftEditWorkspaceWriteResult(
                created = false,
                workspace = existing.toRecord(),
            )
        }
        if (
            command.expectedWorkspaceVersion != existing.workspaceVersion ||
            command.expectedWorkspaceFingerprint != existing.workspaceFingerprint ||
            command.updatedAtEpochMillis < existing.updatedAtEpochMillis
        ) {
            throw conflict(command.draftId)
        }
        val nextVersion = existing.workspaceVersion + 1
        if (nextVersion <= existing.workspaceVersion) throw conflict(command.draftId)
        if (
            updateCas(
                draftId = command.draftId,
                basisRevisionNumber = command.basisRevisionNumber,
                expectedWorkspaceVersion = existing.workspaceVersion,
                expectedWorkspaceFingerprint = existing.workspaceFingerprint,
                nextWorkspaceVersion = nextVersion,
                snapshotSchemaVersion = command.snapshotSchemaVersion,
                workspaceSnapshot = command.workspaceSnapshot,
                nextWorkspaceFingerprint = command.workspaceFingerprint,
                updatedAtEpochMillis = command.updatedAtEpochMillis,
            ) != 1
        ) {
            val winner = find(command.draftId) ?: throw conflict(command.draftId)
            validatePersisted(winner)
            if (!winner.hasSamePayload(command)) throw conflict(command.draftId)
            return ProblemDraftEditWorkspaceWriteResult(
                created = false,
                workspace = winner.toRecord(),
            )
        }
        return ProblemDraftEditWorkspaceWriteResult(
            created = true,
            workspace = checkNotNull(find(command.draftId)).toRecord(),
        )
    }

    @Transaction
    open suspend fun consume(command: ConsumeProblemDraftEditWorkspaceCommand): Boolean {
        DatabaseContractValidator.validateConsumeProblemDraftEditWorkspace(command)
        val existing = find(command.draftId) ?: return false
        validatePersisted(existing)
        if (
            existing.basisRevisionNumber != command.basisRevisionNumber ||
            existing.workspaceVersion != command.expectedWorkspaceVersion ||
            existing.workspaceFingerprint != command.expectedWorkspaceFingerprint
        ) {
            throw conflict(command.draftId)
        }
        if (
            deleteCas(
                draftId = command.draftId,
                basisRevisionNumber = command.basisRevisionNumber,
                expectedWorkspaceVersion = command.expectedWorkspaceVersion,
                expectedWorkspaceFingerprint = command.expectedWorkspaceFingerprint,
            ) != 1
        ) {
            throw conflict(command.draftId)
        }
        return true
    }

    @Transaction
    open suspend fun requireExactForConfirmation(
        expected: ExpectedProblemDraftEditWorkspace,
    ): ProblemDraftEditWorkspaceRecord {
        DatabaseContractValidator.validateExpectedProblemDraftEditWorkspace(expected)
        val existing = find(expected.draftId) ?: throw conflict(expected.draftId)
        validatePersisted(existing)
        if (!existing.matches(expected)) throw conflict(expected.draftId)
        return existing.toRecord()
    }

    @Transaction
    open suspend fun deleteExactAfterFinalization(
        command: ConsumeProblemDraftEditWorkspaceCommand,
    ) {
        DatabaseContractValidator.validateConsumeProblemDraftEditWorkspace(command)
        val existing = find(command.draftId) ?: throw conflict(command.draftId)
        if (
            existing.basisRevisionNumber != command.basisRevisionNumber ||
            existing.workspaceVersion != command.expectedWorkspaceVersion ||
            existing.workspaceFingerprint != command.expectedWorkspaceFingerprint ||
            deleteCas(
                draftId = command.draftId,
                basisRevisionNumber = command.basisRevisionNumber,
                expectedWorkspaceVersion = command.expectedWorkspaceVersion,
                expectedWorkspaceFingerprint = command.expectedWorkspaceFingerprint,
            ) != 1
        ) {
            throw conflict(command.draftId)
        }
    }

    private suspend fun validatePersisted(entity: ProblemDraftEditWorkspaceEntity) {
        val draft = findDraft(entity.draftId)
            ?: throw integrity(entity.draftId, "draft is missing")
        if (
            draft.status != StudyDbValue.ProblemDraftStatus.EDITING ||
            draft.currentRevisionNumber != entity.basisRevisionNumber
        ) {
            throw integrity(entity.draftId, "basis or draft status is stale")
        }
        val revision = findRevision(entity.draftId, entity.basisRevisionNumber)
            ?: throw integrity(entity.draftId, "basis revision is missing")
        val workspace = try {
            DatabaseContractValidator.decodeProblemDraftEditWorkspace(
                snapshotSchemaVersion = entity.snapshotSchemaVersion,
                workspaceSnapshot = entity.workspaceSnapshot,
                workspaceFingerprint = entity.workspaceFingerprint,
            )
        } catch (failure: Exception) {
            throw ProblemDraftEditWorkspaceIntegrityException(
                "Problem-draft workspace ${entity.draftId} is corrupted",
                failure,
            )
        }
        if (
            entity.workspaceVersion <= 0 ||
            entity.createdAtEpochMillis < revision.createdAtEpochMillis ||
            entity.updatedAtEpochMillis < entity.createdAtEpochMillis ||
            workspace.baseCandidateFingerprint != revision.documentFingerprint ||
            workspace.workingDocument.blockEvidence.any {
                it.sourceAssetId != draft.sourceAssetId
            }
        ) {
            throw integrity(entity.draftId, "metadata or candidate binding is invalid")
        }
    }

    private fun validateIncomingBinding(
        command: SaveProblemDraftEditWorkspaceCommand,
        draft: ProblemDraftEntity,
        basisRevision: ProblemDraftRevisionEntity,
        baseCandidateFingerprint: String,
        sourceAssetIds: List<String>,
    ) {
        if (
            command.updatedAtEpochMillis < basisRevision.createdAtEpochMillis ||
            baseCandidateFingerprint != basisRevision.documentFingerprint ||
            sourceAssetIds.any { it != draft.sourceAssetId }
        ) {
            throw ProblemDraftEditWorkspaceConflictException(
                "Problem-draft workspace ${command.draftId} is bound to another candidate",
            )
        }
    }

    private fun ProblemDraftEditWorkspaceEntity.hasSamePayload(
        command: SaveProblemDraftEditWorkspaceCommand,
    ): Boolean = basisRevisionNumber == command.basisRevisionNumber &&
        snapshotSchemaVersion == command.snapshotSchemaVersion &&
        workspaceSnapshot == command.workspaceSnapshot &&
        workspaceFingerprint == command.workspaceFingerprint

    private fun ProblemDraftEditWorkspaceEntity.matches(
        expected: ExpectedProblemDraftEditWorkspace,
    ): Boolean = draftId == expected.draftId &&
        basisRevisionNumber == expected.basisRevisionNumber &&
        workspaceVersion == expected.workspaceVersion &&
        workspaceFingerprint == expected.workspaceFingerprint

    private fun conflict(draftId: String) = ProblemDraftEditWorkspaceConflictException(
        "Problem-draft workspace $draftId changed concurrently",
    )

    private fun integrity(draftId: String, detail: String) =
        ProblemDraftEditWorkspaceIntegrityException(
            "Problem-draft workspace $draftId failed integrity validation: $detail",
        )
}

internal fun ProblemDraftEditWorkspaceEntity.toRecord() = ProblemDraftEditWorkspaceRecord(
    draftId = draftId,
    basisRevisionNumber = basisRevisionNumber,
    workspaceVersion = workspaceVersion,
    snapshotSchemaVersion = snapshotSchemaVersion,
    workspaceSnapshot = workspaceSnapshot,
    workspaceFingerprint = workspaceFingerprint,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)
