package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentProblemIdentityEvidenceOwnerTest {
    @Test
    fun exactAssetSelectionIsPersistedAndReadBackBeforeTheOccurrenceWriter() = runBlocking {
        val ledger = InMemoryIdentityReceiptLedger()
        val occurrencePort = RecordingIdentityOccurrencePort()
        val owner =
            createStudentProblemIdentityEvidenceOwner(
                learnerId = LEARNER_ID,
                ledger = ledger,
                authority =
                    StudentProblemIdentityEvidenceAuthority.create(
                        "student-owner-key",
                        "v1",
                    ),
                captureOccurrences = occurrencePort,
                nowEpochMillis = { 10_000L },
            )

        assertTrue(
            runCatching {
                owner.saveExactAssetSelectionOrUnresolved(captureCommand())
            }.isFailure,
        )

        val receipt = ledger.snapshot().single()
        assertEquals(StudentProblemIdentityReceiptKind.EXACT_ASSET_SELECTION.name, receipt.receiptKind)
        assertEquals(1, receipt.renewalGeneration)
        assertEquals(1, occurrencePort.saveCalls)
    }

    @Test
    fun expiredExactReceiptRenewsByAppendingAnotherGeneration() = runBlocking {
        var now = 20_000L
        val ledger = InMemoryIdentityReceiptLedger()
        val occurrencePort = RecordingIdentityOccurrencePort()
        val owner =
            createStudentProblemIdentityEvidenceOwner(
                learnerId = LEARNER_ID,
                ledger = ledger,
                authority =
                    StudentProblemIdentityEvidenceAuthority.create(
                        "student-owner-key",
                        "v1",
                    ),
                captureOccurrences = occurrencePort,
                nowEpochMillis = { now },
                receiptValidityMillis = 100L,
            )
        val command = captureCommand()

        runCatching { owner.saveExactAssetSelectionOrUnresolved(command) }
        now += 101L
        runCatching { owner.saveExactAssetSelectionOrUnresolved(command) }

        val receipts = ledger.snapshot().sortedBy { it.renewalGeneration }
        assertEquals(listOf(1, 2), receipts.map { it.renewalGeneration })
        assertEquals(
            listOf(
                STUDENT_PROBLEM_IDENTITY_RECEIPT_FINGERPRINT_VERSION_CURRENT,
                STUDENT_PROBLEM_IDENTITY_RECEIPT_FINGERPRINT_VERSION_CURRENT,
            ),
            receipts.map { it.fingerprintVersion },
        )
        assertEquals(2, receipts.map { it.receiptId }.distinct().size)
        assertEquals(2, occurrencePort.saveCalls)
    }

    @Test
    fun trustedAndReviewedAdmissionFailClosedWithoutLocalOwners() = runBlocking {
        val ledger = InMemoryIdentityReceiptLedger()
        val occurrencePort = RecordingIdentityOccurrencePort()
        val owner =
            createStudentProblemIdentityEvidenceOwner(
                learnerId = LEARNER_ID,
                ledger = ledger,
                authority =
                    StudentProblemIdentityEvidenceAuthority.create(
                        "student-owner-key",
                        "v1",
                    ),
                captureOccurrences = occurrencePort,
            )
        val command = captureCommand()

        val trusted =
            runCatching {
                owner.registerTrustedSource(
                    RegisterTrustedStudentProblemSourceCommand(
                        registrationId = "trusted-without-owner",
                        capture = command,
                        locatorNamespace = "publisher-item",
                        locatorVersion = "v1",
                        itemLocatorCanonicalFingerprint = fingerprint("trusted-item"),
                    ),
                )
            }
        val reviewed =
            runCatching {
                owner.registerReviewedAlias(
                    RegisterReviewedStudentProblemAliasCommand(
                        registrationId = "reviewed-without-owner",
                        capture = command,
                        reviewAuthorityKind =
                            StudentProblemAliasReviewAuthorityKind.INDEPENDENT_REVIEW,
                        existingIdentity =
                            StudentProblemCanonicalIdentityKey(
                                namespace = "student-owner-opaque",
                                version = "v1",
                                stableKey = "existing-problem",
                            ),
                        reviewCaseId = "review-case-without-owner",
                        reviewRevision = 1,
                        reviewDecisionCanonicalFingerprint =
                            fingerprint("review-decision"),
                    ),
                )
            }

        assertTrue(trusted.isFailure)
        assertTrue(reviewed.isFailure)
        assertTrue(ledger.snapshot().isEmpty())
        assertEquals(0, occurrencePort.saveCalls)
    }

    @Test
    fun trustedReceiptBindsTheWholeCandidateAndPrivateOwnerSeal() = runBlocking {
        val ledger = InMemoryIdentityReceiptLedger()
        val authority =
            StudentProblemIdentityEvidenceAuthority.create("student-owner-key", "v1")
        val occurrencePort = RecordingIdentityOccurrencePort()
        val owner =
            createStudentProblemIdentityEvidenceOwner(
                learnerId = LEARNER_ID,
                ledger = ledger,
                authority = authority,
                captureOccurrences = occurrencePort,
                trustedSourceAdmissionOwner = TEST_TRUSTED_SOURCE_ADMISSION_OWNER,
                nowEpochMillis = { 1_000L },
                receiptValidityMillis = 5_000L,
            )
        val command = captureCommand()
        val reference =
            owner.registerTrustedSource(
                RegisterTrustedStudentProblemSourceCommand(
                    registrationId = "trusted-registration",
                    capture = command,
                    locatorNamespace = "publisher-item",
                    locatorVersion = "v3",
                    itemLocatorCanonicalFingerprint = fingerprint("publisher-item-42"),
                ),
            )

        val evidence = owner.verifyAndIssueTrustedSource(reference, command)
        val proof = evidence.proof
        val candidate = command.toIdentityCandidateBinding()

        assertTrue(authority.verifier.verifies(reference))
        assertTrue(authority.verifier.verifies(proof))
        assertEquals(reference.receiptId, proof.receiptId)
        assertEquals(candidate.learnerId, proof.learnerId)
        assertEquals(candidate.subject, proof.subject)
        assertEquals(candidate.sourceKind, proof.sourceKind)
        assertEquals(candidate.sourceIntentId, proof.sourceIntentId)
        assertEquals(candidate.idempotencyKey, proof.idempotencyKey)
        assertEquals(
            candidate.sourceCanonicalFingerprint,
            proof.sourceCanonicalFingerprint,
        )
        assertEquals(candidate.problemId, proof.problemId)
        assertEquals(candidate.practiceUnitId, proof.practiceUnitId)
        assertEquals(candidate.revisionId, proof.revisionId)
        assertEquals(candidate.revisionNumber, proof.revisionNumber)
        assertEquals(
            candidate.documentCanonicalFingerprint,
            proof.candidateDocumentCanonicalFingerprint,
        )
        assertEquals(
            candidate.targetCanonicalFingerprint,
            proof.targetCanonicalFingerprint,
        )
        assertEquals(
            candidate.assetManifestCanonicalFingerprint,
            proof.assetManifestCanonicalFingerprint,
        )
        assertEquals(
            candidate.selectedRegionCanonicalFingerprint,
            proof.selectedRegionCanonicalFingerprint,
        )
        assertEquals(candidate.canonicalFingerprint, proof.candidateCanonicalFingerprint)
        assertEquals("publisher-item", proof.locatorNamespace)
        assertEquals("v3", proof.locatorVersion)
        assertNotNull(ledger.read(reference.receiptId))
    }

    @Test
    fun everyCandidateMutationFailsBeforeTheUnderlyingSave() = runBlocking {
        val ledger = InMemoryIdentityReceiptLedger()
        val authority =
            StudentProblemIdentityEvidenceAuthority.create("student-owner-key", "v1")
        val occurrencePort = RecordingIdentityOccurrencePort()
        val owner =
            createStudentProblemIdentityEvidenceOwner(
                learnerId = LEARNER_ID,
                ledger = ledger,
                authority = authority,
                captureOccurrences = occurrencePort,
                trustedSourceAdmissionOwner = TEST_TRUSTED_SOURCE_ADMISSION_OWNER,
                nowEpochMillis = { 1_000L },
                receiptValidityMillis = 5_000L,
            )
        val original = captureCommand()
        val reference =
            owner.registerTrustedSource(
                RegisterTrustedStudentProblemSourceCommand(
                    registrationId = "tamper-registration",
                    capture = original,
                    locatorNamespace = "publisher-item",
                    locatorVersion = "v1",
                    itemLocatorCanonicalFingerprint = fingerprint("item"),
                ),
            )
        val mutations =
            linkedMapOf<String, (SaveStudentCaptureOccurrenceCommand) ->
                SaveStudentCaptureOccurrenceCommand>(
                "learner" to { it.withProblem(learnerId = "learner-other") },
                "subject" to { it.withProblem(subject = SubjectKind.PHYSICS) },
                "source kind" to { it.withLibrarySource() },
                "source intent and idempotency" to { it.withIntent("intent-other") },
                "source fingerprint" to {
                    it.withSourceFingerprint(fingerprint("source-other"))
                },
                "problem id" to { it.withProblem(problemId = "problem-other") },
                "practice unit id" to {
                    it.withProblem(practiceUnitId = "practice-other")
                },
                "revision id" to { it.withRevision(revisionId = "revision-other") },
                "revision number" to { it.withRevision(revisionNumber = 2) },
                "document" to { it.withDocument("另一份题面。") },
                "target" to {
                    it.withProblemCommand {
                        copy(practiceUnitTitle = "另一练习单元")
                    }
                },
                "asset manifest" to {
                    it.withImage {
                        copy(contentCanonicalFingerprint = fingerprint("other-asset"))
                    }
                },
                "selected regions" to {
                    it.withImage {
                        copy(
                            selectedRegions =
                                listOf(NormalizedSourceRegion(0.2, 0.2, 0.7, 0.8)),
                        )
                    }
                },
            )

        mutations.forEach { (label, mutate) ->
            val result =
                runCatching {
                    owner.verifyAndSaveTrustedSource(reference, mutate(original))
                }

            assertTrue("$label must be rejected", result.isFailure)
            assertEquals(
                "$label reached the underlying writer",
                0,
                occurrencePort.saveCalls,
            )
        }
    }

    @Test
    fun everyDurableReceiptFieldMutationFailsBeforeTheUnderlyingSave() = runBlocking {
        val ledger = InMemoryIdentityReceiptLedger()
        val authority =
            StudentProblemIdentityEvidenceAuthority.create("student-owner-key", "v1")
        val occurrencePort = RecordingIdentityOccurrencePort()
        val owner =
            createStudentProblemIdentityEvidenceOwner(
                learnerId = LEARNER_ID,
                ledger = ledger,
                authority = authority,
                captureOccurrences = occurrencePort,
                trustedSourceAdmissionOwner = TEST_TRUSTED_SOURCE_ADMISSION_OWNER,
                nowEpochMillis = { 1_000L },
                receiptValidityMillis = 5_000L,
            )
        val command = captureCommand()
        val reference =
            owner.registerTrustedSource(
                RegisterTrustedStudentProblemSourceCommand(
                    registrationId = "ledger-tamper-registration",
                    capture = command,
                    locatorNamespace = "publisher-item",
                    locatorVersion = "v1",
                    itemLocatorCanonicalFingerprint = fingerprint("ledger-item"),
                ),
            )
        val original = checkNotNull(ledger.read(reference.receiptId))
        val mutations =
            linkedMapOf<String, (StudentProblemIdentityReceiptEntity) ->
                StudentProblemIdentityReceiptEntity>(
                "receipt kind" to {
                    it.copy(receiptKind = StudentProblemIdentityReceiptKind.REVIEWED_ALIAS.name)
                },
                "receipt fingerprint" to {
                    it.copy(receiptCanonicalFingerprint = fingerprint("receipt-corrupt"))
                },
                "issuer key" to { it.copy(issuerKeyId = "issuer-other") },
                "issuer version" to { it.copy(issuerVersion = "v2") },
                "learner" to { it.copy(learnerId = "learner-other") },
                "subject" to { it.copy(subject = SubjectKind.PHYSICS.name) },
                "source kind" to { it.copy(sourceKind = "LIBRARY_CONFIRMATION") },
                "source intent" to { it.copy(sourceIntentId = "intent-other") },
                "idempotency" to { it.copy(idempotencyKey = "intent-other") },
                "source fingerprint" to {
                    it.copy(sourceCanonicalFingerprint = fingerprint("source-corrupt"))
                },
                "locator namespace" to { it.copy(locatorNamespace = "locator-other") },
                "locator version" to { it.copy(locatorVersion = "v2") },
                "item locator fingerprint" to {
                    it.copy(
                        itemLocatorCanonicalFingerprint =
                            fingerprint("locator-corrupt"),
                    )
                },
                "problem" to { it.copy(problemId = "problem-other") },
                "practice unit" to { it.copy(practiceUnitId = "practice-other") },
                "revision" to { it.copy(revisionId = "revision-other") },
                "revision number" to { it.copy(revisionNumber = 2) },
                "document fingerprint" to {
                    it.copy(documentCanonicalFingerprint = fingerprint("document-corrupt"))
                },
                "target fingerprint" to {
                    it.copy(targetCanonicalFingerprint = fingerprint("target-corrupt"))
                },
                "asset manifest" to {
                    it.copy(
                        assetManifestCanonicalFingerprint =
                            fingerprint("assets-corrupt"),
                    )
                },
                "selected regions" to {
                    it.copy(
                        selectedRegionCanonicalFingerprint =
                            fingerprint("regions-corrupt"),
                    )
                },
                "candidate fingerprint" to {
                    it.copy(candidateCanonicalFingerprint = fingerprint("candidate-corrupt"))
                },
                "review authority" to {
                    it.copy(
                        reviewAuthorityKind =
                            StudentProblemAliasReviewAuthorityKind.HUMAN.name,
                    )
                },
                "existing identity" to {
                    it.copy(
                        existingIdentityNamespace = "student-owner-opaque",
                        existingIdentityVersion = "v1",
                        existingIdentityStableKey = "unexpected",
                        existingIdentityCanonicalFingerprint =
                            fingerprint("unexpected-identity"),
                    )
                },
                "review decision" to {
                    it.copy(
                        reviewCaseId = "unexpected-review",
                        reviewRevision = 1,
                        reviewDecisionCanonicalFingerprint =
                            fingerprint("unexpected-review"),
                    )
                },
                "renewal generation" to { it.copy(renewalGeneration = 2) },
                "issued time" to { it.copy(issuedAtEpochMillis = 999L) },
                "expiry time" to { it.copy(expiresAtEpochMillis = 7_000L) },
            )

        mutations.forEach { (label, mutate) ->
            ledger.replaceForTest(mutate(original))
            val result =
                runCatching {
                    owner.verifyAndSaveTrustedSource(reference, command)
                }
            assertTrue("$label ledger tamper must be rejected", result.isFailure)
            assertEquals("$label reached the underlying writer", 0, occurrencePort.saveCalls)
        }
        ledger.replaceForTest(original)
    }

    @Test
    fun unauthenticatedExpiredAndUnavailableReceiptsFailClosed() = runBlocking {
        var now = 1_000L
        val ledger = InMemoryIdentityReceiptLedger()
        val authorityA =
            StudentProblemIdentityEvidenceAuthority.create("student-owner-key", "v1")
        val authorityB =
            StudentProblemIdentityEvidenceAuthority.create("student-owner-key", "v1")
        val occurrencePortA = RecordingIdentityOccurrencePort()
        val occurrencePortB = RecordingIdentityOccurrencePort()
        val ownerA =
            createStudentProblemIdentityEvidenceOwner(
                learnerId = LEARNER_ID,
                ledger = ledger,
                authority = authorityA,
                captureOccurrences = occurrencePortA,
                trustedSourceAdmissionOwner = TEST_TRUSTED_SOURCE_ADMISSION_OWNER,
                nowEpochMillis = { now },
                receiptValidityMillis = 100L,
            )
        val ownerB =
            createStudentProblemIdentityEvidenceOwner(
                learnerId = LEARNER_ID,
                ledger = ledger,
                authority = authorityB,
                captureOccurrences = occurrencePortB,
                trustedSourceAdmissionOwner = TEST_TRUSTED_SOURCE_ADMISSION_OWNER,
                nowEpochMillis = { now },
                receiptValidityMillis = 100L,
            )
        val command = captureCommand()
        val reference =
            ownerA.registerTrustedSource(
                RegisterTrustedStudentProblemSourceCommand(
                    registrationId = "closed-registration",
                    capture = command,
                    locatorNamespace = "publisher-item",
                    locatorVersion = "v1",
                    itemLocatorCanonicalFingerprint = fingerprint("closed-item"),
                ),
            )

        assertTrue(
            runCatching {
                ownerB.verifyAndSaveTrustedSource(reference, command)
            }.isFailure,
        )
        assertEquals(0, occurrencePortB.saveCalls)

        now = 1_100L
        assertTrue(
            runCatching {
                ownerA.verifyAndSaveTrustedSource(reference, command)
            }.isFailure,
        )
        assertEquals(0, occurrencePortA.saveCalls)

        now = 1_050L
        ledger.available = false
        assertTrue(
            runCatching {
                ownerA.verifyAndSaveTrustedSource(reference, command)
            }.isFailure,
        )
        assertEquals(0, occurrencePortA.saveCalls)
    }

    @Test
    fun reviewedAliasPreservesBothIdentitiesAndAuthenticatedReview() = runBlocking {
        val ledger = InMemoryIdentityReceiptLedger()
        val authority =
            StudentProblemIdentityEvidenceAuthority.create("student-owner-key", "v1")
        val occurrencePort = RecordingIdentityOccurrencePort()
        val owner =
            createStudentProblemIdentityEvidenceOwner(
                learnerId = LEARNER_ID,
                ledger = ledger,
                authority = authority,
                captureOccurrences = occurrencePort,
                reviewedAliasAdmissionOwner = TEST_REVIEWED_ALIAS_ADMISSION_OWNER,
                nowEpochMillis = { 2_000L },
                receiptValidityMillis = 5_000L,
            )
        val candidate = captureCommand()
        val existingIdentity =
            StudentProblemCanonicalIdentityKey(
                namespace = "student-owner-opaque",
                version = "v1",
                stableKey = "existing-stable-key",
            )
        val reference =
            owner.registerReviewedAlias(
                RegisterReviewedStudentProblemAliasCommand(
                    registrationId = "alias-registration",
                    capture = candidate,
                    reviewAuthorityKind =
                        StudentProblemAliasReviewAuthorityKind.INDEPENDENT_REVIEW,
                    existingIdentity = existingIdentity,
                    reviewCaseId = "review-case-1",
                    reviewRevision = 3,
                    reviewDecisionCanonicalFingerprint =
                        fingerprint("review-decision"),
                ),
            )

        val evidence = owner.verifyAndIssueReviewedAlias(reference, candidate)
        val proof = evidence.proof

        assertTrue(authority.verifier.verifies(proof))
        assertEquals("INDEPENDENT_REVIEW", proof.reviewAuthorityKind)
        assertEquals(existingIdentity.namespace, proof.existingIdentityNamespace)
        assertEquals(existingIdentity.version, proof.existingIdentityVersion)
        assertEquals(existingIdentity.stableKey, proof.existingIdentityStableKey)
        assertEquals("review-case-1", proof.reviewCaseId)
        assertEquals(3, proof.reviewRevision)
        assertEquals(
            candidate.toIdentityCandidateBinding().canonicalFingerprint,
            proof.candidateCanonicalFingerprint,
        )
        assertFalse(proof.existingIdentityStableKey == candidate.capture.target.problem.revision.problem.problemId)
    }
}

