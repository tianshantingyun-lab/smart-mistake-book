package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.AttemptAdvanceProofRecord
import com.tingyun.smartmistakebook.core.database.AttemptCorrectionRecord
import com.tingyun.smartmistakebook.core.database.AnswerRevealWriteCommand
import com.tingyun.smartmistakebook.core.database.AttemptIdempotencyConflictException
import com.tingyun.smartmistakebook.core.database.AttemptPersistenceRecord
import com.tingyun.smartmistakebook.core.database.AttemptWriteCommand
import com.tingyun.smartmistakebook.core.database.ConsumedLedgerEventReceipt
import com.tingyun.smartmistakebook.core.database.DatabaseContractValidator
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.LearningLedgerRead
import com.tingyun.smartmistakebook.core.database.LearningLedgerReadStatus
import com.tingyun.smartmistakebook.core.database.LearningLedgerIntegrityException
import com.tingyun.smartmistakebook.core.database.PersistedAttemptP0
import com.tingyun.smartmistakebook.core.database.PersistedAnswerRevealP0
import com.tingyun.smartmistakebook.core.database.PersistedCorrectionP0
import com.tingyun.smartmistakebook.core.database.PersistedLearnerSnapshot
import com.tingyun.smartmistakebook.core.database.PersistedLearningLedgerEvent
import com.tingyun.smartmistakebook.core.database.PersistedIncrementalLearningEvent
import com.tingyun.smartmistakebook.core.database.ProjectionBatch
import com.tingyun.smartmistakebook.core.database.ProjectionBatchStopReason
import com.tingyun.smartmistakebook.core.database.ProjectionCasConflictException
import com.tingyun.smartmistakebook.core.database.ProjectionCommit
import com.tingyun.smartmistakebook.core.database.ProjectionCommitMode
import com.tingyun.smartmistakebook.core.database.ProjectionOutboxRecord
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.model.AppliedAttemptRecord
import com.tingyun.smartmistakebook.core.model.AppliedAnswerRevealRecord
import com.tingyun.smartmistakebook.core.model.AppliedCorrectionRecord
import com.tingyun.smartmistakebook.core.model.AppliedTutorAnswerExposureRecord
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.IndependentCorrectObservation
import com.tingyun.smartmistakebook.core.model.EventTimeTrust
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.LearningLedgerFingerprint
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.PresentationProjectionState
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome
import com.tingyun.smartmistakebook.core.database.entity.AppliedAttemptRecordEntity
import com.tingyun.smartmistakebook.core.database.entity.AppliedAnswerRevealRecordEntity
import com.tingyun.smartmistakebook.core.database.entity.AppliedCorrectionRecordEntity
import com.tingyun.smartmistakebook.core.database.entity.AppliedTutorAnswerExposureRecordEntity
import com.tingyun.smartmistakebook.core.database.entity.AnswerRevealOutcomeEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentAnswerRevealEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEvidenceAttributionEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEvidenceSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentItemSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentPresentationEntity
import com.tingyun.smartmistakebook.core.database.entity.AttemptCorrectionEntity
import com.tingyun.smartmistakebook.core.database.entity.AttemptEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AttemptSubmissionEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorAnswerExposureOutcomeEntity
import com.tingyun.smartmistakebook.core.database.entity.IndependentCorrectObservationEntity
import com.tingyun.smartmistakebook.core.database.entity.LearnerKnowledgeMasteryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.LearnerProblemMemoryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.LearnerProjectionSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningSequenceEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProjectionConsumptionEntity
import com.tingyun.smartmistakebook.core.database.entity.ProjectionOutboxEntity
import com.tingyun.smartmistakebook.core.database.entity.PresentationProjectionStateEntity
import kotlinx.coroutines.flow.Flow


