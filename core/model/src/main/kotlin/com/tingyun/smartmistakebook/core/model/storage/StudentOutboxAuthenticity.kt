package com.tingyun.smartmistakebook.core.model.storage

import com.tingyun.smartmistakebook.core.model.CanonicalSha256

/**
 * A structural transport proof attached by the student-mistake outbox owner.
 *
 * Constructing this value is not authority. The learner-mastery boundary must verify [tagHex]
 * through the final verify-only capability issued by the student-store owner before persisting
 * any part of the message. The tag authenticates only an already opaque cross-store envelope; it
 * never contains a raw learner response or a grading rule.
 */
data class StudentOutboxAuthenticityProof(
    val protocolVersion: Int,
    val algorithmVersion: String,
    val issuerKeyId: String,
    val learnerId: String,
    val envelopeCanonicalFingerprint: String,
    val tagHex: String,
) {
    init {
        require(protocolVersion == PROTOCOL_VERSION) {
            "Unsupported student-outbox authenticity protocol"
        }
        require(algorithmVersion == ALGORITHM_VERSION) {
            "Unsupported student-outbox authenticity algorithm"
        }
        requireStoreIdentity(issuerKeyId, "Student-outbox authenticity key id")
        requireStoreIdentity(learnerId, "Student-outbox authenticity learner id")
        requireCanonicalFingerprint(
            envelopeCanonicalFingerprint,
            "Student-outbox authenticated envelope fingerprint",
        )
        requireCanonicalFingerprint(tagHex, "Student-outbox authenticity tag")
    }

    val canonicalFingerprint: String
        get() =
            CanonicalSha256(PROOF_FINGERPRINT_DOMAIN)
                .field("protocolVersion", protocolVersion)
                .field("algorithmVersion", algorithmVersion)
                .field("issuerKeyId", issuerKeyId)
                .field("learnerId", learnerId)
                .field("envelopeCanonicalFingerprint", envelopeCanonicalFingerprint)
                .field("tagHex", tagHex)
                .finish()

    companion object {
        const val PROTOCOL_VERSION = 1
        const val ALGORITHM_VERSION = "android-keystore-hmac-sha256-v1"
        const val AUTHENTICATED_CONTEXT_DOMAIN =
            "student-mistake-outbox-authenticated-context-v1"
        const val MAC_DOMAIN = "student-mistake-outbox-authenticity-v1"

        private const val PROOF_FINGERPRINT_DOMAIN =
            "student-mistake-outbox-authenticity-proof-v1"
    }
}
