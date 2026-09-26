package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ModelTaskInput
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
     * 三条本地校验，缺一不可；任一条不过就是**无题轮**（返回 null），不抛异常、不放行。
     *
     * 1. **候选在菜单内**：按 `problemId` + `problemRevisionId` 精确匹配。同一道题的另一个
     *    修订不算命中——题面变了就是另一道题，答案与证据都不可搬。
     * 2. **学生消息里有可核对的词**：声明的每一个锚词都要在学生这一轮的消息里逐字出现
     *    （沿用 `TutorIntentAuthority.actionIsBoundTo`/`lookupTerms` 的逐字锚纪律），并且
     *    至少一个锚词要能在**该题自身**（标题或题面）里找到。前一条挡住"模型替学生编了
     *    一句他没说过的话"，后一条挡住"词是学生说的、但说的不是这道题"。
     * 3. **不许从被钉住的题上切走**（[pinnedQuestion] 非空时）：学生显式附加了题的那一轮，
     *    附加题就是本轮的题；模型指别的候选一律无效。这一轮的 confirmedQuestion 就是所附之题、
     *    答案围着它展开，允许切走只会让"落库的绑定/证据锚"与"学生看到的那道题"分叉。后续轮次
     *    不再有附加题，语义切换照旧合法。
     *
     * 校验通过返回原声明（内容逐字未改），由调用方作为本轮绑定落库。
     */
    fun resolve(
        candidates: List<RelatedProblemCandidate>,
        declaration: TutorRoundQuestionDeclaration?,
        studentMessage: String,
        /** 本轮被**钉住**的题：学生显式附加的那一道。见下面的切走规则。 */
        pinnedQuestion: RelatedProblemCandidate? = null,
    ): TutorRoundQuestionDeclaration? {
        if (declaration == null) return null
        val candidate = candidates.singleOrNull { candidate ->
            candidate.problemId == declaration.problemId &&
                candidate.problemRevisionId == declaration.problemRevisionId
        } ?: return null
        // 学生显式附加了题的一轮：附加题就是本轮的题，模型指**别的**候选一律无效（不许切走）。
        // 这一轮的 confirmedQuestion 就是所附之题、答案也围着它展开；允许声明切到菜单里另一道题，
        // 会让"落库的绑定/证据锚"与"学生看到的那道题"分叉（badge、账目、下一轮菜单各说各话）。
        // 后续轮次不再有附加题，语义切换照旧合法。
        if (pinnedQuestion != null &&
            (candidate.problemId != pinnedQuestion.problemId ||
                candidate.problemRevisionId != pinnedQuestion.problemRevisionId)
        ) {
            return null
        }
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
     * 这**一次调用**算不算"锚住了本轮的题"——写工具准入的唯一事实来源。
     *
     * 两条来源，按可靠性排序：
     * 1. **模型声明**（[declaration]）：本地两条校验全过才算。首选路径，因为"这一轮在说哪一道"
     *    是语义判断——模型给语义、本地给裁决。
     * 2. **请求侧已经知道的题锚**（[knownRoundQuestion]，`TutorRespondInput.knownRoundQuestion`）：
     *    学生本轮显式把这题带进来、或上一轮已校验的绑定延续到本轮。这种情况本地**已经**知道
     *    这一轮有题，缺的只是"模型有没有在调用里复述它"——不能因为没复述就判成无题轮。
     *
     * 为什么必须有第二条（F1）：原生 `tool_calls` 路由的标准形态 content=null，模型复述题锚的
     * 唯一落点是每次调用的 arguments；schema 的 `required` 只约束合规 provider，复述不是必然的。
     * 只认第一条，拒绝的理由就退化成"这一轮来自哪条解析路由"，而不是"这一轮有没有题"这个语义
     * 事实——比改造前那条 `input is TutorRespondInput` 还窄，是能力回退。
     *
     * 声明**在但核不过**时不回退：那是模型指了一道本轮菜单外的题（或锚词凭不到学生原话），
     * 属于"说错了"，不是"没说"，按无题轮处理。
     *
     * [knownRoundQuestion] 在本轮菜单内的成员资格由 `TutorRespondInput` 的构造契约保证
     * （已知锚不是本轮候选之一时构造直接失败），所以这里只判"有没有"。
     */
    fun callIsAnchoredToRoundQuestion(
        declaration: TutorRoundQuestionDeclaration?,
        candidates: List<RelatedProblemCandidate>,
        studentMessage: String,
        knownRoundQuestion: RelatedProblemCandidate?,
    ): Boolean = if (declaration != null) {
        resolve(candidates, declaration, studentMessage) != null
    } else {
        knownRoundQuestion != null
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
 * 本轮 MASTERY_UPDATE 允许使用的知识点代号白名单（工具环的逐次结构性拒之一）。
 *
 * 正常轮次 = **本会话已披露代号集**（D5：代号会话内稳定、只增不减），由调用方从会话级注册表
 * 取——不是本轮输入的 `knowledgeCodes` 字段：那只是"派发形状"，工具环中途通过 KNOWLEDGE_READ
 * 追加披露的节点也在白名单里。
 *
 * **学生显式附加了另一道题的轮次例外**：代号表属于会话题的节点空间，这一轮讲的是学生从错题库
 * 挑来的那道题，提示词里连代号表都没有。模型若凭上一轮的记忆给出会话题的代号，落库就是把掌握
 * 证据记在会话题头上（`com.tingyun.smartmistakebook.core.domain.MasteryWriteGate` 的 NEGATIVE
 * 方向不要求题锚，拦不住这种错记）。这种轮次返回空集：任何代号结构性拒（fail-closed）。
 *
 * 消灭的失败：`TutorModelTaskPolicy` 附加分支里"清空 knowledgeCodes ⇒ 附加题轮次结构上不可写
 * 掌握证据"这条恰好不成立的信念——清空的是输入字段，而白名单取自注册表，清空挡不住它。
 */
fun masteryUpdateCodeWhitelist(
    input: ModelTaskInput,
    sessionDisclosedCodes: Set<String>,
): Set<String> = if ((input as? TutorRespondInput)?.attachedQuestion != null) {
    emptySet()
} else {
    sessionDisclosedCodes
}

/**
 * 上一轮讲的是哪一道题——候选菜单的第二条来源（`docs/tutor-surface-unification.md` §5.4：可跨轮）。
 *
 * 取**最近一条已成功回复**的 RESPOND 任务，按可靠性回读这一轮的题：
 * 1. 模型声明且**通过了本地校验**的绑定（`output.boundQuestion`，解析层是唯一写入口）——
 *    再从它自己那一轮的菜单里把候选捞出来（候选里带着题面，所以不必再读一次库）；
 * 2. 模型没有复述题锚时，回读**学生本轮显式附加的题**（`input.attachedQuestion`）：请求契约
 *    保证它就是本轮的已知锚（known == attached）且在本轮菜单内，所以"这一轮讲哪道"本地已经
 *    知道，不需要模型的语义复述。少了这一条，附加题会从下一轮的菜单与已知锚里静默消失。
 *
 * 为什么必须回读"已校验的绑定"而不是回读模型声明：声明不是裁决。上一轮如果声明越界、锚词不
 * 对，那一轮就是无题轮——把它的声明当成"上一轮绑定的题"会把这个错误顺延到下一轮。同理，
 * 附加题的回退只认**请求侧**那份契约担保的身份，不认模型的任何自述。
 *
 * 没有已绑定轮次时返回 null（首轮就是无题轮）。
 */
fun previousBoundRoundQuestion(tasks: List<ModelTaskSnapshot>): RelatedProblemCandidate? =
    tasks.asSequence()
        .mapNotNull { task ->
            val input = task.request.input as? TutorRespondInput ?: return@mapNotNull null
            if (task.status != ModelTaskStatus.SUCCEEDED) return@mapNotNull null
            // output.boundQuestion 已经是**校验过**的绑定（解析层是唯一写入口）。
            val binding = (task.output as? TutorRespondOutput)?.boundQuestion
            val candidate = if (binding != null) {
                input.boundQuestionCandidates.singleOrNull { candidate ->
                    candidate.problemId == binding.problemId &&
                        candidate.problemRevisionId == binding.problemRevisionId
                } ?: return@mapNotNull null
            } else {
                input.attachedQuestion?.toCandidate() ?: return@mapNotNull null
            }
            task.createdAtEpochMillis to candidate
        }
        .maxByOrNull { (createdAtEpochMillis, _) -> createdAtEpochMillis }
        ?.second
