package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal const val MODEL_TASK_RECENT_INDEX_NAME =
    "index_model_task_subject_id_task_kind_created_at_epoch_millis_request_id"

internal val MODEL_TASK_RECENT_INDEX_MIGRATION_26_27 = object : Migration(26, 27) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `$MODEL_TASK_RECENT_INDEX_NAME` " +
                "ON `model_task` (`subject_id`, `task_kind`, " +
                "`created_at_epoch_millis`, `request_id`)",
        )
    }
}
