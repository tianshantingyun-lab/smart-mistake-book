package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Transaction

/**
 * Authenticated mastery inbox apply transaction shared by the main DAO and message journal.
 */
@Dao
internal abstract class StudentInboxWriteDao : StudentReviewSessionWriteDao() {
    @Transaction
    open suspend fun acceptInbox(
        bundle: ApplyStudentInboxBundle,
    ): StudentMistakeInboundDisposition {
        val message = bundle.message
        val authenticatedReceipt = bundle.authenticatedReceipt
        check(
            listOf(
                bundle.completedQueueItemId,
                bundle.expectedLearnerId,
                bundle.expectedSubject,
                bundle.expectedProblemId,
                bundle.expectedRevisionId,
                bundle.learningEvidence,
                bundle.attemptRecordedAtEpochMillis,
            ).all { it == null } ||
                listOf(
                bundle.completedQueueItemId,
                bundle.expectedLearnerId,
                bundle.expectedSubject,
                bundle.expectedProblemId,
                bundle.expectedRevisionId,
                bundle.learningEvidence,
                bundle.attemptRecordedAtEpochMillis,
                ).all { it != null },
        ) {
            "Learning-attempt inbox metadata is partial"
        }
        check(
            authenticatedReceipt.eventId == message.eventId &&
                authenticatedReceipt.envelopeCanonicalFingerprint ==
                message.envelopeCanonicalFingerprint &&
                authenticatedReceipt.sourceStoreGeneration ==
                message.sourceStoreGeneration &&
                authenticatedReceipt.receivedAtEpochMillis == message.receivedAtEpochMillis,
        ) {
            "Authenticated mastery receipt does not bind the inbox row"
        }

        val sourceBinding = readMasteryRelaySourceBinding(authenticatedReceipt.learnerId)
        if (sourceBinding == null) {
            insertMasteryRelaySourceBinding(
                StudentMasteryRelaySourceBindingEntity(
                    learnerId = authenticatedReceipt.learnerId,
                    sourceStore = authenticatedReceipt.sourceStore,
                    sourceStoreGeneration = authenticatedReceipt.sourceStoreGeneration,
                    relayEpoch = authenticatedReceipt.relayEpoch,
                    issuerKeyId = authenticatedReceipt.issuerKeyId,
                    algorithmVersion = authenticatedReceipt.algorithmVersion,
                    firstVerificationReceiptCanonicalFingerprint =
                        authenticatedReceipt.verificationReceiptCanonicalFingerprint,
                    pinnedAtEpochMillis = authenticatedReceipt.receivedAtEpochMillis,
                ),
            )
        }
        val durableBinding = checkNotNull(
            readMasteryRelaySourceBinding(authenticatedReceipt.learnerId),
        ) { "Mastery relay source binding was not durably pinned" }
        if (!durableBinding.matches(authenticatedReceipt)) {
            appendMasteryRelayReauthorizationCase(durableBinding, authenticatedReceipt)
            return StudentMistakeInboundDisposition.REAUTHENTICATION_REQUIRED
        }

        val existing =
            readInboxByIdempotency(
                sourceStore = message.sourceStore,
                sourceStoreGeneration = message.sourceStoreGeneration,
                idempotencyKey = message.idempotencyKey,
            )
        if (existing != null) {
            check(existing.envelopeCanonicalFingerprint == message.envelopeCanonicalFingerprint) {
                "Inbox idempotency key was reused for a different envelope"
            }
            val existingReceipt = checkNotNull(
                readAuthenticatedMasteryInboxReceipt(existing.eventId),
            ) { "Authenticated inbox row is missing its append-only verification receipt" }
            check(
                existingReceipt.verificationReceiptCanonicalFingerprint ==
                    authenticatedReceipt.verificationReceiptCanonicalFingerprint &&
                    existingReceipt.scopeCanonicalFingerprint ==
                    authenticatedReceipt.scopeCanonicalFingerprint,
            ) { "Inbox replay changed its authenticated receipt or command scope" }
            return StudentMistakeInboundDisposition.DUPLICATE
        }
        check(insertInbox(message) != -1L) {
            "Inbox event id collided with a different message"
        }

        bundle.completedQueueItemId?.let { queueItemId ->
            val item = checkNotNull(readReviewQueueItem(queueItemId)) {
                "Learning-attempt receipt references a missing review queue item"
            }
            check(
                item.learnerId == bundle.expectedLearnerId &&
                    item.basisRevisionId == bundle.expectedRevisionId,
            ) {
                "Learning-attempt receipt does not match the queued problem"
            }
            val problem = checkNotNull(readProblemByPracticeUnit(item.practiceUnitId)) {
                "Learning-attempt receipt references an unknown problem"
            }
            check(
                problem.problemId == bundle.expectedProblemId &&
                    problem.subject == bundle.expectedSubject &&
                    problem.learnerId == bundle.expectedLearnerId &&
                    authenticatedReceipt.problemId == problem.problemId &&
                    authenticatedReceipt.subject == problem.subject &&
                    authenticatedReceipt.problemRevisionId == item.basisRevisionId,
            ) {
                "Learning-attempt receipt crosses the queued learner, subject, or problem scope"
            }
            val currentState =
                enumValueOrCorrupt<StudentReviewQueueState>(
                    item.state,
                    "review queue state",
                )
            if (currentState == StudentReviewQueueState.READY ||
                currentState == StudentReviewQueueState.PRESENTED
            ) {
                check(
                    updateReviewQueueItem(
                        item.copy(
                            state = StudentReviewQueueState.COMPLETED.name,
                            stateChangedAtEpochMillis = message.receivedAtEpochMillis,
                        ),
                    ) == 1,
                ) {
                    "Review queue completion did not affect exactly one row"
                }
            }
            val evidence = checkNotNull(bundle.learningEvidence)
            val attemptRecordedAtEpochMillis =
                checkNotNull(bundle.attemptRecordedAtEpochMillis)
            val nextCandidate =
                nextReviewCandidate(
                    item = item,
                    availableAtEpochMillis = attemptRecordedAtEpochMillis,
                    dueAtEpochMillis = null,
                    updatedAtEpochMillis = attemptRecordedAtEpochMillis,
                    reasonCode = LEARNING_ATTEMPT_REASON,
                    sourceEvidence = evidence,
                    preserveExistingEvidence = false,
                    replaceSchedule = false,
                )
            applyReviewCandidate(nextCandidate, recordChange = false)
            bumpChangeVersion(checkNotNull(bundle.expectedLearnerId))
        }

        insertAuthenticatedMasteryInboxReceipt(authenticatedReceipt)

        check(
            updateInbox(
                message.copy(
                    applyState = "APPLIED",
                    appliedAtEpochMillis = message.receivedAtEpochMillis,
                ),
            ) == 1,
        ) {
            "Inbox apply state did not affect exactly one row"
        }
        return StudentMistakeInboundDisposition.APPLIED
    }
}
