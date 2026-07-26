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
class StreamingMarkdownAssembler(
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val source = StringBuilder()
    private val analyzer = IncrementalMarkdownAnalyzer(source)
    private var lastSnapshot = TutorMarkdownSnapshot.EMPTY
    private var lastVisibleSourceEnd = 0
    private var nextMaterializationSourceEnd = 1
    private var lastMaterializationNanos = clockNanos()
    private var rejected = false
    private var completed = false

    internal val analysisInspectionCount: Long
        get() = analyzer.inspectionCount
    internal var snapshotMaterializationCharacterCount: Long = 0
        private set

    fun append(fragment: String): TutorMarkdownSnapshot {
        if (fragment.isEmpty() || rejected || completed) return lastSnapshot
        if (source.length + fragment.length > TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS) {
            rejected = true
            return lastSnapshot
        }
        val fragmentStart = source.length
        source.append(fragment)

        val analysis = analyzer.append(fragmentStart, fragment.length)
        if (analysis.invalid) {
            rejected = true
            return lastSnapshot
        }
        val visibleEnd = analysis.heldStart ?: source.length
        val stableEnd = analysis.stableEnd.coerceAtMost(visibleEnd)
        val mustRollBack = visibleEnd < lastVisibleSourceEnd
        val nowNanos = clockNanos()
        val coalescingWindowElapsed =
            nowNanos - lastMaterializationNanos >= SNAPSHOT_COALESCE_NANOS
        if (
            visibleEnd == 0 && !mustRollBack ||
            (
                !mustRollBack &&
                    visibleEnd > IMMEDIATE_PREVIEW_CHARS &&
                    visibleEnd < nextMaterializationSourceEnd &&
                    !coalescingWindowElapsed
                )
        ) {
            return lastSnapshot
        }
        val visibleMarkdown = source.substring(0, visibleEnd)
            .toTutorPreviewLiteralPreserving(lastSnapshot.stableMarkdown)
        snapshotMaterializationCharacterCount += visibleEnd
        val safeStableEnd = stableEnd.coerceAtMost(visibleMarkdown.length)
        val next = TutorMarkdownSnapshot(
            stableMarkdown = visibleMarkdown.substring(0, safeStableEnd)
                .toTutorStableLiteral(),
            provisionalMarkdown = visibleMarkdown.substring(safeStableEnd),
        )
        if (!next.stableMarkdown.startsWith(lastSnapshot.stableMarkdown)) {
            rejected = true
            return lastSnapshot
        }
        lastSnapshot = next
        lastVisibleSourceEnd = visibleEnd
        nextMaterializationSourceEnd = if (visibleEnd < IMMEDIATE_PREVIEW_CHARS) {
            visibleEnd + 1
        } else {
            visibleEnd + SNAPSHOT_CHARACTER_INTERVAL
        }
        lastMaterializationNanos = nowNanos
        return next
    }

    fun complete(): StreamingMarkdownCompletion {
        if (completed) return StreamingMarkdownCompletion.Accepted(lastSnapshot)
        if (rejected) return rejectedCompletion()

        val analysis = analyzer.endOfStreamAnalysis()
        if (analysis.invalid) {
            rejected = true
            return rejectedCompletion()
        }
        val completedMarkdown = when {
            analysis.heldStart == null -> source.toString()
            analysis.literalizableHeldTail -> {
                source.substring(0, analysis.heldStart) +
                    source.substring(analysis.heldStart).toIncompleteMarkdownLiteral()
            }
            else -> {
                rejected = true
                return rejectedCompletion()
            }
        }.toTutorPreviewLiteralPreserving(lastSnapshot.stableMarkdown)
            .toTutorStableLiteral()
        snapshotMaterializationCharacterCount += source.length
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

    private companion object {
        const val IMMEDIATE_PREVIEW_CHARS = 64
        const val SNAPSHOT_CHARACTER_INTERVAL = 128
        const val SNAPSHOT_COALESCE_NANOS = 64_000_000L
    }
}

private class IncrementalMarkdownAnalyzer(
    private val source: CharSequence,
) {
    var inspectionCount: Long = 0
        private set

    private val inline = IncrementalInlineAnalyzer()
    private val tables = IncrementalTableAnalyzer()
    private var lineStart = 0
    private var line = IncrementalMarkdownLine()
    private var lineInlineCheckpoint = inline.checkpoint()
    private var openFence: FenceDelimiter? = null
    private var openFenceStart: Int? = null
    private var pendingHighSurrogateStart: Int? = null
    private var lastBlankLineEnd = 0
    private var lastFenceEnd = 0
    private var invalid = false

    fun append(fragmentStart: Int, fragmentLength: Int): MarkdownAnalysis {
        val fragmentEnd = fragmentStart + fragmentLength
        var index = fragmentStart
        while (index < fragmentEnd && !invalid) {
            val character = source[index]
            inspectionCount += 1
            if (!acceptUnicode(character, index)) {
                invalid = true
                break
            }
            if (character == '\n') {
                finishLine(index + 1)
            } else {
                line.append(character)
                if (openFence == null) {
                    inline.append(character, index)
                }
            }
            index += 1
        }
        return analysis(endOfStream = false)
    }

    fun endOfStreamAnalysis(): MarkdownAnalysis = analysis(endOfStream = true)

    private fun acceptUnicode(character: Char, index: Int): Boolean {
        val pendingHighSurrogate = pendingHighSurrogateStart
        if (pendingHighSurrogate != null) {
            if (!character.isLowSurrogate()) return false
            pendingHighSurrogateStart = null
            return true
        }
        val allowedControl = character == '\n' || character == '\r' || character == '\t'
        if ((character.isISOControl() && !allowedControl) ||
            character == '\u061C' ||
            character == '\u200E' ||
            character == '\u200F' ||
            character in '\u202A'..'\u202E' ||
            character in '\u2066'..'\u2069' ||
            character.isLowSurrogate()
        ) {
            return false
        }
        if (character.isHighSurrogate()) pendingHighSurrogateStart = index
        return true
    }

    private fun finishLine(end: Int) {
        val normalized = line.normalized()
        val delimiter = openFence
        val fenced = when {
            delimiter != null -> {
                if (normalized.closes(delimiter)) {
                    openFence = null
                    openFenceStart = null
                    lastFenceEnd = maxOf(lastFenceEnd, end)
                }
                true
            }

            else -> {
                val opening = normalized.openingFence()
                if (opening != null) {
                    openFence = opening
                    openFenceStart = lineStart
                    inline.restore(lineInlineCheckpoint)
                    true
                } else {
                    inline.finishLine()
                    false
                }
            }
        }
        if (lineStart > 0 && normalized.isBlankMarkdownLine) {
            lastBlankLineEnd = end
        }
        val contentEnd = if (end - 2 >= lineStart && source[end - 2] == '\r') {
            end - 2
        } else {
            end - 1
        }
        val content = source.subSequence(lineStart, contentEnd).toString()
        inspectionCount += content.length
        tables.append(
            IncrementalTableLine(
                start = lineStart,
                end = end,
                isRow = normalized.isTableRow,
                isSeparator = content.isTableSeparator(),
                isExplicitPipeRow = normalized.isExplicitPipeRow,
                fenced = fenced,
            ),
        )
        lineStart = end
        line = IncrementalMarkdownLine()
        lineInlineCheckpoint = inline.checkpoint()
    }

    private fun analysis(endOfStream: Boolean): MarkdownAnalysis {
        if (invalid) {
            return MarkdownAnalysis(
                stableEnd = 0,
                heldStart = null,
                literalizableHeldTail = false,
                invalid = true,
            )
        }
        val activeLine = line.takeIf { lineStart < source.length }?.normalized()
        val activeOpening = if (openFence == null) activeLine?.openingFence() else null
        val closesFenceAtEnd = endOfStream &&
            openFence != null &&
            activeLine?.closes(openFence!!) == true
        val fenceHeldStart = when {
            closesFenceAtEnd -> null
            openFenceStart != null -> openFenceStart
            activeOpening != null -> lineStart
            else -> null
        }
        val effectiveInline = if (activeOpening != null) {
            lineInlineCheckpoint.open
        } else {
            inline.currentOpen()
        }
        val activeFenced = openFence != null || activeOpening != null
        val activeTableLine = activeLine?.let {
            IncrementalActiveTableLine(
                start = lineStart,
                isRow = it.isTableRow,
                isExplicitPipeRow = it.isExplicitPipeRow,
                fenced = activeFenced,
                isSeparator = if (endOfStream && tables.needsActiveSeparator) {
                    val contentEnd = if (source.lastOrNull() == '\r') {
                        source.length - 1
                    } else {
                        source.length
                    }
                    val content = source.subSequence(lineStart, contentEnd).toString()
                    inspectionCount += content.length
                    content.isTableSeparator()
                } else {
                    false
                },
            )
        }
        val tableHeldStart = if (endOfStream) {
            tables.heldStartAtEnd(activeTableLine)
        } else {
            tables.heldStartWhileStreaming(activeTableLine)
        }
        val inlineHeldStart = effectiveInline?.start
        val unicodeHeldStart = pendingHighSurrogateStart
        val literalizableHeldStart = listOfNotNull(
            fenceHeldStart,
            inlineHeldStart?.takeIf { effectiveInline.literalizable },
        ).minOrNull()
        val nonLiteralizableHeldStart = listOfNotNull(
            tableHeldStart,
            inlineHeldStart?.takeUnless { effectiveInline.literalizable },
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
        val stableEnd = maxOf(
            lastBlankLineEnd,
            maxOf(lastFenceEnd, if (closesFenceAtEnd) source.length else 0),
            tables.lastStableBoundary,
        ).coerceAtMost(visibleEnd)
        return MarkdownAnalysis(
            stableEnd = stableEnd,
            heldStart = heldStart,
            literalizableHeldTail = literalizableHeldTail,
            invalid = false,
        )
    }
}

private data class IncrementalInlineOpen(
    val marker: Char,
    val delimiterLength: Int,
    val start: Int,
) {
    val literalizable: Boolean
        get() = marker == '`' || delimiterLength == 1
}

private data class IncrementalMarkerRun(
    val marker: Char,
    val start: Int,
    val length: Int,
    val baseOpen: IncrementalInlineOpen?,
)

private data class IncrementalInlineCheckpoint(
    val open: IncrementalInlineOpen?,
    val trailingBackslashes: Int,
)

private class IncrementalInlineAnalyzer {
    private var open: IncrementalInlineOpen? = null
    private var pendingRun: IncrementalMarkerRun? = null
    private var trailingBackslashes = 0

    fun append(character: Char, index: Int) {
        val pending = pendingRun
        if (pending != null && pending.marker == character) {
            pendingRun = pending.copy(length = pending.length + 1)
            trailingBackslashes = 0
            return
        }
        commitPendingRun()
        if ((character == '`' || character == '$') && trailingBackslashes % 2 == 0) {
            pendingRun = IncrementalMarkerRun(
                marker = character,
                start = index,
                length = 1,
                baseOpen = open,
            )
            trailingBackslashes = 0
        } else {
            trailingBackslashes = if (character == '\\') trailingBackslashes + 1 else 0
        }
    }

    fun checkpoint(): IncrementalInlineCheckpoint {
        commitPendingRun()
        return IncrementalInlineCheckpoint(open, trailingBackslashes)
    }

    fun restore(checkpoint: IncrementalInlineCheckpoint) {
        open = checkpoint.open
        pendingRun = null
        trailingBackslashes = checkpoint.trailingBackslashes
    }

    fun currentOpen(): IncrementalInlineOpen? =
        pendingRun?.resolvedOpen() ?: open

    fun finishLine() {
        pendingRun = null
        open = null
        trailingBackslashes = 0
    }

    private fun commitPendingRun() {
        val pending = pendingRun ?: return
        open = pending.resolvedOpen()
        pendingRun = null
    }
}

private fun IncrementalMarkerRun.resolvedOpen(): IncrementalInlineOpen? {
    val current = baseOpen
    if (current != null && current.marker != marker) return current
    if (marker == '`') {
        if (current == null) {
            return IncrementalInlineOpen(marker, length, start)
        }
        if (current.delimiterLength == 1 && length > 1) return current
        if (length < current.delimiterLength) return current
        val remainder = length - current.delimiterLength
        return if (remainder == 0) {
            null
        } else {
            IncrementalInlineOpen(marker, remainder, start + current.delimiterLength)
        }
    }
    if (current?.delimiterLength == 1) {
        return if (length == 1) null else current
    }
    val remaining = if (current == null) length else {
        if (length < 2) return current
        length - 2
    }
    val remainingStart = if (current == null) start else start + 2
    return when (remaining % 4) {
        0 -> null
        1 -> IncrementalInlineOpen('$', 1, remainingStart + remaining - 1)
        2 -> IncrementalInlineOpen('$', 2, remainingStart + remaining - 2)
        else -> IncrementalInlineOpen('$', 2, remainingStart + remaining - 3)
    }
}

private data class IncrementalLineMetrics(
    var length: Int = 0,
    var leadingSpaces: Int = 0,
    var stillLeadingSpaces: Boolean = true,
    var marker: Char? = null,
    var markerRunLength: Int = 0,
    var markerRunEnded: Boolean = false,
    var backtickAfterRun: Boolean = false,
    var closingRemainderIsWhitespace: Boolean = true,
    var allSpacesOrTabs: Boolean = true,
    var firstTrimmedCharacter: Char? = null,
    var firstTrimmedCharacterIsUnescapedPipe: Boolean = false,
    var lastTrimmedCharacter: Char? = null,
    var lastTrimmedCharacterIsUnescapedPipe: Boolean = false,
    var unescapedPipeCount: Int = 0,
    var trailingBackslashes: Int = 0,
) {
    fun append(character: Char) {
        val escaped = trailingBackslashes % 2 == 1
        length += 1
        allSpacesOrTabs = allSpacesOrTabs && (character == ' ' || character == '\t')
        if (stillLeadingSpaces && character == ' ') {
            leadingSpaces += 1
        } else if (stillLeadingSpaces) {
            stillLeadingSpaces = false
            if (character == '`' || character == '~') {
                marker = character
                markerRunLength = 1
            }
        } else if (marker != null && !markerRunEnded && character == marker) {
            markerRunLength += 1
        } else if (marker != null) {
            markerRunEnded = true
            closingRemainderIsWhitespace =
                closingRemainderIsWhitespace && (character == ' ' || character == '\t')
            if (marker == '`' && character == '`') backtickAfterRun = true
        }
        val unescapedPipe = character == '|' && !escaped
        if (unescapedPipe) unescapedPipeCount += 1
        if (!character.isWhitespace()) {
            if (firstTrimmedCharacter == null) {
                firstTrimmedCharacter = character
                firstTrimmedCharacterIsUnescapedPipe = unescapedPipe
            }
            lastTrimmedCharacter = character
            lastTrimmedCharacterIsUnescapedPipe = unescapedPipe
        }
        trailingBackslashes = if (character == '\\') trailingBackslashes + 1 else 0
    }

    fun openingFence(): FenceDelimiter? {
        val fenceMarker = marker ?: return null
        if (leadingSpaces > 3 || markerRunLength < 3) return null
        if (fenceMarker == '`' && backtickAfterRun) return null
        return FenceDelimiter(fenceMarker, markerRunLength)
    }

    fun closes(delimiter: FenceDelimiter): Boolean =
        leadingSpaces <= 3 &&
            marker == delimiter.marker &&
            markerRunLength >= delimiter.length &&
            closingRemainderIsWhitespace

    val isBlankMarkdownLine: Boolean
        get() = allSpacesOrTabs

    val isExplicitPipeRow: Boolean
        get() = firstTrimmedCharacter == '|' && lastTrimmedCharacter == '|'

    val isTableRow: Boolean
        get() {
            if (firstTrimmedCharacter == null) return false
            val internalPipes = unescapedPipeCount -
                if (firstTrimmedCharacterIsUnescapedPipe) 1 else 0 -
                if (lastTrimmedCharacterIsUnescapedPipe) 1 else 0
            return internalPipes >= 1
        }
}

private class IncrementalMarkdownLine {
    private var metrics = IncrementalLineMetrics()
    private var beforeTrailingCarriageReturn: IncrementalLineMetrics? = null
    private var lastCharacter: Char? = null

    fun append(character: Char) {
        beforeTrailingCarriageReturn =
            if (character == '\r') metrics.copy() else null
        metrics.append(character)
        lastCharacter = character
    }

    fun normalized(): IncrementalLineMetrics =
        if (lastCharacter == '\r') {
            requireNotNull(beforeTrailingCarriageReturn)
        } else {
            metrics
        }
}

private data class IncrementalTableLine(
    val start: Int,
    val end: Int,
    val isRow: Boolean,
    val isSeparator: Boolean,
    val isExplicitPipeRow: Boolean,
    val fenced: Boolean,
)

private data class IncrementalActiveTableLine(
    val start: Int,
    val isRow: Boolean,
    val isSeparator: Boolean,
    val isExplicitPipeRow: Boolean,
    val fenced: Boolean,
)

private data class IncrementalTableHeader(
    val start: Int,
    val isExplicitPipeRow: Boolean,
)

private class IncrementalTableAnalyzer {
    private var pendingHeader: IncrementalTableHeader? = null
    private var openTableStart: Int? = null
    private var lastTableLineEnd = 0

    var lastStableBoundary: Int = 0
        private set

    val needsActiveSeparator: Boolean
        get() = pendingHeader != null

    fun append(line: IncrementalTableLine) {
        if (openTableStart != null) {
            if (!line.fenced && line.isRow) {
                lastTableLineEnd = line.end
                return
            }
            lastStableBoundary = maxOf(lastStableBoundary, lastTableLineEnd)
            openTableStart = null
            considerHeader(line)
            return
        }
        val header = pendingHeader
        if (header != null) {
            pendingHeader = null
            if (!line.fenced && line.isSeparator) {
                openTableStart = header.start
                lastTableLineEnd = line.end
                return
            }
        }
        considerHeader(line)
    }

    fun heldStartWhileStreaming(active: IncrementalActiveTableLine?): Int? {
        val existing = openTableStart ?: pendingHeader?.start
        if (existing != null) return existing
        return active
            ?.takeIf { !it.fenced && it.isRow }
            ?.start
    }

    fun heldStartAtEnd(active: IncrementalActiveTableLine?): Int? {
        if (openTableStart != null) return null
        val header = pendingHeader
        if (header != null) {
            if (active == null) {
                return header.start.takeIf { header.isExplicitPipeRow }
            }
            if (!active.fenced && active.isSeparator) return null
            return active.start.takeIf {
                !active.fenced && active.isRow && active.isExplicitPipeRow
            }
        }
        return active?.start?.takeIf {
            !active.fenced && active.isRow && active.isExplicitPipeRow
        }
    }

    private fun considerHeader(line: IncrementalTableLine) {
        if (!line.fenced && line.isRow) {
            pendingHeader = IncrementalTableHeader(
                start = line.start,
                isExplicitPipeRow = line.isExplicitPipeRow,
            )
        }
    }
}

private data class MarkdownAnalysis(
    val stableEnd: Int,
    val heldStart: Int?,
    val literalizableHeldTail: Boolean,
    val invalid: Boolean,
)

private data class FenceDelimiter(
    val marker: Char,
    val length: Int,
)

private fun String.isTableSeparator(): Boolean {
    val cells = trim().tableCells()
    return cells.size >= 2 && cells.all { cell ->
        cell.trim().matches(Regex(":?-{3,}:?"))
    }
}

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

internal fun String.toIncompleteMarkdownLiteral(): String =
    replace('*', '＊')
        .replace('`', '｀')
        .replace('$', '＄')
        .replace("![", "！[")
        .toTutorPreviewLiteral()

private val potentialHtmlTail = Regex(
    pattern = "<(?:/?[A-Za-z][^>]*|![^>]*)?$",
    option = RegexOption.DOT_MATCHES_ALL,
)

private fun String.toTutorStableLiteral(): String {
    var literal = replace("![", "！[")
    val potentialHtml = potentialHtmlTail.find(literal) ?: return literal
    literal = literal.replaceRange(
        potentialHtml.range.first,
        potentialHtml.range.first + 1,
        "＜",
    )
    return literal
}

private val activeSchemeAcrossStablePrefix = Regex("(?i)(?:javascript|data)\\s*:")

private fun String.toTutorPreviewLiteralPreserving(stablePrefix: String): String {
    require(stablePrefix.length <= length)
    var literal = stablePrefix + substring(stablePrefix.length).toTutorPreviewLiteral()
    activeSchemeAcrossStablePrefix.findAll(literal).toList().asReversed().forEach { match ->
        val colonIndex = match.range.last
        if (colonIndex >= stablePrefix.length) {
            literal = literal.replaceRange(colonIndex, colonIndex + 1, "：")
        }
    }
    return if (literal.requiresTutorPlainTextFallback()) {
        literal.toTutorPreviewLiteral()
    } else {
        literal
    }
}
