package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

internal enum class MasteryEventDirection {
    POSITIVE,
    NEGATIVE,
}

internal sealed interface LocalMasteryAdmissionDecision {
    data class Admit(
        val direction: MasteryEventDirection,
        val attributedMasses: List<AttributedEvidenceMass>,
    ) : LocalMasteryAdmissionDecision

    data class KeepInert(
        val reason: LearningObservationInertReason,
    ) : LocalMasteryAdmissionDecision
}

internal data class AttributedEvidenceMass(
    val attribution: MasteryCandidateAttributionEntity,
    val evidenceMassMicros: Long,
)

internal data class MasteryRecallProjectionState(
    val currentState: KnowledgeMasteryState,
    val nextTransitionAtEpochMillis: Long?,
)

/**
 * Versioned local conservative heuristic policy. The model supplies semantic labels only; all
 * numeric trust, assistance, confidence and attribution allocation is fixed here. A versioned
 * parameter snapshot guarantees replay, but does not claim empirical calibration.
 */
internal object LocalMasteryPolicy {
    const val FULL_MASS_MICROS = 1_000_000L

    fun evaluate(
        candidate: MasteryObservationCandidateEntity,
        attributions: List<MasteryCandidateAttributionEntity>,
        sourceFact: MasterySourceFactEntity?,
        sourceProof: MasterySourceProofEntity?,
        authorizedBindingFingerprints: Set<String>,
        calibrationSnapshot: MasteryCalibrationSnapshotEntity? = null,
    ): LocalMasteryAdmissionDecision {
        if (sourceFact == null || sourceProof == null) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.MISSING_SOURCE_PROOF,
            )
        }
        if (
            candidate.sourceFactId != sourceFact.sourceFactId ||
            sourceProof.sourceFactId != sourceFact.sourceFactId ||
            sourceProof.sourceFactCanonicalFingerprint != sourceFact.canonicalFingerprint ||
            sourceProof.sourcePolicyVersion != sourceFact.sourcePolicyVersion ||
            sourceProof.proofFingerprint != LearnerMasteryFingerprint.sourceProof(sourceFact)
        ) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.MISSING_SOURCE_PROOF,
            )
        }
        if (
            !sourceProof.policySupported ||
            sourceFact.sourcePolicyVersion != LEARNER_MASTERY_SOURCE_POLICY_VERSION
        ) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.UNSUPPORTED_SOURCE_POLICY,
            )
        }
        if (
            sourceFact.presentationId.isBlank() ||
            !sourceFact.presentationFingerprint.isCanonicalMasteryFingerprint()
        ) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.MISSING_LEARNING_EPISODE,
            )
        }
        if (
            candidate.requestedPolicyVersion !in
            setOf(
                LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION,
                LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
            )
        ) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.UNSUPPORTED_PROJECTION_POLICY,
            )
        }
        if (candidate.learnerId != sourceFact.learnerId) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.LEARNER_MISMATCH,
            )
        }
        if (candidate.subject != sourceFact.subject) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.SUBJECT_MISMATCH,
            )
        }
        if (candidate.confidence == MasteryCandidateConfidence.LOW.name) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.LOW_CONFIDENCE,
            )
        }
        if (sourceFact.authority == MasteryEvidenceAuthority.SELF_REPORTED.name) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.UNVERIFIABLE_SOURCE,
            )
        }
        if (
            sourceFact.responseForm == TrustedLearningResponseForm.FREE_RESPONSE.name &&
            (
                sourceFact.authority == MasteryEvidenceAuthority.MODEL_REVIEWED.name ||
                    sourceFact.verificationKind ==
                    TrustedLearningVerification.MODEL_REVIEWED.name
                )
        ) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.MODEL_ONLY_OPEN_RESPONSE,
            )
        }
        if (
            sourceFact.answerRevealed ||
            sourceFact.assistance == ObservedAssistance.ANSWER_REVEALED.name
        ) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.ANSWER_ALREADY_REVEALED,
            )
        }
        if (sourceFact.outcome == ObservedLearningOutcome.VIEWED_ONLY.name) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.NO_LEARNING_OUTCOME,
            )
        }
        if (
            attributions.isEmpty() ||
            attributions.none {
                it.role == MasteryAttributionRole.PRIMARY.name &&
                    it.certainty == MasteryAttributionCertainty.DIRECT.name
            }
        ) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.NO_ATTRIBUTION,
            )
        }
        if (attributions.any { it.subject != candidate.subject }) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.SUBJECT_MISMATCH,
            )
        }
        when (
            enumValueOf<MasteryEvidenceContextKind>(sourceFact.evidenceContextKind)
        ) {
            MasteryEvidenceContextKind.SAVED_MISTAKE -> {
                val problemRevisionFingerprint = sourceFact.problemRevisionRefFingerprint
                if (
                    problemRevisionFingerprint == null ||
                    attributions.any { it.problemBindingRefFingerprint == null }
                ) {
                    return LocalMasteryAdmissionDecision.KeepInert(
                        LearningObservationInertReason.PROBLEM_BINDING_REQUIRED,
                    )
                }
                if (
                    attributions.any { attribution ->
                        attribution.bindingProblemRevisionRefFingerprint !=
                            problemRevisionFingerprint
                    }
                ) {
                    return LocalMasteryAdmissionDecision.KeepInert(
                        LearningObservationInertReason.PROBLEM_BINDING_MISMATCH,
                    )
                }
                val proposedBindingsFingerprint =
                    fingerprintAuthorizedProblemBindings(
                        attributions.mapNotNull(
                            MasteryCandidateAttributionEntity::problemBindingRefFingerprint,
                        ),
                    )
                // A null source fingerprint means attribution was intentionally deferred. The
                // current student-store binding authority below remains mandatory, so a model can
                // propose a subset but cannot invent a knowledge binding.
                if (
                    sourceFact.authorizedProblemBindingsFingerprint?.let { authorized ->
                        authorized != proposedBindingsFingerprint
                    } == true
                ) {
                    return LocalMasteryAdmissionDecision.KeepInert(
                        LearningObservationInertReason.PROBLEM_BINDING_NOT_AUTHORIZED,
                    )
                }
                if (
                    attributions.any { attribution ->
                        attribution.problemBindingRefFingerprint !in
                            authorizedBindingFingerprints
                    }
                ) {
                    return LocalMasteryAdmissionDecision.KeepInert(
                        LearningObservationInertReason.PROBLEM_BINDING_NOT_AUTHORIZED,
                    )
                }
            }
            MasteryEvidenceContextKind.EPHEMERAL_TUTOR_PROBLEM -> {
                val authorizedFingerprint =
                    sourceFact.authorizedKnowledgeRefsFingerprint
                val proposedFingerprint =
                    fingerprintAuthorizedKnowledgeRefs(
                        attributions.map(
                            MasteryCandidateAttributionEntity::knowledgeNodeRefFingerprint,
                        ),
                    )
                if (
                    attributions.any { it.problemBindingRefFingerprint != null } ||
                    authorizedFingerprint == null ||
                    authorizedFingerprint != proposedFingerprint
                ) {
                    return LocalMasteryAdmissionDecision.KeepInert(
                        LearningObservationInertReason.VERIFIED_KNOWLEDGE_REQUIRED,
                    )
                }
            }
        }

        val direction =
            if (sourceFact.outcome == ObservedLearningOutcome.CORRECT.name) {
                MasteryEventDirection.POSITIVE
            } else {
                MasteryEventDirection.NEGATIVE
            }
        val evidenceAttributions =
            attributions.filter { attribution ->
                attribution.role == MasteryAttributionRole.PRIMARY.name &&
                    attribution.certainty == MasteryAttributionCertainty.DIRECT.name
            }
        if (
            direction == MasteryEventDirection.NEGATIVE &&
            evidenceAttributions.size != 1
        ) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.UNLOCALIZED_MULTI_KNOWLEDGE_NEGATIVE,
            )
        }
        val baseMass = fixedBaseMass(sourceFact, candidate, calibrationSnapshot)
        if (baseMass <= 0L) {
            return LocalMasteryAdmissionDecision.KeepInert(
                LearningObservationInertReason.UNVERIFIABLE_SOURCE,
            )
        }
        val units = evidenceAttributions.map(::attributionUnits)
        val totalUnits = units.sum()
        val attributedMasses =
            evidenceAttributions.mapIndexed { index, attribution ->
                AttributedEvidenceMass(
                    attribution = attribution,
                    evidenceMassMicros =
                        if (index == evidenceAttributions.lastIndex) {
                            baseMass -
                                (0 until evidenceAttributions.lastIndex).sumOf { priorIndex ->
                                    baseMass * units[priorIndex] / totalUnits
                                }
                        } else {
                            baseMass * units[index] / totalUnits
                        },
                )
            }
        return LocalMasteryAdmissionDecision.Admit(
            direction = direction,
            attributedMasses = attributedMasses,
        )
    }

    /**
     * Local-only weighting for a host-attested open response.
     *
     * The caller has already selected qualitative supported/gap references from the immutable
     * authorized scope. The model cannot call this method and supplies neither direction nor any
     * numeric value. Assistance, retry and open-response caps all come from the immutable local
     * source fact and the bound calibration snapshot.
     */
    fun evaluateOpenResponseWeakEvidence(
        candidate: MasteryObservationCandidateEntity,
        sourceFact: MasterySourceFactEntity,
        direction: MasteryEventDirection,
        attributions: List<MasteryCandidateAttributionEntity>,
        calibrationSnapshot: MasteryCalibrationSnapshotEntity,
    ): LocalMasteryAdmissionDecision.Admit? {
        if (
            sourceFact.answerRevealed ||
            attributions.isEmpty() ||
            attributions.any {
                it.subject != candidate.subject ||
                    it.role != MasteryAttributionRole.PRIMARY.name ||
                    it.certainty != MasteryAttributionCertainty.DIRECT.name
            } ||
            (direction == MasteryEventDirection.NEGATIVE && attributions.size != 1)
        ) {
            return null
        }
        val parameters = calibrationParametersFor(candidate, calibrationSnapshot)
            ?: return null
        val hintScale =
            when (sourceFact.hintCount) {
                0 -> FULL_MASS_MICROS
                1 -> parameters.oneHintScaleMicros
                else -> parameters.multipleHintsScaleMicros
            }
        val retryScale =
            when (enumValueOf<ObservedRetryState>(sourceFact.retryState)) {
                ObservedRetryState.FIRST_ATTEMPT -> FULL_MASS_MICROS
                ObservedRetryState.ONE_RETRY -> parameters.oneRetryScaleMicros
                ObservedRetryState.MULTIPLE_RETRIES -> parameters.multipleRetriesScaleMicros
            }
        val baseMass =
            min(
                multiplyMicros(
                    multiplyMicros(parameters.modelReviewedMassMicros, hintScale),
                    retryScale,
                ),
                min(parameters.modelAttributionCapMicros, parameters.openResponseCapMicros),
            )
        if (baseMass <= 0L) return null
        val attributedMasses =
            attributions.mapIndexed { index, attribution ->
                AttributedEvidenceMass(
                    attribution = attribution,
                    evidenceMassMicros =
                        if (index == attributions.lastIndex) {
                            baseMass - baseMass / attributions.size * index
                        } else {
                            baseMass / attributions.size
                        },
                )
            }
        return LocalMasteryAdmissionDecision.Admit(direction, attributedMasses)
    }

    fun nextProjection(
        learnerId: String,
        event: MasteryLearningEventEntity,
        attribution: MasteryLearningEventAttributionEntity,
        current: MasteryKnowledgeProjectionEntity?,
    ): MasteryKnowledgeProjectionEntity =
        when (event.projectionPolicyVersion) {
            LEARNER_MASTERY_LEGACY_PROJECTION_POLICY_VERSION,
            LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION ->
                nextProjectionV1Compatible(learnerId, event, attribution, current)
            LEARNER_MASTERY_PROJECTION_POLICY_VERSION ->
                nextProjectionV3(learnerId, event, attribution, current)
            else -> error(
                "Unsupported learner-mastery projection policy: " +
                    event.projectionPolicyVersion,
            )
        }

    /**
     * Frozen, explicitly uncalibrated heuristics for projection versions 1 and 2.
     *
     * These events predate immutable parameter snapshots. Keeping the dispatch separate makes
     * them replayable without pretending their constants were empirically calibrated.
     */
    private fun nextProjectionV1Compatible(
        learnerId: String,
        event: MasteryLearningEventEntity,
        attribution: MasteryLearningEventAttributionEntity,
        current: MasteryKnowledgeProjectionEntity?,
    ): MasteryKnowledgeProjectionEntity {
        val oldPositive = current?.positiveEvidenceMicros ?: 0L
        val oldNegative = current?.negativeEvidenceMicros ?: 0L
        val positive =
            if (event.direction == MasteryEventDirection.POSITIVE.name) {
                Math.addExact(oldPositive, attribution.evidenceMassMicros)
            } else {
                oldPositive
            }
        val negative =
            if (event.direction == MasteryEventDirection.NEGATIVE.name) {
                Math.addExact(oldNegative, attribution.evidenceMassMicros)
            } else {
                oldNegative
            }
        val oldScore = current?.masteryScoreMicros ?: V1_HALF_MASS_MICROS
        val score =
            (
                (positive + V1_PRIOR_MASS_MICROS).toDouble() /
                    (positive + negative + 2L * V1_PRIOR_MASS_MICROS).toDouble() *
                    FULL_MASS_MICROS.toDouble()
                ).roundToLong()
                .coerceIn(0L, FULL_MASS_MICROS)
        val count = Math.addExact(current?.observationCount ?: 0L, 1L)
        val state = masteryState(score, positive, negative, count)
        val trend =
            when {
                score - oldScore >= V1_MATERIAL_SCORE_DELTA_MICROS ->
                    KnowledgeMasteryTrend.IMPROVING
                oldScore - score >= V1_MATERIAL_SCORE_DELTA_MICROS ->
                    KnowledgeMasteryTrend.WAVERING
                else -> KnowledgeMasteryTrend.STABLE
            }
        val oldStability = current?.memoryStabilityMillis ?: V1_DAY_MILLIS
        val stability =
            if (event.direction == MasteryEventDirection.POSITIVE.name) {
                min(
                    V1_MAX_STABILITY_MILLIS,
                    max(
                        V1_DAY_MILLIS,
                        oldStability +
                            V1_DAY_MILLIS * attribution.evidenceMassMicros /
                                FULL_MASS_MICROS,
                    ),
                )
            } else {
                max(V1_MIN_STABILITY_MILLIS, oldStability / 2L)
            }
        val recallDueAt = saturatingAdd(event.occurredAtEpochMillis, stability)
        return MasteryKnowledgeProjectionEntity(
            learnerId = learnerId,
            subject = attribution.subject,
            knowledgeNodeId = attribution.knowledgeNodeId,
            taxonomyVersion = attribution.taxonomyVersion,
            latestEvidenceKnowledgePackVersion = attribution.knowledgePackVersion,
            stableNodeIdentityFingerprint =
                MasteryProjectionIdentity.fingerprint(
                    subject = attribution.subject,
                    knowledgeNodeId = attribution.knowledgeNodeId,
                    taxonomyVersion = attribution.taxonomyVersion,
                ),
            positiveEvidenceMicros = positive,
            negativeEvidenceMicros = negative,
            masteryScoreMicros = score,
            masteryState = state.name,
            trend = trend.name,
            observationCount = count,
            memoryStabilityMillis = stability,
            recallDueAtEpochMillis = recallDueAt,
            lastPositiveAtEpochMillis =
                if (event.direction == MasteryEventDirection.POSITIVE.name) {
                    event.occurredAtEpochMillis
                } else {
                    current?.lastPositiveAtEpochMillis
                },
            lastNegativeAtEpochMillis =
                if (event.direction == MasteryEventDirection.NEGATIVE.name) {
                    event.occurredAtEpochMillis
                } else {
                    current?.lastNegativeAtEpochMillis
                },
            lastEvidenceAtEpochMillis = event.occurredAtEpochMillis,
            lastEventSequence = event.eventSequence,
            lastOrderedEventId = event.eventId,
            projectionPolicyVersion = event.projectionPolicyVersion,
        )
    }

    /**
     * Fixed-point log-odds update for new events.
     *
     * No floating-point operation participates in replay. The bounded rational sigmoid is a
     * deterministic monotonic approximation of the logistic function and its exact parameters are
     * covered by an immutable, versioned conservative heuristic parameter snapshot.
     */
    private fun nextProjectionV3(
        learnerId: String,
        event: MasteryLearningEventEntity,
        attribution: MasteryLearningEventAttributionEntity,
        current: MasteryKnowledgeProjectionEntity?,
    ): MasteryKnowledgeProjectionEntity {
        val snapshot =
            LocalMasteryCalibrationRegistry.resolve(
                subject = event.subject,
                calibrationVersion = event.calibrationVersion,
                profileId =
                    checkNotNull(event.calibrationProfileId) {
                        "Versioned heuristic learning event has no parameter profile"
                    },
                snapshotFingerprint =
                    checkNotNull(event.calibrationSnapshotFingerprint) {
                        "Versioned heuristic learning event has no parameter snapshot fingerprint"
                    },
            )
        check(event.calibrationProfileId == snapshot.profileId) {
            "Learning event parameter profile differs from its subject snapshot"
        }
        if (current != null && current.projectionPolicyVersion == event.projectionPolicyVersion) {
            val currentSnapshot =
                LocalMasteryCalibrationRegistry.resolve(
                    subject = current.subject,
                    calibrationVersion =
                        checkNotNull(current.calibrationVersion) {
                            "Versioned heuristic mastery projection has no parameter version"
                        },
                    profileId =
                        checkNotNull(current.calibrationProfileId) {
                            "Versioned heuristic mastery projection has no parameter profile"
                        },
                    snapshotFingerprint =
                        checkNotNull(current.calibrationSnapshotFingerprint) {
                            "Versioned heuristic mastery projection has no snapshot fingerprint"
                        },
                )
            check(current.calibrationProfileId == currentSnapshot.profileId)
        }
        val parameters = LocalMasteryCalibrationRegistry.parameters(snapshot)
        val oldPositive = current?.positiveEvidenceMicros ?: 0L
        val oldNegative = current?.negativeEvidenceMicros ?: 0L
        val positive =
            if (event.direction == MasteryEventDirection.POSITIVE.name) {
                Math.addExact(oldPositive, attribution.evidenceMassMicros)
            } else {
                oldPositive
            }
        val negative =
            if (event.direction == MasteryEventDirection.NEGATIVE.name) {
                Math.addExact(oldNegative, attribution.evidenceMassMicros)
            } else {
                oldNegative
            }
        val oldLogOdds =
            current?.historicalLogOddsMicros
                ?: current?.let { projection ->
                    fixedProbabilityToLogOddsMicros(
                        probabilityMicros = projection.masteryScoreMicros,
                        maximumAbsoluteLogOddsMicros =
                            parameters.maximumAbsoluteLogOddsMicros,
                    )
                }
                ?: parameters.priorLogOddsMicros
        val magnitude =
            multiplyMicros(
                if (event.direction == MasteryEventDirection.POSITIVE.name) {
                    parameters.positiveLogLikelihoodMicros
                } else {
                    parameters.negativeLogLikelihoodMicros
                },
                attribution.evidenceMassMicros,
            )
        val logOdds =
            (
                if (event.direction == MasteryEventDirection.POSITIVE.name) {
                    saturatingAddSigned(oldLogOdds, magnitude)
                } else {
                    saturatingAddSigned(oldLogOdds, -magnitude)
                }
                ).coerceIn(
                    -parameters.maximumAbsoluteLogOddsMicros,
                    parameters.maximumAbsoluteLogOddsMicros,
                )
        val oldScore =
            current?.masteryScoreMicros
                ?: fixedLogOddsToProbabilityMicros(
                    parameters.priorLogOddsMicros,
                    parameters.maximumAbsoluteLogOddsMicros,
                )
        val score =
            fixedLogOddsToProbabilityMicros(
                logOdds,
                parameters.maximumAbsoluteLogOddsMicros,
            )
        val count = Math.addExact(current?.observationCount ?: 0L, 1L)
        val state =
            masteryState(
                scoreMicros = score,
                positiveMicros = positive,
                negativeMicros = negative,
                observationCount = count,
                steadyThresholdMicros = parameters.steadyThresholdMicros,
                reinforcementThresholdMicros = parameters.reinforcementThresholdMicros,
                steadyMinimumObservationCount =
                    parameters.steadyMinimumObservationCount,
            )
        val trend =
            when {
                score - oldScore >= parameters.materialScoreDeltaMicros ->
                    KnowledgeMasteryTrend.IMPROVING
                oldScore - score >= parameters.materialScoreDeltaMicros ->
                    KnowledgeMasteryTrend.WAVERING
                else -> KnowledgeMasteryTrend.STABLE
            }
        val oldStability =
            current?.memoryStabilityMillis ?: parameters.initialStabilityMillis
        val stability =
            if (event.direction == MasteryEventDirection.POSITIVE.name) {
                min(
                    parameters.maximumStabilityMillis,
                    max(
                        parameters.initialStabilityMillis,
                        saturatingAdd(
                            oldStability,
                            parameters.positiveStabilityGainMillis *
                                attribution.evidenceMassMicros /
                                FULL_MASS_MICROS,
                        ),
                    ),
                )
            } else {
                max(
                    parameters.minimumStabilityMillis,
                    multiplyMicros(
                        oldStability,
                        parameters.negativeStabilityScaleMicros,
                    ),
                )
            }
        val recallDueAt = saturatingAdd(event.occurredAtEpochMillis, stability)
        val halfLife =
            max(
                1L,
                multiplyMicros(stability, parameters.recallHalfLifeScaleMicros),
            )
        val familiarizingAt =
            if (state == KnowledgeMasteryState.STEADY) {
                firstRecallScoreBelowAt(
                    recallDueAtEpochMillis = recallDueAt,
                    historicalScoreMicros = score,
                    halfLifeMillis = halfLife,
                    thresholdMicros = parameters.steadyThresholdMicros,
                )
            } else {
                null
            }
        val reinforcementAt =
            if (state == KnowledgeMasteryState.NEEDS_REINFORCEMENT) {
                null
            } else {
                firstRecallScoreBelowAt(
                    recallDueAtEpochMillis = recallDueAt,
                    historicalScoreMicros = score,
                    halfLifeMillis = halfLife,
                    thresholdMicros = parameters.reinforcementThresholdMicros,
                )
            }
        return MasteryKnowledgeProjectionEntity(
            learnerId = learnerId,
            subject = attribution.subject,
            knowledgeNodeId = attribution.knowledgeNodeId,
            taxonomyVersion = attribution.taxonomyVersion,
            latestEvidenceKnowledgePackVersion = attribution.knowledgePackVersion,
            stableNodeIdentityFingerprint =
                MasteryProjectionIdentity.fingerprint(
                    subject = attribution.subject,
                    knowledgeNodeId = attribution.knowledgeNodeId,
                    taxonomyVersion = attribution.taxonomyVersion,
                ),
            positiveEvidenceMicros = positive,
            negativeEvidenceMicros = negative,
            masteryScoreMicros = score,
            masteryState = state.name,
            trend = trend.name,
            observationCount = count,
            memoryStabilityMillis = stability,
            recallDueAtEpochMillis = recallDueAt,
            lastPositiveAtEpochMillis =
                if (event.direction == MasteryEventDirection.POSITIVE.name) {
                    event.occurredAtEpochMillis
                } else {
                    current?.lastPositiveAtEpochMillis
                },
            lastNegativeAtEpochMillis =
                if (event.direction == MasteryEventDirection.NEGATIVE.name) {
                    event.occurredAtEpochMillis
                } else {
                    current?.lastNegativeAtEpochMillis
                },
            lastEvidenceAtEpochMillis = event.occurredAtEpochMillis,
            lastEventSequence = event.eventSequence,
            lastOrderedEventId = event.eventId,
            projectionPolicyVersion = event.projectionPolicyVersion,
            historicalLogOddsMicros = logOdds,
            calibrationSnapshotFingerprint = snapshot.snapshotFingerprint,
            calibrationProfileId = snapshot.profileId,
            calibrationVersion = snapshot.calibrationVersion,
            recallFamiliarizingAtEpochMillis = familiarizingAt,
            recallReinforcementAtEpochMillis = reinforcementAt,
        )
    }

    fun currentRecallState(
        projection: MasteryKnowledgeProjectionEntity,
        nowEpochMillis: Long,
    ): KnowledgeMasteryState =
        recallProjectionState(projection, nowEpochMillis).currentState

    internal fun recallProjectionState(
        projection: MasteryKnowledgeProjectionEntity,
        nowEpochMillis: Long,
    ): MasteryRecallProjectionState =
        when (projection.projectionPolicyVersion) {
            LEARNER_MASTERY_LEGACY_PROJECTION_POLICY_VERSION,
            LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION ->
                recallProjectionStateV1Compatible(projection, nowEpochMillis)
            LEARNER_MASTERY_PROJECTION_POLICY_VERSION ->
                recallProjectionStateV3(projection, nowEpochMillis)
            else -> error(
                "Unsupported learner-mastery recall policy: " +
                    projection.projectionPolicyVersion,
            )
        }

    private fun recallProjectionStateV1Compatible(
        projection: MasteryKnowledgeProjectionEntity,
        nowEpochMillis: Long,
    ): MasteryRecallProjectionState {
        val historical = enumValueOf<KnowledgeMasteryState>(projection.masteryState)
        if (
            historical == KnowledgeMasteryState.NEEDS_REINFORCEMENT ||
            nowEpochMillis <= projection.recallDueAtEpochMillis
        ) {
            val next =
                when (historical) {
                    KnowledgeMasteryState.NEEDS_REINFORCEMENT -> null
                    KnowledgeMasteryState.FAMILIARIZING ->
                        saturatingAdd(projection.recallDueAtEpochMillis, 1L)
                    KnowledgeMasteryState.STEADY ->
                        saturatingAdd(projection.recallDueAtEpochMillis, 1L)
                }
            return MasteryRecallProjectionState(historical, next)
        }
        val overdue = nowEpochMillis - projection.recallDueAtEpochMillis
        val current =
            when (historical) {
            KnowledgeMasteryState.STEADY ->
                if (overdue > projection.memoryStabilityMillis) {
                    KnowledgeMasteryState.NEEDS_REINFORCEMENT
                } else {
                    KnowledgeMasteryState.FAMILIARIZING
                }
            KnowledgeMasteryState.FAMILIARIZING ->
                KnowledgeMasteryState.NEEDS_REINFORCEMENT
            KnowledgeMasteryState.NEEDS_REINFORCEMENT ->
                KnowledgeMasteryState.NEEDS_REINFORCEMENT
        }
        val next =
            if (current == KnowledgeMasteryState.FAMILIARIZING) {
                saturatingAdd(
                    saturatingAdd(
                        projection.recallDueAtEpochMillis,
                        projection.memoryStabilityMillis,
                    ),
                    1L,
                )
            } else {
                null
            }
        return MasteryRecallProjectionState(current, next)
    }

    private fun recallProjectionStateV3(
        projection: MasteryKnowledgeProjectionEntity,
        nowEpochMillis: Long,
    ): MasteryRecallProjectionState {
        val snapshot =
            LocalMasteryCalibrationRegistry.resolve(
                subject = projection.subject,
                calibrationVersion =
                    checkNotNull(projection.calibrationVersion) {
                        "Versioned heuristic mastery projection has no parameter version"
                    },
                profileId =
                    checkNotNull(projection.calibrationProfileId) {
                        "Versioned heuristic mastery projection has no parameter profile"
                    },
                snapshotFingerprint =
                    checkNotNull(projection.calibrationSnapshotFingerprint) {
                        "Versioned heuristic mastery projection has no snapshot fingerprint"
                    },
            )
        check(projection.calibrationProfileId == snapshot.profileId)
        val historical = enumValueOf<KnowledgeMasteryState>(projection.masteryState)
        if (
            historical == KnowledgeMasteryState.NEEDS_REINFORCEMENT ||
            nowEpochMillis <= projection.recallDueAtEpochMillis
        ) {
            return MasteryRecallProjectionState(
                currentState = historical,
                nextTransitionAtEpochMillis =
                    when (historical) {
                        KnowledgeMasteryState.STEADY ->
                            projection.recallFamiliarizingAtEpochMillis
                        KnowledgeMasteryState.FAMILIARIZING ->
                            projection.recallReinforcementAtEpochMillis
                        KnowledgeMasteryState.NEEDS_REINFORCEMENT -> null
                    },
            )
        }
        val parameters = LocalMasteryCalibrationRegistry.parameters(snapshot)
        val overdue = nowEpochMillis - projection.recallDueAtEpochMillis
        val halfLife =
            max(
                1L,
                multiplyMicros(
                    projection.memoryStabilityMillis,
                    parameters.recallHalfLifeScaleMicros,
                ),
            )
        val denominator = saturatingAdd(halfLife, overdue)
        val recallScore =
            if (projection.masteryScoreMicros == 0L) {
                0L
            } else {
                projection.masteryScoreMicros * halfLife / denominator
            }
        val current =
            when {
            recallScore >= parameters.steadyThresholdMicros ->
                KnowledgeMasteryState.STEADY
            recallScore < parameters.reinforcementThresholdMicros ->
                KnowledgeMasteryState.NEEDS_REINFORCEMENT
            else -> KnowledgeMasteryState.FAMILIARIZING
        }
        val next =
            when (current) {
                KnowledgeMasteryState.STEADY ->
                    projection.recallFamiliarizingAtEpochMillis
                KnowledgeMasteryState.FAMILIARIZING ->
                    projection.recallReinforcementAtEpochMillis
                KnowledgeMasteryState.NEEDS_REINFORCEMENT -> null
            }?.takeIf { it > nowEpochMillis }
        return MasteryRecallProjectionState(current, next)
    }

    fun evidenceQualityMicros(sourceFact: MasterySourceFactEntity): Long {
        val verification =
            when (enumValueOf<TrustedLearningVerification>(sourceFact.verificationKind)) {
                TrustedLearningVerification.DEVICE_OBSERVED -> FULL_MASS_MICROS
                TrustedLearningVerification.DETERMINISTIC_RUBRIC -> 900_000L
                TrustedLearningVerification.MODEL_REVIEWED -> 550_000L
                TrustedLearningVerification.SELF_REPORTED -> 0L
            }
        val independence =
            if (isIndependentMasteryEvidence(sourceFact)) {
                FULL_MASS_MICROS
            } else {
                700_000L
            }
        val assistanceHelp =
            when (enumValueOf<ObservedAssistance>(sourceFact.assistance)) {
                ObservedAssistance.INDEPENDENT -> FULL_MASS_MICROS
                ObservedAssistance.ONE_HINT -> 750_000L
                ObservedAssistance.MULTIPLE_HINTS -> 500_000L
                ObservedAssistance.ANSWER_REVEALED -> 0L
                ObservedAssistance.UNKNOWN -> FULL_MASS_MICROS
            }
        val observedHelp =
            when {
                sourceFact.answerRevealed -> 0L
                sourceFact.hintCount == 0 -> FULL_MASS_MICROS
                sourceFact.hintCount == 1 -> 750_000L
                else -> 500_000L
            }
        val help = min(assistanceHelp, observedHelp)
        val retry =
            when (enumValueOf<ObservedRetryState>(sourceFact.retryState)) {
                ObservedRetryState.FIRST_ATTEMPT -> FULL_MASS_MICROS
                ObservedRetryState.ONE_RETRY -> 700_000L
                ObservedRetryState.MULTIPLE_RETRIES -> 400_000L
            }
        return multiplyMicros(
            multiplyMicros(multiplyMicros(verification, independence), help),
            retry,
        )
    }

    fun isIndependentMasteryEvidence(sourceFact: MasterySourceFactEntity): Boolean =
        sourceFact.outcome == ObservedLearningOutcome.CORRECT.name &&
            sourceFact.independentlyAnswered &&
            sourceFact.assistance == ObservedAssistance.INDEPENDENT.name &&
            sourceFact.retryState == ObservedRetryState.FIRST_ATTEMPT.name &&
            sourceFact.hintCount == 0 &&
            !sourceFact.answerRevealed

    fun weakNegativeConflictsWithStableMastery(
        candidate: MasteryObservationCandidateEntity,
        sourceFact: MasterySourceFactEntity,
        direction: MasteryEventDirection,
        currentProjections: List<MasteryKnowledgeProjectionEntity>,
    ): Boolean {
        if (direction != MasteryEventDirection.NEGATIVE) {
            return false
        }
        val directlyVerifiedIndependentError =
            sourceFact.outcome == ObservedLearningOutcome.INCORRECT.name &&
                sourceFact.responseForm in DIRECT_ANSWER_RESPONSE_FORMS &&
                sourceFact.independentlyAnswered &&
                sourceFact.hintCount == 0 &&
                !sourceFact.answerRevealed &&
                (
                    sourceFact.authority == MasteryEvidenceAuthority.LOCAL_VERIFIED.name ||
                        sourceFact.authority ==
                        MasteryEvidenceAuthority.DETERMINISTIC_RUBRIC.name
                    )
        if (directlyVerifiedIndependentError) {
            return false
        }
        val stabilityFloor =
            calibrationParametersFor(candidate)?.stableConflictFloorMillis
                ?: V1_STABLE_CONFLICT_FLOOR_MILLIS
        return currentProjections.any { projection ->
            projection.masteryState == KnowledgeMasteryState.STEADY.name &&
                projection.memoryStabilityMillis >= stabilityFloor &&
                projection.positiveEvidenceMicros > projection.negativeEvidenceMicros
        }
    }

    private fun fixedBaseMass(
        sourceFact: MasterySourceFactEntity,
        candidate: MasteryObservationCandidateEntity,
        calibrationSnapshot: MasteryCalibrationSnapshotEntity?,
    ): Long {
        val calibratedParameters = calibrationParametersFor(candidate, calibrationSnapshot)
        val authority =
            when (enumValueOf<MasteryEvidenceAuthority>(sourceFact.authority)) {
                MasteryEvidenceAuthority.LOCAL_VERIFIED ->
                    calibratedParameters?.localVerifiedMassMicros ?: FULL_MASS_MICROS
                MasteryEvidenceAuthority.DETERMINISTIC_RUBRIC ->
                    calibratedParameters?.deterministicRubricMassMicros ?: 800_000L
                MasteryEvidenceAuthority.MODEL_REVIEWED ->
                    calibratedParameters?.modelReviewedMassMicros ?: 350_000L
                MasteryEvidenceAuthority.SELF_REPORTED -> 0L
            }
        val reportedAssistance =
            when (enumValueOf<ObservedAssistance>(sourceFact.assistance)) {
                ObservedAssistance.INDEPENDENT -> FULL_MASS_MICROS
                ObservedAssistance.ONE_HINT ->
                    calibratedParameters?.oneHintScaleMicros ?: 600_000L
                ObservedAssistance.MULTIPLE_HINTS ->
                    calibratedParameters?.multipleHintsScaleMicros ?: 350_000L
                ObservedAssistance.ANSWER_REVEALED -> 0L
                ObservedAssistance.UNKNOWN ->
                    calibratedParameters?.unknownAssistanceScaleMicros ?: 500_000L
            }
        val observedHintUse =
            when {
                sourceFact.hintCount == 0 -> FULL_MASS_MICROS
                sourceFact.hintCount == 1 ->
                    calibratedParameters?.oneHintScaleMicros ?: 600_000L
                else ->
                    calibratedParameters?.multipleHintsScaleMicros ?: 350_000L
            }
        // Both fields are immutable source facts. Conservatively apply the stronger penalty so a
        // stale or inconsistent derived assistance label can never erase observed hint usage.
        val assistance = min(reportedAssistance, observedHintUse)
        val retry =
            when (enumValueOf<ObservedRetryState>(sourceFact.retryState)) {
                ObservedRetryState.FIRST_ATTEMPT -> FULL_MASS_MICROS
                ObservedRetryState.ONE_RETRY ->
                    calibratedParameters?.oneRetryScaleMicros ?: 700_000L
                ObservedRetryState.MULTIPLE_RETRIES ->
                    calibratedParameters?.multipleRetriesScaleMicros ?: 400_000L
            }
        val calibrated =
            multiplyMicros(multiplyMicros(authority, assistance), retry)
        val modelAttributionCap =
            if (
                candidate.candidateOrigin == MasteryCandidateOrigin.MODEL_SCOPED.name ||
                sourceFact.attributionModelVersion != null ||
                sourceFact.authority == MasteryEvidenceAuthority.MODEL_REVIEWED.name ||
                sourceFact.verificationKind == TrustedLearningVerification.MODEL_REVIEWED.name
            ) {
                calibratedParameters?.modelAttributionCapMicros
                    ?: V1_MODEL_ATTRIBUTION_CAP_MICROS
            } else {
                FULL_MASS_MICROS
            }
        val openResponseCap =
            if (sourceFact.responseForm == TrustedLearningResponseForm.FREE_RESPONSE.name) {
                calibratedParameters?.openResponseCapMicros
                    ?: V1_OPEN_RESPONSE_CAP_MICROS
            } else {
                FULL_MASS_MICROS
            }
        return min(calibrated, min(modelAttributionCap, openResponseCap))
    }

    private fun calibrationParametersFor(
        candidate: MasteryObservationCandidateEntity,
        calibrationSnapshot: MasteryCalibrationSnapshotEntity? = null,
    ): LocalMasteryCalibrationRegistry.Parameters? =
        if (candidate.requestedPolicyVersion == LEARNER_MASTERY_PROJECTION_POLICY_VERSION) {
            val snapshot =
                calibrationSnapshot ?: LocalMasteryCalibrationRegistry.current(candidate.subject)
            check(snapshot.subject == candidate.subject) {
                "Mastery heuristic parameter snapshot belongs to another subject"
            }
            check(snapshot.projectionPolicyVersion == candidate.requestedPolicyVersion) {
                "Mastery heuristic parameter snapshot belongs to another projection policy"
            }
            LocalMasteryCalibrationRegistry.verify(snapshot)
            LocalMasteryCalibrationRegistry.parameters(
                snapshot,
            )
        } else {
            check(calibrationSnapshot == null) {
                "Legacy mastery policies cannot claim a versioned heuristic parameter snapshot"
            }
            null
        }

    private fun attributionUnits(attribution: MasteryCandidateAttributionEntity): Long {
        val role =
            when (enumValueOf<MasteryAttributionRole>(attribution.role)) {
                MasteryAttributionRole.PRIMARY -> 4L
                MasteryAttributionRole.SUPPORTING -> 2L
                MasteryAttributionRole.CONTEXT -> 1L
            }
        val certainty =
            when (enumValueOf<MasteryAttributionCertainty>(attribution.certainty)) {
                MasteryAttributionCertainty.DIRECT -> 2L
                MasteryAttributionCertainty.INFERRED -> 1L
            }
        return role * certainty
    }

    private fun masteryState(
        scoreMicros: Long,
        positiveMicros: Long,
        negativeMicros: Long,
        observationCount: Long,
    ): KnowledgeMasteryState =
        masteryState(
            scoreMicros = scoreMicros,
            positiveMicros = positiveMicros,
            negativeMicros = negativeMicros,
            observationCount = observationCount,
            steadyThresholdMicros = V1_STEADY_SCORE_MICROS,
            reinforcementThresholdMicros = V1_REINFORCEMENT_SCORE_MICROS,
            steadyMinimumObservationCount = 3L,
        )

    private fun masteryState(
        scoreMicros: Long,
        positiveMicros: Long,
        negativeMicros: Long,
        observationCount: Long,
        steadyThresholdMicros: Long,
        reinforcementThresholdMicros: Long,
        steadyMinimumObservationCount: Long,
    ): KnowledgeMasteryState =
        when {
            scoreMicros >= steadyThresholdMicros &&
                observationCount >= steadyMinimumObservationCount ->
                KnowledgeMasteryState.STEADY
            scoreMicros < reinforcementThresholdMicros &&
                negativeMicros >= positiveMicros ->
                KnowledgeMasteryState.NEEDS_REINFORCEMENT
            else -> KnowledgeMasteryState.FAMILIARIZING
        }

    private fun multiplyMicros(
        left: Long,
        right: Long,
    ): Long = left * right / FULL_MASS_MICROS

    private fun saturatingAdd(
        left: Long,
        right: Long,
    ): Long =
        if (Long.MAX_VALUE - left < right) {
            Long.MAX_VALUE
        } else {
            left + right
        }

    private fun saturatingAddSigned(
        left: Long,
        right: Long,
    ): Long =
        when {
            right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
            right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
            else -> left + right
        }

    private fun fixedLogOddsToProbabilityMicros(
        logOddsMicros: Long,
        maximumAbsoluteLogOddsMicros: Long,
    ): Long {
        require(maximumAbsoluteLogOddsMicros > 0L)
        val bounded =
            logOddsMicros.coerceIn(
                -maximumAbsoluteLogOddsMicros,
                maximumAbsoluteLogOddsMicros,
            )
        val absolute = kotlin.math.abs(bounded)
        val distanceFromHalf =
            absolute * V1_HALF_MASS_MICROS /
                (FULL_MASS_MICROS + absolute)
        return if (bounded >= 0L) {
            V1_HALF_MASS_MICROS + distanceFromHalf
        } else {
            V1_HALF_MASS_MICROS - distanceFromHalf
        }
    }

    private fun fixedProbabilityToLogOddsMicros(
        probabilityMicros: Long,
        maximumAbsoluteLogOddsMicros: Long,
    ): Long {
        require(probabilityMicros in 0L..FULL_MASS_MICROS)
        require(maximumAbsoluteLogOddsMicros > 0L)
        if (probabilityMicros == V1_HALF_MASS_MICROS) {
            return 0L
        }
        val distance = kotlin.math.abs(probabilityMicros - V1_HALF_MASS_MICROS)
        val remaining = V1_HALF_MASS_MICROS - distance
        if (remaining <= 0L) {
            return if (probabilityMicros > V1_HALF_MASS_MICROS) {
                maximumAbsoluteLogOddsMicros
            } else {
                -maximumAbsoluteLogOddsMicros
            }
        }
        val magnitude =
            (distance * FULL_MASS_MICROS / remaining)
                .coerceAtMost(maximumAbsoluteLogOddsMicros)
        return if (probabilityMicros > V1_HALF_MASS_MICROS) {
            magnitude
        } else {
            -magnitude
        }
    }

    private fun firstRecallScoreBelowAt(
        recallDueAtEpochMillis: Long,
        historicalScoreMicros: Long,
        halfLifeMillis: Long,
        thresholdMicros: Long,
    ): Long {
        require(historicalScoreMicros in 0L..FULL_MASS_MICROS)
        require(halfLifeMillis > 0L)
        require(thresholdMicros in 1L..FULL_MASS_MICROS)
        val numerator = historicalScoreMicros * halfLifeMillis
        val maximumOverdueAtOrAboveThreshold =
            numerator / thresholdMicros - halfLifeMillis
        val firstBelowOverdue =
            max(1L, saturatingAdd(maximumOverdueAtOrAboveThreshold, 1L))
        return saturatingAdd(recallDueAtEpochMillis, firstBelowOverdue)
    }

    // Frozen uncalibrated heuristic values for legacy projection/admission replay only.
    private const val V1_HALF_MASS_MICROS = 500_000L
    private const val V1_PRIOR_MASS_MICROS = 1_000_000L
    private const val V1_STEADY_SCORE_MICROS = 720_000L
    private const val V1_REINFORCEMENT_SCORE_MICROS = 450_000L
    private const val V1_MATERIAL_SCORE_DELTA_MICROS = 40_000L
    private const val V1_HOUR_MILLIS = 60L * 60L * 1_000L
    private const val V1_DAY_MILLIS = 24L * V1_HOUR_MILLIS
    private const val V1_MIN_STABILITY_MILLIS = 6L * V1_HOUR_MILLIS
    private const val V1_MAX_STABILITY_MILLIS = 180L * V1_DAY_MILLIS
    private const val V1_STABLE_CONFLICT_FLOOR_MILLIS = 7L * V1_DAY_MILLIS
    private const val V1_MODEL_ATTRIBUTION_CAP_MICROS = 500_000L
    private const val V1_OPEN_RESPONSE_CAP_MICROS = 500_000L
    private val DIRECT_ANSWER_RESPONSE_FORMS =
        setOf(
            TrustedLearningResponseForm.MULTIPLE_CHOICE.name,
            TrustedLearningResponseForm.FREE_RESPONSE.name,
            TrustedLearningResponseForm.VISUAL_TARGET.name,
        )
}

