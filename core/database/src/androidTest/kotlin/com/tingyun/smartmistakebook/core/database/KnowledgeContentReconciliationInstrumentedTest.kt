package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 内容调和：让库里的内容等于这份包。
 *
 * **它消灭的失败**：改前安装器只在"库里一行都没有"时导入，否则要求节点/来源/绑定/材料
 * 四样逐行完全相等，不等就抛——于是发布后哪怕只改一个节点名，知识库都会整包停摆、
 * 横幅常驻（KD-15：80 条材料的时间戳倒挂把另外一万条一起挡在门外）。
 *
 * 五条行为各钉一个具体失败：
 * 1. 首次全插入；2. 重跑幂等（否则每次启动都在写库）；3. 改名原地更新且 **id 不变**
 * （id 变了学生数据就成孤儿）；4. 移出包走**退役而非物删**（外键 RESTRICT + 掌握度 CASCADE，
 * 物删要么被挡要么静默带走学生数据）；5. 坏对象只跳过自己、不拖垮整批。
 *
 * 用文件库而不是内存库：退役要断言**行还在、status 变了**，而 status 没有暴露到端口层，
 * 只能直接读 sqlite_master 之外的那张表。
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeContentReconciliationInstrumentedTest {

    private fun open(name: String): Pair<StudyDatabasePort, String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "kb-reconcile-$name-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        return StudyDatabaseFactory.open(context, databaseName) to databaseName
    }

    /** NULL 必须原样保留为 null——`?: ""` 会把"没有取代目标"和"取代目标是空串"混为一谈。 */
    private fun readRaw(databaseName: String, sql: String): List<Map<String, String?>> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            database.rawQuery(sql, null).use { cursor ->
                val rows = mutableListOf<Map<String, String?>>()
                while (cursor.moveToNext()) {
                    val row = mutableMapOf<String, String?>()
                    for (i in 0 until cursor.columnCount) {
                        row[cursor.getColumnName(i)] = cursor.getString(i)
                    }
                    rows += row
                }
                return rows
            }
        }
    }

    @Test
    fun firstReconciliationInsertsAndSecondIsIdempotent() = runBlocking {
        val (store, name) = open("idempotent")
        try {
            val first = store.applyKnowledgeContentUpdate(command())
            assertEquals(2, first.nodesInserted)
            assertEquals(1, first.materialsInserted)
            assertEquals(0, first.nodesRetired)
            assertEquals(emptyList<String>(), first.skipped)

            // 重跑必须一条都不改：否则每次启动都在写库，且"是否收敛"无法判断
            val second = store.applyKnowledgeContentUpdate(command())
            assertEquals(0, second.nodesInserted)
            assertEquals(0, second.nodesUpdated)
            assertEquals(0, second.nodesRetired)
            assertEquals(0, second.materialsInserted)
            assertEquals(0, second.materialsUpdated)
            assertEquals(0, second.materialsRetired)
        } finally {
            store.close()
            ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
        }
    }

    @Test
    fun renamingANodeUpdatesInPlaceAndKeepsItsIdentity() = runBlocking {
        val (store, name) = open("rename")
        try {
            store.applyKnowledgeContentUpdate(command())
            val renamed = command(
                atomicName = "判断函数的单调性（改）",
            )
            val result = store.applyKnowledgeContentUpdate(renamed)

            assertEquals(1, result.nodesUpdated)
            assertEquals(0, result.nodesInserted)
            assertEquals(0, result.nodesRetired)
            // id 是身份：变了的话学生错题绑定/掌握度/复习队列全成孤儿
            assertEquals(
                "判断函数的单调性（改）",
                store.readKnowledgeNodesByIds(setOf(ATOMIC_ID)).single().displayName,
            )
            assertEquals(
                1,
                readRaw(name, "SELECT COUNT(*) AS c FROM knowledge_node WHERE knowledge_node_id = '$ATOMIC_ID'")
                    .single()["c"]!!.toInt(),
            )
        } finally {
            store.close()
            ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
        }
    }

    @Test
    fun aNodeMissingFromThePackIsRetiredRatherThanDeleted() = runBlocking {
        val (store, name) = open("retire")
        try {
            store.applyKnowledgeContentUpdate(command())
            // 新的一版没有那个原子点（等价于它被删/被并）
            val shrink = command(includeAtomic = false, supersededBy = null)
            val result = store.applyKnowledgeContentUpdate(shrink)

            assertEquals(1, result.nodesRetired)
            assertEquals(
                "行必须还在（外键 RESTRICT + 掌握度 CASCADE：物删要么被挡、要么静默带走学生数据）",
                1,
                readRaw(name, "SELECT COUNT(*) AS c FROM knowledge_node WHERE knowledge_node_id = '$ATOMIC_ID'")
                    .single()["c"]!!.toInt(),
            )
            val row = readRaw(
                name,
                "SELECT status, superseded_by FROM knowledge_node WHERE knowledge_node_id = '$ATOMIC_ID'",
            ).single()
            assertEquals("RETIRED", row["status"])
            assertNull("DELETE 没有唯一取代目标", row["superseded_by"])

            // 退役必须同时清检索特征：自愈逻辑靠"已索引数 ≥ 已审校数"判断补建，
            // 留着特征行会让两个计数永久漂移
            assertEquals(
                0,
                readRaw(name, "SELECT COUNT(*) AS c FROM knowledge_search_feature WHERE knowledge_node_id = '$ATOMIC_ID'")
                    .single()["c"]!!.toInt(),
            )
        } finally {
            store.close()
            ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
        }
    }

    @Test
    fun aMergedNodeCarriesItsSuccessorRedirect() = runBlocking {
        val (store, name) = open("merge")
        try {
            store.applyKnowledgeContentUpdate(command())
            store.applyKnowledgeContentUpdate(
                command(includeAtomic = false, supersededBy = TOPIC_ID),
            )
            val row = readRaw(
                name,
                "SELECT status, superseded_by FROM knowledge_node WHERE knowledge_node_id = '$ATOMIC_ID'",
            ).single()
            assertEquals("RETIRED", row["status"])
            assertEquals("合并是 1:1 重定向，历史界面据此显示新名", TOPIC_ID, row["superseded_by"])
        } finally {
            store.close()
            ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
        }
    }

    @Test
    fun aBrokenObjectIsSkippedWithoutBlockingTheRest() = runBlocking {
        val (store, _name) = open("skip")
        try {
            // 第二条材料的 reviewedAt 早于来源 importedAt —— 正是 KD-15 那个形态。
            // 整批校验下它会挡住全部；逐对象校验下只该挡住它自己。
            val broken = material(materialId = "kb-material:broken", reviewedAt = 500)
            val result = store.applyKnowledgeContentUpdate(
                command(extraMaterials = listOf(broken)),
            )

            assertEquals("好材料照常落地", 1, result.materialsInserted)
            assertEquals(1, result.skipped.size)
            assertTrue(
                "跳过原因要能看出是时间倒挂：${result.skipped}",
                result.skipped.single().contains("reviewedAtEpochMillis") ||
                    result.skipped.single().contains("predate"),
            )
            assertEquals(
                1,
                store.readKnowledgeTeachingMaterialsByIds(setOf("kb-material:good")).size,
            )
            assertEquals(0, store.readKnowledgeTeachingMaterialsByIds(setOf("kb-material:broken")).size)
        } finally {
            store.close()
        }
    }

    @Test
    fun aUserConfirmedNodeIsNeverDowngradedByReconciliation() = runBlocking {
        val (store, name) = open("confirmed")
        try {
            store.applyKnowledgeContentUpdate(command())
            // 人工把它提升为已确认（组织管道做过的事）
            readRaw(name, "SELECT 1").size // 触碰一次，确保前面已落盘
            SQLiteDatabase.openDatabase(
                ApplicationProvider.getApplicationContext<Context>().getDatabasePath(name).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    "UPDATE knowledge_node SET verification_status = 'USER_CONFIRMED' " +
                        "WHERE knowledge_node_id = '$ATOMIC_ID'",
                )
            }

            // 包里写的仍是 SOURCE_GROUNDED；调和只升不降
            store.applyKnowledgeContentUpdate(command())

            assertEquals(
                "USER_CONFIRMED",
                readRaw(
                    name,
                    "SELECT verification_status FROM knowledge_node WHERE knowledge_node_id = '$ATOMIC_ID'",
                ).single()["verification_status"],
            )
        } finally {
            store.close()
            ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
        }
    }

    // ---- 夹具 ----

    private fun command(
        atomicName: String = "判断函数单调性",
        includeAtomic: Boolean = true,
        supersededBy: String? = null,
        extraMaterials: List<KnowledgeTeachingMaterialRecord> = emptyList(),
    ): KnowledgeContentUpdateCommand {
        val nodes = buildList {
            add(topic())
            if (includeAtomic) add(atomic(atomicName))
        }
        return KnowledgeContentUpdateCommand(
            packId = TAXONOMY_VERSION,
            contentVersion = "test-version",
            nodes = nodes,
            sources = listOf(source()),
            nodeSourceBindings = nodes.map { binding(it.knowledgeNodeId) },
            relations = emptyList(),
            materials = listOf(material(materialId = "kb-material:good", reviewedAt = 1_000)) +
                extraMaterials,
            materialBindings = (listOf("kb-material:good") + extraMaterials.map { it.materialId })
                .filter { includeAtomic }
                .map {
                    KnowledgeTeachingMaterialNodeBindingRecord(
                        materialId = it,
                        knowledgeNodeId = ATOMIC_ID,
                        role = "PRIMARY",
                    )
                },
            nodeRetirements = if (includeAtomic) emptyMap() else mapOf(ATOMIC_ID to supersededBy),
            // 真实包里节点来源与材料来源是**两组**（taxonomy 的 `sources` 与侧车的
            // `teachingSources`）。夹具用同一个来源同时填两边，正是为了在快速用例里
            // 就能抓住"只补了一组、材料全被缺来源挡掉"这类错误——那是实测踩过的。
            teachingSources = listOf(source()),
        )
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
        stableCode = "$TAXONOMY_VERSION:math:topic:function",
        subject = "MATH",
        displayName = "函数",
        parentKnowledgeNodeId = null,
        taxonomyVersion = TAXONOMY_VERSION,
        createdAtEpochMillis = 1_000,
        canonicalName = "函数",
        nodeKind = KnowledgeNodeKind.TOPIC.name,
        granularity = KnowledgeNodeGranularity.TOPIC.name,
        verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
    )

    private fun atomic(name: String = "判断函数单调性") = KnowledgeNodeSeedRecord(
        knowledgeNodeId = ATOMIC_ID,
        stableCode = "$TAXONOMY_VERSION:math:atomic:monotonicity",
        subject = "MATH",
        displayName = name,
        parentKnowledgeNodeId = TOPIC_ID,
        taxonomyVersion = TAXONOMY_VERSION,
        createdAtEpochMillis = 1_000,
        canonicalName = name,
        nodeKind = KnowledgeNodeKind.REASONING.name,
        granularity = KnowledgeNodeGranularity.ATOMIC.name,
        boundaryMarkdown = "只判断给定函数在指定区间上的单调性。",
        verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
    )

    private fun material(materialId: String, reviewedAt: Long) = KnowledgeTeachingMaterialRecord(
        materialId = materialId,
        stableCode = "$TAXONOMY_VERSION:math:teaching:$materialId",
        subject = "MATH",
        materialType = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION.name,
        title = "单调性的判断",
        summaryMarkdown = "先看定义域，再比较区间内任意两点的函数值。",
        applicabilityMarkdown = "判断给定区间上单调性的题。",
        contentMarkdown = "1. 取定义域内的任意两点。\n2. 比较函数值大小。\n3. 得出单调性。",
        boundaryMarkdown = "只在给定区间上讨论，端点开闭需单独说明。",
        derivationKind = "REVIEWED_SYNTHESIS",
        sourceId = SOURCE_ID,
        sourceLocator = "函数目录·单调性",
        contentFingerprint = "PENDING",
        reviewedAtEpochMillis = reviewedAt,
    ).let { it.copy(contentFingerprint = KnowledgeTeachingMaterialFingerprint.expected(it)) }

    private fun binding(nodeId: String) = KnowledgeNodeSourceBindingSeedRecord(
        knowledgeNodeId = nodeId,
        sourceId = SOURCE_ID,
        sourceLocator = "函数目录·单调性",
        derivationNote = "人工核对原始材料后拆分。",
        reviewedAtEpochMillis = 1_000,
    )

    private companion object {
        const val TAXONOMY_VERSION = "moe-test-v1"
        const val SOURCE_ID = "source:research:math"
        const val TOPIC_ID = "kb:moe-test-v1:math:topic:function"
        const val ATOMIC_ID = "kb:moe-test-v1:math:atomic:monotonicity"
    }
}
