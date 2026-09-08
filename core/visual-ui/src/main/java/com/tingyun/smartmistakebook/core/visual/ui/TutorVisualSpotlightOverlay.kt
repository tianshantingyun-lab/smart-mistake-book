package com.tingyun.smartmistakebook.core.visual.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.TutorVisualBindingProperty
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisual2DLayout
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualRect
import com.tingyun.smartmistakebook.core.visual.runtime.spotlightMaskRects

/**
 * The teacher-annotation "spotlight" layer — a stable, no-blend-mode adaptation
 * of the OpenMAIC SpotlightOverlay. When a step focuses one element we dim the
 * panel around it and draw a rounded accent frame so the student's attention is
 * guided to the key part, without altering the underlying scene or intercepting
 * the student drag/tap gestures (this overlay draws but never consumes input).
 *
 * Colors come from [MaterialTheme.colorScheme], which the app layer maps to the
 * SmartColors palette (onSurface=Ink, primary=Jade, primaryContainer=JadeSoft) —
 * so the dim uses the warm ink tone, the frame the emphasis green, and the halo
 * the soft jade, consistent with [SmartColors].
 *
 * @param focusElementId the element to spotlight (the step's primary relation).
 * @param layout the 2D layout supplying the element bounds in this panel's px space.
 * @param widthPx panel width in px (matches the layout coordinate space).
 * @param heightPx panel height in px (matches the layout coordinate space).
 */
@Composable
internal fun TutorVisualSpotlightOverlay(
    focusElementId: String?,
    layout: TutorVisual2DLayout,
    widthPx: Double,
    heightPx: Double,
    modifier: Modifier = Modifier,
    dimColor: Color = MaterialTheme.colorScheme.onSurface,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    haloColor: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    val targetRect = remember(focusElementId, layout, widthPx, heightPx) {
        resolveFocusBounds(focusElementId, layout, widthPx, heightPx)
    }
    // Elastic settle: the accent frame animates from an inflated ring to the tight
    // fit, mirroring OpenMAIC's [0.16,1,0.3,1] ease. We only animate the frame,
    // not the mask, so the panel is dimmed immediately when the target changes.
    val inflateR = 12.dp
    val frameInflation = remember { Animatable(1f) }
    LaunchedEffect(targetRect) {
        if (targetRect != null) {
            frameInflation.snapTo(0f)
            frameInflation.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        }
    }

    Canvas(modifier) {
        val rect = targetRect ?: return@Canvas
        val maskRects = spotlightMaskRects(widthPx, heightPx, rect, inflateR.toPx().toDouble())
        // Dim everything outside the target (mask), then draw a rounded accent frame.
        maskRects.forEach { mask ->
            drawRect(
                color = dimColor.copy(alpha = 0.38f),
                topLeft = Offset(mask.left.toFloat(), mask.top.toFloat()),
                size = Size(mask.width.toFloat(), mask.height.toFloat()),
            )
        }
        // Soft primaryContainer halo behind the frame.
        drawRoundRect(
            color = haloColor.copy(alpha = 0.30f),
            topLeft = Offset((rect.left - 10.dp.toPx()).toFloat(), (rect.top - 10.dp.toPx()).toFloat()),
            size = Size(
                (rect.width + 20.dp.toPx()).toFloat(),
                (rect.height + 20.dp.toPx()).toFloat(),
            ),
            cornerRadius = CornerRadius(10.dp.toPx()),
        )
        // Accent frame, settling from inflated to tight.
        val extra = (inflateR.toPx() * (1f - frameInflation.value)).toFloat()
        drawRoundRect(
            color = accentColor,
            topLeft = Offset((rect.left - extra).toFloat(), (rect.top - extra).toFloat()),
            size = Size(
                (rect.width + extra * 2).toFloat(),
                (rect.height + extra * 2).toFloat(),
            ),
            cornerRadius = CornerRadius(10.dp.toPx()),
            style = Stroke(width = 2.5.dp.toPx()),
        )
    }
}

private fun resolveFocusBounds(
    focusElementId: String?,
    layout: TutorVisual2DLayout,
    widthPx: Double,
    heightPx: Double,
): TutorVisualRect? {
    if (focusElementId == null) return null
    layout.nodes[focusElementId]?.let { return it.bounds }
    layout.connectors[focusElementId]?.let { connector ->
        if (connector.points.isEmpty()) return null
        val bounds = unionBounds(connector.points)
        return bounds.inflated(6.0).coerceWithin(widthPx, heightPx)
    }
    return null
}

private fun unionBounds(points: List<com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualPoint>): TutorVisualRect {
    var left = Double.MAX_VALUE
    var top = Double.MAX_VALUE
    var right = -Double.MAX_VALUE
    var bottom = -Double.MAX_VALUE
    points.forEach { p ->
        left = minOf(left, p.x)
        top = minOf(top, p.y)
        right = maxOf(right, p.x)
        bottom = maxOf(bottom, p.y)
    }
    return TutorVisualRect(left, top, right, bottom)
}

private fun TutorVisualRect.coerceWithin(width: Double, height: Double): TutorVisualRect =
    TutorVisualRect(
        left.coerceIn(0.0, width),
        top.coerceIn(0.0, height),
        right.coerceIn(0.0, width),
        bottom.coerceIn(0.0, height),
    )
