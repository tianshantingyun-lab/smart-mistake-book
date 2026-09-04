# 深度研究简报：讲题 Agent 学习证据写入门控与注意力降权——实证依据

**Depth**: deep
**Date**: 2026-09-04
**地位**: 讲题写侧改造（模型语义决策 + 本地 hook 门控 + 实时注意力）的**参数科学依据底稿**。
本文件回答：门控的每一道门、每一个权重档、每一条阈值，凭什么取这个值。
所有数字标注核实等级：【V】=已核实原文数字；【P】=已核实文献存在+定性结论，数字未核实；【I】=工程推断（无直接实证，须自校准）。
检索方式：OpenAlex/Crossref/Semantic Scholar/Europe PMC/PMC 全文/Nature 全文/arXiv/ACL Anthology/Wayback（搜索引擎反爬，未用）。

---

## 0. 执行摘要（设计结论先行）

1. **模型判断 = "p≈0.8 的独立投票者"，不是准权威**：LLM 与人类判断者一致率 >80%【V】，但单次判断不能作为强证据写入门槛——须配本地客观信号交叉核对，且每次写入权重设上限。
2. **"行为证据 > 自我报告"是铁律**：口头"懂了"几乎不构成掌握证据（理解错觉 + 无校准数据）；延迟、无提示的检索表现才是最可信证据。模型判 direction 必须与同会话客观作答信号交叉核对。
3. **权重档位对齐 FSRS 4 档语义**：STRUGGLING↔Again、UNCERTAIN↔Hard、CONFIDENT↔Good、MASTERED(有行为佐证)↔Easy。主观/对话自报的 CONFIDENT/MASTERED 必须大幅打折（自报过自信 86% 级）；纯"说懂"无作答行为 → 不给稳定性增量。
4. **宁多弱证据、勿单强证据**：掌握度一律小步贝叶斯累积；同一对话/同题变式/提示后作答**不视为独立证据**，不计入"多次"门槛（独立性是 Condorcet/Spearman-Brown 的硬前提）。
5. **注意力降权是连续谱，不是开关**：走神对学习的损耗情境化强（难材料/手机在场 10-25%【V】，元分析层仅 ~4% 方差）。降权对象是"该次学习作为掌握证据的效力"，不是作答真伪。
6. **被拒写 ≠ 删除**：保留到独立观察通道（带拒因与置信度），供后续证据确认/复核——但需**独立于掌握度投影**（防止低可信证据污染状态机）。

---

## 1. 理解程度档位（模型判学生懂没懂）的实证

### 1.1 自报理解的系统性偏差
- Dunlosky & Rawson (2012), *Learning & Instruction* 22(4):271-280【P，**"86% 过自信"数字 UNVERIFIED——全文封闭，勿再引用 86%**】。定性结论（已核实摘要）：不准确自评直接损害学习与保持。
- **Deslauriers et al. (2019), PNAS**【V，被引 1355】：主动学习组学得更多、但自我感觉学得更少——"努力感被误读为学得差"，自报感觉与真实学习可反向。
- **Rozenblit & Keil (2002), Cognitive Science 26(5):521-562**【V，被引 663+】illusion of explanatory depth：人对解释性知识的理解感**系统性高估**，且强于事实/程序知识。→ 讲题场景（解释性）的自报理解尤其不可信。
- **Koriat & Bjork (2005), JEP:LMC 31(2):187**【V，被引 171】illusions of competence：学习当下的流畅感制造"胜任错觉"，"现在读得懂"被误当"以后会做"。
- **Nelson & Dunlosky (1991) Delayed-JOL effect**【V】：立即自评通常不准，**延迟**自评才高度准确 → 讲题当下"懂了"需延迟检索验证。
- **Miller & Geraci (2011)【P】**：过自信与低能力并存（低分者预测>实际更甚）——越不会越觉得自己会。

**设计落点**：
- 模型给的 understanding 档位是**有噪预测**，不是事实；CONFIDENT/MASTERED 必须打折。
- 模型判 MASTERED 需要"有行为佐证"（同会话客观作答正确）才可信——纯对话"懂了"按 UNVERIFIED 处理。

