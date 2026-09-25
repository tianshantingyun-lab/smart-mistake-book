# Stage-5 稠密模型换档（bge-base-zh-v1.5）· 最终报告（2026-09-25）

> **性质**：Stage-5 全阶段证据留档（选型与被否 → 离线确证 → 换件清单 → 延迟硬线 → 真机全表与门 → 结局与回退 → UNVERIFIED）。
>
> **一句话结论（第三轮后定论）**：换件链**全程跑通、质量关全过**——编码器对拍（全量 28,931 行）docs 逐行 cosine
> 最小 **0.999127567** ≥0.999、金标融合主集 **0.7889（71/90）** ≥0.7444（vs 现役 0.7444，净增 4 命中、0 丢失）；
> 但写死判据②「真机单条编码 p50 ≤ **213ms**」**不达**：宿主同形态同机实测 base 档 `.tflite` **p50 17,437 ms**
> （前轮同形态记录 16,843 ms）vs 小档 **2,582 ms**，比值 **6.75×** 与两档 FLOPs 比一致，按小档"宿主 2,545 ms → 真机
> 71 ms（≈36×）"外推，base 档真机 **≈470–485 ms**（**外推，base 档真机数不存在**）⇒ **按判据【不落地】**。
> 随包模型件 / 向量资产 / 旁车 / 装配常量**逐字节保持 Stage-3 小档原样**（回退清单 = **0 个文件**），
> 判据③（门重定标）因此**未触发**、旧门一字未改。**结局标签 = `latency_failed`**（延迟硬线不达 ⇒ 未落地）。
>
> **本轮 Kotlin 侧验证 = UNVERIFIED（外部阻塞，逐字登记）**：
> ① **共享工作树阻断的证据**：另一条会话正在改 `core/database`（**未提交**）——**三实体被删而 DAO 未同步**
> （`git status` 显示 `entity/TutorInteractionEntities.kt` 整文件**已删**，其声明的 `TutorSessionEntity` /
> `TutorTurnResponseEntity` / `TutorSessionProblemAnchorEntity` 三个类**在工作区已无定义**，而
> `dao/TutorInteractionDao.kt` / `dao/TutorExposureDao.kt` / `dao/ProblemDraftTransactionDao.kt` 仍引用它们；
> 本会话实跑 `grep -rn "class TutorSessionEntity\|class TutorTurnResponseEntity\|class TutorSessionProblemAnchorEntity" core/ --include=*.kt`
> → **零命中**），**并且** `StudyDatabase.kt:352` 新引用 `TUTOR_CONVERSATION_AREA_MIGRATION_51_52` 而该常量
> **工作区与 HEAD 树里都没有定义**（实跑 `grep -rn "val TUTOR_CONVERSATION_AREA_MIGRATION_51_52\|const val TUTOR_CONVERSATION" core/ --include=*.kt` 与
> `git grep -n "TUTOR_CONVERSATION_AREA_MIGRATION_51_52" HEAD -- core/` **均为空**）。**按纪律我们一律没碰这些文件**。
> ② 因此 **Kotlin 侧验证（JVM / 仪器化）本轮记 UNVERIFIED（外部阻塞）**：本会话实跑
> `./gradlew :core:data:testDebugUnitTest --tests "*Dense*" --rerun` → `:core:database:kspDebugKotlin FAILED`
> （KSP MissingType ×3，BUILD FAILED in 6s）。**Python 侧照常全量**：`run_kb_checks.py` EXIT=0（含 dense 节）、
> `check_asset.py` 10/10 OK、`test_dense_asset_gate.py` 6 tests OK（§6.3，均为本会话实跑）。
> ③ **落地待共享工作树恢复**（对方补齐或用户裁定）——换件后必跑的真机硬门本轮**无法执行**。
>
> **一句话的因果次序**：即使判据②侥幸过线，本轮的 Kotlin 阻断也**单独构成**"不能宣称已落地"的理由；
> 两条互不顶替。

**日期口径**：本报告记录的是 2026-09-25 的 Stage-5 工作（第三轮在 2026-09-26 凌晨收口）；收尾与提交发生在 2026-09-26。
**本报告不重定义判据**：质量关 `≥0.7444`、编码器对拍 `≥0.999`、延迟线 `213ms`、融合参数（α=0.5 / 域内 min-max / RRF k=60）、
金标集 `7c004b76…` 全部**沿用既有写死值，一个字未改**；预注册线 0.75/0.60 **未动**；**未在判官上调参**。

---

## 0. 结局一张表（先给判定，再给证据）

| 判据（写死，未改） | 出处 | 实测 | 结论 |
|---|---|---|---|
| **①编码器对拍 ≥ 0.999**（docs 28,931 行 / queries 90 条） | `tools/dense_build/README.md` §8.4/§8.6 | docs **0.999127567** / queries **0.999171495**（全量，GPTQ 误差补偿口径） | **过** |
| **①′金标融合主集 ≥ 0.7444** | Stage-5 任务书 ①（= Stage-2 主集口径） | **0.7889（71/90）**，逐章最小 **0.4444**，MRR 0.6269（fp32 臂 0.6343） | **过**（净增 4、0 丢失） |
| **②真机单条编码 p50 ≤ 213ms**（现役 71ms × 3，2 线程同口径） | Stage-5 任务书 ② | **真机数不存在**；宿主同形态 p50 **17,437 ms**（本会话复测）/ **16,843 ms**（前轮记录）⇒ 外推真机 **≈470–485 ms** | **不达** |
| **③门重定标**（只在①②都过后做；旧门不撤） | Stage-5 任务书 ③ | ②不达 ⇒ 未触发 | **未做**（一门一字未改） |
| 落地（件进随包 assets / 资源 / 常量） | — | 三条生产路径 `git diff --stat HEAD` = **0 行** | **未落地** |
| 回退清单 | 任务书回退条款 | `git checkout --` **未执行**（从未覆盖随包件） | **0 个文件** |

**标签与事实的关系（如实标注）**：任务书给的结局标签是 `latency_failed`（= 延迟硬线未过或未落地），
本报告**按事实**记为「质量关已过、体积已达标（126.2 MiB）、**延迟硬线不达 ⇒ 未落地**」，
并列逐字登记第二重事实——**共享工作树被另一会话的未完成重构阻断，Kotlin 侧验证本轮记 UNVERIFIED（外部阻塞）**。

