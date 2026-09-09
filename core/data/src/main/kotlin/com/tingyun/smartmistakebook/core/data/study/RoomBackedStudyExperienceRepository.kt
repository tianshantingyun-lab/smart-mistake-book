package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.model.TeachingAdvisoryRecord
import com.tingyun.smartmistakebook.core.database.ReviewAttemptWriteCommand
import com.tingyun.smartmistakebook.core.database.ReviewSessionRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.domain.OptimalRetention
import com.tingyun.smartmistakebook.core.domain.KnowledgeQuizFeedbackResult
import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewSessionPlan
import com.tingyun.smartmistakebook.core.database.StudySeedBundle
import com.tingyun.smartmistakebook.core.domain.ChatEvidenceGateCalibration
import com.tingyun.smartmistakebook.core.domain.ExamCalendarEntry
import com.tingyun.smartmistakebook.core.domain.ForgettingCurve
import com.tingyun.smartmistakebook.core.domain.ForgettingCurveAlgorithm
import com.tingyun.smartmistakebook.core.domain.FsrsMemoryUpdateModel
import com.tingyun.smartmistakebook.core.domain.FsrsParameterOptimizer
import com.tingyun.smartmistakebook.core.domain.FsrsScheduleMath
import com.tingyun.smartmistakebook.core.domain.HLRPredictionAuditService
import com.tingyun.smartmistakebook.core.domain.LearningProjector
import com.tingyun.smartmistakebook.core.domain.LogDurationModel
import com.tingyun.smartmistakebook.core.domain.PredictionAuditSink
import com.tingyun.smartmistakebook.core.domain.ReviewPlanner
import com.tingyun.smartmistakebook.core.domain.LegacyExponentialMemoryUpdateModel
import com.tingyun.smartmistakebook.core.domain.ReviewPlannerV2
import com.tingyun.smartmistakebook.core.domain.KnowledgeQuestionLatticeRow
import com.tingyun.smartmistakebook.core.domain.PlannedReasonCalibration
import com.tingyun.smartmistakebook.core.domain.SourceCalibration
import com.tingyun.smartmistakebook.core.domain.SchedulingEvaluationReport
import com.tingyun.smartmistakebook.core.domain.SchedulingOptions
import com.tingyun.smartmistakebook.core.domain.SchedulingSettingsStore
import com.tingyun.smartmistakebook.core.domain.SaveTutorProblemCommand
import com.tingyun.smartmistakebook.core.domain.SaveTutorProblemReceipt
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealRequest
import com.tingyun.smartmistakebook.core.domain.StudyReviewRatingSubmission
import com.tingyun.smartmistakebook.core.domain.StudyReviewRatingSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealResult
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmission
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyDataStatus
import com.tingyun.smartmistakebook.core.domain.StudyExperienceRepository
import com.tingyun.smartmistakebook.core.domain.StudyExperienceSnapshot
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeCoverageOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewChoiceSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReportSubmission
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReportSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionProgress
import com.tingyun.smartmistakebook.core.model.CalibrationReport
import com.tingyun.smartmistakebook.core.model.LearningModelVersion
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Application-scoped repository for the curated M1 study loop.
 *
 * The database owns immutable learning facts and sequence/CAS authority. This class owns the one
 * composed snapshot consumed by all four feature tabs.
 */
