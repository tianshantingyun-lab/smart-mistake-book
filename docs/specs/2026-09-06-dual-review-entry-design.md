# 双复习入口设计：知识点复习 + 错题复习

状态：设计稿（待审查）
日期：2026-09-06
关联：`docs/specs/mastery-scheduling-spec.md`（复习排程）；`docs/research/llm-mastery-judgment-regulation.md`（模型判断规范）；`docs/specs/2026-09-02-tool-loop-wiring-design.md`（讲题智能体工具环）

## 0. 目标

把复习拆成**两个入口**：每天先复习**知识点**（纯考公式/模型/概念，大模型现场生成考察），再复习**错题**（考具体题）。知识点范围 = **今天错题涉及的知识点**（可超错题本身的范围，因为一个知识点可能覆盖多道题/更多内容），排程**参照错题编排算法**（同样的到期风险 + 掌握度风险打分 + 时间预算 + 多样性）。

## 1. 用户确认的需求要点

1. **作答需要判定**：选择题判对错；口述作答需判定（答得对不对）。
2. **自由选**：两个入口由学生自由选，但复习开始**提示先复习知识点**。
3. **范围**：知识点复习主要覆盖**今天错题里涉及的知识点**——不是"所有知识库知识点"。
4. **纯知识点复习参照错题编排模式算法**：同样的打分母（到期风险 + 掌握度风险），对象从"题"换成"知识点"。

## 2. 现状（已核实）

- **复习入口只有一个**：底部栏 `Routes.Review`（`SmartMistakeBookRoot.kt:180`），排程对象是**题**。
- **错题排程**：`ReviewPlanner.plan`（ReviewPlanner.kt:119）对候选**题**打分（`scoreCandidate`，:215）——`dueRisk`（题记忆到期风险，遗忘曲线）+ `masteryRisks`（题绑定的知识点掌握度风险：CONFLICTED/STALE/UNKNOWN → 高分），再用时间预算 + 多样性/难度循环选队列（:150-177）。
- **知识点掌握度已独立存在**：`LearnerKnowledgeMasteryStateEntity`（LearningEntities.kt:740，`primaryKeys = projection_name, learner_id, knowledge_node_id`，不绑 practice_unit），含 `masteryScore/conservativeMasteryScore/evidenceMass/lastEvidenceAt/status`。
- **知识库讲解材料已存在**：`KnowledgeTeachingMaterialDao`/`KnowledgeTeachingMaterialEntities`（每个知识点的讲解材料，含 `boundaryMarkdown`）——大模型生成知识的考察的依据来源。
- **模型任务类型**：现有 CAPTURE_ASSESS/IMAGE_PIPELINE_CLASSIFY/CAPTURE_PARSE/PROBLEM_CLASSIFY/PROBLEM_RELATE/TUTOR_*/REVIEW_RERANK/LEARNING_SUMMARIZE 等——**没有**"按知识点生成考察 + 判学生作答"的模型任务（需新增）。
- **"今天错题"**：`error_book_entry`（`status='ACTIVE'`，`accepted_at_epoch_millis`）。无现成"当天错题→涉及知识点"的聚合查询（需新增：当天 ACTIVE 错题 → 其绑定的知识点集合）。

## 3. 设计

### 3.1 双入口导航

底部栏"复习"入口下分两个子入口（或复习首页给两个卡片）：

- **知识点复习**（新增）：先做，提示学生。
- **错题复习**（现有 `ReviewRoute`）：后做。

学生自由选（用户确认：自由选 + 提示先复习知识点）。入口顺序/提示：进入复习首页时，若今天有应复习的知识点，显示"建议先复习知识点：XX 个"卡片。

### 3.2 知识点复习的范围与排程

