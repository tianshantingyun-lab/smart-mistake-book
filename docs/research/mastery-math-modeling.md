# 掌握度数学建模 与 用户行为→数据映射（详细版）

**配套**：《mastery-scheduling-research.md》（科学依据）、《three-store-linkage-design.md》（三库关联）、《mastery-scheduling-spec.md》（总 spec）
**约定**：所有公式给出符号定义、单位、值域、不变量与 Kotlin 落点；所有行为给出「UI/客观来源 → 转换规则 → 目标表.字段 → 当前是否已记录」。

---

## 第一部分：数学建模

### 0. 符号表

| 符号 | 含义 | 单位/值域 | 现有落点 |
|---|---|---|---|
| `t` | 距上次复习的经过时间 | 天（日历日差分，UTC） | ForgettingCurve.estimateAt 的 elapsedDays |
| `R` | 可提取性（回忆概率） | [0,1] | ForgettingCurve.retentionAt |
| `S` | 稳定性（R 从 1 降到 0.9 的天数） | 天，≥0.1 | learner_problem_memory_state.stabilityDays |
| `D` | 难度 | [1,10]（FSRS 口径；现库 0..1 需映射 `D_fsrs = 1 + 9·d_legacy`） | learner_problem_memory_state.difficulty |
| `G` | 评级 | 1=Again, 2=Hard, 3=Good, 4=Easy | review_log.rating（新增）；现三档自评映射见 §5 |
| `w[0..20]` | FSRS-6 参数 | 21 个浮点，默认见 §1.3 | 阶段 B 常量表；阶段 B6 可训练 |
| `r*` | 目标保留率 desired retention | [0.7,0.97]，默认 0.9 | 新增设置项 |
| `w_e` | 证据权重 | [0,1] | LearningEvidence.weight（现值表见 §4） |
| `Δt` | 事件发生时间差 | 毫秒/天 | attempt_event.occurred_at_epoch_millis |

**全局不变量**：
- I1 `R(t=0)=1`；`R 单调递减`；
- I2 `R(S,S)=0.9`（稳定性的定义点，幂律与指数实现都必须满足）；
- I3 成功复习后 `S' ≥ S`（SInc≥1）；失败后 `S' < S` 且 `S' ≥ S_min`；
- I4 `D ∈ [1,10]` 恒成立；
- I5 事件重放幂等：同 submissionId/exposureId 二次投影结果不变（现有 fingerprint 机制保持）。

---

### 1. 遗忘曲线（阶段 B1）

**现状**（ForgettingCurve.kt:52-54）：`R = 0.9^(t/S)`（指数，FSRS v3 形式）。

**目标（FSRS-6 幂律）**：
```
R(t,S) = (1 + FACTOR·t/S)^(−w20)
FACTOR = 0.9^(−1/w20) − 1          // 保证 R(S,S)=0.9
默认 w20 = 0.1542（可训练，范围 [0.1, 0.8]）
```
Kotlin 伪代码：
```kotlin
fun retentionAt(stabilityDays: Double, elapsedDays: Double, w20: Double = 0.1542): Double {
    if (elapsedDays <= 0) return 1.0
    val factor = 0.9.pow(-1.0 / w20) - 1.0
    return (1.0 + factor * elapsedDays / stabilityDays).pow(-w20).coerceIn(0.0, 1.0)
}
```
**迁移说明**：S 的语义（R 降到 0.9 的天数）不变，现有 stabilityDays 直接可用；指数→幂律后同 S 在大 t 下 R 更高（幂律尾部更平），复习队列会整体变稀——上线后用真实 log-loss 对照（目标 0.35–0.45）。

**反解间隔**（next review）：
```
I(r*, S) = (S / FACTOR) · (r*^(1/w20) − 1)
```
现钩子：`ForgettingCurve.reviewAtTargetRetention`（LearningProjector.kt:747 调用）——只换内部公式，签名不变。

