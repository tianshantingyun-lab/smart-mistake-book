package com.tingyun.smartmistakebook.core.domain

/**
 * Data-driven calibration channel for the mastery-evidence write gate
 * (research tutor-evidence-gate §4): the gate's constants (confidence
 * threshold, cooldown, quotas, attention floor) are engineering priors until
 * real usage data says otherwise. This reads the collected gate outcomes —
 * rejections by reason, accepted-write pressure per hour — and produces
 * *suggestions only*, mirroring [SourceCalibration]: constants change by
 * human decision after reviewing the report, never automatically.
 *
 * Pure function over the raw statistics; no database access, fully testable.
 */
object ChatEvidenceGateCalibration {

    /**
     * Minimum observations before any suggestion is valid — mirrors
     * [SourceCalibration.MIN_PAIRED_OUTCOMES]: below this the statistics are
     * noise, and small-sample tweaks are exactly the overfitting the gate
     * exists to prevent.
     */
    const val MIN_OBSERVATIONS = 30

    /** Window-pressure share above which the learner-window quota is judged too tight. */
    const val WINDOW_PRESSURE_SUGGEST_THRESHOLD = 0.8

    /** Rejection share above which a specific gate's parameters deserve review. */
    const val REJECTION_SHARE_SUGGEST_THRESHOLD = 0.2

    /** One raw statistic bucket handed in by the persistence layer. */
    data class GateObservation(
        /** Accepted MASTERY_UPDATE writes (gate passed). */
        val acceptedCount: Int,
        /** Rejected writes keyed by [MasteryWriteGate.RejectReason] name. */
        val rejectedByReason: Map<String, Int>,
        /** Accepted writes per epoch-hour bucket (hour = millis / 3_600_000). */
        val acceptedPerHour: Map<Long, Int>,
    ) {
        init {
            require(acceptedCount >= 0) { "Accepted count must not be negative" }
            require(rejectedByReason.values.all { it >= 0 }) { "Rejection counts must not be negative" }
            require(acceptedPerHour.values.all { it >= 0 }) { "Hourly counts must not be negative" }
        }

        val totalObservations: Int
            get() = acceptedCount + rejectedByReason.values.sum()
    }

    data class GateCalibrationReport(
        val totalObservations: Int,
        /** Rejection share per gate reason (0 when nothing was rejected). */
        val rejectionShares: Map<String, Double>,
        /**
         * Peak accepted writes in any single hour divided by the
         * learner-window cap — the direct "is the quota too tight" signal.
         */
        val windowPeakPressure: Double,
        val hasSufficientObservations: Boolean,
        /** Human-review suggestions; empty until [MIN_OBSERVATIONS] is met. */
        val suggestions: List<String>,
    )

    fun calibrate(observation: GateObservation): GateCalibrationReport {
        val total = observation.totalObservations
        val rejectionShares = observation.rejectedByReason.mapValues { (_, count) ->
            if (total == 0) 0.0 else count.toDouble() / total
        }
        val peakHour = observation.acceptedPerHour.values.maxOrNull() ?: 0
        val windowPeakPressure = if (MasteryWriteGate.MAX_WRITES_PER_LEARNER_WINDOW == 0) {
            0.0
        } else {
            peakHour.toDouble() / MasteryWriteGate.MAX_WRITES_PER_LEARNER_WINDOW
        }
        val suggestions = buildList {
            if (total < MIN_OBSERVATIONS) return@buildList
            // 1. Window-quota pressure: a legitimate batch burst repeatedly
            // touching the cap means the constant is too tight for the real
            // intake shape.
            if (windowPeakPressure >= WINDOW_PRESSURE_SUGGEST_THRESHOLD) {
                add(
                    "窗口配额压力 ${"%.0f".format(windowPeakPressure * 100)}%（单小时峰值 $peakHour / " +
                        "上限 ${MasteryWriteGate.MAX_WRITES_PER_LEARNER_WINDOW}）：批量录入场景可能被误拦，" +
                        "建议人工评估放宽 MAX_WRITES_PER_LEARNER_WINDOW。",
                )
            }
            // 2. Per-gate rejection composition: any single gate rejecting
            // more than its share of ALL writes deserves a look — either the
            // model is misbehaving (anchoring/semantics gates) or the
            // parameter is mis-tuned (cooldown/quota gates).
            rejectionShares.forEach { (reason, share) ->
                if (share >= REJECTION_SHARE_SUGGEST_THRESHOLD) {
                    add(
                        "拒写「$reason」占比 ${"%.0f".format(share * 100)}%：" +
                            if (reason == "SAME_KC_IN_COOLDOWN" || reason.contains("QUOTA")) {
                                "节流类拒写占比过高，检查冷却/配额是否与真实学习节奏冲突。"
                            } else {
                                "质量类拒写占比过高，检查模型是否在批量臆测知识点或空判方向。"
                            },
                    )
                }
            }
        }
        return GateCalibrationReport(
            totalObservations = total,
            rejectionShares = rejectionShares,
            windowPeakPressure = windowPeakPressure,
            hasSufficientObservations = total >= MIN_OBSERVATIONS,
            suggestions = suggestions,
        )
    }
}
