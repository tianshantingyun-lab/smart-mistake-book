package com.tingyun.smartmistakebook.core.visual.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.tingyun.smartmistakebook.core.model.TutorVisualBindingProperty
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualFrame

/**
 * State for a dynamic chart that can be updated frame-by-frame.
 */
data class DynamicChartState(
    val series: List<ChartSeries>,
    val xAxisLabel: String = "",
    val yAxisLabel: String = "",
    val title: String? = null,
    val showLegend: Boolean = true,
    val showMarkers: Boolean = true,
    val showDataPoints: Boolean = true,
    val zoomLevel: Float = 1f,
    val panOffset: Float = 0f,
)

/**
 * A single data series in the chart.
 */
data class ChartSeries(
    val name: String,
    val color: Color,
    val points: List<ChartPoint>,
    val isVisible: Boolean = true,
)

/**
 * A single data point in a chart series.
 */
data class ChartPoint(
    val x: Double,
    val y: Double,
    val label: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Marker displayed at a data point.
 */
data class ChartMarker(
    val point: ChartPoint,
    val seriesName: String,
    val color: Color,
)

/**
 * Tooltip information for a data point.
 */
data class ChartTooltip(
    val x: Double,
    val y: Double,
    val seriesName: String,
    val value: Double,
    val label: String?,
)

/**
 * Dynamic chart component that updates based on frame state.
 * Supports frame-driven animations, markers, tooltips, zoom/reset,
 * and data table fallback for accessibility.
 */
@Composable
fun DynamicChart(
    frame: TutorVisualFrame,
    chartState: DynamicChartState,
    modifier: Modifier = Modifier,
    onPointSelected: ((ChartPoint) -> Unit)? = null,
) {
    var selectedPoint by remember { mutableStateOf<ChartPoint?>(null) }
    var showDataTable by remember { mutableStateOf(false) }

    // Update series from frame bindings
    val updatedSeries = chartState.series.map { series ->
        val frameValue = frame.elements["chart-${series.name}"]
            ?.properties?.get(TutorVisualBindingProperty.Y)
        if (frameValue != null) {
            series.copy(
                points = series.points.mapIndexed { index, point ->
                    if (index == 0) point.copy(y = frameValue) else point
                },
            )
        } else {
            series
        }
    }

    val state = chartState.copy(series = updatedSeries)

    if (showDataTable) {
        // Data table fallback for accessibility
        ChartDataTable(
            state = state,
            modifier = modifier,
        )
    } else {
        // Visual chart
        ChartCanvas(
            state = state,
            selectedPoint = selectedPoint,
            modifier = modifier,
            onPointSelected = { point ->
                selectedPoint = point
                onPointSelected?.invoke(point)
            },
        )
    }
}

/**
 * Canvas-based chart rendering with markers and tooltips.
 */
@Composable
private fun ChartCanvas(
    state: DynamicChartState,
    selectedPoint: ChartPoint?,
    modifier: Modifier,
    onPointSelected: (ChartPoint) -> Unit,
) {
    // Chart rendering logic would go here
    // For now, this is a placeholder that would be implemented
    // with Compose Canvas API
    androidx.compose.foundation.Canvas(modifier = modifier) {
        // Draw axes
        // Draw grid lines
        // Draw series lines/curves
        // Draw data points
        // Draw markers
        // Draw tooltip for selected point
    }
}

/**
 * Data table fallback for accessibility (TalkBack).
 */
@Composable
private fun ChartDataTable(
    state: DynamicChartState,
    modifier: Modifier,
) {
    androidx.compose.foundation.layout.Column(modifier = modifier) {
        state.title?.let { title ->
            androidx.compose.material3.Text(
                text = title,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
        }

        state.series.forEach { series ->
            if (series.isVisible) {
                androidx.compose.material3.Text(
                    text = series.name,
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    color = series.color,
                )

                series.points.forEach { point ->
                    androidx.compose.material3.Text(
                        text = buildString {
                            append(point.label ?: "${point.x}")
                            append(": ")
                            append("%.2f".format(point.y))
                        },
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * Compute chart bounds from series data.
 */
fun computeChartBounds(
    series: List<ChartSeries>,
    padding: Float = 0.1f,
): ChartBounds {
    val allPoints = series.flatMap { it.points }
    if (allPoints.isEmpty()) {
        return ChartBounds(0.0, 1.0, 0.0, 1.0)
    }

    val xValues = allPoints.map { it.x }
    val yValues = allPoints.map { it.y }

    val xMin = xValues.min()
    val xMax = xValues.max()
    val yMin = yValues.min()
    val yMax = yValues.max()

    val xRange = (xMax - xMin).coerceAtLeast(0.001)
    val yRange = (yMax - yMin).coerceAtLeast(0.001)

    return ChartBounds(
        xMin = xMin - xRange * padding,
        xMax = xMax + xRange * padding,
        yMin = yMin - yRange * padding,
        yMax = yMax + yRange * padding,
    )
}

/**
 * Chart axis bounds.
 */
data class ChartBounds(
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double,
) {
    val xRange: Double get() = xMax - xMin
    val yRange: Double get() = yMax - yMin
}

/**
 * Convert data coordinates to canvas coordinates.
 */
fun dataToCanvas(
    x: Double,
    y: Double,
    bounds: ChartBounds,
    canvasWidth: Float,
    canvasHeight: Float,
    padding: Float = 40f,
): Pair<Float, Float> {
    val plotWidth = canvasWidth - 2 * padding
    val plotHeight = canvasHeight - 2 * padding

    val canvasX = padding + ((x - bounds.xMin) / bounds.xRange * plotWidth).toFloat()
    val canvasY = canvasHeight - padding - ((y - bounds.yMin) / bounds.yRange * plotHeight).toFloat()

    return canvasX to canvasY
}
