package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.database.AuthorizeProblemOrganizationWorkCommand
import com.tingyun.smartmistakebook.core.database.LegacyOrganizationWorkCoordinationPort
import com.tingyun.smartmistakebook.core.database.ProblemOrganizationWorkRecord
import com.tingyun.smartmistakebook.core.database.ProblemOrganizationWorkTransitionCommand
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LegacyProblemOrganizationWorkSessionAdapter(
    private val boundScope: SessionScope,
    private val legacy: LegacyOrganizationWorkCoordinationPort,
) : ProblemOrganizationWorkSessionPort {
    override fun observeSchedulable(
        scope: SessionScope,
    ): Flow<List<ProblemOrganizationWorkSessionSnapshot>> {
        scope.requireBoundTo(boundScope)
        return legacy.observeSchedulableProblemOrganizationWorks().map { records ->
            records.map { it.toSession(boundScope) }
        }
    }

    override suspend fun read(
        query: ProblemOrganizationWorkSessionReadQuery,
    ): ProblemOrganizationWorkSessionSnapshot? {
        query.scope.requireBoundTo(boundScope)
        val record =
            when (query) {
                is ProblemOrganizationWorkSessionReadQuery.ByWorkId ->
                    legacy.readProblemOrganizationWork(query.workId)
                is ProblemOrganizationWorkSessionReadQuery.ByRequestId ->
                    legacy.readProblemOrganizationWorkByRequestId(query.requestId)
                is ProblemOrganizationWorkSessionReadQuery.BySourceReceiptId ->
                    legacy.readProblemOrganizationWorkByCommitReceipt(
                        query.sourceCommitReceiptId,
                    )
            }
        return record?.toSession(boundScope)
    }

    override suspend fun readSourceReceipt(
        scope: SessionScope,
        sourceCommitReceiptId: String,
    ): OrganizationSourceCommitSessionReceipt? {
        scope.requireBoundTo(boundScope)
        sourceCommitReceiptId.requireSessionIdentifier("Organization source receipt id")
        return legacy.readProblemOrganizationWorkCommitReceipt(sourceCommitReceiptId)?.let {
            OrganizationSourceCommitSessionReceipt(
                scope = boundScope,
                receiptId = it.commandId,
                payloadFingerprint = it.payloadFingerprint,
                recordedAtEpochMillis = it.committedAtEpochMillis,
            )
        }
    }

    override suspend fun authorize(
        command: AuthorizeProblemOrganizationWorkSessionCommand,
    ): ProblemOrganizationWorkSessionMutationResult {
        command.scope.requireBoundTo(boundScope)
        require(command.operation.payloadFingerprint == command.requestPayload.contentSha256) {
            "Organization request identity does not match its opaque payload"
        }
        val before = currentOrResult(command.workId, command.operation, command.occurredAtEpochMillis)
        if (before.result != null) return before.result
        val current = checkNotNull(before.snapshot)
        if (current.version != command.expectedVersion) {
            return mutationResult(
                command.operation,
                SessionMutationDisposition.RELOAD_REQUIRED,
                current,
                command.occurredAtEpochMillis,
            )
        }
        val applied =
            legacy.authorizeProblemOrganizationWork(
                AuthorizeProblemOrganizationWorkCommand(
                    workId = command.workId,
                    expectedStateVersion = current.version.sequence,
                    requestId = command.operation.requestId,
                    requestSnapshot = command.requestPayload.content,
                    notBeforeEpochMillis = command.notBeforeEpochMillis,
                    authorizedAtEpochMillis = command.occurredAtEpochMillis,
                ),
            )
        return afterMutation(
            workId = command.workId,
            operation = command.operation,
            applied = applied,
            duplicateStatus = ProblemOrganizationWorkSessionStatus.PENDING,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
        )
    }

    override suspend fun claim(
        command: ClaimProblemOrganizationWorkSessionCommand,
    ): ProblemOrganizationWorkSessionMutationResult {
        command.scope.requireBoundTo(boundScope)
        val before = currentOrResult(command.workId, command.operation, command.occurredAtEpochMillis)
        if (before.result != null) return before.result
        val current = checkNotNull(before.snapshot)
        if (current.version != command.expectedVersion) {
            return mutationResult(
                command.operation,
                SessionMutationDisposition.RELOAD_REQUIRED,
                current,
                command.occurredAtEpochMillis,
            )
        }
        val claimed =
            legacy.claimProblemOrganizationWork(
                workId = command.workId,
                leaseOwner = command.leaseOwner,
                nowEpochMillis = command.occurredAtEpochMillis,
                leaseDurationMillis = command.leaseDurationMillis,
            )?.toSession(boundScope)
        if (claimed != null) {
            return mutationResult(
                command.operation,
                SessionMutationDisposition.APPLIED,
                claimed,
                command.occurredAtEpochMillis,
            )
        }
        val after = legacy.readProblemOrganizationWork(command.workId)?.toSession(boundScope)
        val disposition =
            if (
                after?.status == ProblemOrganizationWorkSessionStatus.RUNNING &&
                after.leaseOwner == command.leaseOwner
            ) {
                SessionMutationDisposition.DUPLICATE
            } else {
                SessionMutationDisposition.RELOAD_REQUIRED
            }
        return mutationResult(
            command.operation,
            disposition,
            after,
            command.occurredAtEpochMillis,
        )
    }

    override suspend fun transition(
        command: ProblemOrganizationWorkSessionTransition,
    ): ProblemOrganizationWorkSessionMutationResult {
        command.requireValid(boundScope)
        val before = currentOrResult(command.workId, command.operation, command.occurredAtEpochMillis)
        if (before.result != null) return before.result
        val current = checkNotNull(before.snapshot)
        if (
            current.version != command.expectedVersion ||
            current.leaseOwner != command.leaseOwner
        ) {
            return mutationResult(
                command.operation,
                SessionMutationDisposition.RELOAD_REQUIRED,
                current,
                command.occurredAtEpochMillis,
            )
        }
        val transition =
            ProblemOrganizationWorkTransitionCommand(
                workId = command.workId,
                expectedStateVersion = current.version.sequence,
                leaseOwner = command.leaseOwner,
                occurredAtEpochMillis = command.occurredAtEpochMillis,
                failureCode =
                    when (command) {
                        is ProblemOrganizationWorkSessionTransition.WaitForAuthorization ->
                            command.failureCode
                        is ProblemOrganizationWorkSessionTransition.Retry -> command.failureCode
                        is ProblemOrganizationWorkSessionTransition.FailPermanently ->
                            command.failureCode
                        else -> null
                    },
                failureMessage =
                    when (command) {
                        is ProblemOrganizationWorkSessionTransition.WaitForAuthorization ->
                            command.failureMessage
                        is ProblemOrganizationWorkSessionTransition.Retry -> command.failureMessage
                        is ProblemOrganizationWorkSessionTransition.FailPermanently ->
                            command.failureMessage
                        else -> null
                    },
                notBeforeEpochMillis =
                    (command as? ProblemOrganizationWorkSessionTransition.Retry)
                        ?.notBeforeEpochMillis,
                requestId =
                    (command as? ProblemOrganizationWorkSessionTransition.Complete)
                        ?.completedRequestId,
            )
        val applied =
            when (command) {
                is ProblemOrganizationWorkSessionTransition.WaitForAuthorization ->
                    legacy.markProblemOrganizationWorkWaitingAuthorization(transition)
                is ProblemOrganizationWorkSessionTransition.Retry ->
                    legacy.retryProblemOrganizationWork(transition)
                is ProblemOrganizationWorkSessionTransition.FailPermanently ->
                    legacy.failProblemOrganizationWorkPermanently(transition)
                is ProblemOrganizationWorkSessionTransition.Complete ->
                    legacy.completeProblemOrganizationWork(transition)
            }
        return afterMutation(
            workId = command.workId,
            operation = command.operation,
            applied = applied,
            duplicateStatus = command.targetStatus(),
            occurredAtEpochMillis = command.occurredAtEpochMillis,
        )
    }

    override suspend fun readRunningRecoveryPage(
        query: ProblemOrganizationWorkRecoveryQuery,
    ): List<ProblemOrganizationWorkSessionSnapshot> {
        query.scope.requireBoundTo(boundScope)
        return legacy.readRunningProblemOrganizationWorks(
            limit = query.limit,
            afterLeaseExpiresAtEpochMillis = query.after?.leaseExpiresAtEpochMillis,
            afterUpdatedAtEpochMillis = query.after?.updatedAtEpochMillis,
            afterWorkId = query.after?.workId,
        ).map { it.toSession(boundScope) }
    }

    private suspend fun afterMutation(
        workId: String,
        operation: SessionOperationIdentity,
        applied: Boolean,
        duplicateStatus: ProblemOrganizationWorkSessionStatus,
        occurredAtEpochMillis: Long,
    ): ProblemOrganizationWorkSessionMutationResult {
        val after = legacy.readProblemOrganizationWork(workId)?.toSession(boundScope)
        val disposition =
            if (applied) {
                SessionMutationDisposition.APPLIED
            } else if (after?.status == duplicateStatus) {
                SessionMutationDisposition.DUPLICATE
            } else {
                SessionMutationDisposition.RELOAD_REQUIRED
            }
        return mutationResult(operation, disposition, after, occurredAtEpochMillis)
    }

    private suspend fun currentOrResult(
        workId: String,
        operation: SessionOperationIdentity,
        occurredAtEpochMillis: Long,
    ): CurrentOrganizationWork {
        val current = legacy.readProblemOrganizationWork(workId)?.toSession(boundScope)
        return if (current == null) {
            CurrentOrganizationWork(
                snapshot = null,
                result =
                    mutationResult(
                        operation,
                        SessionMutationDisposition.NOT_FOUND,
                        null,
                        occurredAtEpochMillis,
                    ),
            )
        } else {
            CurrentOrganizationWork(snapshot = current)
        }
    }
}

