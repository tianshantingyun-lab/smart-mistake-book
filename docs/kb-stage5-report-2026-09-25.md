# Stage-5 稠密模型换档（bge-base-zh-v1.5）· 最终报告（2026-09-25）

> **性质**：Stage-5 全阶段证据留档（选型与被否 → 离线确证 → 换件清单 → 延迟硬线 → 真机全表与门 → 结局与回退 → UNVERIFIED）。
> **一句话结论**：换件链**全程跑通、离线质量关已过**（bge-base 融合主集 **0.7889（71/90）** vs 现役 0.7444（67/90），净增 4 命中、0 丢失），
> 但写死判据①「编码器对拍 ≥0.999」在 docs 主集上实测 **0.998638**（差 0.00136）⇒ **按判据【未换档】**：
> 随包模型件 / 向量资产 / 旁车 / 装配常量**逐字节保持 Stage-3 小档原样**（回退清单 = **0 个文件**），
> 延迟硬线②因此**未测**（不是"超线"）。**结局 = 质量关未过、交裁**——本阶段不下换档结论，也不调判据。

**日期口径**：本报告记录的是 2026-09-25 的 Stage-5 工作；收尾与提交发生在 2026-09-26。
**本报告不重定义判据**：质量关 `≥0.7444`、编码器对拍 `≥0.999`、延迟线 `213ms`、融合参数（α=0.5 / 域内 min-max / RRF k=60）、
金标集 `7c004b76…` 全部**沿用既有写死值，一个字未改**。

---

## 1. ①选定依据与三支被否的证据

### 1.1 选定：`bge-base-zh-v1.5`（BAAI）

| 项 | 值 | 落点 |
|---|---|---|
| repo / revision | `BAAI/bge-base-zh-v1.5` @ `f03589ceff5aac7111bd60cfc7d497ca17ecac65`（revision 由 HF API 钉死） | `tools/dense_build/dense_asset.py:112-121`（`MODEL_PROFILES`）、`tools/dense_build/model-manifest.json` → `modelEntries.bge-base-zh-v1.5` |
| 量级 / 维度 | 102.3M 参数 / 768 维 | 同上 `dim=768` |
| 架构同族 | BertModel + WordPiece + CLS 池化 + L2 归一 + 三输入签名（`input_ids`/`attention_mask`/`token_type_ids`） | `dense_asset.py:69-83` 的 `MODEL_SHARED`（两档**共用**同一份图契约） |
| 许可证 | MIT（可再分发） | `model-manifest.json` → `…/license` |
| 外部质量 | C-MTEB Retrieval **69.49**，与现役 bge-small 的 **61.77** 同表同口径 | **来自 Stage-5 任务书的选型记录**（仓内无第二来源，本报告未独立复核——见 §7 遗留 2） |

**为什么这一档**：与现役**同族同接口**是这次换件最值钱的一条——图契约、分词器类型、池化与归一化、词表（逐字节相同，见 §2.3）
都不动，换件只换「哪一档」与档位坐标（`dense_asset.MODEL_PROFILES`）。这也是任务书「换档只换一支模型、不做多候选赛马、
不做 MRL 截维实验」的落点：省掉的正是每次换族都要重做的分词器选型、图契约重写与端侧重实现。

### 1.2 三支被否的模型（逐支给被否理由 + 证据强度）

| 候选 | 被否理由 | 证据强度（本报告如实分级） |
|---|---|---|
| **Qwen3-Embedding-0.6B** | ① **量化端侧件无实测**（int8/4-bit 的体积与质量退化**无可信来源**）；② **LM-head 未合并**、`Qwen3ForCausalLM` 系 LLM-ish 结构的导出路径**成熟度未验证** | **仓内可复核（两处）**：`docs/kb-stage2-dense-spec.md:217`（未证实清单第 3 条「**无任何来源**」）、同文件 `:156`「Qwen3 系 LLM-ish 结构（`Qwen3ForCausalLM`）导出路径的成熟度**未验证**，见 §5」 |
| **F2LLM-v2** | ① **口径不可比**（其公布成绩与 C-MTEB 不在同一表/同一口径）；② **decoder 架构**（与现役 encoder-only 的图契约、池化、分词都不是同一条路） | **仅任务书**：`grep -rln "F2LLM" docs/ tools/` **零命中**（本报告未独立复核） |
| **gte-large-zh** | **口径不可比**（同上，成绩与 C-MTEB 不同表） | **仅任务书**：`grep -rln "gte-large-zh" docs/ tools/` **零命中**（本报告未独立复核） |

三条的共同读法：**被否的不是"这些模型不好"，而是"拿它们的成绩与本仓库的判据比不成立"**——
判据是「同一份冻结金标 90 条 + 生产词面腿 + D1 形态 + α=0.5 min-max」的**仓库内实测**，
外部榜单只有与本仓库同表同口径才可比（本档的选型理由正是同表同口径）。

---

## 2. ②离线确证（bge-base 融合数 vs 现役 + 逐章 + 词表同一性）