---

### 2. 稳定性更新（阶段 B2，核心改造）

**现状**（LearningProjector.kt:736-743，固定乘法，与 R 无关）：
```
INDEPENDENT_RECALL: S' = S·(1+1.6·w) + 0.25·w
ASSISTED_RECALL:    S' = S·(1+0.6·w) + 0.1·w
RETRIEVAL_FAILURE:  S' = S·(0.7 − 0.25·w)
ANSWER_REVEALED:    S' = S·0.45
```

**目标（FSRS-6）**。先在复习时刻取 `R = retentionAt(S, t_since_last_review)`：

跨日成功（G∈{2,3,4}）：
```
S'ᵣ = S · (1 + e^{w8} · (11−D) · S^{−w9} · (e^{w10·(1−R)} − 1) · HardPenalty(G) · EasyBonus(G))
HardPenalty = w15 当 G=2 否则 1
EasyBonus   = w16 当 G=4 否则 1
```
跨日遗忘（G=1）：
```
S'f = w11 · D^{−w12} · ((S+1)^{w13} − 1) · e^{w14·(1−R)}
上界 clamp: S'f ≤ S · e^{−w17·w18}
```
同日重复（delta_t 日历日 = 0，FSRS-6 short-term）：
```
S' = S · e^{w17·(G−3+w18)} · S^{−w19}，且 G≥2 时 S'≥S
```
首次复习（无 prior）：`S = w[G−1]`，`D = w4 − e^{w5·(G−1)} + 1`。

**FSRS-6 默认参数**（py-fsrs scheduler.py/README 源码级核验，2026-08-29）：
```
w = [0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
     1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
     1.8729, 0.5425, 0.0912, 0.0658, 0.1542]
w20=0.1542 即 DECAY 默认；w7=3.0194 为难度均值回归系数；w17/w18/w19 为同日分支参数
```

**证据权重 w_e 与 G 的融合**：现体系每条证据带 weight（自评 0.25/0.35/0.5 等）。迁移策略——**weight 不进 S 公式，改为把带权证据映射为软评级**：
```
G_soft = 1 + 3·(0.5·w_positive + 0.5·retrievalSuccess)   // 例：独立答对 w=1 → G=4 区间；视觉满足 w=0.25 → G≈2.4
```
实现上对 FSRS 公式取 G 的连续形式（把公式中 (G−3) 等整型项用 G_soft 代入），或把连续 G 离散化到最近的 1..4（推荐后者，保持与训练分布一致）。**映射表（必须进 spec）**：

| 证据来源 | w_e | 映射 G |
|---|---|---|
| 独立答对（真实作答，首响应） | 1.0 | 4 (Easy 候选) 或 3 (Good)；高置信答对→4 |
| 提示后答对 | 0.6 | 3 (Good) |
| 看答案后答对 | 0.0 | 1 (Again) —— **禁止映射 Hard** |
| 自评「独立完成」 | 0.35 | 3 |
| 自评「勉强做对」 | 0.25 | 2 (Hard) |
| 自评「卡住」 | 0.5(负) | 1 (Again) |
| 视觉交互满足 | 0.25 | 2 |
| 视觉交互违反 | 0.5(负) | 1 |
| 独立答错 | 1.0(负) | 1 |
| 提示后答错 | 0.9(负) | 1 |
| 讲解暴露（现 weight=0.0） | — | 并入 Again 通道但 SInc 钳制 ≥1（见 §6） |

**Kotlin 落点**：`LearningProjector.projectMemory` 整段重写；`ProblemMemoryOutcome` 保留作为 reason 枚举，不再直接决定乘法分支。

---

### 3. 难度更新（阶段 B3）

**现状**：线性 ±0.03~0.12，clamp [0,1]（LearningProjector.kt:740-744）。

