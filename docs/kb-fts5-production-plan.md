# FTS5 臂落生产（WP-D）施工方案——只写方案、不施工（2026-09-23 立）

> **回答的问题**：Stage-1 已证明「FTS5 + `bm25()`」这条词面腿比生产现状的排序引擎明显更好（主集 0.6556 vs 0.5444、MRR 0.5569 vs 0.1511），本文件回答**怎么把它落进生产**、落进去之前先钉死哪些决定、以及**回退一步怎么走**。
> **血缘**：Stage-1 计划 WP-D 原文 `.zcode/plans/plan-sess_29da3326-fc7f-4f10-8270-e35957460dac.md:42-46`；预注册与臂定义 `docs/kb-lexical-stage1-experiments.md`；实验结果 `build/stage1-experiments.md`（+ `build/stage1-verdict.json`）；判定与回滚记录 `docs/kb-vector-topic-decision.md` §3/§3.1–§3.3/§6；缺陷登记 KD-24 `docs/known-defects.md:861`。
> **性质**：**施工方案（不施工）**。本文件不改任何代码、不调任何判读线、不动金标集。§6 的两项裁定拿到之后才可开工。

---

## 1. 现状与收益证据

### 1.1 生产现状（v1 裸 B5，零回归形状）

| 项 | 值 | 出处 |
|---|---|---|
| 生产形状 | v1 截断索引（`MAX_SEARCH_FRAGMENTS=16`/`MAX_NODE_FEATURES=192`）× **裸 B 路 limit=5** | `docs/kb-vector-topic-decision.md` §3.2 第 3 组 / §6.2 点 4；`KnowledgeSearchFeatureExtractor.kt:17-28` |
| 金标主集 Recall@5 | **0.5444（49/90）**（真 SQL = JVM 镜像，零漂移） | 同上 §3.3：`route=B-bare->top5(v1生产形状) indexVersion=1 cases=90 samplesPerQuery=5 p95=143ms p50=20ms` |
| MRR | **0.1511** | 同上 |
| 逐章 | 10 值 0.7778/0.2222/0.7778/1.0/0.4444/0.7778/0.3333/0.2222/0.2222/0.6667（**≥0.80 仅 1 章**） | 同上 §3.3 |
| p95（真 SQL，150ms×CI 门） | 143ms（p50=20ms；含 ~16.6s 单样本停顿族） | 同上 §3.3 / §3.2 波动注记 |
| 19 例回归 | **19/19、cross-subject=0** | 同上 §3.2 |

### 1.2 FTS5 臂的收益（Stage-1 判读数）

| 项 | FTS5 臂 A | 生产现状 | 差 |
|---|---|---|---|
| 主集 Recall@5 | **0.6556（59/90）** | 0.5444（49/90） | **+11.1pp** |
| MRR | **0.5569** | 0.1511 | **3.7×** |
| 逐章最小 | 0.2222 | 0.2222 | 持平（两者都在"物理必修1·第三章"最低） |

出处：`build/stage1-metrics-A.txt`（`route=stage1-A-fts5-bm25`，头 5 行 + `Recall@5(主集)=0.6555555555555556 (59/90)` / `MRR=0.5568518518518518`）、`build/stage1-verdict.json`（`chosenArm: "A"`）。逐章与 MISS 清单见 `build/stage1-experiments.md` §4/§5。

**排序口径被独立钉死（不是"我们以为 FTS5 这样算"）**：臂 A' 用自写 `documents/postings/metadata` + df/BM25 SQL（k1=1.2、b=0.75、tf 二值）复算，与 FTS5 的 `bm25()` **主集 59/90 = 59/90、MRR 0.5568518518518518 = 0.5568518518518518、逐题 top5 序列与放宽窗口（前 256）序列逐题相等（容差 0）**——证据 `build/stage1-experiments.md` §6（含逐题逐字日志）、`build/stage1-verdict.json` 的 `fts5VsCustomAgree: true`。出数过程中曾因**符号方向**写错（FTS5 的 `bm25()` 返回负分、升序即最优）当场对账报红并修正，说明这套对账是有牙的（同 §6）。

