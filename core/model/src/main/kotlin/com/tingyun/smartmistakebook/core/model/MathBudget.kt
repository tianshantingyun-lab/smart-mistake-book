package com.tingyun.smartmistakebook.core.model

/**
 * Budget constraints for math formula parsing and rendering.
 * Prevents DoS through deeply nested or extremely large formulas.
 *
 * Every constant declared here is enforced by [checkBudget] or
 * [checkLayoutSize]; budget violations degrade to the text fallback
 * (they never throw).
 */
object MathBudget {
    /** Maximum input length in characters. */
    const val MAX_INPUT_LENGTH = 2000

    /** Maximum number of tokens after tokenization. */
    const val MAX_TOKEN_COUNT = 500

    /** Maximum AST depth (nesting levels). */
    const val MAX_AST_DEPTH = 20

    /** Maximum matrix dimensions (rows × columns). */
    const val MAX_MATRIX_ROWS = 50
    const val MAX_MATRIX_COLUMNS = 20

    /** Maximum nesting depth for fractions within fractions. */
    const val MAX_FRACTION_NESTING = 10

    /** Maximum total number of nodes in the AST. */
    const val MAX_AST_NODE_COUNT = 1000

    /** Maximum number of cases in a piecewise function. */
    const val MAX_CASES_COUNT = 20

    /** Maximum number of aligned rows. */
    const val MAX_ALIGNED_ROWS = 50

    /**
     * Maximum laid-out box width, measured in character-cell widths.
     * At the default 16px metrics this is 2048 * 8 = 16384 layout units.
     */
    const val MAX_BOX_WIDTH_CHARS = 512

    /**
     * Maximum laid-out box height, measured in line heights.
     * At the default 16px metrics this is 256 * 16 = 4096 layout units.
     */
    const val MAX_BOX_HEIGHT_LINES = 256

    /**
     * Check if a formula exceeds any budget constraint.
     * Returns null if valid, or an error message if violated.
     *
     * Enforces every structural budget constant: input length, token count,
     * AST node count, AST depth, matrix rows/columns, fraction nesting,
     * cases count and aligned row count.
     */
    fun checkBudget(
        input: String,
        tokenCount: Int,
        astNodeCount: Int,
        astDepth: Int,
        astMetrics: MathAstMetrics = MathAstMetrics.EMPTY,
    ): String? {
        if (input.length > MAX_INPUT_LENGTH) {
            return "公式输入过长: ${input.length} > $MAX_INPUT_LENGTH"
        }
        if (tokenCount > MAX_TOKEN_COUNT) {
            return "公式 token 数过多: $tokenCount > $MAX_TOKEN_COUNT"
        }
        if (astNodeCount > MAX_AST_NODE_COUNT) {
            return "公式结构过复杂: $astNodeCount 节点 > $MAX_AST_NODE_COUNT"
        }
        if (astDepth > MAX_AST_DEPTH) {
            return "公式嵌套过深: $astDepth 层 > $MAX_AST_DEPTH"
        }
        if (astMetrics.maxMatrixRows > MAX_MATRIX_ROWS) {
            return "公式矩阵行数过多: ${astMetrics.maxMatrixRows} 行 > $MAX_MATRIX_ROWS"
        }
        if (astMetrics.maxMatrixColumns > MAX_MATRIX_COLUMNS) {
            return "公式矩阵列数过多: ${astMetrics.maxMatrixColumns} 列 > $MAX_MATRIX_COLUMNS"
        }
        if (astMetrics.maxFractionNesting > MAX_FRACTION_NESTING) {
            return "公式嵌套分数过深: ${astMetrics.maxFractionNesting} 层 > $MAX_FRACTION_NESTING"
        }
        if (astMetrics.maxCasesCount > MAX_CASES_COUNT) {
            return "公式分段项数过多: ${astMetrics.maxCasesCount} 项 > $MAX_CASES_COUNT"
        }
        if (astMetrics.maxAlignedRows > MAX_ALIGNED_ROWS) {
            return "公式对齐行数过多: ${astMetrics.maxAlignedRows} 行 > $MAX_ALIGNED_ROWS"
        }
        return null
    }

