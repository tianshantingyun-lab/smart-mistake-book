# 间隔重复调度器中 delta_t（elapsed_days）的计量口径研究

研究日期：2026-09-11
研究范围：FSRS 官方实现（py-fsrs、fsrs-rs）、Anki 集成层、srs-benchmark 实证，以及本仓库现有口径的偏差评估
核实方式：全部结论以 raw.githubusercontent.com 拉取的原始源码逐行核对；不采用博客、二手总结或搜索摘要

## 证据等级标记

- `[已核实]`：来自官方源码逐行阅读，且关键结论有跨实现或多处独立印证
- `[单一来源待验证]`：仅一处来源支持，或仅本仓库内部一致，未获外部独立印证
- `[未找到]`：经多渠道、多关键词检索后仍无一手来源
- `[分析]`：本文基于已核实事实的推导，非来源直接陈述

---

## 1. py-fsrs：elapsed_days 如何计算

来源仓库：`open-spaced-repetition/py-fsrs`，版本 `6.3.2`（`pyproject.toml:6`）`[已核实]`

**检索率路径** — `fsrs/scheduler.py:232`（函数 `get_card_retrievability`）：

```python
elapsed_days = max(0, (current_datetime - card.last_review).days)
```

Python `timedelta.days` 对正值取下整，故这是 **24 小时向下截断**，下限 `max(0, ...)`。同日的不足 24 小时复习得到 `elapsed_days = 0`，代入遗忘曲线得 `R = 1`。

**调度路径** — `fsrs/scheduler.py:269-270`（函数 `review_card`）：

```python
days_since_last_review = (
    (review_datetime - card.last_review).days if card.last_review else None
)
```

此处**没有** `max(0, ...)` 包裹，但因为 `review_datetime` 默认取当前 UTC 时间，正常时钟下非负。

**关键否定结论** `[已核实]`：py-fsrs 全库**不存在**对 `elapsed_days` / `days_since_last_review` 的 `max(1, ...)` 下限。`fsrs/scheduler.py` 中唯一的 `max(next_interval, 1)`（L690 附近）作用于**输出间隔**，不是 `delta_t`。这一点容易误读，审计时须区分。

**时区约束** `[已核实]`：`fsrs/scheduler.py:262-265` 强制要求 `review_datetime` 为 tz-aware 且 `tzinfo == timezone.utc`，否则抛 `ValueError`。因此 py-fsrs 的"天"是 UTC 天，不受本地时区与 DST 影响。

## 2. fsrs-rs：round_elapsed_days 的语义

**重要修正** `[已核实]`：`round_elapsed_days` **只存在于 main 分支，不在任何已发布 tag 中**。逐个核对结果：

| 版本 | 文件 | `round_elapsed_days` 命中数 |
|---|---|---|
| main | `src/model_v6.rs` | 有（L243 定义，L68/L123 调用） |
| v5.0.0 | `src/inference.rs` | 0 |
| v6.5.0 | `src/inference.rs`、`src/model.rs` | 0 / 0 |
| v6.6.2 | `src/inference.rs`、`src/model.rs` | 0 / 0 |

**已发布 v6.6.2 的实际行为** `[已核实]`：
- `src/inference.rs:357`：`pub fn next_states(&self, current_memory_state, desired_retention, days_elapsed: u32)` — 入参是**整数 u32**，L371 直接 `self.step(days_elapsed as f32, ...)`。
- `src/model.rs:287`：`fn step(w: &[f32], delta_t: f32, rating: f32, state: MemoryState, nth: usize)`；L302 `if delta_t == 0.0`；L507 `new_s = new_s.mask_where(delta_t.equal_elem(0), stability_short_term)`。

即：已发布版本**不取整、不 clamp 下限**，靠调用方传整数保证 `delta_t` 为整天，`delta_t = 0` 被显式允许并触发短程分支。

