package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryInboundDisposition
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOutboxDelivery
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryRelayCapability
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryRelayTestFactory
import com.tingyun.smartmistakebook.core.mastery.database.VerifiedStudentMistakeDelivery
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.LearningAttemptRecordedV1
import com.tingyun.smartmistakebook.core.model.storage.LearningEvidenceRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionCommittedV1
import com.tingyun.smartmistakebook.core.model.storage.StudentMistakeRelayMessage
import com.tingyun.smartmistakebook.core.model.storage.StudentOutboxAuthenticityProof
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeInboundDisposition
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeRelayCapability
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMasteryRelayReauthenticationState
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMasteryRelayReauthenticationStatus
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMasteryRelayReauthorizationCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentOutboxAuthenticityVerifierTestFactory
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentOutboxDelivery
import com.tingyun.smartmistakebook.core.student.mistake.database.VerifiedLearnerMasteryDelivery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLearningAuthorityRelayTest {
    @Test
    fun crashBeforeStudentAcknowledgementReplaysDestinationIdempotently() = runBlocking {
        val inbound = studentRevisionEnvelope()
        val student =
            FakeStudentPump(
                pending = mutableListOf(inbound),
                failFirstMark = true,
            )
        val mastery = FakeMasteryPump()
        val relay = relay(student, mastery)

        val first = runCatching { relay.drain(NOW, 8) }
        assertTrue(first.exceptionOrNull() is IllegalStateException)
        assertEquals(1, mastery.accepted.size)
        assertTrue(inbound.eventId !in student.delivered)

        val replay = relay.drain(NOW, 8)
        assertEquals(0, replay.inboundApplied)
        assertEquals(1, replay.inboundDuplicates)
        assertEquals(1, mastery.accepted.size)
        assertTrue(inbound.eventId in student.delivered)
    }

    @Test
    fun crashAfterStudentApplyReplaysOutboundBeforeMasteryAcknowledgement() = runBlocking {
        val outbound = masteryAttemptEnvelope()
        val student = FakeStudentPump(failFirstAcceptAfterApply = true)
        val mastery = FakeMasteryPump(pending = mutableListOf(outbound))
        val relay = relay(student, mastery)

        val first = runCatching { relay.drain(NOW, 8) }
        assertTrue(first.exceptionOrNull() is IllegalStateException)
        assertTrue(outbound.eventId !in mastery.delivered)

        val replay = relay.drain(NOW, 8)
        assertEquals(0, replay.outboundApplied)
        assertEquals(1, replay.outboundDuplicates)
        assertTrue(outbound.eventId in mastery.delivered)
    }

    @Test
    fun forgedStudentProofIsRejectedBeforeMasteryApplyOrSourceAcknowledgement() = runBlocking {
        val inbound = studentRevisionEnvelope()
        val student = FakeStudentPump(
            pending = mutableListOf(inbound),
            proofTag = "0".repeat(64),
        )
        val mastery = FakeMasteryPump()

        val result = runCatching { relay(student, mastery).drain(NOW, 8) }

        assertTrue(result.exceptionOrNull() is SecurityException)
        assertTrue(mastery.accepted.isEmpty())
        assertTrue(inbound.eventId !in student.delivered)
    }

    private fun relay(
        student: StudentMistakeRelayCapability,
        mastery: LearnerMasteryRelayCapability,
    ): LocalLearningAuthorityRelay =
        LocalLearningAuthorityRelay(
            learnerId = LEARNER_ID,
            studentMistakes = student,
            learnerMastery = mastery,
            studentOutboxAuthenticityVerifier = TEST_STUDENT_OUTBOX_VERIFIER,
            masteryOutboxAuthenticityVerifier = TEST_MASTERY_OUTBOX_VERIFIER,
        )

    private fun studentRevisionEnvelope(): CrossStoreEventEnvelope {
        val payload =
            ProblemRevisionCommittedV1(
                revision = revision(),
                commitReceiptId = "commit-receipt-1",
                commitReceiptCanonicalFingerprint = "c".repeat(64),
                committedAtEpochMillis = NOW,
            )
        return CrossStoreEventEnvelope(
            eventId = "student-revision-1",
            sourceStore = StudyStoreKind.STUDENT_MISTAKES,
            destinationStore = StudyStoreKind.LEARNER_MASTERY,
            aggregateId = payload.aggregateId,
            aggregateVersion = 1L,
            occurredAtEpochMillis = payload.occurredAtEpochMillis,
            idempotencyKey = "student-revision-idempotency-1",
            sourceStoreGeneration = "student-v1",
            payload = payload,
        )
    }

    private fun masteryAttemptEnvelope(): CrossStoreEventEnvelope {
        val payload =
            LearningAttemptRecordedV1(
                evidence =
                    LearningEvidenceRef(
                        learnerId = LEARNER_ID,
                        eventKind = "MASTERY_LEARNING_EVENT",
                        eventId = "mastery-event-1",
                        eventSequence = 1L,
                        eventCanonicalFingerprint = "e".repeat(64),
                    ),
                problemRevision = revision(),
                reviewSessionId = "review-session-1",
                reviewQueueItemId = "review-item-1",
                submissionId = "review-submission-1",
                presentationId = "presentation-1",
                recordedAtEpochMillis = NOW,
            )
        return CrossStoreEventEnvelope(
            eventId = "mastery-attempt-1",
            sourceStore = StudyStoreKind.LEARNER_MASTERY,
            destinationStore = StudyStoreKind.STUDENT_MISTAKES,
            aggregateId = payload.aggregateId,
            aggregateVersion = 1L,
            occurredAtEpochMillis = payload.occurredAtEpochMillis,
            idempotencyKey = "mastery-attempt-idempotency-1",
            sourceStoreGeneration = "mastery-v1",
            payload = payload,
        )
    }

    private fun revision(): StudentProblemRevisionRef =
        StudentProblemRevisionRef(
            problem =
                StudentProblemRef(
                    learnerId = LEARNER_ID,
                    subject = SubjectKind.MATH,
                    problemId = "problem-1",
                    practiceUnitId = "practice-unit-1",
                ),
            revisionId = "revision-1",
            revisionNumber = 1,
            documentCanonicalFingerprint = "d".repeat(64),
        )

    private companion object {
        const val LEARNER_ID = "local-default"
        const val NOW = 1_000L
        val TEST_STUDENT_OUTBOX_VERIFIER =
            StudentOutboxAuthenticityVerifierTestFactory.acceptingOnlyTag(
                VALID_STUDENT_OUTBOX_TAG,
            )
        val TEST_MASTERY_OUTBOX_VERIFIER =
            LearnerMasteryRelayTestFactory.acceptingOnlyTag(VALID_MASTERY_OUTBOX_TAG)
    }
}

