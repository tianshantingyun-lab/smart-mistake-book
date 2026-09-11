# 三库关联设计：掌握库 ↔ 知识库 ↔ 错题本库

**配套**：《mastery-math-modeling.md》（公式）、《mastery-scheduling-spec.md》（总 spec）
**目的**：算法落向实际的必须关联——复习选题不能只看「题的下次到期时间」，必须经由知识点图谱指向「薄弱的 KC、缺失的前置、易混的对」。

---

## 1. 三库现状（实体与键路，均经代码核实）

### 1.1 错题本库
```
error_book_entry(entry_id PK, problem_id, practice_unit_id, current_revision_id, source_key, status, …)
problem(problem_id PK, subject)
problem_revision(revision_id PK, problem_id, revision_number, title, problem_markdown,
                 question_document_snapshot, answer_spec_id=null, answer_spec_snapshot=null, …)
problem_revision_source_asset(revision_id, source_asset_id, role=QUESTION_SOURCE)
canonical_source_asset(source_asset_id PK, relative_path, width, height, sha256)
practice_unit(practice_unit_id PK, problem_id, title, estimated_seconds)
```
关联根：`entry ↔ practice_unit ↔ problem ↔ revision`；原图经 canonical_source_asset。
**缺口**：answer_spec 两列全 null（ProblemDraftTransactionDao.kt:880-895 硬编码）——「答案/解析」在数据模型中是空位，导出 PDF 也不含答案。

### 1.2 知识库
```
knowledge_node(knowledge_node_id PK, stable_code, subject, display_name, canonical_name,
               parent_knowledge_node_id →(树), taxonomy_version, node_kind=TOPIC/REASONING,
               granularity=TOPIC/ATOMIC, aliases, boundary_markdown,
               verification_status=CURATED/SOURCE_GROUNDED/MODEL_CANDIDATE)
knowledge_node_relation(relation_id PK, subject, prerequisite_knowledge_node_id,
                        dependent_knowledge_node_id, relation_type=PREREQUISITE_OF, source_id, reviewed_at)
practice_unit_knowledge_binding(binding_id PK, practice_unit_id, knowledge_node_id,
                        basis_revision_id, strength, source_type, taxonomy_version, accepted_at)
knowledge_source(source_id PK, …license/content_fingerprint)  +  knowledge_node_source_binding(审校链)
knowledge_teaching_material(material_id PK, …, derivation_kind, source_id)  +  material_node_binding(role)
knowledge_grounding_request(…problem/practice_unit 关联…)
```
**已具备的关联资产**（很多 App 没有，必须用足）：
- 题目↔KC **多对多绑定带 strength 权重**（binding.strength）与 **basis_revision_id/taxonomy_version 版本锚**；
- KC 间 **PREREQUISITE_OF 有审校来源**（reviewed_at + source_id，非模型拍脑袋）；
- 教学材料与 KC 挂接（role）——「前置缺失→推重教材料」的载体现成。

### 1.3 掌握库（投影产出）
```
learner_problem_memory_state(learner_id, practice_unit_id, stability_days, difficulty,
        last_reviewed_at, next_review_at, independent/assisted_correct_count, lapse_count,
        answer_reveal_count, last_lapse_at, clock_anomaly…, last_attempt_id, projector_version, checkpoint)
learner_knowledge_mastery_state(learner_id, knowledge_node_id, mastery_score,
        conservative_mastery_score, evidence_mass, last_independent_error_at, status,
        calibration_support, last_evidence_at, conflict_since_sequence)
旧 problem_memory_state（fixture 种子专用，retrievability 字段；library_catalog 视图在读它 ⚠）
```

---

## 2. 现有关联缺陷（审计确认）

