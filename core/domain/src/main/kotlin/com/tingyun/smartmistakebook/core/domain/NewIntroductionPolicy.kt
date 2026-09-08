package com.tingyun.smartmistakebook.core.domain

/**
 * Daily introduction policy for batch-intaked mistakes (spec
 * `docs/specs/batch-intake-spec.md` §2): intake only adds inventory —
 * introduction converts a bounded slice of it into today's plan.
 *
 * Semantics:
 * - Introduction is budgeted by TIME, not by count: the introduced
 *   candidates' estimated durations must fit inside
 *   [timeBudgetSeconds] × [NEW_INTRODUCE_SHARE] (and inside the whole
 *   budget), so "new material never crowds out due reviews".
 * - Candidates are introduced in priority order (exam pressure →
 *   hypercorrection-ready high-confidence errors → FIFO by creation).
 * - When an unmet exam day exists, the per-day share is overridden by the
 *   catch-up quota ceil(remainingIntake / daysLeft), still capped by what
 *   fits in the budgeted time slice.
 * - Everything rejected today stays in the intake backlog with NO learning
 *   pressure: it simply is not a candidate today (waiting accrues only
 *   from introduction, never from creation).
 *
 * Pure function; fully deterministic and testable.
 */
object NewIntroductionPolicy {

    /**
     * Share of the daily review time budget that new introductions may
     * occupy. [I] 1/3 keeps due reviews dominant while giving a genuine
     * daily path through the backlog; calibration channel to revisit.
     */
    const val NEW_INTRODUCE_SHARE = 1.0 / 3.0

    /** Safety cap on one introduced item's share of the slice — a single
     *  oversized estimate cannot consume the whole slice and starve others. */
    const val MAX_SINGLE_ITEM_SHARE = 0.5

    data class IntakeCandidate(
        val practiceUnitId: String,
        /** Local per-learner duration estimate (spec §2 L1/L2/L3). Must be positive. */
        val estimatedDurationSeconds: Int,
        /** Exam-driven priority 0..1 (existing examPriority ramp). */
        val examPriority: Double = 0.0,
        /**
         * Confidence-at-error when the source flow recorded a failed attempt
         * (true mistake) — high-confidence errors introduce first
         * (hypercorrection, Butterfield & Metcalfe 2001). Null = no signal.
         */
        val confidenceAtError: ConfidenceLevel? = null,
        /** Intake creation time — FIFO tie-break (first recorded, first learned). */
        val createdAtEpochMillis: Long,
    ) {
        init {
            require(estimatedDurationSeconds > 0) { "Estimated duration must be positive" }
            require(examPriority in 0.0..1.0) { "Exam priority must be in 0..1" }
            require(createdAtEpochMillis >= 0) { "Creation time must not be negative" }
        }
    }

    data class IntakeDecision(
        /** Candidates introduced into today's plan (priority order preserved). */
        val introduced: List<IntakeCandidate>,
        /** Candidates left in the intake backlog — NOT candidates today. */
        val deferred: List<IntakeCandidate>,
        /** Time slice actually granted to introductions (seconds). */
        val grantedSliceSeconds: Int,
    ) {
        init {
            require(grantedSliceSeconds >= 0) { "Granted slice must not be negative" }
        }
    }

