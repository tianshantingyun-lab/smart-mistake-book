package com.tingyun.smartmistakebook.core.mastery.database

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scale benchmark gate for the real, on-disk learner-mastery ledger.
 *
 * Fixture construction is intentionally outside every timed region. The fixture retains the
 * production schema, foreign keys, indices, Room readers and immutability triggers; only the
 * immutable audit history is bulk-loaded through prepared statements so 100,000 events are
 * practical in an instrumented test.
 */
@RunWith(AndroidJUnit4::class)
class LearnerMasteryScalePerformanceInstrumentedTest {
    @Test
    fun hundredThousandRawSnapshotsUseBoundedPagesDuringDigestRecompute() =
        runBlocking {
            runRawSnapshotDigestBenchmark(
                snapshotCount = RAW_SNAPSHOT_COUNT,
                databaseName = "learner-mastery-raw-scale.mastery-test.db",
                budgetNanos = RAW_DIGEST_BUDGET_NANOS,
            )
        }

    @Test
    fun tenThousandRawSnapshotsConfirmDigestScalingBeforeTheReleaseGate() =
        runBlocking {
            runRawSnapshotDigestBenchmark(
                snapshotCount = RAW_SNAPSHOT_MICRO_COUNT,
                databaseName = "learner-mastery-raw-micro.mastery-test.db",
                budgetNanos = RAW_DIGEST_MICRO_BUDGET_NANOS,
            )
        }

    private suspend fun runRawSnapshotDigestBenchmark(
        snapshotCount: Int,
        databaseName: String,
        budgetNanos: Long,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databasePath = context.getDatabasePath(databaseName)
        val sourceGeneration = rawSourceGeneration(snapshotCount)
        context.deleteDatabase(databaseName)
        val database =
            LearnerMasteryStoreFactory.openDatabaseForTest(
                context = context,
                databaseName = databaseName,
            )
        try {
            val migration =
                RoomLearnerMasteryLegacyMigrationPort(
                    database = database,
                    learnerId = LOCAL_LEARNER_ID,
                )
            var cursor: LearnerMasteryLegacyMigrationCursor? = null
            var sourceIndex = 0
            var batchSequence = 1L
            while (sourceIndex < snapshotCount) {
                val pageCount =
                    minOf(RAW_SNAPSHOT_PAGE_SIZE, snapshotCount - sourceIndex)
                val snapshots =
                    (sourceIndex until sourceIndex + pageCount).map(::rawSnapshot)
                val page =
                    LearnerMasteryLegacySnapshotPage(
                        learnerId = LOCAL_LEARNER_ID,
                        sourceGeneration = sourceGeneration,
                        batchSequence = batchSequence,
                        sourcePageCanonicalFingerprint =
                            recomputeSourcePageFingerprint(
                                learnerId = LOCAL_LEARNER_ID,
                                afterExclusive = cursor,
                                snapshots = snapshots,
                            ),
                        afterExclusive = cursor,
                        snapshots = snapshots,
                        finalBatch = sourceIndex + pageCount == snapshotCount,
                    )
                assertEquals(
                    LearnerMasteryLegacySnapshotPageDisposition.IMPORTED,
                    migration.applyPage(page).disposition,
                )
                cursor = page.terminalCursor
                sourceIndex += pageCount
                batchSequence += 1L
            }

            Runtime.getRuntime().gc()
            SystemClock.sleep(100)
            val heapBefore = Runtime.getRuntime().usedHeapBytes()
            val startedAt = SystemClock.elapsedRealtimeNanos()
            val (recomputedLedger, diagnostics) =
                database.cutoverDao().recomputeCompletedMigrationLedgerWithDiagnostics(
                    learnerId = LOCAL_LEARNER_ID,
                    sourceGeneration = sourceGeneration,
                )
            val ledger = checkNotNull(recomputedLedger)
            val elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedAt
            val retainedHeapGrowth =
                (Runtime.getRuntime().usedHeapBytes() - heapBefore).coerceAtLeast(0L)
            Log.i(
                LOG_TAG,
                "rawSnapshotDigest count=$snapshotCount " +
                    "elapsedMs=${elapsedNanos.asMilliseconds()} " +
                    "retainedHeapBytes=$retainedHeapGrowth " +
                    "countReadMs=${diagnostics.countReadNanos.asMilliseconds()} " +
                    "pageReadMs=${diagnostics.pageReadNanos.asMilliseconds()} " +
                    "snapshotReadMs=${diagnostics.snapshotReadNanos.asMilliseconds()} " +
                    "sourceValidationMs=" +
                    "${diagnostics.sourceRecordValidationNanos.asMilliseconds()} " +
                    "pageReceiptMs=" +
                    "${diagnostics.pageReceiptValidationNanos.asMilliseconds()} " +
                    "destinationDigestMs=" +
                    diagnostics.destinationDigestNanos.asMilliseconds(),
            )

            assertEquals(snapshotCount.toLong(), ledger.rawSnapshotCount)
            assertEquals(snapshotCount.toLong(), ledger.migratedObservationCount)
            assertEquals(
                ceil(snapshotCount.toDouble() / RAW_SNAPSHOT_PAGE_SIZE).toInt(),
                ledger.batchReceiptCount,
            )
            assertEquals(2, ledger.destinationLedgerVersion)
            assertEquals(2, ledger.destinationCanonicalLayoutVersion)
            assertTrue(
                "Raw snapshot digest took ${elapsedNanos.asMilliseconds()} ms " +
                    "countReadMs=${diagnostics.countReadNanos.asMilliseconds()} " +
                    "pageReadMs=${diagnostics.pageReadNanos.asMilliseconds()} " +
                    "snapshotReadMs=${diagnostics.snapshotReadNanos.asMilliseconds()} " +
                    "sourceValidationMs=" +
                    "${diagnostics.sourceRecordValidationNanos.asMilliseconds()} " +
                    "pageReceiptMs=${diagnostics.pageReceiptValidationNanos.asMilliseconds()} " +
                    "destinationDigestMs=" +
                    diagnostics.destinationDigestNanos.asMilliseconds(),
                elapsedNanos < budgetNanos,
            )
            assertTrue(
                "Raw snapshot digest retained $retainedHeapGrowth bytes; " +
                    "page-bounded budget is $RAW_DIGEST_HEAP_GROWTH_BUDGET_BYTES",
                retainedHeapGrowth < RAW_DIGEST_HEAP_GROWTH_BUDGET_BYTES,
            )
        } finally {
            database.close()
        }

        SQLiteDatabase.openDatabase(
            databasePath.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sqlite ->
            assertEquals(
                snapshotCount.toLong(),
                sqlite.queryLong(
                    "SELECT COUNT(*) FROM mastery_legacy_observation_snapshot",
                ),
            )
            assertEquals(0L, sqlite.queryLong("SELECT COUNT(*) FROM mastery_source_fact"))
            assertEquals(
                0L,
                sqlite.queryLong("SELECT COUNT(*) FROM mastery_learning_event"),
            )
            assertEquals(
                0L,
                sqlite.queryLong("SELECT COUNT(*) FROM mastery_knowledge_projection"),
            )
        }
        context.deleteDatabase(databaseName)
    }

    @Test
    fun hundredThousandImmutableEventsMeetWarmQueryBudgets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "learner-mastery-scale.mastery-test.db"
        val databasePath = context.getDatabasePath(databaseName)
        context.deleteDatabase(databaseName)

