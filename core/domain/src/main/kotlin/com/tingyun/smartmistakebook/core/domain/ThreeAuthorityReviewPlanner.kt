package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import kotlin.math.min

/**
 * Qualitative mastery signal copied from learner-mastery.db for one planning decision.
 *
 * Raw evidence weights stay inside the mastery authority. Review planning receives only the
 * bounded, versioned decision signal it needs.
 */
data class ReviewKnowledgeSignal(
    val knowledgeNode: KnowledgeNodeRef,
    val recallState: ReviewRecallState,
    val trend: ReviewKnowledgeTrend,
    val recallDueAtEpochMillis: Long?,
) {
    init {
        require(recallDueAtEpochMillis == null || recallDueAtEpochMillis >= 0L) {
            "Knowledge recall due time must not be negative"
        }
    }
}

enum class ReviewRecallState {
    NEEDS_REINFORCEMENT,
    FAMILIARIZING,
    STEADY,
    UNRESOLVED,
}

enum class ReviewKnowledgeTrend {
    IMPROVING,
    STABLE,
    WAVERING,
    UNKNOWN,
}

enum class ReviewCognitiveLoad {
    LIGHT,
    MODERATE,
    HEAVY,
}

enum class ReviewPriorityReason {
    NEWLY_SAVED,
    DUE_RECALL,
    RECENT_ERROR,
    ANSWER_REVEALED,
    REPEATED_ERROR,
    MASTERY_CONFLICT,
    LONG_WAITING,
}

enum class ReviewPacingLevel(
    val timeBudgetSeconds: Int,
    val maxItemCount: Int,
) {
    LIGHT(10 * 60, 4),
    STANDARD(15 * 60, 5),
    STRONG(25 * 60, 8),
}

data class ReviewExamTarget(
    val subject: SubjectKind,
    val examAtEpochMillis: Long,
) {
    init {
        require(subject != SubjectKind.GENERAL) {
            "Review exam target requires a high-school subject"
        }
        require(examAtEpochMillis >= 0L) {
            "Review exam target time must not be negative"
        }
    }
}

/**
 * Read-only planning projection of one saved student mistake.
 *
 * It contains stable cross-store references, never question text or a database row identifier.
 */
data class ThreeAuthorityReviewCandidate(
    val candidateId: String,
    val problemRevision: StudentProblemRevisionRef,
    val knowledgeNodes: Set<KnowledgeNodeRef>,
    val problemFamilyId: String,
    val solutionFamilyId: String?,
    val presentationFamilyId: String?,
    val estimatedDurationSeconds: Int,
    val cognitiveLoad: ReviewCognitiveLoad,
    val availableAtEpochMillis: Long,
    val dueAtEpochMillis: Long?,
    val waitingSinceEpochMillis: Long,
    val priorityReasons: Set<ReviewPriorityReason>,
) {
    init {
        require(candidateId.isBoundedReviewIdentity()) { "Review candidate id is invalid" }
        require(problemFamilyId.isBoundedReviewIdentity()) { "Problem family id is invalid" }
        require(solutionFamilyId == null || solutionFamilyId.isBoundedReviewIdentity()) {
            "Solution family id is invalid"
        }
        require(presentationFamilyId == null || presentationFamilyId.isBoundedReviewIdentity()) {
            "Presentation family id is invalid"
        }
        require(estimatedDurationSeconds in 1..MAX_ITEM_DURATION_SECONDS) {
            "Review duration is outside the supported range"
        }
        require(availableAtEpochMillis >= 0L) { "Review availability must not be negative" }
        require(dueAtEpochMillis == null || dueAtEpochMillis >= 0L) {
            "Review due time must not be negative"
        }
        require(waitingSinceEpochMillis in 0L..availableAtEpochMillis) {
            "Review waiting time must not follow availability"
        }
        require(priorityReasons.isNotEmpty()) { "A review candidate needs a scheduling reason" }
        require(knowledgeNodes.all { it.subject == problemRevision.problem.subject }) {
            "Review knowledge must stay in the problem subject"
        }
    }

    companion object {
        const val MAX_ITEM_DURATION_SECONDS = 30 * 60
    }
}

