package com.tingyun.smartmistakebook.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Length-prefixed canonical SHA-256 used for stable cross-module identities. */
class CanonicalSha256 private constructor(
    domain: String?,
    cacheRepeatedFieldNames: Boolean,
    private val compiledSchema: CompiledSchema?,
) {
    constructor(domain: String) :
        this(
            domain = domain,
            cacheRepeatedFieldNames = false,
            compiledSchema = null,
        )

    private val digest = MessageDigest.getInstance("SHA-256")
    private val lengthPrefix = ByteArray(Int.SIZE_BYTES)
    private val fieldNameFrames =
        if (cacheRepeatedFieldNames) HashMap<String, ByteArray>() else null
    private var compiledFieldIndex = 0
    private var finishedBytes: ByteArray? = null
    private var finishedValue: String? = null

    init {
        if (compiledSchema == null) {
            val requiredDomain = checkNotNull(domain)
            requireCanonicalIdentityValue(requiredDomain, "Canonical hash domain")
            field("domain", requiredDomain)
            field("canonicalVersion", "1")
        } else {
            check(domain == null && !cacheRepeatedFieldNames)
            digest.update(compiledSchema.canonicalPrefix)
        }
    }

    fun field(name: String, value: String): CanonicalSha256 = apply {
        requireOpen()
        appendPresentStringField(fieldNameFrame(name), value)
    }

    fun field(name: String, value: Long): CanonicalSha256 = apply {
        requireOpen()
        appendPresentLongField(fieldNameFrame(name), value)
    }

    fun field(name: String, value: Int): CanonicalSha256 =
        field(name, value.toLong())

    fun field(name: String, value: Boolean): CanonicalSha256 = apply {
        requireOpen()
        appendFramedField(
            fieldNameFrame = fieldNameFrame(name),
            presenceFrame = PRESENT_FRAME,
            valueFrame = if (value) TRUE_FRAME else FALSE_FRAME,
        )
    }

    fun nullableField(name: String, value: String?): CanonicalSha256 = apply {
        requireOpen()
        val fieldNameFrame = fieldNameFrame(name)
        if (value == null) {
            appendFramedField(fieldNameFrame, NULL_FRAME)
        } else {
            appendPresentStringField(fieldNameFrame, value)
        }
    }

    fun nullableLongField(name: String, value: Long?): CanonicalSha256 = apply {
        requireOpen()
        val fieldNameFrame = fieldNameFrame(name)
        if (value == null) {
            appendFramedField(fieldNameFrame, NULL_FRAME)
        } else {
            appendPresentLongField(fieldNameFrame, value)
        }
    }

    fun finish(): String {
        finishedValue?.let { return it }
        return finishBytes().toLowercaseHex().also { finishedValue = it }
    }

    /**
     * Seals this digest and compares it with one strict lowercase SHA-256 value without allocating
     * the usual hexadecimal result string.
     */
    fun finishMatchesHex(expectedLowercaseHex: String): Boolean {
        require(
            expectedLowercaseHex.length == SHA_256_HEX_LENGTH &&
                expectedLowercaseHex.all(::isLowercaseHex),
        ) {
            "Expected fingerprint must be exactly 64 lowercase hexadecimal characters"
        }
        val actual = finishBytes()
        var difference = 0
        actual.forEachIndexed { index, byte ->
            val expected =
                (hexNibble(expectedLowercaseHex[index * 2]) shl 4) or
                    hexNibble(expectedLowercaseHex[index * 2 + 1])
            difference = difference or ((byte.toInt() and 0xff) xor expected)
        }
        return difference == 0
    }

    private fun requireOpen() {
        check(finishedBytes == null) {
            "Canonical hash is already finished"
        }
    }

    private fun finishBytes(): ByteArray {
        finishedBytes?.let { return it }
        val schema = compiledSchema
        check(schema == null || compiledFieldIndex == schema.fieldNames.size) {
            "Canonical compiled schema is missing one or more fields"
        }
        return digest.digest().also { finishedBytes = it }
    }

    private fun appendPresentStringField(
        fieldNameFrame: ByteArray,
        value: String,
    ) {
        val buffer = CANONICAL_VALUE_BUFFER.get()
        val valueOffset = fieldNameFrame.size + PRESENT_FRAME.size + Int.SIZE_BYTES
        if (valueOffset + value.length <= buffer.size) {
            fieldNameFrame.copyInto(buffer)
            PRESENT_FRAME.copyInto(buffer, destinationOffset = fieldNameFrame.size)
            var index = 0
            while (index < value.length) {
                val code = value[index].code
                if (code > ASCII_MAX) break
                buffer[valueOffset + index] = code.toByte()
                index += 1
            }
            if (index == value.length) {
                writeLength(buffer, valueOffset - Int.SIZE_BYTES, value.length)
                digest.update(buffer, 0, valueOffset + value.length)
                return
            }
        }
        appendPresentBytesField(
            fieldNameFrame = fieldNameFrame,
            valueBytes = value.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun appendPresentLongField(
        fieldNameFrame: ByteArray,
        value: Long,
    ) {
        val buffer = CANONICAL_VALUE_BUFFER.get()
        fieldNameFrame.copyInto(buffer)
        PRESENT_FRAME.copyInto(buffer, destinationOffset = fieldNameFrame.size)
        val lengthOffset = fieldNameFrame.size + PRESENT_FRAME.size
        val valueOffset = lengthOffset + Int.SIZE_BYTES
        val valueLength = decimalLength(value)
        writeLength(buffer, lengthOffset, valueLength)
        var position = valueOffset + valueLength
        if (value == 0L) {
            buffer[--position] = '0'.code.toByte()
        } else {
            var remaining = value
            while (remaining != 0L) {
                val quotient = remaining / 10L
                val negativeDigit = (remaining - quotient * 10L).toInt()
                buffer[--position] =
                    (
                        '0'.code +
                            if (negativeDigit < 0) -negativeDigit else negativeDigit
                    ).toByte()
                remaining = quotient
            }
            if (value < 0L) {
                buffer[--position] = '-'.code.toByte()
            }
        }
        check(position == valueOffset)
        digest.update(buffer, 0, valueOffset + valueLength)
    }

    private fun appendPresentBytesField(
        fieldNameFrame: ByteArray,
        valueBytes: ByteArray,
    ) {
        val totalSize =
            fieldNameFrame.size +
                PRESENT_FRAME.size +
                Int.SIZE_BYTES +
                valueBytes.size
        val buffer = CANONICAL_VALUE_BUFFER.get()
        if (totalSize <= buffer.size) {
            fieldNameFrame.copyInto(buffer)
            PRESENT_FRAME.copyInto(buffer, destinationOffset = fieldNameFrame.size)
            val lengthOffset = fieldNameFrame.size + PRESENT_FRAME.size
            writeLength(buffer, lengthOffset, valueBytes.size)
            valueBytes.copyInto(
                buffer,
                destinationOffset = lengthOffset + Int.SIZE_BYTES,
            )
            digest.update(buffer, 0, totalSize)
            return
        }
        digest.update(fieldNameFrame)
        digest.update(PRESENT_FRAME)
        lengthPrefix[0] = (valueBytes.size ushr 24).toByte()
        lengthPrefix[1] = (valueBytes.size ushr 16).toByte()
        lengthPrefix[2] = (valueBytes.size ushr 8).toByte()
        lengthPrefix[3] = valueBytes.size.toByte()
        digest.update(lengthPrefix)
        digest.update(valueBytes)
    }

    private fun appendFramedField(
        fieldNameFrame: ByteArray,
        presenceFrame: ByteArray,
        valueFrame: ByteArray? = null,
    ) {
        val totalSize = fieldNameFrame.size + presenceFrame.size + (valueFrame?.size ?: 0)
        val buffer = CANONICAL_VALUE_BUFFER.get()
        if (totalSize <= buffer.size) {
            fieldNameFrame.copyInto(buffer)
            presenceFrame.copyInto(buffer, destinationOffset = fieldNameFrame.size)
            valueFrame?.copyInto(
                buffer,
                destinationOffset = fieldNameFrame.size + presenceFrame.size,
            )
            digest.update(buffer, 0, totalSize)
            return
        }
        digest.update(fieldNameFrame)
        digest.update(presenceFrame)
        valueFrame?.let(digest::update)
    }

    private fun fieldNameFrame(name: String): ByteArray {
        compiledSchema?.let { schema ->
            check(compiledFieldIndex < schema.fieldNames.size) {
                "Canonical compiled schema received an extra field"
            }
            check(name == schema.fieldNames[compiledFieldIndex]) {
                "Canonical compiled schema field order changed"
            }
            return schema.fieldNameFrames[compiledFieldIndex++]
        }
        val cache = fieldNameFrames
        if (cache != null) {
            cache[name]?.let { cached ->
                return cached
            }
        }
        requireCanonicalIdentityValue(name, "Canonical field name")
        val encoded = canonicalFrame(name)
        if (cache != null && cache.size < MAX_CACHED_FIELD_NAMES) {
            cache[name] = encoded
        }
        return encoded
    }

    companion object {
        /**
         * Avoids re-encoding a small, fixed field-name schema in long rolling digests.
         *
         * Short-lived hashes intentionally use the public constructor so each value hash does not
         * allocate and populate a cache whose names are only visited once.
         */
        fun repeatingSchema(domain: String): CanonicalSha256 =
            CanonicalSha256(
                domain = domain,
                cacheRepeatedFieldNames = true,
                compiledSchema = null,
            )

        /**
         * Compiles immutable protocol metadata for high-volume fixed-layout records.
         *
         * Only the domain and field names are retained. Values always enter a fresh digest and are
         * never stored by the schema.
         */
        fun compileSchema(
            domain: String,
            vararg fieldNames: String,
        ): CompiledSchema {
            requireCanonicalIdentityValue(domain, "Canonical hash domain")
            require(fieldNames.isNotEmpty() && fieldNames.size <= MAX_COMPILED_FIELD_NAMES) {
                "Canonical compiled schema must contain 1..$MAX_COMPILED_FIELD_NAMES fields"
            }
            fieldNames.forEach { name ->
                requireCanonicalIdentityValue(name, "Canonical field name")
            }
            return CompiledSchema(
                canonicalPrefix =
                    canonicalPresentField("domain", domain) +
                        canonicalPresentField("canonicalVersion", "1"),
                fieldNames = fieldNames.toList(),
                fieldNameFrames = fieldNames.map(::canonicalFrame),
            )
        }
    }

    class CompiledSchema internal constructor(
        internal val canonicalPrefix: ByteArray,
        internal val fieldNames: List<String>,
        internal val fieldNameFrames: List<ByteArray>,
    ) {
        fun newDigest(): CanonicalSha256 =
            CanonicalSha256(
                domain = null,
                cacheRepeatedFieldNames = false,
                compiledSchema = this,
            )
    }
}

private fun canonicalPresentField(
    name: String,
    value: String,
): ByteArray = canonicalFrame(name) + PRESENT_FRAME + canonicalFrame(value)

private fun writeLength(
    destination: ByteArray,
    offset: Int,
    size: Int,
) {
    destination[offset] = (size ushr 24).toByte()
    destination[offset + 1] = (size ushr 16).toByte()
    destination[offset + 2] = (size ushr 8).toByte()
    destination[offset + 3] = size.toByte()
}

private fun decimalLength(value: Long): Int {
    if (value == 0L) return 1
    var length = if (value < 0L) 1 else 0
    var remaining = value
    while (remaining != 0L) {
        length += 1
        remaining /= 10L
    }
    return length
}

private fun canonicalFrame(value: String): ByteArray {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    return ByteArray(Int.SIZE_BYTES + bytes.size).also { framed ->
        framed[0] = (bytes.size ushr 24).toByte()
        framed[1] = (bytes.size ushr 16).toByte()
        framed[2] = (bytes.size ushr 8).toByte()
        framed[3] = bytes.size.toByte()
        bytes.copyInto(framed, destinationOffset = Int.SIZE_BYTES)
    }
}

private fun ByteArray.toLowercaseHex(): String {
    val encoded = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        encoded[index * 2] = LOWERCASE_HEX[value ushr 4]
        encoded[index * 2 + 1] = LOWERCASE_HEX[value and 0x0f]
    }
    return encoded.concatToString()
}

private fun isLowercaseHex(char: Char): Boolean =
    char in '0'..'9' || char in 'a'..'f'

private fun hexNibble(char: Char): Int =
    if (char <= '9') char.code - '0'.code else char.code - 'a'.code + 10

/** Shared identity for a captured question before it is committed to the mistake collection. */
object CapturedTutorProblemIdentity {
    const val fingerprintVersion = "captured-question-v1"

    fun questionFingerprint(draftId: String): String {
        requireCanonicalIdentityValue(draftId, "Captured tutor draft id")
        return CanonicalSha256(fingerprintVersion)
            .field("draftId", draftId)
            .finish()
    }
}

private fun requireCanonicalIdentityValue(value: String, label: String) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= 256 &&
            value.none(Char::isISOControl),
    ) { "$label must be a trimmed non-blank value of at most 256 characters" }
}

private const val LOWERCASE_HEX = "0123456789abcdef"
private const val MAX_CACHED_FIELD_NAMES = 128
private const val MAX_COMPILED_FIELD_NAMES = 1024
private const val CANONICAL_VALUE_BUFFER_SIZE = 2_048
private const val ASCII_MAX = 0x7f
private const val SHA_256_HEX_LENGTH = 64
private val CANONICAL_VALUE_BUFFER =
    ThreadLocal.withInitial { ByteArray(CANONICAL_VALUE_BUFFER_SIZE) }
private val PRESENT_FRAME = canonicalFrame("PRESENT")
private val NULL_FRAME = canonicalFrame("NULL")
private val TRUE_FRAME = canonicalFrame("true")
private val FALSE_FRAME = canonicalFrame("false")
