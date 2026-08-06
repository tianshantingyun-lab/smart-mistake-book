package com.tingyun.smartmistakebook.core.mastery.database

import android.annotation.SuppressLint
import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import com.tingyun.smartmistakebook.core.model.NoopProjectionWorkloadGate
import com.tingyun.smartmistakebook.core.model.ProjectionWorkloadGate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofVerifier
import java.util.function.LongSupplier
import kotlin.jvm.JvmSynthetic
import kotlinx.coroutines.runBlocking

internal const val LEARNER_MASTERY_DATABASE_VERSION = 22

internal val LEARNER_MASTERY_MIGRATIONS: List<Migration> =
    listOf(
        LEARNER_MASTERY_MIGRATION_1_2,
        LEARNER_MASTERY_MIGRATION_2_3,
        LEARNER_MASTERY_MIGRATION_3_4,
        LEARNER_MASTERY_MIGRATION_4_5,
        LEARNER_MASTERY_MIGRATION_5_6,
        LEARNER_MASTERY_MIGRATION_6_7,
        LEARNER_MASTERY_MIGRATION_7_8,
        LEARNER_MASTERY_MIGRATION_8_9,
        LEARNER_MASTERY_MIGRATION_9_10,
        LEARNER_MASTERY_MIGRATION_10_11,
        LEARNER_MASTERY_MIGRATION_11_12,
        LEARNER_MASTERY_MIGRATION_12_13,
        LEARNER_MASTERY_MIGRATION_13_14,
        LEARNER_MASTERY_MIGRATION_14_15,
        LEARNER_MASTERY_MIGRATION_15_16,
        LEARNER_MASTERY_MIGRATION_16_17,
        LEARNER_MASTERY_MIGRATION_17_18,
        LEARNER_MASTERY_MIGRATION_18_19,
        LEARNER_MASTERY_MIGRATION_19_20,
        LEARNER_MASTERY_MIGRATION_20_21,
        LEARNER_MASTERY_MIGRATION_21_22,
    )

@Database(
    entities = [
        MasterySourceFactEntity::class,
        MasterySourceProofEntity::class,
        MasteryProblemBindingAuthorityEntity::class,
        MasteryProblemBindingAuthorityStateEntity::class,
        MasteryObservationCandidateEntity::class,
        MasteryCandidateAttributionEntity::class,
        MasteryAdmissionReceiptEntity::class,
        MasteryModelSubmissionAttemptReceiptEntity::class,
        MasteryOpenResponseWeakCandidateReceiptEntity::class,
        MasteryOpenResponseModelEvaluationAttestationEntity::class,
        MasteryOpenResponseEvaluationKnowledgeScopeEntity::class,
        MasteryOpenResponseDedicatedDecisionEntity::class,
        MasteryOpenResponseLegacyQuarantineEntity::class,
        MasteryEvidenceReviewCaseEntity::class,
        MasteryEvidenceReviewResolutionEntity::class,
        MasteryLegacyEvidenceReviewResolutionAuditEntity::class,
        MasteryCalibrationSnapshotEntity::class,
        MasteryLearningEventEntity::class,
        MasteryLearningEvidenceSupersessionEntity::class,
        MasteryLearningEventAttributionEntity::class,
        MasteryAppliedEventEntity::class,
        MasteryKnowledgeProjectionEntity::class,
        MasterySubjectDigestEntity::class,
        MasteryPresentationNodeBudgetEntity::class,
        MasteryProblemFamilyNodeBudgetEntity::class,
        MasteryProjectionGenerationEntity::class,
        ProjectionBudgetRebuildReceiptEntity::class,
        DirectionalBudgetMigrationReceiptEntity::class,
        MasteryProjectionShadowEntity::class,
        MasterySubjectDigestShadowEntity::class,
        MasteryPresentationNodeBudgetShadowEntity::class,
        MasteryProblemFamilyNodeBudgetShadowEntity::class,
        MasteryProjectionInputFactEntity::class,
        MasteryProjectionInputCoverageGapEntity::class,
        MasteryCalibrationReleaseEntity::class,
        MasteryCalibrationProfileHeaderEntity::class,
        MasteryCalibrationValidationMetricEntity::class,
        MasteryCrossStoreInboxEntity::class,
        MasteryCrossStoreOutboxEntity::class,
        MasteryStudentRelaySourceBindingEntity::class,
        MasteryAuthenticatedStudentInboxReceiptEntity::class,
        MasteryPreAuthStudentInboxQuarantineEntity::class,
        MasteryOutboxAuthenticityKeyStateEntity::class,
        MasteryPreAuthOutboxQuarantineEntity::class,
        MasteryTaxonomyLineageDecisionEntity::class,
        MasteryStoreMetadataEntity::class,
        MasteryLedgerSequenceEntity::class,
        MasteryLegacyFactMigrationCheckpointEntity::class,
        LearnerMasteryCutoverFenceEntity::class,
        LearnerMasteryCutoverCompletionReceiptEntity::class,
        LearnerMasteryMigrationDestinationRecordEntity::class,
        LearnerMasteryLegacySnapshotPageEntity::class,
        LearnerMasteryLegacyObservationSnapshotEntity::class,
    ],
    version = LEARNER_MASTERY_DATABASE_VERSION,
    exportSchema = true,
)
internal abstract class LearnerMasteryRoomDatabase : RoomDatabase() {
    abstract fun masteryDao(): LearnerMasteryDao
    abstract fun openResponseDao(): LearnerMasteryOpenResponseDao
    abstract fun calibrationDao(): LearnerMasteryCalibrationDao
    abstract fun crossStoreDao(): LearnerMasteryCrossStoreDao
    abstract fun shadowBudgetDao(): LearnerMasteryShadowBudgetDao
    abstract fun projectionGenerationDao(): LearnerMasteryProjectionGenerationDao
    abstract fun subjectDigestDao(): LearnerMasterySubjectDigestDao

    abstract fun projectionRebuildDao(): LearnerMasteryProjectionRebuildDao

    abstract fun displayDao(): LearnerMasteryDisplayDao

    abstract fun cutoverDao(): LearnerMasteryCutoverDao

    abstract fun cutoverAttestationDao():
        LearnerMasteryCutoverDestinationAttestationDao
}

