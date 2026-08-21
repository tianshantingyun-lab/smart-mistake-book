package com.tingyun.smartmistakebook.core.data.backup

import android.content.Context
import java.io.File

/**
 * Startup recovery coordinator for interrupted restores.
 *
 * The audit (section 10) requires that after a process death during restore,
 * the next app startup knows whether it was in PREPARED / SWAPPING /
 * VERIFYING / ROLLING_BACK and completes it:
 *   - resume: a later VALIDATING/PREFLIGHT phase can simply finish;
 *   - rollback: a mid-swap phase restores the previous generation;
 *   - quarantine: an unverifiable generation is quarantined, never used.
 *
 * This is a pure coordinator over the journal; it does not itself move files
 * (the restore implementation performs the actual generation switch).
 */
internal class RestoreRecoveryCoordinator(
    private val context: Context,
) {
    /** Journal dir where restore journals are written. */
    private val journalDir: File
        get() = File(context.noBackupFilesDir, "")

    /**
     * Scan for any partial restore journal left by a previous run.
     * Returns the phase of the most recent incomplete restore, or null when
     * there is no journal.
     */
    fun detectPendingRestore(): RestorePhase? {
        val journals = journalDir.listFiles { f -> f.name.startsWith("restore-journal-") } ?: return null
        if (journals.isEmpty()) return null
        // Pick the most recent journal.
        val latest = journals.maxByOrNull(File::lastModified) ?: return null
        return try {
            val entry = kotlinx.serialization.json.Json.decodeFromString<JournalEntry>(
                latest.readText(),
            )
            RestorePhase.valueOf(entry.phase)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decide the recovery action needed for an interrupted restore.
     */
    fun recoveryAction(phase: RestorePhase): RecoveryDisposition = when (phase) {
        // Validation/planning happened before any file swapped: safe to resume.
        RestorePhase.VALIDATING,
        RestorePhase.PREFLIGHT,
        -> RecoveryDisposition.Resume

        // A switch started but we cannot be sure it committed: roll back to
        // the previous generation to guarantee consistency.
        RestorePhase.BACKUP_CURRENT,
        RestorePhase.SWAPPING,
        RestorePhase.VERIFYING,
        RestorePhase.FSYNCING,
        RestorePhase.ROLLING_BACK,
        -> RecoveryDisposition.Rollback

        // Completed or failed rollback leaves nothing to do.
        RestorePhase.COMPLETED,
        RestorePhase.ROLLBACK_FAILED,
        -> RecoveryDisposition.None
    }

    /**
     * Remove the journal for the given restore id after successful recovery.
     */
    fun clear(restoreId: String) {
        File(journalDir, "restore-journal-$restoreId.json").delete()
    }

    /**
     * Quarantine an unverifiable restore staging directory.
     */
    fun quarantine(stagingDir: File) {
        if (stagingDir.exists()) {
            val quarantineDir = File(stagingDir.parentFile, "quarantine-${System.currentTimeMillis()}")
            stagingDir.renameTo(quarantineDir)
        }
    }
}

/**
 * What the startup coordinator should do about an interrupted restore.
 */
internal enum class RecoveryDisposition {
    /** Continue a restore that had not yet swapped files. */
    Resume,

    /** Roll back to the previously-valid generation. */
    Rollback,

    /** No action needed. */
    None,
}
