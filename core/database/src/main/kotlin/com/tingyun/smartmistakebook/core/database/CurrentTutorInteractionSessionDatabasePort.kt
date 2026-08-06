package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import kotlinx.coroutines.flow.Flow

/**
 * Narrow persistence boundary for the v46 current Tutor owner.
 *
 * It carries session facts only. Question text is an opaque owner snapshot and none of these rows
 * is a mistake, mastery event, or teaching-knowledge record.
 */
interface CurrentTutorInteractionSessionDatabasePort {
    suspend fun activateCurrentTutorInteraction(
        command: ActivateCurrentTutorInteractionCommand,
    ): CurrentTutorInteractionActivationResult

    suspend fun readCurrentTutorInteraction(
        learnerId: String,
        conversationId: String,
    ): CurrentTutorInteractionBundle?

    fun observeCurrentTutorInteraction(
        learnerId: String,
        conversationId: String,
    ): Flow<CurrentTutorInteractionBundle?>

    fun observeTutorInteractionHistory(
        learnerId: String,
        conversationId: String,
    ): Flow<List<CurrentTutorInteractionEventRecord>>

    suspend fun readTutorAnswerExposureEvents(
        learnerId: String,
        modelTaskRequestIds: Set<String>,
    ): List<CurrentTutorInteractionEventRecord>

    suspend fun appendCurrentTutorInteraction(
        command: AppendCurrentTutorInteractionCommand,
    ): CurrentTutorInteractionAppendResult

    /**
     * Linearizes one open-response candidate against the persisted current Tutor authority.
     *
     * A successful consume is durable and one-shot for [command.candidateScopeFingerprint]. The
     * same candidate may replay after process death, but another candidate cannot reuse the scope.
     */
    suspend fun consumeCurrentTutorOpenResponseAuthorization(
        command: ConsumeCurrentTutorOpenResponseAuthorizationCommand,
    ): CurrentTutorOpenResponseAuthorizationConsumeResult
}

data class ActivateCurrentTutorInteractionCommand(
    val scopeId: String,
    val learnerId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val authorityConversationId: String,
    val authorityConversationGeneration: Long,
    val authorityConversationStateVersion: Long,
    val authorityTurnReceiptId: String,
    val authorityTurnOrdinal: Int,
    val authorityRequestVersion: Long,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val questionDocumentSnapshot: String,
    val questionFingerprint: String,
    val subject: SubjectKind,
    val problemAnchorId: String,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val turnReferenceId: String,
    val turnOrdinal: Int,
    val turnGeneration: Long,
    val cycleOrdinal: Int,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val requestVersion: Long,
    val learningWritePermissionVersion: Long,
    val presentationFingerprint: String,
    val problemFingerprint: String,
    val problemFamilyFingerprint: String,
    val attributionPolicyVersion: String,
    val responsePolicyVersion: String,
    val rubricCanonicalFingerprint: String,
    val knowledgeAuthorityFingerprint: String,
    val evaluator: String,
    val evaluatorPolicyFingerprint: String,
    val activationFingerprint: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        listOf(
            scopeId,
            learnerId,
            conversationId,
            authorityConversationId,
            authorityTurnReceiptId,
            questionDocumentId,
            problemAnchorId,
            turnReferenceId,
            attributionPolicyVersion,
            responsePolicyVersion,
            evaluator,
        ).forEach { requireCurrentTutorOpaque(it) }
        listOf(
            questionFingerprint,
            presentationFingerprint,
            problemFingerprint,
            problemFamilyFingerprint,
            rubricCanonicalFingerprint,
            knowledgeAuthorityFingerprint,
            evaluatorPolicyFingerprint,
            activationFingerprint,
        ).forEach(::requireCurrentTutorFingerprint)
        require(conversationGeneration > 0 && authorityConversationGeneration > 0)
        require(conversationStateVersion >= 0 && authorityConversationStateVersion >= 0)
        require(questionRevisionNumber > 0)
        require(questionDocumentSnapshot.isNotBlank() && questionDocumentSnapshot.length <= 512_000)
        require(subject != SubjectKind.GENERAL)
        require(
            modeVersion >= 0 && requestVersion >= 0 && authorityRequestVersion >= 0 &&
                learningWritePermissionVersion >= 0,
        )
        require(authorityTurnOrdinal > 0)
        require(turnOrdinal > 0 && turnGeneration > 0 && cycleOrdinal > 0)
        // A freshly activated question has no student submission yet. Model execution retries are
        // deliberately not projected into this counter; the first durable student event advances
        // zero to one inside appendCurrentTutorInteraction.
        require(attemptOrdinal in 0..17 && hintCount in 0..32)
        require(occurredAtEpochMillis >= 0)
    }
}

enum class CurrentTutorInteractionActivationDisposition {
    ACTIVATED,
    DUPLICATE,
    STALE,
    AUTHORITY_MISMATCH,
}

