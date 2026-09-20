package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.ModelLiveKind
import com.tingyun.smartmistakebook.core.model.ModelLiveText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在途一轮的实时文本（大厅与会话共用的那一条流）。
 *
 * 消灭的失败：这条通道上的三条文本互相覆盖——网关按"当前这一条"发，后到的通道会把前一条
 * 从 map 里顶掉（`RoomModelTaskRepository.publishLiveText`）。折叠时若不保留上一条，学生就会
 * 看到思考链在回答开始写的那一刻整段消失；这条测试把"保留"钉住。
 */
class TutorLiveTurnTest {

    @Test
    fun laterChannelsReplaceTheirOwnSlotWithoutErasingTheOthers() {
        val folded = TutorLiveTurn.EMPTY
            .withLive(ModelLiveText(ModelLiveKind.THINKING, "先看定义域"))
            .withLive(ModelLiveText(ModelLiveKind.TOOL, "正在查错题本"))
            .withLive(ModelLiveText(ModelLiveKind.ANSWER, "第一步是求导"))

        assertEquals("先看定义域", folded.thinking)
        assertEquals("正在查错题本", folded.toolNote)
        assertEquals("第一步是求导", folded.answer)
        // 回答一开始写，思考卡收起（由渲染侧用 hasAnswer 决定展开与否），但思考链还在。
        assertTrue(folded.hasAnswer)
        assertEquals("先看定义域\n\n正在查错题本", folded.thinkingText)
    }

    @Test
    fun theSameChannelOverwritesItself() {
        val folded = TutorLiveTurn.EMPTY
            .withLive(ModelLiveText(ModelLiveKind.ANSWER, "第一"))
            .withLive(ModelLiveText(ModelLiveKind.ANSWER, "第一第二"))

        assertEquals("第一第二", folded.answer)
    }

    @Test
    fun aNullLiveTextLeavesTheTurnUntouched() {
        val turn = TutorLiveTurn(thinking = "在想", answer = "在写")

        assertEquals(turn, turn.withLive(null))
    }

    @Test
    fun blankChannelsDoNotBecomeAThinkingCard() {
        val folded = TutorLiveTurn.EMPTY
            .withLive(ModelLiveText(ModelLiveKind.THINKING, "   "))
            .withLive(ModelLiveText(ModelLiveKind.TOOL, ""))

        assertNull(folded.thinkingText)
        assertEquals("", folded.toolNote)
        assertFalse(folded.hasAnswer)
    }
}
