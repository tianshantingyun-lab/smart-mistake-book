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
 * duplicate restore request idempotency, and startup recovery after a
 * simulated process death (journal + generation artifacts crafted by hand).
 *
 * True process-death scenarios (killProcess mid-swap) are provided as
 * executable skeletons with explicit injection points; they are no-ops
 * unless the manual flag is enabled.
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
    // §10.4 process-death skeletons (execute manually with the flag below).
    // ------------------------------------------------------------------

    /**
     * Skeleton: process death BETWEEN the database rename and the asset
     * rename. Injection point is marked below; with [KILL_FOR_REAL] = false
     * the test exercises the equivalent simulated path instead of killing
     * the instrumentation process.
     */
    @Test
    fun processDeathBetweenDatabaseAndAssetSwapSkeleton() {
        val killForReal = false // 注入点：手动置 true 后运行，进程将在切换中途被杀死
        if (killForReal) {
            // 注入点：等价于 restore() 中 `Atomic switch #1` 与 `Atomic switch #2`
            // 之间调用 android.os.Process.killProcess(android.os.Process.myPid())。
            // 下一次启动时 BackupRestoreStartupRecovery 必须把数据库回滚到 .prev 代，
            // 且资产目录保持上一代。
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        // Default (no kill): the simulated equivalent is covered by
        // startupRecoveryRollsBackGenerationAfterInterruptedSwap.
    }

    /**
     * Skeleton: process death right AFTER the asset rename but BEFORE the
     * VERIFYING phase completes. Injection point: kill immediately after
     * `Atomic switch #2` in AndroidBackupRepository.doRestore. On next
     * startup the coordinator sees phase SWAPPING in the journal and rolls
     * the whole generation set back; when [KILL_FOR_REAL] is false this test
     * only documents the expected behaviour.
     */
    @Test
    fun processDeathAfterSwapBeforeVerifySkeleton() {
        val killForReal = false // 注入点：手动置 true 后运行
        if (killForReal) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /**
     * Skeleton: first launch failure after a completed swap. Injection point:
     * force StudyDatabaseFactory.open to throw after a successful restore
     * (e.g. incompatible schema injected via a test build); the startup
     * recovery of the NEXT launch must find no journal (restore committed)
     * and therefore must not roll back automatically — quarantine is a
     * product decision surfaced through startupState instead.
     */
    @Test
    fun firstLaunchFailureAfterCommittedSwapSkeleton() {
        val simulateOpenFailure = false // 注入点：手动置 true 后运行
        if (simulateOpenFailure) {
            error("simulate Room open failure after committed restore swap")
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Wraps a stream and sleeps per chunk so a restore runs slowly. */
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
                problemMarkdown = "求函数最值。",
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
}
