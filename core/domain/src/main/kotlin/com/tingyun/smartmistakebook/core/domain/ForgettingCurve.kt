package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

fun interface EpochMillisClock {
    fun nowEpochMillis(): Long
}

object SystemEpochMillisClock : EpochMillisClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

enum class ClockAnomaly {
    NONE,
    TIME_ROLLBACK,
}

data class RetentionEstimate(
    val probability: Double,
    val clockAnomaly: ClockAnomaly,
)

/**
 * Forgetting curve with two coexisting algorithms (spec mastery-scheduling
 * §2.20 kill switch): the audited exponential baseline and the FSRS-6 power
 * law. Method signatures are unchanged; callers choose the algorithm once.
 */
class ForgettingCurve(
    private val clock: EpochMillisClock = SystemEpochMillisClock,
    private val stabilityRetention: Double = DEFAULT_STABILITY_RETENTION,
    private val algorithm: ForgettingCurveAlgorithm = ForgettingCurveAlgorithm.LEGACY_EXPONENTIAL,
    /**
     * FSRS-6 的衰减（`-w20`）。只在 [ForgettingCurveAlgorithm.FSRS6_POWER_LAW] 下有含义；
     * 默认值是出厂参数里的那一位，**装配点必须传拟合出来的值**，否则这个模型的保持率
     * 与排期用的是两条曲线（F-01 的第 ⑤ 处：`LearningProjector` 的毕业分支把
     * `reviewAtTargetRetention` 的结果**写进持久状态**，所以这一处与计算间隔那几处同级）。
     */
    private val decay: Double = -FsrsScheduleMath.DEFAULT_PARAMETERS[20],
) {
    init {
        require(stabilityRetention in 0.0..1.0 && stabilityRetention != 0.0 && stabilityRetention != 1.0) {
            "Stability retention must be strictly between zero and one"
        }
    }

    fun retentionNow(state: ProblemMemoryState, zoneId: ZoneId): Double = retentionAt(
        state = state,
        atEpochMillis = clock.nowEpochMillis(),
        zoneId = zoneId,
    )

    fun retentionAt(state: ProblemMemoryState, atEpochMillis: Long, zoneId: ZoneId): Double {
        return estimateAt(state, atEpochMillis, zoneId).probability
    }

    /**
     * 读侧的可提取率估计。
     *
     * [zoneId] 是学习者时区：FSRS-6 的 `t` 是**本地日历日**差（见 [ReviewCalendar]），
     * 而读侧手上只有时刻，没有"当时那个事件自带的偏移"，所以必须由调用方给出学习者的时区。
     * 这个参数**没有默认值**——给一个"默认 UTC"就是让下一个调用点静默算错一整天的边界。
     */
    fun estimateAt(
        state: ProblemMemoryState,
        atEpochMillis: Long,
        zoneId: ZoneId,
    ): RetentionEstimate {
        require(atEpochMillis >= 0) { "Evaluation time must not be negative" }
        val rollback = atEpochMillis < state.lastReviewedAtEpochMillis
        val elapsedMillis = if (rollback) 0 else atEpochMillis - state.lastReviewedAtEpochMillis
        val probability = when (algorithm) {
            // LEGACY 分支**故意不动**：它是 spec §2.20 的 kill switch，语义由用例逐位冻结，
            // 用的是精确毫秒（不是整天）。F-02 统一的是读侧的 FSRS-6 估计。
            ForgettingCurveAlgorithm.LEGACY_EXPONENTIAL ->
                exp(ln(stabilityRetention) * elapsedMillis / DAY_MILLIS / state.stabilityDays)
            ForgettingCurveAlgorithm.FSRS6_POWER_LAW -> {
                // 与写入侧同一个口径（审计 F-02）：本地日历日差，整日。
                // 不改这一处的话，同一张卡在知识点队列与错题详情上会显示两个不同的 R
                // ——一张 23:00 复习过的卡，次日 01:00 的"墙钟整日地板"说是第 0 天（R = 1）。
                val elapsedDays = ReviewCalendar.elapsedCalendarDays(
                    previousReviewedAtEpochMillis = state.lastReviewedAtEpochMillis,
                    atEpochMillis = atEpochMillis,
                    zoneId = zoneId,
                )
                FsrsScheduleMath.retention(elapsedDays, state.stabilityDays, decay)
            }
        }.coerceIn(0.0, 1.0)
        return RetentionEstimate(
            probability = probability,
            clockAnomaly = if (rollback) ClockAnomaly.TIME_ROLLBACK else ClockAnomaly.NONE,
        )
    }

    fun reviewAtTargetRetention(
        reviewedAtEpochMillis: Long,
        stabilityDays: Double,
        targetRetention: Double = stabilityRetention,
    ): Long {
        require(reviewedAtEpochMillis >= 0) { "Review time must not be negative" }
        require(stabilityDays.isFinite() && stabilityDays > 0.0) {
            "Stability must be positive"
        }
        require(targetRetention in 0.0..1.0 && targetRetention != 0.0 && targetRetention != 1.0) {
            "Target retention must be strictly between zero and one"
        }
        val intervalMillis = when (algorithm) {
            ForgettingCurveAlgorithm.LEGACY_EXPONENTIAL -> {
                val intervalDays = stabilityDays * ln(targetRetention) / ln(stabilityRetention)
                (intervalDays * DAY_MILLIS).toLong().coerceAtLeast(0)
            }
            ForgettingCurveAlgorithm.FSRS6_POWER_LAW -> {
                val intervalDays = FsrsScheduleMath.intervalDays(stabilityDays, targetRetention, decay)
                intervalDays * DAY_MILLIS.toLong()
            }
        }
        return if (Long.MAX_VALUE - reviewedAtEpochMillis < intervalMillis) {
            Long.MAX_VALUE
        } else {
            reviewedAtEpochMillis + intervalMillis
        }
    }

    companion object {
        const val VERSION = "forgetting-curve-v3"
        const val DEFAULT_STABILITY_RETENTION = 0.9
        private const val DAY_MILLIS = 86_400_000.0
    }
}

enum class ForgettingCurveAlgorithm {
    LEGACY_EXPONENTIAL,
    FSRS6_POWER_LAW,
}
