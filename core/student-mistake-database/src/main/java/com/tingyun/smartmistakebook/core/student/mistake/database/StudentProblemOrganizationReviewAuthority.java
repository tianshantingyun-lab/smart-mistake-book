package com.tingyun.smartmistakebook.core.student.mistake.database;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Process-local owner authority proving that one exact organization payload passed local review.
 *
 * <p>A proof's public fields are audit data, not authority. Acceptance also requires the private
 * in-process seal held by the matching verifier.
 */
public final class StudentProblemOrganizationReviewAuthority {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final Object authoritySeal = new Object();
    private final Issuer issuer;
    private final Verifier verifier = new Verifier(authoritySeal);

    private StudentProblemOrganizationReviewAuthority(
            String issuerKeyId,
            String issuerVersion) {
        this.issuer = new Issuer(
                requireText(issuerKeyId, "Review issuer key id"),
                requireText(issuerVersion, "Review issuer version"),
                authoritySeal);
    }

    public static StudentProblemOrganizationReviewAuthority create(
            String issuerKeyId,
            String issuerVersion) {
        return new StudentProblemOrganizationReviewAuthority(issuerKeyId, issuerVersion);
    }

    Issuer getIssuer() {
        return issuer;
    }

    public Verifier getVerifier() {
        return verifier;
    }

    /** Owner-only capability retained inside the student-mistake runtime. */
    public static final class Issuer {
        private final String issuerKeyId;
        private final String issuerVersion;
        private final Object authoritySeal;

        private Issuer(
                String issuerKeyId,
                String issuerVersion,
                Object authoritySeal) {
            this.issuerKeyId = issuerKeyId;
            this.issuerVersion = issuerVersion;
            this.authoritySeal = authoritySeal;
        }

        Proof issue(
                String learnerId,
                String problemId,
                String basisRevisionId,
                int basisRevisionNumber,
                String basisDocumentCanonicalFingerprint,
                String requestId,
                String requestCanonicalFingerprint,
                int requestVersion,
                String modelProviderId,
                String modelId,
                String modelVersion,
                String providerConfigurationVersion,
                int modelTaskSchemaVersion,
                int organizationPlanSchemaVersion,
                String reviewedOutputCanonicalFingerprint,
                String mappingPolicyVersion,
                String knowledgeManifestFingerprint,
                long knowledgeActivationGeneration,
                String finalCommandCanonicalFingerprint,
                String reviewSource,
                String reviewVersion,
                String claimedIssuerKeyId,
                String claimedIssuerVersion,
                long issuedAtEpochMillis,
                long expiresAtEpochMillis) {
            if (!issuerKeyId.equals(claimedIssuerKeyId)
                    || !issuerVersion.equals(claimedIssuerVersion)) {
                throw new IllegalArgumentException(
                        "Review issuer identity does not match the owner runtime");
            }
            return new Proof(
                    requireText(learnerId, "Reviewed learner id"),
                    requireText(problemId, "Reviewed problem id"),
                    requireText(basisRevisionId, "Reviewed basis revision id"),
                    requirePositive(basisRevisionNumber, "Reviewed basis revision number"),
                    requireSha256(
                            basisDocumentCanonicalFingerprint,
                            "Reviewed document fingerprint"),
                    requireText(requestId, "Reviewed request id"),
                    requireSha256(
                            requestCanonicalFingerprint,
                            "Reviewed request fingerprint"),
                    requirePositive(requestVersion, "Reviewed request version"),
                    requireText(modelProviderId, "Reviewed model provider id"),
                    requireText(modelId, "Reviewed model id"),
                    requireText(modelVersion, "Reviewed model version"),
                    requireText(
                            providerConfigurationVersion,
                            "Reviewed provider configuration version"),
                    requirePositive(
                            modelTaskSchemaVersion,
                            "Reviewed model-task schema version"),
                    requirePositive(
                            organizationPlanSchemaVersion,
                            "Reviewed organization-plan schema version"),
                    requireSha256(
                            reviewedOutputCanonicalFingerprint,
                            "Reviewed organization output fingerprint"),
                    requireText(mappingPolicyVersion, "Organization mapping policy version"),
                    requireSha256(
                            knowledgeManifestFingerprint,
                            "Reviewed knowledge manifest fingerprint"),
                    requirePositive(
                            knowledgeActivationGeneration,
                            "Reviewed knowledge activation generation"),
                    requireSha256(
                            finalCommandCanonicalFingerprint,
                            "Reviewed final command fingerprint"),
                    requireText(reviewSource, "Review source"),
                    requireText(reviewVersion, "Review version"),
                    issuerKeyId,
                    issuerVersion,
                    requireNonNegative(issuedAtEpochMillis, "Review issue time"),
                    requireExpiry(issuedAtEpochMillis, expiresAtEpochMillis),
                    authoritySeal);
        }
    }

