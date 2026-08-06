package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.storage.MasteryOutboxAuthenticityProof
import java.security.MessageDigest
import java.util.UUID

@Entity(tableName = MASTERY_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE)
internal data class MasteryOutboxAuthenticityKeyStateEntity(
    @PrimaryKey @ColumnInfo(name = "singleton_id") val singletonId: Int,
    @ColumnInfo(name = "key_id") val keyId: String,
    @ColumnInfo(name = "key_version") val keyVersion: Int,
    @ColumnInfo(name = "key_alias") val keyAlias: String,
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: String,
    @ColumnInfo(name = "source_store_generation") val sourceStoreGeneration: String,
    @ColumnInfo(name = "relay_epoch") val relayEpoch: String,
    val state: String,
    @ColumnInfo(name = "activated_at_epoch_millis") val activatedAtEpochMillis: Long,
    @ColumnInfo(name = "canonical_fingerprint") val canonicalFingerprint: String,
    @ColumnInfo(name = "keyed_sentinel_tag") val keyedSentinelTag: String,
) {
    init {
        require(singletonId == SINGLETON_ID)
        requireMasteryAuthText(keyId, "Mastery-outbox key id")
        requireMasteryAuthText(keyAlias, "Mastery-outbox key alias")
        require(keyVersion == MasteryOutboxAuthenticator.KEY_VERSION)
        require(algorithmVersion == MasteryOutboxAuthenticityProof.ALGORITHM_VERSION)
        requireMasteryAuthText(sourceStoreGeneration, "Mastery store generation")
        requireMasteryAuthText(relayEpoch, "Mastery relay epoch")
        require(state == ACTIVE_STATE)
        require(activatedAtEpochMillis >= 0L)
        require(canonicalFingerprint == expectedFingerprint())
        require(MASTERY_AUTH_SHA256.matches(keyedSentinelTag))
    }

    fun toActiveKey(): ActiveMasteryOutboxAuthenticityKey =
        ActiveMasteryOutboxAuthenticityKey(
            keyId,
            keyVersion,
            keyAlias,
            algorithmVersion,
            sourceStoreGeneration,
            relayEpoch,
        )

    private fun expectedFingerprint(): String =
        masteryOutboxKeyStateFingerprint(
            singletonId,
            keyId,
            keyVersion,
            keyAlias,
            algorithmVersion,
            sourceStoreGeneration,
            relayEpoch,
            state,
            activatedAtEpochMillis,
        )

    companion object {
        const val SINGLETON_ID = 1
        const val ACTIVE_STATE = "ACTIVE"

        fun active(
            keyId: String,
            keyAlias: String,
            sourceStoreGeneration: String,
            relayEpoch: String,
            activatedAtEpochMillis: Long,
            keyedSentinelTag: String,
        ): MasteryOutboxAuthenticityKeyStateEntity =
            MasteryOutboxAuthenticityKeyStateEntity(
                singletonId = SINGLETON_ID,
                keyId = keyId,
                keyVersion = MasteryOutboxAuthenticator.KEY_VERSION,
                keyAlias = keyAlias,
                algorithmVersion = MasteryOutboxAuthenticityProof.ALGORITHM_VERSION,
                sourceStoreGeneration = sourceStoreGeneration,
                relayEpoch = relayEpoch,
                state = ACTIVE_STATE,
                activatedAtEpochMillis = activatedAtEpochMillis,
                canonicalFingerprint =
                    masteryOutboxKeyStateFingerprint(
                        SINGLETON_ID,
                        keyId,
                        MasteryOutboxAuthenticator.KEY_VERSION,
                        keyAlias,
                        MasteryOutboxAuthenticityProof.ALGORITHM_VERSION,
                        sourceStoreGeneration,
                        relayEpoch,
                        ACTIVE_STATE,
                        activatedAtEpochMillis,
                    ),
                keyedSentinelTag = keyedSentinelTag,
            )
    }
}

internal data class ActiveMasteryOutboxAuthenticityKey(
    val keyId: String,
    val keyVersion: Int,
    val keyAlias: String,
    val algorithmVersion: String,
    val sourceStoreGeneration: String,
    val relayEpoch: String,
)

