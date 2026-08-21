package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.EventTimeTrust
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.LearningModelVersion
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.ReviewDifficultyBand
import com.tingyun.smartmistakebook.core.model.ReviewPlan
import com.tingyun.smartmistakebook.core.model.ReviewQueueItem
import com.tingyun.smartmistakebook.core.model.ReviewReason
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Extract HLR features from the current memory/mastery state. */
internal fun extractHlrFeatures(
    memory: ProblemMemoryState,
    mastery: KnowledgeMasteryState?,
    difficulty: Double,
    nowEpochMillis: Long,
): HLRFeatures {
    val daysSinceFirstSeen = (nowEpochMillis -
        (memory.lastReviewedAtEpochMillis - memory.stabilityDays * 86_400_000L))
        .toDouble() / 86_400_000.0
    val timeBetweenReviewsDays = memory.stabilityDays
    val consecutiveCorrectStreak =
        (memory.independentCorrectCount - memory.lapseCount).coerceAtLeast(0).toDouble()
    return HLRFeatures(
        independentCorrectCount = memory.independentCorrectCount.toDouble(),
        assistedCorrectCount = memory.assistedCorrectCount.toDouble(),
        lapseCount = memory.lapseCount.toDouble(),
        answerRevealCount = memory.answerRevealCount.toDouble(),
        evidenceMass = mastery?.evidenceMass ?: 0.0,
        difficulty = difficulty,
        timeBetweenReviewsDays = timeBetweenReviewsDays,
        daysSinceFirstSeen = daysSinceFirstSeen.coerceAtLeast(0.0),
        consecutiveCorrectStreak = consecutiveCorrectStreak,
        lastResponseLatencyNormalized = 0.0,
    )
}

data class ReviewCandidate(
    val practiceUnitId: String,
    val knowledgeNodeIds: Set<String>,
    val itemFamilyId: String,
    val sourceBundleId: String?,
    val difficulty: Double,
    val estimatedDurationSeconds: Int,
    val examPriority: Double = 0.0,
    val repeatMistakePriority: Double = 0.0,
    val eligibleSinceEpochMillis: Long? = null,
    val recentFamilyCount: Int = 0,
    val recentSourceCount: Int = 0,
) {
    init {
        require(practiceUnitId.isNotBlank()) { "Practice unit id must not be blank" }
        require(knowledgeNodeIds.none(String::isBlank)) { "Knowledge-node ids must not be blank" }
        require(itemFamilyId.isNotBlank()) { "Item family id must not be blank" }
        require(sourceBundleId == null || sourceBundleId.isNotBlank()) {
            "Source bundle id must not be blank when provided"
        }
        require(difficulty.isFinite() && difficulty in 0.0..1.0) {
            "Difficulty must be between zero and one"
        }
        require(estimatedDurationSeconds > 0) { "Estimated duration must be positive" }
        require(examPriority.isFinite() && examPriority in 0.0..1.0) {
            "Exam priority must be between zero and one"
        }
        require(repeatMistakePriority.isFinite() && repeatMistakePriority in 0.0..1.0) {
            "Repeat-mistake priority must be between zero and one"
        }
        require(eligibleSinceEpochMillis == null || eligibleSinceEpochMillis >= 0) {
            "Review eligibility time must not be negative"
        }
        require(recentFamilyCount >= 0) { "Recent family count must not be negative" }
        require(recentSourceCount >= 0) { "Recent source count must not be negative" }
    }
}

data class ReviewPlanningRequest(
    val learnerSnapshot: LearnerSnapshot,
    val candidates: List<ReviewCandidate>,
    val localDayEpochDay: Long,
    val timeZoneId: String,
    val timeBudgetSeconds: Int,
    val planningAtEpochMillis: Long,
) {
    init {
        require(timeZoneId.isNotBlank()) { "Review planning time-zone id must not be blank" }
        require(timeBudgetSeconds >= 0) { "Time budget must not be negative" }
        require(planningAtEpochMillis >= 0) { "Review planning time must not be negative" }
        require(
            planningAtEpochMillis >= learnerSnapshot.decisionWatermarkEpochMillis,
        ) { "Review planning time must not precede the projected snapshot or correction watermark" }
        require(candidates.map(ReviewCandidate::practiceUnitId).distinct().size == candidates.size) {
            "A planning request must not repeat a practice unit"
        }
    }
}

