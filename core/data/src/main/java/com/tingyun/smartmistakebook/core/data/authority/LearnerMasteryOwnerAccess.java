package com.tingyun.smartmistakebook.core.mastery.database;

import android.content.Context;
import com.tingyun.smartmistakebook.core.model.NoopProjectionWorkloadGate;
import com.tingyun.smartmistakebook.core.model.ProjectionWorkloadGate;
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope;
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority;
import com.tingyun.smartmistakebook.core.student.mistake.database.VerifiedStudentOutboxDelivery;

/**
 * The single audited source bridge into the learner-mastery owner package.
 *
 * <p>This Java shell never handles key material or raw authenticity proofs. It exposes only
 * owner-issued runtime or purpose-specific ports; verification and state mutation remain inside
 * the learner-mastery authority.
 */
public final class LearnerMasteryOwnerAccess {
    private LearnerMasteryOwnerAccess() {}

    public static LearnerMasteryRuntimeCapabilities openLearnerMasteryOwnerCapabilities(
            Context context,
            String learnerId,
            KnowledgeReferenceProofAuthority.Verifier knowledgeReferenceVerifier) {
        return openLearnerMasteryOwnerCapabilities(
                context,
                learnerId,
                knowledgeReferenceVerifier,
                NoopProjectionWorkloadGate.INSTANCE);
    }

    public static LearnerMasteryRuntimeCapabilities openLearnerMasteryOwnerCapabilities(
            Context context,
            String learnerId,
            KnowledgeReferenceProofAuthority.Verifier knowledgeReferenceVerifier,
            ProjectionWorkloadGate projectionWorkloadGate) {
        return CoreDataLearnerMasteryOwnerBridge.open(
                context,
                learnerId,
                knowledgeReferenceVerifier,
                projectionWorkloadGate);
    }

    public static LearnerMasteryRelayCapability openLearnerMasteryRelayCapability(
            LearnerMasteryRuntimeCapabilities runtimeOwner) {
        return CoreDataLearnerMasteryOwnerBridge.relayHandoff(runtimeOwner);
    }

    public static MasteryOutboxAuthenticityVerifier openMasteryOutboxAuthenticityVerifier(
            LearnerMasteryRuntimeCapabilities runtimeOwner) {
        return CoreDataLearnerMasteryOwnerBridge
                .outboxAuthenticityVerifierHandoff(runtimeOwner);
    }

    public static VerifiedLearnerMasteryOutboxDelivery verifyLearnerMasteryOutboxDelivery(
            LearnerMasteryOutboxDelivery delivery,
            MasteryOutboxAuthenticityVerifier verifier) {
        MasteryOutboxVerificationReceipt receipt = verifier.verifyDelivery(delivery);
        return new VerifiedLearnerMasteryOutboxDelivery(delivery, receipt);
    }

    public static VerifiedStudentMistakeDelivery bindVerifiedStudentOutboxDelivery(
            VerifiedStudentOutboxDelivery delivery) {
        CrossStoreEventEnvelope envelope =
                new CrossStoreEventEnvelope(
                        delivery.getEventId(),
                        delivery.getSourceStore(),
                        delivery.getDestinationStore(),
                        delivery.getAggregateId(),
                        delivery.getAggregateVersion(),
                        delivery.getOccurredAtEpochMillis(),
                        delivery.getIdempotencyKey(),
                        delivery.getSourceStoreGeneration(),
                        delivery.getPayload(),
                        delivery.getPayloadType(),
                        delivery.getPayloadVersion(),
                        delivery.getPayloadCanonicalFingerprint());
        if (!envelope.getCanonicalFingerprint()
                .equals(delivery.getEnvelopeCanonicalFingerprint())) {
            throw new SecurityException(
                    "Source-verified student delivery changed during binding");
        }
        return new VerifiedStudentMistakeDelivery(
                envelope,
                delivery.getLearnerId(),
                delivery.getSourceStoreGeneration(),
                delivery.getRelayEpoch(),
                delivery.getIssuerKeyId(),
                delivery.getAlgorithmVersion(),
                delivery.getProofCanonicalFingerprint(),
                delivery.getEnvelopeCanonicalFingerprint(),
                delivery.getVerificationReceiptCanonicalFingerprint());
    }

    public static LearnerMasteryCutoverControlPort openLearnerMasteryCutoverControlOwner(
            Context context,
            String learnerId) {
        return CoreDataLearnerMasteryOwnerBridge.openCutoverControl(context, learnerId);
    }

    public static LearnerMasteryLegacyMigrationPort openLearnerMasteryLegacyMigrationOwner(
            Context context,
            String learnerId) {
        return CoreDataLearnerMasteryOwnerBridge.openLegacyMigration(context, learnerId);
    }

    public static LearnerMasteryCutoverDestinationAttestationPorts
            openLearnerMasteryCutoverDestinationAttestations(Context context) {
        return CoreDataLearnerMasteryOwnerBridge.openCutoverDestinationAttestations(context);
    }

    public static LearnerMasteryFactsImportReceiptReference
            bindVerifiedLearnerMasteryFactsImportReceipt(
                    String learnerId,
                    long cutoverGeneration,
                    String legacyPrefixFingerprint,
                    long migratedRecordCount,
                    String sourceGeneration,
                    String sourceCheckpoint,
                    String destinationFingerprint,
                    String receiptFingerprint) {
        return CoreDataLearnerMasteryOwnerBridge.bindVerifiedFactsImportReceipt(
                learnerId,
                cutoverGeneration,
                legacyPrefixFingerprint,
                migratedRecordCount,
                sourceGeneration,
                sourceCheckpoint,
                destinationFingerprint,
                receiptFingerprint);
    }

    public static LearnerMasteryOpenResponseOwnerGrant claimLearnerMasteryOpenResponseOwnerGrant(
            LearnerMasteryRuntimeCapabilities runtimeOwner) {
        return CoreDataLearnerMasteryOwnerBridge.claimOpenResponseOwnerGrant(runtimeOwner);
    }

    public static LearnerMasteryOpenResponseWeakCandidateOwner
            openLearnerMasteryOpenResponseWeakCandidateOwner(
                    Context context,
                    String learnerId,
                    LearnerMasteryOpenResponseOwnerGrant ownerGrant) {
        return CoreDataLearnerMasteryOwnerBridge.openOpenResponseWeakCandidateOwner(
                context,
                learnerId,
                ownerGrant);
    }

    public static LearnerMasteryOpenResponseWeakCandidateAuthorization
            bindLearnerMasteryOpenResponseWeakCandidateAuthorization(
                    LearnerMasteryOpenResponseWeakCandidateOwner owner,
                    String learnerId,
                    String scopeFingerprint,
                    LearnerMasteryOpenResponseScopeLease scopeLease) {
        return CoreDataLearnerMasteryOwnerBridge.bindOpenResponseWeakCandidateAuthorization(
                owner,
                learnerId,
                scopeFingerprint,
                scopeLease);
    }
}
