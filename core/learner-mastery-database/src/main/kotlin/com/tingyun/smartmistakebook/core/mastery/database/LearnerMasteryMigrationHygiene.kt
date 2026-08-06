package com.tingyun.smartmistakebook.core.mastery.database

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

internal data class LearnerMasteryDatabasePreOpenState(
    val version: Int,
    val responseSummaryHygieneState: String?,
) {
    val responseSummaryHygienePending: Boolean
        get() =
            responseSummaryHygieneState ==
                LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_PENDING

    init {
        check(
            responseSummaryHygieneState == null ||
                responseSummaryHygieneState ==
                LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_PENDING ||
                responseSummaryHygieneState ==
                LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_COMPLETE,
        ) {
            "Learner-mastery response-summary hygiene state is invalid"
        }
    }
}

internal fun inspectLearnerMasteryDatabaseBeforeRoomOpen(
    context: Context,
    databaseName: String,
): LearnerMasteryDatabasePreOpenState {
    val path = context.getDatabasePath(databaseName)
    if (!path.isFile) {
        return LearnerMasteryDatabasePreOpenState(
            version = 0,
            responseSummaryHygieneState = null,
        )
    }
    return SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
        LearnerMasteryDatabasePreOpenState(
            version = sqlite.version,
            responseSummaryHygieneState = sqlite.readResponseSummaryHygieneState(),
        )
    }
}

/**
 * Removes v16 plaintext remnants while Room is closed. SQLite itself owns WAL/SHM cleanup under
 * database locks; no sidecar file is manually deleted.
 */
internal fun runLearnerMasteryResponseSummaryHygieneExclusive(
    context: Context,
    databaseName: String,
    cipher: LearnerMasteryLegacyResponseSummaryCipher =
        AndroidKeystoreLearnerMasteryLegacyResponseSummaryCipher(),
) {
    val path = context.getDatabasePath(databaseName)
    require(path.isFile) { "Learner-mastery database is missing during migration hygiene" }
    SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
        val state = sqlite.readResponseSummaryHygieneState()
        if (state == LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_COMPLETE) return
        check(state == LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_PENDING) {
            "Learner-mastery response-summary hygiene was not durably requested"
        }
        sqlite.rawQuery("PRAGMA secure_delete = ON", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 1) {
                "SQLite did not enable secure deletion"
            }
        }
        sqlite.requireUncontendedWalCheckpoint()
        sqlite.execSQL("VACUUM")
        sqlite.requireUncontendedWalCheckpoint()
        sqlite.requireEncryptedLegacyResponseSummaryRows(cipher)
        sqlite.completeResponseSummaryHygieneAtomically()
        sqlite.requireUncontendedWalCheckpoint()
        check(
            sqlite.readResponseSummaryHygieneState() ==
                LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_COMPLETE,
        ) {
            "Learner-mastery response-summary hygiene marker was not completed"
        }
    }
}

private fun SQLiteDatabase.readResponseSummaryHygieneState(): String? {
    rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'mastery_store_metadata'",
        null,
    ).use { tables ->
        if (!tables.moveToFirst()) return null
    }
    return rawQuery(
        "SELECT metadata_value FROM mastery_store_metadata WHERE metadata_key = ? LIMIT 1",
        arrayOf(LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_METADATA_KEY),
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}

private fun SQLiteDatabase.requireEncryptedLegacyResponseSummaryRows(
    cipher: LearnerMasteryLegacyResponseSummaryCipher,
) {
    val columns = linkedMapOf<String, String>()
    rawQuery("PRAGMA table_info(`mastery_legacy_observation_snapshot`)", null).use { cursor ->
        while (cursor.moveToNext()) {
            columns[cursor.getString(1)] = cursor.getString(2).uppercase()
        }
    }
    check("response_summary" !in columns) {
        "Plaintext legacy response-summary column survived migration"
    }
    check(
        columns["key_version"] == "INTEGER" &&
            columns["nonce"] == "BLOB" &&
            columns["ciphertext"] == "BLOB",
    ) {
        "Encrypted legacy response-summary columns are incomplete"
    }

    rawQuery(
        """
        SELECT `learner_id`, `source_generation`, `batch_sequence`, `snapshot_ordinal`,
               `source_fact_id`, `key_version`, `nonce`, `ciphertext`,
               `source_record_canonical_fingerprint`, `snapshot_canonical_fingerprint`
        FROM `mastery_legacy_observation_snapshot`
        ORDER BY `learner_id`, `source_generation`, `batch_sequence`, `snapshot_ordinal`
        """.trimIndent(),
        null,
    ).use { cursor ->
        while (cursor.moveToNext()) {
            val encrypted =
                LearnerMasteryEncryptedLegacyResponseSummary(
                    keyVersion = cursor.requireBoundedInt(5),
                    nonce = cursor.getBlob(6),
                    ciphertext = cursor.getBlob(7),
                )
            val binding =
                LearnerMasteryLegacyResponseSummaryBinding(
                    learnerId = cursor.getString(0),
                    sourceGeneration = cursor.getString(1),
                    batchSequence = cursor.getLong(2),
                    snapshotOrdinal = cursor.requireBoundedInt(3),
                    sourceFactId = cursor.getString(4),
                    sourceRecordCanonicalFingerprint = cursor.getString(8),
                    snapshotCanonicalFingerprint = cursor.getString(9),
                    keyVersion = encrypted.keyVersion,
                )
            val plaintext = cipher.decrypt(binding, encrypted)
            check(
                plaintext != null &&
                    plaintext.isNotBlank() &&
                    plaintext == plaintext.trim() &&
                    plaintext.length <= 512,
            ) {
                "Encrypted legacy response summary cannot be authenticated"
            }
        }
    }
}

private fun SQLiteDatabase.completeResponseSummaryHygieneAtomically() {
    beginTransaction()
    try {
        execSQL("DROP TRIGGER IF EXISTS `immutable_mastery_store_metadata_update`")
        execSQL(
            """
            UPDATE `mastery_store_metadata`
            SET `metadata_value` = ?
            WHERE `metadata_key` = ? AND `metadata_value` = ?
            """.trimIndent(),
            arrayOf<Any>(
                LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_COMPLETE,
                LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_METADATA_KEY,
                LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_PENDING,
            ),
        )
        check(changedRowCount() == 1L) {
            "Learner-mastery response-summary hygiene transition was not exclusive"
        }
        execSQL(learnerMasteryCalibrationAuditMetadataUpdateTriggerDefinition().sql)
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}

private fun SQLiteDatabase.changedRowCount(): Long =
    rawQuery("SELECT changes()", null).use { cursor ->
        check(cursor.moveToFirst()) { "SQLite did not return the hygiene update count" }
        cursor.getLong(0)
    }

private fun SQLiteDatabase.requireUncontendedWalCheckpoint() {
    rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
        check(cursor.moveToFirst()) { "SQLite did not return a WAL checkpoint result" }
        check(cursor.getInt(0) == 0) {
            "Learner-mastery database WAL is still owned by another connection"
        }
    }
}

private fun Cursor.requireBoundedInt(columnIndex: Int): Int {
    val value = getLong(columnIndex)
    check(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "Learner-mastery response-summary integer is outside its schema bound"
    }
    return value.toInt()
}
