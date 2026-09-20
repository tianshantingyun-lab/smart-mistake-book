package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 溢出策略从"无声丢弃"改成"压成要点摘要"的回归面。
 *
 * 此前一旦超出轮次/字符预算，比它更早的对话整段消失：模型既不知道学生问过什么，也不知道自己
 * 已经讲过什么，长会话里重复讲同一件事、或与前面的讲解自相矛盾，而学生看不到任何异常。
 */
class TutorContextComposerTest {

    private val charBudget = TutorRespondInput.MAX_PRIOR_MESSAGE_CHARS
    private val maxStudent = TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS
    private val maxAssistant = TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS

    private fun entry(student: String, assistant: String) = TutorChatHistoryEntry(
        studentMessage = student,
        assistantMarkdown = assistant,
    )

    /** 一篇撑满预算的回复：字符预算由它自己跨过。 */
    private fun heavyEntry(marker: String, student: String = "$marker 这题怎么做？") =
        entry(student, "$marker 的讲解".padEnd(maxAssistant, 'y'))

    /** 把回复撑到单条上限，用来跨过聚合预算；首行内容保持不变。 */
    private fun TutorChatHistoryEntry.paddedToBudget() =
        copy(assistantMarkdown = assistantMarkdown.padEnd(maxAssistant, 'y'))

    @Test
    fun aConversationThatFitsIsCarriedWithoutADigest() {
        val window = TutorContextComposer.compose(
            listOf(
                entry("第一问：函数单调性怎么判断？", "先求导，再看导数的符号。"),
                entry("第二问：极值点怎么求？", "令导数为零，再判断两侧符号。"),
            ),
        )

        assertEquals(2, window.recent.size)
        assertNull("没有被挤出去的轮次就不该有摘要", window.digest)
        assertEquals(0, window.droppedExchanges)
    }

    @Test
    fun droppedOlderExchangesBecomeADigestInsteadOfVanishing() {
        // 三条撑满单条上限的回复 + 一轮小对话：只有最近的一两轮装得下，其余必须进摘要。
        val entries = listOf(
            heavyEntry("甲", "甲：先把定义域写清楚。"),
            heavyEntry("乙", "乙：再判断切线斜率。"),
            heavyEntry("丙", "丙：注意端点要单独验证。"),
            entry("最后一问：那这道题的答案是多少？", "看前面三步连起来。"),
        )

        val window = TutorContextComposer.compose(entries)

        assertTrue("最近一轮必须原样保留", window.recent.isNotEmpty())
        assertTrue(
            "被挤出去的轮次必须记进摘要：dropped=${window.droppedExchanges}",
            window.droppedExchanges >= 1,
        )
        val digest = window.digest
        assertNotNull(digest)
        assertTrue(
            "摘要要点到被挤出去的那一轮在问什么：$digest",
            digest!!.contains("甲"),
        )
    }

    @Test
    fun theDigestCarriesTheQuestionAndTheConclusionButDropsWritingNoise() {
        val window = TutorContextComposer.compose(
            listOf(
                entry(
                    student = "## 甲：这题为什么先配方？",
                    assistant = "**结论**：配方后能直接看出顶点坐标。",
                ).paddedToBudget(),
                entry("乙：那顶点怎么读？", "看括号里的两个数。").paddedToBudget(),
                entry("最后一问：继续", "继续讲。"),
            ),
        )

        val digest = window.digest!!
        assertTrue("摘要必须保留学生的问题首句：$digest", digest.contains("甲：这题为什么先配方？"))
        assertTrue("摘要必须保留助教的结论首行：$digest", digest.contains("结论：配方后能直接看出顶点坐标。"))
        assertTrue("摘要里不该留下标题记号：$digest", !digest.contains("#"))
        assertTrue("摘要里不该留下加粗记号：$digest", !digest.contains("**"))
    }

    @Test
    fun theDigestStaysBoundedEvenWithManyDroppedExchanges() {
        val entries = buildList {
            repeat(30) { index -> add(heavyEntry("轮次$index")) }
            add(entry("最后一问", "答。"))
        }

        val window = TutorContextComposer.compose(entries)

        val digest = window.digest
        assertNotNull(digest)
        assertTrue(
            "摘要必须有上限，否则它自己就会把原样保留的轮次挤掉：${digest!!.length}",
            digest.length <= TutorChatDigest.MAX_DIGEST_CHARS,
        )
        assertTrue("三十轮里绝大多数都该进摘要：${window.droppedExchanges}", window.droppedExchanges >= 20)
        assertTrue(
            "原样保留的轮次仍受轮次上限约束",
            window.recent.size <= TutorRespondInput.MAX_PRIOR_MESSAGES,
        )
    }

    @Test
    fun anEmptyConversationHasNoDigest() {
        // 显式指名轮次源那一支：装配入口现在同时接受消息行（`compose(List<TutorMessage>)`），
        // 空列表不再能自己推断出是哪一支。
        val window = TutorContextComposer.compose(emptyList<TutorChatHistoryEntry>())

        assertTrue(window.recent.isEmpty())
        assertNull(window.digest)
        assertEquals(0, window.droppedExchanges)
    }

    @Test
    fun aWindowCannotClaimASummaryThatNeverHappened() {
        val rejection = runCatching {
            TutorContextWindow(recent = emptyList(), digest = "摘要", droppedExchanges = 0)
        }.exceptionOrNull()

        assertTrue(rejection is IllegalArgumentException)
    }
}
