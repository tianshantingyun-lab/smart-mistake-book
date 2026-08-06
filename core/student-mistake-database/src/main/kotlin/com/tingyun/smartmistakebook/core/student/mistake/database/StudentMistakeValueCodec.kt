package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion

internal fun encodeOrderedStrings(values: Collection<String>): String {
    require(values.size <= MAX_ENCODED_STRING_FIELDS) {
        "Encoded string list has too many fields"
    }
    require(values.all { it.length <= MAX_ENCODED_STRING_FIELD_CHARS }) {
        "Encoded string-list field exceeds the supported size"
    }
    return buildString {
        append(values.size)
        append(':')
        values.forEach { value ->
            append(value.length)
            append(':')
            append(value)
        }
    }.also { wire ->
        require(wire.length <= MAX_ENCODED_STRING_WIRE_CHARS) {
            "Encoded string list exceeds the supported wire size"
        }
    }
}

internal fun decodeOrderedStrings(wire: String): List<String> {
    require(wire.length <= MAX_ENCODED_STRING_WIRE_CHARS) {
        "Encoded string list exceeds the supported wire size"
    }
    var cursor = 0

    fun readLength(maximum: Int): Int {
        val separator = wire.indexOf(':', cursor)
        check(separator >= cursor) { "Corrupt string-list wire value" }
        check(separator - cursor in 1..MAX_ENCODED_LENGTH_TOKEN_CHARS) {
            "Corrupt string-list length token"
        }
        val length = wire.substring(cursor, separator).toIntOrNull()
        check(length != null && length in 0..maximum) {
            "Corrupt or oversized string-list length"
        }
        cursor = separator + 1
        return length
    }

    val count = readLength(MAX_ENCODED_STRING_FIELDS)
    val values = ArrayList<String>(count)
    repeat(count) {
        val length = readLength(MAX_ENCODED_STRING_FIELD_CHARS)
        check(length <= wire.length - cursor) { "Truncated string-list wire value" }
        values += wire.substring(cursor, cursor + length)
        cursor += length
    }
    check(cursor == wire.length) { "Trailing bytes in string-list wire value" }
    return values
}

internal fun encodeCanonicalSet(values: Set<String>): String =
    encodeOrderedStrings(values.sorted())

internal fun encodeSelectedRegions(
    regions: List<NormalizedSourceRegion>,
): String =
    encodeOrderedStrings(
        regions.flatMap { region ->
            listOf(
                region.left.toString(),
                region.top.toString(),
                region.right.toString(),
                region.bottom.toString(),
            )
        },
    )

internal fun decodeSelectedRegions(wire: String): List<NormalizedSourceRegion> {
    val fields = decodeOrderedStrings(wire)
    check(fields.size % SELECTED_REGION_FIELD_COUNT == 0) {
        "Corrupt selected-region wire value"
    }
    check(fields.size / SELECTED_REGION_FIELD_COUNT <= MAX_SELECTED_REGION_COUNT) {
        "Selected-region wire value exceeds the supported count"
    }
    return fields.chunked(SELECTED_REGION_FIELD_COUNT).map { values ->
        val coordinates = values.map { value ->
            value.toDoubleOrNull()?.takeIf(Double::isFinite)
                ?: error("Corrupt selected-region coordinate")
        }
        NormalizedSourceRegion(
            left = coordinates[0],
            top = coordinates[1],
            right = coordinates[2],
            bottom = coordinates[3],
        ).also { region ->
            check(
                region.left in 0.0..1.0 &&
                    region.top in 0.0..1.0 &&
                    region.right in 0.0..1.0 &&
                    region.bottom in 0.0..1.0 &&
                    region.left < region.right &&
                    region.top < region.bottom,
            ) {
                "Corrupt selected-region bounds"
            }
        }
    }
}

private const val MAX_ENCODED_STRING_FIELDS = 4_096
private const val MAX_ENCODED_STRING_FIELD_CHARS = 16_384
private const val MAX_ENCODED_STRING_WIRE_CHARS = 1_048_576
private const val MAX_ENCODED_LENGTH_TOKEN_CHARS = 7
private const val SELECTED_REGION_FIELD_COUNT = 4
private const val MAX_SELECTED_REGION_COUNT = 64
