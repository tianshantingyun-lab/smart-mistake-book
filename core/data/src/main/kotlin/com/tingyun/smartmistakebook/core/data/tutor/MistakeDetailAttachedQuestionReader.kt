package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.TutorAttachedQuestionReader
import com.tingyun.smartmistakebook.core.model.AttachedRoundQuestion

/**
 * 加号菜单选中一道错题后的读取：走与候选检索同一条 `MistakeDetailRepository.readExact`
 * 路径（题面指纹校验、修订一致性都已经在里面），读不出 Ready 题面就如实返回 null。
 */
internal class MistakeDetailAttachedQuestionReader(
    private val details: MistakeDetailRepository,
) : TutorAttachedQuestionReader {
    override suspend fun read(entry: StudyCatalogEntry): AttachedRoundQuestion? {
        val key = MistakeRevisionKey(
            entryId = entry.entryId,
            problemId = entry.problemId,
            problemRevisionId = entry.problemRevisionId,
        )
        val state = details.readExact(key)
        val ready = state as? MistakeDetailState.Ready ?: return null
        val document = ready.questionDocument.document
        if (document.blocks.isEmpty()) return null
        return AttachedRoundQuestion(
            problemId = key.problemId,
            problemRevisionId = key.problemRevisionId,
            revisionNumber = ready.detail.identity.revisionNumber,
            subject = entry.subject.toTutorSubjectKind(),
            title = ready.detail.identity.title,
            questionDocument = document,
        )
    }
}

/**
 * 生产装配口，与 [TutorRoundQuestionRetrieverFactory] 同一口径：实现保持 internal，
 * 对外只暴露工厂，app 层不必知道实现类型。
 */
object TutorAttachedQuestionReaderFactory {
    fun create(details: MistakeDetailRepository): TutorAttachedQuestionReader =
        MistakeDetailAttachedQuestionReader(details)
}