口径（全部沿用 Stage-3，未动）：生产 v1 词面腿（`build/production-lexical-leg.tsv`，sha256 `08cc1f70…`，32,804 行）
+ D1 形态 + **每查询候选域内 min-max、α=0.5、缺腿给 0（不参与 min-max）**；判分对象 = 冻结金标 90 条
（`tools/kb_coverage/tables/golden_queries_v1.json`，sha256 `7c004b763bdd49556e11ff1c9500c9461b09a7383b77f230fa8fd35754e6ae39`，
脚本运行时复核）；语料 28,931 行。

### 2.1 主集对照（同一词面腿、同一判分器，只换稠密腿）

| 臂 | 主集 Recall@5 | 逐章最小 | MRR | 出数处 |
|---|---|---|---|---|
| 现役 fp32（bge-small，诊断臂） | 0.7444（**67/90**） | 0.3333 | 0.60667 | `build/stage3-device-expectation.json` → `diagnostics.fp32SameProductionLeg.fused` |
| 现役 int8 资产臂（**随包那一份**） | 0.7333（66/90） | 0.3333 | 0.60352 | 同文件 → `refFusedD1` |
| **bge-base fp32** | **0.7889（71/90）** | **0.4444** | **0.6343** | `build/wp2-golden-candidate.json` → `caliberSelfCheck.measured`（`reproduced: true`） |
| **bge-base int8（α=0.5 重标定口径）** | **0.7889（71/90）** | **0.4444** | **0.6343** | 同文件 → `candidate.fused` |

**净增 4 命中、0 丢失**（`build/wp1-bgebase-stage3-caliber.json` → `comparisonVsIncumbentFp32`）：

- `incumbentHits = 67`、`candidateHits = 71`、`netHits = 4`；`gained` 4 条：index 0（rank 3）、70（rank 5）、74（rank 2）、75（rank 5）；**`lost = []`（空）**。
- 净增全部落在名次轴上，捡回的是"本来在第 3–5 名之外"的那几题——与"换档买的是主集，不是弱章"的立项口径一致。

### 2.2 逐章（`perChapter`，Δ = candidate − incumbent）

| 章 | 现役 | bge-base | Δ |
|---|---|---|---|
| 生物学必修2·第一章第二节·遗传的基本规律 | 8 | **9** | +1 |
| 物理必修第一册·第一章·运动的描述 | 7 | **8** | +1 |
| 物理必修第一册·第三章·相互作用 | 3 | **5** | +2 |
| 化学必修第一册·第三章·铁与金属材料 | 4 | 4 | 0 ← **逐章最小（4/9 = 0.4444）** |
| 化学必修第二册·第五章·硫氮及其化合物 | 9 | 9 | 0 |
| 化学必修第二册·第六章·化学反应与能量 | 9 | 9 | 0 |
| 数学必修第一册·第三章·函数的概念与性质 | 5 | 5 | 0 |
| 数学选择性必修第一册·第二三章·解析几何 | 8 | 8 | 0 |
| 数学选择性必修第二册·第四章·数列 | 6 | 6 | 0 |
| 物理必修第三册·第十一十二章·恒定电流 | 8 | 8 | 0 |

**逐章最小 0.3333 → 0.4444 是"最小值得以抬高"，不是弱章被治好了**：化学·铁与金属材料**一格未动**（4/9），
它只是不再是最小值（物理·相互作用从 3/9 抬到 5/9）。Stage-2 预注册的「逐章最小 ≥0.60」**仍不满足**——
换档**没有**解决弱章，这一点与 Stage-3 §7.1 第 7/8 项的判读一致，**没有反转**。

### 2.3 词表同一性（换件为什么不用重生成词表与分词器 fixture）

`build/wp1-bgebase-vocab-identity.txt`（`build/stage2-dense-work/wp1_vocab_identity.py` 的产物）：

| 项 | bge-base | bge-small | 结论 |
|---|---|---|---|
| `vocab.txt` 字节数 | 109,540 | 109,540 | — |
| `vocab.txt` sha256 | `45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c` | **同值** | **逐字节相同** |
| 行数 / CRLF / 末尾换行 | 21,128 / 0 / True | 同 | 同 |
| 整语料 token id（docs n=28,931 + queries n=90） | — | — | **逐条相同（逐条 id 不同 = 0）** |

⇒ 随包词表资产 `knowledge/dense/bge-small-zh-v1.5-vocab.txt` 与 333 条冻结分词 fixture（query 90 / surface 200 / edge 18 / stage 25）
**两档通用**：不换文件、不重生成。`tokenizer.json` 两档不同，差异**只在 `normalizer.lowercase`**（base=true / small=false），
而导出侧显式传 `do_lower_case=True` 覆盖它 ⇒ 逐条 id 仍相同（`export_bge_int8.py` 在换档导出时当场断言，不一致即停）。

---

## 3. ③换件清单（模型件 / 资产 / 常量 / 脚本参数化，含字节与 sha）

### 3.1 随包侧：**一件未换**（逐字节仍是 Stage-3 小档）

