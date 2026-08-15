package com.tingyun.smartmistakebook.core.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.FigureAxis
import com.tingyun.smartmistakebook.core.model.FigureCoordinate
import com.tingyun.smartmistakebook.core.model.FigureSchema
import com.tingyun.smartmistakebook.core.model.FigureSeriesStyle
import com.tingyun.smartmistakebook.core.model.InlineToken
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.ReadableMathText
import com.tingyun.smartmistakebook.core.model.SafeInlineMarkdown
import com.tingyun.smartmistakebook.core.model.StructuredChoice
import com.tingyun.smartmistakebook.core.model.StructuredContentLimits
import com.tingyun.smartmistakebook.core.model.StructuredContentSanitizer
import java.util.Locale

/**
 * Renders the bounded structured-content contract without WebView, network images, HTML, or an
 * executable LaTeX engine. Every input is sanitized again at this final trust boundary.
 */
@Composable
fun StructuredContentRenderer(
    document: QuestionDocument,
    modifier: Modifier = Modifier,
    selectedChoices: Map<String, String> = emptyMap(),
    choicesEnabled: Boolean = true,
    onChoiceSelected: (groupId: String, choiceId: String) -> Unit = { _, _ -> },
) {
    // Sanitization is bounded; avoiding the raw document as a remember key also avoids a deep
    // data-class equality walk over an attacker-sized list during recomposition.
    val safeDocument = StructuredContentSanitizer.sanitize(document).document
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        safeDocument.title?.let { title ->
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                color = Ink,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        safeDocument.blocks.forEach { block ->
            key(block.id) {
                when (block) {
                    is ContentBlock.Paragraph -> SafeMarkdownText(
                        markdown = block.markdown,
                        style = MaterialTheme.typography.bodyLarge,
                        emptyFallback = "内容为空",
                    )

                    is ContentBlock.Formula -> FormulaBlock(block)
                    is ContentBlock.ChoiceGroup -> ChoiceGroupBlock(
                        block = block,
                        selectedChoiceId = selectedChoices[block.id] ?: block.selectedChoiceId,
                        enabled = choicesEnabled,
                        onChoiceSelected = { choiceId -> onChoiceSelected(block.id, choiceId) },
                    )

                    is ContentBlock.Figure -> FigureBlock(block)
                    is ContentBlock.Unknown -> Unit // Sanitizer always removes this branch.
                }
            }
        }
    }
}

@Composable
fun SafeMarkdownText(
    markdown: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Ink,
    emptyFallback: String? = null,
) {
    val displayText = markdown.ifBlank { emptyFallback.orEmpty() }
    val annotated = remember(displayText) { displayText.toSafeAnnotatedString() }
    Text(
        text = annotated,
        modifier = modifier,
        color = color,
        style = style,
    )
}

