package com.tingyun.smartmistakebook

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.data.study.StudyFixtureRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootVisualCaptureInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun seedCuratedFixture() {
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
    fun captureAllFourCurrentRootDestinations() {
        waitForTag("root_review")
        capture("root-review-current.png")

        navigateAndCapture("nav_tutor", "root_tutor", "root-tutor-current.png")
        navigateAndCapture("nav_library", "root_library", "root-library-current.png")
        navigateAndCapture("nav_profile", "root_profile", "root-profile-current.png")
    }

    private fun navigateAndCapture(navigationTag: String, rootTag: String, displayName: String) {
        composeRule.onNodeWithTag(navigationTag).performClick()
        waitForTag(rootTag)
        capture(displayName)
    }

    private fun waitForTag(tag: String) {
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(displayName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
        resolver.delete(
            collection,
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf(displayName, SCREENSHOT_DIRECTORY),
        )
        val uri = checkNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, SCREENSHOT_DIRECTORY)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val encoded = checkNotNull(resolver.openOutputStream(uri)).use { stream ->
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        screenshot.recycle()
        check(encoded) { "Current root screenshot could not be encoded." }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
    }

    companion object {
        private const val SCREENSHOT_DIRECTORY = "Pictures/SmartMistakeBookQA/"
    }
}