**main 分支的 FSRS-6 取整** `[已核实]` — `src/model_v6.rs:243-245`：

```rust
fn round_elapsed_days<B: Backend>(t: Tensor<B, 1>) -> Tensor<B, 1> {
    t.clamp_min(0.0).add_scalar(0.5).int().float()
}
```

语义为 **+0.5 后取整（半值向上）**，仅 clamp 到 `≥ 0`。调用点：`src/model_v6.rs:68`（`update_state`，注释 L67 原文 "FSRS-6 stays day-based: keep f32 transport, but round elapsed days to nearest day"）、L123（`power_forgetting_curve`）、L231（标量版 `t.max(0.0).round()`）。

行为由测试锁定 — `src/inference.rs:1748-1775`（`test_next_states_fsrs6_rounds_fractional_elapsed_days`）断言 `delta_t = 0.0` 与 `0.49` 结果相同、`0.51` 与 `1.0` 结果相同。

**FSRS-7 对照** `[已核实]`：`src/inference.rs:521-532` 的 `next_states_with_elapsed_days` 文档注释原文："FSRS-6 rounds elapsed days to nearest whole day internally; FSRS-7 keeps fractional elapsed days." 测试 `test_next_states_accepts_fractional_elapsed_days`（L1726）确认 FSRS-7 下 `0.0` 与 `0.5` 结果不同。分数日支持是 FSRS-7 的新增能力，FSRS-6 没有。

## 3. delta_t = 0 是否被允许

`[已核实]` **两者都显式允许**：

- py-fsrs：`max(0, ...)` 保证下界为 0，且 `days_since_last_review < 1` 把 0 路由到短程分支。
- fsrs-rs：已发布版 `delta_t == 0.0`（`model.rs:302`）与 `delta_t.equal_elem(0)`（`model.rs:507`）都是显式分支条件；main 版 `round_elapsed_days` 只 clamp 到 `≥ 0`。

**两者均无 `max(1, ...)` 下限** `[已核实]`。遗忘曲线在 `t = 0` 处取 `R = 1`，这是设计意图而非边界缺陷。

## 4. short-term / long-term 分支的判定条件

`[已核实]` 判定量就是 `delta_t`（等价的 `elapsed_days`），**不是**调度状态、不是复习次数、不是卡片 state。

**py-fsrs** — 三处同构判断（`fsrs/scheduler.py:284` Learning 态、`:370` Review 态、`:420` Relearning 态）：

```python
if days_since_last_review is not None and days_since_last_review < 1:
```

即"UTC 不足 24 小时"。

**短程公式** — `fsrs/scheduler.py:697-714`（`_short_term_stability`）：

```python
short_term_stability_increase = (
    math.e ** (self.parameters[17] * (rating - 3 + self.parameters[18]))
) * (stability ** -self.parameters[19])
# Hard/Good/Easy 时强制 max(..., 1.0)
short_term_stability = stability * short_term_stability_increase
```

对应数学式 `S' = S · e^(w17·(G−3+w18)) · S^(−w19)`。命中此分支时**不调用** `_next_stability`／`_next_recall_stability`。

**长程公式** — `fsrs/scheduler.py:796-803`（`_next_recall_stability`）：

```python
return stability * (
    1 + (math.e ** (self.parameters[8])) * (11 - difficulty)
      * (stability ** -self.parameters[9])
      * ((math.e ** ((1 - retrievability) * self.parameters[10])) - 1)
      * hard_penalty * easy_bonus
)
```

**注意** `[已核实]`：w17/w18 也出现在**遗忘项**中，`fsrs/scheduler.py:776-783`（`_next_forget_stability`）取 `min(长程项, stability / e^(w17·w18))`。因此 w17/w18 不是短程分支专属。

