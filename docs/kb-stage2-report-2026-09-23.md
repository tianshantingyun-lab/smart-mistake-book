# Stage-2 收尾报告（2026-09-23 立 · 2026-09-24 收尾）· 返回形态 D1 落地 + 墙钟门方法学修正 + dense 离线验证

> **这一份回答什么问题**：Stage-1（`58ed753e`，已推送）判「词面栈不达标」，并留下两个待办——D1（改返回形态）与 dense 立项裁定；Stage-2 期间又暴露出第三条：20k 基准的墙钟门把单个离群样本当 p95（档 2 三轮误红）。本报告把这三件事的**改动、波及面、新旧数值与未验证边界**一次收口。
> **判官冻结（全文未动）**：金标集 `tools/kb_coverage/tables/golden_queries_v1.json`（90 条 / 4 科 / 10 章 × 9 条）sha256 `7c004b763bdd49556e11ff1c9500c9461b09a7383b77f230fa8fd35754e6ae39`；预注册判读线 0.75 / 0.60（`docs/kb-stage2-dense-spec.md` §1.2）与 Stage-1 判读线一字未改；本轮无一处改题面、改判据或在判官上调参。
> **日期口径（两处并存，照录不合并）**：文件名的 `2026-09-23` 是 Stage-2 的计划日（规格/报告沿用的日期标签）；**D1 与墙钟门修正的源码注释里写的落地日是 `2026-09-24`**（跨零点执行：`RoomKnowledgeBaseStore.kt:108`、`GoldenRetrievalInstrumentedTest.kt:41/321`、`Stage1Experiment.kt:872` 等）。设备侧 logcat 时间戳为 `09-23`（模拟器时钟）。
> **基线坐标**：分支 `main`，收尾前 HEAD `58ed753e`（与 origin/main 同）；本报告的提交紧随其后。

## 0. 证据分级（先读这一节，再读数）

| 标记 | 含义 |
|---|---|
| **【本轮复核】** | 收尾代理在写本报告时**亲自读原件或亲自跑**过的（命令/文件路径随文给出） |
| **【引交付报告】** | 引自 WP2/WP3/WP4/WP5a 的交付报告，收尾代理**未独立复跑**，只核对了它们引用的存档是否在盘、内容是否与所述一致（能核对的已标注） |
| **【未验证】** | 没有任何当前证据，明写在 §4 |

本轮实际执行的检查（逐条，命令与输出见对应节）：
1. `sha256sum tools/kb_coverage/tables/golden_queries_v1.json` + 与 `.sha256` 封存文件、`core/data/src/androidTest/assets/golden/golden_queries_v1.json` 三方比对 → 三者同为 `7c004b76…`，条数 90（Python 读 JSON 复核）。
2. `diff` 两次独立真 SQL 金标运行的**判分内容行**（D1 轮 `/tmp/golden-instrumented.log` vs WP5a 轮 `/tmp/wp5a/logcat-golden2.txt`）→ 逐章块与 32 条 MISS 清单**逐行一致**（`diff` 退出码 0）。
3. `diff` JVM 镜像（`build/golden-jvm-metrics.txt` 的 `B-route-mirror(v1-bare-B5, matched-first)` 段）vs 真 SQL → 主集/MRR/逐章 10 行**逐项相同**；MISS 集合 32/32 相同，仅 2 条 rank 差（69↔71、56↔57）。
4. Python 独立复算 p95 口径（旧/新两口径 × 三轮红样本 × 四次绿跑样本）→ 与打印值一致（见 §2.3）。
5. `grep` 读 `/tmp/kcr_run{1,2,3}.log`、`/tmp/wp3/red-demo.log`、`/tmp/wp3/green{1,2}.log` 的原始断言消息与 Gradle 结论 → 与 KD-25 登记一致。
6. `./gradlew.bat :core:database:compileDebugKotlin :core:data:compileDebugKotlin :core:data:compileDebugUnitTestKotlin :core:data:compileDebugAndroidTestKotlin --rerun`（强制重编译，见 §4 结论）。
7. 读生产消费方四处调用点源码（`RoomTutorKnowledgeContextLoader.kt:46`、`RoomMistakeOrganizationRepository.kt:177`、`RoomTutorToolRunner.kt:222/526`）与 `KnowledgeContextRetriever.select` 的排序实现 → D1 波及面结论（见 §1.2）。

---

## 1. D1：生产返回形态改为 matched 优先（金标真 SQL 0.5444 → 0.6444）

