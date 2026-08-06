package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.model.CaptureMergeSessionReceiptReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionCapabilityContractTest {
    @Test
    fun opaquePayloadRejectsTampering() {
        val content = """{"state":"ready"}"""
        val payload = SessionOpaquePayload(schema = "session-state-v1", content = content)

        assertEquals(content.sha256(), payload.contentSha256)
        assertThrows(IllegalArgumentException::class.java) {
            payload.copy(content = """{"state":"changed"}""")
        }
    }

    @Test
    fun appliedMutationRequiresCommittedVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionMutationReceipt(
                operation = operation("apply-without-version"),
                disposition = SessionMutationDisposition.APPLIED,
                currentVersion = null,
                recordedAtEpochMillis = 1,
            )
        }
    }

    @Test
    fun learnerScopeCannotCrossAnAdapterBoundary() {
        val bound = SessionScope("learner-a")

        assertThrows(IllegalArgumentException::class.java) {
            SessionScope("learner-b").requireBoundTo(bound)
        }
    }

    @Test
    fun operationIdentityRejectsInvalidVersionAndFingerprint() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionOperationIdentity(
                requestId = "request",
                idempotencyKey = "idempotency",
                requestVersion = -1,
                payloadFingerprint = fingerprint("request"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionOperationIdentity(
                requestId = "request",
                idempotencyKey = "idempotency",
                requestVersion = 0,
                payloadFingerprint = "not-a-sha256",
            )
        }
    }

    @Test
    fun versionFingerprintChangesWhenStateChanges() {
        val first =
            SessionVersion(
                sequence = 3,
                fingerprint = fingerprint("state-a"),
            )
        val second =
            SessionVersion(
                sequence = 3,
                fingerprint = fingerprint("state-b"),
            )

        assertNotEquals(first, second)
    }

    @Test
    fun batchBoundaryRequiresCaptureReceiptBeforeDistinctDraftRemap() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchImportSessionMutation.ResolveBoundary(
                scope = SessionScope("learner"),
                operation = operation("boundary"),
                jobId = "job",
                expectedVersion =
                    SessionVersion(
                        sequence = 1,
                        fingerprint = fingerprint("batch-version"),
                    ),
                occurredAtEpochMillis = 2,
                pageIndex = 0,
                primaryDraftSessionId = "draft-a",
                followingDraftSessionId = "draft-b",
                resolution = BatchImportBoundarySessionStatus.SAME_QUESTION,
                boundaryClaimedAtEpochMillis = 1,
                captureMergeReceiptRef = null,
            )
        }
    }

    @Test
    fun batchBoundaryRejectsAReceiptFromAnotherNaturalKey() {
        val expectedVersion =
            SessionVersion(
                sequence = 1,
                fingerprint = fingerprint("batch-version"),
            )
        val receiptForAnotherBoundary =
            CaptureMergeSessionReceiptReference.forBatchBoundary(
                jobId = "other-job",
                pageIndex = 0,
                primaryDraftSessionId = "draft-a",
                followingDraftSessionId = "draft-b",
            )

        assertThrows(IllegalArgumentException::class.java) {
            BatchImportSessionMutation.ResolveBoundary(
                scope = SessionScope("learner"),
                operation = operation("boundary-wrong-receipt"),
                jobId = "job",
                expectedVersion = expectedVersion,
                occurredAtEpochMillis = 2,
                pageIndex = 0,
                primaryDraftSessionId = "draft-a",
                followingDraftSessionId = "draft-b",
                resolution = BatchImportBoundarySessionStatus.SAME_QUESTION,
                boundaryClaimedAtEpochMillis = 1,
                captureMergeReceiptRef = receiptForAnotherBoundary,
            )
        }
    }

    @Test
    fun batchSnapshotRejectsNonContiguousPages() {
        val page =
            BatchImportPageSessionSnapshot(
                pageIndex = 1,
                sourceUri = "content://page/1",
                status = BatchImportPageSessionStatus.QUEUED,
                resultDraftSessionId = null,
                failureCode = null,
                attemptCount = 0,
                boundaryAfter = BatchImportBoundarySessionStatus.PENDING,
                boundaryClaimedAtEpochMillis = null,
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
            )

        assertThrows(IllegalArgumentException::class.java) {
            BatchImportSessionSnapshot(
                scope = SessionScope("learner"),
                jobId = "job",
                requestId = "request",
                requestFingerprint = fingerprint("request"),
                version =
                    SessionVersion(
                        sequence = 1,
                        fingerprint = fingerprint("version"),
                    ),
                status = BatchImportSessionStatus.PROCESSING,
                pages = listOf(page),
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
            )
        }
    }

    @Test
    fun checkingBoundaryRequiresItsPersistedClaimTime() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchImportPageSessionSnapshot(
                pageIndex = 0,
                sourceUri = "content://page/0",
                status = BatchImportPageSessionStatus.READY,
                resultDraftSessionId = "draft-a",
                failureCode = null,
                attemptCount = 1,
                boundaryAfter = BatchImportBoundarySessionStatus.CHECKING,
                boundaryClaimedAtEpochMillis = null,
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 2,
            )
        }
    }

    @Test
    fun everyNonCheckingBoundaryRejectsAStaleClaimTime() {
        BatchImportBoundarySessionStatus.entries
            .filterNot { it == BatchImportBoundarySessionStatus.CHECKING }
            .forEach { boundaryStatus ->
                assertThrows(IllegalArgumentException::class.java) {
                    BatchImportPageSessionSnapshot(
                        pageIndex = 0,
                        sourceUri = "content://page/0",
                        status = BatchImportPageSessionStatus.READY,
                        resultDraftSessionId = "draft-a",
                        failureCode = null,
                        attemptCount = 1,
                        boundaryAfter = boundaryStatus,
                        boundaryClaimedAtEpochMillis = 2,
                        createdAtEpochMillis = 1,
                        updatedAtEpochMillis = 2,
                    )
                }
            }
    }

    @Test
    fun checkingBoundaryAcceptsItsPersistedClaimTime() {
        val snapshot =
            BatchImportPageSessionSnapshot(
                pageIndex = 0,
                sourceUri = "content://page/0",
                status = BatchImportPageSessionStatus.READY,
                resultDraftSessionId = "draft-a",
                failureCode = null,
                attemptCount = 1,
                boundaryAfter = BatchImportBoundarySessionStatus.CHECKING,
                boundaryClaimedAtEpochMillis = 2,
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 2,
            )

        assertEquals(2L, snapshot.boundaryClaimedAtEpochMillis)
    }

    private fun operation(seed: String) =
        SessionOperationIdentity(
            requestId = "request-$seed",
            idempotencyKey = "idempotency-$seed",
            requestVersion = 1,
            payloadFingerprint = fingerprint(seed),
        )

    private fun fingerprint(seed: String): String = seed.sha256()
}