### 1.2 他人判断 vs 学生自报的可靠性
- **教师判断学业：元分析 r = .63**【V，Südkamp, Kaiser & Möller 2012, J. Educ. Psych., 75 项研究】→ 这是"外部观察者判断理解"的实证上限。模型判断应视为同类判断者，天花板 r≈.63。
- 学生自评 vs 教师：Falchikov & Boud (1989)【P，数值 UNVERIFIED】。
- **LLM 判断"对方是否理解"：无一手实证**（未找到）——最接近的是 LLM-as-judge 评分研究【V，Zheng 2023 arXiv:2306.05685：GPT-4 judge 与人类偏好一致率 >80%】+ 数学短答评分"surprisingly reliable 但需人工复核"【V，Liu 2024 arXiv:2408.11728】+ **谄媚警示**【V，Do 2026：LLM 模拟学生对反馈有谄媚，易被表面信号带偏】。

**设计落点**：LLM 单次判断定位 p≈0.8 的独立投票者 + 须附置信度 + 与客观信号交叉核对 + 升级类（MASTERED/POSITIVE）写入要求行为佐证。

### 1.3 档位 → 权重映射的成熟先例（唯一有公开公式的主流系统）
- **FSRS（Anki 默认，开源可审计）**：4 档 Again/Hard/Good/Easy；成功稳定性增长 Hard×0.29 / Good×1.0 / Easy×2.61（w15/w16 默认）【P，awesome-fsrs wiki】。
- **SM-2（SuperMemo）**：q<3 直接重置间隔、q<4 当天重练【P】。
- 支持"低自信但正确=记忆弱"：Benjamin/Bjork/Schwartz 1998 流畅度误导【V】；FSRS 中 correct-but-hard 增益仅 Good 的 0.14-0.29 倍。
- 支持"自报不得拿高稳定性奖励"：Deslauriers 2019 + Falchikov & Boud。

**设计落点（具体映射，以 FSRS Good=1.0 为基准）**：
| 档位 | 语义 | 类比 FSRS | 证据权重档 |
|---|---|---|---|
| STRUGGLING | 卡住/失败 | Again | NEGATIVE 标准档（0.35，对齐现有 NEGATIVE_WEIGHT） |
| UNCERTAIN | 低自信/勉强 | Hard | 低正向档（0.10-0.15） |
| CONFIDENT | 自述较懂（对话） | Good | 中正向档但打折（0.15-0.18） |
| MASTERED | 自述掌握 + 行为佐证 | Easy | 高正向档（0.18 上限），**无行为佐证则按 CONFIDENT** |

## 2. 注意力/走神实证（决定降权曲线与拒写线）

### 2.1 走神对学习的量化
- **走神率基线高达 30-40%**【V，Szpunar, Khan & Schacter 2013 PNAS 110(16):6313：视频讲课未干预组走神 39-41%，插入测试组 19%，成绩 59-70%→84-89%】→ 走神是常态，**首次/少量切出不重罚**。
- **走神与理解：元分析合并 r = −0.21**【V，Bonifacci et al. 2022 PBR 30(1):40-59，25 项研究 73 相关】→ 全局弱负相关；**"~18% 方差"出处未能核实，弃用**。
- 难文本走神更频、损害更大【P，Feng/D'Mello 2013 PBR 20(3):586 "Mind wandering while reading easy and difficult texts"】。
- **媒体多任务：测验 −11%、目击同桌多任务 −17%**【V，Sana et al. 2013 Comp&Educ 62:24】。
- **手机在场降低可用认知容量**：注意力测验低 ~9-10 分，η²=0.134-0.160【V，Skowronek et al. 2023 Sci Rep 13:9363】；Stothart 2015【V】：仅收到一条通知（不查看）损害与主动使用手机相当。

