package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureAssessment
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentAction
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentIssue
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentIssueCode
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentSeverity
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureModelTaskUiPolicyTest {
    @Test
    fun cancelledAssessmentEndsHonestlyWithoutForcingManualEntry() {
        val assessment = cancelledSnapshot(assessmentRequest())

        assertEquals("这道题暂时没准备好", captureModelTaskTitle(assessment, null))
        assertEquals(
            "处理已停止，原图已经保存，可以重新处理。",
            modelPipelineDetail(assessment, null),
        )
        assertEquals(
            CaptureModelRecoveryAction.RETRY,
            captureModelRecoveryAction(assessment, null),
        )
    }

    @Test
    fun cancelledParseEndsHonestlyWithoutForcingManualEntry() {
        val assessment = waitingSnapshot(assessmentRequest())
        val parse = cancelledSnapshot(parseRequest())

        assertEquals("这道题暂时没准备好", captureModelTaskTitle(assessment, parse))
        assertEquals(
            "处理已停止，原图已经保存，可以重新处理。",
            modelPipelineDetail(assessment, parse),
        )
    }

    @Test
    fun configurationAndAuthenticationFailuresOpenSettings() {
        listOf(
            ModelFailureCode.MODEL_NOT_CONFIGURED,
            ModelFailureCode.AUTHENTICATION_FAILED,
            ModelFailureCode.PROVIDER_CAPABILITY_MISSING,
        ).forEach { code ->
            val failed = failureSnapshot(assessmentRequest(), code)
            assertEquals(
                CaptureModelRecoveryAction.OPEN_SETTINGS,
                captureModelRecoveryAction(failed, null),
            )
        }
    }

    @Test
    fun missingOrStaleSendApprovalReturnsToTheApprovalCard() {
        val assessment = waitingSnapshot(assessmentRequest())
        val required = failureSnapshot(
            assessmentRequest(),
            ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED,
        )
        val staleParse = failureSnapshot(
            parseRequest(),
            ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
        )
        val authentication = failureSnapshot(
            parseRequest(),
            ModelFailureCode.AUTHENTICATION_FAILED,
        )

        assertTrue(captureEgressApprovalMustBeRenewed(required, null))
        assertTrue(captureEgressApprovalMustBeRenewed(assessment, staleParse))
        assertFalse(captureEgressApprovalMustBeRenewed(assessment, authentication))
    }

    @Test
    fun temporaryFailureRetriesTheActiveStage() {
        val assessment = waitingSnapshot(assessmentRequest())
        val parse = failureSnapshot(parseRequest(), ModelFailureCode.NETWORK_UNAVAILABLE)

        assertEquals(
            CaptureModelRecoveryAction.RETRY,
            captureModelRecoveryAction(assessment, parse),
        )
    }

    @Test
    fun nonSettingsPermanentFailureCanStartFreshProcessing() {
        val failed = failureSnapshot(
            request = assessmentRequest(),
            code = ModelFailureCode.INVALID_RESPONSE,
            status = ModelTaskStatus.PERMANENT_FAILURE,
        )

        assertEquals(
            CaptureModelRecoveryAction.RETRY,
            captureModelRecoveryAction(failed, null),
        )
    }

    @Test
    fun incompleteImageOnlyOffersCaptureRecovery() {
        val incomplete = waitingSnapshot(assessmentRequest()).copy(
            status = ModelTaskStatus.SUCCEEDED,
            stage = ModelTaskStage.COMPLETE,
            output = CaptureAssessmentOutput(
                CaptureAssessment(
                    decision = CaptureAssessmentDecision.NEED_MORE_IMAGE,
                    issues = listOf(
                        CaptureAssessmentIssue(
                            code = CaptureAssessmentIssueCode.MISSING_OPTIONS,
                            severity = CaptureAssessmentSeverity.BLOCKING,
                            message = "选项没有拍全",
                        ),
                    ),
                    suggestedActions = listOf(CaptureAssessmentAction.ADD_IMAGE),
                    modelVersion = "fixture-v1",
                ),
            ),
        )

        assertEquals(
            CaptureModelRecoveryAction.NONE,
            captureModelRecoveryAction(incomplete, null),
        )
    }

    @Test
    fun multipleQuestionsAreHandledLocallyWithoutRetakeRecovery() {
        val split = waitingSnapshot(assessmentRequest()).copy(
            status = ModelTaskStatus.SUCCEEDED,
            stage = ModelTaskStage.COMPLETE,
            output = CaptureAssessmentOutput(
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
                        NormalizedSourceRegion(0.05, 0.05, 0.95, 0.45),
                        NormalizedSourceRegion(0.05, 0.55, 0.95, 0.95),
                    ),
                    modelVersion = "fixture-v1",
                ),
            ),
        )

        assertEquals(CaptureModelRecoveryAction.NONE, captureModelRecoveryAction(split, null))
        assertEquals("正在整理这一页", captureModelTaskTitle(split, null))
        assertTrue(modelPipelineDetail(split, null).contains("分别整理"))
    }

    @Test
    fun pendingWorkUsesOneCalmOutcomeMessage() {
        assertEquals("正在准备这道题", captureModelTaskTitle(null, null))
        assertEquals(
            "原图已经保存，可以稍后回来继续。",
            modelPipelineDetail(null, null),
        )
    }

    private fun cancelledSnapshot(request: ModelTaskRequest) = snapshot(
        request = request,
        status = ModelTaskStatus.CANCELLED,
    )

    private fun waitingSnapshot(request: ModelTaskRequest) = snapshot(
        request = request,
        status = ModelTaskStatus.WAITING_FOR_MODEL,
    )

    private fun failureSnapshot(
        request: ModelTaskRequest,
        code: ModelFailureCode,
        status: ModelTaskStatus = ModelTaskStatus.RETRYABLE_FAILURE,
    ) = snapshot(
        request = request,
        status = status,
        failure = ModelTaskFailure(code, "暂时没有完成", retryable = true),
    )

    private fun snapshot(
        request: ModelTaskRequest,
        status: ModelTaskStatus,
        failure: ModelTaskFailure? = null,
    ) = ModelTaskSnapshot(
        taskId = "task-${request.requestId}",
        request = request,
        requestFingerprint = ModelTaskFingerprint.of(request),
        status = status,
        stateVersion = 2,
        stage = ModelTaskStage.WAITING,
        userMessage = "任务已结束",
        attemptCount = 0,
        failure = failure,
        createdAtEpochMillis = 100,
        updatedAtEpochMillis = 200,
    )

    private fun assessmentRequest() = ModelTaskRequest(
        requestId = "capture-assess:cancelled-ui-test",
        input = CaptureAssessmentInput(
            draftId = DRAFT_ID,
            sourceAssetId = SOURCE_ASSET_ID,
            origin = CaptureAssessmentOrigin.TUTOR,
            imageWidth = 1_080,
            imageHeight = 1_440,
        ),
        occurredAtEpochMillis = 100,
    )

    private fun parseRequest() = ModelTaskRequest(
        requestId = "capture-parse:cancelled-ui-test",
        input = CaptureParseInput(
            draftId = DRAFT_ID,
            origin = CaptureAssessmentOrigin.TUTOR,
            basisRevisionNumber = 1,
            sourceAssets = listOf(
                CaptureSourceAssetRef(
                    assetId = SOURCE_ASSET_ID,
                    sha256 = "a".repeat(64),
                    width = 1_080,
                    height = 1_440,
                    pageIndex = 0,
                ),
            ),
            assessmentRequestId = "capture-assess:cancelled-ui-test",
        ),
        occurredAtEpochMillis = 100,
    )

    private companion object {
        const val DRAFT_ID = "draft-cancelled-ui-test"
        const val SOURCE_ASSET_ID = "asset-cancelled-ui-test"
    }
}
