# 扫描页"版面结构"与"二次校验"的工程做法调研

> 课题：用模型读图转写中文扫描教辅（4 本《53 知识清单》共 1205 页，含分栏、思维导图、方程式荟萃、表格）时，
> **既不漏块、也不编造**。本文只回答"业界怎么做的 + 出处"，并在每节末尾给出"我们能不能用"。
>
> 日期：2026-09-22。状态：调研（未落地）。

## 0. 检索方法说明与证据等级

- **检索通道限制**：本次 `WebSearch` 通道不可用（返回 `Provider API kind openai-compatible does not provide provider-native WebSearch`），
  因此全部来源均由 `WebFetch` 按具体 URL 抓取原文/摘要页，或经 `gh api` 拉取仓库源文件。**没有做跨引擎交叉检索**，
  "多来源一致"这一级证据在本轮基本拿不到，故下文对每条来源都标注了单源/多源。
- **证据等级约定**（本文内标注）：
  - `[规范]` 官方标准/官方文档原文（可逐字引用）
  - `[源码]` 开源项目当前源码文件（可逐字引用，代表工程实践）
  - `[论文]` 论文摘要页或全文页（已抓取到的部分才算）
  - `[单源]` 仅此一处来源，未见独立复核 → 按"待验证"对待
  - `[推断]` 我的工程判断，**不是**文献结论
  - `[未取到]` 本轮没抓到出处（不等于不存在）
- 本轮**没有**实测任何转写方案（无 pilot、无对照实验）。本文所有"有效性"表述均来自文献/工程实践引用，
  凡是我们自己的结论一律标 `[推断]`。

---

## ① 版面区块化：一页怎么拆成"区块"

### 1.1 受控词表（block type）的三套主流做法

**(a) DocLayNet 的 11 类**（文档版面分割的事实标准，80863 页人工标注）`[规范][单源]`

IBM 的 docling 版面模型卡给出了带 id 的完整类别表（原文照录）：

```
0: Caption        1: Footnote       2: Formula        3: List-item
4: Page-footer    5: Page-header    6: Picture        7: Section-header
8: Table          9: Text          10: Title
11: Document Index  12: Code  13: Checkbox-Selected
14: Checkbox-Unselected  15: Form  16: Key-Value Region
```

来源：<https://huggingface.co/ds4sd/docling-layout-heron>（0–10 即 DocLayNet 原生 11 类，11–16 为 docling 扩展）
佐证（同类别名、但 `figure` 取代 `picture`）：<https://github.com/deepdoctection/deepdoctection/blob/master/docs/tutorials/Analyzer_Doclaynet_With_YOLO.md>

DocLayNet 数据集卡另有两点对我们有用：**"a fraction of the pages are double- or triple-annotated"**，
用来估计 **annotation uncertainty** 与"可达上限"，即**标注本身承认不确定性、并用重复标注量化它**。
来源：<https://huggingface.co/datasets/ds4sd/DocLayNet/raw/main/README.md>、<https://arxiv.org/abs/2206.01062>（摘要页）

**(b) 更细的工程实现词表 —— docling `DocItemLabel`（30 个）** `[规范][单源]`

```
caption, chart, checkbox_selected, checkbox_unselected, code, document_index,
empty_value, field_heading, field_hint, field_item, field_key, field_region,
field_value, footnote, form, formula, grading_scale, handwritten_text,
key_value_region, list_item, marker, page_footer, page_header, paragraph,
picture, reference, section_header, table, text, title
```

来源：<https://docling-project.github.io/docling/reference/docling_document/>
要点：docling 的文档树把 `body`（正文树）与 `furniture`（页眉/页脚等）分开，
**阅读顺序由 body 树 + 子节点顺序表达**，每个 item 可带 bbox 与 provenance。`[规范]`

**(c) 古籍/手稿界的 SegmOnto 区域词表** `[规范][单源]`

Zones：`CustomZone, DamageZone, DigitizationArtefactZone, DropCapitalZone, GraphicZone, MainZone,
MarginTextZone, MusicZone, NumberingZone, QuireMarksZone, RunningTitleZone, SealZone, StampZone,
TableZone, TitlePageZone`
Lines：`CustomLine, DefaultLine, DropCapitalLine, HeadingLine, InterlinearLine, MusicLine`
来源：<https://segmonto.github.io/>

对我们最有价值的三个概念：**`MarginTextZone`（页边注）、`NumberingZone`（页码/题号，可安全丢弃）、
`DigitizationArtefactZone`（扫描产生的黑边/手指/阴影，不是内容）** —— 这三类恰好是"扫描教辅"里
"该丢的 / 该留的 / 容易被误当内容"的边界。

**(d) 分栏与阅读顺序的确定性旋钮 —— Tesseract PSM** `[规范]`

原文照录（`tesseract -h` 列表，3.21）：

```
  3    Fully automatic page segmentation, but no OSD. (Default)
  4    Assume a single column of text of variable sizes.
  5    Assume a single uniform block of vertically aligned text.
  6    Assume a single uniform block of text.
 11    Sparse text. Find as much text as possible in no particular order.
```

