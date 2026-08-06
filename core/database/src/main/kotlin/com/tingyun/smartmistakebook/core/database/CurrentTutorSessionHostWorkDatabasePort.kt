package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationInputPolicy
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import kotlinx.coroutines.flow.Flow

/** Durable recovery journal for the owner-side current Tutor activation saga. */
interface CurrentTutorSessionHostWorkDatabasePort {
    suspend fun persistCurrentTutorSessionPolicy(
        command: PersistCurrentTutorSessionPolicyCommand,
    ): CurrentTutorSessionPolicyWriteResult

    suspend fun readCurrentTutorSessionPolicy(
        learnerId: String,
        sessionId: String,
    ): CurrentTutorSessionPolicyRecord?

    suspend fun stageCurrentTutorSessionHostWork(
        command: StageCurrentTutorSessionHostWorkCommand,
    ): CurrentTutorSessionHostWorkWriteResult

    suspend fun readCurrentTutorSessionHostWork(
        learnerId: String,
        sessionId: String,
    ): CurrentTutorSessionHostWorkRecord?

    fun observeCurrentTutorSessionHostWork(
        learnerId: String,
        sessionId: String,
    ): Flow<CurrentTutorSessionHostWorkRecord?>

    suspend fun markCurrentTutorSessionHostWorkActive(
        command: MarkCurrentTutorSessionHostWorkActiveCommand,
    ): CurrentTutorSessionHostWorkWriteResult

    suspend fun revokeCurrentTutorSessionHostWork(
        command: RevokeCurrentTutorSessionHostWorkCommand,
    ): CurrentTutorSessionHostWorkWriteResult

    /** Atomically claims the action and writes its encrypted conversation-scoped outbox row. */
    suspend fun claimCurrentTutorFreeResponseAction(
        command: ClaimCurrentTutorFreeResponseActionCommand,
    ): CurrentTutorFreeResponseActionClaimResult = CurrentTutorFreeResponseActionClaimResult(
        disposition = CurrentTutorFreeResponseActionClaimDisposition.NOT_CURRENT,
        canonicalOccurredAtEpochMillis = null,
        recordedAtEpochMillis = command.occurredAtEpochMillis,
    )

    suspend fun readCurrentTutorFreeResponseDispatchState(
        query: CurrentTutorFreeResponseActionClaimQuery,
    ): CurrentTutorFreeResponseDispatchState = CurrentTutorFreeResponseDispatchState.NOT_CLAIMED

    suspend fun acquireCurrentTutorFreeResponseDispatch(
        command: AcquireCurrentTutorFreeResponseDispatchCommand,
    ): CurrentTutorFreeResponseDispatchAcquireResult =
        CurrentTutorFreeResponseDispatchAcquireResult.NotAvailable

    suspend fun completeCurrentTutorFreeResponseDispatch(
        command: CompleteCurrentTutorFreeResponseDispatchCommand,
    ): CurrentTutorFreeResponseDispatchMutationResult =
        CurrentTutorFreeResponseDispatchMutationResult.NOT_CURRENT

    suspend fun releaseCurrentTutorFreeResponseDispatch(
        command: ReleaseCurrentTutorFreeResponseDispatchCommand,
    ): CurrentTutorFreeResponseDispatchMutationResult =
        CurrentTutorFreeResponseDispatchMutationResult.NOT_CURRENT

    suspend fun failCurrentTutorFreeResponseDispatchClosed(
        command: FailCurrentTutorFreeResponseDispatchClosedCommand,
    ): CurrentTutorFreeResponseDispatchMutationResult =
        CurrentTutorFreeResponseDispatchMutationResult.NOT_CURRENT
}

enum class CurrentTutorSessionHostWorkStatus {
    STAGED,
    ACTIVE,
    REVOKED,
}

enum class CurrentTutorSessionHostWorkWriteDisposition {
    APPLIED,
    DUPLICATE,
    STALE,
    NOT_FOUND,
    REJECTED,
}

