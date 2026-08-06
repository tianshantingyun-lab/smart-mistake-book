package com.tingyun.smartmistakebook.core.student.mistake.database;

import android.content.Context;
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint;
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest;
import com.tingyun.smartmistakebook.core.model.ModelTaskCompletionValidator;
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint;
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest;
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot;
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus;
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationOutput;
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input;
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot;
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority;
import java.util.List;

/**
 * Package-private bridge consumed only by core:data code compiled into this exact package.
 *
 * <p>The repository architecture test forbids any other production split-package consumer.
 */
final class CoreDataStudentMistakeOwnerBridge {
    private CoreDataStudentMistakeOwnerBridge() {}

    static StudentMistakeRuntimeCapabilities openRuntime(
            Context context,
            String learnerId,
            KnowledgeReferenceProofAuthority.Verifier knowledgeReferenceVerifier) {
        return StudentMistakeRuntimeFactory.INSTANCE.open(
                context,
                learnerId,
                knowledgeReferenceVerifier,
                StudentMistakeOwnerKey.INSTANCE);
    }

    static StudentMistakeMigrationPort openMigration(Context context) {
        return StudentMistakeMigrationPortFactory.INSTANCE.open(
                context,
                StudentMistakeOwnerKey.INSTANCE);
    }

    static StudentMistakeCutoverControlPort openCutoverControl(Context context) {
        return StudentMistakeCutoverControlPortFactory.INSTANCE.open(
                context,
                StudentMistakeOwnerKey.INSTANCE);
    }

    static StudentCutoverDestinationAttestationPorts openCutoverDestinationAttestations(
            Context context) {
        return StudentCutoverDestinationAttestationPortFactory.INSTANCE.open(
                context,
                StudentMistakeOwnerKey.INSTANCE);
    }

    /**
     * Binds a core:data receipt only after core:data has verified that it is the exact
     * STUDENT_DOCUMENTS_IMPORTED receipt and legacy-prefix successor.
     */
    static StudentDocumentImportReceiptReference bindVerifiedDocumentImportReceipt(
            long cutoverGeneration,
            String legacyPrefixFingerprint,
            long migratedRecordCount,
            String sourceCheckpoint,
            String destinationFingerprint,
            String receiptFingerprint) {
        return StudentDocumentImportReceiptReferenceFactory.INSTANCE.bind(
                cutoverGeneration,
                legacyPrefixFingerprint,
                migratedRecordCount,
                sourceCheckpoint,
                destinationFingerprint,
                receiptFingerprint,
                StudentMistakeOwnerKey.INSTANCE);
    }

    static StudentProblemOrganizationReviewAuthority.Proof
            reviewAndIssueOrganizationProof(
            StudentMistakeRuntimeCapabilities capabilities,
            OrganizeStudentProblemCommand command,
            ModelTaskSnapshot reviewedTask) {
        if (!capabilities.getLearnerId().equals(
                command.getProblemRevision().getProblem().getLearnerId())) {
            throw new IllegalArgumentException(
                    "Organization review crosses the learner-bound runtime");
        }
        requireLocallyReviewedOrganization(command, reviewedTask);
        ReviewedStudentProblemOrganizationBinding mapping =
                ReviewedStudentProblemOrganizationMapping.requireExact(
                        command,
                        reviewedTask,
                        proof -> capabilities.verifiesKnowledgeReference(proof));
        StudentProblemOrganizationProvenance provenance = command.getProvenance();
        return capabilities.organizationReviewIssuer().issue(
                command.getProblemRevision().getProblem().getLearnerId(),
                command.getProblemRevision().getProblem().getProblemId(),
                command.getProblemRevision().getRevisionId(),
                command.getProblemRevision().getRevisionNumber(),
                command.getProblemRevision().getDocumentCanonicalFingerprint(),
                command.getRequestId(),
                command.getRequestCanonicalFingerprint(),
                provenance.getRequestVersion(),
                provenance.getModelProviderId(),
                provenance.getModelId(),
                provenance.getResultModelVersion(),
                provenance.getProviderConfigurationVersion(),
                provenance.getModelTaskSchemaVersion(),
                provenance.getOrganizationPlanSchemaVersion(),
                mapping.getReviewedOutputCanonicalFingerprint(),
                mapping.getMappingPolicyVersion(),
                mapping.getKnowledgeManifestFingerprint(),
                mapping.getKnowledgeActivationGeneration(),
                mapping.getFinalCommandCanonicalFingerprint(),
                provenance.getReviewSource().name(),
                provenance.getReviewVersion(),
                provenance.getReviewIssuerKeyId(),
                provenance.getReviewIssuerVersion(),
                provenance.getReviewIssuedAtEpochMillis(),
                provenance.getReviewExpiresAtEpochMillis());
    }