**fsrs-rs 一致性** `[已核实]`：main 的 `src/model_v6.rs:82` 与已发布 `src/model.rs:507` 均为 `mask_where(delta_t.equal_elem(0), stability_short_term)`，与 py-fsrs 同构。**差别只在"多久算 0"**：py-fsrs 是 UTC 不足 24h，fsrs-rs main（FSRS-6）是四舍五入后为 0（不足 0.5 天），已发布 fsrs-rs 由调用方直接给 0。

**Anki 的额外门控** `[已核实]`：`rslib/src/scheduler/states/card_state.rs:517-521`，仅当 `params[17] > 0 && params[18] > 0` 时才允许 FSRS 接管短程调度。

## 5. Anki：elapsed_days 与 rollover hour

`[已核实]` 数据流：

1. `rslib/src/scheduler/states/card_state.rs:498-500`：
```rust
let days_elapsed = if let Some(last_review_time) = card.last_review_time {
    timing.next_day_at.elapsed_days_since(last_review_time) as u32
} else { ... };
```
2. `rslib/src/timestamp.rs:31-33`：
```rust
pub fn elapsed_days_since(self, other: TimestampSecs) -> u64 {
    (self.0 - other.0).max(0) as u64 / 86_400
}
```
即 `floor((next_day_at − last_review) / 86400)`，整数、下限 0，**以 `next_day_at` 为锚点**。
3. `rslib/src/scheduler/timing.rs:38-53`：`next_day_at` 由 `rollover_datetime(now_datetime, rollover_hour)` 推出；L57-63 `rollover_datetime` 把时间归到 rollover 小时。
4. `anki-manual/src/preferences.md:52-59`：rollover 默认 **4AM**，原文 "The default setting of 4AM ensures that if you're studying around midnight, you won't have two days' worth of cards shown to you in one session... Note that the start of the next day is relative to your current time zone."

**关键结论** `[已核实]`：Anki 传给 FSRS 的 `elapsed_days` **就是**它的"学习日"概念，`u32` 直传、模型内不再取整，不存在"学习日一个口径、FSRS delta_t 另一个口径"的双轨。它做的是**以 rollover 为锚的日界计数**，而非 24 小时截断。

**推论** `[分析]`：当 `rollover_hour = 0` 时，`floor((next_midnight − last_review)/86400)` 在数学上恒等于"学员本地日历日差"。因此本仓库采用的本地日历日差**不是第三种异类**，而是 Anki 口径在 rollover=0 处的特例。审计报告"与两套官方参考实现都不同"的说法，对 Anki 一侧不成立。

**同日处理** `[已核实]`：`anki-manual/src/deck-options.md:405` 建议所有 learning/relearning steps 短于 1 天且能在同日完成，并明确 "23h is not recommended"；`deck-options.md:178-183` 说明跨日界的 step 会被自动折算为天。

## 6. DST 与时区边界

`[已核实]` **Anki 对 collection 级 `days_elapsed` 用本地日历日**：`rslib/src/scheduler/timing.rs:69-80` 的 `days_elapsed` 使用 `end_date.num_days_from_ce() - start_date.num_days_from_ce()`，并有测试注释（L279-281）：

```rust
// a change to DST, but the number shouldn't change
assert_eq!(elap(crt, now, mdt_offset, mdt_offset, 4), 507);
```

即已发布实现有意保证 DST 切换不改变天数计数。

`[分析]` **per-card 的 `elapsed_days_since` 是纯秒差 floor，锚点 `next_day_at`，不享受上述 DST 保护**。我的推导：秋令回拨的 25 小时那天，若上次复习落在 rollover 后 1 小时内，`floor` 会多算 1 天。此条为本文推导，**未找到**官方文档或 issue 讨论该情形 `[未找到]`。

`[已核实]` **py-fsrs 不受 DST 影响**：强制 UTC-aware（`fsrs/scheduler.py:262-265`）。

## 7. srs-benchmark 的实证情况

