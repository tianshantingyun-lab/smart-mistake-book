package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.LobbyMessageImage
import com.tingyun.smartmistakebook.core.domain.LobbyMessageImageIntake
import com.tingyun.smartmistakebook.core.domain.TutorMessage
import com.tingyun.smartmistakebook.core.domain.TutorMessageRole
import com.tingyun.smartmistakebook.core.domain.TutorMessageStatus
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 追问要带上文图片——这是「模型看不懂我的图片」的修复面。
 *
 * 实测里学生先发一张题图问"解一下这个题吧"，再追问"第三题"时上下文里只剩文字，模型自己在
 * 回答里写「这道题的题面细节我这边看不到」。图片属于那条历史消息，但必须随本次发送一起回去。
 */
class TutorLobbyContextImagesTest {

    private val conversationId = "tutor-conv:test"

    private class FakeIntake(
        private val assets: Map<String, LobbyMessageImage>,
    ) : LobbyMessageImageIntake {
        override suspend fun registerImage(
            localUri: String,
            occurredAtEpochMillis: Long,
        ): LobbyMessageImage = error("注册不该在读取路径上被调用")

        override suspend fun resolveImageUri(assetId: String): String? = "file:///$assetId"

        override suspend fun describeImage(assetId: String): LobbyMessageImage? = assets[assetId]
    }

    private fun image(assetId: String) = LobbyMessageImage(
        assetId = assetId,
        sha256 = "c".repeat(64),
        byteSize = 4_096,
        width = 1_080,
        height = 1_440,
    )

    private fun student(
        ordinal: Int,
        body: String = "第${(ordinal + 1) / 2}问",
        imageAssetIds: List<String> = emptyList(),
    ) = TutorMessage(
        messageId = "m-$ordinal",
        conversationId = conversationId,
        ordinal = ordinal,
        role = TutorMessageRole.STUDENT,
        bodyMarkdown = body,
        status = TutorMessageStatus.PERSISTED,
        logicalOperationId = "op-$ordinal",
        replyToMessageId = null,
        createdAtEpochMillis = ordinal.toLong(),
        completedAtEpochMillis = null,
        errorCode = null,
        sourceImageAssetIds = imageAssetIds,
    )

    private fun assistant(ordinal: Int, body: String = "先看第一步。") = TutorMessage(
        messageId = "m-$ordinal",
        conversationId = conversationId,
        ordinal = ordinal,
        role = TutorMessageRole.ASSISTANT,
        bodyMarkdown = body,
        status = TutorMessageStatus.SUCCEEDED,
        logicalOperationId = "op-${ordinal - 1}",
        replyToMessageId = "m-${ordinal - 1}",
        createdAtEpochMillis = ordinal.toLong(),
        completedAtEpochMillis = ordinal.toLong(),
        errorCode = null,
    )

    @Test
    fun theMostRecentImageBearingMessageIsCarriedIntoTheFollowUp() = runBlocking {
        val intake = FakeIntake(mapOf("asset-a" to image("asset-a")))
        val messages = listOf(
            student(ordinal = 1, imageAssetIds = listOf("asset-a")),
            assistant(ordinal = 2),
            student(ordinal = 3, body = "第三题呢？"),
        )

        val carried = messages.toContextImages(intake, beforeOrdinal = 3)

        assertEquals(listOf("asset-a"), carried.map { it.assetId })
    }

    @Test
    fun imagesFromTheVeryMessageBeingResentAreNotCarriedTwice() = runBlocking {
        // 重发一条带图消息时，它的图属于"本条消息"，不能同时又算作上文图片：
        // 输入契约禁止同一张图在一次发送里出现两次，算重了整条消息会被判非法。
        val intake = FakeIntake(mapOf("asset-old" to image("asset-old"), "asset-new" to image("asset-new")))
        val messages = listOf(
            student(ordinal = 1, imageAssetIds = listOf("asset-old")),
            assistant(ordinal = 2),
            student(ordinal = 3, imageAssetIds = listOf("asset-new")),
        )

        val carried = messages.toContextImages(intake, beforeOrdinal = 3)

        assertEquals(listOf("asset-old"), carried.map { it.assetId })
    }

    @Test
    fun anUnreadableAssetIsSkippedInsteadOfFailingTheSend() = runBlocking {
        val intake = FakeIntake(emptyMap())
        val messages = listOf(
            student(ordinal = 1, imageAssetIds = listOf("asset-gone")),
            assistant(ordinal = 2),
            student(ordinal = 3, body = "第三题呢？"),
        )

        val carried = messages.toContextImages(intake, beforeOrdinal = 3)

        assertTrue("读不回元数据的图按「这张不再出网」处理，而不是把整条消息顶成发送失败", carried.isEmpty())
    }

    @Test
    fun aConversationWithoutImagesCarriesNone() = runBlocking {
        val intake = FakeIntake(mapOf("asset-a" to image("asset-a")))
        val messages = listOf(student(ordinal = 1), assistant(ordinal = 2), student(ordinal = 3))

        assertTrue(messages.toContextImages(intake, beforeOrdinal = 3).isEmpty())
    }

    @Test
    fun noIntakeMeansNoImagesRatherThanACrash() = runBlocking {
        val messages = listOf(
            student(ordinal = 1, imageAssetIds = listOf("asset-a")),
            assistant(ordinal = 2),
            student(ordinal = 3),
        )

        assertTrue(messages.toContextImages(intake = null, beforeOrdinal = 3).isEmpty())
    }

    @Test
    fun theExchangeListUsedForSummarizingIsNotTrimmed() {
        // 装配器要拿到未裁剪的轮次才能给被挤出去的轮次做摘要；先裁剪再摘要等于让它们彻底消失。
        val maxStudent = TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS
        val messages = buildList {
            repeat(TutorRespondInput.MAX_PRIOR_MESSAGES + 2) { index ->
                val ordinal = index * 2 + 1
                add(student(ordinal = ordinal, body = "x".padEnd(maxStudent, 'x')))
                add(assistant(ordinal = ordinal + 1))
            }
        }

        assertEquals(TutorRespondInput.MAX_PRIOR_MESSAGES + 2, messages.toLobbyExchanges().size)
        assertTrue(
            "裁剪仍然只发生在 bounded 这一层",
            messages.toLobbyHistory().size < messages.toLobbyExchanges().size,
        )
    }
}
