# 知识库深度研究（内部 + 外部）· 2026-09-15

> **后续文档（2026-09-15 同日产出）**：
> - `docs/kb-problem-register-2026-09-15.md` —— 本轮全部发现的逐条登记（69 条，问题 65 条）
> - `docs/research/coverage-baseline-design-2026-09-15.md` —— 第 5 条"全覆盖高考考点"口径的研究结论
> - 用户已对第 6 节的五个分叉逐条裁定；决定记录见问题登记册第 0 节。
>
> **性质**：只读研究，未改动任何生产代码。
> **证据口径**：本文所有"实测"数字均由本轮亲自运行命令或解析源文件得到；外部结论逐条标注证据等级。
> **可重跑命令**见文末附录 A。

---

## 0. 一句话结论

知识库的**工程管道是完整的、且质量高于同类开源项目**（严格的 JSON 契约、逐字节指纹、幂等安装、导入门禁、生产同构的仪器化测试）；**内容本身尚未完成**，且缺的不是"数量"而是"内容与绑定"：2573 个原子知识点中 **38.8% 没有任何教学材料**，同时 **892 条材料因没有绑定而在导入时被静默丢弃**，另有 **96 个材料被丢弃后仍留在仓库里**。外部对标显示这一节点量级与粒度是健康的，真正的问题与外部已知的失败模式（"空壳节点 + 未验证绑定"）完全一致。

---

## 1. 软件整体定位（研究基线）

**产品**：Android 端本地优先的"智能错题本"。一张错题照片 → 本地资产管道 → 模型提议分类与知识点绑定 → 学生确认入库 → 讲解 / 复习 / 排程 / 导出全程可迁移。

**规模**（实测）：17 个 Gradle 模块 · Room schema v44 · CI 最近一次全绿为 `4c76125`（2026-09-12）。

**与知识库相关的主链路**：

```
随包 JSON 内容包（16MB，打进 APK 作 Java 资源）
  → BundledKnowledgeBaseInstaller（应用启动时安装，幂等，失败关闭）
  → Room：knowledge_node / knowledge_source / knowledge_node_source_binding
          / knowledge_node_relation / knowledge_teaching_material(+绑定) / knowledge_search_feature
  → 检索：字符 n-gram 召回 → 本地精排 → 最多 64 个节点进整理提示词
  → 消费：错题→知识点绑定 / 掌握度 / 复习排程 / 讲题参考材料
```

---

## 2. 内部研究：知识库的实际形态

### 2.1 资产与格式（实测）

10 个 JSON，共 15,388 KiB，全部 tracked 且随 APK 发布（无 assets、无 LFS、无运行时下载）：

| 文件 | 字节 | 作用 |
|---|---|---|
| `moe-2025-four-subjects-v1.json` | 2,941,479 | 四科知识树（schema 2） |
| `moe-2025-teaching-support-v2-01…05.json` | 各 ~2.4–2.6 MB | 教学材料侧车，各 2048 条上限 |
| `moe-2025-teaching-support-v2-06.json` | 148,044 | 尾批 116 条 |
| `moe-2020-foundation-v1.json` / `-teaching-support-v1` | 11,751 / 3,466 | 2020 历史样例（9 科 19 点） |
| `source-register-2025-v1.json` | 50,819 | 来源登记，**Kotlin 从不读取**（只被 PowerShell 审计读） |

**契约强度（本仓的强项）**：`ReviewedKnowledgePackJsonCodec` 用 `requireOnlyKeys` 做精确键白名单——多一个键、少一个键都直接解码失败；材料侧车恰好 13 键；`contentFingerprint` 在导入时**重算比对**；来源 `sourceFingerprint` 只做格式校验不重算；无包级哈希清单。

### 2.2 内容现状（本轮实测，最承重的一组数字）

```
knowledge_nodes = 3018（445 topic + 2573 atomic）
prerequisite 边   = 1489
教学材料          = 10356，绑定 9464
```

**真实覆盖状态**：

