package com.tingyun.smartmistakebook.core.data.capture

import android.content.Context
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CoroutineScope

/** Instrumentation-classpath-only bridge for exercising pre-cutover migration behavior. */
internal object TestOnlyLegacyBatchImportRepositoryFactory {
    fun create(
        context: Context,
        database: StudyDatabasePort,
        capture: CaptureWorkflowRepository,
        processingScope: CoroutineScope,
        modelTasks: ModelTaskRepository? = null,
    ): BatchImportRepository {
        try {
            val factoryType =
                Class.forName(
                    "com.tingyun.smartmistakebook.core.data.session.migration." +
                        "LegacyBatchImportRepositoryFactory",
                )
            val factory =
                factoryType.getDeclaredField("INSTANCE")
                    .apply { isAccessible = true }
                    .get(null)
            val resolvedModelTasks = modelTasks ?: unavailableModelTasks()
            val create =
                factoryType.declaredMethods.single { method ->
                    method.name == "create" && method.parameterCount == 5
                }.apply { isAccessible = true }
            return create.invoke(
                factory,
                context,
                database,
                capture,
                processingScope,
                resolvedModelTasks,
            ) as BatchImportRepository
        } catch (failure: InvocationTargetException) {
            throw failure.targetException.asRuntimeFailure()
        }
    }

    private fun unavailableModelTasks(): ModelTaskRepository {
        val type =
            Class.forName(
                "com.tingyun.smartmistakebook.core.data.session.migration." +
                    "BatchOrganizationUnavailableModelTasks",
            )
        return type.getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null) as ModelTaskRepository
    }

    private fun Throwable.asRuntimeFailure(): RuntimeException =
        when (this) {
            is RuntimeException -> this
            is Error -> throw this
            else -> IllegalStateException("Legacy migration fixture failed", this)
        }
}
