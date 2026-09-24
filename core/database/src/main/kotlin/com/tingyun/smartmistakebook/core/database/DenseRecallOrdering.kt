package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity

/**
 * 稠密腿重排的**排序域守卫**（纯函数，JVM 可测）。
 *
 * 为什么单独抽出来：`RoomKnowledgeBaseStore` 里那段是 suspend + Room 的，JVM 测试够不着；
 * 而真正需要被钉住的是这条**不变量**——稠密腿只许改次序：
 *
 * - 重排结果必须是词面召回集的**排列**（同长度、同 id 集合）；不是就整体丢弃，退回词面次序。
 *
 * 这条守卫是"稠密腿不改变返回集合的长度语义 / subject 作用域"这句话在代码里的落点：
 * 模型侧再怎么错（重复、丢项、多给项），都只能让稠密腿**失效**，不能让它改变召回集合。
 */
internal object DenseRecallOrdering {
    fun orderOrLexical(
        lexicalOrder: List<KnowledgeNodeEntity>,
        reranked: List<DenseRecallCandidate>?,
    ): List<KnowledgeNodeEntity> {
        if (reranked == null || reranked.size != lexicalOrder.size) return lexicalOrder
        val expected = lexicalOrder.mapTo(HashSet(lexicalOrder.size), KnowledgeNodeEntity::knowledgeNodeId)
        val actual = reranked.mapTo(HashSet(reranked.size), DenseRecallCandidate::knowledgeNodeId)
        if (actual != expected) return lexicalOrder
        val byId = lexicalOrder.associateBy(KnowledgeNodeEntity::knowledgeNodeId)
        return reranked.map { byId.getValue(it.knowledgeNodeId) }
    }
}
