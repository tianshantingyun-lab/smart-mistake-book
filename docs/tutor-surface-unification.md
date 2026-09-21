# 智能体页面统一：差异事实、遗留问题与方案

> 2026-09-19 立项。起因：产品侧指出「哪里有什么大厅页和讲题页，这不是同一个页面（智能体页面）」，
> 并裁定：功能汇总到智能体页面；选题在聊天框的加号里完成；**错题的绑定不与会话绑定，而是按轮次语义判定**（可跨轮）。
> 本文是这件事的事实底稿与分阶段方案：先把"两个东西到底哪里不同"写清楚，再写怎么合。

## 0. 一句话结论

代码里本来**只有一个入口**：`TutorRoute` 只按一个条件切换渲染——
`practiceUnitId.isBlank() && teachingArtifact == null`（`feature/tutor/.../TutorRoute.kt:125-142`）。
"两套东西"的来源不是产品设计，而是**有人把"有没有题"这条安全边界画在了页面/契约层**：注释写得很直白——
`ALLOWED_LOCAL_CAPABILITIES` 排除掌握度读取的理由是「没有当前题就没有锚点」（`core/model/.../TutorLobbyTasks.kt:172-184`）。
于是"有没有题"被实现成两条 route、两个输入契约、两套输出契约、两个工具声明集。
**它本该画在"每一轮"上**。本方案就是把这条边界下移到轮次。

## 1. 入口清单（今天的六条）

| 入口 | 触发 | 携带 | 现状问题 |
|---|---|---|---|
| 底部「智能体」`nav_tutor` | `SmartMistakeBookRoot.kt:123` | 无 | 就是那个"一个 tab" |
| `Routes.Tutor` 的 `tutor_capture_shortcut` | `TutorLobbyRoute.kt:750` | → `Routes.CaptureTutor` | 页内按钮，跳走去拍照 |
| `Routes.Tutor` 的 `tutor_choose_existing_button` | `TutorLobbyRoute.kt:759` | → `Routes.Library`（**不返回任何东西**） | 「从错题本选择」其实只是跳去错题本，选完还得再点「讲解这道题」 |
| 拍照讲解 `Routes.CaptureTutor` | `SmartMistakeBookRoot.kt:592-620` | `sessionId` → `Routes.capturedTutorSession(sessionId)` | 独立页面 |
| 错题详情「讲解这道题」`mistake_detail_start_tutor` | `feature/library/.../MistakeDetailRoute.kt:355-364` | `MistakeRevisionKey`（entryId/problemId/problemRevisionId） | 独立页面；历史版本无此按钮 |
| 历史重开 | `SmartMistakeBookRoot.kt:398-433` | `conversationId` 或 `anchorId` | TEXT_ONLY 回大厅、锚定会话回会话 |

另有两处顺带的判题入口复用同一会话页：复习预判 `SmartMistakeBookDestinations.kt:262`、判题复核 `:283`。
无生产调用的死代码：`TutorStartEmptyState`（`TutorRoute.kt:247-280`，主树无调用者）。

## 2. 差异事实表（大厅 vs 讲题会话）