来源：<https://github.com/tesseract-ocr/tessdoc/blob/main/ImproveQuality.md>
用途：**分栏是"段落合并顺序"问题的根源**；`--psm 4/6/11` 给出可复现的对照口径（同一页跑多档 PSM，
差异大的区域就是"版面歧义区"，可作为人工复核的定位手段）`[推断]`。

### 1.2 模型自己读图时，"先出区块清单、再逐块填"的已有实现

**olmOCR（AllenAI）是目前最贴近我们要做的事的公开实现**：整页图 + 结构化前置字段 + 正文一体输出。`[源码]`

其 OpenAI JSON Schema（`olmocr/prompts/prompts.py`，逐字照录字段与描述）：

```json
{"primary_language": "...null if there is no text at all...",
 "is_rotation_valid": "Is this page oriented correctly for reading?
    Answer only considering the textual content, do not factor in the rotation of
    any charts, tables, drawings, or figures.",
 "rotation_correction": {"enum": [0, 90, 180, 270]},
 "is_table":  "Indicates if the majority of the page content is in tabular format.",
 "is_diagram":"Indicates if the majority of the page content is a visual diagram.",
 "natural_text": "The natural text content extracted from the page."}
required: [primary_language, is_rotation_valid, rotation_correction,
           is_table, is_diagram, natural_text]   "strict": true
```

主提示词同时含 **`Do not hallucinate.`** 与 "as if you were reading it naturally"。
来源：<https://github.com/allenai/olmocr/blob/main/olmocr/prompts/prompts.py>（经 `gh api` 取原文）

**读法**：这就是"页面级区块清单在前、正文在后"的工业版本 —— 但它只声明**页级**属性
（朝向/是否表格页/是否图页），**没有到"每块的条目数"**。我们的需求比它更细一层 `[推断]`。

olmOCR 的另一个机制 **document anchoring**（把 PDF 文本层抽出的原文作为"锚"拼进提示词）：
源码 `get_anchor_text(..., target_length=4000)`，引擎含 `pdftotext / pdfium / pypdf / topcoherency / pdfreport`；
`topcoherency` 会**在多种抽取结果里挑"连贯性"最高的一份**；`pdf_report` 走坐标感知的
`_linearize_pdf_report` + 按 `(row, col)` 排序（`selected_elements.sort(key=lambda x: (x[3][0], x[3][1]))`）。
来源：<https://github.com/allenai/olmocr/blob/main/olmocr/prompts/anchor.py> `[源码]`
**限制**：锚定依赖 PDF 文本层；纯扫描件（无文本层）拿不到，对我们**只适用于有 OCR 文本层的部分页** `[推断]`。

### 1.3 评测口径：哪些版面是真难点

olmOCR-Bench 的类别名（README 逐字照录）：`ArXiv`、`Old scans math`、`Tables`、
`Old scans Headers & footers`、`Multi column`、`Long tiny text`、`Base`、`Overall`。
来源：<https://github.com/allenai/olmocr> `[源码]`

**"Multi column"、"Tables"、"Old scans math"、"Long tiny text" 被单独列成类别**，说明这四类
是整页转写公认的失分点 —— 与我们四本书的结构（分栏 + 表格 + 公式荟萃 + 小字）完全对上。
Docling 的实现细节也印证："layout on **72 dpi** page images"、OCR 默认 **216 dpi**、
TableFormer 单表 CPU 上 2–6 秒、装配阶段"**infer reading-order**"、"matching figures with captions"。
来源：<https://arxiv.org/html/2408.09869v5>（Docling 技术报告全文页）`[论文]`

### 1.4 我们能不能用

**能用，且优先级最高的是"受控区块词表 + 每块 bbox + 阅读顺序"，用本地版面检测产出，而不是让模型自由描述。**

| 做法 | 来源 | 我们怎么用 | 风险/缺口 |
|---|---|---|---|
| DocLayNet 11 类 | docling 模型卡 | 直接作为我们的 `block.type` 受控词表（中文教辅需扩 3 类：`FormulaBlock` 已有 Formula、`KnowledgeMap`（思维导图）、`NoteBox`（栏目框/小贴士）） | 中文教辅的"栏目框/思维导图"不在 11 类里 `[推断]` |
| SegmOnto 的 `NumberingZone`/`DigitizationArtefactZone`/`MarginTextZone` | segmonto.github.io | 明确"页码/题号"与"扫描伪影"两个**可丢弃类**，把"页边小字"单列，避免被当正文 | 古籍语境，扫描件语义需映射 |
| 本地版面检测（RT-DETR/YOLO 系） | docling、deepdoctection | 先产出 `blocks.json`（id/type/bbox/order），再让模型"逐块填" | 项目当前**没有**版面检测依赖，需新增（成本待评估）`[未取到]` |
| olmOCR 式"页面级结构化前置字段" | olmOCR 源码 | 立即可用：先输出朝向/页型/是否表格页，再出正文 | 只到页级，不到块级 |

**项目内落地锚点**：现行转写暂存文件是 `{"page", "heading", "text"}` 的扁平结构
（见 `D:\smart mistake book\knowledge-production\2027-53-transcripts-staged\PHYSICS\2027高中知识清单物理\range_0029_0042.jsonl`），
图描述以内联 `【图：...】` 形式写在 text 里 —— **正是"无块结构、无计数、图描述靠推断"的形态**。
`docs/kb-content-conformance-spec.md:180` 已有 `sourceLocator: 《2027 5·3 A版 高考总复习 <科> 精讲册》P<页码>`，
建议扩展为 `P<页码>-B<块号>`，块级证据链即可复用现有规范而不是另起一套。`[推断]`