| # | 缺陷 | 证据 | 影响 |
|---|---|---|---|
| L1 | **library_catalog 视图读旧表 + 硬编码 learner:local** | LibraryCatalogView.kt:40-41,65-66；LibraryCatalogMigration.kt:44 | 目录的 next_review/retrievability 永不随投影刷新（读 fixture 静态表）；LEAST_MASTERED 排序失真；非 local learner 全空 |
| L2 | **真实作答证据未按 binding 分摊到 KC** | 视觉通道有 PRIMARY/SECONDARY 分摊（RoomBackedStudyExperienceRepository.kt:786-805），作答通道直落 practice_unit | KC mastery 只吃视觉+自评，吃不到最重要的真实作答 |
| L3 | **binding.strength 未参与任何聚合** | StudyDatabasePort.kt:384-393 定义后仅视觉分摊间接用 | 多 KC 题的证据平均主义 |
| L4 | **prerequisite 关系未进调度**（**2026-09-12 已修**） | 原：`ReviewPlannerV2.scoreCandidate` 无 KC 图谱输入；生产调用点从不填 `ReviewPlanningRequest.knowledgePrerequisites` | 前置缺失时复习本题收益递减（DAS3H/KST 依据）。修复见 §3.3 实现状态 |
| L5 | **KC 标签随 revision 变化时旧掌握不迁移** | binding 带 basis_revision_id + taxonomy_version，但无迁移逻辑 | 改绑后旧 KC 的 evidence_mass 悬空 |
| L6 | **teaching material 未接弱点通道**（**2026-09-12 部分已修**） | 原：`material_node_binding` 存在，无「弱点→材料」检索路径。现：§2.9 前置补救与 §2.16 重教都已落到会话界面；**知识点复习出题侧**（`selectKnowledgeReviewQueue`）仍未纳入前置判定 | 前置补救/概念错重教无落点 |
| L7 | **重教链路空转**（答案/解析缺失 + 概念错因缺失） | answer_spec null + 无 error_type | 分通道调度（概念→重教）缺两根柱子 |

---

## 3. 目标关联设计

### 3.1 键路总图（写入方向 = 行为 → 掌握；读取方向 = 调度 → 展示）

```
[作答/自评/视觉/暴露]
        │ attempt_event(practice_unit_id, evidence)              ← 行为层（见 math-modeling 第二部分）
        ▼
practice_unit_knowledge_binding (practice_unit_id, knowledge_node_id, strength, basis_revision_id, taxonomy_version)
        │ 证据分摊：contribution_k = ±w_e · strength_k/Σstrength_j     ← §math-9
        ▼
learner_knowledge_mastery_state (knowledge_node_id, mastery_score, evidence_mass, …)
        │                                      │
        │ KC→题 反推                            │ prerequisite（knowledge_node_relation）
        ▼                                      ▼
learner_problem_memory_state ←→ practice_unit ←→ knowledge_node(树/aliases/boundary)
        │                                      │
        ▼                                      ▼
ReviewPlannerV2 候选打分 ──────────────► 前置缺口→teaching_material（重教通道）
```

### 3.2 证据分摊规则（修 L2/L3）

- **所有**调度证据（真实作答、提示作答、自评、视觉、暴露）统一走 binding 分摊；practice_unit 级 memory 保留为「题级缓存」（= 其 KC 分摊结果的确定性函数），KC 为权威层。
- 分摊权重：`strength_k / Σ_j strength_j`；同 binding 多 revision（basis_revision_id 不同）取 accepted_at 最新的 taxonomy_version 组。
- 视觉通道现行 PRIMARY 0.6 / SECONDARY 0.4 pool 与统一规则冲突——改为「PRIMARY = 最大 strength 的绑定」，其余按 strength 归一，常数删除。

### 3.3 前置驱动调度（修 L4）

- `readyToLearn(k) = ∀p∈prereq(k): masteryScore_p ≥ τ_ready`，τ_ready 默认 0.6（ALEKS ready-to-learn 思想，KST：Doignon & Falmagne；来源见 research 报告 §2.4/KC 节）。
- 选题打分新增两项（ReviewPlannerV2.scoreCandidate 扩展）：
  - `prereqGap(k) = max(0, τ_ready − min_p masteryScore_p)` → 权重 W3，gap>0 时本题候选降权；
  - `remediation(k) = argmin_p masteryScore_p` → 对该前置 KC 检索 `knowledge_teaching_material`（material_node_binding.role）注入「先读材料再做题」的会话项。
- **实现状态（2026-09-12）**：两项均已接线。判定的唯一权威是 `KnowledgeReadiness`（core:domain）；前置图由 `KnowledgePrerequisiteReader` 解析（按 KC 自己的科目分区、256 分块）并在 `StudyReviewPlannerService` 喂入 `ReviewPlanningRequest`。降权侧产出 `ReviewReason.PREREQ_GAP`；会话侧由 `StudyExperienceRepository.prerequisiteRemediation` 把**前置 KC** 的材料作为 `ReviewSessionScreen` 上的一张卡呈现。**注入是非阻塞的**：材料与题干并存，不设"先确认"门——本条原文的"先读材料再做题"读起来像前置闸门，但 R9 与外部范式（Khan Readiness Check / ALEKS）都要求与常规排期**并行**，且"先补前置 vs 继续做题"没有正面比较实验支持（`leech-remediation-research.md` §6.5）。措辞以 R9 为准。
- 多 KC 题：取各 KC 的 min(masteryScore) 作为该题 weakness 输入（保守口径，与 conservativeMasteryScore 一致）。

