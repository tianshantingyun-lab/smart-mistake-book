package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

internal val LEARNER_MASTERY_MIGRATION_16_17: Migration =
    learnerMasteryMigration16To17(
        cipher = AndroidKeystoreLearnerMasteryLegacyResponseSummaryCipher(),
    )

internal fun learnerMasteryMigration16To17(
    cipher: LearnerMasteryLegacyResponseSummaryCipher,
): Migration =
    object : Migration(16, 17) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("PRAGMA secure_delete = ON")
            dropV16LegacySnapshotGuards(connection)
            connection.execSQL(
                "ALTER TABLE `$LEGACY_SNAPSHOT_TABLE` " +
                    "RENAME TO `$LEGACY_SNAPSHOT_V16_PLAINTEXT_TABLE`",
            )
            connection.execSQL(CREATE_ENCRYPTED_LEGACY_SNAPSHOT_TABLE_SQL)
            copyV16LegacySnapshotsEncrypted(connection, cipher)
            requireSameLegacySnapshotRowCount(connection)
            connection.execSQL("DROP TABLE `$LEGACY_SNAPSHOT_V16_PLAINTEXT_TABLE`")
            connection.execSQL(CREATE_LEGACY_SNAPSHOT_SOURCE_FACT_INDEX_SQL)
            connection.execSQL(
                """
                INSERT INTO `mastery_store_metadata` (`metadata_key`, `metadata_value`)
                VALUES (
                    '$LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_METADATA_KEY',
                    '$LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_PENDING'
                )
                """.trimIndent(),
            )
            requireEncryptedLegacySnapshotShape(connection)
            installLearnerMasteryImmutableLedgerGuards(connection)
        }
    }

private fun dropV16LegacySnapshotGuards(connection: SQLiteConnection) {
    V16_LEGACY_SNAPSHOT_GUARD_NAMES.forEach { guardName ->
        connection.execSQL("DROP TRIGGER IF EXISTS `$guardName`")
    }
}

private fun copyV16LegacySnapshotsEncrypted(
    connection: SQLiteConnection,
    cipher: LearnerMasteryLegacyResponseSummaryCipher,
) {
    connection.prepare(READ_V16_LEGACY_SNAPSHOTS_SQL).use { source ->
        connection.prepare(INSERT_V17_LEGACY_SNAPSHOT_SQL).use { destination ->
            while (source.step()) {
                val row = source.readV16LegacySnapshotRow()
                val encrypted =
                    cipher.encrypt(
                        binding = row.responseSummaryBinding(),
                        plaintext = row.responseSummary,
                    )
                destination.clearBindings()
                destination.bindV17LegacySnapshot(row, encrypted)
                check(!destination.step()) {
                    "Encrypted legacy response summary unexpectedly returned a result row"
                }
                destination.reset()
            }
        }
    }
}

private fun requireSameLegacySnapshotRowCount(connection: SQLiteConnection) {
    val sourceCount = connection.readCount(LEGACY_SNAPSHOT_V16_PLAINTEXT_TABLE)
    val destinationCount = connection.readCount(LEGACY_SNAPSHOT_TABLE)
    check(sourceCount == destinationCount) {
        "Legacy response-summary migration did not preserve every snapshot row"
    }
}

private fun SQLiteConnection.readCount(tableName: String): Long =
    prepare("SELECT COUNT(*) FROM `$tableName`").use { statement ->
        check(statement.step()) { "SQLite did not return a legacy snapshot row count" }
        statement.getLong(0)
    }

