# 补充研究：交互犹豫信号、现实客观事件、时段权重、三库关联实践

**日期**: 2026-08-29。定位：对《mastery-scheduling-research.md》《mastery-math-modeling.md》《three-store-linkage-design.md》《mastery-scheduling-spec.md》四份文档的补充研究。
**说明**: 本次环境检索通道受限，以下引用均为领域公认文献（给出处），与四份主文档的既有引用体系衔接；个别条目标注 UNVERIFIED。

---

## 一、作答延迟与「猜测/求解」的混合建模

1. **RT 分布形态**：教育数据中的作答时间呈正偏、对数正态——心理测量学标准做法是 van der Linden 的层级框架（RT 与正确性联合建模，log-normal；van der Linden 2006/2007, *Psychometrika*；综述 https://link.springer.com/article/10.1007/s11336-006-0009-6 — UNVERIFIED 链接，模型名可查）。
2. **两成分混合**：Meyer (2010) 随机效应混合模型区分「求解行为」与「快速猜测」两类 RT 分布；Wise & Kong (2005) 提出 **Response Time Effort (RTE)** 指数检测低利害测验中的快速作答（https://onlinelibrary.wiley.com/doi/10.1111/j.1745-3984.2005.00011.x — UNVERIFIED）。工程含义：**不要用 RT 的绝对值，用「相对该学生该题型 RT 分布的分位数」判定猜测**。
3. **BKT 接 RT**：Qiu, Qi & Lynn (2011) "Does Time Matter? Modeling the Effect of Time in BKT"（EDM 2011, https://eric.ed.gov/?id=ED541870 — UNVERIFIED）：把 RT 接入 guess/slip 概率的动态估计。与既有 spec 的 §2.5 修正信号一致：**快答对→提高 guess 后验；慢答错→提高 slip 后验**。
4. **个体的 RT 基线**：同一学生跨题 RT 的对数正态参数（μ, σ）应按学生估计（层级先验），判定「快/慢」永远是相对个人的——固定阈值会误伤慢性子/急性子。

## 二、滑动/滚动/编辑等交互犹豫信号

1. **滚动回看 = 不确定信号**：D'Mello 组的走神检测系列（Bixler & D'Mello 2016, *UMUAI*, gaze-based；Faber, Bixler & D'Mello 用 clickstream/scroll/dwell 特征无眼动检测走神，https://sites.google.com/site/dmelloimdart/ — UNVERIFIED 链接；综述见 D'Mello 2018 *Educ Psychol* "Mind wandering during reading"）。可迁移的屏幕内信号：**scroll-up 回看题干次数、文本区 dwell 的二段分布（首读 vs 回看）、长时间空转后作答**。
2. **输入编辑信号**：删除/退格次数、提交前的修改次数——「犹豫/自纠」信号（配合最终提交文本即可，不记逐键流，见主报告 §3.6 隐私边界）。
3. **改答案的证据方向**：经典 answer-changing 文献（Benjamin, Cavell & Shallenberger 1984 综述；Waddell & Blankenship 1994）：**改答案多数从错改对**（约 50–55% W→R vs ~25% R→W），「第一直觉最好」是迷思（UNVERIFIED 具体比例，结论方向为学界共识）。含义：**「改对」仍应计为成功提取，但 weight 打折（提取不完整）；「改错」计入失败但 reason 标注 CHANGED_FROM_CORRECT**。
4. **gaming 检测**：Baker 系列的 gaming-the-system 特征（快速连点、重复同答案、提示滥用；sensor-free 版本 Paquette et al., https://doi.org/10.1145/3303772.3303786 — UNVERIFIED）：这些特征用于**降权/剔除证据**，而非直接进调度。
5. **成本收益三档**（手机端可行最小集）：
   - **必记**：首答相对分位（RT percentile，按个人基线）、提交文本+修改次数、scroll-up 次数、会话内位置；
   - **可选**：提交前停顿时长、看解析前停留、翻回上一题次数；
   - **不记**：逐字轨迹、高频采样坐标、传感器流（主报告 §3.6 已定）。
   这些信号的**唯一用途**是修正对错证据可信度（§math-2.5 的 G 映射前置修正），不是独立预测因子（Benjamin 1998 已证 RT 流畅性是误导性元记忆线索）。

