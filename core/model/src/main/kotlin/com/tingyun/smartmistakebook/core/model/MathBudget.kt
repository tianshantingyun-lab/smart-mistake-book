package com.tingyun.smartmistakebook.core.model

/**
 * Budget constraints for math formula parsing and rendering.
 * Prevents DoS through deeply nested or extremely large formulas.
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
     * Check if a formula exceeds any budget constraint.
     * Returns null if valid, or an error message if violated.
     */
    fun checkBudget(input: String, tokenCount: Int, astNodeCount: Int, astDepth: Int): String? {
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
        return null
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
}

/**
 * Convert a MathNode to a box layout for rendering.
 * This is a simplified version; a full implementation would use
 * proper TeX-style box placement algorithms.
 */
object MathBoxBuilder {
    private const val CHAR_WIDTH = 8f
    private const val CHAR_HEIGHT = 16f
    private const val LINE_SPACING = 4f
    private const val FRAC_LINE_THICKNESS = 1f
    private const val RADICAL_EXTRA_HEIGHT = 4f

    fun buildBox(node: MathNode): MathBox = when (node) {
        is MathNode.Atom -> buildAtomBox(node)
        is MathNode.Text -> buildTextBox(node)
        is MathNode.Fraction -> buildFractionBox(node)
        is MathNode.Radical -> buildRadicalBox(node)
        is MathNode.Superscript -> buildSuperscriptBox(node)
        is MathNode.Subscript -> buildSubscriptBox(node)
        is MathNode.SuperscriptSubscript -> buildSuperscriptSubscriptBox(node)
        is MathNode.Delimited -> buildDelimitedBox(node)
        is MathNode.Matrix -> buildMatrixBox(node)
        is MathNode.AlignedRows -> buildAlignedBox(node)
        is MathNode.Group -> buildGroupBox(node)
        is MathNode.Row -> buildRowBox(node)
        is MathNode.Operator -> buildOperatorBox(node)
        is MathNode.UnderOver -> buildUnderOverBox(node)
    }

    private fun buildAtomBox(node: MathNode.Atom): MathBox.Leaf {
        val width = node.symbol.length * CHAR_WIDTH
        return MathBox.Leaf(
            width = width,
            height = CHAR_HEIGHT,
            baseline = CHAR_HEIGHT * 0.4f,
            content = node.symbol,
        )
    }

    private fun buildTextBox(node: MathNode.Text): MathBox.Leaf {
        val width = node.value.length * CHAR_WIDTH
        return MathBox.Leaf(
            width = width,
            height = CHAR_HEIGHT,
            baseline = CHAR_HEIGHT * 0.4f,
            content = node.value,
        )
    }

    private fun buildFractionBox(node: MathNode.Fraction): MathBox {
        val numBox = buildBox(node.numerator)
        val denBox = buildBox(node.denominator)
        val width = maxOf(numBox.width, denBox.width) + 2 * CHAR_WIDTH
        val height = numBox.height + denBox.height + LINE_SPACING + FRAC_LINE_THICKNESS
        val baseline = denBox.height + LINE_SPACING / 2 + FRAC_LINE_THICKNESS / 2

        return MathBox.FractionBox(
            numerator = numBox,
            denominator = denBox,
            width = width,
            height = height,
            baseline = baseline,
        )
    }

    private fun buildRadicalBox(node: MathNode.Radical): MathBox {
        val contentBox = buildBox(node.content)
        val indexBox = node.index?.let { buildBox(it) }
        val width = contentBox.width + CHAR_WIDTH * 2 + (indexBox?.width ?: 0f)
        val height = contentBox.height + RADICAL_EXTRA_HEIGHT
        val baseline = contentBox.baseline + RADICAL_EXTRA_HEIGHT

        return MathBox.RadicalBox(
            content = contentBox,
            index = indexBox,
            width = width,
            height = height,
            baseline = baseline,
        )
    }

