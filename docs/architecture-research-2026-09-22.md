# 智能错题本：架构级全量研究（2026-09-22）

> 目的：为后续「逻辑重构」建立事实基线。本文只回答宏观问题——产品的使用逻辑、前后端接线、
> 页面编排——不讨论代码风格。
>
> 证据口径：标 `[自核]` 的是本会话亲手读源码/实测确认的；标 `[代理]` 的是并行探查代理报告、
> 附了 `file:line` 但我未逐条复核的；标 `UNVERIFIED` 的是没有一手证据的。
> 与 `docs/current/*` 冲突时，以代码实测为准并在文中说明。

---

## 1. 规模与形态（实测）

| 项 | 值 | 来源 |
|---|---|---|
| 主源码 | 约 124,000 行 / 443 文件（14 个模块） | `[自核]` find/wc |
| 测试源码 | 约 85,000 行 / 395 文件（测试/主 ≈ 0.68） | `[自核]` |
| 最大模块 | `core:database` 30,599 行 / 131 文件；`core:data` 23,863 行 | `[自核]` |
| 持久层占比 | `core:database` + `core:data` = 54,462 行 ≈ **全仓 44%** | `[自核]` |
| Room 版本 | **51**，导出 schema 1..51 齐全，迁移 50 个 | `[自核]` `StudyDatabase.kt:126` |
| 表 / 视图 / DAO | **84 张表 + 2 视图 + 31 个 DAO** | `[代理]` |
| 领域内核 | `core:domain` 73 文件，但**只有 1 个类叫 `UseCase`** | `[自核]` |
| 导航目的地 | **24 条 route**，1 个 NavHost，无嵌套 graph、无 URL 深链 | `[代理]` `SmartMistakeBookRoutes.kt:18-43` |
| git | **543 commits，全部在最近 60 天内**；无 release tag | `[自核]` |
| 版本 | `versionCode = 2` / `versionName = "0.2.0"` | `[自核]` `app/build.gradle.kts:26-27` |
| 知识库载荷 | 15 个 JSON、raw **52.24 MB**、压缩后 **11.76 MB**，随 APK 分发 | `[代理]` 实测解包 |
| 首次安装实测 | 进程启动 → 知识库 install 返回 **43.7 s**（其中 JSON 全量解析 16.7 s） | `[代理]` `.jez/artifacts/r4a-startup-baseline-2026-09-22.md` |
| 稳态启动（R4a 后） | install 296 ms；TOTAL PSS 153 MB（原 233 MB） | 同上 |

**第一条宏观结论**：这是一个**从未发布、没有真实用户**的 0.2.0 手机应用，它的持久层与导入管线
占了将近一半代码量，而它的产品面（四个根页）在最基础的第一分钟体验上仍然是不通的（见 §3.1）。

---

## 2. 三条裂缝：设计层与实现层已经分开走了

这三条是后面所有具体问题的共同根因，也是重构真正要处理的东西。

### 裂缝 1：契约写在测试里，不在运行时 `[自核]`

`docs/current/model-task-contracts.md` 是"权威契约"，它说「每个 `ModelTaskKind` 都有一份机器可
校验契约」。事实是：

- `ModelTaskContractRegistry` 只被契约测试消费，**派发路径上没有任何调用者** `[代理]`。
- 更严重：讲题有题轮（Respond/Plan/Visual）在构造请求时一律 `egressManifest = null` +
  `agentConsentGranted = true`（`feature/tutor/.../TutorModelTaskPolicy.kt:471-478` 等四处），
  于是 `ModelEgressPolicy.authorize` 在 `ModelEgress.kt:606-611` 直接返回 `ProviderConsented`
  ——**`requireAuthorizes` 里 Respond/Plan 的披露分支在生产结构性不可达** `[自核]`。
- 结果：项目反复强调的"披露集合是精确相等"这条不变量，**只在大厅与采集路径上真的执行**；
  产品主路径（有题讲题）只在测试里成立。
- 同一模式的另一例：工具调用审计（工具名/参数哈希/结果大小/耗时/授权依据）在 spec 里要求落库，
  代码里**没有任何落库点** `[代理]`。

**含义**：这个仓库的工程纪律（指纹、幂等、CAS、契约测试）密度远高于一般项目，但其中相当一部分
是"纸上安全"——它保证的是"不该变的东西没变"，而不是"运行时真的被拦住"。

### 裂缝 2：两套互斥的产品世界观同时在跑，且都上线了 `[自核]`

| | 世界观 A：**只复习你自己的题** | 世界观 B：**生成知识点选择题** |
|---|---|---|
| 出处 | `docs/product-information-architecture.md` §6、`docs/student-experience-spec.md` §9、`docs/model-first-product-boundaries.md` §6.9、`docs/current/release-gates.md` | `docs/specs/2026-09-06-dual-review-entry-design.md`（§6 标注"四个待定全部由用户确认收紧为已定"） |
| 规则 | 生产复习内容源**只有** `ACTIVE` 真实错题的原始转写；**未经用户请求生成新题/同类题/变式题/校准题数 = 0** | 用知识库讲解材料**现场生成选择题**，学生作答后**回写掌握度** |
| 代码 | `ReviewSessionScreen` + `CapturedReviewSessionScreen` | `KnowledgeReviewSessionScreen` + `KnowledgeReviewQuizLoader` + `KNOWLEDGE_QUIZ` 模型任务 + `KnowledgeQuizFeedbackWriter` |
| 入口 | 复习首页 `review_start_button` | 复习首页 `review_start_knowledge_review` 卡片（`ReviewRoute.kt:80-87`） |
| 现状 | 已上线 | 已上线 |

两条都在复习首页并列陈列（`ReviewRoute.kt:78-102`）。世界观 B 直接违反 A 里被重复冻结四次的红线，
但它有明确的学生需求描述（"先复习知识点，再复习错题"）和用户签字。

**含义**：这是本次重构的根分叉，不是 bug。它同时解释了内核里为什么会有**三台调度器**
（`ReviewPlanner` V1 / `ReviewPlannerV2` / 知识点队列借用 V1 打分器 `[代理]`）和
**两套"到期"与"掌握"语义**。

### 裂缝 3：文档是一层层增补的羊皮纸，没有单一真值源 `[自核]`

同一批文档里同时存在互相取代的表述，而正文多数没有改：

- dispatch 上限：正文满篇"3 次"，实际常量是 6（`ModelTasks.kt:62`），只有 `docs/current/product-contract.md` 写对。
- `TUTOR_LOBBY`：`docs/current/product-contract.md:11-12` 明说"lobby is text-only"，代码里大厅**接受最多 9 张附图**（`TutorLobbyTasks.kt:26`、`TutorLobbyRoute.kt:197`）`[自核]`。
- 九科 → 四科：`docs/model-first-product-boundaries.md` §2 仍以九科本体为基线，实际只做四科。
- `spatial_diagram` 与 motion 场景：IA §13 与 UX §7.2 的 allowlist 写着它，实现里另有 `TutorSpatialDiagram.kt`+4 种 motion，而同批文档又说结构化场景已隔离 `[代理]`。
- `docs/knowledge-memory-retrieval-design.md` 仍称"9 个板块、19 个细知识点"，实际是 398 主题 / 3,572 原子 / 27,182 材料 `[代理]`。
- `docs/tutor-surface-unification.md` §4 的 D1/D2/D4/D6 仍标"真 bug"，同文件 §6 又说已修完 `[代理]`。

**含义**：重构不能以文档为起点。开工前需要给"当前产品真值"选一个载体（见 §10 决策树 Q7）。

---

## 3. 用户使用逻辑与真实行为的贴合度（第 2 问）

### 3.1 关键路径：第一分钟是断的

1. **首次启动要等 43.7 秒**才能完成知识库安装，且 `initialize()` 排在 install 之后，
   安装完成前学习快照不可用。学生第一次打开 App 看到的是等待。`[代理]` 实测日志锚在
   `.jez/artifacts/r4a-startup-baseline-2026-09-22.md`。
