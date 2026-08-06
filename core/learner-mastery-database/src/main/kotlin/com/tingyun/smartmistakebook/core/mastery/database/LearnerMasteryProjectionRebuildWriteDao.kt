package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import java.util.UUID

@Dao
internal abstract class LearnerMasteryProjectionRebuildWriteDao : LearnerMasteryProjectionRebuildDao() {
    internal open suspend fun rebuildDerivedStateIfRequired():
        MasteryProjectionRebuildChunkResult =
        prepareProjectionRebuild(nowEpochMillis = System.currentTimeMillis())

    internal open suspend fun rebuildDerivedStateChunk(): MasteryProjectionRebuildChunkResult =
        rebuildDerivedStateChunk(
            ownerId = "projection-rebuild-direct:${UUID.randomUUID()}",
            nowEpochMillis = System.currentTimeMillis(),
        )

    internal open suspend fun rebuildDerivedStateChunk(
        ownerId: String,
        nowEpochMillis: Long,
    ): MasteryProjectionRebuildChunkResult {
        require(ownerId.isNotBlank()) { "Projection rebuild owner id must not be blank" }
        require(nowEpochMillis >= 0L) { "Projection rebuild clock must not be negative" }
        val acquired =
            acquireProjectionRebuildLease(
                ownerId = ownerId,
                nowEpochMillis = nowEpochMillis,
            )
        if (
            acquired.completed ||
            acquired.blockedReason != null ||
            acquired.leaseBusy
        ) {
            return acquired
        }
        val generationId =
            checkNotNull(acquired.generationId) {
                "Acquired projection rebuild lease has no generation"
            }
        return try {
            runLeasedProjectionRebuildChunk(
                generationId = generationId,
                ownerId = ownerId,
                nowEpochMillis = nowEpochMillis,
            )
        } finally {
            releaseProjectionGenerationLease(generationId, ownerId)
        }
    }

    @Transaction
    protected open suspend fun acquireProjectionRebuildLease(
        ownerId: String,
        nowEpochMillis: Long,
    ): MasteryProjectionRebuildChunkResult {
        // A BUILDING generation is created only after full binding validation. Immutable history
        // is checked again before activation, so resuming it must not rescan the ledger per page.
        readCurrentEpochBuildingProjectionGeneration()?.let { building ->
            return claimExistingProjectionRebuildLease(
                generation = building,
                ownerId = ownerId,
                nowEpochMillis = nowEpochMillis,
            )
        }
        val prepared = prepareProjectionRebuild(nowEpochMillis)
        if (prepared.completed || prepared.blockedReason != null) {
            return prepared
        }
        val generation =
            readCurrentEpochBuildingProjectionGeneration()
                ?: ensureCurrentProjectionRebuildGeneration(
                    eventCount = countLearningEvents(),
                    supersessionCount = countLearningEvidenceSupersessions(),
                    nowEpochMillis = nowEpochMillis,
                )
        return claimExistingProjectionRebuildLease(
            generation = generation,
            ownerId = ownerId,
            nowEpochMillis = nowEpochMillis,
            prepared = prepared,
        )
    }

    private suspend fun claimExistingProjectionRebuildLease(
        generation: MasteryProjectionGenerationEntity,
        ownerId: String,
        nowEpochMillis: Long,
        prepared: MasteryProjectionRebuildChunkResult? = null,
    ): MasteryProjectionRebuildChunkResult {
        val leaseExpiresAt =
            if (nowEpochMillis > Long.MAX_VALUE - PROJECTION_REBUILD_LEASE_MILLIS) {
                Long.MAX_VALUE
            } else {
                nowEpochMillis + PROJECTION_REBUILD_LEASE_MILLIS
            }
        if (
            claimProjectionGenerationLease(
                generationId = generation.generationId,
                ownerId = ownerId,
                nowEpochMillis = nowEpochMillis,
                leaseExpiresAtEpochMillis = leaseExpiresAt,
            ) != 1
        ) {
            return prepared?.copy(
                generationId = generation.generationId,
                leaseBusy = true,
            ) ?: MasteryProjectionRebuildChunkResult(
                stage = enumValueOf(generation.stage),
                processedRowCount = 0,
                completed = false,
                generationId = generation.generationId,
                activeGenerationAvailable = readActiveProjectionGeneration() != null,
                leaseBusy = true,
            )
        }
        return prepared?.copy(
            generationId = generation.generationId,
            leaseBusy = false,
        ) ?: MasteryProjectionRebuildChunkResult(
            stage = enumValueOf(generation.stage),
            processedRowCount = 0,
            completed = false,
            generationId = generation.generationId,
            activeGenerationAvailable = readActiveProjectionGeneration() != null,
            leaseBusy = false,
        )
    }

    @Transaction
    protected open suspend fun runLeasedProjectionRebuildChunk(
        generationId: Long,
        ownerId: String,
        nowEpochMillis: Long,
    ): MasteryProjectionRebuildChunkResult {
        val renewedUntil =
            if (nowEpochMillis > Long.MAX_VALUE - PROJECTION_REBUILD_LEASE_MILLIS) {
                Long.MAX_VALUE
            } else {
                nowEpochMillis + PROJECTION_REBUILD_LEASE_MILLIS
            }
        check(
            claimProjectionGenerationLease(
                generationId = generationId,
                ownerId = ownerId,
                nowEpochMillis = nowEpochMillis,
                leaseExpiresAtEpochMillis = renewedUntil,
            ) == 1,
        ) {
            "Projection rebuild lease could not be renewed"
        }
        val leased =
            checkNotNull(readBuildingProjectionGeneration())
                .also {
                    check(
                        it.generationId == generationId &&
                            it.leaseOwnerId == ownerId &&
                            checkNotNull(it.leaseExpiresAtEpochMillis) > nowEpochMillis,
                    ) {
                        "Projection rebuild lease is missing, expired, or changed"
                    }
                }
        validateAllPersistedCalibrationBindings()?.let { blockedReason ->
            return MasteryProjectionRebuildChunkResult(
                stage = MasteryProjectionRebuildStage.RESET,
                processedRowCount = 0,
                completed = false,
                blockedReason = blockedReason,
                generationId = leased.generationId,
                activeGenerationAvailable = readActiveProjectionGeneration() != null,
            )
        }
        return when (enumValueOf<MasteryProjectionRebuildStage>(leased.stage)) {
            MasteryProjectionRebuildStage.RESET -> resetProjectionRebuild(leased)
            MasteryProjectionRebuildStage.PROJECTIONS ->
                rebuildProjectionHistoryChunk(leased)
            MasteryProjectionRebuildStage.DIMENSIONS ->
                rebuildProjectionDimensionsChunk(leased)
            MasteryProjectionRebuildStage.EVIDENCE_DIMENSIONS ->
                rebuildProjectionEvidenceDimensionsChunk(leased)
            MasteryProjectionRebuildStage.PRESENTATION_BUDGETS ->
                fingerprintPresentationBudgetsChunk(leased)
            MasteryProjectionRebuildStage.PROBLEM_FAMILY_BUDGETS ->
                fingerprintProblemFamilyBudgetsChunk(leased)
            MasteryProjectionRebuildStage.SUBJECT_DIGESTS ->
                rebuildSubjectDigestsChunk(leased)
            MasteryProjectionRebuildStage.COMPLETE ->
                completeProjectionRebuild(leased, nowEpochMillis)
        }
    }