| 文件（入库跟踪） | 体积 | sha256 | 与 HEAD 的关系 |
|---|---|---|---|
| `core/data/src/main/assets/dense/bge-small-zh-v1.5-int8.tflite` | 62,396,488 B | `015b231580dd850e5107c6991ed36fffabf2b1f6f326134a8b146a5610c672ee` | **blob == index**（`git diff --stat HEAD` 该路径 0 行） |
| `core/data/src/main/resources/knowledge/dense/bge-small-zh-int8.vec` | 17,167,873 B | `cdf93650b948a09179c893a8de285b0248917da420770570afb47d453f80cb5a` | **blob == index**；sha 与旁车、与 `DenseRecallAssembly.kt:90` 常量三方一致 |
| `core/data/src/main/resources/knowledge/dense/bge-small-zh-int8.vec.json`（旁车） | 3,402 B | `01e631c0b44189d450ef4def33fa091cda6558ddf13ab88afaf7a2373ec20e7a` | **blob == index** |
| `core/data/src/main/resources/knowledge/dense/bge-small-zh-v1.5-vocab.txt` | 109,540 B | `45bbac6b…`（两档共用） | 未动 |
| `…/DenseRecallAssembly.kt`（常量） | — | `MODEL_ASSET_PATH = "dense/bge-small-zh-v1.5-int8.tflite"`（`:79`）、`VECTOR_ASSET_SHA256 = "cdf93650…"`（`:90`） | **blob == index**（`9affc40c…`） |

旁车自述的档位身份（`…bge-small-zh-int8.vec.json`）：`model.dim = 512`、`model.repo = BAAI/bge-small-zh-v1.5`、
`model.revision = 7999e1d3359715c523056ef9478215996d62a620`、`onnxInt8Bytes = 23,952,639`、`onnxInt8Sha256 = 4d3b31355a3df4ff923db83bc76181f8581592de10f67502be825f519d2fa213`。

### 3.2 bge-base 档的中间物（`build/`，**不入库**，可由导出链从钉死的 revision 重生成）

| 文件 | 体积 | sha256 |
|---|---|---|
| `build/dense-model/bge-base-zh-v1.5-fp32.onnx` | 406,956,186 B | `8bd4f755403b5b0d050ec24086439eac8dce292965684922b1b8fbc457188ae9` |
| `build/dense-model/bge-base-zh-v1.5-int8.onnx` | 102,989,680 B | `8baeae174677e5b1d538aa8df3c1610ffeab5e471f887166cf887c503a391ec4` |
| `.npy` 输出（通用名，身份由清单钉） | `int8-docs.npy` 28,931×768 / `int8-queries.npy` 90×768 / `fp32-*` 同 | `fea00c74…` / `b293b439…` / `0730c041…` / `3368f5c0…` |
| bge-base `.tflite`（`flatbuffer_direct` 路线，**未采用**） | 358,236,080 B（341.6 MiB） | `d290652dd8f2e258…`（`build/tflite-parity-bgebase.log`） |
| bge-base `.vec`（**为验证新代码路径打包过一遍，已按备份原字节还原**） | 会是 24,574,209 B | `d50bca8323193bf4…` |

> 还原复核：随包 `.vec` / 旁车已按 `build/backup-*` 备份**逐字节还原**，sha 复核 `cdf93650…` / `01e631c0…`，
> 上面 §3.1 的 `blob == index` 即本报告对它的独立复核。

### 3.3 脚本参数化（入库；本阶段新增 2 件、改 8 件、测试侧 3 件）

**新增（此前转换驱动只存在于 `build/tflite-work/`，而 `build/` 是 gitignore、历史上被清过场 ⇒ 转换链无法从仓库复现）**：

| 文件 | 角色 | 它消灭的具体失败 |
|---|---|---|
| `tools/dense_build/convert_onnx_to_tflite.py` | 冻静态 → onnxsim → onnx2tf；两条路线（`flatbuffer_direct` / `tf_converter_drqt`）；把 README §7 的三条取舍写成**断言**（含「源件哈希未变」） | 换件时只能照散文重写一遍转换链，**三条取舍随时漏一条**；2026-09-24 那次"转换就地改写输入 ONNX"（`4d3b3135…`→`6a795693…`）无防线 |
| `tools/dense_build/check_tflite_parity.py` | 宿主对拍 `.tflite` vs int8 ONNX（290 条文本，门 ≥0.999）+ id/行对齐自证 | tflite 不对时端侧只表现为**分数变差**（静默回退纯词面），没有宿主对拍就得靠 4 分钟真机 instrumentation 才发现 |

**改（全部是"档位参数化 + 去掉写死的 512"）**：

