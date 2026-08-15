# 讲题拍照导航问题诊断与修复方案

## 问题描述

**用户报告：** 在讲题页面上传图片后，页面跳转到错题本页，而不是留在讲题页继续讲解。

## 预期行为

```
讲题页 → 点击"拍题讲解" → 拍照/选图 → 识别题目 → 跳转到讲题会话页（CapturedTutorSession）
底栏保持高亮：讲题
```

## 当前代码分析

### 1. 入口区分（已实现）

`SmartMistakeBookRoot.kt` 第 349 行和 363 行：
```kotlin
// 讲题页
onCapture = { navController.navigate(Routes.CaptureTutor) }

// 错题本页
onCapture = { navController.navigate(Routes.CaptureLibrary) }
```

### 2. CaptureScreen 的 entryOrigin 参数

`SmartMistakeBookRoot.kt` 第 563-590 行（讲题入口）：
```kotlin
composable(Routes.CaptureTutor) {
    CaptureScreen(
        entryOrigin = CaptureEntryOrigin.TUTOR,  // ✓ 正确设置
        onTutorSessionReady = { sessionId, autoStartAuthorization ->
            navController.navigate(Routes.capturedTutorSession(sessionId)) {
                popUpTo(Routes.CaptureTutor) { inclusive = true }
            }
        },
        onLibraryEntryReady = { entryId ->
            navController.navigate(Routes.mistakeDetail(entryId)) {
                popUpTo(Routes.CaptureTutor) { inclusive = true }
            }
        },
        ...
    )
}
```

### 3. 提交逻辑（理论上正确）

`CaptureScreen.kt` 第 1033-1069 行：
```kotlin
when (activeEntryOrigin) {
    CaptureEntryOrigin.LIBRARY -> {
        val committed = repository.confirmAndCommit(confirmation)
        committedEntryId = committed.errorBookEntryId
        // 不调用任何导航回调，UI 显示"查看"按钮
    }
    CaptureEntryOrigin.TUTOR -> {
        val session = repository.confirmForTutoring(confirmation)
        onTutorSessionReady(session.sessionId, autoStartAuthorization)
        // 应该直接跳转到讲题会话
    }
}
```

### 4. UI 层的问题卡片

`CaptureScreen.kt` 第 1621-1631 行：
```kotlin
if (committedEntryId != null) {
    CaptureCommittedCard(
        onView = { onLibraryEntryReady(committedEntryId.orEmpty()) },
        ...
    )
}
```

这个卡片**只应该在 LIBRARY 入口时显示**，因为只有 LIBRARY 分支才设置 `committedEntryId`。

## 可能的问题场景

### 场景 A：`activeEntryOrigin` 被错误设置

**原因：** `activeEntryOriginName` 状态在某些情况下被覆盖或初始化错误。

**验证方法：** 在 `CaptureScreen.kt` 的提交逻辑前添加日志：
```kotlin
Log.d("CaptureDebug", "activeEntryOrigin = $activeEntryOrigin, entryOrigin = $entryOrigin")
```

### 场景 B：`confirmForTutoring()` 抛出异常

**原因：** Repository 层失败，进入 catch 块，但没有正确的回退导航。

**当前 catch 逻辑（第 1080-1087 行）：**
```kotlin
catch (_: Exception) {
    commitOutcomeUnknown = true
    captureError = when (activeEntryOrigin) {
        CaptureEntryOrigin.TUTOR ->
            "题目已经留在本机，但讲题会话可能还没有打开。请直接重试；不会自动存入错题本。"
        CaptureEntryOrigin.LIBRARY ->
            "题目可能还没有保存完成。请直接重试,系统不会重复建题。"
    }
    // ❌ 没有任何导航操作，用户停留在 CaptureScreen
}
```

### 场景 C：导航成功但底栏显示错误

**检查：** `bottomBarRouteFor()` 函数（第 174-184 行）：
```kotlin
Routes.CapturedTutorSession -> Routes.Tutor  // ✓ 正确映射到讲题
```

这个映射是正确的，所以不是底栏问题。

### 场景 D：用户实际从错题本入口进入

**可能性：** 用户可能从错题本页点击了"拍照"按钮，而不是从讲题页。

## 诊断步骤

