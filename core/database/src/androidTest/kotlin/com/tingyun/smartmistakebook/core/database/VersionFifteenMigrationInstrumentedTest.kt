package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VersionFifteenMigrationInstrumentedTest {
    @Test
    fun versionFifteenMigratesToCurrentWithoutDestructiveFallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "version-fifteen-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 15)
            val migrated = StudyDatabaseFactory.open(context, databaseName)

            runBlocking { assertEquals(STUDY_DATABASE_VERSION, migrated.readDatabaseVersion()) }
            val conversation = migrated.createTutorConversation(
                CreateTutorConversationDatabaseCommand(
                    conversationId = "conversation-after-v15",
                    anchorKind = "TEXT_ONLY",
                    anchorId = null,
                    anchorRevisionId = null,
                    title = null,
                    createdAtEpochMillis = 1_000L,
                ),
            )

            assertNotNull(conversation)
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionSixteenMigratesToCurrentWithoutDestructiveFallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "version-sixteen-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 16)
            val migrated = StudyDatabaseFactory.open(context, databaseName)

            val conversation = migrated.createTutorConversation(
                CreateTutorConversationDatabaseCommand(
                    conversationId = "conversation-after-v16",
                    anchorKind = "TEXT_ONLY",
                    anchorId = null,
                    anchorRevisionId = null,
                    title = null,
                    createdAtEpochMillis = 1_000L,
                ),
            )

            assertNotNull(conversation)
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
