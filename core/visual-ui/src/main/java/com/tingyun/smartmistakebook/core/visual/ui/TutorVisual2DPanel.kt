package com.tingyun.smartmistakebook.core.visual.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.TutorVisual2DConnectorElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.TutorVisualBindingProperty
import com.tingyun.smartmistakebook.core.model.TutorVisualConnectorKind
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualParticleGroupElement
import com.tingyun.smartmistakebook.core.visual.runtime.CompiledTutorVisualDocument
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisual2DLayout
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualFrame
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualLayoutEngine
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualPoint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@Composable
internal fun TutorVisual2DPanel(
    compiled: CompiledTutorVisualDocument,
    panel: TutorVisualPanel,
    frame: TutorVisualFrame,
    onTargetHit: ((String) -> Unit)?,
    modifier: Modifier,
) {
    val paper = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outline
    var selectedElementId by remember(compiled.scene.sceneId, panel.panelId) {
        mutableStateOf<String?>(null)
    }
    val panelElements = compiled.scene.elements.filter { it.panelId == panel.panelId }
    val nodes = panelElements.filterIsInstance<TutorVisual2DNodeElement>()
    val connectors = panelElements.filterIsInstance<TutorVisual2DConnectorElement>()
    val particles = panelElements.filterIsInstance<TutorVisualParticleGroupElement>()

    BoxWithConstraints(
        modifier = modifier.semantics {
            contentDescription = panel.title ?: compiled.scene.accessibilitySummary
        },
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.toDouble()
        val heightPx = with(density) { maxHeight.toPx() }.toDouble()
        val layout = remember(panel, nodes, connectors, widthPx, heightPx) {
            TutorVisualLayoutEngine.layout(
                panel = panel,
                nodes = nodes,
                connectors = connectors,
                width = widthPx,
                height = heightPx,
            )
        }
        val currentFrame by rememberUpdatedState(frame)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(layout, onTargetHit) {
                    detectTapGestures { offset ->
                        val hitTargetId = layout.hitTest(
                            TutorVisualPoint(offset.x.toDouble(), offset.y.toDouble()),
                            eligibleElementIds = layout.hitTestEligibleElementIds(currentFrame),
                            connectorProgressById =
                                layout.visibleConnectorProgressById(currentFrame),
                        )
                        selectedElementId = hitTargetId
                        hitTargetId?.let { targetId -> onTargetHit?.invoke(targetId) }
                    }
                },
        ) {
            drawRoundRect(
                color = paper,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
            )
            layout.connectors.values.forEach { connectorLayout ->
                val state = frame.elements[connectorLayout.element.elementId] ?: return@forEach
                if (!state.visible) return@forEach
                val color = when {
                    connectorLayout.element.elementId == selectedElementId || state.focused -> accent
                    state.dimmed -> outline.copy(alpha = 0.35f)
                    connectorLayout.element.kind == TutorVisualConnectorKind.FLOW -> secondary
                    else -> ink.copy(alpha = 0.76f)
                }
                val progress = state.properties[TutorVisualBindingProperty.PATH_PROGRESS]
                    ?.coerceIn(0.0, 1.0)
                    ?: 1.0
                val visiblePoints = connectorLayout.points.takeByProgress(progress)
                visiblePoints.zipWithNext().forEach { (start, end) ->
                    drawLine(
                        color = color,
                        start = Offset(start.x.toFloat(), start.y.toFloat()),
                        end = Offset(end.x.toFloat(), end.y.toFloat()),
                        strokeWidth = if (state.focused) 4.dp.toPx() else 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                if (connectorLayout.element.directed && visiblePoints.size >= 2 && progress >= 0.98) {
                    drawArrowHead(
                        start = visiblePoints[visiblePoints.lastIndex - 1],
                        end = visiblePoints.last(),
                        color = color,
                        size = 9.dp.toPx(),
                    )
                }
            }
            layout.nodes.values.forEach { nodeLayout ->
                val state = frame.elements[nodeLayout.element.elementId] ?: return@forEach
                if (!state.visible) return@forEach
                val alpha = when {
                    nodeLayout.element.elementId == selectedElementId || state.focused -> 1f
                    state.dimmed -> 0.28f
                    else -> state.properties[TutorVisualBindingProperty.OPACITY]
                        ?.coerceIn(0.0, 1.0)?.toFloat() ?: 0.9f
                }
                val fill = if (state.focused || nodeLayout.element.elementId == selectedElementId) {
                    accent.copy(alpha = 0.18f)
                } else {
                    secondary.copy(alpha = 0.11f)
                }
                drawNode(
                    node = nodeLayout.element,
                    bounds = Rect(
                        nodeLayout.bounds.left.toFloat(),
                        nodeLayout.bounds.top.toFloat(),
                        nodeLayout.bounds.right.toFloat(),
                        nodeLayout.bounds.bottom.toFloat(),
                    ),
                    fill = fill.copy(alpha = fill.alpha * alpha),
                    stroke = ink.copy(alpha = alpha),
                    liquidLevel = state.properties[TutorVisualBindingProperty.LIQUID_LEVEL],
                )
            }
            particles.forEach { particleGroup ->
                val state = frame.elements[particleGroup.elementId] ?: return@forEach
                if (!state.visible) return@forEach
                val region = layout.nodes[particleGroup.regionElementId]?.bounds ?: return@forEach
                val progress = state.properties[TutorVisualBindingProperty.PARTICLE_PROGRESS]
                    ?: frame.timeProgress
                val drawCount = particleGroup.instanceCount
                repeat(drawCount) { index ->
                    val xFraction = deterministicFraction(particleGroup.deterministicSeed, index * 2)
                    val yBase = deterministicFraction(particleGroup.deterministicSeed, index * 2 + 1)
                    val yFraction = (yBase + progress * 0.32 + index * 0.013) % 1.0
                    drawCircle(
                        color = accent.copy(alpha = if (state.dimmed) 0.22f else 0.75f),
                        radius = 2.5.dp.toPx(),
                        center = Offset(
                            (region.left + region.width * xFraction).toFloat(),
                            (region.top + region.height * yFraction).toFloat(),
                        ),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNode(
    node: TutorVisual2DNodeElement,
    bounds: Rect,
    fill: Color,
    stroke: Color,
    liquidLevel: Double?,
) {
    when (node.kind) {
        TutorVisual2DNodeKind.POINT -> drawCircle(stroke, radius = 5.dp.toPx(), center = bounds.center)
        TutorVisual2DNodeKind.CIRCLE -> {
            drawOval(fill, bounds.topLeft, bounds.size)
            drawOval(stroke, bounds.topLeft, bounds.size, style = Stroke(2.dp.toPx()))
        }
        TutorVisual2DNodeKind.POLYGON,
        TutorVisual2DNodeKind.BEZIER,
        TutorVisual2DNodeKind.FILLED_REGION,
        TutorVisual2DNodeKind.CROSS_SECTION,
        TutorVisual2DNodeKind.GEOGRAPHIC_LAYER,
        -> {
            val path = Path()
            val points = if (node.localPoints.isEmpty()) {
                listOf(
                    Offset(bounds.center.x, bounds.top),
                    Offset(bounds.right, bounds.bottom),
                    Offset(bounds.left, bounds.bottom),
                )
            } else {
                node.localPoints.map {
                    Offset(
                        bounds.left + bounds.width * it.x.toFloat(),
                        bounds.top + bounds.height * it.y.toFloat(),
                    )
                }
            }
            points.firstOrNull()?.let { point -> path.moveTo(point.x, point.y) }
            points.drop(1).forEach { point -> path.lineTo(point.x, point.y) }
            path.close()
            drawPath(path, fill)
            drawPath(path, stroke, style = Stroke(2.dp.toPx()))
        }
        TutorVisual2DNodeKind.LIQUID_LEVEL -> {
            val level = liquidLevel?.coerceIn(0.0, 1.0) ?: 0.5
            val top = bounds.bottom - bounds.height * level.toFloat()
            drawRect(fill, topLeft = Offset(bounds.left, top), size = androidx.compose.ui.geometry.Size(bounds.width, bounds.bottom - top))
            drawLine(stroke, Offset(bounds.left, top), Offset(bounds.right, top), 2.dp.toPx())
        }
        TutorVisual2DNodeKind.MEMBRANE -> {
            drawRect(fill, bounds.topLeft, bounds.size)
            val spacing = 7.dp.toPx()
            var y = bounds.top
            while (y < bounds.bottom) {
                drawLine(stroke, Offset(bounds.left, y), Offset(bounds.right, y + spacing), 1.dp.toPx())
                y += spacing
            }
            drawRect(stroke, bounds.topLeft, bounds.size, style = Stroke(2.dp.toPx()))
        }
        TutorVisual2DNodeKind.PUMP -> {
            drawCircle(fill, radius = minOf(bounds.width, bounds.height) / 2f, center = bounds.center)
            drawCircle(stroke, radius = minOf(bounds.width, bounds.height) / 2f, center = bounds.center, style = Stroke(2.dp.toPx()))
            drawLine(stroke, Offset(bounds.left, bounds.bottom), Offset(bounds.right, bounds.bottom), 2.dp.toPx())
        }
        TutorVisual2DNodeKind.ELECTRODE,
        TutorVisual2DNodeKind.PISTON,
        TutorVisual2DNodeKind.RESISTOR,
        TutorVisual2DNodeKind.MEMBRANE,
        -> Unit
        else -> {
            drawRoundRect(
                color = fill,
                topLeft = bounds.topLeft,
                size = bounds.size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
            )
            drawRoundRect(
                color = stroke,
                topLeft = bounds.topLeft,
                size = bounds.size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrowHead(
    start: TutorVisualPoint,
    end: TutorVisualPoint,
    color: Color,
    size: Float,
) {
    val angle = atan2(end.y - start.y, end.x - start.x)
    val left = Offset(
        (end.x - size * cos(angle - PI / 6.0)).toFloat(),
        (end.y - size * sin(angle - PI / 6.0)).toFloat(),
    )
    val right = Offset(
        (end.x - size * cos(angle + PI / 6.0)).toFloat(),
        (end.y - size * sin(angle + PI / 6.0)).toFloat(),
    )
    drawLine(color, Offset(end.x.toFloat(), end.y.toFloat()), left, 2.dp.toPx())
    drawLine(color, Offset(end.x.toFloat(), end.y.toFloat()), right, 2.dp.toPx())
}

private fun List<TutorVisualPoint>.takeByProgress(progress: Double): List<TutorVisualPoint> {
    if (progress >= 1.0 || size < 2) return this
    if (progress <= 0.0) return take(1)
    val lengths = zipWithNext().map { (start, end) ->
        kotlin.math.hypot(end.x - start.x, end.y - start.y)
    }
    val target = lengths.sum() * progress
    var consumed = 0.0
    val result = mutableListOf(first())
    zipWithNext().forEachIndexed { index, (start, end) ->
        val segment = lengths[index]
        if (consumed + segment <= target) {
            result += end
            consumed += segment
        } else if (segment > 0.0 && consumed < target) {
            val fraction = (target - consumed) / segment
            result += TutorVisualPoint(
                start.x + (end.x - start.x) * fraction,
                start.y + (end.y - start.y) * fraction,
            )
            return result
        }
    }
    return result
}

private fun com.tingyun.smartmistakebook.core.visual.runtime.TutorVisual2DLayout
    .hitTestEligibleElementIds(frame: TutorVisualFrame): Set<String> =
    (nodes.keys + connectors.keys).filterTo(hashSetOf()) { elementId ->
        val state = frame.elements[elementId] ?: return@filterTo false
        state.visible &&
            state.properties[TutorVisualBindingProperty.OPACITY]
                ?.let { opacity -> opacity > MIN_INTERACTIVE_OPACITY } != false
    }

private fun com.tingyun.smartmistakebook.core.visual.runtime.TutorVisual2DLayout
    .visibleConnectorProgressById(frame: TutorVisualFrame): Map<String, Double> =
    connectors.keys.associateWith { elementId ->
        frame.elements[elementId]
            ?.properties
            ?.get(TutorVisualBindingProperty.PATH_PROGRESS)
            ?: 1.0
    }

private fun deterministicFraction(seed: Int, index: Int): Double {
    var value = seed.toLong() xor (index.toLong() * 0x9E3779B9L)
    value = (value xor (value ushr 16)) * 0x45d9f3b
    value = (value xor (value ushr 16)) * 0x45d9f3b
    value = value xor (value ushr 16)
    return (value and 0xffff).toDouble() / 65535.0
}

private const val MIN_INTERACTIVE_OPACITY = 0.01
