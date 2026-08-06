package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef

/**
 * Exact revisions used to build one DERIVED, DISCARDABLE and NON_AUTHORITATIVE catalog generation.
 * None of these values grants write access to an authority database.
 */
data class StudentMistakeCatalogRevision(
    val studentChangeVersion: Long,
    val masteryLedgerSequence: Long,
    val masteryAsOfEpochMillis: Long,
    val knowledgeActivationGeneration: Long,
    val knowledgeManifestFingerprint: String,
    val knowledgeTaxonomyVersion: String,
    val knowledgePackVersion: String,
) {
    init {
        require(studentChangeVersion >= 0L) { "Student catalog revision must not be negative" }
        require(masteryLedgerSequence >= 0L) { "Mastery catalog revision must not be negative" }
        require(masteryAsOfEpochMillis >= 0L) { "Mastery catalog time must not be negative" }
        require(knowledgeActivationGeneration > 0L) {
            "Knowledge activation generation must be positive"
        }
        require(knowledgeManifestFingerprint.matches(SHA_256_REGEX)) {
            "Knowledge manifest fingerprint must be SHA-256"
        }
        requireProjectionText(knowledgeTaxonomyVersion, "Knowledge taxonomy version")
        requireProjectionText(knowledgePackVersion, "Knowledge pack version")
    }

    internal fun canonicalFingerprint(): String =
        CanonicalSha256(REVISION_FINGERPRINT_DOMAIN)
            .field("studentChangeVersion", studentChangeVersion)
            .field("masteryLedgerSequence", masteryLedgerSequence)
            .field("masteryAsOfEpochMillis", masteryAsOfEpochMillis)
            .field("knowledgeActivationGeneration", knowledgeActivationGeneration)
            .field("knowledgeManifestFingerprint", knowledgeManifestFingerprint)
            .field("knowledgeTaxonomyVersion", knowledgeTaxonomyVersion)
            .field("knowledgePackVersion", knowledgePackVersion)
            .finish()
}

data class StudentMistakeKnowledgeCatalogRevision(
    val activationGeneration: Long,
    val manifestFingerprint: String,
    val taxonomyVersion: String,
    val knowledgePackVersion: String,
) {
    init {
        require(activationGeneration > 0L) { "Knowledge activation generation must be positive" }
        require(manifestFingerprint.matches(SHA_256_REGEX)) {
            "Knowledge manifest fingerprint must be SHA-256"
        }
        requireProjectionText(taxonomyVersion, "Knowledge taxonomy version")
        requireProjectionText(knowledgePackVersion, "Knowledge pack version")
    }
}

data class StudentMistakeCatalogFilter(
    val text: String? = null,
    val subject: SubjectKind? = null,
    val sectionStableId: String? = null,
    val knowledgeNode: KnowledgeNodeRef? = null,
    val masteryStatuses: Set<MasteryStatus> = emptySet(),
    val favoriteOnly: Boolean = false,
) {
    init {
        subject?.let { require(it != SubjectKind.GENERAL) { "Catalog subject must be specific" } }
        sectionStableId?.let { requireProjectionText(it, "Section stable id") }
        require(subject == null || knowledgeNode == null || subject == knowledgeNode.subject) {
            "Catalog subject and knowledge node must agree"
        }
        text?.let { raw ->
            require(raw.length <= MAX_CATALOG_SEARCH_CHARS) {
                "Catalog search text exceeds the supported size"
            }
            DerivedStudentMistakeSearchNormalizer.query(raw)
        }
    }

    internal fun canonicalFingerprint(): String =
        CanonicalSha256(QUERY_FINGERPRINT_DOMAIN)
            .nullableField(
                "text",
                text?.let(DerivedStudentMistakeSearchNormalizer::normalize),
            ).nullableField("subject", subject?.name)
            .nullableField("sectionStableId", sectionStableId)
            .nullableField("knowledgeNode", knowledgeNode?.canonicalFingerprint)
            .field(
                "masteryStatuses",
                masteryStatuses.map(MasteryStatus::name).sorted().joinToString(","),
            ).field("favoriteOnly", favoriteOnly)
            .finish()
}

data class StudentMistakeCatalogPageRequest(
    val filter: StudentMistakeCatalogFilter = StudentMistakeCatalogFilter(),
    val cursor: StudentMistakeCatalogCursor? = null,
    val limit: Int = DEFAULT_STUDENT_MISTAKE_CATALOG_PAGE_SIZE,
) {
    init {
        require(limit in 1..MAX_STUDENT_MISTAKE_CATALOG_PAGE_SIZE) {
            "Catalog page size is outside the supported range"
        }
    }
}

