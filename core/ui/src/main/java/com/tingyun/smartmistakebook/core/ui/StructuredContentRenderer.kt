package com.tingyun.smartmistakebook.core.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.tingyun.smartmistakebook.core.model.TutorMarkdownChunkChain
import com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    contentIdentity: Any = markdown,
) {
    val displayText = markdown.ifBlank { emptyFallback.orEmpty() }
    val parsed = produceState(
        initialValue = SafeMarkdownParseResult(
            source = "",
            contentIdentity = contentIdentity,
            annotated = AnnotatedString(""),
        ),
        key1 = displayText,
        key2 = contentIdentity,
    ) {
        value = SafeMarkdownParseResult(
            source = displayText,
            contentIdentity = contentIdentity,
            annotated = parseSafeMarkdown(displayText),
        )
    }.value
    Text(
        text = safeMarkdownWhileParsing(
            displayText = displayText,
            contentIdentity = contentIdentity,
            parsed = parsed,
        ),
        modifier = modifier,
        color = color,
        style = style,
    )
}

@Composable
fun StreamingSafeMarkdownText(
    snapshot: TutorMarkdownSnapshot,
    contentIdentity: Any,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Ink,
) {
    val parser = remember(contentIdentity) {
        IncrementalSafeMarkdownParser()
    }
    val parseKey = StreamingMarkdownParseKey(
        stableContent = snapshot.stableContent,
        provisionalContent = snapshot.provisionalContent,
        provisionalTail = snapshot.provisionalTail,
        contentIdentity = contentIdentity,
    )
    val parseRequest = parser.prepare(snapshot, contentIdentity)
    val fallback = parseRequest.fallback
    val parsed = produceState(
        initialValue = StreamingMarkdownParseResult(parseKey, fallback),
        key1 = parseKey,
    ) {
        value = StreamingMarkdownParseResult(
            key = parseKey,
            state = parser.parse(parseRequest),
        )
    }.value
    val visible = streamingMarkdownWhileParsing(
        currentKey = parseKey,
        fallback = fallback,
        parsed = parsed,
    )
    AndroidView(
        factory = { context ->
            IncrementalMarkdownTextView(context)
        },
        modifier = modifier,
        update = { textView ->
            textView.applyStyle(style, color)
            textView.render(visible)
        },
    )
}

