package com.tingyun.smartmistakebook.core.student.mistake.database;

import com.tingyun.smartmistakebook.core.model.CanonicalSha256;
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope;
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef;
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsSnapshotV2;
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleChangedV1;
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleState;
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionCommittedV1;
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionSupersededV1;
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV2;
import com.tingyun.smartmistakebook.core.model.storage.StudentMistakeRelayMessage;
import com.tingyun.smartmistakebook.core.model.storage.StudentOutboxAuthenticityProof;
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef;
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind;
import java.util.List;

/** Package-private construction keeps raw envelopes inside the student owner. */
final class StudentOutboxEntityFactory {
    private StudentOutboxEntityFactory() {}

    static CrossStoreEventEnvelope problemRevisionCommitted(
            StudentProblemRevisionRef revision,
            long committedAtEpochMillis,
            String storeGeneration) {
        String receiptFingerprint =
                new CanonicalSha256("student-problem-commit-receipt-v1")
                        .field("revisionRef", revision.getCanonicalFingerprint())
                        .field("committedAtEpochMillis", committedAtEpochMillis)
                        .finish();
        ProblemRevisionCommittedV1 payload =
                new ProblemRevisionCommittedV1(
                        revision,
                        "receipt-" + receiptFingerprint.substring(0, 40),
                        receiptFingerprint,
                        committedAtEpochMillis);
        return envelope(
                "event-" + payload.getPayloadCanonicalFingerprint().substring(0, 40),
                payload.getAggregateId(),
                revision.getRevisionNumber(),
                payload.getOccurredAtEpochMillis(),
                "revision-" + payload.getPayloadCanonicalFingerprint().substring(0, 40),
                storeGeneration,
                payload);
    }

    static CrossStoreEventEnvelope knowledgeBindingsSnapshot(
            StudentProblemRevisionRef revision,
            List<ProblemKnowledgeBindingRef> bindings,
            long bindingSetVersion,
            long changedAtEpochMillis,
            String storeGeneration) {
        ProblemKnowledgeBindingsSnapshotV2 payload =
                new ProblemKnowledgeBindingsSnapshotV2(
                        revision,
                        bindings,
                        bindingSetVersion,
                        changedAtEpochMillis);
        return envelope(
                "event-" + payload.getPayloadCanonicalFingerprint().substring(0, 40),
                payload.getAggregateId(),
                bindingSetVersion,
                payload.getOccurredAtEpochMillis(),
                "binding-snapshot-"
                        + payload.getPayloadCanonicalFingerprint().substring(0, 40),
                storeGeneration,
                payload);
    }

    static CrossStoreEventEnvelope problemLifecycleChanged(
            StudentProblemRevisionRef revision,
            ProblemLifecycleState previousState,
            ProblemLifecycleState nextState,
            long changedAtEpochMillis,
            String storeGeneration) {
        ProblemLifecycleChangedV1 payload =
                new ProblemLifecycleChangedV1(
                        revision,
                        previousState,
                        nextState,
                        changedAtEpochMillis);
        long aggregateVersion = Math.max(1L, changedAtEpochMillis);
        return envelope(
                "event-" + payload.getPayloadCanonicalFingerprint().substring(0, 40),
                payload.getAggregateId(),
                aggregateVersion,
                payload.getOccurredAtEpochMillis(),
                "lifecycle-" + payload.getPayloadCanonicalFingerprint().substring(0, 40),
                storeGeneration,
                payload);
    }

    static CrossStoreEventEnvelope problemRevisionSuperseded(
            StudentProblemRevisionRef previousRevision,
            StudentProblemRevisionRef nextRevision,
            long changedAtEpochMillis,
            String storeGeneration) {
        ProblemRevisionSupersededV1 payload =
                new ProblemRevisionSupersededV1(
                        previousRevision,
                        nextRevision,
                        changedAtEpochMillis);
        long aggregateVersion = Math.max(1L, changedAtEpochMillis);
        return envelope(
                "event-" + payload.getPayloadCanonicalFingerprint().substring(0, 40),
                payload.getAggregateId(),
                aggregateVersion,
                payload.getOccurredAtEpochMillis(),
                "revision-superseded-"
                        + payload.getPayloadCanonicalFingerprint().substring(0, 40),
                storeGeneration,
                payload);
    }

    static StudentStoreOutboxEntity signedEntity(
            CrossStoreEventEnvelope envelope,
            StudentOutboxAuthenticityIssuer authenticityIssuer) {
        StudentMistakeRelayMessage issued = authenticityIssuer.attest(envelope);
        StudentOutboxAuthenticityProof proof = issued.getAuthenticityProof();
        String learnerId;
        if (envelope.getPayload() instanceof ProblemRevisionCommittedV1) {
            learnerId =
                    ((ProblemRevisionCommittedV1) envelope.getPayload())
                            .getRevision().getProblem().getLearnerId();
        } else if (envelope.getPayload() instanceof ProblemKnowledgeBindingsSnapshotV2) {
            learnerId =
                    ((ProblemKnowledgeBindingsSnapshotV2) envelope.getPayload())
                            .getProblemRevision().getProblem().getLearnerId();
        } else if (envelope.getPayload() instanceof ProblemLifecycleChangedV1) {
            learnerId =
                    ((ProblemLifecycleChangedV1) envelope.getPayload())
                            .getProblemRevision().getProblem().getLearnerId();
        } else if (envelope.getPayload() instanceof ProblemRevisionSupersededV1) {
            learnerId =
                    ((ProblemRevisionSupersededV1) envelope.getPayload())
                            .getPreviousRevision().getProblem().getLearnerId();
        } else if (envelope.getPayload() instanceof ReviewObservationCapturedV2) {
            learnerId =
                    ((ReviewObservationCapturedV2) envelope.getPayload())
                            .getProblemRevision().getProblem().getLearnerId();
        } else {
            throw new IllegalArgumentException("Unsupported student mistake outbox payload");
        }
        return new StudentStoreOutboxEntity(
                envelope.getEventId(),
                envelope.getSourceStore().name(),
                envelope.getDestinationStore().name(),
                learnerId,
                envelope.getAggregateId(),
                envelope.getAggregateVersion(),
                envelope.getPayloadType(),
                envelope.getPayloadVersion(),
                envelope.getPayloadCanonicalFingerprint(),
                StudentMistakeCrossStoreCodec.INSTANCE.encode(envelope.getPayload()),
                envelope.getCanonicalFingerprint(),
                envelope.getOccurredAtEpochMillis(),
                envelope.getIdempotencyKey(),
                envelope.getSourceStoreGeneration(),
                proof.getProtocolVersion(),
                proof.getAlgorithmVersion(),
                proof.getIssuerKeyId(),
                proof.getLearnerId(),
                proof.getEnvelopeCanonicalFingerprint(),
                proof.getTagHex(),
                authenticityIssuer.relayEpoch(),
                "PENDING",
                0,
                envelope.getOccurredAtEpochMillis(),
                null);
    }

    private static CrossStoreEventEnvelope envelope(
            String eventId,
            String aggregateId,
            long aggregateVersion,
            long occurredAtEpochMillis,
            String idempotencyKey,
            String storeGeneration,
            com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventPayload payload) {
        return new CrossStoreEventEnvelope(
                eventId,
                StudyStoreKind.STUDENT_MISTAKES,
                StudyStoreKind.LEARNER_MASTERY,
                aggregateId,
                aggregateVersion,
                occurredAtEpochMillis,
                idempotencyKey,
                storeGeneration,
                payload,
                payload.getPayloadType(),
                payload.getPayloadVersion(),
                payload.getPayloadCanonicalFingerprint());
    }
}
