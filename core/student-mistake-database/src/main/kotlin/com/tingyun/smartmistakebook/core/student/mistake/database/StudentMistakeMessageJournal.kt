package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.LearningAttemptRecordedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsSnapshotV2
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleChangedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionCommittedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionSupersededV1
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV1
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV2
import com.tingyun.smartmistakebook.core.model.storage.StudentMistakeRelayMessage
import com.tingyun.smartmistakebook.core.model.storage.StudentOutboxAuthenticityProof
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind
import kotlinx.coroutines.CancellationException

internal class StudentMistakeMessageJournal(
    private val dao: StudentMistakeDao,
    override val learnerId: String,
    private val authenticityVerifier: StudentOutboxAuthenticityVerifier,
    private val activeStoreGeneration: suspend () -> String,
    private val activeAuthenticityKey: suspend () -> ActiveStudentOutboxAuthenticityKey,
    private val authenticatorSession: StudentOutboxAuthenticatorSession,
) : StudentMistakeRelayCapability {
    init {
        learnerId.requireStoreText("Learner id", MAX_ID_CHARS)
    }

    override suspend fun acceptInboundBatch(
        messages: List<VerifiedLearnerMasteryDelivery>,
        receivedAtEpochMillis: Long,
    ): List<StudentMistakeInboundDisposition> {
        require(messages.size in 1..MAX_MESSAGE_BATCH_SIZE) {
            "Inbox batch size is outside the supported range"
        }
        return messages.map { acceptInbound(it, receivedAtEpochMillis) }
    }

    override suspend fun acceptInbound(
        message: VerifiedLearnerMasteryDelivery,
        receivedAtEpochMillis: Long,
    ): StudentMistakeInboundDisposition {
        val envelope = message.envelope()
        val payloadWire = validateAndEncode(envelope, receivedAtEpochMillis)
        val payload = envelope.payload as LearningAttemptRecordedV1
        val subject = payload.problemRevision.problem.subject.name
        val scopeFingerprint =
            masteryAttemptScopeFingerprint(
                learnerId = learnerId,
                subject = subject,
                problemId = payload.problemRevision.problem.problemId,
                problemRevisionId = payload.problemRevision.revisionId,
                eventSequence = payload.evidence.eventSequence,
                reviewSessionId = payload.reviewSessionId,
                reviewQueueItemId = payload.reviewQueueItemId,
                submissionId = payload.submissionId,
                presentationId = payload.presentationId,
            )
        return dao.acceptInbox(
            ApplyStudentInboxBundle(
                message = envelope.toInboxEntity(receivedAtEpochMillis, payloadWire),
                authenticatedReceipt =
                    StudentAuthenticatedMasteryInboxReceiptEntity(
                        eventId = envelope.eventId,
                        learnerId = message.learnerId(),
                        subject = subject,
                        problemId = payload.problemRevision.problem.problemId,
                        problemRevisionId = payload.problemRevision.revisionId,
                        eventSequence = payload.evidence.eventSequence,
                        reviewSessionId = payload.reviewSessionId,
                        reviewQueueItemId = payload.reviewQueueItemId,
                        submissionId = payload.submissionId,
                        presentationId = payload.presentationId,
                        scopeCanonicalFingerprint = scopeFingerprint,
                        sourceStore = envelope.sourceStore.name,
                        sourceStoreGeneration = message.sourceStoreGeneration(),
                        relayEpoch = message.relayEpoch(),
                        issuerKeyId = message.issuerKeyId(),
                        algorithmVersion = message.algorithmVersion(),
                        envelopeCanonicalFingerprint = message.envelopeCanonicalFingerprint(),
                        proofCanonicalFingerprint = message.proofCanonicalFingerprint(),
                        verificationReceiptCanonicalFingerprint =
                            message.verificationReceiptCanonicalFingerprint(),
                        recordedAtEpochMillis = payload.recordedAtEpochMillis,
                        receivedAtEpochMillis = receivedAtEpochMillis,
                    ),
                completedQueueItemId = payload.reviewQueueItemId,
                expectedLearnerId = learnerId,
                expectedSubject = subject,
                expectedProblemId = payload.problemRevision.problem.problemId,
                expectedRevisionId = payload.problemRevision.revisionId,
                learningEvidence = payload.evidence,
                attemptRecordedAtEpochMillis = payload.recordedAtEpochMillis,
            ),
        )
    }

    private fun validateAndEncode(
        envelope: CrossStoreEventEnvelope,
        receivedAtEpochMillis: Long,
    ): String {
        require(envelope.destinationStore == StudyStoreKind.STUDENT_MISTAKES) {
            "Student mistake inbox only accepts messages addressed to the mistake store"
        }
        require(envelope.sourceStore == StudyStoreKind.LEARNER_MASTERY) {
            "Student mistake inbox only accepts the authenticated mastery route"
        }
        require(receivedAtEpochMillis >= envelope.occurredAtEpochMillis) {
            "Inbox receive time must not precede the source event"
        }
        val payload = envelope.payload
        require(payload is LearningAttemptRecordedV1) {
            "Unsupported student mistake inbox payload"
        }
        require(
            payload.evidence.learnerId == learnerId &&
                payload.problemRevision.problem.learnerId == learnerId,
        ) { "Student mistake inbox message is outside the bound learner scope" }
        require(
            payload.evidence.eventSequence > 0L &&
                envelope.aggregateId == payload.evidence.eventId &&
                envelope.aggregateVersion == payload.evidence.eventSequence &&
                envelope.occurredAtEpochMillis == payload.recordedAtEpochMillis,
        ) { "Learning attempt sequence or time scope is invalid" }
        val payloadWire = StudentMistakeCrossStoreCodec.encode(payload)
        val decoded =
            StudentMistakeCrossStoreCodec.decode(
                payloadType = envelope.payloadType,
                payloadVersion = envelope.payloadVersion,
                wire = payloadWire,
            )
        check(
            decoded == payload &&
                decoded.payloadCanonicalFingerprint ==
                envelope.payloadCanonicalFingerprint,
        ) {
            "Student mistake inbox accepts only a complete, canonically decodable payload"
        }
        return payloadWire
    }

    private fun CrossStoreEventEnvelope.toInboxEntity(
        receivedAtEpochMillis: Long,
        payloadWire: String,
    ): StudentStoreInboxEntity =
        StudentStoreInboxEntity(
            eventId = eventId,
            sourceStore = sourceStore.name,
            destinationStore = destinationStore.name,
            aggregateId = aggregateId,
            aggregateVersion = aggregateVersion,
            payloadType = payloadType,
            payloadVersion = payloadVersion,
            payloadCanonicalFingerprint = payloadCanonicalFingerprint,
            payloadWire = payloadWire,
            envelopeCanonicalFingerprint = canonicalFingerprint,
            occurredAtEpochMillis = occurredAtEpochMillis,
            idempotencyKey = idempotencyKey,
            sourceStoreGeneration = sourceStoreGeneration,
            applyState = "RECEIVED",
            receivedAtEpochMillis = receivedAtEpochMillis,
            appliedAtEpochMillis = null,
        )

    override suspend fun readPending(
        nowEpochMillis: Long,
        limit: Int,
    ): List<StudentOutboxDelivery> {
        require(nowEpochMillis >= 0) { "Outbox read time must not be negative" }
        require(limit in 1..MAX_MESSAGE_BATCH_SIZE) {
            "Outbox batch size is outside the supported range"
        }
        dao.retirePendingUnsignedStudentOutbox(learnerId, nowEpochMillis)
        val currentStoreGeneration = activeStoreGeneration()
        val activeKey = activeAuthenticityKey()
        check(activeKey.sourceStoreGeneration == currentStoreGeneration) {
            "Student outbox key-state is bound to another store generation"
        }
        authenticatorSession.bind(activeKey)
        val deliveries = mutableListOf<StudentOutboxDelivery>()
        val scannedAuthenticEventIds = mutableSetOf<String>()
        var scanRounds = 0
        while (
            deliveries.size < limit &&
                scanRounds++ < MAX_AUTHENTICITY_REJECTION_SCAN_ROUNDS
        ) {
            val rows =
                dao.readPendingOutbox(
                    learnerId,
                    nowEpochMillis,
                    MAX_MESSAGE_BATCH_SIZE,
                )
            if (rows.isEmpty()) break
            var rejectedCount = 0
            for (row in rows) {
                if (deliveries.size == limit) break
                if (!scannedAuthenticEventIds.add(row.eventId)) continue
                try {
                    deliveries += decodeAuthenticDelivery(row, currentStoreGeneration, activeKey)
                } catch (failure: RuntimeException) {
                    if (failure is CancellationException || !failure.isAuthenticityRowFailure()) {
                        throw failure
                    }
                    val persistedTag =
                        checkNotNull(row.authenticityTagHex) {
                            "Pending authenticated outbox row lost its proof tag"
                        }
                    check(
                        dao.rejectPendingOutboxAuthenticity(
                            learnerId = learnerId,
                            eventId = row.eventId,
                            expectedEnvelopeCanonicalFingerprint =
                                row.envelopeCanonicalFingerprint,
                            expectedAuthenticityTagHex = persistedTag,
                            rejectedAtEpochMillis =
                                maxOf(nowEpochMillis, row.occurredAtEpochMillis),
                        ) == 1,
                    ) {
                        "Invalid student outbox row changed before authenticity rejection"
                    }
                    rejectedCount += 1
                }
            }
            if (deliveries.size == limit) break
            if (rejectedCount == 0) break
        }
        return deliveries
    }

    private fun decodeAuthenticDelivery(
        row: StudentStoreOutboxEntity,
        currentStoreGeneration: String,
        activeKey: ActiveStudentOutboxAuthenticityKey,
    ): StudentOutboxDelivery {
        val message =
            try {
                check(row.learnerId == learnerId) {
                    "Corrupt student mistake outbox: learner scope mismatch"
                }
                val payload =
                    StudentMistakeCrossStoreCodec.decode(
                        payloadType = row.payloadType,
                        payloadVersion = row.payloadVersion,
                        wire = row.payloadWire,
                    )
                val envelope =
                    CrossStoreEventEnvelope(
                        eventId = row.eventId,
                        sourceStore = enumValueOrCorrupt(row.sourceStore, "outbox source store"),
                        destinationStore =
                            enumValueOrCorrupt(row.destinationStore, "outbox destination store"),
                        aggregateId = row.aggregateId,
                        aggregateVersion = row.aggregateVersion,
                        occurredAtEpochMillis = row.occurredAtEpochMillis,
                        idempotencyKey = row.idempotencyKey,
                        sourceStoreGeneration = row.sourceStoreGeneration,
                        payload = payload,
                        payloadType = row.payloadType,
                        payloadVersion = row.payloadVersion,
                        payloadCanonicalFingerprint = row.payloadCanonicalFingerprint,
                    )
                check(envelope.canonicalFingerprint == row.envelopeCanonicalFingerprint) {
                    "Corrupt student mistake outbox: envelope fingerprint mismatch"
                }
                check(payload.learnerIdForStudentOutbox() == learnerId) {
                    "Corrupt student mistake outbox: payload learner mismatch"
                }
                check(row.sourceStoreGeneration == currentStoreGeneration) {
                    "Corrupt student mistake outbox: source generation mismatch"
                }
                check(row.authenticityRelayEpoch == activeKey.relayEpoch) {
                    "Corrupt student mistake outbox: relay epoch mismatch"
                }
                val proof =
                    StudentOutboxAuthenticityProof(
                        protocolVersion =
                            checkNotNull(row.authenticityProofProtocolVersion) {
                                "Corrupt student mistake outbox: missing proof protocol"
                            },
                        algorithmVersion =
                            checkNotNull(row.authenticityAlgorithmVersion) {
                                "Corrupt student mistake outbox: missing proof algorithm"
                            },
                        issuerKeyId =
                            checkNotNull(row.authenticityIssuerKeyId) {
                                "Corrupt student mistake outbox: missing proof key id"
                            },
                        learnerId =
                            checkNotNull(row.authenticityLearnerId) {
                                "Corrupt student mistake outbox: missing proof learner"
                            },
                        envelopeCanonicalFingerprint =
                            checkNotNull(row.authenticityEnvelopeFingerprint) {
                                "Corrupt student mistake outbox: missing envelope proof"
                            },
                        tagHex =
                            checkNotNull(row.authenticityTagHex) {
                                "Corrupt student mistake outbox: missing proof tag"
                            },
                    )
                StudentMistakeRelayMessage.fromUnverifiedEnvelopeAndProof(envelope, proof)
            } catch (failure: IllegalArgumentException) {
                throw StudentOutboxInvalidRowException(failure)
            } catch (failure: IllegalStateException) {
                if (failure is CancellationException) throw failure
                throw StudentOutboxInvalidRowException(failure)
            }
        authenticityVerifier.requireAuthentic(message)
        return StudentOutboxDelivery.ownerIssued(message)
    }

    override suspend fun markDelivered(
        message: StudentOutboxDelivery,
        deliveredAtEpochMillis: Long,
    ) {
        authenticityVerifier.requireAuthentic(message)
        val envelope = message.issuedMessage().envelope
        require(envelope.sourceStore == StudyStoreKind.STUDENT_MISTAKES) {
            "Only student mistake outbox events can be marked delivered"
        }
        require(envelope.payload.learnerIdForStudentOutbox() == learnerId) {
            "A learner-scoped pump cannot mark another learner's event delivered"
        }
        require(deliveredAtEpochMillis >= envelope.occurredAtEpochMillis) {
            "Outbox delivery time must not precede the event"
        }
        check(
            dao.markOutboxDelivered(
                learnerId = learnerId,
                eventId = envelope.eventId,
                envelopeCanonicalFingerprint = envelope.canonicalFingerprint,
                deliveredAtEpochMillis = deliveredAtEpochMillis,
            ) == 1,
        ) {
            "Pending outbox event was not found or was already delivered"
        }
    }

    override suspend fun readMasteryRelayReauthenticationStatus():
        StudentMasteryRelayReauthenticationStatus {
        val pending = dao.readPendingMasteryRelayReauthorizationCase(learnerId)
        return if (pending == null) {
            StudentMasteryRelayReauthenticationStatus(
                StudentMasteryRelayReauthenticationState.TRUSTED,
                caseId = null,
            )
        } else {
            StudentMasteryRelayReauthenticationStatus(
                StudentMasteryRelayReauthenticationState.REAUTHENTICATION_REQUIRED,
                caseId = pending.caseId,
            )
        }
    }

    override suspend fun reauthorizeMasteryRelaySource(
        command: StudentMasteryRelayReauthorizationCommand,
    ): StudentMasteryRelayReauthenticationStatus =
        dao.reauthorizeMasteryRelaySource(learnerId, command)
}

private class StudentOutboxInvalidRowException(cause: RuntimeException) :
    SecurityException("Student outbox row is structurally invalid", cause)

private fun RuntimeException.isAuthenticityRowFailure(): Boolean =
    this is StudentOutboxInvalidRowException || this is StudentOutboxInvalidProofException

private const val MAX_AUTHENTICITY_REJECTION_SCAN_ROUNDS = 1_024

private fun Any.learnerIdForStudentOutbox(): String =
    when (this) {
        is ProblemRevisionCommittedV1 -> revision.problem.learnerId
        is ProblemLifecycleChangedV1 -> problemRevision.problem.learnerId
        is ProblemRevisionSupersededV1 -> previousRevision.problem.learnerId
        is ProblemKnowledgeBindingsSnapshotV2 -> problemRevision.problem.learnerId
        is ReviewObservationCapturedV1 -> problemRevision.problem.learnerId
        is ReviewObservationCapturedV2 -> problemRevision.problem.learnerId
        else -> error("Unsupported student mistake outbox payload")
    }
