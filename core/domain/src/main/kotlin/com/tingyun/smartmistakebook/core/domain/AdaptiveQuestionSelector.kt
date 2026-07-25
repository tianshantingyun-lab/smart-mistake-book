package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import kotlin.math.abs

enum class AdaptiveTargetKind {
    FOUNDATION,
    TRANSFER,
    BOUNDARY,
    COUNTEREXAMPLE,
    CALIBRATION,
}

enum class AdaptiveDecisionKind {
    ASK,
    ASK_CALIBRATION,
    SKIP_MASTERED_FOUNDATION,
    CLARIFY_OR_CONFIRM,
    NO_SAFE_CANDIDATE,
}

data class PredictedCorrectnessInterval(
    val lowerBound: Double,
    val upperBound: Double,
    val modelVersion: String,
    val generatedAtEpochMillis: Long,
    val validUntilEpochMillis: Long,
) {
    init {
        require(lowerBound.isFinite() && lowerBound in 0.0..1.0) {
            "Prediction lower bound must be between zero and one"
        }
        require(upperBound.isFinite() && upperBound in lowerBound..1.0) {
            "Prediction upper bound must follow its lower bound"
        }
        require(modelVersion.isNotBlank()) { "Prediction model version must not be blank" }
        require(generatedAtEpochMillis >= 0) { "Prediction generation time must not be negative" }
        require(validUntilEpochMillis >= generatedAtEpochMillis) {
            "Prediction validity must not end before generation"
        }
    }

    val midpoint: Double
        get() = (lowerBound + upperBound) / 2.0

    fun isCurrent(atEpochMillis: Long): Boolean = atEpochMillis in generatedAtEpochMillis..validUntilEpochMillis
}

data class AdaptiveQuestionCandidate(
    val assessmentItemId: String,
    val knowledgeNodeIds: Set<String>,
    val itemFamilyId: String,
    val targetKind: AdaptiveTargetKind,
    val contentVerified: Boolean,
    val answerWouldBeRevealed: Boolean,
    val informationValue: Double,
    val predictedCorrectness: PredictedCorrectnessInterval? = null,
    val fatigueCost: Double = 0.0,
    /** Extra intents this verified item can safely serve in addition to [targetKind]. */
    val additionalEligibleTargetKinds: Set<AdaptiveTargetKind> = emptySet(),
) {
    init {
        require(assessmentItemId.isNotBlank()) { "Assessment-item id must not be blank" }
        require(knowledgeNodeIds.isNotEmpty() && knowledgeNodeIds.none(String::isBlank)) {
            "A candidate must target at least one knowledge node"
        }
        require(itemFamilyId.isNotBlank()) { "Item family id must not be blank" }
        require(informationValue.isFinite() && informationValue in 0.0..1.0) {
            "Information value must be between zero and one"
        }
        require(fatigueCost.isFinite() && fatigueCost in 0.0..1.0) {
            "Fatigue cost must be between zero and one"
        }
    }
}

data class AdaptiveSelectionRequest(
    val learnerSnapshot: LearnerSnapshot,
    val targetKnowledgeNodeIds: Set<String>,
    val requestedTargetKind: AdaptiveTargetKind,
    /** Fail-closed authorization for selecting any question beyond the user's current problem. */
    val userRequestedQuestion: Boolean = false,
    val candidates: List<AdaptiveQuestionCandidate>,
    val decisionAtEpochMillis: Long,
    val recentItemFamilyIds: Set<String> = emptySet(),
    val deterministicSeed: Long = 0,
) {
    init {
        require(targetKnowledgeNodeIds.isNotEmpty() && targetKnowledgeNodeIds.none(String::isBlank)) {
            "Adaptive selection requires at least one target knowledge node"
        }
        require(recentItemFamilyIds.none(String::isBlank)) { "Recent item-family ids must not be blank" }
        require(candidates.map(AdaptiveQuestionCandidate::assessmentItemId).distinct().size == candidates.size) {
            "Adaptive candidates must have unique assessment-item ids"
        }
        require(decisionAtEpochMillis >= 0) { "Adaptive decision time must not be negative" }
        require(decisionAtEpochMillis >= learnerSnapshot.decisionWatermarkEpochMillis) {
            "Adaptive decision time must not precede the projected snapshot or correction watermark"
        }
    }
}

