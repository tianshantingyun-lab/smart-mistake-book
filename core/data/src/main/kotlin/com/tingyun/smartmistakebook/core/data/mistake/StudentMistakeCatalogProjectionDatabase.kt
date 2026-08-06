package com.tingyun.smartmistakebook.core.data.mistake

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.RoomRawQuery
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.tingyun.smartmistakebook.core.data.model.SharedWorkloadAdmissionGate
import com.tingyun.smartmistakebook.core.data.model.WorkloadCategory
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException

/**
 * Schema v1 for `student-mistake-library-projection.db`.
 *
 * This database is DERIVED, DISCARDABLE and NON_AUTHORITATIVE. It contains identifiers plus
 * discardable display projections (titles, knowledge labels, normalized/tokenized search text and
 * categorical mastery status), never authoritative problem bodies, events, mastery evidence or
 * knowledge bodies. Version upgrades deliberately use destructive rebuild; that policy must never
 * be copied to the three authority databases.
 */
@Database(
    entities = [
        DerivedStudentMistakeCatalogGenerationEntity::class,
        DerivedStudentMistakeCatalogEntryEntity::class,
        DerivedStudentMistakeCatalogEntryFtsEntity::class,
        DerivedStudentMistakeCatalogSectionEntity::class,
        DerivedStudentMistakeCatalogKnowledgeEntity::class,
    ],
    version = STUDENT_MISTAKE_CATALOG_PROJECTION_SCHEMA_VERSION,
    exportSchema = true,
)
internal abstract class StudentMistakeCatalogProjectionRoomDatabase : RoomDatabase() {
    abstract fun projectionDao(): StudentMistakeCatalogProjectionDao
}

internal object StudentMistakeCatalogProjectionDatabaseFactory {
    suspend fun open(context: Context): DiscardableStudentMistakeCatalogProjectionStore {
        return SharedWorkloadAdmissionGate.gate.withPermit(WorkloadCategory.PROJECTION) {
            openWithinPermit(context)
        }
    }

    private suspend fun openWithinPermit(
        context: Context,
    ): DiscardableStudentMistakeCatalogProjectionStore {
        val applicationContext = context.applicationContext
        fun build(): StudentMistakeCatalogProjectionRoomDatabase =
            Room.databaseBuilder(
                applicationContext,
                StudentMistakeCatalogProjectionRoomDatabase::class.java,
                STUDENT_MISTAKE_CATALOG_PROJECTION_DATABASE_NAME,
            ).fallbackToDestructiveMigration(true)
                .setDriver(AndroidSQLiteDriver())
                .build()

        var database = build()
        try {
            database.useConnection(isReadOnly = false) { Unit }
        } catch (cancelled: CancellationException) {
            database.close()
            throw cancelled
        } catch (failure: Throwable) {
            database.close()
            // Only the explicitly discardable projection is deleted. Authority databases are not
            // opened, attached, migrated, or mutated here.
            applicationContext.deleteDatabase(STUDENT_MISTAKE_CATALOG_PROJECTION_DATABASE_NAME)
            database = build()
            try {
                database.useConnection(isReadOnly = false) { Unit }
            } catch (rebuildFailure: Throwable) {
                database.close()
                rebuildFailure.addSuppressed(failure)
                throw rebuildFailure
            }
        }
        return RoomDiscardableStudentMistakeCatalogProjectionStore(applicationContext, database)
    }
}

internal data class DerivedStudentMistakeCatalogGeneration(
    val generationId: String,
    val learnerFingerprint: String,
    val revision: StudentMistakeCatalogRevision,
    val cursorAuthenticationKey: String,
    val indexedEntryCount: Long,
) {
    init {
        require(indexedEntryCount >= 0L) { "Derived catalog count must not be negative" }
    }
}

internal data class DerivedStudentMistakeCatalogFilterQuery(
    val ftsMatchExpression: String?,
    val normalizedSearchText: String?,
    val subject: String?,
    val favoriteOnly: Boolean,
    val masteryFilterDisabled: Boolean,
    val masteryStatuses: List<String>,
    val sectionStableId: String?,
    val knowledgeSubject: String?,
    val knowledgeNodeId: String?,
    val knowledgeTaxonomyVersion: String?,
    val knowledgePackVersion: String?,
)

internal data class DerivedStudentMistakeCatalogCursorKey(
    val changedAtEpochMillis: Long,
    val problemId: String,
    val entryId: String,
)

