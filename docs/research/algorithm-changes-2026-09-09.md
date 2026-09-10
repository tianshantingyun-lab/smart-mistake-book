# 学习科学证据 → 算法改动决定（2026-09-09）

外部证据底稿：`scratch/RESEARCH-LEARNING-SCIENCE-2026-09-09.md`（一手来源优先，逐条标注
`[已核实]` / `[单一来源待验证]` / `[未找到]`）。本文只记录**决定与落地**：哪一条改了、
哪一条经复核后不成立、哪一条延后以及为什么。所有"已实现"都必须能在当前代码里指到行。

---

## 1. 已实现（本轮改动）

| # | 改动 | 依据 | 落点 | 验证 |
|---|---|---|---|---|
| 1 | `review_log.rating` 改记**调度实际使用的**评分 | 训练/推理分布一致性是 FSRS 拟合的基本前提；实体列注释本就写着 "after the evidence mapping" | `ReviewLogSink.kt`（`schedulingRatingFor`）；学员原始按键仍可从 `evidence_weight`（0.9/0.8/0.7/1.0）还原 | `RoomBackedReviewRatingTest.easyRatingMapsToRatingEvidenceAndReviewLog`（4→3 + weight 0.9） |
| 2 | leech 卡从**硬排除**改为**重罚**（×0.15） | spec §2.16 自述恢复路径是"跨日成功自动清零"，而硬排除让该路径不可达（自我维持）；Anki 手册的 leech 动作是改写/等待，不是永久停排 | `ReviewPlannerV2.scoreCandidate` + `LEECH_RANK_FACTOR`；难度冻结不变 | `ReviewPlannerV2Test.leeched card stays reachable but ranks below a healthy card` |
| 3 | 知识点到期风险改由**绑定题目的预测 R**（取最小）聚合 | Cepeda 2006/2008：间隔的意义相对于目标保持间隔；FSRS R(t,S) 才是有原则的时间函数。掌握度 EMA 在"新鲜"窗口内不随时间衰减 | `knowledgeRecallRiskByNode`（core/domain）+ `KnowledgeReviewCandidate.recallRisk` + `ReviewPlanner.scoreKnowledgeNode(recallRisk=)`；调用方 `RoomBackedStudyExperienceRepository.currentKnowledgeReviewPlan` | `KnowledgeReviewQueueTest` 3 例（聚合/取最小/打分方向） |
| 4 | w16（Easy 加成）固定为 **1.0 且永不参与拟合**；w15 解锁增加 HARD 样本门槛 | 证据映射从不判 EASY → w16 训练集为空、运行期永不生效（研究 §7）；用户从不按 Hard 时 w15 同样不可辨识（研究 §8） | `FsrsScheduleMath.DEFAULT_PARAMETERS[16]`；`FsrsParameterOptimizer`（扩展集只含 15，新增 `MIN_HARD_SAMPLES_FOR_W15 = 100`） | `SchedulingEvaluationTest.easy bonus is pinned neutral and never fitted`、`w15 w16 unlock contract…` |
| 5 | 同 KC 交错配额在**未达可学阈值**时豁免 | 低先验知识先组块更有效（Brunmair & Richter 2019 数学 g=0.34；Hwang；Nemeth & Lipowsky 2023） | `ReviewPlannerV2.antiOscillationPenalty` + `ScoredCandidate.weakestKnowledgeMastery` | `ReviewPlannerV2Test.same-KC quota is waived while the knowledge node is not yet learned` |
| 6 | 新增 **CMRR 式最优保持率模拟**（实验性、只读参考） | 目标与 fsrs-rs `simulation.rs` 的 `CMRRTargetFn` 一致：最小化"每次复习成本 / 记忆量"（研究 §5） | `OptimalRetention`（纯函数）→ `StudyExperienceRepository.recommendedDesiredRetention()` → 排程设置页"参考值" | `OptimalRetentionTest` 4 例 → **2026-09-10 增补 lapse 力后 5 例** |
| 7 | **考试**理由提前拉题**仅当 R < 0.8** | FSRS 稳定性增益 `e^{w10(1−R)}−1`：R≈1 时复习收益趋零；Rohrer & Taylor 2005 过度学习低效。**且这与 spec §2.17 一致**——考前队列本就定义为 `R < r*_exam` 的题 | `ReviewPlannerV2` 与 `ReviewPlanner`（V1 回滚路径同步）+ `EARLY_REVIEW_MAX_RETRIEVABILITY` | `ReviewPlannerV2Test.an exam pulls a card forward only below the retrieval ceiling` |

