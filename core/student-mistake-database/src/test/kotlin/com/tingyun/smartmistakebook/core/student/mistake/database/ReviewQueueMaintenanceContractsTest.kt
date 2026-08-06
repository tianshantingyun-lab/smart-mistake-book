package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewQueueMaintenanceContractsTest {
    @Test
    fun commandIsExactDeterministicAndMachineAuditable() {
        val command = command()

        assertEquals(
            command.canonicalFingerprint(LEARNER),
            command.canonicalFingerprint(LEARNER),
        )
        assertTrue(
            command.auditReasonCode(LEARNER)
                .contains(StudentReviewQueueRemovalReason.PENDING_KNOWLEDGE_ATTRIBUTION.name),
        )
        assertTrue(command.auditReasonCode(LEARNER).length <= 128)
        assertNotEquals(
            command.canonicalFingerprint(LEARNER),
            command.copy(changedAtEpochMillis = 2L).canonicalFingerprint(LEARNER),
        )
        assertNotEquals(
            command.canonicalFingerprint(LEARNER),
            command.copy(
                reason = StudentReviewQueueRemovalReason.SAVED_REVISION_INELIGIBLE,
            ).canonicalFingerprint(LEARNER),
        )
        assertNotEquals(
            command.canonicalFingerprint(LEARNER),
            command.copy(
                expectedProblemRevision =
                    revision().copy(documentCanonicalFingerprint = "c".repeat(64)),
            ).canonicalFingerprint(LEARNER),
        )
    }

    @Test
    fun maintenanceCarriesNoDeletionMasteryOrModelWriteSurface() {
        val fieldNames =
            RemoveUnreadyStudentReviewQueueItemCommand::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .map(java.lang.reflect.Field::getName)

        setOf(
            "delete",
            "sql",
            "mastery",
            "weight",
            "confidence",
            "outbox",
            "model",
        ).forEach { forbidden ->
            assertTrue(
                "Review queue maintenance leaks $forbidden",
                fieldNames.none { it.contains(forbidden, ignoreCase = true) },
            )
        }
    }

    private fun command(): RemoveUnreadyStudentReviewQueueItemCommand =
        RemoveUnreadyStudentReviewQueueItemCommand(
            maintenanceId = "maintenance-1",
            planId = "plan-1",
            expectedPlanCanonicalFingerprint = "a".repeat(64),
            queueItemId = "queue-1",
            expectedProblemRevision = revision(),
            reason = StudentReviewQueueRemovalReason.PENDING_KNOWLEDGE_ATTRIBUTION,
            changedAtEpochMillis = 1L,
        )

    private fun revision(): StudentProblemRevisionRef =
        StudentProblemRevisionRef(
            problem =
                StudentProblemRef(
                    learnerId = LEARNER,
                    subject = SubjectKind.MATH,
                    problemId = "problem-1",
                    practiceUnitId = "practice-1",
                ),
            revisionId = "revision-1",
            revisionNumber = 1,
            documentCanonicalFingerprint = "b".repeat(64),
        )

    private companion object {
        const val LEARNER = "learner-local"
    }
}
