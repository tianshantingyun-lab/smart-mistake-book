package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind

/**
 * Immutable, subject-scoped conservative heuristic parameter registry.
 *
 * Every parameter used by projection or recall is part of the persisted snapshot and its
 * canonical fingerprint. Historical versions remain resolvable so replay never substitutes the
 * current profile for the profile bound to an event. The persisted `calibration_*` vocabulary is
 * retained for schema compatibility; these built-in profiles are not empirical release claims.
 */
internal object LocalMasteryCalibrationRegistry {
    data class Parameters(
        val priorLogOddsMicros: Long,
        val positiveLogLikelihoodMicros: Long,
        val negativeLogLikelihoodMicros: Long,
        val steadyThresholdMicros: Long,
        val reinforcementThresholdMicros: Long,
        val steadyMinimumObservationCount: Long,
        val materialScoreDeltaMicros: Long,
        val maximumAbsoluteLogOddsMicros: Long,
        val probabilityTransformVersion: Long,
        val legacyProbabilityBridgeVersion: Long,
        val initialStabilityMillis: Long,
        val minimumStabilityMillis: Long,
        val maximumStabilityMillis: Long,
        val positiveStabilityGainMillis: Long,
        val negativeStabilityScaleMicros: Long,
        val recallHalfLifeScaleMicros: Long,
        val presentationMassCapMicros: Long,
        val problemFamilyMassCapMicros: Long,
        val secondFamilyObservationScaleMicros: Long,
        val repeatedFamilyObservationScaleMicros: Long,
        val stableConflictFloorMillis: Long,
        val localVerifiedMassMicros: Long,
        val deterministicRubricMassMicros: Long,
        val modelReviewedMassMicros: Long,
        val oneHintScaleMicros: Long,
        val multipleHintsScaleMicros: Long,
        val unknownAssistanceScaleMicros: Long,
        val oneRetryScaleMicros: Long,
        val multipleRetriesScaleMicros: Long,
        val modelAttributionCapMicros: Long,
        val openResponseCapMicros: Long,
    )

    private data class Definition(
        val calibrationVersion: String,
        val fingerprintSchema: String,
        val parameters: Parameters,
    )

    private data class SnapshotKey(
        val subject: String,
        val calibrationVersion: String,
        val profileId: String,
        val snapshotFingerprint: String,
    )

    private val historicalConservativeParameters =
        Parameters(
            priorLogOddsMicros = 0L,
            positiveLogLikelihoodMicros = 1_250_000L,
            negativeLogLikelihoodMicros = 1_450_000L,
            steadyThresholdMicros = 720_000L,
            reinforcementThresholdMicros = 450_000L,
            steadyMinimumObservationCount = 3L,
            materialScoreDeltaMicros = 40_000L,
            maximumAbsoluteLogOddsMicros = 8_000_000L,
            probabilityTransformVersion = 1L,
            legacyProbabilityBridgeVersion = 1L,
            initialStabilityMillis = 24L * 60L * 60L * 1_000L,
            minimumStabilityMillis = 6L * 60L * 60L * 1_000L,
            maximumStabilityMillis = 180L * 24L * 60L * 60L * 1_000L,
            positiveStabilityGainMillis = 24L * 60L * 60L * 1_000L,
            negativeStabilityScaleMicros = 500_000L,
            recallHalfLifeScaleMicros = 1_000_000L,
            presentationMassCapMicros = 1_000_000L,
            problemFamilyMassCapMicros = 2_000_000L,
            secondFamilyObservationScaleMicros = 500_000L,
            repeatedFamilyObservationScaleMicros = 250_000L,
            stableConflictFloorMillis = 7L * 24L * 60L * 60L * 1_000L,
            localVerifiedMassMicros = 1_000_000L,
            deterministicRubricMassMicros = 800_000L,
            modelReviewedMassMicros = 350_000L,
            oneHintScaleMicros = 600_000L,
            multipleHintsScaleMicros = 350_000L,
            unknownAssistanceScaleMicros = 500_000L,
            oneRetryScaleMicros = 700_000L,
            multipleRetriesScaleMicros = 400_000L,
            modelAttributionCapMicros = 500_000L,
            openResponseCapMicros = 500_000L,
        )

