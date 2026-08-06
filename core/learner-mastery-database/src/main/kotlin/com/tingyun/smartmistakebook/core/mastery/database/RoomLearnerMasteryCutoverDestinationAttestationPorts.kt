package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID

internal class RoomLearnerMasteryCutoverDestinationReadSource(
    private val database: LearnerMasteryRoomDatabase,
) : LearnerMasteryCutoverDestinationReadSource {
    private val dao = database.cutoverAttestationDao()
    private val cutoverDao = database.cutoverDao()

    override suspend fun readSnapshot(
        learnerId: String,
        sourceGeneration: String,
    ): LearnerMasteryCutoverDestinationSnapshot {
        requireMasteryIdentity(learnerId, "Learner id")
        require(learnerId == LOCAL_LEARNER_ID) {
            "Mastery destination attestations are scoped to the local learner"
        }
        requireMasteryVersion(sourceGeneration, "Mastery facts-import source generation")

        val ledger =
            cutoverDao.recomputeCompletedMigrationLedger(
                learnerId = learnerId,
                sourceGeneration = sourceGeneration,
            )
        val health =
            dao.readEventBindingHealth(
                learnerId = learnerId,
                sourceGeneration = sourceGeneration,
                sourcePolicyVersion = LEARNER_MASTERY_SOURCE_POLICY_VERSION,
                admissionPolicyVersion = LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
                calibrationVersion = LEARNER_MASTERY_CALIBRATION_VERSION,
                projectionPolicyVersion =
                    LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
            )
        val schema = inspectSchema()
        val active = dao.readActiveProjectionGeneration()
        val activeGenerationId = active?.generationId ?: 0L
        val budgetReceipt =
            active?.let { generation ->
                dao.readProjectionBudgetRebuildReceipt(generation.generationId)
            }
        val invalidDirectionalBudgetRows = dao.countInvalidDirectionalBudgetRows()
        val projectionHealth =
            dao.readProjectionHealth(
                learnerId = learnerId,
                generationId = activeGenerationId,
                projectionPolicyVersion =
                    LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
                calibrationVersion = LEARNER_MASTERY_CALIBRATION_VERSION,
            )
        val ledgerFingerprint =
            ledger?.destinationCanonicalFingerprint ?: missingFingerprint("ledger")
        val immutableWatermark =
            immutableLedgerWatermarkFingerprint(
                learnerId = learnerId,
                sourceGeneration = sourceGeneration,
                ledgerFingerprint = ledgerFingerprint,
                health = health,
            )
        val destinationIdentity =
            destinationIdentityFingerprint(learnerId)
        val eventBindingsConsistent =
                ledger != null &&
                ledger.migratedObservationCount == health.migrationRecordCount &&
                health.sourceFactCount == health.candidateCount &&
                health.eventCount == health.appliedEventCount &&
                health.maxEventSequence == health.allocatedEventSequence &&
                health.conflictReceiptCount == 0L &&
                health.unresolvedReviewCount == 0L &&
                health.pendingInboxCount == 0L &&
                health.pendingOutboxCount == 0L &&
                health.invalidCandidateCount == 0L &&
                health.invalidAttributionCount == 0L &&
                health.invalidBindingCount == 0L &&
                health.invalidMigrationCount == 0L &&
                schema.immutableLedgerGuardsConsistent
        val eventBindingFingerprint =
            eventBindingSnapshotFingerprint(
                destinationIdentityFingerprint = destinationIdentity,
                immutableLedgerWatermarkFingerprint = immutableWatermark,
                health = health,
            )

        val activeGenerationFingerprint =
            active?.let(::activeGenerationCanonicalFingerprint)
                ?: missingFingerprint("active-projection-generation")
        val budgetReceiptFingerprint =
            budgetReceipt?.let(::projectionBudgetReceiptCanonicalFingerprint)
                ?: missingFingerprint("directional-budget-rebuild-receipt")
        val expectedGenerationFingerprint =
            active?.let(::expectedProjectionGenerationFingerprint)
        val activeShadowsAbsent =
            projectionHealth.activeShadowProjectionCount == 0L &&
                projectionHealth.activeShadowDigestCount == 0L &&
                projectionHealth.activeShadowPresentationBudgetCount == 0L &&
                projectionHealth.activeShadowProblemFamilyBudgetCount == 0L
        val activeShadowConsistent =
            activeShadowsAbsent ||
                (
                    projectionHealth.projectionCount ==
                        projectionHealth.activeShadowProjectionCount &&
                        projectionHealth.subjectDigestCount ==
                        projectionHealth.activeShadowDigestCount &&
                        projectionHealth.presentationBudgetCount ==
                        projectionHealth.activeShadowPresentationBudgetCount &&
                        projectionHealth.problemFamilyBudgetCount ==
                        projectionHealth.activeShadowProblemFamilyBudgetCount &&
                        projectionHealth.projectionShadowMismatchCount == 0L &&
                        projectionHealth.digestShadowMismatchCount == 0L &&
                        projectionHealth.presentationShadowMismatchCount == 0L &&
                        projectionHealth.problemFamilyShadowMismatchCount == 0L
                )
        val projectionsConsistent =
            active != null &&
                active.state == MasteryProjectionGenerationState.ACTIVE.name &&
                active.stage == MasteryProjectionRebuildStage.COMPLETE.name &&
                active.targetProjectionPolicyVersion ==
                LEARNER_MASTERY_PROJECTION_POLICY_VERSION &&
                active.targetCalibrationVersion ==
                LEARNER_MASTERY_CALIBRATION_VERSION &&
                active.leaseOwnerId == null &&
                active.leaseExpiresAtEpochMillis == null &&
                active.activatedAtEpochMillis != null &&
                active.snapshotFingerprint == expectedGenerationFingerprint &&
                active.sourceEventCount == projectionHealth.sourceEventCount &&
                active.sourceSupersessionCount ==
                projectionHealth.sourceSupersessionCount &&
                active.projectionRowCount == projectionHealth.projectionCount &&
                active.subjectDigestRowCount ==
                projectionHealth.subjectDigestCount &&
                active.presentationBudgetRowCount ==
                projectionHealth.presentationBudgetCount &&
                active.problemFamilyBudgetRowCount ==
                projectionHealth.problemFamilyBudgetCount &&
                active.budgetInputSnapshotFingerprint != null &&
                active.budgetOutputFingerprint != null &&
                budgetReceipt != null &&
                budgetReceipt.budgetPolicyVersion ==
                LEARNER_MASTERY_DIRECTIONAL_BUDGET_POLICY_VERSION &&
                budgetReceipt.algorithmVersion ==
                LEARNER_MASTERY_DIRECTIONAL_BUDGET_REBUILD_ALGORITHM_VERSION &&
                budgetReceipt.sourceEventCount == active.sourceEventCount &&
                budgetReceipt.sourceSupersessionCount ==
                active.sourceSupersessionCount &&
                budgetReceipt.inputRowCount == active.budgetInputRowCount &&
                budgetReceipt.inputSnapshotFingerprint ==
                active.budgetInputSnapshotFingerprint &&
                budgetReceipt.presentationBudgetRowCount ==
                active.presentationBudgetRowCount &&
                budgetReceipt.problemFamilyBudgetRowCount ==
                active.problemFamilyBudgetRowCount &&
                budgetReceipt.outputFingerprint == active.budgetOutputFingerprint &&
                budgetReceipt.completedAtEpochMillis == active.activatedAtEpochMillis &&
                invalidDirectionalBudgetRows == 0L &&
                projectionHealth.activeGenerationCount == 1L &&
                projectionHealth.buildingGenerationCount == 0L &&
                projectionHealth.nonActiveShadowCount == 0L &&
                activeShadowConsistent &&
                projectionHealth.staleProjectionCount == 0L &&
                projectionHealth.staleDigestCount == 0L &&
                projectionHealth.inconsistentDigestCount == 0L &&
                projectionHealth.missingDigestProjectionCount == 0L &&
                projectionHealth.projectionCompletionMarker ==
                LEARNER_MASTERY_PROJECTION_POLICY_VERSION &&
                projectionHealth.directionalBudgetCompletionMarker ==
                DIRECTIONAL_BUDGET_REBUILD_EPOCH &&
                schema.requiredIndexesConsistent
        val projectionFingerprint =
            projectionSnapshotFingerprint(
                destinationIdentityFingerprint = destinationIdentity,
                activeGenerationFingerprint = activeGenerationFingerprint,
                budgetReceiptFingerprint = budgetReceiptFingerprint,
                schemaFingerprint = schema.schemaFingerprint,
                invalidDirectionalBudgetRows = invalidDirectionalBudgetRows,
                health = projectionHealth,
            )
        return LearnerMasteryCutoverDestinationSnapshot(
            destinationIdentityFingerprint = destinationIdentity,
            ledgerDestinationFingerprint = ledgerFingerprint,
            ledgerMigratedRecordCount = ledger?.migratedObservationCount ?: 0L,
            eventBindingFingerprint = eventBindingFingerprint,
            projectionFingerprint = projectionFingerprint,
            immutableLedgerWatermarkFingerprint = immutableWatermark,
            activeProjectionGenerationFingerprint = activeGenerationFingerprint,
            activeProjectionGenerationId = activeGenerationId,
            eventBindingsConsistent = eventBindingsConsistent,
            projectionsConsistent = projectionsConsistent,
            cutoverFencePresent =
                health.cutoverFenceCount != 0L ||
                    projectionHealth.cutoverFenceCount != 0L,
            completionReceiptPresent =
                health.completionReceiptCount != 0L ||
                    projectionHealth.completionReceiptCount != 0L,
        )
    }

    override suspend fun readPage(
        phase: LearnerMasteryCutoverVerificationPhase,
        learnerId: String,
        sourceGeneration: String,
        projectionGenerationId: Long,
        afterExclusive: String?,
        limit: Int,
    ): List<LearnerMasteryCutoverVerificationRecord> {
        require(learnerId == LOCAL_LEARNER_ID) {
            "Mastery destination attestation pages are scoped to the local learner"
        }
        return when (phase) {
            LearnerMasteryCutoverVerificationPhase.SOURCE_FACTS ->
                dao.readSourceFactPage(learnerId, afterExclusive, limit).map {
                    fact ->
                    fact.toVerificationRecord()
                }

            LearnerMasteryCutoverVerificationPhase.CANDIDATES ->
                dao.readCandidatePage(
                    learnerId = learnerId,
                    sourcePolicyVersion = LEARNER_MASTERY_SOURCE_POLICY_VERSION,
                    admissionPolicyVersion =
                        LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
                    calibrationVersion = LEARNER_MASTERY_CALIBRATION_VERSION,
                    projectionPolicyVersion =
                        LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
                    afterExclusive = afterExclusive,
                    limit = limit,
                ).map { row -> row.toVerificationRecord() }

            LearnerMasteryCutoverVerificationPhase.ATTRIBUTIONS ->
                dao.readAttributionPage(learnerId, afterExclusive, limit).map {
                    row ->
                    row.toVerificationRecord()
                }

            LearnerMasteryCutoverVerificationPhase.PROBLEM_BINDINGS ->
                dao.readProblemBindingPage(learnerId, afterExclusive, limit).map {
                    row ->
                    row.toVerificationRecord()
                }

            LearnerMasteryCutoverVerificationPhase.SUPERSESSIONS ->
                dao.readSupersessionPage(learnerId, afterExclusive, limit).map {
                    row ->
                    row.toVerificationRecord()
                }

            LearnerMasteryCutoverVerificationPhase.MIGRATION_LEDGER ->
                dao.readMigrationDestinationPage(
                    learnerId = learnerId,
                    sourceGeneration = sourceGeneration,
                    afterExclusive = afterExclusive,
                    limit = limit,
                ).map { row -> row.toVerificationRecord() }

            LearnerMasteryCutoverVerificationPhase.PROJECTION_GENERATION ->
                dao.readActiveProjectionGeneration()
                    ?.takeIf {
                        it.generationId == projectionGenerationId &&
                            (afterExclusive == null ||
                                generationStableKey(it.generationId) > afterExclusive)
                    }?.let { generation ->
                        listOf(generation.toVerificationRecord())
                    }.orEmpty()

            LearnerMasteryCutoverVerificationPhase.PROJECTIONS ->
                dao.readProjectionPage(learnerId, afterExclusive, limit).map {
                    projection ->
                    projection.toVerificationRecord()
                }

            LearnerMasteryCutoverVerificationPhase.SUBJECT_DIGESTS ->
                dao.readSubjectDigestPage(learnerId, afterExclusive, limit).map {
                    digest ->
                    digest.toVerificationRecord()
                }

            LearnerMasteryCutoverVerificationPhase.PRESENTATION_BUDGETS ->
                dao.readPresentationBudgetPage(
                    learnerId,
                    afterExclusive,
                    limit,
                ).map { budget -> budget.toVerificationRecord() }

            LearnerMasteryCutoverVerificationPhase.PROBLEM_FAMILY_BUDGETS ->
                dao.readProblemFamilyBudgetPage(
                    learnerId,
                    afterExclusive,
                    limit,
                ).map { budget -> budget.toVerificationRecord() }

            LearnerMasteryCutoverVerificationPhase.SCHEMA_OBJECTS ->
                dao.readSchemaObjectPage(afterExclusive, limit).map { schemaObject ->
                    schemaObject.toVerificationRecord()
                }
        }
    }

    override fun close() {
        database.close()
    }

    private suspend fun inspectSchema(): SchemaInspection {
        val rows = dao.readAllSchemaObjects()
        check(rows.size <= MAX_SCHEMA_OBJECTS) {
            "Mastery schema object count exceeds its fixed verification budget"
        }
        val byName = rows.associateBy(LearnerMasteryCutoverSchemaObjectRow::name)
        val immutableGuardsConsistent =
            learnerMasteryImmutableLedgerTriggerDefinitions().all { expected ->
                val actual = byName[expected.name]
                actual?.type == "trigger" &&
                    actual.sql != null &&
                    canonicalizeLearnerMasterySql(actual.sql) ==
                    canonicalizeLearnerMasterySql(expected.sql)
            } &&
                REQUIRED_CALIBRATION_GUARDS.all { name ->
                    byName[name]?.type == "trigger" && byName[name]?.sql != null
                }
        val requiredIndexesConsistent =
            REQUIRED_CUTOVER_INDEXES.all { name ->
                byName[name]?.type == "index" && byName[name]?.sql != null
            }
        var rolling =
            CanonicalSha256(SCHEMA_FINGERPRINT_DOMAIN)
                .field("schemaVersion", LEARNER_MASTERY_DATABASE_VERSION)
                .finish()
        rows.sortedBy(LearnerMasteryCutoverSchemaObjectRow::stableKey).forEach { row ->
            rolling =
                CanonicalSha256(SCHEMA_FINGERPRINT_DOMAIN)
                    .field("previous", rolling)
                    .field("stableKey", row.stableKey)
                    .field("sql", canonicalizeLearnerMasterySql(checkNotNull(row.sql)))
                    .finish()
        }
        return SchemaInspection(
            schemaFingerprint = rolling,
            immutableLedgerGuardsConsistent = immutableGuardsConsistent,
            requiredIndexesConsistent = requiredIndexesConsistent,
        )
    }
}

