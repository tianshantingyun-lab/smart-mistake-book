package com.tingyun.smartmistakebook.feature.tutor

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.TutorVisualEntityCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualEntityShape
import com.tingyun.smartmistakebook.core.model.TutorVisualExpression
import com.tingyun.smartmistakebook.core.model.TutorVisualExpressionOperation
import com.tingyun.smartmistakebook.core.model.TutorVisualLinkCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualLineStyle
import com.tingyun.smartmistakebook.core.model.TutorVisualMetricCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualParameter
import com.tingyun.smartmistakebook.core.model.TutorVisualPathCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualProgramScene
import com.tingyun.smartmistakebook.core.model.TutorVisualVectorCommand
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import com.tingyun.smartmistakebook.core.ui.TutorVisualSceneRenderer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorVisualProgramInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun genericProgramShowsOnlyUsefulContentAndKeepsPlaybackAccessible() {
        val scene = movingObjectScene()
        composeRule.setContent {
            SmartMistakeBookTheme {
                RootPageColumn {
                    TutorVisualSceneRenderer(scene)
                }
            }
        }

        composeRule.onNodeWithText("小球的位置怎样变化").assertExists()
        composeRule.onNodeWithText("动态讲解").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(scene.accessibilitySummary).assertExists()
        composeRule.onNodeWithText("初速度").assertExists()
        composeRule.onNodeWithTag("tutor-program-play-program-motion").assertExists()
        composeRule.onNodeWithTag("tutor-program-slider-program-motion")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                check(setProgress(1f))
            }
        composeRule.onNodeWithContentDescription(
            "演示进度 1 秒，共 2 秒",
        ).assertExists()
        capture("tutor-visual-program-current.png")
    }

    @Test
    fun readyAndFallbackPresentationsRemainLegibleWithoutAProvider() {
        val resolution = mutableStateOf<TutorVisualResolution>(
            inlineTutorVisualResolution(
                scene = movingObjectScene(),
                ownerModelTaskRequestId = "deterministic-visual-qa",
            ),
        )
        composeRule.setContent {
            SmartMistakeBookTheme {
                RootPageColumn {
                    TutorVisualPresentation(
                        state = resolution.value,
                        mode = TutorVisualPresentationMode.CURRENT_EXPANDED,
                        originalAvailable = true,
                        onRetry = {},
                        onOpenOriginal = {},
                        onReportIncorrect = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("tutor_visual_ready").assertExists()
        composeRule.onNodeWithText("小球的位置怎样变化").assertExists()
        capture("tutor-visual-ready-current.png")

        composeRule.runOnIdle {
            resolution.value = TutorVisualResolution.Fallback(
                reason = TutorVisualFallbackReason.PROVIDER_UNAVAILABLE,
                canRetry = true,
            )
        }
        composeRule.onNodeWithTag("tutor_visual_fallback").assertExists()
        composeRule.onNodeWithText("这次先看文字或原图").assertExists()
        composeRule.onNodeWithText("重试图解").assertExists()
        capture("tutor-visual-fallback-current.png")
    }

    private fun movingObjectScene(): TutorVisualProgramScene {
        val time = TutorVisualExpression.time()
        val velocity = TutorVisualExpression.parameter("velocity")
        val position = TutorVisualExpression.binary(
            TutorVisualExpressionOperation.MULTIPLY,
            velocity,
            time,
        )
        return TutorVisualProgramScene(
            sceneId = "program-motion",
            title = "小球的位置怎样变化",
            accessibilitySummary = "小球沿横轴向右移动，位置随时间均匀增加。",
            parameters = listOf(
                TutorVisualParameter("velocity", "初速度", 2.5, "m/s"),
            ),
            commands = listOf(
                TutorVisualEntityCommand(
                    commandId = "origin",
                    label = "起点",
                    shape = TutorVisualEntityShape.POINT,
                    x = TutorVisualExpression.constant(0.0),
                    y = TutorVisualExpression.constant(0.0),
                ),
                TutorVisualEntityCommand(
                    commandId = "ball",
                    label = "小球",
                    shape = TutorVisualEntityShape.CIRCLE,
                    x = position,
                    y = TutorVisualExpression.constant(0.0),
                ),
                TutorVisualLinkCommand(
                    commandId = "distance",
                    fromEntityId = "origin",
                    toEntityId = "ball",
                    label = "位移",
                    style = TutorVisualLineStyle.ARROW,
                ),
                TutorVisualPathCommand("track", "ball"),
                TutorVisualVectorCommand(
                    commandId = "velocity-vector",
                    label = "速度",
                    originEntityId = "ball",
                    x = velocity,
                    y = TutorVisualExpression.constant(0.0),
                    unit = "m/s",
                ),
                TutorVisualMetricCommand("position", "位置", position, "m"),
            ),
            durationSeconds = 2.0,
            showAxes = true,
            xUnit = "m",
            yUnit = "m",
        )
    }

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
