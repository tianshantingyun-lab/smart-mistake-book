package com.tingyun.smartmistakebook.core.model

/** The only model tasks whose structured output may produce a live text preview. */
enum class TutorStreamTarget {
    RESPOND,
    LOBBY,
}

sealed interface TutorStructuredPreviewCompletion {
    data class Accepted(
        val messageMarkdown: String,
    ) : TutorStructuredPreviewCompletion

    data object Rejected : TutorStructuredPreviewCompletion
}

/**
 * Incrementally extracts the one root JSON field that is safe to route into the Markdown
 * assembler. Provider JSON, nested fields, and every other output field are never returned.
 *
 * Respond previews additionally require the solution guard to appear before `messageMarkdown`.
 * This makes a provider that uses the old/message-first wire order fail closed.
 */
class TutorStructuredPreviewDecoder(
    private val target: TutorStreamTarget,
    private val solutionPreviewAllowed: Boolean = false,
) {
    private val rawJson = StringBuilder()
    private var emittedChars = 0
    private var rejected = false
    private var completed = false

    init {
        require(target == TutorStreamTarget.RESPOND || !solutionPreviewAllowed) {
            "Lobby streams do not have solution preview authority"
        }
    }

    /**
     * Returns only newly decoded `messageMarkdown` characters. Malformed or unauthorized input
     * permanently closes this decoder and returns an empty delta.
     */
    fun append(fragment: String): String {
        if (fragment.isEmpty() || rejected || completed) return ""
        if (rawJson.length + fragment.length > MAX_STRUCTURED_PREVIEW_CHARS) {
            rejected = true
            return ""
        }
        rawJson.append(fragment)

        return when (val scan = StructuredPreviewScanner(rawJson, target, solutionPreviewAllowed).scan()) {
            ScanResult.Invalid -> {
                rejected = true
                ""
            }

            is ScanResult.ValidPrefix -> {
                val markdown = scan.messageMarkdown ?: return ""
                val safeLength = markdown.safeUnicodePrefixLength()
                if (!markdown.take(safeLength).isSafeDecodedPreview() || safeLength < emittedChars) {
                    rejected = true
                    ""
                } else {
                    markdown.substring(emittedChars, safeLength).also {
                        emittedChars = safeLength
                    }
                }
            }
        }
    }

    /** Accepts only one complete root object and never flushes an incomplete JSON string. */
    fun complete(): TutorStructuredPreviewCompletion {
        if (rejected) return TutorStructuredPreviewCompletion.Rejected
        val scan = StructuredPreviewScanner(rawJson, target, solutionPreviewAllowed).scan()
        val markdown = (scan as? ScanResult.ValidPrefix)
            ?.takeIf { it.documentComplete && it.messageClosed }
            ?.messageMarkdown
            ?: return TutorStructuredPreviewCompletion.Rejected
        if (markdown.safeUnicodePrefixLength() != markdown.length || !markdown.isSafeDecodedPreview()) {
            rejected = true
            return TutorStructuredPreviewCompletion.Rejected
        }
        completed = true
        return TutorStructuredPreviewCompletion.Accepted(markdown)
    }

    private companion object {
        const val MAX_STRUCTURED_PREVIEW_CHARS = 64 * 1_024
    }
}

private sealed interface ScanResult {
    data class ValidPrefix(
        val messageMarkdown: String?,
        val messageClosed: Boolean,
        val documentComplete: Boolean,
    ) : ScanResult

    data object Invalid : ScanResult
}