**归因（为什么换排序引擎就有这个差）**：生产 SQL 的排序是"命中特征数 DESC → 粒度 → 名字 → id"（`core/database/src/main/kotlin/.../dao/ProblemOrganizationDao.kt:150-168`，`COUNT(DISTINCT feature.search_feature) DESC` 在 `:163`），**没有 IDF、没有文档长度归一化**（登记册 D-01）；金标 MISS 的共同形态是"自然语言问句 vs 词条式节点名"——公共 2/3-gram 少 ⇒ 二值 count 低 ⇒ 被公共 gram 多的大题节点挤掉（`docs/kb-vector-topic-decision.md` §3 的 MISS 形态段，自由落体例：count=1、裸 B 路 rank 499/546）。FTS5 的 `bm25()` 带 IDF 与长度归一化，正是对这条病。

### 1.3 未过预注册线，不影响"该不该上"这层判断

Stage-1 判读线（主集 ≥0.75 且逐章 ≥0.60）**未过**（实测 0.6556/0.2222，`docs/kb-lexical-stage1-experiments.md` §1；`build/stage1-experiments.md` §10 结论：Stage-1 未达标 ⇒ dense 转立项）。两者回答的不是同一个问题，必须分开读：

- 预注册 0.90/0.80（D12）与 Stage-1 0.75/0.60 回答的是「**词面栈能否单独服务金标**」——结论已锁：不能，dense 兜底议题保持开启（`docs/kb-vector-topic-decision.md` §6，本方案不重开、不反对）。
- 本方案回答的是「**同一条词面腿内部，排序引擎换不换**」——对照对象是**生产现状 0.5444**，不是判读线。+11.1pp 与 MRR 3.7× 是这个问题的答案。
- 两条并存的形态：词面腿用 FTS5（本方案）**并且** dense 兜底议题继续走 Stage-2 离线验证（`docs/kb-stage2-dense-spec.md`）；两者互不阻塞，落生产顺序独立。

### 1.4 ⚠ 口径事实（先读，再决策）：臂 A 的数与生产返回形态不同构

- 臂 A 的 0.6556 是「**matched 节点 top-5**」口径——`build/stage1-verdict.json` 的 evidence 逐字：`top5 无父节点前置`。
- 生产 `readSubjectKnowledgeRecallCandidates` 的返回形态是 **`parents + matched`**（`core/database/src/main/kotlin/.../RoomKnowledgeBaseStore.kt:125-133`：先取 matched 的父 topic，再前置拼接），KNOWLEDGE_READ 直接取前 5 → **父 topic 占席**（这是 v1 裸 B5 基线的既有语义，`docs/kb-vector-topic-decision.md` §1.1 钉定的判分口径）。
- Stage-1 扫描诊断里已量过这条：`scan-A-parentprefix = 主集 0.5333 / 逐章最小 0.1111 / MRR 0.1552`（`build/stage1-experiments.md` §9，**显式标注非判读数**）。
- 读法：把 FTS5 排序**原样装进**父节点前置的返回形态，预期落在 **≈0.53**——**改进会被返回形态吃掉**（甚至略低于基线 0.5444）。所以 §3 步骤 4 把"返回形态"列为**必须先裁定的决定点 D1**；施工时两种形状都要用真 SQL 实测，不许拿扫描数当结论。
- **【追加指针，2026-09-24；本节其余字面与 §6 的待裁项为历史记录，不改】** 生产形态已于 Stage-2（计划日 2026-09-23，代码注释的落地日 2026-09-24）**改为 matched 优先**——`RoomKnowledgeBaseStore.kt:131` 现为 `return (matched + parents)`，真 SQL 金标新基线 **0.6444（58/90）/ MRR 0.5637 / 逐章最小 0.2222**（旧形 0.5444 / 0.1511 留档）；即本节 (b) 形态已成生产现状，**但"FTS5 排序引擎换不换"（本方案的 D1/§6 第 1 项）仍未裁**：D1 后生产口径下臂 A 0.6556 vs v1 0.6444（JVM 复算，差 1 题），按 Stage-1 §11 判读线与预注册口径另判。见 `docs/kb-stage2-report-2026-09-23.md`。

---

## 2. 施工范围与影响面（先枚举，再动手）