/**
 * Opens the production authority runtime.
 *
 * Cross-store delivery is exposed only through the learner-bound relay capability retained by
 * core:data. Model access can only be derived from the returned, already-open runtime.
 */
internal object LearnerMasteryAuthorityFactory {
    @JvmSynthetic
    fun open(
        context: Context,
        learnerId: String,
        knowledgeReferenceVerifier: KnowledgeReferenceProofVerifier,
        projectionWorkloadGate: ProjectionWorkloadGate =
            NoopProjectionWorkloadGate,
    ): LearnerMasteryAuthority {
        requireMasteryIdentity(learnerId, "Learner id")
        val authenticatorSession =
            MasteryOutboxAuthenticatorSession(
                learnerId,
                AndroidKeystoreMasteryOutboxHmacKeyStore.INSTANCE,
            )
        val store =
            RoomLearnerMasteryStore(
                database = openProductionLearnerMasteryDatabase(context),
                nowEpochMillis = System::currentTimeMillis,
                outboxAuthenticatorSession = authenticatorSession,
                reopenDatabase = { openProductionLearnerMasteryDatabase(context) },
                projectionWorkloadGate = projectionWorkloadGate,
            )
        return RoomLearnerMasteryAuthority(
            store = store,
            learnerId = learnerId,
            nowEpochMillis = System::currentTimeMillis,
            knowledgeReferenceVerifier = knowledgeReferenceVerifier,
            outboxAuthenticityVerifier = authenticatorSession.verifier,
        )
    }
}

internal object LearnerMasteryRuntimeFactory {
    fun open(
        context: Context,
        learnerId: String,
        knowledgeReferenceVerifier: KnowledgeReferenceProofVerifier,
        ownerKey: LearnerMasteryOwnerKey,
        databaseOwnerSeal: Any,
        projectionWorkloadGate: ProjectionWorkloadGate =
            NoopProjectionWorkloadGate,
    ): LearnerMasteryRuntimeCapabilities {
        check(ownerKey === LearnerMasteryOwnerKey.INSTANCE) {
            "Learner-mastery runtime requires the core:data owner key"
        }
        return assembleLearnerMasteryRuntimeCapabilities(
            authority =
                LearnerMasteryAuthorityFactory.open(
                    context = context,
                    learnerId = learnerId,
                    knowledgeReferenceVerifier = knowledgeReferenceVerifier,
                    projectionWorkloadGate = projectionWorkloadGate,
                ),
            databaseOwnerSeal = databaseOwnerSeal,
        )
    }
}

internal object LearnerMasteryLegacyMigrationPortFactory {
    fun open(
        context: Context,
        learnerId: String,
        ownerKey: LearnerMasteryOwnerKey,
    ): LearnerMasteryLegacyMigrationPort {
        check(ownerKey === LearnerMasteryOwnerKey.INSTANCE) {
            "Learner-mastery legacy migration requires the core:data owner key"
        }
        require(learnerId == LOCAL_LEARNER_ID) {
            "Legacy mastery migration is defined only for the fixed local learner"
        }
        return RoomLearnerMasteryLegacyMigrationPort(
            database = openProductionLearnerMasteryDatabase(context),
            learnerId = learnerId,
        )
    }
}

internal object LearnerMasteryCutoverControlPortFactory {
    fun open(
        context: Context,
        learnerId: String,
        ownerKey: LearnerMasteryOwnerKey,
    ): LearnerMasteryCutoverControlPort {
        check(ownerKey === LearnerMasteryOwnerKey.INSTANCE) {
            "Learner-mastery cutover control requires the core:data owner key"
        }
        require(learnerId == LOCAL_LEARNER_ID) {
            "Learner-mastery cutover is defined only for the device's fixed local learner"
        }
        return RoomLearnerMasteryCutoverControlPort(
            database = openProductionLearnerMasteryDatabase(context),
            learnerId = learnerId,
        )
    }
}

internal object LearnerMasteryCutoverDestinationAttestationPortFactory {
    fun open(
        context: Context,
        ownerKey: LearnerMasteryOwnerKey,
    ): LearnerMasteryCutoverDestinationAttestationPorts =
        open(
            context = context,
            ownerKey = ownerKey,
            nowEpochMillis = LongSupplier { System.currentTimeMillis() },
        )

    fun open(
        context: Context,
        ownerKey: LearnerMasteryOwnerKey,
        nowEpochMillis: LongSupplier,
    ): LearnerMasteryCutoverDestinationAttestationPorts {
        check(ownerKey === LearnerMasteryOwnerKey.INSTANCE) {
            "Mastery destination attestations require the core:data owner key"
        }
        return LearnerMasteryCutoverDestinationAttestationEngine(
            source =
                RoomLearnerMasteryCutoverDestinationReadSource(
                    openProductionLearnerMasteryDatabase(context),
                ),
            nowEpochMillis = nowEpochMillis,
            ownerKey = ownerKey,
        )
    }
}

internal object OpenResponseWeakCandidateOwnerFactory {
    fun open(
        context: Context,
        learnerId: String,
        ownerSeal: Any,
    ): RoomOpenResponseWeakCandidateOwner {
        CoreDataLearnerMasteryOwnerBridge.requireOpenResponseOwnerSeal(ownerSeal)
        requireMasteryIdentity(learnerId, "Open-response owner learner id")
        return RoomOpenResponseWeakCandidateOwner(
            database = openProductionLearnerMasteryDatabase(context),
            learnerId = learnerId,
            nowEpochMillis = System::currentTimeMillis,
            ownerSeal = ownerSeal,
        )
    }
}

