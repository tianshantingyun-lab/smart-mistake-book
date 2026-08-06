package com.tingyun.smartmistakebook.core.database

import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.tingyun.smartmistakebook.core.database.entity.LegacyBusinessWriteBarrierEntity
import com.tingyun.smartmistakebook.core.model.CanonicalSha256

internal const val LEGACY_BUSINESS_WRITE_BARRIER_KEY =
    "legacy-business-write-barrier-v1"
internal const val LEGACY_BUSINESS_WRITE_REJECTED_MESSAGE =
    "legacy business authority is read-only after cutover"

internal enum class LegacyBusinessWriteBarrierActivationKind {
    FRESH_EMPTY,
    TERMINAL_CUTOVER,
}

internal data class LegacyTerminalCutoverStageExpectation(
    val ordinal: Int,
    val stageName: String,
    val targetDatabaseName: String,
)

internal data class LegacyBarrierTriggerDefinition(
    val name: String,
    val tableName: String,
    val sql: String,
)

/**
 * Exact v44 inventory. Any future application table that is not deliberately classified makes
 * the inventory tests fail before it can ship without a barrier decision.
 */
internal object LegacyBusinessWriteBarrierSchema {
    const val TABLE_NAME = "legacy_business_write_barrier"
    const val TERMINAL_STAGE_ORDINAL = 12
    const val V44_APPLICATION_TABLE_COUNT = 97
    const val V45_APPLICATION_TABLE_COUNT = 98
    const val V46_APPLICATION_TABLE_COUNT = 101
    const val V47_APPLICATION_TABLE_COUNT = 102
    const val V48_APPLICATION_TABLE_COUNT = 103
    const val V49_APPLICATION_TABLE_COUNT = 104
    const val IMMUTABLE_UPDATE_TRIGGER = "legacy_business_barrier_immutable_update"
    const val IMMUTABLE_DELETE_TRIGGER = "legacy_business_barrier_immutable_delete"
    const val CONFLICTING_INSERT_TRIGGER = "legacy_business_barrier_conflicting_insert"

    val legacyCoordinationTables: Set<String> =
        setOf(
            "batch_capture_content_binding",
            "batch_import_boundary_resolution_receipt",
            "capture_draft_batch_import_receipt",
            "capture_draft_merge_session_receipt",
            "capture_student_save_handoff",
            "legacy_authority_cutover_stage_receipt",
            "tutor_conversation",
            "tutor_evidence_request",
            "tutor_learning_evidence_finalization_receipt",
            "tutor_turn_receipt",
        )

    val currentTutorInteractionCoordinationTables: Set<String> =
        setOf(
            "tutor_current_interaction_event",
            "tutor_current_interaction_head",
            "tutor_current_interaction_scope",
        )

    val currentTutorHostCoordinationTables: Set<String> =
        setOf(
            "tutor_current_host_work",
            "tutor_current_policy",
            "tutor_free_response_outbox",
        )

    val allowedCoordinationTables: Set<String> =
        legacyCoordinationTables +
            currentTutorInteractionCoordinationTables +
            currentTutorHostCoordinationTables

    val blockedBusinessAuthorityTables: Set<String> =
        setOf(
            "active_review_plan_slot",
            "answer_reveal_outcome",
            "applied_answer_reveal_record",
            "applied_attempt_record",
            "applied_correction_record",
            "applied_learning_observation_record",
            "applied_tutor_answer_exposure_record",
            "assessment_answer_reveal_event",
            "assessment_event",
            "assessment_evidence_attribution",
            "assessment_evidence_snapshot",
            "assessment_item_snapshot",
            "assessment_presentation",
            "attempt_correction",
            "attempt_event",
            "attempt_submission",
            "attributed_learning_observation_event",
            "batch_import_job",
            "batch_import_page",
            "canonical_source_asset",
            "error_book_entry",
            "independent_correct_observation",
            "knowledge_grounding_request",
            "knowledge_grounding_resolution",
            "knowledge_mastery_state",
            "knowledge_node",
            "knowledge_node_relation",
            "knowledge_node_source_binding",
            "knowledge_research_review_bundle",
            "knowledge_research_review_source",
            "knowledge_search_feature",
            "knowledge_source",
            "knowledge_teaching_material",
            "knowledge_teaching_material_node_binding",
            "learner_knowledge_mastery_state",
            "learner_problem_memory_state",
            "learner_projection_snapshot",
            "learning_event_identity",
            "learning_evidence_review_case",
            "learning_observation_candidate",
            "learning_observation_candidate_attribution",
            "learning_observation_event_admission",
            "learning_observation_event_attribution",
            "learning_observation_source_authority",
            "learning_observation_source_fact",
            "learning_observation_source_fact_proof",
            "learning_problem_anchor",
            "learning_sequence",
            "model_task",
            "model_task_event",
            "model_task_operation",
            "practice_unit",
            "practice_unit_knowledge_binding",
            "presentation_projection_state",
            "problem",
            "problem_classification_binding",
            "problem_draft",
            "problem_draft_commit_receipt",
            "problem_draft_edit_snapshot",
            "problem_draft_revision",
            "problem_draft_source_asset",
            "problem_error_attribution_candidate",
            "problem_error_candidate_evidence",
            "problem_memory_state",
            "problem_organization_receipt",
            "problem_organization_work",
            "problem_relation",
            "problem_revision",
            "problem_revision_source_asset",
            "problem_solution_step",
            "problem_step_knowledge_binding",
            "projection_consumption",
            "projection_outbox",
            "review_plan",
            "review_queue_item",
            "review_queue_knowledge_node",
            "review_queue_reason",
            "review_session",
            "review_session_advance_receipt",
            "review_session_revision",
            "tutor_answer_exposure",
            "tutor_answer_exposure_outcome",
            "tutor_evidence_cancellation",
            "tutor_session",
            "tutor_session_problem_anchor",
            "tutor_turn_response",
            "tutor_visual_target_evidence",
        )

