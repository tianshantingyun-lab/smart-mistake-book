package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSourcePort
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogActivationReceipt
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import java.io.Closeable

/**
 * Destination-owned evidence for one terminal cutover stage.
 *
 * Implementations may privately own an authority port, but the coordinator receives only this
 * stable attestation. It never receives a database, DAO, SQL connection, or mutable record.
 */
internal data class TerminalAuthorityStageSnapshot(
    val migratedRecordCount: Long,
    val sourceCheckpoint: String,
    val destinationFingerprint: String,
) {
    init {
        require(migratedRecordCount >= 0L) {
            "Terminal stage record count must not be negative"
        }
        require(
            sourceCheckpoint.isNotBlank() &&
                sourceCheckpoint.length <= MAX_RAW_TERMINAL_CHECKPOINT_LENGTH &&
                sourceCheckpoint.none { character -> character.isISOControl() },
        ) {
            "Terminal stage source checkpoint is invalid"
        }
        require(destinationFingerprint.matches(TERMINAL_SHA_256)) {
            "Terminal stage destination fingerprint must be lowercase SHA-256"
        }
    }
}

internal data class TerminalAuthorityCutoverBinding(
    val cutoverGeneration: Long,
    val legacyPrefixReceiptFingerprint: String,
) {
    init {
        require(cutoverGeneration > 0L) {
            "Terminal cutover generation must be positive"
        }
        require(legacyPrefixReceiptFingerprint.matches(TERMINAL_SHA_256)) {
            "Legacy prefix receipt fingerprint must be lowercase SHA-256"
        }
    }
}

/**
 * A real stage capability must both perform forward repair and freshly attest its result.
 *
 * There is deliberately no default or no-op implementation. Missing capabilities are resolved to
 * [TerminalAuthorityPostImportCapabilityResolution.Blocked] before any journal write is attempted.
 */
internal interface TerminalAuthorityStageOperation {
    val stage: ThreeAuthorityCutoverStage

    suspend fun migrate(
        binding: TerminalAuthorityCutoverBinding,
        predecessor: AuthorityStageReceipt,
    ): TerminalAuthorityStageSnapshot

    suspend fun verify(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot
}

internal sealed interface TerminalAuthorityPostImportCapabilityResolution {
    data class Ready(
        val orderedOperations: List<TerminalAuthorityStageOperation>,
    ) : TerminalAuthorityPostImportCapabilityResolution

    data class Blocked(
        val missingStages: Set<ThreeAuthorityCutoverStage>,
        val duplicateStages: Set<ThreeAuthorityCutoverStage>,
        val unexpectedStages: Set<ThreeAuthorityCutoverStage>,
    ) : TerminalAuthorityPostImportCapabilityResolution
}

/**
 * Validates the three mastery-owned stages that are not implemented yet.
 *
 * Student stages 6-8 are deliberately absent from this injectable capability list. Production
 * always constructs those stages from [StudentTerminalAuthorityAttestationOwner].
 */
internal object TerminalAuthorityPostImportCapabilityResolver {
    fun resolve(
        operations: Collection<TerminalAuthorityStageOperation>,
    ): TerminalAuthorityPostImportCapabilityResolution {
        val grouped = operations.groupBy(TerminalAuthorityStageOperation::stage)
        val present = grouped.keys
        val missing = REQUIRED_POST_IMPORT_STAGES.toSet() - present
        val duplicate =
            grouped
                .filterValues { stageOperations -> stageOperations.size != 1 }
                .keys
        val unexpected = present - REQUIRED_POST_IMPORT_STAGES.toSet()
        if (missing.isNotEmpty() || duplicate.isNotEmpty() || unexpected.isNotEmpty()) {
            return TerminalAuthorityPostImportCapabilityResolution.Blocked(
                missingStages = missing,
                duplicateStages = duplicate,
                unexpectedStages = unexpected,
            )
        }
        return TerminalAuthorityPostImportCapabilityResolution.Ready(
            orderedOperations =
                REQUIRED_POST_IMPORT_STAGES.map { stage ->
                    checkNotNull(grouped[stage]).single()
                },
        )
    }
}

internal sealed interface ProductionTerminalAuthorityCutoverWriterCreation {
    data class Ready(
        val writer: ProductionTerminalAuthorityCutoverWriter,
    ) : ProductionTerminalAuthorityCutoverWriterCreation

