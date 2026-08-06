package com.tingyun.smartmistakebook.core.model.storage;

import java.util.Objects;

/**
 * Validated transport value read from the learner-mastery outbox.
 *
 * <p>This value carries no mutation authority. Only an owner-issued relay capability may deliver
 * or acknowledge it.
 */
public final class LearnerMasteryRelayMessage {
    private final CrossStoreEventEnvelope envelope;
    private final MasteryOutboxAuthenticityProof authenticityProof;

    private LearnerMasteryRelayMessage(
            CrossStoreEventEnvelope envelope,
            MasteryOutboxAuthenticityProof authenticityProof) {
        this.envelope = envelope;
        this.authenticityProof = authenticityProof;
    }

    public static LearnerMasteryRelayMessage fromUnverifiedEnvelopeAndProof(
            CrossStoreEventEnvelope envelope,
            MasteryOutboxAuthenticityProof authenticityProof) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(authenticityProof, "authenticityProof");
        requireRoute(
                envelope,
                StudyStoreKind.LEARNER_MASTERY,
                StudyStoreKind.STUDENT_MISTAKES,
                "Learner mastery relay message");
        if (!envelope.getCanonicalFingerprint().equals(
                authenticityProof.getEnvelopeCanonicalFingerprint())) {
            throw new IllegalArgumentException(
                    "Learner-mastery proof does not bind the supplied envelope");
        }
        return new LearnerMasteryRelayMessage(envelope, authenticityProof);
    }

    public CrossStoreEventEnvelope getEnvelope() {
        return envelope;
    }

    public MasteryOutboxAuthenticityProof getAuthenticityProof() {
        return authenticityProof;
    }

    private static void requireRoute(
            CrossStoreEventEnvelope envelope,
            StudyStoreKind source,
            StudyStoreKind destination,
            String label) {
        if (envelope.getSourceStore() != source) {
            throw new IllegalArgumentException(label + " source must be " + source.name());
        }
        if (envelope.getDestinationStore() != destination) {
            throw new IllegalArgumentException(
                    label + " destination must be " + destination.name());
        }
    }
}
