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
| 4 | **考试**理由提前拉题**仅当 R < 0.8** | FSRS 稳定性增益 `e^{w10(1−R)}−1`：R≈1 时复习收益趋零；Rohrer & Taylor 2005 过度学习低效。**且这与 spec §2.17 一致**——考前队列本就定义为 `R < r*_exam` 的题 | `ReviewPlannerV2` 与 `ReviewPlanner`（V1 回滚路径同步）+ `EARLY_REVIEW_MAX_RETRIEVABILITY` | `ReviewPlannerV2Test.an exam pulls a card forward only below the retrieval ceiling` |

**研究 §5 的原始建议把 `REPEATED_MISTAKE` / `KC_MASTERY_DROP` 也纳入该门，本轮经复核后收窄到只对 `EXAM_PRIORITY` 生效**：`KC_MASTERY_DROP` 是 spec §5 的**用户规则**（"绑定知识点出现负向更新时，所有绑定题目的调度权重上升——连续传导，不是机械闸门"，`KnowledgeMasteryDropPropagationTest` 锁定），它携带的是题目自身 R 无法表达的新负向证据；`REPEATED_MISTAKE` 来自"同一题被重复拍入"的持久信号（`captureOccurrenceCount`），同理。把这两者按 R 关掉会让"知识点刚退化/反复出错但该题本身还新鲜"的题不再被提前复习——属产品回退。`CLOCK_ANOMALY` / `CALIBRATION_CHECK` 保持无条件（完整性检查）。

## 2. 复核后**不成立**的条目

- **研究 §3"答对统一权重 0.15，未区分是否用提示"**：前提不成立。知识点复习通道
  （`submitKnowledgeQuizFeedback` → `MasteryWriteGate`）**没有提示/看答案通道**，不存在
  "提示后答对"这一状态；而题目复习通道已经分离：`MasteryEvidencePolicy` 把带提示/重试的
  答对映射为 `CORRECT_AFTER_HINT` / `CORRECT_ON_RETRY`，`FsrsEvidenceRatingMapper` 将其
  判为 `HARD`，`LearningProjector` 也只在 `positive && evidence.isIndependent` 时追加独立
  正确观测。故**无需改动**，改为在报告里修正该条描述。
- **研究 §8"训练/推理分布不一致"**：与 §1 是同一问题，已由本轮改动 1 消除。

## 3. 延后（有证据但本轮不改，附理由）

- **研究 §2 低先验知识豁免 `SAME_KC_EXHAUSTION_PENALTY`**（Brunmair & Richter 2019 数学
  g=0.34；Hwang；Nemeth & Lipowsky 2023）：证据方向可信但调节变量结论弱于"相似度结构"，
  且会覆盖 anti-oscillation 规范（spec 2.18，本身是审计修复项）、需要把 KC 平滑掌握度带进
  `ScoredCandidate` 才能按研究建议的条件（`smoothedMastery < 0.6`）判定。现有
  `CONFUSABLE_MASTERY_GAP` 配对加分已命中"类别间高相似"这一最强调节变量。→ 记录为下一步。
- **研究 §5 CMRR（最优保持率模拟）**：需要足够样本量才能给出可辨识结果，且官方实现
  （fsrs-rs `optimal_retention.rs`）依赖真实复习时长分布。当前 `NOT_MEASURED` 状态下引入
  会给用户一个不可信的推荐值。→ 待真实 Provider/设备数据积累后单独排期。
- **研究 §7 EASY 不可达 → w16 永不生效**：方案 B（固定 w16=1.0 并移出拟合集）会改变参数
  向量长度与已存优化参数（`optimizedParameters: DoubleArray`）的兼容性，收益仅是"参数表
  不误导"，不消灭任何用户可见失败。→ 延后，等参数持久化加版本号时一并处理。
- **研究 §8 w15/w16 解锁加"每档评分样本数"条件**：同上，属优化器可辨识性改进，非缺陷。
- **研究 §10/§11（毕业 3 次跨日成功、掌握门槛 0.85/2.0/2 families/2 days）**：研究结论为
  "方向保留、无实证标定"，不构成改动依据，保持现状并在代码注释中标注为设计选择。

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
