package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftCommitReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemErrorAttributionCandidateEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemErrorCandidateEvidenceEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemOrganizationWorkEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemSolutionStepEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemStepKnowledgeBindingEntity
import kotlinx.coroutines.flow.Flow

internal data class ProblemOrganizationWorkPreparationRow(
    @Embedded
    val work: ProblemOrganizationWorkEntity,
    @Embedded(prefix = "receipt_")
    val receipt: ProblemDraftCommitReceiptEntity,
)

@Dao
internal abstract class ProblemOrganizationWorkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertWork(work: ProblemOrganizationWorkEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSolutionSteps(steps: List<ProblemSolutionStepEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertStepKnowledgeBindings(
        bindings: List<ProblemStepKnowledgeBindingEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertErrorAttributionCandidates(
        candidates: List<ProblemErrorAttributionCandidateEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertErrorCandidateEvidence(
        evidence: List<ProblemErrorCandidateEvidenceEntity>,
    )

    @Query("SELECT * FROM problem_organization_work WHERE work_id = :workId LIMIT 1")
    abstract suspend fun readWork(workId: String): ProblemOrganizationWorkEntity?

    @Query(
        """
        SELECT * FROM problem_organization_work
        WHERE commit_receipt_command_id = :commitReceiptCommandId
        LIMIT 1
        """,
    )
    abstract suspend fun readWorkByCommitReceipt(
        commitReceiptCommandId: String,
    ): ProblemOrganizationWorkEntity?

    @Query(
        """
        SELECT * FROM problem_organization_work
        WHERE request_id = :requestId
        LIMIT 1
        """,
    )
    abstract suspend fun readWorkByRequestId(
        requestId: String,
    ): ProblemOrganizationWorkEntity?

    @Query(
        """
        SELECT * FROM problem_draft_commit_receipt
        WHERE command_id = :commitReceiptCommandId
        LIMIT 1
        """,
    )
    abstract suspend fun readCommitReceipt(
        commitReceiptCommandId: String,
    ): ProblemDraftCommitReceiptEntity?

    @Query(
        """
        SELECT work.*,
               receipt.command_id AS receipt_command_id,
               receipt.payload_fingerprint AS receipt_payload_fingerprint,
               receipt.draft_id AS receipt_draft_id,
               receipt.draft_revision_number AS receipt_draft_revision_number,
               receipt.problem_id AS receipt_problem_id,
               receipt.problem_revision_id AS receipt_problem_revision_id,
               receipt.practice_unit_id AS receipt_practice_unit_id,
               receipt.error_book_entry_id AS receipt_error_book_entry_id,
               receipt.committed_at_epoch_millis AS receipt_committed_at_epoch_millis
        FROM problem_organization_work AS work
        INNER JOIN problem_draft_commit_receipt AS receipt
          ON receipt.command_id = work.commit_receipt_command_id
        WHERE work.status = 'WAITING_AUTHORIZATION'
          AND work.request_id IS NULL
          AND work.request_snapshot IS NULL
          AND receipt.problem_id = :problemId
          AND receipt.problem_revision_id = :problemRevisionId
          AND receipt.error_book_entry_id = :errorBookEntryId
        ORDER BY receipt.committed_at_epoch_millis DESC,
                 work.created_at_epoch_millis DESC,
                 work.work_id DESC
        LIMIT 1
        """,
    )
    abstract suspend fun readWaitingPreparation(
        problemId: String,
        problemRevisionId: String,
        errorBookEntryId: String,
    ): ProblemOrganizationWorkPreparationRow?

    @Query(
        """
        SELECT work.*,
               receipt.command_id AS receipt_command_id,
               receipt.payload_fingerprint AS receipt_payload_fingerprint,
               receipt.draft_id AS receipt_draft_id,
               receipt.draft_revision_number AS receipt_draft_revision_number,
               receipt.problem_id AS receipt_problem_id,
               receipt.problem_revision_id AS receipt_problem_revision_id,
               receipt.practice_unit_id AS receipt_practice_unit_id,
               receipt.error_book_entry_id AS receipt_error_book_entry_id,
               receipt.committed_at_epoch_millis AS receipt_committed_at_epoch_millis
        FROM problem_organization_work AS work
        INNER JOIN problem_draft_commit_receipt AS receipt
          ON receipt.command_id = work.commit_receipt_command_id
        WHERE receipt.problem_id = :problemId
          AND receipt.problem_revision_id = :problemRevisionId
          AND receipt.error_book_entry_id = :errorBookEntryId
        ORDER BY receipt.committed_at_epoch_millis DESC,
                 work.created_at_epoch_millis DESC,
                 work.work_id DESC
        LIMIT 1
        """,
    )
    abstract suspend fun readLatestPreparation(
        problemId: String,
        problemRevisionId: String,
        errorBookEntryId: String,
    ): ProblemOrganizationWorkPreparationRow?

    @Query(
        """
        SELECT * FROM problem_organization_work
        WHERE (
            status IN ('PENDING', 'RETRY')
            AND not_before_epoch_millis <= :nowEpochMillis
        ) OR (
            status = 'RUNNING'
            AND lease_expires_at_epoch_millis IS NOT NULL
            AND lease_expires_at_epoch_millis <= :nowEpochMillis
        )
        ORDER BY not_before_epoch_millis ASC, created_at_epoch_millis ASC, work_id ASC
        LIMIT 1
        """,
    )
    protected abstract suspend fun readClaimable(
        nowEpochMillis: Long,
    ): ProblemOrganizationWorkEntity?

    @Query(
        """
        SELECT * FROM problem_organization_work
        WHERE status IN ('PENDING', 'RETRY', 'RUNNING')
           OR (status = 'WAITING_AUTHORIZATION' AND authorization_grant_snapshot IS NOT NULL)
        ORDER BY not_before_epoch_millis ASC, created_at_epoch_millis ASC, work_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readSchedulable(
        limit: Int,
    ): List<ProblemOrganizationWorkEntity>

    @Query(
        """
        SELECT * FROM problem_organization_work
        WHERE status = 'RUNNING'
          AND lease_expires_at_epoch_millis IS NOT NULL
          AND (
              :afterLeaseExpiresAtEpochMillis IS NULL
              OR lease_expires_at_epoch_millis > :afterLeaseExpiresAtEpochMillis
              OR (
                  lease_expires_at_epoch_millis = :afterLeaseExpiresAtEpochMillis
                  AND updated_at_epoch_millis > :afterUpdatedAtEpochMillis
              )
              OR (
                  lease_expires_at_epoch_millis = :afterLeaseExpiresAtEpochMillis
                  AND updated_at_epoch_millis = :afterUpdatedAtEpochMillis
                  AND work_id > :afterWorkId
              )
          )
        ORDER BY lease_expires_at_epoch_millis ASC, updated_at_epoch_millis ASC, work_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readRunning(
        limit: Int,
        afterLeaseExpiresAtEpochMillis: Long?,
        afterUpdatedAtEpochMillis: Long?,
        afterWorkId: String?,
    ): List<ProblemOrganizationWorkEntity>

    @Query(
        """
        SELECT * FROM problem_organization_work
        WHERE status IN ('PENDING', 'RETRY')
           OR (status = 'WAITING_AUTHORIZATION' AND authorization_grant_snapshot IS NOT NULL)
        ORDER BY not_before_epoch_millis ASC, created_at_epoch_millis ASC, work_id ASC
        """,
    )
    abstract fun observeSchedulable(): Flow<List<ProblemOrganizationWorkEntity>>

    @Query(
        """
        UPDATE problem_organization_work
        SET status = 'RUNNING',
            state_version = state_version + 1,
            attempt_count = attempt_count + 1,
            lease_owner = :leaseOwner,
            lease_expires_at_epoch_millis = :leaseExpiresAtEpochMillis,
            failure_code = NULL,
            failure_message = NULL,
            updated_at_epoch_millis = :nowEpochMillis
        WHERE work_id = :workId
          AND state_version = :expectedStateVersion
          AND (
              (
                  status IN ('PENDING', 'RETRY')
                  AND not_before_epoch_millis <= :nowEpochMillis
              ) OR (
                  status = 'RUNNING'
                  AND lease_expires_at_epoch_millis IS NOT NULL
                  AND lease_expires_at_epoch_millis <= :nowEpochMillis
              )
          )
        """,
    )
    protected abstract suspend fun claimExact(
        workId: String,
        expectedStateVersion: Long,
        leaseOwner: String,
        leaseExpiresAtEpochMillis: Long,
        nowEpochMillis: Long,
    ): Int

    @Transaction
    open suspend fun claimNext(
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
    ): ProblemOrganizationWorkEntity? {
        require(leaseOwner.isNotBlank()) { "leaseOwner must not be blank" }
        require(nowEpochMillis >= 0) { "nowEpochMillis must not be negative" }
        require(leaseDurationMillis > 0) { "leaseDurationMillis must be positive" }
        val candidate = readClaimable(nowEpochMillis) ?: return null
        val leaseExpiry = Math.addExact(nowEpochMillis, leaseDurationMillis)
        if (
            claimExact(
                workId = candidate.workId,
                expectedStateVersion = candidate.stateVersion,
                leaseOwner = leaseOwner,
                leaseExpiresAtEpochMillis = leaseExpiry,
                nowEpochMillis = nowEpochMillis,
            ) != 1
        ) {
            return null
        }
        return checkNotNull(readWork(candidate.workId))
    }

    @Transaction
    open suspend fun claim(
        workId: String,
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
    ): ProblemOrganizationWorkEntity? {
        require(workId.isNotBlank()) { "workId must not be blank" }
        require(leaseOwner.isNotBlank()) { "leaseOwner must not be blank" }
        require(nowEpochMillis >= 0) { "nowEpochMillis must not be negative" }
        require(leaseDurationMillis > 0) { "leaseDurationMillis must be positive" }
        val candidate = readWork(workId) ?: return null
        val leaseExpiry = Math.addExact(nowEpochMillis, leaseDurationMillis)
        if (
            claimExact(
                workId = candidate.workId,
                expectedStateVersion = candidate.stateVersion,
                leaseOwner = leaseOwner,
                leaseExpiresAtEpochMillis = leaseExpiry,
                nowEpochMillis = nowEpochMillis,
            ) != 1
        ) {
            return null
        }
        return checkNotNull(readWork(candidate.workId))
    }

    @Query(
        """
        UPDATE problem_organization_work
        SET status = 'WAITING_AUTHORIZATION',
            state_version = state_version + 1,
            request_id = NULL,
            request_snapshot = NULL,
            lease_owner = NULL,
            lease_expires_at_epoch_millis = NULL,
            failure_code = :failureCode,
            failure_message = :failureMessage,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE work_id = :workId
          AND state_version = :expectedStateVersion
          AND status = 'RUNNING'
          AND lease_owner = :leaseOwner
          AND lease_expires_at_epoch_millis > :updatedAtEpochMillis
        """,
    )
    abstract suspend fun markWaitingAuthorization(
        workId: String,
        expectedStateVersion: Long,
        leaseOwner: String,
        failureCode: String,
        failureMessage: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE problem_organization_work
        SET status = 'PENDING',
            state_version = state_version + 1,
            not_before_epoch_millis = :notBeforeEpochMillis,
            request_id = :requestId,
            request_snapshot = :requestSnapshot,
            failure_code = NULL,
            failure_message = NULL,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE work_id = :workId
          AND state_version = :expectedStateVersion
          AND status = 'WAITING_AUTHORIZATION'
          AND request_id IS NULL
          AND request_snapshot IS NULL
        """,
    )
    abstract suspend fun authorize(
        workId: String,
        expectedStateVersion: Long,
        requestId: String,
        requestSnapshot: String,
        notBeforeEpochMillis: Long,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE problem_organization_work
        SET state_version = state_version + 1,
            authorization_grant_snapshot = :authorizationGrantSnapshot,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE work_id = :workId
          AND state_version = :expectedStateVersion
          AND status = 'WAITING_AUTHORIZATION'
          AND request_id IS NULL
          AND request_snapshot IS NULL
        """,
    )
    abstract suspend fun reauthorize(
        workId: String,
        expectedStateVersion: Long,
        authorizationGrantSnapshot: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE problem_organization_work
        SET status = 'RETRY',
            state_version = state_version + 1,
            not_before_epoch_millis = :notBeforeEpochMillis,
            lease_owner = NULL,
            lease_expires_at_epoch_millis = NULL,
            failure_code = :failureCode,
            failure_message = :failureMessage,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE work_id = :workId
          AND state_version = :expectedStateVersion
          AND status = 'RUNNING'
          AND lease_owner = :leaseOwner
          AND lease_expires_at_epoch_millis > :updatedAtEpochMillis
        """,
    )
    abstract suspend fun markRetry(
        workId: String,
        expectedStateVersion: Long,
        leaseOwner: String,
        notBeforeEpochMillis: Long,
        failureCode: String,
        failureMessage: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE problem_organization_work
        SET status = 'PERMANENT_FAILURE',
            state_version = state_version + 1,
            lease_owner = NULL,
            lease_expires_at_epoch_millis = NULL,
            failure_code = :failureCode,
            failure_message = :failureMessage,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE work_id = :workId
          AND state_version = :expectedStateVersion
          AND status = 'RUNNING'
          AND lease_owner = :leaseOwner
          AND lease_expires_at_epoch_millis > :updatedAtEpochMillis
        """,
    )
    abstract suspend fun markPermanentFailure(
        workId: String,
        expectedStateVersion: Long,
        leaseOwner: String,
        failureCode: String,
        failureMessage: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE problem_organization_work
        SET status = 'SUCCEEDED',
            state_version = state_version + 1,
            lease_owner = NULL,
            lease_expires_at_epoch_millis = NULL,
            failure_code = NULL,
            failure_message = NULL,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE work_id = :workId
          AND state_version = :expectedStateVersion
          AND status = 'RUNNING'
          AND lease_owner = :leaseOwner
          AND request_id = :requestId
          AND lease_expires_at_epoch_millis > :updatedAtEpochMillis
        """,
    )
    abstract suspend fun markSucceeded(
        workId: String,
        expectedStateVersion: Long,
        leaseOwner: String,
        requestId: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT * FROM problem_solution_step
        WHERE organization_command_id = :organizationCommandId
        ORDER BY step_ordinal ASC
        """,
    )
    abstract suspend fun readSolutionSteps(
        organizationCommandId: String,
    ): List<ProblemSolutionStepEntity>

    @Query(
        """
        SELECT * FROM problem_error_attribution_candidate
        WHERE organization_command_id = :organizationCommandId
        ORDER BY candidate_ordinal ASC
        """,
    )
    abstract suspend fun readErrorAttributionCandidates(
        organizationCommandId: String,
    ): List<ProblemErrorAttributionCandidateEntity>
}
