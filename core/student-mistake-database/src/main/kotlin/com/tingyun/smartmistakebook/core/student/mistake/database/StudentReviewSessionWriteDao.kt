package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.model.storage.LearningEvidenceRef
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV2
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind

/**
 * Review session, queue transition and self-report write transactions.
 */
@Dao
internal abstract class StudentReviewSessionWriteDao : StudentReviewScheduleWriteDao() {
    @Transaction
    open suspend fun startOrResumeReviewSession(
        bundle: StartStudentReviewSessionBundle,
    ): StartStudentReviewSessionDaoResult {
        val requested = bundle.session
        check(
            requested.state == StudentReviewSessionState.ACTIVE.name &&
                requested.activeLearnerId == requested.learnerId &&
                requested.sessionVersion == 1L &&
                requested.currentQueueItemId == null &&
                requested.currentPresentationId == null &&
                requested.completedAtEpochMillis == null,
        ) {
            "New review session has an invalid initial shape"
        }
        readReviewSession(requested.sessionId)?.let { existing ->
            check(
                existing.sessionCanonicalFingerprint == requested.sessionCanonicalFingerprint &&
                    existing.learnerId == requested.learnerId &&
                    existing.planId == requested.planId &&
                    existing.startedAtEpochMillis == requested.startedAtEpochMillis,
            ) {
                "Review session id was replayed with different immutable content"
            }
            return StartStudentReviewSessionDaoResult(
                disposition = StartStudentReviewSessionDaoDisposition.READY,
                session = existing,
            )
        }
        readActiveReviewSession(requested.learnerId)?.let { active ->
            if (!active.hasCoherentActiveReviewOwnership(requested.learnerId)) {
                return StartStudentReviewSessionDaoResult(
                    disposition = StartStudentReviewSessionDaoDisposition.RELOAD_REQUIRED,
                    session = null,
                )
            }
            return StartStudentReviewSessionDaoResult(
                disposition =
                    if (active.planId == requested.planId) {
                        StartStudentReviewSessionDaoDisposition.READY
                    } else {
                        StartStudentReviewSessionDaoDisposition.ACTIVE_SESSION_CONFLICT
                    },
                session = active,
            )
        }
        readLatestReviewSessionForPlan(
            learnerId = requested.learnerId,
            planId = requested.planId,
        )?.let { completed ->
            if (
                completed.state != StudentReviewSessionState.COMPLETED.name ||
                completed.activeLearnerId != null ||
                completed.currentQueueItemId != null ||
                completed.currentPresentationId != null ||
                completed.completedAtEpochMillis == null
            ) {
                return StartStudentReviewSessionDaoResult(
                    disposition = StartStudentReviewSessionDaoDisposition.RELOAD_REQUIRED,
                    session = null,
                )
            }
            return StartStudentReviewSessionDaoResult(
                disposition = StartStudentReviewSessionDaoDisposition.READY,
                session = completed,
            )
        }
        val plan = readReviewPlan(requested.planId)
        if (
            plan == null ||
            plan.learnerId != requested.learnerId ||
            plan.planCanonicalFingerprint != bundle.expectedPlanCanonicalFingerprint ||
            requested.startedAtEpochMillis < plan.generatedAtEpochMillis
        ) {
            return StartStudentReviewSessionDaoResult(
                disposition = StartStudentReviewSessionDaoDisposition.RELOAD_REQUIRED,
                session = null,
            )
        }
        if (hasUnownedPresentedReviewItem(requested.learnerId)) {
            return StartStudentReviewSessionDaoResult(
                disposition = StartStudentReviewSessionDaoDisposition.LEGACY_ACTIVITY_CONFLICT,
                session = null,
            )
        }
        val firstItem =
            readNextReadyReviewQueueItem(
                learnerId = requested.learnerId,
                planId = requested.planId,
                afterScheduledOrder = -1,
            )
        if (
            firstItem != null &&
            (
                requested.startedAtEpochMillis < firstItem.stateChangedAtEpochMillis ||
                    !isReviewQueueItemEligible(firstItem)
            )
        ) {
            return StartStudentReviewSessionDaoResult(
                disposition = StartStudentReviewSessionDaoDisposition.RELOAD_REQUIRED,
                session = null,
            )
        }
        val persisted =
            if (firstItem == null) {
                requested.copy(
                    activeLearnerId = null,
                    state = StudentReviewSessionState.COMPLETED.name,
                    completedAtEpochMillis = requested.startedAtEpochMillis,
                )
            } else {
                requested.copy(
                    currentQueueItemId = firstItem.queueItemId,
                    currentPresentationId =
                        reviewPresentationId(
                            sessionId = requested.sessionId,
                            item = firstItem,
                        ),
                )
            }
        if (insertReviewSession(persisted) == -1L) {
            val active = readActiveReviewSession(requested.learnerId)
            return StartStudentReviewSessionDaoResult(
                disposition =
                    if (active?.hasCoherentActiveReviewOwnership(requested.learnerId) == true) {
                        StartStudentReviewSessionDaoDisposition.ACTIVE_SESSION_CONFLICT
                    } else {
                        StartStudentReviewSessionDaoDisposition.RELOAD_REQUIRED
                    },
                session = active,
            )
        }
        firstItem?.let { item ->
            insertTrustedReviewFenceIfExact(
                learnerId = requested.learnerId,
                planId = requested.planId,
                sessionId = requested.sessionId,
                item = item,
                presentationId = checkNotNull(persisted.currentPresentationId),
                createdAtEpochMillis = requested.startedAtEpochMillis,
            )
        }
        firstItem?.let { item ->
            check(
                compareAndSetReviewQueueItem(
                    queueItemId = item.queueItemId,
                    planId = item.planId,
                    learnerId = item.learnerId,
                    expectedState = StudentReviewQueueState.READY.name,
                    nextState = StudentReviewQueueState.PRESENTED.name,
                    changedAtEpochMillis = requested.startedAtEpochMillis,
                ) == 1,
            ) {
                "Review queue changed while its session was starting"
            }
        }
        bumpChangeVersion(requested.learnerId)
        return StartStudentReviewSessionDaoResult(
            disposition = StartStudentReviewSessionDaoDisposition.READY,
            session = persisted,
        )
    }