internal data class DerivedStudentMistakeCatalogPage(
    val rows: List<DerivedStudentMistakeCatalogEntryEntity>,
    val nextCursor: StudentMistakeCatalogCursor?,
)

internal data class DerivedStudentMistakeCatalogFacets(
    val totalCount: Long,
    val subjects: List<DerivedStudentMistakeCatalogSubjectFacetRow>,
    val sections: List<DerivedStudentMistakeCatalogSectionFacetRow>,
    val knowledge: List<DerivedStudentMistakeCatalogKnowledgeFacetRow>,
    val mastery: List<DerivedStudentMistakeCatalogMasteryFacetRow>,
    val truncatedDimensions: Set<StudentMistakeCatalogFacetDimension>,
)

internal interface DiscardableStudentMistakeCatalogProjectionStore : Closeable {
    suspend fun discardBuildingGenerations()

    suspend fun findReady(
        learnerFingerprint: String,
        revision: StudentMistakeCatalogRevision,
    ): DerivedStudentMistakeCatalogGeneration?

    suspend fun beginBuilding(
        learnerFingerprint: String,
        revision: StudentMistakeCatalogRevision,
        nowEpochMillis: Long,
    ): DerivedStudentMistakeCatalogGeneration

    suspend fun appendBatch(
        generation: DerivedStudentMistakeCatalogGeneration,
        batch: List<DerivedStudentMistakeCatalogBatchEntry>,
        indexedEntryCount: Long,
        nowEpochMillis: Long,
    )

    suspend fun discard(generationId: String)

    suspend fun promote(
        generation: DerivedStudentMistakeCatalogGeneration,
        nowEpochMillis: Long,
    ): Boolean

    suspend fun readPage(
        generation: DerivedStudentMistakeCatalogGeneration,
        learnerFingerprint: String,
        revision: StudentMistakeCatalogRevision,
        filter: StudentMistakeCatalogFilter,
        cursor: StudentMistakeCatalogCursor?,
        limit: Int,
    ): DerivedStudentMistakeCatalogPage?

    suspend fun readExportRows(
        generation: DerivedStudentMistakeCatalogGeneration,
        learnerFingerprint: String,
        revision: StudentMistakeCatalogRevision,
        filter: StudentMistakeCatalogFilter,
        limit: Int,
    ): List<DerivedStudentMistakeCatalogEntryEntity>?

    suspend fun readFacets(
        generation: DerivedStudentMistakeCatalogGeneration,
        learnerFingerprint: String,
        revision: StudentMistakeCatalogRevision,
        filter: StudentMistakeCatalogFilter,
    ): DerivedStudentMistakeCatalogFacets?

    /** Closes and recreates only the explicitly discardable projection database. */
    suspend fun rebuildAfterCorruption(): DiscardableStudentMistakeCatalogProjectionStore
}

