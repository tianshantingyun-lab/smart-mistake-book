# 计划自我挑战与链路缺口补全（定稿前最后审查）

**日期**: 2026-08-29
**方法**: ① WebFetch/gh 核验关键证据（py-fsrs 源码级核验 + srs-benchmark 全表 + ACL 元数据）；② 对四份文档逐条自我质疑；③ 高温思维扫描「现有链路上还缺什么机制」——**原则：只做现有链路（录入→账本→投影→计划→复习→重教）的升级，不引入新模型范式**（LSTM/RWKV 等神经网络仅作为 benchmark 上下文，不进入计划）。

---

## 第一部分：证据核验结果（WebFetch/gh 实证）

### 1.1 py-fsrs 源码级核验（gh api 直接读 scheduler.py）✅

**默认 21 参数（精确值，已核验，spec 占位符必须替换）**：
```
w = [0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
     1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
     1.8729, 0.5425, 0.0912, 0.0658, 0.1542]
```

**公式逐条核验**（与 spec §2 一致，补充三处精确细节）：
- 遗忘曲线：`(1 + FACTOR·elapsed_days/S)^DECAY`，`DECAY = −w20`，`FACTOR = 0.9^(1/DECAY) − 1` ✅
- 成功更新：`S' = S·(1 + e^{w8}·(11−D)·S^{−w9}·(e^{(1−R)·w10} − 1)·HP·EB)` ✅（HP=w15 仅 Hard，EB=w16 仅 Easy）
- 遗忘更新：`S'f = w11·D^{−w12}·((S+1)^{w13}−1)·e^{(1−R)·w14}`，**clamp = min(long_term, S/e^{w17·w18})** ✅（spec 写的 ≤S·e^{−w17·w18} 等价，但实现是 min() 取二者较小）
- 同日 short-term：`S' = S·e^{w17·(G−3+w18)}·S^{−w19}`，G≥2 时 clamp ≥1 ✅
- 难度：linear damping `(10−D)·Δ/9` + mean reversion `w7·D0(Easy,clamp=False) + (1−w7)·arg` ✅（回归锚是 **Easy 初见难度**，spec 已写 D0(4) ✅）
- 间隔反解：`I = (S/FACTOR)·(r*^{1/DECAY} − 1)`，round 后 clamp [1, maximum_interval] ✅
- **新发现的实现细节**：① fuzz 对 <2.5 天的间隔不生效；② `elapsed_days = max(0, (now−last_review).days)`（日历日，负回拨钳 0）；③ 同日复习在 Review 态走 short_term 分支、Learning/Relearning 态一律 short_term——**同日语义在源码中是一等分支，不是边角**。

### 1.2 srs-benchmark 全表核验 ✅（并修正两处）

数据集：Anki-10k，约 7.27 亿次原始复习，过滤后 3.499 亿条（不含同日）/5.193 亿条（含同日）。

不含同日（Log Loss ↓）：
| 算法 | Log Loss |
|---|---|
| FSRS-7 | **0.3437** |
| FSRS-rs | 0.3443 |
| FSRS-6（21 参数） | 0.3460 |
| FSRS-5（19） | 0.3560 |
| FSRS-4.5（17） | 0.3624 |
| FSRS v3（13，指数） | 0.4364 |
| FSRS v1（7） | 0.4913 |
| HLR（3 参数） | 0.4694 |
| LSTM（8869 参数，用答题时间） | 0.3332 |
| AVG 基线 | 0.3945 |

**两个重大发现**：
1. **FSRS-7 已存在且是当前最优简单模型**——算法族仍在演进；我们的实现必须把 w 数组长度作为版本标记（17/19/21/…自动兼容，ts-fsrs 已有先例），并注明「FSRS-7 公式待跟进」。
2. **含同日复习时全线恶化**：FSRS-6 0.346→**0.3813**（比 AVG 基线 0.3816 还差）、FSRS-5 0.356→0.4565、HLR 0.469→**0.705**（灾难性）。**同日复习的建模是一等设计问题**：必须把「同日多次接触」与「跨日复习」严格分流（见第二部分 C1）。

### 1.3 其它核验

- HLR（ACL 2016 P16-1174）：页面仅元数据；公式/特征/损失此前由子代理直读 PDF 核验 ✅；benchmark 中的 HLR 是 3 参数简化版（用间隔与评分），完整版含 lexeme 特征 ✅
- **Käser et al. 多技能 BKT（IEEE TLT）**：IEEE 页面内容为空，**降级为 UNVERIFIED**——「全 KC 各记一次是主流做法」改为「EDM/DataShop 社区常见实践（DataShop 多 KC 标签存在），一手核验待补」，并给出替代依据：DataShop 的 transaction 级多 KC 标签（https://pslcdatashop.web.cmu.edu/about ）✅
- SM-2/SM-17 **不在** srs-benchmark 表内（此前判断正确）；SM-17 对比在独立仓库 fsrs-vs-sm17
- 神经网络（LSTM 0.3332 / RWKV）虽超过 FSRS-6，但参数量 8869~276 万、需 Reptile 预训练，不符合本地优先/可解释约束——**排除出计划**（benchmark 上下文）

