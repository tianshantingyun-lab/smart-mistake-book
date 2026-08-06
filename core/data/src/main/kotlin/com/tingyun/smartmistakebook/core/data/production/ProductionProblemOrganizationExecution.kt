package com.tingyun.smartmistakebook.core.data.production

import com.tingyun.smartmistakebook.core.data.mistake.ProblemOrganizationWorkAuthorizationResult
import com.tingyun.smartmistakebook.core.data.mistake.ProblemOrganizationWorkProcessResult
import com.tingyun.smartmistakebook.core.data.mistake.ProblemOrganizationWorkProcessor
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemOrganizationCommitFence
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock

/** Persistable opaque lease. Its token is bound to one owner instance, work id, and state version. */
data class ProductionProblemOrganizationExecutionLease(
    val workId: String,
    val stateVersion: Long,
    val token: String,
) {
    init {
        require(workId.isSafeExecutionIdentifier())
        require(stateVersion >= 0L)
        require(token.matches(EXECUTION_LEASE_TOKEN_PATTERN))
    }
}

/** Minimal request accepted by an executor already bound to one exact durable work occurrence. */
data class ProductionProblemOrganizationExecutionRequest(
    val attemptToken: String,
    val requestedAtEpochMillis: Long,
) {
    init {
        require(attemptToken.isSafeExecutionIdentifier())
        require(requestedAtEpochMillis >= 0L)
    }
}

/** Scheduling-only outcome; all authorization and organization details remain inside core:data. */
sealed interface ProductionProblemOrganizationExecutionResult {
    data object Finished : ProductionProblemOrganizationExecutionResult

    /** A durable RETRY transition already published a new state version for the scheduling feed. */
    data class DurableRetryPublished(
        val eligibleAtEpochMillis: Long,
    ) : ProductionProblemOrganizationExecutionResult {
        init {
            require(eligibleAtEpochMillis >= 0L)
        }
    }

    /** The current RUNNING version still exists and must be recovered at its lease expiry. */
    data class RunningLeaseRecoveryRequired(
        val eligibleAtEpochMillis: Long,
    ) : ProductionProblemOrganizationExecutionResult {
        init {
            require(eligibleAtEpochMillis >= 0L)
        }
    }
}

/** Signals an owner/generation revocation; WorkManager must terminally discard that invocation. */
class ProductionProblemOrganizationExecutionRevokedException internal constructor() :
    IllegalStateException("Problem organization execution owner is no longer current")

/**
 * Linearization gate shared by generation revocation and the complete final student-store commit.
 *
 * A commit reserves the current generation before entering Room and keeps that reservation until
 * Room returns after commit or rollback. Revocation rejects new reservations immediately, waits for
 * every earlier reservation to leave Room, and only then returns. The gate exposes only the narrow
 * commit-fence contract; it has no learner, SQL, model, mastery, or cross-database capability.
 */
internal class ProductionProblemOrganizationCommitLinearizer(
    private val upstreamGenerationIsCurrent: () -> Boolean,
) : StudentProblemOrganizationCommitFence,
    AutoCloseable {
    private val lock = ReentrantLock()
    private val noActiveCommits = lock.newCondition()
    private var revocationRequested = false
    private var activeCommitCount = 0

    fun isCurrent(): Boolean =
        lock.withLock {
            !revocationRequested && upstreamGenerationIsCurrent()
        }

    override fun requireCurrentOwner() {
        lock.withLock {
            requireCurrentLocked()
        }
    }

    override suspend fun <Result> linearizeCommit(
        operation: suspend () -> Result,
    ): Result {
        reserveCommit()
        return try {
            operation()
        } finally {
            releaseCommit()
        }
    }

    override fun close() {
        lock.withLock {
            revocationRequested = true
            while (activeCommitCount != 0) {
                noActiveCommits.awaitUninterruptibly()
            }
        }
    }

    private fun reserveCommit() {
        lock.withLock {
            requireCurrentLocked()
            activeCommitCount = Math.addExact(activeCommitCount, 1)
        }
    }

    private fun releaseCommit() {
        lock.withLock {
            check(activeCommitCount > 0) { "Problem organization commit reservation underflow" }
            activeCommitCount -= 1
            if (activeCommitCount == 0) noActiveCommits.signalAll()
        }
    }

    private fun requireCurrentLocked() {
        if (revocationRequested || !upstreamGenerationIsCurrent()) {
            throw ProductionProblemOrganizationExecutionRevokedException()
        }
    }
}

