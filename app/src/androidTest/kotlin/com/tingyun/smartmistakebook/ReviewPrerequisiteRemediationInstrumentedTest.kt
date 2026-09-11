package com.tingyun.smartmistakebook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.tingyun.smartmistakebook.core.domain.PrerequisiteRemediation
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
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
 * 前置补救的**非阻塞**性（spec §2.9）。
 *
 * 这里验的是"补救不拦作答"，而不是"材料有没有渲染"：§2.16 的开场重教是必经步骤（学员须先
 * 确认），而前置补救只是题干旁的上下文——两条通道在界面上共用材料卡，行为却必须相反。
 * 只断言"出现了补救卡"，等于把两者混为一谈，也测不到这条规则的内容。
 */
class ReviewPrerequisiteRemediationInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val stem = "求 f(x)=x^3-3x+1 在闭区间上的最大值与最小值。"

    private val artifact = VerifiedTeachingArtifact(
        id = "teaching:prereq",
        subject = "MATH",
        title = "闭区间上的函数最值",
        problemMarkdown = stem,
        explanationMarkdown = "端点值与驻点值必须同时比较。",
        verification = TeachingArtifactVerification.CURATED_REFERENCE,
        assessmentItems = listOf(
            TutorAssessmentItem(
                id = "assessment:prereq",
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

    private val remediation = PrerequisiteRemediation(
        prerequisiteName = "从图像读取单调性",
        title = "读图判断单调性常见错误",
        markdown = "单调区间要按定义域分段读。\n\n**适用范围**：只用于可导函数的单调性判断。",
    )

    private val capabilities = AppCapabilitySnapshot(
        networkMode = NetworkMode.LOCAL_FIRST,
        cameraCaptureAvailable = false,
        trustedOcrAvailable = false,
        tutorTeachingEnabled = true,
        remoteModelConfigured = false,
    )

    @Test
    fun theRemediationSitsBesideTheQuestionWithoutBlockingTheAttempt() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                ReviewSessionScreen(
                    onBack = {},
                    capabilities = capabilities,
                    practiceUnitId = "practice:m1:closed-interval-extrema:whole",
                    presentationId = "presentation:prereq",
                    teachingArtifact = artifact,
                    profile = StudyProfileOverview(hasLearningEvidence = true),
                    prerequisiteRemediation = remediation,
                    queuePosition = 1,
                    queueSize = 3,
                    onSubmitChoice = { error("the remediation must not block submission") },
                    onRevealAnswer = { error("remediation must not go through revealAnswer") },
                    onContinue = {},
                )
            }
        }
        composeRule.waitForIdle()

        // 材料在屏上……
        composeRule.onNodeWithTag("review_prereq_remediation").assertIsDisplayed()
        composeRule.onNodeWithTag("review_prereq_name").assertIsDisplayed()
        composeRule.onNodeWithTag("review_prereq_material_title").assertIsDisplayed()
        // ……而题干与提交按钮**同时**可用。这才是 §2.9 与 §2.16 的区别所在：
        // 前置补救不设确认门，学员可以直接作答。
        composeRule.onNodeWithText(stem).assertIsDisplayed()
        composeRule.onNodeWithTag("review_submit_answer").assertIsDisplayed()
    }

    @Test
    fun anOrdinaryCardShowsNoRemediation() {
        // 反例：前置都达标时不该弹补救卡，否则每个有前置关系的题都变成关卡。
        composeRule.setContent {
            SmartMistakeBookTheme {
                ReviewSessionScreen(
                    onBack = {},
                    capabilities = capabilities,
                    practiceUnitId = "practice:m1:closed-interval-extrema:whole",
                    presentationId = "presentation:no-prereq",
                    teachingArtifact = artifact,
                    profile = StudyProfileOverview(hasLearningEvidence = true),
                    prerequisiteRemediation = null,
                    queuePosition = 1,
                    queueSize = 3,
                    onSubmitChoice = { error("not exercised") },
                    onRevealAnswer = { error("not exercised") },
                    onContinue = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("review_prereq_remediation").assertDoesNotExist()
        composeRule.onNodeWithText(stem).assertIsDisplayed()
    }
}
