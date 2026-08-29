package com.tingyun.smartmistakebook.core.database

import java.io.File

/** WAL checkpointing, consistency snapshots, and full clears for backup flows. */
internal class RoomBackupSupportStore(
    private val database: StudyDatabase,
) {
    suspend fun checkpointForBackup() {
        // WAL checkpoint so committed rows are captured before the archived
        // database file is packaged. This stays the cheap flush; the archive
        // flow itself uses snapshotForBackup below for the consistency
        // snapshot.
        database.useConnection(isReadOnly = false) { connection ->
            connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { statement ->
                while (statement.step()) {
                    // Checkpoint result row is intentionally consumed and ignored.
                }
            }
        }
    }

    suspend fun snapshotForBackup(
        sourceDatabaseFile: File,
        snapshotTarget: File,
    ) {
        // Consistency snapshot for backup archives (see BackupPort docs).
        //
        // Preferred: VACUUM INTO (SQLite >= 3.27.0) writes a complete,
        // transaction-consistent copy of the database into the target file
        // even while concurrent writers are active; this matches the
        // semantics required by the SQLite Online Backup API approach.
        //
        // Fallback (older SQLite builds): TRUNCATE WAL checkpoint plus a
        // read-only byte copy of the main file, which is consistent as long
        // as no writer commits during the copy. The backup flow guarantees
        // that by running while the app is otherwise idle.
        if (snapshotTarget.exists() && !snapshotTarget.delete()) {
            error("Cannot replace existing backup snapshot target")
        }
        val sqliteVersion = readSqliteVersion()
        if (sqliteVersion != null && isAtLeast(sqliteVersion, 3, 27, 0)) {
            val escapedPath = snapshotTarget.absolutePath.replace("'", "''")
            val succeeded = try {
                database.useConnection(isReadOnly = false) { connection ->
                    connection.usePrepared("VACUUM INTO '$escapedPath'") { statement ->
                        while (statement.step()) {
                            // VACUUM INTO produces no result rows.
                        }
                    }
                }
                snapshotTarget.isFile && snapshotTarget.length() > 0L
            } catch (_: Exception) {
                // Older SQLite builds reject the INTO clause; fall through to
                // the checkpoint + read-only copy fallback.
                false
            }
            if (succeeded) {
                fsyncSnapshot(snapshotTarget)
                return
            }
            if (snapshotTarget.exists()) snapshotTarget.delete()
        }
        // Fallback: checkpoint every WAL frame into the main file, then copy
        // the main file through a plain read-only stream.
        checkpointForBackup()
        sourceDatabaseFile.inputStream().use { input ->
            snapshotTarget.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        fsyncSnapshot(snapshotTarget)
    }

    private suspend fun readSqliteVersion(): String? {
        val holder = arrayOfNulls<String>(1)
        database.useConnection(isReadOnly = true) { connection ->
            connection.usePrepared("SELECT sqlite_version()") { statement ->
                if (statement.step()) {
                    holder[0] = statement.getText(0)
                }
            }
        }
        return holder[0]
    }

    private fun isAtLeast(version: String, major: Int, minor: Int, patch: Int): Boolean {
        val parts = version.split('.').mapNotNull { part -> part.toIntOrNull() }
        val actual = listOf(
            parts.getOrElse(0) { 0 },
            parts.getOrElse(1) { 0 },
            parts.getOrElse(2) { 0 },
        )
        val required = listOf(major, minor, patch)
        for (index in 0..2) {
            if (actual[index] != required[index]) return actual[index] > required[index]
        }
        return true
    }

    private fun fsyncSnapshot(file: File) {
        runCatching {
            java.io.RandomAccessFile(file, "r").use { raf -> raf.fd.sync() }
        }
    }

    suspend fun clearAllData() {
        database.useConnection(isReadOnly = false) { connection ->
            connection.usePrepared("PRAGMA foreign_keys = OFF") { statement ->
                while (statement.step()) {
                    // PRAGMA result row is intentionally ignored.
                }
            }
            val tableNames = buildList {
                connection.usePrepared(
                    "SELECT name FROM sqlite_master WHERE type = 'table' " +
                        "AND name NOT IN ('room_master_table', 'android_metadata')",
                ) { statement ->
                    while (statement.step()) {
                        add(requireNotNull(statement.getText(0)))
                    }
                }
            }
            tableNames.forEach { table ->
                connection.usePrepared("DELETE FROM `$table`") { statement ->
                    statement.step()
                }
            }
            connection.usePrepared("PRAGMA foreign_keys = ON") { statement ->
                while (statement.step()) {
                    // PRAGMA result row is intentionally ignored.
                }
            }
        }
    }
}
