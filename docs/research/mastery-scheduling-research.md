# 学生掌握度建模与复习调度深度研究报告（详细版 v2）

**调研日期**: 2026-08-28（v2 扩充 2026-08-29）
**方法**: 三路并行网络深研（间隔重复算法前沿 / 记忆强化学习科学 / 学生数据采集粒度与特征工程）+ 代码库现状逐行对齐
**配套文档**: 《掌握度数学建模与行为数据映射》（mastery-math-modeling.md）、《三库关联设计》（three-store-linkage-design.md）、《掌握度调度总 spec》（mastery-scheduling-spec.md）

---

## 第一部分：间隔重复调度算法的科学前沿

### 1.1 SM-2 及其缺陷（我们为何不能停留在乘法式更新）

**SM-2 原始规则**（SuperMemo 1987/1991，来源：https://supermemo.guru/wiki/Algorithm_SM-2 ）：
- `I(1)=1 天`，`I(2)=6 天`，`I(n)=I(n−1)×EF`；`EF' = EF + (0.1 − (5−q)×(0.08 + (5−q)×0.02))`，q≥3 视为记住；忘记则重置到 I(1)。
- Anki 改造版（https://github.com/open-spaced-repetition/anki-sm-2 ）：四档 Again/Hard/Good/Easy；Again → ease×0.80 间隔清零；Hard → ease×0.85、interval×1.2；Easy → ease×1.15、interval×ease×1.3；逾期补偿 `+days_overdue/2`；ease 下限 1.3。

**公认缺陷**（来源：https://github.com/open-spaced-repetition/awesome-fsrs/wiki/ABC-of-FSRS ；https://supermemo.guru/wiki/Algorithm_SM-17 的 SM-15 弱点清单）：
1. 无可比的「记忆状态」——只有 ease 一个标量，无法表达同一间隔下不同难度材料的记忆差异；
2. 延期复习处理粗糙（线性拍脑袋补偿，不基于真实遗忘曲线）；
3. ease 与遗忘无真实对应（Bjork 二变量理论：一个标量不足以描述记忆，见第三部分 §3.5）；
4. 无法目标化保留率——不能回答「以 90% 回忆概率为目标，下次应在哪天」。

**与我们的对应**：`LearningProjector.projectMemory`（core/domain/LearningProjector.kt:736-743）的 `stability×(1+1.6w)` 等更新虽已带 stability/difficulty 双状态，但更新系数是固定乘法，本质仍是 SM-2 族——不感知复习时刻的遗忘程度。

### 1.2 FSRS（DSR 三变量模型）完整算法规格

FSRS 源自 MaiMemo 的 DHP 模型，是 Wozniak 三变量记忆模型的现代参数化（来源：https://www.maimemo.com/paper/ ；https://supermemo.guru/wiki/Three_component_model_of_memory ）。

**三变量**：
- **Retrievability R**：当前回忆概率，随时间衰减（不是持久状态，是状态的函数）；
- **Stability S**：R 从 100% 降到 90% 所需天数（记忆的「存储强度」代理）；
- **Difficulty D ∈ [1,10]**：材料固有难度，影响每次复习后 S 的增长倍数。

**遗忘曲线**（FSRS-4.5 起幂律，FSRS-6 decay 可训练）：
```
R(t,S) = (1 + FACTOR·t/S)^DECAY
FSRS-4.5: DECAY=-0.5, FACTOR=19/81
FSRS-6:   DECAY=-w20（默认 0.1542，范围 0.1–0.8），FACTOR = 0.9^(−1/DECAY) − 1
保证 R(S,S)=0.9（S 的定义点）
```
对比：FSRS v3 与 HLR 是指数 `R = 0.9^(t/S)` / `2^(−t/h)`——大延迟下低估保留（Wixted & Ebbesen 1991 幂律证据，https://doi.org/10.1111/j.1467-9280.1991.tb00175.x ；Murre & Dros 2015 Ebbinghaus 复刻 https://doi.org/10.1371/journal.pone.0120644 ）。

