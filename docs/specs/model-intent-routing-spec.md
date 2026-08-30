# 大模型意图识别路由 Spec（意图驱动工具环 + 写路由矩阵）

状态：方案征集稿（待用户批准合同变更项）
日期：2026-08-30
动机（用户原话归纳）：①"用户提问之后，在回答前应该自己思考调用工具"——模型自主决定何时查库；②"遇见闲聊，不需要调数据库，也不需要改写数据库权重"——写路由按意图门控。

## 0. 目标与非目标

目标：

1. **读侧工具环**：回答前模型可自主申请本地数据工具（知识召回、错题本检索、学习进度摘要），本地执行、结果回填后作答。模型成为路由器，取代"确定性预查或查不到"的现状。
2. **写路由矩阵**：按意图决定本次输出是否有资格产生学习状态写入。闲聊/帮助类意图零触碰（不进 review_log、不进掌握度证据、不产出 advisory、不改任何权重）；当前题求助类意图保持既有证据链路。
3. 优雅退化：不支持工具环的 Provider 单发直答（现状行为），意图决策照常随回答产出。

非目标（硬边界，不动）：

- 模型直接写掌握度/权重/投影状态——写永远只经确定性投影器（冻结合同：模型无权写掌握度）。
- 改写学生原话——学生消息进 prompt 恒为原话。
- 三次派遣预算、最小记忆、披露同意边界——全部维持，仅按 §4 记账。

## 1. 术语

- **工具环（tool loop）**：回答派遣内，模型输出工具调用申请 → 本地执行 → 结果回填 → 继续作答的循环。
- **本地读工具**：只读的本地数据访问面，参数受意图约束，结果有大小上限。
- **证据资格（evidence eligibility）**：本次模型输出产生的学习证据是否有资格进入确定性投影/长期记录。资格由写路由矩阵决定，投影照常确定性执行。
- **单段退化**：Provider 不支持工具环时，回退到现状单发 JSON（意图决策随回答产出）。

## 2. 现状盘点（2026-08-30 审查结论）

已存在：

- 12 种 `ModelTaskKind` + `ModelTaskContractRegistry`（promptPolicyVersion、披露集合、预算，parity test 锁定）。任务选择是确定性 UI 流程，模型不参与。
- 意图决策随回答产出：`TUTOR_LOBBY`（`TutorLobbyRoute`）与 `TUTOR_RESPOND` 输出 `intentDecision`：7 意图（CURRENT_QUESTION_HELP / MISTAKE_NOTEBOOK_LOOKUP / LEARNING_PROGRESS_LOOKUP / APP_HELP_OR_SETTINGS / CASUAL_CONVERSATION / END_OR_PAUSE / AMBIGUOUS）+ confidence + explicitActionRequest + memoryPreference + requestedLocalCapability + lookupTerms（0–6 词，仅限 READ 意图）。
- `requestedLocalCapability` 白名单硬约束：lobby 只允许 NONE/READ_MISTAKE_NOTEBOOK/READ_LEARNING_PROGRESS，越权 → `TUTOR_INTENT_BOUNDARY_VIOLATION`。
- `memoryPreference=BLOCK_LONG_TERM_WRITES_FOR_SESSION` **已真消费**：`TutorIntentAuthorityPolicy.authorize` → `blocksTutorLongTermWrites()` 挡长期写入。
- 知识召回（`readSubjectKnowledgeRecallCandidates` → `KnowledgeContextRetriever.select`）仅在错题确认/组织流程确定性触发。

缺失（本 spec 要补的三个洞）：

1. READ 意图只过滤内存快照展示，**回答前不查库、结果不喂回模型**。
2. **无工具环**：网关是单发 `chat.completions` + `response_format: json_object`，无 tools 协议。
3. **无写路由矩阵**：意图决策目前不约束"证据资格"——一个被打分为 CASUAL_CONVERSATION 的回答，其上下文仍走既有会话写入面（会话内容、曝光、debrief 资格），没有"闲聊零触碰"的显式门。

预留未接线：`TUTOR_EVALUATE` / `REVIEW_RERANK` / `PROBLEM_RELATE` 三个契约零调度点。

## 3. 设计

### 3.1 读侧工具环

- 工具（首期三个，全部只读、本地执行、免费不占派遣预算——冻结合同原文"本地执行不占该预算"）：
  - `knowledge_recall(terms: string[], limit)` → `readSubjectKnowledgeRecallCandidates`（知识节点+关系摘要）
  - `notebook_lookup(terms: string[], subject?, limit)` → 错题本目录检索（复用 library FTS/目录查询，只读）
  - `progress_summary()` → 学习进度摘要（只读投影读面）
- 循环上限：一次逻辑操作内**至多 2 轮工具申请 + 1 轮最终作答 = 3 次真实派遣**（预算不变）；第 3 轮后模型必须作答。
- 回填内容最小化：工具结果以结构化摘要回填（条目数、标题、id、匹配词命中），单工具结果上限字符数（复用 TutorRespondInput 量级），不整库倾倒。
- 派遣记账：工具环所有真实 dispatch 记入同一逻辑操作预算；超预算 → 截断工具声明，强制单段作答。

