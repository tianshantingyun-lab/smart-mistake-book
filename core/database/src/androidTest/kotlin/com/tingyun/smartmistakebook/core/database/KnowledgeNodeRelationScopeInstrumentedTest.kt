package com.tingyun.smartmistakebook.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 审计 R-10：`readForDependents` 原先把「这张表只有 `PREREQUISITE_OF` 一种关系类型」
 * 当作查询语义的一部分，而那条不变量维护在**另一个文件**里
 * （`KnowledgeNodeRelationContract` 里的一句 `require`）。契约将来放宽一格，
 * 前置图就会静默多出几条"其实不是前置"的边——不崩、不报错，只是多喂给调度器几条
 * 错误的先修关系。修法是把类型写进 SQL，让查询不再依赖别处维护的严格性。
 *
 * 这条用例把那次"将来"提前造出来：**绕过契约，直接往表里插一行另一种类型的关系**，
 * 然后要求 `readKnowledgeNodeRelationsForDependents` 看不见它。
 *
 * 之所以能绕过去，正是这条缺陷的成因：`relation_type` 列上**没有 CHECK 约束**，
 * `knowledge_node_relation` 上也没有触发器——契约是 Kotlin 层的，不是 schema 层的。
 *
 * 用例同时钉三件事，缺一条就会变成"因为插不进去所以看不见"的假绿：
 * ① 那行异类关系**确实躺在表里**（raw SQL 数得出来）；
 * ② 同一对节点上的 `PREREQUISITE_OF` 关系**确实被返回**（查询没坏，只是变严）；
 * ③ 异类那一行**不在返回值里**。
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeNodeRelationScopeInstrumentedTest {

    @Test
    fun aRelationTypeOutsideTheCurrentContractStaysOutOfThePrerequisiteGraph() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "knowledge-relation-scope-${System.nanoTime()}.db"
        val databasePath = context.getDatabasePath(databaseName).path
        val store = StudyDatabaseFactory.open(context, databaseName)
        try {
            val declaredRelation = relation(from = PREREQUISITE_ID, to = DEPENDENT_ID)
            store.importKnowledgeBase(
                sources = listOf(source()),
                nodes = listOf(topic(), atomic(PREREQUISITE_ID), atomic(OTHER_ID), atomic(DEPENDENT_ID)),
                // **每个**导入的节点都要有已核验的来源绑定，主题节点也不例外
                // （契约：`Every imported knowledge node must have reviewed provenance`）。
                bindings = listOf(TOPIC_ID, PREREQUISITE_ID, OTHER_ID, DEPENDENT_ID).map(::binding),
            )
            store.importKnowledgeNodeRelations(listOf(declaredRelation))

            // 契约层不允许的关系类型。它不是"猜出来的下一个取值"，只是一段占位：
            // 用例要证明的恰恰是**查询不关心那个值具体是什么**。
            plantRelationRow(databasePath, relationType = PLANTED_RELATION_TYPE)

            val plantedRowType = readPlantedRowType(databasePath)
            assertEquals(
                "夹具没生效：异类关系行根本没进表（或被写成了别的类型），" +
                    "这条用例会因为「查不到」而假绿",
                PLANTED_RELATION_TYPE,
                plantedRowType,
            )

            val relations = store.readKnowledgeNodeRelationsForDependents(
                subject = SUBJECT,
                dependentKnowledgeNodeIds = setOf(DEPENDENT_ID),
            )

            assertEquals(
                "同一对节点上确实存在的那条前置关系必须照常返回",
                listOf(declaredRelation),
                relations,
            )
            assertTrue(
                "另一种类型的边不能混进前置图（审计 R-10）：实际返回了 " +
                    relations.map { "${it.prerequisiteKnowledgeNodeId}/${it.relationType}" },
                relations.none { it.prerequisiteKnowledgeNodeId == OTHER_ID },
            )
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    /**
     * 直接写底层文件，**故意绕开** `importKnowledgeNodeRelations`：那条路上有契约把关，
     * 插不进这种类型的行——而本用例要复现的正是"契约松动之后，表里会出现什么"。
     */
    private fun plantRelationRow(databasePath: String, relationType: String) {
        SQLiteDatabase.openDatabase(databasePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.execSQL(
                """
                INSERT INTO knowledge_node_relation (
                    relation_id, subject, prerequisite_knowledge_node_id,
                    dependent_knowledge_node_id, relation_type, source_id,
                    source_locator, reviewed_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                // 显式写 `Array<Any>`：这里混了 String 与 Long，让编译器自己推会把 T 推成
                // `Comparable<*> & Serializable` 的交集类型，而 `execSQL` 的参数是 reified。
                arrayOf<Any>(
                    PLANTED_RELATION_ID,
                    SUBJECT,
                    OTHER_ID,
                    DEPENDENT_ID,
                    relationType,
                    SOURCE_ID,
                    "前置关系范围测试",
                    REVIEWED_AT,
                ),
            )
        }
    }

    /**
     * 读回那行异类关系的类型——**先证明夹具真的生效**，再断言查询看不见它。
     * 用一次性 raw 句柄，避免与 Room 的连接池混用。
     */
    private fun readPlantedRowType(databasePath: String): String? =
        SQLiteDatabase.openDatabase(databasePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery(
                "SELECT relation_type FROM knowledge_node_relation WHERE relation_id = ?",
                arrayOf(PLANTED_RELATION_ID),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }

    private fun source() = KnowledgeSourceSeedRecord(
        sourceId = SOURCE_ID,
        subject = SUBJECT,
        sourceType = KnowledgeSourceType.MANUAL_RESEARCH.name,
        title = "经审校的函数知识目录",
        publisher = "授权教研机构",
        edition = null,
        sourceUri = "https://example.edu/math/function-index",
        licenseStatus = KnowledgeSourceLicenseStatus.REFERENCE_ONLY.name,
        // 必须是**大写十六进制**的 64 位：契约用的是 `Regex("[A-F0-9]{64}")`，
        // 全串匹配。用 "R" 这类字母会被 `validateSource` 挡下来（第一次跑就是这么红的）。
        contentFingerprint = "D".repeat(64),
        importedAtEpochMillis = REVIEWED_AT,
    )

    private fun topic() = KnowledgeNodeSeedRecord(
        knowledgeNodeId = TOPIC_ID,
        stableCode = "relation-scope:math:topic:function",
        subject = SUBJECT,
        displayName = "函数性质",
        parentKnowledgeNodeId = null,
        taxonomyVersion = TAXONOMY_VERSION,
        createdAtEpochMillis = REVIEWED_AT,
        canonicalName = "函数性质",
        nodeKind = KnowledgeNodeKind.TOPIC.name,
        granularity = KnowledgeNodeGranularity.TOPIC.name,
        verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
    )

    private fun atomic(id: String) = KnowledgeNodeSeedRecord(
        knowledgeNodeId = id,
        stableCode = "relation-scope:math:atomic:${id.substringAfterLast(':')}",
        subject = SUBJECT,
        displayName = id,
        parentKnowledgeNodeId = TOPIC_ID,
        taxonomyVersion = TAXONOMY_VERSION,
        createdAtEpochMillis = REVIEWED_AT,
        canonicalName = id,
        nodeKind = KnowledgeNodeKind.REASONING.name,
        granularity = KnowledgeNodeGranularity.ATOMIC.name,
        boundaryMarkdown = "只用于关系类型作用域的验证。",
        verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
    )

    private fun binding(knowledgeNodeId: String) = KnowledgeNodeSourceBindingSeedRecord(
        knowledgeNodeId = knowledgeNodeId,
        sourceId = SOURCE_ID,
        sourceLocator = "函数性质目录",
        derivationNote = "人工核验后录入。",
        reviewedAtEpochMillis = REVIEWED_AT,
    )

    private fun relation(from: String, to: String): KnowledgeNodeRelationRecord {
        val draft = KnowledgeNodeRelationRecord(
            relationId = "pending",
            subject = SUBJECT,
            prerequisiteKnowledgeNodeId = from,
            dependentKnowledgeNodeId = to,
            relationType = StudyDbValue.KnowledgeRelationType.PREREQUISITE_OF,
            sourceId = SOURCE_ID,
            sourceLocator = "函数性质目录",
            reviewedAtEpochMillis = REVIEWED_AT,
        )
        return draft.copy(relationId = KnowledgeNodeRelationContract.expectedId(draft))
    }

    private companion object {
        const val SUBJECT = "MATH"
        const val SOURCE_ID = "source:relation-scope:math"
        const val TOPIC_ID = "kb:relation-scope:math:topic:function"
        const val PREREQUISITE_ID = "kb:relation-scope:math:atomic:prerequisite"
        const val OTHER_ID = "kb:relation-scope:math:atomic:other"
        const val DEPENDENT_ID = "kb:relation-scope:math:atomic:dependent"
        const val TAXONOMY_VERSION = "relation-scope-v1"
        const val REVIEWED_AT = 1_000L
        const val PLANTED_RELATION_ID = "relation:relation-scope:planted-second-type"
        const val PLANTED_RELATION_TYPE = "PLANTED_SECOND_RELATION_TYPE"
    }
}
