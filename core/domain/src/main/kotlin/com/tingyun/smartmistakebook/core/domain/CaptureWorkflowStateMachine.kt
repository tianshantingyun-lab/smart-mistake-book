package com.tingyun.smartmistakebook.core.domain

enum class CaptureWorkflowPhase {
    IDLE,
    IMPORTING,
    ASSESSING,
    PARSING,
    REVIEWING,
    COMMITTING,
    SAVED,
    FAILED,
}

enum class CaptureFailureCode {
    IMPORT_REJECTED,
    ASSESSMENT_REJECTED,
    PARSE_REJECTED,
    COMMIT_REJECTED,
    STALE_RESULT,
}

data class CaptureWorkflowState(
    val phase: CaptureWorkflowPhase = CaptureWorkflowPhase.IDLE,
    val draftId: String? = null,
    val basisRevisionNumber: Int? = null,
    val latestRequestId: String? = null,
    val failureCode: CaptureFailureCode? = null,
    val canRetry: Boolean = false,
    val savedEntryId: String? = null,
) {
    init {
        require(draftId == null || draftId.isNotBlank()) {
            "Capture draft id must be null or non-blank"
        }
        require(basisRevisionNumber == null || basisRevisionNumber > 0) {
            "Capture basis revision must be positive"
        }
        require(
            phase != CaptureWorkflowPhase.FAILED || failureCode != null,
        ) { "A failed capture phase requires a failure code" }
        require(
            phase == CaptureWorkflowPhase.SAVED || savedEntryId == null,
        ) { "Only a saved capture may expose an entry id" }
    }
}

sealed interface CaptureWorkflowAction {
    val draftId: String?
    val basisRevisionNumber: Int?
    val requestId: String?

    data class ImportStarted(
        override val draftId: String? = null,
        override val basisRevisionNumber: Int? = null,
        override val requestId: String,
    ) : CaptureWorkflowAction

    data class DraftImported(
        override val draftId: String,
        override val basisRevisionNumber: Int,
        override val requestId: String,
    ) : CaptureWorkflowAction

    data class AssessmentStarted(
        override val draftId: String,
        override val basisRevisionNumber: Int,
        override val requestId: String,
    ) : CaptureWorkflowAction

    data class AssessmentSucceeded(
        override val draftId: String,
        override val basisRevisionNumber: Int,
        override val requestId: String,
    ) : CaptureWorkflowAction

    data class ParseStarted(
        override val draftId: String,
        override val basisRevisionNumber: Int,
        override val requestId: String,
    ) : CaptureWorkflowAction

    data class ParseSucceeded(
        override val draftId: String,
        override val basisRevisionNumber: Int,
        override val requestId: String,
    ) : CaptureWorkflowAction

    data class CommitStarted(
        override val draftId: String,
        override val basisRevisionNumber: Int,
        override val requestId: String,
    ) : CaptureWorkflowAction

    data class CommitSucceeded(
        override val draftId: String,
        override val basisRevisionNumber: Int,
        override val requestId: String,
        val savedEntryId: String?,
    ) : CaptureWorkflowAction

    data class Failed(
        override val draftId: String?,
        override val basisRevisionNumber: Int?,
        override val requestId: String?,
        val code: CaptureFailureCode,
        val canRetry: Boolean,
    ) : CaptureWorkflowAction

    data class Reset(
        override val draftId: String? = null,
        override val basisRevisionNumber: Int? = null,
        override val requestId: String? = null,
    ) : CaptureWorkflowAction
}

object CaptureWorkflowStateMachine {
    fun reduce(
        state: CaptureWorkflowState,
        action: CaptureWorkflowAction,
    ): CaptureWorkflowState {
        if (action is CaptureWorkflowAction.Reset) {
            return CaptureWorkflowState()
        }
        if (!acceptsAction(state, action)) {
            return state.copy(
                phase = CaptureWorkflowPhase.FAILED,
                failureCode = CaptureFailureCode.STALE_RESULT,
                canRetry = true,
            )
        }
        return when (action) {
            is CaptureWorkflowAction.ImportStarted -> state.copy(
                phase = CaptureWorkflowPhase.IMPORTING,
                draftId = action.draftId,
                basisRevisionNumber = action.basisRevisionNumber,
                latestRequestId = action.requestId,
                failureCode = null,
                canRetry = false,
                savedEntryId = null,
            )

            is CaptureWorkflowAction.DraftImported -> state.copy(
                phase = CaptureWorkflowPhase.ASSESSING,
                draftId = action.draftId,
                basisRevisionNumber = action.basisRevisionNumber,
                latestRequestId = action.requestId,
                failureCode = null,
                canRetry = false,
            )

            is CaptureWorkflowAction.AssessmentStarted -> state.copy(
                phase = CaptureWorkflowPhase.ASSESSING,
                latestRequestId = action.requestId,
                failureCode = null,
                canRetry = false,
            )

            is CaptureWorkflowAction.AssessmentSucceeded -> state.copy(
                phase = CaptureWorkflowPhase.PARSING,
                latestRequestId = action.requestId,
                failureCode = null,
                canRetry = false,
            )

            is CaptureWorkflowAction.ParseStarted -> state.copy(
                phase = CaptureWorkflowPhase.PARSING,
                latestRequestId = action.requestId,
                failureCode = null,
                canRetry = false,
            )

            is CaptureWorkflowAction.ParseSucceeded -> state.copy(
                phase = CaptureWorkflowPhase.REVIEWING,
                latestRequestId = action.requestId,
                failureCode = null,
                canRetry = false,
            )

            is CaptureWorkflowAction.CommitStarted -> state.copy(
                phase = CaptureWorkflowPhase.COMMITTING,
                latestRequestId = action.requestId,
                failureCode = null,
                canRetry = false,
            )

            is CaptureWorkflowAction.CommitSucceeded -> state.copy(
                phase = CaptureWorkflowPhase.SAVED,
                latestRequestId = action.requestId,
                failureCode = null,
                canRetry = false,
                savedEntryId = action.savedEntryId,
            )

            is CaptureWorkflowAction.Failed -> state.copy(
                phase = CaptureWorkflowPhase.FAILED,
                draftId = action.draftId ?: state.draftId,
                basisRevisionNumber = action.basisRevisionNumber ?: state.basisRevisionNumber,
                latestRequestId = action.requestId ?: state.latestRequestId,
                failureCode = action.code,
                canRetry = action.canRetry,
            )

            is CaptureWorkflowAction.Reset -> CaptureWorkflowState()
        }
    }

    private fun acceptsAction(
        state: CaptureWorkflowState,
        action: CaptureWorkflowAction,
    ): Boolean {
        if (state.phase == CaptureWorkflowPhase.SAVED) return false
        if (action.draftId != null && state.draftId != null && action.draftId != state.draftId) {
            return false
        }
        if (
            action.basisRevisionNumber != null &&
            state.basisRevisionNumber != null &&
            action.basisRevisionNumber != state.basisRevisionNumber &&
            state.latestRequestId != action.requestId
        ) {
            return false
        }
        return true
    }
}
