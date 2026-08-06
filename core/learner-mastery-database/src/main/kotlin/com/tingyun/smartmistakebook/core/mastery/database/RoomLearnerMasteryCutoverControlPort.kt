package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID

internal class RoomLearnerMasteryCutoverControlPort(
    private val database: LearnerMasteryRoomDatabase,
    private val learnerId: String,
) : LearnerMasteryCutoverControlPort {
    private val dao = database.cutoverDao()

    init {
        requireLocalLearnerCutoverScope(learnerId)
    }

    override suspend fun readCutoverFence(): LearnerMasteryAuthorityCutoverFence? =
        dao.readCutoverFence()?.toDomain()

    override suspend fun appendCutoverFenceIfAbsent(
        candidate: LearnerMasteryAuthorityCutoverFence,
    ): LearnerMasteryAuthorityCutoverFence {
        readCutoverFence()?.let { return it }
        check(candidate.hasValidFingerprint()) {
            "Mastery cutover fence fingerprint is invalid"
        }
        return dao.appendCutoverFenceIfAbsent(candidate.toEntity()).toDomain()
    }

    override suspend fun readCompletionReceipt():
        LearnerMasteryAuthorityCutoverCompletionReceipt? =
        dao.readBoundCompletionReceipt()?.toDomain().also { receipt ->
            check(receipt == null || receipt.learnerId == learnerId) {
                "Mastery cutover completion receipt belongs to another learner"
            }
        }

    override suspend fun appendCompletionReceiptIfAbsent(
        candidate: LearnerMasteryAuthorityCutoverCompletionReceipt,
    ): LearnerMasteryAuthorityCutoverCompletionReceipt {
        readCompletionReceipt()?.let { return it }
        check(candidate.learnerId == learnerId) {
            "Mastery cutover completion receipt is outside the owner learner scope"
        }
        check(candidate.hasValidFingerprint()) {
            "Mastery cutover completion receipt fingerprint is invalid"
        }
        return dao.appendCompletionReceiptIfAbsent(candidate.toEntity()).toDomain().also { stored ->
            check(stored.learnerId == learnerId) {
                "Mastery cutover completion receipt belongs to another learner"
            }
        }
    }

    override suspend fun recomputeCompletedMigrationLedger(
        sourceGeneration: String,
    ): LearnerMasteryImmutableMigrationLedgerDigest? {
        requireMasteryVersion(sourceGeneration, "Legacy source generation")
        return dao.recomputeCompletedMigrationLedger(
            learnerId = learnerId,
            sourceGeneration = sourceGeneration,
        )
    }

    override fun close() {
        database.close()
    }
}

private fun requireLocalLearnerCutoverScope(learnerId: String) {
    require(learnerId == LOCAL_LEARNER_ID) {
        "Learner-mastery cutover is defined only for the device's fixed local learner"
    }
}

private fun LearnerMasteryAuthorityCutoverFence.toEntity():
    LearnerMasteryCutoverFenceEntity =
    LearnerMasteryCutoverFenceEntity(
        singletonKey = LEARNER_MASTERY_CUTOVER_SINGLETON_KEY,
        cutoverGeneration = cutoverGeneration,
        studentImportEvidenceFingerprint = studentImportEvidenceFingerprint,
        masteryImportEvidenceFingerprint = masteryImportEvidenceFingerprint,
        cutoverIntentFingerprint = cutoverIntentFingerprint,
        fenceFingerprint = fenceFingerprint,
    )

private fun LearnerMasteryCutoverFenceEntity.toDomain():
    LearnerMasteryAuthorityCutoverFence {
    check(singletonKey == LEARNER_MASTERY_CUTOVER_SINGLETON_KEY) {
        "Mastery cutover fence has an invalid singleton key"
    }
    return LearnerMasteryAuthorityCutoverFence(
        cutoverGeneration = cutoverGeneration,
        studentImportEvidenceFingerprint = studentImportEvidenceFingerprint,
        masteryImportEvidenceFingerprint = masteryImportEvidenceFingerprint,
        cutoverIntentFingerprint = cutoverIntentFingerprint,
        fenceFingerprint = fenceFingerprint,
    ).also { fence ->
        check(fence.hasValidFingerprint()) {
            "Persisted mastery cutover fence fingerprint is invalid"
        }
    }
}

private fun LearnerMasteryAuthorityCutoverCompletionReceipt.toEntity():
    LearnerMasteryCutoverCompletionReceiptEntity =
    LearnerMasteryCutoverCompletionReceiptEntity(
        singletonKey = LEARNER_MASTERY_CUTOVER_SINGLETON_KEY,
        cutoverGeneration = cutoverGeneration,
        cutoverIntentFingerprint = cutoverIntentFingerprint,
        authorityFenceFingerprint = authorityFenceFingerprint,
        learnerId = learnerId,
        sourceGeneration = sourceGeneration,
        migrationLedgerCanonicalDigest = migrationLedgerCanonicalDigest,
        ledgerBindingFingerprint = ledgerBindingFingerprint,
        receiptFingerprint = receiptFingerprint,
    )

private fun LearnerMasteryCutoverCompletionReceiptEntity.toDomain():
    LearnerMasteryAuthorityCutoverCompletionReceipt {
    check(singletonKey == LEARNER_MASTERY_CUTOVER_SINGLETON_KEY) {
        "Mastery cutover completion receipt has an invalid singleton key"
    }
    return LearnerMasteryAuthorityCutoverCompletionReceipt(
        cutoverGeneration = cutoverGeneration,
        cutoverIntentFingerprint = cutoverIntentFingerprint,
        authorityFenceFingerprint = authorityFenceFingerprint,
        learnerId = learnerId,
        sourceGeneration = sourceGeneration,
        migrationLedgerCanonicalDigest = migrationLedgerCanonicalDigest,
        ledgerBindingFingerprint = ledgerBindingFingerprint,
        receiptFingerprint = receiptFingerprint,
    ).also { receipt ->
        check(receipt.hasValidFingerprint()) {
            "Persisted mastery cutover completion receipt fingerprint is invalid"
        }
    }
}
