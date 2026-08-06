package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryInboundDisposition
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess.bindVerifiedStudentOutboxDelivery
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess.verifyLearnerMasteryOutboxDelivery
import com.tingyun.smartmistakebook.core.mastery.database.MasteryOutboxAuthenticityVerifier
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOutboxDelivery
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryRelayCapability
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionCommittedV1
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeInboundDisposition
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.bindVerifiedLearnerMasteryOutboxDelivery
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.verifyStudentOutboxDelivery
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeRelayCapability
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentOutboxAuthenticityVerifier

/**
 * Moves immutable envelopes between the independent student-mistake and learner-mastery stores.
 *
 * Neither database module owns the other store's lifecycle or calls its API. Delivery remains
 * replayable: the destination applies first, then this coordinator acknowledges the source.
 */
internal class LocalLearningAuthorityRelay(
    private val learnerId: String,
    private val studentMistakes: StudentMistakeRelayCapability,
    private val learnerMastery: LearnerMasteryRelayCapability,
    private val studentOutboxAuthenticityVerifier: StudentOutboxAuthenticityVerifier,
    private val masteryOutboxAuthenticityVerifier: MasteryOutboxAuthenticityVerifier,
) {
    init {
        require(learnerId.isNotBlank())
        require(
            studentMistakes.learnerId == learnerId &&
                learnerMastery.learnerId == learnerId,
        ) {
            "Relay capabilities must share the runtime learner scope"
        }
    }

    suspend fun drain(
        nowEpochMillis: Long,
        batchSize: Int,
    ): LocalLearningAuthorityRelayDrainResult {
        require(nowEpochMillis >= 0L) {
            "Relay time must not be negative"
        }
        require(batchSize in 1..LearnerMasteryRelayCapability.MAX_RELAY_BATCH_SIZE) {
            "Relay batch size is outside the supported range"
        }

        var inboundApplied = 0
        var inboundDuplicates = 0
        var inboundReferenceReceipts = 0
        studentMistakes.readPending(
            nowEpochMillis = nowEpochMillis,
            limit = batchSize,
        ).forEach { message ->
            val envelope =
                CrossStoreEventEnvelope(
                    eventId = message.eventId,
                    sourceStore = message.sourceStore,
                    destinationStore = message.destinationStore,
                    aggregateId = message.aggregateId,
                    aggregateVersion = message.aggregateVersion,
                    occurredAtEpochMillis = message.occurredAtEpochMillis,
                    idempotencyKey = message.idempotencyKey,
                    sourceStoreGeneration = message.sourceStoreGeneration,
                    payload = message.payload,
                    payloadType = message.payloadType,
                    payloadVersion = message.payloadVersion,
                    payloadCanonicalFingerprint = message.payloadCanonicalFingerprint,
                ).also { reconstructed ->
                    check(
                        reconstructed.canonicalFingerprint ==
                            message.envelopeCanonicalFingerprint,
                    ) {
                        "Owner-issued student delivery changed during relay"
                    }
                }
            val sourceVerifiedMessage =
                verifyStudentOutboxDelivery(
                    message,
                    studentOutboxAuthenticityVerifier,
                )
            val verifiedMessage =
                bindVerifiedStudentOutboxDelivery(sourceVerifiedMessage)
            val isReferenceReceipt = envelope.payload is ProblemRevisionCommittedV1
            when (
                learnerMastery.accept(
                    message = verifiedMessage,
                    receivedAtEpochMillis = nowEpochMillis,
                )
            ) {
                LearnerMasteryInboundDisposition.APPLIED -> inboundApplied += 1
                LearnerMasteryInboundDisposition.DUPLICATE -> inboundDuplicates += 1
                LearnerMasteryInboundDisposition.CONFLICT ->
                    error(
                        "Conflicting student outbox event '${envelope.eventId}' " +
                            "was not acknowledged",
                    )
            }
            if (isReferenceReceipt) {
                inboundReferenceReceipts += 1
            }
            studentMistakes.markDelivered(
                message = message,
                deliveredAtEpochMillis = nowEpochMillis,
            )
        }

        var outboundApplied = 0
        var outboundDuplicates = 0
        learnerMastery.readPending(
            nowEpochMillis = nowEpochMillis,
            limit = batchSize,
        ).forEach { message ->
            val envelope = message.toEnvelope()
            val sourceVerifiedMessage =
                verifyLearnerMasteryOutboxDelivery(
                    message,
                    masteryOutboxAuthenticityVerifier,
                )
            val verifiedMessage =
                bindVerifiedLearnerMasteryOutboxDelivery(sourceVerifiedMessage)
            when (
                studentMistakes.acceptInbound(
                    message = verifiedMessage,
                    receivedAtEpochMillis = nowEpochMillis,
                )
            ) {
                StudentMistakeInboundDisposition.APPLIED -> outboundApplied += 1
                StudentMistakeInboundDisposition.DUPLICATE -> outboundDuplicates += 1
                StudentMistakeInboundDisposition.REAUTHENTICATION_REQUIRED ->
                    error(
                        "Learner-mastery source rotation requires explicit reauthorization; " +
                            "event '${envelope.eventId}' was not acknowledged",
                    )
            }
            learnerMastery.markDelivered(
                message = message,
                deliveredAtEpochMillis = nowEpochMillis,
            )
        }

        return LocalLearningAuthorityRelayDrainResult(
            inboundApplied = inboundApplied,
            inboundDuplicates = inboundDuplicates,
            inboundReferenceReceipts = inboundReferenceReceipts,
            outboundApplied = outboundApplied,
            outboundDuplicates = outboundDuplicates,
        )
    }

    private fun LearnerMasteryOutboxDelivery.toEnvelope(): CrossStoreEventEnvelope =
        CrossStoreEventEnvelope(
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
        ).also { envelope ->
            check(envelope.canonicalFingerprint == envelopeCanonicalFingerprint) {
                "Owner-issued learner-mastery delivery changed during relay"
            }
        }
}

internal data class LocalLearningAuthorityRelayDrainResult(
    val inboundApplied: Int,
    val inboundDuplicates: Int,
    val inboundReferenceReceipts: Int,
    val outboundApplied: Int,
    val outboundDuplicates: Int,
) {
    init {
        require(
            listOf(
                inboundApplied,
                inboundDuplicates,
                inboundReferenceReceipts,
                outboundApplied,
                outboundDuplicates,
            ).all { it >= 0 },
        ) {
            "Relay counts must not be negative"
        }
    }
}
