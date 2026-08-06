package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import java.math.BigDecimal
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException

internal sealed interface StudentTrustedReviewAnswerRule {
    val answerSpecVersion: String
    val canonicalFingerprint: String

    data class Choice(
        val acceptedChoiceIds: Set<String>,
        val correctChoiceId: String,
        override val answerSpecVersion: String,
    ) : StudentTrustedReviewAnswerRule {
        init {
            require(acceptedChoiceIds.size in 2..MAX_TRUSTED_REVIEW_TARGETS) {
                "Trusted review choice rule has an invalid option count"
            }
            require(acceptedChoiceIds.all(String::isTrustedReviewRuleText)) {
                "Trusted review choice rule has an invalid option id"
            }
            require(correctChoiceId in acceptedChoiceIds) {
                "Trusted review correct choice is not an accepted option"
            }
            answerSpecVersion.requireStoreText(
                "Trusted review answer-spec version",
                MAX_VERSION_CHARS,
            )
        }

        override val canonicalFingerprint: String =
            CanonicalSha256("student-trusted-review-choice-rule-v1")
                .field("acceptedChoiceIds", acceptedChoiceIds.sorted().joinToString("\u001f"))
                .field("correctChoiceId", correctChoiceId)
                .field("answerSpecVersion", answerSpecVersion)
                .finish()
    }

    data class Numeric(
        val expectedValue: BigDecimal,
        val absoluteTolerance: BigDecimal,
        val expectedUnit: String?,
        override val answerSpecVersion: String,
    ) : StudentTrustedReviewAnswerRule {
        init {
            require(expectedValue.hasTrustedReviewDecimalShape()) {
                "Trusted review expected value is outside the supported range"
            }
            require(
                absoluteTolerance.hasTrustedReviewDecimalShape() &&
                    absoluteTolerance.signum() >= 0
            ) {
                "Trusted review numeric tolerance is outside the supported range"
            }
            require(expectedUnit == null || expectedUnit.isTrustedReviewRuleText()) {
                "Trusted review numeric unit is invalid"
            }
            answerSpecVersion.requireStoreText(
                "Trusted review answer-spec version",
                MAX_VERSION_CHARS,
            )
        }

        override val canonicalFingerprint: String =
            CanonicalSha256("student-trusted-review-numeric-rule-v1")
                .field("expectedValue", expectedValue.toTrustedReviewDecimal())
                .field("absoluteTolerance", absoluteTolerance.toTrustedReviewDecimal())
                .nullableField("expectedUnit", expectedUnit)
                .field("answerSpecVersion", answerSpecVersion)
                .finish()
    }

    data class VisualTarget(
        val acceptedTargetIds: Set<String>,
        val correctTargetIds: Set<String>,
        override val answerSpecVersion: String,
    ) : StudentTrustedReviewAnswerRule {
        init {
            require(
                acceptedTargetIds.isNotEmpty() &&
                    acceptedTargetIds.size <= MAX_TRUSTED_REVIEW_TARGETS
            ) {
                "Trusted review visual rule has an invalid target count"
            }
            require(acceptedTargetIds.all(String::isTrustedReviewRuleText)) {
                "Trusted review visual rule has an invalid target id"
            }
            require(
                correctTargetIds.isNotEmpty() &&
                    correctTargetIds.all(acceptedTargetIds::contains)
            ) {
                "Trusted review correct targets must be accepted targets"
            }
            answerSpecVersion.requireStoreText(
                "Trusted review answer-spec version",
                MAX_VERSION_CHARS,
            )
        }

        override val canonicalFingerprint: String =
            CanonicalSha256("student-trusted-review-visual-rule-v1")
                .field("acceptedTargetIds", acceptedTargetIds.sorted().joinToString("\u001f"))
                .field("correctTargetIds", correctTargetIds.sorted().joinToString("\u001f"))
                .field("answerSpecVersion", answerSpecVersion)
                .finish()
    }
}

/** Raw learner input accepted by the student-mistake owner. */
sealed interface StudentTrustedReviewResponse {
    data class Choice(
        val choiceId: String,
    ) : StudentTrustedReviewResponse {
        init {
            require(choiceId.isTrustedReviewRuleText()) {
                "Trusted review choice response is invalid"
            }
        }
    }

    data class Numeric(
        val value: String,
        val unit: String? = null,
    ) : StudentTrustedReviewResponse {
        init {
            require(value.isTrustedReviewRuleText()) {
                "Trusted review numeric response is invalid"
            }
            require(unit == null || unit.isTrustedReviewRuleText()) {
                "Trusted review numeric response unit is invalid"
            }
        }
    }

