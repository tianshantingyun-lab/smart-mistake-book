package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorLobbyTasksTest {
    @Test
    fun legacyLobbyPayloadDefaultsToDirectWithoutInteractionOrVisualWork() {
        val encoded = ModelTaskCodec.encodeRequest(request())
        val root = Json.parseToJsonElement(encoded).jsonObject
        val legacyInput = root.getValue("input").jsonObject.toMutableMap().apply {
            remove("explanationMode")
            remove("modeVersion")
            remove("explicitVisualRequest")
            remove("choiceInteractionAuthorized")
            remove("allowedVisualTargetIds")
        }
        val legacy = Json.encodeToString(JsonObject.serializer(), JsonObject(root.toMutableMap().apply {
            put("input", JsonObject(legacyInput))
        }))

        val decoded = ModelTaskCodec.decodeRequest(legacy).input as TutorLobbyInput

        assertEquals(TutorExplanationMode.DIRECT, decoded.explanationMode)
        assertEquals(0L, decoded.modeVersion)
        assertEquals(null, decoded.explicitVisualRequest)
        assertEquals(false, decoded.choiceInteractionAuthorized)
        assertTrue(decoded.allowedVisualTargetIds.isEmpty())

        val encodedOutput = ModelTaskCodec.encodeOutput(
            TutorLobbyOutput(
                conversationId = "tutor-lobby",
                messageOrdinal = 1,
                messageMarkdown = "直接回复。",
                modelVersion = "model-v1",
            ),
        )
        val outputRoot = Json.parseToJsonElement(encodedOutput).jsonObject.toMutableMap().apply {
            remove("explanationMode")
            remove("modeVersion")
            remove("responseIntent")
            remove("interactionDirective")
        }
        val decodedOutput = ModelTaskCodec.decodeOutput(
            Json.encodeToString(JsonObject.serializer(), JsonObject(outputRoot)),
        ) as TutorLobbyOutput
        assertEquals(TutorExplanationMode.DIRECT, decodedOutput.explanationMode)
        assertEquals(TutorResponseIntent.EXPLAIN, decodedOutput.responseIntent)
        assertEquals(null, decodedOutput.interactionDirective)
    }

    @Test
    fun lobbyCodecRejectsUnknownPolicyOrAuthorityFields() {
        val root = Json.parseToJsonElement(ModelTaskCodec.encodeRequest(request())).jsonObject
        val injectedInput = root.getValue("input").jsonObject.toMutableMap().apply {
            put("learnerId", JsonPrimitive("must-not-cross-egress"))
        }
        val injected = Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(root.toMutableMap().apply { put("input", JsonObject(injectedInput)) }),
        )

        assertTrue(runCatching { ModelTaskCodec.decodeRequest(injected) }.isFailure)
    }

    @Test
    fun currentInteractionPolicyHasStrictVersionTextCountAndIdBudgets() {
        val policy = input().copy(
            explanationMode = TutorExplanationMode.GUIDED,
            modeVersion = 7,
            choiceInteractionAuthorized = true,
            allowedVisualTargetIds = setOf("node-a", "cell:2.point_3"),
            explicitVisualRequest = TutorLobbyVisualRequest(
                kind = TutorLobbyVisualKind.ANIMATION,
                focusMarkdown = "只看液面如何联动。",
            ),
        ).toCurrentTutorInteractionPolicy()

        assertEquals(CurrentTutorInteractionPolicy.CURRENT_SCHEMA_VERSION, policy.schemaVersion)
        assertEquals(7L, policy.modeVersion)
        assertEquals(setOf("node-a", "cell:2.point_3"), policy.allowedVisualTargetIds)

        listOf("", "-node", "node/answer", "a".repeat(65)).forEach { invalidId ->
            assertTrue(
                runCatching { input().copy(allowedVisualTargetIds = setOf(invalidId)) }.isFailure,
            )
        }
        assertTrue(
            runCatching {
                input().copy(
                    allowedVisualTargetIds = (0 until 32).map { index ->
                        "n$index-${"x".repeat(58)}"
                    }.toSet(),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                input().copy(modeVersion = CurrentTutorInteractionPolicy.MAX_MODE_VERSION + 1)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                TutorLobbyVisualRequest(
                    TutorLobbyVisualKind.DIAGRAM,
                    "图".repeat(CurrentTutorInteractionPolicy.MAX_VISUAL_FOCUS_CHARS + 1),
                )
            }.isFailure,
        )
    }

    @Test
    fun currentInteractionPolicyWireShapeCannotGrowIntoLearnerEvidenceOrProofData() {
        val policy = input().copy(
            allowedVisualTargetIds = linkedSetOf("node-z", "node-a"),
        ).toCurrentTutorInteractionPolicy()
        val wire = Json.parseToJsonElement(
            CurrentTutorInteractionPolicyWire.encode(policy),
        ).jsonObject
        val wireFields = wire.keys

        assertEquals(
            setOf(
                "schemaVersion",
                "explanationMode",
                "modeVersion",
                "choiceInteractionAuthorized",
                "allowedVisualTargetIds",
                "explicitVisualRequest",
            ),
            wireFields,
        )
        assertEquals(
            listOf("node-a", "node-z"),
            wire.getValue("allowedVisualTargetIds").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(wireFields.none { field ->
            field.contains("learner", ignoreCase = true) ||
                field.contains("database", ignoreCase = true) ||
                field.contains("answer", ignoreCase = true) ||
                field.contains("proof", ignoreCase = true) ||
                field.contains("evidence", ignoreCase = true)
        })
    }

    @Test
    fun visualTargetSetOrderDoesNotChangeAnyRequestFingerprint() {
        val first = input().copy(
            allowedVisualTargetIds = linkedSetOf("node-z", "node-a"),
        )
        val second = input().copy(
            allowedVisualTargetIds = linkedSetOf("node-a", "node-z"),
        )

        assertEquals(
            ModelEgressAuthorizationId.forInput("request", first),
            ModelEgressAuthorizationId.forInput("request", second),
        )
        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(first),
            ModelTaskLogicalOperationFingerprint.of(second),
        )
        assertEquals(
            ModelTaskFingerprint.of(request(input = first)),
            ModelTaskFingerprint.of(request(input = second)),
        )
    }

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
    fun lobbyUnsafeStudentMessageUsesTheTypedInputBoundary() {
        val invalid = runCatching {
            TutorLobbyInput(
                conversationId = "tutor-lobby",
                messageOrdinal = 1,
                studentMessage = "不可见\u202E控制",
            )
        }.exceptionOrNull()

        assertTrue(invalid is InvalidTutorStudentMessageException)
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
    fun directModeRejectsEveryInteractionAndModeVersionMismatchRejectsLateOutput() {
        val input = input().copy(modeVersion = 4)
        assertTrue(
            runCatching {
                TutorLobbyOutput(
                    conversationId = input.conversationId,
                    messageOrdinal = input.messageOrdinal,
                    messageMarkdown = "先看你最不确定的关系。",
                    intentDecision = TutorIntentDecision.currentQuestionDefault(),
                    explanationMode = TutorExplanationMode.DIRECT,
                    modeVersion = 4,
                    responseIntent = TutorResponseIntent.ASK,
                    interactionDirective =
                        TutorInteractionDirective.FreeResponse("你会先判断哪个量？"),
                    modelVersion = "model-v1",
                )
            }.isFailure,
        )

        val late = TutorLobbyOutput(
            conversationId = input.conversationId,
            messageOrdinal = input.messageOrdinal,
            messageMarkdown = "按当前步骤继续分析。",
            explanationMode = TutorExplanationMode.DIRECT,
            modeVersion = 3,
            modelVersion = "model-v1",
        )
        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_CONTEXT_MISMATCH),
            ModelTaskCompletionValidator.validate(request(input = input), late).map { it.code },
        )

        val disguisedQuestion = TutorLobbyOutput(
            conversationId = input.conversationId,
            messageOrdinal = input.messageOrdinal,
            messageMarkdown = "你会先判断哪个量？",
            intentDecision = TutorIntentDecision.currentQuestionDefault(),
            explanationMode = TutorExplanationMode.DIRECT,
            modeVersion = 4,
            responseIntent = TutorResponseIntent.EXPLAIN,
            modelVersion = "model-v1",
        )
        assertEquals(null, disguisedQuestion.locallyConstrainedFor(input))
        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
            ModelTaskCompletionValidator.validate(request(input = input), disguisedQuestion)
                .map { it.code },
        )
    }

    @Test
    fun directModeRejectsUnpunctuatedStudentInteractionButKeepsTeachingNarration() {
        val input = input().copy(modeVersion = 4)
        val explanation = TutorLobbyOutput(
            conversationId = input.conversationId,
            messageOrdinal = input.messageOrdinal,
            messageMarkdown = "先求导，再判断符号。",
            intentDecision = TutorIntentDecision.currentQuestionDefault(),
            explanationMode = TutorExplanationMode.DIRECT,
            modeVersion = 4,
            responseIntent = TutorResponseIntent.EXPLAIN,
            modelVersion = "model-v1",
        )

        listOf(
            "你先判断哪个量更关键。",
            "请回答这一步如何处理。",
            "选择你认为正确的关系。",
            "说出你认为最关键的关系。",
            "填写空格中的结果。",
            "点击图中的关键位置。",
        ).forEach { disguisedInteraction ->
            assertEquals(
                disguisedInteraction,
                null,
                explanation.copy(messageMarkdown = disguisedInteraction)
                    .locallyConstrainedFor(input),
            )
        }

        listOf(
            "先求导，再判断符号。",
            "判断依据是导数在区间内的符号。",
            "选择依据是两个向量的夹角。",
        ).forEach { teachingNarration ->
            val candidate = explanation.copy(messageMarkdown = teachingNarration)
            assertEquals(teachingNarration, candidate, candidate.locallyConstrainedFor(input))
        }
    }

    @Test
    fun guidedLobbyNeverUsesModelTextAsUnexposedInteractionAuthority() {
        val guided = input().copy(
            explanationMode = TutorExplanationMode.GUIDED,
            modeVersion = 2,
        )
        val modelFreeResponse = TutorLobbyOutput(
            conversationId = guided.conversationId,
            messageOrdinal = guided.messageOrdinal,
            messageMarkdown = "最终答案是 2，但先说出你认为最关键的一步。",
            intentDecision = TutorIntentDecision.currentQuestionDefault(),
            explanationMode = TutorExplanationMode.GUIDED,
            modeVersion = 2,
            responseIntent = TutorResponseIntent.ASK,
            interactionDirective = TutorInteractionDirective.FreeResponse(
                "满足条件的是第二项，你能选出来吗？",
            ),
            modelVersion = "model-v1",
        )
        val freeResponse = requireNotNull(modelFreeResponse.locallyConstrainedFor(guided))
        assertEquals(GUIDED_INTERACTION_MESSAGE, freeResponse.messageMarkdown)
        assertEquals(
            TutorInteractionDirective.FreeResponse(GUIDED_FREE_RESPONSE_PROMPT),
            freeResponse.interactionDirective,
        )
        assertTrue(!freeResponse.messageMarkdown.contains("最终答案"))
        assertEquals(freeResponse, freeResponse.locallyConstrainedFor(guided))
        assertTrue(ModelTaskCompletionValidator.validate(request(input = guided), freeResponse).isEmpty())

        val choices = freeResponse.copy(
            interactionDirective = TutorInteractionDirective.Choices(
                promptMarkdown = "哪个关系最值得先判断？",
                choices = listOf(
                    TutorInteractionChoice("a", "方向关系"),
                    TutorInteractionChoice("b", "满足条件的第二项"),
                ),
            ),
        )
        assertEquals(null, choices.locallyConstrainedFor(guided))
        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
            ModelTaskCompletionValidator.validate(request(input = guided), choices).map { it.code },
        )
        val choiceAuthorized = guided.copy(choiceInteractionAuthorized = true)
        assertEquals(null, choices.locallyConstrainedFor(choiceAuthorized))
        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
            ModelTaskCompletionValidator.validate(
                request(input = choiceAuthorized),
                choices,
            ).map { it.code },
        )

        val visualTarget = freeResponse.copy(
            interactionDirective = TutorInteractionDirective.VisualTarget(
                promptMarkdown = "满足条件的是高亮点，你能指出来吗？",
                targetId = "verified-node-a",
            ),
        )
        assertEquals(null, visualTarget.locallyConstrainedFor(guided))
        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
            ModelTaskCompletionValidator.validate(request(input = guided), visualTarget).map { it.code },
        )
        val visualAuthorized = guided.copy(
            allowedVisualTargetIds = setOf("verified-node-a"),
        )
        assertEquals(null, visualTarget.locallyConstrainedFor(visualAuthorized))
        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
            ModelTaskCompletionValidator.validate(
                request(input = visualAuthorized),
                visualTarget,
            ).map { it.code },
        )
    }

    @Test
    fun externalLobbyManifestSeparatesCurrentInteractionPolicyFromConversationData() {
        val provider = provider()
        val input = input()
        val requestId = "tutor-lobby-request"
        val request = request(
            ModelEgressManifest(
                authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
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
            input,
        )

        val execution = ModelEgressPolicy.authorize(request, provider, nowEpochMillis = 1_000)

        assertTrue(execution.permit is ModelExecutionPermit.External)
        assertEquals(
            setOf(
                ModelEgressDataClass.STUDENT_TUTOR_MESSAGE,
                ModelEgressDataClass.TUTOR_CONVERSATION_CONTEXT,
                ModelEgressDataClass.CURRENT_TUTOR_INTERACTION_POLICY,
            ),
            request.egressManifest?.disclosedData,
        )

        val currentManifest = requireNotNull(request.egressManifest)
        assertTrue(
            runCatching {
                currentManifest.copy(
                    disclosedData = currentManifest.disclosedData -
                        ModelEgressDataClass.CURRENT_TUTOR_INTERACTION_POLICY,
                    prohibitedData = currentManifest.prohibitedData +
                        ModelEgressDataClass.CURRENT_TUTOR_INTERACTION_POLICY,
                )
            }.isFailure,
        )
    }

    @Test
    fun legacyLobbyManifestOnlyAuthorizesLegacyDirectDefaults() {
        val provider = provider()
        val legacyInput = input()
        val legacySchema = ModelEgressManifest.TUTOR_INTERACTION_POLICY_SCHEMA_VERSION - 1
        val legacyRequest = request(
            manifest = lobbyManifest(
                schemaVersion = legacySchema,
                input = legacyInput,
                provider = provider,
            ),
            input = legacyInput,
        )

        assertTrue(
            ModelEgressPolicy.authorize(legacyRequest, provider, nowEpochMillis = 1_000)
                .permit is ModelExecutionPermit.External,
        )

        val guided = legacyInput.copy(
            explanationMode = TutorExplanationMode.GUIDED,
            modeVersion = 1,
        )
        val guidedRequest = request(
            manifest = lobbyManifest(schemaVersion = legacySchema, input = guided, provider = provider),
            input = guided,
        )
        assertTrue(
            runCatching {
                ModelEgressPolicy.authorize(guidedRequest, provider, nowEpochMillis = 1_000)
            }.exceptionOrNull() is ModelEgressAuthorizationException,
        )
    }

    private fun request(
        manifest: ModelEgressManifest? = null,
        input: TutorLobbyInput = input(),
    ) = ModelTaskRequest(
        requestId = "tutor-lobby-request",
        input = input,
        occurredAtEpochMillis = 1_000,
        egressManifest = manifest,
    )

    private fun input() = TutorLobbyInput(
        conversationId = "tutor-lobby",
        messageOrdinal = 2,
        studentMessage = "我应该从哪里开始？",
        priorMessages = listOf(
            TutorChatHistoryEntry(
                studentMessage = "你好",
                assistantMarkdown = "你好，你现在想讲哪道题？",
            ),
        ),
    )

    private fun lobbyManifest(
        schemaVersion: Int,
        input: TutorLobbyInput,
        provider: ProviderCapabilitySnapshot,
    ) = ModelEgressManifest(
        schemaVersion = schemaVersion,
        authorizationId = ModelEgressAuthorizationId.forInput("tutor-lobby-request", input),
        subjectId = input.conversationId,
        purpose = ModelEgressPurpose.TUTORING,
        authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_LOBBY),
        providerId = provider.providerId,
        modelId = provider.modelId,
        providerConfigurationVersion = provider.providerConfigurationVersion,
        promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_LOBBY,
        approvedAtEpochMillis = 1_000,
        assets = emptyList(),
        disclosedData = ModelEgressManifest.tutorLobbyDisclosureForSchema(schemaVersion),
        prohibitedData = ModelEgressManifest.tutorLobbyProhibitedDataForSchema(schemaVersion),
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
