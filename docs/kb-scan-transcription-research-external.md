# 扫描页"纯视觉模型"转写的外部做法调研：项目 / 抗漏 / 抗编 / 像素 / 评测

> 课题：用视觉语言模型（VLM）可靠转写**扫描版**页面（公式、方程式、图表密集的中文教辅页），
> **不调用任何 OCR 引擎**（tesseract / RapidOCR / PaddleOCR 等一律排除），必须"模型自己读图后写文字"。
> 本文只回答：外部有哪些**已被验证的做法**、出处是什么、哪些能迁移到**提示词与流程层**。
>
> 日期：2026-09-22。状态：**调研（未落地）**。仓库元数据（stars / pushed_at）为 2026-09-24 实测值。
>
> 姊妹文档：`docs/kb-scan-transcription-research-layout.md`（版面区块词表、CoVe、TEI 不确定标记、平铺缺陷）。
> 本文**不重复**它已覆盖的内容（DocLayNet 词表、CoVe、SelfCheckGPT、self-preference、TEI P5、LLaVA-UHD/Monkey 平铺），
> 只补它没做的四层：**开源项目的方法内核、抗漏/抗编的具体机制、像素级经验、量化评测口径**。

---

## 0. 检索方法、证据等级与本文边界

**检索通道**：本环境 `WebSearch` 仍不可用（返回 `Provider API kind openai-compatible does not provide provider-native WebSearch`），
因此**没有跨引擎交叉检索**，全部来源由以下三条通道**逐字抓取原文**：

- `curl` 直取 GitHub/HuggingFace 的原始文件（README、LICENSE、源码、arXiv 全文页）；
- `gh api` 取仓库元数据（license / stars / archived / pushed_at）与 `search/code`；
- arXiv 官方 API（`export.arxiv.org/api/query`）取标题与摘要原文。

**证据等级标签**（沿用姊妹文档约定）：
`[源码]` 项目当前源码/README 可逐字引用 · `[规范]` 官方文档原文 · `[论文]` 已抓到的论文页/摘要原文 ·
`[单源]` 仅一处来源、未见独立复核（**按"待验证"对待**） · `[推断]` 我的工程判断，**不是**文献结论 · `[未取到]` 本轮没抓到出处（≠不存在）。

**硬约束导致的取舍（重要）**：我们不调用 OCR 引擎，但下文仍列出 MinerU / marker / docling / PaddleOCR-VL / PDF-Extract-Kit 等
"内含检测或识别模型"的管线式项目。**列它们的唯一目的是搬"方法"**（版面先行、按块处理、空块定点重做、后处理校验、评测口径），
**不是建议去调它们的 OCR**。凡"必须调用某识别引擎才成立"的做法，一律不进入第 ⑥ 节落地建议。

**我们自己的实测基线（用户提供，本轮未复核原始文件，故不作为文献证据；仅用于对齐口径）**：

| 实验 | 结果 |
|---|---|
| 单遍普通转写（基线） | 《高中化学方程式荟萃》一页：应 121 条 → 实际写 **106 条**（整块丢"氧硫 8–15"共 8 条 + 若干）；**19 处"没把握但照写"**；正文**零处**不确定标记 |
| 先清点再填写 | 写到 121 条，与清点一致 |
| 两遍法 | 自查出 6 漏 / 2 多写 / 5 错，但**治不了像素级**（$2H^+$ vs $4H^+$ 反复看也定不了） |
| 切块放大（另一实验） | 对像素级**有效** |
| 图形描述 | **脑补**：给饼图扇区起名字；把流程图"说明框 ↔ 步骤"接反 |

---

## ① 开源项目与方法表

### 1.1 表 A：纯 VLM 端到端（最贴近我们"模型自己读图"的形态）

| 项目 | 方法内核 | 对我们可迁移的技术点 | License | 维护状态（★ / 最后推送） | URL |
|---|---|---|---|---|---|
| **Nougat** (Meta) | 端到端 image→Markup，无 OCR；训练时用扫描劣化增强模拟扫描件 | ①`[MISSING_PAGE]` 占位（姊妹文档已录）②**退化重复的检测量**（logit 方差，B=15，阈值 6.75）③**反重复增强**：训练期按概率随机替换 token，OOD 失败页 −32% | 代码 MIT / **权重 CC-BY-NC** | 10,079★ / 2025-02-21（**停滞**） | <https://github.com/facebookresearch/nougat> · <https://arxiv.org/abs/2308.13418> |
| **olmOCR** (AllenAI) | 7B VLM（v0.2.0 起换用 Qwen2.5-VL 系）整页转 markdown；**结构化前置字段 + 正文**；可选 document anchoring | ①前置字段 schema（姊妹文档已录）②**输出有效性闸门**：`finish_reason != "stop"` → 判无效重试 ③guided_regex 约束前置字段 ④benchmark 的"单元测试"口径 | Apache-2.0 | 19,656★ / 2026-03-25 | <https://github.com/allenai/olmocr> · <https://arxiv.org/abs/2502.18443> |
| **olmOCR 2** | 同架构 + **RLVR 训练，奖励=一组二值"单元测试"**；合成文档造测试用例 | "把'内容是否出现'写成机器可判定的断言"这套思路，**评测与训练通用**；其收益最大项正是公式/表格/多栏 | Apache-2.0 | 同上仓库（2025-10 发布 v0.4.0） | <https://arxiv.org/abs/2510.19817> |
| **GOT-OCR2.0** | 580M 端到端；编码器固定 **1024×1024** 输入；**多裁切（multi-crop）动态分辨率**（InternVL-1.5 式裁切，tiles ≤12） | **"放大切片能救小字公式/表格"的直接对照结论**（见 ④）；区域聚焦 OCR（bbox 提示） | README 徽章：代码 Apache-2.0 / 数据 CC-BY-NC 4.0；**GitHub 未识别出 LICENSE 文件**（`gh api` license=none）→ 商用前须核实 | 8,219★ / 2025-02-10（**停滞**） | <https://github.com/Ucas-HaoranWei/GOT-OCR2.0> · <https://arxiv.org/abs/2409.01704> |
| **DeepSeek-OCR** | DeepEncoder + 3B-MoE；**多分辨率档位**（512/640/1024/1280）与 **Gundam 模式 = n×640 局部块 + 1×1024 全局视图**（n∈2..9） | ①**压缩比 ↔ 精度**的量化曲线（见 ④）②**n-gram 重复抑制**（`ngram_size=30, window_size=90`）③**按任务分档的 prompt**（document / free OCR / figure / describe / rec）④grounding 模式输出 bbox | MIT | 23,906★ / 2026-01-27 | <https://github.com/deepseek-ai/DeepSeek-OCR> · <https://arxiv.org/abs/2510.18234> |
| **dots.ocr** (小红书) | 1.7B **单模型统一"版面检测 + 内容识别"**，一个 prompt 切换任务，输出 bbox + 类别 + 文本（公式→LaTeX、表→HTML、其余→Markdown），**按人类阅读顺序排序、单一 JSON** | ①**可直接抄的"版面 JSON 提示词"**（见 ③）②页面级像素建议（200 DPI / 总像素上限）③限制条款里给出"何时该放大"的判据 | MIT | 9,148★ / 2026-03-24 | <https://github.com/rednote-hilab/dots.ocr> · <https://arxiv.org/abs/2512.02498> |
| **Zerox** (OmniAI) | **"把每页转成图片，交给视觉模型要 Markdown"**——零 OCR 引擎路线的工程实现 | ①`extractPerPage`（逐页独立）②`maintainFormat`：把**上一页的输出**作为下一页上下文（跨页表格一致性）③`correctOrientation`、失败重试 `maxRetries` | MIT | 12,263★ / 2025-05-20 | <https://github.com/getomni-ai/zerox> |
| **SmolDocling-256M** (IBM/DS4SD) | 256M 小 VLM，输出 **DocTags**（受控标签序列），可加载回 `DoclingDocument` | **"输出用受控标签而非自由描述"**的最小实现范本；适合"低成本跑覆盖自检遍"的候选 | `cdla-permissive-2.0`（HF 模型卡） | HF 模型（预览版） | <https://huggingface.co/ds4sd/SmolDocling-256M-preview> |

