package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorToolName

/**
 * 工具声明的**同一页口径**与轮次级可用性。
 *
 * 这一层要解决的是"声明什么"与"放行什么"必须能被分开表达：声明集是**页面级**的（大厅与讲题
 * 会话是同一个页面，`docs/tutor-surface-unification.md` §5.6），可用性是**轮次级**的（意图 ×
 * 置信度 × 声明集，再叠一条"这一轮的披露面装不装得下它的产出"）。把两者混在一处，就会出现
 * 此前那种局面：同一个页面上"模型能申请什么"随轮次类型跳变，无题轮里学生问错题本，模型连
 * 可申请的工具都没有。
 *
 * 放在 core:domain 而不是 core:data：声明集与可用性同时被数据层（工具环）与界面层（派发装配）
 * 读到，而 feature 模块看不到 core:data 的 internal。
 */

/** 页面上的全量工具声明集（顺序稳定，便于提示词与测试逐项比对）。 */
val TUTOR_TOOL_DECLARATIONS: Set<TutorToolName> = linkedSetOf(
    TutorToolName.KNOWLEDGE_READ,
    TutorToolName.NOTEBOOK_READ,
    TutorToolName.MASTERY_READ,
    TutorToolName.MASTERY_UPDATE,
    TutorToolName.NOTEBOOK_WRITE,
)

/** 会**落库**的两个工具：它们的准入不是"声明集里有"，而是"本轮有绑定题"。 */
val TUTOR_WRITE_TOOLS: Set<TutorToolName> = setOf(
    TutorToolName.MASTERY_UPDATE,
    TutorToolName.NOTEBOOK_WRITE,
)

/**
 * 产出**装不进无题轮披露集合**的读工具：它们的产出是学习证据与学科知识库，而
 * `TUTOR_LOBBY_DISCLOSURE` 只有"学生消息 + 会话上下文"（`TutorLobbyTasks.ALLOWED_LOCAL_CAPABILITIES`
 * 已经为掌握度读取写下同一条理由）。声明它们但让它们只对有题轮可用，得到的结果是：同一个页面上
 * 声明集一致，而不存在任何"以不含该数据类的披露集合把它发出去"的轮次。
 *
 * NOTEBOOK_READ 不在此列：错题本条目本来就是大厅轮次现有且被接受的能力（大厅一直声明它）。
 */
val TUTOR_QUESTION_BOUND_ONLY_READS: Set<TutorToolName> = setOf(
    TutorToolName.MASTERY_READ,
    TutorToolName.KNOWLEDGE_READ,
)

/**
 * 轮次级可用性：声明集里的工具在这一轮到底能不能执行。
 *
 * - 写工具（[TUTOR_WRITE_TOOLS]）：只有"本轮确实有绑定题"（模型声明通过了
 *   [TutorRoundQuestionBindingPolicy] 的两条本地校验）才放行——它们会落库，没有题目锚点就是
 *   无主证据。
 * - 产出装不进本轮披露面的读工具（[TUTOR_QUESTION_BOUND_ONLY_READS]）：同样要求有绑定题。
 *   无题轮的披露集合覆盖不到它们的产出，任其进入就等于放宽那条通道的披露面。
 * - 其余读工具不受限。
 *
 * 具名函数而不是内联在工具环里，是为了让它可被单测直接钉死——"无题轮申请写入仍被拒"这条
 * 不变量必须有一条不依赖数据库的用例。
 */
fun tutorRoundToolAvailable(
    tool: TutorToolName,
    roundHasBoundQuestion: Boolean,
): Boolean = when (tool) {
    in TUTOR_WRITE_TOOLS, in TUTOR_QUESTION_BOUND_ONLY_READS -> roundHasBoundQuestion
    else -> true
}
