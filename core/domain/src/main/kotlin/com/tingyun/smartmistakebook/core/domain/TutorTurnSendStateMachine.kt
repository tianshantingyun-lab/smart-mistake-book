package com.tingyun.smartmistakebook.core.domain

const val MAX_TUTOR_DISPATCH_ATTEMPTS = 3

enum class TutorSendPhase {
    IDLE,
    PERSISTING,
    AWAITING_CONSENT,
    DISPATCHING,
    STREAMING,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    COMPLETED,
}

data class TutorSendState(
    val phase: TutorSendPhase = TutorSendPhase.IDLE,
    val logicalOperationId: String? = null,
    val messageId: String? = null,
    val dispatchAttemptCount: Int = 0,
    val errorCode: String? = null,
) {
    init {
        require(logicalOperationId == null || logicalOperationId.isNotBlank()) {
            "Tutor logical operation id must be null or non-blank"
        }
        require(messageId == null || messageId.isNotBlank()) {
            "Tutor message id must be null or non-blank"
        }
        require(dispatchAttemptCount in 0..MAX_TUTOR_DISPATCH_ATTEMPTS) {
            "Tutor dispatch attempts must stay within the fixed budget"
        }
        require(
            phase != TutorSendPhase.DISPATCHING ||
                (logicalOperationId != null && messageId != null),
        ) { "A dispatching tutor turn requires stable logical and message ids" }
        require(
            phase != TutorSendPhase.RETRYABLE_FAILURE &&
                phase != TutorSendPhase.PERMANENT_FAILURE ||
                errorCode != null,
        ) { "A failed tutor turn requires an error code" }
        require(
            phase != TutorSendPhase.COMPLETED || dispatchAttemptCount > 0,
        ) { "A completed tutor turn must have dispatched at least once" }
    }
}

sealed interface TutorSendAction {
    val logicalOperationId: String?
    val messageId: String?

    data class StudentMessagePersisted(
        override val logicalOperationId: String,
        override val messageId: String,
    ) : TutorSendAction

    data class ConsentRequired(
        override val logicalOperationId: String,
        override val messageId: String,
    ) : TutorSendAction

    data class ConsentGranted(
        override val logicalOperationId: String,
        override val messageId: String,
    ) : TutorSendAction

    data class DispatchStarted(
        override val logicalOperationId: String,
        override val messageId: String,
    ) : TutorSendAction

    data class DispatchSucceeded(
        override val logicalOperationId: String,
        override val messageId: String,
    ) : TutorSendAction

    data class DispatchFailed(
        override val logicalOperationId: String,
        override val messageId: String,
        val errorCode: String,
        val retryable: Boolean,
    ) : TutorSendAction

    data class ResumeAfterRestart(
        override val logicalOperationId: String,
        override val messageId: String,
    ) : TutorSendAction

    data class Reset(
        override val logicalOperationId: String? = null,
        override val messageId: String? = null,
    ) : TutorSendAction
}

object TutorTurnSendStateMachine {
    fun reduce(
        state: TutorSendState,
        action: TutorSendAction,
    ): TutorSendState {
        if (action is TutorSendAction.Reset) return TutorSendState()
        if (!acceptsAction(state, action)) {
            return state.copy(
                phase = TutorSendPhase.PERMANENT_FAILURE,
                errorCode = "STALE_TUTOR_TURN_RESULT",
            )
        }
        return when (action) {
            is TutorSendAction.StudentMessagePersisted -> state.copy(
                phase = TutorSendPhase.PERSISTING,
                logicalOperationId = action.logicalOperationId,
                messageId = action.messageId,
                dispatchAttemptCount = 0,
                errorCode = null,
            )
            is TutorSendAction.ConsentRequired -> state.copy(
                phase = TutorSendPhase.AWAITING_CONSENT,
                logicalOperationId = action.logicalOperationId,
                messageId = action.messageId,
                errorCode = null,
            )
            is TutorSendAction.ConsentGranted -> state.copy(
                phase = TutorSendPhase.DISPATCHING,
                logicalOperationId = action.logicalOperationId,
                messageId = action.messageId,
                dispatchAttemptCount = state.dispatchAttemptCount + 1,
                errorCode = null,
            )
            is TutorSendAction.DispatchStarted -> state.copy(
                phase = TutorSendPhase.STREAMING,
                logicalOperationId = action.logicalOperationId,
                messageId = action.messageId,
                errorCode = null,
            )
            is TutorSendAction.DispatchSucceeded -> state.copy(
                phase = TutorSendPhase.COMPLETED,
                logicalOperationId = action.logicalOperationId,
                messageId = action.messageId,
                errorCode = null,
            )
            is TutorSendAction.DispatchFailed -> {
                val nextPhase = if (action.retryable) {
                    TutorSendPhase.RETRYABLE_FAILURE
                } else {
                    TutorSendPhase.PERMANENT_FAILURE
                }
                state.copy(
                    phase = nextPhase,
                    logicalOperationId = action.logicalOperationId,
                    messageId = action.messageId,
                    errorCode = action.errorCode,
                )
            }
            is TutorSendAction.ResumeAfterRestart -> state.copy(
                phase = TutorSendPhase.AWAITING_CONSENT,
                logicalOperationId = action.logicalOperationId,
                messageId = action.messageId,
                errorCode = null,
            )
            is TutorSendAction.Reset -> TutorSendState()
        }
    }

    private fun acceptsAction(
        state: TutorSendState,
        action: TutorSendAction,
    ): Boolean {
        if (state.phase == TutorSendPhase.COMPLETED &&
            action !is TutorSendAction.StudentMessagePersisted
        ) {
            return false
        }
        if (
            action.logicalOperationId != null &&
            state.logicalOperationId != null &&
            action.logicalOperationId != state.logicalOperationId &&
            !(state.phase == TutorSendPhase.COMPLETED &&
                action is TutorSendAction.StudentMessagePersisted)
        ) {
            return false
        }
        if (
            action.messageId != null &&
            state.messageId != null &&
            action.messageId != state.messageId &&
            !(state.phase == TutorSendPhase.COMPLETED &&
                action is TutorSendAction.StudentMessagePersisted)
        ) {
            return false
        }
        if (
            action is TutorSendAction.ConsentGranted &&
            state.dispatchAttemptCount >= MAX_TUTOR_DISPATCH_ATTEMPTS
        ) {
            return false
        }
        return true
    }
}