`[已核实]` **版本语义** — `README.md:44-46` 原文要点：
- FSRS-5：同日复习数据**仅用于训练，不用于评估**；
- FSRS-6：改进同日复习公式，引入控制遗忘曲线平坦度的可训练参数；
- FSRS-7："It is the only version that can give realistic predictions of probability of recall for same-day reviews"，因其唯一使用分数区间长度。

`[已核实]` **已有整数日/分数日的 A/B 开关**：
- `README.md:304-305`：`--short` 包含同日复习；`--secs` "Use `elapsed_seconds` as the interval instead of days"；
- `README.md:319-320`：`--no_test_same_day` / `--no_train_same_day` 用于排除 `elapsed_days=0`；
- `features/base.py:120-133`：默认 `delta_t = elapsed_days`（整数，`max(0,·)`）；`--secs` 下 `delta_t_secs = elapsed_seconds / 86400`，同样 `max(0,·)`。

`[未找到]` **没有任何基准或论文直接比较"24h 截断 vs 四舍五入 vs 日历日"三种口径**。srs-benchmark 的 `--secs` 切换的是"整天 vs 分数天"，不是三种取整约定的对比。

`[未找到]` **FSRS-6 正式论文不存在**。arXiv 全库检索（`all:"Free Spaced Repetition Scheduler"`）无命中；Semantic Scholar 命中均无关。FSRS-6 的一手描述目前仅有 `awesome-fsrs` wiki 的 `The-Algorithm.md`（给出 `S' = S·e^(w17(G−3+w18))·S^(−w19)`、`R(t,S) = (1 + factor·t/S)^(−w20)`、`factor = 0.9^(−1/w20) − 1` 及 21 个默认参数）与 srs-benchmark README。

## 8. 本仓库现状核查

### 8.1 已核实的实现事实

| 事实 | 位置 | 等级 |
|---|---|---|
| `lastReviewedEpochDay` 默认值为 UTC 近似 `millis / 86_400_000L` | `core/model/src/main/kotlin/.../LearningState.kt:502` | `[已核实]` |
| `elapsedCalendarDays = eventEpochDay − previous.lastReviewedEpochDay` | `core/domain/.../LearningProjector.kt:845-847` | `[已核实]` |
| `crossDay` 判据 `elapsedCalendarDays >= 1.0` | `LearningProjector.kt:859` | `[已核实]` |
| 短程分支判据 `elapsedDays < 1.0` → `shortTermStability` | `core/domain/.../MemoryUpdateModel.kt:79-81` | `[已核实]` |
| `projectTutorAnswerExposure` **未传** `lastReviewedEpochDay`，落入 UTC 默认值 | `LearningProjector.kt:786-825` | `[已核实]` |
| `TutorAnswerExposureOutcome` **没有** `studyDay` 字段（对照 `AnswerRevealOutcome` 有） | `LearningState.kt:358-376`；`LearningProjector.kt:779` 对照 | `[已核实]` |
| v42 迁移用 UTC 日序近似回填存量行 | `core/database/.../CalendarDayMigration.kt:22-26` | `[已核实，已在 spec 中声明为一次性近似]` |

### 8.2 曝光路径缺陷的影响面（闭合影响链）

团队补充的事实 `[已核实]`：`TutorAnswerExposureOutcome` 不带 `studyDay`，`projectTutorAnswerExposure` 又未显式传 `lastReviewedEpochDay`，于是 `LearningState.kt:502` 的默认值把 **UTC epoch day** 写入该字段。UTC+8 学员本地 07:00 看答案时，UTC 日序比本地日序小 1；当天本地 20:00 复习时 `eventEpochDay` 取的是本地日序（`studyDay.epochDay`），二者相减得 1，`crossDay = true`，`delta_t` 记为 1 而非 0。

**这不是单点缺陷，有两条传播路径** `[已核实]`：

