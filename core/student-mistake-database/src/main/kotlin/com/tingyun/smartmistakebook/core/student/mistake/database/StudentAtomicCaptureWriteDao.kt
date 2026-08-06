package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceKind
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceRef
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import java.util.UUID

@Dao
internal abstract class StudentAtomicCaptureWriteDao : StudentMigrationWriteDao() {
    @Transaction
    open suspend fun saveStudentCaptureOccurrence(
        bundle: SaveStudentCaptureOccurrenceBundle,
    ): SaveStudentCaptureOccurrenceDbResult {
        requireAtomicCaptureOccurrenceRequest(bundle)
        val request = bundle.request
        val transactionCollisions =
            readCaptureOccurrenceTransactionCollisions(
                transactionId = request.transactionId,
                requestCanonicalFingerprint = request.requestCanonicalFingerprint,
                captureIntentId = request.captureIntentId,
                occurrenceId = bundle.occurrence.occurrence.occurrenceId,
            )
        if (transactionCollisions.isNotEmpty()) {
            check(
                transactionCollisions.size == 1 &&
                    transactionCollisions.single().requestCanonicalFingerprint ==
                    request.requestCanonicalFingerprint,
            ) {
                "Atomic capture transaction identity was replayed with different content"
            }
            return requireExactAtomicCaptureReplay(
                bundle = bundle,
                transaction = transactionCollisions.single(),
            )
        }

        val resolved = resolveAtomicCaptureIdentity(bundle)
        requireAtomicCaptureOccurrenceBundle(resolved)
        val handoff = resolved.capture.handoff
        check(
            readCaptureSaveHandoffCollisions(
                intentId = handoff.intentId,
                draftId = handoff.draftId,
                sessionId = handoff.sessionId,
            ).isEmpty(),
        ) {
            "Atomic capture cannot adopt a pre-existing standalone handoff"
        }
        val expectedOccurrence = resolved.occurrence.occurrence
        check(
            readAtomicErrorOccurrenceCollisions(
                occurrenceId = expectedOccurrence.occurrenceId,
                learnerId = expectedOccurrence.learnerId,
                idempotencyKey = expectedOccurrence.idempotencyKey,
                batchCanonicalFingerprint =
                    expectedOccurrence.batchCanonicalFingerprint,
                basisRevisionId = expectedOccurrence.basisRevisionId,
            ).isEmpty(),
        ) {
            "Atomic capture cannot adopt a pre-existing standalone error occurrence"
        }

        val saveOutcome = persistResolvedAtomicCaptureTarget(resolved)
        if (resolved.insertIdentity) {
            insertCanonicalProblemIdentity(resolved.identity)
        }
        if (resolved.insertSourceBinding) {
            insertCanonicalProblemSourceBinding(resolved.sourceBinding)
        }
        materializeTrustedSourceExactAssetBinding(resolved)
        insertCaptureSaveHandoff(handoff)
        val storedOccurrence = appendAtomicErrorOccurrence(resolved.occurrence)
        val transaction = resolved.transaction.withSaveOutcome(saveOutcome)
        insertCaptureOccurrenceTransaction(transaction)
        check(
            readCaptureOccurrenceTransactionCollisions(
                transactionId = transaction.transactionId,
                requestCanonicalFingerprint =
                    transaction.requestCanonicalFingerprint,
                captureIntentId = transaction.captureIntentId,
                occurrenceId = transaction.occurrenceId,
            ) == listOf(transaction),
        ) {
            "Atomic capture transaction receipt is not exactly readable"
        }
        bumpChangeVersion(transaction.learnerId)
        return SaveStudentCaptureOccurrenceDbResult(
            identity = resolved.identity,
            transaction = transaction,
            handoff = handoff,
            occurrence = storedOccurrence,
        )
    }