| 文件 | 改动要点 |
|---|---|
| `dense_asset.py` | 新增 `MODEL_PROFILES`（两档坐标）/ `model_profile()` / `model_paths()`；`DEFAULT_MODEL_KEY = "bge-small-zh-v1.5"`（= 随包那一档）；**路径名一律不动**（`.vec`/`.tflite`/词表名保持消费侧常量） |
| `export_bge_int8.py` | `--model` 选档；换档时对 333 条冻结 fixture **当场断言 token id 逐条相同**，不一致即停 |
| `pack_dense_asset.py` | `--model`；新增门：输入 `.npy` 必须与清单里**该档条目**的 sha256/维度/行数逐个相等（否则"拿另一档的向量按本档打包"没有任何门会红） |
| `freeze_onnx_static.py` | 第 4 个参数 `--out-dim`；**去掉 512 兜底**（取不到静态输出维即拒跑）——512 是小档的值，换件后拿它当默认会把 768 维的图悄悄冻成 512 维 |
| `stage3_expectation.py` / `stage3_device_sim.py` / `gen_dense_device_fixture.py` / `gen_device_parity_fixture.py` / `gen_tokenizer_fixture.py` | `--model` 选档；参考数与 fixture 的形状（N×dim）随档走；`gen_device_parity_fixture.py` 的输入长度截断从误用的 `DIM` 改成 `MAX_LEN`；`gen_tokenizer_fixture.py` 的词表来源写死为**词表那一档**的 snapshot（不跟会漂移的 `D.BGE_REVISION` 走） |
| `model-manifest.json` | 顶层仍是"当前随包那一档"的扁平镜像（`check_asset`/打包侧读它），所有档位留在 `modelEntries[<档>]`；**没过门的档位只进 `modelEntries`（`gate.passed=false`）** ⇒ 换件失败不污染随包侧读数 |

**测试侧（3 件）**：

| 文件 | 改动要点 |
|---|---|
| `DenseEncoderParityInstrumentedTest.kt` | 参考向量维度**取自 fixture 的 `dim`**（删掉写死的 `DENSE_DIM = 512`）——写死会在下一次换件时**静默比错对象** |
| `DenseRecallRerankerTest.kt` | 假编码器的向量维度取 `asset.dim`——写死 512 会让 `DenseVectorAsset.cosine` 的 `require` 抛错，测试断言的是"回退"而不是它想测的事 |
| `DenseFirstUseCostInstrumentedTest.kt` | Stage-5 延迟探针：同串重复编码 **N=10**、报 p50/p95/稳态 p50、打印**运行期模型件 bytes+sha256** 与机型/线程数；`LATENCY_LINE_MS = 213` **只打印不判定**（把某一档的数冻成断言，换档后会变成假信号） |

### 3.4 测试侧的连带（一次真实编译失败的修复）

`DenseEncoderParityInstrumentedTest.kt:193:80` 曾在 WP3 的延迟探针那一刻编译失败（`build/wp3-latency-test.log` 原文：
`e: file:///…/DenseEncoderParityInstrumentedTest.kt:193:80 Unresolved reference 'int'.` +
`> Task :core:data:compileDebugAndroidTestKotlin FAILED` + `EXIT=1`），
补 `kotlinx.serialization.json.int` 导入（本报告复核 diff：`+import kotlinx.serialization.json.int`）后，
同一条 androidTest Kotlin 编译任务 **BUILD SUCCESSFUL**（`build/wp3-androidtest-compile.log`，`EXIT=0`；
该日志不记命令行，故本报告只声称"该任务通过"，不声称具体调用参数）。

---

## 4. ④延迟硬线判定（逐样本表、p50/p95、与 213ms 线）

**判据②写死**：真机单条编码 **p50 ≤ 213ms**（= 现役 Stage-3 实测 71ms 的 3 倍，2 线程同口径）。

### 4.1 判定：**未测（不存在）**，不是"超线"

| 项 | 值 | 依据 |
|---|---|---|
| bge-base 档的逐样本表 | **不存在** | 该档**从未进入随包 assets**；本报告独立复核：assets 内模型件就是 `bge-small-zh-v1.5-int8.tflite`（62,396,488 B / `015b2315…`，与 HEAD 逐字节相同，§3.1） |
| bge-base 的 p50 / p95 | **未测** | 同上——没有件，就没有可量的对象 |
| 本阶段新写的探针 | 已就位（N=10 同串、p50/p95/稳态 p50、运行期 sha/机型/线程数），**本阶段未运行** | `DenseFirstUseCostInstrumentedTest.kt:81-116`；未运行的理由见 §4.3 |
| 现役小档的真机 p50（历史，本阶段未重跑） | **71ms**（p95 76ms / max 91ms，模拟器 API 34 x86_64、XNNPACK 开、2 线程） | Stage-3 实测；`tools/dense_build/README.md` §7.1 |

> **纪律说明**：`latency_failed`（延迟硬线未过）需要**实测**支撑。本阶段既没有 base 档的件，也没有 base 档的真机数
> ⇒ 不能记成"超线"，只能记成**未测**。把宿主旁证的外推值填进实测栏，就是把推断当事实。

### 4.2 唯一存在的 bge-base 耗时证据：宿主旁证（**外推，不是实测**）

`build/latency_probe.py`，同机、同 LiteRT Python 运行时、同形态（逐条 + PAD 512 + 掩码）各测 12 条
（`tools/dense_build/README.md` §8.5）：

| 模型件 | 宿主 p50 |
|---|---|
| bge-small tflite（现役，62.4 MB） | 2,533 ms |
| bge-base tflite（341.6 MiB） | 19,389 ms |