| 指标 | 值 |
|---|---|
| 有 ≥1 材料绑定的知识点 | **1575 / 2573 = 61.2%** |
| **零材料的知识点** | **998（38.8%）** |
| 无任何绑定的材料（**导入时被静默丢弃**） | **892** |
| 材料类型分布 | CONCEPT_EXPLANATION 6900 / METHOD_MODEL 3456 —— **仅此两种** |
| MISCONCEPTION_GUIDE / WORKED_EXAMPLE / DERIVATION / REPRESENTATION_GUIDE / COMPLETE_SOLUTION | **各 0 条** |

**分科零材料率**（说明这不是"平均分布的小缺口"）：

| 科 | 知识点 | 零材料 | 占比 |
|---|---|---|---|
| MATH | 406 | 80 | 19.7% |
| PHYSICS | 732 | 312 | 42.6% |
| CHEMISTRY | 1097 | 507 | 46.2% |
| BIOLOGY | 338 | 99 | 29.3% |

**每知识点材料数分布**：`0→998 / 1→344 / 2→245 / 3→185 / 4→152 / 5+→649`

> ⚠️ **这条分布是倒置的**。运行时 `TutorPlanInput.MAX_TEACHING_REFERENCES = 4`——**每个节点最多 4 条材料被选中，第 5 条起永远不可见**。也就是说 **649 个节点（25%）的**材料里有相当一部分**永远送不到模型**，而同时 998 个节点一条都没有。这是"内容生产没有对准消费约束"的实证。

### 2.3 内容质量门：当前是**失败**状态（本轮亲自复跑）

```
$ python tools/build-knowledge-pack.py --gate
GATE_EXIT=1        # 15 项指标中 13 项非 0
```

未通过的 13 项（括号为实测计数）：

| 指标 | 计数 | 含义 |
|---|---|---|
| 名称不是知识点名 | 584 | `配方法：主要用于二次函数…．` 整句话当名称 |
| 名称含 ★ 难度星号 | 96 | `病毒★★★☆☆` |
| 同名重复知识点 | 118 | 需合并的份数 |
| **无材料绑定的知识点** | **998** | 见 2.2 |
| **无任何绑定的教学材料** | **892** | 白写 |
| 幽灵别名（与绑定脱节） | 847 | |
| 未在权威表中声明的前置 | 1489 | 即**全部**前置边 |
| boundary 含第三方原文摘录（分发风险） | 968 | **合规问题** |
| boundary 只有定位串/占位 | 589 | `定位：数学必修第一册 第三章·函数的概念与性质。` |
| LaTeX 命令丢失反斜杠 | 194 | |
| 材料含控制字符 | 80 | |
| 定位串与章表不一致 | 2163 | 84% 的节点 |
| 跨章拆分缺节点级覆盖 | 6 | |

**"13892 条候选层"的记忆需要订正**：候选层（`knowledge-coverage-candidates-2025-v1.json`，1.1 MB）是**范围清单的候选**，不是入库内容。实际入库的是上面 2573 + 10356。

### 2.4 生产工具链：`tools/kb_build/`（本轮实测：**完全未跟踪**）

`git ls-files tools/kb_build` → **0**。46 个 Python 模块 + 16 张 CSV 权威表全部存在于磁盘、且**从未提交过**。`knowledge-production/wusan/` 同为 0 tracked。这是当前最大的**工程连续性风险**：整套内容生产线没有版本历史。

**设计意图是好的**（`__init__.py` 自述）：把"一次性产物"变成"权威表 + 生成器"，用 round-trip 证明保真。

```
$ python tools/build-knowledge-pack.py --verify-roundtrip
OK  × 7 个文件     EXIT=0      # 生成器可逐字节重放现行成品
```

`build.py` 干跑投影（不写盘）：知识点 2573 → **2168**（删 324、并 108）、topic 445 → 331、+27 新点、+134 新材料、708 条绑定改指、1571 条 boundary 剥离待重写、2141 条 boundary 待定稿。

**未完成的两张权威表**：`boundary_map.csv` 与 `prereq_map.csv` **不存在**——这就是"1489 条前置全部未声明"和"589 条 boundary 只有定位串"的根因：不是内容写错，是**定稿动作还没做**。

**五三（5·3）内容流水线现状**：

