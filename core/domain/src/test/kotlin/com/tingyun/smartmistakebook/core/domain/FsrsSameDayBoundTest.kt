package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import kotlin.math.exp
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同一天里反复复习，会把稳定度 `S` 推到哪——**这是有上界的，而且上界与证据条数无关**。
 *
 * 审计批 3 第 1 项（发现 `sameday-aggregation-missing`）与它对 spec §10「同日语义」实现注记的订正
 * 就建立在这几条不变量上。原文写"聚合无额外自由度"，那半句只对**同日重复失败**成立；
 * 对**同日重复成功**，实现比"聚合成一次"更高——但高多少是可以算死的：
 *
 * 短程乘子（`FsrsScheduleMath.shortTermStability`，`G != AGAIN` 时钳制 `≥1`）
 *
 *     m(S) = e^{w17·(G−3+w18)} · S^(−w19)
 *     S'   = S · max(m(S), 1)
 *
 * 的不动点 `S* = (e^{w17·w18})^{1/w19} ≈ 2.1211` 天（默认参数）。`S < S*` 时被推高，
 * `S > S*` 时乘子 < 1 又被钳回 1.0，所以**任意多次同日成功最多把 S 推到 `max(S₀, S*)`**。
 *
 * 换成调度上看得见的东西：保持率目标 0.9 时 `I(r*,S) = S`（spec §2.3 的已证性质），
 * 间隔四舍五入取整、下限 1 天。于是 `S₀ = 0.212`（首见 Again 的初始稳定度）时——
 * 聚合成一次给 1 天，反复成功最多给 2 天。**这条偏差在调度上只值一天。**
 *
 * 消灭的失败：有人改短程公式（例如去掉 `≥1` 的钳制、或改 `w17/w18/w19`）时，
 * 没有别的东西会发现"同一天多答对几次就能把卡推到很久以后"这件事已经失去上界——
 * 这条缺陷的症状在界面上是"这题明明答对了却要我很久以后再复习"，很难归因。
 */
class FsrsSameDayBoundTest {

    private val model = FsrsMemoryUpdateModel()

    @Test
    fun `the same day success multiplier is exactly one at its fixed point`() {
        val fixed = sameDayFixedPoint()

        assertEquals(
            "不动点就是乘子等于 1 的那一点；不等于 1 说明公式或参数已经变了，" +
                "本文件其余断言的上界也就不再成立",
            1.0,
            sameDayMultiplier(stability = fixed, rating = 3),
            1e-12,
        )
    }

    @Test
    fun `same day successes cannot push stability past the fixed point`() {
        val fixed = sameDayFixedPoint()
        var state = memory(stabilityDays = 0.212)
        var previous = state.stabilityDays

        repeat(200) { index ->
            state = applySameDay(state, rating = 3)
            assertTrue(
                "第 ${index + 1} 次同日成功后 S=$state.stabilityDays 越过了上界 $fixed",
                state.stabilityDays <= fixed + 1e-9,
            )
            assertTrue(
                "同日成功不得降低 S（G≥2 的钳制），第 ${index + 1} 次之后却降了",
                state.stabilityDays >= previous,
            )
            previous = state.stabilityDays
        }

        assertEquals(
            "200 次同日成功之后应收敛到不动点，而不是继续往上跑",
            fixed,
            state.stabilityDays,
            1e-4,
        )
    }

    @Test
    fun `a card already above the fixed point gains nothing from another same day success`() {
        val before = memory(stabilityDays = 20.0)

        val after = applySameDay(before, rating = 3)

        assertEquals(
            "成熟卡同日再答对，乘子 < 1 但被钳到 1.0：S 一点都不动，间隔也不该变",
            before.stabilityDays,
            after.stabilityDays,
            0.0,
        )
        assertEquals(
            "间隔同理不动",
            FsrsScheduleMath.intervalDays(before.stabilityDays, DESIRED_RETENTION),
            FsrsScheduleMath.intervalDays(after.stabilityDays, DESIRED_RETENTION),
        )
    }

