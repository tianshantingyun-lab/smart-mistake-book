package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.ColumnInfo

internal const val PROJECTION_REBUILD_PROGRESS_V3_METADATA_PREFIX =
    "projection_rebuild_progress_v3:"
internal const val PROJECTION_REBUILD_PROGRESS_V2_METADATA_PREFIX =
    "projection_rebuild_progress_v2:"
internal const val PROJECTION_REBUILD_COMPLETED_METADATA_KEY =
    "projection_rebuild_completed_v3:event-sequence-subject-history-stream-v2"
internal const val LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE = 512
internal const val LEARNER_MASTERY_RETIREMENT_POLICY_VERSION =
    "learner-mastery-revision-retirement-v1"
// Bounds both SQLite IN parameters and attribution materialization independently of transactions.
internal const val PROJECTION_REPLAY_EVENT_QUERY_BATCH_SIZE = 256
internal const val PROJECTION_EVIDENCE_DIMENSION_PAGE_SIZE = 64
internal const val PROJECTION_REBUILD_METADATA_KEY = "projection_rebuild_policy"
internal const val PROJECTION_REBUILD_REQUIRED_V2 = "REQUIRED_V2"
// This is part of the v2 receipt format; changing it requires an algorithm-version bump.
internal const val DIRECTIONAL_BUDGET_FINGERPRINT_CHUNK_SIZE = 256
internal const val PROJECTION_DIMENSION_STREAM_CURSOR_VERSION = -2L
internal const val PROJECTION_REBUILD_SEQUENCE_WIDTH = 20

internal data class MasteryBandCountRow(
    @ColumnInfo(name = "needs_reinforcement_count")
    val needsReinforcementCount: Long?,
    @ColumnInfo(name = "familiarizing_count")
    val familiarizingCount: Long?,
    @ColumnInfo(name = "steady_count")
    val steadyCount: Long?,
)

internal data class MasteryTimelineRow(
    @ColumnInfo(name = "utc_epoch_day")
    val utcEpochDay: Long,
    @ColumnInfo(name = "observation_count")
    val observationCount: Long,
    @ColumnInfo(name = "affected_knowledge_count")
    val affectedKnowledgeCount: Long,
    @ColumnInfo(name = "positive_mass_micros")
    val positiveMassMicros: Long,
    @ColumnInfo(name = "negative_mass_micros")
    val negativeMassMicros: Long,
)

internal data class MasterySubjectDigestSnapshot(
    val digest: MasterySubjectDigestEntity?,
    val focus: List<MasteryKnowledgeProjectionEntity>,
)

internal fun roundRobinDistinctFocus(
    limit: Int,
    lanes: List<List<MasteryKnowledgeProjectionEntity>>,
): List<MasteryKnowledgeProjectionEntity> {
    val result = ArrayList<MasteryKnowledgeProjectionEntity>(limit)
    val seen = HashSet<String>(limit)
    var laneIndex = 0
    while (result.size < limit) {
        var advanced = false
        lanes.forEach { lane ->
            if (laneIndex < lane.size) {
                advanced = true
                val projection = lane[laneIndex]
                if (seen.add(projection.stableNodeIdentityFingerprint)) {
                    result += projection
                }
            }
        }
        if (!advanced) break
        laneIndex += 1
    }
    return result.take(limit)
}

internal data class MasteryDisplayOverviewDaoSnapshot(
    val revision: Long,
    val revisionChanged: Boolean,
    val projections: List<MasteryKnowledgeProjectionEntity>,
    val taxonomyVersions: Set<String>,
)

internal data class MasteryDisplayTemporalBoundsRow(
    @ColumnInfo(name = "latest_transition_at_epoch_millis")
    val latestTransitionAtEpochMillis: Long?,
    @ColumnInfo(name = "next_transition_at_epoch_millis")
    val nextTransitionAtEpochMillis: Long?,
)

internal data class MasteryDisplayPageDaoSnapshot(
    val revision: Long,
    val revisionChanged: Boolean,
    val projections: List<MasteryKnowledgeProjectionEntity>,
    val taxonomyVersions: Set<String>,
)