### 1.2 表 B：管线式 / 版面先行（只搬方法，不搬引擎）

| 项目 | 方法内核 | 可迁移点 | License | 维护状态 | URL |
|---|---|---|---|---|---|
| **marker** (Datalab) | 版面 → 按块处理 → 表格重建 → 可选 LLM 精修；两种模式（balanced/fast） | ①**"坏文本触发整页重做"**：balanced 模式"任何内嵌文本被判定为坏，就重做整页" ②fast 模式**"定点修复单个乱码/空块"** ③**低置信重建回退到 VLM**（带阈值）④`--block_correction_prompt` 自定义纠错提示词 | Apache-2.0（LICENSE 原文为纯 Apache-2.0，本轮已逐字核对） | 39,944★ / 2026-09-13 | <https://github.com/datalab-to/marker> |
| **surya** (Datalab) | marker 底层的版面+OCR VLM，本地推理服务 | 版面模型的"块"输出形态 | Apache-2.0（LICENSE 原文已核对） | 21,415★ / 2026-09-11 | <https://github.com/datalab-to/surya> |
| **Chandra** (Datalab) | marker 团队的文档 VLM（olmocr-bench **83.1**，取自 marker README 结果表；该 README 正文另处自述 **85.8**，两处口径不一致，须以官方 checker 复跑为准） | 整页 VLM 路线的最新基线参照 | Apache-2.0 | 12,324★ / 2026-06-26 | <https://github.com/datalab-to/chandra> |
| **MinerU** (OpenDataLab) | 四档解析（flash/basic/standard/advanced）；VLM + 小模型；**稳定定位符** `doc:{id}/tier:{t}/page:{p}/block:{b}`；按页/按块续读 | ①**块级定位符 = 证据链与"按块核对"的天然主键**（与我们的 `sourceLocator` 可对齐）②"长文档用 page/block continuation，而不是整文件塞上下文" | **MinerU Open Source License**（README 原文："based on Apache 2.0 with additional conditions"，非纯 Apache） | 80,584★ / 2026-09-24 | <https://github.com/opendatalab/MinerU> |
| **docling** (IBM) | 版面（72 dpi 图上跑）+ 表格 TableFormer + 公式→LaTeX；216 dpi OCR；阅读顺序推断 | 姊妹文档已录；本文只补：**"版面用低分辨率、识别用高分辨率"的双通道分辨率**是工业默认 | MIT | 67,821★ / 2026-09-24 | <https://github.com/docling-project/docling> · <https://arxiv.org/html/2408.09869v5> |
| **PDF-Extract-Kit** | 版面/公式检测/公式识别/OCR 的工具箱式组合 | "公式单独一条链"的拆分范式 | AGPL-3.0 | 10,025★ / 2025-01-03 | <https://github.com/opendatalab/PDF-Extract-Kit> |
| **texify** (VikParuchuri) | 公式/行内数学专用识别（已并入 marker 生态） | "行内公式单独处理"的先例 | GPL-3.0 | 1,125★ / **已归档**（2025-01-29） | <https://github.com/VikParuchuri/texify> |
| **Table Transformer** (MS) | 表格结构识别（DETR 系） | 表格"不可切"论证（姊妹文档已录） | MIT | 2,946★ / **已归档**（2024-06-24） | <https://github.com/microsoft/table-transformer> |

### 1.3 表 C：评测与度量工具（我们量化"漏/编/错"的现成口径）

