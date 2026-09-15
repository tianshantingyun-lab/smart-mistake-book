package com.tingyun.smartmistakebook

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

internal object OrphanAssetGc {
    const val UNIQUE_NAME = "orphan-asset-gc"
    const val REMOVED_COUNT_KEY = "removedCount"

    val existingPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP

    fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<OrphanAssetGcWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build(),
        )
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

internal enum class OrphanAssetGcPhase {
    IDLE,
    SCHEDULED,
    RUNNING,
    FAILED,
}

internal fun orphanAssetGcPhase(states: Collection<String>): OrphanAssetGcPhase {
    if (states.any { it == "RUNNING" }) return OrphanAssetGcPhase.RUNNING
    if (states.any { it == "ENQUEUED" || it == "BLOCKED" }) return OrphanAssetGcPhase.SCHEDULED
    if (states.any { it == "FAILED" }) return OrphanAssetGcPhase.FAILED
    return OrphanAssetGcPhase.IDLE
}

internal fun orphanAssetGcStatusLine(phase: OrphanAssetGcPhase): String? = when (phase) {
    OrphanAssetGcPhase.IDLE -> null
    OrphanAssetGcPhase.SCHEDULED, OrphanAssetGcPhase.RUNNING -> "正在清理不再使用的原图"
    OrphanAssetGcPhase.FAILED -> "这次没清理完，原图没有改动。"
}

class OrphanAssetGcWorker(
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
            val removed = app.backupRepository.cleanupOrphanAssets()
            Result.success(workDataOf(OrphanAssetGc.REMOVED_COUNT_KEY to removed))
        } catch (_: Exception) {
            retryOrFail()
        }
    }

    /**
     * 有限重试：超过上限转 FAILED，让存储页的"这次没清理完"状态行真正可达
     * （无限 retry 会让界面永远显示"正在清理"而从不报结果）。
     */
    private fun retryOrFail(): Result =
        if (runAttemptCount >= MAX_ATTEMPTS - 1) Result.failure() else Result.retry()

    internal companion object {
        const val MAX_ATTEMPTS = 3
    }
}
