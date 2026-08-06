package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentInteractionEventEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentInteractionHeadEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentInteractionScopeEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentHostWorkEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentPolicyEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorEvidenceRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorFreeResponseOutboxEntity
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import kotlinx.coroutines.flow.map

internal const val FREE_RESPONSE_OUTBOX_SCHEMA = "tutor-free-response-outbox-v1"
internal const val FREE_RESPONSE_OUTBOX_NEEDS_DISPATCH = "NEEDS_DISPATCH"
internal const val FREE_RESPONSE_OUTBOX_IN_FLIGHT = "IN_FLIGHT"
internal const val FREE_RESPONSE_OUTBOX_COMPLETED = "COMPLETED"
internal const val FREE_RESPONSE_OUTBOX_FAILED_CLOSED = "FAILED_CLOSED"
internal const val TUTOR_FREE_RESPONSE_MAX_DISPATCH_ATTEMPTS = 3
internal const val TUTOR_FREE_RESPONSE_OUTBOX_RETENTION_MILLIS = 15 * 60 * 1_000L
internal const val TUTOR_FREE_RESPONSE_FIRST_RETRY_DELAY_MILLIS = 5_000L
internal const val TUTOR_FREE_RESPONSE_SECOND_RETRY_DELAY_MILLIS = 30_000L

internal fun TutorCurrentPolicyEntity.invalidatesFreeResponse(
    candidate: TutorCurrentPolicyEntity,
): Boolean =
    explanationMode != candidate.explanationMode ||
        modeVersion != candidate.modeVersion ||
        learningWritesAllowed != candidate.learningWritesAllowed ||
        learningWritePermissionVersion != candidate.learningWritePermissionVersion ||
        visualIntent != candidate.visualIntent ||
        visualIntentVersion != candidate.visualIntentVersion

internal fun TutorCurrentInteractionScopeEntity.matchesFreeResponseWork(
    work: CurrentTutorSessionHostWorkRecord,
): Boolean =
    scopeId == work.activeScopeId &&
        learnerId == work.learnerId &&
        conversationId == work.sessionId &&
        authorityConversationId == work.authorityConversationId &&
        authorityConversationGeneration == work.authorityConversationGeneration &&
        authorityConversationStateVersion == work.authorityConversationStateVersion &&
        authorityTurnReceiptId == work.authorityTurnReceiptId &&
        authorityTurnOrdinal == work.authorityTurnOrdinal &&
        authorityRequestVersion == work.authorityRequestVersion &&
        questionDocumentId == work.questionDocumentId &&
        questionRevisionNumber == work.questionRevisionNumber &&
        subject == work.subject.name &&
        problemAnchorId == work.problemAnchorId &&
        explanationMode == work.explanationMode.name &&
        modeVersion == work.modeVersion &&
        learningWritePermissionVersion == work.learningWritePermissionVersion &&
        turnReferenceId == work.evidenceRequestId &&
        turnOrdinal == work.turnOrdinal &&
        cycleOrdinal == work.cycleOrdinal &&
        requestVersion == work.requestVersion &&
        presentationFingerprint == work.constrainedTutorContentFingerprint

internal fun CurrentTutorSessionHostWorkRecord.matches(
    command: ClaimCurrentTutorFreeResponseActionCommand,
): Boolean =
    learnerId == command.learnerId &&
        sessionId == command.sessionId &&
        workId == command.expectedWorkId &&
        stateVersion == command.expectedWorkStateVersion &&
        stateFingerprint == command.expectedWorkStateFingerprint &&
        presentationToken == command.presentationToken &&
        evidenceRequestId == command.evidenceRequestId

internal fun CurrentTutorSessionHostWorkRecord.matches(
    query: CurrentTutorFreeResponseActionClaimQuery,
): Boolean =
    learnerId == query.learnerId &&
        sessionId == query.sessionId &&
        workId == query.expectedWorkId &&
        stateVersion == query.expectedWorkStateVersion &&
        stateFingerprint == query.expectedWorkStateFingerprint &&
        presentationToken == query.presentationToken &&
        evidenceRequestId == query.evidenceRequestId

internal fun TutorFreeResponseOutboxEntity.matchesCurrentWork(
    work: CurrentTutorSessionHostWorkRecord,
): Boolean =
    learnerId == work.learnerId &&
        sessionId == work.sessionId &&
        authorityConversationId == work.authorityConversationId &&
        conversationGeneration == work.authorityConversationGeneration &&
        workId == work.workId &&
        workStateVersion == work.stateVersion &&
        workStateFingerprint == work.stateFingerprint &&
        presentationToken == work.presentationToken &&
        evidenceRequestId == work.evidenceRequestId

internal fun TutorFreeResponseOutboxEntity.matches(
    command: ClaimCurrentTutorFreeResponseActionCommand,
    work: CurrentTutorSessionHostWorkRecord,
    expectedAnswerBinding: String,
): Boolean =
    matchesCurrentWork(work) &&
        actionToken == command.actionToken &&
        actionExpiresAtEpochMillis == command.actionExpiresAtEpochMillis &&
        answerBinding == expectedAnswerBinding &&
        payloadFingerprint == command.outboxPayloadFingerprint(
            work,
            expectedAnswerBinding,
            canonicalOccurredAtEpochMillis,
        )

internal fun TutorFreeResponseOutboxEntity.matches(
    query: CurrentTutorFreeResponseActionClaimQuery,
    work: CurrentTutorSessionHostWorkRecord,
): Boolean =
    matchesCurrentWork(work) &&
        actionToken == query.actionToken

internal fun TutorFreeResponseOutboxEntity.dispatchState(
    nowEpochMillis: Long,
): CurrentTutorFreeResponseDispatchState =
    when (status) {
        FREE_RESPONSE_OUTBOX_NEEDS_DISPATCH ->
            CurrentTutorFreeResponseDispatchState.NEEDS_DISPATCH
        FREE_RESPONSE_OUTBOX_IN_FLIGHT -> if (
            leaseExpiresAtEpochMillis == null || leaseExpiresAtEpochMillis <= nowEpochMillis
        ) {
            CurrentTutorFreeResponseDispatchState.NEEDS_DISPATCH
        } else {
            CurrentTutorFreeResponseDispatchState.IN_FLIGHT
        }
        FREE_RESPONSE_OUTBOX_COMPLETED -> CurrentTutorFreeResponseDispatchState.COMPLETED
        FREE_RESPONSE_OUTBOX_FAILED_CLOSED ->
            CurrentTutorFreeResponseDispatchState.FAILED_CLOSED
        else -> CurrentTutorFreeResponseDispatchState.FAILED_CLOSED
    }

internal fun TutorFreeResponseOutboxEntity.mustFailClosedOnObservation(
    nowEpochMillis: Long,
): Boolean =
    nowEpochMillis >= discardAfterEpochMillis ||
        hasFreeResponseClockRollback(nowEpochMillis) ||
        hasInconsistentInFlightLeaseTuple() ||
        dispatchAttemptCount < 0 ||
        (
            dispatchAttemptCount >= TUTOR_FREE_RESPONSE_MAX_DISPATCH_ATTEMPTS &&
                (
                    status == FREE_RESPONSE_OUTBOX_NEEDS_DISPATCH ||
                        leaseExpiresAtEpochMillis == null ||
                        leaseExpiresAtEpochMillis <= nowEpochMillis
                    )
            )

internal fun TutorFreeResponseOutboxEntity.hasInconsistentInFlightLeaseTuple(): Boolean =
    status == FREE_RESPONSE_OUTBOX_IN_FLIGHT &&
        (
            leaseOwnerId == null ||
                leaseGenerationId == null ||
                leaseToken == null ||
                leaseExpiresAtEpochMillis == null
            )

internal fun TutorFreeResponseOutboxEntity.hasFreeResponseClockRollback(
    nowEpochMillis: Long,
): Boolean = nowEpochMillis < claimedAtEpochMillis || nowEpochMillis < updatedAtEpochMillis

