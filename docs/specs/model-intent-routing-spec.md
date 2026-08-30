# 大模型意图识别路由 Spec v2.2（五核心工具 + 工具环 + 可选联网附加）

状态：方案征集稿 v2.2（T6 全自动证据提交已批准；联网搜索移出核心、降为 BYOK key 附加功能）
日期：2026-08-30
v1 → v2 变更：按用户指示把工具集固定为 **6 个**；新增工具环协议、合法性/参数校验/多轮控制等新问题面；掌握度写工具与冻结合同的冲突给出调和方案。动机（用户原话）：①"用户提问之后，在回答前应该自己思考调用工具"；②"闲聊不需要调数据库，也不需要改写数据库权重"；③错题库写"需要学生明确命令，否则不能自己调用"；④未提及的问题由实现方自行探索补全。
v2 → v2.2 变更：T6 全自动证据提交语义获用户批准；**联网搜索移出核心工具集**（"把联网搜索功能去掉…先做一个附加功能保留，需要提供搜索API key才行"）——核心收敛为五个全本地工具，联网检索降为 BYOK key 附加能力。

## 0. 目标与非目标

目标：

1. **六工具授权矩阵**：模型在回答前可自主申请 6 个本地/受控工具（见 §2），其中两个写工具受"学生明确命令"门控。
2. **工具环协议**：单发网关升级为可多轮的工具调用循环，含循环上限、参数校验、幻觉与越权处理、结果回填。
3. **写路由**：工具环之外，意图决策继续驱动"证据资格"矩阵（v1 §3.3，保留）——闲聊/帮助零学习状态触碰。

非目标（硬边界不动）：模型直接计算并写入掌握度数值（见 §5 的调和方案）；改写学生原话；云端路由服务；向学生展示内部流水线状态（确认对话框除外——那是学生决策）。

## 1. 现状基线（v1 §2 结论不变，此处只列增量事实）

- 网关为单发 `chat.completions` + `response_format: json_object`，**无 tools 协议**（`OpenAiCompatibleModelGateway` / `OpenAiModelProtocol.kt:213`）。
- 意图决策（7 意图 + requestedLocalCapability 白名单 + lookupTerms + memoryPreference）随 TUTOR_LOBBY/TUTOR_RESPOND 输出产出，memoryPreference 已真消费。
- 写面事实：聊天路径今天只写会话内容/曝光/debrief advisory；权重只经确定性投影器（`LearningProjector`）。

## 2. 六工具授权矩阵

| # | 工具 | 类型 | 数据面 | 授权类 | 派遣预算 | flavor 可用性 |
|---|---|---|---|---|---|---|
| T2 | `knowledge_read` 读取知识库 | 读（本地） | 知识库 | 意图门控（当前题/检索类自动可用） | 本地免费 | 双 flavor |
| T3 | `notebook_read` 读取调用错题库 | 读（本地） | 错题库 | 意图门控（READ_MISTAKE_NOTEBOOK 或当前题上下文） | 本地免费 | 双 flavor |
| T4 | `notebook_write` 改写（增减）错题库 | **写**（本地） | 错题库 | **学生明确命令**：模型环内只能"申请"，两段式——环暂停 → 学生确认 UI → 确定性执行器落库 | 本地免费 | 双 flavor |
| T5 | `mastery_read` 读取学生掌握情况 | 读（本地） | 掌握度投影 | 意图门控（LEARNING_PROGRESS/当前题） | 本地免费 | 双 flavor |
| T6 | `mastery_update` 学习证据提交（自动改写掌握情况，见 §5，已批准） | **写**（本地，全自动） | 掌握度投影 | **无学生确认**：模型自动调用；提交的是证据事件而非数值，由确定性投影器自动落库 | 本地免费 | 双 flavor |
| 附加 | `web_search`（联网搜索 + URL 直拉，可选附加功能） | 读（外部） | 互联网 | **仅在学生配置 BYOK 搜索 API key 后存在**；一次性能力开启同意；strictOffline 无此工具 | 占真实派遣 | 仅 localFirst + 已配 key |