    private suspend fun resolveAtomicCaptureIdentity(
        bundle: SaveStudentCaptureOccurrenceBundle,
    ): ResolvedAtomicCaptureOccurrenceBundle {
        val evidence = bundle.identityEvidence
        val request = bundle.request
        val proposedProblem = bundle.capture.confirmedMistake.problem.problem
        val learnerId = proposedProblem.learnerId
        val subject = proposedProblem.subject
        val hasExactAssetEvidence =
            bundle.capture.confirmedMistake.problem.images.isNotEmpty()
        val existingBinding =
            readCanonicalProblemSourceBinding(
                learnerId = learnerId,
                subject = subject,
                evidenceKind = evidence.evidenceKind.name,
                evidenceCanonicalFingerprint =
                    evidence.evidenceCanonicalFingerprint,
            )
        val canonicalAssetBindings =
            if (
                evidence.resolutionKind !=
                StudentProblemIdentityResolutionKind.FRESH_OPAQUE &&
                hasExactAssetEvidence
            ) {
                readCanonicalProblemAssetBindings(
                    learnerId = learnerId,
                    subject = subject,
                    exactAssetEvidenceKind =
                        StudentProblemIdentityEvidenceKind.EXACT_ASSET_SELECTION.name,
                    trustedSourceEvidenceKind =
                        StudentProblemIdentityEvidenceKind.TRUSTED_SOURCE.name,
                    assetManifestCanonicalFingerprint =
                        evidence.assetManifestCanonicalFingerprint,
                    selectedRegionCanonicalFingerprint =
                        evidence.selectedRegionCanonicalFingerprint,
                )
            } else {
                emptyList()
            }
        val reviewedCorrectionBindings =
            if (
                evidence.resolutionKind !=
                StudentProblemIdentityResolutionKind.FRESH_OPAQUE &&
                hasExactAssetEvidence
            ) {
                readReviewedAliasCorrectionBindings(
                    learnerId = learnerId,
                    subject = subject,
                    reviewedAliasEvidenceKind =
                        StudentProblemIdentityEvidenceKind.REVIEWED_ALIAS.name,
                    assetManifestCanonicalFingerprint =
                        evidence.assetManifestCanonicalFingerprint,
                    selectedRegionCanonicalFingerprint =
                        evidence.selectedRegionCanonicalFingerprint,
                    documentCanonicalFingerprint =
                        evidence.candidateDocumentCanonicalFingerprint,
                )
            } else {
                emptyList()
            }

        val aliasIdentity =
            if (
                evidence.resolutionKind ==
                StudentProblemIdentityResolutionKind.REVIEWED_ALIAS
            ) {
                val identity =
                    readCanonicalProblemIdentity(
                        learnerId = learnerId,
                        subject = subject,
                        identityNamespace =
                            checkNotNull(evidence.aliasIdentityNamespace) {
                                "Reviewed alias proof omitted its target namespace"
                            },
                        identityVersion =
                            checkNotNull(evidence.aliasIdentityVersion) {
                                "Reviewed alias proof omitted its target version"
                            },
                        stableKey =
                            checkNotNull(evidence.aliasIdentityStableKey) {
                                "Reviewed alias proof omitted its target stable key"
                            },
                    )
                checkNotNull(identity) {
                    "Reviewed alias proof targets a missing canonical identity"
                }.also {
                    check(
                        it.identityCanonicalFingerprint ==
                            evidence.aliasIdentityCanonicalFingerprint,
                    ) {
                        "Reviewed alias proof targets another canonical identity"
                    }
                }
            } else {
                null
            }

        if (aliasIdentity != null) {
            check(
                canonicalAssetBindings.all { it.targetsIdentity(aliasIdentity) } &&
                    reviewedCorrectionBindings.all {
                        it.targetsIdentity(aliasIdentity)
                    },
            ) {
                "Reviewed alias evidence conflicts with an existing canonical identity"
            }
        }

        if (existingBinding != null) {
            val boundIdentity = readCanonicalIdentityForBinding(existingBinding)
            check(aliasIdentity == null || aliasIdentity == boundIdentity) {
                "Reviewed alias evidence is already bound to another identity"
            }
            if (
                evidence.resolutionKind !=
                StudentProblemIdentityResolutionKind.REVIEWED_ALIAS
            ) {
                check(
                    canonicalAssetBindings.all {
                        it.targetsIdentity(boundIdentity)
                    } &&
                        reviewedCorrectionBindings.all {
                            it.targetsIdentity(boundIdentity)
                        },
                ) {
                    "Canonical asset evidence conflicts across problem identities"
                }
                if (
                    evidence.evidenceKind ==
                    StudentProblemIdentityEvidenceKind.EXACT_ASSET_SELECTION
                ) {
                    check(
                        existingBinding.assetManifestCanonicalFingerprint ==
                            evidence.assetManifestCanonicalFingerprint &&
                            existingBinding.selectedRegionCanonicalFingerprint ==
                            evidence.selectedRegionCanonicalFingerprint,
                    ) {
                        "Exact asset identity evidence changed bytes or selected-region order"
                    }
                }
                val bindingForDocument =
                    if (
                        existingBinding.documentCanonicalFingerprint ==
                        evidence.candidateDocumentCanonicalFingerprint
                    ) {
                        check(
                            reviewedCorrectionBindings.all {
                                it.boundRevisionId == existingBinding.boundRevisionId
                            },
                        ) {
                            "Reviewed alias evidence conflicts across problem revisions"
                        }
                        existingBinding
                    } else {
                        check(
                            reviewedCorrectionBindings.isNotEmpty() &&
                                reviewedCorrectionBindings.all { correction ->
                                    correction.targetsIdentity(boundIdentity)
                                } &&
                                reviewedCorrectionBindings
                                    .map { it.boundRevisionId }
                                    .distinct()
                                    .size == 1,
                        ) {
                            "Canonical identity evidence changed content; internal alias/revision review is required"
                        }
                        reviewedCorrectionBindings.first()
                    }
                val boundRevision =
                    checkNotNull(readRevision(bindingForDocument.boundRevisionId)) {
                        "Canonical source binding targets a missing revision"
                    }
                return buildResolvedAtomicCapture(
                    bundle = bundle,
                    identity = boundIdentity,
                    revisionId = boundRevision.revisionId,
                    revisionNumber = boundRevision.revisionNumber,
                    practiceUnitId = boundIdentity.practiceUnitId,
                    errorBookEntryId = boundIdentity.errorBookEntryId,
                    targetMode = ResolvedAtomicCaptureTargetMode.REUSE_REVISION,
                    insertIdentity = false,
                    insertSourceBinding = false,
                )
            }
        }

        if (aliasIdentity != null) {
            val matchingRevision =
                readCanonicalProblemRevisionByDocument(
                    problemId = aliasIdentity.problemId,
                    documentCanonicalFingerprint =
                        evidence.candidateDocumentCanonicalFingerprint,
                )
            val latestRevision =
                checkNotNull(readLatestRevision(aliasIdentity.problemId)) {
                    "Reviewed alias identity has no canonical revision"
                }
            val revisionId =
                matchingRevision?.revisionId
                    ?: canonicalStudentProblemRevisionId(
                        problemId = aliasIdentity.problemId,
                        documentCanonicalFingerprint =
                            evidence.candidateDocumentCanonicalFingerprint,
                    )
            val revisionNumber =
                matchingRevision?.revisionNumber ?: (latestRevision.revisionNumber + 1)
            return buildResolvedAtomicCapture(
                bundle = bundle,
                identity = aliasIdentity,
                revisionId = revisionId,
                revisionNumber = revisionNumber,
                practiceUnitId = aliasIdentity.practiceUnitId,
                errorBookEntryId = aliasIdentity.errorBookEntryId,
                targetMode =
                    if (matchingRevision == null) {
                        ResolvedAtomicCaptureTargetMode.CREATE_REVIEWED_ALIAS_REVISION
                    } else {
                        ResolvedAtomicCaptureTargetMode.REUSE_REVISION
                    },
                insertIdentity = false,
                insertSourceBinding = existingBinding == null,
            )
        }

        if (existingBinding == null && canonicalAssetBindings.isNotEmpty()) {
            check(
                canonicalAssetBindings
                    .map {
                        Triple(
                            it.identityNamespace,
                            it.identityVersion,
                            it.identityStableKey,
                        )
                    }.distinct()
                    .size == 1,
            ) {
                "Canonical asset evidence conflicts across problem identities"
            }
            val canonicalAssetIdentity =
                readCanonicalIdentityForBinding(canonicalAssetBindings.first())
            check(
                reviewedCorrectionBindings.all {
                    it.targetsIdentity(canonicalAssetIdentity)
                },
            ) {
                "Reviewed alias evidence conflicts with canonical asset evidence"
            }
            val matchingDocumentBindings =
                canonicalAssetBindings.filter {
                    it.documentCanonicalFingerprint ==
                        evidence.candidateDocumentCanonicalFingerprint
                }
            val bindingForDocument =
                if (matchingDocumentBindings.isNotEmpty()) {
                    check(
                        matchingDocumentBindings
                            .map { it.boundRevisionId }
                            .distinct()
                            .size == 1 &&
                            reviewedCorrectionBindings.all {
                                it.boundRevisionId ==
                                    matchingDocumentBindings.first().boundRevisionId
                            },
                    ) {
                        "Canonical asset evidence conflicts across problem revisions"
                    }
                    matchingDocumentBindings.first()
                } else {
                    check(
                        reviewedCorrectionBindings.isNotEmpty() &&
                            reviewedCorrectionBindings
                                .map { it.boundRevisionId }
                                .distinct()
                                .size == 1,
                    ) {
                        "Canonical identity evidence changed content; internal alias/revision review is required"
                    }
                    reviewedCorrectionBindings.first()
                }
            val canonicalAssetRevision =
                checkNotNull(readRevision(bindingForDocument.boundRevisionId)) {
                    "Canonical asset binding targets a missing revision"
                }
            return buildResolvedAtomicCapture(
                bundle = bundle,
                identity = canonicalAssetIdentity,
                revisionId = canonicalAssetRevision.revisionId,
                revisionNumber = canonicalAssetRevision.revisionNumber,
                practiceUnitId = canonicalAssetIdentity.practiceUnitId,
                errorBookEntryId = canonicalAssetIdentity.errorBookEntryId,
                targetMode = ResolvedAtomicCaptureTargetMode.REUSE_REVISION,
                insertIdentity = false,
                insertSourceBinding = true,
            )
        }

        if (existingBinding == null && reviewedCorrectionBindings.isNotEmpty()) {
            check(
                reviewedCorrectionBindings
                    .map {
                        Triple(
                            it.identityNamespace,
                            it.identityVersion,
                            it.identityStableKey,
                        )
                    }.distinct()
                    .size == 1 &&
                    reviewedCorrectionBindings
                        .map { it.boundRevisionId }
                        .distinct()
                        .size == 1,
            ) {
                "Reviewed alias evidence conflicts across canonical identities"
            }
            val correctionBinding = reviewedCorrectionBindings.first()
            val correctionIdentity =
                readCanonicalIdentityForBinding(correctionBinding)
            val correctionRevision =
                checkNotNull(readRevision(correctionBinding.boundRevisionId)) {
                    "Reviewed correction binding targets a missing revision"
                }
            return buildResolvedAtomicCapture(
                bundle = bundle,
                identity = correctionIdentity,
                revisionId = correctionRevision.revisionId,
                revisionNumber = correctionRevision.revisionNumber,
                practiceUnitId = correctionIdentity.practiceUnitId,
                errorBookEntryId = correctionIdentity.errorBookEntryId,
                targetMode = ResolvedAtomicCaptureTargetMode.REUSE_REVISION,
                insertIdentity = false,
                insertSourceBinding = true,
            )
        }

        check(existingBinding == null) {
            "Canonical source binding resolution lost its existing identity"
        }
        val identityNamespace =
            when (evidence.resolutionKind) {
                StudentProblemIdentityResolutionKind.TRUSTED_SOURCE ->
                    checkNotNull(evidence.locatorNamespace)
                StudentProblemIdentityResolutionKind.EXACT_ASSET_SELECTION ->
                    STUDENT_PROBLEM_EXACT_ASSET_IDENTITY_NAMESPACE
                StudentProblemIdentityResolutionKind.FRESH_OPAQUE ->
                    STUDENT_PROBLEM_OPAQUE_IDENTITY_NAMESPACE
                StudentProblemIdentityResolutionKind.REVIEWED_ALIAS ->
                    error("Reviewed aliases must resolve an existing identity")
            }
        val identityVersion =
            when (evidence.resolutionKind) {
                StudentProblemIdentityResolutionKind.TRUSTED_SOURCE ->
                    checkNotNull(evidence.locatorVersion)
                StudentProblemIdentityResolutionKind.EXACT_ASSET_SELECTION,
                StudentProblemIdentityResolutionKind.FRESH_OPAQUE,
                -> STUDENT_PROBLEM_OWNER_IDENTITY_VERSION
                StudentProblemIdentityResolutionKind.REVIEWED_ALIAS ->
                    error("Reviewed aliases must resolve an existing identity")
            }
        val key =
            StudentProblemCanonicalIdentityKey(
                namespace = identityNamespace,
                version = identityVersion,
                stableKey = "identity-${UUID.randomUUID()}",
            )
        val identityFingerprint =
            key.canonicalFingerprint(
                learnerId = learnerId,
                subject = subject,
            )
        val problemId =
            canonicalStudentProblemId(
                identityCanonicalFingerprint = identityFingerprint,
            )
        val revisionId =
            canonicalStudentProblemRevisionId(
                problemId = problemId,
                documentCanonicalFingerprint =
                    evidence.candidateDocumentCanonicalFingerprint,
            )
        val errorBookEntryId =
            canonicalStudentProblemErrorBookEntryId(problemId)
        val targetFingerprint =
            canonicalStudentProblemTargetFingerprint(
                identityCanonicalFingerprint = identityFingerprint,
                problemId = problemId,
                revisionId = revisionId,
                documentCanonicalFingerprint =
                    evidence.candidateDocumentCanonicalFingerprint,
            )
        val capture =
            retargetAtomicCapture(
                capture = bundle.capture,
                problemId = problemId,
                revisionId = revisionId,
                revisionNumber = 1,
                practiceUnitId = proposedProblem.primaryPracticeUnitId,
                errorBookEntryId = errorBookEntryId,
                targetCanonicalFingerprint = targetFingerprint,
                persistedSaveReceipt = null,
                persistedPracticeUnit = null,
            )
        val identity =
            StudentProblemCanonicalIdentityEntity(
                learnerId = learnerId,
                subject = subject,
                identityNamespace = key.namespace,
                identityVersion = key.version,
                stableKey = key.stableKey,
                identityCanonicalFingerprint = identityFingerprint,
                issuanceKind = evidence.resolutionKind.name,
                problemId = problemId,
                revisionId = revisionId,
                revisionNumber = 1,
                documentCanonicalFingerprint =
                    evidence.candidateDocumentCanonicalFingerprint,
                practiceUnitId = proposedProblem.primaryPracticeUnitId,
                errorBookEntryId = errorBookEntryId,
                targetSaveReceiptId =
                    capture.confirmedMistake.receipt.intentConfirmationId,
                targetCanonicalFingerprint = targetFingerprint,
                createdTransactionId = request.transactionId,
                createdAtEpochMillis = request.committedAtEpochMillis,
            )
        return finishResolvedAtomicCapture(
            bundle = bundle,
            identity = identity,
            capture = capture,
            revisionId = revisionId,
            revisionNumber = 1,
            targetMode = ResolvedAtomicCaptureTargetMode.CREATE_IDENTITY,
            insertIdentity = true,
            insertSourceBinding = true,
        )
    }