比值 **7.65×**（与该档/小档的 FLOPs 比 ~6.75× 同量级）⇒ 若真机也按这个比例走，现役真机 71ms 会变成 **≈350–550ms**，
与判据②（≤213ms）差得很远。**这只是旁证**：真机数必须在真机上量，本阶段没有量。

### 4.3 为什么本阶段没跑设备测试（如实登记，不是漏测）

1. **件未落地**——按判据，"不要量旧模型"：判据②的判读对象是换档后的件，量现役小档对判据②没有信息量
   （现役小档的真机数 Stage-3 已出、已封存）。
2. **设备被另一条会话的 device 测试占用**：收尾时刻 `adb` 可见的 `emulator-5554` 上，另一条工作流正在跑
   （`build/wp4a2-cold-logcat.txt` 的 mtime = 2026-09-26 00:00，`java.exe` 常驻 ~2.5 GB）。
   此时插一次 `connectedDebugAndroidTest` 会与它抢同一台设备与内存（本阶段已知的基础设施故障模式），
   **代价由别人的证据承担** ⇒ 主动不跑。

⇒ 结论：**判据②在本阶段既未通过、也未失败，状态 = 未测**。若裁定要继续换档，判据②是**第一件必须补的真机数**。

---

## 5. ⑤真机全表 + 新墙钟门与其定标依据 + APK 增量

### 5.1 真机全表（**引用** Stage-3 / Stage-4，本阶段未重跑——理由同 §4.3）

| 项 | 值 | 来源 |
|---|---|---|
| 编码对拍硬门（n=290） | min **0.99963** / median 0.99978 / p95 0.99984；query 90 条 min 0.99973、surface 200 条 min 0.99963（**全过**，阈值 0.999） | Stage-3 `DenseEncoderParityInstrumentedTest` |
| 单条编码耗时 | p50 **71ms** / p95 76ms / max 91ms（模拟器 2 线程） | 同上 |
| 首次用到才付的一次性开销 | `openEncoder` 34ms；`firstOrder` 391ms；`secondOrder`（稳态）93ms | `DenseFirstUseCostInstrumentedTest`（Stage-3） |
| 金标跑分（真 SQL 主集） | 主集 **0.7444（67/90）** / MRR 0.6109 / 逐章最小 0.4444；融合路由 p95 134–148ms | Stage-3 WP3 与复跑 |
| 融合路由墙钟（Stage-4 收口） | `p95=115ms p50=98ms max=2111 denseLegLive=true p95Budget=250ms p50Budget=150ms overBudgetSamples=3` | `build/wp4a2-golden-logcat.txt:8` |
| 19 例回归 / cross-subject / T6·W-4 | 19/19、cross-subject=0、5/5 | Stage-4（`build/wp4a2-kcr-logcat.txt`、`build/wp4a2-t6-w4-logcat.txt`） |

### 5.2 新墙钟门：**Stage-4 已建，本阶段未重定标**（判据③未触发）

| 形态 | 门 | 值 |
|---|---|---|
| 稠密腿**在场**（`denseLegLive=true`） | p50 ≤ `FUSED_P50_BUDGET_MILLIS` 且 p95 ≤ `FUSED_P95_BUDGET_MILLIS` | **150ms / 250ms**（各乘 `CI_MULTIPLIER`） |
| 稠密腿**掉**（纯词面回退） | p95 < `RECALL_P95_BUDGET_MILLIS`（**旧门不撤**） | **150ms** × `CI_MULTIPLIER` |

- 代码：`core/data/src/androidTest/.../GoldenRetrievalInstrumentedTest.kt:558`、`:584-585`，分流规则 `:194-199` 与 `:288-300`；
  定标依据（逐条写明在 `:568-579`，并附原始日志行）见 `docs/kb-stage4-report-2026-09-25.md` §4.4(1)。
- **定标依据的要点**：融合路由 = 词面腿 + 编码器前向（+ 资产扫描），与纯词面腿不是同一件事；
  拿纯词面腿的 150ms 门套它只会得到**一条恒红的门**。所以新门是"新路由的新门"，**不是放宽旧门**；
  腿掉时同一条探针当场红、墙钟退回旧门——"退化的那条腿不能借融合门宽松过关"。
- **旧门不撤声明**：纯词面回退路径的 `p95 < 150ms` **保持原值、一字未动**（上表第二行）。
- **本阶段为什么没重定标**：判据③写死「**门重定标只在①②都过后做**」。①（编码器对拍 ≥0.999）未过 ⇒ 未触发。
  本阶段**没有**改任何门值、没有改 `CI_MULTIPLIER`、没有调 α / min-max / RRF k 的任何参数。

### 5.3 APK 增量