data class PersistCurrentTutorSessionPolicyCommand(
    val learnerId: String,
    val sessionId: String,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val learningWritesAllowed: Boolean,
    val learningWritePermissionVersion: Long,
    val visualIntent: TutorCurrentSessionVisualIntent,
    val visualIntentVersion: Long,
    val occurredAtEpochMillis: Long,
) {
    init {
        requireTutorHostOpaque(learnerId)
        requireTutorHostOpaque(sessionId)
        require(
            modeVersion >= 0L && learningWritePermissionVersion >= 0L &&
                visualIntentVersion >= 0L && occurredAtEpochMillis >= 0L,
        )
    }
}

data class CurrentTutorSessionPolicyRecord(
    val learnerId: String,
    val sessionId: String,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val learningWritesAllowed: Boolean,
    val learningWritePermissionVersion: Long,
    val visualIntent: TutorCurrentSessionVisualIntent,
    val visualIntentVersion: Long,
    val stateFingerprint: String,
    val updatedAtEpochMillis: Long,
) {
    init {
        requireTutorHostOpaque(learnerId)
        requireTutorHostOpaque(sessionId)
        require(
            modeVersion >= 0L && learningWritePermissionVersion >= 0L &&
                visualIntentVersion >= 0L && updatedAtEpochMillis >= 0L,
        )
        requireTutorHostFingerprint(stateFingerprint)
    }
}

data class CurrentTutorSessionPolicyWriteResult(
    val disposition: CurrentTutorSessionHostWorkWriteDisposition,
    val record: CurrentTutorSessionPolicyRecord?,
    val recordedAtEpochMillis: Long,
)

enum class CurrentTutorSessionHostWorkRevocationReason {
    NEW_QUESTION,
    MODE_CHANGED,
    LEARNING_WRITES_DISABLED,
    SESSION_ENDED,
    POLICY_REJECTED,
}

data class StageCurrentTutorSessionHostWorkCommand(
    val workId: String,
    val learnerId: String,
    val sessionId: String,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val subject: SubjectKind,
    val authorityConversationId: String,
    val authorityConversationGeneration: Long,
    val authorityConversationStateVersion: Long,
    val authorityTurnReceiptId: String,
    val authorityTurnOrdinal: Int,
    val authorityRequestVersion: Long,
    val authorityDirectiveFingerprint: String,
    val modelTaskRequestId: String,
    val modelTaskRequestFingerprint: String,
    val problemAnchorId: String,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val learningWritesAllowed: Boolean,
    val learningWritePermissionVersion: Long,
    val visualIntent: TutorCurrentSessionVisualIntent,
    val visualIntentVersion: Long,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val attemptOrdinal: Int,
    val requestVersion: Long,
    val evidenceRequestId: String?,
    val pendingInteractionKind: TutorEvidenceRequestKind?,
    val targetScopeId: String,
    val targetActivationFingerprint: String,
    val constrainedTutorContentFingerprint: String,
    val presentationToken: String,
    val payloadFingerprint: String,
    /** Exact durable policy row observed by the Host before staging; production never omits it. */
    val expectedPolicyStateFingerprint: String? = null,
    val expectedStateVersion: Long?,
    val expectedStateFingerprint: String?,
    val occurredAtEpochMillis: Long,
) {
    init {
        listOf(
            workId,
            learnerId,
            sessionId,
            questionDocumentId,
            authorityConversationId,
            authorityTurnReceiptId,
            modelTaskRequestId,
            problemAnchorId,
            targetScopeId,
        ).forEach(::requireTutorHostOpaque)
        listOf(
            modelTaskRequestFingerprint,
            authorityDirectiveFingerprint,
            targetActivationFingerprint,
            constrainedTutorContentFingerprint,
            presentationToken,
            payloadFingerprint,
        ).forEach(::requireTutorHostFingerprint)
        expectedPolicyStateFingerprint?.let(::requireTutorHostFingerprint)
        require(questionRevisionNumber > 0 && subject != SubjectKind.GENERAL)
        require(authorityConversationGeneration > 0 && authorityConversationStateVersion >= 0)
        require(authorityTurnOrdinal > 0 && authorityRequestVersion >= 0)
        require(
            modeVersion >= 0 && learningWritePermissionVersion >= 0 && visualIntentVersion >= 0,
        )
        require(cycleOrdinal > 0 && turnOrdinal > 0 && attemptOrdinal in 1..17)
        require(requestVersion >= 0 && occurredAtEpochMillis >= 0)
        require((evidenceRequestId == null) == (pendingInteractionKind == null))
        evidenceRequestId?.let(::requireTutorHostOpaque)
        require(
            pendingInteractionKind == null || explanationMode == TutorExplanationMode.GUIDED,
        )
        require((expectedStateVersion == null) == (expectedStateFingerprint == null))
        expectedStateVersion?.let { require(it >= 0) }
        expectedStateFingerprint?.let(::requireTutorHostFingerprint)
    }
}