    private suspend fun readCanonicalIdentityForBinding(
        binding: StudentProblemCanonicalSourceBindingEntity,
    ): StudentProblemCanonicalIdentityEntity =
        checkNotNull(
            readCanonicalProblemIdentity(
                learnerId = binding.learnerId,
                subject = binding.subject,
                identityNamespace = binding.identityNamespace,
                identityVersion = binding.identityVersion,
                stableKey = binding.identityStableKey,
            ),
        ) {
            "Canonical source binding targets a missing identity"
        }

    private fun StudentProblemCanonicalSourceBindingEntity.targetsIdentity(
        identity: StudentProblemCanonicalIdentityEntity,
    ): Boolean =
        learnerId == identity.learnerId &&
            subject == identity.subject &&
            identityNamespace == identity.identityNamespace &&
            identityVersion == identity.identityVersion &&
            identityStableKey == identity.stableKey

    private suspend fun materializeTrustedSourceExactAssetBinding(
        bundle: ResolvedAtomicCaptureOccurrenceBundle,
    ) {
        val trustedBinding = bundle.sourceBinding
        if (
            trustedBinding.evidenceKind !=
            StudentProblemIdentityEvidenceKind.TRUSTED_SOURCE.name ||
            bundle.capture.confirmedMistake.problem.images.isEmpty()
        ) {
            return
        }
        val exactEvidenceFingerprint =
            canonicalStudentProblemIdentityEvidenceFingerprint(
                evidenceKind =
                    StudentProblemIdentityEvidenceKind.EXACT_ASSET_SELECTION,
                transactionId = bundle.transaction.transactionId,
                assetManifestCanonicalFingerprint =
                    trustedBinding.assetManifestCanonicalFingerprint,
                selectedRegionCanonicalFingerprint =
                    trustedBinding.selectedRegionCanonicalFingerprint,
            )
        val existingExactBinding =
            readCanonicalProblemSourceBinding(
                learnerId = trustedBinding.learnerId,
                subject = trustedBinding.subject,
                evidenceKind =
                    StudentProblemIdentityEvidenceKind.EXACT_ASSET_SELECTION.name,
                evidenceCanonicalFingerprint = exactEvidenceFingerprint,
            )
        if (existingExactBinding != null) {
            check(existingExactBinding.targetsIdentity(bundle.identity)) {
                "Trusted source exact assets are bound to another canonical identity"
            }
            return
        }
        insertCanonicalProblemSourceBinding(
            trustedBinding.copy(
                evidenceKind =
                    StudentProblemIdentityEvidenceKind.EXACT_ASSET_SELECTION.name,
                evidenceCanonicalFingerprint = exactEvidenceFingerprint,
                locatorNamespace = null,
                locatorVersion = null,
                itemLocatorCanonicalFingerprint = null,
                trustedSourceProofFingerprint = null,
                reviewedAliasProofFingerprint = null,
                reviewCaseId = null,
                reviewRevision = null,
                reviewDecisionCanonicalFingerprint = null,
            ),
        )
    }

    private suspend fun buildResolvedAtomicCapture(
        bundle: SaveStudentCaptureOccurrenceBundle,
        identity: StudentProblemCanonicalIdentityEntity,
        revisionId: String,
        revisionNumber: Int,
        practiceUnitId: String,
        errorBookEntryId: String,
        targetMode: ResolvedAtomicCaptureTargetMode,
        insertIdentity: Boolean,
        insertSourceBinding: Boolean,
    ): ResolvedAtomicCaptureOccurrenceBundle {
        val revision =
            if (targetMode == ResolvedAtomicCaptureTargetMode.REUSE_REVISION) {
                checkNotNull(readRevision(revisionId)) {
                    "Canonical identity targets a missing revision"
                }
            } else {
                null
            }
        if (revision != null) {
            check(
                revision.problemId == identity.problemId &&
                    revision.revisionNumber == revisionNumber &&
                    revision.documentCanonicalFingerprint ==
                    bundle.identityEvidence.candidateDocumentCanonicalFingerprint,
            ) {
                "Canonical identity revision changed immutable content"
            }
        }
        val persistedReceipt =
            checkNotNull(readSaveReceipt(identity.targetSaveReceiptId)) {
                "Canonical identity targets a missing stable save receipt"
            }
        val persistedPracticeUnit =
            checkNotNull(readPracticeUnit(practiceUnitId)) {
                "Canonical identity targets a missing practice unit"
            }
        val persistedProblem =
            checkNotNull(readProblem(identity.problemId)) {
                "Canonical identity targets a missing problem"
            }
        val targetFingerprint =
            canonicalStudentProblemTargetFingerprint(
                identityCanonicalFingerprint =
                    identity.identityCanonicalFingerprint,
                problemId = identity.problemId,
                revisionId = revisionId,
                documentCanonicalFingerprint =
                    bundle.identityEvidence.candidateDocumentCanonicalFingerprint,
            )
        val capture =
            retargetAtomicCapture(
                capture = bundle.capture,
                problemId = identity.problemId,
                revisionId = revisionId,
                revisionNumber = revisionNumber,
                practiceUnitId = practiceUnitId,
                errorBookEntryId = errorBookEntryId,
                targetCanonicalFingerprint = targetFingerprint,
                persistedSaveReceipt = persistedReceipt,
                persistedPracticeUnit = persistedPracticeUnit,
                persistedProblem = persistedProblem,
            )
        return finishResolvedAtomicCapture(
            bundle = bundle,
            identity = identity,
            capture = capture,
            revisionId = revisionId,
            revisionNumber = revisionNumber,
            targetMode = targetMode,
            insertIdentity = insertIdentity,
            insertSourceBinding = insertSourceBinding,
        )
    }

