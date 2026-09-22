# ADR 0001：智能体页无场景矩阵——单一 5 工具面、单一本地门、单一代号通道

- **状态**：Accepted（2026-09-21 用户逐条裁定；台账 D5–D10，见 `docs/kb-architecture-refactor-decisions-2026-09-21.md`）
- **关联审计**：`.jez/artifacts/research-brief-kb-architecture-audit.md` §1.4（四条 KB 触点 / 三条断链）、§2.2（Khanmigo 复盘）、§2.3-1（id 通道工业模式）、§5-R2
- **前置 ADR**：无（本目录首篇）

## Context

知识库审计（2026-09-21 实测）发现：教学智能体对知识库的消费处于"场景矩阵"状态——每个入口有各自的工具面与注入方式，差异硬编码在代码里：

| 入口 | 现状 | 证据 |
|---|---|---|
| 讲题 Respond | 5 工具完整环（`KNOWLEDGE_READ` / `NOTEBOOK_READ` / `MASTERY_READ` / `NOTEBOOK_WRITE` / `MASTERY_UPDATE`） | `core/model/src/main/kotlin/com/tingyun/smartmistakebook/core/model/TutorToolLoop.kt:12-19`（本 ADR 读源核实） |
| 讲题 Plan | 只注入已确认绑定节点的教学参考，无工具环 | 审计 §1.4（`SavedMistakeTutorRoute.kt:100-126`、`OpenAiModelTaskAdapters.kt:234,312`） |
| 大厅 | 仅 `NOTEBOOK_READ`，从不查 KB | 审计 §1.4（`RoomModelTaskRepository.kt:749`） |
| 拍照讲题（产品主入口） | **零** KB 注入 | 审计 §1.4（`TutorModelTaskPolicy.kt:126-132`，审计一手核实） |

掌握度写口（MASTERY_UPDATE）在聊天路径**结构性不可达**：工具要求传知识点原始 id，但 prompt 里的教学参考只有 6 个字段（type/title/summary/applicability/content/boundary，无 id，`OpenAiModelTaskAdapters.kt:588-598`）、学习证据只给 label——模型没有任何渠道拿到合法 id。仪表化测试 `RoomModelTaskT6MasteryInstrumentedTest`（`:120,180-181`）还把"编造 term → `rejected:KNOWLEDGE_NODE_NOT_ANCHORED`"钉成了**期望行为**。"披露边界"（不给 id）与"写入契约"（要 id）两个设计互相矛盾，且矛盾被测试固化成了现状（审计 §4-P2）。

id 通道的工业模式已有现成解（审计 §2.3-1）：Anthropic 官方工程博客的 `response_format` detailed/concise 旋钮（detailed 响应带轮内短代号 + 映射表）；OpenAI 官方 function-calling 最佳实践两条——"Don't make the model fill arguments you already know"（系统已知的 id 由代码侧绑定）+ "Use enums to prevent invalid states"（Structured Outputs enum = 白名单约束解码）。

本 ADR 裁定的问题：**不为每个入口单独开写口、单独配工具面（那只是再造一个场景矩阵），而裁定一条所有入口共用的单一通道。**

## Decision

1. **一个 5 工具面（D7/D8）**。智能体页所有模型调用——Plan、Respond、大厅——拥有同一工具面：`TutorToolName` 的 5 个工具（`TutorToolLoop.kt:12-19`）。Plan 复用 Respond 的工具环（D8）；大厅拿到全工具面（D7），`MASTERY_READ` 无场景分支，其输出形态与轮预算按旧裁定不变（本 ADR 不重开）。

2. **写不写由模型语义判定，代码零场景分叉（D6）**。是否调用 MASTERY_UPDATE、以什么语义调用，完全由模型判定；系统提示词教会模型判定标准。代码不区分"这次调用来自哪个入口/场景"——不存在按场景分叉的写口代码。

3. **单一代号通道 K1..Kn（D5）**。原始知识点 id **永不进 prompt、永不进工具参数**。读侧（教学参考、检索候选菜单、读工具输出）只以本轮代号 `K1..Kn` 向模型披露知识点，每轮一次映射表随 prompt 下发；写侧 MASTERY_UPDATE 收代号参数，以本轮代号集做 enum 白名单校验（约束解码），非法代号在解码层即不可产生、更到不了门；代码把代号解析为原始 id 后才进门。当前题的已确认绑定由系统代码侧直接绑定（系统已知，不让模型填）。

