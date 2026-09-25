# Stage-3 执行报告（端侧稠密小档闭环：bge-small-zh-v1.5 int8 + LiteRT + 分词器对拍）· 2026-09-24/25

> **性质**：Stage-3 全阶段证据留档（用户改判 → 前置核实 → 资产与溯源 → 端侧实现与对拍 → 真机闭环数 → 回退路径与未验证项 → 换大档判据）。
> **生成**：WP4（收尾与推送代理，2026-09-25）。**上游**：用户 2026-09-24 改判（先小档跑通闭环）；Stage-2 离线规格与报告 `docs/kb-stage2-dense-spec.md` / `docs/kb-stage2-report-2026-09-23.md`；判定模板 `docs/kb-vector-topic-decision.md`（§4 形态预案 / §6.2 点 3「立项需用户另裁」）。
> **本阶段性质**：**小档端侧闭环已跑通**（对拍硬门 + 真机金标 + 回归 + 预算 + 回退路径齐备）；**是否换大档（Qwen3-Embedding-0.6B）仍需用户裁定**（§7）。
> **纪律**：金标集（90 条，sha256 `7c004b76…`）**未增未减未改**；预注册线 0.75/0.60 与判分口径**一字未动**；融合参数（α=0.5、域内 min-max、缺失腿给 0、并列按 node_id、RRF k=60）**出数前写死**，判官上调参为零。仲裁口径见 `docs/kb-vector-topic-decision.md` §4/§6 与 Stage-2 规格 §1.2。
> **证据分层**：本报告把「逐字日志行/文件行」与「引 WP 档 2 记录」分开写；§6.2 列出本会话（收尾代理）**重新跑过**的检查与**只复核了落盘证据**的项。`build/` 不入版本库（`.gitignore:14` = `**/build/`），引用其中的 `build/*` 路径只在磁盘上可复算。

---

## 0. 结论摘要（一句话）

词面栈（v1 计数排序 + matched 优先返回形态，真 SQL 主集 0.6444 / MRR 0.5637）之上加**端侧稠密腿**（bge-small-zh-v1.5 int8 + LiteRT，28,932×512 向量随包，**只重排词面召回集**）后，真机金标**主集 0.7444（67/90）/ MRR 0.6109 / 逐章最小 0.4444 / p95 134–148ms（预算 150ms）**，比离线参考 `refFusedD1=0.7333（66/90）` **高 1 命中**、比词面基线 **+10pp**；**编码对拍硬门 ≥0.999 过**（n=290，min 0.99963）；19 例回归 19/19、cross-subject=0、T6/W-4 全绿；**回退是一行常量**（`DenseRecallAssembly.ENABLED = false`）。**弱章不动**（物理·相互作用、化学·铁与金属材料仍是 4/9 = 0.4444，本阶段只承诺主集）——因此 **Stage-2 预注册的「逐章最小 ≥0.60」仍不满足**，换大档不是自动决策项（§7）。

---

## 1. 改判来龙去脉（这一阶段为什么存在）

**时间线（事实）**：

1. **2026-09-22（§3 判定）**：D12 预注册规则被首测触发（词面 0.5222 < 0.90 且 9/10 章 < 0.80）⇒ 开「同层 dense 兜底」议题，**只开议题、不施工**，立项需另一次用户裁定（`docs/kb-vector-topic-decision.md` §1/§3）。
2. **2026-09-23（Stage-2 离线验证）**：离线跑通 dense 质量（Qwen3-0.6B 与 bge-small-zh 两档），**主判据未过**（卡在逐章最小 0.2222 / 0.4444 < 0.60）⇒ 按规格 §1.2 第 3 种归宿 = **不立项**；`docs/kb-vector-topic-decision.md` §6.2 点 3 的「立项需用户另裁」保持不变，端侧一步未做（`docs/kb-stage2-report-2026-09-23.md` §3.4）。
3. **2026-09-24（用户改判 —— 本阶段的授权来源）**：**dense 立项，且先小档跑通闭环**——`bge-small-zh-v1.5 int8 + LiteRT`；**闭环达标后再决定是否换大档**（Qwen3-Embedding-0.6B）。改判记录落在：`docs/kb-vector-topic-decision.md` §3.2 现状段的 2026-09-24 改判段、`docs/kb-stage2-dense-spec.md` §6.2 的「不立项」段指针、`docs/kb-stage2-report-2026-09-23.md` §3.4 的「不立项」段指针（三处均由本阶段补，历史结论一字未改）。
4. **本阶段（Stage-3）做的是什么**：把「小档」从**离线质量数**变成**随包可分的端侧闭环**——自转 int8 模型件 → 转 LiteRT 模型件 → 分词器/编码/融合三处对拍 → 单点接入生产检索 → 真机跑金标与回归 → 留下**一行回退**。

**为什么是「先小档」而不是直接上大档（用户裁定的实质，非本代理推断）**：Stage-2 的账面上，qwen3 模型件比 bge 大 ≈6.3×、向量维度 2×，换来融合主集 +3.3pp，而**两者都没过逐章最小**（`docs/kb-stage2-report-2026-09-23.md` §3.4(b) 表）。先小档 = 用最小代价把「端侧这条路能不能走通」（模型可转、分词可对拍、检索可接入、体积与延迟可接受、失败可回退）验证掉，**质量收益是否够**留在下一轮裁定。

---

## 2. 前置核实（动手前先确认两件事：同科隔离、形态口径）

### 2.1 同科隔离**已经存在**，端侧只需复用（不必新造）

