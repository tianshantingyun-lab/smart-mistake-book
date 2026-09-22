# Stage-1 执行报告（词面检索 FTS5 + `bm25()` 实验与判读）· 2026-09-22/23

> **性质**：Stage-1 全阶段证据留档（各 WP 摘要、档 1/档 2 记录、逐字日志行、UNVERIFIED 清单、遗留）。
> **生成**：WP-E2（收尾与推送代理，2026-09-23）。**上游**：编排脚本 `dwfrun-6d298c2a-002f-4784-bc93-e3629b649c35`（ask#10）；规则与判读线 `docs/kb-lexical-stage1-experiments.md`；出数 `build/stage1-experiments.md` + `build/stage1-verdict.json`（`build/` 不提交）；决策文档 `docs/kb-vector-topic-decision.md`。
> **状态**：**Stage-1 未过线**（下方 §1）⇒ **生产检索代码一字未改**；dense 兜底议题维持开启、立项待用户另裁。
> **纪律**：金标集未增未减未改（sha256 `7c004b76…`，§2 复核）；判读线 0.75/0.60 与判分口径出数前写死、出数后一字未动；参数扫描只作诊断（§6 显式标注非判读数）。

---

## 0. 结论摘要（一句话）

词面栈换 **SQLite FTS5 + `bm25()`**（外加别名词典扩展、册·章·节门控四臂实验）**未过**预注册判读线 0.75/0.60（chosenArm = 臂 A：主集 0.6556 属 **matched-only 诊断口径**；**生产判分口径下 0.5333 < 基线 0.5444**）⇒ **Stage-1 未达标、dense 转立项（大档，仍需用户另裁）**；生产形状保持 **v1 截断索引 × 裸 B 路 limit=5**（零回归，未改）。

---

## 1. 判读（机械求值 + 判分口径更正）

### 1.1 按预注册线机械求值（判读线出数前写死：主集 Recall@5 ≥0.75 且 逐章最小值 ≥0.60）

- `chosenArm` = **A**（`stage1-A-fts5-bm25`；可上生产臂 A / B / C-2stage / D 中主集最高者；C-upper 与 T 不参评、A' 为一致性对照）。
- 实测 **主集 0.6555555555555556（59/90）、逐章最小 0.2222222222222222、MRR 0.5568518518518518** ⇒ **未过线**（两条判据均不满足：0.6556 < 0.75；0.2222 < 0.60）。
- 判读实现 = 编排脚本按 `passed = main >= 0.75 && chapterMin >= 0.60` 机械求值，不接受人工改判（本报告不重判）。

### 1.2 判分口径更正（**必读**：臂 A 的判读数与生产口径不是同一口径）

- **臂 A 的 0.6556 / 0.2222 / 0.5569 出自 matched-only 口径**：只对排序后的 matched 节点取前 5，**无父节点前置**——这是实验台的诊断口径，**不是生产判分口径**。
- **生产判分口径** = store 的 `(parents + matched)` 返回形态取前 5（`core/database/src/main/kotlin/com/tingyun/smartmistakebook/core/database/RoomKnowledgeBaseStore.kt:125-133`；`GoldenRetrievalInstrumentedTest` 的判分方式，见该文件 :110-112）。在该口径下（`build/stage1-experiments.md` §10 六格表与 §9 的 `scan-A-parentprefix` 行，WP-S 复算）：
  - **臂 A（FTS5+bm25 排序 × 生产形）= 0.5333（48/90）/ 逐章最小 0.1111 / MRR 0.1552**；
  - **基线 v1 排序 × 生产形 = 0.5444（49/90）/ 逐章最小 0.2222 / MRR 0.1511**（预注册基线，真 SQL 与 JVM 镜像零漂移）。
- **正式结论（写死读法）**：**在生产口径下 FTS5+`bm25()` 排序未带来 Recall 增益（0.5333 < 0.5444），增益只在改变返回形态（matched 优先）后才出现（0.6556）**——表观的 +0.1112 来自返回形态（父节点不再占席），不是排序增益；同一条 FTS5 排序只换返回形态：生产形 0.5333 → matched 优先 0.6556（+0.1222）。
- **不得再出现未限定口径的"FTS5 严格更优"或"+11.1pp"之类表述**：正确表述只有上一条（本报告、`docs/kb-lexical-stage1-experiments.md` §7.2 均已按此写；见 §11 遗留里对清单外文件的登记）。
- **是否改返回形态 = 待用户裁定项 D1**（`(parents + matched)` 是 KNOWLEDGE_READ / MASTERY_READ 聚焦解析的既有语义，改形态要逐一复核消费者：`RoomTutorToolRunner`、`RoomTutorKnowledgeContextLoader`、`RoomMistakeOrganizationRepository`）。

### 1.3 判读后果

| 项 | 结果 |
|---|---|
| 过线分支 | **不适用**（未过线）——无生产落地，故无"落地后 p95 / DB 增量 / 地板新值"可记 |
| 生产检索代码 | **未改**（仍在 v1 截断索引 × 裸 B 路 limit=5；`INDEX_VERSION=1`） |
| 回归地板 | **维持旧值**：主集 ≥0.54 / MRR ≥0.15（`GoldenRetrievalInstrumentedTest.kt:323-324`），未抬高（无新基线） |
| dense | **转立项**（大档 Qwen3-Embedding-0.6B 量级），**仍需用户另裁**（含 APK 增量、端侧可部署性与延迟实测） |

---

## 2. 冻结项与口径（复核记录）