### 1.1 改动本体（一行）

`core/database/src/main/kotlin/com/tingyun/smartmistakebook/core/database/RoomKnowledgeBaseStore.kt`

```kotlin
// :131（改动前 → 改动后）
-        return (parents + matched)
+        return (matched + parents)
             .distinctBy(KnowledgeNodeEntity::knowledgeNodeId)
             .map(KnowledgeNodeEntity::toSeedRecord)
```

- 只换**序列位置**：集合与长度语义不变（未加 `take`，`distinctBy` 保留，SQL 的 `LIMIT` 仍只作用于 matched）；`:108` 按令加一行 KDoc 指向本报告。
- 空特征回退分支（`searchFeatures.isEmpty()` → `readSubjectKnowledgeNodes`）两形态完全相同，未动。

**四处镜像/钉数与生产逐处同步**（同一提交内，均为测试侧）：

| # | 文件 | 同步内容 |
|---|---|---|
| 1 | `core/data/src/test/.../RetrievalBenchmark.kt` | `BRouteMirrorIndex.recall` 改为 `matched + parents`；原 `matchedFirst` 删除、改名 **`parentsFirst`** 承载**旧**形态（历史记账）；`goldenBRoute` 标签改 `B-route-mirror(v1-bare-B5, matched-first)` |
| 2 | `core/data/src/test/.../Stage1Experiment.kt` | `SHAPE_D1` → `SHAPE_LEGACY_PRODUCTION`；`SHAPE_PRODUCTION` 语义 = matched 优先；§10 口径对照扩为**六格**（v1 × A 各 3 形） |
| 3 | `core/data/src/test/.../Stage1LexicalLab.kt` | 预注册基线 `BASELINE_MAIN=0.5444` / `BASELINE_MRR=0.1511` **值一字未动**，只加注释标明它是**旧生产形**的历史锚点 |
| 4 | `core/data/src/test/.../Stage1LexicalLabTest.kt` | 旧形态值改从 `SHAPE_LEGACY_PRODUCTION` 取并**继续钉 0.5444（49/90）**；新形态断言改为「≥ matched-only 且 ≥ 旧生产形」 |
| 5 | `core/data/src/androidTest/.../GoldenRetrievalInstrumentedTest.kt` | 回归地板 `RECALL_MAIN_FLOOR 0.54 → 0.64`、`MRR_MAIN_FLOOR 0.15 → 0.56`（`:327-328`，旧值就地留档）；判分口径注释、route 标签同步 |

### 1.2 波及面核实（第 12.1 条：逐处写「改到 / 为何不用改」）

`readSubjectKnowledgeRecallCandidates` 的生产消费方共 **4 处**；逐处结论【本轮复核：读调用点源码】：

| 消费方 | 调用点 | 结论 |
|---|---|---|
| `RoomTutorKnowledgeContextLoader` | `core/data/.../knowledge/RoomTutorKnowledgeContextLoader.kt:46` | **零影响**：候选交给 `KnowledgeContextRetriever.select`，该方法用 `score DESC → canonicalName → knowledgeNodeId` 的**全序**重排（`KnowledgeContextRetriever.kt:53-68`），输入序被完全抹掉；空排序分支同为 `canonicalName → id` 全序 |
| `RoomMistakeOrganizationRepository` | `core/data/.../mistake/RoomMistakeOrganizationRepository.kt:177` | **零影响**：同上走 `select` |
| `RoomTutorToolRunner`（mastery 聚焦解析） | `core/data/.../study/RoomTutorToolRunner.kt:526`（`MASTERY_FOCUS_RESOLUTION_LIMIT=24`，`:60`） | **零影响**：`.associate { id to displayName }` 是序无关映射 |
| `RoomTutorToolRunner`（**KNOWLEDGE_READ 工具**） | `core/data/.../study/RoomTutorToolRunner.kt:222`（`limit = KNOWLEDGE_READ_NODE_LIMIT = 5`，`:283`） | **唯一可见变化**：`nodes.mapIndexed { index, node -> … }` 直接按序编号与渲染 ⇒ 工具输出前 5 由「父 topic 前置」变为「matched 前置」。这是 D1 的**目的**（父节点不再占席），同时是**唯一需要人眼确认的行为变化**；其测试覆盖在 `core/data/src/test/.../study/RoomTutorToolRunnerTest.kt`【引交付报告：WP2 档 1 绿声称覆盖，本轮未复跑该文件】 |