| 维度 | 大厅（无题） | 讲题会话（绑题） | 证据 |
|---|---|---|---|
| 锚定 | `TEXT_ONLY`，无题面/无 revision | `EPHEMERAL_DRAFT` + 题面 revision | `TutorConversationRepository.kt:5-9, 33-72`；`TutorSessionViewModel.kt:94-108` |
| 派发 | `TUTOR_LOBBY` | `TUTOR_PLAN` / `TUTOR_RESPOND` | `TutorLobbyModelTaskPolicy.kt:21`；`TutorModelTaskPolicy.kt:285, 379` |
| 输入契约 | `TutorLobbyInput`（**无题面字段**） | `TutorRespondInput`（`questionDocument` + revision + cycle/turn） | `TutorLobbyTasks.kt:6-39`；`TutorTasks.kt:342-373` |
| 输出契约 | 正文+意图+思考（4 个 wire key） | 再加 `solutionRevealed`/`suggestedMoves`/`visualRequest·Scene`/题面 id/轮次 | `TutorLobbyTasks.kt:134-144`；`TutorTasks.kt:690-709`；`OpenAiModelResponseParsers.kt:550-565` |
| 工具声明 | 1 个（`NOTEBOOK_READ`） | 5 个（含 `MASTERY_UPDATE`/`NOTEBOOK_WRITE`） | `RoomModelTaskRepository.kt:740-751`；`TutorLobbyModelTaskPolicy.kt:76` |
| 出网授权 | **逐次清单**（`isAgentConsentEligible=false`） | 「配置模型=同意」（`ProviderConsented`，无清单） | `ModelTasks.kt:158-166`；`TutorModelTaskPolicy.kt:426-434`；`ModelEgress.kt:504-528` |
| 披露集合 | 学生消息 + 会话上下文（+图片） | 再加题面、学习证据、学科知识库 | `ModelEgress.kt:289-346` |
| 轮次账目 | `messageOrdinal`（库里 slot 为 NULL） | `responseOrdinal`（落库 slot，含唯一槽校验） | `ModelTaskTransactionDao.kt:201, 215-227` |
| 实时流 | 已接（`observeLiveText` + `TutorStreamingReply`） | **未接**（仍用 `streamingReplyBody` 状态串） | `TutorLobbyRoute.kt:292-301` vs `TutorChatConversation.kt:138-144` |
| 写权限判定 | 不适用 | 写死"输入类型是 Respond" | `RoomModelTaskRepository.kt:332-346` |
| 答案暴露 | **契约上无法表达**（无 `solutionRevealed`） | 需 5 项身份匹配 | `TutorTasks.kt:479-495`；`TutorInteractionRepository.kt:64-85` |

## 3. 必须保留的不变量（合并时一条都不能丢）

1. **无题不得写**：没有题目锚点就不能产生学习证据。今天写成 `input is TutorRespondInput`，合并后必须变成显式"本轮有绑定题"，并保留一条"无题轮申请写入仍被拒"的测试。
2. **工具授权 = 意图 × 置信度 × 声明集**；`NOTEBOOK_WRITE` 还需 `explicitActionRequest`（`TutorToolLoop.kt:359-411`）。
3. **披露集合是精确相等**（`disclosedData == expected` 且 `prohibited = 全集 − 已披露`，`ModelEgress.kt:222-227`）。
4. **无题面走逐次清单**：文本闲聊不允许悄悄变成"全局同意"外发。
5. **答案暴露需要学生原话 + 精确轮次身份**（`TutorTasks.kt:479-495`）。
6. **写侧引文底线**：任何正向判断至少 1 条已核实引文锚（`MasteryWriteGate.kt:93-101, 147-158`）。
7. `tutor_message` 唯一 `(conversation_id, ordinal)`；`model_task` 会话内轮次号唯一（slot 校验）。
8. 指纹对"输入类 + 字段"敏感：新字段必须 strip 空载体 + 升 schema（见 `docs/` 与提交 `bf8be888` 的教训）。
9. 旧数据可读：旧会话/旧任务行升级后不得抛完整性异常。

## 4. 纯漂移与真缺陷（6 条，其中 4 条是真 bug）