    private fun retargetAtomicCapture(
        capture: SaveStudentOwnedCaptureBundle,
        problemId: String,
        revisionId: String,
        revisionNumber: Int,
        practiceUnitId: String,
        errorBookEntryId: String,
        targetCanonicalFingerprint: String,
        persistedSaveReceipt: StudentMistakeSaveReceiptEntity?,
        persistedPracticeUnit: StudentPracticeUnitEntity?,
        persistedProblem: StudentProblemDocumentEntity? = null,
    ): SaveStudentOwnedCaptureBundle {
        val target = capture.confirmedMistake
        val original = target.problem
        val originalProblemId = original.problem.problemId
        val problem =
            persistedProblem?.copy(
                currentRevisionId = revisionId,
                updatedAtEpochMillis =
                    maxOf(
                        persistedProblem.updatedAtEpochMillis,
                        capture.handoff.occurredAtEpochMillis,
                    ),
            )
                ?: original.problem.copy(
                    problemId = problemId,
                    primaryPracticeUnitId = practiceUnitId,
                    currentRevisionId = revisionId,
                    errorBookEntryId = errorBookEntryId,
                )
        val revision =
            original.revision.copy(
                revisionId = revisionId,
                problemId = problemId,
                revisionNumber = revisionNumber,
            )
        val practiceUnit =
            persistedPracticeUnit?.copy(
                basisRevisionId = revisionId,
                updatedAtEpochMillis =
                    maxOf(
                        persistedPracticeUnit.updatedAtEpochMillis,
                        capture.handoff.occurredAtEpochMillis,
                    ),
            )
                ?: original.practiceUnit.copy(
                    practiceUnitId = practiceUnitId,
                    problemId = problemId,
                    basisRevisionId = revisionId,
                    itemFamilyId =
                        if (original.practiceUnit.itemFamilyId == originalProblemId) {
                            problemId
                        } else {
                            original.practiceUnit.itemFamilyId
                        },
                )
        val images =
            original.images.map { image ->
                image.copy(
                    imageReferenceId =
                        canonicalAtomicCaptureImageReferenceId(
                            revisionId = revisionId,
                            ordinal = image.ordinal,
                            contentCanonicalFingerprint =
                                image.contentCanonicalFingerprint,
                            selectedRegionsWire = image.selectedRegionsWire,
                        ),
                    revisionId = revisionId,
                )
            }
        val searchDocument =
            original.searchDocument.copy(
                rowId = 0,
                revisionId = revisionId,
                sourceCanonicalFingerprint =
                    CanonicalSha256(ATOMIC_CAPTURE_SEARCH_DOCUMENT_DOMAIN)
                        .field("revisionId", revisionId)
                        .field(
                            "documentCanonicalFingerprint",
                            revision.documentCanonicalFingerprint,
                        )
                        .finish(),
            )
        val problemBundle =
            original.copy(
                problem = problem,
                revision = revision,
                practiceUnit = practiceUnit,
                images = images,
                searchDocument = searchDocument,
            )
        val receipt =
            persistedSaveReceipt
                ?: target.receipt.copy(
                    intentConfirmationId = "target-$targetCanonicalFingerprint",
                    intentCanonicalFingerprint = targetCanonicalFingerprint,
                    learnerId = problem.learnerId,
                    problemId = problemId,
                    basisRevisionId = revisionId,
                    errorBookEntryId = errorBookEntryId,
                )
        val resolvedTarget =
            target.copy(
                problem = problemBundle,
                collection =
                    target.collection.copy(
                        practiceUnitId = practiceUnitId,
                        problemId = problemId,
                    ),
                initialReviewCandidate =
                    target.initialReviewCandidate.copy(
                        candidateId = practiceUnitId,
                        practiceUnitId = practiceUnitId,
                        basisRevisionId = revisionId,
                        itemFamilyId = practiceUnit.itemFamilyId,
                    ),
                receipt = receipt,
            )
        val handoff =
            capture.handoff.copy(
                targetProblemId = problemId,
                targetPracticeUnitId = practiceUnitId,
                targetRevisionId = revisionId,
                targetRevisionNumber = revisionNumber,
                targetDocumentCanonicalFingerprint =
                    revision.documentCanonicalFingerprint,
                errorBookEntryId = errorBookEntryId,
                targetCanonicalFingerprint = targetCanonicalFingerprint,
            )
        return SaveStudentOwnedCaptureBundle(
            confirmedMistake = resolvedTarget,
            handoff = handoff,
        )
    }