4. **统一本地门（D9）**。所有入口共用同一个门，门不感知场景。数值常量沿用既有裁定（本 ADR 不改值）：
   - 引文锚底线：POSITIVE ≥ 1 条**已核实**引文锚、MASTERED ≥ 2 条（`MasteryWriteGate.REQUIRED_EVIDENCE_ANCHORS_FOR_POSITIVE` / `_FOR_MASTERED`，`core/domain/src/main/kotlin/com/tingyun/smartmistakebook/core/domain/MasteryWriteGate.kt:101,90`，读源核实）；
   - 证据置信度 ≥ 0.7（`MasteryWriteGate.kt:30`）；
   - 同 KC 冷却 12h（`MasteryWriteGate.kt:42`）；
   - 双配额：50/会话、100/learner 滚动窗（`MasteryWriteGate.kt:52,63`）。
   拒写照旧落 `learner_chat_evidence` 观察行（rejected ≠ 删除，既有 v43 语义，`LearnerChatEvidenceEntity.kt:42-48`）。

5. **唯一数值分支 = 降权安全垫（D9）**。写入代号的 `anchor_class` ≠ `CONFIRMED` 时，weight 减半（现有档位常数 × 0.5）。这是写口上**唯一**的数值分支；不存在其他按入口、按场景的权重/阈值分叉。

6. **`anchor_class` 记入 `learner_chat_evidence`（D10）**。表新增 `anchor_class` 列，三值，全部由系统自己机械确立（不依赖模型声称）：
   - `CONFIRMED`：代号为当前题已确认绑定的知识点（绑定在表内，代码侧绑定）；
   - `CANDIDATE`：代号作为检索候选注入过本轮 prompt（在候选菜单内，未确认绑定）；
   - `DISCLOSED`：代号仅出现在本轮读工具输出里（工具披露）。
   CANDIDATE 与 DISCLOSED 数值待遇相同（都吃安全垫）；二者区分只用于审计与后续校准。

## Consequences

正面：
- "编造 id"失败类被结构性消灭：模型没有产生原始 id 的渠道（非法空间为零），enum 白名单 + 代码侧绑定同时闭合"读工具不给 id / 写工具要 id"断链。
- P2 三条断链的聊天侧闭环打开：拍照讲题 Plan 获得材料注入（复用归类路径两段式，审计 R2-2）；大厅与讲题同一条掌握度证据路径；"AI 坐在内容旁边"（Khanmigo 复盘，审计 §2.2）变为"织进内容里"。
- 未来新增入口 = 接同一 5 工具面 + 同一门，零代码场景分叉；门常数（0.7 / 1 / 2 / 12h / 50 / 100）全部沿用既有裁定，本 ADR 不改。
- 每条证据行带 `anchor_class`，校准数据可区分"确认绑定 vs 未锚定会话证据各自推动了投影多少"，为阈值再校准（含向量预注册规则）留数据。

负面 / 代价：
- prompt 每轮多一张代号映射表（审计 §9-6：估算 <500 token/轮，**未实测**）。
- Room 迁移：`learner_chat_evidence` 新增列 → DB 版本号升级 + 单点 schema 导出（重构工作流铁律 8：导出后核对 `schemas/` 只有新增版本文件、无其他文件变化）。
- 新增模型输入字段（代号映射、工具 schema 的代号参数）→ 铁律 7 适用：抹平空载体 + 升 schema 指纹，必测 4 用例（旧行读取 / 新行写入 / 空载体 / 升级路径）。
- 模型在大厅 / Plan 里也能调写工具（此前不可达）：门压力上升，但冷却 / 配额 / 锚底线数值不变；非 CONFIRMED 证据只以半权重动投影——这是设计（安全垫），不是缺陷。
- 把"无合法 id 拒写"钉成期望的仪表化测试 `RoomModelTaskT6MasteryInstrumentedTest` 需按审计 R2 验收 (c) 翻转为真口径：白名单内代号写入成功并落投影；白名单外（编造）代号拒写。语义不变，口径变真。

后续（不在本 ADR）：
- 代号映射的 prompt 形态、enum schema 定义、迁移 SQL 由重构工作流 R2 施工（台账 D4 范围）。
- 门的具体判定提示词写法沿用"写不写由模型语义判定（系统提示词教会）"的既有切片纪律（D17）。
