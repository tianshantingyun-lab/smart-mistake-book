package com.tingyun.smartmistakebook

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryKnowledgeItem
import com.tingyun.smartmistakebook.core.domain.LearningMasteryKnowledgeKey
import com.tingyun.smartmistakebook.core.domain.LearningMasteryKnowledgePage
import com.tingyun.smartmistakebook.core.domain.LearningMasteryLoadState
import com.tingyun.smartmistakebook.core.domain.LearningMasteryOverview
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPageRequest
import com.tingyun.smartmistakebook.core.domain.LearningMasteryProjectionRevision
import com.tingyun.smartmistakebook.core.domain.LearningMasteryStatus
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubject
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubjectOverview
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimeline
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineActivity
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineEntry
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineRange
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineRequest
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineSignal
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTrend
import com.tingyun.smartmistakebook.core.ui.Paper
import com.tingyun.smartmistakebook.core.ui.SmartDarkColors
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LearningMasteryScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun repositoryProjectionUsesOnlyShortStudentFacingLanguage() {
        val repository = displayRepository()

        composeRule.setContent {
            SmartMistakeBookTheme {
                LearningMasteryRoute(repository = repository, onBack = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            repository.requests.any {
                it.subject == LearningMasterySubject.MATHEMATICS
            }
        }
        composeRule.onNodeWithText("学习掌握").assertIsDisplayed()
        composeRule.onNodeWithTag("learning_mastery_overall").assertIsDisplayed()
        composeRule.onNodeWithTag("learning_mastery_distribution").assertIsDisplayed()
        composeRule.onNodeWithText("分科总览").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            repository.timelineRequests.any {
                it.subject == LearningMasterySubject.MATHEMATICS &&
                    it.range == LearningMasteryTimelineRange.LAST_7_DAYS
            }
        }
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag("learning_mastery_timeline_chart")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("learning_mastery_timeline_range_30")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            repository.timelineRequests.any {
                it.subject == LearningMasterySubject.MATHEMATICS &&
                    it.range == LearningMasteryTimelineRange.LAST_30_DAYS
            }
        }
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag("learning_mastery_timeline_chart")
                .fetchSemanticsNodes().isNotEmpty()
        }
        saveAuditScreenshot("learning-mastery-repository-top.png")
        composeRule.onNodeWithTag("learning_mastery_subject:MATHEMATICS").assertIsDisplayed()
        composeRule.onAllNodesWithText("最近变化").assertCountEquals(0)
        composeRule.onNodeWithTag("learning_mastery_section_progress")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("板块进度").assertIsDisplayed()
        composeRule.onNodeWithText("薄弱知识").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("知识点").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("函数单调性").assertCountEquals(1)
        composeRule.onAllNodesWithTag("learning_mastery_point:math-monotonicity")
            .assertCountEquals(1)
        composeRule.onAllNodes(
            hasContentDescription(
                "数学，需要再巩固，最近有波动",
                substring = false,
            ),
        ).assertCountEquals(1)
        assertNoInternalLanguage()
        saveAuditScreenshot("learning-mastery-repository.png")
    }

    @Test
    fun darkThemeUsesDarkPaletteAndRendersLearningMastery() {
        val repository = displayRepository()
        var darkBackground = Color.Unspecified
        var darkPaper = Color.Unspecified

        composeRule.setContent {
            SmartMistakeBookTheme(darkTheme = true) {
                darkBackground = MaterialTheme.colorScheme.background
                darkPaper = Paper
                LearningMasteryRoute(repository = repository, onBack = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            repository.requests.any {
                it.subject == LearningMasterySubject.MATHEMATICS
            }
        }
        composeRule.onNodeWithTag("learning_mastery_overall").assertIsDisplayed()
        assertEquals(SmartDarkColors.Paper, darkBackground)
        assertEquals(SmartDarkColors.Paper, darkPaper)
        assertNoInternalLanguage()
        saveDownloadScreenshot("learning-mastery-dark.png")
    }

    @Test
    fun selectedTimelineRangeSurvivesComposeStateRestoration() {
        val repository = displayRepository()
        val restorationTester = StateRestorationTester(composeRule)

        restorationTester.setContent {
            SmartMistakeBookTheme {
                LearningMasteryRoute(repository = repository, onBack = {})
            }
        }
        composeRule.waitUntil(timeoutMillis = 3_000) {
            repository.requests.any {
                it.subject == LearningMasterySubject.MATHEMATICS
            }
        }
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag("learning_mastery_timeline_chart")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("learning_mastery_timeline_range_30")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            repository.timelineRequests.any {
                it.subject == LearningMasterySubject.MATHEMATICS &&
                    it.range == LearningMasteryTimelineRange.LAST_30_DAYS
            }
        }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.waitUntil(timeoutMillis = 3_000) {
            repository.timelineRequests.any {
                it.subject == LearningMasterySubject.MATHEMATICS &&
                    it.range == LearningMasteryTimelineRange.LAST_30_DAYS
            }
        }
        composeRule.onNodeWithTag("learning_mastery_timeline_chart")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun selectingASubjectReadsOnlyThatSubjectsDisplayPage() {
        val repository = displayRepository()

        composeRule.setContent {
            SmartMistakeBookTheme {
                LearningMasteryRoute(repository = repository, onBack = {})
            }
        }
        composeRule.waitUntil(timeoutMillis = 3_000) {
            repository.requests.any {
                it.subject == LearningMasterySubject.MATHEMATICS
            }
        }

        composeRule.onNodeWithTag("learning_mastery_subject:PHYSICS")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) {
            repository.requests.any {
                it.subject == LearningMasterySubject.PHYSICS
            }
        }
        composeRule.onNodeWithText("牛顿第二定律")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("相互作用与运动").assertCountEquals(3)
        composeRule.onNodeWithTag("learning_mastery_point:physics-newton-two")
            .performScrollTo()
            .assertIsDisplayed()
        assertNoInternalLanguage()
    }

    @Test
    fun selectingABoardFiltersKnowledgeAndProgressToThatBoard() {
        val repository =
            FakeLearningMasteryDisplayRepository(
                overviewState =
                    LearningMasteryLoadState.Content(
                        LearningMasteryOverview(
                            revision = REVISION,
                            subjects =
                                listOf(
                                    LearningMasterySubjectOverview(
                                        subject = LearningMasterySubject.MATHEMATICS,
                                        status = LearningMasteryStatus.NEEDS_REINFORCEMENT,
                                        trend = LearningMasteryTrend.RECENTLY_FLUCTUATING,
                                    ),
                                ),
                        ),
                    ),
                pages =
                    mapOf(
                        LearningMasterySubject.MATHEMATICS to
                            listOf(
                                knowledge(
                                    key = "math-monotonicity",
                                    name = "函数单调性",
                                    path = listOf("数学", "函数"),
                                    status = LearningMasteryStatus.NEEDS_REINFORCEMENT,
                                    trend = LearningMasteryTrend.RECENTLY_FLUCTUATING,
                                ),
                                knowledge(
                                    key = "math-derivative",
                                    name = "导数的几何意义",
                                    path = listOf("数学", "导数"),
                                    status = LearningMasteryStatus.FAIRLY_STEADY,
                                    trend = LearningMasteryTrend.STEADY,
                                ),
                            ),
                    ),
            )

        composeRule.setContent {
            SmartMistakeBookTheme {
                LearningMasteryRoute(repository = repository, onBack = {})
            }
        }
        composeRule.waitUntil(timeoutMillis = 3_000) {
            repository.requests.any {
                it.subject == LearningMasterySubject.MATHEMATICS
            }
        }
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText("导数").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("learning_mastery_section_1")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag("learning_mastery_point:math-derivative")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("函数单调性").assertCountEquals(0)
        assertNoInternalLanguage()
    }

    @Test
    fun emptyProjectionShowsOneShortReadOnlyState() {
        val repository =
            FakeLearningMasteryDisplayRepository(
                overviewState = LearningMasteryLoadState.Empty(REVISION),
                pages = emptyMap(),
            )

        composeRule.setContent {
            SmartMistakeBookTheme {
                LearningMasteryRoute(repository = repository, onBack = {})
            }
        }

        composeRule.onNodeWithTag("learning_mastery_empty").assertIsDisplayed()
        composeRule.onNodeWithText("还没有学习记录").assertIsDisplayed()
        composeRule.onAllNodesWithText("重试").assertCountEquals(0)
        assertNoInternalLanguage()
    }

    @Test
    fun twoHundredPercentFontKeepsCompleteLabelsAndReadingOrder() {
        val repository = displayRepository()

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                SmartMistakeBookTheme {
                    LearningMasteryRoute(repository = repository, onBack = {})
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            repository.requests.isNotEmpty()
        }
        composeRule.onNodeWithTag("learning_mastery_subject:MATHEMATICS")
            .assertIsDisplayed()
        composeRule.onNodeWithText("薄弱知识")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodes(
            hasContentDescription(
                "函数单调性，需要再巩固，最近有波动",
                substring = false,
            ),
        ).assertCountEquals(1)
        assertNoInternalLanguage()
        saveAuditScreenshot("learning-mastery-font-200.png")
    }

    private fun assertNoInternalLanguage() {
        FORBIDDEN_STUDENT_TERMS.forEach { term ->
            composeRule.onAllNodesWithText(
                term,
                substring = true,
                useUnmergedTree = true,
            ).assertCountEquals(0)
            composeRule.onAllNodes(
                hasContentDescription(term, substring = true),
                useUnmergedTree = true,
            ).assertCountEquals(0)
        }
    }

    private fun saveAuditScreenshot(fileName: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = requireNotNull(context.getExternalFilesDir("audit"))
        val screenshot = File(directory, fileName)
        FileOutputStream(screenshot).use { output ->
            composeRule.onNodeWithTag("learning_mastery_screen")
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun saveDownloadScreenshot(fileName: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/$fileName")
            .close()
    }

    private fun displayRepository(): FakeLearningMasteryDisplayRepository =
        FakeLearningMasteryDisplayRepository(
            overviewState =
                LearningMasteryLoadState.Content(
                    LearningMasteryOverview(
                        revision = REVISION,
                        subjects =
                            listOf(
                                LearningMasterySubjectOverview(
                                    subject = LearningMasterySubject.MATHEMATICS,
                                    status = LearningMasteryStatus.NEEDS_REINFORCEMENT,
                                    trend = LearningMasteryTrend.RECENTLY_FLUCTUATING,
                                ),
                                LearningMasterySubjectOverview(
                                    subject = LearningMasterySubject.PHYSICS,
                                    status = LearningMasteryStatus.FAIRLY_STEADY,
                                    trend = LearningMasteryTrend.STEADY,
                                ),
                                LearningMasterySubjectOverview(
                                    subject = LearningMasterySubject.IDEOLOGY_AND_POLITICS,
                                    status = LearningMasteryStatus.NOT_YET_LEARNED,
                                    trend = LearningMasteryTrend.NO_CLEAR_CHANGE,
                                ),
                            ),
                    ),
                ),
            pages =
                mapOf(
                    LearningMasterySubject.MATHEMATICS to
                        listOf(
                            knowledge(
                                key = "math-monotonicity",
                                name = "函数单调性",
                                path = listOf("数学", "函数"),
                                status = LearningMasteryStatus.NEEDS_REINFORCEMENT,
                                trend = LearningMasteryTrend.RECENTLY_FLUCTUATING,
                            ),
                            knowledge(
                                key = "math-quadratic",
                                name = "二次函数",
                                path = listOf("数学", "函数"),
                                status = LearningMasteryStatus.GETTING_FAMILIAR,
                                trend = LearningMasteryTrend.IMPROVING,
                            ),
                        ),
                    LearningMasterySubject.PHYSICS to
                        listOf(
                            knowledge(
                                key = "physics-newton-two",
                                name = "牛顿第二定律",
                                path = listOf("物理", "相互作用与运动"),
                                status = LearningMasteryStatus.FAIRLY_STEADY,
                                trend = LearningMasteryTrend.STEADY,
                            ),
                    ),
                ),
            timelines =
                mapOf(
                    LearningMasterySubject.MATHEMATICS to
                        listOf(
                            LearningMasteryTimelineEntry(
                                day = 7L,
                                signal = LearningMasteryTimelineSignal.PROGRESS,
                                activity = LearningMasteryTimelineActivity.REGULAR,
                                attempts = 3,
                                knowledgePoints = 2,
                            ),
                            LearningMasteryTimelineEntry(
                                day = 6L,
                                signal = LearningMasteryTimelineSignal.NEEDS_ATTENTION,
                                activity = LearningMasteryTimelineActivity.LIGHT,
                                attempts = 1,
                                knowledgePoints = 1,
                            ),
                        ),
                ),
        )

    private fun knowledge(
        key: String,
        name: String,
        path: List<String>,
        status: LearningMasteryStatus,
        trend: LearningMasteryTrend,
    ): LearningMasteryKnowledgeItem =
        LearningMasteryKnowledgeItem(
            key = LearningMasteryKnowledgeKey.fromOpaque(key),
            displayName = name,
            displayPath = path,
            status = status,
            trend = trend,
        )

    private companion object {
        val REVISION = LearningMasteryProjectionRevision.fromOpaque("revision-ui-test")

        val FORBIDDEN_STUDENT_TERMS =
            listOf(
                "原子知识",
                "知识本体",
                "检索召回",
                "学习投影",
                "grounding",
                "taxonomy",
                "embedding",
                "置信度",
                "证据权重",
                "数据库",
                "数据表",
                "索引",
                "节点",
                "向量",
                "schema",
                "模型候选",
                "分类依据",
                "资料完整度",
                "历史轴",
                "衰减",
                "冲突投影",
            )
    }
}

private class FakeLearningMasteryDisplayRepository(
    private val overviewState: LearningMasteryLoadState<LearningMasteryOverview>,
    private val pages: Map<LearningMasterySubject, List<LearningMasteryKnowledgeItem>>,
    private val timelines: Map<LearningMasterySubject, List<LearningMasteryTimelineEntry>> =
        emptyMap(),
) : LearningMasteryDisplayRepository {
    val requests = mutableListOf<LearningMasteryPageRequest>()
    val timelineRequests = mutableListOf<LearningMasteryTimelineRequest>()

    override fun observeSubjectOverview():
        Flow<LearningMasteryLoadState<LearningMasteryOverview>> =
        flowOf(overviewState)

    override fun observeKnowledgePage(
        request: LearningMasteryPageRequest,
    ): Flow<LearningMasteryLoadState<LearningMasteryKnowledgePage>> {
        requests += request
        val items = pages[request.subject].orEmpty()
        return if (items.isEmpty()) {
            flowOf(LearningMasteryLoadState.Empty(request.revision))
        } else {
            flowOf(
                LearningMasteryLoadState.Content(
                    LearningMasteryKnowledgePage(
                        subject = request.subject,
                        revision = request.revision,
                        items = items,
                    ),
                ),
            )
        }
    }

    override fun observeSubjectTimeline(
        request: LearningMasteryTimelineRequest,
    ): Flow<LearningMasteryLoadState<LearningMasteryTimeline>> {
        timelineRequests += request
        val byDay = timelines[request.subject].orEmpty().associateBy { it.day }
        val entries =
            (0 until request.range.days).map { index ->
                byDay[(index + 1).toLong()]
                    ?: LearningMasteryTimelineEntry(
                        day = (index + 1).toLong(),
                        signal = LearningMasteryTimelineSignal.NO_ACTIVITY,
                        activity = LearningMasteryTimelineActivity.NONE,
                        attempts = 0,
                        knowledgePoints = 0,
                    )
            }
        return flowOf(
            LearningMasteryLoadState.Content(
                LearningMasteryTimeline(
                    revision = request.revision,
                    subject = request.subject,
                    entries = entries,
                ),
            ),
        )
    }
}
