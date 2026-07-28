package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.data.M1CuratedStudySeed
import com.tingyun.smartmistakebook.core.data.tutor.TutorEvidenceWriteGate
import com.tingyun.smartmistakebook.core.database.AnswerRevealWriteCommand
import com.tingyun.smartmistakebook.core.database.AttemptWriteCommand
import com.tingyun.smartmistakebook.core.database.ConsumedLedgerEventReceipt
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.LearningLedgerIntegrityException
import com.tingyun.smartmistakebook.core.database.LearningLedgerReadStatus
import com.tingyun.smartmistakebook.core.database.MAX_REVIEW_COMPLETION_HISTORY_DAYS
import com.tingyun.smartmistakebook.core.database.KnowledgeGroundingSummaryRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.PersistedLearnerSnapshot
import com.tingyun.smartmistakebook.core.database.ProjectionBatchStopReason
import com.tingyun.smartmistakebook.core.database.ProjectionCasConflictException
import com.tingyun.smartmistakebook.core.database.ProjectionCommit
import com.tingyun.smartmistakebook.core.database.ProjectionCommitMode
import com.tingyun.smartmistakebook.core.database.ReviewPlanBundle
import com.tingyun.smartmistakebook.core.database.ReviewPlanRecord
import com.tingyun.smartmistakebook.core.database.ReviewAttemptWriteCommand
import com.tingyun.smartmistakebook.core.database.ReviewQueueItemRecord
import com.tingyun.smartmistakebook.core.database.ReviewSessionRecord
import com.tingyun.smartmistakebook.core.database.ReviewedKnowledgeCoverageRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.StudySeedBundle
import com.tingyun.smartmistakebook.core.domain.ForgettingCurve
import com.tingyun.smartmistakebook.core.domain.LearningProjector
import com.tingyun.smartmistakebook.core.domain.MasteryEvidencePolicy
import com.tingyun.smartmistakebook.core.domain.ReviewCandidate
import com.tingyun.smartmistakebook.core.domain.ReviewCompletionStreak
import com.tingyun.smartmistakebook.core.domain.ReviewPlanner
import com.tingyun.smartmistakebook.core.domain.ReviewPlanningRequest
import com.tingyun.smartmistakebook.core.domain.SaveStudyMistakeResult
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealRequest
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealResult
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmission
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyDataStatus
import com.tingyun.smartmistakebook.core.domain.StudyExperienceRepository
import com.tingyun.smartmistakebook.core.domain.StudyExperienceSnapshot
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeCoverageGap
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeCoverageOverview
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSubjectCoverage
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyQuestionMemory
import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewChoiceSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReport
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReportSubmission
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReportSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionProgress
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus
import com.tingyun.smartmistakebook.core.domain.TutorEvidenceRejectedException
import com.tingyun.smartmistakebook.core.model.AssessmentSubmissionContext
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.LocalReviewSelfReportContract
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val closeDatabaseOnClose: Boolean = false,
    private val initialFixture: StudySeedBundle? = null,
) : StudyExperienceRepository {
    private val operationMutex = Mutex()
    private val _snapshot = MutableStateFlow(StudyExperienceSnapshot())
    private val completeSeed = M1CuratedStudySeed.bundle(includeTutorMistake = true)
    private val curatedProblemIds = completeSeed.problems.mapTo(hashSetOf()) { it.problemId }
    private val knowledgeNames = completeSeed.knowledgeNodes.associate {
        it.knowledgeNodeId to it.displayName
    }
    private val forgettingCurve = ForgettingCurve()
    private val reviewPlanner = ReviewPlanner()
    private val learningProjector = LearningProjector()
    private val tutorChoiceWriteGate = TutorEvidenceWriteGate()
    private var initialized = false
    private var latestMistakes: List<MistakeRecord> = emptyList()
    private var latestPendingCorrectionCount: Int = 0
    private var latestKnowledgeCoverage = StudyKnowledgeCoverageOverview()

    override val snapshot: StateFlow<StudyExperienceSnapshot> = _snapshot.asStateFlow()

    init {
        require(learnerId.isNotBlank()) { "Learner id must not be blank" }
        require(reviewTimeBudgetSeconds > 0) { "Review time budget must be positive" }
    }

    private val observationJob: Job = applicationScope.launch {
        try {
            database.observeMistakes().collect { mistakes ->
                operationMutex.withLock {
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
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            operationMutex.withLock { publishFailure(failure) }
        }
    }

    private val pendingDraftObservationJob: Job = applicationScope.launch {
        try {
            database.observePendingProblemDraftCount().collect { count ->
                operationMutex.withLock {
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
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            operationMutex.withLock { publishFailure(failure) }
        }
    }

    private val learningLedgerObservationJob: Job = applicationScope.launch {
        try {
            database.observeLearningLedgerHead(learnerId)
                .distinctUntilChanged()
                .collect {
                    operationMutex.withLock {
                        if (initialized) {
                            try {
                                publishReadySnapshot(latestMistakes)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                publishFailure(failure)
                            }
                        }
                    }
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            operationMutex.withLock { publishFailure(failure) }
        }
    }

    private fun observeKnowledgeCoverageOverview() = combine(
        database.observeReviewedKnowledgeCoverage(),
        database.observePendingKnowledgeGroundingSummaries(),
    ) { reviewedCoverage, pendingGaps ->
        reviewedCoverage.toKnowledgeCoverageOverview(pendingGaps)
    }.distinctUntilChanged()

    private val knowledgeCoverageObservationJob: Job = applicationScope.launch {
        try {
            observeKnowledgeCoverageOverview().collect { coverage ->
                operationMutex.withLock {
                    latestKnowledgeCoverage = coverage
                    if (initialized) {
                        _snapshot.value = _snapshot.value.copy(
                            knowledgeCoverage = coverage,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            operationMutex.withLock { publishFailure(failure) }
        }
    }

    override suspend fun initialize() {
        runOperation {
            if (!initialized) initialFixture?.let { database.seedFixture(it) }
            database.reconcileTutorAnswerExposures(learnerId)
            latestMistakes = database.observeMistakes().first()
            latestPendingCorrectionCount = database.observePendingProblemDraftCount().first()
            latestKnowledgeCoverage = observeKnowledgeCoverageOverview().first()
            initialized = true
            publishReadySnapshot(latestMistakes)
        }
    }

    override suspend fun saveTutorExampleMistake(): SaveStudyMistakeResult = runOperation {
        val result = database.seedFixture(completeSeed)
        latestMistakes = database.observeMistakes().first()
        initialized = true
        publishReadySnapshot(latestMistakes)
        val entryCount = database.countMistakes()
        if (result.insertedErrorBookEntryCount > 0) {
            SaveStudyMistakeResult.Saved(entryCount)
        } else {
            SaveStudyMistakeResult.AlreadySaved(entryCount)
        }
    }

    override suspend fun teachingArtifact(practiceUnitId: String): VerifiedTeachingArtifact? =
        M1CuratedStudySeed.teachingArtifactForPracticeUnit(practiceUnitId)

    override suspend fun submitChoice(
        submission: StudyChoiceSubmission,
    ): StudyChoiceSubmissionResult = runOperation {
        val prepared = prepareChoiceSubmission(submission)
        val writeResult = tutorChoiceWriteGate.persist(
            requestId = submission.requestId,
            write = {
                database.saveAssessmentEvidenceSnapshot(prepared.evidenceSnapshot)
                database.recordAttempt(prepared.command)
            },
        )
        latestMistakes = database.observeMistakes().first()
        initialized = true
        publishReadySnapshot(latestMistakes)
        StudyChoiceSubmissionResult(
            attemptId = writeResult.attempt.attemptId,
            created = writeResult.created,
            isCorrect = prepared.isCorrect,
            evidenceReason = writeResult.attempt.evidence.reason,
        )
    }

    override fun cancelChoiceSubmission(requestId: String) {
        require(requestId.isNotBlank()) { "Choice request id must not be blank" }
        tutorChoiceWriteGate.cancel(requestId)
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

        val prepared = prepareChoiceSubmission(submission)
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
        val progress = writeResult.advance.session.toProgress(orderedQueue.size)
        latestMistakes = database.observeMistakes().first()
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
        val prepared = prepareSelfReportSubmission(submission, mistake)
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

    override suspend fun revealAnswer(
        request: StudyAnswerRevealRequest,
    ): StudyAnswerRevealResult = runOperation {
        val artifact = requireTeachingArtifact(request.practiceUnitId)
        val assessmentItem = artifact.assessmentItems.singleOrNull()
            ?: error("Curated practice unit ${request.practiceUnitId} must have one assessment")
        val evidenceSnapshot = requireNotNull(
            M1CuratedStudySeed.evidenceSnapshotForAssessment(assessmentItem.id),
        ) { "No verified evidence snapshot for assessment ${assessmentItem.id}" }

        database.saveAssessmentEvidenceSnapshot(evidenceSnapshot)
        val writeResult = database.recordAnswerReveal(
            AnswerRevealWriteCommand(
                learnerId = learnerId,
                assessmentEventId = stableId("answer-reveal", request.requestId),
                presentationId = request.presentationId,
                assessmentSnapshotId = evidenceSnapshot.snapshotId,
                contentMarkdown = artifact.explanationMarkdown,
                occurredAtEpochMillis = request.occurredAtEpochMillis,
                studyDay = studyDayAt(request.occurredAtEpochMillis),
            ),
        )
        latestMistakes = database.observeMistakes().first()
        initialized = true
        publishReadySnapshot(latestMistakes)
        StudyAnswerRevealResult(
            outcomeId = writeResult.outcome.outcomeId,
            created = writeResult.created,
            explanationMarkdown = artifact.explanationMarkdown,
        )
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

        val planningContext = planningContext(currentLearnerSnapshot())
        val currentPlan = requireNotNull(
            database.observeActiveReviewPlan(learnerId).first() ?: currentReviewPlan(planningContext),
        ) {
            "No current review plan is available"
        }
        if (currentPlan.queue.isEmpty()) return@runOperation null
        currentPlan.activeSession?.let { return@runOperation it.toProgress(currentPlan.queue.size) }
        currentPlan.latestSession?.takeIf {
            it.status == StudyDbValue.ReviewStatus.COMPLETED
        }?.let { return@runOperation it.toProgress(currentPlan.queue.size) }

        val session = ReviewSessionRecord(
            reviewSessionId = stableId(
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
            val concurrent = currentReviewPlan(planningContext)?.activeSession
            if (concurrent == null || concurrent.reviewPlanId != currentPlan.plan.reviewPlanId) {
                throw conflict
            }
            return@runOperation concurrent.toProgress(currentPlan.queue.size)
        }
        publishReadySnapshot(latestMistakes)
        session.toProgress(currentPlan.queue.size)
    }

    override fun close() {
        observationJob.cancel()
        pendingDraftObservationJob.cancel()
        learningLedgerObservationJob.cancel()
        knowledgeCoverageObservationJob.cancel()
        if (closeDatabaseOnClose) database.close()
    }

    private suspend fun publishReadySnapshot(mistakes: List<MistakeRecord>) {
        val learnerSnapshot = currentLearnerSnapshot()
        check(
            learnerSnapshot.freshness == LearnerSnapshotFreshness.CURRENT &&
                learnerSnapshot.projectionStatus == ProjectionStatus.CURRENT,
        ) { "Cannot publish a study snapshot from a stale or incomplete learning projection" }

        val planningContext = planningContext(learnerSnapshot)
        val activePlan = database.observeActiveReviewPlan(learnerId).first()
        val retainedPlan = activePlan ?: database.observeCurrentReviewPlan(
            learnerId = learnerId,
            localDayEpochDay = planningContext.localDate.toEpochDay(),
            timeZoneId = studyZoneId.id,
        ).first()?.takeIf { current ->
            current.activeSession != null ||
                current.latestSession?.status == StudyDbValue.ReviewStatus.COMPLETED
        }
        val reviewBundle = retainedPlan ?: createReviewPlan(
            mistakes = mistakes,
            learnerSnapshot = learnerSnapshot,
            planningContext = planningContext,
        ).also { database.saveReviewPlan(it) }
        val completedReviewDays = database.observeCompletedReviewLocalDays(
            learnerId = learnerId,
            limit = MAX_REVIEW_COMPLETION_HISTORY_DAYS,
        ).first()
        val orderedMistakes = mistakes.sortedWith(
            compareByDescending<MistakeRecord>(MistakeRecord::createdAtEpochMillis)
                .thenBy(MistakeRecord::entryId),
        )
        val referencedKnowledgeNodeIds = buildSet {
            addAll(learnerSnapshot.knowledgeMasteryStates.keys)
            orderedMistakes.forEach { mistake -> addAll(mistake.knowledgeNodeIds) }
        }
        val resolvedKnowledgeContexts = resolveKnowledgeContexts(referencedKnowledgeNodeIds)
        val resolvedKnowledgeNames = knowledgeNames + resolvedKnowledgeContexts.mapValues {
            it.value.displayName
        }
        _snapshot.value = StudyExperienceSnapshot(
            status = StudyDataStatus.READY,
            catalog = orderedMistakes.map { mistake ->
                mistake.toCatalogEntry(
                    learnerSnapshot = learnerSnapshot,
                    atEpochMillis = planningContext.planningAtEpochMillis,
                    resolvedKnowledgeNames = resolvedKnowledgeNames,
                )
            },
            pendingCorrectionCount = latestPendingCorrectionCount,
            review = reviewBundle.toOverview(
                completedReviewDays = completedReviewDays,
                currentLocalDay = planningContext.localDate.toEpochDay(),
            ),
            profile = learnerSnapshot.toProfileOverview(
                resolvedKnowledgeContexts = resolvedKnowledgeContexts,
                fallbackKnowledgeNames = resolvedKnowledgeNames,
            ),
            knowledgeCoverage = latestKnowledgeCoverage,
            tutorExampleSaved = orderedMistakes.any {
                it.practiceUnitId == M1CuratedStudySeed.TUTOR_PRACTICE_UNIT_ID
            },
            // The tutor root has no current question until the student captures or selects one.
            tutorPracticeUnitId = null,
            tutorDecision = null,
        )
    }

    private suspend fun currentLearnerSnapshot(): LearnerSnapshot =
        drainProjection()?.snapshot ?: LearnerSnapshot.empty(
            learnerId = learnerId,
            projectorVersion = LearningProjector.VERSION,
        )

    private suspend fun currentReviewPlan(
        planningContext: PlanningContext,
    ): ReviewPlanBundle? = database.observeCurrentReviewPlan(
        learnerId = learnerId,
        localDayEpochDay = planningContext.localDate.toEpochDay(),
        timeZoneId = studyZoneId.id,
    ).first()

    private fun createReviewPlan(
        mistakes: List<MistakeRecord>,
        learnerSnapshot: LearnerSnapshot,
        planningContext: PlanningContext,
    ): ReviewPlanBundle {
        val candidates = mistakes
            .sortedBy(MistakeRecord::practiceUnitId)
            .distinctBy(MistakeRecord::practiceUnitId)
            .map { mistake ->
                val curatedEvidence = M1CuratedStudySeed
                    .teachingArtifactForPracticeUnit(mistake.practiceUnitId)
                    ?.assessmentItems
                    ?.singleOrNull()
                    ?.let { assessment ->
                        M1CuratedStudySeed.evidenceSnapshotForAssessment(assessment.id)
                    }
                ReviewCandidate(
                    practiceUnitId = mistake.practiceUnitId,
                    knowledgeNodeIds = mistake.knowledgeNodeIds.ifEmpty {
                        curatedEvidence?.attributions
                            ?.mapTo(linkedSetOf()) { it.knowledgeNodeId }
                            .orEmpty()
                    },
                    itemFamilyId = curatedEvidence?.itemFamilyId
                        ?: "saved-question:${mistake.practiceUnitId}",
                    sourceBundleId = curatedEvidence?.sourceBundleId,
                    difficulty = learnerSnapshot.problemMemoryStates[mistake.practiceUnitId]
                        ?.difficulty ?: DEFAULT_CANDIDATE_DIFFICULTY,
                    estimatedDurationSeconds = mistake.estimatedSeconds,
                    repeatMistakePriority = (mistake.captureOccurrenceCount - 1)
                        .coerceIn(0, MAX_REPEAT_CAPTURE_BONUS_COUNT).toDouble() /
                        MAX_REPEAT_CAPTURE_BONUS_COUNT,
                    eligibleSinceEpochMillis = mistake.createdAtEpochMillis,
                )
            }
        val plan = reviewPlanner.plan(
            ReviewPlanningRequest(
                learnerSnapshot = learnerSnapshot,
                candidates = candidates,
                localDayEpochDay = planningContext.localDate.toEpochDay(),
                timeZoneId = studyZoneId.id,
                timeBudgetSeconds = reviewTimeBudgetSeconds,
                planningAtEpochMillis = planningContext.planningAtEpochMillis,
            ),
        )
        return ReviewPlanBundle(
            plan = ReviewPlanRecord(
                reviewPlanId = plan.planId,
                learnerId = learnerId,
                localDate = planningContext.localDate.toString(),
                localDayEpochDay = planningContext.localDate.toEpochDay(),
                timeZoneId = studyZoneId.id,
                timeBudgetSeconds = plan.timeBudgetSeconds,
                planningAtEpochMillis = plan.generatedAtEpochMillis,
                status = StudyDbValue.ReviewStatus.PLANNED,
                plannerVersion = plan.plannerVersion,
                projectionCheckpoint = plan.projectionCheckpoint.lastSequence,
                inputFingerprint = plan.planFingerprint,
                planFingerprint = plan.planFingerprint,
                planRevision = 1,
                createdAtEpochMillis = plan.generatedAtEpochMillis,
            ),
            queue = plan.queueItems.map { queueItem ->
                ReviewQueueItemRecord(
                    reviewQueueItemId = queueItem.queueItemId,
                    reviewPlanId = plan.planId,
                    practiceUnitId = queueItem.practiceUnitId,
                    knowledgeNodeIds = queueItem.knowledgeNodeIds,
                    itemFamilyId = queueItem.itemFamilyId,
                    sourceBundleId = queueItem.sourceBundleId,
                    reasons = queueItem.reasons.mapTo(linkedSetOf()) { it.name },
                    ordinal = queueItem.scheduledOrder,
                    priorityScore = queueItem.priorityScore,
                    difficultyBand = queueItem.difficultyBand.name,
                    dueAtEpochMillis = queueItem.dueAtEpochMillis,
                    estimatedSeconds = queueItem.estimatedDurationSeconds,
                    reasonSnapshot = queueItem.reasons.map { it.name }.sorted().joinToString(","),
                )
            },
            activeSession = null,
            isCurrent = true,
        )
    }

    private suspend fun drainProjection(): PersistedLearnerSnapshot? {
        var consecutiveCasConflicts = 0
        repeat(MAX_PROJECTION_DRAIN_STEPS) {
            val current = database.readCurrentLearnerSnapshot(PROJECTION_NAME, learnerId)
            val batch = database.loadProjectionBatch(
                projectionName = PROJECTION_NAME,
                learnerId = learnerId,
                limit = PROJECTION_BATCH_SIZE,
            )
            val expectedCheckpoint = current?.snapshot?.checkpoint?.lastSequence ?: 0L
            if (batch.previousCheckpoint != expectedCheckpoint) {
                consecutiveCasConflicts++
                if (consecutiveCasConflicts >= MAX_CAS_RETRIES) {
                    throw ProjectionCasConflictException("Projection checkpoint changed during drain")
                }
                return@repeat
            }
            when (batch.stopReason) {
                ProjectionBatchStopReason.GAP,
                ProjectionBatchStopReason.CONFLICT,
                -> throw LearningLedgerIntegrityException(
                    batch.detail ?: "Learning ledger stopped at ${batch.blockedAtSequence}",
                )

                ProjectionBatchStopReason.FULL_REPLAY_REQUIRED -> {
                    try {
                        commitFullReplay(current)
                        consecutiveCasConflicts = 0
                    } catch (conflict: ProjectionCasConflictException) {
                        consecutiveCasConflicts++
                        if (consecutiveCasConflicts >= MAX_CAS_RETRIES) throw conflict
                    }
                }

                ProjectionBatchStopReason.END_OF_LEDGER,
                ProjectionBatchStopReason.LIMIT_REACHED,
                -> {
                    val previous = current?.snapshot ?: LearnerSnapshot.empty(
                        learnerId = learnerId,
                        projectorVersion = LearningProjector.VERSION,
                    )
                    val requiresReplay = previous.checkpoint.projectorVersion != LearningProjector.VERSION ||
                        (
                            batch.events.isEmpty() &&
                                (
                                    previous.freshness != LearnerSnapshotFreshness.CURRENT ||
                                        previous.projectionStatus != ProjectionStatus.CURRENT
                                    )
                            )
                    if (requiresReplay) {
                        try {
                            commitFullReplay(current)
                            consecutiveCasConflicts = 0
                        } catch (conflict: ProjectionCasConflictException) {
                            consecutiveCasConflicts++
                            if (consecutiveCasConflicts >= MAX_CAS_RETRIES) throw conflict
                        }
                    } else if (batch.events.isEmpty()) {
                        return current
                    } else {
                        val result = learningProjector.project(
                            previous = previous,
                            events = batch.events.map { it.event },
                            knownLedgerHeadSequence = batch.ledgerHeadSequence,
                            authoritativePresentationStates = batch.authoritativePresentationStates,
                        )
                        check(
                            result.missingSequence == null &&
                            result.conflictedAttemptIds.isEmpty() &&
                                result.conflictedAnswerRevealOutcomeIds.isEmpty() &&
                                result.conflictedTutorAnswerExposureOutcomeIds.isEmpty() &&
                                result.deferredAttemptIds.isEmpty() &&
                                result.deferredAnswerRevealOutcomeIds.isEmpty() &&
                                result.deferredTutorAnswerExposureOutcomeIds.isEmpty(),
                        ) { "Projector rejected a database-validated incremental prefix" }
                        val commit = ProjectionCommit(
                            projectionName = PROJECTION_NAME,
                            learnerId = learnerId,
                            expectedPreviousCheckpoint = expectedCheckpoint,
                            expectedPreviousStateVersion = current?.stateVersion ?: 0L,
                            mode = ProjectionCommitMode.INCREMENTAL,
                            knownLedgerHeadSequence = batch.ledgerHeadSequence,
                            consumedLedgerEvents = batch.events.map { persisted ->
                                ConsumedLedgerEventReceipt(
                                    eventKind = persisted.outbox.eventKind,
                                    eventId = persisted.outbox.eventId,
                                    eventSequence = persisted.outbox.outboxSequence,
                                    canonicalFingerprint = persisted.canonicalFingerprint,
                                )
                            },
                            presentationProjectionStates = result.presentationProjectionStates,
                            snapshot = result.snapshot,
                        )
                        try {
                            database.commitProjection(commit)
                            consecutiveCasConflicts = 0
                        } catch (conflict: ProjectionCasConflictException) {
                            consecutiveCasConflicts++
                            if (consecutiveCasConflicts >= MAX_CAS_RETRIES) throw conflict
                        }
                    }
                }
            }
        }
        throw ProjectionCasConflictException("Projection did not drain within the bounded work limit")
    }

    private suspend fun commitFullReplay(
        current: PersistedLearnerSnapshot?,
    ): PersistedLearnerSnapshot {
        val ledger = database.loadLearningLedger(learnerId)
        if (ledger.status != LearningLedgerReadStatus.COMPLETE) {
            throw LearningLedgerIntegrityException(
                ledger.detail ?: "Full replay blocked at ${ledger.blockedAtSequence}",
            )
        }
        val result = learningProjector.replay(
            learnerId = learnerId,
            ledger = ledger.validPrefix.map { it.event },
        )
        val expectedCheckpoint = current?.snapshot?.checkpoint?.lastSequence ?: 0L
        val consumed = ledger.validPrefix
            .filter { it.event.eventSequence > expectedCheckpoint }
            .map(::fullReplayReceipt)
        return database.commitProjection(
            ProjectionCommit(
                projectionName = PROJECTION_NAME,
                learnerId = learnerId,
                expectedPreviousCheckpoint = expectedCheckpoint,
                expectedPreviousStateVersion = current?.stateVersion ?: 0L,
                mode = ProjectionCommitMode.FULL_REPLAY,
                knownLedgerHeadSequence = result.snapshot.knownLedgerHeadSequence,
                consumedLedgerEvents = consumed,
                presentationProjectionStates = result.presentationProjectionStates,
                snapshot = result.snapshot,
            ),
        )
    }

    internal fun fullReplayReceipt(
        persisted: com.tingyun.smartmistakebook.core.database.PersistedLearningLedgerEvent,
    ) =
        ConsumedLedgerEventReceipt(
            eventKind = when (persisted.event) {
                is Attempt -> EVENT_KIND_ATTEMPT
                is AttemptCorrection -> EVENT_KIND_CORRECTION
                is com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome -> EVENT_KIND_ANSWER_REVEAL
                is TutorAnswerExposureOutcome -> EVENT_KIND_TUTOR_ANSWER_EXPOSURE
                is com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent ->
                    EVENT_KIND_LEARNING_OBSERVATION
            },
            eventId = persisted.event.ledgerEventId,
            eventSequence = persisted.event.eventSequence,
            canonicalFingerprint = persisted.canonicalFingerprint,
        )

    private fun MistakeRecord.toCatalogEntry(
        learnerSnapshot: LearnerSnapshot,
        atEpochMillis: Long,
        resolvedKnowledgeNames: Map<String, String>,
    ): StudyCatalogEntry {
        val memory = learnerSnapshot.problemMemoryStates[practiceUnitId]
        val artifact = M1CuratedStudySeed.teachingArtifactForPracticeUnit(practiceUnitId)
        val knowledgeNodeIds = this.knowledgeNodeIds.ifEmpty { artifact?.knowledgeNodeIds.orEmpty() }
        val knowledgeStates = knowledgeNodeIds.mapNotNull(
            learnerSnapshot.knowledgeMasteryStates::get,
        )
        return StudyCatalogEntry(
            entryId = entryId,
            problemId = problemId,
            problemRevisionId = problemRevisionId,
            practiceUnitId = practiceUnitId,
            subject = subject,
            title = title,
            problemMarkdown = problemMarkdown,
            sourceKey = sourceKey,
            isCuratedExample = problemId in curatedProblemIds,
            chapterLabels = chapterLabels,
            knowledgeLabels = knowledgeLabels.ifEmpty {
                knowledgeNodeIds.map { knowledgeNodeId ->
                    resolvedKnowledgeNames[knowledgeNodeId] ?: knowledgeNodeId
                }
            },
            masteryStatus = knowledgeStates.conservativeMasteryStatus(),
            nextReviewAtEpochMillis = memory?.nextReviewAtEpochMillis ?: nextReviewAtEpochMillis,
            retrievability = memory?.let { forgettingCurve.retentionAt(it, atEpochMillis) }
                ?: retrievability,
            questionMemory = memory?.let { state ->
                StudyQuestionMemory(
                    independentRecallCount = state.independentCorrectCount,
                    assistedRecallCount = state.assistedCorrectCount,
                    retrievalFailureCount = state.lapseCount,
                    answerRevealCount = state.answerRevealCount,
                    lastReviewedAtEpochMillis = state.lastReviewedAtEpochMillis,
                    nextReviewAtEpochMillis = state.nextReviewAtEpochMillis,
                    retrievabilityAtSnapshot = forgettingCurve.retentionAt(state, atEpochMillis),
                    projectionIsCurrent = learnerSnapshot.freshness == LearnerSnapshotFreshness.CURRENT &&
                        learnerSnapshot.projectionStatus == ProjectionStatus.CURRENT,
                )
            },
        )
    }

    private fun List<com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState>
        .conservativeMasteryStatus(): MasteryStatus = when {
        isEmpty() -> MasteryStatus.UNKNOWN
        any { it.status == MasteryStatus.CONFLICTED } -> MasteryStatus.CONFLICTED
        any { it.status == MasteryStatus.STALE } -> MasteryStatus.STALE
        any { it.status == MasteryStatus.LEARNING } -> MasteryStatus.LEARNING
        all { it.status == MasteryStatus.MASTERED } -> MasteryStatus.MASTERED
        else -> MasteryStatus.UNKNOWN
    }

    private suspend fun resolveKnowledgeContexts(
        knowledgeNodeIds: Set<String>,
    ): Map<String, ResolvedKnowledgeContext> {
        if (knowledgeNodeIds.isEmpty()) return emptyMap()
        val nodesById = database.readKnowledgeNodesByIds(knowledgeNodeIds)
            .associateByTo(linkedMapOf(), KnowledgeNodeSeedRecord::knowledgeNodeId)
        var pendingParentIds = nodesById.values
            .mapNotNullTo(linkedSetOf(), KnowledgeNodeSeedRecord::parentKnowledgeNodeId)
            .filterNotTo(linkedSetOf(), nodesById::containsKey)
        var remainingDepth = MAX_KNOWLEDGE_TOPIC_DEPTH
        while (pendingParentIds.isNotEmpty() && remainingDepth > 0) {
            val parents = database.readKnowledgeNodesByIds(pendingParentIds)
            parents.forEach { parent -> nodesById[parent.knowledgeNodeId] = parent }
            pendingParentIds = parents
                .mapNotNullTo(linkedSetOf(), KnowledgeNodeSeedRecord::parentKnowledgeNodeId)
                .filterNotTo(linkedSetOf(), nodesById::containsKey)
            remainingDepth -= 1
        }
        return knowledgeNodeIds.mapNotNull { knowledgeNodeId ->
            val node = nodesById[knowledgeNodeId] ?: return@mapNotNull null
            val path = ArrayDeque<String>()
            val visited = hashSetOf<String>()
            var parentId = node.parentKnowledgeNodeId
            while (parentId != null && path.size < MAX_KNOWLEDGE_TOPIC_DEPTH) {
                if (!visited.add(parentId)) break
                val parent = nodesById[parentId] ?: break
                path.addFirst(parent.displayName)
                parentId = parent.parentKnowledgeNodeId
            }
            val subject = runCatching { SubjectKind.valueOf(node.subject) }
                .getOrDefault(SubjectKind.GENERAL)
            knowledgeNodeId to ResolvedKnowledgeContext(
                displayName = node.displayName,
                subject = subject,
                topicPath = path.toList(),
            )
        }.toMap()
    }

    private fun LearnerSnapshot.toProfileOverview(
        resolvedKnowledgeContexts: Map<String, ResolvedKnowledgeContext>,
        fallbackKnowledgeNames: Map<String, String>,
    ): StudyProfileOverview {
        val weaknesses = knowledgeMasteryStates.values
            .filter { it.status != MasteryStatus.MASTERED }
            .sortedWith(
                compareBy<KnowledgeMasteryState> { it.lowerBoundIndependentCorrect }
                    .thenBy { it.knowledgeNodeId },
            )
            .map { state ->
                val context = resolvedKnowledgeContexts[state.knowledgeNodeId]
                StudyKnowledgeSummary(
                    knowledgeNodeId = state.knowledgeNodeId,
                    displayName = context?.displayName
                        ?: fallbackKnowledgeNames[state.knowledgeNodeId]
                        ?: state.knowledgeNodeId,
                    status = state.status,
                    lowerBoundIndependentCorrect = state.lowerBoundIndependentCorrect,
                    evidenceMass = state.evidenceMass,
                    independentCorrectObservationCount =
                        state.independentCorrectObservations.size,
                    lastEvidenceAtEpochMillis = state.lastEvidenceAtEpochMillis,
                    lastIndependentErrorAtEpochMillis =
                        state.lastIndependentErrorAtEpochMillis,
                    subject = context?.subject ?: SubjectKind.GENERAL,
                    topicPath = context?.topicPath.orEmpty(),
                )
            }
        val strengths = knowledgeMasteryStates.values
            .filter { it.status == MasteryStatus.MASTERED }
            .sortedWith(
                compareByDescending<KnowledgeMasteryState> { it.lowerBoundIndependentCorrect }
                    .thenBy { it.knowledgeNodeId },
            )
            .map { state ->
                val context = resolvedKnowledgeContexts[state.knowledgeNodeId]
                StudyKnowledgeSummary(
                    knowledgeNodeId = state.knowledgeNodeId,
                    displayName = context?.displayName
                        ?: fallbackKnowledgeNames[state.knowledgeNodeId]
                        ?: state.knowledgeNodeId,
                    status = state.status,
                    lowerBoundIndependentCorrect = state.lowerBoundIndependentCorrect,
                    evidenceMass = state.evidenceMass,
                    independentCorrectObservationCount =
                        state.independentCorrectObservations.size,
                    lastEvidenceAtEpochMillis = state.lastEvidenceAtEpochMillis,
                    lastIndependentErrorAtEpochMillis =
                        state.lastIndependentErrorAtEpochMillis,
                    subject = context?.subject ?: SubjectKind.GENERAL,
                    topicPath = context?.topicPath.orEmpty(),
                )
            }
        return StudyProfileOverview(
            hasLearningEvidence = appliedAttemptRecords.isNotEmpty(),
            recordedAttemptCount = appliedAttemptRecords.size,
            newlyMasteredCount = knowledgeMasteryStates.values.count {
                it.status == MasteryStatus.MASTERED
            },
            weaknesses = weaknesses,
            strengths = strengths,
            projectionIsCurrent = freshness == LearnerSnapshotFreshness.CURRENT &&
                projectionStatus == ProjectionStatus.CURRENT,
        )
    }

    private fun ReviewPlanBundle.toOverview(
        completedReviewDays: List<Long>,
        currentLocalDay: Long,
    ): StudyReviewOverview {
        val visibleSession = activeSession ?: latestSession
        val effectiveCompletedDays = if (
            latestSession?.status == StudyDbValue.ReviewStatus.COMPLETED
        ) {
            completedReviewDays + plan.localDayEpochDay
        } else {
            completedReviewDays
        }
        val totalEstimatedSeconds = queue.fold(0L) { total, item ->
            Math.addExact(total, item.estimatedSeconds.toLong())
        }
        check(totalEstimatedSeconds in 0..Int.MAX_VALUE.toLong()) {
            "Review plan duration exceeds the supported UI range"
        }
        return StudyReviewOverview(
            planId = plan.reviewPlanId,
            scheduledCount = queue.size,
            estimatedSeconds = totalEstimatedSeconds.toInt(),
            reasons = queue.flatMapTo(linkedSetOf()) { item ->
                item.reasons.map { com.tingyun.smartmistakebook.core.model.ReviewReason.valueOf(it) }
            },
            scheduledPracticeUnitIds = queue.sortedBy { it.ordinal }.map { it.practiceUnitId },
            activeSessionId = activeSession?.reviewSessionId,
            currentOrdinal = visibleSession?.currentOrdinal ?: 0,
            sessionStateVersion = visibleSession?.stateVersion,
            completedToday = currentLocalDay in effectiveCompletedDays,
            completionStreakDays = ReviewCompletionStreak.count(
                completedLocalDays = effectiveCompletedDays,
                currentLocalDay = currentLocalDay,
            ),
        )
    }

    private fun ReviewSessionRecord.toProgress(queueSize: Int) = StudyReviewSessionProgress(
        sessionId = reviewSessionId,
        planId = reviewPlanId,
        currentOrdinal = currentOrdinal,
        queueSize = queueSize,
        stateVersion = stateVersion,
        status = when (status) {
            StudyDbValue.ReviewStatus.IN_PROGRESS -> StudyReviewSessionStatus.ACTIVE
            StudyDbValue.ReviewStatus.COMPLETED -> StudyReviewSessionStatus.COMPLETED
            else -> error("Review session $reviewSessionId has unsupported status $status")
        },
    )

    private fun planningContext(learnerSnapshot: LearnerSnapshot): PlanningContext {
        val referenceAt = maxOf(clock.millis(), learnerSnapshot.decisionWatermarkEpochMillis)
        val localDate = Instant.ofEpochMilli(referenceAt).atZone(studyZoneId).toLocalDate()
        val startOfDay = localDate.atStartOfDay(studyZoneId).toInstant().toEpochMilli()
        return PlanningContext(
            localDate = localDate,
            planningAtEpochMillis = maxOf(startOfDay, learnerSnapshot.decisionWatermarkEpochMillis),
        )
    }

    private fun studyDayAt(occurredAtEpochMillis: Long): StudyDayContext {
        val local = Instant.ofEpochMilli(occurredAtEpochMillis).atZone(studyZoneId)
        check(local.offset.totalSeconds % 60 == 0) {
            "Study time-zone offset must be minute-aligned"
        }
        return StudyDayContext(
            epochDay = local.toLocalDate().toEpochDay(),
            timeZoneId = studyZoneId.id,
            utcOffsetMinutes = local.offset.totalSeconds / 60,
        )
    }

    private fun requireTeachingArtifact(practiceUnitId: String): VerifiedTeachingArtifact =
        requireNotNull(M1CuratedStudySeed.teachingArtifactForPracticeUnit(practiceUnitId)) {
            "Practice unit $practiceUnitId is outside the verified M1 catalog"
        }

    private suspend fun <T> runOperation(block: suspend () -> T): T = operationMutex.withLock {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: TutorEvidenceRejectedException) {
            throw rejected
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

    private fun prepareChoiceSubmission(
        submission: StudyChoiceSubmission,
    ): PreparedChoiceSubmission {
        val artifact = requireTeachingArtifact(submission.practiceUnitId)
        val assessmentItem = artifact.assessmentItems.singleOrNull()
            ?: error("Curated practice unit ${submission.practiceUnitId} must have one assessment")
        val evidenceSnapshot = requireNotNull(
            M1CuratedStudySeed.evidenceSnapshotForAssessment(assessmentItem.id),
        ) { "No verified evidence snapshot for assessment ${assessmentItem.id}" }
        val evaluation = assessmentItem.evaluateChoice(submission.selectedChoiceId)
        val submittedResponse = AttemptSubmittedResponse.Choice(
            choiceId = evaluation.choice.id,
            choiceMarkdown = evaluation.choice.markdown,
            submittedAtEpochMillis = submission.occurredAtEpochMillis,
        )
        val decision = MasteryEvidencePolicy.evaluate(
            assessmentItem = assessmentItem,
            context = AssessmentSubmissionContext(
                assessmentItemId = assessmentItem.id,
                selectedChoiceId = submission.selectedChoiceId,
                presentationId = submission.presentationId,
                responseSequence = submission.responseOrdinal.toLong(),
                responseOrdinal = submission.responseOrdinal,
            ),
        )
        return PreparedChoiceSubmission(
            evidenceSnapshot = evidenceSnapshot,
            command = AttemptWriteCommand(
                learnerId = learnerId,
                submissionId = stableId("submission", submission.requestId),
                attemptId = stableId("attempt", submission.requestId),
                presentationId = submission.presentationId,
                assessmentSnapshotId = evidenceSnapshot.snapshotId,
                submittedResponse = submittedResponse,
                evidence = decision.evidence,
                problemMemoryOutcome = decision.problemMemoryOutcome,
                occurredAtEpochMillis = submission.occurredAtEpochMillis,
                durationSeconds = submission.durationSeconds,
                studyDay = studyDayAt(submission.occurredAtEpochMillis),
            ),
            isCorrect = evaluation.isCorrect,
        )
    }

    private fun prepareSelfReportSubmission(
        submission: StudyReviewSelfReportSubmission,
        mistake: MistakeRecord,
    ): PreparedSelfReportSubmission {
        val evidenceSnapshot = AssessmentEvidenceSnapshot(
            snapshotId = stableId("self-report-snapshot", submission.requestId),
            assessmentItemId = LocalReviewSelfReportContract.ASSESSMENT_ITEM_ID_PREFIX +
                stableId(
                    namespace = "item",
                    requestId = "${mistake.practiceUnitId}\n${mistake.problemRevisionId}",
                ),
            practiceUnitId = mistake.practiceUnitId,
            problemRevisionId = mistake.problemRevisionId,
            answerSpecId = LocalReviewSelfReportContract.ANSWER_SPEC_ID,
            itemFamilyId = LocalReviewSelfReportContract.ITEM_FAMILY_ID,
            sourceBundleId = null,
            taxonomyVersion = LocalReviewSelfReportContract.TAXONOMY_VERSION,
            verification = AssessmentSnapshotVerification.VERIFIED,
            calibration = CalibrationSnapshot.unknown(),
            attributions = emptyList(),
            capturedAtEpochMillis = submission.occurredAtEpochMillis,
        )
        val reportDecision = when (submission.report) {
            StudyReviewSelfReport.RECALL_COMPLETED -> SelfReportDecision(
                choiceMarkdown = "我已独立完成",
                evidence = LearningEvidence(
                    direction = LearningEvidenceDirection.POSITIVE,
                    weight = SELF_REPORTED_RECALL_WEIGHT,
                    reason = LearningEvidenceReason.SELF_REPORTED_RECALL,
                ),
                memoryOutcome = ProblemMemoryOutcome.ASSISTED_RECALL,
            )

            StudyReviewSelfReport.NEEDS_HELP -> SelfReportDecision(
                choiceMarkdown = "这里还卡住",
                evidence = LearningEvidence(
                    direction = LearningEvidenceDirection.NEGATIVE,
                    weight = SELF_REPORTED_STUCK_WEIGHT,
                    reason = LearningEvidenceReason.SELF_REPORTED_STUCK,
                ),
                memoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
            )
        }
        return PreparedSelfReportSubmission(
            evidenceSnapshot = evidenceSnapshot,
            command = AttemptWriteCommand(
                learnerId = learnerId,
                submissionId = stableId("submission", submission.requestId),
                attemptId = stableId("attempt", submission.requestId),
                presentationId = submission.presentationId,
                assessmentSnapshotId = evidenceSnapshot.snapshotId,
                submittedResponse = AttemptSubmittedResponse.Choice(
                    choiceId = submission.report.name,
                    choiceMarkdown = reportDecision.choiceMarkdown,
                    submittedAtEpochMillis = submission.occurredAtEpochMillis,
                ),
                evidence = reportDecision.evidence,
                problemMemoryOutcome = reportDecision.memoryOutcome,
                occurredAtEpochMillis = submission.occurredAtEpochMillis,
                durationSeconds = submission.durationSeconds,
                studyDay = studyDayAt(submission.occurredAtEpochMillis),
            ),
        )
    }

    private fun stableId(namespace: String, requestId: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$learnerId\n$requestId".toByteArray(StandardCharsets.UTF_8))
        val digest = bytes.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return "$namespace-$digest"
    }

    private data class PlanningContext(
        val localDate: LocalDate,
        val planningAtEpochMillis: Long,
    )

    private data class ResolvedKnowledgeContext(
        val displayName: String,
        val subject: SubjectKind,
        val topicPath: List<String>,
    )

    private data class PreparedChoiceSubmission(
        val evidenceSnapshot: AssessmentEvidenceSnapshot,
        val command: AttemptWriteCommand,
        val isCorrect: Boolean,
    )

    private data class PreparedSelfReportSubmission(
        val evidenceSnapshot: AssessmentEvidenceSnapshot,
        val command: AttemptWriteCommand,
    )

    private data class SelfReportDecision(
        val choiceMarkdown: String,
        val evidence: LearningEvidence,
        val memoryOutcome: ProblemMemoryOutcome,
    )

    companion object {
        const val DEFAULT_LEARNER_ID = "learner:local"
        private const val PROJECTION_NAME = "study-experience-v1"
        private const val DEFAULT_REVIEW_TIME_BUDGET_SECONDS = 20 * 60
        private const val DEFAULT_CANDIDATE_DIFFICULTY = 0.5
        private const val MAX_REPEAT_CAPTURE_BONUS_COUNT = 4
        private const val MAX_KNOWLEDGE_TOPIC_DEPTH = 6
        private const val SELF_REPORTED_RECALL_WEIGHT = 0.35
        private const val SELF_REPORTED_STUCK_WEIGHT = 0.5
        private const val PROJECTION_BATCH_SIZE = 100
        private const val MAX_CAS_RETRIES = 4
        private const val MAX_PROJECTION_DRAIN_STEPS = 64
        private const val EVENT_KIND_ATTEMPT = "ATTEMPT"
        private const val EVENT_KIND_CORRECTION = "ATTEMPT_CORRECTION"
        private const val EVENT_KIND_ANSWER_REVEAL = "ANSWER_REVEAL_OUTCOME"
        private const val EVENT_KIND_TUTOR_ANSWER_EXPOSURE = "TUTOR_ANSWER_EXPOSURE_OUTCOME"
        private const val EVENT_KIND_LEARNING_OBSERVATION = "ATTRIBUTED_LEARNING_OBSERVATION"
    }
}

private fun List<ReviewedKnowledgeCoverageRecord>.toKnowledgeCoverageOverview(
    groundingSummaries: List<KnowledgeGroundingSummaryRecord>,
): StudyKnowledgeCoverageOverview {
    val reviewedSubjects = map { summary ->
        StudyKnowledgeSubjectCoverage(
            subject = SubjectKind.valueOf(summary.subject),
            topicCount = summary.topicCount,
            atomicKnowledgeCount = summary.atomicKnowledgeCount,
            reviewedSourceCount = summary.reviewedSourceCount,
            latestReviewedAtEpochMillis = summary.latestReviewedAtEpochMillis,
        )
    }.sortedBy { coverage -> coverage.subject.ordinal }
    val pendingGaps = groundingSummaries.map { summary ->
        StudyKnowledgeCoverageGap(
            groundingKey = summary.groundingKey,
            subject = SubjectKind.valueOf(summary.subject),
            expectedParentKnowledgeDisplayName = summary.expectedParentKnowledgeDisplayName,
            query = summary.query,
            relatedQuestionCount = summary.relatedQuestionCount,
            firstObservedAtEpochMillis = summary.firstObservedAtEpochMillis,
            lastObservedAtEpochMillis = summary.lastObservedAtEpochMillis,
        )
    }
    return StudyKnowledgeCoverageOverview(
        reviewedSubjects = reviewedSubjects,
        pendingGaps = pendingGaps,
    )
}
