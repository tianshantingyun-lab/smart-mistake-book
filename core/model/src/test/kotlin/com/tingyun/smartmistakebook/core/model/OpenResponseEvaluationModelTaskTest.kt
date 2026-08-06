package com.tingyun.smartmistakebook.core.model

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenResponseEvaluationModelTaskTest {
    @Test
    fun modelTaskCodecReadsSnapshotWithoutPromotingJsonIntoHostAuthority() {
        val issued = issuedRequest()
        val original = input(issued, requestVersion = 7)
        val decodedRequest =
            ModelTaskCodec.decodeRequest(
                ModelTaskCodec.encodeRequest(request(original)),
            )
        val decoded = decodedRequest.input as TutorOpenResponseEvaluationInput

        assertEquals(original.requestVersion, decoded.requestVersion)
        assertEquals(original.idempotencyKey, decoded.idempotencyKey)
        assertEquals(original.questionDocument, decoded.questionDocument)
        assertEquals(original.currentAnswer, decoded.currentAnswer)
        assertEquals(original.teachingGuidance, decoded.teachingGuidance)
        assertFailsWith<ModelEgressAuthorizationException> {
            ModelEgressPolicy.authorize(decodedRequest, localProvider())
        }

        val rebound = decoded.withHostAuthorization(issued)
        ModelEgressPolicy.authorize(
            decodedRequest.copy(input = rebound),
            localProvider(),
        )
    }

    @Test
    fun strictProviderDecisionBecomesCandidateOnlyAndRoundTripsAsOutput() {
        val issued = issuedRequest()
        val input = input(issued)
        val candidate =
            OpenResponseEvaluationModelTaskProtocol.decodeProviderDecision(
                input = input,
                providerJson = validDecisionJson(issued),
                modelVersion = "gpt-evaluator-v1",
            )

        assertEquals(OpenResponseEvaluationOutcome.INCORRECT, candidate.outcome)
        assertEquals(input.requestVersion, candidate.requestVersion)
        assertEquals(input.idempotencyKey, candidate.idempotencyKey)
        assertEquals(
            listOf(
                OpenResponseKnowledgeEffect(
                    refFingerprint = KNOWLEDGE_REF,
                    role = OpenResponseKnowledgeRole.LOCATED_GAP,
                ),
            ),
            candidate.knowledgeEffects,
        )
        assertTrue(
            ModelTaskCompletionValidator.validate(request(input), candidate).isEmpty(),
        )

        val decoded =
            ModelTaskCodec.decodeOutput(ModelTaskCodec.encodeOutput(candidate))
                as TutorOpenResponseEvaluationCandidateOutput
        assertEquals(candidate, decoded)
        assertTrue(
            ModelTaskCompletionValidator.validate(request(input), decoded).isEmpty(),
        )
    }

    @Test
    fun staleCandidateCannotCrossRequestVersionOrIdempotentOperation() {
        val issued = issuedRequest()
        val firstInput = input(issued, requestVersion = 3)
        val laterInput = input(issued, requestVersion = 4)
        val firstCandidate =
            OpenResponseEvaluationModelTaskProtocol.decodeProviderDecision(
                input = firstInput,
                providerJson = validDecisionJson(issued),
                modelVersion = "gpt-evaluator-v1",
            )

        assertNotEquals(firstInput.idempotencyKey, laterInput.idempotencyKey)
        assertEquals(
            listOf(
                ModelTaskCompletionIssueCode.OPEN_RESPONSE_AUTHORITY_OR_SCOPE_MISMATCH,
            ),
            ModelTaskCompletionValidator
                .validate(request(laterInput), firstCandidate)
                .map(ModelTaskCompletionIssue::code),
        )
    }

    @Test
    fun foreignKnowledgeReferenceAndOldDecisionSchemaFailClosed() {
        val issued = issuedRequest()
        val input = input(issued)
        val valid = validDecisionJson(issued)
        val foreignRef =
            valid.replace(KNOWLEDGE_REF, fingerprint("foreign-knowledge"))
        val oldSchema = valid.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":0")

        listOf(foreignRef, oldSchema).forEach { payload ->
            assertFailsWith<IllegalArgumentException> {
                OpenResponseEvaluationModelTaskProtocol.decodeProviderDecision(
                    input = input,
                    providerJson = payload,
                    modelVersion = "gpt-evaluator-v1",
                )
            }
        }
    }

    @Test
    fun externalEgressIsExactAndExcludesStorageMasteryAndIdentityData() {
        val issued = issuedRequest()
        val input = input(issued)
        val provider = externalProvider()
        val requestId = "open-response-request"
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
                approvedAtEpochMillis = 2,
                assets = emptyList(),
                disclosedData = ModelEgressManifest.TUTOR_EVALUATE_DISCLOSURE,
                prohibitedData = ModelEgressManifest.TUTOR_EVALUATE_PROHIBITED_DATA,
            )
        val request =
            ModelTaskRequest(
                requestId = requestId,
                input = input,
                occurredAtEpochMillis = 1,
                egressManifest = manifest,
            )

        ModelEgressPolicy.authorize(request, provider, nowEpochMillis = 3)
        assertTrue(ModelEgressDataClass.FULL_LEARNING_HISTORY in manifest.prohibitedData)
        assertTrue(ModelEgressDataClass.API_CREDENTIALS in manifest.prohibitedData)
        assertTrue(ModelEgressDataClass.RELEVANT_LEARNING_EVIDENCE in manifest.prohibitedData)
        assertFalse(ModelEgressDataClass.SUBJECT_KNOWLEDGE_BASE in manifest.disclosedData)
        assertEquals(
            setOf(
                ModelEgressDataClass.CONFIRMED_QUESTION_DOCUMENT,
                ModelEgressDataClass.CURRENT_QUESTION_TEACHING_CONSTRAINTS,
                ModelEgressDataClass.CURRENT_OPEN_RESPONSE_ANSWER,
                ModelEgressDataClass.QUESTION_LOCAL_EVALUATION_SCOPE,
            ),
            manifest.disclosedData,
        )
    }

    @Test
    fun candidateSurfaceContainsNoDatabaseOrMasteryMutationAuthority() {
        val visibleMemberNames =
            TutorOpenResponseEvaluationCandidateOutput::class.java.methods
                .filterNot { method ->
                    Modifier.isStatic(method.modifiers) || method.isSynthetic
                }
                .map { it.name.lowercase() }
                .toSet()

        listOf(
            "sql",
            "dao",
            "database",
            "mastery",
            "weight",
            "confidence",
            "timestamp",
            "learner",
            "write",
            "insert",
            "update",
        ).forEach { forbidden ->
            assertTrue(
                "Candidate unexpectedly exposes $forbidden",
                visibleMemberNames.none { forbidden in it },
            )
        }
    }

    private fun input(
        issued: HostIssuedOpenResponseEvaluationRequest,
        requestVersion: Long = 1,
    ): TutorOpenResponseEvaluationInput =
        OpenResponseEvaluationTaskBinder.bind(
            issuedRequest = issued,
            operationBinding = fingerprint("operation-binding"),
            questionDocument = QUESTION,
            currentAnswer = ANSWER,
            teachingGuidance =
                listOf(
                    OpenResponseEvaluationTeachingGuidance(
                        refFingerprint = KNOWLEDGE_REF,
                        label = "根据导数符号判断单调性",
                        constraint = TutorTeachingConstraint.EXPLAIN_DIRECTLY,
                    ),
                ),
            requestVersion = requestVersion,
        )

    private fun request(input: TutorOpenResponseEvaluationInput): ModelTaskRequest =
        ModelTaskRequest(
            requestId = "open-response-request-${input.requestVersion}",
            input = input,
            occurredAtEpochMillis = 1,
        )

    private fun issuedRequest(): HostIssuedOpenResponseEvaluationRequest =
        issueTestOpenResponseEvaluationRequest(
            binding =
                OpenResponseEvaluationBinding(
                    caseFingerprint = fingerprint("case"),
                    sessionFingerprint = fingerprint("session"),
                    questionFingerprint =
                        OpenResponseEvaluationTaskFingerprints.question(QUESTION),
                    answerFingerprint =
                        OpenResponseEvaluationTaskFingerprints.answer(ANSWER),
                    rubricFingerprint = fingerprint("rubric"),
                ),
            subject = SubjectKind.MATH,
            knowledgeScope =
                listOf(
                    OpenResponseKnowledgeScopeRef(
                        refFingerprint = KNOWLEDGE_REF,
                        subject = SubjectKind.MATH,
                        questionFingerprint =
                            OpenResponseEvaluationTaskFingerprints.question(QUESTION),
                    ),
                ),
            evaluator = OpenResponseEvaluatorKind.RUBRIC,
            policyFingerprint = fingerprint("policy"),
            evidenceFingerprint = fingerprint("evidence"),
        )

    private fun validDecisionJson(
        issued: HostIssuedOpenResponseEvaluationRequest,
    ): String {
        val request = issued.requireIssuedRequest()
        val decision =
            OpenResponseEvaluationDecision.verified(
                request = request,
                binding = request.binding,
                subject = request.subject,
                outcome = OpenResponseEvaluationOutcome.INCORRECT,
                knowledgeEffects =
                    listOf(
                        OpenResponseKnowledgeEffect(
                            refFingerprint = KNOWLEDGE_REF,
                            role = OpenResponseKnowledgeRole.LOCATED_GAP,
                        ),
                    ),
                evaluator = request.evaluator,
                evidenceFingerprint = request.evidenceFingerprint,
            )
        return OpenResponseEvaluationJsonCodec.encodeDecision(decision, issued)
    }

    private fun localProvider(): ProviderCapabilitySnapshot =
        ProviderCapabilitySnapshot(
            providerId = "local-evaluator",
            providerDisplayName = "本地测试评价器",
            modelId = "local-v1",
            supportedTasks = setOf(ModelTaskKind.TUTOR_EVALUATE),
            supportsImageInput = false,
            supportsStructuredOutput = true,
            supportsStreaming = false,
            executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
        )

    private fun externalProvider(): ProviderCapabilitySnapshot =
        localProvider().copy(
            providerId = "external-evaluator",
            providerDisplayName = "外部测试评价器",
            modelId = "external-v1",
            executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
            providerConfigurationVersion = "external-config-v1",
        )

    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue(
            "Expected ${T::class.java.simpleName}, got ${thrown?.javaClass?.simpleName}",
            thrown is T,
        )
        @Suppress("UNCHECKED_CAST")
        return thrown as T
    }

    private companion object {
        val QUESTION =
            QuestionDocument(
                id = "database-question-row-secret",
                blocks =
                    listOf(
                        ContentBlock.Paragraph(
                            id = "database-block-row-secret",
                            markdown = "已知函数在区间内导数为正，判断函数的单调性。",
                        ),
                    ),
            )
        const val ANSWER = "函数在该区间单调递减。"
        val KNOWLEDGE_REF: String = fingerprint("knowledge")

        fun fingerprint(seed: String): String =
            CanonicalSha256("open-response-model-task-test")
                .field("seed", seed)
                .finish()
    }
}
