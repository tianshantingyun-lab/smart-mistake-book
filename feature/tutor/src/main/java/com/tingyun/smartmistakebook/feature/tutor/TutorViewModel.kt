package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealRequest
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealResult
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmission
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmissionResult
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

internal enum class TutorSaveStatus {
    IDLE,
    SAVING,
    SAVED,
    FAILED,
}

internal enum class TutorSubmissionStatus {
    IDLE,
    RECORDING,
    RECORDED,
    FAILED,
}

internal enum class TutorRevealStatus {
    HIDDEN,
    RECORDING,
    REVEALED,
    FAILED,
}

/** Presentation state for the current teaching turn, persisted across process recreation. */
class TutorViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var activePresentationKey by mutableStateOf(
        savedStateHandle.get<String>(ACTIVE_PRESENTATION_KEY),
    )

    var selectedChoiceId by mutableStateOf(savedStateHandle.get<String>(SELECTED_CHOICE_KEY))
        private set

    private var selectedAssessmentItemId by mutableStateOf(
        savedStateHandle.get<String>(SELECTED_ASSESSMENT_ITEM_KEY),
    )

    private var submittedChoiceId by mutableStateOf(
        savedStateHandle.get<String>(SUBMITTED_CHOICE_KEY),
    )

    private var submittedAssessmentItemId by mutableStateOf(
        savedStateHandle.get<String>(SUBMITTED_ASSESSMENT_ITEM_KEY),
    )

    var selectedFollowUpId by mutableStateOf(savedStateHandle.get<String>(FOLLOW_UP_KEY))
        private set

    var draft by mutableStateOf(savedStateHandle.get<String>(DRAFT_KEY) ?: "")
        private set

    var unverifiedQuestionNoticeVisible by mutableStateOf(
        savedStateHandle.get<Boolean>(UNVERIFIED_NOTICE_KEY) ?: false,
    )
        private set

    internal var saveStatus by mutableStateOf(restoredSaveStatus(savedStateHandle))
        private set

    internal var submissionStatus by mutableStateOf(restoredSubmissionStatus(savedStateHandle))
        private set

    internal var revealStatus by mutableStateOf(restoredRevealStatus(savedStateHandle))
        private set

    var revealedExplanation by mutableStateOf(savedStateHandle.get<String>(REVEALED_EXPLANATION_KEY))
        private set

    private var directAnswerExposed by mutableStateOf(
        savedStateHandle.get<Boolean>(DIRECT_ANSWER_EXPOSED_KEY) ?: false,
    )

    private var presentationId: String = savedStateHandle.get<String>(PRESENTATION_ID_KEY)
        ?: "presentation:tutor:${UUID.randomUUID()}".also {
            savedStateHandle[PRESENTATION_ID_KEY] = it
        }

    private var presentationStartedAtEpochMillis: Long =
        savedStateHandle.get<Long>(PRESENTATION_STARTED_AT_KEY)
            ?: System.currentTimeMillis().also {
                savedStateHandle[PRESENTATION_STARTED_AT_KEY] = it
            }

    private var saveJob: Job? = null
    private var submissionJob: Job? = null
    private var revealJob: Job? = null

    fun isActivePresentation(key: String): Boolean = activePresentationKey == key

    fun useExplanationMode(
        mode: TutorExplanationMode,
        cancelSubmission: (String) -> Unit = {},
    ) {
        if (mode == TutorExplanationMode.DIRECT && activePresentationKey != null) {
            recordDirectExposure()
        }
        if (
            mode == TutorExplanationMode.DIRECT &&
            submissionStatus == TutorSubmissionStatus.RECORDING
        ) {
            savedStateHandle.get<String>(PENDING_SUBMISSION_REQUEST_ID_KEY)
                ?.let(cancelSubmission)
            submissionJob?.cancel()
            submissionJob = null
            savedStateHandle.remove<Any?>(PENDING_SUBMISSION_REQUEST_ID_KEY)
            savedStateHandle.remove<Any?>(PENDING_SUBMISSION_PRESENTATION_ID_KEY)
            savedStateHandle.remove<Any?>(PENDING_SUBMISSION_PRACTICE_UNIT_ID_KEY)
            savedStateHandle.remove<Any?>(PENDING_SUBMISSION_CHOICE_ID_KEY)
            savedStateHandle.remove<Any?>(PENDING_SUBMISSION_DURATION_SECONDS_KEY)
            savedStateHandle.remove<Any?>(PENDING_SUBMISSION_OCCURRED_AT_KEY)
            updateSubmissionStatus(TutorSubmissionStatus.IDLE)
        }
    }

    fun recordDirectExposure() {
        if (activePresentationKey == null || directAnswerExposed) return
        directAnswerExposed = true
        savedStateHandle[DIRECT_ANSWER_EXPOSED_KEY] = true
    }

    /** Binds every answer/reveal state field to one verified teaching turn. */
    fun synchronizePresentation(key: String, isSaved: Boolean) {
        require(key.isNotBlank()) { "Tutor presentation key must not be blank" }
        if (activePresentationKey != key) {
            resetForPresentation(key, isSaved)
        } else {
            synchronizeSaveStatus(isSaved)
        }
    }

    /** Selection is a local, replaceable draft. It is not learning evidence until explicit submit. */
    fun selectChoice(assessmentItem: TutorAssessmentItem, choiceId: String) {
        if (directAnswerExposed) return
        if (submittedChoiceFor(assessmentItem) != null) return
        if (savedStateHandle.get<String>(PENDING_SUBMISSION_REQUEST_ID_KEY) != null) return
        if (submissionStatus == TutorSubmissionStatus.RECORDING ||
            revealStatus == TutorRevealStatus.RECORDING
        ) return
        if (assessmentItem.choices.none { it.id == choiceId }) return

        selectedAssessmentItemId = assessmentItem.id
        selectedChoiceId = choiceId
        selectedFollowUpId = null
        savedStateHandle[SELECTED_ASSESSMENT_ITEM_KEY] = assessmentItem.id
        savedStateHandle[SELECTED_CHOICE_KEY] = choiceId
        savedStateHandle[FOLLOW_UP_KEY] = null
    }

    /** Records the trusted attempt before the UI exposes correctness or teaching content. */
    fun requestSubmit(
        assessmentItem: TutorAssessmentItem,
        practiceUnitId: String,
        submit: suspend (StudyChoiceSubmission) -> StudyChoiceSubmissionResult,
    ) {
        if (directAnswerExposed) return
        if (submittedChoiceFor(assessmentItem) != null) return
        if (submissionStatus == TutorSubmissionStatus.RECORDING) return
        if (revealStatus == TutorRevealStatus.RECORDING) return
        val choiceId = selectedChoiceFor(assessmentItem) ?: return
        if (assessmentItem.choices.none { it.id == choiceId }) return

        val command = runCatching {
            submissionCommand(assessmentItem, practiceUnitId, choiceId)
        }.getOrElse {
            updateSubmissionStatus(TutorSubmissionStatus.FAILED)
            return
        }
        val operationPresentationKey = activePresentationKey
        updateSubmissionStatus(TutorSubmissionStatus.RECORDING)
        submissionJob = viewModelScope.launch {
            try {
                val result = submit(command)
                if (activePresentationKey != operationPresentationKey) return@launch
                check(result.isCorrect == assessmentItem.evaluateChoice(choiceId).isCorrect) {
                    "Persisted assessment result disagrees with the verified teaching artifact"
                }
                submittedAssessmentItemId = assessmentItem.id
                submittedChoiceId = choiceId
                savedStateHandle[SUBMITTED_ASSESSMENT_ITEM_KEY] = assessmentItem.id
                savedStateHandle[SUBMITTED_CHOICE_KEY] = choiceId
                updateSubmissionStatus(TutorSubmissionStatus.RECORDED)
            } catch (cancellation: CancellationException) {
                if (activePresentationKey == operationPresentationKey) {
                    updateSubmissionStatus(TutorSubmissionStatus.IDLE)
                }
                throw cancellation
            } catch (_: Exception) {
                if (activePresentationKey == operationPresentationKey) {
                    updateSubmissionStatus(TutorSubmissionStatus.FAILED)
                }
            }
        }
    }

    /** Persists the reveal first; failed persistence never unlocks the answer in presentation state. */
    fun requestReveal(
        assessmentItem: TutorAssessmentItem,
        practiceUnitId: String,
        reveal: suspend (StudyAnswerRevealRequest) -> StudyAnswerRevealResult,
        followUpId: String? = null,
    ) {
        if (revealStatus == TutorRevealStatus.RECORDING) return
        if (revealedExplanation != null) {
            followUpId?.let(::selectFollowUp)
            return
        }

        val command = runCatching { revealCommand(practiceUnitId) }.getOrElse {
            updateRevealStatus(TutorRevealStatus.FAILED)
            return
        }
        val operationPresentationKey = activePresentationKey
        updateRevealStatus(TutorRevealStatus.RECORDING)
        revealJob = viewModelScope.launch {
            try {
                val result = reveal(command)
                if (activePresentationKey != operationPresentationKey) return@launch
                check(result.explanationMarkdown.isNotBlank()) {
                    "Persisted answer reveal returned empty teaching content"
                }
                revealedExplanation = result.explanationMarkdown
                savedStateHandle[REVEALED_EXPLANATION_KEY] = result.explanationMarkdown
                updateRevealStatus(TutorRevealStatus.REVEALED)
                followUpId?.let(::selectFollowUp)
            } catch (cancellation: CancellationException) {
                if (activePresentationKey == operationPresentationKey) {
                    updateRevealStatus(TutorRevealStatus.HIDDEN)
                }
                throw cancellation
            } catch (_: Exception) {
                if (activePresentationKey == operationPresentationKey) {
                    updateRevealStatus(TutorRevealStatus.FAILED)
                }
            }
        }
    }

    fun selectedChoiceFor(assessmentItem: TutorAssessmentItem): String? = selectedChoiceId?.takeIf {
        selectedAssessmentItemId == assessmentItem.id &&
            assessmentItem.choices.any { choice -> choice.id == it }
    }

    fun submittedChoiceFor(assessmentItem: TutorAssessmentItem): String? = submittedChoiceId?.takeIf {
        submittedAssessmentItemId == assessmentItem.id &&
            assessmentItem.choices.any { choice -> choice.id == it }
    }

    fun selectFollowUp(actionId: String) {
        selectedFollowUpId = actionId
        savedStateHandle[FOLLOW_UP_KEY] = actionId
    }

    fun updateDraft(value: String) {
        draft = value
        savedStateHandle[DRAFT_KEY] = value
        if (value.isBlank()) dismissUnverifiedQuestionNotice()
    }

    fun submitDraft() {
        if (draft.isBlank()) return
        unverifiedQuestionNoticeVisible = true
        savedStateHandle[UNVERIFIED_NOTICE_KEY] = true
    }

    fun dismissUnverifiedQuestionNotice() {
        unverifiedQuestionNoticeVisible = false
        savedStateHandle[UNVERIFIED_NOTICE_KEY] = false
    }

    fun synchronizeSaveStatus(isSaved: Boolean) {
        when {
            isSaved && saveStatus != TutorSaveStatus.SAVED -> updateSaveStatus(TutorSaveStatus.SAVED)
            !isSaved && saveStatus == TutorSaveStatus.SAVED -> updateSaveStatus(TutorSaveStatus.IDLE)
        }
    }

    fun requestSave(save: suspend () -> Unit) {
        if (saveStatus == TutorSaveStatus.SAVING || saveStatus == TutorSaveStatus.SAVED) return

        val operationPresentationKey = activePresentationKey
        updateSaveStatus(TutorSaveStatus.SAVING)
        saveJob = viewModelScope.launch {
            try {
                save()
                if (activePresentationKey == operationPresentationKey) {
                    updateSaveStatus(TutorSaveStatus.SAVED)
                }
            } catch (cancellation: CancellationException) {
                if (activePresentationKey == operationPresentationKey) {
                    updateSaveStatus(TutorSaveStatus.IDLE)
                }
                throw cancellation
            } catch (_: Exception) {
                if (activePresentationKey == operationPresentationKey) {
                    updateSaveStatus(TutorSaveStatus.FAILED)
                }
            }
        }
    }

    private fun resetForPresentation(key: String, isSaved: Boolean) {
        saveJob?.cancel()
        submissionJob?.cancel()
        revealJob?.cancel()

        activePresentationKey = key
        savedStateHandle[ACTIVE_PRESENTATION_KEY] = key
        selectedChoiceId = null
        selectedAssessmentItemId = null
        submittedChoiceId = null
        submittedAssessmentItemId = null
        selectedFollowUpId = null
        draft = ""
        unverifiedQuestionNoticeVisible = false
        revealedExplanation = null
        directAnswerExposed = false
        clearPresentationValues()

        presentationId = "presentation:tutor:${UUID.randomUUID()}"
        presentationStartedAtEpochMillis = System.currentTimeMillis()
        savedStateHandle[PRESENTATION_ID_KEY] = presentationId
        savedStateHandle[PRESENTATION_STARTED_AT_KEY] = presentationStartedAtEpochMillis
        updateSaveStatus(if (isSaved) TutorSaveStatus.SAVED else TutorSaveStatus.IDLE)
        updateSubmissionStatus(TutorSubmissionStatus.IDLE)
        updateRevealStatus(TutorRevealStatus.HIDDEN)
    }

    private fun clearPresentationValues() {
        listOf(
            SELECTED_CHOICE_KEY,
            SELECTED_ASSESSMENT_ITEM_KEY,
            SUBMITTED_CHOICE_KEY,
            SUBMITTED_ASSESSMENT_ITEM_KEY,
            FOLLOW_UP_KEY,
            DRAFT_KEY,
            UNVERIFIED_NOTICE_KEY,
            REVEALED_EXPLANATION_KEY,
            DIRECT_ANSWER_EXPOSED_KEY,
            PENDING_SUBMISSION_REQUEST_ID_KEY,
            PENDING_SUBMISSION_PRESENTATION_ID_KEY,
            PENDING_SUBMISSION_PRACTICE_UNIT_ID_KEY,
            PENDING_SUBMISSION_CHOICE_ID_KEY,
            PENDING_SUBMISSION_DURATION_SECONDS_KEY,
            PENDING_SUBMISSION_OCCURRED_AT_KEY,
            PENDING_REVEAL_REQUEST_ID_KEY,
            PENDING_REVEAL_PRESENTATION_ID_KEY,
            PENDING_REVEAL_PRACTICE_UNIT_ID_KEY,
            PENDING_REVEAL_OCCURRED_AT_KEY,
        ).forEach { key -> savedStateHandle.remove<Any?>(key) }
    }

    private fun submissionCommand(
        assessmentItem: TutorAssessmentItem,
        practiceUnitId: String,
        choiceId: String,
    ): StudyChoiceSubmission {
        savedStateHandle.get<String>(PENDING_SUBMISSION_REQUEST_ID_KEY)?.let { requestId ->
            return StudyChoiceSubmission(
                requestId = requestId,
                presentationId = requireNotNull(
                    savedStateHandle[PENDING_SUBMISSION_PRESENTATION_ID_KEY],
                ),
                practiceUnitId = requireNotNull(
                    savedStateHandle[PENDING_SUBMISSION_PRACTICE_UNIT_ID_KEY],
                ),
                selectedChoiceId = requireNotNull(
                    savedStateHandle[PENDING_SUBMISSION_CHOICE_ID_KEY],
                ),
                responseOrdinal = 1,
                durationSeconds = requireNotNull(
                    savedStateHandle[PENDING_SUBMISSION_DURATION_SECONDS_KEY],
                ),
                occurredAtEpochMillis = requireNotNull(
                    savedStateHandle[PENDING_SUBMISSION_OCCURRED_AT_KEY],
                ),
            ).also { existing ->
                require(existing.presentationId == presentationId)
                require(existing.practiceUnitId == practiceUnitId)
                require(existing.selectedChoiceId == choiceId)
                require(assessmentItem.choices.any { it.id == existing.selectedChoiceId })
            }
        }

        val now = System.currentTimeMillis()
        return StudyChoiceSubmission(
            requestId = "request:$presentationId:choice:1",
            presentationId = presentationId,
            practiceUnitId = practiceUnitId,
            selectedChoiceId = choiceId,
            responseOrdinal = 1,
            durationSeconds = elapsedSeconds(now),
            occurredAtEpochMillis = now,
        ).also(::persistSubmissionCommand)
    }

    private fun persistSubmissionCommand(command: StudyChoiceSubmission) {
        savedStateHandle[PENDING_SUBMISSION_REQUEST_ID_KEY] = command.requestId
        savedStateHandle[PENDING_SUBMISSION_PRESENTATION_ID_KEY] = command.presentationId
        savedStateHandle[PENDING_SUBMISSION_PRACTICE_UNIT_ID_KEY] = command.practiceUnitId
        savedStateHandle[PENDING_SUBMISSION_CHOICE_ID_KEY] = command.selectedChoiceId
        savedStateHandle[PENDING_SUBMISSION_DURATION_SECONDS_KEY] = command.durationSeconds
        savedStateHandle[PENDING_SUBMISSION_OCCURRED_AT_KEY] = command.occurredAtEpochMillis
    }

    private fun revealCommand(practiceUnitId: String): StudyAnswerRevealRequest {
        savedStateHandle.get<String>(PENDING_REVEAL_REQUEST_ID_KEY)?.let { requestId ->
            return StudyAnswerRevealRequest(
                requestId = requestId,
                presentationId = requireNotNull(savedStateHandle[PENDING_REVEAL_PRESENTATION_ID_KEY]),
                practiceUnitId = requireNotNull(savedStateHandle[PENDING_REVEAL_PRACTICE_UNIT_ID_KEY]),
                occurredAtEpochMillis = requireNotNull(savedStateHandle[PENDING_REVEAL_OCCURRED_AT_KEY]),
            ).also { existing ->
                require(existing.presentationId == presentationId)
                require(existing.practiceUnitId == practiceUnitId)
            }
        }

        return StudyAnswerRevealRequest(
            requestId = "request:$presentationId:reveal:1",
            presentationId = presentationId,
            practiceUnitId = practiceUnitId,
            occurredAtEpochMillis = System.currentTimeMillis(),
        ).also(::persistRevealCommand)
    }

    private fun persistRevealCommand(command: StudyAnswerRevealRequest) {
        savedStateHandle[PENDING_REVEAL_REQUEST_ID_KEY] = command.requestId
        savedStateHandle[PENDING_REVEAL_PRESENTATION_ID_KEY] = command.presentationId
        savedStateHandle[PENDING_REVEAL_PRACTICE_UNIT_ID_KEY] = command.practiceUnitId
        savedStateHandle[PENDING_REVEAL_OCCURRED_AT_KEY] = command.occurredAtEpochMillis
    }

    private fun updateSaveStatus(status: TutorSaveStatus) {
        saveStatus = status
        savedStateHandle[SAVE_STATUS_KEY] = status.name
    }

    private fun updateSubmissionStatus(status: TutorSubmissionStatus) {
        submissionStatus = status
        savedStateHandle[SUBMISSION_STATUS_KEY] = status.name
    }

    private fun updateRevealStatus(status: TutorRevealStatus) {
        revealStatus = status
        savedStateHandle[REVEAL_STATUS_KEY] = status.name
    }

    private fun elapsedSeconds(nowEpochMillis: Long): Int =
        ((nowEpochMillis - presentationStartedAtEpochMillis).coerceAtLeast(0L) / 1_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private companion object {
        const val SELECTED_CHOICE_KEY = "tutor_selected_choice"
        const val SELECTED_ASSESSMENT_ITEM_KEY = "tutor_selected_assessment_item"
        const val SUBMITTED_CHOICE_KEY = "tutor_submitted_choice"
        const val SUBMITTED_ASSESSMENT_ITEM_KEY = "tutor_submitted_assessment_item"
        const val FOLLOW_UP_KEY = "tutor_follow_up"
        const val DRAFT_KEY = "tutor_draft"
        const val UNVERIFIED_NOTICE_KEY = "tutor_unverified_notice"
        const val SAVE_STATUS_KEY = "tutor_save_status"
        const val SUBMISSION_STATUS_KEY = "tutor_submission_status"
        const val REVEAL_STATUS_KEY = "tutor_reveal_status"
        const val REVEALED_EXPLANATION_KEY = "tutor_revealed_explanation"
        const val DIRECT_ANSWER_EXPOSED_KEY = "tutor_direct_answer_exposed"
        const val ACTIVE_PRESENTATION_KEY = "tutor_active_presentation"
        const val PRESENTATION_ID_KEY = "tutor_presentation_id"
        const val PRESENTATION_STARTED_AT_KEY = "tutor_presentation_started_at"
        const val PENDING_SUBMISSION_REQUEST_ID_KEY = "tutor_pending_submission_request_id"
        const val PENDING_SUBMISSION_PRESENTATION_ID_KEY = "tutor_pending_submission_presentation_id"
        const val PENDING_SUBMISSION_PRACTICE_UNIT_ID_KEY = "tutor_pending_submission_practice_unit_id"
        const val PENDING_SUBMISSION_CHOICE_ID_KEY = "tutor_pending_submission_choice_id"
        const val PENDING_SUBMISSION_DURATION_SECONDS_KEY = "tutor_pending_submission_duration_seconds"
        const val PENDING_SUBMISSION_OCCURRED_AT_KEY = "tutor_pending_submission_occurred_at"
        const val PENDING_REVEAL_REQUEST_ID_KEY = "tutor_pending_reveal_request_id"
        const val PENDING_REVEAL_PRESENTATION_ID_KEY = "tutor_pending_reveal_presentation_id"
        const val PENDING_REVEAL_PRACTICE_UNIT_ID_KEY = "tutor_pending_reveal_practice_unit_id"
        const val PENDING_REVEAL_OCCURRED_AT_KEY = "tutor_pending_reveal_occurred_at"

        fun restoredSaveStatus(savedStateHandle: SavedStateHandle): TutorSaveStatus {
            val savedStatus = savedStateHandle.get<String>(SAVE_STATUS_KEY)
                ?.let { runCatching { TutorSaveStatus.valueOf(it) }.getOrNull() }
                ?: TutorSaveStatus.IDLE
            return if (savedStatus == TutorSaveStatus.SAVING) {
                TutorSaveStatus.FAILED
            } else {
                savedStatus
            }
        }

        fun restoredSubmissionStatus(savedStateHandle: SavedStateHandle): TutorSubmissionStatus {
            val restored = savedStateHandle.get<String>(SUBMISSION_STATUS_KEY)
                ?.let { runCatching { TutorSubmissionStatus.valueOf(it) }.getOrNull() }
                ?: TutorSubmissionStatus.IDLE
            return if (restored == TutorSubmissionStatus.RECORDING) {
                TutorSubmissionStatus.FAILED
            } else {
                restored
            }
        }

        fun restoredRevealStatus(savedStateHandle: SavedStateHandle): TutorRevealStatus {
            val restored = savedStateHandle.get<String>(REVEAL_STATUS_KEY)
                ?.let { runCatching { TutorRevealStatus.valueOf(it) }.getOrNull() }
                ?: TutorRevealStatus.HIDDEN
            return when {
                savedStateHandle.get<String>(REVEALED_EXPLANATION_KEY) != null ->
                    TutorRevealStatus.REVEALED
                restored == TutorRevealStatus.RECORDING -> TutorRevealStatus.FAILED
                else -> restored
            }
        }
    }
}