**目标**：
```
D' = w7·D0(4) + (1−w7)·(D − w6·(G−3)·(10−D)/9)
```
- `D0(4) = w4 − e^{w5·3} + 1`（回归锚点，防 ease-hell：无论滑多远都向「Good 初见难度」回归）；
- 线性阻尼 `(10−D)/9`：D 越高同样错误的推高越小；
- 现库 0..1 域映射：读入 `D = 1+9·d_legacy`，写回 `d_legacy = (D−1)/9`——迁移 v36 一次性转换。

---

### 4. 证据权重总表（现状值 + 迁移后通道）

| 来源 | 触发 | 现 weight | 现 outcome | 迁移后 G | 依据 |
|---|---|---|---|---|---|
| 真实作答·首响应·答对（无提示未揭示） | MasteryEvidencePolicy.kt:155-164 | 1.0 | INDEPENDENT_RECALL | 3 或 4（按 confidence） | 提取练习核心 |
| 真实作答·首响应·答错 | :122-131 | 1.0(负) | RETRIEVAL_FAILURE | 1 | 同上 |
| 提示后答对（hint 或 ordinal>1） | :133-153 | 0.6 | ASSISTED_RECALL | 3 | 提示降低提取难度→增益小 |
| 提示后答错 | :100-120 | 0.9(负) | RETRIEVAL_FAILURE | 1 | |
| 看答案后答错 | :78-87 | 0.6(负) | RETRIEVAL_FAILURE | 1（reveal 衰减合并，见 §6） | |
| 看答案后答对 | :89-98 | 0.0 | ANSWER_REVEALED | 1（无提取，不强化） | Bjork 双强度理论 |
| 自评 RECALL_COMPLETED | RoomBackedStudyExperienceRepository.kt:1463-1470 | 0.35 | ASSISTED_RECALL | 3 | FSRS Good |
| 自评 RECALLED_WITH_EFFORT | :1477-1485 | 0.25 | ASSISTED_RECALL | 2 | FSRS Hard |
| 自评 NEEDS_HELP | :1487-1495 | 0.5(负) | RETRIEVAL_FAILURE | 1 | FSRS Again |
| 视觉交互满足（decisive kinds） | :807-812 | 0.25 | ASSISTED_RECALL | 2 | |
| 视觉交互违反 | :813-818 | 0.5(负) | RETRIEVAL_FAILURE | 1 | |
| 讲解暴露 TutorAnswerExposureOutcome | LearningProjector.kt:700-713 | 0.0 + 0.45 衰减 | ANSWER_REVEALED | 1，且暴露本身不再单独衰减 | 合并双罚 |

---

### 5. 三档自评 → 四档映射与产品升级

- 过渡期映射（不改 UI）：独立完成→Good(3)、勉强做对→Hard(2)、卡住→Again(1)；「看答案后作答」一律 Again(1)。
- 目标期（UI 升级）：复习作答界面直接提供 Again/Hard/Good/Easy 四键（自评与作答合一），删除单独三档自评——这正是 FSRS 的采集形态；旧行为自评保留为详情页的元认知标注而非调度输入。

### 6. reveal 双罚合并（阶段 A2）

现状：ANSWER_REVEALED 把 S×0.45（LearningProjector.kt:738,1025）；之后同 presentation 的负作答再以 0.6 满强度计 RETRIEVAL_FAILURE；`applyRevealCausality`（:586-611）还把 POSITIVE 改写 NONE。双重计罚。
**目标**：同一 reveal 事件只施加一次衰减——
```
if reveal 在 attempt 之前（因果序）:
    attempt 证据照常（独立判定已被 ordinal/revealed_before 修正）
    reveal 自身不再衰减（被 attempt 吸收）
if reveal 之后无 attempt（纯看了答案）:
    S' = S·0.45 保留现状（等价于 Again+低 R 的近似）
```
实现：`LearningProjector.applyRevealCausality` 增加标记 `revealDecayAbsorbedByAttempt=true`，`projectMemory` 收到该标记时跳过 0.45 分支。