- `wusan_plan.csv` = 95 章（生物 26 / 化学 21 / 数学 18 / 物理 30），覆盖 2170 个节点，其中 1557 已有材料 → **这些章还缺 613 个节点的材料（28.2%）**；95 章里 **75 章标"部分无材料"**。
- 五三侧候选知识点 6455 条：**解析到既有节点 6538 行，未解析 6321 行**（约一半对不上——既可能是噪声，也可能是真缺的知识点，未逐一裁定）。
- 已产出新材料：`materials.jsonl` **134 条**（化学 128 / 物理 6），类型为 CONCEPT_EXPLANATION 103 / METHOD_MODEL 15 / MISCONCEPTION_GUIDE 16。
  → **16 条 MISCONCEPTION_GUIDE 是该管线唯一的"新增类型"成果**，正对 2.2 里"最该补的类型一条都没有"的缺口。

### 2.5 检索链路（实测代码 + 实测分数）

**两段式，全本地、确定性、无向量、无 BM25、无分词库**：

1. **特征**：`KnowledgeSearchFeatureExtractor` — 整词（2–48 字）+ 字符 2-gram + 3-gram；查询 128 个特征上限（跨分片轮询，长题面优先保末尾设问）。
2. **召回**：SQL `WHERE subject=? AND search_feature IN (…) GROUP BY node ORDER BY COUNT(DISTINCT feature) DESC` ——**等于"二值 TF、无 IDF、无长度归一化"的 BM25**。
3. **精排**：`KnowledgeContextRetriever.select` — 硬编码权重 `名称×8 + 别名×7 + boundary×1 + 精确短语 80/70 + 父级×3`；只收 `CURATED`/`SOURCE_GROUNDED`，只排 ATOMIC。
4. **预算**：候选 ≤512 → 精排 ≤64 交给整理模型；讲题工具 `KNOWLEDGE_READ` 只给 5 个节点；教学参考 ≤4 条 / 20,000 字符（先到先得）。

**本轮我亲自跑了基准（`./gradlew :core:data:testDebugUnitTest --tests "*RetrievalBenchmark*"`，0 failures）**：

| 数据 | Recall@5 | Precision@5 | MRR | 跨科误召 |
|---|---|---|---|---|
| 打进包的现行包 | **0.95** (19/20) | 0.70 | 0.842 | 0 |
| `build/kb-staging` 暂存包 | 0.95 | 0.72 | 0.879 | 0 |
| **逐章覆盖题面（仅暂存包）** | **0.733** (11/15) | 0.253 | 0.70 | 0 |

> 三条要读准：① 主基准只有 **20 条手写题面**，且 `expected` 用"名称子串"近似——它是**冒烟级**证据，不是统计结论。② 新增的 15 条逐章题面 Recall 只有 0.733，而它**刻意不参与阈值断言**（作者在注释里写明"口径保持不变，要不要并进门槛是将来的单独决定"）——这才是"材料—别名—召回"这条链的真实水位。③ 我复跑得到 `Precision@5` 非 1.0，说明 2026-09-14 那次"把恒等于 1.0 的假指标改成真占比"的修改已生效。

**一个重要的门禁事实**：`core/domain/KnowledgeRetrievalBenchmark.kt` 的 `KnowledgeRetriever` 接口**在生产中零实现零调用**（死代码）；`tools/retrieval_quality_gate.py` 的 Python 门是**脚手架**——金标集只有 5 条、节点 id 是编造的（`math:monotonicity`）、**没有任何生产者输出它要的结果文件**、CI 也不调用它。**真实生效的门是 Kotlin 的 `FourSubjectRetrievalBenchmarkTest` 与仪器化 `KnowledgeContextRetrievalInstrumentedTest`（九科 19 例 + 查询计划断言 + p95 延迟）。**

### 2.6 运行时消费：知识库到学生的**六处断链**

这是我认为比"内容没写完"更值得注意的一组发现——**管道通了，但知识库在多个入口根本没被调用**：

