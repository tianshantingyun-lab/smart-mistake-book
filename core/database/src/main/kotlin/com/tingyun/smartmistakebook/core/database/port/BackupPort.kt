package com.tingyun.smartmistakebook.core.database.port

import java.io.File

/**
 * Port for backup operations.
 */
interface BackupPort {
    /**
     * Flushes any SQLite WAL frames into the main database file so a file-level
     * backup captures every committed row. No-op for non-WAL fixtures.
     */
    suspend fun checkpointForBackup()

    /**
     * Produces a single-file, transaction-consistent snapshot of the database
     * at [sourceDatabaseFile] into [snapshotTarget] for archival. Prefers
     * `VACUUM INTO` (available since SQLite 3.27) which yields a consistent
     * snapshot even under concurrent writers; on older SQLite builds it falls
     * back to a TRUNCATE WAL checkpoint followed by a read-only byte copy,
     * which is consistent as long as no writer is active during the backup.
     */
    suspend fun snapshotForBackup(sourceDatabaseFile: File, snapshotTarget: File)

    /**
     * Deletes all business rows while keeping the schema and connection open.
     * Used by tests that need a true empty catalog without restarting the app.
     */
    suspend fun clearAllData()
}
