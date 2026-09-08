# 多协议大模型 Provider 设计：OpenAI 兼容 + Anthropic + OpenAI Responses + Gemini

> 状态：待用户审查（2026-09-08）。审查通过后进入实现计划（writing-plans）。

## 0. 目标

把"模型 Provider 协议"从硬编码的 OpenAI Chat Completions 抽成可插拔协议层，新增
Anthropic Messages、OpenAI Responses、Google Gemini generateContent 三个协议。
任务层（30+ 个模型任务的 prompt 与解析）**不动**——它们是协议无关的。

## 1. 用户确认的需求要点（2026-09-08）

- **范围**：Anthropic Messages、OpenAI Responses、Google Gemini generateContent。
  本地模型（Ollama/LM Studio）与企业云（Bedrock/Vertex）**不在本期**。
- **协议选择方式**：**显式**——配置新增协议字段 + 设置界面下拉（方案 A）。
- **工具环**：新协议一律 **Route B**（现有 `json_object` 文本协议回退），不实现原生
  tools 映射；`supportsFunctionCalling` 对新协议恒为 false。
- **安全边界不变**：SSRF 守卫保留（仅公网 HTTPS），本期不放开本地/私网端点。

## 2. 现状（已核实，代码事实）

协议形状硬编码在 4 处，全部假设 OpenAI Chat Completions：

| 位置 | 硬编码内容 |
|---|---|
| `OpenAiModelTransport.PublicModelEndpoint.resolve` | 路径 `{base}/chat/completions` |
| `OkHttpModelTransport.post` | 请求头 `Authorization: Bearer <key>` |
| `OpenAiModelProtocol.encodeRequestBody` | 请求体 `{model,temperature,stream,response_format,tools,messages[]}` |
| `OpenAiModelProtocol.parseResponse` + `OpenAiSse` | 响应 `choices[].message.content` / SSE `choices[].delta` |

另外两处协议耦合：

- `ModelCapabilityTester`（410 行）：探测请求用 OpenAI 形状（`response_format`/`tools`/`image_url`）；
  **但断言是协议无关的**（"回复是不是带 token 的 JSON 对象"/"是不是图片 token"/"有没有 tool_calls"）。
- `OpenAiCompatibleModelGateway.toCapabilities()`：`providerConfigurationVersion` 与指纹里
  硬编码字面量 `"openai-compatible-v1"`——加协议必须动态化，否则切换协议不会让旧的能力
  验证失效。

**已有的两条缝**（决定了改造集中度）：

1. `ModelHttpTransport` 是接口——HTTP 层可替换。
2. `OpenAiModelTaskAdapters.prompt/parse` 按任务分发，**与协议无关**——30+ 任务的
   prompt 与 JSON 解析不用重写。

**死代码**：`TutorProviderMatrix`（558 行）自己又实现了一遍 OpenAI 形状，全仓库无调用方
（仅自身文件与 `.worktrees` 副本）。扩展协议前先删除，避免扩展一个没人用的副本。

**外部核对（2026-09-08，官方 SDK 源码）**：

- Anthropic：`messages.create(max_tokens=…（必填）, model=…, messages=[{role,content}])`；
  响应 `{content:[{type:"text",text}], role:"assistant", stop_reason, usage}`
  （`anthropics/anthropic-sdk-python` 的 `types/message.py`）。
- OpenAI：README 明示 **Responses API 是当前主接口**（`responses.create(model, instructions, input)`
  → `output_text`），Chat Completions 为"上一代标准，无限期支持"。
- Gemini：`genai.Client(api_key=…)`（`googleapis/python-genai`）；REST 形状待实现时按官方文档核对。

> 本环境访问不到三家官方文档站（区域限制），表中未标"已核实"的字段在实现时逐条对官方
> 文档/SDK 源码核对，并写进协议实现的注释。

## 3. 设计

### 3.1 协议枚举与抽象

```kotlin
enum class ModelProviderProtocol {
    OPENAI_CHAT_COMPLETIONS,   // 默认；覆盖 DeepSeek/Qwen/Moonshot/vLLM 等兼容端点
    OPENAI_RESPONSES,
    ANTHROPIC_MESSAGES,
    GEMINI_GENERATE_CONTENT,
}

internal interface ModelWireProtocol {
    val protocol: ModelProviderProtocol
    /** 该协议是否支持原生 tools（仅 OpenAI 兼容为 true；新协议走 Route B）。 */
    val supportsNativeTools: Boolean
    /** 该协议是否有 json_object 信封（无则靠 prompt 约束 JSON）。 */
    val supportsJsonObjectEnvelope: Boolean
    fun endpoint(baseUrl: HttpUrl, modelId: String, stream: Boolean): HttpUrl
    fun headers(apiKey: CharArray, stream: Boolean): List<Pair<String, String>>
    fun requestBody(modelId: String, input: ModelTaskInput, images: List<ApprovedImage>, stream: Boolean): String
    fun parseCompletion(body: String, input: ModelTaskInput, modelVersion: String): ModelTaskOutput
    /** 流式文本增量；非流式或非文本增量返回 null。 */
    fun parseStreamDelta(chunk: String): String?
}
```

