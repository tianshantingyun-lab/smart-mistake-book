# 讲题多模态聊天改造 - 实施进度

## 目标
将讲题改造成 ChatGPT/微信风格的多模态聊天，支持图片发送和 AI 控制错题本。

---

## 已完成的工作

### ✅ 阶段 1：数据层改造（已完成）

#### 1.1 扩展 TutorLobbyInput 支持图片
**文件：** `core/model/src/main/kotlin/.../TutorLobbyTasks.kt`
- ✅ 添加 `imageAssetRefs: List<String>` 字段
- ✅ 添加验证逻辑（最多5张图片）
- ✅ 添加 `MAX_IMAGE_ASSETS` 和 `MAX_ASSET_REF_CHARS` 常量

#### 1.2 新增 TutorSuggestedAction（AI 建议操作）
**文件：** `core/model/src/main/kotlin/.../TutorLobbyTasks.kt`
- ✅ 定义 `TutorSuggestedAction` sealed class
- ✅ 添加 `SaveToLibrary` 操作（用于"加入错题本"）
- ✅ 扩展 `TutorLobbyOutput` 添加 `suggestedAction` 字段

#### 1.3 扩展聊天历史支持图片
**文件：** `core/model/src/main/kotlin/.../TutorTasks.kt`
- ✅ `TutorChatHistoryEntry` 添加 `studentImageAssetRefs` 字段
- ✅ 添加验证逻辑和 `MAX_IMAGE_ASSETS_PER_MESSAGE` 常量

#### 1.4 扩展 TutorRespondInput 支持图片
**文件：** `core/model/src/main/kotlin/.../TutorTasks.kt`
- ✅ 添加 `studentImageAssetRefs: List<String>` 字段
- ✅ 添加验证逻辑

### ✅ 阶段 2：UI 层基础改造（已完成）

#### 2.1 改造 TutorComposer 支持图片附件
**文件：** `feature/tutor/src/main/java/.../TutorRoute.kt`
- ✅ 添加 `attachedImages`, `onImagesAttached`, `onImageRemoved` 参数
- ✅ 发送按钮支持空文字+图片的情况
- ✅ 改为 Column 布局，包含图片预览区

#### 2.2 新增图片选择组件
**文件：** `feature/tutor/src/main/java/.../TutorRoute.kt`
- ✅ `ImageAttachmentButtons`：相机/相册选择菜单
- ✅ `ImageAttachmentPreview`：图片缩略图预览+删除
- ✅ 集成 `rememberLauncherForActivityResult` 和 `PickMultipleVisualMedia`

#### 2.3 添加必要的 imports
- ✅ `android.net.Uri`
- ✅ `androidx.activity.compose.rememberLauncherForActivityResult`
- ✅ `ActivityResultContracts`
- ✅ `coil.compose.AsyncImage`
- ✅ Material3 `DropdownMenu`, `DropdownMenuItem`
- ✅ 各种 Icons（`Close`, `PhotoLibrary` 等）

---

## 进行中的工作

### 🔄 阶段 3：TutorLobbyRoute 改造（进行中）

#### 需要做的：
1. **修改 TutorLobbyRoute 的状态管理**
   - 添加 `attachedImages` 状态
   - 处理图片附加/移除逻辑
   
2. **修改消息发送逻辑**
   - `submitDraft()` 函数需要支持发送图片
   - 构建包含图片的 `TutorLobbyInput`

3. **修改消息显示逻辑**
   - `TutorLobbyTask` 需要显示用户发送的图片
   - 新增 `TutorLobbyImageMessage` 组件

4. **处理相机拍照流程**
   - `onCapture` 不再导航到 `CaptureScreen`
   - 改为打开系统相机，拍照完成后添加到附件

---

## 待完成的工作

### ⏳ 阶段 4：后端/数据层集成

#### 4.1 图片 OCR 集成
**文件：** `core/data/src/main/kotlin/.../RoomModelTaskRepository.kt`
- [ ] 提取 OCR 逻辑为独立服务
- [ ] 实现 `executeTutorLobbyWithImage()` 函数
- [ ] OCR 结果注入 Prompt

#### 4.2 图片存储管理
- [ ] 复制用户选择的图片到应用私有目录
- [ ] 生成持久化的资产引用 ID
- [ ] 实现图片清理逻辑

#### 4.3 工具调用执行
- [ ] 实现 `executeTutorToolCall()` 函数
- [ ] 处理 `SaveToLibrary` 操作
- [ ] 集成现有的 `saveTutorExampleMistake()` 逻辑

