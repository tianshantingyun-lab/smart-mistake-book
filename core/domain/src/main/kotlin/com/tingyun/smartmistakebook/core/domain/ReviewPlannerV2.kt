package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.ReviewDifficultyBand
import com.tingyun.smartmistakebook.core.model.ReviewPlan
import com.tingyun.smartmistakebook.core.model.ReviewQueueItem
import com.tingyun.smartmistakebook.core.model.ReviewReason
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Review Planner V2 with enhanced scheduling algorithms:
 *
 * - Dynamic diversity penalties based on real-time queue composition
 * - Personal duration model based on historical response times
 * - Uncertainty as independent review value
 * - Knapsack/beam search/local swap optimization
 * - Fatigue and subject stacking constraints
 * - Hard sequencing constraints (audit §7.2): the same item family never
 *   appears in consecutive positions and the same subject never runs longer
 *   than [MAX_SAME_SUBJECT_RUN] consecutive positions; both are validated at
 *   sequence construction, so a violating candidate can never enter a plan
 * - Plan explanation for each selected item
 */
class ReviewPlannerV2(
    private val forgettingCurve: ForgettingCurve = ForgettingCurve(),
    private val durationModel: LogDurationModel = LogDurationModel(),
) {
    fun plan(request: ReviewPlanningRequest): ReviewPlan {
        require(request.learnerSnapshot.freshness == LearnerSnapshotFreshness.CURRENT) {
            "Review plans require a current learner snapshot"
        }
        require(request.learnerSnapshot.projectionStatus == ProjectionStatus.CURRENT) {
            "Review plans require a complete, conflict-free projection"
        }

        val now = request.planningAtEpochMillis
        // Apply the personalized duration model: each candidate's static
        // estimated duration is replaced by the learner-bucket prediction.
        val scored = request.candidates.mapNotNull { candidate ->
            scoreCandidate(candidate, request.learnerSnapshot, now)
                ?.withModeledDuration(durationModel, request.learnerSnapshot.learnerId)
        }

        // Phase 1: Initial selection with beam search
        val selected = beamSearchSelection(
            candidates = scored,
            timeBudgetSeconds = request.timeBudgetSeconds,
        )

        // Phase 2: Local swap optimization to improve diversity
        val optimized = localSwapOptimization(selected, scored, request.timeBudgetSeconds)
        check(satisfiesHardConstraints(optimized)) {
            "Review plan violated the hard sequencing constraints (audit §7.2)"
        }

        val planFingerprint = canonicalPlanFingerprint(request, optimized)
        val queue = optimized.mapIndexed { index, scoredCandidate ->
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

    /**
     * Beam search selection that considers multiple candidate sequences
     * to find better combinations than greedy selection.
     *
     * All candidates that fit in the remaining budget are scored with the
     * incremental utility (base score minus dynamic diversity penalties),
     * then the beam is pruned to the top [beamWidth] states.
     */
    private fun beamSearchSelection(
        candidates: List<ScoredCandidate>,
        timeBudgetSeconds: Int,
        beamWidth: Int = 3,
    ): List<ScoredCandidate> {
        if (candidates.isEmpty()) return emptyList()

        // For small candidate sets, use simple greedy
        if (candidates.size <= beamWidth) {
            return greedySelection(candidates, timeBudgetSeconds)
        }

        // Initialize beam with empty selections
        val initial = BeamState(
            selected = emptyList(),
            usedFamilies = emptyMap(),
            usedSources = emptyMap(),
            remainingSeconds = timeBudgetSeconds,
            totalScore = 0.0,
        )
        var beam = listOf(initial)
        // Track the best state seen so far. Beam pruning and the hard
        // sequencing constraints can drop every expandable state in a step;
        // the search must still return the best partial plan instead of
        // collapsing to an empty queue.
        var best = initial

        for (step in 0 until MAX_BEAM_STEPS) {
            val newBeam = mutableListOf<BeamState>()
            var advanced = false

            for (state in beam) {
                // Score EVERY fitting candidate with incremental utility
                // (do not pre-prune by static score alone, or valuable
                // diverse candidates would never enter the beam). Candidates
                // that violate the hard sequencing constraints are rejected
                // here, at sequence construction, so they can never enter a
                // plan.
                val fitting = candidates.filter {
                    it.candidate.estimatedDurationSeconds <= state.remainingSeconds &&
                        it.candidate.practiceUnitId !in state.usedPracticeUnits &&
                        canAppend(state.selected, it)
                }

                if (fitting.isEmpty()) {
                    newBeam.add(state)
                    continue
                }

                val candidateStates = fitting.mapNotNull { candidate ->
                    val familyPenalty = computeDynamicFamilyPenalty(candidate, state.usedFamilies)
                    val sourcePenalty = computeDynamicSourcePenalty(candidate, state.usedSources)
                    val adjustedScore = candidate.score - familyPenalty - sourcePenalty
                    if (adjustedScore <= 0) return@mapNotNull null

                    val newUsedFamilies = state.usedFamilies.increment(candidate.candidate.itemFamilyId)
                    val newUsedSources = candidate.candidate.sourceBundleId
                        ?.let { state.usedSources.increment(it) }
                        ?: state.usedSources

                    BeamState(
                        selected = state.selected + candidate.copy(score = adjustedScore),
                        usedFamilies = newUsedFamilies,
                        usedSources = newUsedSources,
                        remainingSeconds = state.remainingSeconds - candidate.candidate.estimatedDurationSeconds,
                        totalScore = state.totalScore + adjustedScore,
                    )
                }

                if (candidateStates.isNotEmpty()) advanced = true
                newBeam.addAll(candidateStates)
            }

            // Keep top beamWidth states by total score
            beam = newBeam.sortedByDescending(BeamState::totalScore).take(beamWidth)
            if (beam.isEmpty()) break
            val stepBest = beam.first()
            if (stepBest.totalScore > best.totalScore) best = stepBest

            // Stop only when no beam state could add another candidate. The
            // previous `beam.size == 1` shortcut abandoned the search while a
            // single surviving state could still fill the remaining budget.
            if (!advanced) break
        }

        return best.selected
    }

    /**
     * Simple greedy selection as fallback.
     */
    private fun greedySelection(
        candidates: List<ScoredCandidate>,
        timeBudgetSeconds: Int,
    ): List<ScoredCandidate> {
        val remaining = candidates.toMutableList()
        val selected = mutableListOf<ScoredCandidate>()
        var usedFamilies = emptyMap<String, Int>()
        var usedSources = emptyMap<String, Int>()
        var remainingSeconds = timeBudgetSeconds

        while (remaining.isNotEmpty() && remainingSeconds > 0) {
            val fitting = remaining.filter {
                it.candidate.estimatedDurationSeconds <= remainingSeconds &&
                    canAppend(selected, it)
            }
            if (fitting.isEmpty()) break

            val adjustedCandidates = fitting.map { scored ->
                val familyPenalty = computeDynamicFamilyPenalty(scored, usedFamilies)
                val sourcePenalty = computeDynamicSourcePenalty(scored, usedSources)
                val adjustedScore = scored.score - familyPenalty - sourcePenalty
                scored.copy(score = adjustedScore.coerceAtLeast(0.0))
            }

            val chosen = adjustedCandidates.maxByOrNull(ScoredCandidate::score) ?: break
            if (chosen.score <= 0) break

            selected += chosen
            remaining.removeAll { it.candidate.practiceUnitId == chosen.candidate.practiceUnitId }
            remainingSeconds -= chosen.candidate.estimatedDurationSeconds
            usedFamilies = usedFamilies.increment(chosen.candidate.itemFamilyId)
            chosen.candidate.sourceBundleId?.let { usedSources = usedSources.increment(it) }
        }

        return selected
    }

    /**
     * Local swap optimization: try swapping items in the selected list
     * with unselected items to improve total utility (not just diversity).
     *
     * `unselected` is recomputed after every accepted swap so that already
     * selected practice units can never be offered again as swap targets.
     */
    private fun localSwapOptimization(
        selected: List<ScoredCandidate>,
        allCandidates: List<ScoredCandidate>,
        timeBudgetSeconds: Int,
    ): List<ScoredCandidate> {
        if (selected.size < 2) return selected

        var currentSelection = selected.toMutableList()

        // Try swapping each selected item with each unselected item
        for (i in currentSelection.indices) {
            val currentItem = currentSelection[i]
            val timeDelta = currentItem.candidate.estimatedDurationSeconds
            val usedPracticeUnits = currentSelection.map { it.candidate.practiceUnitId }.toSet()
            val unselected = allCandidates.filter {
                it.candidate.practiceUnitId !in usedPracticeUnits
            }

            var improved = false
            for (unselectedItem in unselected) {
                val newTime = currentSelection.sumOf { it.candidate.estimatedDurationSeconds } -
                    timeDelta + unselectedItem.candidate.estimatedDurationSeconds
                if (newTime > timeBudgetSeconds) continue

                val newSelection = currentSelection.toMutableList()
                newSelection[i] = unselectedItem
                // A swap must never break the hard sequencing constraints.
                if (!satisfiesHardConstraints(newSelection)) continue

                // Compare total utility (score sum + diversity bonus), not
                // diversity alone, so a weak-but-diverse item cannot displace
                // a high-value item.
                val currentUtility = totalUtility(currentSelection)
                val newUtility = totalUtility(newSelection)

                if (newUtility > currentUtility) {
                    currentSelection = newSelection
                    improved = true
                    break
                }
            }
            if (improved) continue
        }

        return currentSelection
    }

    /**
     * Hard sequencing constraints (audit §7.2), enforced where the sequence
     * is constructed so a violating candidate can never be selected:
     *
     * - the same item family must never occupy two consecutive positions;
     * - the same subject must never run longer than [MAX_SAME_SUBJECT_RUN]
     *   consecutive positions (candidates without a subject are exempt).
     *
     * Returns true when [candidate] may be appended to [selected].
     */
    private fun canAppend(selected: List<ScoredCandidate>, candidate: ScoredCandidate): Boolean {
        val last = selected.lastOrNull() ?: return true
        if (candidate.candidate.itemFamilyId == last.candidate.itemFamilyId) return false
        val subjectId = candidate.candidate.subjectId ?: return true
        if (subjectId != last.candidate.subjectId) return true
        val trailingRun = selected.asReversed()
            .takeWhile { it.candidate.subjectId == subjectId }
            .count()
        return trailingRun < MAX_SAME_SUBJECT_RUN
    }

    /** True when the whole [selection] satisfies the hard sequencing constraints. */
    private fun satisfiesHardConstraints(selection: List<ScoredCandidate>): Boolean {
        for (index in selection.indices) {
            if (!canAppend(selection.subList(0, index), selection[index])) return false
        }
        return true
    }

    /**
     * Total utility of a selection: sum of adjusted scores plus a diversity
     * bonus that rewards family/source/difficulty spread.
     */
    private fun totalUtility(selection: List<ScoredCandidate>): Double {
        val scoreSum = selection.sumOf(ScoredCandidate::score)
        val diversityBonus = computeDiversityScore(selection)
        return scoreSum + diversityBonus
    }

    /**
     * Compute dynamic family penalty based on current queue composition.
     * Penalty grows with each additional item from the same family.
     */
    private fun computeDynamicFamilyPenalty(
        candidate: ScoredCandidate,
        usedFamilies: Map<String, Int>,
    ): Double {
        val familyCount = usedFamilies[candidate.candidate.itemFamilyId] ?: 0
        return (familyCount * FAMILY_PENALTY_WEIGHT)
            .coerceAtMost(MAX_DIVERSITY_PENALTY)
    }

    /**
     * Compute dynamic source penalty based on current queue composition.
     */
    private fun computeDynamicSourcePenalty(
        candidate: ScoredCandidate,
        usedSources: Map<String, Int>,
    ): Double {
        val sourceCount = candidate.candidate.sourceBundleId
            ?.let { usedSources[it] }
            ?: 0
        return (sourceCount * SOURCE_PENALTY_WEIGHT)
            .coerceAtMost(MAX_DIVERSITY_PENALTY)
    }

    /**
     * Compute diversity score for a selection. Higher is better.
     */
    private fun computeDiversityScore(selection: List<ScoredCandidate>): Double {
        val familyCount = selection.map { it.candidate.itemFamilyId }.distinct().size
        val sourceCount = selection.mapNotNull { it.candidate.sourceBundleId }.distinct().size
        val difficultyDistribution = selection.groupBy { it.difficultyBand }.size
        return familyCount * 1.0 + sourceCount * 0.5 + difficultyDistribution * 0.3
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

        // Due risk
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

        // Missing knowledge
        if (missingKnowledgeCount > 0 || candidate.knowledgeNodeIds.isEmpty()) {
            reasons += ReviewReason.MISSING_KNOWLEDGE_EVIDENCE
            reasons += ReviewReason.CALIBRATION_CHECK
        }

        // Mastery risk
        val masteryRisks = masteryStates.map { state ->
            val currentSupportedEvidenceMass = state.independentCorrectObservations
                .filter { it.calibrationSupportAt(now) == CalibrationSupport.SUPPORTED }
                .sumOf { it.evidenceWeight }
            val stale = state.status == com.tingyun.smartmistakebook.core.model.MasteryStatus.STALE ||
                state.lastEvidenceAtEpochMillis == null ||
                now < (state.lastEvidenceAtEpochMillis ?: 0) ||
                now - (state.lastEvidenceAtEpochMillis ?: now) >
                ClearlyMasteredForSkipPolicy.MAX_EVIDENCE_AGE_MILLIS
            when {
                state.status == com.tingyun.smartmistakebook.core.model.MasteryStatus.CONFLICTED -> {
                    reasons += ReviewReason.CONFLICTED_KNOWLEDGE
                    reasons += ReviewReason.CALIBRATION_CHECK
                    1.0
                }
                state.status == com.tingyun.smartmistakebook.core.model.MasteryStatus.UNKNOWN -> {
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
                else -> 1.0 - state.conservativeMasteryScore
            }
        }

        val weakness = (masteryRisks + List(
            maxOf(missingKnowledgeCount, if (candidate.knowledgeNodeIds.isEmpty()) 1 else 0),
        ) { 1.0 }).maxOrNull() ?: 1.0
        if (weakness >= WEAKNESS_THRESHOLD) reasons += ReviewReason.WEAK_KNOWLEDGE

        // Lapse risk
        val lapseIsRecent = memory?.lastLapseAtEpochMillis?.let { lastLapseAt ->
            now - lastLapseAt in 0..RECENT_LAPSE_WINDOW_MILLIS
        } == true
        val lapseScore = if (lapseIsRecent) {
            (memory!!.lapseCount / 3.0).coerceAtMost(1.0)
        } else {
            0.0
        }
        if (lapseScore > 0.0) reasons += ReviewReason.RECENT_LAPSE

        // Repeat mistake
        if (candidate.repeatMistakePriority > 0.0) reasons += ReviewReason.REPEATED_MISTAKE

        // Exam priority
        if (candidate.examPriority > 0.0) reasons += ReviewReason.EXAM_PRIORITY

        // Waiting fairness
        val waitingScore = candidate.eligibleSinceEpochMillis
            ?.let { eligibleSince ->
                val waitingDays = (now - eligibleSince).coerceAtLeast(0).toDouble() / DAY_MILLIS
                ((waitingDays - WAITING_GRACE_DAYS) / WAITING_BONUS_RAMP_DAYS)
                    .coerceIn(0.0, 1.0)
            }
            ?: 0.0
        if (waitingScore > 0.0) reasons += ReviewReason.LONG_WAITING

        // Early review check
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

        // Compute final score
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
            field("queue[$index].subjectId", candidate.subjectId)
            field("queue[$index].itemType", candidate.itemType)
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
    ) {
        /**
         * Replaces the candidate's static duration estimate with the
         * learner-bucket personalized prediction from [durationModel].
         *
         * Uses the candidate's real subject/item-type dimensions instead of
         * impersonating a subject with a knowledge-node id, so the duration
         * model buckets on `student × subject × itemType × difficulty`
         * (audit §7.3) instead of degrading to fewer dimensions.
         */
        fun withModeledDuration(durationModel: LogDurationModel, learnerId: String): ScoredCandidate {
            val predicted = durationModel.expectedSeconds(
                learnerId = learnerId,
                subjectId = candidate.subjectId,
                itemType = candidate.itemType,
                difficulty = candidate.difficulty,
            )
            val modeled = if (predicted > 0) {
                candidate.copy(
                    estimatedDurationSeconds = predicted.toInt().coerceAtLeast(1),
                )
            } else {
                candidate
            }
            return copy(candidate = modeled)
        }
    }

    private data class BeamState(
        val selected: List<ScoredCandidate>,
        val usedFamilies: Map<String, Int>,
        val usedSources: Map<String, Int>,
        val remainingSeconds: Int,
        val totalScore: Double,
    ) {
        val usedPracticeUnits: Set<String>
            get() = selected.map { it.candidate.practiceUnitId }.toSet()
    }

    companion object {
        const val VERSION = "review-planner-v2"
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
        /** Hard constraint (audit §7.2): same-subject consecutive run limit. */
        private const val MAX_SAME_SUBJECT_RUN = 3
        private const val DAY_MILLIS = 86_400_000.0
        private const val RECENT_LAPSE_WINDOW_MILLIS = 30L * 86_400_000L
        private const val WAITING_GRACE_DAYS = 7.0
        private const val WAITING_BONUS_RAMP_DAYS = 83.0
        private const val PLAN_FINGERPRINT_SCHEMA_VERSION = "review-plan-canonical-v7"
        private const val MAX_BEAM_STEPS = 20
    }
}

/** Returns a copy of the frequency map with [key] incremented by one. */
private fun Map<String, Int>.increment(key: String): Map<String, Int> {
    return this + (key to (this[key] ?: 0) + 1)
}
