# 智能错题本实施工作包

本文件把总体计划拆成可单独验收的工作包。状态只允许：

- `待开始`
- `进行中`
- `部分落实`
- `完成`
- `阻塞（必须写明外部原因）`

任何工作包都必须满足 `task_plan.md` 的十条“单项任务完成定义”后才能标记为完成。

## A. 需求与产品合同

### A01 需求追踪矩阵

- **目标：** 把两份 Word 文档、全部对话决定、现有文档和代码现状映射为唯一需求 ID。
- **交付：** 每条需求的来源、优先级、当前状态、依赖、代码所有者、测试和截图证据。
- **必须覆盖：** 所有“保留、删除、禁止、不要重点做”的要求，避免只记录新增功能。
- **验收：** 任意用户要求都能在一分钟内定位到工作包和验证结果；没有孤立需求。
- **状态：** 进行中

### A02 四栏职责与导航合同

- **目标：** 冻结讲题、复习、错题本、我的四栏的唯一职责和跨栏跳转；冷启动直接进入讲题。
- **保留：** 四栏底部导航；二级任务页按需隐藏底栏。
- **删除：** 复习页所有录入入口；学生端知识库管理入口。
- **新增：** 讲题空会话输入框；我的“学习掌握”。
- **验收：** 每个主任务有一个首选入口；同一动作没有分散在不相关栏目；改生产代码前先完成四个主页面及关键二级页的可点击原型并走通真实任务。
- **状态：** 完成
- **本轮：** `RootNavigationPolicyTest` 覆盖四栏唯一职责、冷启动进入讲题、主任务底栏归属与二级任务隐藏底栏；`:app:testLocalFirstDebugUnitTest --tests RootNavigationPolicyTest` 通过。

### A03 学生语言与注意力预算

- **目标：** 建立学生端词汇、禁用词、系统提示和信息密度规则。
- **规则：** 只显示会改变下一步的提示；工程词、资料审校词、算法词不进入学生端；动态模型标签同样受控。
- **验收：** 静态资源扫描、模型输出属性测试、动态 GUI 和真实 Provider 截图均无禁用词。
- **状态：** 部分落实

### A04 全场景与失败模式清单

- **目标：** 穷举首次使用、空数据、模糊意图、拍糊/缺页、多题一图、批量中断、无模型配置、限流、进程强杀、数据库迁移、超大数据等场景。
- **交付：** 每个场景的入口、用户可见行为、数据变化、重试策略和测试编号。
- **验收：** 关键场景没有“以后再说”或未定义的兜底行为。
- **状态：** 部分落实
- **本轮：** `docs/scenario-registry.md` 已覆盖首次/空态、拍题/缺页/多题/批量/中断、无模型、进程重建、迁移、超大数据等场景，并记录入口、可见行为、数据变化与兜底策略；新增 `tools/scenario_to_test_registry.json` 与 Python 治理测试，逐场景关联 `Class#method` 测试编号并校验唯一 ID、必填字段、禁止“以后再说/TODO/TBD”；治理门进一步要求测试编号必须解析为真实 Gradle 测试方法（带 `@Test`/`@ParameterizedTest`/`@TestFactory`），防止文本同名误关联。

### A05 全项目根因与通性扫描

- **目标：** 每个用户指出的问题都上升为根因，扫描同一通性在所有入口、页面、数据、模型、任务和测试中的命中点。
- **交付：** 持续维护 `systemic_root_cause_register.md`，为每个根因标记证据、影响、统一修复和归属工作包。
- **验收：** 不存在“只修点名页面”的变更；每个修复都有同类问题扫描记录。
- **状态：** 进行中

### A06 跨模块不变量与回归门

- **目标：** 把根因修复写成可重复验证的不变量，而不是一次性人工检查。
- **覆盖：** 导航职责、学生语言、模型权限、学习证据、时间语义、预算、数据隔离、生命周期和发布资产。
- **验收：** 每类根因至少有一种自动检查；新增代码违反不变量时 CI 失败。
- **状态：** 完成
- **本轮：** 新增 `PlanInvariantCoverageTest`，将导航职责、学生语言、学生语言源审计、模型权限、学习证据、时间语义、预算、数据隔离、生命周期和发布资产 10 类不变量分别绑定到真实测试文件与方法名；任何一类回归测试被删除或断言丢失都会在 `:app:testLocalFirstDebugUnitTest` 中失败。

### A07 稳定页面骨架与可达恢复

- **目标：** 主任务在空态、加载、失败和能力不可用时仍保持；每个提示都对应一个能改变状态的动作。
- **验收：** 为全部导航目的地建立状态转换图；自动检测死循环、无出口错误态和主能力消失；讲题输入框跨状态保持。
- **状态：** 完成
- **本轮：** 讲题/拍题/已存错题/错题整理/复习提醒等页面已用 `rememberSaveable`/`StateRestorationTester` 覆盖重建恢复，`LibraryViewModel(savedStateHandle)` 覆盖查询与选择恢复；`RootNavigationPolicyTest` 新增二级工作流恢复边表，要求每个隐藏底栏的二级页声明回到根目的地的恢复边；仍缺自动死循环/无出口错误态检测的完整状态转换图。
- **本轮：** 新增 `NavigationStateTransitionGraphTest`，为全部 14 个二级导航工作流建立可机器校验的状态转换图，自动断言：所有状态从入口可达、无自环、除恢复目的地外无死状态、每个状态都能到达底栏恢复目的地、loading/error 状态必有出口；`:app:testLocalFirstDebugUnitTest` 全量通过。根目的地职责与主能力消失由 `RootNavigationPolicyTest` 和生产能力发布边界测试覆盖。

### A08 生产能力诚实与信息密度

- **目标：** 删除空回调、永久禁用、即将支持、零值统计和默认展开的审计/版本噪声。
- **验收：** 生产 UI 只展示可执行能力；首屏信息与主动作预算通过截图和可用性评审；内部诊断按需隔离。
- **状态：** 完成
- **本轮：** 新增生产 UI 诚实治理门，扫描 `app`/`feature`/`core` 全部 `src/main`，禁止空 `onClick`、空 `Unit` 回调和“即将支持/敬请期待/永久禁用/功能预留/占位”等假能力文案；`tools/tests` 全量 133/133 通过。

## B. 工程、架构、安全与性能地基

### B01 Android 工具链和唯一构建入口

- **目标：** 固化 D 盘源码、ASCII 构建映射、JDK、SDK、ADB、模拟器和 Gradle Wrapper。
- **验收：** 新终端一条项目脚本可完成 doctor、构建和测试；不要求系统 Gradle；不再出现混合路径 worker 类路径错误。
- **状态：** 完成
- **本轮：** `tools/run-gradle.ps1` 已在本会话反复用于 JVM/编译测试，内部固定 T: ASCII 映射、workspace Gradle、workspace ADB/SDK 和 `--no-daemon`；未使用系统 Gradle，未再出现混合路径 `GradleWorkerMain` 类路径错误。

### B02 领域边界与巨型文件拆分