    val v44ApplicationTables: Set<String> =
        legacyCoordinationTables + blockedBusinessAuthorityTables

    val v45ApplicationTables: Set<String> =
        v44ApplicationTables + TABLE_NAME

    val v46ApplicationTables: Set<String> =
        v45ApplicationTables + currentTutorInteractionCoordinationTables

    val v47ApplicationTables: Set<String> =
        v46ApplicationTables + "tutor_current_host_work"

    val v48ApplicationTables: Set<String> =
        v47ApplicationTables + "tutor_current_policy"

    val v49ApplicationTables: Set<String> =
        v48ApplicationTables + "tutor_free_response_outbox"

    val barrierProtectionTriggerNames: Set<String> =
        setOf(
            IMMUTABLE_UPDATE_TRIGGER,
            IMMUTABLE_DELETE_TRIGGER,
            CONFLICTING_INSERT_TRIGGER,
        )

    val terminalCutoverStages: List<LegacyTerminalCutoverStageExpectation> =
        listOf(
            stage(1, "LEGACY_SCHEMA_READY", LegacyAuthorityDatabaseName.LEGACY_SESSION_COORDINATION),
            stage(
                2,
                "KNOWLEDGE_PACKAGE_INSTALLED",
                LegacyAuthorityDatabaseName.HIGH_SCHOOL_KNOWLEDGE,
            ),
            stage(
                3,
                "KNOWLEDGE_PACKAGE_VERIFIED",
                LegacyAuthorityDatabaseName.HIGH_SCHOOL_KNOWLEDGE,
            ),
            stage(
                4,
                "KNOWLEDGE_RUNTIME_READ_ONLY",
                LegacyAuthorityDatabaseName.HIGH_SCHOOL_KNOWLEDGE,
            ),
            stage(
                5,
                "STUDENT_DOCUMENTS_IMPORTED",
                LegacyAuthorityDatabaseName.STUDENT_MISTAKES,
            ),
            stage(
                6,
                "STUDENT_OUTBOX_RECONCILED",
                LegacyAuthorityDatabaseName.STUDENT_MISTAKES,
            ),
            stage(
                7,
                "STUDENT_INDEXES_REBUILT",
                LegacyAuthorityDatabaseName.STUDENT_MISTAKES,
            ),
            stage(
                8,
                "STUDENT_AUTHORITY_VERIFIED",
                LegacyAuthorityDatabaseName.STUDENT_MISTAKES,
            ),
            stage(
                9,
                "MASTERY_FACTS_IMPORTED",
                LegacyAuthorityDatabaseName.LEARNER_MASTERY,
            ),
            stage(
                10,
                "MASTERY_BINDINGS_RECONCILED",
                LegacyAuthorityDatabaseName.LEARNER_MASTERY,
            ),
            stage(
                11,
                "MASTERY_PROJECTIONS_REBUILT",
                LegacyAuthorityDatabaseName.LEARNER_MASTERY,
            ),
            stage(
                12,
                "MASTERY_AUTHORITY_VERIFIED",
                LegacyAuthorityDatabaseName.LEARNER_MASTERY,
            ),
        )

    init {
        check(legacyCoordinationTables.size == 10)
        check(currentTutorInteractionCoordinationTables.size == 3)
        check(currentTutorHostCoordinationTables.size == 3)
        check(allowedCoordinationTables.size == 16)
        check(blockedBusinessAuthorityTables.size == 87)
        check(allowedCoordinationTables.intersect(blockedBusinessAuthorityTables).isEmpty())
        check(v44ApplicationTables.size == V44_APPLICATION_TABLE_COUNT)
        check(v45ApplicationTables.size == V45_APPLICATION_TABLE_COUNT)
        check(v46ApplicationTables.size == V46_APPLICATION_TABLE_COUNT)
        check(v47ApplicationTables.size == V47_APPLICATION_TABLE_COUNT)
        check(v48ApplicationTables.size == V48_APPLICATION_TABLE_COUNT)
        check(v49ApplicationTables.size == V49_APPLICATION_TABLE_COUNT)
        check(terminalCutoverStages.map { it.ordinal } == (1..TERMINAL_STAGE_ORDINAL).toList())
        (v49ApplicationTables + triggerNames() + barrierProtectionTriggerNames).forEach { name ->
            check(SQLITE_SCHEMA_NAME.matches(name)) { "Unsafe legacy barrier schema name: $name" }
        }
    }

    fun triggerName(
        tableName: String,
        operation: LegacyBusinessMutation,
    ): String = "legacy_business_barrier_${operation.name.lowercase()}_$tableName"