private class InMemoryIdentityReceiptLedger : StudentProblemIdentityReceiptLedger {
    private val rows = linkedMapOf<String, StudentProblemIdentityReceiptEntity>()
    var available: Boolean = true

    override suspend fun record(
        receipt: StudentProblemIdentityReceiptEntity,
    ): StudentProblemIdentityReceiptEntity {
        check(available) { "Identity ledger unavailable" }
        val existing = rows.putIfAbsent(receipt.receiptId, receipt)
        return existing ?: receipt
    }

    override suspend fun read(
        receiptId: String,
    ): StudentProblemIdentityReceiptEntity? {
        check(available) { "Identity ledger unavailable" }
        return rows[receiptId]
    }

    override suspend fun readLatestCandidate(
        learnerId: String,
        subject: String,
        kind: StudentProblemIdentityReceiptKind,
        assetManifestCanonicalFingerprint: String,
        selectedRegionCanonicalFingerprint: String,
        candidateCanonicalFingerprint: String,
    ): StudentProblemIdentityReceiptEntity? {
        check(available) { "Identity ledger unavailable" }
        return rows.values
            .asSequence()
            .filter {
                it.learnerId == learnerId &&
                    it.subject == subject &&
                    it.receiptKind == kind.name &&
                    it.assetManifestCanonicalFingerprint ==
                    assetManifestCanonicalFingerprint &&
                    it.selectedRegionCanonicalFingerprint ==
                    selectedRegionCanonicalFingerprint &&
                    it.candidateCanonicalFingerprint == candidateCanonicalFingerprint
            }
            .maxWithOrNull(
                compareBy<StudentProblemIdentityReceiptEntity>(
                    StudentProblemIdentityReceiptEntity::renewalGeneration,
                ).thenBy(StudentProblemIdentityReceiptEntity::issuedAtEpochMillis),
            )
    }

