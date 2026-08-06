package com.tingyun.smartmistakebook.core.knowledge.database

import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Trusted metadata delivered beside a reviewed pack. Generation is an independent monotonic
 * sequence; content versions are identities, not an ordering mechanism.
 */
internal data class KnowledgePackActivationAuthorization(
    val packId: String,
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val generation: Long,
    val expectedContentFingerprint: String,
) {
    init {
        packId.requireActivationId("Authorized pack id")
        knowledgePackVersion.requireActivationId("Authorized pack version")
        taxonomyVersion.requireActivationId("Authorized taxonomy version")
        require(generation > 0L) { "Knowledge-pack generation must be positive" }
        require(expectedContentFingerprint.isSha256Fingerprint()) {
            "Authorized knowledge-pack fingerprint must be lowercase SHA-256"
        }
    }
}

/**
 * Successful activation proof. Its constructor is private and the class intentionally has no
 * `copy` method, so ordinary application code can forward but cannot accidentally manufacture or
 * alter the proof. It is not a sandbox against malicious in-process reflection.
 */
class KnowledgeCatalogActivationReceipt private constructor(
    val generation: Long,
    val activatedAtEpochMillis: Long,
    val packId: String,
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val manifestFingerprint: String,
) {
    init {
        require(generation > 0L)
        require(activatedAtEpochMillis >= 0L)
        packId.requireActivationId("Activated pack id")
        knowledgePackVersion.requireActivationId("Activated pack version")
        taxonomyVersion.requireActivationId("Activated taxonomy version")
        require(manifestFingerprint.isSha256Fingerprint())
    }

    internal companion object {
        fun create(
            generation: Long,
            activatedAtEpochMillis: Long,
            packId: String,
            knowledgePackVersion: String,
            taxonomyVersion: String,
            manifestFingerprint: String,
        ): KnowledgeCatalogActivationReceipt =
            KnowledgeCatalogActivationReceipt(
                generation = generation,
                activatedAtEpochMillis = activatedAtEpochMillis,
                packId = packId,
                knowledgePackVersion = knowledgePackVersion,
                taxonomyVersion = taxonomyVersion,
                manifestFingerprint = manifestFingerprint,
            )
    }
}

internal data class KnowledgeCatalogRuntimeTrust(
    val generation: Long,
    val packId: String,
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val manifestFingerprint: String,
)

private data class ValidatedClosedKnowledgePack(
    val manifest: KnowledgePackManifestEntity,
)

/**
 * Copies a pinned closed `.next` database into an exclusive immutable snapshot, validates that
 * exact inode through physical read-only connections, then promotes only the validated snapshot.
 * Runtime readers never receive this mutation surface. The lifecycle monitor deliberately covers
 * one app process; a future multi-process deployment must add an operating-system file lock around
 * this same state machine.
 */
internal object KnowledgePackActivationManager {
    internal class BuilderLease internal constructor() {
        internal var closed: Boolean = false
    }

    internal class RuntimeLease internal constructor() {
        internal var closed: Boolean = false
    }

    suspend fun activateNext(
        context: Context,
        authorization: KnowledgePackActivationAuthorization,
    ): KnowledgeCatalogActivationReceipt =
        activateNextWithPromotionHook(context, authorization) {}

    internal suspend fun activateNextForTest(
        context: Context,
        authorization: KnowledgePackActivationAuthorization,
        beforeSnapshotPromotion: (File) -> Unit,
    ): KnowledgeCatalogActivationReceipt =
        activateNextWithPromotionHook(context, authorization, beforeSnapshotPromotion)

    private suspend fun activateNextWithPromotionHook(
        context: Context,
        authorization: KnowledgePackActivationAuthorization,
        beforeSnapshotPromotion: (File) -> Unit,
    ): KnowledgeCatalogActivationReceipt {
        beginActivation(context.applicationContext)
        return try {
            activateNextExclusively(context, authorization, beforeSnapshotPromotion)
        } finally {
            finishActivation()
        }
    }

