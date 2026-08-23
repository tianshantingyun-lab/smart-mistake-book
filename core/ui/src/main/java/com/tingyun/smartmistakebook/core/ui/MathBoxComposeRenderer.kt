package com.tingyun.smartmistakebook.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingyun.smartmistakebook.core.model.MathBox
import com.tingyun.smartmistakebook.core.model.MathBudget
import com.tingyun.smartmistakebook.core.model.MathBoxBuilder
import com.tingyun.smartmistakebook.core.model.MathMetrics
import com.tingyun.smartmistakebook.core.model.parseAndBuildBox

/**
 * Compose renderer for the box-layout math pipeline.
 *
 * Unlike the Unicode-linear [com.tingyun.smartmistakebook.core.model
 * .ReadableMathText] fallback, this draws true fraction bars, radical signs,
 * matrix grids and cases braces from [MathBox] dimensions. It is the
 * production wrapper around
 * `Tokenizer -> Parser -> AST -> Budget -> BoxLayout -> Compose draw`
 * mandated by the audit (section 13).
 *
 * Parsing is budget-bounded via [parseAndBuildBox]; when the formula exceeds
 * budget or fails to parse, the composable degrades to [fallbackText] or the
 * raw formula text (原文透传).
 *
 * Box dimensions are derived from [fontSize] (sp, already scaled by the
 * system font scale via [LocalDensity]), so formulas keep proportional size
 * at 200% font scale.
 */
@Composable
fun MathFormulaBox(
    formula: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Black,
    fallbackText: String? = null,
    fontSize: TextUnit = 16.sp,
) {
    val density = LocalDensity.current
    // sp -> px applies the system font scale, so 200% font doubles the boxes.
    val fontSizePx = with(density) { fontSize.toPx() }
    val box = remember(formula, fontSizePx) {
        val result = parseAndBuildBox(formula)
        result.node?.let { node ->
            val metrics = MathMetrics.of(fontSizePx)
            val laid = MathBoxBuilder.buildBox(node, metrics)
            // Re-check the layout budget at the effective font size; a huge
            // font scale can push a budget-valid formula past the layout cap.
            laid.takeIf { MathBudget.checkLayoutSize(it.width, it.height, metrics) == null }
        }
    }

    val content = if (box == null) fallbackText ?: formula else null
    if (content != null) {
        Text(
            text = content,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
        )
        return
    }

    val laidOut = box ?: return
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .size(
                    width = with(density) { laidOut.width.toDp() },
                    height = with(density) { laidOut.height.toDp() },
                ),
        ) {
            drawMathBox(laidOut, Offset.Zero, color, maxOf(1f, fontSizePx * 0.0625f))
        }
    }
}

private fun DrawScope.drawMathBox(
    box: MathBox,
    origin: Offset,
    color: Color,
    strokeWidth: Float,
) {
    when (box) {
        is MathBox.Leaf -> drawContext.canvas.nativeCanvas.drawText(
            box.content,
            origin.x,
            origin.y + box.baseline,
            android.graphics.Paint().apply {
                this.color = color.toArgbInt()
                textSize = box.height
                isAntiAlias = true
            },
        )

        is MathBox.HBox -> {
            var cursorX = origin.x
            box.children.forEach { child ->
                drawMathBox(child, Offset(cursorX, origin.y), color, strokeWidth)
                cursorX += child.width
            }
        }

        is MathBox.VBox -> {
            // Stack children vertically; baseline alignment handled by caller.
            var cursorY = origin.y
            box.children.forEach { child ->
                drawMathBox(child, Offset(origin.x, cursorY), color, strokeWidth)
                cursorY += child.height
            }
        }

        is MathBox.FractionBox -> {
            val num = box.numerator
            val den = box.denominator
            val barY = origin.y + den.height + 1f
            drawLine(
                color = color,
                start = Offset(origin.x, barY),
                end = Offset(origin.x + box.width, barY),
                strokeWidth = strokeWidth * 2,
                cap = StrokeCap.Round,
            )
            // Numerator sits above the bar, denominator below.
            drawMathBox(
                num,
                Offset(origin.x + (box.width - num.width) / 2f, origin.y),
                color,
                strokeWidth,
            )
            drawMathBox(
                den,
                Offset(origin.x + (box.width - den.width) / 2f, origin.y + den.height + 2f),
                color,
                strokeWidth,
            )
        }

        is MathBox.RadicalBox -> {
            // Draw the radical tick + overline surrounding the content.
            val contentHeight = box.height - 4f
            val tickL = origin.x
            val tickTop = origin.y + contentHeight * 0.3f
            drawLine(color, Offset(tickL, tickTop + contentHeight), Offset(tickL + 3f, tickTop), strokeWidth)
            drawLine(color, Offset(tickL + 3f, tickTop), Offset(tickL + 6f, origin.y + contentHeight), strokeWidth)
            drawLine(
                color,
                Offset(origin.x + 6f, origin.y),
                Offset(origin.x + box.width, origin.y),
                strokeWidth,
            )
            drawMathBox(box.content, Offset(origin.x + 6f, origin.y + 2f), color, strokeWidth)
            box.index?.let { index ->
                drawMathBox(index, Offset(origin.x - index.width, origin.y + 2f), color, strokeWidth)
            }
        }

        is MathBox.MatrixBox -> drawMatrix(box, origin, color, strokeWidth)
        is MathBox.AlignedBox -> {
            val charHeight = box.rows.firstOrNull()?.firstOrNull()?.height ?: 16f
            drawMatrixRows(
                rows = box.rows,
                origin = origin,
                color = color,
                strokeWidth = strokeWidth,
                columnGap = charHeight,
                rowGap = charHeight * 0.25f,
            )
        }
        is MathBox.CasesBox -> {
            val charHeight = box.rows.firstOrNull()?.height ?: 16f
            val braceWidth = charHeight * 0.75f
            drawTextDelimiter("{", origin, box.height, color)
            var cursorY = origin.y
            box.rows.forEach { row ->
                drawMathBox(row, Offset(origin.x + braceWidth, cursorY), color, strokeWidth)
                cursorY += row.height + box.rowGap
            }
        }
    }
}

