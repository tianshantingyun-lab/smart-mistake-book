package com.tingyun.smartmistakebook.core.student.mistake.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
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
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class StudentCanonicalCaptureIdentityInstrumentedTest {
    @Test
    fun exactAssetSelectionAcrossIntentsReusesCanonicalIdentityAndAppendsOnlyOccurrences() =
        runBlocking {
            withCaptureFixture(REUSE_DATABASE_NAME) { fixture ->
                val document =
                    capturedQuestionDocument(
                        documentId = "document-a",
                        sourceAssetId = "asset-a",
                        markdown = "求函数 f(x)=x+1 在 x=2 时的值。",
                    )
                val firstCommand =
                    captureCommand(
                        intentId = "reuse-intent-1",
                        occurredAtEpochMillis = 1_000L,
                        document = document,
                        assetFingerprint = fingerprint("asset-a"),
                        identityEvidence =
                            StudentProblemIdentityEvidence.ExactAssetSelection,
                    )
                val secondCommand =
                    captureCommand(
                        intentId = "reuse-intent-2",
                        occurredAtEpochMillis = 2_000L,
                        document = document,
                        assetFingerprint = fingerprint("asset-a"),
                        identityEvidence =
                            StudentProblemIdentityEvidence.ExactAssetSelection,
                    )

                assertNotEquals(
                    firstCommand.capture.source.intentId,
                    secondCommand.capture.source.intentId,
                )
                assertNotEquals(
                    firstCommand.capture.target.problem.revision.problem.problemId,
                    secondCommand.capture.target.problem.revision.problem.problemId,
                )
                assertNotEquals(
                    firstCommand.occurrence.batchCanonicalFingerprint,
                    secondCommand.occurrence.batchCanonicalFingerprint,
                )
                assertTrue(
                    firstCommand.capture.target.problem.originalImages
                        .single()
                        .selectedRegions
                        .isNotEmpty(),
                )
                assertEquals(
                    studentCaptureAssetManifestCanonicalFingerprint(
                        firstCommand.capture.target.problem.originalImages,
                    ),
                    studentCaptureAssetManifestCanonicalFingerprint(
                        secondCommand.capture.target.problem.originalImages,
                    ),
                )
                assertEquals(
                    studentCaptureSelectedRegionCanonicalFingerprint(
                        firstCommand.capture.target.problem.originalImages,
                    ),
                    studentCaptureSelectedRegionCanonicalFingerprint(
                        secondCommand.capture.target.problem.originalImages,
                    ),
                )
                assertEquals(
                    firstCommand.capture.target.problem.revision
                        .documentCanonicalFingerprint,
                    secondCommand.capture.target.problem.revision
                        .documentCanonicalFingerprint,
                )

                val first = fixture.captures.save(firstCommand)
                val second = fixture.captures.save(secondCommand)

                assertEquals(
                    TargetConfirmedStudentMistakeSaveOutcome.CREATED,
                    first.save.outcome,
                )
                assertEquals(
                    TargetConfirmedStudentMistakeSaveOutcome.DUPLICATE,
                    second.save.outcome,
                )
                assertEquals(first.resolvedIdentity, second.resolvedIdentity)
                assertEquals(
                    first.save.handoff.targetProblem,
                    second.save.handoff.targetProblem,
                )
                assertEquals(
                    first.save.handoff.targetRevision,
                    second.save.handoff.targetRevision,
                )
                assertNotEquals(
                    firstCommand.capture.target.problem.revision.problem.problemId,
                    first.save.handoff.targetProblem.problemId,
                )
                assertNotEquals(
                    first.occurrence.ref.occurrenceId,
                    second.occurrence.ref.occurrenceId,
                )

                fixture.openReadOnlyDatabase().use { database ->
                    database.assertRowCounts(
                        "student_problem_canonical_identity" to 1,
                        "student_problem_canonical_source_binding" to 1,
                        "student_problem_document" to 1,
                        "student_problem_revision" to 1,
                        "student_problem_image_reference" to 1,
                        "student_mistake_save_receipt" to 1,
                        "student_capture_save_handoff" to 2,
                        "student_problem_error_occurrence" to 2,
                        "student_capture_occurrence_transaction" to 2,
                        "student_store_outbox" to 0,
                        "student_review_candidate" to 0,
                    )
                    assertEquals(
                        1,
                        database.distinctValueCount(
                            tableName = "student_capture_occurrence_transaction",
                            columnName = "identity_canonical_fingerprint",
                        ),
                    )
                    assertEquals(
                        1,
                        database.distinctValueCount(
                            tableName = "student_capture_occurrence_transaction",
                            columnName = "target_save_receipt_id",
                        ),
                    )
                }
            }
        }

    @Test
    fun changedDocumentForExactAssetFailsClosedThenReviewedCorrectionCanBeReused() =
        runBlocking {
            withCaptureFixture(CORRECTION_DATABASE_NAME) { fixture ->
                val assetFingerprint = fingerprint("same-exact-asset")
                val documentA =
                    capturedQuestionDocument(
                        documentId = "document-a",
                        sourceAssetId = "same-exact-asset",
                        markdown = "原始识别：解方程 x+1=3。",
                    )
                val documentB =
                    capturedQuestionDocument(
                        documentId = "document-b",
                        sourceAssetId = "same-exact-asset",
                        markdown = "审核修正：解方程 2x+1=3。",
                    )
                val first =
                    fixture.captures.save(
                        captureCommand(
                            intentId = "correction-original",
                            occurredAtEpochMillis = 3_000L,
                            document = documentA,
                            assetFingerprint = assetFingerprint,
                            identityEvidence =
                                StudentProblemIdentityEvidence.ExactAssetSelection,
                        ),
                    )
                val conflictingCommand =
                    captureCommand(
                        intentId = "correction-unreviewed",
                        occurredAtEpochMillis = 4_000L,
                        document = documentB,
                        assetFingerprint = assetFingerprint,
                        identityEvidence =
                            StudentProblemIdentityEvidence.ExactAssetSelection,
                    )

                val conflict = runCatching {
                    fixture.captures.save(conflictingCommand)
                }

                assertTrue(conflict.isFailure)
                assertTrue(
                    conflict.exceptionOrNull()
                        ?.message
                        .orEmpty()
                        .contains("internal alias/revision review is required"),
                )
                fixture.openReadOnlyDatabase().use { database ->
                    database.assertRowCounts(
                        "student_problem_canonical_identity" to 1,
                        "student_problem_canonical_source_binding" to 1,
                        "student_problem_revision" to 1,
                        "student_mistake_save_receipt" to 1,
                        "student_capture_save_handoff" to 1,
                        "student_problem_error_occurrence" to 1,
                        "student_capture_occurrence_transaction" to 1,
                    )
                    assertEquals(
                        0,
                        database.matchingRowCount(
                            tableName = "student_capture_occurrence_transaction",
                            columnName = "capture_intent_id",
                            value = conflictingCommand.capture.source.intentId,
                        ),
                    )
                    assertEquals(
                        0,
                        database.matchingRowCount(
                            tableName = "student_problem_error_occurrence",
                            columnName = "idempotency_key",
                            value = conflictingCommand.capture.source.intentId,
                        ),
                    )
                }

                val reviewedAliasCommand =
                    captureCommand(
                        intentId = "correction-reviewed",
                        occurredAtEpochMillis = 5_000L,
                        document = documentB,
                        assetFingerprint = assetFingerprint,
                        identityEvidence =
                            StudentProblemIdentityEvidence.Unresolved,
                    )
                val reviewDecisionFingerprint =
                    fingerprint("same-asset-document-b-review-decision")
                val reviewedAliasEvidenceFingerprint =
                    canonicalReviewedAliasEvidenceFingerprint(
                        existingIdentity = first.resolvedIdentity,
                        candidateCommand = reviewedAliasCommand,
                        reviewCaseId = "same-asset-correction-case",
                        reviewDecisionCanonicalFingerprint =
                            reviewDecisionFingerprint,
                    )
                val aliasProof =
                    fixture.issueReviewedAlias(
                        existingIdentity = first.resolvedIdentity,
                        candidateCommand = reviewedAliasCommand,
                        reviewCaseId = "same-asset-correction-case",
                        reviewDecisionCanonicalFingerprint =
                            reviewDecisionFingerprint,
                    )
                val reviewed =
                    fixture.captures.save(
                        reviewedAliasCommand.copy(
                            identityEvidence = aliasProof,
                        ),
                    )
                val approvedReplay =
                    fixture.captures.save(
                        captureCommand(
                            intentId = "correction-approved-reuse",
                            occurredAtEpochMillis = 6_000L,
                            document = documentB,
                            assetFingerprint = assetFingerprint,
                            identityEvidence =
                                StudentProblemIdentityEvidence.ExactAssetSelection,
                        ),
                    )

                assertEquals(first.resolvedIdentity, reviewed.resolvedIdentity)
                assertEquals(reviewed.resolvedIdentity, approvedReplay.resolvedIdentity)
                assertEquals(
                    first.save.handoff.targetProblem,
                    reviewed.save.handoff.targetProblem,
                )
                assertNotEquals(
                    first.save.handoff.targetRevision,
                    reviewed.save.handoff.targetRevision,
                )
                assertEquals(2, reviewed.save.handoff.targetRevision.revisionNumber)
                assertEquals(
                    reviewed.save.handoff.targetRevision,
                    approvedReplay.save.handoff.targetRevision,
                )
                fixture.openReadOnlyDatabase().use { database ->
                    database.assertRowCounts(
                        "student_problem_canonical_identity" to 1,
                        "student_problem_canonical_source_binding" to 2,
                        "student_problem_document" to 1,
                        "student_problem_revision" to 2,
                        "student_mistake_save_receipt" to 1,
                        "student_capture_save_handoff" to 3,
                        "student_problem_error_occurrence" to 3,
                        "student_capture_occurrence_transaction" to 3,
                        "student_store_outbox" to 0,
                        "student_review_candidate" to 0,
                    )
                    assertEquals(
                        reviewDecisionFingerprint,
                        database.singleText(
                            """
                            SELECT review_decision_canonical_fingerprint
                            FROM student_problem_canonical_source_binding
                            WHERE review_case_id = ?
                            """.trimIndent(),
                            arrayOf("same-asset-correction-case"),
                        ),
                    )
                    assertEquals(
                        "REVIEWED_ALIAS",
                        database.singleText(
                            """
                            SELECT evidence_kind
                            FROM student_problem_canonical_source_binding
                            WHERE review_case_id = ?
                            """.trimIndent(),
                            arrayOf("same-asset-correction-case"),
                        ),
                    )
                    assertEquals(
                        reviewedAliasEvidenceFingerprint,
                        database.singleText(
                            """
                            SELECT evidence_canonical_fingerprint
                            FROM student_problem_canonical_source_binding
                            WHERE review_case_id = ?
                            """.trimIndent(),
                            arrayOf("same-asset-correction-case"),
                        ),
                    )
                }
            }
        }

    @Test
    fun unresolvedExactCommandReplayReturnsSameResolutionWithoutDuplicateRows() =
        runBlocking {
            withCaptureFixture(REPLAY_DATABASE_NAME) { fixture ->
                val command =
                    captureCommand(
                        intentId = "unresolved-replay",
                        occurredAtEpochMillis = 7_000L,
                        document =
                            capturedQuestionDocument(
                                documentId = "replay-document",
                                sourceAssetId = "replay-asset",
                                markdown = "计算 3²。",
                            ),
                        assetFingerprint = fingerprint("replay-asset"),
                        identityEvidence =
                            StudentProblemIdentityEvidence.Unresolved,
                    )

                val created = fixture.captures.save(command)
                val replayed = fixture.captures.save(command)

                assertEquals(created, replayed)
                assertEquals(created.resolvedIdentity, replayed.resolvedIdentity)
                assertEquals(
                    created.save.handoff.targetRevision,
                    replayed.save.handoff.targetRevision,
                )
                assertNotEquals(
                    command.capture.target.problem.revision.problem.problemId,
                    created.save.handoff.targetProblem.problemId,
                )
                fixture.openReadOnlyDatabase().use { database ->
                    database.assertRowCounts(
                        "student_problem_canonical_identity" to 1,
                        "student_problem_canonical_source_binding" to 1,
                        "student_problem_document" to 1,
                        "student_problem_revision" to 1,
                        "student_mistake_save_receipt" to 1,
                        "student_capture_save_handoff" to 1,
                        "student_problem_error_occurrence" to 1,
                        "student_capture_occurrence_transaction" to 1,
                        "student_store_outbox" to 0,
                        "student_review_candidate" to 0,
                    )
                }
            }
        }

    @Test
    fun atomicCommandRejectsTamperedCapturedDocumentTitleAndStemProjection() {
        val valid =
            captureCommand(
                intentId = "tampered-projection",
                occurredAtEpochMillis = 9_500L,
                document =
                    capturedQuestionDocument(
                        documentId = "tampered-projection-document",
                        sourceAssetId = "tampered-projection-asset",
                        markdown = "原始结构化题干。",
                    ),
                assetFingerprint =
                    fingerprint("tampered-projection-asset"),
                identityEvidence =
                    StudentProblemIdentityEvidence.ExactAssetSelection,
            )
        val originalProblem = valid.capture.target.problem

        val tamperedTitle =
            runCatching {
                valid.copy(
                    capture =
                        valid.capture.copy(
                            target =
                                valid.capture.target.copy(
                                    problem =
                                        originalProblem.copy(
                                            title = "篡改标题",
                                        ),
                                ),
                        ),
                )
            }
        val tamperedStem =
            runCatching {
                valid.copy(
                    capture =
                        valid.capture.copy(
                            target =
                                valid.capture.target.copy(
                                    problem =
                                        originalProblem.copy(
                                            stemMarkdown = "篡改题干",
                                        ),
                                ),
                        ),
                )
            }

        assertTrue(tamperedTitle.isFailure)
        assertTrue(tamperedStem.isFailure)
    }

    @Test
    fun trustedSourceReceiptRotationConflictsAndPreservesExactAssetAdmission() =
        runBlocking {
            withCaptureFixture(TRUSTED_SOURCE_DATABASE_NAME) { fixture ->
                val document =
                    capturedQuestionDocument(
                        documentId = "trusted-source-document",
                        sourceAssetId = "trusted-source-asset",
                        markdown = "可信题源：证明相似三角形的对应边成比例。",
                    )
                val baseCommand =
                    captureCommand(
                        intentId = "trusted-source-intent",
                        occurredAtEpochMillis = 10_000L,
                        document = document,
                        assetFingerprint = fingerprint("trusted-source-asset"),
                        identityEvidence =
                            StudentProblemIdentityEvidence.Unresolved,
                    )
                val itemLocatorFingerprint =
                    fingerprint("trusted-source-item-locator")
                val proofEpochMillis = System.currentTimeMillis()
                val firstProof =
                    fixture.issueTrustedSource(
                        candidateCommand = baseCommand,
                        locatorNamespace = "trusted-question-bank",
                        locatorVersion = "2026.1",
                        itemLocatorCanonicalFingerprint =
                            itemLocatorFingerprint,
                        issuedAtEpochMillis = proofEpochMillis - 5_000L,
                    )
                val reissuedProof =
                    fixture.issueTrustedSource(
                        candidateCommand = baseCommand,
                        locatorNamespace = "trusted-question-bank",
                        locatorVersion = "2026.1",
                        itemLocatorCanonicalFingerprint =
                            itemLocatorFingerprint,
                        issuedAtEpochMillis = proofEpochMillis - 4_000L,
                    )
                val firstCommand =
                    baseCommand.copy(
                        identityEvidence = firstProof,
                    )
                val reissuedCommand =
                    baseCommand.copy(
                        identityEvidence = reissuedProof,
                    )

                assertNotEquals(
                    firstProof.proof.proofCanonicalFingerprint,
                    reissuedProof.proof.proofCanonicalFingerprint,
                )
                assertEquals(
                    firstProof.proof.itemLocatorCanonicalFingerprint,
                    reissuedProof.proof.itemLocatorCanonicalFingerprint,
                )
                assertNotEquals(
                    firstCommand.requestCanonicalFingerprint,
                    reissuedCommand.requestCanonicalFingerprint,
                )

                val created = fixture.captures.save(firstCommand)
                val replayed =
                    runCatching {
                        fixture.captures.save(reissuedCommand)
                    }

                assertTrue(replayed.isFailure)
                fixture.openReadOnlyDatabase().use { database ->
                    database.assertRowCounts(
                        "student_problem_canonical_identity" to 1,
                        "student_problem_canonical_source_binding" to 2,
                        "student_problem_document" to 1,
                        "student_problem_revision" to 1,
                        "student_mistake_save_receipt" to 1,
                        "student_capture_save_handoff" to 1,
                        "student_problem_error_occurrence" to 1,
                        "student_capture_occurrence_transaction" to 1,
                        "student_store_outbox" to 0,
                        "student_review_candidate" to 0,
                    )
                    assertEquals(
                        1,
                        database.matchingRowCount(
                            tableName =
                                "student_problem_canonical_source_binding",
                            columnName = "evidence_kind",
                            value = "TRUSTED_SOURCE",
                        ),
                    )
                    assertEquals(
                        1,
                        database.matchingRowCount(
                            tableName =
                                "student_problem_canonical_source_binding",
                            columnName = "evidence_kind",
                            value = "EXACT_ASSET_SELECTION",
                        ),
                    )
                }

                val exactAssetReceipt =
                    fixture.captures.save(
                        captureCommand(
                            intentId = "trusted-source-exact-reuse",
                            occurredAtEpochMillis = 11_000L,
                            document = document,
                            assetFingerprint =
                                fingerprint("trusted-source-asset"),
                            identityEvidence =
                                StudentProblemIdentityEvidence.ExactAssetSelection,
                        ),
                    )

                assertEquals(
                    created.resolvedIdentity,
                    exactAssetReceipt.resolvedIdentity,
                )
                assertEquals(
                    created.save.handoff.targetProblem,
                    exactAssetReceipt.save.handoff.targetProblem,
                )
                assertEquals(
                    created.save.handoff.targetRevision,
                    exactAssetReceipt.save.handoff.targetRevision,
                )
                fixture.openReadOnlyDatabase().use { database ->
                    database.assertRowCounts(
                        "student_problem_canonical_identity" to 1,
                        "student_problem_canonical_source_binding" to 2,
                        "student_problem_document" to 1,
                        "student_problem_revision" to 1,
                        "student_mistake_save_receipt" to 1,
                        "student_capture_save_handoff" to 2,
                        "student_problem_error_occurrence" to 2,
                        "student_capture_occurrence_transaction" to 2,
                        "student_store_outbox" to 0,
                        "student_review_candidate" to 0,
                    )
                }
            }
        }

    @Test
    fun zeroImageTrustedLocatorsStayDistinctWhileTheSameLocatorStillReuses() =
        runBlocking {
            withCaptureFixture(ZERO_IMAGE_TRUSTED_SOURCE_DATABASE_NAME) { fixture ->
                val document =
                    capturedQuestionDocument(
                        documentId = "zero-image-trusted-document",
                        sourceAssetId = "zero-image-document-evidence",
                        markdown = "可信题源无图片：计算 2x+3=9。",
                    )
                val firstCandidate =
                    captureCommand(
                        intentId = "zero-image-trusted-first",
                        occurredAtEpochMillis = 11_100L,
                        document = document,
                        assetFingerprint = fingerprint("unused-zero-image-first"),
                        identityEvidence = StudentProblemIdentityEvidence.Unresolved,
                        includeOriginalImage = false,
                    )
                val firstLocatorFingerprint =
                    fingerprint("zero-image-trusted-locator-a")
                val secondLocatorFingerprint =
                    fingerprint("zero-image-trusted-locator-b")
                val firstCommand =
                    firstCandidate.copy(
                        identityEvidence =
                            fixture.issueTrustedSource(
                                candidateCommand = firstCandidate,
                                locatorNamespace = "zero-image-question-bank",
                                locatorVersion = "2026.1",
                                itemLocatorCanonicalFingerprint =
                                    firstLocatorFingerprint,
                            ),
                    )

                assertTrue(
                    firstCommand.capture.target.problem.originalImages.isEmpty(),
                )
                val first = fixture.captures.save(firstCommand)
                assertEquals(first, fixture.captures.save(firstCommand))

                val sameLocatorCandidate =
                    captureCommand(
                        intentId = "zero-image-trusted-same-locator",
                        occurredAtEpochMillis = 11_200L,
                        document = document,
                        assetFingerprint = fingerprint("unused-zero-image-reuse"),
                        identityEvidence = StudentProblemIdentityEvidence.Unresolved,
                        includeOriginalImage = false,
                    )
                val sameLocator =
                    fixture.captures.save(
                        sameLocatorCandidate.copy(
                            identityEvidence =
                                fixture.issueTrustedSource(
                                    candidateCommand = sameLocatorCandidate,
                                    locatorNamespace = "zero-image-question-bank",
                                    locatorVersion = "2026.1",
                                    itemLocatorCanonicalFingerprint =
                                        firstLocatorFingerprint,
                                ),
                        ),
                    )

                assertEquals(first.resolvedIdentity, sameLocator.resolvedIdentity)
                assertEquals(
                    first.save.handoff.targetProblem,
                    sameLocator.save.handoff.targetProblem,
                )
                assertEquals(
                    first.save.handoff.targetRevision,
                    sameLocator.save.handoff.targetRevision,
                )

                val distinctLocatorCandidate =
                    captureCommand(
                        intentId = "zero-image-trusted-distinct-locator",
                        occurredAtEpochMillis = 11_300L,
                        document = document,
                        assetFingerprint = fingerprint("unused-zero-image-distinct"),
                        identityEvidence = StudentProblemIdentityEvidence.Unresolved,
                        includeOriginalImage = false,
                    )
                val distinctLocator =
                    fixture.captures.save(
                        distinctLocatorCandidate.copy(
                            identityEvidence =
                                fixture.issueTrustedSource(
                                    candidateCommand = distinctLocatorCandidate,
                                    locatorNamespace = "zero-image-question-bank",
                                    locatorVersion = "2026.1",
                                    itemLocatorCanonicalFingerprint =
                                        secondLocatorFingerprint,
                                ),
                        ),
                    )

                assertNotEquals(
                    first.resolvedIdentity,
                    distinctLocator.resolvedIdentity,
                )
                assertNotEquals(
                    first.save.handoff.targetProblem,
                    distinctLocator.save.handoff.targetProblem,
                )
                fixture.openReadOnlyDatabase().use { database ->
                    database.assertRowCounts(
                        "student_problem_canonical_identity" to 2,
                        "student_problem_canonical_source_binding" to 2,
                        "student_problem_document" to 2,
                        "student_problem_revision" to 2,
                        "student_problem_image_reference" to 0,
                        "student_mistake_save_receipt" to 2,
                        "student_capture_save_handoff" to 3,
                        "student_problem_error_occurrence" to 3,
                        "student_capture_occurrence_transaction" to 3,
                        "student_store_outbox" to 0,
                        "student_review_candidate" to 0,
                    )
                    assertEquals(
                        2,
                        database.matchingRowCount(
                            tableName =
                                "student_problem_canonical_source_binding",
                            columnName = "evidence_kind",
                            value = "TRUSTED_SOURCE",
                        ),
                    )
                    assertEquals(
                        0,
                        database.matchingRowCount(
                            tableName =
                                "student_problem_canonical_source_binding",
                            columnName = "evidence_kind",
                            value = "EXACT_ASSET_SELECTION",
                        ),
                    )
                }
            }
        }

    @Test
    fun crossKindBindingOrderPreservesDocumentSpecificRevisionResolution() =
        runBlocking {
            withCaptureFixture(CROSS_KIND_ORDER_DATABASE_NAME) { fixture ->
                val assetFingerprint = fingerprint("cross-kind-shared-asset")
                val documentOne =
                    capturedQuestionDocument(
                        documentId = "cross-kind-document-1",
                        sourceAssetId = "cross-kind-shared-asset",
                        markdown = "文档一：求直线斜率。",
                    )
                val documentTwo =
                    capturedQuestionDocument(
                        documentId = "cross-kind-document-2",
                        sourceAssetId = "cross-kind-shared-asset",
                        markdown = "文档二：求直线斜率并写出截距。",
                    )
                val revisionOne =
                    fixture.captures.save(
                        captureCommand(
                            intentId = "cross-kind-exact-doc-1",
                            occurredAtEpochMillis = 20_000L,
                            document = documentOne,
                            assetFingerprint = assetFingerprint,
                            identityEvidence =
                                StudentProblemIdentityEvidence.ExactAssetSelection,
                        ),
                    )
                val aliasDocumentTwoCommand =
                    captureCommand(
                        intentId = "cross-kind-alias-doc-2",
                        occurredAtEpochMillis = 21_000L,
                        document = documentTwo,
                        assetFingerprint = assetFingerprint,
                        identityEvidence =
                            StudentProblemIdentityEvidence.Unresolved,
                    )
                val aliasProof =
                    fixture.issueReviewedAlias(
                        existingIdentity = revisionOne.resolvedIdentity,
                        candidateCommand = aliasDocumentTwoCommand,
                        reviewCaseId = "cross-kind-doc-2-alias-case",
                        reviewDecisionCanonicalFingerprint =
                            fingerprint("cross-kind-doc-2-alias-decision"),
                    )
                val revisionTwo =
                    fixture.captures.save(
                        aliasDocumentTwoCommand.copy(
                            identityEvidence = aliasProof,
                        ),
                    )
                val trustedDocumentTwoCommand =
                    captureCommand(
                        intentId = "cross-kind-trusted-doc-2",
                        occurredAtEpochMillis = 22_000L,
                        document = documentTwo,
                        assetFingerprint = assetFingerprint,
                        identityEvidence =
                            StudentProblemIdentityEvidence.Unresolved,
                    )
                val trustedProof =
                    fixture.issueTrustedSource(
                        candidateCommand = trustedDocumentTwoCommand,
                        locatorNamespace = "cross-kind-question-bank",
                        locatorVersion = "2026.1",
                        itemLocatorCanonicalFingerprint =
                            fingerprint("cross-kind-doc-2-locator"),
                    )
                val trustedDocumentTwo =
                    fixture.captures.save(
                        trustedDocumentTwoCommand.copy(
                            identityEvidence = trustedProof,
                        ),
                    )

                assertEquals(1, revisionOne.save.handoff.targetRevision.revisionNumber)
                assertEquals(2, revisionTwo.save.handoff.targetRevision.revisionNumber)
                assertEquals(
                    revisionTwo.save.handoff.targetRevision,
                    trustedDocumentTwo.save.handoff.targetRevision,
                )
                assertEquals(
                    revisionOne.resolvedIdentity,
                    trustedDocumentTwo.resolvedIdentity,
                )

                val laterDocumentOne =
                    fixture.captures.save(
                        captureCommand(
                            intentId = "cross-kind-later-exact-doc-1",
                            occurredAtEpochMillis = 23_000L,
                            document = documentOne,
                            assetFingerprint = assetFingerprint,
                            identityEvidence =
                                StudentProblemIdentityEvidence.ExactAssetSelection,
                        ),
                    )
                val laterDocumentTwo =
                    fixture.captures.save(
                        captureCommand(
                            intentId = "cross-kind-later-exact-doc-2",
                            occurredAtEpochMillis = 24_000L,
                            document = documentTwo,
                            assetFingerprint = assetFingerprint,
                            identityEvidence =
                                StudentProblemIdentityEvidence.ExactAssetSelection,
                        ),
                    )

                assertEquals(
                    revisionOne.save.handoff.targetRevision,
                    laterDocumentOne.save.handoff.targetRevision,
                )
                assertEquals(
                    revisionTwo.save.handoff.targetRevision,
                    laterDocumentTwo.save.handoff.targetRevision,
                )
                assertEquals(
                    revisionOne.resolvedIdentity,
                    laterDocumentOne.resolvedIdentity,
                )
                assertEquals(
                    revisionOne.resolvedIdentity,
                    laterDocumentTwo.resolvedIdentity,
                )
                fixture.openReadOnlyDatabase().use { database ->
                    database.assertRowCounts(
                        "student_problem_canonical_identity" to 1,
                        "student_problem_canonical_source_binding" to 3,
                        "student_problem_document" to 1,
                        "student_problem_revision" to 2,
                        "student_mistake_save_receipt" to 1,
                        "student_capture_save_handoff" to 5,
                        "student_problem_error_occurrence" to 5,
                        "student_capture_occurrence_transaction" to 5,
                        "student_store_outbox" to 0,
                        "student_review_candidate" to 0,
                    )
                    assertEquals(
                        1,
                        database.matchingRowCount(
                            tableName =
                                "student_problem_canonical_source_binding",
                            columnName = "evidence_kind",
                            value = "EXACT_ASSET_SELECTION",
                        ),
                    )
                    assertEquals(
                        1,
                        database.matchingRowCount(
                            tableName =
                                "student_problem_canonical_source_binding",
                            columnName = "evidence_kind",
                            value = "REVIEWED_ALIAS",
                        ),
                    )
                    assertEquals(
                        1,
                        database.matchingRowCount(
                            tableName =
                                "student_problem_canonical_source_binding",
                            columnName = "evidence_kind",
                            value = "TRUSTED_SOURCE",
                        ),
                    )
                }
            }
        }

    @Test
    fun reviewedAliasCreatesSecondRevisionUnderStableIdentityWithoutMasteryOrReviewWrites() =
        runBlocking {
            withCaptureFixture(ALIAS_DATABASE_NAME) { fixture ->
                val firstCommand =
                    captureCommand(
                        intentId = "alias-original",
                        occurredAtEpochMillis = 8_000L,
                        document =
                            capturedQuestionDocument(
                                documentId = "alias-document-a",
                                sourceAssetId = "alias-asset-a",
                                markdown = "由资产 A 采集：求 1+1。",
                            ),
                        assetFingerprint = fingerprint("alias-asset-a"),
                        identityEvidence =
                            StudentProblemIdentityEvidence.ExactAssetSelection,
                    )
                val first = fixture.captures.save(firstCommand)
                fixture.store.setProblemLifecycle(
                    SetStudentProblemLifecycleCommand(
                        problem = first.save.handoff.targetProblem,
                        state = StudentProblemLifecycleState.ARCHIVED,
                        changedAtEpochMillis = 8_500L,
                    ),
                )
                val (problemBeforeAlias, practiceBeforeAlias) =
                    fixture.openReadOnlyDatabase().use { database ->
                        database.readProblemState(
                            first.save.handoff.targetProblem.problemId,
                        ) to
                            database.readPracticeState(
                                first.save.handoff.targetProblem.practiceUnitId,
                            )
                    }
                assertEquals(
                    StudentProblemLifecycleState.ARCHIVED.name,
                    problemBeforeAlias.lifecycleState,
                )
                assertEquals(
                    8_500L,
                    problemBeforeAlias.archivedAtEpochMillis,
                )
                val aliasCandidate =
                    captureCommand(
                        intentId = "alias-reviewed",
                        occurredAtEpochMillis = 9_000L,
                        document =
                            capturedQuestionDocument(
                                documentId = "alias-document-b",
                                sourceAssetId = "alias-asset-b",
                                markdown = "由资产 B 复核：求 1+1，并写出过程。",
                            ),
                        assetFingerprint = fingerprint("alias-asset-b"),
                        identityEvidence =
                            StudentProblemIdentityEvidence.Unresolved,
                        practiceUnitKind =
                            StudentPracticeUnitKind.PROBLEM_PART,
                        practiceUnitTitle = "caller-tampered-title",
                        itemFamilyId = "caller-tampered-family",
                        estimatedDurationSeconds = 777,
                        sourceBundleId = "caller-tampered-source-bundle",
                        partIds = listOf("caller-tampered-part"),
                    )
                val callerPreparedTarget =
                    fixture.store.prepareTargetConfirmedMistakeBundle(
                        aliasCandidate.capture.target,
                    )

                assertEquals(
                    StudentProblemLifecycleState.ACTIVE.name,
                    callerPreparedTarget.problem.problem.lifecycleState,
                )
                assertEquals(
                    null,
                    callerPreparedTarget.problem.problem.archivedAtEpochMillis,
                )
                assertEquals(
                    null,
                    callerPreparedTarget.problem.problem.tombstonedAtEpochMillis,
                )
                assertEquals(
                    StudentPracticeUnitKind.PROBLEM_PART.name,
                    callerPreparedTarget.problem.practiceUnit.unitKind,
                )
                assertEquals(
                    "caller-tampered-title",
                    callerPreparedTarget.problem.practiceUnit.title,
                )
                assertEquals(
                    "caller-tampered-family",
                    callerPreparedTarget.problem.practiceUnit.itemFamilyId,
                )
                assertEquals(
                    777,
                    callerPreparedTarget.problem.practiceUnit
                        .estimatedDurationSeconds,
                )
                assertEquals(
                    "caller-tampered-source-bundle",
                    callerPreparedTarget.problem.practiceUnit.sourceBundleId,
                )
                assertNotEquals(
                    practiceBeforeAlias.partIdsWire,
                    callerPreparedTarget.problem.practiceUnit.partIdsWire,
                )

                assertNotEquals(
                    studentCaptureAssetManifestCanonicalFingerprint(
                        firstCommand.capture.target.problem.originalImages,
                    ),
                    studentCaptureAssetManifestCanonicalFingerprint(
                        aliasCandidate.capture.target.problem.originalImages,
                    ),
                )
                assertNotEquals(
                    firstCommand.capture.target.problem.revision
                        .documentCanonicalFingerprint,
                    aliasCandidate.capture.target.problem.revision
                        .documentCanonicalFingerprint,
                )
                val reviewDecisionFingerprint =
                    fingerprint("different-asset-alias-review-decision")
                val reviewedAliasEvidenceFingerprint =
                    canonicalReviewedAliasEvidenceFingerprint(
                        existingIdentity = first.resolvedIdentity,
                        candidateCommand = aliasCandidate,
                        reviewCaseId = "different-asset-alias-case",
                        reviewDecisionCanonicalFingerprint =
                            reviewDecisionFingerprint,
                    )
                val proof =
                    fixture.issueReviewedAlias(
                        existingIdentity = first.resolvedIdentity,
                        candidateCommand = aliasCandidate,
                        reviewCaseId = "different-asset-alias-case",
                        reviewDecisionCanonicalFingerprint =
                            reviewDecisionFingerprint,
                    )
                val aliased =
                    fixture.captures.save(
                        aliasCandidate.copy(
                            identityEvidence = proof,
                        ),
                    )

                assertEquals(first.resolvedIdentity, aliased.resolvedIdentity)
                assertEquals(
                    first.save.handoff.targetProblem,
                    aliased.save.handoff.targetProblem,
                )
                assertNotEquals(
                    first.save.handoff.targetRevision,
                    aliased.save.handoff.targetRevision,
                )
                assertEquals(1, first.save.handoff.targetRevision.revisionNumber)
                assertEquals(2, aliased.save.handoff.targetRevision.revisionNumber)
                assertEquals(
                    TargetConfirmedStudentMistakeSaveOutcome.DUPLICATE,
                    aliased.save.outcome,
                )
                fixture.openReadOnlyDatabase().use { database ->
                    database.assertRowCounts(
                        "student_problem_canonical_identity" to 1,
                        "student_problem_canonical_source_binding" to 2,
                        "student_problem_document" to 1,
                        "student_problem_revision" to 2,
                        "student_mistake_save_receipt" to 1,
                        "student_capture_save_handoff" to 2,
                        "student_problem_error_occurrence" to 2,
                        "student_capture_occurrence_transaction" to 2,
                        // Archive publishes one lifecycle change plus one binding snapshot;
                        // the reviewed alias revision itself adds no mastery event.
                        "student_store_outbox" to 2,
                        "student_review_candidate" to 0,
                    )
                    assertEquals(
                        1,
                        database.distinctValueCount(
                            tableName = "student_capture_occurrence_transaction",
                            columnName = "target_save_receipt_id",
                        ),
                    )
                    assertEquals(
                        reviewDecisionFingerprint,
                        database.singleText(
                            """
                            SELECT review_decision_canonical_fingerprint
                            FROM student_problem_canonical_source_binding
                            WHERE review_case_id = ?
                            """.trimIndent(),
                            arrayOf("different-asset-alias-case"),
                        ),
                    )
                    assertEquals(
                        "REVIEWED_ALIAS",
                        database.singleText(
                            """
                            SELECT evidence_kind
                            FROM student_problem_canonical_source_binding
                            WHERE review_case_id = ?
                            """.trimIndent(),
                            arrayOf("different-asset-alias-case"),
                        ),
                    )
                    assertEquals(
                        reviewedAliasEvidenceFingerprint,
                        database.singleText(
                            """
                            SELECT evidence_canonical_fingerprint
                            FROM student_problem_canonical_source_binding
                            WHERE review_case_id = ?
                            """.trimIndent(),
                            arrayOf("different-asset-alias-case"),
                        ),
                    )
                    val problemAfterAlias =
                        database.readProblemState(
                            aliased.save.handoff.targetProblem.problemId,
                        )
                    val practiceAfterAlias =
                        database.readPracticeState(
                            aliased.save.handoff.targetProblem.practiceUnitId,
                        )
                    assertEquals(
                        problemBeforeAlias.lifecycleState,
                        problemAfterAlias.lifecycleState,
                    )
                    assertEquals(
                        problemBeforeAlias.archivedAtEpochMillis,
                        problemAfterAlias.archivedAtEpochMillis,
                    )
                    assertEquals(
                        problemBeforeAlias.tombstonedAtEpochMillis,
                        problemAfterAlias.tombstonedAtEpochMillis,
                    )
                    assertEquals(
                        problemBeforeAlias.createdAtEpochMillis,
                        problemAfterAlias.createdAtEpochMillis,
                    )
                    assertEquals(
                        aliased.save.handoff.targetRevision.revisionId,
                        problemAfterAlias.currentRevisionId,
                    )
                    assertTrue(
                        problemAfterAlias.updatedAtEpochMillis >=
                            problemBeforeAlias.updatedAtEpochMillis,
                    )
                    assertEquals(
                        practiceBeforeAlias.unitKind,
                        practiceAfterAlias.unitKind,
                    )
                    assertEquals(
                        practiceBeforeAlias.title,
                        practiceAfterAlias.title,
                    )
                    assertEquals(
                        practiceBeforeAlias.itemFamilyId,
                        practiceAfterAlias.itemFamilyId,
                    )
                    assertEquals(
                        practiceBeforeAlias.estimatedDurationSeconds,
                        practiceAfterAlias.estimatedDurationSeconds,
                    )
                    assertEquals(
                        practiceBeforeAlias.sourceBundleId,
                        practiceAfterAlias.sourceBundleId,
                    )
                    assertEquals(
                        practiceBeforeAlias.partIdsWire,
                        practiceAfterAlias.partIdsWire,
                    )
                    assertEquals(
                        practiceBeforeAlias.createdAtEpochMillis,
                        practiceAfterAlias.createdAtEpochMillis,
                    )
                    assertEquals(
                        aliased.save.handoff.targetRevision.revisionId,
                        practiceAfterAlias.basisRevisionId,
                    )
                    assertTrue(
                        practiceAfterAlias.updatedAtEpochMillis >=
                            practiceBeforeAlias.updatedAtEpochMillis,
                    )
                }
            }
        }

    @Test
    fun reviewedAliasReceiptRotationAndSemanticReviewChangesConflict() =
        runBlocking {
            withCaptureFixture(ALIAS_REPLAY_DATABASE_NAME) { fixture ->
                val first =
                    fixture.captures.save(
                        captureCommand(
                            intentId = "alias-replay-original",
                            occurredAtEpochMillis = 12_000L,
                            document =
                                capturedQuestionDocument(
                                    documentId = "alias-replay-document-a",
                                    sourceAssetId = "alias-replay-asset-a",
                                    markdown = "原题：求等差数列的通项。",
                                ),
                            assetFingerprint =
                                fingerprint("alias-replay-asset-a"),
                            identityEvidence =
                                StudentProblemIdentityEvidence.ExactAssetSelection,
                        ),
                    )
                val aliasCandidate =
                    captureCommand(
                        intentId = "alias-replay-reviewed",
                        occurredAtEpochMillis = 13_000L,
                        document =
                            capturedQuestionDocument(
                                documentId = "alias-replay-document-b",
                                sourceAssetId = "alias-replay-asset-b",
                                markdown = "复核题：求等差数列的通项并验证。",
                            ),
                        assetFingerprint =
                            fingerprint("alias-replay-asset-b"),
                        identityEvidence =
                            StudentProblemIdentityEvidence.Unresolved,
                    )
                val decisionFingerprint =
                    fingerprint("stable-alias-review-decision")
                val proofEpochMillis = System.currentTimeMillis()
                val firstProof =
                    fixture.issueReviewedAlias(
                        existingIdentity = first.resolvedIdentity,
                        candidateCommand = aliasCandidate,
                        reviewCaseId = "alias-bearer-replay-case",
                        reviewRevision = 1,
                        reviewDecisionCanonicalFingerprint =
                            decisionFingerprint,
                        issuedAtEpochMillis = proofEpochMillis - 5_000L,
                    )
                val reissuedProof =
                    fixture.issueReviewedAlias(
                        existingIdentity = first.resolvedIdentity,
                        candidateCommand = aliasCandidate,
                        reviewCaseId = "alias-bearer-replay-case",
                        reviewRevision = 1,
                        reviewDecisionCanonicalFingerprint =
                            decisionFingerprint,
                        issuedAtEpochMillis = proofEpochMillis - 4_000L,
                    )
                val firstAliasCommand =
                    aliasCandidate.copy(
                        identityEvidence = firstProof,
                    )
                val reissuedAliasCommand =
                    aliasCandidate.copy(
                        identityEvidence = reissuedProof,
                    )

                assertNotEquals(
                    firstProof.proof.proofCanonicalFingerprint,
                    reissuedProof.proof.proofCanonicalFingerprint,
                )
                assertNotEquals(
                    firstAliasCommand.requestCanonicalFingerprint,
                    reissuedAliasCommand.requestCanonicalFingerprint,
                )

                val created = fixture.captures.save(firstAliasCommand)
                val replayed =
                    runCatching {
                        fixture.captures.save(reissuedAliasCommand)
                    }

                assertTrue(replayed.isFailure)
                assertEquals(2, created.save.handoff.targetRevision.revisionNumber)
                val baseline =
                    fixture.openReadOnlyDatabase().use { database ->
                        database.rowCounts(ATOMIC_CAPTURE_WRITE_TABLES)
                    }

                val changedRevisionProof =
                    fixture.issueReviewedAlias(
                        existingIdentity = first.resolvedIdentity,
                        candidateCommand = aliasCandidate,
                        reviewCaseId = "alias-bearer-replay-case",
                        reviewRevision = 2,
                        reviewDecisionCanonicalFingerprint =
                            decisionFingerprint,
                        issuedAtEpochMillis = proofEpochMillis - 3_000L,
                    )
                val changedRevisionResult =
                    runCatching {
                        fixture.captures.save(
                            aliasCandidate.copy(
                                identityEvidence = changedRevisionProof,
                            ),
                        )
                    }

                assertTrue(changedRevisionResult.isFailure)
                fixture.openReadOnlyDatabase().use { database ->
                    assertEquals(
                        baseline,
                        database.rowCounts(ATOMIC_CAPTURE_WRITE_TABLES),
                    )
                }

                val changedDecisionProof =
                    fixture.issueReviewedAlias(
                        existingIdentity = first.resolvedIdentity,
                        candidateCommand = aliasCandidate,
                        reviewCaseId = "alias-bearer-replay-case",
                        reviewRevision = 1,
                        reviewDecisionCanonicalFingerprint =
                            fingerprint("changed-alias-review-decision"),
                        issuedAtEpochMillis = proofEpochMillis - 2_000L,
                    )
                val changedDecisionResult =
                    runCatching {
                        fixture.captures.save(
                            aliasCandidate.copy(
                                identityEvidence = changedDecisionProof,
                            ),
                        )
                    }

                assertTrue(changedDecisionResult.isFailure)
                fixture.openReadOnlyDatabase().use { database ->
                    assertEquals(
                        baseline,
                        database.rowCounts(ATOMIC_CAPTURE_WRITE_TABLES),
                    )
                    database.assertRowCounts(
                        "student_problem_canonical_identity" to 1,
                        "student_problem_canonical_source_binding" to 2,
                        "student_problem_document" to 1,
                        "student_problem_revision" to 2,
                        "student_mistake_save_receipt" to 1,
                        "student_capture_save_handoff" to 2,
                        "student_problem_error_occurrence" to 2,
                        "student_capture_occurrence_transaction" to 2,
                        "student_store_outbox" to 0,
                        "student_review_candidate" to 0,
                    )
                }
            }
        }

    @Test
    fun reviewedAliasCannotContradictMaterializedExactAssetIdentity() =
        runBlocking {
            withCaptureFixture(ALIAS_CONTRADICTION_DATABASE_NAME) { fixture ->
                val identityI =
                    fixture.captures.save(
                        captureCommand(
                            intentId = "identity-i-original",
                            occurredAtEpochMillis = 14_000L,
                            document =
                                capturedQuestionDocument(
                                    documentId = "identity-i-document",
                                    sourceAssetId = "identity-i-asset",
                                    markdown = "身份 I 原题：计算圆的面积。",
                                ),
                            assetFingerprint =
                                fingerprint("identity-i-asset"),
                            identityEvidence =
                                StudentProblemIdentityEvidence.ExactAssetSelection,
                        ),
                    )
                val aliasedDocument =
                    capturedQuestionDocument(
                        documentId = "shared-alias-document",
                        sourceAssetId = "shared-alias-asset",
                        markdown = "审核别名题：已知半径，计算圆的面积。",
                    )
                val aliasToICommand =
                    captureCommand(
                        intentId = "alias-assets-to-i",
                        occurredAtEpochMillis = 15_000L,
                        document = aliasedDocument,
                        assetFingerprint = fingerprint("shared-alias-asset"),
                        identityEvidence =
                            StudentProblemIdentityEvidence.Unresolved,
                    )
                val aliasToIProof =
                    fixture.issueReviewedAlias(
                        existingIdentity = identityI.resolvedIdentity,
                        candidateCommand = aliasToICommand,
                        reviewCaseId = "alias-assets-to-i-case",
                        reviewDecisionCanonicalFingerprint =
                            fingerprint("alias-assets-to-i-decision"),
                    )
                val aliasedToI =
                    fixture.captures.save(
                        aliasToICommand.copy(
                            identityEvidence = aliasToIProof,
                        ),
                    )
                val exactMaterializationCommand =
                    captureCommand(
                        intentId = "materialize-exact-assets-to-i",
                        occurredAtEpochMillis = 16_000L,
                        document = aliasedDocument,
                        assetFingerprint = fingerprint("shared-alias-asset"),
                        identityEvidence =
                            StudentProblemIdentityEvidence.ExactAssetSelection,
                    )
                val exactMaterialized =
                    fixture.captures.save(exactMaterializationCommand)

                assertEquals(
                    identityI.resolvedIdentity,
                    exactMaterialized.resolvedIdentity,
                )
                assertEquals(
                    aliasedToI.save.handoff.targetRevision,
                    exactMaterialized.save.handoff.targetRevision,
                )
                val exactEvidenceFingerprint =
                    canonicalStudentProblemIdentityEvidenceFingerprint(
                        evidenceKind =
                            StudentProblemIdentityEvidenceKind
                                .EXACT_ASSET_SELECTION,
                        transactionId =
                            exactMaterializationCommand.transactionId,
                        assetManifestCanonicalFingerprint =
                            studentCaptureAssetManifestCanonicalFingerprint(
                                exactMaterializationCommand.capture.target.problem
                                    .originalImages,
                            ),
                        selectedRegionCanonicalFingerprint =
                            studentCaptureSelectedRegionCanonicalFingerprint(
                                exactMaterializationCommand.capture.target.problem
                                    .originalImages,
                            ),
                    )
                fixture.openReadOnlyDatabase().use { database ->
                    assertEquals(
                        identityI.resolvedIdentity.stableKey,
                        database.singleText(
                            """
                            SELECT identity_stable_key
                            FROM student_problem_canonical_source_binding
                            WHERE evidence_kind = ?
                              AND evidence_canonical_fingerprint = ?
                            """.trimIndent(),
                            arrayOf(
                                "EXACT_ASSET_SELECTION",
                                exactEvidenceFingerprint,
                            ),
                        ),
                    )
                }

                val identityJ =
                    fixture.captures.save(
                        captureCommand(
                            intentId = "identity-j-fresh",
                            occurredAtEpochMillis = 17_000L,
                            document =
                                capturedQuestionDocument(
                                    documentId = "identity-j-document",
                                    sourceAssetId = "identity-j-asset",
                                    markdown = "身份 J：解一元二次方程。",
                                ),
                            assetFingerprint =
                                fingerprint("identity-j-asset"),
                            identityEvidence =
                                StudentProblemIdentityEvidence.Unresolved,
                        ),
                    )
                assertNotEquals(
                    identityI.resolvedIdentity,
                    identityJ.resolvedIdentity,
                )
                val contradictionCommand =
                    captureCommand(
                        intentId = "contradict-assets-to-j",
                        occurredAtEpochMillis = 18_000L,
                        document = aliasedDocument,
                        assetFingerprint = fingerprint("shared-alias-asset"),
                        identityEvidence =
                            StudentProblemIdentityEvidence.Unresolved,
                    )
                val contradictionProof =
                    fixture.issueReviewedAlias(
                        existingIdentity = identityJ.resolvedIdentity,
                        candidateCommand = contradictionCommand,
                        reviewCaseId = "contradict-assets-to-j-case",
                        reviewDecisionCanonicalFingerprint =
                            fingerprint("contradict-assets-to-j-decision"),
                    )
                val baseline =
                    fixture.openReadOnlyDatabase().use { database ->
                        database.rowCounts(ATOMIC_CAPTURE_WRITE_TABLES)
                    }

                val contradiction =
                    runCatching {
                        fixture.captures.save(
                            contradictionCommand.copy(
                                identityEvidence = contradictionProof,
                            ),
                        )
                    }

                assertTrue(contradiction.isFailure)
                fixture.openReadOnlyDatabase().use { database ->
                    assertEquals(
                        baseline,
                        database.rowCounts(ATOMIC_CAPTURE_WRITE_TABLES),
                    )
                }

                val laterExact =
                    fixture.captures.save(
                        captureCommand(
                            intentId = "exact-assets-still-resolve-i",
                            occurredAtEpochMillis = 19_000L,
                            document = aliasedDocument,
                            assetFingerprint =
                                fingerprint("shared-alias-asset"),
                            identityEvidence =
                                StudentProblemIdentityEvidence.ExactAssetSelection,
                        ),
                    )
                assertEquals(
                    identityI.resolvedIdentity,
                    laterExact.resolvedIdentity,
                )
                assertNotEquals(
                    identityJ.resolvedIdentity,
                    laterExact.resolvedIdentity,
                )
                assertEquals(
                    aliasedToI.save.handoff.targetRevision,
                    laterExact.save.handoff.targetRevision,
                )
                fixture.openReadOnlyDatabase().use { database ->
                    database.assertRowCounts(
                        "student_store_outbox" to 0,
                        "student_review_candidate" to 0,
                    )
                }
            }
        }

    @Test
    fun lateTransactionReceiptAbortRollsBackEntireCanonicalCapture() =
        runBlocking {
            withCaptureFixture(LATE_ABORT_DATABASE_NAME) { fixture ->
                val command =
                    captureCommand(
                        intentId = "late-abort-intent",
                        occurredAtEpochMillis = 14_000L,
                        document =
                            capturedQuestionDocument(
                                documentId = "late-abort-document",
                                sourceAssetId = "late-abort-asset",
                                markdown = "事务回滚题：计算 6×7。",
                            ),
                        assetFingerprint = fingerprint("late-abort-asset"),
                        identityEvidence =
                            StudentProblemIdentityEvidence.ExactAssetSelection,
                    )
                assertEquals(
                    null,
                    fixture.store.findProblem(
                        command.capture.target.problem.revision.problem,
                    ),
                )
                val baseline =
                    fixture.openReadOnlyDatabase().use { database ->
                        database.rowCounts(ATOMIC_CAPTURE_WRITE_TABLES)
                    }

                try {
                    fixture.openReadWriteDatabase().use { database ->
                        database.execSQL(
                            """
                            CREATE TRIGGER `$LATE_ABORT_TRIGGER_NAME`
                            BEFORE INSERT ON student_capture_occurrence_transaction
                            BEGIN
                                SELECT RAISE(
                                    ABORT,
                                    'forced late atomic capture failure'
                                );
                            END
                            """.trimIndent(),
                        )
                    }

                    val failed = runCatching {
                        fixture.captures.save(command)
                    }

                    assertTrue(failed.isFailure)
                    fixture.openReadOnlyDatabase().use { database ->
                        assertEquals(
                            baseline,
                            database.rowCounts(ATOMIC_CAPTURE_WRITE_TABLES),
                        )
                    }
                } finally {
                    fixture.openReadWriteDatabase().use { database ->
                        database.execSQL(
                            "DROP TRIGGER IF EXISTS `$LATE_ABORT_TRIGGER_NAME`",
                        )
                    }
                }

                fixture.openReadOnlyDatabase().use { database ->
                    assertEquals(
                        0,
                        database.matchingRowCount(
                            tableName = "sqlite_master",
                            columnName = "name",
                            value = LATE_ABORT_TRIGGER_NAME,
                        ),
                    )
                    assertEquals(
                        baseline,
                        database.rowCounts(ATOMIC_CAPTURE_WRITE_TABLES),
                    )
                }
            }
        }

    @Test
    fun concurrentFirstWritesAndFailedRetryConvergeOnOneCanonicalProblem() =
        runBlocking {
            withCaptureFixture(CONCURRENT_CAPTURE_DATABASE_NAME) { fixture ->
                val document =
                    capturedQuestionDocument(
                        documentId = "concurrent-capture-document",
                        sourceAssetId = "concurrent-capture-asset",
                        markdown = "并发收敛题：计算 9×9。",
                    )
                val assetFingerprint = fingerprint("concurrent-capture-asset")
                val commands =
                    List(CONCURRENT_CAPTURE_COUNT) { index ->
                        val intentId =
                            if (index == 0) {
                                CONCURRENT_RETRY_INTENT_ID
                            } else {
                                "concurrent-first-write-$index"
                            }
                        captureCommand(
                            intentId = intentId,
                            occurredAtEpochMillis = 15_000L + index,
                            document = document,
                            assetFingerprint = assetFingerprint,
                            identityEvidence =
                                StudentProblemIdentityEvidence.ExactAssetSelection,
                        )
                    }
                fixture.openReadWriteDatabase().use { database ->
                    database.execSQL(
                        """
                        CREATE TRIGGER `$CONCURRENT_ABORT_TRIGGER_NAME`
                        BEFORE INSERT ON student_capture_occurrence_transaction
                        WHEN NEW.capture_intent_id = '$CONCURRENT_RETRY_INTENT_ID'
                        BEGIN
                            SELECT RAISE(
                                ABORT,
                                'forced concurrent retry failure'
                            );
                        END
                        """.trimIndent(),
                    )
                }

                val firstWave =
                    try {
                        coroutineScope {
                            val start = CompletableDeferred<Unit>()
                            val readyCount = AtomicInteger()
                            val allReady = CompletableDeferred<Unit>()
                            val writes =
                                commands
                                    .map { command ->
                                        async(Dispatchers.Default) {
                                            if (
                                                readyCount.incrementAndGet() ==
                                                    CONCURRENT_CAPTURE_COUNT
                                            ) {
                                                allReady.complete(Unit)
                                            }
                                            start.await()
                                            command to
                                                runCatching {
                                                    fixture.captures.save(command)
                                                }
                                        }
                                    }
                            allReady.await()
                            start.complete(Unit)
                            writes.awaitAll()
                        }
                    } finally {
                        fixture.openReadWriteDatabase().use { database ->
                            database.execSQL(
                                "DROP TRIGGER IF EXISTS `$CONCURRENT_ABORT_TRIGGER_NAME`",
                            )
                        }
                    }

                val failedResult =
                    firstWave.single { (command, _) ->
                        command.capture.source.intentId == CONCURRENT_RETRY_INTENT_ID
                    }.second
                assertTrue(failedResult.isFailure)
                val firstWaveReceipts =
                    firstWave.mapNotNull { (_, result) -> result.getOrNull() }
                assertEquals(CONCURRENT_CAPTURE_COUNT - 1, firstWaveReceipts.size)
                fixture.openReadOnlyDatabase().use { database ->
                    assertEquals(
                        0,
                        database.matchingRowCount(
                            tableName = "student_capture_save_handoff",
                            columnName = "intent_id",
                            value = CONCURRENT_RETRY_INTENT_ID,
                        ),
                    )
                    assertEquals(
                        0,
                        database.matchingRowCount(
                            tableName = "student_problem_error_occurrence",
                            columnName = "idempotency_key",
                            value = CONCURRENT_RETRY_INTENT_ID,
                        ),
                    )
                    assertEquals(
                        0,
                        database.matchingRowCount(
                            tableName = "student_capture_occurrence_transaction",
                            columnName = "capture_intent_id",
                            value = CONCURRENT_RETRY_INTENT_ID,
                        ),
                    )
                }

                val failedCommand =
                    commands.single {
                        it.capture.source.intentId == CONCURRENT_RETRY_INTENT_ID
                    }
                val retryReceipt = fixture.captures.save(failedCommand)
                val receipts = firstWaveReceipts + retryReceipt

                assertEquals(CONCURRENT_CAPTURE_COUNT, receipts.size)
                assertEquals(
                    1,
                    receipts.map(StudentCaptureOccurrenceReceipt::resolvedIdentity)
                        .distinct()
                        .size,
                )
                assertEquals(
                    1,
                    receipts.map { it.save.handoff.targetProblem }.distinct().size,
                )
                assertEquals(
                    1,
                    receipts.map { it.save.handoff.targetRevision }.distinct().size,
                )
                fixture.openReadOnlyDatabase().use { database ->
                    database.assertRowCounts(
                        "student_problem_canonical_identity" to 1,
                        "student_problem_canonical_source_binding" to 1,
                        "student_problem_document" to 1,
                        "student_problem_revision" to 1,
                        "student_practice_unit" to 1,
                        "student_problem_image_reference" to 1,
                        "student_mistake_save_receipt" to 1,
                        "student_capture_save_handoff" to CONCURRENT_CAPTURE_COUNT,
                        "student_problem_error_occurrence" to CONCURRENT_CAPTURE_COUNT,
                        "student_capture_occurrence_transaction" to CONCURRENT_CAPTURE_COUNT,
                        "student_store_outbox" to 0,
                        "student_review_candidate" to 0,
                    )
                    commands.forEach { command ->
                        val intentId = command.capture.source.intentId
                        assertEquals(
                            1,
                            database.matchingRowCount(
                                tableName = "student_capture_save_handoff",
                                columnName = "intent_id",
                                value = intentId,
                            ),
                        )
                        assertEquals(
                            1,
                            database.matchingRowCount(
                                tableName = "student_problem_error_occurrence",
                                columnName = "idempotency_key",
                                value = intentId,
                            ),
                        )
                        assertEquals(
                            1,
                            database.matchingRowCount(
                                tableName =
                                    "student_capture_occurrence_transaction",
                                columnName = "capture_intent_id",
                                value = intentId,
                            ),
                        )
                    }
                }
            }
        }

    private suspend fun withCaptureFixture(
        databaseName: String,
        block: suspend (CaptureFixture) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val authority =
            StudentProblemIdentityEvidenceAuthority.create(
                "instrumented-canonical-identity-owner",
                "v1",
            )
        val store = StudentMistakeStoreFactory.openForTest(context, databaseName)
        try {
            val captures =
                store.captureOccurrencesForLearner(
                    LEARNER_ID,
                    authority.verifier,
                )
            val receiptClock = AtomicLong(System.currentTimeMillis())
            val identityOwner =
                createStudentProblemIdentityEvidenceOwner(
                    learnerId = LEARNER_ID,
                    ledger = store.identityReceiptLedger(),
                    authority = authority,
                    captureOccurrences = captures,
                    trustedSourceAdmissionOwner =
                        TrustedStudentProblemSourceAdmissionOwner { command ->
                            check(
                                command.capture.capture.target.problem.revision.problem.learnerId ==
                                    LEARNER_ID,
                            )
                        },
                    reviewedAliasAdmissionOwner =
                        ReviewedStudentProblemAliasAdmissionOwner { command ->
                            check(
                                command.capture.capture.target.problem.revision.problem.learnerId ==
                                    LEARNER_ID,
                            )
                        },
                    nowEpochMillis = receiptClock::get,
                    receiptValidityMillis = 600_000L,
                )
            val fixture =
                CaptureFixture(
                    context = context,
                    databaseName = databaseName,
                    store = store,
                    captures = captures,
                    identityOwner = identityOwner,
                    receiptClock = receiptClock,
                )
            fixture.materializeRoomDatabase()
            block(fixture)
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    private data class CaptureFixture(
        val context: Context,
        val databaseName: String,
        val store: RoomStudentMistakeStore,
        val captures: LearnerBoundStudentCaptureOccurrencePort,
        val identityOwner: StudentProblemIdentityEvidenceOwner,
        val receiptClock: AtomicLong,
    ) {
        suspend fun materializeRoomDatabase() {
            val probe =
                StudentProblemRef(
                    learnerId = LEARNER_ID,
                    subject = SubjectKind.MATH,
                    problemId = "fixture-room-open-probe",
                    practiceUnitId = "fixture-room-open-probe",
                )
            check(store.findProblem(probe) == null) {
                "Fresh capture fixture unexpectedly contains the Room open probe"
            }
            check(context.getDatabasePath(databaseName).isFile) {
                "Room did not materialize capture fixture database $databaseName"
            }
        }

        fun openReadOnlyDatabase(): SQLiteDatabase =
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )

        fun openReadWriteDatabase(): SQLiteDatabase =
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )

        suspend fun issueTrustedSource(
            candidateCommand: SaveStudentCaptureOccurrenceCommand,
            locatorNamespace: String,
            locatorVersion: String,
            itemLocatorCanonicalFingerprint: String,
            issuedAtEpochMillis: Long =
                System.currentTimeMillis() - 1_000L,
        ): StudentProblemIdentityEvidenceAuthority.TrustedSourceEvidence {
            receiptClock.set(issuedAtEpochMillis)
            val reference =
                identityOwner.registerTrustedSource(
                    RegisterTrustedStudentProblemSourceCommand(
                        registrationId =
                            "trusted-${candidateCommand.capture.source.intentId}-" +
                                issuedAtEpochMillis,
                        capture = candidateCommand,
                        locatorNamespace = locatorNamespace,
                        locatorVersion = locatorVersion,
                        itemLocatorCanonicalFingerprint =
                            itemLocatorCanonicalFingerprint,
                    ),
                )
            return identityOwner.verifyAndIssueTrustedSource(
                reference,
                candidateCommand,
            )
        }

        suspend fun issueReviewedAlias(
            existingIdentity: StudentProblemCanonicalIdentityKey,
            candidateCommand: SaveStudentCaptureOccurrenceCommand,
            reviewCaseId: String,
            reviewRevision: Int = 1,
            reviewDecisionCanonicalFingerprint: String,
            issuedAtEpochMillis: Long =
                System.currentTimeMillis() - 1_000L,
        ): StudentProblemIdentityEvidenceAuthority.ReviewedAliasEvidence {
            receiptClock.set(issuedAtEpochMillis)
            val reference =
                identityOwner.registerReviewedAlias(
                    RegisterReviewedStudentProblemAliasCommand(
                        registrationId =
                            "alias-${candidateCommand.capture.source.intentId}-" +
                                "$reviewRevision-$issuedAtEpochMillis",
                        capture = candidateCommand,
                        reviewAuthorityKind =
                            StudentProblemAliasReviewAuthorityKind.INDEPENDENT_REVIEW,
                        existingIdentity = existingIdentity,
                        reviewCaseId = reviewCaseId,
                        reviewRevision = reviewRevision,
                        reviewDecisionCanonicalFingerprint =
                            reviewDecisionCanonicalFingerprint,
                    ),
                )
            return identityOwner.verifyAndIssueReviewedAlias(
                reference,
                candidateCommand,
            )
        }
    }

    companion object {
        const val LEARNER_ID = "learner-canonical-capture-test"
        const val REUSE_DATABASE_NAME =
            "canonical-capture-reuse.student-mistake-test.db"
        const val CORRECTION_DATABASE_NAME =
            "canonical-capture-correction.student-mistake-test.db"
        const val REPLAY_DATABASE_NAME =
            "canonical-capture-replay.student-mistake-test.db"
        const val ALIAS_DATABASE_NAME =
            "canonical-capture-alias.student-mistake-test.db"
        const val TRUSTED_SOURCE_DATABASE_NAME =
            "canonical-capture-trusted-source.student-mistake-test.db"
        const val ZERO_IMAGE_TRUSTED_SOURCE_DATABASE_NAME =
            "canonical-capture-zero-image-trusted-source.student-mistake-test.db"
        const val CROSS_KIND_ORDER_DATABASE_NAME =
            "canonical-capture-cross-kind-order.student-mistake-test.db"
        const val ALIAS_REPLAY_DATABASE_NAME =
            "canonical-capture-alias-replay.student-mistake-test.db"
        const val ALIAS_CONTRADICTION_DATABASE_NAME =
            "canonical-capture-alias-contradiction.student-mistake-test.db"
        const val LATE_ABORT_DATABASE_NAME =
            "canonical-capture-late-abort.student-mistake-test.db"
        const val LATE_ABORT_TRIGGER_NAME =
            "abort_atomic_capture_transaction_receipt_test"
        const val CONCURRENT_CAPTURE_DATABASE_NAME =
            "canonical-capture-concurrent.student-mistake-test.db"
        const val CONCURRENT_ABORT_TRIGGER_NAME =
            "abort_one_concurrent_atomic_capture_test"
        const val CONCURRENT_RETRY_INTENT_ID =
            "concurrent-first-write-retry"
        const val CONCURRENT_CAPTURE_COUNT = 16

        val ORDERED_REGIONS =
            listOf(
                NormalizedSourceRegion(
                    left = 0.10,
                    top = 0.15,
                    right = 0.90,
                    bottom = 0.55,
                ),
                NormalizedSourceRegion(
                    left = 0.20,
                    top = 0.60,
                    right = 0.80,
                    bottom = 0.90,
                ),
            )

        val ATOMIC_CAPTURE_WRITE_TABLES =
            listOf(
                "student_problem_document",
                "student_problem_revision",
                "student_practice_unit",
                "student_problem_image_reference",
                "student_problem_search_document",
                "student_problem_search_fts",
                "student_problem_search_index_state",
                "student_problem_collection",
                "student_mistake_save_receipt",
                "student_problem_canonical_identity",
                "student_problem_canonical_source_binding",
                "student_capture_save_handoff",
                "student_problem_error_occurrence",
                "student_problem_error_occurrence_evidence",
                "student_capture_occurrence_transaction",
                "student_store_outbox",
                "student_review_candidate",
                "student_learner_change",
            )
    }
}

