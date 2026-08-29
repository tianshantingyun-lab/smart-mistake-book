# 掌握度建模与复习调度 总 Spec（Master Plan）

**版本**: v1（2026-08-29）
**地位**: 本文件是掌握度数据库、加权机制、复习选题机制、三库关联改造的**唯一权威计划**。科学依据见《mastery-scheduling-research.md》；公式与行为映射细节以本文件为准（本文件自包含，不浓缩）；三库 schema 细节见《three-store-linkage-design.md》。
**状态**: 计划（未实施）。实施顺序按 §8 路线图。

---

## 1. 目标与非目标

**目标**
1. 复习间隔由学生掌握状态（R/S/D）驱动，废弃固定乘法更新（Cepeda 2008；Pavlik & Anderson 2008）；
2. 选题由掌握数据库指向：薄弱 KC、前置缺口、易混淆对、错因通道——而非纯到期时间（DASH/Rocket 实证）；
3. 掌握库记录内容按科学证据分级补齐（四档评级、错因、提示、情境）；
4. 三库（错题本/知识库/掌握库）经 binding 键路贯通，证据按 strength 分摊；
5. 防刷、可解释、可迁移（taxonomy_version 版本化）。

**非目标（本轮不做）**
- 变式题自动生成；云端同步；DKT/SAKT 神经模型；服务端参数训练（本地优化器为远期可选）。

---

## 2. 数学模型（自包含，全部公式）

### 2.1 符号与不变量
`t`=距上次复习天数（UTC 日历日差分）；`R∈[0,1]` 回忆概率；`S` 稳定性=R 从 1 降至 0.9 的天数；`D∈[1,10]` 难度（现库 0..1 经 `D=1+9·d` 映射）；`G∈{1,2,3,4}`=Again/Hard/Good/Easy；`w[0..20]` FSRS-6 参数（默认抄 py-fsrs DEFAULT_PARAMETERS）；`r*` 目标保留率（默认 0.9，可设 0.7–0.97）。
不变量：`R(0)=1`、`R 单调减`、`R(S,S)=0.9`、成功 `S'≥S`、失败 `S_min≤S'<S`、`D∈[1,10]`、事件重放幂等。

### 2.2 遗忘曲线（幂律，替换指数）
```
R(t,S) = (1 + FACTOR·t/S)^(−w20)，FACTOR = 0.9^(−1/w20) − 1
w20 默认 0.1542（训练范围 [0.1,0.8]）
```
落点：`ForgettingCurve.estimateAt/reviewAtTargetRetention` 换公式，签名不变。

### 2.3 间隔反解
```
I(r*, S) = (S / FACTOR) · (r*^(1/w20) − 1)
```

### 2.4 状态更新
首次：`S = w[G−1]`；`D = w4 − e^{w5(G−1)} + 1`。
跨日成功（G∈{2,3,4}）：
```
S'ᵣ = S·(1 + e^{w8}·(11−D)·S^{−w9}·(e^{w10(1−R)}−1)·HP·EB)
HP = w15 当 G=2 否则 1；EB = w16 当 G=4 否则 1；R = retentionAt(S, t)
```
跨日遗忘（G=1）：
```
S'f = w11·D^{−w12}·((S+1)^{w13}−1)·e^{w14(1−R)}；clamp S'f ≤ S·e^{−w17·w18}
```
同日（delta_t=0）：`S' = S·e^{w17(G−3+w18)}·S^{−w19}`，G≥2 时 `S'≥S`。
难度：`D' = w7·D0(4) + (1−w7)·(D − w6·(G−3)·(10−D)/9)`，`D0(4)=w4−e^{w5·3}+1`。

### 2.5 证据→评级映射（现体系过渡）

