# 掌握证据判断门控演进（档2/档3）：从"本地行为佐证"到"规范判断 + 延迟复核"

状态：**档2 已实现（2026-09-10）**；**§1.2 客观作答交叉核对已实现（2026-09-11）**；档3 待办 spec（未实现，供后续排期）
日期：2026-09-06（§1 落地与实现注记 2026-09-10；§1.2 2026-09-11）
关联：`docs/research/llm-mastery-judgment-regulation.md`（实证底稿）；`docs/research/tutor-evidence-gate-research.md`（写侧门控参数）；`docs/specs/2026-09-02-tool-loop-wiring-design.md` §3.5（T6 已落地形态）

## 0. 背景与已落地基线

用户方向（2026-09-05）：掌握度不该通过选择题/本地行为信号判断，掌握度由模型判断；且本地无法在语义上佐证模型的判断，正解是**规范模型如何判断**。

深研结论（llm-mastery-judgment-regulation.md）：
- 本地无可靠语义信号可佐证"学生懂了"（选择题对错≠理解，performance vs learning）。
- MASTERED 判断可信度来自：先引证据 → checklist 判定 → 防谄媚 → 置信降权 → **跨次/延迟复核**。
- MASTERED 永不单次判定；跨会话只存行为证据、绝不把"他说会了"存成记忆（Bensal 2026）。

**已落地（档1，本 spec 的基线）**：重写 MASTERY_UPDATE 的 prompt 判断规范（证据先行/防谄媚/可观察 rubric）。零 gate/runner 改动。见 `OpenAiModelTaskAdapters.kt` T6 声明块 + `ModelEgress.kt` TUTOR_RESPOND v7。

**现状缺口（2026-09-10 已修）**：gate 的 `MASTERED_WITHOUT_BEHAVIORAL_SUPPORT` 门（`hasBehavioralSupport` 恒 false → 模型判 MASTERED 必拒）曾建立在"找本地行为信号佐证"的伪路径上——与档1"规范判断"方向冲突。**档2 已按 §1 落地**，下面的设计草案保留为决策记录，实测落点见 §1.1。

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

### §1.1 落地形态（2026-09-10 实现，已实证）

按 §1 设计草案第 1 条的**第二个选项**落地：**复用现有 `rationale`**，不新增 wire 字段——因此没有 schema 版本 bump、没有 Route A strict schema 变更、也没有旧持久化 tutor 行读回指纹风险。

| 层 | 落点 |
|---|---|
| domain `MasteryWriteGate` | `REQUIRES_BEHAVIORAL_SUPPORT_FOR_MASTERED` → **`REQUIRED_EVIDENCE_ANCHORS_FOR_MASTERED = 2`**（与档1 规范第 1 条"逐字引用≥2条"同值）+ `MIN_EVIDENCE_ANCHOR_CHARS = 4`（挡"懂了/会了"这类空话凑条数） |
| domain `MasteryWriteGate` | 新增纯函数 `evidenceAnchorCount(rationale): Int`——数引号包住的片段（`"…"` / `“…”` / `「…」` / `『…』`），即"逐字引用"的机械形态 |
| domain `MasteryWriteGate.GateInput` | `hasBehavioralSupport: Boolean` → **双路字段**：`hasObjectiveSupport: Boolean`（本地客观作答，测验通道）+ `evidenceAnchorCount: Int`（模型逐字证据锚，讲题通道）。MASTERED 需两者之一；`RejectReason.MASTERED_WITHOUT_BEHAVIORAL_SUPPORT` → `MASTERED_WITHOUT_EVIDENCE_ANCHOR` |
| data `RoomTutorToolRunner.masteryUpdate` | `hasBehavioralSupport = false` 硬编码 → `hasObjectiveSupport = false, evidenceAnchorCount = MasteryWriteGate.evidenceAnchorCount(call.rationale)`（拉通"模型 rationale → 证据锚 → 门"这条链） |
| data `KnowledgeQuizFeedbackWriter` | `hasObjectiveSupport = verdict.hasBehavioralSupport, evidenceAnchorCount = 0`——客观作答通道本来就有一份本地可核查的证据，语义未变 |
| data `OpenAiModelTaskAdapters`（档1 prompt） | 判断规范第 1/2 条补明"用引号逐字引用"这一机械形态（否则模型按旧措辞写非引号叙述会被误拒）；`toolPurposeDescription(MASTERY_UPDATE)` 同步 |
| model `ModelPromptPolicyVersions` | `TUTOR_RESPOND` v7-mastery-judgment-norms → **v8-evidence-anchor-gate**（§1.2 再升至 v9-objective-cross-check） |