---

## 1. ①选定依据与三支被否的证据

### 1.1 选定：`bge-base-zh-v1.5`（BAAI）

| 项 | 值 | 落点 |
|---|---|---|
| repo / revision | `BAAI/bge-base-zh-v1.5` @ `f03589ceff5aac7111bd60cfc7d497ca17ecac65`（revision 由 HF API 钉死） | `tools/dense_build/dense_asset.py`（`MODEL_PROFILES`）、`tools/dense_build/model-manifest.json` → `modelEntries.bge-base-zh-v1.5`（收尾复核：`revision=f03589ce…`、`dim=768`、`pooling=cls`、`license=mit（基座许可证；可再分发）`） |
| 量级 / 维度 | 102.3M 参数 / 768 维 | 同上 `dim=768` |
| 架构同族 | BertModel + WordPiece + CLS 池化 + L2 归一 + 三输入签名（`input_ids`/`attention_mask`/`token_type_ids`） | `dense_asset.py` 的 `MODEL_SHARED`（两档**共用**同一份图契约）；收尾复核转换日志：tflite 输入 `[('input_ids',[1,512],'int64'),('attention_mask',…),('token_type_ids',…)]`、输出 `sentence_embedding [1,768] float32` |
| 许可证 | MIT（可再分发） | `model-manifest.json` → `license` |
| 外部质量 | C-MTEB Retrieval **69.49**，与现役 bge-small 的 **61.77** 同表同口径 | **来自 Stage-5 任务书的选型记录**（仓内无第二来源，本报告未独立复核——见 §7 遗留 2） |

**为什么这一档**：与现役**同族同接口**是这次换件最值钱的一条——图契约、分词器类型、池化与归一化、词表（逐字节相同，见 §2.3）
都不动，换件只换「哪一档」与档位坐标（`dense_asset.MODEL_PROFILES`）。这也是任务书「换档只换一支模型、不做多候选赛马、
不做 MRL 截维实验」的落点：省掉的正是每次换族都要重做的分词器选型、图契约重写与端侧重实现。
**本轮只换这一支**：全部 base 档读数只来自 `bge-base-zh-v1.5`，没有跑任何第二支候选。

### 1.2 三支被否的模型（逐支给被否理由 + 证据强度）

| 候选 | 被否理由 | 证据强度（本报告如实分级） |
|---|---|---|
| **Qwen3-Embedding-0.6B** | ① **量化端侧件无实测**（int8/4-bit 的体积与质量退化**无可信来源**）；② **LM-head 未合并**、`Qwen3ForCausalLM` 系 LLM-ish 结构的导出路径**成熟度未验证** | **仓内可复核（两处，收尾实读）**：`docs/kb-stage2-dense-spec.md:217`（未证实清单第 3 条「**无任何来源**」）、同文件 `:156`「Qwen3 系 LLM-ish 结构（`Qwen3ForCausalLM`）导出路径的成熟度**未验证**，见 §5」 |
| **F2LLM-v2** | ① **口径不可比**（其公布成绩与 C-MTEB 不在同一表/同一口径）；② **decoder 架构**（与现役 encoder-only 的图契约、池化、分词都不是同一条路） | **仅任务书**：收尾实跑 `grep -rln "F2LLM" docs/ tools/` → 命中的**只有本报告自身**（本报告写入前为 0 命中）⇒ 仓内无第二来源，本报告未独立复核 |
| **gte-large-zh** | **口径不可比**（同上，成绩与 C-MTEB 不同表） | **仅任务书**：收尾实跑 `grep -rln "gte-large-zh" docs/ tools/` → 同上一行情形 |

三条的共同读法：**被否的不是"这些模型不好"，而是"拿它们的成绩与本仓库的判据比不成立"**——
判据是「同一份冻结金标 90 条 + 生产词面腿 + D1 形态 + α=0.5 min-max」的**仓库内实测**，
外部榜单只有与本仓库同表同口径才可比（本档的选型理由正是同表同口径）。

---

## 2. ②离线确证（bge-base 融合数 vs 现役 + 逐章 + 词表同一性）

口径（全部沿用 Stage-3，未动）：生产 v1 词面腿（`build/production-lexical-leg.tsv`，sha256 `08cc1f70…`，32,804 行）
+ D1 形态 + **每查询候选域内 min-max、α=0.5、缺腿给 0（不参与 min-max）**；判分对象 = 冻结金标 90 条
（`tools/kb_coverage/tables/golden_queries_v1.json`；**收尾实跑** `sha256sum` = `7c004b763bdd49556e11ff1c9500c9461b09a7383b77f230fa8fd35754e6ae39`，与写死值一致、**未增未减未改**）；语料 28,931 行。

### 2.1 主集对照（同一词面腿、同一判分器，只换稠密腿）

| 臂 | 主集 Recall@5 | 逐章最小 | MRR | 出数处 |
|---|---|---|---|---|
| 现役 fp32（bge-small，诊断臂） | 0.7444（**67/90**） | 0.3333 | 0.60667 | `build/wp1-bgebase-stage3-caliber.json` → `arms[label=incumbent-fp32].fused` |
| 现役 int8 资产臂（**随包那一份**） | 0.7333（66/90） | 0.3333 | 0.60352 | 同文件 → `arms[label=incumbent-int8-asset].fused` |
| **bge-base fp32** | **0.7889（71/90）** | **0.4444** | **0.63426** | 同文件 → `arms[label=bgebase-fp32].fused`；`build/wp2-golden-candidate.json` → `caliberSelfCheck.measured`（`reproduced: true`） |
| **bge-base int8** | **0.7889（71/90）** | **0.4444** | **0.62685** | `build/wp2-golden-candidate.json` → `candidate.fused`（`landingCriterion.passed: true`） |

> **收尾纠正一处数字**：本报告 2026-09-26 00:33 的上一版把 int8 臂的 MRR 写成 0.6343 —— 那是 **fp32 臂**的数；
> int8 臂的 MRR 实测 **0.62685**（`candidate.fused.mrr`），与 `tools/dense_build/README.md` §8.6 的 0.6269 同源。
> 两臂的主集/逐章数相同（71/90、0.4444），只有名次轴有差 ⇒ 本版按 JSON 逐位更正，判据结论不受影响。

