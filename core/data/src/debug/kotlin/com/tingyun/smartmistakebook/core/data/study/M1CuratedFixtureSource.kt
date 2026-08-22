package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.data.M1CuratedStudySeed
import com.tingyun.smartmistakebook.core.database.StudySeedBundle
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot

/**
 * Debug-only fixture source backed by the M1 curated content. Registered into
 * [StudyFixtureRegistry] by [M1FixtureInitProvider]; never present in release
 * builds because this file lives in the debug source set (audit PR-05).
 */
object M1CuratedFixtureSource : StudyFixtureSource {
    override val tutorPracticeUnitId: String? = M1CuratedStudySeed.TUTOR_PRACTICE_UNIT_ID

    override fun bundle(includeTutorMistake: Boolean): StudySeedBundle? =
        M1CuratedStudySeed.bundle(includeTutorMistake = includeTutorMistake)

    override fun teachingArtifactForPracticeUnit(
        practiceUnitId: String,
    ): VerifiedTeachingArtifact? =
        M1CuratedStudySeed.teachingArtifactForPracticeUnit(practiceUnitId)

    override fun evidenceSnapshotForAssessment(
        assessmentItemId: String,
    ): AssessmentEvidenceSnapshot? =
        M1CuratedStudySeed.evidenceSnapshotForAssessment(assessmentItemId)
}
