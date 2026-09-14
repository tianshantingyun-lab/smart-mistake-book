package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v44→45：消息附图引用表。迁移后要能建立引用，且孤儿清理必须按新表保留
 * 被消息引用的图片（P0 红线：学生发的图不能被后台静默删掉）。
 */
@RunWith(AndroidJUnit4::class)
class TutorMessageImageMigrationInstrumentedTest {
    @Test
    fun versionFortyFourMigratesToMessageImageLinks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-message-image-v44-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 44)
            val migrated = StudyDatabaseFactory.open(context, databaseName)
            val conversation = migrated.createTutorConversation(
                CreateTutorConversationDatabaseCommand(
                    conversationId = "conversation-images",
                    anchorKind = "TEXT_ONLY",
                    anchorId = null,
                    anchorRevisionId = null,
                    title = null,
                    createdAtEpochMillis = 1_000,
                ),
            )
            migrated.registerCanonicalSourceAsset(
                CanonicalSourceAssetRecord(
                    sourceAssetId = "message-image-asset",
                    contentSha256 = "a".repeat(64),
                    relativePath = "source-assets/${"a".repeat(64)}.jpg",
                    mimeType = "image/jpeg",
                    byteSize = 1_024,
                    width = 1080,
                    height = 1440,
                    sourceType = StudyDbValue.SourceAssetType.PHOTO_PICKER,
                    createdAtEpochMillis = 1_000,
                ),
            )
            migrated.appendTutorStudentMessage(
                AppendTutorStudentMessageDatabaseCommand(
                    conversationId = conversation.conversationId,
                    messageId = "message-with-image",
                    ordinal = 1,
                    bodyMarkdown = "看看这道题",
                    logicalOperationId = "operation-images",
                    createdAtEpochMillis = 1_000,
                    sourceImageAssetIds = listOf("message-image-asset"),
                ),
            )

            val links = migrated.readTutorMessageSourceAssets(listOf("message-with-image"))
            assertEquals(1, links.size)
            assertEquals("message-image-asset", links.single().sourceAssetId)
            // 被消息引用的资产不是孤儿：清理判定必须保留它。
            assertTrue(
                migrated.readUnreferencedCanonicalAssets()
                    .none { it.sourceAssetId == "message-image-asset" },
            )

            // 删除会话（级联删除消息与引用）后，资产回到可清理状态。
            migrated.deleteTutorConversation(conversation.conversationId)
            assertEquals(
                0,
                migrated.readTutorMessageSourceAssets(listOf("message-with-image")).size,
            )
            assertTrue(
                migrated.readUnreferencedCanonicalAssets()
                    .any { it.sourceAssetId == "message-image-asset" },
            )
            assertEquals(STUDY_DATABASE_VERSION, migrated.readDatabaseVersion())
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
