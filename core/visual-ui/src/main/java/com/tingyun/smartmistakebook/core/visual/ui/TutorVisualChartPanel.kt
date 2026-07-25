package com.tingyun.smartmistakebook.core.visual.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    val modelKey = allSeries.map { series ->
        series.elementId to frame.elements[series.elementId]?.focused
    }
    val modelProducer = remember(panel.panelId) { CartesianChartModelProducer() }

    LaunchedEffect(modelProducer, modelKey) {
        modelProducer.runTransaction {
            if (leftBars.isNotEmpty()) {
                columnModel {
                    leftBars.forEach { series(
                        it.points.map { point -> point.x },
                        it.points.map { point -> point.y },
                    ) }
                }
            }
            if (leftLines.isNotEmpty()) {
                lineModel {
                    leftLines.forEach { series(
                        it.points.map { point -> point.x },
                        it.points.map { point -> point.y },
                    ) }
                }
            }
            if (rightSeries.isNotEmpty()) {
                lineModel {
                    rightSeries.forEach { series(
                        it.points.map { point -> point.x },
                        it.points.map { point -> point.y },
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

    Box(
        modifier = modifier.semantics {
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
}