internal object LearnerMasteryFactsImportReceiptReferenceFactory {
    fun bind(
        learnerId: String,
        cutoverGeneration: Long,
        legacyPrefixFingerprint: String,
        migratedRecordCount: Long,
        sourceGeneration: String,
        sourceCheckpoint: String,
        destinationFingerprint: String,
        receiptFingerprint: String,
        ownerKey: LearnerMasteryOwnerKey,
    ): LearnerMasteryFactsImportReceiptReference {
        require(learnerId == LOCAL_LEARNER_ID) {
            "Mastery facts-import references are scoped to the local learner"
        }
        return LearnerMasteryFactsImportReceiptReference.issue(
            ownerKey = ownerKey,
            learnerId = learnerId,
            cutoverGeneration = cutoverGeneration,
            legacyPrefixFingerprint = legacyPrefixFingerprint,
            migratedRecordCount = migratedRecordCount,
            sourceGeneration = sourceGeneration,
            sourceCheckpoint = sourceCheckpoint,
            destinationFingerprint = destinationFingerprint,
            receiptFingerprint = receiptFingerprint,
        )
    }
}

private fun MasterySourceFactEntity.toVerificationRecord():
    LearnerMasteryCutoverVerificationRecord =
    LearnerMasteryCutoverVerificationRecord(
        stableKey = sourceFactId,
        canonicalFingerprint =
            CanonicalSha256(SOURCE_FACT_RECORD_DOMAIN)
                .field("sourceFactId", sourceFactId)
                .field("learnerId", learnerId)
                .field("subject", subject)
                .field("canonicalFingerprint", canonicalFingerprint)
                .field("sourcePolicyVersion", sourcePolicyVersion)
                .field("occurredAtEpochMillis", occurredAtEpochMillis)
                .finish(),
    )