**初始状态**（首次 rating=G，G∈1..4）：
```
S0(G) = w[G−1]                 （FSRS-6 21 参数中的 w0..w3）
D0(G) = w4 − e^(w5·(G−1)) + 1  （柔和初始难度）
```

**成功复习更新**（跨日，G∈{Hard,Good,Easy}；Again 走遗忘分支）：
```
S'r = S·(1 + e^w8 · (11−D) · S^(−w9) · (e^(w10·(1−R)) − 1) · HardPenalty · EasyBonus)
HardPenalty = w15（G=Hard 时生效，<1）
EasyBonus   = w16（G=Easy 时生效，>1）
```
逐项解读：`(11−D)` 越难增益越小；`S^(−w9)` 越稳定增益越小（边际递减）；`(e^(w10·(1−R))−1)` **R 越低（遗忘越多）增益越大**——这就是「间隔效应」的数学化，也是我们当前实现完全缺失的项。

**遗忘更新**（Again）：
```
S'f = w11 · D^(−w12) · ((S+1)^w13 − 1) · e^(w14·(1−R))
并 clamp: S'f ≤ S·e^(−w17·w18)（遗忘后新稳定性不超过旧稳定性的一个折扣）
```

**同日重复（short-term，FSRS-5/6 专门分支）**：
```
S' = S·e^(w17·(G−3+w18)) · S^(−w19)     （FSRS-6 幂律形式）
SInc ≥ 1 对 G≥2（重复不会降低稳定性）
```

**难度更新**（FSRS-5 起均值回归防 ease-hell）：
```
D' = w7·D0(4) + (1−w7)·(D − w6·(G−3)·(10−D)/9)
```
线性阻尼（`(10−D)/9` 使高难度更难再升）+ 向 `D0(4)` 均值回归。

**下次间隔**（由目标保留率 r 反解遗忘曲线）：
```
I(r,S) = (S/FACTOR)·(r^(1/DECAY) − 1)
```

**参数版本演进**：v1=7 → v2=14 → v3=13 → v4=17 → 4.5=17 → v5=19 → v6=21。FSRS-6 关键改进：同日重复幂律化（w17/w18/w19）、decay 本身可训练（w20）。来源：https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm ；ts-fsrs constant.ts；py-fsrs DEFAULT_PARAMETERS。

**参数优化**：
- 损失：`loss = −[p·log R̂ + (1−p)·log(1−R̂)]`（BCE），BPTT 沿每卡复习序列训练；
- 输入：按卡分组、按时间排序的 `(rating, delta_t)` 序列，delta_t=两次复习间天数（日历日差分）；
- py-fsrs：Adam + CosineAnnealing，mini_batch=512，每卡最多 64 次复习参与；**复习日志 <512 条直接返回默认参数**；
- fsrs-rs：`<8` 条返回默认；`<64` 条只拟合初始稳定期（https://github.com/open-spaced-repetition/fsrs-rs/src/training.rs ）；
- 默认参数来自约 1 万用户、数亿次复习；
- `compute_optimal_retention`：在 0.7–0.95 网格上用随机最短路径仿真找总复习量最小的 desired retention（需 ≥512 条且带 review_duration）。

**基准表现**（https://github.com/open-spaced-repetition/srs-benchmark ；https://github.com/open-spaced-repetition/fsrs-vs-sm17 ）：
- Anki 10k 集合（约 3.5 亿次非同日复习）log-loss：FSRS-6 ≈0.346、FSRS-5/4.5 ≈0.35–0.36、FSRS v1 0.491、HLR 0.469；
- FSRS-6 vs SM-17（19 个集合约 69 万复习）：0.367 vs 0.432，83% 集合上不劣；
- 官方主张同保留率下比 SM-2 少 20–30% 复习（**仿真口径**，上线后须用真实 log-loss 验证，目标区间 0.35–0.45）。

### 1.3 半衰期回归 HLR（Settles & Meeder 2016，Duolingo）

