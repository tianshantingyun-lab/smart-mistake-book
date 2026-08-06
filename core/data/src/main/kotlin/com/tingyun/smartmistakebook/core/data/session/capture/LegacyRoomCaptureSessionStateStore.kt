package com.tingyun.smartmistakebook.core.data.session.capture

import android.net.Uri
import com.tingyun.smartmistakebook.core.data.capture.CaptureDraftCanonicalAssetBundle
import com.tingyun.smartmistakebook.core.data.capture.CaptureDraftCanonicalAssetPage
import com.tingyun.smartmistakebook.core.data.capture.CaptureDraftMergeReceipt
import com.tingyun.smartmistakebook.core.data.capture.CaptureDraftSessionId
import com.tingyun.smartmistakebook.core.data.capture.LocalOcrQuestionDocumentMapper
import com.tingyun.smartmistakebook.core.data.capture.LocalQuestionTextRecognizer
import com.tingyun.smartmistakebook.core.data.capture.MergeAdjacentCaptureDraftsCommand
import com.tingyun.smartmistakebook.core.data.capture.PreparedStudentOwnedCaptureCommit
import com.tingyun.smartmistakebook.core.data.capture.ReadCaptureDraftCanonicalAssetsQuery
import com.tingyun.smartmistakebook.core.data.capture.ReadCaptureDraftMergeReceiptQuery
import com.tingyun.smartmistakebook.core.data.capture.captureAssetOrderFingerprint
import com.tingyun.smartmistakebook.core.data.capture.captureDraftMergeReceiptFingerprint
import com.tingyun.smartmistakebook.core.data.capture.captureDraftSessionVersion
import com.tingyun.smartmistakebook.core.data.capture.captureMergeSessionReceiptReference
import com.tingyun.smartmistakebook.core.database.AppendProblemDraftSourceAssetCommand
import com.tingyun.smartmistakebook.core.database.AcknowledgeStudentOwnedCaptureSessionCommand
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
import com.tingyun.smartmistakebook.core.database.LegacyCaptureSessionDatabasePort
import com.tingyun.smartmistakebook.core.database.LegacyCaptureStudentSaveClaim
import com.tingyun.smartmistakebook.core.database.LegacyPreCutoverCaptureBusinessWritePort
import com.tingyun.smartmistakebook.core.database.CaptureDraftMergeSessionReceiptRecord
import com.tingyun.smartmistakebook.core.database.MergeProblemDraftSourceBundleCommand
import com.tingyun.smartmistakebook.core.database.ProblemDraftRevisionRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftEditWorkspaceRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftCommitReceipt
import com.tingyun.smartmistakebook.core.database.ProblemDraftWriteResult
import com.tingyun.smartmistakebook.core.database.PendingCaptureDraftRecord
import com.tingyun.smartmistakebook.core.database.ReviseProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.ReplaceProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.SaveProblemDraftEditWorkspaceCommand
import com.tingyun.smartmistakebook.core.database.SplitProblemDraftCommand
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
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
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
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.StructuredContentLimits
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.student.mistake.database.CommitStudentProblemCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.LibraryStudentCaptureSaveSource
import com.tingyun.smartmistakebook.core.student.mistake.database.SaveStudentOwnedCaptureCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.SaveTargetConfirmedStudentMistakeCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCaptureSaveSource
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemImageReference
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentPracticeUnitKind
import com.tingyun.smartmistakebook.core.student.mistake.database.TutorStudentCaptureSaveSource
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Exact pre-cutover result retained only for migration and direct legacy contract tests. */
internal data class LegacyConfirmedCaptureCommit<T>(
    val receipt: ProblemDraftCommitReceipt,
    val tutorSessionId: String?,
    val result: T,
)

