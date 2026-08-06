package com.tingyun.smartmistakebook.core.model.provider

import com.tingyun.smartmistakebook.core.domain.ModelApiKey
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerification
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationMutationResult
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationUpdate
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAsset
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAssetSource
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.CaptureParseOutput
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.CurrentTutorInteractionPolicyWire
import com.tingyun.smartmistakebook.core.model.FigureAxis
import com.tingyun.smartmistakebook.core.model.FigureCoordinate
import com.tingyun.smartmistakebook.core.model.FigureLabel
import com.tingyun.smartmistakebook.core.model.FigurePoint
import com.tingyun.smartmistakebook.core.model.FigurePolyline
import com.tingyun.smartmistakebook.core.model.GUIDED_INTERACTION_MESSAGE
import com.tingyun.smartmistakebook.core.model.GUIDED_FREE_RESPONSE_PROMPT
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseCatalogProvenance
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.FigureSchema
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationId
import com.tingyun.smartmistakebook.core.model.ModelEgressDataClass
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPolicy
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.MODEL_EGRESS_APPROVAL_TTL_MILLIS
import com.tingyun.smartmistakebook.core.model.MODEL_EGRESS_MAX_CLOCK_SKEW_MILLIS
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.StructuredChoice
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationOutput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorFreeResponseEvaluation
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorComparisonScene
import com.tingyun.smartmistakebook.core.model.TutorCircularMotionScene
import com.tingyun.smartmistakebook.core.model.TutorConceptMapScene
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorConversationMemory
import com.tingyun.smartmistakebook.core.model.TutorEvidenceChainScene
import com.tingyun.smartmistakebook.core.model.TutorFormulaDerivationScene
import com.tingyun.smartmistakebook.core.model.TutorLinearMotionScene
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorLobbyVisualKind
import com.tingyun.smartmistakebook.core.model.TutorLobbyVisualRequest
import com.tingyun.smartmistakebook.core.model.TutorMemoryPreference
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorRequestedLocalCapability
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeGuidance
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorProcessTimelineScene
import com.tingyun.smartmistakebook.core.model.TutorProjectileMotionScene
import com.tingyun.smartmistakebook.core.model.TutorOscillationMotionScene
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorResponseIntent
import com.tingyun.smartmistakebook.core.model.TutorSpatialDiagramScene
import com.tingyun.smartmistakebook.core.model.TutorStepFlowScene
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference
import com.tingyun.smartmistakebook.core.model.TutorTeachingConstraint
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorVisualProgramScene
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.TutorVisualDimension
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualSourceFact
import com.tingyun.smartmistakebook.core.model.TutorVisualSourceTextField
import com.tingyun.smartmistakebook.core.model.TutorVisualSourceTextLocator
import com.tingyun.smartmistakebook.core.model.TutorVisualValueProof
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualStep
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.canExposeSolutionFor
import com.tingyun.smartmistakebook.core.model.toCurrentTutorInteractionPolicy
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleModelGatewayTest {
    @Test
    fun configuredButUntestedProviderAdvertisesNoModelTasks() = runBlocking {
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(
                CONFIGURATION.copy(capabilityVerification = null),
            ),
            assetSource = assetSource { _, _ -> asset() },
            clock = { AUTHORIZATION_NOW },
        )

        val capabilities = gateway.capabilities()

        assertTrue(capabilities.supportedTasks.isEmpty())
        assertFalse(capabilities.supportsImageInput)
        assertFalse(capabilities.supportsStructuredOutput)
    }

    @Test
    fun onlyCapabilitiesThatPassedTheExactTestAreAdvertised() = runBlocking {
        val verification = requireNotNull(CONFIGURATION.capabilityVerification).copy(
            supportsImageInput = false,
            supportsStructuredOutput = true,
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(
                CONFIGURATION.copy(capabilityVerification = verification),
            ),
            assetSource = assetSource { _, _ -> asset() },
            clock = { AUTHORIZATION_NOW },
        )

        val capabilities = gateway.capabilities()

        assertTrue(capabilities.supports(ModelTaskKind.TUTOR_PLAN))
        assertTrue(capabilities.supports(ModelTaskKind.TUTOR_RESPOND))
        assertTrue(capabilities.supports(ModelTaskKind.TUTOR_LOBBY))
        assertTrue(capabilities.supports(ModelTaskKind.PROBLEM_CLASSIFY))
        assertFalse(capabilities.supports(ModelTaskKind.CAPTURE_ASSESS))
        assertFalse(capabilities.supportsImageInput)
        assertTrue(capabilities.supportsStructuredOutput)
    }

    @Test
    fun missingCredentialDoesNotOpenAssetOrCallTransport() = runBlocking {
        var assetOpened = false
        var transportCalled = false
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION, credentialAvailable = false),
            assetSource = assetSource { _, _ ->
                assetOpened = true
                asset()
            },
            transport = modelTransport { _, _, _ ->
                transportCalled = true
                ModelHttpResponse(200, "{}")
            },
            clock = { AUTHORIZATION_NOW },
        )
        val events = gateway.execute(authorizedAssessment(gateway)).toList()

        assertFalse(assetOpened)
        assertFalse(transportCalled)
        assertEquals(
            ModelFailureCode.MODEL_NOT_CONFIGURED,
            (events.single() as ModelGatewayEvent.Failed).failure.code,
        )
    }

    @Test
    fun readinessRevokedWhilePreparingFailsClosedBeforeTransport() = runBlocking {
        val store = FakeConfigurationStore(CONFIGURATION)
        var transportCalled = false
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = store,
            assetSource = assetSource { _, _ ->
                val revoked = requireNotNull(store.state.value.capabilityVerification).copy(
                    supportsImageInput = false,
                    supportsStructuredOutput = false,
                )
                store.state.value = store.state.value.copy(capabilityVerification = revoked)
                asset()
            },
            transport = modelTransport { _, _, _ ->
                transportCalled = true
                ModelHttpResponse(200, envelope(assessmentPayload()))
            },
            clock = { AUTHORIZATION_NOW },
        )
        val events = gateway.execute(authorizedAssessment(gateway)).toList()

        assertFalse(transportCalled)
        assertEquals(
            ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
            (events.last() as ModelGatewayEvent.Failed).failure.code,
        )
    }

    @Test
    fun approvalExpiringDuringImagePreparationFailsClosedBeforeTransport() = runBlocking {
        var nowEpochMillis = AUTHORIZATION_NOW
        var transportCalled = false
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ ->
                nowEpochMillis = AUTHORIZATION_APPROVED_AT +
                    MODEL_EGRESS_APPROVAL_TTL_MILLIS + 1
                asset()
            },
            transport = modelTransport { _, _, _ ->
                transportCalled = true
                ModelHttpResponse(200, envelope(assessmentPayload()))
            },
            clock = { nowEpochMillis },
        )

        val events = gateway.execute(authorizedAssessment(gateway)).toList()

        assertFalse(transportCalled)
        assertEquals(
            ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
            (events.last() as ModelGatewayEvent.Failed).failure.code,
        )
    }

    @Test
    fun approvalTooFarInFutureAfterImagePreparationFailsClosedBeforeTransport() = runBlocking {
        var nowEpochMillis = AUTHORIZATION_NOW
        var transportCalled = false
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ ->
                nowEpochMillis = AUTHORIZATION_APPROVED_AT -
                    MODEL_EGRESS_MAX_CLOCK_SKEW_MILLIS - 1
                asset()
            },
            transport = modelTransport { _, _, _ ->
                transportCalled = true
                ModelHttpResponse(200, envelope(assessmentPayload()))
            },
            clock = { nowEpochMillis },
        )

        val events = gateway.execute(authorizedAssessment(gateway)).toList()

        assertFalse(transportCalled)
        assertEquals(
            ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
            (events.last() as ModelGatewayEvent.Failed).failure.code,
        )
    }

    @Test
    fun credentialClearedDuringEndpointPreparationFailsClosedBeforeEnqueue() = runBlocking {
        val store = FakeConfigurationStore(CONFIGURATION)
        val transport = BlockingBeforeEnqueueTransport(
            ModelHttpResponse(200, envelope(assessmentPayload())),
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = store,
            assetSource = assetSource { _, _ -> asset() },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )
        val execution = authorizedAssessment(gateway)

        val pendingEvents = async { gateway.execute(execution).toList() }
        withTimeout(5_000L) { transport.endpointPrepared.await() }
        store.clearCredential()
        transport.continueToEnqueue.complete(Unit)
        val events = pendingEvents.await()

        assertEquals(0, transport.networkEnqueueCount)
        assertEquals(
            ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
            (events.last() as ModelGatewayEvent.Failed).failure.code,
        )
    }

    @Test
    fun credentialRotatedDuringEndpointPreparationFailsClosedBeforeEnqueue() = runBlocking {
        val store = FakeConfigurationStore(CONFIGURATION)
        val transport = BlockingBeforeEnqueueTransport(
            ModelHttpResponse(200, envelope(assessmentPayload())),
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = store,
            assetSource = assetSource { _, _ -> asset() },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )
        val execution = authorizedAssessment(gateway)

        val pendingEvents = async { gateway.execute(execution).toList() }
        withTimeout(5_000L) { transport.endpointPrepared.await() }
        store.rotateCredential()
        transport.continueToEnqueue.complete(Unit)
        val events = pendingEvents.await()

        assertEquals(0, transport.networkEnqueueCount)
        assertEquals(
            ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
            (events.last() as ModelGatewayEvent.Failed).failure.code,
        )
    }

    @Test
    fun approvalExpiresDuringEndpointPreparationFailsClosedBeforeEnqueue() = runBlocking {
        var nowEpochMillis = AUTHORIZATION_NOW
        val transport = BlockingBeforeEnqueueTransport(
            ModelHttpResponse(200, envelope(assessmentPayload())),
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> asset() },
            transport = transport,
            clock = { nowEpochMillis },
        )
        val execution = authorizedAssessment(gateway)

        val pendingEvents = async { gateway.execute(execution).toList() }
        withTimeout(5_000L) { transport.endpointPrepared.await() }
        nowEpochMillis = AUTHORIZATION_APPROVED_AT + MODEL_EGRESS_APPROVAL_TTL_MILLIS + 1
        transport.continueToEnqueue.complete(Unit)
        val events = pendingEvents.await()

        assertEquals(0, transport.networkEnqueueCount)
        assertEquals(
            ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
            (events.last() as ModelGatewayEvent.Failed).failure.code,
        )
    }

    @Test
    fun localOnlyPermitNeverReachesNetworkEnqueue() = runBlocking {
        val transport = BlockingBeforeEnqueueTransport(
            ModelHttpResponse(200, envelope(assessmentPayload())),
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> asset() },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )
        val externalExecution = authorizedAssessment(gateway)
        val localOnlyExecution = ModelEgressPolicy.authorize(
            request = externalExecution.request,
            provider = gateway.capabilities().copy(
                executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
            ),
            nowEpochMillis = AUTHORIZATION_NOW,
        )

        val events = gateway.execute(localOnlyExecution).toList()

        assertEquals(0, transport.networkEnqueueCount)
        assertEquals(
            ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
            (events.last() as ModelGatewayEvent.Failed).failure.code,
        )
    }

    @Test
    fun assessmentUsesOnlyApprovedImageAndMapsBoundedJson() = runBlocking {
        var sentBody = ""
        var sentKey = ""
        var borrowedKey: CharArray? = null
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, assetId ->
                assertEquals(ASSET_ID, assetId)
                asset()
            },
            transport = modelTransport { _, key, body ->
                borrowedKey = key
                sentBody = body
                sentKey = String(key)
                ModelHttpResponse(200, envelope(assessmentPayload()))
            },
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedAssessment(gateway)).toList()
        val output = (events.last() as ModelGatewayEvent.Completed).output as CaptureAssessmentOutput

        assertEquals(CaptureAssessmentDecision.PASS, output.assessment.decision)
        assertEquals(CONFIGURATION.modelId, output.assessment.modelVersion)
        assertEquals("secret", sentKey)
        assertTrue(borrowedKey?.all { it == '\u0000' } == true)
        assertTrue(sentBody.contains("data:image/jpeg;base64,"))
        assertFalse(sentBody.contains(DRAFT_ID))
        assertFalse(sentBody.contains(ASSET_ID))
    }

    @Test
    fun assessmentMapsIndependentQuestionRegionsInReadingOrder() = runBlocking {
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> asset() },
            transport = modelTransport { _, _, _ ->
                ModelHttpResponse(200, envelope(splitAssessmentPayload()))
            },
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedAssessment(gateway)).toList()
        val output = (events.last() as ModelGatewayEvent.Completed).output as CaptureAssessmentOutput

        assertEquals(CaptureAssessmentDecision.SPLIT, output.assessment.decision)
        assertEquals(2, output.assessment.questionRegions.size)
        assertEquals(0.05, output.assessment.questionRegions.first().top, 0.0)
        assertEquals(0.55, output.assessment.questionRegions.last().top, 0.0)
    }

    @Test
    fun oversizedMultiPageRequestFailsBeforeOpeningAnyApprovedAsset() = runBlocking {
        var assetOpened = false
        var transportCalled = false
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ ->
                assetOpened = true
                asset()
            },
            transport = modelTransport { _, _, _ ->
                transportCalled = true
                ModelHttpResponse(200, envelope(parsePayload()))
            },
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(
            authorizedParseWithDeclaredAssetBytes(
                gateway = gateway,
                byteSizes = listOf(14L * 1_024L * 1_024L, 14L * 1_024L * 1_024L),
            ),
        ).toList()
        val failure = (events.single() as ModelGatewayEvent.Failed).failure

        assertFalse(assetOpened)
        assertFalse(transportCalled)
        assertEquals(ModelFailureCode.PROVIDER_REJECTED_INPUT, failure.code)
        assertEquals("本次题图总量超过单次发送上限，请减少图片后重试", failure.message)
        assertFalse(failure.retryable)
        assertFalse(failure.message.contains(ASSET_ID))
    }

    @Test
    fun parseOverwritesTrustFieldsAndNeverPreselectsAnswer() = runBlocking {
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> asset() },
            transport = modelTransport { _, _, _ ->
                ModelHttpResponse(200, envelope(parsePayload()))
            },
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedParse(gateway)).toList()
        val output = (events.last() as ModelGatewayEvent.Completed).output as CaptureParseOutput
        val choice = output.capturedDocument.document.blocks.single() as ContentBlock.ChoiceGroup
        val evidence = output.capturedDocument.blockEvidence.single()

        assertEquals("document-$DRAFT_ID", output.capturedDocument.document.id)
        assertEquals("block-1", choice.id)
        assertEquals(listOf("choice-1-1", "choice-1-2"), choice.choices.map { it.id })
        assertNull(choice.selectedChoiceId)
        assertEquals(ASSET_ID, evidence.sourceAssetId)
        assertEquals(QuestionBlockProvenance.MODEL_DOCUMENT_PARSE, evidence.provenance)
        assertEquals(QuestionBlockReviewStatus.NEEDS_REVIEW, evidence.reviewStatus)
        assertEquals(CONFIGURATION.modelId, evidence.producerVersion)
    }

    @Test
    fun parseReconstructsCartesianFigureWithLocalIdsAndDiagramEvidence() = runBlocking {
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> asset() },
            transport = modelTransport { _, _, _ ->
                ModelHttpResponse(200, envelope(figureParsePayload()))
            },
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedParse(gateway)).toList()
        val output = (events.last() as ModelGatewayEvent.Completed).output as CaptureParseOutput
        val figure = output.capturedDocument.document.blocks.single() as ContentBlock.Figure
        val schema = figure.schema as FigureSchema.Cartesian
        val evidence = output.capturedDocument.blockEvidence.single()

        assertEquals("block-1", figure.id)
        assertEquals("polyline-1-1", schema.polylines.single().id)
        assertEquals("x", schema.xAxis.label)
        assertEquals("y", schema.yAxis.label)
        assertEquals(WritingLayer.DIAGRAM, evidence.writingLayer)
        assertEquals(ASSET_ID, evidence.sourceAssetId)
        assertEquals(QuestionBlockReviewStatus.NEEDS_REVIEW, evidence.reviewStatus)
    }

    @Test
    fun parseRejectsInvalidFigureAxisInsteadOfFallingBackSilently() = runBlocking {
        val invalidPayload = figureParsePayload().replaceFirst(
            oldValue = "\"maximum\":2.0",
            newValue = "\"maximum\":-2.0",
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> asset() },
            transport = modelTransport { _, _, _ ->
                ModelHttpResponse(200, envelope(invalidPayload))
            },
            clock = { AUTHORIZATION_NOW },
        )

        val failed = gateway.execute(authorizedParse(gateway)).toList().last()
            as ModelGatewayEvent.Failed

        assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
        assertFalse(failed.failure.retryable)
    }

    @Test
    fun authenticationFailureIsPermanentAndActionable() = runBlocking {
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> asset() },
            transport = modelTransport { _, _, _ -> ModelHttpResponse(401, "") },
            clock = { AUTHORIZATION_NOW },
        )

        val failed = gateway.execute(authorizedAssessment(gateway)).toList().last()
            as ModelGatewayEvent.Failed

        assertEquals(ModelFailureCode.AUTHENTICATION_FAILED, failed.failure.code)
        assertFalse(failed.failure.retryable)
        assertTrue(failed.failure.message.contains("API Key"))
    }

    @Test
    fun tutorPlanSendsNoImageOrLocalIdsAndReconstructsTrustedContext() = runBlocking {
        var assetOpened = false
        var sentBody = ""
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ ->
                assetOpened = true
                asset()
            },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(200, envelope(tutorPayload()))
            },
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedTutor(gateway)).toList()
        val output = (events.last() as ModelGatewayEvent.Completed).output as TutorPlanOutput

        assertFalse(assetOpened)
        assertTrue(sentBody.contains("求函数的单调区间"))
        assertTrue(sentBody.contains("导数符号"))
        assertTrue(sentBody.contains("current-question-point-1"))
        assertTrue(sentBody.contains("MAY_GUIDE"))
        assertTrue(sentBody.contains("完整例题：先找导数为零的分界点"))
        assertTrue(sentBody.contains("“包含题目和解答”不等于题库"))
        assertTrue(sentBody.contains("不是学生作答、不是掌握证据"))
        assertFalse(sentBody.contains(TUTOR_SESSION_ID))
        assertFalse(sentBody.contains("node-derivative"))
        assertFalse(sentBody.contains("teaching-method-1"))
        assertFalse(sentBody.contains("data:image"))
        assertTrue(sentBody.contains("严禁生成新题、同类题、变式题、校准题"))
        assertTrue(sentBody.contains("diagnosticQuestion是可选的当前题内交互块"))
        assertTrue(sentBody.contains("visualRequest可选且最多一个"))
        assertTrue(sentBody.contains("本次不得返回visualScene"))
        assertTrue(sentBody.contains("后续视觉任务会另行读取题图"))
        assertFalse(sentBody.contains("visual_program"))
        assertTrue(sentBody.contains("ID或未列出的字段"))
        assertTrue(sentBody.contains("不得出现图片、SVG、HTML、CSS、JS、代码"))
        assertTrue(sentBody.contains("inferredKnowledgeLabels给当前题涉及的1到8个知识标签"))
        assertEquals(TUTOR_SESSION_ID, output.sessionId)
        assertEquals("question-confirmed", output.questionDocumentId)
        assertNull(output.plan.diagnosticItem)
        val directive = output.plan.interactionDirective as TutorInteractionDirective.FreeResponse
        assertEquals(GUIDED_FREE_RESPONSE_PROMPT, directive.promptMarkdown)
    }

    @Test
    fun tutorPlanAndRespondProjectQuestionsWithoutNestedLocalIdentityOrSelectionState() =
        runBlocking {
            val localIdentities =
                listOf(
                    "local-question-id-never-egress",
                    "local-paragraph-id-never-egress",
                    "local-formula-id-never-egress",
                    "local-choice-block-id-never-egress",
                    "local-choice-a-id-never-egress",
                    "local-selected-choice-id-never-egress",
                    "local-figure-block-id-never-egress",
                    "local-polyline-id-never-egress",
                )
            val question =
                QuestionDocument(
                    id = localIdentities[0],
                    title = "含图选择题",
                    blocks =
                        listOf(
                            ContentBlock.Paragraph(
                                id = localIdentities[1],
                                markdown = "观察曲线并判断变化趋势。",
                            ),
                            ContentBlock.Formula(
                                id = localIdentities[2],
                                latex = "v=\\frac{\\Delta x}{\\Delta t}",
                                alternativeText = "速度等于位移变化量除以时间变化量",
                            ),
                            ContentBlock.ChoiceGroup(
                                id = localIdentities[3],
                                promptMarkdown = "请选择与曲线相符的说法",
                                choices =
                                    listOf(
                                        StructuredChoice(
                                            id = localIdentities[4],
                                            markdown = "先减小后增大",
                                            accessibilityLabel = "选项甲",
                                        ),
                                        StructuredChoice(
                                            id = localIdentities[5],
                                            markdown = "始终增大",
                                            accessibilityLabel = "选项乙",
                                        ),
                                    ),
                                selectedChoiceId = localIdentities[5],
                            ),
                            ContentBlock.Figure(
                                id = localIdentities[6],
                                title = "速度随时间变化",
                                alternativeText = "速度曲线经过标记点",
                                schema =
                                    FigureSchema.Cartesian(
                                        xAxis = FigureAxis(-2.0, 2.0, "时间", 4),
                                        yAxis = FigureAxis(-1.0, 3.0, "速度", 4),
                                        polylines =
                                            listOf(
                                                FigurePolyline(
                                                    id = localIdentities[7],
                                                    points =
                                                        listOf(
                                                            FigureCoordinate(-0.5, 1.25),
                                                            FigureCoordinate(1.5, 2.5),
                                                        ),
                                                    label = "速度曲线",
                                                ),
                                            ),
                                        points =
                                            listOf(
                                                FigurePoint(
                                                    coordinate = FigureCoordinate(1.5, 2.5),
                                                    label = "最高点",
                                                ),
                                            ),
                                        labels =
                                            listOf(
                                                FigureLabel(
                                                    coordinate = FigureCoordinate(-0.5, 1.25),
                                                    text = "起始观察点",
                                                ),
                                            ),
                                    ),
                            ),
                        ),
                )
            val sentBodies = mutableListOf<String>()
            val gateway =
                OpenAiCompatibleModelGateway(
                    configurationStore = FakeConfigurationStore(CONFIGURATION),
                    assetSource = assetSource { _, _ ->
                        error("Tutor text tasks must not open image assets")
                    },
                    transport =
                        modelTransport { _, _, body ->
                            sentBodies += body
                            ModelHttpResponse(
                                statusCode = 400,
                                body = localIdentities.joinToString(),
                            )
                        },
                    clock = { AUTHORIZATION_NOW },
                )

            val planEvents =
                gateway.execute(
                    authorizedTutor(
                        gateway,
                        tutorInput().copy(questionDocument = question),
                    ),
                ).toList()
            val respondEvents =
                gateway.execute(
                    authorizedTutorRespond(
                        gateway,
                        tutorRespondInput().copy(questionDocument = question),
                    ),
                ).toList()

            assertEquals(2, sentBodies.size)
            sentBodies.forEach { body ->
                localIdentities.forEach { identity ->
                    assertFalse("Provider body leaked $identity", body.contains(identity))
                }
                assertFalse(body.contains("selectedChoiceId"))
                assertTrue(body.contains("观察曲线并判断变化趋势"))
                assertTrue(body.contains("速度等于位移变化量除以时间变化量"))
                assertTrue(body.contains("请选择与曲线相符的说法"))
                assertTrue(body.contains("选项甲"))
                assertTrue(body.contains("速度随时间变化"))
                assertTrue(body.contains("速度曲线"))
                assertTrue(body.contains("最高点"))
                assertTrue(body.contains("起始观察点"))
                assertTrue(body.contains("1.25"))
                assertTrue(body.contains("2.5"))
            }
            listOf(planEvents, respondEvents).forEach { events ->
                val failure = events.last() as ModelGatewayEvent.Failed
                assertEquals(ModelFailureCode.PROVIDER_REJECTED_INPUT, failure.failure.code)
                val observableFailure = failure.toString()
                localIdentities.forEach { identity ->
                    assertFalse(
                        "Provider failure leaked $identity",
                        observableFailure.contains(identity),
                    )
                }
            }
        }

    @Test
    fun tutorPlanSendsOnlySemanticCurrentQuestionTeachingConstraints() = runBlocking {
        var sentBody = ""
        val input = tutorInput().copy(
            teachingConstraints = listOf(
                TutorKnowledgeGuidance(
                    "current-question-point-1",
                    "导数符号",
                    TutorTeachingConstraint.MAY_GUIDE,
                ),
                TutorKnowledgeGuidance(
                    "current-question-point-2",
                    "一次函数基础",
                    TutorTeachingConstraint.SKIP_BASIC_PROMPT,
                ),
                TutorKnowledgeGuidance(
                    "current-question-point-3",
                    "当前易错点",
                    TutorTeachingConstraint.EXPLAIN_DIRECTLY,
                ),
            ),
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor must not open image assets") },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(200, envelope(tutorPayload()))
            },
            clock = { AUTHORIZATION_NOW },
        )

        gateway.execute(authorizedTutor(gateway, input)).toList()

        listOf(
            "SKIP_BASIC_PROMPT",
            "MAY_GUIDE",
            "EXPLAIN_DIRECTLY",
            "current-question-point-1",
            "current-question-point-2",
            "current-question-point-3",
        ).forEach { allowed -> assertTrue(sentBody.contains(allowed)) }
        listOf(
            "independentCorrectLowerBound",
            "evidenceMass",
            "independentCorrectObservationCount",
            "latestEvidenceRecency",
            "latestIndependentErrorRecency",
            "retentionEstimate",
            "projectionIsCurrent",
            "knowledgeNodeId",
        ).forEach { forbidden -> assertFalse(sentBody.contains(forbidden, ignoreCase = true)) }
    }

    @Test
    fun persistedLegacyTutorPayloadIsCanonicalizedBeforeProviderDispatch() = runBlocking {
        var sentBody = ""
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor must not open image assets") },
            transport = modelTransport { _, _, body ->
                sentBody = body
                val payload = tutorPayload(includeDiagnostic = false).replace(
                    "\"targetedEvidenceLabels\":[\"导数符号\"]",
                    "\"targetedEvidenceLabels\":[]",
                )
                ModelHttpResponse(200, envelope(payload))
            },
            clock = { AUTHORIZATION_NOW },
        )
        val originallyAuthorized = authorizedTutor(
            gateway,
            tutorInput().copy(teachingConstraints = emptyList()),
        ).request
        val legacyPayload = ModelTaskCodec.encodeRequest(
            originallyAuthorized.copy(
                schemaVersion = ModelTaskRequest.TUTOR_RESPOND_CHOICE_ID_SCHEMA_VERSION,
            ),
        ).replace(
            "\"teachingConstraints\":[]",
            """
                "relevantLearningEvidence":[{
                    "knowledgeNodeId":"learner-storage-row-legacy",
                    "displayName":"raw-mastery-label-0.873421",
                    "level":"MASTERED",
                    "independentCorrectLowerBound":0.873421,
                    "evidenceMass":17.25,
                    "independentCorrectObservationCount":9,
                    "latestEvidenceRecency":"WITHIN_7_DAYS",
                    "latestIndependentErrorRecency":"UNKNOWN"
                }],
                "projectionIsCurrent":true,
                "questionLearningEvidence":{
                    "independentRecallCount":7,
                    "assistedRecallCount":2,
                    "retrievalFailureCount":1,
                    "answerRevealCount":3,
                    "retentionEstimate":0.731,
                    "reviewStatus":"DUE"
                }
            """.trimIndent().replace("\n", "").replace(" ", ""),
        )
        val decoded = ModelTaskCodec.decodeRequest(legacyPayload)
        val safeInput = decoded.input as TutorPlanInput
        val execution = ModelEgressPolicy.authorize(
            decoded,
            gateway.capabilities(),
            AUTHORIZATION_NOW,
        )

        gateway.execute(execution).toList()

        assertTrue(safeInput.teachingConstraints.isEmpty())
        listOf(
            "learner-storage-row-legacy",
            "raw-mastery-label-0.873421",
            "independentCorrectLowerBound",
            "evidenceMass",
            "independentCorrectObservationCount",
            "latestEvidenceRecency",
            "latestIndependentErrorRecency",
            "retentionEstimate",
            "0.873421",
            "17.25",
            "0.731",
        ).forEach { forbidden -> assertFalse(sentBody.contains(forbidden, ignoreCase = true)) }
    }

    @Test
    fun tutorPlanAcceptsExplanationWithoutInventingAChoiceQuestion() = runBlocking {
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor must not open image assets") },
            transport = modelTransport { _, _, _ ->
                ModelHttpResponse(200, envelope(tutorPayload(includeDiagnostic = false)))
            },
            clock = { AUTHORIZATION_NOW },
        )

        val output = gateway.execute(authorizedTutor(gateway)).toList().last()
            .let { it as ModelGatewayEvent.Completed }
            .output as TutorPlanOutput

        assertEquals(null, output.plan.diagnosticItem)
        assertNull(output.plan.visualScene)
        assertTrue(output.plan.solutionMarkdown.contains("符号不等式"))
        assertTrue(output.plan.alternateMethodMarkdown.contains("符号表"))
    }

    @Test
    fun guidedTutorPlanCarriesOneCurrentStepHintAndDocumentsItsBoundary() = runBlocking {
        var sentBody = ""
        val hint = "先只判断导数在零点两侧分别是正还是负。"
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor must not open image assets") },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(
                    200,
                    envelope(
                        tutorPayload(
                            extraTopLevel = "hintMarkdown" to JsonPrimitive(hint),
                        ),
                    ),
                )
            },
            clock = { AUTHORIZATION_NOW },
        )

        val output = gateway.execute(authorizedTutor(gateway)).toList().last()
            .let { it as ModelGatewayEvent.Completed }
            .output as TutorPlanOutput

        assertEquals(hint, output.plan.hintMarkdown)
        assertTrue(sentBody.contains("hintMarkdown仅可用于GUIDED且responseIntent=ASK时"))
        assertTrue(sentBody.contains("当前题当前步骤提示"))
        assertTrue(sentBody.contains("不得另出题、改问其他步骤、给最终答案、关键结果、完整解法"))
    }

    @Test
    fun tutorPlanRejectsUnsafeHintPayloadsInsteadOfPresentingThem() = runBlocking {
        val unsafeHints = listOf(
            "   ",
            "提".repeat(TutorTurnPlan.MAX_HINT_CHARS + 1),
            "求导并解符号不等式，再写出单调区间。",
            "最终答案是 x=2。",
        )

        unsafeHints.forEach { hint ->
            val failed = executeTutorPayload(
                tutorPayload(extraTopLevel = "hintMarkdown" to JsonPrimitive(hint)),
            ).last() as ModelGatewayEvent.Failed

            assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
        }
    }

    @Test
    fun directTutorPlanCarriesStructuredModeAndFailsClosedToCompleteExplanation() = runBlocking {
        var sentBody = ""
        val input = tutorInput().copy(
            explanationMode = TutorExplanationMode.DIRECT,
            modeVersion = 7,
            learningWritePermissionVersion = 3,
            allowLongTermLearningWrites = false,
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor must not open image assets") },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(
                    200,
                    envelope(
                        tutorPayload(
                            responseIntent = TutorResponseIntent.ASK,
                            solutionRevealed = false,
                            extraTopLevel = "hintMarkdown" to
                                JsonPrimitive("先只判断导数在零点两侧的符号。"),
                        ),
                    ),
                )
            },
            clock = { AUTHORIZATION_NOW },
        )

        val output = gateway.execute(authorizedTutor(gateway, input)).toList().last()
            .let { it as ModelGatewayEvent.Completed }
            .output as TutorPlanOutput

        assertTrue(sentBody.contains("当前讲解模式为DIRECT"))
        assertTrue(sentBody.contains("modeVersion=7"))
        assertEquals(TutorResponseIntent.EXPLAIN, output.plan.responseIntent)
        assertTrue(output.plan.solutionRevealed)
        assertFalse(output.plan.showOpening)
        assertNull(output.plan.diagnosticItem)
        assertNull(output.plan.interactionDirective)
        assertNull(output.plan.hintMarkdown)
        assertTrue(sentBody.contains("不得返回hintMarkdown"))
    }

    @Test
    fun tutorPlanAcceptsNoContextualMovesInsteadOfForcingButtons() = runBlocking {
        val output = executeTutorPayload(
            tutorPayload(includeNextMoves = false),
        ).last().let { it as ModelGatewayEvent.Completed }.output as TutorPlanOutput

        assertTrue(output.plan.suggestedMoves.isEmpty())
    }

    @Test
    fun tutorPlanRejectsEveryInlineLegacySceneKind() = runBlocking {
        val scenes = listOf(
            stepFlowScenePayload(),
            comparisonScenePayload(),
            evidenceChainScenePayload(),
            processTimelineScenePayload(),
            conceptMapScenePayload(),
            formulaDerivationScenePayload(),
            spatialDiagramScenePayload(),
            circuitDiagramScenePayload(),
            linearMotionScenePayload(),
            projectileMotionScenePayload(),
            circularMotionScenePayload(),
            oscillationMotionScenePayload(),
            visualProgramScenePayload(),
        )

        scenes.forEach { scene ->
            val failed = executeTutorPayload(tutorPayload(visualScene = scene)).last()
                as ModelGatewayEvent.Failed
            assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
            assertFalse(failed.failure.retryable)
        }
    }

    @Test
    fun tutorPlanRejectsScenesOutsideTheExactReadOnlyAllowlist() = runBlocking {
        val invalidScenes = listOf<JsonElement>(
            buildJsonObject {
                put("kind", "spatial_canvas")
                put("title", "任意画布")
            },
            buildJsonArray { add(stepFlowScenePayload()) },
            stepFlowScenePayload(sceneExtras = mapOf("schemaVersion" to JsonPrimitive(1))),
            stepFlowScenePayload(sceneExtras = mapOf("imageUrl" to JsonPrimitive("asset.png"))),
            stepFlowScenePayload(stepExtras = mapOf("action" to JsonPrimitive("run"))),
            stepFlowScenePayload(bodyMarkdown = "完整答案在 https://example.com"),
            stepFlowScenePayload(bodyMarkdown = "<div>完整答案</div>"),
            stepFlowScenePayload(bodyMarkdown = "`println(1)`"),
            processTimelineScenePayload(lastTransitionMarkdown = "不存在的下一阶段"),
            spatialDiagramScenePayload(fromIndex = 0),
            spatialDiagramScenePayload(nodeAnchor = "FREE_POSITION"),
            linearMotionScenePayload(durationSeconds = 31.0),
            linearMotionScenePayload(extra = "pixels" to JsonPrimitive(320)),
        )

        invalidScenes.forEach { scene ->
            val failed = executeTutorPayload(tutorPayload(visualScene = scene)).last()
                as ModelGatewayEvent.Failed
            assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
            assertFalse(failed.failure.retryable)
        }
        val topLevelExtra = executeTutorPayload(
            tutorPayload(
                visualScene = stepFlowScenePayload(),
                extraTopLevel = "imageUrl" to JsonPrimitive("asset.png"),
            ),
        ).last() as ModelGatewayEvent.Failed
        assertEquals(ModelFailureCode.INVALID_RESPONSE, topLevelExtra.failure.code)
    }

    @Test
    fun tutorResponseUsesOnlyBoundedCurrentQuestionTextAndAllowsOmittedOptionalContent() = runBlocking {
        var assetOpened = false
        var sentBody = ""
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ ->
                assetOpened = true
                asset()
            },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(
                    200,
                    envelope(tutorRespondPayload(solutionRevealed = true)),
                )
            },
            clock = { AUTHORIZATION_NOW },
        )

        val output = gateway.execute(
            authorizedTutorRespond(
                gateway,
                tutorRespondInput().copy(explanationMode = TutorExplanationMode.DIRECT),
            ),
        ).toList().last()
            .let { it as ModelGatewayEvent.Completed }
            .output as TutorRespondOutput

        assertFalse(assetOpened)
        assertTrue(gateway.capabilities().supports(ModelTaskKind.TUTOR_RESPOND))
        assertTrue(sentBody.contains("这一步为什么要先判断导数符号？"))
        assertTrue(sentBody.contains("先找到导数的零点。"))
        assertTrue(sentBody.contains("CHANGE_REPRESENTATION"))
        assertTrue(sentBody.contains("只解决studentMessage表达的一个当前题目标"))
        assertTrue(sentBody.contains("不得擅自把所有消息都当作讲题要求"))
        assertTrue(sentBody.contains("模型只提出本地动作申请"))
        assertTrue(sentBody.contains("严禁生成新题、同类题、变式题、校准题"))
        assertTrue(sentBody.contains("explanationMode：DIRECT"))
        assertTrue(sentBody.contains("solutionRevealed是必填的JSON布尔值"))
        assertTrue(sentBody.contains("不得返回diagnosticQuestion、选择题"))
        assertTrue(sentBody.contains("visualRequest可省略"))
        assertTrue(sentBody.contains("本次不得返回visualScene"))
        assertFalse(sentBody.contains("visual_program"))
        assertTrue(sentBody.contains("不得返回ID或schemaVersion"))
        assertFalse(sentBody.contains(TUTOR_SESSION_ID))
        assertFalse(sentBody.contains("node-derivative"))
        assertFalse(sentBody.contains("data:image"))
        assertEquals(TUTOR_SESSION_ID, output.sessionId)
        assertEquals("question-confirmed", output.questionDocumentId)
        assertEquals(4, output.responseOrdinal)
        assertEquals(2, output.cycleOrdinal)
        assertEquals(3, output.turnOrdinal)
        assertTrue(output.solutionRevealed)
        assertEquals(TutorMessageIntent.CURRENT_QUESTION_HELP, output.intentDecision.intent)
        assertNull(output.visualScene)
        assertTrue(output.suggestedMoves.isEmpty())
    }

    @Test
    fun tutorResponseStreamsAllowlistedPreviewAndRequestsStreaming() = runBlocking {
        val message = "先判断导数符号。\n\n再写出单调区间。"
        val payload = tutorRespondPayload(
            messageMarkdown = message,
            solutionRevealed = true,
        )
        val transport = RecordingStreamingTransport(
            streamEvents = payload.chunked(13).map { fragment ->
                ModelHttpStreamEvent.Data(streamDelta(fragment))
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor response must not open images") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(
            authorizedTutorRespond(
                gateway,
                tutorRespondInput().copy(explanationMode = TutorExplanationMode.DIRECT),
            ),
        ).toList()

        val previews = events.filterIsInstance<ModelGatewayEvent.TutorPreview>()
        assertTrue(previews.isNotEmpty())
        assertEquals(message, previews.last().snapshot.visibleMarkdown)
        val completed = events.last() as ModelGatewayEvent.Completed
        assertEquals(message, (completed.output as TutorRespondOutput).messageMarkdown)
        assertTrue(transport.streamBodies.single().contains("\"stream\":true"))
        assertTrue(
            transport.streamBodies.single().contains(
                "intentDecision、responseIntent、solutionRevealed、messageMarkdown",
            ),
        )
        assertEquals(1, transport.authorizationChecks)
        assertEquals(0, transport.postBodies.size)
        assertFalse(gateway.capabilities().supportsStreaming)
    }

    @Test
    fun nonSseSuccessCompletesWithoutLegacyRetry() = runBlocking {
        val response = ModelHttpResponse(
            statusCode = 200,
            body = envelope(tutorRespondPayload(solutionRevealed = true)),
        )
        val transport = RecordingStreamingTransport(
            streamEvents = listOf(ModelHttpStreamEvent.Fallback(response)),
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor response must not open images") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val completed = gateway.execute(
            authorizedTutorRespond(
                gateway,
                tutorRespondInput().copy(explanationMode = TutorExplanationMode.DIRECT),
            ),
        ).toList().last()

        assertTrue(completed is ModelGatewayEvent.Completed)
        assertEquals(1, transport.authorizationChecks)
        assertEquals(0, transport.postBodies.size)
    }

    @Test
    fun unsupportedStreamRetriesLegacyOnceAfterReauthorization() = runBlocking {
        val transport = RecordingStreamingTransport(
            streamEvents = listOf(
                ModelHttpStreamEvent.Fallback(ModelHttpResponse(statusCode = 400, body = "")),
            ),
            postResponse = ModelHttpResponse(
                200,
                envelope(tutorRespondPayload(solutionRevealed = true)),
            ),
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor response must not open images") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val completed = gateway.execute(
            authorizedTutorRespond(
                gateway,
                tutorRespondInput().copy(explanationMode = TutorExplanationMode.DIRECT),
            ),
        ).toList().last()

        assertTrue(completed is ModelGatewayEvent.Completed)
        assertEquals(2, transport.authorizationChecks)
        assertEquals(1, transport.streamBodies.size)
        assertEquals(1, transport.postBodies.size)
        assertTrue(transport.streamBodies.single().contains("\"stream\":true"))
        assertFalse(transport.postBodies.single().contains("\"stream\":true"))
    }

    @Test
    fun rateLimitDuringStreamDoesNotRetryLegacyRequest() = runBlocking {
        val transport = RecordingStreamingTransport(
            streamEvents = listOf(
                ModelHttpStreamEvent.Fallback(ModelHttpResponse(statusCode = 429, body = "")),
            ),
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor response must not open images") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val failed = gateway.execute(authorizedTutorRespond(gateway)).toList().last()
            as ModelGatewayEvent.Failed

        assertEquals(ModelFailureCode.RATE_LIMITED, failed.failure.code)
        assertEquals(1, transport.authorizationChecks)
        assertEquals(0, transport.postBodies.size)
    }

    @Test
    fun tutorLobbyStreamsRootMessageThroughTheSamePreviewContract() = runBlocking {
        val message = "把题目发来，我会直接帮你梳理。"
        val payload = tutorLobbyPayload(message)
        val transport = RecordingStreamingTransport(
            streamEvents = payload.chunked(11).map { fragment ->
                ModelHttpStreamEvent.Data(streamDelta(fragment))
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Lobby must not read image assets") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedTutorLobby(gateway)).toList()

        val previews = events.filterIsInstance<ModelGatewayEvent.TutorPreview>()
        assertEquals(message, previews.last().snapshot.stableMarkdown)
        assertEquals("", previews.last().snapshot.provisionalMarkdown)
        val completed = events.last() as ModelGatewayEvent.Completed
        assertEquals(message, (completed.output as TutorLobbyOutput).messageMarkdown)
    }

    @Test
    fun tutorLobbySendsCurrentPolicyAsASeparateManifestAuthorizedBlock() = runBlocking {
        var sentBody = ""
        val input = tutorLobbyInput().copy(
            conversationId = "learner-db-answer-proof-must-stay-local",
            explanationMode = TutorExplanationMode.GUIDED,
            modeVersion = 7,
            choiceInteractionAuthorized = true,
            allowedVisualTargetIds = setOf("verified-node-a"),
            explicitVisualRequest = TutorLobbyVisualRequest(
                kind = TutorLobbyVisualKind.ANIMATION,
                focusMarkdown = "只看液面如何联动。",
            ),
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Lobby must not read image assets") },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(200, envelope(tutorLobbyPayload("先看液面之间的关系。")))
            },
            clock = { AUTHORIZATION_NOW },
        )

        gateway.execute(authorizedTutorLobby(gateway, input)).toList()

        val context = tutorLobbyContextFrom(sentBody)
        assertEquals(
            setOf("conversationData", "currentTutorInteractionPolicy"),
            context.keys,
        )
        val conversation = context.getValue("conversationData") as JsonObject
        assertEquals(setOf("studentMessage", "priorMessages"), conversation.keys)
        assertEquals(
            input.studentMessage,
            (conversation.getValue("studentMessage") as JsonPrimitive).content,
        )
        assertEquals(
            Json.parseToJsonElement(
                CurrentTutorInteractionPolicyWire.encode(input.toCurrentTutorInteractionPolicy()),
            ),
            context.getValue("currentTutorInteractionPolicy"),
        )
        assertFalse(conversation.containsKey("currentTutorInteractionPolicy"))
        assertFalse(sentBody.contains(input.conversationId))
        assertFalse(sentBody.contains("learnerId"))
        assertFalse(sentBody.contains("answerProof"))
    }

    @Test
    fun legacyLobbyManifestOmitsEveryCurrentPolicyValueFromThePrompt() = runBlocking {
        var sentBody = ""
        val input = tutorLobbyInput()
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Lobby must not read image assets") },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(200, envelope(tutorLobbyPayload("把当前卡点发来即可。")))
            },
            clock = { AUTHORIZATION_NOW },
        )

        gateway.execute(
            authorizedTutorLobby(
                gateway,
                input,
                manifestSchemaVersion =
                    ModelEgressManifest.TUTOR_INTERACTION_POLICY_SCHEMA_VERSION - 1,
            ),
        ).toList()

        val context = tutorLobbyContextFrom(sentBody)
        assertEquals(setOf("conversationData"), context.keys)
        assertFalse(context.containsKey("currentTutorInteractionPolicy"))
    }

    @Test
    fun tutorLobbyBindsGuidedDirectiveToThePersistedModeVersion() = runBlocking {
        val input = tutorLobbyInput().copy(
            explanationMode = TutorExplanationMode.GUIDED,
            modeVersion = 7,
        )
        val payload = tutorLobbyPayload(
            messageMarkdown = "先判断最关键的关系。",
            intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
            responseIntent = TutorResponseIntent.ASK,
            interactionDirective = buildJsonObject {
                put("kind", "FREE_RESPONSE")
                put("promptMarkdown", "哪个关系决定了下一步？")
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Lobby must not read image assets") },
            transport = modelTransport { _, _, _ -> ModelHttpResponse(200, envelope(payload)) },
            clock = { AUTHORIZATION_NOW },
        )

        val completed = gateway.execute(authorizedTutorLobby(gateway, input)).toList().last()
            as ModelGatewayEvent.Completed
        val output = completed.output as TutorLobbyOutput

        assertEquals(TutorExplanationMode.GUIDED, output.explanationMode)
        assertEquals(7L, output.modeVersion)
        assertTrue(output.interactionDirective is TutorInteractionDirective.FreeResponse)
    }

    @Test
    fun tutorLobbyGuidedStreamNeverPreviewsModelAuthoredAnswerBesideAnInteraction() = runBlocking {
        val input = tutorLobbyInput().copy(
            explanationMode = TutorExplanationMode.GUIDED,
            modeVersion = 7,
        )
        val payload = tutorLobbyPayload(
            messageMarkdown = "最终答案是 2，你先自己想。",
            intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
            responseIntent = TutorResponseIntent.ASK,
            interactionDirective = buildJsonObject {
                put("kind", "FREE_RESPONSE")
                put("promptMarkdown", "哪个关系决定了下一步？")
            },
        )
        val transport = RecordingStreamingTransport(
            streamEvents = payload.chunked(7).map { fragment ->
                ModelHttpStreamEvent.Data(streamDelta(fragment))
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Lobby must not read image assets") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedTutorLobby(gateway, input)).toList()

        val previews = events.filterIsInstance<ModelGatewayEvent.TutorPreview>()
        assertEquals(listOf(GUIDED_INTERACTION_MESSAGE), previews.map { it.snapshot.stableMarkdown })
        assertTrue(previews.none { it.snapshot.stableMarkdown.contains("最终答案") })
        val completed = events.last() as ModelGatewayEvent.Completed
        assertEquals(
            GUIDED_INTERACTION_MESSAGE,
            (completed.output as TutorLobbyOutput).messageMarkdown,
        )
    }

    @Test
    fun tutorLobbyDirectModeRejectsModelAuthoredInteraction() = runBlocking {
        val payload = tutorLobbyPayload(
            messageMarkdown = "先判断最关键的关系。",
            intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
            responseIntent = TutorResponseIntent.ASK,
            interactionDirective = buildJsonObject {
                put("kind", "FREE_RESPONSE")
                put("promptMarkdown", "哪个关系决定了下一步？")
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Lobby must not read image assets") },
            transport = modelTransport { _, _, _ -> ModelHttpResponse(200, envelope(payload)) },
            clock = { AUTHORIZATION_NOW },
        )

        val failed = gateway.execute(authorizedTutorLobby(gateway)).toList().last()
            as ModelGatewayEvent.Failed

        assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
    }

    @Test
    fun tutorLobbyDirectModeRejectsQuestionTextDisguisedAsExplanation() = runBlocking {
        val payload = tutorLobbyPayload(
            messageMarkdown = "你会先判断哪个量？",
            intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
            responseIntent = TutorResponseIntent.EXPLAIN,
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Lobby must not read image assets") },
            transport = modelTransport { _, _, _ -> ModelHttpResponse(200, envelope(payload)) },
            clock = { AUTHORIZATION_NOW },
        )

        val failed = gateway.execute(authorizedTutorLobby(gateway)).toList().last()
            as ModelGatewayEvent.Failed

        assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
    }

    @Test
    fun tutorLobbyRejectedStreamDoesNotPreviewUnverifiedText() = runBlocking {
        val payload = tutorLobbyPayload(
            messageMarkdown = "你会先判断哪个量？",
            intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
            responseIntent = TutorResponseIntent.EXPLAIN,
        )
        val transport = RecordingStreamingTransport(
            streamEvents = payload.chunked(5).map { fragment ->
                ModelHttpStreamEvent.Data(streamDelta(fragment))
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Lobby must not read image assets") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedTutorLobby(gateway)).toList()

        assertTrue(events.none { it is ModelGatewayEvent.TutorPreview })
        val failed = events.last() as ModelGatewayEvent.Failed
        assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
    }

    @Test
    fun tutorLobbyDirectStreamRejectsUnpunctuatedInteractionWithZeroPreview() = runBlocking {
        listOf(
            "你先判断哪个量更关键。",
            "请回答这一步如何处理。",
            "选择你认为正确的关系。",
            "点击图中的关键位置。",
        ).forEach { disguisedInteraction ->
            val payload = tutorLobbyPayload(
                messageMarkdown = disguisedInteraction,
                intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
                responseIntent = TutorResponseIntent.EXPLAIN,
            )
            val transport = RecordingStreamingTransport(
                streamEvents = payload.chunked(5).map { fragment ->
                    ModelHttpStreamEvent.Data(streamDelta(fragment))
                },
            )
            val gateway = OpenAiCompatibleModelGateway(
                configurationStore = FakeConfigurationStore(CONFIGURATION),
                assetSource = assetSource { _, _ -> error("Lobby must not read image assets") },
                transport = transport,
                clock = { AUTHORIZATION_NOW },
            )

            val events = gateway.execute(authorizedTutorLobby(gateway)).toList()

            assertTrue(
                disguisedInteraction,
                events.none { event -> event is ModelGatewayEvent.TutorPreview },
            )
            val failed = events.last() as ModelGatewayEvent.Failed
            assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
        }
    }

    @Test
    fun tutorLobbyDirectStreamAcceptsTeachingStepsThatDoNotRequestAResponse() = runBlocking {
        val message = "先求导，再判断符号。判断依据是导数在区间内的符号。"
        val payload = tutorLobbyPayload(
            messageMarkdown = message,
            intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
            responseIntent = TutorResponseIntent.EXPLAIN,
        )
        val transport = RecordingStreamingTransport(
            streamEvents = payload.chunked(5).map { fragment ->
                ModelHttpStreamEvent.Data(streamDelta(fragment))
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Lobby must not read image assets") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedTutorLobby(gateway)).toList()

        assertEquals(
            listOf(message),
            events.filterIsInstance<ModelGatewayEvent.TutorPreview>()
                .map { preview -> preview.snapshot.stableMarkdown },
        )
        val completed = events.last() as ModelGatewayEvent.Completed
        assertEquals(message, (completed.output as TutorLobbyOutput).messageMarkdown)
    }

    @Test
    fun providerDeclarationsCannotWidenGuidedPreviewAuthority() = runBlocking {
        val payload = tutorRespondPayload(
            messageMarkdown = "最终答案是 2。",
            solutionRevealed = true,
        )
        val transport = RecordingStreamingTransport(
            streamEvents = payload.chunked(9).map { fragment ->
                ModelHttpStreamEvent.Data(streamDelta(fragment))
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor response must not open images") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedTutorRespond(gateway)).toList()

        assertTrue(events.none { it is ModelGatewayEvent.TutorPreview })
        val failed = events.last() as ModelGatewayEvent.Failed
        assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
        assertEquals(0, transport.postBodies.size)
    }

    @Test
    fun falseProviderDeclarationCannotExposeAnUnauthorizedGuidedBody() = runBlocking {
        val input = tutorRespondInput().copy(explanationMode = TutorExplanationMode.GUIDED)
        val payload = tutorRespondPayload(
            messageMarkdown = "最终答案是 2。完整解法如下。",
            solutionRevealed = false,
        )
        val transport = RecordingStreamingTransport(
            streamEvents = payload.chunked(7).map { fragment ->
                ModelHttpStreamEvent.Data(streamDelta(fragment))
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor response must not open images") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedTutorRespond(gateway, input)).toList()

        assertTrue(events.none { it is ModelGatewayEvent.TutorPreview })
        val output = (events.last() as ModelGatewayEvent.Completed).output as TutorRespondOutput
        assertTrue(output.solutionRevealed)
        assertNull(output.interactionDirective)
        assertFalse(output.canExposeSolutionFor(input))
    }

    @Test
    fun guidedDirectiveCannotHideAnAnswerInsideModelAuthoredText() = runBlocking {
        val input = tutorRespondInput().copy(explanationMode = TutorExplanationMode.GUIDED)
        val directive = buildJsonObject {
            put("kind", "FREE_RESPONSE")
            put("promptMarkdown", "答案是 2，请照抄。")
        }
        val payload = tutorRespondPayload(
            messageMarkdown = "完整解法是先求导再令导数为零。",
            solutionRevealed = false,
            responseIntent = TutorResponseIntent.ASK,
            extraTopLevel = "interactionDirective" to directive,
        )
        val transport = RecordingStreamingTransport(
            streamEvents = payload.chunked(7).map { fragment ->
                ModelHttpStreamEvent.Data(streamDelta(fragment))
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor response must not open images") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedTutorRespond(gateway, input)).toList()

        assertTrue(events.none { it is ModelGatewayEvent.TutorPreview })
        val output = (events.last() as ModelGatewayEvent.Completed).output as TutorRespondOutput
        assertEquals(GUIDED_INTERACTION_MESSAGE, output.messageMarkdown)
        assertFalse(output.solutionRevealed)
        assertFalse(output.canExposeSolutionFor(input))
        assertEquals(
            GUIDED_FREE_RESPONSE_PROMPT,
            (output.interactionDirective as TutorInteractionDirective.FreeResponse).promptMarkdown,
        )
    }

    @Test
    fun tutorResponseDecodesExplicitFreeResponseEvaluationAndDefaultsLegacyOutputToUnknown() =
        runBlocking {
            val continueDirective = buildJsonObject {
                put("kind", "CONTINUE")
            }
            val incorrect = parsedTutorResponse(
                tutorRespondPayload(
                    messageMarkdown = "这一步还不对，请回到符号判断。",
                    solutionRevealed = false,
                    responseIntent = TutorResponseIntent.ASK,
                    freeResponseEvaluation = TutorFreeResponseEvaluation.INCORRECT,
                    extraTopLevel = "interactionDirective" to continueDirective,
                ),
                explanationMode = TutorExplanationMode.GUIDED,
            )
            val legacy = parsedTutorResponse(
                tutorRespondPayload(
                    messageMarkdown = "继续看当前题。",
                    solutionRevealed = false,
                    responseIntent = TutorResponseIntent.ASK,
                    extraTopLevel = "interactionDirective" to continueDirective,
                ),
                explanationMode = TutorExplanationMode.GUIDED,
            )

            assertEquals(TutorFreeResponseEvaluation.INCORRECT, incorrect.freeResponseEvaluation)
            assertEquals(TutorFreeResponseEvaluation.UNKNOWN, legacy.freeResponseEvaluation)
        }

    @Test
    fun messageFirstDirectResponseCompletesWithoutPreview() = runBlocking {
        val input = tutorRespondInput().copy(explanationMode = TutorExplanationMode.DIRECT)
        val message = "先求导并判断符号，完整答案是函数先增后减。"
        val payload = Json.encodeToString(
            buildJsonObject {
                put("messageMarkdown", message)
                put("responseIntent", TutorResponseIntent.EXPLAIN.name)
                put("solutionRevealed", true)
                put("intentDecision", tutorIntentPayload())
            },
        )
        val transport = RecordingStreamingTransport(
            streamEvents = payload.chunked(5).map { fragment ->
                ModelHttpStreamEvent.Data(streamDelta(fragment))
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor response must not open images") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedTutorRespond(gateway, input)).toList()

        assertTrue(events.none { it is ModelGatewayEvent.TutorPreview })
        val completed = events.last() as ModelGatewayEvent.Completed
        assertEquals(message, (completed.output as TutorRespondOutput).messageMarkdown)
    }

    @Test
    fun falseProviderDeclarationCannotNarrowDirectPreviewAuthority() = runBlocking {
        val input = tutorRespondInput().copy(explanationMode = TutorExplanationMode.DIRECT)
        val payload = tutorRespondPayload(
            messageMarkdown = "最终答案是 2。",
            solutionRevealed = false,
        )
        val transport = RecordingStreamingTransport(
            streamEvents = payload.chunked(9).map { fragment ->
                ModelHttpStreamEvent.Data(streamDelta(fragment))
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor response must not open images") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedTutorRespond(gateway, input)).toList()

        assertTrue(events.any { it is ModelGatewayEvent.TutorPreview })
        val failed = events.last() as ModelGatewayEvent.Failed
        assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
        assertTrue(failed.failure.retryable)
    }

    @Test
    fun directModeStreamsAVisibleBlockBeforeCompletion() = runBlocking {
        val input = tutorRespondInput().copy(explanationMode = TutorExplanationMode.DIRECT)
        val message = "先求导并判断符号。\n\n最终答案是先增后减。"
        val payload = tutorRespondPayload(
            messageMarkdown = message,
            solutionRevealed = true,
        )
        val transport = RecordingStreamingTransport(
            streamEvents = payload.chunked(9).map { fragment ->
                ModelHttpStreamEvent.Data(streamDelta(fragment))
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor response must not open images") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedTutorRespond(gateway, input)).toList()

        val previewIndex = events.indexOfFirst { event ->
            event is ModelGatewayEvent.TutorPreview &&
                event.snapshot.visibleMarkdown.contains("先求导并判断符号。")
        }
        assertTrue(previewIndex in 0 until events.lastIndex)
        val completed = events.last() as ModelGatewayEvent.Completed
        assertEquals(message, (completed.output as TutorRespondOutput).messageMarkdown)
    }

    @Test
    fun invalidDirectTerminalKeepsTheLastSafePreviewBeforeFailure() = runBlocking {
        val input = tutorRespondInput().copy(explanationMode = TutorExplanationMode.DIRECT)
        val safeBlock = "先求导并判断符号。\n\n"
        val payload = tutorRespondPayload(
            messageMarkdown = safeBlock + "再写出单调区间。",
            solutionRevealed = true,
            extraTopLevel = "internalTrace" to JsonPrimitive("must be rejected"),
        )
        val transport = RecordingStreamingTransport(
            streamEvents = payload.chunked(9).map { fragment ->
                ModelHttpStreamEvent.Data(streamDelta(fragment))
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor response must not open images") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedTutorRespond(gateway, input)).toList()

        val lastPreview = events.filterIsInstance<ModelGatewayEvent.TutorPreview>().last()
        assertTrue(lastPreview.snapshot.visibleMarkdown.contains("先求导并判断符号。"))
        val failed = events.last() as ModelGatewayEvent.Failed
        assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
        assertTrue(failed.failure.retryable)
    }

    @Test
    fun firstSafePreviewAtEofStillMakesAnInvalidTerminalRetryable() = runBlocking {
        val input = tutorRespondInput().copy(explanationMode = TutorExplanationMode.DIRECT)
        val payload = tutorRespondPayload(
            messageMarkdown = "```kotlin\nunfinished",
            solutionRevealed = true,
        )
        val transport = RecordingStreamingTransport(
            streamEvents = payload.chunked(7).map { fragment ->
                ModelHttpStreamEvent.Data(streamDelta(fragment))
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor response must not open images") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedTutorRespond(gateway, input)).toList()

        assertEquals(1, events.filterIsInstance<ModelGatewayEvent.TutorPreview>().size)
        val failed = events.last() as ModelGatewayEvent.Failed
        assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
        assertTrue(failed.failure.retryable)
    }

    @Test
    fun streamedSafeLiteralPreviewCanCompleteWhileRawOutputRemainsValidated() = runBlocking {
        val message = "参考 ![图](https://example.test/a.png)\n\n"
        val payload = tutorLobbyPayload(message)
        val transport = RecordingStreamingTransport(
            streamEvents = payload.chunked(9).map { fragment ->
                ModelHttpStreamEvent.Data(streamDelta(fragment))
            },
        )
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Lobby must not read image assets") },
            transport = transport,
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedTutorLobby(gateway)).toList()

        val preview = events.filterIsInstance<ModelGatewayEvent.TutorPreview>().last().snapshot
        assertTrue(preview.visibleMarkdown.contains("图"))
        assertFalse(preview.visibleMarkdown.contains("![图](https://"))
        val completed = events.last() as ModelGatewayEvent.Completed
        assertEquals(message, (completed.output as TutorLobbyOutput).messageMarkdown)
    }

    @Test
    fun guidedTutorResponsePreservesTheModelSelectedBoundedDirective() = runBlocking {
        var sentBody = ""
        val directive = buildJsonObject {
            put("kind", "FREE_RESPONSE")
            put("promptMarkdown", "导数符号怎样决定单调性？")
        }
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor response must not open images") },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(
                    200,
                    envelope(
                        tutorRespondPayload(
                            responseIntent = TutorResponseIntent.ASK,
                            extraTopLevel = "interactionDirective" to directive,
                        ),
                    ),
                )
            },
            clock = { AUTHORIZATION_NOW },
        )

        val output = gateway.execute(
            authorizedTutorRespond(
                gateway,
                tutorRespondInput().copy(explanationMode = TutorExplanationMode.GUIDED),
            ),
        ).toList().last().let { it as ModelGatewayEvent.Completed }.output as TutorRespondOutput

        assertTrue(sentBody.contains("explanationMode：GUIDED"))
        assertTrue(sentBody.contains("interactionDirective"))
        val modelDirective = output.interactionDirective as TutorInteractionDirective.FreeResponse
        assertEquals(GUIDED_INTERACTION_MESSAGE, output.messageMarkdown)
        assertEquals(GUIDED_FREE_RESPONSE_PROMPT, modelDirective.promptMarkdown)
    }

    @Test
    fun guidedTutorResponseAcceptsAModelSelectedImperativeFreeResponse() = runBlocking {
        listOf("请写下下一步判断。", "选择 B。", "写下 x=2。").forEach { prompt ->
            val modelDirective = buildJsonObject {
                put("kind", "FREE_RESPONSE")
                put("promptMarkdown", prompt)
            }
            val completed = executeTutorRespondPayload(
                payload = tutorRespondPayload(
                    responseIntent = TutorResponseIntent.ASK,
                    extraTopLevel = "interactionDirective" to modelDirective,
                ),
                input = tutorRespondInput().copy(explanationMode = TutorExplanationMode.GUIDED),
            ).last() as ModelGatewayEvent.Completed
            val output = completed.output as TutorRespondOutput
            assertEquals(GUIDED_INTERACTION_MESSAGE, output.messageMarkdown)
            assertFalse(output.solutionRevealed)
            assertEquals(
                GUIDED_FREE_RESPONSE_PROMPT,
                (output.interactionDirective as TutorInteractionDirective.FreeResponse)
                    .promptMarkdown,
            )
        }
    }

    @Test
    fun guidedTutorResponseCanExplainWithoutSelectingAnInteraction() = runBlocking {
        val message = "先比较导数在区间两侧的符号，再看函数值的变化方向。"
        val completed = executeTutorRespondPayload(
            payload = tutorRespondPayload(
                messageMarkdown = message,
                solutionRevealed = false,
            ),
            input = tutorRespondInput().copy(explanationMode = TutorExplanationMode.GUIDED),
        ).last() as ModelGatewayEvent.Completed

        val output = completed.output as TutorRespondOutput
        assertEquals(message, output.messageMarkdown)
        assertNull(output.interactionDirective)
    }

    @Test
    fun directTutorResponseUsesStructuredIntentInsteadOfQuestionPunctuation() = runBlocking {
        val completed = executeTutorRespondPayload(
            payload = tutorRespondPayload(
                messageMarkdown = "你明白吗？接着根据导数符号写出全部单调区间。",
                solutionRevealed = true,
                responseIntent = TutorResponseIntent.EXPLAIN,
            ),
            input = tutorRespondInput().copy(explanationMode = TutorExplanationMode.DIRECT),
        ).last() as ModelGatewayEvent.Completed

        assertEquals(
            TutorResponseIntent.EXPLAIN,
            (completed.output as TutorRespondOutput).responseIntent,
        )
    }

    @Test
    fun guidedExplanationOnlyResponseAlsoUsesStructuredIntent() = runBlocking {
        val completed = executeTutorRespondPayload(
            payload = tutorRespondPayload(
                messageMarkdown = "先比较导数符号？然后说明函数的变化。",
                solutionRevealed = false,
                responseIntent = TutorResponseIntent.EXPLAIN,
            ),
            input = tutorRespondInput().copy(explanationMode = TutorExplanationMode.GUIDED),
        ).last() as ModelGatewayEvent.Completed

        assertNull((completed.output as TutorRespondOutput).interactionDirective)
    }

    @Test
    fun directTutorResponseCannotOverrideANonLearningIntent() = runBlocking {
        val failed = executeTutorRespondPayload(
            payload = tutorRespondPayload(
                messageMarkdown = "完整解法是先求导，再根据导数符号写出全部单调区间。",
                solutionRevealed = true,
                intentDecision = tutorIntentPayload(intent = TutorMessageIntent.CASUAL_CONVERSATION),
            ),
            input = tutorRespondInput().copy(explanationMode = TutorExplanationMode.DIRECT),
        ).last() as ModelGatewayEvent.Failed

        assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
    }

    @Test
    fun explicitRevealRejectsAModelAttemptToReturnOnlyAPauseReply() = runBlocking {
        val failed = executeTutorRespondPayload(
            payload = tutorRespondPayload(
                messageMarkdown = "好的，我们先暂停。",
                solutionRevealed = false,
                intentDecision = tutorIntentPayload(intent = TutorMessageIntent.END_OR_PAUSE),
            ),
            input = tutorRespondInput().copy(
                explanationMode = TutorExplanationMode.GUIDED,
                requestedMove = TutorMoveType.REVEAL_SOLUTION,
            ),
        ).last() as ModelGatewayEvent.Failed

        assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
    }

    @Test
    fun nonLearningTutorIntentCannotSmuggleDeterministicTeachingContent() = runBlocking {
        val failed = executeTutorRespondPayload(
            payload = tutorRespondPayload(
                messageMarkdown = "由已知条件推出 x=2。",
                solutionRevealed = false,
                intentDecision = tutorIntentPayload(intent = TutorMessageIntent.END_OR_PAUSE),
            ),
            input = tutorRespondInput().copy(explanationMode = TutorExplanationMode.GUIDED),
        ).last() as ModelGatewayEvent.Failed

        assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
    }

    @Test
    fun tutorPlanParserAcceptsOneBoundedDirectiveWithoutALegacyDiagnostic() = runBlocking {
        val directive = buildJsonObject {
            put("kind", "FREE_RESPONSE")
            put("promptMarkdown", "先说说导数符号怎样决定单调性。")
        }

        val completed = executeTutorPayload(
            tutorPayload(
                includeDiagnostic = false,
                responseIntent = TutorResponseIntent.ASK,
                extraTopLevel = "interactionDirective" to directive,
            ),
        ).last() as ModelGatewayEvent.Completed
        val output = completed.output as TutorPlanOutput

        assertTrue(output.plan.interactionDirective is TutorInteractionDirective.FreeResponse)
        assertNull(output.plan.diagnosticItem)
    }

    @Test
    fun directTutorResponseRequiresACompleteAnswerAndRejectsInteractionDirectives() = runBlocking {
        var sentBody = ""
        val directInput = tutorRespondInput().copy(explanationMode = TutorExplanationMode.DIRECT)
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor response must not open images") },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(
                    200,
                    envelope(
                        tutorRespondPayload(
                            messageMarkdown = "完整解法是先求导，再由符号写出全部单调区间。",
                            solutionRevealed = true,
                        ),
                    ),
                )
            },
            clock = { AUTHORIZATION_NOW },
        )

        val completed = gateway.execute(authorizedTutorRespond(gateway, directInput)).toList().last()
            as ModelGatewayEvent.Completed
        assertTrue(sentBody.contains("explanationMode：DIRECT"))
        assertTrue(sentBody.contains("不得返回interactionDirective"))
        assertTrue((completed.output as TutorRespondOutput).solutionRevealed)

        val directive = buildJsonObject {
            put("kind", "FREE_RESPONSE")
            put("promptMarkdown", "你准备先做哪一步？")
        }
        val failed = executeTutorRespondPayload(
            payload = tutorRespondPayload(
                solutionRevealed = true,
                responseIntent = TutorResponseIntent.ASK,
                extraTopLevel = "interactionDirective" to directive,
            ),
            input = directInput,
        ).last() as ModelGatewayEvent.Failed
        assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
    }

    @Test
    fun tutorVisualGenerationReadsOnlyGrantedImagesAndParsesTheV2Document() = runBlocking {
        var openedAssetId: String? = null
        var sentBody = ""
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, assetId ->
                openedAssetId = assetId
                asset()
            },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(
                    200,
                    envelope(
                        Json.encodeToString(
                            buildJsonObject {
                                put("decision", TutorVisualGenerationDecision.GENERATED.name)
                                put("confidence", 0.96)
                                put(
                                    "scene",
                                    visualDocumentPayload(
                                        variables = buildJsonArray { add(visualGivenVariablePayload()) },
                                        nodeValueVariableId = "distance",
                                    ),
                                )
                            },
                        ),
                    ),
                )
            },
            clock = { AUTHORIZATION_NOW },
        )

        val output = gateway.execute(authorizedTutorVisualGenerate(gateway)).toList().last()
            .let { it as ModelGatewayEvent.Completed }
            .output as TutorVisualGenerateOutput

        assertEquals(ASSET_ID, openedAssetId)
        assertTrue(sentBody.contains("为一条已经先展示文字的当前题讲解生成可交互图形"))
        assertTrue(sentBody.contains("visualDocument固定为"))
        assertTrue(sentBody.contains("聚焦函数图象中的增减关系"))
        assertTrue(sentBody.contains("本地题面数值目录"))
        assertTrue(sentBody.contains("visual-fact-distance"))
        assertTrue(sentBody.contains("given_source_fact"))
        assertTrue(sentBody.contains("不得返回sceneId、schemaVersion或provenanceSchemaVersion"))
        assertFalse(sentBody.contains(TUTOR_SESSION_ID))
        assertEquals(TutorVisualGenerationDecision.GENERATED, output.decision)
        assertEquals("关系图", requireNotNull(output.scene).title)
        assertTrue(
            requireNotNull(output.scene).variables.single().proof is TutorVisualValueProof.Given,
        )
        assertTrue(requireNotNull(output.scene).sceneId.startsWith("tutor-visual-"))
        assertEquals(
            TutorVisualDocumentScene.CURRENT_PROVENANCE_SCHEMA_VERSION,
            requireNotNull(output.scene).provenanceSchemaVersion,
        )
    }

    @Test
    fun tutorVisualGenerationRejectsUnknownFieldsAndVisibleIllustrativeValues() = runBlocking {
        val unknownFieldScene = visualDocumentPayload(
            extra = "imageUrl" to JsonPrimitive("asset.png"),
        )
        val modelControlledProvenanceScene = visualDocumentPayload(
            extra = "provenanceSchemaVersion" to JsonPrimitive(
                TutorVisualDocumentScene.CURRENT_PROVENANCE_SCHEMA_VERSION,
            ),
        )
        val illustrativeScene = visualDocumentPayload(
            variables = buildJsonArray {
                add(
                    buildJsonObject {
                        put("variableId", "animation-only")
                        put("label", "速度")
                        put("value", 2.0)
                        put("dimension", "SPEED")
                        put("source", "ILLUSTRATIVE")
                        put("display", false)
                    },
                )
            },
            nodeValueVariableId = "animation-only",
        )

        listOf(unknownFieldScene, modelControlledProvenanceScene, illustrativeScene).forEach { scene ->
            val gateway = OpenAiCompatibleModelGateway(
                configurationStore = FakeConfigurationStore(CONFIGURATION),
                assetSource = assetSource { _, _ -> asset() },
                transport = modelTransport { _, _, _ ->
                    ModelHttpResponse(
                        200,
                        envelope(
                            Json.encodeToString(
                                buildJsonObject {
                                    put("decision", TutorVisualGenerationDecision.GENERATED.name)
                                    put("confidence", 0.96)
                                    put("scene", scene)
                                },
                            ),
                        ),
                    )
                },
                clock = { AUTHORIZATION_NOW },
            )

            val failed = gateway.execute(authorizedTutorVisualGenerate(gateway)).toList().last()
                as ModelGatewayEvent.Failed

            assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
            assertFalse(failed.failure.retryable)
        }
    }

    @Test
    fun tutorVisualReviewCanApproveWithoutRepeatingTheCandidate() = runBlocking {
        var sentBody = ""
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> asset() },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(
                    200,
                    envelope(
                        Json.encodeToString(
                            buildJsonObject {
                                put("decision", TutorVisualReviewDecision.APPROVED.name)
                                put("confidence", 0.98)
                            },
                        ),
                    ),
                )
            },
            clock = { AUTHORIZATION_NOW },
        )

        val output = gateway.execute(authorizedTutorVisualReview(gateway)).toList().last()
            .let { it as ModelGatewayEvent.Completed }
            .output as TutorVisualReviewOutput

        assertTrue(sentBody.contains("唯一一次修复机会"))
        assertTrue(sentBody.contains("candidateScene"))
        assertTrue(sentBody.contains("visual-fact-distance"))
        assertEquals(TutorVisualReviewDecision.APPROVED, output.decision)
        assertNull(output.scene)
    }

    @Test
    fun tutorLobbySendsOnlyTheMessageContextAndParsesBoundedIntent() = runBlocking {
        var sentBody = ""
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Lobby must not read image assets") },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(
                    200,
                    envelope(
                        Json.encodeToString(
                            buildJsonObject {
                                put(
                                    "intentDecision",
                                    tutorIntentPayload(
                                        intent = TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
                                        confidence = 0.94,
                                        explicitActionRequest = true,
                                        capability =
                                            TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
                                        lookupTerms = listOf("函数"),
                                    ),
                                )
                                put("messageMarkdown", "我会先让本机查找和函数有关的错题。")
                            },
                        ),
                    ),
                )
            },
            clock = { AUTHORIZATION_NOW },
        )

        val completed = gateway.execute(authorizedTutorLobby(gateway)).toList().last()
            as ModelGatewayEvent.Completed
        val output = completed.output as TutorLobbyOutput

        assertTrue(sentBody.contains("帮我找函数错题"))
        assertTrue(sentBody.contains("你好，你现在想做什么？"))
        assertTrue(sentBody.contains("模型无权保存、删除、修改错题或学习记录"))
        assertFalse(sentBody.contains("confirmedQuestion"))
        assertFalse(sentBody.contains("relevantLearningEvidence"))
        assertEquals(
            TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
            output.intentDecision.requestedLocalCapability,
        )
        assertEquals("我会先让本机查找和函数有关的错题。", output.messageMarkdown)
    }

    @Test
    fun tutorResponseParsesIntentWithoutGrantingDatabaseAuthority() = runBlocking {
        val events = executeTutorRespondPayload(
            tutorRespondPayload(
                messageMarkdown = "我会把请求交给本机处理，读取前不会改动任何学习记录。",
                intentDecision = tutorIntentPayload(
                    intent = TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
                    confidence = 0.91,
                    explicitActionRequest = true,
                    capability = TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
                    lookupTerms = listOf("函数", "单调性"),
                ),
            ),
        )

        val completed = events.last() as ModelGatewayEvent.Completed
        val output = completed.output as TutorRespondOutput
        assertEquals(TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP, output.intentDecision.intent)
        assertEquals(
            TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
            output.intentDecision.requestedLocalCapability,
        )
        assertFalse(output.solutionRevealed)
        assertNull(output.interactionDirective)
        assertNull(output.visualScene)
        assertTrue(output.suggestedMoves.isEmpty())
    }

    @Test
    fun missingIntentFailsClosedAsAmbiguousInsteadOfAssumingCurrentQuestionHelp() = runBlocking {
        val events = executeTutorRespondPayload(
            tutorRespondPayload(intentDecision = null),
        )

        val completed = events.last() as ModelGatewayEvent.Completed
        val output = completed.output as TutorRespondOutput
        assertEquals(TutorMessageIntent.AMBIGUOUS, output.intentDecision.intent)
        assertEquals(
            TutorRequestedLocalCapability.NONE,
            output.intentDecision.requestedLocalCapability,
        )
        assertFalse(output.solutionRevealed)
        assertNull(output.interactionDirective)
        assertNull(output.visualScene)
        assertTrue(output.suggestedMoves.isEmpty())
    }

    @Test
    fun tutorResponseRejectsIntentCapabilityMismatch() = runBlocking {
        val payload = tutorRespondPayload(
            intentDecision = tutorIntentPayload(
                intent = TutorMessageIntent.CASUAL_CONVERSATION,
                confidence = 0.96,
                explicitActionRequest = true,
                capability = TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
            ),
        )

        val failed = executeTutorRespondPayload(payload).last() as ModelGatewayEvent.Failed
        assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
    }

    @Test
    fun tutorResponseRequiresAStrictSolutionRevealBoolean() = runBlocking {
        assertTrue(parsedTutorResponse(tutorRespondPayload(solutionRevealed = true)).solutionRevealed)

        val missingDeclaration = Json.encodeToString(
            buildJsonObject { put("messageMarkdown", "这是当前题的完整答案。") },
        )
        val quotedDeclaration = tutorRespondPayload(
            extraTopLevel = "solutionRevealed" to JsonPrimitive("true"),
        )
        listOf(missingDeclaration, quotedDeclaration).forEach { payload ->
            val failed = executeTutorRespondPayload(payload).last() as ModelGatewayEvent.Failed
            assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
            assertFalse(failed.failure.retryable)
        }
    }

    @Test
    fun tutorResponseRejectsInlineLegacySceneAndKeepsMoveIdsLocal() = runBlocking {
        val rejected = executeTutorRespondPayload(
            tutorRespondPayload(
                solutionRevealed = true,
                visualScene = stepFlowScenePayload(),
            ),
        ).last() as ModelGatewayEvent.Failed
        assertEquals(ModelFailureCode.INVALID_RESPONSE, rejected.failure.code)
        assertFalse(rejected.failure.retryable)

        val payloadWithoutScene = tutorRespondPayload(
            solutionRevealed = true,
            nextMoves = buildJsonArray {
                add(buildJsonObject {
                    put("label", "换成符号表表示")
                    put("type", "CHANGE_REPRESENTATION")
                })
            },
        )
        val first = parsedTutorResponse(payloadWithoutScene)
        val second = parsedTutorResponse(payloadWithoutScene)
        val suffix = stableTutorRespondSuffix(tutorRespondInput())

        assertEquals(first, second)
        assertNull(first.visualScene)
        assertEquals(
            listOf("tutor-respond-move-$suffix-1"),
            first.suggestedMoves.map { it.id },
        )
    }

    @Test
    fun tutorResponseRejectsUnknownOrActiveOutputFields() = runBlocking {
        val invalidPayloads = listOf(
            tutorRespondPayload(
                visualScene = buildJsonObject {
                    put("kind", "spatial_canvas")
                    put("title", "任意画布")
                },
            ),
            tutorRespondPayload(extraTopLevel = "unexpected" to JsonPrimitive("value")),
            tutorRespondPayload(extraTopLevel = "imageUrl" to JsonPrimitive("asset.png")),
            tutorRespondPayload(
                nextMoves = buildJsonArray {
                    add(buildJsonObject {
                        put("label", "执行任意动作")
                        put("type", "DEEPEN_REASONING")
                        put("action", "run")
                    })
                },
            ),
            tutorRespondPayload(messageMarkdown = "`println(1)`"),
            tutorRespondPayload(messageMarkdown = "<div>答案</div>"),
            tutorRespondPayload(messageMarkdown = "答案见 https://example.com"),
        )

        invalidPayloads.forEach { payload ->
            val failed = executeTutorRespondPayload(payload).last() as ModelGatewayEvent.Failed
            assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure.code)
            assertFalse(failed.failure.retryable)
        }
    }

    @Test
    fun tutorFollowUpUsesPersistedChoiceAndRequestedTeachingMove() = runBlocking {
        var sentBody = ""
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor must not open image assets") },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(200, envelope(tutorPayload()))
            },
            clock = { AUTHORIZATION_NOW },
        )
        val history = TutorTurnHistoryEntry(
            turnOrdinal = 1,
            diagnosticStemMarkdown = "导数先正后负时，原函数怎样变化？",
            selectedChoiceMarkdown = "先减后增",
            selectionWasCorrect = false,
            feedbackMarkdown = "你把导数正负与增减的对应关系反过来了。",
            requestedMove = TutorMoveType.CHANGE_REPRESENTATION,
        )

        val events = gateway.execute(
            authorizedTutor(
                gateway,
                tutorInput().copy(turnOrdinal = 2, priorTurns = listOf(history)),
            ),
        ).toList()
        val output = (events.last() as ModelGatewayEvent.Completed).output as TutorPlanOutput

        assertTrue(sentBody.contains("第1轮第2步讲解"))
        assertTrue(sentBody.contains("先减后增"))
        assertTrue(sentBody.contains("CHANGE_REPRESENTATION"))
        assertEquals(2, output.turnOrdinal)
    }

    @Test
    fun laterTutorCycleReceivesExactStudentWordsWithoutPermissionToProbe() = runBlocking {
        var sentBody = ""
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor must not open image assets") },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(200, envelope(tutorPayload()))
            },
            clock = { AUTHORIZATION_NOW },
        )
        val exactMessages = listOf(
            "  我卡在配方法第二步\n",
            "为什么这里要同时加上 4？  ",
        )
        val input = tutorInput().copy(
            cycleOrdinal = 2,
            priorConversationMemory = TutorConversationMemory(
                completedCycleCount = 1,
                answeredTurnCount = 0,
                correctChoiceCount = 0,
                solutionWasRevealed = true,
            ),
            priorCycleStudentMessages = exactMessages,
        )

        val events = gateway.execute(authorizedTutor(gateway, input)).toList()
        val output = (events.last() as ModelGatewayEvent.Completed).output as TutorPlanOutput

        assertTrue(sentBody.contains("priorCycleStudentMessages"))
        assertTrue(sentBody.contains("我卡在配方法第二步"))
        assertTrue(sentBody.indexOf("我卡在配方法第二步") < sentBody.indexOf("为什么这里要同时加上 4"))
        assertTrue(sentBody.contains("不得据此额外出题、诊断、校准或探测能力"))
        assertTrue(sentBody.contains("不得用conversationMemory覆盖、否定或改写这些原话"))
        assertEquals(2, output.cycleOrdinal)
        assertEquals(1, output.turnOrdinal)
    }

    @Test
    fun organizationUsesAliasesAndReconstructsRelationTargetsLocally() = runBlocking {
        var assetOpened = false
        var sentBody = ""
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ ->
                assetOpened = true
                asset()
            },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(200, envelope(organizationPayload()))
            },
            clock = { AUTHORIZATION_NOW },
        )

        val events = gateway.execute(authorizedOrganization(gateway)).toList()
        val output = (events.last() as ModelGatewayEvent.Completed).output
            as ProblemOrganizationOutput

        assertFalse(assetOpened)
        assertTrue(sentBody.contains("candidate-1"))
        assertTrue(sentBody.contains("导数变式"))
        assertFalse(sentBody.contains(ORGANIZATION_PROBLEM_ID))
        assertFalse(sentBody.contains(RELATED_PROBLEM_ID))
        assertFalse(sentBody.contains("node-derivative"))
        assertFalse(sentBody.contains("data:image"))
        assertEquals(ORGANIZATION_PROBLEM_ID, output.problemId)
        assertEquals(RELATED_PROBLEM_ID, output.plan.relations.single().targetProblemId)
        assertEquals("revision-related", output.plan.relations.single().targetProblemRevisionId)
    }

    @Test
    fun organizationV3ReadsOnlyItsExactGrantAndReconstructsEvidenceAliases() = runBlocking {
        val openedAssets = mutableListOf<String>()
        var sentBody = ""
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, assetId ->
                openedAssets += assetId
                asset()
            },
            transport = modelTransport { _, _, body ->
                sentBody = body
                ModelHttpResponse(200, envelope(organizationV3Payload()))
            },
            clock = { AUTHORIZATION_NOW },
        )

        val execution = authorizedOrganizationV3(gateway)
        val input = execution.request.input as ProblemOrganizationV3Input
        val events = gateway.execute(execution).toList()
        val output = (events.last() as ModelGatewayEvent.Completed).output
            as ProblemOrganizationOutput
        val evidence = output.plan.errorAttributionCandidates.single().evidenceRefs.single()

        assertEquals(listOf(ASSET_ID), openedAssets)
        assertTrue(sentBody.contains("data:image/jpeg;base64,"))
        assertTrue(sentBody.contains("confirmed-question-block-1"))
        assertTrue(sentBody.contains("source-1"))
        assertFalse(sentBody.contains(input.problemId))
        assertFalse(sentBody.contains(input.capturedDocument.document.id))
        assertFalse(sentBody.contains(input.capturedDocument.document.blocks.single().id))
        assertFalse(sentBody.contains(ASSET_ID))
        assertFalse(sentBody.contains("relatedCandidates"))
        assertFalse(sentBody.contains(RELATED_PROBLEM_ID))
        assertFalse(sentBody.contains("导数变式"))
        assertEquals(input.capturedDocument.document.blocks.single().id, evidence.blockId)
        assertEquals(ASSET_ID, evidence.sourceAssetId)
    }

    @Test
    fun publicAddressPolicyRejectsLocalAndReservedNetworks() {
        listOf(
            "127.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "169.254.169.254",
            "192.168.1.1",
            "198.51.100.4",
            "203.0.113.8",
            "::1",
            "fc00::1",
            "2001:db8::1",
        ).forEach { address ->
            assertFalse(address, InetAddress.getByName(address).isPubliclyRoutable())
        }
        assertTrue(InetAddress.getByName("8.8.8.8").isPubliclyRoutable())
        assertTrue(InetAddress.getByName("2606:4700:4700::1111").isPubliclyRoutable())
    }

    private suspend fun authorizedAssessment(
        gateway: OpenAiCompatibleModelGateway,
    ): ModelGatewayExecution {
        val capabilities = gateway.capabilities()
        val input = CaptureAssessmentInput(
            draftId = DRAFT_ID,
            sourceAssetId = ASSET_ID,
            origin = CaptureAssessmentOrigin.LIBRARY,
            imageWidth = 100,
            imageHeight = 200,
        )
        val requestId = "assessment-request"
        val request = ModelTaskRequest(
            requestId = requestId,
            input = input,
            occurredAtEpochMillis = REQUEST_OCCURRED_AT,
            egressManifest = manifest(capabilities, requestId, input),
        )
        return ModelEgressPolicy.authorize(request, capabilities, AUTHORIZATION_NOW)
    }

    private suspend fun authorizedParse(
        gateway: OpenAiCompatibleModelGateway,
    ): ModelGatewayExecution {
        val capabilities = gateway.capabilities()
        val input = CaptureParseInput(
            draftId = DRAFT_ID,
            origin = CaptureAssessmentOrigin.LIBRARY,
            basisRevisionNumber = 1,
            sourceAssets = listOf(
                CaptureSourceAssetRef(
                    assetId = ASSET_ID,
                    sha256 = SHA,
                    width = 100,
                    height = 200,
                    pageIndex = 0,
                ),
            ),
            assessmentRequestId = "assessment-request",
        )
        val requestId = "parse-request"
        val request = ModelTaskRequest(
            requestId = requestId,
            input = input,
            occurredAtEpochMillis = REQUEST_OCCURRED_AT,
            egressManifest = manifest(capabilities, requestId, input),
        )
        return ModelEgressPolicy.authorize(request, capabilities, AUTHORIZATION_NOW)
    }

    private suspend fun authorizedParseWithDeclaredAssetBytes(
        gateway: OpenAiCompatibleModelGateway,
        byteSizes: List<Long>,
    ): ModelGatewayExecution {
        val capabilities = gateway.capabilities()
        val assetIds = byteSizes.indices.map { index -> "declared-asset-${index + 1}" }
        val sourceAssets = assetIds.mapIndexed { index, assetId ->
            CaptureSourceAssetRef(
                assetId = assetId,
                sha256 = SHA,
                width = 100,
                height = 200,
                pageIndex = index,
            )
        }
        val assessmentRequestIds = byteSizes.indices.map { index ->
            "declared-assessment-${index + 1}"
        }
        val input = CaptureParseInput(
            draftId = DRAFT_ID,
            origin = CaptureAssessmentOrigin.LIBRARY,
            basisRevisionNumber = 1,
            sourceAssets = sourceAssets,
            assessmentRequestId = assessmentRequestIds.first(),
            assessmentRequestIds = assessmentRequestIds,
        )
        val requestId = "oversized-parse-request"
        val manifest = ModelEgressManifest(
            authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
            subjectId = DRAFT_ID,
            purpose = ModelEgressPurpose.CAPTURE_TO_DOCUMENT,
            authorizedTaskKinds = setOf(ModelTaskKind.CAPTURE_ASSESS, ModelTaskKind.CAPTURE_PARSE),
            providerId = capabilities.providerId,
            modelId = capabilities.modelId,
            providerConfigurationVersion = capabilities.providerConfigurationVersion,
            promptPolicyVersion = ModelPromptPolicyVersions.CAPTURE_DOCUMENT,
            approvedAtEpochMillis = AUTHORIZATION_APPROVED_AT,
            assets = sourceAssets.mapIndexed { index, source ->
                ModelEgressAssetGrant(
                    assetId = source.assetId,
                    sha256 = source.sha256,
                    byteSize = byteSizes[index],
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
            occurredAtEpochMillis = REQUEST_OCCURRED_AT,
            egressManifest = manifest,
        )
        return ModelEgressPolicy.authorize(request, capabilities, AUTHORIZATION_NOW)
    }

    private suspend fun authorizedTutor(
        gateway: OpenAiCompatibleModelGateway,
        input: TutorPlanInput = tutorInput(),
    ): ModelGatewayExecution {
        val capabilities = gateway.capabilities()
        val requestId = "tutor-request"
        val request = ModelTaskRequest(
            requestId = requestId,
            input = input,
            occurredAtEpochMillis = REQUEST_OCCURRED_AT,
            egressManifest = ModelEgressManifest(
                authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
                subjectId = TUTOR_SESSION_ID,
                purpose = ModelEgressPurpose.TUTORING,
                authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_PLAN),
                providerId = capabilities.providerId,
                modelId = capabilities.modelId,
                providerConfigurationVersion = capabilities.providerConfigurationVersion,
                promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_PLAN,
                approvedAtEpochMillis = AUTHORIZATION_APPROVED_AT,
                assets = emptyList(),
                disclosedData = ModelEgressManifest.TUTOR_PLAN_DISCLOSURE,
                prohibitedData = ModelEgressManifest.TUTOR_PLAN_PROHIBITED_DATA,
            ),
        )
        return ModelEgressPolicy.authorize(request, capabilities, AUTHORIZATION_NOW)
    }

    private fun tutorInput() = TutorPlanInput(
        sessionId = TUTOR_SESSION_ID,
        draftRevisionNumber = 3,
        subject = "MATH",
        questionDocument = QuestionDocument(
            id = "question-confirmed",
            blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
        ),
        teachingConstraints = listOf(
            TutorKnowledgeGuidance(
                ref = "current-question-point-1",
                label = "导数符号",
                constraint = TutorTeachingConstraint.MAY_GUIDE,
            ),
        ),
        reviewedTeachingReferences = listOf(
            TutorTeachingReference(
                materialId = "teaching-method-1",
                subject = "MATH",
                materialType = KnowledgeTeachingMaterialType.WORKED_EXAMPLE,
                title = "由导数符号判断单调性",
                summaryMarkdown = "先找分界点，再判断各区间内的导数符号。",
                applicabilityMarkdown = "适用于由导数判断函数单调性的当前题。",
                contentMarkdown = "完整例题：先找导数为零的分界点，再列符号表。",
                boundaryMarkdown = "必须结合当前题的定义域。",
                knowledgeNodeIds = listOf("node-derivative"),
            ),
        ),
    )

    private suspend fun authorizedTutorRespond(
        gateway: OpenAiCompatibleModelGateway,
        input: TutorRespondInput = tutorRespondInput(),
    ): ModelGatewayExecution {
        val capabilities = gateway.capabilities()
        val requestId = "tutor-respond-request"
        val request = ModelTaskRequest(
            requestId = requestId,
            input = input,
            occurredAtEpochMillis = REQUEST_OCCURRED_AT,
            egressManifest = ModelEgressManifest(
                authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
                subjectId = TUTOR_SESSION_ID,
                purpose = ModelEgressPurpose.TUTORING,
                authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_RESPOND),
                providerId = capabilities.providerId,
                modelId = capabilities.modelId,
                providerConfigurationVersion = capabilities.providerConfigurationVersion,
                promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_RESPOND,
                approvedAtEpochMillis = AUTHORIZATION_APPROVED_AT,
                assets = emptyList(),
                disclosedData = ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE,
                prohibitedData = ModelEgressManifest.TUTOR_RESPOND_PROHIBITED_DATA,
            ),
        )
        return ModelEgressPolicy.authorize(request, capabilities, AUTHORIZATION_NOW)
    }

    private suspend fun authorizedTutorVisualGenerate(
        gateway: OpenAiCompatibleModelGateway,
    ): ModelGatewayExecution {
        val capabilities = gateway.capabilities()
        val input = tutorVisualGenerateInput()
        val requestId = "tutor-visual-generate-request"
        val request = ModelTaskRequest(
            requestId = requestId,
            input = input,
            occurredAtEpochMillis = REQUEST_OCCURRED_AT,
            egressManifest = tutorVisualManifest(
                capabilities = capabilities,
                requestId = requestId,
                input = input,
                kind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
            ),
        )
        return ModelEgressPolicy.authorize(request, capabilities, AUTHORIZATION_NOW)
    }

    private suspend fun authorizedTutorVisualReview(
        gateway: OpenAiCompatibleModelGateway,
    ): ModelGatewayExecution {
        val capabilities = gateway.capabilities()
        val generated = tutorVisualGenerateInput()
        val input = TutorVisualReviewInput(
            sessionId = generated.sessionId,
            draftRevisionNumber = generated.draftRevisionNumber,
            subject = generated.subject,
            questionDocument = generated.questionDocument,
            sourceAssets = generated.sourceAssets,
            anchor = generated.anchor,
            focusMarkdown = generated.focusMarkdown,
            explanationMarkdown = generated.explanationMarkdown,
            candidateScene = visualDocumentScene(),
            reviewReasonCodes = setOf("multiple_synchronized_views"),
            sourceFacts = generated.sourceFacts,
        )
        val requestId = "tutor-visual-review-request"
        val request = ModelTaskRequest(
            requestId = requestId,
            input = input,
            occurredAtEpochMillis = REQUEST_OCCURRED_AT,
            egressManifest = tutorVisualManifest(
                capabilities = capabilities,
                requestId = requestId,
                input = input,
                kind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
            ),
        )
        return ModelEgressPolicy.authorize(request, capabilities, AUTHORIZATION_NOW)
    }

    private fun tutorVisualGenerateInput() = TutorVisualGenerateInput(
        sessionId = TUTOR_SESSION_ID,
        draftRevisionNumber = 3,
        subject = "MATH",
        questionDocument = QuestionDocument(
            id = "question-visual",
            blocks = listOf(
                ContentBlock.Paragraph("visual-stem", "物体移动 3 m，观察位置关系。"),
            ),
        ),
        sourceAssets = listOf(
            CaptureSourceAssetRef(
                assetId = ASSET_ID,
                sha256 = SHA,
                width = 100,
                height = 200,
                pageIndex = 0,
            ),
        ),
        anchor = TutorVisualTurnAnchor(
            surface = TutorVisualTurnSurface.PLAN,
            cycleOrdinal = 1,
            turnOrdinal = 1,
        ),
        focusMarkdown = "聚焦函数图象中的增减关系",
        explanationMarkdown = "沿横轴从左到右观察函数值的变化。",
        sourceFacts = listOf(visualSourceFact()),
    )

    private fun visualSourceFact() = TutorVisualSourceFact.create(
        factId = "visual-fact-distance",
        documentId = "question-visual",
        blockId = "visual-stem",
        locator = TutorVisualSourceTextLocator(TutorVisualSourceTextField.PARAGRAPH_MARKDOWN),
        startUtf16 = 5,
        endUtf16Exclusive = 8,
        literal = "3 m",
        value = 3.0,
        unit = "m",
        dimension = TutorVisualDimension.LENGTH,
        sourceAssetId = ASSET_ID,
        sourceAssetSha256 = SHA,
        sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
    )

    private fun tutorVisualManifest(
        capabilities: com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot,
        requestId: String,
        input: com.tingyun.smartmistakebook.core.model.ModelTaskInput,
        kind: ModelTaskKind,
    ): ModelEgressManifest {
        val disclosed = when (kind) {
            ModelTaskKind.TUTOR_VISUAL_GENERATE ->
                ModelEgressManifest.tutorVisualGenerateDisclosure(false)
            ModelTaskKind.TUTOR_VISUAL_REVIEW ->
                ModelEgressManifest.tutorVisualReviewDisclosure(false)
            else -> error("Visual manifest test helper received a non-visual task")
        }
        return ModelEgressManifest(
            authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
            subjectId = TUTOR_SESSION_ID,
            purpose = ModelEgressPurpose.TUTORING,
            authorizedTaskKinds = setOf(kind),
            providerId = capabilities.providerId,
            modelId = capabilities.modelId,
            providerConfigurationVersion = capabilities.providerConfigurationVersion,
            promptPolicyVersion = requireNotNull(ModelPromptPolicyVersions.currentFor(kind)),
            approvedAtEpochMillis = AUTHORIZATION_APPROVED_AT,
            assets = listOf(
                ModelEgressAssetGrant(
                    assetId = ASSET_ID,
                    sha256 = SHA,
                    byteSize = IMAGE.size.toLong(),
                    width = 100,
                    height = 200,
                ),
            ),
            disclosedData = disclosed,
            prohibitedData = ModelEgressDataClass.entries.toSet() - disclosed,
        )
    }

    private suspend fun authorizedTutorLobby(
        gateway: OpenAiCompatibleModelGateway,
        input: TutorLobbyInput = tutorLobbyInput(),
        manifestSchemaVersion: Int = ModelEgressManifest.CURRENT_SCHEMA_VERSION,
    ): ModelGatewayExecution {
        val capabilities = gateway.capabilities()
        val requestId = "tutor-lobby-request"
        val request = ModelTaskRequest(
            requestId = requestId,
            input = input,
            occurredAtEpochMillis = REQUEST_OCCURRED_AT,
            egressManifest = ModelEgressManifest(
                schemaVersion = manifestSchemaVersion,
                authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
                subjectId = input.conversationId,
                purpose = ModelEgressPurpose.TUTORING,
                authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_LOBBY),
                providerId = capabilities.providerId,
                modelId = capabilities.modelId,
                providerConfigurationVersion = capabilities.providerConfigurationVersion,
                promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_LOBBY,
                approvedAtEpochMillis = AUTHORIZATION_APPROVED_AT,
                assets = emptyList(),
                disclosedData =
                    ModelEgressManifest.tutorLobbyDisclosureForSchema(manifestSchemaVersion),
                prohibitedData =
                    ModelEgressManifest.tutorLobbyProhibitedDataForSchema(manifestSchemaVersion),
            ),
        )
        return ModelEgressPolicy.authorize(request, capabilities, AUTHORIZATION_NOW)
    }

    private fun tutorLobbyInput() = TutorLobbyInput(
        conversationId = "tutor-lobby",
        messageOrdinal = 2,
        studentMessage = "帮我找函数错题",
        priorMessages = listOf(
            TutorChatHistoryEntry(
                studentMessage = "你好",
                assistantMarkdown = "你好，你现在想做什么？",
            ),
        ),
    )

    private fun tutorLobbyContextFrom(requestBody: String): JsonObject {
        val request = Json.parseToJsonElement(requestBody) as JsonObject
        val messages = request.getValue("messages") as JsonArray
        val userMessage = messages[1] as JsonObject
        val content = userMessage.getValue("content") as JsonArray
        val textPart = content.first() as JsonObject
        val prompt = (textPart.getValue("text") as JsonPrimitive).content
        val contextLine = prompt.lineSequence()
            .map(String::trim)
            .single { line -> line.startsWith(TUTOR_LOBBY_CONTEXT_PREFIX) }
        return Json.parseToJsonElement(contextLine.removePrefix(TUTOR_LOBBY_CONTEXT_PREFIX))
            as JsonObject
    }

    private fun tutorRespondInput() = TutorRespondInput(
        sessionId = TUTOR_SESSION_ID,
        draftRevisionNumber = 3,
        subject = "MATH",
        questionDocument = tutorInput().questionDocument,
        teachingConstraints = tutorInput().teachingConstraints,
        reviewedTeachingReferences = tutorInput().reviewedTeachingReferences,
        responseOrdinal = 4,
        cycleOrdinal = 2,
        turnOrdinal = 3,
        studentMessage = "这一步为什么要先判断导数符号？",
        visibleTutorContextMarkdown = "当前讲解正在分析导数符号与单调性的对应。",
        priorMessages = listOf(
            TutorChatHistoryEntry(
                studentMessage = "先从哪里开始？",
                assistantMarkdown = "先找到导数的零点。",
            ),
        ),
        requestedMove = TutorMoveType.CHANGE_REPRESENTATION,
    )

    private suspend fun authorizedOrganization(
        gateway: OpenAiCompatibleModelGateway,
    ): ModelGatewayExecution {
        val capabilities = gateway.capabilities()
        val input = ProblemOrganizationInput(
            problemId = ORGANIZATION_PROBLEM_ID,
            problemRevisionId = "revision-current",
            practiceUnitId = "unit-current",
            subject = SubjectKind.MATH,
            questionDocument = QuestionDocument(
                id = "question-current",
                blocks = listOf(ContentBlock.Paragraph("stem-current", "求函数的单调区间")),
            ),
            relevantLearningEvidence = emptyList(),
            relationCandidates = listOf(
                RelatedProblemCandidate(
                    problemId = RELATED_PROBLEM_ID,
                    problemRevisionId = "revision-related",
                    subject = SubjectKind.MATH,
                    title = "导数变式",
                    questionDocument = QuestionDocument(
                        id = "question-related",
                        blocks = listOf(
                            ContentBlock.Paragraph("stem-related", "讨论参数函数的单调性"),
                        ),
                    ),
                ),
            ),
            knowledgeBaseNodes = listOf(
                KnowledgeBaseNodeContext(
                    knowledgeNodeId = "math-derivative-sign-monotonicity",
                    catalogProvenance = testKnowledgeCatalogProvenance(),
                    subject = SubjectKind.MATH,
                    canonicalName = "根据导数符号判断函数单调性",
                    aliases = emptyList(),
                    kind = KnowledgeNodeKind.REASONING,
                    granularity = KnowledgeNodeGranularity.ATOMIC,
                    parentCanonicalName = "利用导数研究函数单调性",
                    taxonomyVersion = "math-v1",
                    verificationStatus = KnowledgeNodeVerificationStatus.CURATED,
                    boundaryMarkdown = "不包含求导公式的机械计算。",
                ),
            ),
        )
        val requestId = "organization-request"
        val request = ModelTaskRequest(
            requestId = requestId,
            input = input,
            occurredAtEpochMillis = REQUEST_OCCURRED_AT,
            egressManifest = ModelEgressManifest(
                authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
                subjectId = input.subjectId,
                purpose = ModelEgressPurpose.CLASSIFICATION,
                authorizedTaskKinds = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
                providerId = capabilities.providerId,
                modelId = capabilities.modelId,
                providerConfigurationVersion = capabilities.providerConfigurationVersion,
                promptPolicyVersion = ModelPromptPolicyVersions.PROBLEM_ORGANIZATION,
                approvedAtEpochMillis = AUTHORIZATION_APPROVED_AT,
                assets = emptyList(),
                disclosedData = ModelEgressManifest.PROBLEM_ORGANIZATION_DISCLOSURE,
                prohibitedData = ModelEgressManifest.PROBLEM_ORGANIZATION_PROHIBITED_DATA,
            ),
        )
        return ModelEgressPolicy.authorize(request, capabilities, AUTHORIZATION_NOW)
    }

    private suspend fun authorizedOrganizationV3(
        gateway: OpenAiCompatibleModelGateway,
    ): ModelGatewayExecution {
        val capabilities = gateway.capabilities()
        val input = ProblemOrganizationV3Input(
            problemId = "problem-v3-secret-local-id",
            problemRevisionId = "revision-v3-secret-local-id",
            practiceUnitId = "unit-v3-secret-local-id",
            subject = SubjectKind.MATH,
            capturedDocument = CapturedQuestionDocument(
                document = QuestionDocument(
                    id = "question-v3-secret-local-id",
                    blocks = listOf(
                        ContentBlock.Paragraph(
                            "block-v3-secret-local-id",
                            "手写答案把导数为负的区间判断成递增区间。",
                        ),
                    ),
                ),
                blockEvidence = listOf(
                    QuestionBlockEvidence(
                        blockId = "block-v3-secret-local-id",
                        sourceAssetId = ASSET_ID,
                        writingLayer = WritingLayer.HANDWRITTEN,
                        provenance = QuestionBlockProvenance.USER_CORRECTION,
                        confidence = 0.95,
                        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    ),
                ),
            ),
            sourceAssets = listOf(
                CaptureSourceAssetRef(
                    assetId = ASSET_ID,
                    sha256 = SHA,
                    width = 100,
                    height = 200,
                    pageIndex = 0,
                ),
            ),
            relationCandidates = emptyList(),
            knowledgeBaseNodes = listOf(
                KnowledgeBaseNodeContext(
                    knowledgeNodeId = "knowledge-v3-secret-local-id",
                    catalogProvenance = testKnowledgeCatalogProvenance(),
                    subject = SubjectKind.MATH,
                    canonicalName = "根据导数符号判断函数单调性",
                    aliases = emptyList(),
                    kind = KnowledgeNodeKind.REASONING,
                    granularity = KnowledgeNodeGranularity.ATOMIC,
                    parentCanonicalName = "利用导数研究函数单调性",
                    taxonomyVersion = "math-v1",
                    verificationStatus = KnowledgeNodeVerificationStatus.CURATED,
                    boundaryMarkdown = "不包含求导公式的机械计算。",
                ),
            ),
        )
        val disclosure = ModelEgressManifest.problemOrganizationV3Disclosure(
            includesSelectedRegion = false,
        )
        val requestId = "organization-v3-request"
        val request = ModelTaskRequest(
            requestId = requestId,
            input = input,
            occurredAtEpochMillis = REQUEST_OCCURRED_AT,
            egressManifest = ModelEgressManifest(
                authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
                subjectId = input.subjectId,
                purpose = ModelEgressPurpose.CLASSIFICATION,
                authorizedTaskKinds = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
                providerId = capabilities.providerId,
                modelId = capabilities.modelId,
                providerConfigurationVersion = capabilities.providerConfigurationVersion,
                promptPolicyVersion = ModelPromptPolicyVersions.PROBLEM_ORGANIZATION,
                approvedAtEpochMillis = AUTHORIZATION_APPROVED_AT,
                assets = listOf(
                    ModelEgressAssetGrant(
                        assetId = ASSET_ID,
                        sha256 = SHA,
                        byteSize = IMAGE.size.toLong(),
                        width = 100,
                        height = 200,
                    ),
                ),
                disclosedData = disclosure,
                prohibitedData = ModelEgressManifest.problemOrganizationV3ProhibitedData(
                    includesSelectedRegion = false,
                ),
            ),
        )
        return ModelEgressPolicy.authorize(request, capabilities, AUTHORIZATION_NOW)
    }

    private fun manifest(
        capabilities: com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot,
        requestId: String,
        input: com.tingyun.smartmistakebook.core.model.ModelTaskInput,
    ) = ModelEgressManifest(
        authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
        subjectId = input.subjectId,
        purpose = ModelEgressPurpose.CAPTURE_TO_DOCUMENT,
        authorizedTaskKinds = setOf(ModelTaskKind.CAPTURE_ASSESS, ModelTaskKind.CAPTURE_PARSE),
        providerId = capabilities.providerId,
        modelId = capabilities.modelId,
        providerConfigurationVersion = capabilities.providerConfigurationVersion,
        promptPolicyVersion = ModelPromptPolicyVersions.CAPTURE_DOCUMENT,
        approvedAtEpochMillis = AUTHORIZATION_APPROVED_AT,
        assets = listOf(ModelEgressAssetGrant(ASSET_ID, SHA, IMAGE.size.toLong(), 100, 200)),
        disclosedData = ModelEgressManifest.CAPTURE_IMAGE_DISCLOSURE,
        prohibitedData = ModelEgressDataClass.entries.toSet() -
            ModelEgressManifest.CAPTURE_IMAGE_DISCLOSURE,
    )

    private fun asset() = RestrictedModelAsset(
        assetId = ASSET_ID,
        sha256 = SHA,
        mimeType = "image/jpeg",
        byteSize = IMAGE.size.toLong(),
        width = 100,
        height = 200,
        stream = ByteArrayInputStream(IMAGE),
    )

    private fun modelTransport(
        post: suspend (String, CharArray, String) -> ModelHttpResponse,
    ): ModelHttpTransport = ModelHttpTransport { baseUrl, apiKey, requestBody, beforeEnqueue ->
        beforeEnqueue()
        post(baseUrl, apiKey, requestBody)
    }

    private fun assetSource(
        open: suspend (ModelGatewayExecution, String) -> RestrictedModelAsset,
    ): RestrictedModelAssetSource = object : RestrictedModelAssetSource {
        override suspend fun open(
            execution: ModelGatewayExecution,
            assetId: String,
        ): RestrictedModelAsset = open(execution, assetId)
    }

    private fun envelope(content: String): String = Json.encodeToString(
        buildJsonObject {
            put(
                "choices",
                buildJsonArray {
                    add(buildJsonObject {
                        put("message", buildJsonObject { put("content", content) })
                    })
                },
            )
        },
    )

    private fun streamDelta(content: String): String = Json.encodeToString(
        buildJsonObject {
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "delta",
                                buildJsonObject { put("content", content) },
                            )
                        },
                    )
                },
            )
        },
    )

    private fun assessmentPayload(): String = Json.encodeToString(
        buildJsonObject {
            put("decision", "PASS")
            put("issues", buildJsonArray {})
            put("suggestedActions", buildJsonArray {})
        },
    )

    private fun splitAssessmentPayload(): String = Json.encodeToString(
        buildJsonObject {
            put("decision", "SPLIT")
            put("issues", buildJsonArray {
                add(buildJsonObject {
                    put("code", "MULTIPLE_QUESTIONS")
                    put("severity", "BLOCKING")
                    put("message", "画面中有两道独立题目")
                })
            })
            put("suggestedActions", buildJsonArray {})
            put("questionRegions", buildJsonArray {
                add(buildJsonObject {
                    put("left", 0.05); put("top", 0.05)
                    put("right", 0.95); put("bottom", 0.45)
                })
                add(buildJsonObject {
                    put("left", 0.05); put("top", 0.55)
                    put("right", 0.95); put("bottom", 0.95)
                })
            })
        },
    )

    private fun parsePayload(): String = Json.encodeToString(
        buildJsonObject {
            put("title", "函数单调性")
            put(
                "blocks",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "choice_group")
                            put("pageIndex", 0)
                            put("region", buildJsonObject {
                                put("left", 0.1); put("top", 0.2)
                                put("right", 0.9); put("bottom", 0.8)
                            })
                            put("writingLayer", "PRINTED")
                            put("confidence", 0.94)
                            put("promptMarkdown", "函数 ${'$'}f(x)${'$'} 的单调区间是")
                            put(
                                "choices",
                                buildJsonArray {
                                    add(buildJsonObject { put("markdown", "${'$'}(0,+∞)${'$'}") })
                                    add(buildJsonObject { put("markdown", "${'$'}(-∞,0)${'$'}") })
                                },
                            )
                            put("selectedChoiceId", "malicious-answer")
                            put("sourceAssetId", "malicious-asset")
                            put("id", "malicious-block")
                        },
                    )
                },
            )
        },
    )

    private fun figureParsePayload(): String = Json.encodeToString(
        buildJsonObject {
            put("title", "一次函数图像")
            put(
                "blocks",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "figure")
                            put("pageIndex", 0)
                            put("region", buildJsonObject {
                                put("left", 0.1); put("top", 0.1)
                                put("right", 0.9); put("bottom", 0.9)
                            })
                            put("writingLayer", "PRINTED")
                            put("confidence", 0.91)
                            put("title", "函数 y=x")
                            put("alternativeText", "直线 y=x 经过原点")
                            put(
                                "schema",
                                buildJsonObject {
                                    put("type", "cartesian")
                                    put("xAxis", axis(-2.0, 2.0, "x"))
                                    put("yAxis", axis(-2.0, 2.0, "y"))
                                    put(
                                        "polylines",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("id", "untrusted-model-id")
                                                    put("label", "y=x")
                                                    put("style", "PRIMARY")
                                                    put(
                                                        "points",
                                                        buildJsonArray {
                                                            add(coordinate(-2.0, -2.0))
                                                            add(coordinate(2.0, 2.0))
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                    put("points", buildJsonArray { add(buildJsonObject {
                                        put("x", 0.0); put("y", 0.0); put("label", "O")
                                        put("style", "EMPHASIS")
                                    }) })
                                    put("labels", buildJsonArray { add(buildJsonObject {
                                        put("x", 1.0); put("y", 1.0); put("text", "A")
                                    }) })
                                },
                            )
                        },
                    )
                },
            )
        },
    )

    private fun axis(minimum: Double, maximum: Double, label: String) = buildJsonObject {
        put("minimum", minimum)
        put("maximum", maximum)
        put("label", label)
        put("tickCount", 5)
    }

    private fun coordinate(x: Double, y: Double) = buildJsonObject {
        put("x", x)
        put("y", y)
    }

    private fun visualDocumentPayload(
        extra: Pair<String, JsonElement>? = null,
        variables: JsonElement = buildJsonArray {},
        nodeValueVariableId: String? = null,
    ): JsonObject = buildJsonObject {
        put("kind", "visual_document")
        put("title", "关系图")
        put(
            "panels",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("panelId", "panel")
                        put("kind", "DIAGRAM_2D")
                    },
                )
            },
        )
        put("variables", variables)
        put(
            "elements",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "node_2d")
                        put("elementId", "object")
                        put("panelId", "panel")
                        put("kind", "RECTANGLE")
                        put("label", "对象")
                        nodeValueVariableId?.let { put("valueVariableId", it) }
                    },
                )
            },
        )
        put("bindings", buildJsonArray {})
        put(
            "steps",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("stepId", "focus")
                        put("label", "先看对象")
                        put("focusElementIds", buildJsonArray { add(JsonPrimitive("object")) })
                        put("primaryRelationElementId", "object")
                    },
                )
            },
        )
        put("durationSeconds", 0.0)
        put("fallbackMarkdown", "先观察对象之间的关系。")
        put("accessibilitySummary", "一个标有对象的矩形。")
        extra?.let { put(it.first, it.second) }
    }

    private fun visualDocumentScene() = TutorVisualDocumentScene(
        sceneId = "candidate-scene",
        title = "关系图",
        panels = listOf(TutorVisualPanel("panel", TutorVisualPanelKind.DIAGRAM_2D)),
        elements = listOf(
            TutorVisual2DNodeElement(
                elementId = "object",
                panelId = "panel",
                kind = TutorVisual2DNodeKind.RECTANGLE,
                label = "对象",
            ),
        ),
        steps = listOf(
            TutorVisualStep(
                stepId = "focus",
                label = "先看对象",
                focusElementIds = listOf("object"),
                primaryRelationElementId = "object",
            ),
        ),
        fallbackMarkdown = "先观察对象之间的关系。",
        accessibilitySummary = "一个标有对象的矩形。",
        provenanceSchemaVersion = TutorVisualDocumentScene.CURRENT_PROVENANCE_SCHEMA_VERSION,
    )

    private fun tutorPayload(
        includeDiagnostic: Boolean = true,
        includeNextMoves: Boolean = true,
        visualScene: JsonElement? = null,
        extraTopLevel: Pair<String, JsonElement>? = null,
        responseIntent: TutorResponseIntent? = null,
        solutionRevealed: Boolean? = null,
    ): String = Json.encodeToString(
        buildJsonObject {
            put(
                "responseIntent",
                (
                    responseIntent ?: if (includeDiagnostic) {
                        TutorResponseIntent.ASK
                    } else {
                        TutorResponseIntent.EXPLAIN
                    }
                    ).name,
            )
            solutionRevealed?.let { put("solutionRevealed", it) }
            put("openingMarkdown", "先看导数符号怎样变化，不急着写最终区间。")
            if (includeDiagnostic) {
                put(
                    "diagnosticQuestion",
                    buildJsonObject {
                        put("stemMarkdown", "导数先正后负时，原函数怎样变化？")
                        put("promptMarkdown", "请选择最能说明理由的一项")
                        put(
                            "choices",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("markdown", "先增后减")
                                    put("feedbackMarkdown", "导数正负和单调性对应正确。")
                                    put("isCorrect", true)
                                })
                                add(buildJsonObject {
                                    put("markdown", "先减后增")
                                    put("feedbackMarkdown", "正负对应关系反了。")
                                    put("isCorrect", false)
                                })
                                add(buildJsonObject {
                                    put("markdown", "始终递增")
                                    put("feedbackMarkdown", "忽略了导数变号。")
                                    put("isCorrect", false)
                                })
                            },
                        )
                    },
                )
            }
            visualScene?.let { put("visualScene", it) }
            put("solutionMarkdown", "求导并解符号不等式，再写出单调区间。")
            put("alternateMethodMarkdown", "画导函数符号表，从图像变化理解单调性。")
            put("difficultyReasonMarkdown", "区分正负对应错误和变号遗漏。")
            put("targetedEvidenceLabels", buildJsonArray { add(JsonPrimitive("导数符号")) })
            put(
                "inferredKnowledgeLabels",
                buildJsonArray {
                    add(JsonPrimitive("导数"))
                    add(JsonPrimitive("函数单调性"))
                },
            )
            if (includeNextMoves) {
                put(
                    "nextMoves",
                    buildJsonArray {
                        add(buildJsonObject {
                            put("label", "沿导数变号继续推")
                            put("type", "DEEPEN_REASONING")
                        })
                        add(buildJsonObject {
                            put("label", "改用函数图像理解")
                            put("type", "CHANGE_REPRESENTATION")
                        })
                        add(buildJsonObject {
                            put("label", "查看完整讲解")
                            put("type", "REVEAL_SOLUTION")
                        })
                    },
                )
            }
            extraTopLevel?.let { (key, value) -> put(key, value) }
        },
    )

    private fun tutorRespondPayload(
        messageMarkdown: String = "导数符号决定原函数在当前区间内的增减方向。",
        solutionRevealed: Boolean = false,
        responseIntent: TutorResponseIntent = TutorResponseIntent.EXPLAIN,
        visualScene: JsonElement? = null,
        nextMoves: JsonElement? = null,
        intentDecision: JsonElement? = tutorIntentPayload(),
        freeResponseEvaluation: TutorFreeResponseEvaluation? = null,
        extraTopLevel: Pair<String, JsonElement>? = null,
    ): String = Json.encodeToString(
        buildJsonObject {
            intentDecision?.let { put("intentDecision", it) }
            put("responseIntent", responseIntent.name)
            put("solutionRevealed", solutionRevealed)
            put("messageMarkdown", messageMarkdown)
            freeResponseEvaluation?.let { put("freeResponseEvaluation", it.name) }
            visualScene?.let { put("visualScene", it) }
            nextMoves?.let { put("nextMoves", it) }
            extraTopLevel?.let { (key, value) -> put(key, value) }
        },
    )

    private fun tutorLobbyPayload(
        messageMarkdown: String,
        intent: TutorMessageIntent = TutorMessageIntent.AMBIGUOUS,
        responseIntent: TutorResponseIntent? = null,
        interactionDirective: JsonObject? = null,
    ): String = Json.encodeToString(
        buildJsonObject {
            put("intentDecision", tutorIntentPayload(intent = intent))
            put("messageMarkdown", messageMarkdown)
            responseIntent?.let { put("responseIntent", it.name) }
            interactionDirective?.let { put("interactionDirective", it) }
        },
    )

    private fun tutorIntentPayload(
        intent: TutorMessageIntent = TutorMessageIntent.CURRENT_QUESTION_HELP,
        confidence: Double = 0.98,
        explicitActionRequest: Boolean = false,
        memoryPreference: TutorMemoryPreference = TutorMemoryPreference.UNCHANGED,
        capability: TutorRequestedLocalCapability = TutorRequestedLocalCapability.NONE,
        lookupTerms: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("intent", intent.name)
        put("confidence", confidence)
        put("explicitActionRequest", explicitActionRequest)
        put("memoryPreference", memoryPreference.name)
        put("requestedLocalCapability", capability.name)
        put(
            "lookupTerms",
            buildJsonArray {
                lookupTerms.forEach { term -> add(JsonPrimitive(term)) }
            },
        )
    }

    private fun stepFlowScenePayload(
        bodyMarkdown: String = "先确定导数为正与为负的区间。",
        sceneExtras: Map<String, JsonElement> = emptyMap(),
        stepExtras: Map<String, JsonElement> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("kind", "step_flow")
        put("title", "解题路径")
        put(
            "steps",
            buildJsonArray {
                add(buildJsonObject {
                    put("label", "判断符号")
                    put("bodyMarkdown", bodyMarkdown)
                    put("formula", "f'(x)>0")
                    put("emphasis", "KEY")
                    stepExtras.forEach { (key, value) -> put(key, value) }
                })
                add(buildJsonObject {
                    put("label", "写出结论")
                    put("bodyMarkdown", "把符号区间对应到原函数的增减性。")
                    put("emphasis", "CHECK")
                })
            },
        )
        sceneExtras.forEach { (key, value) -> put(key, value) }
    }

    private fun comparisonScenePayload(): JsonObject = buildJsonObject {
        put("kind", "comparison")
        put("title", "符号与增减对照")
        put("leftTitle", "导数为正")
        put("rightTitle", "导数为负")
        put(
            "rows",
            buildJsonArray {
                add(buildJsonObject {
                    put("criterion", "原函数变化")
                    put("leftMarkdown", "原函数递增")
                    put("rightMarkdown", "原函数递减")
                    put("takeawayMarkdown", "先看导数符号，再对应增减性。")
                })
            },
        )
    }

    private fun evidenceChainScenePayload(): JsonObject = buildJsonObject {
        put("kind", "evidence_chain")
        put("title", "推理依据")
        put("claimMarkdown", "函数在目标区间内先增后减。")
        put(
            "evidence",
            buildJsonArray {
                add(buildJsonObject {
                    put("kind", "GIVEN")
                    put("markdown", "导数在分界点两侧由正变负。")
                })
                add(buildJsonObject {
                    put("kind", "INFERENCE")
                    put("markdown", "导数正负分别对应原函数递增和递减。")
                })
            },
        )
        put("conclusionMarkdown", "因此原函数先增后减。")
    }

    private fun processTimelineScenePayload(
        lastTransitionMarkdown: String? = null,
    ): JsonObject = buildJsonObject {
        put("kind", "process_timeline")
        put("title", "反应变化过程")
        put(
            "stages",
            buildJsonArray {
                add(buildJsonObject {
                    put("label", "开始")
                    put("bodyMarkdown", "反应物充分接触。")
                    put("transitionMarkdown", "达到反应条件")
                })
                add(buildJsonObject {
                    put("label", "变化后")
                    put("bodyMarkdown", "生成物比例趋于稳定。")
                    lastTransitionMarkdown?.let { put("transitionMarkdown", it) }
                })
            },
        )
    }

    private fun conceptMapScenePayload(): JsonObject = buildJsonObject {
        put("kind", "concept_map")
        put("title", "函数关系")
        put("centerMarkdown", "导数符号")
        put(
            "relations",
            buildJsonArray {
                add(buildJsonObject {
                    put("relationLabel", "决定")
                    put("targetMarkdown", "原函数增减性")
                })
                add(buildJsonObject {
                    put("relationLabel", "发生改变时")
                    put("targetMarkdown", "可能出现极值")
                    put("detailMarkdown", "还要核对定义域和变号方向。")
                })
            },
        )
    }

    private fun formulaDerivationScenePayload(): JsonObject = buildJsonObject {
        put("kind", "formula_derivation")
        put("title", "配方过程")
        put("startFormula", "x^2+4x+1")
        put(
            "steps",
            buildJsonArray {
                add(buildJsonObject {
                    put("reasonMarkdown", "先补成完全平方。")
                    put("resultFormula", "x^2+4x+4-3")
                })
                add(buildJsonObject {
                    put("reasonMarkdown", "把前三项写成平方。")
                    put("resultFormula", "(x+2)^2-3")
                })
            },
        )
    }

    private fun spatialDiagramScenePayload(
        fromIndex: Int = 1,
        nodeAnchor: String = "CENTER",
    ): JsonObject = buildJsonObject {
        put("kind", "spatial_diagram")
        put("title", "物体受到哪些力")
        put(
            "nodes",
            buildJsonArray {
                add(buildJsonObject {
                    put("label", "物体")
                    put("anchor", nodeAnchor)
                    put("shape", "BLOCK")
                })
                add(buildJsonObject {
                    put("label", "N")
                    put("anchor", "TOP")
                    put("shape", "CIRCLE")
                })
                add(buildJsonObject {
                    put("label", "G")
                    put("anchor", "BOTTOM")
                    put("shape", "CIRCLE")
                })
            },
        )
        put(
            "edges",
            buildJsonArray {
                add(buildJsonObject {
                    put("fromIndex", fromIndex)
                    put("toIndex", 2)
                    put("label", "支持力")
                    put("style", "ARROW")
                })
                add(buildJsonObject {
                    put("fromIndex", 1)
                    put("toIndex", 3)
                    put("label", "重力")
                    put("style", "ARROW")
                })
            },
        )
        put("captionMarkdown", "箭头从受力物体出发。")
    }

    private fun circuitDiagramScenePayload(): JsonObject = buildJsonObject {
        put("kind", "spatial_diagram")
        put("title", "串联电路中的元件")
        put(
            "nodes",
            buildJsonArray {
                listOf(
                    Triple("接点", "TOP_LEFT", "JUNCTION"),
                    Triple("电阻", "TOP", "RESISTOR"),
                    Triple("接点", "TOP_RIGHT", "JUNCTION"),
                    Triple("灯泡", "RIGHT", "LAMP"),
                    Triple("接点", "BOTTOM_RIGHT", "JUNCTION"),
                    Triple("电源", "BOTTOM", "BATTERY"),
                    Triple("接点", "BOTTOM_LEFT", "JUNCTION"),
                    Triple("开关", "LEFT", "SWITCH_OPEN"),
                ).forEach { (label, anchor, shape) ->
                    add(buildJsonObject {
                        put("label", label)
                        put("anchor", anchor)
                        put("shape", shape)
                    })
                }
            },
        )
        put(
            "edges",
            buildJsonArray {
                listOf(
                    1 to 2,
                    2 to 3,
                    3 to 4,
                    4 to 5,
                    5 to 6,
                    6 to 7,
                    7 to 8,
                    8 to 1,
                ).forEach { (from, to) ->
                    add(buildJsonObject {
                        put("fromIndex", from)
                        put("toIndex", to)
                        put("style", "LINE")
                    })
                }
            },
        )
        put("captionMarkdown", "开关目前断开，闭合后电流流过电阻和灯泡。")
    }

    private fun linearMotionScenePayload(
        durationSeconds: Double = 2.0,
        extra: Pair<String, JsonElement>? = null,
    ): JsonObject = buildJsonObject {
        put("kind", "linear_motion")
        put("title", "匀加速直线运动")
        put("durationSeconds", durationSeconds)
        put("initialPositionMeters", 0.0)
        put("initialVelocityMetersPerSecond", 2.0)
        put("accelerationMetersPerSecondSquared", 1.0)
        extra?.let { (key, value) -> put(key, value) }
    }

    private fun projectileMotionScenePayload(): JsonObject = buildJsonObject {
        put("kind", "projectile_motion")
        put("title", "平抛运动")
        put("durationSeconds", 2.0)
        put("initialHeightMeters", 5.0)
        put("horizontalVelocityMetersPerSecond", 4.0)
        put("verticalVelocityMetersPerSecond", 0.0)
        put("gravityMetersPerSecondSquared", 10.0)
    }

    private fun circularMotionScenePayload(): JsonObject = buildJsonObject {
        put("kind", "circular_motion")
        put("title", "匀速圆周运动")
        put("durationSeconds", 4.0)
        put("radiusMeters", 2.0)
        put("angularVelocityRadiansPerSecond", 1.57)
        put("initialAngleRadians", 0.0)
    }

    private fun oscillationMotionScenePayload(): JsonObject = buildJsonObject {
        put("kind", "oscillation_motion")
        put("title", "简谐运动")
        put("durationSeconds", 4.0)
        put("equilibriumPositionMeters", 0.0)
        put("amplitudeMeters", 1.0)
        put("periodSeconds", 4.0)
        put("initialPhaseRadians", 0.0)
    }

    private fun visualProgramScenePayload(): JsonObject = buildJsonObject {
        put("kind", "visual_program")
        put("title", "位置随时间变化")
        put("accessibilitySummary", "小球沿横轴向右移动，位置随时间增大。")
        put(
            "parameters",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("label", "速度")
                        put("value", 2.0)
                        put("unit", "m/s")
                    },
                )
            },
        )
        put(
            "commands",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("kind", "entity")
                        put("label", "小球")
                        put("shape", "CIRCLE")
                        put(
                            "x",
                            buildJsonObject {
                                put("op", "MULTIPLY")
                                put(
                                    "left",
                                    buildJsonObject {
                                        put("op", "PARAMETER")
                                        put("parameterIndex", 1)
                                    },
                                )
                                put("right", buildJsonObject { put("op", "TIME") })
                            },
                        )
                        put(
                            "y",
                            buildJsonObject {
                                put("op", "CONSTANT")
                                put("value", 0.0)
                            },
                        )
                    },
                )
                add(
                    buildJsonObject {
                        put("kind", "path")
                        put("targetIndex", 1)
                    },
                )
                add(
                    buildJsonObject {
                        put("kind", "metric")
                        put("label", "位置")
                        put(
                            "value",
                            buildJsonObject {
                                put("op", "MULTIPLY")
                                put(
                                    "left",
                                    buildJsonObject {
                                        put("op", "PARAMETER")
                                        put("parameterIndex", 1)
                                    },
                                )
                                put("right", buildJsonObject { put("op", "TIME") })
                            },
                        )
                        put("unit", "m")
                    },
                )
            },
        )
        put("durationSeconds", 3.0)
        put("showAxes", true)
        put("xUnit", "m")
    }

    private suspend fun executeTutorPayload(
        payload: String,
        input: TutorPlanInput = tutorInput(),
    ): List<ModelGatewayEvent> {
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor must not open image assets") },
            transport = modelTransport { _, _, _ -> ModelHttpResponse(200, envelope(payload)) },
            clock = { AUTHORIZATION_NOW },
        )
        return gateway.execute(authorizedTutor(gateway, input)).toList()
    }

    private suspend fun parsedTutorResponse(
        payload: String,
        explanationMode: TutorExplanationMode = TutorExplanationMode.DIRECT,
    ): TutorRespondOutput =
        executeTutorRespondPayload(
            payload = payload,
            input = tutorRespondInput().copy(explanationMode = explanationMode),
        ).last()
            .let { it as ModelGatewayEvent.Completed }
            .output as TutorRespondOutput

    private suspend fun executeTutorRespondPayload(
        payload: String,
        input: TutorRespondInput = tutorRespondInput(),
    ): List<ModelGatewayEvent> {
        val gateway = OpenAiCompatibleModelGateway(
            configurationStore = FakeConfigurationStore(CONFIGURATION),
            assetSource = assetSource { _, _ -> error("Tutor response must not open image assets") },
            transport = modelTransport { _, _, _ -> ModelHttpResponse(200, envelope(payload)) },
            clock = { AUTHORIZATION_NOW },
        )
        return gateway.execute(authorizedTutorRespond(gateway, input)).toList()
    }

    private fun stableTutorSuffix(input: TutorPlanInput): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                "${input.sessionId}\n${input.draftRevisionNumber}\n${input.cycleOrdinal}\n${input.turnOrdinal}"
                    .toByteArray(StandardCharsets.UTF_8),
            )
            .joinToString("") { "%02x".format(it) }
            .take(20)

    private fun stableTutorRespondSuffix(input: TutorRespondInput): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                "${input.sessionId}\n${input.draftRevisionNumber}\n${input.responseOrdinal}"
                    .toByteArray(StandardCharsets.UTF_8),
            )
            .joinToString("") { "%02x".format(it) }
            .take(20)

    private fun organizationPayload(): String = Json.encodeToString(
        buildJsonObject {
            put("summaryMarkdown", "这是一道利用导数判断单调性的题。")
            put("reviewPriorityMarkdown", "这道题综合使用导数符号与单调性，适合近期复习。")
            put("schemaVersion", 2)
            put("targetedEvidenceLabels", buildJsonArray {})
            put(
                "classifications",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("dimension", "KNOWLEDGE")
                            put("displayName", "利用导数研究函数单调性")
                            put("rationaleMarkdown", "核心步骤是求导并判断符号。")
                            put("confidence", 0.94)
                        },
                    )
                },
            )
            put(
                "relations",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("targetAlias", "candidate-1")
                            put("kind", "VARIANT_OF")
                            put("rationaleMarkdown", "两题共享导数符号分析。")
                            put("confidence", 0.82)
                        },
                    )
                },
            )
            put(
                "atomicKnowledge",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("referenceId", "atom-1")
                            put("canonicalName", "根据导数符号判断函数单调性")
                            put("aliases", buildJsonArray {})
                            put("kind", "REASONING")
                            put("parentKnowledgeDisplayName", "利用导数研究函数单调性")
                            put("existingAlias", "knowledge-1")
                            put("prerequisiteReferenceIds", buildJsonArray {})
                            put("observableOutcomeMarkdown", "能由导数符号确定函数增减区间。")
                            put("boundaryMarkdown", "不包含求导公式的机械计算。")
                            put("confidence", 0.94)
                        },
                    )
                },
            )
            put(
                "stepAttributions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("stepOrdinal", 1)
                            put("stepSummaryMarkdown", "求导后判断导数符号。")
                            put(
                                "atomicReferenceIds",
                                buildJsonArray { add(JsonPrimitive("atom-1")) },
                            )
                        },
                    )
                },
            )
            put("groundingRequests", buildJsonArray {})
        },
    )

    private fun organizationV3Payload(): String = Json.encodeToString(
        buildJsonObject {
            put("summaryMarkdown", "这是一道利用导数判断单调性的题。")
            put("reviewPriorityMarkdown", "适合近期复习作答中的符号判断。")
            put("schemaVersion", 3)
            put(
                "problemFamily",
                buildJsonObject {
                    put("familyKey", "monotonic_sign_family")
                    put("rationaleMarkdown", "同题不同录入与近似变式归入同一族。")
                    put("confidence", 0.93)
                },
            )
            put("targetedEvidenceLabels", buildJsonArray {})
            put(
                "classifications",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("dimension", "KNOWLEDGE")
                            put("displayName", "利用导数研究函数单调性")
                            put("rationaleMarkdown", "核心步骤是根据导数符号判断增减。")
                            put("confidence", 0.94)
                        },
                    )
                },
            )
            put("relations", buildJsonArray {})
            put(
                "atomicKnowledge",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("referenceId", "atom-1")
                            put("canonicalName", "根据导数符号判断函数单调性")
                            put("aliases", buildJsonArray {})
                            put("kind", "REASONING")
                            put("parentKnowledgeDisplayName", "利用导数研究函数单调性")
                            put("existingAlias", "knowledge-1")
                            put("prerequisiteReferenceIds", buildJsonArray {})
                            put("observableOutcomeMarkdown", "能由导数符号确定函数增减区间。")
                            put("boundaryMarkdown", "不包含求导公式的机械计算。")
                            put("confidence", 0.94)
                        },
                    )
                },
            )
            put(
                "stepAttributions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("stepOrdinal", 1)
                            put("stepSummaryMarkdown", "根据导数符号判断单调区间。")
                            put(
                                "atomicReferenceIds",
                                buildJsonArray { add(JsonPrimitive("atom-1")) },
                            )
                        },
                    )
                },
            )
            put("groundingRequests", buildJsonArray {})
            put(
                "errorAttributionCandidates",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("resolutionStatus", "RESOLVED")
                            put("rationaleMarkdown", "手写作答把导数为负的区间判断成递增区间。")
                            put("confidence", 0.92)
                            put("stepOrdinal", 1)
                            put("atomicReferenceId", "atom-1")
                            put(
                                "evidenceRefs",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put(
                                                "blockAlias",
                                                "confirmed-question-block-1",
                                            )
                                            put("sourceAlias", "source-1")
                                            put("evidenceKind", "STUDENT_WORK")
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        },
    )

    private class BlockingBeforeEnqueueTransport(
        private val response: ModelHttpResponse,
    ) : ModelHttpTransport {
        val endpointPrepared = CompletableDeferred<Unit>()
        val continueToEnqueue = CompletableDeferred<Unit>()
        var networkEnqueueCount = 0
            private set

        override suspend fun post(
            baseUrl: String,
            apiKey: CharArray,
            requestBody: String,
            beforeEnqueue: suspend () -> Unit,
        ): ModelHttpResponse {
            endpointPrepared.complete(Unit)
            continueToEnqueue.await()
            beforeEnqueue()
            networkEnqueueCount += 1
            return response
        }
    }

    private fun visualGivenVariablePayload() = buildJsonObject {
        put("variableId", "distance")
        put("label", "距离")
        put("value", 3.0)
        put("unit", "m")
        put("dimension", "LENGTH")
        put("source", "GIVEN")
        put("display", true)
        put(
            "proof",
            buildJsonObject {
                put("type", "given_source_fact")
                put("sourceFactId", "visual-fact-distance")
            },
        )
    }

    private class RecordingStreamingTransport(
        private val streamEvents: List<ModelHttpStreamEvent>,
        private val postResponse: ModelHttpResponse? = null,
    ) : StreamingModelHttpTransport {
        val streamBodies = mutableListOf<String>()
        val postBodies = mutableListOf<String>()
        var authorizationChecks = 0
            private set

        override suspend fun post(
            baseUrl: String,
            apiKey: CharArray,
            requestBody: String,
            beforeEnqueue: suspend () -> Unit,
        ): ModelHttpResponse {
            beforeEnqueue()
            authorizationChecks += 1
            postBodies += requestBody
            return checkNotNull(postResponse) { "Unexpected legacy retry" }
        }

        override fun stream(
            baseUrl: String,
            apiKey: CharArray,
            requestBody: String,
            beforeEnqueue: suspend () -> Unit,
        ): Flow<ModelHttpStreamEvent> = flow {
            beforeEnqueue()
            authorizationChecks += 1
            streamBodies += requestBody
            streamEvents.forEach { event -> emit(event) }
        }
    }

    private class FakeConfigurationStore(
        snapshot: ModelConfigurationSnapshot,
        private var credentialAvailable: Boolean = true,
    ) : ModelConfigurationStore {
        val state = MutableStateFlow(snapshot)
        private var credentialSecret = "secret"
        override val configuration: Flow<ModelConfigurationSnapshot> = state

        override suspend fun save(
            update: ModelConfigurationUpdate,
            apiKey: ModelApiKey,
        ): ModelConfigurationMutationResult = error("Not used")

        override suspend fun rotateApiKey(apiKey: ModelApiKey): ModelConfigurationMutationResult =
            error("Not used")

        override suspend fun readCredential(): ModelCredentialReadResult =
            if (credentialAvailable) {
                ModelCredentialReadResult.Available(
                    state.value,
                    ModelApiKey.from(credentialSecret.toCharArray()),
                )
            } else {
                ModelCredentialReadResult.Missing
            }

        override suspend fun clear(): ModelConfigurationMutationResult = error("Not used")

        fun clearCredential() {
            credentialAvailable = false
        }

        fun rotateCredential() {
            val rotatedVersion = "${state.value.configurationVersion}-rotated"
            val rotatedUpdatedAt = state.value.updatedAtEpochMillis + 1
            state.value = state.value.copy(
                updatedAtEpochMillis = rotatedUpdatedAt,
                configurationVersion = rotatedVersion,
                capabilityVerification = state.value.capabilityVerification?.copy(
                    configurationVersion = rotatedVersion,
                    configurationUpdatedAtEpochMillis = rotatedUpdatedAt,
                ),
            )
            credentialSecret = "rotated-secret"
        }
    }

    private fun testKnowledgeCatalogProvenance() =
        KnowledgeBaseCatalogProvenance(
            packId = "pack",
            knowledgePackVersion = "pack-v1",
            taxonomyVersion = "math-v1",
            manifestFingerprint = SHA,
            activationGeneration = 1L,
        )

    private companion object {
        const val DRAFT_ID = "draft-1"
        const val ASSET_ID = "asset-1"
        const val TUTOR_SESSION_ID = "tutor-session-secret-local-id"
        const val ORGANIZATION_PROBLEM_ID = "problem-current-secret-local-id"
        const val RELATED_PROBLEM_ID = "problem-related-secret-local-id"
        const val REQUEST_OCCURRED_AT = 1_000_000L
        const val AUTHORIZATION_APPROVED_AT = 1_000_001L
        const val AUTHORIZATION_NOW = 1_000_002L
        const val TUTOR_LOBBY_CONTEXT_PREFIX = "tutorLobbyContext："
        const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val IMAGE = byteArrayOf(1, 2, 3, 4)
        val CONFIGURATION = ModelConfigurationSnapshot(
            provider = "兼容模型服务",
            baseUrl = "https://api.example.com/v1",
            modelId = "vision-model",
            isConfigured = true,
            updatedAtEpochMillis = 123,
            configurationVersion = "test-configuration-v1",
            capabilityVerification = ModelCapabilityVerification(
                provider = "兼容模型服务",
                baseUrl = "https://api.example.com/v1",
                modelId = "vision-model",
                configurationVersion = "test-configuration-v1",
                configurationUpdatedAtEpochMillis = 123,
                supportsImageInput = true,
                supportsStructuredOutput = true,
                testedAtEpochMillis = 124,
            ),
        )
    }
}
