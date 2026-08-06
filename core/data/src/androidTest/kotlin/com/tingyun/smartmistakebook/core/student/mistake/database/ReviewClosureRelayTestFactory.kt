package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.StudentMistakeRelayMessage
import com.tingyun.smartmistakebook.core.model.storage.StudentOutboxAuthenticityProof

/**
 * Instrumentation-only factory that mints the same typed verified delivery shape the student
 * owner produces for one exact outbox envelope. It stays in this package only to reuse the
 * owner-private verification receipt binding; no production API is widened for tests.
 */
internal object ReviewClosureRelayTestFactory {
    fun verifiedDelivery(
        envelope: CrossStoreEventEnvelope,
        learnerId: String,
    ): VerifiedStudentOutboxDelivery {
        val raw =
            StudentMistakeRelayMessage.fromUnverifiedEnvelopeAndProof(
                envelope,
                StudentOutboxAuthenticityProof(
                    protocolVersion = StudentOutboxAuthenticityProof.PROTOCOL_VERSION,
                    algorithmVersion = StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
                    issuerKeyId = "student-test-key",
                    learnerId = learnerId,
                    envelopeCanonicalFingerprint = envelope.canonicalFingerprint,
                    tagHex = "0".repeat(64),
                ),
            )
        val delivery = StudentOutboxDelivery.ownerIssued(raw)
        val receipt =
            StudentOutboxVerificationReceipt.ownerIssued(
                raw,
                envelope.sourceStoreGeneration,
                "student-test-relay-epoch",
                "student-test-key",
                StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
            )
        return VerifiedStudentOutboxDelivery(delivery, receipt)
    }
}
