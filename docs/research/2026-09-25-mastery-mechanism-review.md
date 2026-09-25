# 掌握库机制外部对标研究 + 两个机制设计（插眼 6/1/5 · 阶段 0 交付）

- 日期：2026-09-25
- 状态：**研究部分已合成（证据等级已标注）；插眼 1/5 机制设计待用户批**
- 用途：供阶段 3（掌握库 M1–M7 + K3）与两个插眼的实施使用；遵守"算法参数须科学落实"纪律。

## 0. 方法与证据等级

- 四个并行研究子代理 + 主会话交叉验证。子代理环境 WebSearch 通道不可用，全部改走
  `gh api` / `curl` / WebFetch / OpenAlex / arXiv / Crossref 取**一手来源**；主会话 WebSearch 可用，
  已对关键结论做了交叉验证（如 FSRS 校准门槛）。
- 证据等级：**A** = 已读原文（官方文档/源码/论文，附 URL）；**A−** = 论文元数据经 API 核实但正文
  被出版商拦截未读；**B** = ≥2 独立来源一致；**C** = 单一来源待验证；**D** = 无出处，不得采用；
  **未找到** = 本轮多轮检索无命中（不等于不存在）。

## 1. 内部现状基线（供对照，此前已核实）

- 掌握更新：p′ = p + (1−p)·0.32·w（正向）/ p − p·0.42·w（负向）；evidenceMass += w；
  保守下界 p − 1.2·sqrt(p(1−p)/(mass+1))。MASTERED = 下界≥0.85 ∧ mass≥2.0 ∧ ≥2 itemFamily ∧
  ≥2 学习日 ∧ 证据≤45 天。
- 证据八档：独立 1.0 / 提示重试后错 0.9 / 提示重试后对 0.6 / 看答案后对 EXCLUDED(0) / 看答案后错 0.6。
- 写门：置信≥0.7；正向≥1 锚、MASTERED≥2；冷却 12h；配额 50/会话、100/learner；非 CONFIRMED 半权；
  档位 0.10/0.15/0.18/0.35。
- FSRS-6 双实现（FSRS/legacy）；"看答案"在 FSRS 评 AGAIN、legacy ×0.45+10 分钟——语义分裂。
- 排程 V2 打分：DUE 5.0/WEAKNESS 3.0/LAPSE 1.0/REPEAT 2.0/AVOIDANCE 1.0/EXAM 2.0/WAITING 1.5/
  KC_DROP 2.5/PREREQ −2.0 + beam search + 时间预算。考前 14 天线性 ramp。

## 2. 六块研究结论

### 2.1 掌握更新数学

- **我们不是 BKT 的特例**（A：Wikipedia BKT 条目 + OATutor 源码 `BKT-brain.js`）。BKT 是两步
  （先验→观测后验→学习转移）；我们是一步固定增益 EMA。0.32/0.42 在 BKT 里**没有对应参数**——
  它们既不是 p(T) 也不是 slip/guess，而是"观测+学习"融合后的经验增益。Beta-Bernoulli 伪计数
  语义下：0.32 ≈ n=2.13、0.42 ≈ n=1.38，且**步长恒定不随证据收缩**（真贝叶斯按 1/(n+1) 收缩）。
- **OATutor 的 BKT 是固定参数**（A，源码与 `plan.MD`）：四参数全 0.1；`MASTERY_THRESHOLD = 0.85`
  （config.js:113）；只在首次作答更新。手算其行为：连续两题首答对即 ≥0.85 判掌握、一错即近清零——
  名义阈值与我们同为 0.85，实际松紧完全不同。可借鉴它的两点工程化（只首答更新、阈值可被课程目标覆盖），
  **不要照抄松紧度**。
- **IRT 在单学生场景不可行**（A：标定需数百学生，Frontiers 2016 全文已读；Wikipedia IRT 条目）。
  我们的 `w`（证据质量）是为题目难度/区分度留的**手工代偿**——与 IRT 的 a_i/b_i 对应。