        try {
            val buildStartedAt = SystemClock.elapsedRealtimeNanos()
            val schemaDatabase =
                LearnerMasteryStoreFactory.openDatabaseForTest(
                    context = context,
                    databaseName = databaseName,
                )
            try {
                // Room opens lazily. Create the guarded production schema without publishing an
                // empty, already-complete projection generation before the scale ledger is loaded.
                schemaDatabase.masteryDao().hasProblemBindingAuthorityState("0".repeat(64))
            } finally {
                schemaDatabase.close()
            }

            SQLiteDatabase.openDatabase(
                databasePath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                sqlite.execSQL("PRAGMA foreign_keys = ON")
                assertEquals(1L, sqlite.queryLong("PRAGMA foreign_keys"))
                RealLedgerSeeder(sqlite).use { seeder ->
                    seeder.seed()
                }
                verifyRealLedger(sqlite)
                verifyProjectionRebuildPlan(sqlite)
                sqlite.execSQL(
                    """
                    INSERT INTO mastery_store_metadata(metadata_key, metadata_value)
                    VALUES('projection_rebuild_policy', 'REQUIRED_V2')
                    """.trimIndent(),
                )
                sqlite.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                }
            }
            val buildNanos = SystemClock.elapsedRealtimeNanos() - buildStartedAt
            val rebuildStartedAt = SystemClock.elapsedRealtimeNanos()
            val rebuildDatabase =
                LearnerMasteryStoreFactory.openDatabaseForTest(
                    context = context,
                    databaseName = databaseName,
                )
            var rebuiltEventRowCount = 0L
            var rebuildChunkCount = 0L
            var immutableHistoryQueryCount = 0L
            var peakMaterializedRowCount = 0
            try {
                val foregroundPreparationStartedAt = SystemClock.elapsedRealtimeNanos()
                val foregroundPreparation =
                    rebuildDatabase.masteryDao().prepareProjectionRebuild(
                        nowEpochMillis = NOW_EPOCH_MILLIS,
                    )
                val foregroundPreparationNanos =
                    SystemClock.elapsedRealtimeNanos() - foregroundPreparationStartedAt
                assertEquals(0, foregroundPreparation.processedRowCount)
                assertTrue(!foregroundPreparation.activeGenerationAvailable)
                assertTrue(!foregroundPreparation.completed)
                assertEquals(
                    MasteryProjectionRebuildStage.RESET,
                    foregroundPreparation.stage,
                )
                assertTrue(
                    "100k-event foreground preparation took " +
                        "${foregroundPreparationNanos.asMilliseconds()} ms",
                    foregroundPreparationNanos < FOREGROUND_PREPARATION_BUDGET_NANOS,
                )
                var rebuildResult: MasteryProjectionRebuildChunkResult
                var lastLoggedStage: MasteryProjectionRebuildStage? = null
                do {
                    rebuildResult = rebuildDatabase.masteryDao().rebuildDerivedStateChunk()
                    if (rebuildResult.stage == MasteryProjectionRebuildStage.PROJECTIONS) {
                        rebuiltEventRowCount += rebuildResult.processedRowCount
                    }
                    immutableHistoryQueryCount += rebuildResult.immutableHistoryQueryCount
                    peakMaterializedRowCount =
                        maxOf(
                            peakMaterializedRowCount,
                            rebuildResult.peakMaterializedRowCount,
                        )
                    rebuildChunkCount += 1L
                    if (
                        rebuildResult.stage != lastLoggedStage ||
                        rebuildChunkCount % REBUILD_PROGRESS_LOG_INTERVAL == 0L
                    ) {
                        Log.i(
                            LOG_TAG,
                            "LEARNER_MASTERY_REBUILD_PROGRESS " +
                                "stage=${rebuildResult.stage} chunks=$rebuildChunkCount " +
                                "rebuiltRows=$rebuiltEventRowCount " +
                                "elapsedMs=${(SystemClock.elapsedRealtimeNanos() - rebuildStartedAt).asMilliseconds()}",
                        )
                        lastLoggedStage = rebuildResult.stage
                    }
                } while (!rebuildResult.completed)
            } finally {
                rebuildDatabase.close()
            }
            val rebuildNanos = SystemClock.elapsedRealtimeNanos() - rebuildStartedAt
            assertEquals(EVENT_COUNT.toLong(), rebuiltEventRowCount)
            val chunksPerHundredThousandRowStage =
                EVENT_COUNT.toLong() /
                    LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE.toLong() +
                    1L
            val projectionCount = SUBJECTS.size.toLong() * NODES_PER_SUBJECT.toLong()
            val evidenceDimensionChunkCount =
                projectionCount / PROJECTION_EVIDENCE_DIMENSION_PAGE_SIZE.toLong() + 1L
            val expectedRebuildChunkCount =
                3L +
                    4L * chunksPerHundredThousandRowStage +
                    evidenceDimensionChunkCount
            assertEquals(
                "Every paged rebuild stage must advance without duplicate or stalled chunks",
                expectedRebuildChunkCount,
                rebuildChunkCount,
            )
            assertEquals(
                "History replay and evidence dimensions must use bounded queries only",
                ((EVENT_COUNT + LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE - 1) /
                    LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE).toLong() +
                    projectionCount,
                immutableHistoryQueryCount,
            )
            assertTrue(
                "Projection replay materialized $peakMaterializedRowCount reducer rows",
                peakMaterializedRowCount <=
                    LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE * 3,
            )
            assertTrue(
                "100k-event rebuild took ${rebuildNanos.asMilliseconds()} ms",
                rebuildNanos < REBUILD_BUDGET_NANOS,
            )
            val storageDiagnostics =
                SQLiteDatabase.openDatabase(
                databasePath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                sqlite.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                }
                assertEquals(
                    EVENT_COUNT.toLong(),
                    sqlite.queryLong("SELECT COUNT(*) FROM mastery_presentation_node_budget"),
                )
                assertEquals(
                    EVENT_COUNT.toLong(),
                    sqlite.queryLong("SELECT COUNT(*) FROM mastery_problem_family_node_budget"),
                )
                verifyRealLedger(sqlite)
                MasteryStorageDiagnostics.capture(sqlite)
            }

            val exactFingerprints =
                SUBJECTS.associateWith { subject ->
                    (0 until EXACT_NODE_COUNT).map { nodeIndex ->
                        stableNodeFingerprint(subject, nodeIndex)
                    }
                }

            val store =
                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { NOW_EPOCH_MILLIS },
                )
            RoomLearnerMasteryAuthority(
                store = store,
                learnerId = LEARNER_ID,
                nowEpochMillis = { NOW_EPOCH_MILLIS },
                runtimeBindingId = "scale-performance-runtime",
            ).use { authority ->
                val digestStats =
                    measureWarmQueries(
                        operation = { sampleIndex ->
                            authority.queryDigest(
                                subject = SUBJECTS[sampleIndex % SUBJECTS.size],
                                focusLimit = NODES_PER_SUBJECT,
                            )
                        },
                        consume = { digest ->
                            assertEquals(NODES_PER_SUBJECT, digest.focus.size)
                            assertEquals(
                                NODES_PER_SUBJECT,
                                digest.stateCounts.needsReinforcement +
                                    digest.stateCounts.familiarizing +
                                    digest.stateCounts.steady,
                            )
                        },
                    )
                val exactContextStats =
                    measureWarmQueries(
                        operation = { sampleIndex ->
                            val subject = SUBJECTS[sampleIndex % SUBJECTS.size]
                            authority.localContextReader.queryContext(
                                LocalMasteryContextRequest(
                                    subject = subject,
                                    exactStableNodeFingerprints =
                                        checkNotNull(exactFingerprints[subject]),
                                    fallbackLimit = 0,
                                ),
                            )
                        },
                        consume = { masteryContext ->
                            assertEquals(EXACT_NODE_COUNT, masteryContext.items.size)
                            assertTrue(
                                masteryContext.items.all {
                                    it.selection == LocalMasteryContextSelection.EXACT
                                },
                            )
                        },
                    )
                val timelineStats =
                    measureWarmQueries(
                        operation = { sampleIndex ->
                            authority.queryTimeline(
                                subject = SUBJECTS[sampleIndex % SUBJECTS.size],
                                sinceEpochMillis = 0L,
                                dayLimit = TIMELINE_DAY_LIMIT,
                            )
                        },
                        consume = { timeline ->
                            assertEquals(TIMELINE_DAY_LIMIT, timeline.size)
                            assertTrue(timeline.zipWithNext().all { (left, right) ->
                                left.utcEpochDay < right.utcEpochDay
                            })
                        },
                    )

                val databaseBytes = databaseFootprintBytes(databasePath)
                val report =
                    "LEARNER_MASTERY_PERF " +
                        "manufacturer=${Build.MANUFACTURER} " +
                        "model=${Build.MODEL} " +
                        "device=${Build.DEVICE} " +
                        "sdk=${Build.VERSION.SDK_INT} " +
                        "abis=${Build.SUPPORTED_ABIS.joinToString(",")} " +
                        "events=$EVENT_COUNT subjects=${SUBJECTS.size} " +
                        "nodesPerSubject=$NODES_PER_SUBJECT timelineDays=$TOTAL_TIMELINE_DAYS " +
                        "warmups=$WARMUP_QUERY_COUNT samples=$MEASURED_QUERY_COUNT " +
                        "buildMs=${buildNanos.asMilliseconds()} " +
                        "rebuildMs=${rebuildNanos.asMilliseconds()} " +
                        "rebuildChunks=$rebuildChunkCount " +
                        "rebuiltRows=$rebuiltEventRowCount " +
                        "immutableHistoryQueries=$immutableHistoryQueryCount " +
                        "peakReducerRows=$peakMaterializedRowCount " +
                        "databaseBytes=$databaseBytes " +
                        storageDiagnostics.reportFields() + " " +
                        "digestP95Ms=${digestStats.p95Nanos.asMilliseconds()} " +
                        "exactContextP95Ms=${exactContextStats.p95Nanos.asMilliseconds()} " +
                        "timelineP95Ms=${timelineStats.p95Nanos.asMilliseconds()}"
                Log.i(LOG_TAG, report)
                println(report)

                assertTrue(
                    "Digest warm P95 was ${digestStats.p95Nanos.asMilliseconds()} ms; " +
                        "budget is under 30 ms",
                    digestStats.p95Nanos < QUERY_P95_BUDGET_NANOS,
                )
                assertTrue(
                    "Exact-context warm P95 was " +
                        "${exactContextStats.p95Nanos.asMilliseconds()} ms; " +
                        "budget is under 30 ms",
                    exactContextStats.p95Nanos < QUERY_P95_BUDGET_NANOS,
                )
                assertTrue(
                    "Database footprint was $databaseBytes bytes; measured 100k baseline plus " +
                        "headroom is $DATABASE_FOOTPRINT_BUDGET_BYTES bytes",
                    databaseBytes <= DATABASE_FOOTPRINT_BUDGET_BYTES,
                )
                assertTrue(
                    "SQLite used-page bytes exceeded the measured 100k ceiling: " +
                        storageDiagnostics.reportFields(),
                    storageDiagnostics.usedPageBytes <= DATABASE_USED_PAGE_BUDGET_BYTES,
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun hundredThousandEventsInOneSubjectKeepRebuildMemoryAndQueriesBounded() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "learner-mastery-single-subject-skew.mastery-test.db"
        val databasePath = context.getDatabasePath(databaseName)
        context.deleteDatabase(databaseName)
        try {
            val schemaDatabase =
                LearnerMasteryStoreFactory.openDatabaseForTest(
                    context = context,
                    databaseName = databaseName,
                )
            try {
                schemaDatabase.masteryDao().hasProblemBindingAuthorityState("0".repeat(64))
            } finally {
                schemaDatabase.close()
            }
            SQLiteDatabase.openDatabase(
                databasePath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                sqlite.execSQL("PRAGMA foreign_keys = ON")
                RealLedgerSeeder(
                    sqlite = sqlite,
                    subjects = listOf(SubjectKind.MATH),
                ).use { seeder -> seeder.seed() }
                sqlite.execSQL(
                    """
                    INSERT INTO mastery_store_metadata(metadata_key, metadata_value)
                    VALUES('projection_rebuild_policy', 'REQUIRED_V2')
                    """.trimIndent(),
                )
            }

            val database =
                LearnerMasteryStoreFactory.openDatabaseForTest(
                    context = context,
                    databaseName = databaseName,
                )
            var chunkCount = 0L
            var replayedRows = 0L
            var immutableHistoryQueryCount = 0L
            var peakMaterializedRowCount = 0
            try {
                val preparation =
                    database.masteryDao().prepareProjectionRebuild(NOW_EPOCH_MILLIS)
                assertEquals(MasteryProjectionRebuildStage.RESET, preparation.stage)
                var result: MasteryProjectionRebuildChunkResult
                do {
                    result = database.masteryDao().rebuildDerivedStateChunk()
                    if (result.stage == MasteryProjectionRebuildStage.PROJECTIONS) {
                        replayedRows += result.processedRowCount
                    }
                    immutableHistoryQueryCount += result.immutableHistoryQueryCount
                    peakMaterializedRowCount =
                        maxOf(peakMaterializedRowCount, result.peakMaterializedRowCount)
                    chunkCount += 1L
                    assertTrue("Single-subject skew rebuild stalled", chunkCount <= 1_000L)
                } while (!result.completed)
            } finally {
                database.close()
            }

            val historyStageChunks =
                EVENT_COUNT.toLong() /
                    LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE.toLong() + 1L
            val projectionCount = NODES_PER_SUBJECT.toLong()
            val evidenceDimensionChunks =
                projectionCount / PROJECTION_EVIDENCE_DIMENSION_PAGE_SIZE.toLong() + 1L
            assertEquals(EVENT_COUNT.toLong(), replayedRows)
            assertEquals(
                3L + 4L * historyStageChunks + evidenceDimensionChunks,
                chunkCount,
            )
            assertEquals(
                ((EVENT_COUNT + LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE - 1) /
                    LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE).toLong() +
                    projectionCount,
                immutableHistoryQueryCount,
            )
            assertTrue(
                "Single-subject reducer materialized $peakMaterializedRowCount rows",
                peakMaterializedRowCount <=
                    LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE * 3,
            )
            SQLiteDatabase.openDatabase(
                databasePath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    EVENT_COUNT.toLong(),
                    sqlite.queryLong("SELECT COUNT(*) FROM mastery_learning_event"),
                )
                assertEquals(
                    1L,
                    sqlite.queryLong("SELECT COUNT(DISTINCT subject) FROM mastery_learning_event"),
                )
                assertEquals(
                    projectionCount,
                    sqlite.queryLong("SELECT COUNT(*) FROM mastery_knowledge_projection"),
                )
                assertEquals(
                    EVENT_COUNT.toLong(),
                    sqlite.queryLong("SELECT SUM(observation_count) FROM mastery_knowledge_projection"),
                )
                assertEquals(
                    EVENT_COUNT.toLong(),
                    sqlite.queryLong(
                        "SELECT SUM(independent_problem_family_count) " +
                            "FROM mastery_knowledge_projection",
                    ),
                )
                assertEquals(
                    EVENT_COUNT.toLong(),
                    sqlite.queryLong(
                        "SELECT SUM(distinct_presentation_count) " +
                            "FROM mastery_knowledge_projection",
                    ),
                )
                assertFalse(sqlite.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() })
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun verifyRealLedger(sqlite: SQLiteDatabase) {
        assertEquals(
            EVENT_COUNT.toLong(),
            sqlite.queryLong("SELECT COUNT(*) FROM mastery_learning_event"),
        )
        assertEquals(
            EVENT_COUNT.toLong(),
            sqlite.queryLong("SELECT COUNT(*) FROM mastery_learning_event_attribution"),
        )
        assertEquals(
            EVENT_COUNT.toLong(),
            sqlite.queryLong("SELECT COUNT(*) FROM mastery_applied_event"),
        )
        assertEquals(
            (SUBJECTS.size * NODES_PER_SUBJECT).toLong(),
            sqlite.queryLong("SELECT COUNT(*) FROM mastery_knowledge_projection"),
        )
        assertEquals(
            SUBJECTS.size.toLong(),
            sqlite.queryLong("SELECT COUNT(DISTINCT subject) FROM mastery_learning_event"),
        )
        assertEquals(
            NODES_PER_SUBJECT.toLong(),
            sqlite.queryLong(
                """
                SELECT MIN(node_count)
                FROM (
                    SELECT e.subject, COUNT(DISTINCT a.knowledge_node_id) AS node_count
                    FROM mastery_learning_event e
                    INNER JOIN mastery_learning_event_attribution a
                        ON a.event_id = e.event_id
                    GROUP BY e.subject
                )
                """.trimIndent(),
            ),
        )
        assertEquals(
            TOTAL_TIMELINE_DAYS.toLong(),
            sqlite.queryLong(
                """
                SELECT MIN(day_count)
                FROM (
                    SELECT subject,
                           COUNT(DISTINCT occurred_at_epoch_millis / $DAY_MILLIS) AS day_count
                    FROM mastery_learning_event
                    GROUP BY subject
                )
                """.trimIndent(),
            ),
        )

        sqlite.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            assertFalse("Scale fixture contains a broken foreign key", cursor.moveToFirst())
        }
        sqlite.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
            assertFalse("Integrity check returned more than one result", cursor.moveToNext())
        }

        assertEquals(
            REQUIRED_INDEX_NAMES.size.toLong(),
            sqlite.queryLong(
                """
                SELECT COUNT(*)
                FROM sqlite_master
                WHERE type = 'index'
                  AND name IN (${REQUIRED_INDEX_NAMES.joinToString { "?" }})
                """.trimIndent(),
                REQUIRED_INDEX_NAMES.toTypedArray(),
            ),
        )
        assertEquals(
            2L,
            sqlite.queryLong(
                """
                SELECT COUNT(*)
                FROM sqlite_master
                WHERE type = 'trigger'
                  AND name IN (
                      'immutable_mastery_learning_event_update',
                      'immutable_mastery_learning_event_delete'
                  )
                """.trimIndent(),
            ),
        )

        val mutationFailure =
            runCatching {
                sqlite.execSQL(
                    """
                    UPDATE mastery_learning_event
                    SET direction = direction
                    WHERE event_id = 'event:0'
                    """.trimIndent(),
                )
            }.exceptionOrNull()
        assertTrue(
            "The production immutability trigger accepted a learning-event update",
            mutationFailure != null &&
                mutationFailure.message.orEmpty().contains("immutable learner-mastery record"),
        )
    }

    private fun verifyProjectionRebuildPlan(sqlite: SQLiteDatabase) {
        val eventPlans =
            listOf(
                """
                SELECT * FROM mastery_learning_event e
                    INDEXED BY index_mastery_learning_event_directional_budget_replay
                WHERE e.learner_id = 'learner'
                  AND e.occurred_at_epoch_millis = 1
                  AND e.event_id = 'event'
                  AND e.direction IN ('POSITIVE', 'NEGATIVE')
                  AND NOT EXISTS (
                    SELECT 1 FROM mastery_learning_evidence_supersession s
                    WHERE s.original_event_id = e.event_id
                  )
                LIMIT 1
                """.trimIndent(),
                """
                SELECT * FROM mastery_learning_event e
                    INDEXED BY index_mastery_learning_event_directional_budget_replay
                WHERE e.learner_id = 'learner'
                  AND e.occurred_at_epoch_millis = 1
                  AND e.event_id > 'event'
                  AND e.direction IN ('POSITIVE', 'NEGATIVE')
                  AND NOT EXISTS (
                    SELECT 1 FROM mastery_learning_evidence_supersession s
                    WHERE s.original_event_id = e.event_id
                  )
                ORDER BY e.event_id LIMIT 64
                """.trimIndent(),
                """
                SELECT * FROM mastery_learning_event e
                    INDEXED BY index_mastery_learning_event_directional_budget_replay
                WHERE e.learner_id = 'learner'
                  AND e.occurred_at_epoch_millis > 1
                  AND e.direction IN ('POSITIVE', 'NEGATIVE')
                  AND NOT EXISTS (
                    SELECT 1 FROM mastery_learning_evidence_supersession s
                    WHERE s.original_event_id = e.event_id
                  )
                ORDER BY e.occurred_at_epoch_millis, e.event_id LIMIT 64
                """.trimIndent(),
                """
                SELECT * FROM mastery_learning_event e
                    INDEXED BY index_mastery_learning_event_directional_budget_replay
                WHERE e.learner_id > 'learner'
                  AND e.direction IN ('POSITIVE', 'NEGATIVE')
                  AND NOT EXISTS (
                    SELECT 1 FROM mastery_learning_evidence_supersession s
                    WHERE s.original_event_id = e.event_id
                  )
                ORDER BY e.learner_id, e.occurred_at_epoch_millis, e.event_id LIMIT 64
                """.trimIndent(),
            )
        eventPlans.forEach { sql ->
            sqlite.assertSeekOnlyPlan(
                sql = sql,
                requiredIndex = "index_mastery_learning_event_directional_budget_replay",
            )
        }
        val projectionEvidenceDimensionPlans =
            listOf(
                """
                SELECT * FROM mastery_projection_shadow
                WHERE generation_id = 1
                  AND learner_id = 'learner'
                  AND subject = 'MATHEMATICS'
                  AND knowledge_node_id = 'node'
                  AND taxonomy_version > 'taxonomy'
                ORDER BY taxonomy_version
                LIMIT 64
                """.trimIndent(),
                """
                SELECT * FROM mastery_projection_shadow
                WHERE generation_id = 1
                  AND learner_id > 'learner'
                ORDER BY learner_id, subject, knowledge_node_id, taxonomy_version
                LIMIT 64
                """.trimIndent(),
            )
        projectionEvidenceDimensionPlans.forEach { sql ->
            sqlite.assertSeekOnlyPlan(
                sql = sql,
                requiredIndex = "sqlite_autoindex_mastery_projection_shadow",
            )
        }

        val presentationTable = "mastery_presentation_node_budget_shadow"
        sqlite.assertDirectionalBudgetKeysetPlans(
            table = presentationTable,
            secondaryColumn = "presentation_id",
        )
        sqlite.assertDirectionalBudgetKeysetPlans(
            table = "mastery_problem_family_node_budget_shadow",
            secondaryColumn = "problem_family_fingerprint",
        )
        sqlite.assertSeekOnlyPlan(
            sql =
                "SELECT * FROM mastery_learning_event_attribution " +
                    "WHERE event_id IN ('event-a', 'event-b')",
            requiredIndex =
                "index_mastery_learning_event_attribution_" +
                    "event_id_knowledge_node_id_taxonomy_version_knowledge_pack_version",
        )
    }

    private fun SQLiteDatabase.assertDirectionalBudgetKeysetPlans(
        table: String,
        secondaryColumn: String,
    ) {
        val prefix =
            "SELECT * FROM $table WHERE generation_id = 1"
        val plans =
            listOf(
                "$prefix ORDER BY learner_id, $secondaryColumn, subject, " +
                    "knowledge_node_id, taxonomy_version, direction LIMIT 64",
                "$prefix AND learner_id = 'l' AND $secondaryColumn = 's' " +
                    "AND subject = 'MATH' AND knowledge_node_id = 'n' " +
                    "AND taxonomy_version = 't' AND direction > 'NEGATIVE' " +
                    "ORDER BY direction LIMIT 64",
                "$prefix AND learner_id = 'l' AND $secondaryColumn = 's' " +
                    "AND subject = 'MATH' AND knowledge_node_id = 'n' " +
                    "AND taxonomy_version > 't' ORDER BY taxonomy_version, direction LIMIT 64",
                "$prefix AND learner_id = 'l' AND $secondaryColumn = 's' " +
                    "AND subject = 'MATH' AND knowledge_node_id > 'n' " +
                    "ORDER BY knowledge_node_id, taxonomy_version, direction LIMIT 64",
                "$prefix AND learner_id = 'l' AND $secondaryColumn = 's' " +
                    "AND subject > 'MATH' ORDER BY subject, knowledge_node_id, " +
                    "taxonomy_version, direction LIMIT 64",
                "$prefix AND learner_id = 'l' AND $secondaryColumn > 's' " +
                    "ORDER BY $secondaryColumn, subject, knowledge_node_id, " +
                    "taxonomy_version, direction LIMIT 64",
                "$prefix AND learner_id > 'l' ORDER BY learner_id, $secondaryColumn, " +
                    "subject, knowledge_node_id, taxonomy_version, direction LIMIT 64",
            )
        plans.forEach { sql ->
            assertSeekOnlyPlan(
                sql = sql,
                requiredIndex = "sqlite_autoindex_$table",
            )
        }
    }

    private fun SQLiteDatabase.assertSeekOnlyPlan(
        sql: String,
        requiredIndex: String,
    ) {
        val plan =
            rawQuery("EXPLAIN QUERY PLAN $sql", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(3))
                }
            }
        assertTrue(
            "Keyset query did not seek through $requiredIndex: $plan",
            plan.any { it.contains("SEARCH") && it.contains(requiredIndex) },
        )
        assertFalse(
            "Keyset query regressed to a table/index scan: $plan",
            plan.any { it.startsWith("SCAN ") },
        )
        assertFalse(
            "Keyset query performs a temporary sort: $plan",
            plan.any { it.contains("USE TEMP B-TREE") },
        )
    }

    private suspend fun <T> measureWarmQueries(
        operation: suspend (sampleIndex: Int) -> T,
        consume: (T) -> Unit,
    ): QueryStats {
        repeat(WARMUP_QUERY_COUNT) { warmupIndex ->
            consume(operation(warmupIndex))
        }
        val elapsedNanos = LongArray(MEASURED_QUERY_COUNT)
        repeat(MEASURED_QUERY_COUNT) { measuredIndex ->
            val sampleIndex = WARMUP_QUERY_COUNT + measuredIndex
            val startedAt = SystemClock.elapsedRealtimeNanos()
            val value = operation(sampleIndex)
            elapsedNanos[measuredIndex] = SystemClock.elapsedRealtimeNanos() - startedAt
            // Validate and consume outside the timed region.
            consume(value)
        }
        elapsedNanos.sort()
        val p95Index = ceil(elapsedNanos.size * 0.95).toInt() - 1
        return QueryStats(p95Nanos = elapsedNanos[p95Index])
    }

    private data class QueryStats(
        val p95Nanos: Long,
    )

    private data class MasteryStorageDiagnostics(
        val pageCount: Long,
        val freelistCount: Long,
        val pageSize: Long,
        val sourceFactRows: Long,
        val sourceProofRows: Long,
        val observationCandidateRows: Long,
        val candidateAttributionRows: Long,
        val admissionReceiptRows: Long,
        val learningEventRows: Long,
        val learningEventAttributionRows: Long,
        val appliedEventRows: Long,
        val activeProjectionRows: Long,
        val activeSubjectDigestRows: Long,
        val activePresentationBudgetRows: Long,
        val activeProblemFamilyBudgetRows: Long,
        val shadowProjectionRows: Long,
        val shadowSubjectDigestRows: Long,
        val shadowPresentationBudgetRows: Long,
        val shadowProblemFamilyBudgetRows: Long,
        val largestDatabaseObjects: String,
    ) {
        val usedPageBytes: Long
            get() = Math.multiplyExact(pageCount - freelistCount, pageSize)

        fun reportFields(): String =
            "pageCount=$pageCount freelistCount=$freelistCount pageSize=$pageSize " +
                "allocatedPageBytes=${Math.multiplyExact(pageCount, pageSize)} " +
                "usedPageBytes=$usedPageBytes " +
                "sourceFactRows=$sourceFactRows sourceProofRows=$sourceProofRows " +
                "observationCandidateRows=$observationCandidateRows " +
                "candidateAttributionRows=$candidateAttributionRows " +
                "admissionReceiptRows=$admissionReceiptRows " +
                "learningEventRows=$learningEventRows " +
                "learningEventAttributionRows=$learningEventAttributionRows " +
                "appliedEventRows=$appliedEventRows " +
                "activeProjectionRows=$activeProjectionRows " +
                "activeSubjectDigestRows=$activeSubjectDigestRows " +
                "activePresentationBudgetRows=$activePresentationBudgetRows " +
                "activeProblemFamilyBudgetRows=$activeProblemFamilyBudgetRows " +
                "shadowProjectionRows=$shadowProjectionRows " +
                "shadowSubjectDigestRows=$shadowSubjectDigestRows " +
                "shadowPresentationBudgetRows=$shadowPresentationBudgetRows " +
                "shadowProblemFamilyBudgetRows=$shadowProblemFamilyBudgetRows " +
                "largestDatabaseObjects=$largestDatabaseObjects"

        companion object {
            fun capture(sqlite: SQLiteDatabase): MasteryStorageDiagnostics =
                MasteryStorageDiagnostics(
                    pageCount = sqlite.queryLong("PRAGMA page_count"),
                    freelistCount = sqlite.queryLong("PRAGMA freelist_count"),
                    pageSize = sqlite.queryLong("PRAGMA page_size"),
                    sourceFactRows =
                        sqlite.queryLong("SELECT COUNT(*) FROM mastery_source_fact"),
                    sourceProofRows =
                        sqlite.queryLong("SELECT COUNT(*) FROM mastery_source_proof"),
                    observationCandidateRows =
                        sqlite.queryLong("SELECT COUNT(*) FROM mastery_observation_candidate"),
                    candidateAttributionRows =
                        sqlite.queryLong("SELECT COUNT(*) FROM mastery_candidate_attribution"),
                    admissionReceiptRows =
                        sqlite.queryLong("SELECT COUNT(*) FROM mastery_admission_receipt"),
                    learningEventRows =
                        sqlite.queryLong("SELECT COUNT(*) FROM mastery_learning_event"),
                    learningEventAttributionRows =
                        sqlite.queryLong(
                            "SELECT COUNT(*) FROM mastery_learning_event_attribution",
                        ),
                    appliedEventRows =
                        sqlite.queryLong("SELECT COUNT(*) FROM mastery_applied_event"),
                    activeProjectionRows =
                        sqlite.queryLong("SELECT COUNT(*) FROM mastery_knowledge_projection"),
                    activeSubjectDigestRows =
                        sqlite.queryLong("SELECT COUNT(*) FROM mastery_subject_digest"),
                    activePresentationBudgetRows =
                        sqlite.queryLong("SELECT COUNT(*) FROM mastery_presentation_node_budget"),
                    activeProblemFamilyBudgetRows =
                        sqlite.queryLong("SELECT COUNT(*) FROM mastery_problem_family_node_budget"),
                    shadowProjectionRows =
                        sqlite.queryLong("SELECT COUNT(*) FROM mastery_projection_shadow"),
                    shadowSubjectDigestRows =
                        sqlite.queryLong("SELECT COUNT(*) FROM mastery_subject_digest_shadow"),
                    shadowPresentationBudgetRows =
                        sqlite.queryLong(
                            "SELECT COUNT(*) FROM mastery_presentation_node_budget_shadow",
                        ),
                    shadowProblemFamilyBudgetRows =
                        sqlite.queryLong(
                            "SELECT COUNT(*) FROM mastery_problem_family_node_budget_shadow",
                        ),
                    largestDatabaseObjects = sqlite.largestDatabaseObjects(),
                )

            private fun SQLiteDatabase.largestDatabaseObjects(): String =
                runCatching {
                    rawQuery(
                        """
                        SELECT name, SUM(pgsize) AS object_bytes
                        FROM dbstat
                        GROUP BY name
                        ORDER BY object_bytes DESC, name
                        LIMIT 20
                        """.trimIndent(),
                        null,
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add("${cursor.getString(0)}:${cursor.getLong(1)}")
                            }
                        }.joinToString(separator = ",")
                    }
                }.getOrElse { failure ->
                    "unavailable-${failure::class.java.simpleName}"
                }
        }
    }

    private class RealLedgerSeeder(
        private val sqlite: SQLiteDatabase,
        private val subjects: List<SubjectKind> = SUBJECTS,
        private val nodesPerSubject: Int = NODES_PER_SUBJECT,
    ) : AutoCloseable {
        init {
            require(subjects.isNotEmpty())
            require(nodesPerSubject > 0)
        }

        private val insertSourceFact =
            sqlite.compileStatement(
                """
                INSERT INTO mastery_source_fact (
                    source_fact_id, learner_id, subject, source_kind, source_reference_id,
                    presentation_id, outcome, assistance, retry_state, authority,
                    source_payload_fingerprint, occurred_at_epoch_millis,
                    attested_at_epoch_millis, received_at_epoch_millis, source_policy_version,
                    idempotency_key, canonical_fingerprint, problem_family_fingerprint,
                    presentation_fingerprint, response_form, independently_answered,
                    verification_kind, evidence_context_kind, ephemeral_problem_fingerprint,
                    submission_evidence_fingerprint, attribution_model_version
                ) VALUES (${questionMarks(26)})
                """.trimIndent(),
            )
        private val insertSourceProof =
            sqlite.compileStatement(
                """
                INSERT INTO mastery_source_proof (
                    source_fact_id, source_fact_canonical_fingerprint, source_policy_version,
                    policy_supported, proof_fingerprint, created_at_epoch_millis
                ) VALUES (${questionMarks(6)})
                """.trimIndent(),
            )
        private val insertCandidate =
            sqlite.compileStatement(
                """
                INSERT INTO mastery_observation_candidate (
                    candidate_id, learner_id, subject, source_fact_id, confidence, model_version,
                    requested_policy_version, proposed_at_epoch_millis,
                    received_at_epoch_millis, idempotency_key, canonical_fingerprint,
                    candidate_origin
                ) VALUES (${questionMarks(12)})
                """.trimIndent(),
            )
        private val insertCandidateAttribution =
            sqlite.compileStatement(
                """
                INSERT INTO mastery_candidate_attribution (
                    candidate_id, ordinal, subject, knowledge_node_id, taxonomy_version,
                    knowledge_pack_version, knowledge_node_ref_fingerprint, role, certainty,
                    proposal_fingerprint
                ) VALUES (${questionMarks(10)})
                """.trimIndent(),
            )
        private val insertAdmissionReceipt =
            sqlite.compileStatement(
                """
                INSERT INTO mastery_admission_receipt (
                    candidate_id, candidate_canonical_fingerprint, learner_id, disposition,
                    source_proof_fingerprint, policy_version, admission_policy_version,
                    calibration_version, event_id, receipt_fingerprint,
                    decided_at_epoch_millis
                ) VALUES (${questionMarks(11)})
                """.trimIndent(),
            )
        private val insertLearningEvent =
            sqlite.compileStatement(
                """
                INSERT INTO mastery_learning_event (
                    event_id, candidate_id, source_fact_id, source_proof_fingerprint, learner_id,
                    subject, direction, event_sequence, occurred_at_epoch_millis,
                    admitted_at_epoch_millis, projection_policy_version,
                    admission_policy_version, calibration_version, canonical_fingerprint,
                    problem_family_fingerprint, presentation_fingerprint,
                    evidence_quality_micros, independently_answered,
                    calibration_snapshot_fingerprint, calibration_profile_id
                ) VALUES (${questionMarks(20)})
                """.trimIndent(),
            )
        private val insertLearningEventAttribution =
            sqlite.compileStatement(
                """
                INSERT INTO mastery_learning_event_attribution (
                    event_id, ordinal, subject, knowledge_node_id, taxonomy_version,
                    knowledge_pack_version, knowledge_node_ref_fingerprint,
                    evidence_mass_micros
                ) VALUES (${questionMarks(8)})
                """.trimIndent(),
            )
        private val insertAppliedEvent =
            sqlite.compileStatement(
                """
                INSERT INTO mastery_applied_event (
                    event_id, event_canonical_fingerprint, learner_id, event_sequence,
                    projection_policy_version, application_fingerprint,
                    applied_at_epoch_millis
                ) VALUES (${questionMarks(7)})
                """.trimIndent(),
            )
        private val insertProjection =
            sqlite.compileStatement(
                """
                INSERT INTO mastery_knowledge_projection (
                    learner_id, subject, knowledge_node_id, taxonomy_version,
                    latest_evidence_knowledge_pack_version, stable_node_identity_fingerprint,
                    positive_evidence_micros, negative_evidence_micros, mastery_score_micros,
                    mastery_state, trend, observation_count, memory_stability_millis,
                    recall_due_at_epoch_millis, last_positive_at_epoch_millis,
                    last_negative_at_epoch_millis, last_evidence_at_epoch_millis,
                    last_event_sequence, last_ordered_event_id, projection_policy_version,
                    evidence_quality_micros, independent_problem_family_count,
                    distinct_presentation_count, calibration_snapshot_fingerprint,
                    calibration_profile_id, calibration_version
                ) VALUES (${questionMarks(26)})
                """.trimIndent(),
            )
        private val insertDigest =
            sqlite.compileStatement(
                """
                INSERT INTO mastery_subject_digest (
                    learner_id, subject, needs_reinforcement_count, familiarizing_count,
                    steady_count, last_event_sequence, updated_at_epoch_millis,
                    projection_policy_version
                ) VALUES (${questionMarks(8)})
                """.trimIndent(),
            )
        private val insertLedgerSequence =
            sqlite.compileStatement(
                """
                INSERT INTO mastery_ledger_sequence (
                    learner_id, last_allocated_sequence
                ) VALUES (${questionMarks(2)})
                """.trimIndent(),
            )
        private val statements =
            listOf(
                insertSourceFact,
                insertSourceProof,
                insertCandidate,
                insertCandidateAttribution,
                insertAdmissionReceipt,
                insertLearningEvent,
                insertLearningEventAttribution,
                insertAppliedEvent,
                insertProjection,
                insertDigest,
                insertLedgerSequence,
            )
        private val aggregates =
            Array(subjects.size) {
                Array(nodesPerSubject) {
                    NodeAggregate()
                }
            }

        fun seed() {
            sqlite.beginTransaction()
            try {
                repeat(EVENT_COUNT) { eventIndex ->
                    insertEvent(eventIndex)
                }
                insertDerivedState()
                insertLedgerSequence.bindAndExecute {
                    bindString(1, LEARNER_ID)
                    bindLong(2, EVENT_COUNT.toLong())
                }
                sqlite.setTransactionSuccessful()
            } finally {
                sqlite.endTransaction()
            }
        }

        private fun insertEvent(eventIndex: Int) {
            val subjectIndex = eventIndex % subjects.size
            val subject = subjects[subjectIndex]
            val subjectEventIndex = eventIndex / subjects.size
            val nodeIndex = subjectEventIndex % nodesPerSubject
            val timelineDay = subjectEventIndex % TOTAL_TIMELINE_DAYS
            val occurredAt = NOW_EPOCH_MILLIS - timelineDay * DAY_MILLIS
            val eventSequence = eventIndex.toLong() + 1L
            val nodeId = knowledgeNodeId(subject, nodeIndex)
            val nodeRefFingerprint =
                KnowledgeNodeRef(
                    subject = subject,
                    knowledgeNodeId = nodeId,
                    taxonomyVersion = TAXONOMY_VERSION,
                    knowledgePackVersion = KNOWLEDGE_PACK_VERSION,
                ).canonicalFingerprint
            val positiveThreshold =
                when (nodeIndex % 3) {
                    0 -> 4
                    1 -> 7
                    else -> 9
                }
            val isPositive =
                (subjectEventIndex / nodesPerSubject) % 10 < positiveThreshold
            val direction = if (isPositive) "POSITIVE" else "NEGATIVE"
            val sourceFactId = "fact:$eventIndex"
            val candidateId = "candidate:$eventIndex"
            val eventId = "event:$eventIndex"
            val sourceFactFingerprint = fingerprint(FINGERPRINT_FACT, eventIndex)
            val sourceProofFingerprint = fingerprint(FINGERPRINT_PROOF, eventIndex)
            val candidateFingerprint = fingerprint(FINGERPRINT_CANDIDATE, eventIndex)
            val eventFingerprint = fingerprint(FINGERPRINT_EVENT, eventIndex)
            val problemFamilyFingerprint = fingerprint(FINGERPRINT_PROBLEM_FAMILY, eventIndex)
            val presentationFingerprint = fingerprint(FINGERPRINT_PRESENTATION, eventIndex)
            val calibration = LocalMasteryCalibrationRegistry.current(subject.name)

            insertSourceFact.bindAndExecute {
                bindString(1, sourceFactId)
                bindString(2, LEARNER_ID)
                bindString(3, subject.name)
                bindString(4, "TUTOR_CHOICE")
                bindString(5, "scale-observation:$eventIndex")
                bindString(6, "scale-presentation:$eventIndex")
                bindString(7, if (isPositive) "CORRECT" else "INCORRECT")
                bindString(8, "INDEPENDENT")
                bindString(9, "FIRST_ATTEMPT")
                bindString(10, "LOCAL_VERIFIED")
                bindString(11, fingerprint(FINGERPRINT_SOURCE_PAYLOAD, eventIndex))
                bindLong(12, occurredAt)
                bindLong(13, occurredAt)
                bindLong(14, occurredAt)
                bindString(15, LEARNER_MASTERY_SOURCE_POLICY_VERSION)
                bindString(16, "scale-fact-idempotency:$eventIndex")
                bindString(17, sourceFactFingerprint)
                bindString(18, problemFamilyFingerprint)
                bindString(19, presentationFingerprint)
                bindString(20, "MULTIPLE_CHOICE")
                bindLong(21, 1L)
                bindString(22, "DEVICE_OBSERVED")
                bindString(23, "EPHEMERAL_TUTOR_PROBLEM")
                bindString(24, fingerprint(FINGERPRINT_EPHEMERAL_PROBLEM, eventIndex))
                bindString(25, fingerprint(FINGERPRINT_SUBMISSION, eventIndex))
                bindString(26, FIXTURE_MODEL_VERSION)
            }
            insertSourceProof.bindAndExecute {
                bindString(1, sourceFactId)
                bindString(2, sourceFactFingerprint)
                bindString(3, LEARNER_MASTERY_SOURCE_POLICY_VERSION)
                bindLong(4, 1L)
                bindString(5, sourceProofFingerprint)
                bindLong(6, occurredAt)
            }
            insertCandidate.bindAndExecute {
                bindString(1, candidateId)
                bindString(2, LEARNER_ID)
                bindString(3, subject.name)
                bindString(4, sourceFactId)
                bindString(5, "HIGH")
                bindString(6, FIXTURE_MODEL_VERSION)
                bindString(7, LEARNER_MASTERY_PROJECTION_POLICY_VERSION)
                bindLong(8, occurredAt)
                bindLong(9, occurredAt)
                bindString(10, "scale-candidate-idempotency:$eventIndex")
                bindString(11, candidateFingerprint)
                bindString(12, "TRUSTED_LOCAL")
            }
            insertCandidateAttribution.bindAndExecute {
                bindString(1, candidateId)
                bindLong(2, 0L)
                bindString(3, subject.name)
                bindString(4, nodeId)
                bindString(5, TAXONOMY_VERSION)
                bindString(6, KNOWLEDGE_PACK_VERSION)
                bindString(7, nodeRefFingerprint)
                bindString(8, "PRIMARY")
                bindString(9, "DIRECT")
                bindString(10, fingerprint(FINGERPRINT_PROPOSAL, eventIndex))
            }
            insertLearningEvent.bindAndExecute {
                bindString(1, eventId)
                bindString(2, candidateId)
                bindString(3, sourceFactId)
                bindString(4, sourceProofFingerprint)
                bindString(5, LEARNER_ID)
                bindString(6, subject.name)
                bindString(7, direction)
                bindLong(8, eventSequence)
                bindLong(9, occurredAt)
                bindLong(10, occurredAt)
                bindString(11, LEARNER_MASTERY_PROJECTION_POLICY_VERSION)
                bindString(12, LEARNER_MASTERY_ADMISSION_POLICY_VERSION)
                bindString(13, LEARNER_MASTERY_CALIBRATION_VERSION)
                bindString(14, eventFingerprint)
                bindString(15, problemFamilyFingerprint)
                bindString(16, presentationFingerprint)
                bindLong(17, EVIDENCE_QUALITY_MICROS)
                bindLong(18, 1L)
                bindString(19, calibration.snapshotFingerprint)
                bindString(20, calibration.profileId)
            }
            insertLearningEventAttribution.bindAndExecute {
                bindString(1, eventId)
                bindLong(2, 0L)
                bindString(3, subject.name)
                bindString(4, nodeId)
                bindString(5, TAXONOMY_VERSION)
                bindString(6, KNOWLEDGE_PACK_VERSION)
                bindString(7, nodeRefFingerprint)
                bindLong(8, EVIDENCE_MASS_MICROS)
            }
            insertAdmissionReceipt.bindAndExecute {
                bindString(1, candidateId)
                bindString(2, candidateFingerprint)
                bindString(3, LEARNER_ID)
                bindString(4, "ADMITTED")
                bindString(5, sourceProofFingerprint)
                bindString(6, LEARNER_MASTERY_PROJECTION_POLICY_VERSION)
                bindString(7, LEARNER_MASTERY_ADMISSION_POLICY_VERSION)
                bindString(8, LEARNER_MASTERY_CALIBRATION_VERSION)
                bindString(9, eventId)
                bindString(10, fingerprint(FINGERPRINT_RECEIPT, eventIndex))
                bindLong(11, occurredAt)
            }
            insertAppliedEvent.bindAndExecute {
                bindString(1, eventId)
                bindString(2, eventFingerprint)
                bindString(3, LEARNER_ID)
                bindLong(4, eventSequence)
                bindString(5, LEARNER_MASTERY_PROJECTION_POLICY_VERSION)
                bindString(6, fingerprint(FINGERPRINT_APPLICATION, eventIndex))
                bindLong(7, occurredAt)
            }

            aggregates[subjectIndex][nodeIndex].record(
                eventId = eventId,
                eventSequence = eventSequence,
                occurredAtEpochMillis = occurredAt,
                isPositive = isPositive,
            )
        }

        private fun insertDerivedState() {
            subjects.forEachIndexed { subjectIndex, subject ->
                var needsReinforcement = 0
                var familiarizing = 0
                var steady = 0
                var subjectLastEventSequence = 0L

                aggregates[subjectIndex].forEachIndexed { nodeIndex, aggregate ->
                    val calibration =
                        LocalMasteryCalibrationRegistry.current(subject.name)
                    val state =
                        when (nodeIndex % 3) {
                            0 -> {
                                needsReinforcement += 1
                                "NEEDS_REINFORCEMENT"
                            }
                            1 -> {
                                familiarizing += 1
                                "FAMILIARIZING"
                            }
                            else -> {
                                steady += 1
                                "STEADY"
                            }
                        }
                    subjectLastEventSequence =
                        maxOf(subjectLastEventSequence, aggregate.lastEventSequence)
                    val totalMass = aggregate.positiveMassMicros + aggregate.negativeMassMicros
                    val score =
                        if (totalMass == 0L) {
                            0L
                        } else {
                            aggregate.positiveMassMicros * 1_000_000L / totalMass
                        }
                    insertProjection.bindAndExecute {
                        bindString(1, LEARNER_ID)
                        bindString(2, subject.name)
                        bindString(3, knowledgeNodeId(subject, nodeIndex))
                        bindString(4, TAXONOMY_VERSION)
                        bindString(5, KNOWLEDGE_PACK_VERSION)
                        bindString(6, stableNodeFingerprint(subject, nodeIndex))
                        bindLong(7, aggregate.positiveMassMicros)
                        bindLong(8, aggregate.negativeMassMicros)
                        bindLong(9, score)
                        bindString(10, state)
                        bindString(
                            11,
                            when (nodeIndex % 3) {
                                0 -> "WAVERING"
                                1 -> "STABLE"
                                else -> "IMPROVING"
                            },
                        )
                        bindLong(12, aggregate.observationCount)
                        bindLong(13, MEMORY_STABILITY_MILLIS)
                        bindLong(14, NOW_EPOCH_MILLIS + DAY_MILLIS)
                        bindLong(15, aggregate.lastPositiveAtEpochMillis)
                        bindLong(16, aggregate.lastNegativeAtEpochMillis)
                        bindLong(17, aggregate.lastEvidenceAtEpochMillis)
                        bindLong(18, aggregate.lastEventSequence)
                        bindString(19, aggregate.lastEventId)
                        bindString(20, LEARNER_MASTERY_PROJECTION_POLICY_VERSION)
                        bindLong(21, EVIDENCE_QUALITY_MICROS)
                        bindLong(22, aggregate.observationCount)
                        bindLong(23, aggregate.observationCount)
                        bindString(24, calibration.snapshotFingerprint)
                        bindString(25, calibration.profileId)
                        bindString(26, calibration.calibrationVersion)
                    }
                }

                insertDigest.bindAndExecute {
                    bindString(1, LEARNER_ID)
                    bindString(2, subject.name)
                    bindLong(3, needsReinforcement.toLong())
                    bindLong(4, familiarizing.toLong())
                    bindLong(5, steady.toLong())
                    bindLong(6, subjectLastEventSequence)
                    bindLong(7, NOW_EPOCH_MILLIS)
                    bindString(8, LEARNER_MASTERY_PROJECTION_POLICY_VERSION)
                }
            }
        }

        override fun close() {
            statements.forEach(SQLiteStatement::close)
        }
    }

    private class NodeAggregate {
        var positiveMassMicros: Long = 0L
            private set
        var negativeMassMicros: Long = 0L
            private set
        var observationCount: Long = 0L
            private set
        var lastPositiveAtEpochMillis: Long = 0L
            private set
        var lastNegativeAtEpochMillis: Long = 0L
            private set
        var lastEvidenceAtEpochMillis: Long = 0L
            private set
        var lastEventSequence: Long = 0L
            private set
        var lastEventId: String = ""
            private set

        fun record(
            eventId: String,
            eventSequence: Long,
            occurredAtEpochMillis: Long,
            isPositive: Boolean,
        ) {
            observationCount += 1L
            if (isPositive) {
                positiveMassMicros += EVIDENCE_MASS_MICROS
                lastPositiveAtEpochMillis =
                    maxOf(lastPositiveAtEpochMillis, occurredAtEpochMillis)
            } else {
                negativeMassMicros += EVIDENCE_MASS_MICROS
                lastNegativeAtEpochMillis =
                    maxOf(lastNegativeAtEpochMillis, occurredAtEpochMillis)
            }
            lastEvidenceAtEpochMillis =
                maxOf(lastEvidenceAtEpochMillis, occurredAtEpochMillis)
            if (eventSequence > lastEventSequence) {
                lastEventSequence = eventSequence
                lastEventId = eventId
            }
        }
    }

    private companion object {
        const val LOG_TAG = "LearnerMasteryPerf"
        const val LEARNER_ID = "scale-performance-learner"
        const val EVENT_COUNT = 100_000
        const val RAW_SNAPSHOT_COUNT = 100_000
        const val RAW_SNAPSHOT_MICRO_COUNT = 10_000
        const val RAW_SNAPSHOT_PAGE_SIZE = 256
        const val NODES_PER_SUBJECT = 64
        const val EXACT_NODE_COUNT = 16
        const val TOTAL_TIMELINE_DAYS = 365
        const val TIMELINE_DAY_LIMIT = 180
        const val WARMUP_QUERY_COUNT = 18
        const val MEASURED_QUERY_COUNT = 90
        const val DAY_MILLIS = 86_400_000L
        const val NOW_EPOCH_MILLIS = 1_900_000_000_000L
        const val MEMORY_STABILITY_MILLIS = 30L * DAY_MILLIS
        const val QUERY_P95_BUDGET_NANOS = 30_000_000L
        const val FOREGROUND_PREPARATION_BUDGET_NANOS = 1_000_000_000L
        const val REBUILD_BUDGET_NANOS = 180_000_000_000L
        // Measured full 100k ledger footprint was 831,038,336 bytes. This ceiling keeps
        // 13% device/runtime headroom and intentionally includes retained WAL capacity.
        const val DATABASE_FOOTPRINT_BUDGET_BYTES = 900L * 1024L * 1024L
        // SQLite does not return freed pages to the filesystem without VACUUM. Check the
        // logical used-page footprint separately so WAL/freelist capacity cannot hide growth.
        const val DATABASE_USED_PAGE_BUDGET_BYTES = 880L * 1024L * 1024L
        const val REBUILD_PROGRESS_LOG_INTERVAL = 32L
        const val RAW_DIGEST_BUDGET_NANOS = 60_000_000_000L
        const val RAW_DIGEST_MICRO_BUDGET_NANOS = 15_000_000_000L
        const val RAW_DIGEST_HEAP_GROWTH_BUDGET_BYTES = 96L * 1024L * 1024L
        const val EVIDENCE_MASS_MICROS = 750_000L
        const val EVIDENCE_QUALITY_MICROS = 900_000L
        const val TAXONOMY_VERSION = SCALE_TAXONOMY_VERSION
        const val KNOWLEDGE_PACK_VERSION = "scale-knowledge-pack-v1"
        const val FIXTURE_MODEL_VERSION = "scale-fixture-model-v1"

        const val FINGERPRINT_SOURCE_PAYLOAD = 0x01
        const val FINGERPRINT_FACT = 0x02
        const val FINGERPRINT_PROBLEM_FAMILY = 0x03
        const val FINGERPRINT_PRESENTATION = 0x04
        const val FINGERPRINT_SUBMISSION = 0x05
        const val FINGERPRINT_PROOF = 0x06
        const val FINGERPRINT_CANDIDATE = 0x07
        const val FINGERPRINT_PROPOSAL = 0x08
        const val FINGERPRINT_RECEIPT = 0x09
        const val FINGERPRINT_EVENT = 0x0a
        const val FINGERPRINT_APPLICATION = 0x0b
        const val FINGERPRINT_EPHEMERAL_PROBLEM = 0x0c

        val SUBJECTS =
            listOf(
                SubjectKind.CHINESE,
                SubjectKind.MATH,
                SubjectKind.ENGLISH,
                SubjectKind.PHYSICS,
                SubjectKind.CHEMISTRY,
                SubjectKind.BIOLOGY,
                SubjectKind.POLITICS,
                SubjectKind.HISTORY,
                SubjectKind.GEOGRAPHY,
            )

        val REQUIRED_INDEX_NAMES =
            listOf(
                "index_mastery_learning_event_learner_id_subject_occurred_at_epoch_millis",
                "index_mastery_learning_event_directional_budget_replay",
                "index_mastery_knowledge_projection_learner_id_subject_" +
                    "mastery_state_last_evidence_at_epoch_millis",
                "index_mastery_knowledge_projection_learner_id_subject_" +
                    "stable_node_identity_fingerprint",
            )
    }
}