    private suspend fun activateNextExclusively(
        context: Context,
        authorization: KnowledgePackActivationAuthorization,
        beforeSnapshotPromotion: (File) -> Unit,
    ): KnowledgeCatalogActivationReceipt {
        val applicationContext = context.applicationContext
        val candidateFile =
            applicationContext.getDatabasePath(HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME)
        val activeFile =
            applicationContext.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
        val validationFile = validationSnapshotFile(applicationContext)
        val receiptFile = activationReceiptFile(applicationContext)
        val pendingReceiptFile = pendingActivationReceiptFile(applicationContext)
        val rollbackFile = rollbackDatabaseFile(applicationContext)
        val databaseDirectory = requireNotNull(activeFile.parentFile)
        removeEmptyCandidateSidecars(candidateFile)
        requireClosedSingleFile(candidateFile, "Candidate knowledge pack")
        deleteTrustedRoomLockCompanionIfPresent(candidateFile)
        check(!pathEntryExists(validationFile)) {
            "A previous knowledge-pack validation snapshot still exists"
        }

        return try {
            PinnedRegularFile.open(candidateFile, "Candidate knowledge pack").use { candidatePin ->
                candidatePin
                    .copyToNewFile(
                        destination = validationFile,
                        destinationLabel = "Knowledge-pack validation snapshot",
                        makeReadOnly = true,
                    ).use { validationPin ->
                        syncDirectory(databaseDirectory)
                        candidatePin.requireAtPath(candidateFile)
                        requireClosedSingleFile(candidateFile, "Candidate knowledge pack")
                        validationPin.requireImmutableAtPath(validationFile)
                        val candidate =
                            readValidatedPack(
                                context = applicationContext,
                                databaseName = validationFile.name,
                                authorization = authorization,
                                pinnedFile = validationPin,
                                deleteRoomLockAfterValidation = true,
                            )
                        val candidateManifest = candidate.manifest
                        val hadActiveInstall = pathEntryExists(activeFile)
                        val nextReceipt =
                            PersistedKnowledgeCatalogActivationReceipt(
                                generation = authorization.generation,
                                activatedAtEpochMillis =
                                    System.currentTimeMillis().coerceAtLeast(0L),
                                packId = candidateManifest.packId,
                                knowledgePackVersion = candidateManifest.knowledgePackVersion,
                                taxonomyVersion = candidateManifest.taxonomyVersion,
                                searchIndexVersion = candidateManifest.searchIndexVersion,
                                manifestFingerprint = candidateManifest.contentFingerprint,
                                registryAuthorizationIdentity =
                                    trustedKnowledgePackAuthorizationIdentity(
                                        packId = candidateManifest.packId,
                                        knowledgePackVersion =
                                            candidateManifest.knowledgePackVersion,
                                        taxonomyVersion = candidateManifest.taxonomyVersion,
                                        searchIndexVersion = candidateManifest.searchIndexVersion,
                                        generation = authorization.generation,
                                        contentFingerprint =
                                            candidateManifest.contentFingerprint,
                                    ),
                            )
                        check(
                            !pathEntryExists(pendingReceiptFile) &&
                                !pathEntryExists(rollbackFile),
                        ) {
                            "A previous knowledge-pack activation must be recovered first"
                        }

                        var activePin: PinnedRegularFile? = null
                        var currentReceipt: PersistedKnowledgeCatalogActivationReceipt? = null
                        try {
                            if (hadActiveInstall) {
                                requireClosedSingleFile(activeFile, "Active knowledge pack")
                                val pinnedActive =
                                    PinnedRegularFile.open(activeFile, "Active knowledge pack")
                                activePin = pinnedActive
                                val persistedCurrentReceipt = readActivationReceipt(receiptFile)
                                currentReceipt = persistedCurrentReceipt
                                val current =
                                    readValidatedPack(
                                        context = applicationContext,
                                        databaseName = activeFile.name,
                                        pinnedFile = pinnedActive,
                                    )
                                requireReceiptMatchesManifest(
                                    persistedCurrentReceipt,
                                    current.manifest,
                                )
                                require(
                                    authorization.generation > persistedCurrentReceipt.generation,
                                ) {
                                    "Knowledge-pack generation must advance monotonically"
                                }
                                require(
                                    candidateManifest.knowledgePackVersion !=
                                        current.manifest.knowledgePackVersion,
                                ) {
                                    "An installed knowledge-pack version cannot be replaced"
                                }
                                require(candidateManifest.packId == current.manifest.packId) {
                                    "Knowledge-pack activation cannot switch package lineage"
                                }
                                createRollbackSnapshot(
                                    context = applicationContext,
                                    activePinned = pinnedActive,
                                    activeFile = activeFile,
                                    rollbackFile = rollbackFile,
                                    currentReceipt = persistedCurrentReceipt,
                                    expectedManifest = current.manifest,
                                )
                            } else {
                                check(!pathEntryExists(receiptFile)) {
                                    "Knowledge-pack activation receipt exists without an active database"
                                }
                            }

                            candidatePin.unlinkAtPath(candidateFile)
                            deleteTrustedRoomLockCompanionIfPresent(candidateFile)
                            syncDirectory(databaseDirectory)
                            writeAndSyncReceipt(pendingReceiptFile, nextReceipt)
                            syncDirectory(databaseDirectory)

                            PinnedRegularFile
                                .open(
                                    file = pendingReceiptFile,
                                    label = "Pending knowledge-pack activation receipt",
                                    maximumBytes = MAX_ACTIVATION_RECEIPT_BYTES,
                                ).use { pendingReceiptPin ->
                                    check(readActivationReceipt(pendingReceiptPin) == nextReceipt) {
                                        "Pending knowledge-pack activation receipt changed"
                                    }
                                    beforeSnapshotPromotion(validationFile)
                                    requirePromotionReady(
                                        validationPin = validationPin,
                                        validationFile = validationFile,
                                        activeFile = activeFile,
                                    )
                                    activePin?.requireAtPath(activeFile)
                                    currentReceipt?.let { expectedReceipt ->
                                        check(readActivationReceipt(receiptFile) == expectedReceipt) {
                                            "Active knowledge-pack receipt changed before promotion"
                                        }
                                    }
                                    if (!hadActiveInstall) {
                                        check(
                                            !pathEntryExists(activeFile) &&
                                                !pathEntryExists(receiptFile),
                                        ) {
                                            "First knowledge-pack install changed before promotion"
                                        }
                                    }
                                    pendingReceiptPin.requireAtPath(pendingReceiptFile)
                                    syncDirectory(databaseDirectory)

                                    Os.rename(
                                        validationFile.absolutePath,
                                        activeFile.absolutePath,
                                    )
                                    validationPin.requireAtPath(activeFile)
                                    syncDirectory(databaseDirectory)
                                    Os.rename(
                                        pendingReceiptFile.absolutePath,
                                        receiptFile.absolutePath,
                                    )
                                    pendingReceiptPin.requireAtPath(receiptFile)
                                    validationPin.requireAtPath(activeFile)
                                    syncDirectory(databaseDirectory)
                                }
                        } finally {
                            activePin?.close()
                        }

                        deleteUntrustedDatabaseArtifacts(rollbackFile)
                        syncDirectory(databaseDirectory)
                        validationPin.requireAtPath(activeFile)
                        val persistedReceipt = readActivationReceipt(receiptFile)
                        requireReceiptMatchesManifest(persistedReceipt, candidateManifest)
                        check(persistedReceipt.generation == authorization.generation) {
                            "Activated knowledge-pack generation was not persisted"
                        }
                        persistedReceipt.toPublicReceipt()
                    }
            }
        } catch (failure: Throwable) {
            runCatching {
                recoverPendingActivationMetadata(applicationContext)
            }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    /**
     * Production trust verification, the physical Room snapshot construction, and runtime lease
     * registration share one monitor with activation. Activation cannot rename the active database
     * until every returned catalog has closed its lease.
     */
    fun <T> openRuntimeSnapshot(
        context: Context,
        open: (KnowledgeCatalogRuntimeTrust, RuntimeLease) -> T,
    ): T =
        synchronized(lifecycleMonitor) {
            requireIdle("Knowledge catalog cannot open during a pack lifecycle operation")
            recoverPendingActivationMetadata(context.applicationContext)
            val trust = verifyRuntimeInstallLocked(context.applicationContext)
            val lease = RuntimeLease()
            check(runtimeLeases.add(lease))
            try {
                open(trust, lease)
            } catch (failure: Throwable) {
                // The catalog factory can fail before it has a database handle to close. Never
                // leave that failed open registered as a live reader, otherwise every later pack
                // activation is blocked until process death.
                if (!lease.closed) {
                    lease.closed = true
                    check(runtimeLeases.remove(lease)) {
                        "Failed runtime knowledge-catalog open lost its registered lease"
                    }
                }
                throw failure
            }
        }

    fun abortRuntimeSnapshotOpen(lease: RuntimeLease) {
        synchronized(lifecycleMonitor) {
            check(runtimeLeases.contains(lease) && !lease.closed) {
                "Runtime knowledge-catalog lease is not awaiting open completion"
            }
            lease.closed = true
            runtimeLeases.remove(lease)
        }
    }

    fun closeRuntimeSnapshot(
        lease: RuntimeLease,
        close: () -> Unit,
    ) {
        synchronized(lifecycleMonitor) {
            if (lease.closed) return
            check(runtimeLeases.contains(lease)) {
                "Runtime knowledge-catalog lease is not active"
            }
            close()
            lease.closed = true
            runtimeLeases.remove(lease)
        }
    }

    /**
     * Returns the verified receipt for idempotent trusted provisioning, or null when neither the
     * production catalog nor its receipt exists. No database, DAO, or mutation handle escapes.
     */
    fun currentActivationReceipt(context: Context): KnowledgeCatalogActivationReceipt? =
        synchronized(lifecycleMonitor) {
            requireIdle("Active knowledge-pack metadata is unavailable during a lifecycle operation")
            val applicationContext = context.applicationContext
            recoverPendingActivationMetadata(applicationContext)
            val activeFile =
                applicationContext.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
            val receiptFile = activationReceiptFile(applicationContext)
            if (!pathEntryExists(activeFile) || !pathEntryExists(receiptFile)) {
                check(!pathEntryExists(activeFile) && !pathEntryExists(receiptFile)) {
                    "Active knowledge-pack database and activation receipt must exist together"
                }
                return@synchronized null
            }
            requireClosedSingleFile(activeFile, "Active knowledge pack")
            val persisted = readActivationReceipt(receiptFile)
            val manifest = readManifestReadOnly(activeFile, runIntegrityCheck = false)
            requireReceiptMatchesManifest(persisted, manifest)
            persisted.toPublicReceipt()
        }

    /**
     * Revalidates the complete active database and its pinned activation receipt. A historical
     * sample, an unregistered pack, a non-production registry entry, or any verification failure
     * produces no witness.
     */
    suspend fun readFreshProductionCutoverWitness(
        context: Context,
    ): ProductionKnowledgeActivationWitness? {
        var verificationStarted = false
        return try {
            beginProductionCutoverVerification(context.applicationContext)
            verificationStarted = true
            readProductionCutoverWitnessExclusively(context.applicationContext)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            if (verificationStarted) finishProductionCutoverVerification()
        }
    }

    fun beginNextBuild(context: Context): BuilderLease =
        synchronized(lifecycleMonitor) {
            requireIdle("A candidate cannot be built during a pack lifecycle operation")
            recoverPendingActivationMetadata(context.applicationContext)
            val candidate =
                context.applicationContext.getDatabasePath(
                    HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME,
                )
            check(
                !pathEntryExists(candidate) &&
                    candidateSidecars(candidate).none(::pathEntryExists),
            ) {
                "A candidate knowledge pack already exists"
            }
            check(
                !pathEntryExists(pendingActivationReceiptFile(context.applicationContext)) &&
                    !pathEntryExists(rollbackDatabaseFile(context.applicationContext)) &&
                    !pathEntryExists(validationSnapshotFile(context.applicationContext)),
            ) {
                "A previous knowledge-pack activation is incomplete"
            }
            val lease = BuilderLease()
            activeBuilderLease = lease
            lifecyclePhase = LifecyclePhase.BUILDING
            lease
        }

    fun abortNextBuildOpen(lease: BuilderLease) {
        synchronized(lifecycleMonitor) {
            requireActiveBuilder(lease)
            check(!builderWriteInProgress) {
                "A candidate build cannot abort while a write is in progress"
            }
            lease.closed = true
            activeBuilderLease = null
            lifecyclePhase = LifecyclePhase.IDLE
        }
    }

    fun beginBuilderWrite(lease: BuilderLease) {
        synchronized(lifecycleMonitor) {
            requireActiveBuilder(lease)
            check(!builderWriteInProgress) {
                "A knowledge-pack builder write is already in progress"
            }
            builderWriteInProgress = true
        }
    }

    fun endBuilderWrite(lease: BuilderLease) {
        synchronized(lifecycleMonitor) {
            requireActiveBuilder(lease)
            check(builderWriteInProgress) {
                "Knowledge-pack builder write state is inconsistent"
            }
            builderWriteInProgress = false
        }
    }

    fun closeBuilder(
        lease: BuilderLease,
        close: () -> Unit,
    ) {
        synchronized(lifecycleMonitor) {
            if (lease.closed) return
            requireActiveBuilder(lease)
            check(!builderWriteInProgress) {
                "Knowledge-pack builder cannot close while a write is in progress"
            }
            close()
            lease.closed = true
            activeBuilderLease = null
            lifecyclePhase = LifecyclePhase.IDLE
        }
    }

    /**
     * Explicit recovery surface for a trusted update coordinator. It never touches the active pack.
     */
    fun discardNext(context: Context) {
        synchronized(lifecycleMonitor) {
            requireIdle("A candidate cannot be discarded during a pack lifecycle operation")
            val applicationContext = context.applicationContext
            recoverPendingActivationMetadata(applicationContext)
            lifecyclePhase = LifecyclePhase.DISCARDING
            try {
                val candidate =
                    applicationContext.getDatabasePath(
                        HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME,
                    )
                deleteUntrustedDatabaseArtifacts(candidate)
                check(
                    !pathEntryExists(candidate) &&
                        candidateSidecars(candidate).none(::pathEntryExists),
                ) {
                    "Unable to discard candidate knowledge pack"
                }
                pendingActivationReceiptFile(applicationContext).let { pending ->
                    deleteRecoveryFileIfPresent(pending)
                }
                deleteUntrustedDatabaseArtifacts(rollbackDatabaseFile(applicationContext))
                deleteUntrustedDatabaseArtifacts(validationSnapshotFile(applicationContext))
                syncDirectory(requireNotNull(candidate.parentFile))
            } finally {
                lifecyclePhase = LifecyclePhase.IDLE
            }
        }
    }

    private suspend fun createRollbackSnapshot(
        context: Context,
        activePinned: PinnedRegularFile,
        activeFile: File,
        rollbackFile: File,
        currentReceipt: PersistedKnowledgeCatalogActivationReceipt,
        expectedManifest: KnowledgePackManifestEntity,
    ) {
        check(!pathEntryExists(rollbackFile)) {
            "A previous knowledge-pack rollback snapshot still exists"
        }
        val rollback =
            activePinned
                .copyToNewFile(
                    destination = rollbackFile,
                    destinationLabel = "Knowledge-pack rollback snapshot",
                    makeReadOnly = true,
                ).use { rollbackPinned ->
                    syncDirectory(requireNotNull(activeFile.parentFile))
                    activePinned.requireAtPath(activeFile)
                    rollbackPinned.requireImmutableAtPath(rollbackFile)
                    readValidatedPack(
                        context = context,
                        databaseName = rollbackFile.name,
                        pinnedFile = rollbackPinned,
                        deleteRoomLockAfterValidation = true,
                    )
                }
        check(rollback.manifest == expectedManifest) {
            "Knowledge-pack rollback snapshot changed during copy"
        }
        requireReceiptMatchesManifest(currentReceipt, rollback.manifest)
    }

    private suspend fun readValidatedPack(
        context: Context,
        databaseName: String,
        authorization: KnowledgePackActivationAuthorization? = null,
        pinnedFile: PinnedRegularFile? = null,
        deleteRoomLockAfterValidation: Boolean = false,
    ): ValidatedClosedKnowledgePack {
        val databaseFile = context.getDatabasePath(databaseName)
        pinnedFile?.requireAtPath(databaseFile)
        val stableReadPath = pinnedFile?.openStableReadPath()
        return try {
            // Room creates a sibling `.lck` file, so `/proc/self/fd/<n>` is not a usable Room
            // database name. The app-private immutable snapshot path is checked against the pinned
            // inode before and after Room's schema gate; raw SQLite verification below still reads
            // the exact pinned descriptor path.
            val verifiedDatabaseName = databaseName
            val schemaDatabase =
                HighSchoolKnowledgeCatalogFactory.openReadOnlyExistingDatabase(
                    context,
                    verifiedDatabaseName,
                )
            val schemaValidatedManifest =
                try {
                    checkNotNull(schemaDatabase.verificationDao().readManifest()) {
                        "Knowledge pack has no active manifest"
                    }
                } finally {
                    try {
                        schemaDatabase.close()
                    } finally {
                        if (deleteRoomLockAfterValidation) {
                            deleteTrustedRoomLockCompanionIfPresent(databaseFile)
                        }
                    }
                }
            requireManifestAuthorization(schemaValidatedManifest, authorization)
            pinnedFile?.requireAtPath(databaseFile)
            val manifest =
                readValidatedContentReadOnly(
                    databaseFile = databaseFile,
                    authorization = authorization,
                    verifiedReadPath = stableReadPath?.file ?: databaseFile,
                    pinnedFile = pinnedFile,
                )
            check(manifest == schemaValidatedManifest) {
                "Knowledge-pack manifest changed after schema validation"
            }
            pinnedFile?.requireAtPath(databaseFile)
            ValidatedClosedKnowledgePack(manifest)
        } finally {
            stableReadPath?.close()
        }
    }

    private suspend fun readProductionCutoverWitnessExclusively(
        context: Context,
    ): ProductionKnowledgeActivationWitness? {
        val activeFile = context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
        val receiptFile = activationReceiptFile(context)
        if (!pathEntryExists(activeFile) || !pathEntryExists(receiptFile)) {
            check(!pathEntryExists(activeFile) && !pathEntryExists(receiptFile)) {
                "Active knowledge-pack database and activation receipt must exist together"
            }
            return null
        }
        requireClosedSingleFile(activeFile, "Active knowledge pack")
        return PinnedRegularFile.open(activeFile, "Active knowledge pack").use { pinnedDatabase ->
            PinnedRegularFile
                .open(
                    file = receiptFile,
                    label = "Knowledge-pack activation receipt",
                    maximumBytes = MAX_ACTIVATION_RECEIPT_BYTES,
                ).use { pinnedReceipt ->
                    val receipt = readActivationReceipt(pinnedReceipt)
                    val verifiedPack =
                        readValidatedPack(
                            context = context,
                            databaseName = HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME,
                            pinnedFile = pinnedDatabase,
                        )
                    pinnedDatabase.requireAtPath(activeFile)
                    pinnedReceipt.requireAtPath(receiptFile)
                    requireReceiptMatchesManifest(receipt, verifiedPack.manifest)
                    val registeredForProduction =
                        isRegisteredProductionCutoverActivation(
                            packId = verifiedPack.manifest.packId,
                            knowledgePackVersion =
                                verifiedPack.manifest.knowledgePackVersion,
                            taxonomyVersion = verifiedPack.manifest.taxonomyVersion,
                            searchIndexVersion = verifiedPack.manifest.searchIndexVersion,
                            generation = receipt.generation,
                            contentFingerprint = verifiedPack.manifest.contentFingerprint,
                        )
                    if (!registeredForProduction) {
                        null
                    } else {
                        ProductionKnowledgeActivationWitness.issue(
                            activationGeneration = receipt.generation,
                            activatedAtEpochMillis = receipt.activatedAtEpochMillis,
                            packId = receipt.packId,
                            knowledgePackVersion = receipt.knowledgePackVersion,
                            taxonomyVersion = receipt.taxonomyVersion,
                            manifestFingerprint = receipt.manifestFingerprint,
                        )
                    }
                }
        }
    }

    private fun beginActivation(context: Context) {
        synchronized(lifecycleMonitor) {
            requireIdle("A knowledge pack cannot activate during another lifecycle operation")
            check(runtimeLeases.isEmpty()) {
                "A knowledge pack cannot activate while a runtime catalog is open"
            }
            check(activeBuilderLease == null) {
                "A knowledge pack cannot activate while its writable builder is open"
            }
            recoverPendingActivationMetadata(context)
            lifecyclePhase = LifecyclePhase.ACTIVATING
        }
    }

    private fun beginProductionCutoverVerification(context: Context) {
        synchronized(lifecycleMonitor) {
            requireIdle(
                "Knowledge cutover witness is unavailable during a pack lifecycle operation",
            )
            recoverPendingActivationMetadata(context)
            lifecyclePhase = LifecyclePhase.VERIFYING_CUTOVER
        }
    }

    private fun finishProductionCutoverVerification() {
        synchronized(lifecycleMonitor) {
            check(lifecyclePhase == LifecyclePhase.VERIFYING_CUTOVER) {
                "Knowledge cutover witness verification state is inconsistent"
            }
            lifecyclePhase = LifecyclePhase.IDLE
        }
    }

    private fun finishActivation() {
        synchronized(lifecycleMonitor) {
            check(lifecyclePhase == LifecyclePhase.ACTIVATING) {
                "Knowledge-pack activation state is inconsistent"
            }
            lifecyclePhase = LifecyclePhase.IDLE
        }
    }

    /**
     * Production open is fail-closed: a database without the matching monotonic activation receipt
     * is not treated as an installed catalog. The lifecycle monitor is held by the caller.
     */
    private fun verifyRuntimeInstallLocked(context: Context): KnowledgeCatalogRuntimeTrust {
        val activeFile = context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
        requireClosedSingleFile(activeFile, "Active knowledge pack")
        val receipt = readActivationReceipt(activationReceiptFile(context))
        val manifest = readManifestReadOnly(activeFile, runIntegrityCheck = false)
        requireReceiptMatchesManifest(receipt, manifest)
        return KnowledgeCatalogRuntimeTrust(
            generation = receipt.generation,
            packId = receipt.packId,
            knowledgePackVersion = receipt.knowledgePackVersion,
            taxonomyVersion = receipt.taxonomyVersion,
            manifestFingerprint = receipt.manifestFingerprint,
        )
    }

    private fun requireActiveBuilder(lease: BuilderLease) {
        check(
            lifecyclePhase == LifecyclePhase.BUILDING &&
                activeBuilderLease === lease &&
                !lease.closed,
        ) {
            "Knowledge-pack build handle is closed or no longer active"
        }
    }

    private fun requireIdle(message: String) {
        check(lifecyclePhase == LifecyclePhase.IDLE && activeBuilderLease == null) {
            message
        }
    }

    private fun recoverPendingActivationMetadata(context: Context) {
        val pendingFile = pendingActivationReceiptFile(context)
        val activeFile = context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
        val receiptFile = activationReceiptFile(context)
        val rollbackFile = rollbackDatabaseFile(context)
        val validationFile = validationSnapshotFile(context)
        val hasPending = pathEntryExists(pendingFile)
        val hasRollback = pathEntryExists(rollbackFile)
        val hasValidation = pathEntryExists(validationFile)
        if (!hasPending && !hasRollback && !hasValidation) return

        val hasReceipt = pathEntryExists(receiptFile)
        val currentResult =
            if (hasReceipt) runCatching { readActivationReceipt(receiptFile) } else null
        val current = currentResult?.getOrNull()
        val pendingResult =
            if (hasPending) runCatching { readActivationReceipt(pendingFile) } else null
        val pending = pendingResult?.getOrNull()
        val rollbackPackResult =
            if (hasRollback) {
                runCatching {
                    openFullyValidatedPinnedPack(
                        rollbackFile,
                        "Knowledge-pack rollback snapshot",
                    )
                }
            } else {
                null
            }
        val rollbackPack = rollbackPackResult?.getOrNull()
        val hasActive = pathEntryExists(activeFile)
        val activePackResult =
            if (hasActive) {
                runCatching {
                    openFullyValidatedPinnedPack(activeFile, "Active knowledge pack")
                }
            } else {
                null
            }
        val activePack = activePackResult?.getOrNull()
        val pendingAuthorizationResult =
            if (pending != null && activePack != null) {
                runCatching {
                    requirePendingReceiptRegistryAuthorization(pending, activePack.manifest)
                }
            } else {
                null
            }
        val databaseDirectory = requireNotNull(activeFile.parentFile)

        try {
            if (hasValidation) {
                when {
                    activePack != null &&
                        current != null &&
                        receiptMatchesManifest(current, activePack.manifest) -> {
                        activePack.pinnedFile.requireAtPath(activeFile)
                        check(readActivationReceipt(receiptFile) == current) {
                            "Active knowledge-pack receipt changed during recovery"
                        }
                        deleteUntrustedInternalPathIfPresent(pendingFile)
                        deleteUntrustedDatabaseArtifacts(rollbackFile)
                        deleteUntrustedDatabaseArtifacts(validationFile)
                        syncDirectory(databaseDirectory)
                        return
                    }

                    rollbackPack != null &&
                        current != null &&
                        receiptMatchesManifest(current, rollbackPack.manifest) -> {
                        restorePinnedRollback(
                            rollbackPack = rollbackPack,
                            rollbackFile = rollbackFile,
                            activeFile = activeFile,
                            expectedReceipt = current,
                            receiptFile = receiptFile,
                        )
                        deleteUntrustedInternalPathIfPresent(pendingFile)
                        deleteUntrustedDatabaseArtifacts(validationFile)
                        syncDirectory(databaseDirectory)
                        return
                    }

                    !hasReceipt -> {
                        deleteUntrustedDatabaseArtifacts(activeFile)
                        deleteUntrustedInternalPathIfPresent(pendingFile)
                        deleteUntrustedDatabaseArtifacts(rollbackFile)
                        deleteUntrustedDatabaseArtifacts(validationFile)
                        syncDirectory(databaseDirectory)
                        return
                    }

                    else -> {
                        currentResult?.exceptionOrNull()?.let { throw it }
                        activePackResult?.exceptionOrNull()?.let { throw it }
                        rollbackPackResult?.exceptionOrNull()?.let { throw it }
                        pendingResult?.exceptionOrNull()?.let { throw it }
                        error(
                            "Knowledge-pack validation snapshot cannot be trusted during recovery",
                        )
                    }
                }
            }

            when {
                activePack != null &&
                    pending != null &&
                    pendingAuthorizationResult?.isSuccess == true &&
                    receiptMatchesManifest(pending, activePack.manifest) -> {
                    check(
                        !hasReceipt ||
                            (current != null && pending.generation > current.generation),
                    ) {
                        "Pending knowledge-pack generation does not advance the activation receipt"
                    }
                    activePack.pinnedFile.requireAtPath(activeFile)
                    PinnedRegularFile
                        .open(
                            file = pendingFile,
                            label = "Pending knowledge-pack activation receipt",
                            maximumBytes = MAX_ACTIVATION_RECEIPT_BYTES,
                        ).use { pendingPin ->
                            check(readActivationReceipt(pendingPin) == pending) {
                                "Pending knowledge-pack activation receipt changed during recovery"
                            }
                            current?.let { expectedCurrent ->
                                check(readActivationReceipt(receiptFile) == expectedCurrent) {
                                    "Active knowledge-pack receipt changed during recovery"
                                }
                            }
                            activePack.pinnedFile.requireAtPath(activeFile)
                            Os.rename(pendingFile.absolutePath, receiptFile.absolutePath)
                            pendingPin.requireAtPath(receiptFile)
                            activePack.pinnedFile.requireAtPath(activeFile)
                        }
                    deleteUntrustedDatabaseArtifacts(rollbackFile)
                    syncDirectory(databaseDirectory)
                    return
                }

                activePack != null &&
                    current != null &&
                    receiptMatchesManifest(current, activePack.manifest) -> {
                    activePack.pinnedFile.requireAtPath(activeFile)
                    check(readActivationReceipt(receiptFile) == current) {
                        "Active knowledge-pack receipt changed during recovery"
                    }
                    deleteUntrustedInternalPathIfPresent(pendingFile)
                    deleteUntrustedDatabaseArtifacts(rollbackFile)
                    syncDirectory(databaseDirectory)
                    return
                }

                rollbackPack != null &&
                    current != null &&
                    receiptMatchesManifest(current, rollbackPack.manifest) -> {
                    restorePinnedRollback(
                        rollbackPack = rollbackPack,
                        rollbackFile = rollbackFile,
                        activeFile = activeFile,
                        expectedReceipt = current,
                        receiptFile = receiptFile,
                    )
                    deleteUntrustedInternalPathIfPresent(pendingFile)
                    syncDirectory(databaseDirectory)
                    return
                }

                !hasReceipt -> {
                    deleteUntrustedDatabaseArtifacts(activeFile)
                    deleteUntrustedInternalPathIfPresent(pendingFile)
                    deleteUntrustedDatabaseArtifacts(rollbackFile)
                    syncDirectory(databaseDirectory)
                    return
                }

                else -> {
                    currentResult?.exceptionOrNull()?.let { throw it }
                    activePackResult?.exceptionOrNull()?.let { throw it }
                    rollbackPackResult?.exceptionOrNull()?.let { throw it }
                    pendingResult?.exceptionOrNull()?.let { throw it }
                    pendingAuthorizationResult?.exceptionOrNull()?.let { throw it }
                    error("Knowledge-pack activation recovery cannot restore a trusted install")
                }
            }
        } finally {
            activePack?.close()
            rollbackPack?.close()
        }
    }

    private enum class LifecyclePhase {
        IDLE,
        BUILDING,
        ACTIVATING,
        DISCARDING,
        VERIFYING_CUTOVER,
    }

    private val lifecycleMonitor = Any()
    private var lifecyclePhase = LifecyclePhase.IDLE
    private var activeBuilderLease: BuilderLease? = null
    private var builderWriteInProgress = false
    private val runtimeLeases: MutableSet<RuntimeLease> =
        Collections.newSetFromMap(IdentityHashMap<RuntimeLease, Boolean>())
}

private class FullyValidatedPinnedKnowledgePack(
    val pinnedFile: PinnedRegularFile,
    val manifest: KnowledgePackManifestEntity,
) : Closeable {
    override fun close() = pinnedFile.close()
}

private fun openFullyValidatedPinnedPack(
    file: File,
    label: String,
): FullyValidatedPinnedKnowledgePack {
    requireClosedSingleFile(file, label)
    val pinnedFile = PinnedRegularFile.open(file, label)
    return try {
        val manifest =
            pinnedFile.openStableReadPath().use { stableReadPath ->
                readValidatedContentReadOnly(
                    databaseFile = file,
                    authorization = null,
                    verifiedReadPath = stableReadPath.file,
                    pinnedFile = pinnedFile,
                )
            }
        pinnedFile.requireAtPath(file)
        FullyValidatedPinnedKnowledgePack(pinnedFile, manifest)
    } catch (failure: Throwable) {
        pinnedFile.close()
        throw failure
    }
}

private fun restorePinnedRollback(
    rollbackPack: FullyValidatedPinnedKnowledgePack,
    rollbackFile: File,
    activeFile: File,
    expectedReceipt: PersistedKnowledgeCatalogActivationReceipt,
    receiptFile: File,
) {
    rollbackPack.pinnedFile.requireAtPath(rollbackFile)
    requireClosedSingleFile(rollbackFile, "Knowledge-pack rollback snapshot")
    check(candidateSidecars(rollbackFile).none(::pathEntryExists)) {
        "Knowledge-pack rollback snapshot must not have SQLite sidecars"
    }
    check(readActivationReceipt(receiptFile) == expectedReceipt) {
        "Active knowledge-pack receipt changed during rollback recovery"
    }
    deleteUntrustedDatabaseArtifacts(activeFile)
    Os.rename(rollbackFile.absolutePath, activeFile.absolutePath)
    rollbackPack.pinnedFile.requireAtPath(activeFile)
    requireReceiptMatchesManifest(expectedReceipt, rollbackPack.manifest)
}

private fun deleteUntrustedDatabaseArtifacts(databaseFile: File) {
    deleteTrustedRoomLockCompanionIfPresent(databaseFile)
    deleteUntrustedInternalPathIfPresent(databaseFile)
    candidateSidecars(databaseFile).forEach(::deleteUntrustedInternalPathIfPresent)
}

private fun deleteUntrustedInternalPathIfPresent(file: File) {
    if (!pathEntryExists(file)) return
    val stat = Os.lstat(file.absolutePath)
    check(stat.st_uid == Process.myUid() && !OsConstants.S_ISDIR(stat.st_mode)) {
        "Untrusted knowledge-pack artifact '${file.name}' is not removable"
    }
    Os.remove(file.absolutePath)
    check(!pathEntryExists(file)) {
        "Unable to remove untrusted knowledge-pack artifact '${file.name}'"
    }
}

private data class PersistedKnowledgeCatalogActivationReceipt(
    val generation: Long,
    val activatedAtEpochMillis: Long,
    val packId: String,
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val searchIndexVersion: String?,
    val manifestFingerprint: String,
    val registryAuthorizationIdentity: String?,
) {
    init {
        require(generation > 0L) { "Knowledge-pack activation generation must be positive" }
        require(activatedAtEpochMillis >= 0L) {
            "Knowledge-pack activation time must not be negative"
        }
        packId.requireActivationId("Activated pack id")
        knowledgePackVersion.requireActivationId("Activated pack version")
        taxonomyVersion.requireActivationId("Activated taxonomy version")
        check((searchIndexVersion == null) == (registryAuthorizationIdentity == null)) {
            "Activation registry identity and search-index version must be persisted together"
        }
        searchIndexVersion?.requireActivationId("Activated search-index version")
        require(manifestFingerprint.isSha256Fingerprint()) {
            "Activated knowledge-pack fingerprint must be lowercase SHA-256"
        }
        registryAuthorizationIdentity?.let { identity ->
            require(identity.isSha256Fingerprint()) {
                "Activation registry identity must be lowercase SHA-256"
            }
        }
    }
}

private fun PersistedKnowledgeCatalogActivationReceipt.toPublicReceipt():
    KnowledgeCatalogActivationReceipt =
    KnowledgeCatalogActivationReceipt.create(
        generation = generation,
        activatedAtEpochMillis = activatedAtEpochMillis,
        packId = packId,
        knowledgePackVersion = knowledgePackVersion,
        taxonomyVersion = taxonomyVersion,
        manifestFingerprint = manifestFingerprint,
    )

private data class RegularFileIdentity(
    val device: Long,
    val inode: Long,
    val linkCount: Long,
    val size: Long,
    val modifiedAtSeconds: Long,
    val modifiedAtNanoseconds: Long,
) {
    companion object {
        fun from(stat: StructStat): RegularFileIdentity =
            RegularFileIdentity(
                device = stat.st_dev,
                inode = stat.st_ino,
                linkCount = stat.st_nlink,
                size = stat.st_size,
                modifiedAtSeconds = stat.st_mtim.tv_sec,
                modifiedAtNanoseconds = stat.st_mtim.tv_nsec,
            )
    }
}

private class StablePinnedReadPath(
    private val descriptor: ParcelFileDescriptor,
) : Closeable {
    val file: File = File("/proc/self/fd/${descriptor.fd}")

    override fun close() = descriptor.close()
}

/**
 * Pins one exact regular-file inode across copying, validation, recovery, and rename boundaries.
 * Validation opens are routed through a duplicated descriptor path, so a replacement pathname
 * cannot inherit validation or a trusted receipt.
 */
private class PinnedRegularFile private constructor(
    private val file: File,
    private val label: String,
    private val descriptor: FileDescriptor,
    private val identity: RegularFileIdentity,
) : Closeable {
    fun requireAtPath(expectedPath: File) {
        val pathIdentity =
            RegularFileIdentity.from(requireOwnedRegularFile(expectedPath, label))
        val descriptorIdentity =
            RegularFileIdentity.from(
                requireOwnedRegularStat(Os.fstat(descriptor), label),
            )
        check(pathIdentity == identity && descriptorIdentity == identity) {
            "$label changed after validation"
        }
    }

    fun readBytes(): ByteArray {
        check(identity.size <= Int.MAX_VALUE.toLong()) { "$label is too large to read" }
        val bytes = ByteArray(identity.size.toInt())
        Os.lseek(descriptor, 0L, OsConstants.SEEK_SET)
        var offset = 0
        while (offset < bytes.size) {
            val count = Os.read(descriptor, bytes, offset, bytes.size - offset)
            check(count > 0) { "$label ended before its declared size" }
            offset += count
        }
        requireAtPath(file)
        return bytes
    }

    fun openStableReadPath(): StablePinnedReadPath {
        requireAtPath(file)
        val duplicate = ParcelFileDescriptor.dup(descriptor)
        return try {
            val stablePath = StablePinnedReadPath(duplicate)
            val stableIdentity =
                RegularFileIdentity.from(
                    requireOwnedRegularStat(Os.stat(stablePath.file.absolutePath), label),
                )
            check(stableIdentity == identity) {
                "$label file-descriptor path does not identify the pinned inode"
            }
            stablePath
        } catch (failure: Throwable) {
            duplicate.close()
            throw failure
        }
    }

    fun copyToNewFile(
        destination: File,
        destinationLabel: String,
        makeReadOnly: Boolean = false,
    ): PinnedRegularFile {
        requireAtPath(file)
        check(!pathEntryExists(destination)) {
            "$destinationLabel already exists"
        }
        val destinationDescriptor =
            Os.open(
                destination.absolutePath,
                OsConstants.O_WRONLY or
                    OsConstants.O_CREAT or
                    OsConstants.O_EXCL or
                    OsConstants.O_CLOEXEC or
                    OsConstants.O_NOFOLLOW,
                if (makeReadOnly) {
                    OsConstants.S_IRUSR
                } else {
                    OsConstants.S_IRUSR or OsConstants.S_IWUSR
                },
            )
        return try {
            if (makeReadOnly) {
                // The descriptor retains its original write access, while no second descriptor
                // can be opened for mutation during a long snapshot copy.
                Os.fchmod(destinationDescriptor, OsConstants.S_IRUSR)
            }
            Os.lseek(descriptor, 0L, OsConstants.SEEK_SET)
            val buffer = ByteArray(FILE_COPY_BUFFER_BYTES)
            var remaining = identity.size
            while (remaining > 0L) {
                val requested = minOf(remaining, buffer.size.toLong()).toInt()
                val read = Os.read(descriptor, buffer, 0, requested)
                check(read > 0) { "$label ended before its declared size" }
                var written = 0
                while (written < read) {
                    val count =
                        Os.write(
                            destinationDescriptor,
                            buffer,
                            written,
                            read - written,
                        )
                    check(count > 0) { "Unable to write $destinationLabel" }
                    written += count
                }
                remaining -= read
            }
            Os.fsync(destinationDescriptor)
            val destinationStat =
                requireOwnedRegularStat(
                    Os.fstat(destinationDescriptor),
                    destinationLabel,
                )
            check(destinationStat.st_nlink == 1L && destinationStat.st_size == identity.size) {
                "$destinationLabel is incomplete"
            }
            if (makeReadOnly) {
                check((destinationStat.st_mode and WRITE_PERMISSION_BITS) == 0) {
                    "$destinationLabel must be immutable during validation"
                }
            }
            PinnedRegularFile(
                file = destination,
                label = destinationLabel,
                descriptor = destinationDescriptor,
                identity = RegularFileIdentity.from(destinationStat),
            ).also { destinationPin ->
                destinationPin.requireAtPath(destination)
                requireAtPath(file)
            }
        } catch (failure: Throwable) {
            Os.close(destinationDescriptor)
            throw failure
        }
    }

    fun requireImmutableAtPath(expectedPath: File) {
        requireAtPath(expectedPath)
        val stat = requireOwnedRegularFile(expectedPath, label)
        check(stat.st_nlink == 1L && (stat.st_mode and WRITE_PERMISSION_BITS) == 0) {
            "$label is not an immutable single-link snapshot"
        }
    }

    fun unlinkAtPath(expectedPath: File) {
        requireAtPath(expectedPath)
        Os.remove(expectedPath.absolutePath)
        check(!pathEntryExists(expectedPath)) { "Unable to remove $label" }
    }

    override fun close() {
        Os.close(descriptor)
    }

    companion object {
        fun open(
            file: File,
            label: String,
            maximumBytes: Long = MAX_KNOWLEDGE_DATABASE_BYTES,
        ): PinnedRegularFile {
            val descriptor =
                Os.open(
                    file.absolutePath,
                    OsConstants.O_RDONLY or
                        OsConstants.O_CLOEXEC or
                        OsConstants.O_NOFOLLOW,
                    0,
                )
            return try {
                val stat = requireOwnedRegularStat(Os.fstat(descriptor), label)
                check(stat.st_size in 1..maximumBytes) {
                    "$label '${file.name}' is missing or exceeds its byte budget"
                }
                check(stat.st_nlink == 1L) {
                    "$label must not be hard-linked"
                }
                PinnedRegularFile(
                    file = file,
                    label = label,
                    descriptor = descriptor,
                    identity = RegularFileIdentity.from(stat),
                ).also { pinned ->
                    pinned.requireAtPath(file)
                }
            } catch (failure: Throwable) {
                Os.close(descriptor)
                throw failure
            }
        }
    }
}

private fun readManifestReadOnly(
    databaseFile: File,
    runIntegrityCheck: Boolean,
): KnowledgePackManifestEntity =
    withVerifiedReadOnlyDatabase(
        databaseFile = databaseFile,
        runIntegrityCheck = runIntegrityCheck,
    ) { sqlite ->
        readActiveManifest(sqlite)
    }

private fun readValidatedContentReadOnly(
    databaseFile: File,
    authorization: KnowledgePackActivationAuthorization?,
    verifiedReadPath: File = databaseFile,
    pinnedFile: PinnedRegularFile? = null,
): KnowledgePackManifestEntity =
    withVerifiedReadOnlyDatabase(
        databaseFile = databaseFile,
        verifiedReadPath = verifiedReadPath,
        pinnedFile = pinnedFile,
        runIntegrityCheck = true,
    ) { sqlite ->
        val manifest = readActiveManifest(sqlite)
        requireDeclaredBudgets(manifest)
        requireManifestAuthorization(manifest, authorization)
        verifyPersistedPackContent(sqlite, manifest)
        manifest
    }

private fun requireManifestAuthorization(
    manifest: KnowledgePackManifestEntity,
    authorization: KnowledgePackActivationAuthorization?,
) {
    authorization ?: return
    require(manifest.packId == authorization.packId) {
        "Candidate knowledge-pack id is not authorized"
    }
    require(manifest.knowledgePackVersion == authorization.knowledgePackVersion) {
        "Candidate knowledge-pack version is not authorized"
    }
    require(manifest.taxonomyVersion == authorization.taxonomyVersion) {
        "Candidate knowledge-pack taxonomy is not authorized"
    }
    require(manifest.contentFingerprint == authorization.expectedContentFingerprint) {
        "Candidate knowledge-pack fingerprint is not authorized"
    }
}

private inline fun <T> withVerifiedReadOnlyDatabase(
    databaseFile: File,
    verifiedReadPath: File = databaseFile,
    pinnedFile: PinnedRegularFile? = null,
    runIntegrityCheck: Boolean,
    read: (SQLiteDatabase) -> T,
): T {
    requireClosedSingleFile(databaseFile, "High-school knowledge database")
    pinnedFile?.requireAtPath(databaseFile)
    val database =
        SQLiteDatabase.openDatabase(
            verifiedReadPath.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
    return database.use { sqlite ->
        validateReadOnlyConnection(sqlite, runIntegrityCheck)
        read(sqlite).also {
            pinnedFile?.requireAtPath(databaseFile)
        }
    }
}

private fun validateReadOnlyConnection(
    sqlite: SQLiteDatabase,
    runIntegrityCheck: Boolean,
) {
    check(sqlite.isReadOnly) { "Knowledge-pack verification requires OPEN_READONLY" }
    sqlite.rawQuery("PRAGMA user_version", null).use { cursor ->
        check(cursor.moveToFirst() && cursor.getInt(0) == HIGH_SCHOOL_KNOWLEDGE_DATABASE_VERSION) {
            "Knowledge-pack schema version is unsupported; runtime migration is forbidden"
        }
    }
    sqlite.rawQuery("PRAGMA encoding", null).use { cursor ->
        check(cursor.moveToFirst() && cursor.getString(0).equals("UTF-8", ignoreCase = true)) {
            "Knowledge-pack text encoding is not canonical UTF-8"
        }
    }
    if (runIntegrityCheck) {
        sqlite.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                "Knowledge-pack SQLite integrity check failed"
            }
        }
        sqlite.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            check(!cursor.moveToFirst()) {
                "Knowledge-pack foreign-key integrity check failed"
            }
        }
    }
    validateSchemaObjects(sqlite)
}