**研究 §5 的原始建议把 `REPEATED_MISTAKE` / `KC_MASTERY_DROP` 也纳入该门，本轮经复核后收窄到只对 `EXAM_PRIORITY` 生效**：`KC_MASTERY_DROP` 是 spec §5 的**用户规则**（"绑定知识点出现负向更新时，所有绑定题目的调度权重上升——连续传导，不是机械闸门"，`KnowledgeMasteryDropPropagationTest` 锁定），它携带的是题目自身 R 无法表达的新负向证据；`REPEATED_MISTAKE` 来自"同一题被重复拍入"的持久信号（`captureOccurrenceCount`），同理。把这两者按 R 关掉会让"知识点刚退化/反复出错但该题本身还新鲜"的题不再被提前复习——属产品回退。`CLOCK_ANOMALY` / `CALIBRATION_CHECK` 保持无条件（完整性检查）。

## 1b. 已实现（2026-09-10 / 09-11 续轮）

审计 `scratch/AUDIT-ALGORITHM-2026-09-09.md` §3.6 指出的"产品方向与实现相反"一条，
本轮接通。三条改动的共同点：**模型只给语义判断，一切数值与判定门槛留在本地**。

| # | 改动 | 依据 | 落点 | 验证 |
|---|---|---|---|---|
| 8 | MASTERED 门从"本地行为佐证"改为"模型逐字证据锚"（档2） | 本地在讲题通道拿不到可靠语义佐证（研究 `llm-mastery-judgment-regulation.md` §1）；判 MASTERED 必拒使 `WEIGHT_MASTERED_POSITIVE = 0.18` 成为死常数 | `MasteryWriteGate`（`REQUIRED_EVIDENCE_ANCHORS_FOR_MASTERED = 2` ＋ `evidenceAnchorCount(rationale)`）；`RoomTutorToolRunner` 填 `evidenceAnchorCount`；档1 prompt 规范补"用引号逐字引用"的机械形态 | `MasteryWriteGateTest` 15/15、`RoomTutorToolRunnerTest` 5/5；spec `2026-09-06-…` §1.1 |
| 9 | 模型判的难度档喂入新题冷启动估时（方向 1） | `TutorDifficultyTier` 在协议层解析后全库无消费方——新题没有记忆状态，占位难度使每道新题都按中档 180s 估时，当日引入配额按错误时长计算 | `IntakeDurationBaseline`（domain 纯策略，档→秒）；`ProblemOrganizationPlan.difficultyTier`；`KIND_DIFFICULTY_TIER` 咨询行；`StudyReviewPlannerService` 读取 | `IntakeDurationBaselineTest` 3/3、`OpenAiProblemOrganizationProtocolTest` 6/6、`RoomMistakeOrganizationRepositoryTest` 17/17 |
| 10 | 客观作答交叉核对：学生答错检查题时拒写 POSITIVE（§1.2） | 研究 `tutor-evidence-gate-research.md` §3.2——冲突时行为证据胜出，口头声明降级为观察记录（Koriat & Bjork 2005；Nelson & Dunlosky）；§4 参数表已列此门 | `TutorSessionObjectiveEvidence`（domain 纯策略）；`MasteryWriteGate.GateInput.objectiveAnswersContradictPositive` ＋ 新拒因；`RoomTutorToolRunner` 回读本会话本轮作答；prompt 规范第 5 条 | `TutorSessionObjectiveEvidenceTest` 4/4、`MasteryWriteGateTest` 19/19、`RoomTutorToolRunnerTest` 11/11；变异验证见 spec §1.2 |
| 11 | chat 证据写 `lastEvidenceAt` / `lastEvidenceDirection`；`PROJECTOR` v5→v6 | 审计 `AUDIT-ALGORITHM-2026-09-09` §3.5：`projectChatEvidence` 从不写这两个字段，而它们默认只从 attempt 通道的 `independentCorrectObservations` 推导 | `LearningProjector.projectChatEvidence`；`LearningCoreVersions.PROJECTOR` / 三个 composite 升 v6（触发已有库全量重放，否则修复对已投影事件静默无效） | `LearningProjectorTest` 11/11（新增 4 例，含 replay 等价性） |
| 12 | 日历日 delta_t 改由**时间戳 + 事件 UTC 偏移现算**；`PROJECTOR` v6→v7 | 审计 §3.7：`projectTutorAnswerExposure` 不带 studyDay，`ProblemMemoryState.lastReviewedEpochDay` 落入默认值（UTC 日序），使同一本地日的复习被判成跨日 → 走 long_term 分支拿到本不该有的稳定性增益 + 毕业连胜多计一次；`review_log.delta_t_days` 同源错配，污染 FSRS 参数优化器的训练数据（`docs/research/deltat-convention-research.md` §8.2） | `LearningProjector.projectMemory`（新增 `eventUtcOffsetMinutes` + `localEpochDayOf`）；`ReviewLogSink.record`；`ProblemMemoryState.lastReviewedEpochDay` 降级为审计字段；spec §2.1 过时表述与 §2.15 数字溯源一并修正 | `LearningProjectorTest` 13/13（新增 2 例：`a review after an answer exposure stays on its own learner-local day`、`a review on the local day after an exposure is still a cross day`）；变异验证：把推导改回常量使第 2 例失败（`expected:<2> but was:<1>`） |

