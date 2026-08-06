package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentMistakeCatalogProjectionSqlTest {
    @Test
    fun unfilteredPageUsesThreePartKeysetAndNeverOffset() {
        val query =
            DerivedStudentMistakeCatalogSql.page(
                query = StudentMistakeCatalogFilter().toTestStoreQuery(),
                generationId = "generation",
                cursor = DerivedStudentMistakeCatalogCursorKey(10L, "problem", "entry"),
                limit = 31,
            ).sql

        assertTrue(query.contains("entry.changed_at_epoch_millis < ?"))
        assertTrue(query.contains("entry.problem_id < ?"))
        assertTrue(query.contains("entry.entry_id < ?"))
        assertTrue(
            query.contains(
                "ORDER BY entry.changed_at_epoch_millis DESC, " +
                    "entry.problem_id DESC, entry.entry_id DESC",
            ),
        )
        assertFalse(query.contains("OFFSET", ignoreCase = true))
        assertFalse(query.contains("_fts"))
    }

    @Test
    fun combinedFilterUsesFtsAndDuplicateSafeRelationshipExistenceChecks() {
        val filter =
            StudentMistakeCatalogFilter(
                text = "二次函数",
                subject = SubjectKind.MATH,
                sectionStableId = "math.algebra",
                knowledgeNode =
                    KnowledgeNodeRef(
                        subject = SubjectKind.MATH,
                        knowledgeNodeId = "math.quadratic",
                        taxonomyVersion = "taxonomy-v1",
                        knowledgePackVersion = "pack-v1",
                    ),
                masteryStatuses = setOf(MasteryStatus.STALE, MasteryStatus.LEARNING),
                favoriteOnly = true,
            )
        val query =
            DerivedStudentMistakeCatalogSql.page(
                query = filter.toTestStoreQuery(),
                generationId = "generation",
                cursor = null,
                limit = 31,
            ).sql

        assertTrue(query.contains("tokenized_search_text MATCH ?"))
        assertTrue(query.contains("entry.mastery_status IN (?,?)"))
        assertTrue(query.contains("FROM derived_non_authoritative_catalog_section"))
        assertTrue(query.contains("FROM derived_non_authoritative_catalog_knowledge"))
        assertFalse(query.contains("JOIN derived_non_authoritative_catalog_section AS section_filter"))
        assertFalse(query.contains("OFFSET", ignoreCase = true))
    }

    private fun StudentMistakeCatalogFilter.toTestStoreQuery():
        DerivedStudentMistakeCatalogFilterQuery {
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
}