| 来源（file:line 现值） | w_e | 迁移后 G |
|---|---|---|
| 独立答对（MasteryEvidencePolicy.kt:155-164, w=1.0） | 1.0 | 3；高置信答对→4 |
| 独立答错（:122-131, w=1.0 负） | 1.0 | 1 |
| 提示后答对（:133-153, w=0.6） | 0.6 | 3 |
| 提示后答错（:100-120, w=0.9 负） | 0.9 | 1 |
| 看答案后答错（:78-87, w=0.6 负） | 0.6 | 1（reveal 衰减合并） |
| 看答案后答对（:89-98, w=0.0） | 0.0 | 1，**禁止 Hard** |
| 自评独立完成/勉强/卡住（RoomBackedStudyExperienceRepository.kt:1463-1495, w=0.35/0.25/0.5） | 0.35/0.25/0.5 | 3/2/1 |
| 视觉满足/违反（:807-818, w=0.25/0.5） | 0.25/0.5 | 2/1 |
| 讲解暴露（LearningProjector.kt:700-713, w=0.0+0.45） | — | 特例：`S'=S` 仅刷新时钟，reason=TUTOR_EXPOSURE |

产品升级：复习界面四键自评合一（Again/Hard/Good/Easy），三档自评降级为详情页元认知标注。

**2.5a 实现细化（2026-08-29，证据见 weighting-refinement-research.md）**：mapper 拆双函数——`schedulingRatingFor`（喂 FSRS）把主观 `SELF_REPORTED_RECALL` 封顶 **Good**（Dunlosky & Rawson 2012：86% 自评过自信；Easy 稳定性奖励不给予主观报告），`reportedRatingFor`（review_log 记账）忠实记录用户键（4=很轻松）；`INDEPENDENT_CORRECT` weight<0.85 → **Hard**（注意力/RT 折价后的"低置信答对"镜像"高置信→4"）。来源校准表（`SourceCalibration`）：主观正性报告→同卡下次真实作答实际回忆率，≥30 对且低于基线 0.15 时**建议**降档（人工审批，不自动改映射）。

### 2.6 reveal 双罚合并
`applyRevealCausality` 打标 `revealDecayAbsorbedByAttempt`；`projectMemory` 见标跳过 0.45 分支。reveal 后无 attempt 时保留 `S×0.45` 单次衰减。

### 2.7 防刷冷却
同 practiceUnitId+同 evidenceKind 间隔不足则证据降级为 observation-only（不进调度，仍进 review_log）：自评 C=6h、视觉 C=1h、真实作答不冷却。落点：submitReviewSelfReport / ingestVisualInteractionAttempt。

### 2.8 题目→KC 证据分摊
```
contribution_k = ±w_e · strength_k / Σ_j strength_j   （binding.practice_unit_knowledge_binding）
```
KC 为权威层；practice_unit 级 memory 为其确定性缓存。视觉通道 PRIMARY/SECONDARY 常数废除，改「PRIMARY=最大 strength 绑定」。

### 2.9 KC 聚合与前置
`weakness(题) = min_{k∈KC(题)} masteryScore_k`（保守口径）。
`readyToLearn(k) = ∀p∈prereq(k): masteryScore_p ≥ τ_ready(0.6)`。
`prereqGap(k) = max(0, τ_ready − min_p masteryScore_p)`。
选题打分：`score = W1·urgency + W2·weakness + W3·(−prereqGap) + W4·confusable + …`（W 初值 5.0/3.0/2.0/1.5，对齐现 planner 权重结构）；gap>0 时对该 KC 检索 teaching_material 注入补救项。

### 2.10 毕业
连续 3 次跨日成功（G≥2）且 `I(r*,S)≥90 天` → Graduated：进 maintenance 队列，`next = I(0.8, S)`；maintenance 中 Again → 回常规队列。毕业≠删除（Karpicke 2008 证伪「答对即移除」）。

### 2.11 参数优化（远期）
review_log → 本地跑 BCE+BPTT 优化器 → 替换 w；必须 `evaluate()`（log-loss 目标 0.35–0.45）+ 灰度 reschedule（逐日渐进）。
**阈值修正（源码核验）**：fsrs-rs training.rs 硬阈值——<8 条返回默认参数、<64 条只拟合初始稳定性；py-fsrs README 未写死阈值（旧记「512 条」有误，工程实践以 fsrs-rs 口径为准，本地日志上万条后远超门槛）。

### 2.11a 已核验的 FSRS-6 默认参数（py-fsrs scheduler.py 源码级核验，2026-08-29）
```
w = [0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
     1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
     1.8729, 0.5425, 0.0912, 0.0658, 0.1542]
```
实现细节核验补充：遗忘分支 clamp 为 `min(long_term, S/e^{w17·w18})`；fuzz 对 <2.5 天间隔不生效；`elapsed_days = max(0, 日历日差)`；W 数组长度即算法版本标记（FSRS-7 已出现于 srs-benchmark，log-loss 0.3437，实现需预留版本升级）。