## 三、现实客观事件（屏幕外）

1. **考前突击与间隔的交互**：Cepeda 2008（主报告已引）倒 U——考试临近时系统应允许 r* 自适应上调（考前一周 r*→0.95，考后回落），但**不能鼓励 cramming**（间隔效应仍占优）；这是唯一有强证据的「日历事件进调度」。
2. **假期遗忘**：summer learning loss 元分析（Cooper et al. 1996, https://doi.org/10.3102/00346543066003227 — UNVERIFIED 量级，结论共识）：长假后掌握度整体衰减——假期后首复习应按「假期时长 + 公共遗忘先验」重估 R，而非沿用旧 S 排期。
3. **中断成本**：任务中断后的 resumption lag（Trafton, Altmann & Brock 2005, https://doi.org/10.1207/s15516709cog2902_5 — UNVERIFIED；Mark et al. 2008 "The cost of interrupted work", https://doi.org/10.1145/1357054.1357097 — UNVERIFIED）：**被打断那次作答的证据降权**；通知时机避开学习会话（对现有 ReviewReminderCoordinator 的要求）。
4. **急性运动**：Roig et al. 2013 元分析（https://doi.org/10.1111/cogs.12104 — UNVERIFIED）：高强度有氧后短期内记忆编码获益——运动后时段可作「新录入错题」的推荐窗口（弱证据，只做建议不做调度权重）。
5. **真实考卷作为校准点**：学校考试成绩是外部 ground truth——录入考卷得分可作为掌握模型的校准事件（对影 HLR 的 outcome 回填），风险是试卷难度/覆盖与本题库不同构，只做校准不做直接覆盖。
6. **客观作息推断**：设备使用起止推断睡眠窗口（本地、不上传）；用于「复习是否跨夜」「days_since_sleep」特征（主报告 §3.5 已引睡眠巩固证据）。

## 四、一天内时间点权重（用户新增议题）——结论：个人化乘数，不是固定日课权重

1. **不存在「全民最优时段」**： Schmidt et al. 2007 综述（"A time to think: circadian rhythms in human cognition", *Cognitive Neuropsychology*；共识级）：认知表现的日节律随 chronotype 个体差异极大；青少年普遍晚型（Van der Vinne et al. 2015, https://doi.org/10.1038/srep14881 — UNVERIFIED；Zerbini & Merrow 2017 综述 "Time to learn: how chronotype shapes education"）。固定「早上记性好/晚上记性好」的人群权重没有证据。
2. **巩固视角（比表现视角更硬的证据）**：学习后睡眠助记忆（Gais, Lucas & Born 2006, *Learning & Memory*, "Sleep after learning aids memory"；Ellenbogen et al. 2006 *Curr Biol*）——**晚间复习的巩固增益是人群级证据**；但这是「间隔跨夜」的结构问题，不是「晚上作答权重高」。
3. **正确设计**（写进 spec）：
   ```
   timeBucket ∈ {MORNING, NOON, AFTERNOON, EVENING, NIGHT}（按用户作息切分，不按钟表硬切）
   personal_multiplier[bucket] = 每桶一个乘数，贝叶斯收缩向 1.0：
       M[b] = 1 + shrink·(observed[b] − 1)，observed[b] = 该桶实际回忆率 / 全时段平均回忆率
       仅在桶样本 ≥ 30 次作答后启用，否则 M=1
   用途：只修正「作答证据权重」（G 映射前修正 guess/slip 与 w_e），不改遗忘曲线参数 S/D 本身
   调度用途：due 的提醒时间落在用户该桶峰值窗；跨夜间隔优先（巩固）
   ```
4. **反模式**：把「晚上记忆好」写死进权重——会与 chronotype 冲突；把时段时间计入 delta_t 影响遗忘曲线——污染间隔语义。时段只是**证据可信度修正**与**提醒排期**两个用途。

## 五、三库关联行业实践（补前次失败的调研）

