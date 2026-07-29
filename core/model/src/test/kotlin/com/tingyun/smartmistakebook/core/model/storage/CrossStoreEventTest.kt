package com.tingyun.smartmistakebook.core.model.storage

import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class CrossStoreEventTest {
    @Test
    fun `envelope fingerprint is deterministic and binds delivery metadata`() {
        val payload = committedPayload()
        val first = envelope(payload)
        val replay = envelope(committedPayload())

        assertEquals(first.canonicalFingerprint, replay.canonicalFingerprint)
        assertNotEquals(
            first.canonicalFingerprint,
            envelope(payload, eventId = "event-2").canonicalFingerprint,
        )
        assertNotEquals(
            first.canonicalFingerprint,
            envelope(payload, aggregateVersion = 2).canonicalFingerprint,
        )
        assertNotEquals(
            first.canonicalFingerprint,
            envelope(payload, idempotencyKey = "idempotency-2").canonicalFingerprint,
        )
        assertNotEquals(
            first.canonicalFingerprint,
            envelope(payload, sourceStoreGeneration = "mistakes-generation-v2")
                .canonicalFingerprint,
        )
    }

    @Test
    fun `payload fingerprint changes when a semantic field changes`() {
        val first = committedPayload()
        val changed = committedPayload(
            commitReceiptCanonicalFingerprint = "c".repeat(64),
        )

        assertNotEquals(
            first.payloadCanonicalFingerprint,
            changed.payloadCanonicalFingerprint,
        )
        assertNotEquals(
            envelope(first).canonicalFingerprint,
            envelope(changed).canonicalFingerprint,
        )
    }

    @Test
    fun `envelope rejects same-store and unsupported routes`() {
        val payload = committedPayload()
        assertIllegalArgument {
            envelope(
                payload,
                sourceStore = StudyStoreKind.STUDENT_MISTAKES,
                destinationStore = StudyStoreKind.STUDENT_MISTAKES,
            )
        }
        assertIllegalArgument {
            envelope(
                payload,
                destinationStore = StudyStoreKind.HIGH_SCHOOL_KNOWLEDGE,
            )
        }
        assertIllegalArgument {
            envelope(
                payload,
                sourceStore = StudyStoreKind.LEARNER_MASTERY,
            )
        }
    }

    @Test
    fun `envelope rejects empty identities stale headers and invalid versions`() {
        val payload = committedPayload()
        assertIllegalArgument {
            envelope(payload, eventId = "")
        }
        assertIllegalArgument {
            envelope(payload, aggregateVersion = 0)
        }
        assertIllegalArgument {
            envelope(payload, sourceStoreGeneration = " generation-v1")
        }
        assertIllegalArgument {
            envelope(payload, payloadVersion = 2)
        }
        assertIllegalArgument {
            envelope(payload, payloadType = "another_type")
        }
        assertIllegalArgument {
            envelope(payload, payloadCanonicalFingerprint = "f".repeat(64))
        }
    }

    @Test
    fun `knowledge binding payload is canonical and revision scoped`() {
        val revision = revision()
        val first = binding(bindingId = "binding-1", problemRevision = revision)
        val second = binding(bindingId = "binding-2", problemRevision = revision)
        val payload = ProblemKnowledgeBindingsAcceptedV1(
            problemRevision = revision,
            bindings = listOf(first, second),
            acceptedAtEpochMillis = 200,
        )
        assertEquals(
            payload.payloadCanonicalFingerprint,
            ProblemKnowledgeBindingsAcceptedV1(
                problemRevision = revision,
                bindings = listOf(first, second),
                acceptedAtEpochMillis = 200,
            ).payloadCanonicalFingerprint,
        )
        assertIllegalArgument {
            ProblemKnowledgeBindingsAcceptedV1(
                problemRevision = revision,
                bindings = listOf(second, first),
                acceptedAtEpochMillis = 200,
            )
        }
        assertIllegalArgument {
            ProblemKnowledgeBindingsAcceptedV1(
                problemRevision = revision,
                bindings = listOf(
                    binding(
                        problemRevision = revision(
                            revisionId = "revision-2",
                            documentCanonicalFingerprint = "9".repeat(64),
                        ),
                    ),
                ),
                acceptedAtEpochMillis = 200,
            )
        }
    }

    @Test
    fun `attempt and catalog payloads enforce their exact routes`() {
        val attempt = LearningAttemptRecordedV1(
            evidence = LearningEvidenceRef(
                learnerId = "learner-1",
                eventKind = "ATTEMPT",
                eventId = "attempt-1",
                eventSequence = 7,
                eventCanonicalFingerprint = "e".repeat(64),
            ),
            problemRevision = revision(),
            reviewSessionId = "review-session-1",
            reviewQueueItemId = "review-item-1",
            submissionId = "submission-1",
            presentationId = "presentation-1",
            recordedAtEpochMillis = 300,
        )
        envelope(
            payload = attempt,
            sourceStore = StudyStoreKind.LEARNER_MASTERY,
            destinationStore = StudyStoreKind.STUDENT_MISTAKES,
            aggregateId = attempt.aggregateId,
            occurredAtEpochMillis = attempt.occurredAtEpochMillis,
        )

        val catalog = KnowledgeCatalogActivatedV1(
            knowledgePackVersion = "knowledge-pack-v2",
            taxonomyVersion = "taxonomy-v2",
            manifestCanonicalFingerprint = "f".repeat(64),
            activatedAtEpochMillis = 400,
        )
        envelope(
            payload = catalog,
            sourceStore = StudyStoreKind.HIGH_SCHOOL_KNOWLEDGE,
            destinationStore = StudyStoreKind.LEARNER_MASTERY,
            aggregateId = catalog.aggregateId,
            occurredAtEpochMillis = catalog.occurredAtEpochMillis,
        )
        assertIllegalArgument {
            envelope(
                payload = catalog,
                sourceStore = StudyStoreKind.HIGH_SCHOOL_KNOWLEDGE,
                destinationStore = StudyStoreKind.HIGH_SCHOOL_KNOWLEDGE,
                aggregateId = catalog.aggregateId,
                occurredAtEpochMillis = catalog.occurredAtEpochMillis,
            )
        }
        assertIllegalArgument {
            catalog.copy(knowledgePackVersion = " knowledge-pack-v2")
        }
        assertIllegalArgument {
            attempt.copy(
                evidence = attempt.evidence.copy(learnerId = "learner-2"),
            )
        }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }
}

