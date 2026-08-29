package com.tingyun.smartmistakebook.core.model

/**
 * Math AST nodes for structured formula representation.
 * Supports nested fractions, radicals, scripts, matrices, aligned equations,
 * and piecewise cases blocks.
 */
sealed interface MathNode {
    data class Fraction(
        val numerator: MathNode,
        val denominator: MathNode,
    ) : MathNode

    data class Radical(
        val content: MathNode,
        val index: MathNode? = null,
    ) : MathNode

    data class Superscript(
        val base: MathNode,
        val exponent: MathNode,
    ) : MathNode

    data class Subscript(
        val base: MathNode,
        val index: MathNode,
    ) : MathNode

    data class SuperscriptSubscript(
        val base: MathNode,
        val exponent: MathNode?,
        val index: MathNode?,
    ) : MathNode

    data class UnderOver(
        val base: MathNode,
        val under: MathNode? = null,
        val over: MathNode? = null,
    ) : MathNode

    data class Delimited(
        val content: MathNode,
        val leftDelimiter: String = "(",
        val rightDelimiter: String = ")",
    ) : MathNode

    data class Matrix(
        val rows: List<List<MathNode>>,
        val leftDelimiter: String = "(",
        val rightDelimiter: String = ")",
    ) : MathNode

    data class AlignedRows(
        val rows: List<List<MathNode>>,
    ) : MathNode

    /** A piecewise `cases` block: one node per row, drawn behind a left brace. */
    data class Cases(
        val rows: List<MathNode>,
    ) : MathNode

    data class Operator(
        val name: String,
        val symbol: String,
    ) : MathNode

    data class Text(
        val value: String,
    ) : MathNode

    data class Atom(
        val symbol: String,
    ) : MathNode

    data class Group(
        val children: List<MathNode>,
    ) : MathNode

    data class Row(
        val items: List<MathNode>,
    ) : MathNode
}

/**
 * Token types for math formula lexing.
 */
sealed interface MathToken {
    data class Command(val name: String) : MathToken
    data class Symbol(val value: String) : MathToken
    data class Number(val value: String) : MathToken
    data class Letter(val value: String) : MathToken
    data class LeftBracket(val type: BracketType) : MathToken
    data class RightBracket(val type: BracketType) : MathToken
    object LeftBrace : MathToken
    object RightBrace : MathToken
    object Space : MathToken
    object SuperscriptOp : MathToken
    object SubscriptOp : MathToken
    object Carat : MathToken
    object Underscore : MathToken

    /** A `\\` row separator inside aligned/matrix/cases environments. */
    object RowSeparator : MathToken

    /** A `&` column separator inside aligned/matrix/cases environments. */
    object ColumnSeparator : MathToken
}

enum class BracketType {
    PAREN,      // ( )
    BRACKET,    // [ ]
    BRACE,      // { }
    ANGLE,      // ⟨ ⟩
    FLOOR,      // ⌊ ⌋
    CEIL,       // ⌈ ⌉
    ABS,        // | |
}

/**
 * Math tokenizer - converts LaTeX-like input to tokens.
 */
object MathTokenizer {
    private val COMMAND_PATTERN = Regex("^\\\\([a-zA-Z]+)")
    private val NUMBER_PATTERN = Regex("^\\d+(\\.\\d+)?")
    private val LETTER_PATTERN = Regex("^[a-zA-Z]")

