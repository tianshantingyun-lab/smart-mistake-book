package com.tingyun.smartmistakebook.core.student.mistake.database;

import android.content.Context;
import com.tingyun.smartmistakebook.core.data.authority.ProductionStudentTerminalAuthorityAttestationOwner;
import com.tingyun.smartmistakebook.core.data.authority.StudentDocumentImportReceiptReferenceBinder;
import com.tingyun.smartmistakebook.core.data.authority.StudentTerminalAuthorityAttestationOwner;
import com.tingyun.smartmistakebook.core.data.authority.ThreeAuthorityDatabaseLayout;
import com.tingyun.smartmistakebook.core.data.capture.ProductionStudentCaptureOccurrenceOwner;
import com.tingyun.smartmistakebook.core.data.capture.ProductionStudentProblemIdentityEvidenceRequest;
import com.tingyun.smartmistakebook.core.data.mistake.LearnerBoundVerifiedStudentProblemKnowledgeAttributionPort;
import com.tingyun.smartmistakebook.core.data.mistake.MistakeDetailRepositoryFactory;
import com.tingyun.smartmistakebook.core.data.mistake.ReviewedStudentProblemOrganizationReviewPort;
import com.tingyun.smartmistakebook.core.data.mistake.VerifiedStudentKnowledgeAttributionOwnerAssembly;
import com.tingyun.smartmistakebook.core.data.review.DailyReviewActionPorts;
import com.tingyun.smartmistakebook.core.data.review.DailyReviewActionPortsFactory;
import com.tingyun.smartmistakebook.core.data.review.DailyReviewRepositoryFactory;
import com.tingyun.smartmistakebook.core.data.review.DeterministicDailyReviewPacingCommandFactory;
import com.tingyun.smartmistakebook.core.data.review.LearnerBoundDailyReviewPlanPort;
import com.tingyun.smartmistakebook.core.data.review.ProductionDailyReviewPorts;
import com.tingyun.smartmistakebook.core.data.review.StudentTrustedDailyReviewAnswerSubmissionPortFactory;
import com.tingyun.smartmistakebook.core.data.review.ThreeAuthorityReviewPlanCoordinator;
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository;
import com.tingyun.smartmistakebook.core.domain.ThreeAuthorityReviewPlanner;
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog;
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextReader;
import com.tingyun.smartmistakebook.core.mastery.database.VerifiedLearnerMasteryOutboxDelivery;
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot;
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope;
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority;
import java.util.List;
import java.util.Set;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

/**
 * The single audited source bridge into the student-mistake owner package.
 *
 * <p>Owner-only mapping, review and identity transitions happen here. This source never handles a
 * key material or raw authenticity proof. Cross-store handoff uses only owner-issued, immutable
 * verified delivery values.
 */
public final class StudentMistakeOwnerAccess {
    private StudentMistakeOwnerAccess() {}

    public static StudentMistakeRuntimeCapabilities openStudentMistakeOwnerCapabilities(
            Context context,
            String learnerId,
            KnowledgeReferenceProofAuthority.Verifier knowledgeReferenceVerifier) {
        return CoreDataStudentMistakeOwnerBridge.openRuntime(
                context,
                learnerId,
                knowledgeReferenceVerifier);
    }

    public static StudentOutboxAuthenticityVerifier openStudentOutboxAuthenticityVerifier(
            StudentMistakeRuntimeCapabilities capabilities) {
        return capabilities.outboxAuthenticityVerifier();
    }

    public static VerifiedStudentOutboxDelivery verifyStudentOutboxDelivery(
            StudentOutboxDelivery delivery,
            StudentOutboxAuthenticityVerifier verifier) {
        StudentOutboxVerificationReceipt receipt = verifier.verifyDelivery(delivery);
        return new VerifiedStudentOutboxDelivery(delivery, receipt);
    }

    public static VerifiedLearnerMasteryDelivery bindVerifiedLearnerMasteryOutboxDelivery(
            VerifiedLearnerMasteryOutboxDelivery delivery) {
        CrossStoreEventEnvelope envelope =
                new CrossStoreEventEnvelope(
                        delivery.getEventId(),
                        delivery.getSourceStore(),
                        delivery.getDestinationStore(),
                        delivery.getAggregateId(),
                        delivery.getAggregateVersion(),
                        delivery.getOccurredAtEpochMillis(),
                        delivery.getIdempotencyKey(),
                        delivery.getSourceStoreGeneration(),
                        delivery.getPayload(),
                        delivery.getPayloadType(),
                        delivery.getPayloadVersion(),
                        delivery.getPayloadCanonicalFingerprint());
        if (!envelope.getCanonicalFingerprint().equals(
                        delivery.getEnvelopeCanonicalFingerprint())) {
            throw new SecurityException(
                    "Source-verified learner-mastery delivery changed during binding");
        }
        return new VerifiedLearnerMasteryDelivery(
                envelope,
                delivery.getLearnerId(),
                delivery.getSourceStoreGeneration(),
                delivery.getRelayEpoch(),
                delivery.getIssuerKeyId(),
                delivery.getAlgorithmVersion(),
                delivery.getProofCanonicalFingerprint(),
                delivery.getEnvelopeCanonicalFingerprint(),
                delivery.getVerificationReceiptCanonicalFingerprint());
    }

