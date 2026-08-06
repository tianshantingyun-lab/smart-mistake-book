package com.tingyun.smartmistakebook.core.student.mistake.database;

import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority;
import java.io.Closeable;
import java.io.IOException;

/**
 * Opaque owner session for one learner's student-mistake authority.
 *
 * <p>The Java private constructor is deliberate: Kotlin private constructors with default
 * arguments can expose a public synthetic JVM constructor. Only the package-private owner bridge
 * can obtain this capability bundle.
 */
public final class StudentMistakeRuntimeCapabilities implements Closeable {
    private final String learnerId;
    private final StudentMistakeStore businessStore;
    private final LearnerBoundStudentMistakeLibraryPort library;
    private final LearnerBoundStudentCaptureHandoffPort captureHandoffs;
    private final LearnerBoundStudentReviewSessionPort reviewSessions;
    private final LearnerBoundStudentTrustedReviewAnswerPort trustedReviewAnswers;
    private final LearnerBoundStudentTrustedSavedAnswerRulePort trustedSavedAnswerRules;
    private final LearnerBoundTutorInteractionAnswerCertificatePort
            tutorInteractionAnswerCertificates;
    private final LearnerBoundStudentProblemOrganizationSourcePort
            problemOrganizationSources;
    private final StudentProblemOrganizationPort problemOrganization;
    private final StudentProblemOrganizationReviewAuthority.Issuer
            organizationReviewIssuer;
    private final KnowledgeReferenceProofAuthority.Verifier knowledgeReferenceVerifier;
    private final StudentProblemIdentityEvidenceOwner identityEvidenceOwner;
    private final StudentMistakeRelayCapability relay;
    private final StudentOutboxAuthenticityVerifier outboxAuthenticityVerifier;

    private StudentMistakeRuntimeCapabilities(
            String learnerId,
            StudentMistakeStore businessStore,
            LearnerBoundStudentMistakeLibraryPort library,
            LearnerBoundStudentCaptureHandoffPort captureHandoffs,
            LearnerBoundStudentReviewSessionPort reviewSessions,
            LearnerBoundStudentTrustedReviewAnswerPort trustedReviewAnswers,
            LearnerBoundStudentTrustedSavedAnswerRulePort trustedSavedAnswerRules,
            LearnerBoundTutorInteractionAnswerCertificatePort
                    tutorInteractionAnswerCertificates,
            LearnerBoundStudentProblemOrganizationSourcePort problemOrganizationSources,
            StudentProblemOrganizationPort problemOrganization,
            StudentProblemOrganizationReviewAuthority.Issuer organizationReviewIssuer,
            KnowledgeReferenceProofAuthority.Verifier knowledgeReferenceVerifier,
            StudentProblemIdentityEvidenceOwner identityEvidenceOwner,
            StudentMistakeRelayCapability relay,
            StudentOutboxAuthenticityVerifier outboxAuthenticityVerifier) {
        if (!learnerId.equals(captureHandoffs.getLearnerId())
                || !learnerId.equals(problemOrganization.getLearnerId())
                || !learnerId.equals(trustedReviewAnswers.getLearnerId())
                || !learnerId.equals(trustedSavedAnswerRules.getLearnerId())
                || !learnerId.equals(tutorInteractionAnswerCertificates.getLearnerId())
                || !learnerId.equals(problemOrganizationSources.getLearnerId())
                || !learnerId.equals(identityEvidenceOwner.getLearnerId())
                || !learnerId.equals(relay.getLearnerId())) {
            throw new IllegalArgumentException(
                    "Student-mistake capabilities must share one learner scope");
        }
        this.learnerId = learnerId;
        this.businessStore = businessStore;
        this.library = library;
        this.captureHandoffs = captureHandoffs;
        this.reviewSessions = reviewSessions;
        this.trustedReviewAnswers = trustedReviewAnswers;
        this.trustedSavedAnswerRules = trustedSavedAnswerRules;
        this.tutorInteractionAnswerCertificates = tutorInteractionAnswerCertificates;
        this.problemOrganizationSources = problemOrganizationSources;
        this.problemOrganization = problemOrganization;
        this.organizationReviewIssuer = organizationReviewIssuer;
        this.knowledgeReferenceVerifier = knowledgeReferenceVerifier;
        this.identityEvidenceOwner = identityEvidenceOwner;
        this.relay = relay;
        this.outboxAuthenticityVerifier = outboxAuthenticityVerifier;
    }

