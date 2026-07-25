package com.tingyun.smartmistakebook.core.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.tingyun.smartmistakebook.core.model.FigureCoordinate
import com.tingyun.smartmistakebook.core.model.FigureSchema
import com.tingyun.smartmistakebook.core.model.FigureSeriesStyle
import java.util.Locale
import kotlin.math.max

/** Draws only the bounded figure schemas accepted by the structured-content validator. */
internal object DeterministicPdfFigureRenderer {
    private const val CARTESIAN_HEIGHT = 280f
    private const val TABLE_ROW_HEIGHT = 18f
    private const val FIGURE_VERTICAL_CHROME = 48f
    private const val PADDING = 10f

    fun height(block: MistakePdfBlock.Figure): Float = when (val schema = block.schema) {
        is FigureSchema.Cartesian -> CARTESIAN_HEIGHT
        is FigureSchema.SymbolTable ->
            FIGURE_VERTICAL_CHROME + TABLE_ROW_HEIGHT * (schema.rows.size + 1)
        is FigureSchema.Unknown -> error("Unknown figure schemas are rejected before export")
    }

    fun draw(canvas: Canvas, block: MistakePdfBlock.Figure, bounds: RectF) {
        canvas.drawRoundRect(bounds, 8f, 8f, fillPaint(Color.rgb(248, 249, 247)))
        canvas.drawRoundRect(bounds, 8f, 8f, strokePaint(Color.rgb(205, 211, 207), 1f))
        val heading = block.title?.takeIf(String::isNotBlank) ?: "结构化图示"
        canvas.drawText(
            ellipsize(heading, bounds.width() - PADDING * 2, headingPaint),
            bounds.left + PADDING,
            bounds.top + 18f,
            headingPaint,
        )
        val content = RectF(
            bounds.left + PADDING,
            bounds.top + 28f,
            bounds.right - PADDING,
            bounds.bottom - 20f,
        )
        when (val schema = block.schema) {
            is FigureSchema.Cartesian -> drawCartesian(canvas, schema, content)
            is FigureSchema.SymbolTable -> drawSymbolTable(canvas, schema, content)
            is FigureSchema.Unknown -> error("Unknown figure schemas are rejected before export")
        }
        canvas.drawText(
            ellipsize(block.alternativeText, bounds.width() - PADDING * 2, captionPaint),
            bounds.left + PADDING,
            bounds.bottom - 6f,
            captionPaint,
        )
    }

    private fun drawCartesian(canvas: Canvas, schema: FigureSchema.Cartesian, bounds: RectF) {
        val plot = RectF(bounds.left + 28f, bounds.top + 8f, bounds.right - 8f, bounds.bottom - 18f)
        canvas.drawRect(plot, fillPaint(Color.WHITE))
        canvas.drawRect(plot, strokePaint(Color.rgb(190, 197, 192), 1f))

        drawGrid(canvas, plot, schema.xAxis.tickCount, vertical = true)
        drawGrid(canvas, plot, schema.yAxis.tickCount, vertical = false)
        if (schema.xAxis.minimum <= 0.0 && schema.xAxis.maximum >= 0.0) {
            val x = map(0.0, schema.xAxis.minimum, schema.xAxis.maximum, plot.left, plot.right)
            canvas.drawLine(x, plot.top, x, plot.bottom, axisPaint)
        }
        if (schema.yAxis.minimum <= 0.0 && schema.yAxis.maximum >= 0.0) {
            val y = map(0.0, schema.yAxis.minimum, schema.yAxis.maximum, plot.bottom, plot.top)
            canvas.drawLine(plot.left, y, plot.right, y, axisPaint)
        }

        canvas.save()
        canvas.clipRect(plot)
        schema.polylines.forEach { line ->
            if (line.points.size >= 2) {
                val path = Path()
                line.points.forEachIndexed { index, point ->
                    val x = map(point.x, schema.xAxis.minimum, schema.xAxis.maximum, plot.left, plot.right)
                    val y = map(point.y, schema.yAxis.minimum, schema.yAxis.maximum, plot.bottom, plot.top)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, seriesPaint(line.style))
                line.label?.let { label ->
                    val anchor = line.points.last()
                    val x = map(anchor.x, schema.xAxis.minimum, schema.xAxis.maximum, plot.left, plot.right)
                    val y = map(anchor.y, schema.yAxis.minimum, schema.yAxis.maximum, plot.bottom, plot.top)
                    canvas.drawText(label, x + 4f, y - 4f, labelPaint)
                }
            }
        }
        schema.points.forEach { point ->
            val x = map(point.coordinate.x, schema.xAxis.minimum, schema.xAxis.maximum, plot.left, plot.right)
            val y = map(point.coordinate.y, schema.yAxis.minimum, schema.yAxis.maximum, plot.bottom, plot.top)
            canvas.drawCircle(x, y, 3.2f, seriesPaint(point.style).apply { style = Paint.Style.FILL })
            point.label?.let { canvas.drawText(it, x + 5f, y - 4f, labelPaint) }
        }
        schema.labels.forEach { label ->
            val x = map(label.coordinate.x, schema.xAxis.minimum, schema.xAxis.maximum, plot.left, plot.right)
            val y = map(label.coordinate.y, schema.yAxis.minimum, schema.yAxis.maximum, plot.bottom, plot.top)
            canvas.drawText(label.text, x + 4f, y - 4f, labelPaint)
        }
        canvas.restore()

        drawTickLabels(canvas, plot, schema)

        if (schema.xAxis.label.isNotBlank()) {
            canvas.drawText(
                schema.xAxis.label,
                plot.right - labelPaint.measureText(schema.xAxis.label) - 4f,
                plot.top + 10f,
                labelPaint,
            )
        }
        if (schema.yAxis.label.isNotBlank()) {
            canvas.drawText(schema.yAxis.label, plot.left + 4f, plot.top + 10f, labelPaint)
        }
    }

