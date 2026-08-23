package com.tingyun.smartmistakebook.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmarks for the key user paths listed in acceptance audit §16.2.
 *
 * All page lookups use stable Compose `testTag`s (exposed to UiAutomator via
 * `testTagsAsResourceId` on the app root). Numbers produced here are only
 * meaningful on physical devices; see PerformanceTargets for the
 * NOT_MEASURED policy that keeps hardcoded values out of gates.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupCold() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
        ) {
            pressHome()
            startActivityAndWait()
        }
    }

    /** Audit §16.2: cold start until the home destination is interactive. */
    @Test
    fun startupColdToHomeInteractive() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
        ) {
            pressHome()
            startActivityAndWait()
            waitForTag("root_review")
        }
    }

    /** Audit §16.2: open the mistake library, enter search, scroll the list. */
    @Test
    fun libraryBrowseAndSearchEntry() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            iterations = ITERATIONS,
            startupMode = StartupMode.WARM,
        ) {
            pressHome()
            startActivityAndWait()
            clickTag("nav_library")
            waitForTag("library_root")
            // The search field is only present with data; tolerate empty installs.
            clickTag("library_search_field")
            repeat(SCROLL_PASSES) { scrollUp() }
        }
    }

    /** Audit §16.2: enter a review session from the review tab. */
    @Test
    fun reviewSessionEntry() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            iterations = ITERATIONS,
            startupMode = StartupMode.WARM,
        ) {
            pressHome()
            startActivityAndWait()
            clickTag("nav_review")
            waitForTag("review_root")
            // Fresh installs show the "no learning record" panel instead.
            clickTag("review_start_button")
            waitForTag("review_session_gate")
        }
    }

    /** Audit §16.2: capture import flow, from the tutor tab to the camera entry. */
    @Test
    fun captureImportEntry() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            iterations = ITERATIONS,
            startupMode = StartupMode.WARM,
        ) {
            pressHome()
            startActivityAndWait()
            clickTag("nav_tutor")
            waitForTag("tutor_screen")
            clickTag("tutor_capture_button")
            waitForTag("capture_screen")
            device.pressBack()
            device.waitForIdle()
        }
    }

    /** Audit §16.2: open the backup page (profile → storage & backup). */
    @Test
    fun backupPageOpen() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            iterations = ITERATIONS,
            startupMode = StartupMode.WARM,
        ) {
            pressHome()
            startActivityAndWait()
            clickTag("nav_profile")
            waitForTag("profile_screen")
            clickTag("profile_storage_setting")
            waitForTag("storage_screen")
            device.pressBack()
            device.waitForIdle()
        }
    }

    private fun MacrobenchmarkScope.waitForTag(
        tag: String,
        timeoutMs: Long = NAVIGATION_WAIT_MS,
    ): Boolean = device.wait(Until.hasObject(By.res(packageName, tag)), timeoutMs)

    private fun MacrobenchmarkScope.clickTag(tag: String) {
        if (!waitForTag(tag)) return
        device.findObject(By.res(packageName, tag))?.click()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.scrollUp() {
        val width = device.displayWidth
        val height = device.displayHeight
        device.swipe(width / 2, height * 3 / 4, width / 2, height / 4, 20)
        device.waitForIdle()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.tingyun.smartmistakebook.localfirst"
        const val ITERATIONS = 5
        const val SCROLL_PASSES = 3
        const val NAVIGATION_WAIT_MS = 5_000L
    }
}
