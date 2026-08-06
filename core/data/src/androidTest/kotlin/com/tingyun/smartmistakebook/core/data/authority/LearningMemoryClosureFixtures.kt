package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.knowledge.database.DebugBoundaryKnowledgePackFixture
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsSnapshotV2
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV2
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.model.storage.ReviewVerificationOutcome
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind

internal fun learnerMasteryClosureRevision(
    learnerId: String,
    suffix: String = "closure",
): StudentProblemRevisionRef {
    val problem =
        StudentProblemRef(
            learnerId = learnerId,
            subject = SubjectKind.MATH,
            problemId = "problem:$suffix",
            practiceUnitId = "practice:$suffix",
        )
    return StudentProblemRevisionRef(
        problem = problem,
        revisionId = "revision:$suffix",
        revisionNumber = 1,
        documentCanonicalFingerprint = "a".repeat(64),
    )
}

internal fun learnerMasteryClosureNode(): KnowledgeNodeRef =
    KnowledgeNodeRef(
        subject = SubjectKind.MATH,
        knowledgeNodeId = "debug.math.topic",
        taxonomyVersion = DebugBoundaryKnowledgePackFixture.TAXONOMY_VERSION,
        knowledgePackVersion = DebugBoundaryKnowledgePackFixture.KNOWLEDGE_PACK_VERSION,
    )

internal fun learnerMasteryClosureRelatedNode(): KnowledgeNodeRef =
    KnowledgeNodeRef(
        subject = SubjectKind.MATH,
        knowledgeNodeId = "debug.math.related",
        taxonomyVersion = DebugBoundaryKnowledgePackFixture.TAXONOMY_VERSION,
        knowledgePackVersion = DebugBoundaryKnowledgePackFixture.KNOWLEDGE_PACK_VERSION,
    )

internal fun learnerMasteryClosureBinding(
    revision: StudentProblemRevisionRef,
    node: KnowledgeNodeRef,
    bindingId: String = "binding:closure",
): ProblemKnowledgeBindingRef =
    ProblemKnowledgeBindingRef(
        bindingId = bindingId,
        problemRevision = revision,
        knowledgeNode = node,
        bindingCanonicalFingerprint =
            CanonicalSha256("learning-memory-closure-binding-v1")
                .field("revision", revision.canonicalFingerprint)
                .field("node", node.canonicalFingerprint)
                .finish(),
    )

internal fun learnerMasteryClosureBindingsEnvelope(
    revision: StudentProblemRevisionRef,
    bindings: List<ProblemKnowledgeBindingRef>,
    changedAtEpochMillis: Long,
): CrossStoreEventEnvelope {
    val payload =
        ProblemKnowledgeBindingsSnapshotV2(
            problemRevision = revision,
            bindings = bindings.sortedBy(ProblemKnowledgeBindingRef::bindingId),
            bindingSetVersion = 1L,
            changedAtEpochMillis = changedAtEpochMillis,
        )
    return CrossStoreEventEnvelope(
        eventId = "binding-event:${revision.revisionId}:1",
        sourceStore = StudyStoreKind.STUDENT_MISTAKES,
        destinationStore = StudyStoreKind.LEARNER_MASTERY,
        aggregateId = payload.aggregateId,
        aggregateVersion = 1L,
        occurredAtEpochMillis = payload.occurredAtEpochMillis,
        idempotencyKey = "binding-idempotency:${revision.revisionId}:1",
        sourceStoreGeneration = "student-store-closure-v1",
        payload = payload,
    )
}

internal fun learnerMasteryClosureReviewObservation(
    revision: StudentProblemRevisionRef,
    capturedAtEpochMillis: Long,
    eventSuffix: String = "closure",
    outcome: ReviewVerificationOutcome = ReviewVerificationOutcome.INCORRECT,
    answerWasRevealed: Boolean = false,
): CrossStoreEventEnvelope {
    val payload =
        ReviewObservationCapturedV2(
            problemRevision = revision,
            reviewSessionId = "review-session:$eventSuffix",
            reviewQueueItemId = "review-queue:$eventSuffix",
            observationId = "review-observation:$eventSuffix",
            submissionId = "review-submission:$eventSuffix",
            presentationId = "review-presentation:$eventSuffix",
            responseForm = ReviewResponseForm.NUMERIC,
            responseOpaqueBinding = "5".repeat(64),
            responseBindingAlgorithmVersion = "closure-hmac-sha256-v1",
            verificationOutcome = outcome,
            attemptOrdinal = 1,
            hintCount = 0,
            answerWasRevealed = answerWasRevealed,
            verificationPolicyVersion = "review-verification-v1",
            elapsedDurationMillis = 20_000L,
            capturedAtEpochMillis = capturedAtEpochMillis,
        )
    return CrossStoreEventEnvelope(
        eventId = "review-observation-event:$eventSuffix",
        sourceStore = StudyStoreKind.STUDENT_MISTAKES,
        destinationStore = StudyStoreKind.LEARNER_MASTERY,
        aggregateId = payload.aggregateId,
        aggregateVersion = ReviewObservationCapturedV2.PAYLOAD_VERSION.toLong(),
        occurredAtEpochMillis = payload.occurredAtEpochMillis,
        idempotencyKey = "review-observation-idempotency:$eventSuffix",
        sourceStoreGeneration = "student-store-closure-v1",
        payload = payload,
    )
}