1. **大厅自由提问不检索知识库**：`TutorLobbyInput` 只声明 `NOTEBOOK_READ`，提示词规则允许模型凭自身知识作答。
2. **拍照→讲题链路注入零知识**：`ConfirmedTutorSession.toTutorQuestionContext()` 硬编码 `relatedKnowledgeNodeIds = emptySet()`、`reviewedTeachingReferences = emptyList()`。只有**从错题本打开**的讲题才加载绑定与材料。
3. **`KNOWLEDGE_READ` 名不符实**：工具描述是"读取这道题相关知识点**讲解材料**"，实现只返回 `displayName` + 80 字 boundary。**没有任何代码路径让模型通过工具拿到教学材料。**
4. **`MASTERY_UPDATE` 需要的知识点 id 从不披露给模型**：提示词要求模型填 `terms:["<知识点id>"]`，但证据 JSON 只有 label、教学参考不含 id、`KNOWLEDGE_READ` 输出也不含 id——锚定检查（要求 id 真实存在且同科）随后必然拒绝。**聊天路径的掌握度写入在无注入 id 的前提下不可达。**
5. **学员端没有"知识库"这个对象**：没有全树浏览、没有知识库搜索、没有章节树。学生只在 ① 错题上的"知识点："标签 ② 掌握度列表（且**只列有掌握记录的节点**）③ 由材料生成的复习测验题 ④ 模型答案里见到知识库。
6. **覆盖自报是个死胡同**：`StudyExperienceSnapshot.knowledgeCoverage` 被构造、被映射、**没有任何 UI 或 feature 读它**；没有任何功能按覆盖度放行。

另有两处状态不一致（已核实）：

- 召回 SQL 收 `USER_CONFIRMED`，但 `KnowledgeContextRetriever.select` 只留 `CURATED`/`SOURCE_GROUNDED` → **用户自建知识点永远进不了整理提示词**，而写入侧注释明说它们"必须能被后续复用"。
- `pseudo:<SUBJECT>` 伪知识点与真绑定并存时的遮蔽问题（审计 §5.3.6），已在 `audit/kernel-readiness` 分支修复但**未进 main**。

### 2.7 来源与合规门：一条内部规则与产品范围的错位

`docs/knowledge-source-governance.md` 与五条 PowerShell 审计脚本定义了九科完整发布门（`-RequireFullCoverage`）。但它要求 **九个学科**（含语英政史地），而项目已定范围为**四科**（数理化生）——**当前结构的发布门在这条线上永远不可能通过**（`audit-knowledge-source-register.ps1` 硬编码九科 `requiredSubjects`）。这是设计遗留，不是内容缺陷。

同时，`knowledge-production` 那个 **Kotlin 模块（`:knowledge-production`）是死代码**：settings.gradle.kts 里 include 了，但**没有任何模块依赖它**，且它自带一套与 Python 管线无关的 `SeniorHighMathContent` / `SeniorHighPhysicsContent` 硬编码模板。它是历史形态，不是现行生产线。

---

## 3. 外部研究（四条线，均标证据等级）

### 3.1 同类开源知识图谱：**本项目的节点粒度与量级是健康的**

| 项目 | 规模（已核验） | 粒度 | 先修验证 | 证据等级 |
|---|---|---|---|---|
| **haolpku/K12-KGraph**（368★，最近 2026-08，人教版 K1–K12） | 10,685 节点 / 23,278 边 = 48 册 + **6,579 concept** + 1,364 skill + 652 experiment | 7 类节点，Concept 带 definition/formula/aliases/examples | `prerequisites_for` 子图自动 DAG 校验 + **每条边带 evidence 回指教材原文** | 一手项目文档 |
| **withmarbleapp/os-taxonomy**（Marble，ODbL+CC BY-SA） | **1,590 micro-topic / 3,221 先修边 / 8 学科**（数学 503、科学 547） | "a single, teachable idea"，含 mastery evidence criteria 与 assessmentPrompt | **只有结构校验脚本，明确无人工评审** | 一手项目文档 |
| **haojing8312/cn-k12-math-knowledge-graph**（36★，2026-09） | 1,327 主题 / 1,204 先修边 | 主题级 | **唯一样本最完整的先修审核协议**：596 个待定主题 → 2 名独立 AI 审核 + 1 名仲裁 → 158 个获边、**438 个显式保留"未定"，拒绝写成"确认无前置"** | 一手项目文档 |
| **THU-KEG/EDUKG**（115★，2022 停更，无 License） | >2.52 亿实体 / 38.6 亿三元组 | 实体级（非教学知识点级） | — | 论文摘要 |
| **jiangnanboy/education_knowledge_graph_app**（148★，2022 停更） | 无规模数字 | 只有"子→父"关系 | 无 | **反例：README 明写知识点定义/计算方法"（暂无数据）"——与本项目"空壳节点"同一失败模式** |