private class RoomDiscardableStudentMistakeCatalogProjectionStore(
    private val applicationContext: Context,
    private val database: StudentMistakeCatalogProjectionRoomDatabase,
) : DiscardableStudentMistakeCatalogProjectionStore {
    private val dao = database.projectionDao()

    override suspend fun discardBuildingGenerations() {
        dao.discardAllBuildingGenerations()
    }

    override suspend fun findReady(
        learnerFingerprint: String,
        revision: StudentMistakeCatalogRevision,
    ): DerivedStudentMistakeCatalogGeneration? =
        dao.findReadyGeneration(learnerFingerprint, revision.canonicalFingerprint())
            ?.toDomain()

    override suspend fun beginBuilding(
        learnerFingerprint: String,
        revision: StudentMistakeCatalogRevision,
        nowEpochMillis: Long,
    ): DerivedStudentMistakeCatalogGeneration {
        val generation =
            DerivedStudentMistakeCatalogGeneration(
                generationId = UUID.randomUUID().toString(),
                learnerFingerprint = learnerFingerprint,
                revision = revision,
                cursorAuthenticationKey = newCursorAuthenticationKey(),
                indexedEntryCount = 0L,
            )
        dao.beginGeneration(generation.toEntity(nowEpochMillis))
        return generation
    }

    override suspend fun appendBatch(
        generation: DerivedStudentMistakeCatalogGeneration,
        batch: List<DerivedStudentMistakeCatalogBatchEntry>,
        indexedEntryCount: Long,
        nowEpochMillis: Long,
    ) {
        require(batch.all { it.entry.generationId == generation.generationId }) {
            "Derived catalog batch belongs to another generation"
        }
        dao.appendBatch(
            generationId = generation.generationId,
            batch = batch,
            indexedEntryCount = indexedEntryCount,
            updatedAtEpochMillis = nowEpochMillis,
        )
    }

    override suspend fun discard(generationId: String) {
        dao.discardGeneration(generationId)
    }

    override suspend fun promote(
        generation: DerivedStudentMistakeCatalogGeneration,
        nowEpochMillis: Long,
    ): Boolean =
        dao.promoteGeneration(
            generationId = generation.generationId,
            learnerFingerprint = generation.learnerFingerprint,
            promotedAtEpochMillis = nowEpochMillis,
        )

    override suspend fun readPage(
        generation: DerivedStudentMistakeCatalogGeneration,
        learnerFingerprint: String,
        revision: StudentMistakeCatalogRevision,
        filter: StudentMistakeCatalogFilter,
        cursor: StudentMistakeCatalogCursor?,
        limit: Int,
    ): DerivedStudentMistakeCatalogPage? {
        val ready = readMatchingReady(generation, learnerFingerprint, revision) ?: return null
        val queryFingerprint = filter.canonicalFingerprint()
        val cursorKey =
            cursor?.let {
                StudentMistakeCatalogCursorCodec.decodeAndVerify(
                    cursor = it,
                    authenticationKey = ready.cursorAuthenticationKey,
                    expectedGenerationId = ready.generationId,
                    expectedLearnerFingerprint = learnerFingerprint,
                    expectedRevisionFingerprint = revision.canonicalFingerprint(),
                    expectedQueryFingerprint = queryFingerprint,
                ) ?: return null
            }
        val query = filter.toStoreQuery()
        val rows =
            dao.readPage(
                DerivedStudentMistakeCatalogSql.page(
                    query = query,
                    generationId = ready.generationId,
                    cursor = cursorKey,
                    limit = limit + 1,
                ),
            )
        if (readMatchingReady(ready, learnerFingerprint, revision) == null) return null
        val candidates = rows.take(limit)
        val pageRows = ArrayList<DerivedStudentMistakeCatalogEntryEntity>(candidates.size)
        var payloadBytes = 0L
        for (row in candidates) {
            val rowBytes = row.toCatalogItem().catalogPayloadUtf8Bytes()
            if (payloadBytes + rowBytes > MAX_STUDENT_MISTAKE_CATALOG_PAGE_UTF8_BYTES) {
                break
            }
            payloadBytes += rowBytes
            pageRows += row
        }
        check(pageRows.isNotEmpty() || candidates.isEmpty()) {
            "A catalog item exceeds the page UTF-8 payload budget"
        }
        val nextCursor =
            if (pageRows.size < rows.size) {
                pageRows.lastOrNull()?.let { row ->
                    StudentMistakeCatalogCursorCodec.create(
                        generationId = ready.generationId,
                        learnerFingerprint = learnerFingerprint,
                        revisionFingerprint = revision.canonicalFingerprint(),
                        queryFingerprint = queryFingerprint,
                        key =
                            DerivedStudentMistakeCatalogCursorKey(
                                changedAtEpochMillis = row.changedAtEpochMillis,
                                problemId = row.problemId,
                                entryId = row.entryId,
                            ),
                        authenticationKey = ready.cursorAuthenticationKey,
                    )
                }
            } else {
                null
            }
        return DerivedStudentMistakeCatalogPage(pageRows, nextCursor)
    }

    override suspend fun readExportRows(
        generation: DerivedStudentMistakeCatalogGeneration,
        learnerFingerprint: String,
        revision: StudentMistakeCatalogRevision,
        filter: StudentMistakeCatalogFilter,
        limit: Int,
    ): List<DerivedStudentMistakeCatalogEntryEntity>? {
        require(limit in 1..MAX_STUDENT_MISTAKE_CATALOG_EXPORT_ITEMS + 1)
        val ready = readMatchingReady(generation, learnerFingerprint, revision) ?: return null
        val query = filter.toStoreQuery()
        val rows =
            dao.readExportRows(
                DerivedStudentMistakeCatalogSql.export(
                    query = query,
                    generationId = ready.generationId,
                    limit = limit,
                ),
            )
        if (readMatchingReady(ready, learnerFingerprint, revision) == null) return null
        rows.forEach(DerivedStudentMistakeCatalogEntryEntity::toCatalogItem)
        return rows
    }

    override suspend fun readFacets(
        generation: DerivedStudentMistakeCatalogGeneration,
        learnerFingerprint: String,
        revision: StudentMistakeCatalogRevision,
        filter: StudentMistakeCatalogFilter,
    ): DerivedStudentMistakeCatalogFacets? {
        val ready = readMatchingReady(generation, learnerFingerprint, revision) ?: return null
        val query = filter.toStoreQuery()
        val facetLimit = MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION + 1
        val rows =
            dao.readFacetRows(
                totalCountQuery = DerivedStudentMistakeCatalogSql.count(query, ready.generationId),
                subjectQuery =
                    DerivedStudentMistakeCatalogSql.subjectFacets(
                        query,
                        ready.generationId,
                        facetLimit,
                    ),
                sectionQuery =
                    DerivedStudentMistakeCatalogSql.sectionFacets(
                        query,
                        ready.generationId,
                        facetLimit,
                    ),
                knowledgeQuery =
                    DerivedStudentMistakeCatalogSql.knowledgeFacets(
                        query,
                        ready.generationId,
                        facetLimit,
                    ),
                masteryQuery =
                    DerivedStudentMistakeCatalogSql.masteryFacets(
                        query,
                        ready.generationId,
                        facetLimit,
                    ),
            )
        if (readMatchingReady(ready, learnerFingerprint, revision) == null) return null
        val truncated = buildSet {
            if (rows.subjects.size > MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION) {
                add(StudentMistakeCatalogFacetDimension.SUBJECT)
            }
            if (rows.sections.size > MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION) {
                add(StudentMistakeCatalogFacetDimension.SECTION)
            }
            if (rows.knowledge.size > MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION) {
                add(StudentMistakeCatalogFacetDimension.KNOWLEDGE_NODE)
            }
            if (rows.mastery.size > MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION) {
                add(StudentMistakeCatalogFacetDimension.MASTERY_STATUS)
            }
        }
        return DerivedStudentMistakeCatalogFacets(
            totalCount = rows.totalCount,
            subjects = rows.subjects.take(MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION),
            sections = rows.sections.take(MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION),
            knowledge = rows.knowledge.take(MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION),
            mastery = rows.mastery.take(MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION),
            truncatedDimensions = truncated,
        )
    }

    private suspend fun readMatchingReady(
        expected: DerivedStudentMistakeCatalogGeneration,
        learnerFingerprint: String,
        revision: StudentMistakeCatalogRevision,
    ): DerivedStudentMistakeCatalogGeneration? {
        if (
            expected.learnerFingerprint != learnerFingerprint ||
            expected.revision != revision
        ) {
            return null
        }
        val current = dao.readReadyGeneration(expected.generationId)?.toDomain() ?: return null
        return current.takeIf {
            it.learnerFingerprint == learnerFingerprint &&
                it.revision == revision &&
                MessageDigest.isEqual(
                    it.cursorAuthenticationKey.toByteArray(Charsets.UTF_8),
                    expected.cursorAuthenticationKey.toByteArray(Charsets.UTF_8),
                )
        }
    }

    override fun close() {
        database.close()
    }

    override suspend fun rebuildAfterCorruption(): DiscardableStudentMistakeCatalogProjectionStore {
        database.close()
        applicationContext.deleteDatabase(STUDENT_MISTAKE_CATALOG_PROJECTION_DATABASE_NAME)
        return StudentMistakeCatalogProjectionDatabaseFactory.open(applicationContext)
    }
}