**有意不做（记录理由）**：改动 10 没有把检查题作答**写成**掌握度证据。研究
`tutor-evidence-gate-research.md` §3.4 的独立性是硬前提——"同对话/同题变式/提示后作答
不独立，不计入多次门槛"。单次讲题会话内围绕同一道题的多个检查题作答彼此不相关度不足，
逐条写 POSITIVE 会让掌握度被非独立证据灌水。客观作答**落成证据**的通道仍是知识点复习
（`KnowledgeQuizFeedbackWriter`）：那是一次独立的、间隔开的复习活动，满足独立性前提。

**改动 11 修掉的两个后果**（§3.5 原文）：
- 后果 A：只经模型判断或知识点复习获得证据的 KC，`lastEvidenceAt` 恒为 null → `ReviewPlanner`
  判 stale → 该 KC **永远留在复习队列**，无论答对多少次。
- 后果 B：`kcMasteryDropPressure` 要求 `lastEvidenceDirection == NEGATIVE` 才传导 →
  spec §5 的"KC→错题权重联动律"在模型通道上完全失效（模型写入的负向证据不产生任何传导压力）。

## 2. 复核后**不成立**的条目

- **研究 §3"答对统一权重 0.15，未区分是否用提示"**：前提不成立。知识点复习通道
  （`submitKnowledgeQuizFeedback` → `MasteryWriteGate`）**没有提示/看答案通道**，不存在
  "提示后答对"这一状态；而题目复习通道已经分离：`MasteryEvidencePolicy` 把带提示/重试的
  答对映射为 `CORRECT_AFTER_HINT` / `CORRECT_ON_RETRY`，`FsrsEvidenceRatingMapper` 将其
  判为 `HARD`，`LearningProjector` 也只在 `positive && evidence.isIndependent` 时追加独立
  正确观测。故**无需改动**，改为在报告里修正该条描述。
- **研究 §8"训练/推理分布不一致"**：与 §1 是同一问题，已由本轮改动 1 消除。

## 3. 后续观察项（已实现，但需真实数据检验）

- **CMRR 参考值**（研究 §5）：实现为简化模拟（不含新卡引入、学习步与逐评分耗时表）。
  **2026-09-10** 补上遗忘/lapse 力（失败按概率计入重学成本，期望稳定性取成长与塌缩
  两支的加权均值，见 `OptimalRetention` 的 KDoc）——此前无该力时成本/记忆量曲线单调
  下降，"最优"退化为保持率下限（最高遗忘档）。仍是简化模型：低稳定性新学卡片占主导时
  读数仍可能贴下限，故只在设置页显示、不自动改设置；样本 < 20 张卡返回 null。
  真实数据积累后应对比实际复习量，确认推荐值不误导。
- **w15 门槛 100**（研究 §8）：数值取"足以辨识一个系数"的量级判断，非实证标定；
  若真实用户长期达不到，w15 会一直保持默认值——这比在空数据上拟合更安全。
- **同 KC 组块豁免**（研究 §2）：只在 `smoothedMastery < 0.6` 时生效，且仍受
  "同 item family 不连排"硬约束；需观察低掌握度会话的实际题面重复度。

## 4. 未找到证据、明确不得当作事实的项（沿用研究 §10）

