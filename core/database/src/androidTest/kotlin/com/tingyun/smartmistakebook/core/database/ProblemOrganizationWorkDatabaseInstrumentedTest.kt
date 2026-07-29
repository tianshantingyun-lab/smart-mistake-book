package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrant
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrantCodec
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProblemOrganizationWorkDatabaseInstrumentedTest {
    private lateinit var store: StudyDatabasePort

    @Before
    fun setUp() {
        store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun commitPersistsItsReceiptAndExactGrantAtomicallyAndReplayIsIdempotent() = runBlocking {
        val command = prepareCommit("atomic")

        val first = store.commitProblemDraft(command)
        val replay = store.commitProblemDraft(command)
        val receipt = store.readProblemOrganizationWorkCommitReceipt(command.commandId)
        val work = store.readProblemOrganizationWorkByCommitReceipt(command.commandId)

        assertTrue(first.created)
        assertFalse(replay.created)
        assertEquals(first.receipt, replay.receipt)
        assertEquals(first.receipt, receipt)
        assertNotNull(work)
        assertEquals(command.commandId, work?.commitReceiptCommandId)
        assertEquals(StudyDbValue.ProblemOrganizationWorkStatus.WAITING_AUTHORIZATION, work?.status)
        assertEquals(0, work?.attemptCount)
        assertEquals(command.committedAtEpochMillis, work?.notBeforeEpochMillis)
        assertEquals(
            command.problemOrganizationAuthorization,
            ProblemOrganizationAuthorizationGrantCodec.decode(
                requireNotNull(work?.authorizationGrantSnapshot),
            ),
        )
    }

    @Test
    fun commitWithoutGrantStaysWaitingAndIsNotScheduled() = runBlocking {
        val command = prepareCommit("missing-grant", includeAuthorization = false)
        store.commitProblemDraft(command)
        val work = checkNotNull(store.readProblemOrganizationWorkByCommitReceipt(command.commandId))

        assertEquals(StudyDbValue.ProblemOrganizationWorkStatus.WAITING_AUTHORIZATION, work.status)
        assertNull(work.authorizationGrantSnapshot)
        assertFalse(
            store.readSchedulableProblemOrganizationWorks(nowEpochMillis = 10_000, limit = 10)
                .any { it.workId == work.workId },
        )
    }

    @Test
    fun alteredAssetGrantCannotCommitAndCreatesNoReceiptOrWork() = runBlocking {
        val exact = prepareCommit("altered-asset")
        val command = exact.copy(
            problemOrganizationAuthorization = requireNotNull(
                exact.problemOrganizationAuthorization,
            ).copy(
                assets = exact.problemOrganizationAuthorization.assets.map {
                    it.copy(sha256 = "f".repeat(64))
                },
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.commitProblemDraft(command) }
        }
        assertNull(store.readProblemOrganizationWorkCommitReceipt(command.commandId))
        assertNull(store.readProblemOrganizationWorkByCommitReceipt(command.commandId))
    }

    @Test
    fun persistedGrantSurvivesReopenAndAuthorizesTheExactWork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "organization-grant-restart-${System.nanoTime()}.db"
        val originalStore = store
        context.deleteDatabase(databaseName)
        try {
            store = StudyDatabaseFactory.open(context, databaseName)
            val command = prepareCommit("restart")
            store.commitProblemDraft(command)
            store.close()

            store = StudyDatabaseFactory.open(context, databaseName)
            val waiting = checkNotNull(
                store.readProblemOrganizationWorkByCommitReceipt(command.commandId),
            )
            assertEquals(
                command.problemOrganizationAuthorization,
                ProblemOrganizationAuthorizationGrantCodec.decode(
                    requireNotNull(waiting.authorizationGrantSnapshot),
                ),
            )
            assertTrue(authorizeWaiting(command, waiting))
            assertEquals(
                StudyDbValue.ProblemOrganizationWorkStatus.PENDING,
                store.readProblemOrganizationWork(waiting.workId)?.status,
            )
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
            store = originalStore
        }
    }

    @Test
    fun separateCommitReceiptsForTheSameProblemRevisionCreateSeparateWorks() = runBlocking {
        val first = store.commitProblemDraft(prepareCommit("same-revision-first"))
        val second = store.commitProblemDraft(prepareCommit("same-revision-second"))

        assertEquals(first.receipt.problemId, second.receipt.problemId)
        assertEquals(first.receipt.problemRevisionId, second.receipt.problemRevisionId)
        assertEquals(first.receipt.practiceUnitId, second.receipt.practiceUnitId)
        assertNotNull(store.readProblemOrganizationWorkByCommitReceipt(first.receipt.commandId))
        assertNotNull(store.readProblemOrganizationWorkByCommitReceipt(second.receipt.commandId))
        assertFalse(
            store.readProblemOrganizationWorkByCommitReceipt(first.receipt.commandId)?.workId ==
                store.readProblemOrganizationWorkByCommitReceipt(second.receipt.commandId)?.workId,
        )
    }

    @Test
    fun readyAndUnconfirmedDraftsDoNotCreateOrganizationWorkBeforeCommit() = runBlocking {
        createDraft("unconfirmed", confirmed = false)
        createDraft("ready", confirmed = true)

        assertTrue(store.readSchedulableProblemOrganizationWorks(nowEpochMillis = 10_000, limit = 10).isEmpty())
        assertNull(store.readProblemOrganizationWorkCommitReceipt("commit-unconfirmed"))
        assertNull(store.readProblemOrganizationWorkCommitReceipt("commit-ready"))
    }

    @Test
    fun exactClaimAllowsOnlyOneConcurrentWinner() = runBlocking {
        val work = prepareClaimableWork("claim-race")

        val claims = (1..8).map { contender ->
            async {
                store.claimProblemOrganizationWork(
                    workId = work.workId,
                    leaseOwner = "worker-$contender",
                    nowEpochMillis = 5_000,
                    leaseDurationMillis = 500,
                )
            }
        }.awaitAll()

        val winner = claims.filterNotNull()
        assertEquals(1, winner.size)
        assertEquals(StudyDbValue.ProblemOrganizationWorkStatus.RUNNING, winner.single().status)
        assertEquals(1, winner.single().attemptCount)
    }

    @Test
    fun expiredLeaseCanBeReclaimedAndLateCompletionFails() = runBlocking {
        val work = prepareClaimableWork("lease")
        val firstClaim = checkNotNull(
            store.claimProblemOrganizationWork(
                workId = work.workId,
                leaseOwner = "first-worker",
                nowEpochMillis = 7_000,
                leaseDurationMillis = 100,
            ),
        )
        val reclaimed = checkNotNull(
            store.claimProblemOrganizationWork(
                workId = work.workId,
                leaseOwner = "second-worker",
                nowEpochMillis = 7_100,
                leaseDurationMillis = 100,
            ),
        )

        val lateCompletion = store.completeProblemOrganizationWork(
            ProblemOrganizationWorkTransitionCommand(
                workId = work.workId,
                expectedStateVersion = firstClaim.stateVersion,
                leaseOwner = "first-worker",
                requestId = "late-request",
                occurredAtEpochMillis = 7_101,
            ),
        )

        assertEquals("second-worker", reclaimed.leaseOwner)
        assertEquals(2, reclaimed.attemptCount)
        assertFalse(lateCompletion)
        assertEquals("second-worker", store.readProblemOrganizationWork(work.workId)?.leaseOwner)
    }

    private suspend fun prepareCommit(
        suffix: String,
        includeAuthorization: Boolean = true,
    ): CommitProblemDraftCommand {
        createDraft(suffix, confirmed = true)
        return CommitProblemDraftCommand(
            commandId = "commit-$suffix",
            draftId = "draft-$suffix",
            expectedRevisionNumber = 2,
            problemId = "problem-$suffix",
            problemRevisionId = "revision-$suffix",
            practiceUnitId = "practice-$suffix",
            errorBookEntryId = "entry-$suffix",
            estimatedSeconds = 60,
            committedAtEpochMillis = 3_000,
            problemOrganizationAuthorization = if (includeAuthorization) {
                authorizationGrant(suffix)
            } else {
                null
            },
        )
    }

    private suspend fun prepareClaimableWork(suffix: String): ProblemOrganizationWorkRecord {
        val command = prepareCommit(suffix)
        store.commitProblemDraft(command)
        val waiting = checkNotNull(
            store.readProblemOrganizationWorkByCommitReceipt(command.commandId),
        )
        assertTrue(authorizeWaiting(command, waiting))
        return checkNotNull(store.readProblemOrganizationWork(waiting.workId))
    }

    private suspend fun authorizeWaiting(
        command: CommitProblemDraftCommand,
        waiting: ProblemOrganizationWorkRecord,
    ): Boolean {
        val grant = requireNotNull(command.problemOrganizationAuthorization)
        val draft = checkNotNull(store.readProblemDraft(command.draftId))
        val request = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.PROBLEM_ORGANIZATION_V3_SCHEMA_VERSION,
            requestId = "organization-request:${command.commandId}",
            input = ProblemOrganizationV3Input(
                problemId = command.problemId,
                problemRevisionId = command.problemRevisionId,
                practiceUnitId = command.practiceUnitId,
                subject = SubjectKind.MATH,
                capturedDocument = draft.currentRevision.questionDocument,
                sourceAssets = draft.sourceAssets.map { source ->
                    CaptureSourceAssetRef(
                        assetId = source.sourceAsset.sourceAssetId,
                        sha256 = source.sourceAsset.contentSha256,
                        width = source.sourceAsset.width,
                        height = source.sourceAsset.height,
                        pageIndex = source.pageIndex,
                    )
                },
                relationCandidates = emptyList(),
            ),
            occurredAtEpochMillis = 3_000,
            egressManifest = grant.toEgressManifest(command.problemRevisionId),
        )
        return store.authorizeProblemOrganizationWork(
            AuthorizeProblemOrganizationWorkCommand(
                workId = waiting.workId,
                expectedStateVersion = waiting.stateVersion,
                requestId = request.requestId,
                requestSnapshot = ModelTaskCodec.encodeRequest(request),
                notBeforeEpochMillis = 3_000,
                authorizedAtEpochMillis = 3_000,
            ),
        )
    }

    private fun authorizationGrant(suffix: String) = ProblemOrganizationAuthorizationGrant(
        authorizationId = "organization-authorization-$suffix",
        sourceDraftId = "draft-$suffix",
        providerId = "provider",
        modelId = "model",
        providerConfigurationVersion = "configuration-1",
        approvedAtEpochMillis = 2_500,
        expiresAtEpochMillis = 4_000,
        assets = listOf(
            ModelEgressAssetGrant(
                assetId = "asset-$suffix",
                sha256 = assetHash(suffix),
                byteSize = 4_096,
                width = 1_200,
                height = 1_600,
            ),
        ),
    )

    private suspend fun createDraft(suffix: String, confirmed: Boolean) {
        val draftId = "draft-$suffix"
        val assetId = "asset-$suffix"
        val assetHash = assetHash(suffix)
        val importedDocument = questionDocument(
            documentId = "document-$suffix-imported",
            assetId = assetId,
            markdown = "等待用户确认的题目 $suffix",
            provenance = QuestionBlockProvenance.IMPORTED_STRUCTURE,
            reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
        )
        store.createProblemDraft(
            CreateProblemDraftCommand(
                sourceAsset = CanonicalSourceAssetRecord(
                    sourceAssetId = assetId,
                    contentSha256 = assetHash,
                    relativePath = "source-assets/$assetHash.jpg",
                    mimeType = "image/jpeg",
                    byteSize = 4_096,
                    width = 1_200,
                    height = 1_600,
                    sourceType = StudyDbValue.SourceAssetType.PHOTO_PICKER,
                    createdAtEpochMillis = 1_000,
                ),
                draftId = draftId,
                origin = StudyDbValue.CaptureOrigin.LIBRARY,
                initialRevision = ProblemDraftRevisionRecord(
                    draftId = draftId,
                    revisionNumber = 1,
                    basisRevisionNumber = null,
                    subject = null,
                    title = "待确认题目",
                    questionDocument = importedDocument,
                    documentFingerprint = CapturedQuestionDocumentFingerprint.of(importedDocument),
                    author = StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT,
                    createdAtEpochMillis = 1_000,
                ),
            ),
        )
        if (!confirmed) return

        val confirmedDocument = questionDocument(
            documentId = "document-$suffix-confirmed",
            assetId = assetId,
            markdown = "已知函数 f(x)=x²，求最值。",
            provenance = QuestionBlockProvenance.USER_CORRECTION,
            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
        )
        store.reviseProblemDraft(
            ReviseProblemDraftCommand(
                draftId = draftId,
                expectedRevisionNumber = 1,
                revision = ProblemDraftRevisionRecord(
                    draftId = draftId,
                    revisionNumber = 2,
                    basisRevisionNumber = 1,
                    subject = "MATH",
                    title = "函数最值",
                    questionDocument = confirmedDocument,
                    documentFingerprint = CapturedQuestionDocumentFingerprint.of(confirmedDocument),
                    author = StudyDbValue.ProblemDraftAuthor.USER,
                    createdAtEpochMillis = 2_000,
                ),
            ),
        )
    }

    private fun questionDocument(
        documentId: String,
        assetId: String,
        markdown: String,
        provenance: QuestionBlockProvenance,
        reviewStatus: QuestionBlockReviewStatus,
    ) = CapturedQuestionDocument(
        document = QuestionDocument(
            id = documentId,
            title = "测试题目",
            blocks = listOf(ContentBlock.Paragraph("stem", markdown)),
        ),
        blockEvidence = listOf(
            QuestionBlockEvidence(
                blockId = "stem",
                sourceAssetId = assetId,
                sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                writingLayer = WritingLayer.PRINTED,
                provenance = provenance,
                confidence = null,
                reviewStatus = reviewStatus,
                producerVersion = if (
                    provenance == QuestionBlockProvenance.IMPORTED_STRUCTURE
                ) {
                    "capture-import-v1"
                } else {
                    null
                },
            ),
        ),
    )

    private fun assetHash(suffix: String): String =
        "0123456789abcdef"[suffix.sumOf { it.code } % 16].toString().repeat(64)
}
