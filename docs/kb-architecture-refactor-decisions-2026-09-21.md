# 知识库架构重构 · 决策台账（2026-09-21）

- **来源**：用户对知识库架构审计（`.jez/artifacts/research-brief-kb-architecture-audit.md`，R1–R5 蓝图 + §7 三个裁定分叉 F1–F3）的**逐条裁定**。
- **地位**：本次重构工作流的执行依据。后续任何会话需要裁定结果，读本文档，不读记忆、不读旧讨论。
- **配套 ADR**：智能体页工具面/写口裁定（台账 D5–D10）落盘为 `docs/adr/0001-agent-page-no-scenario-matrix.md`。

## 1. 决策台账 D1–D17（逐字）

- D1 蓝图 R1-R5+向量预注册规则定案，RAG 结构性不需要、向量本轮不上；
- D2 R1 拓扑直接切 staging+promote 无双模式旁路；
- D3 一个 workflow 跑完+两档门禁+自动推送，设备基础设施失败→重跑→UNVERIFIED 不阻塞；
- D4 范围 R2+R1+R3+R4a+R5 全量，R4b/向量/卷重切留档不施工；
- D5 单一代号通道 K1..Kn+enum 白名单，原始 id 永不进 prompt/工具参数；
- D6 写不写由模型语义判定（系统提示词教会），代码零场景分叉；
- D7 大厅全工具面，MASTERY_READ 无场景分支（输出形态/轮预算按旧裁定不变）；
- D8 Plan 也全 5 工具（复用 Respond 工具环）；
- D9 统一本地门（引文锚底线 POSITIVE≥1/MASTERED≥2、置信度≥0.7、冷却、配额）+ 降权安全垫：anchor_class≠CONFIRMED 时 weight 减半（唯一数值分支）；
- D10 learner_chat_evidence 加 anchor_class 列（CONFIRMED/CANDIDATE/DISCLOSED）；
- D11 成品 JSON=单一事实源，tables=审计日志，重建不承诺；
- D12 金标集 80-100 条（agent 语义切片+机械验证+一次性冻结），预注册阈值主集≥0.90 且逐章≥0.80 判去截断后生产同构 B 路，不过→开同层 dense 兜底议题（预案 bge-small-zh int8+LiteRT≈30MB/RRF/无 ANN，本轮不施工）；
- D13 install() 先读 manifest+state 戳一致不解析 + reconcile 后释放驻留 + 真机启动探针；
- D14 R5 做 a-e：删 :knowledge-production Kotlin 模块（保留 Python 在读台账 JSON）、source-register 迁出 APK、删 node_actions 死路径、status.md 由 CI 机器生成、过时注释清零；
- D15 R1 加固：MERGE 链一跳到底压平断言、manifest 升 schema 2（Kotlin codec 同步）+单调 version、原子戳仅 promote 刷新、表↔包一致性扩到 5 张权威表、22 门+289 工具测试进 CI；
- D16 chapter_map.csv 不碰；
- D17 子代理继承会话模型、并行度 30、语义判定沿用既有切片纪律（宁缺勿错）。

## 2. 已裁定分叉对照（审计 §7 → 台账）

| 审计分叉 | 内容 | 裁定 |
|---|---|---|
| F1 | 泛化掌握度的 id 披露口径（(a) 轮内代号 + enum 白名单 / (b) 原始长 id / (c) displayName 反查） | **D5**：选 (a)，单一代号通道 K1..Kn，原始 id 永不进 prompt/工具参数 |
| F2 | 无当前题时的 MASTERY_UPDATE（(a) 允许写入但降权 / (b) 一律拒写） | **D9/D10**：选 (a)，统一本地门 + 安全垫（anchor_class≠CONFIRMED weight 减半），anchor_class 三值落 learner_chat_evidence |
| F3 | 可复现性口径（(a) 成品 JSON=单一事实源 / (b) 保留块池重建后半段） | **D11**：选 (a)，成品 JSON=单一事实源，tables=审计日志，重建不承诺 |

