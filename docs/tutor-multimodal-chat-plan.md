# 讲题多模态聊天改造 - 实施计划

## 目标
将讲题改造成 ChatGPT/微信风格的多模态聊天，支持图片发送和 AI 控制错题本。

---

## 阶段 1：数据层改造

### 1.1 扩展 TutorLobbyInput（支持图片消息）

**文件：** `core/model/src/main/kotlin/.../TutorLobbyInput.kt`

**新增字段：**
```kotlin
data class TutorLobbyInput(
    val conversationId: String,
    val messageOrdinal: Int,
    val studentMessage: String,  // 保留（用于纯文字消息）
    val imageUris: List<String> = emptyList(),  // 新增：图片 URI 列表
    val messageType: TutorMessageType = TutorMessageType.TEXT,  // 新增：消息类型
    val occurredAtEpochMillis: Long,
    val approvedAtEpochMillis: Long,
) : ModelInput

enum class TutorMessageType {
    TEXT,        // 纯文字
    IMAGE,       // 纯图片
    MIXED        // 文字+图片
}
```

### 1.2 新增 "加入错题本" 工具定义

**文件：** `core/model/src/main/kotlin/.../TutorToolCall.kt`（新建）

```kotlin
sealed class TutorToolCall {
    data class SaveToMistakeLibrary(
        val practiceUnitId: String,
        val reason: String?  // AI 说明为什么建议加入
    ) : TutorToolCall()
}

data class TutorLobbyOutput(
    val messageMarkdown: String,
    val intentDecision: TutorLocalIntentDecision?,
    val toolCalls: List<TutorToolCall> = emptyList(),  // 新增
)
```

### 1.3 扩展 Repository 接口

**文件：** `core/domain/src/main/kotlin/.../ModelTaskRepository.kt`

```kotlin
interface ModelTaskRepository {
    // 现有方法...
    
    // 新增：处理图片消息
    suspend fun executeTutorLobbyWithImage(
        input: TutorLobbyInput,
        imageUris: List<String>,
    ): Flow<ModelTaskSnapshot>
    
    // 新增：执行工具调用
    suspend fun executeTutorToolCall(
        toolCall: TutorToolCall
    ): ToolCallResult
}

sealed class ToolCallResult {
    data class Success(val message: String) : ToolCallResult()
    data class Failure(val error: String) : ToolCallResult()
}
```

---

## 阶段 2：UI 层改造

### 2.1 改造 TutorComposer（输入框支持图片）

**文件：** `feature/tutor/src/main/java/.../TutorRoute.kt`

**改造点：**
1. 添加 "图片附件" 状态
2. 相机按钮点击 → 打开相机/相册
3. 选择图片后显示缩略图预览
4. 发送按钮支持发送文字+图片

**新增状态：**
```kotlin
var attachedImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
```

**UI 结构：**
```
[图片预览区域]（如果有附件）
  [缩略图1] [x] [缩略图2] [x] ...

[输入框]
  [📷 相机] [📎 附件] [文本输入...] [➤ 发送]
```

### 2.2 改造 TutorLobbyRoute（对话流显示图片消息）

**文件：** `feature/tutor/src/main/java/.../TutorLobbyRoute.kt`

**新增 Composable：**
```kotlin
@Composable
private fun TutorLobbyImageMessage(
    imageUris: List<String>,
    caption: String?,
    modifier: Modifier = Modifier,
) {
    // 显示图片网格 + 可选文字说明
}
```

### 2.3 移除讲题页到 CaptureScreen 的导航

**文件：** `app/src/main/kotlin/.../SmartMistakeBookRoot.kt`

**修改：**
```kotlin
// 删除这个路由
// composable(Routes.CaptureTutor) { CaptureScreen(...) }

// 讲题页的 onCapture 改为打开相机选择器
onCapture = { 
    // 不再导航到 CaptureScreen
    // 改为直接打开系统相机/相册选择器
    launchImagePicker()
}
```

---

## 阶段 3：后端/模型层改造

### 3.1 图片 OCR 集成

**文件：** `core/data/src/main/kotlin/.../RoomModelTaskRepository.kt`

