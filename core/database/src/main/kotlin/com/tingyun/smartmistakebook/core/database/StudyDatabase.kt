package com.tingyun.smartmistakebook.core.database

import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import com.tingyun.smartmistakebook.core.database.dao.AttemptTransactionDao
import com.tingyun.smartmistakebook.core.database.dao.BatchCaptureDraftImportDao
import com.tingyun.smartmistakebook.core.database.dao.BatchImportDao
import com.tingyun.smartmistakebook.core.database.dao.CaptureDraftMergeSessionReceiptDao
import com.tingyun.smartmistakebook.core.database.dao.CaptureStudentSaveHandoffDao
import com.tingyun.smartmistakebook.core.database.dao.CurrentTutorInteractionSessionDao
import com.tingyun.smartmistakebook.core.database.dao.FixtureSeedDao
import com.tingyun.smartmistakebook.core.database.dao.ImmutableLearningFactDao
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeGroundingDao
import com.tingyun.smartmistakebook.core.database.dao.LegacyKnowledgeCatalogDao
import com.tingyun.smartmistakebook.core.database.dao.LegacyBusinessWriteBarrierDao
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeNodeRelationDao
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeResearchReviewDao
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeTeachingMaterialDao
import com.tingyun.smartmistakebook.core.database.dao.LegacyAuthorityMigrationSourceDao
import com.tingyun.smartmistakebook.core.database.dao.LegacyAuthorityCutoverJournalDao
import com.tingyun.smartmistakebook.core.database.dao.LearningDao
import com.tingyun.smartmistakebook.core.database.dao.LearningObservationDao
import com.tingyun.smartmistakebook.core.database.dao.ModelTaskTransactionDao
import com.tingyun.smartmistakebook.core.database.dao.MistakeDetailDao
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureDao
import com.tingyun.smartmistakebook.core.database.dao.ProblemDao
import com.tingyun.smartmistakebook.core.database.dao.ProblemOrganizationDao
import com.tingyun.smartmistakebook.core.database.dao.ProblemOrganizationWorkDao
import com.tingyun.smartmistakebook.core.database.dao.ProblemDraftEditWorkspaceDao
import com.tingyun.smartmistakebook.core.database.dao.ProblemDraftTransactionDao
import com.tingyun.smartmistakebook.core.database.dao.ProjectionTransactionDao
import com.tingyun.smartmistakebook.core.database.dao.ReviewDao
import com.tingyun.smartmistakebook.core.database.dao.ReviewPlanTransactionDao
import com.tingyun.smartmistakebook.core.database.dao.TutorInteractionDao
import com.tingyun.smartmistakebook.core.database.dao.TutorExposureDao
import com.tingyun.smartmistakebook.core.database.dao.TutorLearningEvidenceSessionDao
import com.tingyun.smartmistakebook.core.database.dao.TutorLearningMemoryDao
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEvidenceAttributionEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEvidenceSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentItemSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.ActiveReviewPlanSlotEntity
import com.tingyun.smartmistakebook.core.database.entity.AnswerRevealOutcomeEntity
import com.tingyun.smartmistakebook.core.database.entity.AppliedAnswerRevealRecordEntity
import com.tingyun.smartmistakebook.core.database.entity.AppliedCorrectionRecordEntity
import com.tingyun.smartmistakebook.core.database.entity.AppliedAttemptRecordEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentAnswerRevealEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentPresentationEntity
import com.tingyun.smartmistakebook.core.database.entity.BatchCaptureContentBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.BatchImportBoundaryResolutionReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.BatchImportJobEntity
import com.tingyun.smartmistakebook.core.database.entity.BatchImportPageEntity
import com.tingyun.smartmistakebook.core.database.entity.CanonicalSourceAssetEntity
import com.tingyun.smartmistakebook.core.database.entity.CaptureDraftMergeSessionReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.CaptureDraftBatchImportReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.CaptureStudentSaveHandoffEntity
import com.tingyun.smartmistakebook.core.database.entity.AttemptCorrectionEntity
import com.tingyun.smartmistakebook.core.database.entity.AttemptEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AttemptSubmissionEntity
import com.tingyun.smartmistakebook.core.database.entity.ErrorBookEntryEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeMasteryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeSourceBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSourceEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingResolutionEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeResearchReviewBundleEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeResearchReviewSourceEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSearchFeatureEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialNodeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.IndependentCorrectObservationEntity
import com.tingyun.smartmistakebook.core.database.entity.LearnerKnowledgeMasteryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.LearnerProblemMemoryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.LearnerProjectionSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.LegacyAuthorityCutoverStageReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.LegacyBusinessWriteBarrierEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningSequenceEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningEventIdentityEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationSourceAuthorityEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationSourceFactProofEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationEventAdmissionEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationCandidateEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationCandidateAttributionEntity
import com.tingyun.smartmistakebook.core.database.entity.AttributedLearningObservationEventEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationEventAttributionEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningEvidenceReviewCaseEntity
import com.tingyun.smartmistakebook.core.database.entity.AppliedLearningObservationRecordEntity
import com.tingyun.smartmistakebook.core.database.entity.ModelTaskEntity
import com.tingyun.smartmistakebook.core.database.entity.ModelTaskEventEntity
import com.tingyun.smartmistakebook.core.database.entity.ModelTaskOperationEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftCommitReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftEditWorkspaceEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftRevisionEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftSourceAssetEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemMemoryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemClassificationBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemOrganizationReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemOrganizationWorkEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRevisionEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRevisionSourceAssetEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemSolutionStepEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemStepKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemErrorAttributionCandidateEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemErrorCandidateEvidenceEntity
import com.tingyun.smartmistakebook.core.database.entity.ProjectionConsumptionEntity
import com.tingyun.smartmistakebook.core.database.entity.ProjectionOutboxEntity
import com.tingyun.smartmistakebook.core.database.entity.PresentationProjectionStateEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewPlanEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueKnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueReasonEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueItemEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionAdvanceReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionRevisionEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorSessionEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorTurnResponseEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorVisualTargetEvidenceEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorEvidenceCancellationEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorSessionProblemAnchorEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorConversationEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentInteractionEventEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentInteractionHeadEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentInteractionScopeEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentHostWorkEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentPolicyEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorFreeResponseOutboxEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorTurnReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorEvidenceRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorLearningEvidenceFinalizationReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningProblemAnchorEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationSourceFactEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorAnswerExposureEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorAnswerExposureOutcomeEntity
import com.tingyun.smartmistakebook.core.database.entity.AppliedTutorAnswerExposureRecordEntity

