package com.tingyun.smartmistakebook.core.student.mistake.database;

import kotlin.coroutines.Continuation;

/**
 * Bytecode-private learner owner for identity receipt admission and occurrence persistence.
 *
 * <p>The executable operations are supplied only by the student-mistake runtime factory. The
 * class, delegate type, constructor, and every operation are package-private, so another module
 * cannot obtain a verifier, ledger DAO, raw occurrence writer, or public owner constructor from
 * JVM bytecode.
 */
final class StudentProblemIdentityEvidenceOwner {
    /**
     * Strongly typed coroutine bridge implemented by the private Kotlin owner core.
     *
     * <p>The {@code Object} return is the JVM coroutine ABI; every continuation retains its exact
     * domain result type and the bridge is inaccessible outside this package.
     */
    abstract static class Delegate {
        abstract Object saveExactAssetSelectionOrUnresolved(
                SaveStudentCaptureOccurrenceCommand command,
                Continuation<? super StudentCaptureOccurrenceReceipt> continuation);

        abstract Object registerTrustedSource(
                RegisterTrustedStudentProblemSourceCommand command,
                Continuation<? super StudentProblemIdentityEvidenceAuthority.ReceiptReference>
                        continuation);

        abstract Object registerReviewedAlias(
                RegisterReviewedStudentProblemAliasCommand command,
                Continuation<? super StudentProblemIdentityEvidenceAuthority.ReceiptReference>
                        continuation);

        abstract Object verifyAndIssueTrustedSource(
                StudentProblemIdentityEvidenceAuthority.ReceiptReference reference,
                SaveStudentCaptureOccurrenceCommand command,
                Continuation<
                                ? super StudentProblemIdentityEvidenceAuthority
                                        .TrustedSourceEvidence>
                        continuation);

        abstract Object verifyAndIssueReviewedAlias(
                StudentProblemIdentityEvidenceAuthority.ReceiptReference reference,
                SaveStudentCaptureOccurrenceCommand command,
                Continuation<
                                ? super StudentProblemIdentityEvidenceAuthority
                                        .ReviewedAliasEvidence>
                        continuation);

        abstract Object verifyAndSaveTrustedSource(
                StudentProblemIdentityEvidenceAuthority.ReceiptReference reference,
                SaveStudentCaptureOccurrenceCommand command,
                Continuation<? super StudentCaptureOccurrenceReceipt> continuation);

        abstract Object verifyAndSaveReviewedAlias(
                StudentProblemIdentityEvidenceAuthority.ReceiptReference reference,
                SaveStudentCaptureOccurrenceCommand command,
                Continuation<? super StudentCaptureOccurrenceReceipt> continuation);
    }

    private final String learnerId;
    private final Delegate delegate;

    StudentProblemIdentityEvidenceOwner(
            String learnerId,
            Delegate delegate) {
        this.learnerId = learnerId;
        this.delegate = delegate;
    }

    String getLearnerId() {
        return learnerId;
    }

    Object invokeSaveExactAssetSelectionOrUnresolved(
            SaveStudentCaptureOccurrenceCommand command,
            Continuation<? super StudentCaptureOccurrenceReceipt> continuation) {
        return delegate.saveExactAssetSelectionOrUnresolved(command, continuation);
    }

    Object invokeRegisterTrustedSource(
            RegisterTrustedStudentProblemSourceCommand command,
            Continuation<? super StudentProblemIdentityEvidenceAuthority.ReceiptReference>
                    continuation) {
        return delegate.registerTrustedSource(command, continuation);
    }

    Object invokeRegisterReviewedAlias(
            RegisterReviewedStudentProblemAliasCommand command,
            Continuation<? super StudentProblemIdentityEvidenceAuthority.ReceiptReference>
                    continuation) {
        return delegate.registerReviewedAlias(command, continuation);
    }

    Object invokeVerifyAndIssueTrustedSource(
            StudentProblemIdentityEvidenceAuthority.ReceiptReference reference,
            SaveStudentCaptureOccurrenceCommand command,
            Continuation<
                            ? super StudentProblemIdentityEvidenceAuthority.TrustedSourceEvidence>
                    continuation) {
        return delegate.verifyAndIssueTrustedSource(reference, command, continuation);
    }

    Object invokeVerifyAndIssueReviewedAlias(
            StudentProblemIdentityEvidenceAuthority.ReceiptReference reference,
            SaveStudentCaptureOccurrenceCommand command,
            Continuation<
                            ? super StudentProblemIdentityEvidenceAuthority.ReviewedAliasEvidence>
                    continuation) {
        return delegate.verifyAndIssueReviewedAlias(reference, command, continuation);
    }

    Object invokeVerifyAndSaveTrustedSource(
            StudentProblemIdentityEvidenceAuthority.ReceiptReference reference,
            SaveStudentCaptureOccurrenceCommand command,
            Continuation<? super StudentCaptureOccurrenceReceipt> continuation) {
        return delegate.verifyAndSaveTrustedSource(reference, command, continuation);
    }

    Object invokeVerifyAndSaveReviewedAlias(
            StudentProblemIdentityEvidenceAuthority.ReceiptReference reference,
            SaveStudentCaptureOccurrenceCommand command,
            Continuation<? super StudentCaptureOccurrenceReceipt> continuation) {
        return delegate.verifyAndSaveReviewedAlias(reference, command, continuation);
    }
}