    /**
     * Removes only an unpresented queue item whose exact saved revision is no longer reviewable.
     *
     * The command fingerprint is persisted as the sole reason code, making a crash replay
     * distinguishable from a different maintenance decision without adding a second authority or
     * emitting learning evidence.
     */
    @Transaction
    open suspend fun removeUnreadyReviewQueueItem(
        learnerId: String,
        command: RemoveUnreadyStudentReviewQueueItemCommand,
    ): StudentReviewQueueMaintenanceResult {
        if (command.expectedProblemRevision.problem.learnerId != learnerId) {
            return StudentReviewQueueMaintenanceResult.ReloadRequired
        }
        val auditReason = command.auditReasonCode(learnerId)
        val item =
            readReviewQueueItem(command.queueItemId)
                ?: return StudentReviewQueueMaintenanceResult.ReloadRequired
        if (item.learnerId != learnerId) {
            return StudentReviewQueueMaintenanceResult.ReloadRequired
        }
        val plan =
            readReviewPlan(command.planId)
                ?: return StudentReviewQueueMaintenanceResult.ReloadRequired
        if (
            item.planId != plan.planId ||
            plan.learnerId != learnerId ||
            plan.planCanonicalFingerprint != command.expectedPlanCanonicalFingerprint ||
            command.changedAtEpochMillis < plan.generatedAtEpochMillis
        ) {
            return StudentReviewQueueMaintenanceResult.ReloadRequired
        }
        if (item.state == StudentReviewQueueState.REMOVED.name) {
            return if (decodeOrderedStrings(item.reasonCodesWire) == listOf(auditReason)) {
                StudentReviewQueueMaintenanceResult.Duplicate
            } else {
                StudentReviewQueueMaintenanceResult.ReloadRequired
            }
        }
        if (
            item.state != StudentReviewQueueState.READY.name ||
            command.changedAtEpochMillis < item.stateChangedAtEpochMillis ||
            readActiveReviewSession(learnerId) != null
        ) {
            return StudentReviewQueueMaintenanceResult.ReloadRequired
        }
        val exactQueueItem =
            readReviewQueueRow(
                learnerId = learnerId,
                queueItemId = item.queueItemId,
            )?.toDomain()
                ?: return StudentReviewQueueMaintenanceResult.ReloadRequired
        if (exactQueueItem.problemRevision != command.expectedProblemRevision) {
            return StudentReviewQueueMaintenanceResult.ReloadRequired
        }

        if (readReviewQueueRemovalReason(item) != command.reason) {
            return StudentReviewQueueMaintenanceResult.ReloadRequired
        }

        if (
            compareAndRemoveUnreadyReviewQueueItem(
                queueItemId = item.queueItemId,
                planId = item.planId,
                learnerId = item.learnerId,
                expectedScheduledOrder = item.scheduledOrder,
                expectedPracticeUnitId = item.practiceUnitId,
                expectedBasisRevisionId = item.basisRevisionId,
                expectedStateChangedAtEpochMillis = item.stateChangedAtEpochMillis,
                auditReasonCodesWire = encodeCanonicalSet(setOf(auditReason)),
                changedAtEpochMillis = command.changedAtEpochMillis,
            ) != 1
        ) {
            return StudentReviewQueueMaintenanceResult.ReloadRequired
        }
        bumpChangeVersion(learnerId)
        return StudentReviewQueueMaintenanceResult.Applied
    }

