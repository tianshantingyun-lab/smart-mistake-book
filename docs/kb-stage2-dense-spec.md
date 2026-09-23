# Stage-2 · dense 大档规格（同层兜底叠加在词面之上）——只写规格、不施工（2026-09-23 立）

> **回答的问题**：Stage-1 已判「词面栈不达标」（判读线在 `docs/kb-lexical-stage1-experiments.md` §1，机械判读结论在 `build/stage1-experiments.md` §10：实测 主集 0.6556 / 逐章最小 0.2222 ⇒ **未过线**，dense 转立项）。本规格回答的是**下一步怎么做才叫决策就绪**：先用零端侧成本的离线实验拿到 dense 的第一个真实数字（**当前一个都没有**），达标才谈端侧化；端侧化本身仍需用户另一次裁定。
> **血缘**：形态预案 `docs/kb-vector-topic-decision.md` §4（档位/运行时/融合/无 ANN，2026-09-23 按用户裁定改写）+ §7 附录 A（更正与外部证据）；Stage-1 判读线与臂定义 `docs/kb-lexical-stage1-experiments.md`；WP-D 生产方案 `docs/kb-fts5-production-plan.md`（词面腿的施工形态）；审计 `.jez/artifacts/research-brief-kb-architecture-audit.md` §2.4-1 / §4 R4-2b / §9。
> **性质**：**规格 + 第一步可执行协议**。本文件不施工、不改任何代码、不改金标集、不调任何判读线。§2 的协议在跑出第一个数之前写死；§3 是达标后的施工预案（**立项需用户裁定**，见 §3.7）。

---

## 1. 目标与判据（出数前写死，出数后不改）

### 1.1 目标

把 dense 作为**同层兜底**叠在词面之上：词面腿仍用 SQLite FTS5 `bm25()`（生产形态见 `docs/kb-fts5-production-plan.md`），dense 腿只在**同一候选域**（按科隔离 + 可信状态过滤，可选章门控）里补"同义表述/自然语言问句 → 词条式节点名"的缺口——题面是问句、预期节点名是词条（Stage-1 MISS 的共同形态，`docs/kb-vector-topic-decision.md` §3 的 MISS 形态段），两者公共 2/3-gram 少，二值 TF 排序无从发力；dense 腿做的是"意思近"，不依赖字面重合。

**它消灭的具体失败**（第 12.2 条）：Stage-1 臂 A 的 31 例 MISS 里，题面与节点名**无公共特征或公共特征极弱**的那一类（如"为什么…？"→"合力范围"、"…到底看什么？"→"矢量和标量"，逐题清单见 `build/stage1-metrics-A.txt` 的 MISS 段）——词面腿在这类题上**结构性无解**（不是参数问题，判据是字面），dense 腿是这类题的唯一可达路径。**不做通用 RAG**：不扩到跨科、不扩到全库语义问答。

### 1.2 判据（预注册；判读对象 = 冻结金标 90 条）

| 项 | 值 | 出处 |
|---|---|---|
| 判读集 | 冻结金标 90 条（4 科 / 10 章 × 9 条），sha256 `7c004b763bdd49556e11ff1c9500c9461b09a7383b77f230fa8fd35754e6ae39` | `tools/kb_coverage/tables/golden_queries_v1.json(.sha256)`；两测运行时复核 |
| 指标 | 主集 Recall@5 / **逐章最小** Recall@5 / MRR / 逐题 MISS 清单 | 与 Stage-1 同口径（`docs/kb-lexical-stage1-experiments.md` §2 共用口径） |
| 命中定义 | 前 5 名里存在 `knowledgeNodeId` 以 `:atomic:<expectedSlug>` 结尾的节点（top-5、按科隔离、可信状态过滤同生产） | `RetrievalBenchmark.kt:339` `SCORED_TOP_K=5`、`:347` `TRUSTED_VERIFICATION_STATUSES` |
| **主判据（门槛）** | **主集 Recall@5 ≥ 0.75 且 逐章最小 ≥ 0.60**（两条同时满足） | Stage-1 判读线原值（`docs/kb-lexical-stage1-experiments.md` §1），同一套，不改 |
| **附加判据（机制自证）** | 融合臂主集 **必须 > 臂 A 的 0.6556**；否则判"叠加 dense 未消灭任何词面腿的失败面"，机制不成立（第 12.2 条） | 本规格 |
| 对比对象（参照，不是门槛） | 臂 A `0.6556 / 0.2222 / 0.5569`；章门控上界 C-upper `0.7889 / 0.5556 / 0.6444`；v1 裸 B5 基线 `0.5444 / — / 0.1511` | `build/stage1-metrics-A.txt`、`build/stage1-metrics-C-upper.txt`、`docs/kb-vector-topic-decision.md` §3.2 第 3 组 |
| 主配置（写死） | dense-only 一臂 + **归一化分数融合 α=0.5** 一臂（两臂都判读）；其余 α、RRF、章门控变体**只作诊断**（标注非判读数） | §2.4 |

