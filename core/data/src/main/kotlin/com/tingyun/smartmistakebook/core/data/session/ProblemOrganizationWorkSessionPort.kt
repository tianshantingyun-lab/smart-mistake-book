package com.tingyun.smartmistakebook.core.data.session

import kotlinx.coroutines.flow.Flow

enum class ProblemOrganizationWorkSessionStatus {
    PENDING,
    RUNNING,
    RETRY,
    WAITING_AUTHORIZATION,
    SUCCEEDED,
    PERMANENT_FAILURE,
}

data class ProblemOrganizationWorkSessionSnapshot(
    val scope: SessionScope,
    val workId: String,
    val sourceCommitReceiptId: String,
    val status: ProblemOrganizationWorkSessionStatus,
    val version: SessionVersion,
    val attemptCount: Int,
    val notBeforeEpochMillis: Long,
    val requestId: String?,
    val requestPayload: SessionOpaquePayload?,
    val authorizationPayload: SessionOpaquePayload?,
    val leaseOwner: String?,
    val leaseExpiresAtEpochMillis: Long?,
    val failureCode: String?,
    val failureMessage: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        workId.requireSessionIdentifier("Organization work id")
        sourceCommitReceiptId.requireSessionIdentifier("Organization source receipt id")
        require(attemptCount >= 0) { "Organization work attempt count must not be negative" }
        require(notBeforeEpochMillis >= 0) {
            "Organization work eligibility time must not be negative"
        }
        requestId?.requireSessionIdentifier("Organization request id")
        leaseOwner?.requireSessionIdentifier("Organization lease owner")
        require(leaseExpiresAtEpochMillis == null || leaseExpiresAtEpochMillis >= 0) {
            "Organization lease expiry must not be negative"
        }
        require(failureCode == null || failureCode.isNotBlank()) {
            "Organization failure code must not be blank"
        }
        require(failureMessage == null || failureMessage.length <= MAX_SESSION_FAILURE_MESSAGE_CHARS) {
            "Organization failure message exceeds its session budget"
        }
        require(createdAtEpochMillis >= 0 && updatedAtEpochMillis >= createdAtEpochMillis) {
            "Organization work times are invalid"
        }
        require(
            status != ProblemOrganizationWorkSessionStatus.RUNNING ||
                leaseOwner != null && leaseExpiresAtEpochMillis != null,
        ) { "Running organization work requires a lease" }
    }
}

data class OrganizationSourceCommitSessionReceipt(
    val scope: SessionScope,
    val receiptId: String,
    val payloadFingerprint: String,
    val recordedAtEpochMillis: Long,
) {
    init {
        receiptId.requireSessionIdentifier("Organization source receipt id")
        payloadFingerprint.requireSessionFingerprint("Organization source receipt fingerprint")
        require(recordedAtEpochMillis >= 0) {
            "Organization source receipt time must not be negative"
        }
    }
}

sealed interface ProblemOrganizationWorkSessionReadQuery {
    val scope: SessionScope

    data class ByWorkId(
        override val scope: SessionScope,
        val workId: String,
    ) : ProblemOrganizationWorkSessionReadQuery {
        init {
            workId.requireSessionIdentifier("Organization work id")
        }
    }

    data class ByRequestId(
        override val scope: SessionScope,
        val requestId: String,
    ) : ProblemOrganizationWorkSessionReadQuery {
        init {
            requestId.requireSessionIdentifier("Organization request id")
        }
    }

    data class BySourceReceiptId(
        override val scope: SessionScope,
        val sourceCommitReceiptId: String,
    ) : ProblemOrganizationWorkSessionReadQuery {
        init {
            sourceCommitReceiptId.requireSessionIdentifier("Organization source receipt id")
        }
    }
}

data class AuthorizeProblemOrganizationWorkSessionCommand(
    val scope: SessionScope,
    val operation: SessionOperationIdentity,
    val workId: String,
    val expectedVersion: SessionVersion,
    val requestPayload: SessionOpaquePayload,
    val notBeforeEpochMillis: Long,
    val occurredAtEpochMillis: Long,
) {
    init {
        workId.requireSessionIdentifier("Organization work id")
        require(notBeforeEpochMillis >= 0) {
            "Organization work eligibility time must not be negative"
        }
        require(occurredAtEpochMillis >= 0) {
            "Organization authorization time must not be negative"
        }
    }
}

