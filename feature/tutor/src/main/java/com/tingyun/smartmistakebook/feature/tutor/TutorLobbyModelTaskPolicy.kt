package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.LobbyMessageImage
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.domain.TUTOR_TOOL_DECLARATIONS
import com.tingyun.smartmistakebook.core.model.ModelEgressDataClass
import com.tingyun.smartmistakebook.core.model.TutorRoundDisclosure
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
    /** 上文学生消息里的图片（最近一次带图消息的那几张），随本次一并出网。 */
    contextImageAssets: List<LobbyMessageImage> = emptyList(),
    /** 更早轮次的确定性摘要；空表示没有轮次被挤出原样窗口。 */
    priorDigest: String? = null,
): ModelTaskRequest {
    require(conversationId.isNotBlank()) { "Tutor lobby conversation id must not be blank" }
    require(provider.supports(ModelTaskKind.TUTOR_LOBBY)) {
        "The current provider does not support tutor lobby messages"
    }
    require(attempt >= 0) { "Tutor lobby attempt must not be negative" }
    require(imageAssets.size <= ModelEgressManifest.MAX_LOBBY_IMAGE_ASSETS) {
        "Tutor lobby message carries too many images"
    }
    require(contextImageAssets.size <= ModelEgressManifest.MAX_LOBBY_IMAGE_ASSETS) {
        "Tutor lobby message context carries too many images"
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
    val contextImageRefs = contextImageAssets.mapIndexed { index, image ->
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
        priorDigest = priorDigest,
        sourceImageAssetRefs = imageRefs,
        contextImageAssetRefs = contextImageRefs,
        // 同页声明全量五个（docs/tutor-surface-unification.md §5.6）：大厅与讲题会话是同一个页面，
        // 声明集按页面给，可用性交给既有的授权矩阵（意图 × 置信度 × 声明集）。写工具另有一道
        // 本地门控——只有"本轮有绑定题"才执行，而大厅轮次没有绑定题，等于必然被拒。
        toolDeclarations = TUTOR_TOOL_DECLARATIONS.toList(),
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
            append(priorDigest.orEmpty()).append('\n')
            // 图片是消息的一部分：换图必须换请求标识，避免幂等重放串页。
            // 上文图片同理——换了带的是哪几张图，就是另一次发送。
            (imageRefs + contextImageRefs).forEach { ref ->
                append(ref.assetId).append(':').append(ref.sha256).append('\n')
            }
            append(provider.providerConfigurationVersion)
        },
    ).take(24)
    val requestId = "tutor-lobby:$messageOrdinal:$requestHash:$attempt"
    val manifest = if (provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
        val egressAssets = imageAssets + contextImageAssets
        val includesImage = egressAssets.isNotEmpty()
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
            assets = egressAssets.map { image ->
                ModelEgressAssetGrant(
                    assetId = image.assetId,
                    sha256 = image.sha256,
                    byteSize = image.byteSize,
                    width = image.width,
                    height = image.height,
                )
            },
            // 披露只走 TutorRoundDisclosure 这一条口径（大厅＝无题轮、无候选菜单）。
            disclosedData = TutorRoundDisclosure.noQuestionRound(
                includesImage = includesImage,
            ),
            // 未披露集＝全集 − 已披露：两者必须互补，任何一边单独改都会在这里对不上。
            prohibitedData = ModelEgressDataClass.entries.toSet() -
                TutorRoundDisclosure.noQuestionRound(
                    includesImage = includesImage,
                ),
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