**三种结果的归宿**（先写死，免得数出来后找理由）：

1. **主判据过 + 附加判据过** → dense 有效，进入 §3 端侧化**立项讨论**（仍不自动施工）。
2. **主判据过 + 附加判据不过**（融合没超过词面腿）→ dense 对这批题面没有增量；不立项，记录数并回报用户（词面腿已是这套数据的上限附近）。
3. **主判据不过** → 不立项；同时把"这 90 条里 dense 也捞不回的题"归入内容/绑定问题（可能指向 A-18 抽样绑定正确率类的内容缺陷），不在检索器上继续加机制。

### 1.3 判分口径：与 Stage-1 逐字一致（不许另立一套）

- 分词/索引侧：生产提取器 `KnowledgeSearchFeatureExtractor.fromNode`（`core/database/src/main/kotlin/.../KnowledgeSearchFeatureExtractor.kt:44`）——**不另写一份分词**。
- 词面腿：FTS5 + `bm25()`，k1=1.2 / b=0.75 硬编码不可调（`docs/kb-lexical-stage1-experiments.md` §3）；索引域 = 全量节点 3,970 篇（含 TOPIC）——**N/avgdl/df 是整表上的量**，索引域一改，判读数不可比（`build/stage1-experiments.md` §1）。
- 候选过滤：按科隔离 + `verification_status ∈ {CURATED, SOURCE_GROUNDED, USER_CONFIRMED}`，写在 SQL 的 WHERE（`RetrievalBenchmark.kt:347`）。
- 逐题 MISS 清单、逐章分布（10 章 × 9 条）、MRR（最优名次）——格式与 Stage-1 指标文件同族（`build/stage1-metrics-A.txt`）。
- 复算入口（词面腿的权威实现，不重写）：`./gradlew.bat :core:data:testDebugUnitTest --tests "*Stage1LexicalLabTest*" --rerun`（`core/data/src/test/kotlin/.../Stage1LexicalLab.kt`）。

---

## 2. 第一步：离线质量验证（Python，零端侧工程）

**目标**：在 PC 上拿到 dense 腿与融合臂的真实数字，**不碰 Android 侧任何代码**。本步不达标 → §3 全部不做。

### 2.1 候选模型与坐标

| 角色 | 模型 | 维度 / 层数 / 最大长度 | 参数 | 许可证 | 坐标与封存 |
|---|---|---|---|---|---|
| **主候选（大档）** | Qwen3-Embedding-0.6B | 1024（MRL 支持 32–1024）/ 28 / 32K | 595,776,512（BF16） | **apache-2.0** | `Qwen/Qwen3-Embedding-0.6B`，sha `97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3`（2026-04-20） |
| **下限对照** | bge-small-zh-v1.5 | 512 / 4 / 512 | 24M 量级（审计口径） | **mit** | `BAAI/bge-small-zh-v1.5`，sha `7999e1d3359715c523056ef9478215996d62a620`（2023-10-12） |

- **Qwen3 的 C-MTEB**：模型卡表格逐字 `C-MTEB / Qwen3-Embedding-0.6B / 0.6B / 66.33 / … / Retr. 71.03`——决策文档 §4/附录 A.5 曾按"单一来源待验证"记录，本次已按模型卡原文复核（通道见 §2.3）。
- **文本构造差异（先写死，不许临场试）**：
  - Qwen3：查询前缀 = `Instruct: {task}\nQuery:{query}`，`task` 固定为一句英文任务描述（建议 `Given a Chinese textbook question, retrieve the knowledge point that answers it`）；**文档侧不加指令**（模型卡原文 "No need to add instruction for retrieval documents"）；池化取 last token、L2 归一化。
  - bge：查询前缀 = `为这个句子生成表示以用于检索相关文章：`（模型卡 `query instruction for retrieval [1]` 列）；**文档侧不加指令**；CLS 池化、L2 归一化。
  - 两模型的**文档文本 = surface 原文**（canonicalName 或 alias 单条），**不拼 boundary 摘录**：bge 的 `max_position_embeddings=512`，boundary 动辄数百字，拼进去会被截断成"半句噪声"（= 新的截断缺陷）；若要做"节点正文向量"变体，必须单列为诊断臂。

