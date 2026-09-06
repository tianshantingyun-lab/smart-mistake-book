package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 知识点复习范围（spec dual-review-entry §3.2）：extractReviewKnowledgeScope 纯函数
 * 的测试——从今天复习队列的题（各带绑定 knowledgeNodeIds）提取覆盖的知识点集合。
 */
class ReviewKnowledgeScopeTest {

    @Test
    fun todayQueueKnowledgeScopeIsTheUnionOfItsQuestionsBindings() {
        val queue = listOf(
            ReviewScopeQuestion("p1", setOf("kc1", "kc2")),
            ReviewScopeQuestion("p2", setOf("kc2", "kc3")),
        )
        assertEquals(
            setOf("kc1", "kc2", "kc3"),
            extractReviewKnowledgeScope(queue),
        )
    }

    @Test
    fun emptyQueueYieldsEmptyScope() {
        assertEquals(emptySet<String>(), extractReviewKnowledgeScope(emptyList()))
    }

    @Test
    fun questionWithoutBindingsIsSkipped() {
        val queue = listOf(
            ReviewScopeQuestion("p1", setOf("kc1")),
            ReviewScopeQuestion("p2", emptySet()),
        )
        assertEquals(setOf("kc1"), extractReviewKnowledgeScope(queue))
    }

    @Test
    fun duplicateBindingsAcrossQuestionsAreDeduplicated() {
        val queue = listOf(
            ReviewScopeQuestion("p1", setOf("kc1", "kc1")),
            ReviewScopeQuestion("p2", setOf("kc1")),
        )
        assertEquals(setOf("kc1"), extractReviewKnowledgeScope(queue))
    }
}