internal fun freeResponseRetryDelayMillis(dispatchAttemptCount: Int): Long = when {
    dispatchAttemptCount <= 1 -> TUTOR_FREE_RESPONSE_FIRST_RETRY_DELAY_MILLIS
    else -> TUTOR_FREE_RESPONSE_SECOND_RETRY_DELAY_MILLIS
}

internal fun checkedFreeResponseDeadline(nowEpochMillis: Long, delayMillis: Long): Long? =
    if (nowEpochMillis > Long.MAX_VALUE - delayMillis) null else nowEpochMillis + delayMillis

internal fun ClaimCurrentTutorFreeResponseActionCommand.outboxPayloadFingerprint(
    work: CurrentTutorSessionHostWorkRecord,
    answerBinding: String,
    canonicalOccurredAtEpochMillis: Long = occurredAtEpochMillis,
): String = CanonicalSha256(FREE_RESPONSE_OUTBOX_SCHEMA)
    .field("learnerId", learnerId)
    .field("sessionId", sessionId)
    .field("authorityConversationId", work.authorityConversationId)
    .field("conversationGeneration", work.authorityConversationGeneration)
    .field("workId", expectedWorkId)
    .field("workStateVersion", expectedWorkStateVersion)
    .field("workStateFingerprint", expectedWorkStateFingerprint)
    .field("presentationToken", presentationToken)
    .field("evidenceRequestId", evidenceRequestId)
    .field("actionToken", actionToken)
    .field("actionExpiresAtEpochMillis", actionExpiresAtEpochMillis)
    .field("answerBinding", answerBinding)
    .field("canonicalOccurredAtEpochMillis", canonicalOccurredAtEpochMillis)
    .finish()

internal fun ClaimCurrentTutorFreeResponseActionCommand.toOutboxEntity(
    work: CurrentTutorSessionHostWorkRecord,
    encrypted: TutorFreeResponseEncryptedAnswer,
    answerBinding: String,
    payloadFingerprint: String,
    discardAfterEpochMillis: Long,
    now: Long,
): TutorFreeResponseOutboxEntity = TutorFreeResponseOutboxEntity(
    learnerId = learnerId,
    sessionId = sessionId,
    authorityConversationId = work.authorityConversationId,
    conversationGeneration = work.authorityConversationGeneration,
    actionToken = actionToken,
    actionExpiresAtEpochMillis = actionExpiresAtEpochMillis,
    workId = expectedWorkId,
    workStateVersion = expectedWorkStateVersion,
    workStateFingerprint = expectedWorkStateFingerprint,
    presentationToken = presentationToken,
    evidenceRequestId = evidenceRequestId,
    answerBinding = answerBinding,
    payloadFingerprint = payloadFingerprint,
    canonicalOccurredAtEpochMillis = occurredAtEpochMillis,
    status = FREE_RESPONSE_OUTBOX_NEEDS_DISPATCH,
    encryptedAnswer = encrypted.ciphertext,
    nonce = encrypted.nonce,
    keyVersion = encrypted.keyVersion,
    dispatchAttemptCount = 0,
    nextDispatchAtEpochMillis = now,
    discardAfterEpochMillis = discardAfterEpochMillis,
    leaseOwnerId = null,
    leaseGenerationId = null,
    leaseToken = null,
    leaseExpiresAtEpochMillis = null,
    candidateIdempotencyKey = null,
    candidateReceiptFingerprint = null,
    claimedAtEpochMillis = now,
    completedAtEpochMillis = null,
    updatedAtEpochMillis = now,
)

internal fun freeResponseClaimResult(
    disposition: CurrentTutorFreeResponseActionClaimDisposition,
    canonicalOccurredAtEpochMillis: Long?,
    recordedAtEpochMillis: Long,
) = CurrentTutorFreeResponseActionClaimResult(
    disposition = disposition,
    canonicalOccurredAtEpochMillis = canonicalOccurredAtEpochMillis,
    recordedAtEpochMillis = recordedAtEpochMillis,
)

internal fun freeResponseOutboxAad(
    command: ClaimCurrentTutorFreeResponseActionCommand,
    work: CurrentTutorSessionHostWorkRecord,
    answerBinding: String,
): ByteArray = canonicalFreeResponseOutboxAad(
    FREE_RESPONSE_OUTBOX_SCHEMA,
    command.learnerId,
    command.sessionId,
    work.authorityConversationId,
    work.authorityConversationGeneration.toString(),
    command.expectedWorkId,
    command.expectedWorkStateVersion.toString(),
    command.expectedWorkStateFingerprint,
    command.presentationToken,
    command.evidenceRequestId,
    command.actionToken,
    command.actionExpiresAtEpochMillis.toString(),
    answerBinding,
    command.occurredAtEpochMillis.toString(),
)

internal fun freeResponseAnswerBindingContext(
    command: ClaimCurrentTutorFreeResponseActionCommand,
    work: CurrentTutorSessionHostWorkRecord,
): ByteArray = canonicalFreeResponseOutboxAad(
    "$FREE_RESPONSE_OUTBOX_SCHEMA:answer-binding",
    command.learnerId,
    command.sessionId,
    work.authorityConversationId,
    work.authorityConversationGeneration.toString(),
    command.expectedWorkId,
    command.expectedWorkStateVersion.toString(),
    command.expectedWorkStateFingerprint,
    command.presentationToken,
    command.evidenceRequestId,
    command.actionToken,
    command.actionExpiresAtEpochMillis.toString(),
)

internal fun freeResponseOutboxAad(
    outbox: TutorFreeResponseOutboxEntity,
): ByteArray = canonicalFreeResponseOutboxAad(
    FREE_RESPONSE_OUTBOX_SCHEMA,
    outbox.learnerId,
    outbox.sessionId,
    outbox.authorityConversationId,
    outbox.conversationGeneration.toString(),
    outbox.workId,
    outbox.workStateVersion.toString(),
    outbox.workStateFingerprint,
    outbox.presentationToken,
    outbox.evidenceRequestId,
    outbox.actionToken,
    outbox.actionExpiresAtEpochMillis.toString(),
    outbox.answerBinding,
    outbox.canonicalOccurredAtEpochMillis.toString(),
)

internal fun freeResponseAnswerBindingContext(
    outbox: TutorFreeResponseOutboxEntity,
): ByteArray = canonicalFreeResponseOutboxAad(
    "$FREE_RESPONSE_OUTBOX_SCHEMA:answer-binding",
    outbox.learnerId,
    outbox.sessionId,
    outbox.authorityConversationId,
    outbox.conversationGeneration.toString(),
    outbox.workId,
    outbox.workStateVersion.toString(),
    outbox.workStateFingerprint,
    outbox.presentationToken,
    outbox.evidenceRequestId,
    outbox.actionToken,
    outbox.actionExpiresAtEpochMillis.toString(),
)

internal fun canonicalFreeResponseOutboxAad(vararg fields: String): ByteArray {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { data ->
        data.writeInt(fields.size)
        fields.forEach { field ->
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            try {
                data.writeInt(bytes.size)
                data.write(bytes)
            } finally {
                Arrays.fill(bytes, 0)
            }
        }
    }
    return output.toByteArray()
}

internal fun hostWorkResult(
    disposition: CurrentTutorSessionHostWorkWriteDisposition,
    entity: TutorCurrentHostWorkEntity?,
    recordedAtEpochMillis: Long,
) = CurrentTutorSessionHostWorkWriteResult(
    disposition = disposition,
    record = entity?.toRecord(),
    recordedAtEpochMillis = recordedAtEpochMillis,
)

internal fun policyWriteResult(
    disposition: CurrentTutorSessionHostWorkWriteDisposition,
    entity: TutorCurrentPolicyEntity?,
    recordedAtEpochMillis: Long,
) = CurrentTutorSessionPolicyWriteResult(
    disposition = disposition,
    record = entity?.toRecord(),
    recordedAtEpochMillis = recordedAtEpochMillis,
)