private fun readActiveManifest(sqlite: SQLiteDatabase): KnowledgePackManifestEntity =
    sqlite.query(
        "knowledge_pack_manifest",
        MANIFEST_COLUMNS,
        "manifest_key = ?",
        arrayOf(ACTIVE_MANIFEST_KEY),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        check(cursor.moveToFirst()) { "Knowledge pack has no active manifest" }
        KnowledgePackManifestEntity(
            manifestKey = cursor.getString(0),
            packId = cursor.getString(1),
            schemaVersion = cursor.getInt(2),
            knowledgePackVersion = cursor.getString(3),
            taxonomyVersion = cursor.getString(4),
            searchIndexVersion = cursor.getString(5),
            contentFingerprint = cursor.getString(6),
            builtAtEpochMillis = cursor.getLong(7),
            nodeCount = cursor.getInt(8),
            sourceCount = cursor.getInt(9),
            relationCount = cursor.getInt(10),
            materialCount = cursor.getInt(11),
            searchFeatureCount = cursor.getInt(12),
        ).also { manifest ->
            manifest.toCatalogManifest()
            check(manifest.schemaVersion == HIGH_SCHOOL_KNOWLEDGE_DATABASE_VERSION) {
                "Knowledge-pack manifest schema is unsupported"
            }
        }
    }