internal suspend fun prepareMasteryOutboxAuthenticity(connection: SQLiteConnection) {
    createMasteryOutboxAuthenticityGuards(connection)
    val sourceGeneration = ensureMasteryOutboxStoreGeneration(connection)
    val keyStore = AndroidKeystoreMasteryOutboxHmacKeyStore.INSTANCE
    val existing = readMasteryOutboxKeyState(connection)
    if (existing != null) {
        check(existing.sourceStoreGeneration == sourceGeneration) {
            "Mastery-outbox key belongs to another store generation"
        }
        requireMasteryOutboxKeyAuthority(existing, keyStore)
        return
    }

    val keyId = "mastery-outbox-auth-key:${UUID.randomUUID()}"
    val keyAlias = "$MASTERY_OUTBOX_AUTHENTICITY_KEY_ALIAS_PREFIX.${UUID.randomUUID()}"
    val relayEpoch = UUID.randomUUID().toString()
    val activatedAt = System.currentTimeMillis().also { require(it >= 0L) }
    keyStore.loadOrCreateBootstrap(keyAlias)
    val stateFingerprint =
        masteryOutboxKeyStateFingerprint(
            1,
            keyId,
            MasteryOutboxAuthenticator.KEY_VERSION,
            keyAlias,
            MasteryOutboxAuthenticityProof.ALGORITHM_VERSION,
            sourceGeneration,
            relayEpoch,
            MasteryOutboxAuthenticityKeyStateEntity.ACTIVE_STATE,
            activatedAt,
        )
    val candidate =
        MasteryOutboxAuthenticityKeyStateEntity.active(
            keyId = keyId,
            keyAlias = keyAlias,
            sourceStoreGeneration = sourceGeneration,
            relayEpoch = relayEpoch,
            activatedAtEpochMillis = activatedAt,
            keyedSentinelTag =
                keyStore.issueHmac(
                    keyAlias,
                    masteryOutboxSentinelContext(stateFingerprint, sourceGeneration, relayEpoch),
                ),
        )
    insertMasteryOutboxKeyState(connection, candidate)
    val durable = checkNotNull(readMasteryOutboxKeyState(connection))
    requireMasteryOutboxKeyAuthority(durable, keyStore)
}

private fun ensureMasteryOutboxStoreGeneration(connection: SQLiteConnection): String {
    readMasteryStoreGeneration(connection)?.let { return it }
    val candidate = UUID.randomUUID().toString()
    connection.prepare(
        """
        INSERT INTO mastery_store_metadata (metadata_key, metadata_value)
        VALUES ('store_generation', ?)
        """.trimIndent(),
    ).use { statement ->
        statement.bindText(1, candidate)
        statement.step()
    }
    return checkNotNull(readMasteryStoreGeneration(connection)) {
        "Learner-mastery store generation was not persisted"
    }.also { durable ->
        check(durable == candidate) {
            "Learner-mastery store generation changed while authenticity was prepared"
        }
    }
}

private fun readMasteryStoreGeneration(connection: SQLiteConnection): String? =
    connection.prepare(
        """
        SELECT metadata_value
        FROM mastery_store_metadata
        WHERE metadata_key = 'store_generation'
        LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        if (!statement.step()) {
            null
        } else {
            statement.getText(0).also {
                requireMasteryAuthText(it, "Mastery store generation")
            }
        }
    }

private fun readMasteryOutboxKeyState(
    connection: SQLiteConnection,
): MasteryOutboxAuthenticityKeyStateEntity? =
    connection.prepare(
        """
        SELECT singleton_id, key_id, key_version, key_alias, algorithm_version,
               source_store_generation, relay_epoch, state, activated_at_epoch_millis,
               canonical_fingerprint, keyed_sentinel_tag
        FROM $MASTERY_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE
        WHERE singleton_id = 1
        """.trimIndent(),
    ).use { statement ->
        if (!statement.step()) return@use null
        MasteryOutboxAuthenticityKeyStateEntity(
            Math.toIntExact(statement.getLong(0)),
            statement.getText(1),
            Math.toIntExact(statement.getLong(2)),
            statement.getText(3),
            statement.getText(4),
            statement.getText(5),
            statement.getText(6),
            statement.getText(7),
            statement.getLong(8),
            statement.getText(9),
            statement.getText(10),
        )
    }

private fun insertMasteryOutboxKeyState(
    connection: SQLiteConnection,
    state: MasteryOutboxAuthenticityKeyStateEntity,
) {
    connection.prepare(
        """
        INSERT OR IGNORE INTO $MASTERY_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE (
            singleton_id, key_id, key_version, key_alias, algorithm_version,
            source_store_generation, relay_epoch, state, activated_at_epoch_millis,
            canonical_fingerprint, keyed_sentinel_tag
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.bindLong(1, state.singletonId.toLong())
        statement.bindText(2, state.keyId)
        statement.bindLong(3, state.keyVersion.toLong())
        statement.bindText(4, state.keyAlias)
        statement.bindText(5, state.algorithmVersion)
        statement.bindText(6, state.sourceStoreGeneration)
        statement.bindText(7, state.relayEpoch)
        statement.bindText(8, state.state)
        statement.bindLong(9, state.activatedAtEpochMillis)
        statement.bindText(10, state.canonicalFingerprint)
        statement.bindText(11, state.keyedSentinelTag)
        statement.step()
    }
}

