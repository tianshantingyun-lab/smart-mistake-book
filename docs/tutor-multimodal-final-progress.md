# 讲题多模态聊天改造 - 最终进度报告

## 🎯 已完成的核心功能（约60%）

### ✅ 阶段 1-3：数据层、UI层、图片发送（已完成）

#### 1. 数据层改造 ✅
- ✅ `TutorLobbyInput` 支持 `imageAssetRefs`
- ✅ `TutorLobbyOutput` 支持 `suggestedAction`（为AI控制错题本预留）
- ✅ `TutorChatHistoryEntry` 支持 `studentImageAssetRefs`
- ✅ `TutorRespondInput` 支持 `studentImageAssetRefs`
- ✅ 所有新增字段都有验证逻辑

#### 2. UI层改造 ✅
- ✅ `TutorComposer` 组件支持图片附件
  - 图片预览区域（使用 `BoundedLocalImage`）
  - 相机/相册选择下拉菜单
  - 图片删除功能
  - 发送按钮支持纯图片消息
- ✅ `ImageAttachmentButtons` 组件（相机/相册菜单）
- ✅ `ImageAttachmentPreview` 组件（图片缩略图+删除）

#### 3. 图片存储管理 ✅
- ✅ `TutorImageAssetManager`（core/data）
  - 复制用户图片到私有目录
  - 生成持久化的资产ID
  - 根据ID获取图片URI
  - 清理资产（预留接口）

#### 4. 消息发送逻辑 ✅
- ✅ `TutorLobbyRoute.startMessage()` 支持图片参数
- ✅ 图片保存到私有目录
- ✅ 构建包含图片引用的 `TutorLobbyInput`
- ✅ 支持披露卡片（外部模型）的图片发送

#### 5. 对话流显示 ✅
- ✅ `TutorLobbyTask` 显示用户发送的图片
- ✅ 图片+文字混合消息布局
- ✅ 最多显示4张图片缩略图

#### 6. 构建系统 ✅
- ✅ `feature:tutor` 依赖 `core:data`
- ✅ 添加 `androidx.activity.compose` 依赖
- ✅ 使用项目已有的 `BoundedLocalImage`（不引入外部库）
- ✅ 编译通过
- ✅ APK 构建成功

---

## ⏳ 剩余工作（约40%）

### 阶段 4：后端 Prompt 构建（2-3小时）

**核心任务：**
1. **修改模型执行层**：`RoomModelTaskRepository.kt`
   - 检测 `TutorLobbyInput.imageAssetRefs` 是否为空
   - 非空时构建多模态 Prompt（图片+文字）
   - 调用支持 vision 的模型API

2. **多模态 Prompt 设计**
   ```
   用户发送了 {N} 张图片，请识别图片内容。
   
   [图片1]
   [图片2]
   ...
   
   用户的问题：{studentMessage}
   
   请基于图片内容回答用户的问题。如果图片包含题目，请讲解这道题。
   ```

3. **API 集成**
   - Claude/GPT-4V：发送图片 base64 或 URL
   - 本地模型：根据能力决定是否支持

**文件：**
- `core/data/.../RoomModelTaskRepository.kt`
- 可能需要：`core/model/.../ModelTaskInput.kt`（如果需要扩展）

---

### 阶段 5：AI 工具调用 - "加入错题本"（2-3小时）

**核心任务：**
1. **Prompt 设计**
   ```
   你有一个工具：save_to_library
   
   当以下情况发生时调用这个工具：
   - 用户明确说"加入错题本"、"保存到错题本"
   - 讲解结束后，你认为这道题值得保存
   
   调用格式：
   {
     "tool_call": "save_to_library",
     "reason": "原因说明"
   }
   ```

2. **工具调用解析**
   - 从 `TutorLobbyOutput` 中解析 `suggestedAction`
   - 检测到 `SaveToLibrary` 时执行

3. **执行逻辑**
   - 调用现有的 `saveTutorProblem(...)` 函数（早期的演示种子保存接口已按审计 9.2/PR-05 删除）
   - 将讲题会话保存到错题本
   - 返回确认消息给用户

**文件：**
- `core/data/.../RoomModelTaskRepository.kt`（解析和执行）
- Prompt 模板文件

---

### 阶段 6：导航清理（1-2小时）

**核心任务：**
1. **移除讲题页到 CaptureScreen 的路由**
   - 删除 `composable(Routes.CaptureTutor)`
   - 修改 `onCapture` 回调：不再导航，改为打开系统相机

2. **修改相机行为**
   - `onCapture` 改为使用 `rememberLauncherForActivityResult(TakePicture)`
   - 拍照完成后直接添加到 `attachedImages`
   - 不跳转页面

3. **保留错题本独立录入**
   - 确认 `Routes.CaptureLibrary` 不受影响
   - 验证错题本批量录入流程正常

**文件：**
- `app/.../SmartMistakeBookRoot.kt`
- `feature/tutor/.../TutorLobbyRoute.kt`（修改 `onCapture`）

---

### 阶段 7：端到端测试（2-3小时）

**测试场景：**
1. ✅ 讲题页发送纯文字消息
2. ⏳ 讲题页发送图片消息（拍照）
3. ⏳ 讲题页发送图片消息（相册）
4. ⏳ 讲题页发送文字+图片混合消息
5. ⏳ AI识别图片并讲解
6. ⏳ 用户说"加入错题本"，AI执行
7. ⏳ 讲解结束后，AI询问是否加入
8. ⏳ 错题本页批量录入不受影响

---

## 🚀 当前可运行的功能