| 工具 | 用途 | 关键口径 | License | 维护状态 | URL |
|---|---|---|---|---|---|
| **olmOCR-Bench** | 整页转写的**单元测试式**基准 | 7,000+ 测试用例 / 1,400 文档；反对纯编辑距离；报告 ±区间 | Apache-2.0 | 随 olmOCR 活跃（2026-03） | <https://github.com/allenai/olmocr/tree/main/olmocr/bench> |
| **OmniDocBench** | 文档解析的**综合标注基准** | 1651 页 / 10 类；文本 NED、公式 CDM、表格 TEDS、阅读顺序；Overall 公式（见 ⑤） | Apache-2.0 | 2,064★ / 2026-09-11（v1.7） | <https://github.com/opendatalab/OmniDocBench> |
| **CDM**（UniMERNet） | **公式专用**度量 | "不受公式表达多样性影响"；CDM@ExpRate；CVPR 2025 | Apache-2.0 | 502★ / 2025-09-28 | <https://github.com/opendatalab/UniMERNet/tree/main/cdm> · <https://arxiv.org/abs/2409.03643> |
| **MathWriting** (Google) | 手写数学式数据集 + 基线 | 230k 人工 + 400k 合成；**提供 normalized LaTeX** 简化比较 | **本轮未取到**（GitHub 仓库页未抓，HF 未见模型卡）`[未取到]` | — | <https://arxiv.org/abs/2404.10690> |

---

## ② 抗"漏"：整块条目消失，外部有哪些已被验证的手段

### 2.1 先给结论（含明确的"没有证据"）

- **"先清点条目数再逐条填"本身，本轮仍未找到对照实验证据**。与姊妹文档（§2.1）结论一致：这是一个**在文献里检索不到对照实验、
  但在我们内部实测里有效**的做法。任何"业界已证明"的表述都不成立。
- 有直接证据的是它的**近亲机制**，而且都是**可机械执行**的（不依赖模型自觉）：
  截断检测、空块/坏块定点重做、低置信回退、退化重复检测、单元测试式覆盖断言。
  这五条对"整块丢失"这类**可被外部信号捕捉**的故障有效；对"像素级看不清"无效（那是 ④ 的问题）。

### 2.2 截断 / 超长导致的大段丢失 —— olmOCR 的闸门 `[源码]`

`olmocr/pipeline.py` 原文（逐字）：

```python
is_valid = True
if base_response_data["usage"]["total_tokens"] > MODEL_MAX_CONTEXT:
    is_valid = False
if base_response_data["choices"][0]["finish_reason"] != "stop":
    is_valid = False
```

读法：**"没有正常自然停"就等于"结果不可信"，整页标记无效并重试**（而非接受半截文本）。
对我们是**最便宜的一道门**：一页 121 条写不完、被截断时，`finish_reason` 会暴露它。
来源：<https://raw.githubusercontent.com/allenai/olmocr/main/olmocr/pipeline.py>（2026-09-24 抓取）

### 2.3 空块 / 坏块的"定点重做" —— marker 的两级回退 `[源码]`

marker README 原文（逐字）：

- balanced：*"uses the surya VLM for layout, OCRs inline math, and re-OCRs the **whole page** whenever any of its embedded text is bad"*
- fast：*"keeps VLM use minimal: equations, **surgical block-level repair of individual garbled/empty blocks**, and a single full-page pass only for pages that are scanned or mostly bad"*
- 表格：*"low-confidence reconstructions fall back to the VLM, with a stricter bar in balanced"*

读法：**"空块"和"乱码块"被当成显式可检测状态，并用更昂贵的第二遍定点补**。
对我们的直接映射：一页里"某区块没写出内容"或"某区块输出明显不成条"时，**只重跑该区块（且放大）**，而不是全页重跑。
来源：<https://github.com/datalab-to/marker>（README，2026-09-24 抓取）

### 2.4 退化重复（写到一半开始复读）—— 两套可量化的检测/抑制 `[论文][源码]`

**Nougat §5.4（论文原文）**：

- *"We notice that the model degenerates into repeating the same sentence over and over again. The model can not recover from this state by itself."*
- *"We observed this behavior in **1.5%** of pages in the test set, but the frequency increases for out-of-domain documents."*
- 检测量：滑动窗口 **B=15** 的 logit 方差，**阈值 6.75**，低于阈值且持续到末尾即判为重复。
- 反重复增强：训练期按概率替换随机 token，*"Particularly for out-of-domain documents, where we saw a **32% decline in failed page conversions**"*。

**DeepSeek-OCR（README 源码）**：推理时挂 `NGramPerReqLogitsProcessor`，参数 `ngram_size=30, window_size=90`（另有 `whitelist_token_ids` 白名单）。

读法：**"复读"是一个被承认、可被统计量捕捉、并有工程缓解的失败模式**；单页长清单转写里，一旦进入复读，后面整段就没了。
来源：<https://arxiv.org/html/2308.13418v1>（§5.4）· <https://raw.githubusercontent.com/deepseek-ai/DeepSeek-OCR/main/README.md>

### 2.5 跨页 / 跨块的"跳号"与续接 `[论文][源码][单源]`

- Nougat 自认的局限（论文原文）：*"Most notably in the bibliography where the model was trained on different styles or **section titles where sometimes numbers are skipped or hallucinated**."*
  → **"跳号"（跳过=漏）是论文明确承认的失败**，而且与"跨页一致性"绑定（Nougat 单页独立处理）。
- Zerox `maintainFormat`：把上一页 markdown 作为下一页的上下文（README 原文：*"passing the output of a prior page in as additional context for the next page… valuable if your documents have a lot of tabular data, or frequently have tables that cross pages"*）——**代价是必须串行、明显变慢**（README 自述）。`[单源]`
- MinerU：长文档"用 page/block continuation 而不是整文件入上下文"，并给稳定定位符。

### 2.6 覆盖自检的两种形态（一处有出处，一处只有结构性条件）`[论文][推断]`

- **有出处**：olmOCR-Bench / olmOCR 2 把每个"事实"写成**二值断言**（"某句必须原样出现在页面某处"），
  并在 olmOCR 2 里直接用作 **RLVR 奖励**；论文自述其收益最大项是**公式、表格、多栏**（正是我们最痛的版面）。
  这是"**逐条核对覆盖率**"在工业界的近亲形态（区别：他们核对的是断言集合，不是"条目总数"）。
  来源：<https://github.com/allenai/olmocr/tree/main/olmocr/bench> · <https://arxiv.org/abs/2510.19817>
