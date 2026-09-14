package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.TutorSessionProblemAnchorRecord
import com.tingyun.smartmistakebook.core.database.TutorTurnResponseRecord
import com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus
import com.tingyun.smartmistakebook.core.domain.TutorJudgedReviewSettlement
import com.tingyun.smartmistakebook.core.domain.TutorJudgedReviewSettlementStatus
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.TutorConversationIds
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 讲题判定结算的决策表（第2条：复习作答与评级改由模型判定）。
 *
 * 判定合成顺序：本轮有本地核对的检查题作答 → 本地判定即判定（一条答错即判错，模型
 * 口头说对不作数）；没有检查题 → 取最近一条通过门控的模型判词；两样都没有 → 不写不推进。
 * 定价非独立、题目级只落 HARD/AGAIN，依据 `docs/research/model-judged-verdict-pricing.md`。
 */
class TutorJudgedReviewSettlerTest {
    @Test
    fun localCheckAnswersDecideTheVerdictAndModelClaimCannotOverrideAnIncorrectOne() = runBlocking {
        // 学生在本轮检查题里答错了一条：行为证据胜出，即便会话里也有一条（门控已接受的）
        // 模型 POSITIVE 判词，结算必须判错。
        val database = seededDatabase()
        database.tutorTurnResponses += choiceResponse(correct = false)
        database.recordedChatEvidence += modelVerdict(direction = "POSITIVE")

        val result = settle(database)

        assertEquals(TutorJudgedReviewSettlementStatus.RECORDED, result.status)
        assertFalse(requireNotNull(result.isCorrect))
        assertEquals(
            LearningEvidenceReason.MODEL_JUDGED_INCORRECT,
            database.lastAttemptCommand?.evidence?.reason,
        )
        assertEquals(0.5, database.lastAttemptCommand?.evidence?.weight ?: -1.0, 0.0)
        assertEquals(
            ProblemMemoryOutcome.RETRIEVAL_FAILURE,
            database.lastAttemptCommand?.problemMemoryOutcome,
        )
        assertEquals(
            ReviewLogSink.SOURCE_KIND_MODEL_JUDGED,
            database.reviewLogEntries.single().sourceKind,
        )
        assertTrue(result.created)
        // 空归属：这条通道只驱动题目级排期，不与模型的掌握度通道双写。
        assertTrue(database.lastEvidenceSnapshot?.attributions.isNullOrEmpty())
        assertEquals(1, database.attemptCount)
    }

    @Test
    fun allCheckAnswersCorrectSettlesAsCorrect() = runBlocking {
        val database = seededDatabase()
        database.tutorTurnResponses += choiceResponse(correct = true)
        database.tutorTurnResponses += choiceResponse(correct = true, turnOrdinal = 2)

        val result = settle(database)

        assertTrue(requireNotNull(result.isCorrect))
        assertEquals(
            LearningEvidenceReason.MODEL_JUDGED_CORRECT,
            database.lastAttemptCommand?.evidence?.reason,
        )
        assertEquals(0.5, database.lastAttemptCommand?.evidence?.weight ?: -1.0, 0.0)
        assertEquals(
            ProblemMemoryOutcome.ASSISTED_RECALL,
            database.lastAttemptCommand?.problemMemoryOutcome,
        )
    }

    @Test
    fun earlierCycleMistakesDoNotStickAfterReteach() = runBlocking {
        // 上一轮答错 → 重教 → 本轮全对：只算最新一轮（restartCycle 的理由不该永久记账）。
        val database = seededDatabase()
        database.tutorTurnResponses += choiceResponse(correct = false, cycleOrdinal = 1)
        database.tutorTurnResponses += choiceResponse(correct = true, cycleOrdinal = 2)

        val result = settle(database)

        assertTrue(requireNotNull(result.isCorrect))
    }

