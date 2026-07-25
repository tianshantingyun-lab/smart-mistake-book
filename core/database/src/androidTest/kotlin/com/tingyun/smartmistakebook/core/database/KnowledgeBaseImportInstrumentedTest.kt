package com.tingyun.smartmistakebook.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeBaseImportInstrumentedTest {
    @Test
    fun reviewedGapCanReuseExistingSubjectParentAndSource() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
        try {
            store.importKnowledgeBase(
                sources = listOf(source()),
                nodes = listOf(topic(), atomic()),
                bindings = listOf(binding(TOPIC_ID), binding(ATOMIC_ID)),
            )
            val rangeNode = atomic().copy(
                knowledgeNodeId = RANGE_ID,
                stableCode = "research-v1:math:atomic:range",
                displayName = "求函数值域",
                canonicalName = "求函数值域",
                boundaryMarkdown = "只求给定函数在指定定义域上的值域。",
            )

            store.importKnowledgeBase(
                sources = emptyList(),
                nodes = listOf(rangeNode),
                bindings = listOf(binding(RANGE_ID)),
            )

            assertTrue(store.readKnowledgeNodesByIds(setOf(RANGE_ID)).single() == rangeNode)
            assertTrue(store.readKnowledgeNodeSourceBindings(setOf(RANGE_ID)).size == 1)
            val coverage = store.observeReviewedKnowledgeCoverage().first().single()
            assertEquals("MATH", coverage.subject)
            assertEquals(1, coverage.topicCount)
            assertEquals(2, coverage.atomicKnowledgeCount)
            assertEquals(1, coverage.reviewedSourceCount)
            assertEquals(1_000, coverage.latestReviewedAtEpochMillis)
        } finally {
            store.close()
        }
    }

    @Test
    fun unreviewedResearchCannotReachRoom() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
        try {
            val thrown = runCatching {
                store.importKnowledgeBase(
                    sources = listOf(source()),
                    nodes = listOf(topic(), atomic()),
                    bindings = listOf(
                        binding(TOPIC_ID),
                        binding(ATOMIC_ID).copy(reviewedAtEpochMillis = null),
                    ),
                )
            }.exceptionOrNull()

            assertTrue(thrown is DatabaseContractViolationException)
            assertTrue(store.readKnowledgeNodesByIds(setOf(TOPIC_ID, ATOMIC_ID)).isEmpty())
            assertTrue(store.readKnowledgeSourcesByIds(setOf(SOURCE_ID)).isEmpty())
            assertTrue(store.readKnowledgeNodeSourceBindings(setOf(TOPIC_ID, ATOMIC_ID)).isEmpty())
        } finally {
            store.close()
        }
    }

    private fun source() = KnowledgeSourceSeedRecord(
        sourceId = SOURCE_ID,
        subject = "MATH",
        sourceType = KnowledgeSourceType.MANUAL_RESEARCH.name,
        title = "经审校的函数知识目录",
        publisher = "授权教研机构",
        edition = null,
        sourceUri = "https://example.edu/math/function-index",
        licenseStatus = KnowledgeSourceLicenseStatus.REFERENCE_ONLY.name,
        contentFingerprint = "B".repeat(64),
        importedAtEpochMillis = 1_000,
    )

    private fun topic() = KnowledgeNodeSeedRecord(
        knowledgeNodeId = TOPIC_ID,
        stableCode = "research-v1:math:topic:function",
        subject = "MATH",
        displayName = "函数",
        parentKnowledgeNodeId = null,
        taxonomyVersion = "research-v1",
        createdAtEpochMillis = 1_000,
        canonicalName = "函数",
        nodeKind = KnowledgeNodeKind.TOPIC.name,
        granularity = KnowledgeNodeGranularity.TOPIC.name,
        verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
    )

    private fun atomic() = KnowledgeNodeSeedRecord(
        knowledgeNodeId = ATOMIC_ID,
        stableCode = "research-v1:math:atomic:monotonicity",
        subject = "MATH",
        displayName = "判断函数单调性",
        parentKnowledgeNodeId = TOPIC_ID,
        taxonomyVersion = "research-v1",
        createdAtEpochMillis = 1_000,
        canonicalName = "判断函数单调性",
        nodeKind = KnowledgeNodeKind.REASONING.name,
        granularity = KnowledgeNodeGranularity.ATOMIC.name,
        boundaryMarkdown = "只判断给定函数在指定区间上的单调性。",
        verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
    )

    private fun binding(nodeId: String) = KnowledgeNodeSourceBindingSeedRecord(
        knowledgeNodeId = nodeId,
        sourceId = SOURCE_ID,
        sourceLocator = "函数目录·单调性",
        derivationNote = "人工核对原始材料后拆分。",
        reviewedAtEpochMillis = 1_000,
    )

    private companion object {
        const val SOURCE_ID = "source:research:math"
        const val TOPIC_ID = "kb:research-v1:math:topic:function"
        const val ATOMIC_ID = "kb:research-v1:math:atomic:monotonicity"
        const val RANGE_ID = "kb:research-v1:math:atomic:range"
    }
}