@SuppressLint("RestrictedApi")
private fun openProductionLearnerMasteryDatabase(
    context: Context,
): LearnerMasteryRoomDatabase {
    val applicationContext = context.applicationContext
    var preOpen =
        inspectLearnerMasteryDatabaseBeforeRoomOpen(
            applicationContext,
            LEARNER_MASTERY_DATABASE_NAME,
        )
    if (preOpen.responseSummaryHygienePending) {
        runLearnerMasteryResponseSummaryHygieneExclusive(
            applicationContext,
            LEARNER_MASTERY_DATABASE_NAME,
        )
        preOpen =
            inspectLearnerMasteryDatabaseBeforeRoomOpen(
                applicationContext,
                LEARNER_MASTERY_DATABASE_NAME,
            )
    }
    val requiresPostMigrationHygiene =
        preOpen.version in 1 until LEARNER_MASTERY_DATABASE_VERSION

    fun buildDatabase(): LearnerMasteryRoomDatabase =
        Room.databaseBuilder(
            applicationContext,
            LearnerMasteryRoomDatabase::class.java,
            LEARNER_MASTERY_DATABASE_NAME,
        ).addMigrations(*LEARNER_MASTERY_MIGRATIONS.toTypedArray())
            .addCallback(LEARNER_MASTERY_DATABASE_GUARD_CALLBACK)
            .setDriver(AndroidSQLiteDriver())
            .build()

    var database = buildDatabase()
    if (!requiresPostMigrationHygiene) return database
    try {
        runBlocking {
            database.useConnection(isReadOnly = false) { Unit }
        }
        database.close()
        runLearnerMasteryResponseSummaryHygieneExclusive(
            applicationContext,
            LEARNER_MASTERY_DATABASE_NAME,
        )
        database = buildDatabase()
        return database
    } catch (failure: Throwable) {
        database.close()
        throw failure
    }
}

internal val LEARNER_MASTERY_DATABASE_GUARD_CALLBACK: RoomDatabase.Callback =
    object : RoomDatabase.Callback() {
        override suspend fun onCreate(connection: SQLiteConnection) {
            installLearnerMasteryDatabaseGuards(connection)
            prepareMasteryOutboxAuthenticity(connection)
        }

        override suspend fun onOpen(connection: SQLiteConnection) {
            installLearnerMasteryDatabaseGuards(connection)
            prepareMasteryOutboxAuthenticity(connection)
        }
    }

private fun installLearnerMasteryDatabaseGuards(connection: SQLiteConnection) {
    persistLearnerMasteryCalibrationRegistry(connection)
    installLearnerMasteryCalibrationBindingGuards(connection)
    installLearnerMasteryNextCalibrationGuards(connection)
    installLearnerMasteryImmutableLedgerGuards(connection)
}

internal fun installLearnerMasteryCalibrationBindingGuards(connection: SQLiteConnection) {
    val schemaVersion = readLearnerMasterySchemaVersion(connection)
    if (schemaVersion != LEARNER_MASTERY_DATABASE_VERSION) {
        replaceAndFullyAuditLearnerMasteryCalibrationBindingGuards(connection)
        return
    }

    requireLearnerMasteryForeignKeys(connection)
    val immutableDefinitions = learnerMasteryImmutableLedgerTriggerDefinitions()
    val guardFingerprint =
        learnerMasteryCalibrationAuditGuardFingerprint(connection, immutableDefinitions)
    val bindingGuardsCanonical = hasCanonicalLearnerMasteryCalibrationBindingTriggers(connection)
    val immutableGuardsCanonical =
        hasCanonicalLearnerMasteryTriggerDefinitions(connection, immutableDefinitions)
    if (bindingGuardsCanonical && immutableGuardsCanonical) {
        val current =
            readLearnerMasteryCalibrationAuditState(
                connection = connection,
                guardFingerprint = guardFingerprint,
            )
        val previous = readLatestLearnerMasteryCalibrationAuditWatermark(connection)
        if (previous?.exactlyMatches(current) == true) {
            return
        }
    }

    withLearnerMasterySchemaInstallTransaction(
        connection = connection,
        savepointName = CALIBRATION_BINDING_GUARD_INSTALL_SAVEPOINT,
    ) {
        val bindingGuardsWereCanonical =
            hasCanonicalLearnerMasteryCalibrationBindingTriggers(connection)
        val immutableGuardsWereCanonical =
            hasCanonicalLearnerMasteryTriggerDefinitions(connection, immutableDefinitions)
        if (!bindingGuardsWereCanonical) {
            dropLearnerMasteryCalibrationBindingTriggers(connection)
            createLearnerMasteryCalibrationBindingTriggers(connection)
            verifyLearnerMasteryCalibrationBindingTriggers(connection)
        }

        val current =
            readLearnerMasteryCalibrationAuditState(
                connection = connection,
                guardFingerprint = guardFingerprint,
            )
        val storedPrevious = readLatestLearnerMasteryCalibrationAuditWatermark(connection)
        val storedAuditRevision = readLearnerMasteryCalibrationAuditRevision(connection) ?: 0L
        val trustedPrevious =
            if (bindingGuardsWereCanonical && immutableGuardsWereCanonical) {
                storedPrevious
            } else {
                null
            }
        val transition =
            if (trustedPrevious?.canIncrementallyAdvanceTo(current) == true) {
                auditLearnerMasteryCalibrationBindingsIncrementally(
                    connection,
                    trustedPrevious,
                )
                LearnerMasteryCalibrationAuditTransition.INCREMENTAL
            } else {
                auditLearnerMasteryCalibrationBindings(connection)
                LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET
            }
        persistLearnerMasteryCalibrationAuditWatermark(
            connection = connection,
            watermark =
                readLearnerMasteryCalibrationAuditState(
                    connection = connection,
                    guardFingerprint = guardFingerprint,
                ).forPersistence(
                    previous = storedPrevious,
                    nextTransition = transition,
                    previousAuditRevision = storedAuditRevision,
                ),
        )
    }
}

private fun replaceAndFullyAuditLearnerMasteryCalibrationBindingGuards(
    connection: SQLiteConnection,
) {
    withLearnerMasterySchemaInstallTransaction(
        connection = connection,
        savepointName = CALIBRATION_BINDING_GUARD_INSTALL_SAVEPOINT,
    ) {
        dropLearnerMasteryCalibrationBindingTriggers(connection)
        createLearnerMasteryCalibrationBindingTriggers(connection)
        verifyLearnerMasteryCalibrationBindingTriggers(connection)
    }
}

