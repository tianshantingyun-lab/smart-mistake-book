package com.tingyun.smartmistakebook.core.data.backup

import android.content.Context
import androidx.core.content.ContextCompat
import com.tingyun.smartmistakebook.core.data.capture.AndroidCanonicalAssetVault
import com.tingyun.smartmistakebook.core.data.settings.MODEL_SECRET_KEY_ALIAS
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backup/restore repository implementing the §10.3 target architecture in a
 * deliberately constrained shape:
 *
 *   - Generation isolation: during restore the complete new data set is
 *     materialized as the "next" generation (`<db>.next` + `source-assets.next`)
 *     while the live set, once displaced, becomes the "previous" generation
 *     (`<db>.prev` + `source-assets.prev`).
 *   - Pointer/journal: the durable restore journal records the phase AND the
 *     concrete generation paths, acting as the current-generation pointer.
 *     `BackupRestoreStartupRecovery` reads it on next startup.
 *   - Atomic switch: the live database and the live asset directory are each
 *     replaced by a single atomic rename within the same directory; files and
 *     parent directories are fsynced before and after the renames.
 *   - Deviation from §10.3: the live database stays at its canonical Room
 *     path (`getDatabasePath`) instead of living inside a generation-A/B
 *     directory tree, because every consumer (Room builder, deleteAllData,
 *     backup code) resolves that single path; moving it would ripple through
 *     the Room open path and cannot be verified safely without a device.
 *     The two remaining cross-artifact rename steps are covered by the
 *     journal-driven startup rollback, which is exercised by tests.
 */
