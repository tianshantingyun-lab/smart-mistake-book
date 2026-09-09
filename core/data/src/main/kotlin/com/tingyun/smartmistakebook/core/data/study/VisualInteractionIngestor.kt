package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.AttemptWriteCommand
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.port.VisualInteractionAttemptRecord
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
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
 * Turns judged visual interactions into ledger evidence (audit §12): only
 * actions with a decisive local verdict, anchored to one active saved question
 * that already has accepted knowledge bindings, may enter the mastery ledger.
 * Extracted from the study repository so the conservative admission rules and
 * their cooldown stay readable on their own.
 */
internal class VisualInteractionIngestor(
    private val database: StudyDatabasePort,
    private val learnerId: String,
    private val reviewLogSink: ReviewLogSink,
    private val writeContext: StudyWriteContext,
) {

    suspend fun ingestPending(
        mistakes: List<MistakeRecord>,
    ): Int {
        var createdCount = 0
        mistakes
            .distinctBy(MistakeRecord::practiceUnitId)
            .forEach { mistake ->
                database.readVisualInteractionAttempts(mistake.problemRevisionId)
                    .forEach { attempt ->
                        if (ingestOne(attempt, mistake)) {
                            createdCount += 1
                        }
                    }
            }
        return createdCount
    }

    /**
     * Converts one judged visual interaction into ledger evidence. Stays
     * conservative on purpose (audit §12): only actions with a decisive
     * local verdict, anchored to one active saved question that already has
     * accepted knowledge bindings, may enter the mastery ledger.
     */
    private suspend fun ingestOne(
        attempt: VisualInteractionAttemptRecord,
        mistake: MistakeRecord,
    ): Boolean {
        // Exploratory selects and UNDECIDABLE tool actions (draw/measure/reset)
        // carry no answer semantics and stay audit-only.
        if (attempt.actionKind !in DECISIVE_VISUAL_ACTION_KINDS) return false
        if (attempt.problemRevisionId != mistake.problemRevisionId) return false
        // Spec 2.7: visual interactions cool down for one hour per unit.
        if (writeContext.isWithinCooldown(
                practiceUnitId = mistake.practiceUnitId,
                sourceKind = ReviewLogSink.SOURCE_KIND_VISUAL,
                cooldownMillis = VISUAL_COOLDOWN_MILLIS,
                atEpochMillis = attempt.attemptedAtEpochMillis,
            )
        ) {
            return false
        }
        val bindings = database.readPracticeUnitKnowledgeBindings(mistake.practiceUnitId)
            .filter { binding -> binding.basisRevisionId == mistake.problemRevisionId }
            .sortedWith(compareBy({ it.acceptedAtEpochMillis }, { it.bindingId }))
        if (bindings.isEmpty()) return false
        val taxonomyVersion = bindings.first().taxonomyVersion
        val attributed = bindings.filter { it.taxonomyVersion == taxonomyVersion }
        val secondaryWeight = SECONDARY_VISUAL_ATTRIBUTION_WEIGHT_POOL /
            (attributed.size - 1).coerceAtLeast(1)
        val attributions = attributed.mapIndexed { index, binding ->
            KnowledgeEvidenceAttribution(
                bindingId = binding.bindingId,
                knowledgeNodeId = binding.knowledgeNodeId,
                weight = if (index == 0) {
                    PRIMARY_VISUAL_ATTRIBUTION_WEIGHT
                } else {
                    secondaryWeight
                },
                basisRevisionId = mistake.problemRevisionId,
                taxonomyVersion = taxonomyVersion,
                role = if (index == 0) {
                    EvidenceAttributionRole.PRIMARY
                } else {
                    EvidenceAttributionRole.SECONDARY
                },
                certainty = EvidenceAttributionCertainty.DIRECT,
            )
        }
        val evidence = if (attempt.feasible) {
            LearningEvidence(
                direction = LearningEvidenceDirection.POSITIVE,
                weight = VISUAL_SATISFIED_WEIGHT,
                reason = LearningEvidenceReason.VISUAL_INTERACTION_SATISFIED,
            )
        } else {
            LearningEvidence(
                direction = LearningEvidenceDirection.NEGATIVE,
                weight = VISUAL_VIOLATED_WEIGHT,
                reason = LearningEvidenceReason.VISUAL_INTERACTION_VIOLATED,
            )
        }
        val snapshot = AssessmentEvidenceSnapshot(
            snapshotId = writeContext.stableId("visual-snapshot", attempt.attemptId),
            assessmentItemId = VISUAL_ASSESSMENT_ITEM_ID_PREFIX + writeContext.stableId(
                namespace = "item",
                requestId = "${mistake.practiceUnitId}\n${attempt.attemptId}",
            ),
            practiceUnitId = mistake.practiceUnitId,
            problemRevisionId = mistake.problemRevisionId,
            answerSpecId = VISUAL_ANSWER_SPEC_ID,
            itemFamilyId = VISUAL_ITEM_FAMILY_ID,
            sourceBundleId = null,
            taxonomyVersion = taxonomyVersion,
            verification = AssessmentSnapshotVerification.VERIFIED,
            calibration = CalibrationSnapshot.unknown(),
            attributions = attributions,
            capturedAtEpochMillis = attempt.attemptedAtEpochMillis,
        )
        database.saveAssessmentEvidenceSnapshot(snapshot)
        val writeResult = database.recordAttempt(
            AttemptWriteCommand(
                learnerId = learnerId,
                submissionId = writeContext.stableId("submission", "visual-attempt:${attempt.attemptId}"),
                attemptId = writeContext.stableId("attempt", "visual-attempt:${attempt.attemptId}"),
                presentationId = writeContext.stableId("visual-presentation", attempt.attemptId),
                assessmentSnapshotId = snapshot.snapshotId,
                submittedResponse = AttemptSubmittedResponse.Choice(
                    choiceId = if (attempt.feasible) "visual:SATISFIED" else "visual:VIOLATED",
                    choiceMarkdown = attempt.feedback.ifBlank {
                        if (attempt.feasible) {
                            "操作满足题目条件"
                        } else {
                            "操作不满足题目条件"
                        }
                    },
                    submittedAtEpochMillis = attempt.attemptedAtEpochMillis,
                ),
                evidence = evidence,
                problemMemoryOutcome = if (attempt.feasible) {
                    ProblemMemoryOutcome.ASSISTED_RECALL
                } else {
                    ProblemMemoryOutcome.RETRIEVAL_FAILURE
                },
                occurredAtEpochMillis = attempt.attemptedAtEpochMillis,
                durationSeconds = 0,
                studyDay = writeContext.studyDayAt(attempt.attemptedAtEpochMillis),
            ),
        )
        if (writeResult.created) {
            reviewLogSink.record(
                practiceUnitId = mistake.practiceUnitId,
                evidence = evidence,
                occurredAtEpochMillis = attempt.attemptedAtEpochMillis,
                durationSeconds = 0,
                studyDay = writeContext.studyDayAt(attempt.attemptedAtEpochMillis),
                sourceKind = ReviewLogSink.SOURCE_KIND_VISUAL,
                sourceId = writeResult.attempt.attemptId,
                priorMemory = null,
            )
            return true
        }
        // A replay after the first successful sweep must not claim a new
        // creation; the ledger already has this attempt exactly once.
        return false
    }

    private companion object {
        const val VISUAL_SATISFIED_WEIGHT = 0.25
        const val VISUAL_VIOLATED_WEIGHT = 0.5
        const val PRIMARY_VISUAL_ATTRIBUTION_WEIGHT = 0.6
        const val SECONDARY_VISUAL_ATTRIBUTION_WEIGHT_POOL = 0.4
        const val VISUAL_ASSESSMENT_ITEM_ID_PREFIX = "local-visual-interaction:"
        const val VISUAL_ANSWER_SPEC_ID = "local-visual-interaction-v1"
        const val VISUAL_ITEM_FAMILY_ID = "local-visual-interaction"
        val DECISIVE_VISUAL_ACTION_KINDS = setOf(
            "DragPoint",
            "AdjustParameter",
            "Connect",
            "OrderItems",
            "SubmitHypothesis",
        )
        /** Spec 2.7: visual interactions cool down for one hour per unit. */
        const val VISUAL_COOLDOWN_MILLIS = 1L * 60 * 60 * 1000
    }
}
