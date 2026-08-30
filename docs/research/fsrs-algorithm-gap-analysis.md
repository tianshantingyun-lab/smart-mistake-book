# 深度研究简报：FSRS-6 调度与掌握度建模算法的缺口

**Depth**: deep
**Date**: 2026-08-30
**研究对象**: `core/domain` 的 FSRS-6 调度 + 掌握度建模 + 选题机制
**权威源**: py-fsrs `fsrs/scheduler.py`、fsrs-rs `src/model.rs`（open-spaced-repetition，2026-08-30 拉取）

---

## 执行摘要

算法的**架构设计是优秀的**——它正确地把「遗忘曲线（FSRS-6 幂律）+ 证据→评级映射 + 确定性账本投影 + 掌握度驱动的选题 + kill-switch 评估」五层拆开，且每条科学依据都标注了来源。但代码层存在**一处全局污染的正确性 bug（off-by-one）**、**一处语义偏差（日历日 vs 24h）**，以及**三处与规格自述不符/未达质量的实现**。最严重的是 off-by-one——它被一个自洽但错误的单元测试锁死，让 Good（最常见评级）的难度在每次复习时被错误地上调。

---

## 缺口 1【已确认为真实 bug，严重】FSRS 公式 off-by-one

### 证据链

py-fsrs 官方 `Rating` 是 1-based IntEnum（`fsrs/rating.py`）：
```python
class Rating(IntEnum):
    Again = 1; Hard = 2; Good = 3; Easy = 4
```
fsrs-rs 官方（`src/model.rs`）的公式全部用 1-based `rating`（`rating: f32` = 1..4）。

Kotlin `FsrsRating` 枚举 `ordinal` 是 0-based（AGAIN=0…EASY=3）。两处需要「1-based rating」的公式用了 `rating.ordinal`：

**位置 A** — `FsrsScheduleMath.kt:152` `nextDifficulty`：
```kotlin
val delta = -(parameters[6] * (rating.ordinal - 3))
```
官方：`delta_d = -w[6] * (rating - 3.0)`。Good 官方 delta=0（难度不变）；Kotlin `GOOD.ordinal(2)-3=-1` → delta=+w[6]=+3.0194（**难度每次错误上调 3.0194 的线性阻尼项**）。Easy 官方降难度，Kotlin 里 Easy delta=0（难度不变，丢失 Easy 的难度下降奖励）。

**位置 B** — `FsrsScheduleMath.kt:101` `shortTermStability`：
```kotlin
val multiplier = exp(parameters[17] * (rating.ordinal - 3 + parameters[18])) * ...
```
官方：`(w[17]*(rating-3+w[18])).exp()`。Good 官方指数=0，Kotlin `2-3=-1` → 同日稳定性乘子系统性偏低。

**位置 C（错误被测试锁死）** — `FsrsScheduleMathTest.kt:128`：
```kotlin
val delta = -(w[6] * (FsrsRating.GOOD.ordinal - 3))  // 注释却写「py-fsrs 是 G-3」
```
测试注释声称官方公式 `G-3`，但代码用 `ordinal-3`，且断言二者相等——实现和测试一起偏离官方，形成自洽错误。

### 影响面

`nextDifficulty` / `shortTermStability` 是 `FsrsMemoryUpdateModel.updateMemory`（`MemoryUpdateModel.kt`）的底层，后者又驱动 `LearningProjector.projectMemory`（`LearningProjector.kt`）和回测 harness `SchedulingReplay.predict`（`SchedulingEvaluation.kt`）——三条路径共用。**污染贯穿生产调度 + 回测评估 + 参数优化**。

### 修复

两处 `rating.ordinal - 3` → `rating.ordinal - 2`（等价于 1-based 的 `rating - 3`），并同步修 `FsrsScheduleMathTest.kt:128` 的表达式。注意：`initialStability`（`parameters[rating.ordinal]`）和 `initialDifficulty`（`exp(parameters[5]*rating.ordinal)`）是**正确**的——因为 `G-1 = ordinal`，这两处无需改。

---

## 缺口 2【真实，中等】「同日/跨日」用 24h 毫秒地板，非 spec 的 UTC 日历日

