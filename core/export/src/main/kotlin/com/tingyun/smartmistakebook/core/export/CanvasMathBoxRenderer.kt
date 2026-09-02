package com.tingyun.smartmistakebook.core.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.tingyun.smartmistakebook.core.model.MathBox
import com.tingyun.smartmistakebook.core.model.MathBoxBuilder
import com.tingyun.smartmistakebook.core.model.MathBudget
import com.tingyun.smartmistakebook.core.model.MathMetrics
import com.tingyun.smartmistakebook.core.model.parseAndBuildBox

/**
 * Draws a [MathBox] layout tree onto an [android.graphics.Canvas].
 *
 * This is the PDF/print counterpart of the Compose renderer in
 * `core/ui/MathBoxComposeRenderer.kt`: it walks the same
 * `Tokenizer -> Parser -> AST -> Budget -> BoxLayout` pipeline and paints the
 * laid-out boxes with `android.graphics.Paint` instead of a Compose
 * `DrawScope`. Fraction bars, radical signs, matrices, aligned rows and cases
 * braces are drawn from [MathBox] dimensions, so a text-only problem renders
 * as a real typeset sheet rather than monospace LaTeX.
 *
 * Parsing is budget-bounded via [parseAndBuildBox]; when the formula exceeds
 * budget or fails to parse, [buildMathBox] returns null and the caller decides
 * how to fall back.
 */
internal object CanvasMathBoxRenderer {

    /** Returns the laid-out box for [formula], or null when it fails budget/parse. */
    fun buildMathBox(formula: String, metrics: MathMetrics = MathMetrics.DEFAULT): MathBox? {
        val result = parseAndBuildBox(formula)
        val node = result.node ?: return null
        val box = MathBoxBuilder.buildBox(node, metrics)
        return box.takeIf { MathBudget.checkLayoutSize(it.width, it.height, metrics) == null }
    }

    /** Draws a pre-built [box] layout tree onto [canvas]. */
    fun drawBox(
        canvas: Canvas,
        box: MathBox,
        origin: CanvasPoint,
        color: Int,
        strokeWidth: Float,
        metrics: MathMetrics,
    ) {
        when (box) {
            is MathBox.Leaf -> drawLeaf(canvas, box, origin, color, metrics)

            is MathBox.HBox -> {
                var cursorX = origin.x
                box.children.forEach { child ->
                    drawBox(
                        canvas = canvas,
                        box = child,
                        origin = CanvasPoint(cursorX, origin.y),
                        color = color,
                        strokeWidth = strokeWidth,
                        metrics = metrics,
                    )
                    cursorX += child.width
                }
            }

            is MathBox.VBox -> {
                var cursorY = origin.y
                box.children.forEach { child ->
                    drawBox(
                        canvas = canvas,
                        box = child,
                        origin = CanvasPoint(origin.x, cursorY),
                        color = color,
                        strokeWidth = strokeWidth,
                        metrics = metrics,
                    )
                    cursorY += child.height
                }
            }

            is MathBox.FractionBox -> {
                val numerator = box.numerator
                val denominator = box.denominator
                val barY = origin.y + denominator.height + 1f
                drawLine(
                    canvas,
                    CanvasPoint(origin.x, barY),
                    CanvasPoint(origin.x + box.width, barY),
                    color,
                    strokeWidth * 2f,
                )
                drawBox(
                    canvas = canvas,
                    box = numerator,
                    origin = CanvasPoint(
                        origin.x + (box.width - numerator.width) / 2f,
                        origin.y,
                    ),
                    color = color,
                    strokeWidth = strokeWidth,
                    metrics = metrics,
                )
                drawBox(
                    canvas = canvas,
                    box = denominator,
                    origin = CanvasPoint(
                        origin.x + (box.width - denominator.width) / 2f,
                        origin.y + denominator.height + 2f,
                    ),
                    color = color,
                    strokeWidth = strokeWidth,
                    metrics = metrics,
                )
            }

            is MathBox.RadicalBox -> {
                val contentHeight = box.height - 4f
                val tickLeft = origin.x
                val tickTop = origin.y + contentHeight * 0.3f
                drawLine(
                    canvas,
                    CanvasPoint(tickLeft, tickTop + contentHeight),
                    CanvasPoint(tickLeft + 3f, tickTop),
                    color,
                    strokeWidth,
                )
                drawLine(
                    canvas,
                    CanvasPoint(tickLeft + 3f, tickTop),
                    CanvasPoint(tickLeft + 6f, origin.y + contentHeight),
                    color,
                    strokeWidth,
                )
                drawLine(
                    canvas,
                    CanvasPoint(origin.x + 6f, origin.y),
                    CanvasPoint(origin.x + box.width, origin.y),
                    color,
                    strokeWidth,
                )
                drawBox(
                    canvas = canvas,
                    box = box.content,
                    origin = CanvasPoint(origin.x + 6f, origin.y + 2f),
                    color = color,
                    strokeWidth = strokeWidth,
                    metrics = metrics,
                )
                box.index?.let { index ->
                    drawBox(
                        canvas = canvas,
                        box = index,
                        origin = CanvasPoint(origin.x - index.width, origin.y + 2f),
                        color = color,
                        strokeWidth = strokeWidth,
                        metrics = metrics,
                    )
                }
            }

            is MathBox.MatrixBox -> drawMatrix(canvas, box, origin, color, strokeWidth, metrics)
            is MathBox.AlignedBox -> {
                val charHeight = box.rows.firstOrNull()?.firstOrNull()?.height ?: metrics.charHeight
                drawMatrixRows(
                    canvas = canvas,
                    rows = box.rows,
                    origin = origin,
                    color = color,
                    strokeWidth = strokeWidth,
                    columnGap = charHeight,
                    rowGap = charHeight * 0.25f,
                    metrics = metrics,
                )
            }

            is MathBox.CasesBox -> {
                val charHeight = box.rows.firstOrNull()?.height ?: metrics.charHeight
                val braceWidth = charHeight * 0.75f
                drawTextDelimiter(canvas, "{", origin, box.height, color, metrics)
                var cursorY = origin.y
                box.rows.forEach { row ->
                    drawBox(
                        canvas = canvas,
                        box = row,
                        origin = CanvasPoint(origin.x + braceWidth, cursorY),
                        color = color,
                        strokeWidth = strokeWidth,
                        metrics = metrics,
                    )
                    cursorY += row.height + box.rowGap
                }
            }
        }
    }