private fun rawSourceGeneration(snapshotCount: Int): String =
    CanonicalSha256("learner-mastery-raw-scale-source-v1")
        .field("learnerId", LOCAL_LEARNER_ID)
        .field("snapshotCount", snapshotCount)
        .finish()

private fun rawSnapshot(index: Int): LearnerMasteryLegacyObservationSnapshot {
    val sourceFactId = "raw-scale-source:$index"
    val anchorId = "raw-scale-anchor:$index"
    val responseFingerprint = fingerprint(0x0d, index)
    val sourcePayloadFingerprint = fingerprint(0x0e, index)
    val occurredAtEpochMillis = index.toLong()
    val sourceRecordFingerprint =
        CanonicalSha256("exact-legacy-mastery-fact-record-v1")
            .field("sourceFactId", sourceFactId)
            .field("learnerScopeId", LOCAL_LEARNER_ID)
            .field("source", LearningObservationSource.IMPORTED_MISTAKE.name)
            .field(
                "factKind",
                LearningObservationFactKind.IMPORTED_VISIBLE_ERROR.name,
            )
            .field("anchorId", anchorId)
            .field("subject", SubjectKind.MATH.name)
            .nullableField("conversationGeneration", null)
            .nullableField("conversationId", null)
            .nullableField("turnReceiptId", null)
            .nullableField("evidenceRequestId", null)
            .field("responseFingerprint", responseFingerprint)
            .field("responseSummary", "legacy response $index")
            .field("occurredAtEpochMillis", occurredAtEpochMillis)
            .field("sourceVersion", "legacy-source-v1")
            .field(
                "sourcePayloadCanonicalFingerprint",
                sourcePayloadFingerprint,
            )
            .nullableField("sourceProofCanonicalFingerprint", null)
            .nullableField("sourceReferenceId", null)
            .nullableField("targetKind", null)
            .nullableField("targetDatabase", null)
            .nullableField("targetId", null)
            .nullableField("targetVersion", null)
            .nullableField("targetCanonicalFingerprint", null)
            .nullableField("attestedAtEpochMillis", null)
            .finish()
    return LearnerMasteryLegacyObservationSnapshot(
        sourceFactId = sourceFactId,
        learnerId = LOCAL_LEARNER_ID,
        source = LearningObservationSource.IMPORTED_MISTAKE.name,
        factKind = LearningObservationFactKind.IMPORTED_VISIBLE_ERROR.name,
        anchorId = anchorId,
        subject = SubjectKind.MATH.name,
        conversationGeneration = null,
        conversationId = null,
        turnReceiptId = null,
        evidenceRequestId = null,
        responseFingerprint = responseFingerprint,
        responseSummary = "legacy response $index",
        occurredAtEpochMillis = occurredAtEpochMillis,
        sourceVersion = "legacy-source-v1",
        sourcePayloadCanonicalFingerprint = sourcePayloadFingerprint,
        proofPresent = false,
        sourceProofCanonicalFingerprint = null,
        sourceReferenceId = null,
        targetKind = null,
        targetDatabase = null,
        targetId = null,
        targetVersion = null,
        targetCanonicalFingerprint = null,
        attestedAtEpochMillis = null,
        sourceRecordCanonicalFingerprint = sourceRecordFingerprint,
    )
}