    data class VisualTarget(
        val targetId: String,
    ) : StudentTrustedReviewResponse {
        init {
            require(targetId.isTrustedReviewRuleText()) {
                "Trusted review visual-target response is invalid"
            }
        }
    }
}

internal enum class StudentTrustedReviewAnswerOutcome {
    CORRECT,
    INCORRECT,
}

sealed interface StudentTrustedReviewSubmissionResult {
    data class Recorded(
        val duplicate: Boolean,
        val resultingSessionVersion: Long,
    ) : StudentTrustedReviewSubmissionResult

    data object ReloadRequired : StudentTrustedReviewSubmissionResult

    data object Rejected : StudentTrustedReviewSubmissionResult

    data object StorageUnavailable : StudentTrustedReviewSubmissionResult
}

/**
 * Process-local, owner-issued access to one exact persisted review presentation.
 *
 * The constructor is not part of the public Kotlin API and the owning port additionally verifies
 * object identity. Copying the visible fields therefore cannot manufacture a usable lease.
 */
class StudentTrustedReviewAnswerLease internal constructor(
    val planId: String,
    val sessionId: String,
    val queueItemId: String,
    val expectedSessionVersion: Long,
    val presentationId: String,
    val problemRevision: StudentProblemRevisionRef,
    val errorBookEntryId: String,
    val questionGeneration: Long,
    val questionVersion: String,
    val issuedAtEpochMillis: Long,
    val validThroughEpochMillis: Long,
    val canonicalFingerprint: String,
    internal val receiptId: String,
) {
    init {
        planId.requireStoreText("Trusted review plan id", MAX_ID_CHARS)
        sessionId.requireStoreText("Trusted review session id", MAX_ID_CHARS)
        queueItemId.requireStoreText("Trusted review queue-item id", MAX_ID_CHARS)
        require(expectedSessionVersion in 1 until Long.MAX_VALUE) {
            "Trusted review session version is outside the supported range"
        }
        presentationId.requireStoreText("Trusted review presentation id", MAX_ID_CHARS)
        errorBookEntryId.requireStoreText("Trusted review error-book entry id", MAX_ID_CHARS)
        require(questionGeneration > 0) {
            "Trusted review question generation must be positive"
        }
        questionVersion.requireStoreText("Trusted review question version", MAX_VERSION_CHARS)
        require(issuedAtEpochMillis >= 0) {
            "Trusted review lease time must not be negative"
        }
        require(validThroughEpochMillis >= issuedAtEpochMillis) {
            "Trusted review lease validity must not precede issuance"
        }
        requireSha256(canonicalFingerprint, "Trusted review lease fingerprint")
        receiptId.requireStoreText("Trusted review lease receipt id", MAX_ID_CHARS)
    }
}

data class StudentTrustedReviewAttemptSnapshot(
    val leaseCanonicalFingerprint: String,
    val attemptOrdinal: Int,
    val retryCount: Int,
    val presentationStartedAtEpochMillis: Long,
    val submittedAtEpochMillis: Long,
    val elapsedDurationMillis: Long,
    val hintCount: Int,
    val firstHintAtEpochMillis: Long?,
    val lastHintAtEpochMillis: Long?,
    val answerWasRevealed: Boolean,
    val answerRevealedAtEpochMillis: Long?,
    val submissionIdempotencyKey: String,
    val canonicalFingerprint: String,
) {
    init {
        requireSha256(leaseCanonicalFingerprint, "Trusted review lease fingerprint")
        require(attemptOrdinal >= 1) { "Trusted review attempt ordinal must be positive" }
        require(retryCount == attemptOrdinal - 1) {
            "Trusted review retry count must match the attempt ordinal"
        }
        require(
            presentationStartedAtEpochMillis in 0..submittedAtEpochMillis &&
                elapsedDurationMillis == submittedAtEpochMillis - presentationStartedAtEpochMillis
        ) {
            "Trusted review attempt timing is inconsistent"
        }
        require(hintCount >= 0) { "Trusted review hint count must not be negative" }
        require((hintCount == 0) == (firstHintAtEpochMillis == null)) {
            "Trusted review first-hint time does not match the hint count"
        }
        require((hintCount == 0) == (lastHintAtEpochMillis == null)) {
            "Trusted review last-hint time does not match the hint count"
        }
        require(
            firstHintAtEpochMillis == null ||
                (
                    firstHintAtEpochMillis in
                        presentationStartedAtEpochMillis..submittedAtEpochMillis &&
                        checkNotNull(lastHintAtEpochMillis) in
                        firstHintAtEpochMillis..submittedAtEpochMillis
                )
        ) {
            "Trusted review hint timing is inconsistent"
        }
        require(answerWasRevealed == (answerRevealedAtEpochMillis != null)) {
            "Trusted review reveal state and time must agree"
        }
        require(
            answerRevealedAtEpochMillis == null ||
                answerRevealedAtEpochMillis in
                presentationStartedAtEpochMillis..submittedAtEpochMillis
        ) {
            "Trusted review reveal timing is inconsistent"
        }
        submissionIdempotencyKey.requireStoreText(
            "Trusted review submission idempotency key",
            MAX_ID_CHARS,
        )
        requireSha256(canonicalFingerprint, "Trusted review attempt fingerprint")
    }
}

