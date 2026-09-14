package com.tingyun.smartmistakebook.core.data.study

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.ErrorBookEntrySeedRecord
import com.tingyun.smartmistakebook.core.database.PersistTutorChoiceCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorSessionAnchorCommand
import com.tingyun.smartmistakebook.core.database.PracticeUnitSeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemRevisionSeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemSeedRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudySeedBundle
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus
import com.tingyun.smartmistakebook.core.domain.TutorJudgedReviewSettlement
import com.tingyun.smartmistakebook.core.domain.TutorJudgedReviewSettlementStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 讲题判定结算走真 Room：无工件错题（伪 KC 路径）在讲题会话里被检查过之后，
 * 结算必须在**同一事务**里落 attempt 并推进复习队列，且只驱动题目级排期——
 * 知识点掌握度一行都不许动（空归属，避免与模型掌握度通道双写）。
 *
 * 依据 `docs/research/model-judged-verdict-pricing.md`（非独立、题目级 HARD/AGAIN）。
 */
@RunWith(AndroidJUnit4::class)
class TutorJudgedReviewSettleInstrumentedTest {

    private val learnerId = "learner:local"

    @Test
    fun judgedReviewSettlesAnAttemptAdvancesTheQueueAndLeavesKnowledgeMasteryAlone() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-judged-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database: StudyDatabasePort = StudyDatabaseFactory.open(context, databaseName)
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = RoomBackedStudyExperienceRepository(
            database = database,
            applicationScope = applicationScope,
            initialFixture = null,
        )

        try {
            database.seedFixture(capturedShapedSeed())
            repository.initialize()
            val started = requireNotNull(
                repository.startOrResumeReviewSession("tutor-judged-start", 2_000),
            )
            // 讲题会话把这道题锚定住（写侧的真实路径由 feature:tutor 调用同一命令），
            // 并留下一条**本地判定**的检查题作答：答对。
            database.bindTutorSessionProblemAnchor(
                PersistTutorSessionAnchorCommand(
                    learnerId = learnerId,
                    sessionId = TUTOR_SESSION_ID,
                    problemRevisionId = "captured-revision",
                    practiceUnitId = "captured-unit",
                    source = "SAVED_MISTAKE",
                    anchoredAtEpochMillis = 2_500,
                ),
            )
            database.recordTutorChoice(
                PersistTutorChoiceCommand(
                    sessionId = TUTOR_SESSION_ID,
                    questionDocumentId = "document-captured",
                    revisionNumber = 1,
                    cycleOrdinal = 1,
                    turnOrdinal = 1,
                    diagnosticStemMarkdown = "这一步的依据是什么？",
                    selectedChoiceId = "choice-a",
                    selectedChoiceMarkdown = "因为单调",
                    selectionWasCorrect = true,
                    feedbackMarkdown = "对了",
                    choiceSubmittedAtEpochMillis = 2_600,
                ),
            )
            // 结算前的知识点掌握度当作基线（此后必须逐字不变）。
            repository.refresh()
            val kcBefore = database.observeKnowledgeQuestionLattice(learnerId).first()
                .single { it.practiceUnitId == "captured-unit" }

            val result = repository.settleTutorJudgedReview(
                TutorJudgedReviewSettlement(
                    requestId = "tutor-judged-1",
                    sessionId = started.sessionId,
                    expectedStateVersion = started.stateVersion,
                    practiceUnitId = "captured-unit",
                    presentationId = "presentation:tutor-judged:captured-unit",
                    occurredAtEpochMillis = 3_000,
                    durationSeconds = 90,
                ),
            )

            assertEquals(TutorJudgedReviewSettlementStatus.RECORDED, result.status)
            assertTrue(requireNotNull(result.isCorrect))
            assertTrue(result.created)
            assertEquals(StudyReviewSessionStatus.COMPLETED, result.progress.status)

            // 题目级排期：FSRS 把这条非独立证据记进 assisted 桶并算出下次复习。
            repository.refresh()
            val memory = requireNotNull(
                repository.snapshot.value.catalog
                    .single { it.practiceUnitId == "captured-unit" }
                    .questionMemory,
            ) { "settled attempt did not reach the question memory" }
            assertEquals(1, memory.assistedRecallCount)
            assertTrue(memory.nextReviewAtEpochMillis > 3_000)

            // 复习日志按独立来源落库（校准通道单列，不进 FSRS 参数拟合）。
            val samples = database.readReviewLogSamples(learnerId, limit = 10)
            assertEquals(1, samples.size)
            assertEquals("MODEL_JUDGED", samples.single().sourceKind)
            assertEquals(2, samples.single().rating) // FSRS 序数 2 = HARD：判对也不越过 Hard
            assertEquals(0.5, samples.single().evidenceWeight, 1e-6)

            // 知识点掌握度一行都不许动：这条通道只驱动题目级排期，
            // 掌握度权重由模型的 chat-evidence 通道单独写（避免同一会话对同一 KC 双写）。
            database.loadProjectionBatch("study-experience-v1", learnerId, 100)
            repository.refresh()
            val kcAfter = database.observeKnowledgeQuestionLattice(learnerId).first()
                .single { it.practiceUnitId == "captured-unit" }
            assertEquals(kcBefore.knowledgeNodeId, kcAfter.knowledgeNodeId)
            assertEquals(kcBefore.kcConservativeMastery, kcAfter.kcConservativeMastery)
            assertEquals(kcBefore.kcLastEvidenceAt, kcAfter.kcLastEvidenceAt)
            assertNotNull(kcAfter.questionNextReviewAt) // 题目级排期确实动了
        } finally {
            repository.close()
            applicationScope.cancel()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun settleWithoutAnyVerdictWritesNothing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-judged-empty-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database: StudyDatabasePort = StudyDatabaseFactory.open(context, databaseName)
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = RoomBackedStudyExperienceRepository(
            database = database,
            applicationScope = applicationScope,
            initialFixture = null,
        )

        try {
            database.seedFixture(capturedShapedSeed())
            repository.initialize()
            val started = requireNotNull(
                repository.startOrResumeReviewSession("tutor-judged-empty-start", 2_000),
            )

            val result = repository.settleTutorJudgedReview(
                TutorJudgedReviewSettlement(
                    requestId = "tutor-judged-empty",
                    sessionId = started.sessionId,
                    expectedStateVersion = started.stateVersion,
                    practiceUnitId = "captured-unit",
                    presentationId = "presentation:tutor-judged:captured-unit",
                    occurredAtEpochMillis = 3_000,
                ),
            )

            assertEquals(TutorJudgedReviewSettlementStatus.NO_VERDICT, result.status)
            assertEquals(StudyReviewSessionStatus.ACTIVE, result.progress.status)
            assertEquals(0, result.progress.currentOrdinal)
            assertTrue(database.readReviewLogSamples(learnerId, limit = 10).isEmpty())
        } finally {
            repository.close()
            applicationScope.cancel()
            context.deleteDatabase(databaseName)
        }
    }