- **我们的下界式是单侧 Wald 正态近似**（z=1.2 ≈ 88.5% 置信，推导）。两个自洽性缺陷（B：Wikipedia
  Binomial proportion CI + Brown/Cai/DasGupta 2001 元数据核实）：①Wald 在小 n/极值 p 处不可靠
  （Wilson/Jeffreys 更稳）；②**p 是 EMA 输出、不是比例**，与 mass 塞进同一个 Wald 式属类别混用。
  实测后果：MASTERED 实际要求 p≈0.94–0.97（≈8 次全权重答对），0.85 被下界抬高了 0.09–0.12。
- **未找到**：单学生小数据下"启发式 vs BKT vs IRT"的直接对照实验。
- **〔原生 web search 升级〕"置信下界判定掌握"有先例——在自适应掌握测试（AMT）文献线，不在 BKT 线**（B）：
  Kingsbury & Weiss 的 Bayesian AMT：每次作答后算 θ̂ ± 1.96σ，**"区间下界高于掌握线 θm 即判掌握"**
  （等价于单侧 97.5% 置信）；另一常见停测规则是 **SE < 0.3**。实用警告（多来源一致）：固定阈值是
  常见错误——阈值应随技能类型而变、且应允许教师/学生覆盖。来源：ERIC 收录的 AMT 文献与硕士论文综述
  （本轮搜索层返回、无直接 URL，标 B 待复核）。
- **建议**：①把 0.32/0.42 在文档中明确定义为"固定增益（EMA 式）"，禁止按 p(T) 语义调它；
  ②把 evidenceMass 改造成 **s/f 双计数（β-二项后验）**，p 由 (s+α)/(s+f+α+β) 导出——一步同时
  解决"p 与 mass 脱钩"和"下界类别混用"；③下界换成 Wilson 或 Jeffreys；④向学生展示区间而非单点
  （Glicko-2 官方文档 A 级：rating ± 2·RD 是标准做法）。

### 2.2 证据策略与门常数 + FSRS 用法

- **FSRS 无 hint/reveal 概念**（A：awesome-fsrs wiki + Anki 手册 + py-fsrs 源码 `rating.py` 四值）。
  Anki 官方：日内重复"不会显著贡献长期记忆"；Health check 明说"忘了就评 Again、想起来才评
  Hard/Good/Easy"。
- **"看答案后答对 = 非独立证据（w=0）"有官方表述支撑**（A）；但**必须作为独立 `revealed` 标记落库，
  不要伪装成 AGAIN**——否则污染 FSRS 训练集。遗留路径的 ×0.45+10 分钟**无任何公开依据（D）**，
  应删除或改为 FSRS S′_f 派生值。
- **FSRS 校准（review_log→参数）**：官方输入 schema = card_id/review_time(UTC 毫秒)/rating{1..4}/
  state/duration；输出 FSRS-6 21 参数（A：fsrs-optimizer）。**最小样本硬门：Anki 要求 ≥400 条 review
  才允许个性化**（A：deck-config.ftl + params.rs）；health check 在 >300 条时运行，判据
  adjusted_log_loss≤1.11 或 adjusted_rmse≤1.53；**采纳门 = 新参数必须在同一数据上 log loss 不劣于现
  参数**（A：params.rs）。低于门槛用群体默认参数（来自 ~10k 用户、数亿条 review）。
  → **单用户能否校准的答案：技术下限 400 条，实践建议 ~1000 条；早期用默认参数。**