## 3. 向量预注册规则（全文，依据 D1/D12）

**裁定状态**：RAG 结构性不需要、向量本轮不上（D1）。以下规则为**预注册**——阈值与判分对象现在就定死，R3 同构评测跑完后按本规则判读，不允许事后调阈值、事后换判分对象。

1. **金标集**：80–100 条，由 agent 语义切片 + 机械验证产生，**一次性冻结**。冻结后不增、不减、不改（"不许为评测集打补丁"的 R3 纪律；提升指标只能改检索器/索引/内容）。
2. **判分对象**：**去截断后生产同构 B 路**——生产 SQL 倒排召回路径（`knowledge_search_feature`）在取消别名索引有损截断（`MAX_SEARCH_FRAGMENTS=16` / `MAX_NODE_FEATURES=192`，审计 §1.4：62% 节点别名被截断）之后的同构评测（同一代码路径、Room in-memory），按 R3 同构评测的指标 B 路 Recall@5（审计 R3 验收中"从未存在过"的那个数）判分。
3. **预注册阈值**：**主集 ≥ 0.90 且逐章 ≥ 0.80**（两条同时满足）。
4. **通过**：词面路径（25k 别名 + 册·章·节门控）足以服务，向量议题**关闭**，不上。
5. **不通过**：开**同层 dense 兜底议题**（只开议题、不直接施工）。预注册的形态预案：
   - 模型：bge-small-zh int8 + LiteRT，总预算 ≈ 30MB（审计 §2.4-1 实测：int8 模型 23.9MB + 运行时 5.25MB；3,572 节点规模全扫 1.4–2.8ms）；
   - 定位：仅同层（册·章·节已把候选空间压小）dense 兜底同义表述——lexical 主、dense 补的混合形态；
   - 融合：RRF；
   - 无 ANN 索引（该规模全扫即可）；
   - sqlite-vec 维持不引入（审计"明确不做"：pre-v1 + Android 16KB 页 issue 未修，2027-02 起影响 API 35+ 发布；此规模自建 cosine 零依赖）。
6. **本轮不施工**：向量只是预注册的决策门，不在 D4 的施工范围（R2+R1+R3+R4a+R5）内；议题开启后的立项需另一次用户裁定。

## 4. 给另一会话的拓扑切换同步段（依据 D2/D16）

> 另一会话正在对 `tools/kb_build/tables/chapter_map.csv` 做 5 行飞行改动。本工作流不碰该文件（D16），但写拓扑正在按 D2 切换，影响你如何使用工具，同步如下。

裁定：R1 拓扑**直接切 staging+promote，无双模式旁路**（D2）。具体：

- **30+ 手术工具全部改 staging 输出**：一律只写 `build/kb-staging/`，不写成品目录（`core/data/...` 的 taxonomy 包与材料卷）。
- **仅 `kb_build/promote.py` 可写成品目录**：读 staging → 跑 22 门 → 全绿才 dump 到成品目录，并在晋升时原子刷新内容戳（原子戳仅 promote 刷新，D15）。
- **旧习惯直写会被 path-guard 测试红住**：任何工具或手搓脚本直接写成品目录都是结构违规，path-guard 测试会红——直写场景见红不是工具 bug，是拓扑；正确动作是写 staging、等 promote。
- **表侧不变**：tables（含 `chapter_map.csv`）= 审计日志（D11）；你的飞行改动照常做，表与成品包的同步走 promote 路径（表↔包一致性扩到 5 张权威表，D15）。

## 5. 给另一会话的同步段：写准入裁定（2026-09-22，新于你的 F1/F2 提交）

> 另一会话已于 09-22 00:25/00:39/01:08 提交 F1（写工具准入回退到本轮请求侧已知题锚，无题轮拒写）与 F2（轮次门控负向用例）。本节记录**晚于**那批提交的用户裁定（2026-09-21 对齐轮 Q7/Q8/Q13），按"用户当前明确裁定优先于旧决定"，你后续工作以本节为准。架构重构线将按本节重写写准入分支与 F1/F2 负向用例。

