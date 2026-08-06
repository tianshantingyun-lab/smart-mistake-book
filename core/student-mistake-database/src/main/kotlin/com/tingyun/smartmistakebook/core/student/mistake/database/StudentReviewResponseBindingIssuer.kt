package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef

internal fun studentReviewResponseBindingScopeFingerprint(
    learnerId: String,
    problemRevision: StudentProblemRevisionRef,
    reviewSessionId: String,
    reviewQueueItemId: String,
    presentationId: String,
    attemptOrdinal: Int,
    submissionId: String,
    responseForm: ReviewResponseForm,
): String =
    CanonicalSha256("student-review-response-binding-scope-v1")
        .field("learnerId", learnerId)
        .field("problemRevision", problemRevision.canonicalFingerprint)
        .field("reviewSessionId", reviewSessionId)
        .field("reviewQueueItemId", reviewQueueItemId)
        .field("presentationId", presentationId)
        .field("attemptOrdinal", attemptOrdinal)
        .field("submissionId", submissionId)
        .field("responseForm", responseForm.name)
        .finish()