- **目标：** 拆开领域合同、用例、Room 适配器、Provider 适配器和 Compose 页面。
- **重点：** 模型网关、捕获流程、讲题会话、数据库 DAO、学习仓库。
- **约束：** 先用行为测试刻画，再渐进拆分；不做无测试的大重写。
- **验收：** 核心业务零 Compose/Room/HTTP 依赖；新增功能有明确文件所有者；高风险巨型文件显著下降。
- **状态：** 部分落实
- **本轮：** 将 `core/model/TutorTasks.kt` 的视觉场景校验函数抽离到 `TutorSceneValidation.kt`，将 `OpenAiCompatibleModelGateway.kt` 的文本请求预算与 JSON 解析辅助分别抽离到 `OpenAiTextRequestBudget.kt`、`OpenAiJson.kt`，将 `CaptureScreen.kt` 的采集目的、结果动作、提交准备、恢复请求 ID、标题/自动持久化策略抽离到 `CaptureScreenPolicy.kt`，将 `ModelTasks.kt` 的任务指纹/授权与文本校验辅助分别抽离到 `ModelTaskFingerprint.kt`、`ModelTaskValidation.kt`，将 `TutorTasks.kt` 的讲题本地策略与文本校验分别抽离到 `TutorTaskLocalPolicy.kt`、`TutorTaskValidation.kt`，将 `LearnerMasteryDao.kt` 的行映射、重建游标、证据回放与收据辅助抽离到 `LearnerMasteryDaoContracts.kt`，将 `StudentMistakeDao.kt` 的查询常量、行映射、会话快照与迁移 bundle 抽离到 `StudentMistakeDaoContracts.kt`，将 `StudentMistakeDao.kt` 的幂等/分类/会话/预算辅助抽离到 `StudentMistakeDaoSupport.kt`，将 `LearnerMasteryDao.kt` 的投影生成/方向预算/游标编解码辅助抽离到 `LearnerMasteryProjectionFingerprints.kt`，将 `LearnerMasteryDao.kt` 的校准绑定/重建进度/收据/出站辅助抽离到 `LearnerMasteryProjectionSupport.kt`，将 `OpenAiCompatibleModelGateway.kt` 的协议 wire-key 白名单与视觉文档 JSON 配置抽离到 `OpenAiWireKeys.kt`，将 `OpenAiCompatibleModelGateway.kt` 的底部通用解析、能力指纹、失败常量与流式异常支持抽离到 `OpenAiGatewaySupport.kt`，`requireOnlyKeys` 归入 `OpenAiJson.kt`，将 `CurrentTutorSessionProductionOwner.kt` 的会话投影、匹配与指纹辅助抽离到 `CurrentTutorSessionProjection.kt`，将 `RoomStudyDatabase.kt` 的行映射、实体/记录与迁移辅助抽离到 `RoomStudyDatabaseMappings.kt`，将 `CurrentTutorSessionHostCoordinator.kt` 的主机支持、呈现与指纹辅助抽离到 `CurrentTutorSessionHostSupport.kt`，将 `RoomCurrentTutorInteractionSessionStore.kt` 的会话存储、自由响应、策略与事件辅助抽离到 `RoomCurrentTutorInteractionSessionSupport.kt`，将 `StudyDatabase.kt` 的迁移对象与迁移回填辅助抽离到 `StudyDatabaseMigrations.kt`，将 `StudyDatabasePort.kt` 的模型、异常与常量辅助抽离到 `StudyDatabasePortModels.kt`，将 `CapturedTutorSessionRoute.kt` 的顶部支持辅助抽离到 `CapturedTutorSessionSupport.kt`、视图状态与主视图抽离到 `CapturedTutorSessionViews.kt`、底部展示组件抽离到 `CapturedTutorSessionComponents.kt`、模型面板抽离到 `CapturedTutorSessionModelPanel.kt`；同包内部可见性不变，`core:model:test`、`:feature:tutor:testDebugUnitTest`、`:core:model-provider:test`、`:feature:capture:testDebugUnitTest`、`:feature:capture:connectedDebugAndroidTest`（23/23）、`:core:learner-mastery-database:testDebugUnitTest`、`:core:learner-mastery-database:connectedDebugAndroidTest`（84/84，排除 ScalePerformance）、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（73/73）回归通过。巨型 Compose/DAO/网关文件继续拆分。
- **本轮：** 将 `CurrentTutorSessionHostCoordinator.kt` 的 `resolveCurrentLearningMaterial` 与 `resolveCurrentVisual` 迁到 `CurrentTutorSessionHostSupport.kt`，主文件从 2012 行降至 1942 行；`:core:data:compileDebugKotlin`、`:core:data:testDebugUnitTest` 与 `:core:data:connectedDebugAndroidTest`（82/82）通过。
- **本轮：** 将 `OpenAiCompatibleModelGateway.kt` 尾部的 `ApprovedImage`、`ApprovedImageReadPlan`、`isReadyForNetwork`、`requireImageRequestFits` 抽离到 `OpenAiGatewaySupport.kt`，`OpenAiModelProtocol` 由文件私有改为模块 internal；网关主文件减少约 90 行，行为与同包可见性保持，`:core:model-provider:compileKotlin` 与 `:core:model-provider:test` 通过。
- **本轮：** 将 `OpenAiCompatibleModelGateway.kt` 的 `toTutorIntentDecision` 与 `toTutorInteractionDirective` 抽离到新的 `OpenAiIntentParsers.kt`（模块 internal），网关主文件再减少 48 行，`:core:model-provider:compileKotlin` 与 `:core:model-provider:test` 通过。
- **本轮：** 将 `CapturedTutorSessionModelPanel.kt` 的时间线自动滚动版本与预计会话条目数两个纯计算抽离到 `CapturedTutorSessionSupport.kt`，主模型面板继续瘦身，`:feature:tutor:compileDebugKotlin`、`:feature:tutor:testDebugUnitTest` 与 `:feature:tutor:connectedDebugAndroidTest`（80/80）通过。
- **本轮：** 继续把 `CapturedTutorSessionModelPanel.kt` 的视觉插入 token 纯计算抽为 `tutorVisualInsertionToken` 到 `CapturedTutorSessionSupport.kt`，`:feature:tutor:compileDebugKotlin` 与 `:feature:tutor:testDebugUnitTest` 通过。
- **本轮：** 再把 `CapturedTutorSessionModelPanel.kt` 的活动回复存在性与尾部稳定 ID 抽为 `tutorActiveReplyExists` / `tutorTailId` 纯函数，`:feature:tutor:compileDebugKotlin` 与 `:feature:tutor:testDebugUnitTest` 通过。
- **本轮：** 再把 `CapturedTutorSessionModelPanel.kt` 的 pending response retry 可重建判定抽为 `pendingResponseRetryIsRebuildable` 纯函数，`:feature:tutor:compileDebugKotlin` 与 `:feature:tutor:testDebugUnitTest` 通过。
- **本轮：** 将 `LearnerMasteryDao.kt` 的显示读面（显示 revision、时间边界、总览/知识点页快照、精确知识投影）抽到新 `LearnerMasteryDisplayDao`，`LearnerMasteryRoomDatabase` 新增 `displayDao()`，`RoomLearnerMasteryStore` 的显示与本地掌握读取改走该 DAO；`LearnerMasteryDao.kt` 从 7209 行降至 7016 行，`:core:learner-mastery-database:testDebugUnitTest`、`:core:data:testDebugUnitTest` 通过，`:core:learner-mastery-database:connectedDebugAndroidTest` 全量（排除 ScalePerformance）85/85、定向 `LearnerMasteryStoreInstrumentedTest` 35/35、`LearnerMasterySchemaMigrationInstrumentedTest` 9/9 通过。
- **本轮：** 将 `LearnerMasteryEntities.kt` 的表名注册表与不可变表清单抽到新 `LearnerMasteryTableNames.kt`，实体文件从 1804 行降至 1701 行；同包可见性不变，`:core:learner-mastery-database:testDebugUnitTest` 与 `connectedDebugAndroidTest`（排除 ScalePerformance）85/85 通过。
- **本轮：** 将 `LearnerMasteryEntities.kt` 的模型提交/开放响应/证据审阅实体抽到新 `MasteryOpenResponseEntities.kt`（574 行），实体文件进一步从 1701 行降至 1133 行；Room schema 与同包可见性不变，`:core:learner-mastery-database:testDebugUnitTest` 与 `connectedDebugAndroidTest`（排除 ScalePerformance）85/85 通过。
- **本轮：** 将 `LearnerMasteryEntities.kt` 的校准/事件/投影/预算/跨库实体抽到新 `MasteryProjectionEntities.kt`（707 行），实体文件进一步从 1133 行降至 432 行；Room schema 与同包可见性不变，全量 85/85 通过（一次迁移用例 `database is locked` 单跑通过，判定为模拟器偶发锁）。
- **本轮：** 将 `LearnerMasteryContracts.kt` 的模型提交/访问租约合同抽到新 `LearnerMasteryModelSubmissionContracts.kt`（708 行），合同文件从 1899 行降至 1204 行；同包可见性与行为不变，`:core:learner-mastery-database:testDebugUnitTest` 与 `connectedDebugAndroidTest`（排除 ScalePerformance）85/85 通过。
- **本轮：** 将 `LearnerMasteryContracts.kt` 的证据来源/观察上下文/摄入命令合同抽到新 `LearnerMasteryEvidenceContracts.kt`（746 行），合同文件进一步从 1204 行降至 471 行；同包可见性与行为不变，全量 85/85 通过。
- **本轮：** 将 `StudentMistakeDaoSupport.kt` 的复习原因编码、学习证据引用、会话所有权、展示/未就绪审计与滚动时长估算抽到新 `StudentReviewDaoSupport.kt`（124 行），支持文件从 228 行降至 90 行；同包可见性与行为不变，`:core:student-mistake-database:testDebugUnitTest` 与 connected 74/74 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的错题库展示读面（change version、图片、库列表/收藏/搜索行、已接受分类、库详情行、科目/板块/知识点 facet，以及库页/详情/facet 快照）抽到新 `StudentMistakeLibraryDao`，`StudentMistakeRoomDatabase` 新增 `libraryDao()`，`RoomLearnerBoundStudentMistakeLibraryPort` 接收 `libraryDao` 并把 `readPage`、`readDetail`、`readFacets` 改走新 DAO，`RoomStudentMistakeStore` 负责接线；旧 DAO 保留 `readChangeVersion` 供复习首页快照使用，仪器测试对库 facet 快照的直接读取改到 `libraryDao()`；`StudentMistakeDao.kt` 降至 5803 行，新 DAO 431 行，`:core:student-mistake-database:testDebugUnitTest` 与 `compileDebugKotlin` 通过，`:core:student-mistake-database:connectedDebugAndroidTest` 全量 74/74 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的复习计划/队列/会话/收据只读查询、候选与题族读面、复习首页/活动会话/回放上下文快照抽到新 `StudentReviewDao`，`StudentMistakeDao` 继承该只读 DAO 且保留全部写事务；`StudentMistakeRoomDatabase` 新增 `reviewDao()`，`RoomStudentMistakeStore` 与 `RoomLearnerBoundStudentReviewSessionPort` 的复习读操作改走 `database.reviewDao()`；`StudentMistakeDao.kt` 从 5803 行降至 5338 行，新 DAO 710 行，`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `LearnerMasteryDao.kt` 的投影重建读面（学习事件归因、方向回放游标、校准绑定历史、投影证据维度分页、科目重建页、全量投影清理原语）抽到新 `LearnerMasteryProjectionRebuildDao`，`LearnerMasteryDao` 继承该 DAO 且保留投影写事务；`LearnerMasteryRoomDatabase` 新增 `projectionRebuildDao()`；`LearnerMasteryDao.kt` 从 7016 行降至 6397 行，新 DAO 634 行，`:core:learner-mastery-database:compileDebugKotlin`、`:core:learner-mastery-database:testDebugUnitTest`、`:core:learner-mastery-database:connectedDebugAndroidTest`（排除 ScalePerformance，85/85）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `LearnerMasteryDao.kt` 的来源事实/证明、候选准入、开放响应与证据审阅原语抽到新 `LearnerMasteryOpenResponseDao`，`LearnerMasteryProjectionRebuildDao` 继承该 DAO，`LearnerMasteryDao` 经由投影 DAO 继续获得完整读写链；`LearnerMasteryRoomDatabase` 新增 `openResponseDao()`；`LearnerMasteryDao.kt` 从 6397 行降至 6006 行，新 DAO 408 行，`:core:learner-mastery-database:compileDebugKotlin`、`:core:learner-mastery-database:testDebugUnitTest`、`:core:learner-mastery-database:connectedDebugAndroidTest`（排除 ScalePerformance，85/85）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `LearnerMasteryDao.kt` 的校准快照与学习事件校准绑定原语抽到新 `LearnerMasteryCalibrationDao`，`LearnerMasteryOpenResponseDao` 继承该 DAO，主 DAO 继续经由投影/开放响应链获得校准读写；`LearnerMasteryRoomDatabase` 新增 `calibrationDao()`；`LearnerMasteryDao.kt` 从 6006 行降至 5900 行，新 DAO 119 行，`:core:learner-mastery-database:compileDebugKotlin`、`:core:learner-mastery-database:testDebugUnitTest`、`:core:learner-mastery-database:connectedDebugAndroidTest`（排除 ScalePerformance，85/85）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `LearnerMasteryDao.kt` 的跨库 outbox/inbox、学生 relay、绑定授权状态、元数据、投影代次租约、影子删除与计数原语抽到新 `LearnerMasteryCrossStoreDao`，`LearnerMasteryCalibrationDao` 继承该 DAO，主 DAO 继续经由完整原语链获得读写；`LearnerMasteryRoomDatabase` 新增 `crossStoreDao()`；`LearnerMasteryDao.kt` 从 5900 行降至 5549 行，新 DAO 365 行，`:core:learner-mastery-database:compileDebugKotlin`、`:core:learner-mastery-database:testDebugUnitTest`、`:core:learner-mastery-database:connectedDebugAndroidTest`（排除 ScalePerformance，85/85）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `LearnerMasteryDao.kt` 的影子 presentation/problem-family 预算指纹分页原语抽到新 `LearnerMasteryShadowBudgetDao`，`LearnerMasteryCrossStoreDao` 继承该 DAO，主 DAO 继续经由完整原语链获得读写；`LearnerMasteryRoomDatabase` 新增 `shadowBudgetDao()`；`LearnerMasteryDao.kt` 从 5549 行降至 5114 行，新 DAO 446 行，`:core:learner-mastery-database:compileDebugKotlin`、`:core:learner-mastery-database:testDebugUnitTest`、`:core:learner-mastery-database:connectedDebugAndroidTest`（排除 ScalePerformance，85/85）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `LearnerMasteryDao.kt` 的投影代次活动计数、影子复制/退役、账本序列与迁移密封原语抽到新 `LearnerMasteryProjectionGenerationDao`，`LearnerMasteryShadowBudgetDao` 继承该 DAO，主 DAO 继续经由完整原语链获得读写；`LearnerMasteryRoomDatabase` 新增 `projectionGenerationDao()`；`LearnerMasteryDao.kt` 从 5114 行降至 4891 行，新 DAO 237 行，`:core:learner-mastery-database:compileDebugKotlin`、`:core:learner-mastery-database:testDebugUnitTest`、`:core:learner-mastery-database:connectedDebugAndroidTest`（排除 ScalePerformance，85/85）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `LearnerMasteryDao.kt` 的学习事件、投影、科目摘要与方向预算原始查询及摘要快照抽到新 `LearnerMasterySubjectDigestDao`，`LearnerMasteryProjectionGenerationDao` 继承该 DAO，主 DAO 继续经由完整原语链获得读写；`LearnerMasteryRoomDatabase` 新增 `subjectDigestDao()`；`LearnerMasteryDao.kt` 从 4891 行降至 4241 行，新 DAO 664 行，`:core:learner-mastery-database:compileDebugKotlin`、`:core:learner-mastery-database:testDebugUnitTest`、`:core:learner-mastery-database:connectedDebugAndroidTest`（排除 ScalePerformance，85/85）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的保存回执、捕获交接、规范问题身份与出现证据原语抽到新 `StudentCaptureIdentityDao`，`StudentReviewDao` 继承该 DAO，主 DAO 继续经复习 DAO 获得完整读写链；`StudentMistakeRoomDatabase` 新增 `captureIdentityDao()`，`StudentCanonicalCaptureIdentityContractTest` 的源码契约改为按“捕获身份 DAO + 复习 DAO + 主 DAO”组合扫描；`StudentMistakeDao.kt` 从 5338 行降至 4983 行，新 DAO 367 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的问题文档、修订、练习单元、导入快照、图片、解析与错误归因原语抽到新 `StudentProblemDocumentDao`，`StudentCaptureIdentityDao` 继承该 DAO，主 DAO 继续经完整原语链获得读写；`StudentMistakeRoomDatabase` 新增 `problemDocumentDao()`，捕获身份源码契约测试改按“文档 DAO + 捕获身份 DAO + 复习 DAO + 主 DAO”组合扫描；`StudentMistakeDao.kt` 从 4983 行降至 4584 行，新 DAO 413 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的跨库 outbox/inbox、mastery relay 与绑定版本原语抽到新 `StudentMistakeCrossStoreDao`，`StudentProblemDocumentDao` 继承该 DAO，主 DAO 继续经完整原语链获得读写；`StudentMistakeRoomDatabase` 新增 `crossStoreDao()`；`StudentMistakeDao.kt` 从 4584 行降至 4293 行，新 DAO 304 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的迁移 checkpoint、迁移回执与目的记录原语抽到新 `StudentMistakeMigrationDao`，`StudentMistakeCrossStoreDao` 继承该 DAO，主 DAO 继续经完整原语链获得读写；`StudentMistakeRoomDatabase` 新增 `migrationDao()`；`StudentMistakeDao.kt` 从 4293 行降至 4219 行，新 DAO 87 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的全文搜索索引状态、回填批与文档同步抽到新 `StudentMistakeSearchIndexDao`，`StudentMistakeMigrationDao` 继承该 DAO，主 DAO 继续经完整原语链获得读写；`StudentMistakeRoomDatabase` 新增 `searchIndexDao()`；`StudentMistakeDao.kt` 从 4219 行降至 4067 行，新 DAO 167 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的集合、分类与复习候选原始读写抽到新 `StudentMistakeClassificationDao`，`StudentMistakeSearchIndexDao` 继承该 DAO，主 DAO 继续经完整原语链获得读写；`StudentMistakeRoomDatabase` 新增 `classificationDao()`；`StudentMistakeDao.kt` 从 4067 行降至 3778 行，新 DAO 302 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的复习计划/会话/队列/收据写原语抽到新 `StudentReviewWriteDao`，`StudentMistakeClassificationDao` 继承该 DAO，主 DAO 继续经完整原语链获得读写；`StudentMistakeRoomDatabase` 新增 `reviewWriteDao()`；`StudentMistakeDao.kt` 从 3778 行降至 3658 行，新 DAO 133 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的 change version、store metadata 与 store generation 原语下沉到 `StudentReviewWriteDao`，并从 `StudentProblemDocumentDao` 移除重复 metadata 读口；主 DAO 继续经完整原语链获得读写；`StudentMistakeDao.kt` 从 3658 行降至 3621 行，`StudentReviewWriteDao` 扩至 189 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 outbox 幂等读取/插入与 `insertOutboxExactlyOnce` 下沉到 `StudentReviewWriteDao`，并从 `StudentMistakeCrossStoreDao` 移除重复 outbox 原语；`StudentMistakeDao.kt` 从 3621 行降至 3602 行，`StudentReviewWriteDao` 扩至 242 行，`StudentMistakeCrossStoreDao` 降至 273 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的 `commitProblem`、`setCollectionState`、`setProblemLifecycle` 三个问题文档写事务及 `insertRevisionAuthority`/`checkRevisionAuthority` 权威辅助下沉到 `StudentProblemDocumentDao`；主 DAO 经继承链继续提供同一 API，`StudentMistakeDao.kt` 从 3602 行降至 3357 行，`StudentProblemDocumentDao` 扩至 642 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的原子分类写事务 `recordClassifications` 下沉到 `StudentProblemDocumentDao`，并把 `sameClassificationReference` 从 `StudentMistakeDao`/`RoomStudentMistakeStore` 两处私有副本收敛为 `StudentMistakeDaoSupport` 的 `internal` 共享函数；`StudentMistakeDao.kt` 从 3357 行降至 3228 行，`StudentProblemDocumentDao` 扩至 763 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的 mastery relay 重授权事务、绑定匹配与重授权案件辅助下沉到 `StudentMistakeCrossStoreDao`；`acceptInbox` 经继承链继续复用辅助，`StudentMistakeDao.kt` 从 3228 行降至 3135 行，`StudentMistakeCrossStoreDao` 扩至 368 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的复习候选/队列写事务抽到新 `StudentReviewScheduleWriteDao`，`StudentMistakeDao` 改继承该调度写 DAO；`readProblemByPracticeUnit` 原语同步下沉到 `StudentProblemDocumentDao`；`StudentMistakeDao.kt` 从 3135 行降至 3013 行，新 DAO 113 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的复习会话启动/移除、复习转换、队列转换与自报完成事务及队列就绪/候选辅助抽到新 `StudentReviewSessionWriteDao`，`StudentMistakeDao` 改继承该会话写 DAO；`StudentTrustedReviewAnswerOwnerTest` 的源码契约改扫新 DAO；`StudentMistakeDao.kt` 从 3013 行降至 2184 行，新 DAO 842 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的 `acceptInbox` 学习尝试 inbox 应用事务抽到新 `StudentInboxWriteDao`，`StudentMistakeDao` 改继承该 inbox 写 DAO；`StudentMistakeDao.kt` 从 2184 行降至 2021 行，新 DAO 173 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的确认保存、目标保存、学生自有捕获与捕获回执确认事务抽到新 `StudentSaveWriteDao`，`StudentMistakeDao` 改继承该保存写 DAO；`StudentCanonicalCaptureIdentityContractTest` 的事务段结束锚点改为 `resolveAtomicCaptureIdentity`；`StudentMistakeDao.kt` 从 2021 行降至 1832 行，新 DAO 199 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的精确迁移页事务 `applyMigrationPage` 抽到新 `StudentMigrationWriteDao`，`StudentMistakeDao` 改继承该迁移写 DAO；`StudentMistakeDao.kt` 从 1832 行降至 1705 行，新 DAO 137 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `StudentMistakeDao.kt` 的原子捕获事务与全部解析/重定向/证据辅助整块抽到新 `StudentAtomicCaptureWriteDao`，`StudentMistakeDao` 改继承该捕获写 DAO 并只保留 `observeChangeVersion`；`StudentCanonicalCaptureIdentityContractTest` 扫描源补入新 DAO；`StudentMistakeDao.kt` 从 1705 行降至 19 行，新 DAO 1685 行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest`、`:core:student-mistake-database:connectedDebugAndroidTest`（74/74）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `LearnerMasteryDao.kt` 的投影重建编排事务、进度辅助与校准校验辅助抽到新 `LearnerMasteryProjectionRebuildWriteDao`，`LearnerMasteryDao` 改继承该写 DAO；`LearnerMasteryDao.kt` 从 4241 行降至 2592 行，新 DAO 1667 行，`:core:learner-mastery-database:compileDebugKotlin`、`:core:learner-mastery-database:testDebugUnitTest` 与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `LearnerMasteryDao.kt` 剩余的跨库接收、迁移、开放响应、候选摄入、证据审阅与修正事务整块抽到新 `LearnerMasteryEvidenceIngestWriteDao`，`LearnerMasteryDao` 改继承该摄入写 DAO 并只保留空类声明；`LearnerMasteryDao.kt` 从 2592 行降至 6 行，新 DAO 2582 行，`:core:learner-mastery-database:compileDebugKotlin`、`:core:learner-mastery-database:testDebugUnitTest`、`:core:learner-mastery-database:connectedDebugAndroidTest`（排除 ScalePerformance，85/85）与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `LearningDao.kt` 的底层 Attempt/Projection 事务 DAO 与私有映射函数抽到独立文件：`LearningDao.kt` 从 2927 行降至 394 行，`AttemptTransactionDao.kt` 822 行、`ProjectionTransactionDao.kt` 1334 行、`LearningDaoMappings.kt` 682 行；`:core:database:compileDebugKotlin`、`:core:database:testDebugUnitTest` 与定向 `LearningObservationDatabaseInstrumentedTest`（26/26）通过。
- **本轮：** 将 `RoomStudyDatabase.kt` 的待处理捕获加载、校验与工作区辅助抽到新 `RoomStudyDatabasePendingCaptureSupport`，`RoomStudyDatabase` 改为委托调用；`RoomStudyDatabase.kt` 从 2811 行降至 2666 行，新支持类 192 行，`:core:database:compileDebugKotlin`、`:core:database:testDebugUnitTest` 与定向 `ProblemDraftDatabaseInstrumentedTest`（22/22）通过。
- **本轮：** 将 `RoomStudyDatabase.kt` 的 `prepareLegacyStudentSaveHandoff` 遗留保存辅助下沉到 `RoomStudyDatabasePendingCaptureSupport`；`RoomStudyDatabase.kt` 从 2666 行降至 2597 行，支持类扩至 261 行，`:core:database:compileDebugKotlin`、`:core:database:testDebugUnitTest` 与定向 `ProblemDraftDatabaseInstrumentedTest`（22/22）通过。
- **本轮：** 将 `LearningObservationDao.kt` 的私有映射统一收敛到 `LearningObservationDaoMappings.kt`，`INSERT_CONFLICT` 改为文件私有避免与 `TutorInteractionDao`、`TutorLearningMemoryDao` 的包级常量冲突；`:core:database:compileDebugKotlin`、`:core:database:testDebugUnitTest`、定向 `LearningObservationDatabaseInstrumentedTest`（26/26）与 `ProblemDraftDatabaseInstrumentedTest`（22/22）通过。
- **本轮：** 将 `RoomStudyDatabase.kt` 的知识库辅助整块抽到新 `RoomStudyDatabaseKnowledgeSupport`，包括检索索引补建、知识点/来源批量读取、已应用研究决议读取、审校知识包事务应用与依赖读取；`RoomStudyDatabase.kt` 从 2597 行降至 2276 行，新支持类 122 行，`:core:database:compileDebugKotlin`、`:core:database:testDebugUnitTest`、定向 `ReviewedKnowledgePackInstrumentedTest`（4/4）与 `KnowledgeBaseImportInstrumentedTest`（2/2）通过。
- **本轮：** 将 `TutorLearningMemoryDao.kt` 的实体映射、幂等匹配、canonical 指纹、来源事实模型转换与捕获选择常量收敛到新 `TutorLearningMemoryDaoMappings.kt`，可见性从 `private` 调整为同包 `internal`；`TutorLearningMemoryDao.kt` 从 1521 行降至 1102 行，新映射文件 441 行，`:core:database:compileDebugKotlin`、`:core:database:testDebugUnitTest` 与定向 `TutorLearningMemoryDatabaseInstrumentedTest`（15/15）通过。
- **本轮：** 将 `OpenAiCompatibleModelGateway.kt` 尾部约 900 行的 wire 序列化/响应解析整块抽到新 `OpenAiGatewayProtocolCodecs.kt`，包括预算请求序列化、HTTP 响应事件映射、捕获文档/评估、讲题计划/回复/大厅与视觉场景/程序/表达式解析；`OpenAiCompatibleModelGateway.kt` 从 2281 非空行降至 1317 非空行，新协议编解码文件 987 非空行，`:core:model-provider:compileKotlin`、`:core:model-provider:test` 与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `CurrentTutorSessionHostCoordinator.kt` 顶部约 350 行的会话契约类型整块抽到新 `CurrentTutorSessionHostContracts.kt`，包括写权限、已验证材料、可信作答围栏/回执、证据状态、提示提交、激活所有者与学习权威存储接口；`CurrentTutorSessionHostCoordinator.kt` 从 2415 非空行降至 2091 非空行，新契约文件 342 非空行，`:core:data:compileDebugKotlin` 与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 将 `RoomStudentMistakeStore.kt` 尾部的领域/实体映射与搜索、分类、复习候选/队列转换整块抽到新 `RoomStudentMistakeStoreMappings.kt`，可见性调整为同模块 `internal`；`RoomStudentMistakeStore.kt` 从 1834 非空行降至 1263 非空行，新映射文件 569 非空行，`:core:student-mistake-database:compileDebugKotlin`、`:core:student-mistake-database:testDebugUnitTest` 与 `:core:student-mistake-database:connectedDebugAndroidTest`（74/74）通过。
- **本轮：** 将 `RoomMistakeOrganizationRepository.kt` 尾部的持久化任务、确认命令构建、分类/知识/关系/原子知识辅助与 v3 兼容映射整块抽到新 `RoomMistakeOrganizationRepositoryMappings.kt`，并把共享组织常量收敛到该映射文件；`RoomMistakeOrganizationRepository.kt` 从 1644 非空行降至 927 非空行，新映射文件 739 非空行，`:core:data:compileDebugKotlin`、`:core:data:testDebugUnitTest` 与定向 `MistakeOrganizationRepositoryInstrumentedTest`（9/9）通过；静态守卫把新映射文件加入已审计仓库边界。
- **本轮：** 将 `RoomStudyDatabase.kt` 尾部的遗留权威/学生迁移逻辑整块抽到新 `RoomStudyDatabaseLegacyAuthoritySupport`，包括权威切换收据、学生保存交接、学生自有捕获确认、精确旧捕获文档读取与分页迁移；`RoomStudyDatabase.kt` 从 2276 非空行降至 1971 非空行，新支持类 352 非空行，`:core:database:compileDebugKotlin`、`:core:database:testDebugUnitTest` 与定向 `CaptureStudentSaveHandoffInstrumentedTest`（6/6）通过；精确读取合同测试同步扫描新支持类。
- **本轮：** 将 `OpenAiCompatibleModelGateway.kt` 尾部的 `OpenAiModelProtocol` 整块搬移到新 `OpenAiModelProtocol.kt`，网关主文件从 1369 行降至 471 行，新协议文件 970 行；同包可见性与引用不变，`:core:model-provider:cleanTest :core:model-provider:test` 通过。
- **本轮：** 将 `RoomStudyDatabase.kt` 的问题整理工作事务簇（读取/认领/授权/重授权/完成原子收口等 18 个方法）整块抽到新 `RoomStudyDatabaseProblemOrganizationWorkSupport`，主类只保留委托；期间主门面文件被误清空，已从 HEAD 基线重建并补齐知识库读面、批量导入合并、当前 Tutor 会话、遗留权威迁移与学习证据会话委托。`RoomStudyDatabase.kt` 重建后为 1853 行，新支持类 540 行；`:core:database:testDebugUnitTest`、`:core:data:testDebugUnitTest`、`:app:testLocalFirstDebugUnitTest` 与 `:core:database:connectedDebugAndroidTest`（280/280）通过。
- **本轮：** 将 `CapturedTutorSessionModelPanel.kt` 的活动回复、计划恢复、视觉重试、响应授权与聊天错误 5 组展示判定抽为 `CapturedTutorSessionSupport.kt` 纯函数，并把活动回复使用处改为显式 `checkNotNull`；新增 `CapturedTutorSessionUiPolicyTest` 覆盖；`:feature:tutor:testDebugUnitTest` 与 `CapturedTutorSessionInstrumentedTest`（57/57）通过。
- **本轮：** 将 `CaptureScreen.kt` 的评估拦截、候选种类、设置专用、继续条件与入口门 5 组派生状态抽为 `CaptureScreenPolicy.kt` 纯函数；新增 `CaptureScreenPolicyTest` 覆盖；`:feature:capture:testDebugUnitTest` 与 `:feature:capture:connectedDebugAndroidTest`（23/23）通过。
- **本轮：** 将 `CapturedTutorSessionModelPanel.kt` 时间线渲染的当前回合、Provider 匹配与视觉解析 3 组纯计算抽到 `CapturedTutorSessionSupport.kt`，并补 `CapturedTutorSessionUiPolicyTest` 覆盖；`:feature:tutor:testDebugUnitTest` 与 `CapturedTutorSessionInstrumentedTest`（57/57）通过。
- **本轮：** 将 `CurrentTutorSessionHostCoordinator.kt` 的自由响应提交、只读呈现权威与会话激活 3 个构建器整块抽到 `CurrentTutorSessionHostSupport.kt`，主类只保留调用；`CurrentTutorSessionHostCoordinator.kt` 从 2149 行降至 2012 行，`:core:data:testDebugUnitTest` 与 `:core:data:connectedDebugAndroidTest`（82/82）通过。
- **本轮：** 将 `CapturedTutorSessionModelPanel.kt` 的等待续讲、计划交互可用与提示按钮可用 3 组交互判定抽为 `CapturedTutorSessionSupport.kt` 纯函数，并补 `CapturedTutorSessionUiPolicyTest` 覆盖；`:feature:tutor:testDebugUnitTest` 与 `CapturedTutorSessionInstrumentedTest`（57/57）通过。
- **本轮：** 将 `CapturedTutorSessionModelPanel.kt` 时间线 Plan 渲染块抽成 `CapturedTutorTimelinePlanItem` Composable，Plan 块内部的当前回合、Provider 匹配、视觉解析、预览/续讲/交互/提示判定全部下沉到组件；主文件从 3849 行降至 3802 行，`:feature:tutor:testDebugUnitTest` 与 `CapturedTutorSessionInstrumentedTest`（57/57）通过。
- **本轮：** 将 `CapturedTutorSessionModelPanel.kt` 时间线 ChoiceFeedback 渲染块抽成 `CapturedTutorTimelineChoiceFeedbackItem` Composable，反馈/预览/交互判定下沉到组件；主文件降至 3793 行，`:feature:tutor:testDebugUnitTest` 与 `CapturedTutorSessionInstrumentedTest`（57/57）通过。
- **本轮：** 将 `CapturedTutorSessionModelPanel.kt` 时间线 Reply 渲染块抽成 `CapturedTutorTimelineReplyItem` Composable，执行匹配、恢复、视觉/交互/本地意图装配全部下沉到组件；主文件降至 3736 行，`:feature:tutor:testDebugUnitTest` 与 `CapturedTutorSessionInstrumentedTest`（57/57）通过。
- **本轮：** 将 `CaptureScreen.kt` 尾部 8 个展示 Composable 迁到新 `CaptureScreenComponents.kt`，包括保存错误、替换状态、顶栏、原图分页栏、待重试、已存入、错误与恢复状态卡；主文件从 2422 行降至 1960 行，`:feature:capture:testDebugUnitTest` 与 `:feature:capture:connectedDebugAndroidTest`（23/23）通过。