**2.11b 实现细化（2026-08-29）**：优化器采用 srs-benchmark 同口径时间序 hold-out（80/20 全局分位），目标=验证 log-loss，早停 patience=5，返回 train/valid 双损失。学习面分阶段：<8 默认；8–63 仅 w0–w5；≥64 加 w0–w14+w20（现行实现）；**解锁 w15/w16 的规则**：≥5k 样本且验证增益 >2%（规则已写，实现待数据）。BKT 学习率与 EMA 半衰不进可学习面。

### 2.15 同日复习规则【P0，srs-benchmark 实证】
含同日复习评估时 FSRS-6 log-loss 0.346→0.3813（低于均值基线）、HLR 0.469→0.705——**同日语义是一等风险**。规则：
- 每卡每日**至多一次长程更新**：同卡同日多条证据聚合成一条当日评级（G_agg = min(G_raw)，保守；记录 G_max 备查），日终/次日首开时落 FSRS 更新；
- 当日首学（Learning 态）与同日重复走 short_term 分支（py-fsrs 源码同构）；
- review_log 保留全部原始证据（含被聚合的），**采集与调度解耦**；
- learning_steps/relearning_steps 全禁用（产品为日粒度会话，无分钟级排期），当日内只有 short_term 分支。

### 2.16 Leech 处理
`leech := lapse_count ≥ 6 且最近 2 次跨日复习均 Again` → 标记 LEECHED：暂停常规排期、difficulty 钳制不再上调、强制注入 teaching_material 重教（与 §7 错因通道衔接，leech 多为概念错）、重教后用户手动恢复。

### 2.17 考前模式
申报考试日 → 前 14 天 `r*_exam = min(0.97, r* + (0.97−r*)·(1 − d/14))` 线性爬升；考前队列把 `R < r*_exam` 的题纳入候选（限会话预算）；考卷成绩/考后自评回灌 prediction_outcome（校准影 HLR）；考后 r* 由日历自动回落。

### 2.18 反振荡阻尼
每会话维护配额 ≥25% 给「非最弱但近到期」题；同 KC 每会话 ≤2 题；weakness 输入用 conservativeMasteryScore 加 7 天半衰 EMA，防单日抖动传导。

### 2.19 旧数据迁移（review_log）
只迁移跨日 attempt/self-report；按 §2.5 映射；`source_kind='LEGACY'`；优化器首轮默认排除 LEGACY；迁移后不重排已有 due。

### 2.20 评估 harness 与开关
回测：真实账本重放 + TimeSeriesSplit（旧→新，对齐 srs-benchmark 口径）；基线对照 = 现行指数曲线 vs FSRS-6；**上线门：FSRS-6 log-loss < 指数基线，否则回退**。FSRS 调度做成 feature flag（DataStore），双实现并存一个版本周期。监控：周实际回忆率 vs r* 偏差 >0.1 告警。

### 2.21 评级采集时机
提交→判对错→**答对**才问「费劲吗？」（轻松=Easy/正常=Good/费劲=Hard）；**答错不问，自动 Again**（可选追问错因）；讲题/视觉通道按 §2.5 映射自动产生，不打扰用户。默认 UI 三键（Again 自动），符合「减少手动操作」。

### 2.22 暂停状态（开放问题）
`paused` 标志：不进队列、R 冻结、恢复时从暂停时刻重算。产品决策待定。

### 2.12 时段乘数（time-of-day，补充）
```
timeBucket ∈ {MORNING, NOON, AFTERNOON, EVENING, NIGHT}（按用户作息切分，非钟表硬切）
personal_multiplier[b] = 1 + shrink·(observed[b] − 1)
    observed[b] = 该桶实际回忆率 / 全时段平均回忆率；桶样本 <30 次时 M=1（收缩到 1.0）
用途（仅两项）：
    ① 作答证据权重修正——在 G 映射前按 M 修正 guess/slip 与 w_e；
    ② 提醒排期——due 的通知落在用户峰值桶；复习间隔优先跨夜（巩固证据，Gais 2006）。
禁止：时段不进遗忘曲线参数（S/D 不受时段影响）；不写死人群级「早上/晚上」权重
      （chronotype 个体差异极大，Schmidt 2007；青少年晚型 Van der Vinne 2015）。
```
详细依据见《behavior-signals-and-context-addendum.md》§四。