- **只有结构性条件（未找到直接出处）**：dots.ocr / MinerU 这类"**每块都带 bbox + 文本**"的结构化输出，
  天然允许"**块清单 vs 文本清单**"对齐（检测出 N 块，只有 M<N 块有文本 ⇒ 报可疑）。
  **本轮未找到任何项目公开把这条做成闸门的记录**，故标 `[推断]`：它是从"结构化输出 + marker 空块修复"推出的做法。

### 2.7 逐条对照：能不能治"121 → 106 / 整块丢 8 条"

| 机制 | 对"整块丢" | 对"像素级错" | 对我们现流程的改造量 |
|---|---|---|---|
| 截断闸门（`finish_reason`） | **能**（若丢因是截断/超长） | 否 | 极小（一次判断） |
| 空块/坏块定点重做 | **能**（空块 = 漏的直接信号） | 部分（重做时放大即可） | 中（需块清单） |
| 退化重复检测 | **能**（复读后整段丢失） | 否 | 小（纯文本统计，可离线跑） |
| 条目级覆盖自检（清点 vs 转写） | **能**（我们已经实测有效） | 否 | 小（已有清点结果） |
| 单元测试式断言（陈式） | 能，但需要先写断言 | 否 | 中（每页要生成断言） |
| 跨页续接（Zerox 式） | 部分（跨页表格/长清单） | 否 | 中（串行，慢） |

---

## ③ 抗"编"：不让模型用"听起来对"的内容填空

### 3.1 先接受一个事实：提示词层面的"别编"不是根治手段 `[源码][论文]`

- olmOCR 主提示词里已有 **`Do not hallucinate.`**（姊妹文档已逐字录），但其 README 的版本日志仍写着：
  **v0.3.0（2025-08-13）"fixes auto-rotation detection, and hallucinations on blank documents"**，
  且 olmOCR-Bench 的版本表里存在 **"v0.3.0 + Handle blank pages"** 一行 → **"空白页也编"是长期存在的缺陷，靠提示词没治完**。
- Nougat 论文的反面证据：真正把 OOD 失败率降 32% 的是**训练期反重复增强**，不是推理期提示。

**对我们**：`Do not hallucinate` 这类指令可以留，但**不能作为抗编措施计入**（不消灭可指认的失败）；抗编必须落在**流程与产物结构**上。

### 3.2 结构先行：把"自由正文"降级为"受控结构 + 受控标签" `[源码]`

- **可直接抄的 dots.ocr 版面 JSON 提示词**（README 原文，节选逐字）：

  ```
  1. Bbox format: [x1, y1, x2, y2]
  2. Layout Categories: The possible categories are ['Caption', 'Footnote', 'Formula', 'List-item',
     'Page-footer', 'Page-header', 'Picture', 'Section-header', 'Table', 'Text', 'Title'].
  3. Text Extraction & Formatting Rules:
      - Picture: For the 'Picture' category, the text field should be omitted.
      - Formula: Format its text as LaTeX.
      - Table: Format its text as HTML.
      - All Others (Text, Title, etc.): Format their text as Markdown.
  4. Constraints:
      - The output text must be the original text from the image, with no translation.
      - All layout elements must be sorted according to human reading order.
  5. Final Output: The entire output must be a single JSON object.
  ```
  来源：<https://github.com/rednote-hilab/dots.ocr>（README 中 `prompt` 字面量，2026-09-24 抓取；**逐字**，仅调整缩进）
- **olmOCR 的 guided_regex**：用正则约束"前置字段必须长成规定形状"，否则判无效（源码已录，见 2.2 同文件）。
- **SmolDocling 的 DocTags**：受控标签序列，可程序化加载回文档树（模型卡）。

**为什么这对"抗编"有用（机制而非口号）**：自由正文里，"不存在的扇区名"与"真实文字"**长得一样**；
一旦输出必须是"类别 + bbox + 文本"的定长结构，**没有 bbox 支撑的内容无处安放**，且缺失会表现为**字段为空**（可检测状态），而不是悄悄补一句话。

### 3.3 允许/要求"不确定"，并把置信度当信号用 `[论文][单源]`

- **ChartHal（2025-09）**：*"questions involving information **absent from or contradictory to** charts are especially likely to trigger hallucinations"*；
  其数据上 GPT-5 仅 34.46%、o4-mini 22.79% 正确 → **"图上没有的信息"是最强幻觉触发器**，与我们"给饼图扇区起名字"完全同型。
  来源：<https://arxiv.org/abs/2509.17481> · <https://github.com/ymcui/ChartHal>
- **"Just Ask for Calibration"（EMNLP 2023）**：对 ChatGPT/GPT-4/Claude 这类 RLHF 模型，**以输出 token 表达的口头置信度，通常比条件概率校准得更好** → 让模型"说出没把握"在工程上是**可用信号**，而不是客套话。来源：<https://arxiv.org/abs/2305.14975>
- `[单源]` **Conformal abstention**（为 LLM/VLM 学自适应弃权策略）：<https://arxiv.org/abs/2502.06884>（单一来源，**待验证**）。

### 3.4 自查 ≠ 核验：必须"看不到第一遍的正文 + 引入新证据" `[论文]`

- **"Large Language Models Cannot Self-Correct Reasoning Yet"（ICLR 2024）** 原文：*"LLMs struggle to self-correct their responses without external feedback, and at times, **their performance even degrades after self-correction**."*
  → 直接支持我们"两遍法能查 6 漏但治不了像素级"的实测：**没有新证据的自我复查，天花板就在第一遍的感知里**。
  来源：<https://arxiv.org/abs/2310.01798>
- 与姊妹文档的 **CoVe（核验阶段不给草稿看）**、**self-preference bias**、**SelfCheckGPT** 合起来构成完整论断：
  **核验必须是"独立 + 新证据（放大图/局部重读）"，而不是"请再检查一遍"。**

### 3.5 图 / 表描述：把"语义命名"从允许集里拿掉 `[论文][源码][推断]`