@Composable
private fun FormulaBlock(block: ContentBlock.Formula) {
    val spokenDescription = remember(block.alternativeText, block.latex) {
        block.alternativeText.ifBlank {
            "公式文本：${block.latex.take(StructuredContentLimits.MAX_ACCESSIBILITY_CHARS)}"
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "公式（受限文本显示）：$spokenDescription"
            },
        color = JadeSoft,
        contentColor = Ink,
        shape = RoundedCornerShape(SmartDimens.SurfaceRadius),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "公式 · 文本显示",
                color = JadeActive,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = ReadableMathText.formula(block.latex),
                color = Ink,
                style = if (block.display) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ChoiceGroupBlock(
    block: ContentBlock.ChoiceGroup,
    selectedChoiceId: String?,
    enabled: Boolean,
    onChoiceSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SafeMarkdownText(
            markdown = block.promptMarkdown,
            style = MaterialTheme.typography.titleMedium,
            emptyFallback = "请选择一个选项",
        )
        if (block.choices.isEmpty()) {
            Text(
                text = "暂无可选项",
                modifier = Modifier.semantics {
                    contentDescription = "此问题当前没有可选择的答案"
                },
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            block.choices.forEachIndexed { index, choice ->
                ChoiceRow(
                    choice = choice,
                    spokenPosition = "${index + 1}/${block.choices.size}",
                    selected = selectedChoiceId == choice.id,
                    enabled = enabled && block.enabled && choice.enabled,
                    onClick = { onChoiceSelected(choice.id) },
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    choice: StructuredChoice,
    spokenPosition: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val visibleMarkdown = choice.markdown.ifBlank { "未提供选项内容" }
    val description = (choice.accessibilityLabel ?: visibleMarkdown.toPlainText())
        .take(StructuredContentLimits.MAX_ACCESSIBILITY_CHARS)
    val borderColor = when {
        !enabled -> Outline
        selected -> JadeActive
        else -> InkSecondary
    }
    val backgroundColor = if (selected) JadeSoft else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = SmartDimens.MinimumTouchTarget)
            .clip(RoundedCornerShape(SmartDimens.ChipRadius))
            .background(backgroundColor)
            .border(1.25.dp, SolidColor(borderColor), RoundedCornerShape(SmartDimens.ChipRadius))
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics {
                contentDescription = "选项 $spokenPosition：$description"
                stateDescription = if (selected) "已选择" else "未选择"
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .border(2.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(JadeActive, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        SafeMarkdownText(
            markdown = visibleMarkdown,
            modifier = Modifier.weight(1f),
            color = if (enabled) Ink else InkMuted,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun FigureBlock(block: ContentBlock.Figure) {
    val accessibleAlternative = block.alternativeText.ifBlank { "未提供图示说明" }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        block.title?.let { title ->
            Text(
                text = title,
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        when (val schema = block.schema) {
            is FigureSchema.Cartesian -> CartesianFigure(
                schema = schema,
                alternativeText = accessibleAlternative,
            )

            is FigureSchema.SymbolTable -> SymbolTableFigure(
                schema = schema,
                alternativeText = accessibleAlternative,
            )

            is FigureSchema.Unknown -> SafeMarkdownText(
                markdown = SafeInlineMarkdown.literal(schema.fallbackText),
                modifier = Modifier.semantics {
                    contentDescription = "不支持的确定性图示：$accessibleAlternative"
                },
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
                emptyFallback = "暂不支持此图示类型",
            )
        }
    }
}

@Composable
private fun CartesianFigure(
    schema: FigureSchema.Cartesian,
    alternativeText: String,
) {
    val hasData = schema.polylines.any { it.points.isNotEmpty() } ||
        schema.points.isNotEmpty() ||
        schema.labels.isNotEmpty()
    val spokenDescription = if (hasData) {
        "确定性坐标图：$alternativeText"
    } else {
        "空坐标图，没有数据点：$alternativeText"
    }
    val axisColor = InkSecondary
    val gridColor = Divider
    val primaryColor = JadeActive
    val secondaryColor = InkSecondary
    val emphasisColor = ErrorWarm
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.55f)
            .clip(RoundedCornerShape(SmartDimens.SurfaceRadius))
            .background(Paper)
            .border(1.dp, Outline, RoundedCornerShape(SmartDimens.SurfaceRadius))
            .semantics {
                contentDescription = spokenDescription
            },
    ) {
        val plotLeft = 48.dp.toPx()
        val plotTop = 18.dp.toPx()
        val plotRight = size.width - 18.dp.toPx()
        val plotBottom = size.height - 38.dp.toPx()
        if (
            !size.width.isFinite() ||
            !size.height.isFinite() ||
            !plotLeft.isFinite() ||
            !plotTop.isFinite() ||
            !plotRight.isFinite() ||
            !plotBottom.isFinite() ||
            plotRight <= plotLeft ||
            plotBottom <= plotTop
        ) return@Canvas

        val xToPixel: (Double) -> Float = { x ->
            mapAxisValueToPixel(x, schema.xAxis, plotLeft, plotRight)
        }
        val yToPixel: (Double) -> Float = { y ->
            mapAxisValueToPixel(y, schema.yAxis, plotBottom, plotTop)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = axisColor.toArgb()
            textSize = 11.sp.toPx()
        }
        val dataLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ink.toArgb()
            textSize = 12.sp.toPx()
        }

        drawGridAndTicks(
            xAxis = schema.xAxis,
            yAxis = schema.yAxis,
            plotLeft = plotLeft,
            plotTop = plotTop,
            plotRight = plotRight,
            plotBottom = plotBottom,
            xToPixel = xToPixel,
            yToPixel = yToPixel,
            gridColor = gridColor,
            axisColor = axisColor,
            labelPaint = labelPaint,
        )

        fun FigureSeriesStyle.color(): Color = when (this) {
            FigureSeriesStyle.PRIMARY -> primaryColor
            FigureSeriesStyle.SECONDARY -> secondaryColor
            FigureSeriesStyle.EMPHASIS -> emphasisColor
        }

        schema.polylines.forEach { line ->
            val color = line.style.color()
            line.points.zipWithNext().forEach { (start, end) ->
                drawLine(
                    color = color,
                    start = Offset(xToPixel(start.x), yToPixel(start.y)),
                    end = Offset(xToPixel(end.x), yToPixel(end.y)),
                    strokeWidth = 2.5.dp.toPx(),
                )
            }
            line.points.forEach { point ->
                drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(xToPixel(point.x), yToPixel(point.y)))
            }
            line.label?.let { label ->
                line.points.lastOrNull()?.let { point ->
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        finiteOffset(xToPixel(point.x), 5.dp.toPx()),
                        finiteOffset(yToPixel(point.y), -5.dp.toPx()),
                        dataLabelPaint,
                    )
                }
            }
        }
        schema.points.forEach { point ->
            val center = Offset(xToPixel(point.coordinate.x), yToPixel(point.coordinate.y))
            drawCircle(point.style.color(), radius = 4.dp.toPx(), center = center)
            point.label?.let { label ->
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    finiteOffset(center.x, 5.dp.toPx()),
                    finiteOffset(center.y, -5.dp.toPx()),
                    dataLabelPaint,
                )
            }
        }
        schema.labels.forEach { label ->
            drawContext.canvas.nativeCanvas.drawText(
                label.text,
                finiteOffset(xToPixel(label.coordinate.x), 4.dp.toPx()),
                finiteOffset(yToPixel(label.coordinate.y), -4.dp.toPx()),
                dataLabelPaint,
            )
        }
    }
}

private fun DrawScope.drawGridAndTicks(
    xAxis: FigureAxis,
    yAxis: FigureAxis,
    plotLeft: Float,
    plotTop: Float,
    plotRight: Float,
    plotBottom: Float,
    xToPixel: (Double) -> Float,
    yToPixel: (Double) -> Float,
    gridColor: Color,
    axisColor: Color,
    labelPaint: Paint,
) {
    repeat(xAxis.tickCount + 1) { tick ->
        val value = xAxis.valueAtTick(tick)
        val x = xToPixel(value)
        drawLine(gridColor, Offset(x, plotTop), Offset(x, plotBottom), 1.dp.toPx())
        drawContext.canvas.nativeCanvas.drawText(
            formatTick(value),
            finiteOffset(x, -8.dp.toPx()),
            finiteOffset(plotBottom, 16.dp.toPx()),
            labelPaint,
        )
    }
    repeat(yAxis.tickCount + 1) { tick ->
        val value = yAxis.valueAtTick(tick)
        val y = yToPixel(value)
        drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), 1.dp.toPx())
        drawContext.canvas.nativeCanvas.drawText(
            formatTick(value),
            5.dp.toPx(),
            finiteOffset(y, 4.dp.toPx()),
            labelPaint,
        )
    }

    val xAxisY = if (0.0 in yAxis.minimum..yAxis.maximum) yToPixel(0.0) else plotBottom
    val yAxisX = if (0.0 in xAxis.minimum..xAxis.maximum) xToPixel(0.0) else plotLeft
    drawLine(axisColor, Offset(plotLeft, xAxisY), Offset(plotRight, xAxisY), 1.5.dp.toPx())
    drawLine(axisColor, Offset(yAxisX, plotTop), Offset(yAxisX, plotBottom), 1.5.dp.toPx())
    if (xAxis.label.isNotBlank()) {
        drawContext.canvas.nativeCanvas.drawText(
            xAxis.label,
            finiteOffset(plotRight, -18.dp.toPx()),
            finiteOffset(size.height, -5.dp.toPx()),
            labelPaint,
        )
    }
    if (yAxis.label.isNotBlank()) {
        drawContext.canvas.nativeCanvas.drawText(
            yAxis.label,
            finiteOffset(plotLeft, 4.dp.toPx()),
            finiteOffset(plotTop, 12.dp.toPx()),
            labelPaint,
        )
    }
}

@Composable
private fun SymbolTableFigure(
    schema: FigureSchema.SymbolTable,
    alternativeText: String,
) {
    if (schema.headers.isEmpty()) {
        Text(
            text = "空符号表",
            modifier = Modifier.semantics {
                contentDescription = "空符号表，没有可显示的单元格：$alternativeText"
            },
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val scrollState = rememberScrollState()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "确定性符号表：$alternativeText"
            },
        color = Paper,
        shape = RoundedCornerShape(SmartDimens.SurfaceRadius),
        border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(1.dp),
        ) {
            SymbolTableRow(
                cells = schema.headers,
                backgroundColor = JadeSoft,
                fontWeight = FontWeight.SemiBold,
            )
            schema.rows.forEachIndexed { rowIndex, row ->
                SymbolTableRow(
                    cells = List(schema.headers.size) { column -> row.getOrElse(column) { "" } },
                    backgroundColor = if (rowIndex % 2 == 0) Paper else JadeSoft.copy(alpha = 0.35f),
                )
            }
        }
    }
}

@Composable
private fun SymbolTableRow(
    cells: List<String>,
    backgroundColor: Color,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    Row(modifier = Modifier.background(backgroundColor)) {
        cells.forEach { cell ->
            Text(
                text = cell,
                modifier = Modifier
                    .width(112.dp)
                    .border(0.5.dp, Divider)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                color = Ink,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = fontWeight,
            )
        }
    }
}

private fun String.toSafeAnnotatedString(): AnnotatedString = buildAnnotatedString {
    SafeInlineMarkdown.parse(this@toSafeAnnotatedString).forEach { token ->
        when (token) {
            is InlineToken.Text -> append(token.value)
            is InlineToken.Strong -> withStyle(
                SpanStyle(
                    fontWeight = FontWeight.ExtraBold,  // 使用更粗的字重
                    background = JadeSoft.copy(alpha = 0.25f),  // 明显的背景高亮
                ),
            ) {
                append(token.value)
            }

            is InlineToken.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(token.value)
            }

            is InlineToken.Code -> withStyle(
                SpanStyle(
                    background = JadeSoft,
                    fontFamily = FontFamily.Monospace,
                ),
            ) {
                append(token.value)
            }

            is InlineToken.Formula -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,  // 增加字重使公式更易读
                ),
            ) {
                append(ReadableMathText.formula(token.value))
            }

            InlineToken.LineBreak -> append('\n')
        }
    }
}

