package com.tingyun.smartmistakebook.core.domain

import java.util.Arrays
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelApiKeyTest {
    @Test
    fun `secret string representation is redacted and source mutations do not change it`() {
        val source = "sensitive-value".toCharArray()
        val key = ModelApiKey.from(source)
        Arrays.fill(source, 'x')

        val copied = key.copyChars()
        try {
            assertArrayEquals("sensitive-value".toCharArray(), copied)
            assertFalse(key.toString().contains("sensitive-value"))
            assertFalse(
                ModelCredentialReadResult.Available(ModelConfigurationSnapshot(), key)
                    .toString()
                    .contains("sensitive-value"),
            )
        } finally {
            Arrays.fill(copied, '\u0000')
            key.close()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `closed secret cannot be copied`() {
        val key = ModelApiKey.from("sensitive-value".toCharArray())
        key.close()

        key.copyChars()
    }
}
