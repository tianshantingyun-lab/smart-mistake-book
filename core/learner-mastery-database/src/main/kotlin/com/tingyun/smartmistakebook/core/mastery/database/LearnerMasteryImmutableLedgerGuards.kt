package com.tingyun.smartmistakebook.core.mastery.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

private val SQL_IDENTIFIER = Regex("[a-z][a-z0-9_]*")

private val LEARNER_MASTERY_CUTOVER_GUARD_TABLE_NAMES =
    setOf(
        "mastery_legacy_fact_migration_checkpoint",
        LEARNER_MASTERY_CUTOVER_FENCE_TABLE,
        LEARNER_MASTERY_CUTOVER_COMPLETION_RECEIPT_TABLE,
        LEARNER_MASTERY_MIGRATION_DESTINATION_RECORD_TABLE,
        LEARNER_MASTERY_LEGACY_SNAPSHOT_PAGE_TABLE,
        LEARNER_MASTERY_LEGACY_OBSERVATION_SNAPSHOT_TABLE,
    )

internal data class LearnerMasteryTriggerDefinition(
    val name: String,
    val sql: String,
) {
    init {
        require(SQL_IDENTIFIER.matches(name)) {
            "Learner-mastery trigger name is not a safe SQL identifier"
        }
        require(sql.isNotBlank()) {
            "Learner-mastery trigger definition must not be blank"
        }
    }
}

internal fun installLearnerMasteryImmutableLedgerGuards(
    connection: SQLiteConnection,
    tableNames: Set<String> = LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES,
    includeCutoverInsertGuards: Boolean = true,
    includeRawSnapshotGuards: Boolean = true,
) {
    // The same installer is used by every supported historical migration. Future immutable
    // ledgers must not make an older schema attempt to create a trigger on a table that does not
    // exist yet. Room still validates the exact destination schema after each migration.
    val availableTableNames =
        tableNames.filterTo(mutableSetOf()) { tableName ->
            connection.hasLearnerMasteryTable(tableName)
        }
    val definitions =
        learnerMasteryImmutableLedgerTriggerDefinitions(
            tableNames = availableTableNames,
            includeCutoverInsertGuards = includeCutoverInsertGuards,
            includeRawSnapshotGuards = includeRawSnapshotGuards,
        )
    replaceLearnerMasteryTriggerDefinitionsAtomically(
        connection = connection,
        savepointName = IMMUTABLE_LEDGER_GUARD_INSTALL_SAVEPOINT,
        definitions = definitions,
        audit = {
            if (includeCutoverInsertGuards) {
                auditLearnerMasteryTerminalLedgerState(
                    connection = connection,
                    includeRawSnapshotLedger = includeRawSnapshotGuards,
                )
            }
        },
    )
}

private fun SQLiteConnection.hasLearnerMasteryTable(tableName: String): Boolean =
    prepare(
        "SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = ? LIMIT 1",
    ).use { statement ->
        statement.bindText(1, tableName)
        statement.step()
    }

internal fun learnerMasteryImmutableLedgerTriggerDefinitions(
    tableNames: Set<String> = LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES,
    includeCutoverInsertGuards: Boolean = true,
    includeRawSnapshotGuards: Boolean = true,
): List<LearnerMasteryTriggerDefinition> {
    tableNames.forEach { tableName ->
        require(SQL_IDENTIFIER.matches(tableName)) {
            "Learner-mastery immutable table name is not a safe SQL identifier"
        }
    }
    val immutableDefinitions =
        tableNames.sorted().flatMap { tableName ->
            listOf(
                immutableUpdateTriggerDefinition(tableName),
                immutableTriggerDefinition(tableName, "DELETE"),
            )
        } +
            if ("mastery_store_metadata" in tableNames) {
                listOf(learnerMasteryCalibrationAuditMetadataInsertTriggerDefinition())
            } else {
                emptyList()
            }
    if (!includeCutoverInsertGuards) return immutableDefinitions
    return immutableDefinitions +
        learnerMasteryTerminalInsertTriggerDefinitions(includeRawSnapshotGuards)
}