**净增 4 命中、0 丢失**（`build/wp1-bgebase-stage3-caliber.json` → `comparisonVsIncumbentFp32`，收尾实读）：

- `incumbentHits = 67`、`candidateHits = 71`、`netHits = 4`；`gained` 4 条：index 0（rank 3）、70（rank 5）、74（rank 2）、75（rank 5）；**`lost = []`（空）**。
- 净增全部落在名次轴上，捡回的是"本来在第 3–5 名之外"的那几题——与"换档买的是主集，不是弱章"的立项口径一致。

### 2.2 逐章（`perChapter`，Δ = candidate − incumbent）

| 章 | 现役 fp32 | bge-base | Δ |
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

**收尾实跑复核**：`sha256sum core/data/src/main/resources/knowledge/dense/bge-small-zh-v1.5-vocab.txt`
= `45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c`，与上表两档同值一致
（`tools/dense_build/check_asset.py` 也把该哈希与旁车记录对上了，见 §6.3）。

⇒ 随包词表资产 `knowledge/dense/bge-small-zh-v1.5-vocab.txt` 与 333 条冻结分词 fixture（query 90 / surface 200 / edge 18 / stage 25）
**两档通用**：不换文件、不重生成。`tokenizer.json` 两档不同，差异**只在 `normalizer.lowercase`**（base=true / small=false），
而导出侧显式传 `do_lower_case=True` 覆盖它 ⇒ 逐条 id 仍相同（`export_bge_int8.py` 在换档导出时当场断言，不一致即停；
第三轮导出日志原文：`tokenizer 对拍：本档（bge-base-zh-v1.5）× 冻结 fixture 333 条逐条同 id ⇒ 端侧分词口径不漂移`）。

---

## 3. ③换件清单（模型件 / 资产 / 常量 / 脚本参数化，含字节与 sha）

### 3.1 随包侧：**一件未换**（逐字节仍是 Stage-3 小档）

| 文件（入库跟踪） | 体积 | sha256 | 与 HEAD 的关系 |
|---|---|---|---|
| `core/data/src/main/assets/dense/bge-small-zh-v1.5-int8.tflite` | 62,396,488 B | `015b231580dd850e5107c6991ed36fffabf2b1f6f326134a8b146a5610c672ee` | **blob == index**；**收尾实跑** `sha256sum` 复核同值，该路径 diff 0 行 |
| `core/data/src/main/resources/knowledge/dense/bge-small-zh-int8.vec` | 17,167,873 B | `cdf93650b948a09179c893a8de285b0248917da420770570afb47d453f80cb5a` | 同上；sha 与旁车、与 `DenseRecallAssembly.kt:90` 常量三方一致（收尾复核） |
| `core/data/src/main/resources/knowledge/dense/bge-small-zh-int8.vec.json`（旁车） | 3,402 B | `01e631c0b44189d450ef4def33fa091cda6558ddf13ab88afaf7a2373ec20e7a` | 同上（收尾复核） |
| `core/data/src/main/resources/knowledge/dense/bge-small-zh-v1.5-vocab.txt` | 109,540 B | `45bbac6b…`（两档共用） | 未动 |
| `…/DenseRecallAssembly.kt`（常量） | — | `MODEL_ASSET_PATH = "dense/bge-small-zh-v1.5-int8.tflite"`、`VECTOR_ASSET_SHA256 = "cdf93650…"` | **blob == index** |

> 收尾独立复核（**本会话实跑**）：`git diff --stat HEAD -- core/data/src/main/assets/dense core/data/src/main/resources/knowledge/dense core/data/src/main/kotlin/…/dense`
> → **输出 0 行**（三条生产路径全干净）。这是"未落地"最直接的证据，也是"从未覆盖随包件"的复核。

旁车自述的档位身份（`…bge-small-zh-int8.vec.json`）：`model.dim = 512`、`model.repo = BAAI/bge-small-zh-v1.5`、`onnxInt8Bytes = 23,952,639`。

### 3.2 bge-base 档的中间物（`build/`，**不入库**，可由导出链从钉死的 revision 重生成）

| 文件 | 体积 | sha256 |
|---|---|---|
| `build/dense-model/bge-base-zh-v1.5-fp32.onnx` | 406,956,186 B | `8bd4f755403b5b0d050ec24086439eac8dce292965684922b1b8fbc457188ae9` |
| `build/dense-model/bge-base-zh-v1.5-int8.onnx`（**第三轮最终口径**：GPTQ 误差补偿 + 嵌入表 1 级 + `intermediate/dense` 两级残差） | **131,452,720 B** | `1994768d776b86845a17f22540ccdce1a10b3628b9a46c5a7b8f711271b99b75` |
| base 档 `.tflite`（**第三轮最终件**，路线 `flatbuffer_direct_keepint8`） | **132,375,600 B（126.2 MiB）** | `45fe2cb7936b1f498f391f03a767e25affc6346b208fc92c29f4539f8bc7c518` |
| base 档 `.vec`（scratch，**未覆盖随包件**） | 24,574,209 B | `4484a739e351bbd50a00684a7256bdf788797f06f96246163ffd05c9d9b2ac58`（收尾实跑 `sha256sum` 复核同值） |
| `.npy` 输出（通用名，身份由清单钉） | `int8-docs.npy` 28,931×768 sha `e527608c…` / `int8-queries.npy` 90×768 sha `cbbc989e…` | `build/wp2-golden-candidate.json` → `candidate` |
| 前两轮的 base 档件（**已被第三轮取代**，仅留作对照） | `flatbuffer_direct` 341.6 MiB（`d290652d…`）/ int8 ONNX 102,989,680 B（`8baeae17…`） | `tools/dense_build/README.md` §8.4 与 `build/wp2r3-backup/model-manifest.json` |

**第三轮 tflite 的存储与算子**（收尾实读 `build/wp2r3-convert-final.log` / `build/wp2r3-parity-final.log`）：
88 个 int8 张量 / 130,652,160 个元素（124.6 MiB）；**无 Flex、无 CUSTOM**；输入输出见 §1.1；`源件哈希未变：1994768d776b8684 UNCHANGED`。
**同件的收尾复核**：`build/tflite-work/bge-base-zh-v1.5/out/static-512-sim_float32.tflite` 实测 **132,375,600 B**（与上表一致）。
**转换保真**：`build/wp2r3-parity-final.log` 的 id/行对齐自证 **min 0.999918149**（下限 0.9990）；
tflite vs int8 ONNX 的 n=12 子集对拍 **min 0.999879** 来自 WP2 第三轮返回（**收尾未独立复核，见 §7 遗留 3**）；
全量 290 条对拍**未跑完**（§7 遗留 3）。