| 面 | 位置 | 本方案要求的处置 |
|---|---|---|
| 依赖 | `gradle/libs.versions.toml:14,50`（`sqlite = "2.7.0"`、`androidx-sqlite-framework`）；`core/database/build.gradle.kts`（`implementation(libs.androidx.sqlite.framework)`） | 增 `androidx.sqlite:sqlite-bundled`（同一 `sqlite` 版本 ref），换/并存框架驱动 |
| 驱动 | `StudyDatabase.kt:353`（持久库）、`:362`（in-memory，`openInMemory`） | `.setDriver(AndroidSQLiteDriver())` → `BundledSQLiteDriver()` |
| 驱动（测试旁路） | `core/database/src/androidTest/.../PerformanceGateTest.kt:38` 裸 `Room.databaseBuilder`（未设驱动 = 系统 SQLite） | 显式设同一驱动，否则该门测的不是生产形态 |
| 索引抽取 | `KnowledgeSearchFeatureExtractor.kt:28`（`INDEX_VERSION = 1`） | 1 → 2（规则未变，但索引形态变了：新增 FTS 行） |
| 索引构建 | `RoomKnowledgeBaseStore.kt:149-175`（`ensureKnowledgeSearchIndex`：锚点门 + 只补缺 + 整科换血） | 换血分支一并重建/清空 FTS 行；写入与特征行同事务 |
| 召回 | `RoomKnowledgeBaseStore.kt:108-133`（返回形态）、`ProblemOrganizationDao.kt:150-168`（现排序 SQL） | 增 FTS5 召回查询；旧 SQL 是否保留见 D1 |
| 迁移 | `StudyDatabase.kt:126`（`STUDY_DATABASE_VERSION = 51`）、`KnowledgeSearchIndexStateMigration.kt:23`（锚点表） | 版本 51 → 52 建 FTS5 虚表（对齐仓库既有先例 `LibrarySearchFtsMigration.kt:49`） |
| 金标测量台 | `GoldenRetrievalInstrumentedTest.kt:323-324`（地板 0.54/0.15）、`:336`（p95 门）；`RetrievalBenchmark.kt:396`（`BRouteMirrorIndex`）、`:236`（`MISS_PROBE_LIMIT=256`） | 地板按新基线抬高并记旧值；JVM 侧新增 FTS 镜像 |
| 既有 FTS（不可回归） | `library_search_fts`（FTS4，`LibrarySearchFtsMigration.kt:49`、`RoomLibrarySearchStore.kt:303-320` 同步触发器） | 驱动切换后必须复跑图书馆检索门（`PerformanceGateTest` 的 `ftsSearchCount` 路径） |

### 2.1 制品级证据（本会话实测，可复跑；步骤 0 的运行探针仍是门）

`androidx.sqlite:sqlite-bundled-android:2.7.0`（KMP 根模块 `androidx.sqlite:sqlite-bundled:2.7.0` 的 Android 变体）：

- AAR **2,551,019 B（2.43 MiB）**，四 ABI；arm64-v8a `libsqliteJni.so` **1,281,456 B（1.22 MiB）**；armeabi-v7a 1.20 MiB / x86 1.09 MiB / x86_64 1.13 MiB → 四 ABI 合计解包 ≈ 4.8 MiB（按 ABI 拆分后单臂 ≈1.2 MiB）。
- 四 ABI 的 **PT_LOAD `p_align = 0x4000`**（1/16KB 页合规；16KB 设备兼容性前提）。
- arm64 `.so` 内含版本串 **`3.50.1`**；含编译开关串 `ENABLE_FTS3` / `ENABLE_FTS3_PARENTHESIS` / `ENABLE_FTS4` / **`ENABLE_FTS5`** / `ENABLE_MATH_FUNCTIONS` / `ENABLE_NORMALIZE` / `ENABLE_RTREE` / `ENABLE_STAT4`；tokenizer 串 `unicode61` / `ascii` / `porter` / `trigram`。
- 意义：(a) FTS5 **在制品里**（不只是构建脚本声明）；(b) 既有 FTS4 的 `library_search_fts` **不会因换驱动而消失**（FTS3/4 也在制品里）；(c) Apache-2.0（POM 的 `<licenses>`）。
- 复核命令（本会话实跑，逐字）：