data class ThreeAuthorityReviewPlanningRequest(
    val learnerId: String,
    val candidates: List<ThreeAuthorityReviewCandidate>,
    val knowledgeSignals: List<ReviewKnowledgeSignal>,
    val masteryProjectionVersion: String,
    val localDayEpochDay: Long,
    val timeZoneId: String,
    val timeBudgetSeconds: Int,
    val maxItemCount: Int = DEFAULT_MAX_ITEM_COUNT,
    val examTarget: ReviewExamTarget? = null,
    val planningAtEpochMillis: Long,
) {
    init {
        require(learnerId.isBoundedReviewIdentity()) { "Review learner id is invalid" }
        require(candidates.size <= MAX_CANDIDATE_COUNT) {
            "Review planning candidate budget exceeded"
        }
        require(knowledgeSignals.size <= MAX_KNOWLEDGE_SIGNAL_COUNT) {
            "Review planning knowledge-signal budget exceeded"
        }
        require(candidates.map(ThreeAuthorityReviewCandidate::candidateId).distinct().size == candidates.size) {
            "Review planning candidates must be unique"
        }
        require(candidates.all { it.problemRevision.problem.learnerId == learnerId }) {
            "Review candidates must belong to the requested learner"
        }
        require(
            knowledgeSignals.map { it.knowledgeNode.canonicalFingerprint }.distinct().size ==
                knowledgeSignals.size,
        ) {
            "Review planning knowledge signals must be unique"
        }
        require(masteryProjectionVersion.isBoundedReviewIdentity()) {
            "Mastery projection version is invalid"
        }
        require(timeZoneId.isBoundedReviewIdentity()) { "Review time-zone id is invalid" }
        require(timeBudgetSeconds in 0..MAX_TIME_BUDGET_SECONDS) {
            "Review time budget is outside the supported range"
        }
        require(maxItemCount in 1..MAX_ITEM_COUNT) {
            "Review item-count budget is outside the supported range"
        }
        require(planningAtEpochMillis >= 0L) { "Review planning time must not be negative" }
    }

    companion object {
        const val MAX_CANDIDATE_COUNT = 5_000
        const val MAX_KNOWLEDGE_SIGNAL_COUNT = 10_000
        const val MAX_TIME_BUDGET_SECONDS = 4 * 60 * 60
        const val DEFAULT_MAX_ITEM_COUNT = 5
        const val MAX_ITEM_COUNT = 512
    }
}

data class ThreeAuthorityReviewPlanItem(
    val candidateId: String,
    val problemRevision: StudentProblemRevisionRef,
    val scheduledOrder: Int,
    val estimatedDurationSeconds: Int,
    val priorityReasons: Set<ReviewPriorityReason>,
)

data class ThreeAuthorityReviewPlan(
    val planId: String,
    val canonicalFingerprint: String,
    val learnerId: String,
    val localDayEpochDay: Long,
    val timeZoneId: String,
    val timeBudgetSeconds: Int,
    val maxItemCount: Int = ThreeAuthorityReviewPlanningRequest.DEFAULT_MAX_ITEM_COUNT,
    val generatedAtEpochMillis: Long,
    val plannerVersion: String,
    val masteryProjectionVersion: String,
    val items: List<ThreeAuthorityReviewPlanItem>,
) {
    val estimatedDurationSeconds: Int
        get() = items.sumOf(ThreeAuthorityReviewPlanItem::estimatedDurationSeconds)

    init {
        require(maxItemCount in 1..ThreeAuthorityReviewPlanningRequest.MAX_ITEM_COUNT) {
            "Review plan item-count budget is invalid"
        }
        require(estimatedDurationSeconds <= timeBudgetSeconds) {
            "Review plan exceeds its time budget"
        }
        require(items.size <= maxItemCount) {
            "Review plan exceeds its item-count budget"
        }
        require(items.map(ThreeAuthorityReviewPlanItem::scheduledOrder) == items.indices.toList()) {
            "Review plan order must be contiguous"
        }
    }
}

/**
 * Deterministic planner for student-mistakes.db candidates plus learner-mastery.db signals.
 *
 * The join happens only in memory. It never reads either database and cannot create questions.
 */