| # | 现象 | 证据 | 性质 |
|---|---|---|---|
| D1 | 保存题会话页拿不到 `attachedImageResolver` → 模型要的配图不渲染 | 只在 captured 传 `SmartMistakeBookRoot.kt:738-745`；`SavedMistakeTutorRoute.kt:316-355` 未传 | **真 bug** |
| D2 | SavedMistake 的意图确认按钮是空实现（保存/结束点了没反应） | `TutorSessionPanel.kt:135-136` 默认 `{}`；`SavedMistakeTutorRoute.kt:316-355` 未传 | **真 bug** |
| D3 | SavedMistake 写学生轮次时目标会话可能不存在，失败被 `runCatching` 吞掉 | `TutorRespondCommands.kt:281-300`；会话只在 `TutorLobbyRoute.kt:515` 与 `TutorSessionViewModel.kt:97` 创建 | **真 bug**（静默） |
| D4 | 大厅里模型申请"查错题本"后界面毫无反应（能力合法但惰性） | `intentDecision` 在大厅无消费者；`tutor_choose_existing_button` 只是跳转 | **真 bug** |
| D5 | 大厅的"这次别记"（`memoryPreference`）无消费者；`TutorLobbyOutput.attachedImages` 是死字段（解析+校验但从不落库/渲染） | `TutorSessionWritePolicy.kt:10-21`；`TutorLobbyRoute.kt:312-326` | 漂移 |
| D6 | 大厅不自动滚到最新（真机截图确认：回答到达后停在旧位置） | 真机 2026-09-19 截图 | 体验缺陷 |

## 5. 目标形态（按产品裁定）

1. **一个入口一个页面**：底部「智能体」。拍照讲解 / 错题详情"讲解这道题" / 复习判题 / 历史重开全部变**深链**，把题作为**本轮附件**带进同一个页面。
2. **加号菜单三项**：拍照 / 相册 / **从错题库选择**（新增页内 picker——今天 `onChooseExisting` 只 `navigate(Routes.Library)`，不返回任何东西）。
3. **会话不再绑题**：新建会话一律不带题锚；历史里已有的锚定会话继续可读，只作为候选来源。
4. **每轮语义绑定**：派发前本地给候选菜单（本轮显式添加的题 + 上一轮绑定的题 + 本地文本检索前 N 条，形状复用 `RelatedProblemCandidate`：problemId + revisionId + title + document，≤8）；**模型只判"这一轮在说哪一道（或不指任何一道）"**，本地校验两条：候选必须在菜单内、学生消息里要有可核对的词（沿用 `actionIsBoundTo`/`lookupTerms` 的逐字锚纪律）。校验不过 → 无题轮。
5. **门控按绑定工作**：写工具要求本轮有绑定题；答案暴露要求绑定题 + 学生明确索要；披露集合按"无题 / 有题"**两态**运行时选择，另有大厅附图与候选菜单两个正交加成（题轮在清单路径上不带图字节，没有"有题带图"这一态——它此前写在函数签名里但三个校验点都不可达，已删）。
6. **工具同页声明全五个**，由门控决定可用性。

## 6. 分阶段与已知障碍

- **P1 看得见的一个页面**（不动安全契约）：单入口 + 加号三选项 + 页内 picker + 深链改造 + 一个屏幕组件 + 一条实时流 + 一个上下文装配；顺带修 D1~D4 与 D6。
  - **P1-a（844496e8）**：加号三选项 + 页内 picker（`TutorMistakePickerDialog`）+ 修 D4。
  - **P1-b（2026-09-20）入口收敛**：六条入口落同一个页面——页面标题栏统一成 `TutorRoute.kt`
    里的 `TutorPageHeader`（讲题 + 历史 + 能力设置，可选返回），大厅 / 拍照会话 / 错题讲题
    三处共用，会话页从此也有历史与能力设置入口（此前只有一条"← 讲题"）；空态
    「从错题本选择」不再 `navigate(Routes.Library)`，改成同一页面的 picker
    （`onChooseExisting` 形参删除）；拍照讲解、错题详情「讲解这道题」、复习预判
    （`onRequestTutorPretest`）、判题复核（`onOpenTutorJudge`）、历史重开都带题作为**本轮附件**
    （导航参数：`sessionId` 或 `entryId/problemId/problemRevisionId`）进这个页面，
    底部导航仍停在「智能体」（`bottomBarRouteFor` 未改）。LOBBY/RESPOND 派发契约、egress
    披露、写权限一行未动。
    **仍是三个正文组件**（大厅 / 拍照会话 / 错题会话），即 P1 的"一个屏幕组件 + 一条实时流 +
    一个上下文装配"还没做；会话页加号里仍只有拍照/相册（换题属 P3）。
