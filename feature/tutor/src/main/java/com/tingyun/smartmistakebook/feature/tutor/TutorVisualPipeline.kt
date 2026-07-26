package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskRemoteDispatchPolicy
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewOutput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationRequest
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualDocumentCompiler
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualRiskAssessor
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualRiskLevel
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualCacheKey
import com.tingyun.smartmistakebook.core.visual.runtime.containsVisibleIllustrativeValue
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal sealed interface TutorVisualResolution {
    data object Hidden : TutorVisualResolution

    data object Preparing : TutorVisualResolution

    data class Ready(
        val scene: TutorVisualScene,
        val cacheKey: String,
    ) : TutorVisualResolution

    data class Reviewing(
        val generationTask: ModelTaskSnapshot,
        val output: TutorVisualGenerateOutput,
        val reasonCodes: Set<String>,
    ) : TutorVisualResolution

    data class Fallback(
        val reason: TutorVisualFallbackReason,
        val canRetry: Boolean = false,
        val failedTask: ModelTaskSnapshot? = null,
        val reviewCandidate: Reviewing? = null,
    ) : TutorVisualResolution
}

internal enum class TutorVisualFallbackReason {
    TASK_FAILURE,
    DECLINED,
    INVALID_OUTPUT,
    REJECTED,
    VALIDATION_FAILED,
    SOURCE_UNAVAILABLE,
    PROVIDER_UNAVAILABLE,
    REPORTED,
    NOT_STARTED,
}

internal data class TutorVisualWorkSeed(
    val anchor: TutorVisualTurnAnchor,
    val request: TutorVisualGenerationRequest,
    val explanationMarkdown: String,
)

internal fun tutorVisualWorkSeeds(
    planTasks: List<ModelTaskSnapshot>,
    respondTasks: List<ModelTaskSnapshot>,
): List<TutorVisualWorkSeed> = buildList {
    planTasks.forEach { task ->
        val output = task.output as? TutorPlanOutput ?: return@forEach
        val request = output.plan.visualRequest ?: return@forEach
        if (task.status != ModelTaskStatus.SUCCEEDED) return@forEach
        add(
            TutorVisualWorkSeed(
                anchor = TutorVisualTurnAnchor(
                    surface = TutorVisualTurnSurface.PLAN,
                    cycleOrdinal = output.cycleOrdinal,
                    turnOrdinal = output.turnOrdinal,
                ),
                request = request,
                explanationMarkdown = buildString {
                    append(output.plan.openingMarkdown)
                    output.plan.diagnosticItem?.let { item ->
                        append("\n\n").append(item.stemMarkdown)
                        item.promptMarkdown?.let { prompt ->
                            append("\n\n").append(prompt)
                        }
                    }
                },
            ),
        )
    }
    respondTasks.forEach { task ->
        val output = task.output as? TutorRespondOutput ?: return@forEach
        if (task.status != ModelTaskStatus.SUCCEEDED) return@forEach
        val input = task.request.input as? TutorRespondInput ?: return@forEach
        val request = output.visualRequest
            ?: VisualIntent.detect(input.studentMessage)
                ?.takeIf { output.visualScene == null }
                ?.toGenerationRequest()
            ?: return@forEach
        add(
            TutorVisualWorkSeed(
                anchor = TutorVisualTurnAnchor(
                    surface = TutorVisualTurnSurface.FOLLOW_UP,
                    cycleOrdinal = output.cycleOrdinal,
                    turnOrdinal = output.turnOrdinal,
                    responseOrdinal = output.responseOrdinal,
                ),
                request = request,
                explanationMarkdown = output.messageMarkdown,
            ),
        )
    }
}.distinctBy(TutorVisualWorkSeed::anchor)

