package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeSearchFeatureExtractorTest {
    @Test
    fun `question and reviewed node share Chinese phrase features`() {
        val node = KnowledgeNodeSeedRecord(
            knowledgeNodeId = "knowledge:math:monotonicity",
            stableCode = "math:monotonicity",
            subject = "MATH",
            displayName = "判断函数单调性",
            parentKnowledgeNodeId = "topic:function",
            taxonomyVersion = "test-v1",
            createdAtEpochMillis = 1,
            canonicalName = "根据导数符号判断函数单调区间",
            nodeKind = KnowledgeNodeKind.REASONING.name,
            granularity = KnowledgeNodeGranularity.ATOMIC.name,
            aliases = setOf("导数与单调性"),
            boundaryMarkdown = "判断原函数在哪些区间递增或递减。",
            verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
        )

        val overlap = KnowledgeSearchFeatureExtractor.fromNode(node) intersect
            KnowledgeSearchFeatureExtractor.fromQuestion(
                "已知导函数的符号变化，求原函数的单调递增区间。",
            )

        assertTrue(overlap.contains("单调"))
        assertTrue(overlap.contains("函数"))
        assertTrue(overlap.isNotEmpty())
    }

    @Test
    fun `query feature count is bounded`() {
        val features = KnowledgeSearchFeatureExtractor.fromQuestion("知识点检索".repeat(1_000))

        assertTrue(features.size <= KnowledgeSearchFeatureExtractor.MAX_QUERY_FEATURES)
    }

    @Test
    fun `long multi-part question keeps concepts from the final paragraph`() {
        val earlyParagraphs = List(200) { index ->
            "第${index}段给出与计算无关的背景材料和条件说明。"
        }
        val question = (earlyParagraphs + "最后请根据导数符号判断函数的单调区间。")
            .joinToString("\n")

        val features = KnowledgeSearchFeatureExtractor.fromQuestion(question)

        assertTrue("Tail concept was dropped from a bounded query", "导数" in features)
        assertTrue("Tail concept was dropped from a bounded query", "单调" in features)
        assertTrue(features.size <= KnowledgeSearchFeatureExtractor.MAX_QUERY_FEATURES)
    }

    @Test
    fun `feature extraction is deterministic`() {
        val question = "先阅读材料。\n再根据图象判断函数的单调区间。"

        assertEquals(
            KnowledgeSearchFeatureExtractor.fromQuestion(question),
            KnowledgeSearchFeatureExtractor.fromQuestion(question),
        )
    }
}
