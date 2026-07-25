package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewOutput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
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
    data class Ready(
        val scene: TutorVisualDocumentScene,
        val cacheKey: String,
    ) : TutorVisualResolution

    data class NeedsReview(
        val generationTask: ModelTaskSnapshot,
        val output: TutorVisualGenerateOutput,
        val reasonCodes: Set<String>,
    ) : TutorVisualResolution

    data object Unavailable : TutorVisualResolution
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
        val request = output.visualRequest ?: return@forEach
        if (task.status != ModelTaskStatus.SUCCEEDED) return@forEach
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
): TutorVisualResolution {
    val generationTask = generationTasks
        .asReversed()
        .firstOrNull { task ->
            val input = task.request.input as? TutorVisualGenerateInput
            input?.anchor == anchor &&
                input.sessionId == question.sessionId &&
                input.draftRevisionNumber == question.revisionNumber &&
                input.questionDocument.id == question.questionDocument.document.id &&
                task.status == ModelTaskStatus.SUCCEEDED
        } ?: return TutorVisualResolution.Unavailable
    val generated = generationTask.output as? TutorVisualGenerateOutput
        ?: return TutorVisualResolution.Unavailable
    if (generated.decision != TutorVisualGenerationDecision.GENERATED) {
        return TutorVisualResolution.Unavailable
    }
    val candidate = generated.scene ?: return TutorVisualResolution.Unavailable
    val generationInput = generationTask.request.input as TutorVisualGenerateInput
    val generationCacheKey = visualCacheKey(
        question = question,
        input = generationInput,
        modelVersion = generated.modelVersion,
    )
    val reasons = generated.reviewReasonCodes()
    if (reasons.isEmpty()) {
        return TutorVisualResolution.Ready(candidate, generationCacheKey)
    }

    val reviewOutput = reviewTasks
        .asReversed()
        .firstNotNullOfOrNull { task ->
            val input = task.request.input as? TutorVisualReviewInput
            val output = task.output as? TutorVisualReviewOutput
            output?.takeIf {
                task.status == ModelTaskStatus.SUCCEEDED &&
                    input?.anchor == anchor &&
                    input.candidateScene.sceneId == candidate.sceneId &&
                    input.sessionId == question.sessionId &&
                    input.draftRevisionNumber == question.revisionNumber &&
                    input.questionDocument.id == question.questionDocument.document.id
            }
        } ?: return TutorVisualResolution.NeedsReview(
        generationTask = generationTask,
        output = generated,
        reasonCodes = reasons,
    )

    return when (reviewOutput.decision) {
        TutorVisualReviewDecision.APPROVED -> candidate
            .takeIf { reviewOutput.confidence >= MIN_REVIEW_CONFIDENCE }
            ?.takeIf(::isLocallyRenderable)
            ?.let { scene -> TutorVisualResolution.Ready(scene, generationCacheKey) }
            ?: TutorVisualResolution.Unavailable
        TutorVisualReviewDecision.REPAIRED -> reviewOutput.scene
            ?.takeIf { reviewOutput.confidence >= MIN_REVIEW_CONFIDENCE }
            ?.takeIf(::isLocallyRenderable)
            ?.let { scene ->
                TutorVisualResolution.Ready(
                    scene = scene,
                    cacheKey = visualCacheKey(
                        question = question,
                        input = generationInput,
                        modelVersion = reviewOutput.modelVersion,
                    ),
                )
            }
            ?: TutorVisualResolution.Unavailable
        TutorVisualReviewDecision.REJECTED -> TutorVisualResolution.Unavailable
    }
}

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
    )
}

private const val MIN_GENERATION_CONFIDENCE = 0.90
private const val MIN_REVIEW_CONFIDENCE = 0.75
private const val LOCAL_INTEGRITY_REASON = "local_integrity_error"
private const val LOW_CONFIDENCE_REASON = "low_generation_confidence"