enum class StudentTrustedReviewAssistanceKind {
    HINT,
    ANSWER_REVEAL,
}

sealed interface StudentTrustedReviewAssistanceResult {
    data object Recorded : StudentTrustedReviewAssistanceResult

    data object Duplicate : StudentTrustedReviewAssistanceResult

    data object Unavailable : StudentTrustedReviewAssistanceResult
}

interface LearnerBoundStudentTrustedReviewAnswerPort {
    val learnerId: String

    /**
     * Issues a short-lived lease only for the currently presented, active saved problem.
     *
     * A missing trusted structured rule, stale revision, completed session, removed problem, or
     * any ownership mismatch returns null.
     */
    suspend fun issueCurrentLease(): StudentTrustedReviewAnswerLease?

    /**
     * Submits only constrained raw input. Rule lookup, validation, attempt claiming, response
     * binding, review transition, and outbox creation remain inside the student-mistake owner.
     */
    suspend fun submitResponse(
        lease: StudentTrustedReviewAnswerLease,
        response: StudentTrustedReviewResponse,
    ): StudentTrustedReviewSubmissionResult

    /**
     * Persists only assistance timing. It neither evaluates an answer nor emits mastery evidence.
     */
    suspend fun recordAssistance(
        lease: StudentTrustedReviewAnswerLease,
        assistanceEventId: String,
        kind: StudentTrustedReviewAssistanceKind,
    ): StudentTrustedReviewAssistanceResult
}

/** Owner-private saved rule. No public port may return this value. */
internal data class StudentTrustedSavedAnswerRule(
    val problemRevision: StudentProblemRevisionRef,
    val errorBookEntryId: String,
    /** Exact trusted interaction/scene binding generation; never inferred by the Tutor model. */
    val questionGeneration: Long,
    /** Canonical content binding produced by a trusted credential issuer. */
    val questionVersion: String,
    val answerRule: StudentTrustedReviewAnswerRule,
    val provenanceCanonicalFingerprint: String,
) {
    init {
        errorBookEntryId.requireStoreText("Trusted saved answer error-book entry id", MAX_ID_CHARS)
        require(questionGeneration > 0L)
        questionVersion.requireStoreText("Trusted saved answer question version", MAX_VERSION_CHARS)
        requireSha256(provenanceCanonicalFingerprint, "Trusted saved answer provenance")
    }
}

/**
 * Public, answer-free description of one exact saved Tutor presentation.
 *
 * [questionVersion] is a versioned fingerprint of visible content only. A V1 value is deliberately
 * rejected so an archived rule can never be reopened through the former answer-reading API.
 */
data class StudentTrustedSavedAnswerPresentationRequest(
    val problemRevisionId: String,
    val errorBookEntryId: String,
    val questionGeneration: Long,
    val questionVersion: String,
    val responseForm: ReviewResponseForm,
) {
    init {
        problemRevisionId.requireStoreText("Trusted saved answer revision id", MAX_ID_CHARS)
        errorBookEntryId.requireStoreText("Trusted saved answer error-book entry id", MAX_ID_CHARS)
        require(questionGeneration > 0L)
        questionVersion.requireStoreText("Trusted saved answer question version", MAX_VERSION_CHARS)
        require(questionVersion.startsWith(TRUSTED_SAVED_ANSWER_PRESENTATION_V2_PREFIX)) {
            "Trusted saved answer presentation version is not current"
        }
    }
}