/**
 * Resolves only leases issued by this exact current-generation owner. Invalid, stale, copied to a
 * different work, or copied to a different state version all resolve to null without touching data.
 */
sealed interface ProductionProblemOrganizationExecutionResolver {
    fun resolve(
        lease: ProductionProblemOrganizationExecutionLease,
    ): ProductionProblemOrganizationExecution?
}

@JvmSynthetic
internal fun issueProductionProblemOrganizationExecutionResolver(
    processor: ProblemOrganizationWorkProcessor,
    leaseAuthority: ProductionProblemOrganizationExecutionLeaseAuthority,
    productionGenerationIsCurrent: () -> Boolean,
): ProductionProblemOrganizationExecutionResolver =
    IssuedProductionProblemOrganizationExecutionResolver(
        processor = processor,
        leaseAuthority = leaseAuthority,
        productionGenerationIsCurrent = productionGenerationIsCurrent,
    )

private class IssuedProductionProblemOrganizationExecutionResolver(
    private val processor: ProblemOrganizationWorkProcessor,
    private val leaseAuthority: ProductionProblemOrganizationExecutionLeaseAuthority,
    private val productionGenerationIsCurrent: () -> Boolean,
) : ProductionProblemOrganizationExecutionResolver {
    override fun resolve(
        lease: ProductionProblemOrganizationExecutionLease,
    ): ProductionProblemOrganizationExecution? {
        if (!productionGenerationIsCurrent()) return null
        if (!leaseAuthority.matches(lease)) return null
        if (!productionGenerationIsCurrent()) return null
        return ProductionProblemOrganizationExecution.issue(
            processor = processor,
            workId = lease.workId,
            expectedStateVersion = lease.stateVersion,
            productionGenerationIsCurrent = productionGenerationIsCurrent,
        )
    }
}

/** Generation-bound executor that is already bound to one work id and state version. */
sealed interface ProductionProblemOrganizationExecution {
    suspend fun execute(
        request: ProductionProblemOrganizationExecutionRequest,
    ): ProductionProblemOrganizationExecutionResult

    companion object {
        @JvmSynthetic
        internal fun issue(
            processor: ProblemOrganizationWorkProcessor,
            workId: String,
            expectedStateVersion: Long,
            productionGenerationIsCurrent: () -> Boolean,
        ): ProductionProblemOrganizationExecution =
            issue(
                executeOperation = { request ->
                    executeWithProcessor(
                        processor = processor,
                        workId = workId,
                        expectedStateVersion = expectedStateVersion,
                        request = request,
                        productionGenerationIsCurrent = productionGenerationIsCurrent,
                    )
                },
                productionGenerationIsCurrent = productionGenerationIsCurrent,
            )

        @JvmSynthetic
        internal fun issue(
            executeOperation: suspend (
                ProductionProblemOrganizationExecutionRequest,
            ) -> ProductionProblemOrganizationExecutionResult,
            productionGenerationIsCurrent: () -> Boolean,
        ): ProductionProblemOrganizationExecution =
            Issued(
                executeOperation = executeOperation,
                productionGenerationIsCurrent = productionGenerationIsCurrent,
            )
    }

    private class Issued(
        private val executeOperation: suspend (
            ProductionProblemOrganizationExecutionRequest,
        ) -> ProductionProblemOrganizationExecutionResult,
        private val productionGenerationIsCurrent: () -> Boolean,
    ) : ProductionProblemOrganizationExecution {
        override suspend fun execute(
            request: ProductionProblemOrganizationExecutionRequest,
        ): ProductionProblemOrganizationExecutionResult {
            requireCurrentExecutionGeneration(productionGenerationIsCurrent)
            val result = executeOperation(request)
            requireCurrentExecutionGeneration(productionGenerationIsCurrent)
            return result
        }
    }
}

