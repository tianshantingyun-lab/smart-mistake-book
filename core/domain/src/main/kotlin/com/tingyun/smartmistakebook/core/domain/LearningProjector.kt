package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AppliedAttemptRecord
import com.tingyun.smartmistakebook.core.model.AppliedAnswerRevealRecord
import com.tingyun.smartmistakebook.core.model.AppliedCorrectionRecord
import com.tingyun.smartmistakebook.core.model.AppliedTutorAnswerExposureRecord
import com.tingyun.smartmistakebook.core.model.AppliedLearningObservationRecord
import com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent
import com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.IndependentCorrectObservation
import com.tingyun.smartmistakebook.core.model.IncrementalLearningEvent
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.LearningLedgerEvent
import com.tingyun.smartmistakebook.core.model.LearningLedgerFingerprint
import com.tingyun.smartmistakebook.core.model.LearningObservationDirection
import com.tingyun.smartmistakebook.core.model.LearningObservationIndependence
import com.tingyun.smartmistakebook.core.model.LearningObservationKnowledgeAttribution
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.PresentationProjectionState
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome
import kotlin.math.sqrt

enum class FullReplayReason {
    SEMANTIC_TIME_ROLLBACK,
}

data class LearningProjectionResult(
    val snapshot: LearnerSnapshot,
    val appliedAttemptIds: Set<String>,
    val ignoredAttemptIds: Set<String>,
    val conflictedAttemptIds: Set<String>,
    val deferredAttemptIds: Set<String> = emptySet(),
    val missingSequence: Long? = null,
    val ambiguousAttemptIds: Set<String> = emptySet(),
    val correctedAttemptIds: Set<String> = emptySet(),
    val appliedAnswerRevealOutcomeIds: Set<String> = emptySet(),
    val ignoredAnswerRevealOutcomeIds: Set<String> = emptySet(),
    val conflictedAnswerRevealOutcomeIds: Set<String> = emptySet(),
    val deferredAnswerRevealOutcomeIds: Set<String> = emptySet(),
    val appliedTutorAnswerExposureOutcomeIds: Set<String> = emptySet(),
    val ignoredTutorAnswerExposureOutcomeIds: Set<String> = emptySet(),
    val conflictedTutorAnswerExposureOutcomeIds: Set<String> = emptySet(),
    val deferredTutorAnswerExposureOutcomeIds: Set<String> = emptySet(),
    val appliedLearningObservationEventIds: Set<String> = emptySet(),
    val ignoredLearningObservationEventIds: Set<String> = emptySet(),
    val conflictedLearningObservationEventIds: Set<String> = emptySet(),
    val deferredLearningObservationEventIds: Set<String> = emptySet(),
    val ambiguousLearningObservationEventIds: Set<String> = emptySet(),
    val presentationProjectionStates: Map<String, PresentationProjectionState> = emptyMap(),
    val fullReplayReason: FullReplayReason? = null,
) {
    val requiresFullReplay: Boolean
        get() = fullReplayReason != null
}

