package com.tingyun.smartmistakebook.core.model

import kotlin.math.min
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A deterministic, local-first teaching document. Presentation layers must render this contract
 * with the structured renderer instead of interpreting arbitrary HTML or loading remote media.
 */
@Serializable
data class QuestionDocument(
    val id: String,
    val title: String? = null,
    val blocks: List<ContentBlock>,
)

@Serializable
sealed interface ContentBlock {
    val id: String

    @Serializable
    @SerialName("paragraph")
    data class Paragraph(
        override val id: String,
        val markdown: String,
    ) : ContentBlock

    /** LaTeX-shaped text. It is never passed to a WebView or executable TeX engine. */
    @Serializable
    @SerialName("formula")
    data class Formula(
        override val id: String,
        val latex: String,
        val alternativeText: String,
        val display: Boolean = true,
    ) : ContentBlock

    @Serializable
    @SerialName("choice_group")
    data class ChoiceGroup(
        override val id: String,
        val promptMarkdown: String,
        val choices: List<StructuredChoice>,
        val selectedChoiceId: String? = null,
        val enabled: Boolean = true,
    ) : ContentBlock

    @Serializable
    @SerialName("figure")
    data class Figure(
        override val id: String,
        val title: String? = null,
        val alternativeText: String,
        val schema: FigureSchema,
    ) : ContentBlock

    /**
     * Decoder boundary for a future protocol version. The sanitizer always converts this block to
     * a literal paragraph, so an unknown type can never reach a renderer as executable content.
     */
    @Serializable
    @SerialName("unknown")
    data class Unknown(
        override val id: String,
        val type: String,
        val fallbackText: String = "",
    ) : ContentBlock
}

@Serializable
data class StructuredChoice(
    val id: String,
    val markdown: String,
    val accessibilityLabel: String? = null,
    val enabled: Boolean = true,
)

@Serializable
sealed interface FigureSchema {
    @Serializable
    @SerialName("cartesian")
    data class Cartesian(
        val xAxis: FigureAxis,
        val yAxis: FigureAxis,
        val polylines: List<FigurePolyline> = emptyList(),
        val points: List<FigurePoint> = emptyList(),
        val labels: List<FigureLabel> = emptyList(),
    ) : FigureSchema

