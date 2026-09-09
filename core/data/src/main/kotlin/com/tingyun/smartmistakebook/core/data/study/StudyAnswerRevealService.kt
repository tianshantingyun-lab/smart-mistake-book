package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.AnswerRevealWriteCommand
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealRequest
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealResult
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason

/**
 * Answer-reveal writes for a saved question: the reveal outcome row plus the
 * zero-weight review-log entry that keeps the reveal visible to the memory
 * model without pretending to be recall evidence. Extracted from the study
 * repository so the reveal contract stays readable next to its write.
 */
internal class StudyAnswerRevealService(
    private val database: StudyDatabasePort,
    private val learnerId: String,
    private val fixtureSource: StudyFixtureSource,
    private val reviewLogSink: ReviewLogSink,
    private val writeContext: StudyWriteContext,
    private val learnerSnapshot: suspend () -> LearnerSnapshot,
) {
    suspend fun reveal(request: StudyAnswerRevealRequest): StudyAnswerRevealResult {
        val artifact = writeContext.requireTeachingArtifact(request.practiceUnitId)
        val assessmentItem = artifact.assessmentItems.singleOrNull()
            ?: error("Curated practice unit ${request.practiceUnitId} must have one assessment")
        val evidenceSnapshot = requireNotNull(
            fixtureSource.evidenceSnapshotForAssessment(assessmentItem.id),
        ) { "No verified evidence snapshot for assessment ${assessmentItem.id}" }

        database.saveAssessmentEvidenceSnapshot(evidenceSnapshot)
        val priorMemory = learnerSnapshot().problemMemoryStates
            ?.get(request.practiceUnitId)
        val writeResult = database.recordAnswerReveal(
            AnswerRevealWriteCommand(
                learnerId = learnerId,
                assessmentEventId = writeContext.stableId("answer-reveal", request.requestId),
                presentationId = request.presentationId,
                assessmentSnapshotId = evidenceSnapshot.snapshotId,
                contentMarkdown = artifact.explanationMarkdown,
                occurredAtEpochMillis = request.occurredAtEpochMillis,
                studyDay = writeContext.studyDayAt(request.occurredAtEpochMillis),
            ),
        )
        if (writeResult.created) {
            reviewLogSink.record(
                practiceUnitId = request.practiceUnitId,
                evidence = LearningEvidence(
                    direction = LearningEvidenceDirection.NONE,
                    weight = 0.0,
                    reason = LearningEvidenceReason.ANSWER_REVEALED,
                ),
                occurredAtEpochMillis = request.occurredAtEpochMillis,
                durationSeconds = 0,
                studyDay = writeContext.studyDayAt(request.occurredAtEpochMillis),
                sourceKind = ReviewLogSink.SOURCE_KIND_ATTEMPT,
                sourceId = writeResult.outcome.outcomeId,
                priorMemory = priorMemory,
            )
        }
        return StudyAnswerRevealResult(
            outcomeId = writeResult.outcome.outcomeId,
            created = writeResult.created,
            explanationMarkdown = artifact.explanationMarkdown,
        )
    }
}
