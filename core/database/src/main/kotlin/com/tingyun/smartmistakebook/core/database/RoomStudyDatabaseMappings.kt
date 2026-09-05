package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.database.dao.CanonicalSourceAssetRow
import com.tingyun.smartmistakebook.core.database.dao.MistakeRow
import com.tingyun.smartmistakebook.core.database.dao.ReviewLogSampleProjection
import com.tingyun.smartmistakebook.core.database.dao.ReviewPlanAggregate
import com.tingyun.smartmistakebook.core.database.dao.activeSessionHead
import com.tingyun.smartmistakebook.core.database.dao.latestSessionHead
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentItemSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.ErrorBookEntryEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeMasteryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeQuestionLatticeView
import com.tingyun.smartmistakebook.core.database.entity.LlmTeachingAdvisoryEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemMemoryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRevisionEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewLogEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewPlanEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueItemEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueKnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueReasonEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionAdvanceReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionRevisionEntity
import com.tingyun.smartmistakebook.core.database.port.KnowledgeQuestionLatticeRecord
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.TeachingAdvisoryRecord

/**
 * Lossless row↔record/seed↔entity mappings shared by [RoomStudyDatabase] and its
 * sub-stores. They live in one same-package file so a new column must be wired
 * through here — a missed field is a compile error at this single location
 * instead of a silent null quietly accepted by whichever store needed the type.
 */