internal object LearnerMasteryFingerprint {
    fun sourceProof(fact: MasterySourceFactEntity): String =
        CanonicalSha256("learner-mastery-source-proof-v1")
            .field("sourceFactId", fact.sourceFactId)
            .field("sourceFactFingerprint", fact.canonicalFingerprint)
            .field("sourcePolicyVersion", fact.sourcePolicyVersion)
            .field(
                "policySupported",
                fact.sourcePolicyVersion == LEARNER_MASTERY_SOURCE_POLICY_VERSION,
            )
            .field("attestedAtEpochMillis", fact.attestedAtEpochMillis)
            .finish()

    fun receipt(
        candidate: MasteryObservationCandidateEntity,
        disposition: String,
        inertReason: String?,
        sourceProofFingerprint: String?,
        eventId: String?,
        decidedAtEpochMillis: Long,
        admissionPolicyVersion: String = LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
        calibrationVersion: String = LEARNER_MASTERY_CALIBRATION_VERSION,
    ): String =
        CanonicalSha256("learner-mastery-admission-receipt-v1")
            .field("candidateId", candidate.candidateId)
            .field("candidateFingerprint", candidate.canonicalFingerprint)
            .field("candidateOrigin", candidate.candidateOrigin)
            .field("disposition", disposition)
            .nullableField("inertReason", inertReason)
            .nullableField("sourceProofFingerprint", sourceProofFingerprint)
            .nullableField("eventId", eventId)
            .field("policyVersion", candidate.requestedPolicyVersion)
            .field("admissionPolicyVersion", admissionPolicyVersion)
            .field("calibrationVersion", calibrationVersion)
            .field("decidedAtEpochMillis", decidedAtEpochMillis)
            .finish()

