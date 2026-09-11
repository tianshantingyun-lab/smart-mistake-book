package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorTeachingReference

/**
 * 前置补救：目标题有一个前置知识点未达可学门槛时，会话里呈现**那个前置 KC** 的讲解材料
 * （spec §2.9「gap>0 时检索 teaching_material 注入补救项」）。
 *
 * 与 [ReTeachOpening] 的关键差别是**不阻塞**：补救材料在题干旁并列呈现，学员可以直接作答，
 * 不必先确认。理由来自两侧证据的不同强度——
 * - leech 卡已经连续失败 6 次，"不重教就再出同一道题"是**重复一个已被证明无效的动作**，
 *   所以开场是必经步骤；
 * - 前置缺失只说明该题大概率偏难，而"先补前置 vs 继续做题"的正面比较实验**没有找到**
 *   （`docs/research/leech-remediation-research.md` §6.5，只有 productive failure 的间接
 *   约束），且外部范式（Khan Readiness Check / ALEKS）的做法是与主课程**并行**运行、
 *   不阻塞。把间接推断当成阻塞门，代价是学员可能根本答得出来的题被一道材料挡住。
 *
 * 材料取自**前置** KC 而非目标 KC：gap 度量的是"缺的那个前置"，目标 KC 自己的讲解与它
 * 并不对应（那正是 [ReTeachOpening] 的场景）。此处与 spec §2.9「对该 KC 检索材料」的措辞
 * 有出入——该措辞可读作"对目标 KC"，但落地方案 R9 明确写的是"注入**前置 KC** 的材料"，
 * 且只有后者针对所度量的缺口，故按 R9 实现，并在 spec 中记明这处歧义与取舍。
 */
data class PrerequisiteRemediation(
    val prerequisiteName: String,
    val title: String,
    val markdown: String,
) {
    init {
        require(prerequisiteName.isNotBlank()) { "Prerequisite remediation needs a display name" }
        require(title.isNotBlank()) { "Prerequisite remediation needs a material title" }
        require(markdown.isNotBlank()) { "Prerequisite remediation needs teachable content" }
    }
}

/**
 * 判定该不该注入前置补救、以及注入哪一份（spec §2.9）。
 *
 * 材料**不在此处排序**：调用方传入 [TutorTeachingReferenceRepository] 已排序的结果，本函数
 * 取首项即"最对症的那份"。优先级（以及"针对错误认知的材料优先、`COMPLETE_SOLUTION` 最后"）
 * 的唯一权威仍是 `TutorTeachingReferenceSelector.reTeachPriority`，此处不复制顺序，避免
 * 两条注入通道对同一份材料给出不同次序。
 *
 * 返回 null 的两种情形都有意义：没有低于门槛的前置（无需补救），或该前置知识点确实没有
 * 讲解材料（不能编造补救内容）。两者对调用方是同一结果——不呈现补救卡。
 */
object PrerequisiteRemediationPolicy {
    fun offer(
        prerequisiteName: String,
        references: List<TutorTeachingReference>,
    ): PrerequisiteRemediation? {
        if (prerequisiteName.isBlank()) return null
        val reference = references.firstOrNull() ?: return null
        return PrerequisiteRemediation(
            prerequisiteName = prerequisiteName,
            title = reference.title,
            markdown = reference.asReadOnlyTeachingBlock(),
        )
    }
}