---

## ② "先计数再填写"：是不是已知有效的抗漏手段

### 2.1 直接证据：**没有找到**针对"先清点条目数再逐条填"的对照实验

必须如实说明：本轮检索通道受限，**我没有找到任何一篇论文/工程博客直接做"先报数再填"的 A/B 实验**
（"漏项率是否下降"）。这是本课题最大的证据缺口，标 `[未取到]`。

能找到的是**同族机制**（先规划/先分解/先声明结构，再执行），且都有正对照：

**(a) Plan-and-Solve：显式点名它修的就是"漏步骤"** `[论文]`

> "Zero-shot-CoT ... suffers from calculation errors, **missing-step errors**, and semantic
> misunderstanding errors."
> "**To address the missing-step errors, we propose Plan-and-Solve (PS) Prompting.**"
> 做法："devising a plan to divide the entire task into smaller subtasks" + "carrying out the subtasks according to the plan".

来源：<https://arxiv.org/abs/2305.04091>

**(b) Least-to-Most：先分解、再按序求解** `[论文]`

> "break down a complex problem into a series of simpler subproblems and then solve them in sequence"，
> 且 "Solving each subproblem is facilitated by the answers to previously solved subproblems."

来源：<https://arxiv.org/abs/2205.10625>
注意：这两篇都是**推理题**语境（SCAN 99% vs CoT 16%），**不是文档转写**。把它迁到"版面清单"上是
**我们的类比推断**，不是论文结论 `[推断]`。

**(c) olmOCR 的 strict schema 前置字段** `[源码]`
即在正文之前必须先给出 `is_table / is_diagram / is_rotation_valid / rotation_correction` 等**页级结构断言**，
再给 `natural_text`。这是"先声明结构、后填内容"的工业实例，但同样**不含条目计数**。
来源：<https://github.com/allenai/olmocr/blob/main/olmocr/prompts/prompts.py>

### 2.2 反向证据：**模型的"数数"本身不可信，所以计数必须落在模型之外或另加复核**

**(a) VLM 在计数类任务上很弱** `[论文]`

> "four state-of-the-art VLMs are only **58.07%** accurate on average"；Claude 3.5 Sonnet 最好，
> "**77.84%** accuracy"；人类预期 "**100%**"。计数相关探针含 "how many times two lines intersect"、
> "the number of circles in an Olympic-like logo"。

来源：<https://arxiv.org/abs/2407.06581>（BlindTest）

**(b) 长上下文中间位置会被忽略** `[论文]`

> "performance is often highest when relevant information occurs at the beginning or end of the input
> context"，"significantly degrades when models must access relevant information in the **middle** of
> long contexts"；结论"current language models do not robustly make use of information in long input contexts"。

来源：<https://arxiv.org/abs/2307.03172>（Lost in the Middle）

**(c) 列表类问题正是幻觉的高发口** `[论文]`
CoVe 论文的实验任务里专门包含 "list-based questions from Wikidata" —— 列表题上幻觉表现为
**多写/少写条目**，而 CoVe 在列表题上降低幻觉。来源：<https://arxiv.org/abs/2309.11495>

### 2.3 结论（分事实与推断两层）

- **事实**：`先规划再执行`能减少"漏步骤"，有正对照（PS/LtM）；`模型自报计数`不可靠（58.07%）；`长输入中段`会丢（U 形）。
- **推断**：**"先计数再填写"值得做，但计数不能由模型自报作为终点**。正确形态是
  **"计数来自确定性来源（版面检测得到的块数、原书目录/题号、页面上可见的编号）；模型被要求先声明这些数，再逐条填，
  填完必须回填实际条数并解释差异"**。差异本身就是最强的漏项信号 `[推断]`。

**我们能立刻用的一招**（不需要新依赖）：要求模型在填每一块前，先写
`expected_items`（依据：可见编号 / 人工给定），填完写 `actual_items`，
**两者不等必须落 `mismatch` 而不是自动补齐**。这与 CoVe 的"先立断言再核验"同形，且不依赖任何未验证的模型能力。

---

## ③ 二次校验协议：怎么不被第一遍带偏

### 3.1 核心机制：CoVe（Chain-of-Verification）—— 四步，且**核验阶段不给草稿看**

`[论文]` 四步（原文）：

1. "**Generate Baseline Response**" — 先出草稿
2. "**Plan Verifications**" — 依据 query+draft 生成核验问题
3. "**Execute Verifications**" — 逐题独立回答
4. "**Generate Final Verified Response**" — 用核验结果重写

关键在变体差异：

| 变体 | 核验时能否看到草稿 | 论文结论 |
|---|---|---|
| joint | 能（计划+作答同一提示） | 最弱 |
| **2-step** | **不能**："the context given to the LLM prompt only contains the questions, and not the original baseline response" | Wikidata 题最好 |
| **factored** | 每问单独一次调用，草稿与其他答案都不可见，消除跨题干扰 | Wiki-Category / MultiSpanQA 最好 |
| **factor+revise** | 独立作答后，再单开一次"草稿 vs 核验问答是否一致"的比对 | 长文传记最好（FactScore 71.4 vs factored 63.7 / joint 60.8 / few-shot 55.9） |

