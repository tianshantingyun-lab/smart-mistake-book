package com.tingyun.smartmistakebook.core.student.mistake.database;

import com.tingyun.smartmistakebook.core.model.CanonicalSha256;
import com.tingyun.smartmistakebook.core.model.storage.StudentMistakeRelayMessage;
import com.tingyun.smartmistakebook.core.model.storage.StudentOutboxAuthenticityProof;
import java.util.Objects;

/**
 * Opaque evidence that the student owner verified one exact outbox delivery.
 *
 * <p>The receipt exposes only routing epochs and canonical fingerprints. It contains no key,
 * response body, grading rule, or signing operation, and its private constructor prevents callers
 * from turning a structurally valid relay message into destination authority.
 */
final class StudentOutboxVerificationReceipt {
    private final String learnerId;
    private final String sourceStoreGeneration;
    private final String relayEpoch;
    private final String issuerKeyId;
    private final String algorithmVersion;
    private final String envelopeCanonicalFingerprint;
    private final String proofCanonicalFingerprint;
    private final String canonicalFingerprint;

    private StudentOutboxVerificationReceipt(
            StudentMistakeRelayMessage message,
            String sourceStoreGeneration,
            String relayEpoch,
            String issuerKeyId,
            String algorithmVersion) {
        Objects.requireNonNull(message, "message");
        StudentOutboxAuthenticityProof proof = message.getAuthenticityProof();
        this.learnerId = proof.getLearnerId();
        this.sourceStoreGeneration = requireText(sourceStoreGeneration, "source store generation");
        this.relayEpoch = requireText(relayEpoch, "relay epoch");
        this.issuerKeyId = requireText(issuerKeyId, "issuer key id");
        this.algorithmVersion = requireText(algorithmVersion, "algorithm version");
        this.envelopeCanonicalFingerprint = message.getEnvelope().getCanonicalFingerprint();
        this.proofCanonicalFingerprint = proof.getCanonicalFingerprint();
        if (!this.sourceStoreGeneration.equals(message.getEnvelope().getSourceStoreGeneration())
                || !this.issuerKeyId.equals(proof.getIssuerKeyId())
                || !this.algorithmVersion.equals(proof.getAlgorithmVersion())
                || !this.envelopeCanonicalFingerprint.equals(
                        proof.getEnvelopeCanonicalFingerprint())) {
            throw new SecurityException("Student outbox verification receipt scope mismatch");
        }
        this.canonicalFingerprint =
                new CanonicalSha256("student-outbox-verification-receipt-v1")
                        .field("learnerId", learnerId)
                        .field("sourceStoreGeneration", sourceStoreGeneration)
                        .field("relayEpoch", relayEpoch)
                        .field("issuerKeyId", issuerKeyId)
                        .field("algorithmVersion", algorithmVersion)
                        .field("envelopeCanonicalFingerprint", envelopeCanonicalFingerprint)
                        .field("proofCanonicalFingerprint", proofCanonicalFingerprint)
                        .finish();
    }

    static StudentOutboxVerificationReceipt ownerIssued(
            StudentMistakeRelayMessage message,
            String sourceStoreGeneration,
            String relayEpoch,
            String issuerKeyId,
            String algorithmVersion) {
        return new StudentOutboxVerificationReceipt(
                message,
                sourceStoreGeneration,
                relayEpoch,
                issuerKeyId,
                algorithmVersion);
    }

    String getLearnerId() {
        return learnerId;
    }

    String getSourceStoreGeneration() {
        return sourceStoreGeneration;
    }

    String getRelayEpoch() {
        return relayEpoch;
    }

    String getIssuerKeyId() {
        return issuerKeyId;
    }

    String getAlgorithmVersion() {
        return algorithmVersion;
    }

    String getEnvelopeCanonicalFingerprint() {
        return envelopeCanonicalFingerprint;
    }

    String getProofCanonicalFingerprint() {
        return proofCanonicalFingerprint;
    }

    String getCanonicalFingerprint() {
        return canonicalFingerprint;
    }

    private static String requireText(String value, String label) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.trim())
                || value.length() > 256
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value;
    }
}
