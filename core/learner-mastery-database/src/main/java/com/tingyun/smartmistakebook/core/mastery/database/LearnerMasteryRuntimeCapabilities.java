package com.tingyun.smartmistakebook.core.mastery.database;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opaque owner session assembled by the mastery database module and retained only by core:data.
 *
 * <p>It exposes independent learner-bound capabilities, never the complete authority. The private
 * constructor also prevents a caller from assembling mismatched capabilities.
 */
public final class LearnerMasteryRuntimeCapabilities implements AutoCloseable {
    private final String learnerId;
    private final LearnerMasteryObservationSink observationSink;
    private final LearnerMasteryPendingOpenResponseSink pendingOpenResponseSink;
    private final LearnerMasteryReader reader;
    private final LearnerMasteryDisplayReader displayReader;
    private final LearnerMasteryEvidenceReviewCapability evidenceReviewCapability;
    private final LearnerMasteryEvidenceCorrectionCapability evidenceCorrectionCapability;
    private final LocalMasteryContextReader localContextReader;
    private final LearnerMasteryKnowledgeEvidenceAuthorizer knowledgeEvidenceAuthorizer;
    private final LearnerMasteryEraseCapability eraseCapability;
    private final LearnerMasteryModelAccessProvider modelAccessProvider;
    private final Runnable modelAccessTerminator;
    private final LearnerMasteryRelayCapability relay;
    private final MasteryOutboxAuthenticityVerifier outboxAuthenticityVerifier;
    private final AutoCloseable authorityCloser;
    private final Object databaseOwnerSeal;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private LearnerMasteryRuntimeCapabilities(
            String learnerId,
            LearnerMasteryObservationSink observationSink,
            LearnerMasteryPendingOpenResponseSink pendingOpenResponseSink,
            LearnerMasteryReader reader,
            LearnerMasteryDisplayReader displayReader,
            LearnerMasteryEvidenceReviewCapability evidenceReviewCapability,
            LearnerMasteryEvidenceCorrectionCapability evidenceCorrectionCapability,
            LocalMasteryContextReader localContextReader,
            LearnerMasteryKnowledgeEvidenceAuthorizer knowledgeEvidenceAuthorizer,
            LearnerMasteryEraseCapability eraseCapability,
            LearnerMasteryModelAccessProvider modelAccessProvider,
            Runnable modelAccessTerminator,
            LearnerMasteryRelayCapability relay,
            MasteryOutboxAuthenticityVerifier outboxAuthenticityVerifier,
            AutoCloseable authorityCloser,
            Object databaseOwnerSeal) {
        if (databaseOwnerSeal != null) {
            CoreDataLearnerMasteryOwnerBridge.requireOpenResponseOwnerSeal(databaseOwnerSeal);
        }
        if (!learnerId.equals(relay.getLearnerId())) {
            throw new IllegalArgumentException(
                    "Learner-mastery capabilities must share one learner scope");
        }
        this.learnerId = learnerId;
        this.observationSink = observationSink;
        this.pendingOpenResponseSink = pendingOpenResponseSink;
        this.reader = reader;
        this.displayReader = displayReader;
        this.evidenceReviewCapability = evidenceReviewCapability;
        this.evidenceCorrectionCapability = evidenceCorrectionCapability;
        this.localContextReader = localContextReader;
        this.knowledgeEvidenceAuthorizer = knowledgeEvidenceAuthorizer;
        this.eraseCapability = eraseCapability;
        this.modelAccessProvider = modelAccessProvider;
        this.modelAccessTerminator = modelAccessTerminator;
        this.relay = relay;
        this.outboxAuthenticityVerifier = outboxAuthenticityVerifier;
        this.authorityCloser = authorityCloser;
        this.databaseOwnerSeal = databaseOwnerSeal;
        if (databaseOwnerSeal != null) {
            CoreDataLearnerMasteryOwnerBridge.registerOpenResponseRuntime(
                    this, learnerId, databaseOwnerSeal);
        }
    }

    static LearnerMasteryRuntimeCapabilities create(
            String learnerId,
            LearnerMasteryObservationSink observationSink,
            LearnerMasteryPendingOpenResponseSink pendingOpenResponseSink,
            LearnerMasteryReader reader,
            LearnerMasteryDisplayReader displayReader,
            LearnerMasteryEvidenceReviewCapability evidenceReviewCapability,
            LearnerMasteryEvidenceCorrectionCapability evidenceCorrectionCapability,
            LocalMasteryContextReader localContextReader,
            LearnerMasteryKnowledgeEvidenceAuthorizer knowledgeEvidenceAuthorizer,
            LearnerMasteryEraseCapability eraseCapability,
            LearnerMasteryModelAccessProvider modelAccessProvider,
            Runnable modelAccessTerminator,
            LearnerMasteryRelayCapability relay,
            MasteryOutboxAuthenticityVerifier outboxAuthenticityVerifier,
            AutoCloseable authorityCloser,
            Object databaseOwnerSeal) {
        return new LearnerMasteryRuntimeCapabilities(
                learnerId,
                observationSink,
                pendingOpenResponseSink,
                reader,
                displayReader,
                evidenceReviewCapability,
                evidenceCorrectionCapability,
                localContextReader,
                knowledgeEvidenceAuthorizer,
                eraseCapability,
                modelAccessProvider,
                modelAccessTerminator,
                relay,
                outboxAuthenticityVerifier,
                authorityCloser,
                databaseOwnerSeal);
    }

    public String getLearnerId() {
        return learnerId;
    }

    public LearnerMasteryObservationSink getObservationSink() {
        return observationSink;
    }

    public LearnerMasteryPendingOpenResponseSink getPendingOpenResponseSink() {
        return pendingOpenResponseSink;
    }

    public LearnerMasteryReader getReader() {
        return reader;
    }

    public LearnerMasteryDisplayReader getDisplayReader() {
        return displayReader;
    }

    public LearnerMasteryEvidenceReviewCapability getEvidenceReviewCapability() {
        return evidenceReviewCapability;
    }

    public LearnerMasteryEvidenceCorrectionCapability getEvidenceCorrectionCapability() {
        return evidenceCorrectionCapability;
    }

    public LocalMasteryContextReader getLocalContextReader() {
        return localContextReader;
    }

    public LearnerMasteryKnowledgeEvidenceAuthorizer getKnowledgeEvidenceAuthorizer() {
        return knowledgeEvidenceAuthorizer;
    }

    public LearnerMasteryEraseCapability getEraseCapability() {
        return eraseCapability;
    }

    LearnerMasteryModelAccessProvider modelHostHandoff() {
        return modelAccessProvider;
    }

    LearnerMasteryRelayCapability relayHandoff() {
        return relay;
    }

    MasteryOutboxAuthenticityVerifier outboxAuthenticityVerifierHandoff() {
        if (outboxAuthenticityVerifier == null) {
            throw new IllegalStateException(
                    "Learner-mastery outbox verification is unavailable for this test runtime");
        }
        return outboxAuthenticityVerifier;
    }

    @Override
    public void close() throws Exception {
        if (closed.compareAndSet(false, true)) {
            if (databaseOwnerSeal != null) {
                CoreDataLearnerMasteryOwnerBridge.revokeOpenResponseRuntime(
                        this, databaseOwnerSeal);
            }
            modelAccessTerminator.run();
            authorityCloser.close();
        }
    }
}
