package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.tingyun.smartmistakebook.core.database.dao.AttemptTransactionDao
import com.tingyun.smartmistakebook.core.database.dao.BatchImportDao
import com.tingyun.smartmistakebook.core.database.dao.ChatEvidenceDao
import com.tingyun.smartmistakebook.core.database.dao.FixtureSeedDao
import com.tingyun.smartmistakebook.core.database.dao.ImmutableLearningFactDao
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeGroundingDao
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeNodeRelationDao
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeResearchReviewDao
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeTeachingMaterialDao
import com.tingyun.smartmistakebook.core.database.dao.LearningDao
import com.tingyun.smartmistakebook.core.database.dao.LibraryFtsSearchDao
import com.tingyun.smartmistakebook.core.database.dao.LibraryQueryDao
import com.tingyun.smartmistakebook.core.database.dao.MasteryOverviewDao
import com.tingyun.smartmistakebook.core.database.dao.PredictionAuditDao
import com.tingyun.smartmistakebook.core.database.dao.ModelTaskTransactionDao
import com.tingyun.smartmistakebook.core.database.dao.MistakeDetailDao
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureDao
import com.tingyun.smartmistakebook.core.database.dao.ProblemDao
import com.tingyun.smartmistakebook.core.database.dao.ProblemOrganizationDao
import com.tingyun.smartmistakebook.core.database.dao.ProblemDraftEditWorkspaceDao
import com.tingyun.smartmistakebook.core.database.dao.ProblemDraftTransactionDao
import com.tingyun.smartmistakebook.core.database.dao.ProjectionTransactionDao
import com.tingyun.smartmistakebook.core.database.dao.ReviewDao
import com.tingyun.smartmistakebook.core.database.dao.ReviewPlanTransactionDao
import com.tingyun.smartmistakebook.core.database.dao.SplitImportDao
import com.tingyun.smartmistakebook.core.database.dao.TutorInteractionDao
import com.tingyun.smartmistakebook.core.database.dao.TutorExposureDao
import com.tingyun.smartmistakebook.core.database.dao.TutorConversationDao
import com.tingyun.smartmistakebook.core.database.dao.VisualInteractionAttemptDao
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
import com.tingyun.smartmistakebook.core.database.entity.BatchImportJobEntity
import com.tingyun.smartmistakebook.core.database.entity.BatchImportPageEntity
import com.tingyun.smartmistakebook.core.database.entity.SplitImportJobEntity
import com.tingyun.smartmistakebook.core.database.entity.SplitImportQuestionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewLogEntity
import com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity
import com.tingyun.smartmistakebook.core.database.entity.LlmTeachingAdvisoryEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeQuestionLatticeView
import com.tingyun.smartmistakebook.core.database.entity.StudentModelPredictionEntity
import com.tingyun.smartmistakebook.core.database.entity.PredictionOutcomeEntity
import com.tingyun.smartmistakebook.core.database.entity.VisualInteractionAttemptEntity
import com.tingyun.smartmistakebook.core.database.entity.CanonicalSourceAssetEntity
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
import com.tingyun.smartmistakebook.core.database.entity.FtsLibrarySearchContentEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialNodeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.IndependentCorrectObservationEntity
import com.tingyun.smartmistakebook.core.database.entity.LearnerKnowledgeMasteryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.LearnerProblemMemoryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.LibrarySearchContentEntity
import com.tingyun.smartmistakebook.core.database.entity.LibrarySearchOutboxEntity
import com.tingyun.smartmistakebook.core.database.entity.LearnerProjectionSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningSequenceEntity
import com.tingyun.smartmistakebook.core.database.entity.LibraryCatalogView
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
import com.tingyun.smartmistakebook.core.database.entity.ProblemRevisionEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRevisionSourceAssetEntity
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
import com.tingyun.smartmistakebook.core.database.entity.TutorSessionProblemAnchorEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorAnswerExposureEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorAnswerExposureOutcomeEntity
import com.tingyun.smartmistakebook.core.database.entity.AppliedTutorAnswerExposureRecordEntity
import com.tingyun.smartmistakebook.core.database.entity.ContentInstallStateEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorConversationEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorMessageEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorMessageSourceAssetEntity

internal const val STUDY_DATABASE_VERSION = 46

/** Split-import status values mirrored into [SplitImportMigration]. */
internal object SplitImportLedgerStrings {
    const val PREPARING = "PREPARING"
    const val READY = "READY"
    const val COMPLETED = "COMPLETED"
}

