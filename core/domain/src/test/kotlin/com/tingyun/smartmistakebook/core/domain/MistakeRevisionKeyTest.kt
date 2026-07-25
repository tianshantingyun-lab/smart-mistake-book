package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MistakeRevisionKeyTest {
    @Test
    fun preservesAllThreeIdentityBoundaries() {
        val key = MistakeRevisionKey(
            entryId = "entry-1",
            problemId = "problem-1",
            problemRevisionId = "revision-1",
        )

        assertEquals("entry-1", key.entryId)
        assertEquals("problem-1", key.problemId)
        assertEquals("revision-1", key.problemRevisionId)
    }

    @Test
    fun rejectsBlankIdentityParts() {
        assertTrue(
            runCatching { MistakeRevisionKey("", "problem-1", "revision-1") }.isFailure,
        )
        assertTrue(
            runCatching { MistakeRevisionKey("entry-1", " ", "revision-1") }.isFailure,
        )
        assertTrue(
            runCatching { MistakeRevisionKey("entry-1", "problem-1", "\t") }.isFailure,
        )
    }
}