### 3.3 脚本参数化（入库；本阶段新增 4 件、改 8 件、测试侧 3 件）

**新增（前两轮 2 件 + 第三轮 2 件）**：

| 文件 | 角色 | 它消灭的具体失败 |
|---|---|---|
| `tools/dense_build/convert_onnx_to_tflite.py` | 冻静态 → onnxsim → onnx2tf；把三条取舍写成**断言**（含「源件哈希未变」） | 换件时只能照散文重写转换链、取舍随时漏一条；"转换**就地改写输入 ONNX**"（2026-09-24 那次 `4d3b3135…`→`6a795693…`）无防线 |
| `tools/dense_build/check_tflite_parity.py` | 宿主对拍 `.tflite` vs int8 ONNX（290 条，门 ≥0.999）+ id/行对齐自证 | tflite 不对时端侧只表现为**分数变差**（静默回退纯词面），没有宿主对拍就得靠 4 分钟真机 instrumentation 才发现 |
| `tools/dense_build/quant_error_compensation.py`（第三轮） | **GPTQ 误差补偿**：保持同一张 int8 网格（`f`、`s` 一字不改），只用尚未量化的权重反向抵消已舍入部分的误差 | 前两轮的 docs 卡在 0.998638（差 0.00136）：逐矩阵舍入误差**累积到 CLS**，而"留 fp32 / 加一级残差"要花 85 MB 才补齐（件会到 188 MB，超出"约 100 MB 级"目标） |
| `tools/dense_build/onnx2tf_keep_weight_int8.py`（第三轮） | `python -m onnx2tf` 的等价包装：**进程内**把 `constant_fold_a5` 的 `_FOLDABLE_OPS` 摘掉 `DequantizeLinear`（不改 site-packages、不改其余预处理） | `flatbuffer_direct` 把 int8 权重**折成 fp32 常量**（base 件就地展开成 341.6 MiB），端侧没法分发——这是"体积回不到 int8 量级"的根因 |

**改（全部是"档位参数化 + 去掉写死的 512"）**：

| 文件 | 改动要点 |
|---|---|
| `dense_asset.py` | `MODEL_PROFILES`（两档坐标）/ `model_profile()` / `model_paths()`；`DEFAULT_MODEL_KEY = "bge-small-zh-v1.5"`（= 随包那一档）；**路径名一律不动**（`.vec`/`.tflite`/词表名保持消费侧常量） |
| `export_bge_int8.py` | `--model` 选档；换档时对 333 条冻结 fixture **当场断言 token id 逐条相同**，不一致即停；新增 `--weights-ongrid` / `--publish`（顶层镜像只在显式换件时改写） |
| `convert_onnx_to_tflite.py` | 新增 `--route flatbuffer_direct_keepint8`；断言 int8 张量规模（防折叠回归） |
| `pack_dense_asset.py` | `--model`；门：输入 `.npy` 必须与清单里**该档条目**的 sha256/维度/行数逐个相等（否则"拿另一档的向量按本档打包"没有任何门会红） |
| `freeze_onnx_static.py` | 第 4 个参数 `--out-dim`；**去掉 512 兜底**（512 是小档的值，换件后拿它当默认会把 768 维的图悄悄冻成 512 维） |
| `stage3_expectation.py` / `stage3_device_sim.py` / `gen_dense_device_fixture.py` / `gen_device_parity_fixture.py` / `gen_tokenizer_fixture.py` | `--model` 选档；参考数与 fixture 形状随档走；`gen_device_parity_fixture.py` 的截断从误用的 `DIM` 改回 `MAX_LEN`；`gen_tokenizer_fixture.py` 的词表来源写死为**词表那一档**的 snapshot |
| `model-manifest.json` | 顶层仍是"当前随包那一档"的扁平镜像（`check_asset`/打包侧读它），所有档位留在 `modelEntries[<档>]`；**没过门的档位只进 `modelEntries`** ⇒ 换件失败不污染随包侧读数。**收尾复核**：`modelEntries["bge-base-zh-v1.5"].gate.passed = true`（四项 checks 全 true、threshold 0.999），顶层镜像仍 = 小档 |

**测试侧（3 件；收尾复核 `git diff --stat HEAD -- core/data/src/androidTest/` = 0 行 ⇒ 与 HEAD 逐字节相同）**：

| 文件 | 改动要点 |
|---|---|
| `DenseEncoderParityInstrumentedTest.kt` | 参考向量维度**取自 fixture 的 `dim`**（删掉写死的 `DENSE_DIM = 512`）——写死会在下一次换件时**静默比错对象** |
| `DenseRecallRerankerTest.kt` | 假编码器的向量维度取 `asset.dim`——写死 512 会让 `DenseVectorAsset.cosine` 的 `require` 抛错，测试断言的是"回退"而不是它想测的事 |
| `DenseFirstUseCostInstrumentedTest.kt` | Stage-5 延迟探针：同串重复编码 **N=10**、报 p50/p95/稳态 p50、打印**运行期模型件 bytes+sha256** 与机型/线程数；`LATENCY_LINE_MS = 213`（`:149`）**只打印不判定**（把某一档的数冻成断言，换档后会变成假信号） |

---

## 4. ④延迟硬线判定（逐样本表、p50/p95、与 213ms 线）

**判据②写死**：真机单条编码 **p50 ≤ 213ms**（= 现役 Stage-3 实测 71ms 的 3 倍，2 线程同口径）。

### 4.1 判定：**不达**（宿主实测 + 外推证据；**真机数不存在**）

