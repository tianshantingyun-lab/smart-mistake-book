package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Provider adapter. Implementations cannot access Room or mutate learning facts. */
interface ModelGateway {
    suspend fun capabilities(): ProviderCapabilitySnapshot

    fun execute(execution: ModelGatewayExecution): Flow<ModelGatewayEvent>
}

/**
 * Durable application boundary for model work. Every emitted state has already been persisted, so
 * UI collectors may leave and return without owning the operation's source of truth.
 */
interface ModelTaskRepository {
    suspend fun capabilities(): ProviderCapabilitySnapshot

    fun observe(requestId: String): Flow<ModelTaskSnapshot?>

    /** Durable conversation recovery, bounded to one subject and task kind. */
    fun observeBySubject(
        subjectId: String,
        kind: ModelTaskKind,
    ): Flow<List<ModelTaskSnapshot>> = flowOf(emptyList())

    /** Recent durable recovery, oldest-to-newest, without loading an unbounded conversation. */
    fun observeRecentBySubject(
        subjectId: String,
        kind: ModelTaskKind,
        limit: Int,
    ): Flow<List<ModelTaskSnapshot>> {
        require(limit > 0) { "Recent model-task limit must be positive" }
        return observeBySubject(subjectId, kind).map { snapshots -> snapshots.takeLast(limit) }
    }

    fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot>
}