private fun verifyPersistedPackContent(
    sqlite: SQLiteDatabase,
    manifest: KnowledgePackManifestEntity,
) {
    val counts = readPersistedPackCounts(sqlite, manifest)
    val fingerprint = KnowledgePackContentFingerprintStream(manifest)

    fingerprint.beginNodes(counts.nodes)
    sqlite.streamCanonicalRows(
        table = "knowledge_node",
        columns = NODE_COLUMNS,
        orderBy = "knowledge_node_id COLLATE BINARY",
        expectedCount = counts.nodes,
        read = Cursor::readKnowledgeNode,
        consume = fingerprint::addNode,
    )

    fingerprint.beginSources(counts.sources)
    sqlite.streamCanonicalRows(
        table = "knowledge_source",
        columns = SOURCE_COLUMNS,
        orderBy = "source_id COLLATE BINARY",
        expectedCount = counts.sources,
        read = Cursor::readKnowledgeSource,
        consume = fingerprint::addSource,
    )

    fingerprint.beginNodeSourceBindings(counts.nodeSourceBindings)
    sqlite.streamCanonicalRows(
        table = "knowledge_node_source_binding",
        columns = NODE_SOURCE_BINDING_COLUMNS,
        orderBy =
            "knowledge_node_id COLLATE BINARY, " +
                "source_id COLLATE BINARY, source_locator COLLATE BINARY",
        expectedCount = counts.nodeSourceBindings,
        read = Cursor::readNodeSourceBinding,
        consume = fingerprint::addNodeSourceBinding,
    )

    fingerprint.beginRelations(counts.relations)
    sqlite.streamCanonicalRows(
        table = "knowledge_node_relation",
        columns = RELATION_COLUMNS,
        orderBy = "relation_id COLLATE BINARY",
        expectedCount = counts.relations,
        read = Cursor::readKnowledgeRelation,
        consume = fingerprint::addRelation,
    )

    fingerprint.beginSearchFeatures(counts.searchFeatures)
    sqlite.streamCanonicalRows(
        table = "knowledge_search_feature",
        columns = SEARCH_FEATURE_COLUMNS,
        orderBy =
            "subject COLLATE BINARY, search_feature COLLATE BINARY, " +
                "knowledge_node_id COLLATE BINARY",
        expectedCount = counts.searchFeatures,
        read = Cursor::readSearchFeature,
        consume = fingerprint::addSearchFeature,
    )

    fingerprint.beginMaterials(counts.materials)
    sqlite.streamCanonicalRows(
        table = "knowledge_teaching_material",
        columns = MATERIAL_COLUMNS,
        orderBy = "material_id COLLATE BINARY",
        expectedCount = counts.materials,
        read = Cursor::readTeachingMaterial,
        consume = fingerprint::addMaterial,
    )

    fingerprint.beginMaterialBindings(counts.materialBindings)
    sqlite.streamCanonicalRows(
        table = "knowledge_teaching_material_node_binding",
        columns = MATERIAL_BINDING_COLUMNS,
        orderBy = "material_id COLLATE BINARY, knowledge_node_id COLLATE BINARY",
        expectedCount = counts.materialBindings,
        read = Cursor::readMaterialBinding,
        consume = fingerprint::addMaterialBinding,
    )

    require(fingerprint.finish() == manifest.contentFingerprint) {
        "Knowledge-pack content fingerprint does not match its persisted canonical content"
    }
}

