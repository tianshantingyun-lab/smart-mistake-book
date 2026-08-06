package com.tingyun.smartmistakebook.core.student.mistake.database


internal class RoomLearnerBoundStudentReviewSessionPort(
    private val dao: StudentMistakeDao,
    private val reviewDao: StudentReviewDao,
    private val learnerId: String,
) : LearnerBoundStudentReviewSessionPort {
    init {
        learnerId.requireStoreText("Review-session learner id", MAX_ID_CHARS)
    }

    override suspend fun startOrResume(
        command: StartStudentReviewSessionCommand,
    ): StartStudentReviewSessionResult {
        val result =
            dao.startOrResumeReviewSession(
                StartStudentReviewSessionBundle(
                    session =
                        StudentReviewSessionEntity(
                            sessionId = command.sessionId,
                            sessionCanonicalFingerprint = command.canonicalFingerprint(learnerId),
                            learnerId = learnerId,
                            planId = command.planId,
                            activeLearnerId = learnerId,
                            state = StudentReviewSessionState.ACTIVE.name,
                            sessionVersion = 1,
                            currentQueueItemId = null,
                            currentPresentationId = null,
                            startedAtEpochMillis = command.startedAtEpochMillis,
                            updatedAtEpochMillis = command.startedAtEpochMillis,
                            completedAtEpochMillis = null,
                        ),
                    expectedPlanCanonicalFingerprint =
                        command.expectedPlanCanonicalFingerprint,
                ),
            )
        return when (result.disposition) {
            StartStudentReviewSessionDaoDisposition.READY ->
                result.session
                    ?.let { session -> readSnapshot(session.sessionId) }
                    ?.let(StartStudentReviewSessionResult::Ready)
                    ?: StartStudentReviewSessionResult.ReloadRequired

            StartStudentReviewSessionDaoDisposition.ACTIVE_SESSION_CONFLICT ->
                result.session
                    ?.let { session -> readSnapshot(session.sessionId) }
                    ?.let(StartStudentReviewSessionResult::ActiveSessionConflict)
                    ?: StartStudentReviewSessionResult.ReloadRequired

            StartStudentReviewSessionDaoDisposition.LEGACY_ACTIVITY_CONFLICT ->
                StartStudentReviewSessionResult.LegacyActivityConflict

            StartStudentReviewSessionDaoDisposition.RELOAD_REQUIRED ->
                StartStudentReviewSessionResult.ReloadRequired
        }
    }

    override suspend fun readActiveSession(): StudentReviewSessionSnapshot? {
        val bundle = reviewDao.readActiveReviewSessionSnapshot(learnerId) ?: return null
        return bundle.toSnapshotOrNull()
    }

    override suspend fun readHomeSnapshot(
        localDayEpochDay: Long,
    ): StudentReviewHomeAuthoritySnapshot {
        val bundle =
            reviewDao.readReviewHomeSnapshot(
                learnerId = learnerId,
                localDayEpochDay = localDayEpochDay,
            )
        val plan =
            bundle.plan?.let { entity ->
                check(entity.learnerId == learnerId) {
                    "Review-home plan belongs to another learner"
                }
                check(entity.localDayEpochDay == localDayEpochDay) {
                    "Review-home plan belongs to another local day"
                }
                StudentReviewPlanSnapshot(
                    planId = entity.planId,
                    planCanonicalFingerprint = entity.planCanonicalFingerprint,
                    learnerId = entity.learnerId,
                    localDayEpochDay = entity.localDayEpochDay,
                    timeZoneId = entity.timeZoneId,
                    timeBudgetSeconds = entity.timeBudgetSeconds,
                    generatedAtEpochMillis = entity.generatedAtEpochMillis,
                    plannerVersion = entity.plannerVersion,
                    items = bundle.planItems.map(StudentReviewQueueReadRow::toDomain),
                )
            }
        val activeSession =
            bundle.activeSession?.let { sessionBundle ->
                checkNotNull(sessionBundle.toSnapshotOrNull()) {
                    "Review-home active session is internally inconsistent"
                }
            }
        return StudentReviewHomeAuthoritySnapshot(
            changeVersion = bundle.changeVersion,
            plan = plan,
            activeSession = activeSession,
        )
    }

    override suspend fun removeUnreadyQueueItem(
        command: RemoveUnreadyStudentReviewQueueItemCommand,
    ): StudentReviewQueueMaintenanceResult =
        dao.removeUnreadyReviewQueueItem(
            learnerId = learnerId,
            command = command,
        )

    override suspend fun submitResponse(
        command: SubmitStudentReviewResponseCommand,
    ): StudentReviewTransitionResult =
        // V1 accepted caller-provided correctness and a public response digest. Production answer
        // writes now exist only on LearnerBoundStudentTrustedReviewAnswerPort.
        StudentReviewTransitionResult.ReloadRequired

    override suspend fun reportDone(
        command: ReportStudentReviewDoneCommand,
    ): StudentReviewTransitionResult {
        val context =
            readCurrentContext(command)
                ?: readReplayContext(command.transitionId)
                ?: return StudentReviewTransitionResult.ReloadRequired
        return applyPacingTransition(
            context = context,
            action = StudentReviewSessionActionKind.DONE,
            transitionId = command.transitionId,
            transitionCanonicalFingerprint = command.canonicalFingerprint(learnerId),
            sessionId = command.sessionId,
            queueItemId = command.queueItemId,
            expectedSessionVersion = command.expectedSessionVersion,
            presentationId = command.presentationId,
            elapsedDurationMillis = command.elapsedDurationMillis,
            schedule = command.schedule,
            occurredAtEpochMillis = command.reportedAtEpochMillis,
        )
    }

    override suspend fun reportStuck(
        command: ReportStudentReviewStuckCommand,
    ): StudentReviewTransitionResult {
        val context =
            readCurrentContext(command)
                ?: readReplayContext(command.transitionId)
                ?: return StudentReviewTransitionResult.ReloadRequired
        return applyPacingTransition(
            context = context,
            action = StudentReviewSessionActionKind.STUCK,
            transitionId = command.transitionId,
            transitionCanonicalFingerprint = command.canonicalFingerprint(learnerId),
            sessionId = command.sessionId,
            queueItemId = command.queueItemId,
            expectedSessionVersion = command.expectedSessionVersion,
            presentationId = command.presentationId,
            elapsedDurationMillis = command.elapsedDurationMillis,
            schedule = command.schedule,
            occurredAtEpochMillis = command.reportedAtEpochMillis,
        )
    }

    private suspend fun applyPacingTransition(
        context: CurrentReviewContext,
        action: StudentReviewSessionActionKind,
        transitionId: String,
        transitionCanonicalFingerprint: String,
        sessionId: String,
        queueItemId: String,
        expectedSessionVersion: Long,
        presentationId: String,
        elapsedDurationMillis: Long,
        schedule: StudentReviewScheduleUpdate,
        occurredAtEpochMillis: Long,
    ): StudentReviewTransitionResult =
        applyTransition(
            sessionId = sessionId,
            bundle =
                ApplyStudentReviewTransitionBundle(
                    receipt =
                        StudentReviewTransitionReceiptEntity(
                            transitionId = transitionId,
                            transitionCanonicalFingerprint = transitionCanonicalFingerprint,
                            learnerId = learnerId,
                            planId = context.planId,
                            sessionId = sessionId,
                            queueItemId = queueItemId,
                            actionKind = action.name,
                            expectedSessionVersion = expectedSessionVersion,
                            resultingSessionVersion = expectedSessionVersion + 1,
                            presentationId = presentationId,
                            observationId = null,
                            submissionId = null,
                            responseForm = null,
                            responseCanonicalFingerprint = null,
                            verificationOutcome = null,
                            attemptOrdinal = null,
                            hintCount = null,
                            answerWasRevealed = null,
                            verificationPolicyVersion = null,
                            elapsedDurationMillis = elapsedDurationMillis,
                            nextAvailableAtEpochMillis =
                                schedule.nextAvailableAtEpochMillis,
                            nextDueAtEpochMillis = schedule.nextDueAtEpochMillis,
                            schedulingPolicyVersion =
                                schedule.schedulingPolicyVersion,
                            outboxEventId = null,
                            occurredAtEpochMillis = occurredAtEpochMillis,
                        ),
                    revealReceipt = null,
                    outbox = null,
                ),
        )

    override suspend fun revealAnswer(
        command: RevealStudentReviewAnswerCommand,
    ): StudentReviewTransitionResult {
        val context =
            readCurrentContext(command)
                ?: readReplayContext(command.transitionId)
                ?: return StudentReviewTransitionResult.ReloadRequired
        return applyTransition(
            sessionId = command.sessionId,
            bundle =
                ApplyStudentReviewTransitionBundle(
                    receipt =
                        StudentReviewTransitionReceiptEntity(
                            transitionId = command.transitionId,
                            transitionCanonicalFingerprint =
                                command.canonicalFingerprint(learnerId),
                            learnerId = learnerId,
                            planId = context.planId,
                            sessionId = command.sessionId,
                            queueItemId = command.queueItemId,
                            actionKind = StudentReviewSessionActionKind.REVEAL.name,
                            expectedSessionVersion = command.expectedSessionVersion,
                            resultingSessionVersion = command.expectedSessionVersion + 1,
                            presentationId = command.presentationId,
                            observationId = null,
                            submissionId = null,
                            responseForm = null,
                            responseCanonicalFingerprint = null,
                            verificationOutcome = null,
                            attemptOrdinal = null,
                            hintCount = null,
                            answerWasRevealed = null,
                            verificationPolicyVersion = null,
                            elapsedDurationMillis = null,
                            nextAvailableAtEpochMillis =
                                command.schedule.nextAvailableAtEpochMillis,
                            nextDueAtEpochMillis = command.schedule.nextDueAtEpochMillis,
                            schedulingPolicyVersion =
                                command.schedule.schedulingPolicyVersion,
                            outboxEventId = null,
                            occurredAtEpochMillis = command.revealedAtEpochMillis,
                        ),
                    revealReceipt =
                        StudentReviewRevealReceiptEntity(
                            revealId = command.revealId,
                            revealCanonicalFingerprint =
                                command.revealCanonicalFingerprint(learnerId),
                            learnerId = learnerId,
                            planId = context.planId,
                            sessionId = command.sessionId,
                            queueItemId = command.queueItemId,
                            presentationId = command.presentationId,
                            revealedAtEpochMillis = command.revealedAtEpochMillis,
                        ),
                    outbox = null,
                ),
        )
    }

    private suspend fun applyTransition(
        sessionId: String,
        bundle: ApplyStudentReviewTransitionBundle,
    ): StudentReviewTransitionResult {
        val disposition =
            try {
                dao.applyReviewTransition(bundle)
            } catch (_: StudentReviewTransitionConcurrencyException) {
                return StudentReviewTransitionResult.ReloadRequired
            }
        return when (disposition) {
            ApplyStudentReviewTransitionDisposition.APPLIED ->
                readSnapshot(sessionId)
                    ?.let(StudentReviewTransitionResult::Applied)
                    ?: StudentReviewTransitionResult.ReloadRequired

            ApplyStudentReviewTransitionDisposition.DUPLICATE ->
                readSnapshot(sessionId)
                    ?.let(StudentReviewTransitionResult::Duplicate)
                    ?: StudentReviewTransitionResult.ReloadRequired

            ApplyStudentReviewTransitionDisposition.RELOAD_REQUIRED ->
                StudentReviewTransitionResult.ReloadRequired
        }
    }

    private suspend fun readSnapshot(
        sessionId: String,
    ): StudentReviewSessionSnapshot? =
        reviewDao.readReviewSessionSnapshot(
            learnerId = learnerId,
            sessionId = sessionId,
        )?.toSnapshotOrNull()

    private suspend fun readCurrentContext(
        command: SubmitStudentReviewResponseCommand,
    ): CurrentReviewContext? =
        readCurrentContext(
            sessionId = command.sessionId,
            queueItemId = command.queueItemId,
            expectedSessionVersion = command.expectedSessionVersion,
            presentationId = command.presentationId,
        )

    private suspend fun readCurrentContext(
        command: ReportStudentReviewStuckCommand,
    ): CurrentReviewContext? =
        readCurrentContext(
            sessionId = command.sessionId,
            queueItemId = command.queueItemId,
            expectedSessionVersion = command.expectedSessionVersion,
            presentationId = command.presentationId,
        )

    private suspend fun readCurrentContext(
        command: ReportStudentReviewDoneCommand,
    ): CurrentReviewContext? =
        readCurrentContext(
            sessionId = command.sessionId,
            queueItemId = command.queueItemId,
            expectedSessionVersion = command.expectedSessionVersion,
            presentationId = command.presentationId,
        )

    private suspend fun readCurrentContext(
        command: RevealStudentReviewAnswerCommand,
    ): CurrentReviewContext? =
        readCurrentContext(
            sessionId = command.sessionId,
            queueItemId = command.queueItemId,
            expectedSessionVersion = command.expectedSessionVersion,
            presentationId = command.presentationId,
        )

    private suspend fun readCurrentContext(
        sessionId: String,
        queueItemId: String,
        expectedSessionVersion: Long,
        presentationId: String,
    ): CurrentReviewContext? {
        val snapshot = readSnapshot(sessionId) ?: return null
        val currentItem = snapshot.currentItem ?: return null
        if (
            snapshot.status != StudentReviewSessionStatus.ACTIVE ||
            snapshot.sessionVersion != expectedSessionVersion ||
            currentItem.queueItemId != queueItemId ||
            snapshot.currentPresentationId != presentationId
        ) {
            return null
        }
        return CurrentReviewContext(
            planId = snapshot.planId,
            currentItem = currentItem,
        )
    }

    private suspend fun readReplayContext(
        transitionId: String,
    ): CurrentReviewContext? =
        reviewDao.readExistingReviewTransitionContext(
            learnerId = learnerId,
            transitionId = transitionId,
        )?.let { context ->
            CurrentReviewContext(
                planId = context.planId,
                currentItem = context.queueItem.toDomain(),
            )
        }
}