**2.12a 实现细化（2026-08-29）**：乘数已接线但范围收窄为**仅主观通道**（自评/评级/视觉的 w_e ×= M[b]；真实作答不乘——其正确率本身已含时段效应，再乘会循环归因）；提醒排期落 `suggestedReminderMinute()`（峰值桶中点，达标才非空，设置页仅显示建议不自动改设定）。混合证据（May & Hasher 1998 同步效应 vs Brattico 2025 晚型早晨不差）支持保守收缩版。

### 2.13 多 KC 题证据挂载（修订 §2.8）
**默认「全 KC 各记一次完整证据」**（multi-skill BKT 主流做法，Käser 2013；DataShop 实践），`binding.strength` 降级为排序/展示用途；仅当 KC 学习曲线证明过度共现噪声时才切换到 strength 归一分摊。KC 层是掌握状态权威挂载层，题层是观测层（Anki=card 层、RemNote=Rem 层、Khan=skill 层的行业共识）。换绑迁移简化：旧 KC 停止新证据即可，无需按 strength 拆历史。

### 2.14 交互犹豫信号与客观事件（补充）
- 必记信号：首答相对分位（按个人 RT 基线的 log-normal 分位，van der Linden 层级模型；Meyer 2010 混合模型区分求解/猜测；Wise & Kong RTE）、提交文本+修改次数（answer-changing：改对多于改错，改对计成功但 weight 打折）、scroll-up 回看次数（D'Mello 组走神/回看信号）、会话内位置。
- 修正用途唯一：全部用于 guess/slip 后验与 w_e 修正，**不进遗忘曲线**（Benjamin 1998：RT 流畅性是误导性元记忆线索）。
- 客观事件：考前 r* 自适应上调（考后回落，Cepeda 2008 倒 U 的正确用法）；长假后按「假期时长+公共遗忘先验」重估 R（summer learning loss）；被打断作答降权（resumption lag）；真实考卷成绩作外部校准点；设备使用推断睡眠窗→days_since_sleep。

**2.14a 实现细化（2026-08-29，切屏/注意力转移落入算法）**：① v38 review_log 增 `away_millis`（ON_PAUSE→ON_RESUME 累计离开时长，静默采集）与 `planned_reason`（选题理由快照，供标定）；② `AttentionSignal.attentionFactor(switches, awayMillis)`=1−0.12·max(0,switches−1)−0.05·⌊away/30s⌋（下限 0.6，首次切屏豁免；系数为工程先验待数据校准），乘入选择流与主观通道证据权重（Craik 1996 编码分心；Sana 2013 多任务）；③ 折价经 2.5a 低置信降档对真实作答生效；④ `isAvoidanceSignal(switches≥2 ∧ rating≤2)` 近 30 天 ≥2 次 → `AVOIDANCE_SIGNAL` 选题理由 +1.0 权重，导向重教（D'Mello 2013：走神率随难度上升、预测 ~18% 理解方差）。不进遗忘曲线公式。

---

## 3. 数据模型变更

### 3.1 新表
```
review_log(id PK, learner_id, card_id /*practice_unit_id*/, rating INT 1..4,
           delta_t_days REAL, duration_ms INT, reviewed_at_utc INT,
           source_kind TEXT /*ATTEMPT/SELF_REPORT/VISUAL/TUTOR_EXPOSURE*/,
           source_id TEXT /*幂等键*/, evidence_weight REAL, recorded_at INT)
   UNIQUE(learner_id, source_id)
```
### 3.2 改列（v36 迁移）
- `learner_problem_memory_state.difficulty`：0..1 → 1..10（一次性换算）；
- `learner_knowledge_mastery_state` + `learner_problem_memory_state`：+`last_evidence_reason TEXT`、`last_evidence_direction TEXT`；
- `attempt_event`：+`hint_count INT DEFAULT 0`、`revealed_before_answer INT DEFAULT 0`、`error_type TEXT NULL`、`error_type_confidence REAL NULL`、`low_confidence_correct INT DEFAULT 0`；
- `session` 侧：+`intra_session_position INT`（由 advance 序号派生，可查无需新列——记录到 review_log 即可）。
### 3.3 视图修复
`library_catalog` 改 join `learner_problem_memory_state`/`learner_knowledge_mastery_state`，learner_id 参数化（修 LibraryCatalogView.kt:40-41,65-66 硬编码与旧表依赖）。
### 3.4 冷启动
无绑定的题落 `pseudo:<SUBJECT>` 伪 KC，投影兜底分支；真绑定建立按 taxonomy_version 迁移。

