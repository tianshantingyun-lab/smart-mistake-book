package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.data.study.FakeStudyDatabasePort
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前置关系图的解析（spec §2.9 / §6-L4）。这个类是"图从未被喂进排程请求"这个长期缺陷的修复
 * 落点，因此它自己的边界——分块上限、科目分区、前置节点取回——都要在这里判得出来。
 */
class KnowledgePrerequisiteReaderTest {

    private val dependentId = "knowledge:math.monotonicity-symbolic"
    private val prerequisiteId = "knowledge:math.read-monotonicity-from-graph"

    @Test
    fun `the graph maps each dependent to its prerequisites`() = runBlocking {
        val database = seeded()

        val graph = KnowledgePrerequisiteReader(database).graphFor(setOf(dependentId))

        assertEquals(setOf(prerequisiteId), graph.prerequisitesByDependent[dependentId])
    }

    @Test
    fun `the graph also carries the prerequisite's own row`() = runBlocking {
        // 前置 KC 通常不在被查询集合里（关系指向集合之外），而补救卡需要它的名称与科目。
        // 只回带被查询的那些行，会让"缺的是哪一个前置"无处可取。
        val database = seeded()

        val graph = KnowledgePrerequisiteReader(database).graphFor(setOf(dependentId))

        assertEquals("从图像读取单调性", graph.nodesById.getValue(prerequisiteId).displayName)
        assertEquals("MATH", graph.nodesById.getValue(prerequisiteId).subject)
    }

    @Test
    fun `a question with no prerequisite relations yields an empty graph`() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            addNode("knowledge:math.loose", subject = "MATH", displayName = "孤立知识点")
        }

        val graph = KnowledgePrerequisiteReader(database)
            .graphFor(setOf("knowledge:math.loose"))

        assertTrue(graph.prerequisitesByDependent.isEmpty())
        assertEquals(setOf("knowledge:math.loose"), graph.nodesById.keys)
    }

    @Test
    fun `an empty request does not touch the database`() = runBlocking {
        val graph = KnowledgePrerequisiteReader(FakeStudyDatabasePort()).graphFor(emptySet())

        assertTrue(graph.prerequisitesByDependent.isEmpty())
        assertTrue(graph.nodesById.isEmpty())
    }

    @Test
    fun `more dependents than the store accepts are queried in chunks`() = runBlocking {
        // RoomKnowledgeBaseStore 对 `dependentKnowledgeNodeIds` 有 256 的硬上限，超限直接抛。
        // 因此"分块"不是性能优化而是正确性前提；假实现复制了同一条 require，这里超限的请求
        // 只有真的分了块才能拿到全部关系。
        val nodeCount = MAX_DEPENDENT_NODES_PER_QUERY + 44
        val database = FakeStudyDatabasePort().apply {
            repeat(nodeCount) { index ->
                val dependent = "knowledge:math.dependent-$index"
                val prerequisite = "knowledge:math.prerequisite-$index"
                addNode(dependent, displayName = "待学知识点 $index")
                addNode(prerequisite, displayName = "前置知识点 $index")
                addRelation(prerequisite, dependent)
            }
        }

        val graph = KnowledgePrerequisiteReader(database).graphFor(
            (0 until nodeCount).mapTo(linkedSetOf()) { "knowledge:math.dependent-$it" },
        )

        assertEquals(nodeCount, graph.prerequisitesByDependent.size)
        assertEquals(
            setOf("knowledge:math.prerequisite-0"),
            graph.prerequisitesByDependent["knowledge:math.dependent-0"],
        )
        assertEquals(
            setOf("knowledge:math.prerequisite-${nodeCount - 1}"),
            graph.prerequisitesByDependent["knowledge:math.dependent-${nodeCount - 1}"],
        )
    }

    @Test
    fun `a prerequisite recorded under another subject is not attributed`() = runBlocking {
        // 关系表按科目分区。用错误的科目去查会**静默返回空集**而不是报错，所以这里锁定
        // "以 KC 自己的科目为准"：节点是 MATH，关系却记在 PHYSICS 下，就不该被取回。
        val database = FakeStudyDatabasePort().apply {
            addNode(dependentId, subject = "MATH", displayName = "符号化表达单调性")
            addNode(prerequisiteId, subject = "PHYSICS", displayName = "另一科的前置")
            addRelation(prerequisiteId, dependentId, subject = "PHYSICS")
        }

        val graph = KnowledgePrerequisiteReader(database).graphFor(setOf(dependentId))

        assertEquals(emptySet<String>(), graph.prerequisitesByDependent[dependentId].orEmpty())
        assertNull(graph.nodesById[prerequisiteId])
    }

    private fun seeded() = FakeStudyDatabasePort().apply {
        addNode(dependentId, subject = "MATH", displayName = "符号化表达单调性")
        addNode(prerequisiteId, subject = "MATH", displayName = "从图像读取单调性")
        addRelation(prerequisiteId, dependentId, subject = "MATH")
    }

    private fun FakeStudyDatabasePort.addNode(
        knowledgeNodeId: String,
        displayName: String,
        subject: String = "MATH",
    ) {
        knowledgeNodes += KnowledgeNodeSeedRecord(
            knowledgeNodeId = knowledgeNodeId,
            stableCode = knowledgeNodeId,
            subject = subject,
            displayName = displayName,
            parentKnowledgeNodeId = null,
            taxonomyVersion = "taxonomy-v1",
            createdAtEpochMillis = 1_000,
        )
    }

    private fun FakeStudyDatabasePort.addRelation(
        prerequisiteKnowledgeNodeId: String,
        dependentKnowledgeNodeId: String,
        subject: String = "MATH",
    ) {
        knowledgeNodeRelations += com.tingyun.smartmistakebook.core.database.KnowledgeNodeRelationRecord(
            relationId = "relation:$prerequisiteKnowledgeNodeId->$dependentKnowledgeNodeId",
            subject = subject,
            prerequisiteKnowledgeNodeId = prerequisiteKnowledgeNodeId,
            dependentKnowledgeNodeId = dependentKnowledgeNodeId,
            relationType = StudyDbValue.KnowledgeRelationType.PREREQUISITE_OF,
            sourceId = "source:v1",
            sourceLocator = "v1",
            reviewedAtEpochMillis = 1_000,
        )
    }

    private companion object {
        const val MAX_DEPENDENT_NODES_PER_QUERY = 256
    }
}