    fun event(
        eventId: String,
        candidate: MasteryObservationCandidateEntity,
        sourceFact: MasterySourceFactEntity,
        sourceProof: MasterySourceProofEntity,
        direction: MasteryEventDirection,
        sequence: Long,
        admittedAtEpochMillis: Long,
        attributedMasses: List<AttributedEvidenceMass>,
        projectionPolicyVersion: String,
        calibrationVersion: String,
        calibrationProfileId: String?,
        calibrationSnapshotFingerprint: String?,
        reviewResolutionFingerprint: String?,
    ): String {
        val digest = CanonicalSha256("learner-mastery-learning-event-v1")
            .field("eventId", eventId)
            .field("candidateFingerprint", candidate.canonicalFingerprint)
            .field("candidateOrigin", candidate.candidateOrigin)
            .field("sourceFactFingerprint", sourceFact.canonicalFingerprint)
            .field("sourceProofFingerprint", sourceProof.proofFingerprint)
            .field("direction", direction.name)
            .field("eventSequence", sequence)
            .field("occurredAtEpochMillis", sourceFact.occurredAtEpochMillis)
            .field("admittedAtEpochMillis", admittedAtEpochMillis)
            .field("policyVersion", projectionPolicyVersion)
            .field("admissionPolicyVersion", LEARNER_MASTERY_ADMISSION_POLICY_VERSION)
            .field("calibrationVersion", calibrationVersion)
            .nullableField("calibrationProfileId", calibrationProfileId)
            .nullableField(
                "calibrationSnapshotFingerprint",
                calibrationSnapshotFingerprint,
            )
            .nullableField("reviewResolutionFingerprint", reviewResolutionFingerprint)
            .field("attributionCount", attributedMasses.size)
        attributedMasses.forEachIndexed { index, attributed ->
            digest
                .field(
                    "knowledgeNode[$index]",
                    attributed.attribution.knowledgeNodeRefFingerprint,
                )
                .field("evidenceMassMicros[$index]", attributed.evidenceMassMicros)
        }
        return digest.finish()
    }