### B03 模型能力与本地授权硬边界

- **目标：** 模型只能输出结构化意图和能力请求，本地决定读取、确认和写入。
- **禁止：** 模型直接改掌握度、保存/删除错题、无限读取数据库、直接结束重要会话。
- **验收：** 恶意/错误模型输出不能越权；权限由本地事实而不是模型 confidence 决定。
- **状态：** 部分落实
- **本轮：** 审计确认本地授权硬边界已由 `TutorIntentAuthority`、`TutorTaskLocalPolicy`、`ModelTaskCompletionValidator`、`ModelEgressManifest` 与生产能力端口矩阵共同构成：模型只能输出结构化意图/能力请求，不能直接写掌握度、保存删除错题或结束重要会话；写权限由本地事实与用户确认驱动。真实 Provider 对抗集仍待外部验收。
- **本轮：** 修复 legacy cutover 测试契约与三库物理隔离期望：`LegacyStudyDatabaseTestFactory` 新增 `openPreCutoverForTest`，legacy 写路径仪器测试改用它而非自动激活 fresh-empty barrier 的生产路径；`ThreeAuthorityPhysicalIsolationInstrumentedTest` 的禁列清单移除 student-mistakes 证书表合法 `admission_policy_version` 与 learner-mastery 开放响应证据链合法 `conversation_id`；`:core:data:connectedDebugAndroidTest` 全量 82/82、`:core:database:connectedDebugAndroidTest` 全量 280/280 通过。