### 2.2 语料：28,932 条向量（复算，可复跑）

```
python -c "
import json
d=json.load(open('core/data/src/main/resources/knowledge/moe-2025-four-subjects-v1.json',encoding='utf-8'))
a=sum(len(t['knowledgePoints']) for s in d['subjects'] for t in s['topics'])
al=sum(len(k.get('aliases') or []) for s in d['subjects'] for t in s['topics'] for k in t['knowledgePoints'])
tp=sum(len(s['topics']) for s in d['subjects'])
print('atomic',a,'topics',tp,'docs',a+tp,'aliases',al,'vectors',a+al)"
# 实测输出：atomic 3572 topics 398 docs 3970 aliases 25360 vectors 28932
```

- 向量集 = **3,572 条 canonicalName 向量 + 25,360 条 alias 向量 = 28,932**（与审计 §2.4-1 口径一致；`docs=3970` 与 Stage-1 指标文件头 `index: docs=3970` 一致）。
- 查询侧表：90 条题面 + 10 章 + 4 科（字段 `query/expectedSlug/subject/chapter`，取自同一金标 JSON）。
- **多向量 → 节点分**：节点分 = 该节点全部向量在本次查询下 cosine 的 **max**（别名向量存在即消除"别名被截断"这一失败类：KD-24，`docs/known-defects.md`）。同一节点多向量取 max 而不是 mean——因为每一次召回只要"有一条表述对得上"就该召回，mean 会被无关别名稀释（这是本规格的设计决定，不再是扫描项）。

### 2.3 离线跑法与命令（CPU，零端侧）

环境事实（本会话实测，2026-09-23）：`python 3.13.14`、`torch 2.13.0+cpu`、`numpy 2.5.1` 已就位；**`transformers` / `sentence-transformers` 未安装**（`import` 报 ModuleNotFoundError）→ 第一步必须先装。`huggingface.co` 直连**超时**（与决策文档附录 A.5 记录一致），`https://hf-mirror.com` 的 HF 兼容 API **可达**（本规格的模型卡/许可证/sha 即经此通道取回）。

```bash
# 0) 依赖（只装到本机 python 环境，不进仓库）
python -m pip install "sentence-transformers>=3" transformers

# 1) 取模型（HF 官方直连不可达时用镜像；ENERGY/沙箱环境按需加 -m）
HF_ENDPOINT=https://hf-mirror.com python -c "
from huggingface_hub import snapshot_download
print(snapshot_download('Qwen/Qwen3-Embedding-0.6B'))
print(snapshot_download('BAAI/bge-small-zh-v1.5'))"

# 2) 词面腿分数导出（Kotlin 侧唯一实现，不在 Python 里重造 FTS5 口径）
./gradlew.bat :core:data:testDebugUnitTest --tests "*Stage1LexicalLabTest*" --rerun
#    改造点（第一步唯一的代码动作，测试侧）：把臂 A 的「每查询 × 每节点 bm25 分数」导出为 TSV
#    （列：query_id, node_id, bm25_score）→ build/stage2-dense-offline-lexical.tsv
#    理由：FTS5 的 bm25 只有一份口径（A≡A' 已证），Python 侧重算等于第二实现 = 漂移源。
```

**编码与检索**（一次性脚本，落 `build/` 之外的临时目录亦可，不写进仓库）：

- 编码：`SentenceTransformer(model, device='cpu')`，`batch_size=64`，`normalize_embeddings=True`，`show_progress_bar` 开；Qwen3 用其 `Instruct/Query` 前缀构造（模型卡用法段逐字格式）。
- 检索：**暴力余弦**（归一化后即点积），`query_vecs(90×d) @ doc_vecs(28932×d).T` → (90×28932) fp32。
  - 内存账：Qwen3 1024 维 fp32 文档矩阵 = 28,932×1024×4 B = **118,505,472 B ≈ 113.0 MiB**；bge 512 维 = **59,252,736 B ≈ 56.5 MiB**（后者与决策文档 §A.2 的 fp32 复算值逐字一致）。90×28932 的打分矩阵 ≈ 10.4 MB。**不需要 ANN**（决策文档 §4「无 ANN 保持」，§A.2 全扫 12–23ms 是端侧 int8 口径，PC 上只会更快）。
  - 确定性：CPU、fp32、无随机采样；同一模型同一文本重复编码结果一致（同一 `torch` 版本内）。

### 2.4 判分与融合实验（主 / 对照，出数前写死）

