package com.tingyun.smartmistakebook

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * Keeps a running batch import moving when the app is not on screen.
 *
 * The import's own pass runs on the application scope, so it dies with the
 * process: a student who starts a 30-page import and leaves the app gets no
 * further progress until they open it again. Nothing is lost in that window —
 * every page is a committed transaction and the next pass re-reads the ledger —
 * but the import silently stalls, which reads as a hang.
 *
 * Enqueued both when a job is scheduled (so the durable request exists before it
 * is needed) and at startup (so an import interrupted by a process kill is picked
 * up without waiting for the student to do anything).
 *
 * Honest limit: this does not survive the student force-stopping the app. It
 * covers system reclamation and screen-off.
 */
internal object BatchImportDriver {
    const val UNIQUE_NAME = "batch-import-driver"

    val existingPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP

    /**
     * No constraints: the import is student-initiated and already visible on
     * screen, so deferring it to "not low battery" would be a worse trade than
     * running it now (unlike the orphan GC, which is background housekeeping).
     */
    fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<BatchImportDriverWorker>()
        .addTag(UNIQUE_NAME)
        .build()

    fun enqueue(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_NAME,
            existingPolicy,
            request(),
        )
    }
}

class BatchImportDriverWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? SmartMistakeBookApplication
            ?: return retryOrFail()
        if (app.startupState.value !is StartupState.Ready) {
            return retryOrFail()
        }
        return try {
            if (app.batchImportRepository.drivePendingImports()) {
                Result.success()
            } else {
                // Still PROCESSING after a full pass: the pass was stopped by the
                // system's execution limit rather than finishing. Retrying is safe
                // because each page transition is a committed transaction, so the
                // next pass resumes instead of repeating work.
                retryOrFail()
            }
        } catch (cancelled: CancellationException) {
            // WorkManager stopped us; the work is re-scheduled by the policy, so
            // swallowing this would lose the resumption.
            throw cancelled
        } catch (_: Exception) {
            retryOrFail()
        }
    }

    /**
     * 有限重试：超过上限转 FAILED。导入本身可被学生手动重试或下次启动续跑，
     * 不需要无限重试占着 WorkManager 的队列。
     */
    private fun retryOrFail(): Result =
        if (runAttemptCount >= MAX_ATTEMPTS - 1) Result.failure() else Result.retry()

    internal companion object {
        const val MAX_ATTEMPTS = 3
    }
}
