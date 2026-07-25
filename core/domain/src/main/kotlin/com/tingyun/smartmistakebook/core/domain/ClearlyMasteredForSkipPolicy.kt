package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.IndependentCorrectObservation
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.MasteryStatus

/** Shared, time-aware threshold contract used by projection and adaptive teaching decisions. */
object ClearlyMasteredForSkipPolicy {
    const val VERSION = LearningCoreVersions.SKIP_POLICY
    const val LOWER_BOUND = 0.85
    const val EVIDENCE_MASS = 2.0
    const val REQUIRED_FAMILIES = 2
    const val REQUIRED_STUDY_DAYS = 2
    const val MAX_EVIDENCE_AGE_MILLIS = 45L * 86_400_000L

    fun isSatisfied(state: KnowledgeMasteryState, atEpochMillis: Long): Boolean {
        require(atEpochMillis >= 0) { "Mastery decision time must not be negative" }
        val lastEvidenceAt = state.lastEvidenceAtEpochMillis ?: return false
        if (atEpochMillis < lastEvidenceAt || atEpochMillis - lastEvidenceAt > MAX_EVIDENCE_AGE_MILLIS) {
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
            state.lowerBoundIndependentCorrect >= LOWER_BOUND &&
            state.evidenceMass >= EVIDENCE_MASS &&
            supported.sumOf(IndependentCorrectObservation::evidenceWeight) >= EVIDENCE_MASS &&
            hasIndependentBreadth(
                observations = supported,
                lastIndependentErrorAtEpochMillis = null,
                lastIndependentErrorSequence = null,
                atEpochMillis = atEpochMillis,
            )
    }

    internal fun hasIndependentBreadth(
        observations: List<IndependentCorrectObservation>,
        lastIndependentErrorAtEpochMillis: Long?,
        lastIndependentErrorSequence: Long?,
        atEpochMillis: Long,
    ): Boolean {
        val valid = validIndependentObservations(
            observations,
            lastIndependentErrorAtEpochMillis,
            lastIndependentErrorSequence,
            atEpochMillis,
        )
        return valid.map(IndependentCorrectObservation::itemFamilyId).distinct().size >= REQUIRED_FAMILIES &&
            valid.map(IndependentCorrectObservation::studyDayEpochDay).distinct().size >= REQUIRED_STUDY_DAYS
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