internal class LegacyRoomCaptureSessionStateStore(
    private val database: LegacyCaptureSessionDatabasePort,
    private val assetVault: LegacyRoomCaptureAssetBridge,
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

    suspend fun readDraftSummary(
        draftSessionId: CaptureDraftSessionId,
    ): CaptureDraftSummary? = withContext(Dispatchers.IO) {
        database.readProblemDraft(draftSessionId.value)?.toSummary()
    }

    suspend fun readCanonicalSourceAssets(
        query: ReadCaptureDraftCanonicalAssetsQuery,
    ): CaptureDraftCanonicalAssetBundle? = withContext(Dispatchers.IO) {
        database.readProblemDraft(query.draftSessionId.value)
            ?.toCanonicalAssetBundle(query.maximumAssetCount)
    }

    suspend fun mergeAdjacentDrafts(
        command: MergeAdjacentCaptureDraftsCommand,
    ): CaptureDraftMergeReceipt = withContext(Dispatchers.IO) {
        val query = command.toReceiptQuery()
        database.readProblemDraftMergeSessionReceipt(
            batchJobId = command.batchJobId,
            batchPageIndex = command.batchPageIndex,
        )?.let { existing ->
            check(existing.requestCanonicalFingerprint == command.canonicalFingerprint) {
                "Capture draft merge boundary was reused with another payload"
            }
            return@withContext existing.toCaptureReceipt(query)
        }
        val primary =
            checkNotNull(database.readProblemDraft(command.primaryDraftSessionId.value)) {
                "Primary capture draft no longer exists"
            }.toCanonicalAssetBundle(MAX_CAPTURE_SESSION_SOURCE_ASSETS)
        val following =
            checkNotNull(database.readProblemDraft(command.followingDraftSessionId.value)) {
                "Following capture draft no longer exists"
            }.toCanonicalAssetBundle(MAX_CAPTURE_SESSION_SOURCE_ASSETS)
        primary.requireExpected(
            sessionVersion = command.expectedPrimarySessionVersion,
            assetOrderFingerprint = command.expectedPrimaryAssetOrderFingerprint,
        )
        following.requireExpected(
            sessionVersion = command.expectedFollowingSessionVersion,
            assetOrderFingerprint = command.expectedFollowingAssetOrderFingerprint,
        )
        val expectedMergedAssetIds =
            (primary.pages + following.pages).map { page -> page.asset.assetId }
        require(expectedMergedAssetIds.size <= MAX_CAPTURE_SESSION_SOURCE_ASSETS) {
            "Merged capture draft exceeds the source-asset budget"
        }
        require(expectedMergedAssetIds.distinct().size == expectedMergedAssetIds.size) {
            "Merged capture draft would repeat a canonical source asset"
        }
        val mergedPages =
            (primary.pages + following.pages).mapIndexed { pageIndex, page ->
                page.copy(pageIndex = pageIndex)
            }
        val prospectiveMerged =
            CaptureDraftCanonicalAssetBundle(
                draftSessionId = command.primaryDraftSessionId,
                revisionNumber = primary.revisionNumber,
                sessionVersion =
                    captureDraftSessionVersion(
                        revisionNumber = primary.revisionNumber,
                        sourceAssetCount = mergedPages.size,
                    ),
                assetOrderFingerprint =
                    captureAssetOrderFingerprint(
                        draftSessionId = command.primaryDraftSessionId,
                        pages = mergedPages,
                    ),
                pages = mergedPages,
            )
        val receiptReference = captureMergeSessionReceiptReference(command)
        database.mergeProblemDraftSourceBundle(
                MergeProblemDraftSourceBundleCommand(
                    receiptReference = receiptReference,
                    batchJobId = command.batchJobId,
                    batchPageIndex = command.batchPageIndex,
                    primaryDraftId = command.primaryDraftSessionId.value,
                    expectedPrimaryRevisionNumber = primary.revisionNumber,
                    expectedPrimarySessionVersion = primary.sessionVersion,
                    expectedPrimaryAssetOrderFingerprint = primary.assetOrderFingerprint,
                    expectedPrimarySourceAssetIds =
                        primary.pages.map { page -> page.asset.assetId },
                    followingDraftId = command.followingDraftSessionId.value,
                    expectedFollowingRevisionNumber = following.revisionNumber,
                    expectedFollowingSessionVersion = following.sessionVersion,
                    expectedFollowingAssetOrderFingerprint = following.assetOrderFingerprint,
                    expectedFollowingSourceAssetIds =
                        following.pages.map { page -> page.asset.assetId },
                    mergedAssetOrderFingerprint =
                        prospectiveMerged.assetOrderFingerprint,
                    mergedSessionVersion = prospectiveMerged.sessionVersion,
                    requestCanonicalFingerprint = command.canonicalFingerprint,
                    mergedAtEpochMillis = command.occurredAtEpochMillis,
                ),
            ).toCaptureReceipt(query)
    }

    suspend fun readMergeReceipt(
        query: ReadCaptureDraftMergeReceiptQuery,
    ): CaptureDraftMergeReceipt? = withContext(Dispatchers.IO) {
        database.readProblemDraftMergeSessionReceipt(
            batchJobId = query.batchJobId,
            batchPageIndex = query.batchPageIndex,
        )?.toCaptureReceipt(query)
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
        confirmAndCommitLegacy(request).result

    internal fun studentOwnedLibraryCaptureSource(
        request: ConfirmCapturedProblemRequest,
    ): LibraryStudentCaptureSaveSource {
        val identity = request.workspaceIdentity
        val finalRequest = checkNotNull(identity.finalConfirmationRequest)
        val suffix = confirmationStableSuffix(identity)
        val intentId = "commit-$suffix"
        return LibraryStudentCaptureSaveSource(
            intentId = intentId,
            sourceCanonicalFingerprint =
                CanonicalSha256("student-owned-library-capture-source-v1")
                    .field("intentId", intentId)
                    .field("draftId", identity.draftId)
                    .field("basisRevisionNumber", identity.basisRevisionNumber)
                    .field("workspaceVersion", identity.workspaceVersion)
                    .field("workspaceFingerprint", identity.workspaceFingerprint)
                    .field("confirmationRequestId", finalRequest.requestId)
                    .field(
                        "confirmationOccurredAtEpochMillis",
                        finalRequest.occurredAtEpochMillis,
                    )
                    .finish(),
            draftId = identity.draftId,
            basisRevisionNumber = identity.basisRevisionNumber,
            workspaceVersion = identity.workspaceVersion,
            workspaceCanonicalFingerprint = identity.workspaceFingerprint,
            confirmationRequestId = finalRequest.requestId,
            occurredAtEpochMillis = finalRequest.occurredAtEpochMillis,
        )
    }

    internal suspend fun prepareStudentOwnedLibraryCapture(
        request: ConfirmCapturedProblemRequest,
        source: LibraryStudentCaptureSaveSource = studentOwnedLibraryCaptureSource(request),
        learnerId: String = LOCAL_LEARNER_ID,
    ): PreparedStudentOwnedCaptureCommit = withContext(Dispatchers.IO) {
        val draft =
            database.readProblemDraft(request.draftId)
                ?: error("Capture draft no longer exists")
        require(draft.origin == StudyDbValue.CaptureOrigin.LIBRARY) {
            "Only mistake-library captures may be saved during confirmation"
        }
        require(
            draft.status == StudyDbValue.ProblemDraftStatus.EDITING &&
                draft.currentRevision.revisionNumber == source.basisRevisionNumber,
        ) {
            "Capture draft no longer matches the student-owned save source"
        }
        val workspace =
            database.readProblemDraftEditWorkspace(request.draftId)
                ?.toDomainWorkspaceSnapshot()
                ?: error("Capture workspace no longer exists")
        require(workspace.identity == request.workspaceIdentity) {
            "Capture workspace changed before the student-owned save"
        }
        require(source == studentOwnedLibraryCaptureSource(request)) {
            "Capture source changed before the student-owned save"
        }
        require(source.occurredAtEpochMillis >= workspace.updatedAtEpochMillis) {
            "Capture confirmation precedes the persisted workspace"
        }
        val subject =
            workspace.workspace.subject
                ?.let(SubjectKind::valueOf)
                ?.also { require(it != SubjectKind.GENERAL) }
                ?: error("Capture workspace has no confirmed subject")
        val document = workspace.workspace.workingDocument
        require(CapturedQuestionDocumentValidator.validateForCommit(document).isEmpty()) {
            "Capture workspace is not ready to confirm"
        }
        val title =
            document.document.title?.takeIf(String::isNotBlank)
                ?: error("Capture workspace has no confirmed title")
        assetVault.resolve(draft.sourceAsset)
        buildStudentOwnedCaptureCommit(
            learnerId = learnerId,
            source = source,
            subject = subject,
            title = title,
            document = document,
            sourceAssets = draft.sourceAssets,
        )
    }

    internal suspend fun confirmAndCommitLegacy(
        request: ConfirmCapturedProblemRequest,
        legacyStudentSaveClaim: LegacyCaptureStudentSaveClaim? = null,
    ): LegacyConfirmedCaptureCommit<CapturedProblemCommitSummary> =
        withContext(Dispatchers.IO) {
            val draft = database.readProblemDraft(request.draftId)
                ?: error("Capture draft no longer exists")
            require(draft.origin == StudyDbValue.CaptureOrigin.LIBRARY) {
                "Only mistake-library captures may be saved during confirmation"
            }
            assetVault.resolve(draft.sourceAsset)
            val expected = request.workspaceIdentity.toDatabaseExpectation()
            val suffix = confirmationStableSuffix(request.workspaceIdentity)
            val result = requirePreCutoverBusinessWrites()
                .confirmAndCommitProblemDraftFromWorkspace(
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
                            problemOrganizationAuthorization =
                                request.problemOrganizationAuthorization,
                            legacyStudentSaveClaim = legacyStudentSaveClaim,
                        ),
                    ),
                )
            LegacyConfirmedCaptureCommit(
                receipt = result.receipt,
                tutorSessionId = null,
                result =
                    CapturedProblemCommitSummary(
                        draftId = request.draftId,
                        problemId = result.receipt.problemId,
                        problemRevisionId = result.receipt.problemRevisionId,
                        practiceUnitId = result.receipt.practiceUnitId,
                        errorBookEntryId = result.receipt.errorBookEntryId,
                        created = result.created,
                    ),
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
    ): CapturedProblemCommitSummary =
        saveTutorSessionLegacy(request).result

    internal suspend fun studentOwnedTutorCaptureSource(
        request: SaveTutorSessionRequest,
    ): TutorStudentCaptureSaveSource = withContext(Dispatchers.IO) {
        val session =
            database.readTutorSession(request.sessionId)
                ?: error("Tutor session no longer exists")
        require(session.commitReceipt == null) {
            "Legacy-saved tutor sessions are migration-only"
        }
        require(
            session.draftStatus == StudyDbValue.ProblemDraftStatus.EDITING ||
                session.draftStatus == StudyDbValue.ProblemDraftStatus.COMMITTED,
        ) {
            "An ended tutor session cannot enter the student-owned save"
        }
        require(request.occurredAtEpochMillis >= session.createdAtEpochMillis) {
            "Tutor save precedes its confirmed session"
        }
        val suffix = stableSuffix(request.requestId)
        val intentId = "commit-$suffix"
        TutorStudentCaptureSaveSource(
            intentId = intentId,
            sourceCanonicalFingerprint =
                CanonicalSha256("student-owned-tutor-capture-source-v1")
                    .field("intentId", intentId)
                    .field("saveRequestId", request.requestId)
                    .field("sessionId", request.sessionId)
                    .field("draftId", session.draftId)
                    .field("draftRevisionNumber", session.draftRevisionNumber)
                    .field("saveOccurredAtEpochMillis", request.occurredAtEpochMillis)
                    .finish(),
            saveRequestId = request.requestId,
            sessionId = request.sessionId,
            draftId = session.draftId,
            draftRevisionNumber = session.draftRevisionNumber,
            occurredAtEpochMillis = request.occurredAtEpochMillis,
        )
    }

    internal suspend fun prepareStudentOwnedTutorCapture(
        request: SaveTutorSessionRequest,
        source: TutorStudentCaptureSaveSource,
        learnerId: String = LOCAL_LEARNER_ID,
    ): PreparedStudentOwnedCaptureCommit = withContext(Dispatchers.IO) {
        val session =
            database.readTutorSession(request.sessionId)
                ?: error("Tutor session no longer exists")
        require(session.commitReceipt == null) {
            "Legacy-saved tutor sessions are migration-only"
        }
        require(session.draftStatus == StudyDbValue.ProblemDraftStatus.EDITING) {
            "Only an active tutor session may be saved"
        }
        require(
            session.sessionId == source.sessionId &&
                session.draftId == source.draftId &&
                session.draftRevisionNumber == source.draftRevisionNumber,
        ) {
            "Tutor session changed before the student-owned save"
        }
        require(source == studentOwnedTutorCaptureSource(request)) {
            "Tutor save source changed before the student-owned save"
        }
        val draft =
            database.readProblemDraft(session.draftId)
                ?: error("Tutor capture draft no longer exists")
        require(draft.currentRevision.revisionNumber == session.draftRevisionNumber) {
            "Tutor capture draft changed before the student-owned save"
        }
        require(source.occurredAtEpochMillis >= draft.updatedAtEpochMillis) {
            "Tutor save precedes its persisted draft"
        }
        val subject =
            session.confirmedRevision.subject
                ?.let(SubjectKind::valueOf)
                ?.also { require(it != SubjectKind.GENERAL) }
                ?: error("Tutor session has no confirmed subject")
        buildStudentOwnedCaptureCommit(
            learnerId = learnerId,
            source = source,
            subject = subject,
            title = session.confirmedRevision.title,
            document = session.confirmedRevision.questionDocument,
            sourceAssets = draft.sourceAssets,
        )
    }

    internal suspend fun acknowledgeStudentOwnedCaptureSession(
        command: AcknowledgeStudentOwnedCaptureSessionCommand,
    ) {
        database.acknowledgeStudentOwnedCaptureSession(command)
    }

    internal suspend fun saveTutorSessionLegacy(
        request: SaveTutorSessionRequest,
        legacyStudentSaveClaim: LegacyCaptureStudentSaveClaim? = null,
    ): LegacyConfirmedCaptureCommit<CapturedProblemCommitSummary> = withContext(Dispatchers.IO) {
        val session = database.readTutorSession(request.sessionId)
            ?: error("Tutor session no longer exists")
        assetVault.resolve(session.sourceAsset)
        val suffix = stableSuffix(request.requestId)
        val result = requirePreCutoverBusinessWrites().commitTutorSession(
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
                    legacyStudentSaveClaim = legacyStudentSaveClaim,
                ),
            ),
        )
        LegacyConfirmedCaptureCommit(
            receipt = result.receipt,
            tutorSessionId = request.sessionId,
            result =
                CapturedProblemCommitSummary(
                    draftId = session.draftId,
                    problemId = result.receipt.problemId,
                    problemRevisionId = result.receipt.problemRevisionId,
                    practiceUnitId = result.receipt.practiceUnitId,
                    errorBookEntryId = result.receipt.errorBookEntryId,
                    created = result.created,
                ),
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

    private fun requirePreCutoverBusinessWrites(): LegacyPreCutoverCaptureBusinessWritePort =
        checkNotNull(database as? LegacyPreCutoverCaptureBusinessWritePort) {
            "Legacy capture business writes are unavailable through the production session capability"
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

    private fun List<NormalizedSourceRegion>.boundingRegion(): NormalizedSourceRegion =
        NormalizedSourceRegion(
            left = minOf { region -> region.left },
            top = minOf { region -> region.top },
            right = maxOf { region -> region.right },
            bottom = maxOf { region -> region.bottom },
        )

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

    private fun ProblemDraftRecord.toCanonicalAssetBundle(
        maximumAssetCount: Int,
    ): CaptureDraftCanonicalAssetBundle {
        require(sourceAssets.size <= maximumAssetCount) {
            "Capture draft source bundle exceeds the requested read budget"
        }
        val sessionId = CaptureDraftSessionId(draftId)
        val pages =
            sourceAssets.map { page ->
                CaptureDraftCanonicalAssetPage(
                    pageIndex = page.pageIndex,
                    asset = assetVault.descriptor(page.sourceAsset),
                )
            }
        return CaptureDraftCanonicalAssetBundle(
            draftSessionId = sessionId,
            revisionNumber = currentRevision.revisionNumber,
            sessionVersion =
                captureDraftSessionVersion(
                    revisionNumber = currentRevision.revisionNumber,
                    sourceAssetCount = pages.size,
                ),
            assetOrderFingerprint =
                captureAssetOrderFingerprint(
                    draftSessionId = sessionId,
                    pages = pages,
                ),
            pages = pages,
        )
    }

    private fun CaptureDraftCanonicalAssetBundle.requireExpected(
        sessionVersion: Long,
        assetOrderFingerprint: String,
    ) {
        check(this.sessionVersion == sessionVersion) {
            "Capture draft session changed before its boundary merge"
        }
        check(this.assetOrderFingerprint == assetOrderFingerprint) {
            "Capture draft source order changed before its boundary merge"
        }
    }

    private fun MergeAdjacentCaptureDraftsCommand.toReceiptQuery() =
        ReadCaptureDraftMergeReceiptQuery(
            batchJobId = batchJobId,
            batchPageIndex = batchPageIndex,
            primaryDraftSessionId = primaryDraftSessionId,
            followingDraftSessionId = followingDraftSessionId,
        )

    private fun CaptureDraftMergeSessionReceiptRecord.toCaptureReceipt(
        query: ReadCaptureDraftMergeReceiptQuery,
    ): CaptureDraftMergeReceipt {
        check(batchJobId == query.batchJobId && batchPageIndex == query.batchPageIndex) {
            "Capture draft merge receipt belongs to another batch boundary"
        }
        check(
            primaryDraftId == query.primaryDraftSessionId.value &&
                followingDraftId == query.followingDraftSessionId.value,
        ) {
            "Capture draft merge receipt belongs to another draft pair"
        }
        val primary = CaptureDraftSessionId(primaryDraftId)
        val following = CaptureDraftSessionId(followingDraftId)
        val merged = CaptureDraftSessionId(mergedDraftId)
        return CaptureDraftMergeReceipt(
            receiptReference = receiptReference,
            batchJobId = batchJobId,
            batchPageIndex = batchPageIndex,
            primaryDraftSessionId = primary,
            followingDraftSessionId = following,
            mergedDraftSessionId = merged,
            assetOrderFingerprint = assetOrderFingerprint,
            sessionVersion = sessionVersion,
            sourceAssetCount = sourceAssetCount,
            mergedAtEpochMillis = mergedAtEpochMillis,
            receiptFingerprint =
                captureDraftMergeReceiptFingerprint(
                    receiptReference = receiptReference,
                    batchJobId = batchJobId,
                    batchPageIndex = batchPageIndex,
                    primaryDraftSessionId = primary,
                    followingDraftSessionId = following,
                    assetOrderFingerprint = assetOrderFingerprint,
                    sessionVersion = sessionVersion,
                    sourceAssetCount = sourceAssetCount,
                    mergedAtEpochMillis = mergedAtEpochMillis,
                ),
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

    private fun buildStudentOwnedCaptureCommit(
        learnerId: String,
        source: StudentCaptureSaveSource,
        subject: SubjectKind,
        title: String,
        document: CapturedQuestionDocument,
        sourceAssets: List<ProblemDraftSourceAssetRecord>,
    ): PreparedStudentOwnedCaptureCommit {
        require(learnerId.isNotBlank())
        require(subject != SubjectKind.GENERAL)
        require(CapturedQuestionDocumentValidator.validateForCommit(document).isEmpty()) {
            "Student-owned capture document is not confirmed"
        }
        val suffix =
            source.intentId.removePrefix("commit-").also {
                require(it.isNotBlank() && "commit-$it" == source.intentId) {
                    "Student-owned capture intent id is not canonical"
                }
            }
        val problemId = "problem-$suffix"
        val revisionId = "revision-$suffix"
        val practiceUnitId = "practice-$suffix"
        val errorBookEntryId = "entry-$suffix"
        val problem =
            StudentProblemRef(
                learnerId = learnerId,
                subject = subject,
                problemId = problemId,
                practiceUnitId = practiceUnitId,
            )
        val revision =
            StudentProblemRevisionRef(
                problem = problem,
                revisionId = revisionId,
                revisionNumber = 1,
                documentCanonicalFingerprint =
                    CapturedQuestionDocumentFingerprint.of(document),
            )
        val images =
            sourceAssets.mapIndexed { ordinal, page ->
                val asset = page.sourceAsset
                val localFile = assetVault.resolve(asset)
                val selectedRegions =
                    document.blockEvidence
                        .asSequence()
                        .filter { evidence ->
                            evidence.sourceAssetId == asset.sourceAssetId
                        }
                        .mapNotNull(QuestionBlockEvidence::sourceRegion)
                        .distinct()
                        .toList()
                StudentProblemImageReference(
                    imageReferenceId =
                        CanonicalSha256("student-owned-capture-image-reference-v1")
                            .field("revisionId", revisionId)
                            .field("sourceAssetId", asset.sourceAssetId)
                            .field("ordinal", ordinal)
                            .finish(),
                    localContentUri = Uri.fromFile(localFile).toString(),
                    contentCanonicalFingerprint = asset.contentSha256,
                    mediaType = asset.mimeType,
                    ordinal = ordinal,
                    widthPixels = asset.width,
                    heightPixels = asset.height,
                    byteSize = asset.byteSize,
                    selectedRegions = selectedRegions,
                )
            }
        val target =
            SaveTargetConfirmedStudentMistakeCommand(
                problem =
                    CommitStudentProblemCommand(
                        revision = revision,
                        title = title,
                        stemMarkdown =
                            QuestionDocumentMarkdownProjection.project(document.document),
                        practiceUnitKind = StudentPracticeUnitKind.WHOLE_PROBLEM,
                        practiceUnitTitle = title,
                        itemFamilyId = problemId,
                        estimatedDurationSeconds = DEFAULT_ESTIMATED_SECONDS,
                        sourceBundleId = null,
                        partIds = emptyList(),
                        originalImages = images,
                        committedAtEpochMillis = source.occurredAtEpochMillis,
                        errorBookEntryId = errorBookEntryId,
                        capturedQuestionDocument = document,
                    ),
                confirmedAtEpochMillis = source.occurredAtEpochMillis,
            )
        return PreparedStudentOwnedCaptureCommit(
            command =
                SaveStudentOwnedCaptureCommand(
                    source = source,
                    target = target,
                ),
            summary =
                CapturedProblemCommitSummary(
                    draftId = source.draftId,
                    problemId = problemId,
                    problemRevisionId = revisionId,
                    practiceUnitId = practiceUnitId,
                    errorBookEntryId = errorBookEntryId,
                    created = true,
                ),
        )
    }

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
        const val MAX_CAPTURE_SESSION_SOURCE_ASSETS = 8
        val FULL_IMAGE_REGION = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0)
    }
}
