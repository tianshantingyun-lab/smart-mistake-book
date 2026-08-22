package com.tingyun.smartmistakebook.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.MathBox
import com.tingyun.smartmistakebook.core.model.MathBudget
import com.tingyun.smartmistakebook.core.model.MathBoxBuilder
import com.tingyun.smartmistakebook.core.model.MathNode
import com.tingyun.smartmistakebook.core.model.MathParser
import com.tingyun.smartmistakebook.core.model.parseAndBuildBox

/**
 * Compose renderer for the box-layout math pipeline.
 *
 * Unlike the Unicode-linear [com.tingyun.smartmistakebook.core.model
 * .ReadableMathText] fallback, this draws true fraction bars, radical signs,
 * and matrix grids from [MathBox] dimensions. It is the production wrapper
 * around `Tokenizer -> Parser -> AST -> Budget -> BoxLayout -> Compose draw`
 * mandated by the audit (section 13).
 *
 * Parsing is budget-bounded via [parseAndBuildBox]; when the formula exceeds
 * budget or fails to parse, the caller should fall back to
 * [com.tingyun.smartmistakebook.core.model.ReadableMathText].
 */
@Composable
fun MathFormulaBox(
    formula: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Black,
    fallbackText: String? = null,
) {
    val box = rememberMathBox(formula)
    val content = when (box) {
        null -> fallbackText ?: formula
        else -> null
    }

    if (content != null) {
        androidx.compose.material3.Text(
            text = content,
            modifier = modifier,
            color = color,
        )
        return
    }

    Box(modifier = modifier) {
        val boxNonNull = box
        if (boxNonNull != null) {
            Canvas(
                modifier = Modifier
                    .size(
                        width = with(androidx.compose.ui.platform.LocalDensity.current) {
                            boxNonNull.width.toDp()
                        },
                        height = with(androidx.compose.ui.platform.LocalDensity.current) {
                            boxNonNull.height.toDp()
                        },
                    ),
            ) {
                drawMathBox(boxNonNull, Offset.Zero, color, 1f)
            }
        }
    }
}

/**
 * Returns the parsed [MathBox] for a formula, or null when it exceeds budget.
 */
@Composable
private fun rememberMathBox(formula: String): MathBox? {
    return androidx.compose.runtime.remember(formula) {
        val result = parseAndBuildBox(formula)
        result.node?.let { MathBoxBuilder.buildBox(it) }
    }
}

private val DENSITY = 1f

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMathBox(
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
        is MathBox.AlignedBox -> drawMatrixRows(box.rows, origin, color, strokeWidth)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMatrix(
    box: MathBox.MatrixBox,
    origin: Offset,
    color: Color,
    strokeWidth: Float,
) {
    drawTextDelimiter(box.leftDelimiter, origin, box.height, color)
    drawMatrixRows(box.rows, Offset(origin.x + 8f, origin.y), color, strokeWidth)
    drawTextDelimiter(
        box.rightDelimiter,
        Offset(origin.x + box.width - 8f, origin.y),
        box.height,
        color,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMatrixRows(
    rows: List<List<MathBox>>,
    origin: Offset,
    color: Color,
    strokeWidth: Float,
) {
    var cursorY = origin.y
    rows.forEachIndexed { rowIndex, row ->
        var cursorX = origin.x
        val rowHeight = row.maxOfOrNull { it.height } ?: 0f
        row.forEachIndexed { colIndex, cell ->
            // Center the cell vertically within the row.
            drawMathBox(cell, Offset(cursorX, cursorY + (rowHeight - cell.height) / 2f), color, strokeWidth)
            cursorX += cell.width + 8f
            if (colIndex < row.lastIndex) {
                drawLine(
                    color,
                    Offset(cursorX - 4f, origin.y),
                    Offset(cursorX - 4f, origin.y + boxHeight(rows)),
                    strokeWidth / 2,
                )
            }
        }
        cursorY += rowHeight
    }
}

private fun boxHeight(rows: List<List<MathBox>>): Float =
    rows.sumOf { row -> (row.maxOfOrNull { it.height } ?: 0f).toDouble() }.toFloat()

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTextDelimiter(
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
 * Parse-bounded entry point: returns the [MathBox] for a formula, or null.
 */
fun buildMathBoxOrNull(formula: String): MathBox? {
    val result = parseAndBuildBox(formula)
    return result.node?.let { MathBoxBuilder.buildBox(it) }
}
