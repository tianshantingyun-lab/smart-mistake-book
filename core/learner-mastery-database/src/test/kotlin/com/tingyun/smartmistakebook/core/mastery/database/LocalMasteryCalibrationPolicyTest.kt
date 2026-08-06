package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMasteryCalibrationPolicyTest {
    @Test
    fun currentCalibrationHasNineIndependentProfilesWithoutInventedSubjectDifferences() {
        val snapshots =
            SubjectKind.entries
                .filterNot { it == SubjectKind.GENERAL }
                .map { LocalMasteryCalibrationRegistry.current(it.name) }

        assertEquals(9, snapshots.size)
        assertEquals(9, snapshots.map { it.profileId }.distinct().size)
        assertEquals(9, snapshots.map { it.snapshotFingerprint }.distinct().size)
        assertEquals(1, snapshots.map { it.priorLogOddsMicros }.distinct().size)
        assertEquals(1, snapshots.map { it.positiveLogLikelihoodMicros }.distinct().size)
        assertEquals(1, snapshots.map { it.negativeLogLikelihoodMicros }.distinct().size)
        snapshots.forEach(LocalMasteryCalibrationRegistry::verify)
    }

    @Test
    fun registryRetainsEveryHistoricalProfileAndFingerprintsEveryParameter() {
        val snapshots = LocalMasteryCalibrationRegistry.allSnapshots()

        assertEquals(27, snapshots.size)
        assertEquals(
            setOf(
                LEARNER_MASTERY_OLDER_CALIBRATION_VERSION,
                LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION,
                LEARNER_MASTERY_CALIBRATION_VERSION,
            ),
            snapshots.map { it.calibrationVersion }.toSet(),
        )
        snapshots.forEach { snapshot ->
            assertEquals(
                snapshot,
                LocalMasteryCalibrationRegistry.resolve(
                    subject = snapshot.subject,
                    calibrationVersion = snapshot.calibrationVersion,
                    profileId = snapshot.profileId,
                    snapshotFingerprint = snapshot.snapshotFingerprint,
                ),
            )
            LocalMasteryCalibrationRegistry.verify(snapshot)
        }

        val altered = snapshots.first().copy(oneHintScaleMicros = 1L)
        assertNotNull(
            runCatching {
                LocalMasteryCalibrationRegistry.verify(altered)
            }.exceptionOrNull(),
        )
        assertNotNull(
            runCatching {
                LocalMasteryCalibrationRegistry.resolve(
                    subject = SUBJECT,
                    calibrationVersion = LEARNER_MASTERY_LEGACY_CALIBRATION_VERSION,
                    profileId = "calibration:math:legacy",
                    snapshotFingerprint = "f".repeat(64),
                )
            }.exceptionOrNull(),
        )
    }

    @Test
    fun historicalV2AndV3FingerprintsRemainFrozenWhenV4IsPublished() {
        val snapshots = LocalMasteryCalibrationRegistry.allSnapshots()

        FROZEN_HISTORICAL_FINGERPRINTS.forEach { (key, expectedFingerprint) ->
            val (version, subject) = key
            val snapshot =
                snapshots.single {
                    it.calibrationVersion == version && it.subject == subject
                }
            assertEquals(expectedFingerprint, snapshot.snapshotFingerprint)
            LocalMasteryCalibrationRegistry.verify(snapshot)
        }

        SubjectKind.entries
            .filterNot { it == SubjectKind.GENERAL }
            .forEach { subject ->
                val v3 =
                    snapshots.single {
                        it.subject == subject.name &&
                            it.calibrationVersion ==
                            LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION
                    }
                val v4 = LocalMasteryCalibrationRegistry.current(subject.name)
                assertNotEquals(v3.profileId, v4.profileId)
                assertNotEquals(v3.snapshotFingerprint, v4.snapshotFingerprint)
            }
    }

    @Test
    fun historicalV3AndCurrentV4LeasesDifferAcrossEveryReplayParameterFamily() {
        val historical = historicalCalibration()
        val current = LocalMasteryCalibrationRegistry.current(SUBJECT)

        assertNotEquals(historical.snapshotFingerprint, current.snapshotFingerprint)
        assertNotEquals(
            historical.modelReviewedMassMicros,
            current.modelReviewedMassMicros,
        )
        assertNotEquals(
            historical.presentationMassCapMicros,
            current.presentationMassCapMicros,
        )
        assertNotEquals(
            historical.problemFamilyMassCapMicros,
            current.problemFamilyMassCapMicros,
        )
        assertNotEquals(
            historical.positiveLogLikelihoodMicros,
            current.positiveLogLikelihoodMicros,
        )
        assertNotEquals(
            historical.negativeLogLikelihoodMicros,
            current.negativeLogLikelihoodMicros,
        )
        assertNotEquals(historical.initialStabilityMillis, current.initialStabilityMillis)
        assertNotEquals(
            historical.positiveStabilityGainMillis,
            current.positiveStabilityGainMillis,
        )
        assertNotEquals(
            historical.negativeStabilityScaleMicros,
            current.negativeStabilityScaleMicros,
        )
    }

    @Test
    fun snapshotFingerprintRejectsMutationOfEveryPersistedParameter() {
        val snapshot = LocalMasteryCalibrationRegistry.current(SUBJECT)
        val mutations:
            List<
                Pair<
                    String,
                    (MasteryCalibrationSnapshotEntity) -> MasteryCalibrationSnapshotEntity,
                >,
            > =
            listOf(
                "priorLogOddsMicros" to { it.copy(priorLogOddsMicros = it.priorLogOddsMicros + 1) },
                "positiveLogLikelihoodMicros" to {
                    it.copy(
                        positiveLogLikelihoodMicros =
                            it.positiveLogLikelihoodMicros + 1,
                    )
                },
                "negativeLogLikelihoodMicros" to {
                    it.copy(
                        negativeLogLikelihoodMicros =
                            it.negativeLogLikelihoodMicros + 1,
                    )
                },
                "steadyThresholdMicros" to {
                    it.copy(steadyThresholdMicros = it.steadyThresholdMicros + 1)
                },
                "reinforcementThresholdMicros" to {
                    it.copy(
                        reinforcementThresholdMicros =
                            it.reinforcementThresholdMicros + 1,
                    )
                },
                "steadyMinimumObservationCount" to {
                    it.copy(
                        steadyMinimumObservationCount =
                            it.steadyMinimumObservationCount + 1,
                    )
                },
                "materialScoreDeltaMicros" to {
                    it.copy(materialScoreDeltaMicros = it.materialScoreDeltaMicros + 1)
                },
                "maximumAbsoluteLogOddsMicros" to {
                    it.copy(
                        maximumAbsoluteLogOddsMicros =
                            it.maximumAbsoluteLogOddsMicros + 1,
                    )
                },
                "probabilityTransformVersion" to {
                    it.copy(
                        probabilityTransformVersion =
                            it.probabilityTransformVersion + 1,
                    )
                },
                "legacyProbabilityBridgeVersion" to {
                    it.copy(
                        legacyProbabilityBridgeVersion =
                            it.legacyProbabilityBridgeVersion + 1,
                    )
                },
                "initialStabilityMillis" to {
                    it.copy(initialStabilityMillis = it.initialStabilityMillis + 1)
                },
                "minimumStabilityMillis" to {
                    it.copy(minimumStabilityMillis = it.minimumStabilityMillis + 1)
                },
                "maximumStabilityMillis" to {
                    it.copy(maximumStabilityMillis = it.maximumStabilityMillis + 1)
                },
                "positiveStabilityGainMillis" to {
                    it.copy(
                        positiveStabilityGainMillis =
                            it.positiveStabilityGainMillis + 1,
                    )
                },
                "negativeStabilityScaleMicros" to {
                    it.copy(
                        negativeStabilityScaleMicros =
                            it.negativeStabilityScaleMicros + 1,
                    )
                },
                "recallHalfLifeScaleMicros" to {
                    it.copy(
                        recallHalfLifeScaleMicros =
                            it.recallHalfLifeScaleMicros + 1,
                    )
                },
                "presentationMassCapMicros" to {
                    it.copy(
                        presentationMassCapMicros =
                            it.presentationMassCapMicros + 1,
                    )
                },
                "problemFamilyMassCapMicros" to {
                    it.copy(
                        problemFamilyMassCapMicros =
                            it.problemFamilyMassCapMicros + 1,
                    )
                },
                "secondFamilyObservationScaleMicros" to {
                    it.copy(
                        secondFamilyObservationScaleMicros =
                            it.secondFamilyObservationScaleMicros + 1,
                    )
                },
                "repeatedFamilyObservationScaleMicros" to {
                    it.copy(
                        repeatedFamilyObservationScaleMicros =
                            it.repeatedFamilyObservationScaleMicros + 1,
                    )
                },
                "stableConflictFloorMillis" to {
                    it.copy(
                        stableConflictFloorMillis =
                            it.stableConflictFloorMillis + 1,
                    )
                },
                "localVerifiedMassMicros" to {
                    it.copy(localVerifiedMassMicros = it.localVerifiedMassMicros + 1)
                },
                "deterministicRubricMassMicros" to {
                    it.copy(
                        deterministicRubricMassMicros =
                            it.deterministicRubricMassMicros + 1,
                    )
                },
                "modelReviewedMassMicros" to {
                    it.copy(modelReviewedMassMicros = it.modelReviewedMassMicros + 1)
                },
                "oneHintScaleMicros" to {
                    it.copy(oneHintScaleMicros = it.oneHintScaleMicros + 1)
                },
                "multipleHintsScaleMicros" to {
                    it.copy(multipleHintsScaleMicros = it.multipleHintsScaleMicros + 1)
                },
                "unknownAssistanceScaleMicros" to {
                    it.copy(
                        unknownAssistanceScaleMicros =
                            it.unknownAssistanceScaleMicros + 1,
                    )
                },
                "oneRetryScaleMicros" to {
                    it.copy(oneRetryScaleMicros = it.oneRetryScaleMicros + 1)
                },
                "multipleRetriesScaleMicros" to {
                    it.copy(
                        multipleRetriesScaleMicros =
                            it.multipleRetriesScaleMicros + 1,
                    )
                },
                "modelAttributionCapMicros" to {
                    it.copy(
                        modelAttributionCapMicros =
                            it.modelAttributionCapMicros + 1,
                    )
                },
                "openResponseCapMicros" to {
                    it.copy(openResponseCapMicros = it.openResponseCapMicros + 1)
                },
            )

        assertEquals(31, mutations.size)
        mutations.forEach { (name, mutate) ->
            assertNotNull(
                "$name must participate in the canonical calibration fingerprint",
                runCatching {
                    LocalMasteryCalibrationRegistry.verify(mutate(snapshot))
                }.exceptionOrNull(),
            )
        }
    }

    @Test
    fun fixedPointLogOddsIsMonotonicAndDeterministicallyReplayable() {
        val attribution = attribution("event-positive")
        val event = event("event-positive", MasteryEventDirection.POSITIVE)

        val first =
            LocalMasteryPolicy.nextProjection(
                learnerId = LEARNER_ID,
                event = event,
                attribution = attribution,
                current = null,
            )
        val replay =
            LocalMasteryPolicy.nextProjection(
                learnerId = LEARNER_ID,
                event = event,
                attribution = attribution,
                current = null,
            )
        val negative =
            LocalMasteryPolicy.nextProjection(
                learnerId = LEARNER_ID,
                event = event("event-negative", MasteryEventDirection.NEGATIVE),
                attribution = attribution("event-negative"),
                current = null,
            )

        assertEquals(first, replay)
        assertTrue(first.masteryScoreMicros > 500_000L)
        assertTrue(negative.masteryScoreMicros < 500_000L)
        assertNotNull(first.historicalLogOddsMicros)
        assertEquals(
            LocalMasteryCalibrationRegistry.current(SUBJECT).snapshotFingerprint,
            first.calibrationSnapshotFingerprint,
        )
    }

    @Test
    fun recallTransitionsDoNotRewriteHistoricalMastery() {
        var projection: MasteryKnowledgeProjectionEntity? = null
        repeat(3) { index ->
            val id = "positive-$index"
            projection =
                LocalMasteryPolicy.nextProjection(
                    learnerId = LEARNER_ID,
                    event = event(id, MasteryEventDirection.POSITIVE, index + 1L),
                    attribution = attribution(id),
                    current = projection,
                )
        }
        val steady = requireNotNull(projection)
        assertEquals(KnowledgeMasteryState.STEADY.name, steady.masteryState)
        val familiarizingAt = requireNotNull(steady.recallFamiliarizingAtEpochMillis)
        val reinforcementAt = requireNotNull(steady.recallReinforcementAtEpochMillis)
        assertTrue(reinforcementAt > familiarizingAt)

        val before =
            LocalMasteryPolicy.recallProjectionState(
                steady,
                familiarizingAt - 1L,
            )
        val familiar =
            LocalMasteryPolicy.recallProjectionState(
                steady,
                familiarizingAt,
            )
        val needs =
            LocalMasteryPolicy.recallProjectionState(
                steady,
                reinforcementAt,
            )

        assertEquals(KnowledgeMasteryState.STEADY, before.currentState)
        assertEquals(familiarizingAt, before.nextTransitionAtEpochMillis)
        assertEquals(KnowledgeMasteryState.FAMILIARIZING, familiar.currentState)
        assertEquals(reinforcementAt, familiar.nextTransitionAtEpochMillis)
        assertEquals(KnowledgeMasteryState.NEEDS_REINFORCEMENT, needs.currentState)
        assertEquals(null, needs.nextTransitionAtEpochMillis)
        assertEquals(KnowledgeMasteryState.STEADY.name, steady.masteryState)
    }

    @Test
    fun mixedCalibrationReplayUsesEventAndProjectionBoundSnapshots() {
        val historical = historicalCalibration()
        val first =
            LocalMasteryPolicy.nextProjection(
                learnerId = LEARNER_ID,
                event =
                    event(
                        id = "historical-positive",
                        direction = MasteryEventDirection.POSITIVE,
                        calibration = historical,
                    ),
                attribution = attribution("historical-positive"),
                current = null,
            )
        assertEquals(
            LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION,
            first.calibrationVersion,
        )

        val latestEvent =
            event(
                id = "current-negative",
                direction = MasteryEventDirection.NEGATIVE,
                sequence = 2L,
            )
        val mixed =
            LocalMasteryPolicy.nextProjection(
                learnerId = LEARNER_ID,
                event = latestEvent,
                attribution = attribution(latestEvent.eventId),
                current = first,
            )

        assertEquals(LEARNER_MASTERY_CALIBRATION_VERSION, mixed.calibrationVersion)
        assertEquals(
            latestEvent.calibrationSnapshotFingerprint,
            mixed.calibrationSnapshotFingerprint,
        )
        assertNotNull(
            LocalMasteryPolicy.recallProjectionState(
                projection = mixed,
                nowEpochMillis = mixed.recallDueAtEpochMillis,
            ),
        )
        assertNotNull(
            LocalMasteryPolicy.recallProjectionState(
                projection = first,
                nowEpochMillis = first.recallDueAtEpochMillis,
            ),
        )
    }

    @Test
    fun mixedCalibrationLateArrivalAndCorrectionReplayRemainDeterministic() {
        val historical = historicalCalibration()
        val early =
            event(
                id = "early-historical",
                direction = MasteryEventDirection.NEGATIVE,
                sequence = 2L,
                occurredAtEpochMillis = 1_000L,
                calibration = historical,
            )
        val later =
            event(
                id = "later-current",
                direction = MasteryEventDirection.POSITIVE,
                sequence = 1L,
                occurredAtEpochMillis = 2_000L,
            )

        val chronological = replay(listOf(early, later))
        val lateArrival = replay(listOf(later, early))
        assertEquals(chronological, lateArrival)

        val correctedEarly =
            event(
                id = "corrected-current",
                direction = MasteryEventDirection.POSITIVE,
                sequence = 3L,
                occurredAtEpochMillis = early.occurredAtEpochMillis,
            )
        val correctedReplay = replay(listOf(correctedEarly, later))
        val reopenedReplay = replay(listOf(later, correctedEarly))
        assertEquals(correctedReplay, reopenedReplay)
        assertEquals(2_000_000L, correctedReplay.positiveEvidenceMicros)
        assertEquals(0L, correctedReplay.negativeEvidenceMicros)
    }

    @Test
    fun calibrationFromAnotherSubjectCannotReplayAnEvent() {
        val chemistry = LocalMasteryCalibrationRegistry.current(SubjectKind.CHEMISTRY.name)
        val mismatched =
            event("wrong-subject", MasteryEventDirection.POSITIVE).copy(
                calibrationProfileId = chemistry.profileId,
                calibrationSnapshotFingerprint = chemistry.snapshotFingerprint,
            )

        val failure =
            runCatching {
                LocalMasteryPolicy.nextProjection(
                    learnerId = LEARNER_ID,
                    event = mismatched,
                    attribution = attribution(mismatched.eventId),
                    current = null,
                )
            }.exceptionOrNull()
        assertNotNull(failure)
    }

    @Test
    fun frozenV2MassRatioStillUsesItsOriginalReplayFormula() {
        val old =
            event("legacy", MasteryEventDirection.POSITIVE).copy(
                projectionPolicyVersion =
                    LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION,
                calibrationVersion = LEARNER_MASTERY_LEGACY_CALIBRATION_VERSION,
                calibrationProfileId = null,
                calibrationSnapshotFingerprint = null,
            )
        val projected =
            LocalMasteryPolicy.nextProjection(
                learnerId = LEARNER_ID,
                event = old,
                attribution = attribution(old.eventId),
                current = null,
            )

        assertEquals(666_667L, projected.masteryScoreMicros)
        assertEquals(null, projected.historicalLogOddsMicros)
        assertNotEquals(
            LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
            projected.projectionPolicyVersion,
        )
    }

    private fun event(
        id: String,
        direction: MasteryEventDirection,
        sequence: Long = 1L,
        occurredAtEpochMillis: Long = sequence * 1_000L,
        calibration: MasteryCalibrationSnapshotEntity =
            LocalMasteryCalibrationRegistry.current(SUBJECT),
    ): MasteryLearningEventEntity {
        return MasteryLearningEventEntity(
            eventId = id,
            candidateId = "candidate-$id",
            sourceFactId = "source-$id",
            sourceProofFingerprint = "a".repeat(64),
            learnerId = LEARNER_ID,
            subject = SUBJECT,
            direction = direction.name,
            eventSequence = sequence,
            occurredAtEpochMillis = occurredAtEpochMillis,
            admittedAtEpochMillis = sequence * 1_000L,
            projectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
            admissionPolicyVersion = LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
            calibrationVersion = calibration.calibrationVersion,
            canonicalFingerprint = "b".repeat(64),
            calibrationSnapshotFingerprint = calibration.snapshotFingerprint,
            calibrationProfileId = calibration.profileId,
        )
    }

    private fun historicalCalibration(): MasteryCalibrationSnapshotEntity =
        LocalMasteryCalibrationRegistry.allSnapshots().single {
            it.subject == SUBJECT &&
                it.calibrationVersion == LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION
        }

    private fun replay(
        events: List<MasteryLearningEventEntity>,
    ): MasteryKnowledgeProjectionEntity =
        events
            .sortedWith(
                compareBy<MasteryLearningEventEntity>(
                    MasteryLearningEventEntity::occurredAtEpochMillis,
                    MasteryLearningEventEntity::eventId,
                ),
            ).fold<MasteryLearningEventEntity, MasteryKnowledgeProjectionEntity?>(
                null,
            ) { projection, event ->
                LocalMasteryPolicy.nextProjection(
                    learnerId = LEARNER_ID,
                    event = event,
                    attribution = attribution(event.eventId),
                    current = projection,
                )
            } ?: error("Calibration replay requires at least one event")

    private fun attribution(eventId: String): MasteryLearningEventAttributionEntity =
        MasteryLearningEventAttributionEntity(
            eventId = eventId,
            ordinal = 0,
            subject = SUBJECT,
            knowledgeNodeId = "math.calibration.node",
            taxonomyVersion = "taxonomy-v1",
            knowledgePackVersion = "pack-v1",
            knowledgeNodeRefFingerprint = "c".repeat(64),
            evidenceMassMicros = LocalMasteryPolicy.FULL_MASS_MICROS,
        )

    private companion object {
        const val LEARNER_ID = "learner-calibration-policy"
        const val SUBJECT = "MATH"

        val FROZEN_HISTORICAL_FINGERPRINTS =
            mapOf(
                (LEARNER_MASTERY_OLDER_CALIBRATION_VERSION to "CHINESE") to
                    "14ac1b3e8f6b374a83cbc411137f1a8dea32fe6b979386abe5a8891e25199fa8",
                (LEARNER_MASTERY_OLDER_CALIBRATION_VERSION to "MATH") to
                    "631eacc8c8bf45c07483a9caf5f4f452c992784c81c9cfd218e00b869ae9671f",
                (LEARNER_MASTERY_OLDER_CALIBRATION_VERSION to "ENGLISH") to
                    "ea1ae2bdbd6f653faee4690c1ca7eac041f001411235300d752c18d30c1cb94f",
                (LEARNER_MASTERY_OLDER_CALIBRATION_VERSION to "PHYSICS") to
                    "a9c00df379a10ada7e72c99d3661085b21a33c5276bfa6570a705e24e92fd848",
                (LEARNER_MASTERY_OLDER_CALIBRATION_VERSION to "CHEMISTRY") to
                    "86ab73b2161f01ebbe7a2edc90a5f873986756a1151bd83946f63290d930119d",
                (LEARNER_MASTERY_OLDER_CALIBRATION_VERSION to "BIOLOGY") to
                    "b6407d6ddf1a14972f06714744ba8fb1162c2a6b573fd7c2f9a3a9e25a2784e7",
                (LEARNER_MASTERY_OLDER_CALIBRATION_VERSION to "POLITICS") to
                    "505fa73db7b7b569caa8ea789f3e9103c814cfe5256d83ea3a378824da6fde7b",
                (LEARNER_MASTERY_OLDER_CALIBRATION_VERSION to "HISTORY") to
                    "0ea8063f5091348b9e2ab8c0edf0428a77f2323c8096c62aa2a039a0bc7c2bbb",
                (LEARNER_MASTERY_OLDER_CALIBRATION_VERSION to "GEOGRAPHY") to
                    "e82deb9a7fff4c7bdf316b11a798e6957dddadb651878946ef63decdb12a5453",
                (LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION to "CHINESE") to
                    "8150ad9928f489d32d101e0281eca5a7c483a8847014908b7846be59103cc6b1",
                (LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION to "MATH") to
                    "6a799884188492fac24eaed125946b8566b2e7e0670ecd04d312e34860dcf218",
                (LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION to "ENGLISH") to
                    "429e47be76c0188bf1a18361ab33ab7d7a6e3ca7fe5772bbc07f8bfdc1f87405",
                (LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION to "PHYSICS") to
                    "80c886675617c9862d8c3753e1426f4ddf89381c4e9a8972acfe179dac977479",
                (LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION to "CHEMISTRY") to
                    "08777c9a44a8e5c5c4f562b26c3ef577f3bdd1d7b68d31531d17e8489c5ec75b",
                (LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION to "BIOLOGY") to
                    "7631f0bfb96dfc72cbf75fcaafcd820a5b8e4752d832407475a904173b23fa17",
                (LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION to "POLITICS") to
                    "d0a32c1079c95059bdb54531ce80bdc6b584d695f0be1ae57d9c10c7371c8b35",
                (LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION to "HISTORY") to
                    "e6fee2514b71cd2134f6d23d14361a85788109bcf5625a7d32bb3a1f683d0d87",
                (LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION to "GEOGRAPHY") to
                    "80c07f08edbb1291311b935793011dc134d49da107b620378996e4f1dcc0719d",
            )
    }
}