/** Caller-storable but otherwise opaque. Every decoded field is authenticated before use. */
class StudentMistakeCatalogCursor(
    val opaqueValue: String,
) {
    init {
        require(opaqueValue.isNotBlank() && opaqueValue.length <= MAX_CURSOR_CHARS) {
            "Catalog cursor is invalid"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is StudentMistakeCatalogCursor && opaqueValue == other.opaqueValue

    override fun hashCode(): Int = opaqueValue.hashCode()

    override fun toString(): String = "StudentMistakeCatalogCursor"
}

data class StudentMistakeCatalogItem(
    val entryId: String,
    val problemId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val subject: SubjectKind,
    val title: String,
    val practiceUnitTitle: String,
    val sectionStableIds: List<String>,
    val knowledgeNodes: List<KnowledgeNodeRef>,
    val knowledgeDisplayNames: List<String>,
    val masteryStatus: MasteryStatus,
    val favorite: Boolean,
    val changedAtEpochMillis: Long,
) {
    init {
        require(sectionStableIds.size <= MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM) {
            "Catalog item has too many curriculum sections"
        }
        require(knowledgeNodes.size <= MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM) {
            "Catalog item has too many knowledge references"
        }
        require(knowledgeDisplayNames.size <= MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM) {
            "Catalog item has too many knowledge display names"
        }
        require(catalogPayloadUtf8Bytes() <= MAX_STUDENT_MISTAKE_CATALOG_ITEM_UTF8_BYTES) {
            "Catalog item exceeds the UTF-8 payload budget"
        }
    }
}

sealed interface StudentMistakeCatalogPageResult {
    data class Content(
        val revision: StudentMistakeCatalogRevision,
        val items: List<StudentMistakeCatalogItem>,
        val nextCursor: StudentMistakeCatalogCursor?,
    ) : StudentMistakeCatalogPageResult {
        init {
            require(items.size <= MAX_STUDENT_MISTAKE_CATALOG_PAGE_SIZE)
            require(
                items.sumOf(StudentMistakeCatalogItem::catalogPayloadUtf8Bytes) <=
                    MAX_STUDENT_MISTAKE_CATALOG_PAGE_UTF8_BYTES,
            ) { "Catalog page exceeds the UTF-8 payload budget" }
        }
    }

    data class Preparing(
        val progress: StudentMistakeCatalogBuildProgress,
    ) : StudentMistakeCatalogPageResult

    data object ReloadRequired : StudentMistakeCatalogPageResult
}

data class StudentMistakeCatalogBuildProgress(
    val indexedEntryCount: Long,
) {
    init {
        require(indexedEntryCount >= 0L) { "Catalog progress must not be negative" }
    }
}

data class StudentMistakeCatalogFacetCount<T>(
    val value: T,
    val problemCount: Long,
) {
    init {
        require(problemCount > 0L) { "Catalog facet count must be positive" }
    }
}

enum class StudentMistakeCatalogFacetDimension {
    SUBJECT,
    SECTION,
    KNOWLEDGE_NODE,
    MASTERY_STATUS,
}

sealed interface StudentMistakeCatalogFacetResult {
    data class Content(
        val revision: StudentMistakeCatalogRevision,
        val totalCount: Long,
        val subjects: List<StudentMistakeCatalogFacetCount<SubjectKind>>,
        val sections: List<StudentMistakeCatalogFacetCount<String>>,
        val knowledgeNodes: List<StudentMistakeCatalogFacetCount<KnowledgeNodeRef>>,
        val masteryStatuses: List<StudentMistakeCatalogFacetCount<MasteryStatus>>,
        /** Dimensions capped by the bounded facet query. No values are silently omitted. */
        val truncatedDimensions: Set<StudentMistakeCatalogFacetDimension> = emptySet(),
    ) : StudentMistakeCatalogFacetResult {
        init {
            require(subjects.size <= MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION)
            require(sections.size <= MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION)
            require(knowledgeNodes.size <= MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION)
            require(masteryStatuses.size <= MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION)
        }
    }

    data class Preparing(
        val progress: StudentMistakeCatalogBuildProgress,
    ) : StudentMistakeCatalogFacetResult

    data object ReloadRequired : StudentMistakeCatalogFacetResult
}

sealed interface StudentMistakeCatalogExportResult {
    data class Content(
        val revision: StudentMistakeCatalogRevision,
        val items: List<StudentMistakeCatalogItem>,
        val modelCallCount: Int = 0,
        val modelTokenCount: Int = 0,
    ) : StudentMistakeCatalogExportResult {
        init {
            require(items.size <= MAX_STUDENT_MISTAKE_CATALOG_EXPORT_ITEMS)
            require(
                items.sumOf(StudentMistakeCatalogItem::catalogPayloadUtf8Bytes) <=
                    MAX_STUDENT_MISTAKE_CATALOG_EXPORT_UTF8_BYTES,
            ) { "Catalog snapshot export exceeds the UTF-8 payload budget" }
            require(modelCallCount == 0 && modelTokenCount == 0) {
                "Catalog snapshot export must not call a model"
            }
        }
    }

    data class TooMany(
        val revision: StudentMistakeCatalogRevision,
        val maximum: Int = MAX_STUDENT_MISTAKE_CATALOG_EXPORT_ITEMS,
    ) : StudentMistakeCatalogExportResult {
        init {
            require(maximum == MAX_STUDENT_MISTAKE_CATALOG_EXPORT_ITEMS)
        }
    }

    data class PayloadTooLarge(
        val revision: StudentMistakeCatalogRevision,
        val maximumUtf8Bytes: Long = MAX_STUDENT_MISTAKE_CATALOG_EXPORT_UTF8_BYTES,
    ) : StudentMistakeCatalogExportResult {
        init {
            require(maximumUtf8Bytes == MAX_STUDENT_MISTAKE_CATALOG_EXPORT_UTF8_BYTES)
        }
    }

    data class Preparing(
        val progress: StudentMistakeCatalogBuildProgress,
    ) : StudentMistakeCatalogExportResult

    data object ReloadRequired : StudentMistakeCatalogExportResult
}

internal fun String.studentMistakeCatalogLearnerFingerprint(): String =
    CanonicalSha256(LEARNER_FINGERPRINT_DOMAIN)
        .field("learnerId", this)
        .finish()

internal fun requireProjectionText(value: String, label: String) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= MAX_PROJECTION_TEXT_CHARS &&
            value.none(Char::isISOControl),
    ) { "$label is invalid" }
}