    /** A deterministic symbol/value table, useful for chemistry and short derivations. */
    @Serializable
    @SerialName("symbol_table")
    data class SymbolTable(
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : FigureSchema

    /** Decoder boundary for a figure schema introduced by a newer protocol version. */
    @Serializable
    @SerialName("unknown")
    data class Unknown(
        val type: String,
        val fallbackText: String = "",
    ) : FigureSchema
}

@Serializable
data class FigureAxis(
    val minimum: Double,
    val maximum: Double,
    val label: String = "",
    val tickCount: Int = 5,
)

@Serializable
data class FigureCoordinate(
    val x: Double,
    val y: Double,
)

@Serializable
data class FigurePolyline(
    val id: String,
    val points: List<FigureCoordinate>,
    val label: String? = null,
    val style: FigureSeriesStyle = FigureSeriesStyle.PRIMARY,
    /**
     * When true the polyline is drawn as a smooth cubic through [points].
     * [smoothness] in 0..1 trades between near-straight segments (low) and a
     * pronounced curve (high). Both default to the legacy straight-segment
     * behaviour so old payloads decode unchanged.
     */
    val curved: Boolean = false,
    val smoothness: Float = 0.2f,
)

@Serializable
data class FigurePoint(
    val coordinate: FigureCoordinate,
    val label: String? = null,
    val style: FigureSeriesStyle = FigureSeriesStyle.PRIMARY,
)

@Serializable
data class FigureLabel(
    val coordinate: FigureCoordinate,
    val text: String,
)

@Serializable
enum class FigureSeriesStyle {
    PRIMARY,
    SECONDARY,
    EMPHASIS,
}

/** Hard resource ceilings shared by protocol validation and UI rendering. */
object StructuredContentLimits {
    const val MAX_BLOCKS = 64
    const val MAX_TITLE_CHARS = 256
    const val MAX_TEXT_CHARS = 8_000
    const val MAX_DOCUMENT_TEXT_CHARS = 32_000
    const val MAX_FORMULA_CHARS = 2_000
    const val MAX_ACCESSIBILITY_CHARS = 512
    const val MAX_CHOICES = 12
    const val MAX_DOCUMENT_CHOICES = 48
    const val MAX_POLYLINES = 16
    const val MAX_POINTS_PER_POLYLINE = 128
    const val MAX_FIGURE_POINTS = 512
    const val MAX_FIGURE_LABELS = 64
    const val MAX_TABLE_COLUMNS = 8
    const val MAX_TABLE_ROWS = 32
    const val MAX_CELL_CHARS = 256
    const val MAX_DOCUMENT_TABLE_CELLS = 256
    const val MAX_DOCUMENT_FIGURE_PRIMITIVES = 1_024
}

enum class StructuredContentIssueCode {
    BLANK_DOCUMENT_ID,
    BLANK_OR_DUPLICATE_BLOCK_ID,
    BLOCK_LIMIT_EXCEEDED,
    TEXT_LIMIT_EXCEEDED,
    UNSUPPORTED_MARKUP_AS_TEXT,
    UNKNOWN_BLOCK_AS_TEXT,
    INVALID_FIGURE_AS_TEXT,
    FIGURE_LIMIT_EXCEEDED,
    CHOICE_LIMIT_EXCEEDED,
    BLANK_OR_DUPLICATE_CHOICE_ID,
    UNSUPPORTED_FORMULA_COMMAND_AS_TEXT,
    DOCUMENT_TEXT_BUDGET_EXCEEDED,
    DOCUMENT_CHOICE_BUDGET_EXCEEDED,
    DOCUMENT_TABLE_CELL_BUDGET_EXCEEDED,
    DOCUMENT_FIGURE_PRIMITIVE_BUDGET_EXCEEDED,
    UNKNOWN_FIGURE_SCHEMA_AS_TEXT,
    EMPTY_FIGURE_DESCRIPTION_REPLACED,
    EMPTY_CHOICE_CONTENT_REPLACED,
    EMPTY_CHOICE_GROUP_RENDERED_AS_STATUS,
}

data class StructuredContentIssue(
    val code: StructuredContentIssueCode,
    val blockId: String? = null,
)

data class SanitizedQuestionDocument(
    val document: QuestionDocument,
    val issues: List<StructuredContentIssue>,
)

/**
 * Non-throwing validation for content arriving from OCR/model adapters. Issues describe the exact
 * fallback that [StructuredContentSanitizer] will apply.
 */
object StructuredContentValidator {
    fun validate(document: QuestionDocument): List<StructuredContentIssue> = buildList {
        if (document.id.take(128).isBlank()) {
            add(StructuredContentIssue(StructuredContentIssueCode.BLANK_DOCUMENT_ID))
        }
        if (document.blocks.size > StructuredContentLimits.MAX_BLOCKS) {
            add(StructuredContentIssue(StructuredContentIssueCode.BLOCK_LIMIT_EXCEEDED))
        }

        val blockIds = mutableSetOf<String>()
        document.blocks.take(StructuredContentLimits.MAX_BLOCKS).forEach { block ->
            val boundedBlockId = stripControlCharacters(block.id.take(128))
            if (boundedBlockId.isBlank() || !blockIds.add(boundedBlockId)) {
                add(
                    StructuredContentIssue(
                        StructuredContentIssueCode.BLANK_OR_DUPLICATE_BLOCK_ID,
                        boundedBlockId,
                    ),
                )
            }
            when (block) {
                is ContentBlock.Paragraph -> validateInline(block.markdown, boundedBlockId)
                is ContentBlock.Formula -> validateFormula(block, boundedBlockId)
                is ContentBlock.ChoiceGroup -> validateChoiceGroup(block, boundedBlockId)
                is ContentBlock.Figure -> validateFigure(block, boundedBlockId)
                is ContentBlock.Unknown -> add(
                    StructuredContentIssue(
                        StructuredContentIssueCode.UNKNOWN_BLOCK_AS_TEXT,
                        boundedBlockId,
                    ),
                )
            }
        }
    }.distinct()

    private fun MutableList<StructuredContentIssue>.validateInline(text: String, blockId: String) {
        if (text.length > StructuredContentLimits.MAX_TEXT_CHARS) {
            add(StructuredContentIssue(StructuredContentIssueCode.TEXT_LIMIT_EXCEEDED, blockId))
        }
        if (SafeInlineMarkdown.requiresPlainTextFallback(text)) {
            add(
                StructuredContentIssue(
                    StructuredContentIssueCode.UNSUPPORTED_MARKUP_AS_TEXT,
                    blockId,
                ),
            )
        }
    }

    private fun MutableList<StructuredContentIssue>.validateFormula(
        block: ContentBlock.Formula,
        blockId: String,
    ) {
        if (
            block.latex.length > StructuredContentLimits.MAX_FORMULA_CHARS ||
            block.alternativeText.length > StructuredContentLimits.MAX_ACCESSIBILITY_CHARS
        ) {
            add(StructuredContentIssue(StructuredContentIssueCode.TEXT_LIMIT_EXCEEDED, blockId))
        }
        if (RestrictedFormulaText.hasUnsupportedCommand(block.latex)) {
            add(
                StructuredContentIssue(
                    StructuredContentIssueCode.UNSUPPORTED_FORMULA_COMMAND_AS_TEXT,
                    blockId,
                ),
            )
        }
    }

