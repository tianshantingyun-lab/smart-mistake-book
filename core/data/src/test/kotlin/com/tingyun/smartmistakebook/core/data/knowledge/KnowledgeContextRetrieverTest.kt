package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeRelationRecord
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeContextRetrieverTest {
    @Test
    fun `recalls a relevant knowledge point and its visible parent`() {
        val topic = node("topic-function", "函数性质", KnowledgeNodeGranularity.TOPIC)
        val monotonicity = node(
            id = "atomic-monotonicity",
            name = "借助函数图象判断单调区间",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = topic.knowledgeNodeId,
            aliases = setOf("从图象判断函数增减性"),
            boundary = "只依据函数图象读取增减区间。",
        )
        val extrema = node(
            id = "atomic-extrema",
            name = "从函数图象识别最大值与最小值",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = topic.knowledgeNodeId,
        )

        val selected = KnowledgeContextRetriever.select(
            candidates = listOf(topic, extrema, monotonicity),
            questionText = "观察函数图象，判断函数在哪些区间单调递增。",
            limit = 2,
        )

        assertEquals(listOf(topic.knowledgeNodeId, monotonicity.knowledgeNodeId), selected.map {
            it.knowledgeNodeId
        })
    }

    @Test
    fun `never discloses an unreviewed candidate even when its wording is an exact match`() {
        val unreviewed = node(
            id = "candidate-derivative",
            name = "用导数符号判断单调区间",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = "topic-calculus",
            verificationStatus = KnowledgeNodeVerificationStatus.MODEL_CANDIDATE,
        )

        val selected = KnowledgeContextRetriever.select(
            candidates = listOf(unreviewed),
            questionText = "用导数符号判断单调区间",
            limit = 64,
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `adds a trusted prerequisite beside a recalled dependent point`() {
        val topic = node("topic-weather", "天气系统", KnowledgeNodeGranularity.TOPIC)
        val prerequisite = node(
            "atomic-identify-weather-system",
            "识别锋与高低压系统",
            KnowledgeNodeGranularity.ATOMIC,
            topic.knowledgeNodeId,
        )
        val dependent = node(
            "atomic-explain-weather",
            "结合天气图解释天气现象成因",
            KnowledgeNodeGranularity.ATOMIC,
            topic.knowledgeNodeId,
        )
        val relation = KnowledgeNodeRelationRecord(
            relationId = "relation-1",
            subject = "MATH",
            prerequisiteKnowledgeNodeId = prerequisite.knowledgeNodeId,
            dependentKnowledgeNodeId = dependent.knowledgeNodeId,
            relationType = "PREREQUISITE_OF",
            sourceId = "source-1",
            sourceLocator = "课程内容",
            reviewedAtEpochMillis = 1,
        )

        val selected = KnowledgeContextRetriever.select(
            candidates = listOf(topic, prerequisite, dependent),
            relations = listOf(relation),
            questionText = "结合天气图解释这次降水的形成原因。",
            limit = 3,
        )

        assertEquals(
            setOf(topic.knowledgeNodeId, prerequisite.knowledgeNodeId, dependent.knowledgeNodeId),
            selected.mapTo(hashSetOf()) { it.knowledgeNodeId },
        )
    }

    @Test
    fun `a matching parent alone does not make every child relevant`() {
        val topic = node("topic-function", "函数性质", KnowledgeNodeGranularity.TOPIC)
        val unrelated = node(
            "atomic-function-unrelated",
            "求数列通项公式",
            KnowledgeNodeGranularity.ATOMIC,
            topic.knowledgeNodeId,
        )
        val relevant = node(
            "atomic-function-range",
            "根据图象求函数值域",
            KnowledgeNodeGranularity.ATOMIC,
            topic.knowledgeNodeId,
        )

        val selected = KnowledgeContextRetriever.select(
            candidates = listOf(topic, unrelated, relevant),
            questionText = "观察函数图象，写出函数的值域。",
            limit = 3,
        )

        assertEquals(
            listOf(topic.knowledgeNodeId, relevant.knowledgeNodeId),
            selected.map(KnowledgeNodeSeedRecord::knowledgeNodeId),
        )
    }

    @Test
    fun `a specific boundary can recall a point even when its name is broader`() {
        val topic = node("topic-function", "函数性质", KnowledgeNodeGranularity.TOPIC)
        val point = node(
            id = "atomic-function-image-reading",
            name = "读取函数图象信息",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = topic.knowledgeNodeId,
            boundary = "从图象中读取函数的零点和符号区间。",
        )

        val selected = KnowledgeContextRetriever.select(
            candidates = listOf(topic, point),
            questionText = "根据图象写出函数的零点。",
            limit = 2,
        )

        assertEquals(listOf(topic, point), selected)
    }

    @Test
    fun `large subject index stays bounded and can recall a relevant point at the tail`() {
        val topic = node("topic-calculus", "导数与函数性质", KnowledgeNodeGranularity.TOPIC)
        val filler = (1..8_000).map { index ->
            node(
                id = "atomic-filler-$index",
                name = "无关分类条目$index",
                granularity = KnowledgeNodeGranularity.ATOMIC,
                parentId = topic.knowledgeNodeId,
            )
        }
        val target = node(
            id = "atomic-derivative-monotonicity",
            name = "根据导数符号判断函数单调区间",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = topic.knowledgeNodeId,
            aliases = setOf("导数与单调性"),
        )
        var selected = emptyList<KnowledgeNodeSeedRecord>()

        val elapsedMillis = measureTimeMillis {
            repeat(10) {
                selected = KnowledgeContextRetriever.select(
                    candidates = listOf(topic) + filler + target,
                    questionText = "已知导函数符号变化，判断原函数的单调递增区间。",
                    limit = 64,
                )
            }
        }

        assertTrue(selected.any { it.knowledgeNodeId == target.knowledgeNodeId })
        assertTrue(selected.size <= 64)
        assertFalse(selected.any { it.verificationStatus == KnowledgeNodeVerificationStatus.MODEL_CANDIDATE.name })
        assertTrue("10 recalls took ${elapsedMillis}ms", elapsedMillis < 5_000)
        println("knowledge-recall benchmark: 8,002 candidates x 10 = ${elapsedMillis}ms")
    }

    private fun node(
        id: String,
        name: String,
        granularity: KnowledgeNodeGranularity,
        parentId: String? = null,
        aliases: Set<String> = emptySet(),
        boundary: String? = null,
        verificationStatus: KnowledgeNodeVerificationStatus =
            if (granularity == KnowledgeNodeGranularity.TOPIC) {
                KnowledgeNodeVerificationStatus.CURATED
            } else {
                KnowledgeNodeVerificationStatus.SOURCE_GROUNDED
            },
    ) = KnowledgeNodeSeedRecord(
        knowledgeNodeId = id,
        stableCode = "test:$id",
        subject = "MATH",
        displayName = name,
        parentKnowledgeNodeId = parentId,
        taxonomyVersion = "test-v1",
        createdAtEpochMillis = 1,
        canonicalName = name,
        nodeKind = if (granularity == KnowledgeNodeGranularity.TOPIC) {
            KnowledgeNodeKind.TOPIC.name
        } else {
            KnowledgeNodeKind.REASONING.name
        },
        granularity = granularity.name,
        aliases = aliases,
        boundaryMarkdown = boundary,
        verificationStatus = verificationStatus.name,
    )
}
