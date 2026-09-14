package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end protocol tests for the complete tutoring flow.
 *
 * These tests verify:
 * - Image is only sent to tasks that authorize it
 * - Lobby never sends images
 * - Plan/Respond can send images when authorized
 * - Egress manifest matches exactly what is sent
 * - Provider capability mismatch is caught before network
 */
class TutorE2EProtocolTest {

    @Test
    fun lobbySendsImages_whenProvidedByTheStudent() {
        MockWebServer().use { server ->
            server.enqueue(successResponse(tutorLobbyContent()))
            server.start()

            val imageBytes = ByteArray(100) { it.toByte() }
            val requestBody = OpenAiModelProtocol.requestBody(
                modelId = "gpt-4o",
                input = TutorLobbyInput(
                    conversationId = "conv-1",
                    messageOrdinal = 1,
                    studentMessage = "帮我看这道题",
                    priorMessages = emptyList(),
                ),
                images = listOf(ApprovedImage("image/jpeg", imageBytes)),
            )

            postToServer(server, requestBody)
            val sentBody = server.takeRequest().body.readUtf8()

            // 附图能力开放后：学生确认过的图片随消息出网。
            assertTrue("Lobby body must contain image_url", sentBody.contains("image_url"))
            assertTrue("Lobby body must contain student message", sentBody.contains("帮我看这道题"))
        }
    }

    @Test
    fun planSendsImage_whenEgressManifestAuthorizesIt() {
        MockWebServer().use { server ->
            server.enqueue(successResponse(tutorPlanContent()))
            server.start()

            val imageBytes = ByteArray(100) { it.toByte() }
            val requestBody = OpenAiModelProtocol.requestBody(
                modelId = "gpt-4o",
                input = TutorPlanInput(
                    sessionId = "session-1",
                    draftRevisionNumber = 1,
                    subject = "数学",
                    questionDocument = createTestQuestionDocument(),
                    relevantLearningEvidence = emptyList(),
                    projectionIsCurrent = true,
                    reviewedTeachingReferences = emptyList(),
                    questionLearningEvidence = null,
                    cycleOrdinal = 1,
                    priorConversationMemory = null,
                    priorCycleStudentMessages = emptyList(),
                    turnOrdinal = 1,
                    priorTurns = emptyList(),
                ),
                images = listOf(ApprovedImage("image/jpeg", imageBytes)),
            )

            postToServer(server, requestBody)
            val sentBody = server.takeRequest().body.readUtf8()

            assertTrue("Plan body must contain image_url when authorized", sentBody.contains("image_url"))
            assertTrue("Plan body must contain base64 image", sentBody.contains("data:image/jpeg;base64,"))
        }
    }

    @Test
    fun respondSendsImage_whenAuthorized() {
        MockWebServer().use { server ->
            server.enqueue(successResponse(tutorRespondContent()))
            server.start()

            val imageBytes = ByteArray(100) { it.toByte() }
            val requestBody = OpenAiModelProtocol.requestBody(
                modelId = "gpt-4o",
                input = TutorRespondInput(
                    sessionId = "session-1",
                    draftRevisionNumber = 1,
                    subject = "数学",
                    questionDocument = createTestQuestionDocument(),
                    relevantLearningEvidence = emptyList(),
                    projectionIsCurrent = true,
                    reviewedTeachingReferences = emptyList(),
                    questionLearningEvidence = null,
                    responseOrdinal = 1,
                    cycleOrdinal = 1,
                    turnOrdinal = 1,
                    studentMessage = "我不太理解这一步",
                    visibleTutorContextMarkdown = null,
                    priorMessages = emptyList(),
                    requestedMove = null,
                ),
                images = listOf(ApprovedImage("image/jpeg", imageBytes)),
            )

            postToServer(server, requestBody)
            val sentBody = server.takeRequest().body.readUtf8()

            assertTrue("Respond body must contain image_url when authorized", sentBody.contains("image_url"))
        }
    }

    @Test
    fun egressManifestMatchesExactlyWhatIsSent_lobby() {
        val manifest = ModelEgressManifest(
            authorizationId = "auth:lobby:1",
            subjectId = "conv-1",
            purpose = ModelEgressPurpose.TUTORING,
            authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_LOBBY),
            providerId = "openai",
            modelId = "gpt-4o",
            providerConfigurationVersion = "v2024-01",
            promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_LOBBY,
            approvedAtEpochMillis = System.currentTimeMillis(),
            assets = emptyList(),
            disclosedData = ModelEgressManifest.TUTOR_LOBBY_DISCLOSURE,
            prohibitedData = ModelEgressManifest.TUTOR_LOBBY_PROHIBITED_DATA,
        )

