package com.tingyun.smartmistakebook.core.database

import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.entity.LegacyBusinessWriteBarrierEntity

internal data class ActivateLegacyBusinessWriteBarrierCommand(
    val terminalReceiptFingerprint: String,
) {
    init {
        requireLowerSha256(terminalReceiptFingerprint, "terminalReceiptFingerprint")
    }
}

internal data class LegacyBusinessWriteBarrierRecord(
    val activationKind: LegacyBusinessWriteBarrierActivationKind,
    val terminalStageOrdinal: Int?,
    val terminalReceiptFingerprint: String?,
    val activationReceiptFingerprint: String,
    val activatedAtEpochMillis: Long,
)

internal enum class LegacyBusinessWriteBarrierWriteOutcome {
    ACTIVATED,
    REPLAYED,
}

internal data class LegacyBusinessWriteBarrierWriteResult(
    val outcome: LegacyBusinessWriteBarrierWriteOutcome,
    val barrier: LegacyBusinessWriteBarrierRecord,
)

/**
 * Narrow, internal cutover capability. It can only inspect or irreversibly activate the barrier;
 * it cannot expose SQL, business DAOs, trigger deletion, or a reversal operation.
 */
internal interface LegacyBusinessWriteBarrierCapability {
    suspend fun read(): LegacyBusinessWriteBarrierRecord?

    suspend fun activate(
        command: ActivateLegacyBusinessWriteBarrierCommand,
    ): LegacyBusinessWriteBarrierWriteResult
}

/**
 * Irreversible owner capability used only by the production three-authority startup gate.
 *
 * The surface deliberately exposes neither the legacy database nor a reversal operation. The
 * caller must still present the exact terminal journal head; the database re-verifies the complete
 * 12-stage journal in the activating transaction.
 */
interface LegacyBusinessWriteBarrierOwnerPort {
    suspend fun readState(): LegacyBusinessWriteBarrierState

    suspend fun activateTerminalCutover(
        terminalReceiptFingerprint: String,
    ): LegacyBusinessWriteBarrierOwnerResult
}

enum class LegacyBusinessWriteBarrierState {
    ABSENT,
    FRESH_EMPTY,
    TERMINAL_CUTOVER,
}

enum class LegacyBusinessWriteBarrierOwnerResult {
    ACTIVATED,
    REPLAYED,
}

internal class LegacyBusinessWriteBarrierEligibilityException(message: String) :
    IllegalStateException(message)

internal class LegacyBusinessWriteBarrierIntegrityException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal class LegacyBusinessWriteBarrierConflictException :
    IllegalStateException("Legacy business write barrier already has different durable state")

internal class RoomLegacyBusinessWriteBarrierCapability(
    private val store: RoomStudyDatabase,
) : LegacyBusinessWriteBarrierCapability {
    override suspend fun read(): LegacyBusinessWriteBarrierRecord? =
        store.database.withWriteTransaction {
            store.database
                .legacyBusinessWriteBarrierDao()
                .read(LEGACY_BUSINESS_WRITE_BARRIER_KEY)
                ?.toVerifiedRecord()
        }

    override suspend fun activate(
        command: ActivateLegacyBusinessWriteBarrierCommand,
    ): LegacyBusinessWriteBarrierWriteResult =
        store.database.withWriteTransaction {
            val barrierDao = store.database.legacyBusinessWriteBarrierDao()
            barrierDao.read(LEGACY_BUSINESS_WRITE_BARRIER_KEY)?.let { existing ->
                val record = existing.toVerifiedRecord()
                if (
                    record.activationKind !=
                        LegacyBusinessWriteBarrierActivationKind.TERMINAL_CUTOVER ||
                    record.terminalStageOrdinal !=
                        LegacyBusinessWriteBarrierSchema.TERMINAL_STAGE_ORDINAL ||
                    record.terminalReceiptFingerprint !=
                        command.terminalReceiptFingerprint
                ) {
                    throw LegacyBusinessWriteBarrierConflictException()
                }
                LegacyBusinessWriteBarrierTerminalPolicy.requireProof(
                    receipts = store.database.legacyAuthorityCutoverJournalDao().readOrdered(),
                    expectedTerminalFingerprint = command.terminalReceiptFingerprint,
                )
                return@withWriteTransaction LegacyBusinessWriteBarrierWriteResult(
                    outcome = LegacyBusinessWriteBarrierWriteOutcome.REPLAYED,
                    barrier = record,
                )
            }

            val terminalReceipt = LegacyBusinessWriteBarrierTerminalPolicy.requireProof(
                receipts = store.database.legacyAuthorityCutoverJournalDao().readOrdered(),
                expectedTerminalFingerprint = command.terminalReceiptFingerprint,
            )
            val candidate =
                LegacyBusinessWriteBarrierSchema.terminalCutoverEntity(
                    terminalReceiptFingerprint = terminalReceipt.receiptFingerprint,
                    activatedAtEpochMillis = store.trustedBarrierClockEpochMillis(),
                )
            val inserted = barrierDao.insert(candidate) != -1L
            val durable =
                barrierDao.read(LEGACY_BUSINESS_WRITE_BARRIER_KEY)
                    ?: throw LegacyBusinessWriteBarrierIntegrityException(
                        "Activated barrier was not readable in the activating transaction",
                    )
            val record = durable.toVerifiedRecord()
            if (!durable.hasSameDurableStateAs(candidate)) {
                throw LegacyBusinessWriteBarrierConflictException()
            }
            LegacyBusinessWriteBarrierWriteResult(
                outcome =
                    if (inserted) {
                        LegacyBusinessWriteBarrierWriteOutcome.ACTIVATED
                    } else {
                        LegacyBusinessWriteBarrierWriteOutcome.REPLAYED
                    },
                barrier = record,
            )
        }
}