### 1.4 证据状态修正（对四份文档）

- spec §2.11「py-fsrs <512 条不训练」→ **修正**：py-fsrs README 未写死阈值；fsrs-rs training.rs 有硬阈值（<8 返回默认、<64 只拟合初始稳定性）——阈值以 fsrs-rs 为准
- spec 占位参数数组 → **替换为上述核验值**
- addendum 中 Käser 条目 → **标注 UNVERIFIED**
- FSRS-7 存在 → **补进路线图**（跟进项）

---

## 第二部分：计划自我 challenge（弱点清单）

逐条质询四份文档，发现以下弱点（按链路顺序）：

### C1【P0】同日复习语义未定义清楚——benchmark 级风险
现状：spec §2.4 写了 short-term 公式，但没有回答：**我们的证据流里大量同日事件（拍照→识别→确认→自评→讲题→视觉交互全在同一天）每条都当一次 FSRS review 吗？**
benchmark 实证：同日复习混入训练/评估会让 FSRS-6 掉到不如均值基线（0.3813 vs AVG 0.3816）。
**补全机制（链路升级）**：
- 每卡每日**至多一次长程更新**：同卡同日的多条证据先聚合成一条当日评级（取最差方向为主、最优为辅修正），日终（或次日首开时）落一次 FSRS 更新；
- 卡的学习态（首次录入当天）走 short_term 分支（与 py-fsrs Learning 态一致）；
- review_log 保留**全部**原始证据（采集不丢），但投影只消费聚合后的每日一条——**采集与调度彻底解耦**。
- 聚合规则：G_agg = min(G_raw)（保守：一天内既有 Again 又有 Good 时按 Again），并记录 G_max 备查。

### C2【P0】旧数据迁移进 review_log 的规则缺失
现有 attempt_event 历史是二值对错+权重，没有四档。迁移规则补全：
- 只迁移**跨日**的 attempt/self-report（同日旧证据不迁移）；
- 每条映射按 spec §2.5 表；`source_kind='LEGACY'` + `migrated=1` 标记；
- 优化器第一轮**默认排除 LEGACY**（训练分布要与新采集一致），待新采集 ≥512 条后决定是否回填；
- 迁移后不重排已有 due（避免到期爆炸，Anki 同款建议），只影响增量。

### C3【P1】leech（反复遗忘题）处理缺失
Anki 的 leech 概念：多次遗忘的题继续堆难度没有意义。补全：
```
leech(K) := lapse_count ≥ 6 且 最近 2 次跨日复习均为 Again
→ 该题标记 LEECHED：暂停常规排期，强制注入 teaching_material（重教通道），
   重教后由用户手动恢复；difficulty 钳制在当前值不再上调
```
落点：projector 打标 + MistakeDetailRoute 呈现「这题反复忘，建议重学」+ 教材注入。与错因分通道（spec §7）天然衔接：leech 多为 CONCEPT_MISCONCEPTION。

### C4【P1】考前模式没有机制
spec 只说「考前 r* 上调」。补全：
- 用户申报考试日期（H2 字段）→考前 14 天内 `r*_exam = min(0.97, r* + (0.97−r*)·(1 − d/14))` 线性爬升；
- 考前队列扩容：把「R < r*_exam 的题」无论 due 一律纳入候选（不超过会话预算）；
- **考试结果回灌**：考卷成绩/考后自评作为 outcome 喂 prediction_outcome（校准影子 HLR）；
- 考后 `r*` 回落由日历驱动自动完成。

### C5【P1】反馈环路振荡无阻尼
weakness 驱动选题→弱题集中复习→变强→退出队列→其它题变最弱→振荡。补全：
- 每会话**维护配额**：≥25% 名额给「非最弱但接近到期」的题（防强题断崖式衰减）；
- 同 KC 每会话 ≤2 题（现有 diversity 约束扩展到 KC 维度）；
- weakness 输入用 conservativeMasteryScore（已有）并加 EMA 平滑（半衰 7 天）防单日抖动传导到选题。

### C6【P0】评估 harness 与灰度开关缺失
spec 只有 evaluate() 一句。补全：
- **回测 harness**：上线前用真实账本重放，TimeSeriesSplit（旧→新）口径对齐 srs-benchmark；对照基线 = 现行指数曲线 vs FSRS-6；**上线门：FSRS-6 log-loss < 指数基线**（若不成立则回退）；
- **kill-switch**：FSRS 调度做成 feature flag（DataStore），关闭时回退现行乘法调度——投影双实现并存一个版本周期；
- 监控指标：周实际回忆率 vs r* 的偏差（偏差 >0.1 告警）；review_log 量与冷却拦截率。