/** Process-local, one-presentation capability. It contains no answer rule or correctness bit. */
class StudentTrustedSavedAnswerLease internal constructor(
    val problemRevisionId: String,
    val errorBookEntryId: String,
    val questionGeneration: Long,
    val questionVersion: String,
    val responseForm: ReviewResponseForm,
    val issuedAtEpochMillis: Long,
    val validThroughEpochMillis: Long,
    val canonicalFingerprint: String,
    internal val receiptId: String,
)

sealed interface StudentTrustedSavedAnswerSubmissionResult {
    /**
     * Owner-signed post-evaluation fact. It contains no answer rule and is returned only after the
     * submitted response has been checked against the exact leased presentation.
     */
    data class Accepted(
        val duplicate: Boolean,
        val evaluation: StudentTrustedSavedAnswerEvaluationReceipt,
    ) : StudentTrustedSavedAnswerSubmissionResult

    data object Rejected : StudentTrustedSavedAnswerSubmissionResult
}

/**
 * Bounded hand-off to the trusted application owner. This is not exposed to model or feature
 * modules; it lets that owner durably append the already-evaluated response without reading the
 * saved answer rule.
 */
data class StudentTrustedSavedAnswerEvaluationReceipt(
    val responseForm: ReviewResponseForm,
    val responseBinding: String,
    val selectionWasCorrect: Boolean,
    val canonicalFingerprint: String,
) {
    init {
        requireSha256(responseBinding, "Trusted saved answer response binding")
        requireSha256(canonicalFingerprint, "Trusted saved answer evaluation fingerprint")
    }
}

interface LearnerBoundStudentTrustedSavedAnswerRulePort {
    val learnerId: String

    /** Issues only an answer-free, process-local lease for an exact V2 visible presentation. */
    suspend fun issueExactLease(
        request: StudentTrustedSavedAnswerPresentationRequest,
    ): StudentTrustedSavedAnswerLease?

    /** Accepts raw input; validity and correctness are evaluated only inside this owner. */
    suspend fun submitResponse(
        lease: StudentTrustedSavedAnswerLease,
        response: StudentTrustedReviewResponse,
    ): StudentTrustedSavedAnswerSubmissionResult
}

internal fun interface StudentTrustedSavedAnswerRulePersistencePort {
    suspend fun readExact(
        learnerId: String,
        problemRevisionId: String,
        errorBookEntryId: String,
    ): StudentTrustedSavedAnswerRule?
}