    /**
     * @param timeBudgetSeconds the WHOLE daily review budget; introductions
     *   compete only for [NEW_INTRODUCE_SHARE] of it.
     * @param daysLeftToExam 0/null = no exam pressure (fixed share);
     *   >0 enables catch-up quota ceil(remainingIntake / daysLeft).
     */
    fun decide(
        candidates: List<IntakeCandidate>,
        timeBudgetSeconds: Int,
        daysLeftToExam: Int? = null,
    ): IntakeDecision {
        require(timeBudgetSeconds > 0) { "Time budget must be positive" }
        if (candidates.isEmpty()) {
            return IntakeDecision(emptyList(), emptyList(), 0)
        }
        require(daysLeftToExam == null || daysLeftToExam >= 0) {
            "Days left to exam must not be negative"
        }

        val slice = (timeBudgetSeconds * NEW_INTRODUCE_SHARE).toInt().coerceAtLeast(1)
        val singleCap = (slice * MAX_SINGLE_ITEM_SHARE).toInt().coerceAtLeast(1)

        // Exam catch-up: when the exam is close, the per-day TIME slice may
        // grow so the backlog fits the remaining days — but never beyond what
        // the whole budget can carry, and each item stays capped.
        val catchUpCount = daysLeftToExam?.takeIf { it > 0 }?.let { daysLeft ->
            // ceil(remaining / days) items per day; translate the count into a
            // time slice big enough for the cheapest `count` items of the
            // priority-ordered list.
            val orderedForQuota = candidates.sortedWith(priorityOrder())
            val count = orderedForQuota.size / daysLeft +
                if (orderedForQuota.size % daysLeft == 0) 0 else 1
            if (count <= 0) {
                slice
            } else {
                val cheapestFirst = orderedForQuota.map(IntakeCandidate::estimatedDurationSeconds).sorted()
                cheapestFirst.take(count).sum().coerceAtMost(timeBudgetSeconds)
            }
        } ?: slice
        val effectiveSlice = maxOf(slice, catchUpCount).coerceAtMost(timeBudgetSeconds)
        val effectiveCap = (effectiveSlice * MAX_SINGLE_ITEM_SHARE).toInt().coerceAtLeast(1)

        var remaining = effectiveSlice
        val introduced = mutableListOf<IntakeCandidate>()
        val deferred = mutableListOf<IntakeCandidate>()
        val ordered = candidates.sortedWith(priorityOrder())
        // Deadlock guard: when EVERY remaining candidate is oversized for the
        // normal slice, the day expands to the full budget and the cap rises
        // to the highest-priority candidate's TRUE duration so it can be
        // introduced. Without this, an oversized problem would defer forever —
        // a silent permanent stall of the backlog.
        val allOversized = ordered.isNotEmpty() &&
            ordered.all { it.estimatedDurationSeconds > effectiveCap }
        val (sliceBudget, capBudget) = if (allOversized) {
            val topDuration = ordered.first().estimatedDurationSeconds
            topDuration.coerceAtMost(timeBudgetSeconds) to topDuration
        } else {
            effectiveSlice to effectiveCap
        }
        remaining = sliceBudget
        for (candidate in ordered) {
            val duration = candidate.estimatedDurationSeconds
            // A single oversized item may occupy at most half the slice — but
            // it is billed at its TRUE duration, never a capped fiction.
            // An item whose true duration exceeds the cap is NOT introduced
            // today (its real solving time would silently overshoot the
            // promised slice). The deadlock guard above guarantees an
            // all-oversized backlog still makes progress.
            if (duration > capBudget) {
                deferred += candidate
                continue
            }
            if (duration <= remaining) {
                introduced += candidate
                remaining -= duration
            } else {
                deferred += candidate
            }
        }
        return IntakeDecision(
            introduced = introduced,
            deferred = deferred,
            grantedSliceSeconds = effectiveSlice - remaining,
        )
    }

    /** Priority order: exam pressure → high-confidence error (hypercorrection) → FIFO. */
    private fun priorityOrder(): Comparator<IntakeCandidate> = compareByDescending(
        IntakeCandidate::examPriority,
    ).thenByDescending {
        // HIGH confidence errors come first; null/LOW last. Ordinal order is
        // LOW < MEDIUM < HIGH (see ConfidenceLevel).
        it.confidenceAtError?.ordinal ?: -1
    }.thenBy(IntakeCandidate::createdAtEpochMillis)

    /**
     * Coverage-plan preview (spec `batch-intake-spec.md` §6, P3 algorithm
     * support): how many days the intake backlog needs at the daily pace,
     * with an optional end date. Pure presentation math — the UI renders
     * "每天 N 题 × M 天全覆盖" from this; no scheduling side effects.
     *
     * @param backlogCount questions still in the intake backlog.
     * @param typicalItemSeconds the per-item duration estimate the daily
     *   slice is computed from (e.g. the backlog's median estimate).
     * @param timeBudgetSeconds the WHOLE daily review budget.
     * @param daysLeftToExam 0/null = no exam pressure.
     */
    data class CoveragePreview(
        val perDayItems: Int,
        val daysToCover: Int,
        val backlogCount: Int,
        /**
         * Items that could NOT be scheduled inside the simulation horizon —
         * either they are individually larger than a whole daily budget
         * (a "special session" problem, never a daily-intake item) or the
         * horizon was exhausted. The UI must surface these separately
         * instead of claiming full coverage.
         */
        val unschedulableCount: Int = 0,
    )