data class ClaimProblemOrganizationWorkSessionCommand(
    val scope: SessionScope,
    val operation: SessionOperationIdentity,
    val workId: String,
    val expectedVersion: SessionVersion,
    val leaseOwner: String,
    val leaseDurationMillis: Long,
    val occurredAtEpochMillis: Long,
) {
    init {
        workId.requireSessionIdentifier("Organization work id")
        leaseOwner.requireSessionIdentifier("Organization lease owner")
        require(leaseDurationMillis > 0) { "Organization lease duration must be positive" }
        require(occurredAtEpochMillis >= 0) { "Organization claim time must not be negative" }
    }
}

sealed interface ProblemOrganizationWorkSessionTransition {
    val scope: SessionScope
    val operation: SessionOperationIdentity
    val workId: String
    val expectedVersion: SessionVersion
    val leaseOwner: String
    val occurredAtEpochMillis: Long

    data class WaitForAuthorization(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val workId: String,
        override val expectedVersion: SessionVersion,
        override val leaseOwner: String,
        override val occurredAtEpochMillis: Long,
        val failureCode: String,
        val failureMessage: String,
    ) : ProblemOrganizationWorkSessionTransition

    data class Retry(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val workId: String,
        override val expectedVersion: SessionVersion,
        override val leaseOwner: String,
        override val occurredAtEpochMillis: Long,
        val notBeforeEpochMillis: Long,
        val failureCode: String,
        val failureMessage: String,
    ) : ProblemOrganizationWorkSessionTransition

    data class FailPermanently(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val workId: String,
        override val expectedVersion: SessionVersion,
        override val leaseOwner: String,
        override val occurredAtEpochMillis: Long,
        val failureCode: String,
        val failureMessage: String,
    ) : ProblemOrganizationWorkSessionTransition

    data class Complete(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val workId: String,
        override val expectedVersion: SessionVersion,
        override val leaseOwner: String,
        override val occurredAtEpochMillis: Long,
        val completedRequestId: String,
    ) : ProblemOrganizationWorkSessionTransition {
        init {
            completedRequestId.requireSessionIdentifier("Completed organization request id")
        }
    }
}

data class ProblemOrganizationWorkRecoveryCursor(
    val leaseExpiresAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val workId: String,
) {
    init {
        require(leaseExpiresAtEpochMillis >= 0 && updatedAtEpochMillis >= 0) {
            "Organization recovery cursor times must not be negative"
        }
        workId.requireSessionIdentifier("Organization recovery work id")
    }
}

data class ProblemOrganizationWorkRecoveryQuery(
    val scope: SessionScope,
    val limit: Int,
    val after: ProblemOrganizationWorkRecoveryCursor? = null,
) {
    init {
        require(limit in 1..MAX_ORGANIZATION_RECOVERY_PAGE_SIZE) {
            "Organization recovery page is outside its session budget"
        }
    }
}

data class ProblemOrganizationWorkSessionMutationResult(
    val receipt: SessionMutationReceipt,
    val snapshot: ProblemOrganizationWorkSessionSnapshot?,
)

interface ProblemOrganizationWorkSessionPort {
    fun observeSchedulable(
        scope: SessionScope,
    ): Flow<List<ProblemOrganizationWorkSessionSnapshot>>

    suspend fun read(
        query: ProblemOrganizationWorkSessionReadQuery,
    ): ProblemOrganizationWorkSessionSnapshot?

    suspend fun readSourceReceipt(
        scope: SessionScope,
        sourceCommitReceiptId: String,
    ): OrganizationSourceCommitSessionReceipt?

    suspend fun authorize(
        command: AuthorizeProblemOrganizationWorkSessionCommand,
    ): ProblemOrganizationWorkSessionMutationResult

    suspend fun claim(
        command: ClaimProblemOrganizationWorkSessionCommand,
    ): ProblemOrganizationWorkSessionMutationResult

    suspend fun transition(
        command: ProblemOrganizationWorkSessionTransition,
    ): ProblemOrganizationWorkSessionMutationResult

    suspend fun readRunningRecoveryPage(
        query: ProblemOrganizationWorkRecoveryQuery,
    ): List<ProblemOrganizationWorkSessionSnapshot>
}

private const val MAX_SESSION_FAILURE_MESSAGE_CHARS = 500
private const val MAX_ORGANIZATION_RECOVERY_PAGE_SIZE = 100