    fun replaceForTest(receipt: StudentProblemIdentityReceiptEntity) {
        rows[receipt.receiptId] = receipt
    }

    fun snapshot(): List<StudentProblemIdentityReceiptEntity> = rows.values.toList()
}

private class RecordingIdentityOccurrencePort : LearnerBoundStudentCaptureOccurrencePort {
    override val learnerId: String = LEARNER_ID
    var saveCalls: Int = 0

    override suspend fun save(
        command: SaveStudentCaptureOccurrenceCommand,
    ): StudentCaptureOccurrenceReceipt {
        saveCalls += 1
        error("Receipt construction is outside this fail-before-save test")
    }
}

private fun captureCommand(
    intentId: String = "capture-intent-1",
    learnerId: String = LEARNER_ID,
    subject: SubjectKind = SubjectKind.MATH,
): SaveStudentCaptureOccurrenceCommand {
    val document = capturedDocument(intentId, "求函数值。")
    val problem =
        StudentProblemRef(
            learnerId = learnerId,
            subject = subject,
            problemId = "problem-$intentId",
            practiceUnitId = "practice-$intentId",
        )
    val revision =
        StudentProblemRevisionRef(
            problem = problem,
            revisionId = "revision-$intentId",
            revisionNumber = 1,
            documentCanonicalFingerprint =
                CapturedQuestionDocumentFingerprint.of(document),
        )
    val source =
        TutorStudentCaptureSaveSource(
            intentId = intentId,
            sourceCanonicalFingerprint = fingerprint("source:$intentId"),
            saveRequestId = "save-$intentId",
            sessionId = "session-$intentId",
            draftId = "draft-$intentId",
            draftRevisionNumber = 1,
            occurredAtEpochMillis = 1_000L,
        )
    val target =
        SaveTargetConfirmedStudentMistakeCommand(
            problem =
                CommitStudentProblemCommand(
                    revision = revision,
                    title = document.document.title,
                    stemMarkdown =
                        QuestionDocumentMarkdownProjection.project(document.document),
                    practiceUnitKind = StudentPracticeUnitKind.WHOLE_PROBLEM,
                    practiceUnitTitle = "整题",
                    itemFamilyId = "family-$intentId",
                    estimatedDurationSeconds = 120,
                    sourceBundleId = null,
                    partIds = emptyList(),
                    originalImages =
                        listOf(
                            StudentProblemImageReference(
                                imageReferenceId = "image-$intentId",
                                localContentUri = "content://capture/$intentId",
                                contentCanonicalFingerprint = fingerprint("asset:$intentId"),
                                mediaType = "image/jpeg",
                                ordinal = 0,
                                widthPixels = 1_200,
                                heightPixels = 900,
                                byteSize = 42_000L,
                                selectedRegions =
                                    listOf(
                                        NormalizedSourceRegion(0.1, 0.1, 0.9, 0.9),
                                    ),
                            ),
                        ),
                    committedAtEpochMillis = source.occurredAtEpochMillis,
                    errorBookEntryId = "entry-$intentId",
                    capturedQuestionDocument = document,
                ),
            confirmedAtEpochMillis = source.occurredAtEpochMillis,
        )
    return SaveStudentCaptureOccurrenceCommand(
        capture = SaveStudentOwnedCaptureCommand(source = source, target = target),
        occurrence =
            AppendStudentProblemErrorOccurrenceCommand(
                occurrenceId = "occurrence-$intentId",
                idempotencyKey = intentId,
                problemRevision = revision,
                batchCanonicalFingerprint = fingerprint("batch:$intentId"),
                importSourceCanonicalFingerprint = fingerprint("import:$intentId"),
                occurredAtEpochMillis = source.occurredAtEpochMillis,
                importedAtEpochMillis = source.occurredAtEpochMillis,
                attributionStatus = ProblemErrorAttributionResolutionStatus.UNRESOLVED,
            ),
    )
}

