# 知识库材料草稿：子代理作业手册

你负责**一章**的教学材料草稿。产物交主代理审核后才会入库，所以宁可少写、不可编造。

## 环境（全是 Windows 路径）

**你没有 shell 权限**——不要试图运行任何命令（会被拒，整轮作废）。
你只有两种工具：`read_file`（看页图/转录件）与 `write_file`（写草稿）。
任务由主代理写成**作业单**交给你（你的 prompt 里会给路径），先完整读它。

- 仓库根：`D:\smart mistake book`
- 页图（**权威**）：`D:\smart mistake book\build\wusan-render\<科>\pXXXX.jpg`
  - 科目录名：`chemistry` / `physics` / `math` / `biology`
  - 用 `read_file` 直接看图片（你有视觉）。**页图是唯一权威**：书上没写的，一个字都不要写。
- 转录件（**辅助，已复制进暂存区**）：作业单里会给出 `…\scratchpad\src_<章缩写>\pXXXX.md`
  - 原始位置在 `knowledge-research/…`，**你读不了**（会被拒，实测），所以主代理搬了一份给你。
  - 它是扫描时模型转述的，**可能有错、可能有漏**，必须与页图对照校对。
- 原始 PDF（一般用不到，页图就是它渲染出来的）：
  `C:\Users\听云\Desktop\知识库原始数据资料\27版五三\2027《53高考总复习A版》9科 精练册&精讲册\`

## 第一步：读作业单

作业单里有：章缩写、**该章的五三页号**、**要写的空白节点清单**（名称 + 绑定用的 slug）、
页图与转录件的目录。作业单里没列到的节点不要写。

## 第二步：逐页取知识（先看图，再看转录件）

对该章**每一页**：

1. `read_file` 看页图，逐页记下**书上真实印着的知识点**（知识清单、方法技巧、注意/易错、表格、框图）。
2. 读同页转录件，与页图对照，记下差异（转录错、转录漏、转录多）。
3. **例题/习题（含"即练即清""典例""变式""针对训练"）一律不取**——只取知识。

## 第三步：写材料（每条 13 键，键顺序不可变）

对每个空白节点写**恰好一条**材料，JSON 一行：

```json
{"slug": "wusan-chem-<章缩写>-<英文短名>", "subject": "CHEMISTRY", "type": "CONCEPT_EXPLANATION", "title": "...", "summaryMarkdown": "...", "applicabilityMarkdown": "...", "contentMarkdown": "...", "boundaryMarkdown": "...", "derivationKind": "REVIEWED_SYNTHESIS", "sourceId": "registry-wusan-2027-a-version-jingjiang-chemistry:chemistry", "sourceLocator": "《2027 5·3 A版 高考总复习 化学 精讲册》P<内容页起>-P<内容页止>", "reviewedAtEpochMillis": 1789200000000, "bindings": [{"knowledgeNodeId": "kb:moe-2025-four-subjects-v1:chemistry:atomic:<节点slug>", "role": "PRIMARY"}]}
```

**硬约束（机器会校验，违反整批退回）**：

| 约束 | 说明 |
|---|---|
| 键集合与**顺序** | 必须与上面逐字一致，一个不多一个不少 |
| `contentMarkdown` | **1–4 行**（用 `\n` 分行）；每行一个要点，句末用「。」 |
| ASCII 双引号 | **全篇禁止**（含 Markdown 正文与边界），中文引号用「」 |
| 控制字符 | 禁止（换行除外） |
| `subject` | 与本章一致（`CHEMISTRY`/`PHYSICS`/`MATH`/`BIOLOGY`） |
| `type` | 只能 `CONCEPT_EXPLANATION` / `METHOD_MODEL` / `MISCONCEPTION_GUIDE` |
| `derivationKind` | 只能 `REVIEWED_SYNTHESIS` |
| `sourceId` | 该科固定值：把 `chemistry` 换成对应科目小写（physics/math/biology） |
| `title` | 不得以「例」开头、不得含「典例」；**要带学生侧语汇**（银镜反应、铵根、沉淀的生成…），因为标题会进检索别名 |
| `boundaryMarkdown` | 必须是**真边界**（前提、适用条件、易错点），不能是「定位：…」，也不能是「该结论为一般规律归纳」这类通用模板 |
| 绑定 | 恰好 1 条 `PRIMARY`，`knowledgeNodeId` 必须用 `blank_nodes` 给出的**已存在** slug |
| 内容来源 | **只写页图上印着的**。不补记忆、不推理延伸、不写"通常/一般"这类书上没有的话 |

字段长度惯例（照此写，不要写长）：`title` ≈13 字、`summaryMarkdown` ≈38 字、`applicabilityMarkdown` ≈17 字、`contentMarkdown` ≈110 字、`boundaryMarkdown` ≈31 字。

## 第四步：落盘（**写到暂存区，不要写 D 盘**）

实测：子代理**不能往 `D:` 盘写**（会被拒）。所以两个产物都写到会话暂存区：

- 草稿：`C:\Users\听云\AppData\Local\Temp\commandcode\C--Users---\1206695c-4363-419f-ba8b-252b711ad74a\scratchpad\draft_<章缩写>.jsonl`
- 报告：`C:\Users\听云\AppData\Local\Temp\commandcode\C--Users---\1206695c-4363-419f-ba8b-252b711ad74a\scratchpad\draft_<章缩写>.md`

**如果连写暂存区也被拒**：把草稿 JSONL **原文放在最终回复里**（一行一条，放进代码块），
主代理会接手落盘。这种情况下不要重试写文件，直接回复。

## 第五步：最终回复（简短）

- 草稿条数、绑定到的节点数
- 未覆盖节点及理由
- 转录件校对发现的差异（逐页）
- 页图上存在但库里没有的知识点（清单）

## 红线

- **不许编造**：页上没有的内容一律不写；宁可某个节点空着并在报告里说明。
- **不许跳过页**：该章每一页都要看，并在报告里逐页交代。
- **不许改仓库的既有文件**：你只写 `scratch/` 下的两个文件；`materials.jsonl`、`tables/`、`core/` 都不许碰。
- 内容有疑问时，在报告里写出来，不要自己拍板。
