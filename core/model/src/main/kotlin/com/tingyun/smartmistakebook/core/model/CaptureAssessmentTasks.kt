package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class CaptureAssessmentDecision {
    PASS,
    RECAPTURE,
    NEED_MORE_IMAGE,
    SPLIT,
}

@Serializable
enum class CaptureAssessmentIssueCode {
    MISSING_OPTIONS,
    KEY_TEXT_UNREADABLE,
    GLARE_COVERS_FORMULA,
    OCCLUDED,
    MULTIPLE_QUESTIONS,
}

@Serializable
enum class CaptureAssessmentSeverity {
    BLOCKING,
    REVIEW,
}

@Serializable
enum class CaptureAssessmentAction {
    RECAPTURE,
    ADD_IMAGE,
    CONTINUE_ANYWAY,
}

@Serializable
enum class CapturePageRelation {
    SAME_QUESTION,
    NEXT_QUESTION,
    UNSURE,
}

@Serializable
data class CaptureAssessmentIssue(
    val code: CaptureAssessmentIssueCode,
    val severity: CaptureAssessmentSeverity,
    val region: NormalizedSourceRegion? = null,
    val message: String,
) {
    init {
        message.requireSafeModelText(
            label = "Capture assessment issue message",
            maxChars = MAX_MESSAGE_CHARS,
            allowLineBreaks = true,
        )
        require(region == null || region.isValidModelRegion()) {
            "Capture assessment issue region is invalid"
        }
    }
}

@Serializable
data class CaptureAssessment(
    val decision: CaptureAssessmentDecision,
    val issues: List<CaptureAssessmentIssue>,
    val suggestedActions: List<CaptureAssessmentAction>,
    val modelVersion: String,
    val questionRegions: List<NormalizedSourceRegion> = emptyList(),
    val followingPageRelations: List<CapturePageRelation> = emptyList(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    init {
        require(schemaVersion in MIN_SUPPORTED_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION) {
            "Unsupported capture assessment schema"
        }
        require(issues.size <= MAX_ISSUES) { "Capture assessment has too many issues" }
        require(suggestedActions.size <= MAX_ACTIONS) {
            "Capture assessment has too many actions"
        }
        require(suggestedActions.distinct().size == suggestedActions.size) {
            "Capture assessment actions must be unique"
        }
        modelVersion.requireSafeModelText(
            label = "Capture assessment model version",
            maxChars = MAX_MODEL_VERSION_CHARS,
            allowLineBreaks = false,
        )
        require(
            decision == CaptureAssessmentDecision.PASS || issues.isNotEmpty(),
        ) { "A blocked capture assessment must explain at least one issue" }
        require(
            decision != CaptureAssessmentDecision.PASS ||
                issues.none { it.severity == CaptureAssessmentSeverity.BLOCKING },
        ) { "A passing capture assessment cannot contain blocking issues" }
        require(
            decision != CaptureAssessmentDecision.PASS ||
                CaptureAssessmentAction.RECAPTURE !in suggestedActions &&
                CaptureAssessmentAction.ADD_IMAGE !in suggestedActions,
        ) { "A passing capture assessment cannot request another image" }
        require(
            decision != CaptureAssessmentDecision.RECAPTURE ||
                CaptureAssessmentAction.RECAPTURE in suggestedActions,
        ) { "A recapture decision must suggest recapturing" }
        require(
            decision != CaptureAssessmentDecision.NEED_MORE_IMAGE ||
                CaptureAssessmentAction.ADD_IMAGE in suggestedActions,
        ) { "A missing-image decision must suggest adding an image" }
        val multipleQuestionIssues = issues.filter {
            it.code == CaptureAssessmentIssueCode.MULTIPLE_QUESTIONS
        }
        if (schemaVersion >= MULTIPLE_QUESTION_SPLIT_SCHEMA_VERSION) {
            require(multipleQuestionIssues.all { it.severity == CaptureAssessmentSeverity.BLOCKING }) {
                "Multiple independent questions must be treated as blocking"
            }
            require(
                multipleQuestionIssues.isEmpty() || decision == CaptureAssessmentDecision.SPLIT,
            ) { "Multiple independent questions must enter the local split flow" }
            require(
                decision != CaptureAssessmentDecision.SPLIT || multipleQuestionIssues.isNotEmpty(),
            ) { "A split decision must identify multiple independent questions" }
            require(
                if (decision == CaptureAssessmentDecision.SPLIT) {
                    questionRegions.size in MIN_SPLIT_REGION_COUNT..MAX_SPLIT_REGION_COUNT
                } else {
                    questionRegions.isEmpty()
                },
            ) { "Only a split decision may contain two to twelve question regions" }
            require(questionRegions.distinct().size == questionRegions.size) {
                "Split question regions must be unique"
            }
            require(questionRegions.all(NormalizedSourceRegion::isUsableQuestionRegion)) {
                "Split question regions are too small or invalid"
            }
            questionRegions.forEachIndexed { index, region ->
                questionRegions.drop(index + 1).forEach { other ->
                    require(region.overlapRatioOfSmaller(other) <= MAX_SPLIT_REGION_OVERLAP_RATIO) {
                        "Split question regions overlap too heavily"
                    }
                }
            }
        } else if (schemaVersion >= MULTIPLE_QUESTION_GATE_SCHEMA_VERSION) {
            require(decision != CaptureAssessmentDecision.SPLIT && questionRegions.isEmpty()) {
                "Legacy capture assessments cannot request local splitting"
            }
            require(multipleQuestionIssues.all { it.severity == CaptureAssessmentSeverity.BLOCKING }) {
                "Multiple independent questions must be treated as blocking"
            }
            require(
                multipleQuestionIssues.isEmpty() || decision == CaptureAssessmentDecision.RECAPTURE,
            ) { "A legacy single-question capture cannot continue with multiple questions" }
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 3
        private const val MIN_SUPPORTED_SCHEMA_VERSION = 1
        private const val MULTIPLE_QUESTION_GATE_SCHEMA_VERSION = 2
        private const val MULTIPLE_QUESTION_SPLIT_SCHEMA_VERSION = 3
        const val MIN_SPLIT_REGION_COUNT = 2
        const val MAX_SPLIT_REGION_COUNT = 12
        private const val MAX_SPLIT_REGION_OVERLAP_RATIO = 0.8
        const val MAX_ISSUES = 12
        const val MAX_ACTIONS = 3
    }
}

private fun NormalizedSourceRegion.isUsableQuestionRegion(): Boolean {
    if (!isValidModelRegion()) return false
    val width = right - left
    val height = bottom - top
    return width >= 0.08 && height >= 0.04 && width * height >= 0.006
}

private fun NormalizedSourceRegion.overlapRatioOfSmaller(other: NormalizedSourceRegion): Double {
    val overlapWidth = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0.0)
    val overlapHeight = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0.0)
    val overlap = overlapWidth * overlapHeight
    val smallerArea = minOf(
        (right - left) * (bottom - top),
        (other.right - other.left) * (other.bottom - other.top),
    )
    return if (smallerArea == 0.0) 1.0 else overlap / smallerArea
}
