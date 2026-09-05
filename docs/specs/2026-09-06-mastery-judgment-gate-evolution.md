# 掌握证据判断门控演进（档2/档3）：从"本地行为佐证"到"规范判断 + 延迟复核"待办 spec

状态：待办 spec（未实现，供后续排期）
日期：2026-09-06
关联：`docs/research/llm-mastery-judgment-regulation.md`（实证底稿）；`docs/research/tutor-evidence-gate-research.md`（写侧门控参数）；`docs/specs/2026-09-02-tool-loop-wiring-design.md` §3.5（T6 已落地形态）

## 0. 背景与已落地基线

用户方向（2026-09-05）：掌握度不该通过选择题/本地行为信号判断，掌握度由模型判断；且本地无法在语义上佐证模型的判断，正解是**规范模型如何判断**。

深研结论（llm-mastery-judgment-regulation.md）：
- 本地无可靠语义信号可佐证"学生懂了"（选择题对错≠理解，performance vs learning）。
- MASTERED 判断可信度来自：先引证据 → checklist 判定 → 防谄媚 → 置信降权 → **跨次/延迟复核**。
- MASTERED 永不单次判定；跨会话只存行为证据、绝不把"他说会了"存成记忆（Bensal 2026）。

**已落地（档1，本 spec 的基线）**：重写 MASTERY_UPDATE 的 prompt 判断规范（证据先行/防谄媚/可观察 rubric）。零 gate/runner 改动。见 `OpenAiModelTaskAdapters.kt` T6 声明块 + `ModelEgress.kt` TUTOR_RESPOND v7。

**现状缺口**：gate 的 `MASTERED_WITHOUT_BEHAVIORAL_SUPPORT` 门（`hasBehavioralSupport` 恒 false → 模型判 MASTERED 必拒）仍建立在"找本地行为信号佐证"的伪路径上——与档1"规范判断"方向冲突。档2/档3 解决这个冲突。

## 1. 档2：MASTERED 证据校验从"本地信号"改为"模型证据锚"

### 问题
`MasteryWriteGate.GateInput.hasBehavioralSupport` 是布尔，runner 恒传 false（本地无法可靠判定），导致 MASTERED 档**永远被拒**。这不是安全，是"判了也白判"——模型的 MASTERED 判断永远不会进入掌握度，即使它按档1规范给出了充分证据。

### 目标
把 gate 的 MASTERED 门从"本地无法满足的 hasBehavioralSupport"改为"校验模型 rationale 里是否带出充分的可核查证据锚"。判断可信度由档1 prompt 规范承担；gate 只做机械校验。

### 设计草案
1. **模型输出扩展**（model 层 `TutorToolCall` / wire）：MASTERY_UPDATE 增可选 `evidence` 字段——模型列出的判定依据（逐字引用的学生原话/可观察行为，≥1 条），或复用现有 `rationale` 并要求其按档1格式逐字引用。
2. **gate 改判**：`hasBehavioralSupport` 语义改为"模型是否提供了 ≥N 条逐字引用证据"（evidence anchor present），不再依赖本地信号。`MasteryWriteGate.GateInput` 换字段或改校验。
3. **runner 传参**：把模型 `rationale`/`evidence` 的引用充分度（引用条数/是否逐字）算出来传 gate，替代恒 false。
4. **档位边界**：MASTERED 与 CONFIDENT 的区分 = 证据充分度 + 模型自述的可观察行为类型（独立迁移/解释/间隔），而非一个本地判不了的布尔。
5. **wire/解析**：Route B `toTutorToolRequests` + Route A `toTutorToolRequestsOutput` 同步解析新字段；契约校验（T6 带 direction/understanding 已有，加 evidence 锚可选或必选）。

### 风险与边界
- 模型输出的"证据"仍是模型自报，可能编造引用——机械校验只能查"有没有、够不够条数"，不能查真伪。这是模型判断的固有边界，档3 的延迟复核才是真校验。
- 不能因"规范更好"就放开单次权重（研究 Q6：规范提升的是少触发冷却/配额，非单次大步）。

## 2. 档3：MASTERED 永不单次判定——延迟复核通道

### 问题
即使档1/档2 让模型给出规范判断，单次对话里的"学生懂了"仍受即时工作记忆污染（Nelson & Dunlosky delayed-JOL）；跨会话记忆若存"他说会了"会被谄媚放大（Bensal 2026）。

### 目标
MASTERED 判断永不单次落库。模型判 MASTERED → 只先落 CONFIDENT（或 pending 观察），触发延迟复核：间隔 + 插入其他内容后，学生回来做等价但不同表面的迁移题，独立成功才升级 MASTERED。

### 设计草案
1. **状态**：加一个"待复核掌握声明"（pending mastery）存储——记录 KC、声明的证据锚、时间戳、题面指纹。或复用 rejected/观察通道扩展。
2. **复核触发**：模型判 MASTERED 时 → 不直接写 MASTERED 权重，落一个"pending"，并在讲解流里安排一次延迟迁移题（间隔若干轮/内容）。
3. **复核成功** → 才落 MASTERED 档权重（小步）；复核失败/超时 → pending 过期或降级。
4. **跨会话**：只存行为证据（做对什么题、用时），不存"他说会了"；每轮重新评估。
5. **冷却/配额**：pending 不耗正式配额；复核达标不触发冷却（研究 Q6）。

### 风险与边界
- 需要新的存储（pending 表/列）+ UI 复核流程 + 题面指纹防复用，工程最大。
- LLM 延迟复核的直接实证是空白（研究 UNVERIFIED），需产品实验校准间隔/次数。

## 3. 排期建议
- 档2 依赖档1（已落地）的 prompt 规范作为"证据锚"来源，是纯数据层 + model 层改动，可独立做，风险中。
- 档3 依赖档2 的证据锚 + 需要存储/UI，工程最大，建议排最后。
- 两者都受"是否改变当前 MASTERED 恒拒"的安全权衡约束——改动前须确认"宁漏记"的保守语义是否仍要保留为兜底（研究 Q6：达标不耗配额 ≠ 单次放开权重）。

## 4. 验收（未来实现时）
- 档2：模型按规范给充分证据时 MASTERED 可落（不再恒拒）；无证据时仍拒（兜底）；单测覆盖"有/无证据锚"。
- 档3：MASTERED 单次永不落；pending→复核→升级路径端到端；跨会话不延续"他说会了"。
- 回归：instrumented（T6 + ledger）+ prompt 规范测试保持绿。