private fun capturedDocument(
    intentId: String,
    markdown: String,
): CapturedQuestionDocument =
    CapturedQuestionDocument(
        document =
            QuestionDocument(
                id = "document-$intentId",
                title = "测试题",
                blocks = listOf(ContentBlock.Paragraph(id = "stem", markdown = markdown)),
            ),
        blockEvidence =
            listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = "image-$intentId",
                    sourceRegion = NormalizedSourceRegion(0.1, 0.1, 0.9, 0.9),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    confidence = 1.0,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
    )

private fun SaveStudentCaptureOccurrenceCommand.withIntent(
    intentId: String,
): SaveStudentCaptureOccurrenceCommand {
    val source = capture.source as TutorStudentCaptureSaveSource
    return copy(
        capture = capture.copy(source = source.copy(intentId = intentId)),
        occurrence = occurrence.copy(idempotencyKey = intentId),
    )
}

private fun SaveStudentCaptureOccurrenceCommand.withSourceFingerprint(
    value: String,
): SaveStudentCaptureOccurrenceCommand {
    val source = capture.source as TutorStudentCaptureSaveSource
    return copy(capture = capture.copy(source = source.copy(sourceCanonicalFingerprint = value)))
}

private fun SaveStudentCaptureOccurrenceCommand.withLibrarySource():
    SaveStudentCaptureOccurrenceCommand {
    val source = capture.source
    return copy(
        capture =
            capture.copy(
                source =
                    LibraryStudentCaptureSaveSource(
                        intentId = source.intentId,
                        sourceCanonicalFingerprint = source.sourceCanonicalFingerprint,
                        draftId = source.draftId,
                        basisRevisionNumber = 1,
                        workspaceVersion = 1,
                        workspaceCanonicalFingerprint = fingerprint("workspace"),
                        confirmationRequestId = "confirmation-${source.intentId}",
                        occurredAtEpochMillis = source.occurredAtEpochMillis,
                    ),
            ),
    )
}

