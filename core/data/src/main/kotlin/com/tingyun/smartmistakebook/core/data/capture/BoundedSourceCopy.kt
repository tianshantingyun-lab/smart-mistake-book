package com.tingyun.smartmistakebook.core.data.capture

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal const val MAX_BATCH_IMPORT_STAGED_BYTES = 256L * 1024L * 1024L

internal fun copyBoundedSource(
    input: InputStream,
    output: OutputStream,
    sourceLimit: Long,
    batchLimit: Long,
): Long {
    if (batchLimit <= 0) throw IOException("Import exceeds its private staging limit")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        val next = copied + count
        if (next > sourceLimit || next > batchLimit) {
            throw IOException("Import source exceeds its private staging limit")
        }
        output.write(buffer, 0, count)
        copied = next
    }
    output.flush()
    return copied
}