    @Test
    fun openEndedSessionFallsBackToTheGateAcceptedModelVerdict() = runBlocking {
        // 开放性提问没有本地机判的检查题：只能用模型判词（通过门控的那条）。
        val database = seededDatabase()
        database.recordedChatEvidence += modelVerdict(direction = "POSITIVE")

        val result = settle(database)

        assertTrue(requireNotNull(result.isCorrect))
        assertEquals(
            LearningEvidenceReason.MODEL_JUDGED_CORRECT,
            database.lastAttemptCommand?.evidence?.reason,
        )
    }

    @Test
    fun aVerdictFromBeforeThisReviewItemDoesNotSettleIt() = runBlocking {
        // 上一次推进（或本次复习开始）之前留下的判词，早该被上一项消费掉；若拿它结算，
        // 学生一进复习页就会被旧判词直接判定通过。检查题作答时间早于复习会话开始。
        val database = seededDatabase()
        database.tutorTurnResponses += choiceResponse(
            correct = true,
            submittedAtEpochMillis = 1_500,
        )

        val result = settle(database)

        assertEquals(TutorJudgedReviewSettlementStatus.NO_VERDICT, result.status)
        assertEquals(0, database.attemptCount)
    }

    @Test
    fun rejectedModelVerdictIsNotEvidence() = runBlocking {
        // 被门控拒写的行（rejected_reason 非空，weight 0）只作观察，不能当判定依据。
        val database = seededDatabase()
        database.recordedChatEvidence += modelVerdict(
            direction = "POSITIVE",
            rejectedReason = "EVIDENCE_ANCHOR_MISSING",
        )

        val result = settle(database)

        assertEquals(TutorJudgedReviewSettlementStatus.NO_VERDICT, result.status)
        assertNull(result.isCorrect)
        assertEquals(0, database.attemptCount)
        assertTrue(database.reviewLogEntries.isEmpty())
    }

    @Test
    fun noVerdictAtAllWritesNothingAndLeavesTheQueueWhereItWas() = runBlocking {
        val database = seededDatabase()

        val result = settle(database)

        assertEquals(TutorJudgedReviewSettlementStatus.NO_VERDICT, result.status)
        assertEquals(0, database.attemptCount)
        assertEquals(0, result.progress.currentOrdinal)
        assertEquals(StudyReviewSessionStatus.ACTIVE, result.progress.status)
        assertEquals(PRACTICE_UNIT_ID, result.nextPracticeUnitId)
    }

    @Test
    fun replayingTheSameSettlementDoesNotDoubleWrite() = runBlocking {
        val database = seededDatabase()
        database.tutorTurnResponses += choiceResponse(correct = true)

        val settlement = startedSettlement(database)
        val first = settle(database, settlement)
        val replay = settle(database, settlement)

        assertTrue(first.created)
        assertFalse(replay.created)
        assertEquals(first.attemptId, replay.attemptId)
        assertEquals(1, database.attemptCount)
        assertEquals(1, database.reviewLogEntries.size)
    }

    // --- helpers -------------------------------------------------------------

    private fun seededDatabase(): FakeStudyDatabasePort {
        val database = FakeStudyDatabasePort()
        database.addMistake(
            MistakeRecord(
                entryId = "captured-entry",
                problemId = "captured-problem",
                problemRevisionId = "captured-revision",
                practiceUnitId = PRACTICE_UNIT_ID,
                sourceKey = "capture:photo-1",
                subject = "MATH",
                title = "函数原题",
                problemMarkdown = "求函数的单调区间。",
                status = "ACTIVE",
                createdAtEpochMillis = 1_000,
                nextReviewAtEpochMillis = null,
                retrievability = null,
                knowledgeNodeIds = emptySet(),
            ),
        )
        database.tutorSessionAnchor = TutorSessionProblemAnchorRecord(
            learnerId = LEARNER_ID,
            sessionId = TUTOR_SESSION_ID,
            problemRevisionId = "captured-revision",
            practiceUnitId = PRACTICE_UNIT_ID,
            source = "SAVED_MISTAKE",
            anchoredAtEpochMillis = 2_500,
        )
        return database
    }

