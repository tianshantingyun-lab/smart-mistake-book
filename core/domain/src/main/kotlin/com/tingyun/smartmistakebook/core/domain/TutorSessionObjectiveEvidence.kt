package com.tingyun.smartmistakebook.core.domain

/**
 * 讲题会话内**本地判定**的客观作答记录（诊断题对错）。
 *
 * 对错由本地比对 `TutorAssessmentItem.evaluateChoice`（学生所选 id == 模型声明的
 * correctChoiceId）算出并落 `tutor_turn_response.selection_was_correct`，**不是**
 * 模型自报——因此是研究 `tutor-evidence-gate-research.md` §3.2 所说的"行为证据"。
 *
 * 消灭的失败：模型可以在同一会话里，对着学生刚刚**答错**它自己出的检查题的对话，
 * 判 POSITIVE 并写进掌握度。本地此前不做任何交叉核对（runner 恒传
 * `hasObjectiveSupport = false`），口头声明就这样压过了行为证据——而 §3.2 的
 * 结论（Koriat & Bjork 2005；Nelson & Dunlosky）恰好相反：冲突时行为证据胜出，
 * 口头声明降级为观察记录。
 */
data class TutorSessionObjectiveRecord(
    /** 本会话已判对错的客观作答条数。 */
    val answeredCount: Int,
    /** 其中答对的条数。 */
    val correctCount: Int,
) {
    init {
        require(answeredCount >= 0) { "Answered count must not be negative" }
        require(correctCount in 0..answeredCount) {
            "Correct count $correctCount must be within 0..$answeredCount"
        }
    }

    val incorrectCount: Int get() = answeredCount - correctCount

    /**
     * 会话内学生的客观作答与"正向掌握"判断相冲突：存在答错的检查题。
     * 只要有一条答错，模型在本会话内的 POSITIVE 声明就被这条行为证据推翻。
     */
    val contradictsPositiveClaim: Boolean get() = incorrectCount > 0
}

/**
 * 从**本轮**（cycle）的客观对错序列汇总出 [TutorSessionObjectiveRecord]。
 *
 * 只计本轮：`restartCycle` 会在同一题上开新一轮重教，上一轮的答错正是重教的理由；
 * 若把历史轮次的答错永久计入，学生重教后答对也永远洗不掉，门就变成不可达的死门
 * （与档2 修的 0.18 死常数同类）。
 */
fun tutorSessionObjectiveRecord(correctnessInCurrentCycle: List<Boolean>): TutorSessionObjectiveRecord =
    TutorSessionObjectiveRecord(
        answeredCount = correctnessInCurrentCycle.size,
        correctCount = correctnessInCurrentCycle.count { it },
    )
