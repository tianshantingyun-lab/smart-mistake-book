package com.tingyun.smartmistakebook.core.data.backup

import com.tingyun.smartmistakebook.core.domain.BackupManifestSummary
import com.tingyun.smartmistakebook.core.domain.BackupReceipt
import com.tingyun.smartmistakebook.core.domain.BackupValidation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
internal data class SmbkManifestV1(
    @SerialName("formatVersion")
    val formatVersion: Int = FORMAT_VERSION,
    @SerialName("databaseSchemaVersion")
    val databaseSchemaVersion: Int,
    @SerialName("createdAt")
    val createdAtEpochMillis: Long,
    @SerialName("problemCount")
    val problemCount: Int,
    @SerialName("assetCount")
    val assetCount: Int,
    @SerialName("hashAlgorithm")
    val hashAlgorithm: String = "SHA-256",
    @SerialName("files")
    val files: List<SmbkFileRecord>,
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
internal data class SmbkFileRecord(
    val path: String,
    @SerialName("sha256")
    val sha256: String,
    @SerialName("byteSize")
    val byteSize: Long,
)

/**
 * Resource budgets enforced while validating untrusted archives. The defaults
 * are the production limits; tests may inject smaller values to exercise the
 * rejection branches without moving hundreds of megabytes.
 */
internal data class SmbkResourceLimits(
    /** Maximum number of entries allowed in a backup archive. */
    val maxEntryCount: Int = 10_000,
    /** Maximum size of a single decompressed entry (100 MB). */
    val maxSingleEntryBytes: Long = 100L * 1024 * 1024,
    /** Maximum total decompressed size of all entries (500 MB). */
    val maxTotalDecompressedBytes: Long = 500L * 1024 * 1024,
    /** Maximum manifest text length (1 MB). */
    val maxManifestChars: Int = 1_000_000,
    /** Maximum allowed compression ratio (uncompressed / compressed). */
    val maxCompressionRatio: Double = 100.0,
    /** Maximum size of the compressed archive itself (1 GB). */
    val maxCompressedBytes: Long = 1L * 1024 * 1024 * 1024,
) {
    companion object {
        val DEFAULT = SmbkResourceLimits()
    }
}

/** Structural corruption of an untrusted archive (truncation, bad headers...). */
private class ArchiveIntegrityException(message: String) : Exception(message)

/** One central-directory record cross-checked against its local header. */
private data class SmbkZipRecord(
    val name: String,
    val method: Int,
    val crc: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val dataOffset: Long,
)

internal object SmbkArchiveCodec {
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val DATABASE_ENTRY = "database.sqlite"
    private const val ASSET_PREFIX = "assets/"
    private const val CHECKSUM_ENTRY = "checksums.sha256"

    private const val LOCAL_HEADER_SIG = 0x04034b50L
    private const val CENTRAL_HEADER_SIG = 0x02014b50L
    private const val ZIP64_EOCD_LOCATOR_SIG = 0x07064b50L

    private val json = Json { ignoreUnknownKeys = false }

    fun create(
        archive: OutputStream,
        database: File,
        assets: List<File>,
        databaseSchemaVersion: Int,
        problemCount: Int,
        createdAtEpochMillis: Long,
    ): BackupReceipt {
        require(database.isFile) { "Database backup source is missing" }
        assets.forEach { asset ->
            require(asset.isFile && asset.length() > 0L) {
                "Canonical asset backup source is missing"
            }
        }
        val databaseRecord = SmbkFileRecord(
            path = DATABASE_ENTRY,
            sha256 = sha256(database),
            byteSize = database.length(),
        )
        val assetRecords = assets.map { asset ->
            SmbkFileRecord(
                path = "$ASSET_PREFIX${asset.name}",
                sha256 = sha256(asset),
                byteSize = asset.length(),
            )
        }
        val manifest = SmbkManifestV1(
            databaseSchemaVersion = databaseSchemaVersion,
            createdAtEpochMillis = createdAtEpochMillis,
            problemCount = problemCount,
            assetCount = assetRecords.size,
            files = listOf(databaseRecord) + assetRecords,
        )
        val checksumLines = manifest.files.joinToString(separator = "\n") { record ->
            "${record.sha256} *${record.path}"
        }

        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(json.encodeToString(SmbkManifestV1.serializer(), manifest).toByteArray())
            zip.closeEntry()

            copyEntry(zip, DATABASE_ENTRY, database)
            assets.forEach { asset ->
                copyEntry(zip, "$ASSET_PREFIX${asset.name}", asset)
            }

            zip.putNextEntry(ZipEntry(CHECKSUM_ENTRY))
            zip.write(checksumLines.toByteArray())
            zip.closeEntry()
        }

        return BackupReceipt(
            createdAtEpochMillis = createdAtEpochMillis,
            databaseSchemaVersion = databaseSchemaVersion,
            problemCount = problemCount,
            assetCount = assetRecords.size,
            fileCount = manifest.files.size,
            totalBytes = database.length() + assets.sumOf(File::length),
        )
    }

    /**
     * Validates an untrusted archive WITHOUT trusting java.util.zip's lenient
     * streaming parser. ZipInputStream silently accepts truncated archives
     * (an entry whose deflate stream still completes through the shared
     * compression dictionary) and ignores local-header CRC corruption, so
     * hostile fixtures would pass a stream-only check. Instead:
     *   - the whole archive is spooled to a bounded scratch file;
     *   - the end-of-central-directory record must exist and be complete,
     *     which rejects every truncated archive;
     *   - every central-directory record is cross-checked against its local
     *     header (CRC / sizes), which rejects header-level tampering;
     *   - each entry is decompressed while computing CRC-32 and SHA-256 and
     *     must match the central-directory CRC, the manifest size and the
     *     manifest/checksum SHA-256 — data-level tampering is rejected;
     *   - entry count, single-entry size, total decompressed size and the
     *     compression ratio (zip-bomb defence) are enforced from the exact
     *     central-directory sizes.
     */
    fun validate(
        archive: InputStream,
        scratchDir: File = createTempDir("smbk-validate").also(File::deleteOnExit),
        limits: SmbkResourceLimits = SmbkResourceLimits.DEFAULT,
    ): BackupValidation {
        require(scratchDir.isDirectory || scratchDir.mkdirs()) {
            "Cannot create backup validation scratch directory"
        }
        val scratchFiles = mutableListOf<File>()
        return try {
            val spooled = spool(archive, File(scratchDir, "archive.smbk"), limits)
            scratchFiles += spooled
            RandomAccessFile(spooled, "r").use { raf ->
                val records = parseZipStructure(raf, limits)
                val digests = mutableMapOf<String, String>()
                val sizes = mutableMapOf<String, Long>()
                var manifestText: String? = null
                var checksumText: String? = null
                var totalDecompressed = 0L
                records.forEach { record ->
                    when (record.name) {
                        MANIFEST_ENTRY -> manifestText = expandText(raf, record, limits)
                        CHECKSUM_ENTRY -> checksumText = expandText(raf, record, limits)
                        else -> {
                            val result = expandEntry(raf, record, target = null, limits = limits)
                            totalDecompressed = checkTotalBudget(
                                totalDecompressed,
                                result.bytesWritten,
                                limits,
                            )
                            digests[record.name] = result.sha256Hex
                            sizes[record.name] = result.bytesWritten
                        }
                    }
                }
                finalizeValidation(manifestText, checksumText, digests, sizes)
            }
        } catch (failure: ArchiveIntegrityException) {
            BackupValidation.Invalid(failure.message.orEmpty())
        } catch (failure: Exception) {
            BackupValidation.Invalid("备份校验失败：${failure.message.orEmpty()}")
        } finally {
            scratchFiles.forEach { file -> file.delete() }
            // Safety sweep: remove any partial scratch artifact.
            scratchDir.listFiles { file -> file.name.startsWith("archive.") }
                .orEmpty()
                .forEach { file -> file.delete() }
        }
    }

    /**
     * Runs the same structural verification as [validate] and additionally
     * writes the decompressed database and assets into [destinationDir] so a
     * restore can continue from staged files.
     */
    fun unpack(
        archive: InputStream,
        destinationDir: File,
        limits: SmbkResourceLimits = SmbkResourceLimits.DEFAULT,
    ): BackupValidation {
        require(destinationDir.isDirectory || destinationDir.mkdirs()) {
            "Cannot create backup staging directory"
        }
        val staged = mutableListOf<File>()
        fun cleanupStaging() {
            staged.forEach { file -> file.delete() }
            File(destinationDir, "assets").listFiles().orEmpty().forEach { it.delete() }
            File(destinationDir, DATABASE_ENTRY).delete()
        }
        return try {
            val spooled = spool(archive, File(destinationDir, ".archive.tmp"), limits)
            staged += spooled
            RandomAccessFile(spooled, "r").use { raf ->
                val records = parseZipStructure(raf, limits)
                val digests = mutableMapOf<String, String>()
                val sizes = mutableMapOf<String, Long>()
                var manifestText: String? = null
                var checksumText: String? = null
                var totalDecompressed = 0L
                records.forEach { record ->
                    when (record.name) {
                        MANIFEST_ENTRY -> manifestText = expandText(raf, record, limits)
                        CHECKSUM_ENTRY -> checksumText = expandText(raf, record, limits)
                        else -> {
                            val target = destinationFile(destinationDir, record.name)
                                ?: throw ArchiveIntegrityException(
                                    "备份包含非法路径 ${record.name}",
                                )
                            val result = expandEntry(raf, record, target, limits)
                            staged += target
                            totalDecompressed = checkTotalBudget(
                                totalDecompressed,
                                result.bytesWritten,
                                limits,
                            )
                            digests[record.name] = result.sha256Hex
                            sizes[record.name] = result.bytesWritten
                        }
                    }
                }
                finalizeValidation(manifestText, checksumText, digests, sizes)
            }
        } catch (failure: ArchiveIntegrityException) {
            cleanupStaging()
            BackupValidation.Invalid(failure.message.orEmpty())
        } catch (failure: Exception) {
            cleanupStaging()
            BackupValidation.Invalid("备份解包失败：${failure.message.orEmpty()}")
        } finally {
            File(destinationDir, ".archive.tmp").delete()
        }
    }

    // ------------------------------------------------------------------
    // Shared verification pipeline
    // ------------------------------------------------------------------

    /** Copies the untrusted stream to [target], bounded by [SmbkResourceLimits.maxCompressedBytes]. */
    private fun spool(archive: InputStream, target: File, limits: SmbkResourceLimits): File {
        var written = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = archive.read(buffer)
                if (read < 0) break
                written += read
                if (written > limits.maxCompressedBytes) {
                    throw ArchiveIntegrityException(
                        "压缩归档体积超过上限 (${limits.maxCompressedBytes} 字节)",
                    )
                }
                output.write(buffer, 0, read)
            }
        }
        if (written == 0L) {
            throw ArchiveIntegrityException("备份归档为空")
        }
        return target
    }

    /**
     * Locates the EOCD, walks the central directory and cross-checks every
     * record against its local header. Rejects truncated archives (missing or
     * incomplete EOCD / central directory), zip64 archives, duplicate entries,
     * unsupported compression methods and inconsistent header metadata.
     */
    private fun parseZipStructure(raf: RandomAccessFile, limits: SmbkResourceLimits): List<SmbkZipRecord> {
        val length = raf.length()
        val eocdOffset = findEocd(raf, length)
        raf.seek(eocdOffset + 4)
        val diskNumber = readUShort(raf)
        val cdDisk = readUShort(raf)
        val diskEntries = readUShort(raf)
        val totalEntries = readUShort(raf)
        val cdSize = readUInt(raf)
        val cdOffset = readUInt(raf)
        if (diskNumber != 0 || cdDisk != 0 || diskEntries != totalEntries) {
            throw ArchiveIntegrityException("不支持分卷归档")
        }
        if (
            totalEntries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL ||
            totalEntries == 0 || cdOffset + cdSize > eocdOffset
        ) {
            throw ArchiveIntegrityException("归档中央目录不完整或已截断")
        }
        if (totalEntries > limits.maxEntryCount) {
            throw ArchiveIntegrityException("归档条目数量超过上限 (${limits.maxEntryCount})")
        }

        val records = ArrayList<SmbkZipRecord>(totalEntries)
        val seen = HashSet<String>(totalEntries)
        var cursor = cdOffset
        repeat(totalEntries) {
            raf.seek(cursor)
            if (readUInt(raf) != CENTRAL_HEADER_SIG) {
                throw ArchiveIntegrityException("归档中央目录已损坏")
            }
            raf.skipBytes(6) // version made by + version needed + flags
            val method = readUShort(raf)
            raf.skipBytes(4) // mod time/date
            val crc = readUInt(raf)
            val compressedSize = readUInt(raf)
            val uncompressedSize = readUInt(raf)
            val nameLength = readUShort(raf)
            val extraLength = readUShort(raf)
            val commentLength = readUShort(raf)
            raf.skipBytes(4) // disk number + internal attributes
            val externalAttributes = readUInt(raf)
            if (externalAttributes == 0xFFFFFFFFL) {
                throw ArchiveIntegrityException("不支持 ZIP64 归档")
            }
            val localOffset = readUInt(raf)
            if (
                compressedSize == 0xFFFFFFFFL || uncompressedSize == 0xFFFFFFFFL ||
                localOffset == 0xFFFFFFFFL
            ) {
                throw ArchiveIntegrityException("不支持 ZIP64 归档")
            }
            if (nameLength > MAX_ENTRY_NAME_LENGTH) {
                throw ArchiveIntegrityException("归档条目名过长")
            }
            val name = String(raf.readBytes(nameLength), Charsets.UTF_8)
            if (name.isEmpty() || name.contains("..") || name.startsWith("/")) {
                throw ArchiveIntegrityException("非法路径: $name")
            }
            if (!seen.add(name)) {
                throw ArchiveIntegrityException("重复的归档条目: $name")
            }
            if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                throw ArchiveIntegrityException("不支持的压缩方式: $name")
            }
            val dataOffset = verifyLocalHeader(
                raf,
                localOffset,
                name,
                crc,
                compressedSize,
                uncompressedSize,
            )
            if (dataOffset + compressedSize > length) {
                throw ArchiveIntegrityException("归档已截断: $name")
            }
            records += SmbkZipRecord(
                name = name,
                method = method,
                crc = crc,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                dataOffset = dataOffset,
            )
            cursor = raf.filePointer + (extraLength + commentLength).toLong()
            if (cursor > length) {
                throw ArchiveIntegrityException("归档中央目录已损坏")
            }
        }
        if (cursor != eocdOffset) {
            throw ArchiveIntegrityException("归档中央目录与目录结束记录不一致")
        }
        return records
    }

    /**
     * Verifies the local header of one entry against the central directory.
     * A tampered local header (e.g. a flipped CRC byte) is invisible to
     * ZipInputStream-based validators, so it is rejected here instead.
     * Returns the absolute offset of the compressed data.
     */
    private fun verifyLocalHeader(
        raf: RandomAccessFile,
        localOffset: Long,
        name: String,
        crc: Long,
        compressedSize: Long,
        uncompressedSize: Long,
    ): Long {
        if (localOffset < 0 || localOffset + 30 > raf.length()) {
            throw ArchiveIntegrityException("归档已截断: $name")
        }
        raf.seek(localOffset)
        if (readUInt(raf) != LOCAL_HEADER_SIG) {
            throw ArchiveIntegrityException("归档已截断: $name")
        }
        raf.skipBytes(4) // version + flags
        val method = readUShort(raf)
        raf.skipBytes(4) // mod time/date
        val localCrc = readUInt(raf)
        val localCompressed = readUInt(raf)
        val localUncompressed = readUInt(raf)
        val localNameLength = readUShort(raf)
        val extraLength = readUShort(raf)
        if (localNameLength > MAX_ENTRY_NAME_LENGTH) {
            throw ArchiveIntegrityException("归档条目名过长: $name")
        }
        val localName = String(raf.readBytes(localNameLength), Charsets.UTF_8)
        if (localName != name) {
            throw ArchiveIntegrityException("归档条目名不一致: $name")
        }
        if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
            throw ArchiveIntegrityException("不支持的压缩方式: $name")
        }
        // With a data descriptor (general purpose bit 3) the local header may
        // legitimately carry zeros; any NON-zero value must still match the
        // central directory, otherwise the header was tampered with.
        if (localCrc != 0L && localCrc != crc) {
            throw ArchiveIntegrityException("归档头部校验和不一致: $name")
        }
        if (localCompressed != 0L && localCompressed != compressedSize) {
            throw ArchiveIntegrityException("归档头部大小不一致: $name")
        }
        if (localUncompressed != 0L && localUncompressed != uncompressedSize) {
            throw ArchiveIntegrityException("归档头部大小不一致: $name")
        }
        return localOffset + 30 + localNameLength + extraLength
    }

    /** Scans backwards for a complete EOCD record; rejects truncated archives. */
    private fun findEocd(raf: RandomAccessFile, length: Long): Long {
        if (length < 22) {
            throw ArchiveIntegrityException("归档已截断：缺少中央目录记录")
        }
        val searchLength = minOf(length, 22L + 65_536L).toInt()
        val tail = ByteArray(searchLength)
        raf.seek(length - searchLength)
        raf.readFully(tail)
        for (index in tail.size - 22 downTo 0) {
            if (
                tail[index] == 0x50.toByte() &&
                tail[index + 1] == 0x4B.toByte() &&
                tail[index + 2] == 0x05.toByte() &&
                tail[index + 3] == 0x06.toByte()
            ) {
                val commentLength = (tail[index + 20].toInt() and 0xFF) or
                    ((tail[index + 21].toInt() and 0xFF) shl 8)
                val expectedLength = index + 22 + commentLength
                if (expectedLength == tail.size) {
                    if (index >= 20) {
                        val maybeLocator = (tail[index - 20].toInt() and 0xFF) or
                            ((tail[index - 19].toInt() and 0xFF) shl 8) or
                            ((tail[index - 18].toInt() and 0xFF) shl 16) or
                            ((tail[index - 17].toInt() and 0xFF) shl 24)
                        if (maybeLocator == ZIP64_EOCD_LOCATOR_SIG) {
                            throw ArchiveIntegrityException("不支持 ZIP64 归档")
                        }
                    }
                    return length - searchLength + index
                }
            }
        }
        throw ArchiveIntegrityException("归档已截断：缺少中央目录记录")
    }

    private data class ExpandedEntry(
        val bytesWritten: Long,
        val sha256Hex: String,
        val plain: ByteArray? = null,
    )

    private fun checkTotalBudget(current: Long, added: Long, limits: SmbkResourceLimits): Long {
        val total = current + added
        if (total > limits.maxTotalDecompressedBytes) {
            throw ArchiveIntegrityException("解压后总大小超过上限")
        }
        return total
    }

    /** Expands a metadata entry (manifest/checksums) fully into text. */
    private fun expandText(raf: RandomAccessFile, record: SmbkZipRecord, limits: SmbkResourceLimits): String {
        if (record.uncompressedSize > limits.maxManifestChars.toLong() * 4L) {
            throw ArchiveIntegrityException("${record.name} 内容过长")
        }
        val result = expandEntry(raf, record, target = null, limits = limits, keepPlain = true)
        return String(result.plain ?: ByteArray(0), Charsets.UTF_8)
    }

    /**
     * Decompresses one entry, verifying CRC-32, the declared size and the
     * compression-ratio ceiling. Optionally writes the plaintext to [target].
     */
    private fun expandEntry(
        raf: RandomAccessFile,
        record: SmbkZipRecord,
        target: File?,
        limits: SmbkResourceLimits,
        keepPlain: Boolean = false,
    ): ExpandedEntry {
        if (record.uncompressedSize > limits.maxSingleEntryBytes) {
            throw ArchiveIntegrityException("${record.name} 超过单条目解压大小上限")
        }
        if (record.compressedSize > 0 && record.uncompressedSize > 0) {
            val ratio = record.uncompressedSize.toDouble() / record.compressedSize
            if (ratio > limits.maxCompressionRatio) {
                throw ArchiveIntegrityException(
                    "${record.name} 压缩比异常（1:${"%.0f".format(ratio)}），疑似压缩炸弹",
                )
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val plainBuffer = if (keepPlain) ByteArrayOutputStream(record.uncompressedSize.toInt()) else null
        var written = 0L
        raf.seek(record.dataOffset)
        val rawStream = RafInputStream(raf, record.compressedSize)
        val plainStream: InputStream = when (record.method) {
            ZipEntry.STORED -> BufferedInputStream(rawStream)
            else -> InflaterInputStream(BufferedInputStream(rawStream), Inflater(true))
        }
        plainStream.use { input ->
            val output = target?.outputStream()
            try {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        written += read
                        if (written > record.uncompressedSize ||
                            written > limits.maxSingleEntryBytes
                        ) {
                            throw ArchiveIntegrityException("${record.name} 大小不一致")
                        }
                        digest.update(buffer, 0, read)
                        crc.update(buffer, 0, read)
                        output?.write(buffer, 0, read)
                        plainBuffer?.write(buffer, 0, read)
                    }
                }
            } catch (integrity: ArchiveIntegrityException) {
                throw integrity
            } catch (failure: Exception) {
                throw ArchiveIntegrityException("${record.name} 解压失败：${failure.message.orEmpty()}")
            } finally {
                output?.close()
            }
        }
        if (written != record.uncompressedSize) {
            throw ArchiveIntegrityException("${record.name} 大小不一致")
        }
        if (crc.value != record.crc) {
            throw ArchiveIntegrityException("${record.name} CRC 校验失败")
        }
        return ExpandedEntry(
            bytesWritten = written,
            sha256Hex = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
            plain = plainBuffer?.toByteArray(),
        )
    }

    /** Bounds the read of one entry's compressed data inside the spooled file. */
    private class RafInputStream(
        private val raf: RandomAccessFile,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = raf.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val read = raf.read(b, off, toRead)
            if (read > 0) remaining -= read
            return read
        }
    }

    /** Manifest / checksums cross-validation shared by [validate] and [unpack]. */
    private fun finalizeValidation(
        manifestText: String?,
        checksumText: String?,
        stagedDigests: Map<String, String>,
        stagedSizes: Map<String, Long>,
    ): BackupValidation {
        val text = manifestText ?: return BackupValidation.Invalid("缺少 manifest.json")
        if (text.length > SmbkResourceLimits.DEFAULT.maxManifestChars) {
            return BackupValidation.Invalid("manifest 内容过长")
        }
        val parsed = try {
            json.decodeFromString(SmbkManifestV1.serializer(), text)
        } catch (failure: Exception) {
            return BackupValidation.Invalid("manifest 解析失败：${failure.message.orEmpty()}")
        }
        if (parsed.formatVersion != SmbkManifestV1.FORMAT_VERSION) {
            return BackupValidation.Invalid("备份格式版本过新，暂不支持恢复")
        }
        if (parsed.hashAlgorithm != "SHA-256") {
            return BackupValidation.Invalid("不支持的校验算法")
        }
        val expectedPaths = parsed.files.map { it.path }.toSet()
        if (expectedPaths != stagedSizes.keys) {
            return BackupValidation.Invalid("备份文件列表与内容不一致")
        }
        val checksums = mutableMapOf<String, String>()
        checksumText.orEmpty().lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val separator = line.indexOf(" *")
            if (separator > 0) {
                checksums[line.substring(separator + 2)] = line.substring(0, separator)
            }
        }
        if (checksumText == null || checksums.isEmpty()) {
            return BackupValidation.Invalid("缺少 checksums.sha256")
        }
        if (checksums != parsed.files.associate { it.path to it.sha256 }) {
            return BackupValidation.Invalid("checksums.sha256 与 manifest 不一致")
        }
        parsed.files.forEach { record ->
            val size = stagedSizes[record.path]
                ?: return BackupValidation.Invalid("缺少 ${record.path}")
            if (size != record.byteSize) {
                return BackupValidation.Invalid("${record.path} 大小不一致")
            }
            if (stagedDigests[record.path] != record.sha256) {
                return BackupValidation.Invalid("${record.path} 校验失败")
            }
        }
        return BackupValidation.Valid(
            manifest = BackupManifestSummary(
                formatVersion = parsed.formatVersion,
                databaseSchemaVersion = parsed.databaseSchemaVersion,
                createdAtEpochMillis = parsed.createdAtEpochMillis,
                problemCount = parsed.problemCount,
                assetCount = parsed.assetCount,
                fileCount = parsed.files.size,
            ),
            checkedFileCount = parsed.files.size,
            totalBytes = parsed.files.sumOf { it.byteSize },
        )
    }

    // ------------------------------------------------------------------
    // Small binary helpers
    // ------------------------------------------------------------------

    private fun readUShort(raf: RandomAccessFile): Int {
        val buffer = ByteArray(2)
        raf.readFully(buffer)
        return (buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8)
    }

    private fun readUInt(raf: RandomAccessFile): Long {
        val buffer = ByteArray(4)
        raf.readFully(buffer)
        return (buffer[0].toLong() and 0xFF) or
            ((buffer[1].toLong() and 0xFF) shl 8) or
            ((buffer[2].toLong() and 0xFF) shl 16) or
            ((buffer[3].toLong() and 0xFF) shl 24)
    }

    private fun RandomAccessFile.skipBytes(count: Int) {
        seek(filePointer + count)
    }

    private fun RandomAccessFile.readBytes(count: Int): ByteArray {
        val buffer = ByteArray(count)
        readFully(buffer)
        return buffer
    }

    private fun copyEntry(zip: ZipOutputStream, path: String, file: File) {
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) zip.write(buffer, 0, read)
            }
        }
        zip.closeEntry()
    }

    private fun destinationFile(
        destinationDir: File,
        entryName: String,
    ): File? {
        val normalized = entryName.replace('\\', '/')
        if (normalized == DATABASE_ENTRY) return File(destinationDir, DATABASE_ENTRY)
        if (!normalized.startsWith(ASSET_PREFIX)) return null
        val assetName = normalized.removePrefix(ASSET_PREFIX)
        if (
            assetName.isBlank() ||
            assetName.contains('/') ||
            assetName == "." ||
            assetName == ".."
        ) return null
        val assetsDir = File(destinationDir, "assets").apply { mkdirs() }
        return File(assetsDir, assetName)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private const val DEFAULT_BUFFER_SIZE = 8_192
    private const val MAX_ENTRY_NAME_LENGTH = 4_096
}
