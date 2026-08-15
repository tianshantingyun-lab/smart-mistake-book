package com.tingyun.smartmistakebook.feature.tutor

import android.content.Context
import com.tingyun.smartmistakebook.core.data.TutorImageAssetConverter
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val SIMPLE_TUTOR_CONVERSATION_ID = "simple-tutor-conversation"
internal const val SIMPLE_TUTOR_PROMPT_POLICY_VERSION = ModelPromptPolicyVersions.TUTOR_LOBBY

/**
 * Build a unified tutor request that supports both text and images
 */
internal fun buildSimpleTutorRequest(
    messageOrdinal: Int,
    studentMessage: String,
    imageAssetRefs: List<String>,
    priorTasks: List<ModelTaskSnapshot>,
    occurredAtEpochMillis: Long,
    context: Context,
): ModelTaskRequest {
    // 加入时间戳确保每次发送的 requestId 唯一，避免数据库冲突
    val requestId = "simple-tutor-${messageOrdinal}-${occurredAtEpochMillis}"

    val priorMessages = priorTasks
        .mapNotNull { task ->
            val input = task.request.input as? TutorLobbyInput
            val output = task.output as? TutorLobbyOutput
            if (input != null && output != null) {
                TutorChatHistoryEntry(
                    studentMessage = input.studentMessage,
                    assistantMarkdown = output.messageMarkdown,
                )
            } else {
                null
            }
        }
        .takeLast(10) // Keep last 10 exchanges for context

    val input = TutorLobbyInput(
        conversationId = SIMPLE_TUTOR_CONVERSATION_ID,
        messageOrdinal = messageOrdinal,
        studentMessage = studentMessage,
        priorMessages = priorMessages,
        imageAssetRefs = imageAssetRefs,
    )

    // Build egress manifest with asset grants
    val converter = TutorImageAssetConverter(context)
    val assetGrants = converter.createAssetGrants(imageAssetRefs)

    val manifest = ModelEgressManifest(
        authorizationId = "simple-tutor-auth-${occurredAtEpochMillis}",
        subjectId = SIMPLE_TUTOR_CONVERSATION_ID,
        purpose = ModelEgressPurpose.TUTORING,
        authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_LOBBY),
        providerId = "default", // Will be overridden by actual provider
        modelId = "default",
        providerConfigurationVersion = "1",
        promptPolicyVersion = SIMPLE_TUTOR_PROMPT_POLICY_VERSION,
        approvedAtEpochMillis = occurredAtEpochMillis,
        assets = assetGrants,
        disclosedData = ModelEgressManifest.TUTOR_LOBBY_DISCLOSURE,
        prohibitedData = ModelEgressManifest.TUTOR_LOBBY_PROHIBITED_DATA,
    )

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