private class FakeStudentPump(
    private val pending: MutableList<CrossStoreEventEnvelope> = mutableListOf(),
    private var failFirstMark: Boolean = false,
    private var failFirstAcceptAfterApply: Boolean = false,
    private val proofTag: String = VALID_STUDENT_OUTBOX_TAG,
) : StudentMistakeRelayCapability {
    override val learnerId: String = "local-default"
    val delivered = mutableSetOf<String>()
    private val accepted = mutableSetOf<String>()

    override suspend fun acceptInboundBatch(
        messages: List<VerifiedLearnerMasteryDelivery>,
        receivedAtEpochMillis: Long,
    ): List<StudentMistakeInboundDisposition> =
        messages.map { acceptInbound(it, receivedAtEpochMillis) }

    override suspend fun acceptInbound(
        message: VerifiedLearnerMasteryDelivery,
        receivedAtEpochMillis: Long,
    ): StudentMistakeInboundDisposition {
        val envelopeFingerprint =
            StudentOutboxAuthenticityVerifierTestFactory.envelopeFingerprint(message)
        val disposition =
            if (accepted.add(envelopeFingerprint)) {
                StudentMistakeInboundDisposition.APPLIED
            } else {
                StudentMistakeInboundDisposition.DUPLICATE
            }
        if (failFirstAcceptAfterApply) {
            failFirstAcceptAfterApply = false
            error("simulated crash after student inbox apply")
        }
        return disposition
    }

    override suspend fun readMasteryRelayReauthenticationStatus():
        StudentMasteryRelayReauthenticationStatus =
        StudentMasteryRelayReauthenticationStatus(
            StudentMasteryRelayReauthenticationState.TRUSTED,
            null,
        )

    override suspend fun reauthorizeMasteryRelaySource(
        command: StudentMasteryRelayReauthorizationCommand,
    ): StudentMasteryRelayReauthenticationStatus =
        readMasteryRelayReauthenticationStatus()

    override suspend fun readPending(
        nowEpochMillis: Long,
        limit: Int,
    ): List<StudentOutboxDelivery> =
        pending
            .filterNot { it.eventId in delivered }
            .take(limit)
            .map { envelope ->
                StudentOutboxAuthenticityVerifierTestFactory.delivery(
                    sourceIssuedStudentMessage(envelope, proofTag),
                )
            }

    override suspend fun markDelivered(
        message: StudentOutboxDelivery,
        deliveredAtEpochMillis: Long,
    ) {
        if (failFirstMark) {
            failFirstMark = false
            error("simulated crash before student outbox acknowledgement")
        }
        delivered += message.eventId
    }
}

private class FakeMasteryPump(
    private val pending: MutableList<CrossStoreEventEnvelope> = mutableListOf(),
) : LearnerMasteryRelayCapability {
    override val learnerId: String = "local-default"
    val accepted = mutableSetOf<String>()
    val delivered = mutableSetOf<String>()

    override suspend fun accept(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): LearnerMasteryInboundDisposition {
        val fingerprint = LearnerMasteryRelayTestFactory.envelopeFingerprint(message)
        return if (accepted.add(fingerprint)) {
            LearnerMasteryInboundDisposition.APPLIED
        } else {
            LearnerMasteryInboundDisposition.DUPLICATE
        }
    }

    override suspend fun readPending(
        nowEpochMillis: Long,
        limit: Int,
    ): List<LearnerMasteryOutboxDelivery> =
        pending
            .filterNot { it.eventId in delivered }
            .take(limit)
            .map(LearnerMasteryRelayTestFactory::delivery)

    override suspend fun markDelivered(
        message: LearnerMasteryOutboxDelivery,
        deliveredAtEpochMillis: Long,
    ) {
        delivered += message.eventId
    }
}

private fun sourceIssuedStudentMessage(
    envelope: CrossStoreEventEnvelope,
    tagHex: String,
): StudentMistakeRelayMessage =
    StudentMistakeRelayMessage.fromUnverifiedEnvelopeAndProof(
        envelope,
        StudentOutboxAuthenticityProof(
            protocolVersion = StudentOutboxAuthenticityProof.PROTOCOL_VERSION,
            algorithmVersion = StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
            issuerKeyId = "test-student-outbox-key",
            learnerId = "local-default",
            envelopeCanonicalFingerprint = envelope.canonicalFingerprint,
            tagHex = tagHex,
        ),
    )

private val VALID_STUDENT_OUTBOX_TAG = "f".repeat(64)
private val VALID_MASTERY_OUTBOX_TAG = "a".repeat(64)
