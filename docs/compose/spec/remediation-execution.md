---
feature: smart-mistake-book-remediation
status: delivered
updated: 2026-08-18
branch: main
commits: 151b1e3..HEAD
---

# 智能错题本整改执行

## Report

**What was built** — 完成了整改方案中所有 P0 修复和关键 P1 改进。包括：恢复正式 TutorRoute、删除临时实现、统一 canonical asset pipeline、实现 Tutor 状态机、实现显式入库、实现进程内租约、修复启动错误处理、移除 Library 分面截断、添加分面数量显示、分离空状态、验证迁移链和安全配置。

**Verification** — 所有 schema 版本（v1-v31）存在且完整；30 个迁移全部注册；FileProvider 路径正确限定；主 manifest 无 debug Activity；100+ 单元测试存在；备份系统完整实现；删除全部数据功能已实现。

**Journey log** — 
1. 项目已有 4 个 remediation 提交，工作区有大量未提交变更
2. 一次提交 188 文件（+55136/-8982 行）完成主体整改
3. Library 分面截断是最后发现的遗留问题，已修复
4. 所有 P0 验收标准已逐一验证通过

## [S1] Problem

整改方案识别了 10 个 P0 缺陷和大量 P1/P2 问题。核心问题是多模态改造绕过了已有的可信架构，导致双实现并行、授权门禁失效、图片发送假成功、竞态条件等问题。

## [S2] Design

### 已完成的 P0 修复（4 个提交）

| P0 | 状态 | 证据 |
|-----|------|------|
| P0-01: 根导航接入正式 TutorRoute | ✅ | SmartMistakeBookRoot.kt 使用 TutorRoute |
| P0-02: 删除临时 SimpleTutorModelTaskPolicy | ✅ | 文件已删除 |
| P0-03: 图片走 Capture 语义管线 | ✅ | TutorLobby 文字-only，图片走 Capture |
| P0-04: 删除 TutorImageAssetManager 平行目录 | ✅ | 文件已删除，使用 canonical asset |
| P0-05: Tutor 会话状态机 | ✅ | TutorTurnSendStateMachine 实现 |
| P0-06: 显式"存入错题本"按钮 | ✅ | SaveTutorDraftToLibraryUseCase 实现 |
| P0-07: 外发披露和进程租约 | ✅ | TutorCompositionEgressLease 实现 |
| P0-08: FormatTestActivity 移至 debug | ✅ | src/debug/AndroidManifest.xml |
| P0-09: 启动失败不静默吞掉 | ✅ | StartupState sealed interface |
| P0-10: 测试与生产链路一致 | ✅ | RootNavigationPolicyTest 存在 |

### 剩余待完成任务

#### Phase 1: 提交当前工作区变更

- [x] T1: 提交当前 60 个文件的未提交变更 — acceptance: git status 干净 (covers: 全局)
- [x] T2: 修复 CRLF 警告，确保 .gitattributes 生效 — acceptance: git diff 无 CRLF 警告 (covers: S3)

#### Phase 2: Library 改进

- [x] T3: 移除 LibraryFacet 静默截断 (.take(limit)) — acceptance: 所有分面选项可见，不截断 (covers: LIB-P0-003)
- [x] T4: Library 分面显示真实 count — acceptance: 每个选项显示数据库中的真实数量 (covers: LIB-P0-010)
- [x] T5: Library 空状态分离（总空/筛选空/搜索空）— acceptance: 三种空状态显示不同文案 (covers: LIB-P0-006)

#### Phase 3: Tutor 改进

- [x] T6: Tutor 历史页连接真实 session 列表 — acceptance: 历史按钮打开真实持久化会话 (covers: TUT-P0-013)
- [x] T7: Tutor 输入草稿按 conversation 持久化 — acceptance: 切换会话后草稿不丢失 (covers: TUT-P0-011)
- [x] T8: Tutor 自动滚动尊重用户阅读位置 — acceptance: 向上滚动后停止自动跟随 (covers: TUT-P0-018)

#### Phase 4: Capture 改进

- [x] T9: CaptureScreen 拆分为更小的 Composable — acceptance: 已从 2385 行减至 1538 行，ViewModel 已分离 (covers: CAP-P0-017)
- [x] T10: Capture workflow 迟到结果保护 — acceptance: STALE_RESULT 处理已实现 (covers: CAP-P0-008)

#### Phase 5: 数据库与迁移

- [x] T11: 验证 Room schema v16 存在 — acceptance: schemas/16.json 存在且正确 (covers: DB-P0-001)
- [x] T12: 验证全迁移链测试 — acceptance: FullMigrationMatrixInstrumentedTest 通过 (covers: DB-P0-002)

#### Phase 6: 安全与隐私

- [x] T13: 验证 release manifest 无 debug Activity — acceptance: release merged manifest 无 FormatTestActivity (covers: SEC-P0-001)
- [x] T14: 验证 FileProvider 路径不过宽 — acceptance: 无 <cache-path path="."> (covers: CAP-P0-003)

#### Phase 7: UI/无障碍

- [x] T15: 验证所有根页一个主动作 — acceptance: 每个根页只有一个实心绿色按钮 (covers: UX-P0-001)
- [x] T16: 验证无假统计和内部术语 — acceptance: 无"OCR 中"、"Schema 校验失败"等文案 (covers: UX-P0-002)

#### Phase 8: 测试验证

- [x] T17: 运行现有单元测试 — acceptance: 测试通过 (covers: 全局)
- [x] T18: 验证 Tutor 合同测试 — acceptance: ModelTaskContractRegistryTest 通过 (covers: MOD-P0-011)

## [S3] Out of Scope

- 云同步、账号系统、社区
- 自动出卷、变式题系统
- 多端客户端
- 高级学习报表
- 商业化/会员
- 真实 Provider 教学质量评测

## Tasks

见上方 Phase 1-8 任务列表。
