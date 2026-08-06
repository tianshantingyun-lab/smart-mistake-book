package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.ColumnInfo
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV2
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.model.storage.ReviewVerificationOutcome
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import java.math.BigDecimal

internal enum class StudentTrustedReviewAnswerProvenanceKind {
    TRUSTED_SOURCE_GRADING_KEY,
    INDEPENDENT_HUMAN_REVIEW,
    DETERMINISTIC_VALIDATION,
    USER_OR_TEACHER_CONFIRMATION,
    INDEPENDENT_TRUSTED_REVIEW,
}

internal data class AdmitStudentTrustedReviewAnswerRuleCommand(
    val answerRuleId: String,
    val problemRevision: StudentProblemRevisionRef,
    val errorBookEntryId: String,
    val questionGeneration: Long,
    val questionVersion: String,
    val answerRule: StudentTrustedReviewAnswerRule,
    val provenanceKind: StudentTrustedReviewAnswerProvenanceKind,
    val provenanceReferenceId: String,
    val provenanceCanonicalFingerprint: String,
    val admittedAtEpochMillis: Long,
) {
    init {
        answerRuleId.requireStoreText("Trusted review answer-rule id", MAX_ID_CHARS)
        errorBookEntryId.requireStoreText(
            "Trusted review answer-rule error-book entry id",
            MAX_ID_CHARS,
        )
        require(questionGeneration > 0) {
            "Trusted review question generation must be positive"
        }
        questionVersion.requireStoreText("Trusted review question version", MAX_VERSION_CHARS)
        provenanceReferenceId.requireStoreText(
            "Trusted review provenance reference",
            MAX_ID_CHARS,
        )
        requireSha256(
            provenanceCanonicalFingerprint,
            "Trusted review provenance fingerprint",
        )
        require(admittedAtEpochMillis >= 0) {
            "Trusted review answer-rule admission time must not be negative"
        }
    }

    val canonicalFingerprint: String =
        CanonicalSha256("student-trusted-review-answer-rule-admission-v2")
            .field("answerRuleId", answerRuleId)
            .field("problemRevision", problemRevision.canonicalFingerprint)
            .field("errorBookEntryId", errorBookEntryId)
            .field("questionGeneration", questionGeneration)
            .field("questionVersion", questionVersion)
            .field("answerRule", answerRule.canonicalFingerprint)
            .field("provenanceKind", provenanceKind.name)
            .field("provenanceReferenceId", provenanceReferenceId)
            .field("provenanceCanonicalFingerprint", provenanceCanonicalFingerprint)
            .field("admittedAtEpochMillis", admittedAtEpochMillis)
            .finish()
}

internal sealed interface AdmitStudentTrustedReviewAnswerRuleResult {
    data object Admitted : AdmitStudentTrustedReviewAnswerRuleResult

    data object Duplicate : AdmitStudentTrustedReviewAnswerRuleResult

    data object Unavailable : AdmitStudentTrustedReviewAnswerRuleResult
}

/**
 * Package-internal admission seam. It is deliberately absent from runtime capabilities exposed to
 * feature and model code.
 */
internal fun interface StudentTrustedReviewAnswerRuleAdmissionPort {
    suspend fun admit(
        command: AdmitStudentTrustedReviewAnswerRuleCommand,
    ): AdmitStudentTrustedReviewAnswerRuleResult
}

