package com.tingyun.smartmistakebook.core.student.mistake.database;

import com.tingyun.smartmistakebook.core.model.CanonicalSha256;
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope;
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind;
import java.util.Objects;

/** Destination-owned authority value bound from a source-verified mastery delivery. */
public final class VerifiedLearnerMasteryDelivery {
    private final CrossStoreEventEnvelope envelope;
    private final String learnerId;
    private final String sourceStoreGeneration;
    private final String relayEpoch;
    private final String issuerKeyId;
    private final String algorithmVersion;
    private final String proofCanonicalFingerprint;
    private final String envelopeCanonicalFingerprint;
    private final String verificationReceiptCanonicalFingerprint;

    VerifiedLearnerMasteryDelivery(
            CrossStoreEventEnvelope envelope,
            String learnerId,
            String sourceStoreGeneration,
            String relayEpoch,
            String issuerKeyId,
            String algorithmVersion,
            String proofCanonicalFingerprint,
            String envelopeCanonicalFingerprint,
            String verificationReceiptCanonicalFingerprint) {
        this.envelope = Objects.requireNonNull(envelope, "envelope");
        this.learnerId = requireText(learnerId, "learnerId");
        this.sourceStoreGeneration = requireText(sourceStoreGeneration, "sourceStoreGeneration");
        this.relayEpoch = requireText(relayEpoch, "relayEpoch");
        this.issuerKeyId = requireText(issuerKeyId, "issuerKeyId");
        this.algorithmVersion = requireText(algorithmVersion, "algorithmVersion");
        this.proofCanonicalFingerprint =
                requireText(proofCanonicalFingerprint, "proofCanonicalFingerprint");
        this.envelopeCanonicalFingerprint =
                requireText(envelopeCanonicalFingerprint, "envelopeCanonicalFingerprint");
        this.verificationReceiptCanonicalFingerprint =
                requireText(
                        verificationReceiptCanonicalFingerprint,
                        "verificationReceiptCanonicalFingerprint");
        String expectedReceiptFingerprint =
                new CanonicalSha256("learner-mastery-outbox-verification-receipt-v1")
                        .field("learnerId", this.learnerId)
                        .field("sourceStoreGeneration", this.sourceStoreGeneration)
                        .field("relayEpoch", this.relayEpoch)
                        .field("issuerKeyId", this.issuerKeyId)
                        .field("algorithmVersion", this.algorithmVersion)
                        .field("envelopeCanonicalFingerprint", this.envelopeCanonicalFingerprint)
                        .field("proofCanonicalFingerprint", this.proofCanonicalFingerprint)
                        .finish();
        if (envelope.getSourceStore() != StudyStoreKind.LEARNER_MASTERY
                || envelope.getDestinationStore() != StudyStoreKind.STUDENT_MISTAKES
                || !this.sourceStoreGeneration.equals(envelope.getSourceStoreGeneration())
                || !this.envelopeCanonicalFingerprint.equals(
                        envelope.getCanonicalFingerprint())
                || !this.verificationReceiptCanonicalFingerprint.equals(
                        expectedReceiptFingerprint)) {
            throw new SecurityException("Verified learner-mastery delivery scope mismatch");
        }
    }

    CrossStoreEventEnvelope envelope() { return envelope; }
    String learnerId() { return learnerId; }
    String sourceStoreGeneration() { return sourceStoreGeneration; }
    String relayEpoch() { return relayEpoch; }
    String issuerKeyId() { return issuerKeyId; }
    String algorithmVersion() { return algorithmVersion; }
    String proofCanonicalFingerprint() { return proofCanonicalFingerprint; }
    String envelopeCanonicalFingerprint() { return envelopeCanonicalFingerprint; }
    String verificationReceiptCanonicalFingerprint() {
        return verificationReceiptCanonicalFingerprint;
    }

    public String getEventId() { return envelope.getEventId(); }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()
                || !value.equals(value.trim())
                || value.length() > 256
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value;
    }
}
