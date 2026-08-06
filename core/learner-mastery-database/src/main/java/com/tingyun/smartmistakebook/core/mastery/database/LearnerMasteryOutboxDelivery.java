package com.tingyun.smartmistakebook.core.mastery.database;

import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventPayload;
import com.tingyun.smartmistakebook.core.model.storage.LearnerMasteryRelayMessage;
import com.tingyun.smartmistakebook.core.model.storage.MasteryOutboxAuthenticityProof;
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind;
import java.util.Objects;

/** Immutable public view of one learner-mastery outbox row without a raw relay accessor. */
public final class LearnerMasteryOutboxDelivery {
    private final LearnerMasteryRelayMessage issuedMessage;

    private LearnerMasteryOutboxDelivery(LearnerMasteryRelayMessage issuedMessage) {
        this.issuedMessage = Objects.requireNonNull(issuedMessage, "issuedMessage");
    }

    static LearnerMasteryOutboxDelivery ownerIssued(LearnerMasteryRelayMessage issuedMessage) {
        return new LearnerMasteryOutboxDelivery(issuedMessage);
    }

    LearnerMasteryRelayMessage issuedMessage() {
        return issuedMessage;
    }

    public String getEventId() {
        return issuedMessage.getEnvelope().getEventId();
    }

    public StudyStoreKind getSourceStore() {
        return issuedMessage.getEnvelope().getSourceStore();
    }

    public StudyStoreKind getDestinationStore() {
        return issuedMessage.getEnvelope().getDestinationStore();
    }

    public String getAggregateId() {
        return issuedMessage.getEnvelope().getAggregateId();
    }

    public long getAggregateVersion() {
        return issuedMessage.getEnvelope().getAggregateVersion();
    }

    public long getOccurredAtEpochMillis() {
        return issuedMessage.getEnvelope().getOccurredAtEpochMillis();
    }

    public String getIdempotencyKey() {
        return issuedMessage.getEnvelope().getIdempotencyKey();
    }

    public String getSourceStoreGeneration() {
        return issuedMessage.getEnvelope().getSourceStoreGeneration();
    }

    public CrossStoreEventPayload getPayload() {
        return issuedMessage.getEnvelope().getPayload();
    }

    public String getPayloadType() {
        return issuedMessage.getEnvelope().getPayloadType();
    }

    public int getPayloadVersion() {
        return issuedMessage.getEnvelope().getPayloadVersion();
    }

    public String getPayloadCanonicalFingerprint() {
        return issuedMessage.getEnvelope().getPayloadCanonicalFingerprint();
    }

    public String getEnvelopeCanonicalFingerprint() {
        return issuedMessage.getEnvelope().getCanonicalFingerprint();
    }

    MasteryOutboxAuthenticityProof getAuthenticityProof() {
        return issuedMessage.getAuthenticityProof();
    }
}
