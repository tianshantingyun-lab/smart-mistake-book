package com.tingyun.smartmistakebook.core.student.mistake.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentMistakeImportSemanticSnapshotTest {
    @Test
    fun optionalLegacySemanticsRetainEveryIndependentSourceField() {
        val semantics =
            StudentMistakeImportSemanticSnapshot(
                problemCanonicalFingerprint = "a".repeat(64),
                revisionSourceType = "CAPTURE_CONFIRMED",
                revisionSourceReference = "capture:revision-1",
                answerSpecId = "answer-1",
                answerSpecSnapshot = """{"kind":"choice","answer":"B"}""",
                answerVerificationStatus = "VERIFIED",
                errorBookSourceKey = "error-book:source-1",
                practiceUnitKey = "whole-problem",
                practiceUnitPromptMarkdown = "请选择正确结论。",
            )

        assertEquals("capture:revision-1", semantics.revisionSourceReference)
        assertEquals("error-book:source-1", semantics.errorBookSourceKey)
        assertTrue(
            StudentMistakeImportSemanticSnapshot::class.java.declaredFields.none {
                it.name == "sourceBundleId"
            },
        )
    }

    @Test
    fun importSnapshotFingerprintCoversTargetAndEveryLegacySemanticField() {
        val base = snapshot().withCanonicalFingerprint()
        base.requireCanonicalShape()

        val mutations =
            listOf(
                base.copy(problemId = "problem-2"),
                base.copy(practiceUnitId = "unit-2"),
                base.copy(targetUnitKind = "PROBLEM_PART"),
                base.copy(targetTitle = "另一标题"),
                base.copy(targetItemFamilyId = "family-2"),
                base.copy(targetEstimatedDurationSeconds = 61),
                base.copy(targetSourceBundleId = "bundle-2"),
                base.copy(targetPartIdsWire = "1:a"),
                base.copy(targetErrorBookEntryId = "entry-2"),
                base.copy(legacyProblemCanonicalFingerprint = "b".repeat(64)),
                base.copy(legacyRevisionSourceType = "IMPORTED"),
                base.copy(legacyRevisionSourceReference = "capture:revision-2"),
                base.copy(legacyAnswerSpecId = "answer-2"),
                base.copy(legacyAnswerSpecSnapshot = """{"answer":"C"}"""),
                base.copy(legacyAnswerVerificationStatus = "UNKNOWN"),
                base.copy(legacyErrorBookSourceKey = "error-book:source-2"),
                base.copy(legacyPracticeUnitKey = "part-a"),
                base.copy(legacyPracticeUnitPromptMarkdown = "计算第一问。"),
            )

        mutations.forEach { mutated ->
            assertNotEquals(
                base.snapshotCanonicalFingerprint,
                mutated.computeCanonicalFingerprint(),
            )
        }
    }

    @Test
    fun absentLegacySemanticsCannotHideRetainedLegacyValues() {
        val invalid =
            snapshot()
                .copy(
                    legacySemanticsPresent = false,
                    snapshotCanonicalFingerprint = "0".repeat(64),
                )
                .let { it.copy(snapshotCanonicalFingerprint = it.computeCanonicalFingerprint()) }

        assertTrue(runCatching { invalid.requireCanonicalShape() }.isFailure)
    }

    private fun snapshot(): StudentProblemImportSemanticSnapshotEntity =
        StudentProblemImportSemanticSnapshotEntity(
            revisionId = "revision-1",
            problemId = "problem-1",
            practiceUnitId = "unit-1",
            targetUnitKind = "WHOLE_PROBLEM",
            targetTitle = "函数题",
            targetItemFamilyId = "family-1",
            targetEstimatedDurationSeconds = 60,
            targetSourceBundleId = "bundle-1",
            targetPartIdsWire = "0:",
            targetErrorBookEntryId = "entry-1",
            legacySemanticsPresent = true,
            legacyProblemCanonicalFingerprint = "a".repeat(64),
            legacyRevisionSourceType = "CAPTURE_CONFIRMED",
            legacyRevisionSourceReference = "capture:revision-1",
            legacyAnswerSpecId = "answer-1",
            legacyAnswerSpecSnapshot = """{"answer":"B"}""",
            legacyAnswerVerificationStatus = "VERIFIED",
            legacyErrorBookSourceKey = "error-book:source-1",
            legacyPracticeUnitKey = "whole-problem",
            legacyPracticeUnitPromptMarkdown = "请选择正确结论。",
            snapshotCanonicalFingerprint = "0".repeat(64),
        )

    private fun StudentProblemImportSemanticSnapshotEntity.withCanonicalFingerprint():
        StudentProblemImportSemanticSnapshotEntity =
        copy(snapshotCanonicalFingerprint = computeCanonicalFingerprint())
}