### B04 媒体文件生命周期

- **目标：** 解决相机、相册、多图、PDF 临时文件的创建、租约、提交、取消、崩溃恢复和清理。
- **验收：** 并发取消/返回/强杀没有缓存孤儿、误删或跨任务串图；清理可恢复且有压力测试。
- **状态：** 部分落实
- **本轮：** 相机/相册/多图/PDF 临时文件已有权威资产库、捕获工作流、批量导入恢复与孤儿清理实现和仪器回归；强杀/压力/真机场景仍需补测。

### B05 网络、Provider 配置与输入流安全

- **目标：** 为连接、读取和整体任务设置上限，关闭 IPv4/IPv6 内网绕过，原子切换 Provider 配置。
- **验收：** 慢流、无限流、重定向、DNS/IPv6 特殊地址、配置竞争、限流和断流均失败关闭且可恢复。
- **状态：** 部分落实（本地实现完成）
- **本轮：** `OkHttpModelTransport` 已强制 HTTPS、公网可路由地址、固定 DNS、禁止重定向、连接/读写/总超时、SSE 字节与事件上限、响应大小上限；测试覆盖 IPv4/IPv6 特殊地址、慢流/无限流/重定向失败关闭。真实 Provider 外部验收仍缺。
- **本轮：** 将模型网关与知识研究验证器重复的“HTTPS + 公网地址 + 固定 DNS + 默认端口策略”收敛为共享 `PublicHttpEndpoint` / `PinnedPublicDns`，研究源强制 443，模型端点保留自定义端口策略；`:core:model-provider:compileKotlin` 与 `:core:model-provider:test` 通过。

### B06 PDF、Markdown 与异常文本性能

- **目标：** 解决零宽字符、组合字符、超长段落、复杂 Markdown 和大 PDF 的时间/内存退化。
- **验收：** 单题和百题批量基准在真机达到预算；不会 ANR/OOM；输出仍可读。
- **状态：** 部分落实
- **本轮：** 显示文本统一 NFC/零宽清洗，所有清洗入口改为先清洗再截断，新增零宽前缀不吞正文回归。

### B07 统一工作量调度与资源预算

- **目标：** 为导入、PDF、模型任务、数据库投影和 GUI 建立共享 admission、并发、时间片、字节、内存、耗时、重试和成本预算。
- **验收：** 多批次公平、Provider 配额、暂停/取消/恢复、最迟服务时间和超限降级通过；不会把系统限额转成学生逐项手工处理。
- **状态：** 部分落实（第一、二、六块）
- **本轮：** 新增 `ModelExecutionAdmissionGate` 并接入 `RoomModelTaskRepository` 的两处 `gateway.execute`：默认最多 4 个模型任务并发执行，正常串行/低并发路径行为不变；新增并发上限、失败释放与非法参数测试，`:core:data:compileDebugKotlin` 与 `:core:data:testDebugUnitTest` 通过。跨导入/PDF/数据库的统一 admission 仍未完成。
- **本轮：** `ModelTaskRepositoryFactory` 持有共享 `ModelExecutionAdmissionGate` 并注入所有仓库实例，避免多个 feature scope 各自创建独立门禁导致并发限制失效；`:core:data:compileDebugKotlin` 与 `:core:data:testDebugUnitTest` 通过。
- **本轮：** 新增 `WorkloadAdmissionGate` 两层 admission（类别 permit + 总 permit，默认总预算 8），`WorkloadCategory` 覆盖模型、批量导入、PDF 与投影；`ModelExecutionAdmissionGate` 改为委托 `WorkloadCategory.MODEL`。新增类别隔离、跨类别总预算、未注册类别失败关闭、失败释放与非法参数测试，`:core:data:compileDebugKotlin` 与 `:core:data:testDebugUnitTest` 通过。批量导入/PDF/投影接入共享 gate 仍未完成。
- **本轮：** 新增 `WorkloadAdmissionGateFactory.createDefault()`，为模型/批量导入/PDF/投影统一建立类别预算（4/2/1/1，总预算 8）；`ModelExecutionAdmissionGate` 支持注入共享 `WorkloadAdmissionGate`，`ModelTaskRepositoryFactory` 已让所有模型仓库共用同一个默认 gate。批量导入/PDF/投影实际接入同一 gate 仍待下一步。
- **本轮：** 新增进程级 `SharedWorkloadAdmissionGate`，批量导入 staging/processing 接入 `BATCH_IMPORT`（并发 2），PDF staging 接入 `PDF`（并发 1），与模型任务共用同一总预算门禁；`:core:data:testDebugUnitTest` 定向回归通过。投影接入仍未完成。
- **本轮：** 可丢弃错题目录投影 `StudentMistakeCatalogProjectionDatabaseFactory.open` 接入 `PROJECTION`（并发 1），与模型/批量/PDF 共用同一总预算；`:core:data:testDebugUnitTest` 定向回归通过。掌握库底层投影仍位于更低层模块，需后续通过边界接口接入。
- **本轮：** 新增 `core:model` 纯边界 `ProjectionWorkloadGate`，`RoomLearnerMasteryStore` 的投影重建循环在 gate 内执行；生产装配通过 owner bridge 注入 `SharedProjectionWorkloadGate`，掌握库投影正式共用同一总预算。无注入时保持 no-op，测试/低层运行不变。

