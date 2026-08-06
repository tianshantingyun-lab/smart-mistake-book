package com.tingyun.smartmistakebook.core.database

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.dao.MistakeRow
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeGroundingSummaryRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureHeadRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureIndexRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureSourceAssetRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureWorkspaceColumns
import com.tingyun.smartmistakebook.core.database.dao.ReviewPlanAggregate
import com.tingyun.smartmistakebook.core.database.dao.ReviewedKnowledgeCoverageRow
import com.tingyun.smartmistakebook.core.database.dao.activeSessionHead
import com.tingyun.smartmistakebook.core.database.dao.latestSessionHead
import com.tingyun.smartmistakebook.core.database.dao.toRecord
import com.tingyun.smartmistakebook.core.database.dao.toSnapshot
import com.tingyun.smartmistakebook.core.database.dao.toModel
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentItemSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.ErrorBookEntryEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeMasteryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingResolutionEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeSourceBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSearchFeatureEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSourceEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialNodeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftCommitReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemMemoryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemOrganizationWorkEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRevisionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewPlanEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueItemEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueKnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueReasonEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionAdvanceReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionRevisionEntity
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrant
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrantCodec
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest


internal class RoomStudyDatabaseProblemOrganizationWorkSupport(
    private val database: StudyDatabase,
    private val problemOrganization: RoomProblemOrganizationStore,
    private val clock: () -> Long,
) {
    private fun trustedClockEpochMillis(): Long =
        clock().also { require(it >= 0) { "clock must not be negative" } }

    suspend fun readProblemOrganizationWork(
        workId: String,
    ): ProblemOrganizationWorkRecord? {
        require(workId.isNotBlank()) { "workId must not be blank" }
        return database.problemOrganizationWorkDao().readWork(workId)?.toRecord()
    }

    suspend fun readProblemOrganizationWorkByCommitReceipt(
        commitReceiptCommandId: String,
    ): ProblemOrganizationWorkRecord? {
        require(commitReceiptCommandId.isNotBlank()) {
            "commitReceiptCommandId must not be blank"
        }
        return database.problemOrganizationWorkDao()
            .readWorkByCommitReceipt(commitReceiptCommandId)
            ?.toRecord()
    }

    suspend fun readProblemOrganizationWorkByRequestId(
        requestId: String,
    ): ProblemOrganizationWorkRecord? {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        return database.problemOrganizationWorkDao()
            .readWorkByRequestId(requestId)
            ?.toRecord()
    }

    suspend fun readProblemOrganizationWorkCommitReceipt(
        commitReceiptCommandId: String,
    ): ProblemDraftCommitReceipt? {
        require(commitReceiptCommandId.isNotBlank()) {
            "commitReceiptCommandId must not be blank"
        }
        return database.problemOrganizationWorkDao()
            .readCommitReceipt(commitReceiptCommandId)
            ?.toProblemDraftCommitReceipt()
    }

    suspend fun readWaitingProblemOrganizationWork(
        problemId: String,
        problemRevisionId: String,
        errorBookEntryId: String,
    ): ProblemOrganizationWorkPreparationRecord? {
        require(problemId.isNotBlank()) { "problemId must not be blank" }
        require(problemRevisionId.isNotBlank()) { "problemRevisionId must not be blank" }
        require(errorBookEntryId.isNotBlank()) { "errorBookEntryId must not be blank" }
        return database.problemOrganizationWorkDao()
            .readWaitingPreparation(problemId, problemRevisionId, errorBookEntryId)
            ?.let { row ->
                ProblemOrganizationWorkPreparationRecord(
                    work = row.work.toRecord(),
                    commitReceipt = row.receipt.toProblemDraftCommitReceipt(),
                )
            }
    }

    suspend fun readLatestProblemOrganizationWork(
        problemId: String,
        problemRevisionId: String,
        errorBookEntryId: String,
    ): ProblemOrganizationWorkPreparationRecord? {
        require(problemId.isNotBlank()) { "problemId must not be blank" }
        require(problemRevisionId.isNotBlank()) { "problemRevisionId must not be blank" }
        require(errorBookEntryId.isNotBlank()) { "errorBookEntryId must not be blank" }
        return database.problemOrganizationWorkDao()
            .readLatestPreparation(problemId, problemRevisionId, errorBookEntryId)
            ?.let { row ->
                ProblemOrganizationWorkPreparationRecord(
                    work = row.work.toRecord(),
                    commitReceipt = row.receipt.toProblemDraftCommitReceipt(),
                )
            }
    }

    suspend fun claimNextProblemOrganizationWork(
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
    ): ProblemOrganizationWorkRecord? = database.problemOrganizationWorkDao()
        .claimNext(leaseOwner, nowEpochMillis, leaseDurationMillis)
        ?.toRecord()

    suspend fun claimProblemOrganizationWork(
        workId: String,
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
    ): ProblemOrganizationWorkRecord? = database.problemOrganizationWorkDao()
        .claim(workId, leaseOwner, nowEpochMillis, leaseDurationMillis)
        ?.toRecord()

    suspend fun readSchedulableProblemOrganizationWorks(
        nowEpochMillis: Long,
        limit: Int,
    ): List<ProblemOrganizationWorkRecord> {
        require(nowEpochMillis >= 0) { "nowEpochMillis must not be negative" }
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return database.problemOrganizationWorkDao()
            .readSchedulable(limit)
            .map { it.toRecord() }
    }

    fun observeSchedulableProblemOrganizationWorks():
        Flow<List<ProblemOrganizationWorkRecord>> =
        database.problemOrganizationWorkDao()
            .observeSchedulable()
            .map { works -> works.map(ProblemOrganizationWorkEntity::toRecord) }

    suspend fun readRunningProblemOrganizationWorks(
        limit: Int,
        afterLeaseExpiresAtEpochMillis: Long?,
        afterUpdatedAtEpochMillis: Long?,
        afterWorkId: String?,
    ): List<ProblemOrganizationWorkRecord> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val cursorIsEmpty = afterLeaseExpiresAtEpochMillis == null &&
            afterUpdatedAtEpochMillis == null &&
            afterWorkId == null
        val cursorIsComplete = afterLeaseExpiresAtEpochMillis != null &&
            afterUpdatedAtEpochMillis != null &&
            afterWorkId != null
        require(cursorIsEmpty || cursorIsComplete) {
            "Running organization work cursor must be entirely absent or present"
        }
        return database.problemOrganizationWorkDao()
            .readRunning(
                limit = limit,
                afterLeaseExpiresAtEpochMillis = afterLeaseExpiresAtEpochMillis,
                afterUpdatedAtEpochMillis = afterUpdatedAtEpochMillis,
                afterWorkId = afterWorkId,
            )
            .map(ProblemOrganizationWorkEntity::toRecord)
    }

    suspend fun authorizeProblemOrganizationWork(
        command: AuthorizeProblemOrganizationWorkCommand,
    ): Boolean {
        require(command.workId.isNotBlank()) { "workId must not be blank" }
        require(command.expectedStateVersion >= 0) { "expectedStateVersion must not be negative" }
        require(command.requestId.isNotBlank()) { "requestId must not be blank" }
        require(command.notBeforeEpochMillis >= command.authorizedAtEpochMillis) {
            "Authorized organization work cannot be scheduled in the past"
        }
        val request = ModelTaskCodec.decodeRequest(command.requestSnapshot)
        require(request.requestId == command.requestId) {
            "Organization work request snapshot id does not match"
        }
        require(request.input.kind == com.tingyun.smartmistakebook.core.model.ModelTaskKind.PROBLEM_CLASSIFY) {
            "Organization work can only authorize a problem-classification task"
        }
        require(request.schemaVersion == ModelTaskRequest.PROBLEM_ORGANIZATION_V3_SCHEMA_VERSION) {
            "Organization work requires the current image-grounded request schema"
        }
        request.egressManifest?.let { manifest ->
            require(
                manifest.schemaVersion ==
                    com.tingyun.smartmistakebook.core.model.ModelEgressManifest.CURRENT_SCHEMA_VERSION,
            ) { "Organization work requires the current egress schema" }
        }
        val work = database.problemOrganizationWorkDao().readWork(command.workId)
            ?: return false
        val authorization = ProblemOrganizationAuthorizationGrantCodec.decodeOrNull(
            work.authorizationGrantSnapshot,
        ) ?: return false
        val receipt = database.problemOrganizationWorkDao()
            .readCommitReceipt(work.commitReceiptCommandId)
            ?: throw DatabaseContractViolationException(
                "Organization work commit receipt is missing",
            )
        val input = request.input as? ProblemOrganizationV3Input
            ?: throw IllegalArgumentException(
                "Persistent organization work requires ProblemOrganizationV3Input",
            )
        require(authorization.sourceDraftId == receipt.draftId) {
            "Organization authorization belongs to another source draft"
        }
        require(
            command.authorizedAtEpochMillis in
                authorization.approvedAtEpochMillis until authorization.expiresAtEpochMillis,
        ) { "Organization authorization is not current" }
        require(request.egressManifest == authorization.toEgressManifest(request.requestId, input)) {
            "Organization request does not match its persisted authorization"
        }
        require(
            input.problemId == receipt.problemId &&
                input.problemRevisionId == receipt.problemRevisionId &&
                input.practiceUnitId == receipt.practiceUnitId,
        ) { "Organization work request does not match its exact commit receipt" }
        val draft = database.problemDraftTransactionDao().read(receipt.draftId)
            ?: throw DatabaseContractViolationException(
                "Organization work source draft is missing",
            )
        require(draft.currentRevision.revisionNumber == receipt.draftRevisionNumber) {
            "Organization work source revision no longer matches its receipt"
        }
        require(
            CapturedQuestionDocumentFingerprint.of(input.capturedDocument) ==
                draft.currentRevision.documentFingerprint,
        ) { "Organization work document does not match its committed revision" }
        val expectedAssets = draft.sourceAssets.map { source ->
            OrganizationSourceAssetIdentity(
                assetId = source.sourceAsset.sourceAssetId,
                sha256 = source.sourceAsset.contentSha256,
                width = source.sourceAsset.width,
                height = source.sourceAsset.height,
                pageIndex = source.pageIndex,
            )
        }
        val requestedAssets = input.sourceAssets.map { source ->
            require(source.selectedRegion == null) {
                "Persistent organization work must reference the committed full source page"
            }
            OrganizationSourceAssetIdentity(
                assetId = source.assetId,
                sha256 = source.sha256,
                width = source.width,
                height = source.height,
                pageIndex = source.pageIndex,
            )
        }
        require(requestedAssets == expectedAssets) {
            "Organization work assets do not match its exact import occurrence"
        }
        return database.problemOrganizationWorkDao().authorize(
            workId = command.workId,
            expectedStateVersion = command.expectedStateVersion,
            requestId = command.requestId,
            requestSnapshot = command.requestSnapshot,
            notBeforeEpochMillis = command.notBeforeEpochMillis,
            updatedAtEpochMillis = command.authorizedAtEpochMillis,
        ) == 1
    }

    suspend fun reauthorizeProblemOrganizationWork(
        command: ReauthorizeProblemOrganizationWorkCommand,
    ): ReauthorizeProblemOrganizationWorkResult {
        require(command.workId.isNotBlank()) { "workId must not be blank" }
        require(command.expectedStateVersion in 0L until Long.MAX_VALUE) {
            "expectedStateVersion is outside the supported range"
        }
        require(command.problemId.isNotBlank()) { "problemId must not be blank" }
        require(command.problemRevisionId.isNotBlank()) {
            "problemRevisionId must not be blank"
        }
        require(command.errorBookEntryId.isNotBlank()) {
            "errorBookEntryId must not be blank"
        }
        return database.withWriteTransaction {
            val workDao = database.problemOrganizationWorkDao()
            val work = workDao.readWork(command.workId)
                ?: return@withWriteTransaction reauthorizationNotApplied()
            val receipt = workDao.readCommitReceipt(work.commitReceiptCommandId)
                ?: throw DatabaseContractViolationException(
                    "Organization work commit receipt is missing",
                )
            if (
                receipt.problemId != command.problemId ||
                receipt.problemRevisionId != command.problemRevisionId ||
                receipt.errorBookEntryId != command.errorBookEntryId
            ) {
                return@withWriteTransaction reauthorizationNotApplied()
            }

            val authorizationSnapshot = ProblemOrganizationAuthorizationGrantCodec.encode(
                command.authorizationGrant,
            )
            val replayStateVersion = command.expectedStateVersion + 1
            if (
                work.stateVersion == replayStateVersion &&
                work.status == StudyDbValue.ProblemOrganizationWorkStatus.WAITING_AUTHORIZATION &&
                work.requestId == null &&
                work.requestSnapshot == null
            ) {
                if (work.authorizationGrantSnapshot != authorizationSnapshot) {
                    throw ProblemOrganizationWorkReauthorizationConflictException(command.workId)
                }
                val replayNowEpochMillis = clock()
                require(replayNowEpochMillis >= 0) { "Database clock must not be negative" }
                validateProblemOrganizationReauthorization(
                    authorization = command.authorizationGrant,
                    provider = command.provider,
                    receipt = receipt.toProblemDraftCommitReceipt(),
                    draft = database.problemDraftTransactionDao().read(receipt.draftId)
                        ?: throw DatabaseContractViolationException(
                            "Organization work source draft is missing",
                        ),
                    nowEpochMillis = replayNowEpochMillis,
                )
                return@withWriteTransaction ReauthorizeProblemOrganizationWorkResult(
                    outcome = ReauthorizeProblemOrganizationWorkOutcome.REPLAYED,
                    work = work.toRecord(),
                )
            }
            if (
                work.stateVersion != command.expectedStateVersion ||
                work.status !=
                StudyDbValue.ProblemOrganizationWorkStatus.WAITING_AUTHORIZATION ||
                work.requestId != null ||
                work.requestSnapshot != null
            ) {
                return@withWriteTransaction reauthorizationNotApplied()
            }

            val nowEpochMillis = clock()
            require(nowEpochMillis >= 0) { "Database clock must not be negative" }
            validateProblemOrganizationReauthorization(
                authorization = command.authorizationGrant,
                provider = command.provider,
                receipt = receipt.toProblemDraftCommitReceipt(),
                draft = database.problemDraftTransactionDao().read(receipt.draftId)
                    ?: throw DatabaseContractViolationException(
                        "Organization work source draft is missing",
                    ),
                nowEpochMillis = nowEpochMillis,
            )
            if (
                workDao.reauthorize(
                    workId = command.workId,
                    expectedStateVersion = command.expectedStateVersion,
                    authorizationGrantSnapshot = authorizationSnapshot,
                    updatedAtEpochMillis = nowEpochMillis,
                ) != 1
            ) {
                return@withWriteTransaction reauthorizationNotApplied()
            }
            ReauthorizeProblemOrganizationWorkResult(
                outcome = ReauthorizeProblemOrganizationWorkOutcome.REAUTHORIZED,
                work = checkNotNull(workDao.readWork(command.workId)).toRecord(),
            )
        }
    }

    suspend fun markProblemOrganizationWorkWaitingAuthorization(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean {
        validateOrganizationWorkTransition(command, requireFailure = true)
        return database.problemOrganizationWorkDao().markWaitingAuthorization(
            workId = command.workId,
            expectedStateVersion = command.expectedStateVersion,
            leaseOwner = command.leaseOwner,
            failureCode = requireNotNull(command.failureCode),
            failureMessage = requireNotNull(command.failureMessage),
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        ) == 1
    }

    suspend fun retryProblemOrganizationWork(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean {
        validateOrganizationWorkTransition(command, requireFailure = true)
        val notBefore = requireNotNull(command.notBeforeEpochMillis) {
            "Retry requires a not-before time"
        }
        require(notBefore >= command.occurredAtEpochMillis) {
            "Retry not-before time must not precede its transition"
        }
        return database.problemOrganizationWorkDao().markRetry(
            workId = command.workId,
            expectedStateVersion = command.expectedStateVersion,
            leaseOwner = command.leaseOwner,
            notBeforeEpochMillis = notBefore,
            failureCode = requireNotNull(command.failureCode),
            failureMessage = requireNotNull(command.failureMessage),
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        ) == 1
    }

    suspend fun failProblemOrganizationWorkPermanently(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean {
        validateOrganizationWorkTransition(command, requireFailure = true)
        return database.problemOrganizationWorkDao().markPermanentFailure(
            workId = command.workId,
            expectedStateVersion = command.expectedStateVersion,
            leaseOwner = command.leaseOwner,
            failureCode = requireNotNull(command.failureCode),
            failureMessage = requireNotNull(command.failureMessage),
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        ) == 1
    }

    suspend fun completeProblemOrganizationWork(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean {
        validateOrganizationWorkTransition(command, requireFailure = false)
        val requestId = requireNotNull(command.requestId) { "Success requires a request id" }
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        return database.problemOrganizationWorkDao().markSucceeded(
            workId = command.workId,
            expectedStateVersion = command.expectedStateVersion,
            leaseOwner = command.leaseOwner,
            requestId = requestId,
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        ) == 1
    }

    suspend fun confirmAndCompleteProblemOrganizationWork(
        command: CompleteProblemOrganizationWorkAtomicallyCommand,
    ): ConfirmAndCompleteProblemOrganizationWorkResult {
        validateAtomicOrganizationCompletion(command)
        KnowledgeGroundingRequestContract.validate(command.groundingRequests)
        return database.withWriteTransaction {
            val authorizationNowEpochMillis = trustedClockEpochMillis()
            val workDao = database.problemOrganizationWorkDao()
            val work = workDao.readWork(command.workId)
            if (
                work == null ||
                !work.matchesCompletionAuthority(command, authorizationNowEpochMillis)
            ) {
                return@withWriteTransaction ConfirmAndCompleteProblemOrganizationWorkResult(
                    completed = false,
                    organizationResult = null,
                )
            }
            val sourceReceipt = workDao.readCommitReceipt(work.commitReceiptCommandId)
                ?: throw DatabaseContractViolationException(
                    "Organization work commit receipt is missing",
                )
            command.confirmation?.let { confirmation ->
                require(
                    confirmation.sourceCommitReceiptCommandId == work.commitReceiptCommandId,
                ) { "Organization confirmation belongs to another import occurrence" }
            }
            require(
                command.groundingRequests.all { request ->
                    request.organizationRequestId == command.requestId &&
                        request.problemId == sourceReceipt.problemId &&
                        request.problemRevisionId == sourceReceipt.problemRevisionId &&
                        request.practiceUnitId == sourceReceipt.practiceUnitId
                },
            ) { "Knowledge grounding belongs to another organization work" }
            if (command.groundingRequests.isNotEmpty()) {
                problemOrganization.requireLocalPolicyAuthority(
                    sourceReceipt.problemId,
                    sourceReceipt.problemRevisionId,
                )
            }

            val organizationResult = command.confirmation?.let {
                problemOrganization.confirmInCurrentTransaction(it)
            }
            database.knowledgeGroundingDao().recordAllInCurrentTransaction(
                command.groundingRequests.map(KnowledgeGroundingRequestRecord::toEntity),
            )
            val completionNowEpochMillis = maxOf(
                authorizationNowEpochMillis,
                trustedClockEpochMillis(),
            )
            if (
                workDao.markSucceeded(
                    workId = command.workId,
                    expectedStateVersion = command.expectedStateVersion,
                    leaseOwner = command.leaseOwner,
                    requestId = command.requestId,
                    updatedAtEpochMillis = completionNowEpochMillis,
                ) != 1
            ) {
                throw DatabaseContractViolationException(
                    "Organization work lease changed during atomic completion",
                )
            }
            ConfirmAndCompleteProblemOrganizationWorkResult(
                completed = true,
                organizationResult = organizationResult,
            )
        }
    }
}