private fun SaveStudentCaptureOccurrenceCommand.withProblem(
    learnerId: String = capture.target.problem.revision.problem.learnerId,
    subject: SubjectKind = capture.target.problem.revision.problem.subject,
    problemId: String = capture.target.problem.revision.problem.problemId,
    practiceUnitId: String =
        capture.target.problem.revision.problem.practiceUnitId,
): SaveStudentCaptureOccurrenceCommand {
    val current = capture.target.problem.revision
    val changedProblem =
        current.problem.copy(
            learnerId = learnerId,
            subject = subject,
            problemId = problemId,
            practiceUnitId = practiceUnitId,
        )
    return withRevisionObject(current.copy(problem = changedProblem))
}

private fun SaveStudentCaptureOccurrenceCommand.withRevision(
    revisionId: String = capture.target.problem.revision.revisionId,
    revisionNumber: Int = capture.target.problem.revision.revisionNumber,
): SaveStudentCaptureOccurrenceCommand =
    withRevisionObject(
        capture.target.problem.revision.copy(
            revisionId = revisionId,
            revisionNumber = revisionNumber,
        ),
    )

private fun SaveStudentCaptureOccurrenceCommand.withRevisionObject(
    revision: StudentProblemRevisionRef,
): SaveStudentCaptureOccurrenceCommand =
    copy(
        capture =
            capture.copy(
                target =
                    capture.target.copy(
                        problem = capture.target.problem.copy(revision = revision),
                    ),
            ),
        occurrence = occurrence.copy(problemRevision = revision),
    )