internal object StudentMistakeCatalogCursorCodec {
    private const val VERSION = "v1"
    private const val FIELD_SEPARATOR = "."

    fun create(
        generationId: String,
        learnerFingerprint: String,
        revisionFingerprint: String,
        queryFingerprint: String,
        key: DerivedStudentMistakeCatalogCursorKey,
        authenticationKey: String,
    ): StudentMistakeCatalogCursor {
        val payload =
            listOf(
                VERSION,
                encode(generationId),
                learnerFingerprint,
                revisionFingerprint,
                queryFingerprint,
                key.changedAtEpochMillis.toString(),
                encode(key.problemId),
                encode(key.entryId),
            ).joinToString(FIELD_SEPARATOR)
        return StudentMistakeCatalogCursor("$payload$FIELD_SEPARATOR${sign(payload, authenticationKey)}")
    }

    fun decodeAndVerify(
        cursor: StudentMistakeCatalogCursor,
        authenticationKey: String,
        expectedGenerationId: String,
        expectedLearnerFingerprint: String,
        expectedRevisionFingerprint: String,
        expectedQueryFingerprint: String,
    ): DerivedStudentMistakeCatalogCursorKey? {
        val fields = cursor.opaqueValue.split(FIELD_SEPARATOR)
        if (fields.size != 9 || fields[0] != VERSION) return null
        val payload = fields.take(8).joinToString(FIELD_SEPARATOR)
        val expectedSignature = sign(payload, authenticationKey)
        if (
            !MessageDigest.isEqual(
                fields[8].hexBytesOrNull() ?: return null,
                expectedSignature.hexBytesOrNull() ?: return null,
            )
        ) {
            return null
        }
        if (
            decode(fields[1]) != expectedGenerationId ||
            fields[2] != expectedLearnerFingerprint ||
            fields[3] != expectedRevisionFingerprint ||
            fields[4] != expectedQueryFingerprint
        ) {
            return null
        }
        val changedAt = fields[5].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val problemId = decode(fields[6]) ?: return null
        val entryId = decode(fields[7]) ?: return null
        return DerivedStudentMistakeCatalogCursorKey(changedAt, problemId, entryId)
    }