    private fun buildSuperscriptBox(node: MathNode.Superscript): MathBox {
        val baseBox = buildBox(node.base)
        val expBox = buildBox(node.exponent)
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

    private fun buildSubscriptBox(node: MathNode.Subscript): MathBox {
        val baseBox = buildBox(node.base)
        val indexBox = buildBox(node.index)
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

    private fun buildSuperscriptSubscriptBox(node: MathNode.SuperscriptSubscript): MathBox {
        val baseBox = buildBox(node.base)
        val expBox = node.exponent?.let { buildBox(it) }
        val indexBox = node.index?.let { buildBox(it) }
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

    private fun buildDelimitedBox(node: MathNode.Delimited): MathBox {
        val contentBox = buildBox(node.content)
        val leftWidth = CHAR_WIDTH
        val rightWidth = CHAR_WIDTH
        val width = contentBox.width + leftWidth + rightWidth
        val height = contentBox.height + LINE_SPACING
        val baseline = contentBox.baseline + LINE_SPACING / 2

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

    private fun buildMatrixBox(node: MathNode.Matrix): MathBox {
        val cellBoxes = node.rows.map { row ->
            row.map { buildBox(it) }
        }

        // Compute column widths
        val maxColumns = cellBoxes.maxOfOrNull { it.size } ?: 0
        val columnWidths = (0 until maxColumns).map { col ->
            cellBoxes.maxOfOrNull { row ->
                if (col < row.size) row[col].width else 0f
            } ?: 0f
        }

        val totalWidth = columnWidths.sum() + (maxColumns - 1) * CHAR_WIDTH +
            CHAR_WIDTH * 2 // for delimiters
        val rowHeight = cellBoxes.maxOfOrNull { row ->
            row.maxOfOrNull { it.height } ?: 0f
        } ?: 0f
        val totalHeight = rowHeight * node.rows.size + LINE_SPACING * (node.rows.size - 1)

        return MathBox.MatrixBox(
            rows = cellBoxes,
            leftDelimiter = node.leftDelimiter,
            rightDelimiter = node.rightDelimiter,
            width = totalWidth,
            height = totalHeight,
            baseline = totalHeight / 2,
        )
    }

    private fun buildAlignedBox(node: MathNode.AlignedRows): MathBox {
        val cellBoxes = node.rows.map { row ->
            row.map { buildBox(it) }
        }

        val maxColumns = cellBoxes.maxOfOrNull { it.size } ?: 0
        val columnWidths = (0 until maxColumns).map { col ->
            cellBoxes.maxOfOrNull { row ->
                if (col < row.size) row[col].width else 0f
            } ?: 0f
        }

        val totalWidth = columnWidths.sum() + (maxColumns - 1) * CHAR_WIDTH * 2
        val rowHeight = cellBoxes.maxOfOrNull { row ->
            row.maxOfOrNull { it.height } ?: 0f
        } ?: 0f
        val totalHeight = rowHeight * node.rows.size + LINE_SPACING * (node.rows.size - 1)

        return MathBox.AlignedBox(
            rows = cellBoxes,
            width = totalWidth,
            height = totalHeight,
            baseline = totalHeight / 2,
        )
    }

    private fun buildGroupBox(node: MathNode.Group): MathBox {
        val childBoxes = node.children.map { buildBox(it) }
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

    private fun buildRowBox(node: MathNode.Row): MathBox {
        val childBoxes = node.items.map { buildBox(it) }
        val totalWidth = childBoxes.sumOf { it.width.toDouble() }.toFloat() +
            (childBoxes.size - 1) * CHAR_WIDTH
        val maxHeight = childBoxes.maxOfOrNull { it.height } ?: 0f
        val maxBaseline = childBoxes.maxOfOrNull { it.baseline } ?: 0f

        return MathBox.HBox(
            children = childBoxes,
            width = totalWidth,
            height = maxHeight,
            baseline = maxBaseline,
        )
    }

    private fun buildOperatorBox(node: MathNode.Operator): MathBox.Leaf {
        val width = node.symbol.length * CHAR_WIDTH + CHAR_WIDTH
        return MathBox.Leaf(
            width = width,
            height = CHAR_HEIGHT,
            baseline = CHAR_HEIGHT * 0.4f,
            content = node.symbol,
        )
    }

    private fun buildUnderOverBox(node: MathNode.UnderOver): MathBox {
        val baseBox = buildBox(node.base)
        val underBox = node.under?.let { buildBox(it) }
        val overBox = node.over?.let { buildBox(it) }

        val width = baseBox.width
        val extraHeight = (underBox?.height ?: 0f) + (overBox?.height ?: 0f) + LINE_SPACING * 2
        val height = baseBox.height + extraHeight
        val baseline = baseBox.baseline + (underBox?.height ?: 0f) + LINE_SPACING

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
 * @param input LaTeX-like math formula string
 * @return MathParseResult containing the box layout or error information
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

    // Check AST budget
    val nodeCount = countNodes(node)
    val depth = computeDepth(node)

    val budgetError = MathBudget.checkBudget(input, tokens.size, nodeCount, depth)
    if (budgetError != null) {
        return MathParseResult(
            node = null,
            error = budgetError,
            tokenCount = tokens.size,
            nodeCount = nodeCount,
            depth = depth,
        )
    }

    // Build box layout
    val box = MathBoxBuilder.buildBox(node)

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
 * Returns null if parsing fails.
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
    is MathNode.Group -> 1 + (node.children.maxOfOrNull { computeDepth(it) } ?: 0)
    is MathNode.Row -> 1 + (node.items.maxOfOrNull { computeDepth(it) } ?: 0)
    is MathNode.Operator, is MathNode.Text, is MathNode.Atom -> 1
}