其余影响面：SQL 的 `LIMIT` 施加在 matched 上、父节点是额外拼接 ⇒ **返回条数的上界不变**（≤ limit + 父节点数，改动前后同）；`require(limit in 1..MAX_KNOWLEDGE_RECALL_CANDIDATES)`（=512）等校验未动；JVM 镜像、Stage-1 实验台、金标仪表化三处按 §1.1 逐处同步，无遗漏（枚举 5 处，全部「改到」）。

### 1.3 新基线（真 SQL）与新旧并录

| 量 | 旧生产形（D1 前） | **新生产形（D1 后）** | 差 |
|---|---|---|---|
| 主集 Recall@5 | 0.5444（49/90） | **0.6444（58/90）** | **+0.1000（+9 题）** |
| MRR | 0.1511 | **0.5637** | +0.4126 |
| 逐章最小 Recall@5 | 0.2222 | 0.2222 | 0 |
| p95（本地，预算 150ms） | （Stage-1 轮：68ms / 143ms 两笔） | **54ms**（p50 25ms、max 26739ms、overBudget 9/450） | — |
| 19 例回归 | 19/19 | 19/19（cross-subject=0）【引交付报告：WP2/D1】 | 0 |

- 新旧值来源：旧值 = `docs/kb-vector-topic-decision.md` §6.1 第 3 组（Stage-1 封存，真 SQL 49/90、MRR 0.1511）；新值 = **D1 轮真 SQL 金标**原件 `/tmp/golden-instrumented.log`（设备侧 `09-23 16:41`：`route=B-bare->top5(v1生产形状, matched-first) … p95=54ms`、`主集 Recall@5(全样本命中) = 0.6444444444444445 (58/90)`、`MRR(最优名次) = 0.5637037037037038`）【本轮复核：读原件】。
- **独立复跑逐位复现**【本轮复核】：WP5a 轮 `/tmp/wp5a/logcat-golden2.txt`（设备侧 `09-23 19:42`，`-wipe-data` 冷启后）主集 `0.6444444444444445 (58/90)`、MRR `0.5637037037037038`、**逐章 10 行与 32 条 MISS（含 rank）与 D1 轮逐行一致**（`diff` 退出码 0，核对了两次运行的全部判分内容行）；p95 该轮 22ms、overBudget 2/450。该轮 XML `tests=1 failures=0 errors=0`、`BUILD SUCCESSFUL in 2m 6s`。
- 地板【本轮复核：读源码】：`GoldenRetrievalInstrumentedTest.kt:327-328` 现为 `RECALL_MAIN_FLOOR = 0.64`、`MRR_MAIN_FLOOR = 0.56`，断言在 `:209-218`，旧值 `0.54 / 0.15` 就地留档在 KDoc。

### 1.4 JVM 镜像 ↔ 真 SQL 对照（零漂移口径）

| 项 | JVM 镜像（`build/golden-jvm-metrics.txt`） | 真 SQL（D1 轮 / WP5a 轮） | 结论 |
|---|---|---|---|
| 主集 Recall@5 | `0.6444444444444445 (58/90)` | `0.6444444444444445 (58/90)` | 逐位相同 |
| MRR | `0.5637037037037038` | `0.5637037037037038` | 逐位相同 |
| 逐章 10 行 | 见上文件 B 段 | 同 | **10/10 行逐字相同**【本轮复核：`diff` 退出码 0】 |
| MISS | 32 条 | 32 条 | **集合 32/32 相同**；仅 2 条 rank 差（`函数图象的翻折变换` 71↔69、`物体看作质点的条件…` 57↔56），落在既有口径说明的「父节点块的序不同（镜像按包内序、真 SQL 按 rowid 序）可差 1~3 位」内【本轮复核：`diff` + rank 剥离后 `diff`】 |

⇒ JVM 镜像与真 SQL 的 **12 个质量数（主集 + MRR + 逐章 10）逐项相同**，镜像保真度满足 `docs/kb-vector-topic-decision.md` §5 的漂移纪律（判定仍以真 SQL 为准）。

### 1.5 Stage-1 六格表在 D1 后的读数（诊断段，判读线未动）

D1 后重跑 JVM 侧 Stage-1 实验台，`build/stage1-experiments.md` §10 的六格【本轮复核：读原件】：

