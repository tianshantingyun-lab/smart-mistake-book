package com.tingyun.smartmistakebook.core.mastery.database;

import com.tingyun.smartmistakebook.core.model.CanonicalSha256;
import com.tingyun.smartmistakebook.core.model.storage.LearnerMasteryRelayMessage;
import com.tingyun.smartmistakebook.core.model.storage.MasteryOutboxAuthenticityProof;
import java.util.Objects;

/** Opaque evidence that the mastery owner verified one exact outbox delivery. */
final class MasteryOutboxVerificationReceipt {
    private final String learnerId;
    private final String sourceStoreGeneration;
    private final String relayEpoch;
    private final String issuerKeyId;
    private final String algorithmVersion;
    private final String envelopeCanonicalFingerprint;
    private final String proofCanonicalFingerprint;
    private final String canonicalFingerprint;

    private MasteryOutboxVerificationReceipt(
            LearnerMasteryRelayMessage message,
            String sourceStoreGeneration,
            String relayEpoch,
            String issuerKeyId,
            String algorithmVersion) {
        Objects.requireNonNull(message, "message");
        MasteryOutboxAuthenticityProof proof = message.getAuthenticityProof();
        this.learnerId = proof.getLearnerId();
        this.sourceStoreGeneration = requireText(sourceStoreGeneration, "source generation");
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
            throw new SecurityException(
                    "Learner-mastery outbox verification receipt scope mismatch");
        }
        this.canonicalFingerprint =
                new CanonicalSha256("learner-mastery-outbox-verification-receipt-v1")
                        .field("learnerId", learnerId)
                        .field("sourceStoreGeneration", sourceStoreGeneration)
                        .field("relayEpoch", relayEpoch)
                        .field("issuerKeyId", issuerKeyId)
                        .field("algorithmVersion", algorithmVersion)
                        .field("envelopeCanonicalFingerprint", envelopeCanonicalFingerprint)
                        .field("proofCanonicalFingerprint", proofCanonicalFingerprint)
                        .finish();
    }

    static MasteryOutboxVerificationReceipt ownerIssued(
            LearnerMasteryRelayMessage message,
            String sourceStoreGeneration,
            String relayEpoch,
            String issuerKeyId,
            String algorithmVersion) {
        return new MasteryOutboxVerificationReceipt(
                message,
                sourceStoreGeneration,
                relayEpoch,
                issuerKeyId,
                algorithmVersion);
    }

    String getLearnerId() { return learnerId; }
    String getSourceStoreGeneration() { return sourceStoreGeneration; }
    String getRelayEpoch() { return relayEpoch; }
    String getIssuerKeyId() { return issuerKeyId; }
    String getAlgorithmVersion() { return algorithmVersion; }
    String getEnvelopeCanonicalFingerprint() { return envelopeCanonicalFingerprint; }
    String getProofCanonicalFingerprint() { return proofCanonicalFingerprint; }
    String getCanonicalFingerprint() { return canonicalFingerprint; }

    private static String requireText(String value, String label) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.trim())
                || value.length() > 256
                || containsIsoControl(value)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value;
    }

    private static boolean containsIsoControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