private data class PersistedKnowledgePackCounts(
    val nodes: Int,
    val sources: Int,
    val nodeSourceBindings: Int,
    val relations: Int,
    val searchFeatures: Int,
    val materials: Int,
    val materialBindings: Int,
)

private fun readPersistedPackCounts(
    sqlite: SQLiteDatabase,
    manifest: KnowledgePackManifestEntity,
): PersistedKnowledgePackCounts {
    readBoundedTableCount(sqlite, "knowledge_pack_manifest", maximum = 1, expected = 1)
    return PersistedKnowledgePackCounts(
        // Manifest-declared tables are counted while their ordered cursor is hashed. This avoids
        // a redundant million-row COUNT scan while still rejecting a missing or extra row.
        nodes = manifest.nodeCount,
        sources = manifest.sourceCount,
        nodeSourceBindings =
            readBoundedTableCount(
                sqlite,
                "knowledge_node_source_binding",
                KnowledgePackBudgets.MAX_NODE_SOURCE_BINDINGS,
            ),
        relations = manifest.relationCount,
        searchFeatures = manifest.searchFeatureCount,
        materials = manifest.materialCount,
        materialBindings =
            readBoundedTableCount(
                sqlite,
                "knowledge_teaching_material_node_binding",
                KnowledgePackBudgets.MAX_MATERIAL_BINDINGS,
            ),
    )
}

