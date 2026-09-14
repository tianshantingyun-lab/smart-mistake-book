package com.tingyun.smartmistakebook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.tingyun.smartmistakebook.core.domain.PrerequisiteRemediation
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import com.tingyun.smartmistakebook.feature.review.CapturedReviewSessionScreen
import org.junit.Rule
import org.junit.Test

/**
 * 前置补救在**实拍题**这一屏上也要出现（审计批 2 第 3 项／§2.9）。
 *
 * 消灭的失败：补救卡原先只在策展屏 `ReviewSessionScreen` 上渲染，而真正被学生用到的
 * 复习屏是 `CapturedReviewSessionScreen`——于是即使数据层把材料查了出来，生产里也**从来没
 * 有出现过**这张卡。策展屏那条用例（`ReviewPrerequisiteRemediationInstrumentedTest`）
 * 一直是绿的，正是因为它测的是另一屏。
 *
 * 与那条一样，这里钉的性质是「**不拦作答**」：§2.16 的开场重教是必经步骤（须先确认），
 * 而前置补救只是题干旁的上下文。只断言"卡出现了"，等于把这两条行为相反的通道混为一谈。
 */
class CapturedReviewPrerequisiteRemediationInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val stem = "判断并证明该函数在闭区间上的单调性。"

    private val entry = StudyCatalogEntry(
        entryId = "entry:captured:remediation",
        problemId = "problem:captured:remediation",
        problemRevisionId = "revision:captured:remediation:r1",
        practiceUnitId = "unit:captured:remediation",
        subject = "MATH",
        title = "闭区间上的单调性",
        problemMarkdown = stem,
        sourceKey = "capture:remediation",
        isCuratedExample = false,
        nextReviewAtEpochMillis = null,
        retrievability = null,
    )

    private val remediation = PrerequisiteRemediation(
        prerequisiteName = "从图像读取单调性",
        title = "读图判断单调性常见错误",
        markdown = "单调区间要按定义域分段读。",
    )

    @Test
    fun theCapturedScreenShowsTheRemediationBesideTheQuestionAndStillLetsTheStudentAnswer() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                CapturedReviewSessionScreen(
                    onBack = {},
                    entry = entry,
                    queuePosition = 1,
                    queueSize = 3,
                    tutorJudgedAvailable = true,
                    onOpenTutorJudge = {},
                    prerequisiteRemediation = remediation,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("review_prereq_remediation").assertIsDisplayed()
        composeRule.onNodeWithTag("review_prereq_name").assertIsDisplayed()
        composeRule.onNodeWithText("先补前置：从图像读取单调性").assertIsDisplayed()
        // 题干与**作答入口**同时可用——补救不是关卡（第 2 条之后作答入口是讲题判定，
        // 自评那一套已按产品裁定拆除；断言的性质没变，只是换了载体）。
        composeRule.onNodeWithText(stem).assertIsDisplayed()
        composeRule.onNodeWithTag("captured_review_tutor_judged_button").assertIsDisplayed()
    }

    @Test
    fun anOrdinaryCapturedCardShowsNoRemediation() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                CapturedReviewSessionScreen(
                    onBack = {},
                    entry = entry,
                    queuePosition = 1,
                    queueSize = 3,
                    tutorJudgedAvailable = true,
                    onOpenTutorJudge = {},
                    prerequisiteRemediation = null,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("review_prereq_remediation").assertDoesNotExist()
        composeRule.onNodeWithText(stem).assertIsDisplayed()
    }
}