来源：https://aclanthology.org/P16-1174/ （PDF 逐节读取）；https://github.com/duolingo/halflife-regression

```
p = 2^(−Δ/h)                （Ebbinghaus 指数，h=半衰期）
ĥ_Θ = 2^(Θ·x)               （半衰期 = 特征线性组合的指数）
ℓ = (p−p̂)² + α(h−ĥ)² + λ‖Θ‖²，其中 h = −Δ/log2(p) 是观测经验半衰期
特征 x：累计 seen x_n、累计 correct x⊕、累计 wrong x⊖（工程上 sqrt(1+right)/sqrt(1+wrong)）+ 每词稀疏 lexeme 特征
训练：SGD + L2，λ=0.1、α=0.01、η=0.001
```
- 数据：1290 万 Duolingo 学习轨迹；MAE=0.128，比 Leitner 基线降 45%+；
- 在线 A/B（百万学生）：HLR 组整体日间留存 +12%（去掉 lexeme 后）；
- **与 FSRS 的本质区别**：HLR 是全局回归（无每卡持久状态，特征从历史日志重算），适合「优先排序哪些该练」（ranking）；FSRS 是逐卡状态机，适合精确 next-due。我们的影子 HLR（`HLRPredictionAuditService`）与 FSRS 生产调度天然构成「排序模型 + 状态机模型」的混合，可共用同一份 `(delta_t, rating)` 日志但**必须各自独立训练**（HLR 的 history_seen/correct 特征会被 FSRS 重放改写而互相污染）。

### 1.4 DASH/Rocket（Lindsey et al. 2014）——把「能学多少」并入调度

来源：https://doi.org/10.1177/0956797613504302 ；DAS3H 变体 https://arxiv.org/abs/1905.06873
- `p = 2^(−Δt/h)`，h 是学生能力 α + 题目难度 δ + 历史曝光时间衰减计数 的函数；
- Rocket 一学期课堂实验：**个性化复习比集中复习多记住 16.5%，比一刀切均匀间隔多记住 10.0%**；
- 含义：调度器输入应是模型预测的掌握度而非日历时间；「掌握判定」（该题是否可下线）也可并入同一模型。

### 1.5 固定间隔为何不科学

- Karpicke & Roediger 2007（https://doi.org/10.1037/0278-7393.33.4.704 ）：扩张复习在 10 分钟短时占优，但 2 天后**等间隔**显著更好——关键不是「扩张」而是提取难度；
- Cepeda et al. 2006 元分析（839 项评估、317 实验，https://doi.org/10.1037/0033-2909.132.3.354 ）：最优 ISI 随保留期 RI 增长；
- Cepeda et al. 2008（1350+ 人，https://doi.org/10.1111/j.1467-9280.2008.02209.x ）：**最优间隔 ≈ 测试延迟的 10–40%**（1 周延迟→20–40%；1 年延迟→5–10%），间隔-成绩呈倒 U；
- Pavlik & Anderson 2008（https://doi.org/10.1037/1076-898X.14.2.101 ）：ACT-R 模型每次练习后按当时回忆概率动态调间隔，对照实验大效应量改善；
- 合成结论：**「按目标保留率反解间隔」（FSRS 的 I(r,S)）等价于让每次复习落在该题当前保留期的固定比例处**——老题（S 大）间隔自动变长且由遗忘形状决定。

### 1.6 睡眠与巩固

- Rasch et al. 2007 Science（https://doi.org/10.1126/science.1135074 ）：慢波睡眠中重放线索，保留 +11%；
- 工程含义：复习应跨过至少一夜；把 due 落在用户早晨学习窗而非午夜随机点；同日多次复习收益有限（FSRS-6 short-term 分支收益小、Anki 手册劝 learning 步长 <1 天）。

### 1.7 工程参考与开源库

