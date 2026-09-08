package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知识点复习作答回写（spec dual-review-entry §3.4）：判答（客观对错）→ 走本地
 * [com.tingyun.smartmistakebook.core.domain.MasteryWriteGate] 门控 → Accepted 才把
 * 掌握度证据写入 chat-evidence（进而经 ledger/投影更新知识点掌握态，影响后续错题排程）。
 * 消灭的失败：回写链没有测试——判定与门控接错、方向写反、或未锚定节点也落库都无人发现。
 */
class KnowledgeQuizFeedbackWriteTest {

    private val learnerId = RoomBackedStudyExperienceRepository.DEFAULT_LEARNER_ID

    private fun repository(database: FakeStudyDatabasePort): RoomBackedStudyExperienceRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return RoomBackedStudyExperienceRepository(
            database = database,
            applicationScope = scope,
            clock = Clock.fixed(Instant.parse("2026-01-02T08:00:00Z"), ZoneId.of("Asia/Shanghai")),
            studyZoneId = ZoneId.of("Asia/Shanghai"),
            initialFixture = null,
            fixtureSource = M1CuratedFixtureSource,
        )
    }

    private fun anchoredDatabase(nodeId: String = "kc-monotonicity") = FakeStudyDatabasePort().apply {
        knowledgeNodes += KnowledgeNodeSeedRecord(
            knowledgeNodeId = nodeId,
            stableCode = "math.function.monotonicity",
            subject = "MATH",
            displayName = "函数单调性",
            parentKnowledgeNodeId = null,
            taxonomyVersion = "cn-highschool-m1-v1",
            createdAtEpochMillis = 1_000,
            canonicalName = "函数单调性",
        )
    }

    @Test
    fun correctAnswerWritesPositiveAnchoredEvidence() = runBlocking {
        val database = anchoredDatabase()
        val repository = repository(database)

        try {
            val result = repository.submitKnowledgeQuizFeedback(
                requestId = "req-correct",
                knowledgeNodeId = "kc-monotonicity",
                correctChoiceId = "A",
                selectedChoiceId = "A",
                occurredAtEpochMillis = 5_000,
            )

            assertTrue(result.isCorrect)
            assertTrue(result.evidenceRecorded)
            val evidence = database.recordedChatEvidence.single()
            assertEquals(learnerId, evidence.learner_id)
            assertEquals("kc-monotonicity", evidence.knowledge_node_id)
            assertEquals(TutorEvidenceDirection.POSITIVE.name, evidence.direction)
            assertEquals("KNOWLEDGE_QUIZ", evidence.source_kind)
            assertEquals("knowledge-quiz-review", evidence.conversation_id)
            assertEquals("knowledge-quiz:req-correct:kc-monotonicity", evidence.evidence_id)
            assertTrue(evidence.weight > 0.0)
            assertEquals(5_000L, evidence.created_at_epoch_millis)
            // 正常写入不带拒绝标记（拒绝行只作审计、不进投影）。
            assertEquals(null, evidence.rejected_reason)
        } finally {
            repository.close()
        }
    }

    @Test
    fun wrongAnswerWritesNegativeEvidence() = runBlocking {
        val database = anchoredDatabase()
        val repository = repository(database)

        try {
            val result = repository.submitKnowledgeQuizFeedback(
                requestId = "req-wrong",
                knowledgeNodeId = "kc-monotonicity",
                correctChoiceId = "A",
                selectedChoiceId = "B",
                occurredAtEpochMillis = 6_000,
            )

            assertFalse(result.isCorrect)
            assertTrue(result.evidenceRecorded)
            val evidence = database.recordedChatEvidence.single()
            assertEquals(TutorEvidenceDirection.NEGATIVE.name, evidence.direction)
            assertEquals("knowledge-quiz:req-wrong:kc-monotonicity", evidence.evidence_id)
        } finally {
            repository.close()
        }
    }

    @Test
    fun unanchoredKnowledgeNodeIsRejectedWithoutWritingEvidence() = runBlocking {
        // 知识库里不存在该节点 → 门控 KNOWLEDGE_NODE_NOT_ANCHORED 拒绝：不写证据，
        // 防止模型/调用方凭臆造节点污染掌握度。
        val database = FakeStudyDatabasePort()
        val repository = repository(database)

        try {
            val result = repository.submitKnowledgeQuizFeedback(
                requestId = "req-unanchored",
                knowledgeNodeId = "kc-not-in-library",
                correctChoiceId = "A",
                selectedChoiceId = "A",
                occurredAtEpochMillis = 7_000,
            )

            assertTrue(result.isCorrect)
            assertFalse(result.evidenceRecorded)
            assertNotNull(result.rejectedReason)
            assertTrue(database.recordedChatEvidence.isEmpty())
        } finally {
            repository.close()
        }
    }

    @Test
    fun evidenceIdIsDeterministicSoRetriesDoNotDuplicate() = runBlocking {
        // 同一 requestId + 节点 → 同一 evidence_id（DB 侧幂等键），重试不会写重复证据。
        val database = anchoredDatabase()
        val repository = repository(database)

        try {
            repeat(2) { attempt ->
                repository.submitKnowledgeQuizFeedback(
                    requestId = "req-retry",
                    knowledgeNodeId = "kc-monotonicity",
                    correctChoiceId = "A",
                    selectedChoiceId = "A",
                    occurredAtEpochMillis = 8_000L + attempt,
                )
            }
            val ids = database.recordedChatEvidence.map { it.evidence_id }.distinct()
            assertEquals(listOf("knowledge-quiz:req-retry:kc-monotonicity"), ids)
        } finally {
            repository.close()
        }
    }
}
