# 讲题多模态聊天改造 - 当前状态总结

## ✅ 已完成的工作（约25%进度）

### 1. 数据层改造 ✅
- ✅ `TutorLobbyInput` 支持 `imageAssetRefs`
- ✅ `TutorLobbyOutput` 支持 `suggestedAction`（用于AI建议加入错题本）
- ✅ `TutorChatHistoryEntry` 支持 `studentImageAssetRefs`
- ✅ `TutorRespondInput` 支持 `studentImageAssetRefs`
- ✅ 所有新增字段都有验证逻辑

### 2. UI层基础改造 ✅
- ✅ `TutorComposer` 组件支持图片附件
  - 图片预览区域
  - 相机/相册选择按钮
  - 图片删除功能
  - 发送按钮支持纯图片消息
- ✅ `ImageAttachmentButtons` 组件（相机/相册菜单）
- ✅ `ImageAttachmentPreview` 组件（图片缩略图+删除）
- ✅ `TutorLobbyRoute` 状态管理
  - `attachedImages` 状态
  - 图片附加/移除逻辑
  - `submitDraft` 支持图片（但未实际发送）

### 3. 修改的文件清单
- ✅ `core/model/.../TutorLobbyTasks.kt`
- ✅ `core/model/.../TutorTasks.kt`
- ✅ `feature/tutor/.../TutorRoute.kt`
- ✅ `feature/tutor/.../TutorLobbyRoute.kt`

---

## ⏳ 剩余的关键工作（约75%进度）

### 阶段 4：后端集成（4-5小时）
**关键任务：**
1. **OCR逻辑解耦** - 从`CaptureScreen`提取OCR为独立服务
2. **图片存储管理** - 复制用户图片到私有目录，生成资产ID
3. **实现图片发送** - 修改`startMessage`函数，将图片URI转为资产引用
4. **Prompt构建** - 将OCR结果注入模型输入
5. **工具调用执行** - 实现`SaveToLibrary`操作

**文件：**
- `core/data/.../RoomModelTaskRepository.kt`
- 新建：`core/domain/.../OcrService.kt`
- 新建：`core/domain/.../ImageAssetManager.kt`

### 阶段 5：Prompt设计（1-2小时）
**关键任务：**
1. 设计多模态Prompt模板（图片+OCR+文字）
2. 设计"加入错题本"触发规则
3. 测试模型理解准确性

### 阶段 6：导航清理（1-2小时）
**关键任务：**
1. 移除`Routes.CaptureTutor`路由
2. 修改`onCapture`不再导航到`CaptureScreen`
3. 验证错题本独立录入不受影响

### 阶段 7：端到端测试（2-3小时）
**关键任务：**
1. 完整流程测试
2. 边界情况测试（网络失败、OCR失败等）
3. 性能测试（多图片加载）

---

## 🔴 当前阻塞点

### 问题1：图片未实际发送
**现状：** UI上能选择图片，但`submitDraft`函数还未真正发送图片

**原因：**
- `startMessage` 函数目前只接收文字
- 需要图片URI → 资产引用的转换逻辑
- 需要OCR服务来识别图片

**解决方案：**
```kotlin
fun startMessage(message: String, imageUris: List<Uri>, approvedAtEpochMillis: Long) {
    scope.launch {
        // 1. 复制图片到私有目录
        val assetRefs = imageUris.map { uri ->
            imageAssetManager.saveImage(uri)
        }
        
        // 2. 对图片执行OCR
        val ocrResults = assetRefs.map { ref ->
            ocrService.recognizeImage(ref)
        }
        
        // 3. 构建包含OCR结果的Prompt
        val enrichedMessage = buildMessageWithImages(message, ocrResults)
        
        // 4. 构建请求
        val request = buildTutorLobbyRequest(
            provider = currentProvider,
            messageOrdinal = nextOrdinal,
            studentMessage = enrichedMessage,
            imageAssetRefs = assetRefs,
            ...
        )
        
        // 5. 发送
        modelTasks.execute(request).collect()
    }
}
```

### 问题2：OCR服务不存在
**现状：** OCR逻辑在`CaptureScreen`中，耦合度高

**需要做：**
1. 提取OCR为独立服务
2. 两边都调用这个服务
3. 确保不破坏错题本录入流程

### 问题3：图片资产管理不存在
**现状：** 没有图片存储、引用生成、清理机制

**需要做：**
1. 创建`ImageAssetManager`
2. 实现图片复制、ID生成、清理
3. 持久化到数据库

---

## 💡 下一步建议

### 选项 A：继续完成全部改造（需要连续10-15小时）
**优点：** 一次性彻底解决，完整的ChatGPT式体验
**缺点：** 工作量大，需要长时间专注

**步骤：**
1. 创建`OcrService`（2小时）
2. 创建`ImageAssetManager`（2小时）
3. 修改`TutorLobbyRoute`的`startMessage`（1小时）
4. 实现Prompt构建（1小时）
5. 实现工具调用（1小时）
6. 导航清理（1小时）
7. 测试验证（3小时）

### 选项 B：先解决原始问题，长期改造分批完成
**优点：** 快速解决用户当前的阻塞问题
**缺点：** 多模态聊天需要分多个迭代

**立即修复（1小时）：**
1. 回退刚才的多模态改造
2. 在`CaptureScreen`添加"开始讲解"按钮（修复原始bug）
3. 用户能正常拍照讲题

**后续迭代：**
- 迭代1：OCR服务解耦
- 迭代2：图片资产管理
- 迭代3：多模态消息
- 迭代4：AI控制错题本

### 选项 C：暂停并记录当前进度
**优点：** 保留已完成的工作，稍后继续
**缺点：** 当前代码不可运行（编译会通过但功能不完整）

---

## 🎯 我的建议

考虑到：
1. 当前已完成25%的工作
2. 剩余工作需要10-15小时
3. 涉及复杂的OCR解耦和图片管理

**建议：选项 B - 先快速修复，长期分批改造**

**理由：**
- 用户当前无法使用拍照讲题功能（阻塞问题）
- 多模态改造是"锦上添花"，不是"雪中送炭"
- 分批改造风险更低，每个迭代都能保持功能可用

---

## 📋 如果选择继续（选项A）

我会立即开始创建`OcrService`和`ImageAssetManager`，预计需要10-15小时完成全部工作。

**请确认：是否继续完整改造？**

---

## 📋 如果选择快速修复（选项B）

我会：
1. 暂存当前的多模态改造代码（创建feature分支）
2. 回退到修复前的状态
3. 添加"开始讲解"按钮到`CaptureScreen`
4. 验证用户能正常使用拍照讲题

**预计时间：1小时**

---

## 当前代码状态

**编译状态：** ✅ 应该能编译通过
**运行状态：** ⚠️ UI改造完成，但图片不会实际发送（点发送按钮只发送文字）
**功能完整性：** 25%

**如果现在编译运行：**
- ✅ 用户能看到相机按钮的下拉菜单（拍照/相册）
- ✅ 选择图片后能看到预览
- ✅ 能删除图片
- ❌ 点发送后，图片不会真正发送（只发送文字）
- ❌ AI不会看到图片内容

---

**请告诉我你的决定：**
- **A**：继续完成全部改造（10-15小时）
- **B**：先快速修复原始问题（1小时）
- **C**：暂停并记录进度

我会根据你的选择继续执行。