private fun committedPayload(
    commitReceiptCanonicalFingerprint: String = "b".repeat(64),
) = ProblemRevisionCommittedV1(
    revision = revision(),
    commitReceiptId = "commit-receipt-1",
    commitReceiptCanonicalFingerprint = commitReceiptCanonicalFingerprint,
    committedAtEpochMillis = 100,
)

private fun envelope(
    payload: CrossStoreEventPayload,
    eventId: String = "event-1",
    sourceStore: StudyStoreKind = payload.sourceStore,
    destinationStore: StudyStoreKind = payload.allowedDestinationStores.first(),
    aggregateId: String = payload.aggregateId,
    aggregateVersion: Long = 1,
    occurredAtEpochMillis: Long = payload.occurredAtEpochMillis,
    idempotencyKey: String = "idempotency-1",
    sourceStoreGeneration: String = "mistakes-generation-v1",
    payloadType: String = payload.payloadType,
    payloadVersion: Int = payload.payloadVersion,
    payloadCanonicalFingerprint: String = payload.payloadCanonicalFingerprint,
) = CrossStoreEventEnvelope(
    eventId = eventId,
    sourceStore = sourceStore,
    destinationStore = destinationStore,
    aggregateId = aggregateId,
    aggregateVersion = aggregateVersion,
    occurredAtEpochMillis = occurredAtEpochMillis,
    idempotencyKey = idempotencyKey,
    sourceStoreGeneration = sourceStoreGeneration,
    payload = payload,
    payloadType = payloadType,
    payloadVersion = payloadVersion,
    payloadCanonicalFingerprint = payloadCanonicalFingerprint,
)

private fun revision(
    revisionId: String = "revision-1",
    documentCanonicalFingerprint: String = "a".repeat(64),
) = StudentProblemRevisionRef(
    problem = StudentProblemRef(
        learnerId = "learner-1",
        subject = SubjectKind.MATH,
        problemId = "problem-1",
        practiceUnitId = "practice-1",
    ),
    revisionId = revisionId,
    revisionNumber = 1,
    documentCanonicalFingerprint = documentCanonicalFingerprint,
)

private fun binding(
    bindingId: String = "binding-1",
    problemRevision: StudentProblemRevisionRef = revision(),
) = ProblemKnowledgeBindingRef(
    bindingId = bindingId,
    problemRevision = problemRevision,
    knowledgeNode = KnowledgeNodeRef(
        subject = SubjectKind.MATH,
        knowledgeNodeId = "kb:math:atomic:monotonicity:$bindingId",
        taxonomyVersion = "taxonomy-v1",
        knowledgePackVersion = "knowledge-pack-v1",
    ),
    bindingCanonicalFingerprint = CanonicalSha256ForTest.of(bindingId),
)

private object CanonicalSha256ForTest {
    fun of(value: String): String = com.tingyun.smartmistakebook.core.model.CanonicalSha256(
        "test-binding-v1",
    ).field("value", value).finish()
}