private fun Runtime.usedHeapBytes(): Long =
    totalMemory() - freeMemory()

private fun SQLiteStatement.bindAndExecute(bindings: SQLiteStatement.() -> Unit) {
    clearBindings()
    bindings()
    executeInsert()
}

private fun SQLiteDatabase.queryLong(
    sql: String,
    selectionArgs: Array<String> = emptyArray(),
): Long =
    rawQuery(sql, selectionArgs).use { cursor ->
        check(cursor.moveToFirst()) { "Expected one scalar row for: $sql" }
        cursor.getLong(0)
    }

private fun questionMarks(count: Int): String = List(count) { "?" }.joinToString()

private fun fingerprint(
    domain: Int,
    index: Int,
): String =
    domain.toString(16).padStart(2, '0') +
        index.toString(16).padStart(62, '0')

private fun knowledgeNodeId(
    subject: SubjectKind,
    nodeIndex: Int,
): String = "scale-${subject.name.lowercase(Locale.US)}-knowledge-$nodeIndex"

private fun stableNodeFingerprint(
    subject: SubjectKind,
    nodeIndex: Int,
): String =
    MasteryProjectionIdentity.fingerprint(
        subject = subject.name,
        knowledgeNodeId = knowledgeNodeId(subject, nodeIndex),
        taxonomyVersion = SCALE_TAXONOMY_VERSION,
    )

private fun databaseFootprintBytes(databasePath: File): Long =
    listOf(
        databasePath,
        File(databasePath.absolutePath + "-wal"),
        File(databasePath.absolutePath + "-shm"),
    ).sumOf { file ->
        if (file.exists()) file.length() else 0L
    }

private fun Long.asMilliseconds(): String =
    String.format(Locale.US, "%.3f", this / 1_000_000.0)

private const val SCALE_TAXONOMY_VERSION = "scale-taxonomy-v1"