data class MarkCurrentTutorSessionHostWorkActiveCommand(
    val learnerId: String,
    val sessionId: String,
    val expectedWorkId: String,
    val expectedStateVersion: Long,
    val expectedStateFingerprint: String,
    val expectedTargetScopeId: String,
    val expectedTargetActivationFingerprint: String,
    val expectedPolicyStateFingerprint: String? = null,
    val occurredAtEpochMillis: Long,
) {
    init {
        listOf(learnerId, sessionId, expectedWorkId, expectedTargetScopeId)
            .forEach(::requireTutorHostOpaque)
        listOf(expectedStateFingerprint, expectedTargetActivationFingerprint)
            .forEach(::requireTutorHostFingerprint)
        expectedPolicyStateFingerprint?.let(::requireTutorHostFingerprint)
        require(expectedStateVersion >= 0 && occurredAtEpochMillis >= 0)
    }
}

data class RevokeCurrentTutorSessionHostWorkCommand(
    val learnerId: String,
    val sessionId: String,
    val expectedWorkId: String,
    val expectedStateVersion: Long,
    val expectedStateFingerprint: String,
    val reason: CurrentTutorSessionHostWorkRevocationReason,
    val occurredAtEpochMillis: Long,
) {
    init {
        listOf(learnerId, sessionId, expectedWorkId).forEach(::requireTutorHostOpaque)
        requireTutorHostFingerprint(expectedStateFingerprint)
        require(expectedStateVersion >= 0 && occurredAtEpochMillis >= 0)
    }
}

data class CurrentTutorSessionHostWorkRecord(
    val workId: String,
    val learnerId: String,
    val sessionId: String,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val subject: SubjectKind,
    val authorityConversationId: String,
    val authorityConversationGeneration: Long,
    val authorityConversationStateVersion: Long,
    val authorityTurnReceiptId: String,
    val authorityTurnOrdinal: Int,
    val authorityRequestVersion: Long,
    val authorityDirectiveFingerprint: String,
    val modelTaskRequestId: String,
    val modelTaskRequestFingerprint: String,
    val problemAnchorId: String,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val learningWritesAllowed: Boolean,
    val learningWritePermissionVersion: Long,
    val visualIntent: TutorCurrentSessionVisualIntent,
    val visualIntentVersion: Long,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val attemptOrdinal: Int,
    val requestVersion: Long,
    val evidenceRequestId: String?,
    val pendingInteractionKind: TutorEvidenceRequestKind?,
    val targetScopeId: String,
    val targetActivationFingerprint: String,
    val constrainedTutorContentFingerprint: String,
    val activeScopeId: String?,
    val presentationToken: String,
    val payloadFingerprint: String,
    val status: CurrentTutorSessionHostWorkStatus,
    val revocationReason: CurrentTutorSessionHostWorkRevocationReason?,
    val stateVersion: Long,
    val stateFingerprint: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        listOf(
            authorityDirectiveFingerprint,
            modelTaskRequestFingerprint,
            targetActivationFingerprint,
            constrainedTutorContentFingerprint,
            presentationToken,
            payloadFingerprint,
            stateFingerprint,
        ).forEach(::requireTutorHostFingerprint)
        require((evidenceRequestId == null) == (pendingInteractionKind == null))
        require((status == CurrentTutorSessionHostWorkStatus.REVOKED) == (revocationReason != null))
        require(status != CurrentTutorSessionHostWorkStatus.ACTIVE || activeScopeId == targetScopeId)
        require(visualIntentVersion >= 0)
        require(stateVersion >= 0 && updatedAtEpochMillis >= createdAtEpochMillis)
    }
}

