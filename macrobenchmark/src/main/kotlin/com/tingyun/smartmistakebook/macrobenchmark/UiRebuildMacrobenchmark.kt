package com.tingyun.smartmistakebook.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
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

@LargeTest
@RunWith(AndroidJUnit4::class)
class UiRebuildMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldLaunchIntoTutor() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
        },
    ) {
        startActivityAndWait()
        awaitResource("root_tutor")
    }

    @Test
    fun navigateTutorReviewLibraryProfileTutor() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            awaitResource("root_tutor")
        },
    ) {
        clickAndAwait("nav_review", "root_review")
        clickAndAwait("nav_library", "root_library")
        clickAndAwait("nav_profile", "root_profile")
        clickAndAwait("nav_tutor", "root_tutor")
    }

    @Test
    fun streamMarkdownThenInsertDynamicGui() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait(
                Intent(Intent.ACTION_MAIN)
                    .setComponent(ComponentName(TARGET_PACKAGE, FIXTURE_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            awaitResource("benchmark_stream_start")
        },
    ) {
        clickAndAwait("benchmark_stream_start", "benchmark_dynamic_gui_ready")
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.clickAndAwait(
        clickableResource: String,
        expectedResource: String,
    ) {
        val clickable = device.wait(
            Until.findObject(By.res(clickableResource)),
            UI_TIMEOUT_MILLIS,
        ) ?: error("Missing resource-id tag: $clickableResource")
        clickable.click()
        awaitResource(expectedResource)
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.awaitResource(resource: String) {
        check(
            device.wait(
                Until.hasObject(By.res(resource)),
                UI_TIMEOUT_MILLIS,
            ),
        ) {
            "Timed out waiting for resource-id tag: $resource"
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.tingyun.smartmistakebook.offline"
        const val FIXTURE_ACTIVITY =
            "com.tingyun.smartmistakebook.benchmark.DynamicGuiBenchmarkActivity"
        const val ITERATIONS = 5
        const val UI_TIMEOUT_MILLIS = 10_000L
    }
}