| 臂 | 定义 | 参评 |
|---|---|---|
| **D-only** | 纯 dense：节点分 = 该节点向量 cosine 的 max，取前 5 判分 | ✅ 主 |
| **D-fuse-α0.5** | **归一化分数融合**（主）：`score = 0.5·dense_norm + 0.5·lex_norm` | ✅ 主 |
| D-fuse-α0.3 / α0.7 | 同一融合、α 变体 | ❌ 诊断 |
| D-rrf-60 | **RRF 对照臂**：`Σ 1/(60 + rank_leg)`（词面腿与 dense 腿各出一份名次） | ❌ 对照（参评与否写死：**不参评**，只回答"归一化是否优于 RRF"） |
| Lex-A | 词面腿（臂 A 原样，来自 §2.3 的 TSV） | 参照（Stage-1 判读数 0.6556/0.2222/0.5569） |
| C-upper | 章门控上界 0.7889/0.5556（Stage-1 天线口径，不可上生产） | 参照（回答"还差多少"） |

- **归一化口径（写死）**：每次查询、每条腿**在该查询的候选域内**做 min-max → [0,1]（词面腿先取 `-bm25`，因为 FTS5 的 `bm25()` 返回负分、升序即最优；dense 腿 cosine 已在 [0,1] 附近）。候选域 = 按科 + 可信过滤后的并集；某腿对某节点无分 = 该腿给 0（不参与 min-max）。
- **融合参数不许在判官上调**：α=0.5、RRF k=60、min-max（而非 z-score）在出数前写死；α 变体与 z-score 变体只进诊断段（与 Stage-1 §3「不许在判官上调参」同纪律）。要提升指标，只能改检索器/索引/内容或换模型，不许改判读配置。
- 每条臂都出：主集 Recall@5、逐章 10 值 + 逐章最小、MRR、逐题 MISS（`rank|absent`）。
- **dense 达标后不得回头改词面腿**：融合臂的词面腿输入必须是 §2.3 导出的同一份 TSV（哈希记入环境文件），两侧不同源 = 数据作废。

### 2.5 产出文件与形状

| 文件 | 形状 |
|---|---|
| `build/stage2-dense-offline-env.txt` | 环境与坐标：python/torch/numpy/sentence-transformers 版本；两个模型的 repo id + sha + license + 维度/最大长度；文档数 28,932（3,572+25,360）；词面腿 TSV 的 sha256；金标 sha256 |
| `build/stage2-dense-offline-metrics-<arm>.txt` | 每臂一份（`<arm>` ∈ `D-only-qwen3` / `D-fuse-a0.5-qwen3` / `D-rrf60-qwen3` / `D-only-bge` / `D-fuse-a0.5-bge` / 诊断臂），格式与 `build/stage1-metrics-A.txt` 同族：头 5 行（route/model/golden/index/k1b）+ `Recall@5(主集)` + `MRR` + 逐章 10 行 + MISS 清单 |
| `build/stage2-dense-offline-summary.txt` | **一张对比表**（下），后面接「未过线时的 MISS 归因段」 |
| `build/stage2-dense-verdict.json` | 机械判读输入（对齐 `build/stage1-verdict.json` 形状）：`{model, arm, main, chapterMin, mrr, gate:{main:0.75,chapterMin:0.60}, baselines:{armA:0.6556,baseline:0.5444,cUpper:0.7889}, passed, notes}` |

对比表（列写死，行随测到的臂）：

| 臂 | 模型 | 主集 Recall@5 | 逐章最小 | MRR | 相对臂 A | 相对 v1 基线 | 判读 |
|---|---|---|---|---|---|---|---|
| D-only | Qwen3-0.6B | 待实测 | | | | | |
| **D-fuse-α0.5** | Qwen3-0.6B | 待实测 | | | | | **主判读** |
| D-rrf-60 | Qwen3-0.6B | 待实测 | | | | | 对照 |
| D-only / D-fuse-α0.5 | bge-small-zh | 待实测 | | | | | 下限对照 |
| Lex-A（参照） | — | 0.6556（59/90） | 0.2222 | 0.5569 | — | +11.1pp | Stage-1 判读数 |
| v1 裸 B5（基线） | — | 0.5444（49/90） | 0.2222 | 0.1511 | −11.1pp | — | 生产现状 |
| C-upper（上界参照） | — | 0.7889（71/90） | 0.5556 | 0.6444 | +13.3pp | +24.5pp | 不可上生产 |

> 表里的 `待实测` 是**本规格的真实状态**：dense 在这套金标上**一个已测数字都没有**（决策文档 §4 的形态是预案，不是结果）。

