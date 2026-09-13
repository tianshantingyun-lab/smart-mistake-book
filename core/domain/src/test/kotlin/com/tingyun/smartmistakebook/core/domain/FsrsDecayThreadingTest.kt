package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * F-01：**拟合出来的衰减参数（FSRS-6 的第 21 个参数 `w20`）必须真的改变结果。**
 *
 * 消灭的失败（`P1`，且它一直在**冒充正确**）：参数优化器把下标 20 列进了要拟合的集合
 * （`(0..14).toList() + listOf(20)`，`SchedulingEvaluation.kt:411`），而生产路径上有四处把衰减
 * 冻结在默认值上。梯度因此恒为零——`Adam` 步 `w20 -= lr * 0 / (0 + 1e-8)` 恰好等于不动——
 * 于是参数表里那一位永远不出厂值，**每轮还多算两次损失评估**。
 *
 * 它之所以能活到今天：`w20` 从未离开默认值，所以"哪里都不读它"与"哪里都读它"结果完全一样。
 * 真正危险的是**只修一半**：谁只修了重放那一处，`w20` 立刻开始移动，而排期仍用默认衰减——
 * 拟合用一条曲线、排期用另一条，制造出真正的训练／服务不一致，**且不会有任何测试变红**。
 *
 * 因此这一族断言按**出口**分条，每条钉一处：把 `parameters[20]` 挪开，那个出口必须跟着变。
 * 任何一处重新读默认值，对应的用例立刻变红（逐处归因见 §11 F-01 的变异表）。
 *
 * 一个必须知道的性质，否则用例会写得看起来对但实际恒真：**目标保持率恰好是 0.9 时，
 * `intervalDays` 与衰减无关**（`FACTOR` 就是按 `R(S,S)=0.9` 定义的，两条曲线在 `t=S` 处必重合）。
 * 所以间隔那一条用例把目标保持率设为 0.8——那是产品里真会出现的取值（`0.7..0.97`）。
 */
class FsrsDecayThreadingTest {

    private val defaultParameters = FsrsScheduleMath.DEFAULT_PARAMETERS

    /** 只把 `w20` 挪开，其余 20 个参数逐位相同：断言出来的差异只可能来自衰减。 */
    private val fittedParameters = defaultParameters.copyOf().also { it[20] = FITTED_W20 }

    private val previous = problemMemory(stabilityDays = 30.0, lastReviewedAt = NOW - TWENTY_DAYS)

    // ---------------------------------------------------------------- 记忆更新

    /**
     * 第 ⑤ 处：`ForgettingCurve` 的 FSRS6 分支原先固定用**出厂**衰减。
     *
     * 它比看上去重要：`LearningProjector` 的毕业分支把 `reviewAtTargetRetention` 的结果
     * **写进持久状态**，所以这一处与"算间隔"那几处同级——不是只读估计。
     * 两个断言分别钉这条曲线的两个出口（读 R 与倒推间隔）。
     */
    @Test
    fun theForgettingCurveHonoursTheDecayItIsGiven() {
        val state = problemMemory(stabilityDays = 100.0, lastReviewedAt = NOW - TWENTY_DAYS)
        val atDefault = curve(defaultParameters)
        val atFitted = curve(fittedParameters)

        assertNotEquals(
            "读出的可提取率必须随衰减变化（F-01 的第 ⑥ 处）",
            atDefault.retentionAt(state, NOW, ZoneOffset.UTC),
            atFitted.retentionAt(state, NOW, ZoneOffset.UTC),
            1e-9,
        )
        // 目标保持率取毕业分支真实用的那个（0.8）——取 0.9 会恒等，见类注释。
        assertNotEquals(
            "毕业分支写给下一次复习的那一天必须随衰减变化（F-01 的第 ⑤ 处）",
            atDefault.reviewAtTargetRetention(NOW, GRADUATION_STABILITY_DAYS, GRADUATION_TARGET),
            atFitted.reviewAtTargetRetention(NOW, GRADUATION_STABILITY_DAYS, GRADUATION_TARGET),
        )
    }

