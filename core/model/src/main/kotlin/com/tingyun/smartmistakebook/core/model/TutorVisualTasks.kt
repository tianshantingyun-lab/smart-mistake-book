package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A small hand-off emitted by the text tutor. It requests a visual for the current question
 * without delaying the text reply or embedding a second question.
 */
@Serializable
data class TutorVisualGenerationRequest(
    val focusMarkdown: String,
) {
    init {
        focusMarkdown.requireTutorMarkdown("Tutor visual focus", MAX_FOCUS_MARKDOWN_CHARS)
    }

    companion object {
        const val MAX_FOCUS_MARKDOWN_CHARS = 1_200
    }
}

@Serializable
enum class TutorVisualTurnSurface {
    PLAN,
    FOLLOW_UP,
}

/** Stable attachment point so an asynchronously generated scene returns to the correct reply. */
@Serializable
data class TutorVisualTurnAnchor(
    val surface: TutorVisualTurnSurface,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val responseOrdinal: Int? = null,
) {
    init {
        require(cycleOrdinal > 0)
        require(turnOrdinal in 1..TutorPlanInput.MAX_TURNS)
        when (surface) {
            TutorVisualTurnSurface.PLAN -> require(responseOrdinal == null)
            TutorVisualTurnSurface.FOLLOW_UP -> requireNotNull(responseOrdinal).let { require(it > 0) }
        }
    }
}

/**
 * Independent multimodal visual-generation work. The input is bounded to one confirmed question,
 * one already-produced explanation, and its explicitly granted canonical source images.
 */
@Serializable
@SerialName("tutor_visual_generate")
data class TutorVisualGenerateInput(
    val sessionId: String,
    val draftRevisionNumber: Int,
    val subject: String,
    val questionDocument: QuestionDocument,
    val sourceAssets: List<CaptureSourceAssetRef>,
    val anchor: TutorVisualTurnAnchor,
    val focusMarkdown: String,
    val explanationMarkdown: String,
    /** Locally minted facts available for GIVEN references. Empty only on legacy persisted work. */
    val sourceFacts: List<TutorVisualSourceFact> = emptyList(),
) : ModelTaskInput {
    override val kind: ModelTaskKind
        get() = ModelTaskKind.TUTOR_VISUAL_GENERATE

    override val subjectId: String
        get() = sessionId

    init {
        sessionId.requireSafeModelText("Tutor visual session id", ModelTaskRequest.MAX_ID_CHARS, false)
        require(draftRevisionNumber > 0)
        subject.requireSafeModelText("Tutor visual subject", TutorPlanInput.MAX_SUBJECT_CHARS, false)
        require(questionDocument.blocks.isNotEmpty())
        require(sourceAssets.size in 1..MAX_SOURCE_ASSETS)
        require(sourceAssets.map(CaptureSourceAssetRef::assetId).distinct().size == sourceAssets.size)
        require(sourceAssets.map(CaptureSourceAssetRef::pageIndex) == sourceAssets.indices.toList())
        focusMarkdown.requireTutorMarkdown(
            "Tutor visual generation focus",
            TutorVisualGenerationRequest.MAX_FOCUS_MARKDOWN_CHARS,
        )
        explanationMarkdown.requireTutorMarkdown(
            "Tutor visual generation explanation",
            MAX_EXPLANATION_MARKDOWN_CHARS,
        )
        TutorVisualSourceFactCatalog.requireValid(questionDocument, sourceAssets, sourceFacts)
    }

    companion object {
        const val MAX_SOURCE_ASSETS = 8
        const val MAX_EXPLANATION_MARKDOWN_CHARS = 16_000
    }
}

@Serializable
enum class TutorVisualGenerationDecision {
    GENERATED,
    DECLINED_UNCERTAIN,
}

@Serializable
@SerialName("tutor_visual_generate_output")
data class TutorVisualGenerateOutput(
    val sessionId: String,
    val draftRevisionNumber: Int,
    val questionDocumentId: String,
    val anchor: TutorVisualTurnAnchor,
    val decision: TutorVisualGenerationDecision,
    val confidence: Double,
    val scene: TutorVisualDocumentScene? = null,
    val modelVersion: String,
) : ModelTaskOutput {
    init {
        requireVisualOutputHeader(
            sessionId = sessionId,
            draftRevisionNumber = draftRevisionNumber,
            questionDocumentId = questionDocumentId,
            confidence = confidence,
            modelVersion = modelVersion,
        )
        require((decision == TutorVisualGenerationDecision.GENERATED) == (scene != null)) {
            "Generated tutor visuals require a scene and uncertain results must not expose one"
        }
    }
}