**有意不做**：单次权重未放开（`WEIGHT_MASTERED_POSITIVE` 仍 0.18）——§1 风险与边界已裁定"规范提升的是少触发冷却/配额，非单次大步"，本实现遵守。

**边界（与 §1 风险与边界一致）**：机械校验只查"有没有、够不够条数"，查不出模型是否编造引用。真伪由档3 的延迟复核承担，本层不假装能查。

**验收证据**：
- `MasteryWriteGateTest` 15/15（`:core:domain:test`）——含"两路之一即可判定/两路都缺即拒""引号片段计数""空话不算锚"。
- `RoomTutorToolRunnerTest` 5/5（`:core:data:testDebugUnitTest`，新增）——锁定 runner 侧接线：MASTERED + 2 条引号锚 → 落 0.18 档；无锚/仅"懂了" → `rejected:MASTERED_WITHOUT_EVIDENCE_ANCHOR` 且落观察行；CONFIDENT 与 NEGATIVE 不受该门影响。
- `:core:data:testDebugUnitTest` 354/354、`:core:data:compileDebugAndroidTestKotlin` 通过。

### §1.2 客观作答交叉核对（2026-09-11 实现，已实证）

档2 解决了"判定**有没有**佐证"，但留下一个反向缺口：**判定与本地客观记录相矛盾时怎么办**。

**问题**。学生在讲题会话里答错了模型自己出的检查题——这个对错由本地
`TutorAssessmentItem.evaluateChoice` 判定（学生所选 id == 模型声明的 `correctChoiceId`），
落 `tutor_turn_response.selection_was_correct`，是**行为证据**（不是模型自报）。但此前
`RoomTutorToolRunner` 从不回读它，模型可以在"学生刚答错"的对话上下文里判 POSITIVE 并
写进掌握度，口头声明就这样压过行为证据。研究 `tutor-evidence-gate-research.md` §3.2
的结论恰好相反：**冲突时行为证据胜出，口头声明降级为观察记录**（Koriat & Bjork 2005；
Nelson & Dunlosky）；§4 参数表也早已列出这一行（"交叉核对：MASTERED/POSITIVE 需同会话
客观作答佐证，否则降级/拒"）。

| 层 | 落点 |
|---|---|
| domain（新文件） | `TutorSessionObjectiveEvidence.kt`：`TutorSessionObjectiveRecord(answeredCount, correctCount)` + `contradictsPositiveClaim` 判定 + 纯函数 `tutorSessionObjectiveRecord(correctness)`。保证 `correctCount ∈ 0..answeredCount`，杜绝调用方各算各的 |
| domain `MasteryWriteGate` | `GateInput` 增 `objectiveAnswersContradictPositive: Boolean`；新增 `RejectReason.OBJECTIVE_ANSWER_CONTRADICTS_POSITIVE`。门的次序：语义矛盾 → **客观反驳** → 置信 → 锚定 → 证据锚 → 冷却 → 配额 → 注意力 |
| data `RoomTutorToolRunner` | 新增 `objectiveAnswersContradictPositive(context)`：经 `observeTutorTurnResponses(sessionId)` 回读本会话作答行，按 `cycleOrdinal` 收窄到**本轮**，交域层纯函数判定；`Context` 增 `cycleOrdinal`（来自 `TutorRespondInput.cycleOrdinal`） |
| data `RoomKnowledge…`→`RoomModelTaskRepository` | `toolContext` 传 `cycleOrdinal` |
| data `KnowledgeQuizFeedbackWriter` | 恒 `false`——该通道的语义**就是**客观作答，答错时 `direction` 已是 NEGATIVE，不存在"作答否定判断"这一冲突 |
| data `OpenAiModelTaskAdapters`（档1 prompt） | 增判断规范第 5 条：本会话学生答错过检查题时，本地会推翻 POSITIVE 判断，应判 NEGATIVE 或先重教 |
| model `ModelPromptPolicyVersions` | `TUTOR_RESPOND` v8-evidence-anchor-gate → **v9-objective-cross-check** |