data class AdaptiveDecision(
    val kind: AdaptiveDecisionKind,
    val selectedAssessmentItemId: String?,
    val reasonCodes: Set<String>,
    val selectorVersion: String,
    val projectionCheckpointSequence: Long,
) {
    init {
        require(
            (kind == AdaptiveDecisionKind.ASK || kind == AdaptiveDecisionKind.ASK_CALIBRATION) ==
                (selectedAssessmentItemId != null),
        ) { "Only ask decisions may select an assessment item" }
        require(reasonCodes.isNotEmpty()) { "Adaptive decisions must be explainable" }
    }
}

/** Deterministic policy boundary; model wording may consume this decision but cannot replace it. */
class AdaptiveQuestionSelector {
    fun select(request: AdaptiveSelectionRequest): AdaptiveDecision {
        if (!request.userRequestedQuestion) {
            return noCandidateDecision(request, setOf("QUESTION_NOT_REQUESTED"))
        }
        val targetStates = request.targetKnowledgeNodeIds.mapNotNull {
            request.learnerSnapshot.knowledgeMasteryStates[it]
        }
        if (
            request.learnerSnapshot.freshness == LearnerSnapshotFreshness.STALE ||
            request.learnerSnapshot.projectionStatus != ProjectionStatus.CURRENT
        ) {
            return noCandidateDecision(request, setOf("PROJECTION_NOT_CURRENT"))
        }
        if (request.requestedTargetKind == AdaptiveTargetKind.CALIBRATION) {
            return selectCalibration(request, setOf("CALIBRATION_REQUESTED"))
        }

        val allClearlyMastered = targetStates.size == request.targetKnowledgeNodeIds.size &&
            targetStates.all { ClearlyMasteredForSkipPolicy.isSatisfied(it, request.decisionAtEpochMillis) }
        if (request.requestedTargetKind == AdaptiveTargetKind.FOUNDATION && allClearlyMastered) {
            return decision(
                request,
                AdaptiveDecisionKind.SKIP_MASTERED_FOUNDATION,
                null,
                setOf("CLEARLY_MASTERED_FOR_SKIP"),
            )
        }

        val requiresCalibration = targetStates.size != request.targetKnowledgeNodeIds.size ||
            targetStates.any {
                it.status == MasteryStatus.UNKNOWN ||
                    it.status == MasteryStatus.CONFLICTED ||
                    it.status == MasteryStatus.STALE ||
                    !hasFreshEvidence(it, request.decisionAtEpochMillis) ||
                    !hasCurrentCalibration(it, request.decisionAtEpochMillis)
        }
        if (requiresCalibration) {
            return noCandidateDecision(request, setOf("LEARNING_EVIDENCE_NOT_CURRENT"))
        }

        val baseCandidates = safeBaseCandidates(request)
            .filter { it.supports(request.requestedTargetKind) }
        val candidate = baseCandidates
            .filter { withinHardChallengeCorridor(it, request.decisionAtEpochMillis) }
            .maxWithOrNull(candidateComparator(request.deterministicSeed))
        if (candidate == null && baseCandidates.isNotEmpty()) {
            return noCandidateDecision(request, setOf("PREDICTION_UNKNOWN_STALE_OR_OUTSIDE_CORRIDOR"))
        }
        candidate ?: return noCandidateDecision(request)
        return decision(
            request,
            AdaptiveDecisionKind.ASK,
            candidate.assessmentItemId,
            setOf("TARGET_MATCH", "SAFE_VERIFIED_CANDIDATE", "HARD_CHALLENGE_CORRIDOR"),
        )
    }

    private fun hasFreshEvidence(state: KnowledgeMasteryState, atEpochMillis: Long): Boolean {
        val evidenceAt = state.lastEvidenceAtEpochMillis ?: return false
        return atEpochMillis >= evidenceAt &&
            atEpochMillis - evidenceAt <= ClearlyMasteredForSkipPolicy.MAX_EVIDENCE_AGE_MILLIS
    }

    private fun hasCurrentCalibration(state: KnowledgeMasteryState, atEpochMillis: Long): Boolean =
        state.independentCorrectObservations.any {
            it.calibrationSupportAt(atEpochMillis) == CalibrationSupport.SUPPORTED
        }