@Dao
internal abstract class StudentTrustedReviewAnswerDao :
    StudentTrustedReviewAnswerPersistencePort,
    StudentTrustedReviewAnswerRuleAdmissionPort,
    StudentTrustedSavedAnswerRulePersistencePort {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRule(entity: StudentTrustedReviewAnswerRuleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertLease(entity: StudentTrustedReviewLeaseReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAttempt(entity: StudentTrustedReviewAttemptReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertAssistance(
        entity: StudentTrustedReviewAssistanceReceiptEntity,
    ): Long

    @Query(
        """
        SELECT *
        FROM student_trusted_review_answer_rule
        WHERE answer_rule_id = :answerRuleId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readRuleById(
        answerRuleId: String,
    ): StudentTrustedReviewAnswerRuleEntity?

    @Query(
        """
        SELECT *
        FROM student_trusted_review_lease_receipt
        WHERE lease_receipt_id = :leaseReceiptId
          AND learner_id = :learnerId
          AND lease_canonical_fingerprint = :leaseCanonicalFingerprint
        LIMIT 1
        """,
    )
    protected abstract suspend fun readLeaseReceipt(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
    ): StudentTrustedReviewLeaseReceiptEntity?

    @Query(
        """
        SELECT *
        FROM student_trusted_review_answer_rule
        WHERE basis_revision_id = :revisionId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readRuleByRevision(
        revisionId: String,
    ): StudentTrustedReviewAnswerRuleEntity?

    @Query(
        """
        SELECT
            document.problem_id AS problem_id,
            document.learner_id AS learner_id,
            document.subject AS subject,
            document.primary_practice_unit_id AS practice_unit_id,
            revision.revision_id AS revision_id,
            revision.revision_number AS revision_number,
            revision.document_canonical_fingerprint AS document_canonical_fingerprint,
            document.error_book_entry_id AS error_book_entry_id,
            rule.question_generation AS question_generation,
            rule.question_version AS question_version,
            rule.rule_kind AS rule_kind,
            rule.accepted_values_wire AS accepted_values_wire,
            rule.correct_values_wire AS correct_values_wire,
            rule.expected_numeric_value AS expected_numeric_value,
            rule.absolute_tolerance AS absolute_tolerance,
            rule.expected_unit AS expected_unit,
            rule.answer_spec_version AS answer_spec_version,
            rule.rule_canonical_fingerprint AS rule_canonical_fingerprint,
            rule.provenance_canonical_fingerprint AS provenance_canonical_fingerprint
        FROM student_trusted_review_answer_rule AS rule
        INNER JOIN student_problem_revision AS revision
          ON revision.revision_id = rule.basis_revision_id
         AND revision.problem_id = rule.problem_id
        INNER JOIN student_problem_document AS document
          ON document.problem_id = revision.problem_id
         AND document.learner_id = rule.learner_id
        INNER JOIN student_problem_collection AS collection
          ON collection.problem_id = document.problem_id
         AND collection.practice_unit_id = document.primary_practice_unit_id
         AND collection.learner_id = document.learner_id
        WHERE rule.learner_id = :learnerId
          AND rule.basis_revision_id = :problemRevisionId
          AND document.error_book_entry_id = :errorBookEntryId
          AND document.current_revision_id = :problemRevisionId
          AND document.lifecycle_state = 'ACTIVE'
          AND collection.mistake_state = 'ACTIVE'
        LIMIT 1
        """,
    )
    protected abstract suspend fun readExactSavedAnswerRow(
        learnerId: String,
        problemRevisionId: String,
        errorBookEntryId: String,
    ): StudentTrustedSavedAnswerRuleRow?

    @Transaction
    override suspend fun readExact(
        learnerId: String,
        problemRevisionId: String,
        errorBookEntryId: String,
    ): StudentTrustedSavedAnswerRule? =
        readExactSavedAnswerRow(learnerId, problemRevisionId, errorBookEntryId)?.toDomain()

    @Query(
        """
        SELECT
            document.problem_id AS problem_id,
            document.learner_id AS learner_id,
            document.subject AS subject,
            document.primary_practice_unit_id AS practice_unit_id,
            document.current_revision_id AS current_revision_id,
            revision.revision_number AS revision_number,
            revision.document_canonical_fingerprint AS document_canonical_fingerprint,
            document.lifecycle_state AS lifecycle_state,
            document.error_book_entry_id AS error_book_entry_id,
            collection.mistake_state AS mistake_state
        FROM student_problem_revision AS revision
        INNER JOIN student_problem_document AS document
            ON document.problem_id = revision.problem_id
        INNER JOIN student_problem_collection AS collection
            ON collection.practice_unit_id = document.primary_practice_unit_id
           AND collection.problem_id = document.problem_id
           AND collection.learner_id = document.learner_id
        WHERE revision.revision_id = :revisionId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readRuleAdmissionTarget(
        revisionId: String,
    ): StudentTrustedReviewRuleAdmissionTargetRow?

    @Transaction
    override suspend fun admit(
        command: AdmitStudentTrustedReviewAnswerRuleCommand,
    ): AdmitStudentTrustedReviewAnswerRuleResult {
        readRuleById(command.answerRuleId)?.let { existing ->
            return if (existing == command.toEntity()) {
                AdmitStudentTrustedReviewAnswerRuleResult.Duplicate
            } else {
                AdmitStudentTrustedReviewAnswerRuleResult.Unavailable
            }
        }
        if (readRuleByRevision(command.problemRevision.revisionId) != null) {
            return AdmitStudentTrustedReviewAnswerRuleResult.Unavailable
        }
        val target =
            readRuleAdmissionTarget(command.problemRevision.revisionId)
                ?: return AdmitStudentTrustedReviewAnswerRuleResult.Unavailable
        if (
            !target.matches(
                expected = command.problemRevision,
                expectedErrorBookEntryId = command.errorBookEntryId,
                admittedAtEpochMillis = command.admittedAtEpochMillis,
            )
        ) {
            return AdmitStudentTrustedReviewAnswerRuleResult.Unavailable
        }
        insertRule(command.toEntity())
        return AdmitStudentTrustedReviewAnswerRuleResult.Admitted
    }

    @Query(
        """
        SELECT
            session.plan_id AS plan_id,
            session.session_id AS session_id,
            session.session_version AS session_version,
            session.current_presentation_id AS presentation_id,
            session.updated_at_epoch_millis AS presentation_started_at_epoch_millis,
            queue.queue_item_id AS queue_item_id,
            queue.basis_revision_id AS basis_revision_id,
            queue.practice_unit_id AS practice_unit_id,
            session.learner_id AS learner_id,
            revision.problem_id AS problem_id,
            revision.revision_number AS revision_number,
            revision.document_canonical_fingerprint AS document_canonical_fingerprint,
            document.subject AS subject,
            document.error_book_entry_id AS error_book_entry_id,
            fence.fence_id AS fence_id,
            fence.fence_canonical_fingerprint AS fence_canonical_fingerprint,
            rule.answer_rule_id AS answer_rule_id,
            rule.question_generation AS question_generation,
            rule.question_version AS question_version,
            rule.rule_kind AS rule_kind,
            rule.accepted_values_wire AS accepted_values_wire,
            rule.correct_values_wire AS correct_values_wire,
            rule.expected_numeric_value AS expected_numeric_value,
            rule.absolute_tolerance AS absolute_tolerance,
            rule.expected_unit AS expected_unit,
            rule.answer_spec_version AS answer_spec_version,
            rule.rule_canonical_fingerprint AS rule_canonical_fingerprint
        FROM student_review_session AS session
        INNER JOIN student_review_queue_item AS queue
            ON queue.queue_item_id = session.current_queue_item_id
           AND queue.plan_id = session.plan_id
           AND queue.learner_id = session.learner_id
        INNER JOIN student_problem_revision AS revision
            ON revision.revision_id = queue.basis_revision_id
        INNER JOIN student_problem_document AS document
            ON document.problem_id = revision.problem_id
           AND document.learner_id = session.learner_id
        INNER JOIN student_problem_collection AS collection
            ON collection.practice_unit_id = queue.practice_unit_id
           AND collection.problem_id = document.problem_id
           AND collection.learner_id = session.learner_id
        INNER JOIN student_trusted_review_answer_rule AS rule
            ON rule.basis_revision_id = queue.basis_revision_id
           AND rule.problem_id = document.problem_id
           AND rule.learner_id = session.learner_id
        INNER JOIN student_trusted_review_presentation_fence AS fence
            ON fence.learner_id = session.learner_id
           AND fence.plan_id = session.plan_id
           AND fence.session_id = session.session_id
           AND fence.queue_item_id = queue.queue_item_id
           AND fence.presentation_id = session.current_presentation_id
           AND fence.problem_id = document.problem_id
           AND fence.basis_revision_id = queue.basis_revision_id
           AND fence.practice_unit_id = queue.practice_unit_id
           AND fence.error_book_entry_id = document.error_book_entry_id
        WHERE session.learner_id = :learnerId
          AND session.active_learner_id = :learnerId
          AND session.state = 'ACTIVE'
          AND session.current_queue_item_id IS NOT NULL
          AND session.current_presentation_id IS NOT NULL
          AND session.completed_at_epoch_millis IS NULL
          AND queue.state = 'PRESENTED'
          AND document.lifecycle_state = 'ACTIVE'
          AND document.error_book_entry_id IS NOT NULL
          AND document.current_revision_id = queue.basis_revision_id
          AND collection.mistake_state = 'ACTIVE'
          AND fence.status = 'PENDING'
          AND fence.bound_lease_receipt_id IS NULL
          AND fence.bound_lease_canonical_fingerprint IS NULL
          AND fence.eligible_attempt_receipt_id IS NULL
          AND fence.eligible_at_epoch_millis IS NULL
        LIMIT 1
        """,
    )
    protected abstract suspend fun readCurrentLeaseCandidate(
        learnerId: String,
    ): StudentTrustedReviewLeaseCandidateRow?

    @Query(
        """
        UPDATE student_trusted_review_presentation_fence
        SET bound_lease_receipt_id = :leaseReceiptId,
            bound_lease_canonical_fingerprint = :leaseCanonicalFingerprint
        WHERE fence_id = :fenceId
          AND learner_id = :learnerId
          AND presentation_id = :presentationId
          AND basis_revision_id = :basisRevisionId
          AND practice_unit_id = :practiceUnitId
          AND error_book_entry_id = :errorBookEntryId
          AND status = 'PENDING'
          AND bound_lease_receipt_id IS NULL
          AND bound_lease_canonical_fingerprint IS NULL
          AND eligible_attempt_receipt_id IS NULL
          AND eligible_at_epoch_millis IS NULL
        """,
    )
    protected abstract suspend fun bindPresentationFence(
        fenceId: String,
        learnerId: String,
        presentationId: String,
        basisRevisionId: String,
        practiceUnitId: String,
        errorBookEntryId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
    ): Int

    @Transaction
    override suspend fun issueCurrentLease(
        learnerId: String,
        receiptId: String,
        issuedAtEpochMillis: Long,
        validThroughEpochMillis: Long,
    ): PersistedStudentTrustedReviewLease? {
        if (
            issuedAtEpochMillis < 0 ||
            validThroughEpochMillis < issuedAtEpochMillis
        ) {
            return null
        }
        val candidate = readCurrentLeaseCandidate(learnerId) ?: return null
        if (issuedAtEpochMillis < candidate.presentationStartedAtEpochMillis) return null
        val fingerprint =
            candidate.leaseCanonicalFingerprint(
                receiptId = receiptId,
                issuedAtEpochMillis = issuedAtEpochMillis,
                validThroughEpochMillis = validThroughEpochMillis,
            )
        insertLease(
            StudentTrustedReviewLeaseReceiptEntity(
                leaseReceiptId = receiptId,
                leaseCanonicalFingerprint = fingerprint,
                learnerId = learnerId,
                planId = candidate.planId,
                sessionId = candidate.sessionId,
                queueItemId = candidate.queueItemId,
                expectedSessionVersion = candidate.sessionVersion,
                presentationId = candidate.presentationId,
                basisRevisionId = candidate.basisRevisionId,
                answerRuleId = candidate.answerRuleId,
                issuedAtEpochMillis = issuedAtEpochMillis,
                validThroughEpochMillis = validThroughEpochMillis,
            ),
        )
        check(
            bindPresentationFence(
                fenceId = candidate.fenceId,
                learnerId = learnerId,
                presentationId = candidate.presentationId,
                basisRevisionId = candidate.basisRevisionId,
                practiceUnitId = candidate.practiceUnitId,
                errorBookEntryId = candidate.errorBookEntryId,
                leaseReceiptId = receiptId,
                leaseCanonicalFingerprint = fingerprint,
            ) == 1,
        ) {
            "Trusted review presentation fence changed while binding its one live lease"
        }
        return candidate.toPersistedLease(
            receiptId = receiptId,
            issuedAtEpochMillis = issuedAtEpochMillis,
            validThroughEpochMillis = validThroughEpochMillis,
            canonicalFingerprint = fingerprint,
        )
    }

    @Query(
        """
        SELECT
            lease.lease_receipt_id AS lease_receipt_id,
            lease.lease_canonical_fingerprint AS lease_canonical_fingerprint,
            lease.learner_id AS learner_id,
            lease.plan_id AS plan_id,
            lease.session_id AS session_id,
            lease.queue_item_id AS queue_item_id,
            lease.expected_session_version AS expected_session_version,
            lease.presentation_id AS presentation_id,
            lease.issued_at_epoch_millis AS issued_at_epoch_millis,
            lease.valid_through_epoch_millis AS valid_through_epoch_millis,
            session.updated_at_epoch_millis AS presentation_started_at_epoch_millis,
            fence.fence_id AS fence_id,
            revision.problem_id AS problem_id,
            lease.basis_revision_id AS basis_revision_id,
            queue.practice_unit_id AS practice_unit_id,
            document.error_book_entry_id AS error_book_entry_id
        FROM student_trusted_review_lease_receipt AS lease
        INNER JOIN student_review_session AS session
            ON session.session_id = lease.session_id
           AND session.plan_id = lease.plan_id
           AND session.learner_id = lease.learner_id
        INNER JOIN student_review_queue_item AS queue
            ON queue.queue_item_id = lease.queue_item_id
           AND queue.plan_id = lease.plan_id
           AND queue.learner_id = lease.learner_id
        INNER JOIN student_problem_revision AS revision
            ON revision.revision_id = lease.basis_revision_id
           AND revision.revision_id = queue.basis_revision_id
        INNER JOIN student_problem_document AS document
            ON document.problem_id = revision.problem_id
           AND document.learner_id = lease.learner_id
        INNER JOIN student_problem_collection AS collection
            ON collection.practice_unit_id = queue.practice_unit_id
           AND collection.problem_id = document.problem_id
           AND collection.learner_id = lease.learner_id
        INNER JOIN student_trusted_review_answer_rule AS rule
            ON rule.answer_rule_id = lease.answer_rule_id
           AND rule.basis_revision_id = lease.basis_revision_id
           AND rule.learner_id = lease.learner_id
        INNER JOIN student_trusted_review_presentation_fence AS fence
            ON fence.learner_id = lease.learner_id
           AND fence.plan_id = lease.plan_id
           AND fence.session_id = lease.session_id
           AND fence.queue_item_id = lease.queue_item_id
           AND fence.presentation_id = lease.presentation_id
           AND fence.problem_id = document.problem_id
           AND fence.basis_revision_id = lease.basis_revision_id
           AND fence.practice_unit_id = queue.practice_unit_id
           AND fence.error_book_entry_id = document.error_book_entry_id
           AND fence.bound_lease_receipt_id = lease.lease_receipt_id
           AND fence.bound_lease_canonical_fingerprint = lease.lease_canonical_fingerprint
        WHERE lease.lease_receipt_id = :leaseReceiptId
          AND lease.learner_id = :learnerId
          AND lease.lease_canonical_fingerprint = :leaseCanonicalFingerprint
          AND session.active_learner_id = :learnerId
          AND session.state = 'ACTIVE'
          AND session.session_version = lease.expected_session_version
          AND session.current_queue_item_id = lease.queue_item_id
          AND session.current_presentation_id = lease.presentation_id
          AND session.completed_at_epoch_millis IS NULL
          AND queue.state = 'PRESENTED'
          AND document.lifecycle_state = 'ACTIVE'
          AND document.error_book_entry_id IS NOT NULL
          AND document.current_revision_id = lease.basis_revision_id
          AND collection.mistake_state = 'ACTIVE'
          AND fence.status = 'PENDING'
          AND fence.eligible_attempt_receipt_id IS NULL
          AND fence.eligible_at_epoch_millis IS NULL
        LIMIT 1
        """,
    )
    protected abstract suspend fun readExactCurrentLease(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
    ): StudentTrustedReviewExactLeaseRow?

    @Query(
        """
        SELECT *
        FROM student_trusted_review_attempt_receipt
        WHERE lease_receipt_id = :leaseReceiptId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readAttemptForLease(
        leaseReceiptId: String,
    ): StudentTrustedReviewAttemptReceiptEntity?

    @Query(
        """
        SELECT COUNT(*)
        FROM student_trusted_review_attempt_receipt
        WHERE session_id = :sessionId
          AND presentation_id = :presentationId
        """,
    )
    protected abstract suspend fun countPresentationAttempts(
        sessionId: String,
        presentationId: String,
    ): Int

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN kind = 'HINT' THEN 1 ELSE 0 END), 0) AS hint_count,
            MIN(CASE WHEN kind = 'HINT' THEN occurred_at_epoch_millis END)
                AS first_hint_at_epoch_millis,
            MAX(CASE WHEN kind = 'HINT' THEN occurred_at_epoch_millis END)
                AS last_hint_at_epoch_millis,
            MIN(CASE WHEN kind = 'ANSWER_REVEAL' THEN occurred_at_epoch_millis END)
                AS answer_revealed_at_epoch_millis
        FROM student_trusted_review_assistance_receipt
        WHERE session_id = :sessionId
          AND presentation_id = :presentationId
          AND occurred_at_epoch_millis BETWEEN :startedAtEpochMillis AND :throughEpochMillis
        """,
    )
    protected abstract suspend fun readAssistanceSummary(
        sessionId: String,
        presentationId: String,
        startedAtEpochMillis: Long,
        throughEpochMillis: Long,
    ): StudentTrustedReviewAssistanceSummaryRow

    @Query(
        """
        UPDATE student_trusted_review_presentation_fence
        SET status = 'STRONG_EVIDENCE_ELIGIBLE',
            eligible_attempt_receipt_id = :attemptReceiptId,
            eligible_at_epoch_millis = :eligibleAtEpochMillis
        WHERE fence_id = :fenceId
          AND learner_id = :learnerId
          AND presentation_id = :presentationId
          AND basis_revision_id = :basisRevisionId
          AND practice_unit_id = :practiceUnitId
          AND error_book_entry_id = :errorBookEntryId
          AND status = 'PENDING'
          AND bound_lease_receipt_id = :leaseReceiptId
          AND bound_lease_canonical_fingerprint = :leaseCanonicalFingerprint
          AND eligible_attempt_receipt_id IS NULL
          AND eligible_at_epoch_millis IS NULL
        """,
    )
    protected abstract suspend fun promotePresentationFence(
        fenceId: String,
        learnerId: String,
        presentationId: String,
        basisRevisionId: String,
        practiceUnitId: String,
        errorBookEntryId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        attemptReceiptId: String,
        eligibleAtEpochMillis: Long,
    ): Int

    @Transaction
    override suspend fun claimAttempt(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        submittedAtEpochMillis: Long,
    ): StudentTrustedReviewAttemptSnapshot? {
        val lease =
            readExactCurrentLease(
                learnerId = learnerId,
                leaseReceiptId = leaseReceiptId,
                leaseCanonicalFingerprint = leaseCanonicalFingerprint,
            ) ?: return null
        if (
            submittedAtEpochMillis !in
            lease.issuedAtEpochMillis..lease.validThroughEpochMillis ||
            submittedAtEpochMillis < lease.presentationStartedAtEpochMillis ||
            readAttemptForLease(leaseReceiptId) != null
        ) {
            return null
        }
        val attemptOrdinal = countPresentationAttempts(lease.sessionId, lease.presentationId) + 1
        if (attemptOrdinal !in 1..MAX_TRUSTED_REVIEW_ATTEMPTS_PER_PRESENTATION) return null
        val assistance =
            readAssistanceSummary(
                sessionId = lease.sessionId,
                presentationId = lease.presentationId,
                startedAtEpochMillis = lease.presentationStartedAtEpochMillis,
                throughEpochMillis = submittedAtEpochMillis,
            )
        val elapsed = submittedAtEpochMillis - lease.presentationStartedAtEpochMillis
        val attemptFingerprint =
            CanonicalSha256("student-trusted-review-attempt-receipt-v1")
                .field("leaseCanonicalFingerprint", leaseCanonicalFingerprint)
                .field("attemptOrdinal", attemptOrdinal)
                .field("presentationStartedAtEpochMillis", lease.presentationStartedAtEpochMillis)
                .field("submittedAtEpochMillis", submittedAtEpochMillis)
                .field("hintCount", assistance.hintCount)
                .nullableField(
                    "firstHintAtEpochMillis",
                    assistance.firstHintAtEpochMillis?.toString(),
                )
                .nullableField(
                    "lastHintAtEpochMillis",
                    assistance.lastHintAtEpochMillis?.toString(),
                )
                .nullableField(
                    "answerRevealedAtEpochMillis",
                    assistance.answerRevealedAtEpochMillis?.toString(),
                )
                .finish()
        val attemptId = "trusted-review-attempt:$attemptFingerprint"
        val idempotencyKey = "trusted-review-submit:$attemptFingerprint"
        val entity =
            StudentTrustedReviewAttemptReceiptEntity(
                attemptReceiptId = attemptId,
                attemptCanonicalFingerprint = attemptFingerprint,
                submissionIdempotencyKey = idempotencyKey,
                leaseReceiptId = leaseReceiptId,
                learnerId = learnerId,
                planId = lease.planId,
                sessionId = lease.sessionId,
                queueItemId = lease.queueItemId,
                presentationId = lease.presentationId,
                attemptOrdinal = attemptOrdinal,
                retryCount = attemptOrdinal - 1,
                presentationStartedAtEpochMillis = lease.presentationStartedAtEpochMillis,
                submittedAtEpochMillis = submittedAtEpochMillis,
                elapsedDurationMillis = elapsed,
                hintCount = assistance.hintCount,
                firstHintAtEpochMillis = assistance.firstHintAtEpochMillis,
                lastHintAtEpochMillis = assistance.lastHintAtEpochMillis,
                answerRevealedAtEpochMillis = assistance.answerRevealedAtEpochMillis,
            )
        insertAttempt(entity)
        if (assistance.hintCount == 0 && assistance.answerRevealedAtEpochMillis == null) {
            check(
                promotePresentationFence(
                    fenceId = lease.fenceId,
                    learnerId = learnerId,
                    presentationId = lease.presentationId,
                    basisRevisionId = lease.basisRevisionId,
                    practiceUnitId = lease.practiceUnitId,
                    errorBookEntryId = lease.errorBookEntryId,
                    leaseReceiptId = leaseReceiptId,
                    leaseCanonicalFingerprint = leaseCanonicalFingerprint,
                    attemptReceiptId = attemptId,
                    eligibleAtEpochMillis = submittedAtEpochMillis,
                ) == 1,
            ) {
                "Trusted review presentation fence was not promoted by its exact live lease"
            }
        }
        return entity.toSnapshot(leaseCanonicalFingerprint)
    }

    /**
     * One owner transaction from raw response to queue transition and V2 outbox fact.
     *
     * [responseBindingIssuer] and [transitionWriter] are package-private owner collaborators wired
     * by [RoomStudentMistakeStore], never feature/model inputs.
     */
    @Transaction
    open suspend fun submitOwnedResponse(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        response: StudentTrustedReviewResponse,
        submittedAtEpochMillis: Long,
        sourceStoreGeneration: String,
        outboxIssuer: StudentOutboxAuthenticityIssuer,
        responseBindingIssuer: StudentReviewResponseBindingIssuer,
        transitionWriter: StudentTrustedReviewTransitionWriter,
    ): StudentTrustedReviewSubmissionResult {
        val lease =
            readLeaseReceipt(
                learnerId = learnerId,
                leaseReceiptId = leaseReceiptId,
                leaseCanonicalFingerprint = leaseCanonicalFingerprint,
            ) ?: return StudentTrustedReviewSubmissionResult.ReloadRequired
        if (submittedAtEpochMillis < 0L) {
            return StudentTrustedReviewSubmissionResult.Rejected
        }
        val savedRule =
            readExactSavedAnswerRow(
                learnerId = learnerId,
                problemRevisionId = lease.basisRevisionId,
                errorBookEntryId =
                    readRuleAdmissionTarget(lease.basisRevisionId)?.errorBookEntryId
                        ?: return StudentTrustedReviewSubmissionResult.ReloadRequired,
            ) ?: return StudentTrustedReviewSubmissionResult.ReloadRequired
        val ruleEntity =
            readRuleById(lease.answerRuleId)
                ?: return StudentTrustedReviewSubmissionResult.ReloadRequired
        if (
            ruleEntity.learnerId != learnerId ||
            ruleEntity.basisRevisionId != lease.basisRevisionId ||
            ruleEntity.problemId != savedRule.problemId ||
            ruleEntity.questionGeneration != savedRule.questionGeneration ||
            ruleEntity.questionVersion != savedRule.questionVersion
        ) {
            return StudentTrustedReviewSubmissionResult.ReloadRequired
        }
        val evaluation = ruleEntity.toDomainRule().evaluateOwnedResponse(response)
            ?: return StudentTrustedReviewSubmissionResult.Rejected
        val attempt =
            readAttemptForLease(leaseReceiptId)?.toSnapshot(leaseCanonicalFingerprint)
                ?: claimAttempt(
                    learnerId = learnerId,
                    leaseReceiptId = leaseReceiptId,
                    leaseCanonicalFingerprint = leaseCanonicalFingerprint,
                    submittedAtEpochMillis = submittedAtEpochMillis,
                )
                ?: return StudentTrustedReviewSubmissionResult.ReloadRequired
        val problemRevision = savedRule.toProblemRevision()
        val submissionFingerprint =
            CanonicalSha256("student-trusted-review-owned-submission-v2")
                .field("lease", leaseCanonicalFingerprint)
                .field("attempt", attempt.canonicalFingerprint)
                .field("idempotencyKey", attempt.submissionIdempotencyKey)
                .finish()
        val observationId = "review-observation:$submissionFingerprint"
        val submissionId = "review-submission:$submissionFingerprint"
        val responseForm = evaluation.responseForm
        val bindingScope =
            studentReviewResponseBindingScopeFingerprint(
                learnerId = learnerId,
                problemRevision = problemRevision,
                reviewSessionId = lease.sessionId,
                reviewQueueItemId = lease.queueItemId,
                presentationId = lease.presentationId,
                attemptOrdinal = attempt.attemptOrdinal,
                submissionId = submissionId,
                responseForm = responseForm,
            )
        val responseBinding =
            responseBindingIssuer.issue(
                bindingScope,
                evaluation.canonicalResponse,
            )
        requireSha256(responseBinding, "Review response opaque binding")
        val schedule = evaluation.toSchedule(attempt)
        val payload =
            ReviewObservationCapturedV2(
                problemRevision = problemRevision,
                reviewSessionId = lease.sessionId,
                reviewQueueItemId = lease.queueItemId,
                observationId = observationId,
                submissionId = submissionId,
                presentationId = lease.presentationId,
                responseForm = responseForm,
                responseOpaqueBinding = responseBinding,
                responseBindingAlgorithmVersion = responseBindingIssuer.algorithmVersion(),
                verificationOutcome = evaluation.outcome.toStorageOutcome(),
                attemptOrdinal = attempt.attemptOrdinal,
                hintCount = attempt.hintCount,
                answerWasRevealed = attempt.answerWasRevealed,
                verificationPolicyVersion =
                    "student-review-owner-v2:${ruleEntity.ruleCanonicalFingerprint}",
                elapsedDurationMillis = attempt.elapsedDurationMillis,
                capturedAtEpochMillis = attempt.submittedAtEpochMillis,
            )
        val outbox =
            CrossStoreEventEnvelope(
                eventId = "event-${payload.payloadCanonicalFingerprint.take(40)}",
                sourceStore = StudyStoreKind.STUDENT_MISTAKES,
                destinationStore = StudyStoreKind.LEARNER_MASTERY,
                aggregateId = payload.aggregateId,
                aggregateVersion = payload.attemptOrdinal.toLong(),
                occurredAtEpochMillis = payload.capturedAtEpochMillis,
                idempotencyKey = "review-${payload.payloadCanonicalFingerprint.take(40)}",
                sourceStoreGeneration = sourceStoreGeneration,
                payload = payload,
            ).let { envelope -> StudentOutboxEntityFactory.signedEntity(envelope, outboxIssuer) }
        val receipt =
            StudentReviewTransitionReceiptEntity(
                transitionId = "review-answer:$submissionFingerprint",
                transitionCanonicalFingerprint =
                    ownedReviewTransitionFingerprint(
                        learnerId = learnerId,
                        lease = lease,
                        observationId = observationId,
                        submissionId = submissionId,
                        responseForm = responseForm,
                        responseBinding = responseBinding,
                        outcome = evaluation.outcome,
                        attempt = attempt,
                        verificationPolicyVersion = payload.verificationPolicyVersion,
                        schedule = schedule,
                    ),
                learnerId = learnerId,
                planId = lease.planId,
                sessionId = lease.sessionId,
                queueItemId = lease.queueItemId,
                actionKind = StudentReviewSessionActionKind.RESPONSE.name,
                expectedSessionVersion = lease.expectedSessionVersion,
                resultingSessionVersion = lease.expectedSessionVersion + 1L,
                presentationId = lease.presentationId,
                observationId = observationId,
                submissionId = submissionId,
                responseForm = responseForm.name,
                responseCanonicalFingerprint = responseBinding,
                verificationOutcome = evaluation.outcome.name,
                attemptOrdinal = attempt.attemptOrdinal,
                hintCount = attempt.hintCount,
                answerWasRevealed = attempt.answerWasRevealed,
                verificationPolicyVersion = payload.verificationPolicyVersion,
                elapsedDurationMillis = attempt.elapsedDurationMillis,
                nextAvailableAtEpochMillis = schedule.nextAvailableAtEpochMillis,
                nextDueAtEpochMillis = schedule.nextDueAtEpochMillis,
                schedulingPolicyVersion = schedule.schedulingPolicyVersion,
                outboxEventId = outbox.eventId,
                occurredAtEpochMillis = attempt.submittedAtEpochMillis,
            )
        val disposition =
            transitionWriter.apply(
                ApplyStudentReviewTransitionBundle(
                    receipt = receipt,
                    revealReceipt = null,
                    outbox = outbox,
                ),
            )
        return when (disposition) {
            ApplyStudentReviewTransitionDisposition.APPLIED ->
                StudentTrustedReviewSubmissionResult.Recorded(
                    duplicate = false,
                    resultingSessionVersion = receipt.resultingSessionVersion,
                )
            ApplyStudentReviewTransitionDisposition.DUPLICATE ->
                StudentTrustedReviewSubmissionResult.Recorded(
                    duplicate = true,
                    resultingSessionVersion = receipt.resultingSessionVersion,
                )
            ApplyStudentReviewTransitionDisposition.RELOAD_REQUIRED ->
                throw StudentTrustedReviewSubmissionRollbackException()
        }
    }

    @Query(
        """
        SELECT *
        FROM student_trusted_review_assistance_receipt
        WHERE assistance_event_id = :assistanceEventId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readAssistance(
        assistanceEventId: String,
    ): StudentTrustedReviewAssistanceReceiptEntity?

    @Transaction
    override suspend fun recordAssistance(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        assistanceEventId: String,
        kind: StudentTrustedReviewAssistanceKind,
        occurredAtEpochMillis: Long,
    ): StudentTrustedReviewAssistanceResult {
        val lease =
            readExactCurrentLease(
                learnerId = learnerId,
                leaseReceiptId = leaseReceiptId,
                leaseCanonicalFingerprint = leaseCanonicalFingerprint,
            ) ?: return StudentTrustedReviewAssistanceResult.Unavailable
        readAssistance(assistanceEventId)?.let { existing ->
            return if (existing.matchesReplay(lease, kind)) {
                StudentTrustedReviewAssistanceResult.Duplicate
            } else {
                StudentTrustedReviewAssistanceResult.Unavailable
            }
        }
        // Lease expiry closes answer claiming, but must not erase a reveal/hint that happens while
        // the exact presentation is still current. Persisting it is what keeps a later fresh lease
        // from upgrading an assisted answer to independent evidence.
        if (
            occurredAtEpochMillis < lease.issuedAtEpochMillis ||
            occurredAtEpochMillis < lease.presentationStartedAtEpochMillis ||
            readAttemptForLease(leaseReceiptId) != null
        ) {
            return StudentTrustedReviewAssistanceResult.Unavailable
        }
        val fingerprint =
            CanonicalSha256("student-trusted-review-assistance-receipt-v1")
                .field("assistanceEventId", assistanceEventId)
                .field("leaseCanonicalFingerprint", leaseCanonicalFingerprint)
                .field("kind", kind.name)
                .field("occurredAtEpochMillis", occurredAtEpochMillis)
                .finish()
        val entity =
            StudentTrustedReviewAssistanceReceiptEntity(
                assistanceEventId = assistanceEventId,
                assistanceCanonicalFingerprint = fingerprint,
                leaseReceiptId = leaseReceiptId,
                learnerId = learnerId,
                planId = lease.planId,
                sessionId = lease.sessionId,
                queueItemId = lease.queueItemId,
                presentationId = lease.presentationId,
                kind = kind.name,
                occurredAtEpochMillis = occurredAtEpochMillis,
            )
        if (insertAssistance(entity) != -1L) {
            return StudentTrustedReviewAssistanceResult.Recorded
        }
        return if (readAssistance(assistanceEventId)?.matchesReplay(lease, kind) == true) {
            StudentTrustedReviewAssistanceResult.Duplicate
        } else {
            StudentTrustedReviewAssistanceResult.Unavailable
        }
    }
}

internal data class StudentTrustedReviewRuleAdmissionTargetRow(
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "current_revision_id")
    val currentRevisionId: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    @ColumnInfo(name = "document_canonical_fingerprint")
    val documentCanonicalFingerprint: String,
    @ColumnInfo(name = "lifecycle_state")
    val lifecycleState: String,
    @ColumnInfo(name = "error_book_entry_id")
    val errorBookEntryId: String?,
    @ColumnInfo(name = "mistake_state")
    val mistakeState: String,
)

internal data class StudentTrustedReviewLeaseCandidateRow(
    @ColumnInfo(name = "plan_id")
    val planId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "session_version")
    val sessionVersion: Long,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "presentation_started_at_epoch_millis")
    val presentationStartedAtEpochMillis: Long,
    @ColumnInfo(name = "queue_item_id")
    val queueItemId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    @ColumnInfo(name = "document_canonical_fingerprint")
    val documentCanonicalFingerprint: String,
    val subject: String,
    @ColumnInfo(name = "error_book_entry_id")
    val errorBookEntryId: String,
    @ColumnInfo(name = "fence_id")
    val fenceId: String,
    @ColumnInfo(name = "fence_canonical_fingerprint")
    val fenceCanonicalFingerprint: String,
    @ColumnInfo(name = "answer_rule_id")
    val answerRuleId: String,
    @ColumnInfo(name = "question_generation")
    val questionGeneration: Long,
    @ColumnInfo(name = "question_version")
    val questionVersion: String,
    @ColumnInfo(name = "rule_kind")
    val ruleKind: String,
    @ColumnInfo(name = "accepted_values_wire")
    val acceptedValuesWire: String?,
    @ColumnInfo(name = "correct_values_wire")
    val correctValuesWire: String?,
    @ColumnInfo(name = "expected_numeric_value")
    val expectedNumericValue: String?,
    @ColumnInfo(name = "absolute_tolerance")
    val absoluteTolerance: String?,
    @ColumnInfo(name = "expected_unit")
    val expectedUnit: String?,
    @ColumnInfo(name = "answer_spec_version")
    val answerSpecVersion: String,
    @ColumnInfo(name = "rule_canonical_fingerprint")
    val ruleCanonicalFingerprint: String,
)