    @Transaction
    internal open suspend fun prepareProjectionRebuild(
        nowEpochMillis: Long,
    ): MasteryProjectionRebuildChunkResult {
        require(nowEpochMillis >= 0L) { "Projection rebuild clock must not be negative" }
        var active = readActiveProjectionGeneration()
        val completionMatchesCurrentPolicy =
            readMetadata(PROJECTION_REBUILD_COMPLETED_METADATA_KEY) ==
                LEARNER_MASTERY_PROJECTION_POLICY_VERSION
        val presentationBudgetEpochCompleted =
            readMetadata(DIRECTIONAL_BUDGET_REBUILD_COMPLETED_METADATA_KEY) ==
                DIRECTIONAL_BUDGET_REBUILD_EPOCH
        val presentationBudgetEpochRequired =
            readMetadata(DIRECTIONAL_BUDGET_REBUILD_REQUIRED_METADATA_KEY) ==
                DIRECTIONAL_BUDGET_REBUILD_EPOCH &&
                !presentationBudgetEpochCompleted
        val legacyRebuildRequired =
            readMetadata(PROJECTION_REBUILD_METADATA_KEY) ==
                PROJECTION_REBUILD_REQUIRED_V2 &&
                !completionMatchesCurrentPolicy
        val explicitlyRequired = legacyRebuildRequired || presentationBudgetEpochRequired
        val readableActive = active
        if (
            !explicitlyRequired &&
            readableActive != null &&
            readableActive.targetProjectionPolicyVersion ==
            LEARNER_MASTERY_PROJECTION_POLICY_VERSION &&
            readableActive.targetCalibrationVersion == LEARNER_MASTERY_CALIBRATION_VERSION &&
            directionalBudgetRebuildReady()
        ) {
            return MasteryProjectionRebuildChunkResult(
                stage = MasteryProjectionRebuildStage.COMPLETE,
                processedRowCount = 0,
                completed = true,
                generationId = readableActive.generationId,
                activeGenerationAvailable = true,
            )
        }
        val eventCount = countLearningEvents()
        val supersessionCount = countLearningEvidenceSupersessions()
        val openResponseQuarantineCount = countOpenResponseLegacyQuarantines()
        if (active == null && eventCount == 0L && !explicitlyRequired) {
            return bootstrapEmptyDirectionalProjectionGeneration(
                supersessionCount = supersessionCount,
                nowEpochMillis = nowEpochMillis,
            )
        }
        if (
            active == null &&
            openResponseQuarantineCount == 0L &&
            canAdoptCurrentDerivedState(
                eventCount = eventCount,
                completionMatchesCurrentPolicy = completionMatchesCurrentPolicy,
            )
        ) {
            val projectionCount = countActiveProjectionRows()
            val digestCount = countActiveSubjectDigestRows()
            val generationId = Math.addExact(readMaximumProjectionGenerationId(), 1L)
            val targetPolicy =
                if (eventCount == 0L || completionMatchesCurrentPolicy) {
                    LEARNER_MASTERY_PROJECTION_POLICY_VERSION
                } else {
                    PREVIOUS_ACTIVE_PROJECTION_VERSION
                }
            val targetCalibration =
                if (eventCount == 0L || completionMatchesCurrentPolicy) {
                    LEARNER_MASTERY_CALIBRATION_VERSION
                } else {
                    PREVIOUS_ACTIVE_CALIBRATION_VERSION
                }
            val adopted =
                MasteryProjectionGenerationEntity(
                    generationId = generationId,
                    state = MasteryProjectionGenerationState.ACTIVE.name,
                    targetProjectionPolicyVersion = targetPolicy,
                    targetCalibrationVersion = targetCalibration,
                    sourceEventCount = eventCount,
                    sourceSupersessionCount = supersessionCount,
                    stage = MasteryProjectionRebuildStage.COMPLETE.name,
                    cursorLearnerId = "",
                    cursorSubject = "",
                    cursorEventSequence = -1L,
                    cursorOrdinal = -1,
                    cursorOccurredAtEpochMillis = -1L,
                    cursorEventId = "",
                    cursorDirection = "",
                    leaseOwnerId = null,
                    leaseExpiresAtEpochMillis = null,
                    snapshotFingerprint =
                        projectionGenerationFingerprint(
                            generationId = generationId,
                            targetProjectionPolicyVersion = targetPolicy,
                            targetCalibrationVersion = targetCalibration,
                            sourceEventCount = eventCount,
                            sourceSupersessionCount = supersessionCount,
                            projectionRowCount = projectionCount,
                            subjectDigestRowCount = digestCount,
                        ),
                    projectionRowCount = projectionCount,
                    subjectDigestRowCount = digestCount,
                    createdAtEpochMillis = nowEpochMillis,
                    activatedAtEpochMillis = nowEpochMillis,
            )
            insertProjectionGeneration(adopted)
            if (
                targetPolicy == LEARNER_MASTERY_PROJECTION_POLICY_VERSION &&
                targetCalibration == LEARNER_MASTERY_CALIBRATION_VERSION
            ) {
                markCurrentProjectionRebuildCompleted()
            }
            active = readActiveProjectionGeneration()
        }
        val adoptedOrExistingActive = active
        if (
            eventCount == 0L &&
            adoptedOrExistingActive != null &&
            adoptedOrExistingActive.targetProjectionPolicyVersion ==
            LEARNER_MASTERY_PROJECTION_POLICY_VERSION &&
            adoptedOrExistingActive.targetCalibrationVersion ==
            LEARNER_MASTERY_CALIBRATION_VERSION &&
            directionalBudgetRebuildReady()
        ) {
            if (
                readMetadata(DIRECTIONAL_BUDGET_REBUILD_COMPLETED_METADATA_KEY) !=
                DIRECTIONAL_BUDGET_REBUILD_EPOCH ||
                readMetadata(PROJECTION_REBUILD_COMPLETED_METADATA_KEY) !=
                LEARNER_MASTERY_PROJECTION_POLICY_VERSION
            ) {
                markCurrentProjectionRebuildCompleted()
                markPresentationFingerprintBudgetRebuildCompleted()
            }
            return MasteryProjectionRebuildChunkResult(
                stage = MasteryProjectionRebuildStage.COMPLETE,
                processedRowCount = 0,
                completed = true,
                generationId = adoptedOrExistingActive.generationId,
                activeGenerationAvailable = true,
            )
        }
        val rebuildRequired =
            explicitlyRequired ||
                active?.targetProjectionPolicyVersion !=
                LEARNER_MASTERY_PROJECTION_POLICY_VERSION ||
                active?.targetCalibrationVersion !=
                LEARNER_MASTERY_CALIBRATION_VERSION ||
                !directionalBudgetRebuildReady()
        if (!rebuildRequired) {
            return MasteryProjectionRebuildChunkResult(
                stage = MasteryProjectionRebuildStage.COMPLETE,
                processedRowCount = 0,
                completed = true,
                generationId = active?.generationId,
                activeGenerationAvailable = active != null,
            )
        }
        if (active == null) {
            val existing = readCurrentEpochBuildingProjectionGeneration()
            return MasteryProjectionRebuildChunkResult(
                stage =
                    existing?.let {
                        enumValueOf<MasteryProjectionRebuildStage>(it.stage)
                    } ?: MasteryProjectionRebuildStage.RESET,
                processedRowCount = 0,
                completed = false,
                generationId = existing?.generationId,
                activeGenerationAvailable = false,
            )
        }
        val building =
            ensureCurrentProjectionRebuildGeneration(
                eventCount = eventCount,
                supersessionCount = supersessionCount,
                nowEpochMillis = nowEpochMillis,
            )
        return MasteryProjectionRebuildChunkResult(
            stage =
                enumValueOf<MasteryProjectionRebuildStage>(
                    building.stage,
                ),
            processedRowCount = 0,
            completed = false,
            generationId = building.generationId,
            activeGenerationAvailable = true,
        )
    }

    private suspend fun readCurrentEpochBuildingProjectionGeneration():
        MasteryProjectionGenerationEntity? {
        val building = readBuildingProjectionGeneration() ?: return null
        if (
            building.targetProjectionPolicyVersion !=
            LEARNER_MASTERY_PROJECTION_POLICY_VERSION ||
            building.targetCalibrationVersion != LEARNER_MASTERY_CALIBRATION_VERSION
        ) {
            return null
        }
        return building.takeIf {
            readMetadata(
                directionalBudgetGenerationEpochMetadataKey(
                    building.generationId,
                ),
            ) == DIRECTIONAL_BUDGET_REBUILD_EPOCH
        }
    }

    /** Fresh databases can attest an empty ledger without scheduling or scanning any history. */
    private suspend fun bootstrapEmptyDirectionalProjectionGeneration(
        supersessionCount: Long,
        nowEpochMillis: Long,
    ): MasteryProjectionRebuildChunkResult {
        check(countLearningEvents() == 0L) {
            "Empty directional projection bootstrap observed a learning event"
        }
        var building =
            ensureCurrentProjectionRebuildGeneration(
                eventCount = 0L,
                supersessionCount = supersessionCount,
                nowEpochMillis = nowEpochMillis,
            )
        resetProjectionRebuild(building)
        building = checkNotNull(readCurrentEpochBuildingProjectionGeneration())
        rebuildProjectionHistoryChunk(building)
        building = checkNotNull(readCurrentEpochBuildingProjectionGeneration())
        rebuildProjectionDimensionsChunk(building)
        building = checkNotNull(readCurrentEpochBuildingProjectionGeneration())
        rebuildProjectionEvidenceDimensionsChunk(building)
        building = checkNotNull(readCurrentEpochBuildingProjectionGeneration())
        rebuildSubjectDigestsChunk(building)
        building = checkNotNull(readCurrentEpochBuildingProjectionGeneration())
        fingerprintPresentationBudgetsChunk(building)
        building = checkNotNull(readCurrentEpochBuildingProjectionGeneration())
        fingerprintProblemFamilyBudgetsChunk(building)
        building = checkNotNull(readCurrentEpochBuildingProjectionGeneration())
        return completeProjectionRebuild(building, nowEpochMillis)
    }