- py-fsrs（Scheduler+Optimizer；Card: card_id/state/step/stability/difficulty/due/last_review；ReviewLog: card_id/rating/review_datetime/review_duration；只支持 UTC）：https://github.com/open-spaced-repetition/py-fsrs
- ts-fsrs（Card 多 elapsed_days/scheduled_days/reps/lapses；enable_fuzz 默认 false，**迁移时须显式开**）：https://github.com/open-spaced-repetition/ts-fsrs
- fsrs-rs（Anki 量产；MemoryState{stability,difficulty}；含 `memory_state_from_sm2(ease,interval,retention)` 迁移函数、optimizer、simulation、evaluate）：https://github.com/open-spaced-repetition/fsrs-rs
- Anki FSRS 手册（desired retention 档位、compute minimum recommended retention）：https://docs.ankiweb.net/deck-options.html#fsrs
- SM-17/18/20 思想：SInc[D,S,R] 三元表 → SM-20 用 RL 优化 4 万参数再蒸馏为 ~40 参数全局模型（https://supermemo.guru/wiki/Algorithm_SM-20 ）——「矩阵查表 vs 参数模型」之争，FSRS 走参数模型路线。

---

## 第二部分：记忆强化机制（学习科学实证）

### 2.1 提取练习/测试效应

- Roediger & Karpicke 2006（https://doi.org/10.1111/j.1467-9280.2006.01693.x ）：5 分钟时重读组更好；2 天/1 周后测试组显著更好——**即时表现与长期记忆判若两人**，学生「看熟」的流畅感是假象。
- Rowland 2014 元分析（https://doi.org/10.1037/a0037559 ）：自由回忆（更费力）的测试效应大于再认；双峰分布模型（Kornell, Bjork & Garcia 2011）：一次成功提取把项目从「低强度群」移入「高强度群」，重读只整体微增。
- Soderstrom & Bjork 2015（https://doi.org/10.1177/1745691615569000 ）：learning ≠ performance，即时表现误导。

### 2.2 先猜后学（errorful generation / pretesting effect）

- Kornell, Hays & Bjork 2009（https://doi.org/10.1037/a0015729 ）：6 实验，检索完全失败后再看答案仍优于直接呈现；
- Richland, Kornell & Kao 2009（https://doi.org/10.1037/a0016496 ）：教室情境复制；
- **设计含义**：错题重做/新题首见默认「先作答（可猜）→再给答案+解析」；学生的错误答案是检索网络的路标，不是要抹掉的失败。

### 2.3 反馈时机与内容

- Pashler et al. 2005（https://doi.org/10.1037/0278-7393.31.1.3 ）：**答错后给正确答案 → 1 周保留 +494%**；答对后反馈无差异；
- Butler & Roediger 2008（https://doi.org/10.3758/MC.36.3.604 ）：反馈既增强正向又压制错误干扰项侵入（lure intrusion）；只给对/错无用，必须给正确答案本体；
- Butler, Karpicke & Roediger 2008（https://doi.org/10.1037/0278-7393.34.4.918 ）：**低自信的答对必须反馈**——反馈使 1 周保留翻倍（纠正元认知而非记忆）；
- Mullet et al. 2014（https://doi.org/10.1016/j.jarmac.2014.05.001 ）：真实课堂延迟反馈在新题迁移上更好，但学生自报感觉即时更好（元认知脱节）。

### 2.4 交错练习（interleaving）

- Rohrer & Taylor 2007（https://doi.org/10.1007/s11251-007-9015-8 ）：交错在延迟测验显著更好，分块在即时测验伪装更好；
- Rohrer, Dedrick & Stershic 2015（https://doi.org/10.1037/edu0000001 ）：126 名七年级生、3 个月、1750 题：交错 d=0.42（1 天）/ d=0.79（30 天）；
- 机制：判别负荷（discriminative contrast）——连续同题型会「泄题」策略线索；
- Hartwig, Rohrer & Dedrick 2022（https://doi.org/10.1037/xap0000391 ）：学生普遍误判，觉得交错「更难更不愉快」；
- **设计含义**：同日会话混合 ≥3 知识点；把易混淆对排进同组。我们 `ReviewPlannerV2` 已有同题族不相邻、同科目不超 run 的硬约束（audit §7.2）——方向正确，需补「近混淆对强制同场」。