private fun capturedQuestionDocument(
    documentId: String,
    sourceAssetId: String,
    markdown: String,
): CapturedQuestionDocument =
    CapturedQuestionDocument(
        document =
            QuestionDocument(
                id = documentId,
                title = "测试题",
                blocks =
                    listOf(
                        ContentBlock.Paragraph(
                            id = "stem",
                            markdown = markdown,
                        ),
                    ),
            ),
        blockEvidence =
            listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = sourceAssetId,
                    sourceRegion =
                        StudentCanonicalCaptureIdentityInstrumentedTest.ORDERED_REGIONS.first(),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_TRANSCRIPTION,
                    confidence = 1.0,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
    ).also { document ->
        check(CapturedQuestionDocumentValidator.validateForCommit(document).isEmpty())
    }

private fun captureCommand(
    intentId: String,
    occurredAtEpochMillis: Long,
    document: CapturedQuestionDocument,
    assetFingerprint: String,
    identityEvidence: StudentProblemIdentityEvidence,
    practiceUnitKind: StudentPracticeUnitKind =
        StudentPracticeUnitKind.WHOLE_PROBLEM,
    practiceUnitTitle: String = "整题",
    itemFamilyId: String? = null,
    estimatedDurationSeconds: Int = 120,
    sourceBundleId: String? = "source-bundle-$intentId",
    partIds: List<String> = emptyList(),
    includeOriginalImage: Boolean = true,
): SaveStudentCaptureOccurrenceCommand {
    val problem =
        StudentProblemRef(
            learnerId = StudentCanonicalCaptureIdentityInstrumentedTest.LEARNER_ID,
            subject = SubjectKind.MATH,
            problemId = "provisional-problem-$intentId",
            practiceUnitId = "provisional-practice-$intentId",
        )
    val revision =
        StudentProblemRevisionRef(
            problem = problem,
            revisionId = "provisional-revision-$intentId",
            revisionNumber = 1,
            documentCanonicalFingerprint =
                CapturedQuestionDocumentFingerprint.of(document),
        )
    val source =
        TutorStudentCaptureSaveSource(
            intentId = intentId,
            sourceCanonicalFingerprint = fingerprint("capture-source:$intentId"),
            saveRequestId = "save-request-$intentId",
            sessionId = "session-$intentId",
            draftId = "draft-$intentId",
            draftRevisionNumber = 1,
            occurredAtEpochMillis = occurredAtEpochMillis,
        )
    val target =
        SaveTargetConfirmedStudentMistakeCommand(
            problem =
                CommitStudentProblemCommand(
                    revision = revision,
                    title = document.document.title,
                    stemMarkdown =
                        QuestionDocumentMarkdownProjection.project(document.document),
                    practiceUnitKind = practiceUnitKind,
                    practiceUnitTitle = practiceUnitTitle,
                    itemFamilyId = itemFamilyId ?: problem.problemId,
                    estimatedDurationSeconds = estimatedDurationSeconds,
                    sourceBundleId = sourceBundleId,
                    partIds = partIds,
                    originalImages =
                        if (includeOriginalImage) {
                            listOf(
                                StudentProblemImageReference(
                                    imageReferenceId = "provisional-image-$intentId",
                                    localContentUri = "content://capture/$intentId",
                                    contentCanonicalFingerprint = assetFingerprint,
                                    mediaType = "image/jpeg",
                                    ordinal = 0,
                                    widthPixels = 1_200,
                                    heightPixels = 900,
                                    byteSize = 48_000L,
                                    selectedRegions =
                                        StudentCanonicalCaptureIdentityInstrumentedTest
                                            .ORDERED_REGIONS,
                                ),
                            )
                        } else {
                            emptyList()
                        },
                    committedAtEpochMillis = occurredAtEpochMillis,
                    errorBookEntryId = "provisional-error-book-$intentId",
                    capturedQuestionDocument = document,
                ),
            confirmedAtEpochMillis = occurredAtEpochMillis,
        )
    return SaveStudentCaptureOccurrenceCommand(
        capture =
            SaveStudentOwnedCaptureCommand(
                source = source,
                target = target,
            ),
        occurrence =
            AppendStudentProblemErrorOccurrenceCommand(
                occurrenceId = "provisional-occurrence-$intentId",
                idempotencyKey = intentId,
                problemRevision = revision,
                batchCanonicalFingerprint = fingerprint("batch:$intentId"),
                importSourceCanonicalFingerprint =
                    fingerprint("import-source:$intentId"),
                occurredAtEpochMillis = occurredAtEpochMillis,
                importedAtEpochMillis = occurredAtEpochMillis + 10L,
                attributionStatus =
                    ProblemErrorAttributionResolutionStatus.UNRESOLVED,
            ),
        identityEvidence = identityEvidence,
    )
}