    private suspend fun ensureCurrentProjectionRebuildGeneration(
        eventCount: Long,
        supersessionCount: Long,
        nowEpochMillis: Long,
    ): MasteryProjectionGenerationEntity {
        retireStaleBuildingProjectionGenerations(
            targetProjectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
            targetCalibrationVersion = LEARNER_MASTERY_CALIBRATION_VERSION,
        )
        retireBuildingProjectionGenerationsOutsideEpoch(
            epoch = DIRECTIONAL_BUDGET_REBUILD_EPOCH,
        )
        readCurrentEpochBuildingProjectionGeneration()?.let { return it }
        val generationId = Math.addExact(readMaximumProjectionGenerationId(), 1L)
        val candidate =
            MasteryProjectionGenerationEntity(
                generationId = generationId,
                state = MasteryProjectionGenerationState.BUILDING.name,
                targetProjectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
                targetCalibrationVersion = LEARNER_MASTERY_CALIBRATION_VERSION,
                sourceEventCount = eventCount,
                sourceSupersessionCount = supersessionCount,
                stage = MasteryProjectionRebuildStage.RESET.name,
                cursorLearnerId = "",
                cursorSubject = "",
                cursorEventSequence = -1L,
                cursorOrdinal = -1,
                cursorOccurredAtEpochMillis = -1L,
                cursorEventId = "",
                cursorDirection = "",
                leaseOwnerId = null,
                leaseExpiresAtEpochMillis = null,
                snapshotFingerprint = null,
                projectionRowCount = null,
                subjectDigestRowCount = null,
                presentationBudgetRowCount = null,
                problemFamilyBudgetRowCount = null,
                budgetInputSnapshotFingerprint = directionalBudgetInputSeedFingerprint(),
                budgetOutputFingerprint = null,
                budgetInputRowCount = 0L,
                createdAtEpochMillis = nowEpochMillis,
                activatedAtEpochMillis = null,
            )
        check(insertProjectionGeneration(candidate) != INSERT_CONFLICT) {
            "Projection generation id was allocated concurrently"
        }
        bindProjectionGenerationToPresentationFingerprintBudgetEpoch(generationId)
        return checkNotNull(readCurrentEpochBuildingProjectionGeneration()) {
            "Projection generation was not bound to the presentation-budget epoch"
        }
    }

    private suspend fun resetProjectionRebuild(
        generation: MasteryProjectionGenerationEntity,
    ): MasteryProjectionRebuildChunkResult {
        deleteShadowProjections(generation.generationId)
        deleteShadowSubjectDigests(generation.generationId)
        deleteShadowPresentationBudgets(generation.generationId)
        deleteShadowProblemFamilyBudgets(generation.generationId)
        upsertProjectionGeneration(
            generation.copy(
                stage = MasteryProjectionRebuildStage.PROJECTIONS.name,
                cursorLearnerId = "",
                cursorSubject = "",
                cursorEventSequence = -1L,
                cursorOrdinal = -1,
                cursorOccurredAtEpochMillis = -1L,
                cursorEventId = "",
                cursorDirection = "",
                snapshotFingerprint = null,
                projectionRowCount = null,
                subjectDigestRowCount = null,
                presentationBudgetRowCount = null,
                problemFamilyBudgetRowCount = null,
                budgetInputSnapshotFingerprint = directionalBudgetInputSeedFingerprint(),
                budgetOutputFingerprint = null,
                budgetInputRowCount = 0L,
            ),
        )
        return MasteryProjectionRebuildChunkResult(
            stage = MasteryProjectionRebuildStage.RESET,
            processedRowCount = 0,
            completed = false,
            generationId = generation.generationId,
            activeGenerationAvailable = readActiveProjectionGeneration() != null,
        )
    }

    private suspend fun rebuildProjectionHistoryChunk(
        generation: MasteryProjectionGenerationEntity,
    ): MasteryProjectionRebuildChunkResult {
        val rows =
            readProjectionHistoryRebuildPage(
                cursorLearnerId = generation.cursorLearnerId,
                cursorOccurredAtEpochMillis = generation.cursorOccurredAtEpochMillis,
                cursorEventId = generation.cursorEventId,
                cursorOrdinal = generation.cursorOrdinal,
                cursorDirection = generation.cursorDirection,
                limit = LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE,
            )
        accumulateRebuildPresentationBudgets(generation.generationId, rows)
        accumulateRebuildProblemFamilyBudgets(generation.generationId, rows)
        val nextInputFingerprint =
            if (rows.isEmpty()) {
                generation.budgetInputSnapshotFingerprint
                    ?: directionalBudgetInputSeedFingerprint()
            } else {
                extendDirectionalBudgetInputFingerprint(
                    previousFingerprint =
                        generation.budgetInputSnapshotFingerprint
                            ?: directionalBudgetInputSeedFingerprint(),
                    rows = rows,
                )
            }
        val nextInputRowCount =
            Math.addExact(generation.budgetInputRowCount, rows.size.toLong())
        if (rows.size < LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE) {
            val last = rows.lastOrNull()
            upsertProjectionGeneration(
                generation.copy(
                    stage = MasteryProjectionRebuildStage.DIMENSIONS.name,
                    cursorLearnerId = "",
                    cursorSubject = "",
                    cursorEventSequence = PROJECTION_DIMENSION_STREAM_CURSOR_VERSION,
                    cursorOrdinal = -1,
                    cursorOccurredAtEpochMillis = -1L,
                    cursorEventId = "",
                    cursorDirection = "",
                    budgetInputSnapshotFingerprint = nextInputFingerprint,
                    budgetInputRowCount = nextInputRowCount,
                ),
            )
        } else {
            val last = rows.last()
            upsertProjectionGeneration(
                generation.copy(
                    cursorLearnerId = last.learnerId,
                    cursorEventSequence = last.eventSequence,
                    cursorOrdinal = last.ordinal,
                    cursorOccurredAtEpochMillis = last.occurredAtEpochMillis,
                    cursorEventId = last.eventId,
                    cursorDirection = last.direction,
                    budgetInputSnapshotFingerprint = nextInputFingerprint,
                    budgetInputRowCount = nextInputRowCount,
                ),
            )
        }
        return MasteryProjectionRebuildChunkResult(
            stage = MasteryProjectionRebuildStage.PROJECTIONS,
            processedRowCount = rows.size,
            completed = false,
            generationId = generation.generationId,
            activeGenerationAvailable = readActiveProjectionGeneration() != null,
        )
    }