internal class StudentTrustedSavedAnswerRuleOwner(
    override val learnerId: String,
    private val persistence: StudentTrustedSavedAnswerRulePersistencePort,
    private val responseBindingIssuerProvider: suspend () -> StudentReviewResponseBindingIssuer,
    private val nowEpochMillis: () -> Long,
    private val newReceiptId: () -> String,
    private val leaseValidityMillis: Long = TRUSTED_REVIEW_LEASE_VALIDITY_MILLIS,
) : LearnerBoundStudentTrustedSavedAnswerRulePort {
    private val leaseAuthority = StudentTrustedSavedAnswerLeaseAuthority()

    init {
        learnerId.requireStoreText("Trusted saved answer learner id", MAX_ID_CHARS)
        require(leaseValidityMillis in 1..MAX_TRUSTED_REVIEW_LEASE_VALIDITY_MILLIS)
    }

    override suspend fun issueExactLease(
        request: StudentTrustedSavedAnswerPresentationRequest,
    ): StudentTrustedSavedAnswerLease? {
        val issuedAt = nowEpochMillis().also(::requireTrustedReviewTime)
        val validThrough = runCatching { Math.addExact(issuedAt, leaseValidityMillis) }.getOrNull()
            ?: return null
        val saved = persistence.readExact(
            learnerId,
            request.problemRevisionId,
            request.errorBookEntryId,
        )
            ?.takeIf { answer ->
                answer.problemRevision.problem.learnerId == learnerId &&
                    answer.problemRevision.revisionId == request.problemRevisionId &&
                    answer.errorBookEntryId == request.errorBookEntryId &&
                    answer.questionGeneration == request.questionGeneration &&
                    answer.questionVersion == request.questionVersion &&
                    answer.questionVersion.startsWith(TRUSTED_SAVED_ANSWER_PRESENTATION_V2_PREFIX) &&
                    answer.answerRule.responseForm() == request.responseForm
            } ?: return null
        val receiptId = newReceiptId().also {
            it.requireStoreText("Trusted saved answer lease receipt id", MAX_ID_CHARS)
        }
        val lease = StudentTrustedSavedAnswerLease(
            problemRevisionId = request.problemRevisionId,
            errorBookEntryId = request.errorBookEntryId,
            questionGeneration = request.questionGeneration,
            questionVersion = request.questionVersion,
            responseForm = request.responseForm,
            issuedAtEpochMillis = issuedAt,
            validThroughEpochMillis = validThrough,
            canonicalFingerprint = CanonicalSha256("student-trusted-saved-answer-lease-v2")
                .field("learnerId", learnerId)
                .field("problemRevisionId", request.problemRevisionId)
                .field("errorBookEntryId", request.errorBookEntryId)
                .field("questionGeneration", request.questionGeneration)
                .field("questionVersion", request.questionVersion)
                .field("responseForm", request.responseForm.name)
                .field("receiptId", receiptId)
                .field("issuedAt", issuedAt)
                .field("validThrough", validThrough)
                .finish(),
            receiptId = receiptId,
        )
        return leaseAuthority.issue(lease, saved)
    }

    override suspend fun submitResponse(
        lease: StudentTrustedSavedAnswerLease,
        response: StudentTrustedReviewResponse,
    ): StudentTrustedSavedAnswerSubmissionResult {
        val submittedAt = nowEpochMillis().also(::requireTrustedReviewTime)
        val canonicalResponse = response.canonicalResponseFor(lease.responseForm)
            ?: return StudentTrustedSavedAnswerSubmissionResult.Rejected.also {
                leaseAuthority.revoke(lease)
            }
        val responseBinding = responseBindingIssuerProvider().issue(
            CanonicalSha256("student-trusted-saved-answer-response-scope-v2")
                .field("learnerId", learnerId)
                .field("problemRevisionId", lease.problemRevisionId)
                .field("errorBookEntryId", lease.errorBookEntryId)
                .field("questionGeneration", lease.questionGeneration)
                .field("questionVersion", lease.questionVersion)
                .field("responseForm", lease.responseForm.name)
                .finish(),
            canonicalResponse,
        )
        if (submittedAt !in lease.issuedAtEpochMillis..lease.validThroughEpochMillis) {
            leaseAuthority.revoke(lease)
            return StudentTrustedSavedAnswerSubmissionResult.Rejected
        }
        leaseAuthority.completedEvaluation(lease)?.let { completed ->
            return if (completed.responseBinding == responseBinding) {
                StudentTrustedSavedAnswerSubmissionResult.Accepted(
                    duplicate = true,
                    evaluation = completed,
                )
            } else {
                StudentTrustedSavedAnswerSubmissionResult.Rejected
            }
        }
        val saved = leaseAuthority.consume(lease)
            ?: return StudentTrustedSavedAnswerSubmissionResult.Rejected
        val evaluation = saved.answerRule.evaluateOwnedResponse(response)
            ?: return StudentTrustedSavedAnswerSubmissionResult.Rejected
        check(evaluation.responseForm == lease.responseForm)
        val receipt = StudentTrustedSavedAnswerEvaluationReceipt(
            responseForm = evaluation.responseForm,
            responseBinding = responseBinding,
            selectionWasCorrect = evaluation.outcome == StudentTrustedReviewAnswerOutcome.CORRECT,
            canonicalFingerprint = CanonicalSha256("student-trusted-saved-answer-evaluation-v1")
                .field("learnerId", learnerId)
                .field("problemRevisionId", lease.problemRevisionId)
                .field("errorBookEntryId", lease.errorBookEntryId)
                .field("questionGeneration", lease.questionGeneration)
                .field("questionVersion", lease.questionVersion)
                .field("responseForm", evaluation.responseForm.name)
                .field("responseBinding", responseBinding)
                .field("selectionWasCorrect", evaluation.outcome == StudentTrustedReviewAnswerOutcome.CORRECT)
                .finish(),
        )
        leaseAuthority.complete(lease, receipt)
        return StudentTrustedSavedAnswerSubmissionResult.Accepted(
            duplicate = false,
            evaluation = receipt,
        )
    }
}

private class StudentTrustedSavedAnswerLeaseAuthority {
    private val issued = IdentityHashMap<StudentTrustedSavedAnswerLease, StudentTrustedSavedAnswerRule>()
    private val completed =
        WeakHashMap<StudentTrustedSavedAnswerLease, StudentTrustedSavedAnswerEvaluationReceipt>()

    @Synchronized
    fun issue(
        lease: StudentTrustedSavedAnswerLease,
        saved: StudentTrustedSavedAnswerRule,
    ): StudentTrustedSavedAnswerLease = lease.also {
        check(issued.put(it, saved) == null)
    }

