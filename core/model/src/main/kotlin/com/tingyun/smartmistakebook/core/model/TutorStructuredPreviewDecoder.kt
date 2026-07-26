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
 * Respond previews additionally require the canonical guard order before `messageMarkdown`.
 * A complete legacy/message-first document remains eligible for normal terminal validation, but
 * its free text is never exposed incrementally.
 */
class TutorStructuredPreviewDecoder(
    private val target: TutorStreamTarget,
    private val solutionPreviewAllowed: Boolean = false,
) {
    private val incrementalScanner = IncrementalStructuredPreviewScanner(
        target = target,
        solutionPreviewAllowed = solutionPreviewAllowed,
    )
    private var inputChars = 0
    private var rejected = false
    private var completed = false

    internal val incrementalInspectedCharacterCount: Long
        get() = incrementalScanner.inspectedCharacterCount

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
        if (inputChars + fragment.length > MAX_STRUCTURED_PREVIEW_CHARS) {
            rejected = true
            return ""
        }
        inputChars += fragment.length

        return when (val scan = incrementalScanner.append(fragment)) {
            IncrementalScan.Invalid -> {
                rejected = true
                ""
            }

            is IncrementalScan.Decoded -> scan.delta
        }
    }

    /** Accepts only one complete root object and never flushes an incomplete JSON string. */
    fun complete(): TutorStructuredPreviewCompletion {
        if (rejected) return TutorStructuredPreviewCompletion.Rejected
        val markdown = incrementalScanner.complete()
        if (markdown == null) {
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

private sealed interface IncrementalScan {
    data class Decoded(val delta: String) : IncrementalScan

    data object Invalid : IncrementalScan
}

/**
 * A resumable JSON syntax scanner. Each appended code unit enters [consume] exactly once; state
 * needed at a fragment boundary stays in the scanner instead of being reconstructed from the
 * accumulated provider response.
 */
private class IncrementalStructuredPreviewScanner(
    private val target: TutorStreamTarget,
    private val solutionPreviewAllowed: Boolean,
) {
    private val containers = ArrayDeque<JsonContainer>()
    private var token: JsonToken? = null
    private var documentStarted = false
    private var documentComplete = false
    private var invalid = false

    private var intentDecisionStarted = false
    private var intentDecisionSeen = false
    private var solutionRevealed: Boolean? = null
    private var messageStarted = false
    private var messageClosed = false
    private val messageMarkdown = StringBuilder()
    private var messageDecodedChars = 0
    private var nextCanonicalPreviewField = 0
    private var previewEligible = true
    private var messagePreviewEnabled = false

    var inspectedCharacterCount: Long = 0
        private set

    fun append(fragment: String): IncrementalScan {
        if (invalid) return IncrementalScan.Invalid
        val decoded = StringBuilder()
        for (character in fragment) {
            inspectedCharacterCount += 1
            if (!consume(character, decoded)) {
                invalid = true
                return IncrementalScan.Invalid
            }
        }
        return IncrementalScan.Decoded(decoded.toString())
    }

    fun complete(): String? =
        messageMarkdown.toString().takeIf {
            !invalid && documentComplete && messageClosed
        }

    private fun consume(character: Char, decoded: StringBuilder): Boolean {
        while (true) {
            when (val activeToken = token) {
                is StringToken -> return consumeString(activeToken, character, decoded)
                is LiteralToken -> return consumeLiteral(activeToken, character)
                is NumberToken -> {
                    when (consumeNumber(activeToken, character)) {
                        NumberConsume.Consumed -> return true
                        NumberConsume.Invalid -> return false
                        NumberConsume.Reprocess -> continue
                    }
                }

                null -> return consumeStructural(character)
            }
        }
    }

    private fun consumeStructural(character: Char): Boolean {
        if (!documentStarted) {
            if (character.isJsonWhitespace()) return true
            if (character != '{') return false
            documentStarted = true
            return pushObject(ValueRole.ROOT, isRoot = true)
        }
        if (documentComplete) return character.isJsonWhitespace()
        if (character.isJsonWhitespace()) return true

        return when (val container = containers.lastOrNull()) {
            is ObjectContainer -> consumeObjectStructural(container, character)
            is ArrayContainer -> consumeArrayStructural(container, character)
            null -> false
        }
    }

    private fun consumeObjectStructural(
        container: ObjectContainer,
        character: Char,
    ): Boolean = when (container.state) {
        ObjectState.FIRST_KEY_OR_END -> when (character) {
            '}' -> closeContainer(container)
            '"' -> startString(ValueRole.KEY)
            else -> false
        }

        ObjectState.KEY -> {
            if (character != '"') {
                false
            } else {
                startString(ValueRole.KEY)
            }
        }

        ObjectState.COLON -> {
            if (character != ':') {
                false
            } else {
                container.state = ObjectState.VALUE
                true
            }
        }

        ObjectState.VALUE -> startValue(
            character = character,
            role = container.valueRoleForCurrentKey(),
        )

        ObjectState.COMMA_OR_END -> when (character) {
            ',' -> {
                container.state = ObjectState.KEY
                true
            }

            '}' -> closeContainer(container)
            else -> false
        }
    }

    private fun consumeArrayStructural(
        container: ArrayContainer,
        character: Char,
    ): Boolean = when (container.state) {
        ArrayState.FIRST_VALUE_OR_END -> {
            if (character == ']') {
                closeContainer(container)
            } else {
                startValue(character, ValueRole.GENERIC)
            }
        }

        ArrayState.VALUE -> startValue(character, ValueRole.GENERIC)
        ArrayState.COMMA_OR_END -> when (character) {
            ',' -> {
                container.state = ArrayState.VALUE
                true
            }

            ']' -> closeContainer(container)
            else -> false
        }
    }

    private fun startValue(
        character: Char,
        role: ValueRole,
    ): Boolean {
        when (role) {
            ValueRole.MESSAGE -> {
                if (messageStarted || character != '"') return false
                messageStarted = true
                if (nextCanonicalPreviewField != 2) previewEligible = false
                messagePreviewEnabled =
                    target == TutorStreamTarget.LOBBY ||
                    (solutionPreviewAllowed && previewEligible)
                return startString(role)
            }

            ValueRole.INTENT_DECISION -> {
                if (intentDecisionStarted || character != '{') return false
                intentDecisionStarted = true
                if (nextCanonicalPreviewField != 0) previewEligible = false
                return pushObject(role, isRoot = false)
            }

            ValueRole.SOLUTION_REVEALED -> {
                if (solutionRevealed != null) return false
                if (nextCanonicalPreviewField != 1) previewEligible = false
                return when (character) {
                    't' -> startLiteral("true", role, booleanValue = true)
                    'f' -> startLiteral("false", role, booleanValue = false)
                    else -> false
                }
            }

            ValueRole.GENERIC, ValueRole.ROOT, ValueRole.KEY -> Unit
        }

        return when (character) {
            '{' -> pushObject(ValueRole.GENERIC, isRoot = false)
            '[' -> pushArray(ValueRole.GENERIC)
            '"' -> startString(ValueRole.GENERIC)
            't' -> startLiteral("true", ValueRole.GENERIC)
            'f' -> startLiteral("false", ValueRole.GENERIC)
            'n' -> startLiteral("null", ValueRole.GENERIC)
            '-' -> startNumber(ValueRole.GENERIC, NumberState.AFTER_MINUS)
            '0' -> startNumber(ValueRole.GENERIC, NumberState.ZERO)
            in '1'..'9' -> startNumber(ValueRole.GENERIC, NumberState.INTEGER)
            else -> false
        }
    }

    private fun startString(role: ValueRole): Boolean {
        token = StringToken(
            role = role,
            decodedKey = if (role == ValueRole.KEY) StringBuilder() else null,
        )
        return true
    }

    private fun consumeString(
        activeToken: StringToken,
        character: Char,
        decoded: StringBuilder,
    ): Boolean = when (activeToken.mode) {
        StringMode.NORMAL -> when {
            activeToken.pendingLiteralHighSurrogate != null -> {
                val high = activeToken.pendingLiteralHighSurrogate
                if (!character.isLowSurrogate()) {
                    false
                } else {
                    activeToken.pendingLiteralHighSurrogate = null
                    appendDecoded(activeToken, "$high$character", decoded)
                }
            }

            character == '"' -> finishString(activeToken)
            character == '\\' -> {
                activeToken.mode = StringMode.ESCAPE
                true
            }

            character < ' ' || character.isLowSurrogate() -> false
            character.isHighSurrogate() -> {
                activeToken.pendingLiteralHighSurrogate = character
                true
            }

            else -> appendDecoded(activeToken, character.toString(), decoded)
        }

        StringMode.ESCAPE -> when (character) {
            '"', '\\', '/' -> {
                activeToken.mode = StringMode.NORMAL
                appendDecoded(activeToken, character.toString(), decoded)
            }

            'b' -> {
                activeToken.mode = StringMode.NORMAL
                appendDecoded(activeToken, "\b", decoded)
            }

            'f' -> {
                activeToken.mode = StringMode.NORMAL
                appendDecoded(activeToken, "\u000C", decoded)
            }

            'n' -> {
                activeToken.mode = StringMode.NORMAL
                appendDecoded(activeToken, "\n", decoded)
            }

            'r' -> {
                activeToken.mode = StringMode.NORMAL
                appendDecoded(activeToken, "\r", decoded)
            }

            't' -> {
                activeToken.mode = StringMode.NORMAL
                appendDecoded(activeToken, "\t", decoded)
            }

            'u' -> {
                activeToken.mode = StringMode.UNICODE
                activeToken.unicodeDigits = 0
                activeToken.unicodeValue = 0
                true
            }

            else -> false
        }

        StringMode.UNICODE, StringMode.LOW_UNICODE ->
            consumeUnicodeDigit(activeToken, character, decoded)

        StringMode.LOW_BACKSLASH -> {
            if (character != '\\') {
                false
            } else {
                activeToken.mode = StringMode.LOW_U
                true
            }
        }

        StringMode.LOW_U -> {
            if (character != 'u') {
                false
            } else {
                activeToken.mode = StringMode.LOW_UNICODE
                activeToken.unicodeDigits = 0
                activeToken.unicodeValue = 0
                true
            }
        }
    }

    private fun consumeUnicodeDigit(
        activeToken: StringToken,
        character: Char,
        decoded: StringBuilder,
    ): Boolean {
        val digit = character.digitToIntOrNull(16) ?: return false
        activeToken.unicodeValue = activeToken.unicodeValue * 16 + digit
        activeToken.unicodeDigits += 1
        if (activeToken.unicodeDigits < 4) return true

        val codeUnit = activeToken.unicodeValue.toChar()
        return if (activeToken.mode == StringMode.LOW_UNICODE) {
            val high = activeToken.pendingEscapedHighSurrogate
            if (high == null || !codeUnit.isLowSurrogate()) {
                false
            } else {
                activeToken.pendingEscapedHighSurrogate = null
                activeToken.mode = StringMode.NORMAL
                appendDecoded(activeToken, "$high$codeUnit", decoded)
            }
        } else {
            when {
                codeUnit.isLowSurrogate() -> false
                codeUnit.isHighSurrogate() -> {
                    activeToken.pendingEscapedHighSurrogate = codeUnit
                    activeToken.mode = StringMode.LOW_BACKSLASH
                    true
                }

                else -> {
                    activeToken.mode = StringMode.NORMAL
                    appendDecoded(activeToken, codeUnit.toString(), decoded)
                }
            }
        }
    }

    private fun appendDecoded(
        activeToken: StringToken,
        value: String,
        decoded: StringBuilder,
    ): Boolean = when (activeToken.role) {
        ValueRole.KEY -> {
            activeToken.decodedKey?.append(value)
            true
        }

        ValueRole.MESSAGE -> {
            messageDecodedChars += value.length
            if (
                messageDecodedChars > TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS ||
                !value.isSafeDecodedPreview()
            ) {
                false
            } else {
                messageMarkdown.append(value)
                if (messagePreviewEnabled) decoded.append(value)
                true
            }
        }

        else -> true
    }

    private fun finishString(activeToken: StringToken): Boolean {
        token = null
        return if (activeToken.role == ValueRole.KEY) {
            val container = containers.lastOrNull() as? ObjectContainer ?: return false
            container.currentKey = activeToken.decodedKey?.toString() ?: return false
            container.state = ObjectState.COLON
            true
        } else {
            finishValue(activeToken.role)
        }
    }

    private fun startLiteral(
        expected: String,
        role: ValueRole,
        booleanValue: Boolean? = null,
    ): Boolean {
        token = LiteralToken(
            expected = expected,
            role = role,
            booleanValue = booleanValue,
            consumedChars = 1,
        )
        return true
    }

    private fun consumeLiteral(
        activeToken: LiteralToken,
        character: Char,
    ): Boolean {
        if (character != activeToken.expected.getOrNull(activeToken.consumedChars)) return false
        activeToken.consumedChars += 1
        if (activeToken.consumedChars < activeToken.expected.length) return true

        token = null
        if (activeToken.role == ValueRole.SOLUTION_REVEALED) {
            solutionRevealed = activeToken.booleanValue ?: return false
        }
        return finishValue(activeToken.role)
    }

    private fun startNumber(
        role: ValueRole,
        state: NumberState,
    ): Boolean {
        token = NumberToken(role, state)
        return true
    }

    private fun consumeNumber(
        activeToken: NumberToken,
        character: Char,
    ): NumberConsume {
        when (activeToken.state) {
            NumberState.AFTER_MINUS -> {
                activeToken.state = when (character) {
                    '0' -> NumberState.ZERO
                    in '1'..'9' -> NumberState.INTEGER
                    else -> return NumberConsume.Invalid
                }
                return NumberConsume.Consumed
            }

            NumberState.ZERO -> when {
                character == '.' -> activeToken.state = NumberState.DOT
                character == 'e' || character == 'E' ->
                    activeToken.state = NumberState.EXPONENT_MARK
                character.isJsonValueDelimiter() -> return finishNumber(activeToken)
                else -> return NumberConsume.Invalid
            }

            NumberState.INTEGER -> when {
                character in '0'..'9' -> Unit
                character == '.' -> activeToken.state = NumberState.DOT
                character == 'e' || character == 'E' ->
                    activeToken.state = NumberState.EXPONENT_MARK
                character.isJsonValueDelimiter() -> return finishNumber(activeToken)
                else -> return NumberConsume.Invalid
            }

            NumberState.DOT -> {
                if (character !in '0'..'9') return NumberConsume.Invalid
                activeToken.state = NumberState.FRACTION
            }

            NumberState.FRACTION -> when {
                character in '0'..'9' -> Unit
                character == 'e' || character == 'E' ->
                    activeToken.state = NumberState.EXPONENT_MARK
                character.isJsonValueDelimiter() -> return finishNumber(activeToken)
                else -> return NumberConsume.Invalid
            }

            NumberState.EXPONENT_MARK -> {
                activeToken.state = when (character) {
                    '+', '-' -> NumberState.EXPONENT_SIGN
                    in '0'..'9' -> NumberState.EXPONENT_DIGITS
                    else -> return NumberConsume.Invalid
                }
            }

            NumberState.EXPONENT_SIGN -> {
                if (character !in '0'..'9') return NumberConsume.Invalid
                activeToken.state = NumberState.EXPONENT_DIGITS
            }

            NumberState.EXPONENT_DIGITS -> when {
                character in '0'..'9' -> Unit
                character.isJsonValueDelimiter() -> return finishNumber(activeToken)
                else -> return NumberConsume.Invalid
            }
        }
        return NumberConsume.Consumed
    }

    private fun finishNumber(activeToken: NumberToken): NumberConsume {
        token = null
        return if (finishValue(activeToken.role)) {
            NumberConsume.Reprocess
        } else {
            NumberConsume.Invalid
        }
    }

    private fun pushObject(
        role: ValueRole,
        isRoot: Boolean,
    ): Boolean {
        if (containers.size > MAX_JSON_DEPTH) return false
        containers.addLast(ObjectContainer(valueRole = role, isRoot = isRoot))
        return true
    }

    private fun pushArray(role: ValueRole): Boolean {
        if (containers.size > MAX_JSON_DEPTH) return false
        containers.addLast(ArrayContainer(valueRole = role))
        return true
    }

    private fun closeContainer(container: JsonContainer): Boolean {
        if (containers.lastOrNull() !== container) return false
        containers.removeLast()
        if (container is ObjectContainer && container.isRoot) {
            if (
                !messageClosed ||
                (
                    target == TutorStreamTarget.RESPOND &&
                        (
                            !intentDecisionSeen ||
                                solutionRevealed == null ||
                                (solutionRevealed == true && !solutionPreviewAllowed)
                            )
                    )
            ) {
                return false
            }
            documentComplete = true
            return true
        }
        return finishValue(container.valueRole)
    }

    private fun finishValue(role: ValueRole): Boolean {
        when (role) {
            ValueRole.MESSAGE -> messageClosed = true
            ValueRole.INTENT_DECISION -> {
                intentDecisionSeen = true
                nextCanonicalPreviewField = maxOf(nextCanonicalPreviewField, 1)
            }

            ValueRole.SOLUTION_REVEALED ->
                nextCanonicalPreviewField = maxOf(nextCanonicalPreviewField, 2)

            else -> Unit
        }

        return when (val parent = containers.lastOrNull()) {
            is ObjectContainer -> {
                if (parent.state != ObjectState.VALUE) return false
                parent.currentKey = null
                parent.state = ObjectState.COMMA_OR_END
                true
            }

            is ArrayContainer -> {
                if (
                    parent.state != ArrayState.FIRST_VALUE_OR_END &&
                    parent.state != ArrayState.VALUE
                ) {
                    return false
                }
                parent.state = ArrayState.COMMA_OR_END
                true
            }

            null -> false
        }
    }

    private fun ObjectContainer.valueRoleForCurrentKey(): ValueRole {
        if (!isRoot) return ValueRole.GENERIC
        return when (currentKey) {
            MESSAGE_MARKDOWN -> ValueRole.MESSAGE
            INTENT_DECISION -> if (target == TutorStreamTarget.RESPOND) {
                ValueRole.INTENT_DECISION
            } else {
                ValueRole.GENERIC
            }

            SOLUTION_REVEALED -> if (target == TutorStreamTarget.RESPOND) {
                ValueRole.SOLUTION_REVEALED
            } else {
                ValueRole.GENERIC
            }

            else -> ValueRole.GENERIC
        }
    }

    private companion object {
        const val MESSAGE_MARKDOWN = "messageMarkdown"
        const val INTENT_DECISION = "intentDecision"
        const val SOLUTION_REVEALED = "solutionRevealed"
        const val MAX_JSON_DEPTH = 64
    }
}

private enum class ValueRole {
    ROOT,
    KEY,
    GENERIC,
    INTENT_DECISION,
    SOLUTION_REVEALED,
    MESSAGE,
}

private sealed class JsonContainer(
    val valueRole: ValueRole,
)

private class ObjectContainer(
    valueRole: ValueRole,
    val isRoot: Boolean,
    var state: ObjectState = ObjectState.FIRST_KEY_OR_END,
    var currentKey: String? = null,
) : JsonContainer(valueRole)

private class ArrayContainer(
    valueRole: ValueRole,
    var state: ArrayState = ArrayState.FIRST_VALUE_OR_END,
) : JsonContainer(valueRole)

private enum class ObjectState {
    FIRST_KEY_OR_END,
    KEY,
    COLON,
    VALUE,
    COMMA_OR_END,
}

private enum class ArrayState {
    FIRST_VALUE_OR_END,
    VALUE,
    COMMA_OR_END,
}

private sealed interface JsonToken

private class StringToken(
    val role: ValueRole,
    val decodedKey: StringBuilder?,
    var mode: StringMode = StringMode.NORMAL,
    var unicodeDigits: Int = 0,
    var unicodeValue: Int = 0,
    var pendingLiteralHighSurrogate: Char? = null,
    var pendingEscapedHighSurrogate: Char? = null,
) : JsonToken

private enum class StringMode {
    NORMAL,
    ESCAPE,
    UNICODE,
    LOW_BACKSLASH,
    LOW_U,
    LOW_UNICODE,
}

private class LiteralToken(
    val expected: String,
    val role: ValueRole,
    val booleanValue: Boolean?,
    var consumedChars: Int,
) : JsonToken

private class NumberToken(
    val role: ValueRole,
    var state: NumberState,
) : JsonToken

private enum class NumberState {
    AFTER_MINUS,
    ZERO,
    INTEGER,
    DOT,
    FRACTION,
    EXPONENT_MARK,
    EXPONENT_SIGN,
    EXPONENT_DIGITS,
}

private enum class NumberConsume {
    Consumed,
    Reprocess,
    Invalid,
}

private fun Char.isJsonWhitespace(): Boolean =
    this == ' ' || this == '\n' || this == '\r' || this == '\t'

private fun Char.isJsonValueDelimiter(): Boolean =
    isJsonWhitespace() || this == ',' || this == '}' || this == ']'

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