| 候选排序 | 返回形态 | 主集 | 命中 | 逐章最小 | MRR |
|---|---|---|---|---|---|
| v1（裸 B 路 SQL 镜像） | **新生产形 matched+parents** | 0.6444 | 58/90 | 0.2222 | 0.5637 |
| v1 | matched-only（诊断） | 0.6444 | 58/90 | 0.2222 | 0.5637 |
| v1 | 旧生产形 parents+matched（历史） | 0.5444 | 49/90 | 0.2222 | 0.1517 |
| FTS5 bm25（臂 A） | **新生产形 matched+parents** | 0.6556 | 59/90 | 0.2222 | 0.5569 |
| FTS5 bm25（臂 A） | matched-only（诊断） | 0.6556 | 59/90 | 0.2222 | 0.5569 |
| FTS5 bm25（臂 A） | 旧生产形 parents+matched（历史） | 0.5333 | 48/90 | 0.1111 | 0.1552 |

两条读法（都是**诊断段**口径，不改 §11 判读）：

1. **返回形态不再吃分**：新生产形与 matched-only 逐位相同 ⇒ 「生产口径折扣」0.1222（臂 A）/ 0.1000（v1）在 D1 后归零。
2. **排序之争回到 1 题差**：新生产形下臂 A 0.6556 vs v1 0.6444（差 1 题）；旧形态下两侧同幅下降、结论曾是「FTS5 增益不成立」。**FTS5 排序是否替换 v1 排序属另一次判决**，按 Stage-1 §11 判读线与预注册口径另判——本报告不给出该结论。

---

## 2. WP3：20k 基准墙钟门的方法学修正（只改测量方法，预算一字不动）

### 2.1 症状与三轮误红样本（档 2，同一测试方法）

`KnowledgeContextRetrievalInstrumentedTest#largeSubjectRecallRemainsBoundedOnRoom`（20,000 合成点 + 19,999 关系，API-34 emulator）三轮全红，红的都是 recall 墙钟门（预算 150ms）。**原始断言消息**【本轮复核：`grep` 读 `/tmp/kcr_run{1,2,3}.log`，与 KD-25 登记逐字一致】：

| 轮 | 原始消息（截取 samples） | 旧口径 p95 | 稳态样本 |
|---|---|---|---|
| run1 | `Room recall p95 was 492ms; samples=[492, 44, 46, 57, 57, 53, 50, 45, 50, 49]` | 492 | 44–57ms |
| run2 | `Room recall p95 was 21718ms; samples=[74, 21718, 180, 55, 51, 45, 52, 50, 45, 41]` | 21718 | 41–55ms（**双离群**） |
| run3 | `Room recall p95 was 1267ms; samples=[1267, 54, 45, 46, 50, 52, 47, 53, 39, 43]` | 1267 | 39–54ms |

三轮的**确定性守卫**（EXPLAIN 索引/无 `SCAN feature`、选中语义、19 例回归 19/19）均通过——失败点在守卫之后的墙钟断言，指向测量口径而非查询退化。

### 2.2 口径变更（代码位置）

| 项 | 改前 | 改后 | 位置 |
|---|---|---|---|
| p95 口径 | `((size * 95 + 99) / 100 - 1)`（n=10 时**恒等于 max**） | `((size - 1) * 95) / 100`（最近秩） | `KnowledgeContextRetrievalInstrumentedTest.kt:383-386` |
| 样本数 | `PERFORMANCE_SAMPLE_COUNT = 10` | `= 24` | 同文件 `:500` |
| recall 预热 | 1 轮（无注释） | `RECALL_WARMUP_COUNT = 3` 轮（不计入统计，`:182`） | 同文件 `:501` |
| 预算 | 150ms / 250ms × CI 系数 | **一字未动**（`:522` 起） | 同文件 `:522-523` |

- 新口径与 review 腿**逐字一致**【本轮复核】：`core/database/src/androidTest/kotlin/com/tingyun/smartmistakebook/core/database/KnowledgeResearchReviewInstrumentedTest.kt:140` = `val p95 = durations[((durations.size - 1) * 95) / 100]`。
- n=24 时新口径取 `sorted[21]`（第 22 小）⇒ 容忍 2 个离群；确定性守卫与全部断言语义保留。
- KD-25 的完整登记（含 re-open 条件）见 `docs/known-defects.md`（本提交同步）。

### 2.3 独立算术复核（本轮实跑）

```python
# 旧口径 = ((n*95+99)//100 - 1)  新口径 = ((n-1)*95)//100
```
- 三轮红样本：旧口径 n=10 → **492 / 21718 / 1267**（全红，与日志一致）；新口径**仍按 n=10** → **57 / 180 / 54** ⇒ run2 仍红（180 > 150）——**印证「n=10 + 换口径不够、n=24 才够」**（run2 是双离群，去首样本后 max 仍 21718，再排最大 180 仍超预算；run1/run3 的离群恰在首位只是巧合）。
- 四次绿跑打印值与新口径复算：recall **78 / 90**、mastery **189 / 182** —— 4/4 命中；红演示轮 n=24 复算 = **78**（= 打印值）。

