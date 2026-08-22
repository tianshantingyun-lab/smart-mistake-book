package com.tingyun.smartmistakebook.core.data.backup

import com.tingyun.smartmistakebook.core.domain.BackupManifestSummary
import com.tingyun.smartmistakebook.core.domain.BackupReceipt
import com.tingyun.smartmistakebook.core.domain.BackupValidation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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

internal object SmbkArchiveCodec {
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val DATABASE_ENTRY = "database.sqlite"
    private const val ASSET_PREFIX = "assets/"
    private const val CHECKSUM_ENTRY = "checksums.sha256"

    /** Maximum number of entries allowed in a backup archive. */
    private const val MAX_ENTRY_COUNT = 10_000

    /** Maximum size of a single decompressed entry (100 MB). */
    private const val MAX_SINGLE_ENTRY_BYTES = 100L * 1024 * 1024

    /** Maximum total decompressed size of all entries (500 MB). */
    private const val MAX_TOTAL_DECOMPRESSED_BYTES = 500L * 1024 * 1024

    /** Maximum manifest text length (1 MB). */
    private const val MAX_MANIFEST_CHARS = 1_000_000

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

    fun validate(archive: InputStream): BackupValidation {
        val entries = mutableMapOf<String, ByteArray>()
        val checksums = mutableMapOf<String, String>()
        var manifest: SmbkManifestV1? = null
        var totalDecompressedBytes = 0L
        var entryCount = 0
        val seenEntries = mutableSetOf<String>()

        ZipInputStream(archive).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break

                // Duplicate entry detection
                if (entry.name in seenEntries) {
                    return BackupValidation.Invalid("重复的归档条目: ${entry.name}")
                }
                seenEntries += entry.name

                entryCount++
                if (entryCount > MAX_ENTRY_COUNT) {
                    return BackupValidation.Invalid("归档条目数量超过上限 ($MAX_ENTRY_COUNT)")
                }

                // Path traversal check
                if (entry.name.contains("..") || entry.name.startsWith("/")) {
                    return BackupValidation.Invalid("非法路径: ${entry.name}")
                }

                // Stream entry data with size limits
                val bytes = readEntryBytes(zip, entry.name)
                    ?: return BackupValidation.Invalid("无法读取条目: ${entry.name}")

                totalDecompressedBytes += bytes.size
                if (totalDecompressedBytes > MAX_TOTAL_DECOMPRESSED_BYTES) {
                    return BackupValidation.Invalid("解压后总大小超过上限")
                }

                when (entry.name) {
                    MANIFEST_ENTRY -> {
                        val text = bytes.decodeToString()
                        if (text.length > MAX_MANIFEST_CHARS) {
                            return BackupValidation.Invalid("manifest 内容过长")
                        }
                        manifest = json.decodeFromString(
                            SmbkManifestV1.serializer(),
                            text,
                        )
                    }
                    CHECKSUM_ENTRY -> {
                        bytes.decodeToString().lineSequence().forEach { line ->
                            if (line.isBlank()) return@forEach
                            val separator = line.indexOf(" *")
                            if (separator > 0) {
                                checksums[line.substring(separator + 2)] =
                                    line.substring(0, separator)
                            }
                        }
                    }
                    else -> entries[entry.name] = bytes
                }
                zip.closeEntry()
            }
        }

        val parsed = manifest ?: return BackupValidation.Invalid("缺少 manifest.json")
        if (parsed.formatVersion != SmbkManifestV1.FORMAT_VERSION) {
            return BackupValidation.Invalid("备份格式版本过新，暂不支持恢复")
        }
        if (parsed.hashAlgorithm != "SHA-256") {
            return BackupValidation.Invalid("不支持的校验算法")
        }
        val expectedPaths = parsed.files.map { it.path }.toSet()
        if (expectedPaths != entries.keys) {
            return BackupValidation.Invalid("备份文件列表与内容不一致")
        }
        if (checksums != parsed.files.associate { it.path to it.sha256 }) {
            return BackupValidation.Invalid("checksums.sha256 与 manifest 不一致")
        }
        parsed.files.forEach { record ->
            val bytes = entries[record.path] ?: return BackupValidation.Invalid(
                "缺少 ${record.path}",
            )
            if (bytes.size.toLong() != record.byteSize) {
                return BackupValidation.Invalid("${record.path} 大小不一致")
            }
            if (sha256(bytes) != record.sha256) {
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

    private fun readEntryBytes(zip: ZipInputStream, entryName: String): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val tempBuffer = ByteArray(8192)
        var totalRead = 0
        while (true) {
            val bytesRead = zip.read(tempBuffer)
            if (bytesRead == -1) break
            totalRead += bytesRead
            if (totalRead > MAX_SINGLE_ENTRY_BYTES) {
                return null
            }
            buffer.write(tempBuffer, 0, bytesRead)
        }
        return buffer.toByteArray()
    }

    fun unpack(
        archive: InputStream,
        destinationDir: File,
    ): BackupValidation {
        require(destinationDir.isDirectory || destinationDir.mkdirs()) {
            "Cannot create backup staging directory"
        }
        val stagedFiles = mutableListOf<File>()
        val checksums = mutableMapOf<String, String>()
        var manifest: SmbkManifestV1? = null
        try {
            ZipInputStream(archive).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        MANIFEST_ENTRY -> {
                            manifest = json.decodeFromString(
                                SmbkManifestV1.serializer(),
                                zip.readBytes().decodeToString(),
                            )
                        }
                        CHECKSUM_ENTRY -> {
                            zip.readBytes().decodeToString().lineSequence().forEach { line ->
                                if (line.isBlank()) return@forEach
                                val separator = line.indexOf(" *")
                                if (separator > 0) {
                                    checksums[line.substring(separator + 2)] =
                                        line.substring(0, separator)
                                }
                            }
                        }
                        else -> {
                            val target = destinationFile(destinationDir, entry.name)
                                ?: return BackupValidation.Invalid("备份包含非法路径 ${entry.name}")
                            target.outputStream().use { output ->
                                zip.copyTo(output)
                            }
                            stagedFiles += target
                        }
                    }
                    zip.closeEntry()
                }
            }

            val parsed = manifest ?: return BackupValidation.Invalid("缺少 manifest.json")
            if (parsed.formatVersion != SmbkManifestV1.FORMAT_VERSION) {
                return BackupValidation.Invalid("备份格式版本过新，暂不支持恢复")
            }
            if (parsed.hashAlgorithm != "SHA-256") {
                return BackupValidation.Invalid("不支持的校验算法")
            }
            if (checksums != parsed.files.associate { it.path to it.sha256 }) {
                return BackupValidation.Invalid("checksums.sha256 与 manifest 不一致")
            }
            val stagedByPath = stagedFiles.associateBy { file ->
                file.relativeTo(destinationDir).invariantSeparatorsPath
            }
            parsed.files.forEach { record ->
                val file = stagedByPath[record.path]
                    ?: return BackupValidation.Invalid("缺少 ${record.path}")
                if (file.length() != record.byteSize) {
                    return BackupValidation.Invalid("${record.path} 大小不一致")
                }
                if (sha256(file) != record.sha256) {
                    return BackupValidation.Invalid("${record.path} 校验失败")
                }
            }
            if (stagedByPath.keys != parsed.files.map { it.path }.toSet()) {
                return BackupValidation.Invalid("备份文件列表与内容不一致")
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
        } catch (failure: Exception) {
            stagedFiles.forEach { file -> file.delete() }
            return BackupValidation.Invalid("备份解包失败：${failure.message.orEmpty()}")
        }
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private const val DEFAULT_BUFFER_SIZE = 8_192
}
