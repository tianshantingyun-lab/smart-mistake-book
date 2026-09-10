package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorDifficultyTier

/**
 * 新题冷启动估时的基线选择（spec `batch-intake-spec.md` §2 L2/L3）。
 *
 * 消灭的具体失败：新题没有记忆状态，`problemMemoryStates[...]` 为 null，排程
 * 一律取占位难度 [DEFAULT] → 每道新题都被估成中档 180s。模型其实早就给出了
 * 难度判断（整理/评估任务里的 difficultyTier），但它没有任何消费方——契约
 * 承诺了模型能表达难度，实际被静默丢弃，当日引入配额因此按错误的时长计算。
 *
 * 契约：模型只给语义档（EASY/MEDIUM/HARD），秒数一律由本地常数表出。
 */
object IntakeDurationBaseline {

    /** 数值难度（FSRS 1..10）落到"简单档"的上界——仅在模型没判过难度时兜底。 */
    const val FSRS_EASY_CEILING = 4.0

    /** 数值难度落到"困难档"的下界（同上，兜底口径）。 */
    const val FSRS_HARD_FLOOR = 7.0

    /**
     * 这道新题的冷启动基线秒数。
     *
     * @param modelTier 模型判过的难度档；null = 模型没判过（旧数据/本次未输出），
     *   此时才退回数值难度代理——不得默认成中档。
     * @param fsrsDifficulty 投影器算出的 FSRS 难度（1..10）；新题恒为占位值。
     */
    fun secondsFor(modelTier: TutorDifficultyTier?, fsrsDifficulty: Double): Int = when {
        modelTier != null -> secondsForTier(modelTier)
        fsrsDifficulty < FSRS_EASY_CEILING -> LogDurationModel.TIER_BASELINE_EASY_SECONDS
        fsrsDifficulty < FSRS_HARD_FLOOR -> LogDurationModel.TIER_BASELINE_MEDIUM_SECONDS
        else -> LogDurationModel.TIER_BASELINE_HARD_SECONDS
    }

    /** 模型难度档 → 本地基线秒数（档位与秒数的对应是本地常数，模型不估秒数）。 */
    fun secondsForTier(tier: TutorDifficultyTier): Int = when (tier) {
        TutorDifficultyTier.EASY -> LogDurationModel.TIER_BASELINE_EASY_SECONDS
        TutorDifficultyTier.MEDIUM -> LogDurationModel.TIER_BASELINE_MEDIUM_SECONDS
        TutorDifficultyTier.HARD -> LogDurationModel.TIER_BASELINE_HARD_SECONDS
    }
}