spec §2.1 定义 `t = 距上次复习天数（UTC 日历日差分）`，§2.15 强调「每卡每日至多一次长程更新」。py-fsrs 官方用 `(review_datetime - card.last_review).days`（**跨午夜即 +1**）。

但实现用毫秒地板：
- `MemoryUpdateModel.kt:73`：`floor((effectiveAt - lastReviewedAt)/DAY_MILLIS)`
- `ForgettingCurve.kt:61`：`floor(elapsedMillis/DAY_MILLIS)`

**影响**：昨晚 23:50 复习、今早 00:20 复习（间隔 30 分钟但跨午夜）——官方算 `t=1` 走长程更新，Kotlin `floor(30min/24h)=0` 走同日 short-term 分支。**跨午夜但不满 24h 的复习被错误当同日**，违反 spec §2.15 的日历日语义，也影响毕业/leech 的 `consecutiveCrossDay` 计数。

### 修复方向

需要 `elapsedDays = 日历日差`（用 `StudyDayContext.epochDay` 或 UTC 日界），而非毫秒地板。这是比 off-by-one 更大的改动（要动时间语义 + 可能动 `StudyDay` 传导），建议单独立项。

---

## 缺口 3【真实，中等】优化器未实现 spec 承诺的 w15/w16 解锁

spec §2.11b 写明「解锁 w15/w16 的规则：≥5k 样本且验证增益 >2%（**规则已写**，实现待数据）」。但 `FsrsParameterOptimizer.optimize`（`SchedulingEvaluation.kt:369-373`）的 `fittedIndices` 只有两档：
```kotlin
if (sampleCount < MIN_SAMPLES_FOR_FULL_FIT) (0..5).toList()   // <64 只拟合初始稳定性
else (0..14).toList() + listOf(20)                              // ≥64 拟合 w0..w14 + w20
```
**w15（hard penalty）/ w16（easy bonus）永不进入拟合面**，与 spec「规则已写」自述矛盾。spec 承诺的「≥5k + 验证增益>2%」解锁条件在代码里不存在。

### 影响

hard/easy 两个惩罚系数永远是默认值，无法个性化。这不是正确性 bug（默认值可用），但**是未完成的承诺**。

---

## 缺口 4【真实，次要】优化器参数边界与官方不一致，注释误导

`SchedulingEvaluation.kt:333-340` 的 `LOWER_BOUNDS`/`UPPER_BOUNDS` 注释写「mirroring the py-fsrs parameter validation ranges」，但值对不上：

| 参数 | Kotlin UPPER | py-fsrs/fsrs-rs 官方 |
|---|---|---|
| w0–w3（初始稳定性） | `10.0` | `INITIAL_STABILITY_MAX = 100.0` |
| w7（难度均值回归） | `0.9` | `0.75` |

初始稳定性上界 10 天 vs 官方 100 天，会让优化器无法拟合出「长初始稳定性的卡」。需确认是有意收紧（产品刻意保守）还是抄错。**无论哪种，注释「mirroring」是错的**。

---

## 缺口 5【真实，次要】`LogDurationModel` fallback 语义错误

`LogDurationModel.kt:100-102`：
```kotlin
fun estimateSeconds(): Double = (
    logEma ?: GLOBAL_PRIOR_SECONDS   // GLOBAL_PRIOR_SECONDS = 60.0
).let { exp(it) }
```
`logEma` 存 `ln(时长)`，fallback 到 `60.0` 时 `exp(60.0)` = 天文数字，而非「60 秒」。正常 `record()` 后 `logEma` 非 null 不触发，但一旦落到 fallback 是灾难。修复：`logEma ?: ln(GLOBAL_PRIOR_SECONDS)`。

---

## 不实用/质量未达最高的地方（非 bug，但值得改）

### 6. `ReviewPlannerV2` 与 `ReviewPlanner`(V1) 的打分逻辑大量重复