### 7. 讲解暴露（阶段 A2/G7）

- 暴露不再自带 0.45 衰减；映射为一次 G=1（Again）的 FSRS 更新，但由于学生没有主动提取，**SInc 强制 = 1 的特例**：`S' = S·max(1, S_f(G=1,R))` 不成立时直接 `S'=S`，仅刷新 lastReviewedAt 并记 reason=TUTOR_EXPOSURE。
- 效果：暴露重置复习时钟（不重复排期）但不惩罚不强化；与「看答案」区分（看答案=Again 真更新）。

### 8. 防刷冷却（阶段 A1）

```
cooldown(practiceUnitId, evidenceKind, occurredAt):
    若 同 practiceUnitId + 同 evidenceKind 的上一条证据 occurredAt 距今 < C(K)：
        该证据 weight *= 0（不落账本）或降级为 observation-only
C(K)：自评=6h；视觉=1h；真实作答不冷却（真实提取不刷分，天然受限）
```
落点：`RoomBackedStudyExperienceRepository.submitReviewSelfReport`（:1463 起）提交前查最近同 kind 证据；视觉在 `ingestVisualInteractionAttempt` 同理。**注意**：不落账本的证据仍写 review_log（采集数据与调度解耦）。

### 9. 题目→KC 证据分摊与聚合（联动三库，详见 linkage 文档）

单题证据向 KC 传播（多 KC 绑定，用 binding.strength）：
```
对 attempt 证据 e（weight w_e，方向 s∈{+1,−1}）：
  对每个绑定 KC_k：contribution_k = s · w_e · (strength_k / Σ_j strength_j)
KC 级 evidenceMass_k += |contribution_k|；masteryScore_k 按 LearningProjector 现有正负学习率更新
```

> **⚠ 2026-09-12 订正：上面这条 `strength` 分摊公式已被
> `docs/specs/mastery-scheduling-spec.md` §2.13 修订，并且它不是当前实现。**
>
> - 现行口径是「**全 KC 各记一次完整证据**」，`strength` 只作排序／展示用途；
>   仅在「KC 学习曲线证明过度共现噪声大」时才切到 strength 归一分摊，**当前不启用**。
>   研究依据见 `docs/research/behavior-signals-and-context-addendum.md` §5.2（该修订本身带 UNVERIFIED 标注）。
> - 另有一条**不变式**约束本节：归因（attribution）是**写入时**烘焙进不可变快照的事实，
>   其逐字段哈希进入 `LearningLedgerFingerprint`。因此**投影期不得读活的绑定表**来解析归因，
>   历史归因也不可事后重映射——否则同一份事件日志在不同时刻重放出不同结果、并让投影 `CONFLICT` 停摆。
>   完整论证：`docs/audit-2026-09-12-kernel-readiness.md` §3 **S-3**，规范表述见 spec §2.8.1。
>
> **照上面的公式去"修正"实现，会把对的改错。**

现状差距：视觉通道已按 PRIMARY 0.6/SECONDARY 0.4 pool 分摊（RoomBackedStudyExperienceRepository.kt:786-805），但**真实作答通道没有走 KC 分摊**（直接落 practice_unit 级 memory；KC mastery 由投影内 attribution 更新）——阶段 C 统一为「所有证据经 binding 分摊到 KC」。

### 10. 前置驱动的选题决策（阶段 C2，详见 linkage 文档）