    fun triggerNames(): Set<String> =
        blockedBusinessAuthorityTables.flatMapTo(mutableSetOf()) { table ->
            LegacyBusinessMutation.entries.map { operation -> triggerName(table, operation) }
        }

    fun triggerDefinitions(): Map<String, LegacyBarrierTriggerDefinition> =
        buildList {
            blockedBusinessAuthorityTables.sorted().forEach { tableName ->
                LegacyBusinessMutation.entries.forEach { operation ->
                    val name = triggerName(tableName, operation)
                    add(
                        LegacyBarrierTriggerDefinition(
                            name = name,
                            tableName = tableName,
                            sql = businessTriggerSql(tableName, operation),
                        ),
                    )
                }
            }
            add(
                LegacyBarrierTriggerDefinition(
                    name = IMMUTABLE_UPDATE_TRIGGER,
                    tableName = TABLE_NAME,
                    sql = immutableUpdateTriggerSql(),
                ),
            )
            add(
                LegacyBarrierTriggerDefinition(
                    name = IMMUTABLE_DELETE_TRIGGER,
                    tableName = TABLE_NAME,
                    sql = immutableDeleteTriggerSql(),
                ),
            )
            add(
                LegacyBarrierTriggerDefinition(
                    name = CONFLICTING_INSERT_TRIGGER,
                    tableName = TABLE_NAME,
                    sql = conflictingInsertTriggerSql(),
                ),
            )
        }.associateBy(LegacyBarrierTriggerDefinition::name)

    /**
     * v44 migration entry. The source inventory must be exact before adding the v45 control table;
     * an unclassified future table is never silently left writable.
     */
    fun migrateFromV44(connection: SQLiteConnection) {
        if (readTableSql(connection, TABLE_NAME) != null) {
            installMissingCanonicalTriggers(connection)
            return
        }
        requireV44ApplicationTableInventory(connection)
        requireNoLegacyBarrierTriggers(connection)
        check(readTableSql(connection, TABLE_NAME) == null) {
            "v44 unexpectedly contains the v45 legacy business barrier table"
        }
        connection.execSQL(barrierTableSql(TABLE_NAME))
        installMissingCanonicalTriggers(connection)
        verifyCanonicalRuntimeSchema(connection, v45ApplicationTables)
    }

    /**
     * Fresh Room databases initially contain Room's structurally weaker table. It is replaced
     * before any handle can be published, then the same canonical schema used by migration is
     * verified.
     */
    fun initializeCreatedDatabase(connection: SQLiteConnection) {
        requireV49ApplicationTableInventory(connection)
        ensureCanonicalBarrierTable(connection, allowKnownRoomTableRebuild = true)
        installMissingCanonicalTriggers(connection)
        verifyCanonicalRuntimeSchema(connection, v49ApplicationTables)
    }

    /**
     * Every open is a fail-closed integrity gate. Missing canonical triggers are safe to recreate
     * before Room returns the handle; same-name wrong SQL, unknown tables, and unknown barrier
     * triggers are rejected.
     */
    fun verifyOrRepairBeforeOpen(connection: SQLiteConnection) {
        requireV49ApplicationTableInventory(connection)
        ensureCanonicalBarrierTable(connection, allowKnownRoomTableRebuild = true)
        installMissingCanonicalTriggers(connection)
        verifyCanonicalRuntimeSchema(connection, v49ApplicationTables)
    }

