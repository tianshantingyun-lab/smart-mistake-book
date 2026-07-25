package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val KNOWLEDGE_TEACHING_MATERIAL_MIGRATION_25_26 = object : Migration(25, 26) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_teaching_material` (
                `material_id` TEXT NOT NULL,
                `stable_code` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `material_type` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `summary_markdown` TEXT NOT NULL,
                `applicability_markdown` TEXT NOT NULL,
                `content_markdown` TEXT NOT NULL,
                `boundary_markdown` TEXT NOT NULL,
                `derivation_kind` TEXT NOT NULL,
                `source_id` TEXT NOT NULL,
                `source_locator` TEXT NOT NULL,
                `content_fingerprint` TEXT NOT NULL,
                `reviewed_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`material_id`),
                FOREIGN KEY(`source_id`) REFERENCES `knowledge_source`(`source_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_teaching_material_stable_code` " +
                "ON `knowledge_teaching_material` (`stable_code`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_teaching_material_content_fingerprint` " +
                "ON `knowledge_teaching_material` (`content_fingerprint`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_teaching_material_source_id` " +
                "ON `knowledge_teaching_material` (`source_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_teaching_material_subject_material_type` " +
                "ON `knowledge_teaching_material` (`subject`, `material_type`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_teaching_material_node_binding` (
                `material_id` TEXT NOT NULL,
                `knowledge_node_id` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                PRIMARY KEY(`material_id`, `knowledge_node_id`),
                FOREIGN KEY(`material_id`) REFERENCES `knowledge_teaching_material`(`material_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`knowledge_node_id`) REFERENCES `knowledge_node`(`knowledge_node_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_teaching_material_node_binding_material_id_role` " +
                "ON `knowledge_teaching_material_node_binding` (`material_id`, `role`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_teaching_material_node_binding_knowledge_node_id_role` " +
                "ON `knowledge_teaching_material_node_binding` (`knowledge_node_id`, `role`)",
        )
    }
}