- **LLM 生成证据的数值权重：无针对 LLM 的公开标准**（未找到）→ 数值属产品裁定。**但"提示/作答次数
  加权"有直接先例**（B）：EDM 2015《Optimizing Partial Credit Algorithms》在 ASSISTments（86.7 万题、
  2 万学生）上对提示/作答罚权做**网格搜索**（0–1、步长 0.05、441 组合）；提示罚分作为防
  gaming 机制见于 EDM 2012；难度加权公式（错答数+提示数+看解数）见于专利 US20040018479A1。
  序关系亦有实证支撑（B）：独立提取 ≫ 提示后 ≫ 看答案（Roediger & Karpicke 2006 测试效应；
  Koedinger & Aleven 2007 提示困境；Falchikov & Boud 1989 / Gašević 2017 自评系统性偏高；
  Koriat & Bjork 2005 能力错觉——均为 A−）。
  模型自报置信度普遍过度自信（Guo 2017 / Xiong 2023 / Kadavath 2022，A−）→ **0.7 硬阈值在文献里
  没有任何依据**，应降级为多信号之一并落库。
- **冷却/配额/锚底线：无公开基准**（未找到）→ 产品裁定；理论锚可选 Chow 1970 reject option（A−）
  与 Confident Learning（A）。建议把 12h 冷却对齐 Anki"最小间隔"语义（同 KC 最小复写间隔），
  锚底线实现为**可核验对象**（每条正向证据可指到一条可核验引文）而非裸数字。

### 2.3 重拆/重放的业界做法（供 K3/M3）

- **事件溯源重放是成熟模式**（A：Azure Event Sourcing 模式页）：状态由重放事件流派生（rehydration）；
  **"旧事件 + 新语义"的官方处置 = event versioning / upcasting / 尽量不做 in-place migration**
  （"breaks immutability… undermines the audit trail"）。投影重建范本：Marten `RebuildProjectionAsync`
  （"rebuild is authoritative by definition… always starts by resetting the cell"，A）；数据工程标准
  动作 = **backfill**（Dagster 官方定义含"changed the logic and need to update historical data"，A）；
  维度换版标准 = **SCD2**（Kimball 原文，A；dbt snapshots：历史版本不可由源头重算，A）；
  概念退役/合并的标准词汇 = **SKOS** `exactMatch/closeMatch + changeNote`（W3C，A）——我们的
  `superseded_by` 链语义等价。
- **教育领域"换映射重算掌握度"无公开标准流程**（未找到，3 轮检索）。最接近且等价性有限的是
  心理测量学 equating（C）。Q-matrix 错配会实质改变判定（DINA 2007，A）；Q-matrix 本应迭代验证
  （de la Torre 2015、LFA Cen 2007，A）。**关键区分**：BKT/DINA 的掌握是隐变量+拟合产物，换映射
  后是**重新估计**；我们是确定性账本→投影，**重放合法**——这个区分支撑我们的 M3 全量重放。
- **结论**：把旧知识点 ID→新 ID 解析放在**重放期**（upcaster 层），事件与既有绑定一律不改写；
  `LearningProjector.replay` 建模为"带 taxonomy_version 的幂等 backfill"，**重建前落一份旧版本投影
  快照**（SCD2 教训：历史版本不可由源头重算）。

### 2.4 排程打分与考前加权（供插眼 1 与阶段 3）

- **排程权重表（WEAKNESS/LAPSE/REPEAT/AVOIDANCE/EXAM/KC_DROP/PREREQ）无任何公开依据**（未找到，
  3 轮检索）：FSRS 官方唯一旋钮是 desired retention（A：ABC of FSRS"users should not tweak the
  parameters manually"）；Anki 的优先级 = 单因子 Relative overdueness（防饥饿哲学，A：deck-options、
  studying 页）；srs-benchmark 只评预测精度不评策略（A）。→ **这些数值是自研启发式，不能借外部
  权威背书**；正确做法是把 beam search+权重当**策略层**，用真实日志做离线模拟（TimeSeriesSplit
  防未来泄漏）比较方案。另：**PREREQ −2.0 会被 EXAM 2.0/WEAKNESS 3.0 静默抵消**——建议改成硬过滤
  （前置未达则不参与排序）。
