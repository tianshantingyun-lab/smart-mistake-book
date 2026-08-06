package com.tingyun.smartmistakebook.core.student.mistake.database

import android.content.Context
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofVerifier

internal fun interface TrustedStudentProblemSourceAdmissionOwner {
    suspend fun requireTrustedLocalState(
        command: RegisterTrustedStudentProblemSourceCommand,
    )
}

internal fun interface ReviewedStudentProblemAliasAdmissionOwner {
    suspend fun requireReviewedLocalState(
        command: RegisterReviewedStudentProblemAliasCommand,
    )
}

/**
 * Learner-bound owner of the durable receipt ledger and the matching process-local seal.
 *
 * Registration, durable lookup, exact candidate verification and proof issuance stay together so
 * capture code can never inject a verifier or combine a port with another owner's signer.
 * Trusted-source and reviewed-alias registration additionally require independent local owners;
 * the production runtime currently supplies neither and therefore fails those paths closed.
 */
private class StudentProblemIdentityEvidenceOwnerCore(
    val learnerId: String,
    private val ledger: StudentProblemIdentityReceiptLedger,
    private val authority: StudentProblemIdentityEvidenceAuthority,
    private val captureOccurrences: LearnerBoundStudentCaptureOccurrencePort,
    private val trustedSourceAdmissionOwner:
        TrustedStudentProblemSourceAdmissionOwner?,
    private val reviewedAliasAdmissionOwner:
        ReviewedStudentProblemAliasAdmissionOwner?,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val receiptValidityMillis: Long = DEFAULT_RECEIPT_VALIDITY_MILLIS,
) : StudentProblemIdentityEvidenceOwner.Delegate() {
    init {
        learnerId.requireStoreText("Identity evidence owner learner id", MAX_ID_CHARS)
        require(captureOccurrences.learnerId == learnerId) {
            "Identity evidence owner and occurrence authority cross learner scopes"
        }
        require(receiptValidityMillis in 1..MAX_RECEIPT_VALIDITY_MILLIS) {
            "Identity receipt validity is outside the owner policy"
        }
    }

    override suspend fun registerTrustedSource(
        command: RegisterTrustedStudentProblemSourceCommand,
    ): StudentProblemIdentityEvidenceAuthority.ReceiptReference {
        checkNotNull(trustedSourceAdmissionOwner) {
            "Trusted-source identity admission is unavailable"
        }.requireTrustedLocalState(command)
        val candidate = command.capture.requireOwnerCandidate()
        val receiptId =
            canonicalReceiptId(
                registrationId = command.registrationId,
                kind = StudentProblemIdentityReceiptKind.TRUSTED_SOURCE,
                candidate = candidate,
                renewalGeneration = 1,
                discriminator =
                    CanonicalSha256(TRUSTED_SOURCE_REGISTRATION_DOMAIN)
                        .field("locatorNamespace", command.locatorNamespace)
                        .field("locatorVersion", command.locatorVersion)
                        .field(
                            "itemLocatorCanonicalFingerprint",
                            command.itemLocatorCanonicalFingerprint,
                        )
                        .finish(),
            )
        ledger.read(receiptId)?.let { existing ->
            existing.requireTrustedRegistration(command, candidate)
            return authority.issuer.issueReference(existing)
        }
        val issuedAt = nowEpochMillis()
        val expiresAt = issuedAt + receiptValidityMillis
        val receipt =
            newReceipt(
                receiptId = receiptId,
                kind = StudentProblemIdentityReceiptKind.TRUSTED_SOURCE,
                candidate = candidate,
                renewalGeneration = 1,
                locatorNamespace = command.locatorNamespace,
                locatorVersion = command.locatorVersion,
                itemLocatorCanonicalFingerprint =
                    command.itemLocatorCanonicalFingerprint,
                issuedAtEpochMillis = issuedAt,
                expiresAtEpochMillis = expiresAt,
            )
        val persisted = ledger.record(receipt)
        persisted.requireTrustedRegistration(command, candidate)
        return authority.issuer.issueReference(persisted)
    }

    override suspend fun registerReviewedAlias(
        command: RegisterReviewedStudentProblemAliasCommand,
    ): StudentProblemIdentityEvidenceAuthority.ReceiptReference {
        checkNotNull(reviewedAliasAdmissionOwner) {
            "Reviewed-alias identity admission is unavailable"
        }.requireReviewedLocalState(command)
        val candidate = command.capture.requireOwnerCandidate()
        val existingIdentityFingerprint =
            command.existingIdentity.canonicalFingerprint(
                learnerId = candidate.learnerId,
                subject = candidate.subject,
            )
        val receiptId =
            canonicalReceiptId(
                registrationId = command.registrationId,
                kind = StudentProblemIdentityReceiptKind.REVIEWED_ALIAS,
                candidate = candidate,
                renewalGeneration = 1,
                discriminator =
                    CanonicalSha256(REVIEWED_ALIAS_REGISTRATION_DOMAIN)
                        .field("reviewAuthorityKind", command.reviewAuthorityKind.name)
                        .field(
                            "existingIdentityCanonicalFingerprint",
                            existingIdentityFingerprint,
                        )
                        .field("reviewCaseId", command.reviewCaseId)
                        .field("reviewRevision", command.reviewRevision)
                        .field(
                            "reviewDecisionCanonicalFingerprint",
                            command.reviewDecisionCanonicalFingerprint,
                        )
                        .finish(),
            )
        ledger.read(receiptId)?.let { existing ->
            existing.requireReviewedAliasRegistration(
                command = command,
                candidate = candidate,
                existingIdentityFingerprint = existingIdentityFingerprint,
            )
            return authority.issuer.issueReference(existing)
        }
        val issuedAt = nowEpochMillis()
        val expiresAt = issuedAt + receiptValidityMillis
        val receipt =
            newReceipt(
                receiptId = receiptId,
                kind = StudentProblemIdentityReceiptKind.REVIEWED_ALIAS,
                candidate = candidate,
                renewalGeneration = 1,
                reviewAuthorityKind = command.reviewAuthorityKind,
                existingIdentity = command.existingIdentity,
                existingIdentityCanonicalFingerprint =
                    existingIdentityFingerprint,
                reviewCaseId = command.reviewCaseId,
                reviewRevision = command.reviewRevision,
                reviewDecisionCanonicalFingerprint =
                    command.reviewDecisionCanonicalFingerprint,
                issuedAtEpochMillis = issuedAt,
                expiresAtEpochMillis = expiresAt,
            )
        val persisted = ledger.record(receipt)
        persisted.requireReviewedAliasRegistration(
            command = command,
            candidate = candidate,
            existingIdentityFingerprint = existingIdentityFingerprint,
        )
        return authority.issuer.issueReference(persisted)
    }

    override suspend fun verifyAndIssueTrustedSource(
        reference: StudentProblemIdentityEvidenceAuthority.ReceiptReference,
        command: SaveStudentCaptureOccurrenceCommand,
    ): StudentProblemIdentityEvidenceAuthority.TrustedSourceEvidence {
        val receipt =
            requireCurrentReceipt(
                reference = reference,
                expectedKind = StudentProblemIdentityReceiptKind.TRUSTED_SOURCE,
                command = command,
            )
        return StudentProblemIdentityEvidenceAuthority.TrustedSourceEvidence(
            authority.issuer.issueTrustedSource(receipt),
        )
    }

    override suspend fun saveExactAssetSelectionOrUnresolved(
        command: SaveStudentCaptureOccurrenceCommand,
    ): StudentCaptureOccurrenceReceipt {
        if (command.capture.target.problem.originalImages.isEmpty()) {
            command.requireOwnerCandidate()
            return captureOccurrences.save(
                command.copy(identityEvidence = StudentProblemIdentityEvidence.Unresolved),
            )
        }
        val reference = registerExactAssetSelection(command)
        val receipt =
            requireCurrentReceipt(
                reference = reference,
                expectedKind = StudentProblemIdentityReceiptKind.EXACT_ASSET_SELECTION,
                command = command,
            )
        receipt.requireExactAssetSelectionRegistration(command.toIdentityCandidateBinding())
        return captureOccurrences.save(
            command.copy(identityEvidence = StudentProblemIdentityEvidence.ExactAssetSelection),
        )
    }

    override suspend fun verifyAndSaveTrustedSource(
        reference: StudentProblemIdentityEvidenceAuthority.ReceiptReference,
        command: SaveStudentCaptureOccurrenceCommand,
    ): StudentCaptureOccurrenceReceipt =
        captureOccurrences.save(
            command.copy(
                identityEvidence = verifyAndIssueTrustedSource(reference, command),
            ),
        )

    override suspend fun verifyAndSaveReviewedAlias(
        reference: StudentProblemIdentityEvidenceAuthority.ReceiptReference,
        command: SaveStudentCaptureOccurrenceCommand,
    ): StudentCaptureOccurrenceReceipt =
        captureOccurrences.save(
            command.copy(
                identityEvidence = verifyAndIssueReviewedAlias(reference, command),
            ),
        )

    override suspend fun verifyAndIssueReviewedAlias(
        reference: StudentProblemIdentityEvidenceAuthority.ReceiptReference,
        command: SaveStudentCaptureOccurrenceCommand,
    ): StudentProblemIdentityEvidenceAuthority.ReviewedAliasEvidence {
        val receipt =
            requireCurrentReceipt(
                reference = reference,
                expectedKind = StudentProblemIdentityReceiptKind.REVIEWED_ALIAS,
                command = command,
            )
        val reviewAuthority =
            runCatching {
                StudentProblemAliasReviewAuthorityKind.valueOf(
                    checkNotNull(receipt.reviewAuthorityKind),
                )
            }.getOrElse {
                throw IllegalArgumentException(
                    "Reviewed-alias receipt has no authenticated review authority",
                    it,
                )
            }
        require(
            reviewAuthority == StudentProblemAliasReviewAuthorityKind.HUMAN ||
                reviewAuthority ==
                StudentProblemAliasReviewAuthorityKind.INDEPENDENT_REVIEW,
        ) {
            "Reviewed-alias receipt was not authenticated by an allowed review authority"
        }
        return StudentProblemIdentityEvidenceAuthority.ReviewedAliasEvidence(
            authority.issuer.issueReviewedAlias(receipt),
        )
    }

    private suspend fun requireCurrentReceipt(
        reference: StudentProblemIdentityEvidenceAuthority.ReceiptReference,
        expectedKind: StudentProblemIdentityReceiptKind,
        command: SaveStudentCaptureOccurrenceCommand,
    ): StudentProblemIdentityReceiptEntity {
        require(authority.verifier.verifies(reference)) {
            "Identity receipt reference was not issued by this student owner"
        }
        require(reference.receiptKind == expectedKind) {
            "Identity receipt reference has another evidence kind"
        }
        val receipt =
            checkNotNull(ledger.read(reference.receiptId)) {
                "Identity receipt is unavailable from the student-owner ledger"
            }
        require(receipt.receiptCanonicalFingerprint == reference.receiptCanonicalFingerprint) {
            "Identity receipt reference does not match its durable ledger row"
        }
        require(receipt.receiptKind == expectedKind.name) {
            "Identity receipt ledger row has another evidence kind"
        }
        require(
            receipt.issuerKeyId == authority.issuer.issuerKeyId &&
                receipt.issuerVersion == authority.issuer.issuerVersion,
        ) {
            "Identity receipt belongs to another owner issuer"
        }
        val candidate = command.requireOwnerCandidate()
        require(receipt.toCandidateBinding() == candidate) {
            "Identity receipt does not bind every field of the current capture"
        }
        require(receipt.candidateCanonicalFingerprint == candidate.canonicalFingerprint) {
            "Identity receipt candidate fingerprint is corrupt"
        }
        require(
            receipt.receiptCanonicalFingerprint ==
                receipt.recalculateCanonicalFingerprint(candidate),
        ) {
            "Identity receipt canonical fingerprint is corrupt"
        }
        val now = nowEpochMillis()
        require(now in receipt.issuedAtEpochMillis until receipt.expiresAtEpochMillis) {
            "Identity receipt is not currently valid"
        }
        return receipt
    }

    /**
     * Exact-asset authority is issued only by this learner-bound owner after the complete
     * candidate is durably written and read back. Capture callers never receive an enum or proof
     * constructor with which they could self-promote a non-empty image list.
     */
    private suspend fun registerExactAssetSelection(
        command: SaveStudentCaptureOccurrenceCommand,
    ): StudentProblemIdentityEvidenceAuthority.ReceiptReference {
        val candidate = command.requireOwnerCandidate()
        val now = nowEpochMillis()
        val previous =
            ledger.readLatestCandidate(
                learnerId = learnerId,
                subject = candidate.subject,
                kind = StudentProblemIdentityReceiptKind.EXACT_ASSET_SELECTION,
                assetManifestCanonicalFingerprint =
                    candidate.assetManifestCanonicalFingerprint,
                selectedRegionCanonicalFingerprint =
                    candidate.selectedRegionCanonicalFingerprint,
                candidateCanonicalFingerprint = candidate.canonicalFingerprint,
            )
        if (previous != null) {
            previous.requireExactAssetSelectionRegistration(candidate)
            if (now in previous.issuedAtEpochMillis until previous.expiresAtEpochMillis) {
                return authority.issuer.issueReference(previous)
            }
        }
        val renewalGeneration = (previous?.renewalGeneration ?: 0) + 1
        val receiptId =
            canonicalReceiptId(
                registrationId = command.capture.source.intentId,
                kind = StudentProblemIdentityReceiptKind.EXACT_ASSET_SELECTION,
                candidate = candidate,
                renewalGeneration = renewalGeneration,
                discriminator =
                    CanonicalSha256(EXACT_ASSET_SELECTION_REGISTRATION_DOMAIN)
                        .field(
                            "assetManifestCanonicalFingerprint",
                            candidate.assetManifestCanonicalFingerprint,
                        )
                        .field(
                            "selectedRegionCanonicalFingerprint",
                            candidate.selectedRegionCanonicalFingerprint,
                        )
                        .finish(),
            )
        val issuedAt = now
        val persisted =
            ledger.record(
                newReceipt(
                    receiptId = receiptId,
                    kind = StudentProblemIdentityReceiptKind.EXACT_ASSET_SELECTION,
                    candidate = candidate,
                    renewalGeneration = renewalGeneration,
                    issuedAtEpochMillis = issuedAt,
                    expiresAtEpochMillis = issuedAt + receiptValidityMillis,
                ),
            )
        persisted.requireExactAssetSelectionRegistration(candidate)
        return authority.issuer.issueReference(persisted)
    }

    private fun SaveStudentCaptureOccurrenceCommand.requireOwnerCandidate():
        StudentProblemIdentityCandidateBinding =
        toIdentityCandidateBinding().also { candidate ->
            require(candidate.learnerId == learnerId) {
                "Identity receipt crosses the learner-bound owner"
            }
            require(
                identityEvidence == StudentProblemIdentityEvidence.Unresolved ||
                    identityEvidence == StudentProblemIdentityEvidence.ExactAssetSelection,
            ) {
                "Identity receipt registration cannot be based on an existing owner proof"
            }
        }

    private fun newReceipt(
        receiptId: String,
        kind: StudentProblemIdentityReceiptKind,
        candidate: StudentProblemIdentityCandidateBinding,
        locatorNamespace: String? = null,
        locatorVersion: String? = null,
        itemLocatorCanonicalFingerprint: String? = null,
        reviewAuthorityKind: StudentProblemAliasReviewAuthorityKind? = null,
        existingIdentity: StudentProblemCanonicalIdentityKey? = null,
        existingIdentityCanonicalFingerprint: String? = null,
        reviewCaseId: String? = null,
        reviewRevision: Int? = null,
        reviewDecisionCanonicalFingerprint: String? = null,
        renewalGeneration: Int,
        issuedAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): StudentProblemIdentityReceiptEntity {
        val fingerprint =
            canonicalStudentProblemIdentityReceiptFingerprint(
                fingerprintVersion =
                    STUDENT_PROBLEM_IDENTITY_RECEIPT_FINGERPRINT_VERSION_CURRENT,
                receiptId = receiptId,
                kind = kind,
                issuerKeyId = authority.issuer.issuerKeyId,
                issuerVersion = authority.issuer.issuerVersion,
                candidate = candidate,
                locatorNamespace = locatorNamespace,
                locatorVersion = locatorVersion,
                itemLocatorCanonicalFingerprint =
                    itemLocatorCanonicalFingerprint,
                reviewAuthorityKind = reviewAuthorityKind,
                existingIdentity = existingIdentity,
                existingIdentityCanonicalFingerprint =
                    existingIdentityCanonicalFingerprint,
                reviewCaseId = reviewCaseId,
                reviewRevision = reviewRevision,
                reviewDecisionCanonicalFingerprint =
                    reviewDecisionCanonicalFingerprint,
                renewalGeneration = renewalGeneration,
                issuedAtEpochMillis = issuedAtEpochMillis,
                expiresAtEpochMillis = expiresAtEpochMillis,
            )
        return StudentProblemIdentityReceiptEntity(
            receiptId = receiptId,
            receiptKind = kind.name,
            receiptCanonicalFingerprint = fingerprint,
            fingerprintVersion =
                STUDENT_PROBLEM_IDENTITY_RECEIPT_FINGERPRINT_VERSION_CURRENT,
            issuerKeyId = authority.issuer.issuerKeyId,
            issuerVersion = authority.issuer.issuerVersion,
            learnerId = candidate.learnerId,
            subject = candidate.subject,
            sourceKind = candidate.sourceKind,
            sourceIntentId = candidate.sourceIntentId,
            idempotencyKey = candidate.idempotencyKey,
            sourceCanonicalFingerprint =
                candidate.sourceCanonicalFingerprint,
            locatorNamespace = locatorNamespace,
            locatorVersion = locatorVersion,
            itemLocatorCanonicalFingerprint =
                itemLocatorCanonicalFingerprint,
            problemId = candidate.problemId,
            practiceUnitId = candidate.practiceUnitId,
            revisionId = candidate.revisionId,
            revisionNumber = candidate.revisionNumber,
            documentCanonicalFingerprint =
                candidate.documentCanonicalFingerprint,
            targetCanonicalFingerprint =
                candidate.targetCanonicalFingerprint,
            assetManifestCanonicalFingerprint =
                candidate.assetManifestCanonicalFingerprint,
            selectedRegionCanonicalFingerprint =
                candidate.selectedRegionCanonicalFingerprint,
            candidateCanonicalFingerprint = candidate.canonicalFingerprint,
            reviewAuthorityKind = reviewAuthorityKind?.name,
            existingIdentityNamespace = existingIdentity?.namespace,
            existingIdentityVersion = existingIdentity?.version,
            existingIdentityStableKey = existingIdentity?.stableKey,
            existingIdentityCanonicalFingerprint =
                existingIdentityCanonicalFingerprint,
            reviewCaseId = reviewCaseId,
            reviewRevision = reviewRevision,
            reviewDecisionCanonicalFingerprint =
                reviewDecisionCanonicalFingerprint,
            renewalGeneration = renewalGeneration,
            issuedAtEpochMillis = issuedAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
        )
    }

    private fun canonicalReceiptId(
        registrationId: String,
        kind: StudentProblemIdentityReceiptKind,
        candidate: StudentProblemIdentityCandidateBinding,
        renewalGeneration: Int,
        discriminator: String,
    ): String =
        "identity-receipt-" +
            CanonicalSha256(RECEIPT_ID_DOMAIN)
                .field("learnerId", learnerId)
                .field("registrationId", registrationId)
                .field("receiptKind", kind.name)
                .field("candidateCanonicalFingerprint", candidate.canonicalFingerprint)
                .field("renewalGeneration", renewalGeneration)
                .field("discriminator", discriminator)
                .finish()
                .take(RECEIPT_ID_HASH_CHARS)

    private companion object {
        const val DEFAULT_RECEIPT_VALIDITY_MILLIS = 15 * 60 * 1_000L
        const val MAX_RECEIPT_VALIDITY_MILLIS = 24 * 60 * 60 * 1_000L
        const val RECEIPT_ID_HASH_CHARS = 48
        const val RECEIPT_ID_DOMAIN = "student-problem-identity-receipt-id-v1"
        const val TRUSTED_SOURCE_REGISTRATION_DOMAIN =
            "student-problem-trusted-source-registration-v1"
        const val REVIEWED_ALIAS_REGISTRATION_DOMAIN =
            "student-problem-reviewed-alias-registration-v1"
        const val EXACT_ASSET_SELECTION_REGISTRATION_DOMAIN =
            "student-problem-exact-asset-selection-registration-v1"
    }
}