    fun activateFreshEmpty(
        connection: SQLiteConnection,
        activatedAtEpochMillis: Long,
    ) {
        require(activatedAtEpochMillis >= 0L)
        verifyCanonicalRuntimeSchema(connection, v49ApplicationTables)
        blockedBusinessAuthorityTables.sorted().forEach { tableName ->
            connection.prepare(
                "SELECT EXISTS(SELECT 1 FROM `$tableName` LIMIT 1)",
            ).use { statement ->
                check(statement.step())
                check(statement.getLong(0) == 0L) {
                    "Fresh database unexpectedly contains legacy business rows in $tableName"
                }
            }
        }
        val entity = freshEmptyEntity(activatedAtEpochMillis)
        connection.prepare(
            """
            INSERT OR IGNORE INTO `$TABLE_NAME` (
                `barrier_key`,
                `activation_kind`,
                `terminal_stage_ordinal`,
                `terminal_receipt_fingerprint`,
                `activation_receipt_fingerprint`,
                `activated_at_epoch_millis`
            ) VALUES (?, ?, NULL, NULL, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, entity.barrierKey)
            statement.bindText(2, entity.activationKind)
            statement.bindText(3, entity.activationReceiptFingerprint)
            statement.bindLong(4, entity.activatedAtEpochMillis)
            statement.step()
        }
        verifyBarrierRows(connection)
    }

    fun freshEmptyEntity(activatedAtEpochMillis: Long): LegacyBusinessWriteBarrierEntity =
        barrierEntity(
            activationKind = LegacyBusinessWriteBarrierActivationKind.FRESH_EMPTY,
            terminalStageOrdinal = null,
            terminalReceiptFingerprint = null,
            activatedAtEpochMillis = activatedAtEpochMillis,
        )

    fun terminalCutoverEntity(
        terminalReceiptFingerprint: String,
        activatedAtEpochMillis: Long,
    ): LegacyBusinessWriteBarrierEntity =
        barrierEntity(
            activationKind = LegacyBusinessWriteBarrierActivationKind.TERMINAL_CUTOVER,
            terminalStageOrdinal = TERMINAL_STAGE_ORDINAL,
            terminalReceiptFingerprint = terminalReceiptFingerprint,
            activatedAtEpochMillis = activatedAtEpochMillis,
        )

    private fun barrierEntity(
        activationKind: LegacyBusinessWriteBarrierActivationKind,
        terminalStageOrdinal: Int?,
        terminalReceiptFingerprint: String?,
        activatedAtEpochMillis: Long,
    ): LegacyBusinessWriteBarrierEntity {
        require(activatedAtEpochMillis >= 0L)
        terminalReceiptFingerprint?.requireLowerSha256("terminal receipt fingerprint")
        val receiptFingerprint =
            CanonicalSha256("legacy-business-write-barrier-activation-v1")
                .field("barrierKey", LEGACY_BUSINESS_WRITE_BARRIER_KEY)
                .field("activationKind", activationKind.name)
                .nullableLongField("terminalStageOrdinal", terminalStageOrdinal?.toLong())
                .nullableField("terminalReceiptFingerprint", terminalReceiptFingerprint)
                .finish()
        return LegacyBusinessWriteBarrierEntity(
            barrierKey = LEGACY_BUSINESS_WRITE_BARRIER_KEY,
            activationKind = activationKind.name,
            terminalStageOrdinal = terminalStageOrdinal,
            terminalReceiptFingerprint = terminalReceiptFingerprint,
            activationReceiptFingerprint = receiptFingerprint,
            activatedAtEpochMillis = activatedAtEpochMillis,
        )
    }

    internal fun canonicalBarrierTableSql(): String = barrierTableSql(TABLE_NAME)

    private fun barrierTableSql(tableName: String): String =
        """
        CREATE TABLE `$tableName` (
            `barrier_key` TEXT NOT NULL,
            `activation_kind` TEXT NOT NULL,
            `terminal_stage_ordinal` INTEGER,
            `terminal_receipt_fingerprint` TEXT,
            `activation_receipt_fingerprint` TEXT NOT NULL,
            `activated_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`barrier_key`),
            CHECK(`barrier_key` = '$LEGACY_BUSINESS_WRITE_BARRIER_KEY'),
            CHECK(`activation_kind` IN ('FRESH_EMPTY', 'TERMINAL_CUTOVER')),
            CHECK(`activated_at_epoch_millis` >= 0),
            CHECK(
                length(`activation_receipt_fingerprint`) = 64
                AND `activation_receipt_fingerprint` NOT GLOB '*[^0-9a-f]*'
            ),
            CHECK(
                (
                    `activation_kind` = 'FRESH_EMPTY'
                    AND `terminal_stage_ordinal` IS NULL
                    AND `terminal_receipt_fingerprint` IS NULL
                )
                OR
                (
                    `activation_kind` = 'TERMINAL_CUTOVER'
                    AND `terminal_stage_ordinal` = $TERMINAL_STAGE_ORDINAL
                    AND length(`terminal_receipt_fingerprint`) = 64
                    AND `terminal_receipt_fingerprint` NOT GLOB '*[^0-9a-f]*'
                )
            )
        )
        """.trimIndent()

    private fun businessTriggerSql(
        tableName: String,
        operation: LegacyBusinessMutation,
    ): String =
        """
        CREATE TRIGGER `${triggerName(tableName, operation)}`
        BEFORE ${operation.sql} ON `$tableName`
        WHEN EXISTS (
            SELECT 1
            FROM `$TABLE_NAME`
            WHERE `barrier_key` = '$LEGACY_BUSINESS_WRITE_BARRIER_KEY'
        )
        BEGIN
            SELECT RAISE(ABORT, '$LEGACY_BUSINESS_WRITE_REJECTED_MESSAGE');
        END
        """.trimIndent()

    private fun immutableUpdateTriggerSql(): String =
        """
        CREATE TRIGGER `$IMMUTABLE_UPDATE_TRIGGER`
        BEFORE UPDATE ON `$TABLE_NAME`
        BEGIN
            SELECT RAISE(ABORT, 'legacy business write barrier is immutable');
        END
        """.trimIndent()

    private fun immutableDeleteTriggerSql(): String =
        """
        CREATE TRIGGER `$IMMUTABLE_DELETE_TRIGGER`
        BEFORE DELETE ON `$TABLE_NAME`
        BEGIN
            SELECT RAISE(ABORT, 'legacy business write barrier is immutable');
        END
        """.trimIndent()

    private fun conflictingInsertTriggerSql(): String =
        """
        CREATE TRIGGER `$CONFLICTING_INSERT_TRIGGER`
        BEFORE INSERT ON `$TABLE_NAME`
        WHEN EXISTS (SELECT 1 FROM `$TABLE_NAME`)
            AND NOT EXISTS (
                SELECT 1
                FROM `$TABLE_NAME`
                WHERE `barrier_key` = NEW.`barrier_key`
                  AND `activation_kind` = NEW.`activation_kind`
                  AND `terminal_stage_ordinal` IS NEW.`terminal_stage_ordinal`
                  AND `terminal_receipt_fingerprint` IS NEW.`terminal_receipt_fingerprint`
                  AND `activation_receipt_fingerprint` =
                      NEW.`activation_receipt_fingerprint`
                  AND `activated_at_epoch_millis` = NEW.`activated_at_epoch_millis`
            )
        BEGIN
            SELECT RAISE(ABORT, 'legacy business write barrier conflicts with durable state');
        END
        """.trimIndent()

    private fun ensureCanonicalBarrierTable(
        connection: SQLiteConnection,
        allowKnownRoomTableRebuild: Boolean,
    ) {
        val persistedSql =
            readTableSql(connection, TABLE_NAME)
                ?: throw LegacyBusinessWriteBarrierIntegrityException(
                    "Legacy business write barrier table is missing",
                )
        if (hasExactPersistedSql(persistedSql, barrierTableSql(TABLE_NAME))) {
            verifyBarrierRows(connection)
            return
        }
        if (
            !allowKnownRoomTableRebuild ||
            !isExactKnownRoomGeneratedWeakBarrierTableSql(persistedSql)
        ) {
            throw LegacyBusinessWriteBarrierIntegrityException(
                "Legacy business write barrier table is not canonical",
            )
        }

        val durableRows = readBarrierEntities(connection)
        durableRows.forEach { it.toVerifiedRecord() }
        check(durableRows.size <= 1) {
            "Legacy business write barrier must contain at most one durable row"
        }
        check(readTableSql(connection, BARRIER_REBUILD_TABLE_NAME) == null) {
            "Legacy business write barrier rebuild table already exists"
        }
        connection.execSQL(barrierTableSql(BARRIER_REBUILD_TABLE_NAME))
        connection.execSQL(
            """
            INSERT INTO `$BARRIER_REBUILD_TABLE_NAME` (
                `barrier_key`,
                `activation_kind`,
                `terminal_stage_ordinal`,
                `terminal_receipt_fingerprint`,
                `activation_receipt_fingerprint`,
                `activated_at_epoch_millis`
            )
            SELECT
                `barrier_key`,
                `activation_kind`,
                `terminal_stage_ordinal`,
                `terminal_receipt_fingerprint`,
                `activation_receipt_fingerprint`,
                `activated_at_epoch_millis`
            FROM `$TABLE_NAME`
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE `$TABLE_NAME`")
        connection.execSQL(
            "ALTER TABLE `$BARRIER_REBUILD_TABLE_NAME` RENAME TO `$TABLE_NAME`",
        )
        val rebuiltSql =
            readTableSql(connection, TABLE_NAME)
                ?: throw LegacyBusinessWriteBarrierIntegrityException(
                    "Rebuilt legacy business write barrier table is missing",
                )
        if (!hasExactPersistedSql(rebuiltSql, barrierTableSql(TABLE_NAME))) {
            throw LegacyBusinessWriteBarrierIntegrityException(
                "Rebuilt legacy business write barrier table is not canonical",
            )
        }
        verifyBarrierRows(connection)
    }

    private fun installMissingCanonicalTriggers(connection: SQLiteConnection) {
        val expected = triggerDefinitions()
        val persisted = readBarrierTriggers(connection)
        val unknown = persisted.keys - expected.keys
        if (unknown.isNotEmpty()) {
            throw LegacyBusinessWriteBarrierIntegrityException(
                "Unknown legacy business barrier triggers: ${unknown.sorted().joinToString()}",
            )
        }
        persisted.forEach { (name, actual) ->
            val canonical =
                expected.getValue(name)
            if (
                actual.tableName != canonical.tableName ||
                !hasExactPersistedSql(actual.sql, canonical.sql)
            ) {
                throw LegacyBusinessWriteBarrierIntegrityException(
                    "Legacy business barrier trigger $name is not canonical",
                )
            }
        }
        (expected.keys - persisted.keys).sorted().forEach { missing ->
            connection.execSQL(expected.getValue(missing).sql)
        }
    }

    private fun verifyCanonicalRuntimeSchema(
        connection: SQLiteConnection,
        expectedApplicationTables: Set<String>,
    ) {
        requireApplicationTableInventory(connection, expectedApplicationTables)
        val tableSql =
            readTableSql(connection, TABLE_NAME)
                ?: throw LegacyBusinessWriteBarrierIntegrityException(
                    "Legacy business write barrier table is missing",
                )
        if (!hasExactPersistedSql(tableSql, barrierTableSql(TABLE_NAME))) {
            throw LegacyBusinessWriteBarrierIntegrityException(
                "Legacy business write barrier table is not canonical",
            )
        }
        val expected = triggerDefinitions()
        val persisted = readBarrierTriggers(connection)
        if (persisted.keys != expected.keys) {
            throw LegacyBusinessWriteBarrierIntegrityException(
                "Legacy business barrier trigger inventory is incomplete or unknown",
            )
        }
        expected.forEach { (name, canonical) ->
            val actual = persisted.getValue(name)
            if (
                actual.tableName != canonical.tableName ||
                !hasExactPersistedSql(actual.sql, canonical.sql)
            ) {
                throw LegacyBusinessWriteBarrierIntegrityException(
                    "Legacy business barrier trigger $name is not canonical",
                )
            }
        }
        verifyBarrierRows(connection)
    }

    private fun verifyBarrierRows(connection: SQLiteConnection) {
        val rows = readBarrierEntities(connection)
        if (rows.size > 1) {
            throw LegacyBusinessWriteBarrierIntegrityException(
                "Legacy business write barrier contains multiple durable rows",
            )
        }
        rows.singleOrNull()?.let { row ->
            val record = row.toVerifiedRecord()
            if (record.activationKind == LegacyBusinessWriteBarrierActivationKind.FRESH_EMPTY) {
                blockedBusinessAuthorityTables.sorted().forEach { tableName ->
                    if (tableHasRows(connection, tableName)) {
                        throw LegacyBusinessWriteBarrierIntegrityException(
                            "Fresh-empty barrier conflicts with legacy rows in $tableName",
                        )
                    }
                }
            }
        }
    }

    private fun requireApplicationTableInventory(
        connection: SQLiteConnection,
        expected: Set<String>,
    ) {
        val actual = readApplicationTableNames(connection)
        if (actual != expected) {
            throw LegacyBusinessWriteBarrierIntegrityException(
                "Legacy application table inventory mismatch; " +
                    "missing=${(expected - actual).sorted().joinToString()} " +
                    "unknown=${(actual - expected).sorted().joinToString()}",
            )
        }
    }

    fun requireV44ApplicationTableInventory(connection: SQLiteConnection) {
        requireApplicationTableInventory(connection, v44ApplicationTables)
    }

    fun requireV45ApplicationTableInventory(connection: SQLiteConnection) {
        requireApplicationTableInventory(connection, v45ApplicationTables)
    }

    fun requireV46ApplicationTableInventory(connection: SQLiteConnection) {
        requireApplicationTableInventory(connection, v46ApplicationTables)
    }

    fun requireV47ApplicationTableInventory(connection: SQLiteConnection) {
        requireApplicationTableInventory(connection, v47ApplicationTables)
    }

    fun requireV48ApplicationTableInventory(connection: SQLiteConnection) {
        requireApplicationTableInventory(connection, v48ApplicationTables)
    }

    fun requireV49ApplicationTableInventory(connection: SQLiteConnection) {
        requireApplicationTableInventory(connection, v49ApplicationTables)
    }

    private fun requireNoLegacyBarrierTriggers(connection: SQLiteConnection) {
        val actual = readBarrierTriggers(connection)
        if (actual.isNotEmpty()) {
            throw LegacyBusinessWriteBarrierIntegrityException(
                "v44 unexpectedly contains legacy business barrier triggers",
            )
        }
    }

    private fun readApplicationTableNames(connection: SQLiteConnection): Set<String> =
        connection.prepare(
            "SELECT name FROM main.sqlite_master WHERE type = 'table' ORDER BY name",
        ).use { statement ->
            buildSet {
                while (statement.step()) {
                    val name = statement.getText(0)
                    if (
                        name != "android_metadata" &&
                        name != "room_master_table" &&
                        !name.startsWith("sqlite_")
                    ) {
                        add(name)
                    }
                }
            }
        }

    private fun readTableSql(
        connection: SQLiteConnection,
        tableName: String,
    ): String? =
        connection.prepare(
            """
            SELECT sql
            FROM main.sqlite_master
            WHERE type = 'table' AND name = ?
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, tableName)
            if (statement.step()) statement.getText(0) else null
        }

    private fun readBarrierTriggers(
        connection: SQLiteConnection,
    ): Map<String, LegacyBarrierTriggerDefinition> =
        connection.prepare(
            """
            SELECT name, tbl_name, sql
            FROM main.sqlite_master
            WHERE type = 'trigger'
            ORDER BY name
            """.trimIndent(),
        ).use { statement ->
            buildMap {
                while (statement.step()) {
                    val name = statement.getText(0)
                    if (name.startsWith(LEGACY_BARRIER_TRIGGER_PREFIX)) {
                        put(
                            name,
                            LegacyBarrierTriggerDefinition(
                                name = name,
                                tableName = statement.getText(1),
                                sql = statement.getText(2),
                            ),
                        )
                    }
                }
            }
        }

    private fun readBarrierEntities(
        connection: SQLiteConnection,
    ): List<LegacyBusinessWriteBarrierEntity> =
        connection.prepare(
            """
            SELECT
                `barrier_key`,
                `activation_kind`,
                COALESCE(`terminal_stage_ordinal`, -1),
                COALESCE(`terminal_receipt_fingerprint`, ''),
                `activation_receipt_fingerprint`,
                `activated_at_epoch_millis`
            FROM `$TABLE_NAME`
            ORDER BY `barrier_key`
            """.trimIndent(),
        ).use { statement ->
            buildList {
                while (statement.step()) {
                    val terminalOrdinal = statement.getLong(2)
                    val terminalFingerprint = statement.getText(3)
                    add(
                        LegacyBusinessWriteBarrierEntity(
                            barrierKey = statement.getText(0),
                            activationKind = statement.getText(1),
                            terminalStageOrdinal =
                                terminalOrdinal.takeUnless { it == -1L }?.toInt(),
                            terminalReceiptFingerprint =
                                terminalFingerprint.takeUnless { it.isEmpty() },
                            activationReceiptFingerprint = statement.getText(4),
                            activatedAtEpochMillis = statement.getLong(5),
                        ),
                    )
                }
            }
        }

    private fun tableHasRows(
        connection: SQLiteConnection,
        tableName: String,
    ): Boolean =
        connection.prepare(
            "SELECT EXISTS(SELECT 1 FROM `$tableName` LIMIT 1)",
        ).use { statement ->
            check(statement.step())
            statement.getLong(0) != 0L
        }

    private fun roomGeneratedBarrierTableSql(): String =
        """
        CREATE TABLE IF NOT EXISTS `$TABLE_NAME` (
            `barrier_key` TEXT NOT NULL,
            `activation_kind` TEXT NOT NULL,
            `terminal_stage_ordinal` INTEGER,
            `terminal_receipt_fingerprint` TEXT,
            `activation_receipt_fingerprint` TEXT NOT NULL,
            `activated_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`barrier_key`)
        )
        """.trimIndent()

    /**
     * SQLite removes `IF NOT EXISTS` when it persists a successful CREATE TABLE statement in
     * sqlite_master. Accept exactly that persisted form and the exact Room v45 exported form;
     * every column, affinity, nullability, order, and primary-key clause must otherwise match.
     */
    internal fun isExactKnownRoomGeneratedWeakBarrierTableSql(sql: String): Boolean {
        val expected =
            checkNotNull(roomCreateTableTokens(roomGeneratedBarrierTableSql())) {
                "Room v45 legacy barrier SQL contains unsupported syntax"
            }
        val actual = roomCreateTableTokens(sql) ?: return false
        return haveEquivalentPersistedSqlTokens(actual, expected)
    }

    internal fun isExactCanonicalTriggerSql(
        triggerName: String,
        sql: String,
    ): Boolean =
        triggerDefinitions()[triggerName]?.let { canonical ->
            hasExactPersistedSql(sql, canonical.sql)
        } ?: false

    private fun stage(
        ordinal: Int,
        name: String,
        target: String,
    ) = LegacyTerminalCutoverStageExpectation(ordinal, name, target)
}

internal enum class LegacyBusinessMutation(
    val sql: String,
) {
    INSERT("INSERT"),
    UPDATE("UPDATE"),
    DELETE("DELETE"),
}

internal val LEGACY_BUSINESS_WRITE_BARRIER_MIGRATION_44_45 =
    object : Migration(44, 45) {
        override suspend fun migrate(connection: SQLiteConnection) {
            LegacyBusinessWriteBarrierSchema.migrateFromV44(connection)
        }
    }

internal class LegacyBusinessWriteBarrierCreateCallback(
    private val autoActivateFreshEmpty: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : RoomDatabase.Callback() {
    override suspend fun onCreate(connection: SQLiteConnection) {
        LegacyBusinessWriteBarrierSchema.initializeCreatedDatabase(connection)
        if (autoActivateFreshEmpty) {
            LegacyBusinessWriteBarrierSchema.activateFreshEmpty(
                connection = connection,
                activatedAtEpochMillis = clock().also { now -> require(now >= 0L) },
            )
        }
    }

    override suspend fun onOpen(connection: SQLiteConnection) {
        LegacyBusinessWriteBarrierSchema.verifyOrRepairBeforeOpen(connection)
        val now = clock().also { value -> require(value >= 0L) }
        connection.execSQL(
            """
            UPDATE `tutor_free_response_outbox`
            SET `status` = 'FAILED_CLOSED',
                `encrypted_answer` = NULL,
                `nonce` = NULL,
                `lease_owner_id` = NULL,
                `lease_generation_id` = NULL,
                `lease_token` = NULL,
                `lease_expires_at_epoch_millis` = NULL,
                `updated_at_epoch_millis` = CASE
                    WHEN `updated_at_epoch_millis` > $now THEN `updated_at_epoch_millis`
                    ELSE $now
                END
            WHERE `status` IN ('NEEDS_DISPATCH', 'IN_FLIGHT')
              AND (`discard_after_epoch_millis` <= $now
                   OR `claimed_at_epoch_millis` > $now
                   OR `updated_at_epoch_millis` > $now
                   OR (`status` = 'IN_FLIGHT' AND (
                       `lease_owner_id` IS NULL
                       OR `lease_generation_id` IS NULL
                       OR `lease_token` IS NULL
                       OR `lease_expires_at_epoch_millis` IS NULL
                   )))
            """.trimIndent(),
        )
    }
}

private fun String.requireLowerSha256(label: String) {
    require(length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }) {
        "$label must be a lowercase SHA-256 fingerprint"
    }
}

private val SQLITE_SCHEMA_NAME = Regex("[a-z][a-z0-9_]*")
private val SQL_IDENTIFIER_TOKEN = Regex("[A-Za-z_][A-Za-z0-9_]*")
private const val LEGACY_BARRIER_TRIGGER_PREFIX = "legacy_business_barrier_"
private const val BARRIER_REBUILD_TABLE_NAME =
    "legacy_business_write_barrier_canonical_rebuild"

private fun hasExactPersistedSql(
    actualSql: String,
    canonicalSql: String,
): Boolean {
    val canonicalTokens =
        tokenizePersistedSql(canonicalSql)
            ?: error("Canonical legacy barrier SQL contains unsupported syntax")
    val actualTokens = tokenizePersistedSql(actualSql) ?: return false
    return haveEquivalentPersistedSqlTokens(actualTokens, canonicalTokens)
}

private fun roomCreateTableTokens(sql: String): List<LegacyBarrierSqlToken>? {
    val tokens = tokenizePersistedSql(sql) ?: return null
    val create = LegacyBarrierSqlToken.BareWord("CREATE")
    val table = LegacyBarrierSqlToken.BareWord("TABLE")
    val ifToken = LegacyBarrierSqlToken.BareWord("IF")
    val not = LegacyBarrierSqlToken.BareWord("NOT")
    val exists = LegacyBarrierSqlToken.BareWord("EXISTS")
    return when {
        tokens.take(5) == listOf(create, table, ifToken, not, exists) ->
            tokens.take(2) + tokens.drop(5)
        tokens.take(2) == listOf(create, table) -> tokens
        else -> null
    }
}

private sealed interface LegacyBarrierSqlToken {
    data class BareWord(
        val value: String,
    ) : LegacyBarrierSqlToken

    data class QuotedIdentifier(
        val value: String,
    ) : LegacyBarrierSqlToken

    data class StringLiteral(
        val source: String,
    ) : LegacyBarrierSqlToken

    data class Symbol(
        val value: Char,
    ) : LegacyBarrierSqlToken
}

/**
 * Tokenizes only the SQL subset emitted by this file and Room's v45 CREATE TABLE export.
 *
 * This is deliberately not a permissive SQL parser. It rejects comments, escaped or unusual
 * identifier forms, and every unknown token. Single-quoted literals are retained byte-for-byte,
 * including doubled-quote escapes, so identifier quote normalization can never alter a value.
 */
private fun tokenizePersistedSql(sql: String): List<LegacyBarrierSqlToken>? {
    val tokens = mutableListOf<LegacyBarrierSqlToken>()
    var index = 0
    while (index < sql.length) {
        val character = sql[index]
        when {
            character.isSupportedSqlWhitespace() -> index += 1
            character == '\'' -> {
                val start = index
                index += 1
                var closed = false
                while (index < sql.length) {
                    if (sql[index] != '\'') {
                        index += 1
                        continue
                    }
                    if (index + 1 < sql.length && sql[index + 1] == '\'') {
                        index += 2
                    } else {
                        index += 1
                        closed = true
                        break
                    }
                }
                if (!closed) return null
                tokens +=
                    LegacyBarrierSqlToken.StringLiteral(
                        source = sql.substring(start, index),
                    )
            }
            character == '`' || character == '"' -> {
                val quote = character
                val start = index + 1
                index += 1
                while (index < sql.length && sql[index] != quote) {
                    index += 1
                }
                if (index >= sql.length) return null
                val identifier = sql.substring(start, index)
                index += 1
                if (sql.getOrNull(index) == quote) return null
                if (!SQL_IDENTIFIER_TOKEN.matches(identifier)) return null
                tokens += LegacyBarrierSqlToken.QuotedIdentifier(identifier)
            }
            character.isAsciiSqlWordCharacter() -> {
                val start = index
                while (
                    index < sql.length &&
                    sql[index].isAsciiSqlWordCharacter()
                ) {
                    index += 1
                }
                tokens += LegacyBarrierSqlToken.BareWord(sql.substring(start, index))
            }
            character == '-' && sql.getOrNull(index + 1) == '-' -> return null
            character == '/' && sql.getOrNull(index + 1) == '*' -> return null
            character in SQL_SUPPORTED_SYMBOLS -> {
                tokens += LegacyBarrierSqlToken.Symbol(character)
                index += 1
            }
            else -> return null
        }
    }
    if (tokens.lastOrNull() == LegacyBarrierSqlToken.Symbol(';')) {
        tokens.removeLast()
    }
    return tokens
}

/**
 * Identifier quote persistence differences are allowed only where the canonical SQL itself marks
 * an identifier with backticks. A quoted token can therefore never stand in for a canonical bare
 * keyword, type, function, collation, or conflict mode.
 */
private fun haveEquivalentPersistedSqlTokens(
    actual: List<LegacyBarrierSqlToken>,
    canonical: List<LegacyBarrierSqlToken>,
): Boolean =
    actual.size == canonical.size &&
        actual.zip(canonical).all { (actualToken, canonicalToken) ->
            when (canonicalToken) {
                is LegacyBarrierSqlToken.QuotedIdentifier ->
                    when (actualToken) {
                        is LegacyBarrierSqlToken.QuotedIdentifier ->
                            actualToken.value == canonicalToken.value
                        is LegacyBarrierSqlToken.BareWord ->
                            actualToken.value == canonicalToken.value
                        is LegacyBarrierSqlToken.StringLiteral,
                        is LegacyBarrierSqlToken.Symbol,
                        -> false
                    }
                is LegacyBarrierSqlToken.BareWord,
                is LegacyBarrierSqlToken.StringLiteral,
                is LegacyBarrierSqlToken.Symbol,
                -> actualToken == canonicalToken
            }
        }

private fun Char.isSupportedSqlWhitespace(): Boolean =
    this == ' ' || this == '\t' || this == '\r' || this == '\n' || this == '\u000C'

private fun Char.isAsciiSqlWordCharacter(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '_'

private val SQL_SUPPORTED_SYMBOLS = setOf('(', ')', ',', '.', '=', '>', ';')