internal fun replaceLearnerMasteryTriggerDefinitionsAtomically(
    connection: SQLiteConnection,
    savepointName: String,
    definitions: List<LearnerMasteryTriggerDefinition>,
    audit: () -> Unit = {},
) {
    require(SQL_IDENTIFIER.matches(savepointName)) {
        "Learner-mastery trigger savepoint is not a safe SQL identifier"
    }
    require(definitions.map(LearnerMasteryTriggerDefinition::name).distinct().size == definitions.size) {
        "Learner-mastery trigger definitions contain duplicate names"
    }
    withLearnerMasterySchemaInstallTransaction(
        connection = connection,
        savepointName = savepointName,
    ) {
        audit()
        definitions.forEach { definition ->
            connection.execSQL("DROP TRIGGER IF EXISTS ${definition.name}")
        }
        definitions.forEach { definition ->
            connection.execSQL(definition.sql)
        }
        verifyLearnerMasteryTriggerDefinitions(connection, definitions)
    }
}

internal fun hasCanonicalLearnerMasteryTriggerDefinitions(
    connection: SQLiteConnection,
    definitions: List<LearnerMasteryTriggerDefinition>,
): Boolean =
    definitions.all { definition ->
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
                canonicalizeLearnerMasterySql(definition.sql)
        }
    }

internal fun canonicalizeLearnerMasterySql(sql: String): String {
    val tokens = mutableListOf<String>()
    var index = 0
    while (index < sql.length) {
        val character = sql[index]
        when {
            character.isWhitespace() -> index += 1
            character == '\'' || character == '"' || character == '`' -> {
                val (quotedToken, nextIndex) =
                    readLearnerMasteryQuotedSqlToken(
                        sql = sql,
                        startIndex = index,
                        closingCharacter = character,
                    )
                tokens += quotedToken
                index = nextIndex
            }
            character == '[' -> {
                val (quotedToken, nextIndex) =
                    readLearnerMasteryQuotedSqlToken(
                        sql = sql,
                        startIndex = index,
                        closingCharacter = ']',
                    )
                tokens += quotedToken
                index = nextIndex
            }
            character.isLetterOrDigit() || character == '_' || character == '$' -> {
                val startIndex = index
                while (
                    index < sql.length &&
                    (
                        sql[index].isLetterOrDigit() ||
                            sql[index] == '_' ||
                            sql[index] == '$'
                        )
                ) {
                    index += 1
                }
                tokens += sql.substring(startIndex, index).lowercase()
            }
            index + 1 < sql.length &&
                sql.substring(index, index + 2) in LEARNER_MASTERY_TWO_CHARACTER_SQL_TOKENS -> {
                tokens += sql.substring(index, index + 2)
                index += 2
            }
            else -> {
                tokens += character.toString()
                index += 1
            }
        }
    }
    while (tokens.lastOrNull() == ";") {
        tokens.removeAt(tokens.lastIndex)
    }
    return tokens.joinToString(separator = SQL_TOKEN_SEPARATOR)
}

internal fun withLearnerMasterySchemaInstallTransaction(
    connection: SQLiteConnection,
    savepointName: String,
    install: () -> Unit,
) {
    require(SQL_IDENTIFIER.matches(savepointName)) {
        "Learner-mastery schema-install savepoint is not a safe SQL identifier"
    }
    /*
     * AndroidSQLiteDriver delegates to SQLiteDatabase, whose connection pool is pinned by a
     * framework transaction but not by a raw SAVEPOINT. Without that pin, DROP, CREATE and
     * sqlite_schema verification can run on different physical connections and observe different
     * schema generations. The inner savepoint remains the portable rollback boundary when Room
     * already owns an outer migration transaction.
     */
    var installTransactionStarted = false
    var savepointStarted = false
    try {
        if (!connection.inTransaction()) {
            connection.execSQL("BEGIN IMMEDIATE TRANSACTION")
            installTransactionStarted = true
        }
        connection.execSQL("SAVEPOINT $savepointName")
        savepointStarted = true
        install()
        connection.execSQL("RELEASE SAVEPOINT $savepointName")
        savepointStarted = false
        if (installTransactionStarted) {
            connection.execSQL("COMMIT TRANSACTION")
            installTransactionStarted = false
        }
    } catch (failure: Throwable) {
        if (savepointStarted) {
            rollbackLearnerMasterySavepoint(
                connection = connection,
                savepointName = savepointName,
                failure = failure,
            )
        }
        if (installTransactionStarted) {
            runCatching {
                connection.execSQL("ROLLBACK TRANSACTION")
            }.onFailure(failure::addSuppressed)
        }
        throw failure
    }
}

