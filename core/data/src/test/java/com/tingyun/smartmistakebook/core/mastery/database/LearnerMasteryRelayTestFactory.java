package com.tingyun.smartmistakebook.core.mastery.database;

import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope;
import com.tingyun.smartmistakebook.core.model.storage.LearnerMasteryRelayMessage;
import com.tingyun.smartmistakebook.core.model.storage.MasteryOutboxAuthenticityProof;

/** Test-only package bridge for opaque relay carriers. */
public final class LearnerMasteryRelayTestFactory {
    private LearnerMasteryRelayTestFactory() {}

    public static String envelopeFingerprint(VerifiedStudentMistakeDelivery delivery) {
        return delivery.envelope().getCanonicalFingerprint();
    }

    public static LearnerMasteryOutboxDelivery delivery(CrossStoreEventEnvelope envelope) {
        return LearnerMasteryOutboxDelivery.ownerIssued(
                LearnerMasteryRelayMessage.fromUnverifiedEnvelopeAndProof(
                        envelope,
                        new MasteryOutboxAuthenticityProof(
                                MasteryOutboxAuthenticityProof.PROTOCOL_VERSION,
                                MasteryOutboxAuthenticityProof.ALGORITHM_VERSION,
                                "test-mastery-outbox-key",
                                "local-default",
                                envelope.getCanonicalFingerprint(),
                                "a".repeat(64))));
    }

    public static MasteryOutboxAuthenticityVerifier acceptingOnlyTag(String acceptedTag) {
        return MasteryOutboxAuthenticityVerifier.ownerIssued(
                message -> {
                    if (!acceptedTag.equals(message.getAuthenticityProof().getTagHex())) {
                        throw new SecurityException("Rejected test mastery-outbox proof");
                    }
                    return MasteryOutboxVerificationReceipt.ownerIssued(
                            message,
                            message.getEnvelope().getSourceStoreGeneration(),
                            "test-mastery-relay-epoch",
                            message.getAuthenticityProof().getIssuerKeyId(),
                            message.getAuthenticityProof().getAlgorithmVersion());
                });
    }
}
