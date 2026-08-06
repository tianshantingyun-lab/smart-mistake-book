package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentReviewResponseBindingIssuerTest {
    private val key =
        SecretKeySpec(
            "device-private-test-key-that-is-not-a-dictionary".toByteArray(StandardCharsets.UTF_8),
            "HmacSHA256",
        )

    @Test
    fun sameAnswerCannotBeLinkedAcrossLearnerQuestionOrAttemptScope() {
        val base = scope(revision = revision("learner-a", "problem-a"), attemptOrdinal = 1)
        val otherLearner =
            scope(revision = revision("learner-b", "problem-a"), attemptOrdinal = 1)
        val otherQuestion =
            scope(revision = revision("learner-a", "problem-b"), attemptOrdinal = 1)
        val otherAttempt =
            scope(revision = revision("learner-a", "problem-a"), attemptOrdinal = 2)
        val canonicalAnswer = "choice\u001fC"

        val bindings =
            listOf(base, otherLearner, otherQuestion, otherAttempt).map { scopedFingerprint ->
                StudentReviewResponseHmac.issue(
                    key,
                    publicSha256("$scopedFingerprint\u0000$canonicalAnswer"),
                )
            }

        assertEquals(4, bindings.toSet().size)
        assertTrue(bindings.all { it.matches(Regex("[0-9a-f]{64}")) })
        assertEquals(
            bindings.first(),
            StudentReviewResponseHmac.issue(
                key,
                publicSha256("$base\u0000$canonicalAnswer"),
            ),
        )
    }

    @Test
    fun keyedBindingDoesNotExposeAPublicAnswerDictionary() {
        val binding =
            StudentReviewResponseHmac.issue(
                key,
                publicSha256(
                    scope(revision = revision("learner-a", "problem-a"), attemptOrdinal = 1) +
                        "\u0000choice\u001fC",
                ),
            )
        val publicDictionary =
            listOf("A", "B", "C", "D", "choice\u001fA", "choice\u001fB", "choice\u001fC", "choice\u001fD")
                .map(::publicSha256)

        assertFalse(binding in publicDictionary)
        assertTrue(binding.matches(Regex("[0-9a-f]{64}")))
    }

    private fun scope(
        revision: StudentProblemRevisionRef,
        attemptOrdinal: Int,
    ): String =
        studentReviewResponseBindingScopeFingerprint(
            learnerId = revision.problem.learnerId,
            problemRevision = revision,
            reviewSessionId = "session-${revision.problem.learnerId}",
            reviewQueueItemId = "queue-${revision.problem.problemId}",
            presentationId = "presentation-$attemptOrdinal",
            attemptOrdinal = attemptOrdinal,
            submissionId = "submission-$attemptOrdinal",
            responseForm = ReviewResponseForm.CHOICE,
        )

    private fun revision(
        learnerId: String,
        problemId: String,
    ): StudentProblemRevisionRef =
        StudentProblemRevisionRef(
            problem =
                StudentProblemRef(
                    learnerId = learnerId,
                    subject = SubjectKind.MATH,
                    problemId = problemId,
                    practiceUnitId = "unit-$problemId",
                ),
            revisionId = "revision-$problemId",
            revisionNumber = 1,
            documentCanonicalFingerprint = publicSha256("$learnerId:$problemId"),
        )

    private fun publicSha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
