package com.tingyun.smartmistakebook.core.model

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Length-prefixed canonical SHA-256 used for stable cross-module identities. */
class CanonicalSha256(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        requireCanonicalIdentityValue(domain, "Canonical hash domain")
        field("domain", domain)
        field("canonicalVersion", "1")
    }

    fun field(name: String, value: String): CanonicalSha256 = apply {
        requireCanonicalIdentityValue(name, "Canonical field name")
        append(name)
        append("PRESENT")
        append(value)
    }

    fun field(name: String, value: Long): CanonicalSha256 =
        field(name, java.lang.Long.toString(value))

    fun field(name: String, value: Int): CanonicalSha256 =
        field(name, java.lang.Integer.toString(value))

    fun field(name: String, value: Boolean): CanonicalSha256 =
        field(name, if (value) "true" else "false")

    fun nullableField(name: String, value: String?): CanonicalSha256 = apply {
        requireCanonicalIdentityValue(name, "Canonical field name")
        append(name)
        if (value == null) {
            append("NULL")
        } else {
            append("PRESENT")
            append(value)
        }
    }

    fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun append(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
}

/** Shared identity for a captured question before it is committed to the mistake collection. */
object CapturedTutorProblemIdentity {
    const val fingerprintVersion = "captured-question-v1"

    fun questionFingerprint(draftId: String): String {
        requireCanonicalIdentityValue(draftId, "Captured tutor draft id")
        return CanonicalSha256(fingerprintVersion)
            .field("draftId", draftId)
            .finish()
    }
}

private fun requireCanonicalIdentityValue(value: String, label: String) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= 256 &&
            value.none(Char::isISOControl),
    ) { "$label must be a trimmed non-blank value of at most 256 characters" }
}
