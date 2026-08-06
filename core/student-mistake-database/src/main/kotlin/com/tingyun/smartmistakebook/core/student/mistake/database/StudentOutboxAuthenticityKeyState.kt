package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.storage.StudentOutboxAuthenticityProof
import java.security.MessageDigest
import java.util.UUID

/**
 * Non-secret database anchor for the one active outbox-authenticity key generation.
 *
 * The HMAC key remains non-exportable in Android Keystore. Presence of this row changes bootstrap
 * semantics permanently: if its corresponding alias disappears, callers must fail closed rather
 * than generate a replacement under the same version.
 */
@Entity(tableName = STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE)
internal data class StudentOutboxAuthenticityKeyStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int,
    @ColumnInfo(name = "key_id")
    val keyId: String,
    @ColumnInfo(name = "key_version")
    val keyVersion: Int,
    @ColumnInfo(name = "key_alias")
    val keyAlias: String,
    @ColumnInfo(name = "algorithm_version")
    val algorithmVersion: String,
    @ColumnInfo(name = "source_store_generation")
    val sourceStoreGeneration: String,
    @ColumnInfo(name = "relay_epoch")
    val relayEpoch: String,
    val state: String,
    @ColumnInfo(name = "activated_at_epoch_millis")
    val activatedAtEpochMillis: Long,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "keyed_sentinel_tag")
    val keyedSentinelTag: String,
) {
    init {
        require(singletonId == SINGLETON_ID) {
            "Student-outbox authenticity key state must use the singleton id"
        }
        requireKeyStateText(keyId, "Student-outbox authenticity key id")
        requireKeyStateText(keyAlias, "Student-outbox authenticity key alias")
        require(keyVersion == StudentOutboxAuthenticator.KEY_VERSION) {
            "Unsupported student-outbox authenticity key version"
        }
        require(algorithmVersion == StudentOutboxAuthenticityProof.ALGORITHM_VERSION) {
            "Unsupported student-outbox authenticity key algorithm"
        }
        require(state == ACTIVE_STATE) {
            "Unsupported student-outbox authenticity key state"
        }
        requireKeyStateText(sourceStoreGeneration, "Student store generation")
        requireKeyStateText(relayEpoch, "Student-outbox relay epoch")
        require(activatedAtEpochMillis >= 0L) {
            "Student-outbox authenticity activation time must not be negative"
        }
        require(canonicalFingerprint == expectedCanonicalFingerprint()) {
            "Student-outbox authenticity key state fingerprint mismatch"
        }
        requireLowercaseKeyStateSha256(keyedSentinelTag, "Student-outbox keyed sentinel")
    }

    fun toActiveKey(): ActiveStudentOutboxAuthenticityKey =
        ActiveStudentOutboxAuthenticityKey(
            keyId = keyId,
            keyVersion = keyVersion,
            keyAlias = keyAlias,
            algorithmVersion = algorithmVersion,
            sourceStoreGeneration = sourceStoreGeneration,
            relayEpoch = relayEpoch,
        )

    private fun expectedCanonicalFingerprint(): String =
        keyStateCanonicalFingerprint(
            singletonId = singletonId,
            keyId = keyId,
            keyVersion = keyVersion,
            keyAlias = keyAlias,
            algorithmVersion = algorithmVersion,
            sourceStoreGeneration = sourceStoreGeneration,
            relayEpoch = relayEpoch,
            state = state,
            activatedAtEpochMillis = activatedAtEpochMillis,
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
        ): StudentOutboxAuthenticityKeyStateEntity =
            StudentOutboxAuthenticityKeyStateEntity(
                singletonId = SINGLETON_ID,
                keyId = keyId,
                keyVersion = StudentOutboxAuthenticator.KEY_VERSION,
                keyAlias = keyAlias,
                algorithmVersion = StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
                sourceStoreGeneration = sourceStoreGeneration,
                relayEpoch = relayEpoch,
                state = ACTIVE_STATE,
                activatedAtEpochMillis = activatedAtEpochMillis,
                canonicalFingerprint =
                    keyStateCanonicalFingerprint(
                        singletonId = SINGLETON_ID,
                        keyId = keyId,
                        keyVersion = StudentOutboxAuthenticator.KEY_VERSION,
                        keyAlias = keyAlias,
                        algorithmVersion =
                            StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
                        sourceStoreGeneration = sourceStoreGeneration,
                        relayEpoch = relayEpoch,
                        state = ACTIVE_STATE,
                        activatedAtEpochMillis = activatedAtEpochMillis,
                    ),
                keyedSentinelTag = keyedSentinelTag,
            )
    }
}

