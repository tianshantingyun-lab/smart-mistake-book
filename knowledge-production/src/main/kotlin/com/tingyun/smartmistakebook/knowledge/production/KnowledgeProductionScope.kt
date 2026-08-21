package com.tingyun.smartmistakebook.knowledge.production

import kotlinx.serialization.Serializable

/**
 * Knowledge production scope configuration. Defines which subjects and
 * topics are in scope for production, and the review requirements.
 */
@Serializable
data class KnowledgeProductionScope(
    val scopeId: String,
    val name: String,
    val description: String,
    val educationLevel: EducationLevel,
    val subjects: List<SubjectScope>,
    val createdAtEpochMillis: Long,
    val status: ScopeStatus,
) {
    init {
        require(scopeId.isNotBlank()) { "Scope id must not be blank" }
        require(subjects.isNotEmpty()) { "At least one subject must be in scope" }
    }
}

/**
 * Education levels supported.
 */
@Serializable
enum class EducationLevel {
    PRIMARY_SCHOOL,
    JUNIOR_HIGH,
    SENIOR_HIGH,
    UNIVERSITY,
}

/**
 * Scope for a single subject within a production scope.
 */
@Serializable
data class SubjectScope(
    val subjectId: String,
    val subjectName: String,
    val modules: List<ModuleScope>,
    val requiredTopicCount: Int,
    val requiredKnowledgePointCount: Int,
    val coverageTarget: Double, // 0.0 to 1.0
) {
    init {
        require(subjectId.isNotBlank()) { "Subject id must not be blank" }
        require(coverageTarget in 0.0..1.0) { "Coverage target must be between 0 and 1" }
    }
}

/**
 * Scope for a module within a subject.
 */
@Serializable
data class ModuleScope(
    val moduleId: String,
    val moduleName: String,
    val topicIds: List<String>,
    val requiredKnowledgePointCount: Int,
    val priority: ModulePriority,
)

/**
 * Module priority levels.
 */
@Serializable
enum class ModulePriority {
    /** Must be included in the initial release. */
    REQUIRED,
    /** Should be included if possible. */
    PREFERRED,
    /** Nice to have, can be deferred. */
    OPTIONAL,
}

/**
 * Status of a production scope.
 */
@Serializable
enum class ScopeStatus {
    /** Scope is being defined. */
    DRAFT,
    /** Scope has been approved and is being implemented. */
    ACTIVE,
    /** All required content has been produced and reviewed. */
    COMPLETE,
    /** Scope has been superseded by a new scope. */
    SUPERSEDED,
}

/**
 * Curriculum coverage matrix tracking which topics have been produced
 * and reviewed for a given scope.
 */
@Serializable
data class CurriculumCoverageMatrix(
    val scopeId: String,
    val generatedAtEpochMillis: Long,
    val entries: List<CoverageMatrixEntry>,
    val summary: CoverageSummary,
)

/**
 * A single entry in the coverage matrix.
 */
@Serializable
data class CoverageMatrixEntry(
    val topicId: String,
    val topicName: String,
    val subjectId: String,
    val moduleId: String,
    val requiredKnowledgePointCount: Int,
    val producedKnowledgePointCount: Int,
    val reviewedKnowledgePointCount: Int,
    val approvedKnowledgePointCount: Int,
    val coverageStatus: CoverageStatus,
    val dualReviewStatus: DualReviewStatus,
) {
    val coveragePercent: Double
        get() = if (requiredKnowledgePointCount > 0) {
            producedKnowledgePointCount.toDouble() / requiredKnowledgePointCount
        } else {
            0.0
        }
}

/**
 * Coverage status for a topic.
 */
@Serializable
enum class CoverageStatus {
    /** No content produced yet. */
    NOT_STARTED,
    /** Content production in progress. */
    IN_PROGRESS,
    /** All required content produced but not all reviewed. */
    PRODUCED,
    /** All content produced and reviewed. */
    REVIEWED,
    /** All content approved and ready for release. */
    APPROVED,
}

/**
 * Dual review status for a topic.
 */
@Serializable
enum class DualReviewStatus {
    /** No review started. */
    NOT_REVIEWED,
    /** First reviewer has completed review. */
    FIRST_REVIEW_COMPLETE,
    /** Second reviewer has completed review. */
    SECOND_REVIEW_COMPLETE,
    /** Both reviewers have approved. */
    DUAL_APPROVED,
    /** Reviewers disagree, needs escalation. */
    DISAGREEMENT,
}

/**
 * Summary of the coverage matrix.
 */
@Serializable
data class CoverageSummary(
    val totalTopics: Int,
    val topicsNotStarted: Int,
    val topicsInProgress: Int,
    val topicsProduced: Int,
    val topicsReviewed: Int,
    val topicsApproved: Int,
    val overallCoveragePercent: Double,
    val dualReviewCompletePercent: Double,
    val unreviewedContentCount: Int,
) {
    /**
     * Check if the scope meets release criteria.
     * No unreviewed content may enter the formal package.
     */
    fun meetsReleaseCriteria(): Boolean = unreviewedContentCount == 0 &&
        overallCoveragePercent >= 0.9 // At least 90% coverage
}

/**
 * Dual review record for a knowledge point.
 */
@Serializable
data class DualReviewRecord(
    val reviewId: String,
    val knowledgePointId: String,
    val reviewer1Id: String,
    val reviewer1Decision: ReviewDecision,
    val reviewer1Comments: String,
    val reviewer1CompletedAtEpochMillis: Long,
    val reviewer2Id: String?,
    val reviewer2Decision: ReviewDecision?,
    val reviewer2Comments: String?,
    val reviewer2CompletedAtEpochMillis: Long?,
    val finalDecision: ReviewDecision?,
    val escalationRequired: Boolean,
) {
    val isComplete: Boolean
        get() = reviewer2Decision != null && finalDecision != null
}

/**
 * Review decision types.
 */
@Serializable
enum class ReviewDecision {
    /** Content is approved for inclusion. */
    APPROVED,
    /** Content needs revision before approval. */
    NEEDS_REVISION,
    /** Content is rejected and should not be included. */
    REJECTED,
}

/**
 * Audit trail for knowledge production decisions.
 */
@Serializable
data class ProductionAuditEntry(
    val entryId: String,
    val knowledgePointId: String,
    val action: ProductionAction,
    val performedBy: String,
    val performedAtEpochMillis: Long,
    val details: String,
    val snapshotHash: String,
)

/**
 * Production actions that can be audited.
 */
@Serializable
enum class ProductionAction {
    CONTENT_PRODUCED,
    FIRST_REVIEW_COMPLETED,
    SECOND_REVIEW_COMPLETED,
    DUAL_APPROVAL_GRANTED,
    REVISION_REQUESTED,
    CONTENT_REJECTED,
    CONTENT_APPROVED_FOR_RELEASE,
    CONTENT_PROMOTED_TO_FORMAL,
    CONTENT_DEMOTED_FROM_FORMAL,
}