```bash
python - <<'PY'
import os, struct, tempfile, urllib.request, zipfile, re
url = "https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-bundled-android/2.7.0/sqlite-bundled-android-2.7.0.aar"
p = os.path.join(tempfile.gettempdir(), "sqlite-bundled-android-2.7.0.aar")
if not os.path.exists(p):
    open(p, "wb").write(urllib.request.urlopen(url, timeout=120).read())
z = zipfile.ZipFile(p); print("aar bytes:", os.path.getsize(p))
for n in sorted(x for x in z.namelist() if x.endswith(".so")):
    d = z.read(n); cls = d[4]
    if cls == 2:
        ph, pes, pn = struct.unpack_from("<Q", d, 0x20)[0], struct.unpack_from("<H", d, 0x36)[0], struct.unpack_from("<H", d, 0x38)[0]
    else:
        ph, pes, pn = struct.unpack_from("<I", d, 0x1c)[0], struct.unpack_from("<H", d, 0x2a)[0], struct.unpack_from("<H", d, 0x2c)[0]
    al = []
    for i in range(pn):
        o = ph + i * pes
        if struct.unpack_from("<I", d, o)[0] == 1:
            al.append(struct.unpack_from("<Q", d, o + 48)[0] if cls == 2 else struct.unpack_from("<I", d, o + 28)[0])
    print(n, len(d), [hex(a) for a in al], sorted(set(re.findall(rb"3\.\d{2}\.\d+", d)))[:2],
          "fts5=", b"fts5" in d, "bm25=", b"bm25" in d)
PY
```

> 实测输出（本会话）：四行 `.so`，全部 `['0x4000','0x4000','0x4000','0x4000']`、版本候选 `3.50.1`、`fts5=True bm25=True`。
> **注意区分**：Stage-0 记录的"arm64 `.so` 2.39MiB、四 ABI `p_align=0x4000`"（`docs/kb-lexical-stage1-experiments.md` §5 转引）与本会话实测（AAR 2.43 MiB / arm64 `.so` 1.22 MiB）**口径不同**，以步骤 1 的 APK 实测为准（§7 未证实项）。

---

## 3. 施工步骤（照 WP-D 原文展开；每步给门）

### 步骤 0｜可行性探针（先做，半小时级；不绿则停）

在 `:core:database` androidTest 加一个一次性探针测试：

1. `BundledSQLiteDriver` 打开临时库 → `SELECT sqlite_version()` 断言 `3.50.1`；
2. `CREATE VIRTUAL TABLE probe USING fts5(node_id UNINDEXED, subject UNINDEXED, features, tokenize='unicode61')` → 插 3 篇 → `MATCH` + `ORDER BY bm25(probe) ASC` 返回预期序列（含 `bm25()` 为负分的方向断言）；
3. `EXPLAIN QUERY PLAN` 断言出现 `VIRTUAL TABLE INDEX`（走倒排，不是全扫）；
4. 既有 FTS4 路径不回归：`library_search_fts` 建表 + `MATCH` + `snippet()` 在 bundled 驱动下同结果。

门：四条全绿才进入步骤 1；任一红 → 走 §4 的"FTS5 独立库"备选（或停）。

### 步骤 1｜切 `BundledSQLiteDriver`

1. `gradle/libs.versions.toml`：加 `androidx-sqlite-bundled = { module = "androidx.sqlite:sqlite-bundled", version.ref = "sqlite" }`（与现有 `sqlite-framework` 同一 `sqlite = "2.7.0"` ref；Gradle 会解析到 `sqlite-bundled-android` 变体）；`core/database/build.gradle.kts` 加 `implementation(...)`。
2. `StudyDatabase.kt:353` 与 `:362` 的 `.setDriver(AndroidSQLiteDriver())` → `BundledSQLiteDriver()`；`PerformanceGateTest.kt:38` 的裸 builder 显式设同一驱动。
3. **影响面逐项处置**（第 12.1 条）：
   - 全库 SQL 引擎从设备系统 SQLite 换成 3.50.1 → 计划断言逐条核对（§4 风险 1）；
   - `RoomBackupSupportStore.kt:41-45` 的 `SELECT sqlite_version()` + `isAtLeast(3,27,0)` 分支：3.50.1 必走 `VACUUM INTO` 成功路径 → 备份/恢复仪表化套件必须复跑（含失败回退路径的既有测试）；
   - `SchemaDumpInstrumentedTest` 的 schema 产物若变化，必须能解释（驱动不产生 schema 变化，变了就是有别的改动混进来了）；
   - 迁移矩阵全量复跑（`FullMigrationMatrixInstrumentedTest`）。