/** Versioned, deterministic review queue planning under a strict time budget. */
class ReviewPlanner(
    private val forgettingCurve: ForgettingCurve = ForgettingCurve(),
    private val hlrPredictor: HalfLifeRegressionPredictor? = null,
) {
    /**
     * Shadow predictions generated during planning. These are not used for
     * scheduling but are stored for later calibration.
     */
    var shadowPredictions: List<HLRShadowPrediction> = emptyList()
        private set

    fun plan(request: ReviewPlanningRequest): ReviewPlan {
        require(request.learnerSnapshot.freshness == LearnerSnapshotFreshness.CURRENT) {
            "Review plans require a current learner snapshot"
        }
        require(request.learnerSnapshot.projectionStatus == ProjectionStatus.CURRENT) {
            "Review plans require a complete, conflict-free projection"
        }
        val now = request.planningAtEpochMillis
        val scored = request.candidates.mapNotNull { candidate ->
            scoreCandidate(candidate, request.learnerSnapshot, now)
        }
        val remaining = scored.toMutableList()
        val selected = mutableListOf<ScoredCandidate>()
        val usedFamilies = mutableSetOf<String>()
        val usedSources = mutableSetOf<String>()
        var remainingSeconds = request.timeBudgetSeconds
        var preferredBandIndex = 0

        while (remaining.isNotEmpty() && remainingSeconds > 0) {
            val fitting = remaining.filter { it.candidate.estimatedDurationSeconds <= remainingSeconds }
            if (fitting.isEmpty()) break

            // Soft diversity: penalize repeated families/sources instead of hard exclusion
            val adjustedCandidates = fitting.map { scored ->
                val familyPenalty = (scored.candidate.recentFamilyCount * FAMILY_PENALTY_WEIGHT)
                    .coerceAtMost(MAX_DIVERSITY_PENALTY)
                val sourcePenalty = (scored.candidate.recentSourceCount * SOURCE_PENALTY_WEIGHT)
                    .coerceAtMost(MAX_DIVERSITY_PENALTY)
                val adjustedScore = scored.score - familyPenalty - sourcePenalty
                scored.copy(score = adjustedScore.coerceAtLeast(0.0))
            }

            val scoreOrder = compareByDescending<ScoredCandidate>(ScoredCandidate::score)
                .thenBy { it.candidate.practiceUnitId }
            val chosen = adjustedCandidates.sortedWith(scoreOrder).first()

            selected += chosen
            remaining.removeAll { it.candidate.practiceUnitId == chosen.candidate.practiceUnitId }
            remainingSeconds -= chosen.candidate.estimatedDurationSeconds
            usedFamilies += chosen.candidate.itemFamilyId
            chosen.candidate.sourceBundleId?.let(usedSources::add)
            preferredBandIndex++
        }

        val planFingerprint = canonicalPlanFingerprint(request, selected)
        val queue = selected.mapIndexed { index, scoredCandidate ->
            val candidate = scoredCandidate.candidate
            ReviewQueueItem(
                queueItemId = "queue-$planFingerprint-$index",
                practiceUnitId = candidate.practiceUnitId,
                knowledgeNodeIds = candidate.knowledgeNodeIds,
                itemFamilyId = candidate.itemFamilyId,
                sourceBundleId = candidate.sourceBundleId,
                reasons = scoredCandidate.reasons,
                priorityScore = scoredCandidate.score,
                difficultyBand = scoredCandidate.difficultyBand,
                estimatedDurationSeconds = candidate.estimatedDurationSeconds,
                scheduledOrder = index,
                dueAtEpochMillis = request.learnerSnapshot
                    .problemMemoryStates[candidate.practiceUnitId]
                    ?.nextReviewAtEpochMillis,
            )
        }

        // Generate HLR shadow predictions for calibration
        if (hlrPredictor != null) {
            val shadowPreds = mutableListOf<HLRShadowPrediction>()
            for (scoredCandidate in scored) {
                val memory = request.learnerSnapshot.problemMemoryStates[scoredCandidate.candidate.practiceUnitId]
                val mastery = scoredCandidate.candidate.knowledgeNodeIds.mapNotNull {
                    request.learnerSnapshot.knowledgeMasteryStates[it]
                }.firstOrNull()

                if (memory != null) {
                    val deltaSeconds = (now - memory.lastReviewedAtEpochMillis) / 1000.0
                    val features = extractHlrFeatures(
                        memory = memory,
                        mastery = mastery,
                        difficulty = scoredCandidate.candidate.difficulty,
                        nowEpochMillis = now,
                    )
                    val shadowPrediction = HLRShadowPrediction(
                        predictionId = "shadow-${scoredCandidate.candidate.practiceUnitId}-$now",
                        modelVersion = LearningModelVersion(
                            modelId = "hlr-shadow-v1",
                            version = "0.1.0-experimental",
                            algorithmHash = "hlr-recall-v1",
                        ),
                        practiceUnitId = scoredCandidate.candidate.practiceUnitId,
                        features = features,
                        predictedRecallProbability = hlrPredictor.predict(features, deltaSeconds),
                        halfLifeSeconds = hlrPredictor.computeHalfLife(features),
                        predictedAtEpochMillis = now,
                        timeTrust = EventTimeTrust.TRUSTED,
                    )
                    shadowPreds.add(shadowPrediction)
                }
            }
            shadowPredictions = shadowPreds
        }

        return ReviewPlan(
            planId = "plan-$planFingerprint",
            planFingerprint = planFingerprint,
            learnerId = request.learnerSnapshot.learnerId,
            localDayEpochDay = request.localDayEpochDay,
            timeZoneId = request.timeZoneId,
            generatedAtEpochMillis = now,
            plannerVersion = VERSION,
            projectionCheckpoint = request.learnerSnapshot.checkpoint,
            timeBudgetSeconds = request.timeBudgetSeconds,
            queueItems = queue,
        )
    }

    private fun scoreCandidate(
        candidate: ReviewCandidate,
        snapshot: LearnerSnapshot,
        now: Long,
    ): ScoredCandidate? {
        val memory = snapshot.problemMemoryStates[candidate.practiceUnitId]
        val masteryStates = candidate.knowledgeNodeIds.mapNotNull(snapshot.knowledgeMasteryStates::get)
        val missingKnowledgeCount = candidate.knowledgeNodeIds.size - masteryStates.size
        val reasons = linkedSetOf<ReviewReason>()
        val dueRisk = when {
            memory == null -> {
                reasons += ReviewReason.NEWLY_ADDED
                0.2
            }

            memory.nextReviewAtEpochMillis <= now -> {
                reasons += ReviewReason.DUE_RECALL_RISK
                val estimate = forgettingCurve.estimateAt(memory, now)
                if (estimate.clockAnomaly == ClockAnomaly.TIME_ROLLBACK) {
                    reasons += ReviewReason.CLOCK_ANOMALY
                }
                val retentionRisk = if (estimate.clockAnomaly == ClockAnomaly.TIME_ROLLBACK) {
                    1.0
                } else {
                    1.0 - estimate.probability
                }
                val overdueDays = (now - memory.nextReviewAtEpochMillis)
                    .coerceAtLeast(0)
                    .toDouble() / DAY_MILLIS
                retentionRisk + (overdueDays / 30.0).coerceAtMost(1.0)
            }

            else -> {
                val estimate = forgettingCurve.estimateAt(memory, now)
                if (estimate.clockAnomaly == ClockAnomaly.TIME_ROLLBACK) {
                    reasons += ReviewReason.CLOCK_ANOMALY
                    1.0
                } else {
                    1.0 - estimate.probability
                }
            }
        }
        if (missingKnowledgeCount > 0 || candidate.knowledgeNodeIds.isEmpty()) {
            reasons += ReviewReason.MISSING_KNOWLEDGE_EVIDENCE
            reasons += ReviewReason.CALIBRATION_CHECK
        }
        val masteryRisks = masteryStates.map { state ->
            val currentSupportedEvidenceMass = state.independentCorrectObservations
                .filter { it.calibrationSupportAt(now) == CalibrationSupport.SUPPORTED }
                .sumOf { it.evidenceWeight }
            val stale = state.status == MasteryStatus.STALE ||
                state.lastEvidenceAtEpochMillis == null ||
                now < (state.lastEvidenceAtEpochMillis ?: 0) ||
                now - (state.lastEvidenceAtEpochMillis ?: now) >
                ClearlyMasteredForSkipPolicy.MAX_EVIDENCE_AGE_MILLIS
            when {
                state.status == MasteryStatus.CONFLICTED -> {
                    reasons += ReviewReason.CONFLICTED_KNOWLEDGE
                    reasons += ReviewReason.CALIBRATION_CHECK
                    1.0
                }
                state.status == MasteryStatus.UNKNOWN -> {
                    reasons += ReviewReason.CALIBRATION_CHECK
                    1.0
                }
                stale -> {
                    reasons += ReviewReason.STALE_KNOWLEDGE
                    reasons += ReviewReason.CALIBRATION_CHECK
                    1.0
                }
                currentSupportedEvidenceMass <= 0.0 -> {
                    reasons += ReviewReason.CALIBRATION_CHECK
                    1.0
                }
                else -> 1.0 - state.lowerBoundIndependentCorrect
            }
        }
        val weakness = (masteryRisks + List(
            maxOf(missingKnowledgeCount, if (candidate.knowledgeNodeIds.isEmpty()) 1 else 0),
        ) { 1.0 }).maxOrNull() ?: 1.0
        if (weakness >= WEAKNESS_THRESHOLD) reasons += ReviewReason.WEAK_KNOWLEDGE
        val lapseIsRecent = memory?.lastLapseAtEpochMillis?.let { lastLapseAt ->
            now - lastLapseAt in 0..RECENT_LAPSE_WINDOW_MILLIS
        } == true
        val lapseScore = if (lapseIsRecent) {
            (memory.lapseCount / 3.0).coerceAtMost(1.0)
        } else {
            0.0
        }
        if (lapseScore > 0.0) reasons += ReviewReason.RECENT_LAPSE
        if (candidate.repeatMistakePriority > 0.0) reasons += ReviewReason.REPEATED_MISTAKE
        if (candidate.examPriority > 0.0) reasons += ReviewReason.EXAM_PRIORITY
        val waitingScore = candidate.eligibleSinceEpochMillis
            ?.let { eligibleSince ->
                val waitingDays = (now - eligibleSince).coerceAtLeast(0).toDouble() / DAY_MILLIS
                ((waitingDays - WAITING_GRACE_DAYS) / WAITING_BONUS_RAMP_DAYS)
                    .coerceIn(0.0, 1.0)
            }
            ?: 0.0
        if (waitingScore > 0.0) reasons += ReviewReason.LONG_WAITING
        val hasEarlyReviewReason = reasons.any { reason ->
            reason == ReviewReason.CLOCK_ANOMALY ||
                reason == ReviewReason.CALIBRATION_CHECK ||
                reason == ReviewReason.REPEATED_MISTAKE ||
                reason == ReviewReason.EXAM_PRIORITY
        }
        if (
            memory != null &&
            memory.nextReviewAtEpochMillis > now &&
            !hasEarlyReviewReason
        ) {
            return null
        }
        if (reasons.isEmpty()) return null

        val score = (
            DUE_WEIGHT * dueRisk +
                WEAKNESS_WEIGHT * weakness +
                LAPSE_WEIGHT * lapseScore +
                REPEAT_MISTAKE_WEIGHT * candidate.repeatMistakePriority +
                EXAM_WEIGHT * candidate.examPriority +
                WAITING_WEIGHT * waitingScore
            ).coerceAtLeast(0.0)
        return ScoredCandidate(
            candidate = candidate,
            score = score,
            reasons = reasons,
            difficultyBand = difficultyBand(candidate.difficulty),
        )
    }

    private fun difficultyBand(difficulty: Double): ReviewDifficultyBand = when {
        difficulty < 0.35 -> ReviewDifficultyBand.EASY
        difficulty < 0.7 -> ReviewDifficultyBand.MEDIUM
        else -> ReviewDifficultyBand.HARD
    }

    private fun canonicalPlanFingerprint(
        request: ReviewPlanningRequest,
        selected: List<ScoredCandidate>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun append(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        fun field(name: String, value: Any?) {
            append(name)
            append(value?.toString() ?: "<null>")
        }
        field("schemaVersion", PLAN_FINGERPRINT_SCHEMA_VERSION)
        field("learnerId", request.learnerSnapshot.learnerId)
        field("localDayEpochDay", request.localDayEpochDay)
        field("timeZoneId", request.timeZoneId)
        field("timeBudgetSeconds", request.timeBudgetSeconds)
        field("planningAtEpochMillis", request.planningAtEpochMillis)
        field("plannerVersion", VERSION)
        field("projectorVersion", request.learnerSnapshot.checkpoint.projectorVersion)
        field("checkpointLastSequence", request.learnerSnapshot.checkpoint.lastSequence)
        field("checkpointProjectedAt", request.learnerSnapshot.checkpoint.projectedAtEpochMillis)
        field("correctionWatermark", request.learnerSnapshot.correctionWatermarkEpochMillis)
        field("queueSize", selected.size)
        selected.forEachIndexed { index, scored ->
            val candidate = scored.candidate
            field("queue[$index].practiceUnitId", candidate.practiceUnitId)
            field("queue[$index].knowledgeNodeCount", candidate.knowledgeNodeIds.size)
            candidate.knowledgeNodeIds.sorted().forEachIndexed { knowledgeIndex, knowledgeNodeId ->
                field("queue[$index].knowledgeNode[$knowledgeIndex]", knowledgeNodeId)
            }
            field("queue[$index].itemFamilyId", candidate.itemFamilyId)
            field("queue[$index].sourceBundleId", candidate.sourceBundleId)
            field("queue[$index].candidateDifficulty", java.lang.Double.toHexString(candidate.difficulty))
            field("queue[$index].candidateExamPriority", java.lang.Double.toHexString(candidate.examPriority))
            field(
                "queue[$index].candidateRepeatMistakePriority",
                java.lang.Double.toHexString(candidate.repeatMistakePriority),
            )
            field("queue[$index].eligibleSinceEpochMillis", candidate.eligibleSinceEpochMillis)
            field("queue[$index].reasonCount", scored.reasons.size)
            scored.reasons.map(ReviewReason::name).sorted().forEachIndexed { reasonIndex, reason ->
                field("queue[$index].reason[$reasonIndex]", reason)
            }
            field("queue[$index].priorityScore", java.lang.Double.toHexString(scored.score))
            field("queue[$index].difficultyBand", scored.difficultyBand)
            field("queue[$index].estimatedDurationSeconds", candidate.estimatedDurationSeconds)
            field("queue[$index].scheduledOrder", index)
            field(
                "queue[$index].dueAtEpochMillis",
                request.learnerSnapshot.problemMemoryStates[candidate.practiceUnitId]?.nextReviewAtEpochMillis,
            )
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private data class ScoredCandidate(
        val candidate: ReviewCandidate,
        val score: Double,
        val reasons: Set<ReviewReason>,
        val difficultyBand: ReviewDifficultyBand,
    )

    companion object {
        const val VERSION = LearningCoreVersions.REVIEW_COMPOSITE
        private val DIFFICULTY_CYCLE = listOf(
            ReviewDifficultyBand.MEDIUM,
            ReviewDifficultyBand.EASY,
            ReviewDifficultyBand.HARD,
        )
        private const val WEAKNESS_THRESHOLD = 0.35
        private const val DUE_WEIGHT = 5.0
        private const val WEAKNESS_WEIGHT = 3.0
        private const val LAPSE_WEIGHT = 1.0
        private const val REPEAT_MISTAKE_WEIGHT = 2.0
        private const val EXAM_WEIGHT = 2.0
        private const val WAITING_WEIGHT = 1.5
        private const val FAMILY_PENALTY_WEIGHT = 0.3
        private const val SOURCE_PENALTY_WEIGHT = 0.2
        private const val MAX_DIVERSITY_PENALTY = 1.5
        private const val DAY_MILLIS = 86_400_000.0
        private const val RECENT_LAPSE_WINDOW_MILLIS = 30L * 86_400_000L
        private const val WAITING_GRACE_DAYS = 7.0
        private const val WAITING_BONUS_RAMP_DAYS = 83.0
        private const val PLAN_FINGERPRINT_SCHEMA_VERSION = "review-plan-canonical-v5"
    }
}
