package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v47→48：批量导入页的"切题阶段是否了结"。
 *
 * 迁移的重点不是加列，而是回填。既有的 READY / SKIPPED / FAILED 页必须标成 SETTLED，
 * 否则它们会被当成"还没切过"重新驱动；而它们的暂存原图早已清理，重跑只会写出一条
 * source_uri 指向已删除文件的分题记录，复核页的原图打不开。只有还没走过这一趟
 * （QUEUED / IMPORTING）的页保留 PENDING，随新流程正常处理。
 *
 * 本测试用读写连接直接往 v47 库里塞页，因为导出 schema 的建库辅助只建空库，而空库
 * 上的回填 UPDATE 一行都不碰——那样测不到回填。
 */
@RunWith(AndroidJUnit4::class)
class BatchImportSplitStateMigrationInstrumentedTest {
    @Test
    fun versionFortySevenMigratesSplitStageAndGrandfathersOldPages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "batch-split-state-v47-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 47)
            seedVersionFortySevenPages(context, databaseName)

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            val pages = checkNotNull(migrated.readBatchImportJob("legacy-batch"))
                .pages
                .associateBy(BatchImportPageRecord::pageIndex)

            // 已经走过切题那一趟的页：不归本次驱动管。
            assertEquals(
                StudyDbValue.BatchImportSplitStatus.SETTLED,
                pages.getValue(0).splitAfterStatus,
            )
            assertEquals(
                StudyDbValue.BatchImportSplitStatus.SETTLED,
                pages.getValue(1).splitAfterStatus,
            )
            assertEquals(
                StudyDbValue.BatchImportSplitStatus.SETTLED,
                pages.getValue(2).splitAfterStatus,
            )
            // 还没走过这一趟的页：保留 PENDING。
            assertEquals(
                StudyDbValue.BatchImportSplitStatus.PENDING,
                pages.getValue(3).splitAfterStatus,
            )
            assertEquals(STUDY_DATABASE_VERSION, migrated.readDatabaseVersion())
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    /** Page 0 READY, page 1 SKIPPED, page 2 FAILED, page 3 QUEUED — all without split state. */
    private fun seedVersionFortySevenPages(context: Context, databaseName: String) {
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            database.execSQL(
                "INSERT INTO batch_import_job (" +
                    "job_id, request_id, request_fingerprint, status, " +
                    "created_at_epoch_millis, updated_at_epoch_millis" +
                    ") VALUES ('legacy-batch', 'legacy-request', '${"a".repeat(64)}', " +
                    "'COMPLETED', 1, 1)",
            )
            listOf("READY", "SKIPPED", "FAILED", "QUEUED").forEachIndexed { index, status ->
                database.execSQL(
                    "INSERT INTO batch_import_page (" +
                        "job_id, page_index, source_uri, status, result_draft_id, " +
                        "failure_code, attempt_count, created_at_epoch_millis, " +
                        "updated_at_epoch_millis, boundary_after_status" +
                        ") VALUES ('legacy-batch', $index, 'content://legacy/$index', " +
                        "'$status', NULL, NULL, 0, 1, 1, 'PENDING')",
                )
            }
        } finally {
            database.close()
        }
    }
}
