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
            val staging = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
            val rollback = File(context.filesDir, "restore-rollback-${UUID.randomUUID()}")
                .apply { mkdirs() }
            try {
                val validation = SmbkArchiveCodec.unpack(source, staging)
                val valid = validation as? BackupValidation.Valid
                    ?: throw BackupRestoreException(
                        (validation as BackupValidation.Invalid).reason,
                    )
                val databaseFile = context.getDatabasePath(
                    StudyDatabaseFactory.DEFAULT_DATABASE_NAME,
                )
                val assetRoot = canonicalAssetRoot()
                database.close()

                listOf(
                    File("${databaseFile.absolutePath}-wal"),
                    File("${databaseFile.absolutePath}-shm"),
                ).forEach { it.delete() }

                swapFile(
                    source = File(staging, "database.sqlite"),
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

                rollback.deleteRecursively()
                staging.deleteRecursively()
                RestoreReceipt(
                    databaseSchemaVersion = valid.manifest.databaseSchemaVersion,
                    problemCount = valid.manifest.problemCount,
                    assetCount = valid.manifest.assetCount,
                    fileCount = valid.manifest.fileCount,
                )
            } catch (failure: Exception) {
                rollbackFrom(failure, rollback, staging)
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

        database.close()
        listOf(
            databaseFile,
            File("${databaseFile.absolutePath}-wal"),
            File("${databaseFile.absolutePath}-shm"),
        ).forEach { it.delete() }
        assetRoot.listFiles().orEmpty().forEach { it.deleteRecursively() }
        preferenceFiles.forEach { it.delete() }
        datastoreFiles.forEach { it.delete() }
        tempFiles.forEach { it.deleteRecursively() }

        DeleteAllDataReceipt(
            deletedDatabaseBytes = databaseBytes,
            deletedAssetBytes = assetBytes,
            deletedPreferenceBytes = preferenceBytes + datastoreFiles.sumOf(File::length),
            deletedTempBytes = tempBytes,
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
}

class BackupRestoreException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

object BackupRepositoryFactory {
    fun create(
        context: Context,
        database: StudyDatabasePort,
    ): BackupRepository = AndroidBackupRepository(context, database)
}