    fun tokenize(input: String): List<MathToken> {
        val tokens = mutableListOf<MathToken>()
        var pos = 0
        val trimmed = input.trim()

        while (pos < trimmed.length) {
            val remaining = trimmed.substring(pos)
            when {
                remaining.startsWith("\\\\") -> {
                    tokens.add(MathToken.RowSeparator)
                    pos += 2
                }
                remaining.startsWith("\\") -> {
                    val match = COMMAND_PATTERN.find(remaining)
                    if (match != null) {
                        tokens.add(MathToken.Command(match.groupValues[1]))
                        pos += match.value.length
                    } else {
                        pos++
                    }
                }
                remaining.startsWith("^") -> {
                    tokens.add(MathToken.SuperscriptOp)
                    pos++
                }
                remaining.startsWith("_") -> {
                    tokens.add(MathToken.SubscriptOp)
                    pos++
                }
                remaining.startsWith("{") -> {
                    tokens.add(MathToken.LeftBrace)
                    pos++
                }
                remaining.startsWith("}") -> {
                    tokens.add(MathToken.RightBrace)
                    pos++
                }
                remaining.startsWith("(") -> {
                    tokens.add(MathToken.LeftBracket(BracketType.PAREN))
                    pos++
                }
                remaining.startsWith(")") -> {
                    tokens.add(MathToken.RightBracket(BracketType.PAREN))
                    pos++
                }
                remaining.startsWith("[") -> {
                    tokens.add(MathToken.LeftBracket(BracketType.BRACKET))
                    pos++
                }
                remaining.startsWith("]") -> {
                    tokens.add(MathToken.RightBracket(BracketType.BRACKET))
                    pos++
                }
                remaining.startsWith(" ") -> {
                    tokens.add(MathToken.Space)
                    pos++
                }
                remaining.startsWith("&") -> {
                    tokens.add(MathToken.ColumnSeparator)
                    pos++
                }
                NUMBER_PATTERN.containsMatchIn(remaining) -> {
                    val match = NUMBER_PATTERN.find(remaining)!!
                    tokens.add(MathToken.Number(match.value))
                    pos += match.value.length
                }
                LETTER_PATTERN.containsMatchIn(remaining) -> {
                    tokens.add(MathToken.Letter(remaining[0].toString()))
                    pos++
                }
                else -> {
                    tokens.add(MathToken.Symbol(remaining[0].toString()))
                    pos++
                }
            }
        }
        return tokens
    }
}

/**
 * Math parser - converts tokens to AST.
 */
object MathParser {
    fun parse(input: String): MathNode {
        val tokens = MathTokenizer.tokenize(input)
        val (node, _) = parseExpression(tokens, 0)
        return node
    }

    private fun parseExpression(tokens: List<MathToken>, pos: Int): Pair<MathNode, Int> {
        if (pos >= tokens.size) return MathNode.Row(emptyList()) to pos

        val items = mutableListOf<MathNode>()
        var current = pos

        while (current < tokens.size) {
            val token = tokens[current]
            when (token) {
                is MathToken.RightBrace,
                is MathToken.RightBracket,
                is MathToken.RowSeparator,
                is MathToken.ColumnSeparator,
                -> break
                is MathToken.LeftBracket -> {
                    // Function-style grouping such as f(x) must keep its
                    // bracket glyphs in the readable output and stay one
                    // unit for a following ^ or _ (so (a+b)^2 lifts the
                    // whole group). RightBracket therefore acts as a group
                    // closer here, not as a top-level terminator; before
                    // this branch a stray ")" ended the whole parse and
                    // silently dropped the rest of the formula.
                    val (node, nextPos) = parseExpression(tokens, current + 1)
                    val open = if (token.type == BracketType.PAREN) "(" else "["
                    val close = if (token.type == BracketType.PAREN) ")" else "]"
                    items.add(
                        MathNode.Group(listOf(MathNode.Atom(open), node, MathNode.Atom(close))),
                    )
                    current = nextPos
                    if (current < tokens.size && tokens[current] is MathToken.RightBracket) {
                        current++
                    }
                }
                is MathToken.Command -> {
                    if (token.name == "end") {
                        // \end{...} terminates the enclosing environment's
                        // rows; leave it unconsumed so parseEnvironmentRows
                        // handles the boundary. Swallowing it here (as a
                        // command with an empty payload) leaked an empty Row
                        // into the last cell of every environment.
                        break
                    }
                    val (node, nextPos) = parseCommand(token.name, tokens, current + 1)
                    items.add(node)
                    current = nextPos
                }
                is MathToken.LeftBrace -> {
                    val (node, nextPos) = parseExpression(tokens, current + 1)
                    items.add(node)
                    current = nextPos
                    if (current < tokens.size && tokens[current] is MathToken.RightBrace) {
                        current++
                    }
                }
                is MathToken.SuperscriptOp -> {
                    val (exp, nextPos) = parseAtom(tokens, current + 1)
                    if (items.isNotEmpty()) {
                        val base = items.removeAt(items.lastIndex)
                        items.add(MathNode.Superscript(base, exp))
                    }
                    current = nextPos
                }
                is MathToken.SubscriptOp -> {
                    val (idx, nextPos) = parseAtom(tokens, current + 1)
                    if (items.isNotEmpty()) {
                        val base = items.removeAt(items.lastIndex)
                        items.add(MathNode.Subscript(base, idx))
                    }
                    current = nextPos
                }
                is MathToken.Number -> {
                    items.add(MathNode.Atom(token.value))
                    current++
                }
                is MathToken.Letter -> {
                    items.add(MathNode.Atom(token.value))
                    current++
                }
                is MathToken.Symbol -> {
                    items.add(MathNode.Atom(token.value))
                    current++
                }
                // Spaces are preserved as atoms so the rendered text and the
                // box layout keep the visual spacing of the source formula.
                is MathToken.Space -> {
                    items.add(MathNode.Atom(" "))
                    current++
                }
                else -> current++
            }
        }

        return if (items.size == 1) items[0] to current
        else MathNode.Row(items) to current
    }