2. **空库时复习首页是死路**：冷启动落 `复习`（`SmartMistakeBookRoot.kt:272`），主按钮
   `enabled = scheduledCount > 0 && !completedToday`，文案"暂无待复习题"，**页内没有任何录题入口**
   （`ReviewRoute.kt:88-102`）`[自核]`。而文档承诺"如果题库为空，则同一页面切换为空状态，
   主动作改为 `录入第一道错题`"（`docs/product-information-architecture.md:55`）——没实现 `[代理]`。
   学生的唯一出路是切底栏到错题本。
3. **没有模型时复习整条队列不能推进**（卡在第 1 题）。这是 2026-09-14 的知情裁定，代码 KDoc
   明写"等于该构建没有复习功能"，理由是不愿伪造证据（`CapturedReviewSessionScreen.kt:38-49`）
   `[自核]`。注意：这与"拆除复习自评"是同一个决定的另一面。

### 3.2 三种"答对"只实现了一种 `[代理]`

内核设计的分档是完整的：独立答对 / 提示后答对 / 重试后答对 / 看答案后（不计分）。但
`StudySubmissionPreparer.kt:53-59` 构造提交上下文时**从不传 `persistedAssistance`**，UI 也没有
提示入口，于是 `CORRECT_AFTER_HINT` / `CORRECT_ON_RETRY` / `INCORRECT_AFTER_HINT` /
`INCORRECT_ON_RETRY` 四条分支在生产结构性不可达。学生端看到的永远是"独立答对/独立答错"。
"看答案后"这一档由账本层的因果判定兜住（`AttemptTransactionDao.canonicalizedAfterReveal`
+ `LearningProjector.applyRevealCausality`），所以不是完全失真，但**"用了提示"这件事在产线上
没有记录通道**，讲题里的提示依赖也只靠模型证据链。

### 3.3 学生明显会期待、但没有的能力（按期待强度排序）`[代理]`

| # | 能力 | 证据 |
|---|---|---|
| 1 | 错题详情**再练/重做** | 全仓 main 无 `再练`/`重做` 入口；IA §12 要求它是详情页固定底部动作之一 |
| 2 | 复习中卡住 → **需要讲解** | `ReviewSessionScreen` 只有本地揭示；IA §9/§11.7 承诺"在来源页面上打开共享全屏流程，返回继续当前题"。当前要绕 5 跳并重拍 |
| 3 | 作答结果**撤销**（5–10 s） | 全仓无实现；IA §10 承诺 |
| 4 | 结果与**改期解释**页 | 无 route、无文案；IA §6 时序图与 §12 都要求 |
| 5 | **复习历史** | 无 route |
| 6 | **考前冲刺**临时队列 | 全仓无 `冲刺`；只有 14 天加权 ramp（`StudyReviewPlannerService.kt:101-112`），IA §9 与 M1 都要求独立队列 |
| 7 | **待处理草稿工作台** | 错题本根页无待处理卡；单题采集的草稿**没有任何恢复入口**（`capture/resume` 只由批量导入/拆分页触发）——这同时是 KD-17 |
| 8 | 分类修订**从候选里选** | `RoomMistakeOrganizationRepository.observeOrganizationOptions` 已实现但**零调用**；学生只能手打学科术语 |
| 9 | 学习记忆**纠正** | `attempt_correction` 表与投影都在，`StudyExperienceRepository` 无纠正命令，UI 只有只读卡 |
| 10 | 学习偏好：每日时长 / 左右手 / 外观 / 难度 | 全无实现；复习时长硬编码 1200 s（`RoomBackedStudyExperienceRepository.kt:91,956`）且**无任何调用点可覆盖** |
| 11 | 列表**排序控件** | `LibrarySort` 4 个值已定义、后端已支持，UI 恒用默认 |
| 12 | **系统分享**进来（`ACTION_SEND`） | Manifest 只有 MAIN/LAUNCHER；IA §11.8 承诺分享进入时二选一 |
| 13 | 通知深链**直接进复习会话** | 只到复习根页 |
| 14 | 首次使用引导（onboarding） | 无 route |

### 3.4 冗余：真的有，但不在"页面"上，而在**通道**上 `[代理]`

| 类型 | 实例 | 判定 |
|---|---|---|
| 同一件事的入口过多 | 把题带进 App 有 **5 条**通道（相机/相册/批量导入照片/PDF/讲题附图），而**分享入口缺失** | 5 条里 4 条各有明确意图，问题不是"多"而是"缺一" |
| 统计面 **4 个** | 复习首页（题数/时长/连续/薄弱点）、我的（学习次数/已掌握/连续/薄弱点）、掌握度页、能力设置页里的 Brier/ECE 校准面板 | 前两者指标重复；第 4 个是面向学生的算法分，与 IA §13"不暴露 FSRS/BKT/HLR 算法名"冲突 |
| 分类修订 **2 条**通道 | 详情页"修改章节/知识点" 与 整理卡里"分类有误，修改" | 写同一个命令、入口不同；功能上不算错，但学生找不到哪条是正路 |
| 复习会话 **3 个**屏 | `ReviewSessionScreen`（机判）/ `CapturedReviewSessionScreen`（讲题判定）/ `KnowledgeReviewSessionScreen`（知识点） | 前两者 UI 都自称"今日复习"，学生无法预判点进去会得到什么 |

### 3.5 一个反直觉的事实：AI"能力清单"是隐形的

学生能看见模型输出的文字，但看不见模型**做了什么**：工具结果正文从不进 UI，界面只有
"正在查阅…／已查阅…（n 条结果）"两行标签；工具调用没有落库审计。而模型手上有 5 个工具，
其中写工具（`NOTEBOOK_WRITE`、`MASTERY_UPDATE`）在产线是可达的。外部对标里最一致的一条建议
就是把这个清单显性化（见 §7）。

---

## 4. 前端使用逻辑 ↔ 后端内核：接线审计（第 3 问）

### 4.1 错接（wrong wiring）

| # | 现象 | 证据 | 严重度 |
|---|---|---|---|
| 1 | **"确认加入错题本"实为跳转错题本**，不落任何库 | `SmartMistakeBookDestinations.kt:375-377` 的 `onRequestSave` 就是 `navigate(Routes.Library)`，代码注释自认；按钮文案却是"确认加入错题本"（`TutorLocalIntentPanel.kt:133-141`） | 高 |
| 2 | **基础出口的可见性交给模型**：`TutorLocalIntentPanel` 在 `capabilities.isEmpty()` 时直接 `return`，而 capabilities 由模型本轮 `intentDecision` + 学生原话决定 —— "结束会话"这种基础动作会因模型输出而消失 | `TutorLocalIntentPanel.kt:70` `[代理]` | 高 |
| 3 | **KB 故障被误报成 Provider 未配置**：整理失败的任何异常都映射到 `PROVIDER_NOT_CONFIGURED` + "打开设置" | `MistakeOrganizationSection.kt:349-356` `[代理]` | 中 |
| 4 | **"去错题本看看"实际返回我的页** | `LearningMasteryScreen.kt:58-63` 的 `onAction = onBack` | 中 |
| 5 | **底栏再点当前 tab 不回根页**（深页里点"智能体"无效） | `SmartMistakeBookRoot.kt:242` 的相等判定直接跳过导航；IA §11.3 承诺回根页 | 中 |
| 6 | **轮内预算与 UI 重试计数不同源**：UI 3 次 vs 内核 6 次派遣 vs 工具环 5 轮；重试按钮不查预算 | `TutorTurnSendStateMachine.kt:3` / `ModelTasks.kt:62` / `TutorToolLoop.kt:215` `[代理]` | 中 |
| 7 | **Route A 与流式互相拆台**：`stream` 只看 `supportsStreaming`，`enableNativeTools` 只看 `supportsFunctionCalling`，可同时为真；而原生 tool 轮的标准形态是 `content=null`，SSE 重建会对空 content 抛错 | `OpenAiCompatibleModelGateway.kt:205-206,221-222` / `OpenAiSse.kt:97-101` `[代理]`，真实端点 UNVERIFIED | 高（未实测） |
| 8 | **UI 层的派遣预算会被进程重建清零，而账本不会**：`MAX_TUTOR_DISPATCH_ATTEMPTS = 3` 只活在 `remember(question.sessionId) { mutableStateOf(TutorSendState()) }`（非 `rememberSaveable`、不持久化），而持久账本按 `MAX_DISPATCHES = 6` 的 SQL CAS 记账。两层对**同一资源**给出不同上限，弱的那层可被绕过 | `TutorTurnSendStateMachine.kt:3,192` / `TutorSessionPanel.kt:228` / `ModelTaskTransactionDao.kt:265,271` `[代理]` | 中（一致性缺陷，非纯冗余） |