### C7【P2】评级采集的 UI 时机不明确
spec 说四键自评，但没说什么时候问。补全：
- 作答链：提交→判对错→**答对**才问「这次想起来费劲吗？（轻松/正常/费劲）」映射 Easy/Good/Hard；**答错不问，直接 Again**（错误本身即 Again；可选追问错因）；
- 复习链同理；讲题/视觉通道按 §2.5 映射表自动映射，不打扰用户；
- 默认 UI 只显三键（Again 由答错自动产生），与「减少手动操作」原则一致。

### C8【P2】learning/relearning steps 与我们日粒度会话冲突
py-fsrs 默认 learning_steps=(1m,10m)（分钟级当天微步）。我们的会话是日粒度，产品没有分钟级排期。**决策：learning_steps=() / relearning_steps=() 全部禁用**，当天内只走 short_term 稳定性分支，跨日才进长程更新——与 C1 的每日一条规则自洽。

### C9【P2】难度域迁移与 INITIAL_DIFFICULTY 一致性
现库 `difficulty` 0..1、`INITIAL_DIFFICULTY` 常量未核对。迁移（v36）时：`D_new = 1+9·d_old`，同时把 projector 的 INITIAL_DIFFICULTY/各 ±0.0x 常量整体删除，避免双体系残留。迁移 SQL 需把 KMS 的 mastery 域与 PMS 的 difficulty 域分开处理（两者语义不同，勿混）。

### C10【P3】review_log 的写入路径与备份
- 写入幂等：UNIQUE(learner_id, source_id) 已定；source_id 对 attempt=attemptEventId、自评=selfReportEventId、视觉=attemptId——**必须用账本事件 id 而非 UI requestId**（UUID 每次不同，冷却后重试会重复入库）；
- review_log 随 database.sqlite 备份自动覆盖（无需额外打包）；时区全 UTC 落列，展示层转本地。

### C11【P3】暂停/忽略状态
学生可能想暂停某题（不考了/题目有误）。补一个 `paused` 标志：暂停题不进队列、不计遗忘（R 冻结），恢复时从暂停时刻重算。开放问题：是否需要（产品决策）。

### C12【P3】投影成本
FSRS 公式是 O(1) 指数运算，投影批量重放（万级事件）成本可忽略——无需优化。记录结论防止过度设计。

---

## 第三部分：链路终图（补全后）

```
用户行为（六链，math-modeling 第二部分 A-F+G+H）
   │ 原始证据（全部入 review_log 采集层，含被冷却拦截的）
   ▼
冷却/分位修正/时段乘数 M[b] ──► G 评定（映射表 §2.5 + 采集 UI §C7）
   │
   ├─ 同日聚合（C1：每卡每日一条 G_agg）──► 短程分支（同日）
   └─ 跨日单条 ──────────────────► FSRS 长程更新（S/D，§2.4）
   │                                     │
   ▼                                     ▼
KC 分摊（全 KC 记一次，§2.13）        learner_problem_memory_state
   ▼                                     ▼
learner_knowledge_mastery_state ←── library_catalog 修复（L1）
   │                                     │
   ▼                                     ▼
ReviewPlannerV2（weakness/prereqGap/易混对/维护配额 C5/毕业 §2.10/leech C3/考试模式 C4）
   ▼
会话 → 作答 → 回到顶部（评估 harness C6 持续回测，kill-switch 待命）
```

---

## 第四部分：定稿判定

- 证据：FSRS 公式与参数已源码级核验（gh api 读 py-fsrs scheduler.py，公式逐条对上 spec §2.4，含 min() clamp、fuzz<2.5 天、日历日差分三个实现细节）；benchmark 全表已核验并修正两处错误认知（「py-fsrs 512 阈值」有误→以 fsrs-rs 8/64 为准；「SM-2 在 benchmark 表内」不存在）；同日风险已实证化（FSRS-6 含同日 0.3813 < AVG 基线 0.3816）；Käser 一处降级 UNVERIFIED；FSRS-7 已出现（0.3437），实现需版本化参数数组。
- challenge 产出 12 条（C1-C12），全部为**现有链路上的机制补强**，无新模式引入（LSTM/RWKV 仅 benchmark 上下文，已排除）；C1/C2/C6 为 P0，实施路线图更新为：阶段 A（止血）不变 → 阶段 B 开工前先落 C1/C2/C6（它们是 B 的前置）→ C3/C4/C5 并入 B7。
- 文档回写状态：spec 已加 §2.11a/§2.15-§2.22 与路线图 B0/B7；math-modeling 已替换核验参数；本文件为 challenge 原始记录。**计划至此定稿。**
