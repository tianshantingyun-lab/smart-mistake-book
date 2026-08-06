package com.tingyun.smartmistakebook.feature.tutor

import android.content.Context
import android.content.SharedPreferences
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Durable owner for accepted local-recovery presentation credentials. */
internal interface TutorLocalRecoveryPresentationStore {
    suspend fun read(scopeKey: String): List<TutorLocalRecoveryPresentationCredential>

    /** Returns only after the accepted credential is durably committed. */
    suspend fun persist(
        scopeKey: String,
        credential: TutorLocalRecoveryPresentationCredential,
    ): Boolean
}

internal interface TutorLocalRecoveryPresentationStorage {
    fun readAll(): Map<String, String>

    fun commit(
        upserts: Map<String, String>,
        removals: Set<String>,
    ): Boolean
}

/**
 * Bounded persistent ledger. Limits apply to decoded records and UTF-8 bytes, so neither a large
 * credential nor many old questions can grow Activity saved state or the backing file without
 * bound. Oldest accepted history is cleaned first; the current write is never reported successful
 * unless its exact record remains in the committed ledger.
 */
internal class BoundedTutorLocalRecoveryPresentationStore(
    private val storage: TutorLocalRecoveryPresentationStorage,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : TutorLocalRecoveryPresentationStore {
    override suspend fun read(
        scopeKey: String,
    ): List<TutorLocalRecoveryPresentationCredential> = withContext(workerDispatcher) {
        requireScopeKey(scopeKey)
        processMutex.withLock {
            val encoded = storage.readAll()[scopeKey] ?: return@withLock emptyList()
            val record = decodeScopedRecord(scopeKey, encoded)
            if (record == null) {
                storage.commit(emptyMap(), setOf(scopeKey))
                emptyList()
            } else {
                record.credentials
            }
        }
    }

    override suspend fun persist(
        scopeKey: String,
        credential: TutorLocalRecoveryPresentationCredential,
    ): Boolean = withContext(workerDispatcher) {
        requireScopeKey(scopeKey)
        if (credential.presentationScopeKey != scopeKey) return@withContext false
        val encodedCredential = credential.encode()
        if (encodedCredential.utf8Size() > MAX_CREDENTIAL_BYTES) return@withContext false
        processMutex.withLock {
            val stored = storage.readAll()
            val removals = stored.keys.filterTo(linkedSetOf()) { key ->
                !key.matches(SHA_256) || decodeScopedRecord(key, stored.getValue(key)) == null
            }
            val records = stored.mapNotNull { (key, value) ->
                if (key in removals) null else decodeScopedRecord(key, value)?.let { key to it }
            }.toMap(linkedMapOf())
            val previous = records[scopeKey]?.credentials.orEmpty()
            val deduplicated = previous
                .filterNot { existing ->
                    existing.recoveredRequestId == credential.recoveredRequestId
                } + credential
            val now = clock().coerceAtLeast(0L)
            val boundedCredentials = boundCurrentScope(deduplicated, now)
                ?: return@withLock false
            val currentRecord = ScopeRecord(
                updatedAtEpochMillis = now,
                credentials = boundedCredentials,
            )
            val retained = LinkedHashMap(records)
            retained[scopeKey] = currentRecord
            val encodedRecords = retained.mapValues { (_, record) -> encodeRecord(record) }
                .toMutableMap()
            val evictionOrder = retained.entries
                .filterNot { (key, _) -> key == scopeKey }
                .sortedWith(
                    compareBy<Map.Entry<String, ScopeRecord>> { it.value.updatedAtEpochMillis }
                        .thenBy { it.key },
                )
                .map(Map.Entry<String, ScopeRecord>::key)
                .toMutableList()
            while (
                encodedRecords.size > MAX_SCOPES ||
                encodedRecords.totalUtf8Size() > MAX_TOTAL_BYTES
            ) {
                if (evictionOrder.isEmpty()) return@withLock false
                val evicted = evictionOrder.removeAt(0)
                encodedRecords.remove(evicted)
                removals += evicted
            }
            val encodedCurrent = encodedRecords[scopeKey] ?: return@withLock false
            storage.commit(
                upserts = mapOf(scopeKey to encodedCurrent),
                removals = removals - scopeKey,
            )
        }
    }

    private fun boundCurrentScope(
        credentials: List<TutorLocalRecoveryPresentationCredential>,
        updatedAtEpochMillis: Long,
    ): List<TutorLocalRecoveryPresentationCredential>? {
        val bounded = credentials.takeLast(MAX_CREDENTIALS_PER_SCOPE).toMutableList()
        while (bounded.isNotEmpty()) {
            val encoded = encodeRecord(
                ScopeRecord(
                    updatedAtEpochMillis = updatedAtEpochMillis.coerceAtLeast(0L),
                    credentials = bounded,
                ),
            )
            if (encoded.utf8Size() <= MAX_SCOPE_BYTES) return bounded.toList()
            bounded.removeAt(0)
        }
        return null
    }

    private data class ScopeRecord(
        val updatedAtEpochMillis: Long,
        val credentials: List<TutorLocalRecoveryPresentationCredential>,
    )

    private companion object {
        const val MAX_CREDENTIALS_PER_SCOPE = 128
        const val MAX_CREDENTIAL_BYTES = 4_096
        const val MAX_SCOPE_BYTES = 64 * 1_024
        const val MAX_TOTAL_BYTES = 256 * 1_024
        const val MAX_SCOPES = 32
        const val RECORD_VERSION = "1"
        const val MAX_RECORD_FIELDS = MAX_CREDENTIALS_PER_SCOPE + 3
        val SHA_256 = Regex("^[0-9a-f]{64}$")
        val processMutex = Mutex()

        fun encodeRecord(record: ScopeRecord): String = encodeFields(
            listOf(
                RECORD_VERSION,
                record.updatedAtEpochMillis.toString(),
                record.credentials.size.toString(),
            ) + record.credentials.map { credential -> credential.encode() },
        )

        fun decodeRecord(encoded: String): ScopeRecord? = runCatching {
            if (encoded.length > MAX_SCOPE_BYTES || encoded.utf8Size() > MAX_SCOPE_BYTES) {
                return@runCatching null
            }
            val fields = decodeFields(encoded) ?: return@runCatching null
            if (fields.size !in 3..MAX_RECORD_FIELDS) return@runCatching null
            if (fields[0] != RECORD_VERSION) return@runCatching null
            val updatedAt = fields[1].toLong().takeIf { it >= 0 } ?: return@runCatching null
            val count = fields[2].toInt()
            if (count !in 0..MAX_CREDENTIALS_PER_SCOPE || fields.size != count + 3) {
                return@runCatching null
            }
            val credentials = fields.drop(3).map { field ->
                if (field.utf8Size() > MAX_CREDENTIAL_BYTES) return@runCatching null
                TutorLocalRecoveryPresentationCredential.decode(field)
                    ?: return@runCatching null
            }
            ScopeRecord(updatedAt, credentials)
        }.getOrNull()

        fun decodeScopedRecord(scopeKey: String, encoded: String): ScopeRecord? =
            decodeRecord(encoded)?.takeIf { record ->
                record.credentials.all { credential ->
                    credential.presentationScopeKey == scopeKey
                }
            }

        fun encodeFields(fields: List<String>): String = buildString {
            fields.forEach { field ->
                append(field.length)
                append(':')
                append(field)
            }
        }

        fun decodeFields(encoded: String): List<String>? {
            val result = ArrayList<String>(MAX_RECORD_FIELDS)
            var cursor = 0
            while (cursor < encoded.length && result.size <= MAX_RECORD_FIELDS) {
                val separator = encoded.indexOf(':', cursor)
                if (separator <= cursor || separator - cursor > 7) return null
                val length = encoded.substring(cursor, separator).toIntOrNull() ?: return null
                if (length !in 0..MAX_SCOPE_BYTES) return null
                val start = separator + 1
                val end = start + length
                if (end > encoded.length) return null
                result += encoded.substring(start, end)
                cursor = end
            }
            return result.takeIf { cursor == encoded.length }
        }

        fun requireScopeKey(scopeKey: String) {
            require(scopeKey.matches(SHA_256))
        }

        fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

        fun Map<String, String>.totalUtf8Size(): Int = entries.sumOf { (key, value) ->
            key.utf8Size() + value.utf8Size()
        }
    }
}

internal class AndroidTutorLocalRecoveryPresentationStore(
    context: Context,
) : TutorLocalRecoveryPresentationStore {
    private val delegate = BoundedTutorLocalRecoveryPresentationStore(
        storage = SharedPreferencesTutorLocalRecoveryPresentationStorage(
            context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ),
        ),
    )

    override suspend fun read(
        scopeKey: String,
    ): List<TutorLocalRecoveryPresentationCredential> = delegate.read(scopeKey)

    override suspend fun persist(
        scopeKey: String,
        credential: TutorLocalRecoveryPresentationCredential,
    ): Boolean = delegate.persist(scopeKey, credential)

    private companion object {
        const val PREFERENCES_NAME = "tutor_local_recovery_presentation_v1"
    }
}

private class SharedPreferencesTutorLocalRecoveryPresentationStorage(
    private val preferences: SharedPreferences,
) : TutorLocalRecoveryPresentationStorage {
    override fun readAll(): Map<String, String> = preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it }
    }.toMap()

    override fun commit(
        upserts: Map<String, String>,
        removals: Set<String>,
    ): Boolean = preferences.edit().apply {
        removals.forEach { key -> remove(key) }
        upserts.forEach { (key, value) -> putString(key, value) }
    }.commit()
}

internal fun localRecoveryPresentationScopeKey(
    sessionId: String,
    revisionNumber: Int,
    questionDocumentFingerprint: String,
): String = CanonicalSha256("tutor-local-recovery-presentation-scope-v1")
    .field("sessionId", sessionId)
    .field("revisionNumber", revisionNumber)
    .field("questionDocumentFingerprint", questionDocumentFingerprint)
    .finish()