### 4.2 冗余接线（两条通路做同一件事）

| 冗余 | A | B | 备注 |
|---|---|---|---|
| 工具轮进度 | 内存 `roundRequest` | `requestSnapshot` 只在 create 时写 | 进程死亡后工具轮结果全丢，整轮重放 `[代理]` |
| 意图 → 本地读 | 工具环授权矩阵 | `TutorLocalIntentPanel` + `TutorLocalReadProjection` | 同一个 `intentDecision` 被读两遍 `[代理]` |
| 工具文案 | Route B 长版 | Route A 短版 | 两套协议分支并存 `[代理]` |
| 历史裁剪 | `TutorHistoryBudget.bounded`（**生产零消费者**） | `TutorContextComposer.compose` | 契约上限 `MAX_PRIOR_MESSAGES=8` `[代理]` |
| 会话文本 | `tutor_message` | `model_task` 快照 | 同一文本双份持久化 `[代理]` |
| 内核调度 | `ReviewPlanner` V1 | `ReviewPlannerV2` | 默认 V2，但知识点队列仍用 V1 的打分器 `[代理]` |
| 日志预算 | 内核 6 | 工具环 5 | 实测并存 `[代理]` |
| 同一条规则两处实现 | `masteryRiskFor`（V1） | V2 内联副本 | 分支/阈值/理由枚举相同 `[代理]` |
| 同值常量多份 | `MAX_DIVERSITY_PENALTY=1.5` ×3、`EARLY_REVIEW_MAX_RETRIEVABILITY=0.8` ×2、`0.85/45天` ×2、`0.6` ×2 | | 改一处不会让另一处变红 `[代理]` |
| 同名类两份 | `MappingPagingSource` 在 core:database 与 core:data 各一份 | | `[代理]` |

### 4.3 生产结构性不可达的分支（"死接线"汇总）

这一类的量级最大，也是"重构"最省力的地方：

| 整块不可达 | 证据 | 原因 |
|---|---|---|
| **评估式讲题支路**：`TutorScreen`（选择题讲题页）、`TutorAdaptivePauseScreen`、`TutorUnavailableScreen`、整个 `TutorCapabilityGate` 分支，以及 `Routes.Tutor` 的 `onSave/onSubmitChoice/onRevealAnswer` 形参 | `StudySnapshotBuilder.kt:113` 写死 `tutorPracticeUnitId = null`；`EmptyStudyFixtureSource.tutorPracticeUnitId = null`；`tutorDecision = null` | 工件只在 debug 夹具里存在 `[自核]` |
| **`VerifiedTeachingArtifact` 整套**（含它的 capability gate） | `RoomBackedStudyExperienceRepository.kt:458-459` 只读 fixture source，生产恒 null | 同上 `[自核]` |
| **知识点/研究/自适应三条链**：`KnowledgeResearchCoordinator`（+ 其 data 侧两个实现）、`KnowledgeRetrievalBenchmark`/`KnowledgeRetriever`、`AdaptiveQuestionSelector` 家族、`TutorAssistanceCoordinator`、`BatchSplitRecognizer`(137 行)、`RestoreRecoveryCoordinator`(404 行) | 均只有自身测试引用 `[代理]` | 建了没接 |
| **`AdaptiveQuestionSelector` 生产者与消费者脱节** | 选择器无人调用，但它的输出类型 `AdaptiveDecision` 被 UI 消费（`feature/tutor/TutorRoute.kt:63,108`） | `[代理]` |
| **大厅 5 个工具里 4 个只能返回错误**：`MASTERY_UPDATE` 白名单恒空 → `invalid_knowledge_code`；`KNOWLEDGE_READ`/`MASTERY_READ` 无 subject → `no_subject`；`NOTEBOOK_WRITE` 无 session → `no_conversation` | `RoomModelTaskRepository.kt:353-355`、`RoomTutorToolRunner.kt:157-164,347-355,442-450` | ADR 0001 预期"大厅也能调写工具"，实现相反 `[代理]` |
| **TUTOR_PLAN 的流式文本无订阅** | `rememberTutorLiveTurn` 只挂在 respond 与 lobby 两处 | 计划期间 UI 只有静态卡 `[代理]` |
| **结构化场景渲染 + Filament** | `TutorVisualIsolation.STRUCTURED_SCENE_ISOLATED = true`（`TutorVisualIsolation.kt:16`），5 个渲染点全部 `if (!ISOLATED)`；而 `:core:visual-ui`（10 文件/2,799 行）依赖 **Filament 3D 引擎**（`libs.versions.toml:30,73-75`），`app`/`core:ui` 都依赖它 | 生产无可达渲染路径 `[自核]` |
| **三张"写而不读"的表** | `model_task_event` 只写不读、`projection_consumption` 同 | `[代理]` |
| **`RoomStudyDatabase` 的 31 个子端口拆分** | `core:data` 里子端口作为**类型** 0 处使用，全部只用聚合 `StudyDatabasePort` | 拆分没有消费者 `[代理]` |
| **`seedFixture` / `EmptyStudyFixtureSource`** | 只有 androidTest / debug 使用 | `[代理]` |

### 4.6 对抗性审计：过度工程与死重（专查"为不存在的状态保留的机制"）`[代理]`

判定口径沿用仓库自己的纪律：每个机制必须指出它消除的**具体既有故障**，且该故障必须能由生产输入
产生（给出构造点/状态机证据）。按「移除收益 × 置信度」排序：