| 形态 | 值 | 说明 |
|---|---|---|
| 现役（小档）真实 APK | **257,172,251 B**（约 245.3 MiB） | `app/build/outputs/apk/localFirst/debug/app-localFirst-debug.apk`（2026-09-25 22:34 构建，含小档随包件） |
| 本次换件对 APK 的**实际增量** | **0 B** | 随包侧一件未换（§3.1），APK 与 Stage-3 同 |
| 若落地，三块的账（小档口径，Stage-3 已量化） | 模型 62.4 + LiteRT 运行时 25.9（三 ABI，arm64 单片 8.75）+ 向量资产 17.3 原始 / 10.6 压缩 ≈ **+98.9 MB** | `tools/dense_build/README.md` §7 |
| 若落地，base 档的**预测**增量（未落地，仅登记） | 模型件：`flatbuffer_direct` 路线 **358,236,080 B**（实测，5.7×）；`tf_converter_drqt` 路线估算 **≈103 MB**（**估算，非实测**，转换被 onnx2tf 布局启发式阻断）；向量资产 24,574,209 B（实测后还原） | §3.2、`tools/dense_build/README.md` §8.4 |

---

## 6. ⑥结局分支对应的诚实结论

### 6.1 结局 = **质量关未过 ⇒ 未换档 ⇒ 交裁**（任务书 `quality_failed` 分支）

**先把两条判据的口径分开写清楚**（否则读者会以为"质量关"只有一个）：

| 判据 | 出处 | 实测 | 结论 |
|---|---|---|---|
| **离线融合主集 ≥ 0.7444** | Stage-5 任务书 ①（= Stage-2 主集口径） | **0.7889（71/90）** | **过**（净增 4、0 丢失） |
| **编码器对拍 ≥ 0.999**（docs 28,931 / queries 90） | `tools/dense_build/README.md` §8.6 写死判据① | docs **0.998638** / queries **0.999173** | **不过**（docs 差 **0.00136**） |
| 真机单条编码 p50 ≤ 213ms | Stage-5 任务书 ② | **未测**（件未落地，§4） | **未判定** |
| 门重定标 | Stage-5 任务书 ③（只在①②都过后做） | 未触发 | **未做** |

⇒ **不换档的判决来自"编码器对拍 ≥0.999"这一关**：`model-manifest.json` → `modelEntries.bge-base-zh-v1.5.gate.passed = false`
（`checks.int8_docs = false`，其余三项 true）与随包侧"一件未换"**同源、无矛盾**。

**缺口归因（实测，不是推断）**：docs 的最小值由 **2–4 个字的短文本行**决定（`单质`/`酰胺`/`电离`/`超重的判断`/`波的多解成因`）。
短文本没有"多 token 平均"，**嵌入表的 int8 误差直接落到 CLS 上**；同批行上"ONNX 图 vs 同口径 torch 模拟（模拟里嵌入表未量化）"
逐行 cosine min **0.999292** ⇒ 权重侧已够好，**缺口在嵌入表**。已试过的嵌入表口径都无增益（per-row 0.998906 更差、per-column MSE 裁剪 0.999430 同分）。
口径侧的第二轮改进（输入通道重标定 α=0.5，把 72 个矩阵的"列峰展布 max/median"从最大 146.2 压到 12.1）已把 docs 从第一轮的
**0.947814** 抬到 **0.998638**、queries 从 0.967579 抬到 **0.999173**，但仍差 0.00136。

### 6.2 回退清单：**0 个文件**（从未覆盖，故无需回退）

按任务书的"若已是小档原样 ⇒ 回退为无操作"分支，本阶段**未执行任何 `git checkout --`**。逐项复核（本报告独立复跑）：

| 项 | 复核方式 | 结果 |
|---|---|---|
| `bge-small-zh-v1.5-int8.tflite` | `sha256sum` + `git diff --stat HEAD` | 62,396,488 B / `015b2315…`；该路径 diff 为 0 行 ✅ |
| `bge-small-zh-int8.vec` | 同上 | 17,167,873 B / `cdf93650…`；diff 0 行；== `DenseRecallAssembly.kt:90` 常量 ✅ |
| 旁车 `.vec.json` | 同上 | `01e631c0…`；diff 0 行 ✅ |
| `DenseRecallAssembly.kt` | `git diff --stat HEAD` | diff 0 行（`MODEL_ASSET_PATH` 仍指小档件） ✅ |
| 端侧 encoder-parity fixture | 引 WP3b 的复核 | 512 维（vectors sha `c4e82beb…` == 封印） ✅ |

**本次收尾实跑的门（当前会话结果，不是引用）**：`python tools/dense_build/check_asset.py` → `EXIT=0`，**10/10 OK**——
sidecar 与当前包 sha 一致（`533eab121d77e3da`）、词表（`45bbac6b…`）与 tokenizer（`48cea5d44424912a`）两处哈希一致、
`.vec` 哈希一致（`cdf93650b948a091`）、`vectorHeader dim=512 count=28931`、`vectorBytes 17167873 B`、
**`layout：ids 与包布局逐条一致（28931 条向量）`**、`modelManifest 三方一致`（vocab / sidecar onnxInt8 / onnxBytes）。
`python tools/ci/run_kb_checks.py` → `EXIT=0`（gates / consistency / roundtrip / manifest / **dense** 全 OK）。