两个文件里 `scoreCandidate`（约 200 行）、`canonicalPlanFingerprint`（约 60 行）、`difficultyBand`、权重常量**几乎逐字重复**（V2 只是 V1 + beam search + confusable + prereq）。维护成本高：改一个权重要同步两处，容易漂移。spec §8 说 V2 是「选题升级」，但生产到底用哪个 planner？若是 V2，V1 应退役或只作兼容。

### 7. `ReviewReason` 枚举膨胀但无优先级契约

`ReviewReason` 有 DUE_RECALL_RISK / WEAK_KNOWLEDGE / CALIBRATION_CHECK / CONFUSABLE_PAIR / AVOIDANCE_SIGNAL / KC_MASTERY_DROP / EXAM_PRIORITY / PREREQ_GAP / LONG_WAITING / GRADUATED_MAINTENANCE / CLOCK_ANOMALY / REPEATED_MISTAKE / MISSING_KNOWLEDGE_EVIDENCE / RECENT_LAPSE / STALE_KNOWLEDGE / CONFLICTED_KNOWLEDGE / NEWLY_ADDED… 十几个理由，但「队列解释文案」需要的是**精简的、学生可读的**理由，而当前 reasons 是内部枚举堆叠（一道题可同时挂 5+ 个 reason）。spec §6.3「队列解释文案」实际怎么把这一堆 reason 压缩成一句人话，没有实现规范。

### 8. `TimeOfDayCalibrator.correctedWeight` 的 guess 折扣只对「答对」生效，slip（慢但错）未建模

spec §2.14 提到 guess/slip 后验，但代码只实现了「快答对 = 猜，降权」(`correctedWeight` 只看 `isCorrect && lowerTailFraction`)，**没有 slip 方向**（慢且错 = 真不会，应加重负证据？）。这是半成品。

### 9. `AttentionSignal.attentionFactor` 系数是「工程先验待校准」，但没有任何校准通道

`PER_EXTRA_SWITCH_PENALTY=0.12`、`PER_AWAY_GRADE_PENALTY=0.05` 注释自认「工程先验待校准」，但 review_log 已采集 `away_millis`/`switch` 数据（spec §2.14a），却没有类似 `SourceCalibration`/`PlannedReasonCalibration` 的**注意力系数校准**机制——数据在采，参数永不校准。

---

## 外部权威源对照结论

| 公式 | 状态 |
|---|---|
| 幂律遗忘曲线 `R(t,S)=(1+FACTOR·t/S)^(−w20)` | ✅ 正确（py-fsrs + fsrs-rs 一致）|
| 间隔反解 `I=(S/FACTOR)(r*^(1/decay)−1)` | ✅ 正确 |
| 初始稳定性 `w[G−1]` | ✅ 正确（`rating.ordinal` = G−1）|
| 初始难度 `w4−e^{w5(G−1)}+1` | ✅ 正确 |
| **短期稳定性 `e^{w17(G−3+w18)}`** | ❌ **off-by-one**（用了 ordinal）|
| **难度均值回归 `−w6(G−3)`** | ❌ **off-by-one**（用了 ordinal）|
| 遗忘分支 `min(long, S/e^{w17·w18})` | ✅ 正确 |
| 成功分支 hard/easy penalty | ✅ 正确（`rating==HARD/ordinal` 判断对）|
| 遗忘曲线 FACTOR = 0.9^(1/decay)−1 | ✅ 正确 |

---

---

## 第三消费方专项：掌握度 → 讲题侧重点（本次补查）

用户指出算法本质是「量化掌握度」，它有三个下游：**复习先后顺序**（选题）、**复习频率**（排期）、**讲题侧重点**（tutor）。前两个已覆盖，本节补第三条链的缺口。

### 数据链

```
LearningProjector.projectMastery  →  KnowledgeMasteryState（KC 级，含 status/保守分/evidenceMass）
        ↓ RoomBackedStudyExperienceRepository.toProfileOverview
StudyKnowledgeSummary（weaknesses = status != MASTERED；strengths = MASTERED）
        ↓ TutorModelTaskPolicy.toTutorKnowledgeEvidence
TutorKnowledgeEvidence（level / independentCorrectLowerBound / evidenceMass / recency）
        ↓ OpenAiModelTaskAdapters.tutorPlanPrompt / tutorRespondPrompt
讲题侧重点（prompt 规则 5/7/13）
```

