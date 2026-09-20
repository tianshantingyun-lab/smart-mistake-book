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
5. **门控按绑定工作**：写工具要求本轮有绑定题；答案暴露要求绑定题 + 学生明确索要；披露集合按"无题 / 有题 / 有题带图"三态**运行时**选择。
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
3. **披露三态**写成显式函数并逐态测试。
4. **答案暴露键**要求 `questionDocumentId+revision+cycle+turn`：有题轮提供四项，无题轮永不产生暴露。
5. **迁移兼容**：旧会话/旧任务行仍可读（迁移 + 指纹用例）。

## 7. 未决 / 待产品确认

- 历史列表仍按会话组织（一会话可含多题，行内标注涉及题）；是否要"按题筛选历史"待定。
- 绑定由模型从候选菜单判（默认）vs 本地静默判定——默认取前者，与既有"模型给语义、本地给数值"一致。