```
readyToLearn(KC_k) = ∀ p ∈ prerequisites(k): masteryScore_p ≥ τ_ready (默认 0.6)
priority(question) = W1·urgency(question) + W2·weakness(KC) + W3·prereqGap(KC) + W4·confusable(KC)
prereqGap(KC) = max(0, τ_ready − min_{p∈prereq} masteryScore_p)
若 prereqGap > 0：本题降权，改为排入「前置补救材料」（knowledge_teaching_material 按 node binding 检索）
```
**接线状态（2026-09-12 更新）**：原先的差距是 `knowledge_node_relation`（PREREQUISITE_OF 已审校）与 `ReviewPlannerV2.scoreCandidate` **完全未接线**——判定代码在，但生产调用点从不给 `ReviewPlanningRequest.knowledgePrerequisites` 赋值，`prereqGap` 结构性恒为 0。现已接通：`KnowledgeReadiness`（core:domain）是判定的唯一权威，`KnowledgePrerequisiteReader`（core:data）按 KC 自己的科目分区并分块解析关系图，`StudyReviewPlannerService` 喂入当日候选的图，会话侧由 `StudyExperienceRepository.prerequisiteRemediation` 呈现**前置 KC** 的材料。上面伪码里"改为排入前置补救材料"落地为**非阻塞**的并列卡片（见 `three-store-linkage-design.md` §3.3 实现状态）。

### 11. 毕业机制（阶段 C4）

```
graduate(question) := 连续 3 次跨日成功提取（G≥2）且 当前 R(r*) 设计下 I(r*,S) ≥ 90 天
→ 移出常规队列，进入 maintenance 队列：next_review = I(0.8, S)（更低目标保留率 → 更长间隔）
回退：maintenance 中 Again → 回常规队列，lapse_count+1
```
现差距：`ClearlyMasteredForSkipPolicy.EVIDENCE_MASS=2.0` 只做跳过；需扩为状态机 Review→Graduated→Maintenance。

### 12. 参数优化器（阶段 B6，远期）

- 输入：review_log 全量 `(card_id, rating, delta_t, duration)`；
- 损失：BCE，BPTT 沿序列；Adam+Cosine；<512 条不跑；
- 输出：21 个 w 替换默认值；跑完必须 `evaluate()`（log-loss 对照 0.35–0.45）+ 灰度 reschedule（新间隔逐日渐进，禁一次性全改）。

---

## 第二部分：用户行为→数据映射（细粒度全目）

> 每条：**行为**（用户/客观来源）→ **转换规则** → **目标表.字段** → 现状（✅已记/⚠部分/❌缺）。
> 表缩写：AE=attempt_event, AES=assessment_evidence_snapshot(+attribution), LL=learning ledger(outbox), PMS=learner_problem_memory_state, KMS=learner_knowledge_mastery_state, MT=model_task(+event), RQ=review_plan/queue/session, SI=split_import_*, BID=batch_import_*, PD=problem_draft_*, EB=error_book_entry, KA=knowledge_*。

### A. 录入链行为

| # | 用户行为/客观来源 | 转换规则 | 目标字段 | 现状 |
|---|---|---|---|---|
| A1 | 打开相机拍一张 | 相机返回 content uri → assetVault.import（EXIF 剥离、字节上限、尺寸校验）→ canonical asset + draft | PD.source_asset；AE 无 | ✅ |
| A2 | 相册多选（≥2 张） | → staging（FileProvider 拷贝）→ batch job + pages QUEUED | BID.batch_import_job/page.source_uri | ✅ |
| A3 | 选 PDF | PdfBatchImportPageRenderer 逐页渲染 → staging page-N.source | BID.page.source_uri | ✅ |
| A4 | 拍摄后**裁剪**（手动 region） | crop → 新 canonical asset（无 region 记录） | 仅 asset 内容，**无 crop region 列** | ⚠ 建议记 crop_region 到 PD |
| A5 | 编辑题干/标题/学科/题型 | workspace 保存 → revise draft（author=USER）→ documentFingerprint | PD.revision.question_document_snapshot | ✅ |
| A6 | 确认保存（confirmAndCommit） | commit → EB+problem+revision+practice_unit 建档 | EB/revision/PU 全套 | ✅ |
| A7 | 拍完照不保存直接返回 | BackHandler flush；DisposableEffect 删临时 uri | 无任何「放弃」记录 | ❌ 记 abandonment 事件（改进漏斗） |
| A8 | 模型识别（CAPTURE_ASSESS）成功/失败 | MT.create→execute→transition；输出 assessment（decision/issues/questionRegions） | MT.output_snapshot；MT.event 状态/失败码 | ✅ |
| A9 | 模型解析（CAPTURE_PARSE）被采纳 | adoptParseOutput：workspace pristine 才采纳 | PD.revision.question_document | ✅（采纳不记来源 MT id ⚠） |