internal fun AssessmentEvidenceSnapshot.toEntity() = AssessmentEvidenceSnapshotEntity(
    snapshotId = snapshotId,
    assessmentItemId = assessmentItemId,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    answerSpecId = answerSpecId,
    itemFamilyId = itemFamilyId,
    sourceBundleId = sourceBundleId,
    taxonomyVersion = taxonomyVersion,
    verification = verification.name,
    calibrationSupport = calibration.support.name,
    calibrationSourceId = calibration.sourceId,
    calibrationVersion = calibration.version,
    calibrationValidFromEpochMillis = calibration.validFromEpochMillis,
    calibrationValidUntilEpochMillis = calibration.validUntilEpochMillis,
    capturedAtEpochMillis = capturedAtEpochMillis,
)

internal fun AssessmentEvidenceSnapshot.toAttributionEntities() = attributions
    .sortedBy(KnowledgeEvidenceAttribution::bindingId)
    .map { attribution ->
        AssessmentEvidenceAttributionEntity(
            snapshotId = snapshotId,
            bindingId = attribution.bindingId,
            practiceUnitId = practiceUnitId,
            knowledgeNodeId = attribution.knowledgeNodeId,
            weight = attribution.weight,
            basisRevisionId = attribution.basisRevisionId,
            taxonomyVersion = attribution.taxonomyVersion,
            role = attribution.role.name,
            certainty = attribution.certainty.name,
        )
    }

internal fun AssessmentEvidenceSnapshotEntity.toModel(
    attributions: List<AssessmentEvidenceAttributionEntity>,
) = AssessmentEvidenceSnapshot(
    snapshotId = snapshotId,
    assessmentItemId = assessmentItemId,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    answerSpecId = answerSpecId,
    itemFamilyId = itemFamilyId,
    sourceBundleId = sourceBundleId,
    taxonomyVersion = taxonomyVersion,
    verification = AssessmentSnapshotVerification.valueOf(verification),
    calibration = CalibrationSnapshot(
        support = CalibrationSupport.valueOf(calibrationSupport),
        sourceId = calibrationSourceId,
        version = calibrationVersion,
        validFromEpochMillis = calibrationValidFromEpochMillis,
        validUntilEpochMillis = calibrationValidUntilEpochMillis,
    ),
    attributions = attributions.map { attribution ->
        KnowledgeEvidenceAttribution(
            bindingId = attribution.bindingId,
            knowledgeNodeId = attribution.knowledgeNodeId,
            weight = attribution.weight,
            basisRevisionId = attribution.basisRevisionId,
            taxonomyVersion = attribution.taxonomyVersion,
            role = EvidenceAttributionRole.valueOf(attribution.role),
            certainty = EvidenceAttributionCertainty.valueOf(attribution.certainty),
        )
    },
    capturedAtEpochMillis = capturedAtEpochMillis,
)

internal fun Attempt.toEntity(
    learnerId: String,
    submissionId: String,
    canonicalFingerprint: String,
): AttemptEventEntity {
    val response = submittedResponse as? AttemptSubmittedResponse.Choice
    return AttemptEventEntity(
        attemptId = attemptId,
        learnerId = learnerId,
        submissionId = submissionId,
        eventSequence = eventSequence,
        canonicalFingerprint = canonicalFingerprint,
        presentationId = presentationId,
        responseOrdinal = responseOrdinal,
        assessmentSnapshotId = assessmentSnapshot.snapshotId,
        submittedChoiceId = response?.choiceId,
        submittedChoiceMarkdown = response?.choiceMarkdown,
        responseSubmittedAtEpochMillis = response?.submittedAtEpochMillis,
        evidenceDirection = evidence.direction.name,
        evidenceWeight = evidence.weight,
        evidenceReason = evidence.reason.name,
        problemMemoryOutcome = problemMemoryOutcome.name,
        occurredAtEpochMillis = occurredAtEpochMillis,
        durationSeconds = durationSeconds,
        studyDayEpochDay = studyDay.epochDay,
        studyDayTimeZoneId = studyDay.timeZoneId,
        studyDayUtcOffsetMinutes = studyDay.utcOffsetMinutes,
    )
}