private fun learnerMasteryCalibrationAuditGuardFingerprint(
    connection: SQLiteConnection,
    immutableDefinitions: List<LearnerMasteryTriggerDefinition>,
): String {
    val bindingDefinitions = calibrationBindingTriggerDefinitions()
    val digest =
        CanonicalSha256("learner-mastery-calibration-audit-guard-v1")
            .field("protocolVersion", LEARNER_MASTERY_CALIBRATION_AUDIT_PROTOCOL)
            .field("schemaVersion", LEARNER_MASTERY_DATABASE_VERSION)
            .field(
                "openResponseProofState",
                learnerMasteryOpenResponseProofStateFingerprint(connection),
            )
            .field("bindingDefinitionCount", bindingDefinitions.size)
            .field("immutableDefinitionCount", immutableDefinitions.size)
    bindingDefinitions.forEachIndexed { index, definition ->
        digest.field("bindingName[$index]", definition.name)
        digest.field(
            "bindingSql[$index]",
            canonicalizeLearnerMasterySql(definition.sql()),
        )
    }
    immutableDefinitions.forEachIndexed { index, definition ->
        digest.field("immutableName[$index]", definition.name)
        digest.field(
            "immutableSql[$index]",
            canonicalizeLearnerMasterySql(definition.sql),
        )
    }
    return digest.finish()
}

private fun hasCanonicalLearnerMasteryCalibrationBindingTriggers(
    connection: SQLiteConnection,
): Boolean =
    calibrationBindingTriggerDefinitions().all { definition ->
        connection.prepare(
            """
            SELECT sql
            FROM sqlite_schema
            WHERE type = 'trigger' AND name = ?
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, definition.name)
            statement.step() &&
                canonicalizeLearnerMasterySql(statement.getText(0)) ==
                canonicalizeLearnerMasterySql(definition.sql())
        }
    }

internal fun dropLearnerMasteryCalibrationBindingTriggers(connection: SQLiteConnection) {
    calibrationBindingTriggerDefinitions().forEach { definition ->
        connection.execSQL("DROP TRIGGER IF EXISTS ${definition.name}")
    }
}

private fun createLearnerMasteryCalibrationBindingTriggers(connection: SQLiteConnection) {
    calibrationBindingTriggerDefinitions().forEach { definition ->
        connection.execSQL(definition.sql())
    }
}

private fun verifyLearnerMasteryCalibrationBindingTriggers(connection: SQLiteConnection) {
    calibrationBindingTriggerDefinitions().forEach { definition ->
        connection.prepare(
            """
            SELECT sql
            FROM sqlite_schema
            WHERE type = 'trigger' AND name = ?
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, definition.name)
            check(statement.step()) {
                "Learner-mastery calibration binding guard ${definition.name} was not installed"
            }
            check(
                canonicalizeLearnerMasterySql(statement.getText(0)) ==
                    canonicalizeLearnerMasterySql(definition.sql()),
            ) {
                "Learner-mastery calibration binding guard ${definition.name} is not canonical"
            }
        }
    }
}