- **P2 每轮绑定**：候选菜单 + 模型绑定声明（新 wire key + 提示词 + policy 版本）+ 本地校验 + 绑定落消息层（迁移 v47→48）+ 门控三处改按绑定 + 工具声明改全量。
- **P3 跨轮延续与切换**：上一轮绑定作为候选；继续 / 换题由同一套语义校验判定；历史按题分段。
- **P4 多轮 messages 数组**：`[system]+[user 带图]+[assistant]+…`，工具轮用标准 `assistant.tool_calls` + `role:"tool"`（工具配对 + 上游前缀缓存）。

**必须先解决的障碍**

1. **轮次号改"按会话单调"**：会话侧 `responseOrdinal*2-1` 且按题派生，跨题同号会撞唯一槽（`ModelTaskTransactionDao.kt:215-227`）。序号须由会话层统一分配（大厅已是该口径）。
2. **指纹与 wire key**：新字段/新声明必须 strip 空载体 + 升 schema，并进 wire-key 白名单 + bump `ModelPromptPolicyVersions`。
3. **披露两态**写成显式函数并逐态测试（"有题带图"曾占着函数签名的一个分支但三个校验调用点都不可达，已删；题轮在清单路径上永不带图字节）。
4. **答案暴露键**要求 `questionDocumentId+revision+cycle+turn`：有题轮提供四项，无题轮永不产生暴露。
5. **迁移兼容**：旧会话/旧任务行仍可读（迁移 + 指纹用例）。

## 7. 未决 / 待产品确认

- 历史列表仍按会话组织（一会话可含多题，行内标注涉及题）；是否要"按题筛选历史"待定。
- 绑定由模型从候选菜单判（默认）vs 本地静默判定——默认取前者，与既有"模型给语义、本地给数值"一致。
- **无题轮的错题本读取只回条数与检索词**（不列任何条目标题）：错题本条目标题属于
  `RELATED_QUESTION_CANDIDATES`，而大厅的披露集合把它列为禁止，列出来就是清单少报。要不要把这一档
  披露出去（让无题轮也能报出题目名称）是用户的裁定，不是本地的策略选择；真要放宽，须同时改
  `TUTOR_LOBBY_DISCLOSURE` 并按 bf8be888 的纪律升 manifest schema（否则旧行读回即抛异常）。

## 8. 2026-09-21 修复批次（F1~F6）

P2 改造（`4544310b` + `40bf72ff`）落地后，独立复核列出六条发现；当天逐条修复，并由复核者
按同一份清单再核一遍。**六条全部 resolved**，无 partly、无 unresolved。

判定口径统一为一条：**拒绝/放行依据"这一轮有没有题"这个语义事实，而不是"这一轮来自哪条解析
路由"**；每条修复先写会红的用例（或做一次把修复短路掉的变异反证），再改到转绿。门禁由流程
脚本统一跑；修复者侧跑的是模块定向用例 + 编译门，复核者侧跑的是精确 `--tests` 过滤的 JVM
用例与单方法仪器用例（其环境模拟器可用）。

| 提交 | 覆盖 | 一句话 |
|---|---|---|
| `ab98d2c3` | F1 | 写工具准入回退到本轮请求侧已知题锚 |
| `89ddd768` | F2 | 仓库接线的端到端负向用例（设备阶段） |
| `a4681a6d` | F3 | 旧行暴露语义的回归钉住 + 无题轮不暴露双层守卫 |
| `47958b10` | F4/F5/F6 | 披露口径收敛成两态 + 无题轮错题本读取不点名别的题 |

### F1（medium，resolved）原生 `tool_calls` 路由上的写工具/需题读工具被无条件拒