private fun readBoundedTableCount(
    sqlite: SQLiteDatabase,
    table: String,
    maximum: Int,
    expected: Int? = null,
): Int {
    val count =
        DatabaseUtils.longForQuery(
            sqlite,
            "SELECT COUNT(*) FROM ${table.sqliteIdentifier()}",
            null,
        )
    require(count in 0..maximum.toLong()) {
        "Knowledge pack exceeds the $table row budget"
    }
    expected?.let { expectedCount ->
        require(count == expectedCount.toLong()) {
            "Knowledge-pack $table row count is inconsistent with its manifest"
        }
    }
    return count.toInt()
}

private inline fun <T> SQLiteDatabase.streamCanonicalRows(
    table: String,
    columns: Array<String>,
    orderBy: String,
    expectedCount: Int,
    read: (Cursor) -> T,
    consume: (T) -> Unit,
) {
    var observedCount = 0
    query(table, columns, null, null, null, null, orderBy).use { cursor ->
        while (cursor.moveToNext()) {
            check(observedCount < expectedCount) {
                "Knowledge-pack $table changed while its canonical content was read"
            }
            consume(read(cursor))
            observedCount += 1
        }
    }
    check(observedCount == expectedCount) {
        "Knowledge-pack $table changed while its canonical content was read"
    }
}

private fun Cursor.readKnowledgeNode(): KnowledgeNodeEntity =
    KnowledgeNodeEntity(
        knowledgeNodeId = getString(0),
        stableCode = getString(1),
        subject = getString(2),
        displayName = getString(3),
        canonicalName = getString(4),
        nodeKind = getString(5),
        granularity = getString(6),
        aliasesText = getString(7),
        boundaryMarkdown = nullableString(8),
        verificationStatus = getString(9),
        parentKnowledgeNodeId = nullableString(10),
        taxonomyVersion = getString(11),
        reviewedAtEpochMillis = getLong(12),
    )

private fun Cursor.readKnowledgeSource(): KnowledgeSourceEntity =
    KnowledgeSourceEntity(
        sourceId = getString(0),
        subject = getString(1),
        sourceType = getString(2),
        title = getString(3),
        publisher = nullableString(4),
        edition = nullableString(5),
        sourceUri = nullableString(6),
        licenseStatus = getString(7),
        contentUsePolicy = getString(8),
        contentFingerprint = getString(9),
        licenseExpression = nullableString(10),
        licenseUri = nullableString(11),
        attributionText = nullableString(12),
        reviewedAtEpochMillis = getLong(13),
    )

private fun Cursor.readNodeSourceBinding(): KnowledgeNodeSourceBindingEntity =
    KnowledgeNodeSourceBindingEntity(
        knowledgeNodeId = getString(0),
        sourceId = getString(1),
        sourceLocator = getString(2),
        derivationNote = getString(3),
        reviewedAtEpochMillis = getLong(4),
    )

private fun Cursor.readKnowledgeRelation(): KnowledgeNodeRelationEntity =
    KnowledgeNodeRelationEntity(
        relationId = getString(0),
        subject = getString(1),
        fromKnowledgeNodeId = getString(2),
        toKnowledgeNodeId = getString(3),
        relationType = getString(4),
        taxonomyVersion = getString(5),
        sourceId = getString(6),
        sourceLocator = getString(7),
        reviewedAtEpochMillis = getLong(8),
    )

private fun Cursor.readSearchFeature(): KnowledgeSearchFeatureEntity =
    KnowledgeSearchFeatureEntity(
        subject = getString(0),
        searchFeature = getString(1),
        knowledgeNodeId = getString(2),
        featureKind = getString(3),
        rankWeight = getInt(4),
    )

private fun Cursor.readTeachingMaterial(): KnowledgeTeachingMaterialEntity =
    KnowledgeTeachingMaterialEntity(
        materialId = getString(0),
        stableCode = getString(1),
        subject = getString(2),
        materialType = getString(3),
        title = getString(4),
        summaryMarkdown = getString(5),
        applicabilityMarkdown = getString(6),
        contentMarkdown = getString(7),
        boundaryMarkdown = getString(8),
        derivationKind = getString(9),
        sourceId = getString(10),
        sourceLocator = getString(11),
        contentFingerprint = getString(12),
        reviewedAtEpochMillis = getLong(13),
    )

private fun Cursor.readMaterialBinding(): KnowledgeTeachingMaterialNodeBindingEntity =
    KnowledgeTeachingMaterialNodeBindingEntity(
        materialId = getString(0),
        knowledgeNodeId = getString(1),
        role = getString(2),
    )

private fun Cursor.nullableString(columnIndex: Int): String? =
    if (isNull(columnIndex)) null else getString(columnIndex)

private fun validateSchemaObjects(database: SQLiteDatabase) {
    val actualTables = mutableSetOf<String>()
    database.rawQuery(
        """
        SELECT type, name
        FROM sqlite_master
        WHERE type IN ('table', 'view', 'trigger')
          AND name NOT GLOB 'sqlite_*'
        ORDER BY type, name
        """.trimIndent(),
        null,
    ).use { cursor ->
        while (cursor.moveToNext()) {
            val type = cursor.getString(0)
            val name = cursor.getString(1)
            check(type == "table") {
                "Knowledge pack contains an unexpected $type '$name'"
            }
            actualTables += name
        }
    }
    check(
        actualTables.containsAll(REQUIRED_KNOWLEDGE_DATABASE_TABLES) &&
            actualTables.all { it in ALLOWED_KNOWLEDGE_DATABASE_TABLES },
    ) {
        "Knowledge-pack database contains an unexpected schema object set"
    }
    validateCanonicalIndexes(database)
}

private fun validateCanonicalIndexes(database: SQLiteDatabase) {
    CANONICAL_INDEXES_BY_TABLE.forEach { (tableName, expectedIndexes) ->
        val actualIndexes = linkedMapOf<String, CanonicalIndexDefinition>()
        val primaryKeyIndexes = mutableListOf<List<String>>()
        database.rawQuery("PRAGMA index_list(${tableName.sqliteIdentifier()})", null).use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            val uniqueColumn = cursor.getColumnIndexOrThrow("unique")
            val originColumn = cursor.getColumnIndexOrThrow("origin")
            val partialColumn = cursor.getColumnIndexOrThrow("partial")
            while (cursor.moveToNext()) {
                val indexName = cursor.getString(nameColumn)
                val origin = cursor.getString(originColumn)
                check(cursor.getInt(partialColumn) == 0) {
                    "Knowledge-pack index '$indexName' is not a canonical full index"
                }
                val definition =
                    CanonicalIndexDefinition(
                        name = indexName,
                        unique = cursor.getInt(uniqueColumn) == 1,
                        columns = readCanonicalIndexColumns(database, indexName),
                    )
                when (origin) {
                    "c" -> {
                        check(!indexName.startsWith("sqlite_autoindex_")) {
                            "Knowledge-pack explicit index '$indexName' has an invalid identity"
                        }
                        check(actualIndexes.put(indexName, definition) == null) {
                            "Knowledge-pack index '$indexName' is duplicated"
                        }
                    }

                    "pk" -> {
                        check(
                            indexName.startsWith("sqlite_autoindex_") && definition.unique,
                        ) {
                            "Knowledge-pack primary-key index '$indexName' is not canonical"
                        }
                        primaryKeyIndexes += definition.columns
                    }

                    else ->
                        error(
                            "Knowledge-pack index '$indexName' has unsupported origin '$origin'",
                        )
                }
            }
        }
        check(actualIndexes == expectedIndexes.associateBy(CanonicalIndexDefinition::name)) {
            "Knowledge-pack indexes for '$tableName' do not match the canonical schema"
        }
        val expectedPrimaryKey = PRIMARY_KEY_COLUMNS_BY_TABLE.getValue(tableName)
        check(
            if (expectedPrimaryKey.isEmpty()) {
                primaryKeyIndexes.isEmpty()
            } else {
                primaryKeyIndexes == listOf(expectedPrimaryKey)
            },
        ) {
            "Knowledge-pack primary key for '$tableName' is not canonical"
        }
    }
}

