package com.tingyun.smartmistakebook

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import com.tingyun.smartmistakebook.feature.review.ReviewRoute
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented verification of the batch-intake coverage preview (spec
 * `batch-intake-spec.md` §6 P3): ReviewRoute renders the intake backlog as a
 * schedule ("还有 N 道新题待学：每天约 X 题，约 M 天覆盖") when the overview
 * reports a non-empty backlog, and stays silent for an empty backlog.
 */
class ReviewIntakeBacklogPreviewInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun nonEmptyBacklogShowsTheCoveragePreview() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                ReviewRoute(
                    overview = StudyReviewOverview(
                        scheduledCount = 0,
                        intakeBacklogCount = 45,
                        intakeMedianEstimateSeconds = 100,
                    ),
                    profile = StudyProfileOverview(hasLearningEvidence = true),
                    onStartReview = {},
                )
            }
        }

        composeRule.onNodeWithTag("review_intake_backlog_preview").assertExists()
        // preview(45, 100s, 900s budget): slice = 300s, 300/100 = 3/day,
        // 45/3 = 15 days.
        composeRule.onNodeWithText("还有 45 道新题待学：每天约 3 题，约 15 天覆盖")
            .assertExists()
    }

    @Test
    fun emptyBacklogHidesThePreview() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                ReviewRoute(
                    overview = StudyReviewOverview(
                        scheduledCount = 3,
                        intakeBacklogCount = 0,
                        intakeMedianEstimateSeconds = 0,
                    ),
                    profile = StudyProfileOverview(hasLearningEvidence = true),
                    onStartReview = {},
                )
            }
        }

        composeRule.onNodeWithTag("review_intake_backlog_preview").assertDoesNotExist()
    }
}
