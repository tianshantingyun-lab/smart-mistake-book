package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.domain.ModelGateway
import com.tingyun.smartmistakebook.core.model.CaptureAssessment
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.CaptureParseOutput
import com.tingyun.smartmistakebook.core.model.CapturePageRelation
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ImagePipelineClassifyInput
import com.tingyun.smartmistakebook.core.model.ImagePipelineClassifyOutput
import com.tingyun.smartmistakebook.core.model.ImagePipelineProblemKind
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorDebriefInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Debug-only provider used to exercise real task persistence and GUI states without uploading. */
class FakeModelGateway(
    private val stepDelayMillis: Long = DEFAULT_STEP_DELAY_MILLIS,
) : ModelGateway {
    init {
        require(stepDelayMillis >= 0) { "Fake model delay must not be negative" }
    }

    override suspend fun capabilities(): ProviderCapabilitySnapshot = CAPABILITIES

    override fun execute(execution: ModelGatewayExecution): Flow<ModelGatewayEvent> = flow {
        val request = execution.request
        if (!CAPABILITIES.supports(request.input.kind)) {
            emit(
                ModelGatewayEvent.Failed(
                    ModelTaskFailure(
                        code = ModelFailureCode.PROVIDER_CAPABILITY_MISSING,
                        message = "演示模型暂不支持这项任务",
                        retryable = false,
                    ),
                ),
            )
            return@flow
        }
        emit(ModelGatewayEvent.Started(CAPABILITIES))
        delay(stepDelayMillis)
        emit(
            ModelGatewayEvent.Progress(
                stage = ModelTaskStage.READING_IMAGE,
                userMessage = if (request.input is CaptureParseInput) {
                    "正在演示印刷与手写分区步骤"
                } else {
                    "正在读题"
                },
            ),
        )
        delay(stepDelayMillis)
        emit(
            ModelGatewayEvent.Progress(
                stage = ModelTaskStage.VALIDATING_OUTPUT,
                userMessage = "正在整理下一步需要确认的内容",
            ),
        )
        delay(stepDelayMillis)
        emit(ModelGatewayEvent.Completed(outputFor(request)))
    }

    private fun outputFor(request: ModelTaskRequest) = when (val input = request.input) {
        is CaptureAssessmentInput -> CaptureAssessmentOutput(
            assessment = CaptureAssessment(
                decision = CaptureAssessmentDecision.PASS,
                issues = emptyList(),
                suggestedActions = emptyList(),
                modelVersion = "demo/capture-assess-v1",
                followingPageRelations = List(input.followingSourceAssets.size) {
                    CapturePageRelation.UNSURE
                },
            ),
        )
        is CaptureParseInput -> {
            val source = input.sourceAssets.first()
            CaptureParseOutput(
                capturedDocument = CapturedQuestionDocument(
                    document = QuestionDocument(
                        id = "document-${input.draftId}",
                        title = "演示结构化题面",
                        blocks = listOf(
                            ContentBlock.Paragraph(
                                id = "stem",
                                markdown = "这只是任务流程演示，不是从当前题图识别出的文字。",
                            ),
                        ),
                    ),
                    blockEvidence = listOf(
                        QuestionBlockEvidence(
                            blockId = "stem",
                            sourceAssetId = source.assetId,
                            sourceRegion = source.selectedRegion
                                ?: com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion(
                                    0.0,
                                    0.0,
                                    1.0,
                                    1.0,
                                ),
                            writingLayer = WritingLayer.PRINTED,
                            provenance = QuestionBlockProvenance.MODEL_DOCUMENT_PARSE,
                            confidence = 0.5,
                            reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
                            producerVersion = "demo/capture-parse-v1",
                        ),
                    ),
                ),
                modelVersion = "demo/capture-parse-v1",
            )
        }
        is ImagePipelineClassifyInput -> ImagePipelineClassifyOutput(
            problemKind = ImagePipelineProblemKind.TEXT_ONLY,
            textMarkdown = "演示题面：仅演示任务流程，不是从当前题图识别出的文字。",
            formulas = emptyList(),
            modelVersion = "demo/image-pipeline-classify-v1",
        )
        is TutorPlanInput -> error("The capture-only demo provider cannot plan tutor turns")
        is TutorDebriefInput -> error("The capture-only demo provider cannot summarize tutor debriefs")
        is TutorLobbyInput -> error("The capture-only demo provider cannot answer tutor lobby messages")
        is TutorRespondInput -> error("The capture-only demo provider cannot answer tutor messages")
        is TutorVisualGenerateInput -> error("The capture-only demo provider cannot generate tutor visuals")
        is TutorVisualReviewInput -> error("The capture-only demo provider cannot review tutor visuals")
        is com.tingyun.smartmistakebook.core.model.KnowledgeQuizInput -> error(
            "The capture-only demo provider cannot compose knowledge quizzes",
        )
        is ProblemOrganizationInput -> error(
            "The capture-only demo provider cannot organize committed problems",
        )
    }

    private companion object {
        const val DEFAULT_STEP_DELAY_MILLIS = 450L
        val CAPABILITIES = ProviderCapabilitySnapshot(
            providerId = "demo-provider",
            providerDisplayName = "演示模型",
            modelId = "capture-pipeline-demo-v1",
            supportedTasks = setOf(
                ModelTaskKind.CAPTURE_ASSESS,
                ModelTaskKind.CAPTURE_PARSE,
                ModelTaskKind.IMAGE_PIPELINE_CLASSIFY,
            ),
            supportsImageInput = true,
            supportsStructuredOutput = true,
            supportsStreaming = true,
            isDemo = true,
            executionLocation = com.tingyun.smartmistakebook.core.model.ModelExecutionLocation.LOCAL_NO_EGRESS,
            providerConfigurationVersion = "debug-fixture-v1",
        )
    }
}

