package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate

/**
 * 本轮候选菜单的第三条来源：本地文本检索。
 *
 * 前两条（本轮学生显式添加的题、上一轮绑定的题）是身份，由调用方直接给出；这一条要把学生
 * 这一轮说的话变成一组候选，所以是一条**读**能力，单独成口以便界面替身与测试各自给实现。
 *
 * 返回的候选只进菜单，不构成绑定：绑定仍要模型声明 + 本地两条校验
 * （[TutorRoundQuestionBindingPolicy.resolve]）。
 */
fun interface TutorRoundQuestionRetriever {
    /**
     * @param catalog 本地错题目录快照（界面已有，不必再查库）。
     * @param studentMessage 学生这一轮的原话。
     * @param excluded 已经以更高优先级进过菜单的题（显式添加、上一轮绑定），不必重复检索。
     *   传候选本身而不是另造一个修订引用类型：调用方手上就是候选。
     * @param limit 最多返回几条。
     */
    suspend fun retrieve(
        catalog: List<StudyCatalogEntry>,
        studentMessage: String,
        excluded: List<RelatedProblemCandidate>,
        limit: Int,
    ): List<RelatedProblemCandidate>
}
