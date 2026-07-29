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
import com.tingyun.smartmistakebook.core.data.mistake.ProblemOrganizationWorkProcessResult
import com.tingyun.smartmistakebook.core.data.mistake.ProblemOrganizationWorkAuthorizationResult
import com.tingyun.smartmistakebook.core.data.mistake.ProblemOrganizationWorkProcessor
import com.tingyun.smartmistakebook.core.database.ProblemOrganizationWorkRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal class ProblemOrganizationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
    private val processor: ProblemOrganizationWorkProcessor,
    private val scheduler: ProblemOrganizationWorkScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val workId = inputData.getString(WORK_ID_KEY)?.takeIf(String::isNotBlank)
            ?: return missingWorkIdResult()

        return try {
            when (processor.authorizeStoredGrant(workId, clock())) {
                ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization,
                ProblemOrganizationWorkAuthorizationResult.LostLease,
                -> return Result.success()

                is ProblemOrganizationWorkAuthorizationResult.Authorized,
                ProblemOrganizationWorkAuthorizationResult.NotWaiting,
                -> Unit
            }
            when (val result = processor.process(workId, UUID.randomUUID().toString(), clock())) {
                is ProblemOrganizationWorkProcessResult.RetryScheduled -> {
                    scheduler.enqueue(workId, result.notBeforeEpochMillis)
                    Result.success()
                }

                ProblemOrganizationWorkProcessResult.Succeeded,
                ProblemOrganizationWorkProcessResult.WaitingAuthorization,
                ProblemOrganizationWorkProcessResult.PermanentFailure,
                -> Result.success()

                ProblemOrganizationWorkProcessResult.LostLease -> {
                    processor.recoveryNotBeforeEpochMillis(workId)?.let {
                        scheduler.enqueue(workId, it)
                    }
                    Result.success()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    internal companion object {
        const val WORK_ID_KEY = "problem_organization_work_id"

        fun missingWorkIdResult(): Result = Result.failure()
    }
}

internal class ProblemOrganizationWorkerFactory(
    private val dependencies: () -> ProblemOrganizationWorkerDependencies?,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (!supports(workerClassName)) return null
        val graph = dependencies() ?: return null
        return ProblemOrganizationWorker(
            appContext = appContext,
            workerParameters = workerParameters,
            processor = ProblemOrganizationWorkProcessor(
                database = graph.database,
                modelTasks = graph.modelTasks,
                organizations = graph.organizations,
            ),
            scheduler = graph.scheduler,
        )
    }

    internal companion object {
        fun supports(workerClassName: String): Boolean =
            workerClassName == ProblemOrganizationWorker::class.java.name
    }
}

internal data class ProblemOrganizationWorkerDependencies(
    val database: StudyDatabasePort,
    val modelTasks: ModelTaskRepository,
    val organizations: MistakeOrganizationRepository,
    val scheduler: ProblemOrganizationWorkScheduler,
)

internal class ProblemOrganizationWorkScheduler(
    private val workManager: WorkManager,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun enqueue(workId: String, notBeforeEpochMillis: Long) {
        require(workId.isNotBlank()) { "workId must not be blank" }
        require(notBeforeEpochMillis >= 0) { "notBeforeEpochMillis must not be negative" }
        workManager.enqueueUniqueWork(
            uniqueWorkName(workId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(workId, notBeforeEpochMillis),
        )
    }

    fun enqueue(record: ProblemOrganizationWorkRecord) = enqueue(
        workId = record.workId,
        notBeforeEpochMillis = eligibleAtEpochMillis(record),
    )

    fun enqueueRunningRecovery(record: ProblemOrganizationWorkRecord) {
        require(record.status == "RUNNING") { "Only running work needs lease recovery" }
        val leaseExpiry = requireNotNull(record.leaseExpiresAtEpochMillis) {
            "Running work recovery requires a lease expiry"
        }
        workManager.enqueueUniqueWork(
            recoveryWorkName(record.workId, record.stateVersion),
            ExistingWorkPolicy.KEEP,
            request(record.workId, leaseExpiry),
        )
    }

    private fun request(workId: String, notBeforeEpochMillis: Long) =
        OneTimeWorkRequestBuilder<ProblemOrganizationWorker>()
            .setInputData(Data.Builder().putString(ProblemOrganizationWorker.WORK_ID_KEY, workId).build())
            .setConstraints(networkConstraints())
            .setInitialDelay(initialDelayMillis(notBeforeEpochMillis, clock()), TimeUnit.MILLISECONDS)
            .build()

    internal companion object {
        fun initialDelayMillis(notBeforeEpochMillis: Long, nowEpochMillis: Long): Long =
            (notBeforeEpochMillis - nowEpochMillis).coerceAtLeast(0)

        fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun eligibleAtEpochMillis(record: ProblemOrganizationWorkRecord): Long = when (record.status) {
            "RUNNING" -> record.leaseExpiresAtEpochMillis ?: 0L
            else -> record.notBeforeEpochMillis
        }

        fun uniqueWorkName(workId: String): String = "problem-organization:$workId"

        fun recoveryWorkName(workId: String, stateVersion: Long): String =
            "problem-organization-recovery:$workId:$stateVersion"
    }
}