    private fun parseAtom(tokens: List<MathToken>, pos: Int): Pair<MathNode, Int> {
        var start = pos
        // LaTeX allows space between a command and its unbraced argument
        // (\vec F): skip leading spaces so the argument is not read as empty.
        while (start < tokens.size && tokens[start] is MathToken.Space) start++
        if (start >= tokens.size) return MathNode.Row(emptyList()) to start
        val token = tokens[start]
        return when (token) {
            is MathToken.LeftBrace -> {
                val (node, nextPos) = parseExpression(tokens, start + 1)
                val endPos = if (nextPos < tokens.size && tokens[nextPos] is MathToken.RightBrace) {
                    nextPos + 1
                } else {
                    nextPos
                }
                node to endPos
            }
            is MathToken.Number -> MathNode.Atom(token.value) to start + 1
            is MathToken.Letter -> MathNode.Atom(token.value) to start + 1
            is MathToken.Command -> {
                val (node, nextPos) = parseCommand(token.name, tokens, start + 1)
                node to nextPos
            }
            else -> MathNode.Row(emptyList()) to start + 1
        }
    }

    private fun parseCommand(name: String, tokens: List<MathToken>, pos: Int): Pair<MathNode, Int> {
        return when (name) {
            "frac" -> {
                val (num, pos1) = parseAtom(tokens, pos)
                val (den, pos2) = parseAtom(tokens, pos1)
                MathNode.Fraction(num, den) to pos2
            }
            "sqrt" -> {
                val (content, pos1) = parseAtom(tokens, pos)
                MathNode.Radical(content) to pos1
            }
            "vec" -> {
                val (content, pos1) = parseAtom(tokens, pos)
                MathNode.Group(listOf(content, MathNode.Atom("⃗"))) to pos1
            }
            "overline" -> {
                val (content, pos1) = parseAtom(tokens, pos)
                MathNode.Group(listOf(content, MathNode.Atom("̅"))) to pos1
            }
            "begin" -> {
                val (envNameNode, pos1) = parseAtom(tokens, pos)
                parseEnvironment(nodeToString(envNameNode), tokens, pos1)
            }
            "end" -> {
                // Stray \end without a matching \begin: swallow the name and degrade to empty.
                val (_, pos1) = parseAtom(tokens, pos)
                MathNode.Row(emptyList()) to pos1
            }
            "text" -> {
                val (content, pos1) = parseAtom(tokens, pos)
                val text = when (content) {
                    is MathNode.Atom -> content.symbol
                    is MathNode.Row -> content.items.joinToString("") {
                        when (it) {
                            is MathNode.Atom -> it.symbol
                            else -> ""
                        }
                    }
                    else -> ""
                }
                MathNode.Text(text) to pos1
            }
            "sin", "cos", "tan", "log", "ln", "lim" -> {
                MathNode.Operator(name, name) to pos
            }
            "alpha", "beta", "gamma", "delta", "epsilon", "theta",
            "lambda", "mu", "pi", "sigma", "omega", "phi", "psi", "xi",
            "zeta", "eta", "kappa", "rho", "tau", "chi" -> {
                MathNode.Atom(GREEK_MAP[name] ?: name) to pos
            }
            "Alpha", "Beta", "Gamma", "Delta", "Theta", "Lambda",
            "Sigma", "Omega", "Phi", "Psi" -> {
                MathNode.Atom(GREEK_MAP[name] ?: name) to pos
            }
            "infty" -> MathNode.Atom("∞") to pos
            "in" -> MathNode.Atom("∈") to pos
            "notin" -> MathNode.Atom("∉") to pos
            "subset" -> MathNode.Atom("⊂") to pos
            "subseteq" -> MathNode.Atom("⊆") to pos
            "supset" -> MathNode.Atom("⊃") to pos
            "supseteq" -> MathNode.Atom("⊇") to pos
            "cup" -> MathNode.Atom("∪") to pos
            "cap" -> MathNode.Atom("∩") to pos
            "emptyset" -> MathNode.Atom("∅") to pos
            "forall" -> MathNode.Atom("∀") to pos
            "exists" -> MathNode.Atom("∃") to pos
            "nabla" -> MathNode.Atom("∇") to pos
            "partial" -> MathNode.Atom("∂") to pos
            "sum" -> MathNode.Operator("sum", "∑") to pos
            "prod" -> MathNode.Operator("prod", "∏") to pos
            "int" -> MathNode.Operator("int", "∫") to pos
            "angle" -> MathNode.Atom("∠") to pos
            "triangle" -> MathNode.Atom("△") to pos
            "parallel" -> MathNode.Atom("∥") to pos
            "perp" -> MathNode.Atom("⊥") to pos
            "approx" -> MathNode.Atom("≈") to pos
            "sim" -> MathNode.Atom("∼") to pos
            "cong" -> MathNode.Atom("≅") to pos
            "equiv" -> MathNode.Atom("≡") to pos
            "propto" -> MathNode.Atom("∝") to pos
            "times" -> MathNode.Atom("×") to pos
            "cdot" -> MathNode.Atom("·") to pos
            "div" -> MathNode.Atom("÷") to pos
            "pm" -> MathNode.Atom("±") to pos
            "mp" -> MathNode.Atom("∓") to pos
            "le", "leq" -> MathNode.Atom("≤") to pos
            "ge", "geq" -> MathNode.Atom("≥") to pos
            "ne", "neq" -> MathNode.Atom("≠") to pos
            "ll" -> MathNode.Atom("≪") to pos
            "gg" -> MathNode.Atom("≫") to pos
            "to", "rightarrow" -> MathNode.Atom("→") to pos
            "leftarrow" -> MathNode.Atom("←") to pos
            "Rightarrow" -> MathNode.Atom("⇒") to pos
            "Leftarrow" -> MathNode.Atom("⇐") to pos
            "Leftrightarrow" -> MathNode.Atom("⇔") to pos
            "rightleftharpoons" -> MathNode.Atom("⇌") to pos
            else -> MathNode.Atom("\\$name") to pos
        }
    }