    fun preview(
        backlogCount: Int,
        typicalItemSeconds: Int,
        timeBudgetSeconds: Int,
        daysLeftToExam: Int? = null,
    ): CoveragePreview {
        require(backlogCount >= 0) { "Backlog must not be negative" }
        require(typicalItemSeconds > 0) { "Item estimate must be positive" }
        require(timeBudgetSeconds > 0) { "Time budget must be positive" }
        if (backlogCount == 0) return CoveragePreview(0, 0, 0)

        val sliceSeconds = (timeBudgetSeconds * NEW_INTRODUCE_SHARE).toInt().coerceAtLeast(1)
        val perDayByTime = (sliceSeconds / typicalItemSeconds).coerceAtLeast(1)

        // Exam catch-up: cover the backlog inside the remaining days, still
        // bounded by what the whole daily budget can carry.
        val perDayByExam = daysLeftToExam?.takeIf { it > 0 }?.let { days ->
            val quota = backlogCount / days + if (backlogCount % days == 0) 0 else 1
            val maxByBudget = (timeBudgetSeconds / typicalItemSeconds).coerceAtLeast(1)
            quota.coerceAtMost(maxByBudget)
        } ?: perDayByTime

        val perDay = maxOf(perDayByTime, perDayByExam).coerceAtLeast(1)
        val days = backlogCount / perDay + if (backlogCount % perDay == 0) 0 else 1
        return CoveragePreview(perDayItems = perDay, daysToCover = days, backlogCount = backlogCount)
    }

    /**
     * Coverage-preview assembly over the ACTUAL intake backlog (spec §6 P3):
     * the typical per-item estimate is the backlog's MEDIAN estimate — a
     * median is robust to a few oversized outliers (a single 20-minute
     * problem must not inflate the per-day count for everyone else). The
     * median of an empty backlog is undefined, so callers should short-circuit
     * on an empty backlog before invoking this.
     *
     * Pure and deterministic; the UI layer renders the returned preview.
     */
    /**
     * Coverage-preview assembly over the ACTUAL intake backlog (spec §6 P3).
     *
     * The preview SIMULATES the real introduction policy day by day: each day
     * runs [decide] over the remaining backlog, the introduced items leave the
     * backlog, the deferred ones carry over — until the backlog is empty. This
     * is deliberately NOT arithmetic (budget/typical-duration): the real
     * policy's per-item true-duration billing and the oversized-item deferral
     * make any closed-form estimate diverge from actual behavior, and a
     * coverage promise that differs from what the scheduler will actually do
     * is a runtime default the student pays for.
     *
     * [daysLeftToExam] shrinks by one each simulated day (the exam pressure
     * ramps exactly as it will in production); null keeps the fixed slice.
     *
     * Pure and deterministic; the UI layer renders the returned preview.
     */
    fun previewBacklog(
        backlog: List<IntakeCandidate>,
        timeBudgetSeconds: Int,
        daysLeftToExam: Int? = null,
        maxSimulatedDays: Int = MAX_SIMULATED_DAYS,
    ): CoveragePreview {
        require(timeBudgetSeconds > 0) { "Time budget must be positive" }
        if (backlog.isEmpty()) return CoveragePreview(0, 0, 0)

        // Items larger than a whole daily budget can never be introduced by
        // the daily-intake policy — they need a special dedicated session.
        // Count them out up front so the simulation does not loop on them.
        val (unschedulable, schedulable) = backlog.partition {
            it.estimatedDurationSeconds > timeBudgetSeconds
        }
        var remaining = schedulable
        var totalIntroduced = 0
        var days = 0
        var daysToExam = daysLeftToExam
        while (remaining.isNotEmpty() && days < maxSimulatedDays) {
            val decision = decide(
                candidates = remaining,
                timeBudgetSeconds = timeBudgetSeconds,
                daysLeftToExam = daysToExam,
            )
            totalIntroduced += decision.introduced.size
            remaining = decision.deferred
            days += 1
            // Exam pressure is relative to a fixed exam day: each simulated
            // day brings it one day closer.
            daysToExam?.let { if (it > 0) daysToExam = it - 1 }
        }
        val perDay = if (days == 0) 0 else totalIntroduced / days + if (totalIntroduced % days == 0) 0 else 1
        return CoveragePreview(
            perDayItems = perDay,
            daysToCover = days,
            backlogCount = backlog.size,
            unschedulableCount = unschedulable.size + remaining.size,
        )
    }

    /** Simulation safety bound: a pathological backlog must not loop forever. */
    const val MAX_SIMULATED_DAYS = 365
}

