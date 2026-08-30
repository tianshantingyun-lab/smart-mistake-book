package com.tingyun.smartmistakebook.core.visual.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.TutorVisualGeometry3DElement
import com.tingyun.smartmistakebook.core.model.TutorVisualLatticeElement
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.visual.runtime.CompiledTutorVisualDocument
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualFallbackProjector
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualFrame
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualLatticeCompiler

@Composable
internal fun TutorVisual3DPanel(
    compiled: CompiledTutorVisualDocument,
    panel: TutorVisualPanel,
    frame: TutorVisualFrame,
    profile: TutorVisualRenderProfile,
    modifier: Modifier,
) {
    val baseCamera = frame.panelCameras.getValue(panel.panelId)
    var azimuthOffset by remember(compiled.scene.sceneId, panel.panelId) {
        mutableDoubleStateOf(0.0)
    }
    var elevationOffset by remember(compiled.scene.sceneId, panel.panelId) {
        mutableDoubleStateOf(0.0)
    }
    var zoomFactor by remember(compiled.scene.sceneId, panel.panelId) {
        mutableDoubleStateOf(1.0)
    }
    var filamentReady by remember(compiled.scene.sceneId, panel.panelId) {
        mutableStateOf(false)
    }
    var selectedElementId by remember(compiled.scene.sceneId, panel.panelId) {
        mutableStateOf<String?>(null)
    }
    val camera = baseCamera.copy(
        azimuthDegrees = baseCamera.azimuthDegrees + azimuthOffset,
        elevationDegrees = (baseCamera.elevationDegrees + elevationOffset).coerceIn(-85.0, 85.0),
        distance = (baseCamera.distance / zoomFactor).coerceIn(
            baseCamera.minimumDistance,
            baseCamera.maximumDistance,
        ),
    )
    val panelElements = compiled.scene.elements.filter { it.panelId == panel.panelId }
    val geometries = panelElements.filterIsInstance<TutorVisualGeometry3DElement>()
        .filter { frame.elements[it.elementId]?.visible == true }
    val lattices = panelElements.filterIsInstance<TutorVisualLatticeElement>()
        .filter { frame.elements[it.elementId]?.visible == true }
    val latticeInstances = remember(lattices) {
        lattices.flatMap(TutorVisualLatticeCompiler::expand)
    }
    val useFilament = profile.threeDimensionalMode == TutorVisual3DMode.FILAMENT

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics { contentDescription = panel.title ?: compiled.scene.accessibilitySummary }
                .pointerInput(camera.allowOrbit) {
                    if (!camera.allowOrbit) return@pointerInput
                    detectTransformGestures { offset, _, gestureZoom, rotation ->
                        azimuthOffset = (azimuthOffset - rotation).coerceIn(-720.0, 720.0)
                        zoomFactor = (zoomFactor * gestureZoom).coerceIn(0.35, 4.0)
                    }
                },
        ) {
            // PR-11 picking: Filament taps resolve against the rendered depth
            // buffer (View.pick) and select the hit element; GPU-backed
            // behavior is validated on a real device, the emulator path is
            // covered by the fallback canvas below.
            if (useFilament) {
                FilamentVisualSurface(
                    geometries = geometries,
                    lattices = lattices,
                    frame = frame,
                    camera = camera,
                    modifier = Modifier.fillMaxSize(),
                    onReadyChanged = { filamentReady = it },
                    onElementSelected = { selectedElementId = it },
                )
            }
            if (!useFilament || !filamentReady) {
                TutorVisualFallback3DCanvas(
                    geometries = geometries,
                    latticeInstances = latticeInstances,
                    frame = frame,
                    azimuthDegrees = camera.azimuthDegrees,
                    elevationDegrees = camera.elevationDegrees,
                    selectedElementId = selectedElementId,
                    onElementSelected = { selectedElementId = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Selected element info
        selectedElementId?.let { elementId ->
            val element = geometries.firstOrNull { it.elementId == elementId }
            if (element != null) {
                Text(
                    text = element.label ?: element.elementId,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun TutorVisualFallback3DCanvas(
    geometries: List<TutorVisualGeometry3DElement>,
    latticeInstances: List<com.tingyun.smartmistakebook.core.visual.runtime.TutorVisual3DInstance>,
    frame: TutorVisualFrame,
    azimuthDegrees: Double,
    elevationDegrees: Double,
    selectedElementId: String?,
    onElementSelected: (String?) -> Unit,
    modifier: Modifier,
) {
    val paper = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Canvas(modifier.pointerInput(Unit) {
        detectTapGestures { offset ->
            // Fallback picking (audit PR-11): the nearest projected element
            // wins, but only inside the pick radius; tapping empty space
            // clears the selection instead of grabbing the farthest item.
            val distancesSquared = geometries.mapNotNull { geometry ->
                val state = frame.elements[geometry.elementId] ?: return@mapNotNull null
                val projected = TutorVisualFallbackProjector.project(
                    elements = listOf(geometry),
                    latticeInstances = emptyList(),
                    azimuthDegrees = azimuthDegrees,
                    elevationDegrees = elevationDegrees,
                    width = size.width.toDouble(),
                    height = size.height.toDouble(),
                )
                if (projected.isEmpty()) return@mapNotNull null
                val center = projected.first().center
                val dx = center.x - offset.x.toDouble()
                val dy = center.y - offset.y.toDouble()
                geometry.elementId to dx * dx + dy * dy
            }.toMap()
            val pickRadius = minOf(size.width, size.height).toDouble() * FALLBACK_PICK_RADIUS_FRACTION
            onElementSelected(resolvePickHit(distancesSquared, pickRadius * pickRadius))
        }
    }) {
        drawRect(paper)
        val projected = TutorVisualFallbackProjector.project(
            elements = geometries,
            latticeInstances = latticeInstances,
            azimuthDegrees = azimuthDegrees,
            elevationDegrees = elevationDegrees,
            width = size.width.toDouble(),
            height = size.height.toDouble(),
        )
        if (projected.isEmpty()) return@Canvas
        val positions = projected.associateBy { it.elementId }
        geometries.forEach { geometry ->
            if (geometry.points.size < 2) return@forEach
            val state = frame.elements[geometry.elementId] ?: return@forEach
            val pointElements = geometry.points.mapIndexed { index, point ->
                com.tingyun.smartmistakebook.core.model.TutorVisualGeometry3DElement(
                    elementId = "${geometry.elementId}-fallback-$index",
                    panelId = geometry.panelId,
                    kind = com.tingyun.smartmistakebook.core.model.TutorVisualGeometry3DKind.SPHERE,
                    transform = com.tingyun.smartmistakebook.core.model.TutorVisualTransform3D(
                        translation = point,
                    ),
                )
            }
            val linePoints = TutorVisualFallbackProjector.project(
                elements = pointElements,
                latticeInstances = emptyList(),
                azimuthDegrees = azimuthDegrees,
                elevationDegrees = elevationDegrees,
                width = size.width.toDouble(),
                height = size.height.toDouble(),
            )
            linePoints.zipWithNext().forEach { (start, end) ->
                drawLine(
                    color = if (state.focused) accent else ink.copy(alpha = if (state.dimmed) 0.24f else 0.72f),
                    start = Offset(start.center.x.toFloat(), start.center.y.toFloat()),
                    end = Offset(end.center.x.toFloat(), end.center.y.toFloat()),
                    strokeWidth = if (state.focused) 4.dp.toPx() else 2.dp.toPx(),
                )
            }
        }
        projected.forEachIndexed { index, item ->
            val parentId = item.elementId.substringBeforeLast("-", missingDelimiterValue = item.elementId)
            val state = frame.elements[item.elementId] ?: frame.elements[parentId]
            val dimmed = state?.dimmed == true
            val focused = state?.focused == true
            val isSelected = item.elementId == selectedElementId
            val radius = (5.5.dp.toPx() * item.scale.coerceIn(0.35, 2.2)).toFloat()
            val color = when {
                isSelected -> accent
                focused -> accent
                index % 2 == 0 -> secondary
                else -> ink
            }.copy(alpha = if (dimmed) 0.24f else 0.88f)
            drawCircle(
                color = color.copy(alpha = color.alpha * 0.18f),
                radius = radius * 1.45f,
                center = Offset(item.center.x.toFloat(), item.center.y.toFloat()),
            )
            drawCircle(
                color = color,
                radius = radius,
                center = Offset(item.center.x.toFloat(), item.center.y.toFloat()),
                style = Stroke(1.5.dp.toPx()),
            )
            // Draw label for selected element
            if (isSelected) {
                val geometry = geometries.firstOrNull { it.elementId == item.elementId }
                geometry?.label?.let { label ->
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            this.color = android.graphics.Color.DKGRAY
                            textSize = 10.dp.toPx()
                            textAlign = android.graphics.Paint.Align.CENTER
                            isAntiAlias = true
                        }
                        drawText(
                            label,
                            item.center.x.toFloat(),
                            item.center.y.toFloat() + radius + 14.dp.toPx(),
                            paint,
                        )
                    }
                }
            }
        }
    }
}

private const val FALLBACK_PICK_RADIUS_FRACTION = 0.12

/** Nearest element wins, but only inside the squared threshold. */
internal fun resolvePickHit(
    distancesSquared: Map<String, Double>,
    thresholdSquared: Double,
): String? = distancesSquared
    .minByOrNull { it.value }
    ?.takeIf { it.value <= thresholdSquared }
    ?.key