    private fun MutableList<StructuredContentIssue>.validateChoiceGroup(
        block: ContentBlock.ChoiceGroup,
        blockId: String,
    ) {
        validateInline(block.promptMarkdown, blockId)
        if (block.choices.isEmpty()) {
            add(
                StructuredContentIssue(
                    StructuredContentIssueCode.EMPTY_CHOICE_GROUP_RENDERED_AS_STATUS,
                    blockId,
                ),
            )
        }
        if (block.choices.size > StructuredContentLimits.MAX_CHOICES) {
            add(StructuredContentIssue(StructuredContentIssueCode.CHOICE_LIMIT_EXCEEDED, blockId))
        }
        val choiceIds = mutableSetOf<String>()
        block.choices.take(StructuredContentLimits.MAX_CHOICES).forEach { choice ->
            val boundedChoiceId = stripControlCharacters(choice.id.take(128))
            if (boundedChoiceId.isBlank() || !choiceIds.add(boundedChoiceId)) {
                add(
                    StructuredContentIssue(
                        StructuredContentIssueCode.BLANK_OR_DUPLICATE_CHOICE_ID,
                        blockId,
                    ),
                )
            }
            validateInline(choice.markdown, blockId)
            if (choice.markdown.take(StructuredContentLimits.MAX_TEXT_CHARS).isBlank()) {
                add(
                    StructuredContentIssue(
                        StructuredContentIssueCode.EMPTY_CHOICE_CONTENT_REPLACED,
                        blockId,
                    ),
                )
            }
            if (
                (choice.accessibilityLabel?.length ?: 0) >
                StructuredContentLimits.MAX_ACCESSIBILITY_CHARS
            ) {
                add(StructuredContentIssue(StructuredContentIssueCode.TEXT_LIMIT_EXCEEDED, blockId))
            }
        }
    }

    private fun MutableList<StructuredContentIssue>.validateFigure(
        block: ContentBlock.Figure,
        blockId: String,
    ) {
        if (block.alternativeText.take(StructuredContentLimits.MAX_ACCESSIBILITY_CHARS).isBlank()) {
            add(
                StructuredContentIssue(
                    StructuredContentIssueCode.EMPTY_FIGURE_DESCRIPTION_REPLACED,
                    blockId,
                ),
            )
        }
        when (val schema = block.schema) {
            is FigureSchema.Cartesian -> {
                val axesAreValid = schema.xAxis.isValid() && schema.yAxis.isValid()
                if (!axesAreValid || !schema.hasOnlyFiniteCoordinatesWithinLimits()) {
                    add(
                        StructuredContentIssue(
                            StructuredContentIssueCode.INVALID_FIGURE_AS_TEXT,
                            blockId,
                        ),
                    )
                }
                val pointCount = schema.polylines
                    .take(StructuredContentLimits.MAX_POLYLINES)
                    .sumOf { it.points.size.toLong() } + schema.points.size
                if (
                    schema.polylines.size > StructuredContentLimits.MAX_POLYLINES ||
                    schema.polylines.take(StructuredContentLimits.MAX_POLYLINES).any {
                        it.points.size > StructuredContentLimits.MAX_POINTS_PER_POLYLINE
                    } ||
                    pointCount > StructuredContentLimits.MAX_FIGURE_POINTS ||
                    schema.labels.size > StructuredContentLimits.MAX_FIGURE_LABELS
                ) {
                    add(
                        StructuredContentIssue(
                            StructuredContentIssueCode.FIGURE_LIMIT_EXCEEDED,
                            blockId,
                        ),
                    )
                }
            }

            is FigureSchema.SymbolTable -> {
                if (schema.headers.isEmpty()) {
                    add(
                        StructuredContentIssue(
                            StructuredContentIssueCode.INVALID_FIGURE_AS_TEXT,
                            blockId,
                        ),
                    )
                }
                if (
                    schema.headers.size > StructuredContentLimits.MAX_TABLE_COLUMNS ||
                    schema.rows.size > StructuredContentLimits.MAX_TABLE_ROWS ||
                    schema.rows.take(StructuredContentLimits.MAX_TABLE_ROWS).any {
                        it.size > StructuredContentLimits.MAX_TABLE_COLUMNS
                    }
                ) {
                    add(
                        StructuredContentIssue(
                            StructuredContentIssueCode.FIGURE_LIMIT_EXCEEDED,
                            blockId,
                        ),
                    )
                }
                val visibleCells = schema.headers
                    .asSequence()
                    .take(StructuredContentLimits.MAX_TABLE_COLUMNS)
                    .plus(
                        schema.rows
                            .asSequence()
                            .take(StructuredContentLimits.MAX_TABLE_ROWS)
                            .flatMap { row ->
                                row.asSequence().take(StructuredContentLimits.MAX_TABLE_COLUMNS)
                            },
                    )
                if (visibleCells.any { it.length > StructuredContentLimits.MAX_CELL_CHARS }) {
                    add(StructuredContentIssue(StructuredContentIssueCode.TEXT_LIMIT_EXCEEDED, blockId))
                }
            }

            is FigureSchema.Unknown -> add(
                StructuredContentIssue(
                    StructuredContentIssueCode.UNKNOWN_FIGURE_SCHEMA_AS_TEXT,
                    blockId,
                ),
            )
        }
    }
}

/** Converts an untrusted candidate into a bounded document that every renderer can handle. */
object StructuredContentSanitizer {
    fun sanitize(document: QuestionDocument): SanitizedQuestionDocument {
        val issues = StructuredContentValidator.validate(document).toMutableList()
        val budget = DocumentSanitizationBudget(issues)
        val usedBlockIds = mutableSetOf<String>()
        val title = document.title
            ?.let { budget.takeText(it, StructuredContentLimits.MAX_TITLE_CHARS) }
            ?.takeIf(String::isNotBlank)
        val blocks = document.blocks
            .take(StructuredContentLimits.MAX_BLOCKS)
            .mapIndexed { index, block ->
                val blockId = uniqueId(block.id, "block-$index", usedBlockIds)
                sanitizeBlock(block, blockId, budget)
            }

        return SanitizedQuestionDocument(
            document = QuestionDocument(
                id = sanitizeIdentifier(document.id, "question-document"),
                title = title,
                blocks = blocks,
            ),
            issues = issues.distinct(),
        )
    }