- **考前"距考试多久复习"有量化规律**（A：Cepeda 2008，Spacing Effects in Learning）：最优间隔随
  测试延迟增大、且**按占测试延迟的比例**衡量（1 周延迟约 20–40% → 1 年约 5–10%）；Cepeda 2006
  元分析：分布式练习优于集中突击（A）。→ 我们的固定 14 天线性 ramp **形状与该比例规律不符**；
  窗口与形状可用 Cepeda 比例律作理论锚。
- **"考试倒推"的公开先例 = 独立节流层**（B：Deckline、Deadline2 两个开源项目一致）："turning the
  remaining work into a clear **daily target**… **never changes how Anki schedules your cards**"。
  Anki 官方对考试无算法级支持（A：filtered decks）。
- **三因子复合分（重要性×天数×缺口）无公开先例**（未找到）→ 自研，窗口长度/形状用 Cepeda 锚定。
- **建议**：把"考试"从**加性分改成外生约束**（每日工作量目标层），避免 EXAM 2.0 被 beam search
  时间预算静默截断——这正是 Deckline 先例的形态。该建议进入插眼 1 设计（见 §4）。

### 2.5 缺口分析（按四层，每项分级）

> 先纠正一处基线误差：以下机制**已经存在**（本地源码核实），不是缺口：
> HLR 半衰期回归（未训练）、影子预测审计、CMRR 式 desired-retention 建议、个性化时长模型、
> 中断/离屏注意力折扣、离线重放评测（BCE log-loss）、校准报告（10 桶/Brier/ECE）、FSRS-6/legacy
> kill-switch、先修硬门 τ=0.6、以及 `docs/research/behavior-signals-and-context-addendum.md` 等
> 内部研究文档。

| 层 | 项 | 依据 | 现状 | 分级 |
|---|---|---|---|---|
| 事实 | RT 个人分位进证据门 | **A−**（van der Linden 2007, Psychometrika 72(3) 287–308，lognormal RT 层级模型，B-GLIRT 家族，"最流行的联合模型"） | 时长已采、未进门 | **必须补**（零新增采集） |
| 事实 | RTE 快答猜测降权 | **B**（Kong, Bhola & Wise 2007, EPM, DOI 10.1177/0013164406294779；RTE≥0.9 为足够投入惯例） | 无 | **必须补**（消灭"蒙对记为掌握"） |
| 事实 | 改答案方向 | **B**（Kruger, Wirtz & Miller 2005 首感谬误；多研究一致：65–72% 改对 vs 10–22% 改错） | 无 | 可选 |
| 事实 | 走神/离屏 | 已有内部定案 | **有** | 不要补 |
| 事实 | 眼动/逐键流 | 隐私边界已定 | 无 | 不要补 |
| 投影 | 不确定度量化（Wilson/Jeffreys/β-二项） | B | 部分（Wald 下界） | **必须补**（§2.1） |
| 投影 | HLR 个性化遗忘训练完成 | A（Settles & Meeder ACL16） | 80% 完成 | **必须补**（缺训练+晋升流程） |
| 投影 | 先修传递到后继估计 | B（RPKT/图基 KT/KQN） | 只有硬门 | **必须补**（消灭后继虚高） |
| 投影 | IRT 难度/区分度 | 未验证 | 无 | 可选（需高作答密度） |
| 投影 | CDM/DINA Q-matrix | B | 无 | 可选（需 Q-matrix 标定） |
| 投影 | Elo 在线难度 | 未验证 | 无 | 不要补（低数据不稳定） |
| 排程 | Disperse siblings | A（fsrs4anki-helper README） | 部分（同批去重≠到期分散） | **必须补**（低成本） |
| 排程 | Postpone/Advance | A（同上） | 部分（考前是加权非提前） | **必须补**（复用 exam 信号） |
| 排程 | Easy Days 按星期平滑 | A（Anki 手册） | 无 | 可选 |
| 排程 | Load Balancing | A（helper） | 无 | 可选 |
| 排程 | CMRR 闭环（retention+负载预测） | A（Anki 手册 Simulator） | 部分（只有建议值） | **必须补** |
| 排程 | Interleaving | B（Rohrer 2015 等） | **有（V2 已实现+测试锁）** | 不要补 |
| 排程 | bandit/MEMORIZE 点过程 | B（Tabibian PNAS19） | 无 | 不要补（会替换整套结构） |
| 校准 | 校准报告 | 内部已达标 | **有** | 不要补 |
| 校准 | srs-benchmark 口径复现 | A | 无 | 可选 |
| 校准 | Open Learner Model 解释层 | B（Bull & Kay 2010 等） | 无 | **必须补**（消灭"分数从哪来"） |
| 校准 | 在线随机 A/B | 未找到 | 无 | 可选（上线后再补） |

