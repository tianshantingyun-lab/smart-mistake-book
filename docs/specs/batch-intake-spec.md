# 批量录入（纯入库不讲题）算法 Spec

**状态**: 方案定稿（2026-09-06，用户已拍板三项决策）
**证据底稿**: `docs/research/batch-intake-research.md`（科学/产品/用户心理三路取证）
**决策记录**: ①配额量纲=时间占比，预估时长由模型语义判断+本地校准；②前测形态=(b) 图题接讲题会话链路；③做题自信度=多源判定（模型语义+本地 UI 监测+客观信号），要做就做好。

---

## 1. 核心语义（不变量）

- **I1 录入=只进库存不进任务**：批量录入零证据写入（现状已满足），零掌握度影响；新题进待学态，不进复习候选池。
- **I2 配额载体=当日 plan**：`createReviewPlan` 仅当日无 plan 时生成一次（`observeCurrentReviewPlan(localDayEpochDay)` 已有复用），引入决策在候选层执行，plan 持久化即"今日引入集"持久化——无需新表。
- **I3 waiting 从引入起算**：被引入的新题 `eligibleSince=planningAt`（当日，waiting=0）；未引入的新题**不进候选**（不产生任何学习压力）。修正现状 `eligibleSince=createdAt` 导致的"压库越久 waiting 越高、开闸即挤掉到期复习"的架空缺陷。
- **I4 复习优先于新学**：引入的新题只允许占用时间预算的 `NEW_INTRODUCE_SHARE`（1/3【I】），到期复习天然优先（评分上已成立+配额硬上限）。

## 2. 引入配额与估时（模型估时长的方法）

**量纲**：时间占比（用户拍板）。当日可引入的新题时长总量 ≤ `reviewTimeBudgetSeconds × NEW_INTRODUCE_SHARE(1/3)`【I】。

**每题预估时长 = 三层接管（模型给语义、数值本地定）**：
- **L1 个人化接管（有数据）**：`LogDurationModel` 桶（learner×subject×itemType×difficulty）≥5 样本 → 本地 log-EMA 预测，模型不参与数值。
- **L2 语义基线（冷启动）**：模型的 difficultyTier（EASY/MEDIUM/HARD，已存在）→ 本地查表 90/180/300s【I】。
- **L3 全局先验**：无 tier → 60s（现状 GLOBAL_PRIOR_SECONDS）。

**教模型的判断方法（写进 organization/评估 prompt 规则）**——difficultyTier 按四个语义线索判断：
1. 解题步骤数（1-2 步=EASY；3-5 步=MEDIUM；≥6 步或需分情况讨论=HARD）；
2. 计算量（口算/简单代入=EASY；多步代数运算=MEDIUM；复杂计算/大数/多次转化=HARD）；
3. 知识点数量（单点=EASY；两点综合=MEDIUM；多点综合/跨章节=HARD）；
4. 是否多问（单问降一档；多问链式升一档）。
模型不估秒数——只给 tier，秒数由本地表与个人化数据出。

**考试逆推（模式切换）**：存在未到考试日时，日引入量 = `ceil(待学数 / 剩余天数)`，且不超过当日预算能装的量（`budget×share / 平均估时`）；考试日过后自动回落固定占比（examPriority ramp 已有，两者叠加时取逆推优先）。

**挑选顺序（引入优先级）**：考前逆推压力 > 高自信错误（超纠错，Hypercorrection——Butterfield & Metcalfe 2001，confidence-at-error 数据就绪后启用）> 同 KC 分组（交错有利 g=0.42，Brunmair 2019；纯事实类区分留待题型映射）> FIFO（先录先学，公平语义）。

## 3. 第一课=前测（形态 b：接讲题会话链路）

- 引入当天的题，先"重做原题"再讲解：
  - **Choice 题**：现有复习会话 choice 流（`submitReviewChoice`）直接承载；
  - **图题（解答题）**：接讲题会话——学生提交作答 → 视觉/文本判定 → 反馈讲解 → 判定结果经既有 attempt/visual 链路入账（`ingestVisualInteractionAttempts` 已有）。
- 入账语义：前测作答 = 该题**第一次真实 attempt**（INDEPENDENT_RECALL / RETRIEVAL_FAILURE）→ FSRS 初始稳定性由真实提取起算（消除零证据冷启动；前测效应 d=0.40-1.49，Kornell 2009/Pan & Rickard 2018【V】）。
- 前测答对 = 这题其实会 → INDEPENDENT_RECALL 高初始稳定性（也是超纠错正面场景）。

## 4. 做题自信度多源判定（AttemptConfidenceAssessment）