private fun SaveStudentCaptureOccurrenceCommand.withDocument(
    markdown: String,
): SaveStudentCaptureOccurrenceCommand {
    val current = capture.target.problem
    val document = capturedDocument(capture.source.intentId, markdown)
    val revision =
        current.revision.copy(
            documentCanonicalFingerprint =
                CapturedQuestionDocumentFingerprint.of(document),
        )
    return copy(
        capture =
            capture.copy(
                target =
                    capture.target.copy(
                        problem =
                            current.copy(
                                revision = revision,
                                title = document.document.title,
                                stemMarkdown =
                                    QuestionDocumentMarkdownProjection.project(
                                        document.document,
                                    ),
                                capturedQuestionDocument = document,
                            ),
                    ),
            ),
        occurrence = occurrence.copy(problemRevision = revision),
    )
}

private fun SaveStudentCaptureOccurrenceCommand.withProblemCommand(
    transform: CommitStudentProblemCommand.() -> CommitStudentProblemCommand,
): SaveStudentCaptureOccurrenceCommand =
    copy(
        capture =
            capture.copy(
                target =
                    capture.target.copy(
                        problem = capture.target.problem.transform(),
                    ),
            ),
    )

private fun SaveStudentCaptureOccurrenceCommand.withImage(
    transform: StudentProblemImageReference.() -> StudentProblemImageReference,
): SaveStudentCaptureOccurrenceCommand =
    withProblemCommand {
        copy(originalImages = originalImages.map { image -> image.transform() })
    }

private fun fingerprint(value: String): String =
    CanonicalSha256("identity-owner-test")
        .field("value", value)
        .finish()

private val TEST_TRUSTED_SOURCE_ADMISSION_OWNER =
    TrustedStudentProblemSourceAdmissionOwner { command ->
        check(command.capture.capture.target.problem.revision.problem.learnerId == LEARNER_ID)
    }

private val TEST_REVIEWED_ALIAS_ADMISSION_OWNER =
    ReviewedStudentProblemAliasAdmissionOwner { command ->
        check(command.capture.capture.target.problem.revision.problem.learnerId == LEARNER_ID)
    }

private const val LEARNER_ID = "learner-identity-owner"