/**
 * Confidence-at-error level judged from multi-source signals at answer time
 * (spec §4): student self-report → model semantics → local UI signals →
 * objective inference. Conflicts resolve LOW (a confidently-wrong item must
 * be re-taught early; a low-confidence correct answer must not inflate
 * stability) — the safe direction is always to trust the student LESS.
 */
enum class ConfidenceLevel {
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * Multi-source confidence assessment (spec `batch-intake-spec.md` §4).
 * Pure and deterministic: the model supplies only its semantic tier; every
 * threshold and the conflict rule are local constants.
 */
object AttemptConfidenceAssessment {

    /** Attention factor below which the attempt is treated as distracted (LOW signal). */
    const val DISTRACTED_ATTENTION_FLOOR = 0.7

    /** Scroll-backs above which the attempt reads as hesitant (LOW signal). */
    const val HESITANT_SCROLL_UP_THRESHOLD = 3

    data class Signals(
        /** Student's own four-button rating tier, when the surface collected one. */
        val selfReport: ConfidenceLevel? = null,
        /** Model's semantic understanding tier from the tutoring dialogue. */
        val modelTier: com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier? = null,
        /** AttentionSignal.attentionFactor for this attempt (1.0 = fully attentive). */
        val attentionFactor: Double = 1.0,
        /** Scroll-up (back-to-question) count observed during the attempt. */
        val scrollUpCount: Int = 0,
        /** True when the answer was ultimately WRONG — confidence-at-error. */
        val answerWasWrong: Boolean = false,
    ) {
        init {
            require(attentionFactor in 0.0..1.0) { "Attention factor must be in 0..1" }
            require(scrollUpCount >= 0) { "Scroll count must not be negative" }
        }
    }

    data class Assessment(
        val level: ConfidenceLevel,
        /** Which source decided the level (audit snapshot). */
        val decidedBy: String,
    )

    fun assess(signals: Signals): Assessment {
        // Local UI signals only ever lower the level: distraction and
        // hesitation are objective evidence of a fragile grasp, even when the
        // student (or the model) claims confidence.
        val distracted = signals.attentionFactor < DISTRACTED_ATTENTION_FLOOR
        val hesitant = signals.scrollUpCount >= HESITANT_SCROLL_UP_THRESHOLD
        val objectiveLow = distracted || hesitant

        // A claimed-HIGH confidence under objective distraction is
        // systematically untrustworthy (divided attention impairs encoding —
        // Craik 1996 — and fluency is a misleading metacognitive cue —
        // Benjamin 1998). "Trust the student LESS": downgrade all the way to
        // LOW, not one notch.
        val claim = if (objectiveLow) ConfidenceLevel.LOW else null

        // 1) Self-report is the direct metacognitive measurement.
        signals.selfReport?.let { selfReport ->
            val level = claim ?: selfReport
            return Assessment(level, "self_report")
        }

        // 2) Model semantics from the dialogue.
        signals.modelTier?.let { tier ->
            val base = when (tier) {
                com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier.MASTERED,
                com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier.CONFIDENT,
                -> ConfidenceLevel.HIGH

                com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier.UNCERTAIN ->
                    ConfidenceLevel.MEDIUM

                com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier.STRUGGLING ->
                    ConfidenceLevel.LOW
            }
            val level = claim ?: base
            return Assessment(level, "model_semantics")
        }

        // 3) Objective inference only.
        val level = when {
            objectiveLow -> ConfidenceLevel.LOW
            /**
             * Fluent error proxy (hypercorrection ordering, Butterfield &
             * Metcalfe 2001): an error produced with no significant
             * distraction reads as a confident error — the student saw no
             * problem. Used only for re-teaching PRIORITY ordering, never to
             * scale a weight. The floor allows one brief switch (aligned with
             * AttentionSignal.FREE_SWITCH_ALLOWANCE) — a single notification
             * glance is normal (mind-wandering baseline 30-40%, Szpunar
             * 2013), not hesitation.
             */
            signals.answerWasWrong &&
                signals.attentionFactor >= FLUENT_ATTENTION_FLOOR &&
                signals.scrollUpCount == 0 -> ConfidenceLevel.HIGH

            else -> ConfidenceLevel.MEDIUM
        }
        return Assessment(level, "objective_signals")
    }

    /**
     * Attention factor at/above which the attempt counts as fluent enough for
     * the confident-error proxy. One brief switch is normal (aligned with
     * [AttentionSignal.FREE_SWITCH_ALLOWANCE]): factor for a single switch
     * with no away time is 1 - 0.12 = 0.88.
     */
    const val FLUENT_ATTENTION_FLOOR = 0.88
}
