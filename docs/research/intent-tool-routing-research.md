# 意图路由与工具环 · 深度研究笔记

日期：2026-08-30。服务对象：`docs/specs/model-intent-routing-spec.md`（v2.4）。
取证方式：GitHub 主源（gh api 原文抓取）优先；WebSearch 本环境降级为训练知识摘要，凡只来自该通道的学术结论一律标注**待验证**。

## 1. 工具环协议与循环控制（主源已核验）

### 1.1 OpenAI 工具 wire 协议（主源：openai/openai-python）

- 工具声明：`tools=[{type:"function", function:{name, description, parameters:JSONSchema, strict?}}]`。
  - `strict: Optional[bool]`（`src/openai/types/shared/function_definition.py:36`）——开启后模型参数严格遵循 JSON Schema。
  - 严格模式约束：全字段 `required` + `additionalProperties:false` + 不支持可选字段省略（可选用 nullable 联合类型表达）。
- 申请：`assistant.tool_calls=[{id, type:"function", function:{name, arguments:JSON字符串}}]`——**一次响应可含多个并行 tool_calls**；有副作用/顺序依赖的工具应关闭并行（`parallel_tool_calls:false`）。
- 回填：`{role:"tool", tool_call_id, content}`——结果必须用 `tool_call_id` 与申请配对。
- 工具定义计入上下文 token；strict schema 首次使用有预处理延迟（训练知识摘要，待官方文档复核）。

对本 spec 的映射：T2–T6 的参数 schema 用 strict 风格全字段表达（T4/T6 无可选字段歧义）；环内关闭并行申请（T4/T6 有序副作用，读工具本就轻量）。

### 1.2 langchain4j（主源：langchain4j/langchain4j）

- 三件套：`ToolSpecification`（name/description/parameters schema）/ `ToolExecutionRequest(id, name, arguments)` / `ToolExecutionResultMessage`。
- 循环上限：`maxToolCallingRoundTrips`（`ToolService.java:374`，原名 maxSequentialToolsInvocations）——round-trip 计数与本项目"3 次派遣"预算同构。
- **幻觉策略**：`toolHallucinationStrategy = Function<ToolExecutionRequest, ToolExecutionResultMessage>`（`ToolService.java:102`）——模型调用不存在的工具时不崩溃，按策略生成错误型结果消息让模型自纠。
- **参数异常包装**：`wrapToolArgumentsExceptions(true)`——参数解析失败包装为工具结果而非中断会话。
- **补偿动作**：`@CompensateFor("toolName")`（`ToolService.java` findCompensatingActions）——为指定工具登记补偿执行器（回滚/冲正）。映射：T4/T6 写工具必须配套补偿（撤销通道，对应 spec §5.2 的可追溯批量撤销）。
- 错误传播双模：`propagateToolExecutionExceptions(true/false)`——向上抛 vs 作为结果消息继续环。

### 1.3 spring-ai（主源：spring-projects/spring-ai）

- `DefaultToolCallingManager`（`ToolCallingManager` 实现）：解析申请 → 按 name 经 `ToolCallbackResolver` 解析执行器 → 执行 → 产出 `ToolExecutionResultMessage`。
- **`ToolCallLimits`**（限额原语，`ToolCallLimits.java:37`）：
  - `defaultMaxCallsPerTool`（默认 40）+ `maxCallsPerTool: Map<String,Int>`（按工具覆写）+ `toolsExcludedFromLimit: Set<String>`（豁免集）+ `maxTotalToolCalls`（默认 150）。
  - **`ToolCallLimitBehavior`**：`THROW` / `RETURN_ERROR_RESPONSE`（`ToolCallLimitBehavior.java:26`）——越限后是抛异常还是返回错误响应，可配置。
- `ToolExecutionExceptionProcessor`：工具执行异常 → 转错误结果消息的处理器（默认不向上抛）。

对本 spec 的映射：T6 配额直接采用 per-tool 限额 + breach 行为模式（mastery_update 设低限、breach=RETURN_ERROR_RESPONSE）；读工具豁免限额或高限。

### 1.4 其他扫描（未深读，方向记录）

- JetBrains Koog（JVM agent 框架）、MCP（工具协议标准）：本仓工具为进程内本地执行，MCP 的传输/发现层无必要，仅其 schema 规范可参照——**方向记录，未取证**。

## 2. 工具安全（主源 + 训练知识混合，安全项从严）

- 结果回填 = 不可信输入：一律带来源标注的引用块；系统指令永不从工具结果取值（web/模型内容尤其）。**本条为安全基线，不依赖单一来源**。
- 参数注入面：字符串参数过滤控制字符/超长；schema 严格模式天然收敛形态。
- 副作用工具（T4/T6）：关并行、两段式确认（T4）、幂等键（确认 id / requestId）。
- 未验证项：Compose/移动端 on-device 工具环的时序模式无成熟公开先例——实现时以本项目仪器测试为准。

## 3. 学习证据与难度评判的学术框架（**训练知识摘要，全部标注待验证**）

- **IRT（项目反应理论）**：1PL/2PL/3PL 从作答数据估计题目难度 b、区分度 a、猜测参数 c——"难度是题目参数"这一客观化思想的主框架；冷启动（新题无作答）是经典难题。
- **冷启动方案**（文献方向）：从题面文本预测难度（BERT 类嵌入）、层级贝叶斯先验、相似题迁移。
- **Elo 动态难度**（ASSISTments 数据上的应用方向）：把难度当可更新评分——与本项目"投影器随证据更新"的形态天然同构。
- **Deep-IRT**：深度知识追踪与 IRT 结合，产出可解释的学生能力/题目难度。
- **ECD（Evidence-Centered Design，证据中心设计）**：把"可观察证据 → 潜在知识主张"形式化——正是 T6"证据事件 → 投影器 → 掌握度"的学术表述；审阅 venue：AIED / EDM / LAK。
- **会话式知识追踪**（对话证据建模）：较新方向，文献尚薄——本 spec 的 learner_chat_evidence 表属于该方向的工程落地；权重封顶 + 本地校验是对"自报不可信"的对冲。

⚠️ 以上学术条目来自模型训练知识（本环境 WebSearch 降级），**未经 2025–2026 文献核验**；落地前按 venue 检索核实。工程设计不依赖这些条目成立，它们只是命名既有思想的参照。

## 4. 结论映射表

| spec 章节 | 依据源 | 模式 |
|---|---|---|
| §3.1 循环控制 | langchain4j maxToolCallingRoundTrips；spring-ai ToolCallLimits+Behavior | 主源 |
| §3.2 合法集/幻觉 | langchain4j toolHallucinationStrategy | 主源 |
| §3.3 参数校验 | OpenAI strict schema；langchain4j wrapToolArgumentsExceptions | 主源 |
| §3.4 T1 本地化 | 实测：DDG HTML=CAPTCHA 否决；Wikipedia REST keyless 可用 | 实测 |
| §5 T6 证据提交 | ECD/会话式 KT（方向参照，待验证）+ 本项目投影器机制 | 混合 |
| §5.2 补偿/撤销 | langchain4j @CompensateFor | 主源 |
| §9.3 双轨难度 | IRT/Elo/Deep-IRT 冷启动（方向参照，待验证）+ 本项目 FSRS difficulty | 混合 |
