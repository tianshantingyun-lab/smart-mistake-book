package com.tingyun.smartmistakebook.core.student.mistake.database;

import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventPayload;
import com.tingyun.smartmistakebook.core.model.storage.StudentMistakeRelayMessage;
import com.tingyun.smartmistakebook.core.model.storage.StudentOutboxAuthenticityProof;
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind;
import java.util.Objects;

/**
 * Owner-issued, immutable view of one verified student outbox row.
 *
 * <p>The raw relay wrapper never appears in the public API. Its package-private carrier remains
 * available only to the student authority for re-verification and acknowledgement.
 */
public final class StudentOutboxDelivery {
    private final StudentMistakeRelayMessage issuedMessage;

    private StudentOutboxDelivery(StudentMistakeRelayMessage issuedMessage) {
        this.issuedMessage = Objects.requireNonNull(issuedMessage, "issuedMessage");
    }

    static StudentOutboxDelivery ownerIssued(StudentMistakeRelayMessage issuedMessage) {
        return new StudentOutboxDelivery(issuedMessage);
    }

    StudentMistakeRelayMessage issuedMessage() {
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

    StudentOutboxAuthenticityProof getAuthenticityProof() {
        return issuedMessage.getAuthenticityProof();
    }
}