/**
 * One bounded review attempt for a parsed high-risk or locally rejected candidate. It cannot
 * request a second repair and cannot mutate the original task output.
 */
@Serializable
@SerialName("tutor_visual_review")
data class TutorVisualReviewInput(
    val sessionId: String,
    val draftRevisionNumber: Int,
    val subject: String,
    val questionDocument: QuestionDocument,
    val sourceAssets: List<CaptureSourceAssetRef>,
    val anchor: TutorVisualTurnAnchor,
    val focusMarkdown: String,
    val explanationMarkdown: String,
    val candidateScene: TutorVisualDocumentScene,
    val reviewReasonCodes: Set<String>,
    /** Must be copied unchanged from the generation request. */
    val sourceFacts: List<TutorVisualSourceFact> = emptyList(),
) : ModelTaskInput {
    override val kind: ModelTaskKind
        get() = ModelTaskKind.TUTOR_VISUAL_REVIEW

    override val subjectId: String
        get() = sessionId

    init {
        sessionId.requireSafeModelText("Tutor visual review session id", ModelTaskRequest.MAX_ID_CHARS, false)
        require(draftRevisionNumber > 0)
        subject.requireSafeModelText("Tutor visual review subject", TutorPlanInput.MAX_SUBJECT_CHARS, false)
        require(questionDocument.blocks.isNotEmpty())
        require(sourceAssets.size in 1..TutorVisualGenerateInput.MAX_SOURCE_ASSETS)
        require(sourceAssets.map(CaptureSourceAssetRef::assetId).distinct().size == sourceAssets.size)
        require(sourceAssets.map(CaptureSourceAssetRef::pageIndex) == sourceAssets.indices.toList())
        focusMarkdown.requireTutorMarkdown(
            "Tutor visual review focus",
            TutorVisualGenerationRequest.MAX_FOCUS_MARKDOWN_CHARS,
        )
        explanationMarkdown.requireTutorMarkdown(
            "Tutor visual review explanation",
            TutorVisualGenerateInput.MAX_EXPLANATION_MARKDOWN_CHARS,
        )
        require(reviewReasonCodes.size in 1..MAX_REVIEW_REASONS)
        reviewReasonCodes.forEach { reason ->
            reason.requireSafeModelText("Tutor visual review reason", MAX_REVIEW_REASON_CHARS, false)
        }
        TutorVisualSourceFactCatalog.requireValid(questionDocument, sourceAssets, sourceFacts)
    }

    companion object {
        const val MAX_REVIEW_REASONS = 8
        const val MAX_REVIEW_REASON_CHARS = 64
    }
}

@Serializable
enum class TutorVisualReviewDecision {
    APPROVED,
    REPAIRED,
    REJECTED,
}

@Serializable
@SerialName("tutor_visual_review_output")
data class TutorVisualReviewOutput(
    val sessionId: String,
    val draftRevisionNumber: Int,
    val questionDocumentId: String,
    val anchor: TutorVisualTurnAnchor,
    val decision: TutorVisualReviewDecision,
    val confidence: Double,
    val scene: TutorVisualDocumentScene? = null,
    val modelVersion: String,
) : ModelTaskOutput {
    init {
        requireVisualOutputHeader(
            sessionId = sessionId,
            draftRevisionNumber = draftRevisionNumber,
            questionDocumentId = questionDocumentId,
            confidence = confidence,
            modelVersion = modelVersion,
        )
        require(
            when (decision) {
                TutorVisualReviewDecision.APPROVED,
                TutorVisualReviewDecision.REJECTED,
                -> scene == null
                TutorVisualReviewDecision.REPAIRED -> scene != null
            },
        ) {
            "Only repaired tutor visual reviews may return a replacement scene"
        }
    }
}

private fun requireVisualOutputHeader(
    sessionId: String,
    draftRevisionNumber: Int,
    questionDocumentId: String,
    confidence: Double,
    modelVersion: String,
) {
    sessionId.requireSafeModelText("Tutor visual output session id", ModelTaskRequest.MAX_ID_CHARS, false)
    require(draftRevisionNumber > 0)
    questionDocumentId.requireSafeModelText(
        "Tutor visual output question document id",
        ModelTaskRequest.MAX_ID_CHARS,
        false,
    )
    require(confidence.isFinite() && confidence in 0.0..1.0)
    modelVersion.requireSafeModelText("Tutor visual model version", MAX_MODEL_VERSION_CHARS, false)
}