- 证据侧（**源码级**）：dots.ocr 的版面提示词里直接写着 *"**Picture**: For the 'Picture' category, **the text field should be omitted**."*
  ——即"**图区不许产出文本**"是一条被工程采纳的硬规则；同一项目的模型卡又自认 *"**Picture**: Pictures in documents are currently not parsed."*（Limitation 段）。
  **连 SOTA 文档 VLM 都选择"图内内容不写成文字"**，这比"写得好不好"更值得我们先接受。
  ChartHal 另证："图上不存在/与图矛盾的信息"最易触发幻觉（见 3.3）。
- 可迁移做法（`[推断]`，非文献）：图描述只允许 **①可见的图元与其位置 ②图上真实存在的文字/图例/坐标轴标签 ③连线方向**；
  **禁止**给未命名的扇区/框起名字（应写"未标注名称的扇区，约占 1/4"）；
  流程图必须**逐条引用框内文字**作为锚点，锚点对不上就整条标不确定。
- 工程形态：把"转写"与"描述"拆成**不同 prompt、不同输出字段**（DeepSeek-OCR 的 prompt 分档就是这种拆法：`Convert the document to markdown` / `Parse the figure.` / `Describe this image in detail.` / `Locate <|ref|>…`）。

---

## ④ 像素级可辨性：分辨率、裁切、放大的已知经验

### 4.1 可用数字（逐条给出处与适用范围）

| 数值 / 结论 | 出处 | 适用范围 | 能否直接用 |
|---|---|---|---|
| x-height（小写 x 高）**< 10 px 基本无望**，**< 8 px 多数被判为噪声**；10pt @300dpi ≈ **20 px** x-height；LSTM 引擎在 **~30 px 以上**反而不准 | Tesseract FAQ（`tess3/FAQ-Old.md`，逐字："Accuracy drops off below 10pt x 300dpi, rapidly below 8pt x 300dpi… Below an x-height of 10 pixels, you have very little chance of accurate results, and below about 8 pixels, most of the text will be 'noise removed'."） | **OCR 引擎侧**（且 tesseract 已非我们可调用的对象） | 只能当**几何下限参考**：它告诉我们"小到什么程度人类与机器都无解"，**不能当作 VLM 的阈值** |
| *"When the **character-to-pixel ratio** is excessively high. Try enlarging the image or increasing the PDF parsing **DPI (a setting of 200 is recommended)**"*；但单页总像素 **< 11,289,600**（=3360×3360）表现最佳 | dots.ocr 模型卡 Limitation 段（逐字） | **VLM 侧**，且是"该模型自述的经验值" `[单源]` | 可用作**"放大到多少、以及放大上限"**的起点；具体到我们的模型须实测 |
| 分辨率档：512/640/1024/1280（对应 64/100/256/400 vision tokens）；**Gundam = n×640 局部块 + 1×1024 全局**（n 控制在 2–9，避免过度切碎） | DeepSeek-OCR 论文 §3.2.2 + Table 1 / README Support-Modes | VLM 侧，架构性 | **"全局图 + 局部高倍块"**这一组合形态可直接照搬 |
| 压缩比 ↔ 精度：**9–10× 时 96%+**，10–12× 约 90%，**20× 掉到约 60%** | DeepSeek-OCR 论文 Abstract / §4.1 | VLM 侧（Fox benchmark） | 说明"一页塞越多字，精度越低"是**连续衰减**；长清单页尤其危险 |
| 多裁切（multi-crop）推理时 *"the performance of GOT is further lifted **especially on formulas and tables with small texts**"* | GOT-OCR2.0 论文 §（Table 3 讨论，逐字） | VLM 侧，**与我们"切块放大"实验直接同型** | **这是"放大救小字"最直接的一条对照结论**（论文级） |
| 版面用 **72 dpi**、识别用 **216 dpi**（docling）；报纸/笔记类基准图从 **72 DPI 提到 200 DPI**（OmniDocBench v1.5） | <https://arxiv.org/html/2408.09869v5> · OmniDocBench README（逐字："Increased the image resolution for newspaper and note types from 72 DPI to 200 DPI"） | 工业与基准侧 | 印证"**低分辨率只够看版面、不够认字**"；我们"切块放大"正是补这一层 |
| 识别模型输入高度 **48 px**（PP-OCRv5 `image_shape: [3,48,320]`；旧版 `rec_image_shape="3, 48, 320"`） | PaddleOCR 配置（`configs/rec/PP-OCRv5/PP-OCRv5_mobile_rec.yml`） | **OCR 引擎侧**（仅作参照） | 说明"整行文字被缩到 ~48 px 高"就已是识别模型的正常工作点；我们是**整页**入模，密度远低于此 |
| 切片推理默认 **overlap_ratio = 0.2**（doc："Set overlap ratio for height/width (default: 0.2)"）；512×512 切片示例用 0.1 | SAHI `docs/cli.md` + README | **目标检测**切片，不是文档 OCR `[单源]` | **唯一找到的量化重叠率**；用于文档转写属**跨领域外推，待验证** |
| 448×448 tiles（1–40 个，支持 4K）；NaViT 式动态分辨率（不同分辨率→不同视觉 token 数） | InternVL 1.5 <https://arxiv.org/abs/2404.16821> · Qwen2-VL <https://arxiv.org/abs/2409.12191> | VLM 侧 | 说明主流模型都靠"切 + 全局"解决高分辨率 |

### 4.2 缺口（**未找到证据**，不要编）

- **"VLM 的每字符最小像素高度"没有公认标准值**。找到的全部量化下限都来自 OCR 引擎（tesseract x-height）或单一模型的模型卡（dots.ocr），
  二者**不可互相替代**。→ 结论：**该阈值必须用我们自己的页面实测标定**。
- **文档 OCR 的分块重叠率没有公认值**；0.2 来自目标检测切片（SAHI），跨领域引用须标"待验证"。
- 我们的"切块放大"具体倍数（几倍、块多大）**未找到可复用的外部配方**；只有"该放大"的方向性证据（GOT / dots.ocr / OmniDocBench DPI）。