    private fun capturedShapedSeed() = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(
                problemId = "captured-problem",
                canonicalFingerprint = "fp-captured",
                subject = "MATH",
                createdAtEpochMillis = 0L,
            ),
        ),
        revisions = listOf(
            ProblemRevisionSeedRecord(
                revisionId = "captured-revision",
                problemId = "captured-problem",
                revisionNumber = 1,
                title = "拍摄保存的题",
                problemMarkdown = "解方程。",
                answerSpecId = null,
                answerSpecSnapshot = null,
                answerVerificationStatus = "USER_ASSERTED",
                sourceType = "CAPTURE",
                sourceReference = null,
                contentFingerprint = "fp-captured-r1",
                createdAtEpochMillis = 0L,
            ),
        ),
        practiceUnits = listOf(
            PracticeUnitSeedRecord(
                practiceUnitId = "captured-unit",
                problemId = "captured-problem",
                problemRevisionId = "captured-revision",
                unitKey = "captured-unit",
                unitKind = "SINGLE",
                title = "拍摄保存的题",
                promptMarkdown = "解方程。",
                estimatedSeconds = 60,
                createdAtEpochMillis = 0L,
            ),
        ),
        errorBookEntries = listOf(
            ErrorBookEntrySeedRecord(
                entryId = "captured-entry",
                practiceUnitId = "captured-unit",
                problemId = "captured-problem",
                currentRevisionId = "captured-revision",
                sourceKey = "capture:prod-photo",
                status = "ACTIVE",
                acceptedAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
            ),
        ),
    )

    private companion object {
        const val TUTOR_SESSION_ID = "tutor-session-settle-1"
    }
}
