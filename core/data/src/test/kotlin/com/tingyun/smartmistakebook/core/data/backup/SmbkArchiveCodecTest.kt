package com.tingyun.smartmistakebook.core.data.backup

import com.tingyun.smartmistakebook.core.domain.BackupValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SmbkArchiveCodecTest {
    @Test
    fun `create and validate round trip reports exact counts and checksums`() {
        val database = tempFile("database.sqlite", "database-content")
        val asset = tempFile("asset-a.png", "asset-content")
        val archive = ByteArrayOutputStream()

        val receipt = SmbkArchiveCodec.create(
            archive = archive,
            database = database,
            assets = listOf(asset),
            databaseSchemaVersion = 29,
            problemCount = 3,
            createdAtEpochMillis = 1_000L,
        )

        assertEquals(3, receipt.problemCount)
        assertEquals(1, receipt.assetCount)
        assertEquals(2, receipt.fileCount)
        val validation = SmbkArchiveCodec.validate(
            ByteArrayInputStream(archive.toByteArray()),
        )
        assertTrue(validation is BackupValidation.Valid)
        validation as BackupValidation.Valid
        assertEquals(29, validation.manifest.databaseSchemaVersion)
        assertEquals(2, validation.checkedFileCount)
    }

    @Test
    fun `tampered asset fails validation with a business reason`() {
        val database = tempFile("database.sqlite", "database-content")
        val asset = tempFile("asset-a.png", "asset-content")
        val archive = ByteArrayOutputStream()
        SmbkArchiveCodec.create(
            archive = archive,
            database = database,
            assets = listOf(asset),
            databaseSchemaVersion = 29,
            problemCount = 1,
            createdAtEpochMillis = 1_000L,
        )

        val tampered = ByteArrayInputStream(tamperAsset(archive.toByteArray()))
        val validation = SmbkArchiveCodec.validate(tampered)

        assertTrue(validation is BackupValidation.Invalid)
    }

    @Test
    fun `unpack restores database and assets after full verification`() {
        val database = tempFile("database.sqlite", "database-content")
        val asset = tempFile("asset-a.png", "asset-content")
        val archive = ByteArrayOutputStream()
        SmbkArchiveCodec.create(
            archive = archive,
            database = database,
            assets = listOf(asset),
            databaseSchemaVersion = 30,
            problemCount = 2,
            createdAtEpochMillis = 2_000L,
        )
        val destination = File.createTempFile("smbk-unpack", "").apply {
            delete()
            mkdirs()
        }
        destination.deleteOnExit()

        val validation = SmbkArchiveCodec.unpack(
            ByteArrayInputStream(archive.toByteArray()),
            destination,
        )

        assertTrue(validation is BackupValidation.Valid)
        validation as BackupValidation.Valid
        assertEquals(30, validation.manifest.databaseSchemaVersion)
        assertEquals(
            "database-content",
            File(destination, "database.sqlite").readText(),
        )
        assertEquals(
            "asset-content",
            File(destination, "assets/${asset.name}").readText(),
        )
    }

    @Test
    fun `unpack rejects traversal paths before writing outside staging`() {
        val database = tempFile("database.sqlite", "database-content")
        val asset = tempFile("asset-a.png", "asset-content")
        val archive = ByteArrayOutputStream()
        SmbkArchiveCodec.create(
            archive = archive,
            database = database,
            assets = listOf(asset),
            databaseSchemaVersion = 30,
            problemCount = 1,
            createdAtEpochMillis = 3_000L,
        )
        val withTraversal = rewritePath(archive.toByteArray(), "../escape", "escape-content")
        val destination = File.createTempFile("smbk-unpack-safe", "").apply {
            delete()
            mkdirs()
        }
        destination.deleteOnExit()

        val validation = SmbkArchiveCodec.unpack(
            ByteArrayInputStream(withTraversal),
            destination,
        )

        assertTrue(validation is BackupValidation.Invalid)
        assertTrue(!File(destination.parentFile, "escape").exists())
    }

    @Test
    fun `missing manifest fails validation with a business reason`() {
        val database = tempFile("database.sqlite", "database-content")
        val asset = tempFile("asset-a.png", "asset-content")
        val archive = ByteArrayOutputStream()
        SmbkArchiveCodec.create(
            archive = archive,
            database = database,
            assets = listOf(asset),
            databaseSchemaVersion = 30,
            problemCount = 1,
            createdAtEpochMillis = 4_000L,
        )

        val withoutManifest = removeEntries(archive.toByteArray(), setOf("manifest.json"))
        val validation = SmbkArchiveCodec.validate(ByteArrayInputStream(withoutManifest))

        assertTrue(validation is BackupValidation.Invalid)
        assertTrue((validation as BackupValidation.Invalid).reason.contains("manifest"))
    }

    @Test
    fun `tampered checksum file fails validation`() {
        val database = tempFile("database.sqlite", "database-content")
        val asset = tempFile("asset-a.png", "asset-content")
        val archive = ByteArrayOutputStream()
        SmbkArchiveCodec.create(
            archive = archive,
            database = database,
            assets = listOf(asset),
            databaseSchemaVersion = 30,
            problemCount = 1,
            createdAtEpochMillis = 5_000L,
        )

        val tampered = rewriteEntry(
            archive.toByteArray(),
            "checksums.sha256",
            "0000000000000000000000000000000000000000000000000000000000000000 *database.sqlite",
        )
        val validation = SmbkArchiveCodec.validate(ByteArrayInputStream(tampered))

        assertTrue(validation is BackupValidation.Invalid)
    }

    @Test
    fun `future format version fails validation before any restore`() {
        val database = tempFile("database.sqlite", "database-content")
        val asset = tempFile("asset-a.png", "asset-content")
        val archive = ByteArrayOutputStream()
        SmbkArchiveCodec.create(
            archive = archive,
            database = database,
            assets = listOf(asset),
            databaseSchemaVersion = 30,
            problemCount = 1,
            createdAtEpochMillis = 6_000L,
        )

        val futureManifest = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(archive.toByteArray())).use { zip ->
            ZipOutputStream(futureManifest).use { output ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    output.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "manifest.json") {
                        val manifest = zip.readBytes().decodeToString()
                        output.write(
                            manifest.replace(
                                "\"databaseSchemaVersion\"",
                                "\"formatVersion\":99,\"databaseSchemaVersion\"",
                            ).toByteArray(),
                        )
                    } else {
                        output.write(zip.readBytes())
                    }
                    output.closeEntry()
                    zip.closeEntry()
                }
            }
        }
        val validation = SmbkArchiveCodec.validate(
            ByteArrayInputStream(futureManifest.toByteArray()),
        )

        assertTrue(validation is BackupValidation.Invalid)
    }

    @Test
    fun `missing listed asset fails validation before any restore`() {
        val database = tempFile("database.sqlite", "database-content")
        val asset = tempFile("asset-a.png", "asset-content")
        val archive = ByteArrayOutputStream()
        SmbkArchiveCodec.create(
            archive = archive,
            database = database,
            assets = listOf(asset),
            databaseSchemaVersion = 30,
            problemCount = 1,
            createdAtEpochMillis = 4_000L,
        )

        val withMissingAsset = removeEntries(
            archive.toByteArray(),
            setOf("assets/${asset.name}"),
        )
        val validation = SmbkArchiveCodec.validate(
            ByteArrayInputStream(withMissingAsset),
        )

        assertTrue(validation is BackupValidation.Invalid)
    }

    @Test
    fun `missing database fails validation before any restore`() {
        val database = tempFile("database.sqlite", "database-content")
        val asset = tempFile("asset-a.png", "asset-content")
        val archive = ByteArrayOutputStream()
        SmbkArchiveCodec.create(
            archive = archive,
            database = database,
            assets = listOf(asset),
            databaseSchemaVersion = 30,
            problemCount = 1,
            createdAtEpochMillis = 5_000L,
        )

        val withMissingDatabase = removeEntries(
            archive.toByteArray(),
            setOf("database.sqlite"),
        )
        val validation = SmbkArchiveCodec.validate(
            ByteArrayInputStream(withMissingDatabase),
        )

        assertTrue(validation is BackupValidation.Invalid)
    }

    private fun tamperAsset(archiveBytes: ByteArray): ByteArray {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        entries["assets/asset-a.png"] = "tampered-content".toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun tempFile(name: String, content: String): File {
        val file = File.createTempFile(name, ".tmp")
        file.writeText(content)
        file.deleteOnExit()
        return file
    }

    private fun rewritePath(archiveBytes: ByteArray, newPath: String, content: String): ByteArray {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        entries[newPath] = content.toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun removeEntries(archiveBytes: ByteArray, names: Set<String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
            ZipOutputStream(output).use { zipOutput ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name !in names) {
                        zipOutput.putNextEntry(ZipEntry(entry.name))
                        zipOutput.write(zip.readBytes())
                        zipOutput.closeEntry()
                    }
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    private fun rewriteEntry(
        archiveBytes: ByteArray,
        entryName: String,
        content: String,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
            ZipOutputStream(output).use { zipOutput ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    zipOutput.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == entryName) {
                        zipOutput.write(content.toByteArray())
                    } else {
                        zipOutput.write(zip.readBytes())
                    }
                    zipOutput.closeEntry()
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }
}
