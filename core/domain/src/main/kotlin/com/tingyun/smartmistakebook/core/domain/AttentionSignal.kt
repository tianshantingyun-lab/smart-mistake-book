package com.tingyun.smartmistakebook.core.domain

/**
 * Attention-diversion discount (spec mastery-scheduling §2.14): switching
 * away from the app during a question both fragments encoding (Craik et al.
 * 1996: divided attention at encoding severely impairs later memory; Sana
 * et al. 2013: multitasking hinders learning) and signals difficulty or
 * aversion (D'Mello et al. 2013: mind-wandering rises with text difficulty
 * and its rate predicts comprehension).
 *
 * The factor only ever discounts the evidence weight of the current attempt
 * — it never enters the forgetting curve itself. The coefficients are
 * engineering priors pending calibration from review_log data; the shape
 * (per-extra-switch penalty plus a milder cumulative away-time penalty, both
 * floored) follows the resumption-cost literature (Trafton et al. 2005).
 */
object AttentionSignal {

    /** The first switch is absorbed: single brief context flips are normal. */
    const val FREE_SWITCH_ALLOWANCE = 1

    /** Stability penalty applied per additional switch. */
    const val PER_EXTRA_SWITCH_PENALTY = 0.12

    /** Away-time penalty is graded per this many accumulated milliseconds. */
    const val AWAY_GRADE_MILLIS = 30_000L

    /** Penalty applied per full away-time grade. */
    const val PER_AWAY_GRADE_PENALTY = 0.05

    const val FLOOR = 0.6

    /**
     * @param switchCount interruptions (app switches / screen-away) recorded
     *   during the attempt; zero or one switches keep the weight untouched.
     * @param awayMillis total accumulated time away from the app.
     */
    fun attentionFactor(switchCount: Int, awayMillis: Long): Double {
        require(switchCount >= 0) { "Switch count must not be negative" }
        require(awayMillis >= 0) { "Away time must not be negative" }
        val extraSwitches = (switchCount - FREE_SWITCH_ALLOWANCE).coerceAtLeast(0)
        val awayGrades = (awayMillis / AWAY_GRADE_MILLIS).coerceAtMost(8)
        val factor = 1.0 -
            extraSwitches * PER_EXTRA_SWITCH_PENALTY -
            awayGrades * PER_AWAY_GRADE_PENALTY
        return factor.coerceIn(FLOOR, 1.0)
    }

    /**
     * Avoidance signal (spec §6): the card was repeatedly switched away from
     * AND graded poorly — an aversion/difficulty marker that routes the item
     * toward re-teaching rather than plain rescheduling.
     */
    fun isAvoidanceSignal(switchCount: Int, schedulingRating: Int): Boolean =
        switchCount >= AVOIDANCE_SWITCH_THRESHOLD && schedulingRating <= AVOIDANCE_MAX_RATING

    const val AVOIDANCE_SWITCH_THRESHOLD = 2
    const val AVOIDANCE_MAX_RATING = 2
}