    /**
     * Check if a laid-out box exceeds the layout width/height budget.
     * Limits are expressed in character cells so they scale with the
     * font-size-driven [metrics]. Returns null if valid, or an error message.
     */
    fun checkLayoutSize(
        width: Float,
        height: Float,
        metrics: MathMetrics = MathMetrics.DEFAULT,
    ): String? {
        if (!width.isFinite() || !height.isFinite() || width < 0f || height < 0f) {
            return "公式布局尺寸非法: ${width}x${height}"
        }
        val widthChars = width / metrics.charWidth
        if (widthChars > MAX_BOX_WIDTH_CHARS) {
            return "公式布局过宽: ${"%.1f".format(widthChars)} 字符宽 > $MAX_BOX_WIDTH_CHARS"
        }
        val heightLines = height / metrics.charHeight
        if (heightLines > MAX_BOX_HEIGHT_LINES) {
            return "公式布局过高: ${"%.1f".format(heightLines)} 行高 > $MAX_BOX_HEIGHT_LINES"
        }
        return null
    }
}

/**
 * Structural measurements of a [MathNode] tree used for budget enforcement.
 */
data class MathAstMetrics(
    val maxMatrixRows: Int,
    val maxMatrixColumns: Int,
    val maxFractionNesting: Int,
    val maxCasesCount: Int,
    val maxAlignedRows: Int,
) {
    companion object {
        val EMPTY = MathAstMetrics(0, 0, 0, 0, 0)

        /** Measures all budget-relevant structural properties of [node] in one pass. */
        fun measure(node: MathNode): MathAstMetrics {
            val holder = Holder()
            holder.visit(node, 0)
            return MathAstMetrics(
                maxMatrixRows = holder.maxMatrixRows,
                maxMatrixColumns = holder.maxMatrixColumns,
                maxFractionNesting = holder.maxFractionNesting,
                maxCasesCount = holder.maxCasesCount,
                maxAlignedRows = holder.maxAlignedRows,
            )
        }
    }

    private class Holder {
        var maxMatrixRows = 0
        var maxMatrixColumns = 0
        var maxFractionNesting = 0
        var maxCasesCount = 0
        var maxAlignedRows = 0

        fun visit(node: MathNode, fractionDepth: Int) {
            when (node) {
                is MathNode.Fraction -> {
                    val depth = fractionDepth + 1
                    if (depth > maxFractionNesting) maxFractionNesting = depth
                    visit(node.numerator, depth)
                    visit(node.denominator, depth)
                }
                is MathNode.Radical -> {
                    visit(node.content, fractionDepth)
                    node.index?.let { visit(it, fractionDepth) }
                }
                is MathNode.Superscript -> {
                    visit(node.base, fractionDepth)
                    visit(node.exponent, fractionDepth)
                }
                is MathNode.Subscript -> {
                    visit(node.base, fractionDepth)
                    visit(node.index, fractionDepth)
                }
                is MathNode.SuperscriptSubscript -> {
                    visit(node.base, fractionDepth)
                    node.exponent?.let { visit(it, fractionDepth) }
                    node.index?.let { visit(it, fractionDepth) }
                }
                is MathNode.UnderOver -> {
                    visit(node.base, fractionDepth)
                    node.under?.let { visit(it, fractionDepth) }
                    node.over?.let { visit(it, fractionDepth) }
                }
                is MathNode.Delimited -> visit(node.content, fractionDepth)
                is MathNode.Matrix -> {
                    if (node.rows.size > maxMatrixRows) maxMatrixRows = node.rows.size
                    val columns = node.rows.maxOfOrNull { it.size } ?: 0
                    if (columns > maxMatrixColumns) maxMatrixColumns = columns
                    node.rows.forEach { row -> row.forEach { visit(it, fractionDepth) } }
                }
                is MathNode.AlignedRows -> {
                    if (node.rows.size > maxAlignedRows) maxAlignedRows = node.rows.size
                    node.rows.forEach { row -> row.forEach { visit(it, fractionDepth) } }
                }
                is MathNode.Cases -> {
                    if (node.rows.size > maxCasesCount) maxCasesCount = node.rows.size
                    node.rows.forEach { visit(it, fractionDepth) }
                }
                is MathNode.Group -> node.children.forEach { visit(it, fractionDepth) }
                is MathNode.Row -> node.items.forEach { visit(it, fractionDepth) }
                is MathNode.Operator, is MathNode.Text, is MathNode.Atom -> Unit
            }
        }
    }
}