- **现象**：该路由标准形态 `content=null`，模型复述题锚的唯一落点是每次调用的 `arguments`；
  只认"调用自己带声明"时，"模型没复述"就被判成"无题轮"，写工具与两个需题读工具被拒——拒绝
  依据退化成"这一轮来自哪条解析路由"，相对改造前（`input is TutorRespondInput`）是能力回退。
- **处置**：写工具准入 = `TutorRoundQuestionBindingPolicy.callIsAnchoredToRoundQuestion(declaration,
  candidates, studentMessage, knownRoundQuestion)`（`core/domain/.../TutorRoundQuestionBindingPolicy.kt:110-119`）：
  模型声明经两条本地校验优先；声明**缺失**时回退到本轮请求侧已知锚
  `TutorRespondInput.knownRoundQuestion`（`core/model/.../TutorTasks.kt:396`，schema 11→12，
  含两条指纹 strip 与"已知锚必须是本轮候选之一"的构造契约）。仓库门控在
  `core/data/.../RoomModelTaskRepository.kt:836-852` 调用它。声明**在但核不过**不回退
  （说错了 ≠ 没说）。台账同步：`TutorToolLoop.kt` 的 `TutorToolCall.boundQuestion` KDoc、
  `TutorToolGate.kt`、`OpenAiModelProtocol.kt` 的写锚段落与 `nativeToolRoundIntent` 注释。
- **依据（修复者本轮实跑）**：先红后绿——接线前 `TutorToolRoundGateTest` 8 条 1 红（断言
  "有题轮的原生写调用不得因『模型没复述题锚』被拒"），接线后 8/8 绿。反证两条：短路回退分支
  → 绑定政策 1/24 红 + 工具环 1/8 红；从逻辑指纹链去掉新 strip → 旧 v11 行完整性用例 1/18 红；
  均恢复后转绿。
- **复核（resolved）**：模型声明取自调用对象、其余三个入参（`candidates`/`studentMessage`/
  `knownRoundQuestion`）全部取自 `TutorRespondInput`（`RoomModelTaskRepository.kt:835-852`、
  `TutorRoundQuestionBindingPolicy.kt:110-119`），拒绝依据与解析路由无关；实跑
  `./gradlew :core:data:testDebugUnitTest --tests "*TutorToolRoundGateTest" --tests "*RoomTutorToolRunnerTest"`
  → 8/29 条 0 失败，其中原生路由正例走真实 `OpenAiModelProtocol.parseResponse` + 真实
  `tutorToolAuthorization`，负向孪生断言仍拒；陈旧注释全仓无命中（`Respond-only` /
  `the write anchor`）。
- **残留**：生产侧 `knownRoundQuestion` 目前只有"上一轮绑定延续"一个来源
  （`feature/tutor/.../TutorSessionPanel.kt:678-682`），"本轮学生显式添加的题/深链"那一来源
  未接线（属 P3）；会话首轮若模型不复述锚、请求侧也无已知锚，写调用仍会被拒。
- **复核备注（与原始发现文本不符之处）**：F1 原文称"工具 arguments 里也无法表达"其实不成立
  ——严格 schema 早已把 `problemId/problemRevisionId/anchorTerms` 列为写工具的 required
  （`OpenAiModelProtocol.kt:459-469`），解析层也照收（`:547` → `OpenAiModelResponseParsers.kt:600-608`）。
  本批次真正消灭的是"模型没复述"那一半。

### F2（medium，resolved）轮次门控的落地接线没有可运行测试

- **现象**：判定若只长在仓库内部，删掉接线也不会有本机用例变红；仪器用例只覆盖正方向。
- **处置**：判定整体已是 `tutorToolRoundOutcomes`（`core/data/.../RoomModelTaskRepository.kt:828-870`），
  `execute()` 直接调用它（`:340-355`）——`call.tool !in authorizedTools || !available` 一行即接线本身。
  新增设备侧负向用例
  `RoomModelTaskT6MasteryInstrumentedTest.anUnanchoredWriteCallIsRefusedBeforeItReachesTheRunner`
  （Respond 轮、菜单有候选但无题锚：断言 `outcome.ok=false`、`errorKind="not_authorized"`、
  `toolRunner.executedCallCount==0`、无证据行）。
