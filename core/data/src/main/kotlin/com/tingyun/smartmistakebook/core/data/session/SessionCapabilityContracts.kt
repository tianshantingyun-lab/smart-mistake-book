package com.tingyun.smartmistakebook.core.data.session

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The only learner scope accepted by a session capability.
 *
 * Session adapters are constructed for one learner and reject every command or query from a
 * different scope before touching their backing store.
 */
data class SessionScope(
    val learnerId: String,
) {
    init {
        learnerId.requireSessionIdentifier("Session learner id")
    }
}

/**
 * Stable identity for one requested session mutation.
 *
 * [payloadFingerprint] identifies the immutable command payload, while [requestVersion] prevents
 * a late request from a superseded UI/model cycle from being applied.
 */
data class SessionOperationIdentity(
    val requestId: String,
    val idempotencyKey: String,
    val requestVersion: Long,
    val payloadFingerprint: String,
) {
    init {
        requestId.requireSessionIdentifier("Session request id")
        idempotencyKey.requireSessionIdentifier("Session idempotency key")
        require(requestVersion >= 0) { "Session request version must not be negative" }
        payloadFingerprint.requireSessionFingerprint("Session payload fingerprint")
    }
}

/**
 * Compare-and-set token returned by every versioned session read.
 *
 * [sequence] remains the backing store's monotonic version when one exists. [fingerprint] also
 * protects transitional records whose legacy schema only had an update time or composite state.
 */
data class SessionVersion(
    val sequence: Long,
    val fingerprint: String,
) {
    init {
        require(sequence >= 0) { "Session version sequence must not be negative" }
        fingerprint.requireSessionFingerprint("Session version fingerprint")
    }
}

/**
 * Opaque, integrity-checked transient payload.
 *
 * The session layer can persist model/chat orchestration data without interpreting it as a
 * problem, mastery, or knowledge fact. Semantic decoding stays in the owning feature adapter.
 */
data class SessionOpaquePayload(
    val schema: String,
    val content: String,
    val contentSha256: String = content.sha256(),
) {
    init {
        schema.requireSessionIdentifier("Session payload schema")
        require(content.length <= MAX_SESSION_PAYLOAD_CHARS) {
            "Session payload exceeds the transient payload budget"
        }
        contentSha256.requireSessionFingerprint("Session payload content fingerprint")
        require(contentSha256 == content.sha256()) {
            "Session payload content fingerprint does not match its content"
        }
    }

    companion object {
        const val MAX_SESSION_PAYLOAD_CHARS = 2_000_000
    }
}

enum class SessionMutationDisposition {
    APPLIED,
    DUPLICATE,
    RELOAD_REQUIRED,
    NOT_FOUND,
    REJECTED,
}

data class SessionMutationReceipt(
    val operation: SessionOperationIdentity,
    val disposition: SessionMutationDisposition,
    val currentVersion: SessionVersion?,
    val recordedAtEpochMillis: Long,
) {
    init {
        require(recordedAtEpochMillis >= 0) {
            "Session mutation receipt time must not be negative"
        }
        require(
            disposition != SessionMutationDisposition.APPLIED || currentVersion != null,
        ) { "An applied session mutation must return the committed version" }
    }
}

internal fun SessionScope.requireBoundTo(boundScope: SessionScope) {
    require(this == boundScope) { "Session capability is outside its bound learner scope" }
}

internal fun String.requireSessionIdentifier(label: String) {
    require(
        isNotBlank() &&
            this == trim() &&
            length <= MAX_SESSION_IDENTIFIER_CHARS &&
            none(Char::isISOControl),
    ) { "$label must be a bounded opaque identifier" }
}

internal fun String.requireSessionFingerprint(label: String) {
    require(length == SHA_256_HEX_CHARS && all { it in LOWERCASE_HEX }) {
        "$label must be a lowercase SHA-256 fingerprint"
    }
}

internal fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val MAX_SESSION_IDENTIFIER_CHARS = 256
private const val SHA_256_HEX_CHARS = 64
private const val LOWERCASE_HEX = "0123456789abcdef"
