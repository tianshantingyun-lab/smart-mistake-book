package com.tingyun.smartmistakebook.core.visual.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement

/**
 * Context passed to each node renderer, providing bounds, colors, and binding state.
 */
data class NodeRenderContext(
    val node: TutorVisual2DNodeElement,
    val bounds: Rect,
    val fill: Color,
    val stroke: Color,
    val alpha: Float,
    val liquidLevel: Double?,
    val isSelected: Boolean,
)

/**
 * Contract for rendering a specific TutorVisual2DNodeKind. Each enum value must
 * have exactly one registered renderer; the registry enforces full coverage at
 * construction time so that Kotlin `when` exhaustiveness is guaranteed.
 */
interface TutorVisual2DNodeRenderer {
    val supportedKind: TutorVisual2DNodeKind
    fun DrawScope.render(context: NodeRenderContext)
}

/**
 * Registry that maps every [TutorVisual2DNodeKind] to exactly one renderer.
 * Fails fast at construction if any enum value is missing or duplicated.
 */
class TutorVisual2DRendererRegistry(
    renderers: List<TutorVisual2DNodeRenderer>,
) {
    private val byKind: Map<TutorVisual2DNodeKind, TutorVisual2DNodeRenderer>

    init {
        val grouped = renderers.groupBy(TutorVisual2DNodeRenderer::supportedKind)
        val duplicates = grouped.filter { it.value.size > 1 }
        require(duplicates.isEmpty()) {
            "Duplicate renderers for kinds: ${duplicates.keys}"
        }
        val covered = grouped.keys.toSet()
        val allKinds = TutorVisual2DNodeKind.entries.toSet()
        val missing = allKinds - covered
        require(missing.isEmpty()) {
            "Missing renderers for kinds: $missing"
        }
        byKind = grouped.mapValues { it.value.single() }
    }

    /**
     * The set of all kinds covered by this registry. Used in contract tests.
     */
    val supportedKinds: Set<TutorVisual2DNodeKind> get() = byKind.keys.toSet()

    /**
     * Render a single node via the appropriate renderer.
     */
    fun DrawScope.renderNode(context: NodeRenderContext) {
        val renderer = byKind[context.node.kind]
            ?: error("No renderer registered for ${context.node.kind}")
        with(renderer) { render(context) }
    }
}

// ---------------------------------------------------------------------------
// Default renderers for each node kind
// ---------------------------------------------------------------------------

class PointNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.POINT
    override fun DrawScope.render(context: NodeRenderContext) {
        drawCircle(context.stroke, radius = 5f, center = context.bounds.center)
    }
}

class CircleNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.CIRCLE
    override fun DrawScope.render(context: NodeRenderContext) {
        drawOval(context.fill, context.bounds.topLeft, context.bounds.size)
        drawOval(
            context.stroke,
            context.bounds.topLeft,
            context.bounds.size,
            style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
        )
    }
}

class RectangleNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.RECTANGLE
    override fun DrawScope.render(context: NodeRenderContext) {
        drawRect(context.fill, context.bounds.topLeft, context.bounds.size)
        drawRect(
            context.stroke,
            context.bounds.topLeft,
            context.bounds.size,
            style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
        )
    }
}

class RoundedRectangleNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.ROUNDED_RECTANGLE
    override fun DrawScope.render(context: NodeRenderContext) {
        drawRoundRect(
            color = context.fill,
            topLeft = context.bounds.topLeft,
            size = context.bounds.size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f),
        )
        drawRoundRect(
            color = context.stroke,
            topLeft = context.bounds.topLeft,
            size = context.bounds.size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
        )
    }
}

class PolygonNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.POLYGON
    override fun DrawScope.render(context: NodeRenderContext) = drawPathBasedNode(context)
}

class BezierNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.BEZIER
    override fun DrawScope.render(context: NodeRenderContext) = drawPathBasedNode(context)
}

class FilledRegionNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.FILLED_REGION
    override fun DrawScope.render(context: NodeRenderContext) = drawPathBasedNode(context)
}

class CrossSectionNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.CROSS_SECTION
    override fun DrawScope.render(context: NodeRenderContext) = drawPathBasedNode(context)
}

class GeographicLayerNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.GEOGRAPHIC_LAYER
    override fun DrawScope.render(context: NodeRenderContext) = drawPathBasedNode(context)
}

private fun DrawScope.drawPathBasedNode(context: NodeRenderContext) {
    val path = androidx.compose.ui.graphics.Path()
    val points = if (context.node.localPoints.isEmpty()) {
        listOf(
            androidx.compose.ui.geometry.Offset(context.bounds.center.x, context.bounds.top),
            androidx.compose.ui.geometry.Offset(context.bounds.right, context.bounds.bottom),
            androidx.compose.ui.geometry.Offset(context.bounds.left, context.bounds.bottom),
        )
    } else {
        context.node.localPoints.map {
            androidx.compose.ui.geometry.Offset(
                context.bounds.left + context.bounds.width * it.x.toFloat(),
                context.bounds.top + context.bounds.height * it.y.toFloat(),
            )
        }
    }
    points.firstOrNull()?.let { path.moveTo(it.x, it.y) }
    points.drop(1).forEach { path.lineTo(it.x, it.y) }
    path.close()
    drawPath(path, context.fill)
    drawPath(path, context.stroke, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
}

class ContainerNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.CONTAINER
    override fun DrawScope.render(context: NodeRenderContext) {
        drawRect(context.fill, context.bounds.topLeft, context.bounds.size)
        drawRect(
            context.stroke,
            context.bounds.topLeft,
            context.bounds.size,
            style = androidx.compose.ui.graphics.drawscope.Stroke(1f),
        )
    }
}

class RegionNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.REGION
    override fun DrawScope.render(context: NodeRenderContext) {
        drawRect(context.fill, context.bounds.topLeft, context.bounds.size)
        drawRect(
            context.stroke,
            context.bounds.topLeft,
            context.bounds.size,
            style = androidx.compose.ui.graphics.drawscope.Stroke(1f),
        )
    }
}

class MembraneNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.MEMBRANE
    override fun DrawScope.render(context: NodeRenderContext) {
        drawRect(context.fill, context.bounds.topLeft, context.bounds.size)
        val spacing = 7f
        var y = context.bounds.top
        while (y < context.bounds.bottom) {
            drawLine(
                context.stroke,
                androidx.compose.ui.geometry.Offset(context.bounds.left, y),
                androidx.compose.ui.geometry.Offset(context.bounds.right, y + spacing),
                1f,
            )
            y += spacing
        }
        drawRect(
            context.stroke,
            context.bounds.topLeft,
            context.bounds.size,
            style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
        )
    }
}

class PortNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.PORT
    override fun DrawScope.render(context: NodeRenderContext) {
        val radius = minOf(context.bounds.width, context.bounds.height) / 4f
        drawCircle(context.fill, radius = radius, center = context.bounds.center)
        drawCircle(
            context.stroke,
            radius = radius,
            center = context.bounds.center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
        )
    }
}

class PumpNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.PUMP
    override fun DrawScope.render(context: NodeRenderContext) {
        val radius = minOf(context.bounds.width, context.bounds.height) / 2f
        drawCircle(context.fill, radius = radius, center = context.bounds.center)
        drawCircle(
            context.stroke,
            radius = radius,
            center = context.bounds.center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
        )
        drawLine(
            context.stroke,
            androidx.compose.ui.geometry.Offset(context.bounds.left, context.bounds.bottom),
            androidx.compose.ui.geometry.Offset(context.bounds.right, context.bounds.bottom),
            2f,
        )
    }
}

class ReservoirNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.RESERVOIR
    override fun DrawScope.render(context: NodeRenderContext) {
        drawRoundRect(
            color = context.fill,
            topLeft = context.bounds.topLeft,
            size = context.bounds.size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
        )
        drawRoundRect(
            color = context.stroke,
            topLeft = context.bounds.topLeft,
            size = context.bounds.size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
        )
    }
}

class ElectrodeNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.ELECTRODE
    override fun DrawScope.render(context: NodeRenderContext) = drawDeviceNode(context)
}

class PistonNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.PISTON
    override fun DrawScope.render(context: NodeRenderContext) = drawDeviceNode(context)
}

class ResistorNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.RESISTOR
    override fun DrawScope.render(context: NodeRenderContext) = drawDeviceNode(context)
}

private fun DrawScope.drawDeviceNode(context: NodeRenderContext) {
    drawRoundRect(
        color = context.fill,
        topLeft = context.bounds.topLeft,
        size = context.bounds.size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f),
    )
    drawRoundRect(
        color = context.stroke,
        topLeft = context.bounds.topLeft,
        size = context.bounds.size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
    )
}

class LiquidLevelNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.LIQUID_LEVEL
    override fun DrawScope.render(context: NodeRenderContext) {
        val level = context.liquidLevel?.coerceIn(0.0, 1.0) ?: 0.5
        val top = context.bounds.bottom - context.bounds.height * level.toFloat()
        drawRect(
            context.fill,
            topLeft = androidx.compose.ui.geometry.Offset(context.bounds.left, top),
            size = androidx.compose.ui.geometry.Size(context.bounds.width, context.bounds.bottom - top),
        )
        drawLine(
            context.stroke,
            androidx.compose.ui.geometry.Offset(context.bounds.left, top),
            androidx.compose.ui.geometry.Offset(context.bounds.right, top),
            2f,
        )
    }
}

class AxesNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.AXES
    override fun DrawScope.render(context: NodeRenderContext) {
        drawLine(
            context.stroke,
            androidx.compose.ui.geometry.Offset(context.bounds.left, context.bounds.bottom),
            androidx.compose.ui.geometry.Offset(context.bounds.right, context.bounds.bottom),
            2f,
        )
        drawLine(
            context.stroke,
            androidx.compose.ui.geometry.Offset(context.bounds.left, context.bounds.bottom),
            androidx.compose.ui.geometry.Offset(context.bounds.left, context.bounds.top),
            2f,
        )
    }
}

class BatteryNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.BATTERY
    override fun DrawScope.render(context: NodeRenderContext) {
        val midY = context.bounds.center.y
        drawLine(
            context.stroke,
            androidx.compose.ui.geometry.Offset(context.bounds.left + context.bounds.width * 0.3f, midY),
            androidx.compose.ui.geometry.Offset(context.bounds.left + context.bounds.width * 0.7f, midY),
            3f,
        )
        drawLine(
            context.stroke,
            androidx.compose.ui.geometry.Offset(context.bounds.left + context.bounds.width * 0.4f, midY - context.bounds.height * 0.2f),
            androidx.compose.ui.geometry.Offset(context.bounds.left + context.bounds.width * 0.4f, midY + context.bounds.height * 0.2f),
            2f,
        )
        drawLine(
            context.stroke,
            androidx.compose.ui.geometry.Offset(context.bounds.left + context.bounds.width * 0.6f, midY - context.bounds.height * 0.35f),
            androidx.compose.ui.geometry.Offset(context.bounds.left + context.bounds.width * 0.6f, midY + context.bounds.height * 0.35f),
            2f,
        )
    }
}

class SwitchNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.SWITCH
    override fun DrawScope.render(context: NodeRenderContext) {
        drawLine(
            context.stroke,
            androidx.compose.ui.geometry.Offset(context.bounds.left, context.bounds.center.y),
            androidx.compose.ui.geometry.Offset(context.bounds.left + context.bounds.width * 0.4f, context.bounds.center.y),
            2f,
        )
        drawLine(
            context.stroke,
            androidx.compose.ui.geometry.Offset(context.bounds.left + context.bounds.width * 0.6f, context.bounds.center.y),
            androidx.compose.ui.geometry.Offset(context.bounds.right, context.bounds.center.y),
            2f,
        )
        drawCircle(
            context.stroke,
            radius = 3f,
            center = androidx.compose.ui.geometry.Offset(context.bounds.left + context.bounds.width * 0.4f, context.bounds.center.y),
        )
    }
}

class LensNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.LENS
    override fun DrawScope.render(context: NodeRenderContext) {
        val path = androidx.compose.ui.graphics.Path()
        path.moveTo(context.bounds.left, context.bounds.center.y)
        path.quadTo(context.bounds.center.x, context.bounds.top, context.bounds.right, context.bounds.center.y)
        path.quadTo(context.bounds.center.x, context.bounds.bottom, context.bounds.left, context.bounds.center.y)
        path.close()
        drawPath(path, context.fill)
        drawPath(path, context.stroke, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
    }
}

class MirrorNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.MIRROR
    override fun DrawScope.render(context: NodeRenderContext) {
        drawLine(
            context.stroke,
            androidx.compose.ui.geometry.Offset(context.bounds.left, context.bounds.top),
            androidx.compose.ui.geometry.Offset(context.bounds.left, context.bounds.bottom),
            3f,
        )
        for (i in 0..4) {
            val y = context.bounds.top + context.bounds.height * i / 4f
            drawLine(
                context.stroke,
                androidx.compose.ui.geometry.Offset(context.bounds.left, y),
                androidx.compose.ui.geometry.Offset(context.bounds.left - 4f, y + 4f),
                1f,
            )
        }
    }
}

class WaveNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.WAVE
    override fun DrawScope.render(context: NodeRenderContext) {
        val path = androidx.compose.ui.graphics.Path()
        path.moveTo(context.bounds.left, context.bounds.center.y)
        var x = context.bounds.left
        val step = context.bounds.width / 20f
        while (x <= context.bounds.right) {
            val yOff = kotlin.math.sin(
                (x - context.bounds.left) / context.bounds.width * 4 * Math.PI,
            ).toFloat() * context.bounds.height * 0.3f
            path.lineTo(x, context.bounds.center.y + yOff)
            x += step
        }
        drawPath(path, context.stroke, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
    }
}

class BiologicalStructureNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.BIOLOGICAL_STRUCTURE
    override fun DrawScope.render(context: NodeRenderContext) {
        drawOval(context.fill, context.bounds.topLeft, context.bounds.size)
        drawOval(
            context.stroke,
            context.bounds.topLeft,
            context.bounds.size,
            style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
        )
        drawCircle(
            context.stroke.copy(alpha = 0.5f),
            radius = minOf(context.bounds.width, context.bounds.height) * 0.15f,
            center = context.bounds.center,
        )
    }
}

class MaterialNodeRenderer : TutorVisual2DNodeRenderer {
    override val supportedKind = TutorVisual2DNodeKind.MATERIAL_NODE
    override fun DrawScope.render(context: NodeRenderContext) {
        drawRoundRect(
            color = context.fill,
            topLeft = context.bounds.topLeft,
            size = context.bounds.size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f),
        )
        drawRoundRect(
            color = context.stroke,
            topLeft = context.bounds.topLeft,
            size = context.bounds.size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
        )
    }
}

/**
 * All built-in renderers. Pass to [TutorVisual2DRendererRegistry] to construct
 * a fully-covered registry.
 */
val defaultNodeRenderers: List<TutorVisual2DNodeRenderer> = listOf(
    PointNodeRenderer(),
    CircleNodeRenderer(),
    RectangleNodeRenderer(),
    RoundedRectangleNodeRenderer(),
    PolygonNodeRenderer(),
    BezierNodeRenderer(),
    FilledRegionNodeRenderer(),
    CrossSectionNodeRenderer(),
    GeographicLayerNodeRenderer(),
    ContainerNodeRenderer(),
    RegionNodeRenderer(),
    MembraneNodeRenderer(),
    PortNodeRenderer(),
    PumpNodeRenderer(),
    ReservoirNodeRenderer(),
    ElectrodeNodeRenderer(),
    PistonNodeRenderer(),
    ResistorNodeRenderer(),
    LiquidLevelNodeRenderer(),
    AxesNodeRenderer(),
    BatteryNodeRenderer(),
    SwitchNodeRenderer(),
    LensNodeRenderer(),
    MirrorNodeRenderer(),
    WaveNodeRenderer(),
    BiologicalStructureNodeRenderer(),
    MaterialNodeRenderer(),
)