    private fun sanitizeBlock(
        block: ContentBlock,
        id: String,
        budget: DocumentSanitizationBudget,
    ): ContentBlock = when (block) {
        is ContentBlock.Paragraph -> block.copy(
            id = id,
            markdown = budget.takeText(block.markdown, StructuredContentLimits.MAX_TEXT_CHARS, id),
        )

        is ContentBlock.Formula -> block.copy(
            id = id,
            latex = RestrictedFormulaText.sanitize(
                budget.takeText(block.latex, StructuredContentLimits.MAX_FORMULA_CHARS, id),
            ),
            alternativeText = budget.takeText(
                block.alternativeText,
                StructuredContentLimits.MAX_ACCESSIBILITY_CHARS,
                id,
            ),
        )

        is ContentBlock.ChoiceGroup -> sanitizeChoiceGroup(block, id, budget)
        is ContentBlock.Figure -> sanitizeFigure(block, id, budget)
        is ContentBlock.Unknown -> ContentBlock.Paragraph(
            id = id,
            markdown = SafeInlineMarkdown.literal(
                budget.takeText(
                    unknownFallback(block.type, block.fallbackText, "不支持的内容块"),
                    StructuredContentLimits.MAX_TEXT_CHARS,
                    id,
                ),
            ),
        )
    }

    private fun sanitizeChoiceGroup(
        block: ContentBlock.ChoiceGroup,
        id: String,
        budget: DocumentSanitizationBudget,
    ): ContentBlock.ChoiceGroup {
        val usedChoiceIds = mutableSetOf<String>()
        val prompt = budget.takeText(
            block.promptMarkdown,
            StructuredContentLimits.MAX_TEXT_CHARS,
            id,
        )
        val choiceCount = budget.takeChoices(
            requested = min(block.choices.size, StructuredContentLimits.MAX_CHOICES),
            blockId = id,
        )
        val choices = block.choices
            .take(choiceCount)
            .mapIndexed { index, choice ->
                var markdown = budget.takeText(
                    choice.markdown,
                    StructuredContentLimits.MAX_TEXT_CHARS,
                    id,
                )
                if (markdown.isBlank()) {
                    budget.note(StructuredContentIssueCode.EMPTY_CHOICE_CONTENT_REPLACED, id)
                    markdown = budget.takeText(
                        "未提供选项内容",
                        StructuredContentLimits.MAX_TEXT_CHARS,
                        id,
                    )
                }
                choice.copy(
                    id = uniqueId(choice.id, "$id-choice-$index", usedChoiceIds),
                    markdown = markdown,
                    accessibilityLabel = choice.accessibilityLabel
                        ?.let {
                            budget.takeText(
                                it,
                                StructuredContentLimits.MAX_ACCESSIBILITY_CHARS,
                                id,
                            )
                        }
                        ?.takeIf(String::isNotBlank),
                )
            }
        return block.copy(
            id = id,
            promptMarkdown = prompt,
            choices = choices,
            selectedChoiceId = block.selectedChoiceId?.takeIf { selected ->
                choices.any { it.id == selected }
            },
        )
    }

