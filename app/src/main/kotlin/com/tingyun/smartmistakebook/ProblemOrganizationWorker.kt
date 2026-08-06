package com.tingyun.smartmistakebook

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.await
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionLease
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionRequest
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionResolver
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionResult
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionRevokedException
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationWorkSchedule
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal fun interface ProblemOrganizationWorkerExecution {
    suspend fun execute(
        lease: ProductionProblemOrganizationExecutionLease,
        request: ProductionProblemOrganizationExecutionRequest,
    ): ProblemOrganizationWorkerExecutionOutcome
}

internal sealed interface ProblemOrganizationWorkerExecutionOutcome {
    data object Unavailable : ProblemOrganizationWorkerExecutionOutcome

    data object Stale : ProblemOrganizationWorkerExecutionOutcome

    data class Executed(
        val result: ProductionProblemOrganizationExecutionResult,
    ) : ProblemOrganizationWorkerExecutionOutcome
}

internal class ProblemOrganizationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
    private val execution: ProblemOrganizationWorkerExecution,
    private val clock: () -> Long = System::currentTimeMillis,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val workId = inputData.getString(WORK_ID_KEY)?.takeIf(String::isNotBlank)
            ?: return missingWorkIdResult()
        val stateVersion = inputData.getLong(STATE_VERSION_KEY, MISSING_STATE_VERSION)
        val leaseToken = inputData.getString(EXECUTION_LEASE_TOKEN_KEY)
            ?: return invalidExecutionLeaseResult()
        val lease =
            try {
                ProductionProblemOrganizationExecutionLease(
                    workId = workId,
                    stateVersion = stateVersion,
                    token = leaseToken,
                )
            } catch (_: IllegalArgumentException) {
                return invalidExecutionLeaseResult()
            }

        return try {
            when (
                val outcome =
                    execution.execute(
                        lease,
                        ProductionProblemOrganizationExecutionRequest(
                            attemptToken = UUID.randomUUID().toString(),
                            requestedAtEpochMillis = clock(),
                        ),
                    )
            ) {
                ProblemOrganizationWorkerExecutionOutcome.Unavailable ->
                    unavailableCapabilityResult()

                ProblemOrganizationWorkerExecutionOutcome.Stale -> staleExecutionLeaseResult()

                is ProblemOrganizationWorkerExecutionOutcome.Executed ->
                    outcome.result.toWorkerResult()
            }
        } catch (_: ProductionProblemOrganizationExecutionRevokedException) {
            staleExecutionLeaseResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            executionFailureResult(failure)
        }
    }

    private fun ProductionProblemOrganizationExecutionResult.toWorkerResult(): Result =
        when (this) {
            is ProductionProblemOrganizationExecutionResult.DurableRetryPublished -> {
                // The durable transition emitted a new state version. The owner-bound feed
                // issues the next execution lease and schedules that exact occurrence.
                Result.success()
            }

            is ProductionProblemOrganizationExecutionResult.RunningLeaseRecoveryRequired -> {
                // The continuously reconciled RUNNING feed schedules this exact state version
                // at its persisted lease expiry. WorkManager retry backoff must not replay the
                // older scheduling version that lost the claim.
                Result.success()
            }

            ProductionProblemOrganizationExecutionResult.Finished -> Result.success()
        }

    internal companion object {
        const val WORK_ID_KEY = "problem_organization_work_id"
        const val STATE_VERSION_KEY = "problem_organization_state_version"
        const val EXECUTION_LEASE_TOKEN_KEY = "problem_organization_execution_lease"
        private const val MISSING_STATE_VERSION = -1L

        fun missingWorkIdResult(): Result = Result.failure()

        fun invalidExecutionLeaseResult(): Result = Result.failure()

        fun staleExecutionLeaseResult(): Result = Result.success()

        fun executionFailureResult(failure: Exception): Result =
            if (failure is IOException) Result.retry() else Result.failure()

        // The durable business row remains schedulable. Returning success avoids WorkManager
        // backoff delaying the authority-owned scheduling feed that starts immediately after
        // atomic publication.
        fun unavailableCapabilityResult(): Result = Result.success()
    }
}

internal class ProblemOrganizationWorkerFactory(
    private val executionResolverProvider: () -> ProductionProblemOrganizationExecutionResolver?,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (!supports(workerClassName)) return null
        return ProblemOrganizationWorker(
            appContext = appContext,
            workerParameters = workerParameters,
            execution = productionWorkerExecution(executionResolverProvider),
        )
    }

    internal companion object {
        fun supports(workerClassName: String): Boolean =
            workerClassName == ProblemOrganizationWorker::class.java.name
    }
}

private fun productionWorkerExecution(
    executionResolverProvider: () -> ProductionProblemOrganizationExecutionResolver?,
) = ProblemOrganizationWorkerExecution { lease, request ->
    val resolver = executionResolverProvider()
    if (resolver == null) {
        ProblemOrganizationWorkerExecutionOutcome.Unavailable
    } else {
        val resolved = resolver.resolve(lease)
        if (resolved == null) {
            ProblemOrganizationWorkerExecutionOutcome.Stale
        } else {
            ProblemOrganizationWorkerExecutionOutcome.Executed(resolved.execute(request))
        }
    }
}

internal class ProblemOrganizationWorkScheduler(
    private val workManager: WorkManager,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun enqueue(schedule: ProductionProblemOrganizationWorkSchedule) {
        workManager
            .enqueueUniqueWork(
                uniqueWorkName(schedule),
                ExistingWorkPolicy.KEEP,
                request(schedule),
            ).await()
    }

    suspend fun enqueueRunningRecovery(schedule: ProductionProblemOrganizationWorkSchedule) {
        workManager
            .enqueueUniqueWork(
                recoveryWorkName(
                    schedule.workId,
                    schedule.stateVersion,
                    schedule.executionLeaseToken,
                ),
                ExistingWorkPolicy.KEEP,
                request(schedule),
            ).await()
    }

    private fun request(schedule: ProductionProblemOrganizationWorkSchedule) =
        OneTimeWorkRequestBuilder<ProblemOrganizationWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ProblemOrganizationWorker.WORK_ID_KEY, schedule.workId)
                    .putLong(ProblemOrganizationWorker.STATE_VERSION_KEY, schedule.stateVersion)
                    .putString(
                        ProblemOrganizationWorker.EXECUTION_LEASE_TOKEN_KEY,
                        schedule.executionLeaseToken,
                    )
                    .build(),
            )
            .setConstraints(networkConstraints())
            .setInitialDelay(
                initialDelayMillis(schedule.eligibleAtEpochMillis, clock()),
                TimeUnit.MILLISECONDS,
            )
            .build()

    internal companion object {
        fun initialDelayMillis(notBeforeEpochMillis: Long, nowEpochMillis: Long): Long =
            (notBeforeEpochMillis - nowEpochMillis).coerceAtLeast(0)

        fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun uniqueWorkName(schedule: ProductionProblemOrganizationWorkSchedule): String =
            "problem-organization:${schedule.workId}:${schedule.stateVersion}:" +
                schedule.executionLeaseToken

        fun recoveryWorkName(
            workId: String,
            stateVersion: Long,
            executionLeaseToken: String,
        ): String =
            "problem-organization-recovery:$workId:$stateVersion:$executionLeaseToken"
    }
}