    /** Store-side capability bound to the same private owner seal. */
    public static final class Verifier {
        private final Object authoritySeal;

        private Verifier(Object authoritySeal) {
            this.authoritySeal = authoritySeal;
        }

        public boolean verifies(Proof proof) {
            return proof != null && proof.authoritySeal == authoritySeal;
        }
    }

    /** Immutable attestation. It has no public constructor or copy/rewriting API. */
    public static final class Proof {
        private final String learnerId;
        private final String problemId;
        private final String basisRevisionId;
        private final int basisRevisionNumber;
        private final String basisDocumentCanonicalFingerprint;
        private final String requestId;
        private final String requestCanonicalFingerprint;
        private final int requestVersion;
        private final String modelProviderId;
        private final String modelId;
        private final String modelVersion;
        private final String providerConfigurationVersion;
        private final int modelTaskSchemaVersion;
        private final int organizationPlanSchemaVersion;
        private final String reviewedOutputCanonicalFingerprint;
        private final String mappingPolicyVersion;
        private final String knowledgeManifestFingerprint;
        private final long knowledgeActivationGeneration;
        private final String finalCommandCanonicalFingerprint;
        private final String reviewSource;
        private final String reviewVersion;
        private final String issuerKeyId;
        private final String issuerVersion;
        private final long issuedAtEpochMillis;
        private final long expiresAtEpochMillis;
        private final Object authoritySeal;

        private Proof(
                String learnerId,
                String problemId,
                String basisRevisionId,
                int basisRevisionNumber,
                String basisDocumentCanonicalFingerprint,
                String requestId,
                String requestCanonicalFingerprint,
                int requestVersion,
                String modelProviderId,
                String modelId,
                String modelVersion,
                String providerConfigurationVersion,
                int modelTaskSchemaVersion,
                int organizationPlanSchemaVersion,
                String reviewedOutputCanonicalFingerprint,
                String mappingPolicyVersion,
                String knowledgeManifestFingerprint,
                long knowledgeActivationGeneration,
                String finalCommandCanonicalFingerprint,
                String reviewSource,
                String reviewVersion,
                String issuerKeyId,
                String issuerVersion,
                long issuedAtEpochMillis,
                long expiresAtEpochMillis,
                Object authoritySeal) {
            this.learnerId = learnerId;
            this.problemId = problemId;
            this.basisRevisionId = basisRevisionId;
            this.basisRevisionNumber = basisRevisionNumber;
            this.basisDocumentCanonicalFingerprint = basisDocumentCanonicalFingerprint;
            this.requestId = requestId;
            this.requestCanonicalFingerprint = requestCanonicalFingerprint;
            this.requestVersion = requestVersion;
            this.modelProviderId = modelProviderId;
            this.modelId = modelId;
            this.modelVersion = modelVersion;
            this.providerConfigurationVersion = providerConfigurationVersion;
            this.modelTaskSchemaVersion = modelTaskSchemaVersion;
            this.organizationPlanSchemaVersion = organizationPlanSchemaVersion;
            this.reviewedOutputCanonicalFingerprint = reviewedOutputCanonicalFingerprint;
            this.mappingPolicyVersion = mappingPolicyVersion;
            this.knowledgeManifestFingerprint = knowledgeManifestFingerprint;
            this.knowledgeActivationGeneration = knowledgeActivationGeneration;
            this.finalCommandCanonicalFingerprint = finalCommandCanonicalFingerprint;
            this.reviewSource = reviewSource;
            this.reviewVersion = reviewVersion;
            this.issuerKeyId = issuerKeyId;
            this.issuerVersion = issuerVersion;
            this.issuedAtEpochMillis = issuedAtEpochMillis;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
            this.authoritySeal = Objects.requireNonNull(authoritySeal, "Authority seal is required");
        }