### 2.5 存储强度 vs 提取强度（Bjork 新失用理论）

- Bjork & Bjork 1992（A new theory of disuse, in *From learning processes to cognitive processes*, Erlbaum）：存储强度几乎不衰减；提取强度快速衰减、由最近激活决定；间隔让提取强度掉下去，下次提取更难——而这正是更强提取所需的条件。
- Landauer & Bjork 1978（Practical aspects of memory, p.625）：提取间隔逐渐拉长可获得近似「无遗忘」保持曲线。
- **「已掌握的题还复习吗」**：过度学习无收益（Rohrer & Taylor 2006，https://doi.org/10.1002/acp.1266 ：3 题 vs 9 题，1 周/4 周无差异）→ 毕业出紧急队列；但毕业≠删除——保留为低频维护（1–3 个月一次）或考前热身；「答对一次就移除」被 Karpicke & Roediger 2008（Science, https://doi.org/10.1126/science.1152408 ）证伪：一答对就停练的条目 1 周内大幅遗忘。

### 2.6 元认知与自评

- Dunlosky & Rawson 2012（https://doi.org/10.1016/j.learninstruc.2011.08.003 ）：越自信的材料后来成绩越差（过度自信预测更差保留）；
- Karpicke 2009（https://doi.org/10.1037/a0017341 ）：自选会主动移除已答对条目；Kornell & Son 2009（https://doi.org/10.1080/09658210902832915 ）：把自测当「诊断」而非「学习本身」的元认知错位；
- Hartwig & Dunlosky 2011（https://doi.org/10.3758/s13423-011-0181-y ）：高成就学生更常用自测+间隔；
- **结论**：调度必须由系统接管；自评是调度信号（FSRS 式四档）而非让学生自选内容；低自信答对 → 强制次日重做 + 反馈。

### 2.7 生成效应与情境变异

- Slamecka & Graf 1978（https://doi.org/10.1037/0278-7393.4.6.592 ）：自己生成的信息记得更牢；
- Smith, Glenberg & Bjork 1978（https://doi.org/10.3758/BF03197465 ）：情境变异增强提取路径韧性；
- 变式题（换数字/表述/考查角度）逼迫概念级而非表面特征作答——毕业判定用变式最可靠（变式生成本轮明确不做，可用同 KC 换题替代）。

### 2.8 十条可执行设计原则（供 spec 引用）

P1 默认隐藏答案先作答（三场景：新题/复习重做/错题重做）；P2 解析与结果绑定且必须给答案本体；P3 按「检索失败历史」而非绝对时间排优先级（多次答错 > 低自信偶对 > 高自信偶对 > 长期未错）；P4 会话强制交错 ≥3 知识点 + 易混淆对同场；P5 系统接管调度 + 理由可见化（「为什么今天是这几题」）；P6 低自信答对进次日队列；P7 毕业=降频维护非删除（连续 3 次间隔成功提取→1–3 个月维护）；P8 毕业前变式判定；P9 会话 10–25 分钟上限、宁散勿集；P10 错题本升级为「复习仪表盘」（提取强度曲线/队列/毕业态/易混对可见）。

---

## 第三部分：数据采集——记录什么、精确到哪

### 3.1 各算法所需的最小特征集（消融证据）

- **FSRS**：只用 `rating(1-4) + delta_t` 即达 SOTA；rating 是唯一元认知输入（难度 D0(G) 与间隔更新的核心）。响应延迟/情境均不用。
- **HLR**：只用 `history_seen / history_correct / session_seen / session_correct（计数，sqrt 变换更优）+ lag`；**公开数据无 response time**；词素特征去掉只微降 MAE，去掉半衰期目标 MAE 翻倍（https://github.com/duolingo/halflife-regression README 消融）。
- **BKT**：4 参数（p(L0)/p(T)/p(G)/p(S)），观测仅技能级对错序列；guess/slip 就是错误类型学的模型化。来源：https://en.wikipedia.org/wiki/Bayesian_knowledge_tracing
- **DKT/SAKT**（https://arxiv.org/abs/1506.05908 ；https://arxiv.org/abs/1907.06837 ）：KC ID+对错序列，LSTM/自注意力；稀疏数据泛化差、不可解释。

