package com.tingyun.smartmistakebook.core.model

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.Locale
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSha256Test {
    @Test
    fun finishIsStableAndSealsTheBuilder() {
        val hash = CanonicalSha256("test-domain").field("value", "one")

        val first = hash.finish()

        assertEquals(first, hash.finish())
        assertTrue(
            runCatching {
                hash.field("late", "mutation")
            }.exceptionOrNull() is IllegalStateException,
        )
    }

    @Test
    fun fixedUtf8AndNumericBoundaryVectorsRemainByteForByteCompatible() {
        assertVector(
            domain = "vector-empty",
            fields = listOf(ReferenceField("value", "")),
            expected = "34016d079a024091bd279788d1bfa72a74b4dab99646b3245d995a6cac9fee12",
        )
        assertVector(
            domain = "vector-ascii",
            fields = listOf(ReferenceField("value", "Hello-123")),
            expected = "9734d5d556ccc60ed8d05c17ff21cce74def8c2ebbb08d196dcf4a64c41ee5ea",
        )
        assertVector(
            domain = "vector-utf8",
            fields = listOf(ReferenceField("value", "函数与几何🙂")),
            expected = "bfc518a5012ccf151324f78fed85e13d3813e8435769535e721353437380092d",
        )

        val boundaryHash =
            CanonicalSha256("vector-bounds")
                .field("intMin", Int.MIN_VALUE)
                .field("intMax", Int.MAX_VALUE)
                .field("longMin", Long.MIN_VALUE)
                .field("longMax", Long.MAX_VALUE)
                .finish()
        val boundaryFields =
            listOf(
                ReferenceField("intMin", Int.MIN_VALUE.toString()),
                ReferenceField("intMax", Int.MAX_VALUE.toString()),
                ReferenceField("longMin", Long.MIN_VALUE.toString()),
                ReferenceField("longMax", Long.MAX_VALUE.toString()),
            )
        assertEquals(
            "fa4466d80d7f0001158943176a854e7714016f9c9bfda306aa61c64eb9b109c7",
            boundaryHash,
        )
        assertEquals(referenceHex(boundaryFields, "vector-bounds"), boundaryHash)
    }

    @Test
    fun thousandDeterministicRandomVectorsMatchJdkAndTheLegacyFormatter() {
        val random = Random(0x51a256L)

        repeat(1_000) { index ->
            val randomBytes = ByteArray(random.nextInt(129))
            random.nextBytes(randomBytes)
            val fields =
                listOf(
                    ReferenceField(
                        "payload",
                        Base64.getEncoder().encodeToString(randomBytes),
                    ),
                    ReferenceField(
                        "utf8",
                        "知识点-$index-${random.nextLong()}",
                    ),
                    ReferenceField(
                        "optional",
                        if (index % 7 == 0) null else random.nextInt().toString(),
                    ),
                )
            val domain = "random-vector-$index"
            val actual =
                CanonicalSha256(domain)
                    .field("payload", checkNotNull(fields[0].value))
                    .field("utf8", checkNotNull(fields[1].value))
                    .nullableField("optional", fields[2].value)
                    .finish()
            val referenceBytes = referenceDigest(fields, domain)

            assertEquals(HexFormat.of().formatHex(referenceBytes), actual)
            assertEquals(legacyFormattedHex(referenceBytes), actual)
        }
    }

    @Test
    fun repeatingSchemaOptimizationPreservesTheCanonicalProtocol() {
        val regular =
            CanonicalSha256("repeating-schema")
                .field("index", 1L)
                .nullableLongField("occurredAt", null)
                .field("index", Long.MIN_VALUE)
                .nullableLongField("occurredAt", Long.MAX_VALUE)
                .field("label", "知识点")
                .field("label", "steady")
                .finish()
        val optimized =
            CanonicalSha256.repeatingSchema("repeating-schema")
                .field("index", 1L)
                .nullableLongField("occurredAt", null)
                .field("index", Long.MIN_VALUE)
                .nullableLongField("occurredAt", Long.MAX_VALUE)
                .field("label", "知识点")
                .field("label", "steady")
                .finish()

        assertEquals(regular, optimized)
        assertEquals(
            referenceHex(
                fields =
                    listOf(
                        ReferenceField("index", "1"),
                        ReferenceField("occurredAt", null),
                        ReferenceField("index", Long.MIN_VALUE.toString()),
                        ReferenceField("occurredAt", Long.MAX_VALUE.toString()),
                        ReferenceField("label", "知识点"),
                        ReferenceField("label", "steady"),
                    ),
                domain = "repeating-schema",
            ),
            optimized,
        )
    }

    @Test
    fun reusableBufferDoesNotLeakAcrossInterleavedConcurrentOrExceptionalHashes() {
        val longAscii = "a".repeat(700)
        val firstFields =
            listOf(
                ReferenceField("ascii", "short"),
                ReferenceField("utf8", "函数与几何🙂"),
                ReferenceField("long", longAscii),
                ReferenceField("nested", referenceHex(emptyList(), "nested-domain")),
            )
        val secondFields =
            listOf(
                ReferenceField("value", "completely-different"),
                ReferenceField("optional", null),
            )
        val first = CanonicalSha256("interleaved-first")
        val second = CanonicalSha256("interleaved-second")
        first.field("ascii", checkNotNull(firstFields[0].value))
        second.field("value", checkNotNull(secondFields[0].value))
        first.field("utf8", checkNotNull(firstFields[1].value))
        second.nullableField("optional", null)
        first.field("long", checkNotNull(firstFields[2].value))
        first.field("nested", checkNotNull(firstFields[3].value))

        assertEquals(referenceHex(firstFields, "interleaved-first"), first.finish())
        assertEquals(referenceHex(secondFields, "interleaved-second"), second.finish())

        val recovered = CanonicalSha256("after-exception")
        assertTrue(
            runCatching {
                recovered.field(" invalid ", "ignored")
            }.exceptionOrNull() is IllegalArgumentException,
        )
        recovered.field("value", "still-clean")
        assertEquals(
            referenceHex(
                listOf(ReferenceField("value", "still-clean")),
                "after-exception",
            ),
            recovered.finish(),
        )

        val executor = Executors.newFixedThreadPool(4)
        try {
            val results =
                executor.invokeAll(
                    (0 until 200).map { index ->
                        Callable {
                            val fields =
                                listOf(
                                    ReferenceField("index", index.toString()),
                                    ReferenceField("utf8", "并发-$index-🙂"),
                                    ReferenceField("long", longAscii + index),
                                )
                            val actual =
                                CanonicalSha256("concurrent-$index")
                                    .field("index", index)
                                    .field("utf8", checkNotNull(fields[1].value))
                                    .field("long", checkNotNull(fields[2].value))
                                    .finish()
                            referenceHex(fields, "concurrent-$index") to actual
                        }
                    },
                )
            results.forEach { result ->
                val (expected, actual) = result.get()
                assertEquals(expected, actual)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun compiledSchemaIsProtocolEquivalentStrictAndConcurrencySafe() {
        val schema =
            CanonicalSha256.compileSchema(
                "compiled-record",
                "id",
                "occurredAt",
                "optional",
            )
        val fields =
            listOf(
                ReferenceField("id", "record-1"),
                ReferenceField("occurredAt", Long.MAX_VALUE.toString()),
                ReferenceField("optional", null),
            )
        val expected = referenceHex(fields, "compiled-record")
        val digest =
            schema
                .newDigest()
                .field("id", "record-1")
                .field("occurredAt", Long.MAX_VALUE)
                .nullableField("optional", null)

        assertTrue(digest.finishMatchesHex(expected))
        assertEquals(expected, digest.finish())
        assertTrue(
            !schema
                .newDigest()
                .field("id", "record-2")
                .field("occurredAt", Long.MAX_VALUE)
                .nullableField("optional", null)
                .finishMatchesHex(expected),
        )
        assertTrue(
            runCatching {
                schema.newDigest().field("occurredAt", 1L)
            }.exceptionOrNull() is IllegalStateException,
        )
        assertTrue(
            runCatching {
                schema.newDigest().field("id", "record-1").finish()
            }.exceptionOrNull() is IllegalStateException,
        )
        assertTrue(
            runCatching {
                schema
                    .newDigest()
                    .field("id", "record-1")
                    .field("occurredAt", 1L)
                    .nullableField("optional", null)
                    .field("extra", "forbidden")
            }.exceptionOrNull() is IllegalStateException,
        )
        listOf(
            expected.uppercase(Locale.ROOT),
            expected.dropLast(1),
            expected.dropLast(1) + "g",
        ).forEach { invalid ->
            assertTrue(
                runCatching {
                    schema
                        .newDigest()
                        .field("id", "record-1")
                        .field("occurredAt", Long.MAX_VALUE)
                        .nullableField("optional", null)
                        .finishMatchesHex(invalid)
                }.exceptionOrNull() is IllegalArgumentException,
            )
        }

        val executor = Executors.newFixedThreadPool(4)
        try {
            val results =
                executor.invokeAll(
                    (0 until 200).map { index ->
                        Callable {
                            val concurrentFields =
                                listOf(
                                    ReferenceField("id", "record-$index"),
                                    ReferenceField("occurredAt", index.toString()),
                                    ReferenceField(
                                        "optional",
                                        if (index % 2 == 0) null else "值-$index",
                                    ),
                                )
                            val actual =
                                schema
                                    .newDigest()
                                    .field("id", "record-$index")
                                    .field("occurredAt", index)
                                    .nullableField("optional", concurrentFields[2].value)
                                    .finish()
                            referenceHex(concurrentFields, "compiled-record") to actual
                        }
                    },
                )
            results.forEach { result ->
                val (concurrentExpected, actual) = result.get()
                assertEquals(concurrentExpected, actual)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun batchedFieldFramesMatchTheReferenceAtEveryValueBoundary() {
        val exactBufferValue = "x".repeat(2_024)
        val fallbackValue = "y".repeat(2_025)
        val fields =
            listOf(
                ReferenceField("empty", ""),
                ReferenceField("nullableString", null),
                ReferenceField("nullableLong", null),
                ReferenceField("presentLong", "-9"),
                ReferenceField("intMin", Int.MIN_VALUE.toString()),
                ReferenceField("longMax", Long.MAX_VALUE.toString()),
                ReferenceField("true", "true"),
                ReferenceField("false", "false"),
                ReferenceField("utf8", "知识🙂"),
                ReferenceField("value", exactBufferValue),
                ReferenceField("value", fallbackValue),
                ReferenceField("tail", "z"),
            )
        val actual =
            CanonicalSha256("frame-boundaries")
                .field("empty", "")
                .nullableField("nullableString", null)
                .nullableLongField("nullableLong", null)
                .nullableLongField("presentLong", -9L)
                .field("intMin", Int.MIN_VALUE)
                .field("longMax", Long.MAX_VALUE)
                .field("true", true)
                .field("false", false)
                .field("utf8", "知识🙂")
                .field("value", exactBufferValue)
                .field("value", fallbackValue)
                .field("tail", "z")
                .finish()

        assertEquals(referenceHex(fields, "frame-boundaries"), actual)
    }

    private fun assertVector(
        domain: String,
        fields: List<ReferenceField>,
        expected: String,
    ) {
        var canonical = CanonicalSha256(domain)
        fields.forEach { field ->
            canonical =
                if (field.value == null) {
                    canonical.nullableField(field.name, null)
                } else {
                    canonical.field(field.name, field.value)
                }
        }
        val actual = canonical.finish()

        assertEquals(expected, actual)
        assertEquals(referenceHex(fields, domain), actual)
    }
}

private data class ReferenceField(
    val name: String,
    val value: String?,
)

private fun referenceHex(
    fields: List<ReferenceField>,
    domain: String,
): String =
    HexFormat.of().formatHex(referenceDigest(fields, domain))

private fun referenceDigest(
    fields: List<ReferenceField>,
    domain: String,
): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")

    fun append(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    fun field(name: String, value: String?) {
        append(name)
        if (value == null) {
            append("NULL")
        } else {
            append("PRESENT")
            append(value)
        }
    }

    field("domain", domain)
    field("canonicalVersion", "1")
    fields.forEach { field -> field(field.name, field.value) }
    return digest.digest()
}

private fun legacyFormattedHex(bytes: ByteArray): String =
    bytes.joinToString(separator = "") { byte ->
        String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
    }
