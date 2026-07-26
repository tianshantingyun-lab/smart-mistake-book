package com.tingyun.smartmistakebook.core.ui

import com.tingyun.smartmistakebook.core.model.MasteryStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MasteryLabelsTest {
    @Test
    fun everyMasteryStatusUsesTheCanonicalStudentLabel() {
        assertEquals("还没学到", MasteryStatus.UNKNOWN.studentLabel())
        assertEquals("正在熟悉", MasteryStatus.LEARNING.studentLabel())
        assertEquals("比较稳", MasteryStatus.MASTERED.studentLabel())
        assertEquals("还不稳定", MasteryStatus.CONFLICTED.studentLabel())
        assertEquals("需要再看看", MasteryStatus.STALE.studentLabel())
    }

    @Test
    fun legacyLabelsRestoreToTheOriginalStableStatus() {
        val aliases = mapOf(
            "暂无学习记录" to MasteryStatus.UNKNOWN,
            "学习中" to MasteryStatus.LEARNING,
            "已掌握" to MasteryStatus.MASTERED,
            "需巩固" to MasteryStatus.CONFLICTED,
            "待复习" to MasteryStatus.STALE,
        )

        aliases.forEach { (storedValue, expected) ->
            assertEquals(expected, masteryStatusFromStoredUiValue(storedValue))
        }
    }

    @Test
    fun stableIdsAndCanonicalLabelsAlsoRestore() {
        MasteryStatus.entries.forEach { status ->
            assertEquals(status, masteryStatusFromStoredUiValue(status.name.lowercase()))
            assertEquals(status, masteryStatusFromStoredUiValue(status.studentLabel()))
        }
    }
}