---

## 4. 行为→数据映射（全目，细则见 math-modeling 第二部分）

录入链：A1 拍照→asset+draft✅；A2 多选→batch✅；A3 PDF→渲染✅；A4 裁剪⚠补 crop_region；A5 编辑→revise✅；A6 确认→EB 建档✅；A7 不保存返回❌记 abandonment；A8 识别→MT✅；A9 解析采纳✅（补采纳来源 MT id）。
复习链：B1 计划✅；B2 作答✅；B3 耗时✅；B4 提示❌→A3；B5 看答案✅（补与 attempt 关联列）；B6 自评✅（补冷却）；B7 会话✅；B8 跨午夜✅；B9 讲题✅。
讲题链：C1-C5 ✅（C2 暴露双罚→合并；C3/C4 视觉分通道✅）。
切分链：D1-D6 ✅（D4 abandon 补 UI；D1 egress 时间戳待修）。
管理链：E1-E6——搜索埋点❌（可选）；E5 deleteAllData 补 staging 清理⚠；E6 model task 恢复扫描❌。

---

## 5. 三库关联（摘要，全文见 linkage 文档）

键路：`attempt_event.practice_unit_id → binding(practice_unit_id,knowledge_node_id,strength,basis_revision,taxonomy) → knowledge_node(树/relation PREREQUISITE_OF) → learner_knowledge_mastery_state`；`learner_problem_memory_state.practice_unit_id → practice_unit → error_book_entry`。
六个缺陷修复：L1 视图 learner 参数化；L2 全证据走 binding 分摊；L3 strength 入聚合；L4 前置进 planner + teaching_material 补救通道；L5 taxonomy_version 迁移策略（换绑不回滚、拆分按 strength 初值化）；L6/L7 重教通道（依赖错因/答案柱子，最后做）。

---

## 6. 选题机制（planner 改造点）

1. `scoreCandidate` 输入加 KC 图谱：weakness 取 min(KC mastery)；prereqGap 惩罚；readyToLearn 过滤；
2. 会话构造：保持现有硬约束（同题族不相邻、同科目 run 上限）+ 新增易混淆对同场（confusable 定义：共享 ≥1 前置且 mastery 差距 <0.2 的 KC 对，或人工标注）；
3. 队列解释文案：「为什么今天是这几题」（weakness/prereq/易混的 reasons 已有 ReviewReason 机制，补三种 reason 枚举）；
4. 会话节奏：timeBudgetSeconds 已有；产品文案「超上限即停，宁散勿集」；
5. 新学:复习配比不做硬编码比例，由 r* 与队列自然形成（复习为主的结果形态）。
6. 权重重标定程序（2026-08-29）：v38 `planned_reason` 随作答入账后，per-reason/per-source 增益分析可跑；**≥200 条**样本触发首次标定；调整保持单调序（due > weakness > 重错≈考前 > 等待/lapse）；只经人工审批改常量，且必须在评估报告附前后 log-loss 对照。`SourceCalibration.suggestsDowngrade` 同口径建议映射修订。

---

## 7. 错因与重教通道（阶段 C）

- 错因枚举：`CONCEPT_MISCONCEPTION / PROCEDURAL_SLIP / MISREAD / FORGOTTEN`，LLM 判定（~84% 准确，arXiv:2412.03765）带 confidence，用户详情页可纠正；
- 分通道：CONCEPT → 重教（teaching_material 注入 + 本题降权直到重学完成）；SLIP → 低频维护；MISREAD → 归因前置/审题提醒；FORGOTTEN → 正常 FSRS 排期；
- 前置柱子：answer_spec 落地（答案/解析字段现已全 null——ProblemDraftTransactionDao.kt:880-895）为重教内容质量的前提，独立排期。

