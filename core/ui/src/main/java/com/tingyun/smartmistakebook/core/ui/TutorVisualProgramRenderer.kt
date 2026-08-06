package com.tingyun.smartmistakebook.core.ui

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingyun.smartmistakebook.core.model.ReadableMathText
import com.tingyun.smartmistakebook.core.model.TutorVisualEntityCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualEntityShape
import com.tingyun.smartmistakebook.core.model.TutorVisualEntityState
import com.tingyun.smartmistakebook.core.model.TutorVisualFormulaCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualLineStyle
import com.tingyun.smartmistakebook.core.model.TutorVisualLinkCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualMetricCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualMetricState
import com.tingyun.smartmistakebook.core.model.TutorVisualNoteCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualPathCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualProgramFrame
import com.tingyun.smartmistakebook.core.model.TutorVisualProgramRuntime
import com.tingyun.smartmistakebook.core.model.TutorVisualProgramScene
import com.tingyun.smartmistakebook.core.model.TutorVisualTableCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualVectorState
import java.util.Locale
import kotlinx.coroutines.isActive
import kotlin.math.floor

@Composable
internal fun TutorVisualProgramContent(scene: TutorVisualProgramScene) {
    val savedTimeState = rememberSaveable(scene.sceneId) { mutableFloatStateOf(0f) }
    val savedPlayingState = rememberSaveable(scene.sceneId) { mutableStateOf(false) }
    val playbackState = remember(scene.sceneId, savedTimeState, savedPlayingState) {
        ProgramPlaybackState(savedTimeState, savedPlayingState)
    }
    val runtime = remember(scene) { TutorVisualProgramRuntime.compile(scene) }
    val canvasPlan = remember(scene, runtime) { buildProgramCanvasPlan(scene, runtime) }
    val frameState = remember(runtime, playbackState) {
        derivedStateOf {
            runtime.evaluate(playbackState.timeSeconds.toDouble())
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProgramTextContent(scene)
        ProgramPlayback(
            scene = scene,
            playbackState = playbackState,
            frameState = frameState,
            plan = canvasPlan,
        )
        if (canvasPlan.legendLabels.isNotEmpty()) {
            EntityLegend(canvasPlan.legendLabels)
        }
        if (canvasPlan.hasMetrics) {
            ProgramDynamicValues(scene, runtime, playbackState)
        } else {
            ProgramValues(scene, emptyList())
        }
    }
}

@Composable
private fun ProgramPlayback(
    scene: TutorVisualProgramScene,
    playbackState: ProgramPlaybackState,
    frameState: State<TutorVisualProgramFrame>,
    plan: ProgramCanvasPlan,
) {
    val duration = scene.durationSeconds?.toFloat()
    val animationsEnabled = rememberSystemAnimationsEnabled()
    val density = LocalDensity.current
    val dashPathEffect = remember(density) {
        with(density) {
            PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))
        }
    }
    val isPlaying = playbackState.isPlaying

    LaunchedEffect(duration, animationsEnabled) {
        if (!animationsEnabled || duration == null) {
            playbackState.isPlaying = false
        }
        if (duration != null) {
            playbackState.timeSeconds = playbackState.timeSeconds.coerceIn(0f, duration)
        }
    }
    LaunchedEffect(scene.sceneId, isPlaying, duration, animationsEnabled) {
        if (!isPlaying || duration == null || !animationsEnabled) return@LaunchedEffect
        var lastFrameNanos = withFrameNanos { it }
        while (isActive && playbackState.isPlaying) {
            val frameNanos = withFrameNanos { it }
            val elapsed = (frameNanos - lastFrameNanos) / 1_000_000_000f
            lastFrameNanos = frameNanos
            val next = playbackState.timeSeconds + elapsed
            if (next >= duration) {
                playbackState.timeSeconds = duration
                playbackState.isPlaying = false
            } else {
                playbackState.timeSeconds = next
            }
        }
    }

    if (plan.hasEntities) {
        ProgramAnimatedCanvas(scene, frameState, plan, dashPathEffect)
    }
    if (duration != null) {
        ProgramPlaybackControls(
            playbackState = playbackState,
            durationSeconds = duration,
            animationsEnabled = animationsEnabled,
            sceneId = scene.sceneId,
        )
    }
}