private data class CurrentReviewContext(
    val planId: String,
    val currentItem: StudentReviewQueueItem,
)

private fun StudentReviewSessionSnapshotBundle.toSnapshotOrNull():
    StudentReviewSessionSnapshot? {
    val status =
        enumValues<StudentReviewSessionStatus>()
            .firstOrNull { it.name == session.state }
            ?: return null
    if (session.sessionVersion <= 0 || session.updatedAtEpochMillis < session.startedAtEpochMillis) {
        return null
    }
    val currentItem =
        when (status) {
            StudentReviewSessionStatus.ACTIVE -> {
                val row = currentQueueItem ?: return null
                if (
                    session.activeLearnerId != session.learnerId ||
                    session.currentQueueItemId != row.queueItemId ||
                    session.currentPresentationId == null ||
                    session.completedAtEpochMillis != null ||
                    row.learnerId != session.learnerId ||
                    row.planId != session.planId ||
                    row.state != StudentReviewQueueState.PRESENTED.name
                ) {
                    return null
                }
                row.toDomain()
            }

            StudentReviewSessionStatus.COMPLETED -> {
                if (
                    session.activeLearnerId != null ||
                    session.currentQueueItemId != null ||
                    session.currentPresentationId != null ||
                    session.completedAtEpochMillis == null ||
                    currentQueueItem != null
                ) {
                    return null
                }
                null
            }
        }
    return StudentReviewSessionSnapshot(
        sessionId = session.sessionId,
        planId = session.planId,
        status = status,
        sessionVersion = session.sessionVersion,
        currentItem = currentItem,
        currentPresentationId = session.currentPresentationId,
        startedAtEpochMillis = session.startedAtEpochMillis,
        updatedAtEpochMillis = session.updatedAtEpochMillis,
        completedAtEpochMillis = session.completedAtEpochMillis,
    )
}