| 项 | 值 | 本轮复核（命令/来源） |
|---|---|---|
| 金标集 | `tools/kb_coverage/tables/golden_queries_v1.json`，90 条 / 10 章 | `sha256sum tools/kb_coverage/tables/golden_queries_v1.json` = `7c004b763bdd49556e11ff1c9500c9461b09a7383b77f230fa8fd35754e6ae39`（与 `.sha256` 封存一致） |
| assets 副本 | `core/data/src/androidTest/assets/golden/golden_queries_v1.json(.sha256)` | 同上命令第二行 = 同一 sha256（两份逐字节一致）；`git status core/data/src/androidTest/` 仅 `GoldenRetrievalInstrumentedTest.kt` 为 M ⇒ **副本未被构建改写**（构建期同步任务拷贝内容与权威源相同） |
| 判读线 | 主集 ≥0.75 且 逐章最小 ≥0.60 | `docs/kb-lexical-stage1-experiments.md` §1（出数前写死，出数后一字未动） |
| 固定参数 | FTS5 `bm25()` 的 `k1=1.2` / `b=0.75`（SQLite 硬编码，不可调） | 同上 §3；判读数全部取自该套参数 |
| 判分口径 | top-5、按科隔离、可信状态过滤、命中 = `knowledgeNodeId.endsWith(":atomic:"+expectedSlug)` | §7.2 / §1.2；与既有金标测量台同一套 |
| 生产形状 | v1 截断索引（`MAX_SEARCH_FRAGMENTS=16` / `MAX_NODE_FEATURES=192`，`INDEX_VERSION=1`）× 裸 B 路 limit=5 | `docs/kb-vector-topic-decision.md` §3.2/§3.3 + 本轮 §5 现状同步（未改） |
| 索引规模（实验台） | `indexVersion=1 docs=3970 featureRows=583271 maxFeaturesPerNode=192` | `build/stage1-experiments.md` §1 逐字 |
| 镜像与真 SQL 零漂移锚点 | v1 裸 B5：真 SQL 0.5444（49/90）= JVM 镜像 0.5444（49/90） | `docs/kb-vector-topic-decision.md` §3.3；JVM 侧本轮 `build/golden-jvm-metrics.txt` B-route 段 = `Recall@5(主集)=0.5444444444444444 (49/90)` / `MRR=0.15166666666666667` |
| 判分口径差异红线 | 镜像 vs 真 SQL >5pp 才触发"先修镜像再判" | 本项未触发（历史锚点漂移 0） |

---

## 3. 全臂出数（逐字引用 `build/stage1-experiments.md` §3）

