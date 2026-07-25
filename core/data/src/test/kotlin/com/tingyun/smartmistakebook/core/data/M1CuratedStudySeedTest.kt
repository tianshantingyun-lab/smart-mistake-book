package com.tingyun.smartmistakebook.core.data

import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M1CuratedStudySeedTest {
    @Test
    fun `seed contains five verified questions and only four initial mistake entries`() {
        val bundle = M1CuratedStudySeed.bundle()

        assertEquals(5, bundle.problems.size)
        assertEquals(5, bundle.revisions.size)
        assertEquals(5, bundle.practiceUnits.size)
        assertEquals(5, bundle.assessmentItems.size)
        assertEquals(4, bundle.errorBookEntries.size)
        assertTrue(
            bundle.revisions.all {
                it.answerVerificationStatus == StudyDbValue.VerificationStatus.VERIFIED
            },
        )
        assertFalse(
            bundle.errorBookEntries.any {
                it.problemId == M1CuratedStudySeed.TUTOR_PROBLEM_ID
            },
        )
        assertTrue(bundle.problems.all { it.canonicalFingerprint.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(bundle.revisions.all { it.contentFingerprint.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(
            bundle.revisions.all { revision ->
                val document = CapturedQuestionDocumentCodec.decode(
                    requireNotNull(revision.questionDocumentSnapshot),
                )
                CapturedQuestionDocumentValidator.validateForCommit(document).isEmpty() &&
                    CapturedQuestionDocumentFingerprint.of(document) == revision.contentFingerprint
            },
        )
    }

    @Test
    fun `seed never invents learning history or generated plans`() {
        val bundle = M1CuratedStudySeed.bundle()

        assertTrue(bundle.assessmentEvents.isEmpty())
        assertTrue(bundle.problemMemoryStates.isEmpty())
        assertTrue(bundle.knowledgeMasteryStates.isEmpty())
        assertTrue(bundle.reviewPlans.isEmpty())
        assertTrue(bundle.reviewQueueItems.isEmpty())
        assertTrue(bundle.reviewSessions.isEmpty())
    }

    @Test
    fun `explicit Tutor save produces the fifth entry idempotency payload`() {
        val initial = M1CuratedStudySeed.bundle()
        val saved = M1CuratedStudySeed.bundle(includeTutorMistake = true)

        assertEquals(4, initial.errorBookEntries.size)
        assertEquals(5, saved.errorBookEntries.size)
        assertEquals(
            1,
            saved.errorBookEntries.count {
                it.problemId == M1CuratedStudySeed.TUTOR_PROBLEM_ID
            },
        )
        assertEquals(initial.problems, saved.problems)
        assertEquals(initial.revisions, saved.revisions)
        assertEquals(initial.practiceUnits, saved.practiceUnits)
    }

    @Test
    fun `every reference resolves inside the same immutable bundle`() {
        val bundle = M1CuratedStudySeed.bundle()
        val problemIds = bundle.problems.mapTo(mutableSetOf()) { it.problemId }
        val revisionIds = bundle.revisions.mapTo(mutableSetOf()) { it.revisionId }
        val practiceUnitIds = bundle.practiceUnits.mapTo(mutableSetOf()) { it.practiceUnitId }
        val knowledgeNodeIds = bundle.knowledgeNodes.mapTo(mutableSetOf()) { it.knowledgeNodeId }

        assertTrue(bundle.revisions.all { it.problemId in problemIds })
        assertTrue(
            bundle.practiceUnits.all {
                it.problemId in problemIds && it.problemRevisionId in revisionIds
            },
        )
        assertTrue(
            bundle.errorBookEntries.all {
                it.problemId in problemIds &&
                    it.currentRevisionId in revisionIds &&
                    it.practiceUnitId in practiceUnitIds
            },
        )
        assertTrue(
            bundle.knowledgeBindings.all {
                it.practiceUnitId in practiceUnitIds &&
                    it.knowledgeNodeId in knowledgeNodeIds &&
                    it.basisRevisionId in revisionIds
            },
        )
        assertTrue(
            bundle.assessmentItems.all {
                it.problemRevisionId in revisionIds && it.practiceUnitId in practiceUnitIds
            },
        )
    }

    @Test
    fun `verified answer keys match the curated catalog`() {
        val keysByProblem = M1CuratedStudySeed.bundle().assessmentItems.associate { item ->
            item.problemRevisionId to item.answerSpecSnapshot
        }

        assertTrue(keysByProblem.getValue("revision:m1:closed-interval-extrema:r1").contains("\"B\""))
        assertTrue(keysByProblem.getValue("revision:m1:derivative-sign-change:r1").contains("\"A\""))
        assertTrue(keysByProblem.getValue("revision:m1:electromagnetic-direction:r1").contains("\"B\""))
        assertTrue(keysByProblem.getValue("revision:m1:conic-eccentricity:r1").contains("\"C\""))
        assertTrue(keysByProblem.getValue("revision:m1:chemical-equilibrium:r1").contains("\"D\""))
    }

    @Test
    fun `bundle generation is deterministic`() {
        assertEquals(M1CuratedStudySeed.bundle(), M1CuratedStudySeed.bundle())
    }

    @Test
    fun `Tutor and Review consume the same verified catalog facts`() {
        val artifact = requireNotNull(
            M1CuratedStudySeed.teachingArtifactForPracticeUnit(
                M1CuratedStudySeed.TUTOR_PRACTICE_UNIT_ID,
            ),
        )
        val assessment = artifact.assessmentItems.single()
        val evidence = requireNotNull(
            M1CuratedStudySeed.evidenceSnapshotForAssessment(assessment.id),
        )

        assertEquals("A", assessment.correctChoiceId)
        assertEquals(4, assessment.choices.size)
        assertEquals(AssessmentSnapshotVerification.VERIFIED, evidence.verification)
        assertEquals(M1CuratedStudySeed.TUTOR_PRACTICE_UNIT_ID, evidence.practiceUnitId)
        assertEquals(artifact.knowledgeNodeIds, evidence.attributions.mapTo(mutableSetOf()) { it.knowledgeNodeId })
    }
}
