# 讲题多模态聊天改造 - 最新进度

## ✅ 已完成的工作（约 60% 完成）

### 1. 数据层改造 ✅
- ✅ `TutorLobbyInput` 支持 `imageAssetRefs`
- ✅ `TutorLobbyOutput` 支持 `suggestedAction`（用于 AI 建议加入错题本）
- ✅ `TutorChatHistoryEntry` 支持 `studentImageAssetRefs`
- ✅ `TutorRespondInput` 支持 `studentImageAssetRefs`
- ✅ 所有新增字段都有验证逻辑

### 2. UI 层改造 ✅
- ✅ `TutorComposer` 组件支持图片附件
  - 图片预览区域（使用 `BoundedLocalImage`）
  - 相机/相册选择按钮（`ImageAttachmentButtons`）
  - 图片删除功能
  - 发送按钮支持纯图片消息
- ✅ `TutorLobbyRoute` 状态管理
  - `attachedImages` 状态
  - `pendingDisclosureImages` 状态（隐私披露）
  - 图片附加/移除逻辑
  - `submitDraft` 支持图片
  - `startMessage` 支持图片
- ✅ `TutorLobbyTask` 显示用户发送的图片消息

### 3. 后端/数据层集成 ✅
- ✅ `TutorImageAssetManager`（`core/data`）
  - 复制用户图片到私有目录
  - 生成持久化的资产引用 ID
  - 提供根据 ID 获取 URI 的接口
- ✅ `buildTutorLobbyRequest` 支持 `imageAssetRefs` 参数
- ✅ `startMessage` 中集成图片保存逻辑

### 4. 编译状态 ✅
- ✅ 所有模块编译通过
- ✅ 依赖配置正确（`feature:tutor` 依赖 `core:data`）
- ✅ 使用项目自有的 `BoundedLocalImage` 而非外部库

---

## ⏳ 剩余工作（约 40%）

### 阶段 6：相机拍照处理（2-3 小时）

**当前问题：**
- `TutorLobbyRoute` 的 `onCapture` 仍然导航到独立的 `CaptureScreen`
- 用户点击相机按钮 → 跳转到另一个页面（不符合聊天式交互）

**需要做：**
1. 修改 `onCapture` 行为：
   - 不导航到 `CaptureScreen`
   - 直接打开系统相机
   - 拍照完成后添加到 `attachedImages`
2. 或者：保留独立拍照入口，但从相机按钮的下拉菜单中调用 `ActivityResultContracts.TakePicture`

**技术方案：**
```kotlin
// TutorLobbyRoute.kt
val takePictureLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.TakePicture()
) { success ->
    if (success && tempCameraUri != null) {
        attachedImages = attachedImages + tempCameraUri!!
    }
}

fun onCapturePhoto() {
    val uri = createTempImageFile(context)
    tempCameraUri = uri
    takePictureLauncher.launch(uri)
}
```

### 阶段 7：Prompt 设计（1-2 小时）

**目标：**
- 大模型能理解图片内容（用户上传的题目）
- 大模型知道何时建议"加入错题本"

**需要做：**
1. **多模态 Prompt 模板**
   - 修改 `core/data/.../RoomModelTaskRepository.kt` 中的 Prompt 构建
   - 将图片资产引用转换为模型可用的格式（可能需要 base64 编码或 URI）
   - 告诉模型："用户发送了 X 张图片，请识别并讲解"

2. **工具调用 Prompt**
   - 在 System Prompt 中添加：
     ```
     你有一个工具：save_to_library
     当用户明确说"加入错题本"、"保存到错题本"时，调用这个工具
     当讲解结束后，你可以主动询问："是否要加入错题本？"
     ```
   - 解析 AI 返回的 `suggestedAction`

3. **实现工具调用执行**
   - 检测 `TutorLobbyOutput.suggestedAction`
   - 如果是 `SaveToLibrary`，调用 `saveTutorProblem(...)`（早期的演示种子保存接口已按审计 9.2/PR-05 删除）

**文件：**
- `core/data/.../RoomModelTaskRepository.kt`
- `feature/tutor/.../TutorLobbyRoute.kt`（处理 `suggestedAction`）

### 阶段 8：导航清理（1 小时）

**需要做：**
1. 移除 `Routes.CaptureTutor` 路由（`app/src/main/kotlin/.../SmartMistakeBookRoot.kt`）
2. 保留 `Routes.CaptureLibrary`（错题本独立录入）
3. 验证错题本批量录入流程不受影响

### 阶段 9：端到端测试（2-3 小时）

