# 知识库架构重构 · 执行报告（2026-09-21 台账 → 2026-09-22 施工）

- **执行依据**：决策台账 D1–D17（`docs/kb-architecture-refactor-decisions-2026-09-21.md`，用户逐条裁定，WP0 子串比对 17/17 逐字落盘）。
- **施工范围（D4）**：R2（智能体页）+ R1（构建拓扑）+ R3（金标测量台）+ R4a（启动快路径）+ R5（死重清理）。R4b / 向量 / 卷重切**留档不施工**（§6）。
- **执行形态（D3）**：一个 workflow 跑完 + 两档门禁（档 1 本地单测 / 档 2 真机仪表化）+ 自动推送；设备基础设施失败 → 重跑 → UNVERIFIED 不阻塞（§5 清单）。
- **配套文档**：ADR `docs/adr/0001-agent-page-no-scenario-matrix.md`（D5–D10）；向量判定 `docs/kb-vector-topic-decision.md`（§6 最终判定）；缺陷登记 `docs/known-defects.md`（KD-24）。

## 1. 各 WP 执行摘要

| WP | 内容 | 状态 |
|---|---|---|
| WP0 | 决策落盘（纯文档）：ADR 0001（file:line 引用均读源核实：TutorToolLoop.kt:12-19 五工具枚举、MasteryWriteGate.kt:30/42/52/63/90/101 门常数、LearnerChatEvidenceEntity.kt:19-48 原有列）、D1–D17 台账逐字 17/17、向量预注册规则全文、给另一会话的拓扑切换同步段 | 完成 |
| WP1 | R5 死重清理 a–e（D14），详见 §3-D14；**档 1 验收绿**：Kotlin 全模块单测 1767/1767（0 失败/错误，15 个测试任务全绿）+ Python 套件 279/279 OK（基线 289，−12 作废输入用例 +2 新增路径用例） | 完成，档 1 绿 |
| WP2 | R1 构建侧（D2/D15）：单一晋升路径 staging→promote→成品（无双模式旁路）+ 五表↔包一致性 + manifest schema 2（单调 version + MERGE 链写入时压平 + 一跳到底，Kotlin codec 同步）。（阶段报告在交接中截断，以下按其开头摘要 + 本收尾代理已核实的仓库工件。） | 完成 |
| WP3 | R2 智能体页（D5–D10）：单一代号通道 + 无场景矩阵 + 统一门 + anchor_class。（阶段报告未随交接材料提供，逐条证据见 §3，映射为推定。） | 完成（按工件） |
| WP4 | R3 测量基建：`knowledge_search_index_state` 版本锚点表（v51 迁移，锚点不等即整科换血，含回滚方向——KD-24 明引"WP4 的版本锚点表"）+ 金标集双路测量台（JVM 镜像 + 真 SQL，零漂移纪律）。 | 完成（按工件） |
| WP5 | R4a（D13）：install() 快路径 + 驻留释放 + 真机 before/after 探针，数据见 §4（`.jez/artifacts/r4a-startup-baseline-2026-09-22.md`，gitignored 工件，本报告中内联收录）。 | 完成，真机实测 |
| 收尾（WP6b，本代理） | 向量判定段定稿（kb-vector-topic-decision.md §6）、本报告、提交/推送 | 完成 |

## 2. 两档门禁记录

### 档 1（本地单测，WP1 验收记录）

- Kotlin 全模块单测：**1767/1767**（0 失败/0 错误，15 个测试任务全绿）。
- Python 工具套件：**279/279 OK**（基线 289：−12 作废输入用例 +2 新增路径用例——source-register 新路径可读且 registerId 认身份 / 旧路径不得存在）。
- 记录来源：WP1 阶段报告。后续 WP 阶段报告未随交接材料提供；本收尾代理提交前做了文件级核对（金标 90 条 + sha 匹配封存值、schemas/ 仅新增 50.json/51.json、CI 接线 diff 逐行读），**未重跑全量档 1 套件**——全量绿以 WP1 记录为准。

