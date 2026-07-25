package com.tingyun.smartmistakebook.feature.tutor

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.TutorCircularMotionScene
import com.tingyun.smartmistakebook.core.model.TutorLinearMotionScene
import com.tingyun.smartmistakebook.core.model.TutorMotionScene
import com.tingyun.smartmistakebook.core.model.TutorOscillationMotionScene
import com.tingyun.smartmistakebook.core.model.TutorProjectileMotionScene
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import com.tingyun.smartmistakebook.core.ui.TutorVisualSceneRenderer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorMotionSceneInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fourPhysicsMotionsShareLocalPlaybackScrubbingAndReadouts() {
        val scene = mutableStateOf<TutorMotionScene>(projectileScene())
        composeRule.setContent {
            SmartMistakeBookTheme {
                RootPageColumn {
                    TutorVisualSceneRenderer(scene.value)
                }
            }
        }

        composeRule.onNodeWithText("运动演示").assertDoesNotExist()
        composeRule.onNodeWithText("平抛运动").assertExists()
        composeRule.onNodeWithTag("tutor-motion-play-projectile").assertExists()
        composeRule.onNodeWithTag("tutor-motion-slider-projectile")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                check(setProgress(0.5f))
            }
        composeRule.onNodeWithContentDescription(
            "抛体运动，时间 0.50 秒，位置 2.00，3.75 米，速度 4.00，-5.00 米每秒",
        ).assertExists()
        composeRule.onNodeWithTag("tutor-motion-play-projectile").performClick()
        composeRule.onNodeWithTag("tutor-motion-reset-projectile").performClick()
        capture("tutor-motion-projectile-current.png")

        composeRule.runOnIdle { scene.value = linearScene() }
        composeRule.onNodeWithText("匀加速直线运动").assertExists()
        composeRule.onNodeWithTag("tutor-motion-canvas-linear").assertExists()

        composeRule.runOnIdle { scene.value = circularScene() }
        composeRule.onNodeWithText("匀速圆周运动").assertExists()
        composeRule.onNodeWithTag("tutor-motion-canvas-circular").assertExists()

        composeRule.runOnIdle { scene.value = oscillationScene() }
        composeRule.onNodeWithText("简谐运动").assertExists()
        composeRule.onNodeWithTag("tutor-motion-canvas-oscillation").assertExists()
    }

    private fun projectileScene() = TutorProjectileMotionScene(
        sceneId = "projectile",
        title = "平抛运动",
        durationSeconds = 2.0,
        initialHeightMeters = 5.0,
        horizontalVelocityMetersPerSecond = 4.0,
        verticalVelocityMetersPerSecond = 0.0,
        gravityMetersPerSecondSquared = 10.0,
    )

    private fun linearScene() = TutorLinearMotionScene(
        sceneId = "linear",
        title = "匀加速直线运动",
        durationSeconds = 3.0,
        initialPositionMeters = 0.0,
        initialVelocityMetersPerSecond = 2.0,
        accelerationMetersPerSecondSquared = 1.0,
    )

    private fun circularScene() = TutorCircularMotionScene(
        sceneId = "circular",
        title = "匀速圆周运动",
        durationSeconds = 4.0,
        radiusMeters = 2.0,
        angularVelocityRadiansPerSecond = 1.57,
        initialAngleRadians = 0.0,
    )

    private fun oscillationScene() = TutorOscillationMotionScene(
        sceneId = "oscillation",
        title = "简谐运动",
        durationSeconds = 4.0,
        equilibriumPositionMeters = 0.0,
        amplitudeMeters = 1.0,
        periodSeconds = 4.0,
        initialPhaseRadians = 0.0,
    )

    private fun capture(displayName: String) {
        val resolver = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartMistakeBookQA/")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        val screenshot = composeRule.onRoot().captureToImage().asAndroidBitmap()
        checkNotNull(resolver.openOutputStream(uri)).use { stream ->
            check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
    }
}
