package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.LobbyMessageImage
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorToolName
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val TUTOR_LOBBY_PROMPT_POLICY_VERSION = ModelPromptPolicyVersions.TUTOR_LOBBY

internal fun buildTutorLobbyRequest(
    provider: ProviderCapabilitySnapshot,
    conversationId: String,
    messageOrdinal: Int,
    studentMessage: String,
    priorMessages: List<TutorChatHistoryEntry>,
    occurredAtEpochMillis: Long,
    approvedAtEpochMillis: Long = occurredAtEpochMillis,
    attempt: Int = 0,
    imageAssets: List<LobbyMessageImage> = emptyList(),
): ModelTaskRequest {
    require(conversationId.isNotBlank()) { "Tutor lobby conversation id must not be blank" }
    require(provider.supports(ModelTaskKind.TUTOR_LOBBY)) {
        "The current provider does not support tutor lobby messages"
    }
    require(attempt >= 0) { "Tutor lobby attempt must not be negative" }
    require(imageAssets.size <= ModelEgressManifest.MAX_LOBBY_IMAGE_ASSETS) {
        "Tutor lobby message carries too many images"
    }
    val imageRefs = imageAssets.mapIndexed { index, image ->
        CaptureSourceAssetRef(
            assetId = image.assetId,
            sha256 = image.sha256,
            width = image.width,
            height = image.height,
            pageIndex = index,
        )
    }
    val input = TutorLobbyInput(
        conversationId = conversationId,
        messageOrdinal = messageOrdinal,
        studentMessage = studentMessage,
        priorMessages = priorMessages.takeLast(TutorLobbyInput.MAX_PRIOR_MESSAGES),
        sourceImageAssetRefs = imageRefs,
        // Lobby 只声明错题本读取：MASTERY_READ 的产出（掌握度明细）无法归入 Lobby 的
        // 披露集合，注入 round-2 出网 prompt 会违反 least-disclosure；掌握度读取仅保留在
        // Respond（其披露集合含 RELEVANT_LEARNING_EVIDENCE）。
        toolDeclarations = listOf(TutorToolName.NOTEBOOK_READ),
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
            // 图片是消息的一部分：换图必须换请求标识，避免幂等重放串页。
            imageRefs.forEach { ref ->
                append(ref.assetId).append(':').append(ref.sha256).append('\n')
            }
            append(provider.providerConfigurationVersion)
        },
    ).take(24)
    val requestId = "tutor-lobby:$messageOrdinal:$requestHash:$attempt"
    val manifest = if (provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
        val includesImage = imageRefs.isNotEmpty()
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
            assets = imageAssets.map { image ->
                ModelEgressAssetGrant(
                    assetId = image.assetId,
                    sha256 = image.sha256,
                    byteSize = image.byteSize,
                    width = image.width,
                    height = image.height,
                )
            },
            disclosedData = if (includesImage) {
                ModelEgressManifest.TUTOR_LOBBY_IMAGE_DISCLOSURE
            } else {
                ModelEgressManifest.TUTOR_LOBBY_DISCLOSURE
            },
            prohibitedData = if (includesImage) {
                ModelEgressManifest.TUTOR_LOBBY_IMAGE_PROHIBITED_DATA
            } else {
                ModelEgressManifest.TUTOR_LOBBY_PROHIBITED_DATA
            },
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
