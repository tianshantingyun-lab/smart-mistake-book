package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.KnowledgeGroundingSummaryRecord
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.ReviewPlanBundle
import com.tingyun.smartmistakebook.core.database.ReviewSessionRecord
import com.tingyun.smartmistakebook.core.database.ReviewedKnowledgeCoverageRecord
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.domain.ForgettingCurve
import com.tingyun.smartmistakebook.core.domain.ReviewCompletionStreak
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeCoverageGap
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeCoverageOverview
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSubjectCoverage
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyQuestionMemory
import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionProgress
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind

/**
 * Pure projections from database records onto the read models consumed by the
 * four feature tabs. Extracted from the repository so the composition root
 * keeps only orchestration; every dependency a mapper needs is an explicit
 * parameter, which is what makes these functions testable on their own.
 */
internal fun MistakeRecord.toCatalogEntry(
    learnerSnapshot: LearnerSnapshot,
    atEpochMillis: Long,
    resolvedKnowledgeNames: Map<String, String>,
    curatedProblemIds: Set<String>,
    forgettingCurve: ForgettingCurve,
    fixtureSource: StudyFixtureSource,
): StudyCatalogEntry {
    val memory = learnerSnapshot.problemMemoryStates[practiceUnitId]
    val artifact = fixtureSource.teachingArtifactForPracticeUnit(practiceUnitId)
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

internal fun List<KnowledgeMasteryState>.conservativeMasteryStatus(): MasteryStatus = when {
    isEmpty() -> MasteryStatus.UNKNOWN
    any { it.status == MasteryStatus.CONFLICTED } -> MasteryStatus.CONFLICTED
    any { it.status == MasteryStatus.STALE } -> MasteryStatus.STALE
    any { it.status == MasteryStatus.LEARNING } -> MasteryStatus.LEARNING
    all { it.status == MasteryStatus.MASTERED } -> MasteryStatus.MASTERED
    else -> MasteryStatus.UNKNOWN
}

internal fun LearnerSnapshot.toProfileOverview(
    resolvedKnowledgeContexts: Map<String, ResolvedKnowledgeContext>,
    fallbackKnowledgeNames: Map<String, String>,
): StudyProfileOverview {
    val weaknesses = knowledgeMasteryStates.values
        .filter { it.status != MasteryStatus.MASTERED }
        .sortedWith(
            compareBy<KnowledgeMasteryState> { it.conservativeMasteryScore }
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
                conservativeMasteryScore = state.conservativeMasteryScore,
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
            compareByDescending<KnowledgeMasteryState> { it.conservativeMasteryScore }
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
                conservativeMasteryScore = state.conservativeMasteryScore,
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

internal fun ReviewPlanBundle.toOverview(
    completedReviewDays: List<Long>,
    currentLocalDay: Long,
    intakeBacklogCount: Int,
    intakeMedianEstimateSeconds: Int,
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
        intakeBacklogCount = intakeBacklogCount,
        intakeMedianEstimateSeconds = intakeMedianEstimateSeconds,
    )
}

internal fun ReviewSessionRecord.toProgress(queueSize: Int) = StudyReviewSessionProgress(
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

internal fun List<ReviewedKnowledgeCoverageRecord>.toKnowledgeCoverageOverview(
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

/**
 * Median of the intake backlog's estimated seconds (spec batch-intake §6 P3):
 * the robust typical-item estimate for the coverage preview — a median is not
 * skewed by a few oversized outliers. Empty list → 0.
 */
internal fun List<MistakeRecord>.medianEstimateSeconds(): Int {
    if (isEmpty()) return 0
    val sorted = map(MistakeRecord::estimatedSeconds).sorted()
    return sorted[sorted.size / 2]
}

/** Resolved display context of one knowledge node, built from the knowledge table. */
internal data class ResolvedKnowledgeContext(
    val displayName: String,
    val subject: SubjectKind,
    val topicPath: List<String>,
)
