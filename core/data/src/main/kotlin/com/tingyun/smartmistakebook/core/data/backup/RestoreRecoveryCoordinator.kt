package com.tingyun.smartmistakebook.core.data.backup

import android.content.Context
import java.io.File

/**
 * Startup recovery coordinator for interrupted restores.
 *
 * The audit (§10) requires that after a process death during restore, the
 * next app startup decides what to do with the interrupted restore and then
 * EXECUTES it (decision → execution closed loop):
 *   - Clean: a pre-swap interruption (VALIDATING / PREFLIGHT) never touched
 *     live data, so the leftover staging and journal are simply cleaned up
 *     (the restore request itself died with the process; there is no source
 *     stream left to resume from);
 *   - Rollback: a mid-swap interruption (BACKUP_CURRENT .. FSYNCING) restores
 *     the previous generation from the recorded `.prev` artifacts;
 *   - Quarantine: when rollback itself fails, the untrusted generation
 *     artifacts are moved aside for forensics instead of being trusted.
 *
 * Generation artifacts recorded by the restore implementation:
 *   - previous database copy: `<live db path>.prev`
 *   - staged next database:   `<live db path>.next`
 *   - previous asset generation dir: `source-assets.prev`
 *   - next asset generation dir:     `source-assets.next`
 */
