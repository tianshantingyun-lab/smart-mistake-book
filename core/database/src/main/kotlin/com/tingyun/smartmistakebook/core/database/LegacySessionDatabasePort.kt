package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

/**
 * Read-only documents needed to prepare model tasks and capture-session work.
 *
 * These records remain migration/session inputs. This port cannot mutate assets, capture drafts,
 * student mistakes, review state, mastery, or curriculum knowledge.
 */
interface LegacyModelAssetDocumentReadPort {
    suspend fun readProblemDraft(draftId: String): ProblemDraftRecord?

    suspend fun readCanonicalSourceAsset(sourceAssetId: String): CanonicalSourceAssetRecord?
}

/** Exact model-task capability shape issued by [LegacySessionDatabaseOwner]. */
interface LegacyModelTaskAndAssetDocumentDatabasePort :
    ModelTaskDatabasePort,
    LegacyModelAssetDocumentReadPort

/**
 * Temporary capture-session state that may exist before terminal cutover.
 *
 * The port deliberately excludes the two legacy business commit operations. A production owner
 * can therefore support draft/session recovery without granting a mistake writer.
 */
interface LegacyCaptureSessionDatabasePort :
    LegacyModelAssetDocumentReadPort,
    StudentOwnedCaptureSessionAckPort {
    fun observePendingCaptureDrafts(): Flow<List<PendingCaptureDraftRecord>>

    suspend fun createProblemDraft(
        command: CreateProblemDraftCommand,
    ): ProblemDraftWriteResult

    suspend fun appendProblemDraftSourceAsset(
        command: AppendProblemDraftSourceAssetCommand,
    ): AppendProblemDraftSourceAssetResult

    suspend fun mergeProblemDraftSourceBundle(
        command: MergeProblemDraftSourceBundleCommand,
    ): CaptureDraftMergeSessionReceiptRecord

    suspend fun readProblemDraftMergeSessionReceipt(
        batchJobId: String,
        batchPageIndex: Int,
    ): CaptureDraftMergeSessionReceiptRecord?

    suspend fun reviseProblemDraft(
        command: ReviseProblemDraftCommand,
    ): ProblemDraftWriteResult

    suspend fun replaceProblemDraft(
        command: ReplaceProblemDraftCommand,
    ): ProblemDraftReplacementResult

    suspend fun splitProblemDraft(
        command: SplitProblemDraftCommand,
    ): ProblemDraftSplitResult

    suspend fun readPendingCaptureDraft(draftId: String): PendingCaptureDraftRecord?

    suspend fun readProblemDraftEditWorkspace(
        draftId: String,
    ): ProblemDraftEditWorkspaceRecord?

    suspend fun saveProblemDraftEditWorkspace(
        command: SaveProblemDraftEditWorkspaceCommand,
    ): ProblemDraftEditWorkspaceWriteResult

    suspend fun consumeProblemDraftEditWorkspace(
        command: ConsumeProblemDraftEditWorkspaceCommand,
    ): Boolean

    suspend fun confirmTutorSessionFromWorkspace(
        command: ConfirmTutorSessionFromWorkspaceCommand,
    ): TutorSessionWriteResult

    suspend fun readTutorSession(sessionId: String): TutorSessionRecord?

    suspend fun endTutorSession(
        command: EndTutorSessionCommand,
    ): EndTutorSessionResult
}

/**
 * Compatibility-only writes retained for direct pre-cutover migration tests.
 *
 * [LegacySessionDatabaseOwner] never issues this capability. Terminal v45 triggers remain the
 * final defense even for an internal pre-cutover test handle.
 */
interface LegacyPreCutoverCaptureBusinessWritePort {
    suspend fun confirmAndCommitProblemDraftFromWorkspace(
        command: ConfirmAndCommitProblemDraftFromWorkspaceCommand,
    ): CommitProblemDraftResult

    suspend fun commitTutorSession(
        command: CommitTutorSessionCommand,
    ): CommitProblemDraftResult
}

/** Batch queue and receipt state; it has no capture merge or student-problem writer. */
interface LegacyBatchSessionDatabasePort {
    fun observeBatchImportJobs(): Flow<List<BatchImportJobRecord>>