internal class ProductionProblemOrganizationExecutionLeaseAuthority(
    secret: ByteArray = ByteArray(EXECUTION_LEASE_SECRET_BYTES).also(SecureRandom()::nextBytes),
) : AutoCloseable {
    private val secret = secret.copyOf()
    private var closed = false

    init {
        require(secret.size >= EXECUTION_LEASE_SECRET_BYTES)
    }

    @Synchronized
    fun issue(
        workId: String,
        stateVersion: Long,
    ): String {
        check(!closed) { "Problem organization execution lease authority is closed" }
        require(workId.isSafeExecutionIdentifier())
        require(stateVersion >= 0L)
        return signature(workId, stateVersion)
    }

    @Synchronized
    fun matches(lease: ProductionProblemOrganizationExecutionLease): Boolean {
        if (closed) return false
        val expected = signature(lease.workId, lease.stateVersion)
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.US_ASCII),
            lease.token.toByteArray(StandardCharsets.US_ASCII),
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        Arrays.fill(secret, 0.toByte())
    }

    private fun signature(
        workId: String,
        stateVersion: Long,
    ): String {
        val mac = Mac.getInstance(EXECUTION_LEASE_MAC)
        mac.init(SecretKeySpec(secret, EXECUTION_LEASE_MAC))
        val payload = "$workId\u0000$stateVersion".toByteArray(StandardCharsets.UTF_8)
        return mac.doFinal(payload).joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}

private suspend fun executeWithProcessor(
    processor: ProblemOrganizationWorkProcessor,
    workId: String,
    expectedStateVersion: Long,
    request: ProductionProblemOrganizationExecutionRequest,
    productionGenerationIsCurrent: () -> Boolean,
): ProductionProblemOrganizationExecutionResult {
    if (!processor.matchesScheduledStateVersion(workId, expectedStateVersion)) {
        return ProductionProblemOrganizationExecutionResult.Finished
    }
    requireCurrentExecutionGeneration(productionGenerationIsCurrent)
    val authorization = processor.authorizeStoredGrant(
        workId = workId,
        nowEpochMillis = request.requestedAtEpochMillis,
    )
    requireCurrentExecutionGeneration(productionGenerationIsCurrent)
    when (authorization) {
        ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization,
        ProblemOrganizationWorkAuthorizationResult.LostLease,
        -> return ProductionProblemOrganizationExecutionResult.Finished

        is ProblemOrganizationWorkAuthorizationResult.Authorized,
        ProblemOrganizationWorkAuthorizationResult.NotWaiting,
        -> Unit
    }

    val result = processor.process(
        workId = workId,
        leaseOwner = request.attemptToken,
        nowEpochMillis = request.requestedAtEpochMillis,
    )
    requireCurrentExecutionGeneration(productionGenerationIsCurrent)
    return when (result) {
        is ProblemOrganizationWorkProcessResult.RetryScheduled ->
            ProductionProblemOrganizationExecutionResult.DurableRetryPublished(
                result.notBeforeEpochMillis,
            )

        ProblemOrganizationWorkProcessResult.LostLease -> {
            val recoveryAt = processor.recoveryNotBeforeEpochMillis(workId)
            requireCurrentExecutionGeneration(productionGenerationIsCurrent)
            recoveryAt?.let {
                ProductionProblemOrganizationExecutionResult.RunningLeaseRecoveryRequired(it)
            }
                ?: ProductionProblemOrganizationExecutionResult.Finished
        }

        ProblemOrganizationWorkProcessResult.Succeeded,
        ProblemOrganizationWorkProcessResult.WaitingAuthorization,
        ProblemOrganizationWorkProcessResult.PermanentFailure,
        -> ProductionProblemOrganizationExecutionResult.Finished
    }
}

private fun requireCurrentExecutionGeneration(
    productionGenerationIsCurrent: () -> Boolean,
) {
    if (!productionGenerationIsCurrent()) {
        throw ProductionProblemOrganizationExecutionRevokedException()
    }
}

private fun String.isSafeExecutionIdentifier(): Boolean =
    isNotBlank() && this == trim() && length <= 256 && none(Char::isISOControl)

private const val EXECUTION_LEASE_SECRET_BYTES = 32
private const val EXECUTION_LEASE_MAC = "HmacSHA256"
private val EXECUTION_LEASE_TOKEN_PATTERN = Regex("[0-9a-f]{64}")
