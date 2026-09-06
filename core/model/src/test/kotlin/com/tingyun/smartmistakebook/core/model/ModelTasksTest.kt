package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent.Progress

class ModelTasksTest {
    @Test
    fun remoteDispatchBudgetAllowsUpToTheConfiguredLimitOnly() {
        val max = ModelTaskRemoteDispatchPolicy.MAX_DISPATCHES
        // attemptCount 从 0 起，可调度到 max-1；达到 max 后一律拒（防无限派遣）。
        (0 until max).forEach { attempt ->
            assertEquals("attemptCount=$attempt 应可调度", true, ModelTaskRemoteDispatchPolicy.canSchedule(attemptCount = attempt))
        }
        (max until max + 2).forEach { attempt ->
            assertEquals("attemptCount=$attempt 应被拒", false, ModelTaskRemoteDispatchPolicy.canSchedule(attemptCount = attempt))
        }
    }

    @Test
    fun requestAndOutputCodecsRoundTripStrongTypes() {
        val request = captureRequest()
        val output = CaptureAssessmentOutput(
            CaptureAssessment(
                decision = CaptureAssessmentDecision.NEED_MORE_IMAGE,
                issues = listOf(
                    CaptureAssessmentIssue(
                        code = CaptureAssessmentIssueCode.MISSING_OPTIONS,
                        severity = CaptureAssessmentSeverity.BLOCKING,
                        region = NormalizedSourceRegion(0.7, 0.1, 1.0, 0.9),
                        message = "右侧 C、D 选项没有拍全",
                    ),
                ),
                suggestedActions = listOf(
                    CaptureAssessmentAction.ADD_IMAGE,
                    CaptureAssessmentAction.RECAPTURE,
                ),
                modelVersion = "fake/capture-v1",
            ),
        )

        assertEquals(request, ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(request)))
        assertEquals(output, ModelTaskCodec.decodeOutput(ModelTaskCodec.encodeOutput(output)))
    }

    @Test
    fun captureParseContractRoundTripsAtTheCurrentSchemaVersion() {
        val request = captureParseRequest()
        val output = captureParseOutput()

        val decodedRequest = ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(request))
        val decodedOutput = ModelTaskCodec.decodeOutput(ModelTaskCodec.encodeOutput(output))

        assertEquals(ModelTaskRequest.CURRENT_SCHEMA_VERSION, decodedRequest.schemaVersion)
        assertEquals(request, decodedRequest)
        assertEquals(output, decodedOutput)
        assertTrue(ModelTaskCompletionValidator.validate(decodedRequest, decodedOutput).isEmpty())
    }

    @Test
    fun captureParseBindsOneAssessmentToEveryOrderedSourcePage() {
        val first = (captureParseRequest().input as CaptureParseInput).sourceAssets.single()
        val input = CaptureParseInput(
            draftId = "draft-1",
            origin = CaptureAssessmentOrigin.TUTOR,
            basisRevisionNumber = 3,
            sourceAssets = listOf(
                first,
                CaptureSourceAssetRef(
                    assetId = "asset-2",
                    sha256 = "b".repeat(64),
                    width = 900,
                    height = 1_200,
                    pageIndex = 1,
                ),
            ),
            assessmentRequestId = "assessment-page-0",
            assessmentRequestIds = listOf("assessment-page-0", "assessment-page-1"),
        )

        val decoded = ModelTaskCodec.decodeRequest(
            ModelTaskCodec.encodeRequest(
                ModelTaskRequest(
                    requestId = "capture-parse:multi",
                    input = input,
                    occurredAtEpochMillis = 200,
                ),
            ),
        ).input as CaptureParseInput

        assertEquals(listOf(0, 1), decoded.sourceAssets.map { it.pageIndex })
        assertEquals(
            listOf("assessment-page-0", "assessment-page-1"),
            decoded.assessmentRequestIds,
        )
        assertThrows(IllegalArgumentException::class.java) {
            input.copy(assessmentRequestIds = listOf("assessment-page-0"))
        }
    }

    @Test
    fun completionRejectsOutputsForTheWrongRequestKind() {
        val assessmentOutput = CaptureAssessmentOutput(
            CaptureAssessment(
                decision = CaptureAssessmentDecision.PASS,
                issues = emptyList(),
                suggestedActions = emptyList(),
                modelVersion = "fixture/assess-v1",
            ),
        )
        val parseOutput = captureParseOutput()

        assertEquals(
            setOf(ModelTaskCompletionIssueCode.REQUEST_OUTPUT_TYPE_MISMATCH),
            ModelTaskCompletionValidator.validate(captureRequest(), parseOutput).codes(),
        )
        assertEquals(
            setOf(ModelTaskCompletionIssueCode.REQUEST_OUTPUT_TYPE_MISMATCH),
            ModelTaskCompletionValidator.validate(captureParseRequest(), assessmentOutput).codes(),
        )
    }

    @Test
    fun captureParseRejectsEvidenceFromAnotherSourceAsset() {
        val output = captureParseOutput(sourceAssetId = "asset-other")

        assertTrue(
            ModelTaskCompletionIssueCode.SOURCE_ASSET_MISMATCH in
                ModelTaskCompletionValidator.validate(captureParseRequest(), output).codes(),
        )
    }

    @Test
    fun captureParseRejectsModelClaimingUserConfirmation() {
        val output = captureParseOutput(
            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
        )

        assertTrue(
            ModelTaskCompletionIssueCode.MODEL_CANNOT_CONFIRM_USER_REVIEW in
                ModelTaskCompletionValidator.validate(captureParseRequest(), output).codes(),
        )
    }

    @Test
    fun captureParseRejectsModelClaimingLocalPolicyAcceptance() {
        val output = captureParseOutput(
            reviewStatus = QuestionBlockReviewStatus.LOCAL_POLICY_ACCEPTED,
        )

        assertTrue(
            ModelTaskCompletionIssueCode.MODEL_CANNOT_APPLY_LOCAL_POLICY in
                ModelTaskCompletionValidator.validate(captureParseRequest(), output).codes(),
        )
    }

    @Test
    fun captureParseRejectsInvalidDocumentProvenanceAndMissingProducer() {
        val invalidDocument = capturedDocument(
            provenance = QuestionBlockProvenance.LOCAL_OCR,
            producerVersion = null,
        ).copy(
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "different-block",
                    sourceAssetId = "asset-1",
                    sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.LOCAL_OCR,
                    confidence = 0.9,
                    reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
                    producerVersion = null,
                ),
            ),
        )
        val output = CaptureParseOutput(invalidDocument, modelVersion = "fixture/parse-v1")
        val codes = ModelTaskCompletionValidator.validate(captureParseRequest(), output).codes()

        assertTrue(ModelTaskCompletionIssueCode.INVALID_CAPTURE_DOCUMENT in codes)
        assertTrue(ModelTaskCompletionIssueCode.INVALID_CAPTURE_PROVENANCE in codes)
        assertTrue(ModelTaskCompletionIssueCode.MISSING_PRODUCER_VERSION in codes)
    }

    @Test
    fun captureParseBindsDocumentAndEvidenceToTheRequestedRevision() {
        val output = captureParseOutput().let { candidate ->
            candidate.copy(
                capturedDocument = candidate.capturedDocument.copy(
                    document = candidate.capturedDocument.document.copy(id = "other-draft"),
                    blockEvidence = candidate.capturedDocument.blockEvidence.map {
                        it.copy(producerVersion = "model/other-version")
                    },
                ),
            )
        }
        val codes = ModelTaskCompletionValidator.validate(captureParseRequest(), output).codes()

        assertTrue(ModelTaskCompletionIssueCode.DRAFT_DOCUMENT_MISMATCH in codes)
        assertTrue(ModelTaskCompletionIssueCode.PRODUCER_VERSION_MISMATCH in codes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun captureParseRejectsUnboundedModelVersion() {
        CaptureParseOutput(
            capturedDocument = capturedDocument(),
            modelVersion = "v".repeat(257),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun captureParseRejectsAStaleOrUnversionedBasis() {
        val input = captureParseRequest().input as CaptureParseInput

        input.copy(basisRevisionNumber = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun captureParseRejectsAnUnboundSourceAssetHash() {
        val input = captureParseRequest().input as CaptureParseInput

        input.copy(sourceAssets = input.sourceAssets.map { it.copy(sha256 = "not-a-sha256") })
    }

    @Test
    fun fingerprintChangesWhenInputChanges() {
        val request = captureRequest()
        val changed = request.copy(
            input = (request.input as CaptureAssessmentInput).copy(imageWidth = 1440),
        )

        assertNotEquals(ModelTaskFingerprint.of(request), ModelTaskFingerprint.of(changed))
    }

    @Test
    fun logicalOperationFingerprintIgnoresTransportAuthorizationProviderAndTime() {
        val original = captureRequest().copy(
            egressManifest = operationManifest(
                authorizationId = "authorization-1",
                providerId = "provider-1",
                modelId = "model-1",
                configurationVersion = "config-1",
                approvedAtEpochMillis = 110,
            ),
        )
        val reenveloped = original.copy(
            requestId = "capture-assess:request-2",
            occurredAtEpochMillis = 200,
            egressManifest = operationManifest(
                authorizationId = "authorization-2",
                providerId = "provider-2",
                modelId = "model-2",
                configurationVersion = "config-2",
                approvedAtEpochMillis = 210,
            ),
        )
        val changedInput = reenveloped.copy(
            input = (reenveloped.input as CaptureAssessmentInput).copy(imageWidth = 1440),
        )

        assertNotEquals(ModelTaskFingerprint.of(original), ModelTaskFingerprint.of(reenveloped))
        assertEquals(
            ModelTaskLogicalOperationFingerprint.of(original),
            ModelTaskLogicalOperationFingerprint.of(reenveloped),
        )
        assertNotEquals(
            ModelTaskLogicalOperationFingerprint.of(original),
            ModelTaskLogicalOperationFingerprint.of(changedInput),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun providerMetadataRejectsBidirectionalControlCharacters() {
        ProviderCapabilitySnapshot(
            providerId = "provider-1",
            providerDisplayName = "可信模型\u202E伪装",
            modelId = "model-1",
            supportedTasks = setOf(ModelTaskKind.CAPTURE_ASSESS),
            supportsImageInput = true,
            supportsStructuredOutput = true,
            supportsStreaming = false,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun gatewayMessagesRejectUnsafeControlCharacters() {
        ModelTaskFailure(
            code = ModelFailureCode.INVALID_RESPONSE,
            message = "响应包含不可见字符\u0000",
            retryable = false,
        )
    }

    @Test
    fun progressTruncatesLongReplyInsteadOfRejecting() {
        val longReply = "答".repeat(2400)
        // T1 解耦：流式正文一旦超过安全边界，Progress 应截断到 MAX_PROGRESS_MESSAGE_CHARS
        // 而非让整个模型任务失败（原 >500 即抛 IllegalArgumentException）。
        val event = Progress.of(longReply)
        val expectedLength = MAX_PROGRESS_MESSAGE_CHARS
        assertTrue(
            "长回复截断到 $expectedLength，实际 ${event.userMessage.length}",
            event.userMessage.length == expectedLength,
        )
        assertEquals(longReply.take(expectedLength), event.userMessage)
    }

    @Test
    fun progressKeepsShortReplyUnchanged() {
        val shortReply = "简短回复"
        val event = Progress.of(shortReply)
        assertEquals(shortReply, event.userMessage)
    }

    @Test
    fun progressOfLongReplyDoesNotThrow() {
        // 旧行为（C4）：>500 的正文直接 Progress.init 抛 IllegalArgumentException →
        // 网关掐成 INVALID_RESPONSE，整轮任务失败、正文不呈现。T1 解耦后必须不再抛。
        val veryLongReply = "详".repeat(5000)
        val event = Progress.of(veryLongReply)
        assertEquals(MAX_PROGRESS_MESSAGE_CHARS, event.userMessage.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun taskSnapshotRejectsFingerprintFromAnotherRequest() {
        val request = captureRequest()
        val otherRequest = request.copy(
            input = (request.input as CaptureAssessmentInput).copy(imageWidth = 1440),
        )

        ModelTaskSnapshot(
            taskId = "task-fingerprint-mismatch",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(otherRequest),
            status = ModelTaskStatus.QUEUED,
            stateVersion = 0,
            stage = ModelTaskStage.WAITING,
            userMessage = "等待模型处理",
            attemptCount = 0,
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 100,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun blockedAssessmentRequiresAnExplanation() {
        CaptureAssessment(
            decision = CaptureAssessmentDecision.RECAPTURE,
            issues = emptyList(),
            suggestedActions = listOf(CaptureAssessmentAction.RECAPTURE),
            modelVersion = "fake/capture-v1",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun passingAssessmentCannotHideABlockingRecaptureRequest() {
        CaptureAssessment(
            decision = CaptureAssessmentDecision.PASS,
            issues = listOf(
                CaptureAssessmentIssue(
                    code = CaptureAssessmentIssueCode.OCCLUDED,
                    severity = CaptureAssessmentSeverity.BLOCKING,
                    message = "题干被遮挡",
                ),
            ),
            suggestedActions = listOf(CaptureAssessmentAction.RECAPTURE),
            modelVersion = "fake/capture-v1",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun multipleIndependentQuestionsCannotContinueAsAReviewWarning() {
        CaptureAssessment(
            decision = CaptureAssessmentDecision.PASS,
            issues = listOf(
                CaptureAssessmentIssue(
                    code = CaptureAssessmentIssueCode.MULTIPLE_QUESTIONS,
                    severity = CaptureAssessmentSeverity.REVIEW,
                    message = "画面中有两道独立题目",
                ),
            ),
            suggestedActions = listOf(CaptureAssessmentAction.CONTINUE_ANYWAY),
            modelVersion = "fake/capture-v1",
        )
    }

    @Test
    fun multipleIndependentQuestionsRequireBoundedSplitRegions() {
        CaptureAssessment(
            decision = CaptureAssessmentDecision.SPLIT,
            issues = listOf(
                CaptureAssessmentIssue(
                    code = CaptureAssessmentIssueCode.MULTIPLE_QUESTIONS,
                    severity = CaptureAssessmentSeverity.BLOCKING,
                    message = "画面中有两道独立题目",
                ),
            ),
            suggestedActions = emptyList(),
            questionRegions = listOf(
                NormalizedSourceRegion(left = 0.05, top = 0.05, right = 0.95, bottom = 0.45),
                NormalizedSourceRegion(left = 0.05, top = 0.52, right = 0.95, bottom = 0.95),
            ),
            modelVersion = "fake/capture-v1",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun splitAssessmentCannotOmitQuestionRegions() {
        CaptureAssessment(
            decision = CaptureAssessmentDecision.SPLIT,
            issues = listOf(
                CaptureAssessmentIssue(
                    code = CaptureAssessmentIssueCode.MULTIPLE_QUESTIONS,
                    severity = CaptureAssessmentSeverity.BLOCKING,
                    message = "画面中有两道独立题目",
                ),
            ),
            suggestedActions = emptyList(),
            modelVersion = "fake/capture-v1",
        )
    }

    @Test
    fun legacyAssessmentRemainsReadableAfterTheMultipleQuestionGateUpgrade() {
        CaptureAssessment(
            decision = CaptureAssessmentDecision.PASS,
            issues = listOf(
                CaptureAssessmentIssue(
                    code = CaptureAssessmentIssueCode.MULTIPLE_QUESTIONS,
                    severity = CaptureAssessmentSeverity.REVIEW,
                    message = "旧任务仅记录为待复核",
                ),
            ),
            suggestedActions = listOf(CaptureAssessmentAction.CONTINUE_ANYWAY),
            modelVersion = "legacy/capture-v1",
            schemaVersion = 1,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun successfulTaskRequiresOutput() {
        ModelTaskSnapshot(
            taskId = "task-1",
            request = captureRequest(),
            requestFingerprint = ModelTaskFingerprint.of(captureRequest()),
            status = ModelTaskStatus.SUCCEEDED,
            stateVersion = 3,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "模型已完成图片检查",
            attemptCount = 1,
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 200,
        )
    }

    private fun captureRequest() = ModelTaskRequest(
        requestId = "capture-assess:request-1",
        input = CaptureAssessmentInput(
            draftId = "draft-1",
            sourceAssetId = "asset-1",
            origin = CaptureAssessmentOrigin.TUTOR,
            imageWidth = 1080,
            imageHeight = 1440,
        ),
        occurredAtEpochMillis = 100,
    )

    private fun captureParseRequest() = ModelTaskRequest(
        requestId = "capture-parse:request-1",
        input = CaptureParseInput(
            draftId = "draft-1",
            origin = CaptureAssessmentOrigin.TUTOR,
            basisRevisionNumber = 3,
            sourceAssets = listOf(
                CaptureSourceAssetRef(
                    assetId = "asset-1",
                    sha256 = "a".repeat(64),
                    width = 1080,
                    height = 1440,
                    pageIndex = 0,
                    selectedRegion = NormalizedSourceRegion(0.05, 0.1, 0.95, 0.9),
                ),
            ),
            assessmentRequestId = "capture-assess:request-1",
        ),
        occurredAtEpochMillis = 101,
    )

    private fun captureParseOutput(
        sourceAssetId: String = "asset-1",
        reviewStatus: QuestionBlockReviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
    ) = CaptureParseOutput(
        capturedDocument = capturedDocument(
            sourceAssetId = sourceAssetId,
            reviewStatus = reviewStatus,
        ),
        modelVersion = "fixture/parse-v1",
    )

    private fun operationManifest(
        authorizationId: String,
        providerId: String,
        modelId: String,
        configurationVersion: String,
        approvedAtEpochMillis: Long,
    ) = ModelEgressManifest(
        authorizationId = authorizationId,
        subjectId = "draft-1",
        purpose = ModelEgressPurpose.CAPTURE_TO_DOCUMENT,
        authorizedTaskKinds = setOf(
            ModelTaskKind.CAPTURE_ASSESS,
            ModelTaskKind.CAPTURE_PARSE,
        ),
        providerId = providerId,
        modelId = modelId,
        providerConfigurationVersion = configurationVersion,
        promptPolicyVersion = ModelPromptPolicyVersions.CAPTURE_DOCUMENT,
        approvedAtEpochMillis = approvedAtEpochMillis,
        assets = listOf(
            ModelEgressAssetGrant(
                assetId = "asset-1",
                sha256 = "a".repeat(64),
                byteSize = 1_024,
                width = 1_080,
                height = 1_440,
            ),
        ),
        disclosedData = ModelEgressManifest.CAPTURE_IMAGE_DISCLOSURE,
        prohibitedData = ModelEgressManifest.CAPTURE_PROHIBITED_DATA,
    )

    private fun capturedDocument(
        sourceAssetId: String = "asset-1",
        provenance: QuestionBlockProvenance = QuestionBlockProvenance.MODEL_DOCUMENT_PARSE,
        reviewStatus: QuestionBlockReviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
        producerVersion: String? = "fixture/parse-v1",
    ) = CapturedQuestionDocument(
        document = QuestionDocument(
            id = "document-draft-1",
            title = "函数单调性",
            blocks = listOf(
                ContentBlock.Paragraph("stem", "求函数 \$f(x)=x^2\$ 的单调区间。"),
            ),
        ),
        blockEvidence = listOf(
            QuestionBlockEvidence(
                blockId = "stem",
                sourceAssetId = sourceAssetId,
                sourceRegion = NormalizedSourceRegion(0.05, 0.1, 0.95, 0.9),
                writingLayer = WritingLayer.PRINTED,
                provenance = provenance,
                confidence = 0.92,
                reviewStatus = reviewStatus,
                producerVersion = producerVersion,
            ),
        ),
    )

    private fun List<ModelTaskCompletionIssue>.codes() = map { it.code }.toSet()
}