### 3.2 Response latency 的正确定位

- Benjamin, Bjork & Schwartz 1998（https://doi.org/10.1037/0096-3445.127.1.55 ）：检索流畅性是误导性元记忆线索——慢而费力的提取反而记得牢；
- Grimaldi & Karpicke 2010（https://doi.org/10.1016/j.jml.2010.05.002 ）：更快响应预测更差最终回忆；
- Pyc & Rawson 2009（https://doi.org/10.1016/j.jml.2009.01.004 ）：提取努力假说——费力促进记忆但受难度调节；
- **结论**：latency 不进遗忘曲线；它用于**修正对错证据可信度**（快答对→疑猜错 guess；慢答错→疑真不会），参考 Baker et al. 的 contextual guess/slip 估计（https://doi.org/10.1007/978-3-540-69132-7_44 ）。

### 3.3 粒度：instance / KC / topic 三层

- DataShop 实践（https://pslcdatashop.web.cmu.edu/about ；Koedinger et al. 2012 KLI 框架 https://doi.org/10.1111/j.1551-6709.2012.01245.x ）：作答记在 instance（题次）级；学习曲线按 KC 聚合机会序号——好的 KC 模型曲线平滑单调降，不降则拆分/合并 KC（Learning Factors Analysis, https://doi.org/10.1007/s11251-005-1310-x ）；
- KC 太细→数据稀疏噪音大；太粗→异质题混合曲线不平滑；**最佳粒度 = 能划出平滑学习曲线的技能块**；
- 调度状态（S/D）挂 KC 或题；报告/安排挂 topic；
- 单题记录不够的场景：变式与迁移——同一 KC 下不同题的正确率构成迁移证据（DAS3H 多技能标签 https://arxiv.org/abs/1905.06873 ）。

### 3.4 错误类型学（slip / mistake / bug / lapse）

- Norman 1981（https://doi.org/10.1037/0033-295X.88.1.1 ）：slips（执行错）vs mistakes（计划错）+ memory lapses；
- VanLehn Repair Theory（https://doi.org/10.1207/s15516709cog0404_3 ）：系统性 bug 可生成式预测；
- BKT 的 p(G)/p(S) 是错误类型学的模型化；
- **分通道调度**：概念错误→重教材料（我们的 knowledge_teaching_material 表天然是重教载体）；粗心 slip→低频维护；审题错→归因前置 KC/注意力；真实遗忘→正常 FSRS 排期；
- LLM 自动错因分类：arXiv:2412.03765（初中代数 55 误区上 ~83.9% precision/recall）；arXiv:2310.02439（会算但识别误区弱）→ **LLM 标注 + 置信度 + 用户可纠正**的混合流程，不做实时硬闸。

### 3.5 情境元数据

- 睡眠巩固（Rasch 2007 Science https://doi.org/10.1126/science.1138581 气味线索重放；综述 https://en.wikipedia.org/wiki/Sleep_and_memory ）：跨夜间隔更有效；
- 疲劳效应（vigilance decrement, https://doi.org/10.1007/s00221-011-2749-1 ）：会话后段错≠掌握崩，需 session 内位置校正；
- 时段/chronotype（https://doi.org/10.2139/ssrn.6169806 ）：无通用最优时段，但记录时段可建模个人峰值；
- 场景/设备：影响证据强度与 latency 标准化，中收益。

### 3.6 隐私与最小化

- 本地优先 = 原始行为数据不出设备，天然规避 FERPA/COPPA 数据最小化要求（https://studentprivacy.ed.gov ；https://en.wikipedia.org/wiki/Children%27s_Online_Privacy_Protection_Act ）；
- 不记：毫秒级轨迹、录音录像、逐键流、设备遥测、第三方埋点、无限期原始副本；
- 云/备份只传派生摘要（KC 计数、S/D/R），不传原始作答文本。