internal const val STUDY_DATABASE_VERSION = 49

@Database(
    entities = [
        ProblemEntity::class,
        ProblemRevisionEntity::class,
        PracticeUnitEntity::class,
        ErrorBookEntryEntity::class,
        KnowledgeNodeEntity::class,
        KnowledgeSourceEntity::class,
        KnowledgeNodeSourceBindingEntity::class,
        KnowledgeGroundingRequestEntity::class,
        KnowledgeGroundingResolutionEntity::class,
        KnowledgeNodeRelationEntity::class,
        KnowledgeSearchFeatureEntity::class,
        KnowledgeResearchReviewBundleEntity::class,
        KnowledgeResearchReviewSourceEntity::class,
        KnowledgeTeachingMaterialEntity::class,
        KnowledgeTeachingMaterialNodeBindingEntity::class,
        PracticeUnitKnowledgeBindingEntity::class,
        ProblemRelationEntity::class,
        ProblemClassificationBindingEntity::class,
        ProblemOrganizationReceiptEntity::class,
        ProblemSolutionStepEntity::class,
        ProblemStepKnowledgeBindingEntity::class,
        ProblemErrorAttributionCandidateEntity::class,
        ProblemErrorCandidateEvidenceEntity::class,
        ProblemOrganizationWorkEntity::class,
        CanonicalSourceAssetEntity::class,
        ProblemDraftEntity::class,
        ProblemDraftSourceAssetEntity::class,
        ProblemDraftRevisionEntity::class,
        ProblemDraftEditWorkspaceEntity::class,
        ProblemRevisionSourceAssetEntity::class,
        ProblemDraftCommitReceiptEntity::class,
        AssessmentItemSnapshotEntity::class,
        AssessmentEventEntity::class,
        AssessmentEvidenceSnapshotEntity::class,
        AssessmentEvidenceAttributionEntity::class,
        AssessmentPresentationEntity::class,
        AssessmentAnswerRevealEventEntity::class,
        AnswerRevealOutcomeEntity::class,
        AttemptSubmissionEntity::class,
        AttemptEventEntity::class,
        AttemptCorrectionEntity::class,
        LearningSequenceEntity::class,
        LearningEventIdentityEntity::class,
        ProjectionOutboxEntity::class,
        ProjectionConsumptionEntity::class,
        ProblemMemoryStateEntity::class,
        KnowledgeMasteryStateEntity::class,
        LearnerProjectionSnapshotEntity::class,
        LearnerProblemMemoryStateEntity::class,
        LearnerKnowledgeMasteryStateEntity::class,
        IndependentCorrectObservationEntity::class,
        AppliedAttemptRecordEntity::class,
        AppliedCorrectionRecordEntity::class,
        AppliedAnswerRevealRecordEntity::class,
        PresentationProjectionStateEntity::class,
        ReviewPlanEntity::class,
        ActiveReviewPlanSlotEntity::class,
        ReviewQueueItemEntity::class,
        ReviewQueueKnowledgeNodeEntity::class,
        ReviewQueueReasonEntity::class,
        ReviewSessionEntity::class,
        ReviewSessionRevisionEntity::class,
        ReviewSessionAdvanceReceiptEntity::class,
        ModelTaskOperationEntity::class,
        ModelTaskEntity::class,
        ModelTaskEventEntity::class,
        TutorSessionEntity::class,
        TutorTurnResponseEntity::class,
        TutorVisualTargetEvidenceEntity::class,
        TutorEvidenceCancellationEntity::class,
        TutorSessionProblemAnchorEntity::class,
        TutorAnswerExposureEntity::class,
        TutorAnswerExposureOutcomeEntity::class,
        AppliedTutorAnswerExposureRecordEntity::class,
        LearningObservationSourceAuthorityEntity::class,
        LearningObservationSourceFactProofEntity::class,
        LearningObservationEventAdmissionEntity::class,
        LearningObservationCandidateEntity::class,
        LearningObservationCandidateAttributionEntity::class,
        AttributedLearningObservationEventEntity::class,
        LearningObservationEventAttributionEntity::class,
        LearningEvidenceReviewCaseEntity::class,
        AppliedLearningObservationRecordEntity::class,
        BatchImportJobEntity::class,
        BatchImportPageEntity::class,
        TutorConversationEntity::class,
        TutorTurnReceiptEntity::class,
        TutorEvidenceRequestEntity::class,
        TutorLearningEvidenceFinalizationReceiptEntity::class,
        TutorCurrentInteractionScopeEntity::class,
        TutorCurrentInteractionHeadEntity::class,
        TutorCurrentInteractionEventEntity::class,
        TutorCurrentPolicyEntity::class,
        TutorCurrentHostWorkEntity::class,
        TutorFreeResponseOutboxEntity::class,
        LearningProblemAnchorEntity::class,
        LearningObservationSourceFactEntity::class,
        LegacyAuthorityCutoverStageReceiptEntity::class,
        CaptureStudentSaveHandoffEntity::class,
        CaptureDraftMergeSessionReceiptEntity::class,
        BatchImportBoundaryResolutionReceiptEntity::class,
        BatchCaptureContentBindingEntity::class,
        CaptureDraftBatchImportReceiptEntity::class,
        LegacyBusinessWriteBarrierEntity::class,
    ],
    version = STUDY_DATABASE_VERSION,
    exportSchema = true,
)
internal abstract class StudyDatabase : RoomDatabase() {
    abstract fun problemDao(): ProblemDao

