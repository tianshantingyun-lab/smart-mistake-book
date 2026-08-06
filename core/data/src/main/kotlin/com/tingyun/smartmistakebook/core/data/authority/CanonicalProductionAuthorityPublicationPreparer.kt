package com.tingyun.smartmistakebook.core.data.authority

import android.content.Context
import com.tingyun.smartmistakebook.core.data.knowledge.ProductionHighSchoolKnowledgeCatalogBootstrap
import com.tingyun.smartmistakebook.core.data.production.assembleCompleteProductionCapabilities
import com.tingyun.smartmistakebook.core.database.LegacySessionDatabaseOwner
import com.tingyun.smartmistakebook.core.database.LegacySessionDatabaseOwnerFactory
import com.tingyun.smartmistakebook.core.database.ProductionLegacyBarrierHandoff
import com.tingyun.smartmistakebook.core.database.ProductionTerminalMigrationWriterSessionCreation
import com.tingyun.smartmistakebook.core.database.ProductionTerminalMigrationWriterSessionFactory
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess.openLearnerMasteryCutoverControlOwner
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess.openLearnerMasteryLegacyMigrationOwner
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.openStudentMistakeCutoverControlOwner
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.openStudentMistakeMigrationOwner
import kotlinx.coroutines.CancellationException

