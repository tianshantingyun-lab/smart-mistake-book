package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val KNOWLEDGE_RESEARCH_REVIEW_MIGRATION_24_25 = object : Migration(24, 25) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_research_review_bundle` (
                `bundle_id` TEXT NOT NULL,
                `grounding_key` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `query` TEXT NOT NULL,
                `expected_parent_knowledge_display_name` TEXT NOT NULL,
                `related_question_count` INTEGER NOT NULL,
                `workflow_version` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `reviewer_reference` TEXT,
                `decision_note` TEXT,
                `reviewed_at_epoch_millis` INTEGER,
                `applied_pack_fingerprint` TEXT,
                `applied_at_epoch_millis` INTEGER,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`bundle_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_knowledge_research_review_bundle_status_created_at_epoch_millis` " +
                "ON `knowledge_research_review_bundle` (`status`, `created_at_epoch_millis`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_knowledge_research_review_bundle_grounding_key_status` " +
                "ON `knowledge_research_review_bundle` (`grounding_key`, `status`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_research_review_source` (
                `bundle_id` TEXT NOT NULL,
                `source_ordinal` INTEGER NOT NULL,
                `canonical_source_uri` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `publisher` TEXT,
                `source_type` TEXT NOT NULL,
                `license_status` TEXT NOT NULL,
                `search_rank` INTEGER NOT NULL,
                `content_type` TEXT NOT NULL,
                `content_length_bytes` INTEGER NOT NULL,
                `content_fingerprint` TEXT NOT NULL,
                `verified_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`bundle_id`, `source_ordinal`),
                FOREIGN KEY(`bundle_id`) REFERENCES `knowledge_research_review_bundle`(`bundle_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_knowledge_research_review_source_bundle_id_canonical_source_uri` " +
                "ON `knowledge_research_review_source` (`bundle_id`, `canonical_source_uri`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_knowledge_research_review_source_content_fingerprint` " +
                "ON `knowledge_research_review_source` (`content_fingerprint`)",
        )
    }
}
