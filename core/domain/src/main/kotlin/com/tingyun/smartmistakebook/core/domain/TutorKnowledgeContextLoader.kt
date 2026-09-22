package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorKnowledgeCode

/**
 * 讲题会话的知识点**预披露**加载（ADR 0001 / D5 单一代号通道的派发侧取数）。
 *
 * 拍照讲题（产品主入口）此前对知识库零注入（审计 §1.4 断链一）：本接口把归类路径现成的
 * 两段式检索（B 路 SQL 倒排召回 512 → A 路 n-gram 打分器精排，与错题归类同源）搬到讲题
 * 派发的取数侧，让 Plan/Respond 的输入携带当前题的候选知识点——材料走既有的
 * `TutorTeachingReferenceRepository.referencesFor` / 20k 预算选择器，代号条目走本结果。
 *
 * 返回值是**未赋码**条目（`TutorKnowledgeCode.code = null`）：K1..Kn 的分配是**会话级**
 * 状态（首现顺序、会话内稳定，KNOWLEDGE_READ 还会运行中追加），由 core:data 的仓库在
 * execute() 入口统一赋码——取数侧不掌握会话历史，无权预编号。
 */
interface TutorKnowledgeContextLoader {
    /**
     * @param subject 当前题科目（枚举名，如 MATH）。
     * @param confirmedBindingNodeIds 当前题**已确认**绑定的知识点（表内绑定，如错题讲题的
     *   归类结果）；非空时角色为 CONFIRMED_BINDING，不再跑检索。
     * @param questionText 题面文本（Markdown 投影）：确认绑定为空时（拍照会话，尚无表内
     *   绑定）用它跑两段式检索出候选（角色 RETRIEVAL_CANDIDATE）。零命中 = 空结果
     *   （合法空注入，维持现状语义），[loadFailed] 保持 false。
     * @param loadFailed 数据读取失败（区别于零命中的合法空集）：调用方必须把它显式披露进
     *   prompt（"教学材料未加载"），不得静默降级——静默 catch{emptyList()} 是审计指出的
     *   失败模式（SavedMistakeTutorRoute 既有模板的修正版）。
     */
    suspend fun knowledgePreDisclosure(
        subject: String,
        confirmedBindingNodeIds: List<String>,
        questionText: String?,
    ): TutorKnowledgeContextResult
}

/**
 * 预披露结果。[preDisclosures] 顺序 = 提示词映射表顺序（确认绑定 / 检索候选 在前，
 * 前置在后）；[candidateNodeIds] 供调用方喂 `referencesFor`（材料注入）与
 * `TutorQuestionContext.relatedKnowledgeNodeIds`（其构造契约要求材料节点 ⊆ 该集合）。
 */
data class TutorKnowledgeContextResult(
    val preDisclosures: List<TutorKnowledgeCode>,
    val candidateNodeIds: List<String>,
    val loadFailed: Boolean,
)