    fun eventId(candidate: MasteryObservationCandidateEntity): String =
        "mle:" +
            CanonicalSha256("learner-mastery-event-id-v1")
                .field("candidateId", candidate.candidateId)
                .field("candidateFingerprint", candidate.canonicalFingerprint)
                .finish()
                .take(48)

    fun reviewCase(
        candidate: MasteryObservationCandidateEntity,
        sourceProof: MasterySourceProofEntity,
        reason: LearningObservationInertReason,
        calibrationSnapshot: MasteryCalibrationSnapshotEntity,
        createdAtEpochMillis: Long,
    ): String =
        CanonicalSha256("learner-mastery-evidence-review-case-v2")
            .field("candidateFingerprint", candidate.canonicalFingerprint)
            .field("candidateOrigin", candidate.candidateOrigin)
            .field("sourceProofFingerprint", sourceProof.proofFingerprint)
            .field("reason", reason.name)
            .field("admissionPolicyVersion", LEARNER_MASTERY_ADMISSION_POLICY_VERSION)
            .field("calibrationBindingStatus", MasteryCalibrationBindingStatus.BOUND.name)
            .field("calibrationVersion", calibrationSnapshot.calibrationVersion)
            .field("calibrationProfileId", calibrationSnapshot.profileId)
            .field(
                "calibrationSnapshotFingerprint",
                calibrationSnapshot.snapshotFingerprint,
            )
            .field("createdAtEpochMillis", createdAtEpochMillis)
            .finish()
}

