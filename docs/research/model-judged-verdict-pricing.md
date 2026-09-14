# 深度研究简报：模型判分的复习结论值多少——题目级 FSRS 档与 KC 级权重的实证依据

**Depth**: deep
**Date**: 2026-09-13
**地位**: 第 2 条「复习作答与评级不由用户决定、由模型直接决定」的**定价依据底稿**。回答：当无工件错题（拍照存进来、没有机判 key）的复习结论由模型语义判断给出时，(a) 题目级 attempt 该落哪个 reason/档；(b) 知识掌握度该给多少权重；(c) 哪些条件下必须拒写或降级。

**前置**（本文件不重复其结论，只做增量）：
- `tutor-evidence-gate-research.md` —— 写侧门控参数底稿（模型判断≈p0.8 独立投票者、行为证据>自我报告、权重档 0.10/0.15/0.18、顶 0.35）。
- `llm-mastery-judgment-regulation.md` —— 判断侧规范（证据锚、checklist、采样一致性、防谄媚、§6.2 分层写入规则表）。
- `weighting-refinement-research.md` —— 自评通道的过自信与静态上限。
- `mastery-math-modeling.md` —— FSRS-6 参数与证据权重表（独立 1.0 / hint 0.6 / reveal 0.0 / 自评 0.35）。

**核实等级记法**（本文件专用，因本轮外部检索跨主会话与子代理）：
- 【V】主会话本次直读摘要/原文并逐字核对；
- 【V−】子代理本次直读、主会话抽检未覆盖该条（待复核）；
- 【P】仅元数据核验（DOI/arXiv 存在），未读摘要；
- 【I】工程推断（无直接实证，须用回流数据校准）；
- UNVERIFIED = 本次未定位到，不得当事实。

检索通道：arXiv abs 页（主会话直读 4 篇）+ 子代理并行（Europe PMC REST、arXiv、Crossref、Anki/SuperMemo 官方文档）。失败记录见 §5。

---

## 0. 执行摘要（设计结论先行）

1. **模型判分绝不得标记为独立回忆**（`INDEPENDENT_CORRECT/INDEPENDENT_INCORRECT` 禁用）。两条独立理由：
   - 证据侧：LLM 判分与人类判分 κ≈0.70，而论文称人类水平基准 0.75【V，Henkel 2024】——判分者误差与人类判分者同量级，共识本身不是 ground truth；手写数学卷判分被明确要求"人工复核"【V，Liu 2024】；且 LLM 的已知弱项恰是**定位推理错误**（"even in highly objective, unambiguous cases"）【V，Tyen 2024】——判"这一步错在哪"正是这个弱项。
   - 代码侧（工程事实）：`LearningEvidence.isIndependent` 只由 `INDEPENDENT_*` 派生（`core/model/.../LearningState.kt:60-62`），它同时驱动 (a) `independentCorrectObservations` → 可跳过/已掌握的判据、(b) `lastIndependentError` → 假掌握（CONFLICTED）判定、(c) `ProblemMemoryOutcome.INDEPENDENT_RECALL` → HLR 特征。让 κ≈0.70 的语义判断进这三条链，等于把判分误差写进"是否可跳过"与"是否假掌握"。
