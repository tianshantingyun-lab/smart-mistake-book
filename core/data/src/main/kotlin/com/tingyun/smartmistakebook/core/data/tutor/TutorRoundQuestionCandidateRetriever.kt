package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.database.CjkTextTokenizer
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.TutorRoundQuestionRetriever
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.util.Locale

/**
 * 本轮候选菜单的第三条来源：**本地文本检索**。
 *
 * 前两条（本轮学生显式添加的题、上一轮绑定的题）是身份，不需要检索。这一条把学生这一轮说的话
 * 变成一组候选：分词 → 在错题目录里打分 → 取前 N 条 → 只为这 N 条补齐已确认题面。
 *
 * 为什么检索必须本地做：模型的职责只是"从菜单里指哪一道"，而"哪几道有可能"是数值问题，按
 * 仓库既有的分工（模型给语义、本地给数值）留在本地。把整本错题目录交给模型去挑，既扩大披露
 * 面，又让"候选必须在菜单内"这条校验失去意义。
 *
 * 读盘次数与菜单大小同阶（只读排进前 N 的题面），不随目录增长。
 */
internal class CatalogTutorRoundQuestionRetriever(
    private val details: MistakeDetailRepository,
) : TutorRoundQuestionRetriever {
    override suspend fun retrieve(
        catalog: List<StudyCatalogEntry>,
        studentMessage: String,
        excluded: List<RelatedProblemCandidate>,
        limit: Int,
    ): List<RelatedProblemCandidate> {
        require(limit >= 0) { "Candidate retrieval limit must not be negative" }
        if (limit == 0) return emptyList()
        val terms = tutorRoundSearchTerms(studentMessage)
        // 没有可检索的词（纯图消息、或全是标点）时返回空表，而不是"目录里分数并列的前 N 条"：
        // 后者会让模型在没有任何文本线索时也能"指"一道题，把本地校验第一条变成走过场。
        if (terms.isEmpty()) return emptyList()
        val excludedRevisions = excluded.mapTo(hashSetOf()) { candidate ->
            candidate.problemId to candidate.problemRevisionId
        }
        val ranked = catalog.asSequence()
            .filter { entry -> (entry.problemId to entry.problemRevisionId) !in excludedRevisions }
            .map { entry -> entry to tutorRoundCandidateScore(terms, entry) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<StudyCatalogEntry, Int>> { it.second }
                    .thenBy { it.first.nextReviewAtEpochMillis ?: Long.MAX_VALUE }
                    .thenBy { it.first.entryId },
            )
            .map { (entry, _) -> entry }
            .take(limit)
            .toList()
        val keys = ranked.map { entry ->
            MistakeRevisionKey(
                entryId = entry.entryId,
                problemId = entry.problemId,
                problemRevisionId = entry.problemRevisionId,
            )
        }
        val readyByProblem = details.readExact(keys)
            .filterIsInstance<MistakeDetailState.Ready>()
            .associateBy { state -> state.detail.identity.problemId }
        return ranked.mapNotNull { entry ->
            val document = readyByProblem[entry.problemId]?.questionDocument?.document
                ?: return@mapNotNull null
            if (document.blocks.isEmpty()) return@mapNotNull null
            RelatedProblemCandidate(
                problemId = entry.problemId,
                problemRevisionId = entry.problemRevisionId,
                subject = entry.subject.toTutorSubjectKind(),
                title = entry.title,
                questionDocument = document,
            )
        }
    }
}

/**
 * 文本检索打分。刻意与两处既有打分同构，而不是另起一套：
 *
 * - 词命中按字段分档（标题 60 / 知识点 50 / 章节 40 / 科目 30），与
 *   `TutorLocalReadProjection.StudyCatalogEntry.matchScore` 同序——学生打字时最可能引用的是
 *   标题里的名词；
 * - 分档间距取 ×10 量级，保留 `RoomMistakeOrganizationRepository.relationCandidateScore` 的
 *   "知识层压过文本层"相对关系（那边是知识点 ×100 + 标题字对 ≤20），免得标题里一个常用词
 *   盖过知识点命中。
 *
 * 两边都只做**排序**，不做判定：分数再高也只是进菜单，绑定仍要模型声明 + 本地两条校验。
 */
internal fun tutorRoundCandidateScore(
    terms: List<String>,
    candidate: StudyCatalogEntry,
): Int {
    if (terms.isEmpty()) return 0
    val titleText = candidate.title.lowercase(Locale.ROOT)
    val knowledgeText = candidate.knowledgeLabels.joinToString(" ").lowercase(Locale.ROOT)
    val chapterText = candidate.chapterLabels.joinToString(" ").lowercase(Locale.ROOT)
    val subjectText = candidate.subject.lowercase(Locale.ROOT)
    return terms.sumOf { term ->
        when {
            term in titleText -> 60
            term in knowledgeText -> 50
            term in chapterText -> 40
            term in subjectText -> 30
            else -> 0
        }
    }
}

/**
 * 检索词：CJK 按字切（`CjkTextTokenizer.tokens` 与库内全文索引同一套变换，所以"学生打出来的
 * 词"与"库里存着的词"两侧一致），西文与数字整词保留。
 */
internal fun tutorRoundSearchTerms(studentMessage: String): List<String> =
    CjkTextTokenizer.tokens(studentMessage)
        .map { token -> token.lowercase(Locale.ROOT) }
        .filter { token -> token.isNotBlank() }
        .distinct()
        .take(MAX_SEARCH_TERMS)

internal fun String.toTutorSubjectKind(): SubjectKind =
    SubjectKind.entries.firstOrNull { kind -> kind.name == this } ?: SubjectKind.GENERAL

private const val MAX_SEARCH_TERMS = 12

/**
 * 生产装配口。`CatalogTutorRoundQuestionRetriever` 保持 internal（它依赖本模块的文本分词），
 * 对外只暴露这条工厂，让 app 层不必知道实现类型。
 */
object TutorRoundQuestionRetrieverFactory {
    fun create(details: MistakeDetailRepository): TutorRoundQuestionRetriever =
        CatalogTutorRoundQuestionRetriever(details)
}