### 3.7 推荐字段清单（三档，详见 spec 第 3 节完整版）

**必记**：correct、self_rating（三档升四档）、lag_days、stability/difficulty、seen/correct/lapse 计数、kc_ids、duration_ms。
**值得记**：response_latency（修正信号）、error_type+confidence、hint_count（现恒 0）、revealed_before_answer、intra_session_position、session_elapsed_min、time_of_day、low_confidence_correct、last_evidence_reason（可解释性）。
**不必记**：轨迹/音视频/逐键/遥测。

---

## 第四部分：当前实现 vs 科学目标差距表

| # | 差距 | 当前（file:line） | 科学目标 | 严重性 |
|---|------|------------------|---------|--------|
| G1 | 遗忘曲线是指数 | ForgettingCurve.kt:52-54 `0.9^(t/S)` | FSRS-6 幂律，decay 可训练 | 高 |
| G2 | 稳定性增长不看 R | LearningProjector.kt:736-743 固定乘法 | S'=f(S,D,R@review)，R 低增益大有界 | 高（核心） |
| G3 | 难度无均值回归 | LearningProjector.kt:740-744 线性 ±0.03~0.12 | `D' = w7·D0(4)+(1−w7)(D−w6(G−3)(10−D)/9)` | 中 |
| G4 | 无复习日志表 | 影子 HLR 有 prediction/outcome 表但 hintCount 恒 0（RoomBackedStudyExperienceRepository.kt:690-697） | `(rating1_4, delta_t, duration)` 日志，≥512 条个性化 | 中 |
| G5 | 自评/视觉证据可刷 | 每次自评新 requestId 叠加（SmartMistakeBookRoot.kt:333；LearningProjector.kt:789-796） | 同 practiceUnit 冷却窗/上限 | 高 |
| G6 | reveal 双重惩罚 | LearningProjector.kt:601-608,738,1025（0.45 衰减 + POST-reveal 满强度负作答） | 合并为单一证据 | 中 |
| G7 | 讲解暴露无权重语义 | LearningProjector.kt:700-713 硬编码 weight=0.0+0.45 | 弱证据通道或并入 Again | 中 |
| G8 | 选题无错因/前置维度 | ReviewPlannerV2.kt:372-446 仅 DUE/WEAKNESS/不确定性 | 错因分通道+前置优先+易混对同场 | 中 |
| G9 | 毕业语义缺失 | ClearlyMasteredForSkipPolicy 仅跳过 | 毕业=1–3 个月低频维护队列 | 中 |
| G10 | 记录缺字段 | 无 error_type/confidence/revealed_before/last_evidence_reason | 见 3.7 | 中 |

**已做对的**：FTS 全参数绑定无注入；投影幂等 CAS+fingerprint；planner 会话内交错硬约束；本地优先数据不出设备。

---

## 第五部分：改造路线图（依赖排序，细节见 spec）

- **阶段 A 止血**（不依赖科学升级）：A1 自评冷却窗；A2 reveal 双罚合并；A3 hintCount 接真；A4 投影加 lastEvidenceReason。
- **阶段 B 调度科学化**：B1 幂律曲线；B2 稳定性更新依赖 R（FSRS 公式族+默认参数）；B3 难度均值回归；B4 desiredRetention 设置；B5 review_log 表（rating 四档映射：独立答对=Good、提示答对=Hard、卡住=Again、**看答案=Again 不许 Hard**）；B6 参数优化器（远期，≥512 条）。
- **阶段 C 选题升级**：C1 错因分通道（LLM 辅助+可纠正）；C2 前置缺口优先；C3 易混淆对同场；C4 毕业=低频维护队列；C5 会话节奏产品化。

**迁移坑**：评级语义（看答案≠Hard）；同日连刷勿灌训练集（optimizer 只计 delta_t>0）；全 UTC、日历日差分；小数据用户用默认参数；fuzz 别关；「少复习 20-30%」是仿真口径须真实 log-loss 验证。

