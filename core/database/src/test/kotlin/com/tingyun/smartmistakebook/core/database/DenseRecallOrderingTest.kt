package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 稠密腿"只改次序"的守卫（Stage-3）。
 *
 * 这是"稠密腿不改变召回集合"这句话在代码里的唯一落点：模型/融合再怎么错，也只能让重排
 * **失效**，不能让它改变成员、长度或顺序之外的任何东西。用例覆盖：正常重排、null（不可用）、
 * 长度不符、成员不符（多项/少项/换项）、重复项、空集。
 */
class DenseRecallOrderingTest {

    private val a = node("a")
    private val b = node("b")
    private val c = node("c")
    private val lexicalOrder = listOf(a, b, c)

    @Test
    fun `a permutation is applied as the new order`() {
        val result = DenseRecallOrdering.orderOrLexical(
            lexicalOrder,
            listOf(candidate(c, 1), candidate(a, 9), candidate(b, 4)),
        )
        assertEquals(ids(c, a, b), ids(result))
    }

    @Test
    fun `unavailable dense leg keeps the lexical order`() {
        assertEquals(ids(a, b, c), ids(DenseRecallOrdering.orderOrLexical(lexicalOrder, null)))
    }

    @Test
    fun `a shorter or longer result is discarded`() {
        assertEquals(
            ids(a, b, c),
            ids(DenseRecallOrdering.orderOrLexical(lexicalOrder, listOf(candidate(a, 1), candidate(b, 2)))),
        )
        assertEquals(
            ids(a, b, c),
            ids(
                DenseRecallOrdering.orderOrLexical(
                    lexicalOrder,
                    listOf(candidate(a, 1), candidate(b, 2), candidate(c, 3), candidate(node("d"), 4)),
                ),
            ),
        )
    }

    @Test
    fun `a swapped member is discarded`() {
        assertEquals(
            ids(a, b, c),
            ids(
                DenseRecallOrdering.orderOrLexical(
                    lexicalOrder,
                    listOf(candidate(a, 1), candidate(b, 2), candidate(node("d"), 3)),
                ),
            ),
        )
    }

    @Test
    fun `a duplicated member is discarded`() {
        assertEquals(
            ids(a, b, c),
            ids(DenseRecallOrdering.orderOrLexical(lexicalOrder, listOf(candidate(a, 1), candidate(a, 2), candidate(c, 3)))),
        )
    }

    @Test
    fun `an empty lexical order is never invented into a result`() {
        assertEquals(emptyList<String>(), ids(DenseRecallOrdering.orderOrLexical(emptyList(), emptyList())))
        assertEquals(
            emptyList<String>(),
            ids(DenseRecallOrdering.orderOrLexical(emptyList(), listOf(candidate(a, 1)))),
        )
    }

    private fun candidate(node: KnowledgeNodeEntity, lexicalScore: Int) =
        DenseRecallCandidate(node.knowledgeNodeId, lexicalScore)

    private fun ids(vararg nodes: KnowledgeNodeEntity) = nodes.map { it.knowledgeNodeId }

    private fun ids(nodes: List<KnowledgeNodeEntity>) = nodes.map { it.knowledgeNodeId }

    private fun node(id: String) = KnowledgeNodeEntity(
        knowledgeNodeId = "kb:probe:physics:atomic:$id",
        stableCode = "probe:physics:atomic:$id",
        subject = "PHYSICS",
        displayName = id,
        canonicalName = id,
        nodeKind = "CONCEPT",
        granularity = "ATOMIC",
        aliasesText = "",
        boundaryMarkdown = null,
        verificationStatus = "SOURCE_GROUNDED",
        parentKnowledgeNodeId = "kb:probe:physics:topic:probe",
        taxonomyVersion = "probe",
        createdAtEpochMillis = 0L,
        status = "ACTIVE",
    )
}