| # | 机制 | 规模 | 判定 | 关键证据 |
|---|---|---|---|---|
| R1 | **`core:visual-ui` 整模块 + `core:visual-runtime`** | 3,820 行 + Filament/Vico | **UNREACHABLE** | 10 个文件逐文件扫描 `external: NONE`；跨模块引用只有 `core/ui/.../TutorVisualSceneRenderer.kt:51` 读一个类型，而它本身在隔离开关之后。**即使把 R2 的开关翻回 false 也无救**——渲染走的是 `core/ui` 的渲染器，不是 visual-ui 的 panel。依赖却已进包（`app/build.gradle.kts:141`） |
| R2 | **`STRUCTURED_SCENE_ISOLATED = true` 遮蔽的整条 TUTOR_VISUAL 链** | >2,000 行 | **UNREACHABLE-BY-CONFIG** | 唯一生产数据源 `TutorSessionPanel.kt:466-477` 在开关为真时返回 `emptyList()` → 下游 `dispatchGenerate/dispatchReview` 的 `selectedSeeds()` 恒空、循环体永不执行 → 渲染点全在 `if (!ISOLATED)` 内。代价：2 个 `ModelTaskKind`、一份响应解析器、2 份契约、2 个披露函数仍全量维护 |
| R3 | `ModelTaskContractRegistry` | 155 行 | **TEST-ONLY** | 生产引用 0；且 `maxProviderDispatches` 被 init 强制等于唯一值 —— 字段只有一个合法值 |
| R4 | **egress manifest 的多层校验** | ~300 行 | JUSTIFIED-BUT-DUPLICATED / capture+tutor 分支 UNREACHABLE | 生产构造 manifest 的地方**全仓仅一处**（`RoomMistakeOrganizationRepository.kt:254-283`，且 `assets = emptyList()`）；capture/tutor 一律 `agentConsentGranted = true` 走 `ProviderConsented`。于是 `requireAuthorizes` 的 8 个分支里 7 个、`ModelEgressAssetGrant.init` 的 6 条断言、资产源逐字节比对，在生产不可达。同一事实在 **4 层**重复强制，对唯一的生产 manifest 后两层恒真 |
| R5 | **授权 TTL / 时钟偏移守卫** | 18 行 + 断言 | **SPECULATIVE（守卫的状态由调用方自己制造，无法发生）** | `approvedAtEpochMillis` 的唯一值来源是客户端自己刚写的当前时间；所有派发/恢复点都在同一帧重盖（`MistakeOrganizationSection.kt:145,189,609`）。不存在"15 分钟前批准、现在才派发"的路径，所以 15 分钟判据永远成立 |
| R6 | `prohibitedData` 字段 | 12 处比对 + 8 组常量 | JUSTIFIED-BUT-DUPLICATED | 它由 `schemaVersion` 唯一决定（`disclosedData` 足以重建），生产**读取 0 处**，没有任何决策消费它 |
| R7 | **41 个窄端口** | ~700 行接口 | **UNREACHABLE（作为隔离机制）** | 所有 `core:data` 仓库的构造参数类型都是 God 接口 `StudyDatabasePort`；窄端口除 extends 列表外零出现。反向证据：测试假实现被迫写成 `StudyDatabasePort by delegate` —— 窄端口本应消除的问题，以委托适配器形式反噬 |
| R8 | 死类型清单（8 个独立族） | ~500 行 | UNREACHABLE | `ProviderCapability.kt`（`ProviderProbeSnapshot`/`ProviderFallbackStrategy`/`CapabilityProbeResult`，生产零引用）；`ProblemPort.kt` 与 `LearningPort.kt` 两族（无实现者、无调用者、不在 extends 列表）；`KnowledgeRetrievalBenchmark.kt`(270)；`HLRShadowMode.kt`(117)；`TutorAssistanceCoordinator` 家族；`captureRecoveryApplication`；`ModelEgressPurpose.REVIEW_PLANNING`（成员与拒绝它的守卫同生共死） |
| R9 | **三套派发预算 + 三/四层重复强制** | — | JUSTIFIED-BUT-DUPLICATED | 见 §4.1#8；`MAX_DISPATCHES` 侧另有 `canSchedule` 预检 + DAO CAS + test-only 契约表三层 |
| R10 | `@Deprecated` 且仅测试使用的接口面 | — | LEGACY SHIM | `advanceReviewSession`、四个 `readXxxP0` —— 生产调用 0，实现与测试仍在维护 |
| R11 | **`DEFAULT_USE_REVIEW_PLANNER_V2 = true` 回滚开关** | V1 608 行 | JUSTIFIED-BUT-DUPLICATED | 无任何调用者传 `false` → V1 的 `plan()` 不可达；但 `scoreKnowledgeNode` 仍被知识点队列使用，**故不能整类删除**（这也是 §2 裂缝 2 的下游后果） |
| R12 | 两份逐字相同的 `Json` 配置 | — | DUPLICATED | 都在指纹计算路径上（`ModelTasks.kt:752-757` vs `:759-765`）；相关风险：**schema 演化知识写两遍**（9 个 `withoutEmptyXxx` 剥离器 + Room 迁移），漏改即指纹不匹配——`bf8be888` 就是同类事故 |
| R13 | 其余重复强制层 | — | — | operation fingerprint **四层**，其中"每次读库重算 SHA-256"那层防的是"库内行被应用外篡改"，应用内无此输入 |

**反向发现：为桥接自己的层次而存在的东西（比冗余更贵）**

1. **main 源码里的可变全局测试接缝**：`StudyFixtureSource.kt:42` 是 `var source`，被 6 个生产类注入
   （`RoomBackedStudyExperienceRepository.kt:111`、`StudyReviewPlannerService.kt:54`、
   `StudySnapshotBuilder.kt:28`、`StudyAnswerRevealService.kt:21` 等）。**生产行为取决于一个全局可变量**，
   而它的生产值恒为 `EmptyStudyFixtureSource`——这正是 §4.3 里那一整块不可达的根。
2. **UI 层为满足自家不变量而重算时间戳**：`renewOrganizationRequest` + `maxOf`（见 R5）。
3. **只为"看起来接了线"的自身拷贝**：`RoomModelTaskRepository.kt:396` 在 `copy(input=…)` 里显式重传
   `egressManifest = roundRequest.egressManifest`。
4. **常量池化的死接口**：`KnowledgeReadPort.MAX_TEACHING_MATERIAL_CANDIDATES` 是 R7 中唯一被消费的端口成员。

> 该审计的一句话结论（与本节其余部分一致）：这个仓库最贵的三件事都不是"代码写得差"，
> 而是**为已经不存在的状态保留的机制**：visual-ui/visual-runtime（3,820 行 + Filament）、
> 被 `true` 遮蔽的 TUTOR_VISUAL 链（>2,000 行）、以及 `ModelTaskContractRegistry` /
> egress manifest 的 capture-tutor 分支 / `prohibitedData` / 授权 TTL 这一组
> "用测试和生产代码各自满足自己"的不变量。前两者是纯删除收益；后三者要先把不变量重新锚到
> 真实输入上，**否则删掉它们不会有人察觉**——这本身就是它们不该存在的证明。

### 4.4 欠接（内核算了/存了，学生看不到）

| 内核算出 | 状态 | 证据 |
|---|---|---|
| **"为什么是这几题"**（`ReviewReason`） | 算好、展平、存进 `StudyReviewOverview.reasons`，**UI 零消费** | `[代理]`；IA §9 明确要求显示 |
| **知识点覆盖率 / 待完善汇总**（`knowledgeCoverage`） | 算了没人读；`docs/knowledge-memory-retrieval-design.md:84` 承诺的"几类知识点正在整理"无实现 | `[代理]` |
| 每题 `retrievability` | 组装了，`feature:library` 无消费 | `[代理]` |
| 学习记忆**纠正** | 表可写、投影支持、无命令无 UI | `[代理]` |
| `IntakeBacklogPreview` 的"约 M 天覆盖" | **用的是硬编码 900 s**，而实际引入决策用 1200 s；注释自称"与排程同源" | `ReviewRoute.kt:351-368`，唯一发现的"UI 数非内核算" `[代理]` |
| KB 名称缺失时 | 回退显示原始 id（`kb:…:atomic:…`、`pseudo:MATH`）；词汇泄漏守卫只查固定词表，不查 `kb:`/`pseudo:` 形态 | `StudyExperienceMappers.kt:59-63,109,134` `[代理]` |

### 4.5 知识库接线的真实现状（结论与常见印象相反）

KB **确实接进了运行时**，且是错题自动整理与讲题材料注入的必经数据源 `[代理]`。但：

- **五科实质无 KB**：中文/英语/政治/历史/地理各只有 2020 样例的 **2 个原子节点、0 份材料**，
  且这 2 个节点**可被召回**（`CURATED`/`SOURCE_GROUNDED` 都在准入里）。学生界面对"本学科未覆盖"
  **零提示** `[代理]`。
- **两代 taxonomy 同时装着**（2020 样例 + 2025 四科），同一科里两套命名并存，退役映射只覆盖 2025 `[代理]`。
- **检索质量低于预注册门槛**：生产形状（`KNOWLEDGE_READ` 已回滚为裸词面 B5）Recall@5 = **0.5444**，
  逐章仅 1/10 ≥ 0.80；而金标测试**刻意不断言阈值**，回归不会红 `[代理]`。
- 别名截断影响 **61.6%** 原子的检索索引、**27.7%** 节点进 prompt 前被截 `[代理]`。
- 注释声称的门已不存在：`BundledKnowledgePackResources.kt:543-545` 说"每侧车 ≤2048 份材料由解码侧强制"，
  实测 10 个侧车有 8 个超限（最多 4,595）`[代理]`。

---

## 5. 页面与按钮编排（第 4 问）

### 5.1 24 条 route 的裁决建议