    private suspend fun accumulateRebuildPresentationBudgets(
        generationId: Long,
        rows: List<MasteryEventAttributionReplayRow>,
    ) {
        val accumulated =
            LinkedHashMap<
                MasteryPresentationBudgetReplayKey,
                MasteryPresentationNodeBudgetShadowEntity,
            >()
        rows.forEach { row ->
            val key =
                MasteryPresentationBudgetReplayKey(
                    learnerId = row.learnerId,
                    presentationId = row.presentationFingerprint,
                    subject = row.subject,
                    knowledgeNodeId = row.knowledgeNodeId,
                    taxonomyVersion = row.taxonomyVersion,
                    direction = row.direction,
                )
            val previous = accumulated[key]
            val consumedMassMicros =
                Math.addExact(previous?.consumedMassMicros ?: 0L, row.evidenceMassMicros)
            check(consumedMassMicros in 0L..LocalMasteryPolicy.FULL_MASS_MICROS) {
                "Immutable history exceeds the presentation evidence cap"
            }
            val latestEvent =
                latestBudgetEvent(
                    existingEventId = previous?.lastEventId,
                    existingUpdatedAtEpochMillis = previous?.updatedAtEpochMillis,
                    candidateEventId = row.eventId,
                    candidateUpdatedAtEpochMillis = row.admittedAtEpochMillis,
                )
            accumulated[key] =
                MasteryPresentationNodeBudgetShadowEntity(
                    generationId = generationId,
                    learnerId = row.learnerId,
                    presentationId = row.presentationFingerprint,
                    subject = row.subject,
                    knowledgeNodeId = row.knowledgeNodeId,
                    taxonomyVersion = row.taxonomyVersion,
                    direction = row.direction,
                    stableNodeIdentityFingerprint =
                        MasteryProjectionIdentity.fingerprint(
                            row.subject,
                            row.knowledgeNodeId,
                            row.taxonomyVersion,
                        ),
                    consumedMassMicros = consumedMassMicros,
                    lastEventId = latestEvent.eventId,
                    updatedAtEpochMillis = latestEvent.updatedAtEpochMillis,
                )
        }
        val budgets = accumulated.values.toList()
        val insertedRowIds = insertShadowPresentationNodeBudgetsIfAbsent(budgets)
        check(insertedRowIds.size == budgets.size) {
            "Presentation budget replay insert result is incomplete"
        }
        budgets.indices.forEach { index ->
            if (insertedRowIds[index] == INSERT_CONFLICT) {
                val budget = budgets[index]
                check(
                    addToShadowPresentationNodeBudget(
                        generationId = budget.generationId,
                        learnerId = budget.learnerId,
                        presentationId = budget.presentationId,
                        subject = budget.subject,
                        knowledgeNodeId = budget.knowledgeNodeId,
                        taxonomyVersion = budget.taxonomyVersion,
                        direction = budget.direction,
                        additionalMassMicros = budget.consumedMassMicros,
                        maximumMassMicros = LocalMasteryPolicy.FULL_MASS_MICROS,
                        lastEventId = budget.lastEventId,
                        updatedAtEpochMillis = budget.updatedAtEpochMillis,
                    ) == 1,
                ) {
                    "Immutable history exceeds the presentation evidence cap"
                }
            }
        }
    }

    private suspend fun accumulateRebuildProblemFamilyBudgets(
        generationId: Long,
        rows: List<MasteryEventAttributionReplayRow>,
    ) {
        val accumulated =
            LinkedHashMap<
                MasteryProblemFamilyBudgetReplayKey,
                MasteryProblemFamilyNodeBudgetShadowEntity,
            >()
        rows.forEach { row ->
            val problemFamilyFingerprint = row.problemFamilyFingerprint ?: return@forEach
            val key =
                MasteryProblemFamilyBudgetReplayKey(
                    learnerId = row.learnerId,
                    problemFamilyFingerprint = problemFamilyFingerprint,
                    subject = row.subject,
                    knowledgeNodeId = row.knowledgeNodeId,
                    taxonomyVersion = row.taxonomyVersion,
                    direction = row.direction,
                )
            val previous = accumulated[key]
            val consumedMassMicros =
                Math.addExact(previous?.consumedMassMicros ?: 0L, row.evidenceMassMicros)
            check(
                consumedMassMicros in
                    0L..LocalProblemFamilyEvidenceBudget.MAX_FAMILY_MASS_MICROS,
            ) {
                "Immutable history exceeds the problem-family evidence cap"
            }
            val latestEvent =
                latestBudgetEvent(
                    existingEventId = previous?.lastEventId,
                    existingUpdatedAtEpochMillis = previous?.updatedAtEpochMillis,
                    candidateEventId = row.eventId,
                    candidateUpdatedAtEpochMillis = row.admittedAtEpochMillis,
                )
            accumulated[key] =
                MasteryProblemFamilyNodeBudgetShadowEntity(
                    generationId = generationId,
                    learnerId = row.learnerId,
                    problemFamilyFingerprint = problemFamilyFingerprint,
                    subject = row.subject,
                    knowledgeNodeId = row.knowledgeNodeId,
                    taxonomyVersion = row.taxonomyVersion,
                    direction = row.direction,
                    stableNodeIdentityFingerprint =
                        MasteryProjectionIdentity.fingerprint(
                            row.subject,
                            row.knowledgeNodeId,
                            row.taxonomyVersion,
                        ),
                    observationCount = Math.addExact(previous?.observationCount ?: 0L, 1L),
                    consumedMassMicros = consumedMassMicros,
                    lastEventId = latestEvent.eventId,
                    updatedAtEpochMillis = latestEvent.updatedAtEpochMillis,
                )
        }
        val budgets = accumulated.values.toList()
        val insertedRowIds = insertShadowProblemFamilyNodeBudgetsIfAbsent(budgets)
        check(insertedRowIds.size == budgets.size) {
            "Problem-family budget replay insert result is incomplete"
        }
        budgets.indices.forEach { index ->
            if (insertedRowIds[index] == INSERT_CONFLICT) {
                val budget = budgets[index]
                check(
                    addToShadowProblemFamilyNodeBudget(
                        generationId = budget.generationId,
                        learnerId = budget.learnerId,
                        problemFamilyFingerprint = budget.problemFamilyFingerprint,
                        subject = budget.subject,
                        knowledgeNodeId = budget.knowledgeNodeId,
                        taxonomyVersion = budget.taxonomyVersion,
                        direction = budget.direction,
                        additionalObservationCount = budget.observationCount,
                        maximumObservationCount = Long.MAX_VALUE,
                        additionalMassMicros = budget.consumedMassMicros,
                        maximumMassMicros =
                            LocalProblemFamilyEvidenceBudget.MAX_FAMILY_MASS_MICROS,
                        lastEventId = budget.lastEventId,
                        updatedAtEpochMillis = budget.updatedAtEpochMillis,
                    ) == 1,
                ) {
                    "Immutable history exceeds the problem-family evidence cap"
                }
            }
        }
    }

    private suspend fun rebuildProjectionDimensionsChunk(
        generation: MasteryProjectionGenerationEntity,
    ): MasteryProjectionRebuildChunkResult {
        if (generation.cursorEventSequence != PROJECTION_DIMENSION_STREAM_CURSOR_VERSION) {
            deleteShadowProjections(generation.generationId)
            upsertProjectionGeneration(
                generation.copy(
                    cursorLearnerId = "",
                    cursorSubject = "",
                    cursorEventSequence = PROJECTION_DIMENSION_STREAM_CURSOR_VERSION,
                    cursorOrdinal = -1,
                    cursorOccurredAtEpochMillis = -1L,
                    cursorEventId = "",
                    cursorDirection = "",
                ),
            )
            return MasteryProjectionRebuildChunkResult(
                stage = MasteryProjectionRebuildStage.DIMENSIONS,
                processedRowCount = 0,
                completed = false,
                generationId = generation.generationId,
                activeGenerationAvailable = readActiveProjectionGeneration() != null,
            )
        }
        val rows =
            readProjectionHistoryRebuildPage(
                cursorLearnerId = generation.cursorLearnerId,
                cursorOccurredAtEpochMillis = generation.cursorOccurredAtEpochMillis,
                cursorEventId = generation.cursorEventId,
                cursorOrdinal = generation.cursorOrdinal,
                cursorDirection = generation.cursorDirection,
                limit = LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE,
            )
        val pageKeys = rows.mapTo(linkedSetOf(), MasteryEventAttributionReplayRow::replayKey)
        val currentByKey = loadShadowProjectionPage(generation.generationId, pageKeys)
        rows.forEach { historyRow ->
            val key = historyRow.replayKey()
            currentByKey[key] =
                LocalMasteryPolicy.nextProjection(
                    learnerId = historyRow.learnerId,
                    event = historyRow.event(),
                    attribution = historyRow.attribution(),
                    current = currentByKey[key],
                )
        }
        if (currentByKey.isNotEmpty()) {
            upsertShadowProjections(
                currentByKey.values.map { projection ->
                    projection.toShadow(generation.generationId)
                },
            )
        }
        if (rows.size < LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE) {
            upsertProjectionGeneration(
                generation.copy(
                    stage = MasteryProjectionRebuildStage.EVIDENCE_DIMENSIONS.name,
                    cursorLearnerId = "",
                    cursorSubject = "",
                    cursorEventSequence = -1L,
                    cursorOrdinal = -1,
                    cursorOccurredAtEpochMillis = -1L,
                    cursorEventId = "",
                    cursorDirection = "",
                ),
            )
        } else {
            val last = rows.last()
            upsertProjectionGeneration(
                generation.copy(
                    cursorLearnerId = last.learnerId,
                    cursorSubject = "",
                    cursorEventSequence = PROJECTION_DIMENSION_STREAM_CURSOR_VERSION,
                    cursorOrdinal = last.ordinal,
                    cursorOccurredAtEpochMillis = last.occurredAtEpochMillis,
                    cursorEventId = last.eventId,
                    cursorDirection = last.direction,
                ),
            )
        }
        return MasteryProjectionRebuildChunkResult(
            stage = MasteryProjectionRebuildStage.DIMENSIONS,
            processedRowCount = rows.size,
            completed = false,
            immutableHistoryQueryCount = if (rows.isEmpty()) 0 else 1,
            peakMaterializedRowCount = rows.size + pageKeys.size + currentByKey.size,
            generationId = generation.generationId,
            activeGenerationAvailable = readActiveProjectionGeneration() != null,
        )
    }