- **OpenAI Chat Completions**：现有 `OpenAiModelProtocol` 的请求/解析**原样搬进实现**，
  行为不变（现有 MockWebServer 测试当护栏）。
- 其余三个协议各一个实现；**只有** `endpoint/headers/requestBody/parseCompletion/parseStreamDelta`
  是协议相关代码，任务解析复用现有 `OpenAiModelResponseParsers`。

### 3.2 配置与协议选择（显式）

- `ModelConfigurationSnapshot` / `ModelConfigurationUpdate` 增
  `protocol: ModelProviderProtocol = OPENAI_CHAT_COMPLETIONS`；DataStore 新增键，缺省即旧行为
  （老配置零迁移成本）。
- 设置界面（`SecondaryScreens.CapabilityScreen`）加协议下拉，四项；默认 OpenAI 兼容。
- `provider` 自由文本字段保留（界面显示 + `ProviderCapabilitySnapshot.providerDisplayName`）；
  egress manifest 的 `providerId` 是指纹派生的 `configured-<fingerprint16>`（见下条），不直接用该文本。
- **指纹动态化**：`configurationFingerprint()` 与 `providerConfigurationVersion` 以
  `protocol.wireId`（如 `anthropic-messages-v1`）替换字面量 `"openai-compatible-v1"`——
  切换协议会让旧的能力验证与 egress 授权失效，必须重新探测。

### 3.3 四协议差异清单

| 维度 | OpenAI Chat（已支持） | OpenAI Responses | Anthropic Messages | Gemini generateContent |
|---|---|---|---|---|
| 路径 | `{base}/chat/completions` | `{base}/responses` | `{base}/v1/messages` | `{base}/v1beta/models/{model}:generateContent`（流式 `:streamGenerateContent?alt=sse`） |
| 认证 | `Authorization: Bearer` | 同左 | `x-api-key` + `anthropic-version: 2023-06-01` | `x-goog-api-key` |
| 系统提示 | `messages[0].role=system` | `instructions` | 顶层 `system` | `systemInstruction.parts[].text` |
| 输入 | `messages[].content` | `input` | `messages[].content` 块数组 | `contents[].parts[]` |
| 图片 | `image_url: data:` | `input_image` | `image.source.base64` | `inlineData` |
| JSON 约束 | `response_format: json_object` | 无（prompt 约束） | 无（prompt 约束） | `generationConfig.responseMimeType: application/json` |
| 响应文本 | `choices[0].message.content` | `output[].content[].text` | `content[].text` | `candidates[0].content.parts[].text` |
| 流式增量 | SSE `choices[].delta` | SSE `response.output_text.delta` | SSE `content_block_delta` | SSE `candidates[].parts[].text` |
| `max_tokens` | 可选 | 可选 | **必填** | 可选 |
| 原生 tools | `tools` | `tools`（形状不同） | `tools[].input_schema` | `functionDeclarations` |

（未标"已支持"的行：实现时逐条核对官方文档/SDK 源码后定稿。）

### 3.4 能力探测

- **请求按协议构造，断言不变**：`acceptsStructuredOutput` / `acceptsImageInput` /
  `acceptsTools` 的判定逻辑保持现有实现（协议无关）；只需把 `structuredOutputProbe` /
  `imageProbe` / `toolsProbe` 的**请求体**按协议生成。
- 新协议无 `json_object` 信封：结构化探测请求省去 `response_format`，仍要求模型返回
  带 token 的 JSON 对象——探测通过则 `supportsStructuredOutput = true`，核心任务集正常启用。
- 新协议**不探测** tools（`supportsFunctionCalling` 恒 false，Route A 保持关闭）。

### 3.5 流式

- 三家 SSE 事件形状不同（见 3.3）。`ModelHttpResponse.streamChunks` 的契约不变——
  协议实现负责把各自事件映射成**纯文本增量**。
- 非文本事件（`message_start`、`ping`、`response.completed` 等）返回 null，由现有
  `emitStreamingThenCompletion` 忽略。

### 3.6 错误映射

- HTTP 状态码映射（401/403/404/413/429/5xx → 现有 `ModelFailureCode`）保持不变。
- 错误消息提取：OpenAI 用 `error.message`，Anthropic 用 `error.message`（同形），
  Gemini 用 `error.message`（`google.rpc.Status` 形状）——差异小，协议实现提供
  `errorMessage(body)` 即可。

### 3.7 死代码清理

- 删除 `TutorProviderMatrix`（558 行，无调用方）。**前置**：确认 `.worktrees` 与测试均不引用。

## 4. 与既有系统的边界

