package com.tingyun.smartmistakebook.core.data.authority

import android.content.Context
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverJournalReadPort
import com.tingyun.smartmistakebook.core.database.LegacyBusinessWriteBarrierOwnerPort
import com.tingyun.smartmistakebook.core.database.LegacyBusinessWriteBarrierOwnerResult
import com.tingyun.smartmistakebook.core.database.LegacyBusinessWriteBarrierState
import com.tingyun.smartmistakebook.core.database.TrustedLegacyCutoverMigrationDatabaseCapability
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess.openLearnerMasteryCutoverControlOwner
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.openStudentMistakeCutoverControlOwner

internal enum class ThreeAuthorityLegacyBarrierFinalization {
    FRESH_EMPTY,
    ACTIVATED,
    REPLAYED,
}

/**
 * Owner boundary between the unforgeable three-authority fence and the irreversible legacy SQL
 * barrier. Only a verified fence can authorize this operation.
 */
internal fun interface ThreeAuthorityLegacyBusinessWriteBarrierOwner {
    suspend fun finalizeAfterVerifiedFence(
        proof: VerifiedThreeAuthorityFence,
    ): ThreeAuthorityLegacyBarrierFinalization
}

internal class DatabaseThreeAuthorityLegacyBusinessWriteBarrierOwner(
    private val barrier: LegacyBusinessWriteBarrierOwnerPort,
    private val journal: LegacyAuthorityCutoverJournalReadPort,
) : ThreeAuthorityLegacyBusinessWriteBarrierOwner {
    override suspend fun finalizeAfterVerifiedFence(
        proof: VerifiedThreeAuthorityFence,
    ): ThreeAuthorityLegacyBarrierFinalization {
        VerifiedThreeAuthorityFence.Owner.requireLive(proof)
        check(proof.cutoverGeneration > 0L)
        check(proof.proofFingerprint.matches(BARRIER_SHA_256))

        if (barrier.readState() == LegacyBusinessWriteBarrierState.FRESH_EMPTY) {
            return ThreeAuthorityLegacyBarrierFinalization.FRESH_EMPTY
        }

        val receipts = journal.readOrderedAuthorityReceipts()
        check(
            receipts.map(AuthorityStageReceipt::stage) ==
                ThreeAuthorityCutoverStage.legacyJournalPrefix,
        ) {
            "Legacy business barrier requires the exact 12-stage terminal journal"
        }
        val layout = ThreeAuthorityDatabaseLayout()
        receipts.forEach { receipt ->
            check(
                receipt.targetDatabaseName ==
                    layout.cutoverStorageNameFor(receipt.stage.storageTarget),
            ) {
                "Legacy business barrier terminal journal targets the wrong database"
            }
        }
        val terminalReceipt = receipts.last()
        val result =
            barrier.activateTerminalCutover(terminalReceipt.receiptFingerprint)
        check(barrier.readState() == LegacyBusinessWriteBarrierState.TERMINAL_CUTOVER) {
            "Legacy business barrier did not persist terminal activation"
        }
        return when (result) {
            LegacyBusinessWriteBarrierOwnerResult.ACTIVATED ->
                ThreeAuthorityLegacyBarrierFinalization.ACTIVATED
            LegacyBusinessWriteBarrierOwnerResult.REPLAYED ->
                ThreeAuthorityLegacyBarrierFinalization.REPLAYED
        }
    }
}

internal object ThreeAuthorityLegacyBusinessWriteBarrierOwnerFactory {
    fun create(
        barrier: LegacyBusinessWriteBarrierOwnerPort,
        journal: LegacyAuthorityCutoverJournalReadPort,
    ): ThreeAuthorityLegacyBusinessWriteBarrierOwner =
        DatabaseThreeAuthorityLegacyBusinessWriteBarrierOwner(
            barrier = barrier,
            journal = journal,
        )
}

