package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorToolName

/**
 * 工具声明的**同一页口径**与轮次级可用性。
 *
 * 这一层要解决的是"声明什么"与"放行什么"必须能被分开表达：声明集是**页面级**的（大厅与讲题
 * 会话是同一个页面，`docs/tutor-surface-unification.md` §5.6），可用性是**调用/轮次级**的。
 * 把两者混在一处，就会出现此前那种局面：同一个页面上"模型能申请什么"随轮次类型跳变。
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

/** 会**落库**的两个工具：它们的准入不是"声明集里有"，而是这一**次**调用真的锚住了题。 */
val TUTOR_WRITE_TOOLS: Set<TutorToolName> = setOf(
    TutorToolName.MASTERY_UPDATE,
    TutorToolName.NOTEBOOK_WRITE,
)

/**
 * 产出**只被题轮披露集合覆盖**的读工具（学习证据与学科知识库）。
 *
 * 无题轮的 `TUTOR_LOBBY_DISCLOSURE` 只有"学生消息 + 会话上下文"，装不下它们的产出
 * （`TutorLobbyTasks.ALLOWED_LOCAL_CAPABILITIES` 早已为掌握度读取写下同一条理由），所以
 * 无题轮里它们不发。`NOTEBOOK_READ` **不在此列**：错题本条目一直是大厅轮次的能力
 * （大厅自始声明并使用它，同一条能力边界注释里也只把掌握度读取列为不可覆盖）。
 */
val TUTOR_QUESTION_ROUND_ONLY_READS: Set<TutorToolName> = setOf(
    TutorToolName.MASTERY_READ,
    TutorToolName.KNOWLEDGE_READ,
)

/**
 * 轮次/调用级可用性：声明集里的工具这一次到底能不能执行。
 *
 * - 写工具（[TUTOR_WRITE_TOOLS]）：要求这**一次调用**锚住了本轮的题
 *   （[callIsAnchoredToRoundQuestion] = [com.tingyun.smartmistakebook.core.model.TutorToolCall.boundQuestion]
 *   经本地两条校验 resolve 通过，**或**模型没复述时回退到本轮请求侧已知的题锚
 *   [com.tingyun.smartmistakebook.core.model.TutorRespondInput.knownRoundQuestion]）——它们会落库，
 *   没有题目锚点就是无主证据。
 * - 产出口袋被题轮披露集合覆盖的读工具（[TUTOR_QUESTION_ROUND_ONLY_READS]）：要求本轮派发的
 *   披露面覆盖它们的产出。
 * - 其余读工具不受限。
 *
 * 两个参数都取自**请求/调用本身**，不取自"这一轮由哪种解析路由产出"：原生 tool_calls 路由的
 * 表达位置是每次调用的 arguments（或请求侧已知锚），json_object 信封路由是同一份调用对象的
 * 字段——两条路由都能满足上面每一条。
 *
 * 具名函数而不是内联在工具环里，是为了让它可被单测直接钉死。
 */
fun tutorRoundToolAvailable(
    tool: TutorToolName,
    callIsAnchoredToRoundQuestion: Boolean,
    roundDisclosesQuestionEvidence: Boolean,
): Boolean = when (tool) {
    in TUTOR_WRITE_TOOLS -> callIsAnchoredToRoundQuestion
    in TUTOR_QUESTION_ROUND_ONLY_READS -> roundDisclosesQuestionEvidence
    else -> true
}
