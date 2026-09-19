package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 知识节点进模型候选菜单前的**适配**：别名多了要截断，不能把整条节点丢掉。
 *
 * 它消灭的失败（实测的形态，不是假想）：`KnowledgeBaseNodeContext` 的别名上限是 8，而真实内容
 * 里最多的节点有 84 条别名。改前不截断 → `init` 抛 → `getOrNull()` 吞 → 调用处 `mapNotNull`
 * 丢 → **节点从候选菜单里静默消失**，538 个节点受影响，无日志无计数；而被藏的恰恰是材料
 * 最多的那些节点（`基因工程` 86 条材料），也就是最有资格当候选的那些。
 *
 * 另一半同样要钉住：**截断只该发生在别名上**。因别名之外的原因（如主名超长）不合格的节点
 * 仍然会被丢弃——那条路径不能因为这次修改而消失，否则就成了"什么都放行"。
 */
class KnowledgeBaseNodeContextAdaptationTest {

    @Test
    fun `a node with more aliases than the contract allows is truncated, not dropped`() {
        val node = seed(aliases = (1..11).map { "别名$it" }.toSet())

        val context = node.toKnowledgeBaseContext(
            subject = SubjectKind.MATH,
            parentCanonicalName = "函数",
            prerequisiteKnowledgeNodeIds = emptyList(),
        )

        assertNotNull("别名多不该让整条节点消失——那是内容缺失，不是安全", context)
        assertEquals(
            "截断必须是确定性的：取排序后的前 N 条",
            node.aliases.sorted().take(KnowledgeBaseNodeContext.MAX_ALIASES),
            context!!.aliases,
        )
        assertEquals(8, context.aliases.size)
        assertEquals(
            "主名是独立字段，截断别名不影响节点被指认",
            "判断函数单调性",
            context.canonicalName,
        )
    }

    @Test
    fun `a node with exactly the limit keeps every alias`() {
        val node = seed(aliases = (1..8).map { "别名$it" }.toSet())

        val context = node.toKnowledgeBaseContext(
            subject = SubjectKind.MATH,
            parentCanonicalName = "函数",
            prerequisiteKnowledgeNodeIds = emptyList(),
        )

        assertEquals(node.aliases.sorted(), context!!.aliases)
    }

    @Test
    fun `a node that is malformed for another reason is still dropped`() {
        // 主名超过 MAX_LABEL_CHARS(96)：这不是别名问题，该丢还是要丢——
        // 否则这次修改就变成了"什么都放行"。
        val node = seed(canonicalName = "超长的名字".repeat(20))

        assertNull(
            node.toKnowledgeBaseContext(
                subject = SubjectKind.MATH,
                parentCanonicalName = "函数",
                prerequisiteKnowledgeNodeIds = emptyList(),
            ),
        )
    }

    private fun seed(
        aliases: Set<String> = emptySet(),
        canonicalName: String = "判断函数单调性",
    ) = KnowledgeNodeSeedRecord(
        knowledgeNodeId = "kb:test:math:atomic:monotonicity",
        stableCode = "test:math:atomic:monotonicity",
        subject = "MATH",
        displayName = canonicalName,
        parentKnowledgeNodeId = "kb:test:math:topic:function",
        taxonomyVersion = "test",
        createdAtEpochMillis = 1_000,
        canonicalName = canonicalName,
        nodeKind = KnowledgeNodeKind.REASONING.name,
        granularity = KnowledgeNodeGranularity.ATOMIC.name,
        aliases = aliases,
        verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
    )
}
