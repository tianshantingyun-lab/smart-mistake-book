package com.tingyun.smartmistakebook.core.data.backup

import android.content.Context
import com.tingyun.smartmistakebook.core.data.capture.AndroidCanonicalAssetVault
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.BackupOptions
import com.tingyun.smartmistakebook.core.domain.BackupReceipt
import com.tingyun.smartmistakebook.core.domain.BackupRepository
import com.tingyun.smartmistakebook.core.domain.BackupValidation
import com.tingyun.smartmistakebook.core.domain.DeleteAllDataReceipt
import com.tingyun.smartmistakebook.core.domain.RestoreReceipt
import com.tingyun.smartmistakebook.core.domain.StorageInventory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class AndroidBackupRepository(
    private val context: Context,
    private val database: StudyDatabasePort,
) : BackupRepository {
    /** Maximum allowed compression ratio (uncompressed / compressed). */
    private companion object {
        const val MAX_COMPRESSION_RATIO = 100.0
    }

    override suspend fun inspect(): StorageInventory = withContext(Dispatchers.IO) {
        val databaseFile = context.getDatabasePath(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
        val assetRoot = canonicalAssetRoot()
        val tempFiles = listOf(
            databaseFile.parentFile?.listFiles().orEmpty().toList(),
            assetRoot.listFiles().orEmpty().toList(),
        ).flatten().filter { it.name.startsWith(".raw-") || it.name.startsWith(".canonical-") }
        val assetBytes = assetRoot.listFiles().orEmpty()
            .filter { it.isFile }
            .sumOf(File::length)
        val tempBytes = tempFiles.sumOf(File::length)
        StorageInventory(
            databaseBytes = databaseFile.length(),
            assetBytes = assetBytes,
            tempBytes = tempBytes,
            cleanableBytes = tempBytes,
        )
    }

    override suspend fun cleanupOrphanAssets(): Int = withContext(Dispatchers.IO) {
        val vault = AndroidCanonicalAssetVault(context.applicationContext)
        database.readUnreferencedCanonicalAssets().forEach { asset ->
            runCatching { vault.delete(asset) }
        }
        database.deleteUnreferencedCanonicalAssets()
    }

    override suspend fun create(
        destination: OutputStream,
        options: BackupOptions,
    ): BackupReceipt = withContext(Dispatchers.IO) {
        val databaseFile = context.getDatabasePath(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
        val assetRoot = canonicalAssetRoot()
        val assets = assetRoot.listFiles().orEmpty()
            .filter { it.isFile && !it.name.startsWith(".raw-") && !it.name.startsWith(".canonical-") }
            .sortedBy(File::getName)
        database.checkpointForBackup()
        SmbkArchiveCodec.create(
            archive = destination,
            database = databaseFile,
            assets = if (options.includeAssets) assets else emptyList(),
            databaseSchemaVersion = databaseFile.readSchemaVersion(),
            problemCount = database.countMistakes(),
            createdAtEpochMillis = System.currentTimeMillis(),
        )
    }

    override suspend fun validate(source: InputStream): BackupValidation =
        withContext(Dispatchers.IO) {
            SmbkArchiveCodec.validate(source)
        }

    override suspend fun restore(source: InputStream): RestoreReceipt =
        withContext(Dispatchers.IO) {
            val restoreId = UUID.randomUUID().toString()
            val staging = File(context.cacheDir, "restore-$restoreId").apply { mkdirs() }
            val rollback = File(context.filesDir, "restore-rollback-$restoreId").apply { mkdirs() }
            val journal = RestoreJournal(context, restoreId)

            try {
                // Phase 1: Validate and unpack (counting compressed bytes for zip-bomb ratio)
                journal.writePhase(RestorePhase.VALIDATING)
                val countingSource = CountingInputStream(source)
                val validation = SmbkArchiveCodec.unpack(countingSource, staging)
                val valid = validation as? BackupValidation.Valid
                    ?: throw BackupRestoreException(
                        (validation as BackupValidation.Invalid).reason,
                    )

                // Phase 2: Free-space preflight
                journal.writePhase(RestorePhase.PREFLIGHT)
                val databaseFile = context.getDatabasePath(
                    StudyDatabaseFactory.DEFAULT_DATABASE_NAME,
                )
                val assetRoot = canonicalAssetRoot()
                val stagingDb = File(staging, "database.sqlite")
                val currentDbSize = if (databaseFile.exists()) databaseFile.length() else 0L
                val stagedDbSize = if (stagingDb.exists()) stagingDb.length() else 0L
                val currentAssetSize = assetRoot.listFiles().orEmpty()
                    .filter { it.isFile }
                    .sumOf(File::length)
                val stagedAssetSize = File(staging, "assets").listFiles().orEmpty()
                    .filter { it.isFile }
                    .sumOf(File::length)
                val freeSpace = context.filesDir.freeSpace

                // Calculate required space with safety margin
                // Need space for:
                // 1. New database (stagedDbSize)
                // 2. New assets (stagedAssetSize)
                // 3. Safety snapshot of current database (currentDbSize)
                // 4. Safety snapshot of current assets (currentAssetSize)
                // 5. Working space (10MB minimum)
                val safetySnapshotSize = currentDbSize + currentAssetSize
                val workingSpace = 10L * 1024 * 1024 // 10MB
                val requiredSpace = stagedDbSize + stagedAssetSize + safetySnapshotSize + workingSpace

                if (freeSpace < requiredSpace) {
                    val availableMB = freeSpace / 1024 / 1024
                    val requiredMB = requiredSpace / 1024 / 1024
                    throw BackupRestoreException(
                        "磁盘空间不足：需要 ${requiredMB}MB，可用 ${availableMB}MB。" +
                            "其中新数据 ${stagedDbSize / 1024 / 1024}MB，" +
                            "安全快照 ${safetySnapshotSize / 1024 / 1024}MB，" +
                            "工作空间 ${workingSpace / 1024 / 1024}MB",
                    )
                }

                // Verify compression ratio is reasonable (prevent zip bombs).
                // Compare compressed archive bytes against decompressed staging size.
                val compressedBytes = countingSource.count()
                val totalStagedSize = stagedDbSize + stagedAssetSize
                if (compressedBytes > 0 && totalStagedSize > 0) {
                    val compressionRatio = totalStagedSize.toDouble() / compressedBytes
                    if (compressionRatio > MAX_COMPRESSION_RATIO) {
                        throw BackupRestoreException(
                            "压缩比异常（1:$compressionRatio），可能存在损坏的归档",
                        )
                    }
                }

                // Phase 3: Write rollback journal
                journal.writePhase(RestorePhase.BACKUP_CURRENT)
                journal.recordCurrentState(databaseFile, assetRoot)

                // Phase 4: Close database and swap files
                journal.writePhase(RestorePhase.SWAPPING)
                database.close()

                listOf(
                    File("${databaseFile.absolutePath}-wal"),
                    File("${databaseFile.absolutePath}-shm"),
                ).forEach { it.delete() }

                swapFile(
                    source = stagingDb,
                    target = databaseFile,
                    rollback = File(rollback, "database.sqlite"),
                )

                val existingAssets = assetRoot.listFiles().orEmpty()
                    .filter { it.isFile }
                    .toList()
                existingAssets.forEach { asset ->
                    swapFile(
                        source = asset,
                        target = File(rollback, asset.name),
                        rollback = asset,
                    )
                }
                val stagedAssets = File(staging, "assets").listFiles().orEmpty()
                    .filter { it.isFile }
                    .toList()
                stagedAssets.forEach { staged ->
                    swapFile(
                        source = staged,
                        target = File(assetRoot, staged.name),
                        rollback = File(rollback, staged.name),
                    )
                }

                // Phase 5: Verify restored database
                journal.writePhase(RestorePhase.VERIFYING)
                verifyRestoredDatabase(databaseFile)

                // Phase 6: Fsync to ensure durability
                journal.writePhase(RestorePhase.FSYNCING)
                fsyncFile(databaseFile)
                assetRoot.listFiles().orEmpty()
                    .filter { it.isFile }
                    .forEach { fsyncFile(it) }

                // Phase 7: Clean up
                journal.writePhase(RestorePhase.COMPLETED)
                rollback.deleteRecursively()
                staging.deleteRecursively()
                journal.clear()

                RestoreReceipt(
                    databaseSchemaVersion = valid.manifest.databaseSchemaVersion,
                    problemCount = valid.manifest.problemCount,
                    assetCount = valid.manifest.assetCount,
                    fileCount = valid.manifest.fileCount,
                )
            } catch (failure: Exception) {
                // Attempt rollback if we have a journal
                if (journal.exists()) {
                    try {
                        journal.writePhase(RestorePhase.ROLLING_BACK)
                        rollbackFrom(failure, rollback, staging)
                        journal.clear()
                    } catch (rollbackFailure: Exception) {
                        // Log rollback failure but throw original error
                        journal.writePhase(RestorePhase.ROLLBACK_FAILED)
                    }
                } else {
                    rollbackFrom(failure, rollback, staging)
                }
                throw failure
            }
        }

    override suspend fun deleteAllData(): DeleteAllDataReceipt = withContext(Dispatchers.IO) {
        val databaseFile = context.getDatabasePath(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
        val assetRoot = canonicalAssetRoot()
        val databaseBytes = databaseFile.length()
        val assetBytes = assetRoot.listFiles().orEmpty().filter { it.isFile }.sumOf(File::length)
        val preferenceFiles = File(context.filesDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.isFile }
            .toList()
        val preferenceBytes = preferenceFiles.sumOf(File::length)
        val datastoreFiles = File(context.filesDir, "datastore").listFiles().orEmpty()
            .filter { it.isFile }
            .toList()
        val tempFiles = context.cacheDir.listFiles().orEmpty().toList()
        val tempBytes = tempFiles.sumOf(File::length)

        // 1. Cancel all WorkManager tasks
        try {
            androidx.work.WorkManager.getInstance(context.applicationContext).cancelAllWork()
        } catch (_: Exception) {
            // WorkManager may not be initialized; continue cleanup
        }

        // 2. Close and delete database files
        database.close()
        listOf(
            databaseFile,
            File("${databaseFile.absolutePath}-wal"),
            File("${databaseFile.absolutePath}-shm"),
        ).forEach { it.delete() }

        // 3. Delete canonical assets
        assetRoot.listFiles().orEmpty().forEach { it.deleteRecursively() }

        // 4. Delete SharedPreferences
        preferenceFiles.forEach { it.delete() }

        // 5. Delete DataStore files
        datastoreFiles.forEach { it.delete() }

        // 6. Delete temporary/cache files
        tempFiles.forEach { it.deleteRecursively() }

        // 7. Delete secret vault files (API key ciphertext)
        val secretVaultDir = File(context.noBackupFilesDir, "model-secrets")
        val secretVaultBytes = if (secretVaultDir.exists()) {
            val bytes = secretVaultDir.listFiles().orEmpty().sumOf(File::length)
            secretVaultDir.deleteRecursively()
            bytes
        } else {
            0L
        }

        // 8. Clear Keystore aliases for API keys
        try {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val aliases = keyStore.aliases().toList()
            aliases.filter { it.startsWith("smartmistakebook_") }.forEach { alias ->
                keyStore.deleteEntry(alias)
            }
        } catch (_: Exception) {
            // Keystore may not be accessible; continue cleanup
        }

        // 9. Delete notifications
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            notificationManager.cancelAll()
        } catch (_: Exception) {
            // Notification manager may not be available
        }

        DeleteAllDataReceipt(
            deletedDatabaseBytes = databaseBytes,
            deletedAssetBytes = assetBytes,
            deletedPreferenceBytes = preferenceBytes + datastoreFiles.sumOf(File::length),
            deletedTempBytes = tempBytes,
            deletedSecretVaultBytes = secretVaultBytes,
        )
    }

    private fun swapFile(
        source: File,
        target: File,
        rollback: File,
    ) {
        if (!source.isFile) error("Restore source is missing: ${source.name}")
        if (target.exists() && !target.renameTo(rollback)) {
            error("Cannot stage current file for rollback: ${target.name}")
        }
        if (!source.renameTo(target)) {
            if (rollback.exists() && !rollback.renameTo(target)) {
                error("Cannot restore previous file after failed swap: ${target.name}")
            }
            error("Cannot replace target file: ${target.name}")
        }
    }

    private fun rollbackFrom(
        originalFailure: Exception,
        rollback: File,
        staging: File,
    ) {
        runCatching {
            val databaseFile = context.getDatabasePath(
                StudyDatabaseFactory.DEFAULT_DATABASE_NAME,
            )
            val databaseRollback = File(rollback, "database.sqlite")
            if (databaseRollback.exists() && !databaseRollback.renameTo(databaseFile)) {
                error("Cannot roll back database")
            }
            val assetRoot = canonicalAssetRoot()
            rollback.listFiles().orEmpty()
                .filter { it.isFile && it.name != "database.sqlite" }
                .forEach { file ->
                    if (!file.renameTo(File(assetRoot, file.name))) {
                        error("Cannot roll back asset ${file.name}")
                    }
                }
            staging.deleteRecursively()
            rollback.deleteRecursively()
        }.onFailure { rollbackFailure ->
            originalFailure.addSuppressed(rollbackFailure)
        }
    }

    private fun canonicalAssetRoot(): File {
        val root = File(context.filesDir, "source-assets")
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
            error("Cannot access canonical asset vault")
        }
        return root.canonicalFile
    }

    private fun File.readSchemaVersion(): Int = try {
        android.database.sqlite.SQLiteDatabase.openDatabase(
            absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { it.version }
    } catch (_: Exception) {
        -1
    }

    /**
     * Verify the restored database is valid and can be opened by Room.
     */
    private fun verifyRestoredDatabase(databaseFile: File) {
        if (!databaseFile.exists()) {
            throw BackupRestoreException("恢复后的数据库文件不存在")
        }

        // Check SQLite magic bytes
        val magic = databaseFile.inputStream().use { stream ->
            val buffer = ByteArray(16)
            stream.read(buffer)
            String(buffer.take(16).toByteArray())
        }
        if (!magic.startsWith("SQLite format 3")) {
            throw BackupRestoreException("恢复后的文件不是有效的 SQLite 数据库")
        }

        // Run integrity check
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                val cursor = db.rawQuery("PRAGMA integrity_check", null)
                cursor.use {
                    if (it.moveToFirst()) {
                        val result = it.getString(0)
                        if (result != "ok") {
                            throw BackupRestoreException(
                                "数据库完整性检查失败: $result",
                            )
                        }
                    }
                }
            }
        } catch (e: android.database.sqlite.SQLiteException) {
            throw BackupRestoreException("无法打开恢复后的数据库", e)
        }
    }

    /**
     * Fsync a file to ensure it's written to physical storage.
     */
    private fun fsyncFile(file: File) {
        if (!file.exists()) return
        try {
            java.io.RandomAccessFile(file, "rws").use { raf ->
                raf.sync()
            }
        } catch (_: Exception) {
            // Fsync may not be supported on all filesystems; continue
        }
    }
}

