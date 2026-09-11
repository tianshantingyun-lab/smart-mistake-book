package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference

/**
 * 一次"先重教再练"的开场材料：leech 卡进入复习会话时，先呈现这段材料，再让学员作答
 * （spec §2.16「强制注入 teaching_material 重教」）。
 *
 * 它是**只读**的教学材料，不带答案键、不产生任何学习证据——与
 * [TutorTeachingReference] 的边界一致。展示它不写事件、不刷新时钟，因此可以安全地在
 * 每次会话开场重复呈现（区别于 `StudyAnswerRevealRequest`：那是一条会被记入账本的
 * "看了答案"事件，用它来做重教会把学员的独立作答污染成"看答案后作答"）。
 */
data class ReTeachOpening(
    val materialId: String,
    val title: String,
    val materialType: KnowledgeTeachingMaterialType,
    val markdown: String,
) {
    init {
        require(materialId.isNotBlank()) { "Re-teach opening needs a material id" }
        require(title.isNotBlank()) { "Re-teach opening needs a material title" }
        require(markdown.isNotBlank()) { "Re-teach opening needs teachable content" }
    }
}

/**
 * 判定一张卡是否**必须**先重教，并把它该看到的那份材料挑出来（spec §2.16）。
 *
 * 消灭的失败：leech 卡此前只是"被降权、难度冻结"——学员第 7 次打开的还是那道已经连续
 * 失败 6 次的题，**没有任何重教发生**。而 spec §2.16 自述 leech「多为概念错」，即失败
 * 的成因在概念本身，不呈现对症材料就重复出题，是在重复那个已经被证明无效的动作。
 * 把判定放在这里而不是放在 Room 仓储里，是为了让它可被纯函数测试覆盖（仓储路径需要
 * 真实投影才能触发 leech，难以在单测里构造；而"leech ⇒ 必须重教"这条规则本身是纯粹的）。
 *
 * 材料**不是**在此处排序的：调用方应传入 [TutorTeachingReferenceRepository] 已按重教
 * 优先级排好序的结果，本函数取首项即"最对症的那份"。优先级策略的唯一权威在
 * `TutorTeachingReferenceSelector.reTeachPriority`（依据 Metcalfe 2017/2025，见
 * `docs/research/leech-remediation-research.md` R5/R6），此处不再复制一份顺序，
 * 避免两处漂移。
 *
 * 返回 null 的两种情形都有意义：卡未 leech（正常复习，不该被强制重教），或该知识点
 * 确实没有讲解材料（此时不能编造材料，也没有别的补救内容可注入）。
 */
object ReTeachInjection {
    fun openingFor(
        memory: ProblemMemoryState?,
        references: List<TutorTeachingReference>,
    ): ReTeachOpening? {
        if (memory?.isLeeched != true) return null
        val reference = references.firstOrNull() ?: return null
        return ReTeachOpening(
            materialId = reference.materialId,
            title = reference.title,
            materialType = reference.materialType,
            markdown = teachableMarkdown(reference),
        )
    }

    /**
     * 正文 + 适用范围。边界（`boundaryMarkdown`）必须一并呈现：重教材料是"针对这类错误
     * 认知"的，不写出它的适用条件就等于让学员把它外推到不成立的题目上——而
     * `MISCONCEPTION_GUIDE` / `WORKED_EXAMPLE` 正是最容易被过度外推的两类。
     * 该字段由 [TutorTeachingReference] 的构造约束保证非空，故这里没有空值分支。
     */
    private fun teachableMarkdown(reference: TutorTeachingReference): String =
        reference.contentMarkdown.trim() + "\n\n**适用范围**：" + reference.boundaryMarkdown.trim()
}