internal fun dropLearnerMasteryImmutabilityTriggers(connection: SQLiteConnection) {
    (
        LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES +
            LEGACY_MUTABLE_IMMUTABILITY_TRIGGER_TABLE_NAMES
        ).sorted().forEach { tableName ->
        connection.execSQL("DROP TRIGGER IF EXISTS immutable_${tableName}_update")
        connection.execSQL("DROP TRIGGER IF EXISTS immutable_${tableName}_delete")
    }
    connection.execSQL(
        "DROP TRIGGER IF EXISTS validate_mastery_store_metadata_calibration_audit_insert",
    )
}

internal val LEARNER_MASTERY_CUTOVER_IMMUTABILITY_TRIGGER_NAMES: Set<String> =
    learnerMasteryImmutableLedgerTriggerDefinitions(
        tableNames = LEARNER_MASTERY_CUTOVER_GUARD_TABLE_NAMES,
        includeCutoverInsertGuards = true,
        includeRawSnapshotGuards = true,
    ).mapTo(linkedSetOf(), LearnerMasteryTriggerDefinition::name)

private fun immutableTriggerDefinition(
    tableName: String,
    operation: String,
): LearnerMasteryTriggerDefinition {
    val normalizedOperation = operation.lowercase()
    val name = "immutable_${tableName}_$normalizedOperation"
    return LearnerMasteryTriggerDefinition(
        name = name,
        sql =
            """
            CREATE TRIGGER $name
            BEFORE $operation ON $tableName
            BEGIN
                SELECT RAISE(ABORT, 'immutable learner-mastery record');
            END
            """.trimIndent(),
    )
}

private fun immutableUpdateTriggerDefinition(tableName: String): LearnerMasteryTriggerDefinition =
    if (tableName == "mastery_store_metadata") {
        learnerMasteryCalibrationAuditMetadataUpdateTriggerDefinition()
    } else {
        immutableTriggerDefinition(tableName, "UPDATE")
    }

