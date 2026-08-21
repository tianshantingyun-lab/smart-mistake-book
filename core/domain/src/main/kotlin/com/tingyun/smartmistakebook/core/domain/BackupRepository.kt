package com.tingyun.smartmistakebook.core.domain

import java.io.InputStream
import java.io.OutputStream

data class StorageInventory(
    val databaseBytes: Long,
    val assetBytes: Long,
    val tempBytes: Long,
    val cleanableBytes: Long,
    val recentBackupAtEpochMillis: Long? = null,
) {
    init {
        require(databaseBytes >= 0L && assetBytes >= 0L && tempBytes >= 0L) {
            "Storage inventory bytes must not be negative"
        }
        require(cleanableBytes >= 0L && cleanableBytes <= tempBytes) {
            "Cleanable storage must be within temporary storage"
        }
        require(recentBackupAtEpochMillis == null || recentBackupAtEpochMillis >= 0L) {
            "Backup timestamp must not be negative"
        }
    }
}

data class BackupOptions(
    val includeAssets: Boolean = true,
) {
    init {
        require(includeAssets) { "First backup version always includes canonical assets" }
    }
}

data class BackupReceipt(
    val createdAtEpochMillis: Long,
    val databaseSchemaVersion: Int,
    val problemCount: Int,
    val assetCount: Int,
    val fileCount: Int,
    val totalBytes: Long,
) {
    init {
        require(createdAtEpochMillis >= 0L)
        require(databaseSchemaVersion > 0)
        require(problemCount >= 0 && assetCount >= 0 && fileCount > 0)
        require(totalBytes > 0L)
    }
}

data class RestoreReceipt(
    val databaseSchemaVersion: Int,
    val problemCount: Int,
    val assetCount: Int,
    val fileCount: Int,
    val restartRequired: Boolean = true,
) {
    init {
        require(databaseSchemaVersion > 0)
        require(problemCount >= 0 && assetCount >= 0 && fileCount > 0)
    }
}

data class DeleteAllDataReceipt(
    val deletedDatabaseBytes: Long,
    val deletedAssetBytes: Long,
    val deletedPreferenceBytes: Long,
    val deletedTempBytes: Long,
    val deletedSecretVaultBytes: Long = 0L,
) {
    init {
        require(
            deletedDatabaseBytes >= 0L && deletedAssetBytes >= 0L &&
                deletedPreferenceBytes >= 0L && deletedTempBytes >= 0L &&
                deletedSecretVaultBytes >= 0L,
        ) { "Deleted data byte counts must not be negative" }
    }
}

sealed interface BackupValidation {
    data class Valid(
        val manifest: BackupManifestSummary,
        val checkedFileCount: Int,
        val totalBytes: Long,
    ) : BackupValidation

    data class Invalid(
        val reason: String,
        val manifest: BackupManifestSummary? = null,
    ) : BackupValidation
}

data class BackupManifestSummary(
    val formatVersion: Int,
    val databaseSchemaVersion: Int,
    val createdAtEpochMillis: Long,
    val problemCount: Int,
    val assetCount: Int,
    val fileCount: Int,
)

interface BackupRepository {
    suspend fun inspect(): StorageInventory

    /** Deletes canonical assets that no revision or draft references. */
    suspend fun cleanupOrphanAssets(): Int

    suspend fun create(
        destination: OutputStream,
        options: BackupOptions = BackupOptions(),
    ): BackupReceipt

    suspend fun validate(source: InputStream): BackupValidation

    suspend fun restore(source: InputStream): RestoreReceipt

    suspend fun deleteAllData(): DeleteAllDataReceipt
}