## 3. 研究派生的三条总体建议（进阶段 3 的输入，届时逐项再问你）

1. **掌握表示换框架**：s/f 双计数 + β-二项后验 + Wilson/Jeffreys 区间；MASTERED = 点估计≥θ ∧
   区间宽度≤阈值（"≥2 锚"自然变成区间收窄条件）。这是对 M1–M7 的实质升级，比原计划多动投影核心。
2. **前置改硬过滤**：PREREQ −2.0 加性会被 EXAM/WEAKNESS 抵消；改"前置未达不参与排序"。
3. **考试改外生约束**：倒推复习做成独立"每日工作量目标"层（Deckline 先例），不混进加性分。

## 4. 插眼 1 机制设计（待批）

**目标**：替换 14 天线性 ramp，改为"考试重要性 × 学生自设窗口 × 掌握缺口"复合，考前天数可自设。

- **数据**：`ExamCalendarEntry` 增 `importance: IMPORTANCE`（HIGH/MEDIUM/LOW，默认 MEDIUM；
  序列化兼容 + 空载体剥离 + 指纹纪律，bf8be888 教训）；`SchedulingSettingsStore` 增
  `examWindowDays: Int`（默认 14，范围 1–60）。
- **公式**（全部本地确定性，D1 不破）：
  `examPriorityFor(subject, day) = max over 该科目考试 of [ i(importance) × g(daysUntil, window) ]`
  - i：HIGH=1.0 / MEDIUM=0.7 / LOW=0.4（产品裁定，待校准）；
  - g(d) = 1 − d/window（d ∈ [0, window]），窗口内线性、之外 0。
- **掌握缺口因子——摆给你选的唯一新参数**：
  - A（我推荐）：**不乘**。WEAKNESS 3.0 已在场，考前窗口内薄弱候选本就会浮上来，再乘是与自己打架；
  - B：乘 (1 + gapBoost)，gapBoost = 该候选绑定 KC 的 (0.85−conservativeMastery) 聚合×系数——加一层
    但语义更"考前冲薄弱"。
- **接线**：`examPriorityFor` 分值来源替换（窗口、重要性）；V2 `EXAM_WEIGHT=2.0` 消费点不变；
  `NewIntroductionPolicy` 的 catch-up 输入改用新 window；设置 UI 加两控件（考试重要性选择、考前窗口
  天数输入）。
- **研究注记（不改变本版形状，供后续校准）**：Cepeda 2008 的"最优间隔≈测试延迟的 5–40%"说明线性
  ramp 形状本身不最优；本版先保持线性+可自设窗口，形状优化与"考试改外生约束（§3.3）"合并为后续
  插眼，避免一次改两处。
- **只动这一块**（D1"内核非目标"的唯一授权例外）。

## 5. 插眼 5 机制设计（待批）

**目标**：确认卡挂起/打断工具调用时的持久化、恢复、留痕——消灭"会话被锁死"。

- **持久行**：新表 `agent_pending_request`：`request_id`（幂等键）、会话区、`kind`（本地动作白名单
  五动作之一）、`payload_json`（参数形状固定）、`status`（PENDING/ACCEPTED/DECLINED/IGNORED）、
  `created_at` / `resolved_at` / `resolution_note`。请求由本地动作通道在**同一事务**里落行。