/**
 * Font-size-driven layout metrics for box building.
 *
 * Replaces the old hard-coded 8f/16f constants: every box dimension is a
 * linear function of [charHeight], so boxes scale with the effective font
 * size (including the system font scale, e.g. 200%).
 *
 * [DEFAULT] reproduces the legacy constants exactly (16px font:
 * charWidth 8, charHeight 16, lineSpacing 4, fracLineThickness 1,
 * radicalExtraHeight 4), keeping existing golden dimensions stable.
 */
data class MathMetrics(
    val charWidth: Float,
    val charHeight: Float,
    val lineSpacing: Float,
    val fracLineThickness: Float,
    val radicalExtraHeight: Float,
) {
    companion object {
        /** The historical default: 16px text at 1x density. */
        val DEFAULT = of(16f)

        /**
         * Derives metrics from an effective font size in layout units (px).
         * Non-finite or non-positive sizes fall back to 16px.
         */
        fun of(fontSize: Float): MathMetrics {
            val size = fontSize.takeIf { it.isFinite() && it > 0f } ?: 16f
            return MathMetrics(
                charWidth = size * 0.5f,
                charHeight = size,
                lineSpacing = size * 0.25f,
                fracLineThickness = maxOf(1f, size * 0.0625f),
                radicalExtraHeight = size * 0.25f,
            )
        }
    }
}

/**
 * Result of a math parsing operation with budget validation.
 */
data class MathParseResult(
    val node: MathNode?,
    val error: String?,
    val tokenCount: Int,
    val nodeCount: Int,
    val depth: Int,
) {
    val isSuccess: Boolean get() = error == null && node != null
}

/**
 * Box layout representation for math rendering.
 * Each node is assigned a box with dimensions and baseline position.
 */
sealed interface MathBox {
    /** Width of the laid-out box in layout units. */
    val width: Float

    /** Height of the laid-out box in layout units. */
    val height: Float

    /** Baseline offset from the top of the box, used for text alignment. */
    val baseline: Float

    /** A box with specific dimensions and baseline. */
    data class Leaf(
        override val width: Float,
        override val height: Float,
        override val baseline: Float,
        val content: String,
    ) : MathBox

    /** A horizontal arrangement of boxes. */
    data class HBox(
        val children: List<MathBox>,
        override val width: Float,
        override val height: Float,
        override val baseline: Float,
    ) : MathBox

    /** A vertical arrangement of boxes. */
    data class VBox(
        val children: List<MathBox>,
        override val width: Float,
        override val height: Float,
        override val baseline: Float,
    ) : MathBox

    /** A fraction with numerator and denominator boxes. */
    data class FractionBox(
        val numerator: MathBox,
        val denominator: MathBox,
        override val width: Float,
        override val height: Float,
        override val baseline: Float,
    ) : MathBox

    /** A radical (square root) box. */
    data class RadicalBox(
        val content: MathBox,
        val index: MathBox?,
        override val width: Float,
        override val height: Float,
        override val baseline: Float,
    ) : MathBox

    /** A matrix box with rows and columns. */
    data class MatrixBox(
        val rows: List<List<MathBox>>,
        val leftDelimiter: String,
        val rightDelimiter: String,
        override val width: Float,
        override val height: Float,
        override val baseline: Float,
    ) : MathBox

    /** An aligned equations box. */
    data class AlignedBox(
        val rows: List<List<MathBox>>,
        override val width: Float,
        override val height: Float,
        override val baseline: Float,
    ) : MathBox

    /** A piecewise cases box: one row box per case, drawn behind a left brace. */
    data class CasesBox(
        val rows: List<MathBox>,
        val rowGap: Float,
        override val width: Float,
        override val height: Float,
        override val baseline: Float,
    ) : MathBox
}

/**
 * Convert a MathNode to a box layout for rendering.
 * This is a simplified version; a full implementation would use
 * proper TeX-style box placement algorithms.
 *
 * All dimensions derive from [MathMetrics] (font-size driven); no fixed
 * character sizes are assumed.
 */