    abstract fun problemOrganizationDao(): ProblemOrganizationDao

    abstract fun legacyKnowledgeCatalogDao(): LegacyKnowledgeCatalogDao

    abstract fun problemOrganizationWorkDao(): ProblemOrganizationWorkDao

    abstract fun knowledgeGroundingDao(): KnowledgeGroundingDao

    abstract fun knowledgeNodeRelationDao(): KnowledgeNodeRelationDao

    abstract fun knowledgeResearchReviewDao(): KnowledgeResearchReviewDao

    abstract fun knowledgeTeachingMaterialDao(): KnowledgeTeachingMaterialDao

    abstract fun mistakeDetailDao(): MistakeDetailDao

    abstract fun learningDao(): LearningDao

    abstract fun learningObservationDao(): LearningObservationDao

    abstract fun immutableLearningFactDao(): ImmutableLearningFactDao

    abstract fun reviewDao(): ReviewDao

    abstract fun attemptTransactionDao(): AttemptTransactionDao

    abstract fun projectionTransactionDao(): ProjectionTransactionDao

    abstract fun fixtureSeedDao(): FixtureSeedDao

    abstract fun reviewPlanTransactionDao(): ReviewPlanTransactionDao

    abstract fun problemDraftTransactionDao(): ProblemDraftTransactionDao

    abstract fun problemDraftEditWorkspaceDao(): ProblemDraftEditWorkspaceDao

    abstract fun pendingCaptureDao(): PendingCaptureDao

    abstract fun modelTaskTransactionDao(): ModelTaskTransactionDao

    abstract fun tutorInteractionDao(): TutorInteractionDao

    abstract fun tutorExposureDao(): TutorExposureDao

    abstract fun batchImportDao(): BatchImportDao

    abstract fun batchCaptureDraftImportDao(): BatchCaptureDraftImportDao

    abstract fun tutorLearningMemoryDao(): TutorLearningMemoryDao

    abstract fun tutorLearningEvidenceSessionDao(): TutorLearningEvidenceSessionDao

    abstract fun currentTutorInteractionSessionDao(): CurrentTutorInteractionSessionDao

    abstract fun legacyAuthorityMigrationSourceDao(): LegacyAuthorityMigrationSourceDao