private fun requireMasteryOutboxKeyAuthority(
    state: MasteryOutboxAuthenticityKeyStateEntity,
    keyStore: MasteryOutboxHmacKeyStore,
) {
    if (!keyStore.contains(state.keyAlias)) {
        throw SecurityException("Learner-mastery outbox key is unavailable")
    }
    val expected =
        keyStore.issueHmac(
            state.keyAlias,
            masteryOutboxSentinelContext(
                state.canonicalFingerprint,
                state.sourceStoreGeneration,
                state.relayEpoch,
            ),
        )
    if (!MessageDigest.isEqual(expected.toByteArray(), state.keyedSentinelTag.toByteArray())) {
        throw SecurityException("Learner-mastery outbox key-state sentinel mismatch")
    }
}

internal fun createMasteryOutboxAuthenticityGuards(connection: SQLiteConnection) {
    MASTERY_OUTBOX_AUTHENTICITY_GUARDS.values.forEach(connection::execSQL)
}

private val MASTERY_OUTBOX_AUTHENTICITY_GUARDS = linkedMapOf(
    "immutable_mastery_outbox_key_update" to
        """
        CREATE TRIGGER IF NOT EXISTS immutable_mastery_outbox_key_update
        BEFORE UPDATE ON $MASTERY_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE
        BEGIN SELECT RAISE(ABORT, 'immutable mastery outbox key state'); END
        """.trimIndent(),
    "immutable_mastery_outbox_key_delete" to
        """
        CREATE TRIGGER IF NOT EXISTS immutable_mastery_outbox_key_delete
        BEFORE DELETE ON $MASTERY_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE
        BEGIN SELECT RAISE(ABORT, 'immutable mastery outbox key state'); END
        """.trimIndent(),
    "immutable_mastery_legacy_outbox_quarantine_update" to
        """
        CREATE TRIGGER IF NOT EXISTS immutable_mastery_legacy_outbox_quarantine_update
        BEFORE UPDATE ON mastery_pre_auth_outbox_quarantine
        BEGIN SELECT RAISE(ABORT, 'immutable mastery outbox quarantine'); END
        """.trimIndent(),
    "immutable_mastery_legacy_outbox_quarantine_delete" to
        """
        CREATE TRIGGER IF NOT EXISTS immutable_mastery_legacy_outbox_quarantine_delete
        BEFORE DELETE ON mastery_pre_auth_outbox_quarantine
        BEGIN SELECT RAISE(ABORT, 'immutable mastery outbox quarantine'); END
        """.trimIndent(),
)

private fun masteryOutboxKeyStateFingerprint(
    singletonId: Int,
    keyId: String,
    keyVersion: Int,
    keyAlias: String,
    algorithmVersion: String,
    sourceStoreGeneration: String,
    relayEpoch: String,
    state: String,
    activatedAtEpochMillis: Long,
): String =
    CanonicalSha256("learner-mastery-outbox-key-state-v1")
        .field("singletonId", singletonId)
        .field("keyId", keyId)
        .field("keyVersion", keyVersion)
        .field("keyAlias", keyAlias)
        .field("algorithmVersion", algorithmVersion)
        .field("sourceStoreGeneration", sourceStoreGeneration)
        .field("relayEpoch", relayEpoch)
        .field("state", state)
        .field("activatedAtEpochMillis", activatedAtEpochMillis)
        .finish()

private fun masteryOutboxSentinelContext(
    stateFingerprint: String,
    sourceStoreGeneration: String,
    relayEpoch: String,
): String =
    CanonicalSha256("learner-mastery-outbox-keyed-sentinel-context-v1")
        .field("stateCanonicalFingerprint", stateFingerprint)
        .field("sourceStoreGeneration", sourceStoreGeneration)
        .field("relayEpoch", relayEpoch)
        .finish()

private fun requireMasteryAuthText(value: String, label: String) {
    require(
        value.isNotBlank() && value == value.trim() && value.length <= 256 &&
            value.none(Char::isISOControl),
    ) { "$label is invalid" }
}

internal const val MASTERY_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE =
    "mastery_outbox_authenticity_key_state"
internal const val MASTERY_OUTBOX_AUTHENTICITY_KEY_ALIAS_PREFIX =
    "com.tingyun.smartmistakebook.mastery.outbox.authenticity.hmac"
private val MASTERY_AUTH_SHA256 = Regex("[0-9a-f]{64}")