class ThreeAuthorityReviewPlanner {
    fun plan(request: ThreeAuthorityReviewPlanningRequest): ThreeAuthorityReviewPlan {
        val signalByFingerprint =
            request.knowledgeSignals.associateBy { it.knowledgeNode.canonicalFingerprint }
        val scored =
            request.candidates
                .asSequence()
                .filter { it.availableAtEpochMillis <= request.planningAtEpochMillis }
                .map { candidate ->
                    ScoredCandidate(
                        candidate = candidate,
                        score =
                            score(
                                candidate = candidate,
                                signalByFingerprint = signalByFingerprint,
                                examTarget = request.examTarget,
                                nowEpochMillis = request.planningAtEpochMillis,
                            ),
                    )
                }
                .sortedWith(
                    compareByDescending<ScoredCandidate>(ScoredCandidate::score)
                        .thenBy { it.candidate.waitingSinceEpochMillis }
                        .thenBy { it.candidate.candidateId },
                )
                .toMutableList()

        val selected = mutableListOf<ScoredCandidate>()
        val usedProblemFamilies = hashSetOf<String>()
        val usedSolutionFamilies = hashSetOf<String>()
        val usedPresentationFamilies = hashSetOf<String>()
        var remainingSeconds = request.timeBudgetSeconds
        var cadenceIndex = 0

        while (
            remainingSeconds > 0 &&
            selected.size < request.maxItemCount &&
            scored.isNotEmpty()
        ) {
            scored.removeAll { candidate ->
                candidate.candidate.problemFamilyId in usedProblemFamilies ||
                    candidate.candidate.solutionFamilyId?.let(usedSolutionFamilies::contains) == true ||
                    candidate.candidate.presentationFamilyId
                        ?.let(usedPresentationFamilies::contains) == true
            }
            val fitting =
                scored.filter { it.candidate.estimatedDurationSeconds <= remainingSeconds }
            if (fitting.isEmpty()) break

            val highest = fitting.first()
            val preferredLoad = LOAD_CADENCE[cadenceIndex % LOAD_CADENCE.size]
            val cadenceChoice =
                fitting.firstOrNull {
                    it.candidate.cognitiveLoad == preferredLoad &&
                        highest.score - it.score <= CADENCE_SCORE_WINDOW
                }
            val next = cadenceChoice ?: highest
            selected += next
            scored -= next
            remainingSeconds -= next.candidate.estimatedDurationSeconds
            usedProblemFamilies += next.candidate.problemFamilyId
            next.candidate.solutionFamilyId?.let(usedSolutionFamilies::add)
            next.candidate.presentationFamilyId?.let(usedPresentationFamilies::add)
            cadenceIndex += 1
        }

        val fingerprint = fingerprint(request, selected)
        return ThreeAuthorityReviewPlan(
            planId = "review-$fingerprint",
            canonicalFingerprint = fingerprint,
            learnerId = request.learnerId,
            localDayEpochDay = request.localDayEpochDay,
            timeZoneId = request.timeZoneId,
            timeBudgetSeconds = request.timeBudgetSeconds,
            maxItemCount = request.maxItemCount,
            generatedAtEpochMillis = request.planningAtEpochMillis,
            plannerVersion = VERSION,
            masteryProjectionVersion = request.masteryProjectionVersion,
            items =
                selected.mapIndexed { index, item ->
                    ThreeAuthorityReviewPlanItem(
                        candidateId = item.candidate.candidateId,
                        problemRevision = item.candidate.problemRevision,
                        scheduledOrder = index,
                        estimatedDurationSeconds = item.candidate.estimatedDurationSeconds,
                        priorityReasons = item.candidate.priorityReasons,
                    )
                },
        )
    }

    private fun score(
        candidate: ThreeAuthorityReviewCandidate,
        signalByFingerprint: Map<String, ReviewKnowledgeSignal>,
        examTarget: ReviewExamTarget?,
        nowEpochMillis: Long,
    ): Long {
        val matchingSignals =
            candidate.knowledgeNodes.mapNotNull {
                signalByFingerprint[it.canonicalFingerprint]
            }
        val unresolvedCount = candidate.knowledgeNodes.size - matchingSignals.size
        val knowledgeRisk =
            (
                matchingSignals.map(::knowledgeRisk) +
                    List(unresolvedCount.coerceAtLeast(if (candidate.knowledgeNodes.isEmpty()) 1 else 0)) {
                        UNRESOLVED_KNOWLEDGE_SCORE
                    }
            ).maxOrNull() ?: UNRESOLVED_KNOWLEDGE_SCORE
        val dueScore =
            when {
                candidate.dueAtEpochMillis == null -> NEW_DUE_SCORE
                candidate.dueAtEpochMillis <= nowEpochMillis ->
                    DUE_SCORE +
                        (
                            (nowEpochMillis - candidate.dueAtEpochMillis) / DAY_MILLIS
                        ).coerceAtMost(MAX_OVERDUE_DAYS) * OVERDUE_DAY_SCORE
                else -> 0L
            }
        val waitingDays =
            ((nowEpochMillis - candidate.waitingSinceEpochMillis).coerceAtLeast(0L) / DAY_MILLIS)
                .coerceAtMost(MAX_WAITING_DAYS)
        val waitingScore = waitingDays * WAITING_DAY_SCORE
        val reasonScore = candidate.priorityReasons.sumOf(::reasonScore)
        val examScore =
            examScore(
                candidate = candidate,
                target = examTarget,
                nowEpochMillis = nowEpochMillis,
            )
        return knowledgeRisk + dueScore + waitingScore + reasonScore + examScore
    }

