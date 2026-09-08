# 多协议模型 Provider 实现计划（P1：抽层，行为不变）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 把硬编码的 OpenAI Chat Completions 协议抽成可插拔的 `ModelWireProtocol` 层，并让配置带上协议字段；本阶段**不改变任何现有行为**，为后续新增 Anthropic / OpenAI Responses / Gemini 三个协议铺路。

**架构：** 网关按配置里的协议取实现，用协议构造 `WireRequest`（URL/头/体）交给传输层；响应与 SSE 解码也回到协议实现。任务层（prompt/JSON 解析）不动。

**技术栈：** Kotlin / OkHttp / kotlinx.serialization / MockWebServer / JUnit4。

**规格：** `docs/specs/2026-09-08-multi-protocol-model-provider-design.md`（§3.1/§3.2/§3.7）。

---

## 执行记录（2026-09-09）

| 任务 | 提交 | 验证 |
|---|---|---|
| 1 删死代码 `TutorProviderMatrix` | `3b94b31` | core:data 308/0；任务审查 clean |
| 2 抽协议层（行为不变） | `8f91a74` | 特征化 5/5；core:data 313/0；任务审查 clean（1 条重要发现经 amend 修复 + 定向复审确认） |
| 3 配置字段 + 指纹动态化 | `36e85e9` | core:domain 339/0、core:data 321/0；全模块 1388/0；双 flavor 编译；设备 5/5 |

执行中的计划偏差（均经控制者裁定并已回写本计划）：

- 协议枚举下沉 `core:model`（core:domain 需引用）——文件结构与任务 2 已改。
- 任务 2 的网关先用 `protocolFor(DEFAULT)`，任务 3 步骤 3b 翻到配置——已改。
- `ModelCapabilityTester.kt` 是 `post` 签名变化的第三调用方（文件清单遗漏）——已补。
- 能力探测的协议来源在任务 3 一并翻到配置：未实现协议 fail fast（`ProviderUnavailable`），
  不按 OpenAI 形状向真实端点发请求。

P1 验收（2026-09-09 当前证据）：全模块 **1388 单测 0 失败 0 错误**；`localFirst` +
`strictOffline`（含 androidTest）编译通过；`connectedLocalFirstDebugAndroidTest` 设备实测 **5/5**。

---

## 文件结构

**创建：**
- `core/model/src/main/kotlin/com/tingyun/smartmistakebook/core/model/ModelProviderProtocol.kt` —— 协议枚举。**必须在 core:model**：core:domain（配置快照）与 core:data（协议实现）都要用它，core:model 是两者共同的上游。
- `core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/model/wire/ModelWireProtocol.kt` —— 协议接口 + 协议解析入口。
- `core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/model/wire/OpenAiChatCompletionsProtocol.kt` —— 现有 OpenAI 行为的协议实现（委托现有 `OpenAiModelProtocol`/`OpenAiSse`）。
- `core/data/src/test/kotlin/com/tingyun/smartmistakebook/core/data/model/wire/OpenAiChatCompletionsProtocolTest.kt` —— 特征化测试：新实现与现状逐字节一致。

**修改：**
- `core/data/src/main/kotlin/.../model/OpenAiModelTransport.kt` —— `post` 改收 `WireRequest`；SSE 解码委托协议。
- `core/data/src/main/kotlin/.../model/OpenAiCompatibleModelGateway.kt` —— 按配置取协议、构造 `WireRequest`、解析委托协议、指纹动态化。
- `core/data/src/main/kotlin/.../model/ModelCapabilityTester.kt` —— **`post` 签名变化的第三个调用方**（执行中发现）：探测请求同步改为构造 `WireRequest`（`protocolFor(DEFAULT)`，行为不变）。
- `core/domain/src/main/kotlin/.../domain/ModelConfigurationStore.kt` —— `ModelConfigurationSnapshot`/`ModelConfigurationUpdate` 增 `protocol`。
- `core/data/src/main/kotlin/.../domain/...`（DataStore 实现，见任务 3 步骤）—— 持久化协议字段。
- 测试夹具：`modelTransport { ... }` 的所有调用点（随 `post` 签名变化）。

