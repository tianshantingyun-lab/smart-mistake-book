package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import com.tingyun.smartmistakebook.core.student.mistake.database.OrganizeStudentProblemCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.ReviewedStudentProblemOrganizationLocalContext
import com.tingyun.smartmistakebook.core.student.mistake.database.ReviewedStudentProblemOrganizationMapping

internal fun interface ReviewedStudentProblemOrganizationReviewPort {
    suspend fun mapAndReview(
        local: ReviewedStudentProblemOrganizationLocalContext,
        reviewedTask: ModelTaskSnapshot,
        verifiedKnowledgeReferences: List<VerifiedKnowledgeReferenceProof>,
    ): OrganizeStudentProblemCommand
}

/**
 * Core:data mapping boundary for one locally reviewed v3 result.
 *
 * Its input deliberately contains no solution, classification, error, step, or facet payload.
 * Those fields are projected from the persisted reviewed task by the student-store owner policy.
 */
internal class ReviewedStudentProblemOrganizationMapper(
    private val reviewPort: ReviewedStudentProblemOrganizationReviewPort,
) {
    suspend fun mapAndReview(
        local: ReviewedStudentProblemOrganizationLocalContext,
        reviewedTask: ModelTaskSnapshot,
        verifiedKnowledgeReferences: List<VerifiedKnowledgeReferenceProof>,
    ): OrganizeStudentProblemCommand =
        reviewPort.mapAndReview(
            local = local,
            reviewedTask = reviewedTask,
            verifiedKnowledgeReferences = verifiedKnowledgeReferences,
        )

    companion object {
        /**
         * Pure projection used by deterministic mapping tests. Production commits must call
         * [mapAndReview], which maps and signs without exposing an intervening mutable payload.
         */
        internal fun mapUnsigned(
            local: ReviewedStudentProblemOrganizationLocalContext,
            reviewedTask: ModelTaskSnapshot,
            verifiedKnowledgeReferences: List<VerifiedKnowledgeReferenceProof>,
        ): OrganizeStudentProblemCommand =
            ReviewedStudentProblemOrganizationMapping.mapUnsigned(
                local,
                reviewedTask,
                verifiedKnowledgeReferences,
            )
    }
}
