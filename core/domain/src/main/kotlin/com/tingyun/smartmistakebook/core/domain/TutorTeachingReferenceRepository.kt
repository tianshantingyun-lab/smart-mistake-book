package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference

/**
 * Read-only access to reviewed teaching material for knowledge already linked to the current
 * learner-supplied question. This boundary cannot return practice items or write learning memory.
 */
fun interface TutorTeachingReferenceRepository {
    suspend fun referencesFor(
        subject: String,
        knowledgeNodeIds: Set<String>,
        limit: Int,
    ): List<TutorTeachingReference>

    companion object {
        const val DEFAULT_LIMIT = TutorPlanInput.MAX_TEACHING_REFERENCES
    }
}
