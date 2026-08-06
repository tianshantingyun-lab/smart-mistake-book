package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.data.session.BatchImportSessionSnapshot
import com.tingyun.smartmistakebook.core.data.session.BatchImportSessionStatus
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import com.tingyun.smartmistakebook.core.data.session.SessionVersion
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionBatchImportReplayContractTest {
    @Test
    fun sameRequestAndPayloadReplayIsAcceptedButSameKeyDifferentPayloadConflicts() {
        val snapshot = snapshot(requestId = "request-replay", requestFingerprint = hash('a'))

        requireExistingRequest(snapshot, "request-replay", hash('a'))
        requireExistingRequest(snapshot, "request-replay", hash('a'))

        val conflict =
            assertThrows(IllegalArgumentException::class.java) {
                requireExistingRequest(snapshot, "request-replay", hash('b'))
            }
        assertTrue(conflict.message.orEmpty().contains("reused with different input"))
    }

    @Test
    fun aDifferentRequestCannotReplayAnExistingBatchEvenWithTheSamePayload() {
        val snapshot = snapshot(requestId = "request-original", requestFingerprint = hash('c'))

        assertThrows(IllegalArgumentException::class.java) {
            requireExistingRequest(snapshot, "request-other", hash('c'))
        }
    }

    private fun requireExistingRequest(
        snapshot: BatchImportSessionSnapshot,
        requestId: String,
        requestFingerprint: String,
    ) {
        val method =
            Class.forName(
                "com.tingyun.smartmistakebook.core.data.capture." +
                    "ProductionBatchImportRepositoryKt",
            ).declaredMethods.single { candidate ->
                candidate.name == "requireRequest" && candidate.parameterCount == 3
            }.apply { isAccessible = true }
        try {
            method.invoke(null, snapshot, requestId, requestFingerprint)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    private fun snapshot(
        requestId: String,
        requestFingerprint: String,
    ) = BatchImportSessionSnapshot(
        scope = SessionScope("learner-replay"),
        jobId = "batch-replay",
        requestId = requestId,
        requestFingerprint = requestFingerprint,
        version = SessionVersion(sequence = 1, fingerprint = hash('1')),
        status = BatchImportSessionStatus.PAUSED,
        pages = emptyList(),
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private fun hash(character: Char): String = character.toString().repeat(64)
}
