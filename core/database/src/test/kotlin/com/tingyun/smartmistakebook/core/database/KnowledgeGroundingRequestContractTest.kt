package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeGroundingRequestContractTest {
    @Test
    fun acceptsPendingRequestBoundToItsNormalizedGap() {
        KnowledgeGroundingRequestContract.validate(listOf(validRequest()))
    }

    @Test
    fun rejectsCallerSuppliedGroupingKeyThatDoesNotMatchTheGap() {
        expectViolation("groundingKey") {
            KnowledgeGroundingRequestContract.validate(
                listOf(validRequest().copy(groundingKey = "grounding:${"f".repeat(64)}")),
            )
        }
    }

    @Test
    fun rejectsRequestPretendingToBeResolvedBeforeResearch() {
        expectViolation("must be pending") {
            KnowledgeGroundingRequestContract.validate(
                listOf(validRequest().copy(status = StudyDbValue.KnowledgeGroundingStatus.RESOLVED)),
            )
        }
    }

    private fun validRequest(): KnowledgeGroundingRequestRecord {
        val parent = "函数性质"
        val query = "高中数学 导数符号 单调性"
        val groundingKey = KnowledgeGroundingFingerprint.of(SubjectKind.MATH, parent, query)
        val organizationRequestId = "organization-request:1"
        return KnowledgeGroundingRequestRecord(
            groundingRequestId = KnowledgeGroundingFingerprint.occurrenceId(
                organizationRequestId,
                0,
                groundingKey,
            ),
            groundingKey = groundingKey,
            organizationRequestId = organizationRequestId,
            organizationRequestFingerprint = "a".repeat(64),
            requestOrdinal = 0,
            problemId = "problem-1",
            problemRevisionId = "revision-1",
            practiceUnitId = "unit-1",
            subject = SubjectKind.MATH.name,
            query = query,
            expectedParentKnowledgeDisplayName = parent,
            reasonMarkdown = "现有本体无法可靠归因到原子知识。",
            createdAtEpochMillis = 1_000,
            updatedAtEpochMillis = 1_000,
        )
    }

    private fun expectViolation(messagePart: String, block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue(thrown is DatabaseContractViolationException)
        assertTrue(thrown?.message.orEmpty().contains(messagePart))
    }
}