    public static StudentMistakeMigrationPort openStudentMistakeMigrationOwner(Context context) {
        return CoreDataStudentMistakeOwnerBridge.openMigration(context);
    }

    public static StudentMistakeCutoverControlPort openStudentMistakeCutoverControlOwner(
            Context context) {
        return CoreDataStudentMistakeOwnerBridge.openCutoverControl(context);
    }

    public static StudentTerminalAuthorityAttestationOwner
            openStudentTerminalAuthorityAttestationOwner(
                    Context context,
                    ThreeAuthorityDatabaseLayout layout,
                    Function0<Long> clock) {
        StudentDocumentImportReceiptReferenceBinder receiptBinder =
                (cutoverGeneration,
                                legacyPrefixFingerprint,
                                migratedRecordCount,
                                sourceCheckpoint,
                                destinationFingerprint,
                                receiptFingerprint) ->
                        CoreDataStudentMistakeOwnerBridge.bindVerifiedDocumentImportReceipt(
                                cutoverGeneration,
                                legacyPrefixFingerprint,
                                migratedRecordCount,
                                sourceCheckpoint,
                                destinationFingerprint,
                                receiptFingerprint);
        return new ProductionStudentTerminalAuthorityAttestationOwner(
                layout,
                CoreDataStudentMistakeOwnerBridge.openCutoverDestinationAttestations(context),
                receiptBinder,
                clock);
    }

    public static MistakeDetailRepository openMistakeDetailRepository(
            StudentMistakeRuntimeCapabilities capabilities) {
        return MistakeDetailRepositoryFactory.INSTANCE.create$data(capabilities.businessStore());
    }

    public static ThreeAuthorityReviewPlanCoordinator openThreeAuthorityReviewPlanCoordinator(
            StudentMistakeRuntimeCapabilities capabilities,
            LocalMasteryContextReader masteryContext,
            int candidateLimit) {
        return new ThreeAuthorityReviewPlanCoordinator(
                capabilities.businessStore(),
                masteryContext,
                new ThreeAuthorityReviewPlanner(),
                candidateLimit,
                candidateLimit);
    }

    public static ProductionDailyReviewPorts openProductionDailyReviewPorts(
            StudentMistakeRuntimeCapabilities capabilities,
            LearnerBoundDailyReviewPlanPort planner,
            LocalMasteryContextReader masteryContext,
            HighSchoolKnowledgeCatalog highSchoolKnowledge,
            Function0<Long> nowEpochMillis,
            Object onReviewObservationRecorded) {
        if (!(onReviewObservationRecorded instanceof Function1)) {
            throw new IllegalArgumentException("Review observation callback is invalid");
        }
        @SuppressWarnings("unchecked")
        Function1<? super Continuation<? super Unit>, ? extends Object> recordedCallback =
                (Function1<? super Continuation<? super Unit>, ? extends Object>)
                        onReviewObservationRecorded;
        DailyReviewActionPorts actions =
                DailyReviewActionPortsFactory.INSTANCE.create(capabilities.getReviewSessions());
        StudentTrustedDailyReviewAnswerSubmissionPortFactory.Ports trustedAnswerPorts =
                StudentTrustedDailyReviewAnswerSubmissionPortFactory.INSTANCE.createPorts(
                        capabilities.getTrustedReviewAnswers(),
                        recordedCallback);
        return new ProductionDailyReviewPorts(
                DailyReviewRepositoryFactory.INSTANCE.create(
                        capabilities.getLearnerId(),
                        capabilities.businessStore(),
                        capabilities.getReviewSessions(),
                        planner,
                        masteryContext,
                        highSchoolKnowledge),
                actions.getSessions(),
                actions.getPacing(),
                trustedAnswerPorts.getAnswerSubmissionPorts(),
                trustedAnswerPorts.getAssistanceActions(),
                new DeterministicDailyReviewPacingCommandFactory(nowEpochMillis));
    }