    @Test
    fun theMemoryUpdateFollowsTheFittedDecay() {
        val atDefault = model(defaultParameters).updateMemory(
            previous = previous,
            rating = FsrsRating.GOOD,
            outcome = ProblemMemoryOutcome.INDEPENDENT_RECALL,
            weight = 1.0,
            occurredAtEpochMillis = NOW,
            effectiveAttemptAtEpochMillis = NOW,
            elapsedCalendarDays = TWENTY_DAYS.toDouble(),
        )
        val atFitted = model(fittedParameters).updateMemory(
            previous = previous,
            rating = FsrsRating.GOOD,
            outcome = ProblemMemoryOutcome.INDEPENDENT_RECALL,
            weight = 1.0,
            occurredAtEpochMillis = NOW,
            effectiveAttemptAtEpochMillis = NOW,
            elapsedCalendarDays = TWENTY_DAYS.toDouble(),
        )

        assertNotEquals(
            "记忆强度必须随衰减变化：可提取率 R(t,S) 是 nextRecallStability 的输入，" +
                "而 R 的衰减就是 w20（F-01 的第 ③ 处）",
            atDefault.stabilityDays,
            atFitted.stabilityDays,
            STABILITY_TOLERANCE,
        )
    }

    // -------------------------------------------------------------- 间隔与下一次

    /**
     * **直接断言**（不靠"两个模型比一比"）：模型排出的间隔必须等于
     * `intervalDays(它算出的 S, desiredRetention, **它自己那组参数的衰减**)`。
     *
     * 为什么不能写成"拟合参数的模型 vs 默认参数的模型，`nextReviewAt` 不同"——第一版就是这么写的，
     * 而变异测试当场证明它抓不到第 ② 处：把模型里那个 `decay` 参数拿掉之后，两个模型的**记忆强度**
     * 仍然不同（第 ③ 处是好的），强度差漏进间隔里，于是 `nextReviewAt` 照样不同、断言照样绿。
     * 这条断言把"间隔用了哪个衰减"变成**唯一变量**：S 取实际算出来的那一个，两边只差衰减。
     */
    @Test
    fun theScheduledIntervalFollowsTheFittedDecay() {
        val fittedModel = model(fittedParameters)
        val atFitted = fittedModel.updateMemory(
            previous = previous,
            rating = FsrsRating.GOOD,
            outcome = ProblemMemoryOutcome.INDEPENDENT_RECALL,
            weight = 1.0,
            occurredAtEpochMillis = NOW,
            effectiveAttemptAtEpochMillis = NOW,
            elapsedCalendarDays = TWENTY_DAYS.toDouble(),
        )

        val scheduledInterval = (atFitted.nextReviewAtEpochMillis - NOW) / DAY_MILLIS
        val intervalAtFittedDecay = FsrsScheduleMath.intervalDays(
            atFitted.stabilityDays,
            DESIRED_RETENTION,
            -fittedParameters[20],
        )
        val intervalAtFactoryDecay = FsrsScheduleMath.intervalDays(
            atFitted.stabilityDays,
            DESIRED_RETENTION,
        )

        assertNotEquals(
            "夹具必须能分辨两种衰减（目标保持率不是 0.9），否则这条断言证明不了接线：" +
                "S=${atFitted.stabilityDays}，拟合衰减下 $intervalAtFittedDecay 天、" +
                "出厂衰减下 $intervalAtFactoryDecay 天",
            intervalAtFactoryDecay,
            intervalAtFittedDecay,
        )
        assertEquals(
            "排出的间隔必须按**模型自己那组参数**的衰减算（F-01 的第 ② 处）",
            intervalAtFittedDecay.toLong(),
            scheduledInterval,
        )
    }

    // ------------------------------------------------------------------ 重放

    @Test
    fun theReplayPredictionsFollowTheFittedDecay() {
        val history = listOf(
            ReviewSample(
                practiceUnitId = "unit-1",
                reviewedAtEpochMillis = NOW - TWENTY_DAYS,
                rating = FsrsRating.GOOD,
                deltaTDays = null,
            ),
            ReviewSample(
                practiceUnitId = "unit-1",
                reviewedAtEpochMillis = NOW,
                rating = FsrsRating.GOOD,
                deltaTDays = TWENTY_DAYS.toDouble(),
            ),
        )

        val atDefault = SchedulingReplay.predict(history, defaultParameters)
        val atFitted = SchedulingReplay.predict(history, fittedParameters)

        assertTrue(
            "重放出的 (预测可提取率, 实际结果) 必须随衰减变化——它正是优化器的损失输入，" +
                "（F-01 的第 ① 处）。不变化就意味着 ∂loss/∂w20 ≡ 0，拟合永远是空转。",
            atDefault.zip(atFitted).any { (left, right) ->
                abs(left.first - right.first) > PREDICTION_TOLERANCE
            },
        )
        assertNotEquals(
            "损失必须随衰减变化，否则梯度恒为零",
            SchedulingReplay.bceLogLoss(atDefault),
            SchedulingReplay.bceLogLoss(atFitted),
        )
    }

