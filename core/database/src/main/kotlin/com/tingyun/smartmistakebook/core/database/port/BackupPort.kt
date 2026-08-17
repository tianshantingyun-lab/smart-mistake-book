package com.tingyun.smartmistakebook.core.database.port

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
     * Deletes all business rows while keeping the schema and connection open.
     * Used by tests that need a true empty catalog without restarting the app.
     */
    suspend fun clearAllData()
}