internal data class StudentTrustedReviewExactLeaseRow(
    @ColumnInfo(name = "lease_receipt_id")
    val leaseReceiptId: String,
    @ColumnInfo(name = "lease_canonical_fingerprint")
    val leaseCanonicalFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "plan_id")
    val planId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "queue_item_id")
    val queueItemId: String,
    @ColumnInfo(name = "expected_session_version")
    val expectedSessionVersion: Long,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "issued_at_epoch_millis")
    val issuedAtEpochMillis: Long,
    @ColumnInfo(name = "valid_through_epoch_millis")
    val validThroughEpochMillis: Long,
    @ColumnInfo(name = "presentation_started_at_epoch_millis")
    val presentationStartedAtEpochMillis: Long,
    @ColumnInfo(name = "fence_id")
    val fenceId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "error_book_entry_id")
    val errorBookEntryId: String,
)

internal data class StudentTrustedReviewAssistanceSummaryRow(
    @ColumnInfo(name = "hint_count")
    val hintCount: Int,
    @ColumnInfo(name = "first_hint_at_epoch_millis")
    val firstHintAtEpochMillis: Long?,
    @ColumnInfo(name = "last_hint_at_epoch_millis")
    val lastHintAtEpochMillis: Long?,
    @ColumnInfo(name = "answer_revealed_at_epoch_millis")
    val answerRevealedAtEpochMillis: Long?,
)

