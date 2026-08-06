package com.tingyun.smartmistakebook.core.data.mistake

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.Transaction

internal data class DerivedStudentMistakeCatalogSubjectFacetRow(
    val subject: String,
    val problemCount: Long,
)

internal data class DerivedStudentMistakeCatalogSectionFacetRow(
    val sectionStableId: String,
    val problemCount: Long,
)

internal data class DerivedStudentMistakeCatalogKnowledgeFacetRow(
    val knowledgeSubject: String,
    val knowledgeNodeId: String,
    val knowledgeTaxonomyVersion: String,
    val knowledgePackVersion: String,
    val problemCount: Long,
)

internal data class DerivedStudentMistakeCatalogMasteryFacetRow(
    val masteryStatus: String,
    val problemCount: Long,
)

internal data class DerivedStudentMistakeCatalogBatchEntry(
    val entry: DerivedStudentMistakeCatalogEntryEntity,
    val sectionStableIds: List<String>,
    val knowledgeNodes: List<DerivedStudentMistakeCatalogKnowledgeDraft>,
)

internal data class DerivedStudentMistakeCatalogKnowledgeDraft(
    val subject: String,
    val knowledgeNodeId: String,
    val taxonomyVersion: String,
    val knowledgePackVersion: String,
)

internal data class DerivedStudentMistakeCatalogFacetRows(
    val totalCount: Long,
    val subjects: List<DerivedStudentMistakeCatalogSubjectFacetRow>,
    val sections: List<DerivedStudentMistakeCatalogSectionFacetRow>,
    val knowledge: List<DerivedStudentMistakeCatalogKnowledgeFacetRow>,
    val mastery: List<DerivedStudentMistakeCatalogMasteryFacetRow>,
)