### 2.2 中断/恢复成本（切出 App 的直接证据）
- **平均 2.8s 中断使顺序错误率×2，4.4s ×3**【V，Altmann, Trafton & Hambrick 2014 JEP:Gen 143(1):215】→ 高频短切出主要产生"执行噪声/重新聚焦成本"（损害正在进行的作答），对"已发生学习的证据效力"影响中等。
- **编码期分心 vs 检索期分心不对称**【V，Craik et al. 1996 JEP:Gen 125(2):159：编码期 DA 记忆锐减、检索期 DA 基本无损】→ 长离开属于编码期资源被夺走，对"该段学习的掌握证据"打击大。
- **修正**：Kilb & Naveh-Benjamin (2007)【V】——"分心选择性破坏关联记忆"不成立（年轻人中无此差异）；不要以"关联记忆"作加重理由。
- 恢复成本随离开时长增长【P，Trafton 2003 IJHCS 58:583（常误引 2005）】。

### 2.3 注意力降权/拒写的设计落点【I 工程推断，须自校准】
| 信号档 | factor 建议 | 实证锚 |
|---|---|---|
| 1-2 次切出且单次 <30s | ≥0.85 小降权 | 走神基线 30-40%；微中断是执行噪声非编码破坏 |
| 累计离开 2-3min / ≥3 次切出 | 0.5-0.7 中降权 | Sana −11%/−17%；Skowronek η²≈0.13；Stothart 通知级 |
| 单次离开 >3-5min / 离开占会话 >1/3 / ≥5 次切出 | ≤0.3 或拒写 | 编码期 DA 大幅损毁（Craik 1996）；多任务学习≈半效 |
| 现有 FLOOR=0.6 | **建议降至 ~0.4** 并加拒写档 | 重度分心下增益可近半损，0.6 偏宽容 |

**校准路径**【I】：按 attentionFactor 分桶收集延迟复测增益，拟合 factor→真实增益剂量-反应曲线；拒写线设在"增益不再高于零知识对照"的经验点。上线前无实证取值，全部标【I】并做成常量待校准。

## 3. 门控与拒写补救的实证

### 3.1 升级门槛惯例（头部产品）
- **Khan Academy mastery【V，官方帮助中心经 Wayback 核实】**：not practiced→familiar→proficient→mastered；Mastery Challenge 每次 6 题覆盖 3 技能（每技能 2 题），**2 全对→升级 / 全错→降级 / 1对1错→维持；12 小时冷却**。→ 升级靠"多次分散客观作答 + 冷却"，不是单次高置信。**"5-6 次连续答对"无公开依据【UNVERIFIED】。**
- BKT p(L)≥0.95 阈值被广泛引用但数值不可得【P】。
- Duolingo HLR【V，Settles & Meeder 2016 ACL】：状态由练习历史回归，无单次置信门槛。

### 3.2 交叉核对：口头懂 vs 行为
- **行为证据优先于自我报告**（Koriat & Bjork 2005【V】、Nelson & Dunlosky delayed-JOL【V】）。
- 提示下答对 ≠ 掌握（illusion of competence 的成因）【V】。
- 冲突时**行为证据胜出**，口头声明降级为观察记录。

### 3.3 拒写/降级：不删除
- **Condorcet jury theorem**：p>0.5 的证据源保留累积仍有正价值；p<0.5 才需反转/剔除【V，Wikipedia 级】。→ 被门控拒的证据若 p 未知，保留到观察通道优于丢弃。
- **Open Learner Model / SMILI**【P】：低可信证据保留并显式标注是既有范式。
- gaming 检测系列是"识别→区别处理"而非删数据【P，Baker 2006】。
- **设计**：被拒 ≠ 删除——写独立观察通道（带拒因+置信度），**不进掌握度投影**；仅确认系统性操纵才丢弃。

### 3.4 多次弱证据 vs 单次强证据
- Condorcet【V】+ Spearman-Brown【V】+ 分散练习元分析（Cepeda 2006, 839 项）【V】+ 重复检索（Karpicke & Roediger 2008）【V】→ 理论实证都偏向多次独立弱证据。
- **独立性是硬前提**：同对话/同题变式/提示后作答不独立，不计入"多次"门槛。

---

## 4. 对门控链参数的具体落点（全部标注等级）