    data class Blocked(
        val missingStages: Set<ThreeAuthorityCutoverStage>,
        val duplicateStages: Set<ThreeAuthorityCutoverStage>,
        val unexpectedStages: Set<ThreeAuthorityCutoverStage>,
    ) : ProductionTerminalAuthorityCutoverWriterCreation
}

/**
 * The only production assembly path for the exact twelve-stage legacy journal writer.
 *
 * Stage 5 and stage 9 are always backed by the real lossless import migrators. Student stages 6-8
 * are always built from the student owner; only the still-unimplemented mastery stages 10-12 are
 * resolved from explicit capabilities and remain fail-closed when absent.
 */
internal object ProductionTerminalAuthorityCutoverWriterFactory {
    fun create(
        layout: ThreeAuthorityDatabaseLayout,
        legacySource: LegacyAuthorityMigrationSourcePort,
        knowledgeCatalog: HighSchoolKnowledgeCatalog,
        knowledgeActivation: KnowledgeCatalogActivationReceipt,
        journal: AuthorityCutoverJournal,
        cutoverGeneration: Long,
        studentMigrator: TerminalStudentAuthorityMigrator,
        studentAttestationOwner: StudentTerminalAuthorityAttestationOwner,
        masteryMigrator: TerminalLearnerMasteryAuthorityMigrator,
        postImportOperations: Collection<TerminalAuthorityStageOperation>,
        clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0L) },
    ): ProductionTerminalAuthorityCutoverWriterCreation {
        val postImport =
            when (
                val resolution =
                    TerminalAuthorityPostImportCapabilityResolver.resolve(
                        postImportOperations,
                    )
            ) {
                is TerminalAuthorityPostImportCapabilityResolution.Blocked ->
                    return ProductionTerminalAuthorityCutoverWriterCreation.Blocked(
                        missingStages = resolution.missingStages,
                        duplicateStages = resolution.duplicateStages,
                        unexpectedStages = resolution.unexpectedStages,
                    )

                is TerminalAuthorityPostImportCapabilityResolution.Ready ->
                    resolution.orderedOperations
            }

        val postImportByStage = postImport.associateBy(TerminalAuthorityStageOperation::stage)
        val terminalOperations =
            listOf(
                StudentDocumentsImportedOperation(studentMigrator),
            ) +
                createStudentDestinationAttestationOperations(
                    studentAttestationOwner,
                ) +
                listOf(
                    MasteryFactsImportedOperation(masteryMigrator),
                    checkNotNull(
                        postImportByStage[
                            ThreeAuthorityCutoverStage
                                .MASTERY_BINDINGS_RECONCILED
                        ],
                    ),
                    checkNotNull(
                        postImportByStage[
                            ThreeAuthorityCutoverStage
                                .MASTERY_PROJECTIONS_REBUILT
                        ],
                    ),
                    checkNotNull(
                        postImportByStage[
                            ThreeAuthorityCutoverStage
                                .MASTERY_AUTHORITY_VERIFIED
                        ],
                    ),
                )
        return ProductionTerminalAuthorityCutoverWriterCreation.Ready(
            writer =
                ProductionTerminalAuthorityCutoverWriter(
                    layout = layout,
                    prefixSteps =
                        ProductionAuthorityCutoverPrefixFactory.createSteps(
                            layout = layout,
                            legacySource = legacySource,
                            knowledgeCatalog = knowledgeCatalog,
                            knowledgeActivation = knowledgeActivation,
                            knowledgeCutoverEligible = true,
                            clock = clock,
                        ),
                    terminalOperations = terminalOperations,
                    studentAttestationOwner = studentAttestationOwner,
                    journal = journal,
                    cutoverGeneration = cutoverGeneration,
                    clock = clock,
                ),
        )
    }
}

