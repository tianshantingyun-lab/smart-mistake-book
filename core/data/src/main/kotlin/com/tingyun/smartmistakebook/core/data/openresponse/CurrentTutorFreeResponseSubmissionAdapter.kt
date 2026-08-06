package com.tingyun.smartmistakebook.core.data.openresponse

/** Host-only request. No feature or Compose type can construct the learning authority context. */
internal class CurrentTutorFreeResponseSubmission(
    val contextRequest: CoreDataTutorOpenResponseContextRequest,
    val expectedPresentationFingerprint: String,
    val expectedProblemFingerprint: String,
    val expectedProblemFamilyFingerprint: String,
    val actionToken: String,
    val answerBinding: String,
    val answer: String,
    val elapsedDurationMillis: Long?,
    val occurredAtEpochMillis: Long,
    val isCurrent: () -> Boolean,
) {
    override fun toString(): String =
        "CurrentTutorFreeResponseSubmission(action=<redacted>, answer=<redacted>)"
}

internal fun interface CurrentTutorFreeResponseSubmissionPort {
    suspend fun submit(
        submission: CurrentTutorFreeResponseSubmission,
    ): CoreDataTutorOpenResponseSubmissionResult
}

/**
 * Narrows the shared open-response learning host to one already-authorized current Host action.
 * The only learning path remains independent evaluation followed by revisable weak evidence.
 */
internal class CurrentTutorFreeResponseSubmissionAdapter(
    private val learningHost: CoreDataTutorOpenResponseLearningHostPort,
) : CurrentTutorFreeResponseSubmissionPort {
    override suspend fun submit(
        submission: CurrentTutorFreeResponseSubmission,
    ): CoreDataTutorOpenResponseSubmissionResult {
        val context = learningHost.issueContext(submission.contextRequest)
            ?: return unavailable(CoreDataTutorOpenResponseLearningReceipt.NOT_CURRENT)
        if (
            context.presentationFingerprint != submission.expectedPresentationFingerprint ||
            context.problemFingerprint != submission.expectedProblemFingerprint ||
            context.problemFamilyFingerprint != submission.expectedProblemFamilyFingerprint
        ) {
            learningHost.revoke()
            return unavailable(CoreDataTutorOpenResponseLearningReceipt.REJECTED)
        }
        val lease = CoreDataTutorOpenResponseSubmissionLease.afterDurableClaim(
            featureContextFingerprint = context.featureContextFingerprint,
            responseBinding = submission.answerBinding,
            isCurrentBlock = submission.isCurrent,
        )
        val sourceId = "current-tutor-free-response:${submission.actionToken}"
        return learningHost.submitAdmittedWithReceipt(
            submission = CoreDataTutorOpenResponseAdmittedSubmission(
                context = context,
                sourceSubmissionId = sourceId,
                sourceMessageId = sourceId,
                admission = CoreDataTutorOpenResponseAdmission.GuidedFreeResponse(
                    evidenceRequestId = submission.contextRequest.evidenceRequestId,
                ),
                currentAnswer = submission.answer,
                responseBinding = submission.answerBinding,
                elapsedDurationMillis = submission.elapsedDurationMillis,
                occurredAtEpochMillis = submission.occurredAtEpochMillis,
            ),
            lease = lease,
        )
    }
}

private fun unavailable(
    disposition: CoreDataTutorOpenResponseLearningReceipt,
) = CoreDataTutorOpenResponseSubmissionResult(disposition, null)