private fun learnerMasteryTerminalInsertTriggerDefinitions(
    includeRawSnapshotGuards: Boolean,
): List<LearnerMasteryTriggerDefinition> {
    val definitions =
        mutableListOf(
            LearnerMasteryTriggerDefinition(
                name = "immutable_mastery_cutover_fence_insert",
                sql =
                    """
                    CREATE TRIGGER immutable_mastery_cutover_fence_insert
                    BEFORE INSERT ON mastery_cutover_fence
                    WHEN NEW.singleton_key != '$LEARNER_MASTERY_CUTOVER_SINGLETON_KEY'
                    BEGIN
                        SELECT RAISE(ABORT, 'invalid mastery cutover singleton');
                    END
                    """.trimIndent(),
            ),
            LearnerMasteryTriggerDefinition(
                name = "immutable_mastery_cutover_completion_receipt_insert",
                sql =
                    """
                    CREATE TRIGGER immutable_mastery_cutover_completion_receipt_insert
                    BEFORE INSERT ON mastery_cutover_completion_receipt
                    WHEN NEW.singleton_key != '$LEARNER_MASTERY_CUTOVER_SINGLETON_KEY'
                      OR NOT EXISTS (
                          SELECT 1
                          FROM mastery_cutover_fence AS fence
                          WHERE fence.singleton_key = NEW.singleton_key
                            AND fence.cutover_generation = NEW.cutover_generation
                            AND fence.cutover_intent_fingerprint =
                                NEW.cutover_intent_fingerprint
                            AND fence.fence_fingerprint =
                                NEW.authority_fence_fingerprint
                      )
                    BEGIN
                        SELECT RAISE(
                            ABORT,
                            'mastery cutover completion receipt is not bound to its fence'
                        );
                    END
                    """.trimIndent(),
            ),
            LearnerMasteryTriggerDefinition(
                name = "sealed_mastery_legacy_fact_migration_checkpoint_insert",
                sql =
                    """
                    CREATE TRIGGER sealed_mastery_legacy_fact_migration_checkpoint_insert
                    BEFORE INSERT ON mastery_legacy_fact_migration_checkpoint
                    WHEN EXISTS (SELECT 1 FROM mastery_cutover_fence)
                      OR EXISTS (
                          SELECT 1
                          FROM mastery_legacy_fact_migration_checkpoint
                          WHERE learner_id = NEW.learner_id
                            AND source_generation = NEW.source_generation
                            AND final_batch = 1
                      )
                    BEGIN
                        SELECT RAISE(ABORT, 'mastery migration ledger is terminal');
                    END
                    """.trimIndent(),
            ),
            LearnerMasteryTriggerDefinition(
                name = "sealed_mastery_legacy_fact_migration_destination_record_insert",
                sql =
                    """
                    CREATE TRIGGER
                        sealed_mastery_legacy_fact_migration_destination_record_insert
                    BEFORE INSERT ON mastery_legacy_fact_migration_destination_record
                    WHEN EXISTS (SELECT 1 FROM mastery_cutover_fence)
                      OR EXISTS (
                          SELECT 1
                          FROM mastery_legacy_fact_migration_checkpoint
                          WHERE learner_id = NEW.learner_id
                            AND source_generation = NEW.source_generation
                            AND final_batch = 1
                      )
                    BEGIN
                        SELECT RAISE(ABORT, 'mastery migration ledger is terminal');
                    END
                    """.trimIndent(),
            ),
        )
    if (!includeRawSnapshotGuards) return definitions
    definitions +=
        LearnerMasteryTriggerDefinition(
            name = "sealed_mastery_legacy_observation_snapshot_insert",
            sql =
                """
                CREATE TRIGGER sealed_mastery_legacy_observation_snapshot_insert
                BEFORE INSERT ON mastery_legacy_observation_snapshot
                WHEN EXISTS (SELECT 1 FROM mastery_cutover_fence)
                  OR EXISTS (
                      SELECT 1
                      FROM mastery_legacy_observation_snapshot_page
                      WHERE learner_id = NEW.learner_id
                        AND source_generation = NEW.source_generation
                        AND batch_sequence = NEW.batch_sequence
                  )
                  OR EXISTS (
                      SELECT 1
                      FROM mastery_legacy_observation_snapshot_page
                      WHERE learner_id = NEW.learner_id
                        AND source_generation = NEW.source_generation
                        AND final_batch = 1
                  )
                BEGIN
                    SELECT RAISE(ABORT, 'mastery raw snapshot ledger is terminal');
                END
                """.trimIndent(),
        )
    definitions +=
        LearnerMasteryTriggerDefinition(
            name = "sealed_mastery_legacy_observation_snapshot_page_insert",
            sql =
                """
                CREATE TRIGGER sealed_mastery_legacy_observation_snapshot_page_insert
                BEFORE INSERT ON mastery_legacy_observation_snapshot_page
                WHEN EXISTS (SELECT 1 FROM mastery_cutover_fence)
                  OR EXISTS (
                      SELECT 1
                      FROM mastery_legacy_observation_snapshot_page
                      WHERE learner_id = NEW.learner_id
                        AND source_generation = NEW.source_generation
                        AND final_batch = 1
                  )
                BEGIN
                    SELECT RAISE(ABORT, 'mastery raw snapshot ledger is terminal');
                END
                """.trimIndent(),
        )
    return definitions
}

private fun verifyLearnerMasteryTriggerDefinitions(
    connection: SQLiteConnection,
    definitions: List<LearnerMasteryTriggerDefinition>,
) {
    definitions.forEach { definition ->
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
                "Learner-mastery guard ${definition.name} was not installed"
            }
            check(
                canonicalizeLearnerMasterySql(statement.getText(0)) ==
                    canonicalizeLearnerMasterySql(definition.sql),
            ) {
                "Learner-mastery guard ${definition.name} is not canonical"
            }
        }
    }
}