internal data class ActiveStudentOutboxAuthenticityKey(
    val keyId: String,
    val keyVersion: Int,
    val keyAlias: String,
    val algorithmVersion: String,
    val sourceStoreGeneration: String,
    val relayEpoch: String,
) {
    init {
        requireKeyStateText(keyId, "Student-outbox authenticity key id")
        requireKeyStateText(keyAlias, "Student-outbox authenticity key alias")
        require(keyVersion == StudentOutboxAuthenticator.KEY_VERSION)
        require(algorithmVersion == StudentOutboxAuthenticityProof.ALGORITHM_VERSION)
        requireKeyStateText(sourceStoreGeneration, "Student store generation")
        requireKeyStateText(relayEpoch, "Student-outbox relay epoch")
    }
}

/** Narrow persistence port; the implementing DAO is installed during database integration. */
internal interface StudentOutboxAuthenticityKeyStatePort {
    suspend fun read(): StudentOutboxAuthenticityKeyStateEntity?

    /** Returns true only for the first durable singleton row. */
    suspend fun insertIfAbsent(state: StudentOutboxAuthenticityKeyStateEntity): Boolean
}

/**
 * Crash-safe first-install bootstrap.
 *
 * A Keystore alias may exist without a state row if the process died between key generation and
 * the database insert. Reusing that orphan is safe because no runtime capability was published.
 * The inverse state (durable row, missing alias) is key loss and is never repaired implicitly.
 */
internal class StudentOutboxAuthenticityKeyBootstrap(
    private val statePort: StudentOutboxAuthenticityKeyStatePort,
    private val keyStore: StudentOutboxHmacKeyStore,
    private val sourceStoreGeneration: String,
    private val nowEpochMillis: () -> Long,
    private val newKeyId: () -> String = { "student-outbox-auth-key:${UUID.randomUUID()}" },
    private val newKeyAlias: () -> String = {
        "$STUDENT_OUTBOX_AUTHENTICITY_KEY_ALIAS_PREFIX.${UUID.randomUUID()}"
    },
    private val newRelayEpoch: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun loadOrProvision(): ActiveStudentOutboxAuthenticityKey {
        statePort.read()?.let { existing ->
            check(existing.sourceStoreGeneration == sourceStoreGeneration) {
                "Student-outbox authenticity key is bound to another store generation"
            }
            val active = existing.toActiveKey()
            requireExistingAuthority(existing)
            return active
        }

        val keyId = newKeyId()
        val keyAlias = newKeyAlias()
        val relayEpoch = newRelayEpoch()
        keyStore.loadOrCreateBootstrap(keyAlias)
        val activatedAt = nowEpochMillis().also { now ->
            require(now >= 0L) {
                "Student-outbox authenticity activation time must not be negative"
            }
        }
        val stateFingerprint =
            keyStateCanonicalFingerprint(
                singletonId = StudentOutboxAuthenticityKeyStateEntity.SINGLETON_ID,
                keyId = keyId,
                keyVersion = StudentOutboxAuthenticator.KEY_VERSION,
                keyAlias = keyAlias,
                algorithmVersion = StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
                sourceStoreGeneration = sourceStoreGeneration,
                relayEpoch = relayEpoch,
                state = StudentOutboxAuthenticityKeyStateEntity.ACTIVE_STATE,
                activatedAtEpochMillis = activatedAt,
            )
        val candidate =
            StudentOutboxAuthenticityKeyStateEntity.active(
                keyId = keyId,
                keyAlias = keyAlias,
                sourceStoreGeneration = sourceStoreGeneration,
                relayEpoch = relayEpoch,
                activatedAtEpochMillis = activatedAt,
                keyedSentinelTag =
                    keyStore.issueHmac(
                        keyAlias,
                        keyStateSentinelContextFingerprint(
                            stateFingerprint,
                            sourceStoreGeneration,
                            relayEpoch,
                        ),
                    ),
            )
        statePort.insertIfAbsent(candidate)
        val durable = checkNotNull(statePort.read()) {
            "Student-outbox authenticity key state was not durably published"
        }
        val active = durable.toActiveKey()
        requireExistingAuthority(durable)
        return active
    }

    private fun requireExistingAuthority(state: StudentOutboxAuthenticityKeyStateEntity) {
        if (!keyStore.contains(state.keyAlias)) {
            throw SecurityException("Student outbox authenticity key is unavailable")
        }
        val expected =
            keyStore.issueHmac(
                state.keyAlias,
                keyStateSentinelContextFingerprint(
                    state.canonicalFingerprint,
                    state.sourceStoreGeneration,
                    state.relayEpoch,
                ),
            )
        if (!constantTimeKeyStateSha256Equals(expected, state.keyedSentinelTag)) {
            throw SecurityException("Student outbox authenticity key-state sentinel mismatch")
        }
    }
}

