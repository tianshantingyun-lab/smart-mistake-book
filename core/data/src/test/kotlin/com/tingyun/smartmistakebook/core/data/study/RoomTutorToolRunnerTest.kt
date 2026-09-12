package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.TutorMessageRecord
import com.tingyun.smartmistakebook.core.database.TutorTurnResponseRecord
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
        // 两条锚都必须真出现在会话语料里——校核函数的引入没有取消这条路径，
        // 只是把"引号对上"升级为"引文确有其事"。
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我把两边都乘以了2")
        port.tutorMessages += studentMessage("因为斜率相等所以平行")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把两边都乘以了2\"，随后独立写出\"因为斜率相等所以平行\"。",
            ),
            sessionContext(),
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

    // ---- 客观作答交叉核对（研究 tutor-evidence-gate §3.2）----

    @Test
    fun aPositiveClaimIsRejectedWhenTheStudentJustMissedTheCheckQuestion() = runBlocking {
        // 消灭的失败：模型对着学生刚答错的检查题判 POSITIVE，口头声明压过
        // 本地行为证据写进掌握度——runner 此前恒传 false，从不做这个核对。
        val port = anchoredPort()
        port.tutorTurnResponses += turnResponse(cycleOrdinal = 1, selectionWasCorrect = false)

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(rationale = "学生独立完成了这一步。", understanding = TutorUnderstandingTier.CONFIDENT),
            sessionContext(),
        )

        assertEquals(false, outcome.ok)
        assertEquals("rejected:OBJECTIVE_ANSWER_CONTRADICTS_POSITIVE", outcome.errorKind)
        // 被拒 ≠ 删除：降级为观察记录，保留拒因与时刻。
        val rejected = port.recordedChatEvidence.single()
        assertEquals(0.0, rejected.weight, 1e-9)
        assertEquals("OBJECTIVE_ANSWER_CONTRADICTS_POSITIVE", rejected.rejected_reason)
    }

    @Test
    fun theCrossCheckOutranksTheEvidenceAnchorRoute() = runBlocking {
        // 锁死优先级：模型不能靠多引用两个片段绕开学生的错误作答。
        val port = anchoredPort()
        port.tutorTurnResponses += turnResponse(cycleOrdinal = 1, selectionWasCorrect = false)

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把两边都乘以了2\"，随后独立写出\"因为斜率相等所以平行\"。",
                understanding = TutorUnderstandingTier.MASTERED,
            ),
            sessionContext(),
        )

        assertEquals("rejected:OBJECTIVE_ANSWER_CONTRADICTS_POSITIVE", outcome.errorKind)
    }

    @Test
    fun aCorrectCheckAnswerDoesNotBlockAPositiveClaim() = runBlocking {
        val port = anchoredPort()
        port.tutorTurnResponses += turnResponse(cycleOrdinal = 1, selectionWasCorrect = true)

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(rationale = "学生独立完成了这一步。", understanding = TutorUnderstandingTier.CONFIDENT),
            sessionContext(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
    }

    @Test
    fun lastCyclesMistakeDoesNotBlockThisCyclesPositiveClaim() = runBlocking {
        // 上一轮的答错正是重教的理由。永久计入会让重教后答对也洗不掉，
        // 门成为不可达的死门（与档2 修的 0.18 死常数同类）。
        val port = anchoredPort()
        port.tutorTurnResponses += turnResponse(cycleOrdinal = 1, selectionWasCorrect = false)
        port.tutorTurnResponses += turnResponse(cycleOrdinal = 2, selectionWasCorrect = true)

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(rationale = "学生独立完成了这一步。", understanding = TutorUnderstandingTier.CONFIDENT),
            sessionContext(cycleOrdinal = 2),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
    }

    @Test
    fun aNegativeClaimIsNotBlockedByTheStudentsWrongAnswer() = runBlocking {
        // 方向一致不构成冲突：此时拒写会把真实的下滑信号一起丢掉。
        val port = anchoredPort()
        port.tutorTurnResponses += turnResponse(cycleOrdinal = 1, selectionWasCorrect = false)

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生把符号搞反了。",
                understanding = TutorUnderstandingTier.STRUGGLING,
                direction = TutorEvidenceDirection.NEGATIVE,
            ),
            sessionContext(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
    }

    @Test
    fun withoutASessionThereIsNothingToCrossCheck() = runBlocking {
        // 无会话上下文（Lobby 派遣）时没有客观作答可言，不引入新拒因。
        val port = anchoredPort()
        port.tutorTurnResponses += turnResponse(cycleOrdinal = 1, selectionWasCorrect = false)

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(rationale = "学生独立完成了这一步。", understanding = TutorUnderstandingTier.CONFIDENT),
            context(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
    }

    // ---- 证据锚真实性核对（方向A）----

    @Test
    fun masteredIsRejectedWhenTheQuotedAnchorsAreFabricated() = runBlocking {
        // 消灭的失败：档2 只数引号，模型写 `"因为""所以"` 就凑够 2 条锚并以
        // MASTERED 写入。核对后，引文必须是本会话里学生真产出过的文本。
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我把负号漏掉了")
        port.tutorMessages += studentMessage("因为斜率相等所以平行")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把正号写错了\"，随后\"因为截距相等所以平行\"。",
                understanding = TutorUnderstandingTier.MASTERED,
            ),
            sessionContext(),
        )

        assertEquals(false, outcome.ok)
        assertEquals("rejected:MASTERED_WITHOUT_EVIDENCE_ANCHOR", outcome.errorKind)
        // 被拒 ≠ 删除：落观察行供审计。
        assertEquals(
            "MASTERED_WITHOUT_EVIDENCE_ANCHOR",
            port.recordedChatEvidence.single().rejected_reason,
        )
    }

    @Test
    fun masteredIsAcceptedWhenTheQuotedAnchorsAreVerbatim() = runBlocking {
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我把负号漏掉了")
        port.tutorMessages += studentMessage("因为斜率相等所以平行")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把负号漏掉了\"，随后\"因为斜率相等所以平行\"。",
                understanding = TutorUnderstandingTier.MASTERED,
            ),
            sessionContext(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
        assertEquals(
            MasteryWriteGate.WEIGHT_MASTERED_POSITIVE,
            port.recordedChatEvidence.single().weight,
            1e-9,
        )
    }

    @Test
    fun aVerbatimStudentChoiceCountsAsAnAnchorWithoutAnyMessage() = runBlocking {
        // 学生的客观作答属于档1 规范里的"可观察行为"，与消息原文同为可核查语料。
        val port = anchoredPort()
        port.tutorTurnResponses += turnResponse(
            cycleOrdinal = 1,
            selectionWasCorrect = true,
            selectedChoiceMarkdown = "因为斜率相等所以平行",
        )

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生选了\"因为斜率相等所以平行\"，随后\"我把负号漏掉了\"。",
                understanding = TutorUnderstandingTier.MASTERED,
            ),
            sessionContext(),
        )

        // 两条锚里只有一条能在作答语料中找到，仍不足门槛。
        assertEquals("rejected:MASTERED_WITHOUT_EVIDENCE_ANCHOR", outcome.errorKind)
    }

    @Test
    fun assistantMessagesCannotCorroborateTheModelsOwnClaim() = runBlocking {
        // 模型不能拿自己说过的话当证据：ASSISTANT 行不进入可核查语料。
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我把负号漏掉了", role = "ASSISTANT")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把负号漏掉了\"，随后\"因为斜率相等所以平行\"。",
                understanding = TutorUnderstandingTier.MASTERED,
            ),
            sessionContext(),
        )

        assertEquals("rejected:MASTERED_WITHOUT_EVIDENCE_ANCHOR", outcome.errorKind)
    }

    @Test
    fun theAnchorCheckDoesNotDowngradeLowerTiers() = runBlocking {
        // 核对只加在 MASTERED 档：CONFIDENT 按对话自报折价 0.15，本就不要求锚。
        // 若把核对推广到所有正向档，日常讲题会因引文改写而全部写不进掌握度。
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我把负号漏掉了")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把符号问题处理好了\"。",
                understanding = TutorUnderstandingTier.CONFIDENT,
            ),
            sessionContext(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
    }

    private fun studentMessage(body: String, role: String = "STUDENT") = TutorMessageRecord(
        messageId = "message-${body.hashCode()}-$role",
        conversationId = CONVERSATION_ID,
        ordinal = 1,
        role = role,
        bodyMarkdown = body,
        status = "COMPLETED",
        logicalOperationId = null,
        replyToMessageId = null,
        createdAtEpochMillis = 2_000,
        completedAtEpochMillis = 2_000,
        errorCode = null,
    )

    private fun sessionContext(cycleOrdinal: Int = 1) = context().copy(
        tutorSessionId = TUTOR_SESSION_ID,
        conversationId = CONVERSATION_ID,
        cycleOrdinal = cycleOrdinal,
    )

    private fun turnResponse(
        cycleOrdinal: Int,
        selectionWasCorrect: Boolean,
        selectedChoiceMarkdown: String = "选项 A",
    ) = TutorTurnResponseRecord(
        sessionId = TUTOR_SESSION_ID,
        questionDocumentId = "question-1",
        revisionNumber = 1,
        cycleOrdinal = cycleOrdinal,
        turnOrdinal = 1,
        diagnosticStemMarkdown = "下列哪个选项正确？",
        selectedChoiceId = "choice-a",
        selectedChoiceMarkdown = selectedChoiceMarkdown,
        selectionWasCorrect = selectionWasCorrect,
        feedbackMarkdown = "解析。",
        requestedMove = null,
        solutionRevealed = false,
        choiceSubmittedAtEpochMillis = 2_000,
        submittedAtEpochMillis = 2_000,
        updatedAtEpochMillis = 2_000,
    )

    private companion object {
        const val TUTOR_SESSION_ID = "tutor-session-1"
        const val CONVERSATION_ID = "tutor-conv-1"
    }
}