---

## ⑤ 评测口径：别人怎么量化"漏 / 编 / 错"

### 5.1 olmOCR-Bench：**单元测试式**（对我们最可复制的一套）`[源码]`

README 逐字要点：

- 工作方式：*"olmOCR-Bench works by testing various 'facts' about document pages at the PDF-level. Our intention is that each 'fact' is very simple, unambiguous, and machine-checkable, **similar to a unit test**. For example, once your document has been OCRed, we may check that a particular sentence appears exactly somewhere on the page."*
- 明确反对纯编辑距离：*"We **stay away from soft metrics like edit distance** comparisons, because they may assign lower scores for parses of the document that differ from the reference, but may in fact still be correct… some documents may have critical details, like **switching x and y in an equation** that can make all the difference in understanding, but would appear as just **a single character edit** in an edit-distance metric."*
- 规模与报告：olmOCR README 自述"over **7,000 test cases across 1,400 documents**"；marker README 采用同一基准并写"**1,403 PDFs with ~8,400 pass/fail unit tests**"，总分取 **8 个类别的宏平均**，**引用官方 checker**，且分数带区间（如 `76.1±1.1`）。
- 类别里 **"Old scans math" / "Long tiny text" / "Tables" / "Multi column"** 单列——正是我们四本书的失分面（姊妹文档已指出）。

来源：<https://github.com/allenai/olmocr/tree/main/olmocr/bench> · <https://github.com/datalab-to/marker>

### 5.2 OmniDocBench：**标注 + 多指标 + 匹配算法** `[源码]`

- 数据规模与标注：**1651 页 / 10 类文档 / 5 种版面 / 5 种语言**；**28 类块级 + 4 类 span 级**（含行内公式、上下标）标注；公式给 LaTeX、表格给 LaTeX + HTML；还有**阅读顺序**标注与页/块属性标签（5 页属性、3 文本属性、6 表属性）。
- Overall 公式（README 逐字）：`Overall = ((1 − Text Edit Distance) × 100 + Table TEDS + Formula CDM) / 3`。
- **v1.5 的 hybrid matching**：*"allowing formulas and text to be matched with each other, which **reduces the score impact caused by models outputting formulas in unicode format**"* → **对我们极其关键**：我们的产物是 LaTeX，若与"Unicode 写法的参考答案"比，会被无谓扣分。
- **v1.6 的 MGAM**：*"keep the ground truth unchanged and **search for the optimal segmentation granularity only on the prediction side**"* → 只惩罚内容错，不惩罚切分粒度不同。
- 可比对配置：`match_method: quick_match`（含截断/合并的相邻搜索匹配，官方推荐）、页面属性 `filter`（做**分层抽样**）。
- 依赖提示：CDM 需要 TeX Live / ImageMagick / Ghostscript，且要求 `pdflatex` 带 CJK 字体支持。

来源：<https://github.com/opendatalab/OmniDocBench>

### 5.3 公式专用：CDM `[源码]`

UniMERNet README 逐字：*"Compared to BLEU/EditDistance, CDM provides a more intuitive and accurate evaluation score, **allowing for fair comparison of different models without being affected by formula expression diversity**"*；
*"CDM@ExpRate means the proportion of formulas is **completely** predicted"*；2025-09-28 起 **CDM 支持中文公式**。
来源：<https://github.com/opendatalab/UniMERNet/tree/main/cdm> · 论文 <https://arxiv.org/abs/2409.03643>（CVPR 2025）

### 5.4 手写数学式：MathWriting 的"规范化答案"思路 `[论文]`

数据集提供 **normalized LaTeX**：*"We also provide a normalized version of LaTeX expression to simplify the recognition task and enhance the result quality."*
→ 迁移点：**我们应同时保存"原始 LaTeX"与"规范化 LaTeX"两态**，比对用后者，避免等价写法（`\dfrac` vs `\frac`、上下标写法差异）淹没真错误。
来源：<https://arxiv.org/abs/2404.10690>（230k 人工 + 400k 合成）

### 5.5 条目级完整性（**我们最需要的那一维**）

- **未找到证据**：没有任何公开 benchmark 定义"**长清单条目的漏写率**"这一类指标；
  最接近的是 olmOCR-Bench 的"fact 是否出现"（可用来近似"该条是否被写出"），以及 OmniDocBench 的**文本编辑距离**（对"整条丢失"不敏感，因为它是**字符级**的）。
- **建议口径（这是我们的定义，不是文献结论，标注为自定）**：
  1. `条目 Recall = |转写条目 ∩ 清点条目| / |清点条目|`；`条目 Precision = 反向`（多写/编造在此暴露）；
  2. 清点与转写**分开两次独立产出**，比对在**代码层**做（不交给模型自评）；
  3. 像素级错误用**抽样子集**人工双读裁决（图书馆"双人录入 + 独立仲裁"先例，姊妹文档已录）；
  4. 每页报告"不确定标记数"，且**不确定标记为 0 而抽样仍有错**要单独记一类缺陷（正是我们基线 19 处照写 / 0 标记的情形）。

### 5.6 报告规范（从上游抄的两条）`[源码]`

- **带区间**：olmOCR-Bench 结果表给 `80.0±1.0` 这类区间；我们也应在同一页重复 N 次跑、报均值 ± 区间，而不是单跑一次的数字。
- **固定解码参数**：DeepSeek-OCR 官方示例 `temperature=0.0, max_tokens=8192`；采样温度不固定时，漏/错的可比性不成立。

---

## ⑥ 对我们（只用模型视觉 + 中文教辅 + 输出 Markdown+LaTeX）的落地建议

> 排序依据：**"消灭的失败是否具体、是否可机械验收、改造量"** 三者综合。每条都写明它消灭哪个已有失败（无此说明的措施一律不进表）。