data class CurrentTutorSessionHostWorkWriteResult(
    val disposition: CurrentTutorSessionHostWorkWriteDisposition,
    val record: CurrentTutorSessionHostWorkRecord?,
    val recordedAtEpochMillis: Long,
)

data class ClaimCurrentTutorFreeResponseActionCommand(
    val learnerId: String,
    val sessionId: String,
    val expectedWorkId: String,
    val expectedWorkStateVersion: Long,
    val expectedWorkStateFingerprint: String,
    val presentationToken: String,
    val evidenceRequestId: String,
    val actionToken: String,
    val answer: String,
    val actionExpiresAtEpochMillis: Long,
    val occurredAtEpochMillis: Long,
) {
    init {
        listOf(learnerId, sessionId, expectedWorkId, evidenceRequestId)
            .forEach(::requireTutorHostOpaque)
        listOf(
            expectedWorkStateFingerprint,
            presentationToken,
            actionToken,
        ).forEach(::requireTutorHostFingerprint)
        require(answer == answer.trim())
        OpenResponseEvaluationInputPolicy.requireValidCurrentAnswer(answer)
        require(
            expectedWorkStateVersion >= 0L && occurredAtEpochMillis >= 0L &&
                actionExpiresAtEpochMillis > 0L,
        )
    }

    override fun toString(): String =
        "ClaimCurrentTutorFreeResponseActionCommand(sessionId=<redacted>, answer=<redacted>)"
}

data class CurrentTutorFreeResponseActionClaimQuery(
    val learnerId: String,
    val sessionId: String,
    val expectedWorkId: String,
    val expectedWorkStateVersion: Long,
    val expectedWorkStateFingerprint: String,
    val presentationToken: String,
    val evidenceRequestId: String,
    val actionToken: String,
) {
    init {
        listOf(learnerId, sessionId, expectedWorkId, evidenceRequestId)
            .forEach(::requireTutorHostOpaque)
        listOf(expectedWorkStateFingerprint, presentationToken, actionToken)
            .forEach(::requireTutorHostFingerprint)
        require(expectedWorkStateVersion >= 0L)
    }
}

enum class CurrentTutorFreeResponseActionClaimDisposition {
    CLAIMED,
    DUPLICATE,
    NOT_CURRENT,
    REJECTED,
}

data class CurrentTutorFreeResponseActionClaimResult(
    val disposition: CurrentTutorFreeResponseActionClaimDisposition,
    val canonicalOccurredAtEpochMillis: Long?,
    val recordedAtEpochMillis: Long,
) {
    init {
        require(recordedAtEpochMillis >= 0L)
        require(
            (disposition in setOf(
                CurrentTutorFreeResponseActionClaimDisposition.CLAIMED,
                CurrentTutorFreeResponseActionClaimDisposition.DUPLICATE,
            )) == (canonicalOccurredAtEpochMillis != null),
        )
        canonicalOccurredAtEpochMillis?.let { require(it >= 0L) }
    }
}

enum class CurrentTutorFreeResponseDispatchState {
    NOT_CLAIMED,
    NEEDS_DISPATCH,
    IN_FLIGHT,
    COMPLETED,
    FAILED_CLOSED,
}