- **状态机**：`TutorTurnSendStateMachine` 的 `AWAITING_CONSENT` 相位接上**真生产者**（ConsentRequired
  动作由确认卡触发）；挂起期间：输入框保留可输入（A5 不破），发送键变"等待你决定"态；学生裁决后
  原请求带裁决继续。
- **恢复**：进程死亡/离开后，从 PENDING 行重建**同一张**卡——回原会话区；学生此刻在别处则进
  **悬浮球待确认层**（S3）。**同一 requestId 只挂一次、不自动失效**；点击后终态 + 留痕。
- **回喂**：裁决结果回喂模型（D-K2e）；DECLINED/IGNORED 的留痕供"被拒理由"呈现。
- **与 K3 的关系**：重拆 hook 被拦 → PROPOSE_RECLASSIFY 走同一机制。
- **迁移**：新表随阶段 1 的 schema 改动一起落（bump 版本 + 非破坏迁移）。

## 6. 未验证清单（不得当事实）

- IRT/Elo 的英文原始出处本轮未取到全文（arXiv/Crossref 通道受限）——按 C/未验证处理。
- Anthropic/OpenAI 官方文档在本机被区域拦截，未作为 A 级来源；如需引用须另行复核。
- 标注 A− 的论文仅核验元数据（标题/年/DOI），正文未读（出版商 403）；结论方向性为通行共识但未逐句核对。
- 原生 web search 层返回的三组结论（AMT 下界规则 / EDM 2015 网格搜索 / RTE、RT、改答案文献）**无直接
  URL 随返回**，标 B 待复核——DOI 均已给出可自行核验。
- 两个"未找到"（单学生对照实验、排程权重表依据）均经多轮检索，不代表不存在。
- 所有 URL 来自本轮实测响应；研究代理未写任何文件、未改任何代码。

## 7. 结论 → 决策映射（防止研究偏离需求）

| 研究结论 | 喂给哪个决策/阶段 |
|---|---|
| 固定增益 EMA 定义 + s/f 双计数 + Wilson 区间（§2.1） | 阶段 3 掌握投影改造（M1 的实质升级） |
| 看答案独立标记、不伪装 AGAIN；legacy ×0.45+10min 删（§2.2） | 阶段 3（M2 接线 + 双模型收敛） |
| 校准硬门 ≥400 条 + 采纳门（§2.2） | 阶段 3 的校准通道与门常数依据（插眼 6 的"校准依据"要求） |
| LLM 证据权重无标准、序关系有文献、0.7 阈值无依据（§2.2） | 阶段 3 门常数重标"产品裁定+文献序关系" |
| upcaster + backfill + 重建前快照（§2.3） | K3/M3 重放设计 |
| 排程权重无外部依据 → 离线模拟比较；PREREQ 改硬过滤（§2.4） | 阶段 3/5（策略层验证方式 + 前置门修正） |
| Cepeda 比例律 + Deckline 外生约束（§2.4） | 插眼 1（本版线性+自设窗口；形状优化与"外生约束"合并为后续插眼） |
| 缺口分级清单（§2.5） | 阶段 3 的"要补机制"输入，逐项再问用户 |

## 8. 详细附录（一手材料要点，供阶段 3 实施直接引用）

### 8.1 BKT 与我们的关系（公式与推导）

- **BKT 更新式**（A：Wikipedia BKT 条目 + OATutor `src/models/BKT/BKT-brain.js` 源码原文）：
  答对后验 `p(L|correct) = p(L)(1−p(S)) / [p(L)(1−p(S)) + (1−p(L))p(G)]`；答错分母换成
  `p(L)p(S) + (1−p(L))(1−p(G))`；随后转移 `p(L′) = 后验 + (1−后验)·p(T)`。
