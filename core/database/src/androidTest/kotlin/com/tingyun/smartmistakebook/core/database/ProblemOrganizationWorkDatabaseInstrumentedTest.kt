package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.BindingAcceptanceSource
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrant
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrantCodec
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
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
    private val databaseClockReadings = ArrayDeque<Long>()
    private var databaseClockFallbackEpochMillis = 0L

    @Before
    fun setUp() {
        store = StudyDatabaseFactory.openInMemory(
            ApplicationProvider.getApplicationContext(),
            ::readDatabaseClock,
        )
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
    fun waitingPreparationAndReauthorizationRequireExactMistakeScope() = runBlocking {
        val commit = prepareCommit("reauthorize-scope")
        store.commitProblemDraft(commit)
        val waiting = checkNotNull(
            store.readProblemOrganizationWorkByCommitReceipt(commit.commandId),
        )
        val prepared = checkNotNull(
            store.readWaitingProblemOrganizationWork(
                commit.problemId,
                commit.problemRevisionId,
                commit.errorBookEntryId,
            ),
        )
        val before = checkNotNull(store.readProblemOrganizationWork(waiting.workId))
        val freshGrant = requireNotNull(commit.problemOrganizationAuthorization).copy(
            authorizationId = "fresh-authorization-scope",
        )

        assertEquals(waiting, prepared.work)
        assertEquals(commit.commandId, prepared.commitReceipt.commandId)
        assertNull(
            store.readWaitingProblemOrganizationWork(
                commit.problemId,
                commit.problemRevisionId,
                "another-entry",
            ),
        )
        val rejected = store.reauthorizeProblemOrganizationWork(
            reauthorizationCommand(commit, waiting, freshGrant).copy(
                errorBookEntryId = "another-entry",
            ),
        )

        assertEquals(ReauthorizeProblemOrganizationWorkOutcome.NOT_APPLIED, rejected.outcome)
        assertNull(rejected.work)
        assertEquals(before, store.readProblemOrganizationWork(waiting.workId))
    }

    @Test
    fun reauthorizationIsGrantOnlyAndExactReplayIsIdempotent() = runBlocking {
        val commit = prepareCommit("reauthorize-replay")
        store.commitProblemDraft(commit)
        val waiting = checkNotNull(
            store.readProblemOrganizationWorkByCommitReceipt(commit.commandId),
        )
        val freshGrant = requireNotNull(commit.problemOrganizationAuthorization).copy(
            authorizationId = "fresh-authorization-replay",
        )
        val command = reauthorizationCommand(commit, waiting, freshGrant)
        setDatabaseClockReadings(3_000)

        val first = store.reauthorizeProblemOrganizationWork(command)
        val replay = store.reauthorizeProblemOrganizationWork(command)
        val persisted = checkNotNull(store.readProblemOrganizationWork(waiting.workId))

        assertEquals(ReauthorizeProblemOrganizationWorkOutcome.REAUTHORIZED, first.outcome)
        assertEquals(ReauthorizeProblemOrganizationWorkOutcome.REPLAYED, replay.outcome)
        assertEquals(waiting.stateVersion + 1, persisted.stateVersion)
        assertEquals(StudyDbValue.ProblemOrganizationWorkStatus.WAITING_AUTHORIZATION, persisted.status)
        assertNull(persisted.requestId)
        assertNull(persisted.requestSnapshot)
        assertEquals(3_000, persisted.updatedAtEpochMillis)
        assertEquals(
            freshGrant,
            ProblemOrganizationAuthorizationGrantCodec.decode(
                requireNotNull(persisted.authorizationGrantSnapshot),
            ),
        )
        assertEquals(first.work, replay.work)

        val conflicting = command.copy(
            authorizationGrant = freshGrant.copy(
                authorizationId = "different-authorization-replay",
            ),
        )
        assertThrows(ProblemOrganizationWorkReauthorizationConflictException::class.java) {
            runBlocking { store.reauthorizeProblemOrganizationWork(conflicting) }
        }
        assertEquals(persisted, store.readProblemOrganizationWork(waiting.workId))
        setDatabaseClockReadings(4_000)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.reauthorizeProblemOrganizationWork(command) }
        }
        assertEquals(persisted, store.readProblemOrganizationWork(waiting.workId))
    }

    @Test
    fun reauthorizationRejectsProviderDraftAndByteSizeMismatchWithoutWrites() = runBlocking {
        val commit = prepareCommit("reauthorize-validation")
        store.commitProblemDraft(commit)
        val waiting = checkNotNull(
            store.readProblemOrganizationWorkByCommitReceipt(commit.commandId),
        )
        val before = checkNotNull(store.readProblemOrganizationWork(waiting.workId))
        val exactGrant = requireNotNull(commit.problemOrganizationAuthorization).copy(
            authorizationId = "fresh-authorization-validation",
        )
        val exact = reauthorizationCommand(commit, waiting, exactGrant)
        val invalidCommands = listOf(
            exact.copy(provider = exact.provider.copy(modelId = "another-model")),
            exact.copy(authorizationGrant = exactGrant.copy(sourceDraftId = "another-draft")),
            exact.copy(
                authorizationGrant = exactGrant.copy(
                    assets = exactGrant.assets.map { it.copy(byteSize = it.byteSize + 1) },
                ),
            ),
        )

        invalidCommands.forEach { invalid ->
            setDatabaseClockReadings(3_000)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.reauthorizeProblemOrganizationWork(invalid) }
            }
            assertEquals(before, store.readProblemOrganizationWork(waiting.workId))
        }
        setDatabaseClockReadings(4_000)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.reauthorizeProblemOrganizationWork(exact) }
        }
        assertEquals(before, store.readProblemOrganizationWork(waiting.workId))
    }

    @Test
    fun lateOrWrongVersionReauthorizationCannotOverwriteBoundRequest() = runBlocking {
        val commit = prepareCommit("reauthorize-late")
        store.commitProblemDraft(commit)
        val waiting = checkNotNull(
            store.readProblemOrganizationWorkByCommitReceipt(commit.commandId),
        )
        assertTrue(authorizeWaiting(commit, waiting))
        val bound = checkNotNull(store.readProblemOrganizationWork(waiting.workId))
        assertNull(
            store.readWaitingProblemOrganizationWork(
                commit.problemId,
                commit.problemRevisionId,
                commit.errorBookEntryId,
            ),
        )
        val latest = checkNotNull(
            store.readLatestProblemOrganizationWork(
                commit.problemId,
                commit.problemRevisionId,
                commit.errorBookEntryId,
            ),
        )
        assertEquals(bound, latest.work)
        assertEquals(commit.commandId, latest.commitReceipt.commandId)
        val freshGrant = requireNotNull(commit.problemOrganizationAuthorization).copy(
            authorizationId = "fresh-authorization-late",
        )
        val late = reauthorizationCommand(commit, waiting, freshGrant)

        assertEquals(
            ReauthorizeProblemOrganizationWorkOutcome.NOT_APPLIED,
            store.reauthorizeProblemOrganizationWork(late).outcome,
        )
        assertEquals(
            ReauthorizeProblemOrganizationWorkOutcome.NOT_APPLIED,
            store.reauthorizeProblemOrganizationWork(
                late.copy(expectedStateVersion = bound.stateVersion),
            ).outcome,
        )
        assertEquals(bound, store.readProblemOrganizationWork(waiting.workId))
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

    @Test
    fun organizationFactsAndSuccessfulWorkTransitionCommitAtomically() = runBlocking {
        val claimed = prepareClaimedWork("atomic-completion", "worker", 5_000, 1_000)
        val confirmation = organizationCommand("atomic-completion", acceptedAtEpochMillis = 5_100)

        val result = store.confirmAndCompleteProblemOrganizationWork(
            completionCommand(claimed, "worker", 5_100, confirmation = confirmation),
        )

        assertTrue(result.completed)
        assertTrue(checkNotNull(result.organizationResult).created)
        assertEquals(
            StudyDbValue.ProblemOrganizationWorkStatus.SUCCEEDED,
            store.readProblemOrganizationWork(claimed.workId)?.status,
        )
        assertNotNull(database().problemOrganizationDao().readReceipt(confirmation.commandId))
        assertEquals(
            1,
            database().problemOrganizationWorkDao()
                .readSolutionSteps(confirmation.commandId)
                .size,
        )
        assertEquals(
            1,
            database().problemOrganizationWorkDao()
                .readErrorAttributionCandidates(confirmation.commandId)
                .size,
        )
    }

    @Test
    fun reclaimedLeaseRejectsLateAtomicCompletionWithoutBusinessWrites() = runBlocking {
        val claimA = prepareClaimedWork("late-atomic", "worker-a", 5_000, 100)
        val claimB = checkNotNull(
            store.claimProblemOrganizationWork(
                workId = claimA.workId,
                leaseOwner = "worker-b",
                nowEpochMillis = 5_100,
                leaseDurationMillis = 1_000,
            ),
        )
        val confirmation = organizationCommand("late-atomic", acceptedAtEpochMillis = 5_101)

        val late = store.confirmAndCompleteProblemOrganizationWork(
            completionCommand(claimA, "worker-a", 5_101, confirmation = confirmation),
        )

        assertFalse(late.completed)
        assertNull(late.organizationResult)
        assertNull(database().problemOrganizationDao().readReceipt(confirmation.commandId))
        assertTrue(
            database().problemOrganizationWorkDao()
                .readSolutionSteps(confirmation.commandId)
                .isEmpty(),
        )
        val persisted = checkNotNull(store.readProblemOrganizationWork(claimA.workId))
        assertEquals(claimB.stateVersion, persisted.stateVersion)
        assertEquals("worker-b", persisted.leaseOwner)
        assertEquals(StudyDbValue.ProblemOrganizationWorkStatus.RUNNING, persisted.status)
    }

    @Test
    fun leaseExpiringDuringAtomicCompletionRollsBackBusinessWritesWithoutReclaim() = runBlocking {
        val claimed = prepareClaimedWork("expiry-mid-commit", "worker", 5_000, 100)
        val confirmation = organizationCommand("expiry-mid-commit", acceptedAtEpochMillis = 5_099)
        val command = completionCommand(
            claimed = claimed,
            leaseOwner = "worker",
            completedAtEpochMillis = 5_099,
            confirmation = confirmation,
        )
        setDatabaseClockReadings(5_099, 5_101)

        assertThrows(DatabaseContractViolationException::class.java) {
            runBlocking {
                store.confirmAndCompleteProblemOrganizationWork(command)
            }
        }

        assertNull(database().problemOrganizationDao().readReceipt(confirmation.commandId))
        assertTrue(
            database().problemOrganizationWorkDao()
                .readSolutionSteps(confirmation.commandId)
                .isEmpty(),
        )
        assertNull(
            database().problemOrganizationDao()
                .readClassificationBinding("chapter-classification-expiry-mid-commit"),
        )
        val persisted = checkNotNull(store.readProblemOrganizationWork(claimed.workId))
        assertEquals(claimed.stateVersion, persisted.stateVersion)
        assertEquals("worker", persisted.leaseOwner)
        assertEquals(StudyDbValue.ProblemOrganizationWorkStatus.RUNNING, persisted.status)
    }

    @Test
    fun userOwnedCorrectionBlocksGroundingOnlyCompletionInsideTransaction() = runBlocking {
        val claimed = prepareClaimedWork("grounding-user-race", "worker", 5_000, 1_000)
        val userCorrectionBase = organizationCommand(
            "grounding-user-race",
            acceptedAtEpochMillis = 5_050,
        )
        val userCorrection = userCorrectionBase.copy(
            commandId = "user-correction-grounding-user-race",
            payloadFingerprint = "d".repeat(64),
            classifications = userCorrectionBase.classifications.map { classification ->
                classification.copy(
                    acceptanceSource = BindingAcceptanceSource.USER_CORRECTED.name,
                )
            },
            knowledgeBindings = userCorrectionBase.knowledgeBindings.map { binding ->
                binding.copy(sourceType = BindingAcceptanceSource.USER_CORRECTED.name)
            },
        )
        store.confirmProblemOrganization(userCorrection)
        val grounding = groundingRequest(
            claimed,
            "grounding-user-race",
            occurredAtEpochMillis = 5_100,
        )

        assertThrows(ProblemOrganizationAuthorityConflictException::class.java) {
            runBlocking {
                store.confirmAndCompleteProblemOrganizationWork(
                    completionCommand(
                        claimed = claimed,
                        leaseOwner = "worker",
                        completedAtEpochMillis = 5_100,
                        groundingRequests = listOf(grounding),
                    ),
                )
            }
        }

        assertNotNull(database().problemOrganizationDao().readReceipt(userCorrection.commandId))
        assertTrue(
            database().knowledgeGroundingDao()
                .readByIds(setOf(grounding.groundingRequestId))
                .isEmpty(),
        )
        val persisted = checkNotNull(store.readProblemOrganizationWork(claimed.workId))
        assertEquals(claimed.stateVersion, persisted.stateVersion)
        assertEquals("worker", persisted.leaseOwner)
        assertEquals(StudyDbValue.ProblemOrganizationWorkStatus.RUNNING, persisted.status)
    }

    @Test
    fun failureAfterOrganizationWriteRollsBackFactsReceiptAndWorkTransition() = runBlocking {
        val claimed = prepareClaimedWork("atomic-rollback", "worker", 5_000, 1_000)
        val confirmation = organizationCommand("atomic-rollback", acceptedAtEpochMillis = 5_100)
        val grounding = groundingRequest(claimed, "atomic-rollback", occurredAtEpochMillis = 5_100)
        store.recordKnowledgeGroundingRequests(
            listOf(grounding.copy(reasonMarkdown = "already persisted with another payload")),
        )

        val failure = runCatching {
            store.confirmAndCompleteProblemOrganizationWork(
                completionCommand(
                    claimed = claimed,
                    leaseOwner = "worker",
                    completedAtEpochMillis = 5_100,
                    confirmation = confirmation,
                    groundingRequests = listOf(grounding),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is ImmutablePayloadConflictException)
        assertNull(database().problemOrganizationDao().readReceipt(confirmation.commandId))
        assertTrue(
            database().problemOrganizationWorkDao()
                .readSolutionSteps(confirmation.commandId)
                .isEmpty(),
        )
        val persisted = checkNotNull(store.readProblemOrganizationWork(claimed.workId))
        assertEquals(claimed.stateVersion, persisted.stateVersion)
        assertEquals("worker", persisted.leaseOwner)
        assertEquals(StudyDbValue.ProblemOrganizationWorkStatus.RUNNING, persisted.status)
    }

    @Test
    fun replayAndDelayedPayloadAreImmutableNoOpsAfterAtomicSuccess() = runBlocking {
        val claimed = prepareClaimedWork("atomic-replay", "worker", 5_000, 1_000)
        val confirmation = organizationCommand("atomic-replay", acceptedAtEpochMillis = 5_100)
        val command = completionCommand(claimed, "worker", 5_100, confirmation = confirmation)

        assertTrue(store.confirmAndCompleteProblemOrganizationWork(command).completed)
        val replay = store.confirmAndCompleteProblemOrganizationWork(command)
        val delayedConfirmation = organizationCommand(
            suffix = "atomic-replay",
            acceptedAtEpochMillis = 5_200,
        ).copy(
            commandId = "delayed-organization",
            payloadFingerprint = "f".repeat(64),
        )
        setDatabaseClockReadings(5_200, 5_200)
        val delayed = store.confirmAndCompleteProblemOrganizationWork(
            command.copy(
                confirmation = delayedConfirmation,
            ),
        )

        assertFalse(replay.completed)
        assertNull(replay.organizationResult)
        assertFalse(delayed.completed)
        assertNull(database().problemOrganizationDao().readReceipt(delayedConfirmation.commandId))
        assertEquals(
            1,
            database().problemOrganizationWorkDao()
                .readSolutionSteps(confirmation.commandId)
                .size,
        )
    }

    @Test
    fun groundingOnlyAndNoOpResultsCanCompleteTheirExactWork() = runBlocking {
        val groundingClaim = prepareClaimedWork("grounding-only", "grounder", 5_000, 1_000)
        assertWorkRequestMatchesItsExactReceipt(groundingClaim)
        val grounding = groundingRequest(
            groundingClaim,
            "grounding-only",
            occurredAtEpochMillis = 5_100,
        )
        val groundingResult = store.confirmAndCompleteProblemOrganizationWork(
            completionCommand(
                claimed = groundingClaim,
                leaseOwner = "grounder",
                completedAtEpochMillis = 5_100,
                groundingRequests = listOf(grounding),
            ),
        )
        val noOpClaim = prepareClaimedWork(
            suffix = "no-op",
            leaseOwner = "no-op-worker",
            nowEpochMillis = 6_000,
            leaseDurationMillis = 1_000,
            confirmedQuestionMarkdown = "已知函数 g(x)=x²+1，求最值。",
        )
        assertWorkRequestMatchesItsExactReceipt(noOpClaim)
        val noOpResult = store.confirmAndCompleteProblemOrganizationWork(
            completionCommand(noOpClaim, "no-op-worker", 6_100),
        )

        assertTrue(groundingResult.completed)
        assertNull(groundingResult.organizationResult)
        assertNotNull(
            database().knowledgeGroundingDao()
                .readByIds(setOf(grounding.groundingRequestId))
                .singleOrNull(),
        )
        assertTrue(noOpResult.completed)
        assertNull(noOpResult.organizationResult)
    }

    private suspend fun assertWorkRequestMatchesItsExactReceipt(
        claimed: ProblemOrganizationWorkRecord,
    ) {
        val work = checkNotNull(
            database().problemOrganizationWorkDao().readWork(claimed.workId),
        )
        val receipt = checkNotNull(
            database().problemOrganizationWorkDao()
                .readCommitReceipt(work.commitReceiptCommandId),
        )
        val input = checkNotNull(
            ModelTaskCodec.decodeRequest(checkNotNull(work.requestSnapshot)).input
                as? ProblemOrganizationV3Input,
        )
        assertEquals(receipt.problemId, input.problemId)
        assertEquals(receipt.problemRevisionId, input.problemRevisionId)
        assertEquals(receipt.practiceUnitId, input.practiceUnitId)
    }

    private suspend fun prepareClaimedWork(
        suffix: String,
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
        confirmedQuestionMarkdown: String = "已知函数 f(x)=x²，求最值。",
    ): ProblemOrganizationWorkRecord {
        val claimable = prepareClaimableWork(suffix, confirmedQuestionMarkdown)
        return checkNotNull(
            store.claimProblemOrganizationWork(
                workId = claimable.workId,
                leaseOwner = leaseOwner,
                nowEpochMillis = nowEpochMillis,
                leaseDurationMillis = leaseDurationMillis,
            ),
        )
    }

    private fun completionCommand(
        claimed: ProblemOrganizationWorkRecord,
        leaseOwner: String,
        completedAtEpochMillis: Long,
        confirmation: ConfirmProblemOrganizationCommand? = null,
        groundingRequests: List<KnowledgeGroundingRequestRecord> = emptyList(),
    ): CompleteProblemOrganizationWorkAtomicallyCommand {
        setDatabaseClockReadings(completedAtEpochMillis, completedAtEpochMillis)
        return CompleteProblemOrganizationWorkAtomicallyCommand(
            workId = claimed.workId,
            expectedStateVersion = claimed.stateVersion,
            leaseOwner = leaseOwner,
            requestId = requireNotNull(claimed.requestId),
            confirmation = confirmation,
            groundingRequests = groundingRequests,
        )
    }

    private fun setDatabaseClockReadings(vararg readings: Long) {
        require(readings.isNotEmpty())
        databaseClockReadings.clear()
        databaseClockReadings.addAll(readings.toList())
        databaseClockFallbackEpochMillis = readings.last()
    }

    private fun readDatabaseClock(): Long =
        databaseClockReadings.removeFirstOrNull() ?: databaseClockFallbackEpochMillis

    private fun organizationCommand(
        suffix: String,
        acceptedAtEpochMillis: Long,
    ): ConfirmProblemOrganizationCommand {
        val problemId = "problem-$suffix"
        val revisionId = "revision-$suffix"
        val practiceUnitId = "practice-$suffix"
        val knowledgeNodeId = "knowledge-$suffix"
        val stableCode = "math:knowledge:$suffix"
        return ConfirmProblemOrganizationCommand(
            commandId = "organization-$suffix",
            payloadFingerprint = "e".repeat(64),
            problemId = problemId,
            problemRevisionId = revisionId,
            practiceUnitId = practiceUnitId,
            knowledgeNodes = listOf(
                KnowledgeNodeSeedRecord(
                    knowledgeNodeId = knowledgeNodeId,
                    stableCode = stableCode,
                    subject = SubjectKind.MATH.name,
                    displayName = "二次函数最值",
                    parentKnowledgeNodeId = null,
                    taxonomyVersion = "organization-test-v1",
                    createdAtEpochMillis = acceptedAtEpochMillis,
                ),
            ),
            knowledgeBindings = listOf(
                KnowledgeBindingSeedRecord(
                    bindingId = "knowledge-binding-$suffix",
                    practiceUnitId = practiceUnitId,
                    knowledgeNodeId = knowledgeNodeId,
                    basisRevisionId = revisionId,
                    strength = 0.9,
                    sourceType = "LOCAL_POLICY_ACCEPTED",
                    taxonomyVersion = "organization-test-v1",
                    acceptedAtEpochMillis = acceptedAtEpochMillis,
                ),
            ),
            classifications = listOf(
                ProblemClassificationBindingRecord(
                    bindingId = "chapter-classification-$suffix",
                    problemId = problemId,
                    basisRevisionId = revisionId,
                    dimension = "CHAPTER",
                    labelId = "math:chapter:function",
                    displayName = "函数",
                    taxonomyVersion = "organization-test-v1",
                    acceptanceSource = "LOCAL_POLICY_ACCEPTED",
                    acceptedAtEpochMillis = acceptedAtEpochMillis,
                ),
                ProblemClassificationBindingRecord(
                    bindingId = "knowledge-classification-$suffix",
                    problemId = problemId,
                    basisRevisionId = revisionId,
                    dimension = "KNOWLEDGE",
                    labelId = stableCode,
                    displayName = "二次函数最值",
                    taxonomyVersion = "organization-test-v1",
                    acceptanceSource = "LOCAL_POLICY_ACCEPTED",
                    acceptedAtEpochMillis = acceptedAtEpochMillis,
                ),
            ),
            relations = emptyList(),
            acceptedAtEpochMillis = acceptedAtEpochMillis,
            planSchemaVersion = 3,
            solutionSteps = listOf(
                ProblemSolutionStepSeedRecord(
                    stepOrdinal = 1,
                    summaryMarkdown = "确定函数开口方向与对称轴。",
                    knowledgeReferences = listOf(
                        ProblemStepKnowledgeReferenceSeedRecord(
                            knowledgeReferenceId = "knowledge-ref-$suffix",
                            knowledgeNodeId = knowledgeNodeId,
                        ),
                    ),
                ),
            ),
            errorAttributionCandidates = listOf(
                ProblemErrorAttributionCandidateSeedRecord(
                    candidateOrdinal = 0,
                    resolutionStatus =
                        StudyDbValue.ProblemErrorAttributionResolution.UNRESOLVED,
                    stepOrdinal = null,
                    knowledgeReferenceId = null,
                    knowledgeNodeId = null,
                    rationaleMarkdown = "当前图片无法可靠定位具体错误步骤。",
                    confidence = 0.4,
                    modelVersion = "organization-test-model",
                    evidence = emptyList(),
                ),
            ),
            sourceCommitReceiptCommandId = "commit-$suffix",
        )
    }

    private fun groundingRequest(
        claimed: ProblemOrganizationWorkRecord,
        suffix: String,
        occurredAtEpochMillis: Long,
    ): KnowledgeGroundingRequestRecord {
        val requestId = requireNotNull(claimed.requestId)
        val groundingKey = KnowledgeGroundingFingerprint.of(
            subject = SubjectKind.MATH,
            expectedParentKnowledgeDisplayName = "函数",
            query = "含参数的二次函数最值",
        )
        return KnowledgeGroundingRequestRecord(
            groundingRequestId = KnowledgeGroundingFingerprint.occurrenceId(
                organizationRequestId = requestId,
                requestOrdinal = 0,
                groundingKey = groundingKey,
            ),
            groundingKey = groundingKey,
            organizationRequestId = requestId,
            organizationRequestFingerprint = "a".repeat(64),
            requestOrdinal = 0,
            problemId = "problem-$suffix",
            problemRevisionId = "revision-$suffix",
            practiceUnitId = "practice-$suffix",
            subject = SubjectKind.MATH.name,
            query = "含参数的二次函数最值",
            expectedParentKnowledgeDisplayName = "函数",
            reasonMarkdown = "本地知识节点不足以稳定归类。",
            createdAtEpochMillis = occurredAtEpochMillis,
            updatedAtEpochMillis = occurredAtEpochMillis,
        )
    }

    private fun database(): StudyDatabase =
        (store as RoomStudyDatabase).database

    private suspend fun prepareCommit(
        suffix: String,
        includeAuthorization: Boolean = true,
        confirmedQuestionMarkdown: String = "已知函数 f(x)=x²，求最值。",
    ): CommitProblemDraftCommand {
        createDraft(
            suffix = suffix,
            confirmed = true,
            confirmedQuestionMarkdown = confirmedQuestionMarkdown,
        )
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

    private suspend fun prepareClaimableWork(
        suffix: String,
        confirmedQuestionMarkdown: String = "已知函数 f(x)=x²，求最值。",
    ): ProblemOrganizationWorkRecord {
        val command = prepareCommit(suffix, confirmedQuestionMarkdown = confirmedQuestionMarkdown)
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

    private fun reauthorizationCommand(
        commit: CommitProblemDraftCommand,
        waiting: ProblemOrganizationWorkRecord,
        authorizationGrant: ProblemOrganizationAuthorizationGrant,
    ) = ReauthorizeProblemOrganizationWorkCommand(
        workId = waiting.workId,
        expectedStateVersion = waiting.stateVersion,
        problemId = commit.problemId,
        problemRevisionId = commit.problemRevisionId,
        errorBookEntryId = commit.errorBookEntryId,
        provider = organizationProvider(),
        authorizationGrant = authorizationGrant,
    )

    private fun organizationProvider() = ProviderCapabilitySnapshot(
        providerId = "provider",
        providerDisplayName = "组织测试模型",
        modelId = "model",
        supportedTasks = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = "configuration-1",
    )

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

    private suspend fun createDraft(
        suffix: String,
        confirmed: Boolean,
        confirmedQuestionMarkdown: String = "已知函数 f(x)=x²，求最值。",
    ) {
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
            markdown = confirmedQuestionMarkdown,
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
