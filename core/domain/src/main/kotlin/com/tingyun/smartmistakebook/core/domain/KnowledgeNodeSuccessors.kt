package com.tingyun.smartmistakebook.core.domain

/**
 * 合并重定向：把**已退役**的知识点解析到它当前的节点上。
 *
 * ## 它消灭的失败
 *
 * 内容侧合并两个重复知识点时（`X` 与 `X-x1` 这类副本），包里会让 `X` 退役、把
 * `superseded_by` 指向存活节点 `Y`；材料、别名、前置边都由生成器重指到 `Y`。
 * 但**学生的历史证据仍挂在 `X` 上**——那些行不可改写（改写历史会让重放不再可复现）。
 * 于是学生答错过的 `X` 上的证据无处可去，`Y` 的掌握度凭空少一块，排程把它当没学过。
 *
 * 投影时按这张映射把证据算到 `Y`，就实现了"合并并入"，而**历史行一字未动**。
 *
 * ## 为什么是内容侧映射，而不是一条账本事件
 *
 * 更直觉的做法是往账本里加一个 `KC_MERGED` 事件、让投影在流里消费它。这会动到事件溯源
 * 核心的每一处穷尽 `when`、事件指纹、账本表与排空器——而审计记录里那一层正是最脆的
 * （CAS 冲突、重放上界、投影响应版本）。
 *
 * 而映射本来就有权威出处：它随**内容包**版本化（`update-manifest.json` →
 * `knowledge_node.superseded_by`）。于是"同一份内容 + 同一份账本 ⇒ 同一结果"这条
 * 重放一致性仍然成立——只是把内容也视为重放的输入之一，而它本来就是。
 *
 * 现在全库 `superseded_by` 都是 NULL（尚无节点被退役过），所以这次引入**对既有数据是空操作**，
 * 不需要投影版本升级，也不需要重放。
 */
class KnowledgeNodeSuccessors(private val successorByNodeId: Map<String, String>) {

    /**
     * 沿取代链跟到落地节点。链是真实存在的：`无氧呼吸-x2 → -x1 → 无氧呼吸`。
     *
     * 遇到环或自指时**停在原地返回**，不抛也不无限跟：映射来自内容包，生成器侧有环检测，
     * 但坏内容不该让投影死循环。
     */
    fun resolve(knowledgeNodeId: String): String {
        val seen = mutableSetOf<String>()
        var current = knowledgeNodeId
        while (seen.add(current)) {
            val next = successorByNodeId[current] ?: return current
            if (next == current) return current
            current = next
        }
        return current
    }

    companion object {
        /** 没有任何退役节点时的映射——既有行为逐位不变。 */
        val EMPTY = KnowledgeNodeSuccessors(emptyMap())
    }
}