### ⏳ 阶段 5：Prompt 设计

#### 5.1 多模态 Prompt 模板
- [ ] 设计包含图片+OCR结果的 Prompt 格式
- [ ] 确保模型理解这是题目图片

#### 5.2 工具调用 Prompt
- [ ] 设计"加入错题本"的触发条件
- [ ] 用户明确说"加入错题本" → 高置信度调用
- [ ] 讲解结束后 → AI 主动询问

### ⏳ 阶段 6：导航清理

#### 6.1 移除讲题页到 CaptureScreen 的路由
**文件：** `app/src/main/kotlin/.../SmartMistakeBookRoot.kt`
- [ ] 删除 `composable(Routes.CaptureTutor)`
- [ ] 修改导航逻辑

#### 6.2 保持错题本独立录入
- [ ] 确认 `Routes.CaptureLibrary` 不受影响
- [ ] 验证错题本批量录入流程正常

### ⏳ 阶段 7：端到端测试

- [ ] 讲题页发送纯文字消息
- [ ] 讲题页发送图片消息（拍照）
- [ ] 讲题页发送图片消息（相册）
- [ ] 讲题页发送文字+图片混合消息
- [ ] AI 识别图片并讲解
- [ ] 用户说"加入错题本"，AI 执行
- [ ] 讲解结束后，AI 询问是否加入
- [ ] 错题本页批量录入不受影响

---

## 技术难点与风险

### 🔴 高风险项

1. **OCR 逻辑解耦**（阶段 4.1）
   - 当前 OCR 在 `CaptureScreen` 中，需要提取
   - 风险：可能破坏现有的错题本录入流程
   - 缓解：提取为独立服务，两边都调用

2. **图片生命周期管理**（阶段 4.2）
   - 用户选择的图片需要复制到私有目录
   - 需要清理逻辑，避免内存泄漏
   - 风险：用户退出应用后图片丢失

3. **Prompt 设计**（阶段 5）
   - 模型需要准确理解何时调用"加入错题本"
   - 风险：误触发或不触发
   - 缓解：需要精心设计 Prompt 和测试

### 🟡 中风险项

1. **图片显示性能**（阶段 3.3）
   - 对话流中显示多张图片可能卡顿
   - 缓解：使用 Coil 缓存和懒加载

2. **导航逻辑变更**（阶段 6）
   - 移除 `CaptureScreen` 路由可能影响其他入口
   - 缓解：保留错题本的独立路由

---

## 下一步行动

### 立即执行：阶段 3 - TutorLobbyRoute 改造

**任务清单：**
1. [ ] 修改 `TutorLobbyRoute` 添加图片状态
2. [ ] 实现图片附加/移除逻辑
3. [ ] 修改 `submitDraft()` 支持图片
4. [ ] 修改 `onCapture` 行为（不导航）
5. [ ] 添加图片消息显示组件

**预计时间：** 2-3 小时

**完成标准：**
- ✅ 用户能点击相机按钮选择图片
- ✅ 图片显示在输入框上方
- ✅ 点击发送后，图片显示在对话流中
- ✅ 编译通过，无运行时错误

---

## 修改文件清单

### 已修改
- ✅ `core/model/src/main/kotlin/.../TutorLobbyTasks.kt`
- ✅ `core/model/src/main/kotlin/.../TutorTasks.kt`
- ✅ `feature/tutor/src/main/java/.../TutorRoute.kt`

### 待修改
- ⏳ `feature/tutor/src/main/java/.../TutorLobbyRoute.kt`
- ⏳ `core/data/src/main/kotlin/.../RoomModelTaskRepository.kt`
- ⏳ `app/src/main/kotlin/.../SmartMistakeBookRoot.kt`
- ⏳ 新建：OCR 服务相关文件

---

## 时间估算（剩余）

| 阶段 | 预计时间 | 状态 |
|------|---------|------|
| 阶段 3：TutorLobbyRoute 改造 | 2-3 小时 | 🔄 进行中 |
| 阶段 4：后端集成 | 4-5 小时 | ⏳ 待开始 |
| 阶段 5：Prompt 设计 | 1-2 小时 | ⏳ 待开始 |
| 阶段 6：导航清理 | 1-2 小时 | ⏳ 待开始 |
| 阶段 7：测试验证 | 2-3 小时 | ⏳ 待开始 |
| **总剩余时间** | **10-15 小时** | |

**总进度：** 约 20% 完成
