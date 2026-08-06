package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.student.mistake.database.AcknowledgeStudentCaptureSaveHandoffCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.LibraryStudentCaptureSaveSource
import com.tingyun.smartmistakebook.core.student.mistake.database.ReadPendingStudentCaptureSaveHandoffsQuery
import com.tingyun.smartmistakebook.core.student.mistake.database.SaveStudentCaptureOccurrenceCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCaptureOccurrenceReceipt
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCaptureSaveHandoffRecord
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentOwnedCaptureSaveReceipt
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemCanonicalIdentityKey
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemErrorOccurrence
import com.tingyun.smartmistakebook.core.student.mistake.database.TargetConfirmedStudentMistakeSaveOutcome
import com.tingyun.smartmistakebook.core.student.mistake.database.studentCaptureAssetManifestCanonicalFingerprint
import com.tingyun.smartmistakebook.core.student.mistake.database.studentCaptureSelectedRegionCanonicalFingerprint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProductionCaptureOccurrenceCommitPortTest {
    @Test
    fun exactSelectionIsOneOwnerCommandWithCanonicalEvidenceFingerprints() = runBlocking {
        val prepared = prepared()
        val owner = RecordingOccurrenceOwner()
        val port = ProductionCaptureOccurrenceCommitPortFactory.create(owner)

        val receipt = port.save(prepared)

        val command = owner.commands.single()
        assertEquals(prepared.command, command.capture)
        assertEquals(command.capture.source.intentId, command.occurrence.idempotencyKey)
        assertEquals(
            command.capture.target.problem.revision,
            command.occurrence.problemRevision,
        )
        assertEquals(
            studentCaptureAssetManifestCanonicalFingerprint(
                prepared.command.target.problem.originalImages,
            ),
            receipt.assetManifestCanonicalFingerprint,
        )
        assertEquals(
            studentCaptureSelectedRegionCanonicalFingerprint(
                prepared.command.target.problem.originalImages,
            ),
            receipt.selectedRegionCanonicalFingerprint,
        )
    }

    @Test
    fun localUriAliasesDoNotChangeEvidenceButSelectedRegionsDo() = runBlocking {
        val baseline = prepared()
        val baselineImage = baseline.command.target.problem.originalImages.single()
        val uriAlias =
            baseline.withImages(
                listOf(
                    baselineImage.copy(
                        localContentUri = "content://another-local-alias",
                    ),
                ),
            )
        val selected =
            baseline.withImages(
                listOf(
                    baselineImage.copy(
                        selectedRegions =
                            listOf(NormalizedSourceRegion(0.1, 0.2, 0.8, 0.9)),
                    ),
                ),
            )

        val baselineReceipt =
            ProductionCaptureOccurrenceCommitPortFactory.create(RecordingOccurrenceOwner())
                .save(baseline)
        val uriReceipt =
            ProductionCaptureOccurrenceCommitPortFactory.create(RecordingOccurrenceOwner())
                .save(uriAlias)
        val selectedReceipt =
            ProductionCaptureOccurrenceCommitPortFactory.create(RecordingOccurrenceOwner())
                .save(selected)

        assertEquals(
            baselineReceipt.assetManifestCanonicalFingerprint,
            uriReceipt.assetManifestCanonicalFingerprint,
        )
        assertEquals(
            baselineReceipt.selectedRegionCanonicalFingerprint,
            uriReceipt.selectedRegionCanonicalFingerprint,
        )
        assertEquals(
            baselineReceipt.assetManifestCanonicalFingerprint,
            selectedReceipt.assetManifestCanonicalFingerprint,
        )
        assertNotEquals(
            baselineReceipt.selectedRegionCanonicalFingerprint,
            selectedReceipt.selectedRegionCanonicalFingerprint,
        )
    }

    @Test
    fun factoryExposesOnlyTheOwnerComposedCapability() {
        val source = projectFile(
            "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/capture/" +
                "ProductionCaptureOccurrenceCommitPort.kt",
        ).readText()
        val adapter = projectFile(
            "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/capture/" +
                "ProductionStudentProblemIdentityEvidenceAdapter.kt",
        ).readText()

        assertTrue("ProductionStudentCaptureOccurrenceOwner" in source)
        assertFalse("createWithIdentityEvidence" in source)
        assertFalse("LearnerBoundStudentCaptureOccurrencePort" in source)
        assertTrue("ExactAssetSelectionOrUnresolved" in adapter)
        assertFalse("ReceiptReference" in adapter)
        assertFalse("TrustedSource" in adapter)
        assertFalse("ReviewedAlias" in adapter)
        assertFalse("IdentityEvidenceReceipt(" in adapter)
        assertFalse("Model" in adapter)
        assertFalse("OCR" in adapter)
        assertFalse("android.net.Uri" in adapter)
        assertFalse("Sql" in adapter)
        assertFalse("Mastery" in adapter)
        assertFalse("Knowledge" in adapter)
    }

    private fun prepared(): PreparedStudentOwnedCaptureCommit {
        val source =
            LibraryStudentCaptureSaveSource(
                intentId = "confirm-port-test",
                sourceCanonicalFingerprint = "1".repeat(64),
                draftId = "draft-port-test",
                basisRevisionNumber = 1,
                workspaceVersion = 1,
                workspaceCanonicalFingerprint = "2".repeat(64),
                confirmationRequestId = "confirm-port-test",
                occurredAtEpochMillis = 1_000L,
            )
        return preparedCapture(
            source = source,
            learnerId = "learner-local",
            imageCount = 1,
        )
    }

    private fun projectFile(relativePath: String): File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, relativePath) }
            .first(File::isFile)
}