| # | 做法 | 它消灭的具体失败 | 证据 | 成本 | 验收口径 |
|---|---|---|---|---|---|
| **P0-1** | **截断闸门**：单页输出若"未自然结束"（`finish_reason != stop` 等价信号）或触到 token 上限 ⇒ 整页判无效，按"分块重跑"处理 | 基线 106/121 中"整段没写出来"的**截断型漏** | olmOCR `[源码]` | 极小（一次判断 + 一次重试） | 重跑后页面必须自然结束；重试次数与触发页数入统计 |
| **P0-2** | **清点 / 填写分离 + 条目级差集自检**：清点产出条目键集合，填写后**代码算差集**；差集非空 ⇒ 该页标 FAIL，禁止"静默通过" | 整块丢 8 条（"氧硫 8–15"）这类**结构性漏**；以及"两遍法自查不到"的盲区 | 我们实测（清点法有效）+ olmOCR-Bench 断言式口径（间接） | 小（清点已有先例） | 任意页面 `Recall=1`；不达标必须显式标 `MISSING:[条目键]` |
| **P0-3** | **不确定标记强制化**：正文与清点两处都要能出现不确定标记（语义对标 TEI `unclear`，姊妹文档）；**"0 标记"可疑** | 基线"19 处没把握但照写、零处标记"= 把错误伪装成确定 | `Just Ask for Calibration` `[论文]`（口头置信度可用）+ ChartHal（"图上不存在的信息"最易触发幻觉） | 小（提示词 + 产物字段） | 抽样复核中"未标记但错"的比例；该比例为 0 才算通过 |
| **P1-1** | **空块 / 坏块定点放大重做**：先出块清单（块类型 + 区域），对"空块、乱码块、条数不齐块"**只重跑该块并放大**（非全页） | 整块漏 + 局部像素级错；同时避免"全页重跑引入新错" | marker 两级回退 `[源码]`；GOT multi-crop 对小字公式/表格有效 `[论文]` | 中（需块清单；可用现有版面检测/无 OCR 的块检测器） | 重做块的 diff 必须"只增不减内容"，且新增内容不与原块冲突 |
| **P1-2** | **像素级复核只走"切块放大"**：对低置信/不确定标记处，按固定放大倍率与重叠率（**重叠率 0.2 起步，标 `[单源]` 待验证**）生成局部图，**与原图对照**后定稿 | $2H^+$ vs $4H^+$ 这类**同倍率看几遍也定不了**的像素错 | GOT `[论文]`；DeepSeek-OCR 分辨率/压缩比 `[论文]`；dots.ocr 200 DPI 建议 `[单源]`；SAHI 重叠率 `[单源]` | 中（图像处理 + 复跑） | 该处的判定必须附"局部图 + 依据"；两遍结论不一致时保留标记而非择一 |
| **P1-3** | **图文分离 + 描述白名单**：图描述独立字段；只允许"可见图元/图上真实文字/连线方向"，**禁止自造名称**；流程图锚点必须引用框内文字 | 给饼图扇区起名字、流程图接反 | ChartHal `[论文]`；dots.ocr 自认不解析图内内容 `[源码]`；白名单规则为 `[推断]` | 小（提示词 + 字段） | 描述中每个专名都能在图上找到对应文字，否则判 FAIL |
| **P1-4** | **受控输出结构**：块类型受控表 + 阅读顺序 + 单 JSON（可抄 dots.ocr 的提示词骨架）；平台支持时加 guided 约束（olmOCR 式正则） | "缺字段"变成不可见错误；自由正文里编造内容无处安放 | dots.ocr `[源码]`；olmOCR guided_regex `[源码]`；SmolDocling DocTags | 中（改提示词与解析层） | JSON 必须可解析；缺字段/空字段单独统计（不静默） |
| **P2-1** | **核验遍独立化**：核验调用**不带第一遍正文**，只带图（或放大块）+ 清点表 | "请再检查一遍"式自查无效甚至有害 | `LLMs Cannot Self-Correct…` `[论文]`；CoVe / self-preference（姊妹文档） | 中（多一次调用 + 串行） | 核验遍独立产出的条目集合与第一遍做差集，差集必须为空或全部有标记 |
| **P2-2** | **抽样双人裁决 + 区间报告**：按页型（方程式荟萃/分栏/表格/图）分层抽样，双人独立读同一块；同页重复 N 次报均值±区间 | "单跑一次的数字"无法支撑"已通过"的结论 | 图书馆双录先例（姊妹文档）；olmOCR-Bench 报 ± 区间 `[源码]` | 中（人力） | 层内与层间错误率、区间宽度写进报告 |
| **P2-3** | **接评测工具（可选）**：公式质量如需外部可比，接 **CDM**（注意 CJK 需 `pdflatex` 中文字体）；整页可比则用 **OmniDocBench v1.6 + hybrid matching** | 我们自己的"错"无法与外部横向比较 | OmniDocBench / CDM `[源码]` | 中高（环境依赖重：TeX Live/ImageMagick/Ghostscript） | 固定 `match_method=quick_match`、固定 DPI、固定 decode 参数 |

**明确不建议做的（有反面证据）**：
- **加"请勿幻觉"提示词当作抗编措施**——olmOCR 常年在用同款提示，仍把"空白页幻觉"当成版本修复项（§3.1）；
- **把整册塞进一次长上下文**——Nougat 自述跨页时"numbers are skipped or hallucinated"（§2.5），MinerU 明确建议按页/块续读；
- **靠"重看几遍"解决像素级**——GOT 的增益来自**裁切放大**而不是多看；Tesseract 的 x-height 下限也说明低于某尺寸时"看几遍都一样"（§4.1）。

**与仓内既有资产的接口（不另起一套）**：
- 姊妹文档 `docs/kb-scan-transcription-research-layout.md` 已给出 P0–P5 转写协议草案；本文的第 ⑥ 节是它的**外部证据补强与优先级重排**，两者应合并执行而不是各跑一套。
- 块级主键可复用 `docs/kb-content-conformance-spec.md:180` 的 `sourceLocator`（现为 `P<页码>`，建议扩到 `P<页码>-B<块号>`），与 MinerU 的 `page/block` 定位符思路一致。
- 现行转写暂存仍是 `{"page","heading","text"}` 扁平结构（`knowledge-production/2027-53-transcripts-staged/`）——**没有块、没有计数、没有不确定字段**，本文 P0-2 / P0-3 / P1-4 都需要先扩这个结构。