private fun LearnerMasteryCutoverCandidateRow.toVerificationRecord():
    LearnerMasteryCutoverVerificationRecord {
    check(consistent) {
        "Mastery candidate, source proof, admission, or applied-event chain is inconsistent"
    }
    return LearnerMasteryCutoverVerificationRecord(
        stableKey = stableKey,
        canonicalFingerprint =
            CanonicalSha256(CANDIDATE_RECORD_DOMAIN)
                .field("candidateFingerprint", primaryFingerprint)
                .nullableField("sourceFingerprint", sourceFingerprint)
                .nullableField("proofFingerprint", proofFingerprint)
                .nullableField("decisionFingerprint", decisionFingerprint)
                .nullableField("eventFingerprint", eventFingerprint)
                .nullableField("applicationFingerprint", applicationFingerprint)
                .finish(),
    )
}

private fun LearnerMasteryCutoverAttributionRow.toVerificationRecord():
    LearnerMasteryCutoverVerificationRecord {
    check(consistent) {
        "Mastery attribution is outside its event or authorized problem binding"
    }
    return LearnerMasteryCutoverVerificationRecord(
        stableKey = stableKey,
        canonicalFingerprint =
            CanonicalSha256(ATTRIBUTION_RECORD_DOMAIN)
                .field("parentFingerprint", primaryFingerprint)
                .field("nodeFingerprint", nodeFingerprint)
                .nullableField("auxiliaryFingerprint", auxiliaryFingerprint)
                .field("scalarOne", scalarOne)
                .field("scalarTwo", scalarTwo)
                .finish(),
    )
}