    @Synchronized
    fun consume(lease: StudentTrustedSavedAnswerLease): StudentTrustedSavedAnswerRule? =
        issued.remove(lease)?.takeIf { lease.receiptId.isNotBlank() }

    @Synchronized
    fun complete(
        lease: StudentTrustedSavedAnswerLease,
        evaluation: StudentTrustedSavedAnswerEvaluationReceipt,
    ) {
        check(completed.put(lease, evaluation) == null)
    }

    @Synchronized
    fun completedEvaluation(
        lease: StudentTrustedSavedAnswerLease,
    ): StudentTrustedSavedAnswerEvaluationReceipt? = completed[lease]

    @Synchronized
    fun revoke(lease: StudentTrustedSavedAnswerLease) {
        issued.remove(lease)
    }
}

internal data class OwnedStudentTrustedReviewEvaluation(
    val responseForm: ReviewResponseForm,
    val canonicalResponse: String,
    val outcome: StudentTrustedReviewAnswerOutcome,
)

internal fun StudentTrustedReviewAnswerRule.responseForm(): ReviewResponseForm =
    when (this) {
        is StudentTrustedReviewAnswerRule.Choice -> ReviewResponseForm.CHOICE
        is StudentTrustedReviewAnswerRule.Numeric -> ReviewResponseForm.NUMERIC
        is StudentTrustedReviewAnswerRule.VisualTarget -> ReviewResponseForm.VISUAL_TARGET
    }

internal fun StudentTrustedReviewResponse.canonicalResponseFor(
    expectedForm: ReviewResponseForm,
): String? =
    when {
        this is StudentTrustedReviewResponse.Choice && expectedForm == ReviewResponseForm.CHOICE ->
            encodeOrderedStrings(listOf("choice", choiceId))
        this is StudentTrustedReviewResponse.Numeric && expectedForm == ReviewResponseForm.NUMERIC -> {
            val submitted = value.toOwnedReviewDecimalOrNull() ?: return null
            encodeOrderedStrings(
                listOf("numeric", submitted.toTrustedReviewDecimal(), unit.orEmpty()),
            )
        }
        this is StudentTrustedReviewResponse.VisualTarget &&
            expectedForm == ReviewResponseForm.VISUAL_TARGET ->
            encodeOrderedStrings(listOf("visual-target", targetId))
        else -> null
    }

internal fun StudentTrustedReviewAnswerRule.evaluateOwnedResponse(
    response: StudentTrustedReviewResponse,
): OwnedStudentTrustedReviewEvaluation? =
    when {
        this is StudentTrustedReviewAnswerRule.Choice &&
            response is StudentTrustedReviewResponse.Choice -> {
            if (response.choiceId !in acceptedChoiceIds) return null
            OwnedStudentTrustedReviewEvaluation(
                responseForm = ReviewResponseForm.CHOICE,
                canonicalResponse = encodeOrderedStrings(listOf("choice", response.choiceId)),
                outcome =
                    if (response.choiceId == correctChoiceId) {
                        StudentTrustedReviewAnswerOutcome.CORRECT
                    } else {
                        StudentTrustedReviewAnswerOutcome.INCORRECT
                    },
            )
        }
        this is StudentTrustedReviewAnswerRule.Numeric &&
            response is StudentTrustedReviewResponse.Numeric -> {
            val submitted = response.value.toOwnedReviewDecimalOrNull() ?: return null
            if (response.unit != expectedUnit) return null
            OwnedStudentTrustedReviewEvaluation(
                responseForm = ReviewResponseForm.NUMERIC,
                canonicalResponse =
                    encodeOrderedStrings(
                        listOf("numeric", submitted.toTrustedReviewDecimal(), response.unit.orEmpty()),
                    ),
                outcome =
                    if (submitted.subtract(expectedValue).abs() <= absoluteTolerance) {
                        StudentTrustedReviewAnswerOutcome.CORRECT
                    } else {
                        StudentTrustedReviewAnswerOutcome.INCORRECT
                    },
            )
        }
        this is StudentTrustedReviewAnswerRule.VisualTarget &&
            response is StudentTrustedReviewResponse.VisualTarget -> {
            if (response.targetId !in acceptedTargetIds) return null
            OwnedStudentTrustedReviewEvaluation(
                responseForm = ReviewResponseForm.VISUAL_TARGET,
                canonicalResponse = encodeOrderedStrings(listOf("visual-target", response.targetId)),
                outcome =
                    if (response.targetId in correctTargetIds) {
                        StudentTrustedReviewAnswerOutcome.CORRECT
                    } else {
                        StudentTrustedReviewAnswerOutcome.INCORRECT
                    },
            )
        }
        else -> null
    }