internal data class StudentTrustedSavedAnswerRuleRow(
    @ColumnInfo(name = "problem_id") val problemId: String,
    @ColumnInfo(name = "learner_id") val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "practice_unit_id") val practiceUnitId: String,
    @ColumnInfo(name = "revision_id") val revisionId: String,
    @ColumnInfo(name = "revision_number") val revisionNumber: Int,
    @ColumnInfo(name = "document_canonical_fingerprint")
    val documentCanonicalFingerprint: String,
    @ColumnInfo(name = "error_book_entry_id") val errorBookEntryId: String,
    @ColumnInfo(name = "question_generation") val questionGeneration: Long,
    @ColumnInfo(name = "question_version") val questionVersion: String,
    @ColumnInfo(name = "rule_kind") val ruleKind: String,
    @ColumnInfo(name = "accepted_values_wire") val acceptedValuesWire: String?,
    @ColumnInfo(name = "correct_values_wire") val correctValuesWire: String?,
    @ColumnInfo(name = "expected_numeric_value") val expectedNumericValue: String?,
    @ColumnInfo(name = "absolute_tolerance") val absoluteTolerance: String?,
    @ColumnInfo(name = "expected_unit") val expectedUnit: String?,
    @ColumnInfo(name = "answer_spec_version") val answerSpecVersion: String,
    @ColumnInfo(name = "rule_canonical_fingerprint") val ruleCanonicalFingerprint: String,
    @ColumnInfo(name = "provenance_canonical_fingerprint")
    val provenanceCanonicalFingerprint: String,
)