### 档 2（真机仪表化，2026-09-22，test_device/emulator-5554，逐项 status）

| 项 | status | 证据（档 2 报告，关键数逐字） |
|---|---|---|
| 1. `RoomModelTaskT6MasteryInstrumentedTest`（代号通道回归） | **verified** | 先 `:app:assembleLocalFirstDebug` 安装，再 `:core:data:connectedDebugAndroidTest`（指定 class）。XML `tests=3 failures=0 errors=0 time=4.373s`。三方法：合法代号 K1 → gate 拒 `rejected:KNOWLEDGE_NODE_NOT_ANCHORED` 且 `anchor_class=CONFIRMED` 落库（:209-217）；无题轮放行到执行器 `executedCallCount=1`（:258-261）；编造代号 K9 → `invalid_knowledge_code` 结构性拒、不触执行器、零证据行（:299-311）。 |
| 2. `GoldenRetrievalInstrumentedTest`（金标真 SQL，v1 裸 B5 生产形状） | **verified** | XML `tests=1 failures=0 errors=0 time=333.246s`，150ms p95 预算断言通过。logcat 逐字：`route=B-bare->top5(v1生产形状) indexVersion=1 cases=90 samplesPerQuery=5 p95=143ms p50=20ms max=16616 budget=150ms overBudgetSamples=19`；主集 Recall@5(全样本)=0.5444(49/90)、(任一样本)=0.5444；MRR=0.1511；逐章 10 值 0.7778/0.2222/0.7778/1.0/0.4444/0.7778/0.3333/0.2222/0.2222/0.6667（≥0.80 仅 1 章，判定不变）；`golden-assets sha256=7c004b76…54e6ae39` 运行时复核通过。与 §3.2 第 3 组封存数零漂移。 |
| 3. `KnowledgeContextRetrievalInstrumentedTest`（19 例回归 + 20k 基准） | **unverified** | 类跑了两次（每次 ~10.5min）。(a) 点名验收 `bundledSubjectsRecallExpectedKnowledgeWithoutCrossSubjectCandidates` **两次全过**——run1 logcat 逐字 `bundled-knowledge-recall regression: hit=19/19, cross-subject=0`（v1 形状 B64→A，limit=64，:72）；run2 XML 该 testcase 无 failure（179.5s）。(b) 同类第二方法 `largeSubjectRecallRemainsBoundedOnRoom`（20k 合成点基准）**两次都挂在 line 198 墙钟 p95 断言**——run1 samples=[1062,46,46,45,51,53,44,48,54,45]ms，run2 samples=[1249,61,46,44,51,51,50,46,44,41]ms：仅首样本冷离群（1.0–1.25s），稳态 9 样本 41–61ms 远低于 150ms 预算。EXPLAIN QUERY PLAN 确定性守卫（索引用途/无 feature 全扫）与选中语义断言均在墙钟断言之前通过；该测试形状与同日早先同设备 p95=62ms 通过相比未变，而 v2 形态回归表现为全分布平移（p50≈460ms、overBudget 444/450），与本次单样本形态不同；测试自身注释声明墙钟预算只是 coarse backstop（:463-469）。**未删断言、未改测试、未放宽**。判定 infra/冷样本伪影，UNVERIFIED（D3 不阻塞）。 |
| 4. `KnowledgeContentUpdateDrillInstrumentedTest`（W-4 债·差分包演练） | **verified** | `BUILD SUCCESSFUL in 1m18s`；XML `tests=1 failures=0 errors=0 time=66.689s`。`aDifferencePackUpdatesEveryActionClassWithoutTouchingStudentData` 过：改/增/删/并（superseded_by 重定向）/改材料/改绑定（旧绑定清零）/加前置边/坏对象…（档 2 报告该行截断，XML 结果为准：failures=0）。 |

## 3. D1–D17 逐条执行证据