2. **题目级定价：按"协助后检索"落档——判对 HARD、判错 AGAIN，权重 0.50【I】（区间 0.5–0.6，上限 0.6）。** 依据：FSRS 四键的语义定义就是"**无协助自评回忆**"的努力程度（Anki 手册逐字：Again=想不起来；Hard=答对但有疑虑/很久；Good=答对但费力；Easy=毫不费力）【V】——**标尺里没有"协助后答对"的席位**；本仓库已有同向先例：`CORRECT_AFTER_HINT/CORRECT_ON_RETRY` **无条件 HARD**（`FsrsScheduleMath.kt:232-234`），且 `INDEPENDENT_CORRECT` 只要权重 <0.85（被注意力/猜疑折价过）也降 HARD（同文件 `LOW_CONFIDENCE_CORRECT_CEILING`）。模型判分比"有客观 key 的独立答对"更脏，至少应与提示后答对同价。
3. **KC 级定价：正向 0.10 起步、上限 0.15（硬顶 0.20），负向 0.25 起步、上限 0.35，任何情况 ≤ `MAX_EVIDENCE_WEIGHT` 0.35。** 与既有 `MasteryWriteGate` 常量对齐（`WEIGHT_UNCERTAIN_POSITIVE=0.10`、`WEIGHT_CONFIDENT_POSITIVE=0.15`、`MAX_EVIDENCE_WEIGHT=0.35`）【V，代码】：模型判"这题做对了"的信息量不超过模型判学生 CONFIDENT，而后者已被封在 0.15。
4. **它的价值在"定位错在哪"，不在"给更强的调度信号"。** 对话判分能看到推理过程（二元对错给不了），但"能看见"≠"判得准"：解释质量高 ≠ 持久学习好（performance≠learning，见前置文档），且定位错误是 LLM 弱项【V】。→ 用于教学与错误诊断，不用于给稳定性/掌握度加更大的步。
5. **模型提问本身就是协助**：问什么、问到多细由模型决定，可拆步把一道检索拆成若干小检索，协助强度**不低于**人类导师提示【I】。若学生先独立写出完整过程、模型只判分不提问，则协助成分低、但判分者误差仍在 → 结论是"按模型判分折价"，而非"按 hint 折价"；两者题目级都落 HARD，但 reason 应可区分（§4）。
6. **必须与 FSRS 参数拟合隔离**：Anki 官方口径是四键即 outcome signal、参数由复习历史拟合，并警告"把实际遗忘按 Hard 记会让所有间隔不合理地变长"【V−】。→ 模型判分的 review_log 行必须用独立 `source_kind`（如 `MODEL_JUDGED`）落库，**校准达标前不参与 FSRS 参数优化**；本仓库已有现成配对逻辑 `SchedulingEvaluationHarness.calibrateSources`（`core/domain/.../SchedulingEvaluation.kt:176-217`）【V】。

---

## 1. LLM 判分短答/自由作答/手写步骤的可靠性（决定"能不能算独立"）

| 来源 | 结论 | 等级 |
|---|---|---|
| Henkel et al. 2024, arXiv:2405.02985（K-12 真实学生短答，Science/History，5–16 岁） | GPT-4 + 基础 few-shot：**Kappa 0.70**，论文称"very close to human-level performance (**0.75**)" | 【V】主会话直读 abs |
| Liu et al. 2024, arXiv:2408.11728（大学数学手写半开放作答） | "surprisingly reliable and cost-effective **initial** grading, **subject to subsequent human verification**"；待改环节点名"enhancing the **extraction of handwritten responses**" | 【V】主会话直读 abs |
| BMC Med Educ 2024, DOI 10.1186/s12909-024-06026-5（2,288 份答案、12 门课、3 语言） | GPT-4/Gemini 与人类"moderate agreement"；**GPT-4 显著低于人类评分者（偏严）**、假阳性少；模型预先知道人类分数会产生偏差 | 【V−】（子代理读摘要） |
| Saito et al. 2023, arXiv:2310.10076 | **verbosity bias**：GPT-4 比人类更偏好更长的答案 | 【V】主会话直读 abs |
| Tyen et al. 2024, arXiv:2311.08516 | LLM **定位逻辑错误能力弱**（"generally struggle … even in highly objective, unambiguous cases"）；告知错误位置后纠错显著改善；小分类器找错可优于大模型 prompting | 【V】主会话直读 abs |

**读法（【I】）**：κ≈0.70 的判分者，其"判对"里混着真实回忆 + 蒙对 + 判分者误判。FSRS 的稳定性增益是按**干净独立检索**校准的（Hard×0.29 / Good×1.0 / Easy×2.61，见前置）；把模型判对当独立答对，等于给系统性假阳性发稳定性奖励——与"自评不得拿 Easy"同构，只是换成"模型不得拿 Independent"。偏置方向依模型而异（既有偏严也有 verbosity 偏宽），**只能统一折价，不能假设固定偏宽/偏严**。

**对本仓库场景的加重项**：学生的错题是**拍照的手写原稿**，"识别→判分"两段误差叠加；Liu 2024 恰好把"手写提取"列为失败源【V】。→ 依赖 OCR 抽取才能判的结论，应拒写或标"未判定"（§4 拒写条件 2）。

---

## 2. 自动判分产品的题目级调度先例（是否有"判分不确定度折价"的成熟做法）

