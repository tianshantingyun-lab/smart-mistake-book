package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AppliedAttemptRecord
import com.tingyun.smartmistakebook.core.model.AppliedAnswerRevealRecord
import com.tingyun.smartmistakebook.core.model.AppliedCorrectionRecord
import com.tingyun.smartmistakebook.core.model.AppliedTutorAnswerExposureRecord
import com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.EventTimeTrust
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
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.PresentationProjectionState
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome
import com.tingyun.smartmistakebook.core.model.ChatEvidenceSubmitted
import kotlin.math.sqrt

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
    val presentationProjectionStates: Map<String, PresentationProjectionState> = emptyMap(),
    val predictions: List<com.tingyun.smartmistakebook.core.model.StudentModelPrediction> = emptyList(),
)

/** Deterministic projection of a gap-free append-only attempt prefix into learner state. */
class LearningProjector(
    private val forgettingCurve: ForgettingCurve = ForgettingCurve(
        algorithm = ForgettingCurveAlgorithm.FSRS6_POWER_LAW,
    ),
    private val memoryUpdateModel: MemoryUpdateModel = FsrsMemoryUpdateModel(
        desiredRetention = DEFAULT_DESIRED_RETENTION,
    ),
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
        val incomingPresentationIds = events.mapNotNullTo(linkedSetOf()) { event ->
            when (event) {
                is Attempt -> event.presentationId
                is AnswerRevealOutcome -> event.presentationId
                is TutorAnswerExposureOutcome -> null
                is ChatEvidenceSubmitted -> null
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
                is ChatEvidenceSubmitted ->
                    null
            }
            val idExistsAsAnotherType = when (event) {
                is Attempt -> event.attemptId in previous.appliedAnswerRevealRecords ||
                    event.attemptId in previous.appliedTutorAnswerExposureRecords
                is AnswerRevealOutcome -> event.outcomeId in previous.appliedAttemptRecords ||
                    event.outcomeId in previous.appliedTutorAnswerExposureRecords
                is TutorAnswerExposureOutcome -> event.outcomeId in previous.appliedAttemptRecords ||
                    event.outcomeId in previous.appliedAnswerRevealRecords
                is ChatEvidenceSubmitted -> false
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
                knownLedgerHeadSequence = knownLedgerHeadSequence,
                presentationProjectionStates = authoritativePresentationStates,
            )
        }

        val memoryStates = previous.problemMemoryStates.toMutableMap()
        val masteryStates = previous.knowledgeMasteryStates.toMutableMap()
        val attemptRecords = previous.appliedAttemptRecords.toMutableMap()
        val revealRecords = previous.appliedAnswerRevealRecords.toMutableMap()
        val tutorExposureRecords = previous.appliedTutorAnswerExposureRecords.toMutableMap()
        val presentationProjectionStates = authoritativePresentationStates.toMutableMap()
        val appliedAttempts = linkedSetOf<String>()
        val appliedReveals = linkedSetOf<String>()
        val appliedTutorExposures = linkedSetOf<String>()
        val appliedChatEvidences = linkedSetOf<String>()
        val chatEvidenceRecords = mutableMapOf<String, ChatEvidenceSubmitted>()
        val ambiguous = linkedSetOf<String>()
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
                is ChatEvidenceSubmitted -> {
                    masteryStates[event.knowledgeNodeId] = projectChatEvidence(
                        previous = masteryStates[event.knowledgeNodeId],
                        event = event,
                        effectiveAtEpochMillis = effectiveAt,
                    )
                    appliedChatEvidences += event.evidenceId
                }
            }
            expectedSequence++
            projectedAt = effectiveAt
        }

        val appliedEventCount = appliedAttempts.size +
            appliedReveals.size +
            appliedTutorExposures.size +
            appliedChatEvidences.size
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
                is ChatEvidenceSubmitted -> Unit
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
        val correctionRecords = mutableMapOf<String, AppliedCorrectionRecord>()
        val presentationProjectionStates = mutableMapOf<String, PresentationProjectionState>()
        val ambiguous = linkedSetOf<String>()
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
                    applyAttempt(
                        memoryStates,
                        masteryStates,
                        effective,
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
                is ChatEvidenceSubmitted -> {
                    masteryStates[event.knowledgeNodeId] = projectChatEvidence(
                        previous = masteryStates[event.knowledgeNodeId],
                        event = event,
                        effectiveAtEpochMillis = effectiveAt,
                    )
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
            replayAt = effectiveAt
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
        )

        // Generate predictions for audit trail
        val predictions = generatePredictions(snapshot, ordered, projectedAt)

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
            presentationProjectionStates = outputPresentationStates,
            predictions = predictions,
        )
    }

    private fun projectChatEvidence(
        previous: KnowledgeMasteryState?,
        event: ChatEvidenceSubmitted,
        effectiveAtEpochMillis: Long,
    ): KnowledgeMasteryState {
        val probability = previous?.masteryScore ?: INITIAL_MASTERY_PROBABILITY
        val positive = event.direction == LearningEvidenceDirection.POSITIVE
        val weight = event.weight
        val updatedProbability = if (positive) {
            probability + (1.0 - probability) * POSITIVE_LEARNING_RATE * weight
        } else {
            probability - probability * NEGATIVE_LEARNING_RATE * weight
        }.coerceIn(0.0, 1.0)
        val evidenceMass = (previous?.evidenceMass ?: 0.0) + weight
        val lowerBound = masteryLowerBound(updatedProbability, evidenceMass)
        val status = when {
            evidenceMass < 1.0 -> MasteryStatus.UNKNOWN
            clearlyMastered(lowerBound, evidenceMass, emptyList(), null, null, effectiveAtEpochMillis) ->
                MasteryStatus.MASTERED
            else -> MasteryStatus.LEARNING
        }
        return KnowledgeMasteryState(
            knowledgeNodeId = event.knowledgeNodeId,
            masteryScore = updatedProbability,
            conservativeMasteryScore = lowerBound,
            evidenceMass = evidenceMass,
            independentCorrectObservations = previous?.independentCorrectObservations.orEmpty(),
            lastIndependentErrorAtEpochMillis = previous?.lastIndependentErrorAtEpochMillis,
            lastIndependentErrorSequence = previous?.lastIndependentErrorSequence,
            status = status,
            calibrationSupport = previous?.calibrationSupport ?: CalibrationSupport.UNKNOWN,
            projectorVersion = VERSION,
            checkpointSequence = event.eventSequence,
            // 证据时钟（审计 AUDIT-ALGORITHM-2026-09-09 §3.5）：模型判断与知识点
            // 复习也是这个 KC 的真实证据，必须刷新 lastEvidenceAt，否则
            // KnowledgeMasteryState 的该字段退化为"最后一次 attempt 通道作答时间"
            // （默认值只从 independentCorrectObservations 推导）——只走 chat 通道的
            // KC 恒为 null，被 ReviewPlanner 判 stale，永远留在复习队列。
            // 方向同理：kcMasteryDropPressure 只在 lastEvidenceDirection 为 NEGATIVE
            // 时传导，不写它则 spec §5 的 KC→错题联动律对模型通道完全失效。
            lastEvidenceAtEpochMillis = maxOf(
                previous?.lastEvidenceAtEpochMillis ?: 0L,
                effectiveAtEpochMillis,
            ),
            lastEvidenceReason = CHAT_EVIDENCE_REASON,
            lastEvidenceDirection = event.direction.name,
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
                        previous.appliedTutorAnswerExposureRecords.isEmpty()
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
                is TutorAnswerExposureOutcome, is ChatEvidenceSubmitted -> null
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
            presentationProjectionStates = presentationProjectionStates,
        )
    }

    private fun applyAttempt(
        memoryStates: MutableMap<String, ProblemMemoryState>,
        masteryStates: MutableMap<String, KnowledgeMasteryState>,
        attempt: Attempt,
        ambiguousAttemptIds: MutableSet<String>,
        suppressDuplicateRevealMemory: Boolean = false,
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
                eventEpochDay = attempt.studyDay.epochDay,
                eventUtcOffsetMinutes = attempt.studyDay.utcOffsetMinutes,
                outcome = attempt.problemMemoryOutcome,
                weight = attempt.evidence.weight,
                evidenceReason = attempt.evidence.reason,
                evidenceDirection = attempt.evidence.direction,
            )
        }
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
                    effectiveAtEpochMillis = effectiveAtEpochMillis,
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
        eventEpochDay = outcome.studyDay.epochDay,
        eventUtcOffsetMinutes = outcome.studyDay.utcOffsetMinutes,
        outcome = ProblemMemoryOutcome.ANSWER_REVEALED,
        weight = 0.0,
        evidenceReason = LearningEvidenceReason.ANSWER_REVEALED,
        evidenceDirection = LearningEvidenceDirection.NONE,
    )

    private fun projectTutorAnswerExposure(
        previous: ProblemMemoryState?,
        outcome: TutorAnswerExposureOutcome,
        effectiveAtEpochMillis: Long,
    ): ProblemMemoryState {
        // Spec §2.5/§2.6 merge: a tutor answer exposure never decays memory a
        // second time. It only refreshes the clock (spec "S'=S 仅刷新时钟"),
        // so an attempt that follows the exposure is measured from the
        // exposure and lands on the conservative same-day branch.
        //
        // 本路径不传 `lastReviewedEpochDay`：`TutorAnswerExposureOutcome` 不带 studyDay
        // （它没有时区信息），该字段对曝光态只是占位，**不参与任何计算**——后续复习的
        // 本地日由 [projectMemory] 从 `lastReviewedAtEpochMillis` + 该事件的 UTC 偏移现算。
        // 见 `LearningProjectorTest.a review after an answer exposure stays on its own
        // learner-local day`（审计 AUDIT-ALGORITHM §3.7）。
        val refreshedAt = maxOf(previous?.lastReviewedAtEpochMillis ?: 0, effectiveAtEpochMillis)
        val stability = previous?.stabilityDays
            ?: FsrsScheduleMath.initialStability(FsrsRating.AGAIN)
        val difficulty = previous?.difficulty
            ?: FsrsScheduleMath.clampDifficulty(FsrsScheduleMath.initialDifficulty(FsrsRating.AGAIN))
        return ProblemMemoryState(
            practiceUnitId = outcome.practiceUnitId,
            stabilityDays = stability,
            difficulty = difficulty,
            lastReviewedAtEpochMillis = refreshedAt,
            nextReviewAtEpochMillis = forgettingCurve.reviewAtTargetRetention(refreshedAt, stability),
            independentCorrectCount = previous?.independentCorrectCount ?: 0,
            assistedCorrectCount = previous?.assistedCorrectCount ?: 0,
            lapseCount = previous?.lapseCount ?: 0,
            answerRevealCount = (previous?.answerRevealCount ?: 0) + if (previous == null) 1 else 0,
            lastLapseAtEpochMillis = previous?.lastLapseAtEpochMillis,
            clockAnomalyCount = previous?.clockAnomalyCount ?: 0,
            lastClockAnomalyAtEpochMillis = previous?.lastClockAnomalyAtEpochMillis,
            lastAttemptId = outcome.outcomeId,
            projectorVersion = VERSION,
            checkpointSequence = outcome.eventSequence,
            lastEvidenceReason = TUTOR_EXPOSURE_REASON,
            lastEvidenceDirection = LearningEvidenceDirection.NONE.name,
            consecutiveCrossDaySuccess = previous?.consecutiveCrossDaySuccess ?: 0,
            consecutiveCrossDayAgain = previous?.consecutiveCrossDayAgain ?: 0,
        )
    }

    private fun projectMemory(
        previous: ProblemMemoryState?,
        practiceUnitId: String,
        eventId: String,
        occurredAtEpochMillis: Long,
        effectiveAtEpochMillis: Long,
        eventSequence: Long,
        /** Learner-local calendar day (epoch day) of this review event. */
        eventEpochDay: Long,
        /** UTC offset (minutes) of this event's learner-local study day. */
        eventUtcOffsetMinutes: Int,
        outcome: ProblemMemoryOutcome,
        weight: Double,
        evidenceReason: LearningEvidenceReason,
        evidenceDirection: LearningEvidenceDirection,
    ): ProblemMemoryState {
        require(effectiveAtEpochMillis >= occurredAtEpochMillis) {
            "Effective projection time must not precede the raw event time"
        }
        val clockRollback = occurredAtEpochMillis < effectiveAtEpochMillis
        val effectiveAttemptAt = maxOf(previous?.lastReviewedAtEpochMillis ?: 0, effectiveAtEpochMillis)
        val rating = FsrsEvidenceRatingMapper.schedulingRatingFor(evidenceReason, weight)
        // Calendar-day delta (spec §2.1/§2.15): a review crossing the learner-local midnight is a
        // new study day even when it is under 24 wall-clock hours from the previous review.
        //
        // 上一复习的本地日**由它的时间戳现算**，不读 `previous.lastReviewedEpochDay`：那是派生态，
        // 任何一条没有显式写它的通道都会把该字段的默认值（UTC 日序）留在状态里，从而污染
        // 跨日判定——`projectTutorAnswerExposure` 正是如此（审计 AUDIT-ALGORITHM §3.7，
        // `LearningProjectorTest.a review after an answer exposure stays on its own learner-local day`
        // 锁定该失败）。本事件自带 studyDay（含 utcOffsetMinutes），据此换算得到的是确定值：
        // 重放读同一事件，结果逐位相同，不依赖任何写入方是否记得盖章。
        val previousEpochDay = previous?.let {
            localEpochDayOf(it.lastReviewedAtEpochMillis, eventUtcOffsetMinutes)
        } ?: eventEpochDay
        val elapsedCalendarDays = (eventEpochDay - previousEpochDay)
            .toDouble().coerceAtLeast(0.0)
        val update = memoryUpdateModel.updateMemory(
            previous = previous,
            rating = rating,
            outcome = outcome,
            weight = weight,
            occurredAtEpochMillis = occurredAtEpochMillis,
            effectiveAttemptAtEpochMillis = effectiveAttemptAt,
            elapsedCalendarDays = elapsedCalendarDays,
        )
        var stability = update.stabilityDays
        var difficulty = update.difficulty
        val crossDay = previous != null && elapsedCalendarDays >= 1.0
        val nextCrossDaySuccess = when {
            previous == null -> if (rating == FsrsRating.AGAIN) 0 else 1
            crossDay && rating != FsrsRating.AGAIN -> previous.consecutiveCrossDaySuccess + 1
            // Spec §2.10: the streak counts *consecutive* cross-day successes —
            // a cross-day lapse breaks the run. Without this reset the stale
            // pre-lapse count would combine with post-lapse successes into a
            // fake "three in a row" graduation trigger.
            crossDay && rating == FsrsRating.AGAIN -> 0
            else -> previous.consecutiveCrossDaySuccess
        }
        val nextCrossDayAgain = when {
            previous == null -> if (rating == FsrsRating.AGAIN) 1 else 0
            crossDay && rating == FsrsRating.AGAIN -> previous.consecutiveCrossDayAgain + 1
            crossDay && rating != FsrsRating.AGAIN -> 0
            else -> previous.consecutiveCrossDayAgain
        }
        if (previous?.isLeeched == true && difficulty > previous.difficulty) {
            // Spec §2.16: while leeched, difficulty must not climb further.
            difficulty = previous.difficulty
        }
        var nextReviewAt = update.nextReviewAtEpochMillis
        val isRetrievalFailure = outcome == ProblemMemoryOutcome.RETRIEVAL_FAILURE ||
            outcome == ProblemMemoryOutcome.ANSWER_REVEALED
        if (
            memoryUpdateModel is FsrsMemoryUpdateModel &&
            nextCrossDaySuccess >= ProblemMemoryState.GRADUATION_SUCCESS_STREAK &&
            !isRetrievalFailure
        ) {
            // Spec §2.10 graduation: once the streak is long enough and the
            // regular interval reaches ninety days, schedule maintenance at a
            // lower target retention instead.
            val regularIntervalDays = FsrsScheduleMath.intervalDays(
                stability,
                memoryUpdateModel.desiredRetention,
                // 同一个模型的同一个衰减（F-01）：这里只用 `desiredRetention` 而不用它那组参数的
                // 衰减，就会拿一条曲线判"够不够 90 天"、再拿另一条曲线算下一次复习日。
                memoryUpdateModel.decay,
            )
            if (regularIntervalDays >= GRADUATION_MIN_INTERVAL_DAYS) {
                nextReviewAt = forgettingCurve.reviewAtTargetRetention(
                    effectiveAttemptAt,
                    stability,
                    GRADUATION_TARGET_RETENTION,
                )
            }
        }

        return ProblemMemoryState(
            practiceUnitId = practiceUnitId,
            stabilityDays = stability,
            difficulty = difficulty,
            lastReviewedAtEpochMillis = effectiveAttemptAt,
            lastReviewedEpochDay = eventEpochDay,
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
            lastEvidenceReason = evidenceReason.name,
            lastEvidenceDirection = evidenceDirection.name,
            consecutiveCrossDaySuccess = nextCrossDaySuccess,
            consecutiveCrossDayAgain = nextCrossDayAgain,
        )
    }

    private fun projectMastery(
        previous: KnowledgeMasteryState?,
        knowledgeNodeId: String,
        attempt: Attempt,
        attribution: KnowledgeEvidenceAttribution,
        effectiveAtEpochMillis: Long,
    ): KnowledgeMasteryState {
        val probability = previous?.masteryScore ?: INITIAL_MASTERY_PROBABILITY
        // Spec §2.13: every bound KC receives the full evidence record
        // (multi-skill all-record). Binding strength/ordering stays in the
        // attribution snapshot for presentation; it no longer splits evidence.
        val weight = attempt.evidence.weight
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
                        occurredAtEpochMillis = effectiveAtEpochMillis,
                        eventSequence = attempt.eventSequence,
                        bindingId = attribution.bindingId,
                        evidenceWeight = weight,
                        calibration = attempt.assessmentSnapshot.calibration,
                        timeTrust = when {
                            effectiveAtEpochMillis == attempt.occurredAtEpochMillis ->
                                EventTimeTrust.TRUSTED
                            effectiveAtEpochMillis > attempt.occurredAtEpochMillis ->
                                EventTimeTrust.CLOCK_ROLLBACK_CLAMPED
                            else ->
                                EventTimeTrust.FUTURE_TIMESTAMP_CLAMPED
                        },
                    ),
                )
            } else {
                emptyList()
            }
        val independentError = !positive && attempt.evidence.isIndependent
        val lastErrorAt = if (independentError) {
            maxOf(previous?.lastIndependentErrorAtEpochMillis ?: 0, effectiveAtEpochMillis)
        } else {
            previous?.lastIndependentErrorAtEpochMillis
        }
        val lastErrorSequence = if (independentError) {
            maxOf(previous?.lastIndependentErrorSequence ?: 0, attempt.eventSequence)
        } else {
            previous?.lastIndependentErrorSequence
        }
        val startsConflict = independentError && previous?.let {
            it.status == MasteryStatus.MASTERED ||
                (it.conservativeMasteryScore >= ClearlyMasteredForSkipPolicy.LOWER_BOUND &&
                    it.evidenceMass >= ClearlyMasteredForSkipPolicy.EVIDENCE_MASS)
        } == true
        val conflictSince = when {
            startsConflict -> attempt.eventSequence
            previous?.status == MasteryStatus.CONFLICTED && independentError -> attempt.eventSequence
            previous?.status == MasteryStatus.CONFLICTED -> previous.conflictSinceSequence
                ?: previous.lastIndependentErrorSequence
            else -> null
        }
        val supportedRecovery = observations.filter { observation ->
            observation.eventSequence > (conflictSince ?: 0) &&
                observation.isStudyDayTrusted &&
                observation.calibrationSupportAt(effectiveAtEpochMillis) == CalibrationSupport.SUPPORTED
        }
        val conflictRecovered = conflictSince != null &&
            supportedRecovery.sumOf(IndependentCorrectObservation::evidenceWeight) >=
            ClearlyMasteredForSkipPolicy.EVIDENCE_MASS &&
            ClearlyMasteredForSkipPolicy.hasIndependentBreadth(
                observations = supportedRecovery,
                lastIndependentErrorAtEpochMillis = null,
                lastIndependentErrorSequence = null,
                atEpochMillis = effectiveAtEpochMillis,
            )
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
            conflictSince != null && !conflictRecovered -> MasteryStatus.CONFLICTED
            evidenceMass < 1.0 -> MasteryStatus.UNKNOWN
            clearlyMastered(
                lowerBound,
                evidenceMass,
                activeObservations,
                lastErrorAt,
                lastErrorSequence,
                effectiveAtEpochMillis,
            ) -> MasteryStatus.MASTERED
            else -> MasteryStatus.LEARNING
        }
        return KnowledgeMasteryState(
            knowledgeNodeId = knowledgeNodeId,
            masteryScore = updatedProbability,
            conservativeMasteryScore = lowerBound,
            evidenceMass = evidenceMass,
            independentCorrectObservations = observations,
            lastIndependentErrorAtEpochMillis = lastErrorAt,
            lastIndependentErrorSequence = lastErrorSequence,
            status = status,
            calibrationSupport = calibration,
            projectorVersion = VERSION,
            checkpointSequence = attempt.eventSequence,
            lastEvidenceAtEpochMillis = maxOf(
                previous?.lastEvidenceAtEpochMillis ?: 0,
                effectiveAtEpochMillis,
            ),
            conflictSinceSequence = if (conflictRecovered) null else conflictSince,
            lastEvidenceReason = attempt.evidence.reason.name,
            lastEvidenceDirection = attempt.evidence.direction.name,
        )
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

    private fun safeAdd(value: Long, increment: Long): Long =
        if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment

    /**
     * Generate predictions for audit trail. These are shadow predictions that
     * can be compared with actual outcomes later for calibration.
     */
    private fun generatePredictions(
        snapshot: LearnerSnapshot,
        events: List<LearningLedgerEvent>,
        projectedAt: Long,
    ): List<com.tingyun.smartmistakebook.core.model.StudentModelPrediction> {
        val predictions = mutableListOf<com.tingyun.smartmistakebook.core.model.StudentModelPrediction>()
        val modelVersion = com.tingyun.smartmistakebook.core.model.LearningModelVersion(
            modelId = "projection-v1",
            version = VERSION,
            algorithmHash = "mastery-projection-v1",
        )

        // Generate predictions for each knowledge node that was updated
        for (event in events.filterIsInstance<Attempt>()) {
            for (attribution in event.assessmentSnapshot.attributions) {
                val mastery = snapshot.knowledgeMasteryStates[attribution.knowledgeNodeId]
                if (mastery != null) {
                    val prediction = com.tingyun.smartmistakebook.core.model.StudentModelPrediction(
                        predictionId = "pred-${event.attemptId}-${attribution.knowledgeNodeId}",
                        modelVersion = modelVersion,
                        practiceUnitId = event.assessmentSnapshot.practiceUnitId,
                        knowledgeNodeId = attribution.knowledgeNodeId,
                        featureFingerprint = computeFeatureFingerprint(mastery, event),
                        predictedScore = mastery.masteryScore,
                        conservativeScore = mastery.conservativeMasteryScore,
                        predictionWindowStartEpochMillis = projectedAt,
                        predictionWindowEndEpochMillis = projectedAt + 7 * 86_400_000L, // 7-day window
                        predictedAtEpochMillis = projectedAt,
                    )
                    predictions.add(prediction)
                }
            }
        }

        return predictions
    }

    /**
     * Compute a fingerprint of the features used for prediction.
     */
    private fun computeFeatureFingerprint(
        mastery: KnowledgeMasteryState,
        attempt: Attempt,
    ): String {
        return buildString {
            append("mass=${mastery.evidenceMass}")
            append(";prob=${mastery.masteryScore}")
            append(";independent=${mastery.independentCorrectObservations.size}")
            append(";attempt=${attempt.evidence.weight}")
            append(";family=${attempt.itemFamilyId}")
        }
    }

    /**
     * Learner-local calendar day (epoch day) of [epochMillis] under [utcOffsetMinutes].
     *
     * 把"日期"从时间戳现算，而不是持久化一个可由不同写入方按不同口径覆盖的派生字段。
     * 这正是审计 §3.7 的失败类别：`lastReviewedEpochDay` 一旦被某条通道按 UTC 写入，
     * 所有读它的地方都会跟着错，而错误只在那条通道被使用时才显形。这里时间戳与 UTC 偏移
     * 都来自**当前事件自身**（事件溯源的确定性输入），因此重放结果稳定。
     */
    private fun localEpochDayOf(epochMillis: Long, utcOffsetMinutes: Int): Long =
        ReviewCalendar.localEpochDayOf(epochMillis, utcOffsetMinutes)

    companion object {
        const val VERSION = LearningCoreVersions.PROJECTION_COMPOSITE
        private const val INITIAL_MASTERY_PROBABILITY = 0.5
        private const val POSITIVE_LEARNING_RATE = 0.32
        private const val NEGATIVE_LEARNING_RATE = 0.42

        private const val UNCERTAINTY_SCALE = 1.2
        private const val MAX_APPLIED_RECORDS = 4_096
        private const val DAY_MILLIS = 86_400_000L
        private const val GRADUATION_MIN_INTERVAL_DAYS = 90
        const val GRADUATION_TARGET_RETENTION = 0.8
        const val TUTOR_EXPOSURE_REASON = "TUTOR_EXPOSURE"

        /**
         * `last_evidence_reason` 落库值，用于 chat 证据（模型判断 / 知识点复习）。
         *
         * 保持粗粒度是刻意的：精确来源（MODEL_CHAT vs KNOWLEDGE_QUIZ、哪次会话）
         * 由账本行自己的 `source_kind` 与 `conversation_id` 承载，投影层不再复制
         * 一份可能漂移的副本——这里只需要回答"最近一条证据是不是讲题/复习判断"。
         */
        const val CHAT_EVIDENCE_REASON = "CHAT_EVIDENCE"
        const val DEFAULT_DESIRED_RETENTION = FsrsMemoryUpdateModel.DEFAULT_DESIRED_RETENTION
    }
}