private class StructuredPreviewScanner(
    private val source: CharSequence,
    private val target: TutorStreamTarget,
    private val solutionPreviewAllowed: Boolean,
) {
    private var index = 0
    private var intentDecisionSeen = false
    private var solutionRevealed: Boolean? = null
    private var messageMarkdown: String? = null
    private var messageClosed = false

    fun scan(): ScanResult {
        skipWhitespace()
        if (!consume('{')) {
            return if (atEnd()) prefix() else ScanResult.Invalid
        }
        skipWhitespace()
        if (consume('}')) return finishDocument()

        while (true) {
            val key = when (val parsed = parseString()) {
                is StringParse.Complete -> parsed.value
                is StringParse.Incomplete -> return prefix()
                StringParse.Invalid -> return ScanResult.Invalid
            }
            skipWhitespace()
            if (!consume(':')) {
                return if (atEnd()) prefix() else ScanResult.Invalid
            }
            skipWhitespace()

            val valueStatus = when {
                key == MESSAGE_MARKDOWN -> parseMessageMarkdown()
                target == TutorStreamTarget.RESPOND && key == INTENT_DECISION ->
                    parseIntentDecision()
                target == TutorStreamTarget.RESPOND && key == SOLUTION_REVEALED ->
                    parseSolutionRevealed()
                else -> parseValue(depth = 0)
            }
            when (valueStatus) {
                ValueParse.Incomplete -> return prefix()
                ValueParse.Invalid -> return ScanResult.Invalid
                ValueParse.Complete -> Unit
            }

            skipWhitespace()
            when {
                consume(',') -> {
                    skipWhitespace()
                    if (atEnd()) return prefix()
                    if (peek() == '}') return ScanResult.Invalid
                }

                consume('}') -> return finishDocument()
                atEnd() -> return prefix()
                else -> return ScanResult.Invalid
            }
        }
    }

    private fun parseMessageMarkdown(): ValueParse {
        if (messageMarkdown != null) return ValueParse.Invalid
        if (target == TutorStreamTarget.RESPOND) {
            val revealed = solutionRevealed ?: return ValueParse.Invalid
            if (!intentDecisionSeen || (revealed && !solutionPreviewAllowed)) {
                return ValueParse.Invalid
            }
        }
        return when (val parsed = parseString()) {
            is StringParse.Complete -> {
                messageMarkdown = parsed.value
                messageClosed = true
                ValueParse.Complete
            }

            is StringParse.Incomplete -> {
                messageMarkdown = parsed.value
                messageClosed = false
                ValueParse.Incomplete
            }

            StringParse.Invalid -> ValueParse.Invalid
        }
    }

    private fun parseIntentDecision(): ValueParse {
        if (intentDecisionSeen) return ValueParse.Invalid
        if (peek() == null) return ValueParse.Incomplete
        if (peek() != '{') return ValueParse.Invalid
        return parseValue(depth = 0).also { status ->
            if (status == ValueParse.Complete) intentDecisionSeen = true
        }
    }

    private fun parseSolutionRevealed(): ValueParse {
        if (!intentDecisionSeen || solutionRevealed != null) return ValueParse.Invalid
        return when (val parsed = parseBoolean()) {
            is BooleanParse.Complete -> {
                solutionRevealed = parsed.value
                ValueParse.Complete
            }

            BooleanParse.Incomplete -> ValueParse.Incomplete
            BooleanParse.Invalid -> ValueParse.Invalid
        }
    }

    private fun finishDocument(): ScanResult {
        skipWhitespace()
        if (!atEnd()) return ScanResult.Invalid
        return prefix(documentComplete = true)
    }

    private fun prefix(documentComplete: Boolean = false) = ScanResult.ValidPrefix(
        messageMarkdown = messageMarkdown,
        messageClosed = messageClosed,
        documentComplete = documentComplete,
    )

    private fun parseValue(depth: Int): ValueParse {
        if (depth >= MAX_JSON_DEPTH) return ValueParse.Invalid
        skipWhitespace()
        return when (peek()) {
            null -> ValueParse.Incomplete
            '"' -> when (parseString()) {
                is StringParse.Complete -> ValueParse.Complete
                is StringParse.Incomplete -> ValueParse.Incomplete
                StringParse.Invalid -> ValueParse.Invalid
            }

            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            't' -> parseLiteral("true")
            'f' -> parseLiteral("false")
            'n' -> parseLiteral("null")
            '-', in '0'..'9' -> parseNumber()
            else -> ValueParse.Invalid
        }
    }

    private fun parseObject(depth: Int): ValueParse {
        consume('{')
        skipWhitespace()
        if (consume('}')) return ValueParse.Complete
        while (true) {
            when (parseString()) {
                is StringParse.Complete -> Unit
                is StringParse.Incomplete -> return ValueParse.Incomplete
                StringParse.Invalid -> return ValueParse.Invalid
            }
            skipWhitespace()
            if (!consume(':')) return if (atEnd()) ValueParse.Incomplete else ValueParse.Invalid
            when (val value = parseValue(depth)) {
                ValueParse.Complete -> Unit
                else -> return value
            }
            skipWhitespace()
            when {
                consume(',') -> {
                    skipWhitespace()
                    if (atEnd()) return ValueParse.Incomplete
                    if (peek() == '}') return ValueParse.Invalid
                }

                consume('}') -> return ValueParse.Complete
                atEnd() -> return ValueParse.Incomplete
                else -> return ValueParse.Invalid
            }
        }
    }

    private fun parseArray(depth: Int): ValueParse {
        consume('[')
        skipWhitespace()
        if (consume(']')) return ValueParse.Complete
        while (true) {
            when (val value = parseValue(depth)) {
                ValueParse.Complete -> Unit
                else -> return value
            }
            skipWhitespace()
            when {
                consume(',') -> {
                    skipWhitespace()
                    if (atEnd()) return ValueParse.Incomplete
                    if (peek() == ']') return ValueParse.Invalid
                }

                consume(']') -> return ValueParse.Complete
                atEnd() -> return ValueParse.Incomplete
                else -> return ValueParse.Invalid
            }
        }
    }

    private fun parseBoolean(): BooleanParse {
        val start = index
        return when {
            source.regionMatchesAt(index, "true") -> {
                index += 4
                BooleanParse.Complete(true)
            }

            source.regionMatchesAt(index, "false") -> {
                index += 5
                BooleanParse.Complete(false)
            }

            "true".startsWith(source.substringFrom(start)) ||
                "false".startsWith(source.substringFrom(start)) -> BooleanParse.Incomplete
            else -> BooleanParse.Invalid
        }
    }

    private fun parseLiteral(expected: String): ValueParse {
        val remaining = source.substringFrom(index)
        return when {
            remaining.length >= expected.length && remaining.startsWith(expected) -> {
                index += expected.length
                ValueParse.Complete
            }

            expected.startsWith(remaining) -> ValueParse.Incomplete
            else -> ValueParse.Invalid
        }
    }

    private fun parseNumber(): ValueParse {
        val start = index
        if (consume('-') && atEnd()) return ValueParse.Incomplete
        when {
            consume('0') -> {
                if (peek() in '0'..'9') return ValueParse.Invalid
            }

            peek() in '1'..'9' -> {
                index += 1
                while (peek() in '0'..'9') index += 1
            }

            else -> return if (atEnd()) ValueParse.Incomplete else ValueParse.Invalid
        }
        if (consume('.')) {
            if (peek() !in '0'..'9') return if (atEnd()) ValueParse.Incomplete else ValueParse.Invalid
            while (peek() in '0'..'9') index += 1
        }
        if (peek() == 'e' || peek() == 'E') {
            index += 1
            if (peek() == '+' || peek() == '-') index += 1
            if (peek() !in '0'..'9') return if (atEnd()) ValueParse.Incomplete else ValueParse.Invalid
            while (peek() in '0'..'9') index += 1
        }
        if (index == source.length) {
            index = start
            return ValueParse.Incomplete
        }
        return ValueParse.Complete
    }

    private fun parseString(): StringParse {
        if (!consume('"')) return if (atEnd()) StringParse.Incomplete("") else StringParse.Invalid
        val decoded = StringBuilder()
        while (!atEnd()) {
            when (val char = source[index++]) {
                '"' -> return StringParse.Complete(decoded.toString())
                '\\' -> when (val escape = parseEscape()) {
                    is EscapeParse.Complete -> decoded.append(escape.value)
                    EscapeParse.Incomplete -> return StringParse.Incomplete(decoded.toString())
                    EscapeParse.Invalid -> return StringParse.Invalid
                }

                else -> {
                    if (char < ' ' || char.isLowSurrogate()) return StringParse.Invalid
                    if (char.isHighSurrogate()) {
                        if (atEnd()) return StringParse.Incomplete(decoded.toString())
                        val low = source[index]
                        if (!low.isLowSurrogate()) return StringParse.Invalid
                        decoded.append(char).append(low)
                        index += 1
                    } else {
                        decoded.append(char)
                    }
                }
            }
        }
        return StringParse.Incomplete(decoded.toString())
    }

    private fun parseEscape(): EscapeParse {
        if (atEnd()) return EscapeParse.Incomplete
        return when (val escaped = source[index++]) {
            '"', '\\', '/' -> EscapeParse.Complete(escaped.toString())
            'b' -> EscapeParse.Complete("\b")
            'f' -> EscapeParse.Complete("\u000C")
            'n' -> EscapeParse.Complete("\n")
            'r' -> EscapeParse.Complete("\r")
            't' -> EscapeParse.Complete("\t")
            'u' -> parseUnicodeEscape()
            else -> EscapeParse.Invalid
        }
    }

    private fun parseUnicodeEscape(): EscapeParse {
        val highOrSingle = parseHexCodeUnit() ?: return if (hexPrefixIsValid()) {
            EscapeParse.Incomplete
        } else {
            EscapeParse.Invalid
        }
        val char = highOrSingle.toChar()
        if (char.isLowSurrogate()) return EscapeParse.Invalid
        if (!char.isHighSurrogate()) return EscapeParse.Complete(char.toString())

        if (source.length - index < 2) {
            return if (remainingMatchesPrefix("\\u")) EscapeParse.Incomplete else EscapeParse.Invalid
        }
        if (source[index] != '\\' || source[index + 1] != 'u') return EscapeParse.Invalid
        index += 2
        val lowCodeUnit = parseHexCodeUnit() ?: return if (hexPrefixIsValid()) {
            EscapeParse.Incomplete
        } else {
            EscapeParse.Invalid
        }
        val low = lowCodeUnit.toChar()
        return if (low.isLowSurrogate()) {
            EscapeParse.Complete("$char$low")
        } else {
            EscapeParse.Invalid
        }
    }

    private fun parseHexCodeUnit(): Int? {
        if (source.length - index < 4) return null
        var value = 0
        repeat(4) {
            val digit = source[index + it].digitToIntOrNull(16) ?: return null
            value = value * 16 + digit
        }
        index += 4
        return value
    }

    private fun hexPrefixIsValid(): Boolean {
        val remaining = minOf(4, source.length - index)
        return (0 until remaining).all { offset ->
            source[index + offset].digitToIntOrNull(16) != null
        }
    }

    private fun remainingMatchesPrefix(expected: String): Boolean =
        expected.startsWith(source.substringFrom(index))

    private fun skipWhitespace() {
        while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') {
            index += 1
        }
    }

    private fun consume(expected: Char): Boolean {
        if (peek() != expected) return false
        index += 1
        return true
    }

    private fun peek(): Char? = source.getOrNull(index)

    private fun atEnd(): Boolean = index >= source.length

    private companion object {
        const val MESSAGE_MARKDOWN = "messageMarkdown"
        const val INTENT_DECISION = "intentDecision"
        const val SOLUTION_REVEALED = "solutionRevealed"
        const val MAX_JSON_DEPTH = 64
    }
}

