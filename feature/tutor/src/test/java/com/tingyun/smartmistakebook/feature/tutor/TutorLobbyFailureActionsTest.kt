package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorMessage
import com.tingyun.smartmistakebook.core.domain.TutorMessageRole
import com.tingyun.smartmistakebook.core.domain.TutorMessageStatus
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败卡此前只有一句通用文案：「这次回复没有准备好，你的消息已经保留。」
 *
 * 学生既不知道原因，也没有任何出口——唯一真正发生过的那次失败
 * （`EGRESS_AUTHORIZATION_INVALID`，0.2 秒内在本地被拒）恰好落在原因表的兜底分支里，
 * 于是界面上看不到任何可行动的说明；学生只能把话重新打一遍（实测记录里就是这么做的：
 * 失败之后手动补发了"3"和"第三题"两条新消息）。
 *
 * 这些测试钉住两件事：每个失败码都必须有自己的说法，以及"重新发送"只在重发真有可能
 * 成功时出现。
 */
class TutorLobbyFailureActionsTest {

    private val conversationId = "tutor-conv:test"

    private fun studentMessage(
        messageId: String = "m-1",
        ordinal: Int = 1,
        body: String = "第三题看不懂",
    ) = TutorMessage(
        messageId = messageId,
        conversationId = conversationId,
        ordinal = ordinal,
        role = TutorMessageRole.STUDENT,
        bodyMarkdown = body,
        status = TutorMessageStatus.PERSISTED,
        logicalOperationId = "op-1",
        replyToMessageId = null,
        createdAtEpochMillis = 1,
        completedAtEpochMillis = null,
        errorCode = null,
        sourceImageAssetIds = listOf("asset-1"),
    )

    private fun failedReply(
        messageId: String = "m-2",
        ordinal: Int = 2,
        errorCode: String?,
        replyToMessageId: String? = "m-1",
    ) = TutorMessage(
        messageId = messageId,
        conversationId = conversationId,
        ordinal = ordinal,
        role = TutorMessageRole.ASSISTANT,
        bodyMarkdown = "这条消息已经保留，暂时没有收到讲解。",
        status = TutorMessageStatus.FAILED,
        logicalOperationId = "op-1",
        replyToMessageId = replyToMessageId,
        createdAtEpochMillis = 2,
        completedAtEpochMillis = 2,
        errorCode = errorCode,
    )

    @Test
    fun everyFailureCodeExceptUnknownHasSomethingActionableToSay() {
        val silent = ModelFailureCode.entries.filter { lobbyFailureReasonText(it) == null }

        // 只允许 UNKNOWN 沉默：它是"我们也不知道为什么"的诚实表达，配的是重发按钮。
        assertEquals(listOf(ModelFailureCode.UNKNOWN), silent)
        ModelFailureCode.entries.forEach { code ->
            if (code == ModelFailureCode.UNKNOWN) return@forEach
            assertTrue(
                "${code.name} 必须有可行动的说法",
                !lobbyFailureReasonText(code).isNullOrBlank(),
            )
        }
    }

    @Test
    fun theFailureThatActuallyHappenedExplainsItself() {
        val reason = lobbyFailureReasonText(ModelFailureCode.EGRESS_AUTHORIZATION_INVALID)

        assertTrue("原因必须点出授权失效", reason!!.contains("授权"))
        assertTrue("并且要告诉学生怎么走出去", reason.contains("重新发送"))
    }

    @Test
    fun anAbsentCodeHasNoReasonLine() {
        assertNull(lobbyFailureReasonText(null))
    }

    @Test
    fun theLastFailedReplyOffersAResendOfTheMessageItAnswered() {
        val student = studentMessage()
        val failed = failedReply(errorCode = ModelFailureCode.EGRESS_AUTHORIZATION_INVALID.name)

        val target = failed.resendTargetOrNull(listOf(student, failed))

        assertEquals(student, target)
    }

    @Test
    fun anOlderFailureIsNotResendable() {
        val student = studentMessage()
        val failed = failedReply(errorCode = ModelFailureCode.NETWORK_UNAVAILABLE.name)
        val newerStudent = studentMessage(messageId = "m-3", ordinal = 3, body = "还有第二问")

        val target = failed.resendTargetOrNull(listOf(student, failed, newerStudent))

        assertNull("更早的失败重发会让对话顺序错乱", target)
    }

    @Test
    fun failuresThatWouldRepeatThemselvesAreNotResendable() {
        val student = studentMessage()

        listOf(
            ModelFailureCode.PROVIDER_CAPABILITY_MISSING,
            ModelFailureCode.MODEL_NOT_CONFIGURED,
            ModelFailureCode.AUTHENTICATION_FAILED,
            ModelFailureCode.PROVIDER_REJECTED_INPUT,
        ).forEach { code ->
            val failed = failedReply(errorCode = code.name)
            assertNull(code.name, failed.resendTargetOrNull(listOf(student, failed)))
        }
    }

    @Test
    fun aReplyThatNeverSaidWhoItAnsweredHasNoResendTarget() {
        val failed = failedReply(
            errorCode = ModelFailureCode.NETWORK_UNAVAILABLE.name,
            replyToMessageId = null,
        )

        assertNull(failed.resendTargetOrNull(listOf(failed)))
    }

    @Test
    fun aSucceededReplyIsNeverAResendTarget() {
        val student = studentMessage()
        val succeeded = failedReply(errorCode = null).copy(
            status = TutorMessageStatus.SUCCEEDED,
            bodyMarkdown = "先看第一步…",
            errorCode = null,
        )

        assertNull(succeeded.resendTargetOrNull(listOf(student, succeeded)))
    }
}