| 类别 | route | 裁决 |
|---|---|---|
| 根页 4 | review / tutor / library / profile | 保留；`tutor` 标签需定名（见 5.3） |
| 采集 5 | `capture/tutor`、`capture/library`、`capture/batch`、`capture/resume/{draftId}`、`capture/split-review` | 保留；`capture/tutor` 与 `capture/library` 是同一屏两意图，**建议收敛成一条 route + intent 参数**，同时把"输入意图"记进草稿以便单题也能 resume（补 KD-17） |
| 讲题 4 | `tutor`、`tutor/captured/{id}`、`tutor/lobby/{id}`、`mistake/tutor/{...}` | **建议收敛成 1 条**（带什么 = 参数，不是 route）。当前 4 条渲染同一页但**仍是 2–3 个正文组件**，`TutorScreen`(第 4 个) 已不可达 |
| 复习 2 | `review/session`、`review/knowledge-session` | 取决于 §10-Q2：若保留知识点测验则保留，若合并则两条并一条 |
| 错题 3 | `mistake/{itemId}`、`mistake/export/{...}`、`library/export` | 保留；两个 export 可合并为一条带 selectionId 的 route（IA §11 本就建议 `export/{selectionId}`） |
| 设置 6 | `settings/capability`、`settings/privacy`、`settings/reminder`、`settings/scheduling`、`settings/storage`、`profile/learning-mastery` | **建议合并为 3 个**：智能能力 / 学习与提醒（含排程）/ 数据与存储（含隐私说明）。IA §2 本来就写的是三分组，现在是 5 个平铺入口 + 1 个掌握页 |
| 其它 | `profile/scheduling` 等 | 见上 |

### 5.2 "每屏一个主任务"已被侵蚀 `[自核]`

复习首页现在一屏里有**三个同等醒目的元素**：知识点复习卡、`开始今日复习` 主按钮、
`IntakeBacklogPreview`（"每天约 X 题，约 M 天覆盖"）；再加上问候语、统计、薄弱点、连续文案。
而 UX 规范 §4.1 是"每屏一个主任务，主按钮最多一个"。详情页同理：顶部两个同级主按钮
（讲解 / 导出）+ 底部更多菜单。

### 5.3 术语在同一入口上分裂（domain-modeling 视角）`[自核]`

| 概念 | 冲突 | 现状 |
|---|---|---|
| 第二个 tab | 底栏标签是**"智能体"**（`SmartMistakeBookRoot.kt:123`），页内标题与全部产品文档写**"讲题"**（`TutorRoute.kt:588`；IA/UX/M1 三份文档都冻结为"讲题"） | 同一入口两个名字 |
| "复习" | 同时指"错题复习"和"知识点复习"，两个不同屏的 UI 都自称"今日复习" | 需要区分名 |
| "大厅"/"讲题页" | 用户 2026-09-19 已裁定"不存在大厅页和讲题页，只有一个智能体页面"，但代码里仍有 `TutorLobbyRoute`、`TUTOR_LOBBY` 任务、`TutorLobbyInput/Output`、`lobby` 路由 | 词已废、物还在 |
| "错因" | 四份文档禁止"错因"成为可见分类/标签/必填；详情页却有名为**"错因 / 备注"**的输入区（`MistakeDetailRoute.kt:495,556`） | 边界冲突，需裁决 |
| "确认加入错题本" | 见 §4.1#1 | 名不副实 |

---

## 6. 可以打破的既有逻辑（第 5 问，按收益排序）

1. **把 52 MB JSON 的知识库载荷改成预置 SQLite / 后台安装 / 分科懒装**，消灭首次安装
   43.7 s 与 16.7 s 的全量 JSON 解析，顺带删掉 `BundledKnowledgePackResources` 的手写
   JSON 遍历器与部分 installer 机件（保留内容差分调和，它是真实需求）。
2. **数据层重置到单一基线**。0 真实用户、无 release tag、`versionCode=2`（`[自核]`）的前提下，
   50 个迁移与 51 份 schema 服务于零个人群；`RELEASE.md` 自己写"若不存在 v16 时代的安装，
   这笔债只是档案性质的"。把 84 张表也在同一轮做一次合并（当前有大量"写而不读""只有审计用途"
   的表）。
3. **删掉整块不可达的代码**（§4.3 全表 + §4.6 的 R1/R2/R7/R8/R10/R11）。包括 Filament 3D 栈与整个结构化
   场景渲染的残留、41 个窄端口的接口面、`ProviderCapability.kt`/`KnowledgeRetrievalBenchmark.kt`/
   `HLRShadowMode.kt` 等死类型、`@Deprecated` 且仅测试使用的接口面。
   隔离开关 `STRUCTURED_SCENE_ISOLATED` 的价值是"保留可恢复"，代价是**此后每次重构都要照顾它**；
   git 历史本身就是恢复手段。
4. **收敛"讲题"为一个 route + 一个正文组件**，把"带什么"降级为参数与草稿状态。
5. **合并两台调度器为一台**，并把 `ReviewPlanner` V1 从知识点队列上摘掉。
6. **给 v1 的证据分档补上提示/重试通道**（或明确删掉那四个分支、把文档改成"只区分独立/非独立"）。
   保留无通道的分支比删掉更贵：它让 `MasteryEvidencePolicy` 的测试在测一个产线到不了的函数。
7. **显性化模型能力**：把 5 个工具、披露范围、工具调用结果做成学生/家长可审计的清单与回执
   （外部对标一致推荐，见 §7）。
8. **重排复习首页**：一个主动作 + 队列预览 + "为什么是这些题"（内核算好了，只是不显示）。
9. **把"意图不丢"改成结构保证**：讲题会话页的待发附图用 `rememberSaveable`（其余状态已经用了，
   只有它在丢，见 §4.2/E 报告）。
10. **术语收敛**：见 §5.3，先定名再改代码。

---

## 7. 外部对标：GitHub 上与页面编排相关的可偷点（第 6 问）

代理逐个抓取核实了 14 个项目的 URL/许可证/活跃度（`[代理]`，抓取时间 2026-09-22）。结论先摆：

> **这个细分领域目前不存在成熟可抄的样本。** 最贴近的 `vimalinx/ArtIflow`（中学 Android AI 辅导）
> 只有 50★、无 LICENSE、4 个月未更新；没有找到高质量、活跃的"高考多科 + AI 错题本 + 本地优先"
> 直接竞品。可偷的是**交互与页面结构**，不是整体架构。交互与页面结构不受版权保护，代码与素材受保护。

最有价值的 10 条（按预期收益排序，含来源）：

| # | 模式 | 来源 | 为什么对我们值 |
|---|---|---|---|
| 1 | **单题持久追问线程 + 段落级锚点**：一个错题 = 一条只属于它的对话，挂在题详情里；讲解按段落切分，追问挂在具体段落 | ArtIflow | 我们已经是流式 + 工具环，但"全局单一会话"会把不同题的上下文搅在一起，也让"这题上次问到哪"丢失 |
| 2 | **评分即调度：按钮上印出下次复习时间** `[生疏 <10分][勉强 2天][会了 15天]`，可撤销 | Anki / AnkiDroid | 一次点击完成"评分+排程+记录+看见后果"；我们没有本地自评（已拆除），但可复用在同一位置放"讲题判定/查答案"的后果预览 |
| 3 | **双解析分栏**：`[知识库解析][AI 解析]` 两个 Tab 并排，注明来源 | 陪陪刷 | 把知识库检索从后台机制变成**可见的信任凭据**，天然对冲幻觉。我们 KB 已有 27,182 份材料但学生看不到 |
| 4 | **零摩擦入库**：系统分享 intent + 连续扫题后台排队（当前题解析中可继续拍，失败题单独重试、不重解已成功题） | AnkiDroid / ai-wrong-notebook | 我们缺分享入口，且单题草稿退出后无恢复入口 |
| 5 | **保存动作显式二分**：`保存概念 → 知识库` vs `保存题目 → 错题本`，存概念时**不上传题干/选项/答案** | 408- | 我们本来就有两个写入目标，现在用一个含糊的"保存" |
| 6 | **AI 工具做成可见开关清单 + 默认隐藏答案**：`读取知识库 [开] / 读取掌握度 [开] / 更新掌握度 [关]`，每行显示"上次使用"；破坏性操作需显式确认 | pocketpal-ai / anki-mcp-server | 直接解决 §3.5 的隐形能力和 §4.1#2 的"出口交给模型" |
| 7 | **考前模式的产物矩阵**：不是又一个列表，而是 `打印错题卷 / 变式题训练 / 记忆卡 / 口述复盘` 四张产物卡，打印可勾选含答案解析 | PageLM / wrong-notebook | 我们的"考前冲刺"目前只是一个权重 ramp，学生看不到收益 |
| 8 | **今日队列有上限 + "斩题/背题"降噪**：连对 2 次移出普通列表但保留长期计划；`12/20` 进度环 | 408- / shiroha-quiz | 无边界队列会让高三学生直接放弃；"这题别再出现"比"掌握度 0-100"更贴心智 |
| 9 | **分区结构化输出**：逐选项解析（每行 ✓/✗ + 展开）+ 逐步折叠讲解，每段可单独"不懂" | ArtIflow 等 | 高考选择题"哪个选项为什么错"和"哪一步卡住"是两个粒度 |
| 10 | **手势语义化取代底部按钮堆**：左滑续讲 / 左滑停住=语音追问 / 右滑=详解 / 右滑停住=本题精细追问 | ArtIflow | 做题时一手拿笔，单手操作；也顺手解决"每屏一个主按钮" |