- **SM-2 原始说明**：0–5 质量分**由学习者自评**（"the student has to assess the quality of his response"）；全文无自动判分机制【V−】。→ 间隔重复的评分标尺从诞生起就是"学习者对无协助回忆的自评"。
- **Anki 手册（四键语义）**：Again/Hard/Good/Easy 全部按**回忆时的努力程度**定义，逐字见 §0.2【V 主会话直读】。官方口径"约 10 秒想不出就看答案"、"部分正确如果在现实中算失败就按失败记"【V】。→ 自动判分若要投影到这个标尺，**必须自带折价约定**，标尺本身不提供席位。
- **Anki FSRS 文档**：参数由复习历史拟合、四键是 outcome signal；警告 Hard 误用会系统性拉长间隔；换按键习惯需排除旧记录【V−】。→ 直接支持"独立 source_kind + 校准前不进优化器"。
- **Duolingo HLR**：状态由练习历史回归，标签是题目层面二值正确性，无单次判分置信门槛【P】。
- **Khan Academy 掌握**：多次客观作答 + 冷却（仓库既有核实），不是给单次判定更高权重。
- **未找到**：Memrise / Quizlet / Anki+LLM 插件 / SuperMemo 19 auto-grade 中任何"自动判分 → 评分档/间隔"的公开映射文档。**UNVERIFIED —— 检索通道多处失败，不能断言不存在；本仓库若需要，只能自建并用回流数据标定【I】。**

---

## 3. "模型先问再判"＝协助（决定单次成功该怎么计价）

- **无协助检索才算独立成功**：Anki/SM-2 的标尺都只描述"无协助回忆的努力程度"，"提示后答对"在两大主流 SRS 里**没有独立成功的位置**【V/V−，§2】。
- **线索支持越弱，后续保持越好**：Carpenter & DeLosh 2006, Memory & Cognition, DOI 10.3758/BF03193405【P】。→ 探针提供线索，当次成功对"无线索延迟检索"的预测力下降。
- **先测（探针）本身有教学价值**：Yang, Potts & Shanks 2018, npj Science of Learning, DOI 10.1038/s41539-018-0024-y（forward testing effect 综述）【P】；Bisra et al. 2018 self-explanation 元分析, DOI 10.1007/s10648-018-9434-x【P】。→ **探针要保留**（它促进后续编码），但它不把"这次答对"变成更强的记忆证据。
- **本仓库既有落点**：提示下答对属 ASSISTED，`CORRECT_AFTER_HINT` 权重 0.6、调度档 HARD；"提示下答对≠掌握"（`MasteryEvidencePolicy` + `FsrsScheduleMath`）【V，代码】。
- **【I】新增判断**：LLM 的探针不是静态线索——问什么、问到多细由模型决定，可拆步降低检索难度，故其协助强度**不低于**人类导师提示，不能按"只是问了一句"折成轻协助。

---

## 4. 对本题的具体落点

### (i) 题目级 attempt（reason → FsrsRating / weight）

| 情形 | 允许 INDEPENDENT_* ？ | 调度档 | 建议 weight【I】 | 依据 |
|---|---|---|---|---|
| 有客观 key/选项（现状机判通道） | 是（唯一允许） | 现状不变 | 1.0（或按既有折价） | 机器判分就不是"模型判断"通道；模型判定通道**仅**用于无客观 key 的题 |
| 模型先追问/探针，学生答对 | **否** | **HARD**（绝不 GOOD/EASY） | 0.50（0.5–0.6，上限=既有 assisted 0.6） | §3 全部；`CORRECT_AFTER_HINT` 已无条件 HARD |
| 无探针、学生自由作答、模型判对 | **否**（新增非独立 reason，如 `MODEL_JUDGED_CORRECT`） | **HARD** 起步；仅当校准达标才考虑 GOOD | 0.60（0.6–0.7） | §1；κ≈0.70 + 手写抽取误差 ⇒ 不得等同权重 ≥0.85 的干净答对 |
| 模型判错（有/无探针） | **否**（`INCORRECT_AFTER_HINT` 或专用非独立 reason） | **AGAIN** | 0.50–0.60 | 判分者也会误判，负向不宜用 0.9/1.0 |
| 探针泄露答案 / 学生复述模型给的内容 | **否** | 不给正 | 0（按 `ANSWER_REVEALED` 语义） | 既有规则：reveal 权重 0 |
| 同题同会话多轮追问后的成功 | **否**（同一次 attempt，不累计） | 只写一次 | — | 独立性是"多次"门槛的硬前提（前置 §3.4） |

