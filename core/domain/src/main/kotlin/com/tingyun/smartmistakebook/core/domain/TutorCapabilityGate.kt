package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact

enum class TutorCapabilityBlockReason {
    TUTOR_DISABLED_BY_BUILD,
    NO_VERIFIED_TEACHING_ARTIFACT,
    NO_ASSESSMENT_ITEM,
}

sealed interface TutorCapabilityDecision {
    data class Available(val artifact: VerifiedTeachingArtifact) : TutorCapabilityDecision

    data class Blocked(val reason: TutorCapabilityBlockReason) : TutorCapabilityDecision
}

class TutorCapabilityGate {
    fun evaluate(
        capabilities: AppCapabilitySnapshot,
        artifact: VerifiedTeachingArtifact?,
    ): TutorCapabilityDecision = when {
        !capabilities.tutorTeachingEnabled -> TutorCapabilityDecision.Blocked(
            TutorCapabilityBlockReason.TUTOR_DISABLED_BY_BUILD,
        )

        artifact == null -> TutorCapabilityDecision.Blocked(
            TutorCapabilityBlockReason.NO_VERIFIED_TEACHING_ARTIFACT,
        )

        artifact.assessmentItems.isEmpty() -> TutorCapabilityDecision.Blocked(
            TutorCapabilityBlockReason.NO_ASSESSMENT_ITEM,
        )

        else -> TutorCapabilityDecision.Available(artifact)
    }
}