private class RecordingOccurrenceOwner : ProductionStudentCaptureOccurrenceOwner {
    override val learnerId: String = "learner-local"
    val commands = mutableListOf<SaveStudentCaptureOccurrenceCommand>()

    override suspend fun save(
        command: SaveStudentCaptureOccurrenceCommand,
        identityEvidenceRequest: ProductionStudentProblemIdentityEvidenceRequest,
    ): StudentCaptureOccurrenceReceipt {
        check(
            identityEvidenceRequest ==
                ProductionStudentProblemIdentityEvidenceRequest.ExactAssetSelectionOrUnresolved,
        )
        commands += command
        return command.toTestReceipt()
    }

    override suspend fun readPending(
        query: ReadPendingStudentCaptureSaveHandoffsQuery,
    ): List<StudentCaptureSaveHandoffRecord> = emptyList()

    override suspend fun readByDraftIds(
        draftIds: Set<String>,
    ): List<StudentCaptureSaveHandoffRecord> = emptyList()

    override suspend fun readBySessionId(
        sessionId: String,
    ): StudentCaptureSaveHandoffRecord? = null

    override suspend fun acknowledge(
        command: AcknowledgeStudentCaptureSaveHandoffCommand,
    ): StudentCaptureSaveHandoffRecord = error("No pending handoff")
}

private fun PreparedStudentOwnedCaptureCommit.withImages(
    images: List<com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemImageReference>,
): PreparedStudentOwnedCaptureCommit =
    copy(
        command =
            command.copy(
                target =
                    command.target.copy(
                        problem = command.target.problem.copy(originalImages = images),
                    ),
            ),
    )