private fun LearnerMasteryCutoverBindingRow.toVerificationRecord():
    LearnerMasteryCutoverVerificationRecord {
    check(consistent) {
        "Mastery problem-binding authority is stale, orphaned, or cross-scoped"
    }
    return LearnerMasteryCutoverVerificationRecord(
        stableKey = stableKey,
        canonicalFingerprint =
            CanonicalSha256(PROBLEM_BINDING_RECORD_DOMAIN)
                .field("primaryFingerprint", primaryFingerprint)
                .field("secondaryFingerprint", secondaryFingerprint)
                .field("scalarOne", scalarOne)
                .field("scalarTwo", scalarTwo)
                .finish(),
    )
}

private fun LearnerMasteryCutoverSupersessionRow.toVerificationRecord():
    LearnerMasteryCutoverVerificationRecord {
    check(consistent) {
        "Mastery evidence supersession is outside its immutable event chain"
    }
    return LearnerMasteryCutoverVerificationRecord(
        stableKey = stableKey,
        canonicalFingerprint =
            CanonicalSha256(SUPERSESSION_RECORD_DOMAIN)
                .field("canonicalFingerprint", primaryFingerprint)
                .field("originalFingerprint", originalFingerprint)
                .field("replacementFingerprint", replacementFingerprint)
                .finish(),
    )
}

private fun LearnerMasteryCutoverMigrationRow.toVerificationRecord():
    LearnerMasteryCutoverVerificationRecord {
    check(consistent) {
        "Mastery migration destination row is outside its sealed source checkpoint"
    }
    return LearnerMasteryCutoverVerificationRecord(
        stableKey = stableKey,
        canonicalFingerprint =
            CanonicalSha256(MIGRATION_RECORD_DOMAIN)
                .field("destinationFingerprint", primaryFingerprint)
                .field("sourceFingerprint", sourceFingerprint)
                .field("candidateFingerprint", candidateFingerprint)
                .finish(),
    )
}

private fun MasteryProjectionGenerationEntity.toVerificationRecord():
    LearnerMasteryCutoverVerificationRecord =
    LearnerMasteryCutoverVerificationRecord(
        stableKey = generationStableKey(generationId),
        canonicalFingerprint = activeGenerationCanonicalFingerprint(this),
    )

private fun MasteryKnowledgeProjectionEntity.toVerificationRecord():
    LearnerMasteryCutoverVerificationRecord =
    LearnerMasteryCutoverVerificationRecord(
        stableKey =
            hexStableKey(learnerId, subject, knowledgeNodeId, taxonomyVersion),
        canonicalFingerprint =
            CanonicalSha256(PROJECTION_RECORD_DOMAIN)
                .field("learnerId", learnerId)
                .field("subject", subject)
                .field("knowledgeNodeId", knowledgeNodeId)
                .field("taxonomyVersion", taxonomyVersion)
                .field(
                    "latestEvidenceKnowledgePackVersion",
                    latestEvidenceKnowledgePackVersion,
                )
                .field(
                    "stableNodeIdentityFingerprint",
                    stableNodeIdentityFingerprint,
                )
                .field("positiveEvidenceMicros", positiveEvidenceMicros)
                .field("negativeEvidenceMicros", negativeEvidenceMicros)
                .field("masteryScoreMicros", masteryScoreMicros)
                .field("masteryState", masteryState)
                .field("trend", trend)
                .field("observationCount", observationCount)
                .field("memoryStabilityMillis", memoryStabilityMillis)
                .field("recallDueAtEpochMillis", recallDueAtEpochMillis)
                .nullableField(
                    "lastPositiveAtEpochMillis",
                    lastPositiveAtEpochMillis?.toString(),
                )
                .nullableField(
                    "lastNegativeAtEpochMillis",
                    lastNegativeAtEpochMillis?.toString(),
                )
                .field("lastEvidenceAtEpochMillis", lastEvidenceAtEpochMillis)
                .field("lastEventSequence", lastEventSequence)
                .field("lastOrderedEventId", lastOrderedEventId)
                .field("projectionPolicyVersion", projectionPolicyVersion)
                .field("evidenceQualityMicros", evidenceQualityMicros)
                .field(
                    "independentProblemFamilyCount",
                    independentProblemFamilyCount,
                )
                .field("distinctPresentationCount", distinctPresentationCount)
                .nullableField(
                    "historicalLogOddsMicros",
                    historicalLogOddsMicros?.toString(),
                )
                .nullableField(
                    "calibrationSnapshotFingerprint",
                    calibrationSnapshotFingerprint,
                )
                .nullableField("calibrationProfileId", calibrationProfileId)
                .nullableField("calibrationVersion", calibrationVersion)
                .nullableField(
                    "recallFamiliarizingAtEpochMillis",
                    recallFamiliarizingAtEpochMillis?.toString(),
                )
                .nullableField(
                    "recallReinforcementAtEpochMillis",
                    recallReinforcementAtEpochMillis?.toString(),
                )
                .finish(),
    )

