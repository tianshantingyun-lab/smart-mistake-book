package com.tingyun.smartmistakebook

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionLease
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionRequest

/** Initializes the target process with WorkManager's deterministic test scheduler before onCreate. */
class SmartMistakeBookTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        val executor = SynchronousExecutor()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setExecutor(executor)
                .setTaskExecutor(executor)
                .setWorkerFactory(ProblemOrganizationInstrumentedTestWorkerFactory())
                .build(),
        )
        super.callApplicationOnCreate(app)
    }
}

internal object ProblemOrganizationWorkerInstrumentedTestControl {
    private val unavailableExecution =
        ProblemOrganizationWorkerExecution { _, _ ->
            ProblemOrganizationWorkerExecutionOutcome.Unavailable
        }

    @Volatile
    private var execution = unavailableExecution

    fun install(execution: ProblemOrganizationWorkerExecution) {
        this.execution = execution
    }

    fun reset() {
        execution = unavailableExecution
    }

    suspend fun execute(
        lease: ProductionProblemOrganizationExecutionLease,
        request: ProductionProblemOrganizationExecutionRequest,
    ): ProblemOrganizationWorkerExecutionOutcome = execution.execute(lease, request)
}

private class ProblemOrganizationInstrumentedTestWorkerFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (!ProblemOrganizationWorkerFactory.supports(workerClassName)) return null
        return ProblemOrganizationWorker(
            appContext = appContext,
            workerParameters = workerParameters,
            execution = ProblemOrganizationWorkerExecution { lease, request ->
                ProblemOrganizationWorkerInstrumentedTestControl.execute(lease, request)
            },
        )
    }
}
