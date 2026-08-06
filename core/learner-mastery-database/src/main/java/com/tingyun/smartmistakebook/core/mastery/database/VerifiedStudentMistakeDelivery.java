package com.tingyun.smartmistakebook.core.mastery.database;

import com.tingyun.smartmistakebook.core.model.CanonicalSha256;
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope;
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind;
import java.util.Objects;

/** Destination-owned authority value bound from a source-verified student delivery. */
public final class VerifiedStudentMistakeDelivery {
    private final CrossStoreEventEnvelope envelope;
    private final String learnerId;
    private final String sourceStoreGeneration;
    private final String relayEpoch;
    private final String issuerKeyId;
    private final String algorithmVersion;
    private final String proofCanonicalFingerprint;
    private final String verifiedEnvelopeCanonicalFingerprint;
    private final String verificationReceiptCanonicalFingerprint;

    VerifiedStudentMistakeDelivery(
            CrossStoreEventEnvelope envelope,
            String learnerId,
            String sourceStoreGeneration,
            String relayEpoch,
            String issuerKeyId,
            String algorithmVersion,
            String proofCanonicalFingerprint,
            String verifiedEnvelopeCanonicalFingerprint,
            String verificationReceiptCanonicalFingerprint) {
        this.envelope = Objects.requireNonNull(envelope, "envelope");
        this.learnerId = requireText(learnerId, "learner id");
        this.sourceStoreGeneration = requireText(sourceStoreGeneration, "source store generation");
        this.relayEpoch = requireText(relayEpoch, "relay epoch");
        this.issuerKeyId = requireText(issuerKeyId, "issuer key id");
        this.algorithmVersion = requireText(algorithmVersion, "algorithm version");
        this.proofCanonicalFingerprint =
                requireText(proofCanonicalFingerprint, "proof canonical fingerprint");
        this.verifiedEnvelopeCanonicalFingerprint =
                requireText(
                        verifiedEnvelopeCanonicalFingerprint,
                        "verified envelope canonical fingerprint");
        this.verificationReceiptCanonicalFingerprint =
                requireText(
                        verificationReceiptCanonicalFingerprint,
                        "verification receipt canonical fingerprint");
        String expectedReceiptFingerprint =
                new CanonicalSha256("student-outbox-verification-receipt-v1")
                        .field("learnerId", this.learnerId)
                        .field("sourceStoreGeneration", this.sourceStoreGeneration)
                        .field("relayEpoch", this.relayEpoch)
                        .field("issuerKeyId", this.issuerKeyId)
                        .field("algorithmVersion", this.algorithmVersion)
                        .field(
                                "envelopeCanonicalFingerprint",
                                this.verifiedEnvelopeCanonicalFingerprint)
                        .field("proofCanonicalFingerprint", this.proofCanonicalFingerprint)
                        .finish();
        if (envelope.getSourceStore() != StudyStoreKind.STUDENT_MISTAKES
                || envelope.getDestinationStore() != StudyStoreKind.LEARNER_MASTERY
                || !this.sourceStoreGeneration.equals(envelope.getSourceStoreGeneration())
                || !this.verifiedEnvelopeCanonicalFingerprint.equals(
                        envelope.getCanonicalFingerprint())
                || !this.verificationReceiptCanonicalFingerprint.equals(
                        expectedReceiptFingerprint)) {
            throw new SecurityException("Verified student delivery binding mismatch");
        }
    }

    CrossStoreEventEnvelope envelope() { return envelope; }
    String learnerId() { return learnerId; }
    String sourceStoreGeneration() { return sourceStoreGeneration; }
    String relayEpoch() { return relayEpoch; }
    String issuerKeyId() { return issuerKeyId; }
    String algorithmVersion() { return algorithmVersion; }
    String proofCanonicalFingerprint() { return proofCanonicalFingerprint; }
    String verifiedEnvelopeCanonicalFingerprint() {
        return verifiedEnvelopeCanonicalFingerprint;
    }
    String verificationReceiptCanonicalFingerprint() {
        return verificationReceiptCanonicalFingerprint;
    }

    public String getEventId() { return envelope.getEventId(); }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()
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