---

## 来源清单

FSRS：https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm ；…/ABC-of-FSRS ；…/The-mechanism-of-optimization ；https://github.com/open-spaced-repetition/srs-benchmark ；https://github.com/open-spaced-repetition/fsrs-vs-sm17 ；https://github.com/open-spaced-repetition/py-fsrs ；https://github.com/open-spaced-repetition/ts-fsrs ；https://github.com/open-spaced-repetition/fsrs-rs ；https://docs.ankiweb.net/deck-options.html#fsrs ；https://supermemo.guru/wiki/Algorithm_SM-2 /SM-17/SM-20
HLR：https://aclanthology.org/P16-1174/ ；https://github.com/duolingo/halflife-regression
DASH：https://doi.org/10.1177/0956797613504302 ；DAS3H：https://arxiv.org/abs/1905.06873
间隔：https://doi.org/10.1037/0033-2909.132.3.354 ；https://doi.org/10.1111/j.1467-9280.2008.02209.x ；https://doi.org/10.1037/1076-898X.14.2.101 ；https://doi.org/10.1037/0278-7393.33.4.704
幂律：https://doi.org/10.1111/j.1467-9280.1991.tb00175.x ；https://doi.org/10.1371/journal.pone.0120644
测试效应：https://doi.org/10.1111/j.1467-9280.2006.01693.x ；https://doi.org/10.1037/a0037559 ；https://doi.org/10.1177/1745691615569000
先猜后学：https://doi.org/10.1037/a0015729 ；https://doi.org/10.1037/a0016496
反馈：https://doi.org/10.1037/0278-7393.31.1.3 ；https://doi.org/10.3758/MC.36.3.604 ；https://doi.org/10.1037/0278-7393.34.4.918 ；https://doi.org/10.1016/j.jarmac.2014.05.001
交错：https://doi.org/10.1007/s11251-007-9015-8 ；https://doi.org/10.1037/edu0000001 ；https://doi.org/10.1037/xap0000391 ；https://doi.org/10.3758/s13423-011-0181-y
元认知：https://doi.org/10.1126/science.1152408 ；https://doi.org/10.1037/a0017341 ；https://doi.org/10.1080/09658210902832915 ；https://doi.org/10.1016/j.learninstruc.2011.08.003
过度学习：https://doi.org/10.1002/acp.1266
latency：https://doi.org/10.1037/0096-3445.127.1.55 ；https://doi.org/10.1016/j.jml.2010.05.002 ；https://doi.org/10.1016/j.jml.2009.01.004 ；https://doi.org/10.1007/978-3-540-69132-7_44
KC：https://pslcdatashop.web.cmu.edu/about ；https://doi.org/10.1111/j.1551-6709.2012.01245.x ；https://doi.org/10.1007/s11251-005-1310-x
错因：https://doi.org/10.1037/0033-295X.88.1.1 ；https://doi.org/10.1207/s15516709cog0404_3 ；https://arxiv.org/abs/2412.03765 ；https://arxiv.org/abs/2310.02439
睡眠：https://doi.org/10.1126/science.1135074 ；https://doi.org/10.1126/science.1138581 ；https://en.wikipedia.org/wiki/Sleep_and_memory
Bjork 理论：Bjork & Bjork 1992, A new theory of disuse, in *From learning processes to cognitive processes* (Erlbaum)；Landauer & Bjork 1978, Practical aspects of memory, p.625
隐私：https://studentprivacy.ed.gov ；https://en.wikipedia.org/wiki/Children%27s_Online_Privacy_Protection_Act

**UNVERIFIED 声明**：FSRS-6 公式细节以 wiki 转述 + py/ts/rs 三库源码交叉核对为准；「512 条门槛」是 py-fsrs 工程硬编码；「少复习 20-30%」是官方仿真口径；Bjork 1992 与 Landauer 1978 为书籍章节无 DOI。
