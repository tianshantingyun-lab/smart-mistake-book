package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentTrustedReviewAnswerOwnerTest {
    @Test
    fun savedAnswerLeaseCanBeReissuedForExactContentWithStableEvaluationBinding() = runBlocking {
        val now = AtomicLong(1_000L)
        val receiptSequence = AtomicInteger()
        val contentBinding = TRUSTED_SAVED_ANSWER_PRESENTATION_V2_PREFIX + "a".repeat(64)
        val saved = StudentTrustedSavedAnswerRule(
            problemRevision = REVISION,
            errorBookEntryId = "entry-trusted-review",
            questionGeneration = 1L,
            questionVersion = contentBinding,
            answerRule = StudentTrustedReviewAnswerRule.Choice(
                acceptedChoiceIds = setOf("A", "B"),
                correctChoiceId = "B",
                answerSpecVersion = "choice-v2",
            ),
            provenanceCanonicalFingerprint = "b".repeat(64),
        )
        val owner = StudentTrustedSavedAnswerRuleOwner(
            learnerId = LEARNER_ID,
            persistence = StudentTrustedSavedAnswerRulePersistencePort { learner, revision, entry ->
                saved.takeIf {
                    learner == LEARNER_ID &&
                        revision == REVISION.revisionId &&
                        entry == saved.errorBookEntryId
                }
            },
            responseBindingIssuerProvider = {
                object : StudentReviewResponseBindingIssuer() {
                    override fun algorithmVersion(): String = "test-keyed-v1"

                    override fun issue(
                        scopeCanonicalFingerprint: String,
                        canonicalResponse: String,
                    ): String = "c".repeat(64)
                }
            },
            nowEpochMillis = now::get,
            newReceiptId = { "saved-lease-${receiptSequence.incrementAndGet()}" },
        )
        val request = StudentTrustedSavedAnswerPresentationRequest(
            problemRevisionId = REVISION.revisionId,
            errorBookEntryId = saved.errorBookEntryId,
            questionGeneration = saved.questionGeneration,
            questionVersion = contentBinding,
            responseForm = ReviewResponseForm.CHOICE,
        )
        val expiredLease = checkNotNull(owner.issueExactLease(request))
        now.set(expiredLease.validThroughEpochMillis + 1L)
        assertEquals(
            StudentTrustedSavedAnswerSubmissionResult.Rejected,
            owner.submitResponse(expiredLease, StudentTrustedReviewResponse.Choice("B")),
        )

        val first = owner.submitResponse(
            checkNotNull(owner.issueExactLease(request)),
            StudentTrustedReviewResponse.Choice("B"),
        ) as StudentTrustedSavedAnswerSubmissionResult.Accepted
        val reissued = owner.submitResponse(
            checkNotNull(owner.issueExactLease(request)),
            StudentTrustedReviewResponse.Choice("B"),
        ) as StudentTrustedSavedAnswerSubmissionResult.Accepted

        assertTrue(first.evaluation.selectionWasCorrect)
        assertEquals(first.evaluation.responseBinding, reissued.evaluation.responseBinding)
        assertEquals(first.evaluation.canonicalFingerprint, reissued.evaluation.canonicalFingerprint)
    }

    @Test
    fun ownerIssuesRuleFreeLeaseAndSubmitsRawResponseThroughOwner() = runBlocking {
        val persistence = FakeTrustedReviewPersistence()
        val times = ArrayDeque(listOf(1_100L, 1_120L, 1_150L, 1_180L))
        val owner =
            StudentTrustedReviewAnswerOwner(
                learnerId = LEARNER_ID,
                persistence = persistence,
                nowEpochMillis = { times.removeFirst() },
                newReceiptId = { "lease-receipt-1" },
            )

        val lease = requireNotNull(owner.issueCurrentLease())
        assertEquals(REVISION, lease.problemRevision)
        assertEquals("choice-v1", lease.questionVersion)
        assertTrue(
            StudentTrustedReviewAnswerLease::class.java.declaredMethods.none {
                it.name.contains("answerRule", ignoreCase = true)
            },
        )

        assertEquals(
            StudentTrustedReviewAssistanceResult.Recorded,
            owner.recordAssistance(
                lease = lease,
                assistanceEventId = "hint-1",
                kind = StudentTrustedReviewAssistanceKind.HINT,
            ),
        )
        assertEquals(
            StudentTrustedReviewAssistanceResult.Recorded,
            owner.recordAssistance(
                lease = lease,
                assistanceEventId = "reveal-1",
                kind = StudentTrustedReviewAssistanceKind.ANSWER_REVEAL,
            ),
        )

        assertEquals(
            StudentTrustedReviewSubmissionResult.Recorded(
                duplicate = false,
                resultingSessionVersion = 4,
            ),
            owner.submitResponse(
                lease,
                StudentTrustedReviewResponse.Choice("C"),
            ),
        )
        assertEquals(StudentTrustedReviewResponse.Choice("C"), persistence.lastResponse)
        assertEquals(1_180L, persistence.lastSubmittedAt)
    }

    @Test
    fun leaseIsOneShotAndAnUnissuedLookalikeFailsClosed() = runBlocking {
        val persistence = FakeTrustedReviewPersistence()
        var now = 1_100L
        val owner =
            StudentTrustedReviewAnswerOwner(
                learnerId = LEARNER_ID,
                persistence = persistence,
                nowEpochMillis = { now++ },
                newReceiptId = { "lease-receipt-2" },
            )
        val issued = requireNotNull(owner.issueCurrentLease())
        val forged =
            StudentTrustedReviewAnswerLease(
                planId = issued.planId,
                sessionId = issued.sessionId,
                queueItemId = issued.queueItemId,
                expectedSessionVersion = issued.expectedSessionVersion,
                presentationId = issued.presentationId,
                problemRevision = issued.problemRevision,
                errorBookEntryId = issued.errorBookEntryId,
                questionGeneration = issued.questionGeneration,
                questionVersion = issued.questionVersion,
                issuedAtEpochMillis = issued.issuedAtEpochMillis,
                validThroughEpochMillis = issued.validThroughEpochMillis,
                canonicalFingerprint = issued.canonicalFingerprint,
                receiptId = issued.receiptId,
            )

        assertEquals(
            StudentTrustedReviewSubmissionResult.ReloadRequired,
            owner.submitResponse(forged, StudentTrustedReviewResponse.Choice("C")),
        )
        assertTrue(
            owner.submitResponse(
                issued,
                StudentTrustedReviewResponse.Choice("C"),
            ) is StudentTrustedReviewSubmissionResult.Recorded,
        )
        assertEquals(
            StudentTrustedReviewSubmissionResult.ReloadRequired,
            owner.submitResponse(issued, StudentTrustedReviewResponse.Choice("C")),
        )
        assertEquals(
            StudentTrustedReviewAssistanceResult.Unavailable,
            owner.recordAssistance(
                lease = issued,
                assistanceEventId = "late-hint",
                kind = StudentTrustedReviewAssistanceKind.HINT,
            ),
        )
    }

    @Test
    fun missingTrustedRuleRemainsUnavailable() = runBlocking {
        val persistence = FakeTrustedReviewPersistence(leaseAvailable = false)
        val owner =
            StudentTrustedReviewAnswerOwner(
                learnerId = LEARNER_ID,
                persistence = persistence,
                nowEpochMillis = { 1_100L },
                newReceiptId = { "lease-unavailable" },
            )

        assertNull(owner.issueCurrentLease())
        assertEquals(0, persistence.submitCount)
    }

    @Test
    fun assistanceWriteFailureRevokesTheLeaseAndRecreationCannotReissueTheBoundFence() =
        runBlocking {
            val persistence = DurableBoundFencePersistence()
            val firstOwner =
                StudentTrustedReviewAnswerOwner(
                    learnerId = LEARNER_ID,
                    persistence = persistence,
                    nowEpochMillis = { 1_100L },
                    newReceiptId = { "lease-before-crash" },
                )
            val exposedLease = requireNotNull(firstOwner.issueCurrentLease())

            assertEquals(
                StudentTrustedReviewAssistanceResult.Unavailable,
                firstOwner.recordAssistance(
                    lease = exposedLease,
                    assistanceEventId = "explanation-was-shown",
                    kind = StudentTrustedReviewAssistanceKind.ANSWER_REVEAL,
                ),
            )
            assertEquals(
                StudentTrustedReviewSubmissionResult.ReloadRequired,
                firstOwner.submitResponse(
                    exposedLease,
                    StudentTrustedReviewResponse.Choice("C"),
                ),
            )

            val recreatedOwner =
                StudentTrustedReviewAnswerOwner(
                    learnerId = LEARNER_ID,
                    persistence = persistence,
                    nowEpochMillis = { 1_200L },
                    newReceiptId = { "lease-after-recreation" },
                )
            assertNull(recreatedOwner.issueCurrentLease())
            assertEquals(1, persistence.leaseGrantCount)
            assertEquals(0, persistence.attemptClaimCount)
        }

    @Test
    fun allSupportedRulesAreStructuredAndFingerprintStable() {
        val choice =
            StudentTrustedReviewAnswerRule.Choice(
                acceptedChoiceIds = setOf("D", "B", "A", "C"),
                correctChoiceId = "C",
                answerSpecVersion = "answer-v1",
            )
        val sameChoice =
            StudentTrustedReviewAnswerRule.Choice(
                acceptedChoiceIds = linkedSetOf("A", "B", "C", "D"),
                correctChoiceId = "C",
                answerSpecVersion = "answer-v1",
            )
        val numeric =
            StudentTrustedReviewAnswerRule.Numeric(
                expectedValue = BigDecimal("2.500"),
                absoluteTolerance = BigDecimal("0.05"),
                expectedUnit = "m/s",
                answerSpecVersion = "answer-v2",
            )
        val visual =
            StudentTrustedReviewAnswerRule.VisualTarget(
                acceptedTargetIds = setOf("left", "right", "centre"),
                correctTargetIds = setOf("centre"),
                answerSpecVersion = "answer-v3",
            )

        assertEquals(choice.canonicalFingerprint, sameChoice.canonicalFingerprint)
        assertTrue(numeric.canonicalFingerprint.matches(SHA_256))
        assertTrue(visual.canonicalFingerprint.matches(SHA_256))
    }

    @Test
    fun v18KeepsTheEmptyFenceAndNeverAuthorizesLegacyPresentations() {
        val fenceMigration =
            readProjectFile(
                "src/main/kotlin/com/tingyun/smartmistakebook/core/student/mistake/database/" +
                    "StudentMistakeMigration14To15.kt",
            )
        val confidenceRemovalMigration =
            readProjectFile(
                "src/main/kotlin/com/tingyun/smartmistakebook/core/student/mistake/database/" +
                    "StudentMistakeMigration15To16.kt",
            )
        val certificateMigration =
            readProjectFile(
                "src/main/kotlin/com/tingyun/smartmistakebook/core/student/mistake/database/" +
                    "StudentMistakeMigration19To20.kt",
            )
        assertEquals(20, STUDENT_MISTAKE_DATABASE_VERSION)
        assertEquals(14, STUDENT_MISTAKE_MIGRATION_14_15.startVersion)
        assertEquals(15, STUDENT_MISTAKE_MIGRATION_14_15.endVersion)
        assertEquals(15, STUDENT_MISTAKE_MIGRATION_15_16.startVersion)
        assertEquals(16, STUDENT_MISTAKE_MIGRATION_15_16.endVersion)
        assertEquals(16, STUDENT_MISTAKE_MIGRATION_16_17.startVersion)
        assertEquals(17, STUDENT_MISTAKE_MIGRATION_16_17.endVersion)
        assertEquals(17, STUDENT_MISTAKE_MIGRATION_17_18.startVersion)
        assertEquals(18, STUDENT_MISTAKE_MIGRATION_17_18.endVersion)
        assertEquals(18, STUDENT_MISTAKE_MIGRATION_18_19.startVersion)
        assertEquals(19, STUDENT_MISTAKE_MIGRATION_18_19.endVersion)
        assertEquals(19, STUDENT_MISTAKE_MIGRATION_19_20.startVersion)
        assertEquals(20, STUDENT_MISTAKE_MIGRATION_19_20.endVersion)
        assertTrue("deliberately not backfilled" in fenceMigration)
        assertTrue("student_trusted_review_presentation_fence" in fenceMigration)
        assertTrue(
            "INSERT INTO `student_trusted_review_presentation_fence`" !in fenceMigration,
        )
        assertTrue(
            "INSERT INTO `student_trusted_review_presentation_fence`" !in
                confidenceRemovalMigration,
        )
        assertTrue("must not manufacture reattestation receipts" in confidenceRemovalMigration)
        assertTrue("student_tutor_interaction_answer_certificate" in certificateMigration)
        assertTrue("INSERT INTO" !in certificateMigration)
    }

    @Test
    fun v15FenceSchemaAndDaosKeepOneExactAtomicStrongEvidencePath() {
        val migration =
            readProjectFile(
                "src/main/kotlin/com/tingyun/smartmistakebook/core/student/mistake/database/" +
                    "StudentMistakeMigration14To15.kt",
            )
        val sessionDao =
            readProjectFile(
                "src/main/kotlin/com/tingyun/smartmistakebook/core/student/mistake/database/" +
                    "StudentReviewSessionWriteDao.kt",
            )
        val answerDao =
            readProjectFile(
                "src/main/kotlin/com/tingyun/smartmistakebook/core/student/mistake/database/" +
                    "StudentTrustedReviewAnswerDao.kt",
            )

        listOf(
            "`fence_id` TEXT NOT NULL",
            "`fence_canonical_fingerprint` TEXT NOT NULL",
            "`learner_id` TEXT NOT NULL",
            "`plan_id` TEXT NOT NULL",
            "`session_id` TEXT NOT NULL",
            "`queue_item_id` TEXT NOT NULL",
            "`presentation_id` TEXT NOT NULL",
            "`problem_id` TEXT NOT NULL",
            "`basis_revision_id` TEXT NOT NULL",
            "`practice_unit_id` TEXT NOT NULL",
            "`error_book_entry_id` TEXT NOT NULL",
            "`status` TEXT NOT NULL",
            "`bound_lease_receipt_id` TEXT",
            "`bound_lease_canonical_fingerprint` TEXT",
            "`eligible_attempt_receipt_id` TEXT",
            "`eligible_at_epoch_millis` INTEGER",
            "PRIMARY KEY(`fence_id`)",
        ).forEach { schemaContract ->
            assertTrue("Missing v15 fence schema contract: $schemaContract", schemaContract in migration)
        }
        assertTrue("INSERT INTO `student_trusted_review_presentation_fence`" !in migration)

        val sessionStart =
            sessionDao
                .substringAfter("open suspend fun startOrResumeReviewSession(")
                .substringBefore("open suspend fun applyReviewTransition(")
        val sessionTransition =
            sessionDao
                .substringAfter("open suspend fun applyReviewTransition(")
                .substringBefore("protected suspend fun insertTrustedReviewFenceIfExact(")
        assertTrue("insertTrustedReviewFenceIfExact(" in sessionStart)
        assertTrue("insertTrustedReviewFenceIfExact(" in sessionTransition)

        listOf(
            "fence.learner_id = session.learner_id",
            "fence.plan_id = session.plan_id",
            "fence.session_id = session.session_id",
            "fence.queue_item_id = queue.queue_item_id",
            "fence.presentation_id = session.current_presentation_id",
            "fence.problem_id = document.problem_id",
            "fence.basis_revision_id = queue.basis_revision_id",
            "fence.practice_unit_id = queue.practice_unit_id",
            "fence.error_book_entry_id = document.error_book_entry_id",
            "session.session_version = lease.expected_session_version",
            "fence.status = 'PENDING'",
            "fence.bound_lease_receipt_id IS NULL",
            "fence.bound_lease_canonical_fingerprint IS NULL",
            "fence.eligible_attempt_receipt_id IS NULL",
            "fence.eligible_at_epoch_millis IS NULL",
            "bound_lease_receipt_id = :leaseReceiptId",
            "bound_lease_canonical_fingerprint = :leaseCanonicalFingerprint",
            "status = 'STRONG_EVIDENCE_ELIGIBLE'",
            "eligible_attempt_receipt_id = :attemptReceiptId",
            "CanonicalSha256(\"student-trusted-review-lease-receipt-v2\")",
            ".field(\"learnerId\", learnerId)",
            ".field(\"subject\", subject)",
            ".field(\"revisionNumber\", revisionNumber)",
            ".field(\"documentCanonicalFingerprint\", documentCanonicalFingerprint)",
            ".field(\"errorBookEntryId\", errorBookEntryId)",
            ".field(\"questionGeneration\", questionGeneration)",
            ".field(\"questionVersion\", questionVersion)",
        ).forEach { daoContract ->
            assertTrue("Missing exact fence DAO contract: $daoContract", daoContract in answerDao)
        }

        val issueLease =
            answerDao
                .substringAfter("override suspend fun issueCurrentLease(")
                .substringBefore("override suspend fun claimAttempt(")
        assertTrue(issueLease.indexOf("insertLease(") < issueLease.indexOf("bindPresentationFence("))
        val claimAttempt =
            answerDao
                .substringAfter("override suspend fun claimAttempt(")
                .substringBefore("override suspend fun recordAssistance(")
        assertTrue(
            "if (assistance.hintCount == 0 && assistance.answerRevealedAtEpochMillis == null)" in
                claimAttempt,
        )
        assertTrue(claimAttempt.indexOf("insertAttempt(entity)") < claimAttempt.indexOf("promotePresentationFence("))
    }
}