/** Deterministic projection of a gap-free append-only attempt prefix into learner state. */
class LearningProjector(
    private val forgettingCurve: ForgettingCurve = ForgettingCurve(),
) {
    /** Test-only convenience. Production adapters must supply persistence-authoritative state. */
    internal fun project(
        previous: LearnerSnapshot,
        events: List<IncrementalLearningEvent>,
        knownLedgerHeadSequence: Long,
    ): LearningProjectionResult = project(
        previous = previous,
        events = events,
        knownLedgerHeadSequence = knownLedgerHeadSequence,
        authoritativePresentationStates = inferPresentationStates(previous, events),
    )

    /**
     * Projects against presentation rows read at [previous]'s checkpoint. The persistence adapter
     * must enforce response-ordinal CAS and one terminal reveal before supplying this pure state.
     */
    fun project(
        previous: LearnerSnapshot,
        events: List<IncrementalLearningEvent>,
        knownLedgerHeadSequence: Long,
        authoritativePresentationStates: Map<String, PresentationProjectionState>,
    ): LearningProjectionResult {
        requireCompatibleSnapshot(previous)
        require(knownLedgerHeadSequence >= previous.knownLedgerHeadSequence) {
            "Known ledger head must be monotonic"
        }
        require(knownLedgerHeadSequence >= (events.maxOfOrNull { it.eventSequence } ?: 0)) {
            "Known ledger head must cover every supplied event"
        }
        require(authoritativePresentationStates.all { (id, state) -> id == state.presentationId }) {
            "Authoritative presentation-state keys must match their values"
        }
        require(events.filterIsInstance<AttributedLearningObservationEvent>().all {
            it.learnerId == previous.learnerId
        }) { "Learning observation learner must match the projected snapshot" }
        val incomingPresentationIds = events.mapNotNullTo(linkedSetOf()) { event ->
            when (event) {
                is Attempt -> event.presentationId
                is AnswerRevealOutcome -> event.presentationId
                is TutorAnswerExposureOutcome -> null
                is AttributedLearningObservationEvent -> null
            }
        }
        require(incomingPresentationIds.all(authoritativePresentationStates::containsKey)) {
            "Every supplied event requires persistence-authoritative presentation state"
        }
        require(incomingPresentationIds.all {
            authoritativePresentationStates.getValue(it).asOfLedgerSequence == previous.checkpoint.lastSequence
        }) { "Presentation state must be read at the learner snapshot checkpoint" }

        val incomingAttemptIds = events.filterIsInstance<Attempt>().mapTo(linkedSetOf(), Attempt::attemptId)
        val incomingRevealIds = events.filterIsInstance<AnswerRevealOutcome>()
            .mapTo(linkedSetOf(), AnswerRevealOutcome::outcomeId)
        val incomingTutorExposureIds = events.filterIsInstance<TutorAnswerExposureOutcome>()
            .mapTo(linkedSetOf(), TutorAnswerExposureOutcome::outcomeId)
        val incomingObservationIds = events.filterIsInstance<AttributedLearningObservationEvent>()
            .mapTo(linkedSetOf(), AttributedLearningObservationEvent::eventId)
        val fingerprinted = events.map { it to LearningLedgerFingerprint.event(it) }
        val byEventId = fingerprinted.groupBy { it.first.ledgerEventId }
        val sameIdConflictVariants = byEventId
            .filterValues { variants -> variants.map { it.second }.distinct().size > 1 }
        val sameIdConflicts = sameIdConflictVariants.keys
        val unique = fingerprinted
            .filterNot { it.first.ledgerEventId in sameIdConflicts }
            .distinctBy { it.first.ledgerEventId }

        val ignored = linkedSetOf<String>()
        val historicalConflicts = sameIdConflictVariants
            .filterValues { variants ->
                variants.minOf { it.first.eventSequence } <= previous.checkpoint.lastSequence
            }
            .keys
            .toMutableSet()
        val batchPayloadConflicts = sameIdConflictVariants
            .filterKeys { it !in historicalConflicts }
            .entries
            .groupBy(
                keySelector = { (_, variants) -> variants.minOf { it.first.eventSequence } },
                valueTransform = { it.key },
            )
        val fresh = mutableListOf<Pair<IncrementalLearningEvent, String>>()
        unique.forEach { (event, fingerprint) ->
            val recorded = when (event) {
                is Attempt -> previous.appliedAttemptRecords[event.attemptId]?.let {
                    it.eventSequence to it.canonicalFingerprint
                }
                is AnswerRevealOutcome -> previous.appliedAnswerRevealRecords[event.outcomeId]?.let {
                    it.eventSequence to it.canonicalFingerprint
                }
                is TutorAnswerExposureOutcome ->
                    previous.appliedTutorAnswerExposureRecords[event.outcomeId]?.let {
                        it.eventSequence to it.canonicalFingerprint
                    }
                is AttributedLearningObservationEvent ->
                    previous.appliedLearningObservationRecords[event.eventId]?.let {
                        it.eventSequence to it.canonicalFingerprint
                    }
            }
            val idExistsAsAnotherType = when (event) {
                is Attempt -> event.attemptId in previous.appliedAnswerRevealRecords ||
                    event.attemptId in previous.appliedTutorAnswerExposureRecords ||
                    event.attemptId in previous.appliedLearningObservationRecords
                is AnswerRevealOutcome -> event.outcomeId in previous.appliedAttemptRecords ||
                    event.outcomeId in previous.appliedTutorAnswerExposureRecords ||
                    event.outcomeId in previous.appliedLearningObservationRecords
                is TutorAnswerExposureOutcome -> event.outcomeId in previous.appliedAttemptRecords ||
                    event.outcomeId in previous.appliedAnswerRevealRecords ||
                    event.outcomeId in previous.appliedLearningObservationRecords
                is AttributedLearningObservationEvent ->
                    event.eventId in previous.appliedAttemptRecords ||
                        event.eventId in previous.appliedAnswerRevealRecords ||
                        event.eventId in previous.appliedTutorAnswerExposureRecords
            }
            when {
                recorded != null &&
                    recorded.first == event.eventSequence &&
                    recorded.second == fingerprint -> ignored += event.ledgerEventId

                idExistsAsAnotherType || recorded != null || event.eventSequence <= previous.checkpoint.lastSequence ->
                    historicalConflicts += event.ledgerEventId

                else -> fresh += event to fingerprint
            }
        }

        if (previous.projectionStatus == ProjectionStatus.CONFLICTED || historicalConflicts.isNotEmpty()) {
            return unchangedConflict(
                previous = previous,
                ignored = ignored,
                conflicts = historicalConflicts,
                deferred = fresh.mapTo(linkedSetOf()) { it.first.ledgerEventId }.apply {
                    batchPayloadConflicts.values.flatten().forEach(::add)
                },
                incomingAttemptIds = incomingAttemptIds,
                incomingRevealIds = incomingRevealIds,
                incomingTutorExposureIds = incomingTutorExposureIds,
                incomingObservationIds = incomingObservationIds,
                knownLedgerHeadSequence = knownLedgerHeadSequence,
                presentationProjectionStates = authoritativePresentationStates,
            )
        }
        if (
            batchPayloadConflicts.isEmpty() &&
            hasSemanticTimeRollback(
                previous = previous,
                events = fresh.map(Pair<IncrementalLearningEvent, String>::first),
            )
        ) {
            return unchangedForFullReplay(
                previous = previous,
                ignored = ignored,
                deferred = fresh.mapTo(linkedSetOf()) { it.first.ledgerEventId },
                incomingAttemptIds = incomingAttemptIds,
                incomingRevealIds = incomingRevealIds,
                incomingTutorExposureIds = incomingTutorExposureIds,
                incomingObservationIds = incomingObservationIds,
                presentationProjectionStates = authoritativePresentationStates,
            )
        }

        val memoryStates = previous.problemMemoryStates.toMutableMap()
        val masteryStates = previous.knowledgeMasteryStates.toMutableMap()
        val attemptRecords = previous.appliedAttemptRecords.toMutableMap()
        val revealRecords = previous.appliedAnswerRevealRecords.toMutableMap()
        val tutorExposureRecords = previous.appliedTutorAnswerExposureRecords.toMutableMap()
        val observationRecords = previous.appliedLearningObservationRecords.toMutableMap()
        val presentationProjectionStates = authoritativePresentationStates.toMutableMap()
        val appliedAttempts = linkedSetOf<String>()
        val appliedReveals = linkedSetOf<String>()
        val appliedTutorExposures = linkedSetOf<String>()
        val appliedObservations = linkedSetOf<String>()
        val quarantinedObservations = linkedSetOf<String>()
        val ambiguous = linkedSetOf<String>()
        val ambiguousObservations = linkedSetOf<String>()
        val sequenceConflicts = linkedSetOf<String>()
        val deferred = linkedSetOf<String>()
        var expectedSequence = previous.checkpoint.lastSequence + 1
        var projectedAt = previous.generatedAtEpochMillis
        var missingSequence: Long? = null

        val bySequence = fresh.groupBy { it.first.eventSequence }
        val sequences = (bySequence.keys + batchPayloadConflicts.keys).sorted()
        fun pendingIdsAfter(sequence: Long): Set<String> = buildSet {
            bySequence.filterKeys { it > sequence }.values.flatten().forEach {
                add(it.first.ledgerEventId)
            }
            batchPayloadConflicts.filterKeys { it > sequence }.values.flatten().forEach(::add)
        }
        for (sequence in sequences) {
            val eventsAtSequence = bySequence[sequence].orEmpty()
            val payloadConflictIds = batchPayloadConflicts[sequence].orEmpty()
            if (sequence > expectedSequence) {
                missingSequence = expectedSequence
                deferred += eventsAtSequence.map { it.first.ledgerEventId }
                deferred += payloadConflictIds
                deferred += pendingIdsAfter(sequence)
                break
            }
            if (sequence < expectedSequence) {
                sequenceConflicts += eventsAtSequence.map { it.first.ledgerEventId } + payloadConflictIds
                continue
            }
            if (payloadConflictIds.isNotEmpty() || eventsAtSequence.size != 1) {
                sequenceConflicts += eventsAtSequence.map { it.first.ledgerEventId } + payloadConflictIds
                deferred += pendingIdsAfter(sequence)
                break
            }

            val (event, fingerprint) = eventsAtSequence.single()
            val effectiveAt = maxOf(projectedAt, event.occurredAtEpochMillis)
            when (event) {
                is Attempt -> {
                    val presentationState = presentationProjectionStates.getValue(event.presentationId)
                    val effectiveAttempt = applyRevealCausality(
                        attempt = event,
                        answerRevealSequence = presentationState.answerRevealSequence,
                        causalSequence = event.eventSequence,
                    )
                    applyAttempt(
                        memoryStates,
                        masteryStates,
                        effectiveAttempt,
                        ambiguous,
                        suppressDuplicateRevealMemory = presentationState.answerRevealSequence != null,
                        effectiveAtEpochMillis = effectiveAt,
                    )
                    presentationProjectionStates[event.presentationId] = presentationState.copy(
                        asOfLedgerSequence = event.eventSequence,
                        memoryProjectionApplied = true,
                    )
                    attemptRecords[event.attemptId] = AppliedAttemptRecord(
                        attemptId = event.attemptId,
                        canonicalFingerprint = fingerprint,
                        eventSequence = event.eventSequence,
                        presentationId = event.presentationId,
                        responseOrdinal = event.responseOrdinal,
                    )
                    appliedAttempts += event.attemptId
                }
                is AnswerRevealOutcome -> {
                    val presentationState = presentationProjectionStates.getValue(event.presentationId)
                    if (presentationState.answerRevealSequence != null) {
                        sequenceConflicts += event.outcomeId
                        deferred += pendingIdsAfter(sequence)
                        break
                    }
                    if (!presentationState.memoryProjectionApplied) {
                        memoryStates[event.practiceUnitId] = projectAnswerReveal(
                            memoryStates[event.practiceUnitId],
                            event,
                            effectiveAt,
                        )
                    }
                    revealRecords[event.outcomeId] = AppliedAnswerRevealRecord(
                        outcomeId = event.outcomeId,
                        presentationId = event.presentationId,
                        canonicalFingerprint = fingerprint,
                        eventSequence = event.eventSequence,
                    )
                    presentationProjectionStates[event.presentationId] = presentationState.copy(
                        asOfLedgerSequence = event.eventSequence,
                        memoryProjectionApplied = true,
                        answerRevealSequence = event.eventSequence,
                    )
                    appliedReveals += event.outcomeId
                }
                is TutorAnswerExposureOutcome -> {
                    memoryStates[event.practiceUnitId] = projectTutorAnswerExposure(
                        previous = memoryStates[event.practiceUnitId],
                        outcome = event,
                        effectiveAtEpochMillis = effectiveAt,
                    )
                    tutorExposureRecords[event.outcomeId] = AppliedTutorAnswerExposureRecord(
                        outcomeId = event.outcomeId,
                        exposureId = event.exposureId,
                        canonicalFingerprint = fingerprint,
                        eventSequence = event.eventSequence,
                    )
                    appliedTutorExposures += event.outcomeId
                }
                is AttributedLearningObservationEvent -> {
                    if (event.isProjectionQuarantined) {
                        quarantinedObservations += event.eventId
                    } else {
                        applyLearningObservation(
                            masteryStates = masteryStates,
                            event = event,
                            ambiguousEventIds = ambiguousObservations,
                            effectiveAtEpochMillis = event.occurredAtEpochMillis,
                        )
                        observationRecords[event.eventId] = AppliedLearningObservationRecord(
                            observationEventId = event.eventId,
                            canonicalFingerprint = fingerprint,
                            eventSequence = event.eventSequence,
                        )
                        appliedObservations += event.eventId
                    }
                }
            }
            expectedSequence++
            if (event !is AttributedLearningObservationEvent || !event.isProjectionQuarantined) {
                projectedAt = effectiveAt
            }
        }

        val appliedEventCount = appliedAttempts.size + appliedReveals.size +
            appliedTutorExposures.size + appliedObservations.size + quarantinedObservations.size
        val lastSequence = previous.checkpoint.lastSequence + appliedEventCount
        val projectionStatus = when {
            sequenceConflicts.isNotEmpty() -> ProjectionStatus.CONFLICTED
            missingSequence != null -> ProjectionStatus.WAITING_FOR_GAP
            appliedEventCount == 0 && previous.projectionStatus == ProjectionStatus.WAITING_FOR_GAP ->
                ProjectionStatus.WAITING_FOR_GAP
            lastSequence < knownLedgerHeadSequence -> ProjectionStatus.CATCHING_UP
            else -> ProjectionStatus.CURRENT
        }
        val checkpoint = if (appliedEventCount == 0) {
            previous.checkpoint
        } else {
            ProjectionCheckpoint(lastSequence, VERSION, projectedAt)
        }
        val snapshot = previous.copy(
            problemMemoryStates = memoryStates,
            knowledgeMasteryStates = masteryStates,
            checkpoint = checkpoint,
            knownLedgerHeadSequence = knownLedgerHeadSequence,
            generatedAtEpochMillis = projectedAt,
            freshness = if (projectionStatus == ProjectionStatus.CURRENT) {
                LearnerSnapshotFreshness.CURRENT
            } else {
                LearnerSnapshotFreshness.STALE
            },
            projectionStatus = projectionStatus,
            appliedAttemptRecords = boundedRecords(attemptRecords),
            appliedAnswerRevealRecords = boundedAnswerRevealRecords(revealRecords),
            appliedTutorAnswerExposureRecords = boundedTutorAnswerExposureRecords(tutorExposureRecords),
            appliedLearningObservationRecords = boundedLearningObservationRecords(observationRecords),
        )
        val outputPresentationStates = presentationProjectionStates.mapValues { (_, state) ->
            state.copy(asOfLedgerSequence = checkpoint.lastSequence)
        }
        return LearningProjectionResult(
            snapshot = snapshot,
            appliedAttemptIds = appliedAttempts,
            ignoredAttemptIds = ignored intersect incomingAttemptIds,
            conflictedAttemptIds = sequenceConflicts intersect incomingAttemptIds,
            deferredAttemptIds = deferred intersect incomingAttemptIds,
            missingSequence = missingSequence,
            ambiguousAttemptIds = ambiguous,
            appliedAnswerRevealOutcomeIds = appliedReveals,
            ignoredAnswerRevealOutcomeIds = ignored intersect incomingRevealIds,
            conflictedAnswerRevealOutcomeIds = sequenceConflicts intersect incomingRevealIds,
            deferredAnswerRevealOutcomeIds = deferred intersect incomingRevealIds,
            appliedTutorAnswerExposureOutcomeIds = appliedTutorExposures,
            ignoredTutorAnswerExposureOutcomeIds = ignored intersect incomingTutorExposureIds,
            conflictedTutorAnswerExposureOutcomeIds = sequenceConflicts intersect incomingTutorExposureIds,
            deferredTutorAnswerExposureOutcomeIds = deferred intersect incomingTutorExposureIds,
            appliedLearningObservationEventIds = appliedObservations,
            ignoredLearningObservationEventIds = ignored intersect incomingObservationIds,
            conflictedLearningObservationEventIds = sequenceConflicts intersect incomingObservationIds,
            deferredLearningObservationEventIds = deferred intersect incomingObservationIds,
            ambiguousLearningObservationEventIds = ambiguousObservations,
            presentationProjectionStates = outputPresentationStates,
        )
    }

    /** Corrections require the complete gap-free ledger so their effects can be replayed safely. */
    fun replay(
        learnerId: String,
        ledger: List<LearningLedgerEvent>,
    ): LearningProjectionResult {
        require(learnerId.isNotBlank()) { "Learner id must not be blank" }
        val ordered = ledger.sortedBy(LearningLedgerEvent::eventSequence)
        require(ordered.map(LearningLedgerEvent::eventSequence) == (1L..ordered.size.toLong()).toList()) {
            "Full replay requires a unique, continuous ledger beginning at sequence one"
        }
        require(ordered.map(LearningLedgerEvent::ledgerEventId).distinct().size == ordered.size) {
            "Ledger event ids must be unique"
        }
        require(ordered.filterIsInstance<AttributedLearningObservationEvent>().all {
            it.learnerId == learnerId
        }) { "Learning observation learner must match the replay learner" }

        val attemptsById = linkedMapOf<String, Attempt>()
        val corrections = linkedMapOf<String, AttemptCorrection>()
        val presentationOrdinals = mutableMapOf<String, Int>()
        val answerRevealSequences = mutableMapOf<String, Long>()
        ordered.forEach { event ->
            when (event) {
                is Attempt -> {
                    require(event.attemptId !in attemptsById) { "Attempt ids must be immutable and unique" }
                    val expectedOrdinal = (presentationOrdinals[event.presentationId] ?: 0) + 1
                    require(event.responseOrdinal == expectedOrdinal) {
                        "Presentation responses must use contiguous ordinals beginning at one"
                    }
                    presentationOrdinals[event.presentationId] = event.responseOrdinal
                    attemptsById[event.attemptId] = event
                }
                is AnswerRevealOutcome -> require(
                    answerRevealSequences.put(event.presentationId, event.eventSequence) == null,
                ) { "A presentation may have only one terminal answer-reveal outcome" }
                is TutorAnswerExposureOutcome -> Unit
                is AttributedLearningObservationEvent -> Unit
                is AttemptCorrection -> {
                    require(event.attemptId in attemptsById) {
                        "A correction must follow the attempt it replaces"
                    }
                    corrections[event.attemptId] = event
                }
            }
        }

        val memoryStates = mutableMapOf<String, ProblemMemoryState>()
        val masteryStates = mutableMapOf<String, KnowledgeMasteryState>()
        val attemptRecords = mutableMapOf<String, AppliedAttemptRecord>()
        val revealRecords = mutableMapOf<String, AppliedAnswerRevealRecord>()
        val tutorExposureRecords = mutableMapOf<String, AppliedTutorAnswerExposureRecord>()
        val observationRecords = mutableMapOf<String, AppliedLearningObservationRecord>()
        val correctionRecords = mutableMapOf<String, AppliedCorrectionRecord>()
        val presentationProjectionStates = mutableMapOf<String, PresentationProjectionState>()
        val ambiguous = linkedSetOf<String>()
        val ambiguousObservations = linkedSetOf<String>()
        val masteryTimeline = mutableListOf<IncrementalLearningEvent>()
        val attemptStudyDayTrust = mutableMapOf<String, Boolean>()
        var replayAt = 0L
        var correctionWatermark: Long? = null
        ordered.forEach { event ->
            val effectiveAt = maxOf(replayAt, event.occurredAtEpochMillis)
            when (event) {
                is Attempt -> {
                    val correction = corrections[event.attemptId]
                    val correctedAttempt = if (correction == null) {
                        event
                    } else {
                        event.copy(
                            evidence = correction.replacementEvidence,
                            problemMemoryOutcome = correction.replacementMemoryOutcome,
                        )
                    }
                    val effective = applyRevealCausality(
                        attempt = correctedAttempt,
                        answerRevealSequence = answerRevealSequences[event.presentationId],
                        causalSequence = event.eventSequence,
                    )
                    val presentationState = presentationProjectionStates.getOrPut(event.presentationId) {
                        PresentationProjectionState(
                            event.presentationId,
                            asOfLedgerSequence = 0,
                            memoryProjectionApplied = false,
                        )
                    }
                    applyAttemptMemory(
                        memoryStates = memoryStates,
                        attempt = effective,
                        suppressDuplicateRevealMemory = presentationState.answerRevealSequence != null,
                        effectiveAtEpochMillis = effectiveAt,
                    )
                    masteryTimeline += effective
                    attemptStudyDayTrust[event.attemptId] =
                        event.occurredAtEpochMillis >= effectiveAt
                    presentationProjectionStates[event.presentationId] = presentationState.copy(
                        asOfLedgerSequence = event.eventSequence,
                        memoryProjectionApplied = true,
                    )
                    attemptRecords[event.attemptId] = AppliedAttemptRecord(
                        attemptId = event.attemptId,
                        canonicalFingerprint = LearningLedgerFingerprint.attempt(event),
                        eventSequence = event.eventSequence,
                        presentationId = event.presentationId,
                        responseOrdinal = event.responseOrdinal,
                    )
                }
                is AnswerRevealOutcome -> {
                    val presentationState = presentationProjectionStates.getOrPut(event.presentationId) {
                        PresentationProjectionState(
                            event.presentationId,
                            asOfLedgerSequence = 0,
                            memoryProjectionApplied = false,
                        )
                    }
                    if (!presentationState.memoryProjectionApplied) {
                        memoryStates[event.practiceUnitId] = projectAnswerReveal(
                            memoryStates[event.practiceUnitId],
                            event,
                            effectiveAt,
                        )
                    }
                    revealRecords[event.outcomeId] = AppliedAnswerRevealRecord(
                        outcomeId = event.outcomeId,
                        presentationId = event.presentationId,
                        canonicalFingerprint = LearningLedgerFingerprint.answerReveal(event),
                        eventSequence = event.eventSequence,
                    )
                    presentationProjectionStates[event.presentationId] = presentationState.copy(
                        asOfLedgerSequence = event.eventSequence,
                        memoryProjectionApplied = true,
                        answerRevealSequence = event.eventSequence,
                    )
                }
                is TutorAnswerExposureOutcome -> {
                    memoryStates[event.practiceUnitId] = projectTutorAnswerExposure(
                        previous = memoryStates[event.practiceUnitId],
                        outcome = event,
                        effectiveAtEpochMillis = effectiveAt,
                    )
                    tutorExposureRecords[event.outcomeId] = AppliedTutorAnswerExposureRecord(
                        outcomeId = event.outcomeId,
                        exposureId = event.exposureId,
                        canonicalFingerprint = LearningLedgerFingerprint.tutorAnswerExposure(event),
                        eventSequence = event.eventSequence,
                    )
                }
                is AttributedLearningObservationEvent -> {
                    if (!event.isProjectionQuarantined) {
                        masteryTimeline += event
                        observationRecords[event.eventId] = AppliedLearningObservationRecord(
                            observationEventId = event.eventId,
                            canonicalFingerprint = LearningLedgerFingerprint.learningObservation(event),
                            eventSequence = event.eventSequence,
                        )
                    }
                }
                is AttemptCorrection -> {
                    correctionRecords[event.correctionId] = AppliedCorrectionRecord(
                        correctionId = event.correctionId,
                        attemptId = event.attemptId,
                        canonicalFingerprint = LearningLedgerFingerprint.correction(event),
                        eventSequence = event.eventSequence,
                    )
                    correctionWatermark = maxOf(correctionWatermark ?: 0L, effectiveAt)
                }
            }
            if (event !is AttributedLearningObservationEvent || !event.isProjectionQuarantined) {
                replayAt = effectiveAt
            }
        }
        masteryTimeline
            .sortedWith(
                compareBy<IncrementalLearningEvent>(
                    IncrementalLearningEvent::occurredAtEpochMillis,
                    IncrementalLearningEvent::eventSequence,
                ),
            )
            .forEach { event ->
                when (event) {
                    is Attempt -> applyAttemptMastery(
                        masteryStates = masteryStates,
                        attempt = event,
                        ambiguousAttemptIds = ambiguous,
                        behaviorAtEpochMillis = event.occurredAtEpochMillis,
                        isStudyDayTrusted = attemptStudyDayTrust.getValue(event.attemptId),
                    )
                    is AttributedLearningObservationEvent -> applyLearningObservation(
                        masteryStates = masteryStates,
                        event = event,
                        ambiguousEventIds = ambiguousObservations,
                        effectiveAtEpochMillis = event.occurredAtEpochMillis,
                    )
                    is AnswerRevealOutcome,
                    is TutorAnswerExposureOutcome,
                    -> error("Only mastery evidence belongs in the mastery timeline")
                }
            }

        val projectedAt = replayAt
        val lastSequence = ordered.lastOrNull()?.eventSequence ?: 0
        val snapshot = LearnerSnapshot(
            learnerId = learnerId,
            problemMemoryStates = memoryStates,
            knowledgeMasteryStates = masteryStates,
            checkpoint = ProjectionCheckpoint(lastSequence, VERSION, projectedAt),
            knownLedgerHeadSequence = lastSequence,
            generatedAtEpochMillis = projectedAt,
            correctionWatermarkEpochMillis = correctionWatermark,
            freshness = LearnerSnapshotFreshness.CURRENT,
            projectionStatus = ProjectionStatus.CURRENT,
            appliedAttemptRecords = boundedRecords(attemptRecords),
            appliedCorrectionRecords = boundedCorrectionRecords(correctionRecords),
            appliedAnswerRevealRecords = boundedAnswerRevealRecords(revealRecords),
            appliedTutorAnswerExposureRecords = boundedTutorAnswerExposureRecords(tutorExposureRecords),
            appliedLearningObservationRecords = boundedLearningObservationRecords(observationRecords),
        )
        val outputPresentationStates = presentationProjectionStates.mapValues { (_, state) ->
            state.copy(asOfLedgerSequence = lastSequence)
        }
        return LearningProjectionResult(
            snapshot = snapshot,
            appliedAttemptIds = attemptsById.keys.toSet(),
            ignoredAttemptIds = emptySet(),
            conflictedAttemptIds = emptySet(),
            ambiguousAttemptIds = ambiguous,
            correctedAttemptIds = corrections.keys.toSet(),
            appliedAnswerRevealOutcomeIds = revealRecords.keys.toSet(),
            appliedTutorAnswerExposureOutcomeIds = tutorExposureRecords.keys.toSet(),
            appliedLearningObservationEventIds = observationRecords.keys.toSet(),
            ambiguousLearningObservationEventIds = ambiguousObservations,
            presentationProjectionStates = outputPresentationStates,
        )
    }

    private fun requireCompatibleSnapshot(previous: LearnerSnapshot) {
        require(
            previous.checkpoint.projectorVersion == VERSION ||
                (
                    previous.checkpoint.lastSequence == 0L &&
                        previous.problemMemoryStates.isEmpty() &&
                        previous.knowledgeMasteryStates.isEmpty() &&
                        previous.appliedAttemptRecords.isEmpty() &&
                        previous.appliedCorrectionRecords.isEmpty() &&
                        previous.appliedAnswerRevealRecords.isEmpty() &&
                        previous.appliedTutorAnswerExposureRecords.isEmpty() &&
                        previous.appliedLearningObservationRecords.isEmpty()
                    ),
        ) { "A projector-version change requires replay from an empty snapshot" }
    }

    private fun inferPresentationStates(
        previous: LearnerSnapshot,
        events: List<IncrementalLearningEvent>,
    ): Map<String, PresentationProjectionState> = events
        .mapNotNull { event ->
            when (event) {
                is Attempt -> event.presentationId
                is AnswerRevealOutcome -> event.presentationId
                is TutorAnswerExposureOutcome -> null
                is AttributedLearningObservationEvent -> null
            }
        }
        .distinct()
        .associateWith { presentationId ->
            val revealSequence = previous.appliedAnswerRevealRecords.values
                .filter { it.presentationId == presentationId }
                .maxOfOrNull(AppliedAnswerRevealRecord::eventSequence)
            PresentationProjectionState(
                presentationId = presentationId,
                asOfLedgerSequence = previous.checkpoint.lastSequence,
                memoryProjectionApplied = revealSequence != null || previous.appliedAttemptRecords.values.any {
                    it.presentationId == presentationId
                },
                answerRevealSequence = revealSequence,
            )
        }

    private fun applyRevealCausality(
        attempt: Attempt,
        answerRevealSequence: Long?,
        causalSequence: Long,
    ): Attempt {
        if (answerRevealSequence == null || answerRevealSequence >= causalSequence) return attempt
        return when (attempt.evidence.direction) {
            LearningEvidenceDirection.POSITIVE -> attempt.copy(
                evidence = LearningEvidence(
                    direction = LearningEvidenceDirection.NONE,
                    weight = 0.0,
                    reason = LearningEvidenceReason.ANSWER_REVEALED,
                ),
                problemMemoryOutcome = ProblemMemoryOutcome.ANSWER_REVEALED,
            )
            LearningEvidenceDirection.NEGATIVE -> attempt.copy(
                evidence = LearningEvidence(
                    direction = LearningEvidenceDirection.NEGATIVE,
                    weight = attempt.evidence.weight,
                    reason = LearningEvidenceReason.INCORRECT_AFTER_REVEAL,
                ),
                problemMemoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
            )
            LearningEvidenceDirection.NONE -> attempt
        }
    }

    private fun unchangedConflict(
        previous: LearnerSnapshot,
        ignored: Set<String>,
        conflicts: Set<String>,
        deferred: Set<String>,
        incomingAttemptIds: Set<String>,
        incomingRevealIds: Set<String>,
        incomingTutorExposureIds: Set<String>,
        incomingObservationIds: Set<String>,
        knownLedgerHeadSequence: Long,
        presentationProjectionStates: Map<String, PresentationProjectionState>,
    ): LearningProjectionResult {
        val snapshot = previous.copy(
            freshness = LearnerSnapshotFreshness.STALE,
            projectionStatus = ProjectionStatus.CONFLICTED,
            knownLedgerHeadSequence = knownLedgerHeadSequence,
        )
        return LearningProjectionResult(
            snapshot = snapshot,
            appliedAttemptIds = emptySet(),
            ignoredAttemptIds = ignored intersect incomingAttemptIds,
            conflictedAttemptIds = conflicts intersect incomingAttemptIds,
            deferredAttemptIds = deferred intersect incomingAttemptIds,
            ignoredAnswerRevealOutcomeIds = ignored intersect incomingRevealIds,
            conflictedAnswerRevealOutcomeIds = conflicts intersect incomingRevealIds,
            deferredAnswerRevealOutcomeIds = deferred intersect incomingRevealIds,
            ignoredTutorAnswerExposureOutcomeIds = ignored intersect incomingTutorExposureIds,
            conflictedTutorAnswerExposureOutcomeIds = conflicts intersect incomingTutorExposureIds,
            deferredTutorAnswerExposureOutcomeIds = deferred intersect incomingTutorExposureIds,
            ignoredLearningObservationEventIds = ignored intersect incomingObservationIds,
            conflictedLearningObservationEventIds = conflicts intersect incomingObservationIds,
            deferredLearningObservationEventIds = deferred intersect incomingObservationIds,
            presentationProjectionStates = presentationProjectionStates,
        )
    }

    private fun unchangedForFullReplay(
        previous: LearnerSnapshot,
        ignored: Set<String>,
        deferred: Set<String>,
        incomingAttemptIds: Set<String>,
        incomingRevealIds: Set<String>,
        incomingTutorExposureIds: Set<String>,
        incomingObservationIds: Set<String>,
        presentationProjectionStates: Map<String, PresentationProjectionState>,
    ): LearningProjectionResult = LearningProjectionResult(
        snapshot = previous,
        appliedAttemptIds = emptySet(),
        ignoredAttemptIds = ignored intersect incomingAttemptIds,
        conflictedAttemptIds = emptySet(),
        deferredAttemptIds = deferred intersect incomingAttemptIds,
        ignoredAnswerRevealOutcomeIds = ignored intersect incomingRevealIds,
        deferredAnswerRevealOutcomeIds = deferred intersect incomingRevealIds,
        ignoredTutorAnswerExposureOutcomeIds = ignored intersect incomingTutorExposureIds,
        deferredTutorAnswerExposureOutcomeIds = deferred intersect incomingTutorExposureIds,
        ignoredLearningObservationEventIds = ignored intersect incomingObservationIds,
        deferredLearningObservationEventIds = deferred intersect incomingObservationIds,
        presentationProjectionStates = presentationProjectionStates,
        fullReplayReason = FullReplayReason.SEMANTIC_TIME_ROLLBACK,
    )

    private fun hasSemanticTimeRollback(
        previous: LearnerSnapshot,
        events: List<IncrementalLearningEvent>,
    ): Boolean {
        val latestBehaviorAtByKnowledgeNode = previous.knowledgeMasteryStates
            .mapNotNull { (knowledgeNodeId, state) ->
                state.lastEvidenceAtEpochMillis?.let { knowledgeNodeId to it }
            }
            .toMap()
            .toMutableMap()
        events.sortedBy(IncrementalLearningEvent::eventSequence).forEach { event ->
            val knowledgeNodeIds = when (event) {
                is Attempt -> if (event.evidence.weight > 0.0) {
                    event.assessmentSnapshot.attributions
                        .asSequence()
                        .filter { it.certainty == EvidenceAttributionCertainty.DIRECT }
                        .map(KnowledgeEvidenceAttribution::knowledgeNodeId)
                        .toSet()
                } else {
                    emptySet()
                }
                is AttributedLearningObservationEvent ->
                    if (event.isProjectionQuarantined) {
                        emptySet()
                    } else {
                        event.attributions
                            .asSequence()
                            .filter { it.certainty == EvidenceAttributionCertainty.DIRECT }
                            .map(LearningObservationKnowledgeAttribution::knowledgeNodeId)
                            .toSet()
                    }
                is AnswerRevealOutcome,
                is TutorAnswerExposureOutcome,
                -> emptySet()
            }
            knowledgeNodeIds.forEach { knowledgeNodeId ->
                val latestBehaviorAt = latestBehaviorAtByKnowledgeNode[knowledgeNodeId]
                if (latestBehaviorAt != null && event.occurredAtEpochMillis < latestBehaviorAt) {
                    return true
                }
                latestBehaviorAtByKnowledgeNode[knowledgeNodeId] = event.occurredAtEpochMillis
            }
        }
        return false
    }

    private fun applyAttempt(
        memoryStates: MutableMap<String, ProblemMemoryState>,
        masteryStates: MutableMap<String, KnowledgeMasteryState>,
        attempt: Attempt,
        ambiguousAttemptIds: MutableSet<String>,
        suppressDuplicateRevealMemory: Boolean = false,
        effectiveAtEpochMillis: Long,
    ) {
        applyAttemptMemory(
            memoryStates = memoryStates,
            attempt = attempt,
            suppressDuplicateRevealMemory = suppressDuplicateRevealMemory,
            effectiveAtEpochMillis = effectiveAtEpochMillis,
        )
        applyAttemptMastery(
            masteryStates = masteryStates,
            attempt = attempt,
            ambiguousAttemptIds = ambiguousAttemptIds,
            behaviorAtEpochMillis = attempt.occurredAtEpochMillis,
            isStudyDayTrusted = attempt.occurredAtEpochMillis >= effectiveAtEpochMillis,
        )
    }

    private fun applyAttemptMemory(
        memoryStates: MutableMap<String, ProblemMemoryState>,
        attempt: Attempt,
        suppressDuplicateRevealMemory: Boolean,
        effectiveAtEpochMillis: Long,
    ) {
        if (!suppressDuplicateRevealMemory) {
            memoryStates[attempt.practiceUnitId] = projectMemory(
                previous = memoryStates[attempt.practiceUnitId],
                practiceUnitId = attempt.practiceUnitId,
                eventId = attempt.attemptId,
                occurredAtEpochMillis = attempt.occurredAtEpochMillis,
                effectiveAtEpochMillis = effectiveAtEpochMillis,
                eventSequence = attempt.eventSequence,
                outcome = attempt.problemMemoryOutcome,
                weight = attempt.evidence.weight,
            )
        }
    }

    private fun applyAttemptMastery(
        masteryStates: MutableMap<String, KnowledgeMasteryState>,
        attempt: Attempt,
        ambiguousAttemptIds: MutableSet<String>,
        behaviorAtEpochMillis: Long,
        isStudyDayTrusted: Boolean,
    ) {
        val attributions = attempt.assessmentSnapshot.attributions
        if (attributions.any { it.certainty == EvidenceAttributionCertainty.AMBIGUOUS }) {
            ambiguousAttemptIds += attempt.attemptId
        }
        if (attempt.evidence.weight == 0.0) return
        attributions
            .filter { it.certainty == EvidenceAttributionCertainty.DIRECT }
            .sortedBy(KnowledgeEvidenceAttribution::bindingId)
            .forEach { attribution ->
                val knowledgeNodeId = attribution.knowledgeNodeId
                masteryStates[knowledgeNodeId] = projectMastery(
                    previous = masteryStates[knowledgeNodeId],
                    knowledgeNodeId = knowledgeNodeId,
                    attempt = attempt,
                    attribution = attribution,
                    behaviorAtEpochMillis = behaviorAtEpochMillis,
                    isStudyDayTrusted = isStudyDayTrusted,
                )
            }
    }

    private fun projectAnswerReveal(
        previous: ProblemMemoryState?,
        outcome: AnswerRevealOutcome,
        effectiveAtEpochMillis: Long,
    ): ProblemMemoryState = projectMemory(
        previous = previous,
        practiceUnitId = outcome.practiceUnitId,
        eventId = outcome.outcomeId,
        occurredAtEpochMillis = outcome.occurredAtEpochMillis,
        effectiveAtEpochMillis = effectiveAtEpochMillis,
        eventSequence = outcome.eventSequence,
        outcome = ProblemMemoryOutcome.ANSWER_REVEALED,
        weight = 0.0,
    )

    private fun projectTutorAnswerExposure(
        previous: ProblemMemoryState?,
        outcome: TutorAnswerExposureOutcome,
        effectiveAtEpochMillis: Long,
    ): ProblemMemoryState = projectMemory(
        previous = previous,
        practiceUnitId = outcome.practiceUnitId,
        eventId = outcome.outcomeId,
        occurredAtEpochMillis = outcome.occurredAtEpochMillis,
        effectiveAtEpochMillis = effectiveAtEpochMillis,
        eventSequence = outcome.eventSequence,
        outcome = ProblemMemoryOutcome.ANSWER_REVEALED,
        weight = 0.0,
    )

    private fun applyLearningObservation(
        masteryStates: MutableMap<String, KnowledgeMasteryState>,
        event: AttributedLearningObservationEvent,
        ambiguousEventIds: MutableSet<String>,
        effectiveAtEpochMillis: Long,
    ) {
        if (event.attributions.any {
                it.certainty == EvidenceAttributionCertainty.AMBIGUOUS
            }
        ) {
            ambiguousEventIds += event.eventId
        }
        event.attributions
            .filter { it.certainty == EvidenceAttributionCertainty.DIRECT }
            .sortedBy(LearningObservationKnowledgeAttribution::bindingId)
            .forEach { attribution ->
                val knowledgeNodeId = attribution.knowledgeNodeId
                masteryStates[knowledgeNodeId] = projectObservationMastery(
                    previous = masteryStates[knowledgeNodeId],
                    knowledgeNodeId = knowledgeNodeId,
                    event = event,
                    attribution = attribution,
                    effectiveAtEpochMillis = effectiveAtEpochMillis,
                )
            }
    }

    private fun projectMemory(
        previous: ProblemMemoryState?,
        practiceUnitId: String,
        eventId: String,
        occurredAtEpochMillis: Long,
        effectiveAtEpochMillis: Long,
        eventSequence: Long,
        outcome: ProblemMemoryOutcome,
        weight: Double,
    ): ProblemMemoryState {
        val currentStability = previous?.stabilityDays ?: INITIAL_STABILITY_DAYS
        val currentDifficulty = previous?.difficulty ?: INITIAL_DIFFICULTY
        require(effectiveAtEpochMillis >= occurredAtEpochMillis) {
            "Effective projection time must not precede the raw event time"
        }
        val clockRollback = occurredAtEpochMillis < effectiveAtEpochMillis
        val effectiveAttemptAt = maxOf(previous?.lastReviewedAtEpochMillis ?: 0, effectiveAtEpochMillis)
        val stability = when (outcome) {
            ProblemMemoryOutcome.INDEPENDENT_RECALL ->
                currentStability * (1.0 + 1.6 * weight) + 0.25 * weight
            ProblemMemoryOutcome.ASSISTED_RECALL ->
                currentStability * (1.0 + 0.6 * weight) + 0.1 * weight
            ProblemMemoryOutcome.RETRIEVAL_FAILURE -> currentStability * (0.7 - 0.25 * weight)
            ProblemMemoryOutcome.ANSWER_REVEALED -> currentStability * ANSWER_REVEAL_STABILITY_FACTOR
        }.coerceIn(MIN_STABILITY_DAYS, MAX_STABILITY_DAYS)
        val difficulty = when (outcome) {
            ProblemMemoryOutcome.INDEPENDENT_RECALL -> currentDifficulty - 0.08 * weight
            ProblemMemoryOutcome.ASSISTED_RECALL -> currentDifficulty - 0.03 * weight
            ProblemMemoryOutcome.RETRIEVAL_FAILURE -> currentDifficulty + 0.12 * weight
            ProblemMemoryOutcome.ANSWER_REVEALED -> currentDifficulty + 0.12
        }.coerceIn(0.0, 1.0)
        val shortReview = outcome == ProblemMemoryOutcome.ANSWER_REVEALED || clockRollback
        val nextReviewAt = if (shortReview) {
            safeAdd(effectiveAttemptAt, SHORT_REVIEW_MILLIS)
        } else {
            forgettingCurve.reviewAtTargetRetention(effectiveAttemptAt, stability)
        }
        val isRetrievalFailure = outcome == ProblemMemoryOutcome.RETRIEVAL_FAILURE ||
            outcome == ProblemMemoryOutcome.ANSWER_REVEALED

        return ProblemMemoryState(
            practiceUnitId = practiceUnitId,
            stabilityDays = stability,
            difficulty = difficulty,
            lastReviewedAtEpochMillis = effectiveAttemptAt,
            nextReviewAtEpochMillis = nextReviewAt,
            independentCorrectCount = (previous?.independentCorrectCount ?: 0) +
                if (outcome == ProblemMemoryOutcome.INDEPENDENT_RECALL) 1 else 0,
            assistedCorrectCount = (previous?.assistedCorrectCount ?: 0) +
                if (outcome == ProblemMemoryOutcome.ASSISTED_RECALL) 1 else 0,
            lapseCount = (previous?.lapseCount ?: 0) + if (isRetrievalFailure) 1 else 0,
            answerRevealCount = (previous?.answerRevealCount ?: 0) +
                if (outcome == ProblemMemoryOutcome.ANSWER_REVEALED) 1 else 0,
            lastLapseAtEpochMillis = if (isRetrievalFailure) effectiveAttemptAt else previous?.lastLapseAtEpochMillis,
            clockAnomalyCount = (previous?.clockAnomalyCount ?: 0) + if (clockRollback) 1 else 0,
            lastClockAnomalyAtEpochMillis = if (clockRollback) {
                occurredAtEpochMillis
            } else {
                previous?.lastClockAnomalyAtEpochMillis
            },
            lastAttemptId = eventId,
            projectorVersion = VERSION,
            checkpointSequence = eventSequence,
        )
    }

    private fun projectMastery(
        previous: KnowledgeMasteryState?,
        knowledgeNodeId: String,
        attempt: Attempt,
        attribution: KnowledgeEvidenceAttribution,
        behaviorAtEpochMillis: Long,
        isStudyDayTrusted: Boolean,
    ): KnowledgeMasteryState {
        val probability = previous?.probabilityIndependentCorrect ?: INITIAL_MASTERY_PROBABILITY
        val weight = attempt.evidence.weight * attribution.weight
        val positive = attempt.evidence.signedWeight > 0
        val updatedProbability = if (positive) {
            probability + (1.0 - probability) * POSITIVE_LEARNING_RATE * weight
        } else {
            probability - probability * NEGATIVE_LEARNING_RATE * weight
        }.coerceIn(0.0, 1.0)
        val evidenceMass = (previous?.evidenceMass ?: 0.0) + weight
        val lowerBound = masteryLowerBound(updatedProbability, evidenceMass)
        val observations = previous?.independentCorrectObservations.orEmpty() +
            if (positive && attempt.evidence.isIndependent) {
                listOf(
                    IndependentCorrectObservation(
                        itemFamilyId = attempt.itemFamilyId,
                        studyDayEpochDay = attempt.studyDayEpochDay,
                        occurredAtEpochMillis = behaviorAtEpochMillis,
                        eventSequence = attempt.eventSequence,
                        bindingId = attribution.bindingId,
                        evidenceWeight = weight,
                        calibration = attempt.assessmentSnapshot.calibration,
                        isStudyDayTrusted = isStudyDayTrusted,
                    ),
                )
            } else {
                emptyList()
            }
        val independentError = !positive && attempt.evidence.isIndependent
        val replacesLastError = independentError && isLaterBehavior(
            occurredAtEpochMillis = behaviorAtEpochMillis,
            eventSequence = attempt.eventSequence,
            previousOccurredAtEpochMillis = previous?.lastIndependentErrorAtEpochMillis,
            previousEventSequence = previous?.lastIndependentErrorSequence,
        )
        val lastErrorAt = if (replacesLastError) {
            behaviorAtEpochMillis
        } else {
            previous?.lastIndependentErrorAtEpochMillis
        }
        val lastErrorSequence = if (replacesLastError) {
            attempt.eventSequence
        } else {
            previous?.lastIndependentErrorSequence
        }
        val startsConflict = replacesLastError && previous?.let {
            it.status == MasteryStatus.MASTERED ||
                (it.lowerBoundIndependentCorrect >= ClearlyMasteredForSkipPolicy.LOWER_BOUND &&
                    it.evidenceMass >= ClearlyMasteredForSkipPolicy.EVIDENCE_MASS)
        } == true
        val conflictSince = when {
            startsConflict -> attempt.eventSequence
            previous?.status == MasteryStatus.CONFLICTED && replacesLastError -> attempt.eventSequence
            previous?.status == MasteryStatus.CONFLICTED -> previous.conflictSinceSequence
                ?: previous.lastIndependentErrorSequence
            else -> null
        }
        val supportedRecovery = observations.filter { observation ->
            isLaterBehavior(
                occurredAtEpochMillis = observation.occurredAtEpochMillis,
                eventSequence = observation.eventSequence,
                previousOccurredAtEpochMillis = lastErrorAt,
                previousEventSequence = lastErrorSequence,
            ) &&
                observation.isStudyDayTrusted &&
                observation.calibrationSupportAt(behaviorAtEpochMillis) == CalibrationSupport.SUPPORTED
        }
        val conflictRecovered = conflictSince != null &&
            supportedRecovery.sumOf(IndependentCorrectObservation::evidenceWeight) >=
            ClearlyMasteredForSkipPolicy.EVIDENCE_MASS &&
            ClearlyMasteredForSkipPolicy.hasIndependentBreadth(
                observations = supportedRecovery,
                lastIndependentErrorAtEpochMillis = null,
                lastIndependentErrorSequence = null,
                atEpochMillis = behaviorAtEpochMillis,
            )
        val activeObservations = observations.filter {
            it.calibrationSupportAt(behaviorAtEpochMillis) == CalibrationSupport.SUPPORTED
        }
        val calibration = when {
            activeObservations.isNotEmpty() -> CalibrationSupport.SUPPORTED
            observations.any {
                it.calibrationSupportAt(behaviorAtEpochMillis) == CalibrationSupport.UNSUPPORTED
            } -> CalibrationSupport.UNSUPPORTED
            else -> CalibrationSupport.UNKNOWN
        }
        val status = when {
            conflictSince != null && !conflictRecovered -> MasteryStatus.CONFLICTED
            evidenceMass < 1.0 -> MasteryStatus.UNKNOWN
            clearlyMastered(
                lowerBound,
                evidenceMass,
                activeObservations,
                lastErrorAt,
                lastErrorSequence,
                behaviorAtEpochMillis,
            ) -> MasteryStatus.MASTERED
            else -> MasteryStatus.LEARNING
        }
        return KnowledgeMasteryState(
            knowledgeNodeId = knowledgeNodeId,
            probabilityIndependentCorrect = updatedProbability,
            lowerBoundIndependentCorrect = lowerBound,
            evidenceMass = evidenceMass,
            independentCorrectObservations = observations,
            lastIndependentErrorAtEpochMillis = lastErrorAt,
            lastIndependentErrorSequence = lastErrorSequence,
            status = status,
            calibrationSupport = calibration,
            projectorVersion = VERSION,
            checkpointSequence = maxOf(previous?.checkpointSequence ?: 0, attempt.eventSequence),
            lastEvidenceAtEpochMillis = maxOf(
                previous?.lastEvidenceAtEpochMillis ?: 0,
                behaviorAtEpochMillis,
            ),
            conflictSinceSequence = if (conflictRecovered) null else conflictSince,
        )
    }

    private fun projectObservationMastery(
        previous: KnowledgeMasteryState?,
        knowledgeNodeId: String,
        event: AttributedLearningObservationEvent,
        attribution: LearningObservationKnowledgeAttribution,
        effectiveAtEpochMillis: Long,
    ): KnowledgeMasteryState {
        require(effectiveAtEpochMillis >= event.occurredAtEpochMillis) {
            "Effective projection time must not precede the raw observation time"
        }
        val probability = previous?.probabilityIndependentCorrect ?: INITIAL_MASTERY_PROBABILITY
        val weight = event.evidenceWeight * attribution.weight
        val positive = event.direction == LearningObservationDirection.POSITIVE
        val updatedProbability = if (positive) {
            probability + (1.0 - probability) * POSITIVE_LEARNING_RATE * weight
        } else {
            probability - probability * NEGATIVE_LEARNING_RATE * weight
        }.coerceIn(0.0, 1.0)
        val evidenceMass = (previous?.evidenceMass ?: 0.0) + weight
        val lowerBound = masteryLowerBound(updatedProbability, evidenceMass)
        val observations = previous?.independentCorrectObservations.orEmpty()
        val independentError = !positive &&
            event.independence == LearningObservationIndependence.INDEPENDENT
        val replacesLastError = independentError && isLaterBehavior(
            occurredAtEpochMillis = effectiveAtEpochMillis,
            eventSequence = event.eventSequence,
            previousOccurredAtEpochMillis = previous?.lastIndependentErrorAtEpochMillis,
            previousEventSequence = previous?.lastIndependentErrorSequence,
        )
        val lastErrorAt = if (replacesLastError) {
            effectiveAtEpochMillis
        } else {
            previous?.lastIndependentErrorAtEpochMillis
        }
        val lastErrorSequence = if (replacesLastError) {
            event.eventSequence
        } else {
            previous?.lastIndependentErrorSequence
        }
        val startsConflict = replacesLastError && previous?.let {
            it.status == MasteryStatus.MASTERED ||
                (it.lowerBoundIndependentCorrect >= ClearlyMasteredForSkipPolicy.LOWER_BOUND &&
                    it.evidenceMass >= ClearlyMasteredForSkipPolicy.EVIDENCE_MASS)
        } == true
        val conflictSince = when {
            startsConflict -> event.eventSequence
            previous?.status == MasteryStatus.CONFLICTED && replacesLastError -> event.eventSequence
            previous?.status == MasteryStatus.CONFLICTED -> previous.conflictSinceSequence
                ?: previous.lastIndependentErrorSequence
            else -> null
        }
        val activeObservations = observations.filter {
            it.calibrationSupportAt(effectiveAtEpochMillis) == CalibrationSupport.SUPPORTED
        }
        val calibration = when {
            activeObservations.isNotEmpty() -> CalibrationSupport.SUPPORTED
            observations.any {
                it.calibrationSupportAt(effectiveAtEpochMillis) == CalibrationSupport.UNSUPPORTED
            } -> CalibrationSupport.UNSUPPORTED
            else -> CalibrationSupport.UNKNOWN
        }
        val status = when {
            conflictSince != null -> MasteryStatus.CONFLICTED
            evidenceMass < 1.0 -> MasteryStatus.UNKNOWN
            clearlyMastered(
                lowerBound = lowerBound,
                evidenceMass = evidenceMass,
                observations = activeObservations,
                lastErrorAt = lastErrorAt,
                lastErrorSequence = lastErrorSequence,
                atEpochMillis = effectiveAtEpochMillis,
            ) -> MasteryStatus.MASTERED
            else -> MasteryStatus.LEARNING
        }
        return KnowledgeMasteryState(
            knowledgeNodeId = knowledgeNodeId,
            probabilityIndependentCorrect = updatedProbability,
            lowerBoundIndependentCorrect = lowerBound,
            evidenceMass = evidenceMass,
            independentCorrectObservations = observations,
            lastIndependentErrorAtEpochMillis = lastErrorAt,
            lastIndependentErrorSequence = lastErrorSequence,
            status = status,
            calibrationSupport = calibration,
            projectorVersion = VERSION,
            checkpointSequence = maxOf(previous?.checkpointSequence ?: 0, event.eventSequence),
            lastEvidenceAtEpochMillis = maxOf(
                previous?.lastEvidenceAtEpochMillis ?: 0,
                effectiveAtEpochMillis,
            ),
            conflictSinceSequence = conflictSince,
        )
    }

    private fun isLaterBehavior(
        occurredAtEpochMillis: Long,
        eventSequence: Long,
        previousOccurredAtEpochMillis: Long?,
        previousEventSequence: Long?,
    ): Boolean = when {
        previousOccurredAtEpochMillis == null ->
            previousEventSequence == null || eventSequence > previousEventSequence
        occurredAtEpochMillis != previousOccurredAtEpochMillis ->
            occurredAtEpochMillis > previousOccurredAtEpochMillis
        previousEventSequence == null -> true
        else -> eventSequence > previousEventSequence
    }

    private fun clearlyMastered(
        lowerBound: Double,
        evidenceMass: Double,
        observations: List<IndependentCorrectObservation>,
        lastErrorAt: Long?,
        lastErrorSequence: Long?,
        atEpochMillis: Long,
    ): Boolean = lowerBound >= ClearlyMasteredForSkipPolicy.LOWER_BOUND &&
        evidenceMass >= ClearlyMasteredForSkipPolicy.EVIDENCE_MASS &&
        ClearlyMasteredForSkipPolicy.hasIndependentBreadth(
            observations,
            lastErrorAt,
            lastErrorSequence,
            atEpochMillis,
        )

    private fun masteryLowerBound(probability: Double, evidenceMass: Double): Double {
        val uncertainty = UNCERTAINTY_SCALE *
            sqrt(probability * (1.0 - probability) / (evidenceMass + 1.0))
        return (probability - uncertainty).coerceIn(0.0, probability)
    }

    private fun boundedRecords(records: Map<String, AppliedAttemptRecord>): Map<String, AppliedAttemptRecord> =
        records.values
            .sortedByDescending(AppliedAttemptRecord::eventSequence)
            .take(MAX_APPLIED_RECORDS)
            .sortedBy(AppliedAttemptRecord::eventSequence)
            .associateBy(AppliedAttemptRecord::attemptId)

    private fun boundedCorrectionRecords(
        records: Map<String, AppliedCorrectionRecord>,
    ): Map<String, AppliedCorrectionRecord> = records.values
        .sortedByDescending(AppliedCorrectionRecord::eventSequence)
        .take(MAX_APPLIED_RECORDS)
        .sortedBy(AppliedCorrectionRecord::eventSequence)
        .associateBy(AppliedCorrectionRecord::correctionId)

    private fun boundedAnswerRevealRecords(
        records: Map<String, AppliedAnswerRevealRecord>,
    ): Map<String, AppliedAnswerRevealRecord> = records.values
        .sortedByDescending(AppliedAnswerRevealRecord::eventSequence)
        .take(MAX_APPLIED_RECORDS)
        .sortedBy(AppliedAnswerRevealRecord::eventSequence)
        .associateBy(AppliedAnswerRevealRecord::outcomeId)

    private fun boundedTutorAnswerExposureRecords(
        records: Map<String, AppliedTutorAnswerExposureRecord>,
    ): Map<String, AppliedTutorAnswerExposureRecord> = records.values
        .sortedByDescending(AppliedTutorAnswerExposureRecord::eventSequence)
        .take(MAX_APPLIED_RECORDS)
        .sortedBy(AppliedTutorAnswerExposureRecord::eventSequence)
        .associateBy(AppliedTutorAnswerExposureRecord::outcomeId)

    private fun boundedLearningObservationRecords(
        records: Map<String, AppliedLearningObservationRecord>,
    ): Map<String, AppliedLearningObservationRecord> = records.values
        .sortedByDescending(AppliedLearningObservationRecord::eventSequence)
        .take(MAX_APPLIED_RECORDS)
        .sortedBy(AppliedLearningObservationRecord::eventSequence)
        .associateBy(AppliedLearningObservationRecord::observationEventId)

    private fun safeAdd(value: Long, increment: Long): Long =
        if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment

    companion object {
        const val VERSION = LearningCoreVersions.PROJECTION_COMPOSITE
        private const val INITIAL_STABILITY_DAYS = 0.5
        private const val MIN_STABILITY_DAYS = 0.25
        private const val MAX_STABILITY_DAYS = 3_650.0
        private const val INITIAL_DIFFICULTY = 0.5
        private const val INITIAL_MASTERY_PROBABILITY = 0.5
        private const val POSITIVE_LEARNING_RATE = 0.32
        private const val NEGATIVE_LEARNING_RATE = 0.42
        private const val UNCERTAINTY_SCALE = 1.2
        private const val ANSWER_REVEAL_STABILITY_FACTOR = 0.45
        private const val SHORT_REVIEW_MILLIS = 10 * 60_000L
        private const val MAX_APPLIED_RECORDS = 4_096
    }
}