    private fun finishResolvedAtomicCapture(
        bundle: SaveStudentCaptureOccurrenceBundle,
        identity: StudentProblemCanonicalIdentityEntity,
        capture: SaveStudentOwnedCaptureBundle,
        revisionId: String,
        revisionNumber: Int,
        targetMode: ResolvedAtomicCaptureTargetMode,
        insertIdentity: Boolean,
        insertSourceBinding: Boolean,
    ): ResolvedAtomicCaptureOccurrenceBundle {
        val problem = capture.confirmedMistake.problem.problem
        val revision = capture.confirmedMistake.problem.revision
        check(
            revision.revisionId == revisionId &&
                revision.revisionNumber == revisionNumber,
        ) {
            "Resolved capture revision drifted while retargeting"
        }
        val occurrence =
            retargetAtomicOccurrence(
                bundle = bundle.occurrence,
                problem = problem,
                revision = revision,
            )
        val evidence = bundle.identityEvidence
        val request = bundle.request
        val sourceBinding =
            StudentProblemCanonicalSourceBindingEntity(
                learnerId = problem.learnerId,
                subject = problem.subject,
                evidenceKind = evidence.evidenceKind.name,
                evidenceCanonicalFingerprint =
                    evidence.evidenceCanonicalFingerprint,
                identityNamespace = identity.identityNamespace,
                identityVersion = identity.identityVersion,
                identityStableKey = identity.stableKey,
                assetManifestCanonicalFingerprint =
                    evidence.assetManifestCanonicalFingerprint,
                selectedRegionCanonicalFingerprint =
                    evidence.selectedRegionCanonicalFingerprint,
                documentCanonicalFingerprint =
                    evidence.candidateDocumentCanonicalFingerprint,
                boundRevisionId = revisionId,
                locatorNamespace = evidence.locatorNamespace,
                locatorVersion = evidence.locatorVersion,
                itemLocatorCanonicalFingerprint =
                    evidence.itemLocatorCanonicalFingerprint,
                trustedSourceProofFingerprint =
                    evidence.trustedSourceProofFingerprint,
                reviewedAliasProofFingerprint =
                    evidence.reviewedAliasProofFingerprint,
                reviewCaseId = evidence.reviewCaseId,
                reviewRevision = evidence.reviewRevision,
                reviewDecisionCanonicalFingerprint =
                    evidence.reviewDecisionCanonicalFingerprint,
                createdTransactionId = request.transactionId,
                createdAtEpochMillis = request.committedAtEpochMillis,
            )
        val handoff = capture.handoff
        val occurrenceEntity = occurrence.occurrence
        val transactionFingerprint =
            canonicalStudentCaptureOccurrenceTransactionFingerprint(
                sourceKind = handoff.sourceKind,
                sourceIntentId = handoff.intentId,
                sourceCanonicalFingerprint =
                    handoff.sourceCanonicalFingerprint,
                canonicalIdentityFingerprint =
                    identity.identityCanonicalFingerprint,
                targetLearnerId = problem.learnerId,
                targetSubject = problem.subject,
                targetProblemId = problem.problemId,
                targetPracticeUnitId = problem.primaryPracticeUnitId,
                targetRevisionId = revision.revisionId,
                targetRevisionNumber = revision.revisionNumber,
                targetDocumentCanonicalFingerprint =
                    revision.documentCanonicalFingerprint,
                targetCanonicalFingerprint =
                    handoff.targetCanonicalFingerprint,
                occurrenceId = occurrenceEntity.occurrenceId,
                occurrenceCanonicalFingerprint =
                    occurrenceEntity.occurrenceCanonicalFingerprint,
                batchCanonicalFingerprint =
                    occurrenceEntity.batchCanonicalFingerprint,
                importSourceCanonicalFingerprint =
                    occurrenceEntity.importSourceCanonicalFingerprint,
                assetManifestCanonicalFingerprint =
                    evidence.assetManifestCanonicalFingerprint,
                selectedRegionCanonicalFingerprint =
                    evidence.selectedRegionCanonicalFingerprint,
            )
        val transaction =
            StudentCaptureOccurrenceTransactionDraft(
                transactionId = request.transactionId,
                transactionCanonicalFingerprint = transactionFingerprint,
                requestCanonicalFingerprint =
                    request.requestCanonicalFingerprint,
                schemaVersion = request.schemaVersion,
                learnerId = request.learnerId,
                captureIntentId = request.captureIntentId,
                sourceKind = request.sourceKind,
                sourceCanonicalFingerprint =
                    request.sourceCanonicalFingerprint,
                identityNamespace = identity.identityNamespace,
                identityVersion = identity.identityVersion,
                identityStableKey = identity.stableKey,
                identityCanonicalFingerprint =
                    identity.identityCanonicalFingerprint,
                identityResolutionKind = evidence.resolutionKind.name,
                identityEvidenceKind = evidence.evidenceKind.name,
                identityEvidenceCanonicalFingerprint =
                    evidence.evidenceCanonicalFingerprint,
                trustedSourceProofFingerprint =
                    evidence.trustedSourceProofFingerprint,
                reviewedAliasProofFingerprint =
                    evidence.reviewedAliasProofFingerprint,
                targetSaveReceiptId =
                    capture.confirmedMistake.receipt.intentConfirmationId,
                targetProblemId = problem.problemId,
                targetRevisionId = revision.revisionId,
                targetRevisionNumber = revision.revisionNumber,
                targetDocumentCanonicalFingerprint =
                    revision.documentCanonicalFingerprint,
                targetCanonicalFingerprint =
                    handoff.targetCanonicalFingerprint,
                occurrenceId = occurrenceEntity.occurrenceId,
                occurrenceCanonicalFingerprint =
                    occurrenceEntity.occurrenceCanonicalFingerprint,
                batchCanonicalFingerprint =
                    occurrenceEntity.batchCanonicalFingerprint,
                importSourceCanonicalFingerprint =
                    occurrenceEntity.importSourceCanonicalFingerprint,
                assetManifestCanonicalFingerprint =
                    evidence.assetManifestCanonicalFingerprint,
                selectedRegionCanonicalFingerprint =
                    evidence.selectedRegionCanonicalFingerprint,
                committedAtEpochMillis = request.committedAtEpochMillis,
            )
        return ResolvedAtomicCaptureOccurrenceBundle(
            identity = identity,
            sourceBinding = sourceBinding,
            capture = capture,
            occurrence = occurrence,
            transaction = transaction,
            targetMode = targetMode,
            insertIdentity = insertIdentity,
            insertSourceBinding = insertSourceBinding,
        )
    }

    private fun retargetAtomicOccurrence(
        bundle: StudentProblemErrorOccurrenceBundle,
        problem: StudentProblemDocumentEntity,
        revision: StudentProblemRevisionEntity,
    ): StudentProblemErrorOccurrenceBundle {
        val problemRef =
            StudentProblemRef(
                learnerId = problem.learnerId,
                subject = SubjectKind.valueOf(problem.subject),
                problemId = problem.problemId,
                practiceUnitId = problem.primaryPracticeUnitId,
            )
        val revisionRef =
            StudentProblemRevisionRef(
                problem = problemRef,
                revisionId = revision.revisionId,
                revisionNumber = revision.revisionNumber,
                documentCanonicalFingerprint =
                    revision.documentCanonicalFingerprint,
            )
        val original = bundle.occurrence
        val evidenceRefs =
            bundle.evidence.map { evidence ->
                ProblemErrorEvidenceRef(
                    blockId = evidence.blockId,
                    sourceAssetId = evidence.sourceAssetId,
                    evidenceKind =
                        ProblemErrorEvidenceKind.valueOf(evidence.evidenceKind),
                )
            }
        val attributionStatus =
            original.attributionStatus?.let {
                ProblemErrorAttributionResolutionStatus.valueOf(it)
            }
        val canonicalFingerprint =
            canonicalErrorOccurrenceFingerprint(
                occurrenceId = original.occurrenceId,
                idempotencyKey = original.idempotencyKey,
                problemRevision = revisionRef,
                batchCanonicalFingerprint =
                    original.batchCanonicalFingerprint,
                importSourceCanonicalFingerprint =
                    original.importSourceCanonicalFingerprint,
                occurredAtEpochMillis = original.occurredAtEpochMillis,
                importedAtEpochMillis = original.importedAtEpochMillis,
                attributionStatus = attributionStatus,
                evidenceRefs = evidenceRefs,
            )
        return bundle.copy(
            occurrence =
                original.copy(
                    learnerId = problem.learnerId,
                    subject = problem.subject,
                    problemId = problem.problemId,
                    practiceUnitId = problem.primaryPracticeUnitId,
                    basisRevisionId = revision.revisionId,
                    basisRevisionNumber = revision.revisionNumber,
                    basisDocumentCanonicalFingerprint =
                        revision.documentCanonicalFingerprint,
                    occurrenceCanonicalFingerprint = canonicalFingerprint,
                ),
        )
    }

    private fun canonicalAtomicCaptureImageReferenceId(
        revisionId: String,
        ordinal: Int,
        contentCanonicalFingerprint: String,
        selectedRegionsWire: String,
    ): String =
        "image-" +
            CanonicalSha256(ATOMIC_CAPTURE_IMAGE_REFERENCE_ID_DOMAIN)
                .field("revisionId", revisionId)
                .field("ordinal", ordinal)
                .field(
                    "contentCanonicalFingerprint",
                    contentCanonicalFingerprint,
                )
                .field("selectedRegionsWire", selectedRegionsWire)
                .finish()