1. **下一次复习的 delta_t**：`projectMemory` 读 `previous.lastReviewedEpochDay`（被污染的 UTC 值）与本地 `eventEpochDay` 相减 → 走错分支，`R` 被低估 → `S` 被高估（乐观方向）。
2. **`review_log.delta_t_days`**：`core/data/src/main/kotlin/.../ReviewLogSink.kt:63` 同样用 `studyDay.epochDay - priorMemory.lastReviewedEpochDay` 计算，同一错配被写进 review_log。这条更值得警惕——**review_log 正是后续 FSRS 参数优化器的训练数据**，污染会进入离线拟合。
3. **持久化放大**：与 v42 迁移的一次性 UTC 近似不同，曝光路径是**运行时持续重新注入**该近似值，每次曝光都会覆盖成 UTC 日序。

**对称性证据** `[已核实]`：兄弟路径 `projectAnswerReveal`（`LearningProjector.kt:779`）传的是 `outcome.studyDay.epochDay`，是正确的本地日序。缺陷仅存在于曝光路径，说明这是遗漏而非设计选择。

**测试缺口** `[已核实]`：`LearningProjectorTest.kt` 等测试未覆盖"曝光后同日复习"的跨日误判场景。

**规格内部冲突** `[已核实]`：`docs/specs/mastery-scheduling-spec.md:26`（§2.1）仍写 "`t`=距上次复习天数（**UTC 日历日差分**）"，而同一文件 2026-08-30 修复轮记录明确改为 "learner 本地日历日差（用户定则：本地时区，中国默认）"。§2.1 未同步更新，属文档技术债；实际权威口径是后者。

### 8.3 该偏差在三种候选口径下的存活性

| 口径 | 曝光缺陷是否仍存在 | 原因 |
|---|---|---|
| **A. 本地日历日差**（现状） | **是，必然存在** | 缺陷正是"写入用 UTC、读取用本地"的不一致；口径本身不修复它 |
| **B. Anki 式 `floor((next_rollover − last_review)/86400)`** | **否，结构性消除** | 不再持久化裸 epoch day；delta_t 由时间戳 + 显式锚点算出，无"派生字段被另一定义覆写"的空间 |
| **C. py-fsrs 式 24h 截断** | **否，但换了错** | 同样不依赖持久化 epoch day，故缺陷消失；代价是重新引入 §2.15 明令要修的"跨本地午夜但不足 24h 被误判为同日" |

要点 `[分析]`：**该缺陷本质上不是"选错口径"，而是"派生字段的双定义"**——一处按 UTC 写、一处按本地读。A 保留了这个字段，因此保留了这个失败类别；B/C 取消该字段的独立地位，从结构上消灭它。这解释了为什么只改判据公式无法根治。

---

## 9. 结论与方案建议

**结论一** `[已核实]`：本仓库的"本地日历日差"**不是**与两套官方实现都不同的第三种口径。它与 Anki 口径同源（rollover=0 时数学恒等），真正不同于它的是 py-fsrs 的 UTC 24h 截断。审计对 Anki 一侧的判断需要修正。

**结论二** `[已核实]`：本仓库沿用 py-fsrs 的 `< 1` 短程判据与 short_term 公式，分支结构与两套官方实现同构，**分支逻辑本身没有问题**；问题在于喂给该判据的 `elapsedCalendarDays` 可能被污染。

**结论三** `[分析]`：审计"delta_t 偏大 → R 偏小 → `e^(w10(1−R)) − 1` 偏大 → S 被高估"的方向与 1 天上界成立，但**前提是事件确实走了长程分支**；若仍落短程分支则偏差不存在。当前曝光缺陷恰恰打破了这一前提，使本应同日的事件误入长程分支。

### 候选方案