4. 体积记录：4 ABI `.so` 解包 ≈4.8 MiB、按 ABI 拆分后 ≈1.2 MiB/臂、APK 打包后增量（`app/build/outputs/apk/**` 前后差）。

门：`:core:database` 全量 androidTest + 上述四项；档 1 全量。

### 步骤 2｜建 FTS5 虚表（索引域必须与判读数同域）

- DDL（与 Stage-1 实验台逐字一致，`core/data/src/test/kotlin/.../Stage1LexicalLab.kt:177-179`）：

```sql
CREATE VIRTUAL TABLE knowledge_search_fts USING fts5(
    node_id UNINDEXED, subject UNINDEXED, status UNINDEXED, features,
    tokenize='unicode61')
```

- **索引域 = 全量节点 3,970 篇（3,572 原子 + 398 topic）**，与臂 A 同域：FTS5 的 `N`/`avgdl`/`df` 是整表上的量（`build/stage1-experiments.md` §1 逐字），少收或多收文档都会让 0.6556 不可比。状态/科/章的过滤写在 WHERE（UNINDEXED 列）或 JOIN 上，**不进索引域**。
- 建立位置：按仓库既有先例用**迁移里的裸 SQL**（`LibrarySearchFtsMigration.kt:49` 的 `library_search_fts` = FTS4 同款做法）+ `STUDY_DATABASE_VERSION` 51→52；Room3 `@Fts5` 实体作为可选替代（需先做编译探针，§7）。
- 行的内容来源：**从 `knowledge_search_feature` 行按节点聚合**（`GROUP BY knowledge_node_id` 拼空格串），不重新调 `fromNode`——两处同源，避免二次抽取分歧；**拼接顺序不影响 bm25**（tf 二值、`dl` = 唯一特征数；Stage-1 §2 已用"唯一特征反推文档长度，抽 12 篇不一致 0"把 `D=特征数` 钉死）。
- 写入时机：与特征行同一个 `withWriteTransaction`（`RoomKnowledgeBaseStore.kt:155` 的补缺分支、`:164` 的换血分支）。

门：FTS 行数 = 3,970/科合计；逐节点特征串与 `knowledge_search_feature` 聚合逐字一致（测试断言）。

### 步骤 3｜`INDEX_VERSION` 递增与锚点重建

1. `KnowledgeSearchFeatureExtractor.INDEX_VERSION`（`KnowledgeSearchFeatureExtractor.kt:28`，现 = 1）→ 2。
2. 锚点机制自动换血（`RoomKnowledgeBaseStore.kt:152`：`readVersion(subject) != INDEX_VERSION` ⇒ 整科删特征行 → 重建 → 推进锚点；两个方向都触发，含回滚方向）。**新增机制要消灭的具体失败**：锚点只覆盖 `knowledge_search_feature`，**不覆盖新表** —— 不在换血分支里同时 `DELETE FROM knowledge_search_fts WHERE subject = ?`，旧 FTS 行就会与新特征行错位（检索出已删/已换规则的节点）。
3. 崩安全沿用现纪律：**锚点最后写**（`RoomKnowledgeBaseStore.kt:171`），锚点当前 ⟺ 上次重建完整跑完；崩在中途则锚点没推进、下次收敛。
4. 档 2 测试扩项：`core/database/src/androidTest/.../KnowledgeSearchIndexStateMigrationInstrumentedTest.kt:28`（现覆盖 v50→51 + 锚点换血）扩到 INDEX_VERSION=2 方向：锚点=1/当前=2 ⇒ 特征行与 FTS 行**都**重建、锚点推进到 2、第二次召回不再重建。

门：迁移/重建仪表化绿；换血后金标召回与"从零建索引"同数（同域性验证）。

### 步骤 4｜召回查询切换（含决定点 D1，需用户裁定）

- 查询 SQL（与实验台同形，`Stage1LexicalLab.kt:226-236`）：

```sql
SELECT node.*
FROM knowledge_search_fts f
JOIN knowledge_node node ON node.knowledge_node_id = f.node_id
WHERE f MATCH ?
  AND node.subject = ?
  AND node.verification_status IN ('CURATED', 'SOURCE_GROUNDED', 'USER_CONFIRMED')
  AND node.status != 'RETIRED'
ORDER BY bm25(f) ASC, node.knowledge_node_id ASC
LIMIT ?
```

  三条约定（都来自实验台，不许临场改）：FTS5 的 `MATCH` 与 `bm25()` 以**表名**解析（用别名会报 `no such column`，`Stage1LexicalLab.kt:226-227`）；`MATCH` 表达式 = `fromQuestion` 特征 **OR** 连接（`matchExpression`，不另写一份）；次级键 `node_id ASC` 保证同分候选顺序确定、可复算。