**实现：**
```kotlin
override suspend fun executeTutorLobbyWithImage(
    input: TutorLobbyInput,
    imageUris: List<String>,
): Flow<ModelTaskSnapshot> = flow {
    // 1. 对每张图片执行 OCR（复用现有 OCR 逻辑）
    val ocrResults = imageUris.map { uri ->
        executeOCR(uri) // 复用 CaptureScreen 的 OCR
    }
    
    // 2. 构建 Prompt（包含 OCR 结果）
    val prompt = buildTutorPromptWithImages(
        studentMessage = input.studentMessage,
        ocrResults = ocrResults
    )
    
    // 3. 调用模型
    val response = modelClient.chat(prompt)
    
    // 4. 解析工具调用
    val toolCalls = parseToolCalls(response)
    
    emit(ModelTaskSnapshot(...))
}
```

### 3.2 工具调用执行

**文件：** `core/data/src/main/kotlin/.../RoomModelTaskRepository.kt`

```kotlin
override suspend fun executeTutorToolCall(
    toolCall: TutorToolCall
): ToolCallResult {
    return when (toolCall) {
        is TutorToolCall.SaveToMistakeLibrary -> {
            try {
                // 调用现有的保存错题逻辑（演示种子保存接口已按审计 9.2/PR-05 删除，改用正式保存命令）
                saveTutorProblem(saveCommandFor(toolCall.practiceUnitId))
                ToolCallResult.Success("已加入错题本")
            } catch (e: Exception) {
                ToolCallResult.Failure("加入失败：${e.message}")
            }
        }
    }
}
```

---

## 阶段 4：错题本页保持独立录入

**不改动：**
- `Routes.CaptureLibrary` 保持不变
- 错题本页的拍照按钮继续使用 `CaptureScreen`
- 批量识别、校对流程保持现有逻辑

---

## 实施顺序

### 第 1 步：数据层（可独立验证）
1. 扩展 `TutorLobbyInput` 支持图片
2. 新增工具调用定义
3. 编写单元测试验证数据结构

### 第 2 步：UI 基础改造
1. 改造 `TutorComposer`，添加图片选择
2. 显示图片预览和删除
3. 修改发送逻辑，支持图片

### 第 3 步：后端集成
1. 实现图片 OCR 调用
2. 实现工具调用执行
3. 集成到对话流

### 第 4 步：导航清理
1. 移除讲题页到 `CaptureScreen` 的路由
2. 修改相机按钮行为
3. 验证错题本页的独立录入不受影响

### 第 5 步：端到端测试
1. 讲题页发送图片 → AI 讲解
2. 用户说 "加入错题本" → AI 执行
3. 错题本页批量录入仍正常工作

---

## 风险与注意事项

### 风险 1：OCR 复用
- **问题：** 现有 OCR 逻辑在 `CaptureScreen` 中，需要解耦
- **方案：** 提取 OCR 为独立的 `OcrService`，两边都调用

### 风险 2：图片存储
- **问题：** 图片 URI 需要持久化（用户退出重进后仍能看到）
- **方案：** 图片复制到应用私有目录，保存路径到数据库

### 风险 3：工具调用的 Prompt 设计
- **问题：** 模型需要明确知道何时调用 "加入错题本"
- **方案：** 
  - 用户明确说 "加入错题本" → 高置信度调用
  - 讲解结束后 → AI 询问 "是否加入错题本？"

### 风险 4：多图片消息的显示
- **问题：** 一次发送多张图片，如何显示？
- **方案：** 网格布局，最多 3x3，超过则滚动

---

## 成功标准

### 功能完整性
- ✅ 讲题页能发送图片消息
- ✅ AI 能识别图片中的题目并讲解
- ✅ 用户说 "加入错题本"，AI 能成功执行
- ✅ 讲解结束后，AI 主动询问是否加入
- ✅ 错题本页的批量录入不受影响

### 用户体验
- ✅ 拍照后图片立即显示在输入框
- ✅ 点击发送后，对话流立即显示用户的图片消息
- ✅ AI 响应自然（不显式说 "我识别到..."）
- ✅ 加入错题本有明确反馈

### 技术质量
- ✅ 无内存泄漏（图片及时释放）
- ✅ 网络失败有重试机制
- ✅ 离线时有明确提示
- ✅ 所有改动有单元测试

---

## 时间估算

| 阶段 | 工作量 | 风险 |
|------|--------|------|
| 数据层改造 | 2-3 小时 | 低 |
| UI 基础改造 | 3-4 小时 | 中 |
| 后端集成 | 4-5 小时 | 高（OCR 复用） |
| 导航清理 | 1-2 小时 | 低 |
| 端到端测试 | 2-3 小时 | 中 |
| **总计** | **12-17 小时** | |

---

## 下一步

请确认方案无误后，我开始按顺序实施。

第一步：扩展 `TutorLobbyInput` 数据结构，需要我现在开始吗？