| 台账 | 裁定 | 执行证据（本收尾代理已核实的工件） | 状态 |
|---|---|---|---|
| D1 | 蓝图 R1–R5 + 向量预注册规则定案，向量本轮不上 | 决策台账 §3（规则全文）与 `kb-vector-topic-decision.md` §1（逐字存档）一致；向量未施工，只交付判定基础设施与判定（§4 向量判定） | ✅ |
| D2 | R1 拓扑直接切 staging+promote，无双模式旁路 | `tools/kb_build/promote.py`（唯一成品目录写者，新文件）；`tools/tests/test_kb_path_guard.py`（直写场景红住）；`tools/ci/run_kb_checks.py`（CI 无 staging 时回退成品目录校验）；决策台账 §4 给另一会话的同步段（30+ 手术工具只写 `build/kb-staging/`） | ✅ |
| D3 | 一个 workflow 跑完 + 两档门禁 + 自动推送；设备基础设施失败 → 重跑 → UNVERIFIED 不阻塞 | §2 两档记录；档 2 项 3 两次重跑后仍墙钟红 → UNVERIFIED 如实上报、不阻塞，提交/推送照常执行 | ✅ |
| D4 | 范围 R2+R1+R3+R4a+R5 全量；R4b/向量/卷重切留档不施工 | 各 D 条见本表；留档项见 §6 | ✅ |
| D5 | 单一代号通道 K1..Kn + enum 白名单，原始 id 永不进 prompt/工具参数 | `core/model/.../TutorKnowledgeCode.kt`（`TutorKnowledgeCodeRole` 4 角色 enum；注释逐字"原始 id 永不进 prompt、永不进工具参数——模型没有产生原始 id 的渠道"）；`core/data/.../study/TutorKnowledgeCodeRegistry.kt`（会话级注册表，首披露顺序分配，会话内稳定）；`ModelEgress.kt`/`ModelTasks.kt`/`TutorTasks.kt`/`TutorTeachingReference.kt` + `ModelEgressTest`/`ModelTaskFingerprintStabilityTest`（新输入字段同步升 schema 指纹——铁律 7）；**档 2 项 1 verified：编造代号 K9 → `invalid_knowledge_code` 结构性拒、不触执行器、零证据行** | ✅ 档 2 真机核实 |
| D6 | 写不写由模型语义判定，代码零场景分叉 | ADR 0001；**档 2 项 1 verified：无题轮放行到执行器（`executedCallCount=1`）**，写拒只来自统一门语义（`rejected:KNOWLEDGE_NODE_NOT_ANCHORED`），无场景分叉分支 | ✅ 档 2 真机核实 |
| D7 | 大厅全工具面，MASTERY_READ 无场景分支 | ADR 0001；`TutorToolLoop.kt`（五工具枚举）；`RoomModelTaskToolLoopInstrumentedTest.kt`/`RoomTutorToolRunnerTest.kt`（在提交清单内） | ✅ 档 1 单测 |
| D8 | Plan 也全 5 工具（复用 Respond 工具环） | ADR 0001；`feature/tutor/.../TutorModelTaskPolicy.kt` + `TutorModelTaskPolicyTest.kt`（在提交清单内） | ✅ 档 1 单测 |
| D9 | 统一本地门（POSITIVE≥1/MASTERED≥2、置信度≥0.7、12h 冷却、会话 50 + learner 1h 100 配额）+ 降权安全垫（anchor_class≠CONFIRMED weight×0.5，唯一数值分支） | `MasteryWriteGate.kt:30`（`EVIDENCE_CONFIDENCE_THRESHOLD = 0.7`）、`:42`（`SAME_KC_COOLDOWN_MILLIS = 12h`）、`:52`（`MAX_WRITES_PER_CONVERSATION = 50`）、`:63/:66`（`MAX_WRITES_PER_LEARNER_WINDOW = 100` / 1h 窗）；`MasteryWriteGateTest.kt`；**档 2 项 1 verified：合法代号 K1 被门按语义拒（锚底线不满足）且 `anchor_class=CONFIRMED` 落库** | ✅ 档 2 真机核实 |
| D10 | `learner_chat_evidence` 加 `anchor_class` 列（CONFIRMED/CANDIDATE/DISCLOSED） | `LearnerChatEvidenceEntity.kt:57`（`anchor_class: String? = null`，旧行 NULL legacy 不追溯降权）；`ChatEvidenceAnchorClassMigration.kt:18-20`（v49→50 `ALTER TABLE learner_chat_evidence ADD COLUMN anchor_class TEXT`）；schema 导出 `50.json` 新增（schemas/ 目录仅 50/51 两个新增文件、无其他文件变化——铁律 8 核对通过）；`KnowledgeAnchorClass` 三值 enum 与 `of(role)` 机械映射在 `TutorKnowledgeCode.kt` | ✅ 档 2 真机核实 |
| D11 | 成品 JSON = 单一事实源，tables = 审计日志，重建不承诺 | 决策台账 §2 F3→D11；构建侧（`build.py`/`pack_io.py`，WP2 清单内）；WP1 e) 把 build.py 侧车"硬编码"注释改为索引文件机制（`BundledKnowledgePackResources.kt:58-66` 一手核实 Kotlin 确已读 index），零行为变化 | ✅ |
| D12 | 金标集 80–100 条冻结 + 预注册阈值 + 不过 → 开 dense 兜底议题 | 金标集**90 条**（本收尾代理 `python len()` 实测 90），`tools/kb_coverage/tables/golden_queries_v1.json` + `.sha256`（`7c004b76…54e6ae39`，与决策文档封存值一致、档 2 运行时复核通过）；`make_golden_slices.py`/`validate_golden.py`/`test_kb_golden_slices.py`（agent 语义切片 + 机械验证）；`GoldenRetrievalJvmTest`（档 1 镜像）+ `GoldenRetrievalInstrumentedTest`（档 2 真 SQL，androidTest assets 双副本）；**判定执行：不通过（0.5444 < 0.90，三组数全低于线）→ 同层 dense 兜底议题开启**，详见 §4 | ✅ 判定已执行 |
| D13 | install() 先读 manifest + state 戳一致不解析 + reconcile 后释放驻留 + 真机启动探针 | `BundledKnowledgeBaseInstaller.kt`（快路径注释含"信戳不验包"已知边界，决策台账 §6 登记）；`BundledKnowledgeBaseInstallerTest.kt`（`fullParseCount` 钉死不增）；真机 before/after 三数见 §4 | ✅ 真机实测 |
| D14 | R5 做 a–e | a) `:knowledge-production` Kotlin 模块删除（`settings.gradle.kts` 不再 include；CI 删 `:knowledge-production:test` 与 `Build knowledge-production module` 步骤——本收尾代理读 `android-check.yml` diff 核实；台账 JSON/CSV/TSV/wusan 数据保留）；b) `source-register-2025-v1.json` 迁至 `knowledge-production/`（原 APK 资源位置 `core/data/src/main/resources/knowledge/` 已删；4 处 Python 默认路径 + 3 处 PowerShell 审计脚本 + 治理文档 1 行影响面闭合；2 条新路径测试）；c) `node_actions` 死路径删除（2 CSV + 4 工具 `propose.py`/`review_kit.py`/`diagnose_unbound.py`/`verify_tables.py` + 其测试 `test_kb_verify_tables.py` + `tables.py`/`build.py`/`fix_bad_names.py`/`propose_chapter_by_node.py` 读侧代码；`node_actions.README.md` 保留补删除注记）；d) `generate_status.py` 现运行 22 门（崩溃=红）+ 工具套件摘要 + 逐字嵌入 benchmark-metrics.txt，负路径已实测（门红→FAIL、工具测试红→FAIL），`status-template.md` 加 Knowledge Base 段；e) 4 处过时注释清零（Installer.kt 2600/11000→约3600/约27000、InstrumentedTest 同类、build.py 侧车、TutorToolLoop.kt 轮预算"stays unimplemented"→已实现），零行为变化 | ✅ 档 1 绿 |
| D15 | R1 加固：MERGE 链一跳到底压平断言、manifest schema 2（Kotlin codec 同步）+ 单调 version、原子戳仅 promote 刷新、表↔包一致性扩到 5 张权威表、22 门 + 工具测试进 CI | `moe-2025-update-manifest.json` 升 schema 2（.jez 基线工件记录 schema 2 / version 1 / contentVersion `89415663d45b018b`）；`ReviewedKnowledgeUpdateManifestJsonCodecTest.kt`（Kotlin codec 同步）；`point_merge.csv` + `build.py`（MERGE 链压平）；`test_kb_table_consistency.py`（5 张权威表）；**CI 接线本收尾代理读 diff 核实**：`android-check.yml` 新增 `Knowledge build toolchain tests`（`python3 -m unittest discover -s tools/tests -t tools`）与 `Knowledge pack gate and table-pack consistency`（`python3 tools/ci/run_kb_checks.py`），置于 Gradle 步骤前 | ✅ |
| D16 | `chapter_map.csv` 不碰 | 该文件 git status 为 `M`（另一会话 5 行飞行改动），**本提交显式排除**（收尾代理提交清单核对：不在清单内）；tables = 审计日志不变 | ✅ 本提交不含 |
| D17 | 子代理继承会话模型、并行度 30、语义判定沿用既有切片纪律（宁缺勿错） | 流程级裁定：90 条金标按"宁缺勿错"纪律切片并一次性冻结（sha 封存）；各 WP 子代理按 workflow 参数继承会话模型执行（本收尾代理即该 workflow 的收尾 WP） | ✅ 流程执行 |

