package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorRespondInput

/**
 * 长对话的上下文装配：能装下的轮次原样带上，装不下的轮次压成**确定性摘要**留下要点。
 *
 * 消灭的失败：此前 [TutorHistoryBudget] 一碰到超额的那一轮就 `break`，比它更早的对话
 * **整段无声消失**——学生已经问过什么、模型已经讲过什么、哪些卡点已经解释过，全部从上下文里
 * 蒸发，于是长会话里模型会重复讲同一件事、或跟前面的讲解自相矛盾，而学生看不出任何异常。
 *
 * 摘要必须确定、可测、不需要额外模型调用：这里只用学生消息的首句与助教回复的首行/结论行，
 * 并明确标注"这是摘要、不是原话"，让模型知道哪些内容可以放心引用、哪些需要重新确认。
 * （对照实现：用户自己的项目 鉴心 的 `extractKeyFacts`/`extractFactLedger` 同属这一类
 * 确定性摘要；模型生成摘要会多一次调用与一次新的出网，本轮刻意不做。）
 */
object TutorContextComposer {

    fun compose(entries: List<TutorChatHistoryEntry>): TutorContextWindow {
        val kept = TutorHistoryBudget.bounded(entries)
        val droppedCount = entries.size - kept.size
        if (droppedCount <= 0) return TutorContextWindow(recent = kept, digest = null, droppedExchanges = 0)
        return TutorContextWindow(
            recent = kept,
            digest = TutorChatDigest.of(entries.take(droppedCount)),
            droppedExchanges = droppedCount,
        )
    }
}

/** 装配结果：原样保留的最近轮次 + 更早轮次的摘要（没有丢弃时摘要为 null）。 */
data class TutorContextWindow(
    val recent: List<TutorChatHistoryEntry>,
    val digest: String?,
    val droppedExchanges: Int,
) {
    init {
        require(droppedExchanges >= 0) { "Dropped exchange count must not be negative" }
        require(digest == null || droppedExchanges > 0) {
            "A digest without dropped exchanges would claim content was summarized that is still verbatim"
        }
    }
}

/**
 * 把被挤出去的轮次压成要点文本（纯函数，便于单测）。
 *
 * 每轮只保留两处最能承重的信息：学生那一句在问什么、助教那一轮给出的结论是什么。刻意不
 * 保留思考轨迹与中间推导——它们在 UI 上折叠展示、按约定从不回喂模型。
 */
internal object TutorChatDigest {

    /** 摘要总量上限：它是"要点提示"，不该反过来挤占原样保留的轮次。 */
    const val MAX_DIGEST_CHARS = 1_500

    private const val MAX_STUDENT_CHARS = 80
    private const val MAX_ASSISTANT_CHARS = 160

    fun of(dropped: List<TutorChatHistoryEntry>): String? {
        if (dropped.isEmpty()) return null
        val body = buildString {
            dropped.forEachIndexed { index, entry ->
                if (index > 0) append('\n')
                val student = firstSentenceOf(entry.studentMessage)
                append("第${index + 1}轮 学生：").append(student)
                val assistant = firstLineOf(entry.assistantMarkdown)
                if (assistant.isNotBlank()) {
                    append('\n').append("　　　 助教：").append(assistant)
                }
            }
        }
        return body.take(MAX_DIGEST_CHARS)
    }

    private fun firstSentenceOf(markdown: String): String =
        markdown.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .let(::stripMarkdownNoise)
            .take(MAX_STUDENT_CHARS)

    private fun firstLineOf(markdown: String): String =
        markdown.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .let(::stripMarkdownNoise)
            .take(MAX_ASSISTANT_CHARS)

    /** 去掉标题/列表/加粗等写作记号：摘要里保留这些符号只会干扰模型读要点。 */
    private fun stripMarkdownNoise(line: String): String =
        line.trimStart('#', '-', '*', '>', ' ')
            .replace("**", "")
            .replace("`", "")
            .trim()
}