    private fun drawTickLabels(
        canvas: Canvas,
        plot: RectF,
        schema: FigureSchema.Cartesian,
    ) {
        val xTicks = schema.xAxis.tickCount.coerceIn(2, 10)
        for (index in 0..xTicks) {
            val fraction = index.toFloat() / xTicks
            val value = schema.xAxis.minimum +
                (schema.xAxis.maximum - schema.xAxis.minimum) * fraction
            val text = formatTick(value)
            val x = plot.left + plot.width() * fraction
            canvas.drawText(text, x - labelPaint.measureText(text) / 2f, plot.bottom + 11f, labelPaint)
        }
        val yTicks = schema.yAxis.tickCount.coerceIn(2, 10)
        for (index in 0..yTicks) {
            val fraction = index.toFloat() / yTicks
            val value = schema.yAxis.maximum -
                (schema.yAxis.maximum - schema.yAxis.minimum) * fraction
            val text = formatTick(value)
            val y = plot.top + plot.height() * fraction
            canvas.drawText(text, plot.left - labelPaint.measureText(text) - 4f, y + 3f, labelPaint)
        }
    }

    private fun drawGrid(canvas: Canvas, plot: RectF, tickCount: Int, vertical: Boolean) {
        val count = tickCount.coerceIn(2, 10)
        for (index in 0..count) {
            val fraction = index.toFloat() / count
            if (vertical) {
                val x = plot.left + plot.width() * fraction
                canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            } else {
                val y = plot.top + plot.height() * fraction
                canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            }
        }
    }

    private fun drawSymbolTable(canvas: Canvas, schema: FigureSchema.SymbolTable, bounds: RectF) {
        val columns = max(schema.headers.size, schema.rows.maxOfOrNull(List<String>::size) ?: 0)
        if (columns == 0) return
        val table = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val columnWidth = table.width() / columns
        val rows = listOf(schema.headers) + schema.rows
        rows.forEachIndexed { rowIndex, row ->
            val top = table.top + rowIndex * TABLE_ROW_HEIGHT
            val bottom = top + TABLE_ROW_HEIGHT
            val background = if (rowIndex == 0) Color.rgb(232, 238, 234) else Color.WHITE
            canvas.drawRect(table.left, top, table.right, bottom, fillPaint(background))
            repeat(columns) { columnIndex ->
                val left = table.left + columnIndex * columnWidth
                val cellBounds = RectF(left, top, left + columnWidth, bottom)
                canvas.drawRect(cellBounds, strokePaint(Color.rgb(190, 197, 192), 0.8f))
                val value = row.getOrNull(columnIndex).orEmpty()
                canvas.drawText(
                    ellipsize(value, columnWidth - 8f, tablePaint),
                    left + 4f,
                    bottom - 5f,
                    tablePaint,
                )
            }
        }
    }

    private fun map(value: Double, minimum: Double, maximum: Double, start: Float, end: Float): Float =
        (start + ((value - minimum) / (maximum - minimum)).toFloat() * (end - start))

    private fun formatTick(value: Double): String = when {
        value == -0.0 -> "0"
        value % 1.0 == 0.0 -> value.toLong().toString()
        else -> String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    }

    private fun ellipsize(value: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(value) <= maxWidth) return value
        val suffix = "…"
        var end = value.length
        while (end > 0 && paint.measureText(value, 0, end) + paint.measureText(suffix) > maxWidth) {
            end -= Character.charCount(value.codePointBefore(end))
        }
        return value.substring(0, end) + suffix
    }

    private fun seriesPaint(style: FigureSeriesStyle) = strokePaint(
        when (style) {
            FigureSeriesStyle.PRIMARY -> Color.rgb(38, 98, 74)
            FigureSeriesStyle.SECONDARY -> Color.rgb(77, 111, 145)
            FigureSeriesStyle.EMPHASIS -> Color.rgb(194, 91, 58)
        },
        if (style == FigureSeriesStyle.EMPHASIS) 2.4f else 1.8f,
    )

    private fun fillPaint(colorValue: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorValue
        style = Paint.Style.FILL
    }

    private fun strokePaint(colorValue: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorValue
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(42, 45, 43)
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(89, 95, 91)
        textSize = 8.5f
    }
    private val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(42, 45, 43)
        textSize = 9f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(42, 45, 43)
        textSize = 8.5f
    }
    private val gridPaint = strokePaint(Color.rgb(229, 232, 230), 0.7f)
    private val axisPaint = strokePaint(Color.rgb(79, 86, 82), 1.2f)
}