private fun assembleStudentProblemIdentityEvidenceOwner(
    learnerId: String,
    ledger: StudentProblemIdentityReceiptLedger,
    authority: StudentProblemIdentityEvidenceAuthority,
    captureOccurrences: LearnerBoundStudentCaptureOccurrencePort,
    trustedSourceAdmissionOwner:
        TrustedStudentProblemSourceAdmissionOwner?,
    reviewedAliasAdmissionOwner:
        ReviewedStudentProblemAliasAdmissionOwner?,
    nowEpochMillis: () -> Long,
    receiptValidityMillis: Long,
    ownerKey: StudentMistakeOwnerKey,
): StudentProblemIdentityEvidenceOwner {
    check(ownerKey === StudentMistakeOwnerKey.INSTANCE) {
        "Identity evidence owner requires the student-mistake assembly key"
    }
    val core =
        StudentProblemIdentityEvidenceOwnerCore(
            learnerId = learnerId,
            ledger = ledger,
            authority = authority,
            captureOccurrences = captureOccurrences,
            trustedSourceAdmissionOwner = trustedSourceAdmissionOwner,
            reviewedAliasAdmissionOwner = reviewedAliasAdmissionOwner,
            nowEpochMillis = nowEpochMillis,
            receiptValidityMillis = receiptValidityMillis,
        )
    return StudentProblemIdentityEvidenceOwner(
        learnerId,
        core,
    )
}

