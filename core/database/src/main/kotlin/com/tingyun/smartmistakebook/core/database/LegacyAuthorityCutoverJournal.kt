package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256

object LegacyAuthorityDatabaseName {
    const val STUDENT_MISTAKES = "student-mistakes.db"
    const val LEARNER_MASTERY = "learner-mastery.db"
    const val HIGH_SCHOOL_KNOWLEDGE = "high-school-knowledge.db"
    const val LEGACY_SESSION_COORDINATION = StudyDatabaseFactory.DEFAULT_DATABASE_NAME

    internal val supported = setOf(
        STUDENT_MISTAKES,
        LEARNER_MASTERY,
        HIGH_SCHOOL_KNOWLEDGE,
        LEGACY_SESSION_COORDINATION,
    )
}

data class AppendLegacyAuthorityCutoverStageCommand(
    val stageOrdinal: Int,
    val stageName: String,
    val targetDatabaseName: String,
    val migratedRecordCount: Long,
    val checkpoint: String,
    val destinationFingerprint: String,
    val completedAtEpochMillis: Long,
    val predecessorReceiptFingerprint: String?,
) {
    init {
        validateLegacyAuthorityCutoverFields(
            stageOrdinal = stageOrdinal,
            stageName = stageName,
            targetDatabaseName = targetDatabaseName,
            migratedRecordCount = migratedRecordCount,
            checkpoint = checkpoint,
            destinationFingerprint = destinationFingerprint,
            completedAtEpochMillis = completedAtEpochMillis,
            predecessorReceiptFingerprint = predecessorReceiptFingerprint,
        )
    }
}

data class LegacyAuthorityCutoverStageReceipt(
    val stageOrdinal: Int,
    val stageName: String,
    val targetDatabaseName: String,
    val migratedRecordCount: Long,
    val checkpoint: String,
    val destinationFingerprint: String,
    val completedAtEpochMillis: Long,
    val predecessorReceiptFingerprint: String?,
    val receiptFingerprint: String,
) {
    init {
        validateLegacyAuthorityCutoverFields(
            stageOrdinal = stageOrdinal,
            stageName = stageName,
            targetDatabaseName = targetDatabaseName,
            migratedRecordCount = migratedRecordCount,
            checkpoint = checkpoint,
            destinationFingerprint = destinationFingerprint,
            completedAtEpochMillis = completedAtEpochMillis,
            predecessorReceiptFingerprint = predecessorReceiptFingerprint,
        )
        requireSha256(receiptFingerprint, "receiptFingerprint")
    }
}

enum class LegacyAuthorityCutoverJournalWriteOutcome {
    INSERTED,
    REPLAYED,
}

data class LegacyAuthorityCutoverJournalWriteResult(
    val outcome: LegacyAuthorityCutoverJournalWriteOutcome,
    val receipt: LegacyAuthorityCutoverStageReceipt,
)

class LegacyAuthorityCutoverReceiptConflictException(stageOrdinal: Int) :
    IllegalStateException("Cutover stage $stageOrdinal already has a different receipt")

class LegacyAuthorityCutoverJournalOrderException(message: String) :
    IllegalStateException(message)

class LegacyAuthorityCutoverJournalIntegrityException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal object LegacyAuthorityCutoverReceiptFingerprint {
    private const val DOMAIN = "legacy-authority-cutover-stage-receipt-v1"

    fun of(command: AppendLegacyAuthorityCutoverStageCommand): String = calculate(
        stageOrdinal = command.stageOrdinal,
        stageName = command.stageName,
        targetDatabaseName = command.targetDatabaseName,
        migratedRecordCount = command.migratedRecordCount,
        checkpoint = command.checkpoint,
        destinationFingerprint = command.destinationFingerprint,
        completedAtEpochMillis = command.completedAtEpochMillis,
        predecessorReceiptFingerprint = command.predecessorReceiptFingerprint,
    )

    fun of(receipt: LegacyAuthorityCutoverStageReceipt): String = calculate(
        stageOrdinal = receipt.stageOrdinal,
        stageName = receipt.stageName,
        targetDatabaseName = receipt.targetDatabaseName,
        migratedRecordCount = receipt.migratedRecordCount,
        checkpoint = receipt.checkpoint,
        destinationFingerprint = receipt.destinationFingerprint,
        completedAtEpochMillis = receipt.completedAtEpochMillis,
        predecessorReceiptFingerprint = receipt.predecessorReceiptFingerprint,
    )

    private fun calculate(
        stageOrdinal: Int,
        stageName: String,
        targetDatabaseName: String,
        migratedRecordCount: Long,
        checkpoint: String,
        destinationFingerprint: String,
        completedAtEpochMillis: Long,
        predecessorReceiptFingerprint: String?,
    ): String = CanonicalSha256(DOMAIN)
        .field("stageOrdinal", stageOrdinal)
        .field("stageName", stageName)
        .field("targetDatabaseName", targetDatabaseName)
        .field("migratedRecordCount", migratedRecordCount)
        .field("checkpoint", checkpoint)
        .field("destinationFingerprint", destinationFingerprint)
        .field("completedAtEpochMillis", completedAtEpochMillis)
        .nullableField("predecessorReceiptFingerprint", predecessorReceiptFingerprint)
        .finish()
}