    suspend fun createBatchImportJob(
        command: CreateBatchImportJobCommand,
    ): BatchImportJobRecord

    suspend fun readBatchImportJob(jobId: String): BatchImportJobRecord?

    suspend fun updateBatchImportJobStatus(
        jobId: String,
        expectedStatus: String,
        nextStatus: String,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun requeueInterruptedBatchImportPages(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Int

    suspend fun claimNextBatchImportPage(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): BatchImportPageRecord?

    suspend fun completeBatchImportPage(
        jobId: String,
        pageIndex: Int,
        draftId: String,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun claimBatchImportBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun requeueInterruptedBatchImportBoundaries(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Int

    suspend fun recordBatchImportBoundarySessionResolution(
        command: RecordBatchImportBoundarySessionResolutionCommand,
    ): BatchImportJobRecord

    suspend fun failBatchImportBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun failBatchImportPage(
        jobId: String,
        pageIndex: Int,
        failureCode: String,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun retryBatchImportPage(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun skipBatchImportPage(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun finishBatchImportIfSettled(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun hasRetainedBatchImportSourceUri(sourceUri: String): Boolean
}

/**
 * Compatibility-only combined batch/capture write.
 *
 * Production session assembly uses [LegacyBatchSessionDatabasePort] plus a capture-owned receipt;
 * the owner never issues this cross-boundary command.
 */
interface LegacyPreCutoverBatchBoundaryWritePort {
    suspend fun resolveBatchImportBoundary(
        command: ResolveBatchImportBoundaryCommand,
    ): BatchImportJobRecord
}

/** Exact dependency set of the retained pre-cutover batch compatibility repository. */
interface LegacyPreCutoverBatchImportDatabasePort :
    LegacyBatchSessionDatabasePort,
    LegacyModelAssetDocumentReadPort,
    LegacyPreCutoverBatchBoundaryWritePort

/**
 * Pre-cutover tutor UI interaction rows. These tables are terminally frozen by v45 and this
 * capability is intentionally absent from [LegacySessionDatabaseOwner].
 */
interface LegacyPreCutoverTutorInteractionSessionDatabasePort {
    fun observeTutorTurnResponses(sessionId: String): Flow<List<TutorTurnResponseRecord>>

    fun observeTutorVisualTargetEvidence(
        sessionId: String,
    ): Flow<List<TutorVisualTargetEvidenceRecord>>

    suspend fun recordTutorChoice(
        command: PersistTutorChoiceCommand,
    ): TutorTurnResponseRecord

    suspend fun recordTutorChoiceUnlessCancelled(
        command: PersistTutorChoiceCommand,
        cancellation: PersistTutorEvidenceCancellationCommand,
    ): TutorTurnResponseRecord?

    suspend fun recordTutorVisualTargetEvidenceUnlessCancelled(
        command: PersistTutorVisualTargetEvidenceCommand,
        cancellation: PersistTutorEvidenceCancellationCommand,
    ): TutorVisualTargetEvidenceRecord?

    suspend fun recordTutorEvidenceCancellation(
        command: PersistTutorEvidenceCancellationCommand,
    )

    suspend fun isTutorEvidenceCancelled(
        command: PersistTutorEvidenceCancellationCommand,
    ): Boolean

    suspend fun recordTutorMove(
        command: PersistTutorMoveCommand,
    ): TutorTurnResponseRecord

    suspend fun revealTutorSolution(
        command: PersistTutorRevealCommand,
    ): TutorTurnResponseRecord

    suspend fun recordTutorSolutionExposure(
        command: PersistTutorAnswerExposureCommand,
    ): TutorAnswerExposureRecord

    suspend fun bindTutorSessionProblemAnchor(
        command: PersistTutorSessionAnchorCommand,
    ): TutorSessionProblemAnchorRecord

    suspend fun readTutorAnswerExposure(
        modelTaskRequestId: String,
    ): TutorAnswerExposureRecord?

    suspend fun readTutorAnswerExposures(
        modelTaskRequestIds: Set<String>,
    ): List<TutorAnswerExposureRecord>
}

/**
 * Exact legacy mistake revision read shared by the two retained migration-only consumers.
 *
 * This is historical source access, not student-mistake business authority, and is intentionally
 * absent from [LegacySessionDatabaseOwner].
 */
interface LegacyPreCutoverExactMistakeDetailReadPort {
    suspend fun readExactMistakeDetail(
        entryId: String,
        problemId: String,
        problemRevisionId: String,
    ): MistakeDetailRecord?
}

/**
 * Historical mistake-detail reads used only while a verified student-store cutover is incomplete.
 *
 * New production reads use the learner-bound student-mistake store. The legacy owner never issues
 * this capability.
 */
interface LegacyPreCutoverMistakeDetailReadPort :
    LegacyPreCutoverExactMistakeDetailReadPort {
    suspend fun readMistakeDetail(errorBookEntryId: String): MistakeDetailRecord?

    suspend fun readCurrentMistakeDetails(
        entryIds: List<String>,
    ): List<MistakeDetailRecord>

    suspend fun readMistakeRevisionHistory(
        errorBookEntryId: String,
    ): List<MistakeRevisionSummaryRecord>
}

/**
 * Terminal pre-cutover organization business rows.
 *
 * This compatibility surface exists only to keep the old Room repository testable during the
 * authority migration. It is deliberately separate from organization-work coordination and is
 * never issued by [LegacySessionDatabaseOwner].
 */
interface LegacyPreCutoverMistakeOrganizationBusinessPort :
    LegacyPreCutoverExactMistakeDetailReadPort {
    fun observeMistakes(): Flow<List<MistakeRecord>>

    fun observeConfirmedProblemOrganization(
        problemId: String,
        problemRevisionId: String,
    ): Flow<ConfirmedProblemOrganizationRecord>

    suspend fun recordKnowledgeGroundingRequests(
        requests: List<KnowledgeGroundingRequestRecord>,
    )

    suspend fun confirmProblemOrganization(
        command: ConfirmProblemOrganizationCommand,
    ): ConfirmProblemOrganizationResult

    suspend fun confirmAndCompleteProblemOrganizationWork(
        command: CompleteProblemOrganizationWorkAtomicallyCommand,
    ): ConfirmAndCompleteProblemOrganizationWorkResult
}

/**
 * Historical organization reauthorization retained only for the pre-cutover repository.
 *
 * Reauthorization rebuilds a legacy business grant, so it is intentionally not part of the
 * owner-issued coordination capability.
 */
interface LegacyPreCutoverOrganizationReauthorizationPort {
    suspend fun readLatestProblemOrganizationWork(
        problemId: String,
        problemRevisionId: String,
        errorBookEntryId: String,
    ): ProblemOrganizationWorkPreparationRecord?

    suspend fun reauthorizeProblemOrganizationWork(
        command: ReauthorizeProblemOrganizationWorkCommand,
    ): ReauthorizeProblemOrganizationWorkResult
}

/**
 * Migration-only access to durable organization work coordinated by the process scheduler.
 *
 * This capability is deliberately limited to startup recovery and schedulable-work observation.
 * It is not an authority for student mistakes, learner mastery, or curriculum knowledge.
 */
interface LegacyOrganizationSchedulerPort {
    suspend fun readRunningProblemOrganizationWorks(
        limit: Int,
        afterLeaseExpiresAtEpochMillis: Long? = null,
        afterUpdatedAtEpochMillis: Long? = null,
        afterWorkId: String? = null,
    ): List<ProblemOrganizationWorkRecord>

    fun observeSchedulableProblemOrganizationWorks(): Flow<List<ProblemOrganizationWorkRecord>>
}

/**
 * Organization work coordination without the command that writes organization business results.
 */
interface LegacyOrganizationWorkCoordinationPort : LegacyOrganizationSchedulerPort {
    suspend fun readProblemOrganizationWork(
        workId: String,
    ): ProblemOrganizationWorkRecord?

    suspend fun readProblemOrganizationWorkByCommitReceipt(
        commitReceiptCommandId: String,
    ): ProblemOrganizationWorkRecord?

    suspend fun readProblemOrganizationWorkByRequestId(
        requestId: String,
    ): ProblemOrganizationWorkRecord?

    suspend fun readProblemOrganizationWorkCommitReceipt(
        commitReceiptCommandId: String,
    ): ProblemDraftCommitReceipt?

    suspend fun claimProblemOrganizationWork(
        workId: String,
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
    ): ProblemOrganizationWorkRecord?

    suspend fun authorizeProblemOrganizationWork(
        command: AuthorizeProblemOrganizationWorkCommand,
    ): Boolean

    suspend fun markProblemOrganizationWorkWaitingAuthorization(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean

    suspend fun retryProblemOrganizationWork(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean

    suspend fun failProblemOrganizationWorkPermanently(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean

    suspend fun completeProblemOrganizationWork(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean
}

/**
 * Transitional scheduler facade retained for startup compatibility.
 *
 * New production assembly obtains [TrustedOrganizationWorkDatabaseCapability] from the unique
 * owner. This facade remains narrow so old scheduler tests do not gain a database handle.
 */
interface LegacySessionDatabasePort : LegacyOrganizationSchedulerPort

/**
 * Adapts only the two scheduler operations used during application startup.
 *
 * Function capabilities are injected instead of [StudyDatabasePort] itself so this adapter cannot
 * accidentally forward authoritative mistake, mastery, or knowledge operations.
 */
class StudyDatabaseLegacySessionAdapter(
    private val readRunningWorks: suspend (
        limit: Int,
        afterLeaseExpiresAtEpochMillis: Long?,
        afterUpdatedAtEpochMillis: Long?,
        afterWorkId: String?,
    ) -> List<ProblemOrganizationWorkRecord>,
    private val observeSchedulableWorks:
        () -> Flow<List<ProblemOrganizationWorkRecord>>,
) : LegacySessionDatabasePort {
    override suspend fun readRunningProblemOrganizationWorks(
        limit: Int,
        afterLeaseExpiresAtEpochMillis: Long?,
        afterUpdatedAtEpochMillis: Long?,
        afterWorkId: String?,
    ): List<ProblemOrganizationWorkRecord> = readRunningWorks(
        limit,
        afterLeaseExpiresAtEpochMillis,
        afterUpdatedAtEpochMillis,
        afterWorkId,
    )

    override fun observeSchedulableProblemOrganizationWorks():
        Flow<List<ProblemOrganizationWorkRecord>> =
        observeSchedulableWorks()
}

sealed interface TrustedModelTaskDatabaseCapability :
    LegacyModelTaskAndAssetDocumentDatabasePort

sealed interface TrustedCaptureSessionDatabaseCapability :
    LegacyCaptureSessionDatabasePort

sealed interface TrustedBatchSessionDatabaseCapability :
    LegacyBatchSessionDatabasePort

sealed interface TrustedOrganizationWorkDatabaseCapability :
    LegacyOrganizationWorkCoordinationPort

/**
 * Authority migration sources, cutover proof reads, and capture handoff receipts.
 *
 * The append-only cutover journal writer and v45 activation owner are both gate-bound elsewhere
 * and are intentionally absent from this capability.
 */
sealed interface TrustedLegacyCutoverMigrationDatabaseCapability :
    LegacyAuthorityMigrationSourcePort,
    ExactLegacyCaptureStudentDocumentSourcePort,
    LegacyAuthorityCutoverJournalReadPort,
    CaptureStudentSaveHandoffJournalPort

/**
 * The only production lifecycle owner for smart-mistake-book.db.
 *
 * It returns reusable narrow capabilities and never exposes [StudyDatabasePort], Room, SQL, or a
 * close operation on an individual capability.
 */
sealed interface LegacySessionDatabaseOwner : AutoCloseable {
    fun tutorSessions(): TrustedTutorSessionDatabaseCapability

    fun modelTasksAndAssetDocuments(): TrustedModelTaskDatabaseCapability

    fun captureSessions(): TrustedCaptureSessionDatabaseCapability

    fun batchSessions(): TrustedBatchSessionDatabaseCapability

    fun organizationWork(): TrustedOrganizationWorkDatabaseCapability

    fun cutoverMigrationProof(): TrustedLegacyCutoverMigrationDatabaseCapability
}

/**
 * Process-wide owner for the one production legacy database.
 *
 * Every call returns a distinct lease. The underlying Room handle closes only after the final
 * lease closes.
 */
object LegacySessionDatabaseOwnerFactory {
    const val DEFAULT_DATABASE_NAME = StudyDatabaseFactory.DEFAULT_DATABASE_NAME

    fun open(context: Context): LegacySessionDatabaseOwner {
        return RoomLegacySessionDatabaseOwner(
            acquireProductionLegacySessionResource(canonicalLegacyDatabaseTarget(context)),
        )
    }
}

private data class CanonicalLegacyDatabaseTarget(
    val applicationContext: Context,
    val ownerKey: String,
)

private val productionLegacySessionOwners =
    ReferenceCountedLegacySessionOwnerRegistry<
        String,
        RoomLegacySessionDatabaseResource,
    >(RoomLegacySessionDatabaseResource::close)

private val productionLegacyTerminalWriters =
    ExclusiveLegacyTerminalWriterRegistry<String>()

private fun canonicalLegacyDatabaseTarget(context: Context): CanonicalLegacyDatabaseTarget {
    val applicationContext = context.applicationContext ?: context
    return CanonicalLegacyDatabaseTarget(
        applicationContext = applicationContext,
        ownerKey =
            applicationContext
                .getDatabasePath(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
                .canonicalPath,
    )
}

private fun acquireProductionLegacySessionResource(
    target: CanonicalLegacyDatabaseTarget,
): ReferenceCountedLegacySessionOwnerLease<RoomLegacySessionDatabaseResource> =
    productionLegacySessionOwners.acquire(target.ownerKey) {
        RoomLegacySessionDatabaseResource(
            openProductionLegacyDatabase(target.applicationContext),
        )
    }

/**
 * Hidden hand-off reserved for the unique production cutover coordinator.
 *
 * Keeping both this function and its returned class file-private makes the terminal writer absent
 * from the Kotlin API and package-private in the JVM bytecode. The coordinator must be assembled
 * in this owner file; no application or repository component can open or self-sign the bridge.
 */
private fun openLegacyTerminalAuthorityBridge(
    context: Context,
): LegacyTerminalAuthorityBridge {
    val target = canonicalLegacyDatabaseTarget(context)
    val ownedLease =
        productionLegacyTerminalWriters.acquire(target.ownerKey) {
            acquireProductionLegacySessionResource(target)
        }
    return LegacyTerminalAuthorityBridge(ownedLease)
}

/**
 * JVM-package hand-off used only by core:data's high-level terminal migration session.
 *
 * The class remains absent from Kotlin callers outside this file and package-private in bytecode.
 * Its Java peer never returns the bridge or either database port; it wraps the journal in the
 * authority coordinator abstraction before core:data can observe it.
 */
private object LegacyTerminalAuthorityBridgeOpener {
    @JvmStatic
    fun open(context: Context): LegacyTerminalAuthorityBridge =
        ::openLegacyTerminalAuthorityBridge.invoke(context)
}

/**
 * The only release database builder. It uses the fixed production name and proves that Room's
 * writer connection and onOpen callbacks completed before returning a handle.
 */
private fun openProductionLegacyDatabase(context: Context): RoomStudyDatabase {
    val clock: () -> Long = System::currentTimeMillis
    var preOpen = inspectStudyDatabaseBeforeRoomOpen(
        context,
        StudyDatabaseFactory.DEFAULT_DATABASE_NAME,
    )
    if (preOpen.hygienePending) {
        runTutorFreeResponseMigrationHygieneExclusive(
            context,
            StudyDatabaseFactory.DEFAULT_DATABASE_NAME,
        )
        preOpen = inspectStudyDatabaseBeforeRoomOpen(
            context,
            StudyDatabaseFactory.DEFAULT_DATABASE_NAME,
        )
    }
    val requiresPostMigrationHygiene =
        preOpen.version in 1 until STUDY_DATABASE_VERSION

    fun buildDatabase() =
        Room.databaseBuilder(
            context,
            StudyDatabase::class.java,
            StudyDatabaseFactory.DEFAULT_DATABASE_NAME,
        ).addMigrations(*legacyStudyDatabaseMigrations().toTypedArray())
            .addCallback(
                LegacyBusinessWriteBarrierCreateCallback(
                    autoActivateFreshEmpty = true,
                    clock = clock,
                ),
            )
            .setDriver(AndroidSQLiteDriver())
            .build()
    var database = buildDatabase()

    try {
        runBlocking {
            database.useConnection(isReadOnly = false) { Unit }
        }
        if (requiresPostMigrationHygiene) {
            database.close()
            runTutorFreeResponseMigrationHygieneExclusive(
                context,
                StudyDatabaseFactory.DEFAULT_DATABASE_NAME,
            )
            database = buildDatabase()
            runBlocking {
                database.useConnection(isReadOnly = false) { Unit }
            }
        }
        return RoomStudyDatabase(
            database = database,
            clock = clock,
            freeResponseOutboxCipher = AndroidKeystoreTutorFreeResponseOutboxCipher(),
        )
    } catch (failure: Throwable) {
        try {
            database.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
}

internal class RoomTrustedModelTaskDatabaseCapability(
    database: RoomStudyDatabase,
) : TrustedModelTaskDatabaseCapability,
    ModelTaskDatabasePort by database,
    LegacyModelAssetDocumentReadPort by database

internal class RoomTrustedCaptureSessionDatabaseCapability(
    database: RoomStudyDatabase,
) : TrustedCaptureSessionDatabaseCapability,
    LegacyCaptureSessionDatabasePort by database

internal class RoomTrustedBatchSessionDatabaseCapability(
    database: RoomStudyDatabase,
) : TrustedBatchSessionDatabaseCapability,
    LegacyBatchSessionDatabasePort by database

internal class RoomTrustedOrganizationWorkDatabaseCapability(
    database: RoomStudyDatabase,
) : TrustedOrganizationWorkDatabaseCapability,
    LegacyOrganizationWorkCoordinationPort by database

internal class RoomTrustedLegacyCutoverMigrationDatabaseCapability(
    private val database: RoomStudyDatabase,
) : TrustedLegacyCutoverMigrationDatabaseCapability,
    LegacyAuthorityMigrationSourcePort by database,
    ExactLegacyCaptureStudentDocumentSourcePort by database {
    override suspend fun readStageReceipts(): List<LegacyAuthorityCutoverStageReceipt> =
        database.readLegacyAuthorityCutoverStageReceipts()

    override suspend fun prepare(
        command: PrepareCaptureStudentSaveHandoffCommand,
    ): CaptureStudentSaveHandoffWriteResult =
        database.prepareCaptureStudentSaveHandoff(command)

    override suspend fun finalize(
        command: FinalizeCaptureStudentSaveHandoffCommand,
    ): CaptureStudentSaveHandoffWriteResult =
        database.finalizeCaptureStudentSaveHandoff(command)

    override suspend fun readPending(
        query: ReadPendingCaptureStudentSaveHandoffsQuery,
    ): List<CaptureStudentSaveHandoffRecord> =
        database.readPendingCaptureStudentSaveHandoffs(query)
}

private class RoomLegacySessionDatabaseResource(
    private val database: RoomStudyDatabase,
) {
    private val closeLock = Any()
    private var closed = false

    val tutorSessions: TrustedTutorSessionDatabaseCapability =
        RoomTrustedTutorSessionDatabaseCapability(database)
    val modelTasks: TrustedModelTaskDatabaseCapability =
        RoomTrustedModelTaskDatabaseCapability(database)
    val captureSessions: TrustedCaptureSessionDatabaseCapability =
        RoomTrustedCaptureSessionDatabaseCapability(database)
    val batchSessions: TrustedBatchSessionDatabaseCapability =
        RoomTrustedBatchSessionDatabaseCapability(database)
    val organizationWork: TrustedOrganizationWorkDatabaseCapability =
        RoomTrustedOrganizationWorkDatabaseCapability(database)
    val cutoverMigration: TrustedLegacyCutoverMigrationDatabaseCapability =
        RoomTrustedLegacyCutoverMigrationDatabaseCapability(database)
    val terminalCutoverJournalWriter: LegacyAuthorityCutoverJournalPort =
        StudyDatabaseLegacyAuthorityCutoverJournalAdapter(
            appendReceipt = database::appendLegacyAuthorityCutoverStageReceipt,
            readReceipts = database::readLegacyAuthorityCutoverStageReceipts,
        )
    val terminalBusinessWriteBarrierOwner: LegacyBusinessWriteBarrierOwnerPort =
        RoomLegacyBusinessWriteBarrierOwnerPort(
            RoomLegacyBusinessWriteBarrierCapability(database),
        )

    fun close() {
        synchronized(closeLock) {
            if (closed) return
            closed = true
            database.close()
        }
    }
}

private class RoomLegacySessionDatabaseOwner(
    private val lease:
        ReferenceCountedLegacySessionOwnerLease<RoomLegacySessionDatabaseResource>,
) : LegacySessionDatabaseOwner {
    override fun tutorSessions(): TrustedTutorSessionDatabaseCapability =
        lease.useResource(RoomLegacySessionDatabaseResource::tutorSessions)

    override fun modelTasksAndAssetDocuments(): TrustedModelTaskDatabaseCapability =
        lease.useResource(RoomLegacySessionDatabaseResource::modelTasks)

    override fun captureSessions(): TrustedCaptureSessionDatabaseCapability =
        lease.useResource(RoomLegacySessionDatabaseResource::captureSessions)

    override fun batchSessions(): TrustedBatchSessionDatabaseCapability =
        lease.useResource(RoomLegacySessionDatabaseResource::batchSessions)

    override fun organizationWork(): TrustedOrganizationWorkDatabaseCapability =
        lease.useResource(RoomLegacySessionDatabaseResource::organizationWork)

    override fun cutoverMigrationProof(): TrustedLegacyCutoverMigrationDatabaseCapability =
        lease.useResource(RoomLegacySessionDatabaseResource::cutoverMigration)

    override fun close() = lease.close()
}

/**
 * Lease-bound terminal authority.
 *
 * Returned ports never retain Room or a raw database adapter. Every operation obtains an
 * in-flight use of the bridge, so closing the bridge revokes previously returned ports and waits
 * to release the exclusive writer until the last suspending operation finishes.
 */
private class LegacyTerminalAuthorityBridge(
    ownedLease:
        ExclusiveLegacyTerminalWriterRegistry.OwnedLease<
            ReferenceCountedLegacySessionOwnerLease<RoomLegacySessionDatabaseResource>,
        >,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private var ownedLease:
        ExclusiveLegacyTerminalWriterRegistry.OwnedLease<
            ReferenceCountedLegacySessionOwnerLease<RoomLegacySessionDatabaseResource>,
        >? = ownedLease
    private var closeRequested = false
    private var activeOperations = 0

    private val journalPort =
        object : LegacyAuthorityCutoverJournalPort {
            override suspend fun appendStageReceipt(
                command: AppendLegacyAuthorityCutoverStageCommand,
            ): LegacyAuthorityCutoverJournalWriteResult =
                withResource { resource ->
                    resource.terminalCutoverJournalWriter.appendStageReceipt(command)
                }

            override suspend fun readStageReceipts():
                List<LegacyAuthorityCutoverStageReceipt> =
                withResource { resource ->
                    resource.terminalCutoverJournalWriter.readStageReceipts()
                }
        }

    private val barrierPort =
        object : LegacyBusinessWriteBarrierOwnerPort {
            override suspend fun readState(): LegacyBusinessWriteBarrierState =
                withResource { resource ->
                    resource.terminalBusinessWriteBarrierOwner.readState()
                }

            override suspend fun activateTerminalCutover(
                terminalReceiptFingerprint: String,
            ): LegacyBusinessWriteBarrierOwnerResult =
                withResource { resource ->
                    resource.terminalBusinessWriteBarrierOwner.activateTerminalCutover(
                        terminalReceiptFingerprint,
                    )
                }
        }

    fun cutoverJournal(): LegacyAuthorityCutoverJournalPort =
        synchronized(lifecycleLock) {
            check(!closeRequested) { "Legacy terminal authority bridge is closed" }
            journalPort
        }

    fun writeBarrier(): LegacyBusinessWriteBarrierOwnerPort =
        synchronized(lifecycleLock) {
            check(!closeRequested) { "Legacy terminal authority bridge is closed" }
            barrierPort
        }

    private suspend fun <T> withResource(
        block: suspend (RoomLegacySessionDatabaseResource) -> T,
    ): T {
        val resource = beginOperation()
        try {
            return block(resource)
        } finally {
            endOperation()
        }
    }

    private fun beginOperation(): RoomLegacySessionDatabaseResource =
        synchronized(lifecycleLock) {
            check(!closeRequested) { "Legacy terminal authority bridge is closed" }
            val activeLease =
                checkNotNull(ownedLease) {
                    "Legacy terminal authority bridge has no active owner lease"
                }
            activeOperations += 1
            try {
                activeLease.resourceLease().useResource { resource -> resource }
            } catch (failure: Throwable) {
                activeOperations -= 1
                throw failure
            }
        }

    private fun endOperation() {
        val leaseToClose =
            synchronized(lifecycleLock) {
                check(activeOperations > 0) {
                    "Legacy terminal authority operation count is already zero"
                }
                activeOperations -= 1
                if (closeRequested && activeOperations == 0) {
                    ownedLease.also { ownedLease = null }
                } else {
                    null
                }
            }
        leaseToClose?.close()
    }

    override fun close() {
        val leaseToClose =
            synchronized(lifecycleLock) {
                if (closeRequested) {
                    null
                } else {
                    closeRequested = true
                    if (activeOperations == 0) {
                        ownedLease.also { ownedLease = null }
                    } else {
                        null
                    }
                }
            }
        leaseToClose?.close()
    }
}

internal class ReferenceCountedLegacySessionOwnerRegistry<K : Any, R : Any>(
    private val closeResource: (R) -> Unit,
) {
    private data class Entry<R : Any>(
        val resource: R,
        var leaseCount: Int,
    )

    private val activeEntries = mutableMapOf<K, Entry<R>>()

    fun acquire(
        key: K,
        create: () -> R,
    ): ReferenceCountedLegacySessionOwnerLease<R> = synchronized(activeEntries) {
        val entry =
            activeEntries[key]?.also { existing ->
                check(existing.leaseCount > 0) {
                    "Legacy session database resource is closing"
                }
            } ?: Entry(
                resource = create(),
                leaseCount = 0,
            ).also { created ->
                activeEntries[key] = created
            }
        entry.leaseCount += 1
        ReferenceCountedLegacySessionOwnerLease(entry.resource) {
            release(key, entry.resource)
        }
    }

    private fun release(
        key: K,
        expectedResource: R,
    ) = synchronized(activeEntries) {
        val entry =
            checkNotNull(activeEntries[key]) {
                "Legacy session database lease has no active resource"
            }
        check(entry.resource === expectedResource) {
            "Legacy session database lease belongs to a replaced resource"
        }
        check(entry.leaseCount > 0) {
            "Legacy session database lease count is already zero"
        }

        entry.leaseCount -= 1
        if (entry.leaseCount == 0) {
            try {
                closeResource(entry.resource)
            } finally {
                check(activeEntries[key] === entry)
                activeEntries.remove(key)
            }
        }
    }
}

internal class ReferenceCountedLegacySessionOwnerLease<R : Any>(
    private val resource: R,
    private val release: () -> Unit,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private var closed = false

    fun <T> useResource(block: (R) -> T): T = synchronized(lifecycleLock) {
        check(!closed) { "Legacy session database owner lease is closed" }
        block(resource)
    }

    override fun close() {
        val shouldRelease =
            synchronized(lifecycleLock) {
                if (closed) {
                    false
                } else {
                    closed = true
                    true
                }
            }
        if (shouldRelease) {
            release()
        }
    }
}
