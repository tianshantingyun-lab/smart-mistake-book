package com.tingyun.smartmistakebook.core.student.mistake.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentTrustedAnswerOwnershipBoundaryTest {
    @Test
    fun runtimeDoesNotExposeAnswerRuleAdmissionCapability() {
        val exposedNames = buildSet {
            StudentMistakeRuntimeCapabilities::class.java.declaredMethods.mapTo(this) { it.name }
            CoreDataStudentMistakeOwnerBridge::class.java.declaredMethods.mapTo(this) { it.name }
        }

        assertFalse(exposedNames.any { it.contains("AnswerRuleAdmission", ignoreCase = true) })
        assertFalse(exposedNames.any { it.contains("TrustedReviewAnswerRule", ignoreCase = true) })
    }

    @Test
    fun savedAnswerCapabilityExposesOnlySubmissionAndAnswerFreeLeaseFacts() {
        val portMethods = LearnerBoundStudentTrustedSavedAnswerRulePort::class.java.methods
            .map { it.name.lowercase() }
        val leaseMembers = StudentTrustedSavedAnswerLease::class.java.methods
            .map { it.name.lowercase() }

        assertTrue(portMethods.any { it.contains("issueexactlease") })
        assertTrue(portMethods.any { it.contains("submitresponse") })
        assertFalse(portMethods.any { it.contains("readexact") || it.contains("answerrule") })
        assertFalse(leaseMembers.any { it.contains("correct") || it.contains("answerrule") })
        val publicOwnerMethods =
            listOf(
                StudentMistakeRuntimeCapabilities::class.java,
                CoreDataStudentMistakeOwnerBridge::class.java,
            ).flatMap { type ->
                type.declaredMethods.filter { method ->
                    java.lang.reflect.Modifier.isPublic(method.modifiers)
                }
            }
        assertFalse(
            publicOwnerMethods.any { method ->
                method.returnType == StudentTrustedReviewAnswerRule::class.java ||
                    method.parameterTypes.any { it == StudentTrustedReviewAnswerRule::class.java }
            },
        )
    }
}