internal fun resolveTutorVisual(
    anchor: TutorVisualTurnAnchor,
    question: TutorQuestionContext,
    generationTasks: List<ModelTaskSnapshot>,
    reviewTasks: List<ModelTaskSnapshot>,
    expectedGenerationRequestId: String? = null,
    expectedReviewRequestId: String? = null,
    reviewProvider: ProviderCapabilitySnapshot? = null,
): TutorVisualResolution {
    val generationTask = generationTasks
        .asReversed()
        .firstOrNull { task ->
            val input = task.request.input as? TutorVisualGenerateInput
            input?.anchor == anchor &&
                input.sessionId == question.sessionId &&
                input.draftRevisionNumber == question.revisionNumber &&
                input.questionDocument.id == question.questionDocument.document.id &&
                task.request.matchesSemanticRequest(expectedGenerationRequestId)
        } ?: return TutorVisualResolution.Preparing
    if (generationTask.status != ModelTaskStatus.SUCCEEDED) {
        return if (generationTask.status.isVisualFailure()) {
            TutorVisualResolution.Fallback(
                reason = TutorVisualFallbackReason.TASK_FAILURE,
                canRetry = generationTask.canRetryVisualTask(),
                failedTask = generationTask,
            )
        } else {
            TutorVisualResolution.Preparing
        }
    }
    val generated = generationTask.output as? TutorVisualGenerateOutput
        ?: return TutorVisualResolution.Fallback(TutorVisualFallbackReason.INVALID_OUTPUT)
    if (generated.decision != TutorVisualGenerationDecision.GENERATED) {
        return TutorVisualResolution.Fallback(TutorVisualFallbackReason.DECLINED)
    }
    val candidate = generated.scene
        ?: return TutorVisualResolution.Fallback(TutorVisualFallbackReason.INVALID_OUTPUT)
    val generationInput = generationTask.request.input as TutorVisualGenerateInput
    val generationCacheKey = visualCacheKey(
        question = question,
        input = generationInput,
        modelVersion = generated.modelVersion,
        schemaVersion = candidate.schemaVersion,
        providerId = generationTask.visualProviderId(),
        providerConfigurationVersion = generationTask.visualProviderConfigurationVersion(),
    )
    val reasons = generated.reviewReasonCodes()
    if (reasons.isEmpty()) {
        return TutorVisualResolution.Ready(candidate, generationCacheKey)
    }
    val semanticReviewRequestId = expectedReviewRequestId ?: reviewProvider?.let { provider ->
        tutorVisualReviewRequestId(
            generationRequestId = generationTask.request.requestId,
            provider = provider,
            generated = generated,
            reviewReasonCodes = reasons,
        )
    }

    val reviewOutput = reviewTasks
        .asReversed()
        .firstOrNull { task ->
            val input = task.request.input as? TutorVisualReviewInput
            input?.anchor == anchor &&
                input.candidateScene.sceneId == candidate.sceneId &&
                input.sessionId == question.sessionId &&
                input.draftRevisionNumber == question.revisionNumber &&
                input.questionDocument.id == question.questionDocument.document.id &&
                task.request.matchesSemanticRequest(semanticReviewRequestId)
        }
    val reviewCandidate = TutorVisualResolution.Reviewing(
        generationTask = generationTask,
        output = generated,
        reasonCodes = reasons,
    )
    if (reviewOutput == null) return reviewCandidate
    if (reviewOutput.status != ModelTaskStatus.SUCCEEDED) {
        return if (reviewOutput.status.isVisualFailure()) {
            TutorVisualResolution.Fallback(
                reason = TutorVisualFallbackReason.TASK_FAILURE,
                canRetry = reviewOutput.canRetryVisualTask(),
                failedTask = reviewOutput,
                reviewCandidate = reviewCandidate,
            )
        } else {
            reviewCandidate
        }
    }
    val reviewed = reviewOutput.output as? TutorVisualReviewOutput
        ?: return TutorVisualResolution.Fallback(TutorVisualFallbackReason.INVALID_OUTPUT)

    return when (reviewed.decision) {
        TutorVisualReviewDecision.APPROVED -> candidate
            .takeIf { reviewed.confidence >= MIN_REVIEW_CONFIDENCE }
            ?.takeIf(::isLocallyRenderable)
            ?.let { scene -> TutorVisualResolution.Ready(scene, generationCacheKey) }
            ?: TutorVisualResolution.Fallback(TutorVisualFallbackReason.VALIDATION_FAILED)
        TutorVisualReviewDecision.REPAIRED -> reviewed.scene
            ?.takeIf { reviewed.confidence >= MIN_REVIEW_CONFIDENCE }
            ?.takeIf(::isLocallyRenderable)
            ?.let { scene ->
                TutorVisualResolution.Ready(
                    scene = scene,
                    cacheKey = visualCacheKey(
                        question = question,
                        input = generationInput,
                        modelVersion = reviewed.modelVersion,
                        schemaVersion = scene.schemaVersion,
                        providerId = reviewOutput.visualProviderId(),
                        providerConfigurationVersion =
                            reviewOutput.visualProviderConfigurationVersion(),
                    ),
                )
            }
            ?: TutorVisualResolution.Fallback(TutorVisualFallbackReason.VALIDATION_FAILED)
        TutorVisualReviewDecision.REJECTED ->
            TutorVisualResolution.Fallback(TutorVisualFallbackReason.REJECTED)
    }
}

