package com.tingyun.smartmistakebook.core.model



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

    /** 裸 URL / active scheme 的统一判定（数据层 requireTutorSceneText 与 block renderer 共用）。 */
    val BARE_URL: Regex = Regex(
        "(?i)(?:\\b(?:https?|ftp|file|mailto|data|javascript):\\S*|\\bwww\\.[^\\s]+)",
    )

    fun requiresPlainTextFallback(value: String): Boolean {
        val bounded = value.take(StructuredContentLimits.MAX_TEXT_CHARS)
        return html.containsMatchIn(bounded) ||
            activeScheme.containsMatchIn(bounded) ||
            remoteImage.containsMatchIn(bounded)
    }

    fun parse(value: String): List<InlineToken> = scan(
        value = value,
        tolerateUnclosedTail = false,
    )

    /**
     * 流式渐进解析：与 [parse] 同一受限白名单、同一扫描器，带 `tolerateUnclosedTail = true`。
     * 仅当文本以未闭合 `**`/`*`/`` ` ``/`$` 起始标记结尾时，把剩余内容当作"仍在进行中的样式段"
     * 渲染，避免流式中"先字面量、补全后突变"的闪烁。已闭合部分行为与 [parse] 完全一致。
     */
    fun parseStreaming(value: String): List<InlineToken> = scan(
        value = value,
        tolerateUnclosedTail = true,
    )

    private fun scan(value: String, tolerateUnclosedTail: Boolean): List<InlineToken> {
        val bounded = stripControlCharacters(value.take(StructuredContentLimits.MAX_TEXT_CHARS))
        if (requiresPlainTextFallback(bounded)) return plainTextTokens(bounded)

        val tokens = mutableListOf<InlineToken>()
        var index = 0
        while (index < bounded.length) {
            if (bounded[index] == '\n') {
                tokens += InlineToken.LineBreak
                index += 1
                continue
            }
            val marker = markerAt(bounded, index, tolerateUnclosedTail)
            if (marker == null) {
                val next = nextMarkerIndex(bounded, index)
                tokens += InlineToken.Text(bounded.substring(index, next))
                index = next
            } else {
                when (marker) {
                    is Marker.Rendered -> {
                        tokens += marker.token
                        index = marker.nextIndex
                    }
                    is Marker.Pending -> {
                        // 流式容忍：末尾未闭合起始标记 → 把剩余内容作为仍在进行的样式段。
                        tokens += marker.token
                        break
                    }
                    is Marker.Literal -> {
                        tokens += InlineToken.Text(marker.literal)
                        index = marker.nextIndex
                    }
                }
            }
        }
        return tokens
    }

    private sealed interface Marker {
        data class Rendered(val nextIndex: Int, val token: InlineToken) : Marker
        data class Pending(val token: InlineToken) : Marker
        data class Literal(val nextIndex: Int, val literal: String) : Marker
    }

    /**
     * 分类 [index] 处的 inline 标记并返回对应处理，无标记时返回 null。
     * [tolerateUnclosedTail] 下，一个"无闭合伙伴且其后无任何标记"的起始标记返回 [Marker.Pending]。
     */
    private fun markerAt(value: String, index: Int, tolerateUnclosedTail: Boolean): Marker? {
        if (value.startsWith("**", index)) {
            val closing = value.indexOf("**", startIndex = index + 2)
            if (closing > index + 2) {
                return Marker.Rendered(closing + 2, InlineToken.Strong(value.substring(index + 2, closing)))
            }
            if (tolerateUnclosedTail && isPendingTail(value, index + 2)) {
                return Marker.Pending(InlineToken.Strong(value.substring(index + 2)))
            }
            return Marker.Literal(index + 2, "**")
        }

        val char = value[index]
        if (char != '*' && char != '`' && char != '$') return null
        val kind = when (char) {
            '*' -> MarkerKind.EMPHASIS
            '`' -> MarkerKind.CODE
            else -> MarkerKind.FORMULA
        }
        val closing = value.indexOf(char, startIndex = index + 1)
        if (closing > index + 1) {
            val content = value.substring(index + 1, closing)
            return Marker.Rendered(closing + 1, buildInlineToken(kind, content))
        }
        if (tolerateUnclosedTail && isPendingTail(value, index + 1)) {
            return Marker.Pending(buildInlineToken(kind, value.substring(index + 1)))
        }
        return Marker.Literal(index + 1, char.toString())
    }

    private fun buildInlineToken(kind: MarkerKind, content: String): InlineToken = when (kind) {
        MarkerKind.EMPHASIS -> InlineToken.Emphasis(content)
        MarkerKind.CODE -> InlineToken.Code(content)
        MarkerKind.FORMULA -> InlineToken.Formula(RestrictedFormulaText.sanitize(content))
    }

    /**
     * 判定 [fromIndex] 起是否为可容忍的流式"挂起尾"：剩余非空、且无后续标记（否则它要么是
     * 字面量要么会由后面的标记决定）。content 不能以空白开头——空白的 `* ` 不是强调开环。
     */
    private fun isPendingTail(value: String, fromIndex: Int): Boolean {
        if (fromIndex >= value.length) return false
        val contentStart = fromIndex
        if (value[contentStart] == ' ' || value[contentStart] == '\t') return false
        for (i in contentStart until value.length) {
            val c = value[i]
            if (c == '*' || c == '`' || c == '$') return false
        }
        return true
    }

    private enum class MarkerKind { EMPHASIS, CODE, FORMULA }

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

internal fun stripControlCharacters(value: String): String = value
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .filter { character ->
        (character == '\n' || character == '\t' || !character.isISOControl()) &&
            !character.isBidirectionalControl()
    }