- `searchFeatures.isEmpty()` 的回退、`limit ∈ 1..512` 的约束不动（`RoomKnowledgeBaseStore.kt:120-122`）。
- **决定点 D1（返回形态；需用户一句话）**：
  - **(a) 保留父节点前置**：`parents + matched` 原样（`RoomKnowledgeBaseStore.kt:127-133`），KNOWLEDGE_READ 取前 5 → 预期 ≈ **0.5333**（Stage-1 扫描口径，非判读数）；消费者语义零变化，**但改进很可能被口径吃掉**。
  - **(b) 匹配优先**：FTS5 路由把 matched 放在返回序前部（父 topic 后置或按需注入）→ 预期 ≈ **0.6556**（臂 A 判读口径）；代价是 KNOWLEDGE_READ / MASTERY_READ 聚焦解析的既有语义（父 topic 占席）被改，需要逐一确认消费者（`RoomTutorToolRunner`、`RoomTutorKnowledgeContextLoader`、`RoomMistakeOrganizationRepository`）。
  - **纪律**：无论选哪个，都必须以**真 SQL 实测**为准（两种形状都测一遍再定），不许拿 `scan-A-parentprefix` 当结论；也不许为了数好看而把两者混着比。

门：真 SQL 金标数（判读目标）+ 逐题 MISS 集合与 JVM 镜像对称差 = 0。

### 步骤 5｜JVM 镜像与真 SQL 零漂移

- 现 `BRouteMirrorIndex`（`RetrievalBenchmark.kt:396`）是**纯 Kotlin 的 v1 二值 TF 镜像**，镜像不了 FTS5 的打分：本轮**新增 FTS 镜像**（sqlite-jdbc + 复用 `Stage1LexicalLab.Fts5Index` 与同一 DDL/MATCH/SQL 文本 + 同一 pack 语料），保留 `BRouteMirrorIndex` 作历史基线（它的数继续记，但不再是判分目标）。
- **零漂移红线**：主集/MRR/逐章 10 值**逐项相同**（历史锚点：47/90=47/90、31/90=31/90、49/90=49/90，漂移 0；`docs/kb-vector-topic-decision.md` §5 的 >5pp 判缺陷规则一字不改）。
- MISS 清单：两侧格式与口径一致（`RetrievalBenchmark.kt:236` 的 `MISS_PROBE_LIMIT=256`），**集合对称差 = 0**；名次只在本侧窗口内解释（父节点前置块的序不同可差 1~3 位，`GoldenRetrievalInstrumentedTest.kt:55-58`）。

门：档 1 的 `GoldenRetrievalJvmTest` + 档 2 的金标仪表化，两侧逐项对照表落盘。

### 步骤 6｜回归地板抬高（旧值记账）

- 现状：`RECALL_MAIN_FLOOR = 0.54` / `MRR_MAIN_FLOOR = 0.15`（`core/data/src/androidTest/.../GoldenRetrievalInstrumentedTest.kt:323-324`，注释已声明"基线提升后同步抬高并记录旧值（当前记录：旧值 0.54 / 0.15）"）。
- 施工后：以**真 SQL 实测新基线**向下取整到 0.01 写死新地板，并把旧值写进注释（延续既有记账方式）。例（示意，不是承诺）：新基线 0.6556 → 地板 0.64；MRR 0.5569 → 0.54。
- **反向纪律**：若 D1 选 (a) 而实测 ≈0.53，**地板不得抬高**（抬了就是假门，把"比基线更差"伪装成通过）。
- 地板是"不得更差"的下界，**不是质量目标**；与预注册判据（0.90/0.80、0.75/0.60）互不影响（同注释段已声明）。

### 步骤 7｜p95 复测 + DB 增量测量

