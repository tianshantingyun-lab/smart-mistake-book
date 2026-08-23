package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorMemoryPreference
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorRequestedLocalCapability
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorLocalIntentPanelInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun approvedReadsAndWriteRestrictionsBecomeSmallLocalPanels() {
        val output = mutableStateOf(mistakeLookupOutput())
        val studentMessage = mutableStateOf("帮我找导数函数单调性的错题")
        var openedMistakeNotebook = false
        composeRule.setContent {
            SmartMistakeBookTheme {
                RootPageColumn {
                    TutorLocalIntentPanel(
                        output = output.value,
                        studentMessage = studentMessage.value,
                        catalogEntries = listOf(catalogEntry()),
                        profile = learningProfile(),
                        onRequestSave = {},
                        onRequestEnd = {},
                        onOpenMistakeNotebook = { openedMistakeNotebook = true },
                        onOpenProfile = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("找到 1 道相符错题").assertExists()
        composeRule.onNodeWithTag("tutor_open_mistake_notebook").performClick()
        composeRule.runOnIdle { assertTrue(openedMistakeNotebook) }

        composeRule.runOnIdle {
            studentMessage.value = "看看我的学习情况"
            output.value = progressLookupOutput()
        }
        composeRule.onNodeWithText("已记录 8 次学习反馈").assertExists()
        composeRule.onNodeWithText("需要再看看 · 导数变号").assertExists()

        composeRule.runOnIdle {
            studentMessage.value = "这次不记"
            output.value = blockMemoryOutput()
        }
        composeRule.onNodeWithText("这次对话不会写入长期学习记录").assertExists()
    }

    private fun mistakeLookupOutput() = output(
        TutorIntentDecision(
            intent = TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
            confidence = 0.94,
            explicitActionRequest = true,
            memoryPreference = TutorMemoryPreference.UNCHANGED,
            requestedLocalCapability = TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
            lookupTerms = listOf("导数"),
        ),
    )

    private fun progressLookupOutput() = output(
        TutorIntentDecision(
            intent = TutorMessageIntent.LEARNING_PROGRESS_LOOKUP,
            confidence = 0.94,
            explicitActionRequest = true,
            memoryPreference = TutorMemoryPreference.UNCHANGED,
            requestedLocalCapability = TutorRequestedLocalCapability.READ_LEARNING_PROGRESS,
        ),
    )

    private fun blockMemoryOutput() = output(
        TutorIntentDecision(
            intent = TutorMessageIntent.CASUAL_CONVERSATION,
            confidence = 0.94,
            explicitActionRequest = true,
            memoryPreference = TutorMemoryPreference.BLOCK_LONG_TERM_WRITES_FOR_SESSION,
            requestedLocalCapability = TutorRequestedLocalCapability.NONE,
        ),
    )

    private fun output(decision: TutorIntentDecision) = TutorRespondOutput(
        sessionId = "intent-session",
        draftRevisionNumber = 1,
        questionDocumentId = "question-1",
        responseOrdinal = 1,
        messageMarkdown = "好的。",
        intentDecision = decision,
        modelVersion = "test-model",
    )

    private fun catalogEntry() = StudyCatalogEntry(
        entryId = "entry-1",
        problemId = "problem-1",
        problemRevisionId = "revision-1",
        practiceUnitId = "practice-1",
        subject = "MATH",
        title = "导数与单调性",
        problemMarkdown = "题面",
        sourceKey = null,
        isCuratedExample = false,
        chapterLabels = listOf("函数"),
        knowledgeLabels = listOf("导数"),
        masteryStatus = MasteryStatus.LEARNING,
        nextReviewAtEpochMillis = null,
        retrievability = 0.4,
    )

    private fun learningProfile() = StudyProfileOverview(
        hasLearningEvidence = true,
        recordedAttemptCount = 8,
        weaknesses = listOf(
            StudyKnowledgeSummary(
                knowledgeNodeId = "knowledge-1",
                displayName = "导数变号",
                status = MasteryStatus.LEARNING,
                conservativeMasteryScore = 0.35,
            ),
        ),
    )
}