> 唯一的"换件残留"是**文档误述**，已在 WP3b 最小修订：`tools/dense_build/export_bge_int8.py` 的 docstring 曾有两处
> 称默认档 = `bge-base-zh-v1.5`，与代码相反（实测 `D.DEFAULT_MODEL_KEY = "bge-small-zh-v1.5"`、`model_profile() → dim 512`）。
> 未整文件回退：该参数化是计划内交付、且被两支新脚本 `import` 依赖。本报告收尾时复核：docstring 现为
> "默认 = 仓库当前随包的那一档（`bge-small-zh-v1.5`）… Stage-5 换件目标档 `bge-base-zh-v1.5` **未落地**"
> （`tools/dense_build/export_bge_int8.py:3-7`）——与代码一致。

### 6.3 若裁定"继续换档"：跨过 0.00136 的两条现成路径（**都不是本阶段能自行决定的**）

| 路径 | 代价 | 性质 |
|---|---|---|
| ① 嵌入表（21,128×768）留 fp32 | 件 ~151 MB（+48.7 MB） | **纯工程代价**，不动判据 |
| ② 嵌入表用"int8 + int8 残差"两级码（`Gather(q1)·s1 + Gather(q2)·s2`，ONNX 与 TFLite 都只有 GATHER/MUL/ADD 内置算子） | 件 ~119 MB（+16.2 MB） | 同上 |
| ③ 把 docs 侧的门降下来 / 只按 queries 判 | — | **这是放宽门**，需用户明示裁定，**本阶段不做、也不建议由代理提** |

> 补一条**必须一起看**的风险：即使 0.00136 补上，判据②（≤213ms）按 §4.2 的宿主旁证（7.65×）**极可能过不去**，
> 且 `tf_converter_drqt` 的 int8 体积路线在 base 档**尚未跑通**（onnx2tf 把 3-D 中间张量按 NCHW 误判后转置，
> `wa/backbone_module/embeddings/Add_1` 报 `Dimensions must be equal, but are 512 and 768`；`-kat` 无效，原文在 `build/odrqt-test/base-kat.log`）。
> 换句话说：**"补齐质量缺口"与"过延迟线/压体积"是两道独立的坎，本阶段的证据不支持"只差一点点"的乐观读法。**

---

## 7. ⑦UNVERIFIED 与遗留

1. **bge-base 的真机延迟不存在（未测）**：判据②无实测数；§4.2 的 7.65× 是宿主旁证、§4.3 说明了为什么本轮没跑设备测试。
   任何"base 档 ≈350–550ms"的写法都只是**外推**。
2. **外部榜单一律未独立复核**：C-MTEB Retrieval 69.49 / 61.77 来自 **Stage-5 任务书**，仓内无第二来源；
   `grep -rln "F2LLM" docs/ tools/` 与 `grep -rln "gte-large-zh" docs/ tools/` **零命中**（被否理由仅来自任务书）。按 §11.3 的口径，这些属"单一来源待验证"。
3. **README §8.4 的两行 base 档"子集探针"数（n=24：`min 0.999710 / 中位 0.999761`）在本机找不到落盘日志**：
   `build/` 下唯一可定位的 base 档宿主对拍日志是 `build/tflite-parity-bgebase.log`，它只打到"tflite 输出"行就以 `EXIT=1` 结束
   （未打印 per-kind / ALL 行），且其 citation 的 int8 ONNX sha `f4f39eb39baa9ca8` 是第一轮口径的旧件（现档为 `8baeae174677e5b1`）。
   本报告**保留**该记录（没有证据说它是错的），但**标注为未寻到出处**；不影响任何判决（该路线不属于随包件）。
4. **base 档 `tf_converter_drqt` 的体积是估算**（≈103 MB）：转换被 onnx2tf 布局启发式阻断，未实测。
5. **`check_tflite_parity.py` 的 base 档全量 290 条未跑完**（只跑了子集探针；README §8.4 的待办仍挂着）。
6. **本次收尾未重跑任何设备测试**：§5.1 全部是 Stage-3 / Stage-4 的日志引用；§5.2 的门与定标依据是 Stage-4 的记录。
   §4.3 说明了不跑的理由（件未落地 + 设备被另一条会话的 device 测试占用）。本报告对设备侧的独立贡献 = **只读复核**
   （§3.1 的 sha/blob 对比、§6.2 的回退清单）。
7. **`build/*` 证据产物不入版本库**（`.gitignore:14` = `**/build/`）：本报告引用的 json / log / logcat 都只在本机磁盘，
   clone 后需按 §8 的命令重跑才能复算（仓库既有约定，Stage-1/2/3/4 同）。
8. **两轮口径的数并存，判读以当前口径为准**：bge-base int8 的 MRR 在第一轮（per-channel 口径）是 **0.6219**，
   在第二轮（α=0.5 重标定）是 **0.6343**（与 fp32 臂同数）。`tools/dense_build/README.md` §8.3 与 §8.6 各记一条；
   本报告与 §8.6 同口径。第一轮的 docs 数 0.947814 与本轮的 0.998638 同理，不是矛盾，是**前后两轮**。
