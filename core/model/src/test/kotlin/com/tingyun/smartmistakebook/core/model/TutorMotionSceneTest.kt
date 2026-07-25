package com.tingyun.smartmistakebook.core.model

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TutorMotionSceneTest {
    @Test
    fun linearMotionUsesTheConstantAccelerationEquations() {
        val state = TutorMotionEvaluator.evaluate(
            TutorLinearMotionScene(
                sceneId = "motion-linear",
                title = "匀加速直线运动",
                durationSeconds = 5.0,
                initialPositionMeters = 1.0,
                initialVelocityMetersPerSecond = 2.0,
                accelerationMetersPerSecondSquared = 3.0,
            ),
            requestedTimeSeconds = 2.0,
        )

        assertEquals(11.0, state.xMeters, TOLERANCE)
        assertEquals(8.0, state.velocityXMetersPerSecond, TOLERANCE)
        assertEquals(3.0, state.accelerationXMetersPerSecondSquared, TOLERANCE)
    }

    @Test
    fun projectileStopsAtTheFirstGroundImpact() {
        val scene = TutorProjectileMotionScene(
            sceneId = "motion-projectile",
            title = "平抛运动",
            durationSeconds = 10.0,
            initialHeightMeters = 5.0,
            horizontalVelocityMetersPerSecond = 4.0,
            verticalVelocityMetersPerSecond = 0.0,
            gravityMetersPerSecondSquared = 10.0,
        )

        assertEquals(1.0, TutorMotionEvaluator.playbackDurationSeconds(scene), TOLERANCE)
        val impact = TutorMotionEvaluator.evaluate(scene, requestedTimeSeconds = 8.0)
        assertEquals(1.0, impact.timeSeconds, TOLERANCE)
        assertEquals(4.0, impact.xMeters, TOLERANCE)
        assertEquals(0.0, impact.yMeters, TOLERANCE)
    }

    @Test
    fun circularMotionKeepsTheRadiusAndTangentVelocity() {
        val scene = TutorCircularMotionScene(
            sceneId = "motion-circle",
            title = "匀速圆周运动",
            durationSeconds = 4.0,
            radiusMeters = 2.0,
            angularVelocityRadiansPerSecond = PI / 2.0,
            initialAngleRadians = 0.0,
        )

        val state = TutorMotionEvaluator.evaluate(scene, requestedTimeSeconds = 1.0)

        assertEquals(0.0, state.xMeters, TOLERANCE)
        assertEquals(2.0, state.yMeters, TOLERANCE)
        assertEquals(-PI, state.velocityXMetersPerSecond, TOLERANCE)
        assertEquals(0.0, state.velocityYMetersPerSecond, TOLERANCE)
    }

    @Test
    fun oscillationUsesPeriodAmplitudeAndPhase() {
        val scene = TutorOscillationMotionScene(
            sceneId = "motion-oscillation",
            title = "简谐运动",
            durationSeconds = 4.0,
            equilibriumPositionMeters = 3.0,
            amplitudeMeters = 2.0,
            periodSeconds = 4.0,
            initialPhaseRadians = 0.0,
        )

        assertEquals(
            3.0,
            TutorMotionEvaluator.evaluate(scene, requestedTimeSeconds = 1.0).xMeters,
            TOLERANCE,
        )
        assertEquals(
            1.0,
            TutorMotionEvaluator.evaluate(scene, requestedTimeSeconds = 2.0).xMeters,
            TOLERANCE,
        )
    }

    @Test
    fun contractRejectsNonFiniteAndOutOfRangeParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            TutorLinearMotionScene(
                sceneId = "motion-invalid",
                title = "非法运动",
                durationSeconds = Double.NaN,
                initialPositionMeters = 0.0,
                initialVelocityMetersPerSecond = 0.0,
                accelerationMetersPerSecondSquared = 0.0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TutorCircularMotionScene(
                sceneId = "motion-invalid",
                title = "无运动",
                durationSeconds = 2.0,
                radiusMeters = 1.0,
                angularVelocityRadiansPerSecond = 0.0,
                initialAngleRadians = 0.0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TutorProjectileMotionScene(
                sceneId = "motion-invalid",
                title = "没有飞行时间",
                durationSeconds = 2.0,
                initialHeightMeters = 0.0,
                horizontalVelocityMetersPerSecond = 2.0,
                verticalVelocityMetersPerSecond = 0.0,
                gravityMetersPerSecondSquared = 10.0,
            )
        }
    }

    companion object {
        private const val TOLERANCE = 1e-6
    }
}