---

## 3. 端侧化（**仅在 §2 达标后**；开工前需用户裁定，见 §3.7）

序号即依赖顺序；每一步的产物与门都写死。

### 3.1 模型转换与量化

- 路线（二选一，转换后**必须重跑 §2 判分协议**，量化件不重跑等于没测）：
  - ONNX：`optimum-cli export onnx --model Qwen/Qwen3-Embedding-0.6B out/` + 动态 int8（`onnxruntime.quantization.quantize_dynamic`）；
  - LiteRT：AI Edge 工具链导出 `.tflite`（Qwen3 系 LLM-ish 结构（`Qwen3ForCausalLM`）导出路径的成熟度**未验证**，见 §5）。
- 量化后的质量退化**必须由 §2 的同一套判分给出数**（不许用"通常掉 1-2 个点"这类外部经验替代）。参照锚点（审计记录，非本项目实测）：bge-small-zh-v1.5 int8 ONNX **23.9MB**、GGUF Q4_K_M **15.45MB**（后者 4-bit 质量退化无公开证据 = §5 未验证项）。
- **Qwen3-0.6B 的 int8/4-bit 端侧件体积与质量退化，本规格不给预测值**（无可信来源）——转换后实测并记录。

### 3.2 Kotlin tokenizer 方案（含许可证陷阱）

| 资产 | 约束 |
|---|---|
| `sentence-embeddings`（Kotlin/Android 侧句向量） | **必须钉 v6**，否则 4KB 页不合规（16KB 设备兼容）；见决策文档 §A.4 |
| AI-Pocket-Chat | **GPL-3.0，不可抄码**（copyleft 传染） |
| onnx-community 转换件 | **无 license 声明**——不可分发、不作依赖 |
| bge-small-zh 原版 | **MIT，可再分发**（作下限对照/备选） |

- Qwen3 的 tokenizer = BPE，vocab 151,669（`config.json` 实测），随包 `tokenizer.json`；bge = BERT WordPiece，vocab 21,128。**Android 侧可用的 Kotlin/Java 实现需要在立项后做一次选型探针**（HF `tokenizers` 的 Android 绑定成熟度、`sentence-embeddings` 的 v6 能力边界，都属 §5 未验证项）。
- 硬要求：**端侧 tokenizer 必须与 §2 离线侧逐条同结果**（对 28,932 条 surface + 90 条查询做 id 序列对拍，不一致即停）。这条是"离线数字能不能搬到端侧"的唯一保证——否则离线 0.75 在端侧可能不是同一个模型在跑。

### 3.3 运行时

- 候选：**LiteRT 5.25MiB** 或 **ORT arm64 24.63MiB**（决策文档 §4 引用 Stage-0 实测，两者**均 16KB 页对齐合规**；**本会话未独立复核**，见 §5）。
- `sqlite-vec` **维持不引入**（`vec0.so` `p_align=0x1000` 不满足 16KB 页合规；见 §A.5）。
- 选型判据（立项时定，不在本规格锁死）：包体增量、冷启、单次前向 p95、维护状态与许可证。

### 3.4 向量层与存储

- **暴力余弦 in Room 优先**（决策文档 §4「无 ANN 保持」）：28,932×d 全扫。端侧成本锚点 = 512 维 int8 **12–23ms**（Stage-0 端侧口径，§A.2；**Qwen3 的 1024 维约为其 2 倍，且为 fp16/fp32 时更高——端侧实测前不许当成已达标**）。
- 存储账（复算）：512 维 int8 = 14,813,184 B ≈ **14.1 MiB**；512 维 fp32 = 59,252,736 B ≈ **56.5 MiB**；1024 维 int8 ≈ 29,626,368 B ≈ **28.3 MiB**；1024 维 fp16 ≈ 59.3 MiB。按"质量性能优先"（用户 2026-09-23 裁定，决策文档 §4 改写说明），预算放宽，量级不再是首要约束。
- 备选：**ObjectBox（运行时 2.39MiB）**——注意它**无 FTS/BM25**（三重证据，§A.3），所以它只能承担向量层角色，词面腿仍归 FTS5。

### 3.5 包体增量预期（诚实边界）

给构成清单，不给编造的数：模型件 + 运行时 `.so`（每 ABI）+ 向量表（§3.4 的存储账）+ tokenizer 词表。**APK 增量必须实测**（审计 §9 未验证项 9/10 仍在）。测量命令（立项后）：`./gradlew.bat :app:assembleLocalFirstDebug` 前后对 `app/build/outputs/apk/**/**.apk` 做 `unzip -l | awk` 的逐项差值 + 分 ABI 拆分。

