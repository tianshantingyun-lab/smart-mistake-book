package com.tingyun.smartmistakebook

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.SourceCalibration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 来源校准的可见面：人必须能看到"讲题判定"这个来源攒了多少对样本、之后回忆率与
 * 真实作答基线差多少——否则"校准达标后再放开进 FSRS 拟合"这条裁决永远不会发生。
 */
@RunWith(AndroidJUnit4::class)
class SourceCalibrationSectionInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listsEachSourceWithPairsAndTheModelJudgedFittingExclusion() {
        composeRule.setContent {
            MaterialTheme {
                SourceCalibrationSection(
                    provider = {
                        listOf(
                            SourceCalibration(
                                sourceKind = "MODEL_JUDGED",
                                positiveReportCount = 5,
                                nextAttemptCount = 2,
                                realizedRecallRate = 0.5,
                                attemptBaselineRecallRate = 0.8,
                            ),
                            SourceCalibration(
                                sourceKind = "VISUAL",
                                positiveReportCount = 40,
                                nextAttemptCount = 32,
                                realizedRecallRate = 0.6,
                                attemptBaselineRecallRate = 0.82,
                            ),
                        )
                    },
                )
            }
        }

        // 每个来源一行标签 + 一对统计。
        composeRule.onNodeWithTag("source_calibration_kind_MODEL_JUDGED").assertExists()
        composeRule.onNodeWithTag("source_calibration_stats_MODEL_JUDGED").assertExists()
        composeRule.onNodeWithText("正向报告 5 次 · 配到真实作答 2 次 · 之后回忆率 50% · 真实作答基线 80%")
            .assertExists()
        // 样本不够 → 明确说"暂不建议改动"。
        composeRule.onNodeWithTag("source_calibration_verdict_MODEL_JUDGED").assertExists()
        composeRule.onNodeWithText("样本还不够（需 ≥30 对），暂不建议改动这个来源。").assertExists()
        // 样本够且低于基线超过 15 个百分点 → 建议下调（人工决定）。
        composeRule.onNodeWithText(
            "低于基线超过 15%：这个来源在过度声明，建议下调它的权重（人工决定，不自动改写）。",
        ).assertExists()
        // 讲题判定那档被排除在 FSRS 参数拟合之外，这一条必须写在界面上。
        composeRule.onNodeWithTag("source_calibration_model_judged_note").assertExists()
    }

    @Test
    fun explainsWhatToDoWhenNoSourceHasEverBeenReported() {
        composeRule.setContent {
            MaterialTheme {
                SourceCalibrationSection(provider = { emptyList() })
            }
        }

        composeRule.onNodeWithTag("source_calibration_empty").assertExists()
    }
}