**外部对照的结论**：
- **本项目 542–643 点/学科**，对照人教高中数学社区清单 459 项、Marble 数学 503 微点（跨多年级）、K12-KGraph 四科 12 年级 6,579 概念（≈137 概念/学科/年级）——**同数量级、略偏细，不属异常**。**缺陷不在数量，在内容与绑定。**
- "空壳节点"有现成可抄的 schema：K12-KGraph 的 Concept 字段 + **边级 evidence 回指教材**；Marble 的 `evidence` + `assessmentPrompt`。
- **开放图谱的常见质量水位就是"结构校验（无环、可追溯）"，不是内容校验**——本项目的 gate 已经是这个水位的上游。

### 3.2 先修关系如何验证：**本项目"宁缺勿错"的处置在外部有直接范本**

- 国际标准是知识空间理论（KST）：Doignon & Falmagne 1985；QUERY 算法 Koppen & Doignon 1990（DOI 10.1016/0022-2496(90)90035-8）。判据："若 A 是 B 的先修，则掌握 B 的学生必掌握 A"，可行知识状态对 surmise 关系下闭。〔Crossref 核验〕
- 统计验证法有名字：**Learning Factors Analysis**（Cen & Koedinger 2006，DOI 10.1007/11774303_17），用交叉验证比较候选 KC 拆分——这是将来有作答数据后的升级门。
- **实操范本是 cn-k12-math 那种"显式未定"**：不把"尚未验证"写成"确认无前置"。
- **本次未找到任何"先修边 precision ≥ X"的行业统一质量门槛**〔未找到〕——所以本项目的自查标准必须自定并公开，不能引用外部数字。

> **对本项目的直接含义**：当前"前置链被判为假链、已留空"的处置**方向正确**，且比多数开源项目更保守。缺的是**把"未定"变成一等状态**（显式 `undecided`）与**恢复路径**（谁、按什么证据把它升级为 active），而不是删除数据结构。

### 3.3 检索：**n-gram 方向正确；真正的问题是缺 IDF**

| 结论 | 依据 | 等级 |
|---|---|---|
| 字符 n-gram 检索是中文检索的合法一流方案 | Kwok 1997（SIGIR，bigram 精度与分词相当、召回高约 5%，DOI 10.1145/258525.258531）；Chen 1997 独立团队结论相同（DOI 10.1145/258525.258532）；Nie 2000 认为"词与 n-gram 可比、混合最好"（DOI 10.1145/355214.355235） | A |
| 生产实现的默认含 IDF，本项目的 `COUNT(DISTINCT feature)` 不含 | Lucene `BM25Similarity` k1=1.2/b=0.75 且公式含 IDF；FTS5 `bm25()` 同样硬编码 1.2/0.75 且含 IDF | B（官方文档原文） |
| 缺 IDF 是教科书级缺陷 | 《Introduction to Information Retrieval》§6.2："all terms are considered equally important… certain terms have little or no discriminating power" | A |
| 缺长度归一化造成**系统性长名偏置** | Robertson & Zaragoza 2009："b=0 will switch normalisation off"；本项目特征数随名称变长单调增，方向是**长名更占优**（推算量级：最短名 vs 最长名得分比可达 ~1.6×） | A 原理 + 推算 |
| 短文档 BM25 的实际最优参数偏离 1.2/0.75 | Anserini 在 MS MARCO passage 上的官方网格：Recall 最优 k1=0.82/b=0.68，MRR/MAP 最优 k1=0.60/b=0.62；方法学是"多次采样各自调参后取平均（即正则化）" | B（官方文档原文） |
| "选公式"远不如"把参数调对" | Trotman et al. 2014（DOI 10.1145/2682862.2682863） | A |
| **16MB 预算内** 无可用的中文语义模型 | bge-small-zh-v1.5 ONNX int8 已 **23.9MB**（且需外挂推理运行时）；MediaPipe 官方 USE 只有英文 6.12MB；Model2Vec potion-base-8M 是英文专用、multilingual-128M 为 512MB；SPLADE++ 为 CC-BY-NC-SA（非商用）且 438MB | A（元数据实测） |
| 域外语义模型常不如 BM25 稳健 | BEIR（Thakur et al.，arXiv:2104.08663） | A |
| 若要加语义融合，**凸组合优于 RRF** | Bruch et al. TOIS 2023（DOI 10.1145/3596512）："RRF to be sensitive to its parameters… convex combination outperforms RRF"；且 RRF 的 k 在 Elastic（默认 60）与 Qdrant（默认 2）相差 30 倍，无普适最优 | A + B |
| 小规模金标集的评测下限 | Voorhees & Buckley 2002：topic ≤25 时错误率超预期；Voorhees 2009："50 topics…statistically significant differences can be wrong"；Sakai 2015：不同度量需要不同样本量。**行业惯例 50 条** | A |