/**
 * Completes key bootstrap before Room publishes an opened student-store database.
 *
 * The callback runs for both a fresh schema and a migrated v17 schema. It first replaces the
 * security triggers with their canonical definitions, then provisions only when no durable state
 * exists. A durable state with a missing Keystore alias aborts database open.
 */
internal val STUDENT_OUTBOX_AUTHENTICITY_DATABASE_CALLBACK =
    object : RoomDatabase.Callback() {
        override suspend fun onCreate(connection: SQLiteConnection) {
            prepareStudentOutboxAuthenticity(connection)
        }

        override suspend fun onOpen(connection: SQLiteConnection) {
            prepareStudentOutboxAuthenticity(connection)
        }
    }

private suspend fun prepareStudentOutboxAuthenticity(connection: SQLiteConnection) {
    createStudentOutboxAuthenticityV18ImmutabilityTriggers(connection)
    createStudentMasteryRelayV19Guards(connection)
    val sourceStoreGeneration = ensureStudentStoreGeneration(connection)
    val active =
        StudentOutboxAuthenticityKeyBootstrap(
            statePort = SQLiteStudentOutboxAuthenticityKeyStatePort(connection),
            keyStore = AndroidKeystoreStudentOutboxHmacKeyStore.INSTANCE,
            sourceStoreGeneration = sourceStoreGeneration,
            nowEpochMillis = System::currentTimeMillis,
        ).loadOrProvision()
    check(active.sourceStoreGeneration == sourceStoreGeneration)
    verifyStudentOutboxAuthenticityV18ImmutabilityTriggers(connection)
}

private fun ensureStudentStoreGeneration(connection: SQLiteConnection): String {
    val now = System.currentTimeMillis()
    connection.prepare(
        """
        INSERT OR IGNORE INTO student_store_metadata (
            metadata_key, metadata_value, created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.bindText(1, STORE_GENERATION_METADATA_KEY)
        statement.bindText(2, UUID.randomUUID().toString())
        statement.bindLong(3, now)
        statement.bindLong(4, now)
        statement.step()
    }
    return connection.prepare(
        """
        SELECT metadata_value
        FROM student_store_metadata
        WHERE metadata_key = ?
        LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.bindText(1, STORE_GENERATION_METADATA_KEY)
        check(statement.step()) { "Student mistake store generation was not persisted" }
        statement.getText(0).also { generation ->
            requireKeyStateText(generation, "Student store generation")
        }
    }
}