private fun MasterySubjectDigestEntity.toVerificationRecord():
    LearnerMasteryCutoverVerificationRecord =
    LearnerMasteryCutoverVerificationRecord(
        stableKey = hexStableKey(learnerId, subject),
        canonicalFingerprint =
            CanonicalSha256(SUBJECT_DIGEST_RECORD_DOMAIN)
                .field("learnerId", learnerId)
                .field("subject", subject)
                .field("needsReinforcementCount", needsReinforcementCount)
                .field("familiarizingCount", familiarizingCount)
                .field("steadyCount", steadyCount)
                .field("lastEventSequence", lastEventSequence)
                .field("updatedAtEpochMillis", updatedAtEpochMillis)
                .field("projectionPolicyVersion", projectionPolicyVersion)
                .finish(),
    )

private fun MasteryPresentationNodeBudgetEntity.toVerificationRecord():
    LearnerMasteryCutoverVerificationRecord =
    LearnerMasteryCutoverVerificationRecord(
        stableKey =
            hexStableKey(
                learnerId,
                presentationId,
                subject,
                knowledgeNodeId,
                taxonomyVersion,
                direction,
            ),
        canonicalFingerprint =
            CanonicalSha256(PRESENTATION_BUDGET_RECORD_DOMAIN)
                .field("learnerId", learnerId)
                .field("presentationId", presentationId)
                .field("subject", subject)
                .field("knowledgeNodeId", knowledgeNodeId)
                .field("taxonomyVersion", taxonomyVersion)
                .field("direction", direction)
                .field(
                    "stableNodeIdentityFingerprint",
                    stableNodeIdentityFingerprint,
                )
                .field("consumedMassMicros", consumedMassMicros)
                .field("lastEventId", lastEventId)
                .field("updatedAtEpochMillis", updatedAtEpochMillis)
                .finish(),
    )

private fun MasteryProblemFamilyNodeBudgetEntity.toVerificationRecord():
    LearnerMasteryCutoverVerificationRecord =
    LearnerMasteryCutoverVerificationRecord(
        stableKey =
            hexStableKey(
                learnerId,
                problemFamilyFingerprint,
                subject,
                knowledgeNodeId,
                taxonomyVersion,
                direction,
            ),
        canonicalFingerprint =
            CanonicalSha256(PROBLEM_FAMILY_BUDGET_RECORD_DOMAIN)
                .field("learnerId", learnerId)
                .field("problemFamilyFingerprint", problemFamilyFingerprint)
                .field("subject", subject)
                .field("knowledgeNodeId", knowledgeNodeId)
                .field("taxonomyVersion", taxonomyVersion)
                .field("direction", direction)
                .field(
                    "stableNodeIdentityFingerprint",
                    stableNodeIdentityFingerprint,
                )
                .field("observationCount", observationCount)
                .field("consumedMassMicros", consumedMassMicros)
                .field("lastEventId", lastEventId)
                .field("updatedAtEpochMillis", updatedAtEpochMillis)
                .finish(),
    )

private fun LearnerMasteryCutoverSchemaObjectRow.toVerificationRecord():
    LearnerMasteryCutoverVerificationRecord =
    LearnerMasteryCutoverVerificationRecord(
        stableKey = stableKey,
        canonicalFingerprint =
            CanonicalSha256(SCHEMA_OBJECT_RECORD_DOMAIN)
                .field("type", type)
                .field("name", name)
                .field("sql", canonicalizeLearnerMasterySql(checkNotNull(sql)))
                .finish(),
    )

private fun immutableLedgerWatermarkFingerprint(
    learnerId: String,
    sourceGeneration: String,
    ledgerFingerprint: String,
    health: LearnerMasteryCutoverEventBindingHealthRow,
): String =
    CanonicalSha256(IMMUTABLE_LEDGER_WATERMARK_DOMAIN)
        .field("learnerId", learnerId)
        .field("sourceGeneration", sourceGeneration)
        .field("ledgerFingerprint", ledgerFingerprint)
        .field("migrationRecordCount", health.migrationRecordCount)
        .field("sourceFactCount", health.sourceFactCount)
        .field("candidateCount", health.candidateCount)
        .field("eventCount", health.eventCount)
        .field("eventAttributionCount", health.eventAttributionCount)
        .field("supersessionCount", health.supersessionCount)
        .field("maxEventSequence", health.maxEventSequence)
        .field("allocatedEventSequence", health.allocatedEventSequence)
        .finish()