    @Transaction
    open suspend fun applyReviewTransition(
        bundle: ApplyStudentReviewTransitionBundle,
    ): ApplyStudentReviewTransitionDisposition {
        val receipt = bundle.receipt
        readReviewTransitionReceipt(receipt.transitionId)?.let { existing ->
            check(existing == receipt) {
                "Review transition id was replayed with different immutable content"
            }
            bundle.revealReceipt?.let { reveal ->
                check(readReviewRevealReceipt(reveal.revealId) == reveal) {
                    "Review reveal transition is missing its exact reveal receipt"
                }
            }
            bundle.outbox?.let { outbox ->
                check(
                    readOutboxByEventId(outbox.eventId)
                        ?.sameImmutableOutboxContent(outbox) == true,
                ) {
                    "Review response transition is missing its exact outbox event"
                }
            }
            return ApplyStudentReviewTransitionDisposition.DUPLICATE
        }
        val session = readReviewSession(receipt.sessionId)
            ?: return ApplyStudentReviewTransitionDisposition.RELOAD_REQUIRED
        if (
            !session.hasCoherentActiveReviewOwnership(receipt.learnerId) ||
            session.planId != receipt.planId ||
            session.sessionVersion != receipt.expectedSessionVersion ||
            session.currentQueueItemId != receipt.queueItemId ||
            session.currentPresentationId != receipt.presentationId ||
            receipt.expectedSessionVersion == Long.MAX_VALUE ||
            receipt.resultingSessionVersion != receipt.expectedSessionVersion + 1L
        ) {
            return ApplyStudentReviewTransitionDisposition.RELOAD_REQUIRED
        }
        val currentItem = readReviewQueueItem(receipt.queueItemId)
            ?: return ApplyStudentReviewTransitionDisposition.RELOAD_REQUIRED
        if (
            currentItem.learnerId != receipt.learnerId ||
            currentItem.planId != receipt.planId ||
            currentItem.state != StudentReviewQueueState.PRESENTED.name ||
            receipt.occurredAtEpochMillis < currentItem.stateChangedAtEpochMillis ||
            receipt.occurredAtEpochMillis < session.updatedAtEpochMillis ||
            !isReviewQueueItemEligible(currentItem) ||
            readReviewTransitionReceiptForQueueItem(receipt.queueItemId) != null ||
            !bundle.hasValidReviewTransitionShape()
        ) {
            return ApplyStudentReviewTransitionDisposition.RELOAD_REQUIRED
        }
        val futureReadyItems =
            readFutureReadyReviewQueueItems(
                learnerId = receipt.learnerId,
                planId = receipt.planId,
                afterScheduledOrder = currentItem.scheduledOrder,
                limit = MAX_STORED_REVIEW_ITEMS + 1,
            )
        if (
            futureReadyItems.size > MAX_STORED_REVIEW_ITEMS ||
            futureReadyItems.zipWithNext().any { (before, after) ->
                before.scheduledOrder >= after.scheduledOrder
            } ||
            futureReadyItems.any { future ->
                future.learnerId != receipt.learnerId ||
                    future.planId != receipt.planId ||
                    future.state != StudentReviewQueueState.READY.name ||
                    future.scheduledOrder <= currentItem.scheduledOrder
            }
        ) {
            return ApplyStudentReviewTransitionDisposition.RELOAD_REQUIRED
        }
        val unreadyItems = mutableListOf<FutureUnreadyReviewQueueItem>()
        var nextItem: StudentReviewQueueItemEntity? = null
        futureReadyItems.forEach { future ->
            if (nextItem != null) return@forEach
            if (receipt.occurredAtEpochMillis < future.stateChangedAtEpochMillis) {
                return ApplyStudentReviewTransitionDisposition.RELOAD_REQUIRED
            }
            val removalReason = readReviewQueueRemovalReason(future)
            if (removalReason == null) {
                nextItem = future
            } else {
                unreadyItems +=
                    FutureUnreadyReviewQueueItem(
                        item = future,
                        reason = removalReason,
                    )
            }
        }
        val nextPresentationId =
            nextItem?.let { item ->
                reviewPresentationId(
                    sessionId = receipt.sessionId,
                    item = item,
                )
            }
        if (
            compareAndSetReviewSession(
                sessionId = receipt.sessionId,
                learnerId = receipt.learnerId,
                planId = receipt.planId,
                expectedSessionVersion = receipt.expectedSessionVersion,
                expectedQueueItemId = receipt.queueItemId,
                expectedPresentationId = receipt.presentationId,
                activeLearnerId = nextItem?.learnerId,
                nextState =
                    if (nextItem == null) {
                        StudentReviewSessionState.COMPLETED.name
                    } else {
                        StudentReviewSessionState.ACTIVE.name
                    },
                nextQueueItemId = nextItem?.queueItemId,
                nextPresentationId = nextPresentationId,
                updatedAtEpochMillis = receipt.occurredAtEpochMillis,
                completedAtEpochMillis =
                    if (nextItem == null) receipt.occurredAtEpochMillis else null,
            ) != 1
        ) {
            throw StudentReviewTransitionConcurrencyException()
        }
        if (
            compareAndSetReviewQueueItem(
                queueItemId = currentItem.queueItemId,
                planId = currentItem.planId,
                learnerId = currentItem.learnerId,
                expectedState = StudentReviewQueueState.PRESENTED.name,
                nextState = StudentReviewQueueState.COMPLETED.name,
                changedAtEpochMillis = receipt.occurredAtEpochMillis,
            ) != 1
        ) {
            throw StudentReviewTransitionConcurrencyException()
        }
        unreadyItems.forEach { unready ->
            if (
                compareAndRemoveUnreadyReviewQueueItem(
                    queueItemId = unready.item.queueItemId,
                    planId = unready.item.planId,
                    learnerId = unready.item.learnerId,
                    expectedScheduledOrder = unready.item.scheduledOrder,
                    expectedPracticeUnitId = unready.item.practiceUnitId,
                    expectedBasisRevisionId = unready.item.basisRevisionId,
                    expectedStateChangedAtEpochMillis =
                        unready.item.stateChangedAtEpochMillis,
                    auditReasonCodesWire =
                        encodeCanonicalSet(
                            setOf(
                                transitionUnreadyAuditReasonCode(
                                    receipt = receipt,
                                    item = unready.item,
                                    reason = unready.reason,
                                ),
                            ),
                        ),
                    changedAtEpochMillis = receipt.occurredAtEpochMillis,
                ) != 1
            ) {
                throw StudentReviewTransitionConcurrencyException()
            }
        }
        nextItem?.let { item ->
            if (
                compareAndSetReviewQueueItem(
                    queueItemId = item.queueItemId,
                    planId = item.planId,
                    learnerId = item.learnerId,
                    expectedState = StudentReviewQueueState.READY.name,
                    nextState = StudentReviewQueueState.PRESENTED.name,
                    changedAtEpochMillis = receipt.occurredAtEpochMillis,
                ) != 1
            ) {
                throw StudentReviewTransitionConcurrencyException()
            }
            insertTrustedReviewFenceIfExact(
                learnerId = receipt.learnerId,
                planId = receipt.planId,
                sessionId = receipt.sessionId,
                item = item,
                presentationId = checkNotNull(nextPresentationId),
                createdAtEpochMillis = receipt.occurredAtEpochMillis,
            )
        }
        applyReviewCandidate(
            nextReviewCandidate(
                item = currentItem,
                availableAtEpochMillis = receipt.nextAvailableAtEpochMillis,
                dueAtEpochMillis = receipt.nextDueAtEpochMillis,
                updatedAtEpochMillis = receipt.occurredAtEpochMillis,
                reasonCode =
                    when (
                        enumValueOrCorrupt<StudentReviewSessionActionKind>(
                            receipt.actionKind,
                            "review transition action",
                        )
                    ) {
                        StudentReviewSessionActionKind.RESPONSE ->
                            if (receipt.answerWasRevealed == true) {
                                REVIEW_REVEALED_REASON
                            } else {
                                REVIEW_RESPONSE_REASON
                            }
                        StudentReviewSessionActionKind.DONE -> REVIEW_DONE_REASON
                        StudentReviewSessionActionKind.STUCK -> REVIEW_STUCK_REASON
                        StudentReviewSessionActionKind.REVEAL -> REVIEW_REVEALED_REASON
                    },
                sourceEvidence = null,
                preserveExistingEvidence = true,
                observedDurationMillis = receipt.elapsedDurationMillis,
            ),
            recordChange = false,
        )
        bundle.revealReceipt?.let { reveal ->
            check(
                readReviewRevealReceiptForQueueItem(
                    sessionId = reveal.sessionId,
                    queueItemId = reveal.queueItemId,
                ) == null,
            ) {
                "Review item already has a different reveal receipt"
            }
            insertReviewRevealReceipt(reveal)
        }
        insertReviewTransitionReceipt(receipt)
        bundle.outbox?.let { insertOutboxExactlyOnce(it) }
        bumpChangeVersion(receipt.learnerId)
        return ApplyStudentReviewTransitionDisposition.APPLIED
    }