- 生产召回入口本身就按科隔离：`RoomKnowledgeBaseStore.readSubjectKnowledgeRecallCandidates(subject, …)` 首行 `require(subject.isNotBlank())`，SQL 侧 `WHERE feature.subject = ?`（`core/database/.../dao/ProblemOrganizationDao.kt` 的 `searchSubjectKnowledgeRecallCandidates`）。
- 稠密腿的扫描域同样是该科：`OnDeviceDenseRecallReranker.order` 把 `subject.lowercase(Locale.ROOT)` 传给 `DenseVectorAsset.nodeScores(vector, subjectKey)`（`core/data/.../knowledge/dense/DenseRecallAssembly.kt`）——**不新增隔离机制，只复用既有科目键**。
- 向量集与科目无关地按包内原子布局排序，`ids` 即 `knowledgeNodeId`（旁车 `.vec.json` `"idsAreKnowledgeNodeIds": true`，`layoutSource: spec §2.2`）。

### 2.2 形态口径：D1 = 现行生产形，且 D1 与 matched-only **恒等**（断言，不是假设）

- 2026-09-24 起生产返回形态 = **matched 优先**（`RoomKnowledgeBaseStore.kt:119-160` 的 `(matched + parents)`，D1 落地依据见 `docs/kb-stage2-report-2026-09-23.md`）。
- 判分窗口（前 5）**全部落在 matched 侧** ⇒ D1 的主集/逐章/MRR 与 matched-only **逐位相同**——这一步由 `tools/dense_build/stage3_expectation.py` 断言，落盘为 `build/stage3-device-expectation.json` 的 `selfCheck.d1EqualsMatchedOnly: true`。
- **推论（本阶段敢用 matched-only 参考数的依据）**：Stage-2 的离线参考数可以直接当「现行生产形态下的参考数」用；无需为改形态重算一轮。

### 2.3 判分器同口径自证（与 Stage-2 逐位复现）

- `build/stage3-device-expectation.json` 的 `selfCheck.stage2Reproduction`：用**同一份 stage-2 腿 + 向量**重跑新判分器，`D-only-bge` 得 0.6444（58/90）/ MRR 0.505（`ok: true`）、`D-fuse-a0.5-bge` 得 **0.7444（67/90）/ MRR 0.62**（`ok: true`）——与 Stage-2 封存值**逐位相同**（Stage-2 侧记录见 `docs/kb-stage2-report-2026-09-23.md` §3.1 表）。
- 输出侧另有 int8/fp32 对照（`diagnostics.fp32SameProductionLeg`，显式标注**非参考数**）：同一生产词面腿下，稠密腿换回 fp32 离线向量 ⇒ dense-only 0.6444（不变）、fused 0.7333（int8 参考 0.7333）——**int8 量化在本轮质量指标上无可见影响**。

---

## 3. 资产与溯源（模型 / 词表 / 向量 / 词面腿，逐件有哈希）

### 3.1 模型件（两级：离线 ONNX + 随包 LiteRT）

| 件 | 体积 | sha256 | 来源 |
|---|---|---|---|
| `build/dense-model/bge-small-zh-v1.5-fp32.onnx` | 94,858,640 B | `519a2f4601d842126ce5064132fafd887d351d20907f2795864987a19422f6e9` | 基座导出（不入库） |
| `build/dense-model/bge-small-zh-v1.5-int8.onnx` | **23,952,639 B（24.0 MB）** | `4d3b31355a3df4ff923db83bc76181f8581592de10f67502be825f519d2fa213` | **自转**：`onnx-weight-only-int8-per-channel`（int8 权重含嵌入表 3 张 + 27 个 MatMul，保留 8 个激活×激活 MatMul 为 fp32） |
| `core/data/src/main/assets/dense/bge-small-zh-v1.5-int8.tflite` | **62,396,488 B（≈59.5 MiB）** | `015b231580dd850e5107c6991ed36fffabf2b1f6f326134a8b146a5610c672ee` | 由上一行经 `onnx2tf`（独立 venv `build/tflite-venv`）转出，**随包分发** |

- 基座：`BAAI/bge-small-zh-v1.5` rev `7999e1d3359715c523056ef9478215996d62a620`（**MIT，可再分发**）；查询前缀 `为这个句子生成表示以用于检索相关文章：`；池化 CLS + L2 归一（**图内已含**：`Gather(0) → ReduceL2 → Clip → Expand → Div`，输出 `sentence_embedding`）。
- **量化门（≥0.999，marginal 与路线比较）**：`tools/dense_build/model-manifest.json` 记录 `gate.passed: true`——docs min cosine **0.9990096092224121**、queries min **0.9991949796676636**（阈值 0.999）。**未采用的路线**（同文件 `quantizationRouteComparison`，逐条给出实测，供后人省一次试错）：dynamic-perTensor docs min 0.9499 / perChannel 0.9872，均**未过门**。
- **诚实标注（两处偏离，不当已通过）**：
  1. `.vec` 资产侧的量化对拍**不含在本门里**：`bge-small-zh-int8.vec.json` 记 `endToEndCosineMinVsFp32Reference = 0.998890`（**< 0.999**）/ median 0.999282，另一组 `roundtripCosineMinVsInt8Model = 0.999747`。即「资产字节 → 还原向量」比「模型直接输出」多一步 per-vector scale 量化，**该 min 低于 0.999 门**；它不影响真机硬门（对拍的是部署路径本身，§4.2）与质量（§2.3 的 int8/fp32 对照无差）。
  2. LiteRT 侧模型件是 **62.4 MB 而非 24 MB**：`onnx2tf` 把 weight-only int8 的权重**展开成 fp32 常量**（只有嵌入表留 int8）。压回 ~24 MB 的下一步是 TFLite converter 的动态范围量化（`Optimize.DEFAULT`），它会引入**第二次**量化误差，必须重跑 §4.2 的 ≥0.999 门再定（本轮**未做**，见 §7）。