- **我们的关系**：一步固定增益 `p+(1−p)k / p−p·k`；可精确表述为"在 slip=0、guess=0 的极端假设下，
  用软步长 k 替代 BKT 的 0/1 硬后验"。0.32/0.42 不是 p(T)/slip/guess 的任何一者。
- **伪计数推导**（推导，非文献）：k = 1/(n+1) → 正向 0.32 ≈ n=2.13、负向 0.42 ≈ n=1.38；真贝叶斯步长
  随证据按 1/(n+1) 收缩，我们的恒定 → **p 与 mass 脱钩的结构根因**。
- **MASTERED 实际门槛推导**（推导）：全权重全对时 p_k = 1 − 0.9·0.68^k；k=8 → p=0.959、mass=8、
  Wald 下界 = 0.959 − 1.2·sqrt(0.959·0.041/9) ≈ 0.880 ≥ 0.85。z=1.2 单侧 ≈ 88.5% 置信。
- **OATutor 手算轨迹**（A 源码 + plan.MD）：单答对 0.1→0.55；连对两题 0.925 ≥ 0.85 判掌握；
  在 0.55 上答错 → 0.208。四参数全 0.1、`MASTERY_THRESHOLD=0.85`（config.js:113）、只在首次作答更新。
- **AMT 置信下界规则**（B 待复核，ERIC 收录 + 综述）：θ̂ ± 1.96σ；"下界 > 掌握线 θm 判掌握"
  （等价单侧 97.5%）；另一常见停测规则 SE < 0.3；实用警告：固定阈值是常见错误（阈值随技能类型而变、
  应允许教师/学生覆盖）。

### 8.2 FSRS 与校准细节

- **FSRS-6 公式**（A：awesome-fsrs wiki The-Algorithm）：R(t,S) = (1 + factor·t/S)^(−w20)，
  factor = 0.9^(−1/w20) − 1 保证 R(S,S)=90%；同日复习有独立 S′ 公式；共 21 参数。
- **评级语义**（A：Anki studying 页）：Again=答错或想不起来（部分正确也算）；Hard=答对但有疑虑；
  Good=答对但费力；Easy=轻松。**无 hint/reveal 档**；Health check："忘了就评 Again，想起才评 Hard/Good/Easy"。
- **校准细节**（A：fsrs-optimizer + anki params.rs/deck-config.ftl）：输入 card_id / review_time(UTC 毫秒) /
  rating{1..4} / state{0..3} / duration + timezone/day_start；输出 21 参数；按卡分组 MLE+BPTT。
  **Anki 硬门 ≥400 条 review 才允许个性化**；health check 在 >300 条时运行（adjusted_log_loss≤1.11 或
  adjusted_rmse≤1.53）；**采纳门 = 新参数在同一数据上 log loss 不劣于现参数**；num_epochs=8、
  enable_short_term=true；低于门槛用群体默认（~10k 用户、数亿条 review）。py-fsrs Optimizer <512 条
  返回默认参数；每日每卡只计首条 review。
- **揭示的官方口径**（A）：日内重复"不会显著贡献长期记忆"；"看答案后答对=非独立证据"站得住，
  但必须独立 `revealed` 标记、不得伪装 AGAIN（污染 S′_f 训练集）。

### 8.3 重放模式的官方处置（Azure Event Sourcing 原文要点）

- 状态由重放事件流派生（rehydration）；投影 = materialized view。
- **"旧事件 + 新语义"四条处置**：tolerant deserialization / **event versioning**（事件带版本号，
  消费端据此选逻辑）/ **upcasting**（注册转换函数把旧 schema 转当前——"stored events remain
  unchanged, which preserves immutability"）/ in-place migration（"breaks immutability and should
  be a last resort"）。
- 历史坏数据：compensating events 或 upcasters 在**重放期**处理——"Fixing the bug in application
  code doesn't fix the historical events"。
