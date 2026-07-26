package com.tingyun.smartmistakebook.core.model

sealed interface StreamingMarkdownCompletion {
    val snapshot: TutorMarkdownSnapshot

    data class Accepted(
        override val snapshot: TutorMarkdownSnapshot,
    ) : StreamingMarkdownCompletion

    data class Rejected(
        override val snapshot: TutorMarkdownSnapshot,
    ) : StreamingMarkdownCompletion
}

/**
 * Deterministically separates immutable Markdown blocks from the active tail.
 *
 * Ambiguous formulas, code spans, fenced blocks, and tables are withheld rather than rendered
 * under a meaning that a later fragment could change.
 */
class StreamingMarkdownAssembler {
    private val source = StringBuilder()
    private var lastSnapshot = TutorMarkdownSnapshot.EMPTY
    private var rejected = false
    private var completed = false

    fun append(fragment: String): TutorMarkdownSnapshot {
        if (fragment.isEmpty() || rejected || completed) return lastSnapshot
        if (source.length + fragment.length > TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS) {
            rejected = true
            return lastSnapshot
        }
        source.append(fragment)

        val analysis = analyzeMarkdown(source, endOfStream = false)
        if (analysis.invalid) {
            rejected = true
            return lastSnapshot
        }
        val visibleEnd = analysis.heldStart ?: source.length
        val stableEnd = analysis.stableEnd.coerceAtMost(visibleEnd)
        val next = TutorMarkdownSnapshot(
            stableMarkdown = source.substring(0, stableEnd).toTutorPreviewLiteral(),
            provisionalMarkdown = source.substring(stableEnd, visibleEnd).toTutorPreviewLiteral(),
        )
        if (!next.stableMarkdown.startsWith(lastSnapshot.stableMarkdown)) {
            rejected = true
            return lastSnapshot
        }
        lastSnapshot = next
        return next
    }

    fun complete(): StreamingMarkdownCompletion {
        if (completed) return StreamingMarkdownCompletion.Accepted(lastSnapshot)
        if (rejected) return rejectedCompletion()

        val analysis = analyzeMarkdown(source, endOfStream = true)
        if (analysis.invalid) {
            rejected = true
            return rejectedCompletion()
        }
        val completedMarkdown = when {
            analysis.heldStart == null -> source.toString().toTutorPreviewLiteral()
            analysis.literalizableHeldTail -> {
                source.substring(0, analysis.heldStart).toTutorPreviewLiteral() +
                    source.substring(analysis.heldStart).toIncompleteMarkdownLiteral()
            }
            else -> {
                rejected = true
                return rejectedCompletion()
            }
        }
        val snapshot = TutorMarkdownSnapshot(
            stableMarkdown = completedMarkdown,
            provisionalMarkdown = "",
        )
        if (!snapshot.stableMarkdown.startsWith(lastSnapshot.stableMarkdown)) {
            rejected = true
            return rejectedCompletion()
        }
        completed = true
        lastSnapshot = snapshot
        return StreamingMarkdownCompletion.Accepted(snapshot)
    }

    private fun rejectedCompletion(): StreamingMarkdownCompletion.Rejected =
        StreamingMarkdownCompletion.Rejected(
            TutorMarkdownSnapshot(
                stableMarkdown = lastSnapshot.stableMarkdown,
                provisionalMarkdown = "",
            ),
        )
}

private data class MarkdownAnalysis(
    val stableEnd: Int,
    val heldStart: Int?,
    val literalizableHeldTail: Boolean,
    val invalid: Boolean,
)

private data class MarkdownLine(
    val start: Int,
    val contentEnd: Int,
    val end: Int,
    val terminated: Boolean,
) {
    fun content(source: CharSequence): String = source.subSequence(start, contentEnd).toString()
}

private data class FenceAnalysis(
    val characterMask: BooleanArray,
    val heldStart: Int?,
    val stableBoundaries: List<Int>,
)

private data class TableAnalysis(
    val heldStart: Int?,
    val stableBoundaries: List<Int>,
)