### 3.6 真机 p95 门

- 沿用现门：**150ms × CI 系数**（本地 1×、CI 4×；`core/data/src/androidTest/.../GoldenRetrievalInstrumentedTest.kt:336`）。
- **dense 腿必须单独计时**（不与词面腿混在一个数里）：新增逐样本分段计时（词面腿 / dense 腿 / 融合），三类各自 p95 上报；门先按"融合总 p95 ≤ 150ms×CI"跑，若密集红，**是否重定标（如 250ms 档，对齐 `KnowledgeContextRetrievalInstrumentedTest.kt:482` 的 MASTERY_READ 门）需用户裁定**——不许施工者自己抬门。
- 反面教材（P95 门的真实教训）：v2 宽召回统一路由在 873,465 特征行下 p95=663/695ms，超预算 4.4×（`docs/kb-vector-topic-decision.md` §3.1）——**dense 腿的规模必须先算再上机**（§3.4 的账），不许"先上了再看"。

### 3.7 立项声明（原文，不得改写）

> 议题开启后的实施需**另一次用户裁定**——质量收益/体积增量/端侧可部署性与延迟都要真机数支撑（含 APK 增量实测，审计 §9 未验证项 9/10 仍在）。（`docs/kb-vector-topic-decision.md` §4 末条）

即：**本规格的 §1–§2 可以在不裁定施工令的前提下执行**（零端侧工程、只出数）；**§3 的每一步都要先有用户一句话**。

---

## 4. 风险与纪律

1. **dense 增益目前没有任何已测数字**——`docs/kb-vector-topic-decision.md` §4 的档位/运行时/融合都是**预案**，不是结果；本规格存在的意义就是先把这个数字补上（§2），在此之前任何"dense 行不行"的结论都是猜测。
2. **不许在判官上调参**：α、k、归一化方式、top-k、章门控开关在出数前写死；扫描只作诊断并显式标注（与 `docs/kb-lexical-stage1-experiments.md` §3 同纪律）。
3. **不许动金标集**：不增、不减、不改题面；提升指标只能改检索器/索引/内容（R3-4）。
4. **不许为 dense 去改词面腿口径**：词面腿输入固定为 §2.3 的 TSV（含 sha256）；改动即视为换判分对象。
5. **考卷与考法的风险**：90 条对 P@5 类指标已接近 IR 下限（审计 §2.3-3：50 条"clearly too small"），密集阈值（逐章 9 条）方差大——所以 §1.2 同时看主集、逐章最小与 MRR**三个数**，不靠单点。
6. **许可证纪律**（§3.2 表）：不抄 GPL / 无 license 代码；不采用 0-star / pre-1.0 库作生产依赖。

## 5. 未证实 / 待核实清单（单列，不许当事实用）

| # | 项 | 现状 |
|---|---|---|
| 1 | Qwen3-Embedding-0.6B 的 **apache-2.0** | **本会话取到证据但为单通道**：`https://hf-mirror.com/api/models/Qwen/Qwen3-Embedding-0.6B` 返回 `tags` 含 `license:apache-2.0`；`huggingface.co` 直连超时。立项前用官方通道复核一次：`curl -s https://huggingface.co/api/models/Qwen/Qwen3-Embedding-0.6B` |
| 2 | Qwen3-Embedding-0.6B 的 **C-MTEB 66.33 / Retr. 71.03** | 模型卡表格原文（同镜像通道）；决策文档 §A.5 原记"单一来源待验证"，本次升级为"模型卡原文已复核、通道单一" |
| 3 | Qwen3-0.6B **int8/4-bit 端侧件体积与质量退化** | **无任何来源**；§3.1 要求转换后实测 |
| 4 | **Kotlin/Android 侧 tokenizer** 可用实现与许可证 | 未做选型探针（§3.2）；v6 钉版规则来自决策文档 §A.4（Stage-0 取证） |
| 5 | LiteRT 5.25MiB / ORT arm64 24.63MiB 的**体积与 16KB 合规** | Stage-0 取证，**本会话未复核**（§3.3） |
| 6 | 28,932×512 全扫 **12–23ms** | Stage-0 端侧口径（§A.2）；1024 维、fp16/fp32 的端侧成本无实测 |
| 7 | ObjectBox 2.39MiB 与"无 FTS/BM25" | Stage-0 取证（§A.3），本会话未复核 |
| 8 | 本会话**实测**（可复跑）：模型 config/sha/license（镜像 API）、语料 3,572/398/25,360/28,932（本地脚本，§2.2）、环境缺包事实（§2.3） | 已核实 |

