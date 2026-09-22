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

    @Test
    fun respondBoundQuestionCarrierKeepsTheOperationFingerprintStableAcrossSchemaVersions() {
        // 候选菜单（schema 11）在空值下不得改变逻辑指纹：v10 行当年是按"没有这个键"算出来的。
        val v10 = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.LOBBY_CONTEXT_SCHEMA_VERSION,
            requestId = "respond:v10-menu",
            input = respondInput(),
            occurredAtEpochMillis = 1_000,
        )
        val v11 = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.CURRENT_SCHEMA_VERSION,
            requestId = "respond:v11-menu",
            input = respondInput(),
            occurredAtEpochMillis = 1_000,
        )

        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(v10.input),
            ModelTaskLogicalOperationFingerprint.of(v11.input),
        )
    }

    @Test
    fun aRespondRowWrittenBeforeTheBoundQuestionCarrierStillValidatesAfterUpgrade() {
        // bf8be888 的教训：旧 v10 行的编码里没有 boundQuestionCandidates 键，
        // decode 取默认空列表后重算逻辑指纹必须与存库值一致，否则 toStore 直接抛完整性异常。
        val legacyJson = ModelTaskCodec.encodeRequest(
            ModelTaskRequest(
                schemaVersion = ModelTaskRequest.LOBBY_CONTEXT_SCHEMA_VERSION,
                requestId = "respond:legacy-menu-row",
                input = respondInput(),
                occurredAtEpochMillis = 1_000,
            ),
        ).replace(",\"boundQuestionCandidates\":[]", "")
        val decoded = ModelTaskCodec.decodeRequest(legacyJson)

        assertEquals(ModelTaskRequest.LOBBY_CONTEXT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(respondInput()),
            ModelTaskLogicalOperationFingerprint.of(decoded.input),
        )
    }

    @Test
    fun aRealBoundQuestionMenuStillChangesTheOperationFingerprint() {
        // 反向要求：strip 只抹平空载体。真的带了候选菜单就是另一次输入，
        // 指纹必须变——否则换了一道候选重放会命中旧请求。
        val withMenu = respondInput().copy(boundQuestionCandidates = listOf(relatedCandidate()))

        assertNotEquals(
            ModelTaskLogicalOperationFingerprint.of(respondInput()),
            ModelTaskLogicalOperationFingerprint.of(withMenu),
        )
    }

    @Test
    fun aLegacySchemaRequestCannotCarryABoundQuestionMenu() {
        val rejected = runCatching {
            ModelTaskRequest(
                schemaVersion = ModelTaskRequest.LOBBY_CONTEXT_SCHEMA_VERSION,
                requestId = "respond:legacy-with-menu",
                input = respondInput().copy(
                    boundQuestionCandidates = listOf(relatedCandidate()),
                ),
                occurredAtEpochMillis = 1_000,
            )
        }

        assertTrue(rejected.isFailure)
    }

    @Test
    fun knownRoundQuestionCarrierKeepsTheOperationFingerprintStableAcrossSchemaVersions() {
        // 请求侧已知题锚（schema 12）在空值下不得改变逻辑指纹：v11 行当年是按"没有这个键"算的。
        val v11 = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.TUTOR_ROUND_BINDING_SCHEMA_VERSION,
            requestId = "respond:v11-known-anchor",
            input = respondInput(),
            occurredAtEpochMillis = 1_000,
        )
        val v12 = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.CURRENT_SCHEMA_VERSION,
            requestId = "respond:v12-known-anchor",
            input = respondInput(),
            occurredAtEpochMillis = 1_000,
        )

        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(v11.input),
            ModelTaskLogicalOperationFingerprint.of(v12.input),
        )
    }

    @Test
    fun aRespondRowWrittenBeforeTheKnownRoundQuestionCarrierStillValidatesAfterUpgrade() {
        // bf8be888 的教训（第三次同一条）：旧 v11 行的编码里没有 knownRoundQuestion 键，
        // 升级后读回该行重算**请求指纹**必须与存库值一致，否则 toSnapshot 直接抛完整性异常。
        // 存库值 = 当年 v11 编码器写出的那份 JSON 的摘要（本用例手工复现那份编码）。
        val legacyJson = ModelTaskCodec.encodeRequest(
            ModelTaskRequest(
                schemaVersion = ModelTaskRequest.TUTOR_ROUND_BINDING_SCHEMA_VERSION,
                requestId = "respond:legacy-known-anchor-row",
                input = respondInput(),
                occurredAtEpochMillis = 1_000,
            ),
        ).replace(",\"knownRoundQuestion\":null", "")
            .replace(",\"knowledgeCodes\":[]", "")
        val decoded = ModelTaskCodec.decodeRequest(legacyJson)

        assertEquals(ModelTaskRequest.TUTOR_ROUND_BINDING_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(sha256Hex(legacyJson), ModelTaskFingerprint.of(decoded))
        // 逻辑指纹走同一条纪律：数据库每次读回行都会用**当前**编码重算逻辑指纹并与存库值比对
        // （`ModelTaskEntity.toSnapshot` 抛 `LearningLedgerIntegrityException`），所以旧行的
        // 逻辑指纹也必须按"没有这个键"的那份输入编码算出来。
        assertEquals(
            sha256Hex("${decoded.input.kind.name}\n${legacyInputJson(decoded.input)}"),
            ModelTaskLogicalOperationFingerprint.of(decoded.input),
        )
    }

    @Test
    fun aRealKnownRoundQuestionStillChangesTheOperationFingerprint() {
        // 反向要求：strip 只抹平空载体。真的带了本轮已知题锚就是另一次输入，指纹必须变——
        // 否则"上一轮绑定的题换了"会重放命中旧请求。
        val withKnownAnchor = respondInput().copy(
            boundQuestionCandidates = listOf(relatedCandidate()),
            knownRoundQuestion = relatedCandidate(),
        )

        assertNotEquals(
            ModelTaskLogicalOperationFingerprint.of(respondInput()),
            ModelTaskLogicalOperationFingerprint.of(withKnownAnchor),
        )
    }

    @Test
    fun aLegacySchemaRequestCannotCarryAKnownRoundQuestion() {
        val rejected = runCatching {
            ModelTaskRequest(
                schemaVersion = ModelTaskRequest.TUTOR_ROUND_BINDING_SCHEMA_VERSION,
                requestId = "respond:legacy-with-known-anchor",
                input = respondInput().copy(
                    boundQuestionCandidates = listOf(relatedCandidate()),
                    knownRoundQuestion = relatedCandidate(),
                ),
                occurredAtEpochMillis = 1_000,
            )
        }

        assertTrue(rejected.isFailure)
    }

    @Test
    fun aKnownRoundQuestionOutsideTheMenuIsRejectedAtConstruction() {
        // 已知锚必须是本轮候选之一：不在菜单里的锚会让写工具拿着"本轮从没出现过的题"去写，
        // 菜单这条边界就被绕过了。
        val rejected = runCatching {
            respondInput().copy(knownRoundQuestion = relatedCandidate())
        }

        assertTrue(rejected.isFailure)
    }

    // ---- schema 13：单一代号通道 + Plan 工具环（铁律 7 的四个必测面）----

    private fun planInput() = TutorPlanInput(
        sessionId = "session-plan-stable",
        draftRevisionNumber = 1,
        subject = "数学",
        questionDocument = QuestionDocument(
            id = "question-plan",
            blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
        ),
        relevantLearningEvidence = emptyList(),
        projectionIsCurrent = true,
    )

    private fun codedPlanInput() = planInput().copy(
        knowledgeCodes = listOf(
            TutorKnowledgeCode(
                knowledgeNodeId = "kc-peifang",
                displayName = "配方法",
                role = TutorKnowledgeCodeRole.RETRIEVAL_CANDIDATE,
                code = "K1",
            ),
            TutorKnowledgeCode(
                knowledgeNodeId = "kc-monotonicity",
                displayName = "函数单调性",
                role = TutorKnowledgeCodeRole.PREREQUISITE,
                code = "K2",
            ),
        ),
        toolDeclarations = listOf(
            TutorToolName.KNOWLEDGE_READ,
            TutorToolName.NOTEBOOK_READ,
            TutorToolName.MASTERY_READ,
            TutorToolName.MASTERY_UPDATE,
            TutorToolName.NOTEBOOK_WRITE,
        ),
    )

    @Test
    fun knowledgeCodeChannelCarriersKeepTheOperationFingerprintStableAcrossSchemaVersions() {
        // ③ 空载体：Plan 的新键（knowledgeCodes / toolDeclarations / toolRoundResults /
        // teachingReferencesLoadFailed）与 Respond 的 knowledgeCodes 在空值下不得改变逻辑指纹——
        // v12 行当年是按"没有这些键"算出来的。
        val v12Plan = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.TUTOR_KNOWN_ROUND_QUESTION_SCHEMA_VERSION,
            requestId = "plan:v12-empty-carrier",
            input = planInput(),
            occurredAtEpochMillis = 1_000,
        )
        val v13Plan = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.CURRENT_SCHEMA_VERSION,
            requestId = "plan:v13-empty-carrier",
            input = planInput(),
            occurredAtEpochMillis = 1_000,
        )
        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(v12Plan.input),
            ModelTaskLogicalOperationFingerprint.of(v13Plan.input),
        )
        val v12Respond = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.TUTOR_KNOWN_ROUND_QUESTION_SCHEMA_VERSION,
            requestId = "respond:v12-empty-carrier",
            input = respondInput(),
            occurredAtEpochMillis = 1_000,
        )
        val v13Respond = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.CURRENT_SCHEMA_VERSION,
            requestId = "respond:v13-empty-carrier",
            input = respondInput(),
            occurredAtEpochMillis = 1_000,
        )
        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(v12Respond.input),
            ModelTaskLogicalOperationFingerprint.of(v13Respond.input),
        )
    }

    @Test
    fun aPlanRowWrittenBeforeTheKnowledgeCodeChannelStillValidatesAfterUpgrade() {
        // ① 旧行读取 + ④ 升级路径（bf8be888 的教训）：模拟 v12 时代写库的 Plan 行——
        // 当前 codec 编码后手工删掉 v13 新键，还原当年那份形状。decode 取默认值后，
        // **请求指纹**与**逻辑指纹**两个校验点都必须与存库值一致，否则 toSnapshot 抛完整性异常。
        val legacyJson = ModelTaskCodec.encodeRequest(
            ModelTaskRequest(
                schemaVersion = ModelTaskRequest.TUTOR_KNOWN_ROUND_QUESTION_SCHEMA_VERSION,
                requestId = "plan:legacy-code-channel-row",
                input = planInput(),
                occurredAtEpochMillis = 1_000,
            ),
        )
            .replace(",\"toolDeclarations\":[]", "")
            .replace(",\"toolRoundResults\":[]", "")
            .replace(",\"knowledgeCodes\":[]", "")
            .replace(",\"teachingReferencesLoadFailed\":false", "")
        val decoded = ModelTaskCodec.decodeRequest(legacyJson)

        assertEquals(ModelTaskRequest.TUTOR_KNOWN_ROUND_QUESTION_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(sha256Hex(legacyJson), ModelTaskFingerprint.of(decoded))
        assertEquals(
            sha256Hex("${decoded.input.kind.name}\n${legacyPlanInputJson(decoded.input)}"),
            ModelTaskLogicalOperationFingerprint.of(decoded.input),
        )
    }

    @Test
    fun aRespondRowWrittenBeforeTheKnowledgeCodeChannelStillValidatesAfterUpgrade() {
        // 同一升级路径的 Respond 半边：v12 行编码里没有 knowledgeCodes 键。
        val legacyJson = ModelTaskCodec.encodeRequest(
            ModelTaskRequest(
                schemaVersion = ModelTaskRequest.TUTOR_KNOWN_ROUND_QUESTION_SCHEMA_VERSION,
                requestId = "respond:legacy-code-channel-row",
                input = respondInput(),
                occurredAtEpochMillis = 1_000,
            ),
        ).replace(",\"knowledgeCodes\":[]", "")
        val decoded = ModelTaskCodec.decodeRequest(legacyJson)

        assertEquals(ModelTaskRequest.TUTOR_KNOWN_ROUND_QUESTION_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(sha256Hex(legacyJson), ModelTaskFingerprint.of(decoded))
        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(respondInput()),
            ModelTaskLogicalOperationFingerprint.of(decoded.input),
        )
    }

    @Test
    fun aCodedKnowledgeChannelRowRoundTripsAndKeepsItsFingerprint() {
        // ② 新行写入：v13 行带着真值（已赋码条目 + 全 5 工具声明）落库，
        // encode → decode 往返后两条指纹都逐位稳定。
        val v13 = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.CURRENT_SCHEMA_VERSION,
            requestId = "plan:new-coded-row",
            input = codedPlanInput(),
            occurredAtEpochMillis = 1_000,
        )
        val decoded = ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(v13))

        assertEquals(v13.input, decoded.input)
        assertEquals(ModelTaskFingerprint.of(v13), ModelTaskFingerprint.of(decoded))
        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(v13.input),
            ModelTaskLogicalOperationFingerprint.of(decoded.input),
        )
    }

    @Test
    fun aRealKnowledgeCodeChannelStillChangesTheOperationFingerprint() {
        // 反向要求：strip 只抹平空载体。真的带了一组代号披露（或 Plan 真的声明了工具），
        // 就是另一次输入——否则换一组候选重放会命中旧请求。
        assertNotEquals(
            ModelTaskLogicalOperationFingerprint.of(planInput()),
            ModelTaskLogicalOperationFingerprint.of(codedPlanInput()),
        )
        assertNotEquals(
            ModelTaskLogicalOperationFingerprint.of(respondInput()),
            ModelTaskLogicalOperationFingerprint.of(
                respondInput().copy(
                    knowledgeCodes = listOf(
                        TutorKnowledgeCode(
                            knowledgeNodeId = "kc-peifang",
                            displayName = "配方法",
                            role = TutorKnowledgeCodeRole.CONFIRMED_BINDING,
                            code = "K1",
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun aLegacySchemaRequestCannotCarryTheKnowledgeCodeChannelOrPlanToolRounds() {
        val planRejected = runCatching {
            ModelTaskRequest(
                schemaVersion = ModelTaskRequest.TUTOR_KNOWN_ROUND_QUESTION_SCHEMA_VERSION,
                requestId = "plan:legacy-with-codes",
                input = planInput().copy(
                    knowledgeCodes = listOf(
                        TutorKnowledgeCode(
                            knowledgeNodeId = "kc-peifang",
                            displayName = "配方法",
                            role = TutorKnowledgeCodeRole.CONFIRMED_BINDING,
                        ),
                    ),
                ),
                occurredAtEpochMillis = 1_000,
            )
        }
        val planToolRejected = runCatching {
            ModelTaskRequest(
                schemaVersion = ModelTaskRequest.TUTOR_KNOWN_ROUND_QUESTION_SCHEMA_VERSION,
                requestId = "plan:legacy-with-tools",
                input = planInput().copy(toolDeclarations = listOf(TutorToolName.NOTEBOOK_READ)),
                occurredAtEpochMillis = 1_000,
            )
        }
        val planLoadFailedRejected = runCatching {
            ModelTaskRequest(
                schemaVersion = ModelTaskRequest.TUTOR_KNOWN_ROUND_QUESTION_SCHEMA_VERSION,
                requestId = "plan:legacy-with-load-failed",
                input = planInput().copy(teachingReferencesLoadFailed = true),
                occurredAtEpochMillis = 1_000,
            )
        }
        val respondRejected = runCatching {
            ModelTaskRequest(
                schemaVersion = ModelTaskRequest.TUTOR_KNOWN_ROUND_QUESTION_SCHEMA_VERSION,
                requestId = "respond:legacy-with-codes",
                input = respondInput().copy(
                    knowledgeCodes = listOf(
                        TutorKnowledgeCode(
                            knowledgeNodeId = "kc-peifang",
                            displayName = "配方法",
                            role = TutorKnowledgeCodeRole.CONFIRMED_BINDING,
                        ),
                    ),
                ),
                occurredAtEpochMillis = 1_000,
            )
        }
        assertTrue("v12 Plan 行不得携带代号披露", planRejected.isFailure)
        assertTrue("v12 Plan 行不得携带工具声明", planToolRejected.isFailure)
        assertTrue("v12 Plan 行不得携带加载失败标志", planLoadFailedRejected.isFailure)
        assertTrue("v12 Respond 行不得携带代号披露", respondRejected.isFailure)
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    /**
     * 旧 v11 行当年算**逻辑**指纹时用的那份输入编码。
     *
     * 逻辑指纹链没有 schema 分支（`operationPayload` 无条件抹平空载体键），所以"当年"那份负载
     * = 今天的编码减掉 v12 新键、再减掉 v11 当时那套 strip：
     * - `"knownRoundQuestion":null`：v12 才有的键，当年的编码器不知道它；
     * - `"toolDeclarations":[]` / `"toolRoundResults":[]`（`withoutEmptyToolCarrier`）；
     * - `"priorDigest":null`（`withoutEmptyLobbyContext` 的 Respond 分支）；
     * - `"boundQuestionCandidates":[]`（v11 引入菜单时同一提交也把 strip 加进了逻辑链）。
     *
     * 手工复现这份负载是为了对**存库值**：生产侧删掉任何一条 strip，这里的摘要就与
     * `ModelTaskLogicalOperationFingerprint.of` 不再相等（本用例已用反证跑过）。
     */
    private fun legacyInputJson(input: ModelTaskInput): String = legacyFingerprintJson
        .encodeToString(ModelTaskInput.serializer(), input)
        .replace(",\"knownRoundQuestion\":null", "")
        .replace(",\"toolDeclarations\":[]", "")
        .replace(",\"toolRoundResults\":[]", "")
        .replace(",\"priorDigest\":null", "")
        .replace(",\"boundQuestionCandidates\":[]", "")
        .replace(",\"knowledgeCodes\":[]", "")

    private val legacyFingerprintJson = kotlinx.serialization.json.Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    /**
     * 旧 v12 Plan 行当年算**逻辑**指纹时用的那份输入编码：今天的编码减掉 v13 新键。
     * （逻辑指纹链对 v6/v10/v11/v12 引入的键已有无条件 strip，那些在这里不用重复。）
     */
    private fun legacyPlanInputJson(input: ModelTaskInput): String = legacyFingerprintJson
        .encodeToString(ModelTaskInput.serializer(), input)
        .replace(",\"knowledgeCodes\":[]", "")
        .replace(",\"toolDeclarations\":[]", "")
        .replace(",\"toolRoundResults\":[]", "")
        .replace(",\"teachingReferencesLoadFailed\":false", "")

    private fun relatedCandidate() = RelatedProblemCandidate(
        problemId = "problem-other",
        problemRevisionId = "revision-other",
        subject = SubjectKind.MATH,
        title = "另一道题",
        questionDocument = QuestionDocument(
            id = "question-other",
            blocks = listOf(ContentBlock.Paragraph("stem-other", "求另一个函数的单调区间")),
        ),
    )
}
