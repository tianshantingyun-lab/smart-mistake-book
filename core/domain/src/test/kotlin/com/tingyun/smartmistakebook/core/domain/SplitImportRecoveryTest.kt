package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grace window is the only thing standing between a recovery pass and a
 * writer that is legitimately mid-sequence, so both sides of the boundary are
 * pinned here rather than in an instrumented test that cannot run offline.
 */
class SplitImportRecoveryTest {

    private val grace = SplitImportRecovery.STUCK_PREPARING_GRACE_MILLIS
    private val createdAt = 1_000_000L

    private fun stuck(
        status: String = SplitImportStatus.PREPARING,
        now: Long = createdAt + grace,
        graceMillis: Long = grace,
    ) = SplitImportRecovery.isStuckPreparing(
        status = status,
        createdAtEpochMillis = createdAt,
        nowEpochMillis = now,
        graceMillis = graceMillis,
    )

    @Test
    fun `a preparing job at the grace boundary is stuck`() {
        assertTrue(stuck(now = createdAt + grace))
    }

    @Test
    fun `a preparing job one millisecond inside the grace window is left alone`() {
        assertFalse(stuck(now = createdAt + grace - 1))
    }

    @Test
    fun `a freshly created preparing job is left alone`() {
        assertFalse(stuck(now = createdAt))
    }

    @Test
    fun `a job stamped in the future is left alone`() {
        assertFalse(stuck(now = createdAt - 1))
    }

    @Test
    fun `only preparing counts as stuck`() {
        val now = createdAt + grace * 2
        assertFalse(stuck(status = SplitImportStatus.READY, now = now))
        assertFalse(stuck(status = SplitImportStatus.COMPLETED, now = now))
        assertFalse(stuck(status = SplitImportStatus.ABANDONED, now = now))
    }

    @Test
    fun `a zero grace window promotes immediately`() {
        assertTrue(stuck(now = createdAt, graceMillis = 0))
    }
}