/** Canonical fail-closed terminal cutover and current-generation publication pipeline. */
internal class CanonicalProductionAuthorityPublicationPreparer(
    context: Context,
    private val modelExecutionLeaseProvider: ProductionModelExecutionLeaseProvider,
) : ProductionAuthorityPublicationPreparer {
    private val canonicalContext = context.applicationContext ?: context

    override suspend fun prepare(): ProductionAuthorityPublicationPreparation {
        var legacyOwner: LegacySessionDatabaseOwner? = null
        try {
            legacyOwner = LegacySessionDatabaseOwnerFactory.open(canonicalContext)
            val source = legacyOwner.cutoverMigrationProof()
            val layout = ThreeAuthorityDatabaseLayout()
            var receipts = source.readOrderedAuthorityReceipts()
            var generation =
                PersistentProductionCutoverGenerationResolver.resolve(layout, receipts)

            val knowledge =
                try {
                    ProductionHighSchoolKnowledgeCatalogBootstrap.openActivatedCatalog(
                        context = canonicalContext,
                        proofIssuer = KnowledgeReferenceProofAuthority.create().issuer,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return blocked(
                        ProductionAuthorityStartupBlockCode
                            .PRODUCTION_KNOWLEDGE_UNAVAILABLE,
                    )
                }
            try {
                if (
                    !knowledge.productionCutoverEligible ||
                    knowledge.productionActivationWitness == null
                ) {
                    return blocked(
                        ProductionAuthorityStartupBlockCode.INVALID_PRODUCTION_KNOWLEDGE,
                    )
                }

                val migrationResult =
                    prepareOrVerifyTerminalJournal(
                        layout = layout,
                        source = source,
                        receipts = receipts,
                        generation = generation,
                        knowledge = knowledge,
                    )
                if (migrationResult != null) return blocked(migrationResult)
            } finally {
                knowledge.catalog.close()
            }

            receipts = source.readOrderedAuthorityReceipts()
            PersistentProductionCutoverGenerationResolver.requirePersisted(
                layout = layout,
                receipts = receipts,
                expectedGeneration = generation,
            )
            check(
                receipts.map(AuthorityStageReceipt::stage) ==
                    ThreeAuthorityCutoverStage.legacyJournalPrefix,
            ) {
                "Production terminal migration did not persist all authority stages"
            }
            generation =
                PersistentProductionCutoverGenerationResolver.resolve(layout, receipts)

            val ready =
                when (
                    val terminal =
                        recoverTerminalReady(
                            layout = layout,
                            source = source,
                            receipts = receipts,
                            generation = generation,
                        )
                ) {
                    is ThreeAuthorityProductionStartupState.Blocked ->
                        return blocked(terminal.reason.toPublicBlockCode())
                    is ThreeAuthorityProductionStartupState.Ready -> terminal
                }

            val runtimeClaim =
                try {
                    LocalLearningAuthorityRuntime.openCurrentLocalLearnerForProduction(
                        context = canonicalContext,
                        tutorSessionCapability = legacyOwner.tutorSessions(),
                    )
                } catch (failure: Throwable) {
                    ready.revokeUnclaimedProof()
                    throw failure
                }
            val generationBinding =
                try {
                    runtimeClaim.use { claim ->
                        GenerationBoundLearningAuthorityRuntime.bind(
                            context = canonicalContext,
                            ready = ready,
                            runtimeClaim = claim,
                            legacyProofSource = source,
                        )
                    }
                } catch (failure: Throwable) {
                    ready.revokeUnclaimedProof()
                    throw failure
                }

            var executionLease: com.tingyun.smartmistakebook.core.domain.ConfiguredModelExecutionLease? =
                null
            try {
                val acquiredExecutionLease = modelExecutionLeaseProvider.acquire()
                executionLease = acquiredExecutionLease
                val ownerForPublication = checkNotNull(legacyOwner)
                legacyOwner = null
                val ports =
                    CurrentGenerationProductionOwnerPortFactory.open(
                        context = canonicalContext,
                        generationBinding = generationBinding,
                        sessionOwner = ownerForPublication,
                        modelExecutionLease = acquiredExecutionLease,
                    )
                executionLease = null
                generationBinding.close()
                return ProductionAuthorityPublicationPreparation.Complete(
                    assembleCompleteProductionCapabilities(ports),
                )
            } catch (failure: Throwable) {
                executionLease?.closeAfterPreparationFailure(failure)
                generationBinding.closeAfterPreparationFailure(failure)
                throw failure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return blocked(ProductionAuthorityStartupBlockCode.TERMINAL_PROOF_INCOMPLETE)
        } finally {
            legacyOwner?.close()
        }
    }

    private suspend fun prepareOrVerifyTerminalJournal(
        layout: ThreeAuthorityDatabaseLayout,
        source: com.tingyun.smartmistakebook.core.database.TrustedLegacyCutoverMigrationDatabaseCapability,
        receipts: List<AuthorityStageReceipt>,
        generation: Long,
        knowledge: ActivatedHighSchoolKnowledgeCatalog,
    ): ProductionAuthorityStartupBlockCode? {
        val studentInspection =
            openStudentMistakeCutoverControlOwner(canonicalContext)
                .asStudentLegacyFenceInspectionPort()
        val masteryInspection =
            try {
                openLearnerMasteryCutoverControlOwner(
                    canonicalContext,
                    LOCAL_LEARNER_ID,
                ).asMasteryLegacyFenceInspectionPort()
            } catch (failure: Throwable) {
                studentInspection.closeAfterPreparationFailure(failure)
                throw failure
            }
        try {
            val fenceControl =
                ThreeAuthorityLegacyWriteFenceControl.create(
                    student = studentInspection,
                    mastery = masteryInspection,
                )
            val fenceState = fenceControl.readLegacyWriteFenceState()
            val terminalJournalComplete =
                receipts.map(AuthorityStageReceipt::stage) ==
                    ThreeAuthorityCutoverStage.legacyJournalPrefix
            if (fenceState == LegacyWriteFenceState.CONFLICT) {
                return ProductionAuthorityStartupBlockCode.LEGACY_AUTHORITY_CONFLICT
            }
            if (fenceState == LegacyWriteFenceState.FENCED) {
                if (!terminalJournalComplete) {
                    return ProductionAuthorityStartupBlockCode.TERMINAL_PROOF_INCOMPLETE
                }
                verifyExistingTerminalJournalCurrentPolicy(
                    context = canonicalContext,
                    layout = layout,
                    legacySource = source,
                    journal = source,
                    receipts = receipts,
                    cutoverGeneration = generation,
                )
                return null
            }

            val migrationGate =
                ThreeAuthorityProductionStartupGate.migrationOnly(
                    legacyWriteFenceControl = fenceControl,
                    knowledge = KnowledgeDatabaseActivationWitnessReader(canonicalContext),
                )
            return when (
                migrationGate.runLegacyMigrationWriterIfPermitted {
                    openTerminalMigrationSession(
                        layout = layout,
                        source = source,
                        knowledge = knowledge,
                        generation = generation,
                    )
                }
            ) {
                LegacyMigrationWriterRunResult.COMPLETED -> null
                LegacyMigrationWriterRunResult.DENIED_NO_PRODUCTION_KNOWLEDGE ->
                    ProductionAuthorityStartupBlockCode.MISSING_PRODUCTION_KNOWLEDGE
                LegacyMigrationWriterRunResult.DENIED_INVALID_PRODUCTION_KNOWLEDGE ->
                    ProductionAuthorityStartupBlockCode.INVALID_PRODUCTION_KNOWLEDGE
                LegacyMigrationWriterRunResult.DENIED_KNOWLEDGE_VERIFICATION_FAILED ->
                    ProductionAuthorityStartupBlockCode.PRODUCTION_KNOWLEDGE_UNAVAILABLE
                LegacyMigrationWriterRunResult.DENIED_FENCED,
                LegacyMigrationWriterRunResult.DENIED_CONFLICT,
                -> ProductionAuthorityStartupBlockCode.LEGACY_AUTHORITY_CONFLICT
            }
        } finally {
            closePreparationResources(masteryInspection, studentInspection)
        }
    }

    private fun openTerminalMigrationSession(
        layout: ThreeAuthorityDatabaseLayout,
        source: com.tingyun.smartmistakebook.core.database.TrustedLegacyCutoverMigrationDatabaseCapability,
        knowledge: ActivatedHighSchoolKnowledgeCatalog,
        generation: Long,
    ): LegacyMigrationWriterSession {
        val resources = mutableListOf<AutoCloseable>()
        var transferred = false
        try {
            val studentMigration =
                openStudentMistakeMigrationOwner(canonicalContext).also(resources::add)
            val studentCutover =
                openStudentMistakeCutoverControlOwner(canonicalContext).also(resources::add)
            val masteryMigration =
                openLearnerMasteryLegacyMigrationOwner(
                    canonicalContext,
                    LOCAL_LEARNER_ID,
                ).also(resources::add)
            val masteryCutover =
                openLearnerMasteryCutoverControlOwner(
                    canonicalContext,
                    LOCAL_LEARNER_ID,
                ).also(resources::add)
            val masteryAttestation =
                ProductionLearnerMasteryTerminalAuthorityAttestationOwner(
                    context = canonicalContext,
                    layout = layout,
                    legacySource = source,
                    journal = source,
                ).also(resources::add)
            val studentMigrator =
                TerminalStudentAuthorityMigrator(
                    legacySource = source,
                    targetMigration = studentMigration,
                    targetCutover = studentCutover,
                    learnerId = LOCAL_LEARNER_ID,
                    documentMapper =
                        LegacyStudentDocumentMapper(
                            learnerId = LOCAL_LEARNER_ID,
                            assetUriResolver =
                                productionLegacyStudentAssetUriResolver(
                                    canonicalContext,
                                ),
                        ),
                    cutoverGeneration = generation,
                )
            val masteryMigrator =
                TerminalLearnerMasteryAuthorityMigrator(
                    legacySource = source,
                    targetMigration = masteryMigration,
                    targetCutover = masteryCutover,
                    learnerId = LOCAL_LEARNER_ID,
                    cutoverGeneration = generation,
                )
            transferred = true
            val creation =
                ProductionTerminalMigrationWriterSessionFactory.open(
                    context = canonicalContext,
                    layout = layout,
                    legacySource = source,
                    knowledgeCatalog = knowledge.catalog,
                    knowledgeActivation = knowledge.activation,
                    cutoverGeneration = generation,
                    studentMigrator = studentMigrator,
                    masteryMigrator = masteryMigrator,
                    postImportOperations = masteryAttestation.operations(),
                    ownedResources = resources,
                )
            return when (creation) {
                is ProductionTerminalMigrationWriterSessionCreation.Ready -> creation.session
                is ProductionTerminalMigrationWriterSessionCreation.Blocked ->
                    error("Production terminal writer has incomplete owner capabilities")
            }
        } finally {
            if (!transferred) closePreparationResources(*resources.asReversed().toTypedArray())
        }
    }

    private suspend fun recoverTerminalReady(
        layout: ThreeAuthorityDatabaseLayout,
        source: com.tingyun.smartmistakebook.core.database.TrustedLegacyCutoverMigrationDatabaseCapability,
        receipts: List<AuthorityStageReceipt>,
        generation: Long,
    ): ThreeAuthorityProductionStartupState {
        val prefix = receipts[VERIFIED_PREFIX_STAGE_COUNT - 1].receiptFingerprint
        val student =
            StudentAuthorityCutoverControlAdapter(
                target = openStudentMistakeCutoverControlOwner(canonicalContext),
                legacySource = source,
                learnerId = LOCAL_LEARNER_ID,
                legacyPrefixReceiptFingerprint = prefix,
                cutoverGeneration = generation,
            )
        val mastery =
            try {
                LearnerMasteryAuthorityCutoverControlAdapter(
                    target =
                        openLearnerMasteryCutoverControlOwner(
                            canonicalContext,
                            LOCAL_LEARNER_ID,
                        ),
                    legacySource = source,
                    learnerId = LOCAL_LEARNER_ID,
                    legacyPrefixReceiptFingerprint = prefix,
                    cutoverGeneration = generation,
                )
            } catch (failure: Throwable) {
                student.closeAfterPreparationFailure(failure)
                throw failure
            }
        val barrier =
            try {
                ProductionLegacyBarrierHandoff.open(canonicalContext)
            } catch (failure: Throwable) {
                closePreparationResourcesAfterFailure(failure, mastery, student)
                throw failure
            }
        var result: ThreeAuthorityProductionStartupState? = null
        var failure: Throwable? = null
        try {
            val fenceControl =
                ThreeAuthorityLegacyWriteFenceControl.create(student, mastery)
            val knowledge = KnowledgeDatabaseActivationWitnessReader(canonicalContext)
            val recovered =
                ThreeAuthorityProductionStartupGate(
                    legacyWriteFenceControl = fenceControl,
                    knowledge = knowledge,
                    terminalProofCoordinator =
                        ThreeAuthorityCutoverProofCoordinator(
                            legacyWriteFenceControl = fenceControl,
                            knowledge = knowledge,
                        ),
                    legacyBusinessWriteBarrierOwner = barrier.owner,
                ).recoverOrBlock()
            result = recovered
            return recovered
        } catch (ownerFailure: Throwable) {
            failure = ownerFailure
            throw ownerFailure
        } finally {
            try {
                closePreparationResources(barrier, mastery, student)
            } catch (closeFailure: Throwable) {
                val ownerFailure = failure
                if (ownerFailure == null) {
                    (result as? ThreeAuthorityProductionStartupState.Ready)
                        ?.revokeUnclaimedProof()
                    throw closeFailure
                }
                ownerFailure.addSuppressed(closeFailure)
            }
        }
    }
}

private fun blocked(
    code: ProductionAuthorityStartupBlockCode,
): ProductionAuthorityPublicationPreparation.Blocked =
    ProductionAuthorityPublicationPreparation.Blocked(code)

private fun ThreeAuthorityStartupBlockReason.toPublicBlockCode():
    ProductionAuthorityStartupBlockCode =
    when (this) {
        ThreeAuthorityStartupBlockReason.MISSING_PRODUCTION_KNOWLEDGE ->
            ProductionAuthorityStartupBlockCode.MISSING_PRODUCTION_KNOWLEDGE
        ThreeAuthorityStartupBlockReason.INVALID_PRODUCTION_KNOWLEDGE ->
            ProductionAuthorityStartupBlockCode.INVALID_PRODUCTION_KNOWLEDGE
        ThreeAuthorityStartupBlockReason.KNOWLEDGE_VERIFICATION_FAILED ->
            ProductionAuthorityStartupBlockCode.PRODUCTION_KNOWLEDGE_UNAVAILABLE
        ThreeAuthorityStartupBlockReason.LEGACY_FENCE_CONFLICT,
        ThreeAuthorityStartupBlockReason.LEGACY_BUSINESS_WRITE_BARRIER_CONFLICT,
        ThreeAuthorityStartupBlockReason.POST_VERIFICATION_FENCE_CONFLICT,
        -> ProductionAuthorityStartupBlockCode.LEGACY_AUTHORITY_CONFLICT
        ThreeAuthorityStartupBlockReason.TERMINAL_PROOF_INCOMPLETE_OR_INVALID ->
            ProductionAuthorityStartupBlockCode.TERMINAL_PROOF_INCOMPLETE
    }

private fun AutoCloseable.closeAfterPreparationFailure(owner: Throwable) {
    try {
        close()
    } catch (closeFailure: Throwable) {
        owner.addSuppressed(closeFailure)
    }
}

private fun closePreparationResourcesAfterFailure(
    owner: Throwable,
    vararg resources: AutoCloseable,
) {
    try {
        closePreparationResources(*resources)
    } catch (closeFailure: Throwable) {
        owner.addSuppressed(closeFailure)
    }
}

private fun closePreparationResources(vararg resources: AutoCloseable) {
    var failure: Throwable? = null
    resources.forEach { resource ->
        try {
            resource.close()
        } catch (closeFailure: Throwable) {
            val first = failure
            if (first == null) {
                failure = closeFailure
            } else {
                first.addSuppressed(closeFailure)
            }
        }
    }
    failure?.let { throw it }
}

private const val VERIFIED_PREFIX_STAGE_COUNT = 4
