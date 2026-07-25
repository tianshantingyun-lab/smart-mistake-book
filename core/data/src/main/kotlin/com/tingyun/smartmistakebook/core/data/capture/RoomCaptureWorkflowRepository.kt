package com.tingyun.smartmistakebook.core.data.capture

import android.content.Context
import android.net.Uri
import com.tingyun.smartmistakebook.core.database.AppendProblemDraftSourceAssetCommand
import com.tingyun.smartmistakebook.core.database.CommitProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.CommitTutorSessionCommand
import com.tingyun.smartmistakebook.core.database.ConfirmAndCommitProblemDraftFromWorkspaceCommand
import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.ConsumeProblemDraftEditWorkspaceCommand
import com.tingyun.smartmistakebook.core.database.ConfirmTutorSessionFromWorkspaceCommand
import com.tingyun.smartmistakebook.core.database.CreateProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.EndTutorSessionCommand
import com.tingyun.smartmistakebook.core.database.ExpectedProblemDraftEditWorkspace
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.ProblemDraftRevisionRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftEditWorkspaceRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftWriteResult
import com.tingyun.smartmistakebook.core.database.PendingCaptureDraftRecord
import com.tingyun.smartmistakebook.core.database.ReviseProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.ReplaceProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.SaveProblemDraftEditWorkspaceCommand
import com.tingyun.smartmistakebook.core.database.SplitProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.TutorSessionRecord
import com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest
import com.tingyun.smartmistakebook.core.domain.AppendCaptureDraftPageRequest
import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceIdentity
import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceSnapshot
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSplitResult
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionState
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionSummary
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.domain.CapturedProblemCommitSummary
import com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.ConsumeCaptureDraftWorkspaceRequest
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveResult
import com.tingyun.smartmistakebook.core.domain.PendingCaptureItem
import com.tingyun.smartmistakebook.core.domain.PendingCaptureStage
import com.tingyun.smartmistakebook.core.domain.ResumableCaptureDraft
import com.tingyun.smartmistakebook.core.domain.ReplaceCaptureDraftRequest
import com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest
import com.tingyun.smartmistakebook.core.domain.SaveCaptureDraftWorkspaceRequest
import com.tingyun.smartmistakebook.core.domain.SplitCaptureDraftRequest
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspaceCodec
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspaceFingerprint
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.StructuredContentLimits
import com.tingyun.smartmistakebook.core.model.WritingLayer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class RoomCaptureWorkflowRepository internal constructor(
    private val database: StudyDatabasePort,
    private val assetVault: AndroidCanonicalAssetVault,
    private val localTextRecognizer: LocalQuestionTextRecognizer,
) : CaptureWorkflowRepository {
    override fun observePendingCaptures(): Flow<List<PendingCaptureItem>> =
        database.observePendingCaptureDrafts().map { records ->
            records.map { record -> record.toPendingCaptureItem() }
        }.flowOn(Dispatchers.IO)

    override suspend fun readPendingCapture(draftId: String): ResumableCaptureDraft? =
        withContext(Dispatchers.IO) {
            require(draftId.isNotBlank()) { "Pending capture draft id must not be blank" }
            database.readPendingCaptureDraft(draftId)?.toResumableCaptureDraft()
        }

    override suspend fun readDraftWorkspace(
        draftId: String,
    ): CaptureDraftWorkspaceSnapshot? = withContext(Dispatchers.IO) {
        require(draftId.isNotBlank()) { "Capture workspace draft id must not be blank" }
        database.readProblemDraftEditWorkspace(draftId)?.toDomainWorkspaceSnapshot()
    }

    override suspend fun saveDraftWorkspace(
        request: SaveCaptureDraftWorkspaceRequest,
    ): CaptureDraftWorkspaceSnapshot = withContext(Dispatchers.IO) {
        val encoded = CaptureDraftWorkspaceCodec.encode(request.workspace)
        val result = database.saveProblemDraftEditWorkspace(
            SaveProblemDraftEditWorkspaceCommand(
                draftId = request.draftId,
                basisRevisionNumber = request.basisRevisionNumber,
                expectedWorkspaceVersion = request.expectedWorkspaceVersion,
                expectedWorkspaceFingerprint = request.expectedWorkspaceFingerprint,
                snapshotSchemaVersion = request.workspace.schemaVersion,
                workspaceSnapshot = encoded,
                workspaceFingerprint = CaptureDraftWorkspaceFingerprint.ofEncoded(encoded),
                updatedAtEpochMillis = request.occurredAtEpochMillis,
            ),
        )
        result.workspace.toDomainWorkspaceSnapshot()
    }

    override suspend fun consumeDraftWorkspace(
        request: ConsumeCaptureDraftWorkspaceRequest,
    ): Boolean = withContext(Dispatchers.IO) {
        database.consumeProblemDraftEditWorkspace(
            ConsumeProblemDraftEditWorkspaceCommand(
                draftId = request.identity.draftId,
                basisRevisionNumber = request.identity.basisRevisionNumber,
                expectedWorkspaceVersion = request.identity.workspaceVersion,
                expectedWorkspaceFingerprint = request.identity.workspaceFingerprint,
            ),
        )
    }

    override suspend fun importDraft(request: CaptureDraftImportRequest): CaptureDraftSummary =
        withContext(Dispatchers.IO) {
            val draftId = stableId("draft", request.requestId)
            val imported = assetVault.import(
                localUri = request.localUri,
                sourceType = request.source.toDbValue(),
                createdAtEpochMillis = request.occurredAtEpochMillis,
            )
            val existing = database.readProblemDraft(draftId)
            val draft = if (existing != null) {
                validateImportReplay(existing, request, imported)
                existing
            } else {
                val created = createInitialDraft(draftId, request, imported)
                if (!created.created) validateImportReplay(created.draft, request, imported)
                created.draft
            }
            if (draft.currentRevision.author != StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT) {
                return@withContext draft.toSummary()
            }
            recognizePendingDraft(draft)
        }

    override suspend fun appendDraftPage(
        request: AppendCaptureDraftPageRequest,
    ): CaptureDraftSummary = withContext(Dispatchers.IO) {
        val imported = assetVault.import(
            localUri = request.localUri,
            sourceType = request.source.toDbValue(),
            createdAtEpochMillis = request.occurredAtEpochMillis,
        )
        database.appendProblemDraftSourceAsset(
            AppendProblemDraftSourceAssetCommand(
                draftId = request.draftId,
                expectedRevisionNumber = request.expectedRevisionNumber,
                expectedSourceAssetCount = request.expectedPageCount,
                sourceAsset = imported,
                appendedAtEpochMillis = request.occurredAtEpochMillis,
            ),
        ).draft.toSummary()
    }

    override suspend fun replaceDraft(
        request: ReplaceCaptureDraftRequest,
    ): CaptureDraftSummary = withContext(Dispatchers.IO) {
        val replacementDraftId = stableId(
            "replacement-draft",
            "${request.replacedDraftId}:${request.requestId}",
        )
        val replaced = database.readProblemDraft(request.replacedDraftId)
            ?: error("Capture draft no longer exists")
        database.readProblemDraft(replacementDraftId)?.let { completed ->
            validateCompletedReplacement(replaced, completed, request)
            val replayedAsset = try {
                assetVault.import(
                    localUri = request.localUri,
                    sourceType = request.source.toDbValue(),
                    createdAtEpochMillis = request.occurredAtEpochMillis,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            replayedAsset?.let {
                if (!completed.sourceAsset.hasSameCanonicalContent(replayedAsset)) {
                    throw ImmutablePayloadConflictException(
                        "capture_replacement_request",
                        request.requestId,
                    )
                }
            }
            return@withContext recognizeReplacementBestEffort(completed)
        }
        val imported = assetVault.import(
            localUri = request.localUri,
            sourceType = request.source.toDbValue(),
            createdAtEpochMillis = request.occurredAtEpochMillis,
        )
        val replacementCommand = initialDraftCommand(
            draftId = replacementDraftId,
            sourceAsset = imported,
            origin = replaced.origin,
            occurredAtEpochMillis = request.occurredAtEpochMillis,
            requestFingerprint = replacementRequestFingerprint(replaced, request),
        )
        val replacement = database.replaceProblemDraft(
            ReplaceProblemDraftCommand(
                replacedDraftId = request.replacedDraftId,
                expectedReplacedRevisionNumber = request.expectedReplacedRevisionNumber,
                replacement = replacementCommand,
                replacedAtEpochMillis = request.occurredAtEpochMillis,
            ),
        ).replacement
        recognizeReplacementBestEffort(replacement)
    }

    override suspend fun splitDraft(
        request: SplitCaptureDraftRequest,
    ): CaptureDraftSplitResult = withContext(Dispatchers.IO) {
        val replaced = database.readProblemDraft(request.draftId)
            ?: error("Capture draft no longer exists")
        require(replaced.currentRevision.revisionNumber == request.expectedRevisionNumber) {
            "Capture draft changed before it could be split"
        }
        require(replaced.sourceAssets.size == 1 && replaced.sourceAsset.sourceAssetId == request.sourceAssetId) {
            "Only one-page captures can be split automatically"
        }
        val splitDraftIds = request.regions.mapIndexed { index, region ->
            splitDraftId(request, index, region)
        }
        val completed = splitDraftIds.map { splitDraftId ->
            database.readProblemDraft(splitDraftId)
        }
        if (completed.all { it != null }) {
            require(
                replaced.status == StudyDbValue.ProblemDraftStatus.ABANDONED &&
                    replaced.updatedAtEpochMillis == request.occurredAtEpochMillis,
            ) { "Capture split replay does not match the completed operation" }
            return@withContext CaptureDraftSplitResult(
                created = false,
                replacedDraftId = replaced.draftId,
                splitDrafts = completed.filterNotNull().map { it.toSummary() },
            )
        }
        require(completed.all { it == null }) { "Capture split is only partially present" }

        val croppedAssets = mutableListOf<CanonicalSourceAssetRecord>()
        try {
            request.regions.forEach { region ->
                croppedAssets += assetVault.crop(
                    source = replaced.sourceAsset,
                    region = region,
                    createdAtEpochMillis = request.occurredAtEpochMillis,
                )
            }
            val replacements = croppedAssets.mapIndexed { index, asset ->
                initialDraftCommand(
                    draftId = splitDraftIds[index],
                    sourceAsset = asset,
                    origin = replaced.origin,
                    occurredAtEpochMillis = request.occurredAtEpochMillis,
                    requestFingerprint = splitRequestFingerprint(
                        replaced = replaced,
                        request = request,
                        region = request.regions[index],
                        regionIndex = index,
                        cropped = asset,
                    ),
                )
            }
            val result = database.splitProblemDraft(
                SplitProblemDraftCommand(
                    replacedDraftId = replaced.draftId,
                    expectedReplacedRevisionNumber = request.expectedRevisionNumber,
                    replacements = replacements,
                    splitAtEpochMillis = request.occurredAtEpochMillis,
                ),
            )
            CaptureDraftSplitResult(
                created = result.created,
                replacedDraftId = replaced.draftId,
                splitDrafts = result.replacements.map { it.toSummary() },
            )
        } catch (failure: Exception) {
            croppedAssets.forEach { asset ->
                if (database.readCanonicalSourceAsset(asset.sourceAssetId) == null) {
                    runCatching { assetVault.delete(asset) }
                }
            }
            throw failure
        }
    }

    override suspend fun confirmAndCommit(
        request: ConfirmCapturedProblemRequest,
    ): CapturedProblemCommitSummary =
        withContext(Dispatchers.IO) {
            val draft = database.readProblemDraft(request.draftId)
                ?: error("Capture draft no longer exists")
            require(draft.origin == StudyDbValue.CaptureOrigin.LIBRARY) {
                "Only mistake-library captures may be saved during confirmation"
            }
            assetVault.resolve(draft.sourceAsset)
            val expected = request.workspaceIdentity.toDatabaseExpectation()
            val suffix = confirmationStableSuffix(request.workspaceIdentity)
            val result = database.confirmAndCommitProblemDraftFromWorkspace(
                ConfirmAndCommitProblemDraftFromWorkspaceCommand(
                    workspace = expected,
                    commit = CommitProblemDraftCommand(
                        commandId = "commit-$suffix",
                        draftId = request.draftId,
                        expectedRevisionNumber = expected.basisRevisionNumber + 1,
                        problemId = "problem-$suffix",
                        problemRevisionId = "revision-$suffix",
                        practiceUnitId = "practice-$suffix",
                        errorBookEntryId = "entry-$suffix",
                        estimatedSeconds = DEFAULT_ESTIMATED_SECONDS,
                        committedAtEpochMillis = expected.finalOccurredAtEpochMillis,
                    ),
                ),
            )
            CapturedProblemCommitSummary(
                draftId = request.draftId,
                problemId = result.receipt.problemId,
                problemRevisionId = result.receipt.problemRevisionId,
                practiceUnitId = result.receipt.practiceUnitId,
                errorBookEntryId = result.receipt.errorBookEntryId,
                created = result.created,
            )
        }

    override suspend fun confirmForTutoring(
        request: ConfirmCapturedProblemRequest,
    ): ConfirmedTutorSession = withContext(Dispatchers.IO) {
        val draft = database.readProblemDraft(request.draftId)
            ?: error("Capture draft no longer exists")
        require(draft.origin == StudyDbValue.CaptureOrigin.TUTOR) {
            "Only tutor captures may create a tutor session"
        }
        assetVault.resolve(draft.sourceAsset)
        val expected = request.workspaceIdentity.toDatabaseExpectation()
        database.confirmTutorSessionFromWorkspace(
            ConfirmTutorSessionFromWorkspaceCommand(
                workspace = expected,
                sessionId = "tutor-session-${confirmationStableSuffix(request.workspaceIdentity)}",
            ),
        ).session.toDomainTutorSession()
    }

    override suspend fun readTutorSession(sessionId: String): ConfirmedTutorSession? =
        withContext(Dispatchers.IO) {
            require(sessionId.isNotBlank()) { "Tutor session id must not be blank" }
            database.readTutorSession(sessionId)?.toDomainTutorSession()
        }

    override suspend fun saveTutorSession(
        request: SaveTutorSessionRequest,
    ): CapturedProblemCommitSummary = withContext(Dispatchers.IO) {
        val session = database.readTutorSession(request.sessionId)
            ?: error("Tutor session no longer exists")
        assetVault.resolve(session.sourceAsset)
        val suffix = stableSuffix(request.requestId)
        val result = database.commitTutorSession(
            CommitTutorSessionCommand(
                sessionId = request.sessionId,
                commit = CommitProblemDraftCommand(
                    commandId = "commit-$suffix",
                    draftId = session.draftId,
                    expectedRevisionNumber = session.draftRevisionNumber,
                    problemId = "problem-$suffix",
                    problemRevisionId = "revision-$suffix",
                    practiceUnitId = "practice-$suffix",
                    errorBookEntryId = "entry-$suffix",
                    estimatedSeconds = DEFAULT_ESTIMATED_SECONDS,
                    committedAtEpochMillis = request.occurredAtEpochMillis,
                ),
            ),
        )
        CapturedProblemCommitSummary(
            draftId = session.draftId,
            problemId = result.receipt.problemId,
            problemRevisionId = result.receipt.problemRevisionId,
            practiceUnitId = result.receipt.practiceUnitId,
            errorBookEntryId = result.receipt.errorBookEntryId,
            created = result.created,
        )
    }

    override suspend fun endTutorSessionWithoutSaving(
        request: EndTutorSessionWithoutSaveRequest,
    ): EndTutorSessionWithoutSaveResult = withContext(Dispatchers.IO) {
        val result = database.endTutorSession(
            EndTutorSessionCommand(
                sessionId = request.sessionId,
                endedAtEpochMillis = request.occurredAtEpochMillis,
            ),
        )
        EndTutorSessionWithoutSaveResult(
            sessionId = result.sessionId,
            draftId = result.draftId,
            endedAtEpochMillis = result.endedAtEpochMillis,
            created = result.created,
        )
    }

    private fun TutorSessionRecord.toDomainTutorSession(): ConfirmedTutorSession {
        require(origin == StudyDbValue.CaptureOrigin.TUTOR) {
            "Tutor session refers to a non-tutor capture"
        }
        val subject = confirmedRevision.subject
            ?: error("Tutor session has no confirmed subject")
        val sourceFile = assetVault.resolve(sourceAsset)
        return ConfirmedTutorSession(
            sessionId = sessionId,
            draftId = draftId,
            draftRevisionNumber = draftRevisionNumber,
            subject = subject,
            title = confirmedRevision.title,
            questionDocument = confirmedRevision.questionDocument,
            sourceImageUri = Uri.fromFile(sourceFile).toString(),
            createdAtEpochMillis = createdAtEpochMillis,
            isSaved = commitReceipt != null,
            isEndedWithoutSave = draftStatus == StudyDbValue.ProblemDraftStatus.ABANDONED,
            errorBookEntryId = commitReceipt?.errorBookEntryId,
        )
    }

    private fun PendingCaptureDraftRecord.toPendingCaptureItem(): PendingCaptureItem {
        val workspaceSnapshot = editWorkspace?.toDomainWorkspaceSnapshot()
        val workspace = workspaceSnapshot?.workspace
        val sessionId = validTutorSessionId()
        val sourceAvailable = runCatching { assetVault.resolve(draft.sourceAsset) }.isSuccess
        val stage = when {
            !sourceAvailable -> PendingCaptureStage.SOURCE_UNAVAILABLE
            sessionId != null -> PendingCaptureStage.TUTOR_SESSION_READY
            else -> stageFor(validatedTasks())
        }
        return PendingCaptureItem(
            draftId = draft.draftId,
            origin = draft.origin.toDomainOrigin(),
            subject = if (workspace != null) workspace.subject else draft.currentRevision.subject,
            title = workspace?.workingDocument?.document?.title
                ?.takeIf(String::isNotBlank)
                ?: if (workspace == null) draft.currentRevision.title else "新拍题目",
            currentRevisionNumber = draft.currentRevision.revisionNumber,
            updatedAtEpochMillis = maxOf(
                draft.updatedAtEpochMillis,
                workspaceSnapshot?.updatedAtEpochMillis ?: 0,
            ),
            stage = stage,
            tutorSessionId = sessionId.takeIf {
                stage == PendingCaptureStage.TUTOR_SESSION_READY
            },
        )
    }

    private fun PendingCaptureDraftRecord.toResumableCaptureDraft(): ResumableCaptureDraft {
        val sourcePages = draft.toDomainSourcePages()
        val sourceFile = assetVault.resolve(draft.sourceAsset)
        val tasks = validatedTasks()
        val workspaceSnapshot = editWorkspace?.toDomainWorkspaceSnapshot()
        val workspace = workspaceSnapshot?.workspace
        val document = workspace?.workingDocument ?: draft.currentRevision.questionDocument
        val title = workspace?.workingDocument?.document?.title
            ?.takeIf(String::isNotBlank)
            ?: if (workspace == null) draft.currentRevision.title else "新拍题目"
        return ResumableCaptureDraft(
            draftId = draft.draftId,
            origin = draft.origin.toDomainOrigin(),
            sourceImageUri = Uri.fromFile(sourceFile).toString(),
            sourceAssetId = draft.sourceAsset.sourceAssetId,
            sourceAssetSha256 = draft.sourceAsset.contentSha256,
            sourceWidth = draft.sourceAsset.width,
            sourceHeight = draft.sourceAsset.height,
            sourceByteSize = draft.sourceAsset.byteSize,
            sourcePages = sourcePages,
            draftCreatedAtEpochMillis = draft.createdAtEpochMillis,
            currentRevisionNumber = draft.currentRevision.revisionNumber,
            currentRevisionDocumentFingerprint = draft.currentRevision.documentFingerprint,
            currentRevisionCreatedAtEpochMillis = draft.currentRevision.createdAtEpochMillis,
            subject = if (workspace != null) workspace.subject else draft.currentRevision.subject,
            title = title,
            questionDocument = document,
            transcription = QuestionDocumentMarkdownProjection.project(document.document).trim(),
            writingLayer = document.captureWritingLayer(),
            latestAssessmentTask = tasks.assessment,
            sourcePageAssessmentTasks = tasks.assessments,
            latestParseTask = tasks.parse,
            tutorSessionId = validTutorSessionId(),
            workspace = workspaceSnapshot,
            updatedAtEpochMillis = maxOf(
                draft.updatedAtEpochMillis,
                workspaceSnapshot?.updatedAtEpochMillis ?: 0,
            ),
        )
    }

    private fun ProblemDraftEditWorkspaceRecord.toDomainWorkspaceSnapshot():
        CaptureDraftWorkspaceSnapshot {
        val workspace = try {
            CaptureDraftWorkspaceCodec.decode(workspaceSnapshot)
        } catch (failure: Exception) {
            throw IllegalStateException("Persisted capture workspace is invalid", failure)
        }
        require(snapshotSchemaVersion == workspace.schemaVersion) {
            "Persisted capture workspace schema mismatch"
        }
        return CaptureDraftWorkspaceSnapshot(
            identity = CaptureDraftWorkspaceIdentity(
                draftId = draftId,
                basisRevisionNumber = basisRevisionNumber,
                workspaceVersion = workspaceVersion,
                workspaceFingerprint = workspaceFingerprint,
                finalConfirmationRequest = workspace.finalConfirmationRequest,
            ),
            workspace = workspace,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    private fun PendingCaptureDraftRecord.validTutorSessionId(): String? {
        val sessionId = tutorSessionId ?: return null
        return sessionId.takeIf {
            draft.origin == StudyDbValue.CaptureOrigin.TUTOR &&
                tutorSessionDraftRevisionNumber == draft.currentRevision.revisionNumber
        }
    }

    private fun PendingCaptureDraftRecord.validatedTasks(): ValidatedCaptureTasks {
        val assessments = draft.sourceAssets.map { source ->
            assessmentTasks.firstOrNull { task ->
                (task.request.input as? CaptureAssessmentInput)?.sourceAssetId ==
                    source.sourceAsset.sourceAssetId
            }?.takeIf { it.matchesAssessment(draft, source) }
        }
        val parse = latestParseTask?.takeIf { it.matchesParse(draft, assessments) }
        return ValidatedCaptureTasks(assessments = assessments, parse = parse)
    }

    private fun ModelTaskSnapshot.matchesAssessment(
        draft: ProblemDraftRecord,
        sourcePage: ProblemDraftSourceAssetRecord,
    ): Boolean {
        val input = request.input as? CaptureAssessmentInput ?: return false
        val source = sourcePage.sourceAsset
        return input.draftId == draft.draftId &&
            draft.currentRevision.author != StudyDbValue.ProblemDraftAuthor.USER &&
            input.sourceAssetId == source.sourceAssetId &&
            input.origin == draft.origin.toAssessmentOrigin() &&
            input.imageWidth == source.width &&
            input.imageHeight == source.height &&
            request.occurredAtEpochMillis == source.createdAtEpochMillis
    }

    private fun ModelTaskSnapshot.matchesParse(
        draft: ProblemDraftRecord,
        assessments: List<ModelTaskSnapshot?>,
    ): Boolean {
        val input = request.input as? CaptureParseInput ?: return false
        if (assessments.size != draft.sourceAssets.size || assessments.any { it == null }) return false
        val validatedAssessments = assessments.filterNotNull()
        val requestedSources = input.sourceAssets.sortedBy { it.pageIndex }
        val draftSources = draft.sourceAssets.sortedBy { it.pageIndex }
        if (requestedSources.size != draftSources.size) return false
        val parseProvider = provider
        val providerBindingMatches = parseProvider == null ||
            validatedAssessments.all { it.provider?.isDemo == parseProvider.isDemo }
        return input.draftId == draft.draftId &&
            input.origin == draft.origin.toAssessmentOrigin() &&
            input.basisRevisionNumber == draft.currentRevision.revisionNumber &&
            input.assessmentRequestIds == validatedAssessments.map { it.request.requestId } &&
            validatedAssessments.zip(draftSources).all { (assessment, sourcePage) ->
                val decision = (assessment.output as? CaptureAssessmentOutput)?.assessment?.decision
                assessment.status == ModelTaskStatus.SUCCEEDED &&
                    (decision == CaptureAssessmentDecision.PASS ||
                        (decision == CaptureAssessmentDecision.NEED_MORE_IMAGE &&
                            sourcePage.pageIndex < draftSources.lastIndex))
            } &&
            requestedSources.zip(draftSources).all { (requested, sourcePage) ->
                val source = sourcePage.sourceAsset
                requested.pageIndex == sourcePage.pageIndex &&
                    requested.assetId == source.sourceAssetId &&
                    requested.sha256 == source.contentSha256 &&
                    requested.width == source.width &&
                    requested.height == source.height
            } &&
            request.occurredAtEpochMillis >= draftSources.maxOf { it.sourceAsset.createdAtEpochMillis } &&
            providerBindingMatches &&
            (status != ModelTaskStatus.SUCCEEDED || parseProvider != null)
    }

    private fun PendingCaptureDraftRecord.stageFor(
        tasks: ValidatedCaptureTasks,
    ): PendingCaptureStage {
        val requiresMoreCapture = tasks.assessments.lastOrNull()?.let { assessment ->
            assessment.status == ModelTaskStatus.SUCCEEDED &&
                (assessment.output as? CaptureAssessmentOutput)?.assessment?.decision in setOf(
                CaptureAssessmentDecision.RECAPTURE,
                CaptureAssessmentDecision.NEED_MORE_IMAGE,
            )
        } == true
        if (requiresMoreCapture) {
            return PendingCaptureStage.RECAPTURE_REQUIRED
        }
        if (tasks.assessments.any { it.isWorking() } || tasks.parse.isWorking()) {
            return PendingCaptureStage.MODEL_WORKING
        }
        if (tasks.assessments.any { it.needsRetryOrManual() } || tasks.parse.needsRetryOrManual()) {
            return PendingCaptureStage.RETRY_OR_MANUAL
        }
        if (tasks.parse?.status == ModelTaskStatus.SUCCEEDED) {
            return if (tasks.parse.provider?.isDemo == false) {
                PendingCaptureStage.READY_TO_REVIEW
            } else {
                PendingCaptureStage.MANUAL_REVIEW_REQUIRED
            }
        }
        if (
            draft.currentRevision.author in setOf(
                StudyDbValue.ProblemDraftAuthor.LOCAL_OCR,
                StudyDbValue.ProblemDraftAuthor.OPTIONAL_REMOTE_OCR,
            ) &&
            QuestionDocumentMarkdownProjection.project(
                draft.currentRevision.questionDocument.document,
            ).isNotBlank()
        ) {
            return PendingCaptureStage.MANUAL_REVIEW_REQUIRED
        }
        return PendingCaptureStage.READY_TO_CONTINUE
    }

    private fun ModelTaskSnapshot?.isWorking(): Boolean = this?.status in setOf(
        ModelTaskStatus.WAITING_FOR_MODEL,
        ModelTaskStatus.QUEUED,
        ModelTaskStatus.RUNNING,
        ModelTaskStatus.STREAMING,
    )

    private fun ModelTaskSnapshot?.needsRetryOrManual(): Boolean = this?.status in setOf(
        ModelTaskStatus.RETRYABLE_FAILURE,
        ModelTaskStatus.PERMANENT_FAILURE,
        ModelTaskStatus.CANCELLED,
    )

    private fun CapturedQuestionDocument.captureWritingLayer(): CaptureWritingLayer {
        val layers = blockEvidence.map(QuestionBlockEvidence::writingLayer)
            .filterNot { it == WritingLayer.UNKNOWN || it == WritingLayer.DIAGRAM }
            .distinct()
        if (WritingLayer.MIXED in layers || layers.size > 1) return CaptureWritingLayer.MIXED
        return when (layers.singleOrNull()) {
            WritingLayer.PRINTED -> CaptureWritingLayer.PRINTED
            WritingLayer.HANDWRITTEN -> CaptureWritingLayer.HANDWRITTEN
            WritingLayer.MIXED -> CaptureWritingLayer.MIXED
            WritingLayer.DIAGRAM -> CaptureWritingLayer.UNKNOWN
            WritingLayer.UNKNOWN, null -> CaptureWritingLayer.UNKNOWN
        }
    }

    private fun String.toDomainOrigin(): CaptureEntryOrigin = when (this) {
        StudyDbValue.CaptureOrigin.LIBRARY -> CaptureEntryOrigin.LIBRARY
        StudyDbValue.CaptureOrigin.TUTOR -> CaptureEntryOrigin.TUTOR
        else -> error("Unknown pending capture origin")
    }

    private fun String.toAssessmentOrigin(): CaptureAssessmentOrigin = when (this) {
        StudyDbValue.CaptureOrigin.LIBRARY -> CaptureAssessmentOrigin.LIBRARY
        StudyDbValue.CaptureOrigin.TUTOR -> CaptureAssessmentOrigin.TUTOR
        else -> error("Unknown pending capture origin")
    }

    private data class ValidatedCaptureTasks(
        val assessments: List<ModelTaskSnapshot?>,
        val parse: ModelTaskSnapshot?,
    ) {
        val assessment: ModelTaskSnapshot?
            get() = assessments.filterNotNull().maxByOrNull { it.updatedAtEpochMillis }
    }

    private suspend fun createInitialDraft(
        draftId: String,
        request: CaptureDraftImportRequest,
        imported: CanonicalSourceAssetRecord,
    ): ProblemDraftWriteResult {
        return database.createProblemDraft(
            initialDraftCommand(
                draftId = draftId,
                sourceAsset = imported,
                origin = request.origin.toDbValue(),
                occurredAtEpochMillis = request.occurredAtEpochMillis,
                requestFingerprint = importRequestFingerprint(request, imported),
            ),
        )
    }

    private fun initialDraftCommand(
        draftId: String,
        sourceAsset: CanonicalSourceAssetRecord,
        origin: String,
        occurredAtEpochMillis: Long,
        requestFingerprint: String,
    ): CreateProblemDraftCommand {
        val initialDocument = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "document-$draftId",
                blocks = listOf(ContentBlock.Paragraph(id = "stem", markdown = "")),
            ),
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = sourceAsset.sourceAssetId,
                    sourceRegion = FULL_IMAGE_REGION,
                    writingLayer = WritingLayer.UNKNOWN,
                    provenance = QuestionBlockProvenance.IMPORTED_STRUCTURE,
                    reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
                    producerVersion = CAPTURE_IMPORT_VERSION,
                ),
            ),
        )
        val revision = ProblemDraftRevisionRecord(
            draftId = draftId,
            revisionNumber = 1,
            basisRevisionNumber = null,
            subject = null,
            title = "新拍题目",
            questionDocument = initialDocument,
            documentFingerprint = CapturedQuestionDocumentFingerprint.of(initialDocument),
            author = StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT,
            createdAtEpochMillis = occurredAtEpochMillis,
        )
        return CreateProblemDraftCommand(
            sourceAsset = sourceAsset,
            draftId = draftId,
            origin = origin,
            initialRevision = revision,
            requestFingerprint = requestFingerprint,
        )
    }

    private fun validateImportReplay(
        draft: ProblemDraftRecord,
        request: CaptureDraftImportRequest,
        imported: CanonicalSourceAssetRecord,
    ) {
        val matchesPersistedBinding =
            draft.requestFingerprint == importRequestFingerprint(request, imported) &&
                draft.sourceAsset.hasSameCanonicalContent(imported)
        if (!matchesPersistedBinding) {
            throw ImmutablePayloadConflictException("capture_import_request", request.requestId)
        }
    }

    private fun validateCompletedReplacement(
        replaced: ProblemDraftRecord,
        replacement: ProblemDraftRecord,
        request: ReplaceCaptureDraftRequest,
    ) {
        val matchesDurableResult =
            replaced.status == StudyDbValue.ProblemDraftStatus.ABANDONED &&
                replaced.currentRevision.revisionNumber ==
                    request.expectedReplacedRevisionNumber &&
                replaced.updatedAtEpochMillis == request.occurredAtEpochMillis &&
                replacement.origin == replaced.origin &&
                replacement.createdAtEpochMillis == request.occurredAtEpochMillis &&
                replacement.requestFingerprint == replacementRequestFingerprint(replaced, request)
        if (!matchesDurableResult) {
            throw ImmutablePayloadConflictException(
                "capture_replacement_request",
                request.requestId,
            )
        }
    }

    private suspend fun recognizeReplacementBestEffort(
        replacement: ProblemDraftRecord,
    ): CaptureDraftSummary {
        if (replacement.currentRevision.author != StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT) {
            return replacement.toSummary()
        }
        return try {
            recognizePendingDraft(replacement)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            replacement.toSummary(recognitionFailed = true)
        }
    }

    private fun CanonicalSourceAssetRecord.hasSameCanonicalContent(
        other: CanonicalSourceAssetRecord,
    ): Boolean = sourceAssetId == other.sourceAssetId &&
        contentSha256 == other.contentSha256 &&
        relativePath == other.relativePath &&
        mimeType == other.mimeType &&
        byteSize == other.byteSize &&
        width == other.width &&
        height == other.height

    private fun importRequestFingerprint(
        request: CaptureDraftImportRequest,
        imported: CanonicalSourceAssetRecord,
    ): String = requestFingerprint(
        "capture-import-v2",
        request.requestId,
        request.source.toDbValue(),
        request.origin.toDbValue(),
        request.occurredAtEpochMillis.toString(),
        imported.sourceAssetId,
        imported.contentSha256,
        imported.relativePath,
        imported.mimeType,
        imported.byteSize.toString(),
        imported.width.toString(),
        imported.height.toString(),
    )

    private fun replacementRequestFingerprint(
        replaced: ProblemDraftRecord,
        request: ReplaceCaptureDraftRequest,
    ): String = requestFingerprint(
        "capture-replacement-v2",
        request.requestId,
        request.replacedDraftId,
        request.expectedReplacedRevisionNumber.toString(),
        request.source.toDbValue(),
        request.occurredAtEpochMillis.toString(),
        replaced.origin,
    )

    private fun splitDraftId(
        request: SplitCaptureDraftRequest,
        regionIndex: Int,
        region: NormalizedSourceRegion,
    ): String = stableId(
        "split-draft",
        listOf(
            request.requestId,
            request.draftId,
            request.assessmentRequestId,
            regionIndex.toString(),
            region.fingerprintValue(),
        ).joinToString(separator = ":"),
    )

    private fun splitRequestFingerprint(
        replaced: ProblemDraftRecord,
        request: SplitCaptureDraftRequest,
        region: NormalizedSourceRegion,
        regionIndex: Int,
        cropped: CanonicalSourceAssetRecord,
    ): String = requestFingerprint(
        "capture-split-v1",
        request.requestId,
        request.draftId,
        request.expectedRevisionNumber.toString(),
        request.assessmentRequestId,
        request.sourceAssetId,
        replaced.sourceAsset.contentSha256,
        regionIndex.toString(),
        region.fingerprintValue(),
        cropped.contentSha256,
        request.occurredAtEpochMillis.toString(),
    )

    private fun NormalizedSourceRegion.fingerprintValue(): String =
        listOf(left, top, right, bottom).joinToString(separator = ",") { coordinate ->
            coordinate.toString()
        }

    private fun requestFingerprint(vararg fields: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(fields.joinToString(separator = "\u001F").toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private suspend fun recognizePendingDraft(
        draft: ProblemDraftRecord,
    ): CaptureDraftSummary {
        val canonicalFile = assetVault.resolve(draft.sourceAsset)
        val recognition = try {
            localTextRecognizer.recognize(
                canonicalFile = canonicalFile,
                imageWidth = draft.sourceAsset.width,
                imageHeight = draft.sourceAsset.height,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return draft.toSummary(recognitionFailed = true)
        }
        val document = LocalOcrQuestionDocumentMapper.map(
            draftId = draft.draftId,
            sourceAssetId = draft.sourceAsset.sourceAssetId,
            recognition = recognition,
        )
        val revision = ProblemDraftRevisionRecord(
            draftId = draft.draftId,
            revisionNumber = draft.currentRevision.revisionNumber + 1,
            basisRevisionNumber = draft.currentRevision.revisionNumber,
            subject = null,
            title = "新拍题目",
            questionDocument = document,
            documentFingerprint = CapturedQuestionDocumentFingerprint.of(document),
            author = StudyDbValue.ProblemDraftAuthor.LOCAL_OCR,
            createdAtEpochMillis = draft.currentRevision.createdAtEpochMillis,
        )
        val revised = try {
            database.reviseProblemDraft(
                ReviseProblemDraftCommand(
                    draftId = draft.draftId,
                    expectedRevisionNumber = draft.currentRevision.revisionNumber,
                    revision = revision,
                ),
            ).draft
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val latest = database.readProblemDraft(draft.draftId)
            if (latest != null && latest.currentRevision.revisionNumber > draft.currentRevision.revisionNumber) {
                latest
            } else {
                throw failure
            }
        }
        return revised.toSummary()
    }

    private fun ProblemDraftRecord.toSummary(
        recognitionFailed: Boolean = false,
    ) =
        CaptureDraftSummary(
            draftId = draftId,
            sourceAssetId = sourceAsset.sourceAssetId,
            sourceAssetSha256 = sourceAsset.contentSha256,
            revisionNumber = currentRevision.revisionNumber,
            width = sourceAsset.width,
            height = sourceAsset.height,
            byteSize = sourceAsset.byteSize,
            status = status,
            recognition = recognitionSummary(recognitionFailed),
            sourcePages = toDomainSourcePages(),
        )

    private fun ProblemDraftRecord.toDomainSourcePages(): List<CaptureSourcePage> =
        sourceAssets.map { page ->
            val asset = page.sourceAsset
            CaptureSourcePage(
                pageIndex = page.pageIndex,
                imageUri = Uri.fromFile(assetVault.resolve(asset)).toString(),
                sourceAssetId = asset.sourceAssetId,
                sourceAssetSha256 = asset.contentSha256,
                width = asset.width,
                height = asset.height,
                byteSize = asset.byteSize,
                createdAtEpochMillis = asset.createdAtEpochMillis,
            )
        }

    private fun ProblemDraftRecord.recognitionSummary(
        recognitionFailed: Boolean,
    ): CaptureRecognitionSummary {
        if (recognitionFailed) {
            return CaptureRecognitionSummary(state = CaptureRecognitionState.FAILED)
        }
        if (currentRevision.author != StudyDbValue.ProblemDraftAuthor.LOCAL_OCR) {
            return CaptureRecognitionSummary()
        }
        val candidateText = QuestionDocumentMarkdownProjection.project(
            currentRevision.questionDocument.document,
        ).trim()
        val evidence = currentRevision.questionDocument.blockEvidence
        if (candidateText.isBlank()) {
            return CaptureRecognitionSummary(
                state = CaptureRecognitionState.NO_TEXT,
                producerVersion = evidence.mapNotNull(QuestionBlockEvidence::producerVersion)
                    .distinct()
                    .singleOrNull(),
            )
        }
        return CaptureRecognitionSummary(
            state = CaptureRecognitionState.CANDIDATE_AVAILABLE,
            candidateText = candidateText.take(StructuredContentLimits.MAX_TEXT_CHARS),
            confidence = evidence.mapNotNull(QuestionBlockEvidence::confidence)
                .takeIf(List<Double>::isNotEmpty)
                ?.average(),
            candidateBlockCount = currentRevision.questionDocument.document.blocks.size,
            producerVersion = evidence.mapNotNull(QuestionBlockEvidence::producerVersion)
                .distinct()
                .singleOrNull(),
        )
    }

    private fun CaptureInputSource.toDbValue(): String = when (this) {
        CaptureInputSource.CAMERA -> StudyDbValue.SourceAssetType.CAMERA
        CaptureInputSource.PHOTO_PICKER -> StudyDbValue.SourceAssetType.PHOTO_PICKER
    }

    private fun CaptureEntryOrigin.toDbValue(): String = when (this) {
        CaptureEntryOrigin.LIBRARY -> StudyDbValue.CaptureOrigin.LIBRARY
        CaptureEntryOrigin.TUTOR -> StudyDbValue.CaptureOrigin.TUTOR
    }

    private fun stableId(prefix: String, requestId: String): String =
        "$prefix-${stableSuffix(requestId)}"

    private fun CaptureDraftWorkspaceIdentity.toDatabaseExpectation():
        ExpectedProblemDraftEditWorkspace {
        val finalRequest = checkNotNull(finalConfirmationRequest) {
            "Capture workspace has no final confirmation identity"
        }
        return ExpectedProblemDraftEditWorkspace(
            draftId = draftId,
            basisRevisionNumber = basisRevisionNumber,
            workspaceVersion = workspaceVersion,
            workspaceFingerprint = workspaceFingerprint,
            finalRequestId = finalRequest.requestId,
            finalOccurredAtEpochMillis = finalRequest.occurredAtEpochMillis,
        )
    }

    private fun confirmationStableSuffix(identity: CaptureDraftWorkspaceIdentity): String {
        val finalRequest = checkNotNull(identity.finalConfirmationRequest)
        val canonical = listOf(
            identity.draftId,
            identity.basisRevisionNumber.toString(),
            identity.workspaceVersion.toString(),
            identity.workspaceFingerprint,
            finalRequest.requestId,
            finalRequest.occurredAtEpochMillis.toString(),
        ).joinToString(separator = "\u001F")
        return stableSuffix(canonical)
    }

    private fun stableSuffix(requestId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(requestId.toByteArray(StandardCharsets.UTF_8))
        .take(16)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val CAPTURE_IMPORT_VERSION = "capture-import-v1"
        const val DEFAULT_ESTIMATED_SECONDS = 180
        val FULL_IMAGE_REGION = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0)
    }
}

object CaptureWorkflowRepositoryFactory {
    fun create(context: Context, database: StudyDatabasePort): CaptureWorkflowRepository =
        RoomCaptureWorkflowRepository(
            database = database,
            assetVault = AndroidCanonicalAssetVault(context.applicationContext),
            localTextRecognizer = MlKitChineseQuestionTextRecognizer(context.applicationContext),
        )
}