@Dao
internal abstract class StudentMistakeCatalogProjectionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertGeneration(
        generation: DerivedStudentMistakeCatalogGenerationEntity,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEntries(
        entries: List<DerivedStudentMistakeCatalogEntryEntity>,
    ): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSections(
        sections: List<DerivedStudentMistakeCatalogSectionEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertKnowledge(
        knowledge: List<DerivedStudentMistakeCatalogKnowledgeEntity>,
    )

    @Query(
        """
        UPDATE derived_non_authoritative_catalog_generation
        SET indexed_entry_count = :indexedEntryCount,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE generation_id = :generationId
          AND state = 'BUILDING'
        """,
    )
    protected abstract suspend fun updateBuildingProgress(
        generationId: String,
        indexedEntryCount: Long,
        updatedAtEpochMillis: Long,
    ): Int

    @Transaction
    open suspend fun beginGeneration(
        generation: DerivedStudentMistakeCatalogGenerationEntity,
    ) {
        check(generation.state == DerivedStudentMistakeCatalogGenerationState.BUILDING.name)
        insertGeneration(generation)
    }

    @Transaction
    open suspend fun appendBatch(
        generationId: String,
        batch: List<DerivedStudentMistakeCatalogBatchEntry>,
        indexedEntryCount: Long,
        updatedAtEpochMillis: Long,
    ) {
        require(batch.size <= MAX_STUDENT_MISTAKE_CATALOG_PAGE_SIZE) {
            "Derived catalog staging batch exceeds the memory budget"
        }
        require(
            batch.sumOf { draft -> draft.entry.storedPayloadUtf8Bytes() } <=
                MAX_STUDENT_MISTAKE_CATALOG_PAGE_UTF8_BYTES,
        ) { "Derived catalog staging batch exceeds the UTF-8 memory budget" }
        batch.forEach { draft ->
            require(draft.entry.generationId == generationId) {
                "Derived catalog entry belongs to another generation"
            }
            require(
                draft.sectionStableIds.size <=
                    MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM,
            ) { "Derived catalog entry has too many curriculum sections" }
            require(
                draft.knowledgeNodes.size <=
                    MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM,
            ) { "Derived catalog entry has too many knowledge references" }
            require(draft.sectionStableIds.distinct().size == draft.sectionStableIds.size) {
                "Derived catalog entry repeats a curriculum section"
            }
            require(draft.knowledgeNodes.distinct().size == draft.knowledgeNodes.size) {
                "Derived catalog entry repeats a knowledge reference"
            }
            draft.entry.toCatalogItem()
        }
        if (batch.isNotEmpty()) {
            val rowIds = insertEntries(batch.map(DerivedStudentMistakeCatalogBatchEntry::entry))
            check(rowIds.size == batch.size)
            val sections = ArrayList<DerivedStudentMistakeCatalogSectionEntity>()
            val knowledge = ArrayList<DerivedStudentMistakeCatalogKnowledgeEntity>()
            batch.forEachIndexed { index, draft ->
                val rowId = rowIds[index]
                check(rowId > 0L) { "Derived catalog entry row id was not generated" }
                draft.sectionStableIds.forEach { sectionStableId ->
                    sections +=
                        DerivedStudentMistakeCatalogSectionEntity(
                            entryRowId = rowId,
                            generationId = generationId,
                            sectionStableId = sectionStableId,
                        )
                }
                draft.knowledgeNodes.forEach { node ->
                    knowledge +=
                        DerivedStudentMistakeCatalogKnowledgeEntity(
                            entryRowId = rowId,
                            generationId = generationId,
                            knowledgeSubject = node.subject,
                            knowledgeNodeId = node.knowledgeNodeId,
                            knowledgeTaxonomyVersion = node.taxonomyVersion,
                            knowledgePackVersion = node.knowledgePackVersion,
                        )
                }
            }
            if (sections.isNotEmpty()) insertSections(sections)
            if (knowledge.isNotEmpty()) insertKnowledge(knowledge)
        }
        check(
            updateBuildingProgress(
                generationId = generationId,
                indexedEntryCount = indexedEntryCount,
                updatedAtEpochMillis = updatedAtEpochMillis,
            ) == 1,
        ) { "Derived catalog staging generation is no longer BUILDING" }
    }

    @Query(
        """
        SELECT *
        FROM derived_non_authoritative_catalog_generation
        WHERE learner_fingerprint = :learnerFingerprint
          AND revision_fingerprint = :revisionFingerprint
          AND state = 'READY'
        ORDER BY updated_at_epoch_millis DESC, generation_id DESC
        LIMIT 1
        """,
    )
    abstract suspend fun findReadyGeneration(
        learnerFingerprint: String,
        revisionFingerprint: String,
    ): DerivedStudentMistakeCatalogGenerationEntity?

    @Query(
        """
        SELECT *
        FROM derived_non_authoritative_catalog_generation
        WHERE generation_id = :generationId
          AND state = 'READY'
        LIMIT 1
        """,
    )
    abstract suspend fun readReadyGeneration(
        generationId: String,
    ): DerivedStudentMistakeCatalogGenerationEntity?

    @Query(
        """
        DELETE FROM derived_non_authoritative_catalog_generation
        WHERE state = 'BUILDING'
        """,
    )
    abstract suspend fun discardAllBuildingGenerations(): Int

    @Query(
        """
        DELETE FROM derived_non_authoritative_catalog_generation
        WHERE generation_id = :generationId
        """,
    )
    abstract suspend fun discardGeneration(generationId: String): Int

    @Query(
        """
        UPDATE derived_non_authoritative_catalog_generation
        SET state = 'READY', updated_at_epoch_millis = :promotedAtEpochMillis
        WHERE generation_id = :generationId
          AND state = 'BUILDING'
        """,
    )
    protected abstract suspend fun markGenerationReady(
        generationId: String,
        promotedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        DELETE FROM derived_non_authoritative_catalog_generation
        WHERE learner_fingerprint = :learnerFingerprint
          AND state = 'READY'
          AND generation_id != :promotedGenerationId
        """,
    )
    protected abstract suspend fun deleteSupersededReadyGenerations(
        learnerFingerprint: String,
        promotedGenerationId: String,
    ): Int

    @Transaction
    open suspend fun promoteGeneration(
        generationId: String,
        learnerFingerprint: String,
        promotedAtEpochMillis: Long,
    ): Boolean {
        if (markGenerationReady(generationId, promotedAtEpochMillis) != 1) return false
        deleteSupersededReadyGenerations(learnerFingerprint, generationId)
        return true
    }

    @RawQuery
    abstract suspend fun readPage(
        query: RoomRawQuery,
    ): List<DerivedStudentMistakeCatalogEntryEntity>

    @RawQuery
    abstract suspend fun readExportRows(
        query: RoomRawQuery,
    ): List<DerivedStudentMistakeCatalogEntryEntity>

    @RawQuery
    abstract suspend fun countMatchingEntries(query: RoomRawQuery): Long

    @RawQuery
    abstract suspend fun readSubjectFacets(
        query: RoomRawQuery,
    ): List<DerivedStudentMistakeCatalogSubjectFacetRow>

    @RawQuery
    abstract suspend fun readSectionFacets(
        query: RoomRawQuery,
    ): List<DerivedStudentMistakeCatalogSectionFacetRow>

    @RawQuery
    abstract suspend fun readKnowledgeFacets(
        query: RoomRawQuery,
    ): List<DerivedStudentMistakeCatalogKnowledgeFacetRow>

    @RawQuery
    abstract suspend fun readMasteryFacets(
        query: RoomRawQuery,
    ): List<DerivedStudentMistakeCatalogMasteryFacetRow>

    @Transaction
    open suspend fun readFacetRows(
        totalCountQuery: RoomRawQuery,
        subjectQuery: RoomRawQuery,
        sectionQuery: RoomRawQuery,
        knowledgeQuery: RoomRawQuery,
        masteryQuery: RoomRawQuery,
    ): DerivedStudentMistakeCatalogFacetRows =
        DerivedStudentMistakeCatalogFacetRows(
            totalCount = countMatchingEntries(totalCountQuery),
            subjects = readSubjectFacets(subjectQuery),
            sections = readSectionFacets(sectionQuery),
            knowledge = readKnowledgeFacets(knowledgeQuery),
            mastery = readMasteryFacets(masteryQuery),
        )
}