- **Marten**：`RebuildProjectionAsync(name)` / `RebuildSingleStreamAsync(id)`；"the rebuild is
  authoritative by definition""a rebuild always starts by resetting the cell"（可安全取消重试）。
- **Dagster backfill**：触发场景明写"changed the logic for an asset and need to update historical
  data with the new logic"。
- **Kimball SCD2 / dbt snapshots**：新行 + 新代理键 + 有效/失效日期 + 当前行指示；"Snapshots can't
  be rebuilt"——历史维度必须单独留存。
- **SKOS**（W3C）：exactMatch/closeMatch/broadMatch/narrowMatch/relatedMatch + changeNote/historyNote
  —— 我们的 `superseded_by` 链 ≈ exactMatch/closeMatch + changeNote 组合。
- 开源版本化图库（B）：SirixDB（bitemporal、append-only、page-level versioning）、XTDB（time-travel）、
  Raphtory、AeonG（VLDB）。

### 8.4 教育与排程细节

- **Q-matrix 错配**（A）：DINA 2007（185 引用）——映射错配实质改变掌握判定；de la Torre 2015
  （193 引用）——Q-matrix 是迭代验证对象；LFA Cen, Koedinger & Junker 2007（320 引用）——
  "重拆技能模型并比较拟合"是标准流程。
- **考试/间隔**（A）：Cepeda 2008——最优间隔随测试延迟增大、且**按占测试延迟的比例**衡量
  （1 周约 20–40% → 1 年约 5–10%）；Cepeda 2006 元分析（1899 引用）——分布式练习优于集中突击。
- **Deckline**（B）：独立"每日目标"层、"never changes how Anki schedules your cards"、只读收藏算计划、
  支持休息日/跳周末。
- **Anki 优先级**（A）：Relative overdueness（单因子、防饥饿哲学）；无考试日期/倒推/EXAM 权重
  （filtered deck 的 cram/review-ahead 除外）。
- **srs-benchmark**（A）：anki-revlogs-10k（约 7.27 亿次复习）+ TimeSeriesSplit；指标 Log Loss /
  RMSE(bins) / AUC——**只评预测精度，不评排程策略**。

### 8.5 实施提示（结论 → 代码位置）

| 结论 | 代码位置 |
|---|---|
| 掌握更新/下界（UNCERTAINTY_SCALE=1.2） | `core/domain/.../LearningProjector.kt`（projectMastery ~:976-1100、projectChatEvidence ~:591-640） |
| 证据八档 | `core/domain/.../MasteryEvidencePolicy.kt:64-165` |
| 写门常数 | `core/domain/.../MasteryWriteGate.kt`（0.7=:30、锚 1/2=:90-101、12h=:42、配额=:52,63、档位=:167-172、半权=:208-224） |
| FSRS/legacy 双实现与 ×0.45 | `core/domain/.../MemoryUpdateModel.kt`（FsrsMemoryUpdateModel:48、LegacyExponentialMemoryUpdateModel:130、×0.45=:153/191） |
| 排程打分与 PREREQ −2.0 | `core/domain/.../ReviewPlannerV2.kt`（权重表 :873-912、EXAM_WEIGHT=2.0 :900） |
| 考前线性 ramp | `core/data/.../StudyReviewPlannerService.kt:101-112`（EXAM_RAMP_DAYS=14 :570） |
| 重放引擎 | `LearningProjector.replay :391-589`；`StudyProjectionDrainer.commitFullReplay`；`KnowledgeNodeSuccessors`；superseded_by 落点 `ProblemOrganizationDao.kt:325-328` |
| 确认卡相位（待接真生产者） | `core/domain/.../TutorTurnSendStateMachine.kt`（AWAITING_CONSENT=:8、ConsentRequired=:57）；锁死先例 `TutorLobbyRoute.kt:163-164` |
| 校准通道 | `ReviewLogSink` → `StudySchedulingCalibration`（FSRS 拟合）→ `CalibrationReportBuilder`；review_log 表 `LearningEntities.kt:1021` |