    static StudentMistakeRuntimeCapabilities create(
            String learnerId,
            StudentMistakeStore store,
            LearnerBoundStudentMistakeLibraryPort library,
            LearnerBoundStudentCaptureHandoffPort captureHandoffs,
            LearnerBoundStudentReviewSessionPort reviewSessions,
            LearnerBoundStudentTrustedReviewAnswerPort trustedReviewAnswers,
            LearnerBoundStudentTrustedSavedAnswerRulePort trustedSavedAnswerRules,
            LearnerBoundTutorInteractionAnswerCertificatePort
                    tutorInteractionAnswerCertificates,
            LearnerBoundStudentProblemOrganizationSourcePort problemOrganizationSources,
            StudentProblemOrganizationPort problemOrganization,
            StudentProblemOrganizationReviewAuthority.Issuer organizationReviewIssuer,
            KnowledgeReferenceProofAuthority.Verifier knowledgeReferenceVerifier,
            StudentProblemIdentityEvidenceOwner identityEvidenceOwner,
            StudentMistakeRelayCapability relay,
            StudentOutboxAuthenticityVerifier outboxAuthenticityVerifier) {
        return new StudentMistakeRuntimeCapabilities(
                learnerId,
                store,
                library,
                captureHandoffs,
                reviewSessions,
                trustedReviewAnswers,
                trustedSavedAnswerRules,
                tutorInteractionAnswerCertificates,
                problemOrganizationSources,
                problemOrganization,
                organizationReviewIssuer,
                knowledgeReferenceVerifier,
                identityEvidenceOwner,
                relay,
                outboxAuthenticityVerifier);
    }

    public String getLearnerId() {
        return learnerId;
    }

    StudentMistakeStore businessStore() {
        return businessStore;
    }

    public LearnerBoundStudentMistakeLibraryPort getLibrary() {
        return library;
    }

    LearnerBoundStudentCaptureHandoffPort captureHandoffs() {
        return captureHandoffs;
    }

    public LearnerBoundStudentReviewSessionPort getReviewSessions() {
        return reviewSessions;
    }

    public LearnerBoundStudentTrustedReviewAnswerPort getTrustedReviewAnswers() {
        return trustedReviewAnswers;
    }

    public LearnerBoundStudentTrustedSavedAnswerRulePort getTrustedSavedAnswerRules() {
        return trustedSavedAnswerRules;
    }

    public LearnerBoundTutorInteractionAnswerCertificatePort
            getTutorInteractionAnswerCertificates() {
        return tutorInteractionAnswerCertificates;
    }

    LearnerBoundStudentProblemOrganizationSourcePort problemOrganizationSources() {
        return problemOrganizationSources;
    }

    LearnerBoundStudentProblemKnowledgeAttributionPort problemKnowledgeAttributions() {
        return problemOrganization;
    }

    public StudentProblemOrganizationPort getProblemOrganization() {
        return problemOrganization;
    }

    StudentProblemOrganizationReviewAuthority.Issuer organizationReviewIssuer() {
        return organizationReviewIssuer;
    }

    boolean verifiesKnowledgeReference(KnowledgeReferenceProofAuthority.Proof proof) {
        return knowledgeReferenceVerifier.verifies(proof);
    }

    StudentProblemIdentityEvidenceOwner identityEvidenceOwner() {
        return identityEvidenceOwner;
    }

    public StudentMistakeRelayCapability getRelay() {
        return relay;
    }

    StudentOutboxAuthenticityVerifier outboxAuthenticityVerifier() {
        return outboxAuthenticityVerifier;
    }

    @Override
    public void close() throws IOException {
        businessStore.close();
    }
}
