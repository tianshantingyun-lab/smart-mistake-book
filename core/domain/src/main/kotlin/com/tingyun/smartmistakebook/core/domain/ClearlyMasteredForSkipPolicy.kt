package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.IndependentCorrectObservation
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.MasteryStatus

/**
 * Versioned policy for determining when a knowledge component is clearly mastered.
 * Uses calibrated lower bound and configurable thresholds.
 */
data class MasteryDecisionPolicy(
    val policyVersion: String,
    val minimumLowerBound: Double,
    val minimumDirectObservations: Int,
    val minimumItemFamilies: Int,
    val minimumStudyDays: Int,
    val maximumEvidenceAgeDays: Int,
) {
    init {
        require(minimumLowerBound in 0.0..1.0) { "Lower bound must be between 0 and 1" }
        require(minimumDirectObservations > 0) { "Minimum observations must be positive" }
        require(minimumItemFamilies > 0) { "Minimum families must be positive" }
        require(minimumStudyDays > 0) { "Minimum study days must be positive" }
        require(maximumEvidenceAgeDays > 0) { "Maximum evidence age must be positive" }
    }

    companion object {
        val DEFAULT = MasteryDecisionPolicy(
            policyVersion = "mastery-v1",
            minimumLowerBound = 0.85,
            minimumDirectObservations = 2,
            minimumItemFamilies = 2,
            minimumStudyDays = 2,
            maximumEvidenceAgeDays = 45,
        )
    }
}

/** Shared, time-aware threshold contract used by projection and adaptive teaching decisions. */
object ClearlyMasteredForSkipPolicy {
    const val VERSION = LearningCoreVersions.SKIP_POLICY
    const val LOWER_BOUND = 0.85
    const val EVIDENCE_MASS = 2.0
    const val REQUIRED_FAMILIES = 2
    const val REQUIRED_STUDY_DAYS = 2
    const val MAX_EVIDENCE_AGE_MILLIS = 45L * 86_400_000L

    fun isSatisfied(
        state: KnowledgeMasteryState,
        atEpochMillis: Long,
        policy: MasteryDecisionPolicy = MasteryDecisionPolicy.DEFAULT,
    ): Boolean {
        require(atEpochMillis >= 0) { "Mastery decision time must not be negative" }
        val lastEvidenceAt = state.lastEvidenceAtEpochMillis ?: return false
        val maxAgeMillis = policy.maximumEvidenceAgeDays.toLong() * 86_400_000L
        if (atEpochMillis < lastEvidenceAt || atEpochMillis - lastEvidenceAt > maxAgeMillis) {
            return false
        }
        val supported = validIndependentObservations(
            observations = state.independentCorrectObservations.filter {
                it.calibrationSupportAt(atEpochMillis) == CalibrationSupport.SUPPORTED
            },
            lastIndependentErrorAtEpochMillis = state.lastIndependentErrorAtEpochMillis,
            lastIndependentErrorSequence = state.lastIndependentErrorSequence,
            atEpochMillis = atEpochMillis,
        )
        return state.status == MasteryStatus.MASTERED &&
            state.lowerBoundIndependentCorrect >= policy.minimumLowerBound &&
            state.evidenceMass >= policy.minimumDirectObservations &&
            supported.sumOf(IndependentCorrectObservation::evidenceWeight) >= policy.minimumDirectObservations &&
            hasIndependentBreadth(
                observations = supported,
                lastIndependentErrorAtEpochMillis = null,
                lastIndependentErrorSequence = null,
                atEpochMillis = atEpochMillis,
                policy = policy,
            )
    }

    internal fun hasIndependentBreadth(
        observations: List<IndependentCorrectObservation>,
        lastIndependentErrorAtEpochMillis: Long?,
        lastIndependentErrorSequence: Long?,
        atEpochMillis: Long,
        policy: MasteryDecisionPolicy = MasteryDecisionPolicy.DEFAULT,
    ): Boolean {
        val valid = validIndependentObservations(
            observations,
            lastIndependentErrorAtEpochMillis,
            lastIndependentErrorSequence,
            atEpochMillis,
        )
        return valid.map(IndependentCorrectObservation::itemFamilyId).distinct().size >= policy.minimumItemFamilies &&
            valid.map(IndependentCorrectObservation::studyDayEpochDay).distinct().size >= policy.minimumStudyDays
    }

    internal fun validIndependentObservations(
        observations: List<IndependentCorrectObservation>,
        lastIndependentErrorAtEpochMillis: Long?,
        lastIndependentErrorSequence: Long?,
        atEpochMillis: Long,
    ): List<IndependentCorrectObservation> = observations.filter {
        val afterError = when {
            lastIndependentErrorSequence != null && it.eventSequence > 0 ->
                it.eventSequence > lastIndependentErrorSequence
            lastIndependentErrorAtEpochMillis != null ->
                it.occurredAtEpochMillis > lastIndependentErrorAtEpochMillis
            else -> true
        }
        val fresh = atEpochMillis >= it.occurredAtEpochMillis &&
            atEpochMillis - it.occurredAtEpochMillis <= MAX_EVIDENCE_AGE_MILLIS
        afterError && fresh && it.isStudyDayTrusted
    }
}