internal suspend fun reverifyCurrentGenerationFromAuditedOwners(
    canonicalContext: Context,
    legacyProofSource: TrustedLegacyCutoverMigrationDatabaseCapability,
): CurrentGenerationReverification {
    val context = canonicalContext.applicationContext ?: canonicalContext
    var studentAdapter: StudentAuthorityCutoverControlAdapter? = null
    var masteryAdapter: LearnerMasteryAuthorityCutoverControlAdapter? = null
    var ownerFailure: Throwable? = null
    try {
        val receipts = legacyProofSource.readOrderedAuthorityReceipts()
        check(
            receipts.map(AuthorityStageReceipt::stage) ==
                ThreeAuthorityCutoverStage.legacyJournalPrefix,
        ) {
            "Current-generation verification requires the exact terminal legacy journal"
        }
        val legacyPrefixFingerprint =
            checkNotNull(receipts.getOrNull(CURRENT_GENERATION_PREFIX_INDEX)) {
                "Current-generation verification has no verified legacy prefix receipt"
            }.also { receipt ->
                check(
                    receipt.stage ==
                        ThreeAuthorityCutoverStage.KNOWLEDGE_RUNTIME_READ_ONLY,
                ) {
                    "Current-generation verification found the wrong prefix boundary"
                }
            }.receiptFingerprint

        val studentTarget = openStudentMistakeCutoverControlOwner(context)
        val masteryTarget =
            try {
                openLearnerMasteryCutoverControlOwner(
                    context,
                    LOCAL_LEARNER_ID,
                )
            } catch (failure: Throwable) {
                closeCurrentGenerationResourceAfterFailure(studentTarget, failure)
                throw failure
            }
        try {
            val studentFence =
                checkNotNull(studentTarget.readCutoverFence()) {
                    "Current student authority has no terminal fence"
                }
            val masteryFence =
                checkNotNull(masteryTarget.readCutoverFence()) {
                    "Current mastery authority has no terminal fence"
                }
            check(studentFence.cutoverGeneration == masteryFence.cutoverGeneration) {
                "Current authority fences belong to different generations"
            }
            val generation = studentFence.cutoverGeneration
            studentAdapter =
                StudentAuthorityCutoverControlAdapter(
                    target = studentTarget,
                    legacySource = legacyProofSource,
                    learnerId = LOCAL_LEARNER_ID,
                    legacyPrefixReceiptFingerprint = legacyPrefixFingerprint,
                    cutoverGeneration = generation,
                )
            masteryAdapter =
                LearnerMasteryAuthorityCutoverControlAdapter(
                    target = masteryTarget,
                    legacySource = legacyProofSource,
                    learnerId = LOCAL_LEARNER_ID,
                    legacyPrefixReceiptFingerprint = legacyPrefixFingerprint,
                    cutoverGeneration = generation,
                )
        } catch (failure: Throwable) {
            if (studentAdapter == null && masteryAdapter == null) {
                closeCurrentGenerationResourceAfterFailure(studentTarget, failure)
                closeCurrentGenerationResourceAfterFailure(masteryTarget, failure)
            }
            throw failure
        }

        val student = checkNotNull(studentAdapter)
        val mastery = checkNotNull(masteryAdapter)
        val knowledge = KnowledgeDatabaseActivationWitnessReader(context)
        val fenceControl =
            ThreeAuthorityLegacyWriteFenceControl.create(
                student = student,
                mastery = mastery,
            )
        val initialKnowledge =
            checkNotNull(knowledge.readCurrentActivationWitness()) {
                "Current production knowledge activation is unavailable"
            }
        check(initialKnowledge.hasValidFingerprint()) {
            "Current production knowledge activation is invalid"
        }
        val proof =
            ThreeAuthorityCutoverProofCoordinator(
                legacyWriteFenceControl = fenceControl,
                knowledge = knowledge,
            ).recoverOrVerify()
        val freshKnowledge =
            checkNotNull(knowledge.readCurrentActivationWitness()) {
                "Current production knowledge activation is unavailable"
            }
        check(freshKnowledge.hasValidFingerprint()) {
            "Current production knowledge activation is invalid"
        }
        check(freshKnowledge == initialKnowledge) {
            "Production knowledge activation changed during current-generation verification"
        }
        return CurrentGenerationReverification.Owner.issueFromAuditedOwners(
            context,
            proof,
            freshKnowledge,
        )
    } catch (failure: Throwable) {
        ownerFailure = failure
        throw failure
    } finally {
        closeCurrentGenerationResources(
            ownerFailure,
            masteryAdapter,
            studentAdapter,
        )
    }
}

private fun closeCurrentGenerationResourceAfterFailure(
    resource: AutoCloseable,
    owner: Throwable,
) {
    try {
        resource.close()
    } catch (closeFailure: Throwable) {
        owner.addSuppressed(closeFailure)
    }
}

private fun closeCurrentGenerationResources(
    ownerFailure: Throwable?,
    vararg resources: AutoCloseable?,
) {
    var firstFailure: Throwable? = null
    resources.forEach { resource ->
        if (resource == null) return@forEach
        try {
            resource.close()
        } catch (failure: Throwable) {
            val first = firstFailure
            if (first == null) {
                firstFailure = failure
            } else {
                first.addSuppressed(failure)
            }
        }
    }
    val closeFailure = firstFailure ?: return
    if (ownerFailure == null) {
        throw closeFailure
    }
    ownerFailure.addSuppressed(closeFailure)
}

private val BARRIER_SHA_256 = Regex("[0-9a-f]{64}")
private const val CURRENT_GENERATION_PREFIX_INDEX = 3
