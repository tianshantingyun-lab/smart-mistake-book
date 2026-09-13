package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1..44 每一个导出 schema 版本都要能迁到当前版本，**而且不能把数据弄丢**。
 *
 * 审计 `migration-matrix-empty-assertion` 点的是这句：此前每一版只断言
 * `libraryCatalogCount(...) == 0`，而夹具库是**空的**——那句话对任何迁移都成立，
 * 所以破坏性回退（例如某版 `DROP TABLE problem` 再重建）一条都测不出来，
 * `room_migration_1_to_44` 的"连续无破坏性回退"这个结论因此没有数据面证据。
 *
 * 现在先按**这一版自己的列形状**写一行错题（[seedMinimalLibraryRow]），迁移后再断言：
 * 目录里还是那**一行**、题面还是那**一句**。少了任何一条，都说明某一版把数据吃掉了。
 */
@RunWith(AndroidJUnit4::class)
class FullMigrationMatrixInstrumentedTest {
    @Test
    fun everyExportedSchemaVersionMigratesToCurrentWithoutDestructiveFallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in 1..STUDY_DATABASE_VERSION) {
            val databaseName = "migration-matrix-v$version-${System.nanoTime()}.db"
            context.deleteDatabase(databaseName)
            try {
                createDatabaseFromExportedSchema(context, databaseName, version = version)
                seedMinimalLibraryRow(context, databaseName, version)
                val migrated = StudyDatabaseFactory.open(context, databaseName)

                assertEquals(
                    "v$version 迁移后目录行数不是 1——有迁移把数据丢掉了",
                    1,
                    migrated.libraryCatalogCount("", null, null, null, null),
                )
                assertEquals(
                    "v$version 迁移后题面变了——有迁移改写了内容",
                    SEEDED_PROBLEM_MARKDOWN,
                    migrated.libraryCatalogPage(
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
                migrated.close()
            } finally {
                context.deleteDatabase(databaseName)
            }
        }
    }
}