**用途**（两用，权重折价不在此处——已有链路不重复惩罚）：
1. 高自信错误 → 标 hypercorrection、首次重做提前、引入优先；
2. 低自信答对 → 已有 low-confidence-correct 降档通道的显式依据。

**四源信号（按优先级，冲突取低——宁判低自信更早重做，安全方向）**：
1. ~~**学生自报**（四键评级隐含自信）~~——**2026-09-13 已废止**：不再采信学生自报对错/掌握，无工件题改走讲题判定（模型语义判词 + 本地核对，非独立定价）；
2. **模型语义**（讲题对话 TutorUnderstandingTier：MASTERED/CONFIDENT=HIGH，UNCERTAIN/STRUGGLING=LOW）；
3. **本地 UI 监测**（ReviewInteractionTracker 已采集）：`attentionFactor`（切屏/离开）低=分心、`scrollUpCount` 高=回看犹豫、`editCount` 高=自我纠疑；
4. **客观推断**（无 1/2 时）：RT 相对个人基线的分位（TimeOfDayCalibrator RtBaseline——快答对=流畅，快答错=猜疑）+ 该 KC 保守掌握分。

**输出**：`ConfidenceLevel(HIGH/MEDIUM/LOW)` + per-source 依据（可审计快照）。纯函数，domain 层，全量可测。

## 5. 实现落点

| 层 | 改动 |
|---|---|
| domain | `NewIntroductionPolicy`（待学候选→今日引入集：时间占比+考试逆推+优先级）；`AttemptConfidenceAssessment`（四源自信度）；`tierBaselineSeconds(tier)` |
| data | `createReviewPlan` 候选层接入引入过滤（未引入新题不进候选；被引入的 eligibleSince=planningAt）；冷启动估时接 tier 基线 |
| feature | 前测引导流（P2）；录入完成计划预览（P3）——**可测逻辑已完成 2026-09-06**：`PretestRouting`（choice/讲题判定/自评三路路由 + producesRealAttempt）+ `NewIntroductionPolicy.previewBacklog`（待学库中位估时→覆盖计划装配）；剩余纯 UI 渲染层待 feature 会话排期 |
| 常量 | `NEW_INTRODUCE_SHARE=1/3`、tier 基线 90/180/300s——全部【I】集中待校准 |

## 6. 不做/后续
- confidence-at-error 的采集字段（录入流程"做题自信度"提问）依赖前测流程上线后才有真实数据——先由前测作答的信号面代替；**当前已接线的信号面**：ReviewLogSink.confidenceAtErrorByPracticeUnit() 从 review_log 存储信号（切屏/离开/回看）判定每卡最近一次错误作答的自信档——流畅错误（attentionFactor≥0.88，允许一次短暂切屏，对齐 FREE_SWITCH_ALLOWANCE；零回看）= HIGH（hypercorrection 排序输入），分心（factor<0.7）或犹豫（回看≥3）= LOW（且客观分心时任何自信声明一律降到底 LOW），其余 MEDIUM
- 题型→交错策略映射（纯事实类反转保护）留待分类数据积累
- **L1 激活（已完成 2026-09-06）**：LogDurationModel 现由 repository 持有单一共用实例（planner 构造注入 + 引入决策共用）。①Record 点=真实作答提交（submitChoice / submitReviewChoice / 讲题判定结算 settleTutorJudgedReview；后两者 subject 从 mistake 查）——`submitReviewRating`/`submitReviewSelfReport` 于 2026-09-13 拆除；②桶键**降维为 (learnerId, subjectId)**（DifficultyBucketKey 只含两维——difficulty/itemType 在记录时与查询时漂移导致永不命中，已在 LogDurationModelTest 更新断言）；③Warm-up=initialize 时从 review_log ATTEMPT 样本回放（observedAttemptDurations，仅真实作答）
- **P2/P3（已实现 2026-09-06）**：
  - P2 前测发起流：`PretestRouting`（三路路由 + fromScoringMode 装配 + producesRealAttempt）驱动；ReviewSessionScreen 的 NO_ASSESSMENT_ITEM 分支接入 TUTOR_JUDGED_FLOW 引导（"去讲题判定"按钮）→ app 根 navigate MistakeTutor（讲题会话判定首次作答）；LearningDao.findLatestAssessmentItemForPracticeUnit 装配查询已加
  - P3 计划预览：`previewBacklog`（逐日模拟 decide + unschedulableCount）+ `StudyReviewOverview.intakeBacklogCount/intakeMedianEstimateSeconds` + ReviewRoute IntakeBacklogPreview（"还有 N 道新题待学：每天约 X 题，约 M 天覆盖"）