**对本项目检索的三条可执行建议**（按证据强度）：
1. 把 stage-1 的 `COUNT(DISTINCT feature)` 换成含 IDF 与长度归一化的评分（SQLite 内可用 df 计数做 IDF、`LENGTH(name)` 做 dl）。
2. k1/b 不要猜：以 k1∈[0.6,1.2]、b∈[0.4,0.8] 为网格，按 Recall@5 与 MRR 分别选参，多折采样取平均，在 ≥50 条留出集上报。
3. **16MB 内不引入语义模型**；语义应作为"扩预算后"的独立决策并以本地金标集验证边际收益。

### 3.4 合规：**本项目"禁把源文本放进提示词"的做法有多重依据支持**

| 论点 | 依据 | 等级 |
|---|---|---|
| 独立改写知识点不侵权（复现原表达才侵权） | 《著作权法》第 3 条（独创性）、第 10(14)、第 13 条；北京高院《侵害著作权案件审理指南》2.2 / 2.8 / 10.10（"判断实质性相似…不应从主题、创意、情感等思想层面进行比较"） | 官方 |
| **公式、通用数表、单纯事实不受保护** | 《著作权法》**第五条(三)**"历法、通用数表、通用表格和公式" | 官方 |
| 成套复制例题+解答越界 | 人教社 v 江苏人民出版社·南京书城（南京中院 (2013) 宁知民终字第 11 号）：**"再现《教科书》《教师用书》中的问题、练习、答案及…分析与说明等内容构成侵权"**；而"目录相同不侵权"、"重点字词不侵权" | 半官方（江苏高院发布） |
| **中国法上没有 TDM / 训练数据例外** | 《著作权法》第 24 条 13 项合理使用中无此项；最高法亓蕾文中把"输入端合理使用"明确自述为**立法论建议** | 官方 |
| 生成式 AI 数据须"合法来源"且"不得侵害知识产权" | 《生成式人工智能服务管理暂行办法》第 7 条(一)(二) | 官方 |
| **本项目政策未覆盖的一条新合规项** | 《人工智能生成合成内容标识办法》**2025-09-01 已施行**：AI 生成的文本需显式标识（起始/末尾/中间）+ 元数据隐式标识，且**用户服务协议须写明标识方法** | 官方 |
| 各来源授权实况 | 人教社条款 7.4(3)/7.5 明确禁止复制/衍生/商用；菁优网服务条款一/七.4(8) 明确禁止复制与抓取；**学科网与 smartedu.cn 条款页均取不到正文（403/JS 壳）→ 未验证**；**曲一线无任何公开条款页（3 轮检索未果）** | 混合 |
| 同业先例 | 猿辅导/小猿搜题因把教材制成电子数据供下载**判赔 567,218 元**（(2019)京0491民初34899号等）；学而思走"取得独家授权后反向维权" | 二手 |
| **没有"中文 + 开放许可 + 覆盖中国高中四科 + 可离线"的成套 OER** | OpenStax 全量 129 本实测：46 本 CC BY / 72 本 CC BY-NC-SA / **含中日韩字符的书 = 0 本、无中文译本**，且是大学层次；PhET/可汗/均一/维基的许可页均取不到或 404 | 实测 + 未验证 |

**结论**：`REVIEWED_SYNTHESIS_ONLY` + `DERIVED_CONTENT_ONLY` 是当前唯一可辩护的默认档，且**抬升档位的动力只能来自自建或采购授权**，不存在"换一个 CC 源就一劳永逸"的路径。