### 2.4 红→绿演示与连跑（读原件复核）

| 运行 | 命令 | 结论 | 关键数 |
|---|---|---|---|
| 红演示 | recall 预算临时改 1ms | `BUILD FAILED in 21m 38s`、XML `tests=2 failures=1` | `p95 was 78ms; samples=[…24 个样本…]` ⇒ **门仍能红**；还原后文件 sha256 与改动后一致（`b4ca2450…`） |
| 绿 A | 还原后原样跑 | `BUILD SUCCESSFUL in 22m 37s`、`tests=2 failures=0` | recall p95 78ms；mastery p95 189ms；19 例回归 hit=19/19 |
| 绿 B | 再次原样跑 | `BUILD SUCCESSFUL in 23m 28s`、`tests=2 failures=0` | recall p95 90ms；mastery p95 182ms；19 例回归 hit=19/19 |

【本轮复核：`grep`/`tail` 读 `/tmp/wp3/red-demo.log`、`/tmp/wp3/green1.log`、`/tmp/wp3/green2.log` 与 `/tmp/wp3/green{1,2}/logcat-*.txt`，Gradle 结论与 p95 断言行逐字一致；n=24 的样本列表在存档中逐条可见】

### 2.5 残余风险（照录 KD-25，不淡化）

1. 离群根因（设备侧调度 / GC / 内存压力）**未归因**：本修复只保证「≤2 个离群不再误红」，不保证门能把真实退化与噪声分开；真实退化的确定性判据仍是查询计划守卫与选中语义断言。
2. **mastery 腿余量更小**：绿 A 轮 p95 189ms / 250ms = 76%（该轮有一个 259ms 样本落在 p95 之上）；CI 的 4 倍系数（1000ms）覆盖得了，但 runner 变慢或系数被改时 mastery 腿会先红。
3. 本轮**未单独演示 mastery 门的可红性**（受断言顺序限制，红演示只打到 recall 门）；mastery 门的证据是两次绿跑中该断言被实际执行且通过。
4. 【本轮新增观察，P3】KD-25 与代码注释引用 review 腿时写作 `KnowledgeResearchReviewInstrumentedTest.kt:140`，该文件实际位于 **`core/database/src/androidTest/...`**（与 recall 腿不在同一模块）；行号与口径本身逐字一致，仅引用时宜带模块前缀。**未改任何代码/文档以迁就此观察**（不属本阶段范围）。

---

## 3. dense 离线验证（WP4）：数据、判定与端侧化建议

### 3.1 对比表（判读对象 = 冻结金标 90 条，sha256 `7c004b76…`）

【引交付报告 + 本轮核对原件：`build/stage2-dense-offline-summary.txt`、`build/stage2-dense-verdict.json`、逐臂 `build/stage2-dense-offline-metrics-*.txt` ×15】

| 臂 | 模型 | 主集 Recall@5 | 逐章最小 | MRR | 相对臂 A 0.6556 | 判读 |
|---|---|---|---|---|---|---|
| **D-fuse-α0.5**（主判读） | Qwen3-Embedding-0.6B | **0.7778（70/90）** | **0.2222** | 0.6335 | +0.1222 | **主判据未过**（逐章最小 <0.60） |
| D-only | Qwen3-Embedding-0.6B | 0.7889（71/90） | 0.4444 | 0.6122 | +0.1333 | 主判据未过 |
| D-rrf60（对照，不参评） | Qwen3-Embedding-0.6B | 0.7556（68/90） | 0.3333 | 0.5915 | +0.1000 | （对照） |
| D-only | bge-small-zh-v1.5 | 0.6444（58/90） | 0.3333 | 0.5050 | −0.0111 | 主判据未过 |
| D-fuse-α0.5 | bge-small-zh-v1.5 | 0.7444（67/90） | 0.3333 | 0.6200 | +0.0889 | 主判据未过 |
| Lex-A（参照，词面腿） | —（FTS5 bm25） | 0.6556（59/90） | 0.2222 | 0.5569 | （基准） | Stage-1 判读数，本脚本从 TSV 原样复现 |
| v1 裸 B5（参照） | — | 0.5444（49/90） | 0.2222 | 0.1511 | — | 预注册基线（**旧**生产形，Stage-1 封存） |
| C-upper（上界参照） | — | 0.7889（71/90） | 0.5556 | 0.6444 | — | 章门控上界，不可上生产 |

