package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A bounded physics description that is evaluated and drawn locally. Model output never controls
 * pixels, colors, timing loops, executable code, or interaction behavior.
 */
@Serializable
sealed interface TutorMotionScene : TutorVisualScene {
    val durationSeconds: Double
}

@Serializable
@SerialName("linear_motion")
data class TutorLinearMotionScene(
    override val sceneId: String,
    override val title: String,
    override val durationSeconds: Double,
    val initialPositionMeters: Double,
    val initialVelocityMetersPerSecond: Double,
    val accelerationMetersPerSecondSquared: Double,
    override val schemaVersion: Int = TutorVisualScene.SCHEMA_VERSION,
) : TutorMotionScene {
    init {
        requireTutorMotionHeader(sceneId, title, schemaVersion, durationSeconds)
        initialPositionMeters.requireFiniteMotionValue("Initial position", -10_000.0..10_000.0)
        initialVelocityMetersPerSecond.requireFiniteMotionValue(
            "Initial velocity",
            -1_000.0..1_000.0,
        )
        accelerationMetersPerSecondSquared.requireFiniteMotionValue(
            "Acceleration",
            -100.0..100.0,
        )
        require(
            kotlin.math.abs(initialVelocityMetersPerSecond) >= 0.001 ||
                kotlin.math.abs(accelerationMetersPerSecondSquared) >= 0.001,
        ) { "Linear motion must change position during playback" }
    }
}

@Serializable
@SerialName("projectile_motion")
data class TutorProjectileMotionScene(
    override val sceneId: String,
    override val title: String,
    override val durationSeconds: Double,
    val initialHeightMeters: Double,
    val horizontalVelocityMetersPerSecond: Double,
    val verticalVelocityMetersPerSecond: Double,
    val gravityMetersPerSecondSquared: Double,
    override val schemaVersion: Int = TutorVisualScene.SCHEMA_VERSION,
) : TutorMotionScene {
    init {
        requireTutorMotionHeader(sceneId, title, schemaVersion, durationSeconds)
        initialHeightMeters.requireFiniteMotionValue("Initial height", 0.0..10_000.0)
        horizontalVelocityMetersPerSecond.requireFiniteMotionValue(
            "Horizontal velocity",
            -1_000.0..1_000.0,
        )
        verticalVelocityMetersPerSecond.requireFiniteMotionValue(
            "Vertical velocity",
            -1_000.0..1_000.0,
        )
        gravityMetersPerSecondSquared.requireFiniteMotionValue("Gravity", 0.1..50.0)
        require(initialHeightMeters > 0.0 || verticalVelocityMetersPerSecond > 0.0) {
            "Projectile motion must have positive flight time"
        }
    }
}

@Serializable
@SerialName("circular_motion")
data class TutorCircularMotionScene(
    override val sceneId: String,
    override val title: String,
    override val durationSeconds: Double,
    val radiusMeters: Double,
    val angularVelocityRadiansPerSecond: Double,
    val initialAngleRadians: Double,
    override val schemaVersion: Int = TutorVisualScene.SCHEMA_VERSION,
) : TutorMotionScene {
    init {
        requireTutorMotionHeader(sceneId, title, schemaVersion, durationSeconds)
        radiusMeters.requireFiniteMotionValue("Radius", 0.01..10_000.0)
        angularVelocityRadiansPerSecond.requireFiniteMotionValue(
            "Angular velocity",
            -100.0..100.0,
        )
        require(kotlin.math.abs(angularVelocityRadiansPerSecond) >= 0.001) {
            "Angular velocity must describe visible motion"
        }
        initialAngleRadians.requireFiniteMotionValue("Initial angle", (-4.0 * PI)..(4.0 * PI))
    }
}

@Serializable
@SerialName("oscillation_motion")
data class TutorOscillationMotionScene(
    override val sceneId: String,
    override val title: String,
    override val durationSeconds: Double,
    val equilibriumPositionMeters: Double,
    val amplitudeMeters: Double,
    val periodSeconds: Double,
    val initialPhaseRadians: Double,
    override val schemaVersion: Int = TutorVisualScene.SCHEMA_VERSION,
) : TutorMotionScene {
    init {
        requireTutorMotionHeader(sceneId, title, schemaVersion, durationSeconds)
        equilibriumPositionMeters.requireFiniteMotionValue(
            "Equilibrium position",
            -10_000.0..10_000.0,
        )
        amplitudeMeters.requireFiniteMotionValue("Amplitude", 0.01..10_000.0)
        periodSeconds.requireFiniteMotionValue("Period", 0.05..60.0)
        initialPhaseRadians.requireFiniteMotionValue("Initial phase", (-4.0 * PI)..(4.0 * PI))
    }
}

data class TutorMotionState(
    val timeSeconds: Double,
    val xMeters: Double,
    val yMeters: Double,
    val velocityXMetersPerSecond: Double,
    val velocityYMetersPerSecond: Double,
    val accelerationXMetersPerSecondSquared: Double,
    val accelerationYMetersPerSecondSquared: Double,
)