private class DurableBoundFencePersistence : StudentTrustedReviewAnswerPersistencePort {
    var leaseGrantCount: Int = 0
    var attemptClaimCount: Int = 0
    private var fenceBound: Boolean = false

    override suspend fun issueCurrentLease(
        learnerId: String,
        receiptId: String,
        issuedAtEpochMillis: Long,
        validThroughEpochMillis: Long,
    ): PersistedStudentTrustedReviewLease? {
        if (fenceBound) return null
        fenceBound = true
        leaseGrantCount += 1
        return persistedLease(
            receiptId = receiptId,
            issuedAtEpochMillis = issuedAtEpochMillis,
            validThroughEpochMillis = validThroughEpochMillis,
        )
    }

    override suspend fun claimAttempt(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        submittedAtEpochMillis: Long,
    ): StudentTrustedReviewAttemptSnapshot? {
        attemptClaimCount += 1
        return null
    }

    override suspend fun submitResponse(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        response: StudentTrustedReviewResponse,
        submittedAtEpochMillis: Long,
    ): StudentTrustedReviewSubmissionResult {
        attemptClaimCount += 1
        return StudentTrustedReviewSubmissionResult.ReloadRequired
    }

    override suspend fun recordAssistance(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        assistanceEventId: String,
        kind: StudentTrustedReviewAssistanceKind,
        occurredAtEpochMillis: Long,
    ): StudentTrustedReviewAssistanceResult =
        error("Simulated crash after explanation presentation")
}