- **转换链的三条实测取舍**（不是推断，`tools/dense_build/README.md` §7）：① 必须先把动态轴冻成 `[1,512]`（`flatbuffer_direct` 在动态图上报 `reshape.cc: num_input_elements != num_output_elements`）；② 不能用 `-tb tf_converter`（GELU 落到 `FlexErf`，端侧 LiteRT 无 Flex delegate，`Invoke` 直接失败）；③ **转换会就地改写输入 ONNX**（本轮踩过：参考件哈希 `4d3b3135…` → `6a795693…`，与冻结 npy 的逐行 cosine 从 1.0 掉到 ~0.99985），故只在 `build/tflite-work/` 的副本上转 + 转后断言源件哈希未变，**被改写的参考件已逐字节复原**（用 `export_bge_int8.py::quantize_weights_only(fp32, out)` 重生成，哈希回 `4d3b3135…`、与 `int8-queries.npy` 逐行 cosine = 1.0）。

### 3.2 词表（分词器对拍的参考侧）

| 件 | 体积 | sha256 |
|---|---|---|
| `tools/dense_build/vocab/bge-small-zh-v1.5-vocab.txt` | 109,540 B（21,128 行） | `45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c` |
| `tools/dense_build/vocab/bge-small-zh-v1.5-tokenizer.json` | — | `48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26` |
| 随包副本 `core/data/src/main/resources/knowledge/dense/bge-small-zh-v1.5-vocab.txt` | 109,540 B | 同上（`DenseTokenizerParityTest` 断言「随包 == 冻结副本 == 记录 sha」） |

### 3.3 向量资产（随包，17.2 MB）

- `core/data/src/main/resources/knowledge/dense/bge-small-zh-int8.vec`：**17,169,237 B（16.4 MiB）**，sha256 `7666ab2cc8ffe3d9e72d3dc1979b793dbcc7ea342afe57f0979ed13f63277225`。
- 布局（旁车 `.vec.json`）：`magic SMBV` + `header(24B)` + ids block（u32 len + utf8/行）+ int8 矩阵（row-major）+ f32 scales（115,728 B）；`scale[r] = max|v_r|/127`，还原后再算余弦。
- **规模 28,932 × 512**：`atomicNodes 3572` + `canonicalVectors 3572` + `aliasVectors 25360`，**`includeTopics: false`**（spec §2.2，与离线臂 `stage2_common.build_vector_layout` 同序）。
- 陈旧性门（`tools/ci/run_kb_checks.py` 的 `dense` 一节 + `tools/dense_build/check_asset.py`）：旁车记录的**包哈希 / 词表哈希 / `.vec` 哈希 / 行数维度**与当前仓库逐个比对，另比 ids 与当前包的原子布局；正反两侧在 `tools/tests/test_dense_asset_gate.py`（6 例：正侧真资产绿，反侧改包/改词表/改旁车行数/绕哈希改包/缺旁车各自必红）。

### 3.4 生产词面腿（判分与对拍的第三条腿）

- `build/production-lexical-leg.tsv`：2,685,373 B、**32,769 行 + 表头**，sha256 `53d193308bb9ce98ff2285ef641f35b87efe8709a90bd679675ee2dbf72e919a`；分数 = `COUNT(DISTINCT feature.search_feature)`，行序 = 生产 SQL 排序（读回不重排）。
- 导出测试 `ProductionLexicalLegExportTest`（JVM）+ 回读重算：**58/90** 与既有 B 段（`build/golden-jvm-metrics.txt` 的 `B-route-mirror(v1-bare-B5, matched-first)`：`Recall@5(主集)=0.6444 (58/90)`、`MRR=0.5637`）**逐行一致**。
- 包与参考数身份：`moe-2025-four-subjects-v1.json` sha256 `2973662a…`；金标 `tools/kb_coverage/tables/golden_queries_v1.json` sha256 `7c004b76…`（90 条 / 10 章，运行时复核）。

### 3.5 工具链（都可重跑）

`tools/dense_build/`：`export_bge_int8.py`（基座→fp32/int8 ONNX，含量化门与路线比较）、`pack_dense_asset.py`（→ `.vec` + 旁车）、`check_asset.py`（陈旧性门）、`dense_asset.py`（int8 量化口径，Kotlin 侧同口径）、`stage3_expectation.py`（**判分权威实现**，含 `minmax()` / `best_first()`）、`stage3_device_sim.py`（端侧口径模拟）、`freeze_onnx_static.py` + `patch_litert_api_aar.py`（tflite 链）、`gen_tokenizer_fixture.py` / `gen_dense_device_fixture.py` / `gen_device_parity_fixture.py`（三套 fixture 生成）。判分器与 Stage-2 的 `build/stage2-dense-work/stage2_score.py` **同口径**（§2.3 逐位复现自证）。

---

## 4. 端侧实现与对拍

### 4.1 分词器（Kotlin WordPiece）