    private fun drawMatrix(
        canvas: Canvas,
        box: MathBox.MatrixBox,
        origin: CanvasPoint,
        color: Int,
        strokeWidth: Float,
        metrics: MathMetrics,
    ) {
        val charHeight = box.rows.firstOrNull()?.firstOrNull()?.height ?: metrics.charHeight
        val charWidth = charHeight * 0.5f
        drawTextDelimiter(canvas, box.leftDelimiter, origin, box.height, color, metrics)
        drawMatrixRows(
            canvas = canvas,
            rows = box.rows,
            origin = CanvasPoint(origin.x + charWidth, origin.y),
            color = color,
            strokeWidth = strokeWidth,
            columnGap = charWidth,
            rowGap = charHeight * 0.25f,
            metrics = metrics,
        )
        drawTextDelimiter(
            canvas,
            box.rightDelimiter,
            CanvasPoint(origin.x + box.width - charWidth, origin.y),
            box.height,
            color,
            metrics,
        )
    }

    private fun drawMatrixRows(
        canvas: Canvas,
        rows: List<List<MathBox>>,
        origin: CanvasPoint,
        color: Int,
        strokeWidth: Float,
        columnGap: Float,
        rowGap: Float,
        metrics: MathMetrics,
    ) {
        var cursorY = origin.y
        rows.forEachIndexed { rowIndex, row ->
            var cursorX = origin.x
            val rowHeight = row.maxOfOrNull { it.height } ?: 0f
            row.forEachIndexed { columnIndex, cell ->
                drawBox(
                    canvas = canvas,
                    box = cell,
                    origin = CanvasPoint(
                        cursorX,
                        cursorY + (rowHeight - cell.height) / 2f,
                    ),
                    color = color,
                    strokeWidth = strokeWidth,
                    metrics = metrics,
                )
                cursorX += cell.width + columnGap
                if (columnIndex < row.lastIndex) {
                    drawLine(
                        canvas,
                        CanvasPoint(cursorX - columnGap / 2f, origin.y),
                        CanvasPoint(
                            cursorX - columnGap / 2f,
                            origin.y + matrixRowsHeight(rows, rowGap),
                        ),
                        color,
                        strokeWidth / 2f,
                    )
                }
            }
            cursorY += rowHeight
            if (rowIndex < rows.lastIndex) {
                cursorY += rowGap
            }
        }
    }

    private fun matrixRowsHeight(rows: List<List<MathBox>>, rowGap: Float): Float =
        rows.sumOf { row -> (row.maxOfOrNull { it.height } ?: 0f).toDouble() }.toFloat() +
            rowGap * (rows.size - 1).coerceAtLeast(0)

    private fun drawTextDelimiter(
        canvas: Canvas,
        delimiter: String,
        origin: CanvasPoint,
        height: Float,
        color: Int,
        metrics: MathMetrics,
    ) {
        drawLeaf(
            canvas,
            MathBox.Leaf(height, height, height, delimiter),
            origin,
            color,
            metrics,
        )
    }

    private fun drawLeaf(
        canvas: Canvas,
        box: MathBox.Leaf,
        origin: CanvasPoint,
        color: Int,
        metrics: MathMetrics,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = metrics.charHeight
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        canvas.drawText(box.content, origin.x, origin.y + box.baseline, paint)
    }

    private fun drawLine(
        canvas: Canvas,
        start: CanvasPoint,
        end: CanvasPoint,
        color: Int,
        strokeWidth: Float,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.strokeWidth = strokeWidth
            style = Paint.Style.STROKE
        }
        canvas.drawLine(start.x, start.y, end.x, end.y, paint)
    }

    data class CanvasPoint(val x: Float, val y: Float)
}
