package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.AppendProblemDraftSourceAssetCommand
import com.tingyun.smartmistakebook.core.database.AppendProblemDraftSourceAssetResult
import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.CommitProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.CommitProblemDraftResult
import com.tingyun.smartmistakebook.core.database.ConfirmAndCommitProblemDraftFromWorkspaceCommand
import com.tingyun.smartmistakebook.core.database.ConfirmProblemOrganizationCommand
import com.tingyun.smartmistakebook.core.database.ConfirmProblemOrganizationResult
import com.tingyun.smartmistakebook.core.database.ConsumeProblemDraftEditWorkspaceCommand
import com.tingyun.smartmistakebook.core.database.CreateProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.ProblemDraftEditWorkspaceRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftEditWorkspaceWriteResult
import com.tingyun.smartmistakebook.core.database.ProblemDraftRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftReplacementResult
import com.tingyun.smartmistakebook.core.database.ProblemDraftSplitResult
import com.tingyun.smartmistakebook.core.database.ProblemDraftWriteResult
import com.tingyun.smartmistakebook.core.database.ReplaceProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.ReviseProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.SaveProblemDraftEditWorkspaceCommand
import com.tingyun.smartmistakebook.core.database.SplitProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.ConfirmedProblemOrganizationRecord
import kotlinx.coroutines.flow.Flow

/**
 * Read-only port for problem draft operations.
 */
interface DraftReadPort {
    fun observePendingProblemDraftCount(): Flow<Int>
}

/**
 * Port for confirmed problem organization.
 */
interface OrganizationReadPort {
    fun observeConfirmedProblemOrganization(
        problemId: String,
        problemRevisionId: String,
    ): Flow<ConfirmedProblemOrganizationRecord>
}

/**
 * Port for problem draft writes and the draft edit workspace.
 */
interface DraftWritePort {
    suspend fun createProblemDraft(command: CreateProblemDraftCommand): ProblemDraftWriteResult

    suspend fun appendProblemDraftSourceAsset(
        command: AppendProblemDraftSourceAssetCommand,
    ): AppendProblemDraftSourceAssetResult

    suspend fun reviseProblemDraft(command: ReviseProblemDraftCommand): ProblemDraftWriteResult

    suspend fun replaceProblemDraft(
        command: ReplaceProblemDraftCommand,
    ): ProblemDraftReplacementResult

    suspend fun splitProblemDraft(
        command: SplitProblemDraftCommand,
    ): ProblemDraftSplitResult

    suspend fun readProblemDraft(draftId: String): ProblemDraftRecord?

    suspend fun readProblemDraftEditWorkspace(
        draftId: String,
    ): ProblemDraftEditWorkspaceRecord?

    suspend fun saveProblemDraftEditWorkspace(
        command: SaveProblemDraftEditWorkspaceCommand,
    ): ProblemDraftEditWorkspaceWriteResult

    suspend fun consumeProblemDraftEditWorkspace(
        command: ConsumeProblemDraftEditWorkspaceCommand,
    ): Boolean

    suspend fun commitProblemDraft(command: CommitProblemDraftCommand): CommitProblemDraftResult

    suspend fun confirmAndCommitProblemDraftFromWorkspace(
        command: ConfirmAndCommitProblemDraftFromWorkspaceCommand,
    ): CommitProblemDraftResult

    /**
     * Attaches a clean-redraw canonical asset to an already-committed problem
     * revision under the CLEAN_IMAGE role. Returns false when the revision
     * does not exist. Idempotent.
     */
    suspend fun attachCleanRedrawAsset(
        revisionId: String,
        asset: CanonicalSourceAssetRecord,
    ): Boolean
}
/**
 * Port for problem organization writes.
 */
interface OrganizationWritePort {
    suspend fun markRelationsStaleForRevision(
        problemRevisionId: String,
        updatedAtEpochMillis: Long,
    ): Int

    suspend fun confirmProblemOrganization(
        command: ConfirmProblemOrganizationCommand,
    ): ConfirmProblemOrganizationResult
}