- 实现：`core/data/.../knowledge/dense/DenseTokenizer.kt`（normalizer（clean_text/handle_chinese_chars/lowercase）+ BertPreTokenizer + WordPiece `##` + `[CLS]/[SEP]` 模板 + `max_length=512`）。
- 对拍：`DenseTokenizerParityTest`（JVM，**6 例全绿**）——判据与条数写死在测试里：`query 90 / surface 200 / edge 18 / stage 25`，**333/333 与 Python 参考（transformers 5.17.0 BertTokenizer）逐 token id 一致**；另有 68 行逐阶段探针（normalizer/pre-tokenizer 逐阶段比对）；随包词表字节一致 + sha 一致；边界（空串/纯空白 → `[101,102]`；截断保留单个 `[SEP]`；`encodePadded` 右 PAD 且窗口过小**抛错不静默截断**）。
- fixture 与自证：`core/data/src/test/resources/dense/tokenizer-parity-summary.json`（参考实现/blake 后端版本/规范化链/批式-单条等价断言 `batchPaddingEqualsSingle: true`；诚实项写明空串在参考实现里直接 TypeError、故 fixture 不含空串，端侧只承诺**不崩**）。

### 4.2 编码器（真机硬门 ≥0.999）

- 实现：`LiteRtDenseQueryEncoder`（LiteRT `Interpreter`，XNNPACK 开、2 线程、定长 512；从 assets **mmap** 打开，失败退回直接缓冲；输出只做幂等 L2 归一）。
- **修复（本阶段实测发现）**：把查询喂成 **1D int64** 张量会让模型输出错向量、随后 **native SIGSEGV**；改成 `[1,512]` 后消失。诊断段（`DenseEncoderParityInstrumentedTest` 内保留）逐条打点 `mmapCos / directCos / mmapEqualsDirect` 就是这个修复的唯一实证。
- 对拍（fixture = 90 条金标题面 + 200 条抽样节点文本，参考向量 = 冻结 `build/dense-model/{int8-queries,int8-docs}.npy`，assets 带 sha256 封存）：

| 项 | 实测 | 来源 |
|---|---|---|
| n=290 逐条 cosine | **min 0.9996304 / median 0.9997844 / p95 0.9998405 / mean 0.9997796**（阈值 0.999，**不达标 0 条**） | **本会话（WP4）重跑**，logcat 逐字：`cosine（端侧 vs Python 参考）：n=290 min=0.9996304005089798 median=0.9997843946969996 p95=0.9998404744690854 mean=0.9997796541580073`、`判据：逐条 cosine ≥ 0.999；不达标的条数 = 0`；分腿：query 90 条 min 0.9997256 / surface 200 条 min 0.9996304 |
| fixture 封存复核 | `fixture: cases=290 vectorsFileSha256=3d15902d3e1a7a1633b4375010beb1d7dfa5d573b5e02b26ccf38258c05644c0 (assets 复核)` | 同上 |
| 宿主旁证（同批文本，tflite vs int8 ONNX） | min 0.99965 / median 0.99979 | `tools/dense_build/README.md` §7.1 |
| 单条编码耗时（含分词 + 推理 + 归一） | **p50 74ms / p95 82ms / max 94ms**（WP3 首测 71/76/91，同量级） | 本会话重跑：`单条编码耗时：p50=73984us p95=82044us max=94495us` |
| mmap vs 直接缓冲（1D int64 崩溃修复的回归） | `diag#0 mmapCos=0.9998558464570689 directCos=0.9998558464570689 mmapEqualsDirect=true`（前 5 条同） | 本会话重跑（此前 1D 输入时 mmap 路首条 cos≈0.29、第 2 条 SIGSEGV） |

### 4.3 融合与接入（只重排，不改成员）

- 融合口径（`DenseFusion.kt`）：`score = α·dense_norm + (1-α)·lex_norm`，**α=0.5 写死**、**域内 min-max**、某腿无分 ⇒ 给 0 **且不参与**该腿 min-max、并列按 `node_id` 升序；两腿 min-max **域不同**（稠密腿 = 候选域内**有向量的节点**，词面腿 = **有词面分的节点**）——与 `stage3_expectation.py` 的权威实现同语义，对拍用例是**调用那两个函数现算生成**的（`dense-fusion-reference.txt`），不是两套口径互相印证。用 `Double` 而非 `Float` 做归一化与加权（并列处的名次要和 Python float 一致）。
- 单点接入：`RoomKnowledgeBaseStore.readSubjectKnowledgeRecallCandidates(..., queryText)` 只改 matched 的**次序**（`rerankMatchOrder`）——候选域 = 词面召回集本身（subject 作用域、可信过滤、limit 上限都不动），返回长度语义不变（≤limit 个 matched + 其父节点）。两侧各有一层兜底：`core:data` 实现把任何异常收敛成 `null`（`CancellationException` 继续上抛），store 侧再 try/catch 一层（`DenseRecallOrdering.orderOrLexical` 校验「重排结果必须是入参的**排列**」，不是就整体丢弃）。
- **测试侧堵住静默降级**（本阶段修复，否则量到的是旧基线）：`GoldenRetrievalInstrumentedTest` 现在自己装配 `DenseRecallAssembly.reranker(context)` 并在装配返回 `null` 时**直接红**（`requireNotNull`），另加一次「腿是活的」探测（第一题真候选跑一次重排，返回 `null` 就红）；预热轮带 `queryText`，把模型（62MB）+ 资产（17MB）的**一次性加载**挤出 p95 样本。不装配时同命令只量到纯词面 0.6444 / p95 18ms（`build/devtest-golden.log` 前次运行）。
- **与离线参考的已知差异（如实记录）**：离线判分器的排序域是**该科全部节点**（含词面腿无分的原子节点），端侧按任务书只重排**词面召回集**（limit=512）⇒ 实测差异 = 90 条里 **1 条**的 top-5 成员、命中数 **0**（`tools/dense_build/stage3_device_sim.py`；端侧顺序参考钉在 `core/data/src/test/resources/dense/dense-device-order-reference.txt`）。