private fun SaveStudentCaptureOccurrenceCommand.toTestReceipt():
    StudentCaptureOccurrenceReceipt {
    val source = capture.source
    val revision = capture.target.problem.revision
    val identity = testIdentity(source.intentId)
    val handoff =
        StudentCaptureSaveHandoffRecord(
            source = source,
            learnerId = revision.problem.learnerId,
            targetProblem = revision.problem,
            targetRevision = revision,
            errorBookEntryId = checkNotNull(capture.target.problem.errorBookEntryId),
            targetCanonicalFingerprint = capture.target.targetCanonicalFingerprint,
            acknowledgedAtEpochMillis = null,
        )
    val storedOccurrence =
        StudentProblemErrorOccurrence(
            ref = occurrence.ref,
            idempotencyKey = occurrence.idempotencyKey,
            batchCanonicalFingerprint = occurrence.batchCanonicalFingerprint,
            importSourceCanonicalFingerprint =
                occurrence.importSourceCanonicalFingerprint,
            occurredAtEpochMillis = occurrence.occurredAtEpochMillis,
            importedAtEpochMillis = occurrence.importedAtEpochMillis,
            attributionStatus = occurrence.attributionStatus,
            evidenceRefs = occurrence.evidenceRefs,
        )
    val assetFingerprint =
        studentCaptureAssetManifestCanonicalFingerprint(
            capture.target.problem.originalImages,
        )
    val regionFingerprint =
        studentCaptureSelectedRegionCanonicalFingerprint(
            capture.target.problem.originalImages,
        )
    return StudentCaptureOccurrenceReceipt(
        transactionId = transactionId,
        transactionCanonicalFingerprint =
            testTransactionFingerprint(
                sourceKind = source.kind.name,
                sourceIntentId = source.intentId,
                sourceCanonicalFingerprint = source.sourceCanonicalFingerprint,
                canonicalIdentityFingerprint =
                    identity.canonicalFingerprint(
                        learnerId = handoff.learnerId,
                        subject = revision.problem.subject.name,
                    ),
                handoff = handoff,
                occurrence = storedOccurrence,
                assetManifestCanonicalFingerprint = assetFingerprint,
                selectedRegionCanonicalFingerprint = regionFingerprint,
            ),
        assetManifestCanonicalFingerprint = assetFingerprint,
        selectedRegionCanonicalFingerprint = regionFingerprint,
        resolvedIdentity = identity,
        save =
            StudentOwnedCaptureSaveReceipt(
                outcome = TargetConfirmedStudentMistakeSaveOutcome.CREATED,
                handoff = handoff,
            ),
        occurrence = storedOccurrence,
    )
}

private fun testTransactionFingerprint(
    sourceKind: String,
    sourceIntentId: String,
    sourceCanonicalFingerprint: String,
    canonicalIdentityFingerprint: String,
    handoff: StudentCaptureSaveHandoffRecord,
    occurrence: StudentProblemErrorOccurrence,
    assetManifestCanonicalFingerprint: String,
    selectedRegionCanonicalFingerprint: String,
): String =
    CanonicalSha256("student-capture-occurrence-transaction-v1")
        .field("schemaVersion", 1)
        .field("sourceKind", sourceKind)
        .field("sourceIntentId", sourceIntentId)
        .field("sourceCanonicalFingerprint", sourceCanonicalFingerprint)
        .field("canonicalIdentityFingerprint", canonicalIdentityFingerprint)
        .field("targetLearnerId", handoff.learnerId)
        .field("targetSubject", handoff.targetRevision.problem.subject.name)
        .field("targetProblemId", handoff.targetRevision.problem.problemId)
        .field("targetPracticeUnitId", handoff.targetRevision.problem.practiceUnitId)
        .field("targetRevisionId", handoff.targetRevision.revisionId)
        .field("targetRevisionNumber", handoff.targetRevision.revisionNumber)
        .field(
            "targetDocumentCanonicalFingerprint",
            handoff.targetRevision.documentCanonicalFingerprint,
        )
        .field("targetCanonicalFingerprint", handoff.targetCanonicalFingerprint)
        .field("occurrenceId", occurrence.ref.occurrenceId)
        .field(
            "occurrenceCanonicalFingerprint",
            occurrence.ref.occurrenceCanonicalFingerprint,
        )
        .field("batchCanonicalFingerprint", occurrence.batchCanonicalFingerprint)
        .field(
            "importSourceCanonicalFingerprint",
            occurrence.importSourceCanonicalFingerprint,
        )
        .field(
            "assetManifestCanonicalFingerprint",
            assetManifestCanonicalFingerprint,
        )
        .field(
            "selectedRegionCanonicalFingerprint",
            selectedRegionCanonicalFingerprint,
        )
        .finish()

private fun testIdentity(stableKey: String): StudentProblemCanonicalIdentityKey {
    val constructor =
        StudentProblemCanonicalIdentityKey::class.java.declaredConstructors.single {
            it.parameterCount == 3
        }
    constructor.isAccessible = true
    return constructor.newInstance(
        "test-student-owner",
        "v1",
        stableKey,
    ) as StudentProblemCanonicalIdentityKey
}
