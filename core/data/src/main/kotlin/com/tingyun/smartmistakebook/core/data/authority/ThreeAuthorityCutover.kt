package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.model.CanonicalSha256

internal const val LEGACY_MIGRATION_SOURCE_DATABASE_NAME = "smart-mistake-book.db"

/**
 * Production filenames are part of the persistence contract. This layout intentionally contains
 * exactly the three product authorities. The legacy file is a temporary migration source, never a
 * fourth authority.
 */
internal data class ThreeAuthorityDatabaseLayout(
    val studentMistakes: String = "student-mistakes.db",
    val learnerMastery: String = "learner-mastery.db",
    val highSchoolKnowledge: String = "high-school-knowledge.db",
) {
    init {
        require(studentMistakes == "student-mistakes.db")
        require(learnerMastery == "learner-mastery.db")
        require(highSchoolKnowledge == "high-school-knowledge.db")
        require(
            setOf(
                studentMistakes,
                learnerMastery,
                highSchoolKnowledge,
            ).size == 3,
        ) {
            "Student mistakes, learner mastery, and high-school knowledge must use distinct files"
        }
        require(
            LEGACY_MIGRATION_SOURCE_DATABASE_NAME !in
                setOf(studentMistakes, learnerMastery, highSchoolKnowledge),
        ) {
            "The temporary migration source must not be a product authority"
        }
    }

    fun cutoverStorageNameFor(target: CutoverStorageTarget): String =
        when (target) {
            CutoverStorageTarget.LEGACY_MIGRATION_SOURCE ->
                LEGACY_MIGRATION_SOURCE_DATABASE_NAME
            CutoverStorageTarget.STUDENT_MISTAKES -> studentMistakes
            CutoverStorageTarget.LEARNER_MASTERY -> learnerMastery
            CutoverStorageTarget.HIGH_SCHOOL_KNOWLEDGE -> highSchoolKnowledge
        }
}

internal enum class CutoverStorageTarget {
    LEGACY_MIGRATION_SOURCE,
    STUDENT_MISTAKES,
    LEARNER_MASTERY,
    HIGH_SCHOOL_KNOWLEDGE,
}

/**
 * Monotonic production cutover. A completed stage is never rolled back or deleted; interrupted
 * work resumes at the first absent stage and repairs forward.
 */
internal enum class ThreeAuthorityCutoverStage(
    private val durableStorageTarget: CutoverStorageTarget?,
) {
    LEGACY_SCHEMA_READY(CutoverStorageTarget.LEGACY_MIGRATION_SOURCE),

    KNOWLEDGE_PACKAGE_INSTALLED(CutoverStorageTarget.HIGH_SCHOOL_KNOWLEDGE),
    KNOWLEDGE_PACKAGE_VERIFIED(CutoverStorageTarget.HIGH_SCHOOL_KNOWLEDGE),
    KNOWLEDGE_RUNTIME_READ_ONLY(CutoverStorageTarget.HIGH_SCHOOL_KNOWLEDGE),

    STUDENT_DOCUMENTS_IMPORTED(CutoverStorageTarget.STUDENT_MISTAKES),
    STUDENT_OUTBOX_RECONCILED(CutoverStorageTarget.STUDENT_MISTAKES),
    STUDENT_INDEXES_REBUILT(CutoverStorageTarget.STUDENT_MISTAKES),
    STUDENT_AUTHORITY_VERIFIED(CutoverStorageTarget.STUDENT_MISTAKES),

    MASTERY_FACTS_IMPORTED(CutoverStorageTarget.LEARNER_MASTERY),
    MASTERY_BINDINGS_RECONCILED(CutoverStorageTarget.LEARNER_MASTERY),
    MASTERY_PROJECTIONS_REBUILT(CutoverStorageTarget.LEARNER_MASTERY),
    MASTERY_AUTHORITY_VERIFIED(CutoverStorageTarget.LEARNER_MASTERY),

    /**
     * Decode-only compatibility marker for old receipt schemas.
     *
     * It is deliberately not executable and has no storage target. Global completion is proved
     * from the student and mastery databases plus the current knowledge activation witness.
     */
    CUTOVER_COMPLETE(null),
    ;

    val storageTarget: CutoverStorageTarget
        get() =
            checkNotNull(durableStorageTarget) {
                "CUTOVER_COMPLETE has no database target"
            }

    companion object {
        val legacyJournalPrefix: List<ThreeAuthorityCutoverStage> =
            entries.filter { it.durableStorageTarget != null }
    }
}