| 项 | 值 | 依据 |
|---|---|---|
| bge-base 档的**真机**逐样本表 | **不存在** | 该档**从未进入随包 assets**（§3.1 三条路径 diff = 0 行）；端侧探针 `DenseFirstUseCostInstrumentedTest` 因此**未运行** |
| bge-base 档的**真机** p50 / p95 | **未测** | 同上——没有件，就没有可量的对象 |
| bge-base 档的**宿主** p50 / p95 | **17,437 ms / 17,536 ms**（n=12，min 17,144 / max 17,930 / mean 17,448） | **本会话实跑**（`build/wp5b2-latency.log`；形态同 `build/latency_probe.py`：逐条 + 右 PAD 512 + 掩码 + `token_type_ids` 全 0，Python LiteRT）；前轮同形态记录 p50 **16,843 ms / p95 17,051 ms**（`tools/dense_build/README.md` §8.4） |
| 小档（现役随包件）**宿主** p50 | **2,582 ms**（n=12，min 2,532 / max 2,665） | **本会话实跑**（同一份日志；前轮记录 2,545 / 2,533 ms，同量级） |
| 现役小档**真机** p50（历史，本轮未重跑） | **71ms**（2 线程，模拟器 API 34 x86_64） | Stage-3 实测；`tools/dense_build/README.md` §7.1 |

### 4.2 逐样本表（**宿主同形态复测**，本会话实跑；单位 ms）

| # | query token 长度 | bge-small（现役随包件，62.4 MB） | bge-base（第三轮最终件，126.2 MiB） |
|---|---|---|---|
| 00 | 57 | 2,608 | 17,930 |
| 01 | 48 | 2,580 | 17,517 |
| 02 | 46 | 2,562 | 17,424 |
| 03 | 47 | 2,538 | 17,144 |
| 04 | 49 | 2,532 | 17,353 |
| 05 | 50 | 2,552 | 17,448 |
| 06 | 52 | 2,614 | 17,510 |
| 07 | 56 | 2,612 | 17,426 |
| 08 | 55 | 2,665 | 17,370 |
| 09 | 49 | 2,621 | 17,257 |
| 10 | 37 | 2,567 | 17,461 |
| 11 | 44 | 2,585 | 17,536 |
| — | **p50** | **2,582** | **17,437** |
| — | **p95** | **2,621** | **17,536** |
| — | min / max | 2,532 / 2,665 | 17,144 / 17,930 |

### 4.3 为什么判据②判"不达"（把算术摊开）

1. **量级差**：宿主同形态 **6.75×**（17,437 / 2,582）——与该档/小档的 **FLOPs 比 ~6.75×** 逐位吻合，
   说明这个倍数是**结构性**的，不是某一轮的负载噪声。
2. **外推（两种独立算法同量级）**：小档"宿主 2,545 ms → 真机 71 ms"≈ **36×** ⇒ base 档真机 **≈ 484 ms**；
   按比值外推 71 ms × 6.75 = **479 ms**。两者都 **> 213ms 硬线（约 2.2×）**。
3. **"宿主端单条编码 ≤1.5 s 量级"这条目标对 base 档按算术不可能达成**：即便改用小档里最快的
   `tf_converter_drqt` 路线（宿主 856 ms）乘同一 FLOPs 比，下限也在 **5.8 s** 量级。
4. **为什么宿主慢不是"再优化就能过"**：`flatbuffer_direct_keepint8` 的"int8 存储"是靠
   `DEQUANTIZE` 算子**每次推理把权重还原成 fp32** 做到的（871 个算子、无 hybrid int8 内核）——
   **体积**回到了 int8 量级（126.2 MiB ✓），**速度**没有。

> **纪律说明（这条决定了本报告怎么写）**：`latency_failed` 需要**实测**支撑，而 base 档的**真机数不存在**。
> 因此本报告两层分列：**判据②的结论 = 不达**（依据是宿主实测 + 倍率来源明确的两种独立外推），
> 同时**明确标注"base 档真机逐样本表不存在"**，不把外推值填进实测栏。本报告不出现"base 档真机 ≈470ms"式的事实陈述。

### 4.4 为什么本轮没跑端侧探针（如实登记，不是漏测）

1. **件未落地**——按任务书硬前置"APK 内稠密模型件仍是现役小档 ⇒ 立即停止、不量旧模型"：判据②的判读对象是换档后的件，
   量现役小档对判据②没有信息量（现役小档的真机数 Stage-3 已出、已封存）。WP3 正是按这条**主动停手**的
   （其返回：`encodeP50/P95 以 -1 占位`、`未改任何文件`）。
2. **Kotlin 侧本轮根本编译不过**——另一条会话在 `core/database` 的飞行改动让 `:core:database:kspDebugKotlin` FAILED
   （§7 遗留 1，本会话实跑复现），仪器化测试**没有可执行的载体**。
3. **设备侧还有第二重占用**：收尾时刻 `adb` 可见的 `emulator-5554` 上另一条工作流在跑（抢同一台设备与内存是本阶段已知的
   基础设施故障模式）⇒ 即使前两条不存在，也不该在这一刻插队。

⇒ 结论：**判据② = 不达（宿主实测 + 外推）；真机数 = 缺失**。若裁定要继续换档，判据②是第一件必须补的真机数，
**且必须先解除 §7 遗留 1 的编译阻断**。

---

## 5. ⑤真机全表 + 新墙钟门与其定标依据 + APK 增量

### 5.1 真机全表（**引用** Stage-3 / Stage-4，本阶段未重跑——理由见 §4.4）

| 项 | 值 | 来源 |
|---|---|---|
| 编码对拍硬门（n=290，现役小档） | min **0.99963** / median 0.99978 / p95 0.99984；query 90 条 min 0.99973、surface 200 条 min 0.99963（**全过**，阈值 0.999） | Stage-3 `DenseEncoderParityInstrumentedTest` |
| 单条编码耗时（现役小档） | p50 **71ms** / p95 76ms / max 91ms（模拟器 2 线程） | 同上 |
| 首次用到才付的一次性开销 | `openEncoder` 34ms；`firstOrder` 391ms；`secondOrder`（稳态）93ms | `DenseFirstUseCostInstrumentedTest`（Stage-3） |
| 金标跑分（真 SQL 主集） | 主集 **0.7444（67/90）** / MRR 0.6109 / 逐章最小 0.4444；融合路由 p95 134–148ms | Stage-3 WP3 与复跑 |
| 融合路由墙钟（Stage-4 收口） | `p95=115ms p50=98ms max=2111 denseLegLive=true p95Budget=250ms p50Budget=150ms overBudgetSamples=3` | `build/wp4a2-golden-logcat.txt` |
| 19 例回归 / cross-subject / T6·W-4 | 19/19、cross-subject=0、5/5 | Stage-4 |

