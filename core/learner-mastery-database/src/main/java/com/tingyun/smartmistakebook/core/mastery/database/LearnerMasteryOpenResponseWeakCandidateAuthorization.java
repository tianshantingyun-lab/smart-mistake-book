package com.tingyun.smartmistakebook.core.mastery.database;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-local authorization issued only for a registered learner-database owner.
 *
 * <p>There is deliberately no public constructor or factory. The opaque issuer seal is verified
 * by the Java-private database issuer before this object can be registered.
 */
public final class LearnerMasteryOpenResponseWeakCandidateAuthorization {
    final String learnerId;
    final String scopeFingerprint;

    private final RoomOpenResponseWeakCandidateOwner owner;
    private final Object ownerSeal;
    private final LearnerMasteryOpenResponseScopeLease scopeLease;
    private final long expectedScopeEpoch;
    private final AtomicBoolean consumed = new AtomicBoolean(false);
    private final AtomicBoolean finalCheckCompleted = new AtomicBoolean(false);

    LearnerMasteryOpenResponseWeakCandidateAuthorization(
            Object ownerSeal,
            RoomOpenResponseWeakCandidateOwner owner,
            String learnerId,
            String scopeFingerprint,
            LearnerMasteryOpenResponseScopeLease scopeLease,
            long expectedScopeEpoch) {
        CoreDataLearnerMasteryOwnerBridge.requireOpenResponseOwnerSeal(ownerSeal);
        this.ownerSeal = ownerSeal;
        this.owner = Objects.requireNonNull(owner, "owner");
        this.learnerId = requireText(learnerId, "learnerId");
        this.scopeFingerprint = requireText(scopeFingerprint, "scopeFingerprint");
        this.scopeLease = Objects.requireNonNull(scopeLease, "scopeLease");
        if (expectedScopeEpoch < 0L) {
            throw new IllegalArgumentException("Open-response scope epoch must not be negative");
        }
        this.expectedScopeEpoch = expectedScopeEpoch;
    }

    boolean belongsTo(RoomOpenResponseWeakCandidateOwner expectedOwner) {
        return owner == expectedOwner
                && CoreDataLearnerMasteryOwnerBridge.isIssuedOpenResponseAuthorization(
                        this, expectedOwner, ownerSeal);
    }

    void authorizeTransactionStart(
            RoomOpenResponseWeakCandidateOwner expectedOwner,
            String expectedLearnerId,
            String expectedScopeFingerprint) {
        requireBinding(expectedOwner, expectedLearnerId, expectedScopeFingerprint);
        if (!consumed.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "Open-response authorization has already been consumed");
        }
        requireCurrentEpoch();
    }

    void authorizeFinalInsert(
            RoomOpenResponseWeakCandidateOwner expectedOwner,
            String expectedLearnerId,
            String expectedScopeFingerprint) {
        requireBinding(expectedOwner, expectedLearnerId, expectedScopeFingerprint);
        if (!consumed.get()) {
            throw new IllegalStateException(
                    "Open-response authorization was not checked at transaction start");
        }
        if (!finalCheckCompleted.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "Open-response authorization final check was already completed");
        }
        requireCurrentEpoch();
    }

    void revoke() {
        CoreDataLearnerMasteryOwnerBridge.revokeOpenResponseAuthorization(
                this, owner, ownerSeal);
    }

    private void requireBinding(
            RoomOpenResponseWeakCandidateOwner expectedOwner,
            String expectedLearnerId,
            String expectedScopeFingerprint) {
        if (owner != expectedOwner
                || !learnerId.equals(expectedLearnerId)
                || !scopeFingerprint.equals(expectedScopeFingerprint)
                || !CoreDataLearnerMasteryOwnerBridge.isIssuedOpenResponseAuthorization(
                        this, expectedOwner, ownerSeal)) {
            throw new IllegalStateException(
                    "Open-response authorization is outside the requested owner scope");
        }
    }

    private void requireCurrentEpoch() {
        long currentEpoch = scopeLease.requireCurrentEpoch();
        if (currentEpoch < 0L || currentEpoch != expectedScopeEpoch) {
            throw new IllegalStateException("Open-response tutor scope lease is stale");
        }
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
