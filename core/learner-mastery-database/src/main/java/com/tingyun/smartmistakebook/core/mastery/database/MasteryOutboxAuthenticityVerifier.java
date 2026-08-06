package com.tingyun.smartmistakebook.core.mastery.database;

import com.tingyun.smartmistakebook.core.model.storage.LearnerMasteryRelayMessage;
import java.lang.reflect.Modifier;
import java.util.Objects;

/** Final verify-only capability issued by the learner-mastery database owner. */
public final class MasteryOutboxAuthenticityVerifier {
    private final MasteryOutboxAuthenticityVerificationOperation operation;

    private MasteryOutboxAuthenticityVerifier(
            MasteryOutboxAuthenticityVerificationOperation operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    static MasteryOutboxAuthenticityVerifier ownerIssued(
            MasteryOutboxAuthenticityVerificationOperation operation) {
        if (Modifier.isPublic(operation.getClass().getModifiers())) {
            throw new IllegalArgumentException(
                    "Learner-mastery verification operation must remain owner-private");
        }
        return new MasteryOutboxAuthenticityVerifier(operation);
    }

    /** Throws unless this exact owner-issued delivery still carries an authentic proof. */
    public void requireAuthentic(LearnerMasteryOutboxDelivery delivery) {
        verifyDelivery(delivery);
    }

    /** Owner-package bridge used to mint a typed, source-verified delivery. */
    MasteryOutboxVerificationReceipt verifyDelivery(
            LearnerMasteryOutboxDelivery delivery) {
        return operation.verify(
                Objects.requireNonNull(delivery, "delivery").issuedMessage());
    }

    void requireAuthentic(LearnerMasteryRelayMessage message) {
        operation.verify(Objects.requireNonNull(message, "message"));
    }
}

interface MasteryOutboxAuthenticityVerificationOperation {
    MasteryOutboxVerificationReceipt verify(LearnerMasteryRelayMessage message);
}
