package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
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
import com.tingyun.smartmistakebook.core.database.entity.TutorConversationEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorMessageEntity
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskLogicalOperationFingerprint

internal const val STUDY_DATABASE_VERSION = 40

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

private val REVIEW_RECEIPT_MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `review_session_advance_receipt` (
                `review_session_id` TEXT NOT NULL,
                `from_version` INTEGER NOT NULL,
                `to_version` INTEGER NOT NULL,
                `review_queue_item_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `attempt_id` TEXT NOT NULL,
                `submission_id` TEXT NOT NULL,
                `presentation_id` TEXT NOT NULL,
                `occurred_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`attempt_id`),
                FOREIGN KEY(`review_session_id`, `from_version`)
                    REFERENCES `review_session_revision`(`review_session_id`, `state_version`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`review_session_id`, `to_version`)
                    REFERENCES `review_session_revision`(`review_session_id`, `state_version`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`review_queue_item_id`)
                    REFERENCES `review_queue_item`(`review_queue_item_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`practice_unit_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`attempt_id`)
                    REFERENCES `attempt_event`(`attempt_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_review_session_advance_receipt_review_session_id_from_version`
            ON `review_session_advance_receipt` (`review_session_id`, `from_version`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_review_session_advance_receipt_review_session_id_to_version`
            ON `review_session_advance_receipt` (`review_session_id`, `to_version`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_review_session_advance_receipt_review_queue_item_id`
            ON `review_session_advance_receipt` (`review_queue_item_id`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_review_session_advance_receipt_practice_unit_id`
            ON `review_session_advance_receipt` (`practice_unit_id`)
            """.trimIndent(),
        )
    }
}

private val CAPTURE_MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `problem_revision` ADD COLUMN `question_document_snapshot` TEXT",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `canonical_source_asset` (
                `source_asset_id` TEXT NOT NULL,
                `content_sha256` TEXT NOT NULL,
                `relative_path` TEXT NOT NULL,
                `mime_type` TEXT NOT NULL,
                `byte_size` INTEGER NOT NULL,
                `width` INTEGER NOT NULL,
                `height` INTEGER NOT NULL,
                `source_type` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`source_asset_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_canonical_source_asset_content_sha256` ON `canonical_source_asset` (`content_sha256`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_canonical_source_asset_relative_path` ON `canonical_source_asset` (`relative_path`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_draft` (
                `draft_id` TEXT NOT NULL,
                `source_asset_id` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `current_revision_number` INTEGER NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`draft_id`),
                FOREIGN KEY(`source_asset_id`) REFERENCES `canonical_source_asset`(`source_asset_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_source_asset_id` ON `problem_draft` (`source_asset_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_status_updated_at_epoch_millis` ON `problem_draft` (`status`, `updated_at_epoch_millis`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_draft_revision` (
                `draft_id` TEXT NOT NULL,
                `revision_number` INTEGER NOT NULL,
                `basis_revision_number` INTEGER,
                `subject` TEXT,
                `title` TEXT NOT NULL,
                `question_document_snapshot` TEXT NOT NULL,
                `document_fingerprint` TEXT NOT NULL,
                `author` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`draft_id`, `revision_number`),
                FOREIGN KEY(`draft_id`) REFERENCES `problem_draft`(`draft_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_revision_draft_id` ON `problem_draft_revision` (`draft_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_revision_document_fingerprint` ON `problem_draft_revision` (`document_fingerprint`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_revision_source_asset` (
                `problem_revision_id` TEXT NOT NULL,
                `source_asset_id` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                PRIMARY KEY(`problem_revision_id`, `source_asset_id`, `role`),
                FOREIGN KEY(`problem_revision_id`) REFERENCES `problem_revision`(`revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`source_asset_id`) REFERENCES `canonical_source_asset`(`source_asset_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_revision_source_asset_problem_revision_id` ON `problem_revision_source_asset` (`problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_revision_source_asset_source_asset_id` ON `problem_revision_source_asset` (`source_asset_id`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_draft_commit_receipt` (
                `command_id` TEXT NOT NULL,
                `payload_fingerprint` TEXT NOT NULL,
                `draft_id` TEXT NOT NULL,
                `draft_revision_number` INTEGER NOT NULL,
                `problem_id` TEXT NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `error_book_entry_id` TEXT NOT NULL,
                `committed_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`command_id`),
                FOREIGN KEY(`draft_id`) REFERENCES `problem_draft`(`draft_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`problem_id`) REFERENCES `problem`(`problem_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`problem_revision_id`) REFERENCES `problem_revision`(`revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`practice_unit_id`) REFERENCES `practice_unit`(`practice_unit_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`error_book_entry_id`) REFERENCES `error_book_entry`(`entry_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_problem_draft_commit_receipt_draft_id` ON `problem_draft_commit_receipt` (`draft_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_commit_receipt_problem_id` ON `problem_draft_commit_receipt` (`problem_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_commit_receipt_problem_revision_id` ON `problem_draft_commit_receipt` (`problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_commit_receipt_practice_unit_id` ON `problem_draft_commit_receipt` (`practice_unit_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_commit_receipt_error_book_entry_id` ON `problem_draft_commit_receipt` (`error_book_entry_id`)",
        )
    }
}

private val MODEL_TASK_MIGRATION_3_4 = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `model_task` (
                `task_id` TEXT NOT NULL,
                `request_id` TEXT NOT NULL,
                `request_fingerprint` TEXT NOT NULL,
                `request_snapshot` TEXT NOT NULL,
                `task_kind` TEXT NOT NULL,
                `subject_id` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `state_version` INTEGER NOT NULL,
                `stage` TEXT NOT NULL,
                `user_message` TEXT NOT NULL,
                `attempt_count` INTEGER NOT NULL,
                `provider_snapshot` TEXT,
                `output_snapshot` TEXT,
                `failure_code` TEXT,
                `failure_message` TEXT,
                `failure_retryable` INTEGER,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`task_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_model_task_request_id` ON `model_task` (`request_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_subject_id_task_kind` ON `model_task` (`subject_id`, `task_kind`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_status_updated_at_epoch_millis` ON `model_task` (`status`, `updated_at_epoch_millis`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `model_task_event` (
                `task_id` TEXT NOT NULL,
                `state_version` INTEGER NOT NULL,
                `previous_status` TEXT,
                `next_status` TEXT NOT NULL,
                `stage` TEXT NOT NULL,
                `user_message` TEXT NOT NULL,
                `attempt_count` INTEGER NOT NULL,
                `provider_snapshot` TEXT,
                `output_snapshot` TEXT,
                `failure_code` TEXT,
                `failure_message` TEXT,
                `failure_retryable` INTEGER,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`task_id`, `state_version`),
                FOREIGN KEY(`task_id`) REFERENCES `model_task`(`task_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_event_task_id` ON `model_task_event` (`task_id`)",
        )
    }
}

private val TUTOR_SESSION_MIGRATION_4_5 = object : Migration(4, 5) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_session` (
                `session_id` TEXT NOT NULL,
                `draft_id` TEXT NOT NULL,
                `draft_revision_number` INTEGER NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`session_id`),
                FOREIGN KEY(`draft_id`, `draft_revision_number`)
                    REFERENCES `problem_draft_revision`(`draft_id`, `revision_number`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tutor_session_draft_id` ON `tutor_session` (`draft_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_session_draft_id_draft_revision_number` ON `tutor_session` (`draft_id`, `draft_revision_number`)",
        )
    }
}

private val DRAFT_EDIT_WORKSPACE_MIGRATION_5_6 = object : Migration(5, 6) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_draft_edit_snapshot` (
                `draft_id` TEXT NOT NULL,
                `basis_revision_number` INTEGER NOT NULL,
                `workspace_version` INTEGER NOT NULL,
                `snapshot_schema_version` INTEGER NOT NULL,
                `workspace_snapshot` TEXT NOT NULL,
                `workspace_fingerprint` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`draft_id`),
                FOREIGN KEY(`draft_id`, `basis_revision_number`)
                    REFERENCES `problem_draft_revision`(`draft_id`, `revision_number`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_edit_snapshot_draft_id_basis_revision_number` ON `problem_draft_edit_snapshot` (`draft_id`, `basis_revision_number`)",
        )
    }
}

private val CAPTURE_REQUEST_BINDING_MIGRATION_6_7 = object : Migration(6, 7) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `problem_draft` ADD COLUMN `request_fingerprint` TEXT DEFAULT NULL",
        )
    }
}

private val PROBLEM_ORGANIZATION_MIGRATION_7_8 = object : Migration(7, 8) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_classification_binding` (
                `binding_id` TEXT NOT NULL,
                `problem_id` TEXT NOT NULL,
                `basis_revision_id` TEXT NOT NULL,
                `dimension` TEXT NOT NULL,
                `label_id` TEXT NOT NULL,
                `display_name` TEXT NOT NULL,
                `taxonomy_version` TEXT NOT NULL,
                `acceptance_source` TEXT NOT NULL,
                `accepted_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`binding_id`),
                FOREIGN KEY(`problem_id`, `basis_revision_id`)
                    REFERENCES `problem_revision`(`problem_id`, `revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_classification_binding_problem_id_basis_revision_id` ON `problem_classification_binding` (`problem_id`, `basis_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_classification_binding_dimension_label_id` ON `problem_classification_binding` (`dimension`, `label_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_problem_classification_binding_problem_id_basis_revision_id_dimension_label_id` ON `problem_classification_binding` (`problem_id`, `basis_revision_id`, `dimension`, `label_id`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_organization_receipt` (
                `command_id` TEXT NOT NULL,
                `payload_fingerprint` TEXT NOT NULL,
                `problem_id` TEXT NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `classification_count` INTEGER NOT NULL,
                `relation_count` INTEGER NOT NULL,
                `accepted_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`command_id`),
                FOREIGN KEY(`practice_unit_id`, `problem_revision_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`, `problem_revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_organization_receipt_problem_id_problem_revision_id` ON `problem_organization_receipt` (`problem_id`, `problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_organization_receipt_practice_unit_id_problem_revision_id` ON `problem_organization_receipt` (`practice_unit_id`, `problem_revision_id`)",
        )
    }
}

private val DRAFT_SOURCE_BUNDLE_MIGRATION_8_9 = object : Migration(8, 9) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_draft_source_asset` (
                `draft_id` TEXT NOT NULL,
                `page_index` INTEGER NOT NULL,
                `source_asset_id` TEXT NOT NULL,
                `attached_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`draft_id`, `page_index`),
                FOREIGN KEY(`draft_id`) REFERENCES `problem_draft`(`draft_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`source_asset_id`) REFERENCES `canonical_source_asset`(`source_asset_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_source_asset_source_asset_id` ON `problem_draft_source_asset` (`source_asset_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_problem_draft_source_asset_draft_id_source_asset_id` ON `problem_draft_source_asset` (`draft_id`, `source_asset_id`)",
        )
        connection.execSQL(
            """
            INSERT OR IGNORE INTO `problem_draft_source_asset`
                (`draft_id`, `page_index`, `source_asset_id`, `attached_at_epoch_millis`)
            SELECT `draft_id`, 0, `source_asset_id`, `created_at_epoch_millis`
            FROM `problem_draft`
            """.trimIndent(),
        )
    }
}

private val TUTOR_INTERACTION_MIGRATION_9_10 = object : Migration(9, 10) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_turn_response` (
                `session_id` TEXT NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `revision_number` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `diagnostic_stem_markdown` TEXT NOT NULL,
                `selected_choice_id` TEXT NOT NULL,
                `selected_choice_markdown` TEXT NOT NULL,
                `selection_was_correct` INTEGER NOT NULL,
                `feedback_markdown` TEXT NOT NULL,
                `requested_move` TEXT,
                `solution_revealed` INTEGER NOT NULL,
                `submitted_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`session_id`, `cycle_ordinal`, `turn_ordinal`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_turn_response_question_document_id_revision_number` ON `tutor_turn_response` (`question_document_id`, `revision_number`)",
        )
    }
}

private val BATCH_IMPORT_MIGRATION_10_11 = object : Migration(10, 11) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `batch_import_job` (
                `job_id` TEXT NOT NULL,
                `request_id` TEXT NOT NULL,
                `request_fingerprint` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`job_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_batch_import_job_request_id` ON `batch_import_job` (`request_id`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `batch_import_page` (
                `job_id` TEXT NOT NULL,
                `page_index` INTEGER NOT NULL,
                `source_uri` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `result_draft_id` TEXT,
                `failure_code` TEXT,
                `attempt_count` INTEGER NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`job_id`, `page_index`),
                FOREIGN KEY(`job_id`) REFERENCES `batch_import_job`(`job_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`result_draft_id`) REFERENCES `problem_draft`(`draft_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_batch_import_page_job_id_status_page_index` ON `batch_import_page` (`job_id`, `status`, `page_index`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_batch_import_page_result_draft_id` ON `batch_import_page` (`result_draft_id`)",
        )
    }
}

private val TUTOR_ACTION_MIGRATION_11_12 = object : Migration(11, 12) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `tutor_turn_response` RENAME TO `tutor_turn_response_v11`",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_turn_response` (
                `session_id` TEXT NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `revision_number` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `diagnostic_stem_markdown` TEXT,
                `selected_choice_id` TEXT,
                `selected_choice_markdown` TEXT,
                `selection_was_correct` INTEGER,
                `feedback_markdown` TEXT,
                `requested_move` TEXT,
                `solution_revealed` INTEGER NOT NULL,
                `submitted_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`session_id`, `cycle_ordinal`, `turn_ordinal`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `tutor_turn_response` (
                `session_id`,
                `question_document_id`,
                `revision_number`,
                `cycle_ordinal`,
                `turn_ordinal`,
                `diagnostic_stem_markdown`,
                `selected_choice_id`,
                `selected_choice_markdown`,
                `selection_was_correct`,
                `feedback_markdown`,
                `requested_move`,
                `solution_revealed`,
                `submitted_at_epoch_millis`,
                `updated_at_epoch_millis`
            )
            SELECT
                `session_id`,
                `question_document_id`,
                `revision_number`,
                `cycle_ordinal`,
                `turn_ordinal`,
                `diagnostic_stem_markdown`,
                `selected_choice_id`,
                `selected_choice_markdown`,
                `selection_was_correct`,
                `feedback_markdown`,
                `requested_move`,
                `solution_revealed`,
                `submitted_at_epoch_millis`,
                `updated_at_epoch_millis`
            FROM `tutor_turn_response_v11`
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE `tutor_turn_response_v11`")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_turn_response_question_document_id_revision_number` ON `tutor_turn_response` (`question_document_id`, `revision_number`)",
        )
    }
}

private val TUTOR_RESPONSE_SLOT_MIGRATION_12_13 = object : Migration(12, 13) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `model_task` ADD COLUMN `tutor_response_ordinal` INTEGER DEFAULT NULL",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_model_task_subject_id_task_kind_tutor_response_ordinal` " +
                "ON `model_task` (`subject_id`, `task_kind`, `tutor_response_ordinal`)",
        )
    }
}

private val TUTOR_CHOICE_TIMESTAMP_MIGRATION_13_14 = object : Migration(13, 14) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `tutor_turn_response` ADD COLUMN `choice_submitted_at_epoch_millis` INTEGER",
        )
        // v13 stored only a row-level timestamp, so submitted_at is the closest available
        // approximation for legacy rows that already contain a choice.
        connection.execSQL(
            """
            UPDATE `tutor_turn_response`
            SET `choice_submitted_at_epoch_millis` = `submitted_at_epoch_millis`
            WHERE `diagnostic_stem_markdown` IS NOT NULL
            """.trimIndent(),
        )
    }
}

internal val ATTEMPT_RESPONSE_MIGRATION_14_15 = object : Migration(14, 15) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `attempt_event` ADD COLUMN `submitted_choice_id` TEXT",
        )
        connection.execSQL(
            "ALTER TABLE `attempt_event` ADD COLUMN `submitted_choice_markdown` TEXT",
        )
        connection.execSQL(
            "ALTER TABLE `attempt_event` ADD COLUMN `response_submitted_at_epoch_millis` INTEGER",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_session_problem_anchor` (
                `session_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `anchor_source` TEXT NOT NULL,
                `anchored_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`session_id`),
                FOREIGN KEY(`practice_unit_id`, `problem_revision_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`, `problem_revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_session_problem_anchor_learner_id` ON `tutor_session_problem_anchor` (`learner_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_session_problem_anchor_practice_unit_id_problem_revision_id` ON `tutor_session_problem_anchor` (`practice_unit_id`, `problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_session_problem_anchor_problem_revision_id` ON `tutor_session_problem_anchor` (`problem_revision_id`)",
        )
        connection.execSQL(
            """
            INSERT OR IGNORE INTO `tutor_session_problem_anchor` (
                `session_id`, `learner_id`, `problem_revision_id`, `practice_unit_id`,
                `anchor_source`, `anchored_at_epoch_millis`
            )
            SELECT
                tutor.`session_id`,
                'learner:local',
                receipt.`problem_revision_id`,
                receipt.`practice_unit_id`,
                'DRAFT_COMMIT',
                receipt.`committed_at_epoch_millis`
            FROM `tutor_session` AS tutor
            JOIN `problem_draft_commit_receipt` AS receipt
              ON receipt.`draft_id` = tutor.`draft_id`
             AND receipt.`draft_revision_number` = tutor.`draft_revision_number`
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_answer_exposure` (
                `exposure_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `question_revision_number` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `exposed_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`exposure_id`),
                FOREIGN KEY(`session_id`, `cycle_ordinal`, `turn_ordinal`)
                    REFERENCES `tutor_turn_response`(`session_id`, `cycle_ordinal`, `turn_ordinal`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_answer_exposure_learner_id` ON `tutor_answer_exposure` (`learner_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_answer_exposure_session_id` ON `tutor_answer_exposure` (`session_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tutor_answer_exposure_session_id_cycle_ordinal_turn_ordinal` ON `tutor_answer_exposure` (`session_id`, `cycle_ordinal`, `turn_ordinal`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_answer_exposure_outcome` (
                `outcome_id` TEXT NOT NULL,
                `exposure_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `question_revision_number` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `event_sequence` INTEGER NOT NULL,
                `canonical_fingerprint` TEXT NOT NULL,
                `occurred_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`outcome_id`),
                FOREIGN KEY(`exposure_id`) REFERENCES `tutor_answer_exposure`(`exposure_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`session_id`) REFERENCES `tutor_session_problem_anchor`(`session_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`practice_unit_id`, `problem_revision_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`, `problem_revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tutor_answer_exposure_outcome_exposure_id` ON `tutor_answer_exposure_outcome` (`exposure_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_answer_exposure_outcome_session_id` ON `tutor_answer_exposure_outcome` (`session_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_answer_exposure_outcome_practice_unit_id_problem_revision_id` ON `tutor_answer_exposure_outcome` (`practice_unit_id`, `problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tutor_answer_exposure_outcome_learner_id_event_sequence` ON `tutor_answer_exposure_outcome` (`learner_id`, `event_sequence`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tutor_answer_exposure_outcome_learner_id_outcome_id` ON `tutor_answer_exposure_outcome` (`learner_id`, `outcome_id`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `applied_tutor_answer_exposure_record` (
                `projection_name` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `outcome_id` TEXT NOT NULL,
                `exposure_id` TEXT NOT NULL,
                `canonical_fingerprint` TEXT NOT NULL,
                `event_sequence` INTEGER NOT NULL,
                PRIMARY KEY(`projection_name`, `learner_id`, `outcome_id`),
                FOREIGN KEY(`projection_name`, `learner_id`)
                    REFERENCES `learner_projection_snapshot`(`projection_name`, `learner_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`learner_id`, `outcome_id`)
                    REFERENCES `tutor_answer_exposure_outcome`(`learner_id`, `outcome_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_applied_tutor_answer_exposure_record_projection_name_learner_id` ON `applied_tutor_answer_exposure_record` (`projection_name`, `learner_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_applied_tutor_answer_exposure_record_learner_id_outcome_id` ON `applied_tutor_answer_exposure_record` (`learner_id`, `outcome_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_applied_tutor_answer_exposure_record_projection_name_learner_id_event_sequence` ON `applied_tutor_answer_exposure_record` (`projection_name`, `learner_id`, `event_sequence`)",
        )
    }
}

internal val MODEL_TASK_OPERATION_MIGRATION_15_16 = object : Migration(15, 16) {
    override suspend fun migrate(connection: SQLiteConnection) {
        val legacyTasks = connection.readLegacyModelTaskOperations()
        val operationGroups = legacyTasks.groupBy(ModelTaskOperationBackfill::operationFingerprint)

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `model_task_operation` (
                `operation_fingerprint` TEXT NOT NULL,
                `subject_id` TEXT NOT NULL,
                `task_kind` TEXT NOT NULL,
                `dispatch_count` INTEGER NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`operation_fingerprint`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_operation_subject_id_task_kind` " +
                "ON `model_task_operation` (`subject_id`, `task_kind`)",
        )
        connection.insertModelTaskOperations(operationGroups)

        connection.execSQL(
            "CREATE TEMP TABLE `model_task_operation_backfill` (" +
                "`task_id` TEXT NOT NULL PRIMARY KEY, `operation_fingerprint` TEXT NOT NULL)",
        )
        connection.insertModelTaskOperationBackfill(legacyTasks)
        connection.execSQL(
            """
            CREATE TABLE `model_task_v16` (
                `task_id` TEXT NOT NULL,
                `request_id` TEXT NOT NULL,
                `request_fingerprint` TEXT NOT NULL,
                `operation_fingerprint` TEXT NOT NULL,
                `request_snapshot` TEXT NOT NULL,
                `task_kind` TEXT NOT NULL,
                `subject_id` TEXT NOT NULL,
                `tutor_response_ordinal` INTEGER,
                `status` TEXT NOT NULL,
                `state_version` INTEGER NOT NULL,
                `stage` TEXT NOT NULL,
                `user_message` TEXT NOT NULL,
                `attempt_count` INTEGER NOT NULL,
                `provider_snapshot` TEXT,
                `output_snapshot` TEXT,
                `failure_code` TEXT,
                `failure_message` TEXT,
                `failure_retryable` INTEGER,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`task_id`),
                FOREIGN KEY(`operation_fingerprint`)
                    REFERENCES `model_task_operation`(`operation_fingerprint`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `model_task_v16` (
                `task_id`, `request_id`, `request_fingerprint`, `operation_fingerprint`,
                `request_snapshot`, `task_kind`, `subject_id`, `tutor_response_ordinal`,
                `status`, `state_version`, `stage`, `user_message`, `attempt_count`,
                `provider_snapshot`, `output_snapshot`, `failure_code`, `failure_message`,
                `failure_retryable`, `created_at_epoch_millis`, `updated_at_epoch_millis`
            )
            SELECT
                task.`task_id`, task.`request_id`, task.`request_fingerprint`,
                backfill.`operation_fingerprint`, task.`request_snapshot`, task.`task_kind`,
                task.`subject_id`, task.`tutor_response_ordinal`, task.`status`,
                task.`state_version`, task.`stage`, task.`user_message`, task.`attempt_count`,
                task.`provider_snapshot`, task.`output_snapshot`, task.`failure_code`,
                task.`failure_message`, task.`failure_retryable`,
                task.`created_at_epoch_millis`, task.`updated_at_epoch_millis`
            FROM `model_task` AS task
            INNER JOIN `model_task_operation_backfill` AS backfill
                ON backfill.`task_id` = task.`task_id`
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE `model_task_event_v16` (
                `task_id` TEXT NOT NULL,
                `state_version` INTEGER NOT NULL,
                `previous_status` TEXT,
                `next_status` TEXT NOT NULL,
                `stage` TEXT NOT NULL,
                `user_message` TEXT NOT NULL,
                `attempt_count` INTEGER NOT NULL,
                `provider_snapshot` TEXT,
                `output_snapshot` TEXT,
                `failure_code` TEXT,
                `failure_message` TEXT,
                `failure_retryable` INTEGER,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`task_id`, `state_version`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `model_task_event_v16` (
                `task_id`, `state_version`, `previous_status`, `next_status`, `stage`,
                `user_message`, `attempt_count`, `provider_snapshot`, `output_snapshot`,
                `failure_code`, `failure_message`, `failure_retryable`,
                `created_at_epoch_millis`
            )
            SELECT
                `task_id`, `state_version`, `previous_status`, `next_status`, `stage`,
                `user_message`, `attempt_count`, `provider_snapshot`, `output_snapshot`,
                `failure_code`, `failure_message`, `failure_retryable`,
                `created_at_epoch_millis`
            FROM `model_task_event`
            """.trimIndent(),
        )

        connection.execSQL("DROP TABLE `model_task_event`")
        connection.execSQL("DROP TABLE `model_task`")
        connection.execSQL("ALTER TABLE `model_task_v16` RENAME TO `model_task`")
        connection.execSQL(
            """
            CREATE TABLE `model_task_event` (
                `task_id` TEXT NOT NULL,
                `state_version` INTEGER NOT NULL,
                `previous_status` TEXT,
                `next_status` TEXT NOT NULL,
                `stage` TEXT NOT NULL,
                `user_message` TEXT NOT NULL,
                `attempt_count` INTEGER NOT NULL,
                `provider_snapshot` TEXT,
                `output_snapshot` TEXT,
                `failure_code` TEXT,
                `failure_message` TEXT,
                `failure_retryable` INTEGER,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`task_id`, `state_version`),
                FOREIGN KEY(`task_id`) REFERENCES `model_task`(`task_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `model_task_event` (
                `task_id`, `state_version`, `previous_status`, `next_status`, `stage`,
                `user_message`, `attempt_count`, `provider_snapshot`, `output_snapshot`,
                `failure_code`, `failure_message`, `failure_retryable`,
                `created_at_epoch_millis`
            )
            SELECT
                `task_id`, `state_version`, `previous_status`, `next_status`, `stage`,
                `user_message`, `attempt_count`, `provider_snapshot`, `output_snapshot`,
                `failure_code`, `failure_message`, `failure_retryable`,
                `created_at_epoch_millis`
            FROM `model_task_event_v16`
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE `model_task_event_v16`")
        connection.execSQL("DROP TABLE `model_task_operation_backfill`")

        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_model_task_request_id` " +
                "ON `model_task` (`request_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_operation_fingerprint` " +
                "ON `model_task` (`operation_fingerprint`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_subject_id_task_kind` " +
                "ON `model_task` (`subject_id`, `task_kind`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_model_task_subject_id_task_kind_tutor_response_ordinal` " +
                "ON `model_task` (`subject_id`, `task_kind`, `tutor_response_ordinal`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_status_updated_at_epoch_millis` " +
                "ON `model_task` (`status`, `updated_at_epoch_millis`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_event_task_id` " +
                "ON `model_task_event` (`task_id`)",
        )
    }
}

internal val TUTOR_ANSWER_SURFACE_MIGRATION_16_17 = object : Migration(16, 17) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE `tutor_answer_exposure_v17` (
                `exposure_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `question_revision_number` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `surface_kind` TEXT NOT NULL,
                `model_task_request_id` TEXT,
                `response_ordinal` INTEGER,
                `exposed_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`exposure_id`),
                FOREIGN KEY(`model_task_request_id`) REFERENCES `model_task`(`request_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE `tutor_answer_exposure_outcome_v17` (
                `outcome_id` TEXT NOT NULL,
                `exposure_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `question_revision_number` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `event_sequence` INTEGER NOT NULL,
                `canonical_fingerprint` TEXT NOT NULL,
                `occurred_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`outcome_id`),
                FOREIGN KEY(`session_id`) REFERENCES `tutor_session_problem_anchor`(`session_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`practice_unit_id`, `problem_revision_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`, `problem_revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE `applied_tutor_answer_exposure_record_v17` (
                `projection_name` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `outcome_id` TEXT NOT NULL,
                `exposure_id` TEXT NOT NULL,
                `canonical_fingerprint` TEXT NOT NULL,
                `event_sequence` INTEGER NOT NULL,
                PRIMARY KEY(`projection_name`, `learner_id`, `outcome_id`),
                FOREIGN KEY(`projection_name`, `learner_id`)
                    REFERENCES `learner_projection_snapshot`(`projection_name`, `learner_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        connection.execSQL(
            """
            INSERT INTO `tutor_answer_exposure_v17` (
                `exposure_id`, `learner_id`, `session_id`, `question_document_id`,
                `question_revision_number`, `cycle_ordinal`, `turn_ordinal`, `surface_kind`,
                `model_task_request_id`, `response_ordinal`, `exposed_at_epoch_millis`
            )
            SELECT
                `exposure_id`, `learner_id`, `session_id`, `question_document_id`,
                `question_revision_number`, `cycle_ordinal`, `turn_ordinal`, 'LEGACY_TURN',
                NULL, NULL, `exposed_at_epoch_millis`
            FROM `tutor_answer_exposure`
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `tutor_answer_exposure_outcome_v17` (
                `outcome_id`, `exposure_id`, `learner_id`, `session_id`,
                `question_document_id`, `question_revision_number`, `cycle_ordinal`,
                `turn_ordinal`, `problem_revision_id`, `practice_unit_id`, `event_sequence`,
                `canonical_fingerprint`, `occurred_at_epoch_millis`
            )
            SELECT
                `outcome_id`, `exposure_id`, `learner_id`, `session_id`,
                `question_document_id`, `question_revision_number`, `cycle_ordinal`,
                `turn_ordinal`, `problem_revision_id`, `practice_unit_id`, `event_sequence`,
                `canonical_fingerprint`, `occurred_at_epoch_millis`
            FROM `tutor_answer_exposure_outcome`
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `applied_tutor_answer_exposure_record_v17` (
                `projection_name`, `learner_id`, `outcome_id`, `exposure_id`,
                `canonical_fingerprint`, `event_sequence`
            )
            SELECT
                `projection_name`, `learner_id`, `outcome_id`, `exposure_id`,
                `canonical_fingerprint`, `event_sequence`
            FROM `applied_tutor_answer_exposure_record`
            """.trimIndent(),
        )

        connection.execSQL("DROP TABLE `applied_tutor_answer_exposure_record`")
        connection.execSQL("DROP TABLE `tutor_answer_exposure_outcome`")
        connection.execSQL("DROP TABLE `tutor_answer_exposure`")
        connection.execSQL(
            "ALTER TABLE `tutor_answer_exposure_v17` RENAME TO `tutor_answer_exposure`",
        )
        connection.execSQL(
            """
            CREATE TABLE `tutor_answer_exposure_outcome` (
                `outcome_id` TEXT NOT NULL,
                `exposure_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `question_revision_number` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `event_sequence` INTEGER NOT NULL,
                `canonical_fingerprint` TEXT NOT NULL,
                `occurred_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`outcome_id`),
                FOREIGN KEY(`exposure_id`) REFERENCES `tutor_answer_exposure`(`exposure_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`session_id`) REFERENCES `tutor_session_problem_anchor`(`session_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`practice_unit_id`, `problem_revision_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`, `problem_revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `tutor_answer_exposure_outcome` (
                `outcome_id`, `exposure_id`, `learner_id`, `session_id`,
                `question_document_id`, `question_revision_number`, `cycle_ordinal`,
                `turn_ordinal`, `problem_revision_id`, `practice_unit_id`, `event_sequence`,
                `canonical_fingerprint`, `occurred_at_epoch_millis`
            )
            SELECT
                `outcome_id`, `exposure_id`, `learner_id`, `session_id`,
                `question_document_id`, `question_revision_number`, `cycle_ordinal`,
                `turn_ordinal`, `problem_revision_id`, `practice_unit_id`, `event_sequence`,
                `canonical_fingerprint`, `occurred_at_epoch_millis`
            FROM `tutor_answer_exposure_outcome_v17`
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX `index_tutor_answer_exposure_outcome_v17_learner_outcome` " +
                "ON `tutor_answer_exposure_outcome` (`learner_id`, `outcome_id`)",
        )
        connection.execSQL(
            """
            CREATE TABLE `applied_tutor_answer_exposure_record` (
                `projection_name` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `outcome_id` TEXT NOT NULL,
                `exposure_id` TEXT NOT NULL,
                `canonical_fingerprint` TEXT NOT NULL,
                `event_sequence` INTEGER NOT NULL,
                PRIMARY KEY(`projection_name`, `learner_id`, `outcome_id`),
                FOREIGN KEY(`projection_name`, `learner_id`)
                    REFERENCES `learner_projection_snapshot`(`projection_name`, `learner_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`learner_id`, `outcome_id`)
                    REFERENCES `tutor_answer_exposure_outcome`(`learner_id`, `outcome_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `applied_tutor_answer_exposure_record` (
                `projection_name`, `learner_id`, `outcome_id`, `exposure_id`,
                `canonical_fingerprint`, `event_sequence`
            )
            SELECT
                `projection_name`, `learner_id`, `outcome_id`, `exposure_id`,
                `canonical_fingerprint`, `event_sequence`
            FROM `applied_tutor_answer_exposure_record_v17`
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE `applied_tutor_answer_exposure_record_v17`")
        connection.execSQL("DROP TABLE `tutor_answer_exposure_outcome_v17`")

        connection.execSQL(
            "CREATE INDEX `index_tutor_answer_exposure_learner_id` " +
                "ON `tutor_answer_exposure` (`learner_id`)",
        )
        connection.execSQL(
            "CREATE INDEX `index_tutor_answer_exposure_session_id` " +
                "ON `tutor_answer_exposure` (`session_id`)",
        )
        connection.execSQL(
            "CREATE INDEX `index_tutor_answer_exposure_session_id_cycle_ordinal_turn_ordinal` " +
                "ON `tutor_answer_exposure` (`session_id`, `cycle_ordinal`, `turn_ordinal`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX `index_tutor_answer_exposure_model_task_request_id` " +
                "ON `tutor_answer_exposure` (`model_task_request_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX `index_tutor_answer_exposure_outcome_exposure_id` " +
                "ON `tutor_answer_exposure_outcome` (`exposure_id`)",
        )
        connection.execSQL(
            "CREATE INDEX `index_tutor_answer_exposure_outcome_session_id` " +
                "ON `tutor_answer_exposure_outcome` (`session_id`)",
        )
        connection.execSQL(
            "CREATE INDEX " +
                "`index_tutor_answer_exposure_outcome_practice_unit_id_problem_revision_id` " +
                "ON `tutor_answer_exposure_outcome` (`practice_unit_id`, `problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX " +
                "`index_tutor_answer_exposure_outcome_learner_id_event_sequence` " +
                "ON `tutor_answer_exposure_outcome` (`learner_id`, `event_sequence`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX " +
                "`index_tutor_answer_exposure_outcome_learner_id_outcome_id` " +
                "ON `tutor_answer_exposure_outcome` (`learner_id`, `outcome_id`)",
        )
        connection.execSQL(
            "DROP INDEX `index_tutor_answer_exposure_outcome_v17_learner_outcome`",
        )
        connection.execSQL(
            "CREATE INDEX " +
                "`index_applied_tutor_answer_exposure_record_projection_name_learner_id` " +
                "ON `applied_tutor_answer_exposure_record` (`projection_name`, `learner_id`)",
        )
        connection.execSQL(
            "CREATE INDEX " +
                "`index_applied_tutor_answer_exposure_record_learner_id_outcome_id` " +
                "ON `applied_tutor_answer_exposure_record` (`learner_id`, `outcome_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX " +
                "`index_applied_tutor_answer_exposure_record_projection_name_learner_id_event_sequence` " +
                "ON `applied_tutor_answer_exposure_record` " +
                "(`projection_name`, `learner_id`, `event_sequence`)",
        )
    }
}

internal val KNOWLEDGE_BASE_MIGRATION_17_18 = object : Migration(17, 18) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `knowledge_node` ADD COLUMN `canonical_name` TEXT NOT NULL DEFAULT ''",
        )
        connection.execSQL("UPDATE `knowledge_node` SET `canonical_name` = `display_name`")
        connection.execSQL(
            "ALTER TABLE `knowledge_node` ADD COLUMN `node_kind` TEXT NOT NULL DEFAULT 'TOPIC'",
        )
        connection.execSQL(
            "ALTER TABLE `knowledge_node` ADD COLUMN `granularity` TEXT NOT NULL DEFAULT 'TOPIC'",
        )
        connection.execSQL(
            "ALTER TABLE `knowledge_node` ADD COLUMN `aliases_text` TEXT NOT NULL DEFAULT ''",
        )
        connection.execSQL(
            "ALTER TABLE `knowledge_node` ADD COLUMN `boundary_markdown` TEXT",
        )
        connection.execSQL(
            "ALTER TABLE `knowledge_node` " +
                "ADD COLUMN `verification_status` TEXT NOT NULL DEFAULT 'MODEL_CANDIDATE'",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_node_subject_canonical_name` " +
                "ON `knowledge_node` (`subject`, `canonical_name`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_node_subject_granularity` " +
                "ON `knowledge_node` (`subject`, `granularity`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_source` (
                `source_id` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `source_type` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `publisher` TEXT,
                `edition` TEXT,
                `source_uri` TEXT,
                `license_status` TEXT NOT NULL,
                `content_fingerprint` TEXT NOT NULL,
                `imported_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`source_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_source_content_fingerprint` " +
                "ON `knowledge_source` (`content_fingerprint`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_source_subject_source_type` " +
                "ON `knowledge_source` (`subject`, `source_type`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_node_source_binding` (
                `knowledge_node_id` TEXT NOT NULL,
                `source_id` TEXT NOT NULL,
                `source_locator` TEXT NOT NULL,
                `derivation_note` TEXT NOT NULL,
                `reviewed_at_epoch_millis` INTEGER,
                PRIMARY KEY(`knowledge_node_id`, `source_id`, `source_locator`),
                FOREIGN KEY(`knowledge_node_id`) REFERENCES `knowledge_node`(`knowledge_node_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`source_id`) REFERENCES `knowledge_source`(`source_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_node_source_binding_knowledge_node_id` " +
                "ON `knowledge_node_source_binding` (`knowledge_node_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_node_source_binding_source_id` " +
                "ON `knowledge_node_source_binding` (`source_id`)",
        )
    }
}

internal val KNOWLEDGE_GROUNDING_MIGRATION_18_19 = object : Migration(18, 19) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_grounding_request` (
                `grounding_request_id` TEXT NOT NULL,
                `grounding_key` TEXT NOT NULL,
                `organization_request_id` TEXT NOT NULL,
                `organization_request_fingerprint` TEXT NOT NULL,
                `request_ordinal` INTEGER NOT NULL,
                `problem_id` TEXT NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `query` TEXT NOT NULL,
                `expected_parent_knowledge_display_name` TEXT NOT NULL,
                `reason_markdown` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`grounding_request_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_knowledge_grounding_request_organization_request_id_request_ordinal` " +
                "ON `knowledge_grounding_request` (`organization_request_id`, `request_ordinal`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_knowledge_grounding_request_status_created_at_epoch_millis` " +
                "ON `knowledge_grounding_request` (`status`, `created_at_epoch_millis`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_grounding_request_subject_status` " +
                "ON `knowledge_grounding_request` (`subject`, `status`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_grounding_request_grounding_key_status` " +
                "ON `knowledge_grounding_request` (`grounding_key`, `status`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_grounding_request_problem_revision_id` " +
                "ON `knowledge_grounding_request` (`problem_revision_id`)",
        )
    }
}

private data class ModelTaskOperationBackfill(
    val taskId: String,
    val operationFingerprint: String,
    val subjectId: String,
    val taskKind: String,
    val attemptCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

private fun SQLiteConnection.readLegacyModelTaskOperations(): List<ModelTaskOperationBackfill> =
    buildList {
        prepare(
            """
            SELECT `task_id`, `request_snapshot`, `subject_id`, `task_kind`, `attempt_count`,
                `created_at_epoch_millis`, `updated_at_epoch_millis`
            FROM `model_task`
            """.trimIndent(),
        ).use { statement ->
            while (statement.step()) {
                val request = ModelTaskCodec.decodeRequest(statement.getText(1))
                val subjectId = statement.getText(2)
                val taskKind = statement.getText(3)
                require(request.input.subjectId == subjectId && request.input.kind.name == taskKind) {
                    "Legacy model task columns disagree with its request snapshot"
                }
                add(
                    ModelTaskOperationBackfill(
                        taskId = statement.getText(0),
                        operationFingerprint = ModelTaskLogicalOperationFingerprint.of(request),
                        subjectId = subjectId,
                        taskKind = taskKind,
                        attemptCount = statement.getLong(4).toInt(),
                        createdAtEpochMillis = statement.getLong(5),
                        updatedAtEpochMillis = statement.getLong(6),
                    ),
                )
            }
        }
    }

private fun SQLiteConnection.insertModelTaskOperations(
    groups: Map<String, List<ModelTaskOperationBackfill>>,
) {
    prepare(
        """
        INSERT INTO `model_task_operation` (
            `operation_fingerprint`, `subject_id`, `task_kind`, `dispatch_count`,
            `created_at_epoch_millis`, `updated_at_epoch_millis`
        ) VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        groups.forEach { (operationFingerprint, rows) ->
            val first = rows.first()
            require(rows.all { it.subjectId == first.subjectId && it.taskKind == first.taskKind }) {
                "Logical model operation hash collision during migration"
            }
            val dispatchCount = rows.sumOf { it.attemptCount.toLong() }
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            statement.bindText(1, operationFingerprint)
            statement.bindText(2, first.subjectId)
            statement.bindText(3, first.taskKind)
            statement.bindLong(4, dispatchCount.toLong())
            statement.bindLong(5, rows.minOf { it.createdAtEpochMillis })
            statement.bindLong(6, rows.maxOf { it.updatedAtEpochMillis })
            statement.step()
            statement.reset()
            statement.clearBindings()
        }
    }
}

private fun SQLiteConnection.insertModelTaskOperationBackfill(
    rows: List<ModelTaskOperationBackfill>,
) {
    prepare(
        "INSERT INTO `model_task_operation_backfill` (`task_id`, `operation_fingerprint`) " +
            "VALUES (?, ?)",
    ).use { statement ->
        rows.forEach { row ->
            statement.bindText(1, row.taskId)
            statement.bindText(2, row.operationFingerprint)
            statement.step()
            statement.reset()
            statement.clearBindings()
        }
    }
}