leech 阈值/处理策略、掌握阈值具体数值、w15/w16 的受控实验证据、考试场景 desired
retention 最优值、"80% 掌握标准"的一手出处——均无一手证据，禁止在代码注释或产品文案里
写成"科学研究表明"。

## 4b. 审计余项状态（逐条核实于 2026-09-11）

审计 `scratch/AUDIT-ALGORITHM-2026-09-09.md` §3 共 11 条。**本轮逐条回读源码核实**后的状态
（不采信"应该已修"的印象）：

| 条 | 状态 | 核实落点 |
|---|---|---|
| §3.1 提示后答对在排程侧等同独立答对 | **已修** | `FsrsScheduleMath.schedulingRatingFor`：`CORRECT_AFTER_HINT` / `CORRECT_ON_RETRY`(weight≤0.25) → `HARD`。注意 `reportedRatingFor` 里 `CORRECT_AFTER_HINT` 仍 → `GOOD`，那是**只用于 review_log 记账**的路径，不影响调度 |
| §3.2 "很费劲"被当成"正常" | **已修** | `SELF_REPORTED_RECALL` 按 weight 分档（0.7 → `HARD`） |
| §3.3 `MasterySmoothing` 年龄项抵消 | **已修** | 见 `known-defects.md`（544b575） |
| §3.4 知识点队列无遗忘曲线 | **已修** | 由绑定题目的预测 R 取最小聚合（本文件改动 3） |
| §3.5 模型/quiz 证据不刷新排程时钟 | **本轮修复** | 本文件改动 11 |
| §3.6 知识点复习答对无法单独达成 MASTERED | **不改，且与 §1.2 同源** | `projectChatEvidence` 调 `clearlyMastered(…, emptyList(), …)` —— chat 证据不产生 `IndependentCorrectObservation`，故 breadth 永远不满足。这不是遗漏：研究 §3.4 的独立性硬前提使然，与改动 10"有意不做"是同一条理由 |
| §3.7 日历日 delta_t 与两套官方实现都不同 | **已修（口径收敛，2026-09-11）**＋审计的对照结论需修正 | 审计称"与两套官方实现都不同"——对 Anki 一侧不成立：Anki `elapsed_days_since`（`rslib/src/timestamp.rs:31-33`）以 rollover 为锚做日界计数，rollover=0 时与本地日历日差**数学恒等**（`docs/research/deltat-convention-research.md` §5，一手源码已复核）。真正不同的是 py-fsrs 的 UTC 24h 截断（`fsrs/scheduler.py:232`、`:269-270`，强制 UTC-aware）。缺陷不在口径选择，而在**派生字段的双定义**：`projectTutorAnswerExposure` 按 UTC 写、其余按本地读。已改为计算时从时间戳推导（本文件改动 12） |
| §3.8 排程永远拿不到 EASY | **已修** | w16 钉为 1.0 且永不参与拟合（本文件改动 4） |
| §3.9 leech 只在 V2 生效 | **部分：V1 仍未排除** | `ReviewPlanner`（V1）无 `candidate.leech` 检查，只有 `ReviewPlannerV2` 有。V1 是 kill-switch 回退路径，默认不走；改动它需先决定 V1 的存废 |
| §3.10 早复习通道可能压缩间隔 | **待裁定，非待办** | DASH/Rocket 式产品取舍；spec §5 明确要求"非机械闸门"。`EXAM_PRIORITY` 已收窄（本文件改动 7） |
| §3.11 其余偏差 | **记录在案** | `initialStability` 上界 100、`round` 半值方向、优先分权重为工程先验（标 UNVERIFIED）、`MasteryEvidencePolicy` 提示分支因 `persistedAssistance` 恒空而休眠 |

## 5. 复核方式（可重跑）

```
./gradlew :core:domain:test :core:data:testDebugUnitTest --rerun-tasks
```

覆盖本文件所有"已实现"项的测试类：
`ReviewPlannerV2Test`、`KnowledgeReviewQueueTest`、`RoomBackedReviewRatingTest`、
`FsrsScheduleMathTest`、`ModelTaskContractRegistryTest`、`MasteryWriteGateTest`、
`TutorSessionObjectiveEvidenceTest`、`RoomTutorToolRunnerTest`、`IntakeDurationBaselineTest`、
`OpenAiProblemOrganizationProtocolTest`、`RoomMistakeOrganizationRepositoryTest`、
`LearningProjectorTest`。