        public String getLearnerId() {
            return learnerId;
        }

        public String getProblemId() {
            return problemId;
        }

        public String getBasisRevisionId() {
            return basisRevisionId;
        }

        public int getBasisRevisionNumber() {
            return basisRevisionNumber;
        }

        public String getBasisDocumentCanonicalFingerprint() {
            return basisDocumentCanonicalFingerprint;
        }

        public String getRequestId() {
            return requestId;
        }

        public String getRequestCanonicalFingerprint() {
            return requestCanonicalFingerprint;
        }

        public int getRequestVersion() {
            return requestVersion;
        }

        public String getModelProviderId() {
            return modelProviderId;
        }

        public String getModelId() {
            return modelId;
        }

        public String getModelVersion() {
            return modelVersion;
        }

        public String getProviderConfigurationVersion() {
            return providerConfigurationVersion;
        }

        public int getModelTaskSchemaVersion() {
            return modelTaskSchemaVersion;
        }

        public int getOrganizationPlanSchemaVersion() {
            return organizationPlanSchemaVersion;
        }

        public String getReviewedOutputCanonicalFingerprint() {
            return reviewedOutputCanonicalFingerprint;
        }

        public String getMappingPolicyVersion() {
            return mappingPolicyVersion;
        }

        public String getKnowledgeManifestFingerprint() {
            return knowledgeManifestFingerprint;
        }

        public long getKnowledgeActivationGeneration() {
            return knowledgeActivationGeneration;
        }

        public String getFinalCommandCanonicalFingerprint() {
            return finalCommandCanonicalFingerprint;
        }

        /** Compatibility accessor for callers compiled against the first proof shape. */
        public String getPayloadCanonicalFingerprint() {
            return finalCommandCanonicalFingerprint;
        }

        public String getReviewSource() {
            return reviewSource;
        }

        public String getReviewVersion() {
            return reviewVersion;
        }

        public String getIssuerKeyId() {
            return issuerKeyId;
        }

        public String getIssuerVersion() {
            return issuerVersion;
        }

        public long getIssuedAtEpochMillis() {
            return issuedAtEpochMillis;
        }

        public long getExpiresAtEpochMillis() {
            return expiresAtEpochMillis;
        }
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label + " is required");
        if (value.isBlank()
                || !value.equals(value.trim())
                || value.length() > 256
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    label + " must be trimmed, bounded, and non-blank");
        }
        return value;
    }

    private static String requireSha256(String value, String label) {
        Objects.requireNonNull(value, label + " is required");
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be lowercase SHA-256");
        }
        return value;
    }

    private static int requirePositive(int value, String label) {
        if (value <= 0) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return value;
    }

    private static long requirePositive(long value, String label) {
        if (value <= 0L) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return value;
    }

    private static long requireNonNegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(label + " must not be negative");
        }
        return value;
    }

    private static long requireExpiry(long issuedAtEpochMillis, long expiresAtEpochMillis) {
        if (expiresAtEpochMillis <= issuedAtEpochMillis) {
            throw new IllegalArgumentException("Review proof expiry must follow issue time");
        }
        return expiresAtEpochMillis;
    }
}
