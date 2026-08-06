package com.tingyun.smartmistakebook.core.data.authority

import android.content.Context
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverJournalReadPort
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSourcePort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.openStudentTerminalAuthorityAttestationOwner

/** Read-only current-policy verification used when durable authority fences already exist. */
internal suspend fun verifyExistingTerminalJournalCurrentPolicy(
    context: Context,
    layout: ThreeAuthorityDatabaseLayout,
    legacySource: LegacyAuthorityMigrationSourcePort,
    journal: LegacyAuthorityCutoverJournalReadPort,
    receipts: List<AuthorityStageReceipt>,
    cutoverGeneration: Long,
    clock: () -> Long = System::currentTimeMillis,
) {
    requireValidAuthorityCutoverJournalPrefix(layout, receipts)
    check(
        receipts.map(AuthorityStageReceipt::stage) ==
            ThreeAuthorityCutoverStage.legacyJournalPrefix,
    ) {
        "Current-policy terminal verification requires the exact twelve-stage journal"
    }
    val prefix = receipts[VERIFIED_PREFIX_STAGE_COUNT - 1].receiptFingerprint
    val binding =
        TerminalAuthorityCutoverBinding(
            cutoverGeneration = cutoverGeneration,
            legacyPrefixReceiptFingerprint = prefix,
        )
    val studentOwner =
        openStudentTerminalAuthorityAttestationOwner(
            context,
            layout,
            clock,
        )
    val masteryOwner =
        try {
            ProductionLearnerMasteryTerminalAuthorityAttestationOwner(
                context = context,
                layout = layout,
                legacySource = legacySource,
                journal = journal,
                clock = clock,
            )
        } catch (failure: Throwable) {
            studentOwner.closeAfterPolicyFailure(failure)
            throw failure
        }
    var ownerFailure: Throwable? = null
    try {
        studentOwner.bindDocumentImportReceipt(
            binding = binding,
            receipt = receipts[STUDENT_IMPORT_STAGE_INDEX],
        )
        val operations =
            createStudentDestinationAttestationOperations(studentOwner) +
                masteryOwner.operations()
        operations.forEach { operation ->
            val receipt =
                checkNotNull(receipts.find { candidate -> candidate.stage == operation.stage })
            requireCurrentTerminalStageReceipt(
                layout = layout,
                operation = operation,
                binding = binding,
                receipt = receipt,
            )
        }
    } catch (failure: Throwable) {
        ownerFailure = failure
        throw failure
    } finally {
        var failure: Throwable? = null
        try {
            masteryOwner.close()
        } catch (closeFailure: Throwable) {
            failure = closeFailure
        }
        try {
            studentOwner.close()
        } catch (closeFailure: Throwable) {
            val first = failure
            if (first == null) {
                failure = closeFailure
            } else {
                first.addSuppressed(closeFailure)
            }
        }
        failure?.let { closeFailure ->
            val owner = ownerFailure
            if (owner == null) {
                throw closeFailure
            }
            owner.addSuppressed(closeFailure)
        }
    }
}

private fun AutoCloseable.closeAfterPolicyFailure(owner: Throwable) {
    try {
        close()
    } catch (closeFailure: Throwable) {
        owner.addSuppressed(closeFailure)
    }
}

private const val VERIFIED_PREFIX_STAGE_COUNT = 4
private const val STUDENT_IMPORT_STAGE_INDEX = 4