object MathBoxBuilder {
    fun buildBox(node: MathNode, metrics: MathMetrics = MathMetrics.DEFAULT): MathBox = when (node) {
        is MathNode.Atom -> buildAtomBox(node, metrics)
        is MathNode.Text -> buildTextBox(node, metrics)
        is MathNode.Fraction -> buildFractionBox(node, metrics)
        is MathNode.Radical -> buildRadicalBox(node, metrics)
        is MathNode.Superscript -> buildSuperscriptBox(node, metrics)
        is MathNode.Subscript -> buildSubscriptBox(node, metrics)
        is MathNode.SuperscriptSubscript -> buildSuperscriptSubscriptBox(node, metrics)
        is MathNode.Delimited -> buildDelimitedBox(node, metrics)
        is MathNode.Matrix -> buildMatrixBox(node, metrics)
        is MathNode.AlignedRows -> buildAlignedBox(node, metrics)
        is MathNode.Cases -> buildCasesBox(node, metrics)
        is MathNode.Group -> buildGroupBox(node, metrics)
        is MathNode.Row -> buildRowBox(node, metrics)
        is MathNode.Operator -> buildOperatorBox(node, metrics)
        is MathNode.UnderOver -> buildUnderOverBox(node, metrics)
    }

    private fun buildAtomBox(node: MathNode.Atom, metrics: MathMetrics): MathBox.Leaf {
        val width = node.symbol.length * metrics.charWidth
        return MathBox.Leaf(
            width = width,
            height = metrics.charHeight,
            baseline = metrics.charHeight * 0.4f,
            content = node.symbol,
        )
    }

    private fun buildTextBox(node: MathNode.Text, metrics: MathMetrics): MathBox.Leaf {
        val width = node.value.length * metrics.charWidth
        return MathBox.Leaf(
            width = width,
            height = metrics.charHeight,
            baseline = metrics.charHeight * 0.4f,
            content = node.value,
        )
    }

    private fun buildFractionBox(node: MathNode.Fraction, metrics: MathMetrics): MathBox {
        val numBox = buildBox(node.numerator, metrics)
        val denBox = buildBox(node.denominator, metrics)
        val width = maxOf(numBox.width, denBox.width) + 2 * metrics.charWidth
        val height = numBox.height + denBox.height + metrics.lineSpacing + metrics.fracLineThickness
        val baseline = denBox.height + metrics.lineSpacing / 2 + metrics.fracLineThickness / 2

        return MathBox.FractionBox(
            numerator = numBox,
            denominator = denBox,
            width = width,
            height = height,
            baseline = baseline,
        )
    }

    private fun buildRadicalBox(node: MathNode.Radical, metrics: MathMetrics): MathBox {
        val contentBox = buildBox(node.content, metrics)
        val indexBox = node.index?.let { buildBox(it, metrics) }
        val width = contentBox.width + metrics.charWidth * 2 + (indexBox?.width ?: 0f)
        val height = contentBox.height + metrics.radicalExtraHeight
        val baseline = contentBox.baseline + metrics.radicalExtraHeight

        return MathBox.RadicalBox(
            content = contentBox,
            index = indexBox,
            width = width,
            height = height,
            baseline = baseline,
        )
    }

    private fun buildSuperscriptBox(node: MathNode.Superscript, metrics: MathMetrics): MathBox {
        val baseBox = buildBox(node.base, metrics)
        val expBox = buildBox(node.exponent, metrics)
        val width = baseBox.width + expBox.width * 0.7f
        val height = baseBox.height + expBox.height * 0.6f
        val baseline = baseBox.baseline

        return MathBox.HBox(
            children = listOf(baseBox, expBox),
            width = width,
            height = height,
            baseline = baseline,
        )
    }

    private fun buildSubscriptBox(node: MathNode.Subscript, metrics: MathMetrics): MathBox {
        val baseBox = buildBox(node.base, metrics)
        val indexBox = buildBox(node.index, metrics)
        val width = baseBox.width + indexBox.width * 0.7f
        val height = baseBox.height + indexBox.height * 0.6f
        val baseline = baseBox.baseline + indexBox.height * 0.6f

        return MathBox.HBox(
            children = listOf(baseBox, indexBox),
            width = width,
            height = height,
            baseline = baseline,
        )
    }