data class CurrentTutorInteractionActivationResult(
    val disposition: CurrentTutorInteractionActivationDisposition,
    val bundle: CurrentTutorInteractionBundle?,
)

data class CurrentTutorInteractionScopeRecord(
    val scopeId: String,
    val learnerId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val authorityConversationId: String,
    val authorityConversationGeneration: Long,
    val authorityConversationStateVersion: Long,
    val authorityTurnReceiptId: String,
    val authorityTurnOrdinal: Int,
    val authorityRequestVersion: Long,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val questionDocumentSnapshot: String,
    val questionFingerprint: String,
    val subject: SubjectKind,
    val problemAnchorId: String,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val turnReferenceId: String,
    val turnOrdinal: Int,
    val turnGeneration: Long,
    val cycleOrdinal: Int,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val requestVersion: Long,
    val learningWritePermissionVersion: Long,
    val presentationFingerprint: String,
    val problemFingerprint: String,
    val problemFamilyFingerprint: String,
    val attributionPolicyVersion: String,
    val responsePolicyVersion: String,
    val rubricCanonicalFingerprint: String,
    val knowledgeAuthorityFingerprint: String,
    val evaluator: String,
    val evaluatorPolicyFingerprint: String,
    val activationFingerprint: String,
    val createdAtEpochMillis: Long,
)

data class CurrentTutorInteractionHeadRecord(
    val learnerId: String,
    val conversationId: String,
    val currentScopeId: String,
    val stateVersion: Long,
    val stateFingerprint: String,
    val updatedAtEpochMillis: Long,
)

data class CurrentTutorInteractionBundle(
    val scope: CurrentTutorInteractionScopeRecord,
    val head: CurrentTutorInteractionHeadRecord,
    val events: List<CurrentTutorInteractionEventRecord>,
) {
    init {
        require(scope.learnerId == head.learnerId)
        require(scope.conversationId == head.conversationId)
        require(scope.scopeId == head.currentScopeId)
        require(events.all { it.scopeId == scope.scopeId && it.learnerId == scope.learnerId })
    }
}

enum class CurrentTutorInteractionEventKind {
    CHOICE,
    VISUAL_SELECTION,
    HINT_SHOWN,
    EVIDENCE_CANCELLATION,
    MOVE,
    SOLUTION_REVEAL,
    ANSWER_EXPOSURE,
    SESSION_ANCHOR,
    FREE_RESPONSE_SUBMISSION_CLAIM,
    OPEN_RESPONSE_CANDIDATE_CLAIM,
}

data class AppendCurrentTutorInteractionCommand(
    val scopeId: String,
    val learnerId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val questionFingerprint: String,
    val subject: SubjectKind,
    val problemAnchorId: String,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val learningWritePermissionVersion: Long,
    val turnReferenceId: String,
    val turnOrdinal: Int,
    val turnGeneration: Long,
    val cycleOrdinal: Int,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val expectedStateVersion: Long,
    val expectedStateFingerprint: String,
    val eventId: String,
    val eventKind: CurrentTutorInteractionEventKind,
    val authorizationPurpose: String,
    val authorizationRequestId: String?,
    val idempotencyKey: String,
    val requestVersion: Long,
    val payloadFingerprint: String,
    val occurredAtEpochMillis: Long,
    val diagnosticStemMarkdown: String? = null,
    val selectedChoiceId: String? = null,
    val selectedChoiceMarkdown: String? = null,
    val selectionWasCorrect: Boolean? = null,
    val feedbackMarkdown: String? = null,
    val evidenceRequestId: String? = null,
    val requestedMove: String? = null,
    val solutionRevealed: Boolean = false,
    val surfaceKind: String? = null,
    val modelTaskRequestId: String? = null,
    val responseOrdinal: Int? = null,
    val sceneSourceKind: String? = null,
    val sceneTaskRequestId: String? = null,
    val sceneId: String? = null,
    val sceneFingerprint: String? = null,
    val hitProofId: String? = null,
    val panelId: String? = null,
    val frameFingerprint: String? = null,
    val stepIndex: Int? = null,
    val selectedTargetId: String? = null,
    val targetRevisionRef: String? = null,
    val targetPracticeRef: String? = null,
    val sourceKind: String? = null,
) {
    init {
        listOf(
            scopeId,
            learnerId,
            conversationId,
            questionDocumentId,
            problemAnchorId,
            turnReferenceId,
            eventId,
            authorizationPurpose,
            idempotencyKey,
        )
            .forEach(::requireCurrentTutorOpaque)
        authorizationRequestId?.let(::requireCurrentTutorOpaque)
        requireCurrentTutorFingerprint(expectedStateFingerprint)
        requireCurrentTutorFingerprint(questionFingerprint)
        requireCurrentTutorFingerprint(payloadFingerprint)
        require(conversationGeneration > 0 && conversationStateVersion >= 0)
        require(questionRevisionNumber > 0 && subject != SubjectKind.GENERAL)
        require(modeVersion >= 0 && learningWritePermissionVersion >= 0)
        require(turnOrdinal > 0 && turnGeneration > 0 && cycleOrdinal > 0)
        require(attemptOrdinal in 1..17 && hintCount in 0..32)
        require(expectedStateVersion >= 0 && requestVersion >= 0 && occurredAtEpochMillis >= 0)
    }
}