    /**
     * Parses the body of a `\begin{name} ... \end{name}` environment into rows of cells,
     * splitting on `&` (columns) and `\\` (rows). Tolerates a missing `\end` by running
     * to the end of the input, and skips tokens that cannot start an item so the loop
     * always terminates.
     */
    private fun parseEnvironmentRows(tokens: List<MathToken>, pos: Int): Pair<List<List<MathNode>>, Int> {
        val rows = mutableListOf<List<MathNode>>()
        var cells = mutableListOf<MathNode>()
        var current = pos

        fun flushRow() {
            if (cells.isNotEmpty()) {
                rows.add(cells)
            }
            cells = mutableListOf()
        }

        while (current < tokens.size) {
            val token = tokens[current]
            if (token is MathToken.Command && token.name == "end") {
                val (_, nameEnd) = parseAtom(tokens, current + 1)
                flushRow()
                return rows to nameEnd
            }
            when (token) {
                is MathToken.ColumnSeparator -> current++
                is MathToken.RowSeparator -> {
                    flushRow()
                    current++
                }
                // Leading spaces between separators are cosmetic; drop them so cells
                // do not start with stray space atoms.
                is MathToken.Space -> current++
                else -> {
                    val (node, nextPos) = parseExpression(tokens, current)
                    if (nextPos <= current) {
                        // Guard: skip tokens that cannot start an item (stray closers).
                        current++
                    } else {
                        cells.add(trimCell(node))
                        current = nextPos
                    }
                }
            }
        }
        flushRow()
        return rows to current
    }

    /** Drops trailing space atoms a cell accumulated before a separator. */
    private fun trimCell(node: MathNode): MathNode {
        if (node !is MathNode.Row) return node
        val trimmed = node.items.dropLastWhile { it is MathNode.Atom && it.symbol == " " }
        return when {
            trimmed.size == node.items.size -> node
            trimmed.isEmpty() -> MathNode.Row(emptyList())
            trimmed.size == 1 -> trimmed[0]
            else -> MathNode.Row(trimmed)
        }
    }