来源：<https://arxiv.org/abs/2309.11495>、全文：<https://arxiv.org/html/2309.11495v2>

> 论文明确归因："Factored variants generally beat joint because they **prevent attending to and repeating
> the draft**."

### 3.2 为什么"同一模型看自己的稿"会偏 —— self-preference bias `[论文]`

> 模型 "scores its own outputs higher than others' while human annotators consider them of equal quality"；
> "self-recognition can interfere with unbiased evaluations and AI safety more generally"；
> 自识别能力与自偏好强度呈线性相关。

来源：<https://arxiv.org/abs/2404.13076>
（该摘要页未给数值效应量，故只能作方向性证据。）

### 3.3 零资源一致性检测：SelfCheckGPT `[论文]`

> "sampling-based approach"、"zero-resource fashion"、"without an external database"；
> 机制：若模型真知道某事，"sampled responses are likely to be similar and contain consistent facts"；
> 幻觉处"stochastically sampled responses are likely to diverge and contradict one another"。

来源：<https://arxiv.org/abs/2303.08896>
（摘要页未列具体变体，BERTScore/NLI 等细节本轮**未取到**。）

### 3.4 数字化的先例：图书馆界的"双人录入 + 独立仲裁"

这是我要重点推荐的来源，因为它把"二次校验"讲成了可验收的工程指标。`[单源]`

Holley (2009), *D-Lib Magazine*，英国国家图书馆 19 世纪报纸大规模数字化项目：

> 非 OCR 路线可用"**double or triple keyed** to gain better accuracy than OCR software"，但简单版面才划算。
> 精度等级："**Good OCR accuracy = 98-99%**"、"**Average = 90-98%**"、"**Poor = below 90%**"。
> 关于抽样：用 **45 页**样本代表 1803–1954 全集；未校正时按字符置信度实测 71%–98.02%。
> **最关键的一句**：真实精度 "can only be determined by an **independent arbiter, a human**, via proofreading
> or re-keying and comparing."
> 以及一个警告：**"confidence is often mislabeled accuracy"**（置信度常被误当成准确率）。

来源：<http://www.dlib.org/dlib/march09/holley/03holley.html>

**读法**：业界成熟做法 = **两次独立产出 + 自动化比对 + 人做仲裁**；模型自报的"置信度"不能当准确率。

### 3.5 不给"瞎猜"留后路的先例：Nougat 的 `[MISSING_PAGE]` `[源码]`

> README 提到失败检测启发式会输出 `[MISSING_PAGE]`，并提供 `--no-skipping`；
> 输出为 `.mmd`（类 Mathpix Markdown，含 LaTeX 表）。

来源：<https://github.com/facebookresearch/nougat>
**价值**：它选的是"**弃权**"而不是"编一个像样的结果"。这正是我们需要的默认行为。
（README 中**没有** "Limitations" 章节，故"Nougat 会重复/幻觉"这一流传说法本轮**未取到**出处 `[未取到]`。）

### 3.6 我们能不能用

可以，且要**按 CoVe 的 factored 形态**落地，规则三条：

1. **校验遍不给草稿**（2-step/factored）：输入 =「原页图 + 受控块清单 + 受控词表」，**不含**第一遍正文。
2. **逐块独立调用**（factored）：一块一次，防止跨块串味与"接龙式复制"。
3. **人工只当仲裁、不当初审**：先让机器比对两次产出，只把 conflict 块交给人（Holley 的 "independent arbiter" 语义）。

做不到换模型时，**至少**要做到"不带草稿 + 逐块独立"；同模型自校验的偏置是已知的（3.2），
所以最终仍需人工抽检，并把"抽检结论是否与机器校验一致"本身当成指标。

---

## ④ 分块/平铺（tiling）读图：怎么切才不丢结构

### 4.1 高分辨率分块的两种主流解法，以及它们**公开承认的缺陷**

**(a) LLaVA-UHD：可变尺寸切片 + 低分辨率全局图 + 空间 schema** `[论文]`

- 切片数 N 由面积估计，然后把 N "**factorize the slice number N into m columns and n rows**"，
  选"切片长宽比最接近 ViT 预训练比例"的方案；允许在 **N−1 / N / N+1** 里选，以避开质数 N 的难看切法。
- 切片按原生比例编码，"**without padding or shape-distorting resizing**"。
- **另外送一张低分辨率整图（native aspect ratio）给全局上下文。**
- 空间 schema 用标点表达布局：行内切片用 "`,`" 分隔，换行用 "`\n`"。消融显示去掉它会掉点。
- 压缩模块（perceiver resampler）把每片 576 → 64 个视觉 token，N 片共 64×(N+1)。
- **最重要的承认**："**image slices are currently independently encoded, with interactions only in LLMs**"，
  论文**没有**描述任何重叠区或跨片重建机制，跨片信息只能靠 LLM + 全局缩略图来补；作者把
  "establish efficient connections between image slices" 列为未来工作。

