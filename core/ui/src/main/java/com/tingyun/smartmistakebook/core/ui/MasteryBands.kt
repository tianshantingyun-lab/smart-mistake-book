package com.tingyun.smartmistakebook.core.ui

/**
 * 掌握度／保持率分档的**两个切点**。三处标签措辞各不相同，切点必须相同。
 *
 * 为什么提成常量（审计 R-03）：同一张卡在「掌握程度」页显示 `masteryBandLabel`
 * （较稳／一般／薄弱），在「遗忘风险」一列显示 `LearningMasteryScreen.forgettingRiskLabel`
 * （低／中／高）——两套措辞、两条 `when`，各自写死同样的 `0.7` 与 `0.4`。
 * 把其中一处调成 0.75，同一张卡就会**同时**显示「较稳」与「遗忘风险：高」，
 * 而两处各自的用例都只断言自己那一条，所以不会有任何测试变红。
 *
 * 保持率那一组（[retentionBandLabel]）读的是另一个量（快照时的可提取率），但用同一对切点
 * ——这是有意的：两者都在 `[0,1]` 上，语义都是"学生对它有多牢"。
 */
const val MASTERY_STRONG_THRESHOLD = 0.7
const val MASTERY_FAIR_THRESHOLD = 0.4

/**
 * Qualitative mastery/retention bands shared by every screen that presents
 * learning state (audit section 6.4): precise probabilities require a
 * calibrated model, so student-facing copy uses bands only.
 */
fun masteryBandLabel(conservativeMasteryScore: Double): String = when {
    conservativeMasteryScore >= MASTERY_STRONG_THRESHOLD -> "较稳"
    conservativeMasteryScore >= MASTERY_FAIR_THRESHOLD -> "一般"
    else -> "薄弱"
}

fun retentionBandLabel(retrievabilityAtSnapshot: Double): String = when {
    retrievabilityAtSnapshot >= MASTERY_STRONG_THRESHOLD -> "记忆较稳"
    retrievabilityAtSnapshot >= MASTERY_FAIR_THRESHOLD -> "记忆减弱"
    else -> "记忆模糊"
}
