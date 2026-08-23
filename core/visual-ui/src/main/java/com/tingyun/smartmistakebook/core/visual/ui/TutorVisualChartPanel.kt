package com.tingyun.smartmistakebook.core.visual.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.tingyun.smartmistakebook.core.model.TutorVisualChartAxis
import com.tingyun.smartmistakebook.core.model.TutorVisualChartPoint
import com.tingyun.smartmistakebook.core.model.TutorVisualChartSeriesElement
import com.tingyun.smartmistakebook.core.model.TutorVisualChartSeriesKind
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.visual.runtime.CompiledTutorVisualDocument
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualFrame

@Composable
internal fun TutorVisualChartPanel(
    compiled: CompiledTutorVisualDocument,
    panel: TutorVisualPanel,
    frame: TutorVisualFrame,
    modifier: Modifier,
) {
    val configuration = requireNotNull(panel.chart)
    val allSeries = compiled.scene.elements
        .filterIsInstance<TutorVisualChartSeriesElement>()
        .filter { it.panelId == panel.panelId && frame.elements[it.elementId]?.visible == true }
    val leftBars = allSeries.filter {
        it.axis == TutorVisualChartAxis.LEFT && it.kind == TutorVisualChartSeriesKind.BAR
    }
    val leftLines = allSeries.filter {
        it.axis == TutorVisualChartAxis.LEFT && it.kind != TutorVisualChartSeriesKind.BAR
    }
    val rightSeries = allSeries.filter { it.axis == TutorVisualChartAxis.RIGHT }
    // Frame binding (audit PR-11): the document model carries no
    // per-frame series data, so points reveal along the x-domain as
    // time progresses: everything up to the current frame is visible.
    val visiblePointsBySeries = allSeries.associate { series ->
        series.elementId to visiblePointsForFrame(series.points, frame.timeProgress)
    }
    val modelKey = allSeries.map { series ->
        Triple(
            series.elementId,
            frame.elements[series.elementId]?.focused,
            visiblePointsBySeries.getValue(series.elementId).size,
        )
    }
    val modelProducer = remember(panel.panelId) { CartesianChartModelProducer() }

    LaunchedEffect(modelProducer, modelKey) {
        modelProducer.runTransaction {
            if (leftBars.isNotEmpty()) {
                columnModel {
                    leftBars.forEach { series(
                        visiblePointsBySeries.getValue(it.elementId).map { point -> point.x },
                        visiblePointsBySeries.getValue(it.elementId).map { point -> point.y },
                    ) }
                }
            }
            if (leftLines.isNotEmpty()) {
                lineModel {
                    leftLines.forEach { series(
                        visiblePointsBySeries.getValue(it.elementId).map { point -> point.x },
                        visiblePointsBySeries.getValue(it.elementId).map { point -> point.y },
                    ) }
                }
            }
            if (rightSeries.isNotEmpty()) {
                lineModel {
                    rightSeries.forEach { series(
                        visiblePointsBySeries.getValue(it.elementId).map { point -> point.x },
                        visiblePointsBySeries.getValue(it.elementId).map { point -> point.y },
                    ) }
                }
            }
        }
    }

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val barLayer = rememberColumnCartesianLayer(
        columnProvider = ColumnCartesianLayer.ColumnProvider.series(
            rememberLineComponent(fill = Fill(primary), thickness = 12.dp),
        ),
        verticalAxisPosition = Axis.Position.Vertical.Start,
    )
    val lineLayer = rememberLineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(
            LineCartesianLayer.Line(LineCartesianLayer.LineFill.single(Fill(secondary))),
            LineCartesianLayer.Line(LineCartesianLayer.LineFill.single(Fill(tertiary))),
            LineCartesianLayer.Line(LineCartesianLayer.LineFill.single(Fill(primary))),
        ),
        verticalAxisPosition = Axis.Position.Vertical.Start,
    )
    val rightLayer = rememberLineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(
            LineCartesianLayer.Line(
                LineCartesianLayer.LineFill.single(Fill(Color(0xffb35c1e))),
            ),
            LineCartesianLayer.Line(
                LineCartesianLayer.LineFill.single(Fill(Color(0xff6b5ca5))),
            ),
        ),
        verticalAxisPosition = Axis.Position.Vertical.End,
    )
    val chart = when {
        leftBars.isNotEmpty() && leftLines.isNotEmpty() && rightSeries.isNotEmpty() ->
            rememberCartesianChart(
                barLayer,
                lineLayer,
                rightLayer,
                startAxis = VerticalAxis.rememberStart(),
                endAxis = VerticalAxis.rememberEnd(),
                bottomAxis = HorizontalAxis.rememberBottom(),
            )
        leftBars.isNotEmpty() && leftLines.isNotEmpty() ->
            rememberCartesianChart(
                barLayer,
                lineLayer,
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(),
            )
        leftBars.isNotEmpty() && rightSeries.isNotEmpty() ->
            rememberCartesianChart(
                barLayer,
                rightLayer,
                startAxis = VerticalAxis.rememberStart(),
                endAxis = VerticalAxis.rememberEnd(),
                bottomAxis = HorizontalAxis.rememberBottom(),
            )
        leftLines.isNotEmpty() && rightSeries.isNotEmpty() ->
            rememberCartesianChart(
                lineLayer,
                rightLayer,
                startAxis = VerticalAxis.rememberStart(),
                endAxis = VerticalAxis.rememberEnd(),
                bottomAxis = HorizontalAxis.rememberBottom(),
            )
        leftBars.isNotEmpty() ->
            rememberCartesianChart(
                barLayer,
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(),
            )
        rightSeries.isNotEmpty() ->
            rememberCartesianChart(
                rightLayer,
                endAxis = VerticalAxis.rememberEnd(),
                bottomAxis = HorizontalAxis.rememberBottom(),
            )
        else ->
            rememberCartesianChart(
                lineLayer,
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(),
            )
    }

    Column(modifier = modifier) {
        // Chart area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics {
                    contentDescription = buildString {
                        append(configuration.xAxisLabel)
                        append("；")
                        append(configuration.leftAxisLabel)
                        configuration.rightAxisLabel?.let { append("；").append(it) }
                    }
                },
        ) {
            CartesianChartHost(
                chart = chart,
                modelProducer = modelProducer,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Legend
        if (allSeries.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                allSeries.take(5).forEach { series ->
                    val color = when (series.axis) {
                        TutorVisualChartAxis.LEFT -> when (series.kind) {
                            TutorVisualChartSeriesKind.BAR -> primary
                            else -> secondary
                        }
                        TutorVisualChartAxis.RIGHT -> tertiary
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = series.label ?: series.elementId,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        // Axis labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = configuration.leftAxisLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            configuration.rightAxisLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Points visible at the given frame progress: the x-domain reveals
 * left-to-right with time (series points are x-ordered by contract).
 * The leading point always stays visible so the chart never empties.
 */
internal fun visiblePointsForFrame(
    points: List<TutorVisualChartPoint>,
    timeProgress: Double,
): List<TutorVisualChartPoint> {
    if (points.isEmpty()) return points
    if (timeProgress >= 1.0) return points
    val minX = points.first().x
    val maxX = points.last().x
    if (maxX <= minX) return points
    val cutoff = minX + (maxX - minX) * timeProgress.coerceIn(0.0, 1.0)
    return points.takeWhile { it.x <= cutoff }.ifEmpty { points.take(1) }
}
