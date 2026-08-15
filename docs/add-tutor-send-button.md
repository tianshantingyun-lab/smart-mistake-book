# 讲题拍照"发送"按钮缺失问题 - 完整解决方案

## 问题确认

### 用户报告
从讲题页点相机图标 → 拍照 → 识别完成后 → **没有明确的"发送"或"开始讲解"按钮**

### 截图分析
识别完成后显示：
- ⏱️ "正在准备这道题"
- 💬 "原图已经保存，完成后会显示题面。"
- ❌ **没有任何可点击的按钮让用户主动触发讲解**

### 根本问题
当前设计是**完全自动流程**：
1. 拍照 → 自动保存
2. 自动 OCR 识别 → 自动整理题面
3. 识别完成后 → 自动提交并进入讲题会话

**用户体验问题：**
- 没有主动控制权
- 不知道什么时候能进入讲解
- 和预期的"聊天机器人"体验不一致（应该有"发送"按钮）

---

## 产品需求

### 期望的交互流程

```
[讲题页]
用户点击相机 → 拍照 → 识别题目
                      ↓
        [显示识别结果 + 大按钮："开始讲解"]
                      ↓
        用户点击"开始讲解" → 进入讲题会话
```

### 设计原则

1. **即时反馈**：识别完成立即显示可操作的按钮
2. **主动控制**：用户决定何时开始讲解，而非被动等待
3. **ChatBot 体验**：符合用户对聊天工具的认知（发送消息 = 主动触发）

---

## 技术实现方案

### 方案 1：在 CaptureModelTaskCard 中添加"开始讲解"按钮（推荐）

**修改文件：**
- `feature/capture/src/main/java/com/tingyun/smartmistakebook/feature/capture/CaptureModelTaskCard.kt`

**实现：**

在 `CaptureModelTaskCard` 函数中，当识别成功后显示"开始讲解"按钮：

```kotlin
@Composable
internal fun CaptureModelTaskCard(
    snapshot: ModelTaskSnapshot?,
    onRetry: () -> Unit,
    onOpenModelSettings: () -> Unit,
    parseSnapshot: ModelTaskSnapshot? = null,
    onRetryParse: () -> Unit = {},
    onRetake: () -> Unit = {},
    onAddPage: () -> Unit = {},
    splitInProgress: Boolean = false,
    splitError: String? = null,
    onRetrySplit: () -> Unit = {},
    onStartTutor: () -> Unit = {},  // 新增：开始讲解回调
    modifier: Modifier = Modifier,
) {
    // ... 现有逻辑 ...
    
    val parseSucceeded = parseSnapshot?.status == ModelTaskStatus.SUCCEEDED
    
    Column(/* ... */) {
        // ... 现有 UI ...
        
        // 新增：识别成功后显示"开始讲解"按钮
        if (parseSucceeded && !splitInProgress && splitError == null) {
            PrimaryActionButton(
                text = "开始讲解",
                onClick = onStartTutor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .testTag("capture_start_tutor_button"),
                contentDescription = "确认题目并开始讲解",
            )
        }
        
        // ... 其他按钮（重试、设置等）...
    }
}
```

**修改 CaptureScreen 调用：**

在 `CaptureScreen.kt` 中，调用 `CaptureModelTaskCard` 时传入 `onStartTutor` 回调：

```kotlin
CaptureModelTaskCard(
    snapshot = assessmentSnapshot,
    parseSnapshot = parseSnapshot,
    onRetry = { /* ... */ },
    onStartTutor = {
        // 触发提交并进入讲题会话
        coroutineScope.launch {
            submitWorkspaceAndNavigate()
        }
    },
    // ... 其他参数 ...
)
```

**自动提交逻辑改为手动触发：**

移除当前的自动提交逻辑，改为用户点击"开始讲解"按钮时才提交。

---

### 方案 2：底部固定"开始讲解"按钮（更简洁）

**实现：**

在 `CaptureScreen` 的底部添加固定按钮栏：