    @Transaction
    open suspend fun transitionReviewQueueItem(
        learnerId: String,
        queueItemId: String,
        expectedState: StudentReviewQueueState,
        nextState: StudentReviewQueueState,
        changedAtEpochMillis: Long,
    ) {
        val item = checkNotNull(readReviewQueueItem(queueItemId)) {
            "Review queue item does not exist"
        }
        check(item.learnerId == learnerId) {
            "Review queue item belongs to another learner"
        }
        val persistedState =
            enumValueOrCorrupt<StudentReviewQueueState>(item.state, "review queue state")
        check(persistedState == expectedState) {
            "Review queue item state changed before the requested transition"
        }
        check(changedAtEpochMillis >= item.stateChangedAtEpochMillis) {
            "Review queue state change is older than the persisted state"
        }
        check(persistedState.canTransitionTo(nextState)) {
            "Review queue state transition is not allowed"
        }
        if (persistedState == nextState) return
        if (nextState != StudentReviewQueueState.REMOVED) {
            val problem = checkNotNull(readProblemByPracticeUnit(item.practiceUnitId)) {
                "Review queue problem does not exist"
            }
            val collection = checkNotNull(readCollection(item.practiceUnitId)) {
                "Review queue item no longer belongs to the mistake collection"
            }
            check(
                problem.lifecycleState == StudentProblemLifecycleState.ACTIVE.name &&
                    collection.mistakeState == StudentMistakeEntryState.ACTIVE.name,
            ) {
                "Only an active mistake entry may continue review"
            }
        }
        check(
            updateReviewQueueItem(
                item.copy(
                    state = nextState.name,
                    stateChangedAtEpochMillis = changedAtEpochMillis,
                ),
            ) == 1,
        ) {
            "Review queue state transition did not affect exactly one row"
        }
        bumpChangeVersion(learnerId)
    }

