package com.tingyun.smartmistakebook.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.TutorCircularMotionScene
import com.tingyun.smartmistakebook.core.model.TutorLinearMotionScene
import com.tingyun.smartmistakebook.core.model.TutorMotionEvaluator
import com.tingyun.smartmistakebook.core.model.TutorMotionScene
import com.tingyun.smartmistakebook.core.model.TutorMotionState
import com.tingyun.smartmistakebook.core.model.TutorOscillationMotionScene
import com.tingyun.smartmistakebook.core.model.TutorProjectileMotionScene
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

internal class TutorMotionPlaybackState(
    timeSeconds: Float = 0f,
    isPlaying: Boolean = false,
    speedIndex: Int = DEFAULT_SPEED_INDEX,
) {
    val timeSecondsState = mutableFloatStateOf(timeSeconds)
    val isPlayingState = mutableStateOf(isPlaying)
    val speedIndexState = mutableIntStateOf(speedIndex)
}

internal fun nextPlaybackSpeedIndex(current: Int): Int =
    (current + 1) % PLAYBACK_SPEEDS.size

internal fun coercePlaybackTimeSeconds(timeSeconds: Float, durationSeconds: Float): Float =
    timeSeconds.coerceIn(0f, durationSeconds)

private val TutorMotionPlaybackStateSaver = listSaver<TutorMotionPlaybackState, Any>(
    save = { state ->
        listOf(
            state.timeSecondsState.floatValue,
            state.isPlayingState.value,
            state.speedIndexState.intValue,
        )
    },
    restore = { values ->
        TutorMotionPlaybackState(
            timeSeconds = values[0] as Float,
            isPlaying = values[1] as Boolean,
            speedIndex = values[2] as Int,
        )
    },
)

@Composable
internal fun rememberTutorMotionPlaybackState(key: String): TutorMotionPlaybackState =
    rememberSaveable(key, saver = TutorMotionPlaybackStateSaver) {
        TutorMotionPlaybackState()
    }

@Composable
internal fun TutorMotionSceneContent(
    scene: TutorMotionScene,
    playbackState: TutorMotionPlaybackState,
) {
    val durationSeconds = remember(scene) {
        TutorMotionEvaluator.playbackDurationSeconds(scene).toFloat()
    }
    var timeSeconds by playbackState.timeSecondsState
    var isPlaying by playbackState.isPlayingState
    var speedIndex by playbackState.speedIndexState
    val animationsEnabled = rememberSystemAnimationsEnabled()
    val speed = PLAYBACK_SPEEDS[speedIndex]

    LaunchedEffect(animationsEnabled) {
        if (!animationsEnabled) isPlaying = false
    }
    LaunchedEffect(isPlaying, speed, durationSeconds, animationsEnabled) {
        if (!isPlaying || !animationsEnabled) return@LaunchedEffect
        var previousFrameNanos = withFrameNanos { it }
        while (timeSeconds < durationSeconds) {
            withFrameNanos { frameNanos ->
                val elapsedSeconds = (frameNanos - previousFrameNanos) / 1_000_000_000f
                previousFrameNanos = frameNanos
                timeSeconds = (timeSeconds + elapsedSeconds * speed).coerceAtMost(durationSeconds)
            }
        }
        isPlaying = false
    }

    val state = TutorMotionEvaluator.evaluate(scene, timeSeconds.toDouble())
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MotionCanvas(scene = scene, state = state, durationSeconds = durationSeconds)
        MotionReadout(scene = scene, state = state)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    if (timeSeconds >= durationSeconds) timeSeconds = 0f
                    isPlaying = !isPlaying
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("tutor-motion-play-${scene.sceneId}"),
                enabled = animationsEnabled,
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "暂停运动" else "播放运动",
                )
            }
            IconButton(
                onClick = {
                    isPlaying = false
                    timeSeconds = 0f
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("tutor-motion-reset-${scene.sceneId}"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Replay,
                    contentDescription = "回到开始",
                )
            }
            Slider(
                value = timeSeconds,
                onValueChange = {
                    isPlaying = false
                    timeSeconds = it
                },
                valueRange = 0f..durationSeconds,
                modifier = Modifier
                    .weight(1f)
                    .testTag("tutor-motion-slider-${scene.sceneId}")
                    .semantics {
                        contentDescription =
                            "运动时间 ${state.timeSeconds.asDisplayNumber()} 秒"
                    },
            )
            TextButton(
                onClick = { speedIndex = nextPlaybackSpeedIndex(speedIndex) },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("tutor-motion-speed-${scene.sceneId}"),
            ) {
                Text("${speed.asSpeedLabel()}×")
            }
        }
    }
}