internal data class TerminalAuthorityCutoverJournalResult(
    val cutoverGeneration: Long,
    val legacyPrefixReceiptFingerprint: String,
    val terminalReceiptFingerprint: String,
    val durableStages: List<ThreeAuthorityCutoverStage>,
) {
    init {
        require(cutoverGeneration > 0L)
        require(legacyPrefixReceiptFingerprint.matches(TERMINAL_SHA_256))
        require(terminalReceiptFingerprint.matches(TERMINAL_SHA_256))
        require(durableStages == ThreeAuthorityCutoverStage.legacyJournalPrefix) {
            "Terminal result requires the exact twelve-stage journal"
        }
    }
}

/**
 * Crash-recoverable writer for the exact terminal journal.
 *
 * This object only completes the migration journal. It cannot create authority fences, activate
 * the v45 legacy write barrier, or publish product repositories. The caller must close its
 * exclusive legacy migration owner before asking the startup gate to perform those operations.
 */
internal class ProductionTerminalAuthorityCutoverWriter(
    private val layout: ThreeAuthorityDatabaseLayout,
    prefixSteps: List<AuthorityCutoverStep>,
    terminalOperations: List<TerminalAuthorityStageOperation>,
    private val studentAttestationOwner: StudentTerminalAuthorityAttestationOwner,
    private val journal: AuthorityCutoverJournal,
    private val cutoverGeneration: Long,
    private val clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0L) },
) : Closeable {
    private val prefixSteps = prefixSteps.toList()
    private val terminalOperations = terminalOperations.toList()

    init {
        require(cutoverGeneration > 0L) {
            "Terminal cutover generation must be positive"
        }
        require(
            this.prefixSteps.map(AuthorityCutoverStep::stage) ==
                ThreeAuthorityCutoverStage.legacyJournalPrefix.take(PREFIX_STAGE_COUNT),
        ) {
            "Terminal writer requires the exact verified four-stage production prefix"
        }
        require(
            this.terminalOperations.map(TerminalAuthorityStageOperation::stage) ==
                ThreeAuthorityCutoverStage.legacyJournalPrefix.drop(PREFIX_STAGE_COUNT),
        ) {
            "Terminal writer requires one real operation for every ordered terminal stage"
        }
    }

    suspend fun migrateAndVerifyTerminalJournal(): TerminalAuthorityCutoverJournalResult {
        val initial = journal.readOrdered()
        requireValidAuthorityCutoverJournalPrefix(layout, initial)
        if (initial.size < PREFIX_STAGE_COUNT) {
            VerifiedAuthorityCutoverPrefixCoordinator(
                layout = layout,
                steps = prefixSteps,
                journal = journal,
            ).migrateVerifiedPrefix()
        }

        val prefixDurable = journal.readOrdered()
        requireValidAuthorityCutoverJournalPrefix(layout, prefixDurable)
        check(prefixDurable.size >= PREFIX_STAGE_COUNT) {
            "Terminal writer could not establish the verified production prefix"
        }
        val legacyPrefixReceipt =
            prefixDurable[PREFIX_STAGE_COUNT - 1].also { receipt ->
                check(
                    receipt.stage ==
                        ThreeAuthorityCutoverStage.KNOWLEDGE_RUNTIME_READ_ONLY,
                ) {
                    "Terminal writer found the wrong legacy prefix boundary"
                }
            }
        val binding =
            TerminalAuthorityCutoverBinding(
                cutoverGeneration = cutoverGeneration,
                legacyPrefixReceiptFingerprint =
                    legacyPrefixReceipt.receiptFingerprint,
            )
        prefixDurable
            .getOrNull(STUDENT_DOCUMENT_IMPORT_STAGE_INDEX)
            ?.let { documentImportReceipt ->
                studentAttestationOwner.bindDocumentImportReceipt(
                    binding = binding,
                    receipt = documentImportReceipt,
                )
            }
        val steps =
            prefixSteps +
                terminalOperations.map { operation ->
                    BoundTerminalAuthorityCutoverStep(
                        layout = layout,
                        operation = operation,
                        binding = binding,
                        clock = clock,
                    )
                }
        val migrated =
            VerifiedAuthorityCutoverPrefixCoordinator(
                layout = layout,
                steps = steps,
                journal = journal,
            ).migrateVerifiedPrefix()
        check(
            migrated.durableStages ==
                ThreeAuthorityCutoverStage.legacyJournalPrefix,
        ) {
            "Terminal writer did not persist the exact twelve-stage journal"
        }
        check(migrated.blockedAt == ThreeAuthorityCutoverStage.CUTOVER_COMPLETE) {
            "Terminal writer attempted to stop before the terminal journal boundary"
        }
        return TerminalAuthorityCutoverJournalResult(
            cutoverGeneration = cutoverGeneration,
            legacyPrefixReceiptFingerprint =
                binding.legacyPrefixReceiptFingerprint,
            terminalReceiptFingerprint =
                checkNotNull(migrated.lastReceiptFingerprint),
            durableStages = migrated.durableStages,
        )
    }

    override fun close() {
        studentAttestationOwner.close()
    }
}