    private fun buildSuperscriptSubscriptBox(node: MathNode.SuperscriptSubscript, metrics: MathMetrics): MathBox {
        val baseBox = buildBox(node.base, metrics)
        val expBox = node.exponent?.let { buildBox(it, metrics) }
        val indexBox = node.index?.let { buildBox(it, metrics) }
        val width = baseBox.width + maxOf(expBox?.width ?: 0f, indexBox?.width ?: 0f) * 0.7f
        val height = baseBox.height + maxOf(expBox?.height ?: 0f, indexBox?.height ?: 0f) * 0.6f
        val baseline = baseBox.baseline + (indexBox?.height ?: 0f) * 0.6f

        return MathBox.HBox(
            children = listOfNotNull(baseBox, expBox, indexBox),
            width = width,
            height = height,
            baseline = baseline,
        )
    }

    private fun buildDelimitedBox(node: MathNode.Delimited, metrics: MathMetrics): MathBox {
        val contentBox = buildBox(node.content, metrics)
        val leftWidth = metrics.charWidth
        val rightWidth = metrics.charWidth
        val width = contentBox.width + leftWidth + rightWidth
        val height = contentBox.height + metrics.lineSpacing
        val baseline = contentBox.baseline + metrics.lineSpacing / 2

        return MathBox.HBox(
            children = listOf(
                MathBox.Leaf(leftWidth, height, baseline, node.leftDelimiter),
                contentBox,
                MathBox.Leaf(rightWidth, height, baseline, node.rightDelimiter),
            ),
            width = width,
            height = height,
            baseline = baseline,
        )
    }

    private fun buildMatrixBox(node: MathNode.Matrix, metrics: MathMetrics): MathBox {
        val cellBoxes = node.rows.map { row ->
            row.map { buildBox(it, metrics) }
        }

        // Compute column widths
        val maxColumns = cellBoxes.maxOfOrNull { it.size } ?: 0
        val columnWidths = (0 until maxColumns).map { col ->
            cellBoxes.maxOfOrNull { row ->
                if (col < row.size) row[col].width else 0f
            } ?: 0f
        }

        val totalWidth = columnWidths.sum() + (maxColumns - 1) * metrics.charWidth +
            metrics.charWidth * 2 // for delimiters
        val rowHeight = cellBoxes.maxOfOrNull { row ->
            row.maxOfOrNull { it.height } ?: 0f
        } ?: 0f
        val totalHeight = rowHeight * node.rows.size + metrics.lineSpacing * (node.rows.size - 1)

        return MathBox.MatrixBox(
            rows = cellBoxes,
            leftDelimiter = node.leftDelimiter,
            rightDelimiter = node.rightDelimiter,
            width = totalWidth,
            height = totalHeight,
            baseline = totalHeight / 2,
        )
    }

    private fun buildAlignedBox(node: MathNode.AlignedRows, metrics: MathMetrics): MathBox {
        val cellBoxes = node.rows.map { row ->
            row.map { buildBox(it, metrics) }
        }

        val maxColumns = cellBoxes.maxOfOrNull { it.size } ?: 0
        val columnWidths = (0 until maxColumns).map { col ->
            cellBoxes.maxOfOrNull { row ->
                if (col < row.size) row[col].width else 0f
            } ?: 0f
        }

        val totalWidth = columnWidths.sum() + (maxColumns - 1) * metrics.charWidth * 2
        val rowHeight = cellBoxes.maxOfOrNull { row ->
            row.maxOfOrNull { it.height } ?: 0f
        } ?: 0f
        val totalHeight = rowHeight * node.rows.size + metrics.lineSpacing * (node.rows.size - 1)

        return MathBox.AlignedBox(
            rows = cellBoxes,
            width = totalWidth,
            height = totalHeight,
            baseline = totalHeight / 2,
        )
    }

    private fun buildCasesBox(node: MathNode.Cases, metrics: MathMetrics): MathBox {
        val rowBoxes = node.rows.map { buildBox(it, metrics) }
        val braceWidth = metrics.charWidth * 1.5f
        val maxRowWidth = rowBoxes.maxOfOrNull { it.width } ?: 0f
        val width = braceWidth + maxRowWidth + metrics.charWidth
        val height = if (rowBoxes.isEmpty()) {
            metrics.charHeight
        } else {
            rowBoxes.sumOf { it.height.toDouble() }.toFloat() +
                metrics.lineSpacing * (rowBoxes.size - 1)
        }

        return MathBox.CasesBox(
            rows = rowBoxes,
            rowGap = metrics.lineSpacing,
            width = width,
            height = height,
            baseline = height / 2f,
        )
    }