@Composable
private fun MotionCanvas(
    scene: TutorMotionScene,
    state: TutorMotionState,
    durationSeconds: Float,
) {
    val sampledStates = remember(scene, durationSeconds) {
        (0..TRACE_SAMPLE_COUNT).map { index ->
            val time = durationSeconds * index / TRACE_SAMPLE_COUNT
            TutorMotionEvaluator.evaluate(scene, time.toDouble())
        }
    }
    val bounds = remember(sampledStates) { MotionBounds.from(sampledStates) }
    val axisColor = Outline
    val traceColor = JadeMuted
    val objectColor = JadeActive
    val vectorColor = InkSecondary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .testTag("tutor-motion-canvas-${scene.sceneId}")
            .semantics {
                contentDescription =
                    "${scene.motionLabel()}，${state.accessibilityDescription()}"
            },
    ) {
        val left = 20.dp.toPx()
        val top = 18.dp.toPx()
        val right = size.width - 20.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val drawWidth = (right - left).coerceAtLeast(1f)
        val drawHeight = (bottom - top).coerceAtLeast(1f)

        fun worldToCanvas(sample: TutorMotionState): Offset {
            val xFraction = ((sample.xMeters - bounds.minX) / bounds.width).toFloat()
            val yFraction = ((sample.yMeters - bounds.minY) / bounds.height).toFloat()
            return Offset(
                x = left + drawWidth * xFraction,
                y = bottom - drawHeight * yFraction,
            )
        }

        val horizontalAxisY = if (0.0 in bounds.minY..bounds.maxY) {
            bottom - drawHeight * ((0.0 - bounds.minY) / bounds.height).toFloat()
        } else {
            bottom
        }
        drawLine(
            color = axisColor,
            start = Offset(left, horizontalAxisY),
            end = Offset(right, horizontalAxisY),
            strokeWidth = 1.dp.toPx(),
        )

        val trace = Path()
        sampledStates.forEachIndexed { index, sample ->
            val point = worldToCanvas(sample)
            if (index == 0) trace.moveTo(point.x, point.y) else trace.lineTo(point.x, point.y)
        }
        drawPath(
            path = trace,
            color = traceColor,
            style = Stroke(width = 2.dp.toPx()),
        )

        val objectCenter = worldToCanvas(state)
        drawCircle(
            color = objectColor,
            radius = 10.dp.toPx(),
            center = objectCenter,
        )
        val speedMagnitude = hypot(
            state.velocityXMetersPerSecond,
            state.velocityYMetersPerSecond,
        )
        if (speedMagnitude > MIN_VISIBLE_VECTOR) {
            val arrowLength = 34.dp.toPx()
            val velocityEnd = Offset(
                x = objectCenter.x +
                    (state.velocityXMetersPerSecond / speedMagnitude * arrowLength).toFloat(),
                y = objectCenter.y -
                    (state.velocityYMetersPerSecond / speedMagnitude * arrowLength).toFloat(),
            )
            drawLine(
                color = vectorColor,
                start = objectCenter,
                end = velocityEnd,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawVelocityArrowHead(objectCenter, velocityEnd, vectorColor)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVelocityArrowHead(
    start: Offset,
    end: Offset,
    color: Color,
) {
    val deltaX = end.x - start.x
    val deltaY = end.y - start.y
    val length = hypot(deltaX, deltaY)
    if (length <= 0f) return
    val unitX = deltaX / length
    val unitY = deltaY / length
    val arrowSize = 7.dp.toPx()
    val base = Offset(end.x - unitX * arrowSize, end.y - unitY * arrowSize)
    val perpendicular = Offset(-unitY * arrowSize * 0.55f, unitX * arrowSize * 0.55f)
    drawLine(
        color = color,
        start = end,
        end = base + perpendicular,
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = end,
        end = base - perpendicular,
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

@Composable
private fun MotionReadout(scene: TutorMotionScene, state: TutorMotionState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = JadeSoft,
        contentColor = Ink,
        shape = RoundedCornerShape(SmartDimens.ChipRadius),
        border = BorderStroke(1.dp, JadeMuted),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MotionMetricCell(
                label = "时间 t",
                value = "${state.timeSeconds.asDisplayNumber()} s",
                modifier = Modifier.fillMaxWidth(),
            )
            when (scene) {
                is TutorLinearMotionScene,
                is TutorOscillationMotionScene,
                -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MotionMetricCell(
                            label = "位置 x",
                            value = "${state.xMeters.asDisplayNumber()} m",
                            modifier = Modifier.weight(1f),
                        )
                        MotionMetricCell(
                            label = "速度 v",
                            value = "${state.velocityXMetersPerSecond.asDisplayNumber()} m/s",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    MotionMetricCell(
                        label = "加速度 a",
                        value =
                            "${state.accelerationXMetersPerSecondSquared.asDisplayNumber()} m/s²",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is TutorProjectileMotionScene,
                is TutorCircularMotionScene,
                -> {
                    MotionMetricPair(
                        leftLabel = "水平位置 x",
                        leftValue = "${state.xMeters.asDisplayNumber()} m",
                        rightLabel = "竖直位置 y",
                        rightValue = "${state.yMeters.asDisplayNumber()} m",
                    )
                    MotionMetricPair(
                        leftLabel = "水平速度 vₓ",
                        leftValue = "${state.velocityXMetersPerSecond.asDisplayNumber()} m/s",
                        rightLabel = "竖直速度 vᵧ",
                        rightValue = "${state.velocityYMetersPerSecond.asDisplayNumber()} m/s",
                    )
                    MotionMetricPair(
                        leftLabel = "水平加速度 aₓ",
                        leftValue =
                            "${state.accelerationXMetersPerSecondSquared.asDisplayNumber()} m/s²",
                        rightLabel = "竖直加速度 aᵧ",
                        rightValue =
                            "${state.accelerationYMetersPerSecondSquared.asDisplayNumber()} m/s²",
                    )
                }
            }
        }
    }
}

@Composable
private fun MotionMetricPair(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MotionMetricCell(
            label = leftLabel,
            value = leftValue,
            modifier = Modifier.weight(1f),
        )
        MotionMetricCell(
            label = rightLabel,
            value = rightValue,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MotionMetricCell(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        color = Paper,
        contentColor = Ink,
        shape = RoundedCornerShape(SmartDimens.ChipRadius),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                color = InkSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = value,
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun TutorMotionState.accessibilityDescription(): String =
    "时间 ${timeSeconds.asDisplayNumber()} 秒，位置 " +
        "${xMeters.asDisplayNumber()}，${yMeters.asDisplayNumber()} 米，速度 " +
        "${velocityXMetersPerSecond.asDisplayNumber()}，" +
        "${velocityYMetersPerSecond.asDisplayNumber()} 米每秒"

private fun TutorMotionScene.motionLabel(): String = when (this) {
    is TutorLinearMotionScene -> "直线运动"
    is TutorProjectileMotionScene -> "抛体运动"
    is TutorCircularMotionScene -> "圆周运动"
    is TutorOscillationMotionScene -> "振动"
}

private data class MotionBounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY

    companion object {
        fun from(states: List<TutorMotionState>): MotionBounds {
            val rawMinX = states.minOf(TutorMotionState::xMeters)
            val rawMaxX = states.maxOf(TutorMotionState::xMeters)
            val rawMinY = states.minOf(TutorMotionState::yMeters)
            val rawMaxY = states.maxOf(TutorMotionState::yMeters)
            val xPadding = ((rawMaxX - rawMinX) * 0.08).coerceAtLeast(0.5)
            val yPadding = ((rawMaxY - rawMinY) * 0.12).coerceAtLeast(0.5)
            return MotionBounds(
                minX = rawMinX - xPadding,
                maxX = rawMaxX + xPadding,
                minY = rawMinY - yPadding,
                maxY = rawMaxY + yPadding,
            )
        }
    }
}

private fun Double.asDisplayNumber(): String =
    String.format(Locale.CHINA, "%.2f", if (abs(this) < 0.005) 0.0 else this)

private fun Float.asSpeedLabel(): String =
    if (this == 1f || this == 2f) toInt().toString() else toString()

internal val PLAYBACK_SPEEDS = floatArrayOf(0.5f, 1f, 2f)
private const val DEFAULT_SPEED_INDEX = 1
private const val TRACE_SAMPLE_COUNT = 72
private const val MIN_VISIBLE_VECTOR = 1e-8
