package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorDifficultyTier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 新题冷启动估时的基线选择（spec `batch-intake-spec.md` §2 L2/L3）。
 *
 * 消灭的失败：模型判过的难度档没有消费方，新题一律按占位数值难度估成中档
 * 180s——当日引入配额按错误时长计算。
 */
class IntakeDurationBaselineTest {

    @Test
    fun `model tier wins over the numeric difficulty proxy`() {
        // 模型判 HARD 时，即便数值难度落在"简单档"区间也必须用困难档基线：
        // 模型判过就听模型的，数值代理只是"模型没判过"时的兜底。
        assertEquals(
            LogDurationModel.TIER_BASELINE_HARD_SECONDS,
            IntakeDurationBaseline.secondsFor(
                modelTier = TutorDifficultyTier.HARD,
                fsrsDifficulty = 2.0,
            ),
        )
        assertEquals(
            LogDurationModel.TIER_BASELINE_EASY_SECONDS,
            IntakeDurationBaseline.secondsFor(
                modelTier = TutorDifficultyTier.EASY,
                fsrsDifficulty = 9.0,
            ),
        )
    }

    @Test
    fun `without a model tier the numeric difficulty bands still apply`() {
        assertEquals(
            LogDurationModel.TIER_BASELINE_EASY_SECONDS,
            IntakeDurationBaseline.secondsFor(null, fsrsDifficulty = 3.9),
        )
        assertEquals(
            LogDurationModel.TIER_BASELINE_MEDIUM_SECONDS,
            IntakeDurationBaseline.secondsFor(null, fsrsDifficulty = 4.0),
        )
        assertEquals(
            LogDurationModel.TIER_BASELINE_MEDIUM_SECONDS,
            IntakeDurationBaseline.secondsFor(null, fsrsDifficulty = 6.9),
        )
        assertEquals(
            LogDurationModel.TIER_BASELINE_HARD_SECONDS,
            IntakeDurationBaseline.secondsFor(null, fsrsDifficulty = 7.0),
        )
    }

    @Test
    fun `every model tier maps onto the local constant table`() {
        // 秒数一律由本地常数出——模型只给语义档，不估秒数。
        assertEquals(
            LogDurationModel.TIER_BASELINE_EASY_SECONDS,
            IntakeDurationBaseline.secondsForTier(TutorDifficultyTier.EASY),
        )
        assertEquals(
            LogDurationModel.TIER_BASELINE_MEDIUM_SECONDS,
            IntakeDurationBaseline.secondsForTier(TutorDifficultyTier.MEDIUM),
        )
        assertEquals(
            LogDurationModel.TIER_BASELINE_HARD_SECONDS,
            IntakeDurationBaseline.secondsForTier(TutorDifficultyTier.HARD),
        )
        // 三档互不相同：档位若塌成同一个秒数，模型难度判断就失去了意义。
        val distinct = TutorDifficultyTier.entries
            .map(IntakeDurationBaseline::secondsForTier)
            .distinct()
        assertEquals(TutorDifficultyTier.entries.size, distinct.size)
    }
}
