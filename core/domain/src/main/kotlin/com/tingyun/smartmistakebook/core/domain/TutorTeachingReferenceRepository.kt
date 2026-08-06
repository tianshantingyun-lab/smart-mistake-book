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

    /**
     * Version-bound lookup used by production tutoring. Implementations backed by a catalog must
     * re-resolve these witnesses against the active manifest before returning any material.
     */
    suspend fun referencesForConfirmedNodes(
        subject: String,
        directKnowledgeNodes: Set<ConfirmedKnowledgeNodeBinding>,
        limit: Int,
    ): List<TutorTeachingReference> {
        require(directKnowledgeNodes.size <= TutorTeachingReference.MAX_KNOWLEDGE_NODES)
        require(directKnowledgeNodes.all { it.ref.subject.name == subject }) {
            "Teaching-reference bindings must stay within the current subject"
        }
        return referencesFor(
            subject = subject,
            knowledgeNodeIds = directKnowledgeNodes.mapTo(linkedSetOf()) {
                it.ref.knowledgeNodeId
            },
            limit = limit,
        )
    }

    companion object {
        const val DEFAULT_LIMIT = TutorPlanInput.MAX_TEACHING_REFERENCES
    }
}