来源：<https://arxiv.org/html/2403.11703v1>、摘要：<https://arxiv.org/abs/2403.11703>

**(b) Monkey：等尺寸分块 + 逐块适配器 + 由粗到细的多级描述** `[论文][单源]`

> 切块尺寸对齐视觉编码器原训练尺寸 —— "each matching the size (e.g., **448x448**) used in the original
> training of the well-trained vision encoder"；"Equipped with individual adapter for each patch, Monkey can
> handle higher resolutions up to **1344x896** pixels"；整图先用 "a multi-level description generation method"
> 生成全局描述，再补局部。

来源：<https://arxiv.org/abs/2311.06607>（摘要页；重叠/冗余问题该页**未述** `[未取到]`）

### 4.2 "整表不可切"的先例

- **PubTables-1M**：指出表格标注的 **oversegmentation** 问题，用 "canonicalization procedure" 规范化；
  并证明同一族 transformer 检测器可同时做 "detection, structure recognition, and functional analysis"
  而 "without the need for any special customization"。表规模 "nearly one million tables"。
  来源：<https://arxiv.org/abs/2110.00061> `[论文]`
- **Table Transformer (TATR)**：把结构识别拆成"先**检测出表格区域**（object detection，DETR R18 on PubTables-1M），
  再在裁剪出的表格图上做结构识别"，结构评测用 GriTS。
  来源：<https://github.com/microsoft/table-transformer> `[源码]`
  （该 README **未**说明"跨栏表格"或阅读顺序，这两点**未取到**。）

### 4.3 全页 vs 切片的工程折中

- olmOCR 用**整页 + 长边限制**（`--target_longest_image_dim`：Dimension on longest side to use for rendering
  the pdf pages），并用 `olmocr/prompts/anchor.py` 的坐标感知线性化处理顺序；README 变更记录里
  "v0.3.0 fixes auto-rotation detection" 与 "hallucinations on blank documents"。
  来源：<https://github.com/allenai/olmocr> `[源码]`
- 裁剪的边缘问题有官方口径：Tesseract 建议"**adding a white border to text which is too tightly cropped**
  may also help"，即切得过紧会掉字，需要留白边。来源：<https://github.com/tesseract-ocr/tessdoc/blob/main/ImproveQuality.md> `[规范]`

### 4.4 我们能不能用（含明确空白）

**事实层面**：本轮抓到的所有 VLM 分块方案（LLaVA-UHD / Monkey）**都没有解决"跨片内容"**，
LLaVA-UHD 明确说切片是独立编码、靠 LLM 与全图缩略图交互。**"固定网格 + 重叠区 + 回拼"在 VLM 语境下
本轮没有拿到权威出处**（OCR 引擎界的重叠惯例本轮亦`[未取到]`）。

**因此给出的是工程推断 `[推断]`，不是文献结论：**

1. **先版面、后切片**：切点**落在块边界**（和栏间/栏内块间空白）上，而不是固定网格。
   依据：TATR / PubTables-1M 已证明"表格可作为整体被检测"；SegmOnto/DocLayNet 都已定义区与 bbox；这样"跨块内容"
   在切之前就已被显式识别。
2. **不可切单元清单**（成对列进 manifest）：整表、思维导图整图、跨栏题干的题干+图注配对。
   这些以**整块**（必要时缩放到长边上限）单独送模型，**不参与网格切分**。
3. **给每片附全局缩略图**（LLaVA-UHD 的做法）：切片提示里同时给"整页缩略图 + 本片"，并用空间 schema
   声明"本片是整页的第 r 行第 c 列"，这样模型知道自己在整页的哪个位置。
4. **边缘留白**：切片边界加白边（Tesseract 的口径），避免切字。
5. **回拼顺序由结构决定，不由模型决定**：先有 `blocks.json`（含 order），回拼就是按 order 把各片产出插回，
   模型不负责排序。若某块被切到，则以该块为单元单独重跑，而不是拼接两半。

---

## ⑤ 不可辨内容的处理约定

### 5.1 TEI P5：目前最完整的"看不清/缺失/补字/破损"四分法 `[规范]`

**(a) 四个元素的确切定义**（TEI 源文件逐字照录）

| 元素 | 定义（原文） |
|---|---|
| `<unclear>` | "contains a word, phrase, or passage which **cannot be transcribed with certainty because it is illegible or inaudible** in the source." |
| `<gap>` | "indicates a point where material has been **omitted in a transcription** ... because the material is illegible, invisible, or inaudible." |
| `<supplied>` | "signifies text **supplied by the transcriber or editor** for any reason; for example because the original cannot be read due to physical damage, or because of an obvious omission by the author or scribe." |
| `<damage>` | "contains an area of damage to the text witness." |

来源（TEI 官方源）：`P5/Source/Specs/unclear.xml`、`gap.xml`、`supplied.xml`、`damage.xml`
（<https://github.com/TEIC/TEI>，经 `gh api` 取原文）

**(b) 受控的 reason 词表**（逐字照录）

- `<unclear @reason>`：`illegible`、`inaudible`、`faded`、`background_noise`、`eccentric_ductus`
  （后者定义为"unusual, awkward, or incompetent execution of a glyph"）