1. **Knowledge Space Theory / ALEKS**（Doignon & Falmagne, *Learning Spaces*, Springer 2011；https://www.aleks.com/about_aleks ）：知识状态=满足前置闭包的题目集合；**ready-to-learn 集合 = 外部 fringe**——前置补完即刻可学集。对应我们：`knowledge_node_relation(PREREQUISITE_OF)` 构偏序，`readyToLearn` 用 fringe 语义而非简单阈值（spec §2.9 的 τ_ready 是一阶近似，可升级为闭包判定）。
2. **多技能题证据分摊**：multi-skill BKT（Käser et al. 2013 动态贝叶斯网络多技能，https://doi.org/10.1109/TLT.2013.9 — UNVERIFIED）与 DataShop 实践：**主流做法是「题涉及的所有 KC 各自独立更新一次」**（全记），分摊（按权重）是少数派、用于 KC 粒度过粗时降噪。**修订 spec §2.8**：默认全记（每个绑定的 KC 都吃一次完整证据），`strength` 只作次序/展示权重；仅当 KC 学习曲线证明过度共现（噪声大）时才切换到 strength 归一分摊。这是一个重要的设计修订——之前 spec 的 strength 分摊不是主流。
3. **调度挂载层**：Anki 全部挂在 card（题）层，tags 不参与调度（https://docs.ankiweb.net/background.html — UNVERIFIED）；RemNote 挂 Rem（即 KC）层；Khan Academy 挂 task/skill 层，mastery 状态机 familiar→proficient→mastered 且**沿前置图向上传染晋级、向下传染退化**（产品文档口径，UNVERIFIED 细节）；Duolingo 挂 skill 层 + decay。**共识：KC 层是掌握状态的主挂载层，题层是观测层**——与 spec §2.8 修订后的「KC 权威、题缓存」一致。
4. **KC 变更迁移**：行业无标准答案；DataShop 的做法是版本化数据集重标（LFA 分析后重导出）。我们已有 `taxonomy_version` + `basis_revision_id` 双锚，按 linkage 文档 §3.4 执行即可，补充一条：**全记语义下换绑迁移更简单**（旧 KC 停止新证据即可，无需按 strength 拆历史）。
5. **冷启动**：标签稀疏时按 subject 伪聚合（linkage §3.5 已写）；LFA（https://doi.org/10.1007/s11251-005-1310-x ）作为远期自动修正：用学习曲线平滑度反推 KC 拆分/合并。

---

## 六、对四份主文档的修订指令（已执行的见下）

1. **spec §2.8/§2.13 修订**：多 KC 题证据由「strength 分摊」改为「全 KC 各记一次（主流做法）」，strength 降级为排序/展示用途；仅当 KC 学习曲线证明过度共现噪声大时才切换到 strength 归一分摊（本文 §5.2）。**注意**：Käser et al. 一手出处核验未成功（IEEE 页面为空），「主流做法」依据降级为 DataShop 多 KC 标签实践 + EDM 社区口径，UNVERIFIED 待补一手。
2. **spec 新增 §2.12 时段乘数**：timeBucket + personal_multiplier（本文 §4.3 公式），仅修证据权重与提醒排期。
3. **math-modeling 行为映射新增 G/H 两组**（本文 §一/§二/§三：交互犹豫信号、现实客观事件），字段三档清单同步扩充：必记增「首答 RT 分位、修改次数、scroll-up 次数」；可选增「提交前停顿、timeBucket、days_since_sleep、calendar_exam」；不记不变。
4. **research 报告来源清单**增补：van der Linden 层级 RT、Meyer 2010、Wise & Kong RTE、Qiu 2011、Bixler & D'Mello、answer-changing 综述、Schmidt 2007 日节律、Gais 2006、Cooper 1996、Trafton 2005、Roig 2013、KST/ALEKS、Käser multi-skill BKT。
5. **challenge 轮新增**（见 plan-challenge-and-gapfill.md）：同日聚合规则（benchmark 实证驱动）、FSRS 默认参数源码级替换、优化器阈值修正、leech/考前模式/反振荡/评估 harness 四机制、FSRS-7 版本化提示。