private fun String.toPlainText(): String = ReadableMathText.inlineMarkdown(this)

private fun mapAxisValueToPixel(
    value: Double,
    axis: FigureAxis,
    startPixel: Float,
    endPixel: Float,
): Float {
    val span = axis.maximum - axis.minimum
    if (
        !value.isFinite() ||
        !span.isFinite() ||
        span <= 0.0 ||
        !startPixel.isFinite() ||
        !endPixel.isFinite()
    ) return startPixel.takeIf(Float::isFinite) ?: 0f

    val numerator = value - axis.minimum
    val fraction = numerator / span
    if (!numerator.isFinite() || !fraction.isFinite()) return startPixel
    val pixel = startPixel.toDouble() +
        (endPixel.toDouble() - startPixel.toDouble()) * fraction.coerceIn(0.0, 1.0)
    val asFloat = pixel.toFloat()
    return asFloat.takeIf(Float::isFinite) ?: startPixel
}

private fun FigureAxis.valueAtTick(tick: Int): Double {
    val span = maximum - minimum
    if (!minimum.isFinite() || !span.isFinite() || span <= 0.0) return 0.0
    val fraction = tick.toDouble() / tickCount.coerceAtLeast(1).toDouble()
    val value = minimum + span * fraction.coerceIn(0.0, 1.0)
    return value.takeIf(Double::isFinite) ?: minimum
}

private fun finiteOffset(base: Float, delta: Float): Float {
    val result = base + delta
    return result.takeIf(Float::isFinite) ?: base.takeIf(Float::isFinite) ?: 0f
}

private fun formatTick(value: Double): String = if (!value.isFinite()) {
    "?"
} else if (value % 1.0 == 0.0) {
    value.toLong().toString()
} else {
    String.format(Locale.ROOT, "%.1f", value)
}