- **主判据（≥0.75 且 逐章最小 ≥0.60）**：两臂都**未通过**——主集已过线（0.7778 / 0.7889），卡在**逐章最小**（0.2222 / 0.4444）。
- **附加判据（机制自证：融合 > 臂 A 0.6556）**：**成立**（0.7778 > 0.6556）。
- 机械判读（`build/stage2-dense-verdict.json`）：`passed: false`、`mechanismPassed: true` ⇒ 按规格 §1.2 **第 3 种归宿 = 不立项**。
- 参考口径：本轮判读形 = matched-only，而 matched-only 与**现行生产形（D1 后 = matched 优先）恒等**（脚本断言 `stage2_score.py:290-294`；本文 §1.5 在 JVM 侧独立得到同一结论）⇒ **上表两臂的数就是现行生产形态下的数**。表中 Lex-A 的「生产形」列（0.5333）是**旧**形态，D1 后其生产值 = 0.6556。

### 3.2 口径自证（先证这份数可信）

- 词面腿输入 = `build/stage2-dense-offline-lexical.tsv`（**本轮复算 sha256 = `ea491b55afd66a033273d274aaea281ae738eb5387e5d374eacefece207dce2b`，32,769 行 + 表头**，与产物声明一致）；回读重算臂 A = 0.6556/59、MRR 0.5569、MISS 31 条，与 `build/stage1-metrics-A.txt` 逐行一致；Python 侧再从 TSV 复现 matched-only 0.6556 与**旧**生产形 0.5333（48/90）/MRR 0.1552，与 Stage-1 §10 封存值逐位相同。
- 编码保真（与模型卡用法 sentence-transformers 对拍）：bge 全量 28,932 文档 + 90 查询 cosine 最小 **1.000000**；qwen3（fp32）**0.99999988**（bf16 下 0.997252 ⇒ 差异是 dtype）。token 截断 0/0（两模型）。查询/文档侧指令、池化方式按模型自带配置取自实测。
- 坐标：Qwen3-Embedding-0.6B rev `97b0c614…`（apache-2.0）、bge-small-zh-v1.5 rev `7999e1d3…`（mit）；许可证经 `huggingface.co` 与 `hf-mirror.com` **两通道互证**（规格 §5 第 1 条由「单通道待验证」升级）。
- 语料复算：`atomic 3572 topics 398 docs 3970 aliases 25360 vectors 28932`（与规格 §2.2 一致）。

### 3.3 失败面归因（数据，非判读数）

主判读臂 20 例 MISS 的归类（【本轮复核：对 `build/stage2-dense-offline-summary.txt` 计数】）：

| 归类 | 例数 | 含义 |
|---|---|---|
| 排序深度不足 | **13** | 两腿 top-5 都未命中，但至少一腿在放宽窗口（前 256）内可找到 |
| 融合回归 | **7** | dense 腿 top-5 命中、α=0.5 融合反而未命中（例：`镁的化学性质`、`对勾函数`、`合力范围` 等 dense top-1/2 命中被词面近邻挤掉） |

- **20/20 例在至少一腿的 256 窗口内可及** ⇒ 本轮数据里**没有**「索引/内容根本够不到」这一类；主判读臂的失败面全是**排序深度与融合口径**问题。规格 §1.2 第 3 种归宿里「dense 也捞不回的题归入内容/绑定问题」的那半句，**在本轮数据中找不到支撑**（注意：expectedSlug 的**绑定正确性**是另一根轴（A-18 类），本轮未测，不能由本实验证伪）。
- 诊断段（显式非判读数，不得据此调参）：α=0.3 → 0.7000；**α=0.7 → 0.8222（74/90，逐章最小 0.4444）**；z-score → 0.7556；候选域限 ATOMIC → 0.7778（与主臂同数 ⇒ 规格 §2.2「TOPIC 不进向量集」这一缺口无量化影响）。
- 共同瓶颈章：`物理必修第一册·第三章·相互作用`（融合 2/9、dense 单路 4/9、词面腿同为 2/9）。

### 3.4 dense 是否够格端侧化 —— 只给数据与建议（立项仍待用户裁）

**结论先说：按预注册归宿，不立项。** 以下是支撑它的数据与「若要推进」的账面，决策权仍在用户（规格 §3.7：端侧化每一步都要用户先开口）。

