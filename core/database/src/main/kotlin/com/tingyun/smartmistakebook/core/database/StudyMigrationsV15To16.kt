package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskLogicalOperationFingerprint

/** Migration 15→16 plus its data backfill helpers. Split from StudyMigrationsV10To18.kt. */

internal val MODEL_TASK_OPERATION_MIGRATION_15_16 = object : Migration(15, 16) {
    override suspend fun migrate(connection: SQLiteConnection) {
        val legacyTasks = connection.readLegacyModelTaskOperations()
        val operationGroups = legacyTasks.groupBy(ModelTaskOperationBackfill::operationFingerprint)

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `model_task_operation` (
                `operation_fingerprint` TEXT NOT NULL,
                `subject_id` TEXT NOT NULL,
                `task_kind` TEXT NOT NULL,
                `dispatch_count` INTEGER NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`operation_fingerprint`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_operation_subject_id_task_kind` " +
                "ON `model_task_operation` (`subject_id`, `task_kind`)",
        )
        connection.insertModelTaskOperations(operationGroups)

        connection.execSQL(
            "CREATE TEMP TABLE `model_task_operation_backfill` (" +
                "`task_id` TEXT NOT NULL PRIMARY KEY, `operation_fingerprint` TEXT NOT NULL)",
        )
        connection.insertModelTaskOperationBackfill(legacyTasks)
        connection.execSQL(
            """
            CREATE TABLE `model_task_v16` (
                `task_id` TEXT NOT NULL,
                `request_id` TEXT NOT NULL,
                `request_fingerprint` TEXT NOT NULL,
                `operation_fingerprint` TEXT NOT NULL,
                `request_snapshot` TEXT NOT NULL,
                `task_kind` TEXT NOT NULL,
                `subject_id` TEXT NOT NULL,
                `tutor_response_ordinal` INTEGER,
                `status` TEXT NOT NULL,
                `state_version` INTEGER NOT NULL,
                `stage` TEXT NOT NULL,
                `user_message` TEXT NOT NULL,
                `attempt_count` INTEGER NOT NULL,
                `provider_snapshot` TEXT,
                `output_snapshot` TEXT,
                `failure_code` TEXT,
                `failure_message` TEXT,
                `failure_retryable` INTEGER,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`task_id`),
                FOREIGN KEY(`operation_fingerprint`)
                    REFERENCES `model_task_operation`(`operation_fingerprint`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `model_task_v16` (
                `task_id`, `request_id`, `request_fingerprint`, `operation_fingerprint`,
                `request_snapshot`, `task_kind`, `subject_id`, `tutor_response_ordinal`,
                `status`, `state_version`, `stage`, `user_message`, `attempt_count`,
                `provider_snapshot`, `output_snapshot`, `failure_code`, `failure_message`,
                `failure_retryable`, `created_at_epoch_millis`, `updated_at_epoch_millis`
            )
            SELECT
                task.`task_id`, task.`request_id`, task.`request_fingerprint`,
                backfill.`operation_fingerprint`, task.`request_snapshot`, task.`task_kind`,
                task.`subject_id`, task.`tutor_response_ordinal`, task.`status`,
                task.`state_version`, task.`stage`, task.`user_message`, task.`attempt_count`,
                task.`provider_snapshot`, task.`output_snapshot`, task.`failure_code`,
                task.`failure_message`, task.`failure_retryable`,
                task.`created_at_epoch_millis`, task.`updated_at_epoch_millis`
            FROM `model_task` AS task
            INNER JOIN `model_task_operation_backfill` AS backfill
                ON backfill.`task_id` = task.`task_id`
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE `model_task_event_v16` (
                `task_id` TEXT NOT NULL,
                `state_version` INTEGER NOT NULL,
                `previous_status` TEXT,
                `next_status` TEXT NOT NULL,
                `stage` TEXT NOT NULL,
                `user_message` TEXT NOT NULL,
                `attempt_count` INTEGER NOT NULL,
                `provider_snapshot` TEXT,
                `output_snapshot` TEXT,
                `failure_code` TEXT,
                `failure_message` TEXT,
                `failure_retryable` INTEGER,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`task_id`, `state_version`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `model_task_event_v16` (
                `task_id`, `state_version`, `previous_status`, `next_status`, `stage`,
                `user_message`, `attempt_count`, `provider_snapshot`, `output_snapshot`,
                `failure_code`, `failure_message`, `failure_retryable`,
                `created_at_epoch_millis`
            )
            SELECT
                `task_id`, `state_version`, `previous_status`, `next_status`, `stage`,
                `user_message`, `attempt_count`, `provider_snapshot`, `output_snapshot`,
                `failure_code`, `failure_message`, `failure_retryable`,
                `created_at_epoch_millis`
            FROM `model_task_event`
            """.trimIndent(),
        )

        connection.execSQL("DROP TABLE `model_task_event`")
        connection.execSQL("DROP TABLE `model_task`")
        connection.execSQL("ALTER TABLE `model_task_v16` RENAME TO `model_task`")
        connection.execSQL(
            """
            CREATE TABLE `model_task_event` (
                `task_id` TEXT NOT NULL,
                `state_version` INTEGER NOT NULL,
                `previous_status` TEXT,
                `next_status` TEXT NOT NULL,
                `stage` TEXT NOT NULL,
                `user_message` TEXT NOT NULL,
                `attempt_count` INTEGER NOT NULL,
                `provider_snapshot` TEXT,
                `output_snapshot` TEXT,
                `failure_code` TEXT,
                `failure_message` TEXT,
                `failure_retryable` INTEGER,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`task_id`, `state_version`),
                FOREIGN KEY(`task_id`) REFERENCES `model_task`(`task_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `model_task_event` (
                `task_id`, `state_version`, `previous_status`, `next_status`, `stage`,
                `user_message`, `attempt_count`, `provider_snapshot`, `output_snapshot`,
                `failure_code`, `failure_message`, `failure_retryable`,
                `created_at_epoch_millis`
            )
            SELECT
                `task_id`, `state_version`, `previous_status`, `next_status`, `stage`,
                `user_message`, `attempt_count`, `provider_snapshot`, `output_snapshot`,
                `failure_code`, `failure_message`, `failure_retryable`,
                `created_at_epoch_millis`
            FROM `model_task_event_v16`
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE `model_task_event_v16`")
        connection.execSQL("DROP TABLE `model_task_operation_backfill`")

        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_model_task_request_id` " +
                "ON `model_task` (`request_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_operation_fingerprint` " +
                "ON `model_task` (`operation_fingerprint`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_subject_id_task_kind` " +
                "ON `model_task` (`subject_id`, `task_kind`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_model_task_subject_id_task_kind_tutor_response_ordinal` " +
                "ON `model_task` (`subject_id`, `task_kind`, `tutor_response_ordinal`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_status_updated_at_epoch_millis` " +
                "ON `model_task` (`status`, `updated_at_epoch_millis`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_event_task_id` " +
                "ON `model_task_event` (`task_id`)",
        )
    }
}