    /*
     * New heuristic parameters always receive a new immutable parameter version. Historical v2
     * and v3 deliberately retain their original values and fingerprint schemas.
     */
    private val currentConservativeParameters =
        historicalConservativeParameters.copy(
            positiveLogLikelihoodMicros = 1_350_000L,
            negativeLogLikelihoodMicros = 1_550_000L,
            initialStabilityMillis = 30L * 60L * 60L * 1_000L,
            positiveStabilityGainMillis = 30L * 60L * 60L * 1_000L,
            negativeStabilityScaleMicros = 450_000L,
            presentationMassCapMicros = 900_000L,
            problemFamilyMassCapMicros = 1_750_000L,
            modelReviewedMassMicros = 300_000L,
        )

    private val definitions =
        listOf(
            Definition(
                calibrationVersion = LEARNER_MASTERY_OLDER_CALIBRATION_VERSION,
                fingerprintSchema = "learner-mastery-calibration-snapshot-v1",
                parameters = historicalConservativeParameters,
            ),
            Definition(
                calibrationVersion = LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION,
                fingerprintSchema = "learner-mastery-calibration-snapshot-v2",
                parameters = historicalConservativeParameters,
            ),
            Definition(
                calibrationVersion = LEARNER_MASTERY_CALIBRATION_VERSION,
                fingerprintSchema = "learner-mastery-calibration-snapshot-v3",
                parameters = currentConservativeParameters,
            ),
        )

    private val snapshots: List<MasteryCalibrationSnapshotEntity> =
        definitions.flatMap { definition ->
            SubjectKind.entries
                .filterNot { it == SubjectKind.GENERAL }
                .map { subject -> createSnapshot(subject, definition) }
        }

    private val byKey =
        snapshots.associateBy { snapshot ->
            SnapshotKey(
                subject = snapshot.subject,
                calibrationVersion = snapshot.calibrationVersion,
                profileId = snapshot.profileId,
                snapshotFingerprint = snapshot.snapshotFingerprint,
            )
        }

    private val currentBySubject =
        snapshots
            .filter { it.calibrationVersion == LEARNER_MASTERY_CALIBRATION_VERSION }
            .associateBy(MasteryCalibrationSnapshotEntity::subject)

    init {
        check(currentBySubject.size == 9) {
            "Learner-mastery heuristic parameters must cover all nine high-school subjects"
        }
        check(snapshots.size == definitions.size * currentBySubject.size)
        check(byKey.size == snapshots.size)
        check(snapshots.map { it.profileId }.distinct().size == snapshots.size)
        check(snapshots.map { it.snapshotFingerprint }.distinct().size == snapshots.size)
    }

    fun current(subject: String): MasteryCalibrationSnapshotEntity {
        requireCalibratedSubject(subject)
        return checkNotNull(currentBySubject[subject]) {
            "Missing current learner-mastery heuristic parameter snapshot"
        }
    }

    fun resolve(
        subject: String,
        calibrationVersion: String,
        profileId: String,
        snapshotFingerprint: String,
    ): MasteryCalibrationSnapshotEntity {
        requireCalibratedSubject(subject)
        requireMasteryVersion(calibrationVersion, "Heuristic parameter version")
        requireMasteryIdentity(profileId, "Heuristic parameter profile id")
        requireMasteryFingerprint(snapshotFingerprint, "Heuristic parameter snapshot fingerprint")
        return checkNotNull(
            find(
                subject = subject,
                calibrationVersion = calibrationVersion,
                profileId = profileId,
                snapshotFingerprint = snapshotFingerprint,
            ),
        ) {
            "Unsupported learner-mastery heuristic parameter snapshot"
        }
    }