    @Transaction
    open suspend fun recordReviewSelfReportAndComplete(
        receipt: StudentReviewSelfReportReceiptEntity,
    ) {
        readReviewSelfReportReceipt(receipt.selfReportId)?.let { existing ->
            check(existing == receipt) {
                "Review self-report id was replayed with different immutable content"
            }
            val item = checkNotNull(readReviewQueueItem(receipt.queueItemId)) {
                "Review self-report receipt exists without its queue item"
            }
            check(
                item.learnerId == receipt.learnerId &&
                    item.state == StudentReviewQueueState.COMPLETED.name,
            ) {
                "Review self-report receipt does not match persisted queue state"
            }
            return
        }
        check(readReviewSelfReportReceiptForQueueItem(receipt.queueItemId) == null) {
            "Review queue item already has a different self-report"
        }
        val item = checkNotNull(readReviewQueueItem(receipt.queueItemId)) {
            "Review self-report references a missing queue item"
        }
        check(item.learnerId == receipt.learnerId) {
            "Review self-report belongs to another learner"
        }
        val state =
            enumValueOrCorrupt<StudentReviewQueueState>(
                item.state,
                "review queue state",
            )
        check(
            state == StudentReviewQueueState.READY ||
                state == StudentReviewQueueState.PRESENTED,
        ) {
            "Only an unfinished review queue item accepts a self-report"
        }
        check(receipt.reportedAtEpochMillis >= item.stateChangedAtEpochMillis) {
            "Review self-report predates the queue state"
        }
        val nextCandidate =
            nextReviewCandidate(
                item = item,
                availableAtEpochMillis = receipt.nextAvailableAtEpochMillis,
                dueAtEpochMillis = receipt.nextDueAtEpochMillis,
                updatedAtEpochMillis = receipt.reportedAtEpochMillis,
                reasonCode =
                    when (
                        enumValueOrCorrupt<StudentReviewSelfReportKind>(
                            receipt.reportKind,
                            "review self-report kind",
                        )
                    ) {
                        StudentReviewSelfReportKind.DONE -> SELF_REPORT_DONE_REASON
                        StudentReviewSelfReportKind.STUCK -> SELF_REPORT_STUCK_REASON
                    },
                sourceEvidence = null,
                preserveExistingEvidence = true,
            )
        applyReviewCandidate(nextCandidate, recordChange = false)
        check(
            updateReviewQueueItem(
                item.copy(
                    state = StudentReviewQueueState.COMPLETED.name,
                    stateChangedAtEpochMillis = receipt.reportedAtEpochMillis,
                ),
            ) == 1,
        ) {
            "Review self-report completion did not affect exactly one queue item"
        }
        insertReviewSelfReportReceipt(receipt)
        bumpChangeVersion(receipt.learnerId)
    }

