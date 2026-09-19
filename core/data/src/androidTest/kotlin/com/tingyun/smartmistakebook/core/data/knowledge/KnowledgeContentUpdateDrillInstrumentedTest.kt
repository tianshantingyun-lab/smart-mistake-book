package com.tingyun.smartmistakebook.core.data.knowledge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.KnowledgeContentUpdateCommand
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeRelationRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialFingerprint
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialNodeBindingRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **端到端演练**：一版差分包落到真实的随包内容上，逐类动作各一例，断言学生数据一字未动。
 *
 * 这是整个更新机制唯一能证明"学生数据不受损"的验证——前五步的单元与夹具用例证明的是机制
 * 各部件的行为，**这一条证明它在真实内容规模上组合起来仍然成立**。
 *
 * 覆盖动作类（设计文档 §2 的枚举）：改（改名，id 不变）· 增（新节点）· 删（无取代目标）
 * · 并（有取代目标）· 改材料 · 改绑定 · 加前置边 · 以及**一条坏对象只跳过自己不拖垮整批**
 * （KD-15 那一类）。
 *
 * 学生数据选 `learner_knowledge_mastery_state`：它是**最会被静默带走**的一类——外键是
 * RESTRICT（挡住物删），而姐妹表 `knowledge_mastery_state` 是 CASCADE。掌握度一旦丢失，
 * 排程会从"学过"退回"没学过"，**且没有任何报错**。
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeContentUpdateDrillInstrumentedTest {

    @Test
    fun aDifferencePackUpdatesEveryActionClassWithoutTouchingStudentData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "kb-update-drill-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val store = StudyDatabaseFactory.open(context, databaseName)
        try {
            BundledKnowledgeBaseInstaller.install(store)
            val pack = BundledKnowledgePackResources.load().single { it.packId == BUNDLED_PACK_ID }

            val atomics = pack.nodes.filter {
                it.granularity == KnowledgeNodeGranularity.ATOMIC.name &&
                    it.verificationStatus == KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name
            }
            val renamed = atomics[0]
            val merged = atomics[1]
            val deleted = atomics[2]
            val control = atomics[3]
            val survivor = atomics[4]

            // 从真实材料里挑三条（绑定与材料是**两张表**，`KnowledgeTeachingMaterialRecord`
            // 不带 bindings，所以先按绑定找、再回落到材料）：
            // 一条改内容、一条改绑定目标——两者都必须绑在**保留下来的**节点上，
            // 否则它会因为绑定目标消失而被正确退役，改标题就无从谈起；
            // 再加一条"绑定目标全被丢弃"的，它应当被退役（影响面闭合那一格）。
            val dropped = setOf(merged.knowledgeNodeId, deleted.knowledgeNodeId)
            val bindingsByMaterial = pack.teachingMaterialBindings.groupBy { it.materialId }
            val keptBindings = pack.teachingMaterialBindings.filter { it.knowledgeNodeId !in dropped }
            val editedBinding = keptBindings.first { it.knowledgeNodeId == control.knowledgeNodeId }
            val edited = pack.teachingMaterials.first { it.materialId == editedBinding.materialId }
            val reboundBinding = keptBindings.first {
                it.materialId != edited.materialId && it.knowledgeNodeId != control.knowledgeNodeId
            }
            val rebound = pack.teachingMaterials.first { it.materialId == reboundBinding.materialId }
            val reboundOriginalNodeId = reboundBinding.knowledgeNodeId
            val orphaned = pack.teachingMaterials.first { m ->
                bindingsByMaterial[m.materialId].orEmpty().isNotEmpty() &&
                    bindingsByMaterial.getValue(m.materialId).all { it.knowledgeNodeId in dropped }
            }

            seedStudentMastery(context, databaseName, listOf(renamed, merged, deleted, control))
            val masteryBefore = countRows(
                context, databaseName,
                "SELECT COUNT(*) AS c FROM learner_knowledge_mastery_state WHERE learner_id = '$LEARNER_ID'",
            )

            val result = runCatching {
                store.applyKnowledgeContentUpdate(
                    differenceCommand(pack, renamed, merged, deleted, control, survivor, edited, rebound, reboundOriginalNodeId),
                )
            }.getOrElse { failure ->
                // 演练失败必须可诊断：把这次用到的 id 与父级全打出来，
                // 否则只看到一个 FOREIGN KEY constraint failed，无从下手。
                throw AssertionError(
                    "差分包调和失败。新节点父级=${renamed.parentKnowledgeNodeId}；" +
                        "renamed=${renamed.knowledgeNodeId}；merged=${merged.knowledgeNodeId}；" +
                        "deleted=${deleted.knowledgeNodeId}；control=${control.knowledgeNodeId}；" +
                        "survivor=${survivor.knowledgeNodeId}；" +
                        "新节点父级在包内=${pack.nodes.any { it.knowledgeNodeId == renamed.parentKnowledgeNodeId }}",
                    failure,
                )
            }

            // ---- 内容侧：逐类动作都生效 ----
            assertEquals("改名 1 条更新", 1, result.nodesUpdated)
            assertEquals("新节点插入 1", 1, result.nodesInserted)
            assertEquals("被并与被删各 1", 2, result.nodesRetired)
            // 演练**只断言自己这几类动作**。随包内容里已存在被逐条校验挡掉的对象
            // （实测 25 个节点的别名超过 32 上限），那是内容侧的问题，由
            // BundledContentReconciliationInstrumentedTest 负责报告——演练替它背这个锅
            // 只会让"演练失败"指向错误的地方。
            val badMaterialSkip = result.skipped.filter { it.contains(BAD_MATERIAL_ID) }
            assertEquals("演练那条坏材料必须被跳过：${result.skipped}", 1, badMaterialSkip.size)
            assertTrue("跳过原因要能看出是时间倒挂", badMaterialSkip.single().contains("predate"))
            assertTrue(
                "演练自己新增的节点不该被跳过：${result.skipped}",
                result.skipped.none { it.contains(NEW_NODE_ID) },
            )

            val nodeRows = readRows(
                context, databaseName,
                "SELECT knowledge_node_id, display_name, status, superseded_by FROM knowledge_node",
            ).associateBy { it["knowledge_node_id"]!! }

            assertEquals(
                "改名不换身份：同一个 id 换名字",
                RENAMED_NAME,
                nodeRows.getValue(renamed.knowledgeNodeId)["display_name"],
            )
            assertEquals(
                "新节点必须真的落库",
                "ACTIVE",
                nodeRows.getValue(NEW_NODE_ID)["status"],
            )
            assertEquals(
                "合并：退役 + 1:1 重定向",
                "RETIRED",
                nodeRows.getValue(merged.knowledgeNodeId)["status"],
            )
            assertEquals(
                survivor.knowledgeNodeId,
                nodeRows.getValue(merged.knowledgeNodeId)["superseded_by"],
            )
            assertEquals(
                "删除：退役，无取代目标",
                "RETIRED",
                nodeRows.getValue(deleted.knowledgeNodeId)["status"],
            )
            assertNull(
                "删除没有唯一目标，历史界面据此显示旧名",
                nodeRows.getValue(deleted.knowledgeNodeId)["superseded_by"],
            )

            assertEquals(
                "改材料：原地更新（material_id 与内容无关，所以是同一条被更新而非新增）",
                CHANGED_TITLE,
                readRows(
                    context, databaseName,
                    "SELECT title FROM knowledge_teaching_material WHERE material_id = '${edited.materialId}'",
                ).single()["title"],
            )
            assertEquals(
                "改绑定：新目标的绑定在",
                1,
                countRows(
                    context, databaseName,
                    "SELECT COUNT(*) AS c FROM knowledge_teaching_material_node_binding " +
                        "WHERE material_id = '${rebound.materialId}' AND knowledge_node_id = '${control.knowledgeNodeId}'",
                ),
            )
            assertEquals(
                "改绑定：旧目标的绑定必须已清掉，否则留下指向别处的悬空行",
                0,
                countRows(
                    context, databaseName,
                    "SELECT COUNT(*) AS c FROM knowledge_teaching_material_node_binding " +
                        "WHERE material_id = '${rebound.materialId}' AND knowledge_node_id = '$reboundOriginalNodeId'",
                ),
            )
            assertEquals(
                "加前置边：新边落地",
                1,
                countRows(
                    context, databaseName,
                    "SELECT COUNT(*) AS c FROM knowledge_node_relation WHERE relation_id = '$NEW_RELATION_ID'",
                ),
            )
            assertEquals(
                "坏材料一条都不该落库（它连来源都过不了校验）",
                0,
                countRows(
                    context, databaseName,
                    "SELECT COUNT(*) AS c FROM knowledge_teaching_material WHERE material_id = '$BAD_MATERIAL_ID'",
                ),
            )
            assertEquals(
                "绑定目标全被丢弃的材料必须退役：它的绑定已被整体替换清空，留着就是一条状态为" +
                    "ACTIVE 却谁也到不了的行",
                "RETIRED",
                readRows(
                    context, databaseName,
                    "SELECT status FROM knowledge_teaching_material WHERE material_id = '${orphaned.materialId}'",
                ).single()["status"],
            )

            // ---- 学生侧：一字未动 ----
            assertEquals(
                "学生掌握度行数必须完全不变——本次演练的核心断言",
                masteryBefore,
                countRows(
                    context, databaseName,
                    "SELECT COUNT(*) AS c FROM learner_knowledge_mastery_state WHERE learner_id = '$LEARNER_ID'",
                ),
            )
            for (node in listOf(renamed, merged, deleted, control)) {
                assertEquals(
                    "「${node.displayName}」上的学生掌握度必须在——退役不物删的意义就在这里",
                    1,
                    countRows(
                        context, databaseName,
                        "SELECT COUNT(*) AS c FROM learner_knowledge_mastery_state " +
                            "WHERE learner_id = '$LEARNER_ID' AND knowledge_node_id = '${node.knowledgeNodeId}'",
                    ),
                )
            }
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    /** 差分包：从**真实包**派生，只改演练需要的那几处。 */
    private fun differenceCommand(
        pack: KnowledgeBasePack,
        renamed: KnowledgeNodeSeedRecord,
        merged: KnowledgeNodeSeedRecord,
        deleted: KnowledgeNodeSeedRecord,
        control: KnowledgeNodeSeedRecord,
        survivor: KnowledgeNodeSeedRecord,
        edited: KnowledgeTeachingMaterialRecord,
        rebound: KnowledgeTeachingMaterialRecord,
        reboundOriginalNodeId: String,
    ): KnowledgeContentUpdateCommand {
        val dropped = setOf(merged.knowledgeNodeId, deleted.knowledgeNodeId)

        fun resigned(record: KnowledgeTeachingMaterialRecord, title: String, reviewedAt: Long) =
            record.copy(title = title, reviewedAtEpochMillis = reviewedAt)
                .let { it.copy(contentFingerprint = KnowledgeTeachingMaterialFingerprint.expected(it)) }

        val badMaterial = resigned(edited, "演练用的坏材料", reviewedAt = 1L)
            .copy(materialId = BAD_MATERIAL_ID, stableCode = "$BAD_MATERIAL_ID:stable")
            .let { it.copy(contentFingerprint = KnowledgeTeachingMaterialFingerprint.expected(it)) }

        return KnowledgeContentUpdateCommand(
            packId = BUNDLED_PACK_ID,
            contentVersion = "drill",
            nodes = pack.nodes
                .filterNot { it.knowledgeNodeId in dropped }
                .map { if (it.knowledgeNodeId == renamed.knowledgeNodeId) it.copy(displayName = RENAMED_NAME) else it } +
                renamed.copy(
                    knowledgeNodeId = NEW_NODE_ID,
                    stableCode = "$NEW_NODE_ID:stable",
                    displayName = "演练新增节点",
                    canonicalName = "演练新增节点",
                ),
            sources = pack.sources,
            nodeSourceBindings = pack.bindings.filterNot { it.knowledgeNodeId in dropped },
            relations = pack.relations.filterNot {
                it.prerequisiteKnowledgeNodeId in dropped || it.dependentKnowledgeNodeId in dropped
            } + KnowledgeNodeRelationRecord(
                relationId = NEW_RELATION_ID,
                subject = survivor.subject,
                prerequisiteKnowledgeNodeId = survivor.knowledgeNodeId,
                dependentKnowledgeNodeId = control.knowledgeNodeId,
                relationType = "PREREQUISITE_OF",
                sourceId = pack.sources.first().sourceId,
                sourceLocator = "演练新增",
                reviewedAtEpochMillis = 1_000,
            ),
            materials = pack.teachingMaterials.map { m ->
                if (m.materialId == edited.materialId) {
                    resigned(m, CHANGED_TITLE, m.reviewedAtEpochMillis)
                } else {
                    m
                }
            } + badMaterial,
            materialBindings = pack.teachingMaterialBindings.map { b ->
                if (b.materialId == rebound.materialId && b.knowledgeNodeId == reboundOriginalNodeId) {
                    b.copy(knowledgeNodeId = control.knowledgeNodeId)
                } else {
                    b
                }
            } + KnowledgeTeachingMaterialNodeBindingRecord(
                materialId = BAD_MATERIAL_ID,
                knowledgeNodeId = renamed.knowledgeNodeId,
                role = "PRIMARY",
            ),
            nodeRetirements = mapOf(
                merged.knowledgeNodeId to survivor.knowledgeNodeId,
                deleted.knowledgeNodeId to null,
            ),
            teachingSources = pack.teachingSources,
        )
    }

    // ---- 学生数据的种与查 ----
    //
    // 真实的学习事实由账本重放产生；这里只需要"它们存在"，所以直接写 SQL，
    // 不去铺整条导入/组织链路。

    private fun seedStudentMastery(
        context: Context,
        databaseName: String,
        nodes: List<KnowledgeNodeSeedRecord>,
    ) = writable(context, databaseName).use { db ->
        db.execSQL(
            "INSERT OR IGNORE INTO learner_projection_snapshot " +
                "(projection_name, learner_id, state_version, checkpoint_sequence, " +
                "known_ledger_head_sequence, projector_version, projected_at_epoch_millis, " +
                "generated_at_epoch_millis, freshness, projection_status) " +
                "VALUES ('study-experience-v1', '$LEARNER_ID', 1, 0, 0, 'v', 1, 1, 'CURRENT', 'CURRENT')",
        )
        nodes.forEachIndexed { index, node ->
            db.execSQL(
                "INSERT INTO learner_knowledge_mastery_state " +
                    "(projection_name, learner_id, knowledge_node_id, " +
                    "probability_independent_correct, lower_bound_independent_correct, evidence_mass, " +
                    "status, calibration_support, projector_version, checkpoint_sequence) " +
                    "VALUES ('study-experience-v1', '$LEARNER_ID', '${node.knowledgeNodeId}', " +
                    "0.8, 0.7, ${index + 1}.0, 'LEARNING', 'SUPPORTED', 'v', 1)",
            )
        }
    }

    private fun countRows(context: Context, databaseName: String, sql: String): Int =
        readRows(context, databaseName, sql).single().values.first()!!.toInt()

    private fun readRows(
        context: Context,
        databaseName: String,
        sql: String,
    ): List<Map<String, String?>> = readable(context, databaseName).use { db ->
        db.rawQuery(sql, null).use { cursor ->
            val rows = mutableListOf<Map<String, String?>>()
            while (cursor.moveToNext()) {
                val row = mutableMapOf<String, String?>()
                for (i in 0 until cursor.columnCount) row[cursor.getColumnName(i)] = cursor.getString(i)
                rows += row
            }
            rows
        }
    }

    private fun readable(context: Context, databaseName: String) = SQLiteDatabase.openDatabase(
        context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY,
    )

    private fun writable(context: Context, databaseName: String) = SQLiteDatabase.openDatabase(
        context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE,
    )

    private companion object {
        const val BUNDLED_PACK_ID = "moe-2025-four-subjects-v1"
        const val LEARNER_ID = "drill-learner"
        const val RENAMED_NAME = "演练改名后的知识点"
        const val CHANGED_TITLE = "演练改过的材料标题"
        const val NEW_NODE_ID = "kb:moe-2025-four-subjects-v1:math:atomic:drill-new-node"
        const val NEW_RELATION_ID = "kb-relation:drill-new"
        const val BAD_MATERIAL_ID = "kb-material:drill-bad"
    }
}
