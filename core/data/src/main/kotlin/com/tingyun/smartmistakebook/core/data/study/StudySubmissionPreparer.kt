package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.AttemptWriteCommand
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.AttentionSignal
import com.tingyun.smartmistakebook.core.domain.FsrsEvidenceRatingMapper
import com.tingyun.smartmistakebook.core.domain.MasteryEvidencePolicy
import com.tingyun.smartmistakebook.core.domain.StudyReviewRating
import com.tingyun.smartmistakebook.core.domain.StudyReviewRatingSubmission
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmission
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReport
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReportSubmission
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
import com.tingyun.smartmistakebook.core.model.LocalReviewSelfReportContract
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome

/**
 * Builds the immutable write commands for every study submission path: curated
 * choices, self reports, subjective ratings. Extracted from the study
 * repository so the evidence-weighting rules (attention and response-time
 * discounts, pseudo-KC fallback, self-report tiers) stay in one auditable place.
 */
internal class StudySubmissionPreparer(
    private val database: StudyDatabasePort,
    private val learnerId: String,
    private val fixtureSource: StudyFixtureSource,
    private val reviewLogSink: ReviewLogSink,
    private val writeContext: StudyWriteContext,
) {

    fun ratingEvidenceFor(rating: StudyReviewRating): LearningEvidence = when (rating) {
        StudyReviewRating.AGAIN -> LearningEvidence(
            direction = LearningEvidenceDirection.NEGATIVE,
            weight = 1.0,
            reason = LearningEvidenceReason.SELF_REPORTED_STUCK,
        )
        StudyReviewRating.HARD -> LearningEvidence(
            direction = LearningEvidenceDirection.POSITIVE,
            weight = RATING_HARD_WEIGHT,
            reason = LearningEvidenceReason.SELF_REPORTED_RECALL,
        )
        StudyReviewRating.GOOD -> LearningEvidence(
            direction = LearningEvidenceDirection.POSITIVE,
            weight = RATING_GOOD_WEIGHT,
            reason = LearningEvidenceReason.SELF_REPORTED_RECALL,
        )
        StudyReviewRating.EASY -> LearningEvidence(
            direction = LearningEvidenceDirection.POSITIVE,
            weight = RATING_EASY_WEIGHT,
            reason = LearningEvidenceReason.SELF_REPORTED_RECALL,
        )
    }


    suspend fun prepareRatingSubmission(
        submission: StudyReviewRatingSubmission,
        mistake: MistakeRecord,
    ): PreparedSelfReportSubmission {
        val pseudoAttributions = buildPseudoAttribution(
            practiceUnitId = mistake.practiceUnitId,
            problemRevisionId = mistake.problemRevisionId,
            taxonomyVersion = LocalReviewSelfReportContract.TAXONOMY_VERSION,
            subject = mistake.subject,
            acceptedAtEpochMillis = submission.occurredAtEpochMillis,
        )
        val evidenceSnapshot = AssessmentEvidenceSnapshot(
            snapshotId = writeContext.stableId("rating-snapshot", submission.requestId),
            assessmentItemId = LocalReviewSelfReportContract.ASSESSMENT_ITEM_ID_PREFIX +
                writeContext.stableId(
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
            attributions = pseudoAttributions,
            capturedAtEpochMillis = submission.occurredAtEpochMillis,
        )
        val ratingBase = ratingEvidenceFor(submission.rating)
        val evidence = if (ratingBase.direction == LearningEvidenceDirection.NONE) {
            ratingBase
        } else {
            val factor = reviewLogSink.subjectiveSignalFactor(
                occurredAtEpochMillis = submission.occurredAtEpochMillis,
                durationSeconds = submission.durationSeconds,
                interruptionCount = submission.interruptionCount,
                awayMillis = submission.awayMillis,
                isCorrect = submission.rating != StudyReviewRating.AGAIN,
            )
            ratingBase.copy(weight = (ratingBase.weight * factor).coerceIn(0.0, 1.0))
        }
        val memoryOutcome = if (submission.rating == StudyReviewRating.AGAIN) {
            ProblemMemoryOutcome.RETRIEVAL_FAILURE
        } else {
            ProblemMemoryOutcome.ASSISTED_RECALL
        }
        return PreparedSelfReportSubmission(
            evidenceSnapshot = evidenceSnapshot,
            command = AttemptWriteCommand(
                learnerId = learnerId,
                submissionId = writeContext.stableId("submission", submission.requestId),
                attemptId = writeContext.stableId("attempt", submission.requestId),
                presentationId = submission.presentationId,
                assessmentSnapshotId = evidenceSnapshot.snapshotId,
                submittedResponse = AttemptSubmittedResponse.Choice(
                    choiceId = "rating:${submission.rating.name}",
                    choiceMarkdown = when (submission.rating) {
                        StudyReviewRating.AGAIN -> "没想起来"
                        StudyReviewRating.HARD -> "很费劲"
                        StudyReviewRating.GOOD -> "正常"
                        StudyReviewRating.EASY -> "很轻松"
                    },
                    submittedAtEpochMillis = submission.occurredAtEpochMillis,
                ),
                evidence = evidence,
                problemMemoryOutcome = memoryOutcome,
                occurredAtEpochMillis = submission.occurredAtEpochMillis,
                durationSeconds = submission.durationSeconds,
                studyDay = writeContext.studyDayAt(submission.occurredAtEpochMillis),
            ),
        )
    }


    /**
     * Spec 3.4 pseudo-KC fallback: when a saved question carries no accepted
     * knowledge bindings, its subjective evidence still lands on the
     * subject-scoped pseudo knowledge node through a deterministic pseudo
     * binding, so mastery state is never lost for unbound questions.
     */
    /**
     * Subjective evidence factor (spec 2.14 + 2.12): attention switches and
     * away-time (Craik 1996), the personal time-of-day multiplier (May &
     * Hasher 1998; >=30 samples per bucket, cold start neutral) and the
     * response-time guess discount (Meyer 2010 via the RT baseline) all only
     * ever shrink the weight of a subjective report.
     */
    private suspend fun buildPseudoAttribution(
        practiceUnitId: String,
        problemRevisionId: String,
        taxonomyVersion: String,
        subject: String,
        acceptedAtEpochMillis: Long,
    ): List<KnowledgeEvidenceAttribution> {
        val binding = database.ensurePseudoKnowledgeBinding(
            practiceUnitId = practiceUnitId,
            problemRevisionId = problemRevisionId,
            taxonomyVersion = taxonomyVersion,
            subject = subject,
            acceptedAtEpochMillis = acceptedAtEpochMillis,
        ) ?: return emptyList()
        return listOf(
            KnowledgeEvidenceAttribution(
                bindingId = binding.bindingId,
                knowledgeNodeId = binding.knowledgeNodeId,
                weight = 1.0,
                basisRevisionId = binding.basisRevisionId,
                taxonomyVersion = binding.taxonomyVersion,
                role = EvidenceAttributionRole.PRIMARY,
                certainty = EvidenceAttributionCertainty.DIRECT,
            ),
        )
    }


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


    suspend fun prepareSelfReportSubmission(
        submission: StudyReviewSelfReportSubmission,
        mistake: MistakeRecord,
    ): PreparedSelfReportSubmission {
        val pseudoAttributions = buildPseudoAttribution(
            practiceUnitId = mistake.practiceUnitId,
            problemRevisionId = mistake.problemRevisionId,
            taxonomyVersion = LocalReviewSelfReportContract.TAXONOMY_VERSION,
            subject = mistake.subject,
            acceptedAtEpochMillis = submission.occurredAtEpochMillis,
        )
        val evidenceSnapshot = AssessmentEvidenceSnapshot(
            snapshotId = writeContext.stableId("self-report-snapshot", submission.requestId),
            assessmentItemId = LocalReviewSelfReportContract.ASSESSMENT_ITEM_ID_PREFIX +
                writeContext.stableId(
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
            attributions = pseudoAttributions,
            capturedAtEpochMillis = submission.occurredAtEpochMillis,
        )
        val signalFactor = reviewLogSink.subjectiveSignalFactor(
            occurredAtEpochMillis = submission.occurredAtEpochMillis,
            durationSeconds = submission.durationSeconds,
            interruptionCount = submission.interruptionCount,
            awayMillis = submission.awayMillis,
            isCorrect = true,
        )
        val reportDecision = when (submission.report) {
            StudyReviewSelfReport.RECALL_COMPLETED -> SelfReportDecision(
                choiceMarkdown = "我已独立完成",
                evidence = LearningEvidence(
                    direction = LearningEvidenceDirection.POSITIVE,
                    weight = (SELF_REPORTED_RECALL_WEIGHT * signalFactor).coerceIn(0.0, 1.0),
                    reason = LearningEvidenceReason.SELF_REPORTED_RECALL,
                ),
                memoryOutcome = ProblemMemoryOutcome.ASSISTED_RECALL,
            )

            // Middle tier (QA item B2): struggled through unaided. Positive
            // but weaker than clean recall; feeds the HLR assistedCorrect
            // feature so three-tier self-reports can calibrate the
            // independent/assisted/lapse split.
            StudyReviewSelfReport.RECALLED_WITH_EFFORT -> SelfReportDecision(
                choiceMarkdown = "勉强做对",
                evidence = LearningEvidence(
                    direction = LearningEvidenceDirection.POSITIVE,
                    weight = (SELF_REPORTED_EFFORT_RECALL_WEIGHT * signalFactor).coerceIn(0.0, 1.0),
                    reason = LearningEvidenceReason.CORRECT_ON_RETRY,
                ),
                memoryOutcome = ProblemMemoryOutcome.ASSISTED_RECALL,
            )

            StudyReviewSelfReport.NEEDS_HELP -> SelfReportDecision(
                choiceMarkdown = "这里还卡住",
                evidence = LearningEvidence(
                    direction = LearningEvidenceDirection.NEGATIVE,
                    weight = (SELF_REPORTED_STUCK_WEIGHT * signalFactor).coerceIn(0.0, 1.0),
                    reason = LearningEvidenceReason.SELF_REPORTED_STUCK,
                ),
                memoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
            )
        }
        return PreparedSelfReportSubmission(
            evidenceSnapshot = evidenceSnapshot,
            command = AttemptWriteCommand(
                learnerId = learnerId,
                submissionId = writeContext.stableId("submission", submission.requestId),
                attemptId = writeContext.stableId("attempt", submission.requestId),
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
                studyDay = writeContext.studyDayAt(submission.occurredAtEpochMillis),
            ),
        )
    }


    internal data class PreparedChoiceSubmission(
        val evidenceSnapshot: AssessmentEvidenceSnapshot,
        val command: AttemptWriteCommand,
        val isCorrect: Boolean,
    )


    internal data class PreparedSelfReportSubmission(
        val evidenceSnapshot: AssessmentEvidenceSnapshot,
        val command: AttemptWriteCommand,
    )


    internal data class SelfReportDecision(
        val choiceMarkdown: String,
        val evidence: LearningEvidence,
        val memoryOutcome: ProblemMemoryOutcome,
    )


    private companion object {
        const val SELF_REPORTED_RECALL_WEIGHT = 0.35
        const val SELF_REPORTED_EFFORT_RECALL_WEIGHT = 0.25
        const val SELF_REPORTED_STUCK_WEIGHT = 0.5
        const val RATING_HARD_WEIGHT = FsrsEvidenceRatingMapper.RATING_HARD_WEIGHT
        const val RATING_GOOD_WEIGHT = FsrsEvidenceRatingMapper.RATING_GOOD_WEIGHT
        const val RATING_EASY_WEIGHT = FsrsEvidenceRatingMapper.RATING_EASY_WEIGHT
    }
}
