package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentMistakeCatalogProjectionBoundsTest {
    @Test
    fun wireCodecEnforcesRelationshipCountOnWriteAndRead() {
        val tooManyValues =
            List(MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM + 1) { "section-$it" }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionWireCodec.encodeStrings(tooManyValues)
        }

        val oversizedWire =
            List(MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM + 1) { "c2VjdGlvbg" }
                .joinToString(",")
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionWireCodec.decodeStrings(oversizedWire)
        }
    }

    @Test
    fun itemAndStoredEntryEnforceIndependentUtf8Budgets() {
        assertThrows(IllegalArgumentException::class.java) {
            item(title = "题".repeat(11_000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            entity(normalizedSearchText = "题".repeat(22_000))
        }
    }

    @Test
    fun pageAndExportBudgetsRejectAggregateOverflow() {
        val revision = revision()
        val largeItems = List(18) { index -> item(index, "a".repeat(30_000)) }

        assertThrows(IllegalArgumentException::class.java) {
            StudentMistakeCatalogPageResult.Content(
                revision = revision,
                items = largeItems.take(9),
                nextCursor = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StudentMistakeCatalogExportResult.Content(revision, largeItems)
        }
    }

    @Test
    fun facetResultRequiresBoundedListsAndReportsTruncatedDimensions() {
        val bounded =
            List(MAX_STUDENT_MISTAKE_CATALOG_FACET_VALUES_PER_DIMENSION) { index ->
                StudentMistakeCatalogFacetCount("section-$index", 1L)
            }
        val content =
            StudentMistakeCatalogFacetResult.Content(
                revision = revision(),
                totalCount = 1L,
                subjects = emptyList(),
                sections = bounded,
                knowledgeNodes = emptyList(),
                masteryStatuses = emptyList(),
                truncatedDimensions = setOf(StudentMistakeCatalogFacetDimension.SECTION),
            )
        assertEquals(setOf(StudentMistakeCatalogFacetDimension.SECTION), content.truncatedDimensions)

        assertThrows(IllegalArgumentException::class.java) {
            content.copy(
                sections =
                    bounded + StudentMistakeCatalogFacetCount("section-overflow", 1L),
            )
        }
    }

    @Test
    fun facetSqlAlwaysRequestsOneBoundedLookaheadRow() {
        val query =
            DerivedStudentMistakeCatalogFilterQuery(
                ftsMatchExpression = null,
                normalizedSearchText = null,
                subject = null,
                favoriteOnly = false,
                masteryFilterDisabled = true,
                masteryStatuses = emptyList(),
                sectionStableId = null,
                knowledgeSubject = null,
                knowledgeNodeId = null,
                knowledgeTaxonomyVersion = null,
                knowledgePackVersion = null,
            )
        val queries =
            listOf(
                DerivedStudentMistakeCatalogSql.subjectFacets(query, "generation"),
                DerivedStudentMistakeCatalogSql.sectionFacets(query, "generation"),
                DerivedStudentMistakeCatalogSql.knowledgeFacets(query, "generation"),
                DerivedStudentMistakeCatalogSql.masteryFacets(query, "generation"),
            )
        assertTrue(queries.all { it.sql.trimEnd().endsWith("LIMIT ?") })
    }

    private fun item(
        index: Int = 0,
        title: String = "title",
    ): StudentMistakeCatalogItem =
        StudentMistakeCatalogItem(
            entryId = "entry-$index",
            problemId = "problem-$index",
            problemRevisionId = "revision-$index",
            practiceUnitId = "unit-$index",
            subject = SubjectKind.MATH,
            title = title,
            practiceUnitTitle = "unit",
            sectionStableIds = emptyList(),
            knowledgeNodes = emptyList(),
            knowledgeDisplayNames = emptyList(),
            masteryStatus = MasteryStatus.UNKNOWN,
            favorite = false,
            changedAtEpochMillis = index.toLong(),
        )

    private fun entity(
        normalizedSearchText: String = "search",
    ): DerivedStudentMistakeCatalogEntryEntity =
        DerivedStudentMistakeCatalogEntryEntity(
            generationId = "generation",
            entryId = "entry",
            problemId = "problem",
            problemRevisionId = "revision",
            practiceUnitId = "unit",
            subject = SubjectKind.MATH.name,
            title = "title",
            practiceUnitTitle = "unit",
            sectionStableIdsWire = "",
            knowledgeNodesWire = "",
            knowledgeDisplayNamesWire = "",
            masteryStatus = MasteryStatus.UNKNOWN.name,
            favorite = false,
            changedAtEpochMillis = 0L,
            normalizedSearchText = normalizedSearchText,
            tokenizedSearchText = normalizedSearchText,
        )

    private fun revision(): StudentMistakeCatalogRevision =
        StudentMistakeCatalogRevision(
            studentChangeVersion = 1L,
            masteryLedgerSequence = 1L,
            masteryAsOfEpochMillis = 1L,
            knowledgeActivationGeneration = 1L,
            knowledgeManifestFingerprint = "a".repeat(64),
            knowledgeTaxonomyVersion = "taxonomy-v1",
            knowledgePackVersion = "pack-v1",
        )
}