**不要抄的**：FSRS 参数调优（高三节奏由考试日历倒推，不是纯遗忘曲线）、服务端+多用户+管理后台、
桌面 1280px+ 布局、以及几何/语音/Electron 这类重依赖。
**许可证风险**：`PageLM`/`ArtIflow`/`wttwins/wrong-notebook` 未找到可识别 LICENSE（只能借想法）；
Anki/AnkiDroid/shiroha-quiz/ChatTutor/freelingo/KnowNote 是 GPL/AGPL（抄代码会传染）。

---

## 7b. 外部对标（2026-09-23 追加）："智能体即应用"这条架构线的工业现状

> 背景：用户 2026-09-23 把 Q1 重述为「软件的核心是错题本（学习）垂直领域的智能体；除设置外整个
> 软件是它的活动空间；后端三个库（知识库 / 掌握库 / 错题库）由它灵活调用；复习只是它的一种交互，
> 与讲题、聊天无区别」。据此补做了一轮以 `gh` 为工具的架构级检索。星数与更新时间取自
> GitHub API（2026-09-23 快照），会漂移。

### 结论一：用户的"掌握库 ≈ wiki"不是比喻，是一个**已有名字的当红范式**

叫 **LLM Wiki**（Karpathy 提出）：不再每次从零 RAG，而是让模型**增量维护一份持久的、互链的
产物**。生态规模（★为 2026-09-23 快照）：

| 项目 | ★ | 定位 |
|---|---|---|
| `nashsu/llm_wiki` | 19.8k | "personal knowledge base that builds itself"，文档 → 结构化 wiki 并持续保持最新；带 Rust 后端 agent 运行时、异步人工复核队列、来源可追溯 |
| `volcengine/OpenViking` | 38.4k | "one filesystem for everything an agent knows: knowledge, memory, and skills"，`viking://` 命名空间，agent 用 `ls/tree/read/write/grep` 导航，目录自带摘要；`ov compile` 能把素材**编译成 wiki / 知识图谱 / 报告** |
| `TencentCloud/TencentDB-Agent-Memory` | 27.1k | 团队级记忆中枢，把对话/文档/代码变成四类记忆资产，**其中一类直接叫 LLM-Wiki** |
| `mem0ai/mem0` | 65.8k | 通用 agent 记忆层（drop-in memory infrastructure） |
| `vectorize-io/hindsight` / `NevaMind-AI/memU` | 25.2k / 14.4k | agent 记忆的另两条路线 |

**对我们的直接含义**：用户描述的"掌握库在与智能体的交互中不断被改写、像 wiki"是这条范式的
**垂直领域实例**，不是异想。而且它天然带着两个我们缺的东西：**可浏览/可检查**（OpenViking 的
"打开任何目录看智能体到底知道什么"）与**来源可追溯**（llm_wiki 的 source traceability）。
我们现在的 `learner_knowledge_mastery_state` 正是"黑箱式的文本进、数字出"。

### 结论二：我们手搓的东西，已经是某个行业协议的非正式实现

`ag-ui-protocol/ag-ui`（★16.0k，CopilotKit 出品）：**标准化的 agent→前端事件协议**，约 16 种标准
事件类型、传输无关（SSE / WS / webhooks）、带中间件做宽松匹配。对照我们的代码：
`ModelGatewayEvent.Progress` 帧、8 帧节流、工具轮事件、`LiveProgress` 直播文本、终态 `Completed`
——**这就是一份自研的、没有名字的 AG-UI**。

`CopilotKit/CopilotKit`（★37.5k，"The Frontend Stack for Agents & Generative UI"，含 Mobile）与
`CopilotKit/OpenBot`（★5.3k）给出了配套的产品形态。OpenBot 的定位句值得逐字读：

> "Each coworker gets a computer of its own… **Every action decided before it happens and recorded
> after**… Anything a Bot does to a computer, a file, an MCP server or a component goes through
> **one gateway that decides and records it**. That is the difference between an agent that can use
> your tools and an agent you can let near them."

—— 这正是用户要的"整个软件都是它的活动空间"所必需的**单一授权与审计网关**；也正是本仓库
已有雏形（`ModelEgressPolicy` + 派遣账本）却因"纸上门"（§2 裂缝 1）而没有兑现的东西。

### 结论三：**Generative UI 就是我们隔离掉的那套东西**——这条要改判

`vercel-labs/json-render`（★18.1k，"The Generative UI framework"）、`tambo-ai/tambo`（★11.2k）、
`thesysdev/openui`（★9.8k，"Open Standard for Generative UI"）、`CopilotKit/OpenGenerativeUI`、
`langchain-ai/deep-agents-ui`（★1.7k）共同定义的形态是：**模型不返回像素，返回声明式 spec，
由前端受约束地渲染**。对照仓库：`TutorGuiSpec` + `visualScene` allowlist（`step_flow` /
`comparison` / `evidence_chain` / `process_timeline` / `concept_map` / `formula_derivation`），
本地重建稳定 ID、校验预算、确定性渲染、拒绝任意坐标/代码/URL——**这就是一份比工业界更严格的
Generative UI 实现**。

**改判**：§6 第 3 条"删掉整块视觉栈"需要拆成两半——
- **该删的**：`core:visual-ui`（2,799 行，零生产引用，拖入 Filament/Vico）+ `core:visual-runtime`
  里只服务 2D/3D panel 的部分（`TutorVisualLayoutEngine`）。这部分与 Generative UI 无关，是
  3D 渲染引擎，属于纯死重。
- **不该删、反而要扶正的**：`TutorGuiSpec` 的**声明式 spec + 本地白名单校验 + 确定性渲染**这条
  机制。在"智能体即应用"的定义下它不是锦上添花，而是**智能体把活动空间呈现给学生的主通道**
  （替代把四个栏目的布局写死在代码里）。当前它被 `STRUCTURED_SCENE_ISOLATED = true` 关掉，
  这个开关在新定义下是正确的反面。

### 结论四：教育领域的确定性对标物存在，且可引用

- `CAHLR/OATutor`（★263，CHI'23 论文，Berkeley）：开源自适应辅导系统，**用 BKT 做技能掌握度
  估计**，纯前端可部署，带 curated 内容库；后续 PLOS ONE 论文专门报道其 **LLM 生成提示**的效果。
  这是我们"掌握度到底该由谁算、怎么验证"的**可引用外部基线**。
- 知识追踪研究线：`hcnoh/knowledge-tracing-collection-pytorch`（★193）、`chrispiech/DeepKnowledgeTracing`
  （★312）、`jilljenn/ktm`（★140）——供"本地算法是否该升级"的议题立项时引用。
- 排程库：`open-spaced-repetition/awesome-fsrs`（★700）、`fsrs-rs`（★431）、`py-fsrs`（★493）、
  `free-spaced-repetition-scheduler`（★719，DSR 模型）、`fsrs4anki-helper`（★323，含 Easy Days /
  Postpone / Advance / Load Balance —— 与"按考试日历倒推"的需求直接相关）。

### 检索方法说明（可复算）

`gh search repos "<query>" --limit 6 --json fullName,stargazersCount,description,updatedAt`，
多词 query 会被严格 AND，命中稀疏；有效做法是**单词/话题式 query** 或 `gh api repos/<owner>/<repo>/readme`
逐个取 README 核实。本轮所有 URL 均由 API 实取，未出现无法解析的项。

---

## 7c. 外部对标（2026-09-24 追加）：单一智能体面如何容纳多个功能页面，行为数据的家在哪

