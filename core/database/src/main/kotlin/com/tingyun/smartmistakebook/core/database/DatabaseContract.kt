package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspace
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspaceCodec
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspaceFingerprint
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.LearningLedgerFingerprint
import com.tingyun.smartmistakebook.core.model.LocalReviewSelfReportContract
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.ReviewDifficultyBand
import com.tingyun.smartmistakebook.core.model.ReviewReason
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object DatabaseContractValidator {
    const val MAX_PROJECTION_BATCH_SIZE = 4_096
    private const val MAX_APPLIED_ATTEMPT_RECORDS = 4_096
    private const val MAX_RENDERABLE_MARKDOWN_CHARS = 32_000
    private const val MAX_REVIEW_QUEUE_ITEMS = 512
    private const val MAX_CANONICAL_ASSET_BYTES = 24L * 1_024L * 1_024L
    private const val MAX_CANONICAL_DIMENSION = 8_192
    private const val MAX_CANONICAL_PIXELS = 16_000_000L
    private val stableToken = Regex("[A-Z][A-Z0-9_]{0,63}")
    private val localDate = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val sha256 = Regex("[a-f0-9]{64}")
    private val subjects = setOf(
        "CHINESE",
        "MATH",
        "ENGLISH",
        "PHYSICS",
        "CHEMISTRY",
        "BIOLOGY",
        "POLITICS",
        "HISTORY",
        "GEOGRAPHY",
        "GENERAL",
    )
    private val errorBookStatuses = setOf(
        StudyDbValue.ErrorBookStatus.ACTIVE,
        StudyDbValue.ErrorBookStatus.ARCHIVED,
        StudyDbValue.ErrorBookStatus.TRASHED,
    )
    private val relationTypes = setOf(
        StudyDbValue.RelationType.SAME_KNOWLEDGE,
        StudyDbValue.RelationType.SAME_ERROR_PATTERN,
        StudyDbValue.RelationType.VARIANT_OF,
        StudyDbValue.RelationType.PREREQUISITE_OF,
        StudyDbValue.RelationType.SAME_SOURCE_BUNDLE,
        StudyDbValue.RelationType.SHARES_STIMULUS,
        StudyDbValue.RelationType.CONTINUATION_OF,
        StudyDbValue.RelationType.ANSWER_FOR,
        StudyDbValue.RelationType.SAME_FIGURE_PATTERN,
        StudyDbValue.RelationType.POSSIBLE_DUPLICATE,
        StudyDbValue.RelationType.DERIVED_FROM,
    )
    private val relationStatuses = setOf(
        StudyDbValue.RelationStatus.ACTIVE,
        StudyDbValue.RelationStatus.STALE,
        StudyDbValue.RelationStatus.REJECTED,
    )
    private val assessmentEventTypes = setOf(
        StudyDbValue.AssessmentEventType.PRESENTED,
        StudyDbValue.AssessmentEventType.HINT_REVEALED,
        StudyDbValue.AssessmentEventType.ANSWER_REVEALED,
        StudyDbValue.AssessmentEventType.RESPONSE_SUBMITTED,
        StudyDbValue.AssessmentEventType.CANCELLED,
    )
    private val assessmentEligibilities = setOf(
        StudyDbValue.AssessmentEligibility.SESSION_ONLY,
        StudyDbValue.AssessmentEligibility.ATTEMPT_ELIGIBLE,
        StudyDbValue.AssessmentEligibility.BLOCKED,
    )
    private val scoringModes = setOf(
        StudyDbValue.ScoringMode.AUTO_VERIFIED,
        StudyDbValue.ScoringMode.USER_SELF_REPORT,
        StudyDbValue.ScoringMode.RUBRIC_ASSISTED,
    )
    private val verificationStatuses = setOf(
        StudyDbValue.VerificationStatus.VERIFIED,
        StudyDbValue.VerificationStatus.USER_ASSERTED,
        StudyDbValue.VerificationStatus.UNKNOWN,
    )
    private val reviewStatuses = setOf(
        StudyDbValue.ReviewStatus.PLANNED,
        StudyDbValue.ReviewStatus.IN_PROGRESS,
        StudyDbValue.ReviewStatus.COMPLETED,
        StudyDbValue.ReviewStatus.SKIPPED,
        StudyDbValue.ReviewStatus.CANCELLED,
    )
    private val reviewSessionStatuses = setOf(
        StudyDbValue.ReviewStatus.IN_PROGRESS,
        StudyDbValue.ReviewStatus.COMPLETED,
    )
    private val problemDraftAuthors = setOf(
        StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT,
        StudyDbValue.ProblemDraftAuthor.LOCAL_OCR,
        StudyDbValue.ProblemDraftAuthor.OPTIONAL_REMOTE_OCR,
        StudyDbValue.ProblemDraftAuthor.USER,
    )
    private val captureOrigins = setOf(
        StudyDbValue.CaptureOrigin.LIBRARY,
        StudyDbValue.CaptureOrigin.TUTOR,
    )
    private val sourceAssetTypes = setOf(
        StudyDbValue.SourceAssetType.CAMERA,
        StudyDbValue.SourceAssetType.PHOTO_PICKER,
    )
    fun validateCreateProblemDraft(command: CreateProblemDraftCommand) {
        validateSourceAsset(command.sourceAsset)
        id(command.draftId, "draftId")
        command.requestFingerprint?.let { fingerprint ->
            requireContract(sha256.matches(fingerprint)) {
                "Capture request fingerprint is invalid"
            }
        }
        known(command.origin, "captureOrigin", captureOrigins)
        requireContract(command.initialRevision.draftId == command.draftId) {
            "Initial draft revision must belong to its draft"
        }
        requireContract(
            command.initialRevision.revisionNumber == 1 &&
                command.initialRevision.basisRevisionNumber == null,
        ) { "Initial draft revision must start at revision 1 without a basis" }
        requireContract(
            command.initialRevision.author == StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT,
        ) { "Initial draft revision must be owned by the capture import boundary" }
        validateDraftRevision(command.initialRevision, command.sourceAsset.sourceAssetId)
    }

    fun validateAppendProblemDraftSourceAsset(command: AppendProblemDraftSourceAssetCommand) {
        id(command.draftId, "draftId")
        positive(command.expectedRevisionNumber, "expectedRevisionNumber")
        requireContract(command.expectedSourceAssetCount in 1 until MAX_CAPTURE_SOURCE_PAGES) {
            "A source page can only be appended within the source bundle limit"
        }
        validateSourceAsset(command.sourceAsset)
        nonNegative(command.appendedAtEpochMillis, "appendedAtEpochMillis")
    }

    private const val MAX_CAPTURE_SOURCE_PAGES = 8

    fun validateReviseProblemDraft(command: ReviseProblemDraftCommand) {
        id(command.draftId, "draftId")
        positive(command.expectedRevisionNumber, "expectedRevisionNumber")
        requireContract(command.revision.draftId == command.draftId) {
            "Draft revision must belong to its draft"
        }
        requireContract(
            command.revision.revisionNumber == command.expectedRevisionNumber + 1 &&
                command.revision.basisRevisionNumber == command.expectedRevisionNumber,
        ) { "Draft revisions must advance exactly one known basis revision" }
        validateDraftRevision(command.revision, expectedSourceAssetId = null)
    }

    fun validateReplaceProblemDraft(command: ReplaceProblemDraftCommand) {
        id(command.replacedDraftId, "replacedDraftId")
        positive(command.expectedReplacedRevisionNumber, "expectedReplacedRevisionNumber")
        requireContract(command.replacement.draftId != command.replacedDraftId) {
            "A replacement draft must use a new id"
        }
        validateCreateProblemDraft(command.replacement)
        nonNegative(command.replacedAtEpochMillis, "replacedAtEpochMillis")
        requireContract(
            command.replacement.initialRevision.createdAtEpochMillis ==
                command.replacedAtEpochMillis,
        ) { "Replacement creation time must match the replacement event" }
    }

    fun validateSplitProblemDraft(command: SplitProblemDraftCommand) {
        id(command.replacedDraftId, "replacedDraftId")
        positive(command.expectedReplacedRevisionNumber, "expectedReplacedRevisionNumber")
        requireContract(command.replacements.size in 2..12) {
            "A capture split must create between two and twelve drafts"
        }
        requireContract(command.replacements.map { it.draftId }.distinct().size == command.replacements.size) {
            "Capture split draft ids must be unique"
        }
        requireContract(command.replacements.none { it.draftId == command.replacedDraftId }) {
            "A split draft must use a new id"
        }
        command.replacements.forEach { replacement ->
            validateCreateProblemDraft(replacement)
            requireContract(
                replacement.initialRevision.createdAtEpochMillis == command.splitAtEpochMillis,
            ) { "Split draft creation time must match the split event" }
        }
        nonNegative(command.splitAtEpochMillis, "splitAtEpochMillis")
    }

    fun validateSaveProblemDraftEditWorkspace(
        command: SaveProblemDraftEditWorkspaceCommand,
    ): CaptureDraftWorkspace {
        id(command.draftId, "draftId")
        positive(command.basisRevisionNumber, "basisRevisionNumber")
        nonNegative(command.expectedWorkspaceVersion, "expectedWorkspaceVersion")
        requireContract(
            (command.expectedWorkspaceVersion == 0L &&
                command.expectedWorkspaceFingerprint == null) ||
                (command.expectedWorkspaceVersion > 0L &&
                    command.expectedWorkspaceFingerprint != null &&
                    sha256.matches(command.expectedWorkspaceFingerprint)),
        ) { "Expected workspace version and fingerprint must describe the same snapshot" }
        nonNegative(command.updatedAtEpochMillis, "updatedAtEpochMillis")
        return decodeProblemDraftEditWorkspace(
            snapshotSchemaVersion = command.snapshotSchemaVersion,
            workspaceSnapshot = command.workspaceSnapshot,
            workspaceFingerprint = command.workspaceFingerprint,
        )
    }

    fun validateConsumeProblemDraftEditWorkspace(
        command: ConsumeProblemDraftEditWorkspaceCommand,
    ) {
        id(command.draftId, "draftId")
        positive(command.basisRevisionNumber, "basisRevisionNumber")
        positive(command.expectedWorkspaceVersion, "expectedWorkspaceVersion")
        requireContract(sha256.matches(command.expectedWorkspaceFingerprint)) {
            "Expected workspace fingerprint is invalid"
        }
    }

    fun validateExpectedProblemDraftEditWorkspace(
        expected: ExpectedProblemDraftEditWorkspace,
    ) {
        id(expected.draftId, "draftId")
        positive(expected.basisRevisionNumber, "basisRevisionNumber")
        positive(expected.workspaceVersion, "workspaceVersion")
        requireContract(sha256.matches(expected.workspaceFingerprint)) {
            "Workspace fingerprint is invalid"
        }
        id(expected.finalRequestId, "finalRequestId")
        nonNegative(expected.finalOccurredAtEpochMillis, "finalOccurredAtEpochMillis")
    }

    fun validateConfirmAndCommitProblemDraftFromWorkspace(
        command: ConfirmAndCommitProblemDraftFromWorkspaceCommand,
    ) {
        validateExpectedProblemDraftEditWorkspace(command.workspace)
        validateCommitProblemDraft(command.commit)
        val expected = command.workspace
        val suffix = confirmationStableSuffix(expected)
        requireContract(
            command.commit.draftId == expected.draftId &&
                command.commit.expectedRevisionNumber == expected.basisRevisionNumber + 1 &&
                command.commit.commandId == "commit-$suffix" &&
                command.commit.problemId == "problem-$suffix" &&
                command.commit.problemRevisionId == "revision-$suffix" &&
                command.commit.practiceUnitId == "practice-$suffix" &&
                command.commit.errorBookEntryId == "entry-$suffix" &&
                command.commit.committedAtEpochMillis == expected.finalOccurredAtEpochMillis,
        ) { "Workspace confirmation commit identity is not deterministic" }
    }

    fun validateConfirmTutorSessionFromWorkspace(
        command: ConfirmTutorSessionFromWorkspaceCommand,
    ) {
        validateExpectedProblemDraftEditWorkspace(command.workspace)
        val suffix = confirmationStableSuffix(command.workspace)
        requireContract(command.sessionId == "tutor-session-$suffix") {
            "Workspace tutor-session identity is not deterministic"
        }
    }

    fun confirmationStableSuffix(expected: ExpectedProblemDraftEditWorkspace): String {
        val canonical = listOf(
            expected.draftId,
            expected.basisRevisionNumber.toString(),
            expected.workspaceVersion.toString(),
            expected.workspaceFingerprint,
            expected.finalRequestId,
            expected.finalOccurredAtEpochMillis.toString(),
        ).joinToString(separator = "\u001F")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .take(16)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun decodeProblemDraftEditWorkspace(
        snapshotSchemaVersion: Int,
        workspaceSnapshot: String,
        workspaceFingerprint: String,
    ): CaptureDraftWorkspace {
        requireContract(snapshotSchemaVersion == CaptureDraftWorkspace.CURRENT_SCHEMA_VERSION) {
            "Unsupported problem-draft workspace schema"
        }
        requireContract(workspaceSnapshot.length <= CaptureDraftWorkspaceCodec.MAX_ENCODED_CHARS) {
            "Problem-draft workspace exceeds the snapshot budget"
        }
        requireContract(sha256.matches(workspaceFingerprint)) {
            "Problem-draft workspace fingerprint is invalid"
        }
        requireContract(
            workspaceFingerprint == CaptureDraftWorkspaceFingerprint.ofEncoded(workspaceSnapshot),
        ) { "Problem-draft workspace fingerprint mismatch" }
        val workspace = try {
            CaptureDraftWorkspaceCodec.decode(workspaceSnapshot)
        } catch (failure: Exception) {
            throw DatabaseContractViolationException(
                "Problem-draft workspace snapshot is invalid: ${failure.message ?: "decode failed"}",
            )
        }
        requireContract(workspace.schemaVersion == snapshotSchemaVersion) {
            "Problem-draft workspace schema columns disagree"
        }
        return workspace
    }

    fun validateCommitProblemDraft(command: CommitProblemDraftCommand) {
        id(command.commandId, "commandId")
        id(command.draftId, "draftId")
        positive(command.expectedRevisionNumber, "expectedRevisionNumber")
        id(command.problemId, "problemId")
        id(command.problemRevisionId, "problemRevisionId")
        id(command.practiceUnitId, "practiceUnitId")
        id(command.errorBookEntryId, "errorBookEntryId")
        positive(command.estimatedSeconds, "estimatedSeconds")
        nonNegative(command.committedAtEpochMillis, "committedAtEpochMillis")
    }

    fun validateConfirmTutorSession(command: ConfirmTutorSessionCommand) {
        id(command.sessionId, "sessionId")
        id(command.draftId, "draftId")
        nonNegative(command.createdAtEpochMillis, "createdAtEpochMillis")
        validateReviseProblemDraft(
            ReviseProblemDraftCommand(
                draftId = command.draftId,
                expectedRevisionNumber = command.expectedRevisionNumber,
                revision = command.confirmedRevision,
            ),
        )
        requireContract(command.confirmedRevision.author == StudyDbValue.ProblemDraftAuthor.USER) {
            "Tutor sessions must be based on a user-confirmed revision"
        }
        requireContract(command.confirmedRevision.subject != null) {
            "Tutor sessions require a confirmed subject"
        }
        requireContract(
            CapturedQuestionDocumentValidator.validateForCommit(
                command.confirmedRevision.questionDocument,
            ).isEmpty(),
        ) { "Tutor-session question document is not ready for use" }
        requireContract(command.createdAtEpochMillis == command.confirmedRevision.createdAtEpochMillis) {
            "Tutor-session creation time must match its confirmed revision"
        }
    }

    fun validateCommitTutorSession(command: CommitTutorSessionCommand) {
        id(command.sessionId, "sessionId")
        validateCommitProblemDraft(command.commit)
    }

    fun validateEndTutorSession(command: EndTutorSessionCommand) {
        id(command.sessionId, "sessionId")
        nonNegative(command.endedAtEpochMillis, "endedAtEpochMillis")
    }

    private fun validateSourceAsset(asset: CanonicalSourceAssetRecord) {
        id(asset.sourceAssetId, "sourceAssetId")
        requireContract(sha256.matches(asset.contentSha256)) { "Invalid source asset SHA-256" }
        requireContract(asset.mimeType in setOf("image/jpeg", "image/png")) {
            "Canonical assets must be JPEG or PNG"
        }
        val expectedExtension = if (asset.mimeType == "image/png") "png" else "jpg"
        requireContract(
            asset.relativePath == "source-assets/${asset.contentSha256}.$expectedExtension",
        ) { "Source asset path must be derived from its content hash and MIME type" }
        requireContract(asset.byteSize in 1L..MAX_CANONICAL_ASSET_BYTES) {
            "Canonical source asset exceeds byte budget"
        }
        requireContract(asset.width in 1..MAX_CANONICAL_DIMENSION) {
            "Canonical source asset width is invalid"
        }
        requireContract(asset.height in 1..MAX_CANONICAL_DIMENSION) {
            "Canonical source asset height is invalid"
        }
        requireContract(asset.width.toLong() * asset.height <= MAX_CANONICAL_PIXELS) {
            "Canonical source asset exceeds pixel budget"
        }
        known(asset.sourceType, "sourceAssetType", sourceAssetTypes)
        nonNegative(asset.createdAtEpochMillis, "createdAtEpochMillis")
    }

    private fun validateDraftRevision(
        revision: ProblemDraftRevisionRecord,
        expectedSourceAssetId: String?,
    ) {
        id(revision.draftId, "draftId")
        positive(revision.revisionNumber, "revisionNumber")
        revision.basisRevisionNumber?.let { positive(it, "basisRevisionNumber") }
        revision.subject?.let { known(it, "subject", subjects) }
        text(revision.title, "title", 4_000)
        known(revision.author, "problemDraftAuthor", problemDraftAuthors)
        nonNegative(revision.createdAtEpochMillis, "createdAtEpochMillis")
        requireContract(CapturedQuestionDocumentValidator.validateDraft(revision.questionDocument).isEmpty()) {
            "Draft question document is invalid"
        }
        requireContract(
            revision.documentFingerprint ==
                CapturedQuestionDocumentFingerprint.of(revision.questionDocument),
        ) { "Draft question document fingerprint mismatch" }
        expectedSourceAssetId?.let { expected ->
            requireContract(revision.questionDocument.blockEvidence.all { it.sourceAssetId == expected }) {
                "Initial draft evidence must reference its canonical source asset"
            }
        }
    }

    fun validateAttempt(command: AttemptWriteCommand) {
        id(command.learnerId, "learnerId")
        id(command.submissionId, "submissionId")
        id(command.attemptId, "attemptId")
        id(command.presentationId, "presentationId")
        id(command.assessmentSnapshotId, "assessmentSnapshotId")
        id(command.submittedResponse.choiceId, "submittedResponse.choiceId")
        text(
            command.submittedResponse.choiceMarkdown,
            "submittedResponse.choiceMarkdown",
            MAX_RENDERABLE_MARKDOWN_CHARS,
        )
        nonNegative(
            command.submittedResponse.submittedAtEpochMillis,
            "submittedResponse.submittedAtEpochMillis",
        )
        nonNegative(command.occurredAtEpochMillis, "occurredAtEpochMillis")
        requireContract(
            command.submittedResponse.submittedAtEpochMillis == command.occurredAtEpochMillis,
        ) { "Submitted response time must match occurredAtEpochMillis" }
        nonNegative(command.durationSeconds, "durationSeconds")
        validateStudyDay(command.occurredAtEpochMillis, command.studyDay)
    }

    fun validateAssessmentEvidenceSnapshot(snapshot: com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot) {
        requireContract(snapshot.verification == AssessmentSnapshotVerification.VERIFIED) {
            "Only VERIFIED assessment evidence snapshots may be persisted"
        }
        id(snapshot.snapshotId, "snapshotId")
        id(snapshot.assessmentItemId, "assessmentItemId")
        id(snapshot.practiceUnitId, "practiceUnitId")
        id(snapshot.problemRevisionId, "problemRevisionId")
        id(snapshot.answerSpecId, "answerSpecId")
        id(snapshot.itemFamilyId, "itemFamilyId")
        snapshot.sourceBundleId?.let { id(it, "sourceBundleId") }
        id(snapshot.taxonomyVersion, "taxonomyVersion")
        id(snapshot.calibration.sourceId, "calibration.sourceId")
        id(snapshot.calibration.version, "calibration.version")
        requireContract(
            snapshot.attributions.isNotEmpty() || LocalReviewSelfReportContract.matches(snapshot),
        ) {
            "A persisted assessment evidence snapshot needs attributions or the local review self-report contract"
        }
        snapshot.attributions.forEach { attribution ->
            id(attribution.bindingId, "attribution.bindingId")
            id(attribution.knowledgeNodeId, "attribution.knowledgeNodeId")
            finiteRange(attribution.weight, "attribution.weight", Double.MIN_VALUE, 1.0)
            requireContract(
                attribution.basisRevisionId == snapshot.problemRevisionId &&
                    attribution.taxonomyVersion == snapshot.taxonomyVersion,
            ) { "Attribution basis must match its assessment snapshot" }
        }
    }

    fun validateAnswerReveal(command: AnswerRevealWriteCommand) {
        id(command.learnerId, "learnerId")
        id(command.assessmentEventId, "assessmentEventId")
        id(command.presentationId, "presentationId")
        id(command.assessmentSnapshotId, "assessmentSnapshotId")
        text(command.contentMarkdown, "contentMarkdown", 512_000)
        nonNegative(command.occurredAtEpochMillis, "occurredAtEpochMillis")
        validateStudyDay(command.occurredAtEpochMillis, command.studyDay)
    }

    fun validateCorrection(correction: AttemptCorrectionRecord) {
        id(correction.learnerId, "learnerId")
        id(correction.submissionId, "submissionId")
        id(correction.correctionId, "correctionId")
        id(correction.attemptId, "attemptId")
        text(correction.reasonMarkdown, "reasonMarkdown", 32_000)
        nonNegative(correction.occurredAtEpochMillis, "occurredAtEpochMillis")
        constructCorrection(correction, eventSequence = 1)
    }

    fun validateCorrectionAgainstAttempt(
        correction: AttemptCorrectionRecord,
        target: Attempt,
    ) {
        requireContract(correction.learnerId.isNotBlank()) { "learnerId is blank" }
        requireContract(correction.attemptId == target.attemptId) {
            "Correction target does not match the loaded attempt"
        }
        if (target.responseOrdinal > 1) {
            requireContract(!correction.replacementEvidence.isIndependent) {
                "A later response cannot be corrected into independent evidence"
            }
        }
        target.copy(
            evidence = correction.replacementEvidence,
            problemMemoryOutcome = correction.replacementMemoryOutcome,
        )
    }

    fun constructAttempt(
        command: AttemptWriteCommand,
        assessmentSnapshot: com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot,
        responseOrdinal: Int,
        eventSequence: Long,
    ): Attempt = Attempt(
        attemptId = command.attemptId,
        presentationId = command.presentationId,
        responseOrdinal = responseOrdinal,
        assessmentSnapshot = assessmentSnapshot,
        evidence = command.evidence,
        problemMemoryOutcome = command.problemMemoryOutcome,
        occurredAtEpochMillis = command.occurredAtEpochMillis,
        durationSeconds = command.durationSeconds,
        studyDay = command.studyDay,
        eventSequence = eventSequence,
        submittedResponse = command.submittedResponse,
    )

    fun constructAnswerReveal(
        command: AnswerRevealWriteCommand,
        assessmentSnapshot: com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot,
        eventSequence: Long,
    ) = AnswerRevealOutcome(
        outcomeId = answerRevealOutcomeId(command.assessmentEventId),
        presentationId = command.presentationId,
        assessmentSnapshot = assessmentSnapshot,
        occurredAtEpochMillis = command.occurredAtEpochMillis,
        studyDay = command.studyDay,
        eventSequence = eventSequence,
    )

    fun answerRevealOutcomeId(assessmentEventId: String): String =
        "answer-reveal-outcome:$assessmentEventId"

    fun constructCorrection(
        correction: AttemptCorrectionRecord,
        eventSequence: Long,
    ): AttemptCorrection = AttemptCorrection(
        correctionId = correction.correctionId,
        attemptId = correction.attemptId,
        replacementEvidence = correction.replacementEvidence,
        replacementMemoryOutcome = correction.replacementMemoryOutcome,
        reasonMarkdown = correction.reasonMarkdown,
        occurredAtEpochMillis = correction.occurredAtEpochMillis,
        eventSequence = eventSequence,
    )

    fun validateAssessmentItem(item: AssessmentItemSnapshotSeedRecord) {
        id(item.assessmentItemSnapshotId, "assessmentItemSnapshotId")
        positive(item.itemRevision, "itemRevision")
        requireContract((item.practiceUnitId == null) == (item.problemRevisionId == null)) {
            "Assessment practiceUnitId and problemRevisionId must both be present or absent"
        }
        item.practiceUnitId?.let { id(it, "practiceUnitId") }
        item.problemRevisionId?.let { id(it, "problemRevisionId") }
        item.tutorContentSnapshotId?.let { id(it, "tutorContentSnapshotId") }
        requireContract(item.practiceUnitId != null || item.tutorContentSnapshotId != null) {
            "Assessment requires a saved practice unit or tutor content snapshot"
        }
        text(item.promptMarkdown, "promptMarkdown", MAX_RENDERABLE_MARKDOWN_CHARS)
        text(item.optionsSnapshot, "optionsSnapshot", 512_000)
        text(item.answerSpecSnapshot, "answerSpecSnapshot", 512_000)
        known(item.verificationStatus, "verificationStatus", verificationStatuses)
        known(item.assessmentEligibility, "assessmentEligibility", assessmentEligibilities)
        known(item.scoringMode, "scoringMode", scoringModes)
        id(item.learnerSnapshotVersion, "learnerSnapshotVersion")
        nonNegative(item.projectionCheckpoint, "projectionCheckpoint")
        nonNegative(item.hintLevelAtPresentation, "hintLevelAtPresentation")
        stableToken(item.answerRevealState, "answerRevealState")
        nonNegative(item.createdAtEpochMillis, "createdAtEpochMillis")
    }

    fun validateAssessmentEvent(event: AssessmentEventSeedRecord) {
        id(event.assessmentEventId, "assessmentEventId")
        id(event.assessmentItemSnapshotId, "assessmentItemSnapshotId")
        positive(event.eventSequence, "eventSequence")
        known(event.eventType, "eventType", assessmentEventTypes)
        requireContract(event.eventType != StudyDbValue.AssessmentEventType.ANSWER_REVEALED) {
            "ANSWER_REVEALED must use the atomic recordAnswerReveal transaction"
        }
        event.hintLevel?.let { nonNegative(it, "hintLevel") }
        event.submittedResponse?.let { text(it, "submittedResponse", 128_000) }
        nonNegative(event.occurredAtEpochMillis, "occurredAtEpochMillis")
    }

    fun validateReviewBundle(bundle: ReviewPlanBundle) {
        val plan = bundle.plan
        id(plan.reviewPlanId, "reviewPlanId")
        id(plan.learnerId, "learnerId")
        requireContract(localDate.matches(plan.localDate)) { "localDate must be YYYY-MM-DD" }
        requireContract(plan.localDate == LocalDate.ofEpochDay(plan.localDayEpochDay).toString()) {
            "localDate must match localDayEpochDay"
        }
        requireContract(isKnownZone(plan.timeZoneId)) { "Unknown timeZoneId" }
        positive(plan.timeBudgetSeconds, "timeBudgetSeconds")
        nonNegative(plan.planningAtEpochMillis, "planningAtEpochMillis")
        known(plan.status, "plan.status", reviewStatuses)
        id(plan.plannerVersion, "plannerVersion")
        nonNegative(plan.projectionCheckpoint, "projectionCheckpoint")
        id(plan.inputFingerprint, "inputFingerprint")
        id(plan.planFingerprint, "planFingerprint")
        requireContract(plan.reviewPlanId == "plan-${plan.planFingerprint}") {
            "reviewPlanId must use the domain-provided full plan fingerprint"
        }
        positive(plan.planRevision, "planRevision")
        nonNegative(plan.createdAtEpochMillis, "createdAtEpochMillis")
        requireContract(bundle.queue.map { it.ordinal } == bundle.queue.indices.toList()) {
            "Review queue ordinals must be contiguous and start at zero"
        }
        requireContract(bundle.queue.size <= MAX_REVIEW_QUEUE_ITEMS) {
            "Review queue exceeds $MAX_REVIEW_QUEUE_ITEMS items"
        }
        requireContract(bundle.queue.map { it.practiceUnitId }.distinct().size == bundle.queue.size) {
            "Review queue cannot contain duplicate practice units"
        }
        val totalEstimatedSeconds = bundle.queue.fold(0L) { total, item ->
            Math.addExact(total, item.estimatedSeconds.toLong())
        }
        requireContract(totalEstimatedSeconds <= plan.timeBudgetSeconds.toLong()) {
            "Review queue exceeds its plan time budget"
        }
        bundle.queue.forEach { item ->
            id(item.reviewQueueItemId, "reviewQueueItemId")
            requireContract(item.reviewPlanId == plan.reviewPlanId) {
                "Review queue item belongs to another plan"
            }
            id(item.practiceUnitId, "practiceUnitId")
            requireContract(item.knowledgeNodeIds.isNotEmpty()) {
                "Review queue item needs at least one frozen knowledge-node id"
            }
            item.knowledgeNodeIds.forEach { id(it, "knowledgeNodeId") }
            id(item.itemFamilyId, "itemFamilyId")
            item.sourceBundleId?.let { id(it, "sourceBundleId") }
            requireContract(item.reasons.isNotEmpty()) { "Review queue item needs a reason" }
            item.reasons.forEach { reason ->
                known(reason, "reviewReason", enumValues<ReviewReason>().map { it.name }.toSet())
            }
            nonNegative(item.ordinal, "ordinal")
            finiteRange(item.priorityScore, "priorityScore", 0.0, Double.MAX_VALUE)
            item.dueAtEpochMillis?.let { nonNegative(it, "dueAtEpochMillis") }
            known(
                item.difficultyBand,
                "difficultyBand",
                enumValues<ReviewDifficultyBand>().map { it.name }.toSet(),
            )
            positive(item.estimatedSeconds, "estimatedSeconds")
            text(item.reasonSnapshot, "reasonSnapshot", 32_000)
            known(item.status, "queue.status", reviewStatuses)
        }
        bundle.activeSession?.let { session ->
            requireContract(session.reviewPlanId == plan.reviewPlanId) {
                "Review session belongs to another plan"
            }
            validateReviewSession(session)
        }
        bundle.latestSession?.let { session ->
            requireContract(session.reviewPlanId == plan.reviewPlanId) {
                "Latest review session belongs to another plan"
            }
            validateReviewSession(session)
        }
    }

    fun validateReviewSession(session: ReviewSessionRecord) {
        id(session.reviewSessionId, "reviewSessionId")
        id(session.reviewPlanId, "reviewPlanId")
        known(session.status, "session.status", reviewSessionStatuses)
        nonNegative(session.startedAtEpochMillis, "startedAtEpochMillis")
        nonNegative(session.lastActiveAtEpochMillis, "lastActiveAtEpochMillis")
        requireContract(session.lastActiveAtEpochMillis >= session.startedAtEpochMillis) {
            "lastActiveAtEpochMillis precedes session start"
        }
        session.completedAtEpochMillis?.let {
            nonNegative(it, "completedAtEpochMillis")
            requireContract(it >= session.lastActiveAtEpochMillis) {
                "completedAtEpochMillis precedes the last active time"
            }
        }
        nonNegative(session.currentOrdinal, "currentOrdinal")
        positive(session.timeBudgetSeconds, "session.timeBudgetSeconds")
        nonNegative(session.projectionCheckpoint, "session.projectionCheckpoint")
        nonNegative(session.stateVersion, "session.stateVersion")
        requireContract(session.stateVersion == session.currentOrdinal.toLong()) {
            "Review-session stateVersion must equal currentOrdinal"
        }
        val completed = session.status == StudyDbValue.ReviewStatus.COMPLETED
        requireContract(completed == (session.completedAtEpochMillis != null)) {
            "Only a completed review session may have completedAtEpochMillis"
        }
        requireContract(!completed || session.completedAtEpochMillis == session.lastActiveAtEpochMillis) {
            "Completed review-session time must equal its last active time"
        }
    }

    fun validateReviewSessionCreation(session: ReviewSessionRecord) {
        validateReviewSession(session)
        requireContract(session.stateVersion == 0L) {
            "A review session must be created at stateVersion zero"
        }
        requireContract(session.currentOrdinal == 0) {
            "A review session must be created at ordinal zero"
        }
        requireContract(session.status == StudyDbValue.ReviewStatus.IN_PROGRESS) {
            "A review session must be created in progress"
        }
        requireContract(session.lastActiveAtEpochMillis == session.startedAtEpochMillis) {
            "A new review session cannot already contain activity"
        }
    }

    fun validateReviewSessionAdvance(command: ReviewSessionAdvanceCommand) {
        id(command.sessionId, "sessionId")
        nonNegative(command.expectedStateVersion, "expectedStateVersion")
        id(command.reviewQueueItemId, "reviewQueueItemId")
        id(command.practiceUnitId, "practiceUnitId")
        id(command.attemptId, "attemptId")
        id(command.submissionId, "submissionId")
        id(command.presentationId, "presentationId")
        nonNegative(command.occurredAtEpochMillis, "occurredAtEpochMillis")
    }

    fun validateProjectionRequest(projectionName: String, learnerId: String, limit: Int) {
        id(projectionName, "projectionName")
        id(learnerId, "learnerId")
        requireContract(limit in 1..MAX_PROJECTION_BATCH_SIZE) {
            "limit must be between 1 and $MAX_PROJECTION_BATCH_SIZE"
        }
    }

    fun validateLedgerRequest(learnerId: String) = id(learnerId, "learnerId")

    fun validateProjectionCommit(commit: ProjectionCommit) {
        id(commit.projectionName, "projectionName")
        id(commit.learnerId, "learnerId")
        nonNegative(commit.expectedPreviousCheckpoint, "expectedPreviousCheckpoint")
        nonNegative(commit.expectedPreviousStateVersion, "expectedPreviousStateVersion")
        nonNegative(commit.knownLedgerHeadSequence, "knownLedgerHeadSequence")
        requireContract(
            commit.knownLedgerHeadSequence == commit.snapshot.knownLedgerHeadSequence,
        ) { "Projection commit and snapshot known ledger heads differ" }
        requireContract(commit.knownLedgerHeadSequence >= commit.snapshot.checkpoint.lastSequence) {
            "Known ledger head cannot precede the committed checkpoint"
        }
        if (commit.snapshot.projectionStatus == ProjectionStatus.CURRENT) {
            requireContract(
                commit.snapshot.checkpoint.lastSequence == commit.knownLedgerHeadSequence,
            ) { "A CURRENT snapshot must have projected its confirmed ledger head" }
        }
        requireContract(commit.snapshot.learnerId == commit.learnerId) {
            "Snapshot learner differs from projection commit learner"
        }
        requireContract(commit.snapshot.appliedAttemptRecords.size <= MAX_APPLIED_ATTEMPT_RECORDS) {
            "Snapshot exceeds the bounded applied-attempt record limit"
        }
        requireContract(
                commit.snapshot.appliedCorrectionRecords.size <= MAX_APPLIED_ATTEMPT_RECORDS &&
                commit.snapshot.appliedAnswerRevealRecords.size <= MAX_APPLIED_ATTEMPT_RECORDS &&
                commit.snapshot.appliedTutorAnswerExposureRecords.size <= MAX_APPLIED_ATTEMPT_RECORDS &&
                commit.snapshot.appliedLearningObservationRecords.size <= MAX_APPLIED_ATTEMPT_RECORDS,
        ) { "Snapshot exceeds the bounded applied-event record limit" }
        if (commit.mode == ProjectionCommitMode.INCREMENTAL) {
            requireContract(commit.consumedLedgerEvents.size <= MAX_PROJECTION_BATCH_SIZE) {
                "Incremental projection commit exceeds the maximum batch size"
            }
            requireContract(
                commit.consumedLedgerEvents.all {
                    it.eventKind == "ATTEMPT" ||
                        it.eventKind == "ANSWER_REVEAL_OUTCOME" ||
                        it.eventKind == "TUTOR_ANSWER_EXPOSURE_OUTCOME" ||
                        it.eventKind == "ATTRIBUTED_LEARNING_OBSERVATION"
                },
            ) {
                "Incremental projection cannot consume corrections"
            }
        }
        requireContract(
            commit.consumedLedgerEvents.map { it.eventKind to it.eventId }.distinct().size ==
                commit.consumedLedgerEvents.size,
        ) {
            "Consumed ledger event identities must be unique"
        }
        val expectedSequences = if (commit.consumedLedgerEvents.isEmpty()) {
            emptyList()
        } else {
            (commit.expectedPreviousCheckpoint + 1..commit.snapshot.checkpoint.lastSequence).toList()
        }
        requireContract(commit.consumedLedgerEvents.map { it.eventSequence } == expectedSequences) {
            "Consumed ledger events must be the exact contiguous sequence after the previous checkpoint"
        }
        requireContract(
            commit.snapshot.checkpoint.lastSequence ==
                (commit.consumedLedgerEvents.lastOrNull()?.eventSequence ?: commit.expectedPreviousCheckpoint),
        ) { "Snapshot checkpoint does not match its consumed ledger prefix" }
        requireContract(
            commit.presentationProjectionStates.all { (presentationId, state) ->
                presentationId == state.presentationId &&
                    state.asOfLedgerSequence == commit.snapshot.checkpoint.lastSequence
            },
        ) { "Presentation authority ids/watermarks must match the committed checkpoint" }
        commit.presentationProjectionStates.keys.forEach { id(it, "presentationId") }
        commit.consumedLedgerEvents.forEach { receipt ->
            requireContract(
                receipt.eventKind == "ATTEMPT" ||
                    receipt.eventKind == "ATTEMPT_CORRECTION" ||
                    receipt.eventKind == "ANSWER_REVEAL_OUTCOME" ||
                    receipt.eventKind == "TUTOR_ANSWER_EXPOSURE_OUTCOME" ||
                    receipt.eventKind == "ATTRIBUTED_LEARNING_OBSERVATION",
            ) {
                "Unknown ledger event kind"
            }
            id(receipt.eventId, "eventId")
            id(receipt.canonicalFingerprint, "canonicalFingerprint")
            if (receipt.eventKind == "ATTEMPT") {
                commit.snapshot.appliedAttemptRecords[receipt.eventId]?.let { applied ->
                    requireContract(
                        applied.eventSequence == receipt.eventSequence &&
                            applied.canonicalFingerprint == receipt.canonicalFingerprint,
                    ) { "Snapshot applied-attempt receipt differs for ${receipt.eventId}" }
                }
            }
        }
        known(commit.snapshot.freshness.name, "snapshot.freshness", enumValues<LearnerSnapshotFreshness>().map { it.name }.toSet())
        known(commit.snapshot.projectionStatus.name, "snapshot.projectionStatus", enumValues<ProjectionStatus>().map { it.name }.toSet())
    }

    fun validateSeedBundle(bundle: StudySeedBundle) {
        bundle.problems.forEach {
            id(it.problemId, "problemId")
            id(it.canonicalFingerprint, "canonicalFingerprint")
            known(it.subject, "subject", subjects)
            nonNegative(it.createdAtEpochMillis, "createdAtEpochMillis")
        }
        bundle.revisions.forEach {
            id(it.revisionId, "revisionId")
            id(it.problemId, "problemId")
            positive(it.revisionNumber, "revisionNumber")
            text(it.title, "title", 4_000)
            text(it.problemMarkdown, "problemMarkdown", MAX_RENDERABLE_MARKDOWN_CHARS)
            it.answerSpecId?.let { value -> id(value, "answerSpecId") }
            known(it.answerVerificationStatus, "answerVerificationStatus", verificationStatuses)
            stableToken(it.sourceType, "sourceType")
            id(it.contentFingerprint, "contentFingerprint")
            nonNegative(it.createdAtEpochMillis, "createdAtEpochMillis")
        }
        bundle.practiceUnits.forEach {
            id(it.practiceUnitId, "practiceUnitId")
            id(it.problemId, "problemId")
            id(it.problemRevisionId, "problemRevisionId")
            id(it.unitKey, "unitKey")
            stableToken(it.unitKind, "unitKind")
            text(it.title, "title", 4_000)
            text(it.promptMarkdown, "promptMarkdown", MAX_RENDERABLE_MARKDOWN_CHARS)
            positive(it.estimatedSeconds, "estimatedSeconds")
            nonNegative(it.createdAtEpochMillis, "createdAtEpochMillis")
        }
        bundle.errorBookEntries.forEach {
            id(it.entryId, "entryId")
            id(it.practiceUnitId, "practiceUnitId")
            id(it.problemId, "problemId")
            id(it.currentRevisionId, "currentRevisionId")
            it.sourceKey?.let { value -> id(value, "sourceKey") }
            known(it.status, "errorBookStatus", errorBookStatuses)
            nonNegative(it.acceptedAtEpochMillis, "acceptedAtEpochMillis")
            nonNegative(it.updatedAtEpochMillis, "updatedAtEpochMillis")
        }
        bundle.knowledgeNodes.forEach {
            id(it.knowledgeNodeId, "knowledgeNodeId")
            id(it.stableCode, "stableCode")
            known(it.subject, "subject", subjects)
            text(it.displayName, "displayName", 4_000)
            it.parentKnowledgeNodeId?.let { value -> id(value, "parentKnowledgeNodeId") }
            id(it.taxonomyVersion, "taxonomyVersion")
        }
        bundle.knowledgeBindings.forEach {
            id(it.bindingId, "bindingId")
            id(it.practiceUnitId, "practiceUnitId")
            id(it.knowledgeNodeId, "knowledgeNodeId")
            id(it.basisRevisionId, "basisRevisionId")
            finiteRange(it.strength, "binding.strength", 0.0, 1.0)
            stableToken(it.sourceType, "binding.sourceType")
            id(it.taxonomyVersion, "taxonomyVersion")
        }
        bundle.relations.forEach {
            id(it.relationId, "relationId")
            id(it.sourceProblemId, "sourceProblemId")
            id(it.targetProblemId, "targetProblemId")
            requireContract(it.sourceProblemId != it.targetProblemId) {
                "A problem relation cannot target itself"
            }
            known(it.relationType, "relationType", relationTypes)
            known(it.status, "relationStatus", relationStatuses)
            id(it.sourceBasisRevisionId, "sourceBasisRevisionId")
            id(it.targetBasisRevisionId, "targetBasisRevisionId")
            finiteRange(it.confidence, "relation.confidence", 0.0, 1.0)
        }
        bundle.assessmentItems.forEach(::validateAssessmentItem)
        bundle.assessmentEvents.forEach(::validateAssessmentEvent)
        bundle.problemMemoryStates.forEach {
            finiteRange(it.stabilityDays, "stabilityDays", 0.000_001, Double.MAX_VALUE)
            finiteRange(it.difficulty, "difficulty", 0.0, 1.0)
            finiteRange(it.retrievability, "retrievability", 0.0, 1.0)
        }
        bundle.knowledgeMasteryStates.forEach {
            finiteRange(it.masteryProbability, "masteryProbability", 0.0, 1.0)
            finiteRange(it.evidenceWeightTotal, "evidenceWeightTotal", 0.0, Double.MAX_VALUE)
        }
        bundle.reviewSessions.forEach { session ->
            validateReviewSessionCreation(session)
        }
        if (bundle.reviewPlans.isNotEmpty() || bundle.reviewQueueItems.isNotEmpty() ||
            bundle.reviewSessions.isNotEmpty()
        ) {
            val queues = bundle.reviewQueueItems.groupBy { it.reviewPlanId }
            val sessions = bundle.reviewSessions.groupBy { it.reviewPlanId }
            bundle.reviewPlans.forEach { plan ->
                validateReviewBundle(
                    ReviewPlanBundle(
                        plan = plan,
                        queue = queues[plan.reviewPlanId].orEmpty().sortedBy { it.ordinal },
                        activeSession = sessions[plan.reviewPlanId].orEmpty()
                            .singleOrNull { it.status == StudyDbValue.ReviewStatus.IN_PROGRESS },
                    ),
                )
            }
        }
    }

    fun verifyCanonicalFingerprint(event: com.tingyun.smartmistakebook.core.model.LearningLedgerEvent, stored: String) {
        requireContract(LearningLedgerFingerprint.event(event) == stored) {
            "Stored canonical fingerprint differs from the public learning-core fingerprint"
        }
    }

    private fun validateStudyDay(
        occurredAtEpochMillis: Long,
        studyDay: com.tingyun.smartmistakebook.core.model.StudyDayContext,
    ) {
        val zone = try {
            ZoneId.of(studyDay.timeZoneId)
        } catch (_: DateTimeException) {
            throw DatabaseContractViolationException("Unknown studyDay.timeZoneId")
        }
        val local = Instant.ofEpochMilli(occurredAtEpochMillis).atZone(zone)
        requireContract(local.toLocalDate().toEpochDay() == studyDay.epochDay) {
            "Study-day epochDay does not contain occurredAt in its time zone"
        }
        requireContract(local.offset.totalSeconds % 60 == 0) {
            "Study-day UTC offset is not minute-aligned"
        }
        requireContract(local.offset.totalSeconds / 60 == studyDay.utcOffsetMinutes) {
            "Study-day UTC offset does not match occurredAt in its time zone"
        }
    }

    private fun isKnownZone(value: String): Boolean = try {
        ZoneId.of(value)
        true
    } catch (_: DateTimeException) {
        false
    }

    private fun id(value: String, name: String) {
        requireContract(value.isNotBlank() && value == value.trim() && value.length <= 256) {
            "$name must be a trimmed non-blank string of at most 256 characters"
        }
        requireContract(value.none(Char::isISOControl)) { "$name contains control characters" }
    }

    private fun text(value: String, name: String, maxLength: Int) {
        requireContract(value.isNotBlank() && value.length <= maxLength) {
            "$name must be non-blank and at most $maxLength characters"
        }
        requireContract(
            value.none { character ->
                (character.isISOControl() && character !in "\n\r\t") ||
                    character.isBidirectionalControl()
            },
        ) { "$name contains unsupported control characters" }
    }

    private fun Char.isBidirectionalControl(): Boolean =
        this == '\u061C' || this == '\u200E' || this == '\u200F' ||
            this in '\u202A'..'\u202E' || this in '\u2066'..'\u2069'

    private fun stableToken(value: String, name: String) {
        requireContract(stableToken.matches(value)) { "$name is not a stable storage token" }
    }

    private fun known(value: String, name: String, allowed: Set<String>) {
        requireContract(value in allowed) { "$name has unknown value '$value'" }
    }

    private fun finiteRange(value: Double, name: String, minimum: Double, maximum: Double) {
        requireContract(value.isFinite() && value in minimum..maximum) {
            "$name is outside $minimum..$maximum or is not finite"
        }
    }

    private fun nonNegative(value: Long, name: String) {
        requireContract(value >= 0) { "$name cannot be negative" }
    }

    private fun nonNegative(value: Int, name: String) {
        requireContract(value >= 0) { "$name cannot be negative" }
    }

    private fun positive(value: Int, name: String) {
        requireContract(value > 0) { "$name must be positive" }
    }

    private fun positive(value: Long, name: String) {
        requireContract(value > 0) { "$name must be positive" }
    }

    private inline fun requireContract(condition: Boolean, message: () -> String) {
        if (!condition) throw DatabaseContractViolationException(message())
    }
}
