package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSourcePort
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogActivationReceipt
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgePackManifest
import com.tingyun.smartmistakebook.core.model.CanonicalSha256

/**
 * The concrete production prefix that can be proved while legacy student and mastery writes remain
 * active. Student and mastery stages are intentionally absent.
 */
internal object ProductionAuthorityCutoverPrefixFactory {
    fun create(
        layout: ThreeAuthorityDatabaseLayout,
        legacySource: LegacyAuthorityMigrationSourcePort,
        knowledgeCatalog: HighSchoolKnowledgeCatalog,
        knowledgeActivation: KnowledgeCatalogActivationReceipt,
        knowledgeCutoverEligible: Boolean,
        journal: AuthorityCutoverJournal,
        clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0L) },
    ): VerifiedAuthorityCutoverPrefixCoordinator =
        VerifiedAuthorityCutoverPrefixCoordinator(
            layout = layout,
            steps =
                createSteps(
                    layout = layout,
                    legacySource = legacySource,
                    knowledgeCatalog = knowledgeCatalog,
                    knowledgeActivation = knowledgeActivation,
                    knowledgeCutoverEligible = knowledgeCutoverEligible,
                    clock = clock,
                ),
            journal = journal,
        )

    /**
     * The exact, concrete prefix reused by the terminal writer.
     *
     * Returning verified step implementations avoids rebuilding knowledge receipts from copied
     * fields. No journal, database, DAO, or terminal-barrier capability is exposed here.
     */
    fun createSteps(
        layout: ThreeAuthorityDatabaseLayout,
        legacySource: LegacyAuthorityMigrationSourcePort,
        knowledgeCatalog: HighSchoolKnowledgeCatalog,
        knowledgeActivation: KnowledgeCatalogActivationReceipt,
        knowledgeCutoverEligible: Boolean,
        clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0L) },
    ): List<AuthorityCutoverStep> =
        buildList {
            add(
                LegacySchemaReadyStep(
                    layout = layout,
                    source = legacySource,
                    clock = clock,
                ),
            )
            if (knowledgeCutoverEligible) {
                add(
                    KnowledgeCatalogStep(
                        stage =
                            ThreeAuthorityCutoverStage.KNOWLEDGE_PACKAGE_INSTALLED,
                        layout = layout,
                        catalog = knowledgeCatalog,
                        activation = knowledgeActivation,
                        clock = clock,
                    ),
                )
                add(
                    KnowledgeCatalogStep(
                        stage =
                            ThreeAuthorityCutoverStage.KNOWLEDGE_PACKAGE_VERIFIED,
                        layout = layout,
                        catalog = knowledgeCatalog,
                        activation = knowledgeActivation,
                        clock = clock,
                    ),
                )
                add(
                    KnowledgeCatalogStep(
                        stage =
                            ThreeAuthorityCutoverStage.KNOWLEDGE_RUNTIME_READ_ONLY,
                        layout = layout,
                        catalog = knowledgeCatalog,
                        activation = knowledgeActivation,
                        clock = clock,
                    ),
                )
            }
        }
}

private data class ConcreteAuthorityStageState(
    val migratedRecordCount: Long,
    val sourceCheckpoint: String,
    val destinationFingerprint: String,
)

private class LegacySchemaReadyStep(
    private val layout: ThreeAuthorityDatabaseLayout,
    private val source: LegacyAuthorityMigrationSourcePort,
    private val clock: () -> Long,
) : AuthorityCutoverStep {
    override val stage: ThreeAuthorityCutoverStage =
        ThreeAuthorityCutoverStage.LEGACY_SCHEMA_READY

    override suspend fun migrate(previous: AuthorityStageReceipt?): AuthorityStageReceipt {
        check(previous == null) { "Legacy schema must be the first cutover stage" }
        val state = readState()
        return AuthorityStageReceipt.create(
            stage = stage,
            targetDatabaseName = layout.cutoverStorageNameFor(stage.storageTarget),
            migratedRecordCount = state.migratedRecordCount,
            sourceCheckpoint = state.sourceCheckpoint,
            destinationFingerprint = state.destinationFingerprint,
            completedAtEpochMillis = clock().coerceAtLeast(0L),
            previousReceiptFingerprint = null,
        )
    }

    override suspend fun verify(receipt: AuthorityStageReceipt): AuthorityStageVerification {
        check(receipt.stage == stage)
        val current = readState()
        check(receipt.migratedRecordCount == current.migratedRecordCount)
        check(receipt.sourceCheckpoint == current.sourceCheckpoint)
        check(receipt.destinationFingerprint == current.destinationFingerprint)
        return AuthorityStageVerification(
            receiptFingerprint = receipt.receiptFingerprint,
            destinationFingerprint = current.destinationFingerprint,
        )
    }

    private suspend fun readState(): ConcreteAuthorityStageState {
        val snapshot = source.readLegacyAuthorityMigrationSnapshot(LEGACY_SCHEMA_PROBE_LEARNER)
        return ConcreteAuthorityStageState(
            migratedRecordCount = 0L,
            sourceCheckpoint = "schema:${snapshot.schemaVersion}",
            destinationFingerprint = snapshot.legacySchemaCanonicalFingerprint,
        )
    }
}