internal data class PersistedStudentTrustedReviewLease(
    val receiptId: String,
    val planId: String,
    val sessionId: String,
    val queueItemId: String,
    val expectedSessionVersion: Long,
    val presentationId: String,
    val problemRevision: StudentProblemRevisionRef,
    val errorBookEntryId: String,
    val questionGeneration: Long,
    val questionVersion: String,
    val answerRule: StudentTrustedReviewAnswerRule,
    val issuedAtEpochMillis: Long,
    val validThroughEpochMillis: Long,
    val canonicalFingerprint: String,
)

internal interface StudentTrustedReviewAnswerPersistencePort {
    suspend fun issueCurrentLease(
        learnerId: String,
        receiptId: String,
        issuedAtEpochMillis: Long,
        validThroughEpochMillis: Long,
    ): PersistedStudentTrustedReviewLease?

    suspend fun submitResponse(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        response: StudentTrustedReviewResponse,
        submittedAtEpochMillis: Long,
    ): StudentTrustedReviewSubmissionResult =
        StudentTrustedReviewSubmissionResult.StorageUnavailable

    suspend fun claimAttempt(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        submittedAtEpochMillis: Long,
    ): StudentTrustedReviewAttemptSnapshot?

    suspend fun recordAssistance(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        assistanceEventId: String,
        kind: StudentTrustedReviewAssistanceKind,
        occurredAtEpochMillis: Long,
    ): StudentTrustedReviewAssistanceResult
}

