package com.tingyun.smartmistakebook.core.ui

import com.tingyun.smartmistakebook.core.model.MasteryStatus
import java.util.Locale

fun MasteryStatus.studentLabel(): String = when (this) {
    MasteryStatus.UNKNOWN -> "还没学到"
    MasteryStatus.LEARNING -> "正在熟悉"
    MasteryStatus.MASTERED -> "比较稳"
    MasteryStatus.CONFLICTED -> "还不稳定"
    MasteryStatus.STALE -> "需要再看看"
}

fun masteryStatusFromStoredUiValue(value: String?): MasteryStatus? {
    val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: return null
    return MasteryStatus.entries.firstOrNull { status ->
        normalized == status.name.lowercase(Locale.ROOT) ||
            normalized == status.studentLabel().lowercase(Locale.ROOT) ||
            normalized in legacyMasteryLabels.getValue(status)
    }
}

private val legacyMasteryLabels = mapOf(
    MasteryStatus.UNKNOWN to setOf("暂无学习记录"),
    MasteryStatus.LEARNING to setOf("学习中"),
    MasteryStatus.MASTERED to setOf("已掌握"),
    MasteryStatus.CONFLICTED to setOf("需巩固"),
    MasteryStatus.STALE to setOf("待复习"),
)