> **指针（2026-09-24 用户改判，不改本段结论）**：用户当日裁定 **dense 立项、先小档跑通端侧闭环**（bge-small-zh-v1.5 int8 + LiteRT），闭环达标后再决定是否换大档（Qwen3-Embedding-0.6B）。本段「不立项」是 Stage-2 的预注册归宿，**已被该改判取代**；预注册线 0.75/0.60 与本节两条判据**未改、也未被满足**（小档真机融合主集 0.7444、逐章最小 0.4444）。Stage-3 的端侧落地、真机数与「换大档判据」见 `docs/kb-stage3-report-2026-09-24.md`。

**(a) 质量侧**：主判据未过，且卡的是**逐章最小**这一条（0.2222 / 0.4444 对 0.60，差距不是 1–2 题的量级）；主集虽已过 0.75，但预注册判据是两条同时满足。

**(b) 端侧成本侧（本轮一步未做，只有账面）**：

| 坐标 | Qwen3-Embedding-0.6B（主判读） | bge-small-zh-v1.5（下限对照） | 来源 |
|---|---|---|---|
| 参数 / 快照体积 | 595,776,512（BF16）；**1151.6 MiB**（本机实测快照） | 24M 量级（审计口径）；**183.3 MiB** | `build/stage2-dense-offline-env.txt` §2 |
| 向量维度 / 向量表 fp32 | 1024；**118,505,600 B ≈ 113 MiB**（本轮实测 .npy） | 512；59,252,864 B ≈ 56.5 MiB | 同上 §3 + 规格 §3.4 账 |
| 向量表 int8（规格账） | ≈ 28.3 MiB（估算） | ≈ 14.1 MiB（估算） | 规格 §3.4 |
| 质量（融合 / 单路） | **0.7778 / 0.7889** | 0.7444 / 0.6444 | 本报告 §3.1 |
| 许可证 | apache-2.0（两通道互证） | mit（两通道互证） | env §2 |

⇒ qwen3 的模型件比 bge 大 **≈6.3×**、向量维度 2×，换来融合主集 **+3.3pp**；而 bge 的单路（0.6444）在**主集上与 D1 后的 v1 生产形基线同值**（都是 58/90——**不同题集、仅命中数相同**：我核对了两者 32 条 MISS 的 expectedSlug 集合，`diff` 不同，不是同一批题）。**端侧的量化件、Kotlin tokenizer 对拍、APK 增量、真机 p95 一步都没做**（规格 §3 全节未施工）⇒ 任何「够格/不够格」的端侧判断目前**没有任何实测支撑**，本报告只能给质量与账面。

**(c) 建议（不构成立项，供裁定）**：

1. **不建议现在立项**：预注册归宿是「不立项」，且未过的那条判据（逐章最小）离门槛最远；25,360 条别名向量 + 0.6B 模型件进 APK 的代价要用真机数换，而收益目前只有主集 +3.3pp 且不达标。
2. 若用户仍要推进，**账面最省的不是 qwen3 而是 bge**（MIT 可再分发、模型件小 6 倍、向量表 2 倍小、int8 ONNX 23.9MB 有审计锚点）；但前提是先解决逐章最小——数据（§3.3）指向**排序深度与融合口径**（13 例深度不足 + 7 例融合回归，α=0.7 诊断回到 0.8222），而不是「补内容」。
3. 「内容/绑定」这根轴（A-18 类绑定正确率）本轮**没有出数**，不宜在检索器上继续加机制，也不宜据本报告断言内容无缺陷。

---

## 4. UNVERIFIED 与遗留清单

### 4.1 本轮未做 / 未验证（不得当已通过）

| # | 项 | 状态 |
|---|---|---|
| 1 | 设备侧任何测试（金标真 SQL、19 例回归、KCR 墙钟腿、T6 代号通道、W-4 债、冷启） | **本轮未跑**。金标真 SQL 与 KCR 墙钟腿的数字全部来自已存档 logcat/XML（`/tmp/golden-instrumented.log`、`/tmp/wp5a/*`、`/tmp/wp3/*`）与交付报告；19 例回归 19/19 为【引交付报告】 |
| 2 | JVM 侧测试复跑（`Stage1LexicalLabTest`、`GoldenRetrievalJvmTest`、`RoomTutorToolRunnerTest` 等） | **未跑**；`build/golden-jvm-metrics.txt`、`build/stage1-experiments.md` 是其上一次运行的产物（文件时间 2026-09-24 00:26 / 00:27，晚于 D1 代码改动 00:09–00:12）。本轮只做了强制重编译检查（见 4.2） |
| 3 | dense 端侧链（§3 全节） | 一步未做：无量化件、无 Kotlin tokenizer、无 APK 增量、无真机 p95 |
| 4 | WP2 的 19 例回归与 p95=54ms 之外的「档 1 绿」细节 | 【引交付报告】未逐条复核 |
| 5 | knowledge-production 与 `.agent_*`/`out/`/`tmp/` 等另一会话的脏文件 | **不属本阶段，一律未碰、未提交**（见 §5 提交清单） |