private fun eventBindingSnapshotFingerprint(
    destinationIdentityFingerprint: String,
    immutableLedgerWatermarkFingerprint: String,
    health: LearnerMasteryCutoverEventBindingHealthRow,
): String =
    CanonicalSha256(EVENT_BINDING_SNAPSHOT_DOMAIN)
        .field("destinationIdentityFingerprint", destinationIdentityFingerprint)
        .field(
            "immutableLedgerWatermarkFingerprint",
            immutableLedgerWatermarkFingerprint,
        )
        .field("sourceFactCount", health.sourceFactCount)
        .field("sourceProofCount", health.sourceProofCount)
        .field("candidateCount", health.candidateCount)
        .field("eventCount", health.eventCount)
        .field("appliedEventCount", health.appliedEventCount)
        .field("eventAttributionCount", health.eventAttributionCount)
        .field("bindingCount", health.bindingCount)
        .field("bindingStateCount", health.bindingStateCount)
        .field("supersessionCount", health.supersessionCount)
        .field("conflictReceiptCount", health.conflictReceiptCount)
        .field("unresolvedReviewCount", health.unresolvedReviewCount)
        .field("pendingInboxCount", health.pendingInboxCount)
        .field("pendingOutboxCount", health.pendingOutboxCount)
        .field("invalidCandidateCount", health.invalidCandidateCount)
        .field("invalidAttributionCount", health.invalidAttributionCount)
        .field("invalidBindingCount", health.invalidBindingCount)
        .field("invalidMigrationCount", health.invalidMigrationCount)
        .field("cutoverFenceCount", health.cutoverFenceCount)
        .field("completionReceiptCount", health.completionReceiptCount)
        .finish()

private fun projectionSnapshotFingerprint(
    destinationIdentityFingerprint: String,
    activeGenerationFingerprint: String,
    budgetReceiptFingerprint: String,
    schemaFingerprint: String,
    invalidDirectionalBudgetRows: Long,
    health: LearnerMasteryCutoverProjectionHealthRow,
): String =
    CanonicalSha256(PROJECTION_SNAPSHOT_DOMAIN)
        .field("destinationIdentityFingerprint", destinationIdentityFingerprint)
        .field("activeGenerationFingerprint", activeGenerationFingerprint)
        .field("budgetReceiptFingerprint", budgetReceiptFingerprint)
        .field("schemaFingerprint", schemaFingerprint)
        .field("invalidDirectionalBudgetRows", invalidDirectionalBudgetRows)
        .field("activeGenerationCount", health.activeGenerationCount)
        .field("buildingGenerationCount", health.buildingGenerationCount)
        .field("sourceEventCount", health.sourceEventCount)
        .field("sourceSupersessionCount", health.sourceSupersessionCount)
        .field("projectionCount", health.projectionCount)
        .field("subjectDigestCount", health.subjectDigestCount)
        .field("presentationBudgetCount", health.presentationBudgetCount)
        .field("problemFamilyBudgetCount", health.problemFamilyBudgetCount)
        .field("activeShadowProjectionCount", health.activeShadowProjectionCount)
        .field("activeShadowDigestCount", health.activeShadowDigestCount)
        .field(
            "activeShadowPresentationBudgetCount",
            health.activeShadowPresentationBudgetCount,
        )
        .field(
            "activeShadowProblemFamilyBudgetCount",
            health.activeShadowProblemFamilyBudgetCount,
        )
        .field("nonActiveShadowCount", health.nonActiveShadowCount)
        .field(
            "projectionShadowMismatchCount",
            health.projectionShadowMismatchCount,
        )
        .field("digestShadowMismatchCount", health.digestShadowMismatchCount)
        .field(
            "presentationShadowMismatchCount",
            health.presentationShadowMismatchCount,
        )
        .field(
            "problemFamilyShadowMismatchCount",
            health.problemFamilyShadowMismatchCount,
        )
        .field("staleProjectionCount", health.staleProjectionCount)
        .field("staleDigestCount", health.staleDigestCount)
        .field("inconsistentDigestCount", health.inconsistentDigestCount)
        .field(
            "missingDigestProjectionCount",
            health.missingDigestProjectionCount,
        )
        .nullableField(
            "projectionCompletionMarker",
            health.projectionCompletionMarker,
        )
        .nullableField(
            "directionalBudgetCompletionMarker",
            health.directionalBudgetCompletionMarker,
        )
        .field("cutoverFenceCount", health.cutoverFenceCount)
        .field("completionReceiptCount", health.completionReceiptCount)
        .finish()

private fun projectionBudgetReceiptCanonicalFingerprint(
    receipt: ProjectionBudgetRebuildReceiptEntity,
): String =
    CanonicalSha256("learner-mastery-cutover-directional-budget-receipt-v1")
        .field("generationId", receipt.generationId)
        .field("budgetPolicyVersion", receipt.budgetPolicyVersion)
        .field("algorithmVersion", receipt.algorithmVersion)
        .field("sourceEventCount", receipt.sourceEventCount)
        .field("sourceSupersessionCount", receipt.sourceSupersessionCount)
        .field("inputRowCount", receipt.inputRowCount)
        .field("inputSnapshotFingerprint", receipt.inputSnapshotFingerprint)
        .field("presentationBudgetRowCount", receipt.presentationBudgetRowCount)
        .field("problemFamilyBudgetRowCount", receipt.problemFamilyBudgetRowCount)
        .nullableField(
            "lastOccurredAtEpochMillis",
            receipt.lastOccurredAtEpochMillis?.toString(),
        )
        .nullableField("lastEventId", receipt.lastEventId)
        .nullableField(
            "lastAttributionOrdinal",
            receipt.lastAttributionOrdinal?.toString(),
        )
        .nullableField("lastDirection", receipt.lastDirection)
        .field("outputFingerprint", receipt.outputFingerprint)
        .field("completedAtEpochMillis", receipt.completedAtEpochMillis)
        .finish()

