package com.tingyun.smartmistakebook.core.student.mistake.database

/** Joins the answer-rule DAO and review-session DAO under one Room transaction context. */
internal class RoomStudentTrustedReviewAnswerPersistence(
    private val answers: StudentTrustedReviewAnswerDao,
    private val reviews: StudentMistakeDao,
    private val storeGenerationProvider: suspend (Long) -> String,
    private val outboxIssuerProvider:
        suspend (learnerId: String, sourceStoreGeneration: String) ->
            StudentOutboxAuthenticator,
) : StudentTrustedReviewAnswerPersistencePort {
    override suspend fun issueCurrentLease(
        learnerId: String,
        receiptId: String,
        issuedAtEpochMillis: Long,
        validThroughEpochMillis: Long,
    ): PersistedStudentTrustedReviewLease? =
        answers.issueCurrentLease(
            learnerId = learnerId,
            receiptId = receiptId,
            issuedAtEpochMillis = issuedAtEpochMillis,
            validThroughEpochMillis = validThroughEpochMillis,
        )

    override suspend fun submitResponse(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        response: StudentTrustedReviewResponse,
        submittedAtEpochMillis: Long,
    ): StudentTrustedReviewSubmissionResult {
        val sourceStoreGeneration = storeGenerationProvider(submittedAtEpochMillis)
        val outboxIssuer = outboxIssuerProvider(learnerId, sourceStoreGeneration)
        val responseBindingIssuer = StudentOutboxBoundReviewResponseBindingIssuer(outboxIssuer)
        return try {
            answers.submitOwnedResponse(
                learnerId = learnerId,
                leaseReceiptId = leaseReceiptId,
                leaseCanonicalFingerprint = leaseCanonicalFingerprint,
                response = response,
                submittedAtEpochMillis = submittedAtEpochMillis,
                sourceStoreGeneration = sourceStoreGeneration,
                outboxIssuer = outboxIssuer,
                responseBindingIssuer = responseBindingIssuer,
                transitionWriter = StudentTrustedReviewTransitionWriter(reviews::applyReviewTransition),
            )
        } catch (_: StudentTrustedReviewSubmissionRollbackException) {
            StudentTrustedReviewSubmissionResult.ReloadRequired
        }
    }

    override suspend fun recordAssistance(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        assistanceEventId: String,
        kind: StudentTrustedReviewAssistanceKind,
        occurredAtEpochMillis: Long,
    ): StudentTrustedReviewAssistanceResult =
        answers.recordAssistance(
            learnerId = learnerId,
            leaseReceiptId = leaseReceiptId,
            leaseCanonicalFingerprint = leaseCanonicalFingerprint,
            assistanceEventId = assistanceEventId,
            kind = kind,
            occurredAtEpochMillis = occurredAtEpochMillis,
        )

    override suspend fun claimAttempt(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        submittedAtEpochMillis: Long,
    ): StudentTrustedReviewAttemptSnapshot? =
        answers.claimAttempt(
            learnerId = learnerId,
            leaseReceiptId = leaseReceiptId,
            leaseCanonicalFingerprint = leaseCanonicalFingerprint,
            submittedAtEpochMillis = submittedAtEpochMillis,
        )
}