### 4.2 本轮实际跑的检查：提交前强制重编译（4/4 绿）

逐任务 `--rerun`（首次一条命令只重跑了 androidTest 那一个，故改为逐条跑），**全部真正重新执行**（非 UP-TO-DATE）：

| 任务 | 宿主改动 | 结果 |
|---|---|---|
| `:core:database:compileDebugKotlin` | `RoomKnowledgeBaseStore.kt`（D1） | `BUILD SUCCESSFUL in 23s`，1 executed |
| `:core:data:compileDebugKotlin` | 生产侧（本阶段未改） | `BUILD SUCCESSFUL in 20s`，1 executed |
| `:core:data:compileDebugUnitTestKotlin` | `RetrievalBenchmark/Stage1Experiment/Stage1LexicalLab*/Stage2LexicalScoresExportTest` | `BUILD SUCCESSFUL in 9s`，1 executed，进程退出码 0 |
| `:core:data:compileDebugAndroidTestKotlin` | `GoldenRetrievalInstrumentedTest/KnowledgeContextRetrievalInstrumentedTest` | `BUILD SUCCESSFUL in 13s`，1 executed |

命令：`./gradlew.bat <任务> --rerun --console=plain`。仅编译告警（`w:`，均为既有代码/其它改动的告警，无 `e:`/error）——**编译通过 ≠ 测试通过**：行为断言（真 SQL 判分、19 例回归、墙钟门）仍按 §4.1 第 1–2 条列为未复跑。

### 4.3 遗留（明确留给下一次）

1. **build/* 全部不入版本库**（`.gitignore:14` = `**/build/`）：本阶段全部证据产物（`stage2-dense-*`、`stage1-experiments.md`、`golden-jvm-metrics.txt`、`stage1-metrics-*`、词面腿 TSV 及其 sha256）都只在**本机磁盘**，文档里引用的 sha256 需按命令重跑才能复算。这是仓库既有约定（Stage-1 同），但**引用它们的段落无法在 clone 后自证**——如需证据随库，须另开一次"产物入库"裁定（含体积账）。
2. **FTS5 排序是否替换 v1 排序**：D1 后生产口径下臂 A 0.6556 vs v1 0.6444（1 题差，JVM 复算）。属另一次判决，判读线未动。
3. **dense 立项**：待用户裁定（§3.4 只给数据与建议）。
4. **KD-25 残余风险**（离群未归因、mastery 余量 76%、mastery 门未单独演示可红性）与 re-open 条件见 `docs/known-defects.md`。
5. **A-18 类绑定正确率**：本轮未测。
6. **日期口径**：文件名/计划日 `2026-09-23` 与源码注释的落地日 `2026-09-24` 两处并存（跨零点），后续引用时按上下文取值。

---

## 5. 本阶段提交范围（收尾代理登记）

- 提交清单 = `.wf-manifest-stage2.txt` 全路径 ∩ `git status` 实际变更；显式排除 `.wf-manifest-stage2.txt` 自身、`tools/kb_build/tables/chapter_map.csv`（另一会话飞行改动）、`.agent_*/out//tmp/` 与清单外脏文件。
- **被 gitignore 挡下的清单项**：`build/stage2-*` 全部路径（`.gitignore:14`）——见 §4.3 第 1 条；它们**不在**本次提交里，与本报告"产物只在磁盘"的结论一致。
- 生产代码改动仅 1 处（`RoomKnowledgeBaseStore.kt:131`）；其余为测试侧镜像/钉数、`docs/known-defects.md`（KD-25）与本报告及三份文档指针（`docs/kb-vector-topic-decision.md` §3.2 现状段、`docs/kb-lexical-stage1-experiments.md`、`docs/kb-fts5-production-plan.md`）。
- 金标本阶段**未增未减未改**：三处 sha256 一致（`7c004b76…`）。