class RoomBackedStudyExperienceRepository(
    private val database: StudyDatabasePort,
    applicationScope: CoroutineScope,
    private val learnerId: String = DEFAULT_LEARNER_ID,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val studyZoneId: ZoneId = clock.zone,
    private val reviewTimeBudgetSeconds: Int = DEFAULT_REVIEW_TIME_BUDGET_SECONDS,
    /**
     * Feature flag for the review planner (audit §3.4 rollback switch): when true
     * (default), plans are produced by the V2 planner with beam search, hard
     * sequencing constraints and the personalized duration model; flipping it
     * back to false restores the audited V1 greedy planner without any other
     * code change.
     */
    private val useReviewPlannerV2: Boolean = DEFAULT_USE_REVIEW_PLANNER_V2,
    /**
     * Scheduling options (spec mastery-scheduling 2.4 / 2.20): desired
     * retention plus the FSRS kill switch. Read at construction so a single
     * session's projection model stays stable; a flip applies on next launch.
     */
    private val schedulingOptions: SchedulingOptions = SchedulingOptions(),
    private val schedulingSettingsStore: SchedulingSettingsStore? = null,
    /** Locally optimized FSRS-6 parameters (spec 2.11); null keeps the verified defaults. */
    private val optimizedFsrsParameters: DoubleArray? = null,
    private val closeDatabaseOnClose: Boolean = false,
    private val initialFixture: StudySeedBundle? = null,
    private val fixtureSource: StudyFixtureSource = StudyFixtureRegistry.source,
) : StudyExperienceRepository {
    private val operationMutex = Mutex()
    private val _snapshot = MutableStateFlow(StudyExperienceSnapshot())
    private val fixtureBundle = fixtureSource.bundle(includeTutorMistake = true)
    private val curatedProblemIds = fixtureBundle?.problems
        ?.mapTo(hashSetOf()) { it.problemId }
        ?: hashSetOf()
    private val knowledgeNames = fixtureBundle?.knowledgeNodes
        ?.associate { it.knowledgeNodeId to it.displayName }
        ?: emptyMap()
    private val forgettingCurve = ForgettingCurve(
        algorithm = if (schedulingOptions.useFsrsScheduling) {
            ForgettingCurveAlgorithm.FSRS6_POWER_LAW
        } else {
            ForgettingCurveAlgorithm.LEGACY_EXPONENTIAL
        },
    )
    private val reviewPlanner = ReviewPlanner()
    /**
     * ONE shared duration model (spec `batch-intake-spec.md` §6 L1 rollout):
     * fed by the submission paths below, consumed by BOTH the review planner
     * (per-candidate modeled duration) and the intake introduction decision
     * (L1 personalized estimate for never-attempted questions). A single
     * instance is what makes recorded samples reach the queries.
     */
    private val durationModel = LogDurationModel()
    private val reviewPlannerV2 = ReviewPlannerV2(
        forgettingCurve = ForgettingCurve(
            algorithm = if (schedulingOptions.useFsrsScheduling) {
                ForgettingCurveAlgorithm.FSRS6_POWER_LAW
            } else {
                ForgettingCurveAlgorithm.LEGACY_EXPONENTIAL
            },
        ),
        durationModel = durationModel,
    )
    private val learningProjector = LearningProjector(
        forgettingCurve = forgettingCurve,
        memoryUpdateModel = if (schedulingOptions.useFsrsScheduling) {
            FsrsMemoryUpdateModel(
                parameters = optimizedFsrsParameters ?: FsrsScheduleMath.DEFAULT_PARAMETERS,
                desiredRetention = schedulingOptions.desiredRetention,
            )
        } else {
            LegacyExponentialMemoryUpdateModel(
                forgettingCurve = ForgettingCurve(),
            )
        },
    )
    private val projectionDrainer = StudyProjectionDrainer(
        database = database,
        learnerId = learnerId,
        learningProjector = learningProjector,
    )
    private val predictionAuditService = HLRPredictionAuditService()

    private val reviewLogSink = ReviewLogSink(
        database = database,
        learnerId = learnerId,
        clock = clock,
        studyZoneId = studyZoneId,
    )
    private val predictionAuditSink: PredictionAuditSink = RoomPredictionAuditSink(database)
    private val writeContext = StudyWriteContext(
        database = database,
        learnerId = learnerId,
        studyZoneId = studyZoneId,
        fixtureSource = fixtureSource,
    )
    private val visualInteractionIngestor = VisualInteractionIngestor(
        database = database,
        learnerId = learnerId,
        reviewLogSink = reviewLogSink,
        writeContext = writeContext,
    )
    private val submissionPreparer = StudySubmissionPreparer(
        database = database,
        learnerId = learnerId,
        fixtureSource = fixtureSource,
        reviewLogSink = reviewLogSink,
        writeContext = writeContext,
    )
    private val ratingSubmissionService = StudyRatingSubmissionService(
        database = database,
        learnerId = learnerId,
        durationModel = durationModel,
        reviewLogSink = reviewLogSink,
        writeContext = writeContext,
        submissionPreparer = submissionPreparer,
        learnerSnapshot = { currentLearnerSnapshot() },
    )
    private val advisoryStore = StudyAdvisoryStore(
        database = database,
        learnerId = learnerId,
        clock = clock,
    )
    private val quizFeedbackWriter = KnowledgeQuizFeedbackWriter(
        database = database,
        learnerId = learnerId,
    )
    private val calibration = StudySchedulingCalibration(
        database = database,
        learnerId = learnerId,
        reviewLogSink = reviewLogSink,
        predictionAuditService = predictionAuditService,
        schedulingSettingsStore = schedulingSettingsStore,
        clock = clock,
        learnerSnapshot = { currentLearnerSnapshot() },
    )
    private val plannerService = StudyReviewPlannerService(
        database = database,
        learnerId = learnerId,
        studyZoneId = studyZoneId,
        clock = clock,
        reviewTimeBudgetSeconds = reviewTimeBudgetSeconds,
        useReviewPlannerV2 = useReviewPlannerV2,
        fixtureSource = fixtureSource,
        reviewPlanner = reviewPlanner,
        reviewPlannerV2 = reviewPlannerV2,
        durationModel = durationModel,
        reviewLogSink = reviewLogSink,
        schedulingSettingsStore = schedulingSettingsStore,
        predictionAuditService = predictionAuditService,
        predictionAuditSink = predictionAuditSink,
        learnerSnapshot = { currentLearnerSnapshot() },
    )
    private var initialized = false
    private var latestMistakes: List<MistakeRecord> = emptyList()
    private var latestPendingCorrectionCount: Int = 0
    private var latestKnowledgeCoverage = StudyKnowledgeCoverageOverview()

    private val answerRevealService = StudyAnswerRevealService(
        database = database,
        learnerId = learnerId,
        fixtureSource = fixtureSource,
        reviewLogSink = reviewLogSink,
        writeContext = writeContext,
        learnerSnapshot = { currentLearnerSnapshot() },
    )
    private val snapshotBuilder = StudySnapshotBuilder(
        database = database,
        learnerId = learnerId,
        studyZoneId = studyZoneId,
        fixtureSource = fixtureSource,
        knowledgeNames = knowledgeNames,
        curatedProblemIds = curatedProblemIds,
        forgettingCurve = forgettingCurve,
        plannerService = plannerService,
        learnerSnapshot = { currentLearnerSnapshot() },
    )
    override val snapshot: StateFlow<StudyExperienceSnapshot> = _snapshot.asStateFlow()

    init {
        require(learnerId.isNotBlank()) { "Learner id must not be blank" }
        require(reviewTimeBudgetSeconds > 0) { "Review time budget must be positive" }
    }

    private fun observeKnowledgeCoverageOverview() = combine(
        database.observeReviewedKnowledgeCoverage(),
        database.observePendingKnowledgeGroundingSummaries(limit = 64),
    ) { reviewedCoverage, pendingGaps ->
        reviewedCoverage.toKnowledgeCoverageOverview(pendingGaps)
    }.distinctUntilChanged()

    private val observationJobs = StudyExperienceObservationJobs(
        database = database,
        learnerId = learnerId,
        mutex = operationMutex,
        scope = applicationScope,
        coverageFlow = observeKnowledgeCoverageOverview(),
        onMistakes = { mistakes ->
            latestMistakes = mistakes
            if (initialized) {
                try {
                    publishReadySnapshot(mistakes)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    publishFailure(failure)
                }
            }
        },
        onPendingDraftCount = { count ->
            latestPendingCorrectionCount = count
            if (initialized) {
                try {
                    publishReadySnapshot(latestMistakes)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    publishFailure(failure)
                }
            }
        },
        onLedgerChanged = {
            if (initialized) {
                try {
                    publishReadySnapshot(latestMistakes)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    publishFailure(failure)
                }
            }
        },
        onCoverage = { coverage ->
            latestKnowledgeCoverage = coverage
            if (initialized) {
                _snapshot.value = _snapshot.value.copy(knowledgeCoverage = coverage)
            }
        },
        onFailure = { failure -> publishFailure(failure) },
    )

    override suspend fun initialize() {
        runOperation {
            if (!initialized) initialFixture?.let { database.seedFixture(it) }
            database.reconcileTutorAnswerExposures(learnerId)
            latestMistakes = database.observeMistakes().first()
            latestPendingCorrectionCount = database.observePendingProblemDraftCount().first()
            latestKnowledgeCoverage = observeKnowledgeCoverageOverview().first()
            // L1 warm-up (spec batch-intake-spec §6): replay recent real-answer
            // durations into the shared duration model so personalization is
            // available from the first plan after a restart, not only after
            // fresh submissions accumulate.
            val subjectByUnit = latestMistakes.associate { it.practiceUnitId to it.subject }
            reviewLogSink.observedAttemptDurations().forEach { (practiceUnitId, seconds) ->
                subjectByUnit[practiceUnitId]?.let { subject ->
                    durationModel.record(
                        learnerId = learnerId,
                        subjectId = subject,
                        itemType = null,
                        difficulty = 5.0, // unused dimension; kept for API stability
                        durationSeconds = seconds,
                    )
                }
            }
            initialized = true
            try {
                visualInteractionIngestor.ingestPending(latestMistakes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Visual-evidence ingestion degrades silently, mirroring the
                // shadow audit loop: it must never block startup (audit §12).
            }
            publishReadySnapshot(latestMistakes)
        }
    }

    override suspend fun saveTutorProblem(command: SaveTutorProblemCommand): SaveTutorProblemReceipt =
        runOperation {
            val committedAtEpochMillis = clock.millis()

            // Derive the draft owning the current problem. conversationId is
            // treated as the draft/session id as the tutor anchor.
            val draftId = command.conversationId
            val problemId = command.problemRevisionId ?: command.ephemeralProblemId

            // Idempotency check: if this exact revision is already in the library,
            // return AlreadySaved without mutating anything.
            val exactRevisionId = command.problemRevisionId
            if (exactRevisionId != null) {
                val existing = database.readExactMistakeDetail(
                    entryId = draftId,
                    problemId = problemId ?: "",
                    problemRevisionId = exactRevisionId,
                )
                if (existing != null) {
                    return@runOperation SaveTutorProblemReceipt.AlreadySaved(
                        problemId = exactRevisionId,
                        entryCount = database.countMistakes(),
                    )
                }
            }

            // Read the draft that carries the current question revision.
            val draft = database.readProblemDraft(draftId)
                ?: return@runOperation SaveTutorProblemReceipt.ReferenceNotFound(
                    reason = "No problem draft found for conversation $draftId",
                )
            val revision = draft.currentRevision
            val question = revision.questionDocument

            // Validate the question is safe to commit.
            val issues = CapturedQuestionDocumentValidator.validateForCommit(question)
            if (issues.isNotEmpty()) {
                return@runOperation SaveTutorProblemReceipt.ReferenceNotFound(
                    reason = "Question is not ready to save: ${issues.first().code}",
                )
            }

            val commitCommandId = "commit-$draftId-${System.nanoTime()}"
            val errorBookEntryId = "entry-$draftId-${System.nanoTime()}"
            val practiceUnitId = draftId

            val commitResult = database.commitTutorSession(
                com.tingyun.smartmistakebook.core.database.CommitTutorSessionCommand(
                    sessionId = draftId,
                    commit = com.tingyun.smartmistakebook.core.database.CommitProblemDraftCommand(
                        commandId = commitCommandId,
                        draftId = draftId,
                        expectedRevisionNumber = revision.revisionNumber,
                        problemId = revision.draftId,
                        problemRevisionId = "${draftId}-rev-${revision.revisionNumber}",
                        practiceUnitId = practiceUnitId,
                        errorBookEntryId = errorBookEntryId,
                        estimatedSeconds = 60,
                        committedAtEpochMillis = committedAtEpochMillis,
                    ),
                ),
            )

            latestMistakes = database.observeMistakes().first()
            initialized = true
            publishReadySnapshot(latestMistakes)

            if (commitResult.created) {
                SaveTutorProblemReceipt.Saved(
                    problemId = revision.draftId,
                    entryCount = database.countMistakes(),
                )
            } else {
                SaveTutorProblemReceipt.AlreadySaved(
                    problemId = revision.draftId,
                    entryCount = database.countMistakes(),
                )
            }
        }

    override suspend fun teachingArtifact(practiceUnitId: String): VerifiedTeachingArtifact? =
        fixtureSource.teachingArtifactForPracticeUnit(practiceUnitId)

    override suspend fun submitChoice(
        submission: StudyChoiceSubmission,
    ): StudyChoiceSubmissionResult = runOperation {
        val prepared = submissionPreparer.prepareChoiceSubmission(submission)
        val priorMemory = currentLearnerSnapshot().problemMemoryStates
            ?.get(submission.practiceUnitId)
        database.saveAssessmentEvidenceSnapshot(prepared.evidenceSnapshot)
        val writeResult = database.recordAttempt(prepared.command)
        if (writeResult.created) {
            reviewLogSink.record(
                practiceUnitId = submission.practiceUnitId,
                evidence = prepared.command.evidence,
                occurredAtEpochMillis = submission.occurredAtEpochMillis,
                durationSeconds = submission.durationSeconds,
                studyDay = prepared.command.studyDay,
                sourceKind = ReviewLogSink.SOURCE_KIND_ATTEMPT,
                sourceId = writeResult.attempt.attemptId,
                priorMemory = priorMemory,
                scrollUpCount = submission.scrollUpCount,
                awayMillis = submission.awayMillis,
                // Answer changing (spec 2.14): every retry is one edit of
                // the submitted answer for this presentation.
                editCount = (submission.responseOrdinal - 1).coerceAtLeast(0),
                interruptionCount = submission.interruptionCount,
            )
        }
        backfillPredictionOutcome(
            practiceUnitId = submission.practiceUnitId,
            wasIndependentCorrect = prepared.isCorrect,
            observedAtEpochMillis = submission.occurredAtEpochMillis,
            responseLatencyMs = submission.durationSeconds * 1000L,
            hintCount = submission.hintCount,
        )
        latestMistakes = database.observeMistakes().first()
        // L1 rollout (spec batch-intake-spec §6): feed real answer attempts
        // into the shared duration model (bucket = learner × subject) AFTER
        // the snapshot refresh so the subject lookup succeeds.
        recordObservedDuration(
            practiceUnitId = submission.practiceUnitId,
            durationSeconds = submission.durationSeconds.toDouble(),
        )
        initialized = true
        publishReadySnapshot(latestMistakes)
        StudyChoiceSubmissionResult(
            attemptId = writeResult.attempt.attemptId,
            created = writeResult.created,
            isCorrect = prepared.isCorrect,
            evidenceReason = writeResult.attempt.evidence.reason,
        )
    }

    override suspend fun submitReviewChoice(
        sessionId: String,
        expectedStateVersion: Long,
        submission: StudyChoiceSubmission,
    ): StudyReviewChoiceSubmissionResult = runOperation {
        require(sessionId.isNotBlank()) { "Review session id must not be blank" }
        require(expectedStateVersion >= 0) { "Expected review-session version must not be negative" }
        val reviewPlan = requireNotNull(
            database.observeReviewPlanForSession(sessionId).first(),
        ) { "No persisted review plan owns session $sessionId" }
        requireNotNull(
            (reviewPlan.activeSession ?: reviewPlan.latestSession)?.takeIf {
                it.reviewSessionId == sessionId
            },
        ) { "Review session $sessionId does not belong to its persisted plan" }
        val orderedQueue = reviewPlan.queue.sortedBy { it.ordinal }
        val queueItem = requireNotNull(
            orderedQueue.singleOrNull { it.ordinal.toLong() == expectedStateVersion },
        ) { "Expected review-session version does not identify one planned queue item" }
        require(queueItem.practiceUnitId == submission.practiceUnitId) {
            "Review answer belongs to another planned practice unit"
        }

        val prepared = submissionPreparer.prepareChoiceSubmission(submission)
        val priorMemory = currentLearnerSnapshot().problemMemoryStates
            ?.get(submission.practiceUnitId)
        database.saveAssessmentEvidenceSnapshot(prepared.evidenceSnapshot)
        val writeResult = database.recordReviewAttempt(
            ReviewAttemptWriteCommand(
                attempt = prepared.command,
                sessionId = sessionId,
                expectedStateVersion = expectedStateVersion,
                reviewQueueItemId = queueItem.reviewQueueItemId,
                practiceUnitId = queueItem.practiceUnitId,
            ),
        )
        if (writeResult.attempt.created) {
            reviewLogSink.record(
                practiceUnitId = submission.practiceUnitId,
                evidence = prepared.command.evidence,
                occurredAtEpochMillis = submission.occurredAtEpochMillis,
                durationSeconds = submission.durationSeconds,
                studyDay = prepared.command.studyDay,
                sourceKind = ReviewLogSink.SOURCE_KIND_ATTEMPT,
                sourceId = writeResult.attempt.attempt.attemptId,
                priorMemory = priorMemory,
                scrollUpCount = submission.scrollUpCount,
                awayMillis = submission.awayMillis,
                plannedReason = queueItem.reasonSnapshot.takeIf(String::isNotBlank),
                // Answer changing (spec 2.14): every retry is one edit of
                // the submitted answer for this presentation.
                editCount = (submission.responseOrdinal - 1).coerceAtLeast(0),
                interruptionCount = submission.interruptionCount,
            )
        }
        backfillPredictionOutcome(
            practiceUnitId = submission.practiceUnitId,
            wasIndependentCorrect = prepared.isCorrect,
            observedAtEpochMillis = submission.occurredAtEpochMillis,
            responseLatencyMs = submission.durationSeconds * 1000L,
            // The review path must carry the hint count the submission holds;
            // dropping it here made the whole hint channel read 0 (audit 2026-09-09).
            hintCount = submission.hintCount,
        )
        val progress = writeResult.advance.session.toProgress(orderedQueue.size)
        latestMistakes = database.observeMistakes().first()
        // L1 rollout: feed the observed duration after the snapshot refresh
        // so the subject lookup succeeds.
        recordObservedDuration(
            practiceUnitId = submission.practiceUnitId,
            durationSeconds = submission.durationSeconds.toDouble(),
        )
        initialized = true
        publishReadySnapshot(latestMistakes)
        StudyReviewChoiceSubmissionResult(
            attempt = StudyChoiceSubmissionResult(
                attemptId = writeResult.attempt.attempt.attemptId,
                created = writeResult.attempt.created,
                isCorrect = prepared.isCorrect,
                evidenceReason = writeResult.attempt.attempt.evidence.reason,
            ),
            progress = progress,
            nextPracticeUnitId = orderedQueue
                .getOrNull(progress.currentOrdinal)
                ?.practiceUnitId,
        )
    }

    override suspend fun submitReviewSelfReport(
        sessionId: String,
        expectedStateVersion: Long,
        submission: StudyReviewSelfReportSubmission,
    ): StudyReviewSelfReportSubmissionResult = runOperation {
        require(sessionId.isNotBlank()) { "Review session id must not be blank" }
        require(expectedStateVersion >= 0) { "Expected review-session version must not be negative" }
        val reviewPlan = requireNotNull(
            database.observeReviewPlanForSession(sessionId).first(),
        ) { "No persisted review plan owns session $sessionId" }
        requireNotNull(
            (reviewPlan.activeSession ?: reviewPlan.latestSession)?.takeIf {
                it.reviewSessionId == sessionId
            },
        ) { "Review session $sessionId does not belong to its persisted plan" }
        val orderedQueue = reviewPlan.queue.sortedBy { it.ordinal }
        val queueItem = requireNotNull(
            orderedQueue.singleOrNull { it.ordinal.toLong() == expectedStateVersion },
        ) { "Expected review-session version does not identify one planned queue item" }
        require(queueItem.practiceUnitId == submission.practiceUnitId) {
            "Review report belongs to another planned practice unit"
        }
        val currentMistakes = database.observeMistakes().first()
        val mistake = requireNotNull(
            currentMistakes.singleOrNull { it.practiceUnitId == submission.practiceUnitId },
        ) { "The planned saved question is no longer active" }
        val prepared = submissionPreparer.prepareSelfReportSubmission(submission, mistake)
        val priorMemory = currentLearnerSnapshot().problemMemoryStates
            ?.get(submission.practiceUnitId)
        database.saveAssessmentEvidenceSnapshot(prepared.evidenceSnapshot)
        val writeResult = database.recordReviewAttempt(
            ReviewAttemptWriteCommand(
                attempt = prepared.command,
                sessionId = sessionId,
                expectedStateVersion = expectedStateVersion,
                reviewQueueItemId = queueItem.reviewQueueItemId,
                practiceUnitId = queueItem.practiceUnitId,
            ),
        )
        if (writeResult.attempt.created) {
            reviewLogSink.record(
                practiceUnitId = submission.practiceUnitId,
                evidence = prepared.command.evidence,
                occurredAtEpochMillis = submission.occurredAtEpochMillis,
                durationSeconds = submission.durationSeconds,
                studyDay = prepared.command.studyDay,
                sourceKind = ReviewLogSink.SOURCE_KIND_SELF_REPORT,
                sourceId = writeResult.attempt.attempt.attemptId,
                priorMemory = priorMemory,
                scrollUpCount = submission.scrollUpCount,
                interruptionCount = submission.interruptionCount,
                awayMillis = submission.awayMillis,
            )
        }
        // L1 rollout: feed the observed duration (subject already loaded above).
        durationModel.record(
            learnerId = learnerId,
            subjectId = mistake.subject,
            itemType = null,
            difficulty = 5.0, // unused dimension; kept for API stability
            durationSeconds = submission.durationSeconds.toDouble().coerceAtLeast(1.0),
        )
        val progress = writeResult.advance.session.toProgress(orderedQueue.size)
        latestMistakes = currentMistakes
        initialized = true
        publishReadySnapshot(latestMistakes)
        StudyReviewSelfReportSubmissionResult(
            attemptId = writeResult.attempt.attempt.attemptId,
            created = writeResult.attempt.created,
            report = submission.report,
            evidenceReason = writeResult.attempt.attempt.evidence.reason,
            progress = progress,
            nextPracticeUnitId = orderedQueue
                .getOrNull(progress.currentOrdinal)
                ?.practiceUnitId,
        )
    }

    override suspend fun submitReviewRating(
        sessionId: String,
        expectedStateVersion: Long,
        submission: StudyReviewRatingSubmission,
    ): StudyReviewRatingSubmissionResult = runOperation {
        ratingSubmissionService.submit(sessionId, expectedStateVersion, submission)
    }

    override fun observeKnowledgeQuestionLattice(): Flow<List<KnowledgeQuestionLatticeRow>> =
        database.observeKnowledgeQuestionLattice(learnerId).map { rows ->
            rows.map { row ->
                KnowledgeQuestionLatticeRow(
                    practiceUnitId = row.practiceUnitId,
                    knowledgeNodeId = row.knowledgeNodeId,
                    bindingStrength = row.bindingStrength,
                    basisRevisionId = row.basisRevisionId,
                    bindingTaxonomyVersion = row.bindingTaxonomyVersion,
                    entryId = row.entryId,
                    entryStatus = row.entryStatus,
                    kcConservativeMastery = row.kcConservativeMastery,
                    kcStatus = row.kcStatus,
                    kcLastEvidenceDirection = row.kcLastEvidenceDirection,
                    kcLastEvidenceAt = row.kcLastEvidenceAt,
                    questionStabilityDays = row.questionStabilityDays,
                    questionDifficulty = row.questionDifficulty,
                    questionNextReviewAt = row.questionNextReviewAt,
                    questionLapseCount = row.questionLapseCount,
                    questionCrossDayAgain = row.questionCrossDayAgain,
                )
            }
        }

    override suspend fun recordTeachingFocus(
        sessionId: String,
        practiceUnitId: String,
        labels: List<String>,
        cycleOrdinal: Int,
    ) {
        advisoryStore.recordTeachingFocus(sessionId, practiceUnitId, labels, cycleOrdinal)
    }

    override suspend fun recordMisconceptionAdvisory(
        sessionId: String,
        practiceUnitId: String,
        payloadMarkdown: String,
        cycleOrdinal: Int,
    ) {
        advisoryStore.recordMisconceptionAdvisory(
            sessionId = sessionId,
            practiceUnitId = practiceUnitId,
            payloadMarkdown = payloadMarkdown,
            cycleOrdinal = cycleOrdinal,
        )
    }

    override fun observeTeachingAdvisories(practiceUnitId: String?): Flow<List<TeachingAdvisoryRecord>> =
        advisoryStore.observeTeachingAdvisories(practiceUnitId)

    override suspend fun submitKnowledgeQuizFeedback(
        requestId: String,
        knowledgeNodeId: String,
        correctChoiceId: String,
        selectedChoiceId: String,
        occurredAtEpochMillis: Long,
        conversationId: String,
    ): KnowledgeQuizFeedbackResult = quizFeedbackWriter.submit(
        requestId = requestId,
        knowledgeNodeId = knowledgeNodeId,
        correctChoiceId = correctChoiceId,
        selectedChoiceId = selectedChoiceId,
        occurredAtEpochMillis = occurredAtEpochMillis,
        conversationId = conversationId,
    )

    override suspend fun evaluateSchedulingModels(): SchedulingEvaluationReport? =
        calibration.evaluateSchedulingModels()

    override suspend fun sourceCalibrations(): List<SourceCalibration> =
        calibration.sourceCalibrations()

    override suspend fun plannedReasonCalibrations(): List<PlannedReasonCalibration> =
        calibration.plannedReasonCalibrations()

    override suspend fun chatEvidenceGateCalibration():
        ChatEvidenceGateCalibration.GateCalibrationReport? =
        calibration.chatEvidenceGateCalibration()

    override suspend fun suggestedReminderMinute(): Int? = calibration.suggestedReminderMinute()

    override suspend fun optimizeSchedulingParameters(): FsrsParameterOptimizer.Result? =
        calibration.optimizeSchedulingParameters()

    override suspend fun recommendedDesiredRetention(): OptimalRetention.Recommendation? =
        calibration.recommendedDesiredRetention()

    suspend fun calibrationReport(modelVersion: LearningModelVersion): CalibrationReport =
        calibration.calibrationReport(modelVersion)

    override suspend fun calibrationReport(): CalibrationReport = calibration.calibrationReport()

    override suspend fun revealAnswer(
        request: StudyAnswerRevealRequest,
    ): StudyAnswerRevealResult = runOperation {
        val result = answerRevealService.reveal(request)
        latestMistakes = database.observeMistakes().first()
        initialized = true
        publishReadySnapshot(latestMistakes)
        result
    }
    override suspend fun startOrResumeReviewSession(
        requestId: String,
        occurredAtEpochMillis: Long,
    ): StudyReviewSessionProgress? = runOperation {
        require(requestId.isNotBlank()) { "Review-session request id must not be blank" }
        require(occurredAtEpochMillis >= 0) { "Review-session start time must not be negative" }
        latestMistakes = database.observeMistakes().first()
        initialized = true
        publishReadySnapshot(latestMistakes)

        val planningContext = plannerService.planningContext(currentLearnerSnapshot())
        val currentPlan = requireNotNull(
            database.observeActiveReviewPlan(learnerId).first() ?: plannerService.currentReviewPlan(planningContext),
        ) {
            "No current review plan is available"
        }
        if (currentPlan.queue.isEmpty()) return@runOperation null
        currentPlan.activeSession?.let { return@runOperation it.toProgress(currentPlan.queue.size) }
        currentPlan.latestSession?.takeIf {
            it.status == StudyDbValue.ReviewStatus.COMPLETED
        }?.let { return@runOperation it.toProgress(currentPlan.queue.size) }

        val session = ReviewSessionRecord(
            reviewSessionId = writeContext.stableId(
                namespace = "review-session",
                requestId = "${currentPlan.plan.reviewPlanId}\n$requestId",
            ),
            reviewPlanId = currentPlan.plan.reviewPlanId,
            status = StudyDbValue.ReviewStatus.IN_PROGRESS,
            startedAtEpochMillis = occurredAtEpochMillis,
            lastActiveAtEpochMillis = occurredAtEpochMillis,
            completedAtEpochMillis = null,
            currentOrdinal = 0,
            timeBudgetSeconds = currentPlan.plan.timeBudgetSeconds,
            projectionCheckpoint = currentPlan.plan.projectionCheckpoint,
            stateVersion = 0,
        )
        try {
            database.saveReviewSession(session)
        } catch (conflict: ImmutablePayloadConflictException) {
            val concurrent = plannerService.currentReviewPlan(planningContext)?.activeSession
            if (concurrent == null || concurrent.reviewPlanId != currentPlan.plan.reviewPlanId) {
                throw conflict
            }
            return@runOperation concurrent.toProgress(currentPlan.queue.size)
        }
        publishReadySnapshot(latestMistakes)
        session.toProgress(currentPlan.queue.size)
    }

    override suspend fun currentKnowledgeReviewPlan(
        requestId: String,
        occurredAtEpochMillis: Long,
    ): KnowledgeReviewSessionPlan? = runOperation {
        latestMistakes = database.observeMistakes().first()
        initialized = true
        publishReadySnapshot(latestMistakes)
        plannerService.currentKnowledgeReviewPlan(
            requestId = requestId,
            occurredAtEpochMillis = occurredAtEpochMillis,
            mistakes = latestMistakes,
            knowledgeNames = knowledgeNames,
        )
    }


    override fun close() {
        observationJobs.cancel()
        if (closeDatabaseOnClose) database.close()
    }

    private suspend fun publishReadySnapshot(mistakes: List<MistakeRecord>) {
        _snapshot.value = snapshotBuilder.build(
            mistakes = mistakes,
            pendingCorrectionCount = latestPendingCorrectionCount,
            knowledgeCoverage = latestKnowledgeCoverage,
        )
    }
    /** Best-effort outcome backfill for every pending prediction covering the attempt. */
    private suspend fun backfillPredictionOutcome(
        practiceUnitId: String,
        wasIndependentCorrect: Boolean,
        observedAtEpochMillis: Long,
        responseLatencyMs: Long?,
        hintCount: Int = 0,
    ) {
        try {
            predictionAuditSink.resolveOutcome(
                practiceUnitId = practiceUnitId,
                wasIndependentCorrect = wasIndependentCorrect,
                observedAtEpochMillis = observedAtEpochMillis,
                responseLatencyMs = responseLatencyMs,
                hintCount = hintCount,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Shadow audit degradation must never surface to the user.
        }
    }

    /** Calibration for the shadow student-model generation writing predictions today. */
    /**
     * Visual-interaction attempts become ledger attempts exactly once per
     * recorded attemptId (audit §12 / PR-11): the stable submission id makes
     * [StudyDatabasePort.recordAttempt] replay-safe, so repeated sweeps can
     * never double-count an interaction.
     */
    override suspend fun ingestVisualInteractionAttempts(): Int = runOperation {
        val mistakes = database.observeMistakes().first()
        latestMistakes = mistakes
        initialized = true
        val created = visualInteractionIngestor.ingestPending(mistakes)
        if (created > 0) {
            publishReadySnapshot(latestMistakes)
        }
        created
    }

    /**
     * L1 rollout (spec batch-intake-spec §6): feed one real answer attempt's
     * observed duration into the shared duration model, bucketed by
     * (learner, subject). The bucket key deliberately drops the difficulty
     * and item-type dimensions — difficulty drifts between record time
     * (prior) and query time (current), which would make recorded samples
     * never match the queries; duration is driven mainly by the learner and
     * the subject. Runs AFTER the latest snapshot refresh so the subject is
     * reliably available.
     */
    private suspend fun recordObservedDuration(
        practiceUnitId: String,
        durationSeconds: Double,
    ) {
        if (durationSeconds <= 0.0) return
        val subject = latestMistakes
            .firstOrNull { it.practiceUnitId == practiceUnitId }
            ?.subject
            ?: return
        durationModel.record(
            learnerId = learnerId,
            subjectId = subject,
            itemType = null,
            difficulty = 5.0, // unused dimension; kept for API stability
            durationSeconds = durationSeconds,
        )
    }

    private suspend fun currentLearnerSnapshot(): LearnerSnapshot =
        projectionDrainer.drain()?.snapshot ?: LearnerSnapshot.empty(
            learnerId = learnerId,
            projectorVersion = LearningProjector.VERSION,
        )

    private suspend fun <T> runOperation(block: suspend () -> T): T = operationMutex.withLock {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            publishFailure(failure)
            throw failure
        }
    }

    private fun publishFailure(failure: Throwable) {
        _snapshot.value = _snapshot.value.copy(
            status = StudyDataStatus.ERROR,
            review = StudyReviewOverview(),
            profile = _snapshot.value.profile.copy(projectionIsCurrent = false),
            tutorPracticeUnitId = null,
            tutorDecision = null,
            failureMessage = failure.message ?: failure::class.java.simpleName,
        )
    }

    companion object {
        const val DEFAULT_LEARNER_ID = "learner:local"
        /** 知识点复习会话的固定 conversation id（同一复习会话内计数防刷，非聊天会话）。 */
        private const val KNOWLEDGE_QUIZ_CONVERSATION_ID = "knowledge-quiz-review"
        private const val DEFAULT_REVIEW_TIME_BUDGET_SECONDS = 20 * 60
        /** 知识点复习范围材料读取上限（对齐 KnowledgeTeachingMaterialDao 的 1..64 约束）。 */
        /** Rollback switch for the V2 review planner; see [useReviewPlannerV2]. */
        private const val DEFAULT_USE_REVIEW_PLANNER_V2 = true
        /** Difficulty mid-point on the FSRS 1..10 domain (spec 3.2). */
        private const val VISUAL_VIOLATED_WEIGHT = 0.5
        private const val PRIMARY_VISUAL_ATTRIBUTION_WEIGHT = 0.6
        private const val SECONDARY_VISUAL_ATTRIBUTION_WEIGHT_POOL = 0.4
        private const val VISUAL_ASSESSMENT_ITEM_ID_PREFIX = "local-visual-interaction:"
        private const val VISUAL_ANSWER_SPEC_ID = "local-visual-interaction-v1"
        private const val VISUAL_ITEM_FAMILY_ID = "local-visual-interaction"
        /** Spec 2.7 cooldowns: subjective reports 6h, visual interactions 1h. */
    }
}