    private fun sanitizeFigure(
        block: ContentBlock.Figure,
        id: String,
        budget: DocumentSanitizationBudget,
    ): ContentBlock {
        val schemaFallback = (block.schema as? FigureSchema.Unknown)?.fallbackText.orEmpty()
        val rawAlternativeText = block.alternativeText
            .take(StructuredContentLimits.MAX_ACCESSIBILITY_CHARS)
            .takeIf(String::isNotBlank)
            ?: schemaFallback.take(StructuredContentLimits.MAX_ACCESSIBILITY_CHARS)
        var alternativeText = budget.takeText(
            rawAlternativeText,
            StructuredContentLimits.MAX_ACCESSIBILITY_CHARS,
            id,
        )
        if (alternativeText.isBlank()) {
            budget.note(StructuredContentIssueCode.EMPTY_FIGURE_DESCRIPTION_REPLACED, id)
            alternativeText = budget.takeText(
                "未提供图示说明",
                StructuredContentLimits.MAX_ACCESSIBILITY_CHARS,
                id,
            )
        }
        val title = block.title
            ?.let { budget.takeText(it, StructuredContentLimits.MAX_TITLE_CHARS, id) }
            ?.takeIf(String::isNotBlank)
        return when (val schema = block.schema) {
            is FigureSchema.Cartesian -> {
                if (
                    !schema.xAxis.isValid() ||
                    !schema.yAxis.isValid() ||
                    !schema.hasOnlyFiniteCoordinatesWithinLimits()
                ) {
                    ContentBlock.Paragraph(id, SafeInlineMarkdown.literal(alternativeText))
                } else {
                    sanitizeCartesian(schema, budget, id)?.let { safeSchema ->
                        block.copy(
                            id = id,
                            title = title,
                            alternativeText = alternativeText,
                            schema = safeSchema,
                        )
                    } ?: ContentBlock.Paragraph(id, SafeInlineMarkdown.literal(alternativeText))
                }
            }

            is FigureSchema.SymbolTable -> {
                if (schema.headers.isEmpty()) {
                    ContentBlock.Paragraph(id, SafeInlineMarkdown.literal(alternativeText))
                } else {
                    sanitizeSymbolTable(schema, budget, id)?.let { safeSchema ->
                        block.copy(
                            id = id,
                            title = title,
                            alternativeText = alternativeText,
                            schema = safeSchema,
                        )
                    } ?: ContentBlock.Paragraph(id, SafeInlineMarkdown.literal(alternativeText))
                }
            }

            is FigureSchema.Unknown -> ContentBlock.Paragraph(
                id = id,
                markdown = SafeInlineMarkdown.literal(alternativeText),
            )
        }
    }

    private fun sanitizeCartesian(
        schema: FigureSchema.Cartesian,
        budget: DocumentSanitizationBudget,
        blockId: String,
    ): FigureSchema.Cartesian? {
        val xAxis = schema.xAxis.sanitized(budget, blockId)
        val yAxis = schema.yAxis.sanitized(budget, blockId)
        val basePrimitiveCount = 4 + ((xAxis.tickCount + 1) + (yAxis.tickCount + 1)) * 2
        if (!budget.reserveFigurePrimitives(basePrimitiveCount, blockId)) return null

        var remainingPoints = StructuredContentLimits.MAX_FIGURE_POINTS
        val polylines = schema.polylines
            .take(StructuredContentLimits.MAX_POLYLINES)
            .mapIndexedNotNull { index, polyline ->
                val requested = min(
                    min(polyline.points.size, StructuredContentLimits.MAX_POINTS_PER_POLYLINE),
                    remainingPoints,
                )
                val allowed = budget.takeFigurePrimitiveUnits(requested, costPerUnit = 2, blockId)
                val points = polyline.points
                    .take(allowed)
                    .map { it.clampedTo(schema.xAxis, schema.yAxis) }
                remainingPoints -= points.size
                points.takeIf(List<FigureCoordinate>::isNotEmpty)?.let {
                    polyline.copy(
                        id = sanitizeIdentifier(polyline.id, "line-$index"),
                        points = points,
                        label = polyline.label
                            ?.takeIf { budget.reserveFigurePrimitives(1, blockId) }
                            ?.let {
                                budget.takeText(it, StructuredContentLimits.MAX_CELL_CHARS, blockId)
                            }
                            ?.takeIf(String::isNotBlank),
                    )
                }
            }
        val requestedPoints = min(schema.points.size, remainingPoints)
        val allowedPoints = budget.takeFigurePrimitiveUnits(
            requestedPoints,
            costPerUnit = 1,
            blockId = blockId,
        )
        val points = schema.points
            .take(allowedPoints)
            .map { point ->
                point.copy(
                    coordinate = point.coordinate.clampedTo(schema.xAxis, schema.yAxis),
                    label = point.label
                        ?.takeIf { budget.reserveFigurePrimitives(1, blockId) }
                        ?.let {
                            budget.takeText(it, StructuredContentLimits.MAX_CELL_CHARS, blockId)
                        }
                        ?.takeIf(String::isNotBlank),
                )
            }
        return schema.copy(
            xAxis = xAxis,
            yAxis = yAxis,
            polylines = polylines,
            points = points,
            labels = schema.labels
                .take(
                    budget.takeFigurePrimitiveUnits(
                        requested = min(
                            schema.labels.size,
                            StructuredContentLimits.MAX_FIGURE_LABELS,
                        ),
                        costPerUnit = 1,
                        blockId = blockId,
                    ),
                )
                .map { label ->
                    label.copy(
                        coordinate = label.coordinate.clampedTo(schema.xAxis, schema.yAxis),
                        text = budget.takeText(
                            label.text,
                            StructuredContentLimits.MAX_CELL_CHARS,
                            blockId,
                        ),
                    )
                }
                .filter { it.text.isNotBlank() }
        )
    }