internal fun AttemptEventEntity.toModel(snapshot: AssessmentEvidenceSnapshot): Attempt {
    val choiceId = submittedChoiceId
    val choiceMarkdown = submittedChoiceMarkdown
    val submittedAtEpochMillis = responseSubmittedAtEpochMillis
    val submittedResponse = when {
        choiceId == null &&
            choiceMarkdown == null &&
            submittedAtEpochMillis == null -> AttemptSubmittedResponse.LegacyUnavailable

        choiceId != null &&
            choiceMarkdown != null &&
            submittedAtEpochMillis != null -> AttemptSubmittedResponse.Choice(
            choiceId = choiceId,
            choiceMarkdown = choiceMarkdown,
            submittedAtEpochMillis = submittedAtEpochMillis,
        )

        else -> throw LearningLedgerIntegrityException(
            "Attempt $attemptId has a partially persisted submitted response",
        )
    }
    return Attempt(
        attemptId = attemptId,
        presentationId = presentationId,
        responseOrdinal = responseOrdinal,
        assessmentSnapshot = snapshot,
        evidence = LearningEvidence(
            direction = LearningEvidenceDirection.valueOf(evidenceDirection),
            weight = evidenceWeight,
            reason = LearningEvidenceReason.valueOf(evidenceReason),
        ),
        problemMemoryOutcome = ProblemMemoryOutcome.valueOf(problemMemoryOutcome),
        occurredAtEpochMillis = occurredAtEpochMillis,
        durationSeconds = durationSeconds,
        studyDay = StudyDayContext(
            epochDay = studyDayEpochDay,
            timeZoneId = studyDayTimeZoneId,
            utcOffsetMinutes = studyDayUtcOffsetMinutes,
        ),
        eventSequence = eventSequence,
        submittedResponse = submittedResponse,
    )
}

