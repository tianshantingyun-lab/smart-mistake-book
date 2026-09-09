package com.tingyun.smartmistakebook

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.data.study.StudyFixtureRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootExperienceInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun seedCuratedFixtureExplicitlyForUiScenarios() {
        runBlocking {
            val application = composeRule.activity.application as SmartMistakeBookApplication
            // Debug-only fixture injection through the audit seam (PR-05): the
            // registry holds curated content in debug builds and stays empty in
            // release, so this seed fails closed outside debug/test builds.
            val bundle = checkNotNull(
                StudyFixtureRegistry.source.bundle(includeTutorMistake = true),
            ) { "Curated fixture content is not available in this build" }
            application.studyDatabase.seedFixture(bundle)
            application.studyRepository.refresh()
        }
    }

    @Test
    fun fourBottomDestinationsExposeTheirOwnRootContent() {
        waitForTag("root_review")

        navigateAndWait("nav_tutor", "root_tutor")
        navigateAndWait("nav_library", "root_library")
        navigateAndWait("nav_profile", "root_profile")
        navigateAndWait("nav_review", "root_review")
    }

    @Test
    fun reviewDoesNotExposeAnyCaptureShortcut() {
        waitForTag("root_review")

        composeRule.onAllNodesWithTag("review_capture_shortcut").assertCountEquals(0)
        composeRule.onAllNodesWithTag("review_capture_button").assertCountEquals(0)
    }

    @Test
    fun tutorStartsFromAUserProvidedQuestionWithoutBuiltInCalibration() {
        navigateAndWait("nav_tutor", "root_tutor")
        waitForTag("tutor_empty_state")
        composeRule.onAllNodesWithText("只处理你现在提出的事").assertCountEquals(0)
        composeRule.onAllNodesWithTag("tutor_choice_a").assertCountEquals(0)
        composeRule.onAllNodesWithTag("tutor_reveal_answer").assertCountEquals(0)

        composeRule.onNodeWithTag("tutor_capture_shortcut").performClick()
        waitForTag("capture_screen")
        navigateBackAndWait("root_tutor")

        composeRule.onNodeWithTag("tutor_choose_existing_button").performClick()
        waitForTag("root_library")
    }

    @Test
    fun settingsPagesShowOnlyCapabilitiesThatExistNow() {
        navigateAndWait("nav_profile", "root_profile")
        composeRule.onNodeWithTag("profile_storage_setting").assertTextContains("存储与导出")

        composeRule.onNodeWithTag("profile_privacy_setting").performClick()
        waitForText("数据与隐私")
        waitForText("智能服务")
        // The privacy copy differs by flavor's NetworkMode; assert the one
        // this build actually shows instead of the localFirst wording.
        // 2026-09-09: the localFirst copy now states the global-consent model
        // (发起即交给已配置模型，不再逐次询问), so the expectation follows it.
        val localFirstCopy = "拍照、讲题或整理是你主动发起时才会进行"
        val strictOfflineCopy = "当前版本不使用联网智能服务"
        val expectedPrivacyCopy = if (
            com.tingyun.smartmistakebook.BuildConfig.FLAVOR == "strictOffline"
        ) {
            strictOfflineCopy
        } else {
            localFirstCopy
        }
        composeRule.onAllNodesWithText(
            expectedPrivacyCopy,
            substring = true,
        ).assertCountEquals(1)
        composeRule.onAllNodesWithText("尚未开放").assertCountEquals(0)
        composeRule.onAllNodesWithText("云同步", substring = true).assertCountEquals(0)
        navigateBackAndWait("root_profile")

        composeRule.onNodeWithTag("profile_storage_setting").performClick()
        waitForText("存储、备份与导出")
        composeRule.onAllNodesWithText("加密备份", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("暂未开放", substring = true).assertCountEquals(0)
    }

    @Test
    fun librarySummariesExposeReadableMathInsteadOfFormulaSource() {
        navigateAndWait("nav_library", "root_library")

        composeRule.onAllNodesWithText("$", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("\\frac", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("\\right", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("f(x)=x³-3x+1", substring = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("⇌", substring = true).assertCountEquals(1)
    }

    @Test
    fun libraryItemOpensRepositoryBackedDetailAndReturnsToCatalog() {
        navigateAndWait("nav_library", "root_library")

        composeRule.onNodeWithTag("library_item_entry:m1:closed-interval-extrema")
            .performScrollTo()
            .performClick()
        waitForTag("mistake_detail_ready")
        waitForText("当前题面")
        waitForText("智能整理")

        composeRule.onNodeWithTag("mistake_detail_back").performClick()
        waitForTag("root_library")
    }

    @Test
    fun savedMistakeTutorKeepsStableBottomNavigationWithoutLeavingTheQuestion() {
        navigateAndWait("nav_library", "root_library")
        composeRule.onNodeWithTag("library_item_entry:m1:closed-interval-extrema")
            .performScrollTo()
            .performClick()
        waitForTag("mistake_detail_ready")

        composeRule.onNodeWithTag("mistake_detail_start_tutor")
            .performScrollTo()
            .performClick()
        waitForTag("saved_mistake_tutor_screen")
        listOf("nav_review", "nav_tutor", "nav_library", "nav_profile").forEach(::waitForTag)
        captureFullTutorShell()

        composeRule.onNodeWithTag("nav_tutor").performClick()
        waitForTag("saved_mistake_tutor_screen")

        composeRule.onNodeWithTag("captured_tutor_back").performClick()
        waitForTag("mistake_detail_ready")
        waitForText("当前题面")
    }

    @Test
    fun savedCredentialsImmediatelyUpdateTheProfileWithoutClaimingAConnectionProbe() {
        navigateAndWait("nav_profile", "root_profile")
        composeRule.onNodeWithTag("profile_capability_setting").performClick()
        waitForTag("capability_screen")

        val isLocalFirst = composeRule.activity.packageName.endsWith(".localfirst")
        if (!isLocalFirst) {
            composeRule.onNodeWithTag("capability_strict_offline_notice")
                .performScrollTo()
                .assertTextContains("不显示服务商", substring = true)
            composeRule.onAllNodesWithTag("capability_api_key").assertCountEquals(0)
            return
        }

        composeRule.onAllNodesWithTag("capability_strict_offline_notice").assertCountEquals(0)
        composeRule.onNodeWithTag("capability_provider").performTextInput("OpenAI-compatible")
        composeRule.onNodeWithTag("capability_model_id").performTextInput("test-model")
        composeRule.onNodeWithTag("capability_base_url").performTextInput("https://example.com")
        composeRule.onNodeWithTag("capability_api_key").performTextInput("test-secret-not-real")
        composeRule.onNodeWithTag("capability_save").performScrollTo().performClick()
        waitForText("配置已安全保存在本机")

        navigateBackAndWait("root_profile")
        waitForText("模型配置已保存")
        composeRule.onAllNodesWithText("请完成能力测试", substring = true)
            .assertCountEquals(1)

        composeRule.onNodeWithTag("profile_capability_setting").performClick()
        waitForTag("capability_screen")
        composeRule.onNodeWithTag("capability_clear").performScrollTo().performClick()
        waitForText("本机配置与密钥已清除。")
        navigateBackAndWait("root_profile")
        waitForText("配置大模型 API")
    }

    @Test
    fun profileOpensStudentLearningMasteryWithoutKnowledgeBaseInternals() {
        navigateAndWait("nav_profile", "root_profile")
        composeRule.onNodeWithTag("profile_learning_mastery")
            .performScrollTo()
            .performClick()

        waitForTag("learning_mastery_screen")
        waitForText("学习掌握")
        listOf(
            "原子知识",
            "知识本体",
            "检索召回",
            "学习投影",
            "grounding",
            "taxonomy",
            "embedding",
            "置信度",
            "分类依据",
            "审核",
            "批准",
        ).forEach { internalTerm ->
            composeRule.onAllNodesWithText(internalTerm, substring = true).assertCountEquals(0)
        }

        navigateBackAndWait("root_profile")
    }

    @Test
    fun submittedReviewItemIsAlreadyAdvancedWhenUserLeavesBeforeNext() {
        val application = composeRule.activity.application as SmartMistakeBookApplication
        assumeTrue(
            "The strict-offline diagnostic flavor intentionally has no interactive teaching artifact.",
            application.capabilities.tutorTeachingEnabled,
        )
        waitForTag("root_review")
        composeRule.onNodeWithTag("review_start_button").performClick()
        waitForTag("review_session_root")
        waitForTag("review_choice_A")

        composeRule.onNodeWithTag("review_reveal_answer").performClick()
        waitForTag("review_explanation")
        navigateBackAndWait("root_review")
        waitForText("继续今日复习")
        composeRule.onNodeWithTag("review_start_button").performClick()
        waitForTag("review_session_root")
        waitForTag("review_choice_A")
        val beforeSubmit = application.studyRepository.snapshot.value.review
        val expectedOrdinal = beforeSubmit.currentOrdinal + 1
        val queueSize = beforeSubmit.scheduledCount
        assumeTrue(
            "The retained review session must have another item for the re-entry assertion.",
            expectedOrdinal < queueSize,
        )

        composeRule.onNodeWithTag("review_choice_A").performClick()
        composeRule.onNodeWithTag("review_submit_answer").performClick()
        waitForTag("review_next_item")
        composeRule.waitUntil(timeoutMillis = 20_000) {
            application.studyRepository.snapshot.value.review.currentOrdinal == expectedOrdinal
        }

        navigateBackAndWait("root_review")
        waitForText("继续今日复习")
        composeRule.onNodeWithTag("review_start_button").performClick()
        waitForTag("review_session_root")
        waitForText("第 ${expectedOrdinal + 1} / $queueSize 题")
        navigateBackAndWait("root_review")
    }

    private fun navigateAndWait(navigationTag: String, rootTag: String) {
        composeRule.onNodeWithTag(navigationTag).performClick()
        waitForTag(rootTag)
    }

    private fun captureFullTutorShell() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
        val uri = checkNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        "tutor-saved-shell-current-20260722.png",
                    )
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "Pictures/SmartMistakeBookQA/",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val encoded = checkNotNull(resolver.openOutputStream(uri)).use { stream ->
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        screenshot.recycle()
        check(encoded) { "Tutor shell screenshot could not be encoded." }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
    }

    private fun waitForTag(tag: String) {
        awaitComposeIdle()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) {
        awaitComposeIdle()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitComposeIdle() {
        composeRule.waitForIdle()
    }

    private fun navigateBackAndWait(rootTag: String) {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        waitForTag(rootTag)
        waitForTag("nav_review")
    }
}