    abstract fun legacyAuthorityCutoverJournalDao(): LegacyAuthorityCutoverJournalDao

    abstract fun legacyBusinessWriteBarrierDao(): LegacyBusinessWriteBarrierDao

    abstract fun captureStudentSaveHandoffDao(): CaptureStudentSaveHandoffDao

    abstract fun captureDraftMergeSessionReceiptDao(): CaptureDraftMergeSessionReceiptDao
}

object StudyDatabaseFactory {
    const val DEFAULT_DATABASE_NAME = "smart-mistake-book.db"
}

/**
 * One migration chain shared by the private production opener and debug-only compatibility
 * builders. This value configures an already-owned Room builder; it cannot open a database.
 */
internal fun legacyStudyDatabaseMigrations(): List<Migration> =
    listOf(
        REVIEW_RECEIPT_MIGRATION_1_2,
        CAPTURE_MIGRATION_2_3,
        MODEL_TASK_MIGRATION_3_4,
        TUTOR_SESSION_MIGRATION_4_5,
        DRAFT_EDIT_WORKSPACE_MIGRATION_5_6,
        CAPTURE_REQUEST_BINDING_MIGRATION_6_7,
        PROBLEM_ORGANIZATION_MIGRATION_7_8,
        DRAFT_SOURCE_BUNDLE_MIGRATION_8_9,
        TUTOR_INTERACTION_MIGRATION_9_10,
        BATCH_IMPORT_MIGRATION_10_11,
        TUTOR_ACTION_MIGRATION_11_12,
        TUTOR_RESPONSE_SLOT_MIGRATION_12_13,
        TUTOR_CHOICE_TIMESTAMP_MIGRATION_13_14,
        ATTEMPT_RESPONSE_MIGRATION_14_15,
        MODEL_TASK_OPERATION_MIGRATION_15_16,
        TUTOR_ANSWER_SURFACE_MIGRATION_16_17,
        KNOWLEDGE_BASE_MIGRATION_17_18,
        KNOWLEDGE_GROUNDING_MIGRATION_18_19,
        KNOWLEDGE_GROUNDING_RESOLUTION_MIGRATION_19_20,
        KNOWLEDGE_NODE_RELATION_MIGRATION_20_21,
        KNOWLEDGE_SEARCH_INDEX_MIGRATION_21_22,
        BATCH_IMPORT_BOUNDARY_MIGRATION_22_23,
        KNOWLEDGE_RETRIEVAL_INDEX_MIGRATION_23_24,
        KNOWLEDGE_RESEARCH_REVIEW_MIGRATION_24_25,
        KNOWLEDGE_TEACHING_MATERIAL_MIGRATION_25_26,
        MODEL_TASK_RECENT_INDEX_MIGRATION_26_27,
        KNOWLEDGE_SOURCE_REUSE_RIGHTS_MIGRATION_27_28,
        TUTOR_VISUAL_TARGET_EVIDENCE_MIGRATION_28_29,
        TUTOR_VISUAL_TARGET_EVIDENCE_MIGRATION_29_30,
        TUTOR_EVIDENCE_CANCELLATION_MIGRATION_30_31,
        LEARNING_OBSERVATION_MIGRATION_31_32,
        PROBLEM_ORGANIZATION_WORK_MIGRATION_32_33,
        TUTOR_LEARNING_MEMORY_MIGRATION_33_34,
        TUTOR_RESPONSE_IDENTITY_MIGRATION_34_35,
        LEARNING_OBSERVATION_SOURCE_FACT_MIGRATION_35_36,
        LEARNING_OBSERVATION_SOURCE_FACT_PROOF_MIGRATION_36_37,
        LEGACY_AUTHORITY_CUTOVER_JOURNAL_MIGRATION_37_38,
        CAPTURE_STUDENT_SAVE_HANDOFF_MIGRATION_38_39,
        PROBLEM_ORGANIZATION_KNOWLEDGE_REFERENCE_MIGRATION_39_40,
        CAPTURE_DRAFT_MERGE_SESSION_RECEIPT_MIGRATION_40_41,
        BATCH_BOUNDARY_CLAIM_TIME_MIGRATION_41_42,
        BATCH_CAPTURE_DRAFT_IMPORT_MIGRATION_42_43,
        TUTOR_LEARNING_EVIDENCE_SESSION_MIGRATION_43_44,
        LEGACY_BUSINESS_WRITE_BARRIER_MIGRATION_44_45,
        CURRENT_TUTOR_INTERACTION_MIGRATION_45_46,
        CURRENT_TUTOR_HOST_WORK_MIGRATION_46_47,
        CURRENT_TUTOR_VISUAL_INTENT_MIGRATION_47_48,
        TUTOR_FREE_RESPONSE_OUTBOX_MIGRATION_48_49,
    )

