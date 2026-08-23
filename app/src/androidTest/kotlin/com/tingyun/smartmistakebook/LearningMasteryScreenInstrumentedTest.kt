package com.tingyun.smartmistakebook

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test

class LearningMasteryScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun learningHistoryIsGroupedForStudentsWithoutKnowledgeBaseInternals() {
        val now = 10 * DAY_MILLIS
        composeRule.setContent {
            SmartMistakeBookTheme {
                LearningMasteryScreen(
                    overview = StudyProfileOverview(
                        hasLearningEvidence = true,
                        recordedAttemptCount = 8,
                        weaknesses = listOf(
                            summary(
                                id = "math:monotonicity",
                                name = "函数单调性",
                                subject = SubjectKind.MATH,
                                topic = "函数",
                                status = MasteryStatus.LEARNING,
                                confidence = 0.58,
                                lastEvidenceAtEpochMillis = now - DAY_MILLIS,
                            ),
                        ),
                        strengths = listOf(
                            summary(
                                id = "physics:newton-2",
                                name = "牛顿第二定律",
                                subject = SubjectKind.PHYSICS,
                                topic = "相互作用与运动",
                                status = MasteryStatus.MASTERED,
                                confidence = 0.86,
                                lastEvidenceAtEpochMillis = now,
                            ),
                        ),
                    ),
                    onBack = {},
                    nowEpochMillis = now,
                )
            }
        }

        composeRule.onNodeWithText("学习掌握").assertIsDisplayed()
        composeRule.onNodeWithTag("learning_mastery_subject:MATH").assertIsDisplayed()
        composeRule.onAllNodesWithText("函数单调性").assertCountEquals(2)
        composeRule.onNodeWithTag("learning_mastery_subject:PHYSICS").assertIsDisplayed()
        composeRule.onAllNodesWithText("牛顿第二定律").assertCountEquals(2)
        composeRule.onNodeWithTag("learning_mastery_recent").assertIsDisplayed()
        composeRule.onNodeWithText("今天").assertIsDisplayed()
        composeRule.onNodeWithText("昨天").assertIsDisplayed()
        FORBIDDEN_STUDENT_TERMS.forEach { term ->
            composeRule.onAllNodesWithText(term, substring = true).assertCountEquals(0)
        }
        saveAuditScreenshot()
    }

    private fun saveAuditScreenshot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = requireNotNull(context.getExternalFilesDir("audit"))
        val screenshot = File(directory, "learning-mastery.png")
        FileOutputStream(screenshot).use { output ->
            composeRule.onNodeWithTag("learning_mastery_screen")
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun summary(
        id: String,
        name: String,
        subject: SubjectKind,
        topic: String,
        status: MasteryStatus,
        confidence: Double,
        lastEvidenceAtEpochMillis: Long,
    ) = StudyKnowledgeSummary(
        knowledgeNodeId = id,
        displayName = name,
        status = status,
        conservativeMasteryScore = confidence,
        evidenceMass = 4.0,
        independentCorrectObservationCount = 3,
        lastEvidenceAtEpochMillis = lastEvidenceAtEpochMillis,
        subject = subject,
        topicPath = listOf(topic),
    )

    private companion object {
        const val DAY_MILLIS = 86_400_000L

        val FORBIDDEN_STUDENT_TERMS = listOf(
            "原子知识",
            "知识本体",
            "检索召回",
            "学习投影",
            "grounding",
            "taxonomy",
            "embedding",
            "置信度",
            "数据库",
            "数据表",
            "索引",
            "节点",
            "向量",
            "schema",
            "模型候选",
            "前置边",
            "分类依据",
        )
    }
}