---

## 5. 真机闭环证据（设备 AVD `test_device` / emulator-5554，API 34 x86_64）

**设备与异常**：userdebug/dev-keys，`/data` 余 4.4G、RAM 2.4G；测试期间模拟器**进程级自崩一次**（金标首轮跑到 91s 时 adb 掉设备），按协议冷启 `-wipe-data` 后重跑取数——该次判为 **infra，非 code**（引 WP3 档 2）。

### 5.1 金标真 SQL 融合路由（对拍参考数）

命令：`gradlew :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=…GoldenRetrievalInstrumentedTest`；XML `tests=1 failures=0`（`build/devtest-golden.log`，`BUILD SUCCESSFUL in 4m 30s`；**本会话 WP4 重跑** `build/stage3-recheck-golden.log`，`BUILD SUCCESSFUL in 3m 57s`、EXIT=0）。

logcat 逐字行（WP3 首跑 `build/golden-run.log:17930-17934`，09-24 17:15:07；**本会话重跑** 09-24 18:33:15 设备时钟，逐字如下）：

```
route=B-bare->top5(v1生产形状, matched-first) indexVersion=1 cases=90 samplesPerQuery=5 p95=148ms p50=115ms max=3507 budget=150ms overBudgetSamples=20
主集 Recall@5(全样本命中) = 0.7444444444444445 (67/90)
主集 Recall@5(任一样本命中) = 0.7444444444444445
MRR(最优名次) = 0.6109259259259261
golden-assets sha256=7c004b763bdd49556e11ff1c9500c9461b09a7383b77f230fa8fd35754e6ae39（应与仓库根封存一致）
```

（WP3 首跑的对应行为 `p95=134ms p50=113ms max=2664 budget=150ms overBudgetSamples=5`——两次**质量数逐位相同**，只有墙钟 p95 与超预算样本数波动，同族于既有 p95 波动注记。）

| 指标 | 实测（融合，真 SQL） | 参考 `refFusedD1` | 差 |
|---|---|---|---|
| 主集 Recall@5 | **0.7444（67/90）** | 0.7333（66/90） | **+1 命中** |
| 逐章最小 | **0.4444（4/9）** | 0.3333 | +0.1111 |
| MRR | **0.6109** | 0.6035 | +0.0074 |
| p95 | **148ms**（本会话重跑；WP3 首跑 134ms；p50 115ms；budget 150ms，overBudget 20/450） | —（参考文件只给质量数） | 预算内 |

- 逐章 10 值（两次运行**逐项相同**）：生物学必修2 8/9、化学必修一·第三章 **4/9**、化学必修二·第五章 9/9、第六章 9/9、数学必修一·第三章 5/9、数学选必一 8/9、数学选必二 5/9、物理必修一·第一章 8/9、物理必修一·第三章 **4/9**、物理必修三 7/9。**两个弱章仍是 4/9**（本阶段不承诺改善）。
- MISS 23 例（`build/golden-run.log:17965-17968` / 本会话 logcat 同批题面留档）；**稠密腿确实实跑**：logcat `DenseRecall: dense rerank on subject=` **474 行（WP3）/ 490 行（本会话）**。
- 对照：**未装配稠密腿**时同文件同命令只量到纯词面 0.6444 / p95 18ms（`build/devtest-golden.log` 前次；与 §3.4 的词面基线和 JVM 镜像一致）。

### 5.2 回归（19 例 / cross-subject / 墙钟）

命令：同 Gradle 命令，`class=…KnowledgeContextRetrievalInstrumentedTest`；XML `tests=2 failures=0`（`build/devtest-kcr.log`，`BUILD SUCCESSFUL in 6m 39s`；**本会话 WP4 重跑** `build/stage3-recheck-kcr.log`，`BUILD SUCCESSFUL in 7m 5s`、EXIT=0；落盘 XML `tests=2 failures=0 errors=0 skipped=0 time=418.428s`）。

- `bundled-knowledge-recall regression: hit=19/19, cross-subject=0`（WP3：`build/kcr-run.log:1457`；本会话重跑同一行，`hit=19/19, cross-subject=0`）；
- `knowledge-room-recall benchmark: 20000 points + 19999 relations … p95=38ms`（预算 150ms；EXPLAIN 索引守卫与选中语义断言全过，`:1101`）——**本会话重跑 p95=41ms**（runs 24 次，同一条 EXPLAIN 计划）；
- `mastery-room-read benchmark: 20000 states … p95=127ms`（预算 250ms，`:1103`）——**本会话重跑 p95=137ms**。
- 新路由（含编码 + 扫描 + 融合）p95 = §5.1 的 **134ms（WP3）/ 148ms（本会话）均 < 150ms** ⇒ 按任务书**无需定义新预算**，实测值入档。

### 5.3 代号通道与内容更新债（T6 / W-4）

一次 Gradle 调用同跑三类（`-P…class=…RoomModelTaskT6MasteryInstrumentedTest,…KnowledgeContentUpdateDrillInstrumentedTest,…BundledContentReconciliationInstrumentedTest`）：XML **tests=5 failures=0 errors=0 skipped=0**（`build/devtest-t6-w4.log`，`BUILD SUCCESSFUL in 1m 16s`）；T6 三例：`aLegalCodeWriteRunsThroughTheGateAndIsRejectedAsUnanchoredNotUnAuthorized` 0.519s / `aNoQuestionRoundWriteReachesTheRunnerAndTheGateDecides` 0.205s / `aFabricatedCodeIsStructurallyRefusedBeforeItReachesTheRunner` 0.229s（引 WP3 档 2 XML 记录，见 §6.2）。