internal object MasteryProjectionIdentity {
    fun fingerprint(
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
    ): String =
        CanonicalSha256("learner-mastery-stable-node-identity-v1")
            .field("subject", subject)
            .field("knowledgeNodeId", knowledgeNodeId)
            .field("taxonomyVersion", taxonomyVersion)
            .finish()
}

internal object LocalPresentationEvidenceBudget {
    fun admittedMass(
        alreadyConsumedMicros: Long,
        requestedMassMicros: Long,
        maximumMassMicros: Long = LocalMasteryPolicy.FULL_MASS_MICROS,
    ): Long {
        require(maximumMassMicros > 0L)
        require(alreadyConsumedMicros in 0..maximumMassMicros) {
            "Consumed presentation evidence exceeds the local cap"
        }
        require(requestedMassMicros >= 0L) {
            "Requested presentation evidence must not be negative"
        }
        return min(
            requestedMassMicros,
            maximumMassMicros - alreadyConsumedMicros,
        )
    }
}

private fun String.isCanonicalMasteryFingerprint(): Boolean =
    length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }

/**
 * A repeated problem family remains evidence, but its marginal contribution declines and is
 * capped independently for each learner/node/taxonomy identity.
 */
internal object LocalProblemFamilyEvidenceBudget {
    const val MAX_FAMILY_MASS_MICROS = 2_000_000L