### 3.2 能力协商与退化

- `ProviderCapabilitySnapshot` 增 `supportsToolLoop`（探测：`ModelCapabilityTester` 发带 `tools` 的探测请求，收到 `tool_calls`/合法拒答即支持）。
- 不支持 → 单段退化：现状行为（intentDecision 随回答产出，lookupTerms 仍用于本地面板过滤）。工具环是增强，不是依赖。

### 3.3 写路由矩阵（意图 × 允许的写入面）

| intent | 会话内容存储 | 曝光/尝试证据 | review_log 证据资格 | advisory 资格（debrief） | 权重/投影 |
|---|---|---|---|---|---|
| CURRENT_QUESTION_HELP | ✓ | ✓ | ✓ | ✓ | 仅经投影器 |
| MISTAKE_NOTEBOOK_LOOKUP | ✓ | ✗ | ✗ | ✗ | 仅经投影器 |
| LEARNING_PROGRESS_LOOKUP | ✓ | ✗ | ✗ | ✗ | 仅经投影器 |
| APP_HELP_OR_SETTINGS | ✓ | ✗ | ✗ | ✗ | 不触碰 |
| CASUAL_CONVERSATION | ✓ | ✗ | ✗ | ✗ | 不触碰 |
| END_OR_PAUSE | ✓ | ✗ | ✗ | 会话收尾 debrief 资格 | 仅经投影器 |
| AMBIGUOUS | ✓ | ✗ | ✗ | ✗ | 不触碰 |

- 投影器与写入面**实现不动**；变化是入口处新增"证据资格"门：意图不符合的输出不得进入证据/记录通道（student 侧 `memoryPreference=BLOCK` 语义不变，且与学生偏好取**交**——任一方禁止即禁止）。
- confidence 门：低置信意图按保守档处理（视为 AMBIGUOUS，零证据资格），阈值常量化待数据标定。
- 闲聊零触碰的验证是硬测试（§6）。

### 3.4 工具环期间的安全语义

- 工具执行是本地读，无出网；**工具结果回填模型 = 新的出网内容**，仅在 egress manifest 披露集合覆盖"检索结果摘要"时允许（§4）。
- 工具参数（terms）只接受来自 studentMessage/会话的词（延续 lookupTerms"不得臆测"约束），长度与数量上限复用 lookupTerms 常量。

## 4. 合同与协议变更（需批准项加 ★）

1. ★ **披露集合扩展**：外部 Provider 工具环回填的检索结果摘要属于新披露面——`TUTOR_LOBBY/TUTOR_RESPOND` 的 manifest `disclosedData` 增加"本次会话工具检索结果摘要"条目；LOCAL_NO_EGRESS 不受影响。
2. ★ **promptPolicyVersion bump**：`TUTOR_LOBBY/TUTOR_RESPOND` 增加工具环变体（新版本号），旧版本契约保留用于不支持工具环的退化路径。
3. `ModelCapabilityTester` 增加 `supportsToolLoop` 探测项（非破坏，附加探测）。
4. 预算记账规则澄清：工具环多轮 dispatch 共享同一逻辑操作 3 次预算（与冻结合同一致，无变更）。

## 5. 数据模型

- `model_task` 快照增加工具环轮次记录（每轮 request/response 摘要、工具名/参数/结果大小/耗时）——审计与预算核查依据；无新表。
- 证据资格门不加字段：不合格输出本来就到不了证据通道（负向测试锁定）。

## 6. 测试与验收

- 契约测试：工具声明集合、预算记账（3 轮截断）、退化路径（无 tools 能力 → 单段）、披露集合 bump parity。
- 负向测试（KD 式硬门）：CASUAL/AMBIGUOUS 输出 + 任意后续动作 ⇒ 零 review_log/零证据/零 advisory/投影不变；READ 意图 + 工具结果回填 ⇒ 回答 prompt 含结果摘要且不超上限。
- 单元：工具参数过滤（臆测词拒绝）、结果截断、能力探测解析。
- 仪器：Lobby 真实流程——闲聊会话后断言学习状态全库不变；READ 会话后断言回答引用了检索结果。

## 7. 分期

- **阶段 A（纯本地，零新增派遣）**：写路由矩阵落地——意图→证据资格门接进会话/advisory/证据入口；低置信保守档。先做，因为它把用户最关心的"闲聊不改权重"关死，且不动网关。
- **阶段 B（网关）**：能力探测 + 工具环协议 + 三工具实现 + 预算记账 + 退化路径。
- **阶段 C（预留契约启用）**：TUTOR_EVALUATE（回答后自评，产出喂写路由复核）/ REVIEW_RERANK（复习重排）——与 A/B 解耦，独立排期。

## 8. 待用户批准项汇总

1. §4.1 披露集合扩展（外部 Provider 工具结果出网）。
2. §4.2 promptPolicyVersion bump（两个任务）。
3. §3.3 写路由矩阵本身（尤其 CURRENT_QUESTION_HELP 之外的意图全部零证据资格——比现状更收紧）。