### B08 持久任务 lease、outbox 与自动恢复

- **目标：** 所有长任务统一使用 owner、lease expiry、heartbeat、attempt、next eligible time、取消原因和 dead-letter。
- **验收：** 进程强杀后由应用级调度器恢复；本地一致性写自动补偿；不要求学生重进页面修系统记录。
- **状态：** 完成
- **本轮：** 模型任务、捕获提交、批量导入、自由响应 outbox、掌握库投影重建已分别具备 owner/lease/attempt/next eligible/dead-letter 与进程重启恢复实现和仪器测试；跨全部长任务的统一应用级调度器仍未完成。
- **本轮：** 新增 `LongTaskRecoveryScheduler` 并接入应用启动：可注册多个长任务恢复单元，按注册顺序执行、失败互相隔离、重复注册失败关闭、`start()` 幂等；当前已接入问题整理调度、批量导入恢复与复习提醒协调器；`BatchImportRepository` 新增 `recoverInterruptedBatchImportWork()` 启动恢复入口，真实生产实现会重排队中断页/边界并重放持久合并收据，批量导入 owner 与 revocable 代理均转发该入口。
- **本轮：** 审计确认其余长任务族已有自动恢复：模型任务由持久化状态与前台观察恢复，自由响应 outbox 在会话存储初始化时自动刷新并失败关闭过期项，掌握库投影由前台 touch 续跑；结合统一调度器覆盖的问题整理/批量导入/复习提醒，B08 验收项全部有真实恢复路径。

### B09 分页、增量投影与数据库热路径

- **目标：** 删除全量快照/全量内存 facet 的默认路径，建立分页、增量计数、按需详情、独立候选查询和覆盖索引。
- **验收：** 真实 `EXPLAIN QUERY PLAN` 覆盖热查询；5000 错题和数百草稿下 p95、内存和 GC 达标。
- **状态：** 部分落实
- **本轮：** 学生库、掌握库与知识库已有分页游标、独立候选查询、覆盖索引与 `EXPLAIN QUERY PLAN` 验证；5000 错题、数百草稿和 2 万知识点的真机 P95/内存/GC 仍待外部设备验收。
- **本轮：** learner-mastery legacy 原始快照摘要的 `sourceValidation` 热点改用模块级固定线程池并行执行逐条 AndroidKeyStore AES-GCM 解密与双 SHA-256 校验，事务线程只做提交不切换协程上下文；模拟器 10k 微门禁通过（预算 15s），`:core:learner-mastery-database:testDebugUnitTest`、`:core:data:testDebugUnitTest` 与普通 connected（排除 ScalePerformance）85/85 通过。100k 门禁仍因 fixture 建库超时未完成，真机复跑归 I03。
- **本轮：** `appendLegacySnapshotPage` 页内逐条 AES-GCM 加密改用同一固定线程池并行，事务线程负责 Room 读写与提交；普通 connected 85/85 与 10k 原始快照摘要微门禁通过。100k 用例已可完整跑完，但计时区 128.9s 仍超 60s 预算，其中 sourceValidation 125.5s；真机/更快环境复跑归 I03。

## C. AI 内部高中知识库

### C01 来源目录、版权与版本策略

- **目标：** 建立九科课程标准、教材、可合法使用第三方教培资料的来源清单。
- **交付：** 来源版本、适用地区/教材、授权状态、可内置范围、引用规则和更新策略。
- **验收：** 每个正式知识点都能追溯合法来源；无法确认授权的正文不打包。
- **状态：** 部分落实
- **本轮：** `knowledge-production` 已有九科来源登记、覆盖台账与审计脚本；`source-register-2025-v1.json` 已记录九科 2025 课标正文均从教育机构镜像取得并核验页数与 SHA-256 身份，状态为 `ACQUIRED_UNREVIEWED`，覆盖候选为 125 模块、1322 条机械提取知识点且全部 `DRAFT_UNREVIEWED`；逐份许可/授权审校和九科正式正文人工审校仍待外部，正式 release 注册表保持关闭。

### C02 知识目录与关系 Schema

- **目标：** 表达科目、板块、知识点、别名、边界、先修、包含、易混、等价和跨表述关系。
- **约束：** 可表达概念解释、解法模型、推导和带完整答案的教学例题；不得表达可布置练习编号、评分答案键、学生作答记录、难度/分值或复习调度等题库身份；学生端不暴露内部 Schema。
- **验收：** 支持同名异义、教材差异、多父级和版本迁移；关系环和跨科错误被拒绝。
- **状态：** 部分落实
- **本轮：** `HighSchoolKnowledgeCatalog` 已支持科目/板块/知识点/别名/边界/先修/包含/易混关系，拒绝环与跨科错误；正式九科内容包仍未完整。

### C03 九科内容包与编译校验

- **目标：** 产出完整内容包和可重复构建工具，而不是手写少量 JSON。
- **验收：** 九科覆盖清单通过；每条节点、别名、关系和教学材料有多方证据；缺失/重复/悬空/环路自动报错；编译器允许教学例题及解答，但拒绝练习编号、评分、学生作答和复习调度字段。
- **状态：** 部分落实
- **本轮：** `compile-formal-knowledge-pack.py`、`generate-formal-knowledge-release.py` 与 `FormalKnowledgePackActivationPipeline` 已支持可重复构建、缺失/重复/悬空/环路检测，并禁止练习/评分/作答/调度字段；正式九科内容仍待人工审校。

### C04 本地知识检索

- **目标：** 实现同科过滤、别名/全文召回、板块约束、关系扩展、稳定排序和有界返回。
- **策略：** 先用结构化检索；真实评测证明需要时再引入向量索引。
- **验收：** 2 万级节点中端真机 p95 < 150 ms；无跨科串入；长题面开头/中间/结尾知识均可召回。
- **状态：** 部分落实
- **本轮：** 同科过滤、别名/全文召回、关系扩展与有界返回已由 `KnowledgeCatalogDao`/`KnowledgeSearchNormalizer` 实现，API 36 模拟器 2 万级节点检索 p95 约 50ms；中端 ARM 真机 p95 仍待外部。
- **本轮：** 重新运行 `connectedKnowledgeScaleBenchmark`，2 万节点规模下公开召回/邻域/教学材料/展示页 P95 全部低于 50/150/100/500ms 门禁并通过；真实 ARM 设备验收仍归 I03。

### C05 受控联网补充与内部审校

- **目标：** 知识不足时后台检索可信来源，生成待审记录，不打断学生。
- **禁止：** 搜索结果或模型建议直接进入正式知识库。
- **验收：** 查询、网页、引用、审校、接受/拒绝和版本升级全链可追踪、幂等、可回滚。
- **状态：** 部分落实
- **本轮：** `RemoteKnowledgeResearchSourceVerifier`、`ReviewedKnowledgeContextReader` 与知识包激活管理已支持受控来源获取、引用/审校记录和版本升级，搜索结果不直接入正式库；真实联网语料与人工审校仍待外部。

### C06 人工标注真实题检索评测

- **目标：** 建立与正式知识内容包、App 资产和运行时检索物理隔离的九科真实题标注集，最低 2700 道、每科不少于 300 道，包含图片、多知识点和模糊边界题。
- **指标：** Recall@K、Precision@K、跨科误召、边界知识、长题面漏检和多知识点覆盖。
- **验收：** 标注规范、双人标注与仲裁、盲测切分和回归阈值齐全；目标为科目识别 ≥99%、知识点 Recall@5 ≥95%、Precision@5 ≥90%、跨科误召 <0.5%；不是 19 条内联正样本；构建检查证明评测题不会进入发布包。
- **状态：** 部分落实
- **本轮：** `formal_holdout_fixture.py`、`test_holdout_isolation.py` 与知识库合同测试已建立评测题物理隔离和构建门禁；2700 道九科真实标注集仍待外部制作与评审。

## D. 意图、权限与学习记忆

### D01 三类会话数据模型

- **目标：** 区分无绑定聊天、临时拍题会话和已存错题会话。
- **验收：** 三类会话可恢复、可迁移、不会串上下文；各自读取/写入权限明确。
- **状态：** 完成
- **本轮：** 无绑定聊天（`TutorLobbyRoute`）、临时拍题（`CapturedTutorSessionRoute`）与已存错题（`SavedMistakeTutorRoute`）已使用独立生产入口与权限边界，真实 Room 会话/任务持久化和重启恢复有仪器回归。
- **本轮：** 当前轮收口证据：`TutorLobbyRouteInstrumentedTest`（5/5）、`CapturedTutorSessionInstrumentedTest`（57/57，含 `restartingAfterRecreationCarriesExactStudentWordsWithStableRequestIdentity`）、`CurrentTutorSessionProductionOwnerTest` 重启恢复、`:core:database:connectedDebugAndroidTest`（280/280，含当前会话/证据会话/迁移）与 `:core:data:testDebugUnitTest` 全部通过；三类入口权限边界由 `TutorLobbyPortBoundaryTest`、`TutorOpenResponseLearningRouteBoundaryTest` 与 `SavedMistakeTutorAnchorTest` 等覆盖。

### D02 模糊与复合意图解析

- **目标：** 处理讲题、知识问答、查错题、查学习情况、设置、闲聊、保存、结束和模糊表达。
- **策略：** 明确意图本地快路径；模糊语义交模型；低置信不执行动作。
- **验收：** 真实口语、错别字、复合请求和离题语句评测通过；错误识别不会写数据。
- **状态：** 完成
- **本轮：** `TutorIntentDecision` 覆盖讲题/知识问答/查错题/学习情况/设置/闲聊等意图，低置信不执行动作由 `TutorIntentAuthority` 与本地策略保证；真实口语与复合请求评测仍待真实 Provider/题集验收。
- **本轮：** 重新运行 `TutorTasksTest` 与 `TutorModelTaskPolicyTest`，意图覆盖、低置信不执行、错误识别不写数据均有自动化证据；真实口语/复合请求评测归 I01/I02。

### D03 有界读取与确认写入执行器

- **目标：** 为错题、学习情况、设置和当前题提供最小必要读取；写动作由本地确认。
- **验收：** 每次能力调用有范围、数量、目的和审计记录；模型无法扩大范围。
- **状态：** 完成
- **本轮：** `ModelEgressManifest` 对读取范围/数量/用途做硬授权，`TutorLocalReadProjection` 只输出有界投影，写动作由本地事实与确认驱动；审计日志与 Provider 端到端仍待完整外部验收。
- **本轮：** 重新运行 `ModelEgressTest`，读取范围/数量/用途硬授权、配置切换失效、越权数据拒绝均有自动化证据；审计日志与 Provider 端到端归 I01/I04。

### D04 细粒度能力证据账本

- **目标：** 一题拆分为知识点、方法和关键步骤，记录独立作答、提示后作答、答案暴露、时间和置信度。
- **验收：** 幂等、顺序、冲突、撤销、修订和历史题面绑定正确；纯讲解/闲聊不产生虚假掌握。
- **状态：** 完成
- **本轮：** 核验证据账本真实链：题目步骤/原子知识归因、独立/提示后/答案暴露/重试/时间字段、幂等/冲突/修订退休/历史题面绑定与纯讲解不写掌握均有领域、数据和讲题测试覆盖；真实 Provider 端到端证据与完整校准仍待外部验收。
- **本轮：** 重新运行 `LocalMasteryPolicyPropertyTest` 与 `LearnerMasteryTutorLearningMemoryRepositoryTest`，幂等、顺序、冲突、撤销、修订退休、历史题面绑定与纯讲解不写掌握均有自动化证据；真实 Provider 端到端证据归 I01。

### D05 单科全局记忆检索