### 5.4 一次性开销与冷启

- 稠密首次检索一次性开销（`DenseFirstUseCostInstrumentedTest`，XML `tests=1 failures=0`，`build/devtest-firstuse.log`）：`openEncoder 34ms / firstOrder 391ms / secondOrder（稳态）93ms`（`tools/dense_build/README.md` §7.1）——首次多付的是模型加载 + 资产读取，稳态回到 93ms。
- **冷启基线（R4a 同协议）**：WP3 档 2 记为 verified，但**其原始 logcat 未落在本仓库可见位置**（本会话只找到协议定义 `.jez/artifacts/r4a-startup-baseline-2026-09-22.md` 与 R4a 当时的 before/after 数，未见 Stage-3 复测件）⇒ **本报告不引具体冷启/PSS 数值**，列为未验证项（§6.1）。

### 5.5 APK 体积（三块明细）

`APK = app/build/outputs/apk/localFirst/debug/app-localFirst-debug.apk`，**251,815,028 B**（本会话实测，含既有 mlkit OCR / filament 等大头，非稠密腿引入）：

| 块 | 体积 | 说明 |
|---|---|---|
| 模型件 `assets/dense/bge-small-zh-v1.5-int8.tflite` | **62,396,488 B（59.5 MiB）** | APK 内 **stored**（未压缩） |
| LiteRT 运行时（3 ABI 合计） | **25,930,744 B（24.7 MiB）** | arm64-v8a 8,750,040（`libLiteRt.so` 5,392,000 + `libLiteRtClGlAccelerator.so` 2,827,264 + `liblitert_jni.so` 530,776）/ armeabi-v7a 5,848,496 / x86_64 11,332,208 |
| 向量资产 `knowledge/dense/bge-small-zh-int8.vec` | **17,169,237 B 原始 → 10,516,990 B 压缩** | 词表另 109,540 → 54,707 B；旁车 3,402 → 1,737 B |
| 三块合计（压缩口径） | **≈ 98.9 MB** | `tools/dense_build/README.md` §7 的账（62.4 + 25.9 + 10.6） |

**与上一条已推提交的 APK 总增量 = 未验证**（WP3 尝试做过「有/无稠密腿」的两包对照：`build/apk-nodense-build.log` / `build/apk-dense-build2.log`，但 `apk-baseline-build.log` 那次基线构建是 `BUILD FAILED in 5s`，且对照包未留在磁盘上）⇒ 只能给**分块体积**，不能给「相比上一版 +X MB」（§6.1）。

---

## 6. 回退路径与未验证项

### 6.1 回退路径（一行 / 一个常量，均已在本阶段实测过的形态上）

| 级别 | 动作 | 效果 |
|---|---|---|
| **一级（总开关）** | `DenseRecallAssembly.ENABLED = false`（`core/data/.../dense/DenseRecallAssembly.kt`） | 装配返回 `null` ⇒ 检索路径**回到纯词面**（Stage-1/2 行为），不改任何其它代码 |
| 二级（装配点） | `StudyDatabaseFactory.open(..., denseRerank = null)`（装配处 `SmartMistakeBookApplication`） | 同上，且不加载分词器/资产/模型 |
| 三级（调用方） | 不传 `queryText`（`readSubjectKnowledgeRecallCandidates` 的默认参数即 `null`） | 该调用方退回纯词面（`MASTERY_READ` 聚焦解析本就 `null`，D7 冻结其形态） |
| 四级（资产） | 移除模型件 ⇒ 加载失败 ⇒ `Log.w` + 纯词面 | 这是**设计内的静默回退**，不是回退手段；正是因为它是静默的，§4.2 的硬门与 §4.3 的"腿是活的"断言才必须在测试里 |

不变量（代码层已钉）：稠密腿**不可能**改变召回集合的成员与长度（`DenseRecallOrdering` 的排列校验）、**不可能**改变 subject 作用域（域 = 词面召回集）、失败**不可能**让主检索抛错（两层 try/catch，`CancellationException` 除外）。

### 6.2 本会话（收尾代理 WP4）重新跑过 / 只复核落盘证据的项

**本会话实际执行（命令与结果）**：