- **依据（修复者本轮实跑）**：删掉 `|| !available` → `TutorToolRoundGateTest` 8 条 5 红（含
  `a write call with no question anchor is refused and never reaches the runner`），恢复后 8/8 绿。
  仓库无法在 JVM 构造（本机现成的假端口对模型任务方法一律 error：
  `RoomBackedStudyExperienceRepositoryTest.kt:1530`、`:1698-1704`），所以"仓库是否调用它"这一半
  由设备用例钉住，本机只过 `:core:data:compileDebugAndroidTestKotlin` 编译门。
- **复核（resolved）**：复核者环境模拟器可用（`emulator-5554`），**实跑**了该仪器用例
  （`connectedDebugAndroidTest` 单方法）→ `Starting 1 tests / Finished 1 tests`、tests=1
  failures=0；JVM 侧 8 条 0 失败。
- **复核备注**："删掉接线会转红"的变异实验复核者未亲自执行（其为只读角色，不得改文件），
  该结论来自断言与代码阅读；修复者提交里报告过同名的变异结果（上文）。

### F3（medium，resolved）旧行的暴露语义回退（升级后不再被认作已暴露）

- **现象**：`canExposeSolutionFor` 无条件要求 `boundQuestion != null` 之后，升级前真的展示过
  完整解答、并已持久化 `RESPOND_REPLY` 暴露的旧行（输出没有该字段）被判成无题轮 → 曝光行被
  候选键过滤，正文被 `HIDDEN_TUTOR_ANSWER_CONTEXT` 顶替，会话记忆的已暴露轮次一起回退。
- **处置**：判据按**行**分岔 `(!requiresRoundQuestionBinding || boundQuestion != null)`
  （`core/model/.../TutorTasks.kt:587-589`），谓词 = `schemaVersion >= 11`
  （`ModelTasks.kt:455`），四处调用点都传本行判据（`TutorExposureDao.kt:301`、
  `TutorChatConversation.kt:119` 与 `:313-314`、`TutorSolutionExposurePolicy.kt:83-84` 与 `:177`）。
  本批次补的是**证据**：行年龄→判据（v10 及更早不受约束、v11/当前受约束）、判据→正文
  （老化到 schema 10 的行正文原样保留，同一输出在新行上被占位顶替）、无题轮既不是候选键也
  生成不出曝光目标。
- **依据（修复者本轮实跑）**：三条变异反证——A 绑定条件改成无条件 → 规范层 1/7 红 + 正文层 1/33 红；
  B 两处 feature 调用点传 `false`（等于按旧语义放行）→ 时间线 1/13 红 + 正文 1/33 红；
  C 谓词漂到 v12 → 新谓词用例 1/8 红；恢复后合计 1498 条 0 失败。
- **复核（resolved）**：核对四个调用点后实跑
  `./gradlew :core:model:test --tests "*TutorSolutionExposureAuthorityTest" --tests "*ModelEgressTest" --tests "*ModelTaskFingerprintStabilityTest"`
  → 8/32/18 条 0 失败；`./gradlew :feature:tutor:testDebugUnitTest --tests "*TutorChatConversationTest" --tests "*TutorConversationTimelineTest"`
  → 20/13 条 0 失败，其中旧行正文保留与新行占位顶替成对、无题轮双守卫生效。
- **复核备注**：红测结论来自用例断言与夹具阅读（复核者未执行变异）。

### F4（resolved）"有题带图"这一态在三个校验调用点不可达

- **处置**：不是让它可达，而是**删掉死分支**：`TutorRoundDisclosure.expected(...)` 已不存在，只剩
  两个具名入口 `noQuestionRound(includesImage, schemaVersion)`（`ModelEgress.kt:103-109`）与
  `questionRound(includesQuestionCandidates, schemaVersion)`（`:117-125`）；第四个调用点同步
  （`TutorLobbyModelTaskPolicy.kt:122-130`）。不合法的组合从此**构造不出来**。