private class SQLiteStudentOutboxAuthenticityKeyStatePort(
    private val connection: SQLiteConnection,
) : StudentOutboxAuthenticityKeyStatePort {
    override suspend fun read(): StudentOutboxAuthenticityKeyStateEntity? =
        connection.prepare(
            """
            SELECT singleton_id,
                   key_id,
                   key_version,
                   key_alias,
                   algorithm_version,
                   source_store_generation,
                   relay_epoch,
                   state,
                   activated_at_epoch_millis,
                   canonical_fingerprint,
                   keyed_sentinel_tag
            FROM `$STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE`
            WHERE singleton_id = 1
            """.trimIndent(),
        ).use { statement ->
            if (!statement.step()) return@use null
            StudentOutboxAuthenticityKeyStateEntity(
                singletonId = Math.toIntExact(statement.getLong(0)),
                keyId = statement.getText(1),
                keyVersion = Math.toIntExact(statement.getLong(2)),
                keyAlias = statement.getText(3),
                algorithmVersion = statement.getText(4),
                sourceStoreGeneration = statement.getText(5),
                relayEpoch = statement.getText(6),
                state = statement.getText(7),
                activatedAtEpochMillis = statement.getLong(8),
                canonicalFingerprint = statement.getText(9),
                keyedSentinelTag = statement.getText(10),
            )
        }

    override suspend fun insertIfAbsent(
        state: StudentOutboxAuthenticityKeyStateEntity,
    ): Boolean {
        connection.prepare(
            """
            INSERT OR IGNORE INTO `$STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE` (
                singleton_id,
                key_id,
                key_version,
                key_alias,
                algorithm_version,
                source_store_generation,
                relay_epoch,
                state,
                activated_at_epoch_millis,
                canonical_fingerprint,
                keyed_sentinel_tag
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
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
        return connection.prepare("SELECT changes()").use { statement ->
            check(statement.step()) { "Expected one key-state insertion count" }
            statement.getLong(0) == 1L
        }
    }
}

private fun keyStateCanonicalFingerprint(
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
    CanonicalSha256("student-outbox-authenticity-key-state-v1")
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

private fun keyStateSentinelContextFingerprint(
    stateCanonicalFingerprint: String,
    sourceStoreGeneration: String,
    relayEpoch: String,
): String =
    CanonicalSha256("student-outbox-keyed-sentinel-context-v1")
        .field("stateCanonicalFingerprint", stateCanonicalFingerprint)
        .field("sourceStoreGeneration", sourceStoreGeneration)
        .field("relayEpoch", relayEpoch)
        .finish()

private fun constantTimeKeyStateSha256Equals(expected: String, actual: String): Boolean {
    if (!KEY_STATE_SHA_256.matches(expected) || !KEY_STATE_SHA_256.matches(actual)) return false
    return MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
}

private fun requireLowercaseKeyStateSha256(value: String, label: String) {
    require(KEY_STATE_SHA_256.matches(value)) { "$label must be a lowercase SHA-256 value" }
}

private fun requireKeyStateText(
    value: String,
    label: String,
) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= 256 &&
            value.none(Char::isISOControl),
    ) {
        "$label must be a trimmed non-blank value of at most 256 characters"
    }
}

internal const val STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE =
    "student_outbox_authenticity_key_state"
internal const val STUDENT_OUTBOX_AUTHENTICITY_KEY_ID =
    "student-outbox-auth-key:v1"
internal const val STUDENT_OUTBOX_AUTHENTICITY_KEY_ALIAS_PREFIX =
    "com.tingyun.smartmistakebook.student.outbox.authenticity.hmac"
private val KEY_STATE_SHA_256 = Regex("[0-9a-f]{64}")