    private suspend fun loadShadowProjectionPage(
        generationId: Long,
        pageKeys: Set<MasteryProjectionReplayKey>,
    ): LinkedHashMap<MasteryProjectionReplayKey, MasteryKnowledgeProjectionEntity> {
        val currentByKey = LinkedHashMap<MasteryProjectionReplayKey, MasteryKnowledgeProjectionEntity>()
        pageKeys.groupBy(MasteryProjectionReplayKey::learnerId).forEach { (learnerId, keys) ->
            keys.chunked(PROJECTION_REPLAY_EVENT_QUERY_BATCH_SIZE).forEach { keyBatch ->
                readShadowProjectionsByStableIdentity(
                    generationId = generationId,
                    learnerId = learnerId,
                    stableNodeIdentityFingerprints =
                        keyBatch.map { key ->
                            MasteryProjectionIdentity.fingerprint(
                                key.subject,
                                key.knowledgeNodeId,
                                key.taxonomyVersion,
                            )
                        },
                ).forEach { shadow ->
                    val key = shadow.replayKey()
                    if (key in pageKeys) currentByKey[key] = shadow.toProjection()
                }
            }
        }
        return currentByKey
    }

    private suspend fun rebuildProjectionEvidenceDimensionsChunk(
        generation: MasteryProjectionGenerationEntity,
    ): MasteryProjectionRebuildChunkResult {
        val rows =
            readProjectionEvidenceDimensionPage(
                generationId = generation.generationId,
                cursorLearnerId = generation.cursorLearnerId,
                cursorSubject = generation.cursorSubject,
                cursorKnowledgeNodeId = generation.cursorEventId,
                cursorTaxonomyVersion = generation.cursorDirection,
                limit = PROJECTION_EVIDENCE_DIMENSION_PAGE_SIZE,
            )
        val completedRows = ArrayList<MasteryProjectionShadowEntity>(rows.size)
        rows
            .groupBy { shadow ->
                Triple(shadow.learnerId, shadow.subject, shadow.taxonomyVersion)
            }
            .forEach { (identity, group) ->
                val (learnerId, subject, taxonomyVersion) = identity
                val batched =
                    readProjectionEvidenceDimensionsForNodes(
                        learnerId = learnerId,
                        subject = subject,
                        taxonomyVersion = taxonomyVersion,
                        knowledgeNodeIds =
                            group.map { shadow -> shadow.knowledgeNodeId },
                    ).associateBy(MasteryProjectionNodeEvidenceDimensionsRow::knowledgeNodeId)
                group.forEach { shadow ->
                    val dimensions =
                        batched[shadow.knowledgeNodeId]
                            ?.toProjectionDimensions()
                            ?: readProjectionEvidenceDimensions(
                                learnerId = learnerId,
                                subject = subject,
                                knowledgeNodeId = shadow.knowledgeNodeId,
                                taxonomyVersion = taxonomyVersion,
                            )
                    completedRows +=
                        shadow.toProjection()
                            .withEvidenceDimensions(dimensions)
                            .toShadow(generation.generationId)
                }
            }
        if (completedRows.isNotEmpty()) upsertShadowProjections(completedRows)
        if (rows.size < PROJECTION_EVIDENCE_DIMENSION_PAGE_SIZE) {
            upsertProjectionGeneration(
                generation.copy(
                    stage = MasteryProjectionRebuildStage.SUBJECT_DIGESTS.name,
                    cursorLearnerId = "",
                    cursorSubject = "",
                    cursorEventSequence = -1L,
                    cursorOrdinal = -1,
                    cursorOccurredAtEpochMillis = -1L,
                    cursorEventId = "",
                    cursorDirection = "",
                ),
            )
        } else {
            val last = rows.last()
            upsertProjectionGeneration(
                generation.copy(
                    cursorLearnerId = last.learnerId,
                    cursorSubject = last.subject,
                    cursorEventId = last.knowledgeNodeId,
                    cursorDirection = last.taxonomyVersion,
                ),
            )
        }
        return MasteryProjectionRebuildChunkResult(
            stage = MasteryProjectionRebuildStage.EVIDENCE_DIMENSIONS,
            processedRowCount = rows.size,
            completed = false,
            immutableHistoryQueryCount = 0,
            peakMaterializedRowCount = rows.size + completedRows.size,
            generationId = generation.generationId,
            activeGenerationAvailable = readActiveProjectionGeneration() != null,
        )
    }

    private suspend fun rebuildSubjectDigestsChunk(
        generation: MasteryProjectionGenerationEntity,
    ): MasteryProjectionRebuildChunkResult {
        val rows =
            readSubjectRebuildPage(
                cursorLearnerId = generation.cursorLearnerId,
                cursorSubject = generation.cursorSubject,
                limit = LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE,
            )
        rows.forEach { row ->
            val counts =
                countShadowMasteryBands(
                    generationId = generation.generationId,
                    learnerId = row.learnerId,
                    subject = row.subject,
                )
            upsertShadowSubjectDigest(
                MasterySubjectDigestShadowEntity(
                    generationId = generation.generationId,
                    learnerId = row.learnerId,
                    subject = row.subject,
                    needsReinforcementCount =
                        (counts.needsReinforcementCount ?: 0L).toBoundedInt(),
                    familiarizingCount =
                        (counts.familiarizingCount ?: 0L).toBoundedInt(),
                    steadyCount = (counts.steadyCount ?: 0L).toBoundedInt(),
                    lastEventSequence = row.lastEventSequence,
                    updatedAtEpochMillis = row.updatedAtEpochMillis,
                    projectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
                ),
            )
        }
        if (rows.size < LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE) {
            upsertProjectionGeneration(
                generation.copy(
                    stage = MasteryProjectionRebuildStage.PRESENTATION_BUDGETS.name,
                    cursorLearnerId = "",
                    cursorSubject = "",
                    presentationBudgetRowCount = 0L,
                    problemFamilyBudgetRowCount = 0L,
                    budgetOutputFingerprint = directionalBudgetOutputSeedFingerprint(),
                ),
            )
        } else {
            val last = rows.last()
            upsertProjectionGeneration(
                generation.copy(
                    cursorLearnerId = last.learnerId,
                    cursorSubject = last.subject,
                ),
            )
        }
        return MasteryProjectionRebuildChunkResult(
            stage = MasteryProjectionRebuildStage.SUBJECT_DIGESTS,
            processedRowCount = rows.size,
            completed = false,
            generationId = generation.generationId,
            activeGenerationAvailable = readActiveProjectionGeneration() != null,
        )
    }

    private suspend fun fingerprintPresentationBudgetsChunk(
        generation: MasteryProjectionGenerationEntity,
    ): MasteryProjectionRebuildChunkResult {
        val rows =
            readShadowPresentationBudgetFingerprintPage(
                generationId = generation.generationId,
                afterExclusive = generation.cursorLearnerId.ifBlank { null },
                limit = LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE,
            )
        val nextFingerprint =
            if (rows.isEmpty()) {
                generation.budgetOutputFingerprint
                    ?: directionalBudgetOutputSeedFingerprint()
            } else {
                extendDirectionalPresentationBudgetOutputFingerprint(
                    previousFingerprint =
                        generation.budgetOutputFingerprint
                            ?: directionalBudgetOutputSeedFingerprint(),
                    rows = rows,
                )
            }
        val processed =
            Math.addExact(
                generation.presentationBudgetRowCount ?: 0L,
                rows.size.toLong(),
            )
        if (rows.size < LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE) {
            upsertProjectionGeneration(
                generation.copy(
                    stage = MasteryProjectionRebuildStage.PROBLEM_FAMILY_BUDGETS.name,
                    cursorLearnerId = "",
                    cursorSubject = "",
                    presentationBudgetRowCount = processed,
                    budgetOutputFingerprint = nextFingerprint,
                ),
            )
        } else {
            upsertProjectionGeneration(
                generation.copy(
                    cursorLearnerId = rows.last().directionalStableKey(),
                    presentationBudgetRowCount = processed,
                    budgetOutputFingerprint = nextFingerprint,
                ),
            )
        }
        return MasteryProjectionRebuildChunkResult(
            stage = MasteryProjectionRebuildStage.PRESENTATION_BUDGETS,
            processedRowCount = rows.size,
            completed = false,
            generationId = generation.generationId,
            activeGenerationAvailable = readActiveProjectionGeneration() != null,
        )
    }