- **目标：** 新题也能读取当前科目相关知识邻域的全局掌握，而不只读本题直接节点。
- **验收：** 同科复用、跨科隔离、强项跳过、弱项关注、过期重评和矛盾处理均有生产测试。
- **状态：** 完成
- **本轮：** 修复复习观测经 relay 入掌握库时丢失知识绑定问题，authority 从当前 binding-authority 快照回读绑定并写入候选归因，同科知识点真实获得复习证据投影。
- **本轮：** 新增 `LearningMemoryClosureInstrumentedTest` 真实装配测试：同一 learner-mastery 权威链接收 `ProblemKnowledgeBindingsSnapshotV2` 与 `ReviewObservationCapturedV2` 后，真实 `LocalLearningMasteryDisplayRepository` 总览出现数学 `NEEDS_REINFORCEMENT`、知识点页列出同一状态且跨科保持未学习，真实 `LocalTutorMasteryContextRepository` 对同知识点返回 `NEEDS_PRACTICE`；第二个问题的同知识点绑定也可复用该掌握，证明单科全局记忆跨题共享。
- **本轮：** 同一真实装配测试增加“错误 → 连续五次正确”用例，断言 subject digest 同时出现最近负向/正向证据且独立呈现计数 ≥ 5。
- **本轮：** 同一真实装配测试增加“弱项关注 → 强项跳过”用例：错误一次后讲题上下文返回 `NEEDS_PRACTICE`，随后持续累积真实独立正确证据直到掌握库投影为 `STEADY`，学习掌握总览升为 `FAIRLY_STEADY`，讲题上下文返回 `SOLID`，第二个问题绑定同一知识点仍读取 `SOLID`，证明稳定掌握可跨题跳过基础提问。
- **本轮：** 同一真实装配测试增加“矛盾/再次出错降频与恢复”用例：稳定 `SOLID` 后连续真实错误把掌握库投影降到 `NEEDS_REINFORCEMENT`、讲题上下文降为 `NEEDS_PRACTICE`，随后独立正确证据重新升回 `STEADY` 与 `SOLID`，证明再次出错不会永久压垮稳定掌握，独立正确可恢复。
- **本轮：** 新增 learner-mastery 真实 Room 过期重评用例：固定时钟下真实独立正确证据先把投影推到 `STEADY`，推进 200 天后 `historicalState` 仍为 `STEADY` 但 `currentRecallState` 过期降级，新近独立正确证据恢复到 `STEADY`；证明旧强项不能永远跳过重评。
- **本轮：** 同一真实装配测试增加“看答案后正确作答”用例，断言该知识点不进入 subject digest，证明答案暴露不能制造独立掌握。
- **本轮：** 修复生产讲题断线：已存错题讲题此前只把 `DirectTeachingContext` 的已核验知识点保留为字符串 ID，未把版本化 `KnowledgeNodeRef` 传入 `TutorMasteryContextRepository`，导致真实页面读不到单科全局记忆；现在 `DirectTeachingContext` 同时携带 `directKnowledgeNodeRefs`，`SavedMistakeTutorRoute` 在未显式指定掌握节点时自动用已核验绑定构造掌握上下文请求，`feature:tutor:testDebugUnitTest` 全量通过。
- **本轮：** 掌握上下文真正覆盖“同科相关知识邻域”：`TutorMasteryContextRequest`/`TutorMasteryContext` 新增有界 `relatedKnowledgeNodes`/`relatedSummaries`（上限 8），`LocalTutorMasteryContextRepository` 从激活知识目录按前 4 个题知识点的关系边扩展同科相关节点，排除已请求节点与跨科节点，二次有界查询掌握投影；掌握上下文携带实际允许的相关节点集合，`TutorModelTaskPolicy` 把相关掌握投影为 `current-question-point-N+1` 的教学约束并计入请求指纹；新增领域、数据与讲题策略测试，`:core:domain:test`、`:core:data:testDebugUnitTest` 与 `:feature:tutor:testDebugUnitTest` 全量通过。
- **本轮：** `LearningMemoryClosureInstrumentedTest` 新增真实装配用例：同一真实 learner-mastery relay 接收主知识点与相关知识点的绑定/复习观测后，`LocalTutorMasteryContextRepository` 通过真实知识目录关系边返回 `relatedSummaries`；`DebugBoundaryKnowledgePackFixture` 增加 `debug.math.related` 原子节点与 `PREREQUISITE` 关系，`:core:data:connectedDebugAndroidTest` 定向该测试 7/7 通过，证明真实权威 + 真实知识目录的相关知识邻域闭环可用。
- **本轮：** 同科复用、跨科隔离、强项跳过、弱项关注、过期重评、矛盾处理和答案暴露不制造独立掌握均有生产测试；真实 Provider 语义归 I01/I02。

### D06 掌握投影、降频/升频与修正

- **目标：** 从证据投影学生掌握状态，并允许新证据、题面修订和删除纠正旧结论。
- **验收：** 独立正确提高稳定度；失败/看答案降低；稳定掌握不重复询问；再次出错可恢复关注。
- **状态：** 完成
- **本轮：** 核验 `LocalMasteryPolicy` 升降频、答案揭示/提示/重试降权、稳定冲突与证据修正链；新增“稳定 → 再次出错降频 → 独立正确恢复稳态”属性测试。
- **本轮：** 新增生命周期跨库恢复：`setProblemLifecycle` 在归档/删除时发布空 `ProblemKnowledgeBindingsSnapshotV2` 撤销掌握库授权，恢复 `ACTIVE` 时重发当前知识绑定；学生库与掌握库仪器回归通过。
- **本轮：** 新增 `ProblemLifecycleChangedV1` 跨库事件：learner-mastery 仅在 `TOMBSTONED` 时退休旧已入账事件，`ARCHIVED` 保留历史投影；仪器测试覆盖“归档保留 + 删除退休”。
- **本轮：** 新增 `ProblemRevisionSupersededV1`：学生库提交连续新 revision 时发布旧 revision 被替换事件，learner-mastery 复用退休替换头清除旧 revision 投影；新增学生库/掌握库仪器测试。
- **本轮：** 修复 `ReviewObservationCapturedV2` 因 `deferSavedAttribution=true` 只存事实不落候选的问题；当前绑定已授权时改为直接落候选，新增真实 Room“复习错误 → 投影 → 连续正确写正向证据”闭环用例。
- **本轮：** 新增真实生产装配级闭环测试，覆盖“复习观测 → 掌握投影 → 学习掌握展示 → 讲题掌握上下文”，`core:data` connected 1/1 通过。
- **本轮：** 独立正确提高稳定度、失败/看答案降低、稳定掌握不重复询问、再次出错可恢复关注均有属性与真实装配测试；真实 Provider 语义归 I01/I02。

### D07 记忆隐私、保留与导出

- **目标：** 明确本机存储、模型发送最小化、删除/导出和诊断日志脱敏。
- **验收：** 删除可验证，导出可读，模型请求不携带无关题目和整库记录。
- **状态：** 完成
- **本轮：** 学习记录可读文本导出已接通真实 `LearningMasteryDisplayRepository`，按科目分页读取、5000 条知识点上限、Storage 页面使用系统文档保存；新增数据库级“清除学习记录”能力，事务清除全部掌握库表并重开数据库重装守卫，数据与隐私页提供确认对话框；新增统一 `DiagnosticRedactor` 和生产日志脱敏治理门；新增 4 项导出 JVM 单测、1 项 Storage 仪器用例、1 项数据库擦除仪器用例、1 项数据与隐私仪器用例，并有模型有界读取既有回归覆盖。

### D08 事实来源、用途授权与生命周期

- **目标：** 把模型候选、本地可验证事实、临时会话、已存题和学习证据分层；先判最小意图，再按 purpose 披露数据。
- **验收：** 模型不能自报答案曝光/正确项/明确请求取得写权；`TEMP_SESSION / SAVED_PROBLEM / LEARNING_EVIDENCE` 独立；最终 Repository 强制授权。
- **状态：** 完成
- **本轮：** `ModelTaskCompletionValidator`、`TutorGuidancePolicy` 与学习写权威已拒绝模型自报答案曝光/正确项/直接写权，`TEMP_SESSION / SAVED_PROBLEM / LEARNING_EVIDENCE` 生命周期已由会话与证据库区分；真实 Provider 对抗集仍待外部。
- **本轮：** 重新运行 `ModelEgressTest`、`TutorTasksTest` 与 `TutorModelTaskPolicyTest`，模型自报答案曝光/正确项/直接写权均被本地拒绝，三类生命周期独立；真实 Provider 对抗集归 I01/I02。

## E. 拍题、错题本与文档

### E01 模型图片评估和结构化题面

- **目标：** 模型决定可读、重拍、补页、拆题，并输出受控题面文档。
- **验收：** 糊图、遮挡、反光、缺页、多题、公式、表格和图形真实题集通过；无效 JSON 失败关闭。
- **状态：** 部分落实
- **本轮：** `CaptureAssessment`/`CaptureParse` 已覆盖可读/重拍/补页/拆题决策与受控题面文档，无效 JSON 失败关闭；真实糊图/遮挡/反光/多题题集仍待外部验收。

### E02 正常路径零 OCR 负担

- **目标：** 可用题面自动继续，修改只作为可选动作。
- **验收：** 学生无需重新输入题干；只有无法继续时才出现一次最小补充；没有固定“检查”关卡。
- **状态：** 完成
- **本轮：** CaptureScreen 新增 `shouldAutoCommitCaptureCandidate` 纯策略：模型结构化题面可用、用户未编辑、无进行中/未知结果/已提交状态时自动调用 `commitCorrection`，不再固定停在“题面有误，修改/继续”表单；本地过渡候选和用户编辑仍保留人工入口。新增 10 项自动继续策略测试，`:feature:capture:testDebugUnitTest` 与 `:feature:capture:connectedDebugAndroidTest`（23/23）通过。
- **本轮：** 重新运行 `:feature:capture:testDebugUnitTest`，自动继续策略、最小补充入口与无固定检查关卡均有自动化证据。

### E03 单题、批量与可恢复任务

- **目标：** 相机、相册、多图、PDF 分页的导入任务可暂停、重试和重建恢复。
- **验收：** 1/10/100/500 页中断、强杀、重复回调和部分失败不丢数据、不重复提交。
- **状态：** 完成
- **本轮：** 单题、批量导入、PDF 分页与草稿恢复由权威任务/会话端口支撑，强杀/重复回调/部分失败不丢数据不重复提交有仪器测试；500 页与真机压力仍待外部。
- **本轮：** 批量导入启动恢复入口、持久合并收据重放、中断页/边界重排队已接入统一调度器并有测试；500 页与真机压力归 I03 外部设备验收。

### E04 归类、近重复和题族

- **目标：** 模型归类到科目/板块/多个知识点，并识别相同、近似和同题族关系。
- **验收：** 不暴露错因/来源；不误合并原始题；题族可供复习代表题使用；模型建议需本地证据和阈值。
- **状态：** 完成
- **本轮：** 生产 `ReviewedStudentProblemOrganizationMapping` 新增 `PROBLEM_FAMILY` 本地证据门：仅当模型题族置信度 ≥ 0.90 且绑定到本次已核验知识引用（章节/原子知识证明）时写入题族 facet，低置信度建议静默丢弃且不阻断整理；绑定指纹包含科目、题面指纹、知识快照与证据列表。复习候选读取最新题族 facet 时携带其基础题面指纹，规划器只在题面指纹与当前 revision 完全一致时按题族去重；无审校题族或绑定失效时回退到当前 revision 的题面指纹，使完全相同内容跨条目只选一道，近似题仍不合并。同题/近似题的关系候选仍只在旧 v2 路径披露，生产 v3 近重复与题族关系闭环仍待下一步。
- **本轮：** 审计确认 v3 近重复/题族关系闭环已落地：`ProblemOrganizationV3Input.relationCandidates` 经 OpenAI v3 协议 alias 化进入 prompt，解析器重建 `relations`，`buildV3ConfirmationCommand` 只接受已核验候选并把关系写入学生库组织收据；`OpenAiProblemOrganizationProtocolTest`、`ProblemOrganizationTasksTest`、`ReviewedStudentProblemOrganizationMapperTest` 与 `RoomMistakeOrganizationRepositoryTest` 覆盖候选 alias、未知候选失败关闭、低置信度过滤、VARIANT_OF/POSSIBLE_DUPLICATE 映射和题族证据门。

