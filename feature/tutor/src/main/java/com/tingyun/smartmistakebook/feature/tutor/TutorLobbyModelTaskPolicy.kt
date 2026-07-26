package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val TUTOR_LOBBY_CONVERSATION_ID = "tutor-lobby"
internal const val TUTOR_LOBBY_PROMPT_POLICY_VERSION = ModelPromptPolicyVersions.TUTOR_LOBBY

internal fun latestTutorLobbyConversationTasks(
    tasks: List<ModelTaskSnapshot>,
): List<ModelTaskSnapshot> = tasks
    .filter { it.request.input is TutorLobbyInput }
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
): ModelTaskRequest {
    require(provider.supports(ModelTaskKind.TUTOR_LOBBY)) {
        "The current provider does not support tutor lobby messages"
    }
    require(attempt >= 0) { "Tutor lobby attempt must not be negative" }
    val input = TutorLobbyInput(
        conversationId = TUTOR_LOBBY_CONVERSATION_ID,
        messageOrdinal = messageOrdinal,
        studentMessage = studentMessage,
        priorMessages = priorMessages.takeLast(TutorLobbyInput.MAX_PRIOR_MESSAGES),
    )
    val requestHash = sha256(
        buildString {
            append(input.conversationId).append('\n')
            append(messageOrdinal).append('\n')
            append(studentMessage).append('\n')
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
            authorizationId = "authorization:$requestId",
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
