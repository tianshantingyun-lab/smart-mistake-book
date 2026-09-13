package com.tingyun.smartmistakebook.core.data.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.LearningLedgerReadStatus
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity
import com.tingyun.smartmistakebook.core.domain.LearningProjector
import com.tingyun.smartmistakebook.core.model.ChatEvidenceSubmitted
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 投影器读源整合端到端测试（spec model-intent-routing §5）：chat evidence 经
 * recordChatEvidence 写入（同事务分配序列 + projection_outbox 行）后，
 * 全量 loadLearningLedger 与增量 loadProjectionBatch 两条回放路径都能看到
 * ChatEvidenceSubmitted 事件，投影器把封顶 weight 积分进掌握度。
 * status==COMPLETE 同时证明 outbox 指纹与证据载荷一致（读侧会重算比对）。
 */
@RunWith(AndroidJUnit4::class)
class ChatEvidenceLedgerIntegrationTest {

    private fun entry(
        nodeId: String,
        direction: String,
        weight: Double,
        seq: Int,
        rejectedReason: String? = null,
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
        rejected_reason = rejectedReason,
        rejected_at_epoch_millis = rejectedReason?.let { 1000L + seq },
    )

    @Test
    fun chatEvidenceEventsAppearInLedgerAndAffectMasteryScore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "chat-evidence-ledger-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        val db = StudyDatabaseFactory.open(context, dbName)
        try {
            db.recordChatEvidence(
                listOf(
                    entry("node-algebra", "POSITIVE", 0.18, 1),
                    entry("node-algebra", "POSITIVE", 0.18, 2),
                    entry("node-geometry", "NEGATIVE", 0.35, 3),
                ),
            )

            val ledger = db.loadLearningLedger("learner:local")
            assertEquals(LearningLedgerReadStatus.COMPLETE, ledger.status)
            val chatEvents = ledger.validPrefix.map { it.event }
                .filterIsInstance<ChatEvidenceSubmitted>()
            assertEquals(3, chatEvents.size)
            assertEquals(listOf(1L, 2L, 3L), chatEvents.map { it.eventSequence })
            assertEquals("node-algebra", chatEvents[0].knowledgeNodeId)
            assertEquals(LearningEvidenceDirection.POSITIVE, chatEvents[0].direction)

            val projector = LearningProjector()
            val result = projector.replay(
                learnerId = "learner:local",
                ledger = ledger.validPrefix.map { it.event },
            )
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
    fun chatEvidenceReachesIncrementalProjectionBatchAndAdvancesCheckpoint() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "chat-evidence-batch-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        val db = StudyDatabaseFactory.open(context, dbName)
        try {
            db.recordChatEvidence(
                listOf(
                    entry("node-algebra", "POSITIVE", 0.18, 1),
                    entry("node-geometry", "NEGATIVE", 0.35, 2),
                ),
            )

            val batch = db.loadProjectionBatch(
                projectionName = "study-experience-v1",
                learnerId = "learner:local",
                limit = 100,
            )
            assertEquals(
                com.tingyun.smartmistakebook.core.database.ProjectionBatchStopReason.END_OF_LEDGER,
                batch.stopReason,
            )
            val chatEvents = batch.events.map { it.event }.filterIsInstance<ChatEvidenceSubmitted>()
            assertEquals(2, chatEvents.size)

            val result = LearningProjector().project(
                previous = LearnerSnapshot.empty(
                    learnerId = "learner:local",
                    projectorVersion = LearningProjector.VERSION,
                ),
                events = batch.events.map { it.event },
                knownLedgerHeadSequence = batch.ledgerHeadSequence,
                authoritativePresentationStates = batch.authoritativePresentationStates,
            )
            assertEquals(2L, result.snapshot.checkpoint.lastSequence)
            assertNotNull(result.snapshot.knowledgeMasteryStates["node-algebra"])
            assertNotNull(result.snapshot.knowledgeMasteryStates["node-geometry"])
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun retryingTheSameEvidenceIdIsANoOpThatDoesNotConsumeALedgerSequence() = runBlocking {
        // 幂等（审计 S-1）：evidence_id 是**确定性幂等键**——模型工具环的重试与多轮
        // 共用同一个 requestId 命名空间（RoomModelTaskRepository:691 传
        // evidenceIdNamespace = requestId，RoomTutorToolRunner:290-296 派生 id），
        // 所以同一条证据会被用同一个 id 重发。重发必须**静默 no-op**。
        //
        // 这条测试的形状刻意不是"重复写入不抛异常"——那只证明最浅的一层。
        // 真正要钉住的是**序列号没有被吃掉**：学习序列是账本连续性的凭证，
        // 分配了却没有对应的不可变事件行，读侧的严格 GAP 检测会把整本账判为损坏
        // （ProjectionTransactionDao.loadLearningLedger）。所以下面同时断言
        // 账本状态 COMPLETE、事件序列恰为 [1]、以及**下一条新证据拿到 2**。
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "chat-evidence-idempotent-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        val db = StudyDatabaseFactory.open(context, dbName)
        try {
            val first = entry("node-algebra", "POSITIVE", 0.18, 1)
            db.recordChatEvidence(listOf(first))

            // 同一条证据、同一个 id 重发一次。
            db.recordChatEvidence(listOf(first))

            val ledger = db.loadLearningLedger("learner:local")
            assertEquals(
                "重发之后账本必须仍然完整——留下空号就等于把「重试」变成了「账本永久损坏」",
                LearningLedgerReadStatus.COMPLETE,
                ledger.status,
            )
            assertEquals(1, db.readChatEvidenceByConversation("conv-test").size)
            val chatEvents = ledger.validPrefix.map { it.event }
                .filterIsInstance<ChatEvidenceSubmitted>()
            assertEquals(listOf(1L), chatEvents.map { it.eventSequence })

            // 重发没有把序列号吃掉：下一条**新**证据必须拿到 2。
            db.recordChatEvidence(listOf(entry("node-geometry", "NEGATIVE", 0.35, 2)))
            val after = db.loadLearningLedger("learner:local")
            assertEquals(LearningLedgerReadStatus.COMPLETE, after.status)
            assertEquals(
                listOf(1L, 2L),
                after.validPrefix.map { it.event.eventSequence },
            )
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun aBatchContainingAnAlreadyRecordedEvidenceOnlyAllocatesForTheNewOnes() = runBlocking {
        // 同一条幂等规则在**一批多条**上的形态：已在库的那条跳过，其余照常分配。
        // 单独一条测试，是因为"只处理单元素重发"的实现也会让上面那条通过。
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "chat-evidence-idempotent-batch-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        val db = StudyDatabaseFactory.open(context, dbName)
        try {
            db.recordChatEvidence(listOf(entry("node-algebra", "POSITIVE", 0.18, 1)))

            db.recordChatEvidence(
                listOf(
                    entry("node-algebra", "POSITIVE", 0.18, 1), // 已在库 → 跳过
                    entry("node-geometry", "NEGATIVE", 0.35, 2),
                    entry("node-data", "POSITIVE", 0.18, 3),
                ),
            )

            val ledger = db.loadLearningLedger("learner:local")
            assertEquals(LearningLedgerReadStatus.COMPLETE, ledger.status)
            assertEquals(
                listOf(1L, 2L, 3L),
                ledger.validPrefix.map { it.event.eventSequence },
            )
            assertEquals(3, db.readChatEvidenceByConversation("conv-test").size)
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

    @Test
    fun gateRejectedEvidenceIsPersistedButNeverEntersLedger() = runBlocking {
        // 观察通道（research tutor-evidence-gate §3.3：被拒 ≠ 删除）：
        // rejected 行落库可审计，但不分配学习序列、不产生 outbox——
        // loadLearningLedger 与投影器都看不到它，掌握度零影响。
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "chat-evidence-rejected-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        val db = StudyDatabaseFactory.open(context, dbName)
        try {
            db.recordChatEvidence(
                listOf(
                    entry("node-algebra", "POSITIVE", 0.18, 1),
                    entry("node-algebra", "POSITIVE", 0.0, 2, rejectedReason = "MASTERED_WITHOUT_EVIDENCE_ANCHOR"),
                ),
            )

            // rejected 行确实落库（可审计）
            val all = db.readChatEvidenceByConversation("conv-test")
            assertEquals(2, all.size)
            assertEquals(1, all.count { it.isRejected })
            assertEquals(
                "MASTERED_WITHOUT_EVIDENCE_ANCHOR",
                all.single { it.isRejected }.rejected_reason,
            )

            // 但 ledger 只见 accepted——rejected 不进投影读源
            val ledger = db.loadLearningLedger("learner:local")
            assertEquals(LearningLedgerReadStatus.COMPLETE, ledger.status)
            val chatEvents = ledger.validPrefix.map { it.event }
                .filterIsInstance<ChatEvidenceSubmitted>()
            assertEquals(1, chatEvents.size)
            assertEquals(listOf(1L), chatEvents.map { it.eventSequence })
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