9. **`%TEMP%/`、`.agent_*`、`out/`、`tmp/` 等脏文件属其它会话**：本次提交一律不碰（§8 的清单是显式的）。
10. **本次收尾的 JVM 侧稠密测试被另一条会话的飞行改动挡住（外部阻塞，如实登记）**：
    `./gradlew :core:data:testDebugUnitTest --tests "*Dense*" --rerun` 三次（`build/wp5b-dense-tests{,2,3}.log`）均
    `BUILD FAILED in 5–11s`，失败任务是 **`:core:database:kspDebugKotlin`**（与本次提交内容无一处交集），
    原文：`e: [ksp] …/dao/TutorInteractionDao.kt:18: [MissingType]: Element '…TutorInteractionDao' references a type that is not present`
    （+ `TutorExposureDao`、`ProblemDraftTransactionDao` 同理，`RoomProcessor was unable to process '…StudyDatabase'`）。
    **归因（本会话实测）**：另一条会话正在改 `core/database`（`git status` 显示 5 个文件，**均不在本阶段清单内**：
    `StudyDatabase.kt` / `entity/CaptureEntities.kt` / `entity/TutorConversationEntities.kt` / `entity/TutorExposureEntities.kt` 改，
    `entity/TutorInteractionEntities.kt` **已删**），而该被删文件在 HEAD 里正是 `internal data class TutorTurnResponseEntity(` 的声明处
    （`git show HEAD:…/TutorInteractionEntities.kt`），DAO 仍 import 它、全树已无该类（`grep -rn "class TutorTurnResponseEntity" core/database/src/main/kotlin/` 空）
    ⇒ 是那条会话重构的**中间态**，不是本阶段引入。按纪律**不碰它们的文件**、不由本代理"顺手修好"。
    **本阶段自有内容最后一次通过的编译/测试证据**（均为**前一阶段**的运行，不是本会话）：
    `DenseRecallRerankerTest.kt`（mtime 20:28）→ `build/gradle-dense-tests.log`（20:52，21 tests / 0 failures）；
    `DenseFirstUseCostInstrumentedTest.kt`（22:34）→ `build/wp3-androidtest-compile.log`（22:41，EXIT=0）；
    `DenseEncoderParityInstrumentedTest.kt`（23:31）→ `build/wp2-androidtest-compile3.log`（23:32，`28 actionable tasks: 1 executed`，BUILD SUCCESSFUL）。
    ⇒ **本会话未能验证这三件在当前树上仍可编译/通过**，标记为 `UNVERIFIED（外部阻塞）`，等那条会话收口后重跑 §8 的第 ⑧ 组命令即可。

---

## 8. 复核方式（可重跑）

```bash
# ① 判据①（编码器对拍，本档口径）——重算 int8 ONNX vs torch fp32
python tools/dense_build/export_bge_int8.py --model bge-base-zh-v1.5

# ② 判据① 的宿主下游（若要把 tflite 也量上；base 档 drqt 路线尚未跑通）
build/tflite-venv/Scripts/python.exe tools/dense_build/check_tflite_parity.py

# ③ 离线融合主集（bge-base 臂）= 本报告 §2.1 的数
python build/wp2_golden_candidate.py          # 判分器 import 自 stage3_expectation.py

# ④ 逐题净增 / 逐章（§2.1 / §2.2）
#    重跑 WP1 口径脚本（build/stage2-dense-work/wp1_bgebase_stage3_caliber.py）
python build/stage2-dense-work/wp1_bgebase_stage3_caliber.py

# ⑤ 词表同一性（§2.3）
python build/stage2-dense-work/wp1_vocab_identity.py

# ⑥ 随包侧"一件未换"（§3.1 / §6.2）
git diff --stat HEAD -- core/data/src/main/assets/dense/ core/data/src/main/resources/knowledge/dense/
sha256sum core/data/src/main/assets/dense/bge-small-zh-v1.5-int8.tflite \
          core/data/src/main/resources/knowledge/dense/bge-small-zh-int8.vec \
          core/data/src/main/resources/knowledge/dense/bge-small-zh-int8.vec.json

# ⑦ 门与契约（Python 侧）——收尾实跑，本结果 = 本会话的真实运行
python tools/ci/run_kb_checks.py                       # EXIT=0：gates / consistency / roundtrip / manifest / dense 全 OK
python tools/dense_build/check_asset.py                # EXIT=0：10/10 OK（含 layout：ids 与包布局逐条一致 28,931 条）
python -m unittest discover -s tools/tests -t tools -p "test_dense_asset_gate.py"   # Ran 6 tests, OK

# ⑧ JVM 侧稠密测试——**本次收尾未能跑**（外部阻塞，见 §7 遗留 10；下面两条是复跑命令，不是本会话结果）
./gradlew :core:data:testDebugUnitTest --tests "*Dense*" --rerun
./gradlew :core:data:compileDebugAndroidTestKotlin

# ⑨ 金标集冻结复核
sha256sum tools/kb_coverage/tables/golden_queries_v1.json    # 应为 7c004b76…
```

**纪律自查**：本阶段**未**增删改金标集（`7c004b76…` 不增不减不改）、**未**动预注册线（0.75/0.60）、
**未**动门值（既有 p95 150ms 与新融合门 150/250ms 一字未改）、**未**动融合参数（α=0.5 / min-max / RRF k=60）、
**未**在判官上调参、**未**做多候选赛马或 MRL 截维实验。
