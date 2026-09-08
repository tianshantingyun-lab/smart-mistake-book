package com.tingyun.smartmistakebook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.ExamCalendarEntry
import com.tingyun.smartmistakebook.core.domain.SchedulingOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 审计 2026-09-09：`setOptions` / `declareExam` / `removeExam` 此前全仓无调用方，
 * 排程参数与考试日历在 UI 上完全不可写。本测试锁定这条写入链路。
 */
@RunWith(AndroidJUnit4::class)
class SchedulingSettingsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun schedulingSettingsExposeRetentionAndFsrsControls() {
        var options by mutableStateOf(SchedulingOptions())
        var saved: SchedulingOptions? = null
        composeRule.setContent {
            SchedulingSettingsScreen(
                options = options,
                exams = emptyList(),
                onSetOptions = { updated ->
                    saved = updated
                    options = updated
                },
                onAddExam = {},
                onRemoveExam = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("scheduling_retention_slider").assertIsDisplayed()
        composeRule.onNodeWithTag("scheduling_fsrs_switch").assertIsOn()

        composeRule.onNodeWithTag("scheduling_fsrs_switch").performClick()
        composeRule.runOnIdle {
            assertEquals(false, saved?.useFsrsScheduling)
            assertEquals(false, options.useFsrsScheduling)
        }
        composeRule.onNodeWithTag("scheduling_fsrs_switch").assertIsOff()
    }

    @Test
    fun addingAndRemovingAnExamCallsTheStore() {
        val added = mutableListOf<ExamCalendarEntry>()
        val removed = mutableListOf<String>()
        val existing = ExamCalendarEntry(
            entryId = "exam-existing",
            subject = "数学",
            examEpochDay = 20_000,
            title = "期中考试",
        )
        composeRule.setContent {
            SchedulingSettingsScreen(
                options = SchedulingOptions(),
                exams = listOf(existing),
                onSetOptions = {},
                onAddExam = added::add,
                onRemoveExam = removed::add,
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("exam_subject_field").performTextInput("物理")
        composeRule.onNodeWithTag("exam_title_field").performTextInput("月考")
        composeRule.onNodeWithTag("exam_days_field").performTextInput("14")
        composeRule.onNodeWithTag("exam_add_button").performClick()

        composeRule.runOnIdle {
            assertEquals(1, added.size)
            assertEquals("物理", added.single().subject)
            assertEquals("月考", added.single().title)
            assertTrue(added.single().examEpochDay > 0)
        }

        composeRule.onNodeWithTag("exam_remove_exam-existing").performClick()
        composeRule.runOnIdle { assertEquals(listOf("exam-existing"), removed) }
    }
}