    fun find(
        subject: String,
        calibrationVersion: String,
        profileId: String,
        snapshotFingerprint: String,
    ): MasteryCalibrationSnapshotEntity? =
        byKey[
            SnapshotKey(
                subject = subject,
                calibrationVersion = calibrationVersion,
                profileId = profileId,
                snapshotFingerprint = snapshotFingerprint,
            )
        ]

    fun verify(snapshot: MasteryCalibrationSnapshotEntity) {
        check(snapshot.snapshotFingerprint == fingerprint(snapshot)) {
            "Learner-mastery heuristic parameter snapshot fingerprint is invalid"
        }
        check(
            resolve(
                subject = snapshot.subject,
                calibrationVersion = snapshot.calibrationVersion,
                profileId = snapshot.profileId,
                snapshotFingerprint = snapshot.snapshotFingerprint,
            ) == snapshot,
        ) {
            "Learner-mastery heuristic parameter snapshot does not match the local registry"
        }
    }

    fun parameters(snapshot: MasteryCalibrationSnapshotEntity): Parameters {
        val resolved =
            resolve(
                subject = snapshot.subject,
                calibrationVersion = snapshot.calibrationVersion,
                profileId = snapshot.profileId,
                snapshotFingerprint = snapshot.snapshotFingerprint,
            )
        check(resolved == snapshot) {
            "Learner-mastery heuristic parameter snapshot was altered"
        }
        return resolved.toParameters()
    }

    fun allSnapshots(): List<MasteryCalibrationSnapshotEntity> = snapshots.toList()

    private fun createSnapshot(
        subject: SubjectKind,
        definition: Definition,
    ): MasteryCalibrationSnapshotEntity {
        val parameters = definition.parameters
        val withoutFingerprint =
            MasteryCalibrationSnapshotEntity(
                subject = subject.name,
                profileId =
                    "calibration:${subject.name.lowercase()}:" +
                        definition.calibrationVersion,
                calibrationVersion = definition.calibrationVersion,
                projectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
                priorLogOddsMicros = parameters.priorLogOddsMicros,
                positiveLogLikelihoodMicros = parameters.positiveLogLikelihoodMicros,
                negativeLogLikelihoodMicros = parameters.negativeLogLikelihoodMicros,
                steadyThresholdMicros = parameters.steadyThresholdMicros,
                reinforcementThresholdMicros = parameters.reinforcementThresholdMicros,
                steadyMinimumObservationCount = parameters.steadyMinimumObservationCount,
                materialScoreDeltaMicros = parameters.materialScoreDeltaMicros,
                maximumAbsoluteLogOddsMicros = parameters.maximumAbsoluteLogOddsMicros,
                probabilityTransformVersion = parameters.probabilityTransformVersion,
                legacyProbabilityBridgeVersion = parameters.legacyProbabilityBridgeVersion,
                initialStabilityMillis = parameters.initialStabilityMillis,
                minimumStabilityMillis = parameters.minimumStabilityMillis,
                maximumStabilityMillis = parameters.maximumStabilityMillis,
                positiveStabilityGainMillis = parameters.positiveStabilityGainMillis,
                negativeStabilityScaleMicros = parameters.negativeStabilityScaleMicros,
                recallHalfLifeScaleMicros = parameters.recallHalfLifeScaleMicros,
                presentationMassCapMicros = parameters.presentationMassCapMicros,
                problemFamilyMassCapMicros = parameters.problemFamilyMassCapMicros,
                secondFamilyObservationScaleMicros =
                    parameters.secondFamilyObservationScaleMicros,
                repeatedFamilyObservationScaleMicros =
                    parameters.repeatedFamilyObservationScaleMicros,
                stableConflictFloorMillis = parameters.stableConflictFloorMillis,
                localVerifiedMassMicros = parameters.localVerifiedMassMicros,
                deterministicRubricMassMicros = parameters.deterministicRubricMassMicros,
                modelReviewedMassMicros = parameters.modelReviewedMassMicros,
                oneHintScaleMicros = parameters.oneHintScaleMicros,
                multipleHintsScaleMicros = parameters.multipleHintsScaleMicros,
                unknownAssistanceScaleMicros = parameters.unknownAssistanceScaleMicros,
                oneRetryScaleMicros = parameters.oneRetryScaleMicros,
                multipleRetriesScaleMicros = parameters.multipleRetriesScaleMicros,
                modelAttributionCapMicros = parameters.modelAttributionCapMicros,
                openResponseCapMicros = parameters.openResponseCapMicros,
                snapshotFingerprint = CALIBRATION_FINGERPRINT_PLACEHOLDER,
            )
        return withoutFingerprint.copy(
            snapshotFingerprint =
                fingerprint(
                    snapshot = withoutFingerprint,
                    fingerprintSchema = definition.fingerprintSchema,
                ),
        )
    }

