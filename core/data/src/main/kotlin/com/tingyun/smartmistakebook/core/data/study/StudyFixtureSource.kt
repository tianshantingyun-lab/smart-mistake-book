package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.StudySeedBundle
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact

/**
 * Seam for curated demo study content (audit section 9.2 / PR-05).
 *
 * Production builds must not ship fixtures: [StudyFixtureRegistry] defaults
 * to [EmptyStudyFixtureSource], so saving a tutor problem never writes demo
 * seeds. The debug source set registers the M1 curated content via an
 * auto-initializing provider so instrumented tests keep their behavior.
 */
interface StudyFixtureSource {
    /** Practice unit id of the tutor demo mistake, or null when absent. */
    val tutorPracticeUnitId: String?

    fun bundle(includeTutorMistake: Boolean): StudySeedBundle?

    fun teachingArtifactForPracticeUnit(practiceUnitId: String): VerifiedTeachingArtifact?

    fun evidenceSnapshotForAssessment(assessmentItemId: String): AssessmentEvidenceSnapshot?
}

object EmptyStudyFixtureSource : StudyFixtureSource {
    override val tutorPracticeUnitId: String? = null

    override fun bundle(includeTutorMistake: Boolean): StudySeedBundle? = null

    override fun teachingArtifactForPracticeUnit(
        practiceUnitId: String,
    ): VerifiedTeachingArtifact? = null

    override fun evidenceSnapshotForAssessment(
        assessmentItemId: String,
    ): AssessmentEvidenceSnapshot? = null
}

object StudyFixtureRegistry {
    @Volatile
    var source: StudyFixtureSource = EmptyStudyFixtureSource
}