**删除：**
- `core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/tutor/TutorProviderMatrix.kt`（558 行，无调用方）。

---

### 任务 1：删除死代码 `TutorProviderMatrix`

**文件：**
- 删除：`core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/tutor/TutorProviderMatrix.kt`

- [ ] **步骤 1：确认无引用**

运行：

```bash
grep -rn "TutorProviderMatrix" --include=*.kt . | grep -v "/build/" | grep -v "^./.worktrees/"
```

预期：只命中该文件自身（其它命中都在 `.worktrees/` 副本里，不属本仓库源码）。若有任何其它命中，停止并报告。

- [ ] **步骤 2：删除并编译**

```bash
git rm core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/tutor/TutorProviderMatrix.kt
./gradlew :core:data:compileDebugKotlin :app:compileLocalFirstDebugKotlin --console=plain
```

预期：`BUILD SUCCESSFUL`。

- [ ] **步骤 3：跑该模块单测**

运行：`./gradlew :core:data:testDebugUnitTest --console=plain`
预期：`BUILD SUCCESSFUL`，308 个单测 0 失败。

- [ ] **步骤 4：Commit**

```bash
git add -A
git commit -m "chore(data): 删除死代码 TutorProviderMatrix（558 行无调用方，协议扩展前清理）"
```

---

### 任务 2：抽出 `ModelWireProtocol`（OpenAI 行为不变）

**文件：**
- 创建：`core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/model/wire/ModelWireProtocol.kt`
- 创建：`core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/model/wire/OpenAiChatCompletionsProtocol.kt`
- 测试：`core/data/src/test/kotlin/com/tingyun/smartmistakebook/core/data/model/wire/OpenAiChatCompletionsProtocolTest.kt`
- 修改：`OpenAiModelTransport.kt`、`OpenAiCompatibleModelGateway.kt`

- [ ] **步骤 1：写失败测试（特征化：新实现与现状一致）**

```kotlin
package com.tingyun.smartmistakebook.core.data.model.wire

import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.ModelProviderProtocol
import com.tingyun.smartmistakebook.core.data.model.OpenAiModelProtocol
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatCompletionsProtocolTest {
    private val protocol = OpenAiChatCompletionsProtocol

    @Test
    fun endpointMatchesTheCurrentChatCompletionsPath() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            protocol.endpoint("https://api.example.com/v1".toHttpUrl(), "any-model", stream = false).toString(),
        )
    }

    @Test
    fun headersMatchTheCurrentBearerAndAcceptContract() {
        val headers = protocol.headers("sk-test".toCharArray(), stream = false)
        assertEquals("Bearer sk-test", headers.single { it.first == "Authorization" }.second)
        assertEquals("application/json; charset=utf-8", headers.single { it.first == "Accept" }.second)
        assertEquals(
            "text/event-stream",
            protocol.headers("sk-test".toCharArray(), stream = true).single { it.first == "Accept" }.second,
        )
    }

    @Test
    fun requestBodyIsByteIdenticalToTheCurrentProtocol() {
        val input = CaptureAssessmentInput(
            draftId = "draft-1",
            sourceAssetId = "asset-1",
            origin = CaptureAssessmentOrigin.LIBRARY,
            imageWidth = 100,
            imageHeight = 200,
        )
        assertEquals(
            OpenAiModelProtocol.requestBody("test-model", input, emptyList(), stream = false, enableNativeTools = false),
            protocol.requestBody("test-model", input, emptyList(), stream = false, enableNativeTools = false),
        )
    }

    @Test
    fun parseCompletionMatchesTheCurrentProtocol() {
        val input = CaptureAssessmentInput(
            draftId = "draft-1",
            sourceAssetId = "asset-1",
            origin = CaptureAssessmentOrigin.LIBRARY,
            imageWidth = 100,
            imageHeight = 200,
        )
        val body = """{"choices":[{"message":{"content":"{\"decision\":\"PASS\",\"issues\":[],\"suggestedActions\":[]}"}}]}"""
        assertEquals(
            OpenAiModelProtocol.parseResponse(body, input, "test-model"),
            protocol.parseCompletion(body, input, "test-model"),
        )
    }

    @Test
    fun protocolIsTheDefaultAndKeepsNativeTools() {
        assertEquals(ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS, protocol.protocol)
        assertTrue(protocol.supportsNativeTools)
        assertTrue(protocol.supportsJsonObjectEnvelope)
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`./gradlew :core:data:testDebugUnitTest --tests "*OpenAiChatCompletionsProtocolTest" --console=plain`
预期：编译失败（`OpenAiChatCompletionsProtocol` 未定义）。

- [ ] **步骤 3：实现协议接口与 OpenAI 实现**

`core/model/.../ModelProviderProtocol.kt`：

```kotlin
package com.tingyun.smartmistakebook.core.model

