package com.tingyun.smartmistakebook.core.visual.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
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
    modifier: Modifier,
    visualConstraints: com.tingyun.smartmistakebook.core.domain.visual.VisualProblemConstraints? = null,
    onVisualAttempt: ((com.tingyun.smartmistakebook.core.model.VisualInteractionAttempt) -> Unit)? = null,
) {
    val paper = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outline
    var selectedElementId by remember(compiled.scene.sceneId, panel.panelId) {
        mutableStateOf<String?>(null)
    }
    var interactionFeedback by remember(compiled.scene.sceneId, panel.panelId) {
        mutableStateOf<String?>(null)
    }
    val visualEvaluator = remember(compiled.scene.sceneId) {
        com.tingyun.smartmistakebook.core.domain.visual.LocalVisualConstraintEvaluator()
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
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(layout) {
                    detectTapGestures { offset ->
                        selectedElementId = layout.hitTest(
                            TutorVisualPoint(offset.x.toDouble(), offset.y.toDouble()),
                        )
                    }
                }
                .pointerInput(layout, visualConstraints) {
                    var draggedElementId: String? = null
                    var lastX = 0.0
                    var lastY = 0.0
                    detectDragGestures(
                        onDragStart = { offset ->
                            draggedElementId = layout.hitTest(
                                TutorVisualPoint(offset.x.toDouble(), offset.y.toDouble()),
                            )
                        },
                        onDragEnd = {
                            val elementId = draggedElementId
                            val constraintsSpec = visualConstraints
                            if (elementId != null && constraintsSpec != null) {
                                val action = com.tingyun.smartmistakebook.core.model.VisualStudentAction.DragPoint(
                                    elementId = elementId,
                                    toX = lastX,
                                    toY = lastY,
                                )
                                val result = visualEvaluator.evaluate(action, constraintsSpec)
                                interactionFeedback = result.feedback
                                val attempt = visualEvaluator.toAttempt(
                                    attemptId = "visual-" + System.nanoTime(),
                                    action = action,
                                    constraints = constraintsSpec,
                                    result = result,
                                    atEpochMillis = System.currentTimeMillis(),
                                )
                                onVisualAttempt?.invoke(attempt)
                            }
                            draggedElementId = null
                        },
                    ) { change, _ ->
                        lastX = change.position.x.toDouble()
                        lastY = change.position.y.toDouble()
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
                val drawCount = particleGroup.instanceCount.coerceAtMost(MAX_DRAWN_PARTICLES)
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
        TutorVisualSpotlightOverlay(
            focusElementId = frame.step.primaryRelationElementId,
            layout = layout,
            widthPx = widthPx,
            heightPx = heightPx,
            modifier = Modifier.matchParentSize(),
        )
    }
}

private val defaultRendererRegistry = TutorVisual2DRendererRegistry(defaultNodeRenderers)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNode(
    node: TutorVisual2DNodeElement,
    bounds: Rect,
    fill: Color,
    stroke: Color,
    liquidLevel: Double?,
) {
    val context = NodeRenderContext(
        node = node,
        bounds = bounds,
        fill = fill,
        stroke = stroke,
        alpha = 1f,
        liquidLevel = liquidLevel,
        isSelected = false,
    )
    with(defaultRendererRegistry) { renderNode(context) }
    // Draw node label below the node
    node.label?.let { label ->
        if (label.isNotBlank()) {
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    textSize = 10.dp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }
                drawText(
                    label,
                    bounds.center.x,
                    bounds.bottom + 14.dp.toPx(),
                    paint,
                )
            }
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

private fun deterministicFraction(seed: Int, index: Int): Double {
    var value = seed.toLong() xor (index.toLong() * 0x9E3779B9L)
    value = (value xor (value ushr 16)) * 0x45d9f3b
    value = (value xor (value ushr 16)) * 0x45d9f3b
    value = value xor (value ushr 16)
    return (value and 0xffff).toDouble() / 65535.0
}

private const val MAX_DRAWN_PARTICLES = 300