**方案 A：保留本地日历日差，修复写入路径**
为 `TutorAnswerExposureOutcome` 补 `studyDay: StudyDayContext`（与 `AnswerRevealOutcome` 对称），并让 `TutorExposureEntities` 持久化该字段，曝光投影显式传 `lastReviewedEpochDay = outcome.studyDay.epochDay`。
- 代价：领域事件 + Room 实体 + schema 迁移（v42 之后再加一版）；需处理已按 UTC 写入的存量行；需补曝光后同日复习的回归测试。
- 效果：修复缺陷，但保留"派生字段可被不同定义覆写"这一失败类别，未来新增事件类型仍可能重犯。

**方案 B（推荐）：改存时间戳，读取时按显式 rollover 推导**
停止把裸 epoch day 作为独立持久化字段；`ProblemMemoryState` 只保留 `lastReviewedAtEpochMillis`，`delta_t` 在读取处按 `floor((next_rollover − last_review)/86_400_000)` 计算，rollover 取产品学习日起点（日粒度产品即本地午夜，rollover=0）。字段语义与 Anki 一手实现完全同构。
- 代价：一次跨 model/domain/data 的重构；Room 旧列下线需迁移；`ReviewLogSink` 与 `projectMemory` 两处调用点需同步改造。
- 效果：**结构性消除**本类缺陷（无派生字段可被污染），且因 rollover=0 时与现状数学等价，**不改变当前调度数值**，回归面可控。同时消除了 §2.1 与实现不一致的文档债源头。

**方案 C：改用 py-fsrs 的 UTC 24h 截断**
- 代价：重新引入 §2.15 已明确修复的"跨本地午夜但不足 24h 被误判为同日"，与日粒度会话的产品语义割裂，且与 Anki 不一致。
- 不推荐。

**推荐方案 B**，理由：(1) 它同时修掉曝光缺陷与 `review_log` 污染两条路径；(2) 与 Anki 官方实现同构，有最强一手依据；(3) 在 rollover=0 下与现行数值等价，切换风险低；(4) 从结构上消除"派生字段双重定义"这一失败类别，而不是逐个事件类型补丁。若本轮无法承担 schema 重构，可先执行方案 A 作为止血，并在文档中显式记录"待迁移到方案 B"。

**无论选哪个方案，建议同步处理**：
- 更新 `docs/specs/mastery-scheduling-spec.md` §2.1 的 "UTC 日历日差分" 表述，消除与实现冲突；
- 补覆盖"曝光（或答案揭示）后同日复习"的跨日回归测试；
- 复核 v42 迁移回填的存量 `last_reviewed_epoch_day` 在 UTC+8 下的偏移影响。

---

## 10. 未找到项（明确列出，未作推测填充）

1. `[未找到]` FSRS-6 正式论文（arXiv、Semantic Scholar 均无）。
2. `[未找到]` 直接比较"24h 截断 / 四舍五入 / 日历日"三种口径的实证基准或论文。
3. `[未找到]` DST 回拨下 Anki per-card `elapsed_days_since` 偏离的官方讨论；本文相关陈述标为 `[分析]`。
4. `[单一来源待验证]` 本仓库 spec §2.15 引用的"同日复习评估时 FSRS-6 log-loss 0.346→0.3813、HLR 0.469→0.705"数字，本次未在 srs-benchmark 公开材料中定位到原始出处，建议回溯该数字的直接来源后再作为决策依据。

---

## 11. 来源清单（均为一手源码/官方文档，附核实行号）

**py-fsrs（open-spaced-repetition/py-fsrs，version 6.3.2）**
- `fsrs/scheduler.py`：L232（elapsed_days 定义）、L262-265（UTC 强制）、L269-270（days_since_last_review）、L284/L370/L420（短程判据）、L697-714（short_term_stability）、L776-783（forget stability 短程项）、L796-803（recall stability）
- `pyproject.toml`：L6（版本号）
- 路径前缀：`https://raw.githubusercontent.com/open-spaced-repetition/py-fsrs/main/`