    private fun sanitizeSymbolTable(
        schema: FigureSchema.SymbolTable,
        budget: DocumentSanitizationBudget,
        blockId: String,
    ): FigureSchema.SymbolTable? {
        val (columnCount, rowCount) = budget.takeTableShape(
            requestedColumns = min(schema.headers.size, StructuredContentLimits.MAX_TABLE_COLUMNS),
            requestedRows = min(schema.rows.size, StructuredContentLimits.MAX_TABLE_ROWS),
            blockId = blockId,
        )
        if (columnCount == 0) return null
        return FigureSchema.SymbolTable(
            headers = schema.headers.take(columnCount).map {
                budget.takeText(it, StructuredContentLimits.MAX_CELL_CHARS, blockId)
            },
            rows = schema.rows
                .take(rowCount)
                .map { row ->
                    row.take(columnCount).map {
                        budget.takeText(it, StructuredContentLimits.MAX_CELL_CHARS, blockId)
                    }
                },
        )
    }

    private fun uniqueId(raw: String, fallback: String, used: MutableSet<String>): String {
        val base = sanitizeIdentifier(raw, fallback)
        var candidate = base
        var suffix = 2
        while (!used.add(candidate)) {
            candidate = "$base-$suffix"
            suffix += 1
        }
        return candidate
    }

    private fun unknownFallback(type: String, fallbackText: String, prefix: String): String {
        val boundedFallback = fallbackText.take(StructuredContentLimits.MAX_TEXT_CHARS)
        if (boundedFallback.isNotBlank()) return boundedFallback
        val safeType = sanitizeIdentifier(type, "未知类型")
        return "$prefix：$safeType"
    }