**bge-base 档的真机全表 = 不存在**（件未落地 + Kotlin 阻断，§4.4/§7 遗留 1）。本阶段在设备侧的独立贡献 = **只读复核**：
§3.1 的 sha/blob 对比、`check_asset.py` 10/10、以及 §5.3 的 APK 字节级测量。

### 5.2 新墙钟门：**Stage-4 已建，本阶段未重定标**（判据③未触发，**旧门不撤**）

| 形态 | 门 | 值 |
|---|---|---|
| 稠密腿**在场**（`denseLegLive=true`） | p50 ≤ `FUSED_P50_BUDGET_MILLIS` 且 p95 ≤ `FUSED_P95_BUDGET_MILLIS` | **150ms / 250ms**（各乘 `CI_MULTIPLIER`） |
| 稠密腿**掉**（纯词面回退） | p95 < `RECALL_P95_BUDGET_MILLIS`（**旧门不撤**） | **150ms** × `CI_MULTIPLIER` |

- 代码：`core/data/src/androidTest/.../GoldenRetrievalInstrumentedTest.kt`（**收尾实读**：`RECALL_P95_BUDGET_MILLIS = 150L * CI_MULTIPLIER` 在 `:558`、
  `FUSED_P50_BUDGET_MILLIS = 150L * CI_MULTIPLIER` 在 `:584`、`FUSED_P95_BUDGET_MILLIS = 250L * CI_MULTIPLIER` 在 `:585`；
  文档注释 `:560-579` 逐条写了定标依据与原始 logcat 行号，分流规则 `:194-199` / `:286-287`），
  定标依据的整理见 `docs/kb-stage4-report-2026-09-25.md` §4.4(1)。
- **定标依据的要点**：融合路由 = 词面腿 + 编码器前向（+ 资产扫描），与纯词面腿不是同一件事；
  拿纯词面腿的 150ms 门套它只会得到**一条恒红的门**。所以新门是"新路由的新门"，**不是放宽旧门**；
  腿掉时同一条探针当场红、墙钟退回旧门——"退化的那条腿不能借融合门宽松过关"。
- **旧门不撤声明**：纯词面回退路径的 `p95 < 150ms` **保持原值、一字未动**（上表第二行）。
- **本阶段为什么没重定标**：判据③写死「**门重定标只在①②都过后做**」。②不达 ⇒ 未触发。
  本阶段**没有**改任何门值、没有改 `CI_MULTIPLIER`、没有调 α / min-max / RRF k 的任何参数（收尾实跑：
  上面两条路径的 `git diff --stat HEAD` 为 0 行）。

### 5.3 APK 增量（**本会话实测 + 投影**，分开标注）

| 项 | 值 | 性质 |
|---|---|---|
| 现役 APK（含小档随包件） | **257,172,251 B**（约 245.3 MiB），`app/build/outputs/apk/localFirst/debug/app-localFirst-debug.apk`，mtime 2026-09-25 22:34 | **实测**（本会话 `os.path.getsize`） |
| 本次换件对 APK 的**实际增量** | **0 B** | **实测**（随包侧一件未换，§3.1；APK 与 Stage-3 同） |
| APK 内随包件的存放形态 | `.tflite` **stored 不压缩**（`comp_type=0`，raw=comp=62,396,488）；`.vec` deflate（17,167,873 → 10,516,445，**压缩比 0.61257**）；词表 109,540 → 54,707 | **实测**（本会话 `zipfile` 逐条读 `infolist()`） |
| 若 base 档落地：**模型件增量** | 132,375,600 − 62,396,488 = **+69,979,112 B**（+66.7 MiB，按同样 stored 不压缩计） | **投影**（体积均为实测，压缩形态沿用上面实测） |
| 若 base 档落地：**向量资产增量** | 24,574,209 × 0.61257 − 10,516,445 ≈ **+4,536,865 B**（+4.3 MiB，按 .vec 实测压缩比） | **投影** |
| 若 base 档落地：**APK 增量合计** | ≈ **+71.1 MB**（+67.8 MiB）——**且这还没算** `bge-base` 会不会改变 LiteRT 运行时那 25.9 MB 那块的形状 | **投影（估）** |
| 小档口径的三块账（历史，Stage-3 已量化） | 模型 62.4 + LiteRT 运行时 25.9（三 ABI，arm64 单片 8.75）+ 向量资产 17.3 原始 / 10.6 压缩 ≈ **+98.9 MB** | `tools/dense_build/README.md` §7 |

> 投影的措辞纪律：base 档只落地过到 `build/`，**从未进过 APK**，所以"APK 增量 +71.1 MB"是**按实测字节与实测压缩比算的投影**，
> 不是"测出来的 APK 差"。真正落地后应以两次构建的 `git` 外维度（`unzip -v` 逐条）复核。

---

## 6. ⑥结局分支对应的诚实结论

### 6.1 结局 = **延迟硬线不达 ⇒ 未落地**（`latency_failed` 分支）

| 判据 | 实测 | 结论 |
|---|---|---|
| ①编码器对拍 **≥ 0.999**（全量 28,931 行） | docs **0.999127567** / queries **0.999171495** | **过**（第三轮 GPTQ 误差补偿口径） |
| ①′金标融合主集 **≥ 0.7444** | **0.7889（71/90）** | **过**（净增 4、0 丢失） |
| ②真机单条编码 **p50 ≤ 213ms** | 真机数**不存在**；宿主同形态 17,437 ms ⇒ 外推真机 ≈470–485 ms | **不达** |
| ③门重定标 | 未触发 | **未做**（旧门不撤） |

⇒ **不落地的判决来自判据②**。前两轮曾因 ①的编码器对拍（docs 0.998638）判"质量关未过"——
**第三轮已把这一关跑过**（`model-manifest.json` → `modelEntries["bge-base-zh-v1.5"].gate.passed = true`），
本轮换的是**另一条腿**（延迟）挡住了落地。**这两轮的读数不矛盾**：第一轮 0.947814 → 第二轮 0.998638 →
第三轮 0.999128 是同一口径下的三次迭代（前两轮的归因见 `tools/dense_build/README.md` §8.3.2/§8.3.4）。

