package com.tingyun.smartmistakebook.core.database

/**
 * 词面召回的一条候选（节点 id + 该节点的词面分 = `COUNT(DISTINCT feature.search_feature)`）。
 *
 * 分数是 spec §2.4 融合里"词面腿"的原始分，**不是**名次：归一化用的是单查询候选域内的
 * min-max，名次信息不足以还原它（长尾分布下 min-max 与名次映射不是同一个东西）。
 */
data class DenseRecallCandidate(
    val knowledgeNodeId: String,
    val lexicalScore: Int,
)

/**
 * 稠密腿的重排入口（端口，声明在 store 所在模块；实现在 `core:data` 的 `knowledge/dense`）。
 *
 * 契约（存储层依赖的只有这三条）：
 * 1. **只改次序，不改成员**：返回的列表必须是入参的排列（同长度、同 id 集合），否则调用方
 *    丢弃结果退回词面次序——所以稠密腿**不可能**改变召回集合的长度语义或 subject 作用域；
 * 2. **返回 null 表示不可用**：模型/资产缺失、加载失败、推理抛错都归到这里（调用方已有
 *    纯词面结果，不允许因为一条可选的腿而让主路径失败）；
 * 3. 抛出的异常同样按不可用处理（`CancellationException` 除外，它必须继续上抛）。
 */
fun interface DenseRecallReranker {
    suspend fun order(
        subject: String,
        queryText: String,
        candidates: List<DenseRecallCandidate>,
    ): List<DenseRecallCandidate>?
}
