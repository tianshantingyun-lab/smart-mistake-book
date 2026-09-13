package com.tingyun.smartmistakebook.core.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.ErrorBookEntrySeedRecord
import com.tingyun.smartmistakebook.core.database.PracticeUnitSeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemRevisionSeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemSeedRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudySeedBundle
import com.tingyun.smartmistakebook.core.domain.BackupValidation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Fault-injection matrix for backup/restore V2 (§10.4).
 *
 * Runnable scenarios: truncated archive, checksum corruption, zip bomb
 * (compression ratio), too many entries, single-entry oversize, total
 * decompression oversize, pre-swap staged database validation failure,
 * duplicate restore request idempotency, startup recovery after an
 * interrupted swap (both the database half and the asset half), and
 * "a committed restore must not be rolled back on the next launch".
 *
 * **真进程死亡在本测试宿主里无法自动化**：instrumentation 跑在要被杀的那个进程里，
 * `Process.killProcess(myPid())` 会把整轮测试连同 runner 一起杀掉，用例永远执行不到
 * 断言。此前这里有三个把注入开关硬编码为 false 的 `@Test` 空体（审计
 * `backup-skeleton-tests`），默认路径下不断言任何东西却作为 passed 计入用例数；
 * 它们现在换成了有断言的**仿真等价物**——由生产代码留下的崩溃现场 ＋ 真实启动恢复。
 * 仿真覆盖不到的唯一一环是"物理掉电后 fsync 是否真的落盘"，那需要真机断电实验。
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreInstrumentedTest {
    @Test
    fun backupDeleteAndRestoreRoundTripPreservesCatalog() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
        val backupDirectory = File(context.filesDir, "backup-round-trip").apply { mkdirs() }
        val archive = File(backupDirectory, "round-trip-${System.nanoTime()}.smbk")
        try {
            val database = StudyDatabaseFactory.open(context)
            database.seedFixture(seed())
            val repository = AndroidBackupRepository(context, database)

            val receipt = FileOutputStream(archive).use { output ->
                repository.create(output)
            }
            assertEquals(1, receipt.problemCount)

            val validation = FileInputStream(archive).use { input ->
                repository.validate(input)
            }
            assertTrue(validation is BackupValidation.Valid)

            val deleted = repository.deleteAllData()
            assertTrue(deleted.deletedDatabaseBytes > 0L)

            val reopenedEmpty = StudyDatabaseFactory.open(context)
            assertEquals(0, reopenedEmpty.countMistakes())
            reopenedEmpty.close()

            val restoreReceipt = FileInputStream(archive).use { input ->
                repository.restore(input)
            }
            assertEquals(1, restoreReceipt.problemCount)

            val restored = StudyDatabaseFactory.open(context)
            assertEquals(1, restored.countMistakes())
            restored.close()
        } finally {
            context.deleteDatabase(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
            archive.delete()
            backupDirectory.delete()
        }
    }

    @Test
    fun oldSchemaArchiveMigratesOnRestore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceName = "old-schema-source-${System.nanoTime()}.db"
        val backupDirectory = File(context.filesDir, "backup-old-schema").apply { mkdirs() }
        val archive = File(backupDirectory, "old-schema-${System.nanoTime()}.smbk")
        context.deleteDatabase(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
        context.deleteDatabase(sourceName)
        try {
            createDatabaseFromExportedSchema(context, sourceName, version = 30)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(sourceName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                db.insertOrThrow(
                    "problem",
                    null,
                    ContentValues().apply {
                        put("problem_id", "problem-old")
                        put("canonical_fingerprint", "a".repeat(64))
                        put("subject", "MATH")
                        put("created_at_epoch_millis", 1_000L)
                    },
                )
                db.insertOrThrow(
                    "problem_revision",
                    null,
                    ContentValues().apply {
                        put("revision_id", "revision-old")
                        put("problem_id", "problem-old")
                        put("revision_number", 1)
                        put("title", "旧库备份题")
                        put("problem_markdown", "从旧版本备份恢复后仍能找到这道题。")
                        put("answer_verification_status", "UNKNOWN")
                        put("source_type", "TEST")
                        put("content_fingerprint", "b".repeat(64))
                        put("created_at_epoch_millis", 1_000L)
                    },
                )
                db.insertOrThrow(
                    "practice_unit",
                    null,
                    ContentValues().apply {
                        put("practice_unit_id", "practice-old")
                        put("problem_id", "problem-old")
                        put("problem_revision_id", "revision-old")
                        put("unit_key", "unit:old")
                        put("unit_kind", "PROBLEM")
                        put("title", "旧库备份题")
                        put("prompt_markdown", "从旧版本备份恢复后仍能找到这道题。")
                        put("estimated_seconds", 180)
                        put("created_at_epoch_millis", 1_000L)
                    },
                )
                db.insertOrThrow(
                    "error_book_entry",
                    null,
                    ContentValues().apply {
                        put("entry_id", "entry-old")
                        put("practice_unit_id", "practice-old")
                        put("problem_id", "problem-old")
                        put("current_revision_id", "revision-old")
                        put("status", "ACTIVE")
                        put("accepted_at_epoch_millis", 1_000L)
                        put("updated_at_epoch_millis", 1_000L)
                    },
                )
            }

            val current = StudyDatabaseFactory.open(context)
            val repository = AndroidBackupRepository(context, current)
            FileOutputStream(archive).use { output ->
                SmbkArchiveCodec.create(
                    archive = output,
                    database = context.getDatabasePath(sourceName),
                    assets = emptyList(),
                    databaseSchemaVersion = 30,
                    problemCount = 1,
                    createdAtEpochMillis = 1_000L,
                )
            }

            val restoreReceipt = FileInputStream(archive).use { input ->
                repository.restore(input)
            }
            assertEquals(30, restoreReceipt.databaseSchemaVersion)
            assertEquals(1, restoreReceipt.problemCount)

            val restored = StudyDatabaseFactory.open(context)
            assertEquals(1, restored.countMistakes())
            assertEquals(
                "从旧版本备份恢复后仍能找到这道题。",
                restored.libraryCatalogPage(
                    searchText = "",
                    subjectId = null,
                    sectionId = null,
                    knowledgePointId = null,
                    masteryId = null,
                    sort = "RECENTLY_CREATED",
                    offset = 0,
                    limit = 10,
                ).single().problemMarkdown,
            )
            restored.close()
        } finally {
            context.deleteDatabase(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
            context.deleteDatabase(sourceName)
            archive.delete()
            backupDirectory.delete()
        }
    }

    @Test
    fun orphanCanonicalAssetIsRemovedWithoutTouchingReferencedAssets() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "orphan-cleanup-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            val database = StudyDatabaseFactory.open(context, databaseName)
            database.seedFixture(seed())
            val repository = AndroidBackupRepository(context, database)
            val orphan = com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord(
                sourceAssetId = "asset-orphan",
                contentSha256 = "e".repeat(64),
                relativePath = "source-assets/${"e".repeat(64)}.jpg",
                mimeType = "image/jpeg",
                byteSize = 1_024,
                width = 100,
                height = 200,
                sourceType = "CAMERA",
                createdAtEpochMillis = 2_000,
            )
            database.insertOrphanCanonicalAssetForTest(orphan)

            val removed = repository.cleanupOrphanAssets()

            assertEquals(1, removed)
            assertEquals(null, database.readCanonicalSourceAsset("asset-orphan"))
            assertEquals(1, database.countMistakes())
            database.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    // ------------------------------------------------------------------
    // §10.4 fault injection: archive-level attacks
    // ------------------------------------------------------------------

    @Test
    fun truncatedArchiveIsRejectedByValidateAndRestore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scratch = File(context.cacheDir, "fault-truncated").apply { mkdirs() }
        try {
            val archive = buildSyntheticArchive(databaseContent = ByteArray(8_192) { 7 })
            // Injection point: cut the archive at 70% of its length.
            val truncated = archive.copyOfRange(0, (archive.size * 0.7).toInt())

            val validation = SmbkArchiveCodec.validate(
                ByteArrayInputStream(truncated),
                File(scratch, "v1").apply { mkdirs() },
            )
            assertTrue("截断归档必须被拒绝", validation is BackupValidation.Invalid)

            val database = StudyDatabaseFactory.open(context)
            val repository = AndroidBackupRepository(context, database)
            val failure = runCatching {
                repository.restore(ByteArrayInputStream(truncated))
            }
            assertTrue(failure.isFailure)
            database.close()
        } finally {
            context.deleteDatabase(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
            scratch.deleteRecursively()
        }
    }

    @Test
    fun checksumMismatchIsRejected() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scratch = File(context.cacheDir, "fault-checksum").apply { mkdirs() }
        try {
            // Archive whose data bytes do not match the manifest/checksum sha.
            val archive = buildSyntheticArchive(databaseContent = "original-db".toByteArray())
            val validation = SmbkArchiveCodec.validate(
                ByteArrayInputStream(corruptDataByte(archive)),
                File(scratch, "v1").apply { mkdirs() },
            )
            assertTrue("checksum 不一致必须被拒绝", validation is BackupValidation.Invalid)
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun zipBombCompressionRatioIsRejected() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scratch = File(context.cacheDir, "fault-bomb").apply { mkdirs() }
        try {
            // A single entry of 16 MB zeros compresses to a few KB → ratio ≫ 100.
            val bomb = buildSyntheticArchive(databaseContent = ByteArray(16 * 1024 * 1024))
            val validation = SmbkArchiveCodec.validate(
                ByteArrayInputStream(bomb),
                scratch,
            )
            assertTrue("压缩比超限必须被拒绝", validation is BackupValidation.Invalid)
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun tooManyEntriesAreRejected() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scratch = File(context.cacheDir, "fault-entries").apply { mkdirs() }
        try {
            val limits = SmbkResourceLimits(maxEntryCount = 3)
            val archive = buildSyntheticArchive(
                databaseContent = "db".toByteArray(),
                extraAssets = mapOf(
                    "assets/a1.bin" to byteArrayOf(1),
                    "assets/a2.bin" to byteArrayOf(2),
                    "assets/a3.bin" to byteArrayOf(3),
                ),
            )
            val validation = SmbkArchiveCodec.validate(
                ByteArrayInputStream(archive),
                scratch,
                limits,
            )
            assertTrue("条目数超限必须被拒绝", validation is BackupValidation.Invalid)
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun singleEntryOversizeIsRejected() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scratch = File(context.cacheDir, "fault-single").apply { mkdirs() }
        try {
            val limits = SmbkResourceLimits(maxSingleEntryBytes = 256L)
            val archive = buildSyntheticArchive(databaseContent = ByteArray(1_024) { 3 })
            val validation = SmbkArchiveCodec.validate(
                ByteArrayInputStream(archive),
                scratch,
                limits,
            )
            assertTrue("单条目超限必须被拒绝", validation is BackupValidation.Invalid)
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun totalDecompressionOversizeIsRejected() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scratch = File(context.cacheDir, "fault-total").apply { mkdirs() }
        try {
            // Per-entry limit high, but the total budget is exhausted by entry #2.
            val limits = SmbkResourceLimits(
                maxSingleEntryBytes = 4_096L,
                maxTotalDecompressedBytes = 700L,
            )
            val archive = buildSyntheticArchive(
                databaseContent = ByteArray(512) { 4 },
                extraAssets = mapOf("assets/big.bin" to ByteArray(512) { 5 }),
            )
            val validation = SmbkArchiveCodec.validate(
                ByteArrayInputStream(archive),
                scratch,
                limits,
            )
            assertTrue("总解压超限必须被拒绝", validation is BackupValidation.Invalid)
        } finally {
            scratch.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // §10.4 fault injection: restore-level attacks
    // ------------------------------------------------------------------

    @Test
    fun preSwapValidationFailureRejectsCorruptDatabaseAndKeepsLiveData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
        try {
            val database = StudyDatabaseFactory.open(context)
            database.seedFixture(seed())
            val repository = AndroidBackupRepository(context, database)

            // Archive whose database entry is not a SQLite database at all.
            val corruptArchive = buildSyntheticArchive(
                databaseContent = "this is not a sqlite database".toByteArray(),
            )

            val failure = runCatching {
                repository.restore(ByteArrayInputStream(corruptArchive))
            }
            assertTrue("预验证失败必须拒绝切换", failure.isFailure)
            assertTrue(
                (failure.exceptionOrNull() as? BackupRestoreException)
                    ?.message.orEmpty()
                    .contains("预验证失败"),
            )

            // Live data must be untouched: reopen and count.
            database.close()
            val reopened = StudyDatabaseFactory.open(context)
            assertEquals(1, reopened.countMistakes())
            reopened.close()
        } finally {
            context.deleteDatabase(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
        }
    }

    @Test
    fun duplicateConcurrentRestoreRequestsAreRejectedIdempotently() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
        try {
            val database = StudyDatabaseFactory.open(context)
            database.seedFixture(seed())
            val repository = AndroidBackupRepository(context, database)
            val archiveBytes = ByteArrayOutputStream().use { output ->
                repository.create(output)
                output.toByteArray()
            }

            val outcomes = coroutineScope {
                val slow = launch {
                    // First request reads through a throttled stream so the
                    // second request is guaranteed to overlap it.
                    runCatching {
                        repository.restore(ThrottledInputStream(ByteArrayInputStream(archiveBytes)))
                    }
                }
                delay(100)
                val second = async {
                    runCatching {
                        repository.restore(ByteArrayInputStream(archiveBytes))
                    }
                }
                slow.join()
                listOf(second.await())
            }
            val secondOutcome = outcomes.single()
            assertTrue("重叠的第二次恢复请求必须被拒绝", secondOutcome.isFailure)
            assertTrue(
                (secondOutcome.exceptionOrNull() as? BackupRestoreException)
                    ?.message.orEmpty()
                    .contains("重复的恢复请求"),
            )

            // The data set must still be consistent afterwards.
            database.close()
            val reopened = StudyDatabaseFactory.open(context)
            assertEquals(1, reopened.countMistakes())
            reopened.close()
        } finally {
            context.deleteDatabase(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
        }
    }

    // ------------------------------------------------------------------
    // §10.4 fault injection: startup recovery after simulated process death
    // ------------------------------------------------------------------

    @Test
    fun startupRecoveryRollsBackGenerationAfterInterruptedSwap() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
        val dbName = StudyDatabaseFactory.DEFAULT_DATABASE_NAME
        val dbFile = context.getDatabasePath(dbName)
        val databasesDir = dbFile.parentFile!!
        val assetDir = File(context.filesDir, "source-assets")
        try {
            // Live generation: a seeded database.
            val database = StudyDatabaseFactory.open(context)
            database.seedFixture(seed())
            database.close()

            // Simulate the restore having displaced the live generation:
            //   live db renamed to .prev, staged .next promoted to live.
            val previousDb = File(databasesDir, "$dbName.prev")
            val badLive = dbFile.readBytes()
            assertTrue(dbFile.renameTo(previousDb))
            File(databasesDir, "$dbName.next").writeBytes(badLive) // leftover staged gen

            val restoreId = "simulated-death-${System.nanoTime()}"
            val journalFile = File(context.noBackupFilesDir, "restore-journal-$restoreId.json")
            journalFile.writeText(
                Json.encodeToString(
                    JournalEntry.serializer(),
                    JournalEntry(
                        restoreId = restoreId,
                        phase = RestorePhase.SWAPPING.name,
                        timestamp = System.currentTimeMillis(),
                        liveDatabasePath = dbFile.absolutePath,
                        previousDatabasePath = previousDb.absolutePath,
                        nextDatabasePath = File(databasesDir, "$dbName.next").absolutePath,
                        liveAssetDir = assetDir.absolutePath,
                        previousAssetDir = null,
                        nextAssetDir = null,
                        stagingDir = null,
                    ),
                ),
            )

            // Injection point: in a real process death the process would be
            // killed right after the SWAPPING journal write; the test simply
            // restarts the recovery path instead.
            val outcome = BackupRestoreStartupRecovery.recoverOnStartup(context)
            assertTrue(
                "半程切换必须被回滚, 实际: $outcome",
                outcome is RestoreStartupOutcome.RolledBack,
            )
            assertFalse(previousDb.exists())
            assertFalse(File(databasesDir, "$dbName.next").exists())

            val reopened = StudyDatabaseFactory.open(context)
            assertEquals("回滚后必须恢复原数据", 1, reopened.countMistakes())
            reopened.close()
        } finally {
            context.deleteDatabase(dbName)
            File(databasesDir, "$dbName.prev").delete()
            File(databasesDir, "$dbName.next").delete()
            context.noBackupFilesDir.listFiles().orEmpty()
                .filter { it.name.startsWith("restore-journal-") }
                .forEach { it.delete() }
        }
    }

    @Test
    fun startupRecoveryCleansPreSwapInterruptionWithoutTouchingLiveData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
        try {
            val database = StudyDatabaseFactory.open(context)
            database.seedFixture(seed())
            database.close()

            val restoreId = "simulated-preswap-${System.nanoTime()}"
            val staging = File(context.cacheDir, "restore-$restoreId").apply { mkdirs() }
            File(staging, "staging").mkdirs()
            val journalFile = File(context.noBackupFilesDir, "restore-journal-$restoreId.json")
            journalFile.writeText(
                Json.encodeToString(
                    JournalEntry.serializer(),
                    JournalEntry(
                        restoreId = restoreId,
                        phase = RestorePhase.VALIDATING.name,
                        timestamp = System.currentTimeMillis(),
                        stagingDir = staging.absolutePath,
                    ),
                ),
            )

            // Injection point: process died while still validating/unpacking;
            // nothing was swapped yet.
            val outcome = BackupRestoreStartupRecovery.recoverOnStartup(context)
            assertTrue(
                "切换前中断只需清理, 实际: $outcome",
                outcome is RestoreStartupOutcome.Cleaned,
            )
            assertFalse(journalFile.exists())
            assertFalse(staging.exists())

            val reopened = StudyDatabaseFactory.open(context)
            assertEquals(1, reopened.countMistakes())
            reopened.close()
        } finally {
            context.deleteDatabase(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)
        }
    }

    // ------------------------------------------------------------------
    // §10.4 进程死亡：两次原子切换中途 / 提交之后
    //
    // 真进程死亡杀的是本测试所在的进程，断言永远执行不到，所以这里换成**仿真等价物**：
    // 用生产代码（`RestoreJournal` ＋ 与 `doRestore` 相同的文件布局）摆出崩溃那一刻的
    // 现场，再跑真实的 `BackupRestoreStartupRecovery`，断言它把现场收拾成什么样。
    // 关键是现场的**两代内容必须可分辨**——两代一样的话，把回滚整个跳过也能通过。
    // ------------------------------------------------------------------

    /**
     * 死在 `Atomic switch #1`（数据库 rename）与 `#2`（资产 rename）之间。
     *
     * 此刻磁盘上：数据库已经是新一代，资产还是上一代，`.next` 建好未切。
     * 下一次启动必须把数据库回滚到上一代，**并且不能顺手把没切的资产那一半删掉**。
     */
    @Test
    fun startupRecoveryRollsBackDatabaseHalfSwapWithoutTouchingAssets() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = StudyDatabaseFactory.DEFAULT_DATABASE_NAME
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)
        val databasesDir = dbFile.parentFile!!
        val assetRoot = File(context.filesDir, ASSET_DIRECTORY)
        val previousAssets = File(context.filesDir, "$ASSET_DIRECTORY.prev")
        val nextAssets = File(context.filesDir, "$ASSET_DIRECTORY.next")
        val previousDb = File(databasesDir, "$dbName.prev")
        val nextDb = File(databasesDir, "$dbName.next")
        val restoreId = "death-between-switches-${System.nanoTime()}"
        val staging = File(context.cacheDir, "restore-$restoreId").apply { mkdirs() }
        try {
            // 上一代：已播种的活库 ＋ 活资产目录。
            val database = StudyDatabaseFactory.open(context)
            database.seedFixture(seed())
            database.close()
            assetRoot.mkdirs()
            File(assetRoot, OLD_ASSET_NAME).writeBytes(byteArrayOf(1))

            assertTrue("活库必须能被换成 .prev 代", dbFile.renameTo(previousDb))
            dbFile.writeBytes(newGenerationDatabaseBytes(context))
            nextAssets.mkdirs()
            File(nextAssets, NEW_ASSET_NAME).writeBytes(byteArrayOf(2))
            writeCrashJournal(
                context = context,
                restoreId = restoreId,
                phase = RestorePhase.SWAPPING,
                liveDatabasePath = dbFile,
                previousDatabasePath = previousDb,
                nextDatabasePath = nextDb,
                liveAssetDir = assetRoot,
                previousAssetDir = previousAssets,
                nextAssetDir = nextAssets,
                stagingDir = staging,
            )

            val outcome = BackupRestoreStartupRecovery.recoverOnStartup(context)

            assertTrue("切换中途死亡必须回滚，实际: $outcome", outcome is RestoreStartupOutcome.RolledBack)
            val reopened = StudyDatabaseFactory.open(context)
            assertEquals(
                "活库必须是上一代，而不是切换了一半的新一代",
                OLD_GENERATION_MARKDOWN,
                reopened.soleProblemMarkdown(),
            )
            reopened.close()
            assertFalse(previousDb.exists())
            assertFalse(nextDb.exists())
            // 资产那一半本来就没切：活目录仍是上一代，回滚不该删掉它或换成 .next。
            assertEquals(
                listOf(OLD_ASSET_NAME),
                assetRoot.listFiles().orEmpty().filter(File::isFile).map(File::getName).sorted(),
            )
            assertFalse(nextAssets.exists())
            assertFalse("暂存目录必须被清掉", staging.exists())
            assertFalse(
                "恢复完成后日志必须消失，否则下一次启动会再回滚一次",
                File(context.noBackupFilesDir, "restore-journal-$restoreId.json").exists(),
            )
        } finally {
            context.deleteDatabase(dbName)
            previousDb.delete()
            nextDb.delete()
            previousAssets.deleteRecursively()
            nextAssets.deleteRecursively()
            staging.deleteRecursively()
            restoreArtifactsOf(context).forEach { it.delete() }
        }
    }

    /**
     * 死在 `Atomic switch #2`（资产 rename）之后、VERIFYING 完成之前。
     *
     * 此刻磁盘上两半都已经是新一代（`.prev` 两代都在）。下一次启动必须把**两代一起**
     * 回滚：这一条是"回滚要同时管数据库和资产"的判据——只回滚数据库的实现会在
     * 资产断言上红。
     */
    @Test
    fun startupRecoveryRollsBackBothGenerationsAfterDeathBetweenAssetSwapAndVerify() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = StudyDatabaseFactory.DEFAULT_DATABASE_NAME
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)
        val databasesDir = dbFile.parentFile!!
        val assetRoot = File(context.filesDir, ASSET_DIRECTORY)
        val previousAssets = File(context.filesDir, "$ASSET_DIRECTORY.prev")
        val nextAssets = File(context.filesDir, "$ASSET_DIRECTORY.next")
        val previousDb = File(databasesDir, "$dbName.prev")
        val nextDb = File(databasesDir, "$dbName.next")
        val restoreId = "death-before-verify-${System.nanoTime()}"
        val staging = File(context.cacheDir, "restore-$restoreId").apply { mkdirs() }
        try {
            val database = StudyDatabaseFactory.open(context)
            database.seedFixture(seed())
            database.close()
            assetRoot.mkdirs()
            File(assetRoot, OLD_ASSET_NAME).writeBytes(byteArrayOf(1))

            // switch #1 ＋ #2 都做完了：数据库和资产都是新一代，两代的 .prev 都还在。
            assertTrue(dbFile.renameTo(previousDb))
            dbFile.writeBytes(newGenerationDatabaseBytes(context))
            nextAssets.mkdirs()
            File(nextAssets, NEW_ASSET_NAME).writeBytes(byteArrayOf(2))
            assertTrue(assetRoot.renameTo(previousAssets))
            assertTrue(nextAssets.renameTo(assetRoot))
            writeCrashJournal(
                context = context,
                restoreId = restoreId,
                phase = RestorePhase.VERIFYING,
                liveDatabasePath = dbFile,
                previousDatabasePath = previousDb,
                nextDatabasePath = nextDb,
                liveAssetDir = assetRoot,
                previousAssetDir = previousAssets,
                nextAssetDir = nextAssets,
                stagingDir = staging,
            )

            val outcome = BackupRestoreStartupRecovery.recoverOnStartup(context)

            assertTrue("VERIFYING 前死亡必须回滚，实际: $outcome", outcome is RestoreStartupOutcome.RolledBack)
            val reopened = StudyDatabaseFactory.open(context)
            assertEquals(OLD_GENERATION_MARKDOWN, reopened.soleProblemMarkdown())
            reopened.close()
            assertEquals(
                "资产必须和数据库一起回到上一代",
                listOf(OLD_ASSET_NAME),
                assetRoot.listFiles().orEmpty().filter(File::isFile).map(File::getName).sorted(),
            )
            assertFalse(previousDb.exists())
            assertFalse(previousAssets.exists())
            assertFalse(nextDb.exists())
            assertFalse(nextAssets.exists())
            assertFalse(File(context.noBackupFilesDir, "restore-journal-$restoreId.json").exists())
        } finally {
            context.deleteDatabase(dbName)
            previousDb.delete()
            nextDb.delete()
            previousAssets.deleteRecursively()
            nextAssets.deleteRecursively()
            staging.deleteRecursively()
            restoreArtifactsOf(context).forEach { it.delete() }
        }
    }

    /**
     * 恢复**成功提交**之后，下一次启动不得回滚它。
     *
     * 现状里没有任何用例断言这件事：`doRestore` 走完必须把日志清掉，否则下一次启动
     * 会把刚恢复进来的数据当成"切换了一半"再回滚掉——用户看到的是"恢复成功、重启后
     * 数据回到恢复前"，属于静默数据丢失。这里跑一次**真实**的恢复（不是仿真现场），
     * 再断言：盘上没有日志、启动恢复无事可做、恢复进来的那一代还在。
     */
    @Test
    fun committedRestoreLeavesNoJournalSoTheNextStartupDoesNotRollBack() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = StudyDatabaseFactory.DEFAULT_DATABASE_NAME
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)
        val databasesDir = dbFile.parentFile!!
        val previousDb = File(databasesDir, "$dbName.prev")
        val nextDb = File(databasesDir, "$dbName.next")
        val archive = File(context.filesDir, "committed-restore-${System.nanoTime()}.smbk")
        try {
            // 先让「新一代」成为活库，用它做一份归档；再把活库换回上一代，
            // 这样"恢复成功"这件事是可分辨的（恢复前是上一代，恢复后是新一代）。
            val first = StudyDatabaseFactory.open(context)
            first.seedFixture(newGenerationSeed())
            val repository = AndroidBackupRepository(context, first)
            FileOutputStream(archive).use { output -> repository.create(output) }
            repository.deleteAllData()
            val second = StudyDatabaseFactory.open(context)
            second.seedFixture(seed())
            second.close()

            val receipt = FileInputStream(archive).use { input -> repository.restore(input) }
            assertTrue("归档里应当有一道题，实际: ${receipt.problemCount}", receipt.problemCount > 0)

            val withoutJournal = restoreArtifactsOf(context)
            assertTrue(
                "提交成功的恢复不得留下任何恢复日志（本次实际留下: " +
                    "${withoutJournal.map(File::getName)}）",
                withoutJournal.isEmpty(),
            )
            assertFalse("提交成功后不得留下上一代残留", previousDb.exists())
            assertFalse(nextDb.exists())

            val outcome = BackupRestoreStartupRecovery.recoverOnStartup(context)
            assertTrue(
                "已提交的恢复必须被下一次启动放过，而不是再回滚一次，实际: $outcome",
                outcome is RestoreStartupOutcome.NothingToRecover,
            )
            val reopened = StudyDatabaseFactory.open(context)
            assertEquals(
                "下一次启动之后，恢复进来的那一代必须还在",
                NEW_GENERATION_MARKDOWN,
                reopened.soleProblemMarkdown(),
            )
            reopened.close()
        } finally {
            context.deleteDatabase(dbName)
            previousDb.delete()
            nextDb.delete()
            archive.delete()
            restoreArtifactsOf(context).forEach { it.delete() }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * 留下一条崩溃时的恢复日志——用**生产**的写入器 [RestoreJournal]，不是手写 JSON。
     *
     * 手写 JSON 只能证明"读取端认得这个格式"，证明不了"写入端写的就是这个格式"：
     * 两边一旦漂移（改字段名、改写入时机），手写的那条照样绿，而真机上的启动恢复
     * 会读不出日志、把切换了一半的库当成正常库打开。这里让写入端和读取端在同一个
     * 用例里对上，剩下的差别只有"谁做的 rename"。
     *
     * 调用顺序与 `AndroidBackupRepository.doRestore` 一致：先记世代路径，再写相位。
     */
    private fun writeCrashJournal(
        context: Context,
        restoreId: String,
        phase: RestorePhase,
        liveDatabasePath: File,
        previousDatabasePath: File,
        nextDatabasePath: File,
        liveAssetDir: File,
        previousAssetDir: File,
        nextAssetDir: File,
        stagingDir: File,
    ): RestoreJournal = RestoreJournal(context, restoreId).apply {
        recordGenerationPaths(
            liveDatabasePath = liveDatabasePath,
            previousDatabasePath = previousDatabasePath,
            nextDatabasePath = nextDatabasePath,
            liveAssetDir = liveAssetDir,
            previousAssetDir = previousAssetDir,
            nextAssetDir = nextAssetDir,
            stagingDir = stagingDir,
        )
        writePhase(phase)
    }

    /** 盘上残留的恢复日志（`BackupRestoreStartupRecovery` 真正会去扫的那一批）。 */
    private fun restoreArtifactsOf(context: Context): List<File> =
        context.noBackupFilesDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("restore-journal-") }
            .sortedBy(File::getName)

    /**
     * 造一份「新一代」数据库文件，返回它的字节。
     *
     * 两代内容必须**可分辨**：回滚用例要断了"活下来的是哪一代"。两代一模一样时，
     * 连"把回滚整个跳过"都能通过——那样的用例是假的。
     */
    private suspend fun newGenerationDatabaseBytes(context: Context): ByteArray {
        val name = "restore-new-generation-${System.nanoTime()}.db"
        return try {
            val database = StudyDatabaseFactory.open(context, name)
            database.seedFixture(newGenerationSeed())
            database.close()
            context.getDatabasePath(name).readBytes()
        } finally {
            context.deleteDatabase(name)
        }
    }

    /** 活库的题面——用来判定"现在活着的是哪一代"。 */
    private suspend fun StudyDatabasePort.soleProblemMarkdown(): String =
        libraryCatalogPage(
            searchText = "",
            subjectId = null,
            sectionId = null,
            knowledgePointId = null,
            masteryId = null,
            sort = "RECENTLY_CREATED",
            offset = 0,
            limit = 10,
        ).single().problemMarkdown

    /**
     * Wraps a stream and sleeps per chunk so a restore runs slowly.
     */
    private class ThrottledInputStream(
        delegate: InputStream,
    ) : java.io.FilterInputStream(delegate) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            Thread.sleep(5)
            return super.read(b, off, len.coerceAtMost(1_024))
        }
    }

    /** Builds a spec-conformant .smbk archive with controllable payloads. */
    private fun buildSyntheticArchive(
        databaseContent: ByteArray,
        extraAssets: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val dbSha = sha256Hex(databaseContent)
        val assetRecords = extraAssets.map { (path, bytes) ->
            Triple(path, sha256Hex(bytes), bytes.size.toLong())
        }
        val manifest = buildString {
            append("{")
            append("\"formatVersion\":1,")
            append("\"databaseSchemaVersion\":32,")
            append("\"createdAt\":1000,")
            append("\"problemCount\":1,")
            append("\"assetCount\":${assetRecords.size},")
            append("\"hashAlgorithm\":\"SHA-256\",")
            append("\"files\":[")
            append("{\"path\":\"database.sqlite\",\"sha256\":\"$dbSha\",\"byteSize\":${databaseContent.size}}")
            assetRecords.forEach { (path, sha, size) ->
                append(",{\"path\":\"$path\",\"sha256\":\"$sha\",\"byteSize\":$size}")
            }
            append("]}")
        }
        val checksums = buildString {
            append("$dbSha *database.sqlite")
            assetRecords.forEach { (path, sha, _) -> append("\n$sha *$path") }
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("database.sqlite"))
            zip.write(databaseContent)
            zip.closeEntry()
            extraAssets.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("checksums.sha256"))
            zip.write(checksums.toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    /**
     * Corrupts one data byte inside the archive. Scans backwards from the
     * checksums entry position for a non-zero byte that is part of the
     * database entry payload; flipping it changes the decompressed sha while
     * keeping the zip structure readable.
     */
    private fun corruptDataByte(archive: ByteArray): ByteArray {
        val corrupted = archive.copyOf()
        // Flip a byte ~40% into the archive: deterministically inside the
        // compressed database entry for the small synthetic archives used here.
        val position = (corrupted.size * 0.4).toInt()
        corrupted[position] = (corrupted[position].toInt() xor 0xFF).toByte()
        return corrupted
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun seed() = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(
                problemId = "problem-backup",
                canonicalFingerprint = "a".repeat(64),
                subject = "MATH",
                createdAtEpochMillis = 1_000,
            ),
        ),
        revisions = listOf(
            ProblemRevisionSeedRecord(
                revisionId = "revision-backup",
                problemId = "problem-backup",
                revisionNumber = 1,
                title = "备份恢复题",
                problemMarkdown = OLD_GENERATION_MARKDOWN,
                questionDocumentSnapshot = null,
                answerSpecId = null,
                answerSpecSnapshot = null,
                answerVerificationStatus = "UNKNOWN",
                sourceType = "TEST",
                sourceReference = null,
                contentFingerprint = "b".repeat(64),
                createdAtEpochMillis = 1_000,
            ),
        ),
        practiceUnits = listOf(
            PracticeUnitSeedRecord(
                practiceUnitId = "practice-backup",
                problemId = "problem-backup",
                problemRevisionId = "revision-backup",
                unitKey = "unit:backup",
                unitKind = "PROBLEM",
                title = "备份恢复题",
                promptMarkdown = "求函数最值。",
                estimatedSeconds = 180,
                createdAtEpochMillis = 1_000,
            ),
        ),
        errorBookEntries = listOf(
            ErrorBookEntrySeedRecord(
                entryId = "entry-backup",
                practiceUnitId = "practice-backup",
                problemId = "problem-backup",
                currentRevisionId = "revision-backup",
                sourceKey = null,
                acceptedAtEpochMillis = 1_000,
                updatedAtEpochMillis = 1_000,
            ),
        ),
    )

    /**
     * 「新一代」：与 [seed] 结构相同、内容可分辨的另一道题。
     *
     * 用在两处：造导出用的新一代库、以及判定回滚之后**活下来的是哪一代**。
     * 题目 id 与指纹都与 [seed] 不同，避免两代在库里撞成同一行。
     */
    private fun newGenerationSeed() = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(
                problemId = "problem-restored",
                canonicalFingerprint = "c".repeat(64),
                subject = "PHYSICS",
                createdAtEpochMillis = 2_000,
            ),
        ),
        revisions = listOf(
            ProblemRevisionSeedRecord(
                revisionId = "revision-restored",
                problemId = "problem-restored",
                revisionNumber = 1,
                title = "恢复进来的题",
                problemMarkdown = NEW_GENERATION_MARKDOWN,
                questionDocumentSnapshot = null,
                answerSpecId = null,
                answerSpecSnapshot = null,
                answerVerificationStatus = "UNKNOWN",
                sourceType = "TEST",
                sourceReference = null,
                contentFingerprint = "d".repeat(64),
                createdAtEpochMillis = 2_000,
            ),
        ),
        practiceUnits = listOf(
            PracticeUnitSeedRecord(
                practiceUnitId = "practice-restored",
                problemId = "problem-restored",
                problemRevisionId = "revision-restored",
                unitKey = "unit:restored",
                unitKind = "PROBLEM",
                title = "恢复进来的题",
                promptMarkdown = NEW_GENERATION_MARKDOWN,
                estimatedSeconds = 180,
                createdAtEpochMillis = 2_000,
            ),
        ),
        errorBookEntries = listOf(
            ErrorBookEntrySeedRecord(
                entryId = "entry-restored",
                practiceUnitId = "practice-restored",
                problemId = "problem-restored",
                currentRevisionId = "revision-restored",
                sourceKey = null,
                acceptedAtEpochMillis = 2_000,
                updatedAtEpochMillis = 2_000,
            ),
        ),
    )
}

/** 上一代题库的题面；[BackupRestoreInstrumentedTest.seed] 与断言共用一份。 */
private const val OLD_GENERATION_MARKDOWN = "求函数最值。"

/** 新一代题库的题面，必须与上一代可分辨。 */
private const val NEW_GENERATION_MARKDOWN = "恢复后应当看到的新一代题。"

/** 上一代资产文件与新一代资产文件——回滚用例靠这两个名字分辨活下来的是哪一代。 */
private const val OLD_ASSET_NAME = "old-generation.asset"
private const val NEW_ASSET_NAME = "new-generation.asset"

/** `AndroidBackupRepository` 的资产根目录名（生产里是私有常量）。 */
private const val ASSET_DIRECTORY = "source-assets"
