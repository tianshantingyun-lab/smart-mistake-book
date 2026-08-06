package com.tingyun.smartmistakebook.core.model

internal fun NormalizedSourceRegion.isValidModelRegion(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        left in 0.0..1.0 && top in 0.0..1.0 && right in 0.0..1.0 && bottom in 0.0..1.0 &&
        left < right && top < bottom

internal fun Char.isLowerHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

internal fun String.requireSafeModelText(
    label: String,
    maxChars: Int,
    allowLineBreaks: Boolean,
) {
    require(isNotBlank()) { "$label must not be blank" }
    require(length <= maxChars) { "$label exceeds budget" }
    require(none { it.isForbiddenModelTextCharacter(allowLineBreaks) }) {
        "$label contains unsafe control characters"
    }
}

internal fun String.requireSafeTutorStudentMessage(label: String, maxChars: Int) {
    try {
        requireSafeModelText(label, maxChars, allowLineBreaks = true)
    } catch (invalid: IllegalArgumentException) {
        throw InvalidTutorStudentMessageException(
            invalid.message ?: "$label is invalid",
        )
    }
}

internal fun Char.isForbiddenModelTextCharacter(allowLineBreaks: Boolean): Boolean {
    val allowedControl = allowLineBreaks && (this == '\n' || this == '\r' || this == '\t')
    return (isISOControl() && !allowedControl) ||
        this == '\u061C' ||
        this == '\u200E' ||
        this == '\u200F' ||
        this in '\u202A'..'\u202E' ||
        this in '\u2066'..'\u2069'
}