private sealed interface ValueParse {
    data object Complete : ValueParse
    data object Incomplete : ValueParse
    data object Invalid : ValueParse
}

private sealed interface StringParse {
    data class Complete(val value: String) : StringParse
    data class Incomplete(val value: String) : StringParse
    data object Invalid : StringParse
}

private sealed interface BooleanParse {
    data class Complete(val value: Boolean) : BooleanParse
    data object Incomplete : BooleanParse
    data object Invalid : BooleanParse
}

private sealed interface EscapeParse {
    data class Complete(val value: String) : EscapeParse
    data object Incomplete : EscapeParse
    data object Invalid : EscapeParse
}

private fun CharSequence.substringFrom(startIndex: Int): String =
    subSequence(startIndex, length).toString()

private fun CharSequence.regionMatchesAt(startIndex: Int, expected: String): Boolean {
    if (startIndex + expected.length > length) return false
    return expected.indices.all { offset -> this[startIndex + offset] == expected[offset] }
}

private fun String.safeUnicodePrefixLength(): Int =
    if (lastOrNull()?.isHighSurrogate() == true) length - 1 else length

private fun String.isSafeDecodedPreview(): Boolean =
    length <= TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS &&
        none { char ->
            val allowedControl = char == '\n' || char == '\r' || char == '\t'
            (char.isISOControl() && !allowedControl) ||
                char == '\u061C' ||
                char == '\u200E' ||
                char == '\u200F' ||
                char in '\u202A'..'\u202E' ||
                char in '\u2066'..'\u2069'
        }
