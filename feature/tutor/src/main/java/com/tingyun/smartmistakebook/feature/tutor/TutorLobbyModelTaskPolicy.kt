package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationId
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorLobbyVisualRequest
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val TUTOR_LOBBY_CONVERSATION_ID = "tutor-lobby"
internal const val TUTOR_LOBBY_PROMPT_POLICY_VERSION = ModelPromptPolicyVersions.TUTOR_LOBBY

internal fun latestTutorLobbyConversationTasks(
    tasks: List<ModelTaskSnapshot>,
    conversationId: String = TUTOR_LOBBY_CONVERSATION_ID,
): List<ModelTaskSnapshot> = tasks
    .filter { task ->
        (task.request.input as? TutorLobbyInput)?.conversationId == conversationId
    }
    .groupBy { (it.request.input as TutorLobbyInput).messageOrdinal }
    .values
    .map { attempts ->
        attempts.maxWith(
            compareBy<ModelTaskSnapshot>(ModelTaskSnapshot::updatedAtEpochMillis)
                .thenBy(ModelTaskSnapshot::createdAtEpochMillis)
                .thenBy { it.request.requestId },
        )
    }
    .filterNot { it.status == ModelTaskStatus.CANCELLED }
    .sortedBy { (it.request.input as TutorLobbyInput).messageOrdinal }

internal fun ModelTaskSnapshot.canRetryTutorLobby(): Boolean =
    request.input is TutorLobbyInput &&
        status == ModelTaskStatus.RETRYABLE_FAILURE &&
        attemptCount < 2 &&
        (
            provider?.executionLocation != ModelExecutionLocation.LOCAL_NO_EGRESS ||
                request.tutorLobbyAttempt() < 1
            )

internal fun ModelTaskSnapshot.canResumeTutorLobby(): Boolean =
    request.input is TutorLobbyInput && status.isTutorExecutionPending()

internal sealed interface TutorLobbyVisualPresentation {
    data object Hidden : TutorLobbyVisualPresentation
    data object Preparing : TutorLobbyVisualPresentation
    data object SourceRequired : TutorLobbyVisualPresentation
}

/**
 * Lobby text has no verified question document or image. An explicit visual request is observable
 * immediately, then fails closed instead of fabricating a scene from free text.
 */
internal fun ModelTaskSnapshot.tutorLobbyVisualPresentation(): TutorLobbyVisualPresentation {
    val input = request.input as? TutorLobbyInput ?: return TutorLobbyVisualPresentation.Hidden
    if (input.explicitVisualRequest == null) return TutorLobbyVisualPresentation.Hidden
    return when (status) {
        ModelTaskStatus.WAITING_FOR_MODEL,
        ModelTaskStatus.QUEUED,
        ModelTaskStatus.RUNNING,
        ModelTaskStatus.STREAMING,
        -> TutorLobbyVisualPresentation.Preparing

        ModelTaskStatus.SUCCEEDED,
        ModelTaskStatus.RETRYABLE_FAILURE,
        ModelTaskStatus.PERMANENT_FAILURE,
        ModelTaskStatus.CANCELLED,
        -> TutorLobbyVisualPresentation.SourceRequired
    }
}

internal fun visibleTutorLobbyDirective(
    input: TutorLobbyInput,
    output: TutorLobbyOutput,
    currentMode: TutorExplanationMode,
    currentModeVersion: Long,
): TutorInteractionDirective? = output.interactionDirective.takeIf {
    currentMode == TutorExplanationMode.GUIDED &&
        input.explanationMode == currentMode &&
        input.modeVersion == currentModeVersion &&
        output.explanationMode == currentMode &&
        output.modeVersion == currentModeVersion
}

internal fun ModelTaskSnapshot.nextTutorLobbyRetryAttempt(): Int? {
    if (!canRetryTutorLobby()) return null
    return (request.tutorLobbyAttempt() + 1).takeIf { it <= 1 }
}

private fun ModelTaskRequest.tutorLobbyAttempt(): Int =
    requestId.substringAfterLast(':').toIntOrNull() ?: 0

internal fun buildTutorLobbyRequest(
    provider: ProviderCapabilitySnapshot,
    messageOrdinal: Int,
    studentMessage: String,
    priorMessages: List<TutorChatHistoryEntry>,
    occurredAtEpochMillis: Long,
    approvedAtEpochMillis: Long = occurredAtEpochMillis,
    attempt: Int = 0,
    conversationId: String = TUTOR_LOBBY_CONVERSATION_ID,
    explanationMode: TutorExplanationMode = TutorExplanationMode.DIRECT,
    modeVersion: Long = 0,
    explicitVisualRequest: TutorLobbyVisualRequest? = null,
    choiceInteractionAuthorized: Boolean = false,
    allowedVisualTargetIds: Set<String> = emptySet(),
): ModelTaskRequest {
    require(provider.supports(ModelTaskKind.TUTOR_LOBBY)) {
        "The current provider does not support tutor lobby messages"
    }
    require(attempt >= 0) { "Tutor lobby attempt must not be negative" }
    val input = TutorLobbyInput(
        conversationId = conversationId,
        messageOrdinal = messageOrdinal,
        studentMessage = studentMessage,
        priorMessages = priorMessages.takeLast(TutorLobbyInput.MAX_PRIOR_MESSAGES),
        explanationMode = explanationMode,
        modeVersion = modeVersion,
        explicitVisualRequest = explicitVisualRequest,
        choiceInteractionAuthorized = choiceInteractionAuthorized,
        allowedVisualTargetIds = allowedVisualTargetIds,
    )
    val requestHash = sha256(
        buildString {
            append(input.conversationId).append('\n')
            append(messageOrdinal).append('\n')
            append(studentMessage).append('\n')
            append(input.explanationMode.name).append('\n')
            append(input.modeVersion).append('\n')
            append(input.explicitVisualRequest?.kind?.name.orEmpty()).append('\n')
            append(input.explicitVisualRequest?.focusMarkdown.orEmpty()).append('\n')
            append(input.choiceInteractionAuthorized).append('\n')
            input.allowedVisualTargetIds.sorted().forEach { targetId ->
                append(targetId.length).append(':').append(targetId)
            }
            input.priorMessages.forEach { prior ->
                append(prior.studentMessage.length).append(':').append(prior.studentMessage)
                append(prior.assistantMarkdown.length).append(':').append(prior.assistantMarkdown)
            }
            append(provider.providerConfigurationVersion)
        },
    ).take(24)
    val requestId = "tutor-lobby:$messageOrdinal:$requestHash:$attempt"
    val manifest = if (provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
        ModelEgressManifest(
            authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
            subjectId = input.conversationId,
            purpose = ModelEgressPurpose.TUTORING,
            authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_LOBBY),
            providerId = provider.providerId,
            modelId = provider.modelId,
            providerConfigurationVersion = provider.providerConfigurationVersion,
            promptPolicyVersion = TUTOR_LOBBY_PROMPT_POLICY_VERSION,
            approvedAtEpochMillis = approvedAtEpochMillis,
            assets = emptyList(),
            disclosedData = ModelEgressManifest.TUTOR_LOBBY_DISCLOSURE,
            prohibitedData = ModelEgressManifest.TUTOR_LOBBY_PROHIBITED_DATA,
        )
    } else {
        null
    }
    return ModelTaskRequest(
        requestId = requestId,
        input = input,
        occurredAtEpochMillis = occurredAtEpochMillis,
        egressManifest = manifest,
    )
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