private fun canonicalReviewedAliasEvidenceFingerprint(
    existingIdentity: StudentProblemCanonicalIdentityKey,
    candidateCommand: SaveStudentCaptureOccurrenceCommand,
    reviewCaseId: String,
    reviewDecisionCanonicalFingerprint: String,
): String {
    val candidate = candidateCommand.capture.target.problem
    return canonicalStudentProblemIdentityEvidenceFingerprint(
        evidenceKind = StudentProblemIdentityEvidenceKind.REVIEWED_ALIAS,
        transactionId = candidateCommand.transactionId,
        assetManifestCanonicalFingerprint =
            studentCaptureAssetManifestCanonicalFingerprint(
                candidate.originalImages,
            ),
        selectedRegionCanonicalFingerprint =
            studentCaptureSelectedRegionCanonicalFingerprint(
                candidate.originalImages,
            ),
        candidateDocumentCanonicalFingerprint =
            candidate.revision.documentCanonicalFingerprint,
        aliasIdentityCanonicalFingerprint =
            existingIdentity.canonicalFingerprint(
                learnerId =
                    StudentCanonicalCaptureIdentityInstrumentedTest.LEARNER_ID,
                subject = SubjectKind.MATH.name,
            ),
        reviewCaseId = reviewCaseId,
        reviewRevision = 1,
        reviewDecisionCanonicalFingerprint =
            reviewDecisionCanonicalFingerprint,
    )
}