- `<gap @reason>`：`cancelled`、`deleted`、`editorial`、`illegible`、`inaudible`、`irrelevant`、`sampling`
- `<supplied @reason>`：自由词，示例给出 `overbinding`、`faded-ink`、`lost-folio`、`omitted-in-original`
- 另有 `<damage @degree>`（"not perfectly preserved, though it may be read with confidence"）与
  `@group`（把不连续的多个 damage 归为同一物理现象）

**(c) 组合使用的判定规则**（TEI "Use of the gap, del, damage, unclear, and supplied Elements in
Combination"，逐字要点）来源：`P5/Source/Guidelines/en/PH-PrimarySources.xml` §`PHCOMB`

1. 完全不可辨且**编辑不补** → 放空的 `<gap>`，用 `@reason` 说明原因；
2. 完全不可辨且**编辑要补** → 用 `<supplied>` 包住所补文本，`@reason` 说明原因；
3. 部分可辨、**能读但没有十足把握** → 照读，外包 `<unclear @reason>`，并用 **`@cert` 表示置信度**；
4. 有删除/破损但**至少部分可十足把握读出** → 用 `<del>` 或 `<damage>`，并用 `@degree` 表示"未必完整保存"；
5. **同一区域内**三种情况混杂 → 整区外包 `<del>`/`<damage>`，其中"无十足把握"的文本包 `<unclear>`，
   "完全不可辨且不补"的位置放 `<gap>`。

**(d) 置信度与证据属性** `[规范]`：`att.editLike` 提供 `@evidence`，取值 `internal` / `external` /
`conjecture`（原文："the intervention or interpretation has been made by the editor, cataloguer, or ..."）。
来源：`P5/Source/Specs/att.editLike.xml`
> `@cert` 的存在由上面的 PHCOMB 原文直接证实（"the `cert` attribute to indicate the confidence"）；
> 其**枚举取值本轮未取到**（`att.certifiable` 规格文件在 TEI 仓库内未定位到），标 `[未取到]`。

### 5.2 Wikisource（维基文库）校对惯例：社区实操版 `[规范][单源]`

- `{{Illegible}}` —— "intended for use when a word being transcribed is illegible"；
  在 `Page:` 命名空间**渲染为红色**作为校对提示，在 `Main:` 命名空间正常显示；
  支持 `|texttip=`/`|1=` 说明，`|nodash=` 加虚线下划线。
- 并列的其它模板：`{{?}}`（**可读但字形特殊**）、`{{Reconstruct}}`（**缺失/不可辨但可据上下文或他源推得**）、
  `{{Redact}}`（被涂改/遮盖）、`{{Inaudible}}`（音频无法辨听）。

来源：<https://en.wikisource.org/wiki/Template:Illegible>

**关键区分**（这两类必须分开标，否则模型会把"推断"混进"转写"）：
`Illegible` = 读不出，**没有**给出内容；`Reconstruct` = 读不出但**有依据地补**，且补的内容来源可追。

### 5.3 反"合理猜测填空"的三条硬约定

1. **弃权优先**：Nougat 用 `[MISSING_PAGE]` 明确表示"这页我不给结果"（见 3.5）`[源码]`。
2. **置信度 ≠ 准确率**：Holley 明确警告 "confidence is often mislabeled accuracy"，
   真值必须由人类独立仲裁（见 3.4）`[单源]`。
3. **补字必须可追溯到非图像来源**：TEI `<supplied @reason>`、维基 `{{Reconstruct}}` 都**要求写出补写的理由**；
   给不出理由的补写，要么降级为 `<gap>`，要么标 `[?]`。

### 5.4 我们能不能用

**能，而且这是五项里最容易立刻见效的一项。** 直接采纳 **`<gap>` / `<unclear @reason="illegible">` /
`<supplied @reason=...>` 的三分** + **`@cert` 置信度**，在 Markdown 产物里退化为文本标记：

```
[不可辨]                    ← gap：读不出，且我们不补
[不可辨:原因=污损|缺字]      ← gap 带原因
[存疑:汚损]读作"xxx"         ← unclear：读了但没把握（附原因与置信度）
[补:缺字]据上下文作"xxx"     ← supplied：有依据地补，必须写依据
[图-无文字] 见 P12-B07       ← 图块无文字：只给定位，不给描述
```

**红线（明确禁止）**：不许写"图中是一颗绿色小球放在橙色斜面上、受两个力"这类**由推断生成的图描述**。
现行转写里已经出现这种写法（见 ① 末尾引用的 `range_0029_0042.jsonl`：`【图：左侧为一球体（绿色小球，带中心点）…】`），
它是"编造内容"最集中的来源 `[推断]`。

---

## ⑥ 综合：一套可执行的转写协议草案

> 前置说明：§①③⑤ 的机制有明确出处；§②④ 的组合方式与阈值属**工程推断** `[推断]`，
> 必须在 1 册（建议先用页数最少的一册）上做 pilot 后再推广。

### 名词

- **块（block）**：`blocks.json` 中的一条，字段 `{block_id, type, bbox, order, kind: text|table|formula|figure|graphic_map, cross_column: bool}`。
- **受控词表**：`block.type` ∈ {Title, Section-header, Text, List-item, Table, Formula, Picture, Caption,
  Page-header, Page-footer, Footnote, KnowledgeMap, NoteBox}（前 11 项来自 DocLayNet，后 2 项为中文教辅扩展 `[推断]`）。