private fun StudentTrustedSavedAnswerRuleRow.toDomain(): StudentTrustedSavedAnswerRule {
    val rule = when (StudentTrustedReviewAnswerRuleKind.valueOf(ruleKind)) {
        StudentTrustedReviewAnswerRuleKind.CHOICE -> StudentTrustedReviewAnswerRule.Choice(
            acceptedChoiceIds = checkNotNull(acceptedValuesWire).let(::decodeTrustedReviewSet),
            correctChoiceId = checkNotNull(correctValuesWire).let(::decodeTrustedReviewSet).single(),
            answerSpecVersion = answerSpecVersion,
        )
        StudentTrustedReviewAnswerRuleKind.NUMERIC -> StudentTrustedReviewAnswerRule.Numeric(
            expectedValue = checkNotNull(expectedNumericValue).toTrustedReviewBigDecimal(),
            absoluteTolerance = checkNotNull(absoluteTolerance).toTrustedReviewBigDecimal(),
            expectedUnit = expectedUnit,
            answerSpecVersion = answerSpecVersion,
        )
        StudentTrustedReviewAnswerRuleKind.VISUAL_TARGET ->
            StudentTrustedReviewAnswerRule.VisualTarget(
                acceptedTargetIds = checkNotNull(acceptedValuesWire).let(::decodeTrustedReviewSet),
                correctTargetIds = checkNotNull(correctValuesWire).let(::decodeTrustedReviewSet),
                answerSpecVersion = answerSpecVersion,
            )
    }
    check(rule.canonicalFingerprint == ruleCanonicalFingerprint) {
        "Trusted saved answer rule fingerprint is corrupt"
    }
    return StudentTrustedSavedAnswerRule(
        problemRevision = StudentProblemRevisionRef(
            problem = StudentProblemRef(
                learnerId = learnerId,
                subject = SubjectKind.valueOf(subject),
                problemId = problemId,
                practiceUnitId = practiceUnitId,
            ),
            revisionId = revisionId,
            revisionNumber = revisionNumber,
            documentCanonicalFingerprint = documentCanonicalFingerprint,
        ),
        errorBookEntryId = errorBookEntryId,
        questionGeneration = questionGeneration,
        questionVersion = questionVersion,
        answerRule = rule,
        provenanceCanonicalFingerprint = provenanceCanonicalFingerprint,
    )
}

