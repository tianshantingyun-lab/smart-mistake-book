package com.tingyun.smartmistakebook.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEgressTest {
    @Test
    fun preparedRequestBudgetKeepsSmallImagePayloadsUnchanged() {
        val nonImageJsonBytes = 1_024L

        val estimatedBytes = ModelRequestPayloadBudget.requirePreparedRequestFits(
            nonImageJsonUtf8Bytes = nonImageJsonBytes,
            assetByteSizes = listOf(4L, 5L),
        )

        assertEquals(nonImageJsonBytes + 8L + 8L, estimatedBytes)
    }

    @Test
    fun preparedRequestBudgetRejectsAnOversizedMultiPageTotal() {
        val failure = runCatching {
            ModelRequestPayloadBudget.requirePreparedRequestFits(
                nonImageJsonUtf8Bytes = 1_024L,
                assetByteSizes = listOf(14L * 1_024L * 1_024L, 14L * 1_024L * 1_024L),
            )
        }.exceptionOrNull()

        assertTrue(failure is ModelRequestBudgetExceededException)
        assertEquals("Model request exceeds the upload budget", failure?.message)
    }

    @Test
    fun preparedRequestBudgetFailsClosedWhenBase64ArithmeticWouldOverflow() {
        val failure = runCatching {
            ModelRequestPayloadBudget.requirePreparedRequestFits(
                nonImageJsonUtf8Bytes = 0L,
                assetByteSizes = listOf(Long.MAX_VALUE),
            )
        }.exceptionOrNull()

        assertTrue(failure is ModelRequestBudgetExceededException)
    }

    @Test
    fun exactCaptureApprovalAuthorizesOnlyTheBoundExternalProviderAndAsset() {
        val request = request(manifest())

        val execution = ModelEgressPolicy.authorize(request, externalProvider(), 101)

        assertTrue(execution.permit is ModelExecutionPermit.External)
        assertEquals(request, execution.request)
    }

    @Test
    fun captureApprovalCannotBeReusedForChangedRequestSubjectOrOrigin() {
        val requestId = "capture-assess:request-1"
        val input = captureAssessmentInput()
        val manifest = manifest(requestId, input)
        val changedRequests = listOf(
            request(manifest, requestId = "capture-assess:request-2", input = input),
            request(manifest, requestId = requestId, input = input.copy(draftId = "draft-2")),
            request(
                manifest,
                requestId = requestId,
                input = input.copy(origin = CaptureAssessmentOrigin.TUTOR),
            ),
        )

        changedRequests.forEach { changedRequest ->
            assertAuthorizationInvalid(changedRequest, externalProvider())
        }
    }

    @Test
    fun captureParseApprovalCannotBeReusedForAReorderedImageSet() {
        val requestId = "capture-parse:request-1"
        val sources = listOf(
            CaptureSourceAssetRef(
                assetId = "asset-1",
                sha256 = "a".repeat(64),
                width = 1_080,
                height = 1_440,
                pageIndex = 0,
            ),
            CaptureSourceAssetRef(
                assetId = "asset-2",
                sha256 = "b".repeat(64),
                width = 1_000,
                height = 1_300,
                pageIndex = 1,
            ),
        )
        val input = CaptureParseInput(
            draftId = "draft-1",
            origin = CaptureAssessmentOrigin.LIBRARY,
            basisRevisionNumber = 1,
            sourceAssets = sources,
            assessmentRequestId = "capture-assess:1",
            assessmentRequestIds = listOf("capture-assess:1", "capture-assess:2"),
        )
        val manifest = ModelEgressManifest(
            authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
            subjectId = input.subjectId,
            purpose = ModelEgressPurpose.CAPTURE_TO_DOCUMENT,
            authorizedTaskKinds = setOf(
                ModelTaskKind.CAPTURE_ASSESS,
                ModelTaskKind.CAPTURE_PARSE,
            ),
            providerId = "provider-1",
            modelId = "vision-model-1",
            providerConfigurationVersion = "provider-config-v1",
            promptPolicyVersion = ModelPromptPolicyVersions.CAPTURE_DOCUMENT,
            approvedAtEpochMillis = 101,
            assets = sources.map { source ->
                ModelEgressAssetGrant(
                    assetId = source.assetId,
                    sha256 = source.sha256,
                    byteSize = 2_048,
                    width = source.width,
                    height = source.height,
                )
            },
            disclosedData = ModelEgressManifest.CAPTURE_IMAGE_DISCLOSURE,
            prohibitedData = ModelEgressManifest.CAPTURE_PROHIBITED_DATA,
        )
        val request = ModelTaskRequest(
            requestId = requestId,
            input = input,
            occurredAtEpochMillis = 100,
            egressManifest = manifest,
        )
        val reorderedInput = input.copy(
            sourceAssets = listOf(
                sources[1].copy(pageIndex = 0),
                sources[0].copy(pageIndex = 1),
            ),
        )

        assertTrue(
            ModelEgressPolicy.authorize(request, externalProvider(), 101)
                .permit is ModelExecutionPermit.External,
        )
        assertAuthorizationInvalid(request.copy(input = reorderedInput), externalProvider())
    }

    @Test
    fun externalProviderCannotRunWithoutStudentApproval() {
        val failure = runCatching {
            ModelEgressPolicy.authorize(request(manifest = null), externalProvider(), 101)
        }.exceptionOrNull() as ModelEgressAuthorizationException

        assertEquals(ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED, failure.failureCode)
    }

    @Test
    fun providerConfigurationChangeInvalidatesAnExistingApproval() {
        val failure = runCatching {
            ModelEgressPolicy.authorize(
                request(manifest()),
                externalProvider().copy(providerConfigurationVersion = "provider-config-v2"),
                101,
            )
        }.exceptionOrNull() as ModelEgressAuthorizationException

        assertEquals(ModelFailureCode.EGRESS_AUTHORIZATION_INVALID, failure.failureCode)
    }

    @Test
    fun captureApprovalRejectsAnyUndisclosedExtraDataClass() {
        val extra = ModelEgressDataClass.RELEVANT_LEARNING_EVIDENCE

        val failure = runCatching {
            manifest().copy(
                disclosedData = ModelEgressManifest.CAPTURE_IMAGE_DISCLOSURE + extra,
                prohibitedData = ModelEgressManifest.CAPTURE_PROHIBITED_DATA - extra,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun legacySchemaOneFingerprintStillMatchesItsOriginalJsonShape() {
        val legacyJson =
            """{"schemaVersion":1,"requestId":"capture-assess:legacy","input":{"type":"capture_assessment","draftId":"draft-1","sourceAssetId":"asset-1","origin":"LIBRARY","imageWidth":1080,"imageHeight":1440},"occurredAtEpochMillis":100}"""
        val request = ModelTaskCodec.decodeRequest(legacyJson)
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(legacyJson.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals(1, request.schemaVersion)
        assertEquals(expected, ModelTaskFingerprint.of(request))
    }

    @Test
    fun schemaOneTutorPlanFingerprintOmitsNewStudentContextField() {
        val request = legacyTutorPlanRequest().copy(
            schemaVersion = 1,
            egressManifest = null,
        )
        val legacyJson = ModelTaskCodec.encodeRequest(request)
            .replace(",\"priorCycleStudentMessages\":[]", "")
            .replace(",\"egressManifest\":null", "")
        val decoded = ModelTaskCodec.decodeRequest(legacyJson)
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(legacyJson.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, ModelTaskFingerprint.of(decoded))
    }

    @Test
    fun persistedSchemaOneTutorPlanManifestStillDecodesButCannotAuthorizeACurrentPrompt() {
        val request = legacyTutorPlanRequest()
        val decoded = ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(request))

        assertEquals(1, requireNotNull(decoded.egressManifest).schemaVersion)
        assertTrue(
            runCatching {
            ModelEgressPolicy.authorize(
                decoded,
                tutorProvider(ModelTaskKind.TUTOR_PLAN),
                101,
            )
            }.exceptionOrNull() is ModelEgressAuthorizationException,
        )
        assertTrue(
            runCatching {
                legacyTutorPlanManifest().copy(
                    authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_RESPOND),
                    disclosedData = ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE,
                    prohibitedData = ModelEgressManifest.SCHEMA_V1_DATA_CLASSES -
                        ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE,
                )
            }.isFailure,
        )
    }

    @Test
    fun persistedSchemaTwoTutorPlanManifestKeepsItsOriginalDisclosure() {
        val legacyManifest = legacyTutorPlanManifest().copy(
            schemaVersion = 2,
            prohibitedData = ModelEgressManifest.dataClassUniverseForSchema(2) -
                ModelEgressManifest.LEGACY_TUTOR_PLAN_DISCLOSURE,
        )
        val request = legacyTutorPlanRequest().copy(
            schemaVersion = 2,
            egressManifest = legacyManifest,
        )
        val encoded = ModelTaskCodec.encodeRequest(request)
        val legacyJson = encoded.replace(",\"priorCycleStudentMessages\":[]", "")
        val expectedFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(legacyJson.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val decoded = ModelTaskCodec.decodeRequest(legacyJson)

        assertEquals(2, decoded.schemaVersion)
        assertEquals(2, requireNotNull(decoded.egressManifest).schemaVersion)
        assertEquals(expectedFingerprint, ModelTaskFingerprint.of(decoded))
        assertTrue(
            runCatching {
            ModelEgressPolicy.authorize(
                decoded,
                tutorProvider(ModelTaskKind.TUTOR_PLAN),
                101,
            )
            }.exceptionOrNull() is ModelEgressAuthorizationException,
        )
    }

    @Test
    fun currentTutorPlanDisclosureReplacesLearningEvidenceWithTeachingConstraints() {
        assertEquals(
            setOf(
                ModelEgressDataClass.CONFIRMED_QUESTION_DOCUMENT,
                ModelEgressDataClass.CURRENT_QUESTION_TEACHING_CONSTRAINTS,
                ModelEgressDataClass.STUDENT_TUTOR_MESSAGE,
                ModelEgressDataClass.TUTOR_CONVERSATION_CONTEXT,
                ModelEgressDataClass.SUBJECT_KNOWLEDGE_BASE,
            ),
            ModelEgressManifest.TUTOR_PLAN_DISCLOSURE,
        )
    }

    @Test
    fun tutorResponseApprovalUsesTheExactNewDisclosure() {
        val manifest = tutorRespondManifest()
        val request = tutorRespondRequest(manifest)

        assertEquals(
            setOf(
                ModelEgressDataClass.CONFIRMED_QUESTION_DOCUMENT,
                ModelEgressDataClass.CURRENT_QUESTION_TEACHING_CONSTRAINTS,
                ModelEgressDataClass.STUDENT_TUTOR_MESSAGE,
                ModelEgressDataClass.TUTOR_CONVERSATION_CONTEXT,
                ModelEgressDataClass.SUBJECT_KNOWLEDGE_BASE,
            ),
            manifest.disclosedData,
        )
        assertTrue(
            ModelEgressPolicy.authorize(
                request,
                tutorProvider(ModelTaskKind.TUTOR_RESPOND),
                101,
            ).permit is ModelExecutionPermit.External,
        )
        assertTrue(
            runCatching {
                manifest.copy(
                    disclosedData = manifest.disclosedData -
                        ModelEgressDataClass.TUTOR_CONVERSATION_CONTEXT,
                    prohibitedData = manifest.prohibitedData +
                        ModelEgressDataClass.TUTOR_CONVERSATION_CONTEXT,
                )
            }.isFailure,
        )
    }

    @Test
    fun staleOrFutureApprovalIsRejectedBeforeExternalExecution() {
        val expired = runCatching {
            ModelEgressPolicy.authorize(
                request(manifest()),
                externalProvider(),
                101 + MODEL_EGRESS_APPROVAL_TTL_MILLIS + 1,
            )
        }.exceptionOrNull() as ModelEgressAuthorizationException
        val future = runCatching {
            ModelEgressPolicy.authorize(
                request(manifest().copy(approvedAtEpochMillis = 10_000_000)),
                externalProvider(),
                101,
            )
        }.exceptionOrNull() as ModelEgressAuthorizationException

        assertEquals(ModelFailureCode.EGRESS_AUTHORIZATION_INVALID, expired.failureCode)
        assertEquals(ModelFailureCode.EGRESS_AUTHORIZATION_INVALID, future.failureCode)
    }

    @Test
    fun tutorConversationGrantMayPrecedeANewMessageWhileItIsStillFresh() {
        val manifest = tutorRespondManifest().copy(approvedAtEpochMillis = 90)

        val execution = ModelEgressPolicy.authorize(
            tutorRespondRequest(manifest),
            tutorProvider(ModelTaskKind.TUTOR_RESPOND),
            101,
        )

        assertTrue(execution.permit is ModelExecutionPermit.External)
    }

    @Test
    fun tutorConversationGrantMayPrecedeANewPlanWhileItIsStillFresh() {
        val input = tutorPlanInput()
        val requestId = "tutor-plan:legacy-request"
        val request = legacyTutorPlanRequest().copy(
            schemaVersion = ModelTaskRequest.CURRENT_SCHEMA_VERSION,
            egressManifest = currentTutorPlanManifest(requestId, input).copy(approvedAtEpochMillis = 90),
        )

        val execution = ModelEgressPolicy.authorize(
            request,
            tutorProvider(ModelTaskKind.TUTOR_PLAN),
            101,
        )

        assertTrue(execution.permit is ModelExecutionPermit.External)
    }

    @Test
    fun tutorApprovalCannotBeReusedForChangedPayloadOrRequestId() {
        val requestId = "tutor-respond:request-1"
        val input = tutorRespondInput()
        val manifest = tutorRespondManifest(requestId, input)

        val changedMessage = tutorRespondRequest(
            manifest = manifest,
            input = input.copy(studentMessage = "请直接告诉我答案。"),
        )
        val changedRequestId = tutorRespondRequest(
            manifest = manifest,
            requestId = "tutor-respond:request-2",
            input = input,
        )

        listOf(changedMessage, changedRequestId).forEach { changedRequest ->
            val failure = runCatching {
                ModelEgressPolicy.authorize(
                    changedRequest,
                    tutorProvider(ModelTaskKind.TUTOR_RESPOND),
                    101,
                )
            }.exceptionOrNull() as ModelEgressAuthorizationException

            assertEquals(ModelFailureCode.EGRESS_AUTHORIZATION_INVALID, failure.failureCode)
        }
    }

    private fun request(
        manifest: ModelEgressManifest?,
        requestId: String = "capture-assess:request-1",
        input: CaptureAssessmentInput = captureAssessmentInput(),
    ) = ModelTaskRequest(
        requestId = requestId,
        input = input,
        occurredAtEpochMillis = 100,
        egressManifest = manifest,
    )

    private fun captureAssessmentInput() = CaptureAssessmentInput(
        draftId = "draft-1",
        sourceAssetId = "asset-1",
        origin = CaptureAssessmentOrigin.LIBRARY,
        imageWidth = 1080,
        imageHeight = 1440,
    )

    private fun manifest(
        requestId: String = "capture-assess:request-1",
        input: CaptureAssessmentInput = captureAssessmentInput(),
    ) = ModelEgressManifest(
        authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
        subjectId = input.subjectId,
        purpose = ModelEgressPurpose.CAPTURE_TO_DOCUMENT,
        authorizedTaskKinds = setOf(
            ModelTaskKind.CAPTURE_ASSESS,
            ModelTaskKind.CAPTURE_PARSE,
        ),
        providerId = "provider-1",
        modelId = "vision-model-1",
        providerConfigurationVersion = "provider-config-v1",
        promptPolicyVersion = ModelPromptPolicyVersions.CAPTURE_DOCUMENT,
        approvedAtEpochMillis = 101,
        assets = listOf(
            ModelEgressAssetGrant(
                assetId = "asset-1",
                sha256 = "a".repeat(64),
                byteSize = 2_048,
                width = 1080,
                height = 1440,
            ),
        ),
        disclosedData = setOf(
            ModelEgressDataClass.SANITIZED_IMAGE_BYTES,
            ModelEgressDataClass.IMAGE_DIMENSIONS,
        ),
        prohibitedData = ModelEgressManifest.CAPTURE_PROHIBITED_DATA,
    )

    private fun assertAuthorizationInvalid(
        request: ModelTaskRequest,
        provider: ProviderCapabilitySnapshot,
    ) {
        val failure = runCatching {
            ModelEgressPolicy.authorize(request, provider, 101)
        }.exceptionOrNull() as ModelEgressAuthorizationException

        assertEquals(ModelFailureCode.EGRESS_AUTHORIZATION_INVALID, failure.failureCode)
    }

    private fun legacyTutorPlanRequest() = ModelTaskRequest(
        requestId = "tutor-plan:legacy-request",
        input = tutorPlanInput(),
        occurredAtEpochMillis = 100,
        egressManifest = legacyTutorPlanManifest(),
    )

    private fun tutorPlanInput() = TutorPlanInput(
        sessionId = "tutor-session-1",
        draftRevisionNumber = 2,
        subject = "MATH",
        questionDocument = confirmedQuestion(),
        teachingConstraints = emptyList(),
    )

    private fun legacyTutorPlanManifest() = ModelEgressManifest(
        schemaVersion = 1,
        authorizationId = "tutor-plan-legacy-approval",
        subjectId = "tutor-session-1",
        purpose = ModelEgressPurpose.TUTORING,
        authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_PLAN),
        providerId = "provider-1",
        modelId = "vision-model-1",
        providerConfigurationVersion = "provider-config-v1",
        promptPolicyVersion = "tutor-plan-v1",
        approvedAtEpochMillis = 101,
        assets = emptyList(),
        disclosedData = ModelEgressManifest.LEGACY_TUTOR_PLAN_DISCLOSURE,
        prohibitedData = ModelEgressManifest.SCHEMA_V1_DATA_CLASSES -
            ModelEgressManifest.LEGACY_TUTOR_PLAN_DISCLOSURE,
    )

    private fun currentTutorPlanManifest(
        requestId: String = "tutor-plan:legacy-request",
        input: TutorPlanInput = tutorPlanInput(),
    ) = ModelEgressManifest(
        authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
        subjectId = "tutor-session-1",
        purpose = ModelEgressPurpose.TUTORING,
        authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_PLAN),
        providerId = "provider-1",
        modelId = "vision-model-1",
        providerConfigurationVersion = "provider-config-v1",
        promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_PLAN,
        approvedAtEpochMillis = 101,
        assets = emptyList(),
        disclosedData = ModelEgressManifest.TUTOR_PLAN_DISCLOSURE,
        prohibitedData = ModelEgressManifest.TUTOR_PLAN_PROHIBITED_DATA,
    )

    private fun tutorRespondRequest(
        manifest: ModelEgressManifest,
        requestId: String = "tutor-respond:request-1",
        input: TutorRespondInput = tutorRespondInput(),
    ) = ModelTaskRequest(
        requestId = requestId,
        input = input,
        occurredAtEpochMillis = 100,
        egressManifest = manifest,
    )

    private fun tutorRespondInput() = TutorRespondInput(
        sessionId = "tutor-session-1",
        draftRevisionNumber = 2,
        subject = "MATH",
        questionDocument = confirmedQuestion(),
        teachingConstraints = emptyList(),
        responseOrdinal = 1,
        studentMessage = "请解释当前题这一步。",
    )

    private fun tutorRespondManifest(
        requestId: String = "tutor-respond:request-1",
        input: TutorRespondInput = tutorRespondInput(),
    ) = ModelEgressManifest(
        authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
        subjectId = "tutor-session-1",
        purpose = ModelEgressPurpose.TUTORING,
        authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_RESPOND),
        providerId = "provider-1",
        modelId = "vision-model-1",
        providerConfigurationVersion = "provider-config-v1",
        promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_RESPOND,
        approvedAtEpochMillis = 101,
        assets = emptyList(),
        disclosedData = ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE,
        prohibitedData = ModelEgressManifest.TUTOR_RESPOND_PROHIBITED_DATA,
    )

    private fun confirmedQuestion() = QuestionDocument(
        id = "question-1",
        blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
    )

    private fun tutorProvider(kind: ModelTaskKind) = externalProvider().copy(
        supportedTasks = setOf(kind),
        supportsImageInput = false,
    )

    private fun externalProvider() = ProviderCapabilitySnapshot(
        providerId = "provider-1",
        providerDisplayName = "我的视觉模型",
        modelId = "vision-model-1",
        supportedTasks = setOf(ModelTaskKind.CAPTURE_ASSESS, ModelTaskKind.CAPTURE_PARSE),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = true,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = "provider-config-v1",
    )
}
