# 实施计划：整卷/整页自动切分录入 + 自动分类减少手动 + 移除待办收件箱

## 背景与目标
- 不做 OCR、只依赖模型识别；尽量减少手动操作与判断题（尤其分类）。
- 错题本页整页/PDF 导入 → 模型把一页切成多道题 → 独立「切分确认」界面勾选 → 录入所选 → 自动逐个进入每题确认保存。
- 删除「待办收件箱」（PendingCaptureInbox）列表页；切分确认耗时、中途退出用**悬浮泡泡**返回并带提示。
- 自动分类：一次性全局授权后，详情页对新题自动执行整理（现有 prepare→apply 链路复用）。

## 阶段 1：模型协议升级（页内多题切片输出）
**目标**：让 OpenAI 网关（URV 同层适配器）输出 `CaptureAssessment` schema v3：`decision=SPLIT + MULTIPLE_QUESTIONS issue + questionRegions(2..12 区域框)`。
- 文件（精读后修改）：
  - `core/data/.../model/OpenAiModelProtocol.kt`（243 行，提示词构建/schema 版本）
  - `core/data/.../model/OpenAiModelResponseParsers.kt`（498 行，Capture 解析器：新增 questionRegions 解析、坐标校验、`CaptureAssessment` v3 构造）
  - `core/data/.../model/OpenAiModelTaskAdapters.kt`（411 行，请求适配带上足够 schemaVersion）
  - `core/data/.../model/OpenAiModelTransport.kt`（284 行，如有协议版本传递）
- 兼容策略：模型未返回 regions / 旧响应 → 保持现行为（不切分，按原单题流程）。现有契约校验（`CaptureAssessment` 的 schema v3 require）已保证非法输出失败闭环。
- 测试：解析器单测（含 regions 合法/非法/缺失），`OpenAiModelResponseParsers` 现有测试模式扩展。

## 阶段 2：切分工作台持久化（数据库迁移 v35）
**目标**：为「整卷汇总确认 + 泡泡恢复 + 录入进度」提供持久化状态。
- 新增 Room 实体 + DAO + `StudyDatabasePort` 方法 + 迁移 `SPLIT_IMPORT_JOB_MIGRATION_34_35`：
  - `split_import_job`：jobId、sourceKind(PDF/BATCH/SINGLE_PAGE)、pageCount、createdAt、status(PREPARING/READY/COMPLETED/ABANDONED)、来源（localUri/requestFingerprint）
  - `split_import_question`：jobId、questionOrdinal、pageIndex、region、splitDraftId、selected、confirmState(PENDING/SAVED/TUTOR_SESSION/REJECTED)、resolvedEntryId?
  - 迁移需手工编写 + 导出 schema（`exportSchema=true`，CI 有 drift 检查，务必同步 `core/database/schemas/35.json`）。
- 域接口 `SplitImportRepository`（core:domain）+ Room 实现（core:data）+ 工厂（app 装配）。
- 幂等/恢复：job 由 requestFingerprint 防重；进程被杀后可重读 job 继续。
- 测试：迁移测试、DAO 单测、幂等重放。

## 阶段 3：切分确认界面 + 悬浮泡泡返回入口
- 新增 `feature/library` 或 `feature/capture` 下：
  - `SplitReviewRoute`（Composable）：块列表（题卡：第 N 题 + 块图预览 + 识别文本若有）、勾选/跳过、整卷汇总（PDF job 全部页聚合成一次确认）、底部「录入所选(N)」。
  - 泡泡（悬浮返回入口）：root 层 overlay（`SmartMistakeBookRoot` 顶部）+ 状态（存在未完成 split job → 显示小圆点泡泡，点击回 `SplitReview` 路由，带提示文案「切分还在进行，点此继续」）。泡泡在 job COMPLETED/ABANDONED 后消失。
  - 路由：新增 `Routes.SplitReview`、`Routes.SplitReviewResume`（jobId 参数，泡泡恢复用）；`SmartMistakeBookRoot` 接线。
- 录入编排：点「录入所选」→ 依次打开每题 `CaptureScreen`（resume 该块 splitDraft，REVIEWING 相位）→ 每题确认保存/进讲题 → 自动进入下一题 → 全部完成后 job 标记 COMPLETED、泡泡消失。
- 严格离线（strictOffline）：无模型不产生切分 job，不显示泡泡；整页按原单题流程。
- 测试：Compose 测试（泡泡显隐、勾选/录入流转）、路由测试。

## 阶段 4：删除待办收件箱
- 删除 `PendingCaptureInboxRoute.kt`、`Routes.CaptureInbox`、`SmartMistakeBookRoot` 中 CaptureInbox composable、`LibraryRoute` 的 `onOpenPendingCaptures` 入口、app 路由相关接线。
- `pendingCorrectionCount`（StudyExperienceSnapshot 字段 + `RoomBackedStudyExperienceRepository` 的 pendingDraftObservationJob）处置：保留底层草稿计数逻辑但不展示收件箱入口（或改由泡泡承担“未完成事项”提示）。待精读后定最小改法。
- 未完成切分块/单个草稿的返回入口 = 泡泡（切分 job）+ 捕获页自身恢复（capture/resume，已有）。
- 测试：删除相关的 instrumented/UI 测试更新；确认无残留引用（编译+测试通过）。

## 阶段 5：自动分类减少手动确认
- 一次性全局授权：DataStore 持久化 `organizationAutoRun: Boolean`（默认 true，设置页可关）。
- `MistakeOrganizationSection`：授权开启时，详情页对新题（无任何已接受分类的 revision）自动执行 prepare→apply（现有 LaunchedEffect 链路已具备，改造 `preparationDismissed` 语义 + 移除每次同意卡片，仅在结果不可用/需确认时呈现）；提供撤销/再次整理入口。
- 为保留「本地策略已接受」既有机制：`applySuccessfulOrganization` 路径（thresholds、合并保留、幂等）不改。
- 测试：自动应用幂等、保留用户修正（现有测试扩展）。

## 阶段 6：CI 与验证
- 单元测试：`testLocalFirstDebugUnitTest testStrictOfflineDebugUnitTest`（新增解析器/DAO/工作台/UI 相关）。
- 构建：`assembleLocalFirstDebug assembleStrictOfflineDebug`。
- 数据库：`core:database` 测试 + `git diff --exit-code -- core/database/schemas`（迁移 35 与导出 schema 一致）。
- lint：`lintLocalFirstDebug lintStrictOfflineDebug`。
- 手工验证清单（真机可做项）：PDF 导入→切分确认→录入全卷；切分中退出→泡泡回继续；strictOffline 降级；详情页自动分类。

## 明确不做（本轮）
- 限时整卷训练模式（用户已取消）。
- capture 提交后自动触发分类的后台流水线（二期，本轮仅详情页自动执行）。
- 云同步、变式题生成（用户明确不做）。

## 待精读的接线文件（执行时先读）
`feature/capture/.../CaptureScreen.kt`（maybeSplit 调用点 1047-1112、1361-1363）、`RoomBatchImportRepository`（PDF 导入 → job → 切分钩子）、`RoomCaptureWorkflowRepository.splitDraft`（块落库细节）、`StudyDatabasePort`/DAO/迁移样板、`SmartMistakeBookRoot`（路由与泡泡挂载）、`ListApp`（路由删除波及）、OpenAI 网关四件套、`MistakeOrganizationSection` 授权改造。