### 3.5 "全覆盖高考考点"：**外部不存在可核验的权威总数**

这是本轮最重要的一个"证伪"，直接影响既定的验收标准：

- 《国务院办公厅关于新时代推进普通高中育人方式改革的指导意见》（**国办发〔2019〕29 号**）原文：**"实施普通高中新课程的省份不再制定考试大纲。"**〔官方〕
- 教育部教育考试院高考栏目的"考试大纲"分类页，**最新条目为 2019-01-31**（2019 年总纲 + 11 科），其后无更新〔官方站点〕。
- 《中国高考评价体系》（2020）存在，但本次**未取得官方原文**〔未获取〕。
- 课标的"内容要求"条目与"高考考点数"**不同构**，且本次未取得课标原文统计。
- 学科网/菁优网/组卷网**均不可访问**（反爬），**未找到任何可核验的"高考数学 XXX 个考点"计数**〔未找到〕。

> **因此**：记忆里"联网核对高考考点全覆盖"这个验收标准，**在外部无法用"一个权威总数"来闭合**。可行的替代是**可复算的代理口径**：以人教版教材目录 + 课标"内容要求"条目建立逐条对照表，逐条标注"已覆盖/未覆盖"，并把对照表本身作为可复核产物公开——外部评论者能复核的是**这张表**，不是一个数字。

---

## 4. 交叉验证：内部问题 vs 外部标杆

| 本项目现状 | 外部对标 | 判定 |
|---|---|---|
| 2573 原子点 / 4 科（542–643/科） | 459（人教高中数学）/503（Marble 数学）/6,579（K12-KGraph 四科 12 年级） | **粒度健康**，不是问题 |
| 38.8% 节点零材料 | K12-KGraph 每 concept 带 definition/formula/aliases/examples；Marble 每 micro-topic 带 mastery evidence + assessmentPrompt | **这是真缺口**，且外部有现成 schema 可抄 |
| 绑定正确率抽样 42%（项目自测） | cn-k12-math：绑定/边带 stable ID + trace + source type + release hash；K12-KGraph：边级 evidence 回指教材原文 | **本项目缺"可审计绑定"** |
| 1489 条前置全部"未声明"、已留空 | cn-k12-math 把 438/596 显式保留"未定"并公开 trace | **处置方向一致**；缺 `undecided` 一等状态与升级路径 |
| 检索无 IDF、无长度归一 | Lucene / FTS5 默认含 IDF；Anserini 给出短文档网格 | **教科书级可改进项**，且改动局部（一个 SQL 排序 + 参数字典） |
| Python 检索门只有 5 条编造 id 的查询、无生产者 | TREC 惯例 ≥50 条；Anserini 的"多折取平均" | **当前门无证据力**；真实门是 Kotlin 侧 20 条手写基准（也偏小） |
| 材料类型只有 CONCEPT_EXPLANATION + METHOD_MODEL | Marble 有 assessmentPrompt；规范 §3.2 把 MISCONCEPTION_GUIDE 列为**优先级最高** | 新管线已产出 **16 条 MISCONCEPTION_GUIDE**，方向对 |
| 648 个节点有 5+ 材料，运行时限 4 条 | — | **生产没对准消费约束**：应改为"补齐零材料节点"而非"继续加厚已有节点" |

---

## 5. 知识库的真实状态定位

**一句话**：管道是甲级的，内容是半成品，消费端有六处没接线。

分三层看：

- **工程层（强）**：严格契约、幂等安装、逐字节指纹、导入门禁、生产同构的仪器化测试（九科 19 例 + 查询计划 + p95）。这一层高于绝大多数同类开源项目。
- **内容层（半成品，且质量门是红的）**：38.8% 节点零材料；892 条材料因未绑定被静默丢弃；材料类型缺 5 种；584 个名称不是知识点名；968 条 boundary 含第三方原文摘录（**合规风险**）；2163 个节点定位串与章表不一致。**两张关键权威表（`boundary_map.csv` / `prereq_map.csv`）根本不存在**——这解释了绝大多数指标：不是写错，是**定稿动作没做**。
- **消费层（有断链）**：大厅提问、拍照讲题两条主入口不读知识库；`KNOWLEDGE_READ` 名不符实；`MASTERY_UPDATE` 所需的 id 从不披露；学生端没有"知识库"这个对象；覆盖自报无人消费；用户自建节点进了召回但被精排拒绝。