private fun analyzeMarkdown(source: CharSequence, endOfStream: Boolean): MarkdownAnalysis {
    val unicodeIssue = source.findUnicodeIssue()
    if (unicodeIssue is UnicodeIssue.Invalid) {
        return MarkdownAnalysis(
            stableEnd = 0,
            heldStart = null,
            literalizableHeldTail = false,
            invalid = true,
        )
    }
    val lines = source.markdownLines()
    val fences = analyzeFences(source, lines, endOfStream)
    val tables = analyzeTables(source, lines, fences.characterMask, endOfStream)
    val inlineHeld = findUnclosedInlineConstruct(source, fences.characterMask)
    val unicodeHeldStart = (unicodeIssue as? UnicodeIssue.Incomplete)?.index
    val literalizableHeldStart = listOfNotNull(
        fences.heldStart,
        inlineHeld?.takeIf(InlineHeld::literalizable)?.start,
    ).minOrNull()
    val nonLiteralizableHeldStart = listOfNotNull(
        tables.heldStart,
        inlineHeld?.takeUnless(InlineHeld::literalizable)?.start,
        unicodeHeldStart,
    ).minOrNull()
    val heldStart = listOfNotNull(
        literalizableHeldStart,
        nonLiteralizableHeldStart,
    ).minOrNull()
    val literalizableHeldTail = heldStart != null &&
        literalizableHeldStart == heldStart &&
        (nonLiteralizableHeldStart == null || heldStart < nonLiteralizableHeldStart)
    val visibleEnd = heldStart ?: source.length
    val stableEnd = (
        listOf(
            findLastBlankLineEnd(source, visibleEnd),
            fences.stableBoundaries.filter { it <= visibleEnd }.maxOrNull() ?: 0,
            tables.stableBoundaries.filter { it <= visibleEnd }.maxOrNull() ?: 0,
        ).maxOrNull() ?: 0
        ).coerceAtMost(visibleEnd)
    return MarkdownAnalysis(
        stableEnd = stableEnd,
        heldStart = heldStart,
        literalizableHeldTail = literalizableHeldTail,
        invalid = false,
    )
}

private fun CharSequence.markdownLines(): List<MarkdownLine> {
    if (isEmpty()) return emptyList()
    val lines = mutableListOf<MarkdownLine>()
    var lineStart = 0
    while (lineStart < length) {
        val newline = indexOf('\n', lineStart)
        if (newline < 0) {
            lines += MarkdownLine(
                start = lineStart,
                contentEnd = length,
                end = length,
                terminated = false,
            )
            break
        }
        val contentEnd = if (newline > lineStart && this[newline - 1] == '\r') {
            newline - 1
        } else {
            newline
        }
        lines += MarkdownLine(
            start = lineStart,
            contentEnd = contentEnd,
            end = newline + 1,
            terminated = true,
        )
        lineStart = newline + 1
    }
    return lines
}

private fun analyzeFences(
    source: CharSequence,
    lines: List<MarkdownLine>,
    endOfStream: Boolean,
): FenceAnalysis {
    val mask = BooleanArray(source.length)
    val stableBoundaries = mutableListOf<Int>()
    var open: FenceDelimiter? = null
    var openStart: Int? = null

    lines.forEachIndexed { lineIndex, line ->
        val content = line.content(source)
        val current = open
        if (current == null) {
            val opening = content.openingFence()
            if (opening != null) {
                open = opening
                openStart = line.start
                mask.mark(line.start, line.end)
            }
            return@forEachIndexed
        }

        mask.mark(line.start, line.end)
        if (content.closes(current)) {
            val closeIsEstablished = line.terminated || (endOfStream && lineIndex == lines.lastIndex)
            if (closeIsEstablished) {
                stableBoundaries += line.end
                open = null
                openStart = null
            }
        }
    }
    return FenceAnalysis(
        characterMask = mask,
        heldStart = openStart,
        stableBoundaries = stableBoundaries,
    )
}

private data class FenceDelimiter(
    val marker: Char,
    val length: Int,
)