private fun readCanonicalIndexColumns(
    database: SQLiteDatabase,
    indexName: String,
): List<String> {
    val columns = mutableListOf<Pair<Int, String>>()
    database.rawQuery("PRAGMA index_xinfo(${indexName.sqliteIdentifier()})", null).use { cursor ->
        val sequenceColumn = cursor.getColumnIndexOrThrow("seqno")
        val nameColumn = cursor.getColumnIndexOrThrow("name")
        val descendingColumn = cursor.getColumnIndexOrThrow("desc")
        val collationColumn = cursor.getColumnIndexOrThrow("coll")
        val keyColumn = cursor.getColumnIndexOrThrow("key")
        while (cursor.moveToNext()) {
            if (cursor.getInt(keyColumn) != 1) continue
            check(
                cursor.getInt(descendingColumn) == 0 &&
                    cursor.getString(collationColumn) == "BINARY",
            ) {
                "Knowledge-pack index '$indexName' uses a non-canonical ordering or collation"
            }
            val columnName =
                checkNotNull(cursor.getString(nameColumn)) {
                    "Knowledge-pack index '$indexName' cannot use expressions"
                }
            columns += cursor.getInt(sequenceColumn) to columnName
        }
    }
    return columns.sortedBy { it.first }.map { it.second }
}

private fun requireDeclaredBudgets(manifest: KnowledgePackManifestEntity) {
    require(manifest.nodeCount <= KnowledgePackBudgets.MAX_NODES)
    require(manifest.sourceCount <= KnowledgePackBudgets.MAX_SOURCES)
    require(manifest.relationCount <= KnowledgePackBudgets.MAX_RELATIONS)
    require(manifest.searchFeatureCount <= KnowledgePackBudgets.MAX_SEARCH_FEATURES)
    require(manifest.materialCount <= KnowledgePackBudgets.MAX_MATERIALS)
}

private fun requireClosedSingleFile(
    databaseFile: File,
    label: String,
) {
    val stat = requireOwnedRegularFile(databaseFile, label)
    check(stat.st_size in 1..MAX_KNOWLEDGE_DATABASE_BYTES) {
        "$label '${databaseFile.name}' is missing"
    }
    check(candidateSidecars(databaseFile).none(::pathEntryExists)) {
        "$label must be closed and checkpointed before use"
    }
}

private fun requirePromotionReady(
    validationPin: PinnedRegularFile,
    validationFile: File,
    activeFile: File,
) {
    val validationDirectory = requireNotNull(validationFile.absoluteFile.parentFile)
    val activeDirectory = requireNotNull(activeFile.absoluteFile.parentFile)
    check(validationDirectory == activeDirectory) {
        "Knowledge-pack promotion must remain inside the database directory"
    }
    val directoryStat = Os.lstat(activeDirectory.absolutePath)
    check(OsConstants.S_ISDIR(directoryStat.st_mode) && directoryStat.st_uid == Process.myUid()) {
        "Knowledge-pack database directory is not app-owned"
    }
    validationPin.requireImmutableAtPath(validationFile)
    requireClosedSingleFile(validationFile, "Knowledge-pack validation snapshot")
    check(
        candidateSidecars(validationFile).none(::pathEntryExists) &&
            candidateSidecars(activeFile).none(::pathEntryExists) &&
            !pathEntryExists(roomLockCompanionFile(validationFile)),
    ) {
        "Knowledge-pack promotion paths must not have SQLite sidecars or a validation lock"
    }
}

private fun requireOwnedRegularFile(
    file: File,
    label: String,
): StructStat =
    try {
        requireOwnedRegularStat(Os.lstat(file.absolutePath), label)
    } catch (failure: ErrnoException) {
        throw IllegalStateException("$label '${file.name}' is unavailable", failure)
    }

private fun requireOwnedRegularStat(
    stat: StructStat,
    label: String,
): StructStat {
    check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid()) {
        "$label must be an app-owned regular file"
    }
    return stat
}

private fun pathEntryExists(file: File): Boolean =
    try {
        Os.lstat(file.absolutePath)
        true
    } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT) {
            false
        } else {
            throw IllegalStateException(
                "Unable to inspect knowledge-pack path '${file.name}'",
                failure,
            )
        }
    }

private fun candidateSidecars(databaseFile: File): List<File> =
    listOf(
        File(databaseFile.path + "-wal"),
        File(databaseFile.path + "-shm"),
        File(databaseFile.path + "-journal"),
    )

private fun removeEmptyCandidateSidecars(databaseFile: File) {
    candidateSidecars(databaseFile).forEach { sidecar ->
        if (pathEntryExists(sidecar)) {
            val stat = requireOwnedRegularFile(sidecar, "Candidate knowledge-pack sidecar")
            check(stat.st_size == 0L) {
                "Candidate knowledge pack has uncheckpointed SQLite state"
            }
            check(sidecar.delete()) {
                "Unable to remove an empty candidate SQLite sidecar"
            }
        }
    }
}

private fun deleteRecoveryFileIfPresent(file: File) {
    if (!pathEntryExists(file)) return
    requireOwnedRegularFile(file, "Knowledge-pack recovery artifact")
    check(file.delete()) {
        "Unable to delete knowledge-pack recovery artifact '${file.name}'"
    }
}

private fun requireReceiptMatchesManifest(
    receipt: PersistedKnowledgeCatalogActivationReceipt,
    manifest: KnowledgePackManifestEntity,
) {
    check(receiptMatchesManifest(receipt, manifest)) {
        "Knowledge-pack activation receipt does not match the active database"
    }
}

private fun requirePendingReceiptRegistryAuthorization(
    receipt: PersistedKnowledgeCatalogActivationReceipt,
    manifest: KnowledgePackManifestEntity,
) {
    val searchIndexVersion = receipt.searchIndexVersion
        ?: throw SecurityException("Legacy pending knowledge-pack receipts cannot be recovered")
    val authorizationIdentity = receipt.registryAuthorizationIdentity
        ?: throw SecurityException("Pending knowledge-pack registry identity is missing")
    check(receiptMatchesManifest(receipt, manifest)) {
        "Pending knowledge-pack receipt does not match the promoted database"
    }
    requireRegisteredKnowledgePackRecoveryActivation(
        packId = receipt.packId,
        knowledgePackVersion = receipt.knowledgePackVersion,
        taxonomyVersion = receipt.taxonomyVersion,
        searchIndexVersion = searchIndexVersion,
        generation = receipt.generation,
        contentFingerprint = receipt.manifestFingerprint,
        authorizationIdentity = authorizationIdentity,
    )
}

private fun receiptMatchesManifest(
    receipt: PersistedKnowledgeCatalogActivationReceipt,
    manifest: KnowledgePackManifestEntity,
): Boolean =
    receipt.packId == manifest.packId &&
        receipt.knowledgePackVersion == manifest.knowledgePackVersion &&
    receipt.taxonomyVersion == manifest.taxonomyVersion &&
        (receipt.searchIndexVersion == null ||
            receipt.searchIndexVersion == manifest.searchIndexVersion) &&
        receipt.manifestFingerprint == manifest.contentFingerprint

private fun activationReceiptFile(context: Context): File =
    File(
        requireNotNull(
            context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).parentFile,
        ),
        "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation",
    )

private fun pendingActivationReceiptFile(context: Context): File =
    File(
        requireNotNull(
            context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).parentFile,
        ),
        "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.pending",
    )

private fun rollbackDatabaseFile(context: Context): File =
    File(
        requireNotNull(
            context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).parentFile,
        ),
        "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.rollback",
    )

private fun validationSnapshotFile(context: Context): File =
    File(
        requireNotNull(
            context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).parentFile,
        ),
        "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.validation",
    )