**同时成立的第二重事实（逐字登记，见报告抬头）**：**共享工作树被另一会话未完成的重构阻断** ⇒
**Kotlin 侧验证（JVM / 仪器化）本轮记 UNVERIFIED（外部阻塞）**、**落地待共享树恢复**（对方补齐或用户裁定）。
也就是说：本轮**既没有"换成功"可报，也没有"试过而失败"可报**——判据层面的结论是②不达，执行层面的结论是验证空缺。

### 6.2 回退清单：**0 个文件**（从未覆盖，故无需回退）

按任务书的"若已是小档原样 ⇒ 回退为无操作"分支，本阶段**未执行任何 `git checkout --`**。逐项复核（收尾实跑）：

| 项 | 复核方式 | 结果 |
|---|---|---|
| `bge-small-zh-v1.5-int8.tflite` | `sha256sum` + `git diff --stat HEAD` | 62,396,488 B / `015b2315…`；该路径 diff **0 行** ✅ |
| `bge-small-zh-int8.vec` | 同上 | 17,167,873 B / `cdf93650…`；diff 0 行；== `DenseRecallAssembly.kt:90` 常量 ✅ |
| 旁车 `.vec.json` | 同上 | `01e631c0…`；diff 0 行 ✅ |
| `DenseRecallAssembly.kt` | `git diff --stat HEAD` | diff **0 行**（`MODEL_ASSET_PATH` 仍指小档件） ✅ |
| 生产三路径整体 | `git diff --stat HEAD -- <三路径>` | **0 行** ✅ |
| base 档的 `.vec` | 只在 `build/wp2r3-quant/bge-base-zh-int8.vec`（24,574,209 B / `4484a739…`） | 从未写到随包路径 ✅ |

**保留未回退的工具链改动**（第三轮 WP2 的未提交改动 + 第三轮 WP3b 判定保留）：`dense_asset.py` / `export_bge_int8.py` /
`convert_onnx_to_tflite.py` / `README.md` / `model-manifest.json` / 两支新脚本。理由（WP3b 的逐条判定，本报告认同）：
① 小档 profile 与 HEAD 逐字段相同（默认链不变，新增全是显式开关）；② 回退会断掉第三轮证据链（`--weights-ongrid` 与
`keepint8` 路线只存在于第三轮版本里）；③ 回退会把第三轮实测记录从入库文件里删掉，留下已被推翻的旧归因。

### 6.3 本阶段**实跑**过的门（当前会话结果，不是引用）

| 命令 | 结果 |
|---|---|
| `python tools/ci/run_kb_checks.py` | **EXIT=0**：gates / consistency / roundtrip / manifest / **dense** 全 OK（日志 `build/wp5b2-kb-checks.log`） |
| `python tools/dense_build/check_asset.py` | **EXIT=0，10/10 OK**：旁车 sha（`533eab121d77e3da`）、词表（`45bbac6b…`）、tokenizer（`48cea5d44424912a`）、`.vec`（`cdf93650b948a091`）、`vectorHeader dim=512 count=28931`、`vectorBytes 17167873 B`、**`layout：ids 与包布局逐条一致（28931 条向量）`**、`modelManifest 三方一致`（日志 `build/wp5b2-check-asset.log`） |
| `python -m unittest discover -s tools/tests -t tools -p "test_dense_asset_gate.py"` | **Ran 6 tests, OK**（含三组反侧：包内容变/词表改/行数不符必须红；日志 `build/wp5b2-dense-gate.log`） |
| `sha256sum tools/kb_coverage/tables/golden_queries_v1.json` | `7c004b76…`（与写死值一致） |
| `./gradlew :core:data:testDebugUnitTest --tests "*Dense*" --rerun` | **BUILD FAILED in 6s**，`:core:database:kspDebugKotlin FAILED`（外部阻塞，§7 遗留 1；日志 `build/wp5b2-dense-jvm.log`） |

### 6.4 若裁定"继续换档"：跨过判据②的路径（**都不是本阶段能自行决定的**）

| 路径 | 代价 | 性质 |
|---|---|---|
| ① 让 base 档走 hybrid int8 内核（`tf_converter_drqt`） | 该路线在 base 档**尚未跑通**（onnx2tf 把 3-D 中间张量按 NCHW 误判后转置：`wa/backbone_module/embeddings/Add_1` 报 `Dimensions must be equal, but are 512 and 768`；`-kat` 无效，原文 `build/odrqt-test/base-kat.log`） | 纯工程，不动判据 |
| ② 换更小的大档 / 降 seq 长度 | 会动图契约与离线口径 | **需用户裁定**（超出"只换一支模型"的授权） |
| ③ 放宽 213ms 硬线 | — | **这是改判据**，需用户明示裁定，**本阶段不做、也不建议由代理提** |

> 无论走哪条：**先解除 §7 遗留 1 的编译阻断**，否则真机硬门依然无法执行。

---

## 7. ⑦UNVERIFIED 与遗留

1. **Kotlin 侧验证本轮 UNVERIFIED（外部阻塞，逐字登记）**：另一条会话在改 `core/database`（未提交）——
   三实体（`TutorSessionEntity` / `TutorTurnResponseEntity` / `TutorSessionProblemAnchorEntity`）随
   `entity/TutorInteractionEntities.kt` 删除而**全树无定义**（本会话实跑 `grep -rn "class TutorSessionEntity…" core/` 零命中），
   三个 DAO 仍引用它们，**且** `StudyDatabase.kt:352` 新引用 `TUTOR_CONVERSATION_AREA_MIGRATION_51_52` 而该常量
   **工作区与 HEAD 都无定义**（`grep -rn "val TUTOR_CONVERSATION_AREA_MIGRATION_51_52" core/` 与
   `git grep … HEAD -- core/` 均为空）。本会话实跑 `./gradlew :core:data:testDebugUnitTest --tests "*Dense*" --rerun`
   → `:core:database:kspDebugKotlin FAILED`（`MissingType` ×3 + `RoomProcessor was unable to process '…StudyDatabase'`），
   **BUILD FAILED in 6s**，**未执行任何测试任务**。⇒ 测试任务**未获得**（既不是通过，也不是本阶段造成的失败）；
   本阶段自有内容最后一次通过的编译/测试证据均在**更早的会话**（`build/gradle-dense-tests.log` 21 tests / 0 failures 等）。
   **按纪律未碰那条会话的任何文件**，也不由本代理"顺手修好"。**落地待共享工作树恢复**。
