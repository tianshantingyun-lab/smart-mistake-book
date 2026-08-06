package com.tingyun.smartmistakebook.core.model.storage

import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossStoreRelayMessageTest {
    @Test
    fun `shared relay values accept only their fixed source and destination`() {
        val studentEnvelope = studentEnvelope()
        val masteryEnvelope = masteryEnvelope()

        assertEquals(
            studentEnvelope,
            StudentMistakeRelayMessage
                .fromUnverifiedEnvelopeAndProof(
                    studentEnvelope,
                    proofFor(studentEnvelope),
                ).envelope,
        )
        assertEquals(
            masteryEnvelope,
            LearnerMasteryRelayMessage
                .fromUnverifiedEnvelopeAndProof(
                    masteryEnvelope,
                    masteryProofFor(masteryEnvelope),
                ).envelope,
        )
        assertTrue(
            runCatching {
                StudentMistakeRelayMessage.fromUnverifiedEnvelopeAndProof(
                    masteryEnvelope,
                    proofFor(masteryEnvelope),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                LearnerMasteryRelayMessage.fromUnverifiedEnvelopeAndProof(
                    studentEnvelope,
                    masteryProofFor(studentEnvelope),
                )
            }.isFailure,
        )
    }

    @Test
    fun `relay values expose no public constructor or mutation operation`() {
        StudentMistakeRelayMessage::class.java.let { messageType ->
            assertTrue(
                messageType.constructors.none {
                    java.lang.reflect.Modifier.isPublic(it.modifiers)
                },
            )
            assertEquals(
                setOf(
                    "fromUnverifiedEnvelopeAndProof",
                    "getAuthenticityProof",
                    "getEnvelope",
                ),
                messageType.declaredMethods
                    .filterNot { it.isSynthetic }
                    .filterNot { java.lang.reflect.Modifier.isPrivate(it.modifiers) }
                    .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
            )
            assertTrue(
                messageType.declaredMethods.none { method ->
                    java.lang.reflect.Modifier.isPublic(method.modifiers) &&
                        method.parameterTypes.contentEquals(
                            arrayOf(CrossStoreEventEnvelope::class.java),
                        )
                },
            )
        }
        LearnerMasteryRelayMessage::class.java.let { messageType ->
            assertTrue(
                messageType.constructors.none {
                    java.lang.reflect.Modifier.isPublic(it.modifiers)
                },
            )
            assertEquals(
                setOf(
                    "fromUnverifiedEnvelopeAndProof",
                    "getAuthenticityProof",
                    "getEnvelope",
                ),
                messageType.declaredMethods
                    .filterNot { it.isSynthetic }
                    .filterNot { java.lang.reflect.Modifier.isPrivate(it.modifiers) }
                    .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
            )
        }
    }

    @Test
    fun `student relay proof must name the exact immutable envelope`() {
        val first = studentEnvelope()
        val second =
            first.copy(
                eventId = "student-event-2",
                idempotencyKey = "student-idempotency-2",
            )

        assertTrue(
            runCatching {
                StudentMistakeRelayMessage.fromUnverifiedEnvelopeAndProof(
                    second,
                    proofFor(first),
                )
            }.isFailure,
        )
    }

    @Test
    fun `student outbox proof rejects unsupported or malformed metadata`() {
        val envelope = studentEnvelope()
        val valid = proofFor(envelope)

        listOf(
            { valid.copy(protocolVersion = 2) },
            { valid.copy(algorithmVersion = "hmac-sha1") },
            { valid.copy(issuerKeyId = " ") },
            { valid.copy(learnerId = "\n") },
            { valid.copy(envelopeCanonicalFingerprint = "a".repeat(63)) },
            { valid.copy(tagHex = "A".repeat(64)) },
        ).forEach { invalidProof ->
            assertTrue(runCatching(invalidProof).isFailure)
        }
    }

    private fun studentEnvelope(): CrossStoreEventEnvelope {
        val payload =
            ProblemRevisionCommittedV1(
                revision = revision(),
                commitReceiptId = "receipt-1",
                commitReceiptCanonicalFingerprint = "b".repeat(64),
                committedAtEpochMillis = 100L,
            )
        return CrossStoreEventEnvelope(
            eventId = "student-event-1",
            sourceStore = StudyStoreKind.STUDENT_MISTAKES,
            destinationStore = StudyStoreKind.LEARNER_MASTERY,
            aggregateId = payload.aggregateId,
            aggregateVersion = 1L,
            occurredAtEpochMillis = payload.occurredAtEpochMillis,
            idempotencyKey = "student-idempotency-1",
            sourceStoreGeneration = "student-generation-v1",
            payload = payload,
        )
    }

    private fun masteryEnvelope(): CrossStoreEventEnvelope {
        val payload =
            LearningAttemptRecordedV1(
                evidence =
                    LearningEvidenceRef(
                        learnerId = "learner-1",
                        eventKind = "review_answer",
                        eventId = "evidence-1",
                        eventSequence = 1L,
                        eventCanonicalFingerprint = "c".repeat(64),
                    ),
                problemRevision = revision(),
                reviewSessionId = "review-session-1",
                reviewQueueItemId = "queue-item-1",
                submissionId = "submission-1",
                presentationId = "presentation-1",
                recordedAtEpochMillis = 200L,
            )
        return CrossStoreEventEnvelope(
            eventId = "mastery-event-1",
            sourceStore = StudyStoreKind.LEARNER_MASTERY,
            destinationStore = StudyStoreKind.STUDENT_MISTAKES,
            aggregateId = payload.aggregateId,
            aggregateVersion = 1L,
            occurredAtEpochMillis = payload.occurredAtEpochMillis,
            idempotencyKey = "mastery-idempotency-1",
            sourceStoreGeneration = "mastery-generation-v1",
            payload = payload,
        )
    }

    private fun proofFor(envelope: CrossStoreEventEnvelope):
        StudentOutboxAuthenticityProof =
        StudentOutboxAuthenticityProof(
            protocolVersion = StudentOutboxAuthenticityProof.PROTOCOL_VERSION,
            algorithmVersion = StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
            issuerKeyId = "student-outbox-key-1",
            learnerId = "learner-1",
            envelopeCanonicalFingerprint = envelope.canonicalFingerprint,
            tagHex = "d".repeat(64),
        )

    private fun masteryProofFor(envelope: CrossStoreEventEnvelope):
        MasteryOutboxAuthenticityProof =
        MasteryOutboxAuthenticityProof(
            protocolVersion = MasteryOutboxAuthenticityProof.PROTOCOL_VERSION,
            algorithmVersion = MasteryOutboxAuthenticityProof.ALGORITHM_VERSION,
            issuerKeyId = "mastery-outbox-key-1",
            learnerId = "learner-1",
            envelopeCanonicalFingerprint = envelope.canonicalFingerprint,
            tagHex = "e".repeat(64),
        )

    private fun revision(): StudentProblemRevisionRef =
        StudentProblemRevisionRef(
            problem =
                StudentProblemRef(
                    learnerId = "learner-1",
                    subject = SubjectKind.MATH,
                    problemId = "problem-1",
                    practiceUnitId = "practice-1",
                ),
            revisionId = "revision-1",
            revisionNumber = 1,
            documentCanonicalFingerprint = "a".repeat(64),
        )
}
