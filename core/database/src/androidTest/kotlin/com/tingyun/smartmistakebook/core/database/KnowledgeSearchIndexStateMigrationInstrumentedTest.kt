package com.tingyun.smartmistakebook.core.database

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 别名倒排索引版本锚点（v50→51，`knowledge_search_index_state`）的真机验证。
 *
 * 钉住两条语义：
 * 1. 迁移只建表、不回填——v50 库升级后锚点缺失 = legacy，首次召回必须触发整科重建；
 * 2. 重建是**换血**不是补建：旧规则的特征行与漂移行被清掉，换成当前
 *    [KnowledgeSearchFeatureExtractor.fromNode]（当前规则 = v1 截断，INDEX_VERSION=1；
 *    v2 去截断实验回滚后本用例同样钉住"锚点不等即换血"）的输出，锚点最后推进到
 *    [KnowledgeSearchFeatureExtractor.INDEX_VERSION]，第二次召回不再重建。
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeSearchIndexStateMigrationInstrumentedTest {
    @Test
    fun v50UpgradeCreatesEmptyAnchorTableAndRebuildTruncatedIndexOnFirstRecall() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "knowledge-index-state-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            // ---- 建一个 v50 库，塞一个"旧版截断索引"形态的数据 ----
            createDatabaseFromExportedSchema(context, databaseName, version = 50)
            val node = record()
            val fullFeatures = KnowledgeSearchFeatureExtractor.fromNode(node)
            val truncatedFeatures = KnowledgeSearchFeatureExtractor.fromNode(
                node.copy(boundaryMarkdown = null),
            )
            assertTrue(
                "test fixture must be a strict truncation (boundary dropped)",
                truncatedFeatures.all(fullFeatures::contains) && truncatedFeatures != fullFeatures,
            )
            SQLiteDatabase.openOrCreateDatabase(
                context.getDatabasePath(databaseName),
                null,
            ).use { database ->
                database.execSQL(
                    "INSERT INTO `knowledge_node` (" +
                        "`knowledge_node_id`, `stable_code`, `subject`, `display_name`, " +
                        "`canonical_name`, `node_kind`, `granularity`, `aliases_text`, " +
                        "`boundary_markdown`, `verification_status`, `parent_knowledge_node_id`, " +
                        "`taxonomy_version`, `created_at_epoch_millis`, `status`, `superseded_by`) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        node.knowledgeNodeId,
                        node.stableCode,
                        node.subject,
                        node.displayName,
                        node.canonicalName,
                        node.nodeKind,
                        node.granularity,
                        node.aliases.sorted().joinToString("\u001F"),
                        node.boundaryMarkdown,
                        node.verificationStatus,
                        node.parentKnowledgeNodeId,
                        node.taxonomyVersion,
                        node.createdAtEpochMillis,
                        "ACTIVE",
                        null,
                    ),
                )
                truncatedFeatures.forEach { feature ->
                    database.execSQL(
                        "INSERT OR IGNORE INTO `knowledge_search_feature` " +
                            "(`subject`, `search_feature`, `knowledge_node_id`) VALUES (?, ?, ?)",
                        arrayOf(node.subject, feature, node.knowledgeNodeId),
                    )
                }
                // 一条漂移行：旧规则建不出来的特征。重建必须把它清掉——
                // 若走的是"只补缺"路径，它会永久留在索引里。
                database.execSQL(
                    "INSERT INTO `knowledge_search_feature` " +
                        "(`subject`, `search_feature`, `knowledge_node_id`) VALUES (?, ?, ?)",
                    arrayOf(node.subject, "zzz-漂移特征", node.knowledgeNodeId),
                )
            }

            // ---- 升级到 v51：锚点表存在且为空（不回填 = 不谎称旧行是新版建的）----
            val store = StudyDatabaseFactory.open(context, databaseName)
            try {
                assertEquals(
                    "upgrade must land on the current schema version",
                    STUDY_DATABASE_VERSION,
                    store.readDatabaseVersion(),
                )
                assertRaw(context, databaseName) { database ->
                    database.rawQuery(
                        "SELECT COUNT(*) FROM knowledge_search_index_state",
                        null,
                    ).use { cursor ->
                        cursor.moveToNext()
                        assertEquals("anchor table must not be backfilled", 0, cursor.getInt(0))
                    }
                    database.rawQuery(
                        "SELECT COUNT(*) FROM knowledge_search_feature WHERE search_feature = 'zzz-漂移特征'",
                        null,
                    ).use { cursor ->
                        cursor.moveToNext()
                        assertEquals("legacy stray row still present before first recall", 1, cursor.getInt(0))
                    }
                }

                // ---- 首次召回：版本门触发整科重建 ----
                val recalled = store.readSubjectKnowledgeRecallCandidates(
                    subject = node.subject,
                    searchFeatures = KnowledgeSearchFeatureExtractor.fromQuestion(
                        "根据导数符号判断函数的单调递增区间",
                    ),
                    limit = 10,
                )
                assertTrue(
                    "node must be recalled after rebuild",
                    recalled.map(KnowledgeNodeSeedRecord::knowledgeNodeId).contains(node.knowledgeNodeId),
                )
                assertRaw(context, databaseName) { database ->
                    val featureCount = database.rawQuery(
                        "SELECT COUNT(*) FROM knowledge_search_feature " +
                            "WHERE knowledge_node_id = ?",
                        arrayOf(node.knowledgeNodeId),
                    ).use { cursor -> cursor.moveToNext(); cursor.getInt(0) }
                    assertEquals(
                        "rebuild must replace the truncated index with the full feature set " +
                            "(truncated=${truncatedFeatures.size}, full=${fullFeatures.size})",
                        fullFeatures.size,
                        featureCount,
                    )
                    database.rawQuery(
                        "SELECT COUNT(*) FROM knowledge_search_feature WHERE search_feature = 'zzz-漂移特征'",
                        null,
                    ).use { cursor ->
                        cursor.moveToNext()
                        assertEquals(
                            "rebuild is a full replacement: stray legacy row must be gone",
                            0,
                            cursor.getInt(0),
                        )
                    }
                    database.rawQuery(
                        "SELECT index_version FROM knowledge_search_index_state WHERE subject = ?",
                        arrayOf(node.subject),
                    ).use { cursor ->
                        cursor.moveToNext()
                        assertEquals(
                            "anchor must advance to the current extractor version",
                            KnowledgeSearchFeatureExtractor.INDEX_VERSION,
                            cursor.getInt(0),
                        )
                    }
                }

                // ---- 第二次召回：锚点已当前，不再重建（行数稳定、无副作用）----
                val recalledAgain = store.readSubjectKnowledgeRecallCandidates(
                    subject = node.subject,
                    searchFeatures = KnowledgeSearchFeatureExtractor.fromQuestion(
                        "根据导数符号判断函数的单调递增区间",
                    ),
                    limit = 10,
                )
                assertEquals(recalled, recalledAgain)
                assertRaw(context, databaseName) { database ->
                    val featureCount = database.rawQuery(
                        "SELECT COUNT(*) FROM knowledge_search_feature",
                        null,
                    ).use { cursor -> cursor.moveToNext(); cursor.getInt(0) }
                    assertEquals(
                        "no second rebuild: row count must stay at the full-set size",
                        fullFeatures.size,
                        featureCount,
                    )
                }
            } finally {
                store.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun assertRaw(
        context: android.content.Context,
        databaseName: String,
        block: (SQLiteDatabase) -> Unit,
    ) = SQLiteDatabase.openDatabase(
        context.getDatabasePath(databaseName).path,
        null,
        SQLiteDatabase.OPEN_READWRITE,
    ).use(block)

    private fun record() = KnowledgeNodeSeedRecord(
        knowledgeNodeId = "kb:index-state:math:atomic:monotonicity",
        stableCode = "index-state:math:atomic:monotonicity",
        subject = "MATH",
        displayName = "判断函数单调递增区间",
        parentKnowledgeNodeId = null,
        taxonomyVersion = "index-state-v1",
        createdAtEpochMillis = 1,
        canonicalName = "根据导数符号判断函数单调递增区间",
        nodeKind = KnowledgeNodeKind.REASONING.name,
        granularity = KnowledgeNodeGranularity.ATOMIC.name,
        aliases = setOf("单调区间", "导数与单调性", "增减区间"),
        boundaryMarkdown = "只用于检索索引版本回归的边界说明。",
        verificationStatus = "SOURCE_GROUNDED",
    )
}