**最大的工程风险不是技术，是 `tools/kb_build/`（46 个模块）与 `knowledge-production/wusan/` 全部未提交**——整套内容生产线没有版本历史，任何一次误操作都无法回溯。

---

## 6. 真分叉：需要产品或工程决策的点

以下是"我无法替你定、且不同选择会导致不同施工"的分叉，按影响排序：

1. **知识库要不要成为学生可见的对象？**
   - A. 保持现状——知识库只是内部注入材料，学生只看错题与掌握度。
   - B. 增加"知识地图"入口——但需要先决定 38.8% 空节点怎么展示（这是外部项目普遍踩的坑）。
   - 影响：B 会把 2.2 的内容缺口从"内部质量问题"变成"用户可见缺陷"，从而改变内容生产的优先级。

2. **材料生产的靶子选哪个？**
   - A. 继续按五三章节推（当前路线，95 章 × 2170 节点）。
   - B. 先补 998 个零材料节点（消费端最饿的那批）。
   - C. 先补 MISCONCEPTION_GUIDE（规范自评优先级最高、当前仅 16 条）。
   - 我的判断：**B 的边际收益最高且可量化**（Recall@5 与"有材料可讲"的比例直接挂钩），而 A 会让"649 个已超配节点"继续加厚。

3. **前置关系：留空、还是建 `undecided` 一等状态？**
   - 留空的代价是 1489 条边的信息永久丢失（它们来自真实教辅，只是未定稿）。
   - 建 `undecided` 的成本很小（一个枚举 + 一条门），且外部有直接范本（cn-k12-math）。

4. **是否现在修检索的 IDF/长度归一化？**
   - 证据强（A 级）、改动局部（一个 SQL 排序 + 参数字典）、但**当前 20 条基准太小，无法证明改动有效**。
   - 因此顺序应是：**先把基准扩到 ≥50 条并切分调参/报告集，再改评分**。否则会重复"在小集合上调参过拟合"这个已知陷阱。

5. **"全覆盖高考考点"的验收口径要改。** 外部没有权威总数（3.5）。要么改为"人教版目录 + 课标内容要求"的自建对照表，要么放弃这个措辞。

---

## 附录 A：可重跑命令

```bash
# 保真：生成器能否逐字节重放现行成品（本轮 EXIT=0，7/7 OK）
python tools/build-knowledge-pack.py --verify-roundtrip

# 内容质量门（本轮 EXIT=1，13/15 项非 0）
python tools/build-knowledge-pack.py --gate
python tools/build-knowledge-pack.py --gate --json

# 生成器干跑投影（不写盘）：2573 → 2168 点
PYTHONPATH=tools python tools/kb_build/build.py

# 检索基准（本轮 0 failures；Recall@5=0.95 / P=0.70 / MRR=0.842）
./gradlew :core:data:testDebugUnitTest --tests "*RetrievalBenchmark*"

# 工具链单元测试
PYTHONPATH=tools python -m unittest discover -s tools/tests -v

# 九科发布门（当前结构下必然失败：项目范围是四科）
powershell -File tools/audit-knowledge-packs.ps1 -RequireFullCoverage
```

## 附录 B：本文未验证 / 取不到

- 未运行仪器化测试（`KnowledgeContextRetrievalInstrumentedTest` 的九科 19 例与查询计划断言）——本文引用的 p95 与执行计划结论来自源码与既有记录，**本轮未在设备上复跑**。
- 外部研究受检索通道限制：Google / Wikipedia / DuckDuckGo / Bing 中文在本次环境不可达或降级；moe.gov.cn 重定向循环；人教社官网超时；smartedu.cn / 学科网 / 作业帮 / 洋葱 条款页为 403 或 JS 壳。**凡标"未获取"均不代表不存在。**
- 单来源未复核项：猿辅导判赔金额（凤凰网引天眼查）、学而思判赔（腾讯新闻）、K12-KGraph 论文状态（未评审）、Cormack 2009 的 RRF k=60（原文为扫描件）。
- `build/kb-staging` 的快照（2026-09-14 23:55）**无来源文件证明它由当前权威表生成**。