private fun auditLearnerMasteryTerminalLedgerState(
    connection: SQLiteConnection,
    includeRawSnapshotLedger: Boolean,
) {
    check(
        !connection.hasLearnerMasteryGuardAuditRow(
            """
            SELECT 1
            FROM mastery_cutover_fence
            WHERE singleton_key != '$LEARNER_MASTERY_CUTOVER_SINGLETON_KEY'
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "Learner-mastery cutover fence has an invalid singleton"
    }
    check(
        !connection.hasLearnerMasteryGuardAuditRow(
            """
            SELECT 1
            FROM mastery_cutover_completion_receipt AS receipt
            LEFT JOIN mastery_cutover_fence AS fence
              ON fence.singleton_key = receipt.singleton_key
             AND fence.cutover_generation = receipt.cutover_generation
             AND fence.cutover_intent_fingerprint = receipt.cutover_intent_fingerprint
             AND fence.fence_fingerprint = receipt.authority_fence_fingerprint
            WHERE receipt.singleton_key != '$LEARNER_MASTERY_CUTOVER_SINGLETON_KEY'
               OR fence.singleton_key IS NULL
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "Learner-mastery cutover completion receipt is not bound to its fence"
    }
    check(
        !connection.hasLearnerMasteryGuardAuditRow(
            """
            SELECT 1
            FROM mastery_legacy_fact_migration_checkpoint AS terminal
            INNER JOIN mastery_legacy_fact_migration_checkpoint AS later
              ON later.learner_id = terminal.learner_id
             AND later.source_generation = terminal.source_generation
             AND later.batch_sequence > terminal.batch_sequence
            WHERE terminal.final_batch = 1
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "Learner-mastery migration checkpoint continues after a final batch"
    }
    check(
        !connection.hasLearnerMasteryGuardAuditRow(
            """
            SELECT 1
            FROM mastery_legacy_fact_migration_checkpoint AS terminal
            INNER JOIN mastery_legacy_fact_migration_destination_record AS later
              ON later.learner_id = terminal.learner_id
             AND later.source_generation = terminal.source_generation
             AND later.batch_sequence > terminal.batch_sequence
            WHERE terminal.final_batch = 1
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "Learner-mastery migration destination continues after a final batch"
    }
    if (!includeRawSnapshotLedger) return
    check(
        !connection.hasLearnerMasteryGuardAuditRow(
            """
            SELECT 1
            FROM mastery_legacy_observation_snapshot_page AS terminal
            INNER JOIN mastery_legacy_observation_snapshot_page AS later
              ON later.learner_id = terminal.learner_id
             AND later.source_generation = terminal.source_generation
             AND later.batch_sequence > terminal.batch_sequence
            WHERE terminal.final_batch = 1
            LIMIT 1
            """.trimIndent(),
        ),
    ) {
        "Learner-mastery raw snapshot ledger continues after a final batch"
    }
}

private fun rollbackLearnerMasterySavepoint(
    connection: SQLiteConnection,
    savepointName: String,
    failure: Throwable,
) {
    runCatching {
        connection.execSQL("ROLLBACK TO SAVEPOINT $savepointName")
    }.onFailure(failure::addSuppressed)
    runCatching {
        connection.execSQL("RELEASE SAVEPOINT $savepointName")
    }.onFailure(failure::addSuppressed)
}

private fun SQLiteConnection.hasLearnerMasteryGuardAuditRow(sql: String): Boolean =
    prepare(sql).use { statement -> statement.step() }

private fun readLearnerMasteryQuotedSqlToken(
    sql: String,
    startIndex: Int,
    closingCharacter: Char,
): Pair<String, Int> {
    val token = StringBuilder()
    token.append(sql[startIndex])
    var index = startIndex + 1
    while (index < sql.length) {
        val character = sql[index]
        token.append(character)
        index += 1
        if (character != closingCharacter) continue
        if (index < sql.length && sql[index] == closingCharacter) {
            token.append(sql[index])
            index += 1
        } else {
            break
        }
    }
    return token.toString() to index
}

private val LEGACY_MUTABLE_IMMUTABILITY_TRIGGER_TABLE_NAMES =
    setOf("mastery_store_metadata")

private val LEARNER_MASTERY_TWO_CHARACTER_SQL_TOKENS =
    setOf("<=", ">=", "!=", "<>", "==", "||", "->")

private const val SQL_TOKEN_SEPARATOR = "\u001f"

private const val IMMUTABLE_LEDGER_GUARD_INSTALL_SAVEPOINT =
    "learner_mastery_immutable_ledger_guard_install"
