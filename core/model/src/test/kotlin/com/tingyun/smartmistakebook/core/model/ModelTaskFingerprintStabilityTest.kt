package com.tingyun.smartmistakebook.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
    fun lobbyContextCarriersKeepTheOperationFingerprintStableAcrossSchemaVersions() {
        // 上文图片与早期摘要这两个载体字段（schema 10）在空值下不得改变逻辑指纹：
        // 升级后要能读回旧行，而旧行的哈希是当年按"没有这两个键"算出来的。
        val v9 = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.LOBBY_IMAGE_SCHEMA_VERSION,
            requestId = "lobby:v9",
            input = lobbyInput(),
            occurredAtEpochMillis = 1_000,
        )
        val v10 = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.CURRENT_SCHEMA_VERSION,
            requestId = "lobby:v10",
            input = lobbyInput(),
            occurredAtEpochMillis = 1_000,
        )

        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(v9.input),
            ModelTaskLogicalOperationFingerprint.of(v10.input),
        )
    }

    @Test
    fun respondContextCarrierKeepsTheOperationFingerprintStableAcrossSchemaVersions() {
        val v9 = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.LOBBY_IMAGE_SCHEMA_VERSION,
            requestId = "respond:v9",
            input = respondInput(),
            occurredAtEpochMillis = 1_000,
        )
        val v10 = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.CURRENT_SCHEMA_VERSION,
            requestId = "respond:v10",
            input = respondInput(),
            occurredAtEpochMillis = 1_000,
        )

        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(v9.input),
            ModelTaskLogicalOperationFingerprint.of(v10.input),
        )
    }

    @Test
    fun aLobbyRowWrittenBeforeTheContextCarriersStillValidatesAfterUpgrade() {
        // 实测到的崩溃路径：升级后进智能体页要读最近的 Lobby 任务行，旧行不含这两个键，
        // decode 取默认值后重算逻辑指纹必须与存库值一致，否则 toSnapshot 直接抛完整性异常。
        val legacyJson = ModelTaskCodec.encodeRequest(
            ModelTaskRequest(
                schemaVersion = ModelTaskRequest.LOBBY_IMAGE_SCHEMA_VERSION,
                requestId = "lobby:legacy-row",
                input = lobbyInput(),
                occurredAtEpochMillis = 1_000,
            ),
        )
            .replace(",\"contextImageAssetRefs\":[]", "")
            .replace(",\"priorDigest\":null", "")
        val decoded = ModelTaskCodec.decodeRequest(legacyJson)

        assertEquals(ModelTaskRequest.LOBBY_IMAGE_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(lobbyInput()),
            ModelTaskLogicalOperationFingerprint.of(decoded.input),
        )
    }

    @Test
    fun aRealContextCarrierStillChangesTheOperationFingerprint() {
        // 反向要求：strip 只抹平空载体。真的带了上文图片或摘要，就是另一次输入，
        // 指纹必须变——否则重放会命中旧请求，把上一次的图当成这一次的。
        val withContextImage = lobbyInput().copy(
            contextImageAssetRefs = listOf(
                CaptureSourceAssetRef(
                    assetId = "asset-old",
                    sha256 = "a".repeat(64),
                    width = 1_080,
                    height = 1_440,
                    pageIndex = 0,
                ),
            ),
        )
        val withDigest = lobbyInput().copy(priorDigest = "第1轮 学生：定义域怎么写？")

        assertNotEquals(
            ModelTaskLogicalOperationFingerprint.of(lobbyInput()),
            ModelTaskLogicalOperationFingerprint.of(withContextImage),
        )
        assertNotEquals(
            ModelTaskLogicalOperationFingerprint.of(lobbyInput()),
            ModelTaskLogicalOperationFingerprint.of(withDigest),
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

    @Test
    fun legacyV7RowWithOldConsentKeyDecodesViaShimAndFingerprintIsStable() {
        // 模拟 v7 时代写库的行：字段名是 captureEgressConsentGranted（schema v7）。
        // v8 解码器 rename 后不认旧键（ignoreUnknownKeys=false），decode shim 需把它翻译。
        // v7 init guard 不允许 agentConsentGranted=true，故手工往 JSON 注入旧键值 true，
        // 等价于当年 v7 编码器实际会写出的行。
        val classify = ImagePipelineClassifyInput(
            sourceAssetId = "asset-1",
            imageWidth = 1080,
            imageHeight = 1440,
        )
        val v7 = ModelTaskRequest(
            schemaVersion = 7,
            requestId = "save-decision:legacy-v7",
            input = classify,
            occurredAtEpochMillis = 1_000,
        )
        val v7Encoded = ModelTaskCodec.encodeRequest(v7)
        val legacyV7Json = v7Encoded
            .replace("\"agentConsentGranted\":false", "\"captureEgressConsentGranted\":true")

        val decoded = ModelTaskCodec.decodeRequest(legacyV7Json)
        assertEquals(7, decoded.schemaVersion)
        assertEquals(true, decoded.agentConsentGranted)
        // 指纹稳定：decode 出的 v7 行（走 <8 strip，抹掉旧键）重算指纹 == 直接对旧 v7 JSON 的摘要
        val legacyStripped = legacyV7Json.replace(",\"captureEgressConsentGranted\":true", "")
        val expectedFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(legacyStripped.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectedFingerprint, ModelTaskFingerprint.of(decoded))
    }

    @Test
    fun currentV8RequestEncodesNewConsentKey() {
        // v8 编码必须用新键名，不能沿用旧键（否则 decode shim 判断会误伤）
        val respond = respondInput()
        val v8 = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.CURRENT_SCHEMA_VERSION,
            requestId = "respond:v8-new-key",
            input = respond,
            occurredAtEpochMillis = 1_000,
            agentConsentGranted = true,
        )
        val encoded = ModelTaskCodec.encodeRequest(v8)
        assertTrue("\"agentConsentGranted\":true" in encoded)
        assertFalse("\"captureEgressConsentGranted\"" in encoded)
        // 往返稳定
        val decoded = ModelTaskCodec.decodeRequest(encoded)
        assertEquals(true, decoded.agentConsentGranted)
        assertEquals(ModelTaskFingerprint.of(v8), ModelTaskFingerprint.of(decoded))
    }
}
