package com.tingyun.smartmistakebook.core.student.mistake.database;

import com.tingyun.smartmistakebook.core.model.storage.StudentMistakeRelayMessage;
import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * Final verify-only capability issued by the student-mistake owner.
 *
 * <p>Callers can verify an opaque relay message but cannot construct an implementation, replace
 * verification with a no-op, sign an envelope, inspect a key, or obtain comparison details.
 */
public final class StudentOutboxAuthenticityVerifier {
    private final StudentOutboxAuthenticityVerificationOperation operation;

    private StudentOutboxAuthenticityVerifier(
            StudentOutboxAuthenticityVerificationOperation operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    static StudentOutboxAuthenticityVerifier ownerIssued(
            StudentOutboxAuthenticityVerificationOperation operation) {
        Class<?> implementation = operation.getClass();
        if (Modifier.isPublic(implementation.getModifiers())) {
            throw new IllegalArgumentException(
                    "Student-outbox verification operation must remain owner-private");
        }
        return new StudentOutboxAuthenticityVerifier(operation);
    }

    /** Throws {@link SecurityException} unless the owner-issued delivery still carries its tag. */
    public void requireAuthentic(StudentOutboxDelivery delivery) {
        verifyDelivery(delivery);
    }

    /** Owner-package bridge used to mint a typed, source-verified delivery. */
    StudentOutboxVerificationReceipt verifyDelivery(StudentOutboxDelivery delivery) {
        return operation.verify(Objects.requireNonNull(delivery, "delivery").issuedMessage());
    }

    /** Package-private raw verification used only while constructing an owner delivery. */
    void requireAuthentic(StudentMistakeRelayMessage message) {
        operation.verify(Objects.requireNonNull(message, "message"));
    }
}

/** Package-private operation; only the student owner package may bind cryptographic verification. */
interface StudentOutboxAuthenticityVerificationOperation {
    StudentOutboxVerificationReceipt verify(StudentMistakeRelayMessage message);
}
