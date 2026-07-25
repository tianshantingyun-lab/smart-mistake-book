package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeGroundingMigrationInstrumentedTest {
    @Test
    fun versionEighteenAddsAnEmptyPendingResearchQueue() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "knowledge-grounding-v18-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 18)

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            assertTrue(migrated.observePendingKnowledgeGroundingRequests().first().isEmpty())
            migrated.close()

            val database = SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            try {
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                database.rawQuery(
                    "SELECT grounding_key, status FROM knowledge_grounding_request",
                    null,
                ).use { cursor -> assertEquals(0, cursor.count) }
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionNineteenAddsAnEmptyAuditedResolutionTable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "knowledge-grounding-v19-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 19)

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            assertTrue(
                migrated.readKnowledgeGroundingResolution(
                    "grounding:${"a".repeat(64)}",
                ) == null,
            )
            migrated.close()

            val database = SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            try {
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                database.rawQuery(
                    "SELECT grounding_key, resolution_id FROM knowledge_grounding_resolution",
                    null,
                ).use { cursor -> assertEquals(0, cursor.count) }
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionTwentyAddsAnEmptyReviewedKnowledgeRelationTable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "knowledge-relation-v20-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 20)

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            assertTrue(migrated.readSubjectKnowledgeNodeRelations("MATH", 64).isEmpty())
            migrated.close()

            val database = SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            try {
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                database.rawQuery(
                    "SELECT relation_id, relation_type FROM knowledge_node_relation",
                    null,
                ).use { cursor -> assertEquals(0, cursor.count) }
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionTwentyOneAddsAndLazilyBackfillsTheKnowledgeSearchIndex() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "knowledge-search-v21-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 21)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    """
                    INSERT INTO knowledge_node (
                        knowledge_node_id, stable_code, subject, display_name, canonical_name,
                        node_kind, granularity, aliases_text, boundary_markdown,
                        verification_status, parent_knowledge_node_id, taxonomy_version,
                        created_at_epoch_millis
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any>(
                        "knowledge:math:derivative",
                        "math:derivative",
                        "MATH",
                        "用导数判断函数单调性",
                        "用导数判断函数单调性",
                        "REASONING",
                        "ATOMIC",
                        "导数与单调性",
                        "根据导函数符号判断原函数的单调区间。",
                        "SOURCE_GROUNDED",
                        "test-v1",
                        1L,
                    ),
                )
            }

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            val recalled = migrated.readSubjectKnowledgeRecallCandidates(
                subject = "MATH",
                searchFeatures = KnowledgeSearchFeatureExtractor.fromQuestion(
                    "根据导函数符号求原函数的单调递增区间。",
                ),
                limit = 64,
            )
            migrated.close()

            assertEquals(listOf("knowledge:math:derivative"), recalled.map { it.knowledgeNodeId })
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                database.rawQuery(
                    "SELECT COUNT(*) FROM knowledge_search_feature",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getInt(0) > 0)
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionTwentyThreeAddsTheSubjectScopedRelationLookupIndex() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "knowledge-relation-index-v23-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 23)

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            assertTrue(migrated.readSubjectKnowledgeNodeRelations("MATH", 1).isEmpty())
            migrated.close()

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                database.rawQuery(
                    """
                    SELECT COUNT(*)
                    FROM sqlite_master
                    WHERE type = 'index'
                      AND name = 'index_knowledge_node_relation_subject_dependent_knowledge_node_id'
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