/** 模型 Provider 的线上协议族（spec 2026-09-08-multi-protocol §3.1）。 */
enum class ModelProviderProtocol(val wireId: String) {
    OPENAI_CHAT_COMPLETIONS("openai-chat-completions-v1"),
    OPENAI_RESPONSES("openai-responses-v1"),
    ANTHROPIC_MESSAGES("anthropic-messages-v1"),
    GEMINI_GENERATE_CONTENT("gemini-generate-content-v1beta"),
    ;

    companion object {
        val DEFAULT: ModelProviderProtocol = OPENAI_CHAT_COMPLETIONS
    }
}
```

`wire/ModelWireProtocol.kt`：

```kotlin
package com.tingyun.smartmistakebook.core.data.model.wire

import com.tingyun.smartmistakebook.core.data.model.ApprovedImage
import com.tingyun.smartmistakebook.core.model.ModelProviderProtocol
import com.tingyun.smartmistakebook.core.model.ModelTaskInput
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import okhttp3.HttpUrl

/**
 * 一个协议族要提供的**全部协议相关行为**。任务 prompt 与 JSON 解析不在此层
 * （见 OpenAiModelTaskAdapters）：协议实现只负责"信封"。
 */
internal interface ModelWireProtocol {
    val protocol: ModelProviderProtocol
    /** 是否支持原生 tools 往返（仅 OpenAI 兼容为 true；新协议走 Route B）。 */
    val supportsNativeTools: Boolean
    /** 是否有 json_object 信封（无则靠 prompt 约束 JSON）。 */
    val supportsJsonObjectEnvelope: Boolean

    fun endpoint(baseUrl: HttpUrl, modelId: String, stream: Boolean): HttpUrl

    fun headers(apiKey: CharArray, stream: Boolean): List<Pair<String, String>>

    fun requestBody(
        modelId: String,
        input: ModelTaskInput,
        images: List<ApprovedImage>,
        stream: Boolean,
        enableNativeTools: Boolean,
    ): String

    fun parseCompletion(body: String, input: ModelTaskInput, modelVersion: String): ModelTaskOutput

    /** SSE 单帧的文本增量；非文本帧返回 null。 */
    fun streamDelta(payload: String): String?

    /** 把整段 SSE 重建成一次完整响应体（供终态解析）。 */
    fun reconstructedBody(rawSse: String): String
}
```

`wire/OpenAiChatCompletionsProtocol.kt`：

```kotlin
package com.tingyun.smartmistakebook.core.data.model.wire

