package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.data.study.FakeStudyDatabasePort
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeRelationRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeCodeRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拍照讲题两段式注入的取数（ADR 0001 / D5、审计 R2 断链一）：
 * 确认绑定优先；无绑定时题面走 B 路召回 → A 路精排（与错题归类同源）。
 * 消灭的失败：拍照（产品主入口）Plan 输入零 KB 注入——本测试钉住"有题面、有库
 * → 候选非空且角色为检索候选"，以及"零命中 = 合法空注入 ≠ 加载失败"的分界。
 */
class RoomTutorKnowledgeContextLoaderTest {

    private fun loaderFor(port: StudyDatabasePort) = RoomTutorKnowledgeContextLoader(port)

    private fun node(
        id: String,
        name: String,
        subject: String = "MATH",
        status: String = "CURATED",
        granularity: String = "ATOMIC",
    ) = KnowledgeNodeSeedRecord(
        knowledgeNodeId = id,
        stableCode = "stable-$id",
        subject = subject,
        displayName = name,
        parentKnowledgeNodeId = null,
        taxonomyVersion = "cn-highschool-m1-v1",
        createdAtEpochMillis = 1_000,
        canonicalName = name,
        nodeKind = "CONCEPT",
        granularity = granularity,
        verificationStatus = status,
    )

    @Test
    fun `confirmed binding wins over retrieval and carries prerequisites`() = runBlocking {
        val port = FakeStudyDatabasePort()
        port.knowledgeNodes += node("kc-bound", "配方法")
        port.knowledgeNodes += node("kc-prereq", "一元二次方程")
        port.knowledgeNodeRelations += KnowledgeNodeRelationRecord(
            relationId = "rel-1",
            subject = "MATH",
            prerequisiteKnowledgeNodeId = "kc-prereq",
            dependentKnowledgeNodeId = "kc-bound",
            relationType = "PREREQUISITE",
            sourceId = "src-1",
            sourceLocator = "loc-1",
            reviewedAtEpochMillis = 1_000,
        )

        val result = loaderFor(port).knowledgePreDisclosure(
            subject = "MATH",
            confirmedBindingNodeIds = listOf("kc-bound"),
            questionText = "用配方法求函数的单调区间",
        )

        assertFalse(result.loadFailed)
        assertEquals(listOf("kc-bound"), result.candidateNodeIds)
        assertEquals(
            listOf(TutorKnowledgeCodeRole.CONFIRMED_BINDING, TutorKnowledgeCodeRole.PREREQUISITE),
            result.preDisclosures.map { it.role },
        )
        assertEquals("配方法", result.preDisclosures[0].displayName)
        assertEquals("一元二次方程", result.preDisclosures[1].displayName)
        assertTrue("派发方只给未赋码条目（K1..Kn 由会话注册表分配）", result.preDisclosures.all { it.code == null })
    }

    @Test
    fun `photo path runs the two stage retrieval and marks candidates`() = runBlocking {
        val port = FakeStudyDatabasePort()
        // A 路（KnowledgeContextRetriever.select）只放 trusted 状态：MODEL_CANDIDATE 会被滤掉。
        port.recallCandidates += node("kc-cand", "函数单调性")
        port.recallCandidates += node("kc-noisy", "未审节点", status = "MODEL_CANDIDATE")

        val result = loaderFor(port).knowledgePreDisclosure(
            subject = "MATH",
            confirmedBindingNodeIds = emptyList(),
            questionText = "用配方法求函数的单调区间",
        )

        assertFalse(result.loadFailed)
        assertTrue("A 路精排应选中可信候选", "kc-cand" in result.candidateNodeIds)
        assertFalse("MODEL_CANDIDATE 不进候选", "kc-noisy" in result.candidateNodeIds)
        val candidate = result.preDisclosures.single { it.knowledgeNodeId == "kc-cand" }
        assertEquals(TutorKnowledgeCodeRole.RETRIEVAL_CANDIDATE, candidate.role)
        assertTrue("候选条目未赋码", candidate.code == null)
    }

    @Test
    fun `zero retrieval hits is a legal empty injection not a failure`() = runBlocking {
        val port = FakeStudyDatabasePort()

        val result = loaderFor(port).knowledgePreDisclosure(
            subject = "MATH",
            confirmedBindingNodeIds = emptyList(),
            questionText = "一道知识库里查不到的题",
        )

        assertFalse("零命中是合法空注入（维持现状语义）", result.loadFailed)
        assertTrue(result.preDisclosures.isEmpty())
        assertTrue(result.candidateNodeIds.isEmpty())
    }

    @Test
    fun `a blank question text yields an empty candidate set`() = runBlocking {
        val port = FakeStudyDatabasePort()
        port.recallCandidates += node("kc-cand", "函数单调性")

        val result = loaderFor(port).knowledgePreDisclosure(
            subject = "MATH",
            confirmedBindingNodeIds = emptyList(),
            questionText = "   ",
        )

        assertFalse(result.loadFailed)
        assertTrue("没有可检索词就不拿并列前几名凑候选", result.candidateNodeIds.isEmpty())
    }

    @Test
    fun `a database failure is surfaced as loadFailed not a silent empty`() = runBlocking {
        val result = loaderFor(FailingRecallPort()).knowledgePreDisclosure(
            subject = "MATH",
            confirmedBindingNodeIds = emptyList(),
            questionText = "用配方法求函数的单调区间",
        )

        assertTrue("读取失败必须显式标记（prompt 披露『教学材料未加载』）", result.loadFailed)
        assertTrue(result.preDisclosures.isEmpty())
    }

    /** recall 一查就炸的假端口：把"读取失败"这条路径变成可测的。 */
    private class FailingRecallPort : StudyDatabasePort by FakeStudyDatabasePort() {
        override suspend fun readSubjectKnowledgeRecallCandidates(
            subject: String,
            searchFeatures: Set<String>,
            limit: Int,
            queryText: String?,
        ): List<KnowledgeNodeSeedRecord> = throw IllegalStateException("boom")
    }
}