const val DEFAULT_STUDENT_MISTAKE_CATALOG_PAGE_SIZE = 30
const val MAX_STUDENT_MISTAKE_CATALOG_PAGE_SIZE = 64
const val MAX_STUDENT_MISTAKE_CATALOG_EXPORT_ITEMS = 100
const val MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM = 64
const val MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION = 128
const val MAX_STUDENT_MISTAKE_CATALOG_ITEM_UTF8_BYTES = 32L * 1024L
internal const val MAX_STUDENT_MISTAKE_CATALOG_STORED_ENTRY_UTF8_BYTES = 64L * 1024L
const val MAX_STUDENT_MISTAKE_CATALOG_PAGE_UTF8_BYTES = 256L * 1024L
const val MAX_STUDENT_MISTAKE_CATALOG_EXPORT_UTF8_BYTES = 512L * 1024L
private const val MAX_CATALOG_SEARCH_CHARS = 256
private const val MAX_CURSOR_CHARS = 4096
private const val MAX_PROJECTION_TEXT_CHARS = 256
private const val REVISION_FINGERPRINT_DOMAIN = "derived-student-mistake-catalog-revision-v1"
private const val QUERY_FINGERPRINT_DOMAIN = "derived-student-mistake-catalog-query-v1"
private const val LEARNER_FINGERPRINT_DOMAIN = "derived-student-mistake-catalog-learner-v1"
private val SHA_256_REGEX = Regex("[0-9a-f]{64}")

internal fun StudentMistakeCatalogItem.catalogPayloadUtf8Bytes(): Long =
    sequenceOf(
        entryId,
        problemId,
        problemRevisionId,
        practiceUnitId,
        subject.name,
        title,
        practiceUnitTitle,
        masteryStatus.name,
        favorite.toString(),
        changedAtEpochMillis.toString(),
    ).plus(sectionStableIds.asSequence())
        .plus(
            knowledgeNodes.asSequence().flatMap { node ->
                sequenceOf(
                    node.subject.name,
                    node.knowledgeNodeId,
                    node.taxonomyVersion,
                    node.knowledgePackVersion,
                )
            },
        ).plus(knowledgeDisplayNames.asSequence())
        .sumOf { value -> value.toByteArray(Charsets.UTF_8).size.toLong() + 1L }