**实现约束**：`schedulingRatingFor` / `reportedRatingFor` 目前对未列出的 reason 走 `else -> reportedRatingFor(...)`。新增 reason 时应让它**编译期强制显式定价**（不要继续落到 else 默认档），否则新通道会静默拿到默认档。

### (ii) KC 级掌握度

| 情形 | 通道 | 权重上限【I】 |
|---|---|---|
| 模型判对（探针后或自由作答） | 模型判断通道（非独立），对齐 gate 正向小步档 | **0.15**（起步 0.10；硬顶 0.20；任何情况 ≤0.35） |
| 模型判对 + 逐字证据锚 + 采样一致 3/3 | 同上 | 0.20 |
| 模型判错 | 负向非独立通道 | **0.25** 起步，上限 0.35 |
| 客观核对（key 命中） | 既有客观通道 | 现状（1.0） |
| 写入对象限制 | **绝不**进 `independentCorrectObservations` / `lastIndependentError` / `independentCorrectCount` | — |
| 频率 | 同题每日 ≤1 条；同 KC 沿用既有冷却（12h） | — |

### (iii) 必须拒写 / 降级

1. **无逐字证据锚**（rationale 引用不到学生原话/可观察步骤）→ 拒写。
2. **结论依赖手写识别/OCR 抽取** → 拒写或标"未判定"【V，Liu 2024 点名抽取是失败源】。
3. **探针含解题步骤/答案** → 至多 hint 档，且**不得写 KC 正向**。
4. **采样不一致**（换措辞判定漂移）→ 降 UNCERTAIN 档（0.10）或拒写（一致性优先于自报置信）。
5. **判分理由出现"更完整/更长/更流畅"类依据** → 拒写【V，verbosity bias】。
6. **步骤级判定存在未核对步骤** → 不得给正向【V，Tyen：定位错误是弱项】。
7. **该题其实可机器判分** → 禁用语义通道，强制走客观通道（不得把可客观判的题降级为不确定判分）。
8. **混入 FSRS 参数拟合** → 独立 `review_log.source_kind`（如 `MODEL_JUDGED`）；校准达标前不参与 `FsrsParameterOptimizer`。

---

## 4a. 证据锚适用范围（2026-09-13 落地补充）

本文 §4(iii)1「无逐字证据锚 → 拒写」已推广到**所有模型交互**，不只复习结算：

- **学生会话文本必须落库**：讲题会话里学生打的字此前只存在模型任务快照里，而写侧门控核对
  引文时读的是 `tutor_message` 的 STUDENT 行——纯文字（开放式）作答时本地语料恒为空，
  提示词承诺的"引文会被本地逐条比对"形同虚设，MASTERED 也机械不可达。现在两个会打字的
  讲题界面（captured 会话、错题讲题）都在派发前落库（`TutorRespondCommands.recordStudentTurnIfNeeded`）。
- **正向底线**：任何 POSITIVE 判断都至少要有 **1 条已核实引文锚**（引文真出现在会话文本里；
  MASTERED 仍是 ≥2）。此前只有 MASTERED 校验锚，且 CONFIDENT 档拿到的是"引号数"而非
  "已核实数"——编造的引文照样本进库。两处都已修（`MasteryWriteGate.REQUIRED_EVIDENCE_ANCHORS_FOR_POSITIVE`
  + runner 对正向一律喂已核实计数）。
- **代价（有意）**：模型不引用 → 该次正向不写（fail-closed），KC 累积会变慢。提示词本就要求
  "逐字引用 ≥2 条"，这一步是把已有要求变成机器可执行。
- **开放式检查题**：允许模型用一句话提问、学生自己组织语言回答（`tutorRespondPrompt` 规则 3a）；
  不得写成选择题卡片。作答的对错仍由模型语义判断，本地只保证"引用可核"——这正是本文件
  §2 定价为**非独立**的原因。

## 5. UNVERIFIED / 待办

