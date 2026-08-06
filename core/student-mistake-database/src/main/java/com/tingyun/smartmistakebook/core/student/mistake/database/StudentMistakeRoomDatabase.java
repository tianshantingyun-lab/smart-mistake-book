package com.tingyun.smartmistakebook.core.student.mistake.database;

import androidx.room3.Database;
import androidx.room3.RoomDatabase;

/**
 * Package-private Room authority.
 *
 * <p>Keeping the database type out of the public JVM ABI prevents another Java module from
 * constructing Room, obtaining a DAO, and bypassing the owner-issued capability graph.
 */
@Database(
        entities = {
            StudentStoreMetadataEntity.class,
            StudentProblemDocumentEntity.class,
            StudentProblemRevisionEntity.class,
            StudentPracticeUnitEntity.class,
            StudentProblemImportSemanticSnapshotEntity.class,
            StudentProblemImageReferenceEntity.class,
            StudentProblemSearchDocumentEntity.class,
            StudentProblemSearchFtsEntity.class,
            StudentProblemSearchIndexStateEntity.class,
            StudentProblemCollectionEntity.class,
            StudentProblemClassificationResultEntity.class,
            StudentProblemSolutionAnalysisEntity.class,
            StudentProblemSolutionStepEntity.class,
            StudentProblemErrorAttributionEntity.class,
            StudentProblemErrorEvidenceEntity.class,
            StudentProblemErrorOccurrenceEntity.class,
            StudentProblemErrorOccurrenceEvidenceEntity.class,
            StudentProblemOrganizationReceiptEntity.class,
            StudentProblemStepKnowledgeBindingEntity.class,
            StudentProblemOrganizationFacetEntity.class,
            StudentProblemOrganizationOccurrenceBindingEntity.class,
            StudentReviewCandidateEntity.class,
            StudentReviewPlanEntity.class,
            StudentReviewQueueItemEntity.class,
            StudentReviewSelfReportReceiptEntity.class,
            StudentReviewSessionEntity.class,
            StudentReviewTransitionReceiptEntity.class,
            StudentReviewRevealReceiptEntity.class,
            StudentTrustedReviewAnswerRuleEntity.class,
            StudentTrustedReviewPresentationFenceEntity.class,
            StudentTrustedReviewLeaseReceiptEntity.class,
            StudentTrustedReviewAttemptReceiptEntity.class,
            StudentTrustedReviewAssistanceReceiptEntity.class,
            TutorInteractionAnswerCertificateEntity.class,
            TutorInteractionAnswerCertificateStatusEventEntity.class,
            TutorInteractionAnswerCertificateLeaseReceiptEntity.class,
            TutorInteractionAnswerEvaluationReceiptEntity.class,
            StudentLearnerChangeEntity.class,
            StudentMistakeSaveReceiptEntity.class,
            StudentMistakeMigrationCheckpointEntity.class,
            StudentMistakeMigrationReceiptEntity.class,
            StudentMistakeMigrationDestinationRecordEntity.class,
            StudentMistakeDestinationAttestationInvalidationEntity.class,
            StudentMistakeDestinationReattestationReceiptEntity.class,
            StudentMistakeCutoverFenceEntity.class,
            StudentMistakeCutoverCompletionReceiptEntity.class,
            StudentCaptureSaveHandoffEntity.class,
            StudentProblemCanonicalIdentityEntity.class,
            StudentProblemCanonicalSourceBindingEntity.class,
            StudentCaptureOccurrenceTransactionEntity.class,
            StudentProblemIdentityReceiptEntity.class,
            StudentOutboxAuthenticityKeyStateEntity.class,
            StudentStoreOutboxEntity.class,
            StudentStoreInboxEntity.class,
            StudentMasteryRelaySourceBindingEntity.class,
            StudentAuthenticatedMasteryInboxReceiptEntity.class,
            StudentPreAuthInboxQuarantineEntity.class,
            StudentMasteryRelayReauthorizationCaseEntity.class,
            StudentMasteryRelayReauthorizationResolutionEntity.class
        },
        version = 20,
        exportSchema = true)
abstract class StudentMistakeRoomDatabase extends RoomDatabase {
    abstract StudentMistakeDao mistakeDao();

    abstract StudentReviewDao reviewDao();

    abstract StudentCaptureIdentityDao captureIdentityDao();
    abstract StudentProblemDocumentDao problemDocumentDao();
    abstract StudentMistakeCrossStoreDao crossStoreDao();
    abstract StudentMistakeMigrationDao migrationDao();
    abstract StudentMistakeSearchIndexDao searchIndexDao();
    abstract StudentMistakeClassificationDao classificationDao();
    abstract StudentReviewWriteDao reviewWriteDao();

    abstract StudentMistakeLibraryDao libraryDao();

    abstract StudentMistakeCutoverDao cutoverDao();

    abstract StudentCutoverDestinationAttestationDao cutoverAttestationDao();

    abstract StudentProblemOrganizationDao organizationDao();

    abstract StudentProblemErrorOccurrenceDao errorOccurrenceDao();

    abstract StudentTrustedReviewAnswerDao trustedReviewAnswerDao();

    abstract TutorInteractionAnswerCertificateDao tutorInteractionAnswerCertificateDao();

    protected abstract StudentProblemIdentityReceiptDao identityReceiptDao();

    final StudentProblemIdentityReceiptLedger identityReceiptLedger() {
        return new RoomStudentProblemIdentityReceiptLedger(identityReceiptDao());
    }
}