private fun persistedLease(
    receiptId: String,
    issuedAtEpochMillis: Long,
    validThroughEpochMillis: Long,
) =
    PersistedStudentTrustedReviewLease(
        receiptId = receiptId,
        planId = "plan-1",
        sessionId = "session-1",
        queueItemId = "queue-1",
        expectedSessionVersion = 3,
        presentationId = "presentation-1",
        problemRevision = REVISION,
        errorBookEntryId = "error-book-trusted-review",
        questionGeneration = 2,
        questionVersion = "choice-v1",
        answerRule =
            StudentTrustedReviewAnswerRule.Choice(
                acceptedChoiceIds = setOf("A", "B", "C", "D"),
                correctChoiceId = "C",
                answerSpecVersion = "answer-v1",
            ),
        issuedAtEpochMillis = issuedAtEpochMillis,
        validThroughEpochMillis = validThroughEpochMillis,
        canonicalFingerprint = "a".repeat(64),
    )

private class FakeTrustedReviewPersistence(
    private val leaseAvailable: Boolean = true,
) : StudentTrustedReviewAnswerPersistencePort {
    var submitCount: Int = 0
    var lastResponse: StudentTrustedReviewResponse? = null
    var lastSubmittedAt: Long? = null
    private val assistance = mutableListOf<Pair<StudentTrustedReviewAssistanceKind, Long>>()

    override suspend fun issueCurrentLease(
        learnerId: String,
        receiptId: String,
        issuedAtEpochMillis: Long,
        validThroughEpochMillis: Long,
    ): PersistedStudentTrustedReviewLease? {
        if (!leaseAvailable) return null
        val rule =
            StudentTrustedReviewAnswerRule.Choice(
                acceptedChoiceIds = setOf("A", "B", "C", "D"),
                correctChoiceId = "C",
                answerSpecVersion = "answer-v1",
            )
        return PersistedStudentTrustedReviewLease(
            receiptId = receiptId,
            planId = "plan-1",
            sessionId = "session-1",
            queueItemId = "queue-1",
            expectedSessionVersion = 3,
            presentationId = "presentation-1",
            problemRevision = REVISION,
            errorBookEntryId = "error-book-trusted-review",
            questionGeneration = 2,
            questionVersion = "choice-v1",
            answerRule = rule,
            issuedAtEpochMillis = issuedAtEpochMillis,
            validThroughEpochMillis = validThroughEpochMillis,
            canonicalFingerprint = "a".repeat(64),
        )
    }

    override suspend fun claimAttempt(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        submittedAtEpochMillis: Long,
    ): StudentTrustedReviewAttemptSnapshot {
        val claimCount = submitCount + 1
        val hints =
            assistance
                .filter { it.first == StudentTrustedReviewAssistanceKind.HINT }
                .map(Pair<StudentTrustedReviewAssistanceKind, Long>::second)
        val revealedAt =
            assistance
                .firstOrNull {
                    it.first == StudentTrustedReviewAssistanceKind.ANSWER_REVEAL
                }?.second
        val fingerprint = "b".repeat(64)
        return StudentTrustedReviewAttemptSnapshot(
            leaseCanonicalFingerprint = leaseCanonicalFingerprint,
            attemptOrdinal = claimCount,
            retryCount = claimCount - 1,
            presentationStartedAtEpochMillis = 1_000L,
            submittedAtEpochMillis = submittedAtEpochMillis,
            elapsedDurationMillis = submittedAtEpochMillis - 1_000L,
            hintCount = hints.size,
            firstHintAtEpochMillis = hints.minOrNull(),
            lastHintAtEpochMillis = hints.maxOrNull(),
            answerWasRevealed = revealedAt != null,
            answerRevealedAtEpochMillis = revealedAt,
            submissionIdempotencyKey = "trusted-review-submit:$fingerprint",
            canonicalFingerprint = fingerprint,
        )
    }

    override suspend fun submitResponse(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        response: StudentTrustedReviewResponse,
        submittedAtEpochMillis: Long,
    ): StudentTrustedReviewSubmissionResult {
        submitCount += 1
        lastResponse = response
        lastSubmittedAt = submittedAtEpochMillis
        return StudentTrustedReviewSubmissionResult.Recorded(
            duplicate = submitCount > 1,
            resultingSessionVersion = 4,
        )
    }

    override suspend fun recordAssistance(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        assistanceEventId: String,
        kind: StudentTrustedReviewAssistanceKind,
        occurredAtEpochMillis: Long,
    ): StudentTrustedReviewAssistanceResult {
        assistance += kind to occurredAtEpochMillis
        return StudentTrustedReviewAssistanceResult.Recorded
    }
}

private fun readProjectFile(relativePath: String): String {
    val candidates =
        listOf(
            Paths.get(relativePath),
            Paths.get("core", "student-mistake-database").resolve(relativePath),
        )
    val path: Path =
        candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate project file $relativePath")
    return Files.readString(path)
}

private const val LEARNER_ID = "learner-trusted-review"
private val SHA_256 = Regex("[0-9a-f]{64}")
private val REVISION =
    StudentProblemRevisionRef(
        problem =
            StudentProblemRef(
                learnerId = LEARNER_ID,
                subject = SubjectKind.MATH,
                problemId = "problem-trusted-review",
                practiceUnitId = "practice-trusted-review",
            ),
        revisionId = "revision-trusted-review",
        revisionNumber = 1,
        documentCanonicalFingerprint = "c".repeat(64),
    )