    private fun fingerprint(snapshot: MasteryCalibrationSnapshotEntity): String {
        val definition =
            checkNotNull(
                definitions.singleOrNull {
                    it.calibrationVersion == snapshot.calibrationVersion
                },
            ) {
                "Unsupported learner-mastery heuristic parameter version"
            }
        return fingerprint(snapshot, definition.fingerprintSchema)
    }

    private fun fingerprint(
        snapshot: MasteryCalibrationSnapshotEntity,
        fingerprintSchema: String,
    ): String =
        CanonicalSha256(fingerprintSchema)
            .field("subject", snapshot.subject)
            .field("profileId", snapshot.profileId)
            .field("calibrationVersion", snapshot.calibrationVersion)
            .field("projectionPolicyVersion", snapshot.projectionPolicyVersion)
            .field("priorLogOddsMicros", snapshot.priorLogOddsMicros)
            .field(
                "positiveLogLikelihoodMicros",
                snapshot.positiveLogLikelihoodMicros,
            )
            .field(
                "negativeLogLikelihoodMicros",
                snapshot.negativeLogLikelihoodMicros,
            )
            .field("steadyThresholdMicros", snapshot.steadyThresholdMicros)
            .field(
                "reinforcementThresholdMicros",
                snapshot.reinforcementThresholdMicros,
            )
            .field(
                "recallHalfLifeScaleMicros",
                snapshot.recallHalfLifeScaleMicros,
            )
            .field("materialScoreDeltaMicros", snapshot.materialScoreDeltaMicros)
            .field(
                "steadyMinimumObservationCount",
                snapshot.steadyMinimumObservationCount,
            )
            .field(
                "maximumAbsoluteLogOddsMicros",
                snapshot.maximumAbsoluteLogOddsMicros,
            )
            .field(
                "probabilityTransformVersion",
                snapshot.probabilityTransformVersion,
            )
            .field(
                "legacyProbabilityBridgeVersion",
                snapshot.legacyProbabilityBridgeVersion,
            )
            .field("initialStabilityMillis", snapshot.initialStabilityMillis)
            .field("minimumStabilityMillis", snapshot.minimumStabilityMillis)
            .field("maximumStabilityMillis", snapshot.maximumStabilityMillis)
            .field(
                "positiveStabilityGainMillis",
                snapshot.positiveStabilityGainMillis,
            )
            .field(
                "negativeStabilityScaleMicros",
                snapshot.negativeStabilityScaleMicros,
            )
            .field(
                "presentationMassCapMicros",
                snapshot.presentationMassCapMicros,
            )
            .field(
                "problemFamilyMassCapMicros",
                snapshot.problemFamilyMassCapMicros,
            )
            .field(
                "secondFamilyObservationScaleMicros",
                snapshot.secondFamilyObservationScaleMicros,
            )
            .field(
                "repeatedFamilyObservationScaleMicros",
                snapshot.repeatedFamilyObservationScaleMicros,
            )
            .field(
                "stableConflictFloorMillis",
                snapshot.stableConflictFloorMillis,
            )
            .field("localVerifiedMassMicros", snapshot.localVerifiedMassMicros)
            .field(
                "deterministicRubricMassMicros",
                snapshot.deterministicRubricMassMicros,
            )
            .field("modelReviewedMassMicros", snapshot.modelReviewedMassMicros)
            .field("oneHintScaleMicros", snapshot.oneHintScaleMicros)
            .field("multipleHintsScaleMicros", snapshot.multipleHintsScaleMicros)
            .field(
                "unknownAssistanceScaleMicros",
                snapshot.unknownAssistanceScaleMicros,
            )
            .field("oneRetryScaleMicros", snapshot.oneRetryScaleMicros)
            .field(
                "multipleRetriesScaleMicros",
                snapshot.multipleRetriesScaleMicros,
            )
            .field(
                "modelAttributionCapMicros",
                snapshot.modelAttributionCapMicros,
            )
            .field("openResponseCapMicros", snapshot.openResponseCapMicros)
            .finish()

