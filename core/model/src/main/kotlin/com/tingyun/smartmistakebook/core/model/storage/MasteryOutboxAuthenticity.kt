package com.tingyun.smartmistakebook.core.model.storage

import com.tingyun.smartmistakebook.core.model.CanonicalSha256

/**
 * Structural proof emitted by the learner-mastery owner for one exact outbox envelope.
 *
 * Possessing this value is not authority. The student-mistake destination accepts only a
 * source-verifier receipt produced from this proof. The domains are deliberately different from
 * the student-to-mastery protocol so a valid tag can never be reflected across directions.
 */
data class MasteryOutboxAuthenticityProof(
    val protocolVersion: Int,
    val algorithmVersion: String,
    val issuerKeyId: String,
    val learnerId: String,
    val envelopeCanonicalFingerprint: String,
    val tagHex: String,
) {
    init {
        require(protocolVersion == PROTOCOL_VERSION) {
            "Unsupported learner-mastery outbox authenticity protocol"
        }
        require(algorithmVersion == ALGORITHM_VERSION) {
            "Unsupported learner-mastery outbox authenticity algorithm"
        }
        requireStoreIdentity(issuerKeyId, "Learner-mastery outbox authenticity key id")
        requireStoreIdentity(learnerId, "Learner-mastery outbox learner id")
        requireCanonicalFingerprint(
            envelopeCanonicalFingerprint,
            "Learner-mastery authenticated envelope fingerprint",
        )
        requireCanonicalFingerprint(tagHex, "Learner-mastery outbox authenticity tag")
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
            "learner-mastery-outbox-authenticated-context-v1"
        const val MAC_DOMAIN = "learner-mastery-outbox-authenticity-v1"

        private const val PROOF_FINGERPRINT_DOMAIN =
            "learner-mastery-outbox-authenticity-proof-v1"
    }
}