    private fun requireAtomicCaptureOccurrenceRequest(
        bundle: SaveStudentCaptureOccurrenceBundle,
    ) {
        val target = bundle.capture.confirmedMistake
        val problem = target.problem.problem
        val revision = target.problem.revision
        val handoff = bundle.capture.handoff
        val occurrence = bundle.occurrence.occurrence
        val request = bundle.request
        val evidence = bundle.identityEvidence

        check(
            request.schemaVersion ==
                STUDENT_CAPTURE_OCCURRENCE_TRANSACTION_SCHEMA_VERSION &&
                handoff.schemaVersion == STUDENT_CAPTURE_SAVE_HANDOFF_SCHEMA_VERSION &&
                occurrence.schemaVersion ==
                STUDENT_PROBLEM_ERROR_OCCURRENCE_SCHEMA_VERSION,
        ) {
            "Atomic capture request uses an unsupported immutable schema"
        }
        check(
            target.problem.solutionAnalysis == null &&
                target.problem.solutionSteps.isEmpty() &&
                target.problem.errorAttributions.isEmpty() &&
                target.problem.errorEvidence.isEmpty(),
        ) {
            "Atomic capture request cannot contain organization or review authority"
        }
        check(
            request.transactionId ==
                canonicalStudentCaptureOccurrenceTransactionId(
                    learnerId = problem.learnerId,
                    intentId = handoff.intentId,
                ) &&
                request.learnerId == problem.learnerId &&
                request.captureIntentId == handoff.intentId &&
                request.sourceKind == handoff.sourceKind &&
                request.sourceCanonicalFingerprint ==
                handoff.sourceCanonicalFingerprint &&
                request.committedAtEpochMillis == handoff.occurredAtEpochMillis,
        ) {
            "Atomic capture request crosses its learner, source, or transaction identity"
        }
        check(
            handoff.learnerId == problem.learnerId &&
                handoff.targetSubject == problem.subject &&
                handoff.targetProblemId == problem.problemId &&
                handoff.targetPracticeUnitId == problem.primaryPracticeUnitId &&
                handoff.targetRevisionId == revision.revisionId &&
                handoff.targetRevisionNumber == revision.revisionNumber &&
                handoff.targetDocumentCanonicalFingerprint ==
                revision.documentCanonicalFingerprint,
        ) {
            "Atomic capture request handoff does not match its provisional target"
        }
        check(
            occurrence.learnerId == problem.learnerId &&
                occurrence.subject == problem.subject &&
                occurrence.problemId == problem.problemId &&
                occurrence.practiceUnitId == problem.primaryPracticeUnitId &&
                occurrence.basisRevisionId == revision.revisionId &&
                occurrence.basisRevisionNumber == revision.revisionNumber &&
                occurrence.basisDocumentCanonicalFingerprint ==
                revision.documentCanonicalFingerprint &&
                occurrence.idempotencyKey == handoff.intentId &&
                occurrence.occurredAtEpochMillis == handoff.occurredAtEpochMillis,
        ) {
            "Atomic capture request occurrence does not match its provisional target"
        }
        check(
            request.assetManifestCanonicalFingerprint ==
                evidence.assetManifestCanonicalFingerprint &&
                request.selectedRegionCanonicalFingerprint ==
                evidence.selectedRegionCanonicalFingerprint &&
                evidence.candidateDocumentCanonicalFingerprint ==
                revision.documentCanonicalFingerprint,
        ) {
            "Atomic capture identity evidence does not bind the exact request"
        }
        requireAtomicErrorOccurrenceBundle(bundle.occurrence)
    }

    private fun requireAtomicCaptureOccurrenceBundle(
        bundle: ResolvedAtomicCaptureOccurrenceBundle,
    ) {
        val capture = bundle.capture
        val target = capture.confirmedMistake
        val problem = target.problem.problem
        val revision = target.problem.revision
        val receipt = target.receipt
        val handoff = capture.handoff
        val occurrence = bundle.occurrence.occurrence
        val transaction = bundle.transaction

        check(
            handoff.schemaVersion == STUDENT_CAPTURE_SAVE_HANDOFF_SCHEMA_VERSION &&
                occurrence.schemaVersion ==
                STUDENT_PROBLEM_ERROR_OCCURRENCE_SCHEMA_VERSION &&
                transaction.schemaVersion ==
                STUDENT_CAPTURE_OCCURRENCE_TRANSACTION_SCHEMA_VERSION,
        ) {
            "Atomic capture bundle uses an unsupported immutable schema"
        }
        check(
            handoff.learnerId == problem.learnerId &&
                handoff.targetSubject == problem.subject &&
                handoff.targetProblemId == problem.problemId &&
                handoff.targetPracticeUnitId == problem.primaryPracticeUnitId &&
                handoff.targetRevisionId == revision.revisionId &&
                handoff.targetRevisionNumber == revision.revisionNumber &&
                handoff.targetDocumentCanonicalFingerprint ==
                revision.documentCanonicalFingerprint &&
                handoff.errorBookEntryId == receipt.errorBookEntryId,
        ) {
            "Atomic capture handoff crosses its exact target-save boundary"
        }
        check(
            receipt.learnerId == problem.learnerId &&
                receipt.problemId == problem.problemId &&
                receipt.errorBookEntryId == problem.errorBookEntryId,
        ) {
            "Atomic capture save receipt crosses its exact target identity"
        }
        check(
            occurrence.learnerId == problem.learnerId &&
                occurrence.subject == problem.subject &&
                occurrence.problemId == problem.problemId &&
                occurrence.practiceUnitId == problem.primaryPracticeUnitId &&
                occurrence.basisRevisionId == revision.revisionId &&
                occurrence.basisRevisionNumber == revision.revisionNumber &&
                occurrence.basisDocumentCanonicalFingerprint ==
                revision.documentCanonicalFingerprint &&
                occurrence.idempotencyKey == handoff.intentId &&
                occurrence.occurredAtEpochMillis == handoff.occurredAtEpochMillis,
        ) {
            "Atomic capture occurrence crosses its learner, revision, or commit boundary"
        }
        requireAtomicErrorOccurrenceBundle(bundle.occurrence)
        val expectedTransactionId =
            canonicalStudentCaptureOccurrenceTransactionId(
                learnerId = problem.learnerId,
                intentId = handoff.intentId,
            )
        val expectedTransactionFingerprint =
            canonicalStudentCaptureOccurrenceTransactionFingerprint(
                sourceKind = handoff.sourceKind,
                sourceIntentId = handoff.intentId,
                sourceCanonicalFingerprint =
                    handoff.sourceCanonicalFingerprint,
                canonicalIdentityFingerprint =
                    bundle.identity.identityCanonicalFingerprint,
                targetLearnerId = problem.learnerId,
                targetSubject = problem.subject,
                targetProblemId = problem.problemId,
                targetPracticeUnitId = problem.primaryPracticeUnitId,
                targetRevisionId = revision.revisionId,
                targetRevisionNumber = revision.revisionNumber,
                targetDocumentCanonicalFingerprint =
                    revision.documentCanonicalFingerprint,
                targetCanonicalFingerprint =
                    handoff.targetCanonicalFingerprint,
                occurrenceId = occurrence.occurrenceId,
                occurrenceCanonicalFingerprint =
                    occurrence.occurrenceCanonicalFingerprint,
                batchCanonicalFingerprint =
                    occurrence.batchCanonicalFingerprint,
                importSourceCanonicalFingerprint =
                    occurrence.importSourceCanonicalFingerprint,
                assetManifestCanonicalFingerprint =
                    transaction.assetManifestCanonicalFingerprint,
                selectedRegionCanonicalFingerprint =
                    transaction.selectedRegionCanonicalFingerprint,
            )
        check(
            transaction.transactionId == expectedTransactionId &&
                transaction.transactionCanonicalFingerprint ==
                expectedTransactionFingerprint &&
                transaction.learnerId == problem.learnerId &&
                transaction.captureIntentId == handoff.intentId &&
                transaction.sourceKind == handoff.sourceKind &&
                transaction.sourceCanonicalFingerprint ==
                handoff.sourceCanonicalFingerprint &&
                transaction.identityNamespace ==
                bundle.identity.identityNamespace &&
                transaction.identityVersion == bundle.identity.identityVersion &&
                transaction.identityStableKey == bundle.identity.stableKey &&
                transaction.identityCanonicalFingerprint ==
                bundle.identity.identityCanonicalFingerprint &&
                transaction.identityResolutionKind in
                StudentProblemIdentityResolutionKind.entries.map { it.name } &&
                (
                    transaction.identityResolutionKind ==
                        StudentProblemIdentityResolutionKind.REVIEWED_ALIAS.name
                ) == (bundle.sourceBinding.reviewedAliasProofFingerprint != null) &&
                transaction.identityEvidenceKind ==
                bundle.sourceBinding.evidenceKind &&
                transaction.identityEvidenceCanonicalFingerprint ==
                bundle.sourceBinding.evidenceCanonicalFingerprint &&
                transaction.trustedSourceProofFingerprint ==
                bundle.sourceBinding.trustedSourceProofFingerprint &&
                transaction.reviewedAliasProofFingerprint ==
                bundle.sourceBinding.reviewedAliasProofFingerprint &&
                transaction.targetSaveReceiptId ==
                receipt.intentConfirmationId &&
                transaction.targetProblemId == problem.problemId &&
                transaction.targetRevisionId == revision.revisionId &&
                transaction.targetRevisionNumber == revision.revisionNumber &&
                transaction.targetDocumentCanonicalFingerprint ==
                revision.documentCanonicalFingerprint &&
                transaction.targetCanonicalFingerprint ==
                handoff.targetCanonicalFingerprint &&
                transaction.occurrenceId == occurrence.occurrenceId &&
                transaction.occurrenceCanonicalFingerprint ==
                occurrence.occurrenceCanonicalFingerprint &&
                transaction.batchCanonicalFingerprint ==
                occurrence.batchCanonicalFingerprint &&
                transaction.importSourceCanonicalFingerprint ==
                occurrence.importSourceCanonicalFingerprint &&
                transaction.assetManifestCanonicalFingerprint ==
                bundle.sourceBinding.assetManifestCanonicalFingerprint &&
                transaction.selectedRegionCanonicalFingerprint ==
                bundle.sourceBinding.selectedRegionCanonicalFingerprint &&
                transaction.committedAtEpochMillis ==
                handoff.occurredAtEpochMillis,
        ) {
            "Atomic capture transaction receipt draft does not cover its exact payload"
        }
    }