---

*本规格不施工、不改码、不动金标；§2 产出的数才是下一步裁定的输入。*

---

## 6. 判定段（2026-09-24 填数 · WP4 离线验证；§1–§5 一字未改）

> **本节只填数**：判据、判读线（0.75 / 0.60）、金标 sha256、命中定义、融合参数（α=0.5 / RRF k=60 / min-max）
> 全部按 §1.2 与 §2.4 出数前写死的值执行。§2.5 的对比表原样保留为预注册模板，实测数在本节。
> 产物：`build/stage2-dense-offline-env.txt`（环境与坐标）、`build/stage2-dense-offline-summary.txt`（对比表 + MISS 归因）、
> `build/stage2-dense-offline-metrics-<arm>.txt`（逐臂 15 份）、`build/stage2-dense-verdict.json`（机械判读输入）。

### 6.1 口径自证（先证"这份数可信"，再看分）

- 词面腿输入 = `build/stage2-dense-offline-lexical.tsv`（sha256 `ea491b55…`，由测试侧 `Stage2LexicalScoresExportTest`
  从 `retrieveA` 的同一套 FTS5 索引/MATCH/WHERE 导出，`retrieveA` 未改一行）；导出后**回读重算臂 A** =
  主集 59/90 = 0.6555555555555556、MRR 0.5568518518518518、MISS 31 条（含 rank）与 `build/stage1-metrics-A.txt` 逐行一致。
- Python 判分侧再从 TSV 复现一遍：matched-only 0.6556/0.5569、**生产形（parents+matched）0.5333（48/90）/MRR 0.1552**
  —— 与 Stage-1 §10 封存值逐位相同（形状实现因此被证与 Stage-1 同语义）。
- 编码保真（与模型卡用法 sentence-transformers 对拍）：bge 全量 28,932 文档 + 90 查询 cosine 最小 **1.000000**；
  qwen3 在 fp32 下 cosine 最小 **0.99999988**（默认 bfloat16 下 0.9979 ⇒ 差异是 dtype，不是构造）。
- 金标 sha256 `7c004b76…` 两处运行时复核通过；90 条 / 4 科 / 10 章 × 9 条；两模型 token 截断 0/0。

### 6.2 判读结果（判读形 = matched-only；该形态下 D1 = matched-only，见 6.3）

| 臂 | 模型 | 主集 Recall@5 | 逐章最小 | MRR | 主判据（≥0.75 且 ≥0.60） |
|---|---|---|---|---|---|
| **D-fuse-α0.5**（主判读） | Qwen3-Embedding-0.6B | **0.7778（70/90）** | **0.2222** | 0.6335 | **未通过**（逐章最小 <0.60） |
| D-only | Qwen3-Embedding-0.6B | 0.7889（71/90） | 0.4444 | 0.6122 | 未通过（逐章最小 <0.60） |
| D-rrf-60（对照，不参评） | Qwen3-Embedding-0.6B | 0.7556（68/90） | 0.3333 | 0.5915 | （对照） |
| D-only | bge-small-zh-v1.5 | 0.6444（58/90） | 0.3333 | 0.5050 | 未通过 |
| D-fuse-α0.5 | bge-small-zh-v1.5 | 0.7444（67/90） | 0.3333 | 0.6200 | 未通过 |
| Lex-A（参照） | —（FTS5 bm25） | 0.6556（59/90） | 0.2222 | 0.5569 | Stage-1 判读数（已复现） |
| v1 裸 B5（基线参照） | — | 0.5444（49/90） | 0.2222 | 0.1511 | 生产现状（Stage-1 封存） |
| C-upper（上界参照） | — | 0.7889（71/90） | 0.5556 | 0.6444 | 不可上生产 |

- **附加判据（机制自证）**：融合臂主集 0.7778 **> 臂 A 的 0.6556 ⇒ 成立**（叠加 dense 确实消灭了词面腿的一部分失败面）。
- **主判据（门槛）**：两臂都**未通过**——卡在 **逐章最小**（0.2222 / 0.4444 < 0.60），主集本身已 ≥0.75（融合 0.7778、dense 单路 0.7889）。
- **按 §1.2 的三种归宿**：落在**第 3 种**（主判据不过 → **不立项**；这 90 条里 dense 也捞不回的题归入内容/绑定问题，不在检索器上继续加机制）。
- 机制内部的量化（非判读数，供归因）：融合臂 20 例 MISS 中 **7 例是"融合回归"**（dense 腿 top-5 命中、融合反而未命中）、
  **13 例两腿 top-5 都未命中但至少一腿在放宽窗口（前 256）内能找到**（排序深度不足）——
  这解释了为什么 dense 单路（71）反而比 α=0.5 融合（70）多 1 例、且逐章最小明显更好（0.4444 vs 0.2222）。