private fun activeGenerationCanonicalFingerprint(
    generation: MasteryProjectionGenerationEntity,
): String =
    CanonicalSha256(ACTIVE_GENERATION_RECORD_DOMAIN)
        .field("generationId", generation.generationId)
        .field("state", generation.state)
        .field(
            "targetProjectionPolicyVersion",
            generation.targetProjectionPolicyVersion,
        )
        .field("targetCalibrationVersion", generation.targetCalibrationVersion)
        .field("sourceEventCount", generation.sourceEventCount)
        .field("sourceSupersessionCount", generation.sourceSupersessionCount)
        .field("stage", generation.stage)
        .field("cursorLearnerId", generation.cursorLearnerId)
        .field("cursorSubject", generation.cursorSubject)
        .field("cursorEventSequence", generation.cursorEventSequence)
        .field("cursorOrdinal", generation.cursorOrdinal)
        .field(
            "cursorOccurredAtEpochMillis",
            generation.cursorOccurredAtEpochMillis,
        )
        .field("cursorEventId", generation.cursorEventId)
        .field("cursorDirection", generation.cursorDirection)
        .nullableField("leaseOwnerId", generation.leaseOwnerId)
        .nullableField(
            "leaseExpiresAtEpochMillis",
            generation.leaseExpiresAtEpochMillis?.toString(),
        )
        .nullableField("snapshotFingerprint", generation.snapshotFingerprint)
        .nullableField(
            "projectionRowCount",
            generation.projectionRowCount?.toString(),
        )
        .nullableField(
            "subjectDigestRowCount",
            generation.subjectDigestRowCount?.toString(),
        )
        .nullableField(
            "presentationBudgetRowCount",
            generation.presentationBudgetRowCount?.toString(),
        )
        .nullableField(
            "problemFamilyBudgetRowCount",
            generation.problemFamilyBudgetRowCount?.toString(),
        )
        .nullableField(
            "budgetInputSnapshotFingerprint",
            generation.budgetInputSnapshotFingerprint,
        )
        .nullableField("budgetOutputFingerprint", generation.budgetOutputFingerprint)
        .field("budgetInputRowCount", generation.budgetInputRowCount)
        .field("createdAtEpochMillis", generation.createdAtEpochMillis)
        .nullableField(
            "activatedAtEpochMillis",
            generation.activatedAtEpochMillis?.toString(),
        )
        .finish()

private fun expectedProjectionGenerationFingerprint(
    generation: MasteryProjectionGenerationEntity,
): String =
    CanonicalSha256("learner-mastery-projection-generation-v2")
        .field("generationId", generation.generationId)
        .field(
            "targetProjectionPolicyVersion",
            generation.targetProjectionPolicyVersion,
        )
        .field("targetCalibrationVersion", generation.targetCalibrationVersion)
        .field("sourceEventCount", generation.sourceEventCount)
        .field("sourceSupersessionCount", generation.sourceSupersessionCount)
        .field("projectionRowCount", checkNotNull(generation.projectionRowCount))
        .field(
            "subjectDigestRowCount",
            checkNotNull(generation.subjectDigestRowCount),
        )
        .field(
            "presentationBudgetRowCount",
            checkNotNull(generation.presentationBudgetRowCount),
        )
        .field(
            "problemFamilyBudgetRowCount",
            checkNotNull(generation.problemFamilyBudgetRowCount),
        )
        .field("budgetPolicyVersion", LEARNER_MASTERY_DIRECTIONAL_BUDGET_POLICY_VERSION)
        .field(
            "budgetAlgorithmVersion",
            LEARNER_MASTERY_DIRECTIONAL_BUDGET_REBUILD_ALGORITHM_VERSION,
        )
        .field("budgetInputRowCount", generation.budgetInputRowCount)
        .field(
            "budgetInputSnapshotFingerprint",
            checkNotNull(generation.budgetInputSnapshotFingerprint),
        )
        .field(
            "budgetOutputFingerprint",
            checkNotNull(generation.budgetOutputFingerprint),
        )
        .finish()

private fun destinationIdentityFingerprint(learnerId: String): String =
    CanonicalSha256(DESTINATION_IDENTITY_DOMAIN)
        .field("databaseName", LEARNER_MASTERY_DATABASE_NAME)
        .field("schemaVersion", LEARNER_MASTERY_DATABASE_VERSION)
        .field("learnerId", learnerId)
        .field("sourcePolicyVersion", LEARNER_MASTERY_SOURCE_POLICY_VERSION)
        .field(
            "admissionPolicyVersion",
            LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
        )
        .field("calibrationVersion", LEARNER_MASTERY_CALIBRATION_VERSION)
        .field(
            "projectionPolicyVersion",
            LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
        )
        .finish()

private fun missingFingerprint(kind: String): String =
    CanonicalSha256(MISSING_FINGERPRINT_DOMAIN)
        .field("kind", kind)
        .finish()

private fun generationStableKey(generationId: Long): String =
    "generation:${generationId.toString().padStart(20, '0')}"

private fun hexStableKey(vararg values: String): String =
    values.joinToString(":") { value ->
        value.toByteArray(Charsets.UTF_8)
            .joinToString(separator = "") { byte -> "%02X".format(byte) }
    }

