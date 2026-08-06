package com.tingyun.smartmistakebook.feature.capture

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `ttl prune removes only expired owned captures`() {
        val directory = temporaryFolder.newFolder("captured_images")
        val expired = directory.capture("question_expired.jpg", modifiedAt = 100L)
        val fresh = directory.capture("question_fresh.jpg", modifiedAt = 300L)
        val unrelated = directory.capture("other.jpg", modifiedAt = 100L)

        val deleted = pruneCaptureDirectory(
            directory = directory,
            retainedNames = emptySet(),
            deleteBeforeMillis = 200L,
        )

        assertEquals(1, deleted)
        assertFalse(expired.exists())
        assertTrue(fresh.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `active capture survives immediate screen prune`() {
        val directory = temporaryFolder.newFolder("captured_images")
        val retained = directory.capture("question_active.jpg", modifiedAt = 100L)
        val orphan = directory.capture("question_orphan.jpg", modifiedAt = 100L)

        val deleted = pruneCaptureDirectory(
            directory = directory,
            retainedNames = setOf(retained.name),
        )

        assertEquals(1, deleted)
        assertTrue(retained.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun `capture size gate accepts only nonempty bounded files inside owned directory`() {
        val directory = temporaryFolder.newFolder("bounded_captures")
        val valid = File(directory, "question_valid.jpg").apply { writeBytes(jpegBytes(8)) }
        File(directory, "question_empty.jpg").createNewFile()
        File(directory, "question_large.jpg").writeBytes(jpegBytes(9))

        assertTrue(captureFileWithinLimit(directory, valid.name, maxBytes = 8))
        assertFalse(captureFileWithinLimit(directory, "question_empty.jpg", maxBytes = 8))
        assertFalse(captureFileWithinLimit(directory, "question_large.jpg", maxBytes = 8))
        assertFalse(captureFileWithinLimit(directory, "../outside.jpg", maxBytes = 8))
    }

    @Test
    fun `picker stream is copied only when bounded nonempty and image typed`() {
        val directory = temporaryFolder.newFolder("picker_imports")
        val valid = File(directory, "question_valid.img")
        val oversized = File(directory, "question_oversized.img")
        val unsupported = File(directory, "question_unsupported.img")

        assertTrue(
            copyCaptureStreamWithinLimit(
                ByteArrayInputStream(jpegBytes(8)),
                valid,
                maxBytes = 8,
            ),
        )
        assertTrue(valid.exists())
        assertFalse(
            copyCaptureStreamWithinLimit(
                ByteArrayInputStream(jpegBytes(9)),
                oversized,
                maxBytes = 8,
            ),
        )
        assertFalse(oversized.exists())
        assertFalse(
            copyCaptureStreamWithinLimit(
                ByteArrayInputStream("not-image".toByteArray()),
                unsupported,
                maxBytes = 32,
            ),
        )
        assertFalse(unsupported.exists())
    }

    @Test
    fun `picker stream fails closed when wall clock budget is exceeded`() {
        val directory = temporaryFolder.newFolder("picker_timeout")
        val stalled = File(directory, "question_stalled.img")

        assertFalse(
            copyCaptureStreamWithinLimit(
                SlowStream(jpegBytes(8)),
                stalled,
                maxBytes = 8,
                timeoutMillis = 20L,
            ),
        )
        assertFalse(stalled.exists())
    }

    private class SlowStream(private val bytes: ByteArray) : InputStream() {
        private var index = 0

        override fun read(): Int {
            Thread.sleep(50L)
            if (index >= bytes.size) return -1
            return bytes[index++].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(200L)
            if (index >= bytes.size) return -1
            val count = minOf(length, bytes.size - index)
            bytes.copyInto(buffer, offset, index, index + count)
            index += count
            return count
        }
    }

    private fun File.capture(name: String, modifiedAt: Long): File =
        File(this, name).apply {
            writeText("fixture")
            assertTrue(setLastModified(modifiedAt))
        }

    private fun jpegBytes(size: Int): ByteArray = ByteArray(size).also { bytes ->
        require(size >= 3)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        bytes[2] = 0xFF.toByte()
    }
}