针对用户提出的两个问题——(i) 只有一个统一 Agent 页时，怎么把多个功能的页面插进来、怎么不让 session
乱套、怎么隔离用户行为；(ii) 智能体看会话流容易、看学生的行为数据不容易，那个模块该放哪——补做了
一轮检索。**两个问题各有对口的标准答案，且都不是自研疑难。**

### 问题 (i) 的对口答案：**能力自带界面，宿主嵌入**（MCP Apps / Apps SDK）

| 项目 | ★ | 是什么 |
|---|---|---|
| `modelcontextprotocol/ext-apps` | 2.9k | **官方规格与 SDK：MCP Apps 协议——"standard for UIs embedded AI chatbots, served by MCP servers"**。即：界面由能力（MCP server）自己提供，宿主负责嵌入与呈现 |
| `openai/openai-apps-sdk-examples` | 2.3k | OpenAI Apps SDK 的示例应用 |
| `openai/apps-sdk-ui` | 943 | Apps SDK 的 UI 组件库 |
| `mcp-use/mcp-use` | 10.7k | 全栈 MCP 框架：为 ChatGPT / Claude 开发 MCP Apps 与 MCP Servers |
| `MCPJam/inspector` | 2.2k | 调试 MCP Servers / MCP Apps / ChatGPT Apps 的平台 |
| `excalidraw/excalidraw-mcp` | 5.4k | 一个真实例子：Excalidraw 作为可嵌入能力 |

⇒ **"把功能页面插进智能体面"在工业界已经是一个协议级问题，答案是：界面是能力的属性，不是导航的节点。**
一个能力同时提供两份声明——给模型的 tool schema、给人的 UI surface——宿主只负责嵌入。这直接推翻
"为每个功能开一个入口"的做法，也给出"只凭点击随意进出"的机械实现：**进出页面 = 打开/关闭某个能力的
surface，不是切换模式**。

### 问题 (ii) 的对口答案：**双向共享状态**（shared state）

- `CopilotKit/CopilotKit`（★37.5k）README 原文列出三件套，其中一条逐字是：
  **"Shared State – A synchronized state layer that both agents and UI components can read from and write
  to in real time."** 另一条是 **"Backend Tool Rendering – Enables agents to call backend tools that
  return UI components rendered directly in the client."**
- `ag-ui-protocol/ag-ui`（★16.0k）的能力表把 **Bi-directional state synchronization** 列为跨框架特性
  （LangGraph / CrewAI / Google ADK / AWS Strands / Pydantic AI / LlamaIndex / Claude Agent SDK 均有
  `feature/shared_state` 的演示）。

⇒ **行为数据不该被塞进会话流，而应是一个双方都能读写的状态层**；智能体通过工具读写它，学生通过一个
**它的视图**读写它。会话是时序的，状态是空间的——两者正交。

### D2（后台备料）在工业界也有名字：**sleep-time compute**

`letta-ai/sleep-time-compute`（★137，配套论文）与 `singhh5050/nocturne`（"Sleep-time compute agent:
nightly memory rewrite (add/merge/reweight/drop)"）——**夜里对记忆做 add/merge/reweight/drop 的重写**，
与 D2 的"后台只做可重建的活 + 产出落待确认"是同一形态。可作 D2 的外部依据。

### 顺带订正一条旧结论

`letta-ai/letta`（★24.9k）**已归档**：源码迁到 `letta-ai/letta-code`（agent harness + 终端 UI +
App Server + **channels**）。它的形态是"**一个 agent，多个 channel**"（桌面/网页/Slack/Telegram/Discord）。
对我们是一个有用的镜像：我们是"**一个面，多个能力**"——恰好倒过来，所以不能照搬它的 channel 结构，
但可以借鉴它"agent 身份与呈现面分离"的原则。

---

## 7d. 外部证据（2026-09-24）：AI 放在"独立目的地"会被学生忽略；会话该按现场隔离

针对用户的分叉（**1 多入口 + 会话按功能隔离** vs **2 统一入口**）补做的检索。**这一节是 D-Q1F 裁定的
直接依据，故逐条标注证据等级。**

### 证据 A（强，方向一致）· 独立聊天入口的采用率极低

- **Khan Academy 第一方数据**：仅有 **~15%** 有权限的学生持续使用其附属聊天机器人。官方复盘后
  重做了 Khanmigo：**把它嵌进练习流程**，在作业/做题时可见、**主动提出帮助**而不是等学生问对问题、
  区分**提交前（轻提示）与提交后（更直接讲解）**、按"初次学习 vs 复习"调整支持强度、注入技能掌握与
  先修数据。〔来源：Khan Academy 产品复盘，**规范 URL 未取到**（搜索层未返回链接），标记待核〕
- **Dartmouth/Phosphor 2026 课堂部署**：一个 RAG 聊天侧栏——"每个 AI 教育 pitch deck 都主推的功能"——
  整个学期 **143 名学生只产生 72 次查询，仅 14 人用过一次以上**；学生说直接用通用大模型更快。
  同一课程里**胜出的功能都是"嵌进阅读工作流"的**：交错累计模块复习（90% 通过线、不限重试）让通过
  全部三轮的学生期末高 **7.1 分（d = 0.66）**；把平台做成教材的可选替代时使用率 **90.2%**。
  〔来源同上，**待核**〕
- **证据等级说明**：Dartmouth 那次是**单一课程、选择性学校、观察性、非随机**，数字只能当方向；
  Khanmigo 的 15% 是第一方数据。**两者方向一致，但都不是可外推的定量结论。**

### 证据 B（强，机制层面）· 长上下文会自我污染，且"开新会话"是最有效的缓解

- **Chroma Research《Context Rot》**（Hong / Troynikov / Huber, 2025，18 个模型、约 194,480 次调用）：
  在任务难度固定的前提下，**准确率随输入长度单调下降**，即使输入远未触达宣称的上下文窗口。
  退化**非均匀**：干扰项与目标语义越像、干扰越多，掉得越快；信息位置有影响（靠前最好）；
  且**指令类内容比知识类退化更快**。
  报告明确把 **"Fresh sessions / subagent spawning"列为最有效的缓解手段**。
  → 复算工具：<https://github.com/chroma-core/context-rot>，报告：<https://research.trychroma.com/context-rot>
- **Lost in the Middle**（Stanford）：中部位置准确率下降 20–25pp。<https://github.com/nelson-liu/lost-in-the-middle>
- **LongMemEval**：<https://arxiv.org/abs/2410.10813>

**对我们的双重重含义**：一是 BYOK 下学生**按 token 付费**，单一长会话每轮成本递增；二是**会话越长、
模型对当前这道题越不敏感**——而讲题恰好依赖"当前题"的精确归因（D1 要求会话能映射到原子知识点）。

### 证据 C（强，产品侧）· 连 OpenAI 都要给统一聊天补上"边界"

**ChatGPT Projects**（2024-12-13 发布，官方称"highly requested"）：动因逐条是——免费用户**没有任何
组织手段**、左侧栏自动堆满、只能在杂乱列表里滚；官方原话是"很多事**超出单次会话**……没有 Projects 时
**上下文会散落**，逼用户重复上传文件和重复指令"；而 Projects 提供 **project-only memory** 来控制
**不同工作区之间的边界**（Anthropic 的 Claude Projects 更早就有了）。
〔来源：OpenAI 官方说明与多家报道，**规范 URL 未取到**，标记待核〕

⇒ **"一个大统一会话"不是终局形态；行业头部在统一聊天之上又加回了范围与边界。**

### 证据 D（强，随机对照）· 让模型"替学生做"会减分，让模型"评判学生自己做的"才加分

Bastani et al. (2025)，约 1,000 名学生随机试验：无限制的 GPT-4 访问让学生在**撤掉工具后表现更差**。
结论分野是：**替学生做事的模型减损学习；评判学生自己作品的模型增益学习。**
〔来源未取到规范 URL，标记待核〕
→ 这与本仓库"掌握度只由合格作答事实投影"（D1 的继承不变量）**相互独立地一致**。

### 证据 E（中，企业侧）· 会话隔离是默认的安全形态