/**
 * Durable restore journal that tracks the restore phase. On process death,
 * the journal can be read on next startup to determine if a restore was
 * incomplete and needs rollback.
 */
internal class RestoreJournal(
    private val context: Context,
    private val restoreId: String,
) {
    private val journalFile: File
        get() = File(context.noBackupFilesDir, "restore-journal-$restoreId.json")

    fun exists(): Boolean = journalFile.exists()

    fun writePhase(phase: RestorePhase) {
        val entry = JournalEntry(
            restoreId = restoreId,
            phase = phase.name,
            timestamp = System.currentTimeMillis(),
        )
        journalFile.writeText(
            kotlinx.serialization.json.Json.encodeToString(entry),
        )
    }

    fun recordCurrentState(databaseFile: File, assetRoot: File) {
        val state = CurrentState(
            databaseExists = databaseFile.exists(),
            databaseSize = if (databaseFile.exists()) databaseFile.length() else 0,
            assetCount = assetRoot.listFiles()?.size ?: 0,
            freeSpace = context.filesDir.freeSpace,
        )
        val entry = JournalEntry(
            restoreId = restoreId,
            phase = RestorePhase.BACKUP_CURRENT.name,
            timestamp = System.currentTimeMillis(),
            currentState = state,
        )
        journalFile.writeText(
            kotlinx.serialization.json.Json.encodeToString(entry),
        )
    }

    fun clear() {
        if (journalFile.exists()) {
            journalFile.delete()
        }
    }

    fun readPhase(): RestorePhase? {
        if (!journalFile.exists()) return null
        return try {
            val entry = kotlinx.serialization.json.Json.decodeFromString<JournalEntry>(
                journalFile.readText(),
            )
            RestorePhase.valueOf(entry.phase)
        } catch (_: Exception) {
            null
        }
    }
}

