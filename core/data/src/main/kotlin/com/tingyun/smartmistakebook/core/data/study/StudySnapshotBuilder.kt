package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.MAX_REVIEW_COMPLETION_HISTORY_DAYS
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.domain.ForgettingCurve
import com.tingyun.smartmistakebook.core.domain.StudyDataStatus
import com.tingyun.smartmistakebook.core.domain.StudyExperienceSnapshot
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeCoverageOverview
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/**
 * Composes the one read model every feature tab consumes from the current
 * mistakes, the learner projection and the retained-or-replanned review plan.
 * Extracted from the study repository so the snapshot contract (catalog,
 * review overview, profile, knowledge coverage, tutor slots) is stated in one
 * place instead of inside a write-path class.
 */
internal class StudySnapshotBuilder(
    private val database: StudyDatabasePort,
    private val learnerId: String,
    private val studyZoneId: ZoneId,
    private val fixtureSource: StudyFixtureSource,
    private val knowledgeNames: Map<String, String>,
    private val curatedProblemIds: Set<String>,
    private val forgettingCurve: ForgettingCurve,
    private val plannerService: StudyReviewPlannerService,
    private val learnerSnapshot: suspend () -> LearnerSnapshot,
) {
    suspend fun build(
        mistakes: List<MistakeRecord>,
        pendingCorrectionCount: Int,
        knowledgeCoverage: StudyKnowledgeCoverageOverview,
    ): StudyExperienceSnapshot {
        val projection = learnerSnapshot()
        check(
            projection.freshness == LearnerSnapshotFreshness.CURRENT &&
                projection.projectionStatus == ProjectionStatus.CURRENT,
        ) { "Cannot publish a study snapshot from a stale or incomplete learning projection" }

        val planningContext = plannerService.planningContext(projection)
        val activePlan = database.observeActiveReviewPlan(learnerId).first()
        val retainedPlan = activePlan ?: database.observeCurrentReviewPlan(
            learnerId = learnerId,
            localDayEpochDay = planningContext.localDate.toEpochDay(),
            timeZoneId = studyZoneId.id,
        ).first()?.takeIf { current ->
            current.activeSession != null ||
                current.latestSession?.status == StudyDbValue.ReviewStatus.COMPLETED
        }
        val reviewBundle = retainedPlan ?: plannerService.createReviewPlan(
            mistakes = mistakes,
            learnerSnapshot = projection,
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
        // Intake backlog (spec batch-intake §1): never-attempted questions
        // (no memory state) that are NOT in today's plan queue — they stay in
        // the backlog with no learning pressure until introduced.
        val plannedUnitIds = reviewBundle.queue.mapTo(hashSetOf()) { it.practiceUnitId }
        val intakeBacklog = orderedMistakes.filter { mistake ->
            projection.problemMemoryStates[mistake.practiceUnitId] == null &&
                mistake.practiceUnitId !in plannedUnitIds
        }
        val referencedKnowledgeNodeIds = buildSet {
            addAll(projection.knowledgeMasteryStates.keys)
            orderedMistakes.forEach { mistake -> addAll(mistake.knowledgeNodeIds) }
        }
        val resolvedKnowledgeContexts = plannerService.resolveKnowledgeContexts(referencedKnowledgeNodeIds)
        val resolvedKnowledgeNames = knowledgeNames + resolvedKnowledgeContexts.mapValues {
            it.value.displayName
        }
        return StudyExperienceSnapshot(
            status = StudyDataStatus.READY,
            catalog = orderedMistakes.map { mistake ->
                mistake.toCatalogEntry(
                    learnerSnapshot = projection,
                    atEpochMillis = planningContext.planningAtEpochMillis,
                    resolvedKnowledgeNames = resolvedKnowledgeNames,
                    curatedProblemIds = curatedProblemIds,
                    forgettingCurve = forgettingCurve,
                    zoneId = studyZoneId,
                    fixtureSource = fixtureSource,
                )
            },
            pendingCorrectionCount = pendingCorrectionCount,
            review = reviewBundle.toOverview(
                completedReviewDays = completedReviewDays,
                currentLocalDay = planningContext.localDate.toEpochDay(),
                intakeBacklogCount = intakeBacklog.size,
                intakeMedianEstimateSeconds = intakeBacklog.medianEstimateSeconds(),
            ),
            profile = projection.toProfileOverview(
                resolvedKnowledgeContexts = resolvedKnowledgeContexts,
                fallbackKnowledgeNames = resolvedKnowledgeNames,
            ),
            knowledgeCoverage = knowledgeCoverage,
            tutorExampleSaved = orderedMistakes.any {
                it.practiceUnitId == fixtureSource.tutorPracticeUnitId
            },
            // The tutor root has no current question until the student captures or selects one.
            tutorPracticeUnitId = null,
            tutorDecision = null,
        )
    }
}