@Composable
private fun ProgramAnimatedCanvas(
    scene: TutorVisualProgramScene,
    frameState: State<TutorVisualProgramFrame>,
    plan: ProgramCanvasPlan,
    dashPathEffect: PathEffect,
) {
    ProgramCanvas(
        scene = scene,
        frame = frameState.value,
        plan = plan,
        dashPathEffect = dashPathEffect,
    )
}

@Composable
private fun ProgramPlaybackControls(
    playbackState: ProgramPlaybackState,
    durationSeconds: Float,
    animationsEnabled: Boolean,
    sceneId: String,
) {
    val timeSeconds by remember(playbackState) {
        derivedStateOf {
            playbackState.timeSeconds.quantized(CONTROL_UPDATE_HERTZ)
        }
    }
    val isPlaying = playbackState.isPlaying
    PlaybackControls(
        timeSeconds = timeSeconds,
        durationSeconds = durationSeconds,
        isPlaying = isPlaying,
        animationsEnabled = animationsEnabled,
        sceneId = sceneId,
        onPlayingChange = { shouldPlay ->
            if (shouldPlay && playbackState.timeSeconds >= durationSeconds) {
                playbackState.timeSeconds = 0f
            }
            playbackState.isPlaying = shouldPlay
        },
        onTimeChange = {
            playbackState.isPlaying = false
            playbackState.timeSeconds = it
        },
    )
}

@Composable
private fun ProgramDynamicValues(
    scene: TutorVisualProgramScene,
    runtime: TutorVisualProgramRuntime,
    playbackState: ProgramPlaybackState,
) {
    val metricTimeSeconds by remember(playbackState) {
        derivedStateOf {
            playbackState.timeSeconds.quantized(METRIC_UPDATE_HERTZ)
        }
    }
    val metrics = remember(runtime, metricTimeSeconds) {
        runtime.evaluate(metricTimeSeconds.toDouble()).metrics
    }
    ProgramValues(scene, metrics)
}

