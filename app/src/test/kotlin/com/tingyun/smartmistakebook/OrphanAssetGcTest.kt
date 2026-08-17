package com.tingyun.smartmistakebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanAssetGcTest {
    @Test
    fun uniqueWorkKeepsASingleNamedJob() {
        assertEquals("orphan-asset-gc", OrphanAssetGc.UNIQUE_NAME)
        assertEquals("KEEP", OrphanAssetGc.existingPolicy.name)
    }

    @Test
    fun statusLineIsStudentFacingAndHidesWhenIdle() {
        assertNull(orphanAssetGcStatusLine(OrphanAssetGcPhase.IDLE))
        assertEquals(
            orphanAssetGcStatusLine(OrphanAssetGcPhase.SCHEDULED),
            orphanAssetGcStatusLine(OrphanAssetGcPhase.RUNNING),
        )
        val running = orphanAssetGcStatusLine(OrphanAssetGcPhase.RUNNING).orEmpty()
        val failed = orphanAssetGcStatusLine(OrphanAssetGcPhase.FAILED).orEmpty()
        assertTrue(running.isNotBlank())
        assertTrue(failed.isNotBlank())
        assertTrue("WorkManager" !in running + failed)
        assertTrue("UNIQUE" !in running + failed)
        assertTrue("enqueue" !in running + failed)
        assertEquals(OrphanAssetGcPhase.RUNNING, orphanAssetGcPhase(listOf("ENQUEUED", "RUNNING")))
        assertEquals(OrphanAssetGcPhase.SCHEDULED, orphanAssetGcPhase(listOf("BLOCKED")))
        assertEquals(OrphanAssetGcPhase.FAILED, orphanAssetGcPhase(listOf("FAILED")))
        assertEquals(OrphanAssetGcPhase.IDLE, orphanAssetGcPhase(listOf("SUCCEEDED")))
    }
}
