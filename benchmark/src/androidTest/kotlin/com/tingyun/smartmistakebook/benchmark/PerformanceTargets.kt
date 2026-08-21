package com.tingyun.smartmistakebook.benchmark

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File

/**
 * Performance targets for the Smart Mistake Book app.
 *
 * This is NOT a pass/fail test class: targets alone prove nothing. Real
 * measurements must come from Macrobenchmark JSON/trace output produced on
 * physical devices. When no measurement file is present, results report
 * [MeasurementState.NOT_MEASURED] instead of fabricating a pass.
 */
object PerformanceTargets {
    /** Cold start target: 2 seconds. */
    const val COLD_START_TARGET_MS = 2000L

    /** Warm start target: 1 second. */
    const val WARM_START_TARGET_MS = 1000L

    /** Search P95 target: 350ms on real devices (10k items). */
    const val SEARCH_P95_TARGET_MS = 350L

    /** First screen render target: 500ms. */
    const val FIRST_SCREEN_TARGET_MS = 500L

    /** Facet query P95 target: 200ms. */
    const val FACET_P95_TARGET_MS = 200L

    /** Tutor init target: 600ms. */
    const val TUTOR_INIT_TARGET_MS = 600L

    /** Capture processing target: 1.2 seconds. */
    const val CAPTURE_TARGET_MS = 1200L

    /** Review plan generation target: 500ms. */
    const val REVIEW_PLAN_TARGET_MS = 500L

    /** Peak memory target: 200MB. */
    const val MEMORY_TARGET_MB = 200L

    /** Frame drop target: 5%. */
    const val FRAME_DROP_TARGET_PERCENT = 5.0

    /**
     * Load the benchmark result file from the instrumented storage dir.
     * Returns null when no measurement has been recorded.
     */
    fun loadBenchmarkResults(): Map<String, JSONObject>? {
        val context = InstrumentationRegistry.getInstrumentation().context
        val dir = File(context.filesDir, "benchmark-results")
        if (!dir.isDirectory) return null
        val files = dir.listFiles { f -> f.extension == "json" } ?: return null
        if (files.isEmpty()) return null
        return files.associate { it.nameWithoutExtension to JSONObject(it.readText()) }
    }
}

/**
 * A performance measurement that may not exist yet.
 */
sealed interface PerformanceMeasurement {
    /** Measured value in ms (or the unit of the metric). */
    data class Measured(val valueMs: Long) : PerformanceMeasurement {
        val meetsTarget: Boolean get() = valueMs < targetFor()
        private fun targetFor(): Long = 0 // overridden by caller comparison
    }

    /** No benchmark output recorded; must not be treated as a pass. */
    data object NotMeasured : PerformanceMeasurement
}

/**
 * Reads real benchmark values from Macrobenchmark JSON output. Missing files
 * yield [PerformanceMeasurement.NotMeasured], never a fabricated pass.
 */
object PerformanceMeasurementReader {
    /**
     * Read a single metric from the benchmark results, e.g. "coldStartMs".
     */
    fun readMetric(metricName: String): PerformanceMeasurement {
        val results = PerformanceTargets.loadBenchmarkResults() ?: return PerformanceMeasurement.NotMeasured
        val value = results.values.mapNotNull { json ->
            json.optLong(metricName).takeIf { it > 0 }
        }.firstOrNull()
        return if (value != null) {
            PerformanceMeasurement.Measured(value)
        } else {
            PerformanceMeasurement.NotMeasured
        }
    }
}