### B. 复习链行为

| # | 行为 | 转换 | 目标 | 现状 |
|---|---|---|---|---|
| B1 | 打开复习（当日计划生成/复用） | planner V2 plan→saveReviewPlan→queue | RQ.plan/queue/ordinal | ✅ |
| B2 | 复习作答（选择/输入） | recordAttempt：submissionId 稳定幂等 | AE 全套 + AES attribution + LL outbox | ✅ |
| B3 | 作答耗时 | durationSeconds 由 UI 计 | AE.duration_seconds | ✅ |
| B4 | 用提示（hint） | —— 无真实来源 | AE 无 hint 列；resolveOutcome 硬编码 hintCount=0 | ❌（阶段 A3） |
| B5 | 看答案（answer reveal） | recordAnswerReveal | assessment_answer_reveal_event + outcome | ✅（与 attempt 无关联列 ⚠） |
| B6 | 三档自评 | submitReviewSelfReport（requestId=UUID） | AE(author=SELF_REPORT) | ✅（无冷却 ⚠→A1） |
| B7 | 复习会话推进/完成 | advanceReviewSession CAS | RQ.session/advance_receipt | ✅ |
| B8 | 当日跨午夜 | studyDay 快照 | AE.study_day | ✅ |
| B9 | 复习中打开讲题 | tutor session 锚定 | tutor_* + exposure | ✅ |

### C. 讲题链行为

| # | 行为 | 转换 | 目标 | 现状 |
|---|---|---|---|---|
| C1 | 讲题消息（学生提问） | tutor turn | tutor_conversation/message | ✅ |
| C2 | 讲题中展示答案/解析（exposure） | TutorAnswerExposureOutcome materialize | tutor_answer_exposure_outcome | ✅（投影 0.45 双罚 ⚠→§7） |
| C3 | 讲解中视觉交互（Drag/Connect/Order/Submit 判定） | VisualInteractionEventSink | visual_interaction_attempt(actionKind,feasible) | ✅（decisive 白名单） |
| C4 | 探索性视觉操作（draw/measure/reset） | 同上，undecidable 不入账本 | 同上（不入 AE） | ✅ |
| C5 | 学生在讲题里修改草稿 | draft revise | PD.revision | ✅ |

### D. 整卷/切分链行为

| # | 行为 | 转换 | 目标 | 现状 |
|---|---|---|---|---|
| D1 | 批量导入页识别（batch-split） | BatchSplitRecognizer→MT→SPLIT 建任务 | SI.job/question + MT | ✅（egress -1 时间戳待修） |
| D2 | 切分确认勾选/取消 | updateSelection | SI.question.selected | ✅ |
| D3 | 逐题确认录入 | markConfirmed(SAVED/TUTOR) | SI.question.confirm_state+draft | ✅ |
| D4 | 放弃整次切分 | abandon | SI.job.status | ✅ API 在，UI 无入口 ❌ |
| D5 | 单页跳过/重试 | skip/retry page | BID.page.status | ✅ |
| D6 | 整理批准（organizeBatch） | boundary 模型判定 | BID boundary + MT | ✅ |

### E. 库内管理行为