import com.tingyun.smartmistakebook.core.data.model.ApprovedImage
import com.tingyun.smartmistakebook.core.data.model.OpenAiModelProtocol
import com.tingyun.smartmistakebook.core.data.model.OpenAiSse
import com.tingyun.smartmistakebook.core.model.ModelProviderProtocol
import com.tingyun.smartmistakebook.core.model.ModelTaskInput
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import okhttp3.HttpUrl

/**
 * OpenAI Chat Completions 协议（spec §3.1）：现有行为的原样搬迁——本类只做委托，
 * 逻辑仍在 OpenAiModelProtocol/OpenAiSse，保证 P1 阶段行为逐字节不变。
 */
internal object OpenAiChatCompletionsProtocol : ModelWireProtocol {
    override val protocol = ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS
    override val supportsNativeTools = true
    override val supportsJsonObjectEnvelope = true

    override fun endpoint(baseUrl: HttpUrl, modelId: String, stream: Boolean): HttpUrl =
        baseUrl.newBuilder()
            .addPathSegment("chat")
            .addPathSegment("completions")
            .build()

    override fun headers(apiKey: CharArray, stream: Boolean): List<Pair<String, String>> = listOf(
        "Authorization" to "Bearer ${String(apiKey)}",
        "Accept" to if (stream) SSE_ACCEPT else JSON_MEDIA_TYPE.toString(),
    )

    override fun requestBody(
        modelId: String,
        input: ModelTaskInput,
        images: List<ApprovedImage>,
        stream: Boolean,
        enableNativeTools: Boolean,
    ): String = OpenAiModelProtocol.requestBody(modelId, input, images, stream, enableNativeTools)

    override fun parseCompletion(body: String, input: ModelTaskInput, modelVersion: String): ModelTaskOutput =
        OpenAiModelProtocol.parseResponse(body, input, modelVersion)

    override fun streamDelta(payload: String): String? = OpenAiSse.deltaContent(payload)

    override fun reconstructedBody(rawSse: String): String = OpenAiSse.reconstructedChatCompletion(rawSse)
}
```

> 实现注意：`JSON_MEDIA_TYPE`（`MediaType`）与 `SSE_ACCEPT` 目前是
> `OpenAiModelTransport.kt:358-359` 的 **private** 常量——把这两行改成 `internal`
> 后在本文件复用（单一来源），**不要**另起一份同值常量。
> `OpenAiSse.deltaContent`/`reconstructedChatCompletion` 已是 `internal`，同模块可见。

- [ ] **步骤 4：运行测试确认通过**

运行：`./gradlew :core:data:testDebugUnitTest --tests "*OpenAiChatCompletionsProtocolTest" --console=plain`
预期：5 个测试全部 PASS。

- [ ] **步骤 5：接线——传输层改收 `WireRequest`**

`OpenAiModelTransport.kt`：

```kotlin
internal class WireRequest(
    val url: HttpUrl,
    val headers: List<Pair<String, String>>,
    val body: String,
    val stream: Boolean,
    val protocol: ModelWireProtocol,
)