### 3.4 KC 结构变更与版本迁移（修 L5）

- 以 `taxonomy_version` 为迁移键：绑定与 KC 均带版本；投影按 (learner, knowledge_node_id, taxonomy_version) 记账。
- 换绑（题改 KC）：旧 binding 行 accepted_at 保留、新增行；投影对旧 KC 停止计入新证据（binding 失效时间后），**不回滚历史**；evidence_mass 随新证据自然稀释。
- KC 拆分/合并：新增 taxonomy_version，把旧 KC 的 evidence_mass 按 binding.strength 比例初值化到新 KC（一次性迁移 SQL，v36+）。
- 错绑修正：Learning Factors Analysis 思路（https://doi.org/10.1007/s11251-005-1310-x ）远期自动化；近期靠审校（verification_status + grounding 链）。

### 3.5 冷启动（知识库不完善时的最小关联）

- KC 绑定稀疏的题：按 `problem.subject` 聚合为伪 KC（id 形如 `pseudo:<SUBJECT>`），master state 同结构；真绑定建立后按 3.4 换绑迁移。
- 绑定建立的三条来源（优先级递减）：① organization 管道（grounding→classification_binding→accepted）；② 模型建议 + 用户确认；③ 人工。现状 ①② 已有链路，缺的是「无绑定题自动落 pseudo-KC」——补一条投影兜底分支即可。
- 知识库审计基准：KC 质量以「学习曲线平滑单调降」为验收（DataShop 实践）——我们已有 per-KC evidence 序列，可离线跑曲线检查作为知识库健康度指标。

### 3.6 重教通道（修 L6/L7）

- 触发：① 前置 gap（3.3）；② error_type=concept（错因分类落地后）；③ 用户在详情页主动点「重新学这个知识点」。
- 检索：`knowledge_teaching_material` join `material_node_binding`，检索**调用方传入的** `knowledgeNodeIds`，按（binding 角色，材料类型优先级，materialId）排序，取 1-2 份注入会话（现有 TutorTeachingReferenceRepository 已具备注入机制）。
- **本条的三处 2026-09-11 核实修正**（原表述与该实现不符）：① `material_node_binding.role` 是**角色标签**（`KnowledgeMaterialNodeRole`：PRIMARY / SUPPORTING / PREREQUISITE），**不存在数值权重**——原文"role 权重排序"有误，排序用的是角色的**序**（PRIMARY → SUPPORTING → 其他），不是加权；② "∪ 其前置"由**调用方**决定（`referencesFor` 只检索传入的节点集合）——**2026-09-12 起两条通道都显式并入了前置节点**：§2.9 前置补救在**前置 KC** 自己的范围内取材料，§2.16 重教在题目绑定 KC 的范围内取；③ 材料类型优先级的权威表达在 `TutorTeachingReferenceSelector.reTeachPriority`（依据 Metcalfe 2017/2025，见 `docs/research/leech-remediation-research.md` R5/R6），DAO 的 `ORDER BY` CASE 必须与它逐值一致，否则"预算先给了哪类材料"变回未定义行为。
- 前置条件：answer_spec/error_type 两根柱子落地前，此通道只能由 ①③ 触发——不阻塞，按阶段 C 渐进。

---

## 4. 落地顺序（依赖）

1. **L1 修 library_catalog**（改视图 join learner_* 表 + learner_id 参数化）——独立，收益立现；
2. **L2/L3 统一证据分摊**——依赖 math-modeling §9 的权重表；
3. **L4 前置接线**——依赖 L2（KC mastery 才有真实输入）；
4. **L5 版本迁移**——依赖 L2/L4 稳定后做 v36；
5. **L6/L7 重教通道**——依赖错因/答案柱子，最后。

---

## 闭环实施记录（2026-08-29，v39）