    private fun buildGroupBox(node: MathNode.Group, metrics: MathMetrics): MathBox {
        val childBoxes = node.children.map { buildBox(it, metrics) }
        val totalWidth = childBoxes.sumOf { it.width.toDouble() }.toFloat()
        val maxHeight = childBoxes.maxOfOrNull { it.height } ?: 0f
        val maxBaseline = childBoxes.maxOfOrNull { it.baseline } ?: 0f

        return MathBox.HBox(
            children = childBoxes,
            width = totalWidth,
            height = maxHeight,
            baseline = maxBaseline,
        )
    }

    private fun buildRowBox(node: MathNode.Row, metrics: MathMetrics): MathBox {
        val childBoxes = node.items.map { buildBox(it, metrics) }
        val totalWidth = childBoxes.sumOf { it.width.toDouble() }.toFloat() +
            (childBoxes.size - 1) * metrics.charWidth
        val maxHeight = childBoxes.maxOfOrNull { it.height } ?: 0f
        val maxBaseline = childBoxes.maxOfOrNull { it.baseline } ?: 0f

        return MathBox.HBox(
            children = childBoxes,
            width = totalWidth,
            height = maxHeight,
            baseline = maxBaseline,
        )
    }

    private fun buildOperatorBox(node: MathNode.Operator, metrics: MathMetrics): MathBox.Leaf {
        val width = node.symbol.length * metrics.charWidth + metrics.charWidth
        return MathBox.Leaf(
            width = width,
            height = metrics.charHeight,
            baseline = metrics.charHeight * 0.4f,
            content = node.symbol,
        )
    }

    private fun buildUnderOverBox(node: MathNode.UnderOver, metrics: MathMetrics): MathBox {
        val baseBox = buildBox(node.base, metrics)
        val underBox = node.under?.let { buildBox(it, metrics) }
        val overBox = node.over?.let { buildBox(it, metrics) }

        val width = baseBox.width
        val extraHeight = (underBox?.height ?: 0f) + (overBox?.height ?: 0f) + metrics.lineSpacing * 2
        val height = baseBox.height + extraHeight
        val baseline = baseBox.baseline + (underBox?.height ?: 0f) + metrics.lineSpacing

        return MathBox.VBox(
            children = listOfNotNull(overBox, baseBox, underBox),
            width = width,
            height = height,
            baseline = baseline,
        )
    }
}

/**
 * Convenience function that parses a math formula string and converts it
 * to a box layout for rendering. Integrates MathParser, MathBudget, and MathBoxBuilder.
 *
 * Enforces every budget: input length, token count, AST node count/depth,
 * matrix dimensions, fraction nesting, cases/aligned counts, and the final
 * laid-out box width/height. Violations return an error result (the caller
 * degrades to the text fallback); nothing throws.
 *
 * @param input LaTeX-like math formula string
 * @return MathParseResult containing the parsed node or error information
 */
fun parseAndBuildBox(input: String): MathParseResult {
    // Check input budget
    if (input.length > MathBudget.MAX_INPUT_LENGTH) {
        return MathParseResult(
            node = null,
            error = "公式输入过长: ${input.length} > ${MathBudget.MAX_INPUT_LENGTH}",
            tokenCount = 0,
            nodeCount = 0,
            depth = 0,
        )
    }

    // Tokenize
    val tokens = MathTokenizer.tokenize(input)
    if (tokens.size > MathBudget.MAX_TOKEN_COUNT) {
        return MathParseResult(
            node = null,
            error = "公式 token 数过多: ${tokens.size} > ${MathBudget.MAX_TOKEN_COUNT}",
            tokenCount = tokens.size,
            nodeCount = 0,
            depth = 0,
        )
    }

    // Parse
    val node = MathParser.parse(input)

    // Check AST budget (structural constraints included)
    val nodeCount = countNodes(node)
    val depth = computeDepth(node)
    val astMetrics = MathAstMetrics.measure(node)

    val budgetError = MathBudget.checkBudget(input, tokens.size, nodeCount, depth, astMetrics)
    if (budgetError != null) {
        return MathParseResult(
            node = null,
            error = budgetError,
            tokenCount = tokens.size,
            nodeCount = nodeCount,
            depth = depth,
        )
    }

    // Build box layout and check the layout width/height budget
    val box = MathBoxBuilder.buildBox(node)
    val layoutError = MathBudget.checkLayoutSize(box.width, box.height)
    if (layoutError != null) {
        return MathParseResult(
            node = null,
            error = layoutError,
            tokenCount = tokens.size,
            nodeCount = nodeCount,
            depth = depth,
        )
    }

    return MathParseResult(
        node = node,
        error = null,
        tokenCount = tokens.size,
        nodeCount = nodeCount,
        depth = depth,
    )
}