internal fun interface ModelHttpTransport {
    suspend fun post(request: WireRequest, beforeEnqueue: suspend () -> Unit): ModelHttpResponse
}
```

`OkHttpModelTransport.post` 改为：用 `request.url` 做 SSRF 校验与 DNS 固定；逐条写入
`request.headers`；`stream` 取 `request.stream`（不再靠 `requestBody.contains("\"stream\":true")`）；
SSE 分支用 `request.protocol.reconstructedBody(raw)` 与 `request.protocol.streamDelta(...)`
替换 `OpenAiSse.reconstructedChatCompletion` / `OpenAiSse.deltaChunks`。

- [ ] **步骤 6：接线——网关按配置取协议**

`OpenAiCompatibleModelGateway.execute` 内：

```kotlin
// 任务 2 阶段配置还没有 protocol 字段（任务 3 才加），先用默认协议——DEFAULT 即
// OPENAI_CHAT_COMPLETIONS，唯一实现，行为逐字节不变；任务 3 把它翻成
// protocolFor(credential.configuration.protocol)（见任务 3 步骤 3b）。
val protocol = protocolFor(ModelProviderProtocol.DEFAULT)
val requestBody = protocol.requestBody(
    modelId = provider.modelId,
    input = execution.request.input,
    images = images,
    stream = stream,
    enableNativeTools = provider.supportsFunctionCalling && protocol.supportsNativeTools,
)
val response = transport.post(
    WireRequest(
        url = protocol.endpoint(credential.configuration.baseUrl.toHttpUrl(), provider.modelId, stream),
        headers = protocol.headers(keyChars, stream),
        body = requestBody,
        stream = stream,
        protocol = protocol,
    ),
    beforeEnqueue = { ...现有逻辑不变... },
)
```

`toGatewayEvent` 与 `emitStreamingThenCompletion` 改为接收 `protocol` 并调用
`protocol.parseCompletion(...)`。

`wire/ModelWireProtocol.kt` 追加解析入口：

```kotlin
internal fun protocolFor(protocol: ModelProviderProtocol): ModelWireProtocol = when (protocol) {
    ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS -> OpenAiChatCompletionsProtocol
    else -> error("Protocol $protocol is not implemented yet (P2+)")
}
```

- [ ] **步骤 7：更新测试夹具并跑全量**

`modelTransport { ... }` 的签名随 `post` 变化，按新签名（`WireRequest` 单参）机械更新所有调用点。

运行：

```bash
./gradlew :core:data:testDebugUnitTest --console=plain
```

预期：`BUILD SUCCESSFUL`，0 失败——**这是"行为不变"的验收门**。

- [ ] **步骤 8：Commit**

```bash
git add -A
git commit -m "refactor(data): 抽出 ModelWireProtocol，OpenAI 协议行为不变（多协议 P1）"
```

---

### 任务 3：配置协议字段 + 指纹动态化 + P1 验收

**文件：**
- 修改：`core/domain/src/main/kotlin/.../domain/ModelConfigurationStore.kt`
- 修改：DataStore 实现（`grep -rn "class .*ModelConfigurationStore" core/data/src/main` 定位）
- 修改：`OpenAiCompatibleModelGateway.kt`（`configurationFingerprint`/`toCapabilities`）
- 测试：`core/domain/src/test/.../ModelConfigurationStoreTest.kt`（若不存在则新建）、`core/data/src/test/.../OpenAiCompatibleModelGatewayTest.kt`

- [ ] **步骤 1：写失败测试**

```kotlin
@Test
fun configurationCarriesTheSelectedProtocolAndDefaultsToOpenAiCompatible() {
    assertEquals(ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS, ModelConfigurationSnapshot().protocol)
    assertEquals(
        ModelProviderProtocol.ANTHROPIC_MESSAGES,
        ModelConfigurationSnapshot(protocol = ModelProviderProtocol.ANTHROPIC_MESSAGES).protocol,
    )
}