internal enum class RestorePhase {
    VALIDATING,
    PREFLIGHT,
    BACKUP_CURRENT,
    SWAPPING,
    VERIFYING,
    FSYNCING,
    COMPLETED,
    ROLLING_BACK,
    ROLLBACK_FAILED,
}

@kotlinx.serialization.Serializable
internal data class JournalEntry(
    val restoreId: String,
    val phase: String,
    val timestamp: Long,
    val currentState: CurrentState? = null,
)

@kotlinx.serialization.Serializable
internal data class CurrentState(
    val databaseExists: Boolean,
    val databaseSize: Long,
    val assetCount: Int,
    val freeSpace: Long,
)

class BackupRestoreException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Counts bytes read from the wrapped stream, used to detect zip bombs. */
internal class CountingInputStream(
    delegate: InputStream,
) : java.io.FilterInputStream(delegate) {
    private var byteCount: Long = 0L

    override fun read(): Int {
        val value = super.read()
        if (value != -1) byteCount++
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read > 0) byteCount += read
        return read
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) byteCount += skipped
        return skipped
    }

    fun count(): Long = byteCount
}

object BackupRepositoryFactory {
    fun create(
        context: Context,
        database: StudyDatabasePort,
    ): BackupRepository = AndroidBackupRepository(context, database)
}