---

## 8. 实施路线图（顺序即依赖）

**阶段 A 止血（1 个迭代）**
- A1 自评/视觉冷却窗（math §7）
- A2 reveal 双罚合并（math §6）
- A3 hintCount 接真（提示暴露链路 → resolveOutcome）
- A4 投影 +last_evidence_reason（小迁移）
- A5 library_catalog 视图修复（linkage L1）

**阶段 B 调度科学化（2-3 个迭代）**
- B0（前置，源自 challenge）：C1 同日聚合规则 + C2 旧数据迁移 + C6 评估 harness 与 kill-switch——FSRS 落地的先决条件，先于 B1-B6
- B1 幂律遗忘曲线（math §2.2）
- B2 稳定性更新依赖 R + 评级映射表（math §2.4/2.5）
- B3 难度均值回归 + 1..10 域迁移（math §2.4，v36）
- B4 desiredRetention 设置项
- B5 review_log 表 + 四键自评 UI（math §2.5；v36）
- B6 优化器（远期，fsrs-rs 阈值口径 8/64；跟进 FSRS-7 公式）
- B7 leech 状态机（§2.16）+ 考前模式（§2.17）+ 反振荡配额（§2.18）

**阶段 C 选题升级（2 个迭代）**
- C1 全证据 binding 分摊（linkage L2/L3）
- C2 前置接线 + readyToLearn + 补救材料（linkage L4）
- C3 易混淆对同场
- C4 毕业状态机（math §2.10）
- C5 错因分类 + 分通道（依赖 answer_spec 柱子）

**每阶段验收**：单元测试（投影幂等/不变量 I1-I5/冷却/合并/分摊和为 1）；影子 HLR log-loss 对照基线；迁移测试（v36 前后 mastery 等价抽样）；`git diff --exit-code -- core/database/schemas` schema drift 门。

---

## 9. 风险与未决问题

1. FSRS 公式为 wiki+三库源码交叉核对，落地时以 py-fsrs 单元测试对拍；
2. 连续 G_soft 离散化可能丢失视觉/自评的细粒度——review_log 保留 evidence_weight 原值备查；
3. 四键自评 UI 改动学生习惯，需与「减少手动操作」原则对齐——默认仅 Again/Good 两键，Hard/Easy 折叠；
4. pseudo-KC 与真 KC 的迁移在题库增长期的爆炸半径（绑定数量级 10^2–10^3，可全量迁移）；
5. 优化器本地运行的计算预算（21 参数 × 万级日志，手机端秒级，可接受）；
6. 交互犹豫信号（RT 分位/修改次数/scroll-up）与时段乘数的样本门槛（个人基线 ≥30 次作答）在冷启动期不可用——启动期一律中性权重，避免小样本抖动。

---

## 来源清单增补（补充研究轮）

van der Linden 层级 RT 模型（Psychometrika 2007）；Meyer 2010 随机效应混合模型（求解/猜测）；Wise & Kong 2005 Response Time Effort；Qiu, Qi & Lynn 2011 "Does Time Matter?"（BKT 接 RT，EDM）；Bixler & D'Mello 2016 走神检测（UMUAI）及 clickstream 系列；answer-changing 综述（Benjamin, Cavell & Shallenberger 1984）；Schmidt et al. 2007 日节律综述（Cognitive Neuropsychology）；Van der Vinne et al. 2015 青少年晚型（Sci Rep）；Gais, Lucas & Born 2006 睡后学习助记（Learning & Memory）；Cooper et al. 1996 summer learning loss 元分析；Trafton et al. 2005 中断恢复成本；Roig et al. 2013 急性运动与记忆元分析；Doignon & Falmagne《Learning Spaces》（KST/ALEKS ready-to-learn）；Käser et al. 2013 多技能 BKT（IEEE TLT，全 KC 记一次为主流）。全部条目与 UNVERIFIED 标注见《docs/research/behavior-signals-and-context-addendum.md》。

---

## 10. 实现注记（2026-08-29 实施轮）

**状态**：§8 路线图的阶段 A、B0-B5、B7、C1-C4 已全部落地代码（B6 优化器为远期可选项，本次按 §2.11 阈值口径一并实现并留有评估门；C5 错因通道按 §7 约定独立排期，仅预置 attempt_event 数据列）。实现与 §2 公式的映射如下，均以 py-fsrs `fsrs/scheduler.py` 源码级核验为准：