internal class StudentTrustedReviewAnswerOwner(
    override val learnerId: String,
    private val persistence: StudentTrustedReviewAnswerPersistencePort,
    private val nowEpochMillis: () -> Long,
    private val leaseValidityMillis: Long = TRUSTED_REVIEW_LEASE_VALIDITY_MILLIS,
    private val newReceiptId: () -> String,
) : LearnerBoundStudentTrustedReviewAnswerPort {
    private val leaseAuthority = StudentTrustedReviewLeaseAuthority()

    init {
        learnerId.requireStoreText("Trusted review learner id", MAX_ID_CHARS)
        require(leaseValidityMillis in 1..MAX_TRUSTED_REVIEW_LEASE_VALIDITY_MILLIS) {
            "Trusted review lease validity is outside the supported range"
        }
    }

    override suspend fun issueCurrentLease(): StudentTrustedReviewAnswerLease? {
        val issuedAt = nowEpochMillis().also(::requireTrustedReviewTime)
        val validThrough =
            runCatching { Math.addExact(issuedAt, leaseValidityMillis) }.getOrNull()
                ?: return null
        val receiptId = newReceiptId()
        receiptId.requireStoreText("Trusted review lease receipt id", MAX_ID_CHARS)
        val persisted =
            persistence.issueCurrentLease(
                learnerId = learnerId,
                receiptId = receiptId,
                issuedAtEpochMillis = issuedAt,
                validThroughEpochMillis = validThrough,
            ) ?: return null
        return leaseAuthority.issue(persisted)
    }

    override suspend fun submitResponse(
        lease: StudentTrustedReviewAnswerLease,
        response: StudentTrustedReviewResponse,
    ): StudentTrustedReviewSubmissionResult {
        val receiptId =
            leaseAuthority.verify(lease)
                ?: return StudentTrustedReviewSubmissionResult.ReloadRequired
        val submittedAt = nowEpochMillis().also(::requireTrustedReviewTime)
        val result =
            try {
                persistence.submitResponse(
                    learnerId = learnerId,
                    leaseReceiptId = receiptId,
                    leaseCanonicalFingerprint = lease.canonicalFingerprint,
                    response = response,
                    submittedAtEpochMillis = submittedAt,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                StudentTrustedReviewSubmissionResult.StorageUnavailable
            }
        if (
            result is StudentTrustedReviewSubmissionResult.Recorded ||
            result == StudentTrustedReviewSubmissionResult.ReloadRequired ||
            result == StudentTrustedReviewSubmissionResult.StorageUnavailable
        ) {
            leaseAuthority.revoke(lease)
        }
        return result
    }

    override suspend fun recordAssistance(
        lease: StudentTrustedReviewAnswerLease,
        assistanceEventId: String,
        kind: StudentTrustedReviewAssistanceKind,
    ): StudentTrustedReviewAssistanceResult {
        assistanceEventId.requireStoreText(
            "Trusted review assistance event id",
            MAX_ID_CHARS,
        )
        val receiptId =
            leaseAuthority.verify(lease) ?: return StudentTrustedReviewAssistanceResult.Unavailable
        val occurredAt = nowEpochMillis().also(::requireTrustedReviewTime)
        val result =
            try {
                persistence.recordAssistance(
                    learnerId = learnerId,
                    leaseReceiptId = receiptId,
                    leaseCanonicalFingerprint = lease.canonicalFingerprint,
                    assistanceEventId = assistanceEventId,
                    kind = kind,
                    occurredAtEpochMillis = occurredAt,
                )
            } catch (cancelled: CancellationException) {
                leaseAuthority.revoke(lease)
                throw cancelled
            } catch (_: Exception) {
                StudentTrustedReviewAssistanceResult.Unavailable
            }
        if (result == StudentTrustedReviewAssistanceResult.Unavailable) {
            leaseAuthority.revoke(lease)
        }
        return result
    }
}

private class StudentTrustedReviewLeaseAuthority {
    private val issued = IdentityHashMap<StudentTrustedReviewAnswerLease, String>()

    @Synchronized
    fun issue(value: PersistedStudentTrustedReviewLease): StudentTrustedReviewAnswerLease =
        StudentTrustedReviewAnswerLease(
            planId = value.planId,
            sessionId = value.sessionId,
            queueItemId = value.queueItemId,
            expectedSessionVersion = value.expectedSessionVersion,
            presentationId = value.presentationId,
            problemRevision = value.problemRevision,
            errorBookEntryId = value.errorBookEntryId,
            questionGeneration = value.questionGeneration,
            questionVersion = value.questionVersion,
            issuedAtEpochMillis = value.issuedAtEpochMillis,
            validThroughEpochMillis = value.validThroughEpochMillis,
            canonicalFingerprint = value.canonicalFingerprint,
            receiptId = value.receiptId,
        ).also { lease ->
            check(issued.put(lease, value.receiptId) == null)
        }

    @Synchronized
    fun verify(lease: StudentTrustedReviewAnswerLease): String? =
        issued[lease]?.takeIf { receiptId -> receiptId == lease.receiptId }

    @Synchronized
    fun consume(lease: StudentTrustedReviewAnswerLease): String? =
        issued.remove(lease)?.takeIf { receiptId -> receiptId == lease.receiptId }

    @Synchronized
    fun revoke(lease: StudentTrustedReviewAnswerLease) {
        issued.remove(lease)
    }
}

internal fun BigDecimal.toTrustedReviewDecimal(): String =
    stripTrailingZeros().let { normalized ->
        if (normalized.compareTo(BigDecimal.ZERO) == 0) "0" else normalized.toPlainString()
    }

internal fun BigDecimal.hasTrustedReviewDecimalShape(): Boolean =
    precision() <= MAX_TRUSTED_REVIEW_DECIMAL_PRECISION &&
        scale() in -MAX_TRUSTED_REVIEW_DECIMAL_SCALE..MAX_TRUSTED_REVIEW_DECIMAL_SCALE &&
        toTrustedReviewDecimal().length <= MAX_TRUSTED_REVIEW_DECIMAL_CHARS

private fun String.isTrustedReviewRuleText(): Boolean =
    isNotBlank() &&
        this == trim() &&
        length <= MAX_TRUSTED_REVIEW_RULE_TEXT_CHARS &&
        none { character -> character.isISOControl() }

private fun requireTrustedReviewTime(value: Long) {
    require(value >= 0) { "Trusted review owner time must not be negative" }
}

internal const val TRUSTED_REVIEW_LEASE_VALIDITY_MILLIS = 2 * 60 * 1_000L
const val TRUSTED_SAVED_ANSWER_PRESENTATION_V2_PREFIX = "current-tutor-visible-presentation-v2:"
private const val MAX_TRUSTED_REVIEW_LEASE_VALIDITY_MILLIS = 15 * 60 * 1_000L
private const val MAX_TRUSTED_REVIEW_TARGETS = 32
private const val MAX_TRUSTED_REVIEW_RULE_TEXT_CHARS = 4_096
private const val MAX_TRUSTED_REVIEW_DECIMAL_PRECISION = 128
private const val MAX_TRUSTED_REVIEW_DECIMAL_SCALE = 64
private const val MAX_TRUSTED_REVIEW_DECIMAL_CHARS = 256
