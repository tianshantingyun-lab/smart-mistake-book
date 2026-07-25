package com.tingyun.smartmistakebook.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.TutorDiagramAnchor
import com.tingyun.smartmistakebook.core.model.TutorDiagramEdge
import com.tingyun.smartmistakebook.core.model.TutorDiagramEdgeStyle
import com.tingyun.smartmistakebook.core.model.TutorDiagramNode
import com.tingyun.smartmistakebook.core.model.TutorDiagramNodeShape
import com.tingyun.smartmistakebook.core.model.TutorSpatialDiagramScene
import com.tingyun.smartmistakebook.core.model.isCircuitComponent
import com.tingyun.smartmistakebook.core.model.isCircuitDiagram
import kotlin.math.sqrt

@Composable
internal fun SpatialDiagramScene(scene: TutorSpatialDiagramScene) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Paper,
            contentColor = Ink,
            shape = RoundedCornerShape(SmartDimens.SurfaceRadius),
            border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(252.dp)
                    .padding(8.dp),
            ) {
                DiagramEdges(scene)
                DiagramNodeGrid(scene)
                DiagramEdgeLabels(scene)
            }
        }
        scene.captionMarkdown?.let { caption ->
            SafeMarkdownText(
                markdown = caption,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(JadeSoft, RoundedCornerShape(SmartDimens.ChipRadius))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DiagramEdges(scene: TutorSpatialDiagramScene) {
    val nodesById = scene.nodes.associateBy(TutorDiagramNode::nodeId)
    val accessibilityText = if (scene.isCircuitDiagram) {
        "电路包含" + scene.nodes
            .filter { it.shape.isCircuitComponent }
            .joinToString("、", transform = TutorDiagramNode::label)
    } else {
        "图中关系：" + scene.edges.joinToString("；") { edge ->
            val start = nodesById.getValue(edge.fromNodeId).label
            val end = nodesById.getValue(edge.toNodeId).label
            buildString {
                append(start)
                append(if (edge.style == TutorDiagramEdgeStyle.ARROW) " 指向 " else " 连接 ")
                append(end)
                edge.label?.let { append("，$it") }
            }
        }
    }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .testTag("tutor-spatial-canvas-${scene.sceneId}")
            .semantics { contentDescription = accessibilityText },
    ) {
        val arrowSize = 9.dp.toPx()
        val strokeWidth = 2.dp.toPx()
        scene.edges.forEach { edge ->
            val fromNode = nodesById.getValue(edge.fromNodeId)
            val toNode = nodesById.getValue(edge.toNodeId)
            val rawStart = fromNode.anchor.toOffset(size.width, size.height)
            val rawEnd = toNode.anchor.toOffset(size.width, size.height)
            val rawDelta = rawEnd - rawStart
            val rawLength = sqrt(rawDelta.x * rawDelta.x + rawDelta.y * rawDelta.y)
            if (rawLength <= 0f) return@forEach
            val direction = Offset(rawDelta.x / rawLength, rawDelta.y / rawLength)
            val start = rawStart + direction * fromNode.shape.edgeInset().toPx()
            val end = rawEnd - direction * toNode.shape.edgeInset().toPx()
            drawLine(
                color = if (edge.style == TutorDiagramEdgeStyle.ARROW) JadeActive else JadeMuted,
                start = start,
                end = end,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
                pathEffect = if (edge.style == TutorDiagramEdgeStyle.DASHED) {
                    PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))
                } else {
                    null
                },
            )
            if (edge.style == TutorDiagramEdgeStyle.ARROW) {
                val delta = start - end
                val length = sqrt(delta.x * delta.x + delta.y * delta.y)
                if (length > 0f) {
                    val unitX = delta.x / length
                    val unitY = delta.y / length
                    val perpendicularX = -unitY
                    val perpendicularY = unitX
                    val arrowBase = Offset(
                        x = end.x + unitX * arrowSize,
                        y = end.y + unitY * arrowSize,
                    )
                    drawLine(
                        color = JadeActive,
                        start = end,
                        end = Offset(
                            x = arrowBase.x + perpendicularX * arrowSize * 0.55f,
                            y = arrowBase.y + perpendicularY * arrowSize * 0.55f,
                        ),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = JadeActive,
                        start = end,
                        end = Offset(
                            x = arrowBase.x - perpendicularX * arrowSize * 0.55f,
                            y = arrowBase.y - perpendicularY * arrowSize * 0.55f,
                        ),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagramNodeGrid(scene: TutorSpatialDiagramScene) {
    val nodesByAnchor = scene.nodes.associateBy(TutorDiagramNode::anchor)
    val nodesById = scene.nodes.associateBy(TutorDiagramNode::nodeId)
    val arrowTargets = scene.edges
        .filter { it.style == TutorDiagramEdgeStyle.ARROW }
        .mapTo(mutableSetOf(), TutorDiagramEdge::toNodeId)
    val circuitOrientations = scene.nodes
        .filter { it.shape.isCircuitComponent }
        .associate { component ->
            val neighborAnchors = scene.edges.mapNotNull { edge ->
                when (component.nodeId) {
                    edge.fromNodeId -> nodesById.getValue(edge.toNodeId).anchor
                    edge.toNodeId -> nodesById.getValue(edge.fromNodeId).anchor
                    else -> null
                }
            }
            component.nodeId to component.anchor.circuitOrientation(neighborAnchors)
        }
    Column(modifier = Modifier.fillMaxSize()) {
        repeat(3) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                repeat(3) { column ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        nodesByAnchor[anchorAt(row, column)]?.let { node ->
                            key(node.nodeId) {
                                DiagramNodeView(
                                    node = node,
                                    placePointLabelOutward = node.nodeId in arrowTargets,
                                    circuitOrientation = circuitOrientations[node.nodeId],
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagramNodeView(
    node: TutorDiagramNode,
    placePointLabelOutward: Boolean,
    circuitOrientation: CircuitOrientation?,
) {
    val taggedModifier = Modifier.testTag("tutor-spatial-node-${node.nodeId}")
    val modifier = if (node.shape == TutorDiagramNodeShape.JUNCTION) {
        taggedModifier
    } else {
        taggedModifier.semantics {
            contentDescription = "图中${node.anchor.readablePlace()}：${node.label}"
        }
    }
    when (node.shape) {
        TutorDiagramNodeShape.POINT -> Box(
            modifier = modifier.size(width = 96.dp, height = 50.dp),
        ) {
            val (labelOffsetX, labelOffsetY) = if (placePointLabelOutward) {
                node.anchor.outwardLabelOffset()
            } else {
                0.dp to 18.dp
            }
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .background(JadeActive, CircleShape)
                    .border(1.dp, Paper, CircleShape)
                    .align(Alignment.Center),
            )
            Text(
                text = node.label,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = labelOffsetX, y = labelOffsetY),
                color = Ink,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        TutorDiagramNodeShape.CIRCLE -> Surface(
            modifier = modifier.size(50.dp),
            color = JadeSoft,
            contentColor = Ink,
            shape = CircleShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, JadeActive),
        ) {
            Box(
                modifier = Modifier.padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = node.label,
                    color = Ink,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        TutorDiagramNodeShape.BLOCK -> Surface(
            modifier = modifier
                .widthIn(max = 104.dp)
                .sizeIn(minWidth = 52.dp, minHeight = 38.dp),
            color = JadeSoft,
            contentColor = Ink,
            shape = RoundedCornerShape(SmartDimens.ChipRadius),
            border = androidx.compose.foundation.BorderStroke(1.dp, JadeActive),
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                DiagramNodeLabel(node.label)
            }
        }

        TutorDiagramNodeShape.BATTERY,
        TutorDiagramNodeShape.RESISTOR,
        TutorDiagramNodeShape.LAMP,
        TutorDiagramNodeShape.SWITCH_OPEN,
        TutorDiagramNodeShape.SWITCH_CLOSED,
        TutorDiagramNodeShape.AMMETER,
        TutorDiagramNodeShape.VOLTMETER,
        -> CircuitComponentNode(
            node = node,
            orientation = circuitOrientation ?: CircuitOrientation.HORIZONTAL,
            modifier = modifier,
        )

        TutorDiagramNodeShape.JUNCTION -> Box(
            modifier = modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(JadeActive, CircleShape),
            )
        }
    }
}

@Composable
private fun CircuitComponentNode(
    node: TutorDiagramNode,
    orientation: CircuitOrientation,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.size(width = 84.dp, height = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(52.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 2.dp.toPx()
                val centerY = size.height / 2f
                val symbolColor = JadeActive
                rotate(if (orientation == CircuitOrientation.VERTICAL) 90f else 0f) {
                    when (node.shape) {
                        TutorDiagramNodeShape.BATTERY -> {
                            val longPlateX = size.width / 2f - 6.dp.toPx()
                            val shortPlateX = size.width / 2f + 6.dp.toPx()
                            drawLine(
                                JadeMuted,
                                Offset(0f, centerY),
                                Offset(longPlateX, centerY),
                                strokeWidth,
                            )
                            drawLine(
                                symbolColor,
                                Offset(longPlateX, centerY - 15.dp.toPx()),
                                Offset(longPlateX, centerY + 15.dp.toPx()),
                                strokeWidth,
                            )
                            drawLine(
                                symbolColor,
                                Offset(shortPlateX, centerY - 9.dp.toPx()),
                                Offset(shortPlateX, centerY + 9.dp.toPx()),
                                strokeWidth,
                            )
                            drawLine(
                                JadeMuted,
                                Offset(shortPlateX, centerY),
                                Offset(size.width, centerY),
                                strokeWidth,
                            )
                        }

                        TutorDiagramNodeShape.RESISTOR -> {
                            val left = 10.dp.toPx()
                            val right = size.width - 10.dp.toPx()
                            val halfHeight = 7.dp.toPx()
                            drawLine(
                                JadeMuted,
                                Offset(0f, centerY),
                                Offset(left, centerY),
                                strokeWidth,
                            )
                            drawRect(
                                color = symbolColor,
                                topLeft = Offset(left, centerY - halfHeight),
                                size = Size(right - left, halfHeight * 2f),
                                style = Stroke(strokeWidth),
                            )
                            drawLine(
                                JadeMuted,
                                Offset(right, centerY),
                                Offset(size.width, centerY),
                                strokeWidth,
                            )
                        }

                        TutorDiagramNodeShape.LAMP -> {
                            val radius = 14.dp.toPx()
                            val center = Offset(size.width / 2f, centerY)
                            drawLine(
                                JadeMuted,
                                Offset(0f, centerY),
                                Offset(center.x - radius, centerY),
                                strokeWidth,
                            )
                            drawCircle(symbolColor, radius, center, style = Stroke(strokeWidth))
                            val crossOffset = radius * 0.62f
                            drawLine(
                                symbolColor,
                                center - Offset(crossOffset, crossOffset),
                                center + Offset(crossOffset, crossOffset),
                                strokeWidth,
                            )
                            drawLine(
                                symbolColor,
                                center + Offset(-crossOffset, crossOffset),
                                center + Offset(crossOffset, -crossOffset),
                                strokeWidth,
                            )
                            drawLine(
                                JadeMuted,
                                Offset(center.x + radius, centerY),
                                Offset(size.width, centerY),
                                strokeWidth,
                            )
                        }

                        TutorDiagramNodeShape.SWITCH_OPEN,
                        TutorDiagramNodeShape.SWITCH_CLOSED,
                        -> {
                            val left = 12.dp.toPx()
                            val right = size.width - 12.dp.toPx()
                            val terminalRadius = 2.5.dp.toPx()
                            drawLine(
                                JadeMuted,
                                Offset(0f, centerY),
                                Offset(left, centerY),
                                strokeWidth,
                            )
                            drawLine(
                                JadeMuted,
                                Offset(right, centerY),
                                Offset(size.width, centerY),
                                strokeWidth,
                            )
                            drawCircle(symbolColor, terminalRadius, Offset(left, centerY))
                            drawCircle(symbolColor, terminalRadius, Offset(right, centerY))
                            val leverEnd = if (node.shape == TutorDiagramNodeShape.SWITCH_OPEN) {
                                Offset(right - 3.dp.toPx(), centerY - 12.dp.toPx())
                            } else {
                                Offset(right, centerY)
                            }
                            drawLine(
                                symbolColor,
                                Offset(left, centerY),
                                leverEnd,
                                strokeWidth,
                                StrokeCap.Round,
                            )
                        }

                        TutorDiagramNodeShape.AMMETER,
                        TutorDiagramNodeShape.VOLTMETER,
                        -> {
                            val radius = 14.dp.toPx()
                            val centerX = size.width / 2f
                            drawLine(
                                JadeMuted,
                                Offset(0f, centerY),
                                Offset(centerX - radius, centerY),
                                strokeWidth,
                            )
                            drawCircle(
                                symbolColor,
                                radius,
                                Offset(centerX, centerY),
                                style = Stroke(strokeWidth),
                            )
                            drawLine(
                                JadeMuted,
                                Offset(centerX + radius, centerY),
                                Offset(size.width, centerY),
                                strokeWidth,
                            )
                        }

                        else -> Unit
                    }
                }
            }
            if (node.shape == TutorDiagramNodeShape.AMMETER ||
                node.shape == TutorDiagramNodeShape.VOLTMETER
            ) {
                Text(
                    text = if (node.shape == TutorDiagramNodeShape.AMMETER) "A" else "V",
                    color = JadeActive,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            text = node.label,
            color = InkSecondary,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DiagramNodeLabel(label: String) {
    Text(
        text = label,
        color = Ink,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DiagramEdgeLabels(scene: TutorSpatialDiagramScene) {
    val nodesById = scene.nodes.associateBy(TutorDiagramNode::nodeId)
    val labeledEdges = scene.edges.filter { it.label != null }
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            labeledEdges.forEach { edge ->
                Surface(
                    modifier = Modifier.testTag("tutor-spatial-edge-label-${edge.edgeId}"),
                    color = Paper,
                    contentColor = JadeActive,
                    shape = RoundedCornerShape(SmartDimens.ChipRadius),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JadeMuted),
                ) {
                    Text(
                        text = requireNotNull(edge.label),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(looseConstraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            labeledEdges.forEachIndexed { index, edge ->
                val start = nodesById.getValue(edge.fromNodeId).anchor.toOffset(
                    constraints.maxWidth.toFloat(),
                    constraints.maxHeight.toFloat(),
                )
                val end = nodesById.getValue(edge.toNodeId).anchor.toOffset(
                    constraints.maxWidth.toFloat(),
                    constraints.maxHeight.toFloat(),
                )
                val placeable = placeables[index]
                val x = ((start.x + end.x) / 2f - placeable.width / 2f)
                    .toInt()
                    .coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                val y = ((start.y + end.y) / 2f - placeable.height / 2f)
                    .toInt()
                    .coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
                placeable.placeRelative(x, y)
            }
        }
    }
}

private fun TutorDiagramAnchor.toOffset(width: Float, height: Float): Offset {
    val (row, column) = gridPosition()
    return Offset(
        x = width * (column + 0.5f) / 3f,
        y = height * (row + 0.5f) / 3f,
    )
}

private fun TutorDiagramAnchor.gridPosition(): Pair<Int, Int> = when (this) {
    TutorDiagramAnchor.TOP_LEFT -> 0 to 0
    TutorDiagramAnchor.TOP -> 0 to 1
    TutorDiagramAnchor.TOP_RIGHT -> 0 to 2
    TutorDiagramAnchor.LEFT -> 1 to 0
    TutorDiagramAnchor.CENTER -> 1 to 1
    TutorDiagramAnchor.RIGHT -> 1 to 2
    TutorDiagramAnchor.BOTTOM_LEFT -> 2 to 0
    TutorDiagramAnchor.BOTTOM -> 2 to 1
    TutorDiagramAnchor.BOTTOM_RIGHT -> 2 to 2
}

private fun TutorDiagramAnchor.readablePlace(): String = when (this) {
    TutorDiagramAnchor.TOP_LEFT -> "左上方"
    TutorDiagramAnchor.TOP -> "上方"
    TutorDiagramAnchor.TOP_RIGHT -> "右上方"
    TutorDiagramAnchor.LEFT -> "左侧"
    TutorDiagramAnchor.CENTER -> "中央"
    TutorDiagramAnchor.RIGHT -> "右侧"
    TutorDiagramAnchor.BOTTOM_LEFT -> "左下方"
    TutorDiagramAnchor.BOTTOM -> "下方"
    TutorDiagramAnchor.BOTTOM_RIGHT -> "右下方"
}

private fun TutorDiagramAnchor.outwardLabelOffset() = when (this) {
    TutorDiagramAnchor.TOP_LEFT -> -16.dp to -16.dp
    TutorDiagramAnchor.TOP -> 0.dp to -18.dp
    TutorDiagramAnchor.TOP_RIGHT -> 16.dp to -16.dp
    TutorDiagramAnchor.LEFT -> -18.dp to 0.dp
    TutorDiagramAnchor.CENTER -> 0.dp to 18.dp
    TutorDiagramAnchor.RIGHT -> 18.dp to 0.dp
    TutorDiagramAnchor.BOTTOM_LEFT -> -16.dp to 16.dp
    TutorDiagramAnchor.BOTTOM -> 0.dp to 18.dp
    TutorDiagramAnchor.BOTTOM_RIGHT -> 16.dp to 16.dp
}

private enum class CircuitOrientation {
    HORIZONTAL,
    VERTICAL,
}

private fun TutorDiagramAnchor.circuitOrientation(
    neighborAnchors: List<TutorDiagramAnchor>,
): CircuitOrientation {
    val row = gridPosition().first
    return if (neighborAnchors.all { it.gridPosition().first == row }) {
        CircuitOrientation.HORIZONTAL
    } else {
        CircuitOrientation.VERTICAL
    }
}

private fun TutorDiagramNodeShape.edgeInset() = when (this) {
    TutorDiagramNodeShape.POINT -> 7.dp
    TutorDiagramNodeShape.CIRCLE -> 26.dp
    TutorDiagramNodeShape.BLOCK -> 28.dp
    TutorDiagramNodeShape.JUNCTION -> 4.dp
    TutorDiagramNodeShape.BATTERY,
    TutorDiagramNodeShape.RESISTOR,
    TutorDiagramNodeShape.LAMP,
    TutorDiagramNodeShape.SWITCH_OPEN,
    TutorDiagramNodeShape.SWITCH_CLOSED,
    TutorDiagramNodeShape.AMMETER,
    TutorDiagramNodeShape.VOLTMETER,
    -> 26.dp
}

private fun anchorAt(row: Int, column: Int): TutorDiagramAnchor =
    TutorDiagramAnchor.entries.single { it.gridPosition() == (row to column) }