@ConsistentCopyVisibility
internal data class AuthorityStageReceipt private constructor(
    val stage: ThreeAuthorityCutoverStage,
    val targetDatabaseName: String,
    val migratedRecordCount: Long,
    val sourceCheckpoint: String,
    val destinationFingerprint: String,
    val completedAtEpochMillis: Long,
    val previousReceiptFingerprint: String?,
    val receiptFingerprint: String,
) {
    init {
        require(migratedRecordCount >= 0L) {
            "Migrated record count must not be negative"
        }
        require(sourceCheckpoint.isValidCheckpoint()) {
            "Migration checkpoint is invalid"
        }
        require(destinationFingerprint.matches(SHA_256)) {
            "Destination receipt fingerprint must be lowercase SHA-256"
        }
        require(completedAtEpochMillis >= 0L) {
            "Migration completion time must not be negative"
        }
        require(
            previousReceiptFingerprint == null ||
                previousReceiptFingerprint.matches(SHA_256),
        ) {
            "Previous receipt fingerprint must be lowercase SHA-256"
        }
        require(receiptFingerprint.matches(SHA_256)) {
            "Stage receipt fingerprint must be lowercase SHA-256"
        }
    }

    fun hasValidFingerprint(): Boolean =
        receiptFingerprint ==
            computeFingerprint(
                stage = stage,
                targetDatabaseName = targetDatabaseName,
                migratedRecordCount = migratedRecordCount,
                sourceCheckpoint = sourceCheckpoint,
                destinationFingerprint = destinationFingerprint,
                completedAtEpochMillis = completedAtEpochMillis,
                previousReceiptFingerprint = previousReceiptFingerprint,
            )

    companion object {
        fun create(
            stage: ThreeAuthorityCutoverStage,
            targetDatabaseName: String,
            migratedRecordCount: Long,
            sourceCheckpoint: String,
            destinationFingerprint: String,
            completedAtEpochMillis: Long,
            previousReceiptFingerprint: String?,
        ): AuthorityStageReceipt {
            require(stage in ThreeAuthorityCutoverStage.legacyJournalPrefix) {
                "CUTOVER_COMPLETE is decode-only and cannot be created for the legacy journal"
            }
            return createDecoded(
                stage = stage,
                targetDatabaseName = targetDatabaseName,
                migratedRecordCount = migratedRecordCount,
                sourceCheckpoint = sourceCheckpoint,
                destinationFingerprint = destinationFingerprint,
                completedAtEpochMillis = completedAtEpochMillis,
                previousReceiptFingerprint = previousReceiptFingerprint,
            )
        }

        /**
         * Hydrates the exact fingerprint stored by the legacy journal.
         *
         * This is the only path that can represent an obsolete terminal receipt, and only so
         * startup can fail closed on its presence. It never creates current completion proof.
         */
        fun decodeLegacyReceipt(
            stage: ThreeAuthorityCutoverStage,
            targetDatabaseName: String,
            migratedRecordCount: Long,
            sourceCheckpoint: String,
            destinationFingerprint: String,
            completedAtEpochMillis: Long,
            previousReceiptFingerprint: String?,
            receiptFingerprint: String,
        ): AuthorityStageReceipt =
            AuthorityStageReceipt(
                stage = stage,
                targetDatabaseName = targetDatabaseName,
                migratedRecordCount = migratedRecordCount,
                sourceCheckpoint = sourceCheckpoint,
                destinationFingerprint = destinationFingerprint,
                completedAtEpochMillis = completedAtEpochMillis,
                previousReceiptFingerprint = previousReceiptFingerprint,
                receiptFingerprint = receiptFingerprint,
            )

        private fun createDecoded(
            stage: ThreeAuthorityCutoverStage,
            targetDatabaseName: String,
            migratedRecordCount: Long,
            sourceCheckpoint: String,
            destinationFingerprint: String,
            completedAtEpochMillis: Long,
            previousReceiptFingerprint: String?,
        ): AuthorityStageReceipt {
            val receiptFingerprint =
                computeFingerprint(
                    stage = stage,
                    targetDatabaseName = targetDatabaseName,
                    migratedRecordCount = migratedRecordCount,
                    sourceCheckpoint = sourceCheckpoint,
                    destinationFingerprint = destinationFingerprint,
                    completedAtEpochMillis = completedAtEpochMillis,
                    previousReceiptFingerprint = previousReceiptFingerprint,
                )
            return AuthorityStageReceipt(
                stage = stage,
                targetDatabaseName = targetDatabaseName,
                migratedRecordCount = migratedRecordCount,
                sourceCheckpoint = sourceCheckpoint,
                destinationFingerprint = destinationFingerprint,
                completedAtEpochMillis = completedAtEpochMillis,
                previousReceiptFingerprint = previousReceiptFingerprint,
                receiptFingerprint = receiptFingerprint,
            )
        }

        private fun computeFingerprint(
            stage: ThreeAuthorityCutoverStage,
            targetDatabaseName: String,
            migratedRecordCount: Long,
            sourceCheckpoint: String,
            destinationFingerprint: String,
            completedAtEpochMillis: Long,
            previousReceiptFingerprint: String?,
        ): String =
            CanonicalSha256("legacy-authority-cutover-stage-receipt-v1")
                .field("stageOrdinal", stage.ordinal + 1)
                .field("stageName", stage.name)
                .field("targetDatabaseName", targetDatabaseName)
                .field("migratedRecordCount", migratedRecordCount)
                .field("checkpoint", sourceCheckpoint)
                .field("destinationFingerprint", destinationFingerprint)
                .field("completedAtEpochMillis", completedAtEpochMillis)
                .nullableField(
                    "predecessorReceiptFingerprint",
                    previousReceiptFingerprint,
                )
                .finish()
    }
}