internal fun PersistCurrentTutorSessionPolicyCommand.toEntity(
    updatedAtEpochMillis: Long,
): TutorCurrentPolicyEntity = TutorCurrentPolicyEntity(
    learnerId = learnerId,
    sessionId = sessionId,
    explanationMode = explanationMode.name,
    modeVersion = modeVersion,
    learningWritesAllowed = learningWritesAllowed,
    learningWritePermissionVersion = learningWritePermissionVersion,
    visualIntent = visualIntent.name,
    visualIntentVersion = visualIntentVersion,
    stateFingerprint = CanonicalSha256("current-tutor-policy-state-v1")
        .field("learnerId", learnerId)
        .field("sessionId", sessionId)
        .field("explanationMode", explanationMode.name)
        .field("modeVersion", modeVersion)
        .field("learningWritesAllowed", learningWritesAllowed)
        .field("learningWritePermissionVersion", learningWritePermissionVersion)
        .field("visualIntent", visualIntent.name)
        .field("visualIntentVersion", visualIntentVersion)
        .finish(),
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun TutorCurrentPolicyEntity.matches(
    command: PersistCurrentTutorSessionPolicyCommand,
): Boolean =
    learnerId == command.learnerId &&
        sessionId == command.sessionId &&
        explanationMode == command.explanationMode.name &&
        modeVersion == command.modeVersion &&
        learningWritesAllowed == command.learningWritesAllowed &&
        learningWritePermissionVersion == command.learningWritePermissionVersion &&
        visualIntent == command.visualIntent.name &&
        visualIntentVersion == command.visualIntentVersion

internal fun TutorCurrentPolicyEntity.matchesExpected(
    command: StageCurrentTutorSessionHostWorkCommand,
): Boolean =
    learnerId == command.learnerId &&
        sessionId == command.sessionId &&
        explanationMode == command.explanationMode.name &&
        modeVersion == command.modeVersion &&
        learningWritesAllowed == command.learningWritesAllowed &&
        learningWritePermissionVersion == command.learningWritePermissionVersion &&
        visualIntent == command.visualIntent.name &&
        visualIntentVersion == command.visualIntentVersion &&
        stateFingerprint == command.expectedPolicyStateFingerprint

internal fun TutorCurrentPolicyEntity.matchesExpected(
    command: MarkCurrentTutorSessionHostWorkActiveCommand,
    work: TutorCurrentHostWorkEntity,
): Boolean =
    learnerId == command.learnerId &&
        sessionId == command.sessionId &&
        explanationMode == work.explanationMode &&
        modeVersion == work.modeVersion &&
        learningWritesAllowed == work.learningWritesAllowed &&
        learningWritePermissionVersion == work.learningWritePermissionVersion &&
        visualIntent == work.visualIntent &&
        visualIntentVersion == work.visualIntentVersion &&
        stateFingerprint == command.expectedPolicyStateFingerprint

internal fun PersistCurrentTutorSessionPolicyCommand.isStrictlyNewerThan(
    current: TutorCurrentPolicyEntity,
): Boolean {
    if (learnerId != current.learnerId || sessionId != current.sessionId) return false
    val versions = listOf(
        modeVersion to current.modeVersion,
        learningWritePermissionVersion to current.learningWritePermissionVersion,
        visualIntentVersion to current.visualIntentVersion,
    )
    return versions.all { (next, previous) -> next >= previous } &&
        versions.any { (next, previous) -> next > previous }
}

internal fun TutorCurrentPolicyEntity.toRecord() = CurrentTutorSessionPolicyRecord(
    learnerId = learnerId,
    sessionId = sessionId,
    explanationMode = TutorExplanationMode.valueOf(explanationMode),
    modeVersion = modeVersion,
    learningWritesAllowed = learningWritesAllowed,
    learningWritePermissionVersion = learningWritePermissionVersion,
    visualIntent = TutorCurrentSessionVisualIntent.valueOf(visualIntent),
    visualIntentVersion = visualIntentVersion,
    stateFingerprint = stateFingerprint,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun StageCurrentTutorSessionHostWorkCommand.hasValidContentBinding(): Boolean {
    val expectedPayload = CanonicalSha256("current-tutor-host-work-payload-v1")
        .field("activationFingerprint", targetActivationFingerprint)
        .field("taskRequestFingerprint", modelTaskRequestFingerprint)
        .field("authorityTurnReceiptId", authorityTurnReceiptId)
        .field("authorityDirectiveFingerprint", authorityDirectiveFingerprint)
        .field("constrainedTutorContentFingerprint", constrainedTutorContentFingerprint)
        .field("learningWritePermissionVersion", learningWritePermissionVersion)
        .field("visualIntent", visualIntent.name)
        .field("visualIntentVersion", visualIntentVersion)
        .finish()
    if (payloadFingerprint != expectedPayload) return false
    return presentationToken == CanonicalSha256("current-tutor-presentation-token-v1")
        .field("activationFingerprint", targetActivationFingerprint)
        .field("payloadFingerprint", expectedPayload)
        .finish()
}

internal fun StageCurrentTutorSessionHostWorkCommand.toEntity(
    createdAtEpochMillis: Long,
    stateVersion: Long,
    updatedAtEpochMillis: Long,
): TutorCurrentHostWorkEntity {
    val stateFingerprint = hostWorkStateFingerprint(
        workId = workId,
        payloadFingerprint = payloadFingerprint,
        status = CurrentTutorSessionHostWorkStatus.STAGED,
        activeScopeId = null,
        revocationReason = null,
        stateVersion = stateVersion,
        learningWritesAllowed = learningWritesAllowed,
        learningWritePermissionVersion = learningWritePermissionVersion,
        visualIntent = visualIntent.name,
        visualIntentVersion = visualIntentVersion,
        authorityDirectiveFingerprint = authorityDirectiveFingerprint,
        constrainedTutorContentFingerprint = constrainedTutorContentFingerprint,
    )
    return TutorCurrentHostWorkEntity(
        workId = workId,
        learnerId = learnerId,
        sessionId = sessionId,
        questionDocumentId = questionDocumentId,
        questionRevisionNumber = questionRevisionNumber,
        subject = subject.name,
        authorityConversationId = authorityConversationId,
        authorityConversationGeneration = authorityConversationGeneration,
        authorityConversationStateVersion = authorityConversationStateVersion,
        authorityTurnReceiptId = authorityTurnReceiptId,
        authorityTurnOrdinal = authorityTurnOrdinal,
        authorityRequestVersion = authorityRequestVersion,
        authorityDirectiveFingerprint = authorityDirectiveFingerprint,
        modelTaskRequestId = modelTaskRequestId,
        modelTaskRequestFingerprint = modelTaskRequestFingerprint,
        problemAnchorId = problemAnchorId,
        explanationMode = explanationMode.name,
        modeVersion = modeVersion,
        learningWritesAllowed = learningWritesAllowed,
        learningWritePermissionVersion = learningWritePermissionVersion,
        visualIntent = visualIntent.name,
        visualIntentVersion = visualIntentVersion,
        cycleOrdinal = cycleOrdinal,
        turnOrdinal = turnOrdinal,
        attemptOrdinal = attemptOrdinal,
        requestVersion = requestVersion,
        evidenceRequestId = evidenceRequestId,
        pendingInteractionKind = pendingInteractionKind?.name,
        targetScopeId = targetScopeId,
        targetActivationFingerprint = targetActivationFingerprint,
        constrainedTutorContentFingerprint = constrainedTutorContentFingerprint,
        activeScopeId = null,
        presentationToken = presentationToken,
        payloadFingerprint = payloadFingerprint,
        status = CurrentTutorSessionHostWorkStatus.STAGED.name,
        revocationReason = null,
        stateVersion = stateVersion,
        stateFingerprint = stateFingerprint,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

internal fun TutorCurrentHostWorkEntity.transitioned(
    status: CurrentTutorSessionHostWorkStatus,
    activeScopeId: String?,
    revocationReason: CurrentTutorSessionHostWorkRevocationReason?,
    updatedAtEpochMillis: Long,
    learningWritesAllowed: Boolean = this.learningWritesAllowed,
    learningWritePermissionVersion: Long = this.learningWritePermissionVersion,
): TutorCurrentHostWorkEntity {
    check(stateVersion < Long.MAX_VALUE) { "Current Tutor host-work state version overflow" }
    val nextVersion = stateVersion + 1L
    return copy(
        status = status.name,
        activeScopeId = activeScopeId,
        revocationReason = revocationReason?.name,
        stateVersion = nextVersion,
        stateFingerprint = hostWorkStateFingerprint(
            workId = workId,
            payloadFingerprint = payloadFingerprint,
            status = status,
            activeScopeId = activeScopeId,
            revocationReason = revocationReason,
            stateVersion = nextVersion,
            learningWritesAllowed = learningWritesAllowed,
            learningWritePermissionVersion = learningWritePermissionVersion,
            visualIntent = visualIntent,
            visualIntentVersion = visualIntentVersion,
            authorityDirectiveFingerprint = authorityDirectiveFingerprint,
            constrainedTutorContentFingerprint = constrainedTutorContentFingerprint,
        ),
        learningWritesAllowed = learningWritesAllowed,
        learningWritePermissionVersion = learningWritePermissionVersion,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

internal fun hostWorkStateFingerprint(
    workId: String,
    payloadFingerprint: String,
    status: CurrentTutorSessionHostWorkStatus,
    activeScopeId: String?,
    revocationReason: CurrentTutorSessionHostWorkRevocationReason?,
    stateVersion: Long,
    learningWritesAllowed: Boolean,
    learningWritePermissionVersion: Long,
    visualIntent: String,
    visualIntentVersion: Long,
    authorityDirectiveFingerprint: String,
    constrainedTutorContentFingerprint: String,
): String = CanonicalSha256("current-tutor-host-work-state-v1")
    .field("workId", workId)
    .field("payloadFingerprint", payloadFingerprint)
    .field("status", status.name)
    .nullableField("activeScopeId", activeScopeId)
    .nullableField("revocationReason", revocationReason?.name)
    .field("stateVersion", stateVersion)
    .field("learningWritesAllowed", learningWritesAllowed)
    .field("learningWritePermissionVersion", learningWritePermissionVersion)
    .field("visualIntent", visualIntent)
    .field("visualIntentVersion", visualIntentVersion)
    .field("authorityDirectiveFingerprint", authorityDirectiveFingerprint)
    .field("constrainedTutorContentFingerprint", constrainedTutorContentFingerprint)
    .finish()

internal fun TutorCurrentHostWorkEntity.matches(
    command: StageCurrentTutorSessionHostWorkCommand,
): Boolean =
    workId == command.workId &&
        learnerId == command.learnerId &&
        sessionId == command.sessionId &&
        questionDocumentId == command.questionDocumentId &&
        questionRevisionNumber == command.questionRevisionNumber &&
        subject == command.subject.name &&
        authorityConversationId == command.authorityConversationId &&
        authorityConversationGeneration == command.authorityConversationGeneration &&
        authorityConversationStateVersion == command.authorityConversationStateVersion &&
        authorityTurnReceiptId == command.authorityTurnReceiptId &&
        authorityTurnOrdinal == command.authorityTurnOrdinal &&
        authorityRequestVersion == command.authorityRequestVersion &&
        authorityDirectiveFingerprint == command.authorityDirectiveFingerprint &&
        modelTaskRequestId == command.modelTaskRequestId &&
        modelTaskRequestFingerprint == command.modelTaskRequestFingerprint &&
        problemAnchorId == command.problemAnchorId &&
        explanationMode == command.explanationMode.name &&
        modeVersion == command.modeVersion &&
        learningWritesAllowed == command.learningWritesAllowed &&
        learningWritePermissionVersion == command.learningWritePermissionVersion &&
        visualIntent == command.visualIntent.name &&
        visualIntentVersion == command.visualIntentVersion &&
        cycleOrdinal == command.cycleOrdinal &&
        turnOrdinal == command.turnOrdinal &&
        attemptOrdinal == command.attemptOrdinal &&
        requestVersion == command.requestVersion &&
        evidenceRequestId == command.evidenceRequestId &&
        pendingInteractionKind == command.pendingInteractionKind?.name &&
        targetScopeId == command.targetScopeId &&
        targetActivationFingerprint == command.targetActivationFingerprint &&
        constrainedTutorContentFingerprint == command.constrainedTutorContentFingerprint &&
        presentationToken == command.presentationToken &&
        payloadFingerprint == command.payloadFingerprint

internal fun TutorCurrentHostWorkEntity.matchesExpected(
    command: StageCurrentTutorSessionHostWorkCommand,
): Boolean =
    command.expectedStateVersion != null &&
        stateVersion == command.expectedStateVersion &&
        stateFingerprint == command.expectedStateFingerprint

internal fun TutorCurrentHostWorkEntity.matchesExpected(
    command: MarkCurrentTutorSessionHostWorkActiveCommand,
): Boolean =
    workId == command.expectedWorkId &&
        stateVersion == command.expectedStateVersion &&
        stateFingerprint == command.expectedStateFingerprint &&
        targetScopeId == command.expectedTargetScopeId

internal fun TutorCurrentHostWorkEntity.matchesExpected(
    command: RevokeCurrentTutorSessionHostWorkCommand,
): Boolean =
    workId == command.expectedWorkId &&
        stateVersion == command.expectedStateVersion &&
        stateFingerprint == command.expectedStateFingerprint

internal fun StageCurrentTutorSessionHostWorkCommand.isStrictlyNewerThan(
    current: TutorCurrentHostWorkEntity,
): Boolean {
    if (learnerId != current.learnerId || sessionId != current.sessionId) return false
    val next = listOf(
        authorityConversationStateVersion,
        authorityConversationGeneration,
        questionRevisionNumber.toLong(),
        modeVersion,
        requestVersion,
        learningWritePermissionVersion,
        visualIntentVersion,
        cycleOrdinal.toLong(),
        turnOrdinal.toLong(),
        attemptOrdinal.toLong(),
    )
    val previous = listOf(
        current.authorityConversationStateVersion,
        current.authorityConversationGeneration,
        current.questionRevisionNumber.toLong(),
        current.modeVersion,
        current.requestVersion,
        current.learningWritePermissionVersion,
        current.visualIntentVersion,
        current.cycleOrdinal.toLong(),
        current.turnOrdinal.toLong(),
        current.attemptOrdinal.toLong(),
    )
    return next.zip(previous).firstOrNull { (left, right) -> left != right }
        ?.let { (left, right) -> left > right }
        ?: false
}

internal fun TutorCurrentHostWorkEntity.toRecord() = CurrentTutorSessionHostWorkRecord(
    workId = workId,
    learnerId = learnerId,
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    questionRevisionNumber = questionRevisionNumber,
    subject = com.tingyun.smartmistakebook.core.model.SubjectKind.valueOf(subject),
    authorityConversationId = authorityConversationId,
    authorityConversationGeneration = authorityConversationGeneration,
    authorityConversationStateVersion = authorityConversationStateVersion,
    authorityTurnReceiptId = authorityTurnReceiptId,
    authorityTurnOrdinal = authorityTurnOrdinal,
    authorityRequestVersion = authorityRequestVersion,
    authorityDirectiveFingerprint = authorityDirectiveFingerprint,
    modelTaskRequestId = modelTaskRequestId,
    modelTaskRequestFingerprint = modelTaskRequestFingerprint,
    problemAnchorId = problemAnchorId,
    explanationMode = TutorExplanationMode.valueOf(explanationMode),
    modeVersion = modeVersion,
    learningWritesAllowed = learningWritesAllowed,
    learningWritePermissionVersion = learningWritePermissionVersion,
    visualIntent = TutorCurrentSessionVisualIntent.valueOf(visualIntent),
    visualIntentVersion = visualIntentVersion,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
    attemptOrdinal = attemptOrdinal,
    requestVersion = requestVersion,
    evidenceRequestId = evidenceRequestId,
    pendingInteractionKind = pendingInteractionKind?.let(TutorEvidenceRequestKind::valueOf),
    targetScopeId = targetScopeId,
    targetActivationFingerprint = targetActivationFingerprint,
    constrainedTutorContentFingerprint = constrainedTutorContentFingerprint,
    activeScopeId = activeScopeId,
    presentationToken = presentationToken,
    payloadFingerprint = payloadFingerprint,
    status = CurrentTutorSessionHostWorkStatus.valueOf(status),
    revocationReason = revocationReason?.let(CurrentTutorSessionHostWorkRevocationReason::valueOf),
    stateVersion = stateVersion,
    stateFingerprint = stateFingerprint,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun ActivateCurrentTutorInteractionCommand.initialHead(
    updatedAtEpochMillis: Long,
) =
    TutorCurrentInteractionHeadEntity(
        learnerId = learnerId,
        conversationId = conversationId,
        currentScopeId = scopeId,
        stateVersion = 0L,
        stateFingerprint = activationFingerprint,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

internal fun ActivateCurrentTutorInteractionCommand.toEntity(
    createdAtEpochMillis: Long,
) =
    TutorCurrentInteractionScopeEntity(
        scopeId = scopeId,
        learnerId = learnerId,
        conversationId = conversationId,
        conversationGeneration = conversationGeneration,
        conversationStateVersion = conversationStateVersion,
        authorityConversationId = authorityConversationId,
        authorityConversationGeneration = authorityConversationGeneration,
        authorityConversationStateVersion = authorityConversationStateVersion,
        authorityTurnReceiptId = authorityTurnReceiptId,
        authorityTurnOrdinal = authorityTurnOrdinal,
        authorityRequestVersion = authorityRequestVersion,
        questionDocumentId = questionDocumentId,
        questionRevisionNumber = questionRevisionNumber,
        questionDocumentSnapshot = questionDocumentSnapshot,
        questionFingerprint = questionFingerprint,
        subject = subject.name,
        problemAnchorId = problemAnchorId,
        explanationMode = explanationMode.name,
        modeVersion = modeVersion,
        turnReferenceId = turnReferenceId,
        turnOrdinal = turnOrdinal,
        turnGeneration = turnGeneration,
        cycleOrdinal = cycleOrdinal,
        attemptOrdinal = attemptOrdinal,
        hintCount = hintCount,
        answerWasRevealed = answerWasRevealed,
        requestVersion = requestVersion,
        learningWritePermissionVersion = learningWritePermissionVersion,
        presentationFingerprint = presentationFingerprint,
        problemFingerprint = problemFingerprint,
        problemFamilyFingerprint = problemFamilyFingerprint,
        attributionPolicyVersion = attributionPolicyVersion,
        responsePolicyVersion = responsePolicyVersion,
        rubricCanonicalFingerprint = rubricCanonicalFingerprint,
        knowledgeAuthorityFingerprint = knowledgeAuthorityFingerprint,
        evaluator = evaluator,
        evaluatorPolicyFingerprint = evaluatorPolicyFingerprint,
        activationFingerprint = activationFingerprint,
        createdAtEpochMillis = createdAtEpochMillis,
    )

internal fun ActivateCurrentTutorInteractionCommand.isStrictlyNewerThan(
    current: TutorCurrentInteractionScopeEntity,
): Boolean {
    if (learnerId != current.learnerId || conversationId != current.conversationId) return false
    val next = listOf(
        authorityConversationStateVersion,
        conversationGeneration,
        questionRevisionNumber.toLong(),
        turnGeneration,
        modeVersion,
        requestVersion,
        learningWritePermissionVersion,
        cycleOrdinal.toLong(),
        turnOrdinal.toLong(),
    )
    val previous = listOf(
        current.authorityConversationStateVersion,
        current.conversationGeneration,
        current.questionRevisionNumber.toLong(),
        current.turnGeneration,
        current.modeVersion,
        current.requestVersion,
        current.learningWritePermissionVersion,
        current.cycleOrdinal.toLong(),
        current.turnOrdinal.toLong(),
    )
    return next.zip(previous).firstOrNull { (left, right) -> left != right }
        ?.let { (left, right) -> left > right }
        ?: false
}

internal fun AppendCurrentTutorInteractionCommand.isWellFormed(): Boolean {
    val expectedPurposes = when (eventKind) {
        CurrentTutorInteractionEventKind.VISUAL_SELECTION ->
            setOf("RECORD_VISUAL_SELECTION", TRUSTED_SAVED_VISUAL_SELECTION_PURPOSE)
        CurrentTutorInteractionEventKind.CHOICE -> setOf("RECORD_CHOICE")
        CurrentTutorInteractionEventKind.HINT_SHOWN -> setOf(RECORD_HINT_SHOWN_PURPOSE)
        CurrentTutorInteractionEventKind.EVIDENCE_CANCELLATION -> setOf("CANCEL_EVIDENCE")
        CurrentTutorInteractionEventKind.MOVE -> setOf("RECORD_MOVE")
        CurrentTutorInteractionEventKind.SOLUTION_REVEAL -> setOf("REVEAL_SOLUTION")
        CurrentTutorInteractionEventKind.ANSWER_EXPOSURE -> setOf("RECORD_EXPOSURE")
        CurrentTutorInteractionEventKind.SESSION_ANCHOR -> setOf("ANCHOR_SESSION")
        CurrentTutorInteractionEventKind.FREE_RESPONSE_SUBMISSION_CLAIM ->
            setOf(FREE_RESPONSE_SUBMISSION_CLAIM_PURPOSE)
        CurrentTutorInteractionEventKind.OPEN_RESPONSE_CANDIDATE_CLAIM ->
            setOf(OPEN_RESPONSE_CANDIDATE_PURPOSE)
    }
    if (authorizationPurpose !in expectedPurposes) return false
    return when (eventKind) {
        CurrentTutorInteractionEventKind.CHOICE ->
            !diagnosticStemMarkdown.isNullOrBlank() &&
                !selectedChoiceId.isNullOrBlank() &&
                !selectedChoiceMarkdown.isNullOrBlank() &&
                selectionWasCorrect != null &&
                !feedbackMarkdown.isNullOrBlank() &&
                evidenceRequestId == authorizationRequestId
        CurrentTutorInteractionEventKind.VISUAL_SELECTION ->
            evidenceRequestId == authorizationRequestId &&
                !surfaceKind.isNullOrBlank() &&
                !modelTaskRequestId.isNullOrBlank() &&
                !sceneSourceKind.isNullOrBlank() &&
                !sceneTaskRequestId.isNullOrBlank() &&
                !sceneId.isNullOrBlank() &&
                isFingerprint(sceneFingerprint) &&
                !hitProofId.isNullOrBlank() &&
                !panelId.isNullOrBlank() &&
                isFingerprint(frameFingerprint) &&
                stepIndex != null && stepIndex >= 0 && !selectedTargetId.isNullOrBlank() &&
                (
                    authorizationPurpose != TRUSTED_SAVED_VISUAL_SELECTION_PURPOSE ||
                        selectionWasCorrect != null
                )
        CurrentTutorInteractionEventKind.HINT_SHOWN ->
            authorizationRequestId == null && requestedMove == HINT_SHOWN_EVENT_VALUE
        CurrentTutorInteractionEventKind.EVIDENCE_CANCELLATION ->
            evidenceRequestId == authorizationRequestId && evidenceRequestId != null
        CurrentTutorInteractionEventKind.MOVE -> !requestedMove.isNullOrBlank()
        CurrentTutorInteractionEventKind.SOLUTION_REVEAL -> solutionRevealed
        CurrentTutorInteractionEventKind.ANSWER_EXPOSURE ->
            authorizationRequestId != null && !surfaceKind.isNullOrBlank() &&
                !modelTaskRequestId.isNullOrBlank()
        CurrentTutorInteractionEventKind.SESSION_ANCHOR ->
            !targetRevisionRef.isNullOrBlank() && !targetPracticeRef.isNullOrBlank() &&
                !sourceKind.isNullOrBlank()
        CurrentTutorInteractionEventKind.FREE_RESPONSE_SUBMISSION_CLAIM ->
            authorizationRequestId != null && evidenceRequestId == authorizationRequestId
        CurrentTutorInteractionEventKind.OPEN_RESPONSE_CANDIDATE_CLAIM ->
            authorizationRequestId != null && evidenceRequestId == authorizationRequestId
    }
}

internal fun ClaimCurrentTutorFreeResponseActionCommand.toAppendCommand(
    bundle: CurrentTutorInteractionBundle,
    canonicalOccurredAtEpochMillis: Long,
    answerBinding: String,
): AppendCurrentTutorInteractionCommand {
    val scope = bundle.scope
    val payloadFingerprint = CanonicalSha256("current-tutor-free-response-claim-payload-v1")
        .field("actionToken", actionToken)
        .field("actionExpiresAtEpochMillis", actionExpiresAtEpochMillis)
        .field("answerBinding", answerBinding)
        .field("presentationToken", presentationToken)
        .field("evidenceRequestId", evidenceRequestId)
        .field("workId", expectedWorkId)
        .field("workStateFingerprint", expectedWorkStateFingerprint)
        .finish()
    return AppendCurrentTutorInteractionCommand(
        scopeId = scope.scopeId,
        learnerId = scope.learnerId,
        conversationId = scope.conversationId,
        conversationGeneration = scope.conversationGeneration,
        conversationStateVersion = scope.conversationStateVersion,
        questionDocumentId = scope.questionDocumentId,
        questionRevisionNumber = scope.questionRevisionNumber,
        questionFingerprint = scope.questionFingerprint,
        subject = scope.subject,
        problemAnchorId = scope.problemAnchorId,
        explanationMode = scope.explanationMode,
        modeVersion = scope.modeVersion,
        learningWritePermissionVersion = scope.learningWritePermissionVersion,
        turnReferenceId = scope.turnReferenceId,
        turnOrdinal = scope.turnOrdinal,
        turnGeneration = scope.turnGeneration,
        cycleOrdinal = scope.cycleOrdinal,
        attemptOrdinal = scope.attemptOrdinal.coerceAtLeast(1),
        hintCount = scope.hintCount,
        answerWasRevealed = scope.answerWasRevealed,
        expectedStateVersion = bundle.head.stateVersion,
        expectedStateFingerprint = bundle.head.stateFingerprint,
        eventId = actionToken,
        eventKind = CurrentTutorInteractionEventKind.FREE_RESPONSE_SUBMISSION_CLAIM,
        authorizationPurpose = FREE_RESPONSE_SUBMISSION_CLAIM_PURPOSE,
        authorizationRequestId = evidenceRequestId,
        idempotencyKey = actionToken,
        requestVersion = scope.requestVersion,
        payloadFingerprint = payloadFingerprint,
        occurredAtEpochMillis = canonicalOccurredAtEpochMillis,
        evidenceRequestId = evidenceRequestId,
    )
}

internal fun ConsumeCurrentTutorOpenResponseAuthorizationCommand.toAppendCommand() =
    AppendCurrentTutorInteractionCommand(
        scopeId = scopeId,
        learnerId = learnerId,
        conversationId = conversationId,
        conversationGeneration = conversationGeneration,
        conversationStateVersion = conversationStateVersion,
        questionDocumentId = questionDocumentId,
        questionRevisionNumber = questionRevisionNumber,
        questionFingerprint = questionFingerprint,
        subject = subject,
        problemAnchorId = problemAnchorId,
        explanationMode = explanationMode,
        modeVersion = modeVersion,
        learningWritePermissionVersion = learningWritePermissionVersion,
        turnReferenceId = turnReferenceId,
        turnOrdinal = turnOrdinal,
        turnGeneration = turnGeneration,
        cycleOrdinal = cycleOrdinal,
        attemptOrdinal = attemptOrdinal,
        hintCount = hintCount,
        answerWasRevealed = answerWasRevealed,
        expectedStateVersion = expectedStateVersion,
        expectedStateFingerprint = expectedStateFingerprint,
        eventId = candidateIdempotencyKey,
        eventKind = CurrentTutorInteractionEventKind.OPEN_RESPONSE_CANDIDATE_CLAIM,
        authorizationPurpose = OPEN_RESPONSE_CANDIDATE_PURPOSE,
        authorizationRequestId = evidenceRequestId,
        idempotencyKey = candidateIdempotencyKey,
        requestVersion = requestVersion,
        payloadFingerprint = candidateScopeFingerprint,
        occurredAtEpochMillis = occurredAtEpochMillis,
        evidenceRequestId = evidenceRequestId,
    )

internal fun AppendCurrentTutorInteractionCommand.toEntity(
    scope: TutorCurrentInteractionScopeEntity,
    sequence: Long,
    stateFingerprint: String,
    now: Long,
    trustedSelectionWasCorrect: Boolean?,
) = TutorCurrentInteractionEventEntity(
    eventId = eventId,
    scopeId = scopeId,
    learnerId = learnerId,
    conversationId = conversationId,
    conversationGeneration = scope.conversationGeneration,
    questionDocumentId = scope.questionDocumentId,
    questionRevisionNumber = scope.questionRevisionNumber,
    cycleOrdinal = scope.cycleOrdinal,
    turnOrdinal = scope.turnOrdinal,
    modeVersion = scope.modeVersion,
    turnGeneration = scope.turnGeneration,
    eventSequence = sequence,
    committedStateFingerprint = stateFingerprint,
    eventKind = eventKind.name,
    authorizationPurpose = authorizationPurpose,
    authorizationRequestId = authorizationRequestId,
    idempotencyKey = idempotencyKey,
    requestVersion = requestVersion,
    payloadFingerprint = payloadFingerprint,
    occurredAtEpochMillis = occurredAtEpochMillis,
    recordedAtEpochMillis = now,
    diagnosticStemMarkdown = diagnosticStemMarkdown,
    selectedChoiceId = selectedChoiceId,
    selectedChoiceMarkdown = selectedChoiceMarkdown,
    selectionWasCorrect = trustedSelectionWasCorrect,
    feedbackMarkdown = feedbackMarkdown,
    evidenceRequestId = evidenceRequestId,
    requestedMove = requestedMove,
    solutionRevealed = solutionRevealed,
    surfaceKind = surfaceKind,
    modelTaskRequestId = modelTaskRequestId,
    responseOrdinal = responseOrdinal,
    sceneSourceKind = sceneSourceKind,
    sceneTaskRequestId = sceneTaskRequestId,
    sceneId = sceneId,
    sceneFingerprint = sceneFingerprint,
    hitProofId = hitProofId,
    panelId = panelId,
    frameFingerprint = frameFingerprint,
    stepIndex = stepIndex,
    selectedTargetId = selectedTargetId,
    targetRevisionRef = targetRevisionRef,
    targetPracticeRef = targetPracticeRef,
    sourceKind = sourceKind,
)

internal fun TutorCurrentInteractionEventEntity.matches(
    command: AppendCurrentTutorInteractionCommand,
    scope: TutorCurrentInteractionScopeEntity?,
): Boolean =
    scope != null && scope.matchesImmutableBinding(command) &&
        eventId == command.eventId &&
        scopeId == command.scopeId &&
        learnerId == command.learnerId &&
        conversationId == command.conversationId &&
        eventKind == command.eventKind.name &&
        authorizationPurpose == command.authorizationPurpose &&
        authorizationRequestId == command.authorizationRequestId &&
        requestVersion == command.requestVersion &&
        payloadFingerprint == command.payloadFingerprint

internal fun TutorCurrentInteractionScopeEntity.matchesImmutableBinding(
    command: AppendCurrentTutorInteractionCommand,
): Boolean =
    scopeId == command.scopeId &&
        learnerId == command.learnerId &&
        conversationId == command.conversationId &&
        conversationGeneration == command.conversationGeneration &&
        conversationStateVersion == command.conversationStateVersion &&
        questionDocumentId == command.questionDocumentId &&
        questionRevisionNumber == command.questionRevisionNumber &&
        questionFingerprint == command.questionFingerprint &&
        subject == command.subject.name &&
        problemAnchorId == command.problemAnchorId &&
        explanationMode == command.explanationMode.name &&
        modeVersion == command.modeVersion &&
        learningWritePermissionVersion == command.learningWritePermissionVersion &&
        turnReferenceId == command.turnReferenceId &&
        turnOrdinal == command.turnOrdinal &&
        turnGeneration == command.turnGeneration &&
        cycleOrdinal == command.cycleOrdinal &&
        requestVersion == command.requestVersion

internal fun TutorCurrentInteractionScopeEntity.matchesBinding(
    command: AppendCurrentTutorInteractionCommand,
    state: CurrentTutorStudentInteractionState,
): Boolean =
    matchesImmutableBinding(command) &&
        state.attemptOrdinal.coerceAtLeast(1) == command.attemptOrdinal &&
        state.hintCount == command.hintCount &&
        state.answerWasRevealed == command.answerWasRevealed

internal fun TutorCurrentInteractionScopeEntity.matchesActivationCandidate(
    candidate: TutorCurrentInteractionScopeEntity,
): Boolean =
    copy(
        attemptOrdinal = candidate.attemptOrdinal,
        hintCount = candidate.hintCount,
    ) == candidate

internal fun TutorEvidenceRequestEntity.matchesAuthority(
    scope: TutorCurrentInteractionScopeEntity,
): Boolean =
    conversationId == scope.authorityConversationId &&
        conversationGeneration == scope.authorityConversationGeneration &&
        conversationStateVersion == scope.authorityConversationStateVersion &&
        turnReceiptId == scope.authorityTurnReceiptId &&
        turnOrdinal == scope.authorityTurnOrdinal &&
        subject == scope.subject &&
        problemAnchorId == scope.problemAnchorId &&
        requestVersion == scope.authorityRequestVersion &&
        explanationMode == scope.explanationMode &&
        modeVersion == scope.modeVersion

internal fun TutorCurrentInteractionScopeEntity.toRecord() = CurrentTutorInteractionScopeRecord(
    scopeId = scopeId,
    learnerId = learnerId,
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    conversationStateVersion = conversationStateVersion,
    authorityConversationId = authorityConversationId,
    authorityConversationGeneration = authorityConversationGeneration,
    authorityConversationStateVersion = authorityConversationStateVersion,
    authorityTurnReceiptId = authorityTurnReceiptId,
    authorityTurnOrdinal = authorityTurnOrdinal,
    authorityRequestVersion = authorityRequestVersion,
    questionDocumentId = questionDocumentId,
    questionRevisionNumber = questionRevisionNumber,
    questionDocumentSnapshot = questionDocumentSnapshot,
    questionFingerprint = questionFingerprint,
    subject = com.tingyun.smartmistakebook.core.model.SubjectKind.valueOf(subject),
    problemAnchorId = problemAnchorId,
    explanationMode = com.tingyun.smartmistakebook.core.model.TutorExplanationMode.valueOf(explanationMode),
    modeVersion = modeVersion,
    turnReferenceId = turnReferenceId,
    turnOrdinal = turnOrdinal,
    turnGeneration = turnGeneration,
    cycleOrdinal = cycleOrdinal,
    attemptOrdinal = attemptOrdinal,
    hintCount = hintCount,
    answerWasRevealed = answerWasRevealed,
    requestVersion = requestVersion,
    learningWritePermissionVersion = learningWritePermissionVersion,
    presentationFingerprint = presentationFingerprint,
    problemFingerprint = problemFingerprint,
    problemFamilyFingerprint = problemFamilyFingerprint,
    attributionPolicyVersion = attributionPolicyVersion,
    responsePolicyVersion = responsePolicyVersion,
    rubricCanonicalFingerprint = rubricCanonicalFingerprint,
    knowledgeAuthorityFingerprint = knowledgeAuthorityFingerprint,
    evaluator = evaluator,
    evaluatorPolicyFingerprint = evaluatorPolicyFingerprint,
    activationFingerprint = activationFingerprint,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun TutorCurrentInteractionHeadEntity.toRecord() = CurrentTutorInteractionHeadRecord(
    learnerId = learnerId,
    conversationId = conversationId,
    currentScopeId = currentScopeId,
    stateVersion = stateVersion,
    stateFingerprint = stateFingerprint,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal data class CurrentTutorStudentInteractionState(
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
) {
    fun after(command: AppendCurrentTutorInteractionCommand): CurrentTutorStudentInteractionState? {
        val nextAttempt = attemptOrdinal + if (command.eventKind.isStudentSubmission()) 1 else 0
        val nextHint = hintCount + if (
            command.eventKind == CurrentTutorInteractionEventKind.HINT_SHOWN
        ) 1 else 0
        if (nextAttempt !in 0..17 || nextHint !in 0..32) return null
        return copy(
            attemptOrdinal = nextAttempt,
            hintCount = nextHint,
            answerWasRevealed = answerWasRevealed || command.eventKind.revealsAnswer(),
        )
    }
}

internal fun CurrentTutorInteractionEventKind.isStudentSubmission(): Boolean =
    this == CurrentTutorInteractionEventKind.CHOICE ||
        this == CurrentTutorInteractionEventKind.VISUAL_SELECTION ||
        this == CurrentTutorInteractionEventKind.FREE_RESPONSE_SUBMISSION_CLAIM

internal fun CurrentTutorInteractionEventKind.revealsAnswer(): Boolean =
    this == CurrentTutorInteractionEventKind.SOLUTION_REVEAL ||
        this == CurrentTutorInteractionEventKind.ANSWER_EXPOSURE

internal fun List<TutorCurrentInteractionEventEntity>.interactionState(
    scope: TutorCurrentInteractionScopeEntity,
): CurrentTutorStudentInteractionState {
    val submissionCount = count { event ->
        CurrentTutorInteractionEventKind.valueOf(event.eventKind).isStudentSubmission()
    }
    val shownHintCount = count { event ->
        event.eventKind == CurrentTutorInteractionEventKind.HINT_SHOWN.name
    }
    val initialAttempt = scope.attemptOrdinal - submissionCount
    val initialHint = scope.hintCount - shownHintCount
    check(initialAttempt in 0..17 && initialHint in 0..32) {
        "Current Tutor interaction counters do not match durable events"
    }
    return CurrentTutorStudentInteractionState(
        attemptOrdinal = scope.attemptOrdinal,
        hintCount = scope.hintCount,
        answerWasRevealed = scope.answerWasRevealed || any { event ->
            CurrentTutorInteractionEventKind.valueOf(event.eventKind).revealsAnswer()
        },
    )
}

internal fun List<TutorCurrentInteractionEventEntity>.toRecordsAtCommit(
    scope: TutorCurrentInteractionScopeEntity,
): List<CurrentTutorInteractionEventRecord> {
    val ordered = sortedBy(TutorCurrentInteractionEventEntity::eventSequence)
    val submissionCount = ordered.count { event ->
        CurrentTutorInteractionEventKind.valueOf(event.eventKind).isStudentSubmission()
    }
    val shownHintCount = ordered.count { event ->
        event.eventKind == CurrentTutorInteractionEventKind.HINT_SHOWN.name
    }
    var state = CurrentTutorStudentInteractionState(
        attemptOrdinal = scope.attemptOrdinal - submissionCount,
        hintCount = scope.hintCount - shownHintCount,
        answerWasRevealed = scope.answerWasRevealed,
    )
    check(state.attemptOrdinal in 0..17 && state.hintCount in 0..32) {
        "Current Tutor interaction history exceeds its durable counters"
    }
    return ordered.map { event ->
        val kind = CurrentTutorInteractionEventKind.valueOf(event.eventKind)
        state = checkNotNull(
            state.after(
                event.toStateTransitionCommand(scope, kind),
            ),
        ) { "Current Tutor interaction history exceeds its bounds" }
        event.toRecord(scope.withInteractionState(state))
    }
}

internal fun TutorCurrentInteractionEventEntity.toStateTransitionCommand(
    scope: TutorCurrentInteractionScopeEntity,
    kind: CurrentTutorInteractionEventKind,
): AppendCurrentTutorInteractionCommand = AppendCurrentTutorInteractionCommand(
    scopeId = scope.scopeId,
    learnerId = scope.learnerId,
    conversationId = scope.conversationId,
    conversationGeneration = scope.conversationGeneration,
    conversationStateVersion = scope.conversationStateVersion,
    questionDocumentId = scope.questionDocumentId,
    questionRevisionNumber = scope.questionRevisionNumber,
    questionFingerprint = scope.questionFingerprint,
    subject = com.tingyun.smartmistakebook.core.model.SubjectKind.valueOf(scope.subject),
    problemAnchorId = scope.problemAnchorId,
    explanationMode = TutorExplanationMode.valueOf(scope.explanationMode),
    modeVersion = scope.modeVersion,
    learningWritePermissionVersion = scope.learningWritePermissionVersion,
    turnReferenceId = scope.turnReferenceId,
    turnOrdinal = scope.turnOrdinal,
    turnGeneration = scope.turnGeneration,
    cycleOrdinal = scope.cycleOrdinal,
    attemptOrdinal = scope.attemptOrdinal.coerceAtLeast(1),
    hintCount = scope.hintCount,
    answerWasRevealed = scope.answerWasRevealed,
    expectedStateVersion = 0,
    expectedStateFingerprint = committedStateFingerprint,
    eventId = eventId,
    eventKind = kind,
    authorizationPurpose = authorizationPurpose,
    authorizationRequestId = authorizationRequestId,
    idempotencyKey = idempotencyKey,
    requestVersion = requestVersion,
    payloadFingerprint = payloadFingerprint,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

internal fun TutorCurrentInteractionScopeEntity.withInteractionState(
    state: CurrentTutorStudentInteractionState,
): TutorCurrentInteractionScopeEntity = copy(
    attemptOrdinal = state.attemptOrdinal,
    hintCount = state.hintCount,
    answerWasRevealed = state.answerWasRevealed,
)

internal fun TutorCurrentInteractionEventEntity.toRecord(
    scope: TutorCurrentInteractionScopeEntity,
): CurrentTutorInteractionEventRecord {
    check(
        scopeId == scope.scopeId &&
            learnerId == scope.learnerId &&
            conversationId == scope.conversationId &&
            conversationGeneration == scope.conversationGeneration &&
            questionDocumentId == scope.questionDocumentId &&
            questionRevisionNumber == scope.questionRevisionNumber &&
            cycleOrdinal == scope.cycleOrdinal &&
            turnOrdinal == scope.turnOrdinal &&
            modeVersion == scope.modeVersion &&
            turnGeneration == scope.turnGeneration &&
            requestVersion == scope.requestVersion,
    ) { "Current Tutor event does not match its persisted scope" }
    return CurrentTutorInteractionEventRecord(
        eventId = eventId,
        scopeId = scopeId,
        learnerId = learnerId,
        conversationId = conversationId,
        conversationGeneration = conversationGeneration,
        conversationStateVersion = scope.conversationStateVersion,
        questionDocumentId = questionDocumentId,
        questionRevisionNumber = questionRevisionNumber,
        questionFingerprint = scope.questionFingerprint,
        subject = com.tingyun.smartmistakebook.core.model.SubjectKind.valueOf(scope.subject),
        problemAnchorId = scope.problemAnchorId,
        explanationMode = TutorExplanationMode.valueOf(scope.explanationMode),
        cycleOrdinal = cycleOrdinal,
        turnOrdinal = turnOrdinal,
        modeVersion = modeVersion,
        learningWritePermissionVersion = scope.learningWritePermissionVersion,
        turnReferenceId = scope.turnReferenceId,
        turnGeneration = turnGeneration,
        attemptOrdinal = scope.attemptOrdinal,
        hintCount = scope.hintCount,
        answerWasRevealed = scope.answerWasRevealed,
        eventSequence = eventSequence,
        committedStateFingerprint = committedStateFingerprint,
        eventKind = CurrentTutorInteractionEventKind.valueOf(eventKind),
        authorizationPurpose = authorizationPurpose,
        authorizationRequestId = authorizationRequestId,
        idempotencyKey = idempotencyKey,
        requestVersion = requestVersion,
        payloadFingerprint = payloadFingerprint,
        occurredAtEpochMillis = occurredAtEpochMillis,
        recordedAtEpochMillis = recordedAtEpochMillis,
        diagnosticStemMarkdown = diagnosticStemMarkdown,
        selectedChoiceId = selectedChoiceId,
        selectedChoiceMarkdown = selectedChoiceMarkdown,
        selectionWasCorrect = selectionWasCorrect,
        feedbackMarkdown = feedbackMarkdown,
        evidenceRequestId = evidenceRequestId,
        requestedMove = requestedMove,
        solutionRevealed = solutionRevealed,
        surfaceKind = surfaceKind,
        modelTaskRequestId = modelTaskRequestId,
        responseOrdinal = responseOrdinal,
        sceneSourceKind = sceneSourceKind,
        sceneTaskRequestId = sceneTaskRequestId,
        sceneId = sceneId,
        sceneFingerprint = sceneFingerprint,
        hitProofId = hitProofId,
        panelId = panelId,
        frameFingerprint = frameFingerprint,
        stepIndex = stepIndex,
        selectedTargetId = selectedTargetId,
        targetRevisionRef = targetRevisionRef,
        targetPracticeRef = targetPracticeRef,
        sourceKind = sourceKind,
    )
}

internal fun rejected(now: Long) = CurrentTutorInteractionAppendResult(
    disposition = CurrentTutorInteractionAppendDisposition.REJECTED,
    head = null,
    event = null,
    recordedAtEpochMillis = now,
)

internal fun requireCurrentIdentifier(value: String) {
    require(value.isNotBlank() && value == value.trim() && value.length <= 256)
}

internal fun isFingerprint(value: String?): Boolean =
    value != null && value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

internal fun currentTutorSha256(vararg values: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u001f").toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal const val OPEN_RESPONSE_CANDIDATE_PURPOSE = "CONSUME_OPEN_RESPONSE_CANDIDATE"
internal const val FREE_RESPONSE_SUBMISSION_CLAIM_PURPOSE = "CLAIM_FREE_RESPONSE_SUBMISSION"
internal const val RECORD_HINT_SHOWN_PURPOSE = "RECORD_HINT_SHOWN"
internal const val TRUSTED_SAVED_VISUAL_SELECTION_PURPOSE =
    "RECORD_TRUSTED_SAVED_VISUAL_SELECTION"
internal const val HINT_SHOWN_EVENT_VALUE = "HINT_SHOWN"