    private fun MasteryCalibrationSnapshotEntity.toParameters(): Parameters =
        Parameters(
            priorLogOddsMicros = priorLogOddsMicros,
            positiveLogLikelihoodMicros = positiveLogLikelihoodMicros,
            negativeLogLikelihoodMicros = negativeLogLikelihoodMicros,
            steadyThresholdMicros = steadyThresholdMicros,
            reinforcementThresholdMicros = reinforcementThresholdMicros,
            steadyMinimumObservationCount = steadyMinimumObservationCount,
            materialScoreDeltaMicros = materialScoreDeltaMicros,
            maximumAbsoluteLogOddsMicros = maximumAbsoluteLogOddsMicros,
            probabilityTransformVersion = probabilityTransformVersion,
            legacyProbabilityBridgeVersion = legacyProbabilityBridgeVersion,
            initialStabilityMillis = initialStabilityMillis,
            minimumStabilityMillis = minimumStabilityMillis,
            maximumStabilityMillis = maximumStabilityMillis,
            positiveStabilityGainMillis = positiveStabilityGainMillis,
            negativeStabilityScaleMicros = negativeStabilityScaleMicros,
            recallHalfLifeScaleMicros = recallHalfLifeScaleMicros,
            presentationMassCapMicros = presentationMassCapMicros,
            problemFamilyMassCapMicros = problemFamilyMassCapMicros,
            secondFamilyObservationScaleMicros = secondFamilyObservationScaleMicros,
            repeatedFamilyObservationScaleMicros = repeatedFamilyObservationScaleMicros,
            stableConflictFloorMillis = stableConflictFloorMillis,
            localVerifiedMassMicros = localVerifiedMassMicros,
            deterministicRubricMassMicros = deterministicRubricMassMicros,
            modelReviewedMassMicros = modelReviewedMassMicros,
            oneHintScaleMicros = oneHintScaleMicros,
            multipleHintsScaleMicros = multipleHintsScaleMicros,
            unknownAssistanceScaleMicros = unknownAssistanceScaleMicros,
            oneRetryScaleMicros = oneRetryScaleMicros,
            multipleRetriesScaleMicros = multipleRetriesScaleMicros,
            modelAttributionCapMicros = modelAttributionCapMicros,
            openResponseCapMicros = openResponseCapMicros,
        )

    private fun requireCalibratedSubject(subject: String) {
        val parsed =
            runCatching { SubjectKind.valueOf(subject) }
                .getOrElse { error("Unknown learner-mastery heuristic parameter subject") }
        check(parsed != SubjectKind.GENERAL) {
            "General subject cannot use learner-mastery heuristic parameters"
        }
    }

    private const val CALIBRATION_FINGERPRINT_PLACEHOLDER =
        "0000000000000000000000000000000000000000000000000000000000000000"
}
