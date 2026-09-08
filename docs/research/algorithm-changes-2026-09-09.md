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
| 6 | 新增 **CMRR 式最优保持率模拟**（实验性、只读参考） | 目标与 fsrs-rs `simulation.rs` 的 `CMRRTargetFn` 一致：最小化"每次复习成本 / 记忆量"（研究 §5） | `OptimalRetention`（纯函数）→ `StudyExperienceRepository.recommendedDesiredRetention()` → 排程设置页"参考值" | `OptimalRetentionTest` 4 例 |
| 7 | **考试**理由提前拉题**仅当 R < 0.8** | FSRS 稳定性增益 `e^{w10(1−R)}−1`：R≈1 时复习收益趋零；Rohrer & Taylor 2005 过度学习低效。**且这与 spec §2.17 一致**——考前队列本就定义为 `R < r*_exam` 的题 | `ReviewPlannerV2` 与 `ReviewPlanner`（V1 回滚路径同步）+ `EARLY_REVIEW_MAX_RETRIEVABILITY` | `ReviewPlannerV2Test.an exam pulls a card forward only below the retrieval ceiling` |

**研究 §5 的原始建议把 `REPEATED_MISTAKE` / `KC_MASTERY_DROP` 也纳入该门，本轮经复核后收窄到只对 `EXAM_PRIORITY` 生效**：`KC_MASTERY_DROP` 是 spec §5 的**用户规则**（"绑定知识点出现负向更新时，所有绑定题目的调度权重上升——连续传导，不是机械闸门"，`KnowledgeMasteryDropPropagationTest` 锁定），它携带的是题目自身 R 无法表达的新负向证据；`REPEATED_MISTAKE` 来自"同一题被重复拍入"的持久信号（`captureOccurrenceCount`），同理。把这两者按 R 关掉会让"知识点刚退化/反复出错但该题本身还新鲜"的题不再被提前复习——属产品回退。`CLOCK_ANOMALY` / `CALIBRATION_CHECK` 保持无条件（完整性检查）。

## 2. 复核后**不成立**的条目

- **研究 §3"答对统一权重 0.15，未区分是否用提示"**：前提不成立。知识点复习通道
  （`submitKnowledgeQuizFeedback` → `MasteryWriteGate`）**没有提示/看答案通道**，不存在
  "提示后答对"这一状态；而题目复习通道已经分离：`MasteryEvidencePolicy` 把带提示/重试的
  答对映射为 `CORRECT_AFTER_HINT` / `CORRECT_ON_RETRY`，`FsrsEvidenceRatingMapper` 将其
  判为 `HARD`，`LearningProjector` 也只在 `positive && evidence.isIndependent` 时追加独立
  正确观测。故**无需改动**，改为在报告里修正该条描述。
- **研究 §8"训练/推理分布不一致"**：与 §1 是同一问题，已由本轮改动 1 消除。

## 3. 后续观察项（已实现，但需真实数据检验）

- **CMRR 参考值**（研究 §5）：实现为简化模拟（不含新卡引入、学习步与逐评分耗时表），
  样本 < 20 张卡时返回 null；仅在设置页显示，不自动改设置。真实数据积累后应对比
  实际复习量，确认推荐值不误导。
- **w15 门槛 100**（研究 §8）：数值取"足以辨识一个系数"的量级判断，非实证标定；
  若真实用户长期达不到，w15 会一直保持默认值——这比在空数据上拟合更安全。
- **同 KC 组块豁免**（研究 §2）：只在 `smoothedMastery < 0.6` 时生效，且仍受
  "同 item family 不连排"硬约束；需观察低掌握度会话的实际题面重复度。

## 4. 未找到证据、明确不得当作事实的项（沿用研究 §10）

leech 阈值/处理策略、掌握阈值具体数值、w15/w16 的受控实验证据、考试场景 desired
retention 最优值、"80% 掌握标准"的一手出处——均无一手证据，禁止在代码注释或产品文案里
写成"科学研究表明"。

---

## 5. 复核方式（可重跑）

```
./gradlew :core:domain:testDebugUnitTest :core:data:testDebugUnitTest --rerun-tasks
```

覆盖本文件所有"已实现"项的测试类：
`ReviewPlannerV2Test`、`KnowledgeReviewQueueTest`、`RoomBackedReviewRatingTest`、
`FsrsScheduleMathTest`、`ModelTaskContractRegistryTest`。
