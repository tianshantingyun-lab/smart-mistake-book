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
 * A display-safe snapshot. [stableMarkdown] never changes for a given prefix; provisional text may
 * be replaced by the next coalesced update.
 */
data class TutorMarkdownSnapshot(
    val stableMarkdown: String,
    val provisionalMarkdown: String,
) {
    val visibleMarkdown: String
        get() = stableMarkdown + provisionalMarkdown

    init {
        require(visibleMarkdown.length <= TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS) {
            "Tutor stream preview exceeds its text budget"
        }
        require(visibleMarkdown.none(Char::isUnsafeTutorPreviewCharacter)) {
            "Tutor stream preview contains unsafe control characters"
        }
        require(!visibleMarkdown.requiresTutorPlainTextFallback()) {
            "Tutor stream preview contains active markup"
        }
        val normalized = visibleMarkdown.lowercase()
        require("<script" !in normalized && "javascript:" !in normalized) {
            "Tutor stream preview contains active content"
        }
    }

    companion object {
        val EMPTY = TutorMarkdownSnapshot(
            stableMarkdown = "",
            provisionalMarkdown = "",
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