private fun StudentTrustedReviewRuleAdmissionTargetRow.matches(
    expected: StudentProblemRevisionRef,
    expectedErrorBookEntryId: String,
    admittedAtEpochMillis: Long,
): Boolean =
    problemId == expected.problem.problemId &&
        learnerId == expected.problem.learnerId &&
        subject == expected.problem.subject.name &&
        practiceUnitId == expected.problem.practiceUnitId &&
        currentRevisionId == expected.revisionId &&
        revisionNumber == expected.revisionNumber &&
        documentCanonicalFingerprint == expected.documentCanonicalFingerprint &&
        lifecycleState == StudentProblemLifecycleState.ACTIVE.name &&
        errorBookEntryId == expectedErrorBookEntryId &&
        mistakeState == StudentMistakeEntryState.ACTIVE.name &&
        admittedAtEpochMillis >= 0

private fun AdmitStudentTrustedReviewAnswerRuleCommand.toEntity():
    StudentTrustedReviewAnswerRuleEntity {
    val shape =
        when (val rule = answerRule) {
            is StudentTrustedReviewAnswerRule.Choice ->
                PersistedTrustedReviewRuleShape(
                    kind = StudentTrustedReviewAnswerRuleKind.CHOICE,
                    acceptedValuesWire = encodeCanonicalSet(rule.acceptedChoiceIds),
                    correctValuesWire = encodeCanonicalSet(setOf(rule.correctChoiceId)),
                )
            is StudentTrustedReviewAnswerRule.Numeric ->
                PersistedTrustedReviewRuleShape(
                    kind = StudentTrustedReviewAnswerRuleKind.NUMERIC,
                    expectedNumericValue = rule.expectedValue.toTrustedReviewDecimal(),
                    absoluteTolerance = rule.absoluteTolerance.toTrustedReviewDecimal(),
                    expectedUnit = rule.expectedUnit,
                )
            is StudentTrustedReviewAnswerRule.VisualTarget ->
                PersistedTrustedReviewRuleShape(
                    kind = StudentTrustedReviewAnswerRuleKind.VISUAL_TARGET,
                    acceptedValuesWire = encodeCanonicalSet(rule.acceptedTargetIds),
                    correctValuesWire = encodeCanonicalSet(rule.correctTargetIds),
                )
        }
    return StudentTrustedReviewAnswerRuleEntity(
        answerRuleId = answerRuleId,
        learnerId = problemRevision.problem.learnerId,
        problemId = problemRevision.problem.problemId,
        basisRevisionId = problemRevision.revisionId,
        questionGeneration = questionGeneration,
        questionVersion = questionVersion,
        ruleKind = shape.kind.name,
        acceptedValuesWire = shape.acceptedValuesWire,
        correctValuesWire = shape.correctValuesWire,
        expectedNumericValue = shape.expectedNumericValue,
        absoluteTolerance = shape.absoluteTolerance,
        expectedUnit = shape.expectedUnit,
        answerSpecVersion = answerRule.answerSpecVersion,
        provenanceKind = provenanceKind.name,
        provenanceReferenceId = provenanceReferenceId,
        provenanceCanonicalFingerprint = provenanceCanonicalFingerprint,
        ruleCanonicalFingerprint = answerRule.canonicalFingerprint,
        admittedAtEpochMillis = admittedAtEpochMillis,
    )
}

private data class PersistedTrustedReviewRuleShape(
    val kind: StudentTrustedReviewAnswerRuleKind,
    val acceptedValuesWire: String? = null,
    val correctValuesWire: String? = null,
    val expectedNumericValue: String? = null,
    val absoluteTolerance: String? = null,
    val expectedUnit: String? = null,
)

