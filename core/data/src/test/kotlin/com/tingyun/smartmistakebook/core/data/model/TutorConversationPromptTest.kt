package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话上下文在提示词里的**布局**：历史按时间顺序、只追加地排在前面，当前消息排在最后，
 * 每轮都会变的内容不许插在历史前面。
 *
 * 这条性质的收益是具体的：上游若按前缀缓存计费（DeepSeek 系的常见做法），前缀稳定才命中；
 * 此前 `studentMessage` 排在 `priorMessages` 之前、且"本次是否带图"的说明夹在历史之前，
 * 每一轮都会让整个前缀作废。同时历史从 JSON 数组改成可读转录，模型不必先解析 JSON。
 */
class TutorConversationPromptTest {

    private val turnMarker = "本次消息（只对这一条作答）："

    /** 历史块自身的标记；规则文本里也会提到"对话历史"，用整串标记才能定位到块。 */
    private val historyMarker = "对话历史（按时间顺序"

    private fun historyEntry(student: String, assistant: String) =
        TutorChatHistoryEntry(studentMessage = student, assistantMarkdown = assistant)

    private fun assetRef(assetId: String, pageIndex: Int) = CaptureSourceAssetRef(
        assetId = assetId,
        sha256 = "a".repeat(64),
        width = 1_200,
        height = 900,
        pageIndex = pageIndex,
    )

    private fun lobby(
        studentMessage: String = "解一下这个题吧",
        priorMessages: List<TutorChatHistoryEntry> = emptyList(),
        priorDigest: String? = null,
        sourceImages: List<CaptureSourceAssetRef> = emptyList(),
        contextImages: List<CaptureSourceAssetRef> = emptyList(),
    ) = TutorLobbyInput(
        conversationId = "conv-1",
        messageOrdinal = 1,
        studentMessage = studentMessage,
        priorMessages = priorMessages,
        priorDigest = priorDigest,
        sourceImageAssetRefs = sourceImages,
        contextImageAssetRefs = contextImages,
    )

    private fun respond(
        priorMessages: List<TutorChatHistoryEntry> = emptyList(),
        priorDigest: String? = null,
    ) = TutorRespondInput(
        sessionId = "session-1",
        draftRevisionNumber = 1,
        subject = "数学",
        questionDocument = QuestionDocument(
            id = "question-1",
            blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
        ),
        relevantLearningEvidence = emptyList(),
        projectionIsCurrent = true,
        responseOrdinal = 2,
        cycleOrdinal = 1,
        turnOrdinal = 2,
        studentMessage = "这一步怎么来的？",
        priorMessages = priorMessages,
        priorDigest = priorDigest,
    )

    @Test
    fun consecutiveTurnsShareAStablePrefixSoUpstreamCachingCanHit() {
        val history = listOf(historyEntry("你好", "你好，你想做什么？"))
        val first = lobby(studentMessage = "解一下这个题吧", priorMessages = history)
        val second = lobby(
            studentMessage = "第三题呢？",
            priorMessages = history + historyEntry("解一下这个题吧", "先看第一步：确定定义域。"),
        )

        val firstPrompt = OpenAiModelTaskAdapters.prompt(first)
        val secondPrompt = OpenAiModelTaskAdapters.prompt(second)
        val stablePrefix = firstPrompt.substringBefore(turnMarker)

        assertTrue(
            "下一轮必须建立在上一轮的稳定前缀之上；前缀一变，上游的缓存整段作废",
            secondPrompt.startsWith(stablePrefix),
        )
        assertTrue("当前消息必须在最后", secondPrompt.endsWith("学生：第三题呢？\n"))
    }

    @Test
    fun theLobbyHistoryIsAReadableTranscriptRatherThanAJsonArray() {
        val prompt = OpenAiModelTaskAdapters.prompt(
            lobby(
                priorMessages = listOf(historyEntry("你好", "你好，你想做什么？")),
            ),
        )

        assertTrue(prompt.contains("学生：你好"))
        assertTrue(prompt.contains("助教：你好，你想做什么？"))
        assertFalse("历史不该再要求模型先解析 JSON", prompt.contains("\"priorMessages\""))
    }