`gebruder/wirken`（★183，企业 agent 网关）把 **per-channel isolation + per-session tamper-evident
audit log** 列为产品特性。企业的"每个渠道/会话隔离"与本产品的"不同功能/对象之间不串"是同一诉求。
<https://github.com/gebruder/wirken>

### 综合（写进 D-Q1F 的判定）

**"统一入口"胜出的那一半是"一个引擎"，"分开入口"胜出的那一半是"出现在工作现场 + 会话按现场隔离"。**
把 AI 做成一个要去的目的地（不论那个目的地是一个 tab 还是一个统一页），是采用率的头号杀手；
而把它的会话汇成一条全局流，会让上下文自我污染并破坏"当前题"的归因。
**两者都不是对方的解药：真正的解药是"同一个引擎，出现在每个工作现场，会话跟着现场走"。**

---

## 7e. 外部对标（2026-09-25）：工具调用的"打磨"基准 = coding agent 的权限与可见性模型

> 起因：用户裁定 K2a（无题轮工具返回空范围不报错）时补充"工具调用这个场景还要再好好打磨，
> 用工具这一块其实和 coding agent 差不多，参考一下它的方式"。

### 基准模型：Claude Code 的 allow / ask / deny + 权限模式

- **三层结果**：每条规则对一次工具调用给出 allow（自动）/ ask（询问）/ deny（拒绝）；
  **deny 赢 ask、ask 赢 allow**；评估顺序 = deny → ask → allow → 模式兜底。
- **权限模式（兜底档）**：`default`（只读自动）/ `acceptEdits`（读+编辑自动）/ **`plan`（只读 +
  只出计划，一切写入等批准——与我们"复习未作答只提示"同构）** / `dontAsk` / `bypassPermissions`。
- **交互三选**：Allow once / Allow always / Deny；拒绝会回传给模型。
- 来源：<https://code.claude.com/docs/en/permissions>（**规范 URL 未取到**，内容经搜索摘要核对，待核）。
- 开源参照：`sst/opencode`（★210k，MIT，"The open source coding agent"，含 plan 子代理）、
  `cline/cline`（★69k，Apache-2.0）、`block/goose`（★54.6k，Apache-2.0）——
  三者都实现了"工具调用可见 + 权限层 + 计划模式"这套组合。
- 与本产品同方向的产品形态（OpenBot ★5.3k，MIT）："Every action decided before it happens and
  recorded after…one gateway that decides and records it"（见 §7b）。

### 可移植结论（供 D-K2 的工具调用打磨）

1. **工具调用全程可见**：每次调用在会话流里一行卡（工具名 → 人话标签 → 结果摘要 → 可展开详情）。
  消灭我们现状里的"工具结果正文从不进 UI"（研究报告 §4.4 代理 C 发现）。
2. **写工具走 ask**（确认卡），读工具走 allow（自动 + 可见）；**被门拒绝的写入必须可见理由**
  （对应 `learner_chat_evidence` 的 rejected 观察行，把语义渲染出来）。
3. **会话级权限模式**：我们已裁定的"复习未作答只能提示、判定后放开"就是 coding agent 的
  **plan mode → normal mode** 的同构物——正式化为"提示模式 / 正常模式"两个会话级模式。
4. **聚合折叠**：一轮里多个工具调用折叠成一个"本轮查阅"条目（现有"正在查阅…/已查阅…"两行
  标签的升级），不打断对话流；这保留了我们"流式不阻塞、64 事件上限"的既有纪律。

---

## 8. 单点最重的发现（Top 12，跨维度排序）

1. **两套互斥的复习世界观同时在产线上**（§2 裂缝 2）——它决定了产品是什么，不是代码怎么改。
2. **首次安装 43.7 s + 空库复习首页死路**（§3.1）——第一分钟体验不通。
3. **"确认加入错题本"不落库、只是跳转**（§4.1#1）——学生在最关键的信任动作上被骗一次。
4. **披露与契约在产线主路径上是"纸上门"**（§2 裂缝 1）——`agentConsentGranted=true` 短路了 Respond/Plan 的全部披露校验。
5. **没有模型时整条复习队列不推进**（§3.1，知情取舍）——复习是产品的核心承诺。
6. **一大家族生产不可达的代码**：评估式讲题支路、`VerifiedTeachingArtifact`、知识研究链、自适应选择器、Filament 3D（§4.3）。
7. **基础出口的可见性由模型输出决定**（§4.1#2）。
8. **提示/重做证据通道产线不存在**，但内核为它写了四条分支与测试（§3.2）。
9. **`ReviewReason` 内核算好、UI 不显示**（§4.4）——"为什么是这些题"是产品差异化的地方，也是 IA 的承诺。
10. **五科无 KB 且界面零提示；四科检索 Recall@5=0.54 而金标测试不断言阈值**（§4.5）。
11. **两台调度器 + 同值常量多份 + 同一规则两处实现**（§4.2）——回归改一处不会让另一处红。
12. **文档断层使"验收"失去意义**：至少 15 处文档与代码矛盾（§2 裂缝 3、§5.3）。

---

## 9. 未验证与边界（不得当作已通过）

- 本报告**没有跑任何构建、测试或真机**。所有"不可达"是静态读码 + grep 结论（少数几条我亲手核实），
  未做运行期验证。翻转开关或注入夹具后，其中一部分会变可达。
- Route A（原生 tools）与流式冲突（§4.1#7）是结构性推断，真实端点从未验证。
- KB 载荷与首装耗时来自 `.jez/artifacts/r4a-startup-baseline-2026-09-22.md`（模拟器、debug 包），
  不是真机、不是 release。
- 20k 引用点墙钟门、仪器化搬家用例、Baseline Profile、真实 Provider 质量评测：UNVERIFIED（沿用历史结论）。
- `RestoreRecoveryCoordinator` 的"零引用"是在**当前工作树**的结论；`.worktrees/` 下有副本，
  若另一会话正在合并，结论可能过期。
- 并行探查代理的发现我抽查了 4 条（工件不可达、`teachingArtifact` 恒 null、披露短路、
  无模型复习不推进）均成立；其余未逐条复核，均带 `file:line` 可复查。
- §4.6 的低置信度候选**不构成结论**，需要逐个定案：`ModelEvaluation`、`MasteryDecisionPolicy`、
  `RtBaseline`、`LibrarySort`、`LegacyModelTaskRequest`、`UserRecoveryAction`、
  `SanitizedQuestionDocument`、`ErrorBookEntryStatus`、`BracketType`、`TutorMotionState` 等——
  它们只在定义文件内部出现，可能是文件内自用而非死重。定案方法：对每个名字跑
  `grep -rn "<name>" --include=*.kt`，确认命中是否全在定义文件内。
- `feature/capture` 的 `CaptureScreenPolicy.kt`(343 行) 只完整核验了一个函数
  （`captureRecoveryApplication` 是死的）；其余顶层 `internal fun` 未逐函数做生产调用点计数。
- capture 修订号 CAS 是否真的落在 SQL `WHERE` 上（而非参数回传）未定案：`PendingCaptureDao` 里
  grep 不到 `expected_revision_number` 的 WHERE 子句，需看 `ProblemDraftTransactionDao`。

---

## 10. 决策树（Round 1 frontier）

```
Q1 这次重构的目标函数
├── Q2 复习的产品主轴（保留"生成知识点选择题"吗）   ← 与 Q1 并列，是产品真值问题
├── Q3 知识库载荷形态（预置库 / 后台装 / 分科懒装）
├── Q4 数据层重置到单一基线（0 真实用户前提下）
├── Q5 不可达/死重代码的处置尺子（删 vs 留隔离开关）
├── Q6 "纸上门"怎么处理（接线强制 vs 降级为测试锁）
└── Q7 术语与"当前产品真值"的载体

Round 2（依赖 Q1/Q2，本轮不问）
├── 页面编排收敛：24 route 的最终表、我的设置页合并、复习首页重排
├── 未接线能力补齐清单：撤销 / 再练 / 求助 / 待处理工作台 / 分享 / 深链 / 纠正
├── 应用层归属：feature 的 `*Commands` 是不是应用层、要不要真的 use case 层
├── 知识库对学生可见性：覆盖率、待完善、双解析分栏要不要做
└── 检索质量路线：词面 0.54 的处理（向量议题已开）
```