设计原则：**核心五工具全本地**（T2–T6）；**读工具模型自主调用；数值永远由确定性投影器计算**。T4 需学生明确命令；T6 全自动但只写"证据事件"，模型永不直接给掌握度数值（§5）。联网搜索是**附加功能**：未配置搜索 key 的学生，该工具在环内不存在（非报错，是声明集合里就没有）。

## 3. 工具环协议（核心读工具 T2/T3/T5 的多轮循环；附加 web_search 见 §3.4）

wire 采用 OpenAI 工具协议（已对 openai-python 源码核验）：请求 `tools=[{type:"function", function:{name, description, parameters:JSONSchema}}]`；模型 `assistant.tool_calls[{id, function:{name, arguments:JSON字符串}}]`；结果以 `{role:"tool", tool_call_id, content}` 回填。参数参考 langchain4j 的 `ToolSpecification / ToolExecutionRequest / ToolExecutionResultMessage` 三件套与本仓现有严格 JSON 校验风格。

### 3.1 循环控制（多轮问题）

- **上限**：`maxToolCallingRoundTrips = 2`（沿用 langchain4j 的 round-trip 概念）——即一次逻辑操作 = 首轮派遣 + 至多 1 轮工具回填 + 终答派遣 ≤ 3 次真实派遣，与冻结合同三次预算严格对齐；本地工具执行不占预算（合同原文）。
- **上下文膨胀**：每轮回填结果设字符上限（单工具 ≤ 2k 字符摘要、总计 ≤ 4k，常量入契约）；超限截断并在结果中注明 `truncated`。
- **循环/重复检测**：同参数工具调用重复申请 → 直接返回缓存结果并提示模型停止申请；第 2 轮结束后强制 `tool_choice: "none"` 收口作答。
- **取消**：任一环节学生取消 → 环终止，已产生的派遣按既有出站清单语义记账（可恢复需显式"继续"）。

### 3.2 调用合法性（授权矩阵执行点）

- **合法集** = f(意图, flavor, Provider 能力)。执行器在环内逐调用校验：工具是否存在、当前意图/flavor 是否允许、T6 是否满足意图与配额。
- **幻觉工具**（模型调不存在的工具）：显式返回错误型 tool 结果（`unknown_tool`），模型可自纠；连续两次幻觉 → 终止环、单段作答（对齐 langchain4j 的 ToolHallucinationStrategy 思路）。
- **越权申请**（如闲聊意图申请 T3/T5/T6，或环内申请 T4）：返回 `tool_not_authorized` 结果 + 记 `TUTOR_INTENT_BOUNDARY_VIOLATION` 类审计；T4 的申请一律转为"确认请求"而非执行（§2）。
- **意图-工具映射**：CURRENT_QUESTION_HELP → T2/T3/T5（+附加 web_search，如已配 key）；MISTAKE_NOTEBOOK_LOOKUP → T3(+T5 摘要)；LEARNING_PROGRESS_LOOKUP → T5；APP_HELP/CASUAL/AMBIGUOUS → 无工具。

### 3.3 参数校验

- 每工具一份 JSON Schema（入契约注册表，与 promptPolicyVersion 一起 parity 锁定）：类型、必填、枚举、长度/数量上限、terms 白名单来源（仅学生原话词元，延续 lookupTerms"不得臆测"）。
- 校验失败：包装成错误型 tool 结果回给模型（可自纠重试，消耗轮次）——不抛穿、不中断会话（langchain4j 的 wrapToolArgumentsExceptions 模式）。
- 注入面：字符串参数过滤控制字符/超长/非白名单字段；只读工具天然幂等，读参数不需确认。

### 3.4 环外新问题（自探索补全）