    public static StudentProblemOrganizationReviewAuthority.Proof
            reviewAndIssueStudentProblemOrganization(
                    StudentMistakeRuntimeCapabilities capabilities,
                    OrganizeStudentProblemCommand command,
                    ModelTaskSnapshot reviewedTask) {
        return CoreDataStudentMistakeOwnerBridge.reviewAndIssueOrganizationProof(
                capabilities,
                command,
                reviewedTask);
    }

    public static OrganizeStudentProblemCommand mapAndReviewStudentProblemOrganization(
            StudentMistakeRuntimeCapabilities capabilities,
            ReviewedStudentProblemOrganizationLocalContext local,
            ModelTaskSnapshot reviewedTask,
            List<KnowledgeReferenceProofAuthority.Proof> verifiedKnowledgeReferences) {
        return CoreDataStudentMistakeOwnerBridge.mapAndReviewOrganization(
                capabilities,
                local,
                reviewedTask,
                verifiedKnowledgeReferences);
    }

    public static ReviewedStudentProblemOrganizationReviewPort
            openReviewedStudentProblemOrganizationReviewPort(
                    StudentMistakeRuntimeCapabilities capabilities) {
        return (local, reviewedTask, verifiedKnowledgeReferences, continuation) ->
                mapAndReviewStudentProblemOrganization(
                        capabilities,
                        local,
                        reviewedTask,
                        verifiedKnowledgeReferences);
    }

    public static LearnerBoundStudentProblemOrganizationSourcePort
            openStudentProblemOrganizationSourcePort(
                    StudentMistakeRuntimeCapabilities capabilities) {
        return capabilities.problemOrganizationSources();
    }

    public static LearnerBoundVerifiedStudentProblemKnowledgeAttributionPort
            openVerifiedStudentProblemKnowledgeAttributionPort(
                    StudentMistakeRuntimeCapabilities capabilities,
                    HighSchoolKnowledgeCatalog knowledgeCatalog) {
        return VerifiedStudentKnowledgeAttributionOwnerAssembly.create(
                        capabilities.problemKnowledgeAttributions(),
                        knowledgeCatalog);
    }

    public static ProductionStudentCaptureOccurrenceOwner
            openProductionStudentCaptureOccurrenceOwner(
                    StudentMistakeRuntimeCapabilities capabilities) {
        return new ProductionStudentCaptureOccurrenceOwner() {
            @Override
            public String getLearnerId() {
                return capabilities.getLearnerId();
            }

            @Override
            public Object save(
                    SaveStudentCaptureOccurrenceCommand command,
                    ProductionStudentProblemIdentityEvidenceRequest identityEvidenceRequest,
                    Continuation<? super StudentCaptureOccurrenceReceipt> continuation) {
                String commandLearnerId =
                        command.getCapture()
                                .getTarget()
                                .getProblem()
                                .getRevision()
                                .getProblem()
                                .getLearnerId();
                if (!getLearnerId().equals(commandLearnerId)) {
                    throw new IllegalArgumentException(
                            "Capture occurrence crosses the student-owner learner scope");
                }
                if (identityEvidenceRequest
                        != ProductionStudentProblemIdentityEvidenceRequest
                                .ExactAssetSelectionOrUnresolved.INSTANCE) {
                    throw new IllegalArgumentException(
                            "Unsupported production problem identity evidence request");
                }
                return capabilities.identityEvidenceOwner()
                        .invokeSaveExactAssetSelectionOrUnresolved(command, continuation);
            }

            @Override
            public Object readPending(
                    ReadPendingStudentCaptureSaveHandoffsQuery query,
                    Continuation<? super List<StudentCaptureSaveHandoffRecord>> continuation) {
                return capabilities.captureHandoffs().readPending(query, continuation);
            }

            @Override
            public Object readByDraftIds(
                    Set<String> draftIds,
                    Continuation<? super List<StudentCaptureSaveHandoffRecord>> continuation) {
                return capabilities.captureHandoffs().readByDraftIds(draftIds, continuation);
            }

            @Override
            public Object readBySessionId(
                    String sessionId,
                    Continuation<? super StudentCaptureSaveHandoffRecord> continuation) {
                return capabilities.captureHandoffs().readBySessionId(sessionId, continuation);
            }

            @Override
            public Object acknowledge(
                    AcknowledgeStudentCaptureSaveHandoffCommand command,
                    Continuation<? super StudentCaptureSaveHandoffRecord> continuation) {
                return capabilities.captureHandoffs().acknowledge(command, continuation);
            }
        };
    }
}