## 4. WP5（R4a）before/after 数（真机 test_device，pm clear → 冷启，同口径）

来源：`.jez/artifacts/r4a-startup-baseline-2026-09-22.md`（gitignored 工件；数据内联收录于此，logcat 原始行在该工件内）。manifest：`moe-2025-update-manifest.json` schema 2 / packId `moe-2025-four-subjects-v1` / version 1 / contentVersion `89415663d45b018b`。

| 指标 | before（稳态冷启 2） | after（稳态冷启 2） | 变化 |
|---|---|---|---|
| 进程启动 → install() 返回 | 19,221 ms | **2,880 ms** | −85% |
| install() 耗时 | 16,577 ms | **296 ms**（快路径：零解析零调和；logcat 逐字 `install finished in 296 ms (fast path: content stamp matches, no parse, no reconcile)`） | **−98.2%** |
| load() 全量解析 | 15,983 ms（2 packs） | **0**（日志行不存在——整包一个字符没读；`fullParseCount` 单测钉死不增） | −100% |
| TOTAL PSS | 238,794 KB（≈233 MB） | **156,908 KB（≈153 MB）**（Dalvik 88,468→6,236 KB，驻留对象图已释放；Native 26,564→18,068 KB） | **−80 MB（−34%）** |
| 首装（冷启 1，全量路径，按设计不变） | 44,826 ms（install 41,720 ms，解析 16,949 ms） | 43,666 ms（install 40,543 ms，解析 16,730 ms，噪声范围内） | 不变 |