internal data class AuthorityStageVerification(
    val receiptFingerprint: String,
    val destinationFingerprint: String,
) {
    init {
        require(receiptFingerprint.matches(SHA_256))
        require(destinationFingerprint.matches(SHA_256))
    }
}

/**
 * One stage owns one authority. Implementations may copy a bounded page from legacy state into
 * their destination, but the coordinator never receives records or database handles.
 */
internal interface AuthorityCutoverStep {
    val stage: ThreeAuthorityCutoverStage

    suspend fun migrate(previous: AuthorityStageReceipt?): AuthorityStageReceipt

    suspend fun verify(receipt: AuthorityStageReceipt): AuthorityStageVerification
}

/**
 * The journal implementation must append transactionally and return the durable receipt. If the
 * stage already exists, it returns the existing receipt instead of overwriting it.
 */
internal interface AuthorityCutoverJournal {
    suspend fun readOrdered(): List<AuthorityStageReceipt>

    suspend fun appendIfAbsent(receipt: AuthorityStageReceipt): AuthorityStageReceipt
}

/**
 * Result of a deliberately incomplete production cutover.
 *
 * A verified prefix is durable, while [blockedAt] remains outside the temporary legacy migration
 * journal. It cannot claim terminal completion and cannot skip a stage.
 */
internal data class AuthorityCutoverPrefixResult(
    val durableStages: List<ThreeAuthorityCutoverStage>,
    val lastReceiptFingerprint: String?,
    val blockedAt: ThreeAuthorityCutoverStage,
)

/**
 * Persists only an exact, contiguous set of concrete stages.
 *
 * It exists for production rollouts where later authority stages are intentionally unavailable.
 * Missing stages are reported, never represented by no-op success implementations.
 */
