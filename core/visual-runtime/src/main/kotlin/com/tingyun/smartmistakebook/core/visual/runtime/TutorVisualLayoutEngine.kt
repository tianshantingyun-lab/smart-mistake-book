package com.tingyun.smartmistakebook.core.visual.runtime

import com.tingyun.smartmistakebook.core.model.TutorVisual2DConnectorElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisualAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualGeometry3DElement
import com.tingyun.smartmistakebook.core.model.TutorVisualLatticeElement
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualRouteKind
import com.tingyun.smartmistakebook.core.model.TutorVisualSizeClass
import com.tingyun.smartmistakebook.core.model.TutorVisualTransform3D
import com.tingyun.smartmistakebook.core.model.TutorVisualVector3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class TutorVisualPoint(
    val x: Double,
    val y: Double,
)

data class TutorVisualRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val center: TutorVisualPoint get() = TutorVisualPoint((left + right) / 2.0, (top + bottom) / 2.0)

    fun intersects(other: TutorVisualRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun inflated(amount: Double) = TutorVisualRect(
        left - amount,
        top - amount,
        right + amount,
        bottom + amount,
    )
}

data class TutorVisualNodeLayout(
    val element: TutorVisual2DNodeElement,
    val bounds: TutorVisualRect,
    val labelBounds: TutorVisualRect?,
)

data class TutorVisualConnectorLayout(
    val element: TutorVisual2DConnectorElement,
    val points: List<TutorVisualPoint>,
    val labelPosition: TutorVisualPoint?,
)

data class TutorVisual2DLayout(
    val nodes: Map<String, TutorVisualNodeLayout>,
    val connectors: Map<String, TutorVisualConnectorLayout>,
) {
    fun hitTest(point: TutorVisualPoint): String? {
        nodes.values.reversed().firstOrNull { node ->
            point.x in node.bounds.left..node.bounds.right &&
                point.y in node.bounds.top..node.bounds.bottom
        }?.let { return it.element.elementId }
        return connectors.values.firstOrNull { connector ->
            connector.points.zipWithNext().any { (start, end) ->
                point.distanceToSegment(start, end) <= 12.0
            }
        }?.element?.elementId
    }
}

object TutorVisualLayoutEngine {
    fun layout(
        panel: TutorVisualPanel,
        nodes: List<TutorVisual2DNodeElement>,
        connectors: List<TutorVisual2DConnectorElement>,
        width: Double,
        height: Double,
    ): TutorVisual2DLayout {
        require(panel.kind == TutorVisualPanelKind.DIAGRAM_2D)
        require(width > 0.0 && height > 0.0)
        val margin = min(width, height) * 0.06
        val availableWidth = max(1.0, width - margin * 2.0)
        val availableHeight = max(1.0, height - margin * 2.0)
        val sortedNodes = nodes.sortedWith(
            compareBy<TutorVisual2DNodeElement> { it.layout.order }.thenBy { it.elementId },
        )
        val columns = max(1, kotlin.math.ceil(kotlin.math.sqrt(sortedNodes.size.toDouble())).toInt())
        val rows = max(1, kotlin.math.ceil(sortedNodes.size / columns.toDouble()).toInt())
        val mutableBounds = sortedNodes.mapIndexed { index, node ->
            val (nodeWidth, nodeHeight) = node.sizeClass.resolve(width, height)
            val autoColumn = index % columns
            val autoRow = index / columns
            val autoX = (autoColumn + 0.5) / columns
            val autoY = (autoRow + 0.5) / rows
            val anchored = node.layout.anchor.resolve()
            val centerX = margin + availableWidth * (
                node.layout.preferredX ?: anchored?.x ?: autoX
                )
            val centerY = margin + availableHeight * (
                node.layout.preferredY ?: anchored?.y ?: autoY
                )
            node.elementId to TutorVisualRect(
                centerX - nodeWidth / 2.0,
                centerY - nodeHeight / 2.0,
                centerX + nodeWidth / 2.0,
                centerY + nodeHeight / 2.0,
            ).clamped(margin, width - margin, margin, height - margin)
        }.toMap().toMutableMap()

        repeat(24) {
            var changed = false
            sortedNodes.forEachIndexed { index, node ->
                val current = mutableBounds.getValue(node.elementId)
                sortedNodes.drop(index + 1).forEach { other ->
                    val otherBounds = mutableBounds.getValue(other.elementId)
                    val padded = current.inflated(10.0)
                    if (padded.intersects(otherBounds.inflated(10.0))) {
                        val dx = padded.right - otherBounds.left
                        val dy = padded.bottom - otherBounds.top
                        val moveHorizontally = dx < dy
                        val moved = if (moveHorizontally) {
                            otherBounds.translate(dx.coerceAtLeast(4.0), 0.0)
                        } else {
                            otherBounds.translate(0.0, dy.coerceAtLeast(4.0))
                        }
                        mutableBounds[other.elementId] = moved.clamped(
                            margin,
                            width - margin,
                            margin,
                            height - margin,
                        )
                        changed = true
                    }
                }
            }
            if (!changed) return@repeat
        }

        val occupiedLabels = mutableListOf<TutorVisualRect>()
        val nodeLayouts = sortedNodes.associate { node ->
            val bounds = mutableBounds.getValue(node.elementId)
            val labelBounds = node.label?.let { label ->
                placeLabel(
                    center = bounds.center,
                    estimatedWidth = min(160.0, max(36.0, label.length * 9.0)),
                    height = 26.0,
                    obstacles = mutableBounds.values.toList() + occupiedLabels,
                    viewportWidth = width,
                    viewportHeight = height,
                ).also(occupiedLabels::add)
            }
            node.elementId to TutorVisualNodeLayout(node, bounds, labelBounds)
        }
        val obstacleRects = nodeLayouts.values.map { it.bounds.inflated(6.0) }
        val connectorLayouts = connectors.associate { connector ->
            val from = nodeLayouts.getValue(connector.from.elementId).bounds
            val to = nodeLayouts.getValue(connector.to.elementId).bounds
            val points = route(connector, from, to, obstacleRects)
            val midpoint = points.polylineMidpoint()
            connector.elementId to TutorVisualConnectorLayout(
                element = connector,
                points = points,
                labelPosition = connector.label?.let { midpoint },
            )
        }
        return TutorVisual2DLayout(nodeLayouts, connectorLayouts)
    }