    private suspend fun fingerprintProblemFamilyBudgetsChunk(
        generation: MasteryProjectionGenerationEntity,
    ): MasteryProjectionRebuildChunkResult {
        val rows =
            readShadowProblemFamilyBudgetFingerprintPage(
                generationId = generation.generationId,
                afterExclusive = generation.cursorLearnerId.ifBlank { null },
                limit = LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE,
            )
        val nextFingerprint =
            if (rows.isEmpty()) {
                generation.budgetOutputFingerprint
                    ?: directionalBudgetOutputSeedFingerprint()
            } else {
                extendDirectionalProblemFamilyBudgetOutputFingerprint(
                    previousFingerprint =
                        generation.budgetOutputFingerprint
                            ?: directionalBudgetOutputSeedFingerprint(),
                    rows = rows,
                )
            }
        val processed =
            Math.addExact(
                generation.problemFamilyBudgetRowCount ?: 0L,
                rows.size.toLong(),
            )
        if (rows.size < LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE) {
            upsertProjectionGeneration(
                generation.copy(
                    stage = MasteryProjectionRebuildStage.COMPLETE.name,
                    cursorLearnerId = "",
                    cursorSubject = "",
                    problemFamilyBudgetRowCount = processed,
                    budgetOutputFingerprint = nextFingerprint,
                ),
            )
        } else {
            upsertProjectionGeneration(
                generation.copy(
                    cursorLearnerId = rows.last().directionalStableKey(),
                    problemFamilyBudgetRowCount = processed,
                    budgetOutputFingerprint = nextFingerprint,
                ),
            )
        }
        return MasteryProjectionRebuildChunkResult(
            stage = MasteryProjectionRebuildStage.PROBLEM_FAMILY_BUDGETS,
            processedRowCount = rows.size,
            completed = false,
            generationId = generation.generationId,
            activeGenerationAvailable = readActiveProjectionGeneration() != null,
        )
    }

    private suspend fun completeProjectionRebuild(
        generation: MasteryProjectionGenerationEntity,
        nowEpochMillis: Long,
    ): MasteryProjectionRebuildChunkResult {
        check(
            readMetadata(
                directionalBudgetGenerationEpochMetadataKey(
                    generation.generationId,
                ),
            ) == DIRECTIONAL_BUDGET_REBUILD_EPOCH,
        ) {
            "Projection generation is not bound to the current presentation-budget epoch"
        }
        validateAllPersistedCalibrationBindings()?.let { blockedReason ->
            return MasteryProjectionRebuildChunkResult(
                stage = MasteryProjectionRebuildStage.RESET,
                processedRowCount = 0,
                completed = false,
                blockedReason = blockedReason,
                generationId = generation.generationId,
                activeGenerationAvailable = readActiveProjectionGeneration() != null,
            )
        }
        val currentEventCount = countLearningEvents()
        val currentSupersessionCount = countLearningEvidenceSupersessions()
        if (
            currentEventCount != generation.sourceEventCount ||
            currentSupersessionCount != generation.sourceSupersessionCount
        ) {
            deleteShadowProjections(generation.generationId)
            deleteShadowSubjectDigests(generation.generationId)
            deleteShadowPresentationBudgets(generation.generationId)
            deleteShadowProblemFamilyBudgets(generation.generationId)
            upsertProjectionGeneration(
                generation.copy(
                    sourceEventCount = currentEventCount,
                    sourceSupersessionCount = currentSupersessionCount,
                    stage = MasteryProjectionRebuildStage.RESET.name,
                    cursorLearnerId = "",
                    cursorSubject = "",
                    cursorEventSequence = -1L,
                    cursorOrdinal = -1,
                    cursorOccurredAtEpochMillis = -1L,
                    cursorEventId = "",
                    cursorDirection = "",
                    snapshotFingerprint = null,
                    projectionRowCount = null,
                    subjectDigestRowCount = null,
                    presentationBudgetRowCount = null,
                    problemFamilyBudgetRowCount = null,
                    budgetInputSnapshotFingerprint =
                        directionalBudgetInputSeedFingerprint(),
                    budgetOutputFingerprint = null,
                    budgetInputRowCount = 0L,
                ),
            )
            return MasteryProjectionRebuildChunkResult(
                stage = MasteryProjectionRebuildStage.RESET,
                processedRowCount = 0,
                completed = false,
                generationId = generation.generationId,
                activeGenerationAvailable = readActiveProjectionGeneration() != null,
            )
        }
        val projectionCount = countShadowProjectionRows(generation.generationId)
        val digestCount = countShadowSubjectDigestRows(generation.generationId)
        val presentationBudgetCount =
            countShadowPresentationBudgetRows(generation.generationId)
        val problemFamilyBudgetCount =
            countShadowProblemFamilyBudgetRows(generation.generationId)
        check(generation.presentationBudgetRowCount == presentationBudgetCount) {
            "Directional presentation budget output fingerprint skipped a row"
        }
        check(generation.problemFamilyBudgetRowCount == problemFamilyBudgetCount) {
            "Directional problem-family budget output fingerprint skipped a row"
        }
        check(countProjectionHistoryRebuildRows() == generation.budgetInputRowCount) {
            "Directional budget replay input count changed or a keyset row was skipped"
        }
        val budgetReceipt =
            buildProjectionBudgetRebuildReceipt(
                generation = generation,
                presentationBudgetCount = presentationBudgetCount,
                problemFamilyBudgetCount = problemFamilyBudgetCount,
                completedAtEpochMillis = nowEpochMillis,
            )
        val fingerprint =
            projectionGenerationFingerprint(
                generationId = generation.generationId,
                targetProjectionPolicyVersion = generation.targetProjectionPolicyVersion,
                targetCalibrationVersion = generation.targetCalibrationVersion,
                sourceEventCount = generation.sourceEventCount,
                sourceSupersessionCount = generation.sourceSupersessionCount,
                projectionRowCount = projectionCount,
                subjectDigestRowCount = digestCount,
                presentationBudgetRowCount = presentationBudgetCount,
                problemFamilyBudgetRowCount = problemFamilyBudgetCount,
                budgetInputRowCount = budgetReceipt.inputRowCount,
                budgetInputSnapshotFingerprint = budgetReceipt.inputSnapshotFingerprint,
                budgetOutputFingerprint = budgetReceipt.outputFingerprint,
            )
        val receiptInsert = insertProjectionBudgetRebuildReceipt(budgetReceipt)
        check(
            receiptInsert != INSERT_CONFLICT ||
                findProjectionBudgetRebuildReceipt(generation.generationId) == budgetReceipt,
        ) {
            "Directional budget rebuild receipt conflicts with an immutable prior result"
        }
        deleteAllKnowledgeProjections()
        deleteAllSubjectDigests()
        deleteAllPresentationBudgets()
        deleteAllProblemFamilyBudgets()
        copyShadowProjectionsToActive(generation.generationId)
        check(countActiveProjectionRows() == projectionCount) {
            "Projection generation activation copied an incomplete projection snapshot"
        }
        copyShadowSubjectDigestsToActive(generation.generationId)
        check(countActiveSubjectDigestRows() == digestCount) {
            "Projection generation activation copied an incomplete subject digest snapshot"
        }
        copyShadowPresentationBudgetsToActive(generation.generationId)
        check(countActivePresentationBudgetRows() == presentationBudgetCount) {
            "Projection generation activation copied incomplete presentation budgets"
        }
        copyShadowProblemFamilyBudgetsToActive(generation.generationId)
        check(countActiveProblemFamilyBudgetRows() == problemFamilyBudgetCount) {
            "Projection generation activation copied incomplete problem-family budgets"
        }
        retireOtherProjectionGenerations(generation.generationId)
        upsertProjectionGeneration(
            generation.copy(
                state = MasteryProjectionGenerationState.ACTIVE.name,
                stage = MasteryProjectionRebuildStage.COMPLETE.name,
                leaseOwnerId = null,
                leaseExpiresAtEpochMillis = null,
                snapshotFingerprint = fingerprint,
                projectionRowCount = projectionCount,
                subjectDigestRowCount = digestCount,
                presentationBudgetRowCount = presentationBudgetCount,
                problemFamilyBudgetRowCount = problemFamilyBudgetCount,
                budgetInputSnapshotFingerprint = budgetReceipt.inputSnapshotFingerprint,
                budgetOutputFingerprint = budgetReceipt.outputFingerprint,
                budgetInputRowCount = budgetReceipt.inputRowCount,
                activatedAtEpochMillis = nowEpochMillis,
            ),
        )
        // The active shadow is the independent cutover-attestation witness. Retired detail
        // shadows are no longer authoritative; their immutable generation and receipt rows remain.
        deleteRetiredShadowProjections()
        deleteRetiredShadowSubjectDigests()
        deleteRetiredShadowPresentationBudgets()
        deleteRetiredShadowProblemFamilyBudgets()
        markCurrentProjectionRebuildCompleted()
        markPresentationFingerprintBudgetRebuildCompleted()
        return MasteryProjectionRebuildChunkResult(
            stage = MasteryProjectionRebuildStage.COMPLETE,
            processedRowCount = 0,
            completed = true,
            generationId = generation.generationId,
            activeGenerationAvailable = true,
        )
    }