    @Test
    fun anOverBudgetHistoryArrivesAsAnExplicitlyMarkedSummary() {
        val prompt = OpenAiModelTaskAdapters.prompt(
            lobby(
                priorDigest = "第1轮 学生：函数单调性怎么判断？\n　　　 助教：先求导。",
                priorMessages = listOf(historyEntry("第二问", "令导数为零。")),
            ),
        )

        assertTrue(prompt.contains("更早对话的摘要"))
        assertTrue("摘要必须自报「不是原话」，否则模型会把它当逐字引用", prompt.contains("不是原话"))
        assertTrue(prompt.contains("第1轮 学生：函数单调性怎么判断？"))
        assertTrue(
            "摘要排在原样轮次之前",
            prompt.indexOf("更早对话的摘要") < prompt.indexOf("学生：第二问"),
        )
    }

    @Test
    fun theLobbyDistinguishesEarlierImagesFromTheOnesJustAttached() {
        val justAttached = OpenAiModelTaskAdapters.prompt(
            lobby(sourceImages = listOf(assetRef("asset-a", 0))),
        )
        val carriedOver = OpenAiModelTaskAdapters.prompt(
            lobby(contextImages = listOf(assetRef("asset-old", 0))),
        )
        val both = OpenAiModelTaskAdapters.prompt(
            lobby(
                sourceImages = listOf(assetRef("asset-a", 0), assetRef("asset-b", 1)),
                contextImages = listOf(assetRef("asset-old", 0)),
            ),
        )

        assertTrue(justAttached.contains("本次消息附有学生选择的1张图片"))
        assertTrue(carriedOver.contains("本次消息没有新图"))
        assertTrue("必须说明这些图不是这次新发的，否则模型会重复回答", carriedOver.contains("不是这次新发"))
        assertTrue(both.contains("前2张是这次新选的，后1张是上文发过的"))
    }

    @Test
    fun theVariablePerTurnNoteNeverBreaksTheStablePrefix() {
        val prompt = OpenAiModelTaskAdapters.prompt(
            lobby(
                priorMessages = listOf(historyEntry("你好", "你好。")),
                sourceImages = listOf(assetRef("asset-a", 0)),
            ),
        )

        val rules = prompt.indexOf("规则：")
        val history = prompt.indexOf("对话历史")
        val note = prompt.indexOf("本次消息附有学生选择的1张图片")
        val current = prompt.indexOf(turnMarker)

        assertTrue("规则是固定前缀，必须排在最前", rules in 0 until history)
        assertTrue("历史紧随固定前缀之后", history < note)
        assertTrue("每轮都会变的带图说明必须排在历史之后", note < current)
    }

    @Test
    fun theRespondHistoryAlsoSitsBetweenTheRulesAndTheVariableContext() {
        val prompt = OpenAiModelTaskAdapters.prompt(
            respond(priorMessages = listOf(historyEntry("这一步怎么来的？", "因为两边同时除以二。")))
        )

        val rules = prompt.indexOf("规则：")
        val history = prompt.indexOf(historyMarker)
        val variable = prompt.indexOf("科目：")

        assertTrue("规则是固定前缀，必须排在历史块之前", rules in 0 until history)
        assertTrue("可变字段（科目/证据/本轮上下文）必须排在历史块之后", history < variable)
        assertTrue(prompt.contains("学生：这一步怎么来的？"))
        assertTrue(prompt.contains("助教：因为两边同时除以二。"))
    }

    @Test
    fun aRespondDigestIsMarkedAsASummaryToo() {
        val prompt = OpenAiModelTaskAdapters.prompt(
            respond(priorDigest = "第1轮 学生：定义域怎么写？\n　　　 助教：先看根号内的非负。")
        )

        assertTrue(prompt.contains("更早对话的摘要"))
        assertTrue(prompt.contains("不是原话"))
        assertTrue(prompt.contains("第1轮 学生：定义域怎么写？"))
    }
}