private data class CurrentOrganizationWork(
    val snapshot: ProblemOrganizationWorkSessionSnapshot?,
    val result: ProblemOrganizationWorkSessionMutationResult? = null,
)

private fun ProblemOrganizationWorkSessionTransition.requireValid(boundScope: SessionScope) {
    scope.requireBoundTo(boundScope)
    workId.requireSessionIdentifier("Organization work id")
    leaseOwner.requireSessionIdentifier("Organization lease owner")
    require(occurredAtEpochMillis >= 0) { "Organization transition time must not be negative" }
    when (this) {
        is ProblemOrganizationWorkSessionTransition.WaitForAuthorization ->
            require(failureCode.isNotBlank() && failureMessage.isNotBlank()) {
                "Waiting organization work requires failure details"
            }
        is ProblemOrganizationWorkSessionTransition.Retry -> {
            require(notBeforeEpochMillis >= occurredAtEpochMillis) {
                "Organization retry cannot be scheduled in the past"
            }
            require(failureCode.isNotBlank() && failureMessage.isNotBlank()) {
                "Organization retry requires failure details"
            }
        }
        is ProblemOrganizationWorkSessionTransition.FailPermanently ->
            require(failureCode.isNotBlank() && failureMessage.isNotBlank()) {
                "Permanent organization failure requires details"
            }
        else -> Unit
    }
}