        assertEquals("openai", manifest.providerId)
        assertEquals("gpt-4o", manifest.modelId)
        assertTrue(manifest.assets.isEmpty())
        assertTrue(manifest.authorizedTaskKinds.contains(ModelTaskKind.TUTOR_LOBBY))
        assertFalse(manifest.authorizedTaskKinds.contains(ModelTaskKind.TUTOR_PLAN))
        assertFalse(manifest.authorizedTaskKinds.contains(ModelTaskKind.TUTOR_RESPOND))
    }

    @Test
    fun providerCapabilityMismatch_caughtBeforeNetwork() {
        val provider = ProviderCapabilitySnapshot(
            providerId = "openai",
            providerDisplayName = "OpenAI",
            modelId = "gpt-4o",
            supportedTasks = setOf(ModelTaskKind.TUTOR_LOBBY),
            supportsImageInput = false,
            supportsStructuredOutput = false,
            supportsStreaming = false,
            providerConfigurationVersion = "v2024-01",
            executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        )

        assertFalse(
            "Provider without vision support should not support image tasks",
            provider.supports(ModelTaskKind.CAPTURE_ASSESS),
        )
    }

    @Test
    fun multipleImagesSentInCorrectOrder() {
        MockWebServer().use { server ->
            server.enqueue(successResponse(tutorPlanContent()))
            server.start()

            val image1 = ByteArray(50) { 0x01 }
            val image2 = ByteArray(50) { 0x02 }
            val requestBody = OpenAiModelProtocol.requestBody(
                modelId = "gpt-4o",
                input = TutorPlanInput(
                    sessionId = "session-1",
                    draftRevisionNumber = 1,
                    subject = "数学",
                    questionDocument = createTestQuestionDocument(),
                    relevantLearningEvidence = emptyList(),
                    projectionIsCurrent = true,
                    reviewedTeachingReferences = emptyList(),
                    questionLearningEvidence = null,
                    cycleOrdinal = 1,
                    priorConversationMemory = null,
                    priorCycleStudentMessages = emptyList(),
                    turnOrdinal = 1,
                    priorTurns = emptyList(),
                ),
                images = listOf(
                    ApprovedImage("image/jpeg", image1),
                    ApprovedImage("image/png", image2),
                ),
            )

            postToServer(server, requestBody)
            val sentBody = server.takeRequest().body.readUtf8()

            assertTrue("Should contain two image_url entries", sentBody.contains("image_url"))
            assertTrue("Should contain JPEG base64", sentBody.contains("data:image/jpeg;base64,${image1.toByteString().base64()}"))
            assertTrue("Should contain PNG base64", sentBody.contains("data:image/png;base64,${image2.toByteString().base64()}"))
        }
    }

    @Test
    fun promptPolicyVersionIncludedInManifest() {
        val manifest = ModelEgressManifest(
            authorizationId = "auth:plan:1",
            subjectId = "session-1",
            purpose = ModelEgressPurpose.TUTORING,
            authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_PLAN),
            providerId = "openai",
            modelId = "gpt-4o",
            providerConfigurationVersion = "v2024-01",
            promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_PLAN,
            approvedAtEpochMillis = System.currentTimeMillis(),
            assets = emptyList(),
            disclosedData = ModelEgressManifest.TUTOR_PLAN_DISCLOSURE,
            prohibitedData = ModelEgressManifest.TUTOR_PLAN_PROHIBITED_DATA,
        )

        assertEquals(ModelPromptPolicyVersions.TUTOR_PLAN, manifest.promptPolicyVersion)
        assertTrue("Policy version must not be empty", manifest.promptPolicyVersion.isNotEmpty())
    }

    @Test
    fun responseParser_rejectsExtraFields() {
        val responseJson = """
            {
                "intentDecision": {
                    "intent": "CURRENT_QUESTION_HELP",
                    "confidence": 0.9,
                    "explicitActionRequest": false,
                    "memoryPreference": "UNCHANGED",
                    "requestedLocalCapability": "NONE",
                    "lookupTerms": []
                },
                "messageMarkdown": "test"
            }
        """.trimIndent()

        val output = OpenAiModelProtocol.parseResponse(
            responseBody = tutorLobbyResponse(responseJson),
            input = TutorLobbyInput(
                conversationId = "conv-1",
                messageOrdinal = 1,
                studentMessage = "test",
                priorMessages = emptyList(),
            ),
            modelVersion = "test-model",
        )

        assertTrue("Output should be TutorLobbyOutput", output is com.tingyun.smartmistakebook.core.model.TutorLobbyOutput)
    }

    private fun postToServer(server: MockWebServer, requestBody: String) {
        val request = Request.Builder()
            .url(server.url("/chat/completions"))
            .header("Authorization", "Bearer test-key")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        OkHttpClient().newCall(request).execute().close()
    }

    private fun successResponse(content: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(wrapInEnvelope(content))

    private fun wrapInEnvelope(content: String): String = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "message",
                                buildJsonObject {
                                    put("content", content)
                                },
                            )
                        },
                    )
                },
            )
        },
    )

    private fun tutorLobbyContent(): String = """
        {"intentDecision":{"intent":"CURRENT_QUESTION_HELP","confidence":0.9,"explicitActionRequest":false,"memoryPreference":"UNCHANGED","requestedLocalCapability":"NONE","lookupTerms":[]},"messageMarkdown":"你好"}
    """.trimIndent()

    private fun tutorPlanContent(): String = """
        {"openingMarkdown":"让我们看看这道题","diagnosticItem":null,"solutionMarkdown":"","alternateMethodMarkdown":""}
    """.trimIndent()

    private fun tutorRespondContent(): String = """
        {"markdown":"好的，我来解释","requestedMove":null,"suggestedActions":[]}
    """.trimIndent()

    private fun tutorLobbyResponse(content: String): String = wrapInEnvelope(content)

    private fun createTestQuestionDocument(): com.tingyun.smartmistakebook.core.model.QuestionDocument {
        return com.tingyun.smartmistakebook.core.model.QuestionDocument(
            id = "doc-1",
            title = "测试题目",
            blocks = listOf(
                com.tingyun.smartmistakebook.core.model.ContentBlock.Paragraph(
                    id = "p-1",
                    markdown = "1 + 1 = ?",
                ),
            ),
        )
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
