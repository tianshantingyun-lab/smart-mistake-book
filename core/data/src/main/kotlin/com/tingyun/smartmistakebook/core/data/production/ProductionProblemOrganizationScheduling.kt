package com.tingyun.smartmistakebook.core.data.production

import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkRecoveryCursor
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkRecoveryQuery
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionPort
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionSnapshot
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionStatus
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Minimal durable scheduling state. It contains no request payload, proof, learner id, or answer. */
data class ProductionProblemOrganizationWorkSchedule(
    val workId: String,
    val stateVersion: Long,
    val eligibleAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val executionLeaseToken: String,
) {
    init {
        require(workId.isSafeSchedulingId())
        require(stateVersion >= 0L)
        require(eligibleAtEpochMillis >= 0L && updatedAtEpochMillis >= 0L)
        require(executionLeaseToken.matches(EXECUTION_LEASE_TOKEN_PATTERN))
    }
}

data class ProductionProblemOrganizationRecoveryCursor(
    val eligibleAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val workId: String,
) {
    init {
        require(eligibleAtEpochMillis >= 0L && updatedAtEpochMillis >= 0L)
        require(workId.isSafeSchedulingId())
    }
}

data class ProductionProblemOrganizationRecoveryQuery(
    val limit: Int,
    val after: ProductionProblemOrganizationRecoveryCursor? = null,
) {
    init {
        require(limit in 1..MAX_PRODUCTION_RECOVERY_PAGE_SIZE)
    }
}

/** Read-only, learner-bound coordination surface issued only by the production authority. */
sealed interface ProductionProblemOrganizationScheduling {
    fun observeSchedulable(): Flow<List<ProductionProblemOrganizationWorkSchedule>>

    suspend fun readRunningRecoveryPage(
        query: ProductionProblemOrganizationRecoveryQuery,
    ): List<ProductionProblemOrganizationWorkSchedule>

    companion object {
        @JvmSynthetic
        internal fun issue(
            scope: SessionScope,
            sessions: ProblemOrganizationWorkSessionPort,
            leaseAuthority: ProductionProblemOrganizationExecutionLeaseAuthority,
            productionGenerationIsCurrent: () -> Boolean,
        ): ProductionProblemOrganizationScheduling =
            Issued(
                scope = scope,
                sessions = sessions,
                leaseAuthority = leaseAuthority,
                productionGenerationIsCurrent = productionGenerationIsCurrent,
            )
    }

    private class Issued(
        private val scope: SessionScope,
        private val sessions: ProblemOrganizationWorkSessionPort,
        private val leaseAuthority: ProductionProblemOrganizationExecutionLeaseAuthority,
        private val productionGenerationIsCurrent: () -> Boolean,
    ) : ProductionProblemOrganizationScheduling {
        override fun observeSchedulable():
            Flow<List<ProductionProblemOrganizationWorkSchedule>> {
            requireCurrentGeneration()
            return sessions.observeSchedulable(scope).map { snapshots ->
                requireCurrentGeneration()
                snapshots.map(::toSchedulableState).also {
                    requireCurrentGeneration()
                }
            }
        }

        override suspend fun readRunningRecoveryPage(
            query: ProductionProblemOrganizationRecoveryQuery,
        ): List<ProductionProblemOrganizationWorkSchedule> {
            requireCurrentGeneration()
            val snapshots = sessions.readRunningRecoveryPage(
                ProblemOrganizationWorkRecoveryQuery(
                    scope = scope,
                    limit = query.limit,
                    after = query.after?.toSessionCursor(),
                ),
            )
            requireCurrentGeneration()
            return snapshots.map(::toRunningRecoveryState).also {
                requireCurrentGeneration()
            }
        }

        private fun requireCurrentGeneration() {
            check(productionGenerationIsCurrent()) {
                "Problem organization scheduling generation is no longer current"
            }
        }

        private fun toSchedulableState(
            snapshot: ProblemOrganizationWorkSessionSnapshot,
        ): ProductionProblemOrganizationWorkSchedule =
            snapshot.toSchedulableState(
                leaseAuthority.issue(snapshot.workId, snapshot.version.sequence),
            )

        private fun toRunningRecoveryState(
            snapshot: ProblemOrganizationWorkSessionSnapshot,
        ): ProductionProblemOrganizationWorkSchedule =
            snapshot.toRunningRecoveryState(
                leaseAuthority.issue(snapshot.workId, snapshot.version.sequence),
            )
    }
}

private fun ProblemOrganizationWorkSessionSnapshot.toSchedulableState(
    executionLeaseToken: String,
):
    ProductionProblemOrganizationWorkSchedule {
    check(
        status == ProblemOrganizationWorkSessionStatus.PENDING ||
            status == ProblemOrganizationWorkSessionStatus.RETRY ||
            status == ProblemOrganizationWorkSessionStatus.WAITING_AUTHORIZATION,
    ) { "Scheduling feed returned work that is not schedulable" }
    return ProductionProblemOrganizationWorkSchedule(
        workId = workId,
        stateVersion = version.sequence,
        eligibleAtEpochMillis = notBeforeEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        executionLeaseToken = executionLeaseToken,
    )
}

private fun ProblemOrganizationWorkSessionSnapshot.toRunningRecoveryState(
    executionLeaseToken: String,
):
    ProductionProblemOrganizationWorkSchedule {
    check(status == ProblemOrganizationWorkSessionStatus.RUNNING) {
        "Running recovery returned work that is not running"
    }
    return ProductionProblemOrganizationWorkSchedule(
        workId = workId,
        stateVersion = version.sequence,
        eligibleAtEpochMillis = requireNotNull(leaseExpiresAtEpochMillis) {
            "Running recovery requires a lease expiry"
        },
        updatedAtEpochMillis = updatedAtEpochMillis,
        executionLeaseToken = executionLeaseToken,
    )
}

private fun ProductionProblemOrganizationRecoveryCursor.toSessionCursor() =
    ProblemOrganizationWorkRecoveryCursor(
        leaseExpiresAtEpochMillis = eligibleAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        workId = workId,
    )

private fun String.isSafeSchedulingId(): Boolean =
    isNotBlank() && this == trim() && length <= 256 && none(Char::isISOControl)

private const val MAX_PRODUCTION_RECOVERY_PAGE_SIZE = 100
private val EXECUTION_LEASE_TOKEN_PATTERN = Regex("[0-9a-f]{64}")