    static OrganizeStudentProblemCommand mapAndReviewOrganization(
            StudentMistakeRuntimeCapabilities capabilities,
            ReviewedStudentProblemOrganizationLocalContext local,
            ModelTaskSnapshot reviewedTask,
            List<KnowledgeReferenceProofAuthority.Proof> verifiedKnowledgeReferences) {
        OrganizeStudentProblemCommand command =
                ReviewedStudentProblemOrganizationMapping.mapUnsigned(
                        local,
                        reviewedTask,
                        verifiedKnowledgeReferences);
        return command.withVerifiedReview(
                reviewAndIssueOrganizationProof(capabilities, command, reviewedTask));
    }

    private static void requireLocallyReviewedOrganization(
            OrganizeStudentProblemCommand command,
            ModelTaskSnapshot reviewedTask) {
        if (reviewedTask.getStatus() != ModelTaskStatus.SUCCEEDED
                || !(reviewedTask.getRequest().getInput()
                        instanceof ProblemOrganizationV3Input input)
                || !(reviewedTask.getOutput()
                        instanceof ProblemOrganizationOutput output)) {
            throw new IllegalArgumentException(
                    "Organization proof requires a locally validated successful v3 task");
        }
        ModelTaskCompletionValidator.INSTANCE.requireValid(
                reviewedTask.getRequest(),
                output);
        ModelTaskRequest request = reviewedTask.getRequest();
        StudentProblemOrganizationProvenance provenance = command.getProvenance();
        ProviderCapabilitySnapshot provider = reviewedTask.getProvider();
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Organization review requires the exact provider capability snapshot");
        }
        ModelEgressManifest egressManifest = request.getEgressManifest();
        if (egressManifest == null) {
            throw new IllegalArgumentException(
                    "Organization review requires an exact model-egress manifest");
        }
        if (provenance.getReviewSource()
                        != StudentProblemOrganizationReviewSource.LOCAL_POLICY_ACCEPTED
                || !request.getRequestId().equals(command.getRequestId())
                || !ModelTaskFingerprint.INSTANCE.of(request)
                        .equals(command.getRequestCanonicalFingerprint())
                || request.getSchemaVersion() != provenance.getModelTaskSchemaVersion()
                || !input.getProblemId()
                        .equals(command.getProblemRevision().getProblem().getProblemId())
                || !input.getProblemRevisionId()
                        .equals(command.getProblemRevision().getRevisionId())
                || !input.getPracticeUnitId()
                        .equals(command.getProblemRevision().getProblem().getPracticeUnitId())
                || input.getSubject()
                        != command.getProblemRevision().getProblem().getSubject()
                || !CapturedQuestionDocumentFingerprint.INSTANCE.of(input.getCapturedDocument())
                        .equals(
                                command.getProblemRevision()
                                        .getDocumentCanonicalFingerprint())
                || !output.getProblemId().equals(input.getProblemId())
                || !output.getProblemRevisionId().equals(input.getProblemRevisionId())
                || !output.getPracticeUnitId().equals(input.getPracticeUnitId())
                || output.getPlan().getSchemaVersion()
                        != provenance.getOrganizationPlanSchemaVersion()
                || !output.getModelVersion().equals(provenance.getResultModelVersion())
                || !provider.getProviderId().equals(provenance.getModelProviderId())
                || !provider.getModelId().equals(provenance.getModelId())
                || !provider.getProviderConfigurationVersion()
                        .equals(provenance.getProviderConfigurationVersion())
                || !egressManifest.getProviderId().equals(provenance.getModelProviderId())
                || !egressManifest.getModelId().equals(provenance.getModelId())
                || !egressManifest.getProviderConfigurationVersion()
                        .equals(provenance.getProviderConfigurationVersion())
                || reviewedTask.getUpdatedAtEpochMillis()
                        > provenance.getReviewIssuedAtEpochMillis()
                || reviewedTask.getUpdatedAtEpochMillis()
                        < request.getOccurredAtEpochMillis()) {
            throw new IllegalArgumentException(
                    "Organization command does not match the locally reviewed task");
        }
    }
}