@Test
fun capabilityFingerprintChangesWhenTheProtocolChanges() {
    val openAi = gatewayFor(ModelConfigurationSnapshot(protocol = ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS)).capabilities()
    val anthropic = gatewayFor(ModelConfigurationSnapshot(protocol = ModelProviderProtocol.ANTHROPIC_MESSAGES)).capabilities()
    assertNotEquals(openAi.providerId, anthropic.providerId)
}
```

（`gatewayFor` 用现有测试里的 `FakeConfigurationStore` + 假传输构造。）

- [ ] **步骤 2：运行确认失败**

运行：`./gradlew :core:domain:test --tests "*ModelConfigurationStoreTest" --console=plain`
预期：编译失败（`protocol` 字段不存在）。

- [ ] **步骤 3：实现**

`ModelConfigurationSnapshot` 与 `ModelConfigurationUpdate` 增：

```kotlin
val protocol: ModelProviderProtocol = ModelProviderProtocol.DEFAULT,
```

DataStore 实现新增键 `protocol`（存 `wireId`），缺省读作 `OPENAI_CHAT_COMPLETIONS`
（老配置零迁移）。写入时存 `protocol.wireId`；读取时按 `wireId` 匹配，未知值回退默认并在
配置校验里报错（不静默降级）。

`configurationFingerprint()` 与 `providerConfigurationVersion` 用协议 id 替换字面量：

```kotlin
val canonical = listOf(
    protocol.wireId,          // 原 "openai-compatible-v1"
    provider, baseUrl, modelId, configurationVersion, updatedAtEpochMillis.toString(),
).joinToString("\n")
// providerConfigurationVersion = "${protocol.wireId}-${fingerprint.take(32)}"
```

- [ ] **步骤 4：运行确认通过**

运行：

```bash
./gradlew :core:domain:test :core:data:testDebugUnitTest --console=plain
```

预期：`BUILD SUCCESSFUL`，0 失败。

- [ ] **步骤 3b：把网关的协议来源翻到配置上**

任务 2 阶段网关写的是 `protocolFor(ModelProviderProtocol.DEFAULT)`（因为当时还没有 `protocol`
字段）。现在把它改成：

```kotlin
val protocol = protocolFor(credential.configuration.protocol)
```

并补一条测试：`ModelConfigurationSnapshot(protocol = ANTHROPIC_MESSAGES)` 时网关选到的协议
实现是 `ANTHROPIC_MESSAGES` 对应的实现（P1 阶段 Anthropic 尚未实现 → 断言 `protocolFor`
抛错信息包含协议名，证明"配置真的驱动了协议选择"，而不是永远走 DEFAULT）。

- [ ] **步骤 3c：运行确认通过**

运行：`./gradlew :core:data:testDebugUnitTest --console=plain`
预期：`BUILD SUCCESSFUL`，0 失败。

- [ ] **步骤 5：P1 全量验收（当前证据）**

运行：

```bash
./gradlew test :app:compileStrictOfflineDebugKotlin :app:compileStrictOfflineDebugAndroidTestKotlin --console=plain
./gradlew :app:connectedLocalFirstDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.tingyun.smartmistakebook.DualReviewEntryInstrumentedTest,com.tingyun.smartmistakebook.KnowledgeReviewSessionInstrumentedTest --console=plain
```

预期：全模块单测 0 失败（≥1372）；双 flavor 含 androidTest 编译通过；设备仪器测试 5/5。
**任何一项不通过都不得声称 P1 完成**。

- [ ] **步骤 6：Commit**

```bash
git add -A
git commit -m "feat(domain): 配置增加协议字段 + 能力指纹随协议失效（多协议 P1）"
```

---

## 自检

**1. 规格覆盖度：** §3.1 抽象 → 任务 2；§3.2 配置/显式选择/指纹动态化 → 任务 3；
§3.7 死代码清理 → 任务 1。§3.3/§3.4/§3.5/§3.6 属 P2–P4（本计划不含，规格已分期）。

**2. 占位符扫描：** 无"待定/TODO"；每个代码步骤都给了可执行代码或精确到函数名的指令。

**3. 类型一致性：** `ModelWireProtocol.requestBody(..., enableNativeTools)` 与任务 2 步骤 6
的调用一致；`WireRequest` 字段（url/headers/body/stream/protocol）在步骤 5/6 一致；
`ModelProviderProtocol.DEFAULT` 在任务 3 与枚举定义一致。

**已知风险：** 步骤 7 的测试夹具更新是机械但面广的改动（`OpenAiCompatibleModelGatewayTest`
约 2900 行、多个 `modelTransport { }` 调用点）；若签名改动的连锁范围超出预期，可改为给
`post` 增加一个带默认实现的适配器而不是直接改签名——执行时若遇阻，先报告再决定。