**只挡 POSITIVE**：NEGATIVE 与"学生答错"方向一致，不是冲突；此时拒写会把真实的下滑信号
一起丢掉。

**只数本轮**：`restartCycle` 会在同一题上开新一轮重教，上一轮的答错正是重教的理由。把
历史轮次的答错永久计入，学生重教后答对也洗不掉，门就成了不可达的死门——与档2 修的
0.18 死常数同类。

**门优先于证据锚**：被行为证据推翻的判断不能靠"rationale 里多引用两个片段"复活，否则
证据锚路会变成绕过客观事实的后门（`theCrossCheckOutranksTheEvidenceAnchorRoute` 锁定）。

**有意不做（记录理由，非遗漏）**：没有把学生的检查题作答**写成**掌握度证据（方向 2 的
另一读法）。研究 §3.4 的独立性是硬前提——"同对话/同题变式/提示后作答不独立，不计入
多次门槛"。单次讲题会话内围绕同一道题的多个检查题作答彼此不独立，逐条写 POSITIVE 会让
掌握度质量被非独立证据灌水。客观作答**落成证据**的通道仍是知识点复习
（`KnowledgeQuizFeedbackWriter`）：那是一次独立的、间隔开的复习活动，其作答满足独立性。
本轮的落点是可本地判定、且**无法被刷**的那一半——证伪。

**验收证据**：
- `MasteryWriteGateTest` 19/19（`:core:domain:test`）——新增 4 例：被反驳的 POSITIVE 被拒、
  证据锚路不能复活它、NEGATIVE 不受影响、无客观作答时不算冲突。
- `TutorSessionObjectiveEvidenceTest` 4/4（`:core:domain:test`，新增）。
- `RoomTutorToolRunnerTest` 11/11（`:core:data:testDebugUnitTest`）——新增 6 例：答错即拒且
  落观察行、优先级高于证据锚、答对不拦、上一轮的错不拦本轮、NEGATIVE 不拦、无会话不核对。
- **变异验证**：把门里的该判断临时置为 `false &&`，`:core:domain:test` 与
  `:core:data:testDebugUnitTest` 各恰好 2 例转红（`positive is rejected when the session's
  objective answers contradict it`、`a contradicted positive is rejected even when the
  rationale carries enough anchors`；`aPositiveClaimIsRejectedWhenTheStudentJustMissedTheCheckQuestion`、
  `theCrossCheckOutranksTheEvidenceAnchorRoute`），其余用例保持绿——证明新测试承重，
  不是同义反复。
- 回归：`:core:domain:test` 366/366、`:core:data:testDebugUnitTest` 366/366、
  `:core:data:compileDebugAndroidTestKotlin` / `:feature:tutor:compileDebugKotlin` /
  `:feature:review:compileDebugKotlin` 全部 BUILD SUCCESSFUL。

**未验证**：本会话作答回读在真机 Room 上的整链端到端（需 instrumented/AVD）——单测用
`FakeStudyDatabasePort` 覆盖了"读到的行 → 门"这一段，未覆盖"Room 的
`observeTutorTurnResponses` 在同一事务视图下确实返回刚落的行"。**仍未实现**：档3
（延迟复核）——机械校验查不出模型是否编造引用，这一边界未变。

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