**fsrs-rs（open-spaced-repetition/fsrs-rs）**
- main `src/model_v6.rs`：L61-82（update_state 与 mask_where）、L123（power_forgetting_curve 取整）、L231（标量版）、L243-245（round_elapsed_days 定义）
- main `src/inference.rs`：L457-532（next_states 系列）、L1726-1745（FSRS-7 分数日测试）、L1748-1775（FSRS-6 取整测试）
- v6.6.2 `src/inference.rs`：L357、L371（整数 u32 入参）
- v6.6.2 `src/model.rs`：L287（step）、L291、L302（`delta_t == 0.0`）、L507（`equal_elem(0)`）
- 路径前缀：`https://raw.githubusercontent.com/open-spaced-repetition/fsrs-rs/{main|v6.6.2}/`

**Anki（ankitects/anki）**
- `rslib/src/scheduler/states/card_state.rs`：L498-500（elapsed_days 传参）、L517-521（短程门控）
- `rslib/src/timestamp.rs`：L31-33（elapsed_days_since 实现）
- `rslib/src/scheduler/timing.rs`：L16-18（SchedTimingToday）、L38-53（next_day_at 推导）、L57-63（rollover_datetime）、L69-80（days_elapsed）、L279-281（DST 测试断言）
- 路径前缀：`https://raw.githubusercontent.com/ankitects/anki/main/`

**Anki 官方手册（ankitects/anki-manual）**
- `src/preferences.md`：L52-59（Next day starts at，默认 4AM，相对当前时区）
- `src/deck-options.md`：L178-183（Day Boundaries）、L405（steps < 1d 建议与 23h 反例）

**srs-benchmark（open-spaced-repetition/srs-benchmark）**
- `README.md`：L44-46（FSRS-5/6/7 同日语义差异）、L304-305（--short/--secs）、L319-320（--no_test_same_day/--no_train_same_day）
- `features/base.py`：L120-133（delta_t 与 delta_t_secs 归一化）

**FSRS 算法文档**
- `https://raw.githubusercontent.com/wiki/open-spaced-repetition/awesome-fsrs/The-Algorithm.md`（FSRS-6 公式与 21 个默认参数；注：旧地址 `fsrs4anki/wiki/The-Algorithm.md` 已迁移，返回 302 提示页）

**本仓库（D:\smart mistake book）**
- `core/model/src/main/kotlin/com/tingyun/smartmistakebook/core/model/LearningState.kt`：L217-226（StudyDayContext）、L358-376（TutorAnswerExposureOutcome）、L502（lastReviewedEpochDay 默认值）、L531（非负约束）
- `core/domain/src/main/kotlin/com/tingyun/smartmistakebook/core/domain/LearningProjector.kt`：L741（attempt 本地日）、L779（reveal 本地日，正确对照）、L786-825（projectTutorAnswerExposure，缺 studyDay）、L845-847（elapsedCalendarDays）、L859（crossDay）、L909（写回 lastReviewedEpochDay）
- `core/domain/src/main/kotlin/com/tingyun/smartmistakebook/core/domain/MemoryUpdateModel.kt`：L79-81（`elapsedDays < 1.0` 短程分支）
- `core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/study/ReviewLogSink.kt`：L58-66（review_log delta_t_days 同源错配）
- `core/database/src/main/kotlin/com/tingyun/smartmistakebook/core/database/CalendarDayMigration.kt`：L8、L22、L26（v42 迁移 UTC 近似回填）
- `core/database/src/main/kotlin/com/tingyun/smartmistakebook/core/database/entity/LearningEntities.kt`：L705-706（last_reviewed_epoch_day 列）
- `docs/specs/mastery-scheduling-spec.md`：L25-27（§2.1，含未同步的 UTC 表述）、L108-114（§2.15 同日复习规则）、L307（2026-08-30 修复轮记录的本地日历日口径）

---

## 12. 修复落地记录（2026-09-11，提交 `4cfc607`）