    /**
     * 先开一个真实的计划+会话（sessionId/version 由仓库自己产），把结算请求固定下来，
     * 再用它结算。重放测试必须复用同一个请求——会话完成后再开一次会撞计划冲突，
     * 那是另一个失败路径，不是这里要测的幂等。
     */
    private suspend fun startedSettlement(database: FakeStudyDatabasePort): TutorJudgedReviewSettlement =
        withRepository(database) { repository ->
            val started = requireNotNull(
                repository.startOrResumeReviewSession("settle-start", 2_000),
            )
            TutorJudgedReviewSettlement(
                requestId = "settle-1",
                sessionId = started.sessionId,
                expectedStateVersion = started.stateVersion,
                practiceUnitId = PRACTICE_UNIT_ID,
                presentationId = "presentation:tutor-judged:$PRACTICE_UNIT_ID",
                occurredAtEpochMillis = 3_000,
                durationSeconds = 90,
            )
        }

    private suspend fun settle(database: FakeStudyDatabasePort) =
        settle(database, startedSettlement(database))

    private suspend fun settle(
        database: FakeStudyDatabasePort,
        settlement: TutorJudgedReviewSettlement,
    ) = withRepository(database) { repository ->
        repository.settleTutorJudgedReview(settlement)
    }

    private suspend fun <T> withRepository(
        database: FakeStudyDatabasePort,
        block: suspend (RoomBackedStudyExperienceRepository) -> T,
    ): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = RoomBackedStudyExperienceRepository(
                database = database,
                applicationScope = scope,
                clock = Clock.fixed(
                    Instant.parse("2026-01-02T08:00:00Z"),
                    ZoneId.of("Asia/Shanghai"),
                ),
                studyZoneId = ZoneId.of("Asia/Shanghai"),
                initialFixture = null,
                fixtureSource = M1CuratedFixtureSource,
            )
            repository.initialize()
            return block(repository)
        } finally {
            scope.cancel()
        }
    }

    private fun choiceResponse(
        correct: Boolean,
        cycleOrdinal: Int = 1,
        turnOrdinal: Int = 1,
        submittedAtEpochMillis: Long = 2_600,
    ) = TutorTurnResponseRecord(
        sessionId = TUTOR_SESSION_ID,
        questionDocumentId = "document-1",
        revisionNumber = 1,
        cycleOrdinal = cycleOrdinal,
        turnOrdinal = turnOrdinal,
        diagnosticStemMarkdown = "这一步的依据是什么？",
        selectedChoiceId = "choice-a",
        selectedChoiceMarkdown = "因为单调",
        selectionWasCorrect = correct,
        feedbackMarkdown = "已显示反馈",
        requestedMove = null,
        solutionRevealed = false,
        choiceSubmittedAtEpochMillis = submittedAtEpochMillis,
        submittedAtEpochMillis = submittedAtEpochMillis,
        updatedAtEpochMillis = submittedAtEpochMillis,
    )

    private fun modelVerdict(
        direction: String,
        rejectedReason: String? = null,
    ) = LearnerChatEvidenceEntity(
        evidence_id = "evidence-1",
        learner_id = LEARNER_ID,
        conversation_id = TutorConversationIds.captured(TUTOR_SESSION_ID),
        knowledge_node_id = "knowledge:function-monotonicity",
        direction = direction,
        weight = if (rejectedReason == null) 0.15 else 0.0,
        reason_markdown = "模型判词",
        confidence = 0.9,
        source_kind = "MODEL_CHAT",
        created_at_epoch_millis = 2_700,
        rejected_reason = rejectedReason,
        rejected_at_epoch_millis = rejectedReason?.let { 2_700 },
    )

    private companion object {
        const val LEARNER_ID = "learner-1"
        const val PRACTICE_UNIT_ID = "captured-practice-unit"
        const val TUTOR_SESSION_ID = "tutor-session-1"
    }
}