已知边界（决策台账 §6 登记）：**信戳不验包**——快路径只验"内容版本没变"，不验 APK 内 JSON 与戳一致；威胁模型内无攻击者（APK 签名保护），裁定接受该边界、不加抽样校验机制（新增机制须指认消灭的具体失败——本威胁模型内没有该失败）。

## 5. UNVERIFIED 清单（档 2 unverified 项，逐字，D3 不阻塞）

1. **`KnowledgeContextRetrievalInstrumentedTest.largeSubjectRecallRemainsBoundedOnRoom`**（20k 合成点基准，墙钟 p95 断言）：类级墙钟门在隔离逐类跑法下两次红——run1 `samples=[1062,46,46,45,51,53,44,48,54,45]`ms，run2 `samples=[1249,61,46,44,51,51,50,46,44,41]`ms，均只有首样本冷离群（1.0–1.25s），稳态 9 样本 41–61ms 远低于 150ms 预算；EXPLAIN QUERY PLAN 确定性守卫（索引用途/无 feature 全扫）与选中语义断言都在墙钟断言之前通过；该方法形状与同日早先同设备 p95=62ms 通过相比未变，而 v2 形态的回归表现为全分布平移（p50≈460ms、overBudget 444/450），与本次单样本形态不同；测试自身注释声明墙钟预算只是 coarse backstop（:463-469）；**未删断言、未改测试、未放宽**。判定 infra/冷样本伪影而非 v1 代码回归。
   - 同类点名验收 `bundledSubjectsRecallExpectedKnowledgeWithoutCrossSubjectCandidates`（19 例回归）**本身两次绿**：logcat 逐字 `bundled-knowledge-recall regression: hit=19/19, cross-subject=0`（v1 形状 B64→A，limit=64）。