internal fun AttemptCorrection.toEntity(
    learnerId: String,
    submissionId: String,
    canonicalFingerprint: String,
) = AttemptCorrectionEntity(
    correctionId = correctionId,
    learnerId = learnerId,
    submissionId = submissionId,
    attemptId = attemptId,
    eventSequence = eventSequence,
    canonicalFingerprint = canonicalFingerprint,
    replacementEvidenceDirection = replacementEvidence.direction.name,
    replacementEvidenceWeight = replacementEvidence.weight,
    replacementEvidenceReason = replacementEvidence.reason.name,
    replacementMemoryOutcome = replacementMemoryOutcome.name,
    reasonMarkdown = reasonMarkdown,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

internal fun AttemptCorrectionEntity.toModel() = AttemptCorrection(
    correctionId = correctionId,
    attemptId = attemptId,
    replacementEvidence = LearningEvidence(
        direction = LearningEvidenceDirection.valueOf(replacementEvidenceDirection),
        weight = replacementEvidenceWeight,
        reason = LearningEvidenceReason.valueOf(replacementEvidenceReason),
    ),
    replacementMemoryOutcome = ProblemMemoryOutcome.valueOf(replacementMemoryOutcome),
    reasonMarkdown = reasonMarkdown,
    occurredAtEpochMillis = occurredAtEpochMillis,
    eventSequence = eventSequence,
)

internal fun AnswerRevealWriteCommand.toAnswerRevealEventEntity() =
    AssessmentAnswerRevealEventEntity(
        assessmentEventId = assessmentEventId,
        learnerId = learnerId,
        outcomeId = DatabaseContractValidator.answerRevealOutcomeId(assessmentEventId),
        presentationId = presentationId,
        assessmentSnapshotId = assessmentSnapshotId,
        contentMarkdown = contentMarkdown,
        occurredAtEpochMillis = occurredAtEpochMillis,
        studyDayEpochDay = studyDay.epochDay,
        studyDayTimeZoneId = studyDay.timeZoneId,
        studyDayUtcOffsetMinutes = studyDay.utcOffsetMinutes,
    )

internal fun AssessmentAnswerRevealEventEntity.toModel(
    snapshot: AssessmentEvidenceSnapshot,
    eventSequence: Long,
) = AnswerRevealOutcome(
    outcomeId = outcomeId,
    presentationId = presentationId,
    assessmentSnapshot = snapshot,
    occurredAtEpochMillis = occurredAtEpochMillis,
    studyDay = StudyDayContext(
        epochDay = studyDayEpochDay,
        timeZoneId = studyDayTimeZoneId,
        utcOffsetMinutes = studyDayUtcOffsetMinutes,
    ),
    eventSequence = eventSequence,
)

internal fun AnswerRevealOutcome.toEntity(
    source: AssessmentAnswerRevealEventEntity,
    canonicalFingerprint: String,
) = AnswerRevealOutcomeEntity(
    outcomeId = outcomeId,
    learnerId = source.learnerId,
    assessmentEventId = source.assessmentEventId,
    presentationId = presentationId,
    assessmentSnapshotId = assessmentSnapshot.snapshotId,
    eventSequence = eventSequence,
    canonicalFingerprint = canonicalFingerprint,
    occurredAtEpochMillis = occurredAtEpochMillis,
    studyDayEpochDay = studyDay.epochDay,
    studyDayTimeZoneId = studyDay.timeZoneId,
    studyDayUtcOffsetMinutes = studyDay.utcOffsetMinutes,
)

internal fun AnswerRevealOutcomeEntity.toModel(snapshot: AssessmentEvidenceSnapshot) =
    AnswerRevealOutcome(
        outcomeId = outcomeId,
        presentationId = presentationId,
        assessmentSnapshot = snapshot,
        occurredAtEpochMillis = occurredAtEpochMillis,
        studyDay = StudyDayContext(
            epochDay = studyDayEpochDay,
            timeZoneId = studyDayTimeZoneId,
            utcOffsetMinutes = studyDayUtcOffsetMinutes,
        ),
        eventSequence = eventSequence,
    )

internal fun AttemptEventEntity.toOutbox() = ProjectionOutboxEntity(
    outboxId = "learning-outbox:$learnerId:$EVENT_KIND_ATTEMPT:$attemptId",
    learnerId = learnerId,
    outboxSequence = eventSequence,
    eventKind = EVENT_KIND_ATTEMPT,
    eventId = attemptId,
    canonicalFingerprint = canonicalFingerprint,
    status = StudyDbValue.OutboxStatus.PENDING,
    createdAtEpochMillis = occurredAtEpochMillis,
)

internal fun AttemptCorrectionEntity.toOutbox() = ProjectionOutboxEntity(
    outboxId = "learning-outbox:$learnerId:$EVENT_KIND_CORRECTION:$correctionId",
    learnerId = learnerId,
    outboxSequence = eventSequence,
    eventKind = EVENT_KIND_CORRECTION,
    eventId = correctionId,
    canonicalFingerprint = canonicalFingerprint,
    status = StudyDbValue.OutboxStatus.PENDING,
    createdAtEpochMillis = occurredAtEpochMillis,
)

internal fun AnswerRevealOutcomeEntity.toOutbox() = ProjectionOutboxEntity(
    outboxId = "learning-outbox:$learnerId:$EVENT_KIND_ANSWER_REVEAL:$outcomeId",
    learnerId = learnerId,
    outboxSequence = eventSequence,
    eventKind = EVENT_KIND_ANSWER_REVEAL,
    eventId = outcomeId,
    canonicalFingerprint = canonicalFingerprint,
    status = StudyDbValue.OutboxStatus.PENDING,
    createdAtEpochMillis = occurredAtEpochMillis,
)

internal fun ProjectionOutboxEntity.toRecord() = ProjectionOutboxRecord(
    outboxId = outboxId,
    learnerId = learnerId,
    outboxSequence = outboxSequence,
    eventKind = eventKind,
    eventId = eventId,
    canonicalFingerprint = canonicalFingerprint,
    status = status,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun LearnerSnapshot.toEntity(
    projectionName: String,
    stateVersion: Long,
    knownLedgerHeadSequence: Long,
) = LearnerProjectionSnapshotEntity(
    projectionName = projectionName,
    learnerId = learnerId,
    stateVersion = stateVersion,
    checkpointSequence = checkpoint.lastSequence,
    knownLedgerHeadSequence = knownLedgerHeadSequence,
    projectorVersion = checkpoint.projectorVersion,
    projectedAtEpochMillis = checkpoint.projectedAtEpochMillis,
    generatedAtEpochMillis = generatedAtEpochMillis,
    correctionWatermarkEpochMillis = correctionWatermarkEpochMillis,
    freshness = freshness.name,
    projectionStatus = projectionStatus.name,
)

internal fun LearnerSnapshot.toMemoryEntities(projectionName: String) = problemMemoryStates.values
    .sortedBy(ProblemMemoryState::practiceUnitId)
    .map { state ->
        LearnerProblemMemoryStateEntity(
            projectionName = projectionName,
            learnerId = learnerId,
            practiceUnitId = state.practiceUnitId,
            stabilityDays = state.stabilityDays,
            difficulty = state.difficulty,
            lastReviewedAtEpochMillis = state.lastReviewedAtEpochMillis,
            nextReviewAtEpochMillis = state.nextReviewAtEpochMillis,
            independentCorrectCount = state.independentCorrectCount,
            assistedCorrectCount = state.assistedCorrectCount,
            lapseCount = state.lapseCount,
            answerRevealCount = state.answerRevealCount,
            lastLapseAtEpochMillis = state.lastLapseAtEpochMillis,
            clockAnomalyCount = state.clockAnomalyCount,
            lastClockAnomalyAtEpochMillis = state.lastClockAnomalyAtEpochMillis,
            lastAttemptId = state.lastAttemptId,
            projectorVersion = state.projectorVersion,
            checkpointSequence = state.checkpointSequence,
        )
    }

internal fun LearnerSnapshot.toMasteryEntities(
    projectionName: String,
): Pair<List<LearnerKnowledgeMasteryStateEntity>, List<IndependentCorrectObservationEntity>> {
    val states = mutableListOf<LearnerKnowledgeMasteryStateEntity>()
    val observations = mutableListOf<IndependentCorrectObservationEntity>()
    knowledgeMasteryStates.values.sortedBy(KnowledgeMasteryState::knowledgeNodeId).forEach { state ->
        states += LearnerKnowledgeMasteryStateEntity(
            projectionName = projectionName,
            learnerId = learnerId,
            knowledgeNodeId = state.knowledgeNodeId,
            masteryScore = state.masteryScore,
            conservativeMasteryScore = state.conservativeMasteryScore,
            evidenceMass = state.evidenceMass,
            lastIndependentErrorAtEpochMillis = state.lastIndependentErrorAtEpochMillis,
            lastIndependentErrorSequence = state.lastIndependentErrorSequence,
            status = state.status.name,
            calibrationSupport = state.calibrationSupport.name,
            projectorVersion = state.projectorVersion,
            checkpointSequence = state.checkpointSequence,
            lastEvidenceAtEpochMillis = state.lastEvidenceAtEpochMillis,
            conflictSinceSequence = state.conflictSinceSequence,
        )
        state.independentCorrectObservations.forEachIndexed { ordinal, observation ->
            observations += IndependentCorrectObservationEntity(
                projectionName = projectionName,
                learnerId = learnerId,
                knowledgeNodeId = state.knowledgeNodeId,
                ordinal = ordinal,
                itemFamilyId = observation.itemFamilyId,
                studyDayEpochDay = observation.studyDayEpochDay,
                isStudyDayTrusted = observation.isStudyDayTrusted,
                timeTrust = observation.timeTrust.name,
                occurredAtEpochMillis = observation.occurredAtEpochMillis,
                eventSequence = observation.eventSequence,
                bindingId = observation.bindingId,
                evidenceWeight = observation.evidenceWeight,
                calibrationSupport = observation.calibration.support.name,
                calibrationSourceId = observation.calibration.sourceId,
                calibrationVersion = observation.calibration.version,
                calibrationValidFromEpochMillis = observation.calibration.validFromEpochMillis,
                calibrationValidUntilEpochMillis = observation.calibration.validUntilEpochMillis,
            )
        }
    }
    return states to observations
}

internal fun LearnerSnapshot.toAppliedEntities(projectionName: String) = appliedAttemptRecords.values
    .sortedBy(AppliedAttemptRecord::eventSequence)
    .map { record ->
        AppliedAttemptRecordEntity(
            projectionName = projectionName,
            learnerId = learnerId,
            attemptId = record.attemptId,
            canonicalFingerprint = record.canonicalFingerprint,
            eventSequence = record.eventSequence,
            presentationId = record.presentationId,
            responseOrdinal = record.responseOrdinal,
        )
    }

internal fun LearnerSnapshot.toAppliedCorrectionEntities(projectionName: String) =
    appliedCorrectionRecords.values
        .sortedBy(AppliedCorrectionRecord::eventSequence)
        .map { record ->
            AppliedCorrectionRecordEntity(
                projectionName = projectionName,
                learnerId = learnerId,
                correctionId = record.correctionId,
                attemptId = record.attemptId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
            )
        }

internal fun LearnerSnapshot.toAppliedAnswerRevealEntities(projectionName: String) =
    appliedAnswerRevealRecords.values
        .sortedBy(AppliedAnswerRevealRecord::eventSequence)
        .map { record ->
            AppliedAnswerRevealRecordEntity(
                projectionName = projectionName,
                learnerId = learnerId,
                outcomeId = record.outcomeId,
                presentationId = record.presentationId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
            )
        }

internal fun LearnerSnapshot.toAppliedTutorAnswerExposureEntities(projectionName: String) =
    appliedTutorAnswerExposureRecords.values
        .sortedBy(AppliedTutorAnswerExposureRecord::eventSequence)
        .map { record ->
            AppliedTutorAnswerExposureRecordEntity(
                projectionName = projectionName,
                learnerId = learnerId,
                outcomeId = record.outcomeId,
                exposureId = record.exposureId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
            )
        }

internal fun LearnerProjectionSnapshotEntity.toPersistedSnapshot(
    memoryStates: List<LearnerProblemMemoryStateEntity>,
    masteryStates: List<LearnerKnowledgeMasteryStateEntity>,
    observations: List<IndependentCorrectObservationEntity>,
    appliedAttempts: List<AppliedAttemptRecordEntity>,
    appliedCorrections: List<AppliedCorrectionRecordEntity>,
    appliedAnswerReveals: List<AppliedAnswerRevealRecordEntity>,
    appliedTutorAnswerExposures: List<AppliedTutorAnswerExposureRecordEntity>,
): PersistedLearnerSnapshot {
    val observationsByKnowledge = observations.groupBy { it.knowledgeNodeId }
    val snapshot = LearnerSnapshot(
        learnerId = learnerId,
        problemMemoryStates = memoryStates.associate { state ->
            state.practiceUnitId to ProblemMemoryState(
                practiceUnitId = state.practiceUnitId,
                stabilityDays = state.stabilityDays,
                difficulty = state.difficulty,
                lastReviewedAtEpochMillis = state.lastReviewedAtEpochMillis,
                nextReviewAtEpochMillis = state.nextReviewAtEpochMillis,
                independentCorrectCount = state.independentCorrectCount,
                assistedCorrectCount = state.assistedCorrectCount,
                lapseCount = state.lapseCount,
                answerRevealCount = state.answerRevealCount,
                lastLapseAtEpochMillis = state.lastLapseAtEpochMillis,
                clockAnomalyCount = state.clockAnomalyCount,
                lastClockAnomalyAtEpochMillis = state.lastClockAnomalyAtEpochMillis,
                lastAttemptId = state.lastAttemptId,
                projectorVersion = state.projectorVersion,
                checkpointSequence = state.checkpointSequence,
            )
        },
        knowledgeMasteryStates = masteryStates.associate { state ->
            state.knowledgeNodeId to KnowledgeMasteryState(
                knowledgeNodeId = state.knowledgeNodeId,
                masteryScore = state.masteryScore,
                conservativeMasteryScore = state.conservativeMasteryScore,
                evidenceMass = state.evidenceMass,
                independentCorrectObservations = observationsByKnowledge[state.knowledgeNodeId]
                    .orEmpty()
                    .sortedBy { it.ordinal }
                    .map { observation ->
                        IndependentCorrectObservation(
                            itemFamilyId = observation.itemFamilyId,
                            studyDayEpochDay = observation.studyDayEpochDay,
                            timeTrust = try {
                                EventTimeTrust.valueOf(observation.timeTrust)
                            } catch (_: IllegalArgumentException) {
                                if (observation.isStudyDayTrusted) {
                                    EventTimeTrust.TRUSTED
                                } else {
                                    EventTimeTrust.CLOCK_ROLLBACK_CLAMPED
                                }
                            },
                            occurredAtEpochMillis = observation.occurredAtEpochMillis,
                            eventSequence = observation.eventSequence,
                            bindingId = observation.bindingId,
                            evidenceWeight = observation.evidenceWeight,
                            calibration = CalibrationSnapshot(
                                support = CalibrationSupport.valueOf(observation.calibrationSupport),
                                sourceId = observation.calibrationSourceId,
                                version = observation.calibrationVersion,
                                validFromEpochMillis = observation.calibrationValidFromEpochMillis,
                                validUntilEpochMillis = observation.calibrationValidUntilEpochMillis,
                            ),
                        )
                    },
                lastIndependentErrorAtEpochMillis = state.lastIndependentErrorAtEpochMillis,
                lastIndependentErrorSequence = state.lastIndependentErrorSequence,
                status = MasteryStatus.valueOf(state.status),
                calibrationSupport = CalibrationSupport.valueOf(state.calibrationSupport),
                projectorVersion = state.projectorVersion,
                checkpointSequence = state.checkpointSequence,
                lastEvidenceAtEpochMillis = state.lastEvidenceAtEpochMillis,
                conflictSinceSequence = state.conflictSinceSequence,
            )
        },
        checkpoint = ProjectionCheckpoint(
            lastSequence = checkpointSequence,
            projectorVersion = projectorVersion,
            projectedAtEpochMillis = projectedAtEpochMillis,
        ),
        knownLedgerHeadSequence = knownLedgerHeadSequence,
        generatedAtEpochMillis = generatedAtEpochMillis,
        correctionWatermarkEpochMillis = correctionWatermarkEpochMillis,
        freshness = LearnerSnapshotFreshness.valueOf(freshness),
        projectionStatus = ProjectionStatus.valueOf(projectionStatus),
        appliedAttemptRecords = appliedAttempts.associate { record ->
            record.attemptId to AppliedAttemptRecord(
                attemptId = record.attemptId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
                presentationId = record.presentationId,
                responseOrdinal = record.responseOrdinal,
            )
        },
        appliedCorrectionRecords = appliedCorrections.associate { record ->
            record.correctionId to AppliedCorrectionRecord(
                correctionId = record.correctionId,
                attemptId = record.attemptId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
            )
        },
        appliedAnswerRevealRecords = appliedAnswerReveals.associate { record ->
            record.outcomeId to AppliedAnswerRevealRecord(
                outcomeId = record.outcomeId,
                presentationId = record.presentationId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
            )
        },
        appliedTutorAnswerExposureRecords = appliedTutorAnswerExposures.associate { record ->
            record.outcomeId to AppliedTutorAnswerExposureRecord(
                outcomeId = record.outcomeId,
                exposureId = record.exposureId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
            )
        },
    )
    return PersistedLearnerSnapshot(
        projectionName = projectionName,
        stateVersion = stateVersion,
        knownLedgerHeadSequence = knownLedgerHeadSequence,
        snapshot = snapshot,
    )
}