internal class VerifiedAuthorityCutoverPrefixCoordinator(
    private val layout: ThreeAuthorityDatabaseLayout,
    steps: List<AuthorityCutoverStep>,
    private val journal: AuthorityCutoverJournal,
) {
    private val orderedSteps = steps.toList()

    init {
        require(orderedSteps.isNotEmpty()) {
            "A verified cutover prefix must contain at least one concrete stage"
        }
        require(orderedSteps.map(AuthorityCutoverStep::stage).distinct().size == orderedSteps.size) {
            "A cutover stage may have only one implementation"
        }
        require(
            orderedSteps.map(AuthorityCutoverStep::stage) ==
                ThreeAuthorityCutoverStage.legacyJournalPrefix.take(orderedSteps.size),
        ) {
            "Verified cutover steps must form an exact stage prefix"
        }
        require(orderedSteps.size <= ThreeAuthorityCutoverStage.legacyJournalPrefix.size) {
            "CUTOVER_COMPLETE cannot be written to the legacy migration journal"
        }
    }

    suspend fun migrateVerifiedPrefix(): AuthorityCutoverPrefixResult {
        val durable = journal.readOrdered().toMutableList()
        requireValidAuthorityCutoverJournalPrefix(layout, durable)
        check(durable.size <= orderedSteps.size) {
            "Cutover journal is ahead of the supported production prefix"
        }
        durable.forEachIndexed { index, receipt ->
            val verification = orderedSteps[index].verify(receipt)
            check(verification.receiptFingerprint == receipt.receiptFingerprint) {
                "Durable ${receipt.stage.name} verification did not bind its receipt"
            }
            check(verification.destinationFingerprint == receipt.destinationFingerprint) {
                "Durable ${receipt.stage.name} no longer matches the destination state"
            }
        }

        orderedSteps.drop(durable.size).forEach { step ->
            val previous = durable.lastOrNull()
            val proposed = step.migrate(previous)
            check(proposed.stage == step.stage) {
                "Cutover implementation returned a receipt for the wrong stage"
            }
            check(proposed.previousReceiptFingerprint == previous?.receiptFingerprint) {
                "Cutover implementation returned a receipt for the wrong predecessor"
            }
            check(proposed.hasValidFingerprint()) {
                "Cutover implementation returned an invalid receipt fingerprint"
            }
            validateTarget(proposed)

            val verification = step.verify(proposed)
            check(verification.receiptFingerprint == proposed.receiptFingerprint) {
                "Stage verification did not bind the proposed receipt"
            }
            check(verification.destinationFingerprint == proposed.destinationFingerprint) {
                "Stage verification did not bind the destination state"
            }
            val persisted = journal.appendIfAbsent(proposed)
            check(persisted == proposed) {
                "A different receipt already occupies ${step.stage.name}; forward repair is required"
            }
            durable += persisted
        }

        return AuthorityCutoverPrefixResult(
            durableStages = durable.map(AuthorityStageReceipt::stage),
            lastReceiptFingerprint = durable.lastOrNull()?.receiptFingerprint,
            blockedAt =
                ThreeAuthorityCutoverStage.legacyJournalPrefix
                    .getOrNull(orderedSteps.size)
                    ?: ThreeAuthorityCutoverStage.CUTOVER_COMPLETE,
        )
    }

    private fun validateTarget(receipt: AuthorityStageReceipt) {
        check(
            receipt.targetDatabaseName ==
                layout.cutoverStorageNameFor(receipt.stage.storageTarget),
        ) {
            "${receipt.stage.name} targeted the wrong database file"
        }
    }
}

/**
 * Structural journal validation shared by prefix preparation and terminal recovery.
 *
 * This check trusts neither receipt order nor a journal implementation's duplicate handling. It
 * performs no destination I/O and therefore cannot replace each concrete step's fresh verify.
 */
internal fun requireValidAuthorityCutoverJournalPrefix(
    layout: ThreeAuthorityDatabaseLayout,
    receipts: List<AuthorityStageReceipt>,
) {
    require(receipts.size <= ThreeAuthorityCutoverStage.legacyJournalPrefix.size) {
        "Cutover journal contains too many stages"
    }
    receipts.forEachIndexed { index, receipt ->
        check(receipt.stage == ThreeAuthorityCutoverStage.legacyJournalPrefix[index]) {
            "Cutover journal is not a monotonic stage prefix"
        }
        check(receipt.hasValidFingerprint()) {
            "Cutover journal contains a tampered stage receipt"
        }
        check(
            receipt.previousReceiptFingerprint ==
                receipts.getOrNull(index - 1)?.receiptFingerprint,
        ) {
            "Cutover receipt chain is broken"
        }
        check(
            receipt.targetDatabaseName ==
                layout.cutoverStorageNameFor(receipt.stage.storageTarget),
        ) {
            "${receipt.stage.name} targeted the wrong database file"
        }
    }
}

private val SHA_256 = Regex("[0-9a-f]{64}")

private fun String.isValidCheckpoint(): Boolean =
    isNotBlank() &&
        length <= 512 &&
        none { it.isISOControl() }
