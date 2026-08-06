package com.tingyun.smartmistakebook.core.student.mistake.database;

import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef;

/** Test-only lease issuer. Production leases remain owned by the student-mistake authority. */
public final class StudentTrustedReviewAnswerLeaseTestFactory {
    private StudentTrustedReviewAnswerLeaseTestFactory() {}

    public static StudentTrustedReviewAnswerLease issue(
            String planId,
            String sessionId,
            String queueItemId,
            long expectedSessionVersion,
            String presentationId,
            StudentProblemRevisionRef problemRevision,
            long issuedAtEpochMillis,
            long validThroughEpochMillis) {
        return new StudentTrustedReviewAnswerLease(
                planId,
                sessionId,
                queueItemId,
                expectedSessionVersion,
                presentationId,
                problemRevision,
                "error-book-entry-1",
                7L,
                "question-v9",
                issuedAtEpochMillis,
                validThroughEpochMillis,
                "a".repeat(64),
                "test-owner-receipt-1");
    }
}