    protected suspend fun isReviewQueueItemEligible(
        item: StudentReviewQueueItemEntity,
    ): Boolean = readReviewQueueReadiness(item).savedRevisionEligible == 1

    /**
     * Missing or non-exact error-book identity must not block review presentation, but it also must
     * not create the precondition required for strong answer evidence.
     */
    protected suspend fun insertTrustedReviewFenceIfExact(
        learnerId: String,
        planId: String,
        sessionId: String,
        item: StudentReviewQueueItemEntity,
        presentationId: String,
        createdAtEpochMillis: Long,
    ) {
        val problem = readProblemByPracticeUnit(item.practiceUnitId) ?: return
        if (
            item.learnerId != learnerId ||
            item.planId != planId ||
            problem.learnerId != learnerId ||
            problem.primaryPracticeUnitId != item.practiceUnitId ||
            problem.currentRevisionId != item.basisRevisionId ||
            problem.lifecycleState != StudentProblemLifecycleState.ACTIVE.name ||
            problem.errorBookEntryId == null
        ) {
            return
        }
        insertTrustedReviewPresentationFence(
            newStudentTrustedReviewPresentationFence(
                learnerId = learnerId,
                planId = planId,
                sessionId = sessionId,
                queueItem = item,
                presentationId = presentationId,
                problem = problem,
                createdAtEpochMillis = createdAtEpochMillis,
            ),
        )
    }