internal class RoomLegacyBusinessWriteBarrierOwnerPort(
    private val capability: LegacyBusinessWriteBarrierCapability,
) : LegacyBusinessWriteBarrierOwnerPort {
    override suspend fun readState(): LegacyBusinessWriteBarrierState =
        when (capability.read()?.activationKind) {
            null -> LegacyBusinessWriteBarrierState.ABSENT
            LegacyBusinessWriteBarrierActivationKind.FRESH_EMPTY ->
                LegacyBusinessWriteBarrierState.FRESH_EMPTY
            LegacyBusinessWriteBarrierActivationKind.TERMINAL_CUTOVER ->
                LegacyBusinessWriteBarrierState.TERMINAL_CUTOVER
        }

    override suspend fun activateTerminalCutover(
        terminalReceiptFingerprint: String,
    ): LegacyBusinessWriteBarrierOwnerResult =
        when (
            capability.activate(
                ActivateLegacyBusinessWriteBarrierCommand(terminalReceiptFingerprint),
            ).outcome
        ) {
            LegacyBusinessWriteBarrierWriteOutcome.ACTIVATED ->
                LegacyBusinessWriteBarrierOwnerResult.ACTIVATED
            LegacyBusinessWriteBarrierWriteOutcome.REPLAYED ->
                LegacyBusinessWriteBarrierOwnerResult.REPLAYED
        }
}

internal object LegacyBusinessWriteBarrierTerminalPolicy {
    fun requireProof(
        receipts: List<LegacyAuthorityCutoverStageReceipt>,
        expectedTerminalFingerprint: String,
    ): LegacyAuthorityCutoverStageReceipt {
        val expectedStages = LegacyBusinessWriteBarrierSchema.terminalCutoverStages
        if (receipts.size != expectedStages.size) {
            throw LegacyBusinessWriteBarrierEligibilityException(
                "Legacy authority cutover requires all ${expectedStages.size} terminal stages",
            )
        }
        receipts.zip(expectedStages).forEach { (receipt, expected) ->
            if (
                receipt.stageOrdinal != expected.ordinal ||
                receipt.stageName != expected.stageName ||
                receipt.targetDatabaseName != expected.targetDatabaseName
            ) {
                throw LegacyBusinessWriteBarrierEligibilityException(
                    "Legacy authority cutover stage ${expected.ordinal} " +
                        "does not match the terminal policy",
                )
            }
        }
        val terminal = receipts.last()
        if (terminal.receiptFingerprint != expectedTerminalFingerprint) {
            throw LegacyBusinessWriteBarrierEligibilityException(
                "Terminal cutover receipt fingerprint does not match the activation command",
            )
        }
        return terminal
    }
}

internal fun LegacyBusinessWriteBarrierEntity.toVerifiedRecord():
    LegacyBusinessWriteBarrierRecord {
    val kind =
        try {
            LegacyBusinessWriteBarrierActivationKind.valueOf(activationKind)
        } catch (failure: IllegalArgumentException) {
            throw LegacyBusinessWriteBarrierIntegrityException(
                "Legacy business write barrier has an unknown activation kind",
                failure,
            )
        }
    val expected =
        try {
            when (kind) {
                LegacyBusinessWriteBarrierActivationKind.FRESH_EMPTY ->
                    LegacyBusinessWriteBarrierSchema.freshEmptyEntity(activatedAtEpochMillis)

                LegacyBusinessWriteBarrierActivationKind.TERMINAL_CUTOVER ->
                    LegacyBusinessWriteBarrierSchema.terminalCutoverEntity(
                        terminalReceiptFingerprint =
                            terminalReceiptFingerprint
                                ?: throw LegacyBusinessWriteBarrierIntegrityException(
                                    "Terminal barrier is missing its cutover receipt fingerprint",
                                ),
                        activatedAtEpochMillis = activatedAtEpochMillis,
                    )
            }
        } catch (failure: IllegalArgumentException) {
            throw LegacyBusinessWriteBarrierIntegrityException(
                "Legacy business write barrier contains invalid durable fields",
                failure,
            )
        }
    if (this != expected) {
        throw LegacyBusinessWriteBarrierIntegrityException(
            "Legacy business write barrier fingerprint or terminal proof is inconsistent",
        )
    }
    return LegacyBusinessWriteBarrierRecord(
        activationKind = kind,
        terminalStageOrdinal = terminalStageOrdinal,
        terminalReceiptFingerprint = terminalReceiptFingerprint,
        activationReceiptFingerprint = activationReceiptFingerprint,
        activatedAtEpochMillis = activatedAtEpochMillis,
    )
}

private fun LegacyBusinessWriteBarrierEntity.hasSameDurableStateAs(
    expected: LegacyBusinessWriteBarrierEntity,
): Boolean =
    barrierKey == expected.barrierKey &&
        activationKind == expected.activationKind &&
        terminalStageOrdinal == expected.terminalStageOrdinal &&
        terminalReceiptFingerprint == expected.terminalReceiptFingerprint &&
        activationReceiptFingerprint == expected.activationReceiptFingerprint &&
        activatedAtEpochMillis == expected.activatedAtEpochMillis

private fun requireLowerSha256(value: String, label: String) {
    require(value.length == 64 && value.all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }) {
        "$label must be a lowercase SHA-256 fingerprint"
    }
}
