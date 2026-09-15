package com.tingyun.smartmistakebook.feature.review

import com.tingyun.smartmistakebook.core.domain.MasteryWriteGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 掌握度回写被门控拒收时的学生文案：内部枚举名必须被翻译成人话，
 * 且映射要覆盖 MasteryWriteGate 的全部拒收原因（新增枚举时这里先红）。
 */
class KnowledgeQuizRejectedReasonTextTest {

    @Test
    fun everyRejectReasonMapsToAReadableSentence() {
        MasteryWriteGate.RejectReason.entries.forEach { reason ->
            val text = knowledgeQuizRejectedReasonText(reason.name)

            assertTrue("为空：${reason.name}", text.isNotBlank())
            assertFalse(
                "仍是枚举名：${reason.name} -> $text",
                text.contains(reason.name),
            )
            assertFalse(
                "含下划线枚举风格：${reason.name} -> $text",
                text.contains("_"),
            )
        }
    }

    @Test
    fun unknownReasonFallsBackToAGenericSentence() {
        val text = knowledgeQuizRejectedReasonText("SOME_FUTURE_REASON")

        assertTrue(text.isNotBlank())
        assertFalse(text.contains("SOME_FUTURE_REASON"))
    }
}