internal class RestoreRecoveryCoordinator(
    private val context: Context,
) {
    /** Journal dir where restore journals are written. */
    private val journalDir: File
        get() = context.noBackupFilesDir

    /**
     * Scan for any partial restore journals left by a previous run.
     * Returns the most recent journal entry per leftover journal file.
     */
    fun detectPendingRestores(): List<Pair<File, JournalEntry>> {
        val journals = journalDir.listFiles { f ->
            f.isFile && f.name.startsWith(JOURNAL_PREFIX) && f.name.endsWith(".json")
        } ?: return emptyList()
        return journals.mapNotNull { file ->
            readEntry(file)?.let { entry -> file to entry }
        }
    }

    /**
     * Decide the recovery action needed for an interrupted restore.
     */
    fun recoveryAction(phase: RestorePhase): RecoveryDisposition = when (phase) {
        // Nothing swapped yet: clean up staging/journal; there is nothing to
        // resume because the source stream died with the process.
        RestorePhase.VALIDATING,
        RestorePhase.PREFLIGHT,
        -> RecoveryDisposition.Clean

        // A switch started but we cannot be sure it committed: roll back to
        // the previous generation to guarantee consistency.
        RestorePhase.BACKUP_CURRENT,
        RestorePhase.SWAPPING,
        RestorePhase.VERIFYING,
        RestorePhase.FSYNCING,
        RestorePhase.ROLLING_BACK,
        -> RecoveryDisposition.Rollback

        // A completed restore only needs leftover cleanup.
        RestorePhase.COMPLETED,
        -> RecoveryDisposition.Clean

        // A previous rollback attempt already failed: try once more, then
        // quarantine whatever cannot be restored (handled by the executor).
        RestorePhase.ROLLBACK_FAILED,
        -> RecoveryDisposition.Rollback
    }

    /** Remove a specific journal file after recovery finished. */
    fun deleteJournal(journalFile: File) {
        journalFile.delete()
    }

    /**
     * Remove the journal for the given restore id after successful recovery.
     */
    fun clear(restoreId: String) {
        File(journalDir, "$JOURNAL_PREFIX$restoreId.json").delete()
    }

    /**
     * Quarantine an unverifiable restore staging directory.
     */
    fun quarantine(stagingDir: File) {
        if (stagingDir.exists()) {
            val quarantineDir = File(
                stagingDir.parentFile,
                "quarantine-${System.currentTimeMillis()}",
            )
            stagingDir.renameTo(quarantineDir)
        }
    }

    private fun readEntry(journalFile: File): JournalEntry? = try {
        Json.decodeFromString(JournalEntry.serializer(), journalFile.readText())
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val JOURNAL_PREFIX = "restore-journal-"
        val Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}

/**
 * What the startup coordinator should do about an interrupted restore.
 */
internal enum class RecoveryDisposition {
    /** Clean up a pre-swap interruption; nothing was swapped. */
    Clean,

    /** Roll back to the previously-valid generation. */
    Rollback,

    /** No action needed. */
    None,

    /** Rollback failed; untrusted artifacts were quarantined. */
    Quarantine,
}

/** Outcome of the startup recovery sweep, surfaced for logging/diagnostics. */
sealed interface RestoreStartupOutcome {
    /** No interrupted restore was found (leftover artifacts may be cleaned). */
    data object NothingToRecover : RestoreStartupOutcome

    /** A pre-swap interruption was cleaned up. */
    data class Cleaned(val restoreId: String) : RestoreStartupOutcome

    /** An interrupted swap was rolled back to the previous generation. */
    data class RolledBack(val restoreId: String) : RestoreStartupOutcome

    /** Rollback failed; the untrusted generation was quarantined. */
    data class Quarantined(val restoreId: String, val reason: String) : RestoreStartupOutcome

    /** A journal existed but could not be interpreted; it was quarantined. */
    data class Unreadable(val reason: String) : RestoreStartupOutcome
}

/**
 * Public startup entry point. The application MUST call this before opening
 * the study database so an interrupted restore can never leave a mixed
 * generation visible to Room.
 */
object BackupRestoreStartupRecovery {
    fun recoverOnStartup(context: Context): RestoreStartupOutcome {
        val appContext = context.applicationContext
        val coordinator = RestoreRecoveryCoordinator(appContext)
        var outcome: RestoreStartupOutcome = RestoreStartupOutcome.NothingToRecover
        coordinator.detectPendingRestores().forEach { (journalFile, entry) ->
            val phase = runCatching { RestorePhase.valueOf(entry.phase) }.getOrNull()
            if (phase == null) {
                RestoreGenerationSupport.quarantine(appContext, entry)
                coordinator.deleteJournal(journalFile)
                outcome = RestoreStartupOutcome.Unreadable(
                    "restore-journal ${entry.restoreId} 无法解析",
                )
                return@forEach
            }
            outcome = when (coordinator.recoveryAction(phase)) {
                RecoveryDisposition.None,
                RecoveryDisposition.Clean,
                -> {
                    // Pre-swap interruption or completed restore: nothing was
                    // (or everything was) swapped; remove leftovers and move on.
                    RestoreGenerationSupport.cleanup(appContext, entry)
                    coordinator.deleteJournal(journalFile)
                    RestoreStartupOutcome.Cleaned(entry.restoreId)
                }
                RecoveryDisposition.Rollback,
                -> {
                    val problems = RestoreGenerationSupport.rollback(appContext, entry)
                    if (problems.isEmpty()) {
                        RestoreGenerationSupport.cleanup(appContext, entry)
                        coordinator.deleteJournal(journalFile)
                        RestoreStartupOutcome.RolledBack(entry.restoreId)
                    } else {
                        RestoreGenerationSupport.quarantine(appContext, entry)
                        coordinator.deleteJournal(journalFile)
                        RestoreStartupOutcome.Quarantined(
                            restoreId = entry.restoreId,
                            reason = problems.joinToString("; "),
                        )
                    }
                }
                RecoveryDisposition.Quarantine,
                -> {
                    RestoreGenerationSupport.quarantine(appContext, entry)
                    coordinator.deleteJournal(journalFile)
                    RestoreStartupOutcome.Quarantined(entry.restoreId, "rollback 曾被标记失败")
                }
            }
        }
        // Sweep stale artifacts from any restore whose journal is already gone.
        RestoreGenerationSupport.cleanupOrphans(appContext)
        return outcome
    }
}

/**
 * File-level execution body shared by the restore implementation and the
 * startup coordinator (decision → execution closed loop).
 */
internal object RestoreGenerationSupport {

    /**
     * Restore the previous generation recorded in [entry]. Returns a list of
     * problems; an empty list means the rollback fully succeeded.
     */
    fun rollback(context: Context, entry: JournalEntry): List<String> {
        val problems = mutableListOf<String>()
        rollbackDatabase(entry, problems)
        rollbackAssets(entry, problems)
        fsyncDir(databaseDir(context))
        fsyncDir(context.filesDir)
        return problems
    }

    private fun rollbackDatabase(entry: JournalEntry, problems: MutableList<String>) {
        val previous = entry.previousDatabasePath?.let(::File)
        val live = entry.liveDatabasePath?.let(::File)
        val next = entry.nextDatabasePath?.let(::File)
        if (previous != null && live != null && previous.exists()) {
            // The live file, if present, belongs to the new (untrusted)
            // generation: remove it together with its WAL sidecars and put
            // the previous generation back.
            listOf(
                live,
                File("${live.absolutePath}-wal"),
                File("${live.absolutePath}-shm"),
            ).forEach { file ->
                if (file.exists() && !file.delete()) {
                    problems += "无法删除新数据库文件 ${file.name}"
                }
            }
            if (!previous.renameTo(live)) {
                problems += "无法回滚数据库到上一代"
            }
        }
        if (next != null) {
            listOf(next, File("${next.absolutePath}-wal"), File("${next.absolutePath}-shm"))
                .forEach { file ->
                    if (file.exists() && !file.delete()) {
                        problems += "无法清理未切换完成的数据库 ${file.name}"
                    }
                }
        }
    }

    private fun rollbackAssets(entry: JournalEntry, problems: MutableList<String>) {
        val previousDir = entry.previousAssetDir?.let(::File)
        val liveDir = entry.liveAssetDir?.let(::File)
        val nextDir = entry.nextAssetDir?.let(::File)
        if (previousDir != null && liveDir != null && previousDir.isDirectory) {
            if (liveDir.exists() && !liveDir.deleteRecursively()) {
                problems += "无法删除新一代资产目录"
            }
            if (!previousDir.renameTo(liveDir)) {
                problems += "无法回滚资产目录到上一代"
            }
        }
        if (nextDir != null && nextDir.exists() && !nextDir.deleteRecursively()) {
            problems += "无法清理未切换完成的资产目录"
        }
    }

    /** Delete every generation artifact and staging directory of [entry]. */
    fun cleanup(context: Context, entry: JournalEntry) {
        entry.previousDatabasePath?.let(::File)?.delete()
        entry.nextDatabasePath?.let { next ->
            listOf(next, File("${next.absolutePath}-wal"), File("${next.absolutePath}-shm"))
                .forEach(File::delete)
        }
        entry.previousAssetDir?.let { dir -> File(dir).deleteRecursively() }
        entry.nextAssetDir?.let { dir -> File(dir).deleteRecursively() }
        entry.stagingDir?.let { dir -> File(dir).deleteRecursively() }
        // Legacy artifact shapes from restore V1.
        context.filesDir.listFiles { file -> file.name.startsWith("restore-rollback-") }
            .orEmpty()
            .forEach { dir -> dir.deleteRecursively() }
        fsyncDir(databaseDir(context))
        fsyncDir(context.filesDir)
    }

    /** Move untrusted generation artifacts aside instead of trusting them. */
    fun quarantine(context: Context, entry: JournalEntry) {
        val quarantineRoot = File(context.noBackupFilesDir, "restore-quarantine")
            .apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        listOfNotNull(
            entry.stagingDir,
            entry.nextDatabasePath,
            entry.nextAssetDir,
            entry.previousDatabasePath,
            entry.previousAssetDir,
        ).forEachIndexed { index, path ->
            val source = File(path)
            if (source.exists()) {
                runCatching {
                    source.renameTo(File(quarantineRoot, "artifact-$stamp-$index-${source.name}"))
                }
            }
        }
        fsyncDir(quarantineRoot)
    }

    /** Remove restore artifacts whose journal no longer exists. */
    fun cleanupOrphans(context: Context) {
        // Staging roots live in cacheDir and are per-restore-id; after a
        // process restart no restore can still be active, so any leftover is
        // orphaned by definition.
        context.cacheDir.listFiles { file -> file.name.startsWith("restore-") }
            .orEmpty()
            .forEach { dir -> dir.deleteRecursively() }
        context.filesDir.listFiles { file -> file.name.startsWith("restore-rollback-") }
            .orEmpty()
            .forEach { dir -> dir.deleteRecursively() }
        // Drop quarantines older than 30 days.
        val cutoff = System.currentTimeMillis() - QUARANTINE_RETENTION_MILLIS
        File(context.noBackupFilesDir, "restore-quarantine")
            .listFiles().orEmpty()
            .filter { it.lastModified() < cutoff }
            .forEach { it.deleteRecursively() }
    }

    private fun databaseDir(context: Context): File =
        context.getDatabasePath(
            com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory.DEFAULT_DATABASE_NAME,
        ).parentFile ?: context.filesDir

    private const val QUARANTINE_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
}

/** fsync a directory so that renames/deletes inside it are durable. */
internal fun fsyncDir(dir: File?) {
    if (dir == null || !dir.isDirectory) return
    runCatching {
        // On Linux/Android a directory can be opened read-only and fsynced;
        // this makes rename/unlink metadata changes durable.
        val stream = java.io.FileInputStream(dir)
        try {
            stream.fd.sync()
        } finally {
            stream.close()
        }
    }
}

/** fsync a regular file's content and metadata. */
internal fun fsyncFile(file: File) {
    if (!file.isFile) return
    runCatching {
        java.io.RandomAccessFile(file, "r").use { raf -> raf.fd.sync() }
    }
}