    private suspend fun buildProjectionBudgetRebuildReceipt(
        generation: MasteryProjectionGenerationEntity,
        presentationBudgetCount: Long,
        problemFamilyBudgetCount: Long,
        completedAtEpochMillis: Long,
    ): ProjectionBudgetRebuildReceiptEntity {
        val inputFingerprint =
            checkNotNull(generation.budgetInputSnapshotFingerprint) {
                "Directional budget rebuild is missing its input snapshot chain"
            }
        val outputFingerprint =
            checkNotNull(generation.budgetOutputFingerprint) {
                "Directional budget rebuild is missing its bounded output fingerprint chain"
            }
        val hasInput = generation.budgetInputRowCount > 0L
        val lastInput =
            if (hasInput) {
                checkNotNull(readLastProjectionBudgetInputRow()) {
                    "Directional budget rebuild has input rows without an immutable tail"
                }
            } else {
                null
            }
        return ProjectionBudgetRebuildReceiptEntity(
            generationId = generation.generationId,
            budgetPolicyVersion = LEARNER_MASTERY_DIRECTIONAL_BUDGET_POLICY_VERSION,
            algorithmVersion =
                LEARNER_MASTERY_DIRECTIONAL_BUDGET_REBUILD_ALGORITHM_VERSION,
            sourceEventCount = generation.sourceEventCount,
            sourceSupersessionCount = generation.sourceSupersessionCount,
            inputRowCount = generation.budgetInputRowCount,
            inputSnapshotFingerprint = inputFingerprint,
            presentationBudgetRowCount = presentationBudgetCount,
            problemFamilyBudgetRowCount = problemFamilyBudgetCount,
            lastOccurredAtEpochMillis = lastInput?.occurredAtEpochMillis,
            lastEventId = lastInput?.eventId,
            lastAttributionOrdinal = lastInput?.ordinal,
            lastDirection = lastInput?.direction,
            outputFingerprint = outputFingerprint,
            completedAtEpochMillis = completedAtEpochMillis,
        )
    }

    private suspend fun markCurrentProjectionRebuildCompleted() {
        val completionMarker =
            MasteryStoreMetadataEntity(
                metadataKey = PROJECTION_REBUILD_COMPLETED_METADATA_KEY,
                metadataValue = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
            )
        check(
            insertMetadata(completionMarker) != INSERT_CONFLICT ||
                readMetadata(PROJECTION_REBUILD_COMPLETED_METADATA_KEY) ==
                LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
        ) {
            "Learner-mastery projection rebuild completion marker conflicts"
        }
    }

    private suspend fun markPresentationFingerprintBudgetRebuildCompleted() {
        val completionMarker =
            MasteryStoreMetadataEntity(
                metadataKey =
                    DIRECTIONAL_BUDGET_REBUILD_COMPLETED_METADATA_KEY,
                metadataValue = DIRECTIONAL_BUDGET_REBUILD_EPOCH,
            )
        check(
            insertMetadata(completionMarker) != INSERT_CONFLICT ||
                readMetadata(DIRECTIONAL_BUDGET_REBUILD_COMPLETED_METADATA_KEY) ==
                DIRECTIONAL_BUDGET_REBUILD_EPOCH,
        ) {
            "Presentation-fingerprint budget rebuild completion marker conflicts"
        }
    }

    private suspend fun bindProjectionGenerationToPresentationFingerprintBudgetEpoch(
        generationId: Long,
    ) {
        val metadataKey =
            directionalBudgetGenerationEpochMetadataKey(generationId)
        val epochBinding =
            MasteryStoreMetadataEntity(
                metadataKey = metadataKey,
                metadataValue = DIRECTIONAL_BUDGET_REBUILD_EPOCH,
            )
        check(
            insertMetadata(epochBinding) != INSERT_CONFLICT ||
                readMetadata(metadataKey) == DIRECTIONAL_BUDGET_REBUILD_EPOCH,
        ) {
            "Projection generation presentation-budget epoch binding conflicts"
        }
    }

    private suspend fun canAdoptCurrentDerivedState(
        eventCount: Long,
        completionMatchesCurrentPolicy: Boolean,
    ): Boolean {
        @Suppress("UNUSED_VARIABLE")
        val ignoredLegacyState = eventCount to completionMatchesCurrentPolicy
        return false
    }

    private suspend fun advanceProjectionRebuildStage(stage: MasteryProjectionRebuildStage) {
        check(stage != MasteryProjectionRebuildStage.RESET) {
            "Projection rebuild reset stage is transient"
        }
        appendProjectionRebuildProgress(
            stage = stage,
            cursor =
                MasteryProjectionRebuildCursor(
                    protocolVersion = PROJECTION_REBUILD_STREAM_PROTOCOL,
                ),
        )
    }

    private suspend fun readProjectionRebuildCursor(): MasteryProjectionRebuildCursor =
        checkNotNull(
            readLatestProjectionRebuildProgress()?.toProjectionRebuildProgress(),
        ) {
            "Learner-mastery projection rebuild progress is missing"
        }.cursor

    private suspend fun writeProjectionRebuildCursor(cursor: MasteryProjectionRebuildCursor) {
        val current =
            checkNotNull(
                readLatestProjectionRebuildProgress()?.toProjectionRebuildProgress(),
            ) {
                "Learner-mastery projection rebuild progress is missing"
            }
        appendProjectionRebuildProgress(current.stage, cursor)
    }

    private suspend fun appendProjectionRebuildProgress(
        stage: MasteryProjectionRebuildStage,
        cursor: MasteryProjectionRebuildCursor,
    ) {
        val current = readLatestProjectionRebuildProgress()?.toProjectionRebuildProgress()
        val nextSequence = Math.addExact(current?.sequence ?: 0L, 1L)
        val key =
            PROJECTION_REBUILD_PROGRESS_V3_METADATA_PREFIX +
                nextSequence.toString().padStart(PROJECTION_REBUILD_SEQUENCE_WIDTH, '0')
        check(
            insertMetadata(
                MasteryStoreMetadataEntity(
                    metadataKey = key,
                    metadataValue =
                        encodeProjectionRebuildProgress(
                            MasteryProjectionRebuildProgress(
                                sequence = nextSequence,
                                stage = stage,
                                cursor = cursor,
                            ),
                        ),
                ),
            ) != INSERT_CONFLICT,
        ) {
            "Learner-mastery projection rebuild progress sequence conflicts"
        }
    }

    @Query(
        """
        SELECT
            e.occurred_at_epoch_millis / 86400000 AS utc_epoch_day,
            COUNT(DISTINCT e.event_id) AS observation_count,
            COUNT(DISTINCT (
                a.knowledge_node_id || '|' ||
                a.taxonomy_version || '|' ||
                a.knowledge_pack_version
            )) AS affected_knowledge_count,
            SUM(
                CASE WHEN e.direction = 'POSITIVE'
                    THEN a.evidence_mass_micros ELSE 0 END
            ) AS positive_mass_micros,
            SUM(
                CASE WHEN e.direction = 'NEGATIVE'
                    THEN a.evidence_mass_micros ELSE 0 END
            ) AS negative_mass_micros
        FROM mastery_learning_event e
        INNER JOIN mastery_learning_event_attribution a ON a.event_id = e.event_id
        WHERE e.learner_id = :learnerId
          AND e.subject = :subject
          AND e.occurred_at_epoch_millis >= :sinceEpochMillis
          AND NOT EXISTS (
              SELECT 1
              FROM mastery_learning_evidence_supersession s
              WHERE s.original_event_id = e.event_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM mastery_open_response_legacy_quarantine q
              WHERE q.accepted_event_id = e.event_id
          )
        GROUP BY utc_epoch_day
        ORDER BY utc_epoch_day DESC
        LIMIT :dayLimit
        """,
    )
    internal abstract suspend fun readSubjectTimeline(
        learnerId: String,
        subject: String,
        sinceEpochMillis: Long,
        dayLimit: Int,
    ): List<MasteryTimelineRow>