internal object LegacyAuthorityCutoverJournalChain {
    fun receipt(command: AppendLegacyAuthorityCutoverStageCommand) =
        LegacyAuthorityCutoverStageReceipt(
            stageOrdinal = command.stageOrdinal,
            stageName = command.stageName,
            targetDatabaseName = command.targetDatabaseName,
            migratedRecordCount = command.migratedRecordCount,
            checkpoint = command.checkpoint,
            destinationFingerprint = command.destinationFingerprint,
            completedAtEpochMillis = command.completedAtEpochMillis,
            predecessorReceiptFingerprint = command.predecessorReceiptFingerprint,
            receiptFingerprint = LegacyAuthorityCutoverReceiptFingerprint.of(command),
        )

    fun verify(receipts: List<LegacyAuthorityCutoverStageReceipt>) {
        var expectedOrdinal = 1
        var expectedPredecessor: String? = null
        val stageNames = hashSetOf<String>()
        val fingerprints = hashSetOf<String>()
        receipts.forEach { receipt ->
            if (receipt.stageOrdinal != expectedOrdinal) {
                throw LegacyAuthorityCutoverJournalIntegrityException(
                    "Cutover journal stage order is not contiguous at ordinal $expectedOrdinal",
                )
            }
            if (receipt.predecessorReceiptFingerprint != expectedPredecessor) {
                throw LegacyAuthorityCutoverJournalIntegrityException(
                    "Cutover journal predecessor mismatch at ordinal ${receipt.stageOrdinal}",
                )
            }
            if (receipt.receiptFingerprint != LegacyAuthorityCutoverReceiptFingerprint.of(receipt)) {
                throw LegacyAuthorityCutoverJournalIntegrityException(
                    "Cutover journal receipt fingerprint mismatch at ordinal ${receipt.stageOrdinal}",
                )
            }
            if (!stageNames.add(receipt.stageName) || !fingerprints.add(receipt.receiptFingerprint)) {
                throw LegacyAuthorityCutoverJournalIntegrityException(
                    "Cutover journal contains a duplicate immutable identity",
                )
            }
            expectedOrdinal += 1
            expectedPredecessor = receipt.receiptFingerprint
        }
    }

    fun requireNext(
        persisted: List<LegacyAuthorityCutoverStageReceipt>,
        candidate: LegacyAuthorityCutoverStageReceipt,
    ) {
        val previous = persisted.lastOrNull()
        val expectedOrdinal = (previous?.stageOrdinal ?: 0) + 1
        val expectedPredecessor = previous?.receiptFingerprint
        if (
            candidate.stageOrdinal != expectedOrdinal ||
            candidate.predecessorReceiptFingerprint != expectedPredecessor
        ) {
            throw LegacyAuthorityCutoverJournalOrderException(
                "Cutover stage ${candidate.stageOrdinal} does not extend the durable journal head",
            )
        }
    }
}

private fun validateLegacyAuthorityCutoverFields(
    stageOrdinal: Int,
    stageName: String,
    targetDatabaseName: String,
    migratedRecordCount: Long,
    checkpoint: String,
    destinationFingerprint: String,
    completedAtEpochMillis: Long,
    predecessorReceiptFingerprint: String?,
) {
    require(stageOrdinal > 0) { "stageOrdinal must be positive" }
    requireOpaque(stageName, "stageName", maxLength = 128)
    require(targetDatabaseName in LegacyAuthorityDatabaseName.supported) {
        "targetDatabaseName must identify one separated authority database"
    }
    require(migratedRecordCount >= 0) { "migratedRecordCount must not be negative" }
    requireOpaque(checkpoint, "checkpoint", maxLength = 4_096)
    requireSha256(destinationFingerprint, "destinationFingerprint")
    require(completedAtEpochMillis >= 0) { "completedAtEpochMillis must not be negative" }
    predecessorReceiptFingerprint?.let { requireSha256(it, "predecessorReceiptFingerprint") }
    require((stageOrdinal == 1) == (predecessorReceiptFingerprint == null)) {
        "Only the first cutover stage may omit its predecessor fingerprint"
    }
}

private fun requireOpaque(value: String, label: String, maxLength: Int) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= maxLength &&
            value.none(Char::isISOControl),
    ) { "$label must be a trimmed opaque value of at most $maxLength characters" }
}

private fun requireSha256(value: String, label: String) {
    require(SHA256.matches(value)) { "$label must be a lowercase SHA-256 fingerprint" }
}

private val SHA256 = Regex("[0-9a-f]{64}")
