package com.tingyun.smartmistakebook.core.visual.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import com.tingyun.smartmistakebook.core.model.TutorVisualBindingProperty

/**
 * State for a 3D teaching scene.
 */
data class TutorVisual3DState(
    val objects: List<Visual3DObject>,
    val cameraPosition: CameraPosition,
    val lights: List<LightSource>,
    val showAxes: Boolean = true,
    val showLabels: Boolean = true,
    val showMeasurements: Boolean = false,
    val selectedObjectId: String? = null,
    val qualityLevel: QualityLevel = QualityLevel.HIGH,
)

/**
 * A 3D object in the scene.
 */
data class Visual3DObject(
    val id: String,
    val type: ObjectType,
    val position: Vec3,
    val rotation: Vec3,
    val scale: Vec3,
    val color: Color,
    val label: String? = null,
    val description: String? = null,
    val isInteractive: Boolean = true,
)

/**
 * Types of 3D objects.
 */
enum class ObjectType {
    CUBE,
    SPHERE,
    CYLINDER,
    CONE,
    PLANE,
    CUSTOM_MESH,
}

/**
 * Camera position in 3D space.
 */
data class CameraPosition(
    val position: Vec3,
    val lookAt: Vec3,
    val up: Vec3,
    val fov: Float = 45f,
)

/**
 * Light source in the scene.
 */
data class LightSource(
    val position: Vec3,
    val color: Color,
    val intensity: Float,
)

/**
 * 3D vector for positions, rotations, and scales.
 */
data class Vec3(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val ONE = Vec3(1f, 1f, 1f)
    }
}

/**
 * Quality levels for rendering.
 * Used for low-end device degradation.
 */
enum class QualityLevel {
    /** Full quality with all effects. */
    HIGH,
    /** Reduced quality, simpler shaders. */
    MEDIUM,
    /** Minimal quality, flat shading only. */
    LOW,
    /** 2D fallback for very low-end devices. */
    FALLBACK_2D,
    /** Text-only description for accessibility. */
    TEXT_ONLY,
}

/**
 * Result of a picking operation (hit test on 3D objects).
 */
data class PickingResult(
    val hit: Boolean,
    val objectId: String?,
    val hitPoint: Vec3?,
    val distance: Float,
)

/**
 * Accessible alternative for 3D content.
 */
data class Accessible3DAlternative(
    val type: AlternativeType3D,
    val contentDescription: String,
    val dataTable: List<List<String>>? = null,
    val simplifiedView: @Composable (() -> Unit)? = null,
)

/**
 * Types of accessible alternatives for 3D content.
 */
enum class AlternativeType3D {
    /** Text description of the 3D scene. */
    TEXT_DESCRIPTION,
    /** Data table with object properties. */
    DATA_TABLE,
    /** Simplified 2D view. */
    SIMPLIFIED_2D,
    /** Audio description. */
    AUDIO_DESCRIPTION,
}

/**
 * 3D panel component with picking, labels, axes, measurements,
 * and quality degradation.
 */
@Composable
fun TutorVisual3DPanel(
    state: TutorVisual3DState,
    modifier: Modifier = Modifier,
    onObjectSelected: ((String) -> Unit)? = null,
    onPicking: ((PickingResult) -> Unit)? = null,
) {
    when (state.qualityLevel) {
        QualityLevel.HIGH, QualityLevel.MEDIUM, QualityLevel.LOW -> {
            // Render 3D scene
            ThreeDScene(
                state = state,
                modifier = modifier,
                onObjectSelected = onObjectSelected,
                onPicking = onPicking,
            )
        }
        QualityLevel.FALLBACK_2D -> {
            // Render simplified 2D view
            Simplified2DFallback(
                state = state,
                modifier = modifier,
            )
        }
        QualityLevel.TEXT_ONLY -> {
            // Render text description
            TextOnlyFallback(
                state = state,
                modifier = modifier,
            )
        }
    }
}

/**
 * 3D scene rendering with WebGL/Filament.
 */
@Composable
private fun ThreeDScene(
    state: TutorVisual3DState,
    modifier: Modifier,
    onObjectSelected: ((String) -> Unit)?,
    onPicking: ((PickingResult) -> Unit)?,
) {
    // 3D rendering would go here
    // For now, this is a placeholder that would integrate with
    // Filament or a similar 3D rendering engine
    androidx.compose.foundation.Canvas(modifier = modifier) {
        // Draw axes if enabled
        if (state.showAxes) {
            drawAxes()
        }

        // Draw objects based on quality level
        when (state.qualityLevel) {
            QualityLevel.HIGH -> drawObjectsWithShading(state)
            QualityLevel.MEDIUM -> drawObjectsWithSimpleShading(state)
            QualityLevel.LOW -> drawObjectsFlat(state)
            else -> {}
        }

        // Draw labels if enabled
        if (state.showLabels) {
            drawLabels(state)
        }

        // Draw measurements if enabled
        if (state.showMeasurements) {
            drawMeasurements(state)
        }
    }
}

/**
 * Simplified 2D fallback for low-end devices.
 */