    private fun examScore(
        candidate: ThreeAuthorityReviewCandidate,
        target: ReviewExamTarget?,
        nowEpochMillis: Long,
    ): Long {
        if (target == null) return 0L
        if (candidate.knowledgeNodes.none { it.subject == target.subject }) return 0L
        val remainingDays = (target.examAtEpochMillis - nowEpochMillis).coerceAtLeast(0L) / DAY_MILLIS
        if (remainingDays > MAX_EXAM_PRIORITY_DAYS) return 0L
        val urgency = MAX_EXAM_PRIORITY_DAYS - remainingDays + 1L
        return min(urgency * EXAM_URGENCY_DAY_SCORE, MAX_EXAM_SCORE)
    }

    private fun knowledgeRisk(signal: ReviewKnowledgeSignal): Long {
        val stateScore =
            when (signal.recallState) {
                ReviewRecallState.NEEDS_REINFORCEMENT -> 4_000L
                ReviewRecallState.FAMILIARIZING -> 2_000L
                ReviewRecallState.STEADY -> 0L
                ReviewRecallState.UNRESOLVED -> UNRESOLVED_KNOWLEDGE_SCORE
            }
        val trendScore =
            when (signal.trend) {
                ReviewKnowledgeTrend.WAVERING -> 1_800L
                ReviewKnowledgeTrend.UNKNOWN -> 900L
                ReviewKnowledgeTrend.IMPROVING -> 200L
                ReviewKnowledgeTrend.STABLE -> 0L
            }
        return stateScore + trendScore
    }

    private fun reasonScore(reason: ReviewPriorityReason): Long =
        when (reason) {
            ReviewPriorityReason.RECENT_ERROR -> 2_500L
            ReviewPriorityReason.MASTERY_CONFLICT -> 2_200L
            ReviewPriorityReason.ANSWER_REVEALED -> 2_100L
            ReviewPriorityReason.REPEATED_ERROR -> 1_800L
            ReviewPriorityReason.DUE_RECALL -> 1_000L
            ReviewPriorityReason.NEWLY_SAVED -> 800L
            ReviewPriorityReason.LONG_WAITING -> 600L
        }

    private fun fingerprint(
        request: ThreeAuthorityReviewPlanningRequest,
        selected: List<ScoredCandidate>,
    ): String {
        val digest =
            CanonicalSha256("three-authority-review-plan-v1")
                .field("plannerVersion", VERSION)
                .field("learnerId", request.learnerId)
                .field("localDayEpochDay", request.localDayEpochDay)
                .field("timeZoneId", request.timeZoneId)
                .field("timeBudgetSeconds", request.timeBudgetSeconds)
                .field("maxItemCount", request.maxItemCount)
                .nullableField("examSubject", request.examTarget?.subject?.name)
                .nullableLongField("examAtEpochMillis", request.examTarget?.examAtEpochMillis)
                .field("planningAtEpochMillis", request.planningAtEpochMillis)
                .field("masteryProjectionVersion", request.masteryProjectionVersion)
                .field("itemCount", selected.size)
        selected.forEachIndexed { index, item ->
            digest
                .field("item[$index].candidateId", item.candidate.candidateId)
                .field(
                    "item[$index].problemRevision",
                    item.candidate.problemRevision.canonicalFingerprint,
                )
                .field("item[$index].score", item.score)
                .field("item[$index].duration", item.candidate.estimatedDurationSeconds)
        }
        return digest.finish()
    }

    private data class ScoredCandidate(
        val candidate: ThreeAuthorityReviewCandidate,
        val score: Long,
    )

    companion object {
        const val VERSION = "three-authority-review-planner-v1"
        private val LOAD_CADENCE =
            listOf(
                ReviewCognitiveLoad.MODERATE,
                ReviewCognitiveLoad.LIGHT,
                ReviewCognitiveLoad.HEAVY,
            )
        private const val CADENCE_SCORE_WINDOW = 400L
        private const val DAY_MILLIS = 86_400_000L
        private const val MAX_OVERDUE_DAYS = 90L
        private const val MAX_WAITING_DAYS = 180L
        private const val MAX_EXAM_PRIORITY_DAYS = 14L
        private const val EXAM_URGENCY_DAY_SCORE = 100L
        private const val MAX_EXAM_SCORE = 1_500L
        private const val DUE_SCORE = 3_000L
        private const val NEW_DUE_SCORE = 500L
        private const val OVERDUE_DAY_SCORE = 20L
        private const val WAITING_DAY_SCORE = 10L
        private const val UNRESOLVED_KNOWLEDGE_SCORE = 2_800L
    }
}

private fun String.isBoundedReviewIdentity(): Boolean =
    isNotBlank() &&
        this == trim() &&
        length <= 256 &&
        none(Char::isISOControl)