- **提示注入**（附加 web_search/T4 特有）：外部/模型产生的内容是**不可信输入**——回填一律包在带来源标注的"不可信数据"块内，系统指令永不从中取值。
- **T1 本地化实证（2026-08-30）**：纯零出网的"网页搜索"不成立（索引在远端）；无 key 爬搜索引擎结果页不可靠（DuckDuckGo HTML 端点实测返回 CAPTCHA，已否决）。可行的本地优先三件套：
  1. **`reference_lookup`**：keyless 直连白名单结构化源（已实证：Wikipedia/Wiktionary REST API 无需任何 key，返回标题/摘要/链接的结构化 JSON，明确允许程序化访问；白名单后续可扩开放教育资料源）。出网内容 = 查询词（学生原话派生）。
  2. **`url_fetch`**：学生显式粘贴 URL 时才拉取正文（本地 readability 抽取），非学生指令不主动拉。
  3. **`local_corpus_search`**：真正零出网的检索——本地教材语料/导入文本/讲解历史的本地索引（FTS 管线复用）。"搜索"的离线替代物是**本地语料**，不是远端索引。
- **静默 UX**：工具执行不显示流水线（冻结合同）；仅"正在准备这道题"态；确认对话框（仅 T4）是学生决策，不属于流水线展示。
- **失败语义**：本地工具异常 → 错误型 tool 结果（模型可换路）；连续失败 → 降级单段作答。
- **审计**：每次调用记入 model_task 快照（工具名/参数哈希/结果大小/耗时/授权依据/确认 id）——预算核查、标定与事后追责统一数据源。
- **成本**：附加 web_search 的搜索 API 为 BYOK 付费项；核心五工具本地零边际成本。附加工具使用频次入披露同意文案。
- **幂等**：写工具以确认 id 为幂等键，重复确认/恢复重放不重复落库。

## 4. 读工具实现映射（全部复用既有链路）

- T2 → `readSubjectKnowledgeRecallCandidates` + `KnowledgeContextRetriever.select`（摘要化输出）。
- T3 → 错题本目录/FTS 查询（`libraryQueryDao`/FTS 管线，只读）。
- T5 → 掌握度投影只读面（`learner_*_state` / 观察快照，含 KC/记忆状态摘要）。
- T4 → `CreateProblemDraft/revise/replace` 命令链（学生确认后执行；增=新建草稿链路，减=状态变更链路）。
- T6 → 见 §5。

## 5. T6 `mastery_update`：全自动学习证据提交（用户已定：无学生确认，一切自动）

用户决策（2026-08-30）：T6 **不加学生批准，一切自动**。落点不是"模型直接改数据库"，而是**模型提交证据事件，确定性投影器自动落库**——全自动与信任边界同时成立，因为权重数值永远由公式产生。

### 5.1 三个"如何"的机制回答（全部复用既有链路）

**① 如何往掌握库加新东西？**
既有通道：`importKnowledgeBase` / `applyReviewedKnowledgePack`（知识包，契约校验：`KnowledgeBaseImportContract`）、`ensurePseudoKnowledgeBinding`（伪 KC 兜底，自动建节点+绑定）。T6 的自动写走同一族契约：模型提交"知识包提案"（新节点/新绑定的 seed 记录），`KnowledgeBaseImportContract` 等契约自动校验（taxonomyVersion、basisRevision、来源、大小上限）后落库——**模型产出的是符合 seed 契约的结构化记录，不是自由写入**。

**② 如何改写权重？**
权重数值今天就是由 `LearningProjector` 从**证据事件**（`LearningEvidence`：direction ±、weight、reason）按 FSRS-6/EMA 公式重算并 commit 的——没有任何"直接改数值"的通道。T6 做的事 = 把这条既有证据链路对模型开放为工具：模型提交 `{knowledgeNodeId, direction, reasonMarkdown, sourceId=本次会话引用, evidenceId?}`，weight 由系统按意图与来源赋**上限封顶的常量**（对齐 SELF_REPORTED 权重量级），投影器确定性整合。**模型给的是"发生了什么"，公式给的是"权重是多少"**。

