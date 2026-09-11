package com.tingyun.smartmistakebook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tingyun.smartmistakebook.core.domain.ReTeachOpening
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.NetworkMode
import com.tingyun.smartmistakebook.core.model.TeachingArtifactVerification
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoice
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import com.tingyun.smartmistakebook.feature.review.ReviewSessionScreen
import org.junit.Rule
import org.junit.Test

/**
 * 开场重教的**顺序**（spec §2.16）：leech 卡先呈现针对错误认知的材料，学员确认后才露出
 * 题干与选项。
 *
 * 这里验的是顺序而不是"有没有渲染"——顺序才是这条规则的内容。降权与难度冻结早已生效，
 * 缺的正是"先重教再练"这个次序；只断言材料卡存在，等于没测到 §2.16 的那一半。
 */
class ReviewReTeachOpeningInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val stem = "求 f(x)=x^3-3x+1 在闭区间上的最大值与最小值。"

    private val artifact = VerifiedTeachingArtifact(
        id = "teaching:reteach",
        subject = "MATH",
        title = "闭区间上的函数最值",
        problemMarkdown = stem,
        explanationMarkdown = "端点值与驻点值必须同时比较。",
        verification = TeachingArtifactVerification.CURATED_REFERENCE,
        assessmentItems = listOf(
            TutorAssessmentItem(
                id = "assessment:reteach",
                stemMarkdown = stem,
                choices = listOf(
                    TutorChoice(id = "A", markdown = "最大值为 3，最小值为 -1"),
                    TutorChoice(id = "B", markdown = "最大值为 2，最小值为 -2"),
                ),
                correctChoiceId = "A",
                knowledgeNodeIds = setOf("knowledge:m1:closed-interval-extrema"),
            ),
        ),
        knowledgeNodeIds = setOf("knowledge:m1:closed-interval-extrema"),
    )

    private val opening = ReTeachOpening(
        materialId = "material:misconception",
        title = "闭区间最值常见错误",
        materialType = KnowledgeTeachingMaterialType.MISCONCEPTION_GUIDE,
        markdown = "只比较驻点而漏掉端点，是最常见的错误。\n\n**适用范围**：只用于闭区间上的最值问题。",
    )

    private val capabilities = AppCapabilitySnapshot(
        networkMode = NetworkMode.LOCAL_FIRST,
        cameraCaptureAvailable = false,
        trustedOcrAvailable = false,
        tutorTeachingEnabled = true,
        remoteModelConfigured = false,
    )

    @Test
    fun aLeechedCardHidesTheQuestionUntilTheMaterialIsAcknowledged() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                ReviewSessionScreen(
                    onBack = {},
                    capabilities = capabilities,
                    practiceUnitId = "practice:m1:closed-interval-extrema:whole",
                    presentationId = "presentation:reteach",
                    teachingArtifact = artifact,
                    profile = StudyProfileOverview(hasLearningEvidence = true),
                    reTeachOpening = opening,
                    queuePosition = 1,
                    queueSize = 3,
                    onSubmitChoice = { error("re-teach opening must be acknowledged first") },
                    onRevealAnswer = { error("re-teach must not go through revealAnswer") },
                    onContinue = {},
                )
            }
        }
        composeRule.waitForIdle()

        // 材料先出现，题干与选项都还不在屏上。
        composeRule.onNodeWithTag("review_reteach_opening").assertIsDisplayed()
        composeRule.onNodeWithTag("review_reteach_material_title").assertIsDisplayed()
        composeRule.onNodeWithText(stem).assertDoesNotExist()
        composeRule.onNodeWithTag("review_submit_answer").assertDoesNotExist()

        // 确认后才进入作答。
        composeRule.onNodeWithTag("review_reteach_acknowledge").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(stem).assertIsDisplayed()
        composeRule.onNodeWithTag("review_submit_answer").assertIsDisplayed()
        composeRule.onNodeWithTag("review_reteach_opening").assertDoesNotExist()
    }

    @Test
    fun anOrdinaryCardGoesStraightToTheQuestion() {
        // 反例：没有 leech 的题不该被重教拦住——否则每次复习都要先过一道材料页，
        // 而"重教"这个信号会退化成常态。
        composeRule.setContent {
            SmartMistakeBookTheme {
                ReviewSessionScreen(
                    onBack = {},
                    capabilities = capabilities,
                    practiceUnitId = "practice:m1:closed-interval-extrema:whole",
                    presentationId = "presentation:ordinary",
                    teachingArtifact = artifact,
                    profile = StudyProfileOverview(hasLearningEvidence = true),
                    reTeachOpening = null,
                    queuePosition = 1,
                    queueSize = 3,
                    onSubmitChoice = { error("not exercised") },
                    onRevealAnswer = { error("not exercised") },
                    onContinue = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("review_reteach_opening").assertDoesNotExist()
        composeRule.onNodeWithText(stem).assertIsDisplayed()
    }
}