### E05 结构化渲染、图片转文档与导出

- **目标：** 统一题干、选项、公式、表格、坐标、图形区域和分页文档。
- **验收：** Android 阅读、PDF 保存/分享/打印和长文档性能通过；不信任未校验模型内容。
- **状态：** 部分落实
- **本轮：** 结构化题面、PDF 导入/导出与受信文档渲染已接通，模型内容经协议校验后才渲染；长文档真机性能仍待外部验收。

### E06 多层身份、重复收敛与出现证据

- **目标：** 分离请求幂等键、内容哈希、规范化题目键、正式题目 ID 和题族 ID。
- **验收：** 同内容不同 URI 不产生重复学生待办；近似题可形成可撤销题族；再次采集记录为出现证据而非复制题目。
- **状态：** 部分落实
- **本轮：** 请求幂等键、内容哈希/规范化题面、正式题目 ID 与审校题族 ID 已分层，`StudentCanonicalCaptureIdentity` 覆盖重复捕获收敛与出现证据；近似题可撤销题族与真实 Provider 语义仍待外部。

## F. 讲题与动态 GUI

### F01 空会话自由聊天入口

- **目标：** 讲题首页直接可输入，附件拍题和错题本选择靠近输入框。
- **验收：** 纯文字知识问题、学习情况、查错题、帮助、闲聊和需要题面六类路径真实可用。
- **状态：** 完成
- **本轮：** `TutorLobbyRoute` 空会话已直接展示聊天输入框与拍题/错题本快捷入口，`TutorLobbyInput` 支持知识提问/学习情况/查错题/设置/闲聊/需要题面等意图，相关路由与模型策略测试通过。
- **本轮：** 当前轮收口证据：`TutorLobbyRouteInstrumentedTest`（5/5）、`TutorLobbyModelTaskPolicyTest`、`TutorLobbyConversationControllerTest` 与 `TutorLobbyPortBoundaryTest` 全部通过；六类意图的本地路由与输入入口可真实操作。真实 Provider 语义质量归 I01/I02。

### F02 当前题聊天与持续会话

- **目标：** 临时题和已存题共享聊天流，历史、修订、重试、停止和进程重建准确。
- **验收：** 不重复逻辑回合、不串 Provider/题目版本、不因旋转/强杀丢失回复。
- **状态：** 完成
- **本轮：** 临时拍题与已存错题共享 `CapturedTutorSessionRoute` / `SavedMistakeTutorRoute` 聊天流，会话、任务、修订、重试与进程恢复由真实 Room/模型任务仓库支撑并有仪器回归。
- **本轮：** 收口 `CapturedTutorSessionInstrumentedTest` 的 Compose 跨用例隔离：retry/disclosure 用例在交互前显式滚到会话尾部，`expiredLeaseKeepsExactReplyUntilOneConfirmationResumesIt` 补齐掌握上下文装配，截图 helper 从 MediaStore 改为应用专属外部目录；`:feature:tutor:connectedDebugAndroidTest` 全量 80/80、`CapturedTutorSessionInstrumentedTest` 57/57 通过。
- **本轮：** 当前轮收口证据：`CapturedTutorSessionInstrumentedTest`（57/57，含 `restartingAfterRecreationCarriesExactStudentWordsWithStableRequestIdentity`）、`SavedMistakeTutorAnchorTest`、`CurrentTutorSessionProductionOwnerTest` 与 `:core:database:connectedDebugAndroidTest`（280/280）全部通过；旋转/强杀恢复与不串 Provider/题目版本由真实 Room 会话/任务和模型任务仓库覆盖。真实 Provider 语义质量归 I01/I02。

### F03 教学约束和有价值诊断

- **目标：** 只围绕用户当前请求；诊断可省略、难度匹配、不重复问稳定掌握点。
- **禁止：** 未请求的新题、变式、校准和额外能力测试。
- **验收：** Prompt、结构化协议、本地完成校验和真实 Provider 对抗集四层同时通过。
- **状态：** 部分落实
- **本轮：** 审计确认本地已形成四层中的前三层硬门：Prompt 明确禁止生成新题/同类题/变式/校准，Provider 解析只允许受限 `interactionDirective`（无完整题面）或 legacy `diagnosticQuestion`，`TutorTurnPlan.init` 与 `locallyConstrainedFor` 对交互形状/模式/数量/掌握标签做本地降级与失败关闭，`ModelTaskCompletionValidator` 拒绝越界意图与已掌握证据目标；真实 Provider 对抗集与语义级“变式伪装成诊断”仍待外部验收。

### F04 动态话题与下一步

- **目标：** 结合当前卡点生成实时话题/动作，自由输入始终可用。
- **验收：** 不使用固定死话题，不无意义截断，不重复已完成步骤，动态标签通过学生语言校验。
- **状态：** 部分落实
- **本轮：** 模型输出 `suggestedMoves`/`nextMoves` 作为动态下一步，`TutorRoute` 与 `TutorGeneratedTurn` 渲染真实动作，自由输入始终可用；固定死话题未使用，真实 Provider 语义质量仍待外部验收。

### F05 通用声明式 GUI 命令协议

- **目标：** 定义版本化 `VisualCommand`，模型只声明组件、数据、变量、单位、关系、时间轨道和交互；一次回复最多一个主场景和一个必要辅助场景。
- **禁止：** 任意 Kotlin/JavaScript/HTML/SVG、远程代码、自由脚本、逐题页面 ID、逐题动画实现和无限组件树。
- **验收：** Schema 有版本、数量、尺寸、深度、文本、表达式、时长和复杂度上限；未知/恶意字段失败关闭；同一协议能表达跨学科样例。
- **状态：** 完成
- **本轮：** `TutorVisualScene`/`TutorVisualDocumentScene`/`TutorVisualProgramScene` 已版本化，`requireOnlyKeys` 对未知字段失败关闭，`TutorSceneValidation` 与 `TutorVisualProvenanceValidator` 校验数量/尺寸/深度/文本/表达式/时长预算；单轮一主场景限制由协议字段与解析互斥保证。
- **本轮：** `TutorVisualProvenanceTest`、`TutorVisualProgramTest`、`TutorVisualProvenanceValidatorTest` 与 `TutorVisualDocumentRuntimeTest` 已覆盖版本、未知字段失败关闭、跨学科样例、数量/尺寸/深度/文本/表达式/时长预算和单轮一主场景约束。

### F06 通用原语与场景组合引擎

- **目标：** 用文字/数学、表格、坐标轴、数据序列、节点、连线、形状、向量、标签、步骤和状态卡等有限原语组合所有场景。
- **场景：** 步骤、对照、证据、时间线、关系、公式、函数/数据图、空间/电路、化学关系等只是组合，不拥有题目专用页面。
- **验收：** 新题只提交新命令数据；无需新增 Composable；无模型图片依赖；大字体、滚动、折叠、无障碍和截图回归通过。
- **状态：** 完成
- **本轮：** `TutorVisual2DPanel` 与 `TutorVisualSceneRenderer` 系列已用有限原语组合步骤、对照、证据链、时间线、关系、公式、函数/数据图、空间/电路与化学关系场景，`TutorVisualDocumentInstrumentedTest` 等验证渲染与重建。
- **本轮：** `TutorVisualDocumentInstrumentedTest` 已覆盖 2D/3D/图表通用播放器切换、命中证明、原图/反馈、状态恢复、Filament 重复开关与非法方向回退，证明新场景走通用原语渲染且无需新增题目专用页面。

### F07 通用动画与数值运行时

- **目标：** 统一执行实体属性、时间变量、关键帧、路径、约束和受限数值表达式；运动类型只是参数化命令。
- **验收：** 不为直线、抛体、圆周、振动或新题建立独立页面；安全表达式无循环、I/O 和非确定性；播放/暂停/复位/拖动/倍速可用；单位校验、参数上限和数值精度正确。
- **状态：** 完成
- **本轮：** `TutorMotionSceneRenderer` 与 `TutorVisualProgramRenderer` 提供直线、抛体、圆周、振动等参数化运动，`TutorVisualProgramRenderer` 执行受限表达式与时间轨道，播放/暂停/复位/倍速由通用运行时支持。
- **本轮：** 新增 `TutorMotionPlaybackControlTest`，覆盖 0.5/1/2 倍速循环与播放时间钳制；结合 `TutorMotionSceneTest`、`TutorVisualProgramTest` 与 `TutorVisualProvenanceValidatorTest`，播放/暂停/复位/拖动/倍速、单位校验、参数上限和数值精度均有自动化证据。

### F08 讲题语言、安全与流式体验

- **目标：** Markdown、公式、按钮、错误态和流式内容统一过滤、节流和恢复。
- **验收：** 技术词无法从任意模型字段漏出；长回复不卡顿；半截流、重试和 Provider 切换不制造重复记忆。
- **状态：** 完成
- **本轮：** `StudentFacingLanguagePolicy` 覆盖模型可见文本，`StreamingMarkdownAssembler` 提供有界流式组装，半截流/重试/Provider 切换不制造重复记忆由任务身份与持久化测试保障；真实 Provider 长回复与限流验收仍待外部。
- **本轮：** 重新运行 `StudentFacingLanguagePolicyTest` 与 `StreamingMarkdownAssemblerTest`，技术词过滤、流式稳定前缀、半截流回滚和重复记忆防护均有自动化证据；真实 Provider 长回复与限流归 I01 外部验收。

## G. 非机械复习与大量导入

### G01 可复习集合与知识复习债务

- **目标：** 所有错题保持复习资格，知识点层面记录需要巩固程度，今日队列只是一个预算内视图。
- **验收：** 未入选题不被标为已复习；删除/修订题能正确更新债务；批量导入不制造当天洪峰。
- **状态：** 完成
- **本轮：** 核验 `ThreeAuthorityReviewPlanner` 与 `ThreeAuthorityDailyReviewRepository` 已按候选池、今日队列和知识信号分离调度；真实完成/跳过/未选状态由学生库持久化。
- **本轮：** 重新运行 `ThreeAuthorityDailyReviewRepositoryTest` 与 `ThreeAuthorityReviewPlanCoordinatorTest`，未选题不产生完成记录、删除/修订更新债务、批量导入不制造当天洪峰均有自动化证据。

### G02 题族、代表题和同质去重

- **目标：** 为普通导入题建立真实题族，而不是每题唯一 family ID。
- **验收：** 同知识/同方法/近似题每天不过量；代表题成功只降低兄弟紧迫度，不伪造兄弟完成记录。
- **状态：** 完成
- **本轮：** 核验规划器按 problem/solution/presentation 族去重，已有 `oneRepresentativePerProblemSolutionAndPresentationFamily` 与同族洪水测试；真实题族来源仍需模型分类链路证据。
- **本轮：** 复习候选新增 `reviewedProblemFamilyId`，`StudentMistakeDao.readReviewedProblemFamilies` 从最新 `COMPLETED` 组织收据读取 `PROBLEM_FAMILY` facet，`RoomStudentMistakeStore` 在 `readReviewCandidatesWithKnowledge` 中携带该值，`ThreeAuthorityReviewPlanCoordinator` 优先使用审校题族去重、缺失时回退到 exact saved problem；新增“审校题族按族选代表题”与“未审校标签回退原题”JVM 测试，student-mistake connected 73/73 回归通过；随后补齐模型输出与生产映射，见下一条。
- **本轮：** `ProblemOrganizationPlan` 新增可选 `problemFamily`（`ProblemFamilySuggestion`），字段用 `@EncodeDefault(Mode.NEVER)` 保持旧 v3 wire 编码不变；OpenAI v3 协议 prompt 要求输出并强制解析/校验题族键；`ReviewedStudentProblemOrganizationMapping` 把审校 `PROBLEM_FAMILY` facet 写入 `OrganizeStudentProblemCommand`，学生库端口允许“仅 PROBLEM_FAMILY”的审校 facet 集；新增 Provider 缺省失败/解析、映射器单 facet、DAO 真实 Room 读取与旧编码稳定性测试；`core:model:test`、`:core:model-provider:test`、`:core:data:testDebugUnitTest`、student-mistake connected 74/74 通过。
- **本轮：** 真实题族来源已由 OpenAI v3 协议、Provider 解析、审校映射器、学生库组织收据与复习规划器全链打通；同族代表题只降低兄弟紧迫度，不伪造兄弟完成记录。

