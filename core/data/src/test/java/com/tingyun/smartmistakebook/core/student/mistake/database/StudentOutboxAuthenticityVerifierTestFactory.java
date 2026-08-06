package com.tingyun.smartmistakebook.core.student.mistake.database;

import com.tingyun.smartmistakebook.core.model.storage.StudentMistakeRelayMessage;

/** Test-only same-package fixture; no equivalent factory is packaged in production. */
public final class StudentOutboxAuthenticityVerifierTestFactory {
    private StudentOutboxAuthenticityVerifierTestFactory() {}

    public static StudentOutboxAuthenticityVerifier acceptingOnlyTag(String acceptedTag) {
        return StudentOutboxAuthenticityVerifier.ownerIssued(
                new StudentOutboxAuthenticityVerificationOperation() {
                    @Override
                    public StudentOutboxVerificationReceipt verify(
                            StudentMistakeRelayMessage message) {
                        if (!acceptedTag.equals(message.getAuthenticityProof().getTagHex())) {
                            throw new SecurityException("Rejected test student-outbox proof");
                        }
                        return StudentOutboxVerificationReceipt.ownerIssued(
                                message,
                                message.getEnvelope().getSourceStoreGeneration(),
                                "test-relay-epoch",
                                message.getAuthenticityProof().getIssuerKeyId(),
                                message.getAuthenticityProof().getAlgorithmVersion());
                    }
                });
    }

    public static StudentOutboxDelivery delivery(StudentMistakeRelayMessage message) {
        return StudentOutboxDelivery.ownerIssued(message);
    }

    public static String envelopeFingerprint(VerifiedLearnerMasteryDelivery delivery) {
        return delivery.envelope().getCanonicalFingerprint();
    }
}