    private fun sanitizeIdentifier(value: String, fallback: String): String =
        stripControlCharacters(value.take(128)).trim().ifBlank { fallback }
}

private class DocumentSanitizationBudget(
    private val issues: MutableList<StructuredContentIssue>,
) {
    private var remainingTextChars = StructuredContentLimits.MAX_DOCUMENT_TEXT_CHARS
    private var remainingChoices = StructuredContentLimits.MAX_DOCUMENT_CHOICES
    private var remainingTableCells = StructuredContentLimits.MAX_DOCUMENT_TABLE_CELLS
    private var remainingFigurePrimitives =
        StructuredContentLimits.MAX_DOCUMENT_FIGURE_PRIMITIVES

    fun takeText(value: String, perValueLimit: Int, blockId: String? = null): String {
        val requested = min(value.length, perValueLimit)
        val allowed = min(requested, remainingTextChars)
        if (allowed < requested) note(StructuredContentIssueCode.DOCUMENT_TEXT_BUDGET_EXCEEDED, blockId)
        remainingTextChars -= allowed
        return stripControlCharacters(value.take(allowed))
    }

    fun takeChoices(requested: Int, blockId: String): Int {
        val allowed = min(requested, remainingChoices)
        if (allowed < requested) {
            note(StructuredContentIssueCode.DOCUMENT_CHOICE_BUDGET_EXCEEDED, blockId)
        }
        remainingChoices -= allowed
        return allowed
    }

    fun takeTableShape(
        requestedColumns: Int,
        requestedRows: Int,
        blockId: String,
    ): Pair<Int, Int> {
        val columns = min(requestedColumns, remainingTableCells)
        if (columns < requestedColumns) {
            note(StructuredContentIssueCode.DOCUMENT_TABLE_CELL_BUDGET_EXCEEDED, blockId)
        }
        if (columns == 0) return 0 to 0
        remainingTableCells -= columns
        val rows = min(requestedRows, remainingTableCells / columns)
        if (rows < requestedRows) {
            note(StructuredContentIssueCode.DOCUMENT_TABLE_CELL_BUDGET_EXCEEDED, blockId)
        }
        remainingTableCells -= rows * columns
        return columns to rows
    }

    fun reserveFigurePrimitives(requested: Int, blockId: String): Boolean {
        if (requested > remainingFigurePrimitives) {
            note(StructuredContentIssueCode.DOCUMENT_FIGURE_PRIMITIVE_BUDGET_EXCEEDED, blockId)
            return false
        }
        remainingFigurePrimitives -= requested
        return true
    }

    fun takeFigurePrimitiveUnits(
        requested: Int,
        costPerUnit: Int,
        blockId: String,
    ): Int {
        val allowed = min(requested, remainingFigurePrimitives / costPerUnit)
        if (allowed < requested) {
            note(StructuredContentIssueCode.DOCUMENT_FIGURE_PRIMITIVE_BUDGET_EXCEEDED, blockId)
        }
        remainingFigurePrimitives -= allowed * costPerUnit
        return allowed
    }

    fun note(code: StructuredContentIssueCode, blockId: String? = null) {
        issues += StructuredContentIssue(code, blockId)
    }
}

sealed interface InlineToken {
    data class Text(val value: String) : InlineToken
    data class Strong(val value: String) : InlineToken
    data class Emphasis(val value: String) : InlineToken
    data class Code(val value: String) : InlineToken
    data class Formula(val value: String) : InlineToken
    data object LineBreak : InlineToken
}

/**
 * The complete inline whitelist: **strong**, *emphasis*, `code`, bounded `$formula$`, and line
 * breaks. Links, images, HTML and active URL schemes are intentionally not parsed and therefore
 * remain inert text.
 */
object SafeInlineMarkdown {
    private val html = Regex("<(?:/?[A-Za-z][^>]*|!--[^>]*--)>?")
    private val activeScheme = Regex("(?i)(?:javascript|data)\\s*:")
    private val remoteImage = Regex("!\\[[^]\\r\\n]{0,256}]\\(\\s*https?://", RegexOption.IGNORE_CASE)

    fun requiresPlainTextFallback(value: String): Boolean {
        val bounded = value.take(StructuredContentLimits.MAX_TEXT_CHARS)
        return html.containsMatchIn(bounded) ||
            activeScheme.containsMatchIn(bounded) ||
            remoteImage.containsMatchIn(bounded)
    }

    fun parse(value: String): List<InlineToken> {
        val bounded = stripControlCharacters(value.take(StructuredContentLimits.MAX_TEXT_CHARS))
        if (requiresPlainTextFallback(bounded)) return plainTextTokens(bounded)

        val tokens = mutableListOf<InlineToken>()
        var index = 0
        while (index < bounded.length) {
            when {
                bounded[index] == '\n' -> {
                    tokens += InlineToken.LineBreak
                    index += 1
                }

                bounded.startsWith("**", index) -> {
                    val closing = bounded.indexOf("**", startIndex = index + 2)
                    if (closing > index + 2) {
                        tokens += InlineToken.Strong(bounded.substring(index + 2, closing))
                        index = closing + 2
                    } else {
                        tokens += InlineToken.Text("**")
                        index += 2
                    }
                }

                bounded[index] == '*' -> {
                    val closing = bounded.indexOf('*', startIndex = index + 1)
                    if (closing > index + 1) {
                        tokens += InlineToken.Emphasis(bounded.substring(index + 1, closing))
                        index = closing + 1
                    } else {
                        tokens += InlineToken.Text("*")
                        index += 1
                    }
                }

                bounded[index] == '`' -> {
                    val closing = bounded.indexOf('`', startIndex = index + 1)
                    if (closing > index + 1) {
                        tokens += InlineToken.Code(bounded.substring(index + 1, closing))
                        index = closing + 1
                    } else {
                        tokens += InlineToken.Text("`")
                        index += 1
                    }
                }

                bounded[index] == '$' -> {
                    val closing = bounded.indexOf('$', startIndex = index + 1)
                    if (closing > index + 1) {
                        tokens += InlineToken.Formula(
                            RestrictedFormulaText.sanitize(
                                bounded.substring(index + 1, closing),
                            ),
                        )
                        index = closing + 1
                    } else {
                        tokens += InlineToken.Text("$")
                        index += 1
                    }
                }

                else -> {
                    val next = nextMarkerIndex(bounded, index)
                    tokens += InlineToken.Text(bounded.substring(index, next))
                    index = next
                }
            }
        }
        return tokens
    }

