package com.tingyun.smartmistakebook.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.tingyun.smartmistakebook.core.model.TutorVisualCommand
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

/**
 * A launcher-free, debug-only worst-case scene used by adb frame and Perfetto captures.
 * It never ships in release builds or appears in student navigation.
 */
class DynamicGuiPerformanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartMistakeBookTheme {
                RootPageColumn(
                    modifier = Modifier.semantics { testTagsAsResourceId = true },
                ) {
                    TutorVisualSceneRenderer(PERFORMANCE_SCENE)
                }
            }
        }
    }
}

private val PERFORMANCE_SCENE = TutorVisualProgramScene(
    sceneId = "performance-program",
    title = "多对象运动",
    accessibilitySummary = "十二个对象沿四条轨道同时运动，用于检查复杂动态讲解的流畅度。",
    parameters = listOf(
        TutorVisualParameter("slow", "慢速", 0.24, "m/s"),
        TutorVisualParameter("medium", "中速", 0.36, "m/s"),
        TutorVisualParameter("fast", "快速", 0.48, "m/s"),
        TutorVisualParameter("vector", "箭头长度", 0.6, "m/s"),
    ),
    commands = performanceCommands(),
    durationSeconds = 8.0,
    showAxes = true,
    xUnit = "m",
    yUnit = "m",
)

private fun performanceCommands(): List<TutorVisualCommand> = buildList {
    repeat(12) { index ->
        val row = index / 3
        val column = index % 3
        val speedId = when (column) {
            0 -> "slow"
            1 -> "medium"
            else -> "fast"
        }
        add(
            TutorVisualEntityCommand(
                commandId = "object-$index",
                label = "对象 ${index + 1}",
                shape = when (column) {
                    0 -> TutorVisualEntityShape.POINT
                    1 -> TutorVisualEntityShape.CIRCLE
                    else -> TutorVisualEntityShape.BLOCK
                },
                x = TutorVisualExpression.binary(
                    TutorVisualExpressionOperation.ADD,
                    TutorVisualExpression.constant(column * 0.8),
                    TutorVisualExpression.binary(
                        TutorVisualExpressionOperation.MULTIPLY,
                        TutorVisualExpression.parameter(speedId),
                        TutorVisualExpression.time(),
                    ),
                ),
                y = TutorVisualExpression.constant(row - 1.5),
            ),
        )
    }
    repeat(6) { index ->
        add(
            TutorVisualLinkCommand(
                commandId = "link-$index",
                fromEntityId = "object-$index",
                toEntityId = "object-${index + 1}",
                label = if (index < 2) "间距 ${index + 1}" else null,
                style = if (index % 2 == 0) {
                    TutorVisualLineStyle.DASHED
                } else {
                    TutorVisualLineStyle.ARROW
                },
            ),
        )
    }
    repeat(4) { index ->
        add(TutorVisualPathCommand("path-$index", "object-${index * 3 + 2}"))
    }
    repeat(4) { index ->
        add(
            TutorVisualVectorCommand(
                commandId = "vector-$index",
                label = "速度 ${index + 1}",
                originEntityId = "object-${index * 3 + 1}",
                x = TutorVisualExpression.parameter("vector"),
                y = TutorVisualExpression.constant(0.0),
                unit = "m/s",
            ),
        )
    }
    repeat(6) { index ->
        val speedId = when (index % 3) {
            0 -> "slow"
            1 -> "medium"
            else -> "fast"
        }
        add(
            TutorVisualMetricCommand(
                commandId = "metric-$index",
                label = "位置 ${index + 1}",
                expression = TutorVisualExpression.binary(
                    TutorVisualExpressionOperation.MULTIPLY,
                    TutorVisualExpression.parameter(speedId),
                    TutorVisualExpression.time(),
                ),
                unit = "m",
            ),
        )
    }
}
