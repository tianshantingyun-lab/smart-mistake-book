package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.domain.MasteryWriteGate.GateInput
import com.tingyun.smartmistakebook.core.domain.MasteryWriteGate.GateResult
import com.tingyun.smartmistakebook.core.domain.MasteryWriteGate.RejectReason
import com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection
import com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MasteryWriteGateTest {

    private fun acceptedInput(
        direction: TutorEvidenceDirection = TutorEvidenceDirection.POSITIVE,
        understanding: TutorUnderstandingTier = TutorUnderstandingTier.CONFIDENT,
        evidenceConfidence: Double = 0.9,
        knowledgeNodeIsAnchored: Boolean = true,
        hasObjectiveSupport: Boolean = false,
        evidenceAnchorCount: Int = ENOUGH_ANCHORS,
        objectiveAnswersContradictPositive: Boolean = false,
        sameKcLastWriteAgoMillis: Long? = null,
        writesThisConversation: Int = 0,
        writesThisLearnerInWindow: Int = 0,
        attentionFactor: Double = 1.0,
    ) = GateInput(
        intentConfidence = 0.9,
        evidenceConfidence = evidenceConfidence,
        direction = direction,
        understanding = understanding,
        knowledgeNodeIsAnchored = knowledgeNodeIsAnchored,
        hasObjectiveSupport = hasObjectiveSupport,
        evidenceAnchorCount = evidenceAnchorCount,
        objectiveAnswersContradictPositive = objectiveAnswersContradictPositive,
        sameKcLastWriteAgoMillis = sameKcLastWriteAgoMillis,
        writesThisConversation = writesThisConversation,
        writesThisLearnerInWindow = writesThisLearnerInWindow,
        attentionFactor = attentionFactor,
    )

    private fun assertAccepted(input: GateInput): Double {
        val result = MasteryWriteGate.evaluate(input)
        assertTrue("expected accepted but was $result", result is GateResult.Accepted)
        return (result as GateResult.Accepted).weight
    }

    private fun assertRejected(input: GateInput, reason: RejectReason) {
        val result = MasteryWriteGate.evaluate(input)
        assertEquals(GateResult.Rejected(reason), result)
    }

    @Test
    fun `confident positive needs no verifiable support`() {
        // CONFIDENT is a dialogue self-report that is already discounted to
        // 0.15, so it does not require verifiable support; only the MASTERED
        // tier claims enough to need one.
        val weight = assertAccepted(
            acceptedInput(
                understanding = TutorUnderstandingTier.CONFIDENT,
                hasObjectiveSupport = false,
                evidenceAnchorCount = 0,
            ),
        )
        assertEquals(MasteryWriteGate.WEIGHT_CONFIDENT_POSITIVE, weight, 1e-9)
    }

    @Test
    fun `mastered positive requires verifiable support by either route`() {
        // 档2（spec 2026-09-06 §1）：MASTERED 的可核查性有两条路——本地客观作答
        // （知识点测验通道）或模型 rationale 里的逐字证据锚（讲题通道）。
        // 两条都缺 = 无法核查 → 拒写并落观察行。
        assertRejected(
            acceptedInput(
                understanding = TutorUnderstandingTier.MASTERED,
                hasObjectiveSupport = false,
                evidenceAnchorCount = ENOUGH_ANCHORS - 1,
            ),
            RejectReason.MASTERED_WITHOUT_EVIDENCE_ANCHOR,
        )
        assertAccepted(
            acceptedInput(
                understanding = TutorUnderstandingTier.MASTERED,
                hasObjectiveSupport = false,
                evidenceAnchorCount = ENOUGH_ANCHORS,
            ),
        )
        assertAccepted(
            acceptedInput(
                understanding = TutorUnderstandingTier.MASTERED,
                hasObjectiveSupport = true,
                evidenceAnchorCount = 0,
            ),
        )
    }

    @Test
    fun `evidence anchors are the quoted spans of a rationale`() {
        // 「逐字引用」在机械上就是引号包住的片段；非引号叙述不算证据锚——
        // 这正是 gate 能查的部分（条数/有无），真伪由档1规范与观察行承担。
        assertEquals(
            2,
            MasteryWriteGate.evidenceAnchorCount(
                "学生说\"我把两边都乘以了 2\"，随后独立写出\"因为斜率相等所以平行\"。",
            ),
        )
        assertEquals(
            2,
            MasteryWriteGate.evidenceAnchorCount("第一次「先配方再求根」，第二次『移项后直接开方』。"),
        )
        assertEquals(0, MasteryWriteGate.evidenceAnchorCount("看起来掌握得不错，应该没问题。"))
        assertEquals(0, MasteryWriteGate.evidenceAnchorCount(""))
    }

    @Test
    fun `a bare claim of understanding is not an evidence anchor`() {
        // 档1 规范第 2 条：学生口头说"懂了"只是线索不是事实。过短的引用
        // （含"懂了/会了"这类空话）不算锚，否则证据锚门形同虚设。
        assertEquals(0, MasteryWriteGate.evidenceAnchorCount("学生说\"懂了\"。"))
        assertEquals(1, MasteryWriteGate.evidenceAnchorCount("学生说\"我把负号漏掉了\"。"))
    }

    @Test
    fun `verified anchors must actually appear in the session text`() {
        // 消灭的失败：只数引号时，模型写 `"因为""所以"` 就能凑够 2 条锚并以
        // MASTERED 档写入。核对后，只有真出现在学生文本里的引文才计数。
        val session = "学生说：我把负号漏掉了。 随后独立写出：因为斜率相等所以平行。"
        assertEquals(
            2,
            MasteryWriteGate.verifiedEvidenceAnchorCount(
                rationale = "学生说\"我把负号漏掉了\"，随后\"因为斜率相等所以平行\"。",
                verifiableText = session,
            ),
        )
        assertEquals(
            0,
            MasteryWriteGate.verifiedEvidenceAnchorCount(
                rationale = "学生说\"我把正负号搞反了\"，随后\"因为截距相等所以平行\"。",
                verifiableText = session,
            ),
        )
    }

    @Test
    fun `verified anchors need a session corpus and fold whitespace and case`() {
        // 没有可核查文本时，任何锚都得不到证实——与"缺佐证不写高置信档"同姿态。
        assertEquals(
            0,
            MasteryWriteGate.verifiedEvidenceAnchorCount("\"我把负号漏掉了\"", verifiableText = ""),
        )
        assertEquals(
            0,
            MasteryWriteGate.verifiedEvidenceAnchorCount("\"我把负号漏掉了\"", verifiableText = "   "),
        )
        // Markdown 换行/缩进会把引文切断，逐字节比对会误杀合法引文；标点不折叠。
        assertEquals(
            1,
            MasteryWriteGate.verifiedEvidenceAnchorCount(
                rationale = "学生说\"因为斜率相等所以平行\"。",
                verifiableText = "因为斜率相等\n    所以平行",
            ),
        )
        assertEquals(
            0,
            MasteryWriteGate.verifiedEvidenceAnchorCount(
                rationale = "学生说\"因为斜率相等所以平行\"。",
                verifiableText = "因为斜率相等，所以平行",
            ),
        )
    }

    @Test
    fun `verified count never exceeds the mechanical count`() {
        val rationale = "学生说\"我把负号漏掉了\"，随后\"因为斜率相等所以平行\"。"
        val session = "我把负号漏掉了"
        assertEquals(2, MasteryWriteGate.evidenceAnchorCount(rationale))
        assertEquals(1, MasteryWriteGate.verifiedEvidenceAnchorCount(rationale, session))
    }

    @Test
    fun `positive struggling is a semantic contradiction`() {
        assertRejected(
            acceptedInput(direction = TutorEvidenceDirection.POSITIVE, understanding = TutorUnderstandingTier.STRUGGLING),
            RejectReason.CONTRADICTORY_SEMANTICS,
        )
    }

    @Test
    fun `evidence confidence below threshold is rejected`() {
        assertRejected(
            acceptedInput(evidenceConfidence = 0.69),
            RejectReason.EVIDENCE_BELOW_CONFIDENCE,
        )
    }

    @Test
    fun `unanchored knowledge node is rejected`() {
        assertRejected(
            acceptedInput(knowledgeNodeIsAnchored = false),
            RejectReason.KNOWLEDGE_NODE_NOT_ANCHORED,
        )
    }

    @Test
    fun `same kc write inside cooldown is rejected`() {
        assertRejected(
            acceptedInput(sameKcLastWriteAgoMillis = MasteryWriteGate.SAME_KC_COOLDOWN_MILLIS - 1),
            RejectReason.SAME_KC_IN_COOLDOWN,
        )
        // Exactly at the cooldown boundary is accepted.
        assertTrue(
            MasteryWriteGate.evaluate(
                acceptedInput(sameKcLastWriteAgoMillis = MasteryWriteGate.SAME_KC_COOLDOWN_MILLIS),
            ) is GateResult.Accepted,
        )
    }

    @Test
    fun `conversation quota exhaustion is rejected`() {
        assertRejected(
            acceptedInput(writesThisConversation = MasteryWriteGate.MAX_WRITES_PER_CONVERSATION),
            RejectReason.CONVERSATION_QUOTA_EXHAUSTED,
        )
    }

    @Test
    fun `learner window quota exhaustion is rejected across conversations`() {
        // A multi-session farm (N sessions × up to 8 each) must be capped by
        // the per-learner rolling window even when each single conversation
        // stays under its own quota.
        assertRejected(
            acceptedInput(
                writesThisConversation = 0,
                writesThisLearnerInWindow = MasteryWriteGate.MAX_WRITES_PER_LEARNER_WINDOW,
            ),
            RejectReason.LEARNER_WINDOW_QUOTA_EXHAUSTED,
        )
        // Below the window cap the write proceeds.
        assertTrue(
            MasteryWriteGate.evaluate(
                acceptedInput(
                    writesThisConversation = 0,
                    writesThisLearnerInWindow = MasteryWriteGate.MAX_WRITES_PER_LEARNER_WINDOW - 1,
                ),
            ) is GateResult.Accepted,
        )
    }

    @Test
    fun `attention below reject floor is rejected`() {
        assertRejected(
            acceptedInput(attentionFactor = MasteryWriteGate.MIN_ATTENTION_FACTOR - 0.01),
            RejectReason.ATTENTION_BELOW_FLOOR,
        )
        // At or above the floor is accepted (down-weighting happens upstream).
        assertTrue(
            MasteryWriteGate.evaluate(
                acceptedInput(attentionFactor = MasteryWriteGate.MIN_ATTENTION_FACTOR),
            ) is GateResult.Accepted,
        )
    }

    @Test
    fun `weight table maps understanding tiers to local constants`() {
        assertEquals(MasteryWriteGate.WEIGHT_UNCERTAIN_POSITIVE, MasteryWriteGate.positiveWeightFor(TutorUnderstandingTier.UNCERTAIN), 1e-9)
        assertEquals(MasteryWriteGate.WEIGHT_CONFIDENT_POSITIVE, MasteryWriteGate.positiveWeightFor(TutorUnderstandingTier.CONFIDENT), 1e-9)
        assertEquals(MasteryWriteGate.WEIGHT_MASTERED_POSITIVE, MasteryWriteGate.positiveWeightFor(TutorUnderstandingTier.MASTERED), 1e-9)
        assertEquals(MasteryWriteGate.WEIGHT_STRUGGLING, MasteryWriteGate.negativeWeight(), 1e-9)
        // All positive tiers stay at or below the model-evidence cap.
        listOf(
            TutorUnderstandingTier.UNCERTAIN,
            TutorUnderstandingTier.CONFIDENT,
            TutorUnderstandingTier.MASTERED,
        ).forEach { tier ->
            assertTrue(MasteryWriteGate.positiveWeightFor(tier) <= MasteryWriteGate.MAX_EVIDENCE_WEIGHT)
        }
    }

    @Test
    fun `accepted negative uses the standard lapse weight regardless of understanding`() {
        for (tier in TutorUnderstandingTier.entries) {
            if (tier == TutorUnderstandingTier.STRUGGLING) continue
            val weight = assertAccepted(
                acceptedInput(direction = TutorEvidenceDirection.NEGATIVE, understanding = tier),
            )
            assertEquals(MasteryWriteGate.negativeWeight(), weight, 1e-9)
        }
    }

    @Test
    fun `negative struggling is accepted as the standard lapse tier`() {
        val weight = assertAccepted(
            acceptedInput(direction = TutorEvidenceDirection.NEGATIVE, understanding = TutorUnderstandingTier.STRUGGLING),
        )
        assertEquals(MasteryWriteGate.negativeWeight(), weight, 1e-9)
    }

    @Test
    fun `positive is rejected when the session's objective answers contradict it`() {
        // 研究 §3.2：冲突时行为证据胜出。学生答错了自己面前的检查题，模型仍判
        // 正向 → 该判断降级为观察记录（被拒 ≠ 删除，落 rejected 行）。
        val result = MasteryWriteGate.evaluate(
            acceptedInput(
                direction = TutorEvidenceDirection.POSITIVE,
                understanding = TutorUnderstandingTier.CONFIDENT,
                objectiveAnswersContradictPositive = true,
            ),
        )
        assertEquals(
            GateResult.Rejected(RejectReason.OBJECTIVE_ANSWER_CONTRADICTS_POSITIVE),
            result,
        )
    }

    @Test
    fun `a contradicted positive is rejected even when the rationale carries enough anchors`() {
        // 刻意锁死优先级：证据锚路不能把被行为证据推翻的判断再放进来。
        // 否则模型只要在 rationale 里多引用两个片段就能压过学生答错的事实。
        val result = MasteryWriteGate.evaluate(
            acceptedInput(
                understanding = TutorUnderstandingTier.MASTERED,
                hasObjectiveSupport = false,
                evidenceAnchorCount = ENOUGH_ANCHORS,
                objectiveAnswersContradictPositive = true,
            ),
        )
        assertEquals(
            GateResult.Rejected(RejectReason.OBJECTIVE_ANSWER_CONTRADICTS_POSITIVE),
            result,
        )
    }

    @Test
    fun `negative evidence is unaffected by a contradicted positive claim`() {
        // 学生答错与"负向判断"方向一致，不构成冲突：此时拒写会把真实的下滑
        // 信号一起丢掉。
        val weight = assertAccepted(
            acceptedInput(
                direction = TutorEvidenceDirection.NEGATIVE,
                understanding = TutorUnderstandingTier.STRUGGLING,
                objectiveAnswersContradictPositive = true,
            ),
        )
        assertEquals(MasteryWriteGate.negativeWeight(), weight, 1e-9)
    }

    @Test
    fun `a session with no objective answers cannot contradict a positive claim`() {
        // 佐证缺位 ≠ 冲突：学生没答过检查题时，正向路仍然按原有门槛走
        // （MASTERED 要证据锚，CONFIDENT 不要）。
        val weight = assertAccepted(
            acceptedInput(
                direction = TutorEvidenceDirection.POSITIVE,
                understanding = TutorUnderstandingTier.CONFIDENT,
                objectiveAnswersContradictPositive = false,
            ),
        )
        assertEquals(MasteryWriteGate.WEIGHT_CONFIDENT_POSITIVE, weight, 1e-9)
    }

    @Test
    fun `gate constants match the research calibration table`() {
        assertEquals(0.7, MasteryWriteGate.EVIDENCE_CONFIDENCE_THRESHOLD, 1e-9)
        assertEquals(12L * 60 * 60 * 1000, MasteryWriteGate.SAME_KC_COOLDOWN_MILLIS)
        assertEquals(50, MasteryWriteGate.MAX_WRITES_PER_CONVERSATION)
        assertEquals(100, MasteryWriteGate.MAX_WRITES_PER_LEARNER_WINDOW)
        assertEquals(1L * 60 * 60 * 1000, MasteryWriteGate.LEARNER_WINDOW_MILLIS)
        assertEquals(0.4, MasteryWriteGate.MIN_ATTENTION_FACTOR, 1e-9)
        // 档2：MASTERED 的证据锚门槛与档1 prompt 规范第 1 条（"逐字引用≥2条"）同值。
        assertEquals(2, MasteryWriteGate.REQUIRED_EVIDENCE_ANCHORS_FOR_MASTERED)
    }

    private companion object {
        /** 满足 [MasteryWriteGate.REQUIRED_EVIDENCE_ANCHORS_FOR_MASTERED] 的条数。 */
        const val ENOUGH_ANCHORS = MasteryWriteGate.REQUIRED_EVIDENCE_ANCHORS_FOR_MASTERED
    }
}