**你的机制基底保留**（不重做、不回滚、不重排格式）：
- 每轮题绑定：`TutorRoundQuestionBindingPolicy`（候选菜单 `assembleCandidates` 显式>上轮绑定>本地检索、上限 8；模型声明"这一轮在说哪一道"＋两条本地校验——候选在派发前菜单内、锚词在学生消息里逐字出现且在该题自身文本里可核对；绑定落消息层；会话单调 ordinal）。
- F3 的解答暴露双层守卫（`canExposeSolutionFor` + `requiresRoundQuestionBinding`，行年龄→schema 版本判据）**原样保留**——它管"无题轮不暴露题的答案"，与本次裁定无关。

**要改的（D6/D7，用户 2026-09-21 裁定）**：
1. **权限矩阵废除**：agent 页所有模型调用（Plan/Respond/大厅）同一 5 工具面，工具面不再按场景分支。
2. **无题轮不再结构性拒写**：F1 的"声明与已知锚都缺 = 真无题轮，写工具照旧被拒"分支与 F2 对应负向用例，改写为"无题轮同样可写；写不写由模型语义判定（系统提示词教会）；低置信写入由统一门挡（置信度≥0.7、引文锚底线）"。用例改断言语义，不删用例。
3. **统一本地门全场景同参**：引文锚底线 POSITIVE≥1/MASTERED≥2、置信度≥0.7、12h 冷却、会话 50/learner 1h 100 配额、attention<0.4 拒。
4. **唯一数值分支（安全垫）**：`anchor_class≠CONFIRMED` 时 weight×0.5。`anchor_class∈{CONFIRMED 当前题已确认绑定, CANDIDATE 当前题本地检索候选, DISCLOSED 大厅/工具发现}`，记 `learner_chat_evidence` 新列（纯数据列，审计用）。
5. **两条绑定通道并存、不互替**：你的题锚绑定回答"这一轮在说哪一道题"（解答暴露/写准入的基底）；我们的知识节点代号通道回答"掌握度写入指向哪个知识点"（K1..Kn 轮内代号，原始 id 永不进 prompt/工具参数，enum 白名单校验，预披露=当前题确认绑定+检索候选+前置，KNOWLEDGE_READ 追加披露）。
6. **协调**：工具环区域不要再加新场景逻辑；同文件改动双方都用显式文件列表提交；你的 in-flight 文件（ModelEgress.kt/RoomTutorToolRunner.kt 等未提交改动）我们不改不提交。

## 6. R4a 快路径已知边界（D13 施工登记，2026-09-22）

**信戳不验包**：`install()` 快路径（manifest 先读 + `content_install_state` 戳一致即跳过全量解析与调和）
只验证"内容版本没变"，**不验证 APK 内 JSON 与戳一致**——若 APK 内包文件在戳不变的情况下被改动，
快路径不会发现（全量路径的 `validate()` 也只校验结构/契约，不校验内容哈希）。

- **威胁模型内无攻击者**：APK 受平台签名保护，下载渠道（应用市场/企业分发）的篡改在签名校验层就被拦截；
  现状"每次启动都全量解析"同样只校验 APK 自带资源的结构，对内容篡改的防护是零，不是减损。
- **裁定（沿用审计 §5 R4 注的建议）**：接受该边界，**不加**"每 N 次启动抽样全量校验"之类的机制
  （新增机制必须指认它消灭的具体失败——本威胁模型内没有这个失败，见全局规则 12.2）。
- **登记位置说明**：`docs/adr/0001-agent-page-no-scenario-matrix.md` 是智能体页 D5–D10 的 ADR，与本边界无关；
  故按"决策文档"选项登记于本节。
- **落点代码**：`BundledKnowledgeBaseInstaller.install()` 快路径注释 + `.jez/artifacts/r4a-startup-baseline-2026-09-22.md`（真机 before/after 探针数据）。
