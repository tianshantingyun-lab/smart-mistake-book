package com.tingyun.smartmistakebook.core.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiSseDoneTerminatorWindowTest {

    private val terminator = "data: [DONE]"

    @Test
    fun detectsTerminatorInTheUnscannedTail() {
        val accumulated = "data: {\"content\":\"reply\"}\n\n$terminator\n\n"
        // We scanned only the first data frame; the terminator lies in the still-unscanned tail.
        assertTrue(containsDoneTerminatorAfter(accumulated, scannedThrough = 28))
    }

    @Test
    fun doesNotMisfireOnATerminatorAlreadyScanned() {
        val accumulated = "data: {\"content\":\"reply\"}\n\n$terminator\n\n"
        // Even when scannedThrough covers the whole buffer there is no terminator strictly after it.
        assertFalse(containsDoneTerminatorAfter(accumulated, scannedThrough = accumulated.length))
    }

    @Test
    fun catchesATerminatorStraddlingTwoChunks() {
        val prefix = "data: {\"content\":\"reply\"}\n\ndata: [D"
        val suffix = "ONE]\n\n"
        // Simulate the terminator split across two reads: prefix was scanned, suffix appends the rest.
        assertTrue(containsDoneTerminatorAfter(prefix + suffix, scannedThrough = prefix.length))
    }
}