    protected suspend fun readReviewQueueRemovalReason(
        item: StudentReviewQueueItemEntity,
    ): StudentReviewQueueRemovalReason? {
        val readiness = readReviewQueueReadiness(item)
        return when {
            readiness.savedRevisionEligible != 1 ->
                StudentReviewQueueRemovalReason.SAVED_REVISION_INELIGIBLE
            readiness.acceptedKnowledgeCount == 0 ->
                StudentReviewQueueRemovalReason.PENDING_KNOWLEDGE_ATTRIBUTION
            else -> null
        }
    }

    protected suspend fun readReviewQueueReadiness(
        item: StudentReviewQueueItemEntity,
    ): StudentReviewQueueLocalReadinessRow {
        val readiness =
            checkNotNull(readReviewQueueLocalReadiness(item.queueItemId)) {
                "Persisted review queue item is missing its local-readiness projection"
            }
        check(
            readiness.queueItemId == item.queueItemId &&
                readiness.planId == item.planId &&
                readiness.learnerId == item.learnerId &&
                readiness.basisRevisionId == item.basisRevisionId,
        ) {
            "Review queue local-readiness ownership is inconsistent"
        }
        check(readiness.savedRevisionEligible in 0..1) {
            "Review queue local-readiness saved-revision flag is invalid"
        }
        check(readiness.acceptedKnowledgeCount in 0..MAX_STORED_CLASSIFICATIONS) {
            "Review queue local-readiness knowledge count exceeds the supported bound"
        }
        return readiness
    }

    protected fun ApplyStudentReviewTransitionBundle.hasValidReviewTransitionShape(): Boolean {
        val responseColumns =
            listOf(
                receipt.observationId,
                receipt.submissionId,
                receipt.responseForm,
                receipt.responseCanonicalFingerprint,
                receipt.verificationOutcome,
                receipt.attemptOrdinal,
                receipt.hintCount,
                receipt.answerWasRevealed,
                receipt.verificationPolicyVersion,
            )
        if (
            receipt.nextAvailableAtEpochMillis < receipt.occurredAtEpochMillis ||
            (
                receipt.elapsedDurationMillis != null &&
                    receipt.elapsedDurationMillis !in
                    0..MAX_REVIEW_PACING_ELAPSED_DURATION_MILLIS
            ) ||
            (
                receipt.nextDueAtEpochMillis != null &&
                    receipt.nextDueAtEpochMillis < receipt.nextAvailableAtEpochMillis
            )
        ) {
            return false
        }
        return when (
            enumValueOrCorrupt<StudentReviewSessionActionKind>(
                receipt.actionKind,
                "review transition action",
            )
        ) {
            StudentReviewSessionActionKind.RESPONSE ->
                responseColumns.all { it != null } &&
                    revealReceipt == null &&
                    outbox != null &&
                    receipt.outboxEventId == outbox.eventId &&
                    outbox.sourceStore == StudyStoreKind.STUDENT_MISTAKES.name &&
                    outbox.destinationStore == StudyStoreKind.LEARNER_MASTERY.name &&
                    outbox.learnerId == receipt.learnerId &&
                    outbox.aggregateId == receipt.observationId &&
                    outbox.aggregateVersion == receipt.attemptOrdinal?.toLong() &&
                    outbox.payloadType == ReviewObservationCapturedV2.PAYLOAD_TYPE &&
                    outbox.payloadVersion == ReviewObservationCapturedV2.PAYLOAD_VERSION &&
                    outbox.occurredAtEpochMillis == receipt.occurredAtEpochMillis

            StudentReviewSessionActionKind.DONE,
            StudentReviewSessionActionKind.STUCK ->
                responseColumns.all { it == null } &&
                    receipt.elapsedDurationMillis != null &&
                    receipt.outboxEventId == null &&
                    revealReceipt == null &&
                    outbox == null

            StudentReviewSessionActionKind.REVEAL ->
                responseColumns.all { it == null } &&
                    receipt.elapsedDurationMillis == null &&
                    receipt.outboxEventId == null &&
                    outbox == null &&
                    revealReceipt?.let { reveal ->
                        reveal.learnerId == receipt.learnerId &&
                            reveal.planId == receipt.planId &&
                            reveal.sessionId == receipt.sessionId &&
                            reveal.queueItemId == receipt.queueItemId &&
                            reveal.presentationId == receipt.presentationId &&
                            reveal.revealedAtEpochMillis == receipt.occurredAtEpochMillis
                    } == true
        }
    }