internal data class MasteryEventAttributionReplayRow(
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "source_proof_fingerprint")
    val sourceProofFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    val direction: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "admitted_at_epoch_millis")
    val admittedAtEpochMillis: Long,
    @ColumnInfo(name = "projection_policy_version")
    val projectionPolicyVersion: String,
    @ColumnInfo(name = "admission_policy_version")
    val admissionPolicyVersion: String,
    @ColumnInfo(name = "calibration_version")
    val calibrationVersion: String,
    @ColumnInfo(name = "calibration_snapshot_fingerprint")
    val calibrationSnapshotFingerprint: String?,
    @ColumnInfo(name = "calibration_profile_id")
    val calibrationProfileId: String?,
    @ColumnInfo(name = "review_resolution_fingerprint")
    val reviewResolutionFingerprint: String?,
    @ColumnInfo(name = "problem_family_fingerprint")
    val problemFamilyFingerprint: String?,
    @ColumnInfo(name = "presentation_fingerprint")
    val presentationFingerprint: String,
    @ColumnInfo(name = "evidence_quality_micros")
    val evidenceQualityMicros: Long,
    @ColumnInfo(name = "independently_answered")
    val independentlyAnswered: Boolean,
    @ColumnInfo(name = "event_canonical_fingerprint")
    val eventCanonicalFingerprint: String,
    val ordinal: Int,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "knowledge_pack_version")
    val knowledgePackVersion: String,
    @ColumnInfo(name = "knowledge_node_ref_fingerprint")
    val knowledgeNodeRefFingerprint: String,
    @ColumnInfo(name = "evidence_mass_micros")
    val evidenceMassMicros: Long,
) {
    fun event(): MasteryLearningEventEntity =
        MasteryLearningEventEntity(
            eventId = eventId,
            candidateId = candidateId,
            sourceFactId = sourceFactId,
            sourceProofFingerprint = sourceProofFingerprint,
            learnerId = learnerId,
            subject = subject,
            direction = direction,
            eventSequence = eventSequence,
            occurredAtEpochMillis = occurredAtEpochMillis,
            admittedAtEpochMillis = admittedAtEpochMillis,
            projectionPolicyVersion = projectionPolicyVersion,
            admissionPolicyVersion = admissionPolicyVersion,
            calibrationVersion = calibrationVersion,
            calibrationSnapshotFingerprint = calibrationSnapshotFingerprint,
            calibrationProfileId = calibrationProfileId,
            reviewResolutionFingerprint = reviewResolutionFingerprint,
            canonicalFingerprint = eventCanonicalFingerprint,
            problemFamilyFingerprint = problemFamilyFingerprint,
            presentationFingerprint = presentationFingerprint,
            evidenceQualityMicros = evidenceQualityMicros,
            independentlyAnswered = independentlyAnswered,
        )

    fun attribution(): MasteryLearningEventAttributionEntity =
        MasteryLearningEventAttributionEntity(
            eventId = eventId,
            ordinal = ordinal,
            subject = subject,
            knowledgeNodeId = knowledgeNodeId,
            taxonomyVersion = taxonomyVersion,
            knowledgePackVersion = knowledgePackVersion,
            knowledgeNodeRefFingerprint = knowledgeNodeRefFingerprint,
            evidenceMassMicros = evidenceMassMicros,
        )
}

internal data class MasteryProjectionEvidenceDimensionsRow(
    @ColumnInfo(name = "evidence_quality_sum_micros")
    val evidenceQualitySumMicros: Long?,
    @ColumnInfo(name = "evidence_event_count")
    val evidenceEventCount: Long,
    @ColumnInfo(name = "independent_problem_family_count")
    val independentProblemFamilyCount: Long,
    @ColumnInfo(name = "distinct_presentation_count")
    val distinctPresentationCount: Long,
)

internal data class MasteryProjectionNodeEvidenceDimensionsRow(
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "evidence_quality_sum_micros")
    val evidenceQualitySumMicros: Long?,
    @ColumnInfo(name = "evidence_event_count")
    val evidenceEventCount: Long,
    @ColumnInfo(name = "independent_problem_family_count")
    val independentProblemFamilyCount: Long,
    @ColumnInfo(name = "distinct_presentation_count")
    val distinctPresentationCount: Long,
)

internal data class MasteryProjectionBudgetInputTailRow(
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    val ordinal: Int,
    val direction: String,
)

internal data class MasterySubjectRebuildRow(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "last_event_sequence")
    val lastEventSequence: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

internal data class MasteryPresentationBudgetRebuildRow(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "presentation_fingerprint")
    val presentationFingerprint: String,
    val subject: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    val direction: String,
    @ColumnInfo(name = "consumed_mass_micros")
    val consumedMassMicros: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

internal data class MasteryProblemFamilyBudgetRebuildRow(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "problem_family_fingerprint")
    val problemFamilyFingerprint: String,
    val subject: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    val direction: String,
    @ColumnInfo(name = "observation_count")
    val observationCount: Long,
    @ColumnInfo(name = "consumed_mass_micros")
    val consumedMassMicros: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

internal sealed interface InternalSourceFactWriteResult {
    data class Stored(
        val policySupported: Boolean,
    ) : InternalSourceFactWriteResult

    data object Duplicate : InternalSourceFactWriteResult

    data object Conflict : InternalSourceFactWriteResult
}