### 已实现：
- ✅ 用户能点击相机按钮，看到"拍照/从相册选择"菜单
- ✅ 从相册选择图片后，图片显示在输入框上方
- ✅ 能删除已选择的图片
- ✅ 点击发送后，图片显示在对话流中（用户消息气泡）
- ✅ 图片会被复制到应用私有目录并持久化
- ✅ 编译通过，APK构建成功

### 未实现：
- ❌ AI不会看到图片内容（Prompt未构建多模态请求）
- ❌ 拍照按钮仍会跳转到独立页面（未改造导航）
- ❌ AI不能执行"加入错题本"操作

---

## 📊 进度总结

| 阶段 | 任务 | 状态 | 完成度 |
|------|------|------|--------|
| 1 | 数据层改造 | ✅ 完成 | 100% |
| 2 | UI层改造 | ✅ 完成 | 100% |
| 3 | 图片发送逻辑 | ✅ 完成 | 100% |
| 4 | 后端Prompt构建 | ⏳ 待完成 | 0% |
| 5 | AI工具调用 | ⏳ 待完成 | 0% |
| 6 | 导航清理 | ⏳ 待完成 | 0% |
| 7 | 端到端测试 | ⏳ 待完成 | 10% |
| **总体进度** | | 🔄 进行中 | **60%** |

---

## 🔧 技术亮点

### 1. 无需外部依赖
- 使用项目已有的 `BoundedLocalImage` 加载图片
- 不引入 Coil/Glide 等图片库
- 减少APK体积

### 2. 向后兼容
- `TutorComposer` 的新参数都有默认值
- 现有代码无需修改即可编译通过
- 渐进式升级

### 3. 数据隔离
- 图片存储在独立的 `tutor_images` 目录
- 资产ID生成使用UUID，避免冲突
- 预留清理接口，避免空间泄漏

### 4. 多模态 Prompt 预留
- 数据层已支持 `imageAssetRefs`
- 只需修改 Prompt 构建逻辑即可启用
- 不涉及UI和存储层改动

---

## 📝 下一步行动计划

### 立即可做（按优先级）：

**选项 A：完成剩余40%（7-10小时）**
1. 修改 `RoomModelTaskRepository` 构建多模态 Prompt
2. 集成模型 API 发送图片
3. 实现 AI 工具调用"加入错题本"
4. 清理导航，移除独立拍照页面
5. 端到端测试

**选项 B：暂停并验证当前功能（1小时）**
1. 安装 APK 到设备
2. 测试图片选择、预览、删除功能
3. 验证图片显示在对话流中
4. 确认图片持久化到私有目录
5. 记录任何UI/UX问题

**选项 C：最小可用版本（3-4小时）**
1. 只完成阶段4（多模态 Prompt）
2. 用户能发送图片，AI能识别并讲解
3. "加入错题本"和导航清理留待后续迭代

---

## ⚠️ 已知限制

### 当前版本：
1. **AI无法识别图片**：需要完成阶段4的Prompt构建
2. **拍照仍跳转页面**：需要完成阶段6的导航清理
3. **图片不会被清理**：清理逻辑预留但未实现
4. **最多5张图片**：由 `TutorLobbyInput.MAX_IMAGE_ASSETS` 限制

### 设计决策：
1. **不使用OCR**：完全由大模型识别图片（简化架构）
2. **图片存储在应用私有目录**：不支持跨应用访问
3. **图片显示最多4张**：UI空间限制

---

## 📂 修改文件清单

### 已修改：
1. ✅ `core/model/.../TutorLobbyTasks.kt`
2. ✅ `core/model/.../TutorTasks.kt`
3. ✅ `feature/tutor/.../TutorRoute.kt`
4. ✅ `feature/tutor/.../TutorLobbyRoute.kt`
5. ✅ `feature/tutor/.../TutorLobbyModelTaskPolicy.kt`
6. ✅ `feature/tutor/build.gradle.kts`
7. ✅ `core/data/.../TutorImageAssetManager.kt`（新建）

### 待修改：
- ⏳ `core/data/.../RoomModelTaskRepository.kt`
- ⏳ `app/.../SmartMistakeBookRoot.kt`
- ⏳ Prompt 模板文件（可能需要新建）

---

## 🎉 成果展示

### 用户体验改进：
**改造前：**
```
讲题页 → 点相机 → 跳到拍照页 → 识别 → 点"开始讲解" → 回到讲题页
```

**改造后（目标）：**
```
讲题页（聊天界面）
↓
点相机 → 选图片 → 图片显示在输入框
↓
点发送 → AI直接识别并讲解
↓
说"加入错题本" → AI执行并确认
```

**当前进度（60%）：**
```
讲题页（聊天界面）
↓
点相机 → 选图片 → 图片显示在输入框 ✅
↓
点发送 → 图片显示在对话流 ✅
↓
AI无法识别图片 ❌（需要完成阶段4）
```

---

## 💡 建议

### 我的推荐：选项 C（最小可用版本）

**理由：**
1. 当前60%已完成，UI层完全可用
2. 只需完成阶段4（多模态Prompt），用户就能正常使用
3. "加入错题本"和导航清理不影响核心功能
4. 3-4小时即可交付可用版本

**下一步：**
1. 修改 `RoomModelTaskRepository`，构建多模态Prompt
2. 测试AI能否识别图片并讲解
3. 发布可用版本
4. 后续迭代完成工具调用和导航清理

---

**请告诉我你的选择：**
- **A**：继续完成全部40%（7-10小时）
- **B**：暂停并验证当前功能（1小时）
- **C**：完成最小可用版本（3-4小时）

或者你有其他计划也可以告诉我。