    protected suspend fun directionalBudgetRebuildReady(): Boolean {
        if (
            readMetadata(DIRECTIONAL_BUDGET_REBUILD_COMPLETED_METADATA_KEY) !=
            DIRECTIONAL_BUDGET_REBUILD_EPOCH
        ) {
            return false
        }
        val generation = readActiveProjectionGeneration() ?: return false
        if (
            generation.targetProjectionPolicyVersion !=
            LEARNER_MASTERY_PROJECTION_POLICY_VERSION ||
            generation.targetCalibrationVersion != LEARNER_MASTERY_CALIBRATION_VERSION
        ) {
            return false
        }
        val receipt = findProjectionBudgetRebuildReceipt(generation.generationId) ?: return false
        return receipt.budgetPolicyVersion ==
            LEARNER_MASTERY_DIRECTIONAL_BUDGET_POLICY_VERSION &&
            receipt.algorithmVersion ==
            LEARNER_MASTERY_DIRECTIONAL_BUDGET_REBUILD_ALGORITHM_VERSION &&
            receipt.sourceEventCount == generation.sourceEventCount &&
            receipt.sourceSupersessionCount == generation.sourceSupersessionCount &&
            receipt.presentationBudgetRowCount == generation.presentationBudgetRowCount &&
            receipt.problemFamilyBudgetRowCount == generation.problemFamilyBudgetRowCount &&
            receipt.inputRowCount == generation.budgetInputRowCount &&
            receipt.inputSnapshotFingerprint ==
            generation.budgetInputSnapshotFingerprint &&
            receipt.outputFingerprint == generation.budgetOutputFingerprint &&
            receipt.completedAtEpochMillis == generation.activatedAtEpochMillis
    }

    protected suspend fun validateAllPersistedCalibrationBindings():
        MasteryCalibrationBindingBlockReason? {
        findInvalidLearningEventCalibrationBinding(
            currentProjectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
            legacyCalibrationVersion = LEARNER_MASTERY_LEGACY_CALIBRATION_VERSION,
        )?.let { binding ->
            return validateEventCalibrationBinding(binding)
                ?: MasteryCalibrationBindingBlockReason.UNKNOWN_CALIBRATION_SNAPSHOT
        }
        findInvalidProjectionCalibrationBinding(
            currentProjectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
        )?.let { binding ->
            return validateProjectionCalibrationBinding(binding)
                ?: MasteryCalibrationBindingBlockReason.UNKNOWN_CALIBRATION_SNAPSHOT
        }
        readAllCalibrationSnapshots().forEach { snapshot ->
            validateCalibratedBinding(
                MasteryCalibrationBindingRow(
                    subject = snapshot.subject,
                    projectionPolicyVersion = snapshot.projectionPolicyVersion,
                    calibrationVersion = snapshot.calibrationVersion,
                    calibrationProfileId = snapshot.profileId,
                    calibrationSnapshotFingerprint = snapshot.snapshotFingerprint,
                ),
            )?.let { return it }
        }
        return null
    }

    protected suspend fun validateReplayCalibrationBindings(
        candidate: MasteryObservationCandidateEntity,
        decision: LocalMasteryAdmissionDecision.Admit,
    ): MasteryCalibrationBindingBlockReason? =
        validateReplayCalibrationBindings(
            learnerId = candidate.learnerId,
            nodes =
                decision.attributedMasses.map { attributed ->
                    MasteryProjectionNodeKey(
                        subject = attributed.attribution.subject,
                        knowledgeNodeId = attributed.attribution.knowledgeNodeId,
                        taxonomyVersion = attributed.attribution.taxonomyVersion,
                    )
                },
        )

    protected suspend fun validateReplayCalibrationBindings(
        learnerId: String,
        nodes: List<MasteryProjectionNodeKey>,
    ): MasteryCalibrationBindingBlockReason? {
        nodes.distinct().forEach { node ->
            findProjection(
                learnerId = learnerId,
                subject = node.subject,
                knowledgeNodeId = node.knowledgeNodeId,
                taxonomyVersion = node.taxonomyVersion,
            )?.let { projection ->
                validateProjectionCalibrationBinding(projection.calibrationBinding())
                    ?.let { return it }
            }
            readDistinctNodeHistoryCalibrationBindings(
                learnerId = learnerId,
                subject = node.subject,
                knowledgeNodeId = node.knowledgeNodeId,
                taxonomyVersion = node.taxonomyVersion,
            ).forEach { binding ->
                validateEventCalibrationBinding(binding)
                    ?.let { return it }
            }
        }
        return null
    }

    protected suspend fun validateEventCalibrationBinding(
        binding: MasteryCalibrationBindingRow,
    ): MasteryCalibrationBindingBlockReason? =
        if (binding.projectionPolicyVersion == LEARNER_MASTERY_PROJECTION_POLICY_VERSION) {
            validateCalibratedBinding(binding)
        } else if (
            binding.calibrationVersion == LEARNER_MASTERY_LEGACY_CALIBRATION_VERSION &&
            binding.calibrationProfileId == null &&
            binding.calibrationSnapshotFingerprint == null
        ) {
            null
        } else {
            MasteryCalibrationBindingBlockReason.MALFORMED_LEGACY_BINDING
        }

    protected suspend fun validateProjectionCalibrationBinding(
        binding: MasteryCalibrationBindingRow,
    ): MasteryCalibrationBindingBlockReason? =
        if (binding.projectionPolicyVersion == LEARNER_MASTERY_PROJECTION_POLICY_VERSION) {
            validateCalibratedBinding(binding)
        } else if (
            binding.calibrationVersion == null &&
            binding.calibrationProfileId == null &&
            binding.calibrationSnapshotFingerprint == null
        ) {
            null
        } else {
            MasteryCalibrationBindingBlockReason.MALFORMED_LEGACY_BINDING
        }

    protected suspend fun validateCalibratedBinding(
        binding: MasteryCalibrationBindingRow,
    ): MasteryCalibrationBindingBlockReason? {
        val calibrationVersion =
            binding.calibrationVersion
                ?: return MasteryCalibrationBindingBlockReason
                    .INCOMPLETE_CALIBRATED_BINDING
        val profileId =
            binding.calibrationProfileId
                ?: return MasteryCalibrationBindingBlockReason
                    .INCOMPLETE_CALIBRATED_BINDING
        val snapshotFingerprint =
            binding.calibrationSnapshotFingerprint
                ?: return MasteryCalibrationBindingBlockReason
                    .INCOMPLETE_CALIBRATED_BINDING
        val expected =
            LocalMasteryCalibrationRegistry.find(
                subject = binding.subject,
                calibrationVersion = calibrationVersion,
                profileId = profileId,
                snapshotFingerprint = snapshotFingerprint,
            ) ?: return MasteryCalibrationBindingBlockReason.UNKNOWN_CALIBRATION_SNAPSHOT
        if (expected.projectionPolicyVersion != binding.projectionPolicyVersion) {
            return MasteryCalibrationBindingBlockReason.UNKNOWN_CALIBRATION_SNAPSHOT
        }
        val persisted =
            findCalibrationSnapshot(
                subject = binding.subject,
                calibrationVersion = calibrationVersion,
                profileId = profileId,
                snapshotFingerprint = snapshotFingerprint,
            ) ?: return MasteryCalibrationBindingBlockReason
                .PERSISTED_CALIBRATION_SNAPSHOT_MISSING
        return if (persisted == expected) {
            null
        } else {
            MasteryCalibrationBindingBlockReason.PERSISTED_CALIBRATION_SNAPSHOT_CONFLICT
        }
    }

    private companion object {
        const val INSERT_CONFLICT = -1L
        const val PROJECTION_REBUILD_LEASE_MILLIS = 60_000L
        const val PROJECTION_REBUILD_STREAM_PROTOCOL =
            "event-sequence-subject-history-stream-v2"
        const val PREVIOUS_ACTIVE_PROJECTION_VERSION = "previous-active-projection"
        const val PREVIOUS_ACTIVE_CALIBRATION_VERSION = "previous-active-calibration"
    }
}