private class KnowledgeCatalogStep(
    override val stage: ThreeAuthorityCutoverStage,
    private val layout: ThreeAuthorityDatabaseLayout,
    private val catalog: HighSchoolKnowledgeCatalog,
    private val activation: KnowledgeCatalogActivationReceipt,
    private val clock: () -> Long,
) : AuthorityCutoverStep {
    init {
        require(
            stage == ThreeAuthorityCutoverStage.KNOWLEDGE_PACKAGE_INSTALLED ||
                stage == ThreeAuthorityCutoverStage.KNOWLEDGE_PACKAGE_VERIFIED ||
                stage == ThreeAuthorityCutoverStage.KNOWLEDGE_RUNTIME_READ_ONLY,
        )
    }

    override suspend fun migrate(previous: AuthorityStageReceipt?): AuthorityStageReceipt {
        checkNotNull(previous) { "Knowledge cutover stages require their predecessor" }
        val state = readState()
        return AuthorityStageReceipt.create(
            stage = stage,
            targetDatabaseName = layout.cutoverStorageNameFor(stage.storageTarget),
            migratedRecordCount = state.migratedRecordCount,
            sourceCheckpoint = state.sourceCheckpoint,
            destinationFingerprint = state.destinationFingerprint,
            completedAtEpochMillis = clock().coerceAtLeast(0L),
            previousReceiptFingerprint = previous.receiptFingerprint,
        )
    }

    override suspend fun verify(receipt: AuthorityStageReceipt): AuthorityStageVerification {
        check(receipt.stage == stage)
        val current = readState()
        check(receipt.migratedRecordCount == current.migratedRecordCount)
        check(receipt.sourceCheckpoint == current.sourceCheckpoint)
        check(receipt.destinationFingerprint == current.destinationFingerprint)
        return AuthorityStageVerification(
            receiptFingerprint = receipt.receiptFingerprint,
            destinationFingerprint = current.destinationFingerprint,
        )
    }

    private suspend fun readState(): ConcreteAuthorityStageState {
        val manifest = catalog.readManifest()
        manifest.requireMatches(activation)
        return ConcreteAuthorityStageState(
            migratedRecordCount =
                when (stage) {
                    ThreeAuthorityCutoverStage.KNOWLEDGE_PACKAGE_INSTALLED ->
                        manifest.nodeCount.toLong() +
                            manifest.relationCount +
                            manifest.materialCount

                    ThreeAuthorityCutoverStage.KNOWLEDGE_PACKAGE_VERIFIED,
                    ThreeAuthorityCutoverStage.KNOWLEDGE_RUNTIME_READ_ONLY,
                    -> 0L

                    else -> error("Unsupported knowledge cutover stage")
                },
            sourceCheckpoint =
                "generation:${activation.generation}:" +
                    "pack:${manifest.packId}:" +
                    "version:${manifest.knowledgePackVersion}",
            destinationFingerprint = manifest.stageFingerprint(stage, activation),
        )
    }
}

private fun KnowledgePackManifest.requireMatches(
    activation: KnowledgeCatalogActivationReceipt,
) {
    require(packId == activation.packId) {
        "Knowledge manifest and activation pack ids differ"
    }
    require(knowledgePackVersion == activation.knowledgePackVersion) {
        "Knowledge manifest and activation versions differ"
    }
    require(taxonomyVersion == activation.taxonomyVersion) {
        "Knowledge manifest and activation taxonomy versions differ"
    }
    require(contentFingerprint == activation.manifestFingerprint) {
        "Knowledge manifest content is not the activated content"
    }
}

private fun KnowledgePackManifest.stageFingerprint(
    stage: ThreeAuthorityCutoverStage,
    activation: KnowledgeCatalogActivationReceipt,
): String =
    CanonicalSha256("knowledge-cutover-stage-state-v1")
        .field("stage", stage.name)
        .field("generation", activation.generation)
        .field("activatedAtEpochMillis", activation.activatedAtEpochMillis)
        .field("packId", packId)
        .field("schemaVersion", schemaVersion)
        .field("knowledgePackVersion", knowledgePackVersion)
        .field("taxonomyVersion", taxonomyVersion)
        .field("searchIndexVersion", searchIndexVersion)
        .field("contentFingerprint", contentFingerprint)
        .field("nodeCount", nodeCount)
        .field("sourceCount", sourceCount)
        .field("relationCount", relationCount)
        .field("materialCount", materialCount)
        .field("searchFeatureCount", searchFeatureCount)
        .finish()

private const val LEGACY_SCHEMA_PROBE_LEARNER = "legacy-schema-probe"