internal data class MasteryLegacyObservationWrite(
    val sourceFact: MasterySourceFactEntity,
    val sourceProof: MasterySourceProofEntity,
    val candidate: MasteryObservationCandidateEntity,
    val attributions: List<MasteryCandidateAttributionEntity>,
    val decisionTimeEpochMillis: Long,
    val candidateOrigin: MasteryCandidateOrigin,
)

internal data class MasteryProjectionRebuildChunkResult(
    val stage: MasteryProjectionRebuildStage,
    val processedRowCount: Int,
    val completed: Boolean,
    val blockedReason: MasteryCalibrationBindingBlockReason? = null,
    val immutableHistoryQueryCount: Int = 0,
    val peakMaterializedRowCount: Int = 0,
    val generationId: Long? = null,
    val activeGenerationAvailable: Boolean = true,
    val leaseBusy: Boolean = false,
)

internal enum class MasteryCalibrationBindingBlockReason {
    INCOMPLETE_CALIBRATED_BINDING,
    MALFORMED_LEGACY_BINDING,
    UNKNOWN_CALIBRATION_SNAPSHOT,
    PERSISTED_CALIBRATION_SNAPSHOT_MISSING,
    PERSISTED_CALIBRATION_SNAPSHOT_CONFLICT,
}

internal enum class MasteryProjectionRebuildStage {
    RESET,
    PROJECTIONS,
    DIMENSIONS,
    EVIDENCE_DIMENSIONS,
    PRESENTATION_BUDGETS,
    PROBLEM_FAMILY_BUDGETS,
    SUBJECT_DIGESTS,
    COMPLETE,
}

internal data class MasteryProjectionRebuildCursor(
    val learnerId: String = "",
    val subject: String = "",
    val knowledgeNodeId: String = "",
    val taxonomyVersion: String = "",
    val protocolVersion: String = "",
    val eventSequence: Long = -1L,
    val eventId: String = "",
    val ordinal: Int = -1,
)

internal data class MasteryProjectionRebuildProgress(
    val sequence: Long,
    val stage: MasteryProjectionRebuildStage,
    val cursor: MasteryProjectionRebuildCursor,
)

internal data class MasteryProjectionNodeKey(
    val subject: String,
    val knowledgeNodeId: String,
    val taxonomyVersion: String,
)

internal data class MasteryProjectionReplayKey(
    val learnerId: String,
    val subject: String,
    val knowledgeNodeId: String,
    val taxonomyVersion: String,
)

internal data class MasteryProjectionReplayEventCursor(
    val learnerId: String,
    val occurredAtEpochMillis: Long,
    val eventId: String,
)

internal data class MasteryPresentationBudgetReplayKey(
    val learnerId: String,
    val presentationId: String,
    val subject: String,
    val knowledgeNodeId: String,
    val taxonomyVersion: String,
    val direction: String,
)

internal data class MasteryProblemFamilyBudgetReplayKey(
    val learnerId: String,
    val problemFamilyFingerprint: String,
    val subject: String,
    val knowledgeNodeId: String,
    val taxonomyVersion: String,
    val direction: String,
)

internal data class MasteryBudgetLatestEvent(
    val eventId: String,
    val updatedAtEpochMillis: Long,
)

internal fun latestBudgetEvent(
    existingEventId: String?,
    existingUpdatedAtEpochMillis: Long?,
    candidateEventId: String,
    candidateUpdatedAtEpochMillis: Long,
): MasteryBudgetLatestEvent {
    if (existingEventId == null || existingUpdatedAtEpochMillis == null) {
        return MasteryBudgetLatestEvent(candidateEventId, candidateUpdatedAtEpochMillis)
    }
    return if (
        candidateUpdatedAtEpochMillis > existingUpdatedAtEpochMillis ||
        (
            candidateUpdatedAtEpochMillis == existingUpdatedAtEpochMillis &&
                candidateEventId > existingEventId
        )
    ) {
        MasteryBudgetLatestEvent(candidateEventId, candidateUpdatedAtEpochMillis)
    } else {
        MasteryBudgetLatestEvent(existingEventId, existingUpdatedAtEpochMillis)
    }
}

internal data class MasteryCalibrationBindingRow(
    val subject: String,
    @ColumnInfo(name = "projection_policy_version")
    val projectionPolicyVersion: String,
    @ColumnInfo(name = "calibration_version")
    val calibrationVersion: String?,
    @ColumnInfo(name = "calibration_profile_id")
    val calibrationProfileId: String?,
    @ColumnInfo(name = "calibration_snapshot_fingerprint")
    val calibrationSnapshotFingerprint: String?,
)

internal class LegacyMasteryBatchConflictException : RuntimeException()

internal class LearningEvidenceCorrectionRejectedException : RuntimeException()