    fun admittedMass(
        priorObservationCount: Long,
        alreadyConsumedMicros: Long,
        requestedMassMicros: Long,
        maximumMassMicros: Long = MAX_FAMILY_MASS_MICROS,
        secondObservationScaleMicros: Long = 500_000L,
        repeatedObservationScaleMicros: Long = 250_000L,
    ): Long {
        require(priorObservationCount >= 0L) {
            "Problem-family observation count must not be negative"
        }
        require(maximumMassMicros > 0L)
        require(secondObservationScaleMicros in 0L..LocalMasteryPolicy.FULL_MASS_MICROS)
        require(repeatedObservationScaleMicros in 0L..LocalMasteryPolicy.FULL_MASS_MICROS)
        require(alreadyConsumedMicros in 0L..maximumMassMicros) {
            "Consumed problem-family evidence exceeds the local cap"
        }
        require(requestedMassMicros >= 0L) {
            "Requested problem-family evidence must not be negative"
        }
        val marginalFactorMicros =
            when (priorObservationCount) {
                0L -> LocalMasteryPolicy.FULL_MASS_MICROS
                1L -> secondObservationScaleMicros
                else -> repeatedObservationScaleMicros
            }
        val diminished =
            requestedMassMicros * marginalFactorMicros /
                LocalMasteryPolicy.FULL_MASS_MICROS
        return min(
            diminished,
            maximumMassMicros - alreadyConsumedMicros,
        )
    }
}

internal fun fingerprintAuthorizedKnowledgeRefs(
    knowledgeNodeRefFingerprints: List<String>,
): String {
    val sorted = knowledgeNodeRefFingerprints.sorted()
    val digest = CanonicalSha256("learner-mastery-authorized-knowledge-refs-v1")
        .field("count", sorted.size)
    sorted.forEachIndexed { index, fingerprint ->
        requireMasteryFingerprint(fingerprint, "Knowledge node reference fingerprint")
        digest.field("knowledgeNode[$index]", fingerprint)
    }
    return digest.finish()
}

internal fun fingerprintAuthorizedProblemBindings(
    problemBindingRefFingerprints: List<String>,
): String {
    val sorted = problemBindingRefFingerprints.sorted()
    val digest = CanonicalSha256("learner-mastery-authorized-problem-bindings-v1")
        .field("count", sorted.size)
    sorted.forEachIndexed { index, fingerprint ->
        requireMasteryFingerprint(fingerprint, "Problem binding reference fingerprint")
        digest.field("problemBinding[$index]", fingerprint)
    }
    return digest.finish()
}
