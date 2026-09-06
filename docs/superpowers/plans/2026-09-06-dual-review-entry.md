# 双复习入口（知识点复习 + 错题复习）实现计划

> **面向 AI 代理的工作者：** 用 subagent-driven-development 或 executing-plans 逐任务实现。步骤用 `- [ ]` 跟踪。

**目标：** 把复习拆成"知识点复习"（纯考公式/模型/概念，大模型现场出选择题）+ "错题复习"（现有，考具体题）两个入口。知识点复习范围派生自今天复习队列的题绑定知识点，排程参照错题编排算法，作答判定回写知识点掌握度。

**架构：** 新模型任务 `KNOWLEDGE_QUIZ`（按知识点+讲解材料出选择题）→ 学生选 → 判答 → 回写 `LearnerKnowledgeMasteryState`。知识点排程用 `ReviewPlanner` 的打分同构变体（对象从题换知识点）。UI 复习首页加两个入口卡片。

**技术栈：** Kotlin / Compose / Room / 现有 ModelTask 通道（OpenAI 兼容）。范围来源：`dual-review-entry-design.md`。

---

### 任务 1：知识点复习范围——从今天复习队列提取绑定知识点

**文件：**
- 修改：`core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/study/RoomBackedStudyExperienceRepository.kt`（`createReviewPlan`/`currentReviewPlan` 处）
- 测试：`core/data/src/test/.../ReviewKnowledgeScopeTest.kt`（新建）

- [ ] **步骤1：写失败测试**——给定今天复习队列的题（各带 `knowledgeNodeIds`），断言能提取出"该队列覆盖的知识点集合"（去重、不含重复）。

```kotlin
@Test fun todayQueueKnowledgeScopeIsTheUnionOfItsQuestionsBindings() {
    val queue = listOf(q("p1", setOf("kc1","kc2")), q("p2", setOf("kc2","kc3")))
    assertEquals(setOf("kc1","kc2","kc3"), extractReviewKnowledgeScope(queue))
}
```

- [ ] **步骤2：运行确认失败**
- [ ] **步骤3：实现** `extractReviewKnowledgeScope(queue)`——把复习队列的题（带 `knowledgeNodeIds`）union 成知识点集合。
- [ ] **步骤4：通过 + Commit**

### 任务 2：知识点复习排程——`scoreKnowledgeNode`（同构 ReviewPlanner 打分）

**文件：**
- 修改：`core/domain/src/main/kotlin/com/tingyun/smartmistakebook/core/domain/ReviewPlanner.kt`
- 测试：`core/domain/src/test/.../KnowledgeReviewPlannerTest.kt`（新建）

- [ ] **步骤1**：写失败测试——`scoreKnowledgeNode(kc, snapshot, now)` 对 CONFIDED/STALE/UNKNOWN 知识点给高优先级、对 mastered 给低；用 `lastEvidenceAt` 距今算到期风险（对齐 `scoreCandidate` 的 masteryRisks 逻辑）。
- [ ] **步骤2**：确认失败
- [ ] **步骤3**：实现 `scoreKnowledgeNode`——抽取 `scoreCandidate` 里对"知识点掌握度风险"的打分逻辑，改成直接对一个知识点打分（到期风险用 `lastEvidenceAt`+遗忘曲线，掌握度风险用 `masteryStatus`）。
- [ ] **步骤4**：通过 + Commit

### 任务 3：新模型任务 `KNOWLEDGE_QUIZ`——按知识点出选择题

**文件：**
- 修改：`core/model/.../ModelTasks.kt`（`ModelTaskKind` 加 `KNOWLEDGE_QUIZ`、`KnowledgeQuizInput`/`KnowledgeQuizOutput`）
- 修改：`core/data/.../OpenAiModelTaskAdapters.kt`（prompt：按知识点 + `boundaryMarkdown` 出选择题）
- 修改：`core/data/.../OpenAiModelProtocol.kt`（request body 分支）
- 测试：`core/data/src/test/.../KnowledgeQuizProtocolTest.kt`（新建）

- [ ] **步骤1**：写失败测试——给知识点 + boundaryMarkdown，断言 prompt 教模型出"选择题（给选项，含正确答案）+ 限定在 boundaryMarkdown 范围内"。输出 parse 成 `KnowledgeQuizOutput(question, choices, correctChoiceId)`。
- [ ] **步骤2**：确认失败
- [ ] **步骤3**：实现（input/output 模型 + prompt + parse + kind 分支）
- [ ] **步骤4**：通过 + Commit

### 任务 4：判答回写掌握度——知识点复习作答 → `LearnerKnowledgeMasteryState`

**文件：**
- 修改：`core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/study/RoomBackedStudyExperienceRepository.kt`（判答后的回写）
- 测试：`core/data/src/test/.../KnowledgeQuizFeedbackWriteTest.kt`（新建）

- [ ] **步骤1**：写失败测试——学生答对知识点选择题 → 回写该知识点掌握度（客观对错信号，走本地门控/权重）。
- [ ] **步骤2**：确认失败
- [ ] **步骤3**：实现——判答（correct/incorrect）→ 映射成学习证据 → 更新 `LearnerKnowledgeMasteryState[kc]`（对齐 review 作答写入链，复用现有投影/掌握度更新）。
- [ ] **步骤4**：通过 + Commit

### 任务 5：双入口 UI——复习首页两个卡片

**文件：**
- 修改：`app/src/main/kotlin/com/tingyun/smartmistakebook/SmartMistakeBookRoot.kt`（复习路由）
- 修改：`feature/review/src/main/kotlin/com/tingyun/smartmistakebook/feature/review/ReviewRoute.kt`（首页）
- 测试：`app/src/androidTest/.../DualReviewEntryInstrumentedTest.kt`（新建）

- [ ] **步骤1**：写失败测试——复习首页可见两个入口卡片（知识点/错题），点击进入对应流程。
- [ ] **步骤2**：确认失败
- [ ] **步骤3**：实现——复习首页加"知识点复习"卡片（提示先复习知识点，但学生可自由选错题）+ 现有"错题复习"卡片。
- [ ] **步骤4**：通过 + Commit

### 任务 6：知识点复习会话 UI——选择题作答流

**文件：**
- 修改：`feature/review/src/main/kotlin/com/tingyun/smartmistakebook/feature/review/`（新 KnowledgeQuizScreen）
- 修改：`core/domain/.../StudyExperienceRepository.kt`（知识点复习会话启动/提交接口）
- 测试：`feature/review/src/test/.../KnowledgeQuizSessionTest.kt`（新建）

- [ ] **步骤1**：写失败测试——知识点复习会话：启动 → 出选择题 → 学生选 → 判答 → 回写 → 下一知识点。
- [ ] **步骤2**：确认失败
- [ ] **步骤3**：实现——复用 `TutorAssessmentItem`/`TutorChoice`/`evaluateChoice`（选择题骨架），知识点复习会话复用 ReviewSession 的状态机。
- [ ] **步骤4**：通过 + Commit