private fun String.openingFence(): FenceDelimiter? {
    val leadingSpaces = takeWhile { it == ' ' }.length
    if (leadingSpaces > 3 || leadingSpaces == length) return null
    val marker = this[leadingSpaces]
    if (marker != '`' && marker != '~') return null
    val run = markerRunLength(leadingSpaces, marker)
    if (run < 3) return null
    if (marker == '`' && substring(leadingSpaces + run).contains('`')) return null
    return FenceDelimiter(marker, run)
}

private fun String.closes(delimiter: FenceDelimiter): Boolean {
    val leadingSpaces = takeWhile { it == ' ' }.length
    if (leadingSpaces > 3 || getOrNull(leadingSpaces) != delimiter.marker) return false
    val run = markerRunLength(leadingSpaces, delimiter.marker)
    return run >= delimiter.length && substring(leadingSpaces + run).all { it == ' ' || it == '\t' }
}

private fun String.markerRunLength(start: Int, marker: Char): Int {
    var end = start
    while (getOrNull(end) == marker) end += 1
    return end - start
}

private fun BooleanArray.mark(start: Int, end: Int) {
    for (index in start until minOf(end, size)) this[index] = true
}

private fun analyzeTables(
    source: CharSequence,
    lines: List<MarkdownLine>,
    fenceMask: BooleanArray,
    endOfStream: Boolean,
): TableAnalysis {
    val stableBoundaries = mutableListOf<Int>()
    var heldStart: Int? = null
    var index = 0
    while (index < lines.size) {
        val header = lines[index]
        if (fenceMask.getOrElse(header.start) { false } || !header.content(source).isTableRow()) {
            index += 1
            continue
        }
        val separatorIndex = index + 1
        if (separatorIndex >= lines.size) {
            val trimmedHeader = header.content(source).trim()
            val explicitPipeHeader =
                trimmedHeader.startsWith('|') && trimmedHeader.endsWith('|')
            if (!endOfStream || explicitPipeHeader) {
                heldStart = minOfNullable(heldStart, header.start)
            }
            break
        }
        val separator = lines[separatorIndex]
        if (!separator.terminated && !endOfStream &&
            !separator.content(source).isTableSeparator()
        ) {
            heldStart = minOfNullable(heldStart, header.start)
            break
        }
        if (fenceMask.getOrElse(separator.start) { false } ||
            !separator.content(source).isTableSeparator()
        ) {
            index += 1
            continue
        }

        var next = separatorIndex + 1
        while (next < lines.size &&
            !fenceMask.getOrElse(lines[next].start) { false } &&
            lines[next].content(source).isTableRow()
        ) {
            next += 1
        }
        if (next >= lines.size) {
            if (!endOfStream) heldStart = minOfNullable(heldStart, header.start)
            break
        }

        val boundaryLine = lines[next]
        if (!boundaryLine.terminated && !endOfStream) {
            heldStart = minOfNullable(heldStart, header.start)
            break
        }
        stableBoundaries += lines[next - 1].end
        index = next
    }
    return TableAnalysis(
        heldStart = heldStart,
        stableBoundaries = stableBoundaries,
    )
}

private fun String.isTableRow(): Boolean {
    val trimmed = trim()
    if (trimmed.isEmpty() || !trimmed.containsUnescapedPipe()) return false
    return trimmed.tableCells().size >= 2
}

private fun String.isTableSeparator(): Boolean {
    val cells = trim().tableCells()
    return cells.size >= 2 && cells.all { cell ->
        cell.trim().matches(Regex(":?-{3,}:?"))
    }
}

private fun String.containsUnescapedPipe(): Boolean =
    indices.any { index -> this[index] == '|' && !isEscaped(index) }

private fun String.tableCells(): List<String> {
    val value = trim().removePrefix("|").removeSuffix("|")
    val cells = mutableListOf<String>()
    var cellStart = 0
    value.indices.forEach { index ->
        if (value[index] == '|' && !value.isEscaped(index)) {
            cells += value.substring(cellStart, index)
            cellStart = index + 1
        }
    }
    cells += value.substring(cellStart)
    return cells
}

private fun String.isEscaped(index: Int): Boolean {
    var slashes = 0
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') {
        slashes += 1
        cursor -= 1
    }
    return slashes % 2 == 1
}

private data class InlineHeld(
    val start: Int,
    val literalizable: Boolean,
)