private fun DrawScope.drawMatrix(
    box: MathBox.MatrixBox,
    origin: Offset,
    color: Color,
    strokeWidth: Float,
) {
    val charHeight = box.rows.firstOrNull()?.firstOrNull()?.height ?: 16f
    val charWidth = charHeight * 0.5f
    drawTextDelimiter(box.leftDelimiter, origin, box.height, color)
    drawMatrixRows(
        rows = box.rows,
        origin = Offset(origin.x + charWidth, origin.y),
        color = color,
        strokeWidth = strokeWidth,
        columnGap = charWidth,
        rowGap = charHeight * 0.25f,
    )
    drawTextDelimiter(
        box.rightDelimiter,
        Offset(origin.x + box.width - charWidth, origin.y),
        box.height,
        color,
    )
}

private fun DrawScope.drawMatrixRows(
    rows: List<List<MathBox>>,
    origin: Offset,
    color: Color,
    strokeWidth: Float,
    columnGap: Float,
    rowGap: Float,
) {
    var cursorY = origin.y
    rows.forEachIndexed { rowIndex, row ->
        var cursorX = origin.x
        val rowHeight = row.maxOfOrNull { it.height } ?: 0f
        row.forEachIndexed { colIndex, cell ->
            // Center the cell vertically within the row.
            drawMathBox(cell, Offset(cursorX, cursorY + (rowHeight - cell.height) / 2f), color, strokeWidth)
            cursorX += cell.width + columnGap
            if (colIndex < row.lastIndex) {
                drawLine(
                    color,
                    Offset(cursorX - columnGap / 2f, origin.y),
                    Offset(cursorX - columnGap / 2f, origin.y + boxHeight(rows, rowGap)),
                    strokeWidth / 2,
                )
            }
        }
        cursorY += rowHeight
        if (rowIndex < rows.lastIndex) {
            cursorY += rowGap
        }
    }
}

private fun boxHeight(rows: List<List<MathBox>>, rowGap: Float): Float =
    rows.sumOf { row -> (row.maxOfOrNull { it.height } ?: 0f).toDouble() }.toFloat() +
        rowGap * (rows.size - 1).coerceAtLeast(0)

private fun DrawScope.drawTextDelimiter(
    delimiter: String,
    origin: Offset,
    height: Float,
    color: Color,
) {
    drawContext.canvas.nativeCanvas.drawText(
        delimiter,
        origin.x,
        origin.y + height,
        android.graphics.Paint().apply {
            this.color = color.toArgbInt()
            textSize = height
            isAntiAlias = true
        },
    )
}

private fun Color.toArgbInt(): Int = this.toArgb()

/**
 * Parse-bounded entry point: returns the [MathBox] for a formula, or null
 * when parsing fails or any budget (including layout width/height) is
 * violated at the given [metrics].
 */
fun buildMathBoxOrNull(formula: String, metrics: MathMetrics = MathMetrics.DEFAULT): MathBox? {
    val result = parseAndBuildBox(formula)
    return result.node?.let { node ->
        MathBoxBuilder.buildBox(node, metrics)
            .takeIf { MathBudget.checkLayoutSize(it.width, it.height, metrics) == null }
    }
}