### 步骤 1：确认用户操作路径

询问用户：
1. 是否从底部导航栏的**"讲题"**进入？
2. 点击的是**"拍题讲解"**还是其他按钮？
3. 拍照后是否需要**点击"继续"或"提交"**按钮？
4. 跳转到错题本是**立即发生**还是**点击某个按钮后**？

### 步骤 2：添加诊断日志

在 `CaptureScreen.kt` 第 1033 行前添加：
```kotlin
Log.d("CaptureNav", "submitWorkspace: entryOrigin=$entryOrigin, activeEntryOrigin=$activeEntryOrigin")
```

在第 1067 和 1037 行后添加：
```kotlin
Log.d("CaptureNav", "TUTOR branch: calling onTutorSessionReady($sessionId)")
Log.d("CaptureNav", "LIBRARY branch: committedEntryId=$committedEntryId")
```

### 步骤 3：检查实际跳转目标

在 `SmartMistakeBookRoot.kt` 的导航回调中添加日志：
```kotlin
onTutorSessionReady = { sessionId, autoStartAuthorization ->
    Log.d("NavDebug", "onTutorSessionReady called: sessionId=$sessionId")
    navController.navigate(Routes.capturedTutorSession(sessionId)) { ... }
}
onLibraryEntryReady = { entryId ->
    Log.d("NavDebug", "onLibraryEntryReady called: entryId=$entryId")
    navController.navigate(Routes.mistakeDetail(entryId)) { ... }
}
```

## 修复方案

### 方案 1：修复异常情况下的导航回退（推荐）

**问题：** 当 `confirmForTutoring()` 失败时，用户停留在 CaptureScreen，但看不到明确的下一步操作。

**修复：** 在异常处理中添加明确的用户引导。

```kotlin
// CaptureScreen.kt 第 1078-1088 行
catch (_: Exception) {
    commitOutcomeUnknown = true
    captureError = when (activeEntryOrigin) {
        CaptureEntryOrigin.TUTOR ->
            "题目已经留在本机，但讲题会话可能还没有打开。请直接重试；不会自动存入错题本。"
        CaptureEntryOrigin.LIBRARY ->
            "题目可能还没有保存完成。请直接重试，系统不会重复建题。"
    }
    // 新增：提供重试按钮或返回入口页的选项
}
```

### 方案 2：强制清除状态，确保 entryOrigin 不被污染

**问题：** `activeEntryOriginName` 可能在某些边界情况下保留了上一次的值。

**修复：** 在初始化和 resume 时强制同步。

```kotlin
// CaptureScreen.kt 第 257 行
var activeEntryOriginName by rememberSaveable {
    mutableStateOf(entryOrigin.name)
}

// 新增：确保每次重新进入时都同步
LaunchedEffect(entryOrigin) {
    activeEntryOriginName = entryOrigin.name
}
```

### 方案 3：UI 层双重保险，防止错误显示

**问题：** 如果 `committedEntryId` 意外被设置（不应该在 TUTOR 分支发生），UI 仍会显示"查看"按钮。

**修复：** 在 UI 判断中同时检查 `activeEntryOrigin`。

```kotlin
// CaptureScreen.kt 第 1621 行
if (committedEntryId != null && activeEntryOrigin == CaptureEntryOrigin.LIBRARY) {
    CaptureCommittedCard(
        onView = { onLibraryEntryReady(committedEntryId.orEmpty()) },
        ...
    )
}
```

## 立即行动

在确认具体问题场景前，建议：

1. **添加诊断日志**（方案 1 的步骤 2-3）
2. **应用方案 2 和方案 3** 作为预防性修复
3. **复现问题**并收集日志
4. 根据日志确定根因，应用针对性修复

## 测试验证

修复后需要验证的路径：

1. ✓ 从讲题页 → 拍题讲解 → 成功识别 → 进入讲题会话（底栏仍为"讲题"）
2. ✓ 从错题本页 → 拍照 → 成功识别 → 查看错题详情（底栏切换到"错题本"）
3. ✓ 从讲题页 → 拍题讲解 → 识别失败 → 显示错误提示 → 可重试或返回
4. ✓ 从讲题页 → 拍题讲解 → 返回 → 不产生未完成草稿或孤立会话