private data class SchemaInspection(
    val schemaFingerprint: String,
    val immutableLedgerGuardsConsistent: Boolean,
    val requiredIndexesConsistent: Boolean,
)

private val REQUIRED_CALIBRATION_GUARDS =
    setOf(
        "validate_mastery_evidence_review_case_calibration_insert",
        "validate_mastery_evidence_review_case_calibration_update",
        "validate_mastery_evidence_review_resolution_calibration_insert",
        "validate_mastery_learning_event_calibration_insert",
        "validate_mastery_learning_event_calibration_update",
        "validate_mastery_knowledge_projection_calibration_insert",
        "validate_mastery_knowledge_projection_calibration_update",
    )

private val REQUIRED_CUTOVER_INDEXES =
    setOf(
        "index_mastery_source_fact_learner_id_subject_occurred_at_epoch_millis",
        "index_mastery_observation_candidate_learner_id_subject_proposed_at_epoch_millis",
        "index_mastery_admission_receipt_learner_id_disposition_decided_at_epoch_millis",
        "index_mastery_learning_event_learner_id_event_sequence",
        "index_mastery_learning_event_attribution_subject_knowledge_node_id_taxonomy_version_event_id",
        "index_mastery_applied_event_learner_id_event_sequence",
        "index_mastery_problem_binding_authority_state_learner_id_subject",
        "index_mastery_knowledge_projection_learner_id_subject_mastery_state_last_evidence_at_epoch_millis",
        "index_mastery_knowledge_projection_learner_id_subject_recall_due_at_epoch_millis",
        "index_mastery_subject_digest_learner_id_updated_at_epoch_millis",
        "index_mastery_projection_generation_state_generation_id",
        "index_mastery_projection_shadow_generation_id_learner_id_subject",
        "index_mastery_subject_digest_shadow_generation_id_learner_id",
        "index_mastery_learning_event_directional_budget_replay",
        "index_mastery_presentation_node_budget_learner_id_subject_direction_updated_at_epoch_millis",
        "index_mastery_presentation_node_budget_stable_node_identity_fingerprint_direction",
        "index_mastery_problem_family_node_budget_learner_id_subject_direction_updated_at_epoch_millis",
        "index_mastery_problem_family_node_budget_stable_node_identity_fingerprint_direction",
        "index_mastery_presentation_node_budget_shadow_generation_id_learner_id_subject_direction",
        "index_mastery_problem_family_node_budget_shadow_generation_id_learner_id_subject_direction",
        "index_mastery_projection_budget_rebuild_receipt_input_snapshot_fingerprint",
        "index_mastery_projection_budget_rebuild_receipt_output_fingerprint",
        "index_mastery_open_response_model_evaluation_attestation_receipt_fingerprint",
        "index_mastery_open_response_evaluation_knowledge_scope_attestation_fingerprint_knowledge_node_ref_fingerprint",
        "index_mastery_open_response_dedicated_decision_accepted_event_id",
        "index_mastery_open_response_legacy_quarantine_decision_fingerprint",
        "index_mastery_open_response_legacy_quarantine_accepted_event_id_event_canonical_fingerprint",
        "index_mastery_open_response_legacy_quarantine_quarantine_fingerprint",
    )

private const val MAX_SCHEMA_OBJECTS = 512
private const val SOURCE_FACT_RECORD_DOMAIN =
    "learner-mastery-cutover-source-fact-record-v1"
private const val CANDIDATE_RECORD_DOMAIN =
    "learner-mastery-cutover-candidate-record-v1"
private const val ATTRIBUTION_RECORD_DOMAIN =
    "learner-mastery-cutover-attribution-record-v1"
private const val PROBLEM_BINDING_RECORD_DOMAIN =
    "learner-mastery-cutover-problem-binding-record-v1"
private const val SUPERSESSION_RECORD_DOMAIN =
    "learner-mastery-cutover-supersession-record-v1"
private const val MIGRATION_RECORD_DOMAIN =
    "learner-mastery-cutover-migration-record-v1"
private const val ACTIVE_GENERATION_RECORD_DOMAIN =
    "learner-mastery-cutover-active-generation-record-v2"
private const val PROJECTION_RECORD_DOMAIN =
    "learner-mastery-cutover-projection-record-v1"
private const val SUBJECT_DIGEST_RECORD_DOMAIN =
    "learner-mastery-cutover-subject-digest-record-v1"
private const val PRESENTATION_BUDGET_RECORD_DOMAIN =
    "learner-mastery-cutover-presentation-budget-record-v2"
private const val PROBLEM_FAMILY_BUDGET_RECORD_DOMAIN =
    "learner-mastery-cutover-problem-family-budget-record-v2"
private const val SCHEMA_OBJECT_RECORD_DOMAIN =
    "learner-mastery-cutover-schema-object-record-v1"
private const val SCHEMA_FINGERPRINT_DOMAIN =
    "learner-mastery-cutover-schema-fingerprint-v1"
private const val IMMUTABLE_LEDGER_WATERMARK_DOMAIN =
    "learner-mastery-cutover-immutable-ledger-watermark-v1"
private const val EVENT_BINDING_SNAPSHOT_DOMAIN =
    "learner-mastery-cutover-event-binding-snapshot-v1"
private const val PROJECTION_SNAPSHOT_DOMAIN =
    "learner-mastery-cutover-projection-snapshot-v2"
private const val DESTINATION_IDENTITY_DOMAIN =
    "learner-mastery-cutover-destination-identity-v1"
private const val MISSING_FINGERPRINT_DOMAIN =
    "learner-mastery-cutover-missing-fingerprint-v1"