**测试清单：**
- [ ] 讲题页发送纯文字消息
- [ ] 讲题页发送图片消息（拍照）
- [ ] 讲题页发送图片消息（相册）
- [ ] 讲题页发送文字+图片混合消息
- [ ] AI 识别图片并讲解
- [ ] 用户说"加入错题本"，AI 执行
- [ ] 讲解结束后，AI 询问是否加入
- [ ] 错题本页批量录入不受影响
- [ ] 聊天历史中正确显示图片
- [ ] 图片预览性能测试（多张图片）

---

## 当前状态

**编译状态：** ✅ 全部通过
**运行状态：** ⚠️ 部分功能可用，但缺少关键部分
**功能完整性：** 60%

**如果现在编译运行：**
- ✅ 用户能看到相机按钮的下拉菜单（拍照/相册）
- ✅ 选择相册图片后能看到预览
- ✅ 能删除图片
- ✅ 点发送后，图片保存到私有目录
- ✅ 聊天记录中显示用户发送的图片
- ❌ 点击"拍照"会跳转到独立页面（不符合聊天式交互）
- ❌ 图片未真正发送给 AI（Prompt 未集成）
- ❌ AI 看不到图片内容（未转换为模型可用格式）
- ❌ AI 无法建议"加入错题本"（工具调用未实现）

---

## 关键技术决策

### ✅ 已决策

1. **不使用本地 OCR**
   - 图片识别完全由大模型完成
   - 简化了架构，减少了 5 小时工作量

2. **使用项目自有的图片加载组件**
   - 使用 `BoundedLocalImage` 而非 Coil
   - 避免引入新依赖

3. **图片资产管理**
   - 存储在 `filesDir/tutor_images/`
   - 使用 UUID 生成资产 ID
   - 暂不实现复杂的垃圾回收（TODO）

### ⏳ 待决策

1. **相机拍照方式**
   - **方案 A**：使用 `ActivityResultContracts.TakePicture()`，不跳转页面
   - **方案 B**：保留跳转到 `CaptureScreen`，但拍完直接回到聊天页并附加图片
   - **推荐**：方案 A（更符合聊天式交互）

2. **图片格式传递给模型**
   - **方案 A**：Base64 编码嵌入 Prompt
   - **方案 B**：传递 File URI，由模型 SDK 处理
   - **方案 C**：上传到临时服务器，传递 URL
   - **推荐**：方案 B（取决于你使用的模型 SDK）

3. **工具调用格式**
   - **方案 A**：结构化 JSON 输出（需要模型支持 Function Calling）
   - **方案 B**：自然语言触发词（"[TOOL:save_to_library]"）
   - **推荐**：方案 A（如果模型支持）

---

## 下一步行动

### 立即执行：确认技术决策

**需要用户回答：**

1. **你使用的是什么大模型？**
   - Claude API？
   - GPT-4V？
   - 本地部署的多模态模型？
   - 其他？

2. **模型 SDK 如何传递图片？**
   - 查看 `core/data/.../RoomModelTaskRepository.kt` 中如何调用模型 API
   - 是否支持直接传递图片 URI？
   - 还是需要 Base64 编码？

3. **模型是否支持 Function Calling？**
   - 是否支持结构化输出？
   - 还是只能通过自然语言触发？

**回答这些问题后，我会：**
1. 实现相机拍照逻辑（阶段 6）
2. 设计并实现 Prompt 集成（阶段 7）
3. 实现工具调用（阶段 7）
4. 清理导航（阶段 8）
5. 完成测试（阶段 9）

**预计剩余时间：** 6-9 小时

---

## 修改文件清单

### 已修改 ✅
- ✅ `core/model/.../TutorLobbyTasks.kt`
- ✅ `core/model/.../TutorTasks.kt`
- ✅ `feature/tutor/.../TutorRoute.kt`
- ✅ `feature/tutor/.../TutorLobbyRoute.kt`
- ✅ `feature/tutor/.../TutorLobbyModelTaskPolicy.kt`
- ✅ `core/data/.../TutorImageAssetManager.kt`（新建）
- ✅ `feature/tutor/build.gradle.kts`

### 待修改 ⏳
- ⏳ `feature/tutor/.../TutorLobbyRoute.kt`（相机拍照逻辑）
- ⏳ `core/data/.../RoomModelTaskRepository.kt`（Prompt 集成、工具调用）
- ⏳ `app/src/main/kotlin/.../SmartMistakeBookRoot.kt`（导航清理）

---

## 进度百分比

```
[███████████████████░░░░░░░░░] 60%
```

- 数据层：100% ✅
- UI 层：100% ✅
- 图片管理：100% ✅
- 相机拍照：0% ⏳
- Prompt 集成：0% ⏳
- 工具调用：0% ⏳
- 导航清理：0% ⏳
- 测试验证：0% ⏳
