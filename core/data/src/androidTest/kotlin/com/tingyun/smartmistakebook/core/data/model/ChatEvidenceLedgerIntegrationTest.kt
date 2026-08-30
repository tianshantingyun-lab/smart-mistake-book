package com.tingyun.smartmistakebook.core.data.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity
import com.tingyun.smartmistakebook.core.domain.LearningProjector
import com.tingyun.smartmistakebook.core.model.ChatEvidenceSubmitted
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 投影器读源整合端到端测试（spec model-intent-routing §5）：
 * 验证 learner_chat_evidence 表的记录经 loadLearningLedger 转换为
 * ChatEvidenceSubmitted 事件后，投影器 replay 消费并积分到
 * KnowledgeMasteryState（封顶 weight 进 POSITIVE/NEGATIVE_LEARNING_RATE 公式）。
 */
@RunWith(AndroidJUnit4::class)
class ChatEvidenceLedgerIntegrationTest {

    private fun entry(
        nodeId: String,
        direction: String,
        weight: Double,
        seq: Int,
    ) = LearnerChatEvidenceEntity(
        evidence_id = "ev-$seq",
        learner_id = "learner:local",
        conversation_id = "conv-test",
        knowledge_node_id = nodeId,
        direction = direction,
        weight = weight,
        reason_markdown = "测试理由 $seq",
        confidence = 0.9,
        source_kind = "MODEL_CHAT",
        created_at_epoch_millis = 1000L + seq,
    )

    @Test
    fun chatEvidenceEventsAppearInLedgerAndAffectMasteryScore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "chat-evidence-ledger-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        val db = StudyDatabaseFactory.open(context, dbName)
        try {
            val entries = listOf(
                entry("node-algebra", "POSITIVE", 0.18, 1),
                entry("node-algebra", "POSITIVE", 0.18, 2),
                entry("node-geometry", "NEGATIVE", 0.35, 3),
            )
            db.chatEvidenceDao().insertAll(entries)

            val ledger = db.loadLearningLedger("learner:local")
            assertEquals(
                com.tingyun.smartmistakebook.core.database.LearningLedgerReadStatus.COMPLETE,
                ledger.status,
            )
            val chatEvents = ledger.validPrefix.map { it.event }
                .filterIsInstance<ChatEvidenceSubmitted>()
            assertEquals(3, chatEvents.size)
            assertEquals("node-algebra", chatEvents[0].knowledgeNodeId)
            assertEquals(LearningEvidenceDirection.POSITIVE, chatEvents[0].direction)

            val ledgerEvents = ledger.validPrefix.map { it.event }
            val projector = LearningProjector()
            val result = projector.replay(learnerId = "learner:local", ledger = ledgerEvents)
            val algebraMastery = result.snapshot.knowledgeMasteryStates["node-algebra"]
            val geometryMastery = result.snapshot.knowledgeMasteryStates["node-geometry"]

            assertNotNull(algebraMastery)
            assertNotNull(geometryMastery)
            assertTrue(algebraMastery!!.masteryScore > 0.0)
            assertTrue(geometryMastery!!.masteryScore < 0.5)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun chatEvidenceEmptyLearnerReturnsEmptyLedger() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "chat-evidence-empty-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        val db = StudyDatabaseFactory.open(context, dbName)
        try {
            val ledger = db.loadLearningLedger("learner:nonexistent")
            assertTrue(ledger.validPrefix.isEmpty())
        } finally {
            db.close()
        }
    }
}