- **诊断段（显式非判读数，不得据此调参）**：α=0.3 → 0.7000；α=0.7 → **0.8222**（74/90，逐章最小 0.4444）；
  z-score 归一化 → 0.7556；候选域限 ATOMIC → 0.7778（与主臂同数 ⇒ 规格 §2.2"TOPIC 不进向量集"这一缺口**无量化影响**）。
- **融合为什么会"回退"（机制观察，逐题可查）**：逐题看，dense 单路在这几题上排第 1，而 α=0.5 融合的前 5 被词面腿的近邻
  挤满——例：`镁条着火时为什么不能用二氧化碳灭火器去灭？`（D-only top1 = `镁的化学性质`（= 预期）；融合 top5 无预期）、
  `f(x)=x+1/x（x>0）的最小值…`（D-only top1 = `对勾函数`（= 预期）；融合 top5 无预期）、
  `两个力3N和8N…合力最大最小各是多少？`（D-only top2 = `合力范围`（= 预期）；融合 top5 无预期）。
  归一化把词面腿"一大批都沾边的候选"压到同一分档，dense 的精确命中在 0.5/0.5 加权下被摊平——这是
  **α=0.5 的具体失败面**（诊断臂 α=0.7 回升到 0.8222 也是同一机制的反面证据），不构成改参理由（判据未改）。
- **两臂共同的瓶颈章**：`物理必修第一册·第三章·相互作用`（融合 2/9、dense 单路 4/9，Stage-1 词面腿同为 2/9）
  ——dense 没有改善这一章，属 §1.2 第 3 种归宿里"内容/绑定问题候选"的落点之一。

### 6.3 返回形态（D1 与生产形；父 = matched 节点所属 topic，包内顺序）

| 臂 | D1（matched 前置）主集 | 逐章最小 | MRR | 生产形（parents+matched）主集 | 逐章最小 | MRR |
|---|---|---|---|---|---|---|
| D-fuse-α0.5-qwen3 | 0.7778（70/90） | 0.2222 | 0.6335 | 0.6444（58/90） | 0.2222 | 0.1972 |
| D-only-qwen3 | 0.7889（71/90） | 0.4444 | 0.6122 | 0.6222（56/90） | 0.3333 | 0.1896 |
| D-fuse-α0.5-bge | 0.7444（67/90） | 0.3333 | 0.6200 | 0.6111（55/90） | 0.2222 | 0.1846 |
| Lex-A（参照） | 0.6556（59/90） | 0.2222 | 0.5569 | 0.5333（48/90） | 0.1111 | 0.1552 |

- **D1 与 matched-only 恒等**：D1 把 matched 排在前、父节点排其后，判分窗口（前 5）全在 matched 侧 ⇒
  对每条臂 D1 的主集/逐章/MRR 与 matched-only **逐位相同**（脚本断言，不是假设）。
- **生产形下**：融合 0.6444 > dense 单路 0.6222 > 词面腿 0.5333 ⇒ 即使按生产真实返回形态，dense/融合也高于词面腿
  （Stage-1 §10 里 FTS5 排序在生产形下是 0.5333，**FTS5 相对 v1 的增益只在改返回形态后才出现**这一结论不变）。

### 6.4 未做 / 未证实（诚实边界）

- **端侧（§3）一步未做**：无量化件、无 Kotlin tokenizer、无 APK 增量、无真机 p95 —— 立项仍待用户另裁（§3.7）。
- 未做"topic 也进向量集"的变体（规格 §2.2 写死的向量集不含 topic）；其影响已用"候选域限 ATOMIC"诊断量化 = 0。
- 未做 cross-encoder 重排、章门控与 dense 的组合臂、MRL 降维（32–1024）——均属 §2 未列臂，本轮不出数。
- 90 条对 P@5 的方差风险（审计 §2.3-3）仍在：**逐章最小（9 条/章）是本次未过线的那条判据**，波动敏感度未量化。
- 规格 §5 未证实清单：第 1 条（Qwen3 许可证）本次**升级为两通道互证**（huggingface.co 直连 + hf-mirror 均返回 apache-2.0）；
  其余各条（端侧体积/16KB 合规/端侧延迟等）本轮同样未复核，仍按"单一来源待验证"对待。