private fun writeAndSyncReceipt(
    file: File,
    receipt: PersistedKnowledgeCatalogActivationReceipt,
) {
    val parent = requireNotNull(file.parentFile) {
        "Knowledge-pack activation receipt has no parent directory"
    }
    check(parent.isDirectory || parent.mkdirs()) {
        "Unable to create knowledge-pack activation directory"
    }
    check(!pathEntryExists(file)) {
        "Knowledge-pack activation receipt already exists"
    }
    val payload =
        listOf(
            ACTIVATION_RECEIPT_FORMAT,
            receipt.generation.toString(),
            receipt.activatedAtEpochMillis.toString(),
            receipt.packId,
            receipt.knowledgePackVersion,
            receipt.taxonomyVersion,
            checkNotNull(receipt.searchIndexVersion),
            receipt.manifestFingerprint,
            checkNotNull(receipt.registryAuthorizationIdentity),
        ).joinToString(separator = "\n", postfix = "\n")
            .toByteArray(StandardCharsets.UTF_8)
    val descriptor =
        Os.open(
            file.absolutePath,
            OsConstants.O_WRONLY or
                OsConstants.O_CREAT or
                OsConstants.O_EXCL or
                OsConstants.O_CLOEXEC or
                OsConstants.O_NOFOLLOW,
            OsConstants.S_IRUSR or OsConstants.S_IWUSR,
        )
    try {
        var offset = 0
        while (offset < payload.size) {
            val written = Os.write(descriptor, payload, offset, payload.size - offset)
            check(written > 0) { "Unable to write knowledge-pack activation receipt" }
            offset += written
        }
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}

private fun readActivationReceipt(file: File): PersistedKnowledgeCatalogActivationReceipt =
    PinnedRegularFile
        .open(
            file = file,
            label = "Knowledge-pack activation receipt",
            maximumBytes = MAX_ACTIVATION_RECEIPT_BYTES,
        ).use(::readActivationReceipt)

private fun readActivationReceipt(
    pinnedReceipt: PinnedRegularFile,
): PersistedKnowledgeCatalogActivationReceipt {
    val payload = pinnedReceipt.readBytes()
    val serialized = payload.toString(StandardCharsets.UTF_8)
    val splitLines = serialized.split('\n')
    val lines =
        if (splitLines.lastOrNull().isNullOrEmpty()) {
            splitLines.dropLast(1)
        } else {
            splitLines
        }
    val isLegacy = lines.size == 7 && lines[0] == LEGACY_ACTIVATION_RECEIPT_FORMAT
    val isCurrent = lines.size == 9 && lines[0] == ACTIVATION_RECEIPT_FORMAT
    check(isLegacy || isCurrent) {
        "Knowledge-pack activation receipt format is invalid"
    }
    val generation =
        lines[1].toLongOrNull()
            ?: error("Knowledge-pack activation generation is invalid")
    val activatedAtEpochMillis =
        lines[2].toLongOrNull()
            ?: error("Knowledge-pack activation time is invalid")
    return PersistedKnowledgeCatalogActivationReceipt(
        generation = generation,
        activatedAtEpochMillis = activatedAtEpochMillis,
        packId = lines[3],
        knowledgePackVersion = lines[4],
        taxonomyVersion = lines[5],
        searchIndexVersion = if (isCurrent) lines[6] else null,
        manifestFingerprint = if (isCurrent) lines[7] else lines[6],
        registryAuthorizationIdentity = if (isCurrent) lines[8] else null,
    )
}

private fun syncDirectory(directory: File) {
    val descriptor =
        Os.open(
            directory.absolutePath,
            OsConstants.O_RDONLY,
            0,
        )
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}

private fun String.requireActivationId(label: String) {
    require(isNotBlank() && length <= 160 && none(Char::isISOControl)) {
        "$label is invalid"
    }
}

private fun String.isSha256Fingerprint(): Boolean = matches(Regex("[0-9a-f]{64}"))

private val MANIFEST_COLUMNS =
    arrayOf(
        "manifest_key",
        "pack_id",
        "schema_version",
        "knowledge_pack_version",
        "taxonomy_version",
        "search_index_version",
        "content_fingerprint",
        "built_at_epoch_millis",
        "node_count",
        "source_count",
        "relation_count",
        "material_count",
        "search_feature_count",
    )

private val NODE_COLUMNS =
    arrayOf(
        "knowledge_node_id",
        "stable_code",
        "subject",
        "display_name",
        "canonical_name",
        "node_kind",
        "granularity",
        "aliases_text",
        "boundary_markdown",
        "verification_status",
        "parent_knowledge_node_id",
        "taxonomy_version",
        "reviewed_at_epoch_millis",
    )

private val SOURCE_COLUMNS =
    arrayOf(
        "source_id",
        "subject",
        "source_type",
        "title",
        "publisher",
        "edition",
        "source_uri",
        "license_status",
        "content_use_policy",
        "content_fingerprint",
        "license_expression",
        "license_uri",
        "attribution_text",
        "reviewed_at_epoch_millis",
    )

private val NODE_SOURCE_BINDING_COLUMNS =
    arrayOf(
        "knowledge_node_id",
        "source_id",
        "source_locator",
        "derivation_note",
        "reviewed_at_epoch_millis",
    )

private val RELATION_COLUMNS =
    arrayOf(
        "relation_id",
        "subject",
        "from_knowledge_node_id",
        "to_knowledge_node_id",
        "relation_type",
        "taxonomy_version",
        "source_id",
        "source_locator",
        "reviewed_at_epoch_millis",
    )

private val SEARCH_FEATURE_COLUMNS =
    arrayOf(
        "subject",
        "search_feature",
        "knowledge_node_id",
        "feature_kind",
        "rank_weight",
    )

private val MATERIAL_COLUMNS =
    arrayOf(
        "material_id",
        "stable_code",
        "subject",
        "material_type",
        "title",
        "summary_markdown",
        "applicability_markdown",
        "content_markdown",
        "boundary_markdown",
        "derivation_kind",
        "source_id",
        "source_locator",
        "content_fingerprint",
        "reviewed_at_epoch_millis",
    )

private val MATERIAL_BINDING_COLUMNS =
    arrayOf(
        "material_id",
        "knowledge_node_id",
        "role",
    )

private data class CanonicalIndexDefinition(
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
)

private fun canonicalIndex(
    name: String,
    unique: Boolean = false,
    vararg columns: String,
): CanonicalIndexDefinition =
    CanonicalIndexDefinition(
        name = name,
        unique = unique,
        columns = columns.toList(),
    )

private val CANONICAL_INDEXES_BY_TABLE: Map<String, List<CanonicalIndexDefinition>> =
    mapOf(
        "room_master_table" to emptyList(),
        "knowledge_pack_manifest" to emptyList(),
        "knowledge_node" to
            listOf(
                canonicalIndex(
                    "index_knowledge_node_stable_code_taxonomy_version",
                    true,
                    "stable_code",
                    "taxonomy_version",
                ),
                canonicalIndex(
                    "index_knowledge_node_subject_canonical_name",
                    false,
                    "subject",
                    "canonical_name",
                ),
                canonicalIndex(
                    "index_knowledge_node_subject_display_name",
                    false,
                    "subject",
                    "display_name",
                ),
                canonicalIndex(
                    "index_knowledge_node_subject_granularity",
                    false,
                    "subject",
                    "granularity",
                ),
                canonicalIndex(
                    "index_knowledge_node_parent_knowledge_node_id",
                    false,
                    "parent_knowledge_node_id",
                ),
            ),
        "knowledge_source" to
            listOf(
                canonicalIndex(
                    "index_knowledge_source_content_fingerprint",
                    true,
                    "content_fingerprint",
                ),
                canonicalIndex(
                    "index_knowledge_source_subject_source_type",
                    false,
                    "subject",
                    "source_type",
                ),
            ),
        "knowledge_node_source_binding" to
            listOf(
                canonicalIndex(
                    "index_knowledge_node_source_binding_knowledge_node_id",
                    false,
                    "knowledge_node_id",
                ),
                canonicalIndex(
                    "index_knowledge_node_source_binding_source_id",
                    false,
                    "source_id",
                ),
            ),
        "knowledge_node_relation" to
            listOf(
                canonicalIndex(
                    "index_knowledge_node_relation_" +
                        "from_knowledge_node_id_to_knowledge_node_id_relation_type_taxonomy_version",
                    true,
                    "from_knowledge_node_id",
                    "to_knowledge_node_id",
                    "relation_type",
                    "taxonomy_version",
                ),
                canonicalIndex(
                    "index_knowledge_node_relation_subject_from_knowledge_node_id_relation_type",
                    false,
                    "subject",
                    "from_knowledge_node_id",
                    "relation_type",
                ),
                canonicalIndex(
                    "index_knowledge_node_relation_subject_to_knowledge_node_id_relation_type",
                    false,
                    "subject",
                    "to_knowledge_node_id",
                    "relation_type",
                ),
                canonicalIndex(
                    "index_knowledge_node_relation_to_knowledge_node_id",
                    false,
                    "to_knowledge_node_id",
                ),
                canonicalIndex(
                    "index_knowledge_node_relation_source_id",
                    false,
                    "source_id",
                ),
            ),
        "knowledge_search_feature" to
            listOf(
                canonicalIndex(
                    "index_knowledge_search_feature_knowledge_node_id",
                    false,
                    "knowledge_node_id",
                ),
                canonicalIndex(
                    "index_knowledge_search_feature_subject_knowledge_node_id",
                    false,
                    "subject",
                    "knowledge_node_id",
                ),
            ),
        "knowledge_teaching_material" to
            listOf(
                canonicalIndex(
                    "index_knowledge_teaching_material_stable_code",
                    true,
                    "stable_code",
                ),
                canonicalIndex(
                    "index_knowledge_teaching_material_content_fingerprint",
                    true,
                    "content_fingerprint",
                ),
                canonicalIndex(
                    "index_knowledge_teaching_material_source_id",
                    false,
                    "source_id",
                ),
                canonicalIndex(
                    "index_knowledge_teaching_material_subject_material_type",
                    false,
                    "subject",
                    "material_type",
                ),
            ),
        "knowledge_teaching_material_node_binding" to
            listOf(
                canonicalIndex(
                    "index_knowledge_teaching_material_node_binding_material_id_role",
                    false,
                    "material_id",
                    "role",
                ),
                canonicalIndex(
                    "index_knowledge_teaching_material_node_binding_knowledge_node_id_role",
                    false,
                    "knowledge_node_id",
                    "role",
                ),
            ),
    )

private val PRIMARY_KEY_COLUMNS_BY_TABLE: Map<String, List<String>> =
    mapOf(
        "room_master_table" to emptyList(),
        "knowledge_pack_manifest" to listOf("manifest_key"),
        "knowledge_node" to listOf("knowledge_node_id"),
        "knowledge_source" to listOf("source_id"),
        "knowledge_node_source_binding" to
            listOf("knowledge_node_id", "source_id", "source_locator"),
        "knowledge_node_relation" to listOf("relation_id"),
        "knowledge_search_feature" to
            listOf("subject", "search_feature", "knowledge_node_id"),
        "knowledge_teaching_material" to listOf("material_id"),
        "knowledge_teaching_material_node_binding" to
            listOf("material_id", "knowledge_node_id"),
    )

private val REQUIRED_KNOWLEDGE_DATABASE_TABLES =
    setOf(
        "room_master_table",
        "knowledge_pack_manifest",
        "knowledge_node",
        "knowledge_source",
        "knowledge_node_source_binding",
        "knowledge_node_relation",
        "knowledge_search_feature",
        "knowledge_teaching_material",
        "knowledge_teaching_material_node_binding",
    )

private val ALLOWED_KNOWLEDGE_DATABASE_TABLES =
    REQUIRED_KNOWLEDGE_DATABASE_TABLES + "android_metadata"

private const val LEGACY_ACTIVATION_RECEIPT_FORMAT = "high-school-knowledge-activation-v2"
private const val ACTIVATION_RECEIPT_FORMAT = "high-school-knowledge-activation-v3"
private const val MAX_ACTIVATION_RECEIPT_BYTES = 2_048L
private const val MAX_KNOWLEDGE_DATABASE_BYTES = 512L * 1024L * 1024L
private const val FILE_COPY_BUFFER_BYTES = 64 * 1024
private val WRITE_PERMISSION_BITS =
    OsConstants.S_IWUSR or OsConstants.S_IWGRP or OsConstants.S_IWOTH

private fun String.sqliteIdentifier(): String = "\"" + replace("\"", "\"\"") + "\""