    private fun requireAtomicErrorOccurrenceBundle(
        bundle: StudentProblemErrorOccurrenceBundle,
    ) {
        val occurrence = bundle.occurrence
        check(
            occurrence.evidenceCount == bundle.evidence.size &&
                bundle.evidence.indices.all { index ->
                    val evidence = bundle.evidence[index]
                    evidence.occurrenceId == occurrence.occurrenceId &&
                        evidence.ordinal == index
                } &&
                bundle.evidence
                    .map {
                        Triple(
                            it.blockId,
                            it.sourceAssetId,
                            it.evidenceKind,
                        )
                    }.distinct()
                    .size == bundle.evidence.size,
        ) {
            "Atomic capture occurrence evidence crosses an immutable occurrence boundary"
        }
    }

    private suspend fun persistResolvedAtomicCaptureTarget(
        bundle: ResolvedAtomicCaptureOccurrenceBundle,
    ): TargetConfirmedStudentMistakeSaveOutcome =
        when (bundle.targetMode) {
            ResolvedAtomicCaptureTargetMode.CREATE_IDENTITY ->
                saveAtomicCaptureTargetWithoutReview(
                    bundle.capture.confirmedMistake,
                )
            ResolvedAtomicCaptureTargetMode.REUSE_REVISION -> {
                requireCanonicalIdentityTarget(
                    identity = bundle.identity,
                    capture = bundle.capture,
                )
                TargetConfirmedStudentMistakeSaveOutcome.DUPLICATE
            }
            ResolvedAtomicCaptureTargetMode.CREATE_REVIEWED_ALIAS_REVISION -> {
                requireCanonicalIdentityTarget(
                    identity = bundle.identity,
                    capture = bundle.capture,
                    requireTargetRevision = false,
                )
                commitProblem(
                    bundle = bundle.capture.confirmedMistake.problem,
                    recordChange = false,
                    publishRevisionToMastery = false,
                )
                check(
                    readRevision(
                        bundle.capture.confirmedMistake.problem.revision.revisionId,
                    ) == bundle.capture.confirmedMistake.problem.revision,
                ) {
                    "Reviewed alias revision was not committed exactly"
                }
                TargetConfirmedStudentMistakeSaveOutcome.DUPLICATE
            }
        }

    private suspend fun requireCanonicalIdentityTarget(
        identity: StudentProblemCanonicalIdentityEntity,
        capture: SaveStudentOwnedCaptureBundle,
        requireTargetRevision: Boolean = true,
    ) {
        val problem =
            checkNotNull(readProblem(identity.problemId)) {
                "Canonical identity target problem is missing"
            }
        check(
            problem.learnerId == identity.learnerId &&
                problem.subject == identity.subject &&
                problem.primaryPracticeUnitId == identity.practiceUnitId &&
                problem.errorBookEntryId == identity.errorBookEntryId,
        ) {
            "Canonical identity target problem changed stable ownership"
        }
        if (requireTargetRevision) {
            val revision = capture.confirmedMistake.problem.revision
            val persistedRevision =
                checkNotNull(readRevision(revision.revisionId)) {
                    "Canonical identity target revision is missing"
                }
            check(
                persistedRevision.problemId == identity.problemId &&
                    persistedRevision.revisionNumber == revision.revisionNumber &&
                    persistedRevision.documentCanonicalFingerprint ==
                    revision.documentCanonicalFingerprint,
            ) {
                "Canonical identity target revision changed immutable content"
            }
        }
        check(
            readSaveReceipt(identity.targetSaveReceiptId) ==
                capture.confirmedMistake.receipt,
        ) {
            "Canonical identity target save receipt changed immutable content"
        }
    }

    private suspend fun saveAtomicCaptureTargetWithoutReview(
        bundle: SaveConfirmedStudentMistakeBundle,
    ): TargetConfirmedStudentMistakeSaveOutcome {
        val targetRevisionId = bundle.problem.revision.revisionId
        val targetEntryId = bundle.receipt.errorBookEntryId
        val collisions =
            readSaveReceiptTargetCollisions(
                basisRevisionId = targetRevisionId,
                errorBookEntryId = targetEntryId,
                targetReceiptId = bundle.receipt.intentConfirmationId,
                targetCanonicalFingerprint =
                    bundle.receipt.intentCanonicalFingerprint,
            )
        if (collisions.isNotEmpty()) {
            check(
                collisions.all { receipt ->
                    receipt.basisRevisionId == targetRevisionId &&
                        receipt.errorBookEntryId == targetEntryId
                },
            ) {
                "Atomic capture target identity collides with another confirmed mistake"
            }
            requireExactAtomicCaptureTarget(bundle)
            check(readSaveReceipt(bundle.receipt.intentConfirmationId) == bundle.receipt) {
                "Atomic capture target collision does not match its save receipt"
            }
            return TargetConfirmedStudentMistakeSaveOutcome.DUPLICATE
        }
        if (
            readRevision(targetRevisionId) != null ||
            readProblemByErrorBookEntryId(targetEntryId) != null
        ) {
            requireExactAtomicCaptureTarget(bundle)
            checkRevisionAuthority(bundle.problem)
            setCollectionState(bundle.collection, recordChange = false)
            insertSaveReceipt(bundle.receipt)
            return TargetConfirmedStudentMistakeSaveOutcome.DUPLICATE
        }

        commitProblem(
            bundle = bundle.problem,
            recordChange = false,
            publishRevisionToMastery = false,
        )
        setCollectionState(bundle.collection, recordChange = false)
        insertSaveReceipt(bundle.receipt)
        return TargetConfirmedStudentMistakeSaveOutcome.CREATED
    }

    private suspend fun requireExactAtomicCaptureTarget(
        bundle: SaveConfirmedStudentMistakeBundle,
    ) {
        val expectedProblem = bundle.problem.problem
        val persistedProblem =
            checkNotNull(readProblem(expectedProblem.problemId)) {
                "Atomic capture target problem is missing"
            }
        check(
            persistedProblem.problemId == expectedProblem.problemId &&
                persistedProblem.learnerId == expectedProblem.learnerId &&
                persistedProblem.subject == expectedProblem.subject &&
                persistedProblem.primaryPracticeUnitId ==
                expectedProblem.primaryPracticeUnitId &&
                persistedProblem.errorBookEntryId == expectedProblem.errorBookEntryId,
        ) {
            "Atomic capture target changed its stable problem identity"
        }
        check(readRevision(bundle.problem.revision.revisionId) == bundle.problem.revision) {
            "Atomic capture target changed its exact revision fields"
        }
        check(
            readImages(bundle.problem.revision.revisionId, MAX_STORED_IMAGES + 1) ==
                bundle.problem.images,
        ) {
            "Atomic capture target changed its original-image fields"
        }
    }