---

## ⑦ 明确"未找到证据"的清单（不要当已证明）

1. **"先清点条目数再逐条填写"的对照实验**：未找到（与姊妹文档结论一致）。它在**我们内部实测**有效，属于我方证据。
2. **VLM 的"每字符最小像素高度"公认值**：未找到。已有数字全部来自 OCR 引擎（tesseract）或单一模型卡（dots.ocr）。
3. **文档转写的分块重叠率公认值**：未找到。0.2 来自目标检测切片库（SAHI）文档，跨领域引用须标"待验证"。
4. **"长清单条目漏写率"这一指标的公开 benchmark / 标准定义**：未找到。第 ⑤.5 节口径是**我们的定义**。
5. **VLM 在中文教辅（方程式荟萃/思维导图页型）上的公开评测**：未找到。olmOCR-Bench 是英文；OmniDocBench 有中文页但不含教辅这一页型。
6. **"块清单 vs 文本清单"覆盖闸门的公开实现**：未找到（我的 `[推断]`）。
7. **单一来源待验证**：SAHI 重叠率 0.2、Zerox `maintainFormat` 的实际增益、dots.ocr 的 200 DPI / 总像素上限、conformal abstention（2502.06884）。

---

## 来源清单（全部为本轮实际抓取；抓取时间 2026-09-24）

**仓库元数据**（`gh api repos/<owner>/<repo>`，字段：license / stars / archived / pushed_at）
- facebookresearch/nougat · datalab-to/marker · datalab-to/surya · datalab-to/chandra · allenai/olmocr ·
  opendatalab/MinerU · Ucas-HaoranWei/GOT-OCR2.0 · rednote-hilab/dots.ocr · deepseek-ai/DeepSeek-OCR ·
  docling-project/docling · getomni-ai/zerox · opendatalab/PDF-Extract-Kit · VikParuchuri/texify ·
  opendatalab/OmniDocBench · opendatalab/UniMERNet · microsoft/table-transformer · PaddlePaddle/PaddleOCR

**原始文件（`raw.githubusercontent.com` / HuggingFace raw 直取）**
- olmOCR：README、`olmocr/bench/README.md`、`olmocr/pipeline.py`、`olmocr/prompts/prompts.py`（后两者引姊妹文档）
- marker：README、`LICENSE`（逐字核对为纯 Apache-2.0）；surya：`LICENSE`
- Nougat：README（代码 MIT / 权重 CC-BY-NC）
- MinerU：README（许可原文、四档解析、定位符、续读语义）
- DeepSeek-OCR：README（`NGramPerReqLogitsProcessor`、prompt 分档、Support-Modes）、HF 模型卡
- dots.ocr：README（版面 JSON 提示词）、HF 模型卡（Limitation 段）
- GOT-OCR2.0：README（License 徽章）
- Zerox：README（`maintainFormat` / `extractPerPage` / `maxRetries`）
- SmolDocling：HF 模型卡（DocTags、`cdla-permissive-2.0`）
- OmniDocBench：README（指标、Overall 公式、hybrid matching、MGAM、DPI 变更、依赖）
- UniMERNet：README（CDM 说明）
- Tesseract：`tess3/FAQ-Old.md`（x-height 阈值）、`ImproveQuality.md`（DPI 表述）
- SAHI：`docs/cli.md`（`--overlap_ratio 0.2`）
- PaddleOCR：`configs/rec/PP-OCRv5/PP-OCRv5_mobile_rec.yml`、`deploy/hubserving/ocr_rec/params.py`（`[3,48,320]`）

**论文（arXiv 摘要页 / 全文页原文）**
- Nougat：<https://arxiv.org/abs/2308.13418> · 全文 <https://arxiv.org/html/2308.13418v1>（§5.4 重复与增强、局限性）
- olmOCR：<https://arxiv.org/abs/2502.18443> · olmOCR 2：<https://arxiv.org/abs/2510.19817>
- GOT-OCR2.0：<https://arxiv.org/abs/2409.01704> · 全文 <https://arxiv.org/html/2409.01704v1>
- DeepSeek-OCR：<https://arxiv.org/abs/2510.18234> · 全文 <https://arxiv.org/html/2510.18234v1>
- dots.ocr：<https://arxiv.org/abs/2512.02498>
- OmniDocBench：<https://arxiv.org/abs/2412.07626>
- UniMERNet：<https://arxiv.org/abs/2404.15254> · CDM：<https://arxiv.org/abs/2409.03643>
- MathWriting：<https://arxiv.org/abs/2404.10690>
- SAHI：<https://arxiv.org/abs/2202.06934>
- InternVL 1.5：<https://arxiv.org/abs/2404.16821> · Qwen2-VL：<https://arxiv.org/abs/2409.12191>
- ChartHal：<https://arxiv.org/abs/2509.17481>
- LLMs Cannot Self-Correct Reasoning Yet：<https://arxiv.org/abs/2310.01798>
- Just Ask for Calibration：<https://arxiv.org/abs/2305.14975>
- Self-Consistency：<https://arxiv.org/abs/2203.11171>
- Sequential Enumeration in LLMs：<https://arxiv.org/abs/2512.04727>
- Conformal Abstention（单源待验证）：<https://arxiv.org/abs/2502.06884>
- Docling 技术报告：<https://arxiv.org/html/2408.09869v5>

**仓内引用**
- 姊妹文档：`docs/kb-scan-transcription-research-layout.md`（版面词表、CoVe、SelfCheckGPT、TEI、平铺）
- 证据链字段：`docs/kb-content-conformance-spec.md:180`（`sourceLocator`）
- 现行暂存结构：`knowledge-production/2027-53-transcripts-staged/`（`{"page","heading","text"}` 扁平）
- 我们自己的基线数字（121/106、19 处照写、6 漏 2 多 5 错、切块放大有效、图形脑补）：来自本轮任务说明，**未复核原始文件**