- p95：沿用 `GoldenRetrievalInstrumentedTest` 的 150ms×CI 门（`:336`）；档 2 复跑 **≥3 次**（设备有 ~7.5–25.6s 单样本停顿族，p50 恒 18–20ms，见 `docs/kb-vector-topic-decision.md` §3.2 波动注记），记录 p50/p95/max/overBudgetSamples 与每次的有效性。
- DB 增量三条数（不编造，实测后落 `build/`）：
  1. `knowledge_search_fts` 家族占用：`SELECT SUM(pgsize) FROM dbstat WHERE name LIKE 'knowledge_search_fts%'`（无 dbstat 时退 `PRAGMA page_count`×`PRAGMA page_size` 前后差）；
  2. FTS 行的 token 规模：v1 特征行 **583,271**（`build/stage1-metrics-A.txt` 头 `featureRows=583271 maxFeaturesPerNode=192`）为量级输入；
  3. 驱动 `.so` 体积（步骤 1 的第 4 条）。
- 重建成本归位：整科换血是**一次性**成本（首装/规则变更时），与金标测试的"预热轮吸收"同口径（`GoldenRetrievalInstrumentedTest.kt:79-88`）。

---

## 4. 风险与回退

### 4.1 风险 1：planner/引擎版本差异导致查询计划断言漂移

- 现有硬断言：`KnowledgeContextRetrievalInstrumentedTest.kt:121-134`（"知识检索必须用索引"、"关系查询必须用索引"、"知识检索**不得** SCAN feature 表"）+ `PerformanceGateTest.explainQueryPlanNoFullTableScan`（`:156`）。
- **逐条核对原则（写死）**：换引擎后每条计划断言**逐条**对照新旧 `EXPLAIN QUERY PLAN` 输出——**等价或更优才改**（索引路径不降级、扫描面不扩大、排序方式不变差）；**不许放宽语义**（把"不得全表扫"改成"允许扫"、把 `USING INDEX` 去掉只为变绿，都属违规）；核对表逐条落盘（旧计划 / 新计划 / 判定 / 依据）。
- 对不了、或必须放宽才能过 → **停下回报**，不许"先改绿再说"。

### 4.2 风险 2：驱动切换的冲击面（最大风险）

- 一次切换动的是**全库 SQL 的引擎**（不只 FTS 路）：迁移矩阵、备份 `VACUUM INTO` 分支、schema dump、图书馆 FTS4 检索、分页/排序语义都在面上。
- 处置：档 2 全量复跑是门；任何 unexplained 的红都当红（先回退、后归因）。

### 4.3 风险 3：FTS5 表体积与重建写入量

- 583k 行级特征聚合、每次换血整科重建；体积与写放大先测（步骤 7），再决定是否需要分批（**不预先加机制**：没有实测红之前不加分批/增量同步）。

### 4.4 备选路径（驱动切换不可行时）

- **FTS5 独立库**（WP-D 原文的备选）：单独一个 SQLite 库承载 `knowledge_search_fts`，与主库分驱动并存；代价 = 多一个库 + 一套同步/一致性逻辑（锚点、事务边界、备份面都要各加一份）。
- 独立库方案**同样**要过 §3 步骤 4/5/6 的门（真 SQL 判分、零漂移、地板），且必须证明"主库事务与独立库的可见性"不会产生召回读旧行这类失败。

### 4.5 回退路径（一步可回，写死）

1. `INDEX_VERSION` 2 → 1（`KnowledgeSearchFeatureExtractor.kt:28`）；
2. 驱动回退：两处 `.setDriver(AndroidSQLiteDriver())`（`StudyDatabase.kt:353/362`）+ 去掉依赖；
3. 锚点自动换血（不等即重建，`RoomKnowledgeBaseStore.kt:152`）⇒ 生产回到 **v1 裸 B5 零回归形状**（19 例 19/19、金标 0.5444、p95 68–143ms 族）。
4. FTS5 表留在库里不参与读路径 = 无害死表；要清则另起一次迁移 drop（不着急做，避免为"看起来干净"加风险）。
- 回退纪律：**先回退、后归因**；红着状态不许继续堆改动。

---

## 5. 验收清单

### 档 1（每次改动都要绿的本地门；口径 = 既有每 WP 边界集合：Kotlin 全模块单测 + Python 套件 + kb checks，见 `docs/kb-architecture-refactor-2026-09-21-report.md:5,13`）

