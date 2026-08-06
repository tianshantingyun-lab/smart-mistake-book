package com.tingyun.smartmistakebook.core.mastery.database

import android.database.sqlite.SQLiteDatabase
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.SubjectKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerMasteryGuardHardeningInstrumentedTest {
    @Test
    fun onOpenReplacesSpoofedOperationTableBodyAndTerminalGuards() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "immutable-guard-hardening.mastery-test.db"
        val databasePath = context.getDatabasePath(databaseName)
        context.deleteDatabase(databaseName)
        try {
            forceOpen(databaseName)
            val definitions =
                learnerMasteryImmutableLedgerTriggerDefinitions()
                    .associateBy(LearnerMasteryTriggerDefinition::name)
            val updateName = "immutable_mastery_calibration_snapshot_update"
            val deleteName = "immutable_mastery_calibration_snapshot_delete"
            val terminalName = "immutable_mastery_cutover_fence_insert"
            val spoofedDefinitions =
                listOf(
                    updateName to
                        """
                        CREATE TRIGGER $updateName
                        BEFORE UPDATE ON mastery_calibration_snapshot
                        BEGIN SELECT 1; END
                        """.trimIndent(),
                    updateName to
                        """
                        CREATE TRIGGER $updateName
                        BEFORE DELETE ON mastery_calibration_snapshot
                        BEGIN
                            SELECT RAISE(ABORT, 'immutable learner-mastery record');
                        END
                        """.trimIndent(),
                    updateName to
                        """
                        CREATE TRIGGER $updateName
                        BEFORE UPDATE ON mastery_source_fact
                        BEGIN
                            SELECT RAISE(ABORT, 'immutable learner-mastery record');
                        END
                        """.trimIndent(),
                    updateName to
                        """
                        CREATE TRIGGER $updateName
                        BEFORE UPDATE ON mastery_calibration_snapshot
                        BEGIN SELECT RAISE(ABORT, 'wrong guard'); END
                        """.trimIndent(),
                    deleteName to
                        """
                        CREATE TRIGGER $deleteName
                        BEFORE DELETE ON mastery_calibration_snapshot
                        BEGIN SELECT 1; END
                        """.trimIndent(),
                    terminalName to
                        """
                        CREATE TRIGGER $terminalName
                        BEFORE INSERT ON mastery_cutover_fence
                        BEGIN SELECT 1; END
                        """.trimIndent(),
                )

            spoofedDefinitions.forEach { (triggerName, spoofedSql) ->
                SQLiteDatabase.openDatabase(
                    databasePath.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { sqlite ->
                    sqlite.execSQL("DROP TRIGGER IF EXISTS $triggerName")
                    sqlite.execSQL(spoofedSql)
                }

                forceOpen(databaseName)

                SQLiteDatabase.openDatabase(
                    databasePath.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { sqlite ->
                    val installedSql = sqlite.triggerSql(triggerName)
                    val expectedSql = checkNotNull(definitions[triggerName]).sql
                    assertEquals(
                        canonicalizeLearnerMasterySql(expectedSql),
                        canonicalizeLearnerMasterySql(installedSql),
                    )
                    when (triggerName) {
                        updateName ->
                            assertTrue(
                                runCatching {
                                    sqlite.execSQL(
                                        """
                                        UPDATE mastery_calibration_snapshot
                                        SET subject = subject
                                        WHERE rowid = (
                                            SELECT rowid
                                            FROM mastery_calibration_snapshot
                                            LIMIT 1
                                        )
                                        """.trimIndent(),
                                    )
                                }.isFailure,
                            )

                        deleteName ->
                            assertTrue(
                                runCatching {
                                    sqlite.execSQL(
                                        """
                                        DELETE FROM mastery_calibration_snapshot
                                        WHERE rowid = (
                                            SELECT rowid
                                            FROM mastery_calibration_snapshot
                                            LIMIT 1
                                        )
                                        """.trimIndent(),
                                    )
                                }.isFailure,
                            )

                        terminalName ->
                            assertTrue(
                                runCatching {
                                    sqlite.execSQL(
                                        """
                                        INSERT INTO mastery_cutover_fence (
                                            singleton_key, cutover_generation,
                                            student_import_evidence_fingerprint,
                                            mastery_import_evidence_fingerprint,
                                            cutover_intent_fingerprint, fence_fingerprint
                                        ) VALUES (
                                            'wrong-singleton', 1,
                                            '${"a".repeat(64)}', '${"b".repeat(64)}',
                                            '${"c".repeat(64)}', '${"d".repeat(64)}'
                                        )
                                        """.trimIndent(),
                                    )
                                }.isFailure,
                            )
                    }
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun failedMidInstallRollsBackTheEntireCanonicalGuardSet() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val databaseName = "immutable-guard-atomicity.mastery-test.db"
        context.deleteDatabase(databaseName)
        val helper =
            MigrationTestHelper(
                instrumentation = instrumentation,
                file = context.getDatabasePath(databaseName),
                driver = AndroidSQLiteDriver(),
                databaseClass = LearnerMasteryRoomDatabase::class,
            )
        try {
            helper.createDatabase(LEARNER_MASTERY_DATABASE_VERSION).use { connection ->
                installLearnerMasteryImmutableLedgerGuards(connection)
                val canonicalDefinitions = learnerMasteryImmutableLedgerTriggerDefinitions()
                val before = connection.triggerSql(canonicalDefinitions.map { it.name })
                val failure =
                    runCatching {
                        replaceLearnerMasteryTriggerDefinitionsAtomically(
                            connection = connection,
                            savepointName = "learner_mastery_guard_failure_test",
                            definitions =
                                canonicalDefinitions +
                                    LearnerMasteryTriggerDefinition(
                                        name = "immutable_missing_table_update",
                                        sql =
                                            """
                                            CREATE TRIGGER immutable_missing_table_update
                                            BEFORE UPDATE ON mastery_calibration_snapshot
                                            BEGIN
                                                SELECT RAISE(
                                                    ABORT,
                                                    'immutable learner-mastery record'
                                                );
                                            """.trimIndent(),
                                    ),
                        )
                    }.exceptionOrNull()

                assertNotNull(failure)
                assertEquals(before, connection.triggerSql(canonicalDefinitions.map { it.name }))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun canonicalGuardSqlDoesNotBypassAuditWhenPersistedStateChanged() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "calibration-audit-watermark.mastery-test.db"
        val databasePath = context.getDatabasePath(databaseName)
        val projectionInsertGuard = "validate_mastery_knowledge_projection_calibration_insert"
        context.deleteDatabase(databaseName)
        try {
            forceOpen(databaseName)
            // The first query publishes the empty active generation after onOpen. A second open
            // attests that generation so the only later state change is the injected projection.
            forceOpen(databaseName)

            SQLiteDatabase.openDatabase(
                databasePath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                val canonicalGuardSql = sqlite.triggerSql(projectionInsertGuard)
                sqlite.execSQL("PRAGMA foreign_keys = OFF")
                sqlite.execSQL("DROP TRIGGER $projectionInsertGuard")
                sqlite.execSQL(
                    """
                    INSERT INTO mastery_knowledge_projection (
                        learner_id, subject, knowledge_node_id, taxonomy_version,
                        latest_evidence_knowledge_pack_version,
                        stable_node_identity_fingerprint,
                        positive_evidence_micros, negative_evidence_micros,
                        mastery_score_micros, mastery_state, trend, observation_count,
                        memory_stability_millis, recall_due_at_epoch_millis,
                        last_positive_at_epoch_millis, last_negative_at_epoch_millis,
                        last_evidence_at_epoch_millis, last_event_sequence,
                        last_ordered_event_id, projection_policy_version,
                        evidence_quality_micros, independent_problem_family_count,
                        distinct_presentation_count, calibration_snapshot_fingerprint,
                        calibration_profile_id, calibration_version
                    ) VALUES (
                        'guard-test-learner', 'MATH', 'tampered-node', 'taxonomy-v1',
                        'pack-v1', '${"a".repeat(64)}',
                        0, 0, 0, 'FAMILIARIZING', 'STABLE', 0,
                        0, 0, NULL, NULL, 0, 1,
                        'tampered-event', '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION',
                        0, 0, 0, NULL, NULL, NULL
                    )
                    """.trimIndent(),
                )
                sqlite.execSQL(canonicalGuardSql)
                assertEquals(
                    canonicalizeLearnerMasterySql(canonicalGuardSql),
                    canonicalizeLearnerMasterySql(sqlite.triggerSql(projectionInsertGuard)),
                )
            }

            val failure = runCatching { forceOpen(databaseName) }.exceptionOrNull()
            assertNotNull(failure)
            val requiredFailure = checkNotNull(failure)
            val failureMessages =
                generateSequence(requiredFailure) { it.cause }
                    .mapNotNull(Throwable::message)
                    .toList()
            assertTrue(
                failureMessages.joinToString(separator = "\n"),
                failureMessages.any {
                    it.contains("projection has an invalid calibration binding")
                },
            )
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun auditWatermarkIsOneBoundedRowAndOnlyAcceptsValidatedTransitions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "calibration-audit-singleton.mastery-test.db"
        val databasePath = context.getDatabasePath(databaseName)
        context.deleteDatabase(databaseName)
        try {
            // The second open attests the active generation created by the first query. The third
            // open is a same-state startup and must not write a new watermark revision.
            forceOpen(databaseName)
            forceOpen(databaseName)
            forceOpen(databaseName)

            lateinit var settledValue: String
            SQLiteDatabase.openDatabase(
                databasePath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                assertEquals(
                    1L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM mastery_store_metadata WHERE metadata_key = ?",
                        arrayOf(LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY),
                    ),
                )
                settledValue =
                    sqlite.stringForQuery(
                        "SELECT metadata_value FROM mastery_store_metadata WHERE metadata_key = ?",
                        arrayOf(LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY),
                    )
                assertTrue(settledValue.length < 2_048)

                val settled =
                    checkNotNull(decodeLearnerMasteryCalibrationAuditWatermark(settledValue))
                val newlyIncrementallyAuditedMutations =
                    listOf(
                        settled.copy(
                            attributionCount = settled.attributionCount + 1L,
                            attributionMaxRowId = settled.attributionMaxRowId + 1L,
                            attributionHeadFingerprint = "a".repeat(64),
                        ),
                        settled.copy(
                            supersessionCount = settled.supersessionCount + 1L,
                            supersessionMaxRowId = settled.supersessionMaxRowId + 1L,
                            supersessionHeadFingerprint = "b".repeat(64),
                        ),
                        settled.copy(
                            calibrationSnapshotCount = settled.calibrationSnapshotCount + 1L,
                            calibrationSnapshotMaxRowId =
                                settled.calibrationSnapshotMaxRowId + 1L,
                            calibrationSnapshotHeadFingerprint = "c".repeat(64),
                        ),
                    )
                val firstSyntheticEvent =
                    settled.copy(
                        auditRevision = settled.auditRevision + 1L,
                        transition = LearnerMasteryCalibrationAuditTransition.INCREMENTAL,
                        eventCount = settled.eventCount + 1L,
                        eventMaxRowId = settled.eventMaxRowId + 1L,
                        eventMaxSequence = settled.eventMaxSequence + 1L,
                        eventHeadFingerprint = "d".repeat(64),
                    )
                sqlite.execSQL(
                    "UPDATE mastery_store_metadata SET metadata_value = ? WHERE metadata_key = ?",
                    arrayOf(
                        firstSyntheticEvent.encode(),
                        LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY,
                    ),
                )
                val reusedHeadIncremental =
                    firstSyntheticEvent.copy(
                        auditRevision = firstSyntheticEvent.auditRevision + 1L,
                        eventCount = firstSyntheticEvent.eventCount + 1L,
                        eventMaxRowId = firstSyntheticEvent.eventMaxRowId + 1L,
                        eventMaxSequence = firstSyntheticEvent.eventMaxSequence + 1L,
                    ).encode()
                assertTrue(
                    runCatching {
                        sqlite.execSQL(
                            "UPDATE mastery_store_metadata SET metadata_value = ? " +
                                "WHERE metadata_key = ?",
                            arrayOf(
                                reusedHeadIncremental,
                                LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY,
                            ),
                        )
                    }.isFailure,
                )
                val restored =
                    settled.copy(
                        auditRevision = firstSyntheticEvent.auditRevision + 1L,
                        transition = LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET,
                    )
                settledValue = restored.encode()
                sqlite.execSQL(
                    "UPDATE mastery_store_metadata SET metadata_value = ? WHERE metadata_key = ?",
                    arrayOf(
                        settledValue,
                        LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY,
                    ),
                )

                var acceptedState = restored
                val incrementallyAuditedMutations =
                    newlyIncrementallyAuditedMutations +
                    listOf(
                        settled.copy(
                            reviewCaseCount = settled.reviewCaseCount + 1L,
                            reviewCaseMaxRowId = settled.reviewCaseMaxRowId + 1L,
                            reviewCaseHeadFingerprint = "e".repeat(64),
                        ),
                        settled.copy(
                            reviewResolutionCount = settled.reviewResolutionCount + 1L,
                            reviewResolutionMaxRowId = settled.reviewResolutionMaxRowId + 1L,
                            reviewResolutionHeadFingerprint = "f".repeat(64),
                        ),
                    )
                incrementallyAuditedMutations.forEach { changedState ->
                    acceptedState =
                        changedState.copy(
                            auditRevision = acceptedState.auditRevision + 1L,
                            transition = LearnerMasteryCalibrationAuditTransition.INCREMENTAL,
                        )
                    sqlite.execSQL(
                        "UPDATE mastery_store_metadata SET metadata_value = ? " +
                            "WHERE metadata_key = ?",
                        arrayOf(
                            acceptedState.encode(),
                            LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY,
                        ),
                    )
                    acceptedState =
                        settled.copy(
                            auditRevision = acceptedState.auditRevision + 1L,
                            transition = LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET,
                        )
                    sqlite.execSQL(
                        "UPDATE mastery_store_metadata SET metadata_value = ? " +
                            "WHERE metadata_key = ?",
                        arrayOf(
                            acceptedState.encode(),
                            LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY,
                        ),
                    )
                }
                newlyIncrementallyAuditedMutations.forEach { changedState ->
                    acceptedState =
                        changedState.copy(
                            auditRevision = acceptedState.auditRevision + 1L,
                            transition = LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET,
                        )
                    sqlite.execSQL(
                        "UPDATE mastery_store_metadata SET metadata_value = ? " +
                            "WHERE metadata_key = ?",
                        arrayOf(
                            acceptedState.encode(),
                            LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY,
                        ),
                    )
                    acceptedState =
                        settled.copy(
                            auditRevision = acceptedState.auditRevision + 1L,
                            transition = LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET,
                        )
                    sqlite.execSQL(
                        "UPDATE mastery_store_metadata SET metadata_value = ? " +
                            "WHERE metadata_key = ?",
                        arrayOf(
                            acceptedState.encode(),
                            LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY,
                        ),
                    )
                }
                settledValue = acceptedState.encode()

                sqlite.execSQL(
                    "INSERT INTO mastery_store_metadata(metadata_key, metadata_value) " +
                        "VALUES('guard-test-non-audit', 'original')",
                )
                sqlite.execSQL("PRAGMA recursive_triggers = OFF")
                sqlite.execSQL(
                    "INSERT OR REPLACE INTO mastery_store_metadata" +
                        "(metadata_key, metadata_value) VALUES('guard-test-non-audit', 'original')",
                )
                assertTrue(
                    runCatching {
                        sqlite.execSQL(
                            "INSERT OR REPLACE INTO mastery_store_metadata" +
                                "(metadata_key, metadata_value) " +
                                "VALUES('guard-test-non-audit', 'changed')",
                        )
                    }.isFailure,
                )
                assertEquals(
                    "original",
                    sqlite.stringForQuery(
                        "SELECT metadata_value FROM mastery_store_metadata " +
                            "WHERE metadata_key = 'guard-test-non-audit'",
                        emptyArray(),
                    ),
                )
                assertTrue(
                    runCatching {
                        sqlite.execSQL(
                            "UPDATE mastery_store_metadata SET metadata_value = 'changed' " +
                                "WHERE metadata_key = 'guard-test-non-audit'",
                        )
                    }.isFailure,
                )
                assertTrue(
                    runCatching {
                        sqlite.execSQL(
                            "UPDATE mastery_store_metadata SET metadata_value = metadata_value " +
                                "WHERE metadata_key = ?",
                            arrayOf(LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY),
                        )
                    }.isFailure,
                )
                assertTrue(
                    runCatching {
                        sqlite.execSQL(
                            "INSERT OR REPLACE INTO mastery_store_metadata" +
                                "(metadata_key, metadata_value) VALUES(?, ?)",
                            arrayOf(
                                LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY,
                                settledValue,
                            ),
                        )
                    }.isFailure,
                )
            }

            forceOpen(databaseName)
            SQLiteDatabase.openDatabase(
                databasePath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    1L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM mastery_store_metadata WHERE metadata_key = ?",
                        arrayOf(LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY),
                    ),
                )
                assertEquals(
                    settledValue,
                    sqlite.stringForQuery(
                        "SELECT metadata_value FROM mastery_store_metadata WHERE metadata_key = ?",
                        arrayOf(LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY),
                    ),
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private suspend fun forceOpen(databaseName: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LearnerMasteryStoreFactory.openForTest(
            context = context,
            databaseName = databaseName,
            nowEpochMillis = { 1_900_000_000_000L },
        ).use { store ->
            store.queryLocalMasteryContext(
                BoundLocalMasteryContextQuery(
                    learnerId = "guard-test-learner",
                    request =
                        LocalMasteryContextRequest(
                            subject = SubjectKind.MATH,
                            exactStableNodeFingerprints = emptyList(),
                            fallbackLimit = 0,
                        ),
                ),
            )
        }
    }
}

private fun SQLiteDatabase.triggerSql(triggerName: String): String =
    rawQuery(
        """
        SELECT sql
        FROM sqlite_schema
        WHERE type = 'trigger' AND name = ?
        """.trimIndent(),
        arrayOf(triggerName),
    ).use { cursor ->
        check(cursor.moveToFirst()) {
            "Missing learner-mastery trigger $triggerName"
        }
        cursor.getString(0)
    }

private fun SQLiteDatabase.stringForQuery(
    sql: String,
    args: Array<String>,
): String =
    rawQuery(sql, args).use { cursor ->
        check(cursor.moveToFirst()) { "Expected one string result" }
        cursor.getString(0)
    }

private fun SQLiteDatabase.longForQuery(
    sql: String,
    args: Array<String>,
): Long =
    rawQuery(sql, args).use { cursor ->
        check(cursor.moveToFirst()) { "Expected one long result" }
        cursor.getLong(0)
    }

private fun SQLiteConnection.triggerSql(triggerNames: List<String>): Map<String, String> =
    triggerNames.associateWith { triggerName ->
        prepare(
            """
            SELECT sql
            FROM sqlite_schema
            WHERE type = 'trigger' AND name = ?
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, triggerName)
            check(statement.step()) {
                "Missing learner-mastery trigger $triggerName"
            }
            statement.getText(0)
        }
    }