private fun StudentTrustedReviewLeaseCandidateRow.leaseCanonicalFingerprint(
    receiptId: String,
    issuedAtEpochMillis: Long,
    validThroughEpochMillis: Long,
): String =
    CanonicalSha256("student-trusted-review-lease-receipt-v2")
        .field("receiptId", receiptId)
        .field("learnerId", learnerId)
        .field("planId", planId)
        .field("sessionId", sessionId)
        .field("queueItemId", queueItemId)
        .field("expectedSessionVersion", sessionVersion)
        .field("presentationId", presentationId)
        .field("problemId", problemId)
        .field("subject", subject)
        .field("basisRevisionId", basisRevisionId)
        .field("revisionNumber", revisionNumber)
        .field("documentCanonicalFingerprint", documentCanonicalFingerprint)
        .field("practiceUnitId", practiceUnitId)
        .field("errorBookEntryId", errorBookEntryId)
        .field("fenceCanonicalFingerprint", fenceCanonicalFingerprint)
        .field("answerRuleId", answerRuleId)
        .field("questionGeneration", questionGeneration)
        .field("questionVersion", questionVersion)
        .field("ruleCanonicalFingerprint", ruleCanonicalFingerprint)
        .field("issuedAtEpochMillis", issuedAtEpochMillis)
        .field("validThroughEpochMillis", validThroughEpochMillis)
        .finish()

private fun StudentTrustedReviewLeaseCandidateRow.toPersistedLease(
    receiptId: String,
    issuedAtEpochMillis: Long,
    validThroughEpochMillis: Long,
    canonicalFingerprint: String,
): PersistedStudentTrustedReviewLease =
    PersistedStudentTrustedReviewLease(
        receiptId = receiptId,
        planId = planId,
        sessionId = sessionId,
        queueItemId = queueItemId,
        expectedSessionVersion = sessionVersion,
        presentationId = presentationId,
        problemRevision =
            StudentProblemRevisionRef(
                problem =
                    StudentProblemRef(
                        learnerId = learnerId,
                        subject =
                            runCatching { SubjectKind.valueOf(subject) }.getOrElse {
                                throw IllegalStateException(
                                    "Trusted review subject is corrupt",
                                    it,
                                )
                            },
                        problemId = problemId,
                        practiceUnitId = practiceUnitId,
                    ),
                revisionId = basisRevisionId,
                revisionNumber = revisionNumber,
                documentCanonicalFingerprint = documentCanonicalFingerprint,
            ),
        errorBookEntryId = errorBookEntryId,
        questionGeneration = questionGeneration,
        questionVersion = questionVersion,
        answerRule = toDomainRule(),
        issuedAtEpochMillis = issuedAtEpochMillis,
        validThroughEpochMillis = validThroughEpochMillis,
        canonicalFingerprint = canonicalFingerprint,
    )

private fun StudentTrustedReviewLeaseCandidateRow.toDomainRule(): StudentTrustedReviewAnswerRule =
    when (
        val kind =
            runCatching { StudentTrustedReviewAnswerRuleKind.valueOf(ruleKind) }.getOrElse {
                throw IllegalStateException("Trusted review rule kind is corrupt", it)
            }
    ) {
        StudentTrustedReviewAnswerRuleKind.CHOICE -> {
            val accepted = checkNotNull(acceptedValuesWire).let(::decodeTrustedReviewSet)
            val correct = checkNotNull(correctValuesWire).let(::decodeTrustedReviewSet)
            check(correct.size == 1) { "Trusted review choice rule is corrupt" }
            StudentTrustedReviewAnswerRule.Choice(
                acceptedChoiceIds = accepted,
                correctChoiceId = correct.single(),
                answerSpecVersion = answerSpecVersion,
            )
        }
        StudentTrustedReviewAnswerRuleKind.NUMERIC ->
            StudentTrustedReviewAnswerRule.Numeric(
                expectedValue =
                    checkNotNull(expectedNumericValue).toTrustedReviewBigDecimal(),
                absoluteTolerance =
                    checkNotNull(absoluteTolerance).toTrustedReviewBigDecimal(),
                expectedUnit = expectedUnit,
                answerSpecVersion = answerSpecVersion,
            )
        StudentTrustedReviewAnswerRuleKind.VISUAL_TARGET ->
            StudentTrustedReviewAnswerRule.VisualTarget(
                acceptedTargetIds =
                    checkNotNull(acceptedValuesWire).let(::decodeTrustedReviewSet),
                correctTargetIds = checkNotNull(correctValuesWire).let(::decodeTrustedReviewSet),
                answerSpecVersion = answerSpecVersion,
            )
    }.also {
        check(it.canonicalFingerprint == answerRuleFingerprintOnly()) {
            "Trusted review answer rule fingerprint is corrupt"
        }
    }

private fun StudentTrustedReviewLeaseCandidateRow.answerRuleFingerprintOnly(): String =
    when (
        val kind =
            runCatching { StudentTrustedReviewAnswerRuleKind.valueOf(ruleKind) }.getOrElse {
                throw IllegalStateException("Trusted review rule kind is corrupt", it)
            }
    ) {
        StudentTrustedReviewAnswerRuleKind.CHOICE ->
            StudentTrustedReviewAnswerRule.Choice(
                acceptedChoiceIds = checkNotNull(acceptedValuesWire).let(::decodeTrustedReviewSet),
                correctChoiceId =
                    checkNotNull(correctValuesWire).let(::decodeTrustedReviewSet).single(),
                answerSpecVersion = answerSpecVersion,
            ).canonicalFingerprint
        StudentTrustedReviewAnswerRuleKind.NUMERIC ->
            StudentTrustedReviewAnswerRule.Numeric(
                expectedValue = checkNotNull(expectedNumericValue).toTrustedReviewBigDecimal(),
                absoluteTolerance = checkNotNull(absoluteTolerance).toTrustedReviewBigDecimal(),
                expectedUnit = expectedUnit,
                answerSpecVersion = answerSpecVersion,
            ).canonicalFingerprint
        StudentTrustedReviewAnswerRuleKind.VISUAL_TARGET ->
            StudentTrustedReviewAnswerRule.VisualTarget(
                acceptedTargetIds = checkNotNull(acceptedValuesWire).let(::decodeTrustedReviewSet),
                correctTargetIds = checkNotNull(correctValuesWire).let(::decodeTrustedReviewSet),
                answerSpecVersion = answerSpecVersion,
            ).canonicalFingerprint
    }

private fun String.toTrustedReviewBigDecimal(): BigDecimal =
    runCatching { BigDecimal(this) }
        .getOrElse { throw IllegalStateException("Trusted review numeric rule is corrupt", it) }
        .also {
            check(it.hasTrustedReviewDecimalShape() && it.toTrustedReviewDecimal() == this) {
                "Trusted review numeric rule is not canonical"
            }
        }

private fun decodeTrustedReviewSet(wire: String): Set<String> =
    decodeOrderedStrings(wire).toSet().also { values ->
        check(values.size == decodeOrderedStrings(wire).size && encodeCanonicalSet(values) == wire) {
            "Trusted review answer-set wire value is not canonical"
        }
    }

private fun StudentTrustedReviewAttemptReceiptEntity.toSnapshot(
    leaseCanonicalFingerprint: String,
): StudentTrustedReviewAttemptSnapshot =
    StudentTrustedReviewAttemptSnapshot(
        leaseCanonicalFingerprint = leaseCanonicalFingerprint,
        attemptOrdinal = attemptOrdinal,
        retryCount = retryCount,
        presentationStartedAtEpochMillis = presentationStartedAtEpochMillis,
        submittedAtEpochMillis = submittedAtEpochMillis,
        elapsedDurationMillis = elapsedDurationMillis,
        hintCount = hintCount,
        firstHintAtEpochMillis = firstHintAtEpochMillis,
        lastHintAtEpochMillis = lastHintAtEpochMillis,
        answerWasRevealed = answerRevealedAtEpochMillis != null,
        answerRevealedAtEpochMillis = answerRevealedAtEpochMillis,
        submissionIdempotencyKey = submissionIdempotencyKey,
        canonicalFingerprint = attemptCanonicalFingerprint,
    )

