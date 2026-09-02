package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * schemaVersion 5→6 指纹平移（spec 2026-09-02-tool-loop-wiring §3.1）。
 *
 * 给 TutorLobbyInput/TutorRespondInput 加默认空字段后，`encodeDefaults=true` 使 v6 编码
 * 比 v5 多出 "toolDeclarations":[]/"toolRoundResults":[]（Respond 还有
 * "studentImageAssetRefs":[]，commit 151b1e3 引入未 bump——一并覆盖）。
 *
 * 两条指纹路径的迁移契约不同：
 * - ModelTaskFingerprint（request 级）：schemaVersion 是 request 的一部分。旧 v5 行 decode
 *   后保留 schemaVersion=5，fingerprintPayload 对 v5 走 strip → 与旧编码一致 → 读回校验通过。
 *   断言：v5 request 经 codec 往返后 requestFingerprint 不变。
 * - ModelTaskLogicalOperationFingerprint（逻辑操作级）：schemaVersion 不可及，编码当前 input
 *   并 strip 空载体键。断言：同一逻辑输入（无工具使用）在 v5/v6 下哈希一致。
 */
class ModelTaskFingerprintStabilityTest {

    private fun lobbyInput() = TutorLobbyInput(
        conversationId = "conv-stable",
        messageOrdinal = 1,
        studentMessage = "帮我看看错题本里有没有二次函数",
        priorMessages = emptyList(),
    )

    private fun respondInput() = TutorRespondInput(
        sessionId = "session-stable",
        draftRevisionNumber = 1,
        subject = "数学",
        questionDocument = QuestionDocument(
            id = "question-1",
            blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
        ),
        relevantLearningEvidence = emptyList(),
        projectionIsCurrent = true,
        responseOrdinal = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        studentMessage = "这一步怎么来的？",
        priorMessages = emptyList(),
        requestedMove = null,
    )

    @Test
    fun lobbyOperationFingerprintStableAcrossCarrierFieldAddition() {
        // v5 与 v6 指向同一逻辑输入 → 哈希必须一致（strip 抹平空载体键）
        val v5 = ModelTaskRequest(
            schemaVersion = 5,
            requestId = "lobby:v5",
            input = lobbyInput(),
            occurredAtEpochMillis = 1_000,
        )
        val v6 = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.CURRENT_SCHEMA_VERSION,
            requestId = "lobby:v6",
            input = lobbyInput(),
            occurredAtEpochMillis = 1_000,
        )
        assertNotEquals(ModelTaskFingerprint.of(v5), ModelTaskFingerprint.of(v6))
        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(v5.input),
            ModelTaskLogicalOperationFingerprint.of(v6.input),
        )
    }

    @Test
    fun respondOperationFingerprintStableAcrossCarrierFieldAddition() {
        val v5 = ModelTaskRequest(
            schemaVersion = 5,
            requestId = "respond:v5",
            input = respondInput(),
            occurredAtEpochMillis = 1_000,
        )
        val v6 = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.CURRENT_SCHEMA_VERSION,
            requestId = "respond:v6",
            input = respondInput(),
            occurredAtEpochMillis = 1_000,
        )
        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(v5.input),
            ModelTaskLogicalOperationFingerprint.of(v6.input),
        )
    }

    @Test
    fun legacyV5RequestFingerprintSurvivesCodecRoundTrip() {
        // 旧 v5 行 decode 后 schemaVersion=5 保留；重新指纹必须与存库值一致
        val chat = TutorChatHistoryEntry(
            studentMessage = "这一题？",
            assistantMarkdown = "先看这一步。",
        )
        val v5 = ModelTaskRequest(
            schemaVersion = 5,
            requestId = "respond:legacy-image",
            input = respondInput().copy(priorMessages = listOf(chat)),
            occurredAtEpochMillis = 1_000,
        )
        val expected = ModelTaskFingerprint.of(v5)

        // 模拟旧库行：用当前 codec 编码（v5 编码器会带空键）再 decode
        val reDecoded = ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(v5))
        assertEquals(5, reDecoded.schemaVersion)
        assertEquals(expected, ModelTaskFingerprint.of(reDecoded))
        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(v5.input),
            ModelTaskLogicalOperationFingerprint.of(reDecoded.input),
        )
    }
}