| 检查 | 命令 | 结果 |
|---|---|---|
| JVM 单测 `:core:data` | `./gradlew.bat :core:data:testDebugUnitTest --rerun --console=plain` | `BUILD SUCCESSFUL in 1m 20s`、EXIT=0；落盘 **72 个 XML / 527 tests / 0 failures / 0 errors / 0 skipped**（含 `DenseTokenizerParityTest` 6、`DenseFusionTest` 3、`DenseVectorAssetTest` 5、`DenseRecallRerankerTest` 5、`ProductionLexicalLegExportTest`） |
| JVM 单测 `:core:database` / `:core:model` | 同上一次调用 + `./gradlew.bat :core:model:test --rerun` | `:core:database` 该次为 UP-TO-DATE（其输入本次未变），落盘 **15 个 XML / 75 tests / 0 failures**（含 `DenseRecallOrderingTest` 6）；`:core:model` 重跑 **38 个 XML / 374 tests / 0 failures / 0 errors** |
| dense 资产陈旧性门（正反 6 例） | `python tools/tests/test_dense_asset_gate.py` | `Ran 6 tests in 0.424s` / `OK`（exit 0） |
| kb checks（含 `dense` 一节） | `python tools/ci/run_kb_checks.py --root build/kb-staging` | `gates / consistency / roundtrip / manifest / dense` **五项全 OK**，exit 0 |
| 金标真 SQL 融合路由（真机） | `gradlew :core:data:connectedDebugAndroidTest -P…class=…GoldenRetrievalInstrumentedTest` | `Finished 1 tests` / 0 failed / `BUILD SUCCESSFUL in 3m 57s`；主集 **0.7444（67/90）**、MRR 0.6109、逐章最小 0.4444、p95 **148ms**（与 WP3 质量数逐位相同） |
| 编码对拍硬门（真机） | 同命令 `class=…DenseEncoderParityInstrumentedTest` | `Finished 1 tests` / 0 failed；n=290 **min 0.9996304 / median 0.9997844 / p95 0.9998405**，不达标 0 条 |
| 19 例回归 / 墙钟腿（真机） | 同命令 `class=…KnowledgeContextRetrievalInstrumentedTest` | XML `tests=2 failures=0`；`hit=19/19, cross-subject=0`；KCR p95 **41ms**、mastery p95 **137ms** |

**只复核了落盘证据（未在本会话重跑）**：T6/W-4 五例的 XML（`build/devtest-t6-w4.log` 只留 Gradle 汇总行 `Finished 5 tests`，逐用例 XML 已被后一次运行覆盖，具体用例耗时引 WP3 档 2）；APK 总增量（§5.5）；冷启/PSS（§5.4）。

### 6.3 未验证项（不得当已通过）

| # | 项 | 状态 |
|---|---|---|
| 1 | **APK 总增量**（vs 上一条已推提交） | **未验证**（基线构建 `BUILD FAILED in 5s`，对照包未留存）；只有 §5.5 的分块体积 |
| 2 | **冷启 / PSS（R4a 同协议）** | WP3 记 verified，但原始 logcat 本会话不可见 ⇒ **本报告不给数** |
| 3 | 弱章（物理·相互作用 / 化学·铁与金属材料） | **未改善**（融合 4/9，与词面腿同）——本阶段明示只承诺主集 |
| 4 | Stage-2 预注册主判据（主集 ≥0.75 **且** 逐章最小 ≥0.60） | **小档仍未过**：融合主集 0.7444 < 0.75、逐章最小 0.4444 < 0.60（大档 0.7778 / 0.2222 同样未过）——本阶段**没有**改判据、没有改阈值 |
| 5 | `.vec` 资产侧量化 min cosine | 0.998890 **< 0.999**（未设门）；真机硬门走的是部署路径（≥0.999 过） |
| 6 | 模型件 62.4 MB（非 24 MB） | 已知代价（onnx2tf 展开 int8 权重），压回 24 MB 需动态范围量化 + 重跑 ≥0.999 门（未做） |
| 7 | RRF k=60 对照臂（真机） / α 敏感性（真机） | **未在端侧测**（Stage-2 离线出过数；参数按纪律写死不调） |
| 8 | 90 条金标对 P@5 的方差风险（9 条/章） | 仍在（审计 §2.3-3）——逐章最小这条判据的波动敏感度未量化 |
| 9 | LiteRT 16KB 页对齐（Android 15+ 设备） | 本机为 API 34；页对齐合规性本轮**未复核**（Stage-0 的"均已实测合规"是单一来源待验证） |

---

## 7. 换大档判据（小档闭环是否达标 → 大档 = 换三处 + 重测一轮，需用户裁定）

### 7.1 小档闭环达标清单（逐项给实测值，不替代预注册判据）

| # | 闭环判据 | 来源 | 实测 | 结论 |
|---|---|---|---|---|
| 1 | 端侧编码 vs Python 参考 **min cosine ≥ 0.999** | 任务书写死的硬门 | n=290 min **0.99963**（query 0.99973 / surface 0.99963） | **达标** |
| 2 | 真机金标融合路由 **≥ 词面基线且 ≥ 参考数** | 用户 2026-09-24「只承诺主集改善」 | 0.7444（67/90）≥ 基线 0.6444、≥ 参考 0.7333 | **达标（主集）** |
| 3 | 回归不破：19 例 19/19、cross-subject=0、T6/W-4 全绿 | 既有门 | 19/19、0、5/5 | **达标** |
| 4 | 墙钟在现行预算内（150ms，不重定标） | KD-2 族门 | 真机 p95 **134ms / 148ms**（WP3 / 本会话两次运行） | **达标** |
| 5 | 失败可回退且是一行 | 机制要求 | `ENABLED = false`（+ 两层兜底 + 排列守卫） | **达标** |
| 6 | 体积与首次开销已量化 | 立项须有真机数 | 三块 ≈98.9 MB（压缩口径）；首用 391ms / 稳态 93ms | **达标（量化项）** |
| 7 | 弱章改善 | **不在本阶段承诺** | 两弱章仍 4/9 | **不达标（明示不承诺）** |
| 8 | Stage-2 预注册主判据 0.75/0.60 | Stage-2 规格 §1.2（**判据本身不动**） | 0.7444 / 0.4444 | **不达标**（第 7/8 项是大档决策的真问题） |