internal fun requireEncryptedLegacySnapshotShape(connection: SQLiteConnection) {
    val columns = linkedMapOf<String, String>()
    connection.prepare("PRAGMA table_info(`$LEGACY_SNAPSHOT_TABLE`)").use { statement ->
        while (statement.step()) {
            columns[statement.getText(1)] = statement.getText(2).uppercase()
        }
    }
    check("response_summary" !in columns) {
        "Plaintext legacy response-summary column survived migration"
    }
    check(columns["key_version"] == "INTEGER") {
        "Legacy response-summary key version is missing"
    }
    check(columns["nonce"] == "BLOB") {
        "Legacy response-summary nonce is missing"
    }
    check(columns["ciphertext"] == "BLOB") {
        "Legacy response-summary ciphertext is missing"
    }
    connection.prepare(
        """
        SELECT 1
        FROM `$LEGACY_SNAPSHOT_TABLE`
        WHERE `key_version` != $LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_KEY_VERSION
           OR typeof(`nonce`) != 'blob'
           OR length(`nonce`) != 12
           OR typeof(`ciphertext`) != 'blob'
           OR length(`ciphertext`) <= 16
           OR length(`ciphertext`) > 4112
        LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        check(!statement.step()) {
            "Legacy response-summary encryption envelope is malformed"
        }
    }
}

private fun SQLiteStatement.readV16LegacySnapshotRow() =
    V16LegacySnapshotRow(
        learnerId = getText(0),
        sourceGeneration = getText(1),
        batchSequence = getLong(2),
        snapshotOrdinal = requireV16SnapshotOrdinal(getLong(3)),
        sourceFactId = getText(4),
        source = getText(5),
        factKind = getText(6),
        anchorId = getText(7),
        subject = getText(8),
        conversationGeneration = nullableLong(9),
        conversationId = nullableText(10),
        turnReceiptId = nullableText(11),
        evidenceRequestId = nullableText(12),
        responseFingerprint = getText(13),
        responseSummary = getText(14),
        occurredAtEpochMillis = getLong(15),
        sourceVersion = getText(16),
        sourcePayloadCanonicalFingerprint = getText(17),
        proofPresent = getLong(18),
        sourceProofCanonicalFingerprint = nullableText(19),
        sourceReferenceId = nullableText(20),
        targetKind = nullableText(21),
        targetDatabase = nullableText(22),
        targetId = nullableText(23),
        targetVersion = nullableText(24),
        targetCanonicalFingerprint = nullableText(25),
        attestedAtEpochMillis = nullableLong(26),
        sourceRecordCanonicalFingerprint = getText(27),
        snapshotCanonicalFingerprint = getText(28),
    )

private fun SQLiteStatement.bindV17LegacySnapshot(
    row: V16LegacySnapshotRow,
    encrypted: LearnerMasteryEncryptedLegacyResponseSummary,
) {
    bindText(1, row.learnerId)
    bindText(2, row.sourceGeneration)
    bindLong(3, row.batchSequence)
    bindLong(4, row.snapshotOrdinal.toLong())
    bindText(5, row.sourceFactId)
    bindText(6, row.source)
    bindText(7, row.factKind)
    bindText(8, row.anchorId)
    bindText(9, row.subject)
    bindNullableLong(10, row.conversationGeneration)
    bindNullableText(11, row.conversationId)
    bindNullableText(12, row.turnReceiptId)
    bindNullableText(13, row.evidenceRequestId)
    bindText(14, row.responseFingerprint)
    bindLong(15, encrypted.keyVersion.toLong())
    bindBlob(16, encrypted.nonce)
    bindBlob(17, encrypted.ciphertext)
    bindLong(18, row.occurredAtEpochMillis)
    bindText(19, row.sourceVersion)
    bindText(20, row.sourcePayloadCanonicalFingerprint)
    bindLong(21, row.proofPresent)
    bindNullableText(22, row.sourceProofCanonicalFingerprint)
    bindNullableText(23, row.sourceReferenceId)
    bindNullableText(24, row.targetKind)
    bindNullableText(25, row.targetDatabase)
    bindNullableText(26, row.targetId)
    bindNullableText(27, row.targetVersion)
    bindNullableText(28, row.targetCanonicalFingerprint)
    bindNullableLong(29, row.attestedAtEpochMillis)
    bindText(30, row.sourceRecordCanonicalFingerprint)
    bindText(31, row.snapshotCanonicalFingerprint)
}

private fun SQLiteStatement.nullableText(index: Int): String? =
    if (isNull(index)) null else getText(index)

private fun SQLiteStatement.nullableLong(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun requireV16SnapshotOrdinal(value: Long): Int {
    check(value in 0L..Int.MAX_VALUE.toLong()) {
        "Legacy response-summary snapshot ordinal is outside its schema bound"
    }
    return value.toInt()
}

private fun SQLiteStatement.bindNullableText(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindText(index, value)
}

private fun SQLiteStatement.bindNullableLong(index: Int, value: Long?) {
    if (value == null) bindNull(index) else bindLong(index, value)
}

private data class V16LegacySnapshotRow(
    val learnerId: String,
    val sourceGeneration: String,
    val batchSequence: Long,
    val snapshotOrdinal: Int,
    val sourceFactId: String,
    val source: String,
    val factKind: String,
    val anchorId: String,
    val subject: String,
    val conversationGeneration: Long?,
    val conversationId: String?,
    val turnReceiptId: String?,
    val evidenceRequestId: String?,
    val responseFingerprint: String,
    val responseSummary: String,
    val occurredAtEpochMillis: Long,
    val sourceVersion: String,
    val sourcePayloadCanonicalFingerprint: String,
    val proofPresent: Long,
    val sourceProofCanonicalFingerprint: String?,
    val sourceReferenceId: String?,
    val targetKind: String?,
    val targetDatabase: String?,
    val targetId: String?,
    val targetVersion: String?,
    val targetCanonicalFingerprint: String?,
    val attestedAtEpochMillis: Long?,
    val sourceRecordCanonicalFingerprint: String,
    val snapshotCanonicalFingerprint: String,
) {
    fun responseSummaryBinding() =
        LearnerMasteryLegacyResponseSummaryBinding(
            learnerId = learnerId,
            sourceGeneration = sourceGeneration,
            batchSequence = batchSequence,
            snapshotOrdinal = snapshotOrdinal,
            sourceFactId = sourceFactId,
            sourceRecordCanonicalFingerprint = sourceRecordCanonicalFingerprint,
            snapshotCanonicalFingerprint = snapshotCanonicalFingerprint,
        )
}

internal const val LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_METADATA_KEY =
    "legacy_response_summary_at_rest_hygiene"
internal const val LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_PENDING = "PENDING"
internal const val LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_HYGIENE_COMPLETE = "COMPLETE"

private const val LEGACY_SNAPSHOT_TABLE = "mastery_legacy_observation_snapshot"
private const val LEGACY_SNAPSHOT_V16_PLAINTEXT_TABLE =
    "mastery_legacy_observation_snapshot_v16_plaintext"

private val V16_LEGACY_SNAPSHOT_GUARD_NAMES =
    listOf(
        "immutable_mastery_legacy_observation_snapshot_update",
        "immutable_mastery_legacy_observation_snapshot_delete",
        "sealed_mastery_legacy_observation_snapshot_insert",
    )

private val CREATE_ENCRYPTED_LEGACY_SNAPSHOT_TABLE_SQL =
    """
    CREATE TABLE IF NOT EXISTS `$LEGACY_SNAPSHOT_TABLE` (
        `learner_id` TEXT NOT NULL,
        `source_generation` TEXT NOT NULL,
        `batch_sequence` INTEGER NOT NULL,
        `snapshot_ordinal` INTEGER NOT NULL,
        `source_fact_id` TEXT NOT NULL,
        `source` TEXT NOT NULL,
        `fact_kind` TEXT NOT NULL,
        `anchor_id` TEXT NOT NULL,
        `subject` TEXT NOT NULL,
        `conversation_generation` INTEGER,
        `conversation_id` TEXT,
        `turn_receipt_id` TEXT,
        `evidence_request_id` TEXT,
        `response_fingerprint` TEXT NOT NULL,
        `key_version` INTEGER NOT NULL,
        `nonce` BLOB NOT NULL,
        `ciphertext` BLOB NOT NULL,
        `occurred_at_epoch_millis` INTEGER NOT NULL,
        `source_version` TEXT NOT NULL,
        `source_payload_canonical_fingerprint` TEXT NOT NULL,
        `proof_present` INTEGER NOT NULL,
        `source_proof_canonical_fingerprint` TEXT,
        `source_reference_id` TEXT,
        `target_kind` TEXT,
        `target_database` TEXT,
        `target_id` TEXT,
        `target_version` TEXT,
        `target_canonical_fingerprint` TEXT,
        `attested_at_epoch_millis` INTEGER,
        `source_record_canonical_fingerprint` TEXT NOT NULL,
        `snapshot_canonical_fingerprint` TEXT NOT NULL,
        PRIMARY KEY(`learner_id`, `source_generation`, `batch_sequence`, `snapshot_ordinal`),
        FOREIGN KEY(`learner_id`, `source_generation`, `batch_sequence`)
            REFERENCES `mastery_legacy_observation_snapshot_page`
                (`learner_id`, `source_generation`, `batch_sequence`)
            ON UPDATE NO ACTION ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
    )
    """.trimIndent()

private val CREATE_LEGACY_SNAPSHOT_SOURCE_FACT_INDEX_SQL =
    """
    CREATE UNIQUE INDEX IF NOT EXISTS
        `index_mastery_legacy_observation_snapshot_learner_id_source_generation_source_fact_id`
    ON `$LEGACY_SNAPSHOT_TABLE` (`learner_id`, `source_generation`, `source_fact_id`)
    """.trimIndent()

private val READ_V16_LEGACY_SNAPSHOTS_SQL =
    """
    SELECT `learner_id`, `source_generation`, `batch_sequence`, `snapshot_ordinal`,
           `source_fact_id`, `source`, `fact_kind`, `anchor_id`, `subject`,
           `conversation_generation`, `conversation_id`, `turn_receipt_id`,
           `evidence_request_id`, `response_fingerprint`, `response_summary`,
           `occurred_at_epoch_millis`, `source_version`,
           `source_payload_canonical_fingerprint`, `proof_present`,
           `source_proof_canonical_fingerprint`, `source_reference_id`, `target_kind`,
           `target_database`, `target_id`, `target_version`,
           `target_canonical_fingerprint`, `attested_at_epoch_millis`,
           `source_record_canonical_fingerprint`, `snapshot_canonical_fingerprint`
    FROM `$LEGACY_SNAPSHOT_V16_PLAINTEXT_TABLE`
    ORDER BY `learner_id`, `source_generation`, `batch_sequence`, `snapshot_ordinal`
    """.trimIndent()

private val INSERT_V17_LEGACY_SNAPSHOT_SQL =
    """
    INSERT INTO `$LEGACY_SNAPSHOT_TABLE` (
        `learner_id`, `source_generation`, `batch_sequence`, `snapshot_ordinal`,
        `source_fact_id`, `source`, `fact_kind`, `anchor_id`, `subject`,
        `conversation_generation`, `conversation_id`, `turn_receipt_id`,
        `evidence_request_id`, `response_fingerprint`, `key_version`, `nonce`,
        `ciphertext`, `occurred_at_epoch_millis`, `source_version`,
        `source_payload_canonical_fingerprint`, `proof_present`,
        `source_proof_canonical_fingerprint`, `source_reference_id`, `target_kind`,
        `target_database`, `target_id`, `target_version`,
        `target_canonical_fingerprint`, `attested_at_epoch_millis`,
        `source_record_canonical_fingerprint`, `snapshot_canonical_fingerprint`
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
              ?, ?, ?, ?, ?)
    """.trimIndent()
