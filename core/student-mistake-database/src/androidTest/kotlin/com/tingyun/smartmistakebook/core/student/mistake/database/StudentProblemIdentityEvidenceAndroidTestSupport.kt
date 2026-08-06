package com.tingyun.smartmistakebook.core.student.mistake.database

internal fun createStudentProblemIdentityEvidenceOwner(
    learnerId: String,
    ledger: StudentProblemIdentityReceiptLedger,
    authority: StudentProblemIdentityEvidenceAuthority,
    captureOccurrences: LearnerBoundStudentCaptureOccurrencePort,
    trustedSourceAdmissionOwner: TrustedStudentProblemSourceAdmissionOwner? = null,
    reviewedAliasAdmissionOwner: ReviewedStudentProblemAliasAdmissionOwner? = null,
    nowEpochMillis: () -> Long = System::currentTimeMillis,
    receiptValidityMillis: Long = 15 * 60 * 1_000L,
): StudentProblemIdentityEvidenceOwner =
    invokePrivateStudentProblemIdentityEvidenceOwnerAssembly(
        learnerId,
        ledger,
        authority,
        captureOccurrences,
        trustedSourceAdmissionOwner,
        reviewedAliasAdmissionOwner,
        nowEpochMillis,
        receiptValidityMillis,
    )

private fun invokePrivateStudentProblemIdentityEvidenceOwnerAssembly(
    learnerId: String,
    ledger: StudentProblemIdentityReceiptLedger,
    authority: StudentProblemIdentityEvidenceAuthority,
    captureOccurrences: LearnerBoundStudentCaptureOccurrencePort,
    trustedSourceAdmissionOwner: TrustedStudentProblemSourceAdmissionOwner?,
    reviewedAliasAdmissionOwner: ReviewedStudentProblemAliasAdmissionOwner?,
    nowEpochMillis: () -> Long,
    receiptValidityMillis: Long,
): StudentProblemIdentityEvidenceOwner {
    val method =
        Class
            .forName(
                "com.tingyun.smartmistakebook.core.student.mistake.database." +
                    "StudentProblemIdentityEvidenceOwnerKt",
            ).declaredMethods
            .single { it.name == "assembleStudentProblemIdentityEvidenceOwner" }
            .apply { isAccessible = true }
    return method.invoke(
        null,
        learnerId,
        ledger,
        authority,
        captureOccurrences,
        trustedSourceAdmissionOwner,
        reviewedAliasAdmissionOwner,
        nowEpochMillis,
        receiptValidityMillis,
        StudentMistakeOwnerKey.INSTANCE,
    ) as StudentProblemIdentityEvidenceOwner
}

internal suspend fun StudentProblemIdentityEvidenceOwner.saveExactAssetSelectionOrUnresolved(
    command: SaveStudentCaptureOccurrenceCommand,
): StudentCaptureOccurrenceReceipt =
    invokeSaveExactAssetSelectionOrUnresolved(command)

internal suspend fun StudentProblemIdentityEvidenceOwner.registerTrustedSource(
    command: RegisterTrustedStudentProblemSourceCommand,
): StudentProblemIdentityEvidenceAuthority.ReceiptReference =
    invokeRegisterTrustedSource(command)

internal suspend fun StudentProblemIdentityEvidenceOwner.registerReviewedAlias(
    command: RegisterReviewedStudentProblemAliasCommand,
): StudentProblemIdentityEvidenceAuthority.ReceiptReference =
    invokeRegisterReviewedAlias(command)

internal suspend fun StudentProblemIdentityEvidenceOwner.verifyAndIssueTrustedSource(
    reference: StudentProblemIdentityEvidenceAuthority.ReceiptReference,
    command: SaveStudentCaptureOccurrenceCommand,
): StudentProblemIdentityEvidenceAuthority.TrustedSourceEvidence =
    invokeVerifyAndIssueTrustedSource(reference, command)

internal suspend fun StudentProblemIdentityEvidenceOwner.verifyAndIssueReviewedAlias(
    reference: StudentProblemIdentityEvidenceAuthority.ReceiptReference,
    command: SaveStudentCaptureOccurrenceCommand,
): StudentProblemIdentityEvidenceAuthority.ReviewedAliasEvidence =
    invokeVerifyAndIssueReviewedAlias(reference, command)

internal suspend fun StudentProblemIdentityEvidenceOwner.verifyAndSaveTrustedSource(
    reference: StudentProblemIdentityEvidenceAuthority.ReceiptReference,
    command: SaveStudentCaptureOccurrenceCommand,
): StudentCaptureOccurrenceReceipt =
    invokeVerifyAndSaveTrustedSource(reference, command)

internal suspend fun StudentProblemIdentityEvidenceOwner.verifyAndSaveReviewedAlias(
    reference: StudentProblemIdentityEvidenceAuthority.ReceiptReference,
    command: SaveStudentCaptureOccurrenceCommand,
): StudentCaptureOccurrenceReceipt =
    invokeVerifyAndSaveReviewedAlias(reference, command)