**读法（写给裁定人）**：第 1–6 项 = 「端侧这条路的工程闭环」成立（模型可转、分词/编码可对拍、检索可接入、延迟与体积已量化、失败可回退）。第 7–8 项 = 「质量是否够」**尚未成立**，而它们不是换大档能自动解决的：大档（Qwen3-Embedding-0.6B）在 Stage-2 的实测是**融合 0.7778 / 单路 0.7889（主集过了 0.75）、逐章最小 0.2222 / 0.4444（仍远低于 0.60）**，且共同瓶颈章与词面腿同（`phys 相互作用`）——**换大档买到的仍是主集，不是弱章**。

### 7.2 若裁定换大档：换三处 + 重测一轮（本代理的建议形态，供裁定）

| # | 换什么 | 具体 | 已知代价 / 未验证 |
|---|---|---|---|
| 1 | **换模型件** | Qwen3-Embedding-0.6B（rev `97b0c614…`，**apache-2.0**，两通道互证见 Stage-2 报告 §3.2）×1024 维；**必须重走** ONNX 导出 → `onnx2tf` 转 LiteRT（0.6B 的转换可达性 **未验证**：Stage-2 规格 §5 记 `Qwen3ForCausalLM` 系导出路径成熟度未验证；本轮 bge 的 62.4MB 教训是 int8 权重会被展开成 fp32） | 模型件体积量级 ~6.3× bge；端侧延迟未测 |
| 2 | **换向量资产** | 28,932 × 1024：fp32 ≈113 MiB / int8 ≈28.3 MiB（Stage-2 报告 §3.4 表）→ 重跑 `pack_dense_asset.py` + 旁车 + 陈旧性门 | APK 增量（未验证项 §6.3-1 会变成硬条件） |
| 3 | **换预算** | 三处预算都要重定标并**出数前写死**：① p95 门（现 150ms，含编码 71ms + 扫描 17.3MB 资产的成本）② APK/分发预算（三块 98.9 MB 是**小档**的账）③ 冷启/PSS 预算（R4a 协议，需先补 §6.3-2 的测量） | 重定标必须在跑数前完成，不许出数后调 |
| 4 | **重测一轮** | 对拍硬门 ≥0.999（新 fixture）→ 金标真 SQL 融合路由（对拍新的参考数）→ 19 例回归 + cross-subject → p95/冷启/PSS → APK 三块 → T6/W-4 | 一轮的完整清单与命令见 §5；参考数须先用 `stage3_expectation.py` 生成并冻结 |

**建议（不代替裁定）**：若目标是**弱章**，换大档不是对症的下一步（两档弱章同）；对症的方向在 Stage-2 §3.3 的归因里已给出（排序深度 + 融合口径，α=0.7 诊断回到 0.8222，但那属**诊断段、不得据此调参**）。若目标是「主集再上一档且分发可接受」，先做 §7.2 第 3 项里最便宜的那件——把模型件从 62.4 MB 用**动态范围量化**压回 ~24 MB 并重跑 ≥0.999 门（本轮未做，代价最小、不需要换模型）。

> **指针（2026-09-25 补，本报告历史结论一字未改）**：换大档**已于 2026-09-25 执行并交裁**——本节的"Qwen3-Embedding-0.6B"
> 已被 Stage-5 的选型取代为 **`bge-base-zh-v1.5`（同族同接口，768 维）**。落地判据（写死）为
> ①编码器对拍 ≥0.999 **且** ②量化模型金标融合主集 ≥0.7444；实测**①不过**（docs **0.998638**，差 0.00136）、②过（**0.7889（71/90）**）
> ⇒ **未换档、随包侧保持小档原样**（回退清单 0 个文件），延迟硬线（≤213ms）因此**未测**。
> 全阶段证据、换件清单、回退复核与 UNVERIFIED 见 **`docs/kb-stage5-report-2026-09-25.md`**；
> 可复算的生成链与档位参数化见 `tools/dense_build/README.md` §8。**本节 §7.1/§7.2 的判据与表一律不动**（历史留档）。

---

## 8. 本阶段提交范围（收尾代理登记）

- 提交清单 = `.wf-manifest-stage3.txt` 全路径 ∩ `git status` 实际变更；显式排除 `.wf-manifest-stage3.txt` 自身、`tools/kb_build/tables/chapter_map.csv`（另一会话飞行改动）、`.agent_*/out//tmp/` 与清单外脏文件。
- **被 gitignore 挡下的项**：`build/*` 全部（`.gitignore:14` = `**/build/`）——本阶段全部证据产物（`stage3-device-expectation.json`、`production-lexical-leg.tsv`、各 `devtest-*.log` / `*-run.log`）只在磁盘，文档引用的 sha256/日志行需按命令重跑才能复算（仓库既有约定，Stage-1/2 同）。
- **本阶段入库的资产两件**（体积大，随包分发）：`core/data/src/main/assets/dense/bge-small-zh-v1.5-int8.tflite`（62.4 MB）与 `core/data/src/main/resources/knowledge/dense/bge-small-zh-int8.vec`（17.2 MB）。
- 生产代码改动 = **单点接入**（`RoomKnowledgeBaseStore.rerankMatchOrder` + DAO 一条查询返回词面分 + 装配）；其余为 `core:data` 的 `knowledge/dense/*` 新实现、两个端口文件、测试侧装配与钉数、工具链（`tools/dense_build/*`、`tools/ci/run_kb_checks.py`、`tools/tests/test_dense_asset_gate.py`）与四份文档（本报告 + `docs/kb-vector-topic-decision.md` / `docs/kb-stage2-dense-spec.md` / `docs/kb-stage2-report-2026-09-23.md` 的改判指针）。
- 金标本阶段**未增未减未改**：`tools/kb_coverage/tables/golden_queries_v1.json` sha256 `7c004b76…`（运行时与文档三处一致）。