@Database(
    views = [LibraryCatalogView::class, KnowledgeQuestionLatticeView::class],
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
        TutorSessionProblemAnchorEntity::class,
        TutorAnswerExposureEntity::class,
        TutorAnswerExposureOutcomeEntity::class,
        AppliedTutorAnswerExposureRecordEntity::class,
        TutorConversationEntity::class,
        TutorMessageEntity::class,
        TutorMessageSourceAssetEntity::class,
        BatchImportJobEntity::class,
        BatchImportPageEntity::class,
        LibrarySearchContentEntity::class,
        FtsLibrarySearchContentEntity::class,
        LibrarySearchOutboxEntity::class,
        StudentModelPredictionEntity::class,
        PredictionOutcomeEntity::class,
        VisualInteractionAttemptEntity::class,
        SplitImportJobEntity::class,
        SplitImportQuestionEntity::class,
        ReviewLogEntity::class,
        LlmTeachingAdvisoryEntity::class,
        com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity::class,
        ContentInstallStateEntity::class,
    ],
    version = STUDY_DATABASE_VERSION,
    exportSchema = true,
)
internal abstract class StudyDatabase : RoomDatabase() {
    abstract fun problemDao(): ProblemDao

    abstract fun problemOrganizationDao(): ProblemOrganizationDao

    abstract fun knowledgeGroundingDao(): KnowledgeGroundingDao

    abstract fun knowledgeNodeRelationDao(): KnowledgeNodeRelationDao

    abstract fun knowledgeResearchReviewDao(): KnowledgeResearchReviewDao

    abstract fun knowledgeTeachingMaterialDao(): KnowledgeTeachingMaterialDao

    abstract fun mistakeDetailDao(): MistakeDetailDao

    abstract fun learningDao(): LearningDao

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
    abstract fun chatEvidenceDao(): ChatEvidenceDao

    abstract fun masteryOverviewDao(): MasteryOverviewDao

    abstract fun tutorInteractionDao(): TutorInteractionDao

    abstract fun tutorExposureDao(): TutorExposureDao

    abstract fun tutorConversationDao(): TutorConversationDao

    abstract fun libraryQueryDao(): LibraryQueryDao

    abstract fun libraryFtsSearchDao(): LibraryFtsSearchDao

    abstract fun predictionAuditDao(): PredictionAuditDao

    abstract fun visualInteractionAttemptDao(): VisualInteractionAttemptDao

    abstract fun splitImportDao(): SplitImportDao

    abstract fun batchImportDao(): BatchImportDao
}

object StudyDatabaseFactory {
    const val DEFAULT_DATABASE_NAME = "smart-mistake-book.db"

    /** Persistent production builder. The framework driver keeps data in app-private storage. */
    fun open(
        context: Context,
        databaseName: String = DEFAULT_DATABASE_NAME,
    ): StudyDatabasePort {
        val database = Room.databaseBuilder(
            context.applicationContext,
            StudyDatabase::class.java,
            databaseName,
        ).addMigrations(
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
            TUTOR_CONVERSATION_MIGRATION_28_29,
            LIBRARY_CATALOG_VIEW_MIGRATION_29_30,
            TUTOR_CONVERSATION_DRAFT_MIGRATION_30_31,
            LIBRARY_SEARCH_MIGRATION_31_32,
            PREDICTION_AUDIT_MIGRATION_32_33,
            VISUAL_INTERACTION_MIGRATION_33_34,
            SPLIT_IMPORT_MIGRATION_34_35,
            MASTERY_SCHEDULING_MIGRATION_35_36,
            INTERACTION_SIGNAL_MIGRATION_36_37,
            ATTENTION_SIGNAL_MIGRATION_37_38,
            MASTERY_ADVISORY_MIGRATION_38_39,
            CHAT_EVIDENCE_MIGRATION_39_40,
            CHAT_EVIDENCE_OUTBOX_BACKFILL_MIGRATION_40_41,
            CALENDAR_DAY_MIGRATION_41_42,
            CHAT_EVIDENCE_GATE_REJECTION_MIGRATION_42_43,
            ENTRY_USER_NOTE_MIGRATION_43_44,
            TUTOR_MESSAGE_IMAGE_MIGRATION_44_45,
            KNOWLEDGE_CONTENT_LIFECYCLE_MIGRATION_45_46,
        )
            .setDriver(AndroidSQLiteDriver())
            .build()
        return RoomStudyDatabase(database)
    }

    internal fun openInMemory(context: Context): RoomStudyDatabase {
        val database = Room.inMemoryDatabaseBuilder(
            context.applicationContext,
            StudyDatabase::class.java,
        ).setDriver(AndroidSQLiteDriver())
            .addMigrations(
                SPLIT_IMPORT_MIGRATION_34_35,
            )
            .build()
        return RoomStudyDatabase(database)
    }
}
