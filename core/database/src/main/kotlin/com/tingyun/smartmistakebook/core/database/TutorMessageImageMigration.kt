package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v44→45：学生消息附图的规范资产引用表。Lobby 消息可以携带学生选择的图片；
 * 引用行随消息级联删除，孤儿清理按本表判定保留（见 PendingCaptureDao）。
 */
internal val TUTOR_MESSAGE_IMAGE_MIGRATION_44_45 = object : Migration(44, 45) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_message_source_asset` (
                `message_id` TEXT NOT NULL,
                `source_asset_id` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                PRIMARY KEY(`message_id`, `ordinal`),
                FOREIGN KEY(`message_id`) REFERENCES `tutor_message`(`message_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`source_asset_id`) REFERENCES `canonical_source_asset`(`source_asset_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """,
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_message_source_asset_source_asset_id` " +
                "ON `tutor_message_source_asset` (`source_asset_id`)",
        )
    }
}
