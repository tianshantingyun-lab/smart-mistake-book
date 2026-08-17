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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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
