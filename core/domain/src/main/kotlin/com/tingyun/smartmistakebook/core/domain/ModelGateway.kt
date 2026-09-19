package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelLiveText
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

    /**
     * 生成中的逐 token 实时文本（思考链 / 回答正文 / 工具调用进度），键是一次派发的请求标识。
     *
     * 它**不落库**：进程重启后自然消失，重启后的"进行中 / 可以继续回复"由持久化的任务快照
     * 负责。之所以单独开一条通道，是因为走持久化快照的每一次进度都要写一行状态与审计、且单
     * 任务事件数有上限——那正是实时文本此前只能稀疏回放、正文还被截断的原因。
     *
     * 默认实现返回空流：不关心实时文本的调用方与测试替身不必实现它。
     */
    fun observeLiveText(requestId: String): Flow<ModelLiveText?> = flowOf(null)
}
