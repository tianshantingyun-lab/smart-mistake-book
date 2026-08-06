package com.tingyun.smartmistakebook.core.data.mistake

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StudentMistakeCatalogCursorContractTest {
    @Test
    fun cursorAuthenticatesLearnerGenerationRevisionQueryAndCompleteSortKey() {
        val key = DerivedStudentMistakeCatalogCursorKey(123L, "problem-9", "entry-8")
        val cursor = create(key)

        assertEquals(key, decode(cursor))
        assertNull(decode(cursor, learner = "f".repeat(64)))
        assertNull(decode(cursor, generation = "generation-b"))
        assertNull(decode(cursor, revision = "e".repeat(64)))
        assertNull(decode(cursor, query = "d".repeat(64)))
    }

    @Test
    fun oneByteCursorForgeryFailsClosed() {
        val cursor = create(DerivedStudentMistakeCatalogCursorKey(123L, "problem-9", "entry-8"))
        val last = cursor.opaqueValue.last()
        val forged =
            StudentMistakeCatalogCursor(
                cursor.opaqueValue.dropLast(1) + if (last == '0') '1' else '0',
            )

        assertNull(decode(forged))
    }

    private fun create(key: DerivedStudentMistakeCatalogCursorKey): StudentMistakeCatalogCursor =
        StudentMistakeCatalogCursorCodec.create(
            generationId = GENERATION,
            learnerFingerprint = LEARNER,
            revisionFingerprint = REVISION,
            queryFingerprint = QUERY,
            key = key,
            authenticationKey = AUTHENTICATION_KEY,
        )

    private fun decode(
        cursor: StudentMistakeCatalogCursor,
        generation: String = GENERATION,
        learner: String = LEARNER,
        revision: String = REVISION,
        query: String = QUERY,
    ): DerivedStudentMistakeCatalogCursorKey? =
        StudentMistakeCatalogCursorCodec.decodeAndVerify(
            cursor = cursor,
            authenticationKey = AUTHENTICATION_KEY,
            expectedGenerationId = generation,
            expectedLearnerFingerprint = learner,
            expectedRevisionFingerprint = revision,
            expectedQueryFingerprint = query,
        )

    private companion object {
        const val GENERATION = "generation-a"
        val LEARNER = "a".repeat(64)
        val REVISION = "b".repeat(64)
        val QUERY = "c".repeat(64)
        val AUTHENTICATION_KEY =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
    }
}