    protected suspend fun nextReviewCandidate(
        item: StudentReviewQueueItemEntity,
        availableAtEpochMillis: Long,
        dueAtEpochMillis: Long?,
        updatedAtEpochMillis: Long,
        reasonCode: String,
        sourceEvidence: LearningEvidenceRef?,
        preserveExistingEvidence: Boolean,
        replaceSchedule: Boolean = true,
        observedDurationMillis: Long? = null,
    ): StudentReviewCandidateEntity {
        val practiceUnit = checkNotNull(readPracticeUnit(item.practiceUnitId)) {
            "Review queue practice unit does not exist"
        }
        val existing =
            readLatestReviewCandidate(
                learnerId = item.learnerId,
                practiceUnitId = item.practiceUnitId,
            )
        val existingEvidenceSequence = existing?.sourceEvidenceSequence
        if (
            sourceEvidence != null &&
            existingEvidenceSequence != null &&
            sourceEvidence.eventSequence <= existingEvidenceSequence
        ) {
            return existing
        }
        val reasons =
            addReviewReason(
                encodedReasons = existing?.reasonCodesWire,
                reasonCode = reasonCode,
            )
        val effectiveUpdatedAt =
            maxOf(updatedAtEpochMillis, existing?.updatedAtEpochMillis ?: 0L)
        val effectiveEvidence =
            when {
                sourceEvidence != null -> sourceEvidence
                preserveExistingEvidence -> existing?.toLearningEvidenceRef()
                else -> null
            }
        return StudentReviewCandidateEntity(
            candidateId = existing?.candidateId ?: item.practiceUnitId,
            learnerId = item.learnerId,
            practiceUnitId = item.practiceUnitId,
            basisRevisionId = existing?.basisRevisionId ?: item.basisRevisionId,
            reasonCodesWire = reasons,
            itemFamilyId = practiceUnit.itemFamilyId,
            estimatedDurationSeconds =
                rollingReviewDurationSeconds(
                    baselineSeconds =
                        existing?.estimatedDurationSeconds
                            ?: practiceUnit.estimatedDurationSeconds,
                    observedDurationMillis = observedDurationMillis,
                ),
            availableAtEpochMillis =
                if (replaceSchedule) {
                    availableAtEpochMillis
                } else {
                    maxOf(
                        availableAtEpochMillis,
                        existing?.availableAtEpochMillis ?: 0L,
                    )
                },
            dueAtEpochMillis =
                if (replaceSchedule) {
                    dueAtEpochMillis
                } else {
                    existing?.dueAtEpochMillis
                },
            sourceEvidenceEventKind = effectiveEvidence?.eventKind,
            sourceEvidenceEventId = effectiveEvidence?.eventId,
            sourceEvidenceSequence = effectiveEvidence?.eventSequence,
            sourceEvidenceCanonicalFingerprint =
                effectiveEvidence?.eventCanonicalFingerprint,
            candidateVersion = (existing?.candidateVersion ?: 0L) + 1L,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: effectiveUpdatedAt,
            updatedAtEpochMillis = effectiveUpdatedAt,
        )
    }
}