**③ 如何在掌握库与知识库、错题本库之间串联？**
结构已存在，T6 写的是"绑定 + 证据"，联动由表结构自动成立：
- `practice_unit_knowledge_binding`（错题 practice_unit ↔ knowledge_node，含 strength/taxonomyVersion/basisRevisionId）——错题↔知识点；
- classification binding（题目↔章节/知识点标签）；
- v39 lattice 视图 `observeKnowledgeQuestionLattice`（知识节点 × 错题 × 掌握度联合读面）。
T6 可创建/强化绑定（走 binding seed 契约）+ 提交节点级证据 → 绑定表把三库连成 lattice，T5 读工具与现有 lattice 读面即能看到联动结果。

### 5.2 全自动前提下的安全边界

- **数值不可由模型给**：masteryProbability/stabilityDays/difficulty 一律公式产生；契约测试锁"模型输出字段里不存在数值型掌握度"。
- **weight 封顶**：证据权重 ≤ 常量上限（对齐自评权重量级），防单条会话证据过度移动权重。
- **意图门控**：写路由矩阵（§3.3）——CASUAL/AMBIGUOUS/APP_HELP 意图下 T6 不可用（无证据资格）。
- **频率配额**：每会话 T6 提交次数上限常量，防刷。
- **审计**：每次 T6 调用与落库结果记 model_task 快照；evidence source 标注模型会话来源（可追溯、可批量撤销的前提）。
- **冻结合同修正项 ★**："模型不能直接写掌握度"精确化为"**模型不得写入掌握度数值或直接修改投影行；可提交受契约约束的证据事件与绑定提案，由投影器确定性整合**"。

## 6. 测试与验收

- 契约：六工具 schema parity、预算记账（3 派遣含环）、退化路径、披露集合。
- 负向：幻觉工具/越权申请 → 零执行 + 审计记录；CASUAL/AMBIGUOUS 会话后学习状态全库不变（含 T6 被拒）；T4 无学生命令不落库；参数注入拒绝。
- 正向：READ 意图 → 工具回填 → 回答引用结果；T6 证据提交 → 投影器确定性落库且幂等、数值由公式产生；T4 确认后落库且幂等。
- 仪器：Lobby 全流程（闲聊零写入 / 检索引用 / T4 写确认两段式 / T6 自动证据落库）。

## 7. 分期（修订）

- **A**：写路由矩阵（§3.3）+ 读工具环 T2/T3/T5（能力探测 + 退化 + 预算记账 + 审计）。
- **B**：T6 `mastery_update` 全自动证据提交（证据/绑定 seed 契约 + weight 封顶 + 配额 + 投影器整合）+ T4 两段式（确认 UI + 确定性执行器 + 幂等）。
- **C（可选附加）**：联网搜索——BYOK 搜索 API key 接入（Provider 白名单）+ 注入加固 + 出网披露 + 能力开关 UI；`local_corpus_search`（零出网本地语料检索）可独立先行，不属于联网功能。
- **D**：TUTOR_EVALUATE / REVIEW_RERANK / PROBLEM_RELATE 启用（独立排期）。

## 8. 批准状态

1. **§2 授权矩阵整体 + §5 T6 全自动证据提交语义：已批准（2026-08-30，用户确认）**。补充的时机语义（§5.3）：模型负责"适时"调用——环内实时判断 + 会话收尾兜底槽。
2. §4 披露扩展（附加 web_search 出网 = 查询词/URL；核心工具回填本地 Provider 不涉出网但入披露记录）+ promptPolicyVersion bump。
3. §3.1 循环上限取值（2 轮回填）。
4. §3.4 附加 web_search 的 Provider 白名单与 key 形态（学生自选搜索服务 + BYOK key；Wikipedia/Wiktionary 等 keyless 源可作为免配置默认，已实证）。

## 9. v2.3 增补（2026-08-30 grill-me 轮四项决策）

### 9.1 T6 落库：新会话证据表 `learner_chat_evidence`