@Composable
fun StreamingSafeMarkdownText(
    stableMarkdown: String,
    provisionalMarkdown: String,
    contentIdentity: Any,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Ink,
) {
    val visibleMarkdown = stableMarkdown + provisionalMarkdown
    val parsedStable = produceState(
        initialValue = SafeMarkdownParseResult(
            source = "",
            contentIdentity = contentIdentity,
            annotated = AnnotatedString(""),
        ),
        key1 = stableMarkdown,
        key2 = contentIdentity,
    ) {
        value = SafeMarkdownParseResult(
            source = stableMarkdown,
            contentIdentity = contentIdentity,
            annotated = parseSafeMarkdown(stableMarkdown),
        )
    }.value
    val parsedVisible = produceState(
        initialValue = SafeMarkdownParseResult(
            source = "",
            contentIdentity = contentIdentity,
            annotated = AnnotatedString(""),
        ),
        key1 = visibleMarkdown,
        key2 = contentIdentity,
    ) {
        value = SafeMarkdownParseResult(
            source = visibleMarkdown,
            contentIdentity = contentIdentity,
            annotated = parseSafeMarkdown(visibleMarkdown),
        )
    }.value
    Text(
        text = streamingMarkdownWhileParsing(
            stableText = stableMarkdown,
            provisionalText = provisionalMarkdown,
            contentIdentity = contentIdentity,
            parsedStable = parsedStable,
            parsedVisible = parsedVisible,
        ),
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

internal suspend fun parseSafeMarkdown(
    markdown: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): AnnotatedString = withContext(dispatcher) {
    markdown.toSafeAnnotatedString()
}

internal class IncrementalSafeMarkdownParser(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val chunkParser: (String) -> AnnotatedString = { it.toSafeAnnotatedString() },
) {
    internal data class PublishedState(
        val key: StreamingMarkdownParseKey?,
        val stableCache: ChunkParseCache,
        val provisionalCache: ChunkParseCache,
    )

    internal data class ParseRequest(
        val key: StreamingMarkdownParseKey,
        val fallback: StreamingMarkdownRenderState,
        internal val capturedState: PublishedState,
    )

    private val publishedState = AtomicReference(
        PublishedState(
            key = null,
            stableCache = newChunkParseCache(),
            provisionalCache = newChunkParseCache(),
        ),
    )
    private val parsedCharacterCounter = AtomicLong()
    private val cacheMaintenanceWorkCounter = AtomicLong()

    val parsedCharacterCount: Long
        get() = parsedCharacterCounter.get()

    val cacheMaintenanceWorkCount: Long
        get() = cacheMaintenanceWorkCounter.get()

    fun prepare(
        snapshot: TutorMarkdownSnapshot,
        contentIdentity: Any,
    ): ParseRequest {
        val key = StreamingMarkdownParseKey(
            stableContent = snapshot.stableContent,
            provisionalContent = snapshot.provisionalContent,
            provisionalTail = snapshot.provisionalTail,
            contentIdentity = contentIdentity,
        )
        while (true) {
            val current = publishedState.get()
            val selected = when {
                current.key == key -> current
                current.key?.contentIdentity == contentIdentity -> current.copy(key = key)
                else -> PublishedState(
                    key = key,
                    stableCache = newChunkParseCache(),
                    provisionalCache = newChunkParseCache(),
                )
            }
            if (selected === current || publishedState.compareAndSet(current, selected)) {
                return ParseRequest(
                    key = key,
                    fallback = selected.visibleState(),
                    capturedState = selected,
                )
            }
        }
    }

    suspend fun parse(
        snapshot: TutorMarkdownSnapshot,
        contentIdentity: Any,
    ): StreamingMarkdownRenderState = parse(prepare(snapshot, contentIdentity))

    suspend fun parse(request: ParseRequest): StreamingMarkdownRenderState =
        withContext(dispatcher) {
            val captured = request.capturedState
            val stable = captured.stableCache.parse(
                request.key.stableContent,
                ::parseChunk,
            )
            val provisional = captured.provisionalCache.parse(
                request.key.provisionalContent,
                ::parseChunk,
            )
            val result = StreamingMarkdownRenderState(
                stable = stable.value,
                provisional = provisional.value,
                provisionalTail = parseChunk(request.key.provisionalTail),
                contentIdentity = request.key.contentIdentity,
            )
            val published = publishedState.compareAndSet(
                captured,
                captured.copy(
                    stableCache = stable.cache,
                    provisionalCache = provisional.cache,
                ),
            )
            if (published) {
                stable.publishCacheEntry()
                provisional.publishCacheEntry()
            }
            result
        }

    fun visibleWhileParsing(
        snapshot: TutorMarkdownSnapshot,
        contentIdentity: Any,
    ): StreamingMarkdownRenderState = prepare(snapshot, contentIdentity).fallback

    private fun PublishedState.visibleState(): StreamingMarkdownRenderState {
        val visibleKey = checkNotNull(key)
        return StreamingMarkdownRenderState(
            stable = stableCache.closestParsedPrefix(visibleKey.stableContent),
            provisional = provisionalCache.closestParsedPrefix(visibleKey.provisionalContent),
            provisionalTail = AnnotatedString(""),
            contentIdentity = visibleKey.contentIdentity,
        )
    }

    private fun parseChunk(markdown: String): AnnotatedString {
        parsedCharacterCounter.addAndGet(markdown.length.toLong())
        return chunkParser(markdown)
    }

    private fun newChunkParseCache(): ChunkParseCache =
        ChunkParseCache(cacheMaintenanceWorkCounter::addAndGet)
}

internal class ChunkParseResult(
    val cache: ChunkParseCache,
    val value: ParsedMarkdownChunkChain,
    private val publishCacheEntryAction: () -> Unit = {},
) {
    fun publishCacheEntry() = publishCacheEntryAction()
}

internal class ChunkParseCache private constructor(
    private val registry: ChunkParseRegistry,
    private val latestChain: TutorMarkdownChunkChain,
    private val latestValue: ParsedMarkdownChunkChain,
) {
    constructor(recordMaintenanceWork: (Long) -> Unit) : this(
        registry = ChunkParseRegistry(recordMaintenanceWork),
        latestChain = TutorMarkdownChunkChain.EMPTY,
        latestValue = ParsedMarkdownChunkChain.EMPTY,
    )

    fun parse(
        chain: TutorMarkdownChunkChain,
        parseChunk: (String) -> AnnotatedString,
    ): ChunkParseResult {
        registry[chain]?.let { cached ->
            return ChunkParseResult(
                cache = if (chain === latestChain) {
                    this
                } else {
                    ChunkParseCache(registry, chain, cached)
                },
                value = cached,
            )
        }
        val appended = chain.appendedChunksSince(latestChain)
        var parsed = if (appended != null) latestValue else ParsedMarkdownChunkChain.EMPTY
        val chunks = appended ?: chain.appendedChunksSince(TutorMarkdownChunkChain.EMPTY)
            ?: listOf(chain.materialize())
        chunks.forEach { chunk ->
            parsed = parsed.append(parseChunk(chunk))
        }
        return ChunkParseResult(
            cache = ChunkParseCache(
                registry = registry,
                latestChain = chain,
                latestValue = parsed,
            ),
            value = parsed,
            publishCacheEntryAction = {
                registry.putIfAbsent(chain, parsed)
            },
        )
    }

    fun closestParsedPrefix(chain: TutorMarkdownChunkChain): ParsedMarkdownChunkChain {
        registry[chain]?.let { return it }
        return if (chain.appendedChunksSince(latestChain) != null) {
            latestValue
        } else {
            ParsedMarkdownChunkChain.EMPTY
        }
    }
}

private class ChunkParseRegistry(
    private val recordMaintenanceWork: (Long) -> Unit,
) {
    private val values =
        ConcurrentHashMap<ReferentialIdentityKey<TutorMarkdownChunkChain>, ParsedMarkdownChunkChain>()
            .apply {
                put(
                    ReferentialIdentityKey(TutorMarkdownChunkChain.EMPTY),
                    ParsedMarkdownChunkChain.EMPTY,
                )
            }

    operator fun get(chain: TutorMarkdownChunkChain): ParsedMarkdownChunkChain? =
        values[ReferentialIdentityKey(chain)]

    fun putIfAbsent(
        chain: TutorMarkdownChunkChain,
        parsed: ParsedMarkdownChunkChain,
    ): ParsedMarkdownChunkChain {
        recordMaintenanceWork(1)
        return values.putIfAbsent(ReferentialIdentityKey(chain), parsed) ?: parsed
    }
}

private class ReferentialIdentityKey<T : Any>(
    private val value: T,
) {
    override fun equals(other: Any?): Boolean =
        other is ReferentialIdentityKey<*> && value === other.value

    override fun hashCode(): Int = System.identityHashCode(value)
}

internal class ParsedMarkdownChunkChain private constructor(
    private val previous: ParsedMarkdownChunkChain?,
    private val chunk: AnnotatedString,
    val length: Int,
) {
    fun append(value: AnnotatedString): ParsedMarkdownChunkChain =
        if (value.isEmpty()) this else ParsedMarkdownChunkChain(
            previous = this,
            chunk = value,
            length = length + value.length,
        )

    fun appendedChunksSince(ancestor: ParsedMarkdownChunkChain): List<AnnotatedString>? {
        if (ancestor === this) return emptyList()
        if (ancestor.length >= length) return null

        val reversed = mutableListOf<AnnotatedString>()
        var current: ParsedMarkdownChunkChain? = this
        while (current != null && current !== ancestor) {
            if (current.length <= ancestor.length) return null
            reversed += current.chunk
            current = current.previous
        }
        if (current !== ancestor) return null
        reversed.reverse()
        return reversed
    }

    fun commonAncestor(other: ParsedMarkdownChunkChain): ParsedMarkdownChunkChain {
        var left = this
        var right = other
        while (left !== right) {
            when {
                left.length > right.length -> left = left.previous ?: EMPTY
                right.length > left.length -> right = right.previous ?: EMPTY
                else -> {
                    left = left.previous ?: EMPTY
                    right = right.previous ?: EMPTY
                }
            }
        }
        return left
    }

    fun appendTo(builder: AnnotatedString.Builder) {
        appendedChunksSince(EMPTY)?.forEach(builder::append)
    }

    companion object {
        val EMPTY = ParsedMarkdownChunkChain(
            previous = null,
            chunk = AnnotatedString(""),
            length = 0,
        )
    }
}

internal data class StreamingMarkdownRenderState(
    val stable: ParsedMarkdownChunkChain,
    val provisional: ParsedMarkdownChunkChain,
    val provisionalTail: AnnotatedString,
    val contentIdentity: Any,
) {
    fun materialize(): AnnotatedString = buildAnnotatedString {
        stable.appendTo(this)
        provisional.appendTo(this)
        append(provisionalTail)
    }

    companion object {
        fun empty(contentIdentity: Any) = StreamingMarkdownRenderState(
            stable = ParsedMarkdownChunkChain.EMPTY,
            provisional = ParsedMarkdownChunkChain.EMPTY,
            provisionalTail = AnnotatedString(""),
            contentIdentity = contentIdentity,
        )
    }
}

internal data class StreamingMarkdownParseKey(
    val stableContent: TutorMarkdownChunkChain,
    val provisionalContent: TutorMarkdownChunkChain,
    val provisionalTail: String,
    val contentIdentity: Any,
)

internal data class StreamingMarkdownParseResult(
    val key: StreamingMarkdownParseKey,
    val state: StreamingMarkdownRenderState,
)

internal fun streamingMarkdownWhileParsing(
    currentKey: StreamingMarkdownParseKey,
    fallback: StreamingMarkdownRenderState,
    parsed: StreamingMarkdownParseResult,
): StreamingMarkdownRenderState =
    if (parsed.key == currentKey) parsed.state else fallback

internal data class StreamingTextPatch(
    val deleteSuffixCharacterCount: Int,
    val appendedChunks: List<AnnotatedString>,
)

internal class IncrementalTextPatchPlanner {
    private var contentIdentity: Any? = null
    private var stable = ParsedMarkdownChunkChain.EMPTY
    private var provisional = ParsedMarkdownChunkChain.EMPTY
    private var provisionalTailLength = 0
    private var renderedLength = 0

    var appliedCharacterCount: Long = 0
        private set

    fun plan(next: StreamingMarkdownRenderState): StreamingTextPatch {
        var deleteSuffix = provisionalTailLength
        val appended = mutableListOf<AnnotatedString>()
        if (contentIdentity != next.contentIdentity) {
            deleteSuffix = renderedLength
            stable = ParsedMarkdownChunkChain.EMPTY
            provisional = ParsedMarkdownChunkChain.EMPTY
            provisionalTailLength = 0
        }

        if (stable === next.stable) {
            val commonProvisional = provisional.commonAncestor(next.provisional)
            deleteSuffix += provisional.length - commonProvisional.length
            appended += next.provisional.appendedChunksSince(commonProvisional).orEmpty()
        } else {
            val commonStable = stable.commonAncestor(next.stable)
            deleteSuffix += provisional.length + stable.length - commonStable.length
            appended += next.stable.appendedChunksSince(commonStable).orEmpty()
            appended += next.provisional.appendedChunksSince(ParsedMarkdownChunkChain.EMPTY).orEmpty()
        }
        if (next.provisionalTail.isNotEmpty()) appended += next.provisionalTail

        val appendedLength = appended.sumOf(AnnotatedString::length)
        require(deleteSuffix <= renderedLength) {
            "Streaming text patch cannot delete beyond the rendered prefix"
        }
        renderedLength = renderedLength - deleteSuffix + appendedLength
        appliedCharacterCount += deleteSuffix + appendedLength
        contentIdentity = next.contentIdentity
        stable = next.stable
        provisional = next.provisional
        provisionalTailLength = next.provisionalTail.length
        return StreamingTextPatch(
            deleteSuffixCharacterCount = deleteSuffix,
            appendedChunks = appended,
        )
    }
}

private class IncrementalMarkdownTextView(
    context: Context,
) : TextView(context) {
    private val planner = IncrementalTextPatchPlanner()
    private val buffer = SpannableStringBuilder()

    init {
        setSpannableFactory(
            object : Spannable.Factory() {
                override fun newSpannable(source: CharSequence): Spannable =
                    if (source === buffer) buffer else SpannableStringBuilder(source)
            },
        )
        setText(buffer, BufferType.SPANNABLE)
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        background = null
        isFocusable = false
        isClickable = false
        isLongClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun render(state: StreamingMarkdownRenderState) {
        val patch = planner.plan(state)
        if (patch.deleteSuffixCharacterCount > 0) {
            buffer.delete(
                buffer.length - patch.deleteSuffixCharacterCount,
                buffer.length,
            )
        }
        patch.appendedChunks.forEach(buffer::appendAnnotated)
    }
}

private fun TextView.applyStyle(style: TextStyle, color: Color) {
    val resolvedColor = if (color != Color.Unspecified) color else style.color
    if (resolvedColor != Color.Unspecified) setTextColor(resolvedColor.toArgb())
    if (style.fontSize != TextUnit.Unspecified) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
    }
    val baseTypeface = when (style.fontFamily) {
        FontFamily.Monospace -> Typeface.MONOSPACE
        FontFamily.Serif -> Typeface.SERIF
        FontFamily.SansSerif -> Typeface.SANS_SERIF
        else -> Typeface.DEFAULT
    }
    val typefaceStyle = when {
        style.fontWeight?.weight?.let { it >= FontWeight.SemiBold.weight } == true &&
            style.fontStyle == FontStyle.Italic -> Typeface.BOLD_ITALIC
        style.fontWeight?.weight?.let { it >= FontWeight.SemiBold.weight } == true -> Typeface.BOLD
        style.fontStyle == FontStyle.Italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    typeface = Typeface.create(baseTypeface, typefaceStyle)
    letterSpacing = if (
        style.letterSpacing != TextUnit.Unspecified &&
        style.fontSize != TextUnit.Unspecified &&
        style.fontSize.value > 0f
    ) {
        style.letterSpacing.value / style.fontSize.value
    } else {
        0f
    }
    if (style.lineHeight != TextUnit.Unspecified) {
        val targetLineHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            style.lineHeight.value,
            resources.displayMetrics,
        )
        val fontMetrics = paint.fontMetricsInt
        setLineSpacing(
            targetLineHeight - (fontMetrics.descent - fontMetrics.ascent),
            1f,
        )
    } else {
        setLineSpacing(0f, 1f)
    }
    textAlignment = when (style.textAlign) {
        TextAlign.Center -> View.TEXT_ALIGNMENT_CENTER
        TextAlign.End,
        TextAlign.Right,
        -> View.TEXT_ALIGNMENT_TEXT_END
        else -> View.TEXT_ALIGNMENT_TEXT_START
    }
}

private fun SpannableStringBuilder.appendAnnotated(value: AnnotatedString) {
    val offset = length
    append(value.text)
    value.spanStyles.forEach { range ->
        val start = offset + range.start
        val end = offset + range.end
        if (start >= end) return@forEach
        val style = range.item
        val typefaceStyle = when {
            style.fontWeight?.weight?.let { it >= FontWeight.SemiBold.weight } == true &&
                style.fontStyle == FontStyle.Italic -> Typeface.BOLD_ITALIC
            style.fontWeight?.weight?.let { it >= FontWeight.SemiBold.weight } == true ->
                Typeface.BOLD
            style.fontStyle == FontStyle.Italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        if (typefaceStyle != Typeface.NORMAL) {
            setSpan(
                StyleSpan(typefaceStyle),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        when (style.fontFamily) {
            FontFamily.Monospace -> setSpan(
                TypefaceSpan("monospace"),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            FontFamily.Serif -> setSpan(
                TypefaceSpan("serif"),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            else -> Unit
        }
        if (style.color != Color.Unspecified) {
            setSpan(
                ForegroundColorSpan(style.color.toArgb()),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (style.background != Color.Unspecified) {
            setSpan(
                BackgroundColorSpan(style.background.toArgb()),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
}

internal data class SafeMarkdownParseResult(
    val source: String,
    val contentIdentity: Any,
    val annotated: AnnotatedString,
)

internal fun safeMarkdownWhileParsing(
    displayText: String,
    contentIdentity: Any,
    parsed: SafeMarkdownParseResult,
): AnnotatedString = if (
    contentIdentity == parsed.contentIdentity &&
    displayText.startsWith(parsed.source)
) {
    parsed.annotated
} else {
    AnnotatedString("")
}

internal fun streamingMarkdownWhileParsing(
    stableText: String,
    provisionalText: String,
    contentIdentity: Any,
    parsedStable: SafeMarkdownParseResult,
    parsedVisible: SafeMarkdownParseResult,
): AnnotatedString {
    val visibleText = stableText + provisionalText
    return when {
        parsedVisible.matchesPrefixOf(visibleText, contentIdentity) -> parsedVisible.annotated
        parsedStable.matchesPrefixOf(stableText, contentIdentity) -> parsedStable.annotated
        else -> AnnotatedString("")
    }
}

private fun SafeMarkdownParseResult.matchesPrefixOf(
    currentText: String,
    currentIdentity: Any,
): Boolean = contentIdentity == currentIdentity &&
    (source.isNotEmpty() || currentText.isEmpty()) &&
    currentText.startsWith(source)

private fun String.toSafeAnnotatedString(): AnnotatedString = buildAnnotatedString {
    SafeInlineMarkdown.parse(this@toSafeAnnotatedString).forEach { token ->
        when (token) {
            is InlineToken.Text -> append(token.value)
            is InlineToken.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
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
                SpanStyle(fontFamily = FontFamily.Serif),
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