- **任务层不动**：prompt 与 JSON 解析继续由 `OpenAiModelTaskAdapters` 负责。
- **egress 不变**：manifest 的披露/禁止数据类、授权 TTL、promptPolicyVersion 语义不变；
  仅 `providerConfigurationVersion` 的指纹来源变为协议感知。
- **SSRF 不变**：仍要求公网 HTTPS；新协议不新增网络出口。
- **不新增数据出网**：协议差异只影响信封形状，不改变披露内容。

## 5. 测试策略

- **抽层回归**：现有 OpenAI 协议的 MockWebServer 测试（`OpenAiModelProtocolMockWebServerTest`、
  `OpenAiCompatibleModelGatewayTest`、`KnowledgeQuizProtocolMockWebServerTest` 等）必须全绿，
  证明"行为不变"。
- **每协议一组**（MockWebServer）：请求体形状（路径/认证头/系统提示/图片/JSON 约束）、
  响应解析、流式增量、错误映射、能力探测请求。
- **配置层**：协议字段的持久化/迁移（旧配置默认 OpenAI 兼容）、指纹随协议变化。
- **界面**：协议下拉的选项与默认值（Compose 仪器测试，沿用现有 `createComposeRule`）。

## 6. 分期（每期独立可验证）

- **P1 抽层 + OpenAI 行为不变**：`ModelWireProtocol` + OpenAI 实现搬迁 + 配置字段 +
  指纹动态化 + 删死代码。验收：现有全部测试绿，功能无变化。
- **P2 Anthropic Messages**：协议实现 + 探测 + 测试。
- **P3 OpenAI Responses**：同上。
- **P4 Gemini generateContent**：同上（model 在路径、`responseMimeType` 差异）。
- **P5 设置界面下拉 + 端到端**：UI + 设备实测。

## 7. 风险与待核对

- **待核对（实现时逐条对官方文档/SDK 源码）**：3.3 表中未标"已支持"的字段、
  Gemini 的 REST 形状与流式事件名、Anthropic 的 `max_tokens` 上限语义、
  Responses 的 `output` 数组形状与流式事件名。
- **风险**：抽层触碰已验证的 OpenAI 路径——用现有测试当护栏，P1 严格"行为不变"。
- **已核实（不再是风险）**：解析层已容忍 markdown 围栏——`unwrapJsonFence()` 在解析入口
  剥离 ```json 围栏（`OpenAiModelResponseParsing.kt:30`，用于 `OpenAiModelProtocol.parseResponse:433`）。
  因此没有 `json_object` 信封的协议，只要把文本块交给同一解析入口即可。

## 8. 执行记录（2026-09-09，P1–P5 全部完成）

| 阶段 | 提交 | 内容 |
|---|---|---|
| P1 | `3b94b31` `8f91a74` `36e85e9` | 删死代码 → 抽 `ModelWireProtocol`（OpenAI 行为逐字节不变）→ 配置 `protocol` 字段 + 指纹动态化 |
| P2 | `dd233df` | Anthropic Messages 协议 + 探测请求/响应按协议分派 |
| P3 | `9632afa` | OpenAI Responses 协议 |
| P4 | `3e76ce8` | Gemini generateContent 协议；`protocolFor` 改穷尽 `when`（新增枚举未配实现即编译失败） |
| P5 | `caa3ece` | 设置界面协议四选一（FilterChip）+ 按协议变化的 Base URL 提示 + 调试注入器 `protocol` 参数 + 仪器测试 |

**验收（当前证据）**：全模块 **1419 单测 0 失败**；`localFirst` + `strictOffline`（含 androidTest）编译通过；设备仪器测试 **10/10**（`CapabilityScreenInstrumentedTest` 5 项含新增的协议选择/保存，`DualReviewEntryInstrumentedTest` 3 项，`KnowledgeReviewSessionInstrumentedTest` 2 项）。

**形状取证**（实现时逐条核对官方源，来源写在各实现类注释里）：

- Anthropic：`anthropics/anthropic-sdk-python`（`message_create_params.py`、`message.py`、`tool_param.py`、流式事件与 `text_delta.py`、`base64_image_source_param.py`）。
- OpenAI Responses：`openai/openai-python`（README、`response_output_text.py`、`response_input_text_param.py`、`response_input_image_param.py`、`response_text_delta_event.py`）。
- Gemini：`googleapis/googleapis` 的 `generative_service.proto` 与 `content.proto`（端点、`GenerateContentRequest`、`GenerationConfig.response_mime_type`、`GenerateContentResponse.candidates`、`Blob{mime_type,data}`）。

**未验证（需真实 provider）**：三家协议的真实端点端到端（需要 API key）。本环境用 MockWebServer + 合成响应验证了协议形状、解析与流式；真实连通性需按 §3.3 表格逐家实拨（`adb` 注入示例见 `TestSeedModelConfigReceiver` 的 KDoc，带 `--es protocol <wireId>`）。
