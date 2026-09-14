package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorLobbyTasksTest {
    @Test
    fun lobbyRoundTripsAndValidatesItsExactConversationPosition() {
        val request = request()
        val output = TutorLobbyOutput(
            conversationId = "tutor-lobby",
            messageOrdinal = 2,
            messageMarkdown = "你可以把现在卡住的步骤直接发来。",
            intentDecision = TutorIntentDecision.ambiguousDefault(),
            modelVersion = "model-v1",
        )

        val decodedRequest = ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(request))
        val decodedOutput = ModelTaskCodec.decodeOutput(ModelTaskCodec.encodeOutput(output))

        assertEquals(request, decodedRequest)
        assertEquals(output, decodedOutput)
        assertTrue(ModelTaskCompletionValidator.validate(request, output).isEmpty())
    }

    @Test
    fun lobbyThinkingMarkdownRoundTripsAndRejectsActiveContent() {
        val withThinking = TutorLobbyOutput(
            conversationId = "tutor-lobby",
            messageOrdinal = 2,
            messageMarkdown = "你可以把现在卡住的步骤直接发来。",
            thinkingMarkdown = "这条消息只想确认学生从哪一步开始，不需要任何本地写入。",
            intentDecision = TutorIntentDecision.ambiguousDefault(),
            modelVersion = "model-v1",
        )
        assertEquals(
            withThinking,
            ModelTaskCodec.decodeOutput(ModelTaskCodec.encodeOutput(withThinking)),
        )
        assertTrue(ModelTaskCompletionValidator.validate(request(), withThinking).isEmpty())
        assertTrue(
            runCatching {
                withThinking.copy(thinkingMarkdown = "试着调用 javascript:alert(1)")
            }.isFailure,
        )
    }

    @Test
    fun lobbyRejectsContextMismatchAndEveryWriteLikeCapability() {
        val mismatch = TutorLobbyOutput(
            conversationId = "tutor-lobby",
            messageOrdinal = 3,
            messageMarkdown = "我需要先确认你想问哪一步。",
            modelVersion = "model-v1",
        )
        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_CONTEXT_MISMATCH),
            ModelTaskCompletionValidator.validate(request(), mismatch).map { it.code },
        )

        val writeRequest = TutorIntentDecision(
            intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
            confidence = 0.98,
            explicitActionRequest = true,
            memoryPreference = TutorMemoryPreference.UNCHANGED,
            requestedLocalCapability = TutorRequestedLocalCapability.OFFER_SAVE_CURRENT_QUESTION,
        )
        assertTrue(
            runCatching {
                TutorLobbyOutput(
                    conversationId = "tutor-lobby",
                    messageOrdinal = 2,
                    messageMarkdown = "是否保存应由本机界面确认。",
                    intentDecision = writeRequest,
                    modelVersion = "model-v1",
                )
            }.isFailure,
        )
    }

    @Test
    fun lobbyRejectsActiveHtmlLinksAndUrlsInBody() {
        // C1：Lobby 终稿切 richtext 前必须与 Respond 同强度——正文不得含 HTML/链接/URL。
        listOf(
            "<div>答案</div>",
            "看这条链接 [说明](https://example.com)",
            "详见 https://example.com",
            "`inline code`",
        ).forEach { unsafe ->
            assertTrue(
                "Lobby 应拒绝: $unsafe",
                runCatching {
                    TutorLobbyOutput(
                        conversationId = "tutor-lobby",
                        messageOrdinal = 2,
                        messageMarkdown = unsafe,
                        modelVersion = "model-v1",
                    )
                }.isFailure,
            )
        }
    }

    @Test
    fun externalLobbyManifestDisclosesOnlyMessageAndRecentConversation() {
        val provider = provider()
        val request = request(
            ModelEgressManifest(
                authorizationId = "lobby-authorization",
                subjectId = "tutor-lobby",
                purpose = ModelEgressPurpose.TUTORING,
                authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_LOBBY),
                providerId = provider.providerId,
                modelId = provider.modelId,
                providerConfigurationVersion = provider.providerConfigurationVersion,
                promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_LOBBY,
                approvedAtEpochMillis = 1_000,
                assets = emptyList(),
                disclosedData = ModelEgressManifest.TUTOR_LOBBY_DISCLOSURE,
                prohibitedData = ModelEgressManifest.TUTOR_LOBBY_PROHIBITED_DATA,
            ),
        )

        val execution = ModelEgressPolicy.authorize(request, provider, nowEpochMillis = 1_000)

        assertTrue(execution.permit is ModelExecutionPermit.External)
        assertEquals(
            setOf(
                ModelEgressDataClass.STUDENT_TUTOR_MESSAGE,
                ModelEgressDataClass.TUTOR_CONVERSATION_CONTEXT,
            ),
            request.egressManifest?.disclosedData,
        )
    }

    @Test
    fun lobbyMessageImageManifestAuthorizesTheExactStudentSelectedScope() {
        val provider = provider()
        val image = CaptureSourceAssetRef(
            assetId = "message-image-1",
            sha256 = "a".repeat(64),
            width = 1080,
            height = 1440,
            pageIndex = 0,
        )
        val manifest = ModelEgressManifest(
            authorizationId = "lobby-authorization-images",
            subjectId = "tutor-lobby",
            purpose = ModelEgressPurpose.TUTORING,
            authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_LOBBY),
            providerId = provider.providerId,
            modelId = provider.modelId,
            providerConfigurationVersion = provider.providerConfigurationVersion,
            promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_LOBBY,
            approvedAtEpochMillis = 1_000,
            assets = listOf(
                ModelEgressAssetGrant(
                    assetId = image.assetId,
                    sha256 = image.sha256,
                    byteSize = 1_024,
                    width = image.width,
                    height = image.height,
                ),
            ),
            disclosedData = ModelEgressManifest.TUTOR_LOBBY_IMAGE_DISCLOSURE,
            prohibitedData = ModelEgressManifest.TUTOR_LOBBY_IMAGE_PROHIBITED_DATA,
        )
        val request = request(
            manifest = manifest,
            images = listOf(image),
        )

        val execution = ModelEgressPolicy.authorize(request, provider, nowEpochMillis = 1_000)

        assertTrue(execution.permit is ModelExecutionPermit.External)
        assertEquals(
            ModelEgressManifest.TUTOR_LOBBY_IMAGE_DISCLOSURE,
            request.egressManifest?.disclosedData,
        )
    }

    @Test
    fun lobbyImageEgressRejectsATextOnlyDisclosure() {
        val provider = provider()
        val image = CaptureSourceAssetRef(
            assetId = "message-image-1",
            sha256 = "a".repeat(64),
            width = 1080,
            height = 1440,
            pageIndex = 0,
        )
        // 带图但披露清单仍按纯文本构造：在 manifest 构造期就被拒绝，
        // 根本到不了授权或出网。
        val failure = runCatching {
            ModelEgressManifest(
                authorizationId = "lobby-authorization-text",
                subjectId = "tutor-lobby",
                purpose = ModelEgressPurpose.TUTORING,
                authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_LOBBY),
                providerId = provider.providerId,
                modelId = provider.modelId,
                providerConfigurationVersion = provider.providerConfigurationVersion,
                promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_LOBBY,
                approvedAtEpochMillis = 1_000,
                assets = listOf(
                    ModelEgressAssetGrant(
                        assetId = image.assetId,
                        sha256 = image.sha256,
                        byteSize = 1_024,
                        width = image.width,
                        height = image.height,
                    ),
                ),
                disclosedData = ModelEgressManifest.TUTOR_LOBBY_DISCLOSURE,
                prohibitedData = ModelEgressManifest.TUTOR_LOBBY_PROHIBITED_DATA,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun lobbyMessageRejectsMoreImagesThanTheMessageBudget() {
        val images = (0 until ModelEgressManifest.MAX_LOBBY_IMAGE_ASSETS + 1).map { index ->
            CaptureSourceAssetRef(
                assetId = "message-image-$index",
                sha256 = "a".repeat(64),
                width = 1080,
                height = 1440,
                pageIndex = index,
            )
        }

        val failure = runCatching {
            TutorLobbyInput(
                conversationId = "tutor-lobby",
                messageOrdinal = 2,
                studentMessage = "看看这些图",
                sourceImageAssetRefs = images,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun request(
        manifest: ModelEgressManifest? = null,
        images: List<CaptureSourceAssetRef> = emptyList(),
    ) = ModelTaskRequest(
        requestId = "tutor-lobby-request",
        input = TutorLobbyInput(
            conversationId = "tutor-lobby",
            messageOrdinal = 2,
            studentMessage = "我应该从哪里开始？",
            priorMessages = listOf(
                TutorChatHistoryEntry(
                    studentMessage = "你好",
                    assistantMarkdown = "你好，你现在想讲哪道题？",
                ),
            ),
            sourceImageAssetRefs = images,
        ),
        occurredAtEpochMillis = 1_000,
        egressManifest = manifest,
    )

    private fun provider() = ProviderCapabilitySnapshot(
        providerId = "provider",
        providerDisplayName = "模型",
        modelId = "model",
        supportedTasks = setOf(ModelTaskKind.TUTOR_LOBBY),
        supportsImageInput = false,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = "configuration-v1",
    )
}