internal fun ProblemSeedRecord.toEntity() = ProblemEntity(
    problemId = problemId,
    canonicalFingerprint = canonicalFingerprint,
    subject = subject,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun ProblemRevisionSeedRecord.toEntity() = ProblemRevisionEntity(
    revisionId = revisionId,
    problemId = problemId,
    revisionNumber = revisionNumber,
    title = title,
    problemMarkdown = problemMarkdown,
    questionDocumentSnapshot = questionDocumentSnapshot,
    answerSpecId = answerSpecId,
    answerSpecSnapshot = answerSpecSnapshot,
    answerVerificationStatus = answerVerificationStatus,
    sourceType = sourceType,
    sourceReference = sourceReference,
    contentFingerprint = contentFingerprint,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun PracticeUnitSeedRecord.toEntity() = PracticeUnitEntity(
    practiceUnitId = practiceUnitId,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    unitKey = unitKey,
    unitKind = unitKind,
    title = title,
    promptMarkdown = promptMarkdown,
    estimatedSeconds = estimatedSeconds,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun ErrorBookEntrySeedRecord.toEntity() = ErrorBookEntryEntity(
    entryId = entryId,
    practiceUnitId = practiceUnitId,
    problemId = problemId,
    currentRevisionId = currentRevisionId,
    sourceKey = sourceKey,
    status = status,
    acceptedAtEpochMillis = acceptedAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun ProblemRelationSeedRecord.toEntity() = ProblemRelationEntity(
    relationId = relationId,
    sourceProblemId = sourceProblemId,
    targetProblemId = targetProblemId,
    relationType = relationType,
    status = status,
    sourceBasisRevisionId = sourceBasisRevisionId,
    targetBasisRevisionId = targetBasisRevisionId,
    confidence = confidence,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun AssessmentItemSnapshotSeedRecord.toEntity() = AssessmentItemSnapshotEntity(
    assessmentItemSnapshotId = assessmentItemSnapshotId,
    itemRevision = itemRevision,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    tutorContentSnapshotId = tutorContentSnapshotId,
    promptMarkdown = promptMarkdown,
    optionsSnapshot = optionsSnapshot,
    answerSpecSnapshot = answerSpecSnapshot,
    verificationStatus = verificationStatus,
    assessmentEligibility = assessmentEligibility,
    scoringMode = scoringMode,
    learnerSnapshotVersion = learnerSnapshotVersion,
    projectionCheckpoint = projectionCheckpoint,
    hintLevelAtPresentation = hintLevelAtPresentation,
    answerRevealState = answerRevealState,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun AssessmentItemSnapshotEntity.toRecord() = AssessmentItemSnapshotSeedRecord(
    assessmentItemSnapshotId = assessmentItemSnapshotId,
    itemRevision = itemRevision,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    tutorContentSnapshotId = tutorContentSnapshotId,
    promptMarkdown = promptMarkdown,
    optionsSnapshot = optionsSnapshot,
    answerSpecSnapshot = answerSpecSnapshot,
    verificationStatus = verificationStatus,
    assessmentEligibility = assessmentEligibility,
    scoringMode = scoringMode,
    learnerSnapshotVersion = learnerSnapshotVersion,
    projectionCheckpoint = projectionCheckpoint,
    hintLevelAtPresentation = hintLevelAtPresentation,
    answerRevealState = answerRevealState,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun AssessmentEventSeedRecord.toEntity() = AssessmentEventEntity(
    assessmentEventId = assessmentEventId,
    assessmentItemSnapshotId = assessmentItemSnapshotId,
    eventSequence = eventSequence,
    eventType = eventType,
    hintLevel = hintLevel,
    submittedResponse = submittedResponse,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

internal fun ProblemMemoryStateRecord.toEntity() = ProblemMemoryStateEntity(
    practiceUnitId = practiceUnitId,
    stabilityDays = stabilityDays,
    difficulty = difficulty,
    lastReviewedAtEpochMillis = lastReviewedAtEpochMillis,
    nextReviewAtEpochMillis = nextReviewAtEpochMillis,
    reviewCount = reviewCount,
    lapseCount = lapseCount,
    retrievability = retrievability,
    projectionCheckpoint = projectionCheckpoint,
    projectorVersion = projectorVersion,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun KnowledgeMasteryStateRecord.toEntity() = KnowledgeMasteryStateEntity(
    knowledgeNodeId = knowledgeNodeId,
    masteryProbability = masteryProbability,
    independentCorrectCount = independentCorrectCount,
    assistedCorrectCount = assistedCorrectCount,
    incorrectCount = incorrectCount,
    evidenceWeightTotal = evidenceWeightTotal,
    lastEvidenceAtEpochMillis = lastEvidenceAtEpochMillis,
    projectionCheckpoint = projectionCheckpoint,
    projectorVersion = projectorVersion,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun ReviewPlanRecord.toEntity() = ReviewPlanEntity(
    reviewPlanId = reviewPlanId,
    learnerId = learnerId,
    localDate = localDate,
    localDayEpochDay = localDayEpochDay,
    timeZoneId = timeZoneId,
    timeBudgetSeconds = timeBudgetSeconds,
    planningAtEpochMillis = planningAtEpochMillis,
    status = status,
    plannerVersion = plannerVersion,
    projectionCheckpoint = projectionCheckpoint,
    inputFingerprint = inputFingerprint,
    planFingerprint = planFingerprint,
    planRevision = planRevision,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun ReviewQueueItemRecord.toEntity() = ReviewQueueItemEntity(
    reviewQueueItemId = reviewQueueItemId,
    reviewPlanId = reviewPlanId,
    practiceUnitId = practiceUnitId,
    itemFamilyId = itemFamilyId,
    sourceBundleId = sourceBundleId,
    ordinal = ordinal,
    priorityScore = priorityScore,
    difficultyBand = difficultyBand,
    dueAtEpochMillis = dueAtEpochMillis,
    estimatedSeconds = estimatedSeconds,
    reasonSnapshot = reasonSnapshot,
    status = status,
)

internal fun ReviewQueueItemRecord.toKnowledgeNodeEntities() = knowledgeNodeIds
    .sorted()
    .map { ReviewQueueKnowledgeNodeEntity(reviewQueueItemId, it) }

internal fun ReviewQueueItemRecord.toReasonEntities() = reasons
    .sorted()
    .map { ReviewQueueReasonEntity(reviewQueueItemId, it) }

internal fun ReviewSessionRecord.toEntity() = ReviewSessionEntity(
    reviewSessionId = reviewSessionId,
    reviewPlanId = reviewPlanId,
    status = status,
    activeSessionKey = reviewPlanId.takeIf { status == StudyDbValue.ReviewStatus.IN_PROGRESS },
    startedAtEpochMillis = startedAtEpochMillis,
    lastActiveAtEpochMillis = lastActiveAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    currentOrdinal = currentOrdinal,
    timeBudgetSeconds = timeBudgetSeconds,
    projectionCheckpoint = projectionCheckpoint,
    stateVersion = stateVersion,
)

internal fun ReviewSessionRecord.toRevisionEntity() = ReviewSessionRevisionEntity(
    reviewSessionId = reviewSessionId,
    stateVersion = stateVersion,
    reviewPlanId = reviewPlanId,
    status = status,
    startedAtEpochMillis = startedAtEpochMillis,
    lastActiveAtEpochMillis = lastActiveAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    currentOrdinal = currentOrdinal,
    timeBudgetSeconds = timeBudgetSeconds,
    projectionCheckpoint = projectionCheckpoint,
)

internal fun ReviewPlanAggregate.toRecord(): ReviewPlanBundle {
    val sortedQueue = queue.sortedBy { it.item.ordinal }
    return ReviewPlanBundle(
        plan = ReviewPlanRecord(
            reviewPlanId = plan.reviewPlanId,
            learnerId = plan.learnerId,
            localDate = plan.localDate,
            localDayEpochDay = plan.localDayEpochDay,
            timeZoneId = plan.timeZoneId,
            timeBudgetSeconds = plan.timeBudgetSeconds,
            planningAtEpochMillis = plan.planningAtEpochMillis,
            status = plan.status,
            plannerVersion = plan.plannerVersion,
            projectionCheckpoint = plan.projectionCheckpoint,
            inputFingerprint = plan.inputFingerprint,
            planFingerprint = plan.planFingerprint,
            planRevision = plan.planRevision,
            createdAtEpochMillis = plan.createdAtEpochMillis,
        ),
        queue = sortedQueue.map { aggregate ->
            val item = aggregate.item
            ReviewQueueItemRecord(
                reviewQueueItemId = item.reviewQueueItemId,
                reviewPlanId = item.reviewPlanId,
                practiceUnitId = item.practiceUnitId,
                knowledgeNodeIds = aggregate.knowledgeNodes.mapTo(linkedSetOf()) { it.knowledgeNodeId },
                itemFamilyId = item.itemFamilyId,
                sourceBundleId = item.sourceBundleId,
                reasons = aggregate.reasons.mapTo(linkedSetOf()) { it.reason },
                ordinal = item.ordinal,
                priorityScore = item.priorityScore,
                difficultyBand = item.difficultyBand,
                dueAtEpochMillis = item.dueAtEpochMillis,
                estimatedSeconds = item.estimatedSeconds,
                reasonSnapshot = item.reasonSnapshot,
                status = item.status,
            )
        },
        activeSession = activeSessionHead()?.toRecord(),
        isCurrent = currentSlots.isNotEmpty(),
        latestSession = latestSessionHead()?.toRecord(),
    )
}

internal fun ReviewSessionEntity.toRecord() = ReviewSessionRecord(
    reviewSessionId = reviewSessionId,
    reviewPlanId = reviewPlanId,
    status = status,
    startedAtEpochMillis = startedAtEpochMillis,
    lastActiveAtEpochMillis = lastActiveAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    currentOrdinal = currentOrdinal,
    timeBudgetSeconds = timeBudgetSeconds,
    projectionCheckpoint = projectionCheckpoint,
    stateVersion = stateVersion,
)

internal fun ProblemDraftEditWorkspaceRecord.toConfirmedRevision(
    expected: ExpectedProblemDraftEditWorkspace,
): ProblemDraftRevisionRecord {
    val workspace = try {
        DatabaseContractValidator.decodeProblemDraftEditWorkspace(
            snapshotSchemaVersion = snapshotSchemaVersion,
            workspaceSnapshot = workspaceSnapshot,
            workspaceFingerprint = workspaceFingerprint,
        )
    } catch (failure: Exception) {
        throw ProblemDraftEditWorkspaceIntegrityException(
            "Problem-draft workspace $draftId is corrupted",
            failure,
        )
    }
    val finalRequest = workspace.finalConfirmationRequest
        ?: throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId has no final confirmation identity",
        )
    if (
        draftId != expected.draftId ||
        basisRevisionNumber != expected.basisRevisionNumber ||
        workspaceVersion != expected.workspaceVersion ||
        workspaceFingerprint != expected.workspaceFingerprint ||
        finalRequest.requestId != expected.finalRequestId ||
        finalRequest.occurredAtEpochMillis != expected.finalOccurredAtEpochMillis ||
        expected.finalOccurredAtEpochMillis < updatedAtEpochMillis
    ) {
        throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId does not match the final request",
        )
    }
    val subject = workspace.subject
        ?: throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId has no confirmed subject",
        )
    val title = workspace.workingDocument.document.title
        ?.takeIf(String::isNotBlank)
        ?: throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId has no confirmed title",
        )
    if (CapturedQuestionDocumentValidator.validateForCommit(workspace.workingDocument).isNotEmpty()) {
        throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId is not ready to confirm",
        )
    }
    return ProblemDraftRevisionRecord(
        draftId = draftId,
        revisionNumber = basisRevisionNumber + 1,
        basisRevisionNumber = basisRevisionNumber,
        subject = subject,
        title = title,
        questionDocument = workspace.workingDocument,
        documentFingerprint = CapturedQuestionDocumentFingerprint.of(workspace.workingDocument),
        author = StudyDbValue.ProblemDraftAuthor.USER,
        createdAtEpochMillis = expected.finalOccurredAtEpochMillis,
    )
}

internal fun ExpectedProblemDraftEditWorkspace.toConsumeCommand() =
    ConsumeProblemDraftEditWorkspaceCommand(
        draftId = draftId,
        basisRevisionNumber = basisRevisionNumber,
        expectedWorkspaceVersion = workspaceVersion,
        expectedWorkspaceFingerprint = workspaceFingerprint,
    )

internal fun ProblemDraftEditWorkspaceRecord.toConsumeCommand() =
    ConsumeProblemDraftEditWorkspaceCommand(
        draftId = draftId,
        basisRevisionNumber = basisRevisionNumber,
        expectedWorkspaceVersion = workspaceVersion,
        expectedWorkspaceFingerprint = workspaceFingerprint,
    )

internal fun ReviewSessionAdvanceReceiptEntity.toRecord() = ReviewSessionAdvanceReceipt(
    sessionId = reviewSessionId,
    fromVersion = fromVersion,
    toVersion = toVersion,
    reviewQueueItemId = reviewQueueItemId,
    practiceUnitId = practiceUnitId,
    attemptId = attemptId,
    submissionId = submissionId,
    presentationId = presentationId,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

internal fun MistakeRow.toRecord() = MistakeRecord(
    entryId = entryId,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    sourceKey = sourceKey,
    subject = subject,
    title = title,
    problemMarkdown = problemMarkdown,
    status = status,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    nextReviewAtEpochMillis = nextReviewAtEpochMillis,
    retrievability = retrievability,
    estimatedSeconds = estimatedSeconds,
    knowledgeNodeIds = knowledgeNodeIds.toCatalogLabels().toCollection(linkedSetOf()),
    chapterLabels = chapterLabels.toCatalogLabels(),
    knowledgeLabels = knowledgeLabels.toCatalogLabels(),
    captureOccurrenceCount = maxOf(1, captureOccurrenceCount),
)

internal fun CanonicalSourceAssetRow.toRecord() = CanonicalSourceAssetRecord(
    sourceAssetId = sourceAssetId,
    contentSha256 = contentSha256,
    relativePath = relativePath,
    mimeType = mimeType,
    byteSize = byteSize,
    width = width,
    height = height,
    sourceType = sourceType,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun TeachingAdvisoryRecord.toEntity() = LlmTeachingAdvisoryEntity(
    advisoryId = advisoryId,
    learnerId = learnerId,
    practiceUnitId = practiceUnitId,
    knowledgeNodeId = knowledgeNodeId,
    advisoryKind = advisoryKind,
    payloadMarkdown = payloadMarkdown,
    confidence = confidence,
    sourceId = sourceId,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun LlmTeachingAdvisoryEntity.toRecord() = TeachingAdvisoryRecord(
    advisoryId = advisoryId,
    learnerId = learnerId,
    practiceUnitId = practiceUnitId,
    knowledgeNodeId = knowledgeNodeId,
    advisoryKind = advisoryKind,
    payloadMarkdown = payloadMarkdown,
    confidence = confidence,
    sourceId = sourceId,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun KnowledgeQuestionLatticeView.toRecord() = KnowledgeQuestionLatticeRecord(
    practiceUnitId = practiceUnitId,
    knowledgeNodeId = knowledgeNodeId,
    bindingStrength = bindingStrength,
    basisRevisionId = basisRevisionId,
    bindingTaxonomyVersion = bindingTaxonomyVersion,
    entryId = entryId,
    entryStatus = entryStatus,
    kcLearnerId = kcLearnerId,
    memoryLearnerId = memoryLearnerId,
    kcConservativeMastery = kcConservativeMastery,
    kcStatus = kcStatus,
    kcLastEvidenceDirection = kcLastEvidenceDirection,
    kcLastEvidenceAt = kcLastEvidenceAt,
    questionStabilityDays = questionStabilityDays,
    questionDifficulty = questionDifficulty,
    questionNextReviewAt = questionNextReviewAt,
    questionLapseCount = questionLapseCount,
    questionCrossDayAgain = questionCrossDayAgain,
)

internal fun ReviewLogEntry.toEntity() = ReviewLogEntity(
    learnerId = learnerId,
    cardId = practiceUnitId,
    rating = rating,
    deltaTDays = deltaTDays,
    durationMs = durationMs,
    reviewedAtUtc = reviewedAtEpochMillis,
    sourceKind = sourceKind,
    sourceId = sourceId,
    evidenceWeight = evidenceWeight,
    schedulingEligible = schedulingEligible,
    timeBucket = timeBucket,
    scrollUpCount = scrollUpCount,
    editCount = editCount,
    interruptionCount = interruptionCount,
    awayMillis = awayMillis,
    plannedReason = plannedReason,
    recordedAt = recordedAtEpochMillis,
)

internal fun ReviewLogSampleProjection.toRecord() = ReviewLogSampleRecord(
    practiceUnitId = practiceUnitId,
    reviewedAtEpochMillis = reviewedAtUtc,
    rating = rating,
    durationMs = durationMs,
    timeBucket = timeBucket,
    sourceKind = sourceKind,
    evidenceWeight = evidenceWeight,
    scrollUpCount = scrollUpCount,
    editCount = editCount,
    interruptionCount = interruptionCount,
    awayMillis = awayMillis,
    plannedReason = plannedReason,
    deltaTDays = deltaTDays,
)