- **结构段 / 填写段**：同一次调用内的两段输出（见 P2）。

### P0 预处理与整册骨架（每册一次，人工可审）

1. **渲染**：PDF 逐页渲染为 PNG，长边固定（建议 2400–3000 px，需用角标/表格内小字做可读性验证）。
2. **朝向**：按 olmOCR 式先判 `is_rotation_valid` + `rotation_correction ∈ {0,90,180,270}` 并转正。
3. **版面**：本地版面检测产出每页 `blocks.json`（类型 + bbox + 阅读顺序）。
4. **骨架**：用项目已有 `kb_tools/textbook_toc.json`、`kb_tools/chapter_structure_full.json` 建"页 → 节"期望表。

**验收判据**
- 渲染：抽 10 页人工确认分栏、角标、公式小字可读；渲染参数写入 manifest。
- 朝向：全册"朝向异常页"计数为 0（逐页 flag 统计），另人工抽 5 页确认。
- 版面：每页 `blocks` 非空；`blocks` bbox 并集覆盖页面内容区 ≥ 95%（页边距空白允许未覆盖）；
  阅读顺序人工抽 20 页一致（双栏页必须至少抽 10 页）。
- 骨架：页 → 节映射覆盖 100% 页；"跨节页"必须显式标注，不允许留空。

### P1 逐页两段式转写（一页一次调用）

**结构段**（先出，不得夹正文）：`page_no, rotation_valid, page_type(单栏|双栏|表为主|图为主),
blocks[]（含 type / bbox_approx / cross_column / expected_items）/ illegible_regions[]（bbox + reason）`

**填写段**：按 `order` 逐块填，每块结束声明 `actual_items`；与 `expected_items` 不等时必须给出
`mismatch_reason` 或整块降级为 `[不可辨]`。

**硬约束**：结构段走 strict JSON Schema（枚举受控、`additionalProperties: false`）；
`type` 只能取受控词表值；图块若块内无文字，只能写 `[图-无文字] 见 P{页}-B{块}`。

**验收判据**
- 每页产物通过 schema 校验（0 例失败）；
- 页面内每条内容都挂在某个 `block_id` 上（不存在游离文本）；
- `expected_items` vs `actual_items` 的全部差异都有处置结论（补记原因或降级），差异条数记入日志；
- 图块：无文字图块的产出 100% 是定位标记，**0 例推断性描述**（此项为红线，抽检 20 个图块）。

### P2 不可切单元（表格 / 思维导图 / 跨栏配对）

- 表格先整表检测成 bbox，**整表作为单元**送模型；必须切时按行切且每片重复表头。
- 思维导图整图作为一个单元；跨栏题干+配图作为配对单元。

**验收判据**：含表格页 100% 有 `table` bbox；抽 10 张表，模型给出的行列数与人工一致；
跨栏单元在 `blocks.json` 中 `cross_column=true` 且不参与网格切分。

### P3 校验遍（独立、不带第一遍正文）

按 CoVe 的 **factored / 2-step** 形态：输入 =「原页图 + 受控块清单 + 受控词表」，**不含草稿**；
**逐块一次调用**；每块输出 `verdict ∈ {agree, mismatch, illegible}` 与依据（读写到的首尾词/关键数字）。

**验收判据**
- 校验遍输入中不含任何草稿文本（可机械检查 prompt 组装）；
- 每块恰有一条 verdict（校验遍块数与 `blocks.json` 块数一致，无孤立块、无缺块）—— 这是**漏块的独立兜底**；
- 逐块一次调用（非合并调用）可机械核对调用次数 = 块数。

### P4 冲突仲裁与人工抽检

- 机器比对两遍产出 → 只把 conflict 块交人工（人当仲裁，不当初审）；
- 每册随机抽 25 页做人工全量核对，统计两个硬指标：**漏块率**（图中存在于块清单而未被转写的内容）
  与**编造率**（转写中不存在于图的内容，含推断性图描述）。

**验收判据**
- 人工抽检的 verdict 与机器 verdict 的一致率 ≥ 阈值（阈值须在 pilot 中先定，不得事后调整）；
- **漏块率 = 0 且编造率 = 0** 作为放行门；不达标不放行（两项均为硬伤，不能用平均值掩盖）。

### P5 产物与证据链

每页 `page.json`：`blocks + text + counts(expected/actual) + flags + provenance{模型, prompt 版本, 图像哈希}`；
块级定位符沿用项目现行规范并扩展：`《2027 5·3 A版 高考总复习 <科> 精讲册》P<页码>-B<块号>`
（现行只有 `P<页码>`，见 `docs/kb-content-conformance-spec.md:180`）。

**验收判据**：任一入库条目都能回指（册, 页, 块）；`provenance` 四要素齐全；
随机抽 20 条做"从条目倒推到图像区域"的实操走查，20/20 成功。

### 与现有资产/流程的接口（避免另起一套）

