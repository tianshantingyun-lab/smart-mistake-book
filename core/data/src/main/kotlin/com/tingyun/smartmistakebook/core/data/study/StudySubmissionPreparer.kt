package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.AttemptWriteCommand
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.AttentionSignal
import com.tingyun.smartmistakebook.core.domain.FsrsEvidenceRatingMapper
import com.tingyun.smartmistakebook.core.domain.MasteryEvidencePolicy
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmission
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.AssessmentSubmissionContext
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome

/**
 * Builds the immutable write commands for every study submission path: curated
 * choices. Extracted from the study repository so the evidence-weighting rules
 * (attention and response-time discounts, hint/retry pricing) stay in one auditable place.
 */
internal class StudySubmissionPreparer(
    private val database: StudyDatabasePort,
    private val learnerId: String,
    private val fixtureSource: StudyFixtureSource,
    private val reviewLogSink: ReviewLogSink,
    private val writeContext: StudyWriteContext,
) {

    suspend fun prepareChoiceSubmission(
        submission: StudyChoiceSubmission,
    ): PreparedChoiceSubmission {
        val artifact = writeContext.requireTeachingArtifact(submission.practiceUnitId)
        val assessmentItem = artifact.assessmentItems.singleOrNull()
            ?: error("Curated practice unit ${submission.practiceUnitId} must have one assessment")
        val evidenceSnapshot = requireNotNull(
            fixtureSource.evidenceSnapshotForAssessment(assessmentItem.id),
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
        // Attention + response-time discount (spec 2.14): switches and
        // away-time fragment encoding (Craik et al. 1996) and a personally
        // abnormally fast answer is a suspected guess (Meyer 2010), so the
        // evidence weight shrinks and maps to a lower grade via the mapper.
        val attentionFactor = AttentionSignal.attentionFactor(
            submission.interruptionCount,
            submission.awayMillis,
        )
        val rtFactor = reviewLogSink.responseTimeDiscount(
            isCorrect = evaluation.isCorrect,
            durationMs = submission.durationSeconds * 1000L,
        )
        val discountedEvidence = decision.evidence.let { evidence ->
            val factor = (attentionFactor * rtFactor).coerceIn(0.0, 1.0)
            if (factor < 1.0 && evidence.direction != LearningEvidenceDirection.NONE) {
                evidence.copy(weight = (evidence.weight * factor).coerceIn(0.0, 1.0))
            } else {
                evidence
            }
        }
        return PreparedChoiceSubmission(
            evidenceSnapshot = evidenceSnapshot,
            command = AttemptWriteCommand(
                learnerId = learnerId,
                submissionId = writeContext.stableId("submission", submission.requestId),
                attemptId = writeContext.stableId("attempt", submission.requestId),
                presentationId = submission.presentationId,
                assessmentSnapshotId = evidenceSnapshot.snapshotId,
                submittedResponse = submittedResponse,
                evidence = discountedEvidence,
                problemMemoryOutcome = decision.problemMemoryOutcome,
                occurredAtEpochMillis = submission.occurredAtEpochMillis,
                durationSeconds = submission.durationSeconds,
                studyDay = writeContext.studyDayAt(submission.occurredAtEpochMillis),
                hintCount = submission.hintCount,
                revealedBeforeAnswer = false,
            ),
            isCorrect = evaluation.isCorrect,
        )
    }


    internal data class PreparedChoiceSubmission(
        val evidenceSnapshot: AssessmentEvidenceSnapshot,
        val command: AttemptWriteCommand,
        val isCorrect: Boolean,
    )


}
