package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.NetworkMode
import com.tingyun.smartmistakebook.core.model.TeachingArtifactVerification
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoice
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TutorCapabilityGateTest {
    private val capabilities = AppCapabilitySnapshot(
        networkMode = NetworkMode.STRICT_OFFLINE,
        cameraCaptureAvailable = true,
        trustedOcrAvailable = false,
        tutorTeachingEnabled = true,
        remoteModelConfigured = false,
    )

    @Test
    fun `verified teaching artifact enables tutor even when strictly offline`() {
        val artifact = fixtureArtifact()

        val decision = TutorCapabilityGate().evaluate(capabilities, artifact)

        val available = decision as TutorCapabilityDecision.Available
        assertSame(artifact, available.artifact)
    }

    @Test
    fun `missing verified teaching artifact blocks tutor`() {
        val decision = TutorCapabilityGate().evaluate(capabilities, artifact = null)

        val blocked = decision as TutorCapabilityDecision.Blocked
        assertEquals(
            TutorCapabilityBlockReason.NO_VERIFIED_TEACHING_ARTIFACT,
            blocked.reason,
        )
    }

    @Test
    fun `artifact without an assessment item blocks interactive tutor`() {
        val artifact = fixtureArtifact().copy(assessmentItems = emptyList())

        val decision = TutorCapabilityGate().evaluate(capabilities, artifact)

        val blocked = decision as TutorCapabilityDecision.Blocked
        assertEquals(TutorCapabilityBlockReason.NO_ASSESSMENT_ITEM, blocked.reason)
    }

    private fun fixtureArtifact() = VerifiedTeachingArtifact(
        id = "derivative-fixture",
        subject = "数学",
        title = "导数与单调性",
        problemMarkdown = "求函数的单调区间。",
        explanationMarkdown = "先求定义域，再分析导数符号。",
        verification = TeachingArtifactVerification.HUMAN_VERIFIED,
        assessmentItems = listOf(
            TutorAssessmentItem(
                id = "derivative-check-1",
                stemMarkdown = "下一步应先做什么？",
                choices = listOf(
                    TutorChoice("a", "求定义域"),
                    TutorChoice("b", "直接写答案"),
                ),
                correctChoiceId = "a",
            ),
        ),
    )
}