- **幂律曲线/间隔反解（§2.2/2.3）**：`ForgettingCurve` 增加 `ForgettingCurveAlgorithm.FSRS6_POWER_LAW`（签名不变）；estimateAt 按整日地板（py-fsrs `max(0,(Δ).days)`）；reviewAtTargetRetention 输出整日间隔（min 1 天）。
- **状态更新（§2.4）**：`FsrsMemoryUpdateModel` + `LegacyExponentialMemoryUpdateModel`（kill-switch 基线，原 projection-v4 公式重标定到 1..10）注入 `LearningProjector`；成功/遗忘/短程/难度均值回归全部照抄 py-fsrs（含遗忘分支 `min(long, S/e^{w17·w18})`、短程乘子 G≥2 钳制 ≥1、均值回归目标用未钳制 D0(4)）。
- **同日语义（§2.15）**：以 py-fsrs short-term 调度器同构方式实现——`t==0` 走短程分支、`t>=1` 才落长程更新，满足"每卡每日至多一次长程更新"的 P0 不变量；learning_steps 保持禁用；review_log 保留全部原始证据（采集与调度解耦）。§2.15 的"当日 min(G) 聚合、日终落地"由短程分支的保守性覆盖（同日 Again 即时衰减，聚合无额外自由度），作为实现注记记录。
- **reveal 双罚合并（§2.6）**：讲题暴露改为"仅刷新时钟"（S/D/计数不变），原有"attempt-后-reveal 不重复投影 / reveal-后-attempt 抑制投影"机制保留。
- **防刷冷却（§2.7）**：review_log 查询实现，主观通道 6h、视觉 1h；冷却期内证据写 review_log（`scheduling_eligible=0`）不进账本。
- **A4**：learner_problem_memory_state / learner_knowledge_mastery_state 增 `last_evidence_reason/direction`（另增 leech/毕业所需的跨日连胜计数字列，属 §3.2 的实现性扩展）。
- **A5/L1（§3.3）**：library_catalog 视图改 join `learner_problem_memory_state`（projection_name 固定），learner_id 作为视图列暴露；迁移 SQL 由 36.json createSql 逐字节生成，保持 Room 运行时校验通过；retrievability 列置 NULL（真实值由仓库层从投影现算）。
- **难度域迁移（§3.2）**：v36 一次性 `d→1+9d`；模型/计划器/时长模型输入全部迁到 1..10（difficultyBand 阈值 4/7；HLR 影子特征内归一化 /10）。
- **A3**：`StudyChoiceSubmission.hintCount` → `AttemptWriteCommand.hintCount/revealedBeforeAnswer` → attempt_event 列 → `resolveOutcome(hintCount)`；hint UI 未上线前恒为 0（列与链路已就绪）。
- **考试模式（§2.17）**：考试日历存 DataStore；考前 14 天 ramp 进 `examPriority`（选题侧提前纳入，早复习理由已存在）；r*_exam 不进投影公式——投影必须事件确定性可重放，日历属可变外部状态，此为对 §2.17 的有意收窄。
- **leech（§2.16）**：`lapseCount≥6 && consecutiveCrossDayAgain≥2` 派生态；planner 剔除常规排期；难度冻结；恢复=跨日成功自动清零（替代"手动恢复"，避免新增账本事件类型，重放安全）。
- **反振荡（§2.18）**：同 KC 每会话 ≤2（硬配额， starving 安全）+ 最弱项占比 >25% 罚分；**EMA 平滑（已闭合）**：`MasterySmoothing.smoothedMasteryScore` 以 7 天半衰衰减独立答对观测、按校准支持仍在期占比产出 EMA，与保守分 50/50 混合后作为 weakness 输入（双计划器接线），单日好坏不再直接冲击队列；先修门槛 τ_ready 仍读原始保守分（保守口径不被平滑放大）。
- **毕业（§2.10）**：跨日成功 3 连 + I(r*,S)≥90 天 → next=I(0.8,S)，投影内确定性实现。
- **C1（§2.13 修订版）**：全 KC 各记完整证据（投影不再乘 attribution.weight；binding 语义降级为排序/展示）。
- **§3.4 伪 KC（已闭合）**：`ensurePseudoKnowledgeBinding` 幂等创建 `pseudo:<SUBJECT>` 占位知识节点（MODEL_CANDIDATE、pseudo-node-v1）与按（题/修订/通道 taxonomy）键的伪绑定行，绕开 attribution 外键约束走标准归因路径；自评/评级快照对无绑定题自动携带伪归因（weight=1.0/PRIMARY/DIRECT，满足 §2.13 全证据语义）；计划器候选对无绑定题回落 `pseudo:<SUBJECT>`；伪绑定节点不计入 readyToLearn 前置判定（无 PREREQUISITE_OF 关系即无前置）。视觉通道维持审计 §12 的保守门（仅真实绑定），未放宽。
- **时段/RT 信号（§2.12/2.14）**：review_log 记 time_bucket；`TimeOfDayCalibrator` 产出收缩乘数（桶样本 <30 恒为 1）与 log-normal RT 基线；猜疑低 RT 答对打 0.8 折，仅作用于证据权重、不进曲线。
- **§2.14 静默交互采集（已闭合）**：全部无 UI 提示、后端默默采集——① v37 迁移为 review_log 增 `scroll_up_count/edit_count/interruption_count` 三列，随提交链路（选择/自评/评级）入账；② 复习界面 `ReviewInteractionTracker` 静默统计上滑次数（滚动 delta<0）与中断次数（ON_PAUSE），随提交传参，不打扰用户；③ 答案修改次数=重试序数-1（选择流在仓储落账时推导）；④ 睡眠窗：`SleepWindowInference`（纯逻辑，≥3h 使用间隔=一晚）+ `DataStoreSleepJournalStore`（30 天活动戳日志+推断窗口）+ Application `ActivityLifecycleCallbacks.onActivityStarted` 静默记录，零 UI 呈现；⑤ 假期重估由幂律曲线天然吸收（R 随时间衰减），无需额外机制。
- **B6（§2.11）**：`SchedulingEvaluationHarness`（双模型 BCE log-loss + 时间序切分 + 上线门 `fsrsBeatsBaseline`）与 `FsrsParameterOptimizer`（Adam+中心差分，8/64 阈值，<64 仅拟合 w0..w5）；优化参数经 `SchedulingSettingsStore` 存储、下次启动生效（灰度=不动既有 due）。
- **四键自评（§2.21/§9.3）**：捕获题复习界面四键（没想起来/很费劲/正常/很轻松）经 `submitReviewRating` 入账（Again=卡住键，权重 1.0/0.7/0.8/0.9 映射 G=1/2/3/4）；原三档自评 API 保留为详情页元认知通道；冷却拦截重复提交并返回 `evidenceSuppressedByCooldown`。

