package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection
import com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier

/**
 * 知识点复习作答的回写判决（spec dual-review-entry §3.4）：复习作答是**客观**对错信号
 * （学生答对/答错选择题），比模型自报语义更可信。把这一客观对错确定地映射成掌握度证据
 * 的语义——供 `MasteryWriteGate`（本地门控做主：冷却/配额/注意力）评估后回写。
 *
 * 这个纯函数消灭的核心失败：调用方各自解释"答对/答错该记什么档位"导致标准不一、污染
 * 掌握度。此处统一确定：答对 → POSITIVE+CONFIDENT（行为佐证为真，可升）; 答错 →
 * NEGATIVE+STRUGGLING（客观卡点）。
 */
data class KnowledgeQuizMasteryVerdict(
    val direction: TutorEvidenceDirection,
    val understanding: TutorUnderstandingTier,
    /** 答对本身就是行为佐证；答错是客观失败。 */
    val hasBehavioralSupport: Boolean,
)

/** 从知识点复习选择题的客观对错，确定回写掌握度的证据语义。 */
fun knowledgeQuizMasteryVerdict(isCorrect: Boolean): KnowledgeQuizMasteryVerdict = when {
    isCorrect -> KnowledgeQuizMasteryVerdict(
        direction = TutorEvidenceDirection.POSITIVE,
        understanding = TutorUnderstandingTier.CONFIDENT,
        hasBehavioralSupport = true,
    )
    else -> KnowledgeQuizMasteryVerdict(
        direction = TutorEvidenceDirection.NEGATIVE,
        understanding = TutorUnderstandingTier.STRUGGLING,
        hasBehavioralSupport = false,
    )
}

/**
 * 知识点复习作答回写的结果（spec dual-review-entry §3.4）。
 */
data class KnowledgeQuizFeedbackResult(
    /** 是否答对——客观对错，不依赖模型判断。 */
    val isCorrect: Boolean,
    /** 该作答能否作为掌握度证据写入（true = gate 接受）。 */
    val evidenceRecorded: Boolean,
    /** gate 拒绝原因（evidenceRecorded=false 时非空）；null = 已写入或无需判定。 */
    val rejectedReason: String? = null,
)