2. **外部榜单一律未独立复核**：C-MTEB Retrieval 69.49 / 61.77 来自 **Stage-5 任务书**，仓内无第二来源；
   `F2LLM` / `gte-large-zh` 的 `grep` 命中**只有本报告自身**（写入前 0 命中）。按 §11.3 口径，这些属"单一来源待验证"。
3. **base 档 `.tflite` 的全量 290 条宿主对拍未跑完**：`build/wp2r3-parity-final.log` 给出的是 **id/行对齐自证
   min 0.999918149** 与输入输出签名；tflite vs int8 ONNX 的 **n=12 子集 min 0.999879** 来自 WP2 第三轮返回，
   **收尾未独立复核**（`build/` 下未寻到对应落盘日志）；`README` §8.6 的"290 条"那格仍写着"跑完即写"。
   按 WP2 的记录成本 17 s/条 ⇒ 全量约 82 min，**本轮未跑**。
4. **base 档真机延迟数不存在**（§4.4）：本报告一切"≈470–485 ms"都是**外推**；不得引用为实测。
5. **本轮未重跑任何设备测试**：§5.1 全部是 Stage-3 / Stage-4 的日志引用；`emulator-5554` 收尾时被另一条工作流占用。
6. **`build/*` 证据产物不入版本库**（`.gitignore` = `**/build/`）：本报告引用的 json / log / logcat 只在本机磁盘，
   clone 后需按 §8 的命令重跑才能复算（仓库既有约定，Stage-1/2/3/4 同）。
7. **两轮口径的数并存，判读以当前口径为准**：bge-base int8 的 docs 逐行 cosine 最小 = 第一轮 0.947814 →
   第二轮 0.998638 → 第三轮 **0.999127567**；MRR 在第三轮为 **0.62685**（上一版报告误写 0.6343 = fp32 臂的数，已更正）。
   这是**前后轮**，不是矛盾。
8. **`%TEMP%/`、`.agent_*`、`out/`、`tmp/` 等脏文件属其它会话**：本次提交一律不碰（§8 的清单是显式的）。
9. **一条报告卫生**：本报告 2026-09-26 00:33 的上一版结论是"判据①不过 ⇒ 未换档 / 延迟未测"，
   那是**第三轮之前的中间态**；本版按第三轮实测整篇重写，**上一版的结论已被本版取代**（不是并行两说）。

---

## 8. 复核方式（可重跑）

```bash
# ① 判据①（编码器对拍，本档口径）——重算 int8 ONNX vs torch fp32（全量 28,931 + 90）
python tools/dense_build/export_bge_int8.py --model bge-base-zh-v1.5

# ② 判据① 的宿主下游（tflite vs int8 ONNX；全量 290 条约 82 min，本轮未跑完）
build/tflite-venv/Scripts/python.exe tools/dense_build/check_tflite_parity.py --model bge-base-zh-v1.5

# ③ 离线融合主集（bge-base 臂）= §2.1 的数
python build/wp2_golden_candidate.py          # 判分器 import 自 stage3_expectation.py

# ④ 逐题净增 / 逐章（§2.1 / §2.2）
python build/stage2-dense-work/wp1_bgebase_stage3_caliber.py

# ⑤ 词表同一性（§2.3）
python build/stage2-dense-work/wp1_vocab_identity.py

# ⑥ 随包侧"一件未换"（§3.1 / §6.2）
git diff --stat HEAD -- core/data/src/main/assets/dense/ core/data/src/main/resources/knowledge/dense/ \
                        core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/knowledge/dense/
sha256sum core/data/src/main/assets/dense/bge-small-zh-v1.5-int8.tflite \
          core/data/src/main/resources/knowledge/dense/bge-small-zh-int8.vec \
          core/data/src/main/resources/knowledge/dense/bge-small-zh-int8.vec.json \
          core/data/src/main/resources/knowledge/dense/bge-small-zh-v1.5-vocab.txt

# ⑦ 延迟硬线（§4）：宿主同形态逐样本（本报告 §4.2 的表）
build/tflite-venv/Scripts/python.exe build/latency_probe.py     # 摘要版（p50/min/max + 比值）
#    收尾那一次是同一形态的内联脚本，输出落 build/wp5b2-latency.log（逐样本）

# ⑧ 门与契约（Python 侧）——收尾实跑，本结果 = 本会话的真实运行
python tools/ci/run_kb_checks.py                       # EXIT=0：gates / consistency / roundtrip / manifest / dense 全 OK
python tools/dense_build/check_asset.py                # EXIT=0：10/10 OK（含 layout：ids 与包布局逐条一致 28,931 条）
python -m unittest discover -s tools/tests -t tools -p "test_dense_asset_gate.py"   # Ran 6 tests, OK

# ⑨ JVM / 仪器化侧——**本次收尾未能跑**（外部阻塞，见 §7 遗留 1；下面两条是复跑命令，不是本会话结果）
./gradlew :core:data:testDebugUnitTest --tests "*Dense*" --rerun
./gradlew :core:data:compileDebugAndroidTestKotlin

# ⑩ APK 字节复核（§5.3）
python -c "import zipfile;z=zipfile.ZipFile('app/build/outputs/apk/localFirst/debug/app-localFirst-debug.apk');print([(i.filename,i.compress_type,i.file_size,i.compress_size) for i in z.infolist() if 'dense/' in i.filename])"

# ⑪ 金标集冻结复核
sha256sum tools/kb_coverage/tables/golden_queries_v1.json    # 应为 7c004b76…
```

**纪律自查**：本阶段**未**增删改金标集（`7c004b76…` 不增不减不改，收尾实跑复核）、**未**动预注册线（0.75/0.60）、
**未**动门值（既有融合门 150/250ms 与旧门 p95 150ms 一字未改）、**未**动融合参数（α=0.5 / min-max / RRF k=60）、
**未**在判官上调参、**未**做多候选赛马或 MRL 截维实验、**未**碰 `tools/kb_build/tables/chapter_map.csv` 与 `.worktrees/`。
