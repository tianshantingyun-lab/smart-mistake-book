package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorTeachingReference

/**
 * Read-only access to reviewed teaching material for knowledge already linked to the current
 * learner-supplied question. This boundary cannot return practice items or write learning memory.
 *
 * **不接收条数上限。** 返回多少条由字符预算决定
 * （`TutorPlanInput.MAX_TEACHING_REFERENCE_MARKDOWN_CHARS`），不设条数门——条数上限曾让
 * 同一知识点第 5 条起的材料永远送不到模型。
 */
fun interface TutorTeachingReferenceRepository {
    suspend fun referencesFor(
        subject: String,
        knowledgeNodeIds: Set<String>,
    ): List<TutorTeachingReference>
}