    private fun route(
        connector: TutorVisual2DConnectorElement,
        from: TutorVisualRect,
        to: TutorVisualRect,
        obstacles: List<TutorVisualRect>,
    ): List<TutorVisualPoint> {
        val start = from.edgePointToward(to.center, connector.from.side)
        val end = to.edgePointToward(from.center, connector.to.side)
        return when (connector.route) {
            TutorVisualRouteKind.DIRECT -> listOf(start, end)
            TutorVisualRouteKind.POLYLINE,
            TutorVisualRouteKind.BEZIER,
            -> listOf(start) + connector.controlPoints.map { TutorVisualPoint(it.x, it.y) } + end
            TutorVisualRouteKind.AUTO_ORTHOGONAL -> {
                val horizontalFirst = listOf(
                    start,
                    TutorVisualPoint(end.x, start.y),
                    end,
                )
                val verticalFirst = listOf(
                    start,
                    TutorVisualPoint(start.x, end.y),
                    end,
                )
                listOf(horizontalFirst, verticalFirst).minBy { candidate ->
                    routeIntersectionScore(candidate, obstacles, from, to)
                }.removeDuplicatePoints()
            }
        }
    }

    private fun routeIntersectionScore(
        points: List<TutorVisualPoint>,
        obstacles: List<TutorVisualRect>,
        from: TutorVisualRect,
        to: TutorVisualRect,
    ): Double {
        val crossings = obstacles
            .filterNot { it == from || it == to }
            .sumOf { obstacle ->
                points.zipWithNext().count { (start, end) ->
                    segmentIntersectsRect(start, end, obstacle)
                }
            }
        val length = points.zipWithNext().sumOf { (start, end) ->
            kotlin.math.abs(start.x - end.x) + kotlin.math.abs(start.y - end.y)
        }
        return crossings * 100_000.0 + length
    }

    private fun placeLabel(
        center: TutorVisualPoint,
        estimatedWidth: Double,
        height: Double,
        obstacles: List<TutorVisualRect>,
        viewportWidth: Double,
        viewportHeight: Double,
    ): TutorVisualRect {
        val offsets = listOf(
            TutorVisualPoint(0.0, -34.0),
            TutorVisualPoint(0.0, 34.0),
            TutorVisualPoint(estimatedWidth / 2.0 + 12.0, 0.0),
            TutorVisualPoint(-estimatedWidth / 2.0 - 12.0, 0.0),
        )
        return offsets.map { offset ->
            TutorVisualRect(
                center.x + offset.x - estimatedWidth / 2.0,
                center.y + offset.y - height / 2.0,
                center.x + offset.x + estimatedWidth / 2.0,
                center.y + offset.y + height / 2.0,
            ).clamped(2.0, viewportWidth - 2.0, 2.0, viewportHeight - 2.0)
        }.minBy { candidate ->
            obstacles.count(candidate::intersects)
        }
    }
}

data class TutorVisual3DInstance(
    val elementId: String,
    val label: String?,
    val transform: TutorVisualTransform3D,
)