| 臂 | 路由 | 参评 | 主集 Recall@5 | 命中 | 逐章最小 Recall@5 | MRR | 指标文件 |
|---|---|---|---|---|---|---|---|
| A | `stage1-A-fts5-bm25` | ✅ | 0.6555555555555556 | 59/90 | 0.2222222222222222 | 0.5568518518518518 | `build/stage1-metrics-A.txt` |
| A' | `stage1-Aprim-custom-bm25-sql` | ❌（一致性对照） | 0.6555555555555556 | 59/90 | 0.2222222222222222 | 0.5568518518518518 | `build/stage1-metrics-A'.txt` |
| B | `stage1-B-alias-expansion` | ✅ | 0.6 | 54/90 | 0.2222222222222222 | 0.40092592592592596 | `build/stage1-metrics-B.txt` |
| C-upper | `stage1-C-upper-chapter-oracle` | ❌（天线口径） | 0.7888888888888889 | 71/90 | 0.5555555555555556 | 0.6444444444444447 | `build/stage1-metrics-C-upper.txt` |
| C-2stage | `stage1-C-2stage-majority-chapter` | ✅ | 0.5222222222222223 | 47/90 | 0.2222222222222222 | 0.4433333333333334 | `build/stage1-metrics-C-2stage.txt` |
| D | `stage1-D-A+B+C2stage` | ✅ | 0.5111111111111111 | 46/90 | 0.1111111111111111 | 0.3281481481481481 | `build/stage1-metrics-D.txt` |
| T | `stage1-T-trigram` | ❌（对照） | 0.6444444444444445 | 58/90 | 0.2222222222222222 | 0.5494444444444445 | `build/stage1-metrics-T.txt` |

- 基线对照（预注册）：v1 裸 B5 真 SQL 主集 **0.5444**（49/90）/ MRR **0.1511**；同构建 JVM 镜像 0.5444444444444444（49/90）/ MRR 0.15166666666666667（§3 逐字）。
- 各臂相对基线的差（主集，逐字）：A +0.1112、A' +0.1112、B +0.0556、C-upper +0.2445、C-2stage −0.0222、D −0.0333、T +0.1000——**这些差都是 matched-only 口径对生产口径基线**，故 §1.2 的更正对读法成立（同口径对照见 §6.2 六格表）。
- 环境保真（§2 逐字要点）：sqlite-jdbc（xerial）内嵌 SQLite **3.53.4**、FTS5 建表成功、`trigram` 可用；bm25 公式夹具最大偏差 1.11e-16、文档长度对账 12 篇不一致 0 篇；首题查询计划 `SCAN node_fts VIRTUAL TABLE INDEX 0:M5 / USE TEMP B-TREE FOR ORDER BY`。

---

## 4. 逐章与 MISS（概要；逐字全表在 `build/stage1-experiments.md` §4/§5）

- **臂 A 逐章（matched-only 口径）**：生物学必修2·第一章第二节 8/9、化学必修第一册·第三章 4/9、化学必修第二册·第五章 9/9、化学必修第二册·第六章 9/9、数学必修第一册·第三章 5/9、数学选择性必修第一册·第二三章 7/9、数学选择性必修第二册·第四章 3/9、物理必修第一册·第一章 4/9、物理必修第一册·第三章 2/9、物理必修第三册·第十一十二章 8/9 ⇒ **逐章最小 0.2222（物理必修第一册·第三章）**。
- **MISS**：臂 A 31/90、A' 31/90、B 36/90、C-upper 19/90、C-2stage 43/90、D 44/90、T 32/90（§5 标题逐字）。
- **生产形状（v1 裸 B5）侧本轮 JVM 镜像逐题台账**（`build/golden-jvm-metrics.txt`，WP-B 落地）：A 路（精排参考上界）`Recall@5(主集)=0.4666666666666667 (42/90)`、MRR 0.2766666666666666、MISS **48** 例；B 路镜像（= 判分目标）`Recall@5(主集)=0.5444444444444444 (49/90)`、MRR 0.15166666666666667、MISS **41** 例（含 5 例 absent）。
- **跨侧对照**（WP-B 报告）：两侧 MISS 集合逐题一致（对称差 0），名次可差 1–3 位（父节点前置块序不同：镜像按包内序、真 SQL 按 rowid 序）；诊断窗口两侧统一为"返回序列前 256 名"（`RetrievalBenchmark.MISS_PROBE_LIMIT=256`，`RetrievalBenchmark.kt:236`）。

---

## 5. A ↔ A' 一致性证据（验收硬项：容差 0）

逐字（`build/stage1-experiments.md` §6 汇总行）：

```
汇总: top5 不等 0/90；窗口(前 256)不等 0/90；同题同节点的 bm25 分值差 >1e-9 的项 0 个（最大差 8.526512829121202E-14）
```

主集 59/90 = 59/90、MRR 0.5568518518518518 = 0.5568518518518518，逐题 top5 与放宽窗口（前 256）序列逐题相等；`build/stage1-verdict.json` 的 `fts5VsCustomAgree: true`。出数过程中曾因**排序方向符号写反**（FTS5 的 `bm25()` 返回负分、升序即最优；自写侧初版按正分升序）当场对账报红并修正，根因 = 排序方向约定（§6 逐字：「A' 当时主集 0/90」→ 改 `-SUM(...) ASC` 后逐位一致）。

---

## 6. 诊断段（**全部为非判读数**，不参与 `chosenArm` 与判读）

### 6.1 扫描诊断（`build/stage1-experiments.md` §9 逐字）

| 扫描 | 主集 Recall@5 | 逐章最小 | MRR | 说明 |
|---|---|---|---|---|
| scan-A'-k10.9-b0.4 | 0.6555555555555556 | 0.2222222222222222 | 0.5735185185185185 | 非判读数：k1/b 变体（判读配置固定 k1=1.2 b=0.75） |
| scan-A'-k11.5-b0.9 | 0.6555555555555556 | 0.2222222222222222 | 0.5527777777777778 | 同上 |
| scan-A'-k12.0-b1.0 | 0.6555555555555556 | 0.2222222222222222 | 0.5411111111111111 | 同上 |
| scan-C-2stage-K16 | 0.5222222222222223 | 0.2222222222222222 | 0.4596296296296296 | 非判读数：门控宽度变体（臂定义固定 K=64） |
| scan-C-2stage-K32 | 0.5333333333333333 | 0.2222222222222222 | 0.44888888888888895 | 同上 |
| scan-C-2stage-K128 | 0.5444444444444444 | 0.2222222222222222 | 0.4437037037037038 | 同上 |
| scan-A-parentprefix | 0.5333333333333333 | 0.1111111111111111 | 0.15518518518518518 | 非判读数：A 的排序 + 生产镜像的父节点前置返回形态 |
| scan-B-expansionOnly | 0.6111111111111112 | 0.2222222222222222 | 0.40870370370370374 | 非判读数：臂 B 去掉组覆盖排序键，只保留扩展词 |

trigram 对照臂失配影响（§9 逐字）：`90 题里含 <3 字特征的 90 题；特征总数 4514，其中 <3 字（trigram 索引下注定失配）2243 个；前 5 返回为空的 0 题`。

### 6.2 口径对照六格（`build/stage1-experiments.md` §10，WP-S 复算；诊断段，不改 §1 判读）

| 路线（候选排序） | 返回形态 | 主集 Recall@5 | 命中 | 逐章最小 | MRR |
|---|---|---|---|---|---|
| v1 COUNT(DISTINCT feature)（裸 B 路镜像） | 生产形 parents+matched | 0.5444444444444444 | 49/90 | 0.2222222222222222 | 0.15166666666666667 |
| v1 | matched-only（非生产口径·诊断） | 0.6444444444444445 | 58/90 | 0.2222222222222222 | 0.5637037037037038 |
| v1 | D1 候选 matched 前置 | 0.6444444444444445 | 58/90 | 0.2222222222222222 | 0.5637037037037038 |
| FTS5 bm25（臂 A） | 生产形 parents+matched | 0.5333333333333333 | 48/90 | 0.1111111111111111 | 0.15518518518518518 |
| FTS5 bm25（臂 A） | matched-only（非生产口径·诊断） | 0.6555555555555556 | 59/90 | 0.2222222222222222 | 0.5568518518518518 |
| FTS5 bm25（臂 A） | D1 候选 matched 前置 | 0.6555555555555556 | 59/90 | 0.2222222222222222 | 0.5568518518518518 |

WP-S 一句话结论（逐字）：「**在生产口径（parents+matched 取前 5）下 FTS5+bm25 排序未带来 Recall 增益（0.5333 < 0.5444）**；即使在 matched-only 口径下，FTS5 相对 v1 排序也只多命中 1 题（58→59）且 MRR 反而更低（0.5637→0.5569）——表观的 +11.1pp 来自返回形态（父节点占位挤掉 matched），不是排序增益；是否改返回形态（D1）属待用户裁定项。」

---

## 7. 各工作包记录

### 7.1 WP-A 记录与预注册改写（纯文档，任务书摘要逐字）

「WP-A 记录改写完成（纯文档，未改任何代码）。①新建 `docs/kb-lexical-stage1-experiments.md`：判读线 0.75/0.60 写死、A/A'/B/C/D + trigram 对照臂定义、固定参数与扫描纪律、金标与判分口径冻结声明。②`docs/kb-vector-topic-decision.md`：§4 按用户新裁定整体改写（质量优先大档 Qwen3-Embedding-0.6B 量级、ORT/LiteRT 运行时、归一化分数融合/RRF 降对照、无 ANN、sqlite-vec 不引入、立项仍另裁；≈30MB 与 bge-small-zh+LiteRT 字面作废），新增 §7 附录 A（更正与外部证据 A.1–A.5，含 62% 口径更正、dense 扫描与存储账、Android 生态空白、许可证陷阱、来源置信与本次取证失败实况），并在文首与 §6.2 末加最小指针（§1/§3/§6 冻结条目一字未改）。③`docs/known-defects.md`：KD-24 末尾补口径更正注记（661/18.51%、2,208/61.81%；归宿不变、重开条件不变），标题加"（口径更正见末段）"指针。④`.wf-manifest-stage1.txt` 写入三份文档路径。」（本报告 §2 复核：62% 更正段与 KD-24 注记在树中在位。）

### 7.2 WP-B 测量设施修正（任务书摘要 + 本轮复核）

任务书摘要逐字（**注意：任务书文本在"三处红→绿演示"处被截断，第 2/3 条演示的原始日志未送达本轮，按 UNVERIFIED 记**）：

「三项全部落地并实测；档 1（JVM）绿，仪表化金标测试末次实跑 BUILD SUCCESSFUL。
【任务 1 金标 assets 构建期同步】`core/data/build.gradle.kts:35-72` 新增 Copy 任务 `syncGoldenQueryAssets`（从 `tools/kb_coverage/tables` 拷 `golden_queries_v1.json` + `.sha256` 进 `core/data/src/androidTest/assets/golden/`），并用 `tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }.configureEach { dependsOn(...) }` 接在合并任务前；验证：`--dry-run` 任务图里 sync 出现在 merge 之前；实跑 sync 后三处 sha256 同为 `7c004b76…`；构建后 `git status core/data/src/androidTest/` 干净。运行时 sha 断言（第二道）保留；第三道 = JVM 测试 `goldenAssetsMirrorMatchesRepoCopyByteForByte`（`GoldenRetrievalJvmTest.kt:219`，逐字节比对，失败消息给出两份字节数与 sha256 及修复命令）。
【任务 2 逐题 MISS 持久化】JVM 侧：`RetrievalBenchmark.kt` 新增 `MISS_PROBE_LIMIT=256`（:236）、`GoldenMiss`、`goldenMisses`（只探未命中的题；判分 top-5 仍取 limit=5 的调用，放宽不污染已出分数）、`goldenMissLines`（:269）、`goldenChapterLines`（:282）、`goldenMetricsFile`（:520-545）每路线一节落 `build/golden-jvm-metrics.txt`；本次实跑 A 路 48 例、B 路 41 例（B 中 5 例 absent）。仪表化侧同格式同口径打进 System.out/logcat（末次运行 41/90 例、5 例 absent）。新增产物契约断言 `assertMissLedgerMatchesScores`（`GoldenRetrievalJvmTest.kt:165`）。跨侧对照：两侧 MISS 集合逐题一致（对称差 0），名次可差 1–3 位（父节点前置块序不同）；诊断窗口两侧统一为"返回序列前 256 名"（并修正了仪表化侧原先能报出 rank=284 > 声明深度 256 的口径不一致）。
【任务 3 回归地板】`GoldenRetrievalInstrumentedTest.kt:323-324` `RECALL_MAIN_FLOOR=0.54`、`MRR_MAIN_FLOOR=0.15`，断言置于全部输出之后（先落数后断言）；注释写明"不得更差的下界、不是质量目标"，与 D12 预注册判据（主集 ≥0.75 且逐章 ≥0.60）互不影响、不得据此改判，基线抬高后同步抬高并记录旧值（当前旧值 0.54/0.15）。」

本轮复核（WP-E2，一手）：`core/data/build.gradle.kts:35-72` 的 sync 任务与 `tasks.matching {…AndroidTestAssets…}` 接线在位；`GoldenRetrievalJvmTest.kt:165/:219`、`RetrievalBenchmark.kt:236/:269/:282/:520`、`GoldenRetrievalInstrumentedTest.kt:200-214/:323-324` 均在位；本轮 FINAL 档 1 实跑（`--rerun`）两测均绿（§8.3）；`git status core/data/src/androidTest/` 仅 `GoldenRetrievalInstrumentedTest.kt` 为 M（assets 两份未被构建改写）。

### 7.3 WP-C 实验台与出数

产出 `build/stage1-experiments.md`（11 节：口径与固定参数 / 环境保真 / 全臂表 / 逐章 / MISS / A↔A' 一致性 / 臂 B 日志 / 臂 C 日志 / 扫描诊断 / 口径对照 / 判读）+ `build/stage1-verdict.json` + `build/stage1-metrics-*.txt`；判分口径与 §1/§2 逐字相同，只换"候选排序 × 返回形态"。档 1 绿（编排脚本 `tier1("WP-C", [":core:data:testDebugUnitTest", ":core:database:testDebugUnitTest"] + python + kb checks)`）。
**本轮幂等复核（WP-E2）**：以 `./gradlew testDebugUnitTest --continue --console=plain --rerun` 重跑后，`build/stage1-experiments.md` 与跑前备份 **逐字节相同**（sha256 `f1bbe3548413d29ab1a71f57e44a9af8c4b182faf18534119c7ee2a038292bed`），`build/stage1-verdict.json` 亦同（`9078258…`）——实验台每次跑可复算、产物不漂。

### 7.4 WP-S 判分口径对照（任务书摘要逐字）

「逐字数（主集 Recall@5（命中/90）/ 逐章最小 Recall@5 / MRR）：① v1 COUNT(DISTINCT feature) × 生产形 parents+matched = 0.5444444444444444（49/90）/ 0.2222222222222222 / 0.15166666666666667 —— 复现预注册基线 0.5444 ✓；② v1 × matched-only（非生产口径·诊断）= 0.6444444444444445（58/90）/ 0.2222222222222222 / 0.5637037037037038；③ 臂 A FTS5 bm25 × 生产形 = 0.5333333333333333（48/90）/ 0.1111111111111111 / 0.15518518518518518 —— 复现 scan-A-parentprefix ✓；④ 臂 A × matched-only = 0.6555555555555556（59/90）/ 0.2222222222222222 / 0.5568518518518518 —— 复现臂 A 判分数 ✓；⑤ D1 候选（matched 前置、父节点排其后）：臂 A = 0.6555555555555556，v1 = 0.6444444444444445 —— 与各自 matched-only 逐位相同（只改返回顺序即回收全部分数）。一句话结论：**在生产口径（parents+matched 取前 5）下 FTS5+bm25 排序未带来 Recall 增益（0.5333 < 0.5444）**。落地物：`build/stage1-experiments.md` 新增 §10（六格表，由实验台生成、每次跑可复算；原 §10 判读顺延为 §11）+ `build/stage1-verdict.json` 的 notes 加口径说明（arms 逐字节未改）。」

### 7.5 WP-E1 档 2（设备）验证——详见 §9

### 7.6 WP-E2（本报告）

口径更正落档（`docs/kb-lexical-stage1-experiments.md` §7.2、本报告 §1.2）、Stage-1 判读落档（该文件 §7）、本报告新建、`docs/kb-vector-topic-decision.md` §5 现状段 + 文首时戳同步、FINAL 档 1 实跑（§8.3）、显式清单提交与推送（§12）。

---

## 8. 档 1（JVM/工具链门禁）记录

### 8.1 各阶段门禁（编排脚本执行，逐条）

| 轮次 | 命令 | 结果 |
|---|---|---|
| WP-B | `gradlew.bat -Pwf.stage1=true :core:data:testDebugUnitTest --continue --console=plain` + python | 绿（任务书摘要：「档 1（JVM）绿」） |
| WP-C | `:core:data:testDebugUnitTest` + `:core:database:testDebugUnitTest` + python + kb checks | 绿（编排脚本 `tier1` 通过后才进入 WP-E1） |
| FINAL（编排脚本） | `gradlew.bat testDebugUnitTest --continue --console=plain` + python + kb checks | 通过（未通过则不会派发 WP-E2） |

### 8.2 本轮 WP-E2 自己跑的第一遍（**未执行测试**，如实记）

```
./gradlew testDebugUnitTest --continue --console=plain
→ BUILD SUCCESSFUL in 1s；164 actionable tasks: 164 up-to-date
```

**这一遍不算门禁通过**：全部任务 `UP-TO-DATE`（Gradle 复用上次执行结果，测试代码没有被真正执行）。故补跑 §8.3。

### 8.3 本轮 FINAL 档 1 实跑（**--rerun 强制再执行**，WP-E2 本人执行）

```
./gradlew testDebugUnitTest --continue --console=plain --rerun
→ BUILD SUCCESSFUL in 57s；164 actionable tasks: 10 executed, 154 up-to-date；exit 0
```

- 实跑模块（10）：`:core:data`、`:core:database`、`:core:export`、`:core:ui`、`:core:visual-ui`、`:feature:capture`、`:feature:library`、`:feature:profile`、`:feature:review`、`:feature:tutor`；`:core:model`、`:core:domain` 的 `testDebugUnitTest` 为 UP-TO-DATE（其既有结果有效）。
- 结果聚合（本轮 XML，`*/build/test-results/testDebugUnitTest/*.xml`）：**146 个 XML / 957 个测试 / failures 0 / errors 0 / skipped 0**；其中关键两测 `GoldenRetrievalJvmTest`（tests=2，failures=0，timestamp 2026-09-22T21:58:13Z）与 `Stage1LexicalLabTest`（tests=1，failures=0，21:58:17Z）。
- 工具链门禁：`python -m unittest discover -s tools/tests -t tools -q` → **Ran 322 tests in 59.121s / OK**（exit 0）；`python tools/ci/run_kb_checks.py` → **exit 0**，末段 `gates / consistency / roundtrip / manifest` 全 `OK`。
- 顺带复核：`--rerun` 后金标 assets 两份仍未被改写（`git status` 只显示 `GoldenRetrievalInstrumentedTest.kt` 为 M）——构建期同步任务幂等。

---

## 9. 档 2（真机）记录（WP-E1）

**设备**：`test_device (AVD) / API 34 (Android 14) / x86_64 / emulator-5554`；全程未崩、`pm` 正常（`pm clear` / `install` 均 Success）。开工前按任务书先跑 `:app:assembleLocalFirstDebug`（BUILD SUCCESSFUL，exit 0，产出 `app/build/outputs/localFirst/debug/app-localFirst-debug.apk`）并 `adb install -r` 安装。**本轮全量重跑，未复用旧结果**；总账 **4 项 verified + 1 项 unverified，无 failed_code**。

### 9.1 逐项状态（任务书所载）

| # | 项 | 状态 | 证据（任务书） |
|---|---|---|---|
| 1 | `GoldenRetrievalInstrumentedTest`（金标真 SQL：判分数 + 地板断言 + sha 复核） | **verified** | `./gradlew :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=…GoldenRetrievalInstrumentedTest --console=plain` → BUILD SUCCESSFUL in 4m 38s，exit 0；XML `tests=1 failures=0 errors=0 skipped=0 time=259.24`；logcat（System.out）含 `golden-assets sha256=7c004b76…`（与冻结封存一致）与判分数/地板断言通过 |
| 2 | `KnowledgeContextRetrievalInstrumentedTest`（19 例回归 + 20k 基准） | **unverified**（形态判为 flaky 基础设施；**判据字面未触发，见 §9.2**） | 三次运行均红在**同测试方法内的 recall 墙钟门**（150ms），mastery 墙钟门三次均未到达；详见 §9.2 |
| 3 | `RoomModelTaskT6MasteryInstrumentedTest`（代号通道回归） | verified | 任务书总账「4 项 verified」，本项详细证据在任务书文本中被截断（未送达本轮） |
| 4 | `KnowledgeContentUpdateDrillInstrumentedTest` + `BundledContentReconciliationInstrumentedTest`（W-4 债） | verified | 同上（详细证据未送达本轮） |
| 5 | 驱动未切换 → 既有形状冷启基线复核（R4a 同协议） | verified | 同上（详细证据未送达本轮） |

> **证据完整性声明**：第 3/4/5 项的逐字日志未随任务书送达本代理（任务书 JSON 在 `items[0]` 之后被截断）；本报告**不替它们补写证据**，只如实登记状态来源 = WP-E1 报告总账「4 verified + 1 unverified，无 failed_code」。

### 9.2 第 2 项的关键偏差与判定（**逐字保留分歧**）

- **判据字面 vs 实际形态**：任务书的 flaky 判据写的是「**mastery 墙钟门再红**」（250ms），但本轮三次运行红的都是同一测试方法内的 **recall 墙钟门**（150ms，`KnowledgeContextRetrievalInstrumentedTest.kt:198-201`），因此 **mastery 门在三次运行里一次都没被执行到**——断言在 :198 失败，:208 之后的 mastery 阶段根本没进入。**判据的触发条件（mastery 门红）本轮未发生，规则无法按字面套用。**
- **三次运行逐字（本轮复读 `/tmp/kcr_run1.log`、`kcr_run2.log`、`kcr_run3.log`）**：
  - run1：`java.lang.AssertionError: Room recall p95 was 492ms; samples=[492, 44, 46, 57, 57, 53, 50, 45, 50, 49]`
  - run2：`… was 21718ms; samples=[74, 21718, 180, 55, 51, 45, 52, 50, 45, 41]`
  - run3：`… was 1267ms; samples=[1267, 54, 45, 46, 50, 52, 47, 53, 39, 43]`
  - 形态一致：**单一样本孤立抬升（第 0–1 个样本）+ 其余稳态样本 39–57ms（约预算 1/3）**；若离群样本均匀随机，三次同时落在前两位的概率约 0.8%。
- **判定为 unverified 的理由（WP-E1 原文口径）**：(a) 三次都是"单一样本孤立抬升 + 其余稳态样本约预算 1/3"；(b) **确定性守卫全部通过**（EXPLAIN 查询计划 `:121-135`、选中语义 `:191-197`；本代理复核：失败点 `:198` 在这些断言之后，JUnit 快速失败 ⇒ 之前的断言必已通过）；(c) 同一条生产召回路径在档 1 真 SQL 金标 450 样本上 p95=33ms，未见退化；(d) 上一轮 run1 该门曾全绿（全样本 ≤59ms）。
- **反方读法（如实并列）**：任务书把「recall p95」列进了确定性守卫清单，若按该清单字面执行则属「确定性守卫失败」⇒ `failed_code`；且 run2 第 3 个样本 180ms 超过 150ms 召回预算（但低于 250ms）。WP-E1 判定"未证明存在代码缺陷"，取 `unverified` 而非 `failed_code`，并披露该分歧。
- **本代理的一手复核（WP-E2）**：`core/data/build/outputs/androidTest-results/connected/debug/TEST-test_device(AVD) - 14-_core_data-.xml` 逐字显示 `tests="2" failures="1"`，失败用例 `largeSubjectRecallRemainsBoundedOnRoom` 的失败消息与 run3 完全一致，失败点 `KnowledgeContextRetrievalInstrumentedTest.kt:198`；同 suite 的 `bundledSubjectsRecallExpectedKnowledgeWithoutCrossSubjectCandidates` 通过（`time=275.883`），其 logcat 含逐字行 `bundled-knowledge-recall regression: hit=19/19, cross-subject=0`（`logcat-…bundledSubjectsRecall….txt:735`）⇒ **19 例回归 19/19 在真机成立**、失败的只是 recall 墙钟门（KD-2 族）。
- **未归因项（WP-E1 如实记）**：抬升样本的一次性成本来源未定位（logcat 无独立铁证：`system_server` 的 Slow operation/Long monitor contention 集中在测试自身 20k 导入窗口内，无法与自载负荷分离）。
- **纪律**：未删断言、未放宽预算、未改测试源码（本代理复核：`KnowledgeContextRetrievalInstrumentedTest.kt:481-482` 预算 `150L/250L × CI_MULTIPLIER` 未被改动，该文件不在本轮清单内、git 中无改动）。

---

## 10. UNVERIFIED 清单（本轮全量）

| # | 项 | 状态 | 说明/证据 |
|---|---|---|---|
| U1 | 档 2 第 2 项：20k 基准的 **mastery 墙钟门**（250ms） | **unverified** | 三次运行都在其之前的 recall 墙钟门（150ms）失败，mastery 阶段未执行；无一次绿色复跑（§9.2） |
| U2 | 档 2 第 2 项：**20k 召回 p95** 未取得一次绿色复跑 | unverified | 三次全红（492 / 21718 / 1267ms），形态为单样本抬升（§9.2） |
| U3 | 抬升样本一次性成本的**根因** | unverified | 未能与测试自载负荷分离（§9.2 未归因项） |
| U4 | 档 2 第 3/4/5 项的**逐字日志** | 未送达本轮 | 任务书文本截断；状态取自 WP-E1 总账（§9.1） |
| U5 | WP-B「三处红→绿演示」中第 2/3 条的**原始日志** | 未送达本轮 | 任务书文本在"①漂移测试…"处截断（§7.2） |
| U6 | OpenSearch 的 RRF −3.86% NDCG@10 等 Stage-0 外部证据 | 单一来源待验证 | 见 `docs/kb-vector-topic-decision.md` §7 附录 A.5（本次会话取证失败实况已记）；**不支撑也不推翻任何判定** |
| U7 | `docs/kb-vector-topic-decision.md` §3 记的"首测日志未留存项"（任一样本命中口径、p95 原始值） | 不可复核 | 该文件 §3 表已标注（本报告不新增结论） |

---

## 11. 遗留与待用户裁定项

### 11.1 待用户裁定（本轮不做）

| # | 待裁项 | 依据 |
|---|---|---|
| D1 | **生产返回形态**：保留 `(parents + matched)`（FTS5 排序无增益，0.5333 < 0.5444）还是改 matched 优先（0.6556，需复核既有消费者语义） | §1.2、§6.2 |
| D2 | **dense 立项**（大档 Qwen3-Embedding-0.6B 量级）：立项/不立项、预算与端侧可部署性（APK 增量实测仍缺） | `docs/kb-lexical-stage1-experiments.md` §1、`docs/kb-vector-topic-decision.md` §4/§6.2 |
| D3 | **FTS5 排序引擎是否换**（不改返回形态的前提下）：生产口径实测无收益（0.5333 < 0.5444） | §6.1/§6.2 |
| D4 | 设备墙钟门（KD-2 族）的**预算是否随形态/规模重定标**（对齐决策文档 §3.1 记录的争议；该处只记事实、未定处置） | §9.2 |

### 11.2 遗留事实（不阻塞本次提交）

1. **清单外文件仍含未限定口径的表述（本代理按铁律 4 未改，逐条登记）**：
   - `docs/kb-fts5-production-plan.md:3`（导语"Stage-1 已证明「FTS5 + `bm25()`」这条词面腿比生产现状的排序引擎明显更好（主集 0.6556 vs 0.5444、MRR 0.5569 vs 0.1511）"）与 `:24-30`（§1.2 表含 `+11.1pp`）——**未限定口径**；
   - `docs/kb-stage2-dense-spec.md:140-141`（Lex-A 行 `+11.1pp` / v1 行 `−11.1pp`）——**未限定口径**；
   - 这两份文件**不在 `.wf-manifest-stage1.txt`**（§12.1），按铁律 4「清单外的脏文件一律不碰、不提交」本代理未修改、未提交；**建议由产出它们的 WP 按 §1.2 的读法更正**（同一条排序：生产形 0.5333 < 基线 0.5444；0.6556 只在 matched 优先形态下成立）。
2. 档 2 的第 3/4/5 项与 WP-B 的红→绿演示缺少逐字日志附件（§10 U4/U5），后续若要入档需重跑或补日志。
3. 20k 基准的 p95 门与 mastery 门的状态仍未闭合（§10 U1/U2/U3），与 KD-2 族同源。

---

## 12. 提交与复现

### 12.1 提交清单（`.wf-manifest-stage1.txt` ∩ `git status` 实际变更）

清单共 14 条（13 条本轮既有 + 本报告），与实际变更求交后提交 13 个文件：

| # | 路径 | 变更 | 内容 |
|---|---|---|---|
| 1 | `core/data/build.gradle.kts` | M | 金标 assets 构建期同步任务 + `sqlite-jdbc` 测试依赖（WP-B/WP-C） |
| 2 | `gradle/libs.versions.toml` | M | `sqlite-jdbc = 3.53.4.0`（testImplementation 用；不进 APK） |
| 3 | `core/data/src/test/kotlin/…/knowledge/RetrievalBenchmark.kt` | M | MISS 台账（`MISS_PROBE_LIMIT=256` 等）（WP-B） |
| 4 | `core/data/src/test/kotlin/…/knowledge/GoldenRetrievalJvmTest.kt` | M | 镜像逐字节断言 + MISS 台账契约断言（WP-B） |
| 5 | `core/data/src/androidTest/kotlin/…/knowledge/GoldenRetrievalInstrumentedTest.kt` | M | 逐题 MISS 落日志 + 回归地板（WP-B） |
| 6 | `core/data/src/test/kotlin/…/knowledge/Stage1LexicalLab.kt` | ?? 新增 | FTS5/自写 BM25 实验台（WP-C） |
| 7 | `core/data/src/test/kotlin/…/knowledge/Stage1Experiment.kt` | ?? 新增 | 四臂实验与口径对照（WP-C/WP-S） |
| 8 | `core/data/src/test/kotlin/…/knowledge/Stage1LexicalLabTest.kt` | ?? 新增 | 实验台契约断言 + 报告生成（WP-C/WP-S） |
| 9 | `docs/kb-lexical-stage1-experiments.md` | ?? 新增 + 本轮填 §7 | 预注册规则 + **结果段与口径更正**（WP-A/WP-E2） |
| 10 | `docs/kb-vector-topic-decision.md` | M | §4 改写/附录 A（WP-A）+ **§5 现状同步与时戳**（WP-E2） |
| 11 | `docs/known-defects.md` | M | KD-24 口径更正注记（WP-A） |
| 12 | `docs/kb-stage1-report-2026-09-22.md` | ?? 新增 | **本报告**（WP-E2） |
| 13 | `core/data/src/androidTest/assets/golden/golden_queries_v1.json` + `.sha256` | 清单内但**无变更** | 与权威源逐字节相同，`git status` 无条目 ⇒ 不需要也不进入 `git add` 结果 |

**显式排除**（不提交）：`.wf-manifest-stage1.txt`（元数据）、`tools/kb_build/tables/chapter_map.csv`（另一会话飞行改动）、`.agent_*` / `out/` / `tmp/` / `.v2c/` 等草稿、以及 `core/data/src/main/kotlin/…/OpenAiModelTaskAdapters.kt`、`core/model/**`、`feature/tutor/**` 等**不在清单内**的改动（另一会话）。

提交信息：`refactor(kb): Stage-1 词面检索 FTS5+bm25 实验与落地——四臂出数、预注册判读、设施修正`。

### 12.2 复现命令

```bash
# 实验出数（可复算；产出 build/stage1-experiments.md / stage1-verdict.json / stage1-metrics-*.txt）
./gradlew :core:data:testDebugUnitTest --tests "*Stage1LexicalLabTest*" --rerun

# 金标 JVM 测量台（产出 build/golden-jvm-metrics.txt）
./gradlew :core:data:testDebugUnitTest --tests "*GoldenRetrievalJvmTest*" --rerun

# 档 1 终验门禁（本报告 §8.3 实跑）
./gradlew testDebugUnitTest --continue --console=plain --rerun
python -m unittest discover -s tools/tests -t tools -q
python tools/ci/run_kb_checks.py

# 档 2（真机；先装包）
./gradlew :app:assembleLocalFirstDebug
./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.tingyun.smartmistakebook.core.data.knowledge.GoldenRetrievalInstrumentedTest

# 金标封存复核
sha256sum tools/kb_coverage/tables/golden_queries_v1.json core/data/src/androidTest/assets/golden/golden_queries_v1.json
```

### 12.3 证据索引

- 规则/臂定义/冻结声明：`docs/kb-lexical-stage1-experiments.md` §1–§4；**结果段与口径更正**：同文件 §7。
- 出数（**未提交**，按需重跑）：`build/stage1-experiments.md`（§3 全臂表 / §4 逐章 / §5 MISS / §6 A↔A' / §9 扫描 / §10 口径对照 / §11 判读）、`build/stage1-verdict.json`、`build/stage1-metrics-*.txt`、`build/golden-jvm-metrics.txt`。
- 判分包与地板：`GoldenRetrievalInstrumentedTest.kt:110-112`（生产口径取前 5）、`:140-161`（MISS 诊断窗口 256）、`:200-214`（地板与 p95 断言）、`:264-265`（sha 复核）、`:323-324`（地板常量）。
- 档 2：`core/data/build/outputs/androidTest-results/connected/debug/TEST-test_device(AVD) - 14-_core_data-.xml`（`tests=2 failures=1`，失败点 `KnowledgeContextRetrievalInstrumentedTest.kt:198`）、同目录 `logcat-*.txt`（`hit=19/19, cross-subject=0`）；设备三次运行日志 `/tmp/kcr_run{1,2,3}.log`（仓库外）。
- 档 1：`/tmp/wp-e2-final-gradle-rerun.log`、`/tmp/wp-e2-python.log`、`/tmp/wp-e2-kbchecks.log`（仓库外）；JVM 结果 XML `*/build/test-results/testDebugUnitTest/`。