    fun literal(value: String): String {
        val bounded = stripControlCharacters(
            value.take(StructuredContentLimits.MAX_TEXT_CHARS),
        )
            .replace('*', '＊')
            .replace('`', '｀')
            .replace('$', '＄')
            .replace("![", "！[")
        val withoutActiveMarkup = html.replace(bounded) { match ->
            match.value.replace('<', '＜').replace('>', '＞')
        }
        return activeScheme.replace(withoutActiveMarkup) { match ->
            match.value.replace(':', '：')
        }
    }

    private fun plainTextTokens(value: String): List<InlineToken> = buildList {
        value.split('\n').forEachIndexed { index, line ->
            if (index > 0) add(InlineToken.LineBreak)
            if (line.isNotEmpty()) add(InlineToken.Text(line))
        }
    }

    private fun nextMarkerIndex(value: String, start: Int): Int {
        var index = start
        while (
            index < value.length &&
            value[index] != '\n' &&
            value[index] != '*' &&
            value[index] != '`' &&
            value[index] != '$'
        ) {
            index += 1
        }
        return index
    }
}

/** Neutralizes unknown control sequences before showing formula text. */
object RestrictedFormulaText {
    private val command = Regex("\\\\([A-Za-z]+)")
    private val allowedCommands = setOf(
        "alpha", "approx", "beta", "cdot", "cos", "delta", "Delta", "div", "frac",
        "gamma", "ge", "infty", "int", "lambda", "le", "left", "lim", "ln", "log",
        "mu", "ne", "omega", "overline", "pi", "pm", "right", "rightleftharpoons",
        "sigma", "sin", "sqrt", "sum", "tan", "text", "theta", "times", "vec",
    )

    fun hasUnsupportedCommand(value: String): Boolean = command
        .findAll(value.take(StructuredContentLimits.MAX_FORMULA_CHARS))
        .any { it.groupValues[1] !in allowedCommands }

    fun sanitize(value: String): String {
        val bounded = stripControlCharacters(value.take(StructuredContentLimits.MAX_FORMULA_CHARS))
        return command.replace(bounded) { match ->
            val name = match.groupValues[1]
            if (name in allowedCommands) match.value else "⧵$name"
        }
    }
}

private fun FigureAxis.isValid(): Boolean {
    val span = maximum - minimum
    return minimum.isFinite() &&
        maximum.isFinite() &&
        span.isFinite() &&
        span > 0.0
}

private fun FigureAxis.sanitized(
    budget: DocumentSanitizationBudget,
    blockId: String,
): FigureAxis = copy(
    label = budget.takeText(label, StructuredContentLimits.MAX_CELL_CHARS, blockId),
    tickCount = tickCount.coerceIn(2, 10),
)

private fun FigureCoordinate.isFinite(): Boolean = x.isFinite() && y.isFinite()

private fun FigureSchema.Cartesian.hasOnlyFiniteCoordinatesWithinLimits(): Boolean {
    var remainingPoints = StructuredContentLimits.MAX_FIGURE_POINTS
    polylines.take(StructuredContentLimits.MAX_POLYLINES).forEach { line ->
        val linePointLimit = min(StructuredContentLimits.MAX_POINTS_PER_POLYLINE, remainingPoints)
        if (!line.points.take(linePointLimit).all(FigureCoordinate::isFinite)) return false
        remainingPoints -= min(line.points.size, linePointLimit)
    }
    if (!points.take(remainingPoints).all { it.coordinate.isFinite() }) return false
    return labels
        .take(StructuredContentLimits.MAX_FIGURE_LABELS)
        .all { it.coordinate.isFinite() }
}

private fun FigureCoordinate.clampedTo(
    xAxis: FigureAxis,
    yAxis: FigureAxis,
): FigureCoordinate = FigureCoordinate(
    x = x.coerceIn(xAxis.minimum, xAxis.maximum),
    y = y.coerceIn(yAxis.minimum, yAxis.maximum),
)

private fun stripControlCharacters(value: String): String = value
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .filter { character ->
        (character == '\n' || character == '\t' || !character.isISOControl()) &&
            !character.isBidirectionalControl()
    }

private fun Char.isBidirectionalControl(): Boolean =
    this == '\u061C' ||
        this == '\u200E' ||
        this == '\u200F' ||
        this in '\u202A'..'\u202E' ||
        this in '\u2066'..'\u206F'