class AndroidBackupRepository(
    private val context: Context,
    private val database: StudyDatabasePort,
) : BackupRepository {
    /** Maximum allowed compression ratio (uncompressed / compressed). */
    private companion object {
        const val MAX_COMPRESSION_RATIO = 100.0
        const val ASSET_DIRECTORY = "source-assets"

        /**
         * 孤儿资产回收的宽限期（30 分钟）。资产文件先落盘、引用随后才在另一个事务里
         * 建立（大堂发送附图：登记资产行 → 写入消息引用），这段在途期里它按引用判定
         * 确实是"孤儿"；没有宽限期，清理会把学生正要发送的那张图连行带文件删掉，
         * 随后建立引用时会撞上 tutor_message_source_asset 的 RESTRICT 外键而让发送失败。
         */
        const val ORPHAN_ASSET_GRACE_MILLIS = 30L * 60L * 1_000L

        /** Process-wide guard making concurrent restore requests idempotent-reject. */
        val restoreInFlight = AtomicBoolean(false)
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
        // 认领在数据库侧一个写事务内完成，因此认领前提交的任何新引用都会保住它的行；
        // 这里只负责删除被认领行的文件。返回值是真正删掉的图片数，与存储页文案
        //「已清理 N 张…题图」一致（行删掉但文件已不在时不该报成一张）。
        val claimed = database.claimUnreferencedCanonicalAssets(
            createdBeforeEpochMillis = System.currentTimeMillis() - ORPHAN_ASSET_GRACE_MILLIS,
        )
        claimed.count { asset -> runCatching { vault.delete(asset) }.isSuccess }
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
        // Consistency snapshot: VACUUM INTO when the device SQLite supports it,
        // otherwise WAL checkpoint + read-only copy (see BackupPort docs). The
        // archive is always built from the snapshot file, never from the live
        // database file.
        val snapshotDir = File(context.cacheDir, "backup-snapshots").apply { mkdirs() }
        val snapshotFile = File(snapshotDir, "snapshot-${System.nanoTime()}.db")
        try {
            database.snapshotForBackup(databaseFile, snapshotFile)
            SmbkArchiveCodec.create(
                archive = destination,
                database = snapshotFile,
                assets = if (options.includeAssets) assets else emptyList(),
                databaseSchemaVersion = snapshotFile.readSchemaVersion(),
                problemCount = database.countMistakes(),
                createdAtEpochMillis = System.currentTimeMillis(),
            )
        } finally {
            snapshotFile.delete()
        }
    }

    override suspend fun validate(source: InputStream): BackupValidation =
        withContext(Dispatchers.IO) {
            // Streaming validation: entries are written to a scratch directory
            // and SHA-256 is computed while streaming; nothing large is kept
            // in memory. The scratch directory is always cleaned up.
            val scratch = File(context.cacheDir, "validate-${UUID.randomUUID()}")
            try {
                SmbkArchiveCodec.validate(source, scratch)
            } finally {
                scratch.deleteRecursively()
            }
        }

    override suspend fun restore(source: InputStream): RestoreReceipt =
        withContext(Dispatchers.IO) {
            // Idempotency: a second, overlapping restore request is rejected
            // instead of racing the in-flight generation switch.
            if (!restoreInFlight.compareAndSet(false, true)) {
                throw BackupRestoreException("恢复已在进行中，重复的恢复请求被拒绝")
            }
            try {
                doRestore(source)
            } finally {
                restoreInFlight.set(false)
            }
        }

    private fun doRestore(source: InputStream): RestoreReceipt {
        val restoreId = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "restore-$restoreId")
        val staging = File(root, "staging").apply { mkdirs() }
        val journal = RestoreJournal(context, restoreId)

        val databaseFile = context.getDatabasePath(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
        val databasesDir = databaseFile.parentFile ?: context.filesDir
        val assetRoot = canonicalAssetRoot()
        val filesDir = assetRoot.parentFile ?: context.filesDir
        val previousDb = File(databasesDir, "${databaseFile.name}.prev")
        val nextDb = File(databasesDir, "${databaseFile.name}.next")
        val previousAssets = File(filesDir, "$ASSET_DIRECTORY.prev")
        val nextAssets = File(filesDir, "$ASSET_DIRECTORY.next")
        var swapStarted = false
        var databaseClosed = false

        return try {
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

            // Required space: next-generation database + assets copies, the
            // displaced current generation kept for rollback, working space.
            val safetySnapshotSize = currentDbSize + currentAssetSize
            val workingSpace = 10L * 1024 * 1024 // 10MB
            val requiredSpace =
                stagedDbSize + stagedAssetSize + safetySnapshotSize + workingSpace

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

            // Phase 2b: Validate the STAGED database BEFORE any swap so a
            // corrupt or incompatible backup never replaces the live data:
            // Room open + migrations + user_version + quick_check +
            // foreign_key_check.
            val stagedDbValid = runCatching {
                validateStagedDatabase(stagingDb)
            }
            if (stagedDbValid.isFailure) {
                throw BackupRestoreException(
                    "备份数据库预验证失败：${stagedDbValid.exceptionOrNull()?.message.orEmpty()}",
                )
            }

            // Phase 3: Prepare generations and record the rollback journal.
            journal.writePhase(RestorePhase.BACKUP_CURRENT)
            deleteGenerationArtifacts(previousDb, nextDb, previousAssets, nextAssets)
            journal.recordGenerationPaths(
                liveDatabasePath = databaseFile,
                previousDatabasePath = previousDb,
                nextDatabasePath = nextDb,
                liveAssetDir = assetRoot,
                previousAssetDir = previousAssets,
                nextAssetDir = nextAssets,
                stagingDir = root,
            )
            journal.recordCurrentState(databaseFile, assetRoot)

            // Phase 4: Close the database and perform the atomic switch.
            journal.writePhase(RestorePhase.SWAPPING)
            database.close()
            databaseClosed = true
            listOf(
                File("${databaseFile.absolutePath}-wal"),
                File("${databaseFile.absolutePath}-shm"),
            ).forEach { it.delete() }

            // Build the next asset generation and make it durable BEFORE any
            // pointer moves.
            nextAssets.mkdirs()
            File(staging, "assets").listFiles().orEmpty()
                .filter { it.isFile }
                .forEach { staged ->
                    val target = File(nextAssets, staged.name)
                    staged.copyTo(target, overwrite = true)
                    fsyncFile(target)
                }
            fsyncDir(nextAssets)

            // Build the next database generation in the databases directory
            // (same filesystem as the live file → rename will be atomic).
            stagingDb.copyTo(nextDb, overwrite = true)
            fsyncFile(nextDb)
            fsyncDir(databasesDir)

            swapStarted = true

            // Atomic switch #1: database. Each rename is atomic within the
            // same directory; the journal (written above) is the pointer that
            // lets startup recovery undo a half-done switch.
            if (databaseFile.exists() && !databaseFile.renameTo(previousDb)) {
                error("Cannot displace live database into previous generation")
            }
            fsyncDir(databasesDir)
            if (!nextDb.renameTo(databaseFile)) {
                // Best-effort in-line repair before escalating to the journal.
                if (previousDb.exists() && previousDb.renameTo(databaseFile)) {
                    swapStarted = false
                }
                error("Cannot promote next database generation")
            }
            fsyncDir(databasesDir)

            // Atomic switch #2: asset directory.
            if (assetRoot.isDirectory && !assetRoot.renameTo(previousAssets)) {
                error("Cannot displace live asset generation")
            }
            fsyncDir(filesDir)
            if (!nextAssets.renameTo(assetRoot)) {
                if (previousAssets.isDirectory && previousAssets.renameTo(assetRoot)) {
                    fsyncDir(filesDir)
                }
                error("Cannot promote next asset generation")
            }
            fsyncDir(filesDir)

            // Phase 5: Verify restored database
            journal.writePhase(RestorePhase.VERIFYING)
            verifyRestoredDatabase(databaseFile)

            // Phase 6: Fsync to ensure durability
            journal.writePhase(RestorePhase.FSYNCING)
            fsyncFile(databaseFile)
            fsyncDir(databasesDir)
            fsyncDir(assetRoot)
            fsyncDir(filesDir)

            // Phase 7: Clean up previous generation + staging + journal.
            journal.writePhase(RestorePhase.COMPLETED)
            deleteGenerationArtifacts(previousDb, nextDb, previousAssets, nextAssets)
            root.deleteRecursively()
            journal.clear()

            RestoreReceipt(
                databaseSchemaVersion = valid.manifest.databaseSchemaVersion,
                problemCount = valid.manifest.problemCount,
                assetCount = valid.manifest.assetCount,
                fileCount = valid.manifest.fileCount,
            )
        } catch (failure: Exception) {
            runCatching { journal.writePhase(RestorePhase.ROLLING_BACK) }
            val entry = journal.readEntry()
            if (swapStarted && entry != null) {
                val problems = RestoreGenerationSupport.rollback(context, entry)
                if (problems.isNotEmpty()) {
                    // A failed rollback must never silently trust the mixed
                    // state: quarantine the untrusted generation artifacts.
                    RestoreGenerationSupport.quarantine(context, entry)
                    runCatching { journal.writePhase(RestorePhase.ROLLBACK_FAILED) }
                    val quarantined = BackupRestoreException(
                        "恢复失败且回滚不完整，相关数据已隔离：${problems.joinToString("; ")}",
                        failure,
                    )
                    if (databaseClosed) {
                        throw BackupRestoreDatabaseClosedException(quarantined.message.orEmpty(), quarantined)
                    }
                    throw quarantined
                }
                RestoreGenerationSupport.cleanup(context, entry)
                journal.clear()
            } else {
                // Nothing was swapped yet; just remove partial artifacts.
                deleteGenerationArtifacts(previousDb, nextDb, previousAssets, nextAssets)
                root.deleteRecursively()
                journal.clear()
            }
            if (databaseClosed) {
                // 文件已回滚到恢复前状态，但进程内连接已在 swap 前关闭；database
                // 端口是构造注入到全部仓库的，无法热重开——如实告知用户必须重启，
                // 而不是让其带着一个已关闭的连接继续操作。
                throw BackupRestoreDatabaseClosedException(
                    "恢复失败，已回到原来的数据。",
                    failure,
                )
            }
            throw failure
        }
    }

    private fun deleteGenerationArtifacts(
        previousDb: File,
        nextDb: File,
        previousAssets: File,
        nextAssets: File,
    ) {
        listOf(
            previousDb,
            File("${previousDb.absolutePath}-wal"),
            File("${previousDb.absolutePath}-shm"),
            nextDb,
            File("${nextDb.absolutePath}-wal"),
            File("${nextDb.absolutePath}-shm"),
        ).forEach { it.delete() }
        previousAssets.deleteRecursively()
        nextAssets.deleteRecursively()
    }

    override suspend fun deleteAllData(): DeleteAllDataReceipt = withContext(Dispatchers.IO) {
        val databaseFile = context.getDatabasePath(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
        val assetRoot = canonicalAssetRoot()
        val databaseBytes = databaseFile.length()
        val assetBytes = assetRoot.listFiles().orEmpty().filter { it.isFile }.sumOf(File::length)
        // SharedPreferences live under the app data root, NOT under filesDir.
        // Context#getDataDir only exists from API 24 while minSdk is 23, so go
        // through ContextCompat: on 23 it resolves the root from ApplicationInfo
        // instead of throwing NoSuchMethodError on a delete-all path.
        val preferenceFiles = ContextCompat.getDataDir(context)
            ?.let { File(it, "shared_prefs") }
            ?.listFiles().orEmpty()
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

        // 2. Close and delete database files (including generation artifacts)
        database.close()
        listOf(
            databaseFile,
            File("${databaseFile.absolutePath}-wal"),
            File("${databaseFile.absolutePath}-shm"),
            File("${databaseFile.absolutePath}.prev"),
            File("${databaseFile.absolutePath}.next"),
        ).forEach { it.delete() }

        // 3. Delete canonical assets (including generation artifacts)
        assetRoot.listFiles().orEmpty().forEach { it.deleteRecursively() }
        assetRoot.delete()
        val assetParent = assetRoot.parentFile
        if (assetParent != null) {
            File(assetParent, "$ASSET_DIRECTORY.prev").deleteRecursively()
            File(assetParent, "$ASSET_DIRECTORY.next").deleteRecursively()
        }

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

        // 8. Clear the Keystore entry that protects the model API key. The
        // alias is authored by the vault, so delete exactly that alias instead
        // of re-deriving its name here — a second copy of the string is how the
        // sweep previously missed the entry and left the key behind.
        try {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(MODEL_SECRET_KEY_ALIAS)) {
                keyStore.deleteEntry(MODEL_SECRET_KEY_ALIAS)
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

        // 10. Delete restore journals and quarantined restore artifacts.
        context.noBackupFilesDir.listFiles().orEmpty()
            .filter {
                it.name.startsWith("restore-journal-") || it.name == "restore-quarantine"
            }
            .forEach { it.deleteRecursively() }

        DeleteAllDataReceipt(
            deletedDatabaseBytes = databaseBytes,
            deletedAssetBytes = assetBytes,
            deletedPreferenceBytes = preferenceBytes + datastoreFiles.sumOf(File::length),
            deletedTempBytes = tempBytes,
            deletedSecretVaultBytes = secretVaultBytes,
        )
    }

    private fun canonicalAssetRoot(): File {
        val root = File(context.filesDir, ASSET_DIRECTORY)
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
     * Validate the staged database BEFORE it replaces the live one:
     *   1. Room opens a throwaway copy with the full production builder, so
     *      every registered migration runs exactly as it would in production;
     *   2. PRAGMA user_version of the migrated copy must equal the live
     *      database's user_version (a backup from a newer schema is rejected
     *      because Room cannot open it; an older one is accepted only after
     *      it migrates to the same version);
     *   3. quick_check for page-level corruption and foreign_key_check for
     *      referential integrity.
     * Any failure aborts restore before the swap.
     */
    private fun validateStagedDatabase(stagedDatabaseFile: File) {
        if (!stagedDatabaseFile.exists()) {
            throw BackupRestoreException("备份中缺少数据库文件")
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(
            stagedDatabaseFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            listOf("PRAGMA quick_check", "PRAGMA foreign_key_check").forEach { pragma ->
                db.rawQuery(pragma, null).use { cursor ->
                    if (pragma == "PRAGMA quick_check") {
                        if (cursor.moveToFirst() && cursor.getString(0) != "ok") {
                            throw BackupRestoreException(
                                "quick_check 失败: ${cursor.getString(0)}",
                            )
                        }
                    } else {
                        if (cursor.moveToFirst()) {
                            throw BackupRestoreException("foreign_key_check 发现引用完整性问题")
                        }
                    }
                }
            }
        }

        // Room-level pre-validation: run the staged database through the
        // production builder (migrations included) on a throwaway copy.
        val validationName = "restore-validate-${UUID.randomUUID()}.db"
        val validationCopy = context.getDatabasePath(validationName)
        var validationDatabase: StudyDatabasePort? = null
        try {
            stagedDatabaseFile.copyTo(validationCopy, overwrite = true)
            validationDatabase = StudyDatabaseFactory.open(context, validationName)
            validationDatabase.close()
            validationDatabase = null
            val migratedVersion = validationCopy.readSchemaVersion()
            val liveDatabaseFile = context.getDatabasePath(
                StudyDatabaseFactory.DEFAULT_DATABASE_NAME,
            )
            if (liveDatabaseFile.exists()) {
                val liveVersion = liveDatabaseFile.readSchemaVersion()
                if (migratedVersion != liveVersion) {
                    throw BackupRestoreException(
                        "备份数据库迁移后版本($migratedVersion)与当前数据库版本($liveVersion)不一致",
                    )
                }
            }
        } catch (validationFailure: Exception) {
            if (validationFailure is BackupRestoreException) throw validationFailure
            throw BackupRestoreException(
                "备份数据库无法通过 Room 迁移校验：${validationFailure.message.orEmpty()}",
                validationFailure,
            )
        } finally {
            runCatching { validationDatabase?.close() }
            listOf(
                validationCopy,
                File("${validationCopy.absolutePath}-wal"),
                File("${validationCopy.absolutePath}-shm"),
            ).forEach { it.delete() }
        }
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
}

/**
 * Durable restore journal that tracks the restore phase AND the concrete
 * generation paths. On process death, the journal is read on next startup
 * (BackupRestoreStartupRecovery) to decide resume/rollback/quarantine.
 */
internal class RestoreJournal(
    private val context: Context,
    private val restoreId: String,
) {
    private val journalFile: File
        get() = File(context.noBackupFilesDir, "restore-journal-$restoreId.json")

    private var entry = JournalEntry(
        restoreId = restoreId,
        phase = RestorePhase.VALIDATING.name,
        timestamp = System.currentTimeMillis(),
    )

    fun exists(): Boolean = journalFile.exists()

    fun writePhase(phase: RestorePhase) {
        entry = entry.copy(phase = phase.name, timestamp = System.currentTimeMillis())
        persist()
    }

    /** Records the generation layout so startup recovery can execute rollback. */
    fun recordGenerationPaths(
        liveDatabasePath: File,
        previousDatabasePath: File,
        nextDatabasePath: File,
        liveAssetDir: File,
        previousAssetDir: File,
        nextAssetDir: File,
        stagingDir: File,
    ) {
        entry = entry.copy(
            liveDatabasePath = liveDatabasePath.absolutePath,
            previousDatabasePath = previousDatabasePath.absolutePath,
            nextDatabasePath = nextDatabasePath.absolutePath,
            liveAssetDir = liveAssetDir.absolutePath,
            previousAssetDir = previousAssetDir.absolutePath,
            nextAssetDir = nextAssetDir.absolutePath,
            stagingDir = stagingDir.absolutePath,
        )
        persist()
    }

    fun recordCurrentState(databaseFile: File, assetRoot: File) {
        entry = entry.copy(
            timestamp = System.currentTimeMillis(),
            currentState = CurrentState(
                databaseExists = databaseFile.exists(),
                databaseSize = if (databaseFile.exists()) databaseFile.length() else 0,
                assetCount = assetRoot.listFiles()?.size ?: 0,
                freeSpace = context.filesDir.freeSpace,
            ),
        )
        persist()
    }

    fun clear() {
        if (journalFile.exists()) {
            journalFile.delete()
        }
        fsyncDir(context.noBackupFilesDir)
    }

    fun readPhase(): RestorePhase? = readEntry()?.let { entry ->
        runCatching { RestorePhase.valueOf(entry.phase) }.getOrNull()
    }

    fun readEntry(): JournalEntry? {
        if (!journalFile.exists()) return null
        return try {
            kotlinx.serialization.json.Json.decodeFromString(
                JournalEntry.serializer(),
                journalFile.readText(),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Atomic journal write: temp file + fsync + rename over the journal. */
    private fun persist() {
        val text = kotlinx.serialization.json.Json.encodeToString(
            JournalEntry.serializer(),
            entry,
        )
        val parent = journalFile.parentFile ?: return
        val temp = File(parent, "${journalFile.name}.tmp")
        temp.outputStream().use { output ->
            output.write(text.toByteArray())
            output.flush()
            runCatching { output.fd.sync() }
        }
        if (!temp.renameTo(journalFile)) {
            temp.delete()
        }
        fsyncDir(parent)
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
    /** Live database file path (the current-generation pointer target). */
    val liveDatabasePath: String? = null,
    /** Previous-generation database copy created before the switch. */
    val previousDatabasePath: String? = null,
    /** Next-generation database staged for promotion. */
    val nextDatabasePath: String? = null,
    /** Live canonical asset directory. */
    val liveAssetDir: String? = null,
    /** Previous-generation asset directory created before the switch. */
    val previousAssetDir: String? = null,
    /** Next-generation asset directory staged for promotion. */
    val nextAssetDir: String? = null,
    /** Restore staging root (cacheDir/restore-<id>). */
    val stagingDir: String? = null,
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

/**
 * 恢复失败且进程内数据库连接已被关闭（swap 阶段之后失败、文件已回滚）。
 * database 端口是构造注入到全部仓库的，进程内无法热重开——唯一的出路是
 * 让用户完全退出并重新打开应用，因此单独成类型让 UI 给出可操作的提示。
 */
class BackupRestoreDatabaseClosedException(message: String, cause: Throwable? = null) :
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