private fun ProblemOrganizationWorkSessionTransition.targetStatus() =
    when (this) {
        is ProblemOrganizationWorkSessionTransition.WaitForAuthorization ->
            ProblemOrganizationWorkSessionStatus.WAITING_AUTHORIZATION
        is ProblemOrganizationWorkSessionTransition.Retry ->
            ProblemOrganizationWorkSessionStatus.RETRY
        is ProblemOrganizationWorkSessionTransition.FailPermanently ->
            ProblemOrganizationWorkSessionStatus.PERMANENT_FAILURE
        is ProblemOrganizationWorkSessionTransition.Complete ->
            ProblemOrganizationWorkSessionStatus.SUCCEEDED
    }

private fun ProblemOrganizationWorkRecord.toSession(
    scope: SessionScope,
): ProblemOrganizationWorkSessionSnapshot {
    val fingerprint =
        CanonicalSha256("organization-work-session-state-v1")
            .field("workId", workId)
            .field("sourceReceiptId", commitReceiptCommandId)
            .field("status", status)
            .field("stateVersion", stateVersion)
            .field("attemptCount", attemptCount)
            .field("notBeforeEpochMillis", notBeforeEpochMillis)
            .nullableField("requestId", requestId)
            .nullableField("requestPayloadSha256", requestSnapshot?.sha256())
            .nullableField(
                "authorizationPayloadSha256",
                authorizationGrantSnapshot?.sha256(),
            )
            .nullableField("leaseOwner", leaseOwner)
            .nullableField("leaseExpiresAtEpochMillis", leaseExpiresAtEpochMillis?.toString())
            .nullableField("failureCode", failureCode)
            .field("updatedAtEpochMillis", updatedAtEpochMillis)
            .finish()
    return ProblemOrganizationWorkSessionSnapshot(
        scope = scope,
        workId = workId,
        sourceCommitReceiptId = commitReceiptCommandId,
        status = ProblemOrganizationWorkSessionStatus.valueOf(status),
        version = SessionVersion(stateVersion, fingerprint),
        attemptCount = attemptCount,
        notBeforeEpochMillis = notBeforeEpochMillis,
        requestId = requestId,
        requestPayload =
            requestSnapshot?.let {
                SessionOpaquePayload("organization-request-v1", it)
            },
        authorizationPayload =
            authorizationGrantSnapshot?.let {
                SessionOpaquePayload("organization-authorization-v1", it)
            },
        leaseOwner = leaseOwner,
        leaseExpiresAtEpochMillis = leaseExpiresAtEpochMillis,
        failureCode = failureCode,
        failureMessage = failureMessage,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

private fun mutationResult(
    operation: SessionOperationIdentity,
    disposition: SessionMutationDisposition,
    snapshot: ProblemOrganizationWorkSessionSnapshot?,
    occurredAtEpochMillis: Long,
) = ProblemOrganizationWorkSessionMutationResult(
    receipt =
        SessionMutationReceipt(
            operation = operation,
            disposition = disposition,
            currentVersion = snapshot?.version,
            recordedAtEpochMillis = occurredAtEpochMillis,
        ),
    snapshot = snapshot,
)