- **范围**：**今天复习队列里的题**（`startOrResumeReviewSession` 排程出的当日队列）涉及的绑定知识点集合——不是"所有知识库知识点"，也不只"今天新增错题"，而是**复习作业里今天的题**所覆盖的知识点。**可超"错题本身"**（一个知识点覆盖的内容 > 具体某道题）。
- **排程（参照错题编排算法）**：对每个知识点打分 = `dueRisk`（知识点"到期"风险，类比题的 `nextReviewAt` → 用 `lastEvidenceAt` 距今 + 遗忘曲线/平滑掌握度）+ `masteryRisk`（CONFIDENT/STALE/UNKNOWN → 高），再用**同一套**时间预算 + 多样性 + 难度循环选当天的知识点队列。
- **复用**：`ReviewPlanner` 的打分逻辑可抽出一个"对知识点打分"的变体（`scoreKnowledgeNode`），与 `scoreCandidate`（对题）同构——题是对其绑定的多个知识点打分的聚合。

### 3.3 知识点复习的内容（大模型生成考察）

新模型任务 `KNOWLEDGE_QUIZ`（暂名）：
- 输入：知识点（`knowledge_node_id`）+ 该知识点的讲解材料（`KnowledgeTeachingMaterial`）+ 边界说明（`boundaryMarkdown`）+ 上次掌握度。
- 输出：**考察项**，两种形态：
  - **选择题**：学生对某个公式/模型/概念作判断，给选项。
  - **口述/简答**：学生**打字**用自己的话回答，大模型判定答得对不对。
- **判定**：学生作答 → 大模型判 `correct/partial/incorrect`（选择题判对错；口述判内容准确性）。
- **依据**：讲解材料的 `boundaryMarkdown`（明确"该知识点涵盖什么、边界在哪"）防止模型出超出范围的考察项——对齐 `llm-mastery-judgment-regulation` 的"可观察指标/防臆造"。

### 3.4 掌握度反馈回写

- 知识点复习的作答（`correct/partial/incorrect`）→ **回写知识点掌握度**（`LearnerKnowledgeMasteryState`，像 T6 MASTERY_UPDATE 一样走本地门控，但方向：复习作答是**客观信号**，比模型自报语义更可信——对齐 research §"行为证据>自我报告"）。
- 这样知识点复习**真实影响后续错题复习排程**（改 `conservativeMasteryScore` → 错题排程的 `masteryRisks`）。

## 4. 与既有系统的边界

- **不联网**：知识点复习的依据是**本地知识库讲解材料**（`KnowledgeTeachingMaterial`），不是联网搜——维持本地零出网。
- **掌握度写入仍走本地门控**：知识点复习的作答判定虽然是模型判的，但正确/错误是客观信号（学生答对/答错），回写掌握度用本地算法做主（对齐用户第 2 点"掌握证据本地算法做主、模型判断只是其中因素"）。
- **防臆造**：模型生的考察项必须锚定讲解材料的 `boundaryMarkdown`（知识点的真实边界），不能出超范围题（对齐防谄媚/防编造）。

## 5. 分期建议

- **P1（最小有用闭环）**：双入口导航 + 知识点复习范围 = 今天新增错题涉及的知识点 + 用现有讲解材料出**选择题**（不做口述）+ 判定回写掌握度 + 复用错题排程算法打分。
- **P2**：口述/简答作答 + 大模型判定（更专注内容，比选择题重）。
- **P3**：知识点范围扩展（从"今天错题涉及"到"按掌握度/遗忘到期的所有知识点"，用户第 3 点提到"可超错题范围"的进一步放开）。

## 6. 待定/待用户确认

- ~~"今天错题"的精确界定~~ → **已定**：复习作业里今天队列的题（`startOrResumeReviewSession` 当日排程）→ 绑定知识点集合。
- ~~口述作答形态~~ → **已定**：打字（学生打字用自己的话回答，大模型判对错）。
- 知识点复习的作答判定回写掌握度：回写的权重/机制（对齐 T6 门控？还是更简单，因为它是客观对错）。
- 分期：~~P1 先选择题~~ → **已定**：P1 选择题（学生从选项作判断）；P2 再做口述/简答打字判答。