    @Test
    fun `three same day successes overshoot the single aggregated update the spec asks for`() {
        val start = memory(stabilityDays = 0.212)
        val aggregated = applySameDay(start, rating = 3).stabilityDays

        var compounded = start
        repeat(3) { compounded = applySameDay(compounded, rating = 3) }

        assertEquals(
            "聚合成一次（spec §2.15 的字面要求）",
            0.24668918777567272,
            aggregated,
            1e-12,
        )
        assertEquals(
            "三次逐条更新则给到这个数",
            0.32439327450133126,
            compounded.stabilityDays,
            1e-12,
        )
        assertEquals(
            "高出 31.5%——这就是 spec 原注记说“聚合无额外自由度”所漏掉的那一半",
            1.3149878088549178,
            compounded.stabilityDays / aggregated,
            1e-9,
        )
    }

    @Test
    fun `the scheduling consequence of that overshoot is one day`() {
        val start = memory(stabilityDays = 0.212)
        val aggregated = applySameDay(start, rating = 3)

        var compounded = start
        repeat(200) { compounded = applySameDay(compounded, rating = 3) }

        assertEquals(
            "聚合成一次的间隔是下限 1 天",
            1,
            intervalDaysOf(aggregated),
        )
        assertEquals(
            "反复同日成功的间隔是 2 天",
            2,
            intervalDaysOf(compounded),
        )
        assertEquals(
            "所以这条偏差在调度上正好值一天——稳定度倍率看着大，用户看到的只是这个",
            1,
            intervalDaysOf(compounded) - intervalDaysOf(aggregated),
        )
    }

    @Test
    fun `same day failures are handled more conservatively than one aggregated update`() {
        val start = memory(stabilityDays = 0.212)
        val aggregated = applySameDay(start, rating = 1).stabilityDays

        var compounded = start
        repeat(3) { compounded = applySameDay(compounded, rating = 1) }

        assertEquals(
            "聚合成一次 Again",
            0.08335671711031603,
            aggregated,
            1e-12,
        )
        assertEquals(
            "三次 Again 还要低得多",
            0.015431917514732645,
            compounded.stabilityDays,
            1e-12,
        )
        assertTrue(
            "同日重复失败方向上，实现比聚合**更保守**——spec 原注记的这半句是成立的，" +
                "而上面几条说明它只覆盖了这一半",
            compounded.stabilityDays < aggregated,
        )
    }

    /** 短程乘子；`rating` 是 1-based（1=Again…4=Easy），与 `FsrsRating.ordinal + 1` 一致。 */
    private fun sameDayMultiplier(stability: Double, rating: Int): Double =
        exp(W17 * (rating - 3 + W18)) * stability.pow(-W19)

    private fun sameDayFixedPoint(): Double = exp(W17 * W18).pow(1.0 / W19)

    private fun applySameDay(state: ProblemMemoryState, rating: Int): ProblemMemoryState {
        val update = model.updateMemory(
            previous = state,
            rating = FsrsRating.values()[rating - 1],
            outcome = ProblemMemoryOutcome.INDEPENDENT_RECALL,
            weight = 1.0,
            occurredAtEpochMillis = EPOCH_MILLIS,
            effectiveAttemptAtEpochMillis = EPOCH_MILLIS,
            // 同一天：本地日历日差为 0（审计 F-02 统一后的口径，t 是整日）。
            elapsedCalendarDays = 0.0,
        )
        return memory(stabilityDays = update.stabilityDays, nextReviewAt = update.nextReviewAtEpochMillis)
    }

    private fun intervalDaysOf(state: ProblemMemoryState): Int =
        FsrsScheduleMath.intervalDays(state.stabilityDays, DESIRED_RETENTION)

    private fun memory(
        stabilityDays: Double,
        nextReviewAt: Long = EPOCH_MILLIS,
    ) = ProblemMemoryState(
        practiceUnitId = UNIT_ID,
        stabilityDays = stabilityDays,
        difficulty = 5.0,
        lastReviewedAtEpochMillis = EPOCH_MILLIS,
        nextReviewAtEpochMillis = maxOf(nextReviewAt, EPOCH_MILLIS),
        lastAttemptId = "attempt-1",
        projectorVersion = LearningProjector.VERSION,
        checkpointSequence = 1,
    )

    private companion object {
        const val UNIT_ID = "unit-1"
        const val EPOCH_MILLIS = 1_800_000_000_000L
        const val DESIRED_RETENTION = FsrsMemoryUpdateModel.DEFAULT_DESIRED_RETENTION

        val W17 = FsrsScheduleMath.DEFAULT_PARAMETERS[17]
        val W18 = FsrsScheduleMath.DEFAULT_PARAMETERS[18]
        val W19 = FsrsScheduleMath.DEFAULT_PARAMETERS[19]
    }
}