**写权限矩阵**见 spec §5 表。关键裁决：LLM 的影响写入掌握库内**自己的表**（llm_teaching_advisory），不碰投影行——投影行由 LearningProjector 从账本独家重建，任何直改都会被全量重放覆盖；咨询层与证据层从此互不污染。

**已落地**
- v39：`llm_teaching_advisory` 表（UNIQUE(learner,source_id,kind) 幂等；TEACHING_FOCUS/MISCONCEPTION 两类）+ `knowledge_question_lattice` 视图（绑定×KC掌握×题记忆×错题的显式格点查询，KC→题传导的读取面）。
- 讲题输出→advisory 静默落库：SavedMistakeTutorRoute 观察 TUTOR_PLAN 任务，`targetedEvidenceLabels/inferredKnowledgeLabels` 非空即经 `recordTeachingFocus` 写入（UNIQUE 幂等，无 UI）。
- KC→错题传导：`ReviewReason.KC_MASTERY_DROP`（连续压力项，见 spec §5）双 planner 生效。
- L1-L5 状态更新：L1 视图修复（v36）、C1 全KC证据、L4 先修/伪KC（v36 伪绑定）、L2/L3 全KC语义——均已在此前轮次闭环；L6/L7 教辅重教通道由 advisory+AVOIDANCE_SIGNAL/KC_MASTERY_DROP 承接入口。

**待办（上一轮三项已于 2026-08-30 闭环）**
- debrief 独立模型任务：✅ 接在预留的 LEARNING_SUMMARIZE kind 上（TutorDebriefInput/Output + prompt/parse + 合同注册 + 静默触发：离开讲题页时本地模型（LOCAL_NO_EGRESS）才执行，外部提供方为隐私跳过；完成后 MISCONCEPTION advisory 自动落库，全程无 UI）。
- advisory 注入讲题 prompt：✅ TutorQuestionContext/TutorPlanInput 增 priorTeachingAdvisories，plan prompt 增 8a 规则（仅作讲法参考、不当指令、不向学生复述）。
- per-reason 权重标定：✅ review_log.planned_reason（v38）→ ReviewSample.plannedReason → calibratePlannedReasons 表（per-reason 实际回忆率 vs 总基线，≥200 条开门）；仓储 plannedReasonCalibrations() 暴露。标定本身仍待数据积累。

**生产可跑通验证（2026-08-30）**
- 新增 CapturedReviewLoopInstrumentedTest（真机 Room）：无绑定的拍摄题 → 真实复习会话 →
  四键评分 → FSRS 首次复习稳定度 = w[2]（经 lattice 视图实测）→ review_log 落行 →
  计划下一日含该题。全程无 curated fixture、走伪 KC 兜底，即学生设备真实路径。
- **发现并修复一个真实生产 bug**：无绑定题首次生成复习计划时，`review_queue_knowledge_node`
  外键指向尚不存在的 `pseudo:<SUBJECT>` 节点（此前只在首次提交评分时物化）→ 计划保存
  SQLiteConstraintException。修复：createReviewPlan 建计划前先 `ensurePseudoKnowledgeBinding`
  物化伪节点（幂等），候选回落使用同一节点 id。
- 投影排水为观察者异步收敛（提交后毫秒级由 ledger 观察任务驱动）；测试用 `refresh()`
  显式同步，生产 UI 经 StateFlow 消费同路径。
- **优化器生产触发补齐**：启动初始化后静默运行 `optimizeSchedulingParameters()`
  （自门控：≥64 样本全量拟合，<8 返回默认；输入截取最近 2 万条防爆；失败不破坏启动；
  拟合参数次次启动才生效=灰度）。

**本轮深查补齐的缺口**
- KC_MASTERY_DROP 传导此前无测试：新增 4 例（负向证据触发/越跌权重越高连续性/未到期也能因权重提前入场/正向证据无传导）。
- lattice 视图此前无 Kotlin 读 API：新增 KnowledgeQuestionLatticePort（v39 视图按 learner 过滤，视图补 kc_learner_id/memory_learner_id 两列，39.json 与迁移 SQL 同步重生成）+ 仓储 observeKnowledgeQuestionLattice()。
- recordTeachingFocus/MISCONCEPTION advisory 无持久化测试：新增 2 例（UNIQUE 幂等、kind 区分）。
- 讲题 prompt 注入 advisory 时 commit 校验：TutorPlanInput 校验 priorTeachingAdvisories（≤8 条、每条 ≤1000 字符）。