- **"自动判分成功 → 更高档位/更长间隔"的公开映射：本次未找到**（渠道多失败，不能断言不存在）。
- Henkel κ=0.70/0.75：已核 abs 原文措辞；**0.75 论文称"human-level performance"，未明确写为 human-human 基线**——引用时按论文措辞，不要升级成"人类上限"。
- BMC 的"GPT-4 偏严"：单一来源、特定模型版本与医学场景，**不可外推为固定偏置**（故建议双向统一折价）。
- verbosity bias：单一来源（abs），未见独立复现。
- Liu 2024 的具体一致率数字：未获取（PDF 不可抓取）。
- Carpenter & DeLosh 2006 / Yang 2018 / Bisra 2018 / Adesope 2017 / Settles & Meeder 2016：仅元数据级【P】，**摘要与效应量未读，勿引用具体数值**。
- 未找到：自解释质量 ↔ 后续表现的直接相关系数实证；LLM 判分一致性元分析。
- **校准 runbook（现行）**：
  1. **看哪里**：设置页「我的 → 模型能力」→「来源校准」（`SourceCalibrationSection`）。每行一个来源：正向报告次数、配到真实作答的对数、之后回忆率、真实作答基线，以及一行处置建议。`MODEL_JUDGED`（讲题判定）那一档还带一条说明：校准达标前不参与 FSRS 参数拟合。
  2. **门槛**（`SourceCalibration` 常量，改门槛要连研究文档一起改）：配对数 `MIN_PAIRED_OUTCOMES = 30`；低于基线 `DOWNGRADE_MARGIN = 0.15` 才建议下调。
  3. **两种结论各自的动作**：①与基线相当（且样本达标）→ 把 `MODEL_JUDGED` 从 `fittableReviewSamples` 的排除里放出，并在 `SchedulingEvaluation` 的注释与本节记录放行日期与依据样本数；②低于基线 ≥0.15 → **只降权重，不放出拟合**（改 `TutorJudgedReviewSettler.MODEL_JUDGED_EVIDENCE_WEIGHT` 与/或 `MODEL_JUDGED_*` 的映射档），并记下依据。
  4. **绝不做**：不因“样本数够了”就自动放行；不把 `MODEL_JUDGED` 混进 `ATTEMPT` 来源（Anki 官方口径：混用评分会把历史间隔标尺整体拉偏）——这条由 `fittableReviewSamples` 的单一实现兜住，改它之前先读本文件 §4(iii)8。
- 检索失败记录：arXiv API / Semantic Scholar 429；arXiv 搜索页超时；DuckDuckGo/Bing/Mojeek 反爬；Springer/Nature 跳 IdP；WebFetch 不支持 PDF；browser-use daemon 启动失败。

---

## 6. 来源清单

**【V】主会话本次直读**：arXiv:2405.02985（Henkel 2024，κ 0.70 vs 0.75）、arXiv:2408.11728（Liu 2024，手写数学判分需人工复核、抽取是失败源）、arXiv:2310.10076（Saito 2023，verbosity bias）、arXiv:2311.08516（Tyen 2024，定位错误弱）、docs.ankiweb.net/studying.html（四键逐字语义、10 秒规则、部分正确从严）。

**【V−】子代理直读、主会话待复核**：DOI 10.1186/s12909-024-06026-5（BMC Med Educ 2024）、docs.ankiweb.net/deck-options.html#fsrs（参数拟合与 Hard 误用警告）、super-memory.com/english/ol/sm2.htm（SM-2 自评标尺）。

**【P】仅元数据**：DOI 10.3758/BF03193405（Carpenter & DeLosh 2006）、DOI 10.1038/s41539-018-0024-y（Yang 2018）、DOI 10.1007/s10648-018-9434-x（Bisra 2018）、DOI 10.3102/0034654316689306（Adesope 2017）、DOI 10.18653/v1/P16-1174（Settles & Meeder 2016）。

**【仓库内证据】**：`docs/research/tutor-evidence-gate-research.md`、`llm-mastery-judgment-regulation.md`、`weighting-refinement-research.md`、`mastery-math-modeling.md`。

**【代码事实（主会话 grep 核对）】**：`core/model/.../LearningState.kt:60-62`（isIndependent 只由 INDEPENDENT_* 派生）、`core/domain/.../FsrsScheduleMath.kt:200-234`（`LOW_CONFIDENCE_CORRECT_CEILING=0.85`、hint 档无条件 HARD）、`core/domain/.../MasteryWriteGate.kt:157-171`（0.10/0.15/0.35）、`core/domain/.../SchedulingEvaluation.kt:176-217`（calibrateSources）、`core/data/.../study/ReviewLogSink.kt:283-285`（现有 source_kind 取值）。
