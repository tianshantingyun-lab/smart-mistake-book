package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.IncrementalLearningEvent
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningLedgerEvent
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.PresentationProjectionState
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import com.tingyun.smartmistakebook.core.model.TutorMoveType

/**
 * 知识库、归因来源、讲解材料与 grounding 的记录（跨 [StudyDatabasePort]）。
 *
 * 从 `StudyDatabaseRecords.kt` 拆出（审计 R-01）。
 */
data class KnowledgeNodeSeedRecord(
    val knowledgeNodeId: String,
    val stableCode: String,
    val subject: String,
    val displayName: String,
    val parentKnowledgeNodeId: String?,
    val taxonomyVersion: String,
    val createdAtEpochMillis: Long,
    val canonicalName: String = displayName,
    val nodeKind: String = "TOPIC",
    val granularity: String = "TOPIC",
    val aliases: Set<String> = emptySet(),
    val boundaryMarkdown: String? = null,
    val verificationStatus: String = "MODEL_CANDIDATE",
)
data class KnowledgeBindingSeedRecord(
    val bindingId: String,
    val practiceUnitId: String,
    val knowledgeNodeId: String,
    val basisRevisionId: String,
    val strength: Double,
    val sourceType: String,
    val taxonomyVersion: String,
    val acceptedAtEpochMillis: Long,
)
data class KnowledgeSourceSeedRecord(
    val sourceId: String,
    val subject: String,
    val sourceType: String,
    val title: String,
    val publisher: String?,
    val edition: String?,
    val sourceUri: String?,
    val licenseStatus: String,
    val contentFingerprint: String,
    val importedAtEpochMillis: Long,
    val contentUsePolicy: String = "REVIEWED_SYNTHESIS_ONLY",
    val licenseExpression: String? = null,
    val licenseUri: String? = null,
    val attributionText: String? = null,
)
data class KnowledgeNodeSourceBindingSeedRecord(
    val knowledgeNodeId: String,
    val sourceId: String,
    val sourceLocator: String,
    val derivationNote: String,
    val reviewedAtEpochMillis: Long?,
)
data class KnowledgeNodeRelationRecord(
    val relationId: String,
    val subject: String,
    val prerequisiteKnowledgeNodeId: String,
    val dependentKnowledgeNodeId: String,
    val relationType: String,
    val sourceId: String,
    val sourceLocator: String,
    val reviewedAtEpochMillis: Long,
)
data class KnowledgeTeachingMaterialRecord(
    val materialId: String,
    val stableCode: String,
    val subject: String,
    val materialType: String,
    val title: String,
    val summaryMarkdown: String,
    val applicabilityMarkdown: String,
    val contentMarkdown: String,
    val boundaryMarkdown: String,
    val derivationKind: String,
    val sourceId: String,
    val sourceLocator: String,
    val contentFingerprint: String,
    val reviewedAtEpochMillis: Long,
)
data class KnowledgeTeachingMaterialNodeBindingRecord(
    val materialId: String,
    val knowledgeNodeId: String,
    val role: String,
)
data class KnowledgeGroundingRequestRecord(
    val groundingRequestId: String,
    val groundingKey: String,
    val organizationRequestId: String,
    val organizationRequestFingerprint: String,
    val requestOrdinal: Int,
    val problemId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val subject: String,
    val query: String,
    val expectedParentKnowledgeDisplayName: String,
    val reasonMarkdown: String,
    val status: String = StudyDbValue.KnowledgeGroundingStatus.PENDING,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
data class KnowledgeGroundingSummaryRecord(
    val groundingKey: String,
    val subject: String,
    val expectedParentKnowledgeDisplayName: String,
    val query: String,
    val relatedQuestionCount: Int,
    val firstObservedAtEpochMillis: Long,
    val lastObservedAtEpochMillis: Long,
)
data class KnowledgeResearchReviewSourceRecord(
    val sourceOrdinal: Int,
    val canonicalSourceUri: String,
    val title: String,
    val publisher: String?,
    val sourceType: String,
    val licenseStatus: String,
    val searchRank: Int,
    val contentType: String,
    val contentLengthBytes: Long,
    val contentFingerprint: String,
    val verifiedAtEpochMillis: Long,
)
data class KnowledgeResearchReviewBundleRecord(
    val bundleId: String,
    val groundingKey: String,
    val subject: String,
    val query: String,
    val expectedParentKnowledgeDisplayName: String,
    val relatedQuestionCount: Int,
    val workflowVersion: String,
    val status: String = StudyDbValue.KnowledgeResearchReviewStatus.PENDING_REVIEW,
    val reviewerReference: String? = null,
    val decisionNote: String? = null,
    val reviewedAtEpochMillis: Long? = null,
    val appliedPackFingerprint: String? = null,
    val appliedAtEpochMillis: Long? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val sources: List<KnowledgeResearchReviewSourceRecord>,
)
data class KnowledgeGroundingResolutionRecord(
    val resolutionId: String,
    val groundingKey: String,
    val subject: String,
    val knowledgeNodeId: String,
    val taxonomyVersion: String,
    val resolvedOccurrenceCount: Int,
    val linkedPracticeUnitCount: Int,
    val resolvedAtEpochMillis: Long,
)
data class KnowledgeMasteryStateRecord(
    val knowledgeNodeId: String,
    val masteryProbability: Double,
    val independentCorrectCount: Int,
    val assistedCorrectCount: Int,
    val incorrectCount: Int,
    val evidenceWeightTotal: Double,
    val lastEvidenceAtEpochMillis: Long?,
    val projectionCheckpoint: Long,
    val projectorVersion: String,
    val updatedAtEpochMillis: Long,
)