private fun StudentTrustedReviewAssistanceReceiptEntity.matchesReplay(
    lease: StudentTrustedReviewExactLeaseRow,
    kind: StudentTrustedReviewAssistanceKind,
): Boolean =
    leaseReceiptId == lease.leaseReceiptId &&
        learnerId == lease.learnerId &&
        planId == lease.planId &&
        sessionId == lease.sessionId &&
        queueItemId == lease.queueItemId &&
        presentationId == lease.presentationId &&
        this.kind == kind.name

internal fun interface StudentTrustedReviewTransitionWriter {
    suspend fun apply(
        bundle: ApplyStudentReviewTransitionBundle,
    ): ApplyStudentReviewTransitionDisposition
}

internal class StudentTrustedReviewSubmissionRollbackException : RuntimeException()

private fun StudentTrustedReviewAnswerRuleEntity.toDomainRule():
    StudentTrustedReviewAnswerRule {
    val rule =
        when (
            runCatching { StudentTrustedReviewAnswerRuleKind.valueOf(ruleKind) }.getOrElse {
                throw IllegalStateException("Trusted review rule kind is corrupt", it)
            }
        ) {
            StudentTrustedReviewAnswerRuleKind.CHOICE -> {
                val accepted = checkNotNull(acceptedValuesWire).let(::decodeTrustedReviewSet)
                val correct = checkNotNull(correctValuesWire).let(::decodeTrustedReviewSet)
                check(correct.size == 1) { "Trusted review choice rule is corrupt" }
                StudentTrustedReviewAnswerRule.Choice(
                    acceptedChoiceIds = accepted,
                    correctChoiceId = correct.single(),
                    answerSpecVersion = answerSpecVersion,
                )
            }
            StudentTrustedReviewAnswerRuleKind.NUMERIC ->
                StudentTrustedReviewAnswerRule.Numeric(
                    expectedValue = checkNotNull(expectedNumericValue).toTrustedReviewBigDecimal(),
                    absoluteTolerance = checkNotNull(absoluteTolerance).toTrustedReviewBigDecimal(),
                    expectedUnit = expectedUnit,
                    answerSpecVersion = answerSpecVersion,
                )
            StudentTrustedReviewAnswerRuleKind.VISUAL_TARGET ->
                StudentTrustedReviewAnswerRule.VisualTarget(
                    acceptedTargetIds =
                        checkNotNull(acceptedValuesWire).let(::decodeTrustedReviewSet),
                    correctTargetIds =
                        checkNotNull(correctValuesWire).let(::decodeTrustedReviewSet),
                    answerSpecVersion = answerSpecVersion,
                )
        }
    check(rule.canonicalFingerprint == ruleCanonicalFingerprint) {
        "Trusted review answer rule fingerprint is corrupt"
    }
    return rule
}

private fun StudentTrustedSavedAnswerRuleRow.toProblemRevision(): StudentProblemRevisionRef =
    StudentProblemRevisionRef(
        problem =
            StudentProblemRef(
                learnerId = learnerId,
                subject =
                    runCatching { SubjectKind.valueOf(subject) }.getOrElse {
                        throw IllegalStateException("Trusted review subject is corrupt", it)
                    },
                problemId = problemId,
                practiceUnitId = practiceUnitId,
            ),
        revisionId = revisionId,
        revisionNumber = revisionNumber,
        documentCanonicalFingerprint = documentCanonicalFingerprint,
    )

private fun OwnedStudentTrustedReviewEvaluation.toSchedule(
    attempt: StudentTrustedReviewAttemptSnapshot,
): StudentReviewScheduleUpdate {
    val independentlyCorrect =
        outcome == StudentTrustedReviewAnswerOutcome.CORRECT &&
            attempt.attemptOrdinal == 1 &&
            attempt.retryCount == 0 &&
            attempt.hintCount == 0 &&
            !attempt.answerWasRevealed
    val interval =
        if (independentlyCorrect) {
            OWNER_INDEPENDENT_CORRECT_INTERVAL_MILLIS
        } else {
            OWNER_NEEDS_REVIEW_INTERVAL_MILLIS
        }
    val next = attempt.submittedAtEpochMillis.saturatingOwnedReviewAdd(interval)
    return StudentReviewScheduleUpdate(
        nextAvailableAtEpochMillis = next,
        nextDueAtEpochMillis = next,
        schedulingPolicyVersion = OWNER_REVIEW_SCHEDULING_POLICY_VERSION,
    )
}

private fun ownedReviewTransitionFingerprint(
    learnerId: String,
    lease: StudentTrustedReviewLeaseReceiptEntity,
    observationId: String,
    submissionId: String,
    responseForm: ReviewResponseForm,
    responseBinding: String,
    outcome: StudentTrustedReviewAnswerOutcome,
    attempt: StudentTrustedReviewAttemptSnapshot,
    verificationPolicyVersion: String,
    schedule: StudentReviewScheduleUpdate,
): String =
    SubmitStudentReviewResponseCommand(
        transitionId =
            "review-answer:${
                CanonicalSha256("student-trusted-review-owned-submission-v2")
                    .field("lease", lease.leaseCanonicalFingerprint)
                    .field("attempt", attempt.canonicalFingerprint)
                    .field("idempotencyKey", attempt.submissionIdempotencyKey)
                    .finish()
            }",
        sessionId = lease.sessionId,
        queueItemId = lease.queueItemId,
        expectedSessionVersion = lease.expectedSessionVersion,
        presentationId = lease.presentationId,
        observationId = observationId,
        submissionId = submissionId,
        responseForm = responseForm.toStudentForm(),
        responseCanonicalFingerprint = responseBinding,
        verificationOutcome = outcome.toStudentOutcome(),
        attemptOrdinal = attempt.attemptOrdinal,
        hintCount = attempt.hintCount,
        answerWasRevealed = attempt.answerWasRevealed,
        verificationPolicyVersion = verificationPolicyVersion,
        elapsedDurationMillis = attempt.elapsedDurationMillis,
        schedule = schedule,
        capturedAtEpochMillis = attempt.submittedAtEpochMillis,
    ).canonicalFingerprint(learnerId)

private fun ReviewResponseForm.toStudentForm(): StudentReviewResponseForm =
    when (this) {
        ReviewResponseForm.CHOICE -> StudentReviewResponseForm.CHOICE
        ReviewResponseForm.NUMERIC -> StudentReviewResponseForm.NUMERIC
        ReviewResponseForm.VISUAL_TARGET -> StudentReviewResponseForm.VISUAL_TARGET
    }

private fun StudentTrustedReviewAnswerOutcome.toStudentOutcome():
    StudentReviewVerificationOutcome =
    when (this) {
        StudentTrustedReviewAnswerOutcome.CORRECT -> StudentReviewVerificationOutcome.CORRECT
        StudentTrustedReviewAnswerOutcome.INCORRECT -> StudentReviewVerificationOutcome.INCORRECT
    }

private fun StudentTrustedReviewAnswerOutcome.toStorageOutcome(): ReviewVerificationOutcome =
    when (this) {
        StudentTrustedReviewAnswerOutcome.CORRECT -> ReviewVerificationOutcome.CORRECT
        StudentTrustedReviewAnswerOutcome.INCORRECT -> ReviewVerificationOutcome.INCORRECT
    }

internal fun String.toOwnedReviewDecimalOrNull(): BigDecimal? {
    if (!OWNED_REVIEW_STRICT_DECIMAL.matches(this)) return null
    return runCatching { BigDecimal(this) }
        .getOrNull()
        ?.takeIf(BigDecimal::hasTrustedReviewDecimalShape)
}

private fun Long.saturatingOwnedReviewAdd(increment: Long): Long =
    if (this > Long.MAX_VALUE - increment) Long.MAX_VALUE else this + increment

private val OWNED_REVIEW_STRICT_DECIMAL =
    Regex("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")
private const val OWNER_REVIEW_SCHEDULING_POLICY_VERSION =
    "student-review-owner-outcome-v2"
private const val OWNER_INDEPENDENT_CORRECT_INTERVAL_MILLIS =
    3L * 24L * 60L * 60L * 1_000L
private const val OWNER_NEEDS_REVIEW_INTERVAL_MILLIS = 12L * 60L * 60L * 1_000L

private const val MAX_TRUSTED_REVIEW_ATTEMPTS_PER_PRESENTATION = 100