| # | 行为 | 转换 | 目标 | 现状 |
|---|---|---|---|---|
| E1 | 搜索/筛选/排序 | FTS 参数绑定查询 | 无记录（无埋点） | ❌（产品分析缺，但非调度必需） |
| E2 | 详情页自动分类执行 | prepare/applyOrganization | organization_receipt + classification_binding + grounding | ✅ |
| E3 | 手动纠正分类 | correction editor | AppliedCorrectionRecord | ✅ |
| E4 | 导出 PDF | DeterministicPdfRenderer | 无记录 | ❌（可选） |
| E5 | 备份/恢复/清空数据 | SmbkArchiveCodec / deleteAllData | 无记录 | ❌（deleteAllData 漏 staging ⚠） |
| E6 | 进程被杀/崩溃恢复 | requeueInterrupted* | 状态自愈 | ✅（model task 无回收 ⚠） |
| E7 | 学习时段/会话位置 | （可从 AE.study_day+AE 序列推导） | 建议显式列 intra_session_pos | ❌（阶段 C 字段） |

### G. 交互犹豫信号（补充组，依据见 behavior-signals-and-context-addendum §一/§二）

| # | 行为/信号 | 转换规则 | 目标字段 | 现状 |
|---|---|---|---|---|
| G1 | 首答延迟 | 存毫秒值；判定用「相对个人基线的 log-normal 分位」（层级模型），非绝对阈值 | review_log.response_latency_ms + AE（新增列） | ⚠ durationSeconds 已有，分位判定缺失 |
| G2 | 快答对/慢答错 | 分位数触发 guess/slip 后验修正 → G 映射前修正 w_e | review_log.evidence_weight 修正记录 | ❌ |
| G3 | 提交前修改次数（删除/退格聚合值） | 只存聚合计数，不记逐键 | review_log.edit_count | ❌ |
| G4 | 改答案（改对/改错） | 改对=成功但 weight 打折；改错=失败 reason=CHANGED_FROM_CORRECT | AE.evidence | ❌ |
| G5 | scroll-up 回看题干次数 | 聚合计数 | review_log.reread_count | ❌ |
| G6 | 提交前停顿/空转 | 停顿秒数聚合 | review_log.pause_ms | ❌（可选档） |

### H. 现实客观事件（补充组，addendum §三）

| # | 事件 | 转换规则 | 目标字段 | 现状 |
|---|---|---|---|---|
| H1 | 时段桶 | 按用户作息切 MORNING/NOON/AFTERNOON/EVENING/NIGHT；个人乘数 M[b]（收缩向 1，样本≥30 启用） | review_log.time_bucket；乘数存 DataStore | ❌（spec §2.12） |
| H2 | 考试日程 | 用户申报考试日期 → 考前 r* 上调（0.95）、考后回落 | DataStore exam_calendar | ❌ |
| H3 | 长假（假期遗忘） | 连续 N 天无会话后首复：按假期时长+公共遗忘先验重估 R | 复查分支（无新列） | ❌ |
| H4 | 被打断作答 | 通知/切出后恢复的作答 weight 降权 | review_log.interrupted INT | ❌ |
| H5 | 学校考卷成绩 | 外部校准点：回填影子模型 outcome（不覆盖调度状态） | prediction_outcome | ⚠ 机制在、入口缺 |
| H6 | 睡眠窗推断 | 设备使用起止→入睡/起床估计（本地） | DataStore sleep_window | ❌ |

### F. 行为→数据的转换原则

1. **原始行为只落「题次事件」级**：毫秒轨迹/逐键不留存（3.6 节）；latency 只存「呈现→首次提交」的单值。
2. **调度证据与采集日志解耦**：账本（AE/LL）只存参与调度的证据；review_log 全量采集（含被冷却拦截的）供优化器。
3. **所有时间 UTC 存储、日历日差分**（现有 studyDay 机制保留为用户口径）。
4. **每次转换必须可重放**：行为→数据的转换函数本身幂等（现 fingerprint/CAS 机制扩展到 review_log 写入）。