private class BoundTerminalAuthorityCutoverStep(
    private val layout: ThreeAuthorityDatabaseLayout,
    private val operation: TerminalAuthorityStageOperation,
    private val binding: TerminalAuthorityCutoverBinding,
    private val clock: () -> Long,
) : AuthorityCutoverStep {
    override val stage: ThreeAuthorityCutoverStage = operation.stage

    override suspend fun migrate(
        previous: AuthorityStageReceipt?,
    ): AuthorityStageReceipt {
        val predecessor =
            checkNotNull(previous) {
                "${stage.name} requires its exact predecessor"
            }
        val stageIndex =
            ThreeAuthorityCutoverStage.legacyJournalPrefix.indexOf(stage)
        check(
            ThreeAuthorityCutoverStage.legacyJournalPrefix
                .getOrNull(stageIndex - 1) == predecessor.stage,
        ) {
            "${stage.name} received the wrong predecessor"
        }
        val state =
            operation
                .migrate(binding, predecessor)
                .toJournalState(stage, binding)
        return AuthorityStageReceipt.create(
            stage = stage,
            targetDatabaseName = layout.cutoverStorageNameFor(stage.storageTarget),
            migratedRecordCount = state.migratedRecordCount,
            sourceCheckpoint = state.sourceCheckpoint,
            destinationFingerprint = state.destinationFingerprint,
            completedAtEpochMillis = clock().coerceAtLeast(0L),
            previousReceiptFingerprint = predecessor.receiptFingerprint,
        )
    }

    override suspend fun verify(
        receipt: AuthorityStageReceipt,
    ): AuthorityStageVerification {
        check(receipt.stage == stage) {
            "Terminal operation cannot verify a foreign stage"
        }
        check(
            receipt.targetDatabaseName ==
                layout.cutoverStorageNameFor(stage.storageTarget),
        ) {
            "${stage.name} receipt targets the wrong authority"
        }
        val current = operation.verify(binding).toJournalState(stage, binding)
        check(receipt.migratedRecordCount == current.migratedRecordCount) {
            "${stage.name} record count changed after migration"
        }
        check(receipt.sourceCheckpoint == current.sourceCheckpoint) {
            "${stage.name} source checkpoint or cutover binding changed"
        }
        check(receipt.destinationFingerprint == current.destinationFingerprint) {
            "${stage.name} destination state changed after migration"
        }
        return AuthorityStageVerification(
            receiptFingerprint = receipt.receiptFingerprint,
            destinationFingerprint = current.destinationFingerprint,
        )
    }
}