private fun findUnclosedInlineConstruct(
    source: CharSequence,
    fenceMask: BooleanArray,
): InlineHeld? {
    var index = 0
    while (index < source.length) {
        if (fenceMask.getOrElse(index) { false }) {
            index += 1
            continue
        }
        when {
            source[index] == '`' && !source.isEscaped(index) -> {
                val delimiterLength = source.markerRunLength(index, '`')
                val closing = source.findDelimiter(
                    start = index + delimiterLength,
                    marker = '`',
                    length = delimiterLength,
                    fenceMask = fenceMask,
                )
                if (closing < 0) return InlineHeld(index, literalizable = true)
                index = closing + delimiterLength
            }

            source[index] == '$' && !source.isEscaped(index) -> {
                val delimiterLength = minOf(2, source.markerRunLength(index, '$'))
                val closing = source.findDelimiter(
                    start = index + delimiterLength,
                    marker = '$',
                    length = delimiterLength,
                    fenceMask = fenceMask,
                )
                if (closing < 0) {
                    return InlineHeld(
                        start = index,
                        literalizable = delimiterLength == 1,
                    )
                }
                index = closing + delimiterLength
            }

            else -> index += 1
        }
    }
    return null
}

private fun CharSequence.findDelimiter(
    start: Int,
    marker: Char,
    length: Int,
    fenceMask: BooleanArray,
): Int {
    var index = start
    while (index < this.length) {
        if (!fenceMask.getOrElse(index) { false } &&
            this[index] == marker &&
            !isEscaped(index)
        ) {
            val run = markerRunLength(index, marker)
            if (run >= length && (length > 1 || run == 1)) return index
            index += run
        } else {
            index += 1
        }
    }
    return -1
}

private fun CharSequence.markerRunLength(start: Int, marker: Char): Int {
    var end = start
    while (getOrNull(end) == marker) end += 1
    return end - start
}

private fun CharSequence.isEscaped(index: Int): Boolean {
    var slashes = 0
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') {
        slashes += 1
        cursor -= 1
    }
    return slashes % 2 == 1
}

private fun findLastBlankLineEnd(source: CharSequence, limit: Int): Int {
    var latest = 0
    var index = 0
    while (index < limit) {
        if (source[index] != '\n') {
            index += 1
            continue
        }
        var next = index + 1
        while (next < limit && (source[next] == ' ' || source[next] == '\t')) next += 1
        if (next < limit && source[next] == '\n') {
            latest = next + 1
        } else if (next + 1 < limit && source[next] == '\r' && source[next + 1] == '\n') {
            latest = next + 2
        }
        index += 1
    }
    return latest
}

private sealed interface UnicodeIssue {
    data object None : UnicodeIssue
    data class Incomplete(val index: Int) : UnicodeIssue
    data object Invalid : UnicodeIssue
}

private fun CharSequence.findUnicodeIssue(): UnicodeIssue {
    var index = 0
    while (index < length) {
        val char = this[index]
        val allowedControl = char == '\n' || char == '\r' || char == '\t'
        if ((char.isISOControl() && !allowedControl) ||
            char == '\u061C' ||
            char == '\u200E' ||
            char == '\u200F' ||
            char in '\u202A'..'\u202E' ||
            char in '\u2066'..'\u2069'
        ) {
            return UnicodeIssue.Invalid
        }
        when {
            char.isLowSurrogate() -> return UnicodeIssue.Invalid
            char.isHighSurrogate() && index + 1 >= length -> return UnicodeIssue.Incomplete(index)
            char.isHighSurrogate() && !this[index + 1].isLowSurrogate() -> return UnicodeIssue.Invalid
            char.isHighSurrogate() -> index += 2
            else -> index += 1
        }
    }
    return UnicodeIssue.None
}

private fun minOfNullable(first: Int?, second: Int): Int =
    if (first == null) second else minOf(first, second)

private fun String.toIncompleteMarkdownLiteral(): String =
    replace('*', '＊')
        .replace('`', '｀')
        .replace('$', '＄')
        .replace("![", "！[")
        .toTutorPreviewLiteral()
