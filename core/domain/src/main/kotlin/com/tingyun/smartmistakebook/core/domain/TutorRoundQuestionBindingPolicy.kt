package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorRoundQuestionDeclaration
import java.util.Locale

/**
 * 「这一轮在说哪一道题」的本地政策：**本地给菜单、模型给语义、本地做裁决**。
 *
 * 为什么要有它：绑定此前是会话级的（一个会话锚一道题），而产品的裁定是**按轮次语义判定**
 * （`docs/tutor-surface-unification.md` §0 与 §5.4）。轮次级绑定必须能表达"有没有题、是哪道
 * 题"，而无题轮不能写、不能泄答案，所以裁决权不能交给模型：模型只声明"我认为是这一道"，
 * 本地核两条，两条都过才算绑定。
 *
 * 消灭的失败：模型（或提示词漂移、或上游被注入）随口指一道本轮根本没给学生看过的题，本地
 * 直接拿它当锚去写学习证据或展开答案——锚指向学生没见过的题，写进去的证据与泄出去的答案
 * 都无主。菜单限定了它能指的范围，逐字锚限定了他能指的依据。
 */
object TutorRoundQuestionBindingPolicy {

    /** 与 [TutorRespondInput.MAX_BOUND_QUESTION_CANDIDATES] 同一口径：菜单最多八条。 */
    const val MAX_CANDIDATES: Int = TutorRespondInput.MAX_BOUND_QUESTION_CANDIDATES

    /**
     * 锚词的最短长度。单个字符（尤其单个汉字）几乎能在任何一句中文里找到，无法把一道题与
     * 另一道区分开——它只证明"学生说了话"，不证明"学生在说这道题"。
     */
    const val MIN_ANCHOR_TERM_CHARS: Int = 2

    /**
     * 组本轮候选菜单，按优先级保序去重后截断：
     * 1. 本轮学生显式添加的题（加号里"从错题库选择"选中的）——学生的直接意图最强；
     * 2. 上一轮绑定的题——跨轮延续（`docs/tutor-surface-unification.md` §5.4）；
     * 3. 本地文本检索的前 N 条。
     *
     * 同一道题的同一修订只出现一次（`problemId` + `problemRevisionId` 精确配对）：菜单里出现
     * 两个同 id 同修订的条目，模型声明"那一道"时就没有唯一解，本地只`singleOrNull`一条都绑不上。
     */
    fun assembleCandidates(
        explicitlyAdded: List<RelatedProblemCandidate>,
        previouslyBound: List<RelatedProblemCandidate>,
        retrieved: List<RelatedProblemCandidate>,
    ): List<RelatedProblemCandidate> = (explicitlyAdded + previouslyBound + retrieved)
        .distinctBy { candidate -> candidate.problemId to candidate.problemRevisionId }
        .take(MAX_CANDIDATES)

    /**
     * 两条本地校验，缺一不可；任一条不过就是**无题轮**（返回 null），不抛异常、不放行。
     *
     * 1. **候选在菜单内**：按 `problemId` + `problemRevisionId` 精确匹配。同一道题的另一个
     *    修订不算命中——题面变了就是另一道题，答案与证据都不可搬。
     * 2. **学生消息里有可核对的词**：声明的每一个锚词都要在学生这一轮的消息里逐字出现
     *    （沿用 `TutorIntentAuthority.actionIsBoundTo`/`lookupTerms` 的逐字锚纪律），并且
     *    至少一个锚词要能在**该题自身**（标题或题面）里找到。前一条挡住"模型替学生编了
     *    一句他没说过的话"，后一条挡住"词是学生说的、但说的不是这道题"。
     *
     * 校验通过返回原声明（内容逐字未改），由调用方作为本轮绑定落库。
     */
    fun resolve(
        candidates: List<RelatedProblemCandidate>,
        declaration: TutorRoundQuestionDeclaration?,
        studentMessage: String,
    ): TutorRoundQuestionDeclaration? {
        if (declaration == null) return null
        val candidate = candidates.singleOrNull { candidate ->
            candidate.problemId == declaration.problemId &&
                candidate.problemRevisionId == declaration.problemRevisionId
        } ?: return null
        if (declaration.anchorTerms.isEmpty()) return null
        val normalizedMessage = studentMessage.lowercase(Locale.ROOT)
        val normalizedTerms = declaration.anchorTerms.map { term -> term.lowercase(Locale.ROOT) }
        if (
            normalizedTerms.any { term ->
                term.length < MIN_ANCHOR_TERM_CHARS || term !in normalizedMessage
            }
        ) {
            return null
        }
        val questionText = candidate.checkableText()
        if (normalizedTerms.none(questionText::contains)) return null
        return declaration
    }

    /**
     * 该题**自身**的文本：锚词必须在这一段里能找到，否则"可核对"无从谈起。
     *
     * 只算标题与题面，**不算 `subject.name`**：那是枚举名（`MATH`/`PHYSICS`…），与题目内容毫无
     * 关系；一旦算进去，学生消息里出现该英文串时，任何一条同科目候选都能被"核对"上——菜单有
     * 八条时，模型可随手挑一条同科目候选、拿一个科目名当锚词通过，这条闸门就等于没有。
     * 科目是分类，不是这道题的内容。
     */
    private fun RelatedProblemCandidate.checkableText(): String = buildString {
        append(title)
        append('\n')
        append(QuestionDocumentMarkdownProjection.project(questionDocument))
    }.lowercase(Locale.ROOT)
}

/**
 * 上一轮绑定的题——候选菜单的第二条来源（`docs/tutor-surface-unification.md` §5.4：可跨轮）。
 *
 * 取**最近一条已成功回复、且本轮声明确实通过了本地校验**的 RESPOND 任务，再从它自己那一轮的
 * 菜单里把候选捞出来（候选里带着题面，所以不必再读一次库）。
 *
 * 为什么必须回读"已校验的绑定"而不是回读模型声明：声明不是裁决。上一轮如果声明越界、锚词不
 * 对，那一轮就是无题轮——把它的声明当成"上一轮绑定的题"会把这个错误顺延到下一轮。
 *
 * 没有已绑定轮次时返回 null（首轮就是无题轮）。
 */
fun previousBoundRoundQuestion(tasks: List<ModelTaskSnapshot>): RelatedProblemCandidate? =
    tasks.asSequence()
        .mapNotNull { task ->
            val input = task.request.input as? TutorRespondInput ?: return@mapNotNull null
            val output = task.output as? TutorRespondOutput ?: return@mapNotNull null
            if (task.status != ModelTaskStatus.SUCCEEDED) return@mapNotNull null
            // output.boundQuestion 已经是**校验过**的绑定（解析层是唯一写入口）。
            val binding = output.boundQuestion ?: return@mapNotNull null
            val candidate = input.boundQuestionCandidates.singleOrNull { candidate ->
                candidate.problemId == binding.problemId &&
                    candidate.problemRevisionId == binding.problemRevisionId
            } ?: return@mapNotNull null
            task.createdAtEpochMillis to candidate
        }
        .maxByOrNull { (createdAtEpochMillis, _) -> createdAtEpochMillis }
        ?.second
