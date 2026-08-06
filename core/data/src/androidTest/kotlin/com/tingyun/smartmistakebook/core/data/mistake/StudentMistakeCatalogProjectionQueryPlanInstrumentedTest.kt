package com.tingyun.smartmistakebook.core.data.mistake

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentMistakeCatalogProjectionQueryPlanInstrumentedTest {
    @Test
    fun explainUsesGenerationKeysetIndexWithoutTemporarySort() {
        SQLiteDatabase.create(null).use { database ->
            database.execSQL(
                """
                CREATE TABLE derived_non_authoritative_catalog_entry(
                  rowid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                  generation_id TEXT NOT NULL,
                  entry_id TEXT NOT NULL,
                  problem_id TEXT NOT NULL,
                  changed_at_epoch_millis INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX derived_catalog_keyset
                ON derived_non_authoritative_catalog_entry(
                  generation_id ASC,
                  changed_at_epoch_millis DESC,
                  problem_id DESC,
                  entry_id DESC
                )
                """.trimIndent(),
            )

            val plan =
                database.rawQuery(
                    """
                    EXPLAIN QUERY PLAN
                    SELECT entry.rowid
                    FROM derived_non_authoritative_catalog_entry AS entry
                    WHERE entry.generation_id = 'generation'
                      AND (
                        entry.changed_at_epoch_millis < 100 OR
                        (
                          entry.changed_at_epoch_millis = 100 AND
                          entry.problem_id < 'problem'
                        ) OR
                        (
                          entry.changed_at_epoch_millis = 100 AND
                          entry.problem_id = 'problem' AND
                          entry.entry_id < 'entry'
                        )
                      )
                    ORDER BY entry.changed_at_epoch_millis DESC,
                             entry.problem_id DESC,
                             entry.entry_id DESC
                    LIMIT 65
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(3))
                    }
                }

            assertTrue(plan.any { it.contains("derived_catalog_keyset") })
            assertFalse(plan.any { it.contains("TEMP B-TREE", ignoreCase = true) })
        }
    }

    @Test
    fun explainUsesFtsVirtualTableBeforeExactEntryLookup() {
        SQLiteDatabase.create(null).use { database ->
            database.execSQL(
                """
                CREATE TABLE derived_non_authoritative_catalog_entry(
                  rowid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                  normalized_search_text TEXT NOT NULL,
                  tokenized_search_text TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE VIRTUAL TABLE derived_non_authoritative_catalog_entry_fts
                USING FTS4(tokenized_search_text, content='derived_non_authoritative_catalog_entry')
                """.trimIndent(),
            )

            val plan =
                database.rawQuery(
                    """
                    EXPLAIN QUERY PLAN
                    SELECT entry.rowid
                    FROM derived_non_authoritative_catalog_entry_fts AS search_fts
                    INNER JOIN derived_non_authoritative_catalog_entry AS entry
                      ON entry.rowid = search_fts.docid
                    WHERE search_fts.tokenized_search_text MATCH '函'
                      AND instr(entry.normalized_search_text, '函数') > 0
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(3))
                    }
                }

            assertTrue(plan.any { it.contains("VIRTUAL TABLE INDEX", ignoreCase = true) })
            assertTrue(plan.any { it.contains("INTEGER PRIMARY KEY", ignoreCase = true) })
        }
    }
}