object TutorMotionEvaluator {
    fun playbackDurationSeconds(scene: TutorMotionScene): Double = when (scene) {
        is TutorProjectileMotionScene -> min(scene.durationSeconds, scene.impactTimeSeconds())
        else -> scene.durationSeconds
    }

    fun evaluate(scene: TutorMotionScene, requestedTimeSeconds: Double): TutorMotionState {
        val time = requestedTimeSeconds
            .takeIf(Double::isFinite)
            ?.coerceIn(0.0, playbackDurationSeconds(scene))
            ?: 0.0
        return when (scene) {
            is TutorLinearMotionScene -> scene.evaluate(time)
            is TutorProjectileMotionScene -> scene.evaluate(time)
            is TutorCircularMotionScene -> scene.evaluate(time)
            is TutorOscillationMotionScene -> scene.evaluate(time)
        }
    }

    private fun TutorLinearMotionScene.evaluate(time: Double): TutorMotionState =
        TutorMotionState(
            timeSeconds = time,
            xMeters = initialPositionMeters +
                initialVelocityMetersPerSecond * time +
                0.5 * accelerationMetersPerSecondSquared * time * time,
            yMeters = 0.0,
            velocityXMetersPerSecond =
                initialVelocityMetersPerSecond + accelerationMetersPerSecondSquared * time,
            velocityYMetersPerSecond = 0.0,
            accelerationXMetersPerSecondSquared = accelerationMetersPerSecondSquared,
            accelerationYMetersPerSecondSquared = 0.0,
        )

    private fun TutorProjectileMotionScene.evaluate(time: Double): TutorMotionState =
        TutorMotionState(
            timeSeconds = time,
            xMeters = horizontalVelocityMetersPerSecond * time,
            yMeters = (
                initialHeightMeters +
                    verticalVelocityMetersPerSecond * time -
                    0.5 * gravityMetersPerSecondSquared * time * time
                ).coerceAtLeast(0.0),
            velocityXMetersPerSecond = horizontalVelocityMetersPerSecond,
            velocityYMetersPerSecond =
                verticalVelocityMetersPerSecond - gravityMetersPerSecondSquared * time,
            accelerationXMetersPerSecondSquared = 0.0,
            accelerationYMetersPerSecondSquared = -gravityMetersPerSecondSquared,
        )

    private fun TutorCircularMotionScene.evaluate(time: Double): TutorMotionState {
        val angle = initialAngleRadians + angularVelocityRadiansPerSecond * time
        val x = radiusMeters * cos(angle)
        val y = radiusMeters * sin(angle)
        val angularVelocitySquared =
            angularVelocityRadiansPerSecond * angularVelocityRadiansPerSecond
        return TutorMotionState(
            timeSeconds = time,
            xMeters = x,
            yMeters = y,
            velocityXMetersPerSecond = -radiusMeters * angularVelocityRadiansPerSecond * sin(angle),
            velocityYMetersPerSecond = radiusMeters * angularVelocityRadiansPerSecond * cos(angle),
            accelerationXMetersPerSecondSquared = -angularVelocitySquared * x,
            accelerationYMetersPerSecondSquared = -angularVelocitySquared * y,
        )
    }

    private fun TutorOscillationMotionScene.evaluate(time: Double): TutorMotionState {
        val angularVelocity = 2.0 * PI / periodSeconds
        val phase = angularVelocity * time + initialPhaseRadians
        val displacement = amplitudeMeters * cos(phase)
        return TutorMotionState(
            timeSeconds = time,
            xMeters = equilibriumPositionMeters + displacement,
            yMeters = 0.0,
            velocityXMetersPerSecond = -amplitudeMeters * angularVelocity * sin(phase),
            velocityYMetersPerSecond = 0.0,
            accelerationXMetersPerSecondSquared =
                -amplitudeMeters * angularVelocity * angularVelocity * cos(phase),
            accelerationYMetersPerSecondSquared = 0.0,
        )
    }

    private fun TutorProjectileMotionScene.impactTimeSeconds(): Double =
        (
            verticalVelocityMetersPerSecond +
                sqrt(
                    verticalVelocityMetersPerSecond * verticalVelocityMetersPerSecond +
                        2.0 * gravityMetersPerSecondSquared * initialHeightMeters,
                )
            ) / gravityMetersPerSecondSquared
}

private fun requireTutorMotionHeader(
    sceneId: String,
    title: String,
    schemaVersion: Int,
    durationSeconds: Double,
) {
    requireTutorSceneHeader(sceneId, title, schemaVersion)
    durationSeconds.requireFiniteMotionValue("Duration", 0.5..30.0)
}

private fun Double.requireFiniteMotionValue(label: String, range: ClosedFloatingPointRange<Double>) {
    require(isFinite()) { "$label must be finite" }
    require(this in range) { "$label is outside the supported range" }
}
