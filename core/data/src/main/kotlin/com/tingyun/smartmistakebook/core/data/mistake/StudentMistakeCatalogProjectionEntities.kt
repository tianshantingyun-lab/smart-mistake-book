package com.tingyun.smartmistakebook.core.data.mistake

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Fts4
import androidx.room3.FtsOptions
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Metadata for a DERIVED/DISCARDABLE/NON_AUTHORITATIVE projection generation. */
/**
 * Discardable learner-facing display/search projection, not an authority record. Labels, titles,
 * normalized/tokenized text and categorical mastery status are rebuildable derived values.
 */
@Entity(
    tableName = "derived_non_authoritative_catalog_generation",
    indices = [
        Index(value = ["learner_fingerprint", "state"]),
        Index(value = ["revision_fingerprint"]),
    ],
)
internal data class DerivedStudentMistakeCatalogGenerationEntity(
    @PrimaryKey
    @ColumnInfo(name = "generation_id")
    val generationId: String,
    @ColumnInfo(name = "learner_fingerprint")
    val learnerFingerprint: String,
    val state: String,
    @ColumnInfo(name = "revision_fingerprint")
    val revisionFingerprint: String,
    @ColumnInfo(name = "student_change_version")
    val studentChangeVersion: Long,
    @ColumnInfo(name = "mastery_ledger_sequence")
    val masteryLedgerSequence: Long,
    @ColumnInfo(name = "mastery_as_of_epoch_millis")
    val masteryAsOfEpochMillis: Long,
    @ColumnInfo(name = "knowledge_activation_generation")
    val knowledgeActivationGeneration: Long,
    @ColumnInfo(name = "knowledge_manifest_fingerprint")
    val knowledgeManifestFingerprint: String,
    @ColumnInfo(name = "knowledge_taxonomy_version")
    val knowledgeTaxonomyVersion: String,
    @ColumnInfo(name = "knowledge_pack_version")
    val knowledgePackVersion: String,
    @ColumnInfo(name = "cursor_authentication_key")
    val cursorAuthenticationKey: String,
    @ColumnInfo(name = "indexed_entry_count")
    val indexedEntryCount: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "derived_non_authoritative_catalog_entry",
    foreignKeys = [
        ForeignKey(
            entity = DerivedStudentMistakeCatalogGenerationEntity::class,
            parentColumns = ["generation_id"],
            childColumns = ["generation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["generation_id", "entry_id"], unique = true),
        Index(
            value = [
                "generation_id",
                "changed_at_epoch_millis",
                "problem_id",
                "entry_id",
            ],
            orders = [
                Index.Order.ASC,
                Index.Order.DESC,
                Index.Order.DESC,
                Index.Order.DESC,
            ],
        ),
        Index(value = ["generation_id", "subject", "mastery_status"]),
        Index(value = ["generation_id", "mastery_status"]),
    ],
)
internal data class DerivedStudentMistakeCatalogEntryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0,
    @ColumnInfo(name = "generation_id")
    val generationId: String,
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    val subject: String,
    val title: String,
    @ColumnInfo(name = "practice_unit_title")
    val practiceUnitTitle: String,
    @ColumnInfo(name = "section_stable_ids_wire")
    val sectionStableIdsWire: String,
    @ColumnInfo(name = "knowledge_nodes_wire")
    val knowledgeNodesWire: String,
    @ColumnInfo(name = "knowledge_display_names_wire")
    val knowledgeDisplayNamesWire: String,
    @ColumnInfo(name = "mastery_status")
    val masteryStatus: String,
    val favorite: Boolean,
    @ColumnInfo(name = "changed_at_epoch_millis")
    val changedAtEpochMillis: Long,
    @ColumnInfo(name = "normalized_search_text")
    val normalizedSearchText: String,
    @ColumnInfo(name = "tokenized_search_text")
    val tokenizedSearchText: String,
) {
    init {
        require(storedPayloadUtf8Bytes() <= MAX_STUDENT_MISTAKE_CATALOG_STORED_ENTRY_UTF8_BYTES) {
            "Derived catalog entry exceeds the UTF-8 storage budget"
        }
    }
}

@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    contentEntity = DerivedStudentMistakeCatalogEntryEntity::class,
)
@Entity(tableName = "derived_non_authoritative_catalog_entry_fts")
internal data class DerivedStudentMistakeCatalogEntryFtsEntity(
    @ColumnInfo(name = "tokenized_search_text")
    val tokenizedSearchText: String,
)

@Entity(
    tableName = "derived_non_authoritative_catalog_section",
    foreignKeys = [
        ForeignKey(
            entity = DerivedStudentMistakeCatalogEntryEntity::class,
            parentColumns = ["rowid"],
            childColumns = ["entry_rowid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    primaryKeys = ["entry_rowid", "section_stable_id"],
    indices = [
        Index(value = ["entry_rowid"]),
        Index(value = ["generation_id", "section_stable_id"]),
    ],
)
internal data class DerivedStudentMistakeCatalogSectionEntity(
    @ColumnInfo(name = "entry_rowid")
    val entryRowId: Long,
    @ColumnInfo(name = "generation_id")
    val generationId: String,
    @ColumnInfo(name = "section_stable_id")
    val sectionStableId: String,
)

@Entity(
    tableName = "derived_non_authoritative_catalog_knowledge",
    foreignKeys = [
        ForeignKey(
            entity = DerivedStudentMistakeCatalogEntryEntity::class,
            parentColumns = ["rowid"],
            childColumns = ["entry_rowid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    primaryKeys = [
        "entry_rowid",
        "knowledge_subject",
        "knowledge_node_id",
        "knowledge_taxonomy_version",
        "knowledge_pack_version",
    ],
    indices = [
        Index(value = ["entry_rowid"]),
        Index(
            value = [
                "generation_id",
                "knowledge_subject",
                "knowledge_node_id",
                "knowledge_taxonomy_version",
                "knowledge_pack_version",
            ],
        ),
    ],
)
internal data class DerivedStudentMistakeCatalogKnowledgeEntity(
    @ColumnInfo(name = "entry_rowid")
    val entryRowId: Long,
    @ColumnInfo(name = "generation_id")
    val generationId: String,
    @ColumnInfo(name = "knowledge_subject")
    val knowledgeSubject: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "knowledge_taxonomy_version")
    val knowledgeTaxonomyVersion: String,
    @ColumnInfo(name = "knowledge_pack_version")
    val knowledgePackVersion: String,
)

internal enum class DerivedStudentMistakeCatalogGenerationState {
    BUILDING,
    READY,
}

internal fun DerivedStudentMistakeCatalogEntryEntity.storedPayloadUtf8Bytes(): Long =
    sequenceOf(
        generationId,
        entryId,
        problemId,
        problemRevisionId,
        practiceUnitId,
        subject,
        title,
        practiceUnitTitle,
        sectionStableIdsWire,
        knowledgeNodesWire,
        knowledgeDisplayNamesWire,
        masteryStatus,
        favorite.toString(),
        changedAtEpochMillis.toString(),
        normalizedSearchText,
        tokenizedSearchText,
    ).sumOf { value -> value.toByteArray(Charsets.UTF_8).size.toLong() + 1L }