```kotlin
@Composable
fun CaptureScreen(/* ... */) {
    val parseSucceeded = parseSnapshot?.status == ModelTaskStatus.SUCCEEDED
    
    Scaffold(
        bottomBar = {
            if (parseSucceeded && committedEntryId == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Paper)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    PaperDivider()
                    PrimaryActionButton(
                        text = "开始讲解",
                        onClick = { submitWorkspaceAndNavigate() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("capture_start_tutor_button"),
                        contentDescription = "确认题目并开始讲解",
                    )
                }
            }
        }
    ) {
        // 现有的 CaptureScreen 内容
    }
}
```

**优点：**
- 按钮位置固定，更容易找到
- 不会被滚动内容遮挡
- 符合移动端"底部主操作按钮"的常见模式

---

## 推荐方案：方案 2（底部固定按钮）

### 理由
1. **更符合移动端习惯**：底部固定的主操作按钮是微信、ChatGPT 等应用的标准模式
2. **可见性更高**：不会因为页面滚动而被遮挡
3. **实现更简单**：不需要修改 `CaptureModelTaskCard` 的复杂逻辑

---

## 实现步骤

### 第 1 步：添加提交函数（如果不存在）

在 `CaptureScreen.kt` 中确保有明确的提交函数：

```kotlin
fun submitWorkspaceAndNavigate() {
    coroutineScope.launch {
        workflowInProgress = true
        try {
            val currentWorkspace = workspaceState ?: return@launch
            val finalizedWorkspace = prepareCaptureCommitAttempt(
                workspace = currentWorkspace,
                workspaceUpdatedAtEpochMillis = workspaceUpdatedAtEpochMillis,
            )
            workspaceState = finalizedWorkspace
            
            val confirmation = ConfirmCapturedProblemRequest(
                draftId = draftId!!,
                workspaceIdentity = workspaceIdentity!!,
            )
            
            when (activeEntryOrigin) {
                CaptureEntryOrigin.TUTOR -> {
                    val session = repository.confirmForTutoring(confirmation)
                    onTutorSessionReady(session.sessionId, null)
                }
                CaptureEntryOrigin.LIBRARY -> {
                    val committed = repository.confirmAndCommit(confirmation)
                    committedEntryId = committed.errorBookEntryId
                }
            }
        } catch (e: Exception) {
            captureError = "提交失败，请重试"
        } finally {
            workflowInProgress = false
        }
    }
}
```

### 第 2 步：添加底部按钮栏

修改 `CaptureScreen` 的 UI 结构，添加 `Scaffold` 和 `bottomBar`。

### 第 3 步：移除自动提交逻辑

找到并注释掉当前的自动提交 `LaunchedEffect`。

### 第 4 步：测试验证

- ✓ 拍照后能看到"开始讲解"按钮
- ✓ 点击按钮后进入讲题会话（不跳到错题本）
- ✓ 按钮在页面底部固定，不会滚动遮挡

---

## 后续优化建议

### 1. 添加快捷"重拍"按钮

```
[重拍] [开始讲解]
```

让用户能快速重拍，无需返回。

### 2. 支持编辑题目后再讲解

如果 OCR 识别错误，用户可以：
1. 点击题目文本 → 进入编辑模式
2. 修改后 → 点击"开始讲解"

### 3. 显示识别置信度

```
✓ 题面已准备好（识别准确度：95%）
[开始讲解]
```

让用户对识别结果有信心。

---

## 需要立即实施的修改

由于这是一个**阻断性的体验问题**（用户无法正常使用拍照讲题功能），建议：

1. **立即实施方案 2**（底部固定按钮）
2. 编译并测试
3. 验证从讲题页拍照 → 点击"开始讲解" → 进入讲题会话

**需要我现在开始实现吗？** 我会：
1. 修改 `CaptureScreen.kt`
2. 添加底部"开始讲解"按钮
3. 确保点击后正确进入讲题会话（不跳到错题本）