本节记录 §8/§9 所述缺陷的**最终落地形态**，以免读者把 §8.1/§8.2 的现在时表述当成当前代码状态（见 §12.4）。

### 12.1 实际采用：方案 B 的"结构性一半"，不含 rollover 锚点重构

§9 推荐方案 B（改存时间戳 + `floor((next_rollover − last_review)/86400)`）。落地时只取了它**消除失败类别**的那一半：

- `LearningProjector.projectMemory` 不再读 `previous.lastReviewedEpochDay`，改为**由上一复习的时间戳现算本地日**：`localEpochDayOf(previous.lastReviewedAtEpochMillis, eventUtcOffsetMinutes)`；
- 时间戳与 UTC 偏移**都取自当前事件自身**（事件溯源的确定性输入），故重放读同一事件逐位相同，不依赖任何写入方是否记得盖章；
- `TutorAnswerExposureOutcome` **未加** `studyDay` 字段（即未采用方案 A 的领域模型改动）。

**未做 rollover 锚点重构**：`rollover = 0` 时 `floor((next_rollover − last_review)/86400)` 与本地日历日差**数学恒等**（§5 已证）。因此换锚点不改变任何数值，只增加一次跨 model/domain/data 的重构和一版 Room 迁移；其收益是"与 Anki 一手实现形式同构"，属可读性而非正确性。按项目「新增必先指认它消灭哪个具体失败」的门（全局规则 12.2），这一项**没有可指认的失败**，故不做，记为 §12.3 的可选后续。

### 12.2 方案 A 被**可证地**排除，而非权衡取舍

方案 A 要给 `TutorAnswerExposureOutcome` 补 `studyDay`。核实 `ProjectionTransactionDao.kt:1103-1114` 后排除：该事件行的**规范指纹（SHA-256）随行存储，并在读取时重算比对**做冲突检测。SQL 无法重算 SHA-256，故给该事件类型加字段会让**所有存量曝光行在升级后读不出来**（`readTutorAnswerExposure` 返回 null）。这不是"代价较大"，是"既有数据不可读"。

### 12.3 仍未做（有意）与残留

- **`ProblemMemoryState.lastReviewedEpochDay` 列未删**。它现在对 delta_t **完全惰性**（无读取方），KDoc 已降级为"审计/诊断用途，不要据此计算跨日"。彻底删列需一版迁移 + 仪器化测试，超出本轮可验证范围。
- **曝光路径仍按默认值写该字段**（`millis / 86_400_000`，UTC 日序）。因已无读取方，当前不构成缺陷；但若将来有人重新读它，本类缺陷会复活——故保留 KDoc 警告，这是"惰性但仍在的陷阱"。
- **rollover 锚点重构**（§12.1）未做。

### 12.4 §8 / §9 的时态说明

§8.1 表中 `elapsedCalendarDays = eventEpochDay − previous.lastReviewedEpochDay`（`LearningProjector.kt:845-847`）与 §8.2 全节的现在时描述，**描述的是修复前的代码**。修复后该表达式已不存在；`lastReviewedEpochDay` 的默认值仍在（`LearningState.kt:502`）但不再进入 delta_t 计算。§8.3 中"口径 A 下缺陷必然存在"对**修复前**成立；修复后 A 与 B 数值等价，且两者都不再带该缺陷。

### 12.5 验证

- `:core:domain:test` 372/372、`:core:data:testDebugUnitTest` 370/370、`:feature:tutor:testDebugUnitTest` 89/89，全绿
- 新增 2 例回归：`LearningProjectorTest.a review after an answer exposure stays on its own learner-local day`、`a review on the local day after an exposure is still a cross day`
- **变异验证**：把 delta_t 推导改回常量后第 2 例失败（`expected:<2> but was:<1>`），确认测试确在测该行为
- 同轮修掉的文档债：spec §2.1 的"UTC 日历日差分"表述、spec §2.15 未溯源的 log-loss 数字标注
