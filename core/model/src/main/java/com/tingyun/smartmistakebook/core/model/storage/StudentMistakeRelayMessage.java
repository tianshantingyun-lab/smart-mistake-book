package com.tingyun.smartmistakebook.core.model.storage;

import java.util.Objects;

/**
 * Validated transport value read from the student-mistake outbox.
 *
 * <p>This value carries no mutation authority. Only an owner-issued relay capability may deliver
 * or acknowledge it.
 */
public final class StudentMistakeRelayMessage {
    private final CrossStoreEventEnvelope envelope;
    private final StudentOutboxAuthenticityProof authenticityProof;

    private StudentMistakeRelayMessage(
            CrossStoreEventEnvelope envelope,
            StudentOutboxAuthenticityProof authenticityProof) {
        this.envelope = envelope;
        this.authenticityProof = authenticityProof;
    }

    /**
     * Packages an envelope and a structural proof read from the student owner boundary.
     *
     * <p>This factory does not verify the HMAC. Destinations must invoke their privately assembled
     * destination's owner-issued verify-only capability before reading the envelope as authority.
     */
    public static StudentMistakeRelayMessage fromUnverifiedEnvelopeAndProof(
            CrossStoreEventEnvelope envelope,
            StudentOutboxAuthenticityProof authenticityProof) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(authenticityProof, "authenticityProof");
        requireRoute(
                envelope,
                StudyStoreKind.STUDENT_MISTAKES,
                StudyStoreKind.LEARNER_MASTERY,
                "Student mistake relay message");
        if (!authenticityProof
                .getEnvelopeCanonicalFingerprint()
                .equals(envelope.getCanonicalFingerprint())) {
            throw new IllegalArgumentException(
                    "Student mistake relay proof does not bind the supplied envelope");
        }
        return new StudentMistakeRelayMessage(envelope, authenticityProof);
    }

    public CrossStoreEventEnvelope getEnvelope() {
        return envelope;
    }

    public StudentOutboxAuthenticityProof getAuthenticityProof() {
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
