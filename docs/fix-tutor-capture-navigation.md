# 修复：讲题页拍照后错误跳转到错题本

## 问题描述

**用户报告：** 在讲题页点击输入框左侧的相机按钮，拍照并提交后，页面跳转到错题本页（底栏高亮"错题本"），而不是进入讲题会话页。

**预期行为：** 从讲题页拍照 → 识别题目 → 进入讲题会话（底栏保持"讲题"）

## 根本原因

`CaptureScreen.kt` 中的 `activeEntryOriginName` 状态使用了 `rememberSaveable`，但 key 参数不完整：

**原代码（第 256-258 行）：**
```kotlin
var activeEntryOriginName by rememberSaveable(resumeDraftId) {
    mutableStateOf(entryOrigin.name)
}
```

### 问题分析

`rememberSaveable(resumeDraftId)` 只以 `resumeDraftId` 作为 key，**没有包含 `entryOrigin` 参数**。

这导致跨配置变化（如屏幕旋转、应用后台恢复）或跨不同入口时，状态污染：

1. 用户从**错题本**拍照 → `activeEntryOriginName = "LIBRARY"`
2. 状态被 `rememberSaveable` 保存
3. 用户返回后，从**讲题页**拍照 → 传入 `entryOrigin = TUTOR`
4. 但 `rememberSaveable` 恢复了旧值 → `activeEntryOriginName` 仍是 `"LIBRARY"`
5. 提交时（第 1033 行）走了 `LIBRARY` 分支：
   ```kotlin
   when (activeEntryOrigin) {
       CaptureEntryOrigin.LIBRARY -> {
           val committed = repository.confirmAndCommit(confirmation)
           committedEntryId = committed.errorBookEntryId  // 跳转到错题详情
       }
       CaptureEntryOrigin.TUTOR -> {
           val session = repository.confirmForTutoring(confirmation)
           onTutorSessionReady(session.sessionId, autoStartAuthorization)  // 应该走这里
       }
   }
   ```
6. 结果：导航到 `mistakeDetail`（错题详情页），底栏高亮"错题本"

## 修复方案

### 修复 1：添加 `entryOrigin` 到 `rememberSaveable` 的 key

**文件：** `feature/capture/src/main/java/com/tingyun/smartmistakebook/feature/capture/CaptureScreen.kt`

**修改位置：** 第 256 行

```kotlin
// 修改前
var activeEntryOriginName by rememberSaveable(resumeDraftId) {
    mutableStateOf(entryOrigin.name)
}

// 修改后
var activeEntryOriginName by rememberSaveable(resumeDraftId, entryOrigin) {
    mutableStateOf(entryOrigin.name)
}
```

**效果：** 当 `entryOrigin` 参数变化时，`rememberSaveable` 会重新初始化状态，不会使用旧值。

### 修复 2：UI 层双重校验，防止错误显示

**文件：** `feature/capture/src/main/java/com/tingyun/smartmistakebook/feature/capture/CaptureScreen.kt`

**修改位置：** 第 1621 行

```kotlin
// 修改前
if (committedEntryId != null) {
    CaptureCommittedCard(
        onView = { onLibraryEntryReady(committedEntryId.orEmpty()) },
        ...
    )
}

// 修改后
if (committedEntryId != null && activeEntryOrigin == CaptureEntryOrigin.LIBRARY) {
    CaptureCommittedCard(
        onView = { onLibraryEntryReady(committedEntryId.orEmpty()) },
        ...
    )
}
```

**效果：** 即使 `committedEntryId` 被意外设置（不应该在 TUTOR 分支发生），UI 也不会显示"查看错题"卡片，避免错误导航。

## 验证计划

### 手动测试场景

1. **基本流程（讲题 → 拍照 → 讲题会话）**
   - 打开应用，进入"讲题"页
   - 点击输入框左侧相机图标
   - 拍照/选择图片
   - 等待识别完成，点击提交
   - ✓ 验证：进入讲题会话页，底栏高亮"讲题"

2. **跨入口切换（错题本 → 讲题）**
   - 从"错题本"页拍照并提交一道题（正常流程）
   - 返回，切换到"讲题"页
   - 点击相机图标，拍照并提交另一道题
   - ✓ 验证：进入讲题会话页，底栏高亮"讲题"

3. **后台恢复（讲题拍照 → 后台 → 恢复）**
   - 从"讲题"页开始拍照流程
   - 在识别页面时，按 Home 键将应用切到后台
   - 等待几秒，重新打开应用
   - 继续提交
   - ✓ 验证：进入讲题会话页，底栏高亮"讲题"

4. **屏幕旋转（讲题拍照 → 旋转 → 提交）**
   - 从"讲题"页拍照
   - 在识别页面时旋转屏幕
   - 继续提交
   - ✓ 验证：进入讲题会话页，底栏高亮"讲题"

### 自动化测试（待编写）

由于当前 Gradle 路径问题导致测试无法运行，建议在修复路径问题后添加以下测试：

```kotlin
@Test
fun `capture from tutor with prior library capture does not pollute entryOrigin`() {
    // 1. 模拟从 LIBRARY 入口拍照并保存状态
    // 2. 重新创建 CaptureScreen 使用 TUTOR 入口
    // 3. 验证 activeEntryOrigin == TUTOR
    // 4. 提交后验证调用的是 onTutorSessionReady 而非 onLibraryEntryReady
}
```

## 已知限制

1. **Gradle 路径问题：** 项目路径 `D:\智能错题本` 包含中文，导致 Gradle worker 启动失败，无法运行单元测试。
2. **测试覆盖率：** 当前缺少针对 `entryOrigin` 跨配置恢复的自动化测试。

## 相关文件

- `feature/capture/src/main/java/com/tingyun/smartmistakebook/feature/capture/CaptureScreen.kt`
- `app/src/main/kotlin/com/tingyun/smartmistakebook/SmartMistakeBookRoot.kt`（导航配置）

## 修复日期

2026-08-14

## 修复人员

Claude (Opus 5)