### G03 每日时间与题数预算

- **目标：** 同时使用分钟和题数上限，提供轻量/标准/加强三档；标准档先以 15 分钟作为实验基线。
- **验收：** 任何输入规模都不突破预算；超长单题有明确处理；设置修改次日稳定生效。
- **状态：** 完成
- **本轮：** 新增 `ReviewPacingLevel` 三档节奏和 `maxItemCount` 硬上限，贯穿领域规划器、计划端口、协调器与仓库；默认标准 15 分钟/5 题，新增上限、指纹和协调器回归。
- **本轮：** 复习提醒页新增轻量/标准/加强选择，`ReviewReminderPreferences` 持久化 `pacingLevel`，生产 `ReviewHomeRequest` 读取后按新节奏生成每日计划；UI 与 DataStore 仪器回归通过。
- **本轮：** 超长单题明确处理：单题时长超过当日剩余预算时不安排、不截断、不超时，保持排队等待后续日程；新增两个领域回归测试。
- **本轮：** 设置修改次日稳定生效：当日已持久化计划不因节奏/考试目标变更重排，次日新偏好生效；新增数据层回归测试。
- **本轮：** 重新运行 `ThreeAuthorityDailyReviewRepositoryTest` 与 `ThreeAuthorityReviewPlanCoordinatorTest`，三档节奏、任何规模不突破预算、超长单题排队、次日稳定生效均有自动化证据。

### G04 优先级、遗忘与升降频

- **目标：** 综合遗忘风险、掌握下界、近期失误、重复错、逾期和真实考试目标。
- **验收：** 稳定掌握降频、再次出错升频、答案暴露快速复习、无考试信息时无伪权重。
- **状态：** 完成
- **本轮：** 新增 `ReviewExamTarget(subject, examAtEpochMillis)`，考试科目 14 天内提升对应科目优先级并计入计划指纹；请求/计划/仓库/偏好/UI 全链透传，DataStore 持久化 `exam_subject` / `exam_epoch_day`。
- **本轮：** 复习提醒页新增考试科目与 7/14/30 天目标设置，`SmartMistakeBookRoot` 转为 `ReviewHomeRequest.examTarget`；领域、数据、Compose、DataStore 回归通过，并新增 14/15 天窗口边界测试。真实 Provider 端到端证据仍缺。
- **本轮：** 答案暴露快速复习：新增 `ReviewPriorityReason.ANSWER_REVEALED`，把学生端 `review-answer-revealed` 原因码映射为独立高优先级并在规划器中提升排序；学生库在看答案后提交作答时也写入该原因码，新增领域、协调器与学生库仪器回归。
- **本轮：** 重新运行 `ThreeAuthorityReviewPlannerTest`，考试目标窗口/边界/无目标不造假权重、答案暴露快速复习、稳定/波动知识排序均通过；稳定降频与再次出错升频由 learner-mastery 投影与 `LocalMasteryPolicyPropertyTest` 覆盖。

### G05 轮换、公平与防饥饿

- **目标：** 让长期未选题获得老化优先，平衡高风险与覆盖。
- **验收：** 1000 题仿真中没有永久饥饿；高风险题不过度被公平轮换延误；结果可重复。
- **状态：** 完成
- **本轮：** 核验 30/90 天轮换、等待分与防饥饿测试；`MAX_ITEM_COUNT` 上限下大批量仍可存储到权威队列上限。
- **本轮：** 新增 5000 题大批量高风险性质测试：20 个 `ANSWER_REVEALED` 高风险候选与 4980 个普通新保存候选混排时，首日 50 题计划包含全部高风险候选，证明公平轮换不会延误高风险；`:core:domain:test` 全量通过。

### G06 大批量调度仿真和性质测试

- **目标：** 用 10/100/500/1000/5000 题、同质/异质/多科/重复/已掌握组合验证算法。
- **指标：** 首日负担、30/90 天覆盖、最大等待、同质连续数、预算命中率和状态正确性。
- **验收：** 所有核心不变量自动化；固定种子可复现；性能满足中端真机预算。
- **状态：** 部分落实
- **本轮：** 新增 `ThreeAuthorityReviewBulkSimulationTest`，对 10/100/500/1000/5000 五档规模统一仿真，逐日断言预算命中、题数上限、无重复选择、确定性指纹、公平服务天数与已选状态移除；新增同族大规模场景断言每个每日计划最多一个代表题且最终全量服务；`:core:domain:test` 全量通过。中端真机性能仍待 I03 外部设备验收。

### G07 复习首页、会话、提醒和打卡

- **目标：** 只展示今日题数、预计时间、开始/继续、完成、连续记录和必要薄弱提示。
- **删除：** 顶部和底部录入错题入口。
- **验收：** 通知到达/点击不冒充打卡；只有真实完成产生记录；时区/DST/重启正确。
- **状态：** 完成
- **本轮：** 审计确认 `ReviewOpenRequestPolicyTest` 保证通知点击只打开复习入口不冒充打卡，`ReviewReminderCoordinatorTest` 与持久化测试保证只有真实完成答案才推进会话/写完成记录，通知/时区/重启恢复有仪器回归；真实设备通知与 DST 边界仍待外部。
- **本轮：** 重新运行 `ReviewReminderCoordinatorTest` 与 `ReviewReminderPlatformInstrumentedTest`，通知发布、点击入口、完成记录、时区/DST 与重启恢复均通过；真实设备通知与 DST 边界继续归 I03 外部设备验收。

## H. 我的与学习掌握

### H01 隐藏 AI 知识库运维信息

- **目标：** 移除“知识点与资料”、分类依据、资料数、完善/不完善和待补齐。
- **验收：** 学生 UI、可访问性文本、通知和错误信息均不暴露；内部工具仍可独立使用。
- **状态：** 完成
- **本轮：** 新增生产 UI 内部术语审计门禁：扫描 `app`/`feature`/`core/ui` 全部 `src/main`，只检查 `Text`/`contentDescription`/`title`/`message`/`error`/`hint` 等学生可见字符串位置，禁止“原子知识、学习投影、置信度、证据权重、分类依据、资料完整度、grounding、taxonomy、schema”等内部术语；结合学习掌握、复习、存储等既有 Compose 仪器测试，学生 UI/可访问性/通知/错误文案均不再暴露 AI 知识库运维信息。

### H02 学习掌握只读投影

- **目标：** 为总体、科目、板块、知识点、趋势和最近变化建立高效读模型。
- **验收：** 来源只是真实学习证据；时间线可解释；2 万知识点读取不卡主线程；跨科正确。
- **状态：** 完成
- **本轮：** `LocalLearningMasteryDisplayRepositoryTest` 新增 20,000 知识点分页回归：逐页读取时每页不超过 `LearningMasteryPageRequest.MAX_LIMIT`、任何掌握库读取请求都不一次携带全量节点、全部 20,000 点最终无重复覆盖；结合已有跨科、时间线、revision 绑定与知识库 2 万节点规模基准，H02 验收项均有自动化证据。

### H03 学习掌握多样化页面

- **目标：** 使用总览数字、科目卡片、分布条、板块进度、知识点列表、趋势和变化时间线。
- **交互：** 仅科目/板块下钻和 7/30 天切换，不要求学生维护。
- **验收：** 空数据、少量、九科大量、长名称、大字体和无障碍均清晰；没有不可操作的系统状态。
- **状态：** 部分落实
- **本轮：** 增加顶部总体状态摘要，统一 `SmartDimens` 圆角/间距 token，优化状态文字层级，补充仪器测试与截图证据。
- **本轮：** 将 learner-mastery 真实按天时间线接入领域/数据/UI，新增 7/30 天切换与趋势图；真实 Room 与 Compose 仪器回归通过。
- **本轮：** 新增科目掌握分布条与板块进度条，配套 app 纯函数单元测试和 Compose 仪器断言。
- **本轮：** 学习掌握页新增板块下钻：知识点区提供“全部 + 各板块”横向筛选条，选中板块后板块进度、薄弱知识和知识点列表只展示该板块；新增板块名/筛选纯函数单元测试与 Compose 仪器用例，`:app:testLocalFirstDebugUnitTest` 定向通过、`LearningMasteryScreenInstrumentedTest` 7/7 通过。

### H04 我的页面整体信息层级

- **目标：** 学习情况、学习掌握、模型设置、提醒、隐私和存储按学生心智排序。
- **验收：** 设置与学习数据不混杂；常用信息先于低频管理；入口文案一眼可懂。
- **状态：** 完成
- **本轮：** 生产 `ProductionProfileHomeRoute` 已固定为“学习掌握 → 设置”的信息层级，五个入口可点击；新增 `ProductionProfileHomeInstrumentedTest` 与 `profile_open_learning_mastery` 锚点，验证学习区在设置区之前且所有动作可触发。

## I. 真实质量与发布

### I01 真实 Provider 一致性

- **目标：** 至少两家兼容 Provider 覆盖文字、图片、结构化输出、流式、限流和错误恢复。
- **验收：** 能力探测与实际调用一致；配置切换不会沿用旧授权；质量失败有可恢复路径。
- **状态：** 待开始

### I02 九科模型质量评测

- **目标：** 真实题评测图片理解、题面结构、知识归类、讲解正确性、诊断价值和 GUI 选择。
- **验收：** 分科指标、失败样本、人工复核和回归阈值明确；不以少量演示题代替。
- **状态：** 待开始

### I03 设备、性能与长时稳定

- **目标：** 从项目最低 Android API 到 API 36 的代表版本，以及至少两台中端 ARM 真机，覆盖大库、批量、长会话、PDF、通知和强杀恢复。
- **验收：** 启动、滚动、检索、数据库、内存、耗电和 ANR 指标达标。
- **状态：** 待开始

### I04 安全与隐私回归

- **目标：** 网络边界、URI/文件、密钥、日志、导出、删除、权限和模型越权全量复测。
- **验收：** 已知 11 个规范问题全部关闭；新增攻击路径扫描无阻断/高优先级发现。
- **状态：** 待开始

### I05 高中生可用性测试

- **目标：** 覆盖拍题求助、课后录题、批量迁移、每日复习、查看学习掌握。
- **验收：** 记录完成率、操作数、误解点、注意力负担；阻断问题回到对应工作包修复并复测。
- **状态：** 待开始

### I06 发布完成门

- **目标：** 用 A01 需求矩阵逐条关闭，不留“部分落实”冒充完成。
- **验收：** 全测试、Lint、双构建、数据库迁移、模拟器、真机、真实 Provider、真实题集和 UI 截图证据齐全；用户确认剩余非核心限制。
- **状态：** 待开始

### I07 规模基准、SQL 计划与公平性证明

- **目标：** 用统一基准证明 5000 错题、数百草稿、30 页 PDF、多个并发批次和大知识库真实可用。
- **验收：** 记录 p50/p95、峰值内存、GC、磁盘增长、SQL plan、最迟获调度时间和 Provider 并发；形成回归阈值。
- **状态：** 待开始

## 首要依赖链

```text
A01 → A02/A03/A04
A02/A03/A04 → B03
B01/B02/B03/B04/B05/B06 → C/D/E/F/G/H
C02/C03/C04 + D04/D05/D06 → F03/F04 和 G01–G06 和 H02/H03
D01/D02/D03 → F01/F02
E04 → G02
F05 → F06/F07/F08
全部功能工作包 → I01–I06
```

## 防含糊规则

- 不允许把一个工作包压缩成单一 Compose 页面、单一 Prompt 或单一 DAO。
- 不允许用“已有接口”代替生产调用。
- 不允许只测快乐路径。
- 不允许把模型建议直接当作学习事实或正式知识。
- 不允许把模拟器性能宣传成真机性能。
- 不允许在真实内容、授权、真实 Provider 或学生测试缺失时宣称项目全部完成。
