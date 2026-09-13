package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计 F-02：读侧的 `t` 是**本地日历日**，而且**每一个读侧出口都必须用同一个口径**。
 *
 * 判别性的那一格是「**跨午夜、但不足 24 小时**」：本地 23:00 复习过，次日 01:00 来看。
 * 三种口径在这一格上给出三个**不同**的数，所以只有这一格能把它们分开：
 *
 * | 口径 | 得出的 `t` | 出现在 |
 * |---|---|---|
 * | 墙钟整日地板（`floor(elapsed/24h)`） | 0 天 ⇒ R = 1 | 旧 `ForgettingCurve.estimateAt` 的 FSRS-6 分支 |
 * | 墙钟分数天（`elapsed/24h`） | ≈ 0.083 天 ⇒ R 略低于 1 | 旧 `knowledgeRecallRiskByNode` |
 * | **本地日历日差（正确）** | **1 天** ⇒ R 按一天衰减 | 写入侧（`LearningProjector`）、Anki 的 rollover 口径、spec §2.1/§2.2 |
 *
 * 为什么"正确"是整天而不是分数天：本项目实现的是 FSRS-6，而 FSRS-6 是**按天**的模型
 * ——分数天是 FSRS-7 的能力。逐条引文见 [ReviewCalendar] 的类注释与
 * `docs/research/deltat-convention-research.md`。
 */
class ReviewCalendarTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val curve = ForgettingCurve(algorithm = ForgettingCurveAlgorithm.FSRS6_POWER_LAW)

    @Test
    fun `two hours across local midnight count as one local day`() {
        val lastReviewedAt = localInstant(day = 10, hour = 23)
        val now = localInstant(day = 11, hour = 1)
        val wallClockMillis = now - lastReviewedAt

        assertTrue(
            "夹具本身要成立：确实不足 24 小时",
            wallClockMillis < ReviewCalendar.DAY_MILLIS,
        )
        assertEquals(
            "跨过本地午夜就是过了 1 个日历日",
            1.0,
            ReviewCalendar.elapsedCalendarDays(lastReviewedAt, now, shanghai),
            0.0,
        )
    }

    @Test
    fun `the fsrs read path feeds the curve the local calendar day count`() {
        val lastReviewedAt = localInstant(day = 10, hour = 23)
        val now = localInstant(day = 11, hour = 1)
        val state = memory(stabilityDays = 3.0, lastReviewedAt = lastReviewedAt)

        val probability = curve.estimateAt(state, now, shanghai).probability

        assertEquals(
            "读侧必须按**恰好一天**喂进曲线：整日地板的 0 天与分数天的 0.083 天都会给出别的数",
            FsrsScheduleMath.retention(
                elapsedDays = 1.0,
                stabilityDays = 3.0,
                // 这条曲线是默认构造，衰减就是出厂那一位。
                decay = -FsrsScheduleMath.DEFAULT_PARAMETERS[20],
            ),
            probability,
            1e-12,
        )
        assertTrue(
            "按 0 天算会得到 1.0；按 0.083 天算会得到另一个数——这条断言把三者分开",
            probability < 1.0,
        )
    }

    @Test
    fun `the knowledge risk read path agrees with the retention read path`() {
        val lastReviewedAt = localInstant(day = 10, hour = 23)
        val now = localInstant(day = 11, hour = 1)
        val state = memory(stabilityDays = 3.0, lastReviewedAt = lastReviewedAt)

        val fromCurve = curve.estimateAt(state, now, shanghai).probability
        val fromQueue = knowledgeRecallRiskByNode(
            boundPracticeUnitIdsByNode = mapOf("kc-1" to listOf(UNIT_ID)),
            memoryStates = mapOf(UNIT_ID to state),
            nowEpochMillis = now,
            forgettingCurve = curve,
            zoneId = shanghai,
        ).getValue("kc-1")

        assertEquals(
            "同一张卡、同一时刻，两个读侧出口必须给出同一个 R（审计 F-02 的原始症状就是它们不同）",
            fromCurve,
            fromQueue,
            1e-12,
        )
    }

    @Test
    fun `the event-carried offset and the learner zone agree for a fixed-offset zone`() {
        val instant = localInstant(day = 10, hour = 23)

        assertEquals(
            "写入侧按事件自带的偏移换算、读侧按时区换算，对固定偏移时区必须逐位相同",
            ReviewCalendar.localEpochDayOf(instant, utcOffsetMinutes = 480),
            ReviewCalendar.localEpochDayOf(instant, zoneId = shanghai),
        )
    }

    private fun localInstant(day: Int, hour: Int): Long =
        ZonedDateTime.of(2026, 1, day, hour, 0, 0, 0, shanghai).toInstant().toEpochMilli()

    private fun memory(stabilityDays: Double, lastReviewedAt: Long) = ProblemMemoryState(
        practiceUnitId = UNIT_ID,
        stabilityDays = stabilityDays,
        difficulty = 5.0,
        lastReviewedAtEpochMillis = lastReviewedAt,
        nextReviewAtEpochMillis = lastReviewedAt,
        lastAttemptId = "attempt-1",
        projectorVersion = LearningProjector.VERSION,
        checkpointSequence = 1,
    )

    private companion object {
        const val UNIT_ID = "unit-1"
    }
}