/**
 * Parse a math formula and build its box layout directly.
 * Returns null if parsing fails or any budget is violated.
 */
fun parseAndBuildBoxOrNull(input: String): MathBox? {
    val result = parseAndBuildBox(input)
    return if (result.isSuccess) {
        MathBoxBuilder.buildBox(result.node!!)
    } else {
        null
    }
}

/**
 * Count the number of nodes in a MathNode tree.
 */
private fun countNodes(node: MathNode): Int = when (node) {
    is MathNode.Fraction -> 1 + countNodes(node.numerator) + countNodes(node.denominator)
    is MathNode.Radical -> 1 + countNodes(node.content) + (node.index?.let { countNodes(it) } ?: 0)
    is MathNode.Superscript -> 1 + countNodes(node.base) + countNodes(node.exponent)
    is MathNode.Subscript -> 1 + countNodes(node.base) + countNodes(node.index)
    is MathNode.SuperscriptSubscript -> 1 + countNodes(node.base) +
        (node.exponent?.let { countNodes(it) } ?: 0) +
        (node.index?.let { countNodes(it) } ?: 0)
    is MathNode.UnderOver -> 1 + countNodes(node.base) +
        (node.under?.let { countNodes(it) } ?: 0) +
        (node.over?.let { countNodes(it) } ?: 0)
    is MathNode.Delimited -> 1 + countNodes(node.content)
    is MathNode.Matrix -> 1 + node.rows.sumOf { row -> row.sumOf { countNodes(it) } }
    is MathNode.AlignedRows -> 1 + node.rows.sumOf { row -> row.sumOf { countNodes(it) } }
    is MathNode.Cases -> 1 + node.rows.sumOf { countNodes(it) }
    is MathNode.Group -> 1 + node.children.sumOf { countNodes(it) }
    is MathNode.Row -> 1 + node.items.sumOf { countNodes(it) }
    is MathNode.Operator, is MathNode.Text, is MathNode.Atom -> 1
}

/**
 * Compute the maximum depth of a MathNode tree.
 */
private fun computeDepth(node: MathNode): Int = when (node) {
    is MathNode.Fraction -> 1 + maxOf(computeDepth(node.numerator), computeDepth(node.denominator))
    is MathNode.Radical -> 1 + computeDepth(node.content) + (node.index?.let { computeDepth(it) } ?: 0)
    is MathNode.Superscript -> 1 + maxOf(computeDepth(node.base), computeDepth(node.exponent))
    is MathNode.Subscript -> 1 + maxOf(computeDepth(node.base), computeDepth(node.index))
    is MathNode.SuperscriptSubscript -> 1 + maxOf(
        computeDepth(node.base),
        node.exponent?.let { computeDepth(it) } ?: 0,
        node.index?.let { computeDepth(it) } ?: 0,
    )
    is MathNode.UnderOver -> 1 + maxOf(
        computeDepth(node.base),
        node.under?.let { computeDepth(it) } ?: 0,
        node.over?.let { computeDepth(it) } ?: 0,
    )
    is MathNode.Delimited -> 1 + computeDepth(node.content)
    is MathNode.Matrix -> 1 + (node.rows.maxOfOrNull { row ->
        row.maxOfOrNull { computeDepth(it) } ?: 0
    } ?: 0)
    is MathNode.AlignedRows -> 1 + (node.rows.maxOfOrNull { row ->
        row.maxOfOrNull { computeDepth(it) } ?: 0
    } ?: 0)
    is MathNode.Cases -> 1 + (node.rows.maxOfOrNull { computeDepth(it) } ?: 0)
    is MathNode.Group -> 1 + (node.children.maxOfOrNull { computeDepth(it) } ?: 0)
    is MathNode.Row -> 1 + (node.items.maxOfOrNull { computeDepth(it) } ?: 0)
    is MathNode.Operator, is MathNode.Text, is MathNode.Atom -> 1
}