private class StudentDocumentsImportedOperation(
    private val migrator: TerminalStudentAuthorityMigrator,
) : TerminalAuthorityStageOperation {
    override val stage: ThreeAuthorityCutoverStage =
        ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED

    override suspend fun migrate(
        binding: TerminalAuthorityCutoverBinding,
        predecessor: AuthorityStageReceipt,
    ): TerminalAuthorityStageSnapshot = readState(binding)

    override suspend fun verify(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot = readState(binding)

    private suspend fun readState(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot {
        val result = migrator.migrate()
        check(result.cutoverGeneration == binding.cutoverGeneration) {
            "Student import used a different cutover generation"
        }
        check(result.manifest.recordCount == result.ledgerDigest.migratedRecordCount) {
            "Student import ledger count does not match the exact source manifest"
        }
        return TerminalAuthorityStageSnapshot(
            migratedRecordCount = result.manifest.recordCount,
            sourceCheckpoint = result.manifest.sourceCheckpoint,
            destinationFingerprint = result.ledgerDigest.destinationCanonicalFingerprint,
        )
    }
}

private class StudentOutboxReconciledOperation(
    private val owner: StudentTerminalAuthorityAttestationOwner,
) : TerminalAuthorityStageOperation {
    override val stage: ThreeAuthorityCutoverStage =
        ThreeAuthorityCutoverStage.STUDENT_OUTBOX_RECONCILED

    override suspend fun migrate(
        binding: TerminalAuthorityCutoverBinding,
        predecessor: AuthorityStageReceipt,
    ): TerminalAuthorityStageSnapshot {
        owner.bindDocumentImportReceipt(binding, predecessor)
        return owner.attestOutbox(binding)
    }

    override suspend fun verify(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot = owner.attestOutbox(binding)
}

internal fun createStudentDestinationAttestationOperations(
    owner: StudentTerminalAuthorityAttestationOwner,
): List<TerminalAuthorityStageOperation> =
    listOf(
        StudentOutboxReconciledOperation(owner),
        StudentIndexesRebuiltOperation(owner),
        StudentAuthorityVerifiedOperation(owner),
    )

private class StudentIndexesRebuiltOperation(
    private val owner: StudentTerminalAuthorityAttestationOwner,
) : TerminalAuthorityStageOperation {
    override val stage: ThreeAuthorityCutoverStage =
        ThreeAuthorityCutoverStage.STUDENT_INDEXES_REBUILT

    override suspend fun migrate(
        binding: TerminalAuthorityCutoverBinding,
        predecessor: AuthorityStageReceipt,
    ): TerminalAuthorityStageSnapshot = owner.attestIndexes(binding)

    override suspend fun verify(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot = owner.attestIndexes(binding)
}

private class StudentAuthorityVerifiedOperation(
    private val owner: StudentTerminalAuthorityAttestationOwner,
) : TerminalAuthorityStageOperation {
    override val stage: ThreeAuthorityCutoverStage =
        ThreeAuthorityCutoverStage.STUDENT_AUTHORITY_VERIFIED

    override suspend fun migrate(
        binding: TerminalAuthorityCutoverBinding,
        predecessor: AuthorityStageReceipt,
    ): TerminalAuthorityStageSnapshot = owner.attestAuthority(binding)

    override suspend fun verify(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot = owner.attestAuthority(binding)
}

private class MasteryFactsImportedOperation(
    private val migrator: TerminalLearnerMasteryAuthorityMigrator,
) : TerminalAuthorityStageOperation {
    override val stage: ThreeAuthorityCutoverStage =
        ThreeAuthorityCutoverStage.MASTERY_FACTS_IMPORTED

    override suspend fun migrate(
        binding: TerminalAuthorityCutoverBinding,
        predecessor: AuthorityStageReceipt,
    ): TerminalAuthorityStageSnapshot = readState(binding)

    override suspend fun verify(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot = readState(binding)

    private suspend fun readState(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot {
        val result = migrator.migrate()
        check(result.cutoverGeneration == binding.cutoverGeneration) {
            "Mastery import used a different cutover generation"
        }
        check(
            result.manifest.recordCount ==
                result.ledgerDigest.migratedObservationCount,
        ) {
            "Mastery import ledger count does not match the exact source manifest"
        }
        return TerminalAuthorityStageSnapshot(
            migratedRecordCount = result.manifest.recordCount,
            sourceCheckpoint = result.manifest.sourceCheckpoint,
            destinationFingerprint = result.ledgerDigest.destinationCanonicalFingerprint,
        )
    }
}

private data class TerminalAuthorityJournalState(
    val migratedRecordCount: Long,
    val sourceCheckpoint: String,
    val destinationFingerprint: String,
)

private fun TerminalAuthorityStageSnapshot.toJournalState(
    stage: ThreeAuthorityCutoverStage,
    binding: TerminalAuthorityCutoverBinding,
): TerminalAuthorityJournalState {
    if (stage in STUDENT_OWNER_ATTESTED_STAGES) {
        return TerminalAuthorityJournalState(
            migratedRecordCount = migratedRecordCount,
            sourceCheckpoint = sourceCheckpoint,
            destinationFingerprint = destinationFingerprint,
        )
    }
    val boundCheckpoint =
        "generation:${binding.cutoverGeneration}:" +
            "prefix:${binding.legacyPrefixReceiptFingerprint}:" +
            "source:$sourceCheckpoint"
    check(boundCheckpoint.length <= MAX_CUTOVER_CHECKPOINT_LENGTH) {
        "Bound terminal source checkpoint exceeds the durable journal limit"
    }
    return TerminalAuthorityJournalState(
        migratedRecordCount = migratedRecordCount,
        sourceCheckpoint = boundCheckpoint,
        destinationFingerprint =
            CanonicalSha256("terminal-authority-stage-state-v1")
                .field("stage", stage.name)
                .field("cutoverGeneration", binding.cutoverGeneration)
                .field(
                    "legacyPrefixReceiptFingerprint",
                    binding.legacyPrefixReceiptFingerprint,
                )
                .field("migratedRecordCount", migratedRecordCount)
                .field("sourceCheckpoint", sourceCheckpoint)
                .field("rawDestinationFingerprint", destinationFingerprint)
                .finish(),
    )
}

/** Freshly compares one owner operation with its immutable terminal receipt. */
internal suspend fun requireCurrentTerminalStageReceipt(
    layout: ThreeAuthorityDatabaseLayout,
    operation: TerminalAuthorityStageOperation,
    binding: TerminalAuthorityCutoverBinding,
    receipt: AuthorityStageReceipt,
) {
    check(receipt.stage == operation.stage) {
        "Terminal owner operation received a foreign journal receipt"
    }
    check(
        receipt.targetDatabaseName ==
            layout.cutoverStorageNameFor(receipt.stage.storageTarget),
    ) {
        "${receipt.stage.name} receipt targets the wrong authority"
    }
    val current = operation.verify(binding).toJournalState(operation.stage, binding)
    check(receipt.migratedRecordCount == current.migratedRecordCount) {
        "${receipt.stage.name} current-policy record count changed"
    }
    check(receipt.sourceCheckpoint == current.sourceCheckpoint) {
        "${receipt.stage.name} current-policy checkpoint changed"
    }
    check(receipt.destinationFingerprint == current.destinationFingerprint) {
        "${receipt.stage.name} current-policy destination attestation changed"
    }
}

private const val PREFIX_STAGE_COUNT = 4
private const val STUDENT_DOCUMENT_IMPORT_STAGE_INDEX = 4
private const val MAX_CUTOVER_CHECKPOINT_LENGTH = 512
private const val MAX_RAW_TERMINAL_CHECKPOINT_LENGTH = 384
private val TERMINAL_SHA_256 = Regex("[0-9a-f]{64}")
private val REQUIRED_POST_IMPORT_STAGES =
    listOf(
        ThreeAuthorityCutoverStage.MASTERY_BINDINGS_RECONCILED,
        ThreeAuthorityCutoverStage.MASTERY_PROJECTIONS_REBUILT,
        ThreeAuthorityCutoverStage.MASTERY_AUTHORITY_VERIFIED,
    )
private val STUDENT_OWNER_ATTESTED_STAGES =
    setOf(
        ThreeAuthorityCutoverStage.STUDENT_OUTBOX_RECONCILED,
        ThreeAuthorityCutoverStage.STUDENT_INDEXES_REBUILT,
        ThreeAuthorityCutoverStage.STUDENT_AUTHORITY_VERIFIED,
    )
