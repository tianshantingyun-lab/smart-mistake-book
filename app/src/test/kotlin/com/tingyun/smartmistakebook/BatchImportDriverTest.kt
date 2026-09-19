package com.tingyun.smartmistakebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The driver's own behaviour (drive, retry, resume) lives behind a Room-backed
 * repository and is covered by the batch-import instrumented suites. What is
 * pinned here is the WorkManager wiring, because both properties below fail
 * silently if wrong: a unique name is global to the queue, and a non-KEEP policy
 * would let a second enqueue stack duplicate drivers.
 */
class BatchImportDriverTest {
    @Test
    fun uniqueWorkKeepsASingleNamedJob() {
        assertEquals("batch-import-driver", BatchImportDriver.UNIQUE_NAME)
        assertEquals("KEEP", BatchImportDriver.existingPolicy.name)
    }

    @Test
    fun theDriverDoesNotShareTheGarbageCollectorsUniqueName() {
        // Sharing a unique name with KEEP would let whichever work was enqueued
        // first suppress the other, silently disabling one of them.
        assertNotEquals(OrphanAssetGc.UNIQUE_NAME, BatchImportDriver.UNIQUE_NAME)
    }
}