internal object StudentMistakeRuntimeFactory {
    fun open(
        context: Context,
        learnerId: String,
        knowledgeReferenceVerifier: KnowledgeReferenceProofVerifier,
        ownerKey: StudentMistakeOwnerKey,
    ): StudentMistakeRuntimeCapabilities {
        check(ownerKey === StudentMistakeOwnerKey.INSTANCE) {
            "Student-mistake runtime requires the core:data owner key"
        }
        val store =
            StudentMistakeOwnedDatabase.openStore(
                context,
                knowledgeReferenceVerifier,
                ownerKey,
            )
        val organizationReviewAuthority =
            StudentProblemOrganizationReviewAuthority.create(
                STUDENT_PROBLEM_ORGANIZATION_REVIEW_ISSUER_KEY_ID,
                STUDENT_PROBLEM_ORGANIZATION_REVIEW_ISSUER_VERSION,
            )
        val identityEvidenceAuthority =
            StudentProblemIdentityEvidenceAuthority.create(
                STUDENT_PROBLEM_IDENTITY_EVIDENCE_ISSUER_KEY_ID,
                STUDENT_PROBLEM_IDENTITY_EVIDENCE_ISSUER_VERSION,
            )
        val outboxAuthenticator = store.outboxAuthenticatorForLearner(learnerId)
        val captureOccurrences =
            store.captureOccurrencesForLearner(
                learnerId,
                identityEvidenceAuthority.verifier,
            )
        val captureHandoffs = store.captureSavesForLearner(learnerId)
        val errorOccurrences = store.errorOccurrencesForLearner(learnerId)
        val organizationSources =
            store.organizationSourcesForLearner(
                learnerId = learnerId,
                captureHandoffs = captureHandoffs,
                errorOccurrences = errorOccurrences,
            )
        val identityEvidenceOwner =
            assembleStudentProblemIdentityEvidenceOwner(
                learnerId = learnerId,
                ledger = store.identityReceiptLedger(),
                authority = identityEvidenceAuthority,
                captureOccurrences = captureOccurrences,
                trustedSourceAdmissionOwner = null,
                reviewedAliasAdmissionOwner = null,
                nowEpochMillis = System::currentTimeMillis,
                receiptValidityMillis = 15 * 60 * 1_000L,
                ownerKey = ownerKey,
            )
        return StudentMistakeRuntimeCapabilities.create(
            learnerId,
            store,
            store.libraryForLearner(learnerId),
            captureHandoffs,
            store.reviewSessionsForLearner(learnerId),
            store.trustedReviewAnswersForLearner(learnerId),
            store.trustedSavedAnswerRulesForLearner(learnerId),
            store.tutorInteractionAnswerCertificatesForLearner(learnerId),
            organizationSources,
            store.organizationForLearner(
                learnerId,
                organizationReviewAuthority.verifier,
            ),
            organizationReviewAuthority.issuer,
            knowledgeReferenceVerifier,
            identityEvidenceOwner,
            store.relayForLearner(learnerId, outboxAuthenticator),
            outboxAuthenticator.verifier,
        )
    }
}