    // ------------------------------------------------------------ 保持率推荐

    @Test
    fun theRetentionRecommendationFollowsTheFittedDecay() {
        val cards = (1..OptimalRetention.MIN_CARDS).map { index ->
            OptimalRetention.Card(stabilityDays = 5.0 * index, difficulty = 5.0)
        }

        val atDefault = requireNotNull(OptimalRetention.recommend(cards, defaultParameters)) {
            "夹具必须能给出推荐：卡片数 ${cards.size} 已达到 MIN_CARDS=${OptimalRetention.MIN_CARDS}"
        }
        val atFitted = requireNotNull(OptimalRetention.recommend(cards, fittedParameters))

        val maxDifference = atDefault.curve.zip(atFitted.curve)
            .maxOf { (left, right) -> abs(left.memorized - right.memorized) }
        assertTrue(
            "CMRR 曲线上的「记住量」必须随衰减变化（F-01 的第 ④ 处：averageRetention 原先" +
                "把 decay 写死，而同一个文件里的 simulate 已经正确转发了 parameters）。" +
                "实测最大差 $maxDifference",
            maxDifference > CMRR_TOLERANCE,
        )

        // **把这一格单独点出来**，否则上面的"整条曲线不同"抓不到第 ④ 处：
        // 目标保持率恰好 0.9 时 `intervalDays` 与 `retention` 都与衰减**无关**（见类注释），
        // 于是两个模型在这一格上的差异**只可能**来自 `averageRetention`——也就是第 ④ 处本身。
        // 少了这条断言，把 averageRetention 的 decay 改回出厂值仍然会让整条曲线不同
        // （间隔与 R 都还在用拟合衰减），断言会照样绿。
        val neutralPoint = { curve: List<OptimalRetention.Point> ->
            curve.minBy { abs(it.desiredRetention - NEUTRAL_RETENTION) }
        }
        assertNotEquals(
            "0.9 那一格的「记住量」必须随衰减变化——这一格里只剩 averageRetention 还含衰减（第 ④ 处）",
            neutralPoint(atDefault.curve).memorized,
            neutralPoint(atFitted.curve).memorized,
            CMRR_TOLERANCE,
        )
    }

    // -------------------------------------------------------------------- 夹具

    private fun model(parameters: DoubleArray) = FsrsMemoryUpdateModel(
        parameters = parameters,
        // 0.8 而不是默认的 0.9：0.9 时间隔与衰减无关（见类注释），那条用例就恒真了。
        desiredRetention = DESIRED_RETENTION,
    )

    private fun problemMemory(stabilityDays: Double, lastReviewedAt: Long) = ProblemMemoryState(
        practiceUnitId = "unit-1",
        stabilityDays = stabilityDays,
        difficulty = 5.5,
        lastReviewedAtEpochMillis = lastReviewedAt,
        nextReviewAtEpochMillis = lastReviewedAt,
        lastAttemptId = "attempt-1",
        projectorVersion = LearningProjector.VERSION,
        checkpointSequence = 1,
    )

    private fun curve(parameters: DoubleArray) = ForgettingCurve(
        algorithm = ForgettingCurveAlgorithm.FSRS6_POWER_LAW,
        decay = -parameters[20],
    )

    private companion object {
        /** 默认是 0.1542；挪到 0.4（研究文档标注的可训练区间 `[0.1, 0.8]` 之内）。 */
        const val FITTED_W20 = 0.4

        /** 夹具用的目标保持率；**不是 0.9**，理由见类注释。 */
        const val DESIRED_RETENTION = 0.8

        /** `intervalDays` 与 `retention` 在这里都与衰减无关，于是这一格专测 ④。 */
        const val NEUTRAL_RETENTION = 0.9
        const val NOW = 1_700_000_000_000L
        const val DAY_MILLIS = 86_400_000L
        const val TWENTY_DAYS = 20L * DAY_MILLIS
        const val GRADUATION_STABILITY_DAYS = 200.0
        const val STABILITY_TOLERANCE = 1e-9
        const val PREDICTION_TOLERANCE = 1e-6
        const val CMRR_TOLERANCE = 1e-6

        /** 毕业分支真实用的目标保持率；用字面量会与产品脱钩。 */
        val GRADUATION_TARGET = LearningProjector.GRADUATION_TARGET_RETENTION
    }
}