    private fun selectCalibration(
        request: AdaptiveSelectionRequest,
        reasons: Set<String>,
    ): AdaptiveDecision {
        val candidate = safeBaseCandidates(request)
            .filter { it.supports(AdaptiveTargetKind.CALIBRATION) }
            .maxWithOrNull(candidateComparator(request.deterministicSeed))
            ?: return noCandidateDecision(request, reasons + "NO_SAFE_CALIBRATION_CANDIDATE")
        return decision(
            request,
            AdaptiveDecisionKind.ASK_CALIBRATION,
            candidate.assessmentItemId,
            reasons + "CALIBRATION_ONLY",
        )
    }

    private fun safeBaseCandidates(request: AdaptiveSelectionRequest): List<AdaptiveQuestionCandidate> =
        request.candidates.filter { candidate ->
            candidate.contentVerified &&
                !candidate.answerWouldBeRevealed &&
                candidate.itemFamilyId !in request.recentItemFamilyIds &&
                candidate.knowledgeNodeIds.any(request.targetKnowledgeNodeIds::contains)
        }

    private fun AdaptiveQuestionCandidate.supports(kind: AdaptiveTargetKind): Boolean =
        targetKind == kind || kind in additionalEligibleTargetKinds

    private fun withinHardChallengeCorridor(
        candidate: AdaptiveQuestionCandidate,
        atEpochMillis: Long,
    ): Boolean {
        val prediction = candidate.predictedCorrectness ?: return false
        if (!prediction.isCurrent(atEpochMillis)) return false
        val corridor = when (candidate.targetKind) {
            AdaptiveTargetKind.FOUNDATION -> 0.55..0.85
            AdaptiveTargetKind.TRANSFER,
            AdaptiveTargetKind.BOUNDARY,
            AdaptiveTargetKind.COUNTEREXAMPLE,
            -> 0.35..0.75
            AdaptiveTargetKind.CALIBRATION -> return true
        }
        return prediction.lowerBound >= corridor.start && prediction.upperBound <= corridor.endInclusive
    }

    private fun candidateComparator(seed: Long): Comparator<AdaptiveQuestionCandidate> =
        compareBy<AdaptiveQuestionCandidate> { candidateScore(it) }
            .thenBy { stableTieBreak(it.assessmentItemId, seed) }
            .thenByDescending(AdaptiveQuestionCandidate::assessmentItemId)

    private fun candidateScore(candidate: AdaptiveQuestionCandidate): Double {
        val challengeFit = candidate.predictedCorrectness?.midpoint?.let { 1.0 - abs(it - 0.7) } ?: 0.0
        return 2.0 * candidate.informationValue + challengeFit - candidate.fatigueCost
    }

    private fun stableTieBreak(id: String, seed: Long): Long {
        var hash = seed xor FNV_OFFSET_BASIS
        id.forEach { character -> hash = (hash xor character.code.toLong()) * FNV_PRIME }
        return hash
    }

    private fun noCandidateDecision(
        request: AdaptiveSelectionRequest,
        reasons: Set<String> = emptySet(),
    ): AdaptiveDecision {
        val hasUnverifiedTarget = request.candidates.any { candidate ->
            !candidate.contentVerified && candidate.knowledgeNodeIds.any(request.targetKnowledgeNodeIds::contains)
        }
        return decision(
            request,
            if (hasUnverifiedTarget) AdaptiveDecisionKind.CLARIFY_OR_CONFIRM else AdaptiveDecisionKind.NO_SAFE_CANDIDATE,
            null,
            reasons + if (hasUnverifiedTarget) "TARGET_CONTENT_NOT_VERIFIED" else "NO_SAFE_CANDIDATE",
        )
    }

    private fun decision(
        request: AdaptiveSelectionRequest,
        kind: AdaptiveDecisionKind,
        selectedId: String?,
        reasons: Set<String>,
    ) = AdaptiveDecision(
        kind = kind,
        selectedAssessmentItemId = selectedId,
        reasonCodes = reasons,
        selectorVersion = VERSION,
        projectionCheckpointSequence = request.learnerSnapshot.checkpoint.lastSequence,
    )

    companion object {
        const val VERSION = LearningCoreVersions.SELECTOR_COMPOSITE
        private const val FNV_OFFSET_BASIS = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L
    }
}
