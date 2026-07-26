package com.tingyun.smartmistakebook.core.model

/**
 * Identity of one short-lived Tutor preview stream.
 *
 * Every version participates in equality so UI owners can ignore output from a request that was
 * superseded by navigation, a newer turn, or a mode change.
 */
data class TutorStreamIdentity(
    val requestId: String,
    val ownerVersion: Long,
    val turnVersion: Long,
    val modeVersion: Long,
) {
    init {
        requestId.requireSafeModelText(
            "Tutor stream request id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        require(ownerVersion >= 0) { "Tutor stream owner version must not be negative" }
        require(turnVersion >= 0) { "Tutor stream turn version must not be negative" }
        require(modeVersion >= 0) { "Tutor stream mode version must not be negative" }
    }
}

/**
 * An immutable append-only chunk chain.
 *
 * Preview events share prior nodes instead of copying the complete response. Materialization is a
 * compatibility operation for terminal consumers and tests; streaming UI can consume only the
 * chunks appended since a chain it already parsed.
 */
class TutorMarkdownChunkChain private constructor(
    private val previous: TutorMarkdownChunkChain?,
    private val chunk: String,
    val length: Int,
    val hasNonWhitespace: Boolean,
) {
    @Volatile
    private var cachedText: String? = if (length == 0) "" else null

    internal fun append(value: String): TutorMarkdownChunkChain =
        if (value.isEmpty()) this else TutorMarkdownChunkChain(
            previous = this,
            chunk = value,
            length = length + value.length,
            hasNonWhitespace = hasNonWhitespace || value.any { !it.isWhitespace() },
        )

    internal fun prefix(endExclusive: Int): TutorMarkdownChunkChain {
        require(endExclusive in 0..length)
        if (endExclusive == length) return this
        if (endExclusive == 0) return EMPTY

        var current = this
        while (current.length > endExclusive) {
            val prior = current.previous ?: return EMPTY
            if (prior.length < endExclusive) {
                return prior.append(
                    current.chunk.substring(0, endExclusive - prior.length),
                )
            }
            current = prior
        }
        return current
    }

    /**
     * Returns only chunks appended after [ancestor], or `null` when this chain is a different
     * branch. The latter occurs on provisional rollback and tells consumers to reuse an older
     * cached prefix or rebuild that provisional branch.
     */
    fun appendedChunksSince(ancestor: TutorMarkdownChunkChain): List<String>? {
        if (ancestor === this) return emptyList()
        if (ancestor.length >= length) return null

        val reversed = mutableListOf<String>()
        var current: TutorMarkdownChunkChain? = this
        while (current != null && current !== ancestor) {
            if (current.length <= ancestor.length) return null
            reversed += current.chunk
            current = current.previous
        }
        if (current !== ancestor) return null
        reversed.reverse()
        return reversed
    }

    fun materialize(): String {
        cachedText?.let { return it }
        val chunks = mutableListOf<String>()
        var current: TutorMarkdownChunkChain? = this
        while (current != null && current.length > 0) {
            chunks += current.chunk
            current = current.previous
        }
        val value = buildString(length) {
            chunks.asReversed().forEach(::append)
        }
        cachedText = value
        return value
    }

    companion object {
        val EMPTY = TutorMarkdownChunkChain(
            previous = null,
            chunk = "",
            length = 0,
            hasNonWhitespace = false,
        )

        internal fun of(value: String): TutorMarkdownChunkChain = EMPTY.append(value)
    }
}

/**
 * A display-safe snapshot. Stable chunks form an append-only chain; provisional chunks may branch
 * back to an earlier prefix, while [provisionalTail] remains a small replaceable suffix.
 */
class TutorMarkdownSnapshot private constructor(
    val stableContent: TutorMarkdownChunkChain,
    val provisionalContent: TutorMarkdownChunkChain,
    val provisionalTail: String,
    trustedIncrementalContent: Boolean,
) {
    constructor(
        stableMarkdown: String,
        provisionalMarkdown: String,
    ) : this(
        stableContent = TutorMarkdownChunkChain.of(stableMarkdown),
        provisionalContent = TutorMarkdownChunkChain.of(provisionalMarkdown),
        provisionalTail = "",
        trustedIncrementalContent = false,
    )

    val stableMarkdown: String
        get() = stableContent.materialize()

    val provisionalMarkdown: String
        get() = provisionalContent.materialize() + provisionalTail

    val visibleMarkdown: String
        get() = stableMarkdown + provisionalMarkdown

    val isEmpty: Boolean
        get() = stableContent.length == 0 &&
            provisionalContent.length == 0 &&
            provisionalTail.isEmpty()

    val hasVisibleNonWhitespace: Boolean
        get() = stableContent.hasNonWhitespace ||
            provisionalContent.hasNonWhitespace ||
            provisionalTail.any { !it.isWhitespace() }

    init {
        val length = stableContent.length + provisionalContent.length + provisionalTail.length
        require(length <= TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS) {
            "Tutor stream preview exceeds its text budget"
        }
        require(provisionalTail.length <= MAX_PROVISIONAL_TAIL_CHARS) {
            "Tutor stream provisional tail exceeds its bounded budget"
        }
        if (!trustedIncrementalContent) validateMaterializedContent()
    }

    private fun validateMaterializedContent() {
        val visible = visibleMarkdown
        require(visible.none(Char::isUnsafeTutorPreviewCharacter)) {
            "Tutor stream preview contains unsafe control characters"
        }
        require(!visible.requiresTutorPlainTextFallback()) {
            "Tutor stream preview contains active markup"
        }
        val normalized = visible.lowercase()
        require("<script" !in normalized && "javascript:" !in normalized) {
            "Tutor stream preview contains active content"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TutorMarkdownSnapshot) return false
        if (
            stableContent.length != other.stableContent.length ||
            provisionalContent.length != other.provisionalContent.length ||
            provisionalTail != other.provisionalTail
        ) {
            return false
        }
        val stableMatches = stableContent === other.stableContent ||
            stableMarkdown == other.stableMarkdown
        val provisionalMatches = provisionalContent === other.provisionalContent ||
            provisionalContent.materialize() == other.provisionalContent.materialize()
        return stableMatches && provisionalMatches
    }

    override fun hashCode(): Int {
        var result = stableMarkdown.hashCode()
        result = 31 * result + provisionalMarkdown.hashCode()
        return result
    }

    override fun toString(): String =
        "TutorMarkdownSnapshot(stableMarkdown=$stableMarkdown, " +
            "provisionalMarkdown=$provisionalMarkdown)"

    companion object {
        internal const val MAX_PROVISIONAL_TAIL_CHARS = 3

        val EMPTY = TutorMarkdownSnapshot(
            stableContent = TutorMarkdownChunkChain.EMPTY,
            provisionalContent = TutorMarkdownChunkChain.EMPTY,
            provisionalTail = "",
            trustedIncrementalContent = true,
        )

        internal fun incremental(
            stableContent: TutorMarkdownChunkChain,
            provisionalContent: TutorMarkdownChunkChain,
            provisionalTail: String,
        ): TutorMarkdownSnapshot = TutorMarkdownSnapshot(
            stableContent = stableContent,
            provisionalContent = provisionalContent,
            provisionalTail = provisionalTail,
            trustedIncrementalContent = true,
        )

        /**
         * Preserves a validated durable result when its Markdown is not stream-complete.
         *
         * Incomplete delimiters are rendered inert so a durable success never becomes an active
         * failure or silently loses otherwise valid text during stream-state reconstruction.
         */
        fun completedLiteral(markdown: String): TutorMarkdownSnapshot =
            TutorMarkdownSnapshot(
                stableMarkdown = markdown.toIncompleteMarkdownLiteral(),
                provisionalMarkdown = "",
            )
    }
}

/**
 * Ephemeral UI events. [Started] is emitted only after the first durable task snapshot exists.
 * Durable completion and detailed failures continue to use the existing model task snapshots; this
 * contract intentionally contains neither raw provider data nor Throwables.
 */
sealed interface TutorStreamEvent {
    val identity: TutorStreamIdentity

    data class Started(
        override val identity: TutorStreamIdentity,
    ) : TutorStreamEvent

    data class Preview(
        override val identity: TutorStreamIdentity,
        val snapshot: TutorMarkdownSnapshot,
    ) : TutorStreamEvent

    data class Completed(
        override val identity: TutorStreamIdentity,
        val snapshot: TutorMarkdownSnapshot,
    ) : TutorStreamEvent {
        init {
            require(snapshot.provisionalMarkdown.isEmpty()) {
                "A completed Tutor stream cannot contain provisional Markdown"
            }
        }
    }

    data class Failed(
        override val identity: TutorStreamIdentity,
        val snapshot: TutorMarkdownSnapshot,
        val retryable: Boolean,
    ) : TutorStreamEvent
}

private fun Char.isUnsafeTutorPreviewCharacter(): Boolean {
    val allowedControl = this == '\n' || this == '\r' || this == '\t'
    return (isISOControl() && !allowedControl) ||
        this == '\u061C' ||
        this == '\u200E' ||
        this == '\u200F' ||
        this in '\u202A'..'\u202E' ||
        this in '\u2066'..'\u2069'
}

private val tutorPreviewHtml = Regex("<(?:/?[A-Za-z][^>]*|!--[^>]*--)>?")
private val tutorPreviewActiveScheme = Regex("(?i)(?:javascript|data)\\s*:")
private val tutorPreviewRemoteImage =
    Regex("!\\[[^]\\r\\n]{0,256}]\\(\\s*https?://", RegexOption.IGNORE_CASE)

internal fun String.requiresTutorPlainTextFallback(): Boolean =
    SafeInlineMarkdown.requiresPlainTextFallback(this) ||
        tutorPreviewHtml.containsMatchIn(this) ||
        tutorPreviewActiveScheme.containsMatchIn(this) ||
        tutorPreviewRemoteImage.containsMatchIn(this)

internal fun String.toTutorPreviewLiteral(): String {
    if (!requiresTutorPlainTextFallback()) return this
    val escapedMarkers = replace('*', '＊')
        .replace('`', '｀')
        .replace('$', '＄')
        .replace("![", "！[")
    val withoutHtml = tutorPreviewHtml.replace(escapedMarkers) { match ->
        match.value.replace('<', '＜').replace('>', '＞')
    }
    return tutorPreviewActiveScheme.replace(withoutHtml) { match ->
        match.value.replace(':', '：')
    }
}
