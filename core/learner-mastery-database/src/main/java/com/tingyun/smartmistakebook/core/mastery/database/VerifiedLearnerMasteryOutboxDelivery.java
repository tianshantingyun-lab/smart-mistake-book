package com.tingyun.smartmistakebook.core.mastery.database;

import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope;
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventPayload;
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind;
import java.util.Objects;

/**
 * Source-owned proof that one learner-mastery outbox delivery passed verification.
 *
 * <p>The type carries only immutable event fields and canonical binding fingerprints. It exposes
 * neither the HMAC tag nor a raw relay or verification receipt.
 */
public final class VerifiedLearnerMasteryOutboxDelivery {
    private final String eventId;
    private final StudyStoreKind sourceStore;
    private final StudyStoreKind destinationStore;
    private final String aggregateId;
    private final long aggregateVersion;
    private final long occurredAtEpochMillis;
    private final String idempotencyKey;
    private final String sourceStoreGeneration;
    private final CrossStoreEventPayload payload;
    private final String payloadType;
    private final int payloadVersion;
    private final String payloadCanonicalFingerprint;
    private final String envelopeCanonicalFingerprint;
    private final String learnerId;
    private final String relayEpoch;
    private final String issuerKeyId;
    private final String algorithmVersion;
    private final String proofCanonicalFingerprint;
    private final String verificationReceiptCanonicalFingerprint;

    VerifiedLearnerMasteryOutboxDelivery(
            LearnerMasteryOutboxDelivery delivery,
            MasteryOutboxVerificationReceipt receipt) {
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(receipt, "receipt");
        eventId = delivery.getEventId();
        sourceStore = delivery.getSourceStore();
        destinationStore = delivery.getDestinationStore();
        aggregateId = delivery.getAggregateId();
        aggregateVersion = delivery.getAggregateVersion();
        occurredAtEpochMillis = delivery.getOccurredAtEpochMillis();
        idempotencyKey = delivery.getIdempotencyKey();
        sourceStoreGeneration = delivery.getSourceStoreGeneration();
        payload = Objects.requireNonNull(delivery.getPayload(), "payload");
        payloadType = delivery.getPayloadType();
        payloadVersion = delivery.getPayloadVersion();
        payloadCanonicalFingerprint = delivery.getPayloadCanonicalFingerprint();
        envelopeCanonicalFingerprint = delivery.getEnvelopeCanonicalFingerprint();
        learnerId = receipt.getLearnerId();
        relayEpoch = receipt.getRelayEpoch();
        issuerKeyId = receipt.getIssuerKeyId();
        algorithmVersion = receipt.getAlgorithmVersion();
        proofCanonicalFingerprint = receipt.getProofCanonicalFingerprint();
        verificationReceiptCanonicalFingerprint = receipt.getCanonicalFingerprint();

        CrossStoreEventEnvelope reconstructed =
                new CrossStoreEventEnvelope(
                        eventId,
                        sourceStore,
                        destinationStore,
                        aggregateId,
                        aggregateVersion,
                        occurredAtEpochMillis,
                        idempotencyKey,
                        sourceStoreGeneration,
                        payload,
                        payloadType,
                        payloadVersion,
                        payloadCanonicalFingerprint);
        if (sourceStore != StudyStoreKind.LEARNER_MASTERY
                || destinationStore != StudyStoreKind.STUDENT_MISTAKES
                || !sourceStoreGeneration.equals(receipt.getSourceStoreGeneration())
                || !envelopeCanonicalFingerprint.equals(reconstructed.getCanonicalFingerprint())
                || !envelopeCanonicalFingerprint.equals(
                        receipt.getEnvelopeCanonicalFingerprint())) {
            throw new SecurityException(
                    "Verified learner-mastery outbox delivery binding mismatch");
        }
    }

    public String getEventId() { return eventId; }
    public StudyStoreKind getSourceStore() { return sourceStore; }
    public StudyStoreKind getDestinationStore() { return destinationStore; }
    public String getAggregateId() { return aggregateId; }
    public long getAggregateVersion() { return aggregateVersion; }
    public long getOccurredAtEpochMillis() { return occurredAtEpochMillis; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getSourceStoreGeneration() { return sourceStoreGeneration; }
    public CrossStoreEventPayload getPayload() { return payload; }
    public String getPayloadType() { return payloadType; }
    public int getPayloadVersion() { return payloadVersion; }
    public String getPayloadCanonicalFingerprint() { return payloadCanonicalFingerprint; }
    public String getEnvelopeCanonicalFingerprint() { return envelopeCanonicalFingerprint; }
    public String getLearnerId() { return learnerId; }
    public String getRelayEpoch() { return relayEpoch; }
    public String getIssuerKeyId() { return issuerKeyId; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public String getProofCanonicalFingerprint() { return proofCanonicalFingerprint; }
    public String getVerificationReceiptCanonicalFingerprint() {
        return verificationReceiptCanonicalFingerprint;
    }
}
