package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.BindingAcceptanceSource
import com.tingyun.smartmistakebook.core.model.ProblemRelationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MistakeOrganizationRepositoryTest {
    @Test
    fun automaticAndUserCorrectionAuthoritiesRemainDistinctFromLegacyConfirmation() {
        assertEquals("LOCAL_POLICY_ACCEPTED", BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED.name)
        assertEquals("USER_CORRECTED", BindingAcceptanceSource.USER_CORRECTED.name)
        assertEquals("USER_CONFIRMED", BindingAcceptanceSource.USER_CONFIRMED.name)
    }

    @Test
    fun userCanAddAValidatedKnowledgeCorrection() {
        val correction = UserProblemClassification(
            dimension = ClassificationDimension.KNOWLEDGE,
            displayName = "导数零点与单调区间",
        )
        val selection = ProblemOrganizationSelection(
            classificationIndexes = emptySet(),
            relationIndexes = emptySet(),
            userClassifications = listOf(correction),
        )

        assertEquals(listOf(correction), selection.userClassifications)
    }

    @Test
    fun userClassificationRejectsAuthorityAndTextBoundaryViolations() {
        assertTrue(
            runCatching {
                UserProblemClassification(ClassificationDimension.SUBJECT, "数学")
            }.isFailure,
        )
        assertTrue(
            runCatching {
                UserProblemClassification(ClassificationDimension.ERROR_CAUSE, "计算失误")
            }.isFailure,
        )
        assertTrue(
            runCatching {
                UserProblemClassification(ClassificationDimension.KNOWLEDGE, " 未去空格 ")
            }.isFailure,
        )
    }

    @Test
    fun relationReplacementIsExplicitAndCanRepresentClearAll() {
        val classificationOnly = ProblemOrganizationSelection(emptySet(), emptySet())
        val addSuggestedRelation = ProblemOrganizationSelection(
            classificationIndexes = emptySet(),
            relationIndexes = setOf(0),
        )
        val clearAllRelations = ProblemOrganizationSelection(
            classificationIndexes = emptySet(),
            relationIndexes = emptySet(),
            replaceRelations = true,
        )

        assertTrue(!classificationOnly.replaceRelations)
        assertTrue(!addSuggestedRelation.replaceRelations)
        assertTrue(clearAllRelations.replaceRelations)
    }

    @Test
    fun exactRelationRemovalDoesNotRequestFullReplacement() {
        val removal = ProblemOrganizationRelationKey(
            targetProblemId = "problem-a",
            targetProblemRevisionId = "revision-a",
            kind = ProblemRelationKind.VARIANT_OF,
        )
        val selection = ProblemOrganizationSelection(
            classificationIndexes = emptySet(),
            relationIndexes = emptySet(),
            relationRemovals = setOf(removal),
        )

        assertEquals(setOf(removal), selection.relationRemovals)
        assertTrue(!selection.replaceRelations)
    }
}