@Composable
private fun ProgramTextContent(scene: TutorVisualProgramScene) {
    scene.commands.forEach { command ->
        when (command) {
            is TutorVisualNoteCommand -> SafeMarkdownText(
                markdown = command.markdown,
                modifier = Modifier.testTag("tutor-program-note-${command.commandId}"),
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            is TutorVisualFormulaCommand -> ProgramFormula(command)
            is TutorVisualTableCommand -> ProgramTable(command)
            else -> Unit
        }
    }
}

@Composable
private fun ProgramFormula(command: TutorVisualFormulaCommand) {
    val readable = ReadableMathText.formula(command.formula)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tutor-program-formula-${command.commandId}")
            .semantics { contentDescription = "公式：$readable" },
        color = JadeSoft,
        contentColor = Ink,
        shape = RoundedCornerShape(SmartDimens.ChipRadius),
    ) {
        Text(
            text = readable,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ProgramTable(command: TutorVisualTableCommand) {
    val scrollState = rememberScrollState()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tutor-program-table-${command.commandId}"),
        color = Paper,
        contentColor = Ink,
        border = BorderStroke(1.dp, Outline),
        shape = RoundedCornerShape(SmartDimens.SurfaceRadius),
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(8.dp),
        ) {
            ProgramTableRow(command.columns, isHeading = true)
            command.rows.forEach { row -> ProgramTableRow(row, isHeading = false) }
        }
    }
}

@Composable
private fun ProgramTableRow(cells: List<String>, isHeading: Boolean) {
    Row {
        cells.forEach { cell ->
            Text(
                text = cell,
                modifier = Modifier
                    .width(112.dp)
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                color = if (isHeading) Ink else InkSecondary,
                style = if (isHeading) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                fontWeight = if (isHeading) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun ProgramCanvas(
    scene: TutorVisualProgramScene,
    frame: TutorVisualProgramFrame,
    plan: ProgramCanvasPlan,
    dashPathEffect: PathEffect,
) {
    val density = LocalDensity.current
    val axisColor = Outline
    val pathColor = JadeMuted
    val linkColor = InkSecondary
    val vectorColor = JadeActive
    val entityAccentColor = JadeActive
    val entityFillColor = JadeSoft
    val labelColor = InkSecondary
    val labelPaint = remember(density) {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = labelColor.toArgb()
            textSize = with(density) { 12.sp.toPx() }
            textAlign = AndroidPaint.Align.LEFT
        }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .testTag("tutor-program-canvas-${scene.sceneId}")
            .semantics { contentDescription = scene.accessibilitySummary },
    ) {
        val viewport = Rect(
            left = 18.dp.toPx(),
            top = 14.dp.toPx(),
            right = size.width - 18.dp.toPx(),
            bottom = size.height - 14.dp.toPx(),
        )
        if (scene.showAxes) {
            drawProgramAxes(
                bounds = plan.bounds,
                viewport = viewport,
                xUnit = scene.xUnit,
                yUnit = scene.yUnit,
                labelPaint = labelPaint,
                axisColor = axisColor,
            )
        }
        drawProgramPaths(
            paths = plan.paths,
            bounds = plan.bounds,
            viewport = viewport,
            color = pathColor,
        )
        drawProgramLinks(
            links = plan.links,
            entities = frame.entities,
            bounds = plan.bounds,
            viewport = viewport,
            labelPaint = labelPaint,
            dashPathEffect = dashPathEffect,
            linkColor = linkColor,
            arrowColor = vectorColor,
        )
        drawProgramVectors(
            vectors = frame.vectors,
            entities = frame.entities,
            bounds = plan.bounds,
            viewport = viewport,
            labelPaint = labelPaint,
            color = vectorColor,
        )
        frame.entities.forEach { entity ->
            drawProgramEntity(
                entity = entity,
                position = plan.bounds.toCanvas(entity.x, entity.y, viewport),
                accentColor = entityAccentColor,
                fillColor = entityFillColor,
            )
        }
    }
}

private fun DrawScope.drawProgramAxes(
    bounds: ProgramBounds,
    viewport: Rect,
    xUnit: String?,
    yUnit: String?,
    labelPaint: AndroidPaint,
    axisColor: Color,
) {
    if (0.0 in bounds.minX..bounds.maxX) {
        val x = bounds.toCanvas(0.0, bounds.minY, viewport).x
        drawLine(axisColor, Offset(x, viewport.top), Offset(x, viewport.bottom), 1.dp.toPx())
    }
    if (0.0 in bounds.minY..bounds.maxY) {
        val y = bounds.toCanvas(bounds.minX, 0.0, viewport).y
        drawLine(axisColor, Offset(viewport.left, y), Offset(viewport.right, y), 1.dp.toPx())
    }
    xUnit?.let { unit ->
        drawProgramLabel(
            text = unit,
            anchor = Offset(viewport.right, viewport.bottom),
            viewport = viewport,
            paint = labelPaint,
        )
    }
    yUnit?.let { unit ->
        drawProgramLabel(
            text = unit,
            anchor = Offset(viewport.left, viewport.top + labelPaint.textSize),
            viewport = viewport,
            paint = labelPaint,
        )
    }
}

private fun DrawScope.drawProgramPaths(
    paths: List<ProgramPath>,
    bounds: ProgramBounds,
    viewport: Rect,
    color: Color,
) {
    paths.forEach { path ->
        for (index in 0 until path.points.lastIndex) {
            val start = path.points[index].let { bounds.toCanvas(it.x, it.y, viewport) }
            val end = path.points[index + 1].let { bounds.toCanvas(it.x, it.y, viewport) }
            drawLine(
                color = color,
                start = start,
                end = end,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun DrawScope.drawProgramLinks(
    links: List<TutorVisualLinkCommand>,
    entities: List<TutorVisualEntityState>,
    bounds: ProgramBounds,
    viewport: Rect,
    labelPaint: AndroidPaint,
    dashPathEffect: PathEffect,
    linkColor: Color,
    arrowColor: Color,
) {
    links.forEach { command ->
        val startEntity =
            entities.firstOrNull { it.entityId == command.fromEntityId } ?: return@forEach
        val endEntity =
            entities.firstOrNull { it.entityId == command.toEntityId } ?: return@forEach
        val start = bounds.toCanvas(startEntity.x, startEntity.y, viewport)
        val end = bounds.toCanvas(endEntity.x, endEntity.y, viewport)
        drawLine(
            color = linkColor,
            start = start,
            end = end,
            strokeWidth = 2.dp.toPx(),
            pathEffect = dashPathEffect.takeIf {
                command.style == TutorVisualLineStyle.DASHED
            },
            cap = StrokeCap.Round,
        )
        if (command.style == TutorVisualLineStyle.ARROW) {
            drawArrowHead(start, end, arrowColor)
        }
        command.label?.let { label ->
            drawProgramLabel(
                text = label,
                anchor = (start + end) / 2f - Offset(0f, 7.dp.toPx()),
                viewport = viewport,
                paint = labelPaint,
            )
        }
    }
}

private fun DrawScope.drawProgramVectors(
    vectors: List<TutorVisualVectorState>,
    entities: List<TutorVisualEntityState>,
    bounds: ProgramBounds,
    viewport: Rect,
    labelPaint: AndroidPaint,
    color: Color,
) {
    vectors.forEach { vector ->
        val origin =
            entities.firstOrNull { it.entityId == vector.originEntityId } ?: return@forEach
        val start = bounds.toCanvas(origin.x, origin.y, viewport)
        val end = bounds.toCanvas(origin.x + vector.x, origin.y + vector.y, viewport)
        drawLine(
            color = color,
            start = start,
            end = end,
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawArrowHead(start, end, color)
        drawProgramLabel(
            text = vector.label,
            anchor = (start + end) / 2f - Offset(0f, 7.dp.toPx()),
            viewport = viewport,
            paint = labelPaint,
        )
    }
}

private fun DrawScope.drawProgramLabel(
    text: String,
    anchor: Offset,
    viewport: Rect,
    paint: AndroidPaint,
) {
    val visibleText = text.take(MAX_CANVAS_LABEL_CHARS).let { truncated ->
        if (truncated.length < text.length) "$truncated…" else truncated
    }
    val textWidth = paint.measureText(visibleText)
    val left = (anchor.x - textWidth / 2f).coerceIn(
        viewport.left,
        (viewport.right - textWidth).coerceAtLeast(viewport.left),
    )
    val baseline = anchor.y.coerceIn(
        viewport.top + paint.textSize,
        viewport.bottom,
    )
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(visibleText, left, baseline, paint)
    }
}

private fun DrawScope.drawArrowHead(start: Offset, end: Offset, color: Color) {
    val delta = end - start
    val length = delta.getDistance()
    if (length < 1f) return
    val direction = delta / length
    val perpendicular = Offset(-direction.y, direction.x)
    val headLength = 10.dp.toPx()
    val headWidth = 5.dp.toPx()
    val base = end - direction * headLength
    drawLine(color, end, base + perpendicular * headWidth, 2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, end, base - perpendicular * headWidth, 2.dp.toPx(), cap = StrokeCap.Round)
}

private fun DrawScope.drawProgramEntity(
    entity: TutorVisualEntityState,
    position: Offset,
    accentColor: Color,
    fillColor: Color,
) {
    when (entity.shape) {
        TutorVisualEntityShape.POINT -> drawCircle(
            color = accentColor,
            radius = 5.dp.toPx(),
            center = position,
        )
        TutorVisualEntityShape.CIRCLE -> {
            drawCircle(color = fillColor, radius = 15.dp.toPx(), center = position)
            drawCircle(
                color = accentColor,
                radius = 15.dp.toPx(),
                center = position,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        TutorVisualEntityShape.BLOCK -> {
            val blockSize = Size(34.dp.toPx(), 26.dp.toPx())
            drawRoundRect(
                color = fillColor,
                topLeft = position - Offset(blockSize.width / 2f, blockSize.height / 2f),
                size = blockSize,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
            )
            drawRoundRect(
                color = accentColor,
                topLeft = position - Offset(blockSize.width / 2f, blockSize.height / 2f),
                size = blockSize,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun EntityLegend(labels: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        labels.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { label ->
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(10.dp),
                            color = JadeActive,
                            shape = CircleShape,
                            content = {},
                        )
                        Text(
                            text = label,
                            color = InkSecondary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ProgramValues(
    scene: TutorVisualProgramScene,
    metrics: List<TutorVisualMetricState>,
) {
    val values = scene.parameters.map {
        ProgramValue(it.label, it.value, it.unit)
    } + metrics.map {
        ProgramValue(it.label, it.value, it.unit)
    }
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { value ->
                    ProgramValueCell(value, Modifier.weight(1f))
                }
                if (row.size == 1) Box(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ProgramValueCell(value: ProgramValue, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = Paper,
        contentColor = Ink,
        border = BorderStroke(1.dp, Outline),
        shape = RoundedCornerShape(SmartDimens.ChipRadius),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(value.label, color = InkSecondary, style = MaterialTheme.typography.labelMedium)
            Text(
                text = buildString {
                    append(formatNumber(value.value))
                    value.unit?.let {
                        append(' ')
                        append(it)
                    }
                },
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun PlaybackControls(
    timeSeconds: Float,
    durationSeconds: Float,
    isPlaying: Boolean,
    animationsEnabled: Boolean,
    sceneId: String,
    onPlayingChange: (Boolean) -> Unit,
    onTimeChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tutor-program-playback"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = { onPlayingChange(!isPlaying) },
            enabled = animationsEnabled,
            modifier = Modifier
                .size(48.dp)
                .testTag("tutor-program-play-$sceneId")
                .semantics {
                    contentDescription = when {
                        !animationsEnabled -> "可拖动进度查看变化"
                        isPlaying -> "暂停演示"
                        else -> "播放演示"
                    }
                },
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = JadeActive,
            )
        }
        Slider(
            value = timeSeconds,
            onValueChange = onTimeChange,
            valueRange = 0f..durationSeconds,
            modifier = Modifier
                .weight(1f)
                .testTag("tutor-program-slider-$sceneId")
                .semantics {
                    contentDescription =
                        "演示进度 ${formatNumber(timeSeconds.toDouble())} 秒，共 " +
                        "${formatNumber(durationSeconds.toDouble())} 秒"
                },
        )
        Text(
            text = "${formatNumber(timeSeconds.toDouble())} 秒",
            color = InkSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun buildProgramCanvasPlan(
    scene: TutorVisualProgramScene,
    runtime: TutorVisualProgramRuntime,
): ProgramCanvasPlan {
    val sampledFrames = sampleProgramFrames(scene, runtime)
    val paths = scene.commands
        .filterIsInstance<TutorVisualPathCommand>()
        .mapNotNull { command ->
            val points = sampledFrames.mapNotNull { sample ->
                sample.entities.firstOrNull { entity -> entity.entityId == command.entityId }
                    ?.let { entity -> ProgramPoint(entity.x, entity.y) }
            }
            points.takeIf { it.size > 1 }?.let(::ProgramPath)
        }
    return ProgramCanvasPlan(
        bounds = ProgramBounds.from(sampledFrames),
        paths = paths,
        links = scene.commands.filterIsInstance<TutorVisualLinkCommand>(),
        legendLabels = scene.commands
            .filterIsInstance<TutorVisualEntityCommand>()
            .map(TutorVisualEntityCommand::label),
        hasEntities = scene.commands.any { it is TutorVisualEntityCommand },
        hasMetrics = scene.commands.any { it is TutorVisualMetricCommand },
    )
}

private fun sampleProgramFrames(
    scene: TutorVisualProgramScene,
    runtime: TutorVisualProgramRuntime,
): List<TutorVisualProgramFrame> {
    val duration = scene.durationSeconds
    if (duration == null) return listOf(runtime.evaluate(0.0))
    return (0..PROGRAM_SAMPLE_COUNT).map { index ->
        runtime.evaluate(duration * index.toDouble() / PROGRAM_SAMPLE_COUNT.toDouble())
    }
}

private data class ProgramCanvasPlan(
    val bounds: ProgramBounds,
    val paths: List<ProgramPath>,
    val links: List<TutorVisualLinkCommand>,
    val legendLabels: List<String>,
    val hasEntities: Boolean,
    val hasMetrics: Boolean,
)

private data class ProgramPath(
    val points: List<ProgramPoint>,
)

private data class ProgramPoint(
    val x: Double,
    val y: Double,
)

private data class ProgramBounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
) {
    fun toCanvas(x: Double, y: Double, viewport: Rect): Offset {
        val normalizedX = ((x - minX) / (maxX - minX)).toFloat()
        val normalizedY = ((y - minY) / (maxY - minY)).toFloat()
        return Offset(
            x = viewport.left + normalizedX * viewport.width,
            y = viewport.bottom - normalizedY * viewport.height,
        )
    }

    companion object {
        fun from(frames: List<TutorVisualProgramFrame>): ProgramBounds {
            val points = buildList {
                frames.forEach { frame ->
                    val entityById = frame.entities.associateBy(TutorVisualEntityState::entityId)
                    frame.entities.forEach { add(it.x to it.y) }
                    frame.vectors.forEach { vector ->
                        entityById[vector.originEntityId]?.let { origin ->
                            add((origin.x + vector.x) to (origin.y + vector.y))
                        }
                    }
                }
            }
            if (points.isEmpty()) return ProgramBounds(-1.0, 1.0, -1.0, 1.0)
            val rawMinX = points.minOf { it.first }
            val rawMaxX = points.maxOf { it.first }
            val rawMinY = points.minOf { it.second }
            val rawMaxY = points.maxOf { it.second }
            val xPadding = ((rawMaxX - rawMinX) * 0.12).coerceAtLeast(0.5)
            val yPadding = ((rawMaxY - rawMinY) * 0.12).coerceAtLeast(0.5)
            return ProgramBounds(
                minX = rawMinX - xPadding,
                maxX = rawMaxX + xPadding,
                minY = rawMinY - yPadding,
                maxY = rawMaxY + yPadding,
            )
        }
    }
}

private data class ProgramValue(
    val label: String,
    val value: Double,
    val unit: String?,
)

private class ProgramPlaybackState(
    private val timeState: MutableFloatState,
    private val playingState: MutableState<Boolean>,
) {
    var timeSeconds: Float
        get() = timeState.floatValue
        set(value) {
            timeState.floatValue = value
        }

    var isPlaying: Boolean
        get() = playingState.value
        set(value) {
            playingState.value = value
        }
}

private fun Float.quantized(updatesPerSecond: Int): Float =
    floor(this * updatesPerSecond) / updatesPerSecond.toFloat()

private fun formatNumber(value: Double): String {
    val normalized = if (kotlin.math.abs(value) < 0.000_000_1) 0.0 else value
    return String.format(Locale.ROOT, "%.3f", normalized)
        .trimEnd('0')
        .trimEnd('.')
}

private const val PROGRAM_SAMPLE_COUNT = 64
private const val MAX_CANVAS_LABEL_CHARS = 16
private const val CONTROL_UPDATE_HERTZ = 30
private const val METRIC_UPDATE_HERTZ = 10