    private fun sign(payload: String, authenticationKey: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getUrlDecoder().decode(authenticationKey), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String? =
        runCatching {
            String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
                .takeIf(String::isNotBlank)
        }.getOrNull()
}

private fun String.hexBytesOrNull(): ByteArray? {
    if (length != 64 || any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun newCursorAuthenticationKey(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun StudentMistakeCatalogFilter.toStoreQuery(): DerivedStudentMistakeCatalogFilterQuery {
    val search = text?.let(DerivedStudentMistakeSearchNormalizer::query)
    return DerivedStudentMistakeCatalogFilterQuery(
        ftsMatchExpression = search?.ftsMatchExpression,
        normalizedSearchText = search?.normalizedText,
        subject = subject?.name,
        favoriteOnly = favoriteOnly,
        masteryFilterDisabled = masteryStatuses.isEmpty(),
        masteryStatuses = masteryStatuses.map(MasteryStatus::name).sorted(),
        sectionStableId = sectionStableId,
        knowledgeSubject = knowledgeNode?.subject?.name,
        knowledgeNodeId = knowledgeNode?.knowledgeNodeId,
        knowledgeTaxonomyVersion = knowledgeNode?.taxonomyVersion,
        knowledgePackVersion = knowledgeNode?.knowledgePackVersion,
    )
}

private fun DerivedStudentMistakeCatalogGeneration.toEntity(
    nowEpochMillis: Long,
): DerivedStudentMistakeCatalogGenerationEntity =
    DerivedStudentMistakeCatalogGenerationEntity(
        generationId = generationId,
        learnerFingerprint = learnerFingerprint,
        state = DerivedStudentMistakeCatalogGenerationState.BUILDING.name,
        revisionFingerprint = revision.canonicalFingerprint(),
        studentChangeVersion = revision.studentChangeVersion,
        masteryLedgerSequence = revision.masteryLedgerSequence,
        masteryAsOfEpochMillis = revision.masteryAsOfEpochMillis,
        knowledgeActivationGeneration = revision.knowledgeActivationGeneration,
        knowledgeManifestFingerprint = revision.knowledgeManifestFingerprint,
        knowledgeTaxonomyVersion = revision.knowledgeTaxonomyVersion,
        knowledgePackVersion = revision.knowledgePackVersion,
        cursorAuthenticationKey = cursorAuthenticationKey,
        indexedEntryCount = indexedEntryCount,
        updatedAtEpochMillis = nowEpochMillis,
    )

private fun DerivedStudentMistakeCatalogGenerationEntity.toDomain():
    DerivedStudentMistakeCatalogGeneration =
    DerivedStudentMistakeCatalogGeneration(
        generationId = generationId,
        learnerFingerprint = learnerFingerprint,
        revision =
            StudentMistakeCatalogRevision(
                studentChangeVersion = studentChangeVersion,
                masteryLedgerSequence = masteryLedgerSequence,
                masteryAsOfEpochMillis = masteryAsOfEpochMillis,
                knowledgeActivationGeneration = knowledgeActivationGeneration,
                knowledgeManifestFingerprint = knowledgeManifestFingerprint,
                knowledgeTaxonomyVersion = knowledgeTaxonomyVersion,
                knowledgePackVersion = knowledgePackVersion,
            ).also { revision ->
                check(revision.canonicalFingerprint() == revisionFingerprint) {
                    "Corrupt derived catalog revision fingerprint"
                }
            },
        cursorAuthenticationKey = cursorAuthenticationKey,
        indexedEntryCount = indexedEntryCount,
    )

internal object DerivedStudentMistakeCatalogSql {
    fun page(
        query: DerivedStudentMistakeCatalogFilterQuery,
        generationId: String,
        cursor: DerivedStudentMistakeCatalogCursorKey?,
        limit: Int,
    ): RoomRawQuery {
        val matching = matchingEntries(query, generationId)
        val sql = StringBuilder("SELECT entry.* ${matching.sql}")
        val arguments = matching.arguments.toMutableList()
        cursor?.let { key ->
            sql.append('\n').append(
                """
                 AND (
                   entry.changed_at_epoch_millis < ? OR
                   (
                     entry.changed_at_epoch_millis = ? AND
                     entry.problem_id < ?
                   ) OR
                   (
                     entry.changed_at_epoch_millis = ? AND
                     entry.problem_id = ? AND
                     entry.entry_id < ?
                   )
                 )
                """.trimIndent(),
            )
            arguments += key.changedAtEpochMillis
            arguments += key.changedAtEpochMillis
            arguments += key.problemId
            arguments += key.changedAtEpochMillis
            arguments += key.problemId
            arguments += key.entryId
        }
        sql.append(KEYSET_ORDER).append(" LIMIT ?")
        arguments += limit.toLong()
        return rawQuery(sql.toString(), arguments)
    }

    fun export(
        query: DerivedStudentMistakeCatalogFilterQuery,
        generationId: String,
        limit: Int,
    ): RoomRawQuery {
        val matching = matchingEntries(query, generationId)
        return rawQuery(
            "SELECT entry.* ${matching.sql}$KEYSET_ORDER LIMIT ?",
            matching.arguments + limit.toLong(),
        )
    }

    fun count(
        query: DerivedStudentMistakeCatalogFilterQuery,
        generationId: String,
    ): RoomRawQuery {
        val matching = matchingEntries(query, generationId)
        return rawQuery("SELECT COUNT(*) ${matching.sql}", matching.arguments)
    }

    fun subjectFacets(
        query: DerivedStudentMistakeCatalogFilterQuery,
        generationId: String,
        limit: Int = MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION + 1,
    ): RoomRawQuery {
        val matching = matchingEntries(query, generationId)
        return rawQuery(
            """
            SELECT entry.subject AS subject, COUNT(*) AS problemCount
            ${matching.sql}
            GROUP BY entry.subject
            ORDER BY entry.subject ASC
            LIMIT ?
            """.trimIndent(),
            matching.arguments + limit.toLong(),
        )
    }

    fun sectionFacets(
        query: DerivedStudentMistakeCatalogFilterQuery,
        generationId: String,
        limit: Int = MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION + 1,
    ): RoomRawQuery {
        val matching = matchingEntries(query, generationId)
        return rawQuery(
            """
            WITH matching_entry AS (
              SELECT entry.rowid AS entryRowId
              ${matching.sql}
            )
            SELECT section_facet.section_stable_id AS sectionStableId,
                   COUNT(*) AS problemCount
            FROM matching_entry
            INNER JOIN derived_non_authoritative_catalog_section AS section_facet
              ON section_facet.entry_rowid = matching_entry.entryRowId
            GROUP BY section_facet.section_stable_id
            ORDER BY section_facet.section_stable_id ASC
            LIMIT ?
            """.trimIndent(),
            matching.arguments + limit.toLong(),
        )
    }

    fun knowledgeFacets(
        query: DerivedStudentMistakeCatalogFilterQuery,
        generationId: String,
        limit: Int = MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION + 1,
    ): RoomRawQuery {
        val matching = matchingEntries(query, generationId)
        return rawQuery(
            """
            WITH matching_entry AS (
              SELECT entry.rowid AS entryRowId
              ${matching.sql}
            )
            SELECT knowledge_facet.knowledge_subject AS knowledgeSubject,
                   knowledge_facet.knowledge_node_id AS knowledgeNodeId,
                   knowledge_facet.knowledge_taxonomy_version AS knowledgeTaxonomyVersion,
                   knowledge_facet.knowledge_pack_version AS knowledgePackVersion,
                   COUNT(*) AS problemCount
            FROM matching_entry
            INNER JOIN derived_non_authoritative_catalog_knowledge AS knowledge_facet
              ON knowledge_facet.entry_rowid = matching_entry.entryRowId
            GROUP BY knowledge_facet.knowledge_subject,
                     knowledge_facet.knowledge_node_id,
                     knowledge_facet.knowledge_taxonomy_version,
                     knowledge_facet.knowledge_pack_version
            ORDER BY knowledge_facet.knowledge_subject ASC,
                     knowledge_facet.knowledge_node_id ASC,
                     knowledge_facet.knowledge_taxonomy_version ASC,
                     knowledge_facet.knowledge_pack_version ASC
            LIMIT ?
            """.trimIndent(),
            matching.arguments + limit.toLong(),
        )
    }

    fun masteryFacets(
        query: DerivedStudentMistakeCatalogFilterQuery,
        generationId: String,
        limit: Int = MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION + 1,
    ): RoomRawQuery {
        val matching = matchingEntries(query, generationId)
        return rawQuery(
            """
            SELECT entry.mastery_status AS masteryStatus, COUNT(*) AS problemCount
            ${matching.sql}
            GROUP BY entry.mastery_status
            ORDER BY entry.mastery_status ASC
            LIMIT ?
            """.trimIndent(),
            matching.arguments + limit.toLong(),
        )
    }

    private fun matchingEntries(
        query: DerivedStudentMistakeCatalogFilterQuery,
        generationId: String,
    ): BoundProjectionSql {
        val sql = StringBuilder()
        val arguments = mutableListOf<Any>()
        val ftsMatchExpression = query.ftsMatchExpression
        if (ftsMatchExpression != null) {
            val normalizedSearchText = checkNotNull(query.normalizedSearchText)
            sql.append(
                """
                FROM derived_non_authoritative_catalog_entry_fts AS search_fts
                INNER JOIN derived_non_authoritative_catalog_entry AS entry
                  ON entry.rowid = search_fts.docid
                WHERE search_fts.tokenized_search_text MATCH ?
                  AND instr(entry.normalized_search_text, ?) > 0
                """.trimIndent(),
            )
            arguments += ftsMatchExpression
            arguments += normalizedSearchText
        } else {
            check(query.normalizedSearchText == null)
            sql.append("FROM derived_non_authoritative_catalog_entry AS entry WHERE 1 = 1")
        }
        sql.append(" AND entry.generation_id = ?")
        arguments += generationId
        query.subject?.let { subject ->
            sql.append(" AND entry.subject = ?")
            arguments += subject
        }
        if (query.favoriteOnly) sql.append(" AND entry.favorite = 1")
        if (!query.masteryFilterDisabled) {
            check(query.masteryStatuses.isNotEmpty())
            sql.append(" AND entry.mastery_status IN (")
                .append(query.masteryStatuses.joinToString(",") { "?" })
                .append(')')
            arguments.addAll(query.masteryStatuses)
        }
        query.sectionStableId?.let { sectionStableId ->
            sql.append('\n').append(
                """
                 AND EXISTS (
                   SELECT 1
                   FROM derived_non_authoritative_catalog_section AS section_filter
                   WHERE section_filter.entry_rowid = entry.rowid
                     AND section_filter.generation_id = entry.generation_id
                     AND section_filter.section_stable_id = ?
                 )
                """.trimIndent(),
            )
            arguments += sectionStableId
        }
        query.knowledgeNodeId?.let { knowledgeNodeId ->
            sql.append('\n').append(
                """
                 AND EXISTS (
                   SELECT 1
                   FROM derived_non_authoritative_catalog_knowledge AS knowledge_filter
                   WHERE knowledge_filter.entry_rowid = entry.rowid
                     AND knowledge_filter.generation_id = entry.generation_id
                     AND knowledge_filter.knowledge_subject = ?
                     AND knowledge_filter.knowledge_node_id = ?
                     AND knowledge_filter.knowledge_taxonomy_version = ?
                     AND knowledge_filter.knowledge_pack_version = ?
                 )
                """.trimIndent(),
            )
            arguments += checkNotNull(query.knowledgeSubject)
            arguments += knowledgeNodeId
            arguments += checkNotNull(query.knowledgeTaxonomyVersion)
            arguments += checkNotNull(query.knowledgePackVersion)
        }
        return BoundProjectionSql(sql.toString(), arguments)
    }

    private fun rawQuery(sql: String, arguments: List<Any>): RoomRawQuery =
        RoomRawQuery(sql) { statement ->
            arguments.forEachIndexed { index, argument ->
                when (argument) {
                    is String -> statement.bindText(index + 1, argument)
                    is Long -> statement.bindLong(index + 1, argument)
                    else -> error("Unsupported derived projection query argument")
                }
            }
        }

    private data class BoundProjectionSql(
        val sql: String,
        val arguments: List<Any>,
    )

    private const val KEYSET_ORDER =
        " ORDER BY entry.changed_at_epoch_millis DESC," +
            " entry.problem_id DESC, entry.entry_id DESC"
}

internal fun DerivedStudentMistakeCatalogEntryEntity.toCatalogItem(): StudentMistakeCatalogItem =
    StudentMistakeCatalogItem(
        entryId = entryId,
        problemId = problemId,
        problemRevisionId = problemRevisionId,
        practiceUnitId = practiceUnitId,
        subject = enumValueOf(subject),
        title = title,
        practiceUnitTitle = practiceUnitTitle,
        sectionStableIds = ProjectionWireCodec.decodeStrings(sectionStableIdsWire),
        knowledgeNodes = ProjectionWireCodec.decodeKnowledgeNodes(knowledgeNodesWire),
        knowledgeDisplayNames = ProjectionWireCodec.decodeStrings(knowledgeDisplayNamesWire),
        masteryStatus = enumValueOf(masteryStatus),
        favorite = favorite,
        changedAtEpochMillis = changedAtEpochMillis,
    )

internal object ProjectionWireCodec {
    fun encodeStrings(values: List<String>): String {
        require(values.size <= MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM) {
            "Derived catalog wire list exceeds its relationship budget"
        }
        return values.joinToString(",") { value ->
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(Charsets.UTF_8))
        }.also(::requireBoundedWire)
    }

    fun decodeStrings(wire: String): List<String> =
        if (wire.isEmpty()) {
            emptyList()
        } else {
            requireBoundedWire(wire)
            wire.split(',').also { encodedValues ->
                require(
                    encodedValues.size <= MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM,
                ) { "Derived catalog wire list exceeds its relationship budget" }
            }.map { encoded ->
                String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
            }
        }

    fun encodeKnowledgeNodes(nodes: List<KnowledgeNodeRef>): String {
        require(nodes.size <= MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM) {
            "Derived catalog knowledge wire exceeds its relationship budget"
        }
        return nodes.joinToString(",") { node ->
            encodeStrings(
                listOf(
                    node.subject.name,
                    node.knowledgeNodeId,
                    node.taxonomyVersion,
                    node.knowledgePackVersion,
                ),
            ).replace(',', '.')
        }.also(::requireBoundedWire)
    }

    fun decodeKnowledgeNodes(wire: String): List<KnowledgeNodeRef> =
        if (wire.isEmpty()) {
            emptyList()
        } else {
            requireBoundedWire(wire)
            wire.split(',').also { encodedNodes ->
                require(
                    encodedNodes.size <= MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM,
                ) { "Derived catalog knowledge wire exceeds its relationship budget" }
            }.map { encodedNode ->
                val fields = decodeStrings(encodedNode.replace('.', ','))
                check(fields.size == 4) { "Corrupt derived catalog knowledge reference" }
                KnowledgeNodeRef(
                    subject = enumValueOf(fields[0]),
                    knowledgeNodeId = fields[1],
                    taxonomyVersion = fields[2],
                    knowledgePackVersion = fields[3],
                )
            }
        }

    private fun requireBoundedWire(wire: String) {
        require(
            wire.toByteArray(Charsets.UTF_8).size.toLong() <=
                MAX_STUDENT_MISTAKE_CATALOG_STORED_ENTRY_UTF8_BYTES,
        ) { "Derived catalog wire exceeds the UTF-8 storage budget" }
    }
}

internal const val STUDENT_MISTAKE_CATALOG_PROJECTION_DATABASE_NAME =
    "student-mistake-library-projection.db"
internal const val STUDENT_MISTAKE_CATALOG_PROJECTION_SCHEMA_VERSION = 1