private fun fingerprint(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private data class PersistedProblemState(
    val currentRevisionId: String,
    val lifecycleState: String,
    val archivedAtEpochMillis: Long?,
    val tombstonedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

private data class PersistedPracticeState(
    val basisRevisionId: String,
    val unitKind: String,
    val title: String,
    val itemFamilyId: String,
    val estimatedDurationSeconds: Int,
    val sourceBundleId: String?,
    val partIdsWire: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

private fun SQLiteDatabase.readProblemState(
    problemId: String,
): PersistedProblemState =
    rawQuery(
        """
        SELECT current_revision_id,
               lifecycle_state,
               archived_at_epoch_millis,
               tombstoned_at_epoch_millis,
               created_at_epoch_millis,
               updated_at_epoch_millis
        FROM student_problem_document
        WHERE problem_id = ?
        """.trimIndent(),
        arrayOf(problemId),
    ).use { cursor ->
        check(cursor.moveToFirst())
        check(cursor.count == 1)
        PersistedProblemState(
            currentRevisionId = cursor.getString(0),
            lifecycleState = cursor.getString(1),
            archivedAtEpochMillis =
                cursor.getLongOrNull(2),
            tombstonedAtEpochMillis =
                cursor.getLongOrNull(3),
            createdAtEpochMillis = cursor.getLong(4),
            updatedAtEpochMillis = cursor.getLong(5),
        )
    }

private fun SQLiteDatabase.readPracticeState(
    practiceUnitId: String,
): PersistedPracticeState =
    rawQuery(
        """
        SELECT basis_revision_id,
               unit_kind,
               title,
               item_family_id,
               estimated_duration_seconds,
               source_bundle_id,
               part_ids_wire,
               created_at_epoch_millis,
               updated_at_epoch_millis
        FROM student_practice_unit
        WHERE practice_unit_id = ?
        """.trimIndent(),
        arrayOf(practiceUnitId),
    ).use { cursor ->
        check(cursor.moveToFirst())
        check(cursor.count == 1)
        PersistedPracticeState(
            basisRevisionId = cursor.getString(0),
            unitKind = cursor.getString(1),
            title = cursor.getString(2),
            itemFamilyId = cursor.getString(3),
            estimatedDurationSeconds = cursor.getInt(4),
            sourceBundleId =
                if (cursor.isNull(5)) null else cursor.getString(5),
            partIdsWire = cursor.getString(6),
            createdAtEpochMillis = cursor.getLong(7),
            updatedAtEpochMillis = cursor.getLong(8),
        )
    }

private fun android.database.Cursor.getLongOrNull(columnIndex: Int): Long? =
    if (isNull(columnIndex)) null else getLong(columnIndex)

private fun SQLiteDatabase.rowCounts(
    tableNames: List<String>,
): Map<String, Int> =
    tableNames.associateWith { tableName ->
        rawQuery(
            "SELECT COUNT(*) FROM `${tableName.requireSqlIdentifier()}`",
            null,
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

private fun SQLiteDatabase.assertRowCounts(
    vararg expected: Pair<String, Int>,
) {
    expected.forEach { (tableName, rowCount) ->
        assertEquals(
            "$tableName row count",
            rowCount,
            rawQuery("SELECT COUNT(*) FROM `${tableName.requireSqlIdentifier()}`", null)
                .use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                },
        )
    }
}

private fun SQLiteDatabase.distinctValueCount(
    tableName: String,
    columnName: String,
): Int =
    rawQuery(
        """
        SELECT COUNT(DISTINCT `${columnName.requireSqlIdentifier()}`)
        FROM `${tableName.requireSqlIdentifier()}`
        """.trimIndent(),
        null,
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

private fun SQLiteDatabase.matchingRowCount(
    tableName: String,
    columnName: String,
    value: String,
): Int =
    rawQuery(
        """
        SELECT COUNT(*)
        FROM `${tableName.requireSqlIdentifier()}`
        WHERE `${columnName.requireSqlIdentifier()}` = ?
        """.trimIndent(),
        arrayOf(value),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

private fun SQLiteDatabase.singleText(
    query: String,
    selectionArgs: Array<String>,
): String =
    rawQuery(query, selectionArgs).use { cursor ->
        check(cursor.moveToFirst())
        check(cursor.count == 1)
        cursor.getString(0)
    }

private fun String.requireSqlIdentifier(): String =
    also {
        require(matches(Regex("[a-z_]+"))) {
            "Unsafe SQLite identifier in test"
        }
    }