private fun StudentProblemIdentityReceiptEntity.toCandidateBinding() =
    StudentProblemIdentityCandidateBinding(
        learnerId = learnerId,
        subject = subject,
        sourceKind = sourceKind,
        sourceIntentId = sourceIntentId,
        idempotencyKey = idempotencyKey,
        sourceCanonicalFingerprint = sourceCanonicalFingerprint,
        problemId = problemId,
        practiceUnitId = practiceUnitId,
        revisionId = revisionId,
        revisionNumber = revisionNumber,
        documentCanonicalFingerprint = documentCanonicalFingerprint,
        targetCanonicalFingerprint = targetCanonicalFingerprint,
        assetManifestCanonicalFingerprint = assetManifestCanonicalFingerprint,
        selectedRegionCanonicalFingerprint =
            selectedRegionCanonicalFingerprint,
    )

private fun StudentProblemIdentityReceiptEntity.recalculateCanonicalFingerprint(
    candidate: StudentProblemIdentityCandidateBinding,
): String {
    val kind =
        runCatching {
            StudentProblemIdentityReceiptKind.valueOf(receiptKind)
        }.getOrElse {
            throw IllegalArgumentException("Identity receipt kind is corrupt", it)
        }
    val reviewAuthority =
        reviewAuthorityKind?.let { stored ->
            runCatching {
                StudentProblemAliasReviewAuthorityKind.valueOf(stored)
            }.getOrElse {
                throw IllegalArgumentException(
                    "Identity receipt review authority is corrupt",
                    it,
                )
            }
        }
    val existingIdentity =
        if (
            existingIdentityNamespace != null &&
            existingIdentityVersion != null &&
            existingIdentityStableKey != null
        ) {
            StudentProblemCanonicalIdentityKey(
                namespace = existingIdentityNamespace,
                version = existingIdentityVersion,
                stableKey = existingIdentityStableKey,
            )
        } else {
            null
        }
    return canonicalStudentProblemIdentityReceiptFingerprint(
        fingerprintVersion = fingerprintVersion,
        receiptId = receiptId,
        kind = kind,
        issuerKeyId = issuerKeyId,
        issuerVersion = issuerVersion,
        candidate = candidate,
        locatorNamespace = locatorNamespace,
        locatorVersion = locatorVersion,
        itemLocatorCanonicalFingerprint = itemLocatorCanonicalFingerprint,
        reviewAuthorityKind = reviewAuthority,
        existingIdentity = existingIdentity,
        existingIdentityCanonicalFingerprint =
            existingIdentityCanonicalFingerprint,
        reviewCaseId = reviewCaseId,
        reviewRevision = reviewRevision,
        reviewDecisionCanonicalFingerprint =
            reviewDecisionCanonicalFingerprint,
        renewalGeneration = renewalGeneration,
        issuedAtEpochMillis = issuedAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )
}