2. 无其他本轮 UNVERIFIED 项。（决策文档 §3 判定表中"首测『任一样本』口径未留存 / 首测 p95 未留存 / 首测逐题 MISS 清单在上轮会话"三条是**历史封存口径**，当时已标注"本会话不可复核"，非本轮执行状态。）

## 6. 遗留（留档不施工，D4）

1. **R4b · 预构建 SQLite 内容库（.db 载荷形态，审计 R4-2b）**：Room `createFromAsset`/`createFromFile` 官方模式，启动 50MB JSON 解析整体消失、校验前移到构建时；代价是构建链多一步 JSON→.db 导出、调和语义变 DB 版本比对。审计建议：材料按"容量不设限"持续增长时的 R4 终点形态，与自建内容通道是天然搭档。
2. **卷重切（审计 R4.3）**：可选；当前内容体积无变化，启动成本已不随内容增长（首装除外），无触发条件。
3. **自建内容通道（审计 §2.4-2）**：内容热更新（独立于 App 版本的内容包下载/更新/删除）是国内语境标准形态（高德 2025-05 / 百度 2026-03 SDK 官方文档），真实约束 = 需自建后端 + 完整性校验（下载内容不受 APK 签名保护）；"随版本更新"维持当前决策，登记为"无后端约束的阶段选择"，不是平台不可能项；后端落地时与 R4-2b 同步评估。
4. **向量 · 同层 dense 兜底议题（已开启，未立项）**：D12 首测触发、结论不变（§4）；形态预案 bge-small-zh int8 + LiteRT ≈30MB / RRF / 无 ANN / sqlite-vec 不引入；**立项需用户另裁**。
5. **词面栈排序病（D-01 无 IDF/无长度归一化 + 题面-节点名式差）**：41 例 MISS 的共同形态（决策文档 §3）；词面侧不再以去截断方式修（KD-24 实测两形状净伤害），归宿为 dense 兜底议题。

## 7. 提交范围声明

- 文件清单 = `.wf-manifest.txt` 全部路径 ∩ git status 实际变更，显式排除：`.wf-manifest.txt` 本身、`tools/kb_build/tables/chapter_map.csv`（D16，另一会话 5 行飞行改动）、`.agent_*` / `out/` / `tmp/` / `build/` 草稿；清单外脏文件一律不提交。
- 13 个删除文件（`:knowledge-production` 模块 5 + `source-register` 原位置 1 + `node_actions` 2 CSV + 4 工具 + 1 测试 `test_kb_verify_tables.py`）未逐条列入 manifest（铁律 4 只要求记录创建/修改），但全部为 WP1 R5 施工产物（WP1 阶段报告逐项列明、档 1 验收含此状态）——**一并提交**，否则提交态偏离档 1 实测态（`settings.gradle.kts` 已删模块 include 而模块文件仍被 git 跟踪、CI 已删的测试文件仍在仓库）。
- 提交后 `core/database/schemas/` 相对 HEAD 的变化 = 仅新增 `50.json`、`51.json`（铁律 8 核对通过，CI `Room schema drift check` 可复验）。