data class AcquireCurrentTutorFreeResponseDispatchCommand(
    val learnerId: String,
    val sessionId: String,
    val actionToken: String?,
    val leaseOwnerId: String,
    /** Random once per Android process; a new process may safely reclaim an orphaned lease. */
    val leaseGenerationId: String,
    val leaseDurationMillis: Long,
    val occurredAtEpochMillis: Long,
) {
    init {
        listOf(learnerId, sessionId, leaseOwnerId, leaseGenerationId)
            .forEach(::requireTutorHostOpaque)
        actionToken?.let(::requireTutorHostFingerprint)
        require(leaseDurationMillis in 1_000L..300_000L)
        require(occurredAtEpochMillis >= 0L)
    }
}

interface CurrentTutorFreeResponseDispatchLease {
    val learnerId: String
    val sessionId: String
    val actionToken: String
    val workId: String
    val workStateVersion: Long
    val workStateFingerprint: String
    val presentationToken: String
    val evidenceRequestId: String
    val answerBinding: String
    val answer: String
    val canonicalOccurredAtEpochMillis: Long
    val leaseToken: String
    val leaseExpiresAtEpochMillis: Long
}

internal class RoomCurrentTutorFreeResponseDispatchLease(
    override val learnerId: String,
    override val sessionId: String,
    override val actionToken: String,
    override val workId: String,
    override val workStateVersion: Long,
    override val workStateFingerprint: String,
    override val presentationToken: String,
    override val evidenceRequestId: String,
    override val answerBinding: String,
    override val answer: String,
    override val canonicalOccurredAtEpochMillis: Long,
    override val leaseToken: String,
    override val leaseExpiresAtEpochMillis: Long,
) : CurrentTutorFreeResponseDispatchLease {
    override fun toString(): String =
        "CurrentTutorFreeResponseDispatchLease(sessionId=<redacted>, action=<redacted>)"
}

sealed interface CurrentTutorFreeResponseDispatchAcquireResult {
    data class Acquired(
        val lease: CurrentTutorFreeResponseDispatchLease,
    ) : CurrentTutorFreeResponseDispatchAcquireResult {
        override fun toString(): String = "Acquired(lease=<redacted>)"
    }

    data object Busy : CurrentTutorFreeResponseDispatchAcquireResult

    data object Completed : CurrentTutorFreeResponseDispatchAcquireResult

    data object FailedClosed : CurrentTutorFreeResponseDispatchAcquireResult

    data object NotAvailable : CurrentTutorFreeResponseDispatchAcquireResult
}

data class CompleteCurrentTutorFreeResponseDispatchCommand(
    val learnerId: String,
    val sessionId: String,
    val actionToken: String,
    val leaseToken: String,
    val candidateIdempotencyKey: String,
    val candidateReceiptFingerprint: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        listOf(learnerId, sessionId).forEach(::requireTutorHostOpaque)
        listOf(
            actionToken,
            leaseToken,
            candidateIdempotencyKey,
            candidateReceiptFingerprint,
        ).forEach(::requireTutorHostFingerprint)
        require(occurredAtEpochMillis >= 0L)
    }
}

data class ReleaseCurrentTutorFreeResponseDispatchCommand(
    val learnerId: String,
    val sessionId: String,
    val actionToken: String,
    val leaseToken: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        listOf(learnerId, sessionId).forEach(::requireTutorHostOpaque)
        listOf(actionToken, leaseToken).forEach(::requireTutorHostFingerprint)
        require(occurredAtEpochMillis >= 0L)
    }
}

data class FailCurrentTutorFreeResponseDispatchClosedCommand(
    val learnerId: String,
    val sessionId: String,
    val actionToken: String,
    val leaseToken: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        listOf(learnerId, sessionId).forEach(::requireTutorHostOpaque)
        listOf(actionToken, leaseToken).forEach(::requireTutorHostFingerprint)
        require(occurredAtEpochMillis >= 0L)
    }
}

enum class CurrentTutorFreeResponseDispatchMutationResult {
    APPLIED,
    DUPLICATE,
    NOT_CURRENT,
    REJECTED,
}

private fun requireTutorHostOpaque(value: String) {
    require(
        value.isNotBlank() && value == value.trim() && value.length <= 256 &&
            value.none(Char::isISOControl),
    )
}

private fun requireTutorHostFingerprint(value: String) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' })
}
