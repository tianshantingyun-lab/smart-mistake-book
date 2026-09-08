package com.tingyun.smartmistakebook

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.data.study.StudyFixtureRegistry
import com.tingyun.smartmistakebook.core.domain.AdaptiveDecision
import com.tingyun.smartmistakebook.core.domain.AdaptiveDecisionKind
import com.tingyun.smartmistakebook.core.domain.ExamCalendarEntry
import com.tingyun.smartmistakebook.core.domain.FsrsParameterOptimizer
import com.tingyun.smartmistakebook.core.domain.SaveTutorProblemCommand
import com.tingyun.smartmistakebook.core.domain.SchedulingEvaluationReport
import com.tingyun.smartmistakebook.core.domain.SaveTutorProblemReceipt
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealRequest
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealResult
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmission
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyDataStatus
import com.tingyun.smartmistakebook.core.domain.StudyExperienceRepository
import com.tingyun.smartmistakebook.core.domain.StudyExperienceSnapshot
import com.tingyun.smartmistakebook.core.domain.StudyReviewChoiceSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewRatingSubmission
import com.tingyun.smartmistakebook.core.domain.StudyReviewRatingSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReport
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReportSubmission
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReportSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionProgress
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class RootTutorFailClosedInstrumentedTest {
    private val repository = ControllableStudyExperienceRepository()
    private val repositoryRule = StudyRepositoryOverrideRule(repository)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(repositoryRule)
        .around(composeRule)

    @Before
    fun requireTutorTeachingCapability() {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as SmartMistakeBookApplication
        kotlinx.coroutines.runBlocking {
            application.studyDatabase.clearAllData()
        }
        assumeTrue(
            "The strict-offline diagnostic flavor intentionally has no semantic tutor.",
            application.capabilities.tutorTeachingEnabled,
        )
    }

    @Test
    fun readyQuestionIsRemovedDuringLoadingAndErrorThenRecovers() {
        navigateToTutor()

        repository.publishReady(TUTOR_PRACTICE_UNIT_ID)
        waitForText(TUTOR_TITLE)
        waitForTag("tutor_choice_a")

        repository.publishStatusPreservingPayload(StudyDataStatus.LOADING)
        waitForText("正在读取本机学习记录")
        waitForNoTutorActions()

        repository.publishStatusPreservingPayload(StudyDataStatus.ERROR)
        waitForText("本机学习数据暂时无法更新")
        waitForNoTutorActions()

        repository.publishReady(TUTOR_PRACTICE_UNIT_ID)
        waitForText(TUTOR_TITLE)
        waitForTag("tutor_choice_a")
        waitForTag("tutor_reveal_answer")
    }

    @Test
    fun delayedArtifactCannotPairWithNewerPracticeUnitOrSubmission() {
        repository.delayArtifact(TUTOR_PRACTICE_UNIT_ID)
        navigateToTutor()

        repository.publishReady(TUTOR_PRACTICE_UNIT_ID)
        try {
            waitUntil { TUTOR_PRACTICE_UNIT_ID in repository.artifactRequests }

            repository.publishReady(SECOND_PRACTICE_UNIT_ID)
            waitForText(SECOND_TITLE)
            waitForTag("tutor_choice_a")

            repository.releaseArtifact(TUTOR_PRACTICE_UNIT_ID)
            waitUntil { TUTOR_PRACTICE_UNIT_ID in repository.completedArtifactRequests }
            composeRule.waitForIdle()

            composeRule.onAllNodesWithText(TUTOR_TITLE, substring = false).assertCountEquals(0)
            composeRule.onAllNodesWithText(SECOND_TITLE, substring = false).assertCountEquals(1)

            composeRule.onNodeWithTag("tutor_choice_a").performClick()
            composeRule.onNodeWithTag("tutor_submit_answer").performClick()
            waitUntil { repository.submissions.size == 1 }
            waitUntil {
                composeRule.onAllNodesWithTag("tutor_submit_answer")
                    .fetchSemanticsNodes()
                    .isEmpty()
            }

            assertEquals(SECOND_PRACTICE_UNIT_ID, repository.submissions.single().practiceUnitId)
        } finally {
            repository.releaseArtifact(TUTOR_PRACTICE_UNIT_ID)
        }
    }

    @Test
    fun revealedExplanationAndRequestIdentityDoNotLeakIntoNextPracticeUnit() {
        navigateToTutor()

        repository.publishReady(TUTOR_PRACTICE_UNIT_ID)
        waitForText(TUTOR_TITLE)
        waitForTag("tutor_reveal_answer")
        composeRule.onNodeWithTag("tutor_reveal_answer").performScrollTo().performClick()
        waitUntil { repository.revealRequests.size == 1 }
        waitForTag("tutor_full_explanation")

        repository.publishReady(SECOND_PRACTICE_UNIT_ID)
        waitForText(SECOND_TITLE)
        waitUntil {
            composeRule.onAllNodesWithTag("tutor_full_explanation").fetchSemanticsNodes().isEmpty()
        }
        waitForTag("tutor_reveal_answer")
        composeRule.onNodeWithTag("tutor_reveal_answer").performScrollTo().performClick()
        waitUntil { repository.revealRequests.size == 2 }
        waitForTag("tutor_full_explanation")

        assertEquals(
            listOf(TUTOR_PRACTICE_UNIT_ID, SECOND_PRACTICE_UNIT_ID),
            repository.revealRequests.map(StudyAnswerRevealRequest::practiceUnitId),
        )
        assertEquals(2, repository.revealRequests.map(StudyAnswerRevealRequest::presentationId).distinct().size)
    }

    @Test
    fun emptyStudySnapshotShowsTheTrueNewUserLibraryAndReviewStates() {
        repository.publishEmptyReady()

        waitForTag("nav_library")
        composeRule.onNodeWithTag("nav_library").performClick()
        waitForTag("library_empty_state")
        waitForText("还没有错题")
        composeRule.onAllNodesWithTag("library_capture_button").assertCountEquals(1)
        composeRule.onAllNodesWithTag("library_search_field").assertCountEquals(0)
        composeRule.onAllNodesWithText(TUTOR_TITLE, substring = false).assertCountEquals(0)

        composeRule.onNodeWithTag("nav_profile").performClick()
        waitForTag("root_profile")
        composeRule.onAllNodesWithText("当前学习情况").assertCountEquals(0)
        composeRule.onAllNodesWithText("当前薄弱点").assertCountEquals(0)
        composeRule.onAllNodesWithText("学习次数").assertCountEquals(0)
        composeRule.onNodeWithTag("profile_learning_mastery").assertExists()

        composeRule.onNodeWithTag("nav_review").performClick()
        waitForText("暂无学习记录")
        waitForText("暂无待复习题")
    }

    @Test
    fun savedCapturedQuestionReviewsTheExactOriginalWithoutInventingAnAnswer() {
        repository.publishCapturedReviewReady()

        waitForText("1")
        waitForText("道计划复习")
        composeRule.onNodeWithTag("review_start_button").performClick()
        waitForTag("captured_review_session_root")
        waitForText(CAPTURED_QUESTION_MARKDOWN)
        composeRule.onAllNodesWithText(TUTOR_TITLE, substring = false).assertCountEquals(0)

        composeRule.onNodeWithTag("review_self_report_recalled").performClick()
        waitUntil { repository.selfReports.size == 1 }
        waitForText("今日复习已完成")

        assertEquals(CAPTURED_PRACTICE_UNIT_ID, repository.selfReports.single().practiceUnitId)
        assertEquals(StudyReviewSelfReport.RECALL_COMPLETED, repository.selfReports.single().report)
    }

    private fun navigateToTutor() {
        waitForTag("nav_tutor")
        composeRule.onNodeWithTag("nav_tutor").performClick()
        waitForTag("root_tutor")
    }

    private fun waitForTag(tag: String) {
        waitUntil {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) {
        try {
            waitUntil {
                composeRule.onAllNodesWithText(text, substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (failure: Throwable) {
            throw AssertionError(
                "Timed out waiting for text '$text'. Current semantics:\n" +
                    composeRule.onRoot(useUnmergedTree = true).printToString(),
                failure,
            )
        }
    }

    private fun waitForNoTutorActions() {
        waitUntil {
            composeRule.onAllNodesWithTag("tutor_choice_a").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithTag("tutor_submit_answer").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithTag("tutor_reveal_answer").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onAllNodesWithTag("tutor_choice_a").assertCountEquals(0)
        composeRule.onAllNodesWithTag("tutor_submit_answer").assertCountEquals(0)
        composeRule.onAllNodesWithTag("tutor_reveal_answer").assertCountEquals(0)
    }

    private fun waitUntil(condition: () -> Boolean) {
        composeRule.waitUntil(timeoutMillis = 20_000, condition = condition)
    }

    companion object {
        // Local literal mirroring the curated tutor practice unit of the debug
        // fixture; androidTest must not reference the debug-only seed directly
        // (audit section 9.2 / PR-05).
        const val TUTOR_PRACTICE_UNIT_ID = "practice:m1:derivative-sign-change:whole"
        const val SECOND_PRACTICE_UNIT_ID = "practice:m1:closed-interval-extrema:whole"
        const val CAPTURED_PRACTICE_UNIT_ID = "practice:captured:exact-original"
        const val CAPTURED_QUESTION_MARKDOWN = "已保存原题：若 x + 3 = 7，求 x。"
        const val TUTOR_TITLE = "由导数符号判断单调区间"
        const val SECOND_TITLE = "闭区间上的函数最值"
    }
}

private class StudyRepositoryOverrideRule(
    private val replacement: StudyExperienceRepository,
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val application = InstrumentationRegistry.getInstrumentation()
                .targetContext
                .applicationContext as SmartMistakeBookApplication
            val repositoryField = SmartMistakeBookApplication::class.java
                .getDeclaredField("studyRepository")
                .apply { isAccessible = true }
            val original = application.studyRepository
            repositoryField.set(application, replacement)
            try {
                base.evaluate()
            } finally {
                repositoryField.set(application, original)
                replacement.close()
            }
        }
    }
}

private class ControllableStudyExperienceRepository : StudyExperienceRepository {
    private val mutableSnapshot = MutableStateFlow(StudyExperienceSnapshot())
    private val artifactGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    override val snapshot: StateFlow<StudyExperienceSnapshot> = mutableSnapshot
    val artifactRequests = CopyOnWriteArrayList<String>()
    val completedArtifactRequests = CopyOnWriteArrayList<String>()
    val submissions = CopyOnWriteArrayList<StudyChoiceSubmission>()
    val selfReports = CopyOnWriteArrayList<StudyReviewSelfReportSubmission>()
    val revealRequests = CopyOnWriteArrayList<StudyAnswerRevealRequest>()

    fun publishReady(practiceUnitId: String) {
        val artifact = requireArtifact(practiceUnitId)
        mutableSnapshot.value = StudyExperienceSnapshot(
            status = StudyDataStatus.READY,
            tutorPracticeUnitId = practiceUnitId,
            tutorDecision = artifact.askDecision(),
        )
    }

    fun publishEmptyReady() {
        mutableSnapshot.value = StudyExperienceSnapshot(status = StudyDataStatus.READY)
    }

    fun publishCapturedReviewReady() {
        mutableSnapshot.value = StudyExperienceSnapshot(
            status = StudyDataStatus.READY,
            catalog = listOf(
                StudyCatalogEntry(
                    entryId = "entry:captured:exact-original",
                    problemId = "problem:captured:exact-original",
                    problemRevisionId = "revision:captured:exact-original",
                    practiceUnitId = RootTutorFailClosedInstrumentedTest.CAPTURED_PRACTICE_UNIT_ID,
                    subject = "MATH",
                    title = "一次方程原题",
                    problemMarkdown = RootTutorFailClosedInstrumentedTest.CAPTURED_QUESTION_MARKDOWN,
                    sourceKey = "capture:android-test",
                    isCuratedExample = false,
                    nextReviewAtEpochMillis = null,
                    retrievability = null,
                ),
            ),
            review = StudyReviewOverview(
                planId = CAPTURED_REVIEW_PLAN_ID,
                scheduledCount = 1,
                estimatedSeconds = 120,
                scheduledPracticeUnitIds = listOf(
                    RootTutorFailClosedInstrumentedTest.CAPTURED_PRACTICE_UNIT_ID,
                ),
            ),
        )
    }

    fun publishStatusPreservingPayload(status: StudyDataStatus) {
        mutableSnapshot.value = mutableSnapshot.value.copy(status = status)
    }

    fun delayArtifact(practiceUnitId: String) {
        artifactGates[practiceUnitId] = CompletableDeferred()
    }

    fun releaseArtifact(practiceUnitId: String) {
        artifactGates[practiceUnitId]?.complete(Unit)
    }

    override suspend fun initialize() = Unit

    override suspend fun saveTutorProblem(
        command: SaveTutorProblemCommand,
    ): SaveTutorProblemReceipt =
        // Fail-closed fake: it holds no problem objects, so saving any
        // referenced problem must report the reference as missing.
        SaveTutorProblemReceipt.ReferenceNotFound(
            reason = "This fail-closed test repository holds no problem object for ${command.conversationId}",
        )

    override suspend fun teachingArtifact(practiceUnitId: String): VerifiedTeachingArtifact? {
        artifactRequests += practiceUnitId
        if (practiceUnitId == RootTutorFailClosedInstrumentedTest.CAPTURED_PRACTICE_UNIT_ID) {
            completedArtifactRequests += practiceUnitId
            return null
        }
        val artifact = artifactGates[practiceUnitId]?.let { gate ->
            withContext(NonCancellable) {
                gate.await()
                requireArtifact(practiceUnitId).also {
                    completedArtifactRequests += practiceUnitId
                }
            }
        } ?: requireArtifact(practiceUnitId).also {
            completedArtifactRequests += practiceUnitId
        }
        return artifact
    }

    override suspend fun submitChoice(
        submission: StudyChoiceSubmission,
    ): StudyChoiceSubmissionResult {
        submissions += submission
        val isCorrect = requireArtifact(submission.practiceUnitId)
            .assessmentItems
            .single()
            .evaluateChoice(submission.selectedChoiceId)
            .isCorrect
        return StudyChoiceSubmissionResult(
            attemptId = "attempt:${submission.requestId}",
            created = true,
            isCorrect = isCorrect,
            evidenceReason = if (isCorrect) {
                LearningEvidenceReason.INDEPENDENT_CORRECT
            } else {
                LearningEvidenceReason.INDEPENDENT_INCORRECT
            },
        )
    }

    override suspend fun submitReviewRating(
        sessionId: String,
        expectedStateVersion: Long,
        submission: StudyReviewRatingSubmission,
    ): StudyReviewRatingSubmissionResult = error("Review is outside this root Tutor test")

    override suspend fun recordTeachingFocus(
        sessionId: String,
        practiceUnitId: String,
        labels: List<String>,
        cycleOrdinal: Int,
    ) = Unit

    override suspend fun submitKnowledgeQuizFeedback(
        requestId: String,
        knowledgeNodeId: String,
        correctChoiceId: String,
        selectedChoiceId: String,
        occurredAtEpochMillis: Long,
        conversationId: String,
    ) = com.tingyun.smartmistakebook.core.domain.KnowledgeQuizFeedbackResult(
        isCorrect = selectedChoiceId == correctChoiceId,
        evidenceRecorded = true,
    )

    override fun observeTeachingAdvisories(
        practiceUnitId: String?,
    ) = kotlinx.coroutines.flow.flowOf(emptyList<com.tingyun.smartmistakebook.core.model.TeachingAdvisoryRecord>())

    override suspend fun recordMisconceptionAdvisory(
        sessionId: String,
        practiceUnitId: String,
        payloadMarkdown: String,
        cycleOrdinal: Int,
    ) = Unit

    override suspend fun declareExam(entry: ExamCalendarEntry) = Unit

    override suspend fun removeExam(entryId: String) = Unit

    override suspend fun evaluateSchedulingModels(): SchedulingEvaluationReport? = null

    override suspend fun optimizeSchedulingParameters(): FsrsParameterOptimizer.Result? = null

    override suspend fun submitReviewChoice(
        sessionId: String,
        expectedStateVersion: Long,
        submission: StudyChoiceSubmission,
    ): StudyReviewChoiceSubmissionResult = error("Review is outside this root Tutor test")

    override suspend fun submitReviewSelfReport(
        sessionId: String,
        expectedStateVersion: Long,
        submission: StudyReviewSelfReportSubmission,
    ): StudyReviewSelfReportSubmissionResult {
        selfReports += submission
        val progress = StudyReviewSessionProgress(
            sessionId = CAPTURED_REVIEW_SESSION_ID,
            planId = CAPTURED_REVIEW_PLAN_ID,
            currentOrdinal = 1,
            queueSize = 1,
            stateVersion = 1,
            status = com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus.COMPLETED,
        )
        mutableSnapshot.value = mutableSnapshot.value.copy(
            review = mutableSnapshot.value.review.copy(
                activeSessionId = null,
                currentOrdinal = 1,
                sessionStateVersion = 1,
                completedToday = true,
            ),
        )
        return StudyReviewSelfReportSubmissionResult(
            attemptId = "attempt:${submission.requestId}",
            created = true,
            report = submission.report,
            evidenceReason = when (submission.report) {
                StudyReviewSelfReport.RECALL_COMPLETED ->
                    LearningEvidenceReason.SELF_REPORTED_RECALL
                StudyReviewSelfReport.RECALLED_WITH_EFFORT ->
                    LearningEvidenceReason.CORRECT_ON_RETRY
                StudyReviewSelfReport.NEEDS_HELP -> LearningEvidenceReason.SELF_REPORTED_STUCK
            },
            progress = progress,
            nextPracticeUnitId = null,
        )
    }

    override suspend fun revealAnswer(
        request: StudyAnswerRevealRequest,
    ): StudyAnswerRevealResult {
        revealRequests += request
        return StudyAnswerRevealResult(
            outcomeId = "reveal:${request.requestId}",
            created = true,
            explanationMarkdown = requireArtifact(request.practiceUnitId).explanationMarkdown,
        )
    }

    override suspend fun startOrResumeReviewSession(
        requestId: String,
        occurredAtEpochMillis: Long,
    ): StudyReviewSessionProgress? {
        if (
            RootTutorFailClosedInstrumentedTest.CAPTURED_PRACTICE_UNIT_ID !in
            mutableSnapshot.value.review.scheduledPracticeUnitIds
        ) return null
        val progress = StudyReviewSessionProgress(
            sessionId = CAPTURED_REVIEW_SESSION_ID,
            planId = CAPTURED_REVIEW_PLAN_ID,
            currentOrdinal = 0,
            queueSize = 1,
            stateVersion = 0,
            status = com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus.ACTIVE,
        )
        mutableSnapshot.value = mutableSnapshot.value.copy(
            review = mutableSnapshot.value.review.copy(
                activeSessionId = progress.sessionId,
                currentOrdinal = progress.currentOrdinal,
                sessionStateVersion = progress.stateVersion,
            ),
        )
        return progress
    }

    override fun close() {
        artifactGates.values.forEach { it.complete(Unit) }
    }

    private companion object {
        const val CAPTURED_REVIEW_PLAN_ID = "review-plan:captured:exact-original"
        const val CAPTURED_REVIEW_SESSION_ID = "review-session:captured:exact-original"
    }
}

private fun requireArtifact(practiceUnitId: String): VerifiedTeachingArtifact =
    requireNotNull(
        StudyFixtureRegistry.source.teachingArtifactForPracticeUnit(practiceUnitId),
    ) { "No verified teaching artifact is registered for $practiceUnitId" }

private fun VerifiedTeachingArtifact.askDecision(): AdaptiveDecision = AdaptiveDecision(
    kind = AdaptiveDecisionKind.ASK,
    selectedAssessmentItemId = assessmentItems.single().id,
    reasonCodes = setOf("ANDROID_TEST_VERIFIED_ITEM"),
    selectorVersion = "root-fail-closed-test-v1",
    projectionCheckpointSequence = 0,
)