internal fun auditLearnerMasteryCalibrationBindings(connection: SQLiteConnection) {
    check(
        !connection.hasRow(
            """
            SELECT 1
            FROM mastery_evidence_review_case AS review_case
            WHERE NOT (
                ${VALID_REVIEW_CASE_CALIBRATION_BINDING.replace("NEW.", "review_case.")}
            )
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "Learner-mastery review case has an invalid calibration binding"
    }
    check(
        !connection.hasRow(
            """
            SELECT 1
            FROM mastery_learning_event AS learning_event
            WHERE NOT (
                ${VALID_LEARNING_EVENT_CALIBRATION_BINDING.replace("NEW.", "learning_event.")}
            )
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "Learner-mastery event has an invalid calibration binding"
    }
    check(
        !connection.hasRow(
            """
            SELECT 1
            FROM mastery_knowledge_projection AS projection
            WHERE NOT (
                ${VALID_KNOWLEDGE_PROJECTION_CALIBRATION_BINDING.replace("NEW.", "projection.")}
            )
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "Learner-mastery projection has an invalid calibration binding"
    }
    check(
        !connection.hasRow(
            """
            SELECT 1
            FROM mastery_evidence_review_resolution AS resolution
            WHERE NOT (
                ${
                    VALID_REVIEW_RESOLUTION_CALIBRATION_BINDING
                        .replace("NEW.", "resolution.")
                }
            )
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "Learner-mastery review resolution is not bound to its review calibration"
    }
    auditLearnerMasteryLearningEventAttributions(connection)
    auditLearnerMasteryLearningEvidenceSupersessions(connection)
    auditLearnerMasteryOpenResponseDedicatedProofChains(connection)
    check(
        !connection.hasRow(
            """
            SELECT 1
            FROM mastery_projection_generation AS generation
            WHERE generation.state = 'ACTIVE'
              AND NOT EXISTS (
                  SELECT 1
                  FROM mastery_store_metadata AS required_rebuild
                  WHERE required_rebuild.metadata_key =
                        '$PROJECTION_REBUILD_METADATA_KEY'
                    AND required_rebuild.metadata_value =
                        '$PROJECTION_REBUILD_REQUIRED_V2'
              )
              AND generation.source_supersession_count !=
                  (SELECT COUNT(*) FROM mastery_learning_evidence_supersession) +
                  (SELECT COUNT(*) FROM mastery_open_response_legacy_quarantine)
            LIMIT 1
            """.trimIndent(),
        ),
    ) { "Active mastery projection predates an evidence quarantine" }
    auditCompleteLocalMasteryCalibrationRegistry(
        connection = connection,
        tableName = "mastery_calibration_snapshot",
    )
    check(!connection.hasRow("PRAGMA foreign_key_check")) {
        "Learner-mastery database contains a foreign-key violation"
    }
}

private fun auditLearnerMasteryLearningEventAttributions(
    connection: SQLiteConnection,
    minimumRowIdExclusive: Long = 0L,
) {
    require(minimumRowIdExclusive >= 0L)
    check(
        !connection.hasRow(
            """
            SELECT 1
            FROM mastery_learning_event_attribution AS attribution
            LEFT JOIN mastery_learning_event AS learning_event
              ON learning_event.event_id = attribution.event_id
            LEFT JOIN mastery_candidate_attribution AS candidate_attribution
              ON candidate_attribution.candidate_id = learning_event.candidate_id
             AND candidate_attribution.subject = attribution.subject
             AND candidate_attribution.knowledge_node_id = attribution.knowledge_node_id
             AND candidate_attribution.taxonomy_version = attribution.taxonomy_version
             AND candidate_attribution.knowledge_pack_version = attribution.knowledge_pack_version
             AND candidate_attribution.knowledge_node_ref_fingerprint =
                 attribution.knowledge_node_ref_fingerprint
            WHERE attribution.rowid > $minimumRowIdExclusive
              AND (
                   learning_event.event_id IS NULL
                OR (
                     candidate_attribution.candidate_id IS NULL
                     AND NOT EXISTS (
                       SELECT 1
                       FROM mastery_open_response_dedicated_decision AS dedicated
                       JOIN mastery_open_response_model_evaluation_attestation AS attestation
                         ON attestation.attestation_fingerprint =
                            dedicated.attestation_fingerprint
                       JOIN mastery_open_response_weak_candidate_receipt AS open_receipt
                         ON open_receipt.receipt_fingerprint = dedicated.receipt_fingerprint
                       JOIN mastery_open_response_evaluation_knowledge_scope AS open_scope
                         ON open_scope.attestation_fingerprint =
                            attestation.attestation_fingerprint
                        AND open_scope.subject = attribution.subject
                        AND open_scope.knowledge_node_id = attribution.knowledge_node_id
                        AND open_scope.taxonomy_version = attribution.taxonomy_version
                        AND open_scope.knowledge_pack_version =
                            attribution.knowledge_pack_version
                        AND open_scope.knowledge_node_ref_fingerprint =
                            attribution.knowledge_node_ref_fingerprint
                       WHERE dedicated.disposition = 'ACCEPTED'
                         AND dedicated.accepted_event_id = learning_event.event_id
                         AND dedicated.candidate_id = learning_event.candidate_id
                         AND dedicated.source_fact_id = learning_event.source_fact_id
                         AND dedicated.learner_id = learning_event.learner_id
                         AND dedicated.subject = learning_event.subject
                         AND dedicated.direction = learning_event.direction
                         AND dedicated.calibration_snapshot_fingerprint =
                            learning_event.calibration_snapshot_fingerprint
                         AND dedicated.independently_completed =
                            learning_event.independently_answered
                         AND attestation.receipt_fingerprint =
                            open_receipt.receipt_fingerprint
                         AND open_receipt.candidate_id = learning_event.candidate_id
                         AND open_receipt.source_fact_id = learning_event.source_fact_id
                         AND (
                           (learning_event.direction = 'POSITIVE' AND
                            open_scope.evaluation_role = 'SUPPORTED_CORRECTNESS')
                           OR
                           (learning_event.direction = 'NEGATIVE' AND
                            open_scope.evaluation_role = 'LOCATED_GAP')
                         )
                     )
                   )
                OR attribution.subject IS NOT learning_event.subject
                OR attribution.ordinal < 0
                OR attribution.evidence_mass_micros < 0
                OR length(attribution.knowledge_node_id) = 0
                OR length(attribution.taxonomy_version) = 0
                OR length(attribution.knowledge_pack_version) = 0
                OR NOT (${validLearnerMasterySha256Sql("attribution.knowledge_node_ref_fingerprint")})
              )
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "Learner-mastery event attribution has an invalid parent, subject or value"
    }
    check(
        !connection.hasRow(
            """
            SELECT 1
            FROM (
            SELECT event_id, MIN(ordinal) AS first_ordinal,
                   MAX(ordinal) AS last_ordinal, COUNT(*) AS attribution_count
            FROM mastery_learning_event_attribution
            WHERE event_id IN (
                SELECT event_id
                FROM mastery_learning_event_attribution
                WHERE rowid > $minimumRowIdExclusive
            )
            GROUP BY event_id
            ) AS event_attributions
            WHERE event_attributions.first_ordinal != 0
               OR event_attributions.last_ordinal != event_attributions.attribution_count - 1
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "Learner-mastery event attribution ordinals are not contiguous"
    }
    auditLearnerMasteryLearningEventAttributionCanonicalReferences(
        connection = connection,
        minimumRowIdExclusive = minimumRowIdExclusive,
    )
}

private fun auditLearnerMasteryLearningEventAttributionCanonicalReferences(
    connection: SQLiteConnection,
    minimumRowIdExclusive: Long,
) {
    require(minimumRowIdExclusive >= 0L)
    val verifiedKnowledgeNodes = mutableMapOf<String, KnowledgeNodeRef>()
    val coveringIndexHint =
        if (connection.hasLearnerMasterySchemaIndex(KNOWLEDGE_REFERENCE_COVERING_INDEX)) {
            "INDEXED BY $KNOWLEDGE_REFERENCE_COVERING_INDEX"
        } else {
            ""
        }
    connection.prepare(
        """
        SELECT DISTINCT knowledge_node_ref_fingerprint, subject, knowledge_node_id,
               taxonomy_version, knowledge_pack_version
        FROM mastery_learning_event_attribution
             $coveringIndexHint
        WHERE rowid > $minimumRowIdExclusive
        """.trimIndent(),
    ).use { statement ->
        while (statement.step()) {
            val persistedFingerprint = statement.getText(0)
            val subject = statement.getText(1)
            val knowledgeNodeId = statement.getText(2)
            val taxonomyVersion = statement.getText(3)
            val knowledgePackVersion = statement.getText(4)
            val verified = verifiedKnowledgeNodes[persistedFingerprint]
            if (verified == null) {
                val knowledgeNode =
                    KnowledgeNodeRef(
                        subject = SubjectKind.valueOf(subject),
                        knowledgeNodeId = knowledgeNodeId,
                        taxonomyVersion = taxonomyVersion,
                        knowledgePackVersion = knowledgePackVersion,
                    )
                check(knowledgeNode.canonicalFingerprint == persistedFingerprint) {
                    "Learner-mastery event attribution knowledge reference fingerprint is invalid"
                }
                verifiedKnowledgeNodes[persistedFingerprint] = knowledgeNode
            } else {
                check(
                    verified.subject.name == subject &&
                        verified.knowledgeNodeId == knowledgeNodeId &&
                        verified.taxonomyVersion == taxonomyVersion &&
                        verified.knowledgePackVersion == knowledgePackVersion,
                ) {
                    "Learner-mastery event attribution fingerprint aliases another knowledge reference"
                }
            }
        }
    }
}

private fun SQLiteConnection.hasLearnerMasterySchemaIndex(indexName: String): Boolean =
    prepare(
        "SELECT 1 FROM sqlite_schema WHERE type = 'index' AND name = ? LIMIT 1",
    ).use { statement ->
        statement.bindText(1, indexName)
        statement.step()
    }

private fun auditLearnerMasteryLearningEvidenceSupersessions(
    connection: SQLiteConnection,
    minimumRowIdExclusive: Long = 0L,
) {
    require(minimumRowIdExclusive >= 0L)
    check(
        !connection.hasRow(
            """
            SELECT 1
            FROM mastery_learning_evidence_supersession AS supersession
            LEFT JOIN mastery_source_fact AS original_fact
              ON original_fact.source_fact_id = supersession.original_source_fact_id
            LEFT JOIN mastery_learning_event AS original_event
              ON original_event.event_id = supersession.original_event_id
            LEFT JOIN mastery_source_fact AS replacement_fact
              ON replacement_fact.source_fact_id = supersession.replacement_source_fact_id
            LEFT JOIN mastery_observation_candidate AS replacement_candidate
              ON replacement_candidate.candidate_id = supersession.replacement_candidate_id
            LEFT JOIN mastery_learning_event AS replacement_event
              ON replacement_event.event_id = supersession.replacement_event_id
            WHERE supersession.rowid > $minimumRowIdExclusive
              AND (
                   original_fact.source_fact_id IS NULL
                OR original_event.event_id IS NULL
                OR replacement_fact.source_fact_id IS NULL
                OR replacement_candidate.candidate_id IS NULL
                OR replacement_event.event_id IS NULL
                OR original_event.source_fact_id IS NOT original_fact.source_fact_id
                OR replacement_candidate.source_fact_id IS NOT replacement_fact.source_fact_id
                OR replacement_event.source_fact_id IS NOT replacement_fact.source_fact_id
                OR replacement_event.candidate_id IS NOT replacement_candidate.candidate_id
                OR supersession.learner_id IS NOT original_fact.learner_id
                OR supersession.learner_id IS NOT original_event.learner_id
                OR supersession.learner_id IS NOT replacement_fact.learner_id
                OR supersession.learner_id IS NOT replacement_candidate.learner_id
                OR supersession.learner_id IS NOT replacement_event.learner_id
                OR supersession.subject IS NOT original_fact.subject
                OR supersession.subject IS NOT original_event.subject
                OR supersession.subject IS NOT replacement_fact.subject
                OR supersession.subject IS NOT replacement_candidate.subject
                OR supersession.subject IS NOT replacement_event.subject
                OR supersession.original_source_fact_canonical_fingerprint IS NOT
                   original_fact.canonical_fingerprint
                OR supersession.original_event_canonical_fingerprint IS NOT
                   original_event.canonical_fingerprint
                OR supersession.replacement_source_fact_canonical_fingerprint IS NOT
                   replacement_fact.canonical_fingerprint
                OR supersession.replacement_candidate_canonical_fingerprint IS NOT
                   replacement_candidate.canonical_fingerprint
                OR supersession.original_source_fact_id = supersession.replacement_source_fact_id
                OR supersession.original_event_id = supersession.replacement_event_id
                OR NOT (${validLearnerMasterySha256Sql("supersession.original_source_fact_canonical_fingerprint")})
                OR NOT (${validLearnerMasterySha256Sql("supersession.original_event_canonical_fingerprint")})
                OR NOT (${validLearnerMasterySha256Sql("supersession.replacement_source_fact_canonical_fingerprint")})
                OR NOT (${validLearnerMasterySha256Sql("supersession.replacement_candidate_canonical_fingerprint")})
                OR NOT (${validLearnerMasterySha256Sql("supersession.correction_evidence_fingerprint")})
                OR NOT (${validLearnerMasterySha256Sql("supersession.canonical_fingerprint")})
              )
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "Learner-mastery evidence supersession has an invalid reference or scope"
    }
    connection.prepare(
        """
        SELECT learner_id, subject, original_source_fact_canonical_fingerprint,
               original_event_canonical_fingerprint,
               replacement_source_fact_canonical_fingerprint,
               replacement_candidate_canonical_fingerprint, replacement_event_id,
               authority, authority_version, correction_evidence_fingerprint,
               superseded_at_epoch_millis, canonical_fingerprint, supersession_id
        FROM mastery_learning_evidence_supersession
        WHERE rowid > $minimumRowIdExclusive
        """.trimIndent(),
    ).use { statement ->
        while (statement.step()) {
            val expectedFingerprint =
                CanonicalSha256("learner-mastery-learning-evidence-supersession-v1")
                    .field("learnerId", statement.getText(0))
                    .field("subject", statement.getText(1))
                    .field("originalSourceFactFingerprint", statement.getText(2))
                    .field("originalEventFingerprint", statement.getText(3))
                    .field("replacementSourceFactFingerprint", statement.getText(4))
                    .field("replacementCandidateFingerprint", statement.getText(5))
                    .field("replacementEventId", statement.getText(6))
                    .field("authority", statement.getText(7))
                    .field("authorityVersion", statement.getText(8))
                    .field("correctionEvidenceFingerprint", statement.getText(9))
                    .field("supersededAtEpochMillis", statement.getLong(10))
                    .finish()
            check(statement.getText(11) == expectedFingerprint) {
                "Learner-mastery evidence supersession fingerprint is invalid"
            }
            check(statement.getText(12) == "mles:${expectedFingerprint.take(48)}") {
                "Learner-mastery evidence supersession id is invalid"
            }
        }
    }
}

private fun validLearnerMasterySha256Sql(column: String): String =
    "length($column) = 64 AND $column NOT GLOB '*[^0-9a-f]*'"

private fun auditLearnerMasteryCalibrationBindingsIncrementally(
    connection: SQLiteConnection,
    previous: LearnerMasteryCalibrationAuditWatermark,
) {
    check(
        !connection.hasRow(
            """
            SELECT 1
            FROM mastery_evidence_review_case AS review_case
            WHERE review_case.rowid > ${previous.reviewCaseMaxRowId}
              AND NOT (
                ${VALID_REVIEW_CASE_CALIBRATION_BINDING.replace("NEW.", "review_case.")}
              )
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "New learner-mastery review case has an invalid calibration binding"
    }
    check(
        !connection.hasRow(
            """
            SELECT 1
            FROM mastery_learning_event AS learning_event
            WHERE learning_event.rowid > ${previous.eventMaxRowId}
              AND NOT (
                ${VALID_LEARNING_EVENT_CALIBRATION_BINDING.replace("NEW.", "learning_event.")}
              )
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "New learner-mastery event has an invalid calibration binding"
    }
    check(
        !connection.hasRow(
            """
            SELECT 1
            FROM mastery_knowledge_projection AS projection
            WHERE NOT (
                ${VALID_KNOWLEDGE_PROJECTION_CALIBRATION_BINDING.replace("NEW.", "projection.")}
            )
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "Learner-mastery projection has an invalid calibration binding"
    }
    check(
        !connection.hasRow(
            """
            SELECT 1
            FROM mastery_evidence_review_resolution AS resolution
            WHERE resolution.rowid > ${previous.reviewResolutionMaxRowId}
              AND NOT (
                ${
                    VALID_REVIEW_RESOLUTION_CALIBRATION_BINDING
                        .replace("NEW.", "resolution.")
                }
              )
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "New learner-mastery review resolution is not bound to its review calibration"
    }
    // Parent, candidate, value and ordinal invariants are enforced before every insert by the
    // canonical attribution trigger. A changed or missing trigger forces a full audit before a
    // new watermark is trusted. The only invariant SQLite cannot derive is the canonical
    // knowledge-reference digest, so the incremental path verifies that digest for new rows.
    auditLearnerMasteryLearningEventAttributionCanonicalReferences(
        connection = connection,
        minimumRowIdExclusive = previous.attributionMaxRowId,
    )
    auditLearnerMasteryLearningEvidenceSupersessions(
        connection = connection,
        minimumRowIdExclusive = previous.supersessionMaxRowId,
    )
    auditCompleteLocalMasteryCalibrationRegistry(
        connection = connection,
        tableName = "mastery_calibration_snapshot",
    )
}

private fun SQLiteConnection.hasRow(sql: String): Boolean =
    prepare(sql).use { statement -> statement.step() }

private const val CALIBRATION_BINDING_GUARD_INSTALL_SAVEPOINT =
    "learner_mastery_calibration_binding_guard_install"
private const val KNOWLEDGE_REFERENCE_COVERING_INDEX =
    "index_mastery_event_attribution_knowledge_ref_cover"

private val VALID_REVIEW_CASE_CALIBRATION_BINDING =
    """
    (
        NEW.calibration_binding_status = '${MasteryCalibrationBindingStatus.BOUND.name}'
        AND NEW.calibration_version IS NOT NULL
        AND NEW.calibration_profile_id IS NOT NULL
        AND NEW.calibration_snapshot_fingerprint IS NOT NULL
    ) OR (
        NEW.calibration_binding_status =
            '${MasteryCalibrationBindingStatus.LEGACY_UNCALIBRATED.name}'
        AND NEW.calibration_version IS NULL
        AND NEW.calibration_profile_id IS NULL
        AND NEW.calibration_snapshot_fingerprint IS NULL
    )
    """.trimIndent()

private val VALID_REVIEW_RESOLUTION_CALIBRATION_BINDING =
    """
    EXISTS (
        SELECT 1
        FROM mastery_evidence_review_case AS bound_review_case
        WHERE bound_review_case.review_case_id = NEW.review_case_id
          AND bound_review_case.learner_id = NEW.learner_id
          AND bound_review_case.subject = NEW.subject
          AND bound_review_case.calibration_binding_status =
              '${MasteryCalibrationBindingStatus.BOUND.name}'
          AND bound_review_case.calibration_snapshot_fingerprint IS NOT NULL
          AND bound_review_case.calibration_snapshot_fingerprint =
              NEW.calibration_snapshot_fingerprint
    )
    """.trimIndent()

private val VALID_LEARNING_EVENT_CALIBRATION_BINDING =
    """
    (
        NEW.projection_policy_version =
            '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION'
        AND NEW.calibration_version IS NOT NULL
        AND NEW.calibration_profile_id IS NOT NULL
        AND NEW.calibration_snapshot_fingerprint IS NOT NULL
    ) OR (
        NEW.projection_policy_version !=
            '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION'
        AND NEW.calibration_version =
            '$LEARNER_MASTERY_LEGACY_CALIBRATION_VERSION'
        AND NEW.calibration_profile_id IS NULL
        AND NEW.calibration_snapshot_fingerprint IS NULL
    )
    """.trimIndent()

private val VALID_KNOWLEDGE_PROJECTION_CALIBRATION_BINDING =
    """
    (
        NEW.projection_policy_version =
            '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION'
        AND NEW.calibration_version IS NOT NULL
        AND NEW.calibration_profile_id IS NOT NULL
        AND NEW.calibration_snapshot_fingerprint IS NOT NULL
    ) OR (
        NEW.projection_policy_version !=
            '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION'
        AND NEW.calibration_version IS NULL
        AND NEW.calibration_profile_id IS NULL
        AND NEW.calibration_snapshot_fingerprint IS NULL
    )
    """.trimIndent()

private val INVALID_LEARNING_EVENT_ATTRIBUTION_INSERT =
    """
    NEW.ordinal < 0
    OR NEW.evidence_mass_micros < 0
    OR length(NEW.knowledge_node_id) = 0
    OR length(NEW.taxonomy_version) = 0
    OR length(NEW.knowledge_pack_version) = 0
    OR NOT (${validLearnerMasterySha256Sql("NEW.knowledge_node_ref_fingerprint")})
    OR NOT (
        EXISTS (
            SELECT 1
            FROM mastery_learning_event AS learning_event
            JOIN mastery_candidate_attribution AS candidate_attribution
              ON candidate_attribution.candidate_id = learning_event.candidate_id
             AND candidate_attribution.subject = NEW.subject
             AND candidate_attribution.knowledge_node_id = NEW.knowledge_node_id
             AND candidate_attribution.taxonomy_version = NEW.taxonomy_version
             AND candidate_attribution.knowledge_pack_version = NEW.knowledge_pack_version
             AND candidate_attribution.knowledge_node_ref_fingerprint =
                 NEW.knowledge_node_ref_fingerprint
            WHERE learning_event.event_id = NEW.event_id
              AND learning_event.subject = NEW.subject
        )
        OR EXISTS (
            SELECT 1
            FROM mastery_learning_event AS learning_event
            JOIN mastery_open_response_dedicated_decision AS dedicated
              ON dedicated.accepted_event_id = learning_event.event_id
             AND dedicated.disposition = 'ACCEPTED'
             AND dedicated.candidate_id = learning_event.candidate_id
             AND dedicated.source_fact_id = learning_event.source_fact_id
             AND dedicated.learner_id = learning_event.learner_id
             AND dedicated.subject = learning_event.subject
             AND dedicated.direction = learning_event.direction
             AND dedicated.calibration_snapshot_fingerprint =
                 learning_event.calibration_snapshot_fingerprint
             AND dedicated.independently_completed =
                 learning_event.independently_answered
            JOIN mastery_open_response_model_evaluation_attestation AS attestation
              ON attestation.attestation_fingerprint =
                 dedicated.attestation_fingerprint
             AND attestation.receipt_fingerprint = dedicated.receipt_fingerprint
            JOIN mastery_open_response_weak_candidate_receipt AS open_receipt
              ON open_receipt.receipt_fingerprint = dedicated.receipt_fingerprint
             AND open_receipt.candidate_id = learning_event.candidate_id
             AND open_receipt.source_fact_id = learning_event.source_fact_id
            JOIN mastery_open_response_evaluation_knowledge_scope AS open_scope
              ON open_scope.attestation_fingerprint =
                 attestation.attestation_fingerprint
             AND open_scope.subject = NEW.subject
             AND open_scope.knowledge_node_id = NEW.knowledge_node_id
             AND open_scope.taxonomy_version = NEW.taxonomy_version
             AND open_scope.knowledge_pack_version = NEW.knowledge_pack_version
             AND open_scope.knowledge_node_ref_fingerprint =
                 NEW.knowledge_node_ref_fingerprint
            WHERE learning_event.event_id = NEW.event_id
              AND learning_event.subject = NEW.subject
              AND (
                (learning_event.direction = 'POSITIVE' AND
                 open_scope.evaluation_role = 'SUPPORTED_CORRECTNESS')
                OR
                (learning_event.direction = 'NEGATIVE' AND
                 open_scope.evaluation_role = 'LOCATED_GAP')
              )
        )
    )
    OR (
        NEW.ordinal > 0
        AND NOT EXISTS (
            SELECT 1
            FROM mastery_learning_event_attribution AS previous_attribution
            WHERE previous_attribution.event_id = NEW.event_id
              AND previous_attribution.ordinal = NEW.ordinal - 1
        )
    )
    """.trimIndent()

private data class CalibrationBindingTriggerDefinition(
    val name: String,
    val operation: String,
    val tableName: String,
    val invalidPredicate: String,
) {
    fun sql(): String =
        """
        CREATE TRIGGER $name
        BEFORE $operation ON $tableName
        WHEN $invalidPredicate
        BEGIN
            SELECT RAISE(ABORT, 'invalid learner-mastery calibration binding');
        END
        """.trimIndent()
}

private fun calibrationBindingTriggerDefinitions(): List<CalibrationBindingTriggerDefinition> =
    listOf(
        CalibrationBindingTriggerDefinition(
            name = "validate_mastery_learning_event_attribution_insert",
            operation = "INSERT",
            tableName = "mastery_learning_event_attribution",
            invalidPredicate = INVALID_LEARNING_EVENT_ATTRIBUTION_INSERT,
        ),
        CalibrationBindingTriggerDefinition(
            name = "validate_mastery_evidence_review_case_calibration_insert",
            operation = "INSERT",
            tableName = "mastery_evidence_review_case",
            invalidPredicate = "NOT ($VALID_REVIEW_CASE_CALIBRATION_BINDING)",
        ),
        CalibrationBindingTriggerDefinition(
            name = "validate_mastery_evidence_review_case_calibration_update",
            operation = "UPDATE",
            tableName = "mastery_evidence_review_case",
            invalidPredicate = "NOT ($VALID_REVIEW_CASE_CALIBRATION_BINDING)",
        ),
        CalibrationBindingTriggerDefinition(
            name = "validate_mastery_evidence_review_resolution_calibration_insert",
            operation = "INSERT",
            tableName = "mastery_evidence_review_resolution",
            invalidPredicate = "NOT ($VALID_REVIEW_RESOLUTION_CALIBRATION_BINDING)",
        ),
        CalibrationBindingTriggerDefinition(
            name = "validate_mastery_learning_event_calibration_insert",
            operation = "INSERT",
            tableName = "mastery_learning_event",
            invalidPredicate = "NOT ($VALID_LEARNING_EVENT_CALIBRATION_BINDING)",
        ),
        CalibrationBindingTriggerDefinition(
            name = "validate_mastery_learning_event_calibration_update",
            operation = "UPDATE",
            tableName = "mastery_learning_event",
            invalidPredicate = "NOT ($VALID_LEARNING_EVENT_CALIBRATION_BINDING)",
        ),
        CalibrationBindingTriggerDefinition(
            name = "validate_mastery_knowledge_projection_calibration_insert",
            operation = "INSERT",
            tableName = "mastery_knowledge_projection",
            invalidPredicate = "NOT ($VALID_KNOWLEDGE_PROJECTION_CALIBRATION_BINDING)",
        ),
        CalibrationBindingTriggerDefinition(
            name = "validate_mastery_knowledge_projection_calibration_update",
            operation = "UPDATE",
            tableName = "mastery_knowledge_projection",
            invalidPredicate = "NOT ($VALID_KNOWLEDGE_PROJECTION_CALIBRATION_BINDING)",
        ),
    )
