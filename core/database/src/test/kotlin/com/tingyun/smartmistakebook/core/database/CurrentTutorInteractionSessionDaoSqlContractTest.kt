package com.tingyun.smartmistakebook.core.database

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTutorInteractionSessionDaoSqlContractTest {
    @Test
    fun actionableAuthorityRequestExcludesTerminalReceiptOverlay() {
        val source = daoSource()
        val query = source.queryBefore("readPendingAuthorityEvidenceRequest")

        query.requires(
            "SELECT request.*",
            "FROM tutor_evidence_request AS request",
            "request.evidence_request_id = :evidenceRequestId",
            "request.learner_id = :learnerId",
            "request.status = 'PENDING'",
            "AND NOT EXISTS",
            "FROM tutor_learning_evidence_finalization_receipt AS finalization",
            "finalization.evidence_request_id = request.evidence_request_id",
        )
        assertFalse(
            "Terminal requests must not be re-authorized",
            "status != 'CANCELLED'" in query,
        )
        assertFalse(
            "A receipt state filter would leave another terminal overlay writable",
            "finalization.state" in query,
        )
    }

    @Test
    fun encryptedFreeResponseDispatchIsBoundedByDueTimeAttemptCapAndRetention() {
        val source = daoSource()
        source.queryBefore("readRecoverableFreeResponseOutbox").requires(
            "discard_after_epoch_millis <= :nowEpochMillis",
            "dispatch_attempt_count >= :maxDispatchAttempts",
            "next_dispatch_at_epoch_millis <= :nowEpochMillis",
        )
        source.queryBefore("acquireFreeResponseOutbox").requires(
            "dispatch_attempt_count = dispatch_attempt_count + 1",
            "discard_after_epoch_millis > :updatedAtEpochMillis",
            "dispatch_attempt_count < :maxDispatchAttempts",
            "next_dispatch_at_epoch_millis <= :updatedAtEpochMillis",
        )
        source.queryBefore("releaseFreeResponseOutbox").requires(
            "next_dispatch_at_epoch_millis = :nextDispatchAtEpochMillis",
            "status = 'IN_FLIGHT'",
            "lease_token = :leaseToken",
        )
    }

    @Test
    fun freeResponseExpiryUsesOnlyMinimumCutoffAndCapturedBoundedWipe() {
        val source = daoSource()
        source.queryBefore("readNextPendingFreeResponseOutboxCutoff").requires(
            "SELECT MIN(",
            "ELSE discard_after_epoch_millis",
            "status IN ('NEEDS_DISPATCH', 'IN_FLIGHT')",
            "lease_owner_id IS NULL",
            "lease_generation_id IS NULL",
            "lease_token IS NULL",
            "lease_expires_at_epoch_millis IS NULL",
        )
        source.queryBefore("failClosedPendingFreeResponseOutboxesThrough").requires(
            "status = 'FAILED_CLOSED'",
            "encrypted_answer = NULL",
            "nonce = NULL",
            "discard_after_epoch_millis <= :capturedCutoffEpochMillis",
            "updated_at_epoch_millis > :capturedCutoffEpochMillis",
        )
        source.queryBefore("readRecoverableFreeResponseOutbox").requires(
            "lease_owner_id IS NULL",
            "lease_token IS NULL",
            "lease_expires_at_epoch_millis IS NULL",
        )
    }

    private fun daoSource(): String = projectRoot()
        .resolve(
            "core/database/src/main/kotlin/com/tingyun/smartmistakebook/core/database/dao/" +
                "CurrentTutorInteractionSessionDao.kt",
        )
        .readText()

    private fun projectRoot(): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { directory -> File(directory, "settings.gradle.kts").isFile }
            ?: error("Cannot locate project root")

    private fun String.queryBefore(methodName: String): String {
        val methodIndex = indexOf("fun $methodName(")
        check(methodIndex >= 0) { "Cannot find $methodName" }
        val queryStart = lastIndexOf("@Query(", methodIndex)
        check(queryStart >= 0) { "Cannot find query for $methodName" }
        return substring(queryStart, methodIndex)
    }

    private fun String.requires(vararg fragments: String) {
        fragments.forEach { fragment ->
            assertTrue("Missing SQL contract fragment: $fragment", fragment in this)
        }
    }
}