private fun ModelTaskRequest.matchesSemanticRequest(expectedRequestId: String?): Boolean =
    expectedRequestId == null ||
        requestId == expectedRequestId ||
        requestId.startsWith("$expectedRequestId:retry:")

internal fun ModelTaskSnapshot.matchesTutorVisualRequest(request: ModelTaskRequest): Boolean =
    this.request.matchesSemanticRequest(request.requestId)

private fun ModelTaskSnapshot.canRetryVisualTask(): Boolean =
    status == ModelTaskStatus.RETRYABLE_FAILURE &&
        failure?.retryable == true &&
        ModelTaskRemoteDispatchPolicy.canSchedule(attemptCount)

private fun ModelTaskStatus.isVisualFailure(): Boolean =
    this == ModelTaskStatus.RETRYABLE_FAILURE ||
        this == ModelTaskStatus.PERMANENT_FAILURE ||
        this == ModelTaskStatus.CANCELLED

private fun TutorVisualGenerateOutput.reviewReasonCodes(): Set<String> {
    val candidate = scene ?: return emptySet()
    return buildSet {
        if (!isLocallyRenderable(candidate)) add(LOCAL_INTEGRITY_REASON)
        if (confidence < MIN_GENERATION_CONFIDENCE) add(LOW_CONFIDENCE_REASON)
        TutorVisualRiskAssessor.assess(candidate)
            .takeIf { assessment -> assessment.level == TutorVisualRiskLevel.REVIEW_REQUIRED }
            ?.reasons
            ?.let(::addAll)
    }
}

private fun isLocallyRenderable(scene: TutorVisualDocumentScene): Boolean =
    !scene.containsVisibleIllustrativeValue() &&
        runCatching { TutorVisualDocumentCompiler.compile(scene).integrity.canRender }
            .getOrDefault(false)

private fun visualCacheKey(
    question: TutorQuestionContext,
    input: TutorVisualGenerateInput,
    modelVersion: String,
    schemaVersion: Int,
    providerId: String,
    providerConfigurationVersion: String,
): String {
    val sourceFingerprint = MessageDigest.getInstance("SHA-256")
        .digest(
            input.sourceAssets
                .sortedBy { source -> source.pageIndex }
                .joinToString(separator = "\n") { source -> source.sha256 }
                .toByteArray(StandardCharsets.UTF_8),
        )
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return TutorVisualCacheKey.create(
        questionDocumentFingerprint = CapturedQuestionDocumentFingerprint.of(
            question.questionDocument,
        ),
        sourceImageSha256 = sourceFingerprint,
        modelVersion = modelVersion,
        schemaVersion = schemaVersion,
        providerId = providerId,
        providerConfigurationVersion = providerConfigurationVersion,
    )
}

private fun ModelTaskSnapshot.visualProviderId(): String =
    provider?.providerId ?: request.egressManifest?.providerId.orEmpty()

private fun ModelTaskSnapshot.visualProviderConfigurationVersion(): String =
    provider?.providerConfigurationVersion
        ?: request.egressManifest?.providerConfigurationVersion.orEmpty()

private const val MIN_GENERATION_CONFIDENCE = 0.90
private const val MIN_REVIEW_CONFIDENCE = 0.75
private const val LOCAL_INTEGRITY_REASON = "local_integrity_error"
private const val LOW_CONFIDENCE_REASON = "low_generation_confidence"
