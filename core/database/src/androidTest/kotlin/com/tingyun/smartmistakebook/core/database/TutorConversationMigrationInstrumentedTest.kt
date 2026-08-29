package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorConversationMigrationInstrumentedTest {
    @Test
    fun versionTwentyEightMigratesToConversationAndMessageTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-conversation-v28-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 28)
            val migrated = StudyDatabaseFactory.open(context, databaseName)
            val conversation = migrated.createTutorConversation(
                CreateTutorConversationDatabaseCommand(
                    conversationId = "conversation-migrated",
                    anchorKind = "TEXT_ONLY",
                    anchorId = null,
                    anchorRevisionId = null,
                    title = "文字讲题",
                    createdAtEpochMillis = 1_000,
                ),
            )
            migrated.appendTutorStudentMessage(
                AppendTutorStudentMessageDatabaseCommand(
                    conversationId = conversation.conversationId,
                    messageId = "message-migrated",
                    ordinal = 1,
                    bodyMarkdown = "这道题为什么选 C？",
                    logicalOperationId = "operation-migrated",
                    createdAtEpochMillis = 1_000,
                ),
            )
            val message = migrated.observeTutorMessages("conversation-migrated").first()

            runBlocking { assertEquals(STUDY_DATABASE_VERSION, migrated.readDatabaseVersion()) }
            assertEquals("conversation-migrated", conversation.conversationId)
            assertNotNull(message.singleOrNull())
            assertEquals("message-migrated", message.single().messageId)
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionTwentyNineAddsLibraryCatalogViewWithoutDestructiveFallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-conversation-v29-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 29)
            val migrated = StudyDatabaseFactory.open(context, databaseName)

            runBlocking { assertEquals(STUDY_DATABASE_VERSION, migrated.readDatabaseVersion()) }
            assertEquals(0, migrated.libraryCatalogCount("", null, null, null, null))
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun archivedConversationKeepsAnchorProblemAndMessagesIntact() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-archive-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            val store = StudyDatabaseFactory.open(context, databaseName)
            store.seedFixture(
                StudySeedBundle(
                    problems = listOf(
                        ProblemSeedRecord(
                            problemId = "problem-archive",
                            canonicalFingerprint = "c".repeat(64),
                            subject = "MATH",
                            createdAtEpochMillis = 1_000,
                        ),
                    ),
                    revisions = listOf(
                        ProblemRevisionSeedRecord(
                            revisionId = "revision-archive",
                            problemId = "problem-archive",
                            revisionNumber = 1,
                            title = "归档保留题",
                            problemMarkdown = "原题保持不变。",
                            questionDocumentSnapshot = null,
                            answerSpecId = null,
                            answerSpecSnapshot = null,
                            answerVerificationStatus = "UNKNOWN",
                            sourceType = "TEST",
                            sourceReference = null,
                            contentFingerprint = "d".repeat(64),
                            createdAtEpochMillis = 1_000,
                        ),
                    ),
                    practiceUnits = listOf(
                        PracticeUnitSeedRecord(
                            practiceUnitId = "practice-archive",
                            problemId = "problem-archive",
                            problemRevisionId = "revision-archive",
                            unitKey = "unit:archive",
                            unitKind = "PROBLEM",
                            title = "归档保留题",
                            promptMarkdown = "原题保持不变。",
                            estimatedSeconds = 120,
                            createdAtEpochMillis = 1_000,
                        ),
                    ),
                    errorBookEntries = listOf(
                        ErrorBookEntrySeedRecord(
                            entryId = "entry-archive",
                            practiceUnitId = "practice-archive",
                            problemId = "problem-archive",
                            currentRevisionId = "revision-archive",
                            sourceKey = null,
                            acceptedAtEpochMillis = 1_000,
                            updatedAtEpochMillis = 1_000,
                        ),
                    ),
                ),
            )
            val conversation = store.createTutorConversation(
                CreateTutorConversationDatabaseCommand(
                    conversationId = "conversation-archive",
                    anchorKind = "PROBLEM_REVISION",
                    anchorId = "problem-archive",
                    anchorRevisionId = "revision-archive",
                    title = "错题讲题",
                    createdAtEpochMillis = 2_000,
                ),
            )
            store.appendTutorStudentMessage(
                AppendTutorStudentMessageDatabaseCommand(
                    conversationId = conversation.conversationId,
                    messageId = "message-archive",
                    ordinal = 1,
                    bodyMarkdown = "这一步怎么来的？",
                    logicalOperationId = "operation-archive",
                    createdAtEpochMillis = 2_000,
                ),
            )

            val archived = store.archiveTutorConversation(
                conversationId = conversation.conversationId,
                updatedAtEpochMillis = 3_000,
            )

            assertEquals("ARCHIVED", archived.status)
            val messages = store.observeTutorMessages(conversation.conversationId).first()
            assertEquals(1, messages.size)
            assertEquals(1, store.countMistakes())
            assertEquals(
                "原题保持不变。",
                store.libraryCatalogPage(
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
            store.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun deletedConversationRemovesMessagesButKeepsAnchorProblemAndLibrary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-delete-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            val store = StudyDatabaseFactory.open(context, databaseName)
            store.seedFixture(
                StudySeedBundle(
                    problems = listOf(
                        ProblemSeedRecord(
                            problemId = "problem-delete",
                            canonicalFingerprint = "c".repeat(64),
                            subject = "MATH",
                            createdAtEpochMillis = 1_000,
                        ),
                    ),
                    revisions = listOf(
                        ProblemRevisionSeedRecord(
                            revisionId = "revision-delete",
                            problemId = "problem-delete",
                            revisionNumber = 1,
                            title = "删除保留题",
                            problemMarkdown = "删除会话后原题仍保留。",
                            questionDocumentSnapshot = null,
                            answerSpecId = null,
                            answerSpecSnapshot = null,
                            answerVerificationStatus = "UNKNOWN",
                            sourceType = "TEST",
                            sourceReference = null,
                            contentFingerprint = "d".repeat(64),
                            createdAtEpochMillis = 1_000,
                        ),
                    ),
                    practiceUnits = listOf(
                        PracticeUnitSeedRecord(
                            practiceUnitId = "practice-delete",
                            problemId = "problem-delete",
                            problemRevisionId = "revision-delete",
                            unitKey = "unit:delete",
                            unitKind = "PROBLEM",
                            title = "删除保留题",
                            promptMarkdown = "删除会话后原题仍保留。",
                            estimatedSeconds = 120,
                            createdAtEpochMillis = 1_000,
                        ),
                    ),
                    errorBookEntries = listOf(
                        ErrorBookEntrySeedRecord(
                            entryId = "entry-delete",
                            practiceUnitId = "practice-delete",
                            problemId = "problem-delete",
                            currentRevisionId = "revision-delete",
                            sourceKey = null,
                            acceptedAtEpochMillis = 1_000,
                            updatedAtEpochMillis = 1_000,
                        ),
                    ),
                ),
            )
            val conversation = store.createTutorConversation(
                CreateTutorConversationDatabaseCommand(
                    conversationId = "conversation-delete",
                    anchorKind = "PROBLEM_REVISION",
                    anchorId = "problem-delete",
                    anchorRevisionId = "revision-delete",
                    title = "错题讲题",
                    createdAtEpochMillis = 2_000,
                ),
            )
            store.appendTutorStudentMessage(
                AppendTutorStudentMessageDatabaseCommand(
                    conversationId = conversation.conversationId,
                    messageId = "message-delete",
                    ordinal = 1,
                    bodyMarkdown = "这一步怎么来的？",
                    logicalOperationId = "operation-delete",
                    createdAtEpochMillis = 2_000,
                ),
            )

            store.deleteTutorConversation(conversation.conversationId)

            assertEquals(
                0,
                store.observeTutorMessages(conversation.conversationId).first().size,
            )
            assertEquals(
                null,
                store.observeTutorConversation(conversation.conversationId).first(),
            )
            assertEquals(1, store.countMistakes())
            assertEquals(
                "删除会话后原题仍保留。",
                store.libraryCatalogPage(
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
            store.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun savedDraftSurvivesReopenAndClearRemovesIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-draft-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            var store = StudyDatabaseFactory.open(context, databaseName)
            val conversation = store.createTutorConversation(
                CreateTutorConversationDatabaseCommand(
                    conversationId = "conversation-draft",
                    anchorKind = "TEXT_ONLY",
                    anchorId = null,
                    anchorRevisionId = null,
                    title = null,
                    createdAtEpochMillis = 1_000,
                ),
            )
            store.saveTutorConversationDraft(
                conversationId = conversation.conversationId,
                draft = "这道题我想先自己算一遍",
                updatedAtEpochMillis = 2_000,
            )
            assertEquals(
                "这道题我想先自己算一遍",
                store.observeTutorConversation(conversation.conversationId).first()?.studentDraft,
            )
            store.close()

            store = StudyDatabaseFactory.open(context, databaseName)
            assertEquals(
                "这道题我想先自己算一遍",
                store.observeTutorConversation(conversation.conversationId).first()?.studentDraft,
            )
            store.clearTutorConversationDraft(
                conversationId = conversation.conversationId,
                updatedAtEpochMillis = 3_000,
            )
            assertEquals(
                null,
                store.observeTutorConversation(conversation.conversationId).first()?.studentDraft,
            )
            store.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionThirtyMigratesToCurrentWithDraftColumn() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-draft-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 30)
            val migrated = StudyDatabaseFactory.open(context, databaseName)

            runBlocking { assertEquals(STUDY_DATABASE_VERSION, migrated.readDatabaseVersion()) }
            val conversation = migrated.createTutorConversation(
                CreateTutorConversationDatabaseCommand(
                    conversationId = "conversation-after-v30",
                    anchorKind = "TEXT_ONLY",
                    anchorId = null,
                    anchorRevisionId = null,
                    title = null,
                    createdAtEpochMillis = 1_000,
                ),
            )
            migrated.saveTutorConversationDraft(
                conversationId = conversation.conversationId,
                draft = "迁移后仍可保存草稿",
                updatedAtEpochMillis = 2_000,
            )
            assertEquals(
                "迁移后仍可保存草稿",
                migrated.observeTutorConversation(conversation.conversationId)
                    .first()?.studentDraft,
            )
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
