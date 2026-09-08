package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.ReviewDifficultyBand
import com.tingyun.smartmistakebook.core.model.ReviewPlan
import com.tingyun.smartmistakebook.core.model.ReviewQueueItem
import com.tingyun.smartmistakebook.core.model.ReviewReason
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Spec 5 KC-to-question propagation: when a bound knowledge node's latest
 * evidence was negative and mastery sits below the drop threshold, every
 * question bound to that node gains continuous pressure (bigger drop ->
 * bigger weight -> more likely to make the budget cut) plus an early-entry
 * reason. Not a mechanical gate.
 */
internal const val KC_DROP_MASTERY_THRESHOLD = 0.6
internal const val KC_DROP_WEIGHT = 2.5

internal fun kcMasteryDropPressure(
    masteryStates: List<com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState>,
): Double = masteryStates
    .filter { it.lastEvidenceDirection == LearningEvidenceDirection.NEGATIVE.name }
    .maxOfOrNull { state ->
        (KC_DROP_MASTERY_THRESHOLD - state.conservativeMasteryScore) / KC_DROP_MASTERY_THRESHOLD
    }?.coerceIn(0.0, 1.0) ?: 0.0

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
    /** Subject the mistake belongs to; drives the V2 "same-subject run" hard constraint. */
    val subjectId: String? = null,
    /** Item-type dimension for the personalized duration model; null until the data layer exposes it. */
    val itemType: String? = null,
    /** Leech state (spec §2.16): paused from regular scheduling until re-taught. */
    val leech: Boolean = false,
    /**
     * Avoidance signal (spec §6 / D'Mello 2013): the learner repeatedly
     * switched away from this card and graded it poorly - a difficulty or
     * aversion marker routing it toward re-teaching.
     */
    val avoidance: Boolean = false,
) {
    init {
        require(practiceUnitId.isNotBlank()) { "Practice unit id must not be blank" }
        require(knowledgeNodeIds.none(String::isBlank)) { "Knowledge-node ids must not be blank" }
        require(itemFamilyId.isNotBlank()) { "Item family id must not be blank" }
        require(sourceBundleId == null || sourceBundleId.isNotBlank()) {
            "Source bundle id must not be blank when provided"
        }
        require(subjectId == null || subjectId.isNotBlank()) {
            "Subject id must not be blank when provided"
        }
        require(itemType == null || itemType.isNotBlank()) {
            "Item type must not be blank when provided"
        }
        require(difficulty.isFinite() && difficulty in 1.0..10.0) {
            "Difficulty must be between one and ten"
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
    /**
     * KC prerequisite graph (spec §6, linkage L4): knowledge node id to its
     * prerequisite knowledge node ids, sourced from the PREREQUISITE_OF
     * relation table. Missing entries mean "no known prerequisites".
     */
    val knowledgePrerequisites: Map<String, Set<String>> = emptyMap(),
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
) {
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

            // A session never repeats an item family or a source while a
            // fresh alternative still fits the remaining budget.
            val fresh = fitting.filter { scoredCandidate ->
                scoredCandidate.candidate.itemFamilyId !in usedFamilies &&
                    (scoredCandidate.candidate.sourceBundleId?.let(usedSources::contains) != true)
            }
            if (fresh.isEmpty()) break

            // Soft diversity: penalize recently seen families/sources on top.
            val adjustedCandidates = fresh.map { scored ->
                val familyPenalty = (scored.candidate.recentFamilyCount * FAMILY_PENALTY_WEIGHT)
                    .coerceAtMost(MAX_DIVERSITY_PENALTY)
                val sourcePenalty = (scored.candidate.recentSourceCount * SOURCE_PENALTY_WEIGHT)
                    .coerceAtMost(MAX_DIVERSITY_PENALTY)
                val adjustedScore = scored.score - familyPenalty - sourcePenalty
                scored.copy(score = adjustedScore.coerceAtLeast(0.0))
            }

            // Equal-priority candidates rotate through the difficulty cycle
            // (medium, easy, hard) so a session mixes difficulty bands.
            val desiredBand = DIFFICULTY_CYCLE[preferredBandIndex % DIFFICULTY_CYCLE.size]
            val scoreOrder = compareByDescending<ScoredCandidate>(ScoredCandidate::score)
                .thenBy { if (it.difficultyBand == desiredBand) 0 else 1 }
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
            masteryRiskFor(state, now, reasons)
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
        if (candidate.avoidance) reasons += ReviewReason.AVOIDANCE_SIGNAL

        // Spec 5 KC->question propagation: continuous pressure shared with V2.
        val kcDropPressure = kcMasteryDropPressure(masteryStates)
        if (kcDropPressure > 0.0) reasons += ReviewReason.KC_MASTERY_DROP
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
                reason == ReviewReason.EXAM_PRIORITY ||
                reason == ReviewReason.KC_MASTERY_DROP
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
                AVOIDANCE_WEIGHT * (if (candidate.avoidance) 1.0 else 0.0) +
                KC_DROP_WEIGHT * kcDropPressure +
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

    /**
     * Shared mastery-risk scoring for a single knowledge node (spec
     * dual-review-entry §3.2): CONFLICTED/STALE/UNKNOWN or unsupported evidence
     * is high risk; otherwise the weakness is the 7-day-half-life smoothed
     * mastery. Adds the matching reasons to [reasons]. Used both by
     * [scoreCandidate] (per bound node) and [scoreKnowledgeNode] (node itself).
     */
    private fun masteryRiskFor(
        state: com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState,
        now: Long,
        reasons: MutableSet<ReviewReason>,
    ): Double {
        val currentSupportedEvidenceMass = state.independentCorrectObservations
            .filter { it.calibrationSupportAt(now) == CalibrationSupport.SUPPORTED }
            .sumOf { it.evidenceWeight }
        val stale = state.status == MasteryStatus.STALE ||
            state.lastEvidenceAtEpochMillis == null ||
            now < (state.lastEvidenceAtEpochMillis ?: 0) ||
            now - (state.lastEvidenceAtEpochMillis ?: now) >
            ClearlyMasteredForSkipPolicy.MAX_EVIDENCE_AGE_MILLIS
        return when {
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
            // Spec 2.18: weakness input is the 7-day half-life smoothed
            // mastery, damping single-day swings.
            else -> 1.0 - MasterySmoothing.smoothedMasteryScore(state, now)
        }
    }

    /**
     * Knowledge-node variant of [scoreCandidate] (spec dual-review-entry §3.2):
     * scores a single knowledge node for today's knowledge review queue. The
     * node has no practice-unit memory, so due risk comes from how long ago its
     * last evidence was vs. the forgetting curve; mastery risk is shared with
     * [scoreCandidate]. A node that is mastered, fresh, and isn't early/weak is
     * skipped (returns null), mirroring [scoreCandidate]'s skip logic.
     */
    fun scoreKnowledgeNode(
        knowledgeNodeId: String,
        state: com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState?,
        now: Long,
    ): ScoredKnowledgeNode? {
        val reasons = linkedSetOf<ReviewReason>()
        val dueRisk = if (state == null) {
            reasons += ReviewReason.NEWLY_ADDED
            0.2
        } else {
            val lastEvidenceAt = state.lastEvidenceAtEpochMillis
            if (lastEvidenceAt == null) {
                reasons += ReviewReason.CALIBRATION_CHECK
                1.0
            } else if (now - lastEvidenceAt > ClearlyMasteredForSkipPolicy.MAX_EVIDENCE_AGE_MILLIS) {
                reasons += ReviewReason.DUE_RECALL_RISK
                reasons += ReviewReason.STALE_KNOWLEDGE
                // Stall risk grows with days since last evidence, capped at 1.
                val daysSince = (now - lastEvidenceAt).coerceAtLeast(0).toDouble() / DAY_MILLIS
                (daysSince / KNOWLEDGE_DUE_RAMP_DAYS).coerceAtMost(1.0)
            } else {
                // Fresh evidence: due risk is the forget-curve retention loss.
                reasons += ReviewReason.DUE_RECALL_RISK
                1.0 - state.masteryScore
            }
        }
        val masteryRisk = state
            ?.let { masteryRiskFor(it, now, reasons) }
            ?: run {
                reasons += ReviewReason.MISSING_KNOWLEDGE_EVIDENCE
                reasons += ReviewReason.CALIBRATION_CHECK
                1.0
            }
        val weakness = maxOf(dueRisk, masteryRisk)
        if (weakness >= WEAKNESS_THRESHOLD) reasons += ReviewReason.WEAK_KNOWLEDGE

        // Skip a node that is mastered, fresh, and not otherwise early/weak.
        val lastEvidenceForSkip = state?.lastEvidenceAtEpochMillis
        val masteredFresh = state != null &&
            state.status == MasteryStatus.MASTERED &&
            lastEvidenceForSkip != null &&
            now - lastEvidenceForSkip <= ClearlyMasteredForSkipPolicy.MAX_EVIDENCE_AGE_MILLIS
        val hasEarlyReason = reasons.any { reason ->
            reason == ReviewReason.CONFLICTED_KNOWLEDGE ||
                reason == ReviewReason.STALE_KNOWLEDGE ||
                reason == ReviewReason.CALIBRATION_CHECK
        }
        if (masteredFresh && !hasEarlyReason) return null
        if (reasons.isEmpty()) return null

        val score = (
            DUE_WEIGHT * dueRisk +
                WEAKNESS_WEIGHT * masteryRisk
            ).coerceAtLeast(0.0)
        return ScoredKnowledgeNode(
            knowledgeNodeId = knowledgeNodeId,
            score = score,
            reasons = reasons,
            difficultyBand = knowledgeDifficultyBand(masteryRisk),
        )
    }

    /**
     * 知识点复习的难度档（spec dual-review-entry §3.2 "难度循环"）：由掌握度风险推出——
     * 越薄弱/越无证据的点越难复习。与错题排程的 [difficultyBand] 同构，供
     * [selectKnowledgeReviewQueue] 在分数平局时轮换难度档。
     */
    private fun knowledgeDifficultyBand(masteryRisk: Double): ReviewDifficultyBand = when {
        masteryRisk >= 1.0 -> ReviewDifficultyBand.HARD
        masteryRisk >= WEAKNESS_THRESHOLD -> ReviewDifficultyBand.MEDIUM
        else -> ReviewDifficultyBand.EASY
    }

    private fun difficultyBand(difficulty: Double): ReviewDifficultyBand = when {
        difficulty < EASY_DIFFICULTY_CEILING -> ReviewDifficultyBand.EASY
        difficulty < MEDIUM_DIFFICULTY_CEILING -> ReviewDifficultyBand.MEDIUM
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

    /** Result of [scoreKnowledgeNode]: a knowledge node + its composite score. */
    data class ScoredKnowledgeNode(
        val knowledgeNodeId: String,
        val score: Double,
        val reasons: Set<ReviewReason>,
        /** 掌握度风险推出的复习难度档（spec §3.2 难度循环），见 [knowledgeDifficultyBand]。 */
        val difficultyBand: ReviewDifficultyBand,
    )

    companion object {
        const val VERSION = LearningCoreVersions.REVIEW_COMPOSITE
        /** 会话内难度轮换顺序；错题与知识点排程共用（spec dual-review-entry §3.2）。 */
        internal val DIFFICULTY_CYCLE = listOf(
            ReviewDifficultyBand.MEDIUM,
            ReviewDifficultyBand.EASY,
            ReviewDifficultyBand.HARD,
        )
        private const val EASY_DIFFICULTY_CEILING = 4.0
        private const val MEDIUM_DIFFICULTY_CEILING = 7.0
        private const val WEAKNESS_THRESHOLD = 0.35
        private const val DUE_WEIGHT = 5.0
        private const val WEAKNESS_WEIGHT = 3.0
        private const val LAPSE_WEIGHT = 1.0
        private const val REPEAT_MISTAKE_WEIGHT = 2.0
        private const val AVOIDANCE_WEIGHT = 1.0
        private const val EXAM_WEIGHT = 2.0
        private const val WAITING_WEIGHT = 1.5
        private const val FAMILY_PENALTY_WEIGHT = 0.3
        private const val SOURCE_PENALTY_WEIGHT = 0.2
        private const val MAX_DIVERSITY_PENALTY = 1.5
        private const val DAY_MILLIS = 86_400_000.0
        private const val RECENT_LAPSE_WINDOW_MILLIS = 30L * 86_400_000L
        private const val WAITING_GRACE_DAYS = 7.0
        private const val WAITING_BONUS_RAMP_DAYS = 83.0
        /** Days of evidence age at which a knowledge node's due risk saturates. */
        private const val KNOWLEDGE_DUE_RAMP_DAYS = 30.0
        private const val PLAN_FINGERPRINT_SCHEMA_VERSION = "review-plan-canonical-v5"
    }
}