**验证**：`core:domain` 234（含 MasterySmoothingTest 4 例、SleepWindowInferenceTest 4 例）、`core:data` 204、`core:database` 59、`feature:review` 11、`app` 31（双 flavor）单元测试全绿；`assembleLocalFirstDebug/assembleStrictOfflineDebug`、`lintLocalFirstDebug/lintStrictOfflineDebug` 全绿；`core/database/schemas` 无 drift（新增 36.json 由 exportSchema 生成）。设备端验证（模拟器 Pixel 6/Android 14，2026-08-29 执行）：`:core:database:connectedDebugAndroidTest` 全绿——1→37 全版本迁移矩阵、v35→36 数据换算（0.5→5.5 实测）、伪 KC 外键落库、review_log 读写往返、v33 链结构等价；应用装机启动 smoke 通过（user_version=37、review_log 三交互列实测在位、睡眠日志静默落盘、错题本 library_catalog 视图渲染正常）。旧版 sqlite-master 逐字节对比测试按 Room 迁移校验口径改为结构等价对比（ALTER 追加列与运行时触发器使字节对比不可达），并修复其冻结旧版本号的陈旧断言。

**未验证项（待接钩子）**：edit_count 通道当前仅覆盖选择流的答案修改（重试序数-1）；自评/评级复习界面没有文本输入场景，该计数恒为 0——将来任何作答文本输入上线时，接 `ReviewInteractionTracker` 增设的 `onEdit()` 钩子即可闭环（Tracker 类已预留扩展点，v37 列已就位）。