`review_log.cardId` 语义是"挂在题上的复习证据"，自由文本聊天没有对应题——不放宽旧表。新建表（投影器第二读源）：

```
learner_chat_evidence(
  evidence_id PK, learner_id, conversation_id,
  knowledge_node_id,          -- 目标知识节点
  direction,                  -- POSITIVE / NEGATIVE
  weight,                     -- 本地计算，非模型输入（见 9.2）
  reason_markdown,            -- 模型给出的判定依据（必须引用会话具体内容）
  source_kind = 'MODEL_CHAT', -- 审计与批量撤销的锚点
  confidence,                 -- 模型自报意图置信度
  created_at_epoch_millis
)
```

投影器把它作为与 review_log 平级的证据读源，同一套公式整合。

### 9.2 T6 证据类型：双向收 + 模型判方向 + 本地校验平衡（用户选 1+3）

- **模型判 direction**（POSITIVE/NEGATIVE），但必须给出锚定理由（引用学生原话/作答行为），不许空判。
- **本地校验层交叉核对**：同一会话内的客观信号（作答对错、响应时长、滚动/中断信号、历史证据）与模型判断冲突时——本地降权或拒绝（例：学生说"懂了"但同会话作答错误 → 正向证据降为观察级或拒收）。
- **差异化权重**：NEGATIVE → 标准自报档；POSITIVE → 低权重封顶档（自报过自信，Dunlosky & Rawson 2012 已入研究依据）。两档常量入契约，待标定。

### 9.3 难度评判的客观性与稳定性（用户硬要求："不能换一道题就把简单的说成难的"）

**双轨难度，客观轨为主：**

- **客观轨（权威）**：`problem_memory_state.difficulty`——投影器从真实作答按 FSRS 公式算出的题目难度。有真实作答数据的题，模型的说法**不参与**难度结论，只作为解释素材。
- **模型轨（冷启动先验）**：无作答数据的新题，模型可提交难度判断——但必须走**锚定评分细则**而非自由印象：固定维度（步骤数、概念数量、计算量、前置知识依赖数）逐项打分 → 映射到难度档。输出标 `provisional`。
- **收敛规则**：真实作答累积后，客观轨接管；provisional 判断与后续客观值做**漂移对照**（模型判"难"实际客观"易"的比率）——漂移率进标定报告，反哺细则。
- **稳定性保障**：同一题重复判定应同档（一致性测试）；细则锚定 + 维度化打分天然抗"换题漂移"。

### 9.4 意图不确定性的量化（宁漏记偏置 + 模型自评置信）

- 模型输出的 `confidence`（0–1）语义锚定进 prompt：**"confidence = 消息文本明确指向该意图的文本证据程度，基于文本而非猜测"**。
- 本地量化阈值（契约常量，待标定）：`confidence ≥ θ_evidence`（初值 0.7）→ 该意图下证据资格生效；`θ_evidence ≤ c < θ_route` → 正常回答但零证据；`c < θ_route` → 按 AMBIGUOUS 处理。
- 偏置：**宁漏记**——不确定即零证据，权重纯净优先；漏记的辅导缺口由工具环主动问询缓解（模型可反问澄清，新消息重新判定）。

### 9.5 工具环范围扩为全任务（用户决策：全部任务开环）

- 所有 `ModelTaskKind` 的派遣均可带工具声明；逐任务重新评合同（每任务需要：工具子集声明、预算位、披露集合核对、退化路径）。
- 首批接线顺序仍为 TUTOR_LOBBY/TUTOR_RESPOND（对话最需要），CAPTURE_PARSE/PROBLEM_CLASSIFY/TUTOR_PLAN 等在阶段 A2 逐个补合同；结构性工作（执行器、记账、审计）一次做全。

### 9.6 遗留登记

- KD-4（known-defects.md）：visual-ui 复杂电路测试在 CI 软渲染模拟器 `ComposeTimeoutException after 2000ms`（本地 6/6 绿）——空闲同步超时类环境敏感，与 KD-2 同族（本地/CI 时序差异），待专项。