- **依据**：改前实现确有 `includesImage && carriesQuestion` 那个不可达支，且
  `RELATED_QUESTION_CANDIDATES` 仅凭标志无条件添加（`git show 47958b10^:core/model/.../ModelEgress.kt:98-121`）；
  测试只留生产可达组合（被删的那条逐态用例与其原因记在 `ModelEgressTest.kt:525-535`），边界由
  `a question round manifest still refuses image assets` 与新增
  `a lobby manifest cannot claim to cover a candidate menu` 接住。复核者实跑 ModelEgressTest 32 条 0 失败。
- **修复者核对**：本工作树 `grep "fun expected(\|TutorRoundDisclosure.expected"` 无命中。

### F5（resolved）无题轮的错题本读取会点名"别的题"

- **处置**：按"不扩披露"消除——`RoomTutorToolRunner.kt:225-236` 在
  `!context.roundDisclosesQuestionCandidates` 时只回条数与检索词（不含任何标题、科目）；
  该标志默认 `false`（`:127`，fail-closed），由 `input.disclosesQuestionCandidates()` 传入
  （`RoomModelTaskRepository.kt:791-795`，`toolContext` 仅此一处调用点）。
- **依据**：复核者实跑 `./gradlew :core:data:testDebugUnitTest --tests "*RoomTutorToolRunnerTest"`
  → 29 条 0 失败（含 `aRoundThatDoesNotDiscloseOtherQuestionsGetsNoNotebookTitles` 与其反向
  `aRoundThatDisclosesTheCandidateMenuMayStillNameTheEntries`）；设备侧全链路
  `RoomModelTaskToolLoopInstrumentedTest#aLobbyRoundNotebookReadNamesNoEntry` → 1 条 0 失败。
- **残留（复核提醒，未修）**：①原生 tools 路由的 `nativeToolDescription` 未同步这一档
  （`OpenAiModelProtocol.kt:301-302`，修复者核对仍是旧描述）——那条路由上模型只能从结果正文
  得知新形态；②`TUTOR_LOBBY` v6→v7、`TUTOR_RESPOND` v17→v18 的 bump（`ModelEgress.kt:44,47`）
  会让此前已确认的同版本清单在 `requireAuthorizes` 处失效，代价是学生重新确认一次（仓库先例
  `d3b5a8a9` 的口径）。

### F6（resolved）科目名可当锚词

- **处置**：`checkableText()` 只拼 `title` 与题面投影，不含 `subject.name`
  （`core/domain/.../TutorRoundQuestionBindingPolicy.kt:129-133`）。
- **依据**：复核者实跑 `./gradlew :core:domain:test --tests "*TutorRoundQuestionBindingPolicyTest" --tests "*TutorToolGateTest"`
  → 24/5 条 0 失败，含 `a subject name is not a verifiable anchor`（学生写"MATH 这道题再讲一遍"、
  模型拿 `MATH` 当锚词时 `resolve` 必须返回 null）。
- **复核备注**：把 `append(subject.name)` 加回去观察转红的变异实验复核者未执行。

### 批次的未验证项（不当作已通过）

- 仪器化测试（迁移、DAO 落库、UI 曝光流、真机端到端）：修复者本机 adb 不可用，只跑了
  `compileDebugAndroidTestKotlin` 编译门；F2/F5 的设备用例由复核者在模拟器上实跑过，
  其余仪器用例未跑。
- 变异反证的分工：F1/F3 的变异由修复者实跑（上文数字）；F2 的"删接线转红"与 F6 的
  "加回科目名转红"只有断言与阅读结论，无实跑记录。
- 门禁：修复者一轮里出现过一次基础设施失败（`core:data:testDebugUnitTest` 的 `EOFException`，
  双会话抢构建目录），删 `build/test-results/*/binary` 后单跑转绿；整套门禁由流程脚本统一跑。