private data class ModelTaskOperationBackfill(
    val taskId: String,
    val operationFingerprint: String,
    val subjectId: String,
    val taskKind: String,
    val attemptCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

private fun SQLiteConnection.readLegacyModelTaskOperations(): List<ModelTaskOperationBackfill> =
    buildList {
        prepare(
            """
            SELECT `task_id`, `request_snapshot`, `subject_id`, `task_kind`, `attempt_count`,
                `created_at_epoch_millis`, `updated_at_epoch_millis`
            FROM `model_task`
            """.trimIndent(),
        ).use { statement ->
            while (statement.step()) {
                val request = ModelTaskCodec.decodeRequest(statement.getText(1))
                val subjectId = statement.getText(2)
                val taskKind = statement.getText(3)
                require(request.input.subjectId == subjectId && request.input.kind.name == taskKind) {
                    "Legacy model task columns disagree with its request snapshot"
                }
                add(
                    ModelTaskOperationBackfill(
                        taskId = statement.getText(0),
                        operationFingerprint = ModelTaskLogicalOperationFingerprint.of(request),
                        subjectId = subjectId,
                        taskKind = taskKind,
                        attemptCount = statement.getLong(4).toInt(),
                        createdAtEpochMillis = statement.getLong(5),
                        updatedAtEpochMillis = statement.getLong(6),
                    ),
                )
            }
        }
    }

private fun SQLiteConnection.insertModelTaskOperations(
    groups: Map<String, List<ModelTaskOperationBackfill>>,
) {
    prepare(
        """
        INSERT INTO `model_task_operation` (
            `operation_fingerprint`, `subject_id`, `task_kind`, `dispatch_count`,
            `created_at_epoch_millis`, `updated_at_epoch_millis`
        ) VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        groups.forEach { (operationFingerprint, rows) ->
            val first = rows.first()
            require(rows.all { it.subjectId == first.subjectId && it.taskKind == first.taskKind }) {
                "Logical model operation hash collision during migration"
            }
            val dispatchCount = rows.sumOf { it.attemptCount.toLong() }
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            statement.bindText(1, operationFingerprint)
            statement.bindText(2, first.subjectId)
            statement.bindText(3, first.taskKind)
            statement.bindLong(4, dispatchCount.toLong())
            statement.bindLong(5, rows.minOf { it.createdAtEpochMillis })
            statement.bindLong(6, rows.maxOf { it.updatedAtEpochMillis })
            statement.step()
            statement.reset()
            statement.clearBindings()
        }
    }
}

private fun SQLiteConnection.insertModelTaskOperationBackfill(
    rows: List<ModelTaskOperationBackfill>,
) {
    prepare(
        "INSERT INTO `model_task_operation_backfill` (`task_id`, `operation_fingerprint`) " +
            "VALUES (?, ?)",
    ).use { statement ->
        rows.forEach { row ->
            statement.bindText(1, row.taskId)
            statement.bindText(2, row.operationFingerprint)
            statement.step()
            statement.reset()
            statement.clearBindings()
        }
    }
}