internal fun rejectedOpenResponseWeakCandidate(
    disposition: LearnerMasteryOpenResponseWeakCandidateDisposition =
        LearnerMasteryOpenResponseWeakCandidateDisposition.REJECTED,
): LearnerMasteryOpenResponseWeakCandidateResult =
    LearnerMasteryOpenResponseWeakCandidateResult(
        disposition = disposition,
        receiptFingerprint = null,
    )

internal data class PreparedOpenResponseDedicatedDecision(
    val decision: MasteryOpenResponseDedicatedDecisionEntity,
)

internal fun expectedOpenResponseRetryState(attemptOrdinal: Int): ObservedRetryState =
    when (attemptOrdinal) {
        1 -> ObservedRetryState.FIRST_ATTEMPT
        2 -> ObservedRetryState.ONE_RETRY
        else -> ObservedRetryState.MULTIPLE_RETRIES
    }

internal fun expectedOpenResponseAssistance(
    attemptOrdinal: Int,
    hintCount: Int,
    answerWasRevealed: Boolean,
): ObservedAssistance =
    when {
        answerWasRevealed -> ObservedAssistance.ANSWER_REVEALED
        attemptOrdinal == 1 && hintCount == 0 -> ObservedAssistance.INDEPENDENT
        hintCount == 1 -> ObservedAssistance.ONE_HINT
        hintCount > 1 -> ObservedAssistance.MULTIPLE_HINTS
        else -> ObservedAssistance.UNKNOWN
    }

internal fun bindingAuthorityStateDisposition(
    current: MasteryProblemBindingAuthorityStateEntity?,
    incoming: MasteryProblemBindingAuthorityStateEntity,
): MasteryInboundDisposition? {
    if (current == null) {
        return null
    }
    if (incoming.bindingProtocolVersion == LEGACY_BINDING_PROTOCOL_VERSION) {
        return MasteryInboundDisposition.DUPLICATE
    }
    if (
        current.bindingProtocolVersion != CURRENT_BINDING_PROTOCOL_VERSION ||
        incoming.bindingProtocolVersion != CURRENT_BINDING_PROTOCOL_VERSION ||
        current.sourceStoreGeneration != incoming.sourceStoreGeneration
    ) {
        return null
    }
    return when {
        incoming.bindingSetVersion < current.bindingSetVersion ->
            MasteryInboundDisposition.DUPLICATE
        incoming.bindingSetVersion > current.bindingSetVersion -> null
        incoming.payloadCanonicalFingerprint == current.payloadCanonicalFingerprint ->
            MasteryInboundDisposition.DUPLICATE
        else -> MasteryInboundDisposition.CONFLICT
    }
}

internal fun MasteryLearningEventEntity.replayCursor(): MasteryProjectionReplayEventCursor =
    MasteryProjectionReplayEventCursor(
        learnerId = learnerId,
        occurredAtEpochMillis = occurredAtEpochMillis,
        eventId = eventId,
    )

internal fun MasteryLearningEventEntity.replayRow(
    attribution: MasteryLearningEventAttributionEntity,
): MasteryEventAttributionReplayRow {
    check(eventId == attribution.eventId) {
        "Projection replay event and attribution identities differ"
    }
    check(subject == attribution.subject) {
        "Projection replay event and attribution subjects differ"
    }
    return MasteryEventAttributionReplayRow(
        eventId = eventId,
        candidateId = candidateId,
        sourceFactId = sourceFactId,
        sourceProofFingerprint = sourceProofFingerprint,
        learnerId = learnerId,
        subject = subject,
        direction = direction,
        eventSequence = eventSequence,
        occurredAtEpochMillis = occurredAtEpochMillis,
        admittedAtEpochMillis = admittedAtEpochMillis,
        projectionPolicyVersion = projectionPolicyVersion,
        admissionPolicyVersion = admissionPolicyVersion,
        calibrationVersion = calibrationVersion,
        calibrationSnapshotFingerprint = calibrationSnapshotFingerprint,
        calibrationProfileId = calibrationProfileId,
        reviewResolutionFingerprint = reviewResolutionFingerprint,
        problemFamilyFingerprint = problemFamilyFingerprint,
        presentationFingerprint = presentationFingerprint,
        evidenceQualityMicros = evidenceQualityMicros,
        independentlyAnswered = independentlyAnswered,
        eventCanonicalFingerprint = canonicalFingerprint,
        ordinal = attribution.ordinal,
        knowledgeNodeId = attribution.knowledgeNodeId,
        taxonomyVersion = attribution.taxonomyVersion,
        knowledgePackVersion = attribution.knowledgePackVersion,
        knowledgeNodeRefFingerprint = attribution.knowledgeNodeRefFingerprint,
        evidenceMassMicros = attribution.evidenceMassMicros,
    )
}
