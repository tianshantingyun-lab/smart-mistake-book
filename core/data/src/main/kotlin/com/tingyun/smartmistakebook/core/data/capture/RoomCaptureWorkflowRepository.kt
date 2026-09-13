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
import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceSnapshot
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSplitResult
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.CleanImageGenerator
import com.tingyun.smartmistakebook.core.domain.CapturedProblemCommitSummary
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import java.io.File
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
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspaceCodec
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspaceFingerprint
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ImagePipelineClassifyInput
import com.tingyun.smartmistakebook.core.model.ImagePipelineClassifyOutput
import com.tingyun.smartmistakebook.core.model.ImagePipelineProblemKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.WritingLayer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map

class RoomCaptureWorkflowRepository internal constructor(
    private val database: StudyDatabasePort,
    private val assetVault: AndroidCanonicalAssetVault,
    private val localTextRecognizer: LocalQuestionTextRecognizer,
    private val cleanRedraw: CleanImageGenerator? = null,
    private val cleanRedrawScope: CoroutineScope? = null,
    private val modelTasks: ModelTaskRepository? = null,
    /**
     * Reads the user's current global model-image consent (Settings). A save path
     * only runs the model-decided redraw round when this returns true; the round
     * never runs on a user who disabled it.
     */
    private val captureConsentGranted: () -> Boolean = { false },
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
            val originalFile = assetVault.resolve(draft.sourceAsset)
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
            val summary = CapturedProblemCommitSummary(
                draftId = request.draftId,
                problemId = result.receipt.problemId,
                problemRevisionId = result.receipt.problemRevisionId,
                practiceUnitId = result.receipt.practiceUnitId,
                errorBookEntryId = result.receipt.errorBookEntryId,
                created = result.created,
            )
            // Model-decided clean redraw: the model looks at the photo and decides
            // whether it is figure-bearing. Only WITH_FIGURE problems are redrawn;
            // text-only problems keep the original. Never blocks or fails the commit —
            // a missing/failing model, generator, or classify round leaves the revision
            // with the original photo only.
            if (result.created) {
                decideAndRedraw(
                    original = originalFile,
                    originalMimeType = draft.sourceAsset.mimeType,
                    sourceAssetId = draft.sourceAsset.sourceAssetId,
                    sourceWidth = draft.sourceAsset.width,
                    sourceHeight = draft.sourceAsset.height,
                    revisionId = result.receipt.problemRevisionId,
                    subjectId = draft.draftId,
                )
            }
            summary
        }

    private suspend fun redrawAndAttachClean(
        original: File,
        originalMimeType: String,
        revisionId: String,
    ) {
        val generator = cleanRedraw ?: return
        val clean = generator.generateClean(original.readBytes(), originalMimeType) ?: return
        attachCleanRedrawImage(
            problemRevisionId = revisionId,
            cleanImageBytes = clean.bytes,
            cleanImageMimeType = clean.mimeType,
        )
    }

    /**
     * Runs one IMAGE_PIPELINE_CLASSIFY round on the committed photo and redraws only
     * when the model says WITH_FIGURE. Runs inline (the classify is fast); the redraw
     * itself stays async via [cleanRedrawScope] when injected, else inline fail-closed.
     */
    private suspend fun decideAndRedraw(
        original: File,
        originalMimeType: String,
        sourceAssetId: String,
        sourceWidth: Int,
        sourceHeight: Int,
        revisionId: String,
        subjectId: String,
    ) {
        val tasks = modelTasks ?: return
        if (!captureConsentGranted()) return
        val shouldRedraw = try {
            val classifyRequest = ModelTaskRequest(
                requestId = "save-decision:$revisionId",
                input = ImagePipelineClassifyInput(
                    sourceAssetId = sourceAssetId,
                    imageWidth = sourceWidth,
                    imageHeight = sourceHeight,
                    subjectIdOverride = subjectId,
                ),
                occurredAtEpochMillis = System.currentTimeMillis(),
                // 传用户同意的**真值**，不是硬编码 true（审计 S-2 附带发现）：
                // 网关在 `OpenAiCompatibleModelGateway.kt:601` 用 `check(...)` 做二次校验，
                // 硬编码 true 会让那道闸门形同虚设——今天靠上面 `captureConsentGranted()`
                // 的提前返回兜住，但任何绕过调用点直接构造请求的路径都不会被拦住。
                agentConsentGranted = captureConsentGranted(),
            )
            val terminal = tasks.execute(classifyRequest).last()
            terminal.status == ModelTaskStatus.SUCCEEDED &&
                (terminal.output as? ImagePipelineClassifyOutput)?.problemKind ==
                ImagePipelineProblemKind.WITH_FIGURE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!shouldRedraw) return
        scheduleCleanRedraw(original, originalMimeType, revisionId)
    }

    override suspend fun attachCleanRedrawImage(
        problemRevisionId: String,
        cleanImageBytes: ByteArray,
        cleanImageMimeType: String,
    ): Boolean = withContext(Dispatchers.IO) {
        require(cleanImageMimeType == "image/jpeg" || cleanImageMimeType == "image/png") {
            "Clean redraw must be a JPEG or PNG"
        }
        val asset = assetVault.persistCleanImageBytes(
            bytes = cleanImageBytes,
            mimeType = cleanImageMimeType,
            sourceType = StudyDbValue.SourceAssetType.CAMERA,
            createdAtEpochMillis = System.currentTimeMillis(),
        )
        database.attachCleanRedrawAsset(
            revisionId = problemRevisionId,
            asset = asset,
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

    override suspend fun readTutorSessionSheetBytes(sessionId: String): ByteArray? =
        withContext(Dispatchers.IO) {
            require(sessionId.isNotBlank()) { "Tutor session id must not be blank" }
            val session = database.readTutorSession(sessionId)?.toDomainTutorSession()
                ?: return@withContext null
            assetVault.readUriBytes(session.sourceImageUri)
        }

    override suspend fun readTutorVisualSourceAssets(
        sessionId: String,
    ): List<com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope> =
        withContext(Dispatchers.IO) {
            require(sessionId.isNotBlank()) { "Tutor session id must not be blank" }
            val session = database.readTutorSession(sessionId)
                ?: return@withContext emptyList()
            val draft = database.readProblemDraft(session.draftId)
                ?: return@withContext emptyList()
            val regionsByAsset = session.confirmedRevision.questionDocument.blockEvidence
                .mapNotNull { evidence ->
                    evidence.sourceRegion?.let { region -> evidence.sourceAssetId to region }
                }
                .groupBy(
                    keySelector = { it.first },
                    valueTransform = { it.second },
                )
            draft.sourceAssets.map { page ->
                val asset = page.sourceAsset
                com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope(
                    pageIndex = page.pageIndex,
                    assetId = asset.sourceAssetId,
                    sha256 = asset.contentSha256,
                    byteSize = asset.byteSize,
                    width = asset.width,
                    height = asset.height,
                    selectedRegion = regionsByAsset[asset.sourceAssetId]
                        ?.takeIf(List<NormalizedSourceRegion>::isNotEmpty)
                        ?.boundingRegion(),
                )
            }
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
        ).also { summary ->
            // Model-decided clean redraw for a tutor session saved into the
            // mistake book; identical fail-closed semantics to library commits.
            if (summary.created) {
                decideAndRedraw(
                    original = assetVault.resolve(session.sourceAsset),
                    originalMimeType = session.sourceAsset.mimeType,
                    sourceAssetId = session.sourceAsset.sourceAssetId,
                    sourceWidth = session.sourceAsset.width,
                    sourceHeight = session.sourceAsset.height,
                    revisionId = summary.problemRevisionId,
                    subjectId = session.draftId,
                )
            }
        }
    }

    /**
     * Schedules the commit-time clean redraw. With an injected application scope
     * the redraw is fire-and-forget in the background so the save never blocks;
     * without one it runs inline (tests). Both paths are fail-closed.
     */
    private suspend fun scheduleCleanRedraw(
        original: File,
        originalMimeType: String,
        revisionId: String,
    ) {
        val scope = cleanRedrawScope
        if (scope != null) {
            scope.launch {
                runCatching {
                    redrawAndAttachClean(original, originalMimeType, revisionId)
                }
            }
        } else {
            runCatching {
                redrawAndAttachClean(original, originalMimeType, revisionId)
            }
        }
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

    private companion object {
        const val CAPTURE_IMPORT_VERSION = "capture-import-v1"
        const val DEFAULT_ESTIMATED_SECONDS = 180
        val FULL_IMAGE_REGION = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0)
    }
}

object CaptureWorkflowRepositoryFactory {
    fun create(
        context: Context,
        database: StudyDatabasePort,
        cleanRedraw: CleanImageGenerator? = null,
        cleanRedrawScope: CoroutineScope? = null,
        modelTasks: ModelTaskRepository? = null,
        captureConsentGranted: () -> Boolean = { false },
    ): CaptureWorkflowRepository =
        RoomCaptureWorkflowRepository(
            database = database,
            assetVault = AndroidCanonicalAssetVault(context.applicationContext),
            localTextRecognizer = MlKitChineseQuestionTextRecognizer(context.applicationContext),
            cleanRedraw = cleanRedraw,
            cleanRedrawScope = cleanRedrawScope,
            modelTasks = modelTasks,
            captureConsentGranted = captureConsentGranted,
        )
}