### 缺口 10【真实，中等】CONFLICTED（假掌握）是最该讲题切入的信号，但 prompt 无针对性指令

`KnowledgeMasteryState` 里精心构造了 `CONFLICTED` 状态（`LearningProjector.projectMastery` 的 `startsConflict`：独立错误 + 之前已 mastered），语义是「学生以为会、但最近独立错了」——**这恰是讲题最该优先纠错的点**。

但：`TutorKnowledgeEvidence.level` 确实把 `CONFLICTED` 映射并传递了（`toTutorEvidenceLevel` 正确），**prompt 规则却只写了 `MASTERED`（已掌握不重复问）和题级 `STALE`（历史提示）的处理**，对 KC 级 `CONFLICTED` 没有一句指令告诉模型「这种要优先讲、要纠正错误认知」。

结果：模型拿到 `level=CONFLICTED` 的 evidence，但 prompt 没教它这是什么、该怎么用，只能靠模型的通用理解去猜。对「纠正错误认知」这个讲题最高价值场景，算法提供了信号但**没把信号翻译成可执行的讲题策略**。

### 缺口 11【真实，次要】`weaknesses` 把四种异质状态混为一谈

`toProfileOverview` 里 `weaknesses = status != MASTERED`，把 `LEARNING`（正常薄弱）/`CONFLICTED`（假掌握）/`STALE`（证据过期）/`UNKNOWN`（冷启动）**四种语义完全不同的状态**混进同一个 `weaknesses` 列表，只按 `conservativeMasteryScore` 排序。

- `CONFLICTED` → 该纠错
- `STALE` → 该校准，不是"弱"
- `UNKNOWN` → 冷启动，不能声称"弱"（prompt 规则 7 却要求"缺少或过期时不得声称证据不足"——自相矛盾：UNKNOWN 被当 weakness 送进去，又不许说不知道）

下游 `toTutorKnowledgeEvidence` 用 `weaknessPriority` 给 CONFLICTED 最高优先级（0），这个优先级**只影响排序，不影响 prompt 语义**。分类混淆在「选题」链影响不大（都是"要复习"），但在「讲题侧重点」链是实质误导。

---

## 建议的修复顺序

1. **缺口 1（off-by-one）**：立刻修，两处 + 一处测试，最小改动、最高影响、覆盖污染面最大。
2. **缺口 5（LogDurationModel fallback）**：一行修复，防灾难性 fallback。
3. **缺口 10（讲题侧重点 CONFLICTED 无指令）**：在 `tutorPlanPrompt`/`tutorRespondPrompt` 的 evidence 规则里补「CONFLICTED = 近期独立错误、优先纠错」的指令——这是讲题最高价值场景。
4. **缺口 4（参数边界）**：与用户确认是「有意收紧」还是「抄错」，再定。
5. **缺口 2（日历日 vs 24h）**：需动时间语义，单独立项，涉及 `StudyDayContext` 传导。
6. **缺口 3（w15/w16 解锁）**：补 spec 承诺的 ≥5k+增益>2% 解锁逻辑。
7. **缺口 11 + 6/7/8/9（质量项）**：作为后续优化 backlog，不阻塞。

## 风险与未决

- 缺口 1 修复后会改变所有既有卡的投影结果（难度轨迹变了），需评估对存量数据的影响——但 FSRS 是 feature-flag 双实现，回测 harness 会给出新/旧 log-loss 对照。
- 缺口 2 需要确认「日历日」边界该用 UTC 还是 learner 本地时区（spec 写 UTC，但 `StudyDayContext` 是 learner 本地）。

## 来源

- py-fsrs `fsrs/scheduler.py`（open-spaced-repetition/py-fsrs @ main，2026-08-30）
- py-fsrs `fsrs/rating.py`（确认 Rating 是 1-based IntEnum）
- fsrs-rs `src/model.rs`（open-spaced-repetition/fsrs-rs @ main，2026-08-30）
- 项目内 `docs/specs/mastery-scheduling-spec.md`（v1，2026-08-29）