    private suspend fun appendAtomicErrorOccurrence(
        bundle: StudentProblemErrorOccurrenceBundle,
    ): StudentProblemErrorOccurrenceBundle {
        val occurrence = bundle.occurrence
        val problem =
            checkNotNull(readProblem(occurrence.problemId)) {
                "Atomic capture occurrence target problem does not exist"
            }
        check(
            problem.learnerId == occurrence.learnerId &&
                problem.subject == occurrence.subject &&
                problem.primaryPracticeUnitId == occurrence.practiceUnitId &&
                problem.lifecycleState != StudentProblemLifecycleState.TOMBSTONED.name,
        ) {
            "Atomic capture occurrence target is not the active exact learner problem"
        }
        val revision =
            checkNotNull(readRevision(occurrence.basisRevisionId)) {
                "Atomic capture occurrence target revision does not exist"
            }
        check(
            revision.problemId == occurrence.problemId &&
                revision.revisionNumber == occurrence.basisRevisionNumber &&
                revision.documentCanonicalFingerprint ==
                occurrence.basisDocumentCanonicalFingerprint,
        ) {
            "Atomic capture occurrence target revision changed before append"
        }
        requireAtomicCapturedEvidence(bundle, revision)
        check(
            readAtomicErrorOccurrenceCollisions(
                occurrenceId = occurrence.occurrenceId,
                learnerId = occurrence.learnerId,
                idempotencyKey = occurrence.idempotencyKey,
                batchCanonicalFingerprint =
                    occurrence.batchCanonicalFingerprint,
                basisRevisionId = occurrence.basisRevisionId,
            ).isEmpty(),
        ) {
            "Atomic capture occurrence immutable identity already exists"
        }
        insertAtomicErrorOccurrence(occurrence)
        if (bundle.evidence.isNotEmpty()) {
            insertAtomicErrorOccurrenceEvidence(bundle.evidence)
        }
        val storedEvidence =
            readAtomicErrorOccurrenceEvidence(occurrence.occurrenceId)
        check(storedEvidence == bundle.evidence) {
            "Atomic capture occurrence write produced incomplete evidence"
        }
        check(
            readAtomicErrorOccurrenceCollisions(
                occurrenceId = occurrence.occurrenceId,
                learnerId = occurrence.learnerId,
                idempotencyKey = occurrence.idempotencyKey,
                batchCanonicalFingerprint =
                    occurrence.batchCanonicalFingerprint,
                basisRevisionId = occurrence.basisRevisionId,
            ) == listOf(occurrence),
        ) {
            "Atomic capture occurrence is not exactly readable"
        }
        return StudentProblemErrorOccurrenceBundle(
            occurrence = occurrence,
            evidence = storedEvidence,
        )
    }

    private fun requireAtomicCapturedEvidence(
        bundle: StudentProblemErrorOccurrenceBundle,
        revision: StudentProblemRevisionEntity,
    ) {
        if (bundle.evidence.isEmpty()) return
        val capturedWire =
            checkNotNull(revision.capturedQuestionDocumentWire) {
                "Atomic capture occurrence evidence requires a captured-question snapshot"
            }
        val captured =
            runCatching {
                CapturedQuestionDocumentCodec.decode(capturedWire)
            }.getOrElse {
                throw IllegalStateException(
                    "Atomic capture persisted an unreadable captured-question snapshot",
                    it,
                )
            }
        check(
            CapturedQuestionDocumentFingerprint.of(captured) ==
                bundle.occurrence.basisDocumentCanonicalFingerprint,
        ) {
            "Atomic capture occurrence evidence does not match its exact revision"
        }
        val capturedEvidence =
            captured.blockEvidence.mapTo(hashSetOf()) {
                it.blockId to it.sourceAssetId
            }
        check(
            bundle.evidence.none {
                it.blockId to it.sourceAssetId !in capturedEvidence
            },
        ) {
            "Atomic capture occurrence references evidence outside its exact revision"
        }
    }

    private suspend fun requireExactAtomicCaptureReplay(
        bundle: SaveStudentCaptureOccurrenceBundle,
        transaction: StudentCaptureOccurrenceTransactionEntity,
    ): SaveStudentCaptureOccurrenceDbResult {
        check(
            runCatching {
                TargetConfirmedStudentMistakeSaveOutcome.valueOf(
                    transaction.saveOutcome,
                )
            }.isSuccess,
        ) {
            "Atomic capture transaction has a corrupt save outcome"
        }
        val request = bundle.request
        val evidence = bundle.identityEvidence
        check(
            transaction.requestCanonicalFingerprint ==
                request.requestCanonicalFingerprint &&
                transaction.learnerId == request.learnerId &&
                transaction.captureIntentId == request.captureIntentId &&
                transaction.sourceKind == request.sourceKind &&
                transaction.sourceCanonicalFingerprint ==
                request.sourceCanonicalFingerprint &&
                transaction.identityResolutionKind ==
                evidence.resolutionKind.name &&
                transaction.identityEvidenceKind == evidence.evidenceKind.name &&
                transaction.identityEvidenceCanonicalFingerprint ==
                evidence.evidenceCanonicalFingerprint &&
                transaction.assetManifestCanonicalFingerprint ==
                request.assetManifestCanonicalFingerprint &&
                transaction.selectedRegionCanonicalFingerprint ==
                request.selectedRegionCanonicalFingerprint,
        ) {
            "Atomic capture request was replayed with different identity evidence"
        }
        val identity =
            checkNotNull(
                readCanonicalProblemIdentity(
                    learnerId = transaction.learnerId,
                    subject =
                        bundle.capture.confirmedMistake.problem.problem.subject,
                    identityNamespace = transaction.identityNamespace,
                    identityVersion = transaction.identityVersion,
                    stableKey = transaction.identityStableKey,
                ),
            ) {
                "Atomic capture receipt exists without its canonical identity"
            }
        check(
            identity.identityCanonicalFingerprint ==
                transaction.identityCanonicalFingerprint &&
                identity.problemId == transaction.targetProblemId,
        ) {
            "Atomic capture receipt crosses its canonical identity"
        }
        val expectedHandoff = bundle.capture.handoff
        val persistedHandoff =
            checkNotNull(readCaptureSaveHandoffByIntent(transaction.captureIntentId)) {
                "Atomic capture receipt exists without its capture handoff"
            }
        check(
            persistedHandoff.sameImmutableCaptureSource(expectedHandoff) &&
                persistedHandoff.targetProblemId ==
                transaction.targetProblemId &&
                persistedHandoff.targetRevisionId ==
                transaction.targetRevisionId &&
                persistedHandoff.targetRevisionNumber ==
                transaction.targetRevisionNumber &&
                persistedHandoff.targetDocumentCanonicalFingerprint ==
                transaction.targetDocumentCanonicalFingerprint &&
                persistedHandoff.targetCanonicalFingerprint ==
                transaction.targetCanonicalFingerprint,
        ) {
            "Atomic capture receipt is not bound to its immutable handoff"
        }
        check(
            readSaveReceipt(transaction.targetSaveReceiptId)?.let {
                it.learnerId == transaction.learnerId &&
                    it.problemId == transaction.targetProblemId &&
                    it.errorBookEntryId == persistedHandoff.errorBookEntryId
            } == true,
        ) {
            "Atomic capture receipt is not bound to its stable target-save receipt"
        }
        val occurrenceRows =
            readAtomicErrorOccurrenceCollisions(
                occurrenceId = transaction.occurrenceId,
                learnerId = transaction.learnerId,
                idempotencyKey = transaction.captureIntentId,
                batchCanonicalFingerprint =
                    transaction.batchCanonicalFingerprint,
                basisRevisionId = transaction.targetRevisionId,
            )
        check(
            occurrenceRows.size == 1 &&
                occurrenceRows.single().occurrenceCanonicalFingerprint ==
                transaction.occurrenceCanonicalFingerprint,
        ) {
            "Atomic capture receipt is not bound to its exact error occurrence"
        }
        val persistedOccurrence = occurrenceRows.single()
        val persistedEvidence =
            readAtomicErrorOccurrenceEvidence(transaction.occurrenceId)
        check(persistedEvidence.size == persistedOccurrence.evidenceCount) {
            "Atomic capture receipt has incomplete occurrence evidence"
        }
        return SaveStudentCaptureOccurrenceDbResult(
            identity = identity,
            transaction = transaction,
            handoff = persistedHandoff,
            occurrence =
                StudentProblemErrorOccurrenceBundle(
                    occurrence = persistedOccurrence,
                    evidence = persistedEvidence,
                ),
        )
    }
}