| 门/参数 | 取值 | 等级 | 依据 |
|---|---|---|---|
| θ_route（意图路由置信） | 0.45（沿用） | 既有 | spec §9.4 已定 |
| θ_evidence（证据资格置信） | 0.7 | spec §9.4 已定【I】 | 与 θ_route 拉开；模型判断 p≈0.8 的天花板下，0.7 是"可接受单票"下沿 |
| 冷却窗（同会话同 KC 重复写） | 12h | 【I】对齐 Khan | Khan Mastery Challenge 12h 冷却 |
| 每会话 T6 配额上限 | 8 条 | 【I】 | 对齐"多次分散证据"；单会话不触发升级 |
| 理解程度→权重档 | STRUGGLING: 0.35(负) / UNCERTAIN: 0.10 / CONFIDENT: 0.15 / MASTERED: 0.18(需行为佐证) | 【I】对齐 FSRS | FSRS w15/w16 + 自报打折（Deslauriers/Rozenblit/Koriat） |
| 注意力小降权线 | factor ≥0.85 | 【I】 | 走神基线 30-40%，首切不重罚 |
| 注意力中降权线 | 0.5-0.7 | 【I】 | Sana/Skowronek/Stothart |
| 注意力拒写线 | factor ≤0.4（或单次离开>3-5min） | 【I】 | Craik 编码期 DA；现 FLOOR 0.6 偏宽容，建议降 0.4 |
| 交叉核对 | MASTERED/POSITIVE 需同会话客观作答正确佐证，否则降级/拒 | 【I】依 Koriat&Bjork/Nelson&Dunlosky | 口头懂不可信 |
| 被拒证据 | 写观察通道（rejected 标记），不进投影 | 【I】依 Condorcet/OLM | 不删除可补救 |

---

## 5. UNVERIFIED / 待办
- Dunlosky & Rawson "86% 过自信"：全文封闭，**勿引用**（改引 Deslauriers/Rozenblit 已核实的定性结论）。
- "走神解释 ~18% 理解方差"：出处未核实，**弃用**（元分析合并 r=−0.21 为准）。
- Khan "5-6 次升级"：无公开依据，弃用（用 2 题定级 + 12h 冷却）。
- Hoge & Coladarci r=.66、Falchikov & Boud 数值、Craik/Ward 效应量、Killingsworth 46.9%：均未核实，不引用。
- 全部【I】工程参数（冷却 12h、配额 8、注意力三线、权重档）**上线前须按 §2.3/§4 校准路径用自有数据 A/B 校准**，本文件不改任何权威映射常量，只提供科学落点。

---

## 6. 来源清单（已核实为主）
Südkamp et al. 2012 (10.1037/a0027627)；Deslauriers et al. 2019 (10.1073/pnas.1821936116)；Rozenblit & Keil 2002 (PMC3062901)；Koriat & Bjork 2005 (10.1037/0278-7393.31.2.187)；Nelson & Dunlosky 1991 (10.1111/j.1467-9280.1991.tb00147.x)；Miller & Geraci 2011 (10.1037/a0021802)；Zheng et al. 2023 (arXiv:2306.05685)；Liu et al. 2024 (arXiv:2408.11728)；Do et al. 2026 (arXiv:2605.12748)；Szpunar et al. 2013 (PMC3631699)；Bonifacci et al. 2022 (PBR 30(1));Sana et al. 2013 (10.1016/j.compedu.2013.01.010)；Skowronek et al. 2023 (10.1038/s41598-023-36263-4)；Stothart et al. 2015 (10.1037/xhp0000092)；Altmann, Trafton & Hambrick 2014 (10.1037/a0034336)；Craik et al. 1996 (10.1037/0096-3445.125.2.159)；Kilb & Naveh-Benjamin 2007 (10.3758/BF03193567)；Khan Academy mastery help center (Wayback)；Settles & Meeder 2016 (10.18653/v1/P16-1174)；Cepeda et al. 2006 (10.1037/0033-2909.132.3.354)；Karpicke & Roediger 2008 (10.1126/science.1152408)；Baker et al. 2006 (10.1007/11774303_39)；Bull & Kay (10.1007/978-3-642-14363-2_15)；FSRS wiki / SM-2。
