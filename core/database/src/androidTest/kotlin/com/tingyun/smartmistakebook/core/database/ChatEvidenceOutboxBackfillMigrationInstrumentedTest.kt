package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity
import com.tingyun.smartmistakebook.core.model.ChatEvidenceSubmitted
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v40 → v41 回填验证（spec model-intent-routing §5）：v40 时期写入的
 * chat evidence 没有 projection_outbox 行（只在整本重放时临时拼装）。
 * 迁移必须为孤儿证据按 learner 续接分配序列、写入可验证指纹的 outbox 行，
 * 并推进 learning_sequence，使迁移后的首次写入不与回填序列撞号。
 */
@RunWith(AndroidJUnit4::class)
class ChatEvidenceOutboxBackfillMigrationInstrumentedTest {

    @Test
    fun v40OrphanEvidenceBackfillsOutboxRowsAndContinuesAllocation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "chat-evidence-backfill-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromExportedSchema(context, dbName, version = 40)
            seedV40ChatEvidence(context, dbName, listOf("node-algebra", "node-geometry", "node-algebra"))

            val port = StudyDatabaseFactory.open(context, dbName)
            try {
                // 回填后 ledger 完整：三条孤儿证据成为序列 1..3 的一等事件。
                // status==COMPLETE 同时证明 outbox 指纹与证据载荷逐字一致。
                val ledger = port.loadLearningLedger("learner:local")
                assertEquals(LearningLedgerReadStatus.COMPLETE, ledger.status)
                val chatEvents = ledger.validPrefix.map { it.event }
                    .filterIsInstance<ChatEvidenceSubmitted>()
                assertEquals(3, chatEvents.size)
                assertEquals(listOf(1L, 2L, 3L), chatEvents.map { it.eventSequence })

                // learning_sequence 已推进：迁移后的首次写入接续序列 4，
                // 不与回填序列撞号（撞号会让 ledger 走出 GAP）。
                port.recordChatEvidence(
                    listOf(
                        LearnerChatEvidenceEntity(
                            evidence_id = "ev-post-migration",
                            learner_id = "learner:local",
                            conversation_id = "conv-test",
                            knowledge_node_id = "node-algebra",
                            direction = "POSITIVE",
                            weight = 0.18,
                            reason_markdown = "迁移后写入",
                            confidence = 0.9,
                            source_kind = "MODEL_CHAT",
                            created_at_epoch_millis = 9000L,
                        ),
                    ),
                )
                val afterWrite = port.loadLearningLedger("learner:local")
                assertEquals(LearningLedgerReadStatus.COMPLETE, afterWrite.status)
                assertEquals(4, afterWrite.validPrefix.size)
                assertEquals(4L, afterWrite.validPrefix.last().event.eventSequence)
            } finally {
                port.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun v40FreshDatabaseMigratesWithoutBackfillRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "chat-evidence-backfill-empty-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromExportedSchema(context, dbName, version = 40)
            val port = StudyDatabaseFactory.open(context, dbName)
            try {
                val ledger = port.loadLearningLedger("learner:local")
                assertEquals(LearningLedgerReadStatus.COMPLETE, ledger.status)
                assertEquals(0, ledger.validPrefix.size)
            } finally {
                port.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    private fun seedV40ChatEvidence(
        context: Context,
        dbName: String,
        knowledgeNodeIds: List<String>,
    ) {
        val database = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        try {
            knowledgeNodeIds.forEachIndexed { index, nodeId ->
                database.execSQL(
                    "INSERT INTO learner_chat_evidence (" +
                        "evidence_id, learner_id, conversation_id, knowledge_node_id, direction, " +
                        "weight, reason_markdown, confidence, source_kind, created_at_epoch_millis" +
                        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        "ev-${index + 1}",
                        "learner:local",
                        "conv-test",
                        nodeId,
                        if (index == 1) "NEGATIVE" else "POSITIVE",
                        if (index == 1) 0.35 else 0.18,
                        "v40 时期写入的证据 $index",
                        0.9,
                        "MODEL_CHAT",
                        1000L + index,
                    ),
                )
            }
        } finally {
            database.close()
        }
    }
}
