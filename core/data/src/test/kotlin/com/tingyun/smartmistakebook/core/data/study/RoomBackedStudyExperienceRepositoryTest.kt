package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.data.M1CuratedStudySeed
import com.tingyun.smartmistakebook.core.database.AnswerRevealWriteCommand
import com.tingyun.smartmistakebook.core.database.AnswerRevealWriteResult
import com.tingyun.smartmistakebook.core.database.AssessmentEventSeedRecord
import com.tingyun.smartmistakebook.core.database.AssessmentItemSnapshotSeedRecord
import com.tingyun.smartmistakebook.core.database.CommitProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.CommitProblemDraftResult
import com.tingyun.smartmistakebook.core.database.CreateProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.CreateModelTaskCommand
import com.tingyun.smartmistakebook.core.database.AttemptCorrectionRecord
import com.tingyun.smartmistakebook.core.database.AttemptCorrectionResult
import com.tingyun.smartmistakebook.core.database.AttemptAdvanceProofRecord
import com.tingyun.smartmistakebook.core.database.AttemptPersistenceRecord
import com.tingyun.smartmistakebook.core.database.AttemptWriteCommand
import com.tingyun.smartmistakebook.core.database.AttemptWriteResult
import com.tingyun.smartmistakebook.core.database.LearningLedgerRead
import com.tingyun.smartmistakebook.core.database.LearningLedgerReadStatus
import com.tingyun.smartmistakebook.core.database.KnowledgeGroundingSummaryRecord
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.ModelTaskWriteResult
import com.tingyun.smartmistakebook.core.database.PersistedAnswerRevealP0
import com.tingyun.smartmistakebook.core.database.PersistedAttemptP0
import com.tingyun.smartmistakebook.core.database.PersistedCorrectionP0
import com.tingyun.smartmistakebook.core.database.PersistedLearnerSnapshot
import com.tingyun.smartmistakebook.core.database.ProjectionBatch
import com.tingyun.smartmistakebook.core.database.ProjectionBatchStopReason
import com.tingyun.smartmistakebook.core.database.ProjectionCommit
import com.tingyun.smartmistakebook.core.database.ProblemDraftRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftWriteResult
import com.tingyun.smartmistakebook.core.database.ReviewPlanBundle
import com.tingyun.smartmistakebook.core.database.ReviewAttemptWriteCommand
import com.tingyun.smartmistakebook.core.database.ReviewAttemptWriteResult
import com.tingyun.smartmistakebook.core.database.ReviewSessionAdvanceCommand
import com.tingyun.smartmistakebook.core.database.ReviewSessionAdvanceReceipt
import com.tingyun.smartmistakebook.core.database.ReviewSessionAdvanceResult
import com.tingyun.smartmistakebook.core.database.ReviewSessionRecord
import com.tingyun.smartmistakebook.core.database.ReviewedKnowledgeCoverageRecord
import com.tingyun.smartmistakebook.core.database.ReviseProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.SeedResult
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.StudySeedBundle
import com.tingyun.smartmistakebook.core.database.TransitionModelTaskCommand
import com.tingyun.smartmistakebook.core.domain.SaveStudyMistakeResult
import com.tingyun.smartmistakebook.core.domain.StudyDataStatus
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmission
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReport
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReportSubmission
import com.tingyun.smartmistakebook.core.domain.LearningProjector
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomBackedStudyExperienceRepositoryTest {
    @Test
    fun initializePublishesGroupedKnowledgeCoverageWithoutLearningEvidence() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            reviewedKnowledgeCoverage.value = listOf(
                ReviewedKnowledgeCoverageRecord(
                    subject = SubjectKind.MATH.name,
                    topicCount = 1,
                    atomicKnowledgeCount = 4,
                    reviewedSourceCount = 2,
                    latestReviewedAtEpochMillis = 4_000,
                ),
            )
            knowledgeGroundingSummaries.value = listOf(
                KnowledgeGroundingSummaryRecord(
                    groundingKey = "gap:math:monotonicity",
                    subject = SubjectKind.MATH.name,
                    expectedParentKnowledgeDisplayName = "函数性质",
                    query = "导数符号与单调区间",
                    relatedQuestionCount = 3,
                    firstObservedAtEpochMillis = 1_000,
                    lastObservedAtEpochMillis = 3_000,
                ),
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(
            database = database,
            applicationScope = applicationScope,
            initialFixture = null,
        )

        try {
            repository.initialize()

            val coverage = repository.snapshot.value.knowledgeCoverage
            assertEquals(1, coverage.pendingGapCount)
            assertEquals(3, coverage.pendingQuestionOccurrenceCount)
            assertEquals(SubjectKind.MATH, coverage.pendingGaps.single().subject)
            assertEquals(1, coverage.reviewedSubjectCount)
            assertEquals(4, coverage.reviewedAtomicKnowledgeCount)
            assertEquals(2, coverage.reviewedSourceCount)
            assertFalse(repository.snapshot.value.profile.hasLearningEvidence)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun productionDefaultInitializeKeepsAFreshDatabaseEmpty() = runBlocking {
        val database = FakeStudyDatabasePort()
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(
            database = database,
            applicationScope = applicationScope,
            initialFixture = null,
        )

        try {
            repository.initialize()
            repository.initialize()

            val snapshot = repository.snapshot.value
            assertEquals(0, database.seedCallCount)
            assertEquals(0, database.problemCount)
            assertEquals(0, snapshot.mistakeCount)
            assertTrue(snapshot.review.scheduledPracticeUnitIds.isEmpty())
            assertFalse(snapshot.profile.hasLearningEvidence)
            assertEquals(StudyDataStatus.READY, snapshot.status)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun explicitFixtureSeedsFiveProblemsAndFourEntriesWithoutInventingHistory() = runBlocking {
        val database = FakeStudyDatabasePort()
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            repository.initialize()

            val snapshot = repository.snapshot.value
            assertEquals(5, database.problemCount)
            assertEquals(4, snapshot.mistakeCount)
            assertEquals(StudyDataStatus.READY, snapshot.status)
            assertFalse(snapshot.profile.hasLearningEvidence)
            assertEquals(0, snapshot.profile.recordedAttemptCount)
            assertEquals(0, snapshot.profile.newlyMasteredCount)
            assertTrue(snapshot.profile.weaknesses.isEmpty())
            assertTrue(snapshot.profile.projectionIsCurrent)
            assertEquals(null, snapshot.tutorPracticeUnitId)
            assertEquals(null, snapshot.tutorDecision)
            assertEquals(
                listOf("learner:local", "learner:local"),
                database.tutorExposureReconcileLearners,
            )
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun saveTutorExampleMovesFromFourToFiveExactlyOnce() = runBlocking {
        val database = FakeStudyDatabasePort()
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()

            val first = repository.saveTutorExampleMistake()
            val second = repository.saveTutorExampleMistake()

            assertTrue(first is SaveStudyMistakeResult.Saved)
            assertEquals(5, first.entryCount)
            assertTrue(second is SaveStudyMistakeResult.AlreadySaved)
            assertEquals(5, second.entryCount)
            assertEquals(5, repository.snapshot.value.mistakeCount)
            assertTrue(repository.snapshot.value.tutorExampleSaved)
            assertEquals(null, repository.snapshot.value.tutorPracticeUnitId)
            assertEquals(null, repository.snapshot.value.tutorDecision)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun capturedMistakeWithoutAnswerKeyIsScheduledFromItsSavedTranscription() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            addMistake(
                MistakeRecord(
                    entryId = "captured-entry",
                    problemId = "captured-problem",
                    problemRevisionId = "captured-revision",
                    practiceUnitId = "captured-practice-unit",
                    sourceKey = "capture:photo-1",
                    subject = "数学",
                    title = "刚拍下的错题",
                    problemMarkdown = "待模型完成可信转写与分类",
                    status = "ACTIVE",
                    createdAtEpochMillis = 9_999_999_999_999L,
                    nextReviewAtEpochMillis = null,
                    retrievability = null,
                ),
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope, initialFixture = null)

        try {
            repository.initialize()

            val snapshot = repository.snapshot.value
            val captured = snapshot.catalog.single { it.entryId == "captured-entry" }
            assertEquals(StudyDataStatus.READY, snapshot.status)
            assertEquals("captured-entry", snapshot.catalog.first().entryId)
            assertTrue(captured.knowledgeLabels.isEmpty())
            assertEquals(MasteryStatus.UNKNOWN, captured.masteryStatus)
            assertEquals(
                listOf("captured-practice-unit"),
                snapshot.review.scheduledPracticeUnitIds,
            )
            assertEquals(null, repository.teachingArtifact("captured-practice-unit"))
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun repeatedCaptureMovesTheExistingMistakeForwardInReview() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            addMistake(
                MistakeRecord(
                    entryId = "entry-a",
                    problemId = "problem-a",
                    problemRevisionId = "revision-a",
                    practiceUnitId = "unit-a",
                    sourceKey = "capture:a",
                    subject = "MATH",
                    title = "一次拍到的题",
                    problemMarkdown = "题目 A",
                    status = "ACTIVE",
                    createdAtEpochMillis = 1_000,
                    nextReviewAtEpochMillis = null,
                    retrievability = null,
                ),
            )
            addMistake(
                MistakeRecord(
                    entryId = "entry-z",
                    problemId = "problem-z",
                    problemRevisionId = "revision-z",
                    practiceUnitId = "unit-z",
                    sourceKey = "capture:z",
                    subject = "MATH",
                    title = "再次遇到的题",
                    problemMarkdown = "题目 Z",
                    status = "ACTIVE",
                    createdAtEpochMillis = 2_000,
                    nextReviewAtEpochMillis = null,
                    retrievability = null,
                    captureOccurrenceCount = 3,
                ),
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope, initialFixture = null)

        try {
            repository.initialize()

            assertEquals(
                listOf("unit-z", "unit-a"),
                repository.snapshot.value.review.scheduledPracticeUnitIds,
            )
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun capturedReviewSelfReportAdvancesOnceWithoutKnowledgeAttribution() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            addMistake(
                MistakeRecord(
                    entryId = "captured-entry",
                    problemId = "captured-problem",
                    problemRevisionId = "captured-revision",
                    practiceUnitId = "captured-practice-unit",
                    sourceKey = "capture:photo-1",
                    subject = "MATH",
                    title = "函数原题",
                    problemMarkdown = "求函数的单调区间。",
                    status = "ACTIVE",
                    createdAtEpochMillis = 1_000,
                    nextReviewAtEpochMillis = null,
                    retrievability = null,
                    knowledgeNodeIds = setOf("knowledge:function-monotonicity"),
                ),
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope, initialFixture = null)

        try {
            repository.initialize()
            assertEquals(
                setOf("knowledge:function-monotonicity"),
                database.savedPlans.single().queue.single().knowledgeNodeIds,
            )
            val started = requireNotNull(
                repository.startOrResumeReviewSession("captured-start", 2_000),
            )
            val submission = StudyReviewSelfReportSubmission(
                requestId = "captured-self-report",
                presentationId = "captured-presentation",
                practiceUnitId = "captured-practice-unit",
                report = StudyReviewSelfReport.RECALL_COMPLETED,
                durationSeconds = 15,
                occurredAtEpochMillis = 3_000,
            )

            val first = repository.submitReviewSelfReport(
                sessionId = started.sessionId,
                expectedStateVersion = started.stateVersion,
                submission = submission,
            )
            val replay = repository.submitReviewSelfReport(
                sessionId = started.sessionId,
                expectedStateVersion = started.stateVersion,
                submission = submission,
            )

            assertEquals(LearningEvidenceReason.SELF_REPORTED_RECALL, first.evidenceReason)
            assertEquals(StudyReviewSessionStatus.COMPLETED, first.progress.status)
            assertTrue(first.created)
            assertFalse(replay.created)
            assertEquals(first.progress, replay.progress)
            assertEquals(0.35, database.lastAttemptCommand?.evidence?.weight ?: -1.0, 0.0)
            assertTrue(database.lastEvidenceSnapshot?.attributions?.isEmpty() == true)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun projectionFailureClearsInteractiveDecisionsAndReviewSessionProjection() = runBlocking {
        val database = FakeStudyDatabasePort()
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            assertEquals(null, repository.snapshot.value.tutorDecision)
            database.failProjectionReads = true

            assertTrue(runCatching { repository.saveTutorExampleMistake() }.isFailure)

            val failed = repository.snapshot.value
            assertEquals(StudyDataStatus.ERROR, failed.status)
            assertEquals(null, failed.tutorDecision)
            assertEquals(null, failed.tutorPracticeUnitId)
            assertEquals(null, failed.review.activeSessionId)
            assertFalse(failed.profile.projectionIsCurrent)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun learningLedgerHeadChangeRefreshesCurrentQuestionMemoryWithoutAnotherMutation() = runBlocking {
        val database = FakeStudyDatabasePort()
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            val entryBeforeExposure = repository.snapshot.value.catalog.first()
            assertEquals(null, entryBeforeExposure.questionMemory)

            database.publishTutorExposure(entryBeforeExposure.practiceUnitId)
            yield()

            val refreshedEntry = repository.snapshot.value.catalog.single {
                it.practiceUnitId == entryBeforeExposure.practiceUnitId
            }
            assertEquals(1, refreshedEntry.questionMemory?.answerRevealCount)
            assertEquals(StudyDataStatus.READY, repository.snapshot.value.status)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun reviewSessionProgressAndCompletionComeBackFromPersistence() = runBlocking {
        val database = FakeStudyDatabasePort()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstRepository = repository(database, firstScope)
        val startedAt = Instant.parse("2026-01-02T08:05:00Z").toEpochMilli()

        val completed = try {
            firstRepository.initialize()
            val started = requireNotNull(
                firstRepository.startOrResumeReviewSession("start-review", startedAt),
            )
            assertEquals(
                started,
                firstRepository.startOrResumeReviewSession("duplicate-start", startedAt + 1),
            )
            val scheduledPracticeUnitIds =
                firstRepository.snapshot.value.review.scheduledPracticeUnitIds
            assertEquals(started.queueSize, scheduledPracticeUnitIds.size)
            var progress = started
            repeat(started.queueSize) { index ->
                val expectedVersion = progress.stateVersion
                val progressedAt = startedAt + index + 1
                val practiceUnitId = scheduledPracticeUnitIds[index]
                val artifact = requireNotNull(firstRepository.teachingArtifact(practiceUnitId))
                val submission = StudyChoiceSubmission(
                    requestId = "review-choice-$index",
                    presentationId = "review-presentation:${started.sessionId}:$index",
                    practiceUnitId = practiceUnitId,
                    selectedChoiceId = artifact.assessmentItems.single().choices.first().id,
                    responseOrdinal = 1,
                    durationSeconds = index + 1,
                    occurredAtEpochMillis = progressedAt,
                )
                val submitted = firstRepository.submitReviewChoice(
                    sessionId = progress.sessionId,
                    expectedStateVersion = expectedVersion,
                    submission = submission,
                )
                progress = submitted.progress
                assertTrue(submitted.attempt.created)
                assertEquals(index + 1, progress.currentOrdinal)
                assertEquals((index + 1).toLong(), progress.stateVersion)
                val replay = firstRepository.submitReviewChoice(
                    sessionId = progress.sessionId,
                    expectedStateVersion = expectedVersion,
                    submission = submission,
                )
                assertEquals(progress, replay.progress)
                assertFalse(replay.attempt.created)
            }
            assertEquals(StudyReviewSessionStatus.COMPLETED, progress.status)
            assertTrue(firstRepository.snapshot.value.review.completedToday)
            assertEquals(1, firstRepository.snapshot.value.review.completionStreakDays)
            assertEquals(null, firstRepository.snapshot.value.review.activeSessionId)
            progress
        } finally {
            firstRepository.close()
            firstScope.cancel()
        }

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val secondRepository = repository(database, secondScope)
        try {
            secondRepository.initialize()
            val resumed = requireNotNull(
                secondRepository.startOrResumeReviewSession("another-start", startedAt + 100),
            )
            assertEquals(completed, resumed)
            assertTrue(secondRepository.snapshot.value.review.completedToday)
            assertEquals(1, secondRepository.snapshot.value.review.completionStreakDays)
        } finally {
            secondRepository.close()
            secondScope.cancel()
        }
    }

    @Test
    fun reviewAnswerAtomicallyAdvancesAndReplaysAcrossLocalMidnight() = runBlocking {
        val database = FakeStudyDatabasePort()
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val zone = ZoneId.of("Asia/Shanghai")
        val clock = MutableClock(Instant.parse("2026-01-02T15:59:50Z"), zone)
        val repository = repository(database, applicationScope, clock)

        try {
            repository.initialize()
            val started = requireNotNull(
                repository.startOrResumeReviewSession("cross-midnight", clock.millis()),
            )
            val practiceUnitId = repository.snapshot.value.review.scheduledPracticeUnitIds.first()
            val submittedChoice = requireNotNull(repository.teachingArtifact(practiceUnitId))
                .assessmentItems.single().choices.single { choice -> choice.id == "A" }
            clock.moveTo(Instant.parse("2026-01-02T16:00:10Z"))
            val submission = StudyChoiceSubmission(
                requestId = "review-cross-midnight-choice",
                presentationId = "presentation:${started.sessionId}:${started.stateVersion}",
                practiceUnitId = practiceUnitId,
                selectedChoiceId = "A",
                responseOrdinal = 1,
                durationSeconds = 20,
                occurredAtEpochMillis = clock.millis(),
            )

            val first = repository.submitReviewChoice(
                sessionId = started.sessionId,
                expectedStateVersion = started.stateVersion,
                submission = submission,
            )
            val replay = repository.submitReviewChoice(
                sessionId = started.sessionId,
                expectedStateVersion = started.stateVersion,
                submission = submission,
            )

            assertEquals(1, first.progress.currentOrdinal)
            assertEquals(started.sessionId, repository.snapshot.value.review.activeSessionId)
            assertEquals(1, repository.snapshot.value.review.currentOrdinal)
            assertEquals(first.progress, replay.progress)
            assertEquals(first.attempt.attemptId, replay.attempt.attemptId)
            assertFalse(replay.attempt.created)
            assertEquals(
                AttemptSubmittedResponse.Choice(
                    choiceId = submittedChoice.id,
                    choiceMarkdown = submittedChoice.markdown,
                    submittedAtEpochMillis = submission.occurredAtEpochMillis,
                ),
                database.lastAttemptCommand?.submittedResponse,
            )
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    private fun repository(
        database: StudyDatabasePort,
        applicationScope: CoroutineScope,
        clock: Clock = Clock.fixed(
            Instant.parse("2026-01-02T08:00:00Z"),
            ZoneId.of("Asia/Shanghai"),
        ),
        initialFixture: StudySeedBundle? = M1CuratedStudySeed.bundle(includeTutorMistake = false),
    ) = RoomBackedStudyExperienceRepository(
        database = database,
        applicationScope = applicationScope,
        clock = clock,
        studyZoneId = ZoneId.of("Asia/Shanghai"),
        initialFixture = initialFixture,
    )
}

private class MutableClock(
    private var currentInstant: Instant,
    private val currentZone: ZoneId,
) : Clock() {
    override fun getZone(): ZoneId = currentZone

    override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

    override fun instant(): Instant = currentInstant

    fun moveTo(instant: Instant) {
        currentInstant = instant
    }
}

@Suppress("OVERRIDE_DEPRECATION")
private class FakeStudyDatabasePort : StudyDatabasePort {
    private val mistakes = MutableStateFlow<List<MistakeRecord>>(emptyList())
    private val learningLedgerHead = MutableStateFlow(0L)
    val knowledgeGroundingSummaries =
        MutableStateFlow<List<KnowledgeGroundingSummaryRecord>>(emptyList())
    val reviewedKnowledgeCoverage =
        MutableStateFlow<List<ReviewedKnowledgeCoverageRecord>>(emptyList())
    private val entries = linkedMapOf<String, MistakeRecord>()
    private val problemIds = linkedSetOf<String>()
    private val latestSessions = mutableMapOf<String, ReviewSessionRecord>()
    private val sessionRevisions = mutableMapOf<Pair<String, Long>, ReviewSessionRecord>()
    private val advanceProofs = mutableMapOf<String, AttemptAdvanceProofRecord>()
    private val advanceReceipts = mutableMapOf<String, ReviewSessionAdvanceReceipt>()
    private val evidenceSnapshots = mutableMapOf<String, AssessmentEvidenceSnapshot>()
    private val attemptsBySubmission = mutableMapOf<String, AttemptWriteResult>()
    private var nextEventSequence = 1L
    private var persistedLearnerSnapshot: PersistedLearnerSnapshot? = null
    var lastAttemptCommand: AttemptWriteCommand? = null
        private set
    var lastEvidenceSnapshot: AssessmentEvidenceSnapshot? = null
        private set
    var seedCallCount: Int = 0
        private set
    var failProjectionReads: Boolean = false
    val savedPlans = mutableListOf<ReviewPlanBundle>()
    val tutorExposureReconcileLearners = mutableListOf<String>()

    val problemCount: Int
        get() = problemIds.size

    fun addMistake(mistake: MistakeRecord) {
        entries[mistake.entryId] = mistake
        problemIds += mistake.problemId
        mistakes.value = entries.values.toList()
    }

    fun publishTutorExposure(practiceUnitId: String) {
        val outcome = TutorAnswerExposureOutcome(
            outcomeId = "tutor-exposure-outcome-1",
            exposureId = "tutor-exposure-1",
            sessionId = "current-tutor-session",
            questionDocumentId = "current-question-document",
            questionRevisionNumber = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            problemRevisionId = "current-problem-revision",
            practiceUnitId = practiceUnitId,
            occurredAtEpochMillis = 1_000,
            eventSequence = 1,
        )
        val snapshot = LearningProjector().replay(
            learnerId = "learner:local",
            ledger = listOf(outcome),
        ).snapshot
        persistedLearnerSnapshot = PersistedLearnerSnapshot(
            projectionName = "learner-state-v1",
            stateVersion = 1,
            knownLedgerHeadSequence = 1,
            snapshot = snapshot,
        )
        learningLedgerHead.value = 1
    }

    override fun observeMistakes(): Flow<List<MistakeRecord>> = mistakes

    override fun observeModelTask(requestId: String): Flow<ModelTaskSnapshot?> =
        MutableStateFlow(null)

    override suspend fun readModelTask(requestId: String): ModelTaskSnapshot? = null

    override suspend fun createModelTask(
        command: CreateModelTaskCommand,
    ): ModelTaskWriteResult = error("Model tasks are outside this study-repository fake")

    override suspend fun transitionModelTask(
        command: TransitionModelTaskCommand,
    ): ModelTaskWriteResult = error("Model tasks are outside this study-repository fake")

    override fun observePendingProblemDraftCount(): Flow<Int> = MutableStateFlow(0)

    override fun observeLearningLedgerHead(learnerId: String): Flow<Long> = learningLedgerHead

    override fun observePendingKnowledgeGroundingSummaries(
        limit: Int,
    ): Flow<List<KnowledgeGroundingSummaryRecord>> = knowledgeGroundingSummaries

    override fun observeReviewedKnowledgeCoverage(): Flow<List<ReviewedKnowledgeCoverageRecord>> =
        reviewedKnowledgeCoverage

    override fun observeReviewPlan(reviewPlanId: String): Flow<ReviewPlanBundle?> =
        MutableStateFlow(savedPlans.lastOrNull { it.plan.reviewPlanId == reviewPlanId })

    override fun observeReviewPlanForSession(sessionId: String): Flow<ReviewPlanBundle?> =
        MutableStateFlow(
            savedPlans.lastOrNull { plan ->
                plan.activeSession?.reviewSessionId == sessionId ||
                    plan.latestSession?.reviewSessionId == sessionId
            },
        )

    override fun observeActiveReviewPlan(learnerId: String): Flow<ReviewPlanBundle?> =
        MutableStateFlow(
            savedPlans.lastOrNull { plan ->
                plan.plan.learnerId == learnerId && plan.activeSession != null
            },
        )

    override fun observeCurrentReviewPlan(
        learnerId: String,
        localDayEpochDay: Long,
        timeZoneId: String,
    ): Flow<ReviewPlanBundle?> = MutableStateFlow(
        savedPlans.lastOrNull { plan ->
            plan.isCurrent &&
                plan.plan.learnerId == learnerId &&
                plan.plan.localDayEpochDay == localDayEpochDay &&
                plan.plan.timeZoneId == timeZoneId
        },
    )

    override fun observeCompletedReviewLocalDays(
        learnerId: String,
        limit: Int,
    ): Flow<List<Long>> = MutableStateFlow(
        savedPlans.asReversed()
            .asSequence()
            .filter { plan ->
                plan.plan.learnerId == learnerId &&
                    plan.latestSession?.status == StudyDbValue.ReviewStatus.COMPLETED
            }
            .map { it.plan.localDayEpochDay }
            .distinct()
            .take(limit)
            .toList(),
    )

    override suspend fun countMistakes(): Int = entries.size

    override suspend fun reconcileTutorAnswerExposures(learnerId: String, limit: Int): Int {
        tutorExposureReconcileLearners += learnerId
        return 0
    }

    override suspend fun findMistakeBySourceKey(sourceKey: String): MistakeRecord? =
        entries.values.firstOrNull { it.sourceKey == sourceKey }

    override suspend fun createProblemDraft(
        command: CreateProblemDraftCommand,
    ): ProblemDraftWriteResult = error("Capture is outside this study-repository fake")

    override suspend fun reviseProblemDraft(
        command: ReviseProblemDraftCommand,
    ): ProblemDraftWriteResult = error("Capture is outside this study-repository fake")

    override suspend fun readProblemDraft(draftId: String): ProblemDraftRecord? = null

    override suspend fun commitProblemDraft(
        command: CommitProblemDraftCommand,
    ): CommitProblemDraftResult = error("Capture is outside this study-repository fake")

    override suspend fun seedFixture(bundle: StudySeedBundle): SeedResult {
        seedCallCount++
        val insertedProblems = bundle.problems.count { problemIds.add(it.problemId) }
        val problemsById = bundle.problems.associateBy { it.problemId }
        val revisionsById = bundle.revisions.associateBy { it.revisionId }
        val unitsById = bundle.practiceUnits.associateBy { it.practiceUnitId }
        var insertedEntries = 0
        bundle.errorBookEntries.forEach { entry ->
            if (entry.entryId !in entries) {
                val problem = requireNotNull(problemsById[entry.problemId])
                val revision = requireNotNull(revisionsById[entry.currentRevisionId])
                val unit = requireNotNull(unitsById[entry.practiceUnitId])
                entries[entry.entryId] = MistakeRecord(
                    entryId = entry.entryId,
                    problemId = entry.problemId,
                    problemRevisionId = entry.currentRevisionId,
                    practiceUnitId = entry.practiceUnitId,
                    sourceKey = entry.sourceKey,
                    subject = problem.subject,
                    title = revision.title,
                    problemMarkdown = unit.promptMarkdown,
                    status = entry.status,
                    createdAtEpochMillis = entry.acceptedAtEpochMillis,
                    nextReviewAtEpochMillis = null,
                    retrievability = null,
                )
                insertedEntries++
            }
        }
        mistakes.value = entries.values.toList()
        return SeedResult(insertedProblems, insertedEntries)
    }

    override suspend fun saveAssessmentItemSnapshot(item: AssessmentItemSnapshotSeedRecord) = Unit

    override suspend fun saveAssessmentEvidenceSnapshot(snapshot: AssessmentEvidenceSnapshot) {
        evidenceSnapshots[snapshot.snapshotId] = snapshot
        lastEvidenceSnapshot = snapshot
    }

    override suspend fun appendAssessmentEvent(event: AssessmentEventSeedRecord) = Unit

    override suspend fun recordAttempt(command: AttemptWriteCommand): AttemptWriteResult {
        lastAttemptCommand = command
        attemptsBySubmission[command.submissionId]?.let { return it.copy(created = false) }
        val attempt = Attempt(
            attemptId = command.attemptId,
            presentationId = command.presentationId,
            responseOrdinal = 1,
            assessmentSnapshot = requireNotNull(evidenceSnapshots[command.assessmentSnapshotId]),
            evidence = command.evidence,
            problemMemoryOutcome = command.problemMemoryOutcome,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
            durationSeconds = command.durationSeconds,
            studyDay = command.studyDay,
            eventSequence = nextEventSequence++,
            submittedResponse = command.submittedResponse,
        )
        val result = AttemptWriteResult(
            submissionId = command.submissionId,
            created = true,
            attempt = attempt,
            canonicalFingerprint = "fake:${command.attemptId}",
            outboxId = "outbox:${command.attemptId}",
        )
        attemptsBySubmission[command.submissionId] = result
        addAdvanceProof(
            AttemptAdvanceProofRecord(
                learnerId = command.learnerId,
                attemptId = attempt.attemptId,
                submissionId = command.submissionId,
                presentationId = attempt.presentationId,
                practiceUnitId = attempt.practiceUnitId,
                occurredAtEpochMillis = attempt.occurredAtEpochMillis,
            ),
        )
        return result
    }

    override suspend fun recordReviewAttempt(
        command: ReviewAttemptWriteCommand,
    ): ReviewAttemptWriteResult {
        val attempt = recordAttempt(command.attempt)
        return try {
            val advance = advanceReviewSession(
                command = ReviewSessionAdvanceCommand(
                    sessionId = command.sessionId,
                    expectedStateVersion = command.expectedStateVersion,
                    reviewQueueItemId = command.reviewQueueItemId,
                    practiceUnitId = command.practiceUnitId,
                    attemptId = attempt.attempt.attemptId,
                    submissionId = attempt.submissionId,
                    presentationId = attempt.attempt.presentationId,
                    occurredAtEpochMillis = attempt.attempt.occurredAtEpochMillis,
                ),
                attemptCreatedInCurrentTransaction = attempt.created,
            )
            ReviewAttemptWriteResult(attempt = attempt, advance = advance)
        } catch (failure: Throwable) {
            if (attempt.created) {
                attemptsBySubmission.remove(attempt.submissionId)
                advanceProofs.remove(attempt.attempt.attemptId)
                nextEventSequence--
            }
            throw failure
        }
    }

    override suspend fun recordAnswerReveal(command: AnswerRevealWriteCommand): AnswerRevealWriteResult =
        error("recordAnswerReveal is not used by these focused tests")

    override suspend fun reconcileAnswerRevealOutcomes(
        learnerId: String,
        limit: Int,
    ): List<AnswerRevealWriteResult> = emptyList()

    override suspend fun appendAttemptCorrection(
        correction: AttemptCorrectionRecord,
    ): AttemptCorrectionResult = error("appendAttemptCorrection is not used by these focused tests")

    override suspend fun findAttemptPersistence(submissionId: String): AttemptPersistenceRecord? = null

    fun addAdvanceProof(proof: AttemptAdvanceProofRecord) {
        advanceProofs[proof.attemptId] = proof
    }

    override suspend fun findAttemptAdvanceProof(attemptId: String): AttemptAdvanceProofRecord? =
        advanceProofs[attemptId]

    override suspend fun markRelationsStaleForRevision(
        problemRevisionId: String,
        updatedAtEpochMillis: Long,
    ): Int = 0

    override suspend fun loadProjectionBatch(
        projectionName: String,
        learnerId: String,
        limit: Int,
    ): ProjectionBatch {
        if (failProjectionReads) error("forced projection read failure")
        val checkpoint = persistedLearnerSnapshot?.snapshot?.checkpoint?.lastSequence ?: 0L
        return ProjectionBatch(
            projectionName = projectionName,
            learnerId = learnerId,
            previousCheckpoint = checkpoint,
            ledgerHeadSequence = learningLedgerHead.value,
            events = emptyList(),
            authoritativePresentationStates = emptyMap(),
            stopReason = ProjectionBatchStopReason.END_OF_LEDGER,
        )
    }

    override suspend fun loadLearningLedger(learnerId: String): LearningLedgerRead =
        LearningLedgerRead(
            learnerId = learnerId,
            validPrefix = emptyList(),
            status = LearningLedgerReadStatus.COMPLETE,
        )

    override suspend fun readCurrentLearnerSnapshot(
        projectionName: String,
        learnerId: String,
    ): PersistedLearnerSnapshot? = persistedLearnerSnapshot

    override suspend fun commitProjection(commit: ProjectionCommit): PersistedLearnerSnapshot =
        error("commitProjection is not used by these focused tests")

    override suspend fun saveReviewPlan(bundle: ReviewPlanBundle) {
        val session = latestSessions[bundle.plan.reviewPlanId]
        val stored = bundle.copy(
            activeSession = session?.takeIf { it.status == StudyDbValue.ReviewStatus.IN_PROGRESS },
            latestSession = session,
        )
        savedPlans.removeAll { it.plan.reviewPlanId == bundle.plan.reviewPlanId }
        savedPlans += stored
    }

    override suspend fun saveReviewSession(session: ReviewSessionRecord) {
        val existing = latestSessions[session.reviewPlanId]
        if (existing == session) return
        if (existing != null) {
            require(session.reviewSessionId == existing.reviewSessionId)
            require(session.stateVersion == existing.stateVersion + 1)
        } else {
            require(session.stateVersion == 0L)
        }
        latestSessions[session.reviewPlanId] = session
        sessionRevisions[session.reviewSessionId to session.stateVersion] = session
        val index = savedPlans.indexOfLast { it.plan.reviewPlanId == session.reviewPlanId }
        require(index >= 0)
        savedPlans[index] = savedPlans[index].copy(
            activeSession = session.takeIf { it.status == StudyDbValue.ReviewStatus.IN_PROGRESS },
            latestSession = session,
        )
    }

    override suspend fun advanceReviewSession(
        command: ReviewSessionAdvanceCommand,
    ): ReviewSessionAdvanceResult = advanceReviewSession(
        command = command,
        attemptCreatedInCurrentTransaction = false,
    )

    private fun advanceReviewSession(
        command: ReviewSessionAdvanceCommand,
        attemptCreatedInCurrentTransaction: Boolean,
    ): ReviewSessionAdvanceResult {
        advanceReceipts[command.attemptId]?.let { receipt ->
            require(receipt.matches(command))
            val replayed = requireNotNull(sessionRevisions[receipt.sessionId to receipt.toVersion])
            return ReviewSessionAdvanceResult(
                created = false,
                session = replayed,
                receipt = receipt,
            )
        }
        require(attemptCreatedInCurrentTransaction)

        val current = latestSessions.values.single { it.reviewSessionId == command.sessionId }
        require(current.status == StudyDbValue.ReviewStatus.IN_PROGRESS)
        require(current.stateVersion == command.expectedStateVersion)
        val plan = savedPlans.single { it.plan.reviewPlanId == current.reviewPlanId }
        val queue = plan.queue.sortedBy { it.ordinal }
        val queueItem = queue.single { it.reviewQueueItemId == command.reviewQueueItemId }
        require(queueItem.ordinal == current.currentOrdinal)
        require(queueItem.practiceUnitId == command.practiceUnitId)
        val proof = requireNotNull(advanceProofs[command.attemptId])
        require(proof.submissionId == command.submissionId)
        require(proof.presentationId == command.presentationId)
        require(proof.practiceUnitId == command.practiceUnitId)
        require(proof.occurredAtEpochMillis == command.occurredAtEpochMillis)

        val nextOrdinal = current.currentOrdinal + 1
        val completed = nextOrdinal == queue.size
        val next = current.copy(
            status = if (completed) {
                StudyDbValue.ReviewStatus.COMPLETED
            } else {
                StudyDbValue.ReviewStatus.IN_PROGRESS
            },
            currentOrdinal = nextOrdinal,
            stateVersion = current.stateVersion + 1,
            lastActiveAtEpochMillis = command.occurredAtEpochMillis,
            completedAtEpochMillis = command.occurredAtEpochMillis.takeIf { completed },
        )
        val receipt = ReviewSessionAdvanceReceipt(
            sessionId = command.sessionId,
            fromVersion = command.expectedStateVersion,
            toVersion = next.stateVersion,
            reviewQueueItemId = command.reviewQueueItemId,
            practiceUnitId = command.practiceUnitId,
            attemptId = command.attemptId,
            submissionId = command.submissionId,
            presentationId = command.presentationId,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
        )
        latestSessions[current.reviewPlanId] = next
        sessionRevisions[next.reviewSessionId to next.stateVersion] = next
        advanceReceipts[command.attemptId] = receipt
        val planIndex = savedPlans.indexOf(plan)
        savedPlans[planIndex] = plan.copy(
            activeSession = next.takeIf { it.status == StudyDbValue.ReviewStatus.IN_PROGRESS },
            latestSession = next,
        )
        return ReviewSessionAdvanceResult(created = true, session = next, receipt = receipt)
    }

    override suspend fun readAssessmentSnapshotP0(
        assessmentItemSnapshotId: String,
    ): AssessmentItemSnapshotSeedRecord? = null

    override suspend fun readAttemptP0(attemptId: String): PersistedAttemptP0? = null

    override suspend fun readCorrectionP0(correctionId: String): PersistedCorrectionP0? = null

    override suspend fun readAnswerRevealP0(outcomeId: String): PersistedAnswerRevealP0? = null

    override fun close() = Unit

    private fun ReviewSessionAdvanceReceipt.matches(command: ReviewSessionAdvanceCommand): Boolean =
        sessionId == command.sessionId &&
            fromVersion == command.expectedStateVersion &&
            reviewQueueItemId == command.reviewQueueItemId &&
            practiceUnitId == command.practiceUnitId &&
            attemptId == command.attemptId &&
            submissionId == command.submissionId &&
            presentationId == command.presentationId &&
            occurredAtEpochMillis == command.occurredAtEpochMillis
}
