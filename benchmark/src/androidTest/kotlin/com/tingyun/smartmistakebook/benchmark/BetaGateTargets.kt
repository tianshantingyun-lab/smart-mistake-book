package com.tingyun.smartmistakebook.benchmark

import org.json.JSONObject
import java.io.File

/**
 * Closed Beta gate targets for the Smart Mistake Book app.
 *
 * This is NOT a pass/fail test class: telemetry values alone prove nothing.
 * Every metric must come from a real telemetry source (crash/ANR reports,
 * provider logs, analytics, recovery/migration drill records). When no data
 * exists, results report [TelemetryState.NOT_MEASURED] instead of a fake pass.
 */
object BetaGateTargets {
    /** Crash rate target: 0.5% of sessions. */
    const val CRASH_RATE_TARGET_PERCENT = 0.5

    /** ANR rate target: 0.1% of sessions. */
    const val ANR_RATE_TARGET_PERCENT = 0.1

    /** Provider failure rate target: 5% of requests. */
    const val PROVIDER_FAILURE_RATE_TARGET_PERCENT = 5.0

    /** Provider timeout rate target: 2% of requests. */
    const val PROVIDER_TIMEOUT_RATE_TARGET_PERCENT = 2.0

    /** User task completion rate target: 80% of initiated tasks. */
    const val TASK_COMPLETION_RATE_TARGET_PERCENT = 80.0

    /** Onboarding completion rate target: 60%. */
    const val ONBOARDING_RATE_TARGET_PERCENT = 60.0

    /** User satisfaction target: 4.0 out of 5. */
    const val SATISFACTION_TARGET = 4.0

    /** Backup size target: 100MB. */
    const val BACKUP_SIZE_TARGET_MB = 100L
}

/**
 * A telemetry reading that may not exist yet.
 */
sealed interface TelemetryState {
    /** Real value read from a telemetry source. */
    data class Measured(val value: Double) : TelemetryState

    /** No telemetry recorded; must not be treated as a pass. */
    data object NotMeasured : TelemetryState
}

/**
 * Reads telemetry from JSON files under the instrumented storage dir.
 * Missing files yield [TelemetryState.NotMeasured], never a fabricated pass.
 */
object TelemetryReader {
    private const val TELEMETRY_DIR = "beta-telemetry"

    /**
     * Read a single metric, e.g. "crashRatePercent". Returns NotMeasured
     * when the file or key is absent.
     */
    fun readMetric(metricName: String): TelemetryState {
        val json = loadTelemetry() ?: return TelemetryState.NotMeasured
        val value = json.optDouble(metricName, Double.NaN)
        return if (value.isNaN()) {
            TelemetryState.NotMeasured
        } else {
            TelemetryState.Measured(value)
        }
    }

    /**
     * Read a recovery-drill record. Returns NotMeasured when no drill has run.
     */
    fun readRecoveryDrill(): TelemetryState {
        val json = loadTelemetry() ?: return TelemetryState.NotMeasured
        val succeeded = json.optBoolean("recoveryDrillSucceeded", false)
        val ran = json.optBoolean("recoveryDrillRan", false)
        return if (!ran) {
            TelemetryState.NotMeasured
        } else {
            TelemetryState.Measured(if (succeeded) 1.0 else 0.0)
        }
    }

    /**
     * Read the latest migration drill result. Returns NotMeasured when absent.
     */
    fun readMigrationDrill(): TelemetryState {
        val json = loadTelemetry() ?: return TelemetryState.NotMeasured
        val allPassed = json.optBoolean("migrationDrillAllPassed", false)
        val ran = json.optBoolean("migrationDrillRan", false)
        return if (!ran) {
            TelemetryState.NotMeasured
        } else {
            TelemetryState.Measured(if (allPassed) 1.0 else 0.0)
        }
    }

    private fun loadTelemetry(): JSONObject? {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
        val file = File(context.filesDir, "$TELEMETRY_DIR/telemetry.json")
        if (!file.isFile) return null
        return try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            null
        }
    }
}