    private fun parseEnvironment(name: String, tokens: List<MathToken>, pos: Int): Pair<MathNode, Int> {
        val (rows, endPos) = parseEnvironmentRows(tokens, pos)
        val node: MathNode = when (name) {
            "cases" -> MathNode.Cases(rows.map { cellsToNode(it) })
            "aligned", "align", "alignedat", "alignat", "gather", "gathered" ->
                MathNode.AlignedRows(rows)
            "matrix", "pmatrix", "bmatrix", "Bmatrix", "vmatrix", "Vmatrix" -> {
                val (left, right) = MATRIX_DELIMITERS.getValue(name)
                MathNode.Matrix(rows, left, right)
            }
            // Unknown environment: degrade to aligned rows rather than dropping content.
            else -> MathNode.AlignedRows(rows)
        }
        return node to endPos
    }

    private val MATRIX_DELIMITERS = mapOf(
        "matrix" to ("" to ""),
        "pmatrix" to ("(" to ")"),
        "bmatrix" to ("[" to "]"),
        "Bmatrix" to ("{" to "}"),
        "vmatrix" to ("|" to "|"),
        "Vmatrix" to ("‖" to "‖"),
    )

    /** Joins the cells of one row into a single node. */
    private fun cellsToNode(cells: List<MathNode>): MathNode = when (cells.size) {
        0 -> MathNode.Row(emptyList())
        1 -> cells[0]
        else -> MathNode.Row(cells)
    }

    private fun nodeToString(node: MathNode): String = when (node) {
        is MathNode.Atom -> node.symbol
        is MathNode.Text -> node.value
        is MathNode.Row -> node.items.joinToString("") { nodeToString(it) }
        else -> ""
    }

    private val GREEK_MAP = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ",
        "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
        "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ",
        "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ",
        "omega" to "ω",
        "Alpha" to "Α", "Beta" to "Β", "Gamma" to "Γ", "Delta" to "Δ",
        "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
        "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    )
}

/**
 * Math renderer - converts AST to readable Unicode text.
 */
object MathRenderer {
    fun render(node: MathNode): String = when (node) {
        is MathNode.Fraction -> "(${render(node.numerator)})/(${render(node.denominator)})"
        is MathNode.Radical -> "√(${render(node.content)})"
        is MathNode.Superscript -> "${render(node.base)}${toSuperscript(node.exponent)}"
        is MathNode.Subscript -> "${render(node.base)}${toSubscript(node.index)}"
        is MathNode.SuperscriptSubscript -> buildString {
            append(render(node.base))
            node.exponent?.let { append(toSuperscript(it)) }
            node.index?.let { append(toSubscript(it)) }
        }
        is MathNode.UnderOver -> buildString {
            append(render(node.base))
            node.under?.let { append("_${render(it)}") }
            node.over?.let { append("^${render(it)}") }
        }
        is MathNode.Delimited -> "${node.leftDelimiter}${render(node.content)}${node.rightDelimiter}"
        is MathNode.Matrix -> buildString {
            append(node.leftDelimiter)
            node.rows.forEachIndexed { i, row ->
                if (i > 0) append(" \\\\ ")
                append(row.joinToString(" & ") { render(it) })
            }
            append(node.rightDelimiter)
        }
        is MathNode.AlignedRows -> node.rows.joinToString(" \\\\ ") { row ->
            row.joinToString(" & ") { render(it) }
        }
        is MathNode.Cases -> buildString {
            append("{ ")
            append(node.rows.joinToString("; ") { render(it) })
        }
        is MathNode.Operator -> node.symbol
        is MathNode.Text -> node.value
        is MathNode.Atom -> node.symbol
        is MathNode.Group -> node.children.joinToString("") { render(it) }
        is MathNode.Row -> node.items.joinToString("") { render(it) }
    }

    private fun toSuperscript(node: MathNode): String {
        val text = render(node)
        val mapped = text.map { SUPERSCRIPT_MAP[it] ?: it }
        // Multi-character or unmappable scripts cannot be lifted into
        // Unicode superscripts; keep the script relationship explicit
        // instead of rendering it as baseline text (x^(ab), never "xab").
        val liftable = text.all { SUPERSCRIPT_MAP.containsKey(it) }
        return if (liftable) mapped.joinToString("") else "^($text)"
    }

    private fun toSubscript(node: MathNode): String {
        val text = render(node)
        val mapped = text.map { SUBSCRIPT_MAP[it] ?: it }
        val liftable = text.all { SUBSCRIPT_MAP.containsKey(it) }
        return if (liftable) mapped.joinToString("") else "_($text)"
    }

    private val SUPERSCRIPT_MAP = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', 'n' to 'ⁿ',
        '(' to '⁽', ')' to '⁾',
    )

    private val SUBSCRIPT_MAP = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌',
    )
}