@Composable
private fun Simplified2DFallback(
    state: TutorVisual3DState,
    modifier: Modifier,
) {
    androidx.compose.foundation.layout.Column(modifier = modifier) {
        // Show a top-down or side view of the 3D scene
        androidx.compose.material3.Text(
            text = "简化视图：${state.objects.size} 个对象",
        )

        // List objects with their properties
        state.objects.forEach { obj ->
            androidx.compose.foundation.layout.Row {
                androidx.compose.material3.Text(
                    text = obj.label ?: obj.type.name,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.Text(
                    text = "位置: (${obj.position.x}, ${obj.position.y}, ${obj.position.z})",
                )
            }
        }
    }
}

/**
 * Text-only fallback for maximum accessibility.
 */
@Composable
private fun TextOnlyFallback(
    state: TutorVisual3DState,
    modifier: Modifier,
) {
    androidx.compose.foundation.layout.Column(modifier = modifier) {
        androidx.compose.material3.Text(
            text = "3D 场景描述",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )

        state.objects.forEach { obj ->
            androidx.compose.material3.Text(
                text = buildString {
                    append("${obj.label ?: obj.type.name}: ")
                    append("位于位置 (${obj.position.x}, ${obj.position.y}, ${obj.position.z})")
                    obj.description?.let { append("，$it") }
                },
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Compute the quality level based on device capabilities.
 */
fun computeQualityLevel(
    isLowEndDevice: Boolean,
    hasFilamentSupport: Boolean,
    batteryLevel: Int,
    thermalStatus: Int,
): QualityLevel {
    return when {
        !hasFilamentSupport -> QualityLevel.FALLBACK_2D
        isLowEndDevice && batteryLevel < 20 -> QualityLevel.LOW
        isLowEndDevice -> QualityLevel.MEDIUM
        thermalStatus > 2 -> QualityLevel.LOW // throttled
        else -> QualityLevel.HIGH
    }
}

/**
 * Generate accessible alternatives for a 3D scene.
 */
fun generateAccessibleAlternatives(
    state: TutorVisual3DState,
): List<Accessible3DAlternative> {
    val alternatives = mutableListOf<Accessible3DAlternative>()

    // Text description
    alternatives.add(
        Accessible3DAlternative(
            type = AlternativeType3D.TEXT_DESCRIPTION,
            contentDescription = generateSceneDescription(state),
        ),
    )

    // Data table
    alternatives.add(
        Accessible3DAlternative(
            type = AlternativeType3D.DATA_TABLE,
            contentDescription = "3D 场景对象属性表",
            dataTable = generateObjectDataTable(state),
        ),
    )

    return alternatives
}

private fun generateSceneDescription(state: TutorVisual3DState): String {
    return buildString {
        append("3D 场景包含 ${state.objects.size} 个对象：")
        state.objects.forEach { obj ->
            append("${obj.label ?: obj.type.name}")
            append("位于 (${obj.position.x}, ${obj.position.y}, ${obj.position.z})")
            append("；")
        }
    }
}

private fun generateObjectDataTable(state: TutorVisual3DState): List<List<String>> {
    val header = listOf("对象", "类型", "X", "Y", "Z", "标签")
    val rows = state.objects.map { obj ->
        listOf(
            obj.id,
            obj.type.name,
            "%.2f".format(obj.position.x),
            "%.2f".format(obj.position.y),
            "%.2f".format(obj.position.z),
            obj.label ?: "",
        )
    }
    return listOf(header) + rows
}

// Placeholder extension functions for Canvas drawing
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxes() {
    // Draw X, Y, Z axes
    val origin = center
    val axisLength = 100f

    // X axis (red)
    drawLine(
        color = Color.Red,
        start = origin,
        end = androidx.compose.ui.geometry.Offset(origin.x + axisLength, origin.y),
        strokeWidth = 3f,
    )

    // Y axis (green)
    drawLine(
        color = Color.Green,
        start = origin,
        end = androidx.compose.ui.geometry.Offset(origin.x, origin.y - axisLength),
        strokeWidth = 3f,
    )

    // Z axis (blue)
    drawLine(
        color = Color.Blue,
        start = origin,
        end = androidx.compose.ui.geometry.Offset(origin.x + axisLength * 0.5f, origin.y + axisLength * 0.5f),
        strokeWidth = 3f,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawObjectsWithShading(state: TutorVisual3DState) {
    // Placeholder for shaded 3D object rendering
    state.objects.forEach { obj ->
        val center = androidx.compose.ui.geometry.Offset(
            size.width * 0.5f + obj.position.x,
            size.height * 0.5f - obj.position.y,
        )
        drawCircle(
            color = obj.color,
            radius = 30f * obj.scale.x,
            center = center,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawObjectsWithSimpleShading(state: TutorVisual3DState) {
    // Simplified shading
    drawObjectsWithShading(state)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawObjectsFlat(state: TutorVisual3DState) {
    // Flat rendering without shading
    state.objects.forEach { obj ->
        val center = androidx.compose.ui.geometry.Offset(
            size.width * 0.5f + obj.position.x,
            size.height * 0.5f - obj.position.y,
        )
        drawCircle(
            color = obj.color.copy(alpha = 0.7f),
            radius = 25f * obj.scale.x,
            center = center,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabels(state: TutorVisual3DState) {
    state.objects.forEach { obj ->
        obj.label?.let { label ->
            val center = androidx.compose.ui.geometry.Offset(
                size.width * 0.5f + obj.position.x,
                size.height * 0.5f - obj.position.y - 40f,
            )
            drawContext.canvas.nativeCanvas.drawText(
                label,
                center.x,
                center.y,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    textSize = 24f
                    textAlign = android.graphics.Paint.Align.CENTER
                },
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMeasurements(state: TutorVisual3DState) {
    // Draw measurement lines between objects
    if (state.objects.size >= 2) {
        val obj1 = state.objects[0]
        val obj2 = state.objects[1]
        val start = androidx.compose.ui.geometry.Offset(
            size.width * 0.5f + obj1.position.x,
            size.height * 0.5f - obj1.position.y,
        )
        val end = androidx.compose.ui.geometry.Offset(
            size.width * 0.5f + obj2.position.x,
            size.height * 0.5f - obj2.position.y,
        )
        drawLine(
            color = Color.Gray,
            start = start,
            end = end,
            strokeWidth = 2f,
        )
    }
}
