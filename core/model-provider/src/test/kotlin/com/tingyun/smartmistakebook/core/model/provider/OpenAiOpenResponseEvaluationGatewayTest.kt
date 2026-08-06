package com.tingyun.smartmistakebook.core.model.provider

import com.tingyun.smartmistakebook.core.domain.ModelApiKey
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerification
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationReadCapability
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAsset
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAssetSource
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationId
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPolicy
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationOutcome
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationTestProducerFactory
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluatorKind
import com.tingyun.smartmistakebook.core.model.OpenResponseKnowledgeRole
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorOpenResponseEvaluationCandidateOutput
import com.tingyun.smartmistakebook.core.model.TutorOpenResponseEvaluationInput
import com.tingyun.smartmistakebook.core.model.TutorTeachingConstraint
import com.tingyun.smartmistakebook.core.model.VerifiedOpenResponseEvaluationTeachingReference
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import java.util.function.BooleanSupplier
import java.util.function.LongSupplier
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiOpenResponseEvaluationGatewayTest {
    @Test
    fun advertisesOpenResponseEvaluationOnlyAfterStructuredCapabilityVerification() = runBlocking {
        val verified =
            gateway(
                transport = respondingTransport(decisionEnvelope(fixture())),
            )
        val unverified =
            gateway(
                transport = respondingTransport(decisionEnvelope(fixture())),
                configuration =
                    CONFIGURATION.copy(
                        capabilityVerification = null,
                    ),
            )

        assertTrue(verified.capabilities().supports(ModelTaskKind.TUTOR_EVALUATE))
        assertFalse(unverified.capabilities().supports(ModelTaskKind.TUTOR_EVALUATE))
    }

    @Test
    fun sendsOnlyIdentityFreeQuestionCurrentAnswerAndTeachingConstraints() = runBlocking {
        val fixture = fixture()
        var sentBody = ""
        val gateway =
            gateway(
                transport =
                    ModelHttpTransport { _, _, requestBody, beforeEnqueue ->
                        beforeEnqueue()
                        sentBody = requestBody
                        ModelHttpResponse(200, decisionEnvelope(fixture))
                    },
            )

        val events = gateway.execute(authorized(gateway, fixture.input)).toList()
        val completed = events.last() as ModelGatewayEvent.Completed
        val candidate = completed.output as TutorOpenResponseEvaluationCandidateOutput

        assertEquals(OpenResponseEvaluationOutcome.INCORRECT, candidate.outcome)
        assertTrue(sentBody.contains(QUESTION_MARKDOWN))
        assertTrue(sentBody.contains(ANSWER))
        assertTrue(sentBody.contains("根据导数符号判断单调性"))
        assertTrue(sentBody.contains(TutorTeachingConstraint.EXPLAIN_DIRECTLY.name))
        assertFalse(sentBody.contains(QUESTION_DOCUMENT_ID))
        assertFalse(sentBody.contains(QUESTION_BLOCK_ID))
        assertFalse(sentBody.contains("learner-secret"))
        assertFalse(sentBody.contains("SELECT * FROM"))
        assertFalse(sentBody.contains("\"masteryScore\""))
        assertFalse(sentBody.contains("\"confidence\""))
        assertFalse(sentBody.contains("\"weight\""))
        assertFalse(sentBody.contains("\"occurredAtEpochMillis\""))
    }

    @Test
    fun providerCannotWidenKnowledgeScopeOrReturnAnOldDecisionSchema() = runBlocking {
        val fixture = fixture()
        val valid = decisionEnvelope(fixture)
        val invalidBodies =
            listOf(
                valid.replace(
                    fixture.knowledgeRefFingerprint,
                    fingerprint("foreign-ref"),
                ),
                valid.replaceFirst(
                    "\\\"schemaVersion\\\":1",
                    "\\\"schemaVersion\\\":0",
                ),
            )

        invalidBodies.forEach { invalidBody ->
            val gateway = gateway(respondingTransport(invalidBody))
            val failed =
                gateway.execute(authorized(gateway, fixture.input)).toList().last()
                    as ModelGatewayEvent.Failed
            assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
            assertFalse(failed.failure.retryable)
        }
    }

    @Test
    fun cancellationDropsANonCooperativeLateProviderResponse() = runBlocking {
        val fixture = fixture()
        val transport =
            NonCooperativeLateTransport(
                response = ModelHttpResponse(200, decisionEnvelope(fixture)),
            )
        val gateway = gateway(transport)
        val events = mutableListOf<ModelGatewayEvent>()
        val collection =
            async {
                gateway.execute(authorized(gateway, fixture.input)).toCollection(events)
            }

        withTimeout(2_000) { transport.enqueued.await() }
        collection.cancel()
        transport.release.complete(Unit)
        runCatching { collection.await() }

        assertTrue(transport.returnedAfterCancellation.get())
        assertFalse(events.any { it is ModelGatewayEvent.Completed })
    }

    private suspend fun authorized(
        gateway: OpenAiCompatibleModelGateway,
        input: TutorOpenResponseEvaluationInput,
    ) = gateway.capabilities().let { provider ->
        val requestId = "open-response-provider-request"
        val manifest =
            ModelEgressManifest(
                authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
                subjectId = input.subjectId,
                purpose = ModelEgressPurpose.TUTORING,
                authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_EVALUATE),
                providerId = provider.providerId,
                modelId = provider.modelId,
                providerConfigurationVersion = provider.providerConfigurationVersion,
                promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_EVALUATE,
                approvedAtEpochMillis = AUTHORIZATION_APPROVED_AT,
                assets = emptyList(),
                disclosedData = ModelEgressManifest.TUTOR_EVALUATE_DISCLOSURE,
                prohibitedData = ModelEgressManifest.TUTOR_EVALUATE_PROHIBITED_DATA,
            )
        ModelEgressPolicy.authorize(
            request =
                ModelTaskRequest(
                    requestId = requestId,
                    input = input,
                    occurredAtEpochMillis = REQUEST_OCCURRED_AT,
                    egressManifest = manifest,
                ),
            provider = provider,
            nowEpochMillis = AUTHORIZATION_NOW,
        )
    }

    private fun gateway(
        transport: ModelHttpTransport,
        configuration: ModelConfigurationSnapshot = CONFIGURATION,
    ): OpenAiCompatibleModelGateway =
        OpenAiCompatibleModelGateway(
            configurationStore = StaticConfigurationReadCapability(configuration),
            assetSource =
                object : RestrictedModelAssetSource {
                    override suspend fun open(
                        execution: ModelGatewayExecution,
                        assetId: String,
                    ): RestrictedModelAsset =
                        error("Open-response evaluation must not open image assets")
                },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

    private fun fixture(): Fixture {
        val knowledgeAuthority = KnowledgeReferenceProofAuthority.create()
        val knowledgeProof =
            knowledgeAuthority.issuer.issue(
                KnowledgeNodeRef(
                    subject = SubjectKind.MATH,
                    knowledgeNodeId = "math-calculus-monotonicity",
                    taxonomyVersion = "taxonomy-v1",
                    knowledgePackVersion = "knowledge-pack-v1",
                ),
                fingerprint("knowledge-manifest"),
                1L,
            )
        val producer =
            OpenResponseEvaluationTestProducerFactory.open(
                "learner-secret",
                "conversation-secret",
                QUESTION_DOCUMENT_ID,
                1,
                SubjectKind.MATH,
                QUESTION,
                fingerprint("rubric"),
                fingerprint("operation-binding"),
                ANSWER,
                listOf(
                    VerifiedOpenResponseEvaluationTeachingReference(
                        proof = knowledgeProof,
                        label = "根据导数符号判断单调性",
                        constraint = TutorTeachingConstraint.EXPLAIN_DIRECTLY,
                    ),
                ),
                OpenResponseEvaluatorKind.RUBRIC,
                fingerprint("policy"),
                knowledgeAuthority.verifier,
                AUTHORIZATION_NOW + 60_000L,
                LongSupplier { AUTHORIZATION_NOW },
                BooleanSupplier { true },
            )
        val input = producer.createTask(requestVersion = 11)
        val scope =
            Json.parseToJsonElement(input.authorizedScopeForProvider()).jsonObject
        val knowledgeRefFingerprint =
            scope.getValue("knowledgeScope")
                .jsonArray
                .single()
                .jsonObject
                .getValue("refFingerprint")
                .jsonPrimitive
                .content
        return Fixture(
            input = input,
            knowledgeRefFingerprint = knowledgeRefFingerprint,
        )
    }

    private fun decisionEnvelope(
        fixture: Fixture,
    ): String {
        val scope =
            Json.parseToJsonElement(
                fixture.input.authorizedScopeForProvider(),
            ).jsonObject
        val decision =
            buildJsonObject {
                put("schemaVersion", scope.getValue("schemaVersion"))
                put("binding", scope.getValue("binding"))
                put("subject", scope.getValue("subject"))
                put("outcome", OpenResponseEvaluationOutcome.INCORRECT.name)
                put(
                    "knowledgeEffects",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put(
                                    "refFingerprint",
                                    fixture.knowledgeRefFingerprint,
                                )
                                put("role", OpenResponseKnowledgeRole.LOCATED_GAP.name)
                            },
                        )
                    },
                )
                put("evaluator", scope.getValue("evaluator"))
                put("evidenceFingerprint", scope.getValue("evidenceFingerprint"))
            }
        val response =
            buildJsonObject {
                put(
                    "choices",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put(
                                    "message",
                                    buildJsonObject {
                                        put(
                                            "content",
                                            Json.encodeToString(JsonObject.serializer(), decision),
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            }
        return Json.encodeToString(JsonObject.serializer(), response)
    }

    private fun respondingTransport(body: String): ModelHttpTransport =
        ModelHttpTransport { _, _, _, beforeEnqueue ->
            beforeEnqueue()
            ModelHttpResponse(200, body)
        }

    private data class Fixture(
        val input: TutorOpenResponseEvaluationInput,
        val knowledgeRefFingerprint: String,
    )

    private class StaticConfigurationReadCapability(
        snapshot: ModelConfigurationSnapshot,
    ) : ModelConfigurationReadCapability {
        override val configuration: Flow<ModelConfigurationSnapshot> = flowOf(snapshot)

        override suspend fun readCredential(): ModelCredentialReadResult =
            ModelCredentialReadResult.Available(
                configuration = configurationSnapshot(),
                apiKey = ModelApiKey.from("secret".toCharArray()),
            )

        private val exactSnapshot = snapshot

        private fun configurationSnapshot(): ModelConfigurationSnapshot = exactSnapshot
    }

    private class NonCooperativeLateTransport(
        private val response: ModelHttpResponse,
    ) : ModelHttpTransport {
        val enqueued = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returnedAfterCancellation = AtomicBoolean(false)

        override suspend fun post(
            baseUrl: String,
            apiKey: CharArray,
            requestBody: String,
            beforeEnqueue: suspend () -> Unit,
        ): ModelHttpResponse {
            beforeEnqueue()
            enqueued.complete(Unit)
            try {
                release.await()
            } catch (_: CancellationException) {
                returnedAfterCancellation.set(true)
            }
            return response
        }
    }

    private companion object {
        const val QUESTION_DOCUMENT_ID = "database-question-row-secret"
        const val QUESTION_BLOCK_ID = "database-block-row-secret"
        const val QUESTION_MARKDOWN = "已知函数在区间内导数为正，判断函数的单调性。"
        const val ANSWER = "函数在该区间单调递减。"
        const val REQUEST_OCCURRED_AT = 1_000_000L
        const val AUTHORIZATION_APPROVED_AT = 1_000_001L
        const val AUTHORIZATION_NOW = 1_000_002L
        val QUESTION =
            QuestionDocument(
                id = QUESTION_DOCUMENT_ID,
                blocks =
                    listOf(
                        ContentBlock.Paragraph(
                            id = QUESTION_BLOCK_ID,
                            markdown = QUESTION_MARKDOWN,
                        ),
                    ),
            )
        val CONFIGURATION =
            ModelConfigurationSnapshot(
                provider = "兼容模型服务",
                baseUrl = "https://api.example.com/v1",
                modelId = "gpt-evaluator",
                isConfigured = true,
                updatedAtEpochMillis = 123,
                configurationVersion = "test-configuration-v1",
                capabilityVerification =
                    ModelCapabilityVerification(
                        provider = "兼容模型服务",
                        baseUrl = "https://api.example.com/v1",
                        modelId = "gpt-evaluator",
                        configurationVersion = "test-configuration-v1",
                        configurationUpdatedAtEpochMillis = 123,
                        supportsImageInput = false,
                        supportsStructuredOutput = true,
                        testedAtEpochMillis = 124,
                    ),
            )

        fun fingerprint(seed: String): String =
            CanonicalSha256("open-response-provider-test")
                .field("seed", seed)
                .finish()
    }
}