data class ConsumeCurrentTutorOpenResponseAuthorizationCommand(
    val scopeId: String,
    val learnerId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val questionFingerprint: String,
    val subject: SubjectKind,
    val problemAnchorId: String,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val learningWritePermissionVersion: Long,
    val turnReferenceId: String,
    val turnOrdinal: Int,
    val turnGeneration: Long,
    val cycleOrdinal: Int,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val requestVersion: Long,
    val expectedStateVersion: Long,
    val expectedStateFingerprint: String,
    val evidenceRequestId: String,
    val candidateScopeFingerprint: String,
    val candidateIdempotencyKey: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        listOf(
            scopeId,
            learnerId,
            conversationId,
            questionDocumentId,
            problemAnchorId,
            turnReferenceId,
            evidenceRequestId,
        ).forEach(::requireCurrentTutorOpaque)
        listOf(
            questionFingerprint,
            expectedStateFingerprint,
            candidateScopeFingerprint,
            candidateIdempotencyKey,
        ).forEach(::requireCurrentTutorFingerprint)
        require(conversationGeneration > 0 && conversationStateVersion >= 0)
        require(questionRevisionNumber > 0 && subject != SubjectKind.GENERAL)
        require(modeVersion >= 0 && learningWritePermissionVersion >= 0 && requestVersion >= 0)
        require(turnOrdinal > 0 && turnGeneration > 0 && cycleOrdinal > 0)
        require(attemptOrdinal in 1..17 && hintCount in 0..32)
        require(expectedStateVersion >= 0 && occurredAtEpochMillis >= 0)
    }
}

enum class CurrentTutorOpenResponseAuthorizationConsumeDisposition {
    CONSUMED,
    DUPLICATE,
    NOT_CURRENT,
    REJECTED,
}

data class CurrentTutorOpenResponseAuthorizationConsumeResult(
    val disposition: CurrentTutorOpenResponseAuthorizationConsumeDisposition,
    val recordedAtEpochMillis: Long,
)

data class CurrentTutorInteractionEventRecord(
    val eventId: String,
    val scopeId: String,
    val learnerId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val questionFingerprint: String,
    val subject: SubjectKind,
    val problemAnchorId: String,
    val explanationMode: TutorExplanationMode,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val modeVersion: Long,
    val learningWritePermissionVersion: Long,
    val turnReferenceId: String,
    val turnGeneration: Long,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val eventSequence: Long,
    val committedStateFingerprint: String,
    val eventKind: CurrentTutorInteractionEventKind,
    val authorizationPurpose: String,
    val authorizationRequestId: String?,
    val idempotencyKey: String,
    val requestVersion: Long,
    val payloadFingerprint: String,
    val occurredAtEpochMillis: Long,
    val recordedAtEpochMillis: Long,
    val diagnosticStemMarkdown: String?,
    val selectedChoiceId: String?,
    val selectedChoiceMarkdown: String?,
    val selectionWasCorrect: Boolean?,
    val feedbackMarkdown: String?,
    val evidenceRequestId: String?,
    val requestedMove: String?,
    val solutionRevealed: Boolean,
    val surfaceKind: String?,
    val modelTaskRequestId: String?,
    val responseOrdinal: Int?,
    val sceneSourceKind: String?,
    val sceneTaskRequestId: String?,
    val sceneId: String?,
    val sceneFingerprint: String?,
    val hitProofId: String?,
    val panelId: String?,
    val frameFingerprint: String?,
    val stepIndex: Int?,
    val selectedTargetId: String?,
    val targetRevisionRef: String?,
    val targetPracticeRef: String?,
    val sourceKind: String?,
)

enum class CurrentTutorInteractionAppendDisposition {
    APPLIED,
    DUPLICATE,
    RELOAD_REQUIRED,
    NOT_FOUND,
    REJECTED,
}

data class CurrentTutorInteractionAppendResult(
    val disposition: CurrentTutorInteractionAppendDisposition,
    val head: CurrentTutorInteractionHeadRecord?,
    val event: CurrentTutorInteractionEventRecord?,
    val recordedAtEpochMillis: Long,
)

private fun requireCurrentTutorOpaque(value: String) {
    require(
        value.isNotBlank() && value == value.trim() && value.length <= 256 &&
            value.none(Char::isISOControl),
    )
}

private fun requireCurrentTutorFingerprint(value: String) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' })
}
