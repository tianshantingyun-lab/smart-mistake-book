package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeBaseImportContract
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置包的**每一个**知识节点都必须过数据库导入契约的逐条判据。
 *
 * 它消灭的失败：契约里曾有一条凭空的 `require(aliases.size <= 32)`（无 KDoc、无具名常量、
 * 不从任何预算推导；真正的预算在特征提取器的 16 个片段，与别名条数无关），而内容里最多的
 * 节点有 84 条别名。于是 25 个知识点在调和时被逐条跳过，**进不了任何用户的库**，只在进度表
 * 里留一行。契约夹具测试用的是自造的小节点，照不到这个形态；只有"内置包 × 真契约"能照到。
 *
 * 判据用的是调和器决定的那个函数——同一个 [KnowledgeBaseImportContract.problemWith]、
 * 同一份 `byId`，不是另写一套规则：两套规则迟早漂移，而漂移的方向是"校验说没问题、
 * 导入却跳过"，正是这条测试要防的。
 */
class BundledKnowledgeNodesContractTest {

    @Test
    fun everyBundledKnowledgeNodeIsAcceptedByTheImportContract() {
        val packs = BundledKnowledgePackResources.load()
        val problems = mutableListOf<String>()
        var checked = 0
        for (pack in packs) {
            val nodesById = pack.nodes.associateBy { it.knowledgeNodeId }
            for (node in pack.nodes) {
                checked += 1
                KnowledgeBaseImportContract.problemWith(node, nodesById)?.let { problem ->
                    problems += "${node.knowledgeNodeId}: $problem"
                }
            }
        }
        assertTrue("内置包应有节点可校验", checked > 0)
        assertTrue("内置节点被导入契约拒绝：${problems.take(10)}", problems.isEmpty())
    }
}