private fun StudentProblemIdentityReceiptEntity.requireExactAssetSelectionRegistration(
    candidate: StudentProblemIdentityCandidateBinding,
) {
    require(receiptKind == StudentProblemIdentityReceiptKind.EXACT_ASSET_SELECTION.name)
    require(
        fingerprintVersion in
            STUDENT_PROBLEM_IDENTITY_RECEIPT_FINGERPRINT_VERSION_V1..
                STUDENT_PROBLEM_IDENTITY_RECEIPT_FINGERPRINT_VERSION_CURRENT,
    )
    require(renewalGeneration > 0)
    require(toCandidateBinding() == candidate)
    require(locatorNamespace == null)
    require(locatorVersion == null)
    require(itemLocatorCanonicalFingerprint == null)
    require(reviewAuthorityKind == null)
    require(existingIdentityNamespace == null)
    require(existingIdentityVersion == null)
    require(existingIdentityStableKey == null)
    require(existingIdentityCanonicalFingerprint == null)
    require(reviewCaseId == null)
    require(reviewRevision == null)
    require(reviewDecisionCanonicalFingerprint == null)
    require(receiptCanonicalFingerprint == recalculateCanonicalFingerprint(candidate))
}

private fun StudentProblemIdentityReceiptEntity.requireTrustedRegistration(
    command: RegisterTrustedStudentProblemSourceCommand,
    candidate: StudentProblemIdentityCandidateBinding,
) {
    require(receiptKind == StudentProblemIdentityReceiptKind.TRUSTED_SOURCE.name)
    require(toCandidateBinding() == candidate)
    require(locatorNamespace == command.locatorNamespace)
    require(locatorVersion == command.locatorVersion)
    require(
        itemLocatorCanonicalFingerprint ==
            command.itemLocatorCanonicalFingerprint,
    )
    require(receiptCanonicalFingerprint == recalculateCanonicalFingerprint(candidate))
}

private fun StudentProblemIdentityReceiptEntity.requireReviewedAliasRegistration(
    command: RegisterReviewedStudentProblemAliasCommand,
    candidate: StudentProblemIdentityCandidateBinding,
    existingIdentityFingerprint: String,
) {
    require(receiptKind == StudentProblemIdentityReceiptKind.REVIEWED_ALIAS.name)
    require(toCandidateBinding() == candidate)
    require(reviewAuthorityKind == command.reviewAuthorityKind.name)
    require(existingIdentityNamespace == command.existingIdentity.namespace)
    require(existingIdentityVersion == command.existingIdentity.version)
    require(existingIdentityStableKey == command.existingIdentity.stableKey)
    require(existingIdentityCanonicalFingerprint == existingIdentityFingerprint)
    require(reviewCaseId == command.reviewCaseId)
    require(reviewRevision == command.reviewRevision)
    require(
        reviewDecisionCanonicalFingerprint ==
            command.reviewDecisionCanonicalFingerprint,
    )
    require(receiptCanonicalFingerprint == recalculateCanonicalFingerprint(candidate))
}
