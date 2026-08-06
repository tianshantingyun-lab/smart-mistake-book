package com.tingyun.smartmistakebook.core.database

import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ExactLegacyCaptureStudentDocumentSourcePortContractTest {
    @Test
    fun publicAbiContainsOnlyOneExactDocumentRead() {
        val methods =
            ExactLegacyCaptureStudentDocumentSourcePort::class.java.methods.domainMethods()

        assertEquals(
            setOf("readExactLegacyCaptureStudentDocument"),
            methods.mapTo(linkedSetOf(), Method::getName),
        )
        assertEquals(
            ExactLegacyCaptureStudentDocumentQuery::class.java,
            methods.single().parameterTypes.first(),
        )
        assertFalse(
            methods.any { method ->
                FORBIDDEN_METHOD_FRAGMENTS.any(method.name.lowercase()::contains)
            },
        )
    }

    @Test
    fun receiptReplayHasNoNullableAlternateProof() {
        val query = receiptQuery()

        assertEquals("entry:capture:1", query.errorBookEntryId)
        assertEquals(
            setOf(
                "draftRevisionNumber",
                "draftId",
                "errorBookEntryId",
                "intentCanonicalFingerprint",
                "intentId",
                "learnerId",
                "problemId",
                "problemRevisionId",
                "practiceUnitId",
                "tutorSessionId",
            ),
            ExactLegacyCaptureReceiptReplayQuery::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf()) { it.name },
        )
    }

    @Test
    fun preparedHandoffReplayRequiresCompleteImmutableTarget() {
        val query = preparedHandoffQuery()

        assertEquals("PHYSICS", query.subject)
        assertEquals(2, query.problemRevisionNumber)
        assertEquals("b".repeat(64), query.documentCanonicalFingerprint)
        assertEquals(
            setOf(
                "documentCanonicalFingerprint",
                "draftId",
                "draftRevisionNumber",
                "intentCanonicalFingerprint",
                "intentId",
                "learnerId",
                "practiceUnitId",
                "problemId",
                "problemRevisionId",
                "subject",
                "problemRevisionNumber",
                "tutorSessionId",
            ),
            ExactLegacyPreparedHandoffReplayQuery::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf()) { it.name },
        )
    }

    @Test
    fun bothReplayShapesRejectAnUnprovenLearnerScope() {
        assertThrows(IllegalArgumentException::class.java) {
            receiptQuery(learnerId = "learner:other")
        }
        assertThrows(IllegalArgumentException::class.java) {
            preparedHandoffQuery(learnerId = "learner:other")
        }
    }

    @Test
    fun preparedHandoffRejectsInvalidRevisionProof() {
        assertThrows(IllegalArgumentException::class.java) {
            preparedHandoffQuery(problemRevisionNumber = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            preparedHandoffQuery(documentCanonicalFingerprint = "not-a-sha")
        }
        assertThrows(IllegalArgumentException::class.java) {
            preparedHandoffQuery(subject = "NOT_A_SUBJECT")
        }
    }

    @Test
    fun nullSessionIsAnExplicitAbsenceClaimForBothShapes() {
        assertEquals(null, receiptQuery(tutorSessionId = null).tutorSessionId)
        assertEquals(null, preparedHandoffQuery(tutorSessionId = null).tutorSessionId)
    }

    @Test
    fun legacySaveClaimRejectsAnUnprovenLearnerScope() {
        LegacyCaptureStudentSaveClaim(
            learnerId = "learner:local",
            tutorSessionId = "session:tutor:1",
        )

        assertThrows(IllegalArgumentException::class.java) {
            LegacyCaptureStudentSaveClaim(learnerId = "learner:other")
        }
    }

    @Test
    fun migrationRecordRequiresTheDocumentToBeTheEntryHeadButPreservesThePracticeBasis() {
        legacyRecord()
        legacyRecord(practiceUnitRevisionId = "revision:practice-basis")
        assertThrows(IllegalArgumentException::class.java) {
            legacyRecord(entryCurrentRevisionId = "revision:other")
        }
    }

    private fun receiptQuery(
        learnerId: String = "learner:local",
        tutorSessionId: String? = "session:tutor:1",
    ) = ExactLegacyCaptureReceiptReplayQuery(
        learnerId = learnerId,
        intentId = "capture-commit:1",
        intentCanonicalFingerprint = "a".repeat(64),
        draftId = "draft:capture:1",
        draftRevisionNumber = 3,
        tutorSessionId = tutorSessionId,
        errorBookEntryId = "entry:capture:1",
        problemId = "problem:target",
        problemRevisionId = "revision:target:2",
        practiceUnitId = "practice:target",
    )

    private fun preparedHandoffQuery(
        learnerId: String = "learner:local",
        tutorSessionId: String? = "session:tutor:1",
        subject: String = "PHYSICS",
        problemRevisionNumber: Int = 2,
        documentCanonicalFingerprint: String = "b".repeat(64),
    ) = ExactLegacyPreparedHandoffReplayQuery(
        learnerId = learnerId,
        intentId = "capture-commit:1",
        intentCanonicalFingerprint = "a".repeat(64),
        draftId = "draft:capture:1",
        draftRevisionNumber = 3,
        tutorSessionId = tutorSessionId,
        subject = subject,
        problemId = "problem:target",
        problemRevisionId = "revision:target:2",
        problemRevisionNumber = problemRevisionNumber,
        practiceUnitId = "practice:target",
        documentCanonicalFingerprint = documentCanonicalFingerprint,
    )

    private fun legacyRecord(
        practiceUnitRevisionId: String = "revision:target:2",
        entryCurrentRevisionId: String = "revision:target:2",
    ) = LegacyStudentDocumentMigrationRecord(
        entryId = "entry:capture:1",
        problemId = "problem:target",
        problemCanonicalFingerprint = "a".repeat(64),
        revisionId = "revision:target:2",
        revisionNumber = 2,
        subject = "PHYSICS",
        problemCreatedAtEpochMillis = 1_000,
        problemArchivedAtEpochMillis = null,
        title = "Target problem",
        problemMarkdown = "What is the answer?",
        questionDocumentSnapshot = null,
        answerSpecId = null,
        answerSpecSnapshot = null,
        answerVerificationStatus = "UNKNOWN",
        revisionSourceType = "CAPTURE_CONFIRMED",
        revisionSourceReference = "asset:1",
        contentFingerprint = "b".repeat(64),
        practiceUnitId = "practice:target",
        practiceUnitKey = "whole-problem",
        practiceUnitKind = "WHOLE_PROBLEM",
        practiceUnitTitle = "Target problem",
        practiceUnitPromptMarkdown = "What is the answer?",
        estimatedSeconds = 60,
        practiceUnitRevisionId = practiceUnitRevisionId,
        practiceUnitCreatedAtEpochMillis = 2_000,
        entryCurrentRevisionId = entryCurrentRevisionId,
        sourceKey = "capture:draft:capture:1",
        status = "ACTIVE",
        acceptedAtEpochMillis = 2_000,
        updatedAtEpochMillis = 2_000,
        revisionCreatedAtEpochMillis = 2_000,
        sourceAssets = emptyList(),
    )

    private fun Array<Method>.domainMethods(): List<Method> =
        filterNot { method -> method.declaringClass == Any::class.java }

    private companion object {
        val FORBIDDEN_METHOD_FRAGMENTS = setOf(
            "delete",
            "write",
            "mastery",
            "knowledge",
            "sql",
            "dao",
        )
    }
}