object TutorVisualLatticeCompiler {
    fun expand(element: TutorVisualLatticeElement): List<TutorVisual3DInstance> {
        val a = element.latticeVectors[0]
        val b = element.latticeVectors[1]
        val c = element.latticeVectors[2]
        return buildList {
            for (ix in 0 until element.repeat.x) {
                for (iy in 0 until element.repeat.y) {
                    for (iz in 0 until element.repeat.z) {
                        element.basis.forEachIndexed { basisIndex, site ->
                            val fx = site.fractionalCoordinate.x + ix
                            val fy = site.fractionalCoordinate.y + iy
                            val fz = site.fractionalCoordinate.z + iz
                            val position = TutorVisualVector3(
                                a.x * fx + b.x * fy + c.x * fz,
                                a.y * fx + b.y * fy + c.y * fz,
                                a.z * fx + b.z * fy + c.z * fz,
                            )
                            add(
                                TutorVisual3DInstance(
                                    elementId = "${element.elementId}-$ix-$iy-$iz-$basisIndex",
                                    label = site.label,
                                    transform = TutorVisualTransform3D(
                                        translation = position,
                                        scale = TutorVisualVector3(
                                            site.radiusScale,
                                            site.radiusScale,
                                            site.radiusScale,
                                        ),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

data class TutorVisualProjected3D(
    val elementId: String,
    val label: String?,
    val center: TutorVisualPoint,
    val depth: Double,
    val scale: Double,
)

object TutorVisualFallbackProjector {
    fun project(
        elements: List<TutorVisualGeometry3DElement>,
        latticeInstances: List<TutorVisual3DInstance>,
        azimuthDegrees: Double,
        elevationDegrees: Double,
        width: Double,
        height: Double,
    ): List<TutorVisualProjected3D> {
        val instances = buildList {
            elements.forEach { element ->
                if (element.instanceTransforms.isEmpty()) {
                    add(TutorVisual3DInstance(element.elementId, element.label, element.transform))
                } else {
                    element.instanceTransforms.forEachIndexed { index, instance ->
                        add(TutorVisual3DInstance("${element.elementId}-$index", element.label, instance))
                    }
                }
            }
            addAll(latticeInstances)
        }
        if (instances.isEmpty()) return emptyList()
        val azimuth = Math.toRadians(azimuthDegrees)
        val elevation = Math.toRadians(elevationDegrees)
        val rotated = instances.map { instance ->
            val point = instance.transform.translation
            val x1 = point.x * cos(azimuth) - point.z * sin(azimuth)
            val z1 = point.x * sin(azimuth) + point.z * cos(azimuth)
            val y1 = point.y * cos(elevation) - z1 * sin(elevation)
            val depth = point.y * sin(elevation) + z1 * cos(elevation)
            Triple(instance, TutorVisualPoint(x1, y1), depth)
        }
        val minX = rotated.minOf { it.second.x }
        val maxX = rotated.maxOf { it.second.x }
        val minY = rotated.minOf { it.second.y }
        val maxY = rotated.maxOf { it.second.y }
        val spanX = max(1e-6, maxX - minX)
        val spanY = max(1e-6, maxY - minY)
        val contentScale = min(width * 0.8 / spanX, height * 0.8 / spanY)
        return rotated.sortedBy { it.third }.map { (instance, point, depth) ->
            TutorVisualProjected3D(
                elementId = instance.elementId,
                label = instance.label,
                center = TutorVisualPoint(
                    width / 2.0 + (point.x - (minX + maxX) / 2.0) * contentScale,
                    height / 2.0 - (point.y - (minY + maxY) / 2.0) * contentScale,
                ),
                depth = depth,
                scale = instance.transform.scale.x,
            )
        }
    }
}

private fun TutorVisualSizeClass.resolve(width: Double, height: Double): Pair<Double, Double> {
    val base = min(width, height)
    return when (this) {
        TutorVisualSizeClass.TINY -> base * 0.045 to base * 0.045
        TutorVisualSizeClass.SMALL -> base * 0.09 to base * 0.07
        TutorVisualSizeClass.MEDIUM -> base * 0.16 to base * 0.11
        TutorVisualSizeClass.LARGE -> base * 0.24 to base * 0.18
        TutorVisualSizeClass.WIDE -> base * 0.34 to base * 0.13
        TutorVisualSizeClass.TALL -> base * 0.13 to base * 0.34
    }
}

private fun TutorVisualAnchor.resolve(): TutorVisualPoint? = when (this) {
    TutorVisualAnchor.AUTO -> null
    TutorVisualAnchor.TOP -> TutorVisualPoint(0.5, 0.12)
    TutorVisualAnchor.TOP_END -> TutorVisualPoint(0.85, 0.15)
    TutorVisualAnchor.END -> TutorVisualPoint(0.88, 0.5)
    TutorVisualAnchor.BOTTOM_END -> TutorVisualPoint(0.85, 0.85)
    TutorVisualAnchor.BOTTOM -> TutorVisualPoint(0.5, 0.88)
    TutorVisualAnchor.BOTTOM_START -> TutorVisualPoint(0.15, 0.85)
    TutorVisualAnchor.START -> TutorVisualPoint(0.12, 0.5)
    TutorVisualAnchor.TOP_START -> TutorVisualPoint(0.15, 0.15)
    TutorVisualAnchor.CENTER -> TutorVisualPoint(0.5, 0.5)
}

private fun TutorVisualRect.clamped(
    minX: Double,
    maxX: Double,
    minY: Double,
    maxY: Double,
): TutorVisualRect {
    val dx = when {
        left < minX -> minX - left
        right > maxX -> maxX - right
        else -> 0.0
    }
    val dy = when {
        top < minY -> minY - top
        bottom > maxY -> maxY - bottom
        else -> 0.0
    }
    return translate(dx, dy)
}

private fun TutorVisualRect.translate(dx: Double, dy: Double) =
    TutorVisualRect(left + dx, top + dy, right + dx, bottom + dy)

private fun TutorVisualRect.edgePointToward(
    target: TutorVisualPoint,
    requestedSide: TutorVisualAnchor,
): TutorVisualPoint {
    return when (requestedSide) {
        TutorVisualAnchor.TOP -> TutorVisualPoint(center.x, top)
        TutorVisualAnchor.TOP_END -> TutorVisualPoint(right, top)
        TutorVisualAnchor.END -> TutorVisualPoint(right, center.y)
        TutorVisualAnchor.BOTTOM_END -> TutorVisualPoint(right, bottom)
        TutorVisualAnchor.BOTTOM -> TutorVisualPoint(center.x, bottom)
        TutorVisualAnchor.BOTTOM_START -> TutorVisualPoint(left, bottom)
        TutorVisualAnchor.START -> TutorVisualPoint(left, center.y)
        TutorVisualAnchor.TOP_START -> TutorVisualPoint(left, top)
        TutorVisualAnchor.CENTER -> center
        TutorVisualAnchor.AUTO -> {
            val dx = target.x - center.x
            val dy = target.y - center.y
            if (kotlin.math.abs(dx / max(width, 1e-6)) > kotlin.math.abs(dy / max(height, 1e-6))) {
                TutorVisualPoint(if (dx >= 0.0) right else left, center.y)
            } else {
                TutorVisualPoint(center.x, if (dy >= 0.0) bottom else top)
            }
        }
    }
}

private fun segmentIntersectsRect(
    start: TutorVisualPoint,
    end: TutorVisualPoint,
    rect: TutorVisualRect,
): Boolean {
    if (start.x == end.x) {
        return start.x in rect.left..rect.right &&
            max(start.y, end.y) >= rect.top &&
            min(start.y, end.y) <= rect.bottom
    }
    if (start.y == end.y) {
        return start.y in rect.top..rect.bottom &&
            max(start.x, end.x) >= rect.left &&
            min(start.x, end.x) <= rect.right
    }
    return false
}

private fun List<TutorVisualPoint>.removeDuplicatePoints(): List<TutorVisualPoint> =
    fold(emptyList()) { result, point ->
        if (result.lastOrNull() == point) result else result + point
    }

private fun List<TutorVisualPoint>.polylineMidpoint(): TutorVisualPoint {
    if (size < 2) return firstOrNull() ?: TutorVisualPoint(0.0, 0.0)
    val lengths = zipWithNext().map { (start, end) ->
        kotlin.math.hypot(end.x - start.x, end.y - start.y)
    }
    val target = lengths.sum() / 2.0
    var consumed = 0.0
    zipWithNext().forEachIndexed { index, (start, end) ->
        val length = lengths[index]
        if (consumed + length >= target && length > 0.0) {
            val fraction = (target - consumed) / length
            return TutorVisualPoint(
                start.x + (end.x - start.x) * fraction,
                start.y + (end.y - start.y) * fraction,
            )
        }
        consumed += length
    }
    return last()
}

private fun TutorVisualPoint.distanceToSegment(
    start: TutorVisualPoint,
    end: TutorVisualPoint,
): Double {
    val dx = end.x - start.x
    val dy = end.y - start.y
    if (dx == 0.0 && dy == 0.0) return kotlin.math.hypot(x - start.x, y - start.y)
    val t = (((x - start.x) * dx + (y - start.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    return kotlin.math.hypot(x - (start.x + t * dx), y - (start.y + t * dy))
}
