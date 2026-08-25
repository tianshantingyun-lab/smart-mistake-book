package com.tingyun.smartmistakebook.core.ui

/**
 * Qualitative mastery/retention bands shared by every screen that presents
 * learning state (audit section 6.4): precise probabilities require a
 * calibrated model, so student-facing copy uses bands only.
 */
fun masteryBandLabel(conservativeMasteryScore: Double): String = when {
    conservativeMasteryScore >= 0.7 -> "较稳"
    conservativeMasteryScore >= 0.4 -> "一般"
    else -> "薄弱"
}

fun retentionBandLabel(retrievabilityAtSnapshot: Double): String = when {
    retrievabilityAtSnapshot >= 0.7 -> "记忆较稳"
    retrievabilityAtSnapshot >= 0.4 -> "记忆减弱"
    else -> "记忆模糊"
}
