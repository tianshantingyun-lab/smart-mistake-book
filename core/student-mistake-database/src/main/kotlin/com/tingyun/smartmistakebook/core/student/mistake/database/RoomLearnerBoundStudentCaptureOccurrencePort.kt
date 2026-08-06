package com.tingyun.smartmistakebook.core.student.mistake.database

private class RoomLearnerBoundStudentCaptureOccurrencePort(
    private val store: RoomStudentMistakeStore,
    private val dao: StudentMistakeDao,
    override val learnerId: String,
    private val identityEvidenceVerifier:
        StudentProblemIdentityEvidenceAuthority.Verifier,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : LearnerBoundStudentCaptureOccurrencePort {
    init {
        learnerId.requireStoreText(
            "Capture-occurrence owner learner id",
            MAX_ID_CHARS,
        )
    }

    override suspend fun save(
        command: SaveStudentCaptureOccurrenceCommand,
    ): StudentCaptureOccurrenceReceipt {
        val target = command.capture.target
        val revision = target.problem.revision
        require(revision.problem.learnerId == learnerId) {
            "Capture occurrence command crosses the learner-bound capability"
        }
        require(command.occurrence.problemRevision.problem.learnerId == learnerId) {
            "Error occurrence crosses the learner-bound capture capability"
        }
        revision.problem.subject.requireHighSchoolSubject()

        val assetManifestCanonicalFingerprint =
            studentCaptureAssetManifestCanonicalFingerprint(
                target.problem.originalImages,
            )
        val selectedRegionCanonicalFingerprint =
            studentCaptureSelectedRegionCanonicalFingerprint(
                target.problem.originalImages,
            )
        val identityEvidence =
            command.resolveIdentityEvidence(
                verifier = identityEvidenceVerifier,
                assetManifestCanonicalFingerprint =
                    assetManifestCanonicalFingerprint,
                selectedRegionCanonicalFingerprint =
                    selectedRegionCanonicalFingerprint,
                nowEpochMillis = nowEpochMillis(),
            )
        val confirmedMistake = store.prepareTargetConfirmedMistakeBundle(target)
        val handoff = command.capture.source.toAtomicCaptureHandoffEntity(target, learnerId)
        val result =
            dao.saveStudentCaptureOccurrence(
                SaveStudentCaptureOccurrenceBundle(
                    identityEvidence = identityEvidence,
                    capture =
                        SaveStudentOwnedCaptureBundle(
                            confirmedMistake = confirmedMistake,
                            handoff = handoff,
                        ),
                    occurrence = command.occurrence.toErrorOccurrenceBundle(),
                    request =
                        StudentCaptureOccurrenceRequestDraft(
                            transactionId = command.transactionId,
                            requestCanonicalFingerprint =
                                command.requestCanonicalFingerprint,
                            schemaVersion =
                                STUDENT_CAPTURE_OCCURRENCE_TRANSACTION_SCHEMA_VERSION,
                            learnerId = learnerId,
                            captureIntentId = command.capture.source.intentId,
                            sourceKind = command.capture.source.kind.name,
                            sourceCanonicalFingerprint =
                                command.capture.source.sourceCanonicalFingerprint,
                            assetManifestCanonicalFingerprint =
                                assetManifestCanonicalFingerprint,
                            selectedRegionCanonicalFingerprint =
                                selectedRegionCanonicalFingerprint,
                            committedAtEpochMillis =
                                command.occurrence.occurredAtEpochMillis,
                        ),
                ),
            )
        check(result.transaction.transactionId == command.transactionId) {
            "Atomic capture returned another transaction"
        }
        val saveOutcome =
            runCatching {
                TargetConfirmedStudentMistakeSaveOutcome.valueOf(
                    result.transaction.saveOutcome,
                )
            }.getOrElse {
                throw IllegalStateException(
                    "Corrupt atomic capture save outcome",
                    it,
                )
            }
        val storedOccurrence = result.occurrence.toDomainErrorOccurrence()
        val resolvedRevision = storedOccurrence.ref.problemRevision
        return StudentCaptureOccurrenceReceipt(
            transactionId = result.transaction.transactionId,
            transactionCanonicalFingerprint =
                result.transaction.transactionCanonicalFingerprint,
            assetManifestCanonicalFingerprint =
                result.transaction.assetManifestCanonicalFingerprint,
            selectedRegionCanonicalFingerprint =
                result.transaction.selectedRegionCanonicalFingerprint,
            resolvedIdentity =
                StudentProblemCanonicalIdentityKey(
                    namespace = result.identity.identityNamespace,
                    version = result.identity.identityVersion,
                    stableKey = result.identity.stableKey,
                ),
            save =
                StudentOwnedCaptureSaveReceipt(
                    outcome = saveOutcome,
                    handoff =
                        StudentCaptureSaveHandoffRecord(
                            source = command.capture.source,
                            learnerId = learnerId,
                            targetProblem = resolvedRevision.problem,
                            targetRevision = resolvedRevision,
                            errorBookEntryId =
                                result.handoff.errorBookEntryId,
                            targetCanonicalFingerprint =
                                result.handoff.targetCanonicalFingerprint,
                            acknowledgedAtEpochMillis =
                                result.handoff.acknowledgedAtEpochMillis,
                        ),
                ),
            occurrence = storedOccurrence,
        )
    }
}

@JvmSynthetic
internal fun createRoomLearnerBoundStudentCaptureOccurrencePort(
    store: RoomStudentMistakeStore,
    dao: StudentMistakeDao,
    learnerId: String,
    identityEvidenceVerifier: StudentProblemIdentityEvidenceAuthority.Verifier,
): LearnerBoundStudentCaptureOccurrencePort =
    RoomLearnerBoundStudentCaptureOccurrencePort(
        store = store,
        dao = dao,
        learnerId = learnerId,
        identityEvidenceVerifier = identityEvidenceVerifier,
    )

private fun SaveStudentCaptureOccurrenceCommand.resolveIdentityEvidence(
    verifier: StudentProblemIdentityEvidenceAuthority.Verifier,
    assetManifestCanonicalFingerprint: String,
    selectedRegionCanonicalFingerprint: String,
    nowEpochMillis: Long,
): StudentProblemIdentityEvidenceDraft {
    val revision = capture.target.problem.revision
    val learnerId = revision.problem.learnerId
    val subject = revision.problem.subject.name
    val documentCanonicalFingerprint = revision.documentCanonicalFingerprint
    val candidate = toIdentityCandidateBinding()
    return when (val evidence = identityEvidence) {
        StudentProblemIdentityEvidence.Unresolved -> {
            val evidenceKind =
                StudentProblemIdentityEvidenceKind.OPAQUE_CAPTURE_INTENT
            StudentProblemIdentityEvidenceDraft(
                resolutionKind = StudentProblemIdentityResolutionKind.FRESH_OPAQUE,
                evidenceKind = evidenceKind,
                evidenceCanonicalFingerprint =
                    canonicalStudentProblemIdentityEvidenceFingerprint(
                        evidenceKind = evidenceKind,
                        transactionId = transactionId,
                        assetManifestCanonicalFingerprint =
                            assetManifestCanonicalFingerprint,
                        selectedRegionCanonicalFingerprint =
                            selectedRegionCanonicalFingerprint,
                    ),
                candidateDocumentCanonicalFingerprint =
                    documentCanonicalFingerprint,
                assetManifestCanonicalFingerprint =
                    assetManifestCanonicalFingerprint,
                selectedRegionCanonicalFingerprint =
                    selectedRegionCanonicalFingerprint,
            )
        }
        StudentProblemIdentityEvidence.ExactAssetSelection -> {
            val evidenceKind =
                StudentProblemIdentityEvidenceKind.EXACT_ASSET_SELECTION
            StudentProblemIdentityEvidenceDraft(
                resolutionKind =
                    StudentProblemIdentityResolutionKind.EXACT_ASSET_SELECTION,
                evidenceKind = evidenceKind,
                evidenceCanonicalFingerprint =
                    canonicalStudentProblemIdentityEvidenceFingerprint(
                        evidenceKind = evidenceKind,
                        transactionId = transactionId,
                        assetManifestCanonicalFingerprint =
                            assetManifestCanonicalFingerprint,
                        selectedRegionCanonicalFingerprint =
                            selectedRegionCanonicalFingerprint,
                    ),
                candidateDocumentCanonicalFingerprint =
                    documentCanonicalFingerprint,
                assetManifestCanonicalFingerprint =
                    assetManifestCanonicalFingerprint,
                selectedRegionCanonicalFingerprint =
                    selectedRegionCanonicalFingerprint,
            )
        }
        is StudentProblemIdentityEvidenceAuthority.TrustedSourceEvidence -> {
            val proof = evidence.proof
            require(verifier.verifies(proof)) {
                "Trusted source proof was not issued by this student-owner process"
            }
            require(nowEpochMillis in proof.issuedAtEpochMillis until proof.expiresAtEpochMillis) {
                "Trusted source proof is not currently valid"
            }
            require(
                proof.learnerId == learnerId &&
                    proof.subject == subject &&
                    proof.sourceKind == capture.source.kind.name &&
                    proof.sourceIntentId == capture.source.intentId &&
                    proof.idempotencyKey == occurrence.idempotencyKey &&
                    proof.sourceCanonicalFingerprint ==
                    capture.source.sourceCanonicalFingerprint &&
                    proof.problemId == revision.problem.problemId &&
                    proof.practiceUnitId == revision.problem.practiceUnitId &&
                    proof.revisionId == revision.revisionId &&
                    proof.revisionNumber == revision.revisionNumber &&
                    proof.assetManifestCanonicalFingerprint ==
                    assetManifestCanonicalFingerprint &&
                    proof.selectedRegionCanonicalFingerprint ==
                    selectedRegionCanonicalFingerprint &&
                    proof.candidateDocumentCanonicalFingerprint ==
                    documentCanonicalFingerprint &&
                    proof.targetCanonicalFingerprint ==
                    capture.target.targetCanonicalFingerprint &&
                    proof.candidateCanonicalFingerprint ==
                    candidate.canonicalFingerprint,
            ) {
                "Trusted source proof does not bind this exact capture"
            }
            val evidenceKind = StudentProblemIdentityEvidenceKind.TRUSTED_SOURCE
            StudentProblemIdentityEvidenceDraft(
                resolutionKind = StudentProblemIdentityResolutionKind.TRUSTED_SOURCE,
                evidenceKind = evidenceKind,
                evidenceCanonicalFingerprint =
                    canonicalStudentProblemIdentityEvidenceFingerprint(
                        evidenceKind = evidenceKind,
                        transactionId = transactionId,
                        assetManifestCanonicalFingerprint =
                            assetManifestCanonicalFingerprint,
                        selectedRegionCanonicalFingerprint =
                            selectedRegionCanonicalFingerprint,
                        locatorNamespace = proof.locatorNamespace,
                        locatorVersion = proof.locatorVersion,
                        itemLocatorCanonicalFingerprint =
                            proof.itemLocatorCanonicalFingerprint,
                    ),
                candidateDocumentCanonicalFingerprint =
                    documentCanonicalFingerprint,
                assetManifestCanonicalFingerprint =
                    assetManifestCanonicalFingerprint,
                selectedRegionCanonicalFingerprint =
                    selectedRegionCanonicalFingerprint,
                locatorNamespace = proof.locatorNamespace,
                locatorVersion = proof.locatorVersion,
                itemLocatorCanonicalFingerprint =
                    proof.itemLocatorCanonicalFingerprint,
                trustedSourceProofFingerprint =
                    proof.proofCanonicalFingerprint,
            )
        }
        is StudentProblemIdentityEvidenceAuthority.ReviewedAliasEvidence -> {
            val proof = evidence.proof
            require(verifier.verifies(proof)) {
                "Reviewed alias proof was not issued by this student-owner process"
            }
            require(nowEpochMillis in proof.issuedAtEpochMillis until proof.expiresAtEpochMillis) {
                "Reviewed alias proof is not currently valid"
            }
            require(
                proof.learnerId == learnerId &&
                    proof.subject == subject &&
                    proof.sourceKind == capture.source.kind.name &&
                    proof.sourceIntentId == capture.source.intentId &&
                    proof.idempotencyKey == occurrence.idempotencyKey &&
                    proof.sourceCanonicalFingerprint ==
                    capture.source.sourceCanonicalFingerprint &&
                    proof.problemId == revision.problem.problemId &&
                    proof.practiceUnitId == revision.problem.practiceUnitId &&
                    proof.revisionId == revision.revisionId &&
                    proof.revisionNumber == revision.revisionNumber &&
                    proof.candidateAssetManifestCanonicalFingerprint ==
                    assetManifestCanonicalFingerprint &&
                    proof.candidateSelectedRegionCanonicalFingerprint ==
                    selectedRegionCanonicalFingerprint &&
                    proof.candidateDocumentCanonicalFingerprint ==
                    documentCanonicalFingerprint &&
                    proof.targetCanonicalFingerprint ==
                    capture.target.targetCanonicalFingerprint &&
                    proof.candidateCanonicalFingerprint ==
                    candidate.canonicalFingerprint,
            ) {
                "Reviewed alias proof does not bind this exact capture"
            }
            require(
                proof.reviewAuthorityKind ==
                    StudentProblemAliasReviewAuthorityKind.HUMAN.name ||
                    proof.reviewAuthorityKind ==
                    StudentProblemAliasReviewAuthorityKind.INDEPENDENT_REVIEW.name,
            ) {
                "Reviewed alias proof lacks an authenticated review authority"
            }
            val aliasKey =
                StudentProblemCanonicalIdentityKey(
                    namespace = proof.existingIdentityNamespace,
                    version = proof.existingIdentityVersion,
                    stableKey = proof.existingIdentityStableKey,
                )
            require(
                aliasKey.canonicalFingerprint(
                    learnerId = learnerId,
                    subject = subject,
                ) == proof.existingIdentityCanonicalFingerprint,
            ) {
                "Reviewed alias proof contains a corrupt existing identity"
            }
            val evidenceKind =
                StudentProblemIdentityEvidenceKind.REVIEWED_ALIAS
            StudentProblemIdentityEvidenceDraft(
                resolutionKind = StudentProblemIdentityResolutionKind.REVIEWED_ALIAS,
                evidenceKind = evidenceKind,
                evidenceCanonicalFingerprint =
                    canonicalStudentProblemIdentityEvidenceFingerprint(
                        evidenceKind = evidenceKind,
                        transactionId = transactionId,
                        assetManifestCanonicalFingerprint =
                            assetManifestCanonicalFingerprint,
                        selectedRegionCanonicalFingerprint =
                            selectedRegionCanonicalFingerprint,
                        candidateDocumentCanonicalFingerprint =
                            documentCanonicalFingerprint,
                        aliasIdentityCanonicalFingerprint =
                            proof.existingIdentityCanonicalFingerprint,
                        reviewCaseId = proof.reviewCaseId,
                        reviewRevision = proof.reviewRevision,
                        reviewDecisionCanonicalFingerprint =
                            proof.reviewDecisionCanonicalFingerprint,
                    ),
                candidateDocumentCanonicalFingerprint =
                    documentCanonicalFingerprint,
                assetManifestCanonicalFingerprint =
                    assetManifestCanonicalFingerprint,
                selectedRegionCanonicalFingerprint =
                    selectedRegionCanonicalFingerprint,
                aliasIdentityNamespace = proof.existingIdentityNamespace,
                aliasIdentityVersion = proof.existingIdentityVersion,
                aliasIdentityStableKey = proof.existingIdentityStableKey,
                aliasIdentityCanonicalFingerprint =
                    proof.existingIdentityCanonicalFingerprint,
                reviewedAliasProofFingerprint =
                    proof.proofCanonicalFingerprint,
                reviewCaseId = proof.reviewCaseId,
                reviewRevision = proof.reviewRevision,
                reviewDecisionCanonicalFingerprint =
                    proof.reviewDecisionCanonicalFingerprint,
            )
        }
        else -> error("Unsupported student problem identity evidence")
    }
}

private fun StudentCaptureSaveSource.toAtomicCaptureHandoffEntity(
    target: SaveTargetConfirmedStudentMistakeCommand,
    learnerId: String,
): StudentCaptureSaveHandoffEntity {
    val revision = target.problem.revision
    val library = this as? LibraryStudentCaptureSaveSource
    val tutor = this as? TutorStudentCaptureSaveSource
    return StudentCaptureSaveHandoffEntity(
        intentId = intentId,
        sourceKind = kind.name,
        sourceCanonicalFingerprint = sourceCanonicalFingerprint,
        learnerId = learnerId,
        draftId = draftId,
        draftRevisionNumber = draftRevisionNumber,
        sessionId = sessionId,
        basisRevisionNumber = library?.basisRevisionNumber,
        workspaceVersion = library?.workspaceVersion,
        workspaceCanonicalFingerprint = library?.workspaceCanonicalFingerprint,
        confirmationRequestId = library?.confirmationRequestId,
        saveRequestId = tutor?.saveRequestId,
        targetSubject = revision.problem.subject.name,
        targetProblemId = revision.problem.problemId,
        targetPracticeUnitId = revision.problem.practiceUnitId,
        targetRevisionId = revision.revisionId,
        targetRevisionNumber = revision.revisionNumber,
        targetDocumentCanonicalFingerprint =
            revision.documentCanonicalFingerprint,
        errorBookEntryId = checkNotNull(target.problem.errorBookEntryId),
        targetCanonicalFingerprint = target.targetCanonicalFingerprint,
        occurredAtEpochMillis = occurredAtEpochMillis,
        acknowledgedAtEpochMillis = null,
        schemaVersion = STUDENT_CAPTURE_SAVE_HANDOFF_SCHEMA_VERSION,
    )
}