| 现有资产 | 路径 | 在本协议里的角色 |
|---|---|---|
| 现行转写暂存 | `knowledge-production/2027-53-transcripts-staged/**/range_*.jsonl` | **待升级**：`{page, heading, text}` → 块结构 |
| 章节骨架 | `kb_tools/chapter_structure_full.json`、`kb_tools/textbook_toc.json` | P0 的"页 → 节"期望表 |
| 已有裁剪 QC | `_qc_crops/`（A_tree.png、L1.png …） | 可扩为块级裁剪与人工抽检样本池 |
| 来源定位规范 | `docs/kb-content-conformance-spec.md:180` | 扩展为 `P<页>-B<块>` |
| 多模态读取通道 | `docs/image-pipeline-spec.md`（`OpenAiCompatibleModelGateway` 图像输入、`ModelTaskKind`） | 新增/复用任务类型承载"结构段 / 填写段 / 校验遍"三次调用 |
| 图像生产规范 | `docs/image-pipeline-spec.md`（生成式重绘有字符漂移风险，需 pilot） | 与本协议无冲突；转写走原图，不走重绘图 `[推断]` |

### 已知缺口（必须在 pilot 前补齐或显式接受）

1. `[未取到]` **没有**"先报条目数→漏项率下降"的直接对照实验；本协议 §② 是基于 PS/LtM + 计数不可靠 的双向推断。
2. `[未取到]` VLM 语境下"固定网格 + 重叠区 + 回拼"的权威做法；§④ 采用"先版面后切片"的推断方案规避。
3. `[未取到]` `@cert` 的枚举取值；`[未取到]` MinerU/中文版面模型细节（抓取失败）。
4. `[推断]` 中文教辅需要的 `KnowledgeMap` / `NoteBox` 两个扩展块类型，属于我们自己加的类别，需在 pilot 中校准。
5. 项目当前**没有**版面检测依赖；P0 步骤 3 的引入成本与精度未评估。

---

## 来源清单（全部为本轮实际抓取）

**规范 / 官方文档**
- DocLayNet 11 类 + docling 扩展（HF 模型卡）：<https://huggingface.co/ds4sd/docling-layout-heron>
- DocLayNet 数据集卡（双重/三重标注用于估计不确定性）：<https://huggingface.co/datasets/ds4sd/DocLayNet/raw/main/README.md>
- docling `DocItemLabel` 与 body/furniture 结构：<https://docling-project.github.io/docling/reference/docling_document/>
- SegmOnto 区域词表：<https://segmonto.github.io/>
- Tesseract 页面分割模式（PSM 0–13）：<https://github.com/tesseract-ocr/tessdoc/blob/main/ImproveQuality.md>
- TEI P5 元素规格：`P5/Source/Specs/{unclear,gap,supplied,damage,att.editLike}.xml`（<https://github.com/TEIC/TEI>）
- TEI P5 组合判定规则（PHCOMB）：`P5/Source/Guidelines/en/PH-PrimarySources.xml`
- Wikisource `{{Illegible}}` 及并列模板：<https://en.wikisource.org/wiki/Template:Illegible>

**开源实现（源码）**
- olmOCR：主提示词与 JSON Schema <https://github.com/allenai/olmocr/blob/main/olmocr/prompts/prompts.py>；
  anchoring <https://github.com/allenai/olmocr/blob/main/olmocr/prompts/anchor.py>；
  README 与 olmOCR-Bench 类别 <https://github.com/allenai/olmocr>
- deepdoctection DocLayNet 类别配置：<https://github.com/deepdoctection/deepdoctection/blob/master/docs/tutorials/Analyzer_Doclaynet_With_YOLO.md>
- Table Transformer：<https://github.com/microsoft/table-transformer>
- Nougat（输出格式与 `[MISSING_PAGE]`）：<https://github.com/facebookresearch/nougat>
- DocLayout-YOLO（DocSynth-300K 合成预训练）：<https://arxiv.org/abs/2410.12628>

**论文**
- DocLayNet：<https://arxiv.org/abs/2206.01062>
- Docling 技术报告：<https://arxiv.org/html/2408.09869v5>
- olmOCR：<https://arxiv.org/abs/2502.18443>
- Plan-and-Solve（missing-step errors）：<https://arxiv.org/abs/2305.04091>
- Least-to-Most：<https://arxiv.org/abs/2205.10625>
- Lost in the Middle：<https://arxiv.org/abs/2307.03172>
- Chain-of-Verification：<https://arxiv.org/abs/2309.11495>、全文 <https://arxiv.org/html/2309.11495v2>
- Self-preference bias：<https://arxiv.org/abs/2404.13076>
- SelfCheckGPT：<https://arxiv.org/abs/2303.08896>
- Vision language models are blind（计数弱点）：<https://arxiv.org/abs/2407.06581>
- LLaVA-UHD（可变切片 + 全局缩略图 + 空间 schema）：<https://arxiv.org/html/2403.11703v1>
- Monkey（等尺寸分块 + 多级描述）：<https://arxiv.org/abs/2311.06607>
- PubTables-1M：<https://arxiv.org/abs/2110.00061>

**行业实践**
- Holley (2009) D-Lib，双人录入 / 精度分级 / 独立仲裁：<http://www.dlib.org/dlib/march09/holley/03holley.html>
