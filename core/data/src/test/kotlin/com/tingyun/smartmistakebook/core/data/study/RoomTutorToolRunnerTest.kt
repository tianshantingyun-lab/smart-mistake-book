package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.domain.MasteryWriteGate
import com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection
import com.tingyun.smartmistakebook.core.model.TutorToolCall
import com.tingyun.smartmistakebook.core.model.TutorToolName
import com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 讲题工具环 T6 `MASTERY_UPDATE` 的写侧接线（档2，spec
 * `2026-09-06-mastery-judgment-gate-evolution.md` §1）。
 *
 * 消灭的失败：runner 曾把 `hasBehavioralSupport` 硬编码为 false，而本地在讲题通道
 * 拿不到"学生懂了"的客观佐证——于是模型的 MASTERED 判断**永远被拒**，权重表里的
 * 0.18 档在生产里是死常数。本测试锁定新契约：MASTERED 的可核查性来自模型
 * rationale 里逐字引用的证据锚条数，由 [MasteryWriteGate.evidenceAnchorCount]
 * 从 rationale 数出后传门；不足门槛即拒并落 rejected 观察行。
 */
class RoomTutorToolRunnerTest {

    private val learnerId = "learner:local"

    private fun anchoredPort(nodeId: String = "kc-monotonicity") = FakeStudyDatabasePort().apply {
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

    private fun context() = RoomTutorToolRunner.Context(
        subject = "MATH",
        learnerId = learnerId,
        conversationId = "tutor-conv-1",
    )

    private fun masteryCall(
        rationale: String,
        understanding: TutorUnderstandingTier = TutorUnderstandingTier.MASTERED,
        direction: TutorEvidenceDirection = TutorEvidenceDirection.POSITIVE,
    ) = TutorToolCall(
        tool = TutorToolName.MASTERY_UPDATE,
        rationale = rationale,
        terms = listOf("kc-monotonicity"),
        direction = direction,
        understanding = understanding,
        confidence = 0.9,
    )

    @Test
    fun masteredWithTwoQuotedAnchorsIsAcceptedAtTheMasterTier() = runBlocking {
        val port = anchoredPort()

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把两边都乘以了2\"，随后独立写出\"因为斜率相等所以平行\"。",
            ),
            context(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
        val evidence = port.recordedChatEvidence.single()
        assertEquals(TutorEvidenceDirection.POSITIVE.name, evidence.direction)
        assertEquals(MasteryWriteGate.WEIGHT_MASTERED_POSITIVE, evidence.weight, 1e-9)
        assertNull(evidence.rejected_reason)
    }

    @Test
    fun masteredWithoutAnchorsIsRejectedAndStillAudited() = runBlocking {
        val port = anchoredPort()

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(rationale = "看起来学生已经掌握了这个知识点。"),
            context(),
        )

        assertEquals(false, outcome.ok)
        assertEquals("rejected:MASTERED_WITHOUT_EVIDENCE_ANCHOR", outcome.errorKind)
        // 被拒 ≠ 删除：观察行落库（weight=0、带拒因），不静默丢弃。
        val rejected = port.recordedChatEvidence.single()
        assertEquals(0.0, rejected.weight, 1e-9)
        assertEquals("MASTERED_WITHOUT_EVIDENCE_ANCHOR", rejected.rejected_reason)
        assertNotNull(rejected.rejected_at_epoch_millis)
    }

    @Test
    fun aBareClaimOfUnderstandingDoesNotCountAsAnAnchor() = runBlocking {
        val port = anchoredPort()

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(rationale = "学生说\"懂了\"，也说了\"会了\"。"),
            context(),
        )

        assertEquals(false, outcome.ok)
        assertEquals("rejected:MASTERED_WITHOUT_EVIDENCE_ANCHOR", outcome.errorKind)
    }

    @Test
    fun confidentIsUnaffectedByTheAnchorGate() = runBlocking {
        // 门只在 MASTERED 档加码：CONFIDENT 本就按对话自报折价到 0.15，
        // 不再叠加证据锚要求（否则日常讲题全部写不进掌握度）。
        val port = anchoredPort()

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生独立完成了这一步。",
                understanding = TutorUnderstandingTier.CONFIDENT,
            ),
            context(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
        assertEquals(
            MasteryWriteGate.WEIGHT_CONFIDENT_POSITIVE,
            port.recordedChatEvidence.single().weight,
            1e-9,
        )
    }

    @Test
    fun negativeLapseNeedsNoAnchor() = runBlocking {
        val port = anchoredPort()

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生把符号搞反了。",
                understanding = TutorUnderstandingTier.STRUGGLING,
                direction = TutorEvidenceDirection.NEGATIVE,
            ),
            context(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
        assertNull(port.recordedChatEvidence.single().rejected_reason)
    }
}