```bash
./gradlew.bat :core:data:testDebugUnitTest --tests "*Stage1LexicalLabTest*" --rerun    # A≡A' 仍容差 0（口径没漂）
./gradlew.bat :core:data:testDebugUnitTest --tests "*GoldenRetrievalJvmTest*" --rerun  # JVM 镜像数 + 产物契约
./gradlew.bat :core:database:testDebugUnitTest                                         # 模块单测（含 KnowledgeSearchFeatureExtractorTest 的截断钉）
# 全模块 Kotlin 单测（15 个测试任务）+ Python 套件 + kb checks：沿用每 WP 边界档 1 的既有任务集合
```

### 档 2（设备）六项

| # | 项 | 命令/入口 | 期望 |
|---|---|---|---|
| 1 | 金标真 SQL（判读数 + 地板 + p95） | `:core:data:connectedDebugAndroidTest` → `GoldenRetrievalInstrumentedTest` | 新基线数（按 D1 选形） + 地板绿 + p95 ≤150ms×CI |
| 2 | 19 例回归 + 守门 + 同形 p95 | `KnowledgeContextRetrievalInstrumentedTest#bundledSubjectsRecallExpectedKnowledgeWithoutCrossSubjectCandidates` | 19/19、cross-subject=0 |
| 3 | 锚点/索引重建 | `:core:database:connectedDebugAndroidTest` → `KnowledgeSearchIndexStateMigrationInstrumentedTest` | 换血方向含 FTS 行重建、锚点推进、二次不重建 |
| 4 | 全迁移矩阵 | `FullMigrationMatrixInstrumentedTest#everyExportedSchemaVersionMigratesToCurrentWithoutDestructiveFallback` | 绿（bundled 驱动下重跑） |
| 5 | 性能门 + 计划断言 + schema | `PerformanceGateTest`（含 `explainQueryPlanNoFullTableScan`）、`SchemaDumpInstrumentedTest` | 绿；计划核对表逐条落盘 |
| 6 | 装包/冷启/R4a 三数复核（WP-E 原文）+ DB 增量三数 | 档 2 复跑 + §步骤 7 的三条测量 | 不回归（与切换前对照） |

---

## 6. 需用户一句话裁定的项

1. **D1｜返回形态**（步骤 4）：保留 `parents + matched`（预期 ≈0.5333、消费者零变化）还是改成匹配优先（预期 ≈0.6556、消费者语义要复核）？
2. **施工令**：本方案是否开工（本文件不施工）。
3. （可选）若 bundled 驱动 + FTS5 下 p95 在设备上压不住 150ms×CI，**是否允许重定标预算**——对齐决策文档 §3.1 记录的 p95 门争议（该处只记事实、未定处置）。

---

## 7. 未证实 / 待核实清单（单列，不许当事实用）

| # | 项 | 现状与核实方式 |
|---|---|---|
| 1 | FTS5 / `bm25` 在 **bundled 驱动运行时可注册** | 本会话只证到**制品字符串**（§2.1 的复核命令）；运行时可注册性由**步骤 0 探针**证 |
| 2 | "arm64 `.so` 2.39MiB"（Stage-0 记录） | 与本会话实测（AAR 2.43 MiB / arm64 `.so` 1.22 MiB）**口径不同**；以步骤 1 的 APK 实测为准 |
| 3 | Room3 `@Fts5` 能否覆盖本用法（UNINDEXED 过滤列 + 自管内容同步） | 未验证；步骤 2 默认走裸 SQL 迁移（仓库既有先例 `LibrarySearchFtsMigration.kt:49`），`@Fts5` 属可选替代 |
| 4 | `scan-A-parentprefix = 0.5333` | **扫描诊断数（非判读数）**，只用于预测；D1 的结论必须来自真 SQL 实测 |
| 5 | INDEX_VERSION=2 下 `KnowledgeSearchIndexStateMigrationInstrumentedTest` 的扩项内容 | 本方案给出应断言的方向（步骤 3），具体断言待施工时写 |
| 6 | 新地板的具体值 | **不得预填**：以真 SQL 实测新基线向下取整写死（步骤 6） |
| 7 | 20k 合成点基线（审计 §9 未验证项 3 口径） | 驱动切换后需重测，历史数不直接可比 |
| 8 | 本会话**实测**（可复跑）：AAR 字节/四 ABI p_align/`3.50.1`/FTS5·FTS3·FTS4·bm25 字符串与编译开关串（§2.1 命令） | 已核实